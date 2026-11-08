# DBeaver Data API

The plugin listens on `http://127.0.0.1:8765` by default and reflects into the
running DBeaver workspace. Existing datasource/schema/row/execute routes use
DBeaver's configured credentials, SSL, tunnels, and provider behavior. Use the
bundled `scripts/dbeaver_api.py`; it resolves friendly names to exact native
project/datasource IDs and sends only the selectors required by each endpoint.

## Loopback access and trust boundary

The API has no plugin-managed authentication and has no token or separate
availability setting. The server listens only on `127.0.0.1`; database
credentials and provider authentication remain in DBeaver. The bundled client
accepts only loopback IP URL destinations, never follows redirects, and
disables environment proxies. Configure its destination with `--base-url` or
`DBEAVER_DATA_API_URL` if needed. Loopback binding and `Origin` rejection are
not authentication or protection from other local processes able to access
the listener. Any external protection must cover direct access to that
listener.

The existing `Origin` rejection applies to the discovery,
connection-management, operation-status, and runtime-evaluation routes:
`GET /projects`, `GET /drivers`, `POST /datasources`,
`GET /datasources/<id>`, `POST /datasources/<id>/edit`,
`POST /datasources/<id>/test`, `GET /connection-operations/<operationId>`,
and `POST /runtime/evaluate`. A supplied `Origin` is rejected on those
routes; it is not broadened to the other data routes. The client itself sends
no `Origin`.

## Selecting exact native targets

`GET /projects` returns `{"items":[{"id":"...","name":"...","active":true,"writable":true}],"activeProjectId":"..."}`.
The `activeProjectId` describes the workspace's current active project. It does
not guarantee the target project that DBeaver's native wizard selects: that
selection can follow the navigator. A guided create/edit must be reviewed in
the native UI, and the operation/inspection identity must confirm the intended
`projectId` after saving.

The CLI accepts exactly one `--project <exact-name>` or `--project-id
<native-id>` selector for project operations, and additionally exactly one
`--ds <exact-name>` or `--ds-id <native-id>` for a datasource operation.
Friendly project names resolve through `/projects`; a supplied native project
ID is used directly. Datasource names/IDs are checked against
`GET /datasources?projectId=<native-id>`. Missing or ambiguous friendly names
fail before POST. Native IDs are never replaced by a first/active-project
fallback; the server rejects unknown or wrong-project IDs. Creation preflights
`/projects` even for a supplied ID so unknown or non-writable projects fail
before the POST. Protected projects retain DBeaver's native unlock flow;
complete any required unlock in DBeaver.

## Endpoint contracts

URI query keys and values are decoded once by the server. An unescaped `+`
becomes a space; percent-encode a literal plus as `%2B`.

### `GET /projects`

No plugin-managed authentication. Returns the workspace projects and active
native ID described above. The existing `Origin` check rejects a supplied
`Origin` header on this discovery route.

### `GET /datasources?projectId=<native-project-id>`

Requires `projectId`; there is no implicit first-project behavior. Returns an
array of datasource identities with native `id`, `name`, `providerId`,
`driverId`, `projectId`, `projectName`, and `connected`. No plugin-managed
authentication or `Origin` rejection applies to this data route. Unknown
projects return `404`; a missing ID is `400`.

### `GET /drivers?projectId=<native-project-id>`

No plugin-managed authentication; the existing administrative `Origin` check applies. Returns `{"items":[...],"project":{"id":"...","name":"..."}}`
for drivers enabled and usable in the selected runtime project. Optional
`providerId` and `driverId` must be supplied together. Discovery metadata
includes IDs, configuration types, supported fields, advertised creation
modes, and non-secret property descriptors. It is not a product allowlist and
does not fully describe custom native configuration pages.

### `POST /datasources`

No plugin-managed authentication. This strict-JSON request has `projectId`,
`mode` (`guided` or `direct`), `providerId`, `driverId`, and optional `name`
and `connection`; the existing administrative `Origin` check applies.
The CLI requires a project selector and adds `projectId` without re-serializing
the original JSON, preserving numeric token precision. An existing body
`projectId` must exactly match the selected project. The top-level request
allows only those fields.

`connection` accepts only safe, supported non-secret values (`host`, `port`,
`database`, `server`, `url`, `properties`, and `providerProperties`). Never
include credentials, provider authentication tokens, secret maps, usernames,
passwords, or credential-bearing URL userinfo. JSON must have UTF-8 encoding,
no duplicate keys at any nesting depth, nesting at most 100, numeric tokens at
most 128 characters, and exponent magnitude at most 10,000. The body limit is
1 MiB. Invalid JSON failures do not echo request content.

Guided creation returns `202 {"operationId":"...","kind":"create","state":"queued"}`.
This only queues the native wizard; the user selects the target project,
reviews settings, enters credentials in DBeaver, and completes/cancels it.
Every enabled driver retains guided mode. Direct mode is available only when
the selected driver advertises it, accepts only bounded validated native JDBC
URL configuration, and returns `201` only after persistence with
`created:true`, `saved:true`, `connected:false`, and datasource identity. Direct
persistence is not a connectivity test.

Never replay a creation POST after an ambiguous network/server failure. Check
the operation status and project datasource list first; a failed/cancelled
wizard can leave a partial registration.

### `GET /datasources/<datasourceId>?projectId=<native-project-id>`

No plugin-managed authentication; the existing administrative `Origin` check
applies. Returns
`{"datasource":<identity>,"saved":<boolean>,"connected":<boolean>,"configuration":<safe-settings>}`.
The configuration snapshot contains explicitly selected non-secret settings
only. It never exports arbitrary properties, passwords, tokens, usernames, or
raw sensitive URLs. `saved` and `connected` are distinct facts; neither by
itself proves a fresh test passed.
In URL mode, the configured URL controls the endpoint; native manual host/port
defaults may remain in the snapshot without being used. Unknown/opaque or
potentially sensitive URL forms are omitted.

### `POST /datasources/<datasourceId>/edit?projectId=<native-project-id>`

No plugin-managed authentication; the existing administrative `Origin` check
applies. This strict-JSON request contains only optional `name` and
`connection` fields, using the same non-secret settings rules as creation. It
opens the native editor and returns
`202 {"operationId":"...","kind":"edit","state":"queued"}`. The user
reviews and saves in DBeaver; editing does not create/delete another profile.

### `POST /datasources/<datasourceId>/test?projectId=<native-project-id>`

No plugin-managed authentication; the existing administrative `Origin` check
applies. This strict-JSON request contains only an optional positive `timeout`
in seconds. If omitted, the server's configured default applies. The client
accepts an explicit value from 1 through 3600 seconds; the server enforces the
configured maximum `janf.dataExport.connectionTestTimeoutSeconds` (unset default
30 seconds; the configured value is also the maximum, capped at 3600 seconds).
Returns
`202 {"operationId":"...","kind":"test","state":"queued"}`. Testing uses
DBeaver's native connection/session path; it is not a mock, ping, or synthetic
success. Background tests refuse configured startup SQL/shell hooks, shared
credentials, and enabled distributed handlers that require native confirmation.
Use DBeaver's native UI for those cases; the API does not silently persist a
profile or execute hooks solely to make a test run.

The deadline is cooperative, not a hard cancellation guarantee. Initial
connection setup or provider/native work may take longer; cleanup and temporary
disconnect must be observed before a successful result is reported.

### `GET /connection-operations/<operationId>`

No plugin-managed authentication; the existing administrative `Origin` check
applies. Response fields include `operationId`, `kind` (`create`, `edit`,
`test`), `state`, `created`, `saved`, `connected`, and optional `datasource`,
`test`, `diagnostic`, `code`, `error`, and `cleanup`. States are `queued`,
`running`, `awaiting_input`, `saved`, `succeeded`, `cancelled`, and `failed`.
A create or edit completes only at `state:"saved"` with saved profile identity.
A successful test completes only at `state:"succeeded"` with the original
datasource identity and `test.passed:true`. `saved` and `connected` remain
independent state flags; `connected` describes the profile's original state
and is never inferred from `test.passed`.
The `test` object reports `passed`, native `session` proof, `elapsedMillis`
(native connection time), `totalElapsedMillis` (including cleanup), and
available `server`/`driver` name/version metadata. A test owns a temporary
unregistered descriptor, not the user's existing connection.

Cancelled, failed, contradictory, or waited-to-deadline-pending results exit
nonzero. The CLI emits documented structured residual fields (including safe
partial identity and diagnostics) to stderr when available. Create failures
may leave a profile; edit/test never delete an existing profile. `--wait`
performs GET polling only, defaults to a 300-second deadline, accepts a bounded
1–3600 second `--wait-timeout`, and does not turn pending into success. Without
`--wait`, queued/running/awaiting-input remains visibly pending. Operations are
retained for a bounded period; unknown/expired IDs return `404`.

Diagnostic categories are non-secret and should remain distinct. In
particular, MongoDB error 20 “Cannot use local database through mongos” is a
provider-configuration/default-database issue, not an authentication failure.
Do not treat a native interaction requirement or cleanup failure as a passed
test.
Diagnostic fields include `category`, safe `message`/`code`, and available
`sqlState`, `vendorCode`, `exceptionType`, or recognized `missingClass`.
Unclassified failures remain `provider_error`; they are not assumed to be
configuration or authentication problems.

### `GET /schema` and `POST /execute`

Both require native `projectId` and `dsId` query parameters. Name-only `ds`
selectors are removed; missing identifiers return `400`, unknown project or
datasource returns `404`. Existing response shapes, metadata, pagination, and
isolated execution behavior otherwise remain intact.

`GET /schema` accepts optional `parent` and `mode=full`. Incremental node IDs
are schema-path-v2 IDs containing both native IDs; follow IDs returned by the
server. Old node IDs fail with rediscovery guidance. Full mode is a flattened
scope list, not a navigable hierarchy. `/rows` continues to accept a returned
`node` ID (it carries the project and datasource IDs); `limit` is 1–1000 and
`offset` is nonnegative.

`POST /execute` accepts a strict JSON body such as `{"sql":"SELECT 1 AS ok"}`.
Existing optional query parameters include `limit`, `timeout`, and
MongoDB-specific `database`. MongoDB's explicit `database` targets an
application database and must not be confused with the authentication database
`authSource`. The default/URI path database is a separate connection setting.
MongoDB SQL execution uses DBeaver's limited SQL-to-document adapter, not a
Mongo shell. Each execution owns/closes an isolated context; requests do not
share a transaction and provider auto-commit rules apply.
Schema metadata may be cached by the provider after DDL. Refresh it in DBeaver
or verify the actual provider catalog through a scoped read before concluding
that an object remains. Schema discovery does not establish a fresh snapshot.

### `POST /runtime/evaluate`

This route is always registered on the existing loopback server. It is an
explicitly authorized, privileged Java evaluation facility, not a connection
inspection endpoint. The only accepted input fields are required nonblank
`code` and optional `args`; `args` defaults to an empty object. There are no
project, datasource, language, or timeout fields, no discovery step, no
availability option, and no token/authentication feature. The strict
`application/json` request is limited to 1 MiB, must be valid UTF-8, rejects
duplicate keys, and has maximum nesting depth 100.

`code` is a Java 17 **method body**, not a class or import block. The generated
method receives `Map<String,Object> args` and `RuntimeEvaluationContext
runtime`, returns `Object`, and may throw `Exception`. The existing request
parser represents JSON numbers as `BigDecimal`. The runtime helpers
`runtime.c(name)`, `providerClass(anchor,name)`,
`call(target,method,Object...args)`, and `construct(type,Object...args)` are
available to the method body. For example, this request returns the decimal
argument increased by one:

```json
{
  "code": "return ((java.math.BigDecimal) args.get(\"amount\")).add(new java.math.BigDecimal(\"1\"));",
  "args": {"amount": 1.25}
}
```

Save that UTF-8 JSON as `runtime-request.json` and invoke exactly once:

```powershell
python .\skills\dbeaver-database-operator\scripts\dbeaver_api.py runtime-evaluate --request-file "runtime-request.json"
```

The CLI also accepts `--request-file -` to read UTF-8 JSON from stdin. It
validates `code`, `args`, and unknown fields before sending the original JSON
text in exactly one POST, preserving request number tokens. The command has no
project or datasource selector and performs no target discovery. On success it
requires a `200` JSON object containing `result` (including `result: null`),
validates the result using decimal-preserving number parsing without assuming
it is an object, and prints the original response JSON text so numeric tokens
remain intact. Global
`--http-timeout` controls only client waiting. A timeout, connection loss,
truncated response, or other ambiguous transport/read failure does not stop
the snippet. Never automatically retry: it may still be running or may already
have changed DBeaver state.

The in-process compiler requires `javax.tools.JavaCompiler` (`jdk.compiler`)
in the DBeaver JVM; the current Temurin 25.0.3 runtime includes it. Source is
compiled with `--release 17` and annotation processing disabled. Because the
plugin is bootstrap-loaded, a narrow child-first bridge loader loads only the
compiler bridge and its nested classes; its parent is the plugin loader when
available, otherwise the platform loader. The bootstrap evaluator accesses
that bridge reflectively through JDK-base types, not typed `javax.tools`
references. The bridge is reused, while each evaluation gets a fresh snippet
classloader. Instrumentation-appended bootstrap JARs expose classes but not
resources: startup supplies the agent's plugin directory, and discovery selects
the archive by its `JANF-Plugin-Entry` manifest identity, excludes disabled JARs,
and rejects duplicate matches. Ordinary classpath/classes-root use without an
agent environment resolves class-resource URLs. Source and class output remain
in memory; there are no source/class temporary files or generated-class cache.
Missing compiler, compiler archive, or native runtime loader returns `503`.

The result is `200 {"result": <value>}`; `result` may be JSON `null`. Only null,
strings, booleans, finite `Byte`, `Short`, `Integer`, `Long`, `Float`,
`Double`, `BigInteger`, or `BigDecimal`, lists, and maps with string keys are
accepted recursively. Arbitrary objects and other `Number` types are rejected.
Cycles, non-finite numbers, invalid UTF-16, or container depth above 100
(including the result envelope at depth one; an object/list result starts at
depth two and scalar leaves add no container depth) produce `422` with phase
`result`. The serialized UTF-8 response may not exceed 1 MiB including the
envelope; shared acyclic references are allowed. Result validation occurs
after code returns, so this failure does not undo effects. The evaluator does
not hand arbitrary objects to Gson, capture/redirect stdout or stderr, or
automatically log request code or arguments.

At most one compilation, execution, or result serialization is active.
Overlapping work fails fast with `409` phase `compile`. Evaluation runs
synchronously on the existing HTTP background worker, without a new executor,
persistent REPL, hard server timeout, or cancellation route. The thread context
classloader is restored when evaluation ends. Code runs in the DBeaver process
with access available to that process: there is no sandbox, credential
isolation, transaction, or rollback guarantee. A safe existing-connection
configuration snapshot remains explicitly filtered; this arbitrary-code
endpoint does not inherit that safety boundary. The endpoint does not
automatically marshal snippet code to SWT/UI thread; code requiring native UI
access must explicitly dispatch through DBeaver's UI facilities.

Responses for compile, execution, or result-contract failures use
`{"error":"<generic message>","phase":"compile|execute|result",...}`.
Compile failures return `422` phase `compile` and may include at most 20
diagnostics. Each diagnostic's snippet-relative line/column excludes the four
generated wrapper header lines; its diagnostic code is at most 120 characters
and its message at most 240 characters. Diagnostics do not echo the full
source. Result-contract failures return `422` phase `result`; execution
exceptions return `500` phase `execute` and may include `exceptionType`, never
raw exception messages, stack traces, source, or arguments. Missing native
runtime loader/compiler returns `503` phase `compile`. The existing
administrative `Origin` rejection also applies to this route. No result or
error body is a promise of rollback if user code has already run.

This capability must be used only when the user explicitly authorizes
execution of the requested code. A request to inspect, explain, count, or
diagnose alone does not authorize JVM code or mutations. Never include
credentials or secrets in request arguments or return values; the lack of
credential isolation means code may still access sensitive DBeaver process
state.

## Common statuses and safety

- `400`: missing/invalid parameters, JSON, or project/datasource selector.
- `403`: a supplied `Origin` header on an administrative discovery, connection-management, operation-status, or runtime-evaluation route.
- `404`: unknown project, datasource, driver, node, or operation.
- `405`: unsupported method.
- `413`: body exceeds 1 MiB.
- `415`: unsupported request content type.
- `422`: unsupported direct-mode configuration, runtime snippet compilation failure, or invalid runtime result.
- `409`: lifecycle/target conflict or a runtime evaluation is already active.
- `500`: provider failure, `/execute` failure, or runtime snippet execution exception.
- `503`: DBeaver UI/native runtime unavailable or runtime compiler unavailable.
- `504`: existing `/execute` timeout; runtime evaluation has no hard server timeout.

The API has no plugin-managed authentication, token, or runtime-availability
setting and must remain loopback-only. Loopback and the `Origin` check do not
protect against other local processes that can access the listener; any
external protection must cover direct listener access. The bundled client
accepts only loopback IP URLs, never follows redirects, and ignores
environment proxies. A successful save is not proof of connectivity; a test
success is not proof that the profile remains connected. Safe existing
connection snapshots contain explicitly filtered non-secret settings, but
runtime evaluation executes arbitrary code in-process without a sandbox,
credential isolation, transaction, or rollback guarantee. Use it only with
explicit user authorization. Do not infer a mutation from a request that only
asks to inspect, explain, count, or diagnose. On an ambiguous mutation result,
inspect before deciding what to do next; never automatically replay a
runtime-evaluation request after timeout or disconnect because it may still be
running or may already have changed state.

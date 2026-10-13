# DBeaver Data API

The plugin listens on `http://127.0.0.1:8765` by default and reflects into the
running DBeaver workspace. Existing datasource/schema/row/execute routes use
DBeaver's configured credentials, SSL, tunnels, and provider behavior. Use the
bundled `scripts/dbeaver_api.py`; it resolves friendly names to exact native
project/datasource IDs and sends only the selectors required by each endpoint.

## Loopback access and trust boundary

The API has no plugin-managed authentication. The server listens only on
`127.0.0.1`; database credentials and provider authentication remain in
DBeaver. The bundled client accepts only loopback IP URL destinations, never
follows redirects, and disables environment proxies. Configure its destination
with `--base-url` or `DBEAVER_DATA_API_URL` if needed.

The existing `Origin` rejection is retained only for the current discovery,
connection-management, and operation-status routes: `GET /projects`,
`GET /drivers`, `POST /datasources`, `GET /datasources/<id>`,
`POST /datasources/<id>/edit`, `POST /datasources/<id>/test`, and
`GET /connection-operations/<operationId>`. A supplied `Origin` is rejected on
those routes; the API does not broaden that check to data routes. The client
itself sends no `Origin`.

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

## Common statuses and safety

- `400`: missing/invalid parameters, JSON, or project/datasource selector.
- `403`: a supplied `Origin` header on an administrative discovery, connection-management, or operation-status route.
- `404`: unknown project, datasource, driver, node, or operation.
- `405`: unsupported method.
- `413`: body exceeds 1 MiB.
- `415`: connection creation used a non-JSON content type.
- `422`: unsupported direct-mode configuration.
- `409`: lifecycle or target conflict.
- `500`: provider or execution failure.
- `503`: DBeaver UI/native access or runtime unavailable.
- `504`: execution timeout.

The API has no plugin-managed authentication and must remain loopback-only. The bundled client accepts only loopback IP URLs, never follows redirects, and ignores environment proxies. A successful save is not proof of connectivity; a test success is not proof that the profile remains connected. On any ambiguous mutation result, inspect the operation/profile before deciding what to do next.

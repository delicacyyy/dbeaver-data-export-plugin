# DBeaver Data API

The plugin listens on `http://127.0.0.1:8765` by default and reflects into the
running DBeaver workspace. Existing datasource/schema/row/execute routes use
DBeaver's configured credentials, SSL, tunnels, and provider behavior. Use the
bundled `scripts/dbeaver_api.py`; it resolves friendly names to exact native
project/datasource IDs and sends only the selectors required by each endpoint.

## Administrative access and trust boundary

`GET /projects`, `GET /drivers`, `POST /datasources`,
`GET /datasources/<id>`, `POST /datasources/<id>/edit`,
`POST /datasources/<id>/test`, and
`GET /connection-operations/<operationId>` require a bearer token. Configure
the DBeaver JVM property `janf.dataExport.authTokenFile` with a local token-file
path. The client reads that file using `--api-token-file <path>` or
`DBEAVER_DATA_API_TOKEN_FILE`; never place token contents in a command line,
JVM arguments, request file, or chat. The client sends the token only to a
loopback IP URL, refuses administrative redirects, and sends no bearer token
to legacy data routes. Every administrative route rejects any supplied
`Origin`; the client itself does not send one.

If the file is missing, unreadable, empty, or insecure, administrative routes
return `503`. Runtime acceptance is a regular non-symlink file owned by the
DBeaver user, containing an ASCII RFC 6750 b64token and optionally one trailing
LF or CRLF, with total size at most 4096 bytes. POSIX permissions must allow the
owner to read and deny group/other access (owner execute is allowed). Windows
requires current-user ownership/read access; non-owner ALLOW ACL entries are
accepted only for `NT AUTHORITY\\SYSTEM` and `BUILTIN\\Administrators`.
Unsupported permission inspection disables the administrative routes.

A Windows PowerShell setup may use this pattern. It secures the ACL before
writing a random value and never prints the token:

```powershell
$tokenPath = Join-Path $env:LOCALAPPDATA 'ja-netfilter\dbeaver-api.token'
$null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($tokenPath))
$null = [IO.File]::WriteAllBytes($tokenPath, [byte[]]@())
$owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = [Security.AccessControl.FileSecurity]::new()
$acl.SetAccessRuleProtection($true, $false)
$acl.SetOwner($owner)
$rights = [Security.AccessControl.FileSystemRights]::Read -bor `
  [Security.AccessControl.FileSystemRights]::Write
$rule = [Security.AccessControl.FileSystemAccessRule]::new(
  $owner, $rights, [Security.AccessControl.AccessControlType]::Allow
)
$acl.SetAccessRule($rule)
Set-Acl -LiteralPath $tokenPath -AclObject $acl
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$bytes = New-Object byte[] 32
$rng.GetBytes($bytes)
$token = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
[IO.File]::WriteAllText($tokenPath, $token, [Text.Encoding]::ASCII)
[Array]::Clear($bytes, 0, $bytes.Length)
$rng.Dispose()
$token = $null
$env:DBEAVER_DATA_API_TOKEN_FILE = $tokenPath
```

Use the same path for `-Djanf.dataExport.authTokenFile=<path>` in the DBeaver
launch configuration. The JVM property and client environment variable contain
paths only.

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
Friendly project names resolve through authenticated `/projects`; a supplied
native project ID is used directly. Datasource names/IDs are checked against
`GET /datasources?projectId=<native-id>` without a bearer token. Missing or
ambiguous friendly names fail before POST. Native IDs are never replaced by a
first/active-project fallback; the server rejects unknown or wrong-project IDs.
Creation preflights `/projects` even for a supplied ID so unknown or
non-writable projects fail before the POST.

## Endpoint contracts

URI query keys and values are decoded once by the server. An unescaped `+`
becomes a space; percent-encode a literal plus as `%2B`.

### `GET /projects`

Administrative. Returns the workspace projects and active native ID described
above. No `Origin` header is allowed.

### `GET /datasources?projectId=<native-project-id>`

Requires `projectId`; there is no implicit first-project behavior. Returns an
array of datasource identities with native `id`, `name`, `providerId`,
`driverId`, `projectId`, `projectName`, and `connected`. This legacy data route
does not use a bearer token. Unknown projects return `404`; a missing ID is a
`400`.

### `GET /drivers?projectId=<native-project-id>`

Administrative. Returns `{"items":[...],"project":{"id":"...","name":"..."}}`
for drivers enabled and usable in the selected runtime project. Optional
`providerId` and `driverId` must be supplied together. Discovery metadata
includes IDs, configuration types, supported fields, advertised creation
modes, and non-secret property descriptors. It is not a product allowlist and
does not fully describe custom native configuration pages.

### `POST /datasources`

Administrative strict-JSON request. A request has `projectId`, `mode` (`guided`
or `direct`), `providerId`, `driverId`, and optional `name` and `connection`.
The CLI requires a project selector and adds `projectId` without re-serializing
the original JSON, preserving numeric token precision. An existing body
`projectId` must exactly match the selected project. The top-level request
allows only those fields.

`connection` accepts only safe, supported non-secret values (`host`, `port`,
`database`, `server`, `url`, `properties`, and `providerProperties`). Never
include credentials, secret maps, username/password, or credential-bearing URL
userinfo. JSON must have UTF-8 encoding, no duplicate keys at any nesting
depth, nesting at most 100, numeric tokens at most 128 characters, and exponent
magnitude at most 10,000. The body limit is 1 MiB. Invalid JSON failures do not
echo request content.

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

Administrative safe inspection. Returns
`{"datasource":<identity>,"saved":<boolean>,"connected":<boolean>,"configuration":<safe-settings>}`.
The configuration snapshot contains explicitly selected non-secret settings
only. It never exports arbitrary properties, passwords, tokens, usernames, or
raw sensitive URLs. `saved` and `connected` are distinct facts; neither by
itself proves a fresh test passed.
In URL mode, the configured URL controls the endpoint; native manual host/port
defaults may remain in the snapshot without being used. Unknown/opaque or
potentially sensitive URL forms are omitted.

### `POST /datasources/<datasourceId>/edit?projectId=<native-project-id>`

Administrative strict-JSON request containing only optional `name` and
`connection` fields, using the same non-secret settings rules as creation. It
opens the native editor and returns
`202 {"operationId":"...","kind":"edit","state":"queued"}`. The user
reviews and saves in DBeaver; editing does not create/delete another profile.

### `POST /datasources/<datasourceId>/test?projectId=<native-project-id>`

Administrative strict-JSON request containing only an optional positive
`timeout` in seconds. If omitted, the server's configured default applies. The
client accepts an explicit value from 1 through 3600 seconds; the server
enforces the configured maximum `janf.dataExport.connectionTestTimeoutSeconds`
(unset default 30 seconds; the configured value is also the maximum, capped at
3600 seconds). Returns
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

Administrative response fields include `operationId`, `kind` (`create`,
`edit`, `test`), `state`, `created`, `saved`, `connected`, and optional
`datasource`, `test`, `diagnostic`, `code`, `error`, and `cleanup`. States are
`queued`, `running`, `awaiting_input`, `saved`, `succeeded`, `cancelled`, and
`failed`. A create or edit completes only at `state:"saved"` with saved profile
identity. A successful test completes only at `state:"succeeded"` with the
original datasource identity and `test.passed:true`. `saved` and `connected`
remain independent state flags; `connected` describes the profile's original
state and is never inferred from `test.passed`.
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
- `401` / `403`: missing/invalid token, or an administrative `Origin` header.
- `404`: unknown project, datasource, driver, node, or operation.
- `405`: unsupported method.
- `413`: body exceeds 1 MiB.
- `415`: connection creation used a non-JSON content type.
- `422`: unsupported direct-mode configuration.
- `409`: lifecycle or target conflict.
- `500`: provider or execution failure.
- `503`: DBeaver UI/native access, runtime, or administrative authorization unavailable.
- `504`: execution timeout.

Never expose the local API outside loopback or send the admin token off-host.
A successful save is not proof of connectivity; a test success is not proof that
the profile remains connected. On any ambiguous mutation result, inspect the
operation/profile before deciding what to do next.

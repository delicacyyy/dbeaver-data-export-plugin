---
name: dbeaver-database-operator
description: Operate project-qualified DBeaver data sources through the local data API: discover projects and drivers, create and natively configure connections, safely inspect and test them, diagnose and edit settings, discover schemas, page rows, and execute provider SQL.
---

# DBeaver Database Operator

Use `scripts/dbeaver_api.py` for every API call. Database credentials stay in
DBeaver: never ask for, enter into an API request, print, copy, or capture a
username, password, provider authentication token, credential-bearing URL, or
authentication secret.
Guided create/edit flows open DBeaver's native UI so the user can select the
project, review settings, enter credentials, and complete any required native
interaction.

## Access and target selection

The API has no plugin-managed authentication; the server listens on
`http://127.0.0.1:8765` by default. The bundled client accepts only loopback IP
URLs, never follows redirects, and disables environment proxies. Use
`--base-url` or `DBEAVER_DATA_API_URL` only to select a loopback destination.
Friendly project names resolve through `/projects`; a known `--project-id` is
passed directly. Datasource names resolve through
`/datasources?projectId=...`. All routes are unauthenticated at the plugin
level. The existing `Origin` rejection applies only to project/driver
discovery, datasource create/inspect/edit/test, and connection-operation
status routes; the client itself sends no `Origin`. See
[references/api.md](references/api.md) for the complete HTTP contract.

From PowerShell, set the client path once:

```powershell
$dbApi = ".\skills\dbeaver-database-operator\scripts\dbeaver_api.py"
```

Choose an exact project name or native ID with `--project` or `--project-id`.
A project name must be unique; when names are ambiguous, choose its native ID.
Datasource commands also require exactly one of `--ds` (exact name within that
project) or `--ds-id` (native datasource ID). A friendly datasource name is
looked up within the selected native project; an ambiguous/missing target
fails before a mutation is submitted. Native IDs are sent directly without
fallback to the active or first project; creation verifies the selected
project's existence and writability before POST. `rows` follows a schema node
ID, which already encodes both native IDs. Protected projects retain DBeaver's
native unlock flow; complete it in DBeaver.

The workspace's `activeProjectId` is only the currently active workspace
project; it does **not** guarantee that a native wizard selected the same
project. DBeaver's wizard can follow navigator selection. Before accepting a
save, verify the wizard's project and then verify the returned datasource's
`projectId` with the operation result or `inspect-connection`.
Guided creation activates and selects the explicitly requested project before
constructing the wizard. Do not substitute the UI's existing active project.

## End-to-end connection workflow

1. Discover projects and select the intended writable project by ID:

   ```powershell
   python $dbApi projects
   python $dbApi datasources --project-id "<project-id>"
   python $dbApi drivers --project-id "<project-id>"
   ```

   Driver discovery reports drivers enabled in the running DBeaver instance.
   It is not a product allowlist or a complete description of custom native
   configuration pages. Guided mode is available across enabled drivers; use
   direct mode only when that driver's discovery response advertises it.

2. Prepare a UTF-8 JSON request with only non-secret settings, then submit it
   for the selected project. The client injects `projectId` without rewriting
   existing JSON number tokens; a conflicting request-file project ID is
   rejected. Do not include usernames, passwords, secret maps, or credential
   URL userinfo.

   ```json
   {
     "mode": "guided",
     "providerId": "postgresql",
     "driverId": "postgres-jdbc",
     "name": "local-development",
     "connection": {
       "host": "127.0.0.1",
       "port": 5432,
       "database": "example"
     }
   }
   ```

   ```powershell
   python $dbApi create-connection `
     --project-id "<project-id>" --request-file "connection-request.json"
   python $dbApi connection-operation `
     --id "<operation-id>" --wait --wait-timeout 300
   ```

   `202` and `queued` mean accepted, not saved. In the native wizard, select
   the intended project, review prefills, enter any credentials yourself, and
   complete or cancel the wizard. Polling is GET-only. `--wait` is bounded
   (default 300 seconds, maximum 3600); if the deadline arrives while the
   operation is pending, the client exits nonzero and reports its safe residual
   state. Without `--wait`, pending is reported as pending, never success.
   Never automatically replay a create POST after an ambiguous result.

3. Once the create operation reports `state: "saved"`, inspect the exact saved
   profile. A saved profile is not necessarily connected, and neither save nor
   a connected flag proves that a fresh connection test passed.

   ```powershell
   python $dbApi inspect-connection `
     --project-id "<project-id>" --ds-id "<datasource-id>"
   ```

   The inspection response contains an explicit safe configuration snapshot;
   it omits credentials and unknown arbitrary configuration. It is suitable
   for checking host, port, database, URL-safe settings, and non-secret
   properties, not for recovering authentication material.
   In URL mode, the URL is authoritative for the endpoint; retained native
   manual host/port defaults may not describe that endpoint. Unknown or
   potentially sensitive URL forms are omitted rather than exported.

4. Test the saved profile using DBeaver's real native connection/session path:

   ```powershell
   python $dbApi test-connection `
     --project-id "<project-id>" --ds-id "<datasource-id>"
   python $dbApi connection-operation `
     --id "<test-operation-id>" --wait --wait-timeout 300
   ```

   The test is successful only when the test operation reaches `succeeded`,
   includes the original datasource identity, and has `test.passed: true`.
   `connected` describes the profile's original connection state; it is not
   derived from the test result. The test result also reports native connection
   timing, total time including cleanup, and available server/driver metadata.
   Stored/shared credentials and enabled distributed handlers may require the
   native UI. Background tests refuse configured startup SQL/shell hooks rather
   than execute them as a read-only probe. Complete any required authentication
   or deliberate hook-enabled test in DBeaver. Deadlines are cooperative:
   provider/native setup and safe cleanup may outlast them.

5. Diagnose using `diagnostic.category`, `code`, `error`, and `cleanup` from the
   safe operation response. Categories distinguish authentication, TLS,
   authorization, networking/timeouts, driver/native compatibility, provider
   configuration, interaction requirements, and cleanup failures. Available
   SQLSTATE/vendor codes and recognized missing native classes are safe
   diagnostic evidence; raw exception messages and credentials are not exported.
   For example, a MongoDB server behind `mongos` rejects use of the `local`
   database with “Cannot use local database through mongos”; use an appropriate
   default database instead of treating that as an authentication failure.

6. If a setting needs correction, inspect first, then submit a non-secret edit
   for the same project and datasource. DBeaver's native editor remains the
   authority for project selection and credential entry. Save/review in the
   native UI, check the edit operation, inspect again, and retest before
   continuing:

   ```json
   {"name":"local-development","connection":{"database":"admin"}}
   ```

   ```powershell
   python $dbApi edit-connection `
     --project-id "<project-id>" --ds-id "<datasource-id>" `
     --request-file "connection-edit.json"
   python $dbApi connection-operation `
     --id "<edit-operation-id>" --wait --wait-timeout 300
   ```

   Edit/test never delete the existing profile. On an error, retain the
   structured non-secret residual diagnostics; do not infer that the profile
   disappeared or automatically repeat the POST.

7. After the target is saved and tested as needed, discover schema using native
   IDs. Follow returned node IDs for row paging:

   ```powershell
   python $dbApi schema --project-id "<project-id>" --ds-id "<datasource-id>"
   python $dbApi rows --node "<schema-path-v2-table-node-id>" --limit 25 --offset 0
   python $dbApi execute --project-id "<project-id>" --ds-id "<datasource-id>" `
     --sql "SELECT 1 AS ok"
   ```

   Old name-only `/schema` and `/execute` selectors are removed. Old schema
   node IDs require rediscovery. MongoDB SQL execution uses an explicit
   `--database` when targeting a database other than the configured default.

## MongoDB settings that must not be conflated

Keep a host list separate from authentication userinfo. Host values describe
only hosts (and ports where supported), never `user:password@host`; the user
enters credentials in DBeaver's native editor. The configured/default database
(or URI path database) selects the application database. `authSource` selects
the database against which credentials are authenticated; it is not a
replacement for the default database. For `mongos`, do not use `local` as the
connection's default database. Inspection may show safe `authSource` and
`authMechanism` properties, but never exposes usernames or passwords.

## Data-operation safety

- Do not infer a mutation from a request that only asks to inspect, explain,
  count, or diagnose.
- Keep `UPDATE`/`DELETE` predicates narrow; clarify an ambiguous target or
  destructive scope. A direct request for a scoped mutation is authorization;
  do not add redundant confirmation.
- `/execute` owns and closes an isolated context per request. Statements are
  effectively independent and providers may auto-commit; do not promise a
  transaction or rollback across requests.
- Respect `truncated` and `hasMore`; fetch further pages instead of claiming
  completeness. Column comments come from schema discovery.
- Provider metadata can remain cached after DDL. Verify mutations with an
  authoritative provider/catalog read or refresh metadata in DBeaver; a cached
  schema node is not proof that a dropped object still exists.
- Treat any nonzero client exit as failure. An HTTP error or operation failure
  is not a successful connection or SQL result. Never automatically retry a
  connection-creation POST; inspect the operation/profile and reconcile first.

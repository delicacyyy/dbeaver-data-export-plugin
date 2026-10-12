# DBeaver Data API

The plugin listens on `http://127.0.0.1:8765` by default. It uses DBeaver's
configured connections, credentials, SSL settings, and tunnels.

## Endpoints

### `GET /datasources`

Return datasource objects with `name`, `id`, and `connected`.

### `GET /schema`

Parameters:

- `ds` (required): datasource name.
- `parent`: opaque node ID returned by an earlier schema response.
- `mode=full`: return the complete hierarchy; omit for one level.

The default response is `{"items":[...]}`. Nodes contain `id`, `name`, `kind`,
and `expandable`. Column nodes may also contain `type` and `comment`.

Follow node IDs rather than constructing them. MongoDB root discovery returns
the current non-system databases visible to the connected account.

### `GET /rows`

Parameters:

- `node` (required): table, view, or collection node ID from `/schema`.
- `limit`: 1–1000; default 100.
- `offset`: nonnegative row offset; default 0.

The response contains `fields`, `records`, and `page`. Use `hasMore` and
`nextOffset` to continue. Document collections may return dynamic JSON fields.
Column comments are not repeated here.

### `POST /execute`

Parameters:

- `ds` (required): datasource name.
- `database`: explicit MongoDB database.
- `limit`: returned row limit, 1–1000.
- `timeout`: server-side execution timeout in seconds.

Body:

```json
{"sql":"SELECT 1 AS ok"}
```

Row results contain `context`, `resultKind`, `fields`, `records`, and
`summary`. DML and DDL return `resultKind: "update"` and `updateCount`; some
providers report `-1` when no count is available.

Each call uses and closes an isolated execution context. Do not assume multiple
requests share a transaction. Provider SQL capabilities and dialect rules
still apply. MongoDB uses DBeaver's limited SQL-to-document adapter and should
receive an explicit `database` when the target is not the configured default.

## Errors

The client exits nonzero for HTTP and connection failures. Common statuses:

- `400`: invalid parameters or request JSON.
- `404`: datasource or schema node no longer exists.
- `405`: wrong HTTP method.
- `500`: provider or execution failure.
- `503`: datasource or isolated context unavailable.
- `504`: execution timeout.

An HTTP error means the operation must not be reported as successful. Inspect
the returned provider message before deciding whether a retry is safe.

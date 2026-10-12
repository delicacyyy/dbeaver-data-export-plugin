---
name: dbeaver-database-operator
description: Operate DBeaver-configured relational and MongoDB data sources through the local data API. Use when the user asks to discover databases or tables, inspect schemas and comments, sample or page through rows, translate a natural-language request into provider-appropriate SQL, run queries, perform DML or DDL changes, or verify database state.
---

# DBeaver Database Operator

Use `scripts/dbeaver_api.py` for every API call. Keep credentials inside
DBeaver; never request, print, or copy connection secrets.

Read [references/api.md](references/api.md) when response fields, endpoint
parameters, or provider behavior are relevant.

## Workflow

1. Check availability with `datasources`. If the local API is unavailable,
   ask the user to start DBeaver with the data-export plugin.
2. Resolve the datasource name. If it is ambiguous, show the matching
   datasource names and ask which one to use.
3. Discover structure level by level with `schema`. Follow returned node IDs;
   avoid `--full` unless the task genuinely needs the entire hierarchy.
4. Inspect column types and comments before composing provider-specific SQL.
   Use only identifiers confirmed by schema discovery.
5. Use `rows` for paging or sampling one table or collection. Use `execute`
   for filters, joins, aggregates, DML, and DDL.
6. Execute read and write operations that match the user's request. Treat a
   direct request for a scoped mutation as authorization; do not add a
   redundant write-enable prompt.
7. Verify mutations with a follow-up read or schema lookup and report the
   affected count plus the verified final state.

## Mutation Rules

- Do not infer a mutation from a question that only asks to inspect, explain,
  count, or diagnose.
- Keep `UPDATE` and `DELETE` predicates as narrow as the request permits.
  Preview matching rows or counts when the affected scope is uncertain.
- Require clarification before expanding the requested scope or running a
  destructive statement with an ambiguous database, table, collection, or
  predicate.
- Treat an explicit request naming the destructive target as authorization.
  Do not ask again merely because the operation is DDL.
- Remember that `/execute` owns and closes an isolated context per request.
  Statements are effectively independent and may auto-commit; do not promise
  rollback across requests.
- Never test mutations against an application object when a uniquely named
  disposable object can answer the question. Remove disposable objects and
  verify their removal.

## Commands

Set the script path once:

```powershell
$dbApi = "<skill-directory>\scripts\dbeaver_api.py"
```

Discover datasources:

```powershell
python $dbApi datasources
```

List root schema nodes or expand a node:

```powershell
python $dbApi schema --ds "JWEMS"
python $dbApi schema --ds "JWEMS" --parent "<node-id>"
```

Page through data:

```powershell
python $dbApi rows --node "<table-node-id>" --limit 25 --offset 0
```

Execute relational SQL:

```powershell
python $dbApi execute --ds "cc-switch.db" --sql "SELECT 1 AS ok"
```

Execute against a MongoDB database:

```powershell
python $dbApi execute --ds "jingweiec" --database "CQMJ" `
  --sql "SELECT * FROM CQMJMES"
```

For complex SQL, write a temporary UTF-8 payload file and use `--sql-file`.
Delete the file after execution.

## Result Handling

- Preserve structured JSON output when reporting fields, records, paging, or
  update counts.
- Treat a nonzero client exit code as failure. Report the returned HTTP status
  and API error without pretending that a statement ran.
- Respect `truncated` and `hasMore`; request the next page rather than claiming
  completeness.
- Obtain column comments from `/schema`. `/rows` and `/execute` field metadata
  do not currently include comments.

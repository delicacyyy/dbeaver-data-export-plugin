#!/usr/bin/env python3
"""Deterministic CLI for the local DBeaver data-export HTTP API."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import (
    ProxyHandler,
    Request,
    build_opener,
)


DEFAULT_BASE_URL = os.environ.get(
    "DBEAVER_DATA_API_URL", "http://127.0.0.1:8765"
)


class ApiError(Exception):
    """An HTTP or connectivity failure with a user-facing message."""


def parse_json_or_text(raw: bytes) -> Any:
    text = raw.decode("utf-8", errors="replace")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


def render(value: Any, compact: bool) -> str:
    if isinstance(value, str):
        return value
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":") if compact else None,
        indent=None if compact else 2,
    )


def request_json(
    base_url: str,
    path: str,
    *,
    method: str = "GET",
    params: dict[str, Any] | None = None,
    payload: dict[str, Any] | None = None,
    timeout: float,
) -> Any:
    url = base_url.rstrip("/") + path
    if params:
        query = {
            key: value
            for key, value in params.items()
            if value is not None
        }
        if query:
            url += "?" + urlencode(query)

    body = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"

    request = Request(url, data=body, headers=headers, method=method)
    opener = build_opener(ProxyHandler({}))
    try:
        with opener.open(request, timeout=timeout) as response:
            return parse_json_or_text(response.read())
    except HTTPError as error:
        response_body = parse_json_or_text(error.read())
        raise ApiError(
            f"HTTP {error.code}: {render(response_body, compact=True)}"
        ) from error
    except URLError as error:
        raise ApiError(f"API unavailable: {error.reason}") from error
    except TimeoutError as error:
        raise ApiError(
            f"API request timed out after {timeout:g} seconds"
        ) from error


def read_sql(args: argparse.Namespace) -> str:
    if args.sql is not None:
        sql = args.sql
    elif args.sql_file == "-":
        sql = sys.stdin.read()
    else:
        sql = Path(args.sql_file).read_text(encoding="utf-8-sig")
    if not sql.strip():
        raise ApiError("SQL must not be empty")
    return sql


def run(args: argparse.Namespace) -> Any:
    common = {
        "base_url": args.base_url,
        "timeout": args.http_timeout,
    }
    if args.command == "datasources":
        return request_json(**common, path="/datasources")

    if args.command == "schema":
        return request_json(
            **common,
            path="/schema",
            params={
                "ds": args.ds,
                "parent": args.parent,
                "mode": "full" if args.full else None,
            },
        )

    if args.command == "rows":
        return request_json(
            **common,
            path="/rows",
            params={
                "node": args.node,
                "limit": args.limit,
                "offset": args.offset,
            },
        )

    if args.command == "execute":
        sql = read_sql(args)
        request_timeout = max(args.http_timeout, args.timeout + 5)
        return request_json(
            base_url=args.base_url,
            timeout=request_timeout,
            path="/execute",
            method="POST",
            params={
                "ds": args.ds,
                "database": args.database,
                "limit": args.limit,
                "timeout": args.timeout,
            },
            payload={"sql": sql},
        )

    raise ApiError(f"Unsupported command: {args.command}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Operate databases through the local DBeaver data API."
    )
    parser.add_argument(
        "--base-url",
        default=DEFAULT_BASE_URL,
        help=f"API base URL (default: {DEFAULT_BASE_URL})",
    )
    parser.add_argument(
        "--http-timeout",
        type=float,
        default=45.0,
        help="Client-side HTTP timeout in seconds (default: 45)",
    )
    parser.add_argument(
        "--compact",
        action="store_true",
        help="Print compact JSON",
    )

    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("datasources", help="List configured datasources")

    schema = commands.add_parser("schema", help="Discover schema nodes")
    schema.add_argument("--ds", required=True, help="Datasource name")
    schema.add_argument("--parent", help="Parent node ID to expand")
    schema.add_argument(
        "--full",
        action="store_true",
        help="Return the full hierarchy instead of one level",
    )

    rows = commands.add_parser("rows", help="Page table or collection rows")
    rows.add_argument("--node", required=True, help="Table node ID")
    rows.add_argument("--limit", type=int, default=100)
    rows.add_argument("--offset", type=int, default=0)

    execute = commands.add_parser(
        "execute", help="Execute SQL, DML, or DDL"
    )
    execute.add_argument("--ds", required=True, help="Datasource name")
    execute.add_argument(
        "--database",
        help="MongoDB database name",
    )
    sql_source = execute.add_mutually_exclusive_group(required=True)
    sql_source.add_argument("--sql", help="SQL text")
    sql_source.add_argument(
        "--sql-file",
        help="UTF-8 SQL file path, or '-' to read stdin",
    )
    execute.add_argument("--limit", type=int, default=100)
    execute.add_argument(
        "--timeout",
        type=int,
        default=30,
        help="Server-side execution timeout in seconds (default: 30)",
    )
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        result = run(args)
    except (ApiError, OSError) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(render(result, args.compact))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

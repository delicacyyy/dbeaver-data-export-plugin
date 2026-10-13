#!/usr/bin/env python3
"""Deterministic CLI for the local DBeaver data-export HTTP API."""

from __future__ import annotations

import argparse
from decimal import Decimal
import json
import os
from ipaddress import ip_address
from pathlib import Path
import sys
import time
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import (
    HTTPRedirectHandler,
    ProxyHandler,
    Request,
    build_opener,
)


DEFAULT_BASE_URL = os.environ.get(
    "DBEAVER_DATA_API_URL", "http://127.0.0.1:8765"
)
MAX_JSON_DEPTH = 100
MAX_JSON_NUMBER_CHARS = 128
MAX_JSON_EXPONENT = 10000
MAX_WAIT_SECONDS = 3600
MAX_TEST_TIMEOUT_SECONDS = 3600
POLL_INTERVAL_SECONDS = 1.0


class ApiError(Exception):
    """An HTTP or connectivity failure with a user-facing message."""

    def __init__(
        self, message: str, *, payload: dict[str, Any] | None = None
    ) -> None:
        super().__init__(message)
        self.payload = payload


def _require_loopback_url(url: str) -> None:
    try:
        parsed = urlsplit(url)
        host = parsed.hostname
        address = ip_address(host or "")
    except (TypeError, ValueError):
        address = None
        parsed = None
    if (
        parsed is None
        or parsed.scheme not in ("http", "https")
        or address is None
        or not address.is_loopback
        or parsed.username is not None
        or parsed.password is not None
    ):
        raise ApiError("Local API requests may only target a loopback IP URL")


class _NoRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


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
    payload: dict[str, Any] | str | None = None,
    timeout: float,
    with_status: bool = False,
) -> Any:
    url = base_url.rstrip("/") + path
    if params:
        query = {key: value for key, value in params.items() if value is not None}
        if query:
            url += "?" + urlencode(query)
    _require_loopback_url(url)

    body = None
    headers = {"Accept": "application/json"}
    if payload is not None:
        encoded = payload if isinstance(payload, str) else json.dumps(
            payload, ensure_ascii=False
        )
        body = encoded.encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    request = Request(url, data=body, headers=headers, method=method)
    opener = build_opener(ProxyHandler({}), _NoRedirectHandler())
    try:
        with opener.open(request, timeout=timeout) as response:
            value = parse_json_or_text(response.read())
            if with_status:
                return response.getcode(), value
            return value
    except HTTPError as error:
        if 300 <= error.code < 400:
            error.close()
            raise ApiError(
                f"HTTP {error.code}: local API redirect refused"
            ) from error
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


class InvalidRequestJSON(ValueError):
    """A request contains JSON syntax unsupported by the strict client."""


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise InvalidRequestJSON()
        result[key] = value
    return result


def _bounded_integer(value: str) -> int:
    if len(value) > MAX_JSON_NUMBER_CHARS:
        raise InvalidRequestJSON()
    return int(value)


def _reject_constant(_value: str) -> None:
    raise InvalidRequestJSON()


def _bounded_decimal(value: str) -> Decimal:
    if len(value) > MAX_JSON_NUMBER_CHARS:
        raise InvalidRequestJSON()
    exponent = value.lower().partition("e")[2]
    if exponent and abs(int(exponent)) > MAX_JSON_EXPONENT:
        raise InvalidRequestJSON()
    parsed = Decimal(value)
    if not parsed.is_finite():
        raise InvalidRequestJSON()
    return parsed


def _validate_json_request(value: dict[str, Any]) -> None:
    pending = [(value, 1)]
    while pending:
        item, depth = pending.pop()
        if isinstance(item, dict):
            if depth > MAX_JSON_DEPTH:
                raise InvalidRequestJSON()
            for key in item:
                key.encode("utf-8")
            pending.extend((child, depth + 1) for child in item.values())
        elif isinstance(item, list):
            if depth > MAX_JSON_DEPTH:
                raise InvalidRequestJSON()
            pending.extend((child, depth + 1) for child in item)
        elif isinstance(item, str):
            item.encode("utf-8")


def read_json_request(filename: str, label: str) -> tuple[dict[str, Any], str]:
    try:
        text = (
            sys.stdin.read()
            if filename == "-"
            else Path(filename).expanduser().read_text(encoding="utf-8")
        )
    except (OSError, UnicodeError) as error:
        raise ApiError(f"Unable to read {label} file") from error

    try:
        value = json.loads(
            text,
            object_pairs_hook=_unique_object,
            parse_int=_bounded_integer,
            parse_constant=_reject_constant,
            parse_float=_bounded_decimal,
        )
    except (ValueError, RecursionError):
        raise ApiError(
            f"{label.capitalize()} is invalid or exceeds JSON parser limits"
        ) from None
    if not isinstance(value, dict):
        raise ApiError(f"{label.capitalize()} must be a JSON object")
    try:
        _validate_json_request(value)
    except (ValueError, UnicodeError, RecursionError):
        raise ApiError(
            f"{label.capitalize()} is invalid or exceeds JSON parser limits"
        ) from None
    return value, text


def _response_object(value: Any, operation: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ApiError(f"Invalid {operation} response from API")
    return value


def _nonempty_string(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _valid_datasource_identity(value: Any) -> bool:
    return isinstance(value, dict) and all(
        _nonempty_string(value.get(field))
        for field in ("id", "name", "providerId", "driverId", "projectId")
    )


def _safe_identity(value: Any) -> Any:
    if not isinstance(value, dict):
        return None
    keys = ("id", "name", "providerId", "driverId", "projectId", "projectName")
    return {key: value[key] for key in keys if key in value}


def _safe_operation(value: dict[str, Any]) -> dict[str, Any]:
    """Keep only documented operation fields in residual diagnostics."""
    keys = (
        "operationId", "kind", "state", "created", "saved", "connected",
        "datasource", "test", "diagnostic", "code", "error", "cleanup",
    )
    result = {key: value[key] for key in keys if key in value}
    if "datasource" in result:
        result["datasource"] = _safe_identity(result["datasource"])
    return result


def _operation_error(state: str, result: dict[str, Any]) -> str:
    message = f"Connection operation {state}"
    code = result.get("code")
    if _nonempty_string(code):
        message += f" ({code})"
    detail = result.get("error")
    if _nonempty_string(detail):
        message += f": {detail.strip()}"
    return message


def _strictly_inject_project_id(text: str, request: dict[str, Any], project_id: str) -> str:
    if "projectId" in request:
        existing = request["projectId"]
        if not _nonempty_string(existing):
            raise ApiError("Connection request projectId must be a nonempty string")
        if existing != project_id:
            raise ApiError(
                "Connection request projectId does not match the selected project"
            )
        return text

    # JSON validation above guarantees the final non-whitespace character is
    # the closing brace of the root object. Insert only that member, leaving
    # every original numeric token and byte-for-byte source fragment intact.
    end = len(text.rstrip())
    if end == 0 or text[end - 1] != "}":
        raise ApiError("Connection request must be a JSON object")
    separator = "," if request else ""
    member = '"projectId":' + json.dumps(project_id, ensure_ascii=False)
    return text[: end - 1] + separator + member + text[end - 1 :]


def _get_projects(args: argparse.Namespace) -> dict[str, Any]:
    status, value = request_json(
        args.base_url,
        "/projects",
        timeout=args.http_timeout,
        with_status=True,
    )
    result = _response_object(value, "projects")
    items = result.get("items")
    if (
        status != 200
        or not isinstance(items, list)
        or "activeProjectId" not in result
    ):
        raise ApiError("Invalid projects response from API")
    active_id = result["activeProjectId"]
    if active_id is not None and not _nonempty_string(active_id):
        raise ApiError("Invalid projects response from API")
    for item in items:
        if (
            not isinstance(item, dict)
            or not _nonempty_string(item.get("id"))
            or not _nonempty_string(item.get("name"))
            or type(item.get("active")) is not bool
            or type(item.get("writable")) is not bool
        ):
            raise ApiError("Invalid projects response from API")
    return result


def _resolve_project(
    args: argparse.Namespace, *, discover_id: bool = False
) -> dict[str, Any]:
    selector_id = getattr(args, "project_id", None)
    selector_name = getattr(args, "project", None)
    if (selector_id is None) == (selector_name is None):
        raise ApiError("Select exactly one project with --project or --project-id")
    if selector_id is not None:
        if not _nonempty_string(selector_id):
            raise ApiError("--project-id must be a nonempty native ID")
        if not discover_id:
            return {"id": selector_id}

    projects = _get_projects(args)["items"]
    if selector_id is not None:
        matches = [item for item in projects if item["id"] == selector_id]
        label = f"project ID {selector_id!r}"
    else:
        matches = [item for item in projects if item["name"] == selector_name]
        label = f"project name {selector_name!r}"
    if not matches:
        raise ApiError(f"No exact match for {label}")
    if len(matches) != 1:
        choices = ", ".join(f"{item['name']} ({item['id']})" for item in matches)
        raise ApiError(f"Ambiguous {label}; select a native project ID: {choices}")
    return matches[0]


def _get_project_datasources(args: argparse.Namespace, project: dict[str, Any]) -> list[Any]:
    value = request_json(
        args.base_url,
        "/datasources",
        params={"projectId": project["id"]},
        timeout=args.http_timeout,
    )
    if not isinstance(value, list):
        raise ApiError("Invalid datasources response from API")
    for datasource in value:
        if (
            not isinstance(datasource, dict)
            or not _nonempty_string(datasource.get("id"))
            or not _nonempty_string(datasource.get("name"))
            or datasource.get("projectId") != project["id"]
        ):
            raise ApiError("Invalid project-qualified datasources response from API")
    return value


def _resolve_target(args: argparse.Namespace) -> tuple[dict[str, Any], dict[str, Any]]:
    project = _resolve_project(args)
    selector_id = getattr(args, "ds_id", None)
    selector_name = getattr(args, "ds", None)
    if (selector_id is None) == (selector_name is None):
        raise ApiError("Select exactly one datasource with --ds or --ds-id")
    datasources = _get_project_datasources(args, project)
    project_label = (
        f"project {project['name']!r}"
        if _nonempty_string(project.get("name"))
        else f"project ID {project['id']!r}"
    )
    if selector_id is not None:
        matches = [item for item in datasources if item["id"] == selector_id]
        label = f"datasource ID {selector_id!r} in {project_label}"
    else:
        matches = [item for item in datasources if item["name"] == selector_name]
        label = f"datasource name {selector_name!r} in {project_label}"
    if not matches:
        raise ApiError(f"No exact match for {label}")
    if len(matches) != 1:
        choices = ", ".join(f"{item['name']} ({item['id']})" for item in matches)
        raise ApiError(f"Ambiguous {label}; select a native datasource ID: {choices}")
    return project, matches[0]


def _verify_inspection(value: Any, project: dict[str, Any], datasource: dict[str, Any]) -> dict[str, Any]:
    result = _response_object(value, "connection inspection")
    identity = result.get("datasource")
    if (
        not _valid_datasource_identity(identity)
        or identity["id"] != datasource["id"]
        or identity["projectId"] != project["id"]
        or type(result.get("saved")) is not bool
        or type(result.get("connected")) is not bool
        or not isinstance(result.get("configuration"), dict)
    ):
        raise ApiError("Invalid or wrong-target connection inspection response")
    return result


def _accepted_operation(value: Any, kind: str) -> dict[str, Any]:
    result = _response_object(value, f"{kind} request")
    if (
        not _nonempty_string(result.get("operationId"))
        or result.get("kind") != kind
        or result.get("state") != "queued"
    ):
        raise ApiError(f"Invalid queued {kind} response from API")
    return result


def _validate_operation(value: Any, operation_id: str) -> dict[str, Any]:
    result = _response_object(value, "connection operation")
    kind = result.get("kind")
    state = result.get("state")
    if (
        result.get("operationId") != operation_id
        or kind not in ("create", "edit", "test")
        or state not in ("queued", "running", "awaiting_input", "saved", "succeeded", "cancelled", "failed")
        or any(type(result.get(field)) is not bool for field in ("created", "saved", "connected"))
        or ("datasource" in result and not _valid_datasource_identity(result["datasource"]))
        or (kind != "test" and "test" in result)
    ):
        raise ApiError(
            "Invalid connection operation response from API",
            payload=_safe_operation(result),
        )

    # Persistence and connection are independent residual flags. Completion is
    # proven by the kind-specific terminal state, never by `connected` alone.
    created, saved = result["created"], result["saved"]
    if kind == "create":
        valid = (
            state != "succeeded"
            and (not saved or created)
            and (
                state not in ("queued", "running", "awaiting_input")
                or not created and not saved
            )
            and (
                state != "saved"
                or created and saved
                and _valid_datasource_identity(result.get("datasource"))
            )
        )
    elif kind == "edit":
        valid = (
            not created
            and state != "succeeded"
            and (
                state != "saved"
                or saved and _valid_datasource_identity(result.get("datasource"))
            )
        )
    else:
        test = result.get("test")
        passed = test.get("passed") if isinstance(test, dict) else None
        valid = (
            not created
            and state != "saved"
            and ("test" not in result or isinstance(test, dict))
            and (passed is None or type(passed) is bool)
            and (
                state == "succeeded"
                and isinstance(test, dict)
                and passed is True
                and _valid_datasource_identity(result.get("datasource"))
                or state != "succeeded" and passed is not True
            )
        )
    if not valid:
        raise ApiError(
            "Contradictory connection operation state",
            payload=_safe_operation(result),
        )
    return result


def _operation_failure(state: str, result: dict[str, Any]) -> ApiError:
    message = _operation_error(state, result)
    if result["kind"] == "create":
        message += "; inspect datasources for any partial registration before retrying"
    elif result["kind"] == "edit":
        message += "; inspect the saved connection before another edit"
    else:
        message += "; inspect the diagnostic and connection settings before retesting"
    return ApiError(message, payload=_safe_operation(result))


def _get_operation(args: argparse.Namespace, operation_id: str, timeout: float) -> dict[str, Any]:
    status, value = request_json(
        args.base_url,
        f"/connection-operations/{quote(operation_id, safe='')}",
        timeout=timeout,
        with_status=True,
    )
    result = _validate_operation(value, operation_id)
    if status != 200:
        raise ApiError("Invalid connection operation response from API")
    return result


def _finish_operation(result: dict[str, Any]) -> dict[str, Any]:
    state = result["state"]
    if state in ("cancelled", "failed"):
        raise _operation_failure(state, result)
    return result


def _wait_for_operation(args: argparse.Namespace, operation_id: str) -> dict[str, Any]:
    deadline = time.monotonic() + args.wait_timeout
    result = _get_operation(args, operation_id, min(args.http_timeout, args.wait_timeout))
    while result["state"] in ("queued", "running", "awaiting_input"):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ApiError(
                "Connection operation is still pending at the wait deadline",
                payload=_safe_operation(result),
            )
        time.sleep(min(POLL_INTERVAL_SECONDS, remaining))
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise ApiError(
                "Connection operation is still pending at the wait deadline",
                payload=_safe_operation(result),
            )
        try:
            result = _get_operation(
                args, operation_id, min(args.http_timeout, remaining)
            )
        except ApiError as error:
            if (
                remaining <= args.http_timeout
                and str(error).startswith("API request timed out")
            ):
                raise ApiError(
                    "Connection operation is still pending at the wait deadline",
                    payload=_safe_operation(result),
                ) from error
            raise
    return _finish_operation(result)


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


def _project_params(project: dict[str, Any], datasource: dict[str, Any]) -> dict[str, str]:
    return {"projectId": project["id"], "dsId": datasource["id"]}


def run(args: argparse.Namespace) -> Any:
    common = {"base_url": args.base_url, "timeout": args.http_timeout}

    if args.command == "projects":
        return _get_projects(args)

    if args.command == "datasources":
        project = _resolve_project(args)
        return _get_project_datasources(args, project)

    if args.command == "drivers":
        project = _resolve_project(args)
        provider_id, driver_id = args.provider_id, args.driver_id
        if (provider_id is None) != (driver_id is None):
            raise ApiError("--provider-id and --driver-id must be supplied together")
        status, value = request_json(
            **common,
            path="/drivers",
            params={"projectId": project["id"], "providerId": provider_id, "driverId": driver_id},
            with_status=True,
        )
        result = _response_object(value, "drivers")
        if (
            status != 200
            or not isinstance(result.get("items"), list)
            or not isinstance(result.get("project"), dict)
            or result["project"].get("id") != project["id"]
        ):
            raise ApiError("Invalid or wrong-project drivers response from API")
        return result

    if args.command == "create-connection":
        request, request_text = read_json_request(args.request_file, "connection request")
        allowed = {"mode", "providerId", "driverId", "name", "connection", "projectId"}
        if set(request) - allowed:
            raise ApiError("Connection request contains unsupported top-level fields")
        mode = request.get("mode")
        if mode not in ("guided", "direct"):
            raise ApiError("Connection request mode must be 'guided' or 'direct'")
        project = _resolve_project(args, discover_id=True)
        if not project["writable"]:
            raise ApiError(f"Selected project {project['name']!r} is not writable")
        request_text = _strictly_inject_project_id(request_text, request, project["id"])
        status, value = request_json(
            **common,
            path="/datasources",
            method="POST",
            payload=request_text,
            with_status=True,
        )
        result = _response_object(value, "connection creation")
        if mode == "guided":
            if status != 202:
                raise ApiError("Invalid guided connection response from API")
            return _accepted_operation(result, "create")
        datasource = result.get("datasource")
        if (
            status != 201
            or result.get("created") is not True
            or result.get("saved") is not True
            or result.get("connected") is not False
            or not _valid_datasource_identity(datasource)
            or datasource["projectId"] != project["id"]
        ):
            raise ApiError("Invalid direct connection response from API")
        return result

    if args.command == "connection-operation":
        if args.wait:
            return _wait_for_operation(args, args.id)
        return _finish_operation(_get_operation(args, args.id, args.http_timeout))

    if args.command == "inspect-connection":
        project, datasource = _resolve_target(args)
        status, value = request_json(
            **common,
            path=f"/datasources/{quote(datasource['id'], safe='')}",
            params={"projectId": project["id"]},
            with_status=True,
        )
        if status != 200:
            raise ApiError("Invalid connection inspection response from API")
        return _verify_inspection(value, project, datasource)

    if args.command == "edit-connection":
        request, request_text = read_json_request(args.request_file, "edit request")
        if set(request) - {"name", "connection"}:
            raise ApiError("Edit request may contain only name and connection")
        project, datasource = _resolve_target(args)
        status, value = request_json(
            **common,
            path=f"/datasources/{quote(datasource['id'], safe='')}/edit",
            method="POST",
            params={"projectId": project["id"]},
            payload=request_text,
            with_status=True,
        )
        if status != 202:
            raise ApiError("Invalid edit connection response from API")
        return _accepted_operation(value, "edit")

    if args.command == "test-connection":
        project, datasource = _resolve_target(args)
        payload = {} if args.timeout is None else {"timeout": args.timeout}
        status, value = request_json(
            **common,
            path=f"/datasources/{quote(datasource['id'], safe='')}/test",
            method="POST",
            params={"projectId": project["id"]},
            payload=payload,
            with_status=True,
        )
        if status != 202:
            raise ApiError("Invalid test connection response from API")
        return _accepted_operation(value, "test")

    if args.command == "schema":
        project, datasource = _resolve_target(args)
        return request_json(
            **common,
            path="/schema",
            params={
                **_project_params(project, datasource),
                "parent": args.parent,
                "mode": "full" if args.full else None,
            },
        )

    if args.command == "rows":
        return request_json(
            **common,
            path="/rows",
            params={"node": args.node, "limit": args.limit, "offset": args.offset},
        )

    if args.command == "execute":
        sql = read_sql(args)
        project, datasource = _resolve_target(args)
        request_timeout = max(args.http_timeout, args.timeout + 5)
        return request_json(
            base_url=args.base_url,
            timeout=request_timeout,
            path="/execute",
            method="POST",
            params={
                **_project_params(project, datasource),
                "database": args.database,
                "limit": args.limit,
                "timeout": args.timeout,
            },
            payload={"sql": sql},
        )

    raise ApiError(f"Unsupported command: {args.command}")


def _positive_int(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a positive integer") from error
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def _wait_seconds(value: str) -> int:
    parsed = _positive_int(value)
    if parsed > MAX_WAIT_SECONDS:
        raise argparse.ArgumentTypeError(f"must not exceed {MAX_WAIT_SECONDS} seconds")
    return parsed


def _test_timeout(value: str) -> int:
    parsed = _positive_int(value)
    if parsed > MAX_TEST_TIMEOUT_SECONDS:
        raise argparse.ArgumentTypeError(
            f"must not exceed {MAX_TEST_TIMEOUT_SECONDS} seconds"
        )
    return parsed


def _add_project_selector(parser: argparse.ArgumentParser) -> None:
    selectors = parser.add_mutually_exclusive_group(required=True)
    selectors.add_argument("--project", help="Exact DBeaver project name")
    selectors.add_argument("--project-id", help="Exact native DBeaver project ID")


def _add_target_selectors(parser: argparse.ArgumentParser) -> None:
    _add_project_selector(parser)
    selectors = parser.add_mutually_exclusive_group(required=True)
    selectors.add_argument("--ds", help="Exact datasource name within project")
    selectors.add_argument("--ds-id", help="Exact native datasource ID within project")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Operate databases through the local DBeaver data API."
    )
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL,
                        help=f"API base URL (default: {DEFAULT_BASE_URL})")
    parser.add_argument("--http-timeout", type=float, default=45.0,
                        help="Client-side HTTP timeout in seconds (default: 45)")
    parser.add_argument("--compact", action="store_true", help="Print compact JSON")

    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("projects", help="List workspace projects")

    datasources = commands.add_parser("datasources", help="List project datasources")
    _add_project_selector(datasources)

    drivers = commands.add_parser("drivers", help="List enabled DBeaver drivers")
    _add_project_selector(drivers)
    drivers.add_argument("--provider-id", help="Filter provider ID")
    drivers.add_argument("--driver-id", help="Filter driver ID")

    create = commands.add_parser(
        "create-connection", help="Create a datasource or open a native guided wizard"
    )
    _add_project_selector(create)
    create.add_argument("--request-file", required=True,
                        help="UTF-8 JSON request file, or '-' to read stdin")

    operation = commands.add_parser("connection-operation", help="Check connection operation status")
    operation.add_argument("--id", required=True, help="Operation ID")
    operation.add_argument("--wait", action="store_true",
                           help="Poll with GET until terminal or deadline")
    operation.add_argument("--wait-timeout", type=_wait_seconds, default=300,
                           help=f"Wait deadline in seconds (1–{MAX_WAIT_SECONDS}, default: 300)")

    for command, help_text in (
        ("inspect-connection", "Inspect safe saved connection settings"),
        ("test-connection", "Test a saved connection using DBeaver native behavior"),
        ("edit-connection", "Open the native connection editor"),
        ("schema", "Discover schema nodes"),
    ):
        subparser = commands.add_parser(command, help=help_text)
        _add_target_selectors(subparser)
        if command == "schema":
            subparser.add_argument("--parent", help="Parent node ID to expand")
            subparser.add_argument("--full", action="store_true",
                                   help="Return flattened full-mode scopes")
        elif command == "edit-connection":
            subparser.add_argument("--request-file", required=True,
                                   help="UTF-8 JSON edit object, or '-' to read stdin")
        elif command == "test-connection":
            subparser.add_argument("--timeout", type=_test_timeout,
                                   help=f"Native test timeout in seconds (1–{MAX_TEST_TIMEOUT_SECONDS})")

    rows = commands.add_parser("rows", help="Page rows using a schema-path-v2 node ID")
    rows.add_argument("--node", required=True, help="Node ID returned by schema")
    rows.add_argument("--limit", type=int, default=100)
    rows.add_argument("--offset", type=int, default=0)

    execute = commands.add_parser("execute", help="Execute SQL, DML, or DDL")
    _add_target_selectors(execute)
    execute.add_argument("--database", help="Explicit MongoDB database name")
    sql_source = execute.add_mutually_exclusive_group(required=True)
    sql_source.add_argument("--sql", help="SQL text")
    sql_source.add_argument("--sql-file", help="UTF-8 SQL file path, or '-' to read stdin")
    execute.add_argument("--limit", type=int, default=100)
    execute.add_argument("--timeout", type=int, default=30,
                         help="Server-side execution timeout in seconds (default: 30)")
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        result = run(args)
    except (ApiError, OSError) as error:
        payload = error.payload if isinstance(error, ApiError) else None
        print(render(payload, args.compact) if payload is not None else str(error), file=sys.stderr)
        return 1
    print(render(result, args.compact))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

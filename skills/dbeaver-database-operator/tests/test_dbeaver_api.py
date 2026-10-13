"""Consumer-visible behavioral tests for the bundled DBeaver API client."""

from __future__ import annotations

import json
from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, HTTPServer
import io
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit


SCRIPT_DIR = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPT_DIR))

import dbeaver_api  # noqa: E402


class ResponseServer:
    """Loopback fixture with method/path routing and explicit request capture."""

    def __init__(
        self,
        status: int = 200,
        response: object = None,
        *,
        routes: dict[tuple[str, str], object] | None = None,
        redirect_to: str | None = None,
        bind_host: str = "127.0.0.1",
    ):
        self.status = status
        self.response = {} if response is None else response
        self.routes = routes or {}
        self.redirect_to = redirect_to
        self.requests: list[dict[str, object]] = []
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def _respond(self) -> None:
                length = int(self.headers.get("Content-Length", "0"))
                body = self.rfile.read(length) if length else b""
                owner.requests.append(
                    {
                        "method": self.command,
                        "path": self.path,
                        "authorization": self.headers.get("Authorization"),
                        "origin": self.headers.get("Origin"),
                        "content_type": self.headers.get("Content-Type"),
                        "body": body,
                    }
                )
                route_key = (self.command, urlsplit(self.path).path)
                response = owner.routes.get(route_key, (owner.status, owner.response))
                if isinstance(response, list):
                    response = response.pop(0) if len(response) > 1 else response[0]
                if callable(response):
                    response = response(self)
                status, value = response
                encoded = json.dumps(value).encode("utf-8")
                self.send_response(status)
                if owner.redirect_to is not None:
                    self.send_header("Location", owner.redirect_to)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            do_GET = _respond
            do_POST = _respond

            def log_message(self, _format: str, *_args: object) -> None:
                return

        self.server = HTTPServer((bind_host, 0), Handler)
        self.url = f"http://{bind_host}:{self.server.server_port}"
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    def __enter__(self) -> ResponseServer:
        self.thread.start()
        return self

    def __exit__(self, *_args: object) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()


def parse_args(server_url: str, token_file: Path, *command: str):
    return dbeaver_api.build_parser().parse_args(
        ["--base-url", server_url, "--api-token-file", str(token_file), *command]
    )


def projects(*items: dict[str, object]) -> dict[str, object]:
    return {"items": list(items), "activeProjectId": "project-1"}


def project(
    project_id: str = "project-1", name: str = "Development", *, active: bool = True
) -> dict[str, object]:
    return {"id": project_id, "name": name, "active": active, "writable": True}


def datasource(
    datasource_id: str = "ds-1", name: str = "Warehouse", project_id: str = "project-1"
) -> dict[str, object]:
    return {
        "id": datasource_id,
        "name": name,
        "providerId": "postgresql",
        "driverId": "postgres-jdbc",
        "projectId": project_id,
        "projectName": "Development",
        "connected": False,
    }


def target_routes(*, datasource_items: list[dict[str, object]] | None = None) -> dict[tuple[str, str], object]:
    return {
        ("GET", "/projects"): (200, projects(project())),
        ("GET", "/datasources"): (
            200,
            [datasource()] if datasource_items is None else datasource_items,
        ),
    }


class DBeaverApiClientTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.directory = Path(self.temp.name)
        self.token_file = self.directory / "api-token"
        self.token_file.write_bytes(b"test-token._~+/==\r\n")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_request(self, text: str, filename: str = "request.json") -> Path:
        path = self.directory / filename
        path.write_text(text, encoding="utf-8")
        return path

    def test_strict_creation_json_rejects_bad_input_before_network(self):
        invalid_requests = (
            '{"mode":"guided","connection":{"x":1,"x":"SECRET_SENTINEL"}}',
            '{"mode":"guided","ignored":[1,]}',
            '{"mode":"guided","ignored":NaN}',
            '{"mode":"guided","ignored":"\\ud800"}',
            '{"mode":"guided","ignored":' + "9" * 5000 + "}",
            '{"mode":"guided","ignored":1e10001}',
            '{"mode":"guided","ignored":' + "[" * 100 + "null" + "]" * 100 + "}",
        )
        for index, source in enumerate(invalid_requests):
            with self.subTest(case=index):
                request_file = self.write_request(source)
                with ResponseServer() as server:
                    args = parse_args(
                        server.url,
                        self.token_file,
                        "create-connection",
                        "--project-id", "project-1",
                        "--request-file", str(request_file),
                    )
                    with self.assertRaises(dbeaver_api.ApiError) as caught:
                        dbeaver_api.run(args)
                    self.assertNotIn("SECRET_SENTINEL", str(caught.exception))
                    self.assertEqual(server.requests, [])

    def test_project_injection_preserves_port_precision_across_float_rounding_boundary(self):
        numeric = "5432.0000000000000000000000000001"
        source = (
            '{ "mode":"guided", "providerId":"postgresql", '
            '"driverId":"postgres-jdbc", "connection":{"port":' + numeric + '} }\n'
        )
        request_file = self.write_request(source)
        routes = target_routes()
        routes[("POST", "/datasources")] = (
            202, {"operationId": "op-1", "kind": "create", "state": "queued"}
        )
        with ResponseServer(routes=routes) as server:
            args = parse_args(
                server.url, self.token_file, "create-connection",
                "--project-id", "project-1", "--request-file", str(request_file),
            )
            result = dbeaver_api.run(args)
        self.assertEqual(result["kind"], "create")
        post = next(request for request in server.requests if request["method"] == "POST")
        body = post["body"].decode("utf-8")
        decimal = __import__("decimal").Decimal
        payload = json.loads(body, parse_float=decimal)
        self.assertEqual(payload["connection"]["port"], decimal(numeric))
        self.assertNotEqual(payload["connection"]["port"], decimal("5432"))
        self.assertEqual(payload["projectId"], "project-1")
        self.assertEqual(post["authorization"], "Bearer test-token._~+/==")
        self.assertIsNone(post["origin"])

        same_project_source = (
            '{ "projectId" : "project-1", "mode":"guided", '
            '"providerId":"postgresql","driverId":"postgres-jdbc",'
            '"connection":{"port":' + numeric + '} }\n'
        )
        same_project_file = self.write_request(same_project_source, "same-project.json")
        with ResponseServer(routes=routes) as server:
            args = parse_args(
                server.url, self.token_file, "create-connection",
                "--project-id", "project-1", "--request-file", str(same_project_file),
            )
            dbeaver_api.run(args)
        same_project_post = next(
            request for request in server.requests if request["method"] == "POST"
        )
        payload = json.loads(same_project_post["body"], parse_float=decimal)
        self.assertEqual(payload["connection"]["port"], decimal(numeric))
        self.assertEqual(payload["projectId"], "project-1")

    def test_creation_project_id_mismatch_is_rejected_before_post(self):
        request_file = self.write_request(
            '{"mode":"guided","providerId":"postgresql",'
            '"driverId":"postgres-jdbc","projectId":"project-elsewhere"}'
        )
        with ResponseServer(routes=target_routes()) as server:
            args = parse_args(
                server.url, self.token_file, "create-connection",
                "--project-id", "project-1", "--request-file", str(request_file),
            )
            with self.assertRaisesRegex(dbeaver_api.ApiError, "does not match"):
                dbeaver_api.run(args)
        self.assertEqual([request["path"] for request in server.requests], ["/projects"])

    def test_direct_create_requires_confirmed_saved_disconnected_identity(self):
        request_file = self.write_request(
            '{"mode":"direct","providerId":"postgresql",'
            '"driverId":"postgres-jdbc","name":"local-dev"}'
        )
        response = {
            "created": True,
            "saved": True,
            "connected": False,
            "datasource": {
                "id": "ds-new", "name": "local-dev", "providerId": "postgresql",
                "driverId": "postgres-jdbc", "projectId": "project-1",
            },
        }
        with ResponseServer(routes={
            **target_routes(),
            ("POST", "/datasources"): (201, response),
        }) as server:
            args = parse_args(
                server.url, self.token_file, "create-connection", "--project-id", "project-1",
                "--request-file", str(request_file),
            )
            self.assertTrue(dbeaver_api.run(args)["saved"])
        post = next(r for r in server.requests if r["method"] == "POST")
        self.assertEqual(json.loads(post["body"])["projectId"], "project-1")

        invalid = {**response, "connected": True}
        with ResponseServer(routes={
            **target_routes(),
            ("POST", "/datasources"): (201, invalid),
        }) as server:
            args = parse_args(
                server.url, self.token_file, "create-connection", "--project-id", "project-1",
                "--request-file", str(request_file),
            )
            with self.assertRaises(dbeaver_api.ApiError):
                dbeaver_api.run(args)

    def test_project_name_ambiguity_fails_before_datasource_lookup_or_post(self):
        routes = {
            ("GET", "/projects"): (200, projects(project(), project("project-2", "Development", active=False))),
            ("POST", "/datasources"): (202, {}),
        }
        request_file = self.write_request(
            '{"mode":"guided","providerId":"postgresql","driverId":"postgres-jdbc"}'
        )
        with ResponseServer(routes=routes) as server:
            args = parse_args(
                server.url, self.token_file, "create-connection", "--project", "Development",
                "--request-file", str(request_file),
            )
            with self.assertRaisesRegex(dbeaver_api.ApiError, "Ambiguous"):
                dbeaver_api.run(args)
        self.assertEqual(len(server.requests), 1)
        self.assertEqual(server.requests[0]["path"], "/projects")

    def test_datasource_name_ambiguity_and_wrong_project_id_fail_before_post(self):
        repeated = [datasource("ds-1"), datasource("ds-2")]
        routes = target_routes(datasource_items=repeated)
        with ResponseServer(routes=routes) as server:
            args = parse_args(
                server.url, self.token_file, "test-connection", "--project-id", "project-1",
                "--ds", "Warehouse",
            )
            with self.assertRaisesRegex(dbeaver_api.ApiError, "Ambiguous"):
                dbeaver_api.run(args)
        self.assertEqual([request["method"] for request in server.requests], ["GET"])

        with ResponseServer(routes=target_routes()) as server:
            args = parse_args(
                server.url, self.token_file, "edit-connection", "--project-id", "project-1",
                "--ds-id", "other-project-ds", "--request-file",
                str(self.write_request('{"name":"renamed"}', "edit.json")),
            )
            with self.assertRaisesRegex(dbeaver_api.ApiError, "No exact match"):
                dbeaver_api.run(args)
        self.assertFalse(any(request["method"] == "POST" for request in server.requests))

    def test_old_implicit_datasource_selection_is_not_accepted(self):
        with self.assertRaises(SystemExit):
            dbeaver_api.build_parser().parse_args(["datasources"])
        with self.assertRaises(SystemExit):
            dbeaver_api.build_parser().parse_args(["schema", "--ds", "Warehouse"])

    def test_schema_and_execute_send_native_ids_but_no_admin_token_to_data_routes(self):
        routes = target_routes()
        routes[("GET", "/schema")] = (200, {"items": []})
        routes[("POST", "/execute")] = (200, {"resultKind": "rows", "records": []})
        with ResponseServer(routes=routes) as server:
            schema = parse_args(
                server.url, self.directory / "missing-token", "schema", "--project-id", "project-1",
                "--ds-id", "ds-1",
            )
            self.assertEqual(dbeaver_api.run(schema), {"items": []})
            execute = parse_args(
                server.url, self.token_file, "execute", "--project", "Development",
                "--ds", "Warehouse", "--sql", "SELECT 1 AS ok",
            )
            dbeaver_api.run(execute)
        schema_request = next(r for r in server.requests if r["path"].startswith("/schema"))
        execute_request = next(r for r in server.requests if r["path"].startswith("/execute"))
        self.assertEqual(parse_qs(urlsplit(schema_request["path"]).query),
                         {"projectId": ["project-1"], "dsId": ["ds-1"]})
        self.assertEqual(parse_qs(urlsplit(execute_request["path"]).query)["dsId"], ["ds-1"])
        self.assertIsNone(schema_request["authorization"])
        self.assertIsNone(execute_request["authorization"])
        datasource_request = next(r for r in server.requests if r["path"].startswith("/datasources"))
        self.assertIsNone(datasource_request["authorization"])
        self.assertEqual(parse_qs(urlsplit(datasource_request["path"]).query),
                         {"projectId": ["project-1"]})
        project_request = next(r for r in server.requests if urlsplit(r["path"]).path == "/projects")
        self.assertEqual(project_request["authorization"], "Bearer test-token._~+/==")

    def test_inspection_is_project_qualified_and_requires_safe_identity(self):
        routes = target_routes()
        routes[("GET", "/datasources/ds-1")] = (
            200,
            {"datasource": datasource(), "saved": True, "connected": False,
             "configuration": {"host": "db.local", "port": 27017}},
        )
        with ResponseServer(routes=routes) as server:
            args = parse_args(
                server.url, self.token_file, "inspect-connection", "--project-id", "project-1",
                "--ds-id", "ds-1",
            )
            result = dbeaver_api.run(args)
        inspect = next(r for r in server.requests if r["path"].startswith("/datasources/ds-1"))
        self.assertEqual(parse_qs(urlsplit(inspect["path"]).query), {"projectId": ["project-1"]})
        self.assertEqual(inspect["authorization"], "Bearer test-token._~+/==")
        self.assertTrue(result["saved"])
        self.assertFalse(result["connected"])

        wrong_identity = {**datasource(), "projectId": "project-other"}
        routes = target_routes()
        routes[("GET", "/datasources/ds-1")] = (
            200,
            {"datasource": wrong_identity, "saved": True, "connected": False,
             "configuration": {"host": "db.local"}},
        )
        with ResponseServer(routes=routes) as server:
            args = parse_args(
                server.url, self.token_file, "inspect-connection",
                "--project-id", "project-1", "--ds-id", "ds-1",
            )
            with self.assertRaisesRegex(dbeaver_api.ApiError, "wrong-target"):
                dbeaver_api.run(args)

    def test_edit_and_test_are_single_admin_posts_with_accurate_bodies(self):
        routes = target_routes()
        routes[("POST", "/datasources/ds-1/edit")] = (
            202, {"operationId": "edit-1", "kind": "edit", "state": "queued"}
        )
        routes[("POST", "/datasources/ds-1/test")] = (
            202, {"operationId": "test-1", "kind": "test", "state": "queued"}
        )
        edit_file = self.write_request('{"name":"New name","connection":{"database":"admin"}}', "edit.json")
        with ResponseServer(routes=routes) as server:
            edit = parse_args(
                server.url, self.token_file, "edit-connection", "--project-id", "project-1",
                "--ds-id", "ds-1", "--request-file", str(edit_file),
            )
            self.assertEqual(dbeaver_api.run(edit)["kind"], "edit")
            test = parse_args(
                server.url, self.token_file, "test-connection", "--project-id", "project-1",
                "--ds-id", "ds-1",
            )
            self.assertEqual(dbeaver_api.run(test)["kind"], "test")
            explicit_test = parse_args(
                server.url, self.token_file, "test-connection", "--project-id", "project-1",
                "--ds-id", "ds-1", "--timeout", "20",
            )
            dbeaver_api.run(explicit_test)
        posts = [request for request in server.requests if request["method"] == "POST"]
        self.assertEqual(len(posts), 3)
        self.assertEqual(posts[0]["body"], edit_file.read_bytes())
        self.assertEqual(json.loads(posts[1]["body"]), {})
        self.assertEqual(json.loads(posts[2]["body"]), {"timeout": 20})
        self.assertTrue(all(request["authorization"] == "Bearer test-token._~+/==" for request in posts))

    def test_operation_kinds_validate_completion_and_test_does_not_imply_connected(self):
        outcomes = (
            ("create", "saved", True, True, True,
             {"id": "ds-new", "name": "Created", "providerId": "p", "driverId": "d", "projectId": "project-1"}, None),
            ("edit", "saved", False, True, True,
             {"id": "ds-1", "name": "Warehouse", "providerId": "p", "driverId": "d", "projectId": "project-1"}, None),
            ("test", "succeeded", False, False, False,
             {"id": "ds-1", "name": "Warehouse", "providerId": "p", "driverId": "d", "projectId": "project-1"}, {"passed": True}),
        )
        for kind, state, created, saved, connected, identity, test in outcomes:
            with self.subTest(kind=kind):
                outcome = {
                    "operationId": "op-1", "kind": kind, "state": state,
                    "created": created, "saved": saved, "connected": connected,
                    "datasource": identity,
                }
                if test is not None:
                    outcome["test"] = test
                with ResponseServer(200, outcome) as server:
                    args = parse_args(
                        server.url, self.token_file, "connection-operation", "--id", "op-1"
                    )
                    self.assertEqual(dbeaver_api.run(args), outcome)

    def test_contradictory_operation_and_terminal_failures_are_nonzero_with_residual_json(self):
        contradictory = {
            "operationId": "op-1", "kind": "test", "state": "succeeded",
            "created": False, "saved": True, "connected": False, "test": {"passed": False},
            "secret": "must-not-be-echoed",
        }
        with ResponseServer(200, contradictory) as server:
            args = parse_args(server.url, self.token_file, "connection-operation", "--id", "op-1")
            with self.assertRaises(dbeaver_api.ApiError) as caught:
                dbeaver_api.run(args)
        self.assertNotIn("secret", caught.exception.payload)

        failed_test = {
            "operationId": "op-1", "kind": "test", "state": "failed",
            "created": False, "saved": False, "connected": True,
            "datasource": {"id": "ds-1", "name": "Warehouse", "providerId": "p",
                           "driverId": "d", "projectId": "project-1"},
            "test": {"passed": False},
            "diagnostic": {"category": "provider_configuration"},
        }
        with ResponseServer(200, failed_test) as server:
            args = parse_args(server.url, self.token_file, "connection-operation", "--id", "op-1")
            with self.assertRaises(dbeaver_api.ApiError) as caught:
                dbeaver_api.run(args)
        self.assertEqual(caught.exception.payload["test"]["passed"], False)
        self.assertEqual(caught.exception.payload["connected"], True)

        for state in ("failed", "cancelled"):
            response = {
                "operationId": "op-1", "kind": "create", "state": state,
                "created": True, "saved": False, "connected": False,
                "datasource": {"id": "partial-id", "name": "disposable", "providerId": "p",
                               "driverId": "d", "projectId": "project-1"},
                "diagnostic": {"category": "provider_configuration", "message": "safe detail"},
                "code": "provider_configuration", "error": "Saved profile could not be tested",
            }
            with self.subTest(state=state), ResponseServer(200, response) as server:
                argv = ["dbeaver_api.py", "--base-url", server.url,
                        "--api-token-file", str(self.token_file), "connection-operation",
                        "--id", "op-1"]
                stdout, stderr = io.StringIO(), io.StringIO()
                with patch.object(dbeaver_api.sys, "argv", argv):
                    with redirect_stdout(stdout), redirect_stderr(stderr):
                        exit_code = dbeaver_api.main()
                self.assertEqual(exit_code, 1)
                self.assertEqual(stdout.getvalue(), "")
                reported = json.loads(stderr.getvalue())
                self.assertEqual(reported["state"], state)
                self.assertEqual(reported["datasource"]["id"], "partial-id")
                self.assertEqual(reported["diagnostic"]["category"], "provider_configuration")

    def test_wait_deadline_returns_pending_residual_and_uses_get_only(self):
        pending = {
            "operationId": "op-1", "kind": "edit", "state": "awaiting_input",
            "created": False, "saved": True, "connected": False,
            "diagnostic": {"category": "native_interaction_required"},
        }
        with ResponseServer(200, pending) as server:
            args = parse_args(
                server.url, self.token_file, "connection-operation", "--id", "op-1",
                "--wait", "--wait-timeout", "1",
            )
            with patch.object(dbeaver_api.time, "monotonic", side_effect=[0.0, 0.0, 1.1]):
                with patch.object(dbeaver_api.time, "sleep"):
                    with self.assertRaisesRegex(dbeaver_api.ApiError, "wait deadline") as caught:
                        dbeaver_api.run(args)
        self.assertEqual(caught.exception.payload["state"], "awaiting_input")
        self.assertEqual([(r["method"], r["path"]) for r in server.requests],
                         [("GET", "/connection-operations/op-1")])


    def test_wait_polls_get_until_verified_test_success(self):
        pending = {
            "operationId": "op-1", "kind": "test", "state": "running",
            "created": False, "saved": False, "connected": True,
        }
        complete = {
            "operationId": "op-1", "kind": "test", "state": "succeeded",
            "created": False, "saved": False, "connected": True,
            "datasource": datasource(), "test": {"passed": True},
        }
        routes = {
            ("GET", "/connection-operations/op-1"): [
                (200, pending), (200, complete),
            ],
        }
        with ResponseServer(routes=routes) as server:
            args = parse_args(
                server.url, self.token_file, "connection-operation", "--id", "op-1",
                "--wait", "--wait-timeout", "2",
            )
            with patch.object(dbeaver_api.time, "monotonic", side_effect=[0.0, 0.0, 0.5]):
                with patch.object(dbeaver_api.time, "sleep"):
                    result = dbeaver_api.run(args)
        self.assertTrue(result["test"]["passed"])
        self.assertTrue(result["connected"])
        self.assertFalse(result["saved"])
        self.assertEqual([request["method"] for request in server.requests], ["GET", "GET"])

    def test_creation_http_failure_is_not_replayed(self):
        routes = target_routes()
        routes[("POST", "/datasources")] = (500, {"error": "transient"})
        request_file = self.write_request(
            '{"mode":"guided","providerId":"postgresql","driverId":"postgres-jdbc"}'
        )
        with ResponseServer(routes=routes) as server:
            args = parse_args(
                server.url, self.token_file, "create-connection", "--project-id", "project-1",
                "--request-file", str(request_file),
            )
            with self.assertRaises(dbeaver_api.ApiError):
                dbeaver_api.run(args)
        self.assertEqual(sum(r["method"] == "POST" for r in server.requests), 1)

    def test_admin_redirect_is_not_followed_and_remote_admin_url_is_refused(self):
        with ResponseServer() as destination:
            target = destination.url + "/capture"
            with ResponseServer(302, {}, redirect_to=target) as source:
                args = parse_args(source.url, self.token_file, "projects")
                with self.assertRaisesRegex(dbeaver_api.ApiError, "redirect refused"):
                    dbeaver_api.run(args)
            self.assertEqual(len(source.requests), 1)
            self.assertEqual(destination.requests, [])

        args = parse_args("https://api.example.test", self.token_file, "projects")
        with self.assertRaises(dbeaver_api.ApiError):
            dbeaver_api.run(args)

    def test_project_discovery_requires_valid_token_and_data_routes_get_none(self):
        bad_token = self.directory / "invalid-token"
        bad_token.write_bytes(b"invalid token\n")
        with ResponseServer(routes=target_routes()) as server:
            args = parse_args(server.url, bad_token, "projects")
            with self.assertRaises(dbeaver_api.ApiError):
                dbeaver_api.run(args)
            self.assertEqual(server.requests, [])

        routes = target_routes()
        routes[("GET", "/schema")] = (200, {"items": []})
        with ResponseServer(routes=routes) as server:
            datasource_list = parse_args(
                server.url, self.directory / "missing-token", "datasources",
                "--project-id", "project-1",
            )
            self.assertEqual(dbeaver_api.run(datasource_list), [datasource()])
            args = parse_args(
                server.url, self.directory / "missing-token", "schema", "--project-id", "project-1", "--ds-id", "ds-1"
            )
            dbeaver_api.run(args)
        data_calls = [r for r in server.requests if urlsplit(r["path"]).path in ("/datasources", "/schema")]
        self.assertTrue(data_calls)
        self.assertTrue(all(r["authorization"] is None for r in data_calls))


if __name__ == "__main__":
    unittest.main()

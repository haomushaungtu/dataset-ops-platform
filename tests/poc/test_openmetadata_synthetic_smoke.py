from __future__ import annotations

import importlib.util
import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse


SCRIPT = (
    Path(__file__).resolve().parents[2]
    / "deploy"
    / "scripts"
    / "openmetadata_synthetic_smoke.py"
)
SPEC = importlib.util.spec_from_file_location("openmetadata_synthetic_smoke", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
smoke = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = smoke
SPEC.loader.exec_module(smoke)


class FakeOpenMetadataHandler(BaseHTTPRequestHandler):
    service_deleted = False
    omit_search_hits = False
    table_id = "00000000-0000-0000-0000-000000000004"

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def send_json(self, status: int, payload: object | None) -> None:
        body = b"" if payload is None else json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def read_json(self) -> dict[str, object]:
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length))

    def do_POST(self) -> None:
        payload = self.read_json()
        if self.path == "/api/v1/users/login":
            self.send_json(200, {"accessToken": "mock-token"})
            return
        if self.headers.get("Authorization") != "Bearer mock-token":
            self.send_json(401, {"message": "unauthorized"})
            return
        if self.path == "/api/v1/services/databaseServices":
            connection = payload.get("connection", {}).get("config", {})
            if connection.get("hostPort") != "127.0.0.1:1":
                self.send_json(400, {"message": "non-isolated connection"})
                return
            self.__class__.service_deleted = False
            self.send_json(
                201,
                {
                    "id": "00000000-0000-0000-0000-000000000001",
                    "fullyQualifiedName": payload["name"],
                },
            )
        elif self.path == "/api/v1/databases":
            self.send_json(
                201,
                {
                    "id": "00000000-0000-0000-0000-000000000002",
                    "fullyQualifiedName": f"{payload['service']}.{payload['name']}",
                },
            )
        elif self.path == "/api/v1/databaseSchemas":
            self.send_json(
                201,
                {
                    "id": "00000000-0000-0000-0000-000000000003",
                    "fullyQualifiedName": f"{payload['database']}.{payload['name']}",
                },
            )
        elif self.path == "/api/v1/tables":
            self.send_json(
                201,
                {
                    "id": self.table_id,
                    "fullyQualifiedName": f"{payload['databaseSchema']}.{payload['name']}",
                },
            )
        else:
            self.send_json(404, {"message": "not found"})

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path == f"/api/v1/tables/{self.table_id}":
            self.send_json(200, {"id": self.table_id})
        elif parsed.path == "/api/v1/search/query":
            hits = []
            if not self.__class__.service_deleted and not self.__class__.omit_search_hits:
                hits = [{"_id": self.table_id, "_source": {"id": self.table_id}}]
            self.send_json(
                200,
                {"hits": {"total": {"value": len(hits), "relation": "eq"}, "hits": hits}},
            )
        elif parsed.path.startswith("/api/v1/services/databaseServices/"):
            if self.__class__.service_deleted:
                self.send_json(404, {"message": "not found"})
            else:
                self.send_json(200, {"id": "00000000-0000-0000-0000-000000000001"})
        else:
            self.send_json(404, {"message": "not found"})

    def do_DELETE(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path.startswith("/api/v1/services/databaseServices/"):
            query = parse_qs(parsed.query)
            if query.get("hardDelete") != ["true"] or query.get("recursive") != ["true"]:
                self.send_json(400, {"message": "unsafe cleanup request"})
                return
            self.__class__.service_deleted = True
            self.send_json(200, {"status": "deleted"})
        else:
            self.send_json(404, {"message": "not found"})


class SyntheticSmokeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeOpenMetadataHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def setUp(self) -> None:
        FakeOpenMetadataHandler.service_deleted = False
        FakeOpenMetadataHandler.omit_search_hits = False

    def fixture(self) -> Path:
        return (
            Path(__file__).resolve().parents[1]
            / "fixtures"
            / "synthetic"
            / "cancer-registry.csv"
        )

    def test_crud_search_and_recursive_cleanup(self) -> None:
        base_url = f"http://127.0.0.1:{self.server.server_port}/api"
        client = smoke.OpenMetadataClient(base_url)
        evidence = smoke.run_smoke(
            client, self.fixture(), "poc@example.invalid", "not-a-secret", 2
        )

        self.assertEqual("passed-and-cleaned", evidence["result"])
        self.assertTrue(evidence["cleanup_verified"])
        self.assertEqual(8, evidence["fixture_rows"])
        self.assertEqual(10, evidence["fixture_columns"])
        self.assertEqual(201, evidence["table_create_http_status"])
        self.assertEqual(404, evidence["service_absent_http_status"])

    def test_search_failure_still_recursively_cleans_service(self) -> None:
        FakeOpenMetadataHandler.omit_search_hits = True
        base_url = f"http://127.0.0.1:{self.server.server_port}/api"
        client = smoke.OpenMetadataClient(base_url)

        with self.assertRaises(smoke.SmokeRunError) as raised:
            smoke.run_smoke(
                client, self.fixture(), "poc@example.invalid", "not-a-secret", 0
            )

        self.assertTrue(FakeOpenMetadataHandler.service_deleted)
        self.assertTrue(raised.exception.evidence["cleanup_verified"])
        self.assertNotIn("mock-token", str(raised.exception))
        self.assertNotIn("not-a-secret", str(raised.exception))


if __name__ == "__main__":
    unittest.main()

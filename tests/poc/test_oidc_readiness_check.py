from __future__ import annotations

import importlib.util
import json
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


SCRIPT = (
    Path(__file__).resolve().parents[2]
    / "deploy"
    / "scripts"
    / "oidc_readiness_check.py"
)
SPEC = importlib.util.spec_from_file_location("oidc_readiness_check", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
oidc = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = oidc
SPEC.loader.exec_module(oidc)


class FakeOidcHandler(BaseHTTPRequestHandler):
    include_s256 = True

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def send_json(self, payload: object) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        issuer = f"http://127.0.0.1:{self.server.server_port}/issuer"
        if self.path == "/issuer/.well-known/openid-configuration":
            self.send_json(
                {
                    "issuer": issuer,
                    "authorization_endpoint": issuer + "/authorize",
                    "token_endpoint": issuer + "/token",
                    "userinfo_endpoint": issuer + "/userinfo",
                    "end_session_endpoint": issuer + "/logout",
                    "jwks_uri": issuer + "/jwks",
                    "scopes_supported": ["openid", "email", "profile"],
                    "response_types_supported": ["code"],
                    "grant_types_supported": ["authorization_code"],
                    "code_challenge_methods_supported": (
                        ["S256"] if self.__class__.include_s256 else ["plain"]
                    ),
                    "token_endpoint_auth_methods_supported": [
                        "client_secret_post",
                        "client_secret_basic",
                    ],
                    "id_token_signing_alg_values_supported": ["RS256"],
                    "claims_supported": ["sub", "email"],
                }
            )
        elif self.path == "/issuer/jwks":
            self.send_json({"keys": [{"kty": "RSA", "kid": "test-key"}]})
        else:
            self.send_response(404)
            self.end_headers()


class OidcReadinessCheckTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeOidcHandler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls) -> None:
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join(timeout=5)

    def setUp(self) -> None:
        FakeOidcHandler.include_s256 = True

    def issuer(self) -> str:
        return f"http://127.0.0.1:{self.server.server_port}/issuer"

    def test_valid_discovery_and_jwks_pass(self) -> None:
        evidence = oidc.validate_oidc(
            self.issuer() + "/.well-known/openid-configuration",
            self.issuer(),
            allow_http=True,
        )

        self.assertEqual("passed-discovery-and-jwks", evidence["result"])
        self.assertTrue(evidence["issuer_match"])
        self.assertEqual(1, evidence["jwks_key_count"])
        self.assertEqual(["RSA"], evidence["jwks_key_types"])

    def test_missing_s256_fails_closed(self) -> None:
        FakeOidcHandler.include_s256 = False

        with self.assertRaises(oidc.OidcError) as raised:
            oidc.validate_oidc(
                self.issuer() + "/.well-known/openid-configuration",
                self.issuer(),
                allow_http=True,
            )

        self.assertEqual(
            "code_challenge_methods_supported-missing-S256", raised.exception.reason
        )
        self.assertEqual("failed", raised.exception.evidence["result"])

    def test_issuer_mismatch_fails_closed(self) -> None:
        with self.assertRaises(oidc.OidcError) as raised:
            oidc.validate_oidc(
                self.issuer() + "/.well-known/openid-configuration",
                self.issuer() + "/other",
                allow_http=True,
            )

        self.assertEqual("issuer-mismatch", raised.exception.reason)

    def test_cleartext_public_host_is_rejected_even_for_poc(self) -> None:
        with self.assertRaises(oidc.OidcError) as raised:
            oidc.validate_oidc(
                "http://8.8.8.8/.well-known/openid-configuration",
                "http://8.8.8.8",
                allow_http=True,
            )

        self.assertEqual("discovery-http-host-is-not-private", raised.exception.reason)


if __name__ == "__main__":
    unittest.main()

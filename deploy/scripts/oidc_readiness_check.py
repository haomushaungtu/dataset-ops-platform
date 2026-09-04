#!/usr/bin/env python3
"""Read-only OIDC discovery/JWKS readiness check for the native PoC host."""

from __future__ import annotations

import argparse
import ipaddress
import json
import os
import socket
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


POC_LOG_ROOT = Path("/szah/dataset-foundry-poc/logs")
MAX_JSON_BYTES = 1024 * 1024


class OidcError(RuntimeError):
    def __init__(self, reason: str, evidence: dict[str, Any]) -> None:
        self.reason = reason
        self.evidence = evidence
        super().__init__(reason)


def sanitized_url(value: str) -> str:
    parsed = urllib.parse.urlsplit(value)
    return urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path, "", ""))


def require_url(value: Any, field: str, allow_http: bool, evidence: dict[str, Any]) -> str:
    if not isinstance(value, str):
        raise OidcError(f"{field}-missing", evidence)
    parsed = urllib.parse.urlsplit(value)
    if not parsed.hostname or parsed.scheme not in {"http", "https"}:
        raise OidcError(f"{field}-is-not-an-absolute-http-url", evidence)
    if parsed.username or parsed.password or parsed.fragment:
        raise OidcError(f"{field}-contains-forbidden-url-component", evidence)
    if parsed.scheme != "https" and not allow_http:
        raise OidcError(f"{field}-must-use-https", evidence)
    if parsed.scheme == "http" and allow_http:
        try:
            addresses = {
                row[4][0]
                for row in socket.getaddrinfo(parsed.hostname, parsed.port or 80, type=socket.SOCK_STREAM)
            }
        except socket.gaierror as exc:
            raise OidcError(f"{field}-host-is-not-resolvable", evidence) from exc
        if not addresses or any(
            not (ipaddress.ip_address(address).is_private or ipaddress.ip_address(address).is_loopback)
            for address in addresses
        ):
            raise OidcError(f"{field}-http-host-is-not-private", evidence)
    evidence.setdefault("endpoints", {})[field] = sanitized_url(value)
    return value


def fetch_json(url: str, allow_http: bool, evidence: dict[str, Any], label: str) -> Any:
    require_url(url, label + "_url", allow_http, evidence)
    request = urllib.request.Request(
        url,
        headers={"Accept": "application/json", "User-Agent": "dataset-foundry-oidc-poc/0.1"},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            evidence[label + "_http_status"] = response.status
            final_url = response.geturl()
            require_url(final_url, label + "_final_url", allow_http, evidence)
            body = response.read(MAX_JSON_BYTES + 1)
    except urllib.error.HTTPError as exc:
        evidence[label + "_http_status"] = exc.code
        exc.close()
        raise OidcError(label + "-http-error", evidence) from exc
    except (urllib.error.URLError, TimeoutError) as exc:
        raise OidcError(label + "-transport-error", evidence) from exc
    if len(body) > MAX_JSON_BYTES:
        raise OidcError(label + "-response-too-large", evidence)
    try:
        return json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise OidcError(label + "-invalid-json", evidence) from exc


def require_member(
    payload: dict[str, Any], field: str, required: str, evidence: dict[str, Any]
) -> None:
    values = payload.get(field, [])
    present = isinstance(values, list) and required in values
    evidence[field + "_has_" + required.lower()] = present
    if not present:
        raise OidcError(f"{field}-missing-{required}", evidence)


def validate_oidc(
    discovery_url: str, expected_issuer: str, allow_http: bool = False
) -> dict[str, Any]:
    evidence: dict[str, Any] = {
        "result": "running",
        "http_allowed_for_poc": allow_http,
    }
    try:
        require_url(discovery_url, "discovery", allow_http, evidence)
        require_url(expected_issuer, "expected_issuer", allow_http, evidence)
        discovery = fetch_json(discovery_url, allow_http, evidence, "discovery")
        if not isinstance(discovery, dict):
            raise OidcError("discovery-root-is-not-object", evidence)

        issuer = require_url(discovery.get("issuer"), "issuer", allow_http, evidence)
        evidence["issuer_match"] = issuer.rstrip("/") == expected_issuer.rstrip("/")
        if not evidence["issuer_match"]:
            raise OidcError("issuer-mismatch", evidence)

        require_url(
            discovery.get("authorization_endpoint"),
            "authorization_endpoint",
            allow_http,
            evidence,
        )
        require_url(discovery.get("token_endpoint"), "token_endpoint", allow_http, evidence)
        require_url(discovery.get("userinfo_endpoint"), "userinfo_endpoint", allow_http, evidence)
        require_url(
            discovery.get("end_session_endpoint"),
            "end_session_endpoint",
            allow_http,
            evidence,
        )
        jwks_uri = require_url(discovery.get("jwks_uri"), "jwks_uri", allow_http, evidence)

        require_member(discovery, "scopes_supported", "openid", evidence)
        require_member(discovery, "scopes_supported", "email", evidence)
        require_member(discovery, "scopes_supported", "profile", evidence)
        require_member(discovery, "response_types_supported", "code", evidence)
        require_member(discovery, "grant_types_supported", "authorization_code", evidence)
        require_member(discovery, "code_challenge_methods_supported", "S256", evidence)
        require_member(
            discovery,
            "token_endpoint_auth_methods_supported",
            "client_secret_post",
            evidence,
        )
        require_member(discovery, "id_token_signing_alg_values_supported", "RS256", evidence)
        require_member(discovery, "claims_supported", "sub", evidence)
        require_member(discovery, "claims_supported", "email", evidence)

        jwks = fetch_json(jwks_uri, allow_http, evidence, "jwks")
        keys = jwks.get("keys", []) if isinstance(jwks, dict) else []
        evidence["jwks_key_count"] = len(keys) if isinstance(keys, list) else 0
        if not isinstance(keys, list) or not keys:
            raise OidcError("jwks-has-no-keys", evidence)
        key_types = sorted(
            {
                str(key.get("kty"))
                for key in keys
                if isinstance(key, dict) and key.get("kty")
            }
        )
        evidence["jwks_key_types"] = key_types
        if "RSA" not in key_types:
            raise OidcError("jwks-has-no-rsa-key-for-rs256", evidence)
        evidence["jwks_keys_with_kid"] = sum(
            1 for key in keys if isinstance(key, dict) and key.get("kid")
        )
        if evidence["jwks_keys_with_kid"] == 0:
            raise OidcError("jwks-has-no-key-id", evidence)
        evidence["result"] = "passed-discovery-and-jwks"
        return evidence
    except OidcError:
        evidence["result"] = "failed"
        raise


def path_is_within(path: Path, root: Path) -> bool:
    try:
        path.resolve(strict=False).relative_to(root.resolve(strict=False))
        return True
    except ValueError:
        return False


def write_evidence(path: Path, evidence: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            json.dump(evidence, stream, ensure_ascii=False, indent=2, sort_keys=True)
            stream.write("\n")
        os.replace(temporary, path)
        if os.name == "posix":
            os.chmod(path, 0o600)
    finally:
        if temporary.exists():
            temporary.unlink()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--discovery-url", required=True)
    parser.add_argument("--expected-issuer", required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument(
        "--poc-allow-http",
        action="store_true",
        help="Allow cleartext HTTP only for an explicitly accepted isolated PoC.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not path_is_within(args.evidence, POC_LOG_ROOT):
        print("OIDC_READINESS=blocked", file=sys.stderr)
        print("BLOCKER=evidence-file-outside-poc-log-root", file=sys.stderr)
        return 2
    if not args.evidence.name.startswith("oidc-readiness-") or args.evidence.suffix != ".json":
        print("OIDC_READINESS=blocked", file=sys.stderr)
        print("BLOCKER=evidence-file-name-is-not-scoped", file=sys.stderr)
        return 2
    if args.evidence.is_symlink():
        print("OIDC_READINESS=blocked", file=sys.stderr)
        print("BLOCKER=evidence-file-must-not-be-symlink", file=sys.stderr)
        return 2
    try:
        evidence = validate_oidc(
            args.discovery_url, args.expected_issuer, allow_http=args.poc_allow_http
        )
    except OidcError as exc:
        exc.evidence["blocker"] = exc.reason
        write_evidence(args.evidence, exc.evidence)
        print("OIDC_READINESS=failed", file=sys.stderr)
        print("BLOCKER=" + exc.reason, file=sys.stderr)
        print(f"EVIDENCE={args.evidence}", file=sys.stderr)
        return 1
    write_evidence(args.evidence, evidence)
    print("OIDC_READINESS=passed-discovery-and-jwks")
    print(f"EVIDENCE={args.evidence}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

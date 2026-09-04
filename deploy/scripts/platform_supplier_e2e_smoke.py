#!/usr/bin/env python3
"""Run a synthetic IAM -> platform -> Flowable -> PostgreSQL supplier smoke flow."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import secrets
import string
import sys
import time
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import parse_qs, urlencode, urljoin, urlparse

import requests

SYNTHETIC_PNG = b"\x89PNG\r\n\x1a\nsynthetic-qualification-material"


class CsrfParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.value: str | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "input" and values.get("name") == "_csrf":
            self.value = values.get("value")


def load_env(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            result[key] = value.strip().strip('"').strip("'")
    return result


def require(response: requests.Response, expected: int, label: str) -> requests.Response:
    if response.status_code != expected:
        raise RuntimeError(f"{label} failed with HTTP {response.status_code}")
    return response


def random_password() -> str:
    alphabet = string.ascii_letters + string.digits + "-_.!@#"
    return "Poc!" + "".join(secrets.choice(alphabet) for _ in range(24))


def login(env: dict[str, str], username: str, password: str) -> str:
    issuer = env["DF_IAM_ISSUER"].rstrip("/")
    client_id = env["DF_IAM_OPENMETADATA_CLIENT_ID"]
    client_secret = env["DF_IAM_OPENMETADATA_CLIENT_SECRET"]
    redirect_uri = env["DF_IAM_OPENMETADATA_REDIRECT_URIS"].split(",", 1)[0].strip()
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")
    state = secrets.token_urlsafe(24)
    params = {
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "scope": "openid profile email groups",
        "state": state,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
    }
    session = requests.Session()
    page = require(session.get(issuer + "/oauth2/authorize", params=params, timeout=10), 200, "login page")
    parser = CsrfParser()
    parser.feed(page.text)
    if not parser.value:
        raise RuntimeError("login page did not contain a CSRF token")
    response = session.post(
        issuer + "/login",
        data={"username": username, "password": password, "_csrf": parser.value},
        allow_redirects=False,
        timeout=10,
    )
    code: str | None = None
    for _ in range(8):
        if response.status_code not in (301, 302, 303, 307, 308):
            raise RuntimeError(f"authorization redirect failed with HTTP {response.status_code}")
        location = urljoin(issuer + "/", response.headers["Location"])
        parsed = urlparse(location)
        values = parse_qs(parsed.query)
        if "code" in values:
            if values.get("state", [None])[0] != state:
                raise RuntimeError("authorization state mismatch")
            code = values["code"][0]
            break
        response = session.get(location, allow_redirects=False, timeout=10)
    if not code:
        raise RuntimeError("authorization code was not returned")
    token = requests.post(
        issuer + "/oauth2/token",
        auth=(client_id, client_secret),
        data={
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": redirect_uri,
            "code_verifier": verifier,
        },
        timeout=10,
    )
    require(token, 200, "token exchange")
    return token.json()["access_token"]


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": "Bearer " + token}


def upload_material(
    platform: str,
    application_id: str,
    token: str,
    etag: str,
    key: str,
    content: bytes,
    expected: int = 201,
) -> requests.Response:
    response = requests.post(
        f"{platform}/api/v1/supplier-applications/{application_id}/materials",
        headers={
            **bearer(token),
            "If-Match": etag,
            "Idempotency-Key": key,
        },
        data={"material_type": "BUSINESS_LICENSE"},
        files={"file": ("synthetic-license.png", content, "image/png")},
        timeout=30,
    )
    return require(response, expected, "upload supplier qualification material")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--identity-env", type=Path, required=True)
    parser.add_argument("--platform-url", default="http://10.100.165.139:19100")
    parser.add_argument("--evidence", type=Path)
    args = parser.parse_args()
    env = load_env(args.identity_env)
    suffix = f"{int(time.time())}{secrets.token_hex(2)}"
    applicant_username = "poc.applicant." + suffix
    operator_username = "poc.operator." + suffix
    applicant_password = random_password()
    operator_password = random_password()
    evidence: dict[str, object] = {"result": "running", "synthetic": True}

    admin_token = login(
        env,
        env["DF_IAM_BOOTSTRAP_ADMIN_USERNAME"],
        env["DF_IAM_BOOTSTRAP_ADMIN_PASSWORD"],
    )
    identity = env["DF_IAM_ISSUER"].rstrip("/")
    users = [
        (applicant_username, applicant_password, "BUYER"),
        (operator_username, operator_password, "OPERATOR"),
    ]
    for username, password, role in users:
        response = requests.post(
            identity + "/api/v1/admin/users",
            headers={**bearer(admin_token), "Content-Type": "application/json"},
            json={
                "username": username,
                "password": password,
                "email": username + "@synthetic.invalid",
                "displayName": "Synthetic " + role.title(),
                "emailVerified": True,
                "roles": [role],
            },
            timeout=10,
        )
        require(response, 201, "create synthetic " + role.lower())

    applicant_token = login(env, applicant_username, applicant_password)
    operator_token = login(env, operator_username, operator_password)
    platform = args.platform_url.rstrip("/")
    credit_code = ("91" + suffix.upper().replace(".", "") + "0000000000000000")[:18]
    create = requests.post(
        platform + "/api/v1/supplier-applications",
        headers={**bearer(applicant_token), "Idempotency-Key": "e2e-create-" + suffix},
        json={
            "organization_name": "合成数据供应商-" + suffix,
            "unified_social_credit_code": credit_code,
            "contact_name": "脱敏测试联系人",
            "contact_phone": "138****0000",
        },
        timeout=15,
    )
    require(create, 201, "create supplier application")
    application_id = create.json()["data"]["id"]
    update_draft = requests.put(
        f"{platform}/api/v1/supplier-applications/{application_id}",
        headers={
            **bearer(applicant_token),
            "If-Match": create.headers["ETag"],
            "Idempotency-Key": "e2e-update-draft-" + suffix,
        },
        json={
            "organization_name": "合成数据供应商-已修改-" + suffix,
            "unified_social_credit_code": credit_code,
            "contact_name": "脱敏草稿联系人",
            "contact_phone": "137****0000",
        },
        timeout=15,
    )
    require(update_draft, 200, "update draft supplier application")
    first_material_key = "e2e-material-v1-" + suffix
    first_material = upload_material(
        platform,
        application_id,
        applicant_token,
        update_draft.headers["ETag"],
        first_material_key,
        SYNTHETIC_PNG,
    )
    first_material_id = first_material.json()["data"]["material"]["id"]
    first_material_replay = upload_material(
        platform,
        application_id,
        applicant_token,
        update_draft.headers["ETag"],
        first_material_key,
        SYNTHETIC_PNG,
        expected=200,
    )
    if first_material_replay.json()["data"]["material"]["id"] != first_material_id:
        raise RuntimeError("material idempotent replay returned a different material")
    submit = requests.post(
        f"{platform}/api/v1/supplier-applications/{application_id}:submit",
        headers={
            **bearer(applicant_token),
            "If-Match": first_material.headers["ETag"],
            "Idempotency-Key": "e2e-submit-" + suffix,
        },
        timeout=15,
    )
    require(submit, 200, "submit supplier application")
    returned = requests.post(
        f"{platform}/api/v1/supplier-applications/{application_id}:return",
        headers={
            **bearer(operator_token),
            "If-Match": submit.headers["ETag"],
            "Idempotency-Key": "e2e-return-" + suffix,
        },
        json={"comment": "请修改合成联系人信息"},
        timeout=15,
    )
    require(returned, 200, "return supplier application")
    update_returned = requests.put(
        f"{platform}/api/v1/supplier-applications/{application_id}",
        headers={
            **bearer(applicant_token),
            "If-Match": returned.headers["ETag"],
            "Idempotency-Key": "e2e-update-returned-" + suffix,
        },
        json={
            "organization_name": "合成数据供应商-已补正-" + suffix,
            "unified_social_credit_code": credit_code,
            "contact_name": "脱敏补正联系人",
            "contact_phone": "136****0000",
        },
        timeout=15,
    )
    require(update_returned, 200, "update returned supplier application")
    second_material = upload_material(
        platform,
        application_id,
        applicant_token,
        update_returned.headers["ETag"],
        "e2e-material-v2-" + suffix,
        SYNTHETIC_PNG + b"-corrected",
    )
    resubmit = requests.post(
        f"{platform}/api/v1/supplier-applications/{application_id}:submit",
        headers={
            **bearer(applicant_token),
            "If-Match": second_material.headers["ETag"],
            "Idempotency-Key": "e2e-resubmit-" + suffix,
        },
        timeout=15,
    )
    require(resubmit, 200, "resubmit supplier application")
    approve = requests.post(
        f"{platform}/api/v1/supplier-applications/{application_id}:approve",
        headers={
            **bearer(operator_token),
            "If-Match": resubmit.headers["ETag"],
            "Idempotency-Key": "e2e-approve-" + suffix,
        },
        json={"comment": "合成数据端到端核验通过"},
        timeout=15,
    )
    require(approve, 200, "approve supplier application")
    query = require(
        requests.get(
            f"{platform}/api/v1/supplier-applications/{application_id}",
            headers=bearer(applicant_token),
            timeout=10,
        ),
        200,
        "query supplier application",
    )
    status = query.json()["data"]["status"]
    if status != "APPROVED":
        raise RuntimeError("supplier application did not reach APPROVED")
    materials = require(
        requests.get(
            f"{platform}/api/v1/supplier-applications/{application_id}/materials",
            headers=bearer(applicant_token),
            timeout=10,
        ),
        200,
        "query qualification material versions",
    ).json()["data"]
    if [item["version_no"] for item in materials] != [2, 1]:
        raise RuntimeError("qualification material versions were not append-only")
    history = require(
        requests.get(
            f"{platform}/api/v1/supplier-applications/{application_id}/history",
            headers=bearer(applicant_token),
            timeout=10,
        ),
        200,
        "query supplier application history",
    ).json()["data"]
    if len(history) != 11 or history[0]["to_status"] != "DRAFT" or history[-1]["to_status"] != "APPROVED":
        raise RuntimeError("supplier application history was incomplete or out of order")
    if not any(item.get("reason") == "请修改合成联系人信息" for item in history):
        raise RuntimeError("supplier application review history was missing")

    withdrawn_create = requests.post(
        platform + "/api/v1/supplier-applications",
        headers={**bearer(applicant_token), "Idempotency-Key": "e2e-withdraw-create-" + suffix},
        json={
            "organization_name": "合成撤回供应商-" + suffix,
            "unified_social_credit_code": ("92" + suffix.upper() + "0000000000000000")[:18],
            "contact_name": "脱敏撤回联系人",
            "contact_phone": "135****0000",
        },
        timeout=15,
    )
    require(withdrawn_create, 201, "create withdraw application")
    withdrawn_id = withdrawn_create.json()["data"]["id"]
    withdrawn = requests.post(
        f"{platform}/api/v1/supplier-applications/{withdrawn_id}:withdraw",
        headers={
            **bearer(applicant_token),
            "If-Match": withdrawn_create.headers["ETag"],
            "Idempotency-Key": "e2e-withdraw-" + suffix,
        },
        timeout=15,
    )
    require(withdrawn, 200, "withdraw supplier application")
    if withdrawn.json()["data"]["status"] != "WITHDRAWN":
        raise RuntimeError("supplier application did not reach WITHDRAWN")

    rejected_create = requests.post(
        platform + "/api/v1/supplier-applications",
        headers={**bearer(applicant_token), "Idempotency-Key": "e2e-reject-create-" + suffix},
        json={
            "organization_name": "合成拒绝供应商-" + suffix,
            "unified_social_credit_code": ("93" + suffix.upper() + "0000000000000000")[:18],
            "contact_name": "脱敏拒绝联系人",
            "contact_phone": "134****0000",
        },
        timeout=15,
    )
    require(rejected_create, 201, "create reject application")
    rejected_id = rejected_create.json()["data"]["id"]
    rejected_material = upload_material(
        platform,
        rejected_id,
        applicant_token,
        rejected_create.headers["ETag"],
        "e2e-reject-material-" + suffix,
        SYNTHETIC_PNG + b"-rejected-branch",
    )
    rejected_submit = requests.post(
        f"{platform}/api/v1/supplier-applications/{rejected_id}:submit",
        headers={
            **bearer(applicant_token),
            "If-Match": rejected_material.headers["ETag"],
            "Idempotency-Key": "e2e-reject-submit-" + suffix,
        },
        timeout=15,
    )
    require(rejected_submit, 200, "submit reject application")
    rejected = requests.post(
        f"{platform}/api/v1/supplier-applications/{rejected_id}:reject",
        headers={
            **bearer(operator_token),
            "If-Match": rejected_submit.headers["ETag"],
            "Idempotency-Key": "e2e-reject-" + suffix,
        },
        json={"comment": "合成拒绝路径验证"},
        timeout=15,
    )
    require(rejected, 200, "reject supplier application")
    if rejected.json()["data"]["status"] != "REJECTED":
        raise RuntimeError("supplier application did not reach REJECTED")

    role_synced = False
    for _ in range(8):
        time.sleep(5)
        refreshed_applicant_token = login(env, applicant_username, applicant_password)
        current_user = requests.get(
            platform + "/api/v1/me",
            headers=bearer(refreshed_applicant_token),
            timeout=10,
        )
        require(current_user, 200, "query refreshed applicant roles")
        if "SUPPLIER" in current_user.json()["data"]["roles"]:
            role_synced = True
            break
    if not role_synced:
        raise RuntimeError("approved applicant did not receive SUPPLIER role")
    evidence.update(
        {
            "result": "passed",
            "application_id": application_id,
            "application_status": status,
            "applicant_username": applicant_username,
            "operator_username": operator_username,
            "identity_user_creation": 201,
            "supplier_create": 201,
            "supplier_update_draft": 200,
            "supplier_material_create": 201,
            "supplier_material_replay": 200,
            "supplier_material_versions": [2, 1],
            "supplier_history_count": len(history),
            "supplier_submit": 200,
            "supplier_return": 200,
            "supplier_update_returned": 200,
            "supplier_resubmit": 200,
            "supplier_approve": 200,
            "supplier_query": 200,
            "supplier_role_synced": True,
            "withdrawn_application_id": withdrawn_id,
            "supplier_withdraw_status": "WITHDRAWN",
            "rejected_application_id": rejected_id,
            "supplier_reject_status": "REJECTED",
        }
    )
    if args.evidence:
        args.evidence.parent.mkdir(parents=True, exist_ok=True)
        args.evidence.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        args.evidence.chmod(0o600)
    print("PLATFORM_SUPPLIER_E2E=passed")
    print("APPLICATION_ID=" + application_id)
    print("APPLICATION_STATUS=" + status)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exception:
        print("PLATFORM_SUPPLIER_E2E=failed", file=sys.stderr)
        print("REASON=" + str(exception), file=sys.stderr)
        raise SystemExit(1)

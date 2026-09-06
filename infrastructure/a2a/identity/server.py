#!/usr/bin/env python3
"""Minimal local OIDC client-credentials issuer and HTTPS notification relay."""

import base64
import hashlib
import hmac
import http.client
import json
import os
import re
import ssl
import subprocess
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ISSUER = os.environ.get("A2A_IDENTITY_ISSUER", "https://a2a-identity:8443/issuer").rstrip("/")
AUDIENCE = os.environ.get("A2A_IDENTITY_AUDIENCE", "ai-factory-a2a")
CLIENT_ID = "ai-factory-orchestrator"
SUBJECT = "spiffe://ai-factory.local/control/orchestrator"
CERTIFICATE = "/var/run/a2a-identity/tls.crt"
PRIVATE_KEY = "/var/run/a2a-identity/tls.key"
TRUST_ANCHOR = "/var/run/a2a-identity/ca.crt"
CLIENT_SECRET_FILE = "/run/secrets/a2a-orchestrator-oauth2-client"
MAX_BODY = 1_048_576


def b64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def certificate_der() -> bytes:
    return subprocess.check_output(
        ["openssl", "x509", "-in", CERTIFICATE, "-outform", "DER"]
    )


def jwk() -> dict:
    modulus_line = subprocess.check_output(
        ["openssl", "x509", "-in", CERTIFICATE, "-noout", "-modulus"], text=True
    ).strip()
    modulus = bytes.fromhex(modulus_line.split("=", 1)[1])
    exponent = (65537).to_bytes(3, "big")
    return {
        "kty": "RSA", "use": "sig", "alg": "RS256", "kid": "a2a-identity-local-v1",
        "n": b64url(modulus), "e": b64url(exponent),
        "x5c": [base64.b64encode(certificate_der()).decode("ascii")],
    }


PUBLIC_JWK = jwk()


def sign_token(scopes: str) -> str:
    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT", "kid": PUBLIC_JWK["kid"]}
    payload = {
        "iss": ISSUER, "sub": SUBJECT, "aud": [AUDIENCE], "iat": now, "nbf": now - 1,
        "exp": now + 300, "scope": scopes, "client_id": CLIENT_ID,
        "tenant_id": "local-compose", "role": "workflow",
    }
    signing_input = (
        b64url(json.dumps(header, separators=(",", ":"), sort_keys=True).encode()) + "." +
        b64url(json.dumps(payload, separators=(",", ":"), sort_keys=True).encode())
    )
    signature = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", PRIVATE_KEY],
        input=signing_input.encode("ascii"), stdout=subprocess.PIPE, check=True,
    ).stdout
    return signing_input + "." + b64url(signature)


class Handler(BaseHTTPRequestHandler):
    server_version = "A2AIdentity/1.0"

    def log_message(self, format_string, *args):
        print("identity", self.command, self.path, args[1] if len(args) > 1 else "-", flush=True)

    def json_response(self, status: int, payload: dict):
        body = json.dumps(payload, separators=(",", ":"), sort_keys=True).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/health":
            return self.json_response(200, {"status": "UP"})
        if self.path == "/issuer/.well-known/openid-configuration":
            return self.json_response(200, {
                "issuer": ISSUER,
                "jwks_uri": ISSUER + "/jwks",
                "token_endpoint": ISSUER.removesuffix("/issuer") + "/oauth2/token",
                "grant_types_supported": ["client_credentials"],
                "token_endpoint_auth_methods_supported": ["client_secret_post"],
            })
        if self.path == "/issuer/jwks":
            return self.json_response(200, {"keys": [PUBLIC_JWK]})
        return self.json_response(404, {"error": "not_found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_BODY:
            return self.json_response(413, {"error": "invalid_request"})
        body = self.rfile.read(length)
        if self.path == "/oauth2/token":
            return self.issue_token(body)
        if self.path == "/internal/a2a/notifications":
            return self.relay_notification(body)
        return self.json_response(404, {"error": "not_found"})

    def issue_token(self, body: bytes):
        if not self.connection.getpeercert():
            return self.json_response(401, {"error": "invalid_client"})
        form = urllib.parse.parse_qs(body.decode("utf-8"), strict_parsing=True)
        value = lambda name: form.get(name, [""])[0]
        with open(CLIENT_SECRET_FILE, "r", encoding="ascii") as source:
            expected_secret = source.read().strip()
        scopes = value("scope").split()
        valid_scopes = scopes and len(scopes) <= 16 and all(
            re.fullmatch(r"a2a\.[a-z0-9.-]+", scope) for scope in scopes
        )
        valid = (
            value("grant_type") == "client_credentials"
            and value("client_id") == CLIENT_ID
            and hmac.compare_digest(value("client_secret"), expected_secret)
            and value("audience") == AUDIENCE
            and valid_scopes
        )
        if not valid:
            return self.json_response(401, {"error": "invalid_client"})
        return self.json_response(200, {
            "access_token": sign_token(" ".join(sorted(set(scopes)))),
            "token_type": "Bearer", "expires_in": 300, "scope": " ".join(sorted(set(scopes))),
        })

    def relay_notification(self, body: bytes):
        signature = self.headers.get("X-A2A-Notification-Signature", "")
        connection = http.client.HTTPConnection("orchestrator", 8080, timeout=5)
        try:
            connection.request("POST", "/internal/a2a/notifications", body=body, headers={
                "Content-Type": "application/json",
                "X-A2A-Notification-Signature": signature,
            })
            response = connection.getresponse()
            response.read()
            self.send_response(response.status)
            self.send_header("Content-Length", "0")
            self.end_headers()
        except OSError:
            self.json_response(502, {"error": "notification_target_unavailable"})
        finally:
            connection.close()


def main():
    server = ThreadingHTTPServer(("0.0.0.0", 8443), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_3
    context.load_cert_chain(CERTIFICATE, PRIVATE_KEY)
    context.load_verify_locations(TRUST_ANCHOR)
    context.verify_mode = ssl.CERT_OPTIONAL
    server.socket = context.wrap_socket(server.socket, server_side=True)
    server.serve_forever()


if __name__ == "__main__":
    main()

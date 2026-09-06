#!/usr/bin/env python3
"""Exercise the local issuer and one protected A2A resource without leaking credentials."""

import json
import ssl
import urllib.parse
import urllib.request

CA = "/var/run/a2a-identity/ca.crt"
CERTIFICATE = "/var/run/a2a-identity/tls.crt"
PRIVATE_KEY = "/var/run/a2a-identity/tls.key"
CLIENT_SECRET = "/run/secrets/a2a-orchestrator-oauth2-client"


def main():
    with open(CLIENT_SECRET, "r", encoding="ascii") as source:
        secret = source.read().strip()
    context = ssl.create_default_context(cafile=CA)
    context.load_cert_chain(CERTIFICATE, PRIVATE_KEY)
    form = urllib.parse.urlencode({
        "grant_type": "client_credentials",
        "client_id": "ai-factory-orchestrator",
        "client_secret": secret,
        "audience": "ai-factory-a2a",
        "scope": "a2a.read a2a.role.supervisor",
    }).encode()
    token_request = urllib.request.Request(
        "https://a2a-identity:8443/oauth2/token", data=form)
    token_response = json.load(urllib.request.urlopen(
        token_request, context=context, timeout=5))
    if token_response.get("token_type") != "Bearer" or token_response.get("expires_in") != 300:
        raise RuntimeError("local A2A issuer returned an invalid token response")

    body = json.dumps({
        "jsonrpc": "2.0",
        "id": "identity-smoke",
        "method": "tasks/get",
        "params": {"id": "identity-smoke-not-present"},
    }).encode()
    resource_request = urllib.request.Request(
        "https://a2a-supervisor:8090/a2a",
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + token_response["access_token"],
            "A2A-Version": "1.0",
        },
    )
    response = json.load(urllib.request.urlopen(
        resource_request, context=context, timeout=5))
    if response.get("id") != "identity-smoke" or response.get("error", {}).get("message") != "Task not found":
        raise RuntimeError("protected A2A resource did not accept the local identity")
    print("A2A identity smoke passed: OAuth2 client credentials, JWT validation and mTLS are active.")


if __name__ == "__main__":
    main()

"""Transport-only adapter for running the upstream TCK through the local mTLS boundary."""

from __future__ import annotations

import os
import httpx


_original_init = httpx.Client.__init__
_original_post = httpx.Client.post
_original_build_request = httpx.Client.build_request


def _client_init(self, *args, **kwargs):
    headers = dict(kwargs.get("headers") or {})
    token = os.environ.get("A2A_TCK_AUTH_TOKEN")
    if token:
        headers.setdefault("Authorization", f"Bearer {token}")
    kwargs["headers"] = headers
    return _original_init(self, *args, **kwargs)


def _endpoint_url(client: httpx.Client, url):
    if str(url) == "/" and client.base_url.path not in ("", "/"):
        return str(client.base_url).rstrip("/")
    return url


def _client_post(self, url, *args, **kwargs):
    return _original_post(self, _endpoint_url(self, url), *args, **kwargs)


def _client_build_request(self, method, url, *args, **kwargs):
    return _original_build_request(self, method, _endpoint_url(self, url), *args, **kwargs)


httpx.Client.__init__ = _client_init
httpx.Client.post = _client_post
httpx.Client.build_request = _client_build_request

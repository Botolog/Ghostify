from __future__ import annotations

import requests as _requests

from . import exceptions

__all__ = ["Session", "Response", "exceptions", "RequestException"]

RequestException = exceptions.RequestException

# spotapi/spotdl make requests with no per-call timeout; on a flaky mobile
# network a stuck connection (the open.spotify.com session bootstrap runs
# several times per fetch) can block for minutes and blow the fetch deadline.
# Inject a sane default so no single HTTP request can hang indefinitely.
DEFAULT_TIMEOUT: float = 25.0


class Response(_requests.Response):
    pass


class Session(_requests.Session):
    """requests.Session stand-in that accepts curl_cffi's impersonate profile.

    TLS impersonation is not available on Android, so ``impersonate`` is
    stored on the instance (some callers read it) but otherwise ignored.
    Extra curl_cffi-only kwargs are accepted and ignored.
    """

    def __init__(self, impersonate: str | None = None, **kwargs) -> None:
        super().__init__()
        self.impersonate = impersonate

    def request(self, method, url, **kwargs):
        """Forward to requests.Session.request with a default timeout."""
        if "timeout" not in kwargs or kwargs.get("timeout") is None:
            kwargs["timeout"] = DEFAULT_TIMEOUT
        return super().request(method, url, **kwargs)

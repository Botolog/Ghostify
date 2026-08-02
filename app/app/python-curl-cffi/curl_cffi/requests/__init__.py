from __future__ import annotations

import requests as _requests

from . import exceptions

__all__ = ["Session", "Response", "exceptions", "RequestException"]

RequestException = exceptions.RequestException


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

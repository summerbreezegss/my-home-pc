import hmac
import logging
from typing import Optional

from fastapi import Header, HTTPException, Request, status

from . import ratelimit
from .config import get_settings

logger = logging.getLogger("relay.auth")


def require_token(request: Request, authorization: Optional[str] = Header(default=None)) -> None:
    client_ip = request.client.host if request.client else "unknown"

    if ratelimit.is_locked_out(client_ip):
        # Locked-out sources are refused before the token is even looked at, so the
        # lockout can't be used to test guesses. A legitimate caller just waits it out.
        logger.warning("auth locked out: ip=%s path=%s", client_ip, request.url.path)
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="too_many_auth_failures",
            headers={"Retry-After": str(int(ratelimit.LOCKOUT_SECONDS))},
        )

    if not authorization or not authorization.startswith("Bearer "):
        _reject(client_ip, request, "missing_token")
    token = authorization.removeprefix("Bearer ").strip()
    if not hmac.compare_digest(token, get_settings().relay_api_token):
        _reject(client_ip, request, "invalid_token")

    ratelimit.clear(client_ip)


def _reject(client_ip: str, request: Request, detail: str) -> None:
    ratelimit.record_failure(client_ip)
    # The audit trail for token guessing: one line per rejection, with the source.
    logger.warning("auth rejected (%s): ip=%s path=%s", detail, client_ip, request.url.path)
    raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail=detail)

import hmac
from typing import Optional

from fastapi import Header, HTTPException, status

from .config import get_settings


def require_token(authorization: Optional[str] = Header(default=None)) -> None:
    settings = get_settings()
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="missing_token")
    token = authorization.removeprefix("Bearer ").strip()
    if not hmac.compare_digest(token, settings.relay_api_token):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid_token")

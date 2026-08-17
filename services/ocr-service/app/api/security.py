"""Authentication dependencies for internal OCR endpoints."""

import secrets
from typing import Optional

from fastapi import Header, HTTPException

from app.config import settings


def require_ocr_api_key(
    api_key: Optional[str] = Header(default=None, alias="X-OCR-API-Key"),
) -> None:
    """Require the internal API key when OCR authentication is enabled."""
    if not settings.enable_auth:
        return

    if not settings.api_key:
        raise HTTPException(status_code=503, detail="OCR authentication is not configured")

    if not api_key or not secrets.compare_digest(api_key, settings.api_key):
        raise HTTPException(status_code=401, detail="Authentication required")

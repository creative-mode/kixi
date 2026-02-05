"""
OCR Service API Module

FastAPI routes and handlers for the OCR service.
"""

from .routes import router
from .pdf_handler import (
    extract_images_from_pdf,
    is_pdf,
    get_pdf_info,
    validate_pdf,
)

__all__ = [
    "router",
    "extract_images_from_pdf",
    "is_pdf",
    "get_pdf_info",
    "validate_pdf",
]

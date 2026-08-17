"""Tests for PDF validation and page rendering utilities."""

import io
import sys
from unittest.mock import patch

import fitz
import numpy as np
import pytest
from PIL import Image

from app.api.pdf_handler import (
    extract_images_from_pdf,
    get_pdf_info,
    is_pdf,
    validate_pdf,
)
from app.api import pdf_handler


def make_pdf(page_count: int = 1) -> bytes:
    document = fitz.open()
    document.set_metadata({
        "title": "Kixi fixture",
        "author": "Kixi tests",
        "subject": "OCR",
        "creator": "PyMuPDF",
    })
    for page_number in range(page_count):
        page = document.new_page()
        page.insert_text((72, 72), f"Página {page_number + 1}")

    buffer = io.BytesIO(document.tobytes())
    document.close()
    return buffer.getvalue()


def test_is_pdf_checks_magic_bytes():
    assert is_pdf(b"%PDF-1.7") is True
    assert is_pdf(b"PNG\x89") is False


def test_get_pdf_info_reads_metadata_and_page_count():
    info = get_pdf_info(make_pdf(page_count=2))

    assert info["page_count"] == 2
    assert info["title"] == "Kixi fixture"
    assert info["author"] == "Kixi tests"
    assert info["subject"] == "OCR"
    assert info["creator"] == "PyMuPDF"
    assert info["encrypted"] is False


def test_extract_images_supports_page_ranges():
    images = extract_images_from_pdf(make_pdf(page_count=2), dpi=72, first_page=2, last_page=2)

    assert len(images) == 1
    assert isinstance(images[0], np.ndarray)
    assert images[0].ndim == 3
    assert images[0].shape[2] == 3


def test_validate_pdf_accepts_valid_document():
    validate_pdf(make_pdf(page_count=2), max_pages=2)


def test_validate_pdf_rejects_non_pdf():
    with pytest.raises(ValueError, match="not a valid PDF"):
        validate_pdf(b"not a pdf")


def test_validate_pdf_rejects_too_many_pages():
    with pytest.raises(ValueError, match="maximum allowed"):
        validate_pdf(make_pdf(page_count=2), max_pages=1)


def test_get_pdf_info_rejects_invalid_content():
    with pytest.raises(ValueError, match="Failed to read PDF"):
        get_pdf_info(b"%PDF-invalid")


def test_extract_images_falls_back_to_pdf2image():
    fallback_image = np.zeros((4, 4, 3), dtype=np.uint8)

    with patch.object(pdf_handler, "_extract_with_pymupdf", side_effect=ImportError), \
         patch.object(pdf_handler, "_extract_with_pdf2image", return_value=[fallback_image]) as fallback:
        images = extract_images_from_pdf(b"%PDF-fallback", dpi=72)

    assert images == [fallback_image]
    fallback.assert_called_once()


def test_extract_images_reports_missing_pdf_backend():
    with patch.object(pdf_handler, "_extract_with_pymupdf", side_effect=ImportError), \
         patch.object(pdf_handler, "_extract_with_pdf2image", side_effect=ImportError):
        with pytest.raises(ValueError, match="No PDF processing library available"):
            extract_images_from_pdf(b"%PDF-fallback")


def test_pdf2image_fallback_converts_rgb_to_bgr():
    pil_image = Image.new("RGB", (2, 2), color=(10, 20, 30))

    with patch("pdf2image.convert_from_bytes", return_value=[pil_image]) as convert:
        images = pdf_handler._extract_with_pdf2image(b"%PDF", dpi=100, first_page=2, last_page=3)

    assert images[0][0, 0].tolist() == [30, 20, 10]
    convert.assert_called_once_with(
        b"%PDF",
        dpi=100,
        first_page=2,
        last_page=3,
        fmt="RGB",
    )


def test_pdf_info_fallback_handles_valid_and_invalid_magic_bytes():
    with patch.dict(sys.modules, {"fitz": None}):
        assert get_pdf_info(b"%PDF-valid") ["page_count"] == -1
        with pytest.raises(ValueError, match="Invalid PDF file"):
            get_pdf_info(b"not-pdf")


def test_validate_pdf_rejects_encrypted_and_unexpected_errors():
    with patch("app.api.pdf_handler.get_pdf_info", return_value={"encrypted": True, "page_count": 1}):
        with pytest.raises(ValueError, match="Encrypted PDFs"):
            validate_pdf(b"%PDF-valid")

    with patch("app.api.pdf_handler.get_pdf_info", side_effect=RuntimeError("parser failed")):
        with pytest.raises(ValueError, match="Failed to validate PDF"):
            validate_pdf(b"%PDF-valid")

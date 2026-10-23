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
    extract_text_pages_from_pdf,
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


def test_extract_text_pages_keeps_selectable_text_and_page_coordinates():
    document = fitz.open()
    page = document.new_page(width=300, height=400)
    page.insert_text((50, 70), "1) Determine the velocity")
    content = document.tobytes()
    document.close()

    pages = extract_text_pages_from_pdf(content)

    assert len(pages) == 1
    assert pages[0]["width"] == 300
    assert pages[0]["height"] == 400
    assert pages[0]["lines"][0]["text"] == "1) Determine the velocity"
    assert pages[0]["lines"][0]["bbox"][0] == 50


def test_validate_pdf_accepts_valid_document():
    validate_pdf(make_pdf(page_count=2), max_pages=2)


def test_validate_pdf_rejects_non_pdf():
    with pytest.raises(ValueError, match="not a valid PDF"):
        validate_pdf(b"not a pdf")


def test_validate_pdf_rejects_too_many_pages():
    with pytest.raises(ValueError, match="maximum allowed"):
        validate_pdf(make_pdf(page_count=2), max_pages=1)


def test_validate_pdf_rejects_page_that_would_render_over_pixel_limit():
    document = fitz.open()
    document.new_page(width=50_000, height=50_000)
    content = document.tobytes()
    document.close()

    with pytest.raises(ValueError, match="maximum rendered pixel count"):
        validate_pdf(content)


def test_validate_pdf_rejects_total_request_pixel_budget(monkeypatch):
    monkeypatch.setattr("app.api.pdf_handler.settings.max_ocr_pixels_per_request", 1_000)

    with pytest.raises(ValueError, match="maximum total rendered pixel count"):
        validate_pdf(make_pdf(page_count=1))


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


# --- #407: a page is never rendered at more than max_pdf_page_pixels -----------------------------------------------

def test_an_a4_page_at_300_dpi_is_rendered_at_a_lower_dpi_so_it_stays_under_the_page_pixel_limit():
    a4 = (595.0, 842.0)  # points

    dpi = pdf_handler.effective_render_dpi(*a4, 300)

    assert 150 <= dpi < 300
    assert pdf_handler._rendered_page_pixels(*a4, dpi) <= pdf_handler.settings.max_pdf_page_pixels


def test_a_page_that_already_fits_keeps_the_requested_dpi():
    assert pdf_handler.effective_render_dpi(595.0, 842.0, 150) == 150
    assert pdf_handler.effective_render_dpi(100.0, 100.0, 300) == 300


def test_the_dpi_is_never_lowered_below_the_pdf_own_resolution(monkeypatch):
    monkeypatch.setattr(pdf_handler.settings, "max_pdf_page_pixels", 10)

    assert pdf_handler.effective_render_dpi(595.0, 842.0, 300) == 72


def test_extract_images_renders_a_large_page_within_the_limit_and_keeps_its_aspect_ratio():
    image = extract_images_from_pdf(make_pdf(page_count=1), dpi=300)[0]

    height, width = image.shape[:2]
    assert height * width <= pdf_handler.settings.max_pdf_page_pixels
    assert abs((height / width) - (842.0 / 595.0)) < 0.01


def test_every_page_of_a_multi_page_pdf_respects_the_limit_so_pages_do_not_add_up_to_gigabytes():
    images = extract_images_from_pdf(make_pdf(page_count=3), dpi=300)

    assert len(images) == 3
    assert all(i.shape[0] * i.shape[1] <= pdf_handler.settings.max_pdf_page_pixels for i in images)
    assert sum(i.nbytes for i in images) < 3 * pdf_handler.settings.max_pdf_page_pixels * 3 + 1

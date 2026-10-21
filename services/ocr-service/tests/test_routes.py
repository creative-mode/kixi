"""
OCR Service API Routes Test Suite

Tests for the FastAPI endpoints of the OCR service.
"""

import pytest
from dataclasses import replace
from fastapi import HTTPException
from fastapi.testclient import TestClient
from unittest.mock import patch, MagicMock, AsyncMock
import io
import fitz
from PIL import Image

from app.main import app
from app.config import settings
from app.api.routes import get_ocr_engine as route_get_ocr_engine, resolve_file_type
from app.ocr.engine import OCRResult, DocumentInfo
from app.ocr.postprocessing import ExtractedMetadata, ExtractedQuestion, QuestionType, UnmappedContent


# =============================================================================
# Fixtures
# =============================================================================

@pytest.fixture
def client():
    """Create a test client for the FastAPI app."""
    return TestClient(app)


@pytest.fixture
def engine_override():
    """Override the FastAPI dependency used by OCR routes."""
    def override(engine):
        app.dependency_overrides[route_get_ocr_engine] = lambda: engine

    yield override
    app.dependency_overrides.pop(route_get_ocr_engine, None)


@pytest.fixture
def sample_image_bytes():
    """Create a sample PNG image as bytes."""
    img = Image.new("RGB", (100, 100), color="white")
    buffer = io.BytesIO()
    img.save(buffer, format="PNG")
    buffer.seek(0)
    return buffer.getvalue()


@pytest.fixture
def sample_jpeg_bytes():
    """Create a sample JPEG image as bytes."""
    img = Image.new("RGB", (100, 100), color="white")
    buffer = io.BytesIO()
    img.save(buffer, format="JPEG")
    buffer.seek(0)
    return buffer.getvalue()


def test_extraction_requires_internal_api_key_when_authentication_is_enabled(
    client, engine_override, sample_image_bytes, monkeypatch
):
    engine_override(MagicMock())
    monkeypatch.setattr(settings, "enable_auth", True)
    monkeypatch.setattr(settings, "api_key", "internal-ocr-key")

    response = client.post(
        "/ocr/v1/extract/simple",
        files={"image": ("exam.png", sample_image_bytes, "image/png")},
    )

    assert response.status_code == 401


def test_extraction_rejects_missing_server_api_key_configuration(
    client, engine_override, sample_image_bytes, monkeypatch
):
    engine_override(MagicMock())
    monkeypatch.setattr(settings, "enable_auth", True)
    monkeypatch.setattr(settings, "api_key", None)

    response = client.post(
        "/ocr/v1/extract/simple",
        files={"image": ("exam.png", sample_image_bytes, "image/png")},
    )

    assert response.status_code == 503


@pytest.fixture
def mock_ocr_result():
    """Create a mock OCR result."""
    return OCRResult(
        status="success",
        request_id="req-test-123",
        processing_time_ms=1500,
        overall_confidence=0.85,
        document=DocumentInfo(
            page_count=1,
            main_language="pt",
            has_tables=False,
        ),
        metadata=ExtractedMetadata(),
        questions=[
            ExtractedQuestion(
                number=1,
                text="What is 2+2?",
                text_confidence=0.95,
                question_type=QuestionType.DISSERTATIVA,
                question_type_confidence=0.9,
            )
        ],
        images_to_upload=[],
        unmapped_content=[],
        warnings=[],
    )


# =============================================================================
# Root and Health Endpoint Tests
# =============================================================================

class TestRootEndpoints:
    """Tests for root and health endpoints."""

    def test_root_endpoint(self, client):
        """Test the root endpoint returns service info."""
        response = client.get("/")

        assert response.status_code == 200
        data = response.json()
        assert "service" in data
        assert "version" in data
        assert data["status"] == "running"

    def test_health_endpoint(self, client):
        """Test the health endpoint."""
        response = client.get("/health")

        assert response.status_code == 200
        data = response.json()
        assert data["status"] == "healthy"
        assert "service" in data
        assert "version" in data

    def test_ocr_health_endpoint(self, client, engine_override):
        """Test the OCR-specific health endpoint."""
        mock_instance = MagicMock()
        mock_instance.health_check.return_value = {
            "initialized": True,
            "status": "healthy",
            "message": "OCR engine is operational",
        }
        engine_override(mock_instance)

        response = client.get("/ocr/health")

        assert response.status_code == 200
        data = response.json()
        assert data["initialized"] is True


# =============================================================================
# OCR Extract Endpoint Tests
# =============================================================================

class TestExtractEndpoint:
    """Tests for the OCR extract endpoints."""

    def test_extract_single_image(self, client, engine_override, sample_image_bytes, mock_ocr_result):
        """Test extracting text from a single image."""
        # Setup mock
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=mock_ocr_result)
        engine_override(mock_engine)

        # Make request
        files = {"images": ("test.png", sample_image_bytes, "image/png")}
        response = client.post("/ocr/v1/extract", files=[("images", ("test.png", sample_image_bytes, "image/png"))])

        assert response.status_code in [200, 207]
        data = response.json()
        assert "status" in data
        assert "requestId" in data

    def test_extract_jpeg_image(self, client, engine_override, sample_jpeg_bytes, mock_ocr_result):
        """Test extracting text from a JPEG image."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=mock_ocr_result)
        engine_override(mock_engine)

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("test.jpg", sample_jpeg_bytes, "image/jpeg"))]
        )

        assert response.status_code in [200, 207]

    def test_extract_engine_error_returns_server_error(self, client, engine_override, sample_image_bytes, mock_ocr_result):
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(
            return_value=replace(mock_ocr_result, status="error", error_message="engine failed")
        )
        engine_override(mock_engine)

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("test.png", sample_image_bytes, "image/png"))],
        )

        assert response.status_code == 500
        assert response.json()["status"] == "error"

    def test_extract_text_without_questions_is_partial_not_server_error(
        self, client, engine_override, sample_image_bytes, mock_ocr_result
    ):
        """The OCR read text but parsed no questions: 207 with the text, never a 500 (#292)."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(
            return_value=replace(
                mock_ocr_result,
                status="partial",
                questions=[],
                unmapped_content=[
                    UnmappedContent(0, "Tabela de valores", 0.9, "Texto lido sem perguntas reconhecidas")
                ],
            )
        )
        engine_override(mock_engine)

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("test.png", sample_image_bytes, "image/png"))],
        )

        assert response.status_code == 207
        data = response.json()
        assert data["status"] == "partial"
        assert data["questions"] == []
        assert [item["text"] for item in data["unmappedContent"]] == ["Tabela de valores"]

    def test_extract_no_files(self, client):
        """Test that extraction fails without files."""
        response = client.post("/ocr/v1/extract", files=[])

        # Should fail with 422 (validation error) or 400
        assert response.status_code in [400, 422]

    def test_extract_invalid_file_type(self, client):
        """Test that extraction rejects invalid file types."""
        invalid_content = b"This is not an image"

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("test.txt", invalid_content, "text/plain"))]
        )

        assert response.status_code == 400
        data = response.json()
        assert "Unsupported file type" in data.get("detail", str(data))

    def test_extract_rejects_oversized_file_before_reading_it_into_memory(
        self, client, engine_override, sample_image_bytes, monkeypatch
    ):
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock()
        engine_override(mock_engine)
        monkeypatch.setattr(settings, "max_image_size_mb", 0.000001)

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("large.png", sample_image_bytes, "image/png"))],
        )

        assert response.status_code == 400
        assert "File size exceeds" in response.json()["detail"]
        mock_engine.process_image_async.assert_not_awaited()

    def test_extract_rejects_too_many_files_before_ocr(
        self, client, engine_override, sample_image_bytes, monkeypatch
    ):
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock()
        engine_override(mock_engine)
        monkeypatch.setattr(settings, "max_images_per_request", 1)

        response = client.post(
            "/ocr/v1/extract",
            files=[
                ("images", ("one.png", sample_image_bytes, "image/png")),
                ("images", ("two.png", sample_image_bytes, "image/png")),
            ],
        )

        assert response.status_code == 400
        assert response.json()["detail"] == "Maximum 1 images allowed per request"
        mock_engine.process_image_async.assert_not_awaited()

    def test_extract_rejects_pdf_over_page_limit_before_ocr(
        self, client, engine_override, monkeypatch
    ):
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock()
        engine_override(mock_engine)
        monkeypatch.setattr(settings, "max_pdf_pages", 1)
        pdf = fitz.open()
        pdf.new_page()
        pdf.new_page()
        pdf_bytes = pdf.tobytes()
        pdf.close()

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("exam.pdf", pdf_bytes, "application/pdf"))],
        )

        assert response.status_code == 400
        assert response.json()["detail"] == "Invalid uploaded file"
        mock_engine.process_image_async.assert_not_awaited()

    def test_extract_rejects_request_over_aggregate_upload_limit(
        self, client, engine_override, sample_image_bytes, monkeypatch
    ):
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock()
        engine_override(mock_engine)
        total_limit_bytes = len(sample_image_bytes) + 1
        monkeypatch.setattr(
            settings, "max_upload_size_mb", total_limit_bytes / (1024 * 1024)
        )

        response = client.post(
            "/ocr/v1/extract",
            files=[
                ("images", ("one.png", sample_image_bytes, "image/png")),
                ("images", ("two.png", sample_image_bytes, "image/png")),
            ],
        )

        assert response.status_code == 400
        assert "Total upload size exceeds" in response.json()["detail"]
        mock_engine.process_image_async.assert_not_awaited()


class TestSimpleExtractEndpoint:
    """Tests for the simplified single-image extract endpoint."""

    def test_simple_extract(self, client, engine_override, sample_image_bytes, mock_ocr_result):
        """Test simple extraction with a single image."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=mock_ocr_result)
        engine_override(mock_engine)

        response = client.post(
            "/ocr/v1/extract/simple",
            files={"image": ("test.png", sample_image_bytes, "image/png")}
        )

        assert response.status_code in [200, 207]
        data = response.json()
        assert "status" in data

    def test_simple_extract_pdf_rejected(self, client):
        """Test that PDF files are rejected in simple mode."""
        pdf_content = b"%PDF-1.4 fake pdf content"

        response = client.post(
            "/ocr/v1/extract/simple",
            files={"image": ("test.pdf", pdf_content, "application/pdf")}
        )

        assert response.status_code == 400


# =============================================================================
# Supported Languages Endpoint Tests
# =============================================================================

class TestSupportedLanguagesEndpoint:
    """Tests for the supported languages endpoint."""

    def test_get_supported_languages(self, client):
        """Test retrieving supported OCR languages."""
        response = client.get("/ocr/v1/supported-languages")

        assert response.status_code == 200
        data = response.json()
        assert "languages" in data
        assert "default" in data
        assert isinstance(data["languages"], list)
        assert len(data["languages"]) > 0

        # Check that Portuguese is included
        pt_lang = next((l for l in data["languages"] if l["code"] == "pt"), None)
        assert pt_lang is not None
        assert pt_lang["primary"] is True


# =============================================================================
# Status Endpoint Tests
# =============================================================================

class TestStatusEndpoint:
    """Tests for the request status endpoint."""

    def test_get_status_request_scoped_progress_is_not_trackable(self, client):
        """A completed request has no durable job state to query."""
        response = client.get("/ocr/v1/status/req-test-123")

        assert response.status_code == 410
        data = response.json()
        assert "requestId" in data
        assert data["status"] == "not_trackable"
        assert "no job state is persisted" in data["message"].lower()


# =============================================================================
# File Validation Tests
# =============================================================================

class TestFileValidation:
    """Tests for file validation logic."""

    def test_valid_extensions(self, client, engine_override, sample_image_bytes):
        """Test that valid extensions are accepted."""
        valid_extensions = [
            ("test.jpg", "image/jpeg"),
            ("test.jpeg", "image/jpeg"),
            ("test.png", "image/png"),
            ("test.webp", "image/webp"),
            ("test.bmp", "image/bmp"),
        ]

        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=MagicMock(
            status="success",
            to_dict=lambda: {"status": "success", "requestId": "test"}
        ))
        engine_override(mock_engine)

        for filename, content_type in valid_extensions:
            response = client.post(
                "/ocr/v1/extract",
                files=[("images", (filename, sample_image_bytes, content_type))]
            )
            # Should not return 400 for valid file extensions.
            assert response.status_code != 400 or "Unsupported file type" not in response.text

    def test_invalid_extensions(self, client):
        """Test that invalid extensions are rejected."""
        invalid_files = [
            ("test.txt", b"text content", "text/plain"),
            ("test.doc", b"doc content", "application/msword"),
            ("test.exe", b"exe content", "application/octet-stream"),
            ("test.html", b"<html></html>", "text/html"),
        ]

        for filename, content, content_type in invalid_files:
            response = client.post(
                "/ocr/v1/extract",
                files=[("images", (filename, content, content_type))]
            )
            assert response.status_code == 400

    def test_a_photo_without_an_extension_is_accepted_by_its_content(
        self, client, engine_override, sample_jpeg_bytes
    ):
        """A phone photo called IMG_20250116 is still a JPEG; the name must not refuse it (#291)."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=MagicMock(
            status="success",
            to_dict=lambda: {"status": "success", "requestId": "test"}
        ))
        engine_override(mock_engine)

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("IMG_20250116", sample_jpeg_bytes, "application/octet-stream"))]
        )

        assert response.status_code == 200
        mock_engine.process_image_async.assert_awaited()

    def test_the_content_decides_when_the_name_lies(
        self, client, engine_override, sample_jpeg_bytes
    ):
        """A JPEG called prova.pdf is read as an image, not refused nor opened as a PDF."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=MagicMock(
            status="success",
            to_dict=lambda: {"status": "success", "requestId": "test"}
        ))
        engine_override(mock_engine)

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("prova.pdf", sample_jpeg_bytes, "application/pdf"))]
        )

        assert response.status_code == 200
        mock_engine.process_image_async.assert_awaited()

    def test_an_unnamed_file_that_is_not_an_image_is_still_refused(self, client):
        """The content is the only source: no name, no image, no entry."""
        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("IMG_20250116", b"not an image at all", "application/octet-stream"))]
        )

        assert response.status_code == 400
        assert "Unsupported file type" in response.json()["detail"]


class TestResolveFileType:
    """The content decides; the name is only a fallback (#291)."""

    def test_content_wins_over_the_name(self, sample_image_bytes, sample_jpeg_bytes):
        assert resolve_file_type("IMG_20250116", sample_jpeg_bytes) == "jpeg"
        assert resolve_file_type("prova.pdf", sample_jpeg_bytes) == "jpeg"
        assert resolve_file_type("prova.png", sample_jpeg_bytes) == "jpeg"
        assert resolve_file_type("sem_nome", sample_image_bytes) == "png"

    def test_the_name_is_used_when_the_content_cannot_be_named(self):
        assert resolve_file_type("scan.tiff", b"not a readable image") == "tiff"

    def test_neither_content_nor_name_is_refused(self):
        with pytest.raises(HTTPException) as raised:
            resolve_file_type("notes.txt", b"plain text")
        assert raised.value.status_code == 400


# =============================================================================
# Error Handling Tests
# =============================================================================

class TestErrorHandling:
    """Tests for error handling in API routes."""

    def test_ocr_processing_error(self, client, engine_override, sample_image_bytes):
        """Test handling of OCR processing errors."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(side_effect=Exception("OCR failed"))
        engine_override(mock_engine)

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("test.png", sample_image_bytes, "image/png"))]
        )

        assert response.status_code == 500

    def test_invalid_image_content(self, client, engine_override):
        """Test handling of invalid image content."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(
            side_effect=ValueError("Failed to decode image")
        )
        engine_override(mock_engine)

        # Send garbage data with valid extension
        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("test.png", b"not an image", "image/png"))]
        )

        # Should return error status
        assert response.status_code in [400, 500]


# =============================================================================
# Response Format Tests
# =============================================================================

class TestResponseFormat:
    """Tests for response format correctness."""

    def test_success_response_format(self, client, engine_override, sample_image_bytes, mock_ocr_result):
        """Test that success response has correct format."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=mock_ocr_result)
        engine_override(mock_engine)

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("test.png", sample_image_bytes, "image/png"))]
        )

        assert response.status_code in [200, 207]
        data = response.json()

        # Check required fields
        assert "status" in data
        assert "requestId" in data
        assert "processingTimeMs" in data
        assert "overallConfidence" in data
        assert "document" in data
        assert "metadata" in data
        assert "questions" in data
        assert "warnings" in data

    def test_document_info_format(self, client, engine_override, sample_image_bytes, mock_ocr_result):
        """Test that document info has correct format."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=mock_ocr_result)
        engine_override(mock_engine)

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("test.png", sample_image_bytes, "image/png"))]
        )

        data = response.json()
        document = data.get("document", {})

        assert "pageCount" in document
        assert "mainLanguage" in document
        assert "hasTables" in document


if __name__ == "__main__":
    pytest.main([__file__, "-v"])

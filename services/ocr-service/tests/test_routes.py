"""
OCR Service API Routes Test Suite

Tests for the FastAPI endpoints of the OCR service.
"""

import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch, MagicMock, AsyncMock
import io
from PIL import Image

from app.main import app
from app.ocr.engine import OCRResult, DocumentInfo
from app.ocr.postprocessing import ExtractedMetadata, ExtractedQuestion, QuestionType


# =============================================================================
# Fixtures
# =============================================================================

@pytest.fixture
def client():
    """Create a test client for the FastAPI app."""
    return TestClient(app)


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
                question_type=QuestionType.SHORT_ANSWER,
                question_type_confidence=0.9,
            )
        ],
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

    def test_ocr_health_endpoint(self, client):
        """Test the OCR-specific health endpoint."""
        with patch("app.api.routes.get_ocr_engine") as mock_engine:
            mock_instance = MagicMock()
            mock_instance.health_check.return_value = {
                "initialized": True,
                "status": "healthy",
                "message": "OCR engine is operational",
            }
            mock_engine.return_value = mock_instance

            response = client.get("/ocr/health")

            assert response.status_code == 200
            data = response.json()
            assert data["initialized"] is True


# =============================================================================
# OCR Extract Endpoint Tests
# =============================================================================

class TestExtractEndpoint:
    """Tests for the OCR extract endpoints."""

    @patch("app.api.routes.get_ocr_engine")
    def test_extract_single_image(self, mock_get_engine, client, sample_image_bytes, mock_ocr_result):
        """Test extracting text from a single image."""
        # Setup mock
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=mock_ocr_result)
        mock_get_engine.return_value = mock_engine

        # Make request
        files = {"images": ("test.png", sample_image_bytes, "image/png")}
        response = client.post("/ocr/v1/extract", files=[("images", ("test.png", sample_image_bytes, "image/png"))])

        assert response.status_code in [200, 207]
        data = response.json()
        assert "status" in data
        assert "requestId" in data

    @patch("app.api.routes.get_ocr_engine")
    def test_extract_jpeg_image(self, mock_get_engine, client, sample_jpeg_bytes, mock_ocr_result):
        """Test extracting text from a JPEG image."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=mock_ocr_result)
        mock_get_engine.return_value = mock_engine

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("test.jpg", sample_jpeg_bytes, "image/jpeg"))]
        )

        assert response.status_code in [200, 207]

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
        assert "Invalid file type" in data.get("detail", str(data))


class TestSimpleExtractEndpoint:
    """Tests for the simplified single-image extract endpoint."""

    @patch("app.api.routes.get_ocr_engine")
    def test_simple_extract(self, mock_get_engine, client, sample_image_bytes, mock_ocr_result):
        """Test simple extraction with a single image."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=mock_ocr_result)
        mock_get_engine.return_value = mock_engine

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

    def test_get_status_not_implemented(self, client):
        """Test that status endpoint returns 501 (not implemented)."""
        response = client.get("/ocr/v1/status/req-test-123")

        assert response.status_code == 501
        data = response.json()
        assert "requestId" in data
        assert data["status"] == "unknown"


# =============================================================================
# File Validation Tests
# =============================================================================

class TestFileValidation:
    """Tests for file validation logic."""

    def test_valid_extensions(self, client, sample_image_bytes):
        """Test that valid extensions are accepted."""
        valid_extensions = [
            ("test.jpg", "image/jpeg"),
            ("test.jpeg", "image/jpeg"),
            ("test.png", "image/png"),
            ("test.webp", "image/webp"),
            ("test.bmp", "image/bmp"),
        ]

        with patch("app.api.routes.get_ocr_engine") as mock_get_engine:
            mock_engine = MagicMock()
            mock_engine.process_image_async = AsyncMock(return_value=MagicMock(
                status="success",
                to_dict=lambda: {"status": "success", "requestId": "test"}
            ))
            mock_get_engine.return_value = mock_engine

            for filename, content_type in valid_extensions:
                response = client.post(
                    "/ocr/v1/extract",
                    files=[("images", (filename, sample_image_bytes, content_type))]
                )
                # Should not return 400 for invalid file type
                assert response.status_code != 400 or "Invalid file type" not in response.text

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


# =============================================================================
# Error Handling Tests
# =============================================================================

class TestErrorHandling:
    """Tests for error handling in API routes."""

    @patch("app.api.routes.get_ocr_engine")
    def test_ocr_processing_error(self, mock_get_engine, client, sample_image_bytes):
        """Test handling of OCR processing errors."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(side_effect=Exception("OCR failed"))
        mock_get_engine.return_value = mock_engine

        response = client.post(
            "/ocr/v1/extract",
            files=[("images", ("test.png", sample_image_bytes, "image/png"))]
        )

        assert response.status_code == 500

    @patch("app.api.routes.get_ocr_engine")
    def test_invalid_image_content(self, mock_get_engine, client):
        """Test handling of invalid image content."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(
            side_effect=ValueError("Failed to decode image")
        )
        mock_get_engine.return_value = mock_engine

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

    @patch("app.api.routes.get_ocr_engine")
    def test_success_response_format(self, mock_get_engine, client, sample_image_bytes, mock_ocr_result):
        """Test that success response has correct format."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=mock_ocr_result)
        mock_get_engine.return_value = mock_engine

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

    @patch("app.api.routes.get_ocr_engine")
    def test_document_info_format(self, mock_get_engine, client, sample_image_bytes, mock_ocr_result):
        """Test that document info has correct format."""
        mock_engine = MagicMock()
        mock_engine.process_image_async = AsyncMock(return_value=mock_ocr_result)
        mock_get_engine.return_value = mock_engine

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

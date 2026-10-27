"""Tests for application lifecycle and health exposure."""

from unittest.mock import patch

from fastapi.testclient import TestClient

from app.main import app


def test_lifespan_initializes_and_shuts_down_engine():
    with patch("app.main.initialize_engine") as initialize, patch(
        "app.main.shutdown_engine"
    ) as shutdown:
        with TestClient(app) as client:
            response = client.get("/health")

        assert response.status_code == 200
        initialize.assert_called_once()
        shutdown.assert_called_once()


def test_request_id_accepts_only_the_explicit_ocr_uuid_header():
    # Avoid loading the OCR engine: request correlation does not depend on model startup.
    with patch("app.main.initialize_engine"), patch("app.main.shutdown_engine"):
        with TestClient(app) as client:
            accepted = client.get(
                "/health", headers={"X-OCR-Request-ID": "ad8f8b31-35a0-4f76-ae46-692ec432601e"}
            )
            rejected = client.get("/health", headers={"X-OCR-Request-ID": "spoofed"})
            generic = client.get("/health", headers={"X-Request-ID": "spoofed"})

    assert accepted.status_code == 200
    assert accepted.headers["X-Request-ID"] == "ad8f8b31-35a0-4f76-ae46-692ec432601e"
    assert rejected.status_code == 200
    assert rejected.headers["X-Request-ID"].startswith("req-")
    assert rejected.headers["X-Request-ID"] != "spoofed"
    assert generic.status_code == 200
    assert generic.headers["X-Request-ID"].startswith("req-")
    assert generic.headers["X-Request-ID"] != "spoofed"

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


def test_request_id_is_generated_server_side_instead_of_trusting_header():
    # The engine is patched for the same reason as in the lifespan test above, and not as
    # a convenience: `with TestClient(app)` runs the lifespan, which pre-loads the OCR
    # engine unless settings.debug is true. The model download is what pushed this test
    # past the 120s timeout in CI, while the assertion below has nothing to do with it.
    with patch("app.main.initialize_engine"), patch("app.main.shutdown_engine"):
        with TestClient(app) as client:
            response = client.get("/health", headers={"X-Request-ID": "spoofed"})

    request_id = response.headers.get("X-Request-ID")
    assert response.status_code == 200
    assert request_id is not None
    assert request_id.startswith("req-")
    assert request_id != "spoofed"

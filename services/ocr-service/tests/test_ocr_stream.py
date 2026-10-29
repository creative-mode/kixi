"""Request-scoped SSE progress contract and status semantics."""

import io
import json
from unittest.mock import MagicMock

import fitz
import numpy as np
from PIL import Image
from fastapi.testclient import TestClient

from app.api.routes import get_ocr_engine as route_get_ocr_engine
from app.config import settings
from app.main import app


def _events(text):
    parsed = []
    for block in text.strip().split("\n\n"):
        lines = block.splitlines()
        event = next(line[7:] for line in lines if line.startswith("event: "))
        data = json.loads(next(line[6:] for line in lines if line.startswith("data: ")))
        parsed.append((event, data))
    return parsed


def test_stream_reports_preparation_each_completed_page_and_final_measured_result(monkeypatch):
    engine = MagicMock()

    async def updates(*_args, **_kwargs):
        yield {"type": "rasterized", "page": 1, "pageCount": 2}
        yield {"type": "rasterized", "page": 2, "pageCount": 2}
        yield {"page": 1, "pageCount": 2, "timings": {"ocrMs": 12.5, "parsingMs": 2.0}}
        yield {"page": 2, "pageCount": 2, "timings": {"ocrMs": 15.0, "parsingMs": 3.0}}
        from app.ocr.engine import DocumentInfo, OCRResult
        from app.ocr.postprocessing import ExtractedMetadata

        yield {"type": "result", "result": OCRResult(
            status="success", request_id="req-test", processing_time_ms=31,
            overall_confidence=0.9, document=DocumentInfo(2, "pt", False),
            metadata=ExtractedMetadata(), questions=[], images_to_upload=[],
            unmapped_content=[], warnings=[],
            timings={"ocrMs": 27.5, "parsingMs": 5.0, "totalMs": 31.0},
        )}

    engine.process_images_progress_async = updates
    app.dependency_overrides[route_get_ocr_engine] = lambda: engine
    monkeypatch.setattr(settings, "enable_auth", False)
    pdf = fitz.open()
    pdf.new_page()
    pdf.new_page()
    payload = pdf.tobytes()
    pdf.close()
    monkeypatch.setattr(
        "app.api.routes.extract_images_from_pdf",
        lambda _content, first_page=None, last_page=None, max_total_pixels=None: [
            np.zeros((20, 20, 3), dtype=np.uint8)
            for _ in range((last_page or first_page or 1) - (first_page or 1) + 1)
        ],
    )

    try:
        response = TestClient(app).post(
            "/ocr/v1/extract/stream",
            files=[("images", ("exam.pdf", payload, "application/pdf"))],
            headers={"X-OCR-Upload-Ms": "12.5"},
        )
    finally:
        app.dependency_overrides.pop(route_get_ocr_engine, None)

    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/event-stream")
    assert response.headers["x-accel-buffering"] == "no"
    events = _events(response.text)
    assert [event for event, _ in events] == [
        "prepared", "rasterized", "rasterized", "progress", "progress", "result"
    ]
    prepared = events[0][1]
    assert prepared["pageCount"] == 2
    assert prepared["timings"]["uploadMs"] == 12.5
    assert prepared["timings"]["rasterizationMs"] == 0
    assert events[1][1]["page"] == 1 and events[1][1]["pageCount"] == 2
    assert events[2][1]["page"] == 2 and events[2][1]["pageCount"] == 2
    assert events[3][1] == {"page": 1, "pageCount": 2, "timings": {"ocrMs": 12.5, "parsingMs": 2.0}}
    assert events[4][1]["page"] == 2
    assert events[5][1]["timings"]["responseAssemblyMs"] >= 0
    assert events[5][1]["timings"]["totalMs"] >= events[5][1]["timings"]["responseAssemblyMs"]


def test_status_endpoint_explicitly_reports_no_persisted_job_state():
    response = TestClient(app).get("/ocr/v1/status/req-any")

    assert response.status_code == 410
    assert response.json()["status"] == "not_trackable"
    assert "no job state is persisted" in response.json()["message"]


def test_stream_rejects_multiple_images_over_request_pixel_budget(monkeypatch):
    engine = MagicMock()
    app.dependency_overrides[route_get_ocr_engine] = lambda: engine
    monkeypatch.setattr(settings, "enable_auth", False)
    monkeypatch.setattr(settings, "max_ocr_pixels_per_request", 15_000)

    def image_bytes():
        buffer = io.BytesIO()
        Image.new("RGB", (100, 100), color="white").save(buffer, format="PNG")
        return buffer.getvalue()

    try:
        response = TestClient(app).post(
            "/ocr/v1/extract/stream",
            files=[
                ("images", ("one.png", image_bytes(), "image/png")),
                ("images", ("two.png", image_bytes(), "image/png")),
            ],
        )
    finally:
        app.dependency_overrides.pop(route_get_ocr_engine, None)

    assert response.status_code == 400
    assert "Invalid uploaded file" in response.json()["detail"]
    assert not engine.process_images_progress_async.called


def test_stream_endpoint_requires_internal_service_key(monkeypatch):
    monkeypatch.setattr(settings, "enable_auth", True)
    monkeypatch.setattr(settings, "api_key", "internal-ocr-key")

    response = TestClient(app).post(
        "/ocr/v1/extract/stream",
        files=[("images", ("exam.png", b"not-an-image", "image/png"))],
    )

    assert response.status_code == 401

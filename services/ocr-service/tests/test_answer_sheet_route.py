"""POST /ocr/v1/answer-sheets/extract with a fake engine: the contract, the limits and the failures (kixi#499)."""

import io
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.api.routes import get_ocr_engine as route_get_ocr_engine
from app.main import app
from app.ocr.postprocessing import TextBlock

STRUCTURE = {"statementId": 2, "questions": [
    {"id": "5", "number": 1, "type": "multiple_choice", "options": ["A", "B", "C", "D"]},
    {"id": "7", "number": 2, "type": "open"},
]}


def line(text, y, confidence=0.95, handwritten=False):
    return TextBlock(text=text, confidence=confidence, bbox=(10, y, 300, y + 20), handwritten=handwritten)


def png():
    buffer = io.BytesIO()
    Image.new("RGB", (100, 100), "white").save(buffer, format="PNG")
    return buffer.getvalue()


def engine_reading(*pages):
    queue = list(pages)

    async def process(image, **kwargs):
        return SimpleNamespace(page_context=SimpleNamespace(text_blocks=queue.pop(0)))

    return SimpleNamespace(process_image_async=AsyncMock(side_effect=process))


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture
def engine_override():
    def override(engine):
        app.dependency_overrides[route_get_ocr_engine] = lambda: engine

    yield override
    app.dependency_overrides.pop(route_get_ocr_engine, None)


def post(client, files, context=None):
    return client.post(
        "/ocr/v1/answer-sheets/extract",
        files=[("images", (f"folha{i}.png", content, "image/png")) for i, content in enumerate(files)],
        data={"context": json.dumps(context if context is not None else STRUCTURE)},
    )


def test_two_students_in_two_photos_come_back_as_two_sheets(client, engine_override):
    engine_override(engine_reading(
        [line("Nome: Ana Domingos", 10), line("Nº 3", 40), line("1) B", 90, 0.97), line("2 - texto da Ana", 130, 0.9, True)],
        [line("Nome: Rui Neto", 10), line("Nº 4", 40), line("1) C", 90, 0.5)],
    ))

    response = post(client, [png(), png()])

    assert response.status_code == 200
    body = response.json()
    assert body["version"] == "1"
    assert body["document"] == {"pageCount": 2, "sheetCount": 2}
    first, second = body["sheets"]
    assert first["student"]["number"]["value"] == 3
    assert first["answers"][0]["selected"] == {"value": "B", "confidence": 0.97}
    assert first["answers"][1]["text"]["value"] == "texto da Ana"
    assert second["answers"][0]["selected"]["value"] == "C"
    codes = {(w["code"], w["field"]) for w in body["warnings"]}
    assert ("LOW_CONFIDENCE", "sheets[1].answers[1]") in codes
    assert ("NOT_FOUND", "sheets[1].answers[2]") in codes


def test_a_page_without_header_continues_the_previous_sheet(client, engine_override):
    engine_override(engine_reading(
        [line("Nº 3", 10), line("1) A", 60)],
        [line("2 - continuação", 40, 0.9, True)],
    ))

    body = post(client, [png(), png()]).json()

    assert body["document"]["sheetCount"] == 1
    assert body["sheets"][0]["pageIndexes"] == [0, 1]
    assert body["sheets"][0]["answers"][1]["text"]["value"] == "continuação"


@pytest.mark.parametrize("context", ['not json', '{}', '{"questions": []}', '{"questions": [{"id": "1"}]}'])
def test_a_context_without_the_exam_structure_is_refused(client, engine_override, context):
    engine_override(engine_reading([]))

    response = client.post(
        "/ocr/v1/answer-sheets/extract",
        files=[("images", ("f.png", png(), "image/png"))],
        data={"context": context},
    )

    assert response.status_code == 400


def test_a_file_that_is_not_an_image_is_refused(client, engine_override):
    engine_override(engine_reading([]))

    assert post(client, [b"isto nao e uma imagem"]).status_code == 400


def test_an_engine_failure_is_a_500_without_details(client, engine_override):
    engine = SimpleNamespace(process_image_async=AsyncMock(side_effect=RuntimeError("detalhe interno")))
    engine_override(engine)

    response = post(client, [png()])

    assert response.status_code == 500
    assert "detalhe interno" not in response.text

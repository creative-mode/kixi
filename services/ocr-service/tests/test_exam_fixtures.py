"""File-backed representative exam fixtures for OCR postprocessing."""

import json
from pathlib import Path

import pytest

from app.ocr.postprocessing import OCRPostprocessor, TextBlock


FIXTURES_DIR = Path(__file__).parent / "fixtures"
FIXTURE_PATHS = sorted(FIXTURES_DIR.glob("prova_*.json"))


@pytest.mark.parametrize(
    "fixture_path",
    FIXTURE_PATHS,
    ids=lambda path: path.stem,
)
def test_representative_exam_fixture(fixture_path):
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    blocks = [
        TextBlock(
            text=block["text"],
            confidence=block["confidence"],
            bbox=tuple(block["bbox"]),
            page_index=block.get("page_index", 0),
            line_index=block.get("line_index", index),
        )
        for index, block in enumerate(fixture["blocks"])
    ]

    metadata, questions, images, _, _ = OCRPostprocessor().process(blocks)
    expected = fixture["expected"]

    assert metadata.subject_name.value == expected["subject"], fixture["name"]
    assert len(questions) == expected["question_count"], fixture["name"]

    if "question_type" in expected:
        assert questions[0].question_type.value == expected["question_type"]
    if "option_count" in expected:
        assert len(questions[0].options) == expected["option_count"]
    if expected.get("has_image"):
        assert questions[0].has_image is True
        assert images
    if "exam_type_contains" in expected:
        assert expected["exam_type_contains"] in metadata.exam_type.value.lower()


def test_representative_fixture_set_is_complete():
    assert {path.name for path in FIXTURE_PATHS} == {
        "prova_fisica_figura.json",
        "prova_matematica_12a.json",
        "prova_multipla_escolha.json",
        "prova_recurso.json",
    }

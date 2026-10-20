"""End-to-end regression for a local, user-supplied photographed physics exam.

The source photo is not stored in Git. Set ``KIXI_PHYSICS_PHOTO_FIXTURE`` and
``KIXI_REQUIRE_PHYSICS_PHOTO_FIXTURE=1`` for a non-skipping OCR acceptance run.
"""

import os
from pathlib import Path

import pytest

from app.config import settings
from app.ocr import get_engine, load_image_from_bytes, shutdown_engine


PHOTO_PATH = os.environ.get("KIXI_PHYSICS_PHOTO_FIXTURE")
CLEAN_PHOTO_PATH = os.environ.get("KIXI_PHYSICS_CLEAN_PHOTO_FIXTURE")
REQUIRE_FIXTURE = os.environ.get("KIXI_REQUIRE_PHYSICS_PHOTO_FIXTURE") == "1"


def test_physics_photo_preserves_question_structure_math_and_source_footer(monkeypatch):
    if not PHOTO_PATH:
        if REQUIRE_FIXTURE:
            pytest.fail("KIXI_PHYSICS_PHOTO_FIXTURE is required for the OCR acceptance run")
        pytest.skip("set KIXI_PHYSICS_PHOTO_FIXTURE to the local physics exam photo")

    path = Path(PHOTO_PATH)
    assert path.is_file(), f"physics photo not found: {path}"
    image = load_image_from_bytes(path.read_bytes())
    monkeypatch.setattr(settings, "ocr_layout_enabled", True)

    engine = get_engine()
    try:
        result = engine.process_image(image, request_id="real-physics-photo-fixture")
    finally:
        shutdown_engine()

    questions = {question.number: question for question in result.questions}
    assert set(questions) == {"1", "2", "3", "4"}
    assert [item.label for item in questions["1"].subitems_content] == ["a", "b", "c"]
    assert [item.label for item in questions["2"].subitems_content] == ["c", "d"]
    assert questions["2"].text.count("$5") == 1, "the same formula was duplicated in the question text"
    assert "Bboxed" not in questions["3"].text
    assert r"B_{max}e^{-2t}" in questions["3"].text
    vector_formulas = [
        formula.latex
        for formula in questions["4"].formulas
        if formula.kind == "formula" and formula.latex
    ]
    assert any(
        (r"\overrightarrow{r}" in latex or r"\vec{r}" in latex)
        and "(1-t)" in latex
        and r"\overrightarrow{e_{x}}" in latex
        and r"(t+1)^{2}" in latex
        and r"\overrightarrow{e_{y}}" in latex
        and r"\imath" not in latex
        for latex in vector_formulas
    ), f"the position vector must be recognized without hallucinated symbols: {vector_formulas!r}"
    assert not any(
        latex.startswith(("[2:4]", "t=3s:"))
        for question in result.questions
        for formula in question.formulas
        if (latex := formula.latex)
    ), "prose and interval labels must not be promoted to equations"
    assert [(item.label, item.text.lower()) for item in questions["4"].subitems_content[:3]] == [
        ("a", "o deslcamento"),
        ("b", "a velocidade média"),
        ("c", "a aceleração média"),
    ]
    labels = [item.label for item in questions["4"].subitems_content]
    assert len(labels) == len(set(labels)), "flattened OCR sections must not produce duplicate persistence labels"
    assert questions["4"].text.upper().count("VIII)") == 1, "the final Roman section must not be duplicated"
    assert "VII)" in questions["4"].text and "VIII)" in questions["4"].text
    assert all("VII)" not in item.text and "VIII)" not in item.text for item in questions["4"].subitems_content)

    all_question_text = " ".join(
        question.text + " " + " ".join(item.text for item in question.subitems_content)
        for question in result.questions
    ).lower()
    assert "a coordenação" not in all_question_text
    assert "secretariaitel@itel.gov.ao" not in all_question_text

    footer = [item for item in result.unmapped_content if item.zone == "rodape"]
    assert any(item.text.lower() == "a coordenação" for item in footer)
    assert any("itel.gov.ao" in item.text.lower() for item in footer)
    assert all(item.bbox and item.page_index == 0 for item in footer)

    footer_region = next(image for image in result.images_to_upload if image.region == "rodape")
    assert footer_region.bbox == (0, footer_region.bbox[1], image.shape[1], image.shape[0])

    recognized_formulae = [
        formula
        for question in result.questions
        for formula in [
            *question.formulas,
            *(formula for item in question.subitems_content for formula in item.formulas),
        ]
        if formula.kind == "formula" and formula.latex
    ]
    assert recognized_formulae, "the mathematical expressions were not converted to LaTeX"
    assert any(warning.code == "LOW_CONFIDENCE_FORMULA" for warning in result.warnings), (
        "uncertain formula OCR must remain visibly marked for review"
    )


def test_clean_physics_photo_recovers_vector_formula_from_original_pixels(monkeypatch):
    if not CLEAN_PHOTO_PATH:
        if REQUIRE_FIXTURE:
            pytest.fail("KIXI_PHYSICS_CLEAN_PHOTO_FIXTURE is required for the OCR acceptance run")
        pytest.skip("set KIXI_PHYSICS_CLEAN_PHOTO_FIXTURE to the local clean physics exam photo")

    path = Path(CLEAN_PHOTO_PATH)
    assert path.is_file(), f"clean physics photo not found: {path}"
    image = load_image_from_bytes(path.read_bytes())
    monkeypatch.setattr(settings, "ocr_layout_enabled", True)

    engine = get_engine()
    try:
        result = engine.process_image(image, request_id="clean-physics-photo-vector-formula")
    finally:
        shutdown_engine()

    questions = {question.number: question for question in result.questions}
    assert result.status == "success"
    assert set(questions) == {"1", "2", "3", "4"}
    expected = r"\vec{r}=(1-t)\overrightarrow{e_{x}}+(t+1)^{2}\overrightarrow{e_{y}}"
    assert any(formula.latex == expected for formula in questions["4"].formulas)
    assert questions["4"].text.count(f"${expected}$") == 1
    assert r"\imath" not in questions["4"].text
    assert r"\boxed" not in questions["4"].text
    assert "$B_{max}e^{-2t}$" in questions["3"].text
    assert "[fórmula]" not in questions["3"].text
    assert [(item.label, item.text.lower()) for item in questions["4"].subitems_content[:3]] == [
        ("a", "o deslocamento"),
        ("b", "a velocidade média"),
        ("c", "a aceleração média"),
    ]
    assert "V) Para o intervalo [2; 4]s, calcule:" in questions["4"].text
    assert "VIII)" in questions["4"].text
    assert all("VII)" not in item.text and "VIII)" not in item.text for item in questions["4"].subitems_content)

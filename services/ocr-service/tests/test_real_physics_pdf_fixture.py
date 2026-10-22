"""End-to-end OCR regression over a rights-local physics handout supplied by path.

The PDF is intentionally not checked into Git. Set ``KIXI_PHYSICS_PDF_FIXTURE`` to
the local file and ``KIXI_REQUIRE_PHYSICS_PDF_FIXTURE=1`` in the dedicated OCR QA
command so the acceptance run cannot silently skip.
"""

import asyncio
import os
import json
from pathlib import Path

import pytest

from app.api.pdf_handler import extract_images_from_pdf, extract_text_pages_from_pdf
from app.ocr import get_engine, shutdown_engine


PDF_PATH = os.environ.get("KIXI_PHYSICS_PDF_FIXTURE")
REQUIRE_FIXTURE = os.environ.get("KIXI_REQUIRE_PHYSICS_PDF_FIXTURE") == "1"
EXPECTED_QUESTION_ANCHORS = {str(number) for number in range(1, 21)}
EXPECTED_FIGURE_QUESTIONS = {"1", "34", "35", "41", "45"}


def test_real_physics_pdf_preserves_questions_subitems_formulas_images_and_confidence(monkeypatch):
    if not PDF_PATH:
        if REQUIRE_FIXTURE:
            pytest.fail("KIXI_PHYSICS_PDF_FIXTURE is required for the OCR acceptance run")
        pytest.skip("set KIXI_PHYSICS_PDF_FIXTURE to the local physics PDF")

    pdf_path = Path(PDF_PATH)
    assert pdf_path.is_file(), f"physics fixture not found: {pdf_path}"
    pdf_content = pdf_path.read_bytes()

    # The suite disables layout inference by default; this acceptance test explicitly exercises its real model.
    from app.config import settings
    monkeypatch.setattr(settings, "ocr_layout_enabled", True)

    native_text_pages = extract_text_pages_from_pdf(pdf_content)
    images = extract_images_from_pdf(pdf_content)
    assert len(images) == len(native_text_pages) == 6

    engine = get_engine()
    progress = []
    result = None

    async def process_document():
        nonlocal result
        async for event in engine.process_images_progress_async(
            images,
            request_id="real-physics-pdf-fixture",
            source_file_indices=[0] * len(images),
            native_text_pages=native_text_pages,
        ):
            if event.get("type") == "result":
                result = event["result"]
            else:
                progress.append(event)

    try:
        asyncio.run(process_document())
    finally:
        shutdown_engine()

    assert result is not None
    recognized_latex = [
        formula.latex
        for question in result.questions
        for formula in [
            *question.formulas,
            *(formula for item in question.subitems_content for formula in item.formulas),
        ]
        if formula.kind == "formula" and formula.latex
    ]
    assert len(progress) == 6, f"progress did not complete all pages: {len(progress)}/6"
    assert [event["page"] for event in progress] == list(range(1, 7))
    assert all(event.get("timings", {}).get("ocrMs") is not None for event in progress)

    if os.environ.get("KIXI_REPORT_PHYSICS_TIMINGS") == "1":
        print(json.dumps({
            "pages": [
                {"page": event["page"], **event.get("timings", {})}
                for event in progress
            ],
            "total": result.timings,
            "questions": len(result.questions),
            "subitems": sum(len(question.subitems_content) for question in result.questions),
            "formula_segments": sum(
                len(question.formulas)
                + sum(len(item.formulas) for item in question.subitems_content)
                for question in result.questions
            ),
            "recognized_latex_segments": len(recognized_latex),
            "recognized_latex": sorted(set(recognized_latex))
            if os.environ.get("KIXI_REPORT_PHYSICS_FORMULAS") == "1" else None,
            "formula_images": sum(image.kind == "formula" for image in result.images_to_upload),
            "figure_images": sum(image.kind == "figure" for image in result.images_to_upload),
            "warnings": [warning.code for warning in result.warnings],
        }, sort_keys=True))

    questions = result.questions
    question_numbers = {question.number for question in questions}
    assert len(questions) >= 20, f"only {len(questions)} structured questions were found"
    assert EXPECTED_QUESTION_ANCHORS <= question_numbers, (
        f"missing questions from the first 20: {sorted(EXPECTED_QUESTION_ANCHORS - question_numbers)}"
    )

    subitems = [item for question in questions for item in question.subitems_content]
    assert len(subitems) >= 10, "printed subquestions were not retained as separate structured items"

    formulas = [
        (question.number, formula)
        for question in questions
        for formula in question.formulas
    ]
    formulas.extend(
        (question.number, formula)
        for question in questions
        for item in question.subitems_content
        for formula in item.formulas
    )
    formula_images = [
        image for image in result.images_to_upload
        if image.kind == "formula" and image.region.startswith("questao_")
    ]
    formula_image_questions = {image.region.removeprefix("questao_") for image in formula_images}
    assert formulas or formula_images, "mathematical notation from the printed pages was not retained"
    assert recognized_latex, "the image recognizer did not produce any LaTeX"
    assert all(
        formula.raw.strip() or question_number in formula_image_questions
        for question_number, formula in formulas
    ), "unreadable formula text must keep its original crop attached to the question"
    assert all(
        formula.kind != "formula" or (formula.raw.strip() and formula.latex)
        for _, formula in formulas
    ), "recognized formulas must retain both source text and LaTeX"
    assert all(0 <= formula.confidence <= 1 for _, formula in formulas)

    figure_images = [image for image in result.images_to_upload if image.kind == "figure"]
    associated_figure_questions = {
        image.region.removeprefix("questao_")
        for image in figure_images
        if image.region.startswith("questao_")
    }
    expected_associations = EXPECTED_FIGURE_QUESTIONS & associated_figure_questions
    assert len(expected_associations) >= 3, (
        "fewer than three known question figures were associated: "
        f"{sorted(associated_figure_questions)}"
    )
    assert all(image.page_index >= 0 and image.bbox for image in figure_images)
    assert all(image.score is not None for image in formula_images)

    assert all(question.confidence is not None for question in questions)
    assert all(item.confidence is not None for item in subitems)
    assert any(
        warning.code in {"NATIVE_TEXT_GAP", "LOW_CONFIDENCE_FORMULA", "UNRECOGNIZED_FORMULA"}
        for warning in result.warnings
    ), "missing mathematical source gaps must force a visible review state"

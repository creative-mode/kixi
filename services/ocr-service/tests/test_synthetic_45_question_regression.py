"""Synthetic regression for segmentation, formula evidence, figures and confidence.

This fixture is intentionally generated from generic text. It is not a transcript of
the local source PDF; source OCR accuracy is validated separately and transiently.
"""

from app.ocr.layout import LayoutRegion
from app.ocr.postprocessing import OCRPostprocessor, TextBlock


def _synthetic_document():
    blocks = []
    for page in range(3):
        for local_number in range(1, 16):
            number = page * 15 + local_number
            y = 60 + (local_number - 1) * 60
            confidence = 0.54 if number == 45 else 0.96
            blocks.append(TextBlock(
                text=f"{number}) Enunciado sintético {number}.",
                confidence=confidence,
                bbox=(20, y, 500, y + 20),
                page_index=page,
                line_index=y,
            ))
            if number == 1:
                blocks.extend([
                    TextBlock("a) Primeiro item sintético.", 0.98, (40, y + 22, 500, y + 42), page, y + 22),
                    TextBlock("b) Segundo item sintético.", 0.61, (40, y + 44, 500, y + 64), page, y + 44),
                ])
            if number == 2:
                blocks.extend([
                    TextBlock("5 × 10-³", 0.93, (80, y + 22, 300, y + 42), page, y + 22),
                    TextBlock("† = (1 = t)ex + (t + 1)²y", 0.48, (80, y + 44, 450, y + 64), page, y + 44),
                ])

    regions = {
        0: [
            LayoutRegion("formula", "formula", 0.91, (70, 142, 320, 164)),
            LayoutRegion("formula", "formula", 0.72, (70, 164, 470, 186)),
            # Large, separate synthetic figure centered within question 8's span.
            LayoutRegion("figure", "image", 0.89, (600, 480, 1000, 550)),
        ]
    }
    return blocks, regions


def test_synthetic_45_question_document_preserves_structure_and_uncertainty():
    blocks, regions = _synthetic_document()
    _, questions, images, _, warnings = OCRPostprocessor(
        repair_text=False,
        filter_noise=False,
    ).process(
        blocks,
        page_count=3,
        page_dimensions={0: (1000, 1000), 1: (1000, 1000), 2: (1000, 1000)},
        layout_regions=regions,
    )

    assert [question.number for question in questions] == [str(number) for number in range(1, 46)]
    assert [item.label for item in questions[0].subitems_content] == ["a", "b"]
    assert questions[0].subitems_content[1].confidence == 0.61

    formulas = questions[1].formulas
    assert formulas[0].raw == "5 × 10-³"
    assert formulas[0].latex == r"5 \times 10^{-3}"
    assert formulas[0].ocr_confidence == 0.93
    assert formulas[0].layout_confidence == 0.91
    assert formulas[1].raw == "† = (1 = t)ex + (t + 1)²y"
    assert formulas[1].latex is None
    assert formulas[1].kind == "unrecognized"
    assert formulas[1].layout_confidence == 0.72

    assert questions[7].has_image is True
    assert [image.region for image in images] == ["questao_8"]
    assert any(warning.code == "UNRECOGNIZED_FORMULA" for warning in warnings)
    assert any(warning.code == "LOW_CONFIDENCE" and warning.field == "question_45" for warning in warnings)

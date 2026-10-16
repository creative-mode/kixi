"""Four photographed exams, from the OCR's text lines and layout regions to questions with their figures.

``fixtures/*.json`` hold what the engine produced for each photo (``blocks``: the text lines; ``layout``: the
figure/table/formula regions; ``width``/``height``: the original photo). The model is not run here: what is
tested is the interpreter that ties each figure to the question it sits under, and the repair of the labels the
photos lose.
"""

import json
from pathlib import Path

import pytest

from app.ocr.layout import LayoutRegion
from app.ocr.postprocessing import OCRPostprocessor, TextBlock

FIXTURES = Path(__file__).parent / "fixtures"


def interpret(name, formula_images=False):
    data = json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    blocks = [
        TextBlock(text=b["text"], confidence=b["conf"], bbox=tuple(b["bbox"]), page_index=0, line_index=b["i"])
        for b in data["blocks"]
    ]
    layout = [LayoutRegion(r["kind"], r["label"], r["score"], tuple(r["bbox"])) for r in data["layout"]]
    metadata, questions, images, *_ = OCRPostprocessor().process(
        blocks, page_dimensions={0: (data["width"], data["height"])}, layout_regions={0: layout},
        formula_images=formula_images,
    )
    figures = [(i.region, i.kind) for i in images if i.region.startswith("questao_")]
    return metadata, questions, figures, images


def test_electrotecnia_has_its_two_circuits_on_the_questions_that_ask_for_them():
    metadata, questions, figures, _ = interpret("electrotecnia_11a.json")

    assert [q.number for q in questions] == ["1", "2", "3", "4"]
    assert metadata.subject_name.value == "Electrótecnia"  # "EXAME ESPECIAL DE ELECTRÓTECNIA"
    assert figures == [("questao_2", "figure"), ("questao_4", "figure")]
    assert [q.has_image for q in questions] == [False, True, False, True]


def test_electrotecnia_gets_back_the_labels_the_photo_lost():
    # "4." was read as a lone dot, "a." of question 1 too, and the printed "b." of question 4 as the digit 6
    _, questions, *_ = interpret("electrotecnia_11a.json")

    assert [len(q.subitems_content) for q in questions] == [3, 3, 0, 8]
    assert [s.label for s in questions[3].subitems_content][:3] == ["a", "b", "c"]
    assert "Dado o circuito" in questions[3].text and "Dado o circuito" not in questions[2].text


def test_the_crop_of_a_circuit_is_the_circuit_and_not_the_whole_question():
    _, _, _, images = interpret("electrotecnia_11a.json")
    circuit = next(i for i in images if i.region == "questao_4")

    x1, y1, x2, y2 = circuit.bbox
    assert (x2 - x1) < 0.6 * circuit.source_width and (y2 - y1) < 0.2 * circuit.source_height
    assert circuit.kind == "figure" and circuit.score > 0.5


def test_a_physics_exam_without_drawings_has_no_figures_and_the_cotacao_line_is_not_a_formula():
    _, questions, figures, _ = interpret("fisica_12a.json")

    assert [q.number for q in questions] == ["1", "2", "3", "4"]
    assert figures == []


def test_the_ogi_exam_with_handwriting_at_the_bottom_has_six_questions_and_no_figures():
    metadata, questions, figures, _ = interpret("ogi_12b_layout.json")

    assert [q.number for q in questions] == ["1", "2", "3", "4", "5", "6"]
    assert metadata.subject_name.value == "OGI"
    assert figures == []


def test_formulas_the_layout_finds_are_cropped_only_when_asked_for():
    _, _, figures, _ = interpret("fisica_12a.json", formula_images=True)

    assert all(kind == "formula" for _, kind in figures)


def test_the_parcelar_exam_read_fast_gives_no_questions_and_read_thoroughly_gives_three():
    # The fast detector misses whole blocks of this photo: that is why the engine reads it again with the
    # thorough one when a page has fewer than two questions.
    _, fast, *_ = interpret("ogi_parcelar_fast.json")
    _, thorough, *_ = interpret("ogi_parcelar_thorough.json")

    assert len(fast) < 2
    assert [q.number for q in thorough] == ["1", "4", "5"]  # "I-" is the 1; 2 and 3 are not in the lines


def test_the_parcelar_exam_read_on_the_raw_photo_has_its_stems_but_not_their_numbers_and_gets_five_questions():
    # The thorough detector on the photo as it came reads the stems of all five questions, but the margin numbers
    # "1-", "2-" and "3-" are lost (the "4" and "5-" survive). The «( N VALORES )» that ends each question numbers them.
    _, questions, *_ = interpret("ogi_parcelar_raw.json")

    assert [q.number for q in questions] == ["1", "2", "3", "4", "5"]
    assert "consiste sistema de produção" in questions[0].text
    assert "plano estratégico" in questions[1].text and "Pert" in questions[4].text


def test_a_paper_with_its_numbers_is_not_renumbered_by_the_cotacao_ends():
    _, questions, *_ = interpret("ogi_12b_layout.json")

    assert [q.number for q in questions] == ["1", "2", "3", "4", "5", "6"]


def test_a_choice_paper_has_three_multiple_choice_questions_with_their_options_and_the_figure_on_the_first():
    # SEAC: each question asks something ("…qual seria o ID…?") and its a) b) c) are the answers; a teacher's
    # scribbles ("m ached)", "0.0.3.25", the ticked X) sit around them, and "5V" closes each stem.
    metadata, questions, figures, _ = interpret("seac_12a.json")

    assert metadata.subject_name.value == "SEAC"
    assert [q.question_type.value for q in questions] == ["multipla_escolha"] * 3
    assert [[o.option_label for o in q.options] for q in questions] == [["A", "B", "C"]] * 3
    assert [o.option_text for o in questions[0].options] == ["10.1.0.1", "192.168.1.100", "209.165.201.1"]
    assert [o.option_text for o in questions[2].options] == ["0.0.3.255", "0.0.0.7", "0.0.0.3"]
    assert [q.cotacao for q in questions] == [5.0, 5.0, 5.0]
    assert all(not q.subitems_content for q in questions)
    assert figures == [("questao_1", "figure")]


def test_alineas_that_are_instructions_stay_alineas_even_under_a_question():
    # The physics paper's first question lists three things to study; they are not answers to choose from.
    _, questions, *_ = interpret("fisica_12a.json")

    assert questions[0].question_type.value == "composta" and not questions[0].options

"""Layout regions -> figures tied to the question they sit under."""

from app.ocr.layout import LayoutRegion, parse_layout_boxes
from app.ocr.postprocessing import OCRPostprocessor, TextBlock

W, H = 720, 1280


def line(text, y, x=60, h=22, i=0):
    return TextBlock(text=text, confidence=0.95, bbox=(x, y, x + 420, y + h), page_index=0, line_index=i)


def exam_blocks():
    """A header, three numbered questions (the second one mentions a figure) and the signature line."""
    lines = [
        ("REPÚBLICA DE ANGOLA", 40), ("PROVA DO EXAME DE ELECTROTECNIA", 100), ("Série: A", 140),
        ("1. Sobre a teoria dos semicondutores, responda:", 330),
        ("2. Encontre as correntes representadas no circuito da figura abaixo.", 560),
        ("3. Apresente a polarização dos transístores no modo saturação (2V)", 900),
        ("A COORDENAÇÃO", 1150),
    ]
    return [line(text, y, i=i) for i, (text, y) in enumerate(lines)]


def region(kind, box, score=0.9):
    return LayoutRegion(kind=kind, label="image" if kind == "figure" else kind, score=score, bbox=box)


def run(regions, formula_images=False):
    _, questions, images, *_ = OCRPostprocessor().process(
        exam_blocks(), page_dimensions={0: (W, H)}, layout_regions={0: regions}, formula_images=formula_images,
    )
    return questions, [i for i in images if i.region.startswith("questao_")]


def test_parse_keeps_figures_tables_and_formulas_above_the_score_and_drops_the_rest():
    boxes = [
        {"label": "text", "score": 0.99, "coordinate": [0, 0, 10, 10]},
        {"label": "image", "score": 0.9, "coordinate": [239, 371, 418, 525]},
        {"label": "image", "score": 0.3, "coordinate": [1, 1, 50, 50]},
        {"label": "table", "score": 0.71, "coordinate": [0, 144, 676, 272]},
        {"label": "formula", "score": 0.8, "coordinate": [10, 600, 300, 640]},
        {"label": "chart", "score": 0.7, "coordinate": [5, 5, 4, 4]},
    ]
    kept = parse_layout_boxes(boxes, min_score=0.5)
    assert [(r.kind, r.bbox) for r in kept] == [
        ("table", (0, 144, 676, 272)),
        ("figure", (239, 371, 418, 525)),
        ("formula", (10, 600, 300, 640)),
    ]


def test_a_figure_goes_to_the_question_it_sits_under_not_to_the_whole_span():
    questions, images = run([region("figure", (239, 580, 540, 780))])
    assert [(i.region, i.kind) for i in images] == [("questao_2", "figure")]
    assert images[0].bbox[0] <= 239 and images[0].bbox[2] >= 540  # the figure, padded, not a full-width strip
    assert images[0].bbox[3] - images[0].bbox[1] < 260
    assert [q.has_image for q in questions] == [False, True, False]


def test_two_figures_of_one_question_keep_page_order_and_get_distinct_names():
    _, images = run([region("figure", (239, 760, 540, 860)), region("figure", (239, 590, 540, 700))])
    assert [i.region for i in images] == ["questao_2", "questao_2"]
    assert [i.bbox[1] < 700 for i in images] == [True, False]
    assert len({i.suggested_filename for i in images}) == 2


def test_the_header_logo_the_signature_and_small_marks_are_not_question_figures():
    _, images = run([
        region("figure", (300, 20, 420, 90)),      # logo above the first question
        region("figure", (300, 1120, 600, 1260)),  # signature under «A COORDENAÇÃO»
        region("figure", (340, 620, 380, 650)),    # a handwritten mark: too small
    ])
    assert images == []


def test_a_picture_that_is_really_a_paragraph_is_dropped():
    # the layout model took the lines of question 3 for a picture: the OCR text covers it
    _, images = run([region("figure", (50, 890, 500, 930))])
    assert images == []


def test_adjacent_text_lines_do_not_collectively_hide_a_real_figure():
    blocks = exam_blocks() + [line("continuação da questão na linha", 585, i=20),
                              line("continuação da questão na linha", 610, i=21)]
    _, _, images, *_ = OCRPostprocessor().process(
        blocks,
        page_dimensions={0: (W, H)},
        layout_regions={0: [region("figure", (239, 580, 540, 680))]},
    )

    assert [(image.region, image.kind) for image in images if image.region.startswith("questao_")] == [
        ("questao_2", "figure"),
    ]


def test_without_a_layout_the_old_crop_of_the_question_text_is_kept():
    blocks = exam_blocks()
    _, questions, images, *_ = OCRPostprocessor().process(blocks, page_dimensions={0: (W, H)}, layout_regions=None)
    flagged = [q.number for q in questions if q.has_image]
    assert [i.region for i in images if i.region.startswith("questao_")] == [f"questao_{n}" for n in flagged]


def test_a_formula_is_a_picture_whether_it_stands_alone_or_sits_in_a_sentence():
    blocks = exam_blocks() + [line("B = Bmax e", 620, x=60, i=20)]
    standalone = LayoutRegion(kind="formula", label="formula", score=0.9, bbox=(150, 700, 560, 740))
    inline = LayoutRegion(kind="formula", label="formula", score=0.9, bbox=(300, 620, 500, 642))
    _, _, images, *_ = OCRPostprocessor().process(
        blocks, page_dimensions={0: (W, H)}, layout_regions={0: [standalone, inline]}, formula_images=True,
    )
    kinds = [(i.region, i.kind) for i in images if i.region.startswith("questao_")]
    # the text engine cannot read maths, so each formula travels as a picture, in page order
    assert kinds == [("questao_2", "formula"), ("questao_2", "formula")]


def test_formulas_are_not_cropped_unless_asked_for():
    _, images = run([region("formula", (150, 700, 560, 740))])
    assert images == []


def test_a_tall_handwritten_box_does_not_scramble_two_rows_of_options():
    """The note [938,829,1089,873] spans the rows of "b)" and "c)"; the rows must still read b) value, c) value."""
    from app.ocr.postprocessing import OCRPostprocessor

    def box(text, x1, y1, x2, y2, i):
        return TextBlock(text=text, confidence=0.95, bbox=(x1, y1, x2, y2), page_index=0, line_index=i)

    blocks = [
        box("b)", 164, 827, 195, 851, 0), box("192.168.1.100", 195, 824, 335, 850, 1),
        box("m ached)", 938, 829, 1089, 873, 2),
        box("c)", 165, 864, 188, 886, 3), box("209.165.201.1", 194, 858, 333, 885, 4),
    ]
    assert [b.text for b in OCRPostprocessor._reading_order(blocks)] == ["b)", "192.168.1.100", "m ached)", "c)", "209.165.201.1"]

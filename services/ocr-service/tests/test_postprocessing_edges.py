"""Behavioral coverage for OCR postprocessing edge cases."""

from app.ocr.postprocessing import (
    ExtractedMetadata,
    ExtractedOption,
    ExtractedQuestion,
    ImageToUpload,
    MetadataField,
    OCRPostprocessor,
    QuestionType,
    SubitemContent,
    TextBlock,
    UnmappedContent,
    Warning,
)
from app.ocr.formulas import FORMULA, UNRECOGNIZED, FormulaSegment
from app.ocr.layout import LayoutRegion


def block(text: str, y: int, confidence: float = 0.9, page: int = 0) -> TextBlock:
    return TextBlock(
        text=text,
        confidence=confidence,
        bbox=(10, y, 500, y + 20),
        page_index=page,
        line_index=y,
    )


def test_serialization_models_preserve_optional_fields():
    subitem = SubitemContent(label="a)", text="Calcule x", cotacao=2.5)
    question = ExtractedQuestion(
        number="1",
        text="Observe a figura.",
        text_confidence=0.91,
        question_type=QuestionType.MULTIPLA_ESCOLHA,
        question_type_confidence=0.95,
        subitems=["a)"],
        subitems_content=[subitem],
        cotacao=2.5,
        cotacao_confidence=0.8,
        options=[ExtractedOption("A", "5", 0.88)],
        has_image=True,
        image_description="Gráfico de uma função",
        page_index=1,
        start_y=10,
        end_y=80,
    )

    result = question.to_dict()

    assert result["subitemsContent"] == [subitem.to_dict()]
    assert result["options"][0]["optionLabel"] == "A"
    assert result["imageDescription"] == "Gráfico de uma função"
    assert result["hasImage"] is True

    metadata = ExtractedMetadata(subject_name=MetadataField("Matemática", 0.9))
    assert metadata.to_dict()["subjectName"] == {
        "value": "Matemática",
        "confidence": 0.9,
    }
    assert UnmappedContent(0, "texto", 0.6).to_dict()["pageIndex"] == 0
    assert Warning("LOW_CONFIDENCE", "subjectName", 0.6, "rever").to_dict()["message"] == "rever"
    assert Warning("UNKNOWN", "type", 0.4).to_dict()["code"] == "UNKNOWN"
    assert ImageToUpload("q1.png", "Figura", "questao_1").region == "questao_1"


def test_question_keeps_source_ocr_separate_from_repaired_display_text():
    processor = OCRPostprocessor(repair_text=False, filter_noise=False)
    source_block = block("1 Determine x²", 10)
    source_block.raw_text = "1 Determine x2"

    _, questions, *_ = processor.process([source_block])

    assert len(questions) == 1
    assert questions[0].text == "Determine $x^{2}$"
    assert questions[0].raw_text == "1 Determine x2"
    assert questions[0].to_dict()["rawText"] == "1 Determine x2"


def test_postprocessor_parsers_cover_scores_images_and_question_variants():
    processor = OCRPostprocessor()

    assert processor._normalize_subject_name("fisíca") == "Física"
    assert processor._normalize_subject_name("historia aplicada") == "História"
    assert processor._normalize_subject_name("química") == "Química"
    assert processor._normalize_subject_name("") == ""
    assert processor._normalize_subject_name("engenharia") == "Engenharia"


    metadata = ExtractedMetadata(
        exam_type=MetadataField("Prova de Exame", 0.9),
        subject_name=MetadataField("Matemática", 0.9),
        class_grade=MetadataField("12", 0.9),
        variant=MetadataField("B", 0.9),
        school_year_start=MetadataField(2024, 0.9),
        school_year_end=MetadataField(2025, 0.9),
    )
    assert processor._build_title(metadata) == "Prova de Exame Matemática 12ª Classe - Série B - 2024/2025"
    assert processor._build_title(ExtractedMetadata()) is None

    scores = processor._parse_cotacao_block(
        "Cotação: 1-a) 3 valores 1-b) 2,5 valores 2-) 4 valores 3) 5 V"
    )
    assert scores["1a"] == 3.0
    assert scores["1b"] == 2.5
    assert scores["2"] == 4.0
    assert scores["3"] == 5.0
    assert processor._parse_cotacao_block("sem cotação") == {}

    assert processor._detect_question_number("12ª Classe") is None
    assert processor._detect_question_number("questão nº 3") == (3, "")
    assert processor._detect_question_number("0. inválida") is None
    assert processor._detect_subitem_with_text("(a) resolva") == ("a)", "resolva")
    assert processor._detect_subitem_with_text("b - determine") == ("b)", "determine")
    assert processor._detect_subitem("c)") == "c)"
    assert processor._extract_inline_cotacao("valor (2,5V)") == 2.5
    assert processor._extract_inline_cotacao("valor (4 pontos)") == 4.0
    assert processor._remove_inline_cotacao("Calcule (3 valores) x") == "Calcule x"
    assert processor._clean_footer_garbage("resposta A COORDENAÇÃO minttics.gov.ao") == "resposta"

    assert processor._is_option_block("A) cinco") is True
    assert processor._detect_option("B - dez") == ("B", "dez")
    assert processor._detect_option("sem opção") is None
    assert processor._detect_image_reference("Observe a figura que mostra uma reta.") == (True, "uma reta")
    assert processor._detect_image_reference(r"Resolva \\sqrt{x}") == (True, "Expressão matemática complexa")
    assert processor._detect_image_reference("texto normal") == (False, None)
    assert processor._is_coordination_block("Assinatura do coordenador") is True


def test_formula_recognition_keeps_subitem_ownership_in_json_contract():
    question = ExtractedQuestion(
        number="1",
        text="Calcule o campo.",
        text_confidence=0.91,
        question_type=QuestionType.DISSERTATIVA,
        question_type_confidence=0.95,
        subitems_content=[SubitemContent(label="a", text="Use B = Bmaxe-2t", confidence=0.87)],
    )

    result = OCRPostprocessor._recognize_question_formulas(question).to_dict()
    subitem = result["subitemsContent"][0]

    assert subitem["text"] == r"Use B = $B_{max}$$e^{-2t}$"
    assert {formula["raw"] for formula in subitem["formulas"]} == {"Bmax", "e-2t"}
    assert {formula["raw"] for formula in result["formulas"]} >= {"Bmax", "e-2t"}


def test_inline_and_layout_formula_detection_merges_duplicate_spans_conservatively():
    question = ExtractedQuestion(
        number="1",
        text="O resultado é 8π.",
        text_confidence=0.92,
        question_type=QuestionType.DISSERTATIVA,
        question_type_confidence=0.95,
        formulas=[FormulaSegment("8π", r"8\pi", 0.9, FORMULA, layout_confidence=0.88)],
    )

    result = OCRPostprocessor._recognize_question_formulas(question)

    assert len(result.formulas) == 1
    assert result.formulas[0].ocr_confidence == 0.92
    assert result.formulas[0].layout_confidence == 0.88
    assert result.formulas[0].confidence <= 0.9


def test_layout_formula_is_not_duplicated_by_its_inline_text_spans():
    layout_formula = FormulaSegment(
        "B = Bmaxe-2t",
        r"B = B_{max}e^{-2t}",
        0.86,
        FORMULA,
        ocr_confidence=0.78,
        layout_confidence=0.94,
    )
    item = SubitemContent(
        label="a",
        text="B = Bmaxe-2t",
        confidence=0.64,
        page_index=0,
        start_y=100,
        end_y=120,
    )
    question = ExtractedQuestion(
        number="1",
        text="Calcule a intensidade.",
        text_confidence=0.91,
        question_type=QuestionType.COMPOSTA,
        question_type_confidence=0.9,
        subitems_content=[item],
        page_index=0,
        start_y=80,
        end_y=150,
    )
    processor = OCRPostprocessor()
    source_block = block("B = Bmaxe-2t", 100, confidence=0.78)
    source_block.formula_segments = [layout_formula]
    processor._attach_formula_segments(
        [question],
        [source_block],
        {0: [LayoutRegion("formula", "formula", 0.94, source_block.bbox)]},
    )

    result = OCRPostprocessor._recognize_question_formulas(question)

    assert len(result.subitems_content[0].formulas) == 1
    assert len(result.formulas) == 1
    assert result.formulas[0].raw == "B = Bmaxe-2t"
    assert result.formulas[0].layout_confidence == 0.94
    assert result.formulas[0].ocr_confidence == 0.64
    assert result.subitems_content[0].to_dict()["confidence"] == 0.64


def test_unrecognized_layout_formula_is_attached_to_the_source_subitem():
    item = SubitemContent(
        label="b", text="[fórmula]", confidence=0.63,
        page_index=0, start_y=100, end_y=150,
    )
    question = ExtractedQuestion(
        number="1",
        text="Calcule.",
        text_confidence=0.91,
        question_type=QuestionType.COMPOSTA,
        question_type_confidence=0.9,
        page_index=0,
        start_y=80,
        end_y=160,
        subitems_content=[item],
    )
    processor = OCRPostprocessor()

    processor._attach_formula_segments(
        [question], [], {0: [LayoutRegion("formula", "formula", 0.91, (50, 110, 150, 140))]}
    )

    assert len(question.subitems_content[0].formulas) == 1
    assert question.subitems_content[0].formulas[0].kind == UNRECOGNIZED
    assert question.formulas == question.subitems_content[0].formulas
    result = OCRPostprocessor._recognize_question_formulas(question).to_dict()
    assert result["subitemsContent"][0]["confidence"] == 0.63
    assert result["subitemsContent"][0]["formulas"][0]["kind"] == UNRECOGNIZED


def test_process_detects_header_question_and_footer_image_regions():
    processor = OCRPostprocessor(low_confidence_threshold=0.95)
    blocks = [
        block("República de Angola", 10),
        block("Ministério da Educação", 30),
        block("PROVA DE EXAME DE MATEMÁTICA", 50),
        block("Ano Letivo: 2024/2025", 70),
        block("12ª Classe Série B", 90),
        block("1. Observe a figura que mostra uma reta (3 valores)", 120),
        block("A) cinco", 150),
        block("B) dez", 180),
        block("A COORDENAÇÃO", 220),
    ]

    metadata, questions, images, unmapped, warnings = processor.process(blocks)

    assert metadata.subject_name.value == "Matemática"
    assert len(questions) == 1
    assert questions[0].has_image is True
    assert questions[0].cotacao == 3.0
    assert questions[0].question_type == QuestionType.MULTIPLA_ESCOLHA
    assert [(option.option_label, option.option_text) for option in questions[0].options] == [
        ("A", "cinco"),
        ("B", "dez"),
    ]
    assert any(image.region == "cabecalho" for image in images)
    assert any(image.region == "questao_1" for image in images)
    assert any(image.region == "rodape" for image in images)
    assert unmapped == []
    assert isinstance(warnings, list)


def test_process_emits_versioned_raster_region_contract():
    processor = OCRPostprocessor()
    blocks = [
        block("República de Angola", 10),
        block("Ministério da Educação", 30),
        block("PROVA DE EXAME DE MATEMÁTICA", 50),
        block("Ano Letivo: 2024/2025", 70),
        block("12ª Classe Série B", 90),
        block("1. Observe a figura que mostra uma reta", 120),
        block("A) cinco", 150),
        block("B) dez", 180),
        block("A COORDENAÇÃO", 220),
    ]

    _, _, images, _, _ = processor.process(
        blocks,
        page_dimensions={0: (1000, 800)},
    )

    question_image = next(image for image in images if image.region == "questao_1")
    assert question_image.contract_version == 1
    assert question_image.bbox == (0, 96, 1000, 224)
    assert question_image.page_index == 0
    assert question_image.source_width == 1000
    assert question_image.source_height == 800
    assert question_image.source_file_index == 0


def test_process_keeps_lowercase_multipart_items_as_subitems():
    processor = OCRPostprocessor()
    blocks = [
        block("1. Resolva os itens seguintes.", 10),
        block("a) Calcule 2 + 2.", 50),
        block("b) Determine o valor de x.", 90),
    ]
    blocks[1].raw_text = "a) Calcule 2+2."

    _, questions, *_ = processor.process(blocks)

    assert len(questions) == 1
    # Uma questão com enunciado comum e alíneas que têm resposta própria é `composta` (antes era uma única
    # `dissertativa` com as alíneas coladas ao texto); o enunciado fica sem elas e cada alínea tem o seu tipo.
    assert questions[0].question_type == QuestionType.COMPOSTA
    assert questions[0].text == "Resolva os itens seguintes."
    assert [(item.label, item.type) for item in questions[0].subitems_content] == [
        ("a", "dissertativa"),
        ("b", "dissertativa"),
    ]
    assert questions[0].options is None
    assert questions[0].subitems == ["a)", "b)"]
    assert [item.text for item in questions[0].subitems_content] == [
        "Calcule 2 + 2.",
        "Determine o valor de x.",
    ]
    assert questions[0].subitems_content[0].raw_text == "a) Calcule 2+2."
    assert questions[0].subitems_content[0].to_dict()["rawText"] == "a) Calcule 2+2."


def test_process_keeps_lowercase_choice_labels_when_prompt_requires_choice():
    processor = OCRPostprocessor()
    blocks = [
        block("1. Assinale a alternativa correta.", 10),
        block("a) cinco", 50),
        block("b) dez", 90),
    ]

    _, questions, *_ = processor.process(blocks)

    assert questions[0].question_type == QuestionType.MULTIPLA_ESCOLHA
    assert [(option.option_label, option.option_text) for option in questions[0].options] == [
        ("A", "cinco"),
        ("B", "dez"),
    ]


def test_process_does_not_use_exam_type_as_subject_when_discipline_is_labelled():
    processor = OCRPostprocessor()
    blocks = [
        block("PROVA DE EXAME", 10),
        block("DISCIPLINA: MATEMÁTICA", 30),
        block("12ª CLASSE", 50),
    ]

    metadata, *_ = processor.process(blocks)

    assert metadata.exam_type.value == "Prova de Exame"
    assert metadata.subject_name.value == "Matemática"


def test_process_keeps_recognized_text_in_unmapped_when_no_questions_are_parsed():
    """Text the parser made no questions of is still returned, not dropped (#292)."""
    processor = OCRPostprocessor()
    blocks = [
        block("Tabela de valores trimestrais", 10),
        block("Janeiro 120 Fevereiro 150", 40),
    ]

    _, questions, _, unmapped, _ = processor.process(blocks)

    joined = " ".join(item.text for item in unmapped)
    assert questions == []
    assert "Tabela de valores trimestrais" in joined
    assert "Janeiro 120 Fevereiro 150" in joined


def test_a_noise_block_is_not_repeated_in_unmapped_when_there_are_no_questions():
    """A block moved out as noise is not added again by the no-questions branch (#292).

    The noise never belongs to ``blocks`` by the time ``_collect_unmapped`` runs — ``_split_noise``
    and ``_cut_after_cotacao`` take it out first — so its text must appear once, not twice with two
    different reasons.
    """
    processor = OCRPostprocessor()
    scribble = TextBlock(
        text="nota à margem",
        confidence=0.9,
        bbox=(10, 200, 500, 220),
        page_index=0,
        line_index=200,
        handwritten=True,
    )
    blocks = [
        block("Tabela de valores trimestrais", 10),
        block("Janeiro cento e vinte", 40),
        block("Fevereiro cento e cinquenta", 70),
        block("Março cento e oitenta", 100),
        scribble,
    ]

    _, questions, _, unmapped, _ = processor.process(blocks)

    assert questions == []
    texts = [item.text for item in unmapped]
    assert texts.count("nota à margem") == 1, texts


def test_collect_unmapped_skips_a_block_already_collected_as_noise():
    """The no-questions branch never lists a block that the noise pass already collected (#292).

    Called directly with the same block in both lists — a caller contract the guard keeps even if
    the flow ever changes — the text is collected once, with the noise reason it was given.
    """
    processor = OCRPostprocessor()
    scribble = block("nota à margem", 200, confidence=0.9)
    text = block("Tabela de valores trimestrais", 10)

    unmapped = processor._collect_unmapped(
        [scribble, text],
        ExtractedMetadata(),
        [],
        [(scribble, "Manuscrito (tinta que não é de impressão)")],
    )

    assert [item.text for item in unmapped] == [
        "nota à margem",
        "Tabela de valores trimestrais",
    ]
    assert [item.reason for item in unmapped] == [
        "Manuscrito (tinta que não é de impressão)",
        "Texto lido sem perguntas reconhecidas",
    ]


def test_question_continuation_across_page_break_stays_with_original_question():
    processor = OCRPostprocessor()
    blocks = [
        block("6. Determine a velocidade do móvel.", 100, page=0),
        block("A expressão continua no início da página seguinte.", 20, page=1),
        block("7. Calcule a distância percorrida.", 80, page=1),
    ]

    questions = processor._extract_questions(blocks, {})

    assert [question.number for question in questions] == ["6", "7"]
    assert "continua no início" in questions[0].text
    assert questions[1].text == "Calcule a distância percorrida."


def test_page_start_resynchronizes_on_explicit_question_number_then_is_strict():
    processor = OCRPostprocessor()
    blocks = [
        block("6. Calcule a distância.", 1, page=0),
        block("8. Calcule a velocidade.", 20, page=1),
        block("9) Determine a posição.", 60, page=1),
        block("14. Calcule a aceleração.", 100, page=1),
        block("15. Determine o tempo.", 140, page=1),
    ]

    questions = processor._extract_questions(blocks, {})

    assert [question.number for question in questions] == ["6", "8", "9"]
    assert "14. Calcule a aceleração." in questions[2].text
    assert "15. Determine o tempo." in questions[2].text


def test_page_start_does_not_resynchronize_from_bare_or_ambiguous_numbers():
    processor = OCRPostprocessor()
    blocks = [
        block("1. Determine a velocidade.", 20, page=0),
        block("2. Calcule a aceleração.", 50, page=0),
        block("4 O móvel parte do repouso.", 20, page=1),
        block("80 m/s é a velocidade inicial.", 50, page=1),
        block("7.5 m é a distância indicada.", 80, page=1),
    ]

    questions = processor._extract_questions(blocks, {})

    assert [question.number for question in questions] == ["1", "2"]
    assert "4 O móvel parte" in questions[1].text

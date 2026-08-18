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

    _, questions, *_ = processor.process(blocks)

    assert len(questions) == 1
    assert questions[0].question_type == QuestionType.DISSERTATIVA
    assert questions[0].options is None
    assert questions[0].subitems == ["a)", "b)"]
    assert [item.text for item in questions[0].subitems_content] == [
        "Calcule 2 + 2.",
        "Determine o valor de x.",
    ]


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

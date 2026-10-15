"""Regras de estrutura do interpretador: numeração e alíneas por sequência, tipos, ruído e páginas."""

import pytest

from app.ocr.postprocessing import OCRPostprocessor, QuestionType, TextBlock


def block(text, y, confidence=0.93, x=100, width=700, page=0):
    return TextBlock(text=text, confidence=confidence, bbox=(x, y, x + width, y + 24), page_index=page, line_index=y)


def run(*texts, **options):
    """Linhas de prosa a 40 px umas das outras: o suficiente para o filtro de ruído ter contexto."""
    blocks = [block(t, 40 + i * 40) if isinstance(t, str) else t for i, t in enumerate(texts)]
    return OCRPostprocessor(**options).process(blocks)


# --- numeração -----------------------------------------------------------------------------------------------

@pytest.mark.parametrize("text,expected", [
    ("1) Explique o conceito", (1, "Explique o conceito")),
    ("1. Explique o conceito", (1, "Explique o conceito")),
    ("1- Explique o conceito", (1, "Explique o conceito")),
    ("(1) Explique o conceito", (1, "Explique o conceito")),
    ("1 Explique o conceito de lote", (1, "Explique o conceito de lote")),
    ("Questão 1: explique", (1, ": explique")),
])
def test_forms_of_the_first_question_number(text, expected):
    found = OCRPostprocessor()._detect_question_number(text, expected=1)
    assert found is not None and found[0] == expected[0]
    assert found[1].endswith(expected[1].lstrip(": ")) or found[1] == expected[1]


@pytest.mark.parametrize("text,expecting", [
    ("2.500 unidades por mês", 2),       # decimal
    ("1,8% da demanda em dias", 1),      # decimal
    ("90 mil unidades mensal", 90),      # fora de 1-50
    ("12 Classe Ano Lectivo", 12),       # cabeçalho
    ("3 Pessoas trabalham aqui", 2),     # não é o número esperado
    ("2 pessoas", 2),                    # sem maiúscula nem texto suficiente
    ("5", 5),                            # só um número
])
def test_a_number_in_the_middle_of_text_is_not_a_question(text, expecting):
    assert OCRPostprocessor()._detect_question_number(text, expected=expecting) is None


def test_a_bare_number_is_a_question_only_when_it_is_the_next_one():
    p = OCRPostprocessor()
    assert p._detect_question_number("2 O que entendes por ciclo de vida", expected=2) is not None
    assert p._detect_question_number("4 O que entendes por ciclo de vida", expected=2) is None


def test_a_punctuated_number_may_skip_one_lost_question():
    p = OCRPostprocessor()
    assert p._detect_question_number("3) Calcule o valor", expected=2) is not None
    assert p._detect_question_number("9) Calcule o valor", expected=2) is None


def test_numbers_in_the_text_do_not_split_the_question():
    _, questions, *_ = run(
        "1 Explique o conceito de lote económico em detalhe.",
        "3 pessoas trabalham aqui e fazem 2.500 peças por mês.",
        "2 Calcule o ponto de pedido da empresa.",
    )
    assert [q.number for q in questions] == ["1", "2"]
    assert "3 pessoas trabalham aqui" in questions[0].text


def test_numbering_continues_across_pages():
    page1 = [block("1) Explique o conceito de lote.", 40), block("2) Calcule o custo de manutenção.", 80)]
    page2 = [block("3 Determine o ponto de pedido.", 40, page=1), block("4 Justifique a sua resposta.", 80, page=1)]
    _, questions, *_ = OCRPostprocessor().process(page1 + page2)
    assert [(q.number, q.page_index) for q in questions] == [("1", 0), ("2", 0), ("3", 1), ("4", 1)]


# --- alíneas -------------------------------------------------------------------------------------------------

def test_glued_alineas_follow_the_alphabet():
    _, questions, *_ = run(
        "1) Resolva os itens seguintes.",
        "aCalcule a área do retângulo.",
        "bDetermine o perímetro da figura.",
        "cJustifique cada resposta dada.",
    )
    q = questions[0]
    assert q.question_type == QuestionType.COMPOSTA
    assert q.text == "Resolva os itens seguintes."
    assert [(s.label, s.text) for s in q.subitems_content] == [
        ("a", "Calcule a área do retângulo."),
        ("b", "Determine o perímetro da figura."),
        ("c", "Justifique cada resposta dada."),
    ]
    assert q.subitems == ["a)", "b)", "c)"]


def test_a_glued_label_out_of_sequence_is_just_text():
    _, questions, *_ = run(
        "1) Resolva os itens seguintes.",
        "aCalcule a área do retângulo.",
        "dDetermine o perímetro da figura.",   # d depois de a: não é a alínea esperada (b)
        "bJustifique cada resposta dada.",
    )
    labels = [s.label for s in questions[0].subitems_content]
    assert labels == ["a", "b"]
    assert "dDetermine o perímetro" in questions[0].subitems_content[0].text


def test_the_first_alinea_must_be_a():
    _, questions, *_ = run("1) Resolva.", "bCalcule a área do retângulo.", "cDetermine o perímetro.")
    assert questions[0].subitems_content == []
    assert questions[0].question_type == QuestionType.DISSERTATIVA


def test_a_single_alinea_keeps_the_old_shape():
    _, questions, *_ = run("1) Resolva o item.", "a) Calcule 2 + 2.")
    q = questions[0]
    assert q.question_type == QuestionType.DISSERTATIVA
    assert q.subitems == ["a)"]
    assert "Calcule 2 + 2." in q.text


def test_a_continuation_line_is_added_to_the_alinea_it_belongs_to():
    _, questions, *_ = run(
        "1) Resolva os itens.",
        "a) Calcule a área do retângulo",
        "cuja base mede 5 m.",
        "b) Determine o perímetro.",
    )
    assert questions[0].subitems_content[0].text == "Calcule a área do retângulo cuja base mede 5 m."


# --- tipos ---------------------------------------------------------------------------------------------------

def test_true_false_from_the_statement():
    _, questions, *_ = run(
        "1) Diga quais são verdadeiras ou falsas:",
        "a) A água ferve a 100 graus. ( )",
        "b) O sol gira à volta da terra. ( )",
    )
    q = questions[0]
    assert q.question_type == QuestionType.VERDADEIRO_FALSO
    assert [s.type for s in q.subitems_content] == ["verdadeiro_falso", "verdadeiro_falso"]
    assert [s.text for s in q.subitems_content] == ["A água ferve a 100 graus.", "O sol gira à volta da terra."]


def test_true_false_from_the_empty_parentheses_alone():
    _, questions, *_ = run(
        "1) Classifique as afirmações seguintes.",
        "a) A água ferve a 100 graus. ( )",
        "b) O sol gira à volta da terra. ( )",
        "c) Luanda é a capital de Angola. ( )",
    )
    assert questions[0].question_type == QuestionType.VERDADEIRO_FALSO


def test_alineas_without_the_tick_boxes_are_not_true_false():
    _, questions, *_ = run("1) Resolva.", "a) Calcule 2 + 2.", "b) Calcule 3 + 3.")
    assert questions[0].question_type == QuestionType.COMPOSTA


def test_multiple_choice_with_consecutive_options():
    _, questions, *_ = run("1) Qual é a capital de Angola?", "A) Huambo", "B) Luanda", "C) Benguela", "D) Lobito")
    q = questions[0]
    assert q.question_type == QuestionType.MULTIPLA_ESCOLHA
    assert [(o.option_label, o.option_text) for o in q.options] == [
        ("A", "Huambo"), ("B", "Luanda"), ("C", "Benguela"), ("D", "Lobito"),
    ]


def test_a_single_stray_option_is_text_not_a_choice_question():
    _, questions, *_ = run("1) Explique o conceito de lote.", "C) A escolha das matérias é importante.")
    q = questions[0]
    assert q.options is None
    assert q.question_type == QuestionType.DISSERTATIVA
    assert "A escolha das matérias é importante." in q.text


def test_options_that_do_not_start_at_a_are_not_a_choice():
    _, questions, *_ = run("1) Explique.", "B) primeira", "C) segunda")
    assert questions[0].options is None


def test_a_choice_instruction_without_options_is_still_multiple_choice():
    _, questions, *_ = run("1) Assinale a alternativa correta.")
    assert questions[0].question_type == QuestionType.MULTIPLA_ESCOLHA


def test_the_noun_escolha_does_not_make_a_choice_question():
    _, questions, *_ = run("1) A escolha das matérias-primas influencia o custo? Explique.")
    assert questions[0].question_type == QuestionType.DISSERTATIVA


# --- ruído ---------------------------------------------------------------------------------------------------

def test_handwriting_beside_a_printed_line_is_set_aside():
    blocks = [
        block("1) Explique o conceito de lote económico.", 100),
        block("2) Calcule o ponto de pedido da empresa.", 140),
        block("3) Determine o estoque de segurança.", 180),
        block("4) Justifique a sua resposta final.", 220),
        block("M=31D", 222, confidence=0.87, x=900, width=90),
    ]
    _, questions, _, unmapped, _ = OCRPostprocessor().process(blocks)
    assert "M=31D" not in questions[3].text
    assert [(u.text, u.reason) for u in unmapped] == [("M=31D", "Marca ou manuscrito ao lado de uma linha impressa")]


def test_a_formula_between_printed_lines_is_kept():
    blocks = [
        block("1) Resolva a equação seguinte:", 100),
        block("2x + 5 = 15", 140, confidence=0.9, x=100, width=160),
        block("2) Calcule o ponto de pedido da empresa.", 180),
        block("3) Determine o estoque de segurança.", 220),
        block("4) Justifique a sua resposta final.", 260),
    ]
    _, questions, _, unmapped, _ = OCRPostprocessor().process(blocks)
    assert "2x + 5 = 15" in questions[0].text
    assert unmapped == []


def test_a_score_at_the_right_margin_is_not_noise():
    blocks = [
        block("1) Explique o conceito de lote económico.", 100, width=500),
        block("(2 valores)", 100, confidence=0.88, x=700, width=120),
        block("2) Calcule o ponto de pedido da empresa.", 140),
        block("3) Determine o estoque de segurança.", 180),
        block("4) Justifique a sua resposta final.", 220),
    ]
    _, questions, _, unmapped, _ = OCRPostprocessor().process(blocks)
    assert questions[0].cotacao == 2.0
    assert unmapped == []


def test_marks_above_the_first_printed_line_are_set_aside():
    blocks = [
        block("81", 10, confidence=0.98, x=600, width=40),
        block("PROVA DE EXAME DE MATEMÁTICA", 100),
        block("1) Explique o conceito de lote económico.", 140),
        block("2) Calcule o ponto de pedido da empresa.", 180),
        block("3) Determine o estoque de segurança.", 220),
    ]
    _, _, _, unmapped, _ = OCRPostprocessor().process(blocks)
    assert [(u.text, u.reason) for u in unmapped] == [("81", "Marca ou manuscrito acima do texto impresso")]


def test_a_page_with_too_little_text_is_never_filtered():
    blocks = [block("1) Explique o conceito.", 100), block("M=31D", 102, confidence=0.5, x=900, width=90)]
    _, _, _, unmapped, _ = OCRPostprocessor().process(blocks)
    assert unmapped == []


def test_noise_filter_can_be_turned_off():
    blocks = [
        block("1) Explique o conceito de lote económico.", 100),
        block("2) Calcule o ponto de pedido da empresa.", 140),
        block("3) Determine o estoque de segurança.", 180),
        block("4) Justifique a sua resposta final.", 220),
        block("M=31D", 222, confidence=0.87, x=900, width=90),
    ]
    _, questions, _, unmapped, _ = OCRPostprocessor(filter_noise=False).process(blocks)
    assert "M=31D" in questions[3].text and unmapped == []


# --- reparação ligada/desligada ------------------------------------------------------------------------------

def test_text_repair_can_be_turned_off():
    _, questions, *_ = run("1) Explique a organizacao da cmpresa.", repair_text=False)
    assert questions[0].text == "Explique a organizacao da cmpresa."
    _, repaired, *_ = run("1) Explique a organizacao da cmpresa.")
    assert repaired[0].text == "Explique a organização da empresa."


# --- instruções e cotação ------------------------------------------------------------------------------------

def test_instruction_lines_are_joined_until_the_sentence_ends():
    blocks = [
        block("PROVA DE EXAME DE MATEMÁTICA", 40),
        block("Leia a prova com atenção e responda de", 80),
        block("uma forma clara.", 108),
        block("1) Explique o conceito de lote económico.", 160),
    ]
    metadata, *_ = OCRPostprocessor().process(blocks)
    assert metadata.instructions.value == "Leia a prova com atenção e responda de uma forma clara."


def test_scores_found_on_the_paper_are_kept_and_no_warning_is_raised():
    metadata, questions, _, _, warnings = run("1) Explique o conceito de lote económico. (3 valores)", "2) Calcule. (2 valores)")
    assert [q.cotacao for q in questions] == [3.0, 2.0]
    assert metadata.total_max_score.value == 5.0
    assert not any(w.code == "NO_COTACAO" for w in warnings)


def test_a_composite_question_sums_the_scores_of_its_alineas():
    _, questions, *_ = run("1) Resolva.", "a) Calcule 2 + 2. (2 valores)", "b) Calcule 3 + 3. (3 valores)")
    q = questions[0]
    assert q.question_type == QuestionType.COMPOSTA
    assert [s.cotacao for s in q.subitems_content] == [2.0, 3.0]
    assert q.cotacao == 5.0

"""Duas provas sintéticas, lidas pelo motor real (PaddleOCR) e guardadas como blocos crus.

Servem para o que a prova de OGI não cobre: opções na mesma linha ("A)14 B)20 C)24 D)9", sem espaço depois do
parêntese), linhas de opções em que o motor põe um bloco dois pixéis abaixo do vizinho, cotações na prova e uma
V/F com caixas "( )" vazias. ``engine_mc_blocks.json`` e ``engine_vf_blocks.json`` saem de renderizar um HTML
e correr o motor; o interpretador é o que se testa.
"""

import json
from pathlib import Path

import pytest

from app.ocr.postprocessing import OCRPostprocessor, QuestionType, TextBlock

FIXTURES = Path(__file__).parent / "fixtures"


def process(name):
    blocks = [
        TextBlock(text=b["text"], confidence=b["conf"], bbox=tuple(b["bbox"]), line_index=b["i"])
        for b in json.loads((FIXTURES / name).read_text(encoding="utf-8"))
    ]
    return OCRPostprocessor().process(blocks)


@pytest.fixture(scope="module")
def mc():
    return process("engine_mc_blocks.json")


@pytest.fixture(scope="module")
def vf():
    return process("engine_vf_blocks.json")


def test_options_on_one_line_become_options(mc):
    _, questions, *_ = mc
    q1 = questions[0]
    assert q1.question_type == QuestionType.MULTIPLA_ESCOLHA
    assert q1.text == "Qual é o valor de 2 + 3 x 4?"
    assert [(o.option_label, o.option_text) for o in q1.options] == [("A", "14"), ("B", "20"), ("C", "24"), ("D", "9")]


def test_an_options_row_split_into_blocks_keeps_a_b_c_d_order(mc):
    # O motor devolve "A) 7" dois píxeis abaixo de "B)8 C) 9": a ordem de leitura é por linhas.
    _, questions, *_ = mc
    q3 = questions[2]
    assert q3.question_type == QuestionType.MULTIPLA_ESCOLHA
    assert q3.text == "Assinale a alternativa correcta: a raiz quadrada de 81 é"
    assert [(o.option_label, o.option_text) for o in q3.options] == [("A", "7"), ("B", "8"), ("C", "9"), ("D", "10")]


def test_scores_printed_on_the_paper_are_read_and_summed(mc):
    metadata, questions, _, _, warnings = mc
    assert [q.cotacao for q in questions] == [2.0, 3.0, 2.0, 5.0, 3.0]
    assert metadata.total_max_score.value == 15.0
    assert not any(w.code == "NO_COTACAO" for w in warnings)


def test_open_and_composite_questions_in_the_same_paper(mc):
    _, questions, *_ = mc
    assert [q.question_type for q in questions] == [
        QuestionType.MULTIPLA_ESCOLHA,
        QuestionType.DISSERTATIVA,
        QuestionType.MULTIPLA_ESCOLHA,
        QuestionType.COMPOSTA,
        QuestionType.DISSERTATIVA,
    ]
    assert [(s.label, s.text) for s in questions[3].subitems_content] == [
        ("a", "Determine os zeros de f."), ("b", "Calcule f(3)."), ("c", "Esboce o gráfico de f."),
    ]


def test_metadata_with_accents_lost_by_the_engine(mc):
    metadata, *_ = mc
    assert metadata.subject_name.value == "Matemática"
    assert metadata.duration_minutes.value == 120
    assert metadata.variant.value == "A"
    assert metadata.class_grade.value == "12"


def test_true_false_with_empty_boxes_and_a_class_read_with_an_at_sign(vf):
    metadata, questions, *_ = vf
    # "10ª Classe" saiu como "10@ Classe".
    assert metadata.class_grade.value == "10"
    assert metadata.subject_name.value == "História"
    q1 = questions[0]
    assert q1.question_type == QuestionType.VERDADEIRO_FALSO
    assert [s.text for s in q1.subitems_content] == [
        "Angola tornou-se independente em 1975.",
        "A capital de Angola é Benguela.",
        "O rio Cuanza desagua no Atlântico.",
    ]
    assert [q.question_type for q in questions] == [
        QuestionType.VERDADEIRO_FALSO, QuestionType.DISSERTATIVA, QuestionType.COMPOSTA,
    ]

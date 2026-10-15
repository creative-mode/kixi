"""Prova real de OGI (12.ª classe, série B), fotografada: o texto cru do PaddleOCR até à estrutura final.

``fixtures/ogi_12b_blocks.json`` são os blocos que o motor devolveu para ``fixtures/ogi_12b.png``. O motor lê bem
(todas as linhas com confiança ≥ 0,9); o que se testa aqui é o interpretador que vem a seguir. Antes desta
mudança saíam 3 questões (a 1 e a 2 perdiam-se por não terem ``)``), a V/F era "múltipla escolha" com uma opção solta,
e a disciplina era "Educação".
"""

import json
from pathlib import Path

import pytest

from app.ocr.postprocessing import OCRPostprocessor, QuestionType, TextBlock

FIXTURE = Path(__file__).parent / "fixtures" / "ogi_12b_blocks.json"


@pytest.fixture(scope="module")
def result():
    blocks = [
        TextBlock(text=b["text"], confidence=b["conf"], bbox=tuple(b["bbox"]), page_index=0, line_index=b["i"])
        for b in json.loads(FIXTURE.read_text(encoding="utf-8"))
    ]
    return OCRPostprocessor().process(blocks, page_dimensions={0: (1280, 1280)})


def test_all_six_questions_are_found_in_order(result):
    _, questions, *_ = result
    assert [q.number for q in questions] == ["1", "2", "3", "4", "5", "6"]


def test_question_types_are_inferred(result):
    _, questions, *_ = result
    assert [q.question_type for q in questions] == [
        QuestionType.DISSERTATIVA,
        QuestionType.DISSERTATIVA,
        QuestionType.VERDADEIRO_FALSO,
        QuestionType.DISSERTATIVA,
        QuestionType.COMPOSTA,
        QuestionType.DISSERTATIVA,
    ]
    assert all(q.options is None for q in questions)


def test_numbers_without_punctuation_are_questions_and_decimals_are_not(result):
    _, questions, *_ = result
    assert questions[0].text.startswith("O que consiste o aprovisionamento")
    assert questions[1].text.startswith("O que entendes por ciclo de vida do produto")
    # "2.500 unidades" and "1,8%" stay inside question 6; they are not question numbers.
    assert questions[5].text.startswith("Determinada peça é consumida em 2.500 unidades")
    assert "1,8%" in questions[5].text


def test_true_false_question_has_the_statement_and_four_clean_alineas(result):
    _, questions, *_ = result
    q3 = questions[2]
    assert q3.text == (
        "Para cada uma das questões abaixo, diga quais são verdadeiras (assinalar com V) "
        "ou falsas (assinalar com F):"
    )
    assert q3.subitems == ["a)", "b)", "c)", "d)"]
    assert [(s.label, s.type) for s in q3.subitems_content] == [
        ("a", "verdadeiro_falso"), ("b", "verdadeiro_falso"), ("c", "verdadeiro_falso"), ("d", "verdadeiro_falso"),
    ]
    assert [s.text for s in q3.subitems_content] == [
        "Deve ser preocupação da organização comprar ao melhor preço possível estabelecido pelo produtor.",
        "A escolha das matérias a utilizar só deve ser feita após a determinação das possíveis aplicações "
        "que se irão fazer com os insumos.",
        "A escolha das matérias tem de ter em conta as qualidades do tratamento térmico que se pretende "
        "que produto final possua.",
        "Ao comprar as matérias a empresa deve ainda ter em atenção se o tipo adquirido esta adaptado "
        "ao processo de laboração na empresa.",
    ]


def test_composite_question_keeps_the_shared_statement_and_two_alineas(result):
    _, questions, *_ = result
    q5 = questions[4]
    assert q5.text == (
        "Uma empresa utiliza o Lote Económico, para repor o estoque de uma das suas peças cujo o Lote previsto "
        "de 6000 unidades diário. Se o custo de colocação de um pedido é de 4 mil kz e a demanda a ser utilizado "
        "é de 90 mil unidades mensal."
    )
    assert [(s.label, s.type) for s in q5.subitems_content] == [("a", "dissertativa"), ("b", "dissertativa")]
    assert q5.subitems_content[0].text.startswith("Demostre a expressão matemática para o calculo do custo")
    assert q5.subitems_content[1].text == "Calcule do custo de manutenção de estoques anual e mensal?"


def test_question_six_does_not_absorb_the_handwriting(result):
    _, questions, *_ = result
    assert questions[5].text.endswith("estoque de segurança é de 400 unidades?")
    assert "M=31D" not in questions[4].text + questions[5].text
    assert "Pz" not in questions[5].text


def test_text_is_clean_and_accented(result):
    _, questions, *_ = result
    everything = " ".join(q.text + " ".join(s.text for s in q.subitems_content) for q in questions)
    for wrong in ("organizacao", "atencao", "cmpresa", "questocs", "prcocupacao", "pecas", "colocacao", "possivel"):
        assert wrong not in everything
    for right in ("organização", "atenção", "empresa", "questões", "preocupação", "peças", "colocação", "possível"):
        assert right in everything


def test_header_metadata(result):
    metadata, *_ = result
    assert metadata.duration_minutes.value == 90
    assert metadata.subject_name.value == "OGI"
    assert metadata.variant.value == "B"
    assert metadata.school_year.value == "2024/2025"
    assert metadata.class_grade.value == "12"
    assert metadata.course_name.value == "TODOS"
    assert metadata.exam_type.value == "Prova de Exame"
    assert metadata.title.value == "Prova de Exame OGI 12ª Classe - Série B - 2024/2025"
    assert metadata.instructions.value == (
        "Leia a prova com atenção, coloque na folha de prova a variante correspondente "
        "e responda de uma forma clara."
    )


def test_a_paper_without_scores_invents_none_and_says_so(result):
    metadata, questions, _, _, warnings = result
    assert metadata.total_max_score.value is None
    assert all(q.cotacao is None for q in questions)
    no_cotacao = [w for w in warnings if w.code == "NO_COTACAO"]
    assert len(no_cotacao) == 1 and "6 questões" in no_cotacao[0].message


def test_handwriting_and_the_introduction_are_reported_as_unmapped(result):
    *_, unmapped, _ = result
    texts = {u.text for u in unmapped}
    assert {"81", "4181", "M=31D"} <= texts
    assert any(u.text.startswith("Pz (D.Tr)") for u in unmapped)
    intro = [u for u in unmapped if u.reason and "introdutório" in u.reason]
    assert len(intro) == 2 and intro[0].text.startswith('"Quando lidamos com uma empresa')
    assert all(u.reason for u in unmapped)


def test_json_contract_for_the_new_types(result):
    _, questions, *_ = result
    q3 = questions[2].to_dict()
    assert q3["type"] == "verdadeiro_falso"
    assert q3["subitems"] == ["a)", "b)", "c)", "d)"]
    assert q3["subitemsContent"][0] == {
        "label": "a",
        "text": "Deve ser preocupação da organização comprar ao melhor preço possível estabelecido pelo produtor.",
        "cotacao": None,
        "type": "verdadeiro_falso",
    }
    assert q3["options"] is None
    assert questions[4].to_dict()["type"] == "composta"

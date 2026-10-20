"""Reconhecimento de fórmulas em texto já lido pelo OCR (``app/ocr/formulas.py``, #262).

As expressões testadas aqui vêm, palavra por palavra, da prova de Física de referência
(``tests/fixtures/fisica_12a.json``) citada na própria issue: "8π", "5 × 10⁻³ T", "B =
Bmax e⁻²ᵗ" e o enunciado do vector (que o OCR não consegue mesmo ler e tem de continuar
"unrecognized").
"""

import json
from pathlib import Path

from app.ocr.formulas import FORMULA, UNRECOGNIZED, extract_inline_formulas, recognize_region
from app.ocr.layout import LayoutRegion
from app.ocr.postprocessing import OCRPostprocessor, TextBlock, formula_prediction_key

FIXTURES = Path(__file__).parent / "fixtures"


def test_a_coefficient_glued_to_a_greek_letter_is_recognized():
    text, segments = extract_inline_formulas("Uma espira circular de 8π cm de perímetro")
    assert text == r"Uma espira circular de $8\pi$ cm de perímetro"
    assert [s.kind for s in segments] == [FORMULA]
    assert segments[0].latex == r"8\pi"
    assert segments[0].raw == "8π"


def test_scientific_notation_with_the_times_sign_and_a_superscript_exponent():
    text, segments = extract_inline_formulas("a indução magnética vale 5 × 10-³ T.")
    assert text == r"a indução magnética vale $5 \times 10^{-3}$ T."
    assert segments[0].latex == r"5 \times 10^{-3}"
    assert segments[0].kind == FORMULA


def test_a_physics_subscript_word_glued_to_a_variable_and_an_ascii_exponent():
    # "Bmaxe-2t" tal como a prova de referência traz: o índice "max" colado à variável, e
    # o expoente "-2t" sem nenhuma formatação, ambos colados entre si.
    text, segments = extract_inline_formulas("a expressão B = Bmaxe-2t, onde α é uma constante")
    assert text == r"a expressão B = $B_{max}$$e^{-2t}$, onde $\alpha$ é uma constante"
    raws = {s.raw for s in segments}
    assert raws == {"Bmax", "e-2t", "α"}
    assert all(s.kind == FORMULA for s in segments)


def test_a_parenthesized_expression_with_a_superscript_exponent():
    text, segments = extract_inline_formulas("Para o intervalo [2; 4]s, calcule (t + 1)² passos")
    assert "$(t + 1)^{2}$" in text
    assert segments[-1].raw == "(t + 1)²"


def test_a_garbled_vector_sentence_only_recognizes_the_clean_sub_expression():
    # A frase inteira ("† = (1 = t)ex + (t + 1)²y...") é demasiado corrompida para se
    # perceber como um todo (o "†" devia ser uma seta de vector) - mas o pedaço limpo lá
    # dentro, "(t + 1)²", continua a sair reconhecido, e o resto fica como estava.
    raw = "Seja † = (1 = t)ex + (t + 1)²y, no SI, o vector de posição da partícula"
    text, segments = extract_inline_formulas(raw)
    assert "†" in text  # não se mexeu no que não se percebeu
    assert len(segments) == 1
    assert segments[0].raw == "(t + 1)²"
    assert segments[0].latex == "(t + 1)^{2}"


def test_a_sentence_with_no_maths_is_left_untouched():
    text, segments = extract_inline_formulas("Apresente os teus conhecimentos sobre fenómenos.")
    assert text == "Apresente os teus conhecimentos sobre fenómenos."
    assert segments == []


def test_a_combining_vector_arrow_is_recognized_when_the_ocr_preserved_it():
    text, segments = extract_inline_formulas("o vector v⃗ aponta para cima")
    assert text == r"o vector $\vec{v}$ aponta para cima"
    assert segments[0].kind == FORMULA


# -- recognize_region: usado quando o modelo de layout já disse que uma região inteira é
# uma fórmula impressa (ver `_mask_formulas` em postprocessing.py). Aqui "unrecognized"
# significa mais do que "não achei nada": significa "sei que isto é uma fórmula e não
# consegui lê-la com confiança".

def test_recognize_region_reads_a_full_expression_with_confidence():
    result = recognize_region("B = Bmaxe-2t")
    assert result.kind == FORMULA
    assert result.latex == r"B = B_{max}e^{-2t}"
    assert result.confidence > 0.5


def test_recognize_region_flags_a_garbled_vector_equation_as_unrecognized():
    result = recognize_region("† = (1 = t)ex + (t + 1)²y")
    assert result.kind == UNRECOGNIZED
    assert result.latex is None
    # o texto em bruto continua disponível - é o resultado auditável quando não há LaTeX.
    assert result.raw == "† = (1 = t)ex + (t + 1)²y"


def test_recognize_region_refuses_text_that_already_contains_latex_syntax():
    # Visto em provas reais: um recorte de fórmula lido como lixo que já trazia "$" e
    # "{}" por conta própria. Não se tenta dar sentido a sintaxe de LaTeX que não é nossa.
    result = recognize_region("1+x{3}$ *e3x *(senx)4 *√x4 $y=1 -x^{2}$")
    assert result.kind == UNRECOGNIZED
    assert result.latex is None


def test_recognize_region_refuses_a_mostly_unrecognized_region():
    # Só um fragmento pequeno bate com um padrão conhecido - o resto é texto comum, não
    # uma fórmula que se conseguiu ler na maior parte.
    result = recognize_region("este é um parágrafo inteiro de texto comum com (x)² lá dentro")
    assert result.kind == UNRECOGNIZED


def test_recognize_region_on_empty_text_is_unrecognized_not_an_error():
    result = recognize_region("   ")
    assert result.kind == UNRECOGNIZED
    assert result.confidence == 0.0


def test_image_model_prediction_replaces_garbled_formula_but_requires_review():
    region = LayoutRegion("formula", "formula", 0.94, (100, 100, 300, 160), False)
    block = TextBlock("† = lixo", 0.62, (105, 105, 295, 155), page_index=0)
    latex = r"\vec{r}(t)=(1-t)\vec{e}_x+(t+1)^2\vec{e}_y"

    masked = OCRPostprocessor()._mask_formulas(
        [block],
        {0: [region]},
        recognize=True,
        formula_predictions={formula_prediction_key(0, region.bbox): latex},
    )

    assert masked[0].text == f"${latex}$"
    assert masked[0].formula_segments[0].latex == latex
    assert masked[0].formula_segments[0].raw == "† = lixo"
    assert masked[0].formula_segments[0].confidence == 0.79


def test_image_model_prediction_creates_a_formula_block_when_text_ocr_saw_nothing():
    region = LayoutRegion("formula", "formula", 0.91, (80, 120, 260, 180), False)
    latex = r"\frac{v_0^2\sin(2\theta)}{g}"

    masked = OCRPostprocessor()._mask_formulas(
        [],
        {0: [region]},
        recognize=True,
        formula_predictions={formula_prediction_key(0, region.bbox): latex},
    )

    assert len(masked) == 1
    assert masked[0].text == f"${latex}$"
    assert masked[0].bbox == region.bbox
    assert masked[0].formula_segments[0].raw == latex
    assert masked[0].formula_segments[0].ocr_confidence is None


def test_the_reference_physics_exam_keeps_its_formulas_legible():
    """Critério de aceitação da issue: a prova de referência mantém fórmulas legíveis."""
    data = json.loads((FIXTURES / "fisica_12a.json").read_text(encoding="utf-8"))
    full_text = " ".join(block["text"] for block in data["blocks"])

    _, segments = extract_inline_formulas(full_text)
    recognized_raw = {s.raw for s in segments if s.kind == FORMULA}

    # As quatro expressões que a própria issue cita como exemplo de "texto corrompido".
    assert "8π" in recognized_raw
    assert any(s.latex == r"5 \times 10^{-3}" for s in segments)
    assert "Bmax" in recognized_raw
    assert "e-2t" in recognized_raw
    assert "α" in recognized_raw

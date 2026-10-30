"""Reading a student's answers from lines the engine already read (no engine, no images)."""

from app.ocr.answer_sheet import extract_sheets, group_sheets, read_header
from app.ocr.postprocessing import TextBlock

STRUCTURE = {"questions": [
    {"id": "5", "number": 1, "type": "multiple_choice", "options": ["A", "B", "C", "D"]},
    {"id": "6", "number": 2, "type": "true_false"},
    {"id": "7", "number": 3, "type": "open"},
]}


def line(text, y, confidence=0.95, handwritten=False):
    return TextBlock(text=text, confidence=confidence, bbox=(10, y, 300, y + 20), handwritten=handwritten)


def first_sheet(blocks):
    return extract_sheets([(0, blocks)], STRUCTURE)["sheets"][0]


def answer(sheet, number):
    found = next(a for a in sheet["answers"] if a["number"] == number)
    return found.get("selected") or found.get("text")


def test_header_reads_number_and_name_in_the_usual_spellings():
    for text in ("Nº 3", "N.º 3", "N° 3", "No: 3", "Número 3"):
        assert read_header([line(text, 10)])["number"]["value"] == 3, text
    assert read_header([line("Nome: Ana Domingos", 10)])["name"]["value"] == "Ana Domingos"


def test_reads_a_multiple_choice_letter_in_the_forms_students_write_it():
    for text, letter in (("1) B", "B"), ("1. b", "B"), ("1 - (C)", "C"), ("Questão 1: D", "D"), ("1) A.", "A")):
        sheet = first_sheet([line("Nº 1", 10), line(text, 60)])
        assert answer(sheet, 1)["value"] == letter, text


def test_a_letter_outside_the_options_is_not_read_as_an_answer():
    sheet = first_sheet([line("Nº 1", 10), line("1) E", 60)])
    assert answer(sheet, 1)["value"] is None
    assert any(w["code"] == "NOT_FOUND" and w["field"] == "sheets[0].answers[1]" for w in extract_sheets([(0, [line("Nº 1", 10), line("1) E", 60)])], STRUCTURE)["warnings"])


def test_true_false_accepts_the_words_and_the_initials():
    for text, value in (("2) V", "V"), ("2. Falso", "F"), ("2 - verdadeiro", "V"), ("2) f", "F")):
        sheet = first_sheet([line("Nº 1", 10), line(text, 100)])
        assert answer(sheet, 2)["value"] == value, text


def test_two_different_marks_under_one_question_are_flagged_not_chosen():
    result = extract_sheets([(0, [line("Nº 1", 10), line("1) B", 60), line("A", 80)])], STRUCTURE)
    assert any(w["code"] == "AMBIGUOUS" for w in result["warnings"])
    assert result["sheets"][0]["answers"][0]["selected"]["confidence"] <= 0.5


def test_open_answer_joins_its_lines_and_prefers_the_pen_over_print():
    blocks = [line("Nº 1", 10), line("3 - O quadrado da", 200, 0.8, True), line("hipotenusa", 230, 0.8, True),
              line("Escreva a sua resposta abaixo", 260, 0.99, False)]
    value = answer(first_sheet(blocks), 3)
    assert value["value"] == "O quadrado da hipotenusa"
    assert value["confidence"] == 0.8


def test_low_confidence_is_reported_with_the_exact_field():
    result = extract_sheets([(0, [line("Nº 1", 10), line("1) B", 60, 0.6)])], STRUCTURE)
    assert {"code": "LOW_CONFIDENCE", "field": "sheets[0].answers[1]", "confidence": 0.6} in result["warnings"]


def test_a_missing_answer_is_not_zero_it_is_not_found():
    sheet = first_sheet([line("Nº 1", 10), line("1) B", 60)])
    assert answer(sheet, 3) == {"value": None, "confidence": 0.0}


def test_an_answer_number_inside_an_open_text_does_not_reopen_an_earlier_question():
    blocks = [line("Nº 1", 10), line("1) A", 60), line("3 - Primeiro", 100, 0.9, True),
              line("2. segundo ponto da resposta", 130, 0.9, True)]
    sheet = first_sheet(blocks)
    assert answer(sheet, 2)["value"] is None
    assert "segundo ponto" in answer(sheet, 3)["value"]


def test_a_page_with_a_header_opens_a_sheet_and_one_without_continues_it():
    pages = [(0, [line("Nome: Ana", 10), line("1) A", 60)]),
             (1, [line("3 - continua na folha", 40, 0.9, True)]),
             (2, [line("Nome: Rui", 10), line("1) B", 60)])]
    assert [[p[0] for p in s] for s in group_sheets(pages)] == [[0, 1], [2]]
    result = extract_sheets(pages, STRUCTURE)
    assert result["sheets"][0]["pageIndexes"] == [0, 1]
    assert result["sheets"][0]["answers"][2]["text"]["value"] == "continua na folha"


def test_a_sheet_without_a_header_warns_that_the_student_is_unknown():
    result = extract_sheets([(0, [line("1) A", 60)])], STRUCTURE)
    assert any(w["code"] == "STUDENT_NOT_IDENTIFIED" for w in result["warnings"])

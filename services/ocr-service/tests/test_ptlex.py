"""Reparação do texto do OCR com o léxico português (``app/ocr/ptlex.py``)."""

from pathlib import Path

import pytest

from app.ocr.ptlex import Lexicon, load_lexicon, repair_text, unaccent


@pytest.fixture(scope="module")
def lex() -> Lexicon:
    lexicon = load_lexicon()
    assert lexicon is not None, "o ficheiro data/pt_words.tsv.gz tem de existir"
    return lexicon


def fix(text: str, lex: Lexicon, line_start: bool = True) -> str:
    return repair_text(text, lex, line_start)


@pytest.mark.parametrize("raw,expected", [
    ("organizacao", "organização"),
    ("atencao", "atenção"),
    ("pecas", "peças"),
    ("questocs", "questões"),
    ("funcöes", "funções"),
    ("matemätica", "matemática"),
    ("sao", "são"),
])
def test_accents_are_restored(raw, expected, lex):
    assert fix(f"a {raw} b", lex) == f"a {expected} b"


@pytest.mark.parametrize("raw,expected", [
    ("cmpresa", "empresa"),
    ("fundarmental", "fundamental"),
    ("prcocupacao", "preocupação"),
    ("quc entendes", "que entendes"),
    ("urma empresa", "uma empresa"),
    ("lsso", "isso"),
])
def test_one_letter_ocr_slips_are_fixed(raw, expected, lex):
    assert fix(f"é {raw} grande", lex).startswith(f"é {expected}")


def test_words_already_in_the_lexicon_are_never_changed(lex):
    assert fix("Esta é a nossa casa e o pai pode ir", lex) == "Esta é a nossa casa e o pai pode ir"


def test_valid_words_the_lexicon_lacks_are_not_corrupted(lex):
    # "possua" (verbo válido) não é "possa" nem "pos sua"; "mensalmente" não é "mensal mente".
    assert fix("que o produto final possua tudo mensalmente", lex) == "que o produto final possua tudo mensalmente"
    assert fix("aprovisionamento insumos esquematize", lex) == "aprovisionamento insumos esquematize"


def test_a_function_word_opening_a_lowercase_word_is_not_a_glue(lex):
    # "Determine" não é "De termine"; "emprego" não é "em prego"; "ascender" não é "as cender".
    assert fix("Determine o valor", lex) == "Determine o valor"
    assert fix("o emprego e ascender", lex) == "o emprego e ascender"


def test_numbers_codes_acronyms_and_proper_names_are_untouched(lex):
    text = "Em 2.500 unidades, 1,8% do OGI em LUANDA (D.Tr) x2 kg"
    assert fix(text, lex) == text


def test_glued_words_are_split(lex):
    assert fix("REPÜBLICADEANGOLA", lex) == "REPÚBLICA DE ANGOLA"
    assert fix("PROVADEEXAMEDEOGI", lex) == "PROVA DE EXAME DE OGI"
    assert fix("DEPARTAMENTODEEDUCACAO E ENSINO", lex) == "DEPARTAMENTO DE EDUCAÇÃO E ENSINO"
    assert fix("todasas funcöes", lex) == "todas as funções"
    assert fix("estoque de segurancaéde 400 unidades?", lex) == "estoque de segurança é de 400 unidades?"
    assert fix("pecaé consumida", lex) == "peça é consumida"


def test_an_unknown_acronym_only_follows_a_preposition(lex):
    assert fix("PROVADEEXAMEDEOGI", lex).endswith("DE OGI")
    # "LDE" não é uma sigla: sem preposição antes não se inventa uma separação.
    assert "LDE" not in fix("GABINETE PROVINCIALDE EDUCACAO", lex)


def test_digits_and_case_boundaries(lex):
    assert fix("12aClasseAno Lectivo2024/2025", lex) == "12ª Classe Ano Lectivo 2024/2025"
    assert fix("4mil kze a demanda", lex) == "4 mil kz e a demanda"
    assert fix("assinalar comF):", lex) == "assinalar com F):"
    assert fix("diario.Se o custo", lex) == "diário. Se o custo"
    assert fix("CURSO:TODOS", lex) == "CURSO: TODOS"


def test_duration_with_letters_for_digits(lex):
    assert fix("Duracao9o Min.SérieB", lex) == "Duração: 90 Min. Série B"
    assert fix("Duração: 12o minutos", lex) == "Duração: 120 minutos"
    assert fix("Duração: oo Min", lex) == "Duração: 00 Min"


def test_a_lost_closing_parenthesis_in_the_true_false_idiom_is_restored(lex):
    assert "(assinalar com V)" in fix("verdadeiras (assinalar com V ou falsas", lex)


def test_lone_c_is_an_e_and_cm_without_a_number_is_em(lex):
    assert fix("Explique c esquematize", lex) == "Explique e esquematize"
    assert fix("ter cm conta as qualidades", lex) == "ter em conta as qualidades"
    assert fix("mede 5 cm de largura", lex) == "mede 5 cm de largura"


def test_a_stray_quote_is_dropped_but_a_real_quote_stays(lex):
    assert fix("previsto ‘ de 6000", lex) == "previsto de 6000"
    assert fix('"Quando lidamos com uma empresa"', lex) == '"Quando lidamos com uma empresa"'


def test_a_glued_alinea_label_at_line_start_is_left_for_the_parser(lex):
    assert fix("aDeve ser a empresa", lex).startswith("aDeve")
    assert fix("dAo comprar as matérias", lex).startswith("dAo")
    # no meio da linha já não é uma alínea: separa-se
    assert fix("assinalar comF", lex) == "assinalar com F"


def test_european_forms_win_over_brazilian_ones(lex):
    assert fix("Lote Economico", lex) == "Lote Económico"


def test_correct_portuguese_exam_text_is_never_altered(lex):
    """Português correto de provas (verbos de enunciado, nomes próprios, siglas, termos técnicos) sai igual.

    É o que mais custa se falhar: um reparador que "corrige" o que estava certo (Determine -> De termine,
    Complete -> Compete, concavidade -> con cavidade) estraga as provas que o OCR leu bem.
    """
    corpus = (Path(__file__).parent / "fixtures" / "pt_clean_corpus.txt").read_text(encoding="utf-8").splitlines()
    changed = [(line, fix(line, lex)) for line in corpus if line.strip() and fix(line, lex) != line]
    assert changed == []


def test_exam_command_verbs_in_every_form_are_known(lex):
    for word in ("calcule", "calculem", "complete", "completem", "comente", "determine", "demonstre",
                 "justifique", "esboce", "resolva", "simplifique", "factorize", "analise", "explique"):
        assert word in lex, word


def test_unaccent_drops_accents_and_cedilla():
    assert unaccent("organização") == "organizacao"
    assert unaccent("REPÜBLICA") == "REPUBLICA"

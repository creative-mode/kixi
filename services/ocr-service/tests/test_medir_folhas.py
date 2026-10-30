"""A ferramenta de medição de folhas reais (tools/medir_folhas.py, kixi#499): a lógica pura, sem rede."""

import importlib.util
from pathlib import Path

CAMINHO = Path(__file__).resolve().parents[1] / "tools" / "medir_folhas.py"
spec = importlib.util.spec_from_file_location("medir_folhas", CAMINHO)
medir = importlib.util.module_from_spec(spec)
spec.loader.exec_module(medir)


def folha(numero, nome, respostas):
    """Uma folha como a devolve o OCR: respostas = {n: (letra|None, confiança)}."""
    return {
        "student": {"number": {"value": numero, "confidence": 0.9}, "name": {"value": nome, "confidence": 0.9}},
        "answers": [{"number": n, "selected": {"value": v, "confidence": c}} for n, (v, c) in respostas.items()],
    }


def test_the_name_matches_on_first_and_last_name_ignoring_accents_and_case():
    assert medir.nome_certo("ANA  Domingos", "Ana Domingos")
    assert medir.nome_certo("Joao Kialunda", "João Manuel Kialunda")
    assert not medir.nome_certo("Ana Domingos", "Ana Dominguez")
    assert not medir.nome_certo(None, "Ana Domingos")


def test_each_objective_answer_is_right_unread_or_wrong_and_the_confident_wrong_is_the_silent_error():
    lida = folha(3, "Ana Domingos", {1: ("B", 0.97), 2: (None, 0.0), 3: ("A", 0.55), 4: ("D", 0.95)})
    esperado = {"numero": 3, "nome": "Ana Domingos", "objectivas": {1: "B", 2: "C", 3: "C", 4: "C"}}

    r = medir.avaliar_folha(lida, esperado)

    assert r["numero_certo"] and r["nome_certo"]
    assert r["numero_lido"] == 3 and r["nome_lido"] == "Ana Domingos"  # o relatório diz o que leu, para diagnosticar
    assert [o["estado"] for o in r["objectivas"]] == ["certa", "nao_lida", "errada_duvidosa", "errada_confiante"]


def test_leaving_it_blank_and_reading_nothing_is_right():
    lida = folha(1, "Rui", {1: (None, 0.0)})
    r = medir.avaliar_folha(lida, {"numero": 1, "nome": "Rui", "objectivas": {1: ""}})
    assert r["objectivas"][0]["estado"] == "certa"


def test_a_sheet_that_was_not_read_counts_every_answer_as_unread():
    r = medir.avaliar_folha({"answers": []}, {"numero": 2, "nome": "Rui Neto", "objectivas": {1: "A", 2: "B"}})
    assert not r["numero_certo"] and [o["estado"] for o in r["objectivas"]] == ["nao_lida", "nao_lida"]


def test_the_summary_separates_accuracy_from_silent_errors():
    certas = {"numero_certo": True, "nome_certo": True, "objectivas": [{"estado": "certa"}] * 8}
    mistas = {"numero_certo": False, "nome_certo": True, "objectivas": [{"estado": "nao_lida"}, {"estado": "errada_confiante"}]}

    s = medir.resumir([certas, mistas], {"LOW_CONFIDENCE": 2}, 10.0)

    assert s["objectivas"] == 10 and s["certa"] == 8 and s["nao_lida"] == 1 and s["errada_confiante"] == 1
    assert s["exatidao_objectivas"] == 0.8 and s["erros_silenciosos"] == 0.1
    assert s["exatidao_das_lidas"] == 8 / 9
    assert s["numero_certo"] == 0.5 and s["segundos_por_folha"] == 5.0


def test_the_answer_key_reads_question_columns_and_blanks(tmp_path):
    csv_ = tmp_path / "g.csv"
    csv_.write_text("ficheiro,numero,nome,q1,q2,q3\nf1.jpg,3,Ana Domingos,b,C,\n", encoding="utf-8")

    esperado = medir.ler_gabarito(csv_)["f1.jpg"]

    assert esperado == {"numero": 3, "nome": "Ana Domingos", "objectivas": {1: "B", 2: "C", 3: ""}}


def test_a_connection_reset_is_retried_and_then_reported_not_raised(monkeypatch, tmp_path):
    chamadas = []

    def falha(*args, **kwargs):
        chamadas.append(1)
        raise ConnectionResetError("reposta pelo servidor")

    monkeypatch.setattr(medir, "_pedir_uma_vez", falha)
    monkeypatch.setattr(medir.time, "sleep", lambda s: None)
    foto = tmp_path / "f.jpg"
    foto.write_bytes(b"x")

    try:
        medir.pedir_ocr("http://x", "", foto, {})
        assert False, "devia propagar depois das tentativas"
    except ConnectionResetError:
        pass

    assert len(chamadas) == 2

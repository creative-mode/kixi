"""A ferramenta de medição de folhas reais (tools/medir_folhas.py, kixi#499): a lógica pura, sem rede."""

import importlib.util
import json
import urllib.error
from pathlib import Path

import pytest

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
    assert r["objectivas"][0]["estado"] == "em_branco"


def test_blanks_left_blank_do_not_inflate_the_accuracy():
    certa = {"numero_certo": True, "nome_certo": True, "objectivas": [{"estado": "certa"}, {"estado": "em_branco"}, {"estado": "em_branco"}, {"estado": "errada_duvidosa"}]}

    s = medir.resumir([certa], {}, 1.0)

    assert s["em_branco"] == 2 and s["objectivas"] == 2 and s["exatidao_objectivas"] == 0.5


def test_a_blank_filename_quote_cannot_break_the_multipart_header(monkeypatch, tmp_path):
    visto = {}

    class Resposta:
        def __enter__(self):
            return self

        def __exit__(self, *a):
            return False

        def read(self, *a):
            return b"{}"

    def abrir(pedido, timeout):
        visto["corpo"] = pedido.data
        return Resposta()

    monkeypatch.setattr(medir.urllib.request, "urlopen", abrir)
    foto = tmp_path / 'a"b\r\nX.jpg'
    foto.write_bytes(b"x")

    medir._pedir_uma_vez("http://x", "", foto, {}, 1)

    assert b'filename="a%22bX.jpg"' in visto["corpo"]


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

    with pytest.raises(ConnectionResetError):
        medir.pedir_ocr("http://x", "", foto, {})

    assert len(chamadas) == 2


def test_the_answer_key_accepts_bom_semicolons_and_uppercase_columns(tmp_path):
    csv_ = tmp_path / "g.csv"
    csv_.write_text("ficheiro;numero;nome;Q1;Q2\nf1.jpg;7;Rui Neto;a;\n", encoding="utf-8-sig")

    assert medir.ler_gabarito(csv_)["f1.jpg"] == {"numero": 7, "nome": "Rui Neto", "objectivas": {1: "A", 2: ""}}


def test_a_dirty_answer_key_fails_with_the_line_and_the_reason(tmp_path):
    csv_ = tmp_path / "g.csv"
    csv_.write_text("ficheiro,numero,nome,q1\nf1.jpg,07a,Rui,A\n", encoding="utf-8")
    with pytest.raises(ValueError, match="linha 2"):
        medir.ler_gabarito(csv_)

    csv_.write_text("ficheiro,numero,nome\nf1.jpg,7,Rui\n", encoding="utf-8")
    with pytest.raises(ValueError, match="nenhuma coluna q1"):
        medir.ler_gabarito(csv_)


def test_what_the_ocr_reads_is_normalised_before_comparing():
    lida = folha("7", "Aluno 07", {1: ("b", 0.9)})
    r = medir.avaliar_folha(lida, {"numero": 7, "nome": "Aluno 7", "objectivas": {1: "B"}})
    assert r["numero_certo"] and r["nome_certo"] and r["objectivas"][0]["estado"] == "certa"


def test_the_upper_bound_shows_that_zero_errors_in_few_answers_does_not_prove_the_target():
    assert medir.limite_superior(0, 0) is None
    assert 0.01 < medir.limite_superior(0, 240) < 0.012
    assert medir.limite_superior(0, 400) < 0.01


def test_the_summary_handles_nothing_measured():
    s = medir.resumir([], {}, 0.0)
    assert s["folhas"] == 0 and s["exatidao_objectivas"] is None and s["exatidao_das_lidas"] is None
    assert s["erros_silenciosos_limite_superior"] is None


def test_a_client_error_is_not_retried_and_a_server_error_is(monkeypatch, tmp_path):
    chamadas = []

    def http(codigo):
        def falha(*args, **kwargs):
            chamadas.append(codigo)
            raise urllib.error.HTTPError("http://x", codigo, "erro", {}, None)
        return falha

    monkeypatch.setattr(medir.time, "sleep", lambda s: None)
    foto = tmp_path / "f.jpg"
    foto.write_bytes(b"x")

    monkeypatch.setattr(medir, "_pedir_uma_vez", http(401))
    with pytest.raises(medir.FalhaOcr, match="401"):
        medir.pedir_ocr("http://x", "", foto, {})
    assert chamadas == [401]

    chamadas.clear()
    monkeypatch.setattr(medir, "_pedir_uma_vez", http(503))
    with pytest.raises(urllib.error.HTTPError):
        medir.pedir_ocr("http://x", "", foto, {})
    assert chamadas == [503, 503]


def _executar(tmp_path, monkeypatch, respostas, extra=(), linhas=None, fotos=("f1.jpg",)):
    pasta = tmp_path / "fotos"
    pasta.mkdir()
    for nome in fotos:
        (pasta / nome).write_bytes(b"x")
    gabarito = tmp_path / "g.csv"
    gabarito.write_text("ficheiro,numero,nome,q1,q2\n" + (linhas or "f1.jpg,1,Rui Neto,A,B\n"), encoding="utf-8")
    estrutura = tmp_path / "e.json"
    estrutura.write_text(json.dumps({"questions": [{"number": 1}, {"number": 2}]}), encoding="utf-8")
    relatorio = tmp_path / "r.json"
    monkeypatch.setattr(medir, "pedir_ocr", lambda *a, **k: respostas.pop(0) if isinstance(respostas[0], dict) else (_ for _ in ()).throw(respostas.pop(0)))
    argv = ["--pasta", str(pasta), "--gabarito", str(gabarito), "--estrutura", str(estrutura), "--json", str(relatorio), *extra]
    return medir.main(argv), relatorio


def _boa():
    return {"sheets": [folha(1, "Rui Neto", {1: ("A", 0.95), 2: ("B", 0.95)})]}


def test_the_gate_passes_only_when_the_whole_definition_of_done_is_met(tmp_path, monkeypatch):
    codigo, relatorio = _executar(tmp_path, monkeypatch, [_boa()], extra=["--min-folhas", "1"])
    assert codigo == 0
    assert json.loads(relatorio.read_text(encoding="utf-8"))["meta"]["ficheiros"] == ["f1.jpg"]


def test_one_perfect_sheet_does_not_meet_the_default_minimum_of_sheets(tmp_path, monkeypatch, capsys):
    codigo, _ = _executar(tmp_path, monkeypatch, [_boa()])
    assert codigo == 1 and "menos de 30 folhas" in capsys.readouterr().out


def test_a_gabarito_row_without_a_photo_fails_the_gate(tmp_path, monkeypatch, capsys):
    codigo, relatorio = _executar(tmp_path, monkeypatch, [_boa()], extra=["--min-folhas", "1"], linhas="f1.jpg,1,Rui Neto,A,B\nf2.jpg,2,Ana,A,B\n")
    assert codigo == 1 and "sem foto" in capsys.readouterr().out
    assert json.loads(relatorio.read_text(encoding="utf-8"))["meta"]["sem_foto"] == ["f2.jpg"]


def test_the_student_number_is_part_of_the_gate(tmp_path, monkeypatch, capsys):
    errada = {"sheets": [folha(9, "Rui Neto", {1: ("A", 0.95), 2: ("B", 0.95)})]}
    codigo, _ = _executar(tmp_path, monkeypatch, [errada], extra=["--min-folhas", "1"])
    assert codigo == 1 and "nº de aluno" in capsys.readouterr().out


def test_a_bad_response_does_not_lose_the_report_and_is_listed_as_a_failure(tmp_path, monkeypatch, capsys):
    codigo, relatorio = _executar(tmp_path, monkeypatch, [ValueError("não é JSON")], extra=["--min-folhas", "1"])
    assert codigo == 1
    assert "não é JSON" in json.loads(relatorio.read_text(encoding="utf-8"))["meta"]["falhas"][0]


def test_two_sheets_in_one_file_raise_a_warning(tmp_path, monkeypatch):
    dupla = {"sheets": [folha(1, "Rui Neto", {1: ("A", 0.95), 2: ("B", 0.95)})] * 2, "warnings": [{}]}
    codigo, relatorio = _executar(tmp_path, monkeypatch, [dupla], extra=["--min-folhas", "1"])
    avisos = json.loads(relatorio.read_text(encoding="utf-8"))["resumo"]["avisos"]
    assert avisos["VARIAS_FOLHAS_NO_FICHEIRO"] == 1 and avisos["?"] == 1


def test_strict_mode_gates_on_the_upper_bound(tmp_path, monkeypatch, capsys):
    codigo, _ = _executar(tmp_path, monkeypatch, [_boa()], extra=["--min-folhas", "1", "--rigoroso"])
    assert codigo == 1 and "limite superior" in capsys.readouterr().out


def test_a_gabarito_question_missing_from_the_structure_is_a_usage_error(tmp_path, monkeypatch, capsys):
    pasta = tmp_path / "fotos"
    pasta.mkdir()
    (tmp_path / "g.csv").write_text("ficheiro,numero,nome,q1,q9\nf1.jpg,1,Rui,A,B\n", encoding="utf-8")
    (tmp_path / "e.json").write_text(json.dumps({"questions": [{"number": 1}]}), encoding="utf-8")

    codigo = medir.main(["--pasta", str(pasta), "--gabarito", str(tmp_path / "g.csv"), "--estrutura", str(tmp_path / "e.json")])

    assert codigo == 2 and "q[9]" in capsys.readouterr().err

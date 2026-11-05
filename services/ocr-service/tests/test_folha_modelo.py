"""A folha de resposta modelo (tools/folha_modelo.py, kixi#499): gera o PDF e recusa o que não cabe na página."""

import importlib.util
from pathlib import Path

import pytest

CAMINHO = Path(__file__).resolve().parents[1] / "tools" / "folha_modelo.py"
spec = importlib.util.spec_from_file_location("folha_modelo", CAMINHO)
modelo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(modelo)


def test_the_default_sheet_is_an_a4_page_that_can_be_saved_as_pdf(tmp_path):
    img = modelo.gerar(8, 2, 5, "Folha de resposta")
    assert img.size == (1240, 1754)

    saida = tmp_path / "folha.pdf"
    img.save(saida, "PDF", resolution=150.0)
    assert saida.read_bytes().startswith(b"%PDF")


def test_too_many_objective_answers_are_refused_instead_of_cut_off_silently():
    with pytest.raises(ValueError, match="objetivas não cabem"):
        modelo.gerar(200, 0, 5, "x")


def test_open_answers_that_would_run_past_the_page_are_refused():
    with pytest.raises(ValueError, match="abertas"):
        modelo.gerar(8, 10, 8, "x")

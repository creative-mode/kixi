from types import SimpleNamespace

import numpy as np

from app.ocr.formula_model import FormulaImageRecognizer, normalize_model_latex


def test_model_latex_is_bounded_and_rejects_active_commands(monkeypatch):
    assert normalize_model_latex(r"\[ \frac{x}{2} \]") == r"\frac{x}{2}"
    assert normalize_model_latex(r"\href{https://example.test}{x}") is None
    assert normalize_model_latex(r"\frac{x}{2") is None
    monkeypatch.setattr("app.ocr.formula_model.settings.ocr_formula_max_latex_chars", 16)
    assert normalize_model_latex("x" * 17) is None


def test_recognizer_returns_one_safe_result_per_crop(monkeypatch):
    class FakeModel:
        def predict(self, crops, batch_size):
            assert len(crops) == 2
            assert batch_size >= 1
            return [
                SimpleNamespace(json={"res": {"rec_formula": r"\frac{x}{2}"}}),
                SimpleNamespace(json={"res": {"rec_formula": r"\htmlStyle{color:red}{x}"}}),
            ]

    recognizer = FormulaImageRecognizer()
    monkeypatch.setattr(recognizer, "_get_model", lambda: FakeModel())
    crops = [np.zeros((20, 40, 3), dtype=np.uint8) for _ in range(2)]

    assert recognizer.recognize(crops) == [r"\frac{x}{2}", None]


def test_recognizer_fails_closed_when_model_cannot_run(monkeypatch):
    recognizer = FormulaImageRecognizer()
    monkeypatch.setattr(recognizer, "_get_model", lambda: (_ for _ in ()).throw(RuntimeError("offline")))

    assert recognizer.recognize([np.zeros((4, 4, 3), dtype=np.uint8)]) == [None]

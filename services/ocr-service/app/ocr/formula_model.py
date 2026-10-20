"""Lazy, local image-to-LaTeX recognition for printed formula crops.

The text OCR remains responsible for prose. This adapter is only called for regions
that PP-DocLayout classified as printed formulas, and it never sends an image outside
the OCR service.
"""

from __future__ import annotations

import re
import threading
from typing import List, Optional, Sequence

import numpy as np
import structlog

from app.config import settings

logger = structlog.get_logger(__name__)

FormulaRecognition = None


def _get_formula_recognition():
    global FormulaRecognition
    if FormulaRecognition is None:
        from paddleocr import FormulaRecognition as FormulaRecognitionClass

        FormulaRecognition = FormulaRecognitionClass
    return FormulaRecognition


_UNSAFE_TEX = re.compile(
    r"\\(?:href|url|includegraphics|htmlClass|htmlId|htmlStyle|htmlData|def|gdef|newcommand)\b",
    re.IGNORECASE,
)


def normalize_model_latex(value: object) -> Optional[str]:
    """Return a bounded KaTeX-safe expression, without display delimiters."""
    if not isinstance(value, str):
        return None
    latex = value.strip()
    if latex.startswith("\\[") and latex.endswith("\\]"):
        latex = latex[2:-2].strip()
    elif latex.startswith("$$") and latex.endswith("$$"):
        latex = latex[2:-2].strip()
    elif latex.startswith("$") and latex.endswith("$"):
        latex = latex[1:-1].strip()
    if not latex or len(latex) > settings.ocr_formula_max_latex_chars:
        return None
    if any(ord(char) < 32 and char not in "\t\n\r" for char in latex):
        return None
    if _UNSAFE_TEX.search(latex):
        return None
    depth = 0
    escaped = False
    for char in latex:
        if escaped:
            escaped = False
            continue
        if char == "\\":
            escaped = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth < 0:
                return None
    return latex if depth == 0 else None


class FormulaImageRecognizer:
    """Thread-safe wrapper around PaddleOCR FormulaRecognition."""

    def __init__(self) -> None:
        self._model = None
        self._lock = threading.Lock()

    def _get_model(self):
        if self._model is None:
            with self._lock:
                if self._model is None:
                    logger.info(
                        "Initializing formula recognition model",
                        model=settings.ocr_formula_model_name,
                        use_gpu=settings.ocr_use_gpu,
                    )
                    model_class = _get_formula_recognition()
                    self._model = model_class(
                        model_name=settings.ocr_formula_model_name,
                        device="gpu:0" if settings.ocr_use_gpu else "cpu",
                    )
        return self._model

    @staticmethod
    def _formula_from_result(result: object) -> Optional[str]:
        payload = getattr(result, "json", None)
        if isinstance(payload, dict):
            payload = payload.get("res", payload)
        if isinstance(payload, dict):
            return normalize_model_latex(payload.get("rec_formula"))
        if isinstance(result, dict):
            return normalize_model_latex(result.get("rec_formula"))
        return None

    def recognize(self, crops: Sequence[np.ndarray]) -> List[Optional[str]]:
        if not crops:
            return []
        try:
            results = self._get_model().predict(
                list(crops),
                batch_size=settings.ocr_formula_batch_size,
            )
            recognized = [self._formula_from_result(result) for result in results]
            if len(recognized) != len(crops):
                logger.warning(
                    "Formula recognizer returned a different result count",
                    expected=len(crops),
                    actual=len(recognized),
                )
                recognized.extend([None] * (len(crops) - len(recognized)))
            return recognized[: len(crops)]
        except Exception as exc:
            logger.error(
                "Formula recognition failed",
                error_type=type(exc).__name__,
                crop_count=len(crops),
            )
            return [None] * len(crops)

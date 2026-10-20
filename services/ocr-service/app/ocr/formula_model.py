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
_BARE_TEX_COMMAND = re.compile(
    r"(?<!\\)\b[A-Za-z]*(?:boxed|overrightarrow|overline|underline|frac|sqrt|times|alpha|beta|theta|pi)\b",
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
    # FormulaNet occasionally reads the arrow and command as padded fragments,
    # e.g. ``\\ \\ \\ {vec\\ r{}}``. The crop has already been classified as
    # a printed formula; recover only this constrained vector-command shape.
    latex = re.sub(
        r"(?:\\\s+)+\{vec\\\s*([A-Za-z])\{\}\}",
        lambda match: rf"\vec{{{match.group(1)}}}",
        latex,
    )
    if not latex or len(latex) > settings.ocr_formula_max_latex_chars:
        return None
    if any(ord(char) < 32 and char not in "\t\n\r" for char in latex):
        return None
    if _UNSAFE_TEX.search(latex):
        return None
    # FormulaNet sometimes drops the slash or merges a command into a variable
    # (for example ``Bboxed{...}`` or ``overrightarrow r``). Such output is
    # syntactically printable but semantically corrupt, so it must not reach the
    # exam statement as if it were valid LaTeX.
    if _BARE_TEX_COMMAND.search(latex):
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

    @staticmethod
    def reconcile_vector_prefix(primary: Optional[str], tight_crop: Optional[str]) -> Optional[str]:
        """Accept a tight-crop vector read when it independently resolves a crop artifact.

        FormulaNet can mistake a vector arrow for ``\\imath`` or ``\\mid``, or add
        a spurious ``\\boxed`` wrapper. Accept the tighter read only when it
        independently recognizes the same complete expression; otherwise retain
        the original prediction for review.
        """
        if not primary or not tight_crop:
            return primary
        primary = normalize_model_latex(primary)
        tight_crop = normalize_model_latex(tight_crop)
        if not primary or not tight_crop:
            return primary
        prefix = re.match(r"^\\imath(?=\\(?:vec|overrightarrow)\{[A-Za-z]\})", primary)
        boxed = re.match(r"^\\boxed\{(.+)\}$", primary)
        mid = re.match(r"^\\mid(?=\\(?:vec|overrightarrow)\{[A-Za-z]\})", primary)
        if not (prefix or boxed or mid) or re.match(r"^\\(?:imath|boxed|mid)", tight_crop):
            return primary

        def unvectorized_signature(value: str) -> str:
            value = re.sub(r"\\(?:overrightarrow|vec)\b", "", value)
            value = re.sub(r"\\(?:left|right)\b", "", value)
            return re.sub(r"\s+|[{}]", "", value)

        corrected = (
            primary[prefix.end():] if prefix
            else boxed.group(1) if boxed
            else primary[mid.end():]
        )
        if unvectorized_signature(corrected) == unvectorized_signature(tight_crop):
            return tight_crop if boxed else corrected
        return primary

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

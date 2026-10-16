"""
Layout detection: where the figures, tables and formulas of a photographed exam are.

The text lines say what was written; the layout says where the circuit, the graph, the table or the display
formula sits on the page. The postprocessor ties each of those regions to the question it belongs to by position.
"""

from dataclasses import dataclass
from typing import Any, List, Optional, Tuple

import numpy as np
import structlog

from app.config import settings

logger = structlog.get_logger(__name__)

# PP-DocLayout labels that become a picture of the question.
FIGURE_LABELS = {"image", "chart", "figure"}
TABLE_LABELS = {"table"}
FORMULA_LABELS = {"formula"}


@dataclass
class LayoutRegion:
    """A region found on the page. ``kind`` is figure, table or formula."""
    kind: str
    label: str
    score: float
    bbox: Tuple[int, int, int, int]  # x1, y1, x2, y2
    # In ink that is not print (a student's blue pen): kept out of the exam, not cropped as a figure.
    handwritten: bool = False

    @property
    def area(self) -> int:
        return max(0, self.bbox[2] - self.bbox[0]) * max(0, self.bbox[3] - self.bbox[1])


def kind_of(label: str) -> Optional[str]:
    if label in FIGURE_LABELS:
        return "figure"
    if label in TABLE_LABELS:
        return "table"
    if label in FORMULA_LABELS:
        return "formula"
    return None


def parse_layout_boxes(boxes: List[Any], min_score: float = 0.5) -> List[LayoutRegion]:
    """PaddleOCR LayoutDetection ``boxes`` -> regions worth keeping, in reading order (top to bottom)."""
    regions: List[LayoutRegion] = []
    for box in boxes or []:
        label = str(box.get("label", ""))
        kind = kind_of(label)
        if kind is None:
            continue
        score = float(box.get("score", 0.0))
        if score < min_score:
            continue
        x1, y1, x2, y2 = (int(round(float(v))) for v in box["coordinate"][:4])
        if x2 <= x1 or y2 <= y1:
            continue
        regions.append(LayoutRegion(kind=kind, label=label, score=score, bbox=(x1, y1, x2, y2)))
    regions.sort(key=lambda r: (r.bbox[1], r.bbox[0]))
    return regions


class LayoutDetector:
    """Loads the PaddleOCR layout model once, on first use."""

    def __init__(self, model_name: Optional[str] = None, use_gpu: bool = False, enable_mkldnn: bool = True):
        self.model_name = model_name or settings.ocr_layout_model_name
        self.use_gpu = use_gpu
        self.enable_mkldnn = enable_mkldnn
        self._model: Any = None

    def _load(self) -> Any:
        if self._model is None:
            from paddleocr import LayoutDetection

            self._model = LayoutDetection(
                model_name=self.model_name,
                device="gpu:0" if self.use_gpu else "cpu",
                enable_mkldnn=self.enable_mkldnn,
            )
            logger.info("Layout detector loaded", model=self.model_name)
        return self._model

    def detect(self, image: np.ndarray, min_score: Optional[float] = None) -> List[LayoutRegion]:
        model = self._load()
        results = model.predict(image, batch_size=1, layout_nms=True)
        boxes = results[0]["boxes"] if results else []
        return parse_layout_boxes(boxes, settings.ocr_layout_min_score if min_score is None else min_score)

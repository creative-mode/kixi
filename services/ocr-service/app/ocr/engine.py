"""
OCR Engine Module

Core OCR processing with PaddleOCR 3.x: PP-OCRv5 text lines plus PP-DocLayout regions (figures, tables,
formulas) that the postprocessor ties to the questions. PaddleOCR-VL is available as an opt-in pipeline.
"""

import asyncio
import multiprocessing
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field, replace
from typing import Optional, List, Dict, Any, Tuple, Callable, Iterable
import hashlib
import re
import uuid
import unicodedata

import cv2
import numpy as np
from PIL import Image

from app.config import settings
from app.ocr.preprocessing import (
    ImagePreprocessor,
    PreprocessingResult,
    load_image_from_bytes,
    load_image_from_pil,
)
from app.ocr.ink import is_handwriting
from app.ocr.layout import LayoutDetector, LayoutRegion
from app.ocr.formula_model import FormulaImageRecognizer, normalize_model_latex
from app.ocr.postprocessing import (
    OCRPostprocessor,
    TextBlock,
    ExtractedMetadata,
    ExtractedQuestion,
    ImageToUpload,
    UnmappedContent,
    Warning,
    detect_language,
    formula_prediction_key,
    FormulaPredictions,
)

import structlog

logger = structlog.get_logger(__name__)

# PaddleOCR is loaded lazily because model dependencies are expensive and the
# service must remain importable for unit tests and health endpoints.
PaddleOCR = None


def _get_paddle_ocr():
    """Load PaddleOCR lazily and expose a patchable seam for unit tests."""
    global PaddleOCR
    if PaddleOCR is None:
        from paddleocr import PaddleOCR as PaddleOCRClass

        PaddleOCR = PaddleOCRClass
    return PaddleOCR


@dataclass
class DocumentInfo:
    """Information about the processed document."""
    page_count: int
    main_language: str
    has_tables: bool


@dataclass
class PageOcrContext:
    """Internal OCR evidence needed to structure multi-page questions as one document."""
    page_index: int
    source_file_index: int
    text_blocks: List[TextBlock]
    layout_regions: Optional[List[LayoutRegion]]
    width: int
    height: int
    formula_predictions: FormulaPredictions = field(default_factory=dict)


@dataclass
class OCRResult:
    """Complete OCR extraction result."""
    status: str  # "success", "partial", "error"
    request_id: str
    processing_time_ms: int
    overall_confidence: float
    document: DocumentInfo
    metadata: ExtractedMetadata
    questions: List[ExtractedQuestion]
    images_to_upload: List[ImageToUpload]
    unmapped_content: List[UnmappedContent]
    warnings: List[Warning]
    error_message: Optional[str] = None
    # #263: quality/transformation metadata and the preprocessing stage's own cost, for the
    # first page (multi-page merges keep only this one - the metadata is mainly a signal for
    # "is this submission worth a retake photo", not something every page needs separately).
    preprocessing: Optional[Dict[str, Any]] = None
    timings: Optional[Dict[str, float]] = None
    page_context: Optional[PageOcrContext] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return {
            "status": self.status,
            "requestId": self.request_id,
            "processingTimeMs": self.processing_time_ms,
            "overallConfidence": round(self.overall_confidence, 3),
            **({"preprocessing": self.preprocessing} if self.preprocessing else {}),
            "document": {
                "pageCount": self.document.page_count,
                "mainLanguage": self.document.main_language,
                "hasTables": self.document.has_tables,
            },
            "metadata": self.metadata.to_dict(),
            "questions": [q.to_dict() for q in self.questions],
            "imagesToUpload": [
                {
                    "suggestedFilename": img.suggested_filename,
                    "description": img.description,
                    "region": img.region,
                    "pageIndex": img.page_index,
                    "bbox": list(img.bbox) if img.bbox else None,
                    "sourceWidth": img.source_width,
                    "sourceHeight": img.source_height,
                    "contractVersion": img.contract_version,
                    "sourceFileIndex": img.source_file_index,
                    **({"kind": img.kind} if img.kind else {}),
                    **({"score": img.score} if img.score is not None else {}),
                }
                for img in self.images_to_upload
            ],
            "unmappedContent": [u.to_dict() for u in self.unmapped_content],
            "warnings": [w.to_dict() for w in self.warnings],
            **({"timings": self.timings} if self.timings else {}),
            **({"errorMessage": self.error_message} if self.error_message else {}),
        }


class OCREngine:
    """
    Main OCR engine using PaddleOCR.

    Provides high-level methods for extracting text from images
    with preprocessing and postprocessing.
    """

    def __init__(
        self,
        lang: str = "pt",
        use_gpu: bool = False,
        use_angle_cls: bool = False,
        det_model_dir: Optional[str] = None,
        rec_model_dir: Optional[str] = None,
        cls_model_dir: Optional[str] = None,
        show_log: bool = False,
        det_model_name: Optional[str] = None,
        rec_model_name: Optional[str] = None,
    ):
        """
        Initialize the OCR engine.

        Args:
            lang: Primary language for OCR (pt, en, etc.)
            use_gpu: Whether to use GPU acceleration
            use_angle_cls: Whether to use angle classification for rotated text
            det_model_dir: Custom detection model directory
            rec_model_dir: Custom recognition model directory
            cls_model_dir: Custom classification model directory
            show_log: Whether to show PaddleOCR logs
        """
        self.lang = lang
        self.use_gpu = use_gpu
        self.use_angle_cls = use_angle_cls
        self.det_model_dir = det_model_dir
        self.rec_model_dir = rec_model_dir
        self.cls_model_dir = cls_model_dir
        self.show_log = show_log
        self.det_model_name = det_model_name or settings.ocr_det_model_name
        self.rec_model_name = rec_model_name or settings.ocr_rec_model_name

        self._ocr = None
        self._fallback_ocr = None
        self._vl = None
        self._layout = LayoutDetector(use_gpu=use_gpu, enable_mkldnn=settings.ocr_enable_mkldnn)
        self._formula_recognizer = FormulaImageRecognizer()
        self._initialized = False
        self._executor = ThreadPoolExecutor(max_workers=settings.workers)

        self.preprocessor = ImagePreprocessor(
            enable_deskew=settings.enable_deskew,
            enable_denoise=settings.enable_denoise,
            target_dpi=settings.target_dpi,
            min_confidence_threshold=settings.min_confidence_threshold,
            enable_sheet_detection=settings.enable_sheet_detection,
            enable_shadow_removal=settings.enable_shadow_removal,
            min_sharpness_score=settings.min_sharpness_score,
            min_brightness_score=settings.min_brightness_score,
            max_brightness_score=settings.max_brightness_score,
        )

        self.postprocessor = OCRPostprocessor(
            min_confidence_threshold=settings.min_confidence_threshold,
        )

    def _recognize_formula_regions(
        self,
        image: np.ndarray,
        regions: Optional[List[LayoutRegion]],
        request_id: str,
        page_index: int,
    ) -> FormulaPredictions:
        if not settings.ocr_formula_latex or not settings.ocr_formula_model_enabled or not regions:
            return {}
        candidates = [
            region for region in regions
            if region.kind == "formula" and not region.handwritten
            and region.score >= settings.ocr_layout_min_score
        ]
        crops = []
        tight_crops = []
        usable = []
        height, width = image.shape[:2]
        for region in candidates:
            raw_x1, raw_y1, raw_x2, raw_y2 = (int(round(value)) for value in region.bbox)
            if raw_x2 <= raw_x1 or raw_y2 <= raw_y1:
                continue
            tight_crops.append(image[raw_y1:raw_y2, raw_x1:raw_x2].copy())
            x1, y1, x2, y2 = raw_x1, raw_y1, raw_x2, raw_y2
            pad = max(4, int(round(min(max(1, x2 - x1), max(1, y2 - y1)) * 0.06)))
            x1, y1 = max(0, x1 - pad), max(0, y1 - pad)
            x2, y2 = min(width, x2 + pad), min(height, y2 + pad)
            if x2 <= x1 or y2 <= y1:
                continue
            crops.append(image[y1:y2, x1:x2].copy())
            usable.append(region)
        recognized = self._formula_recognizer.recognize(crops)
        retry_indices = [
            index for index, latex in enumerate(recognized)
            if latex and (
                re.match(r"^\\imath(?=\\(?:vec|overrightarrow)\{[A-Za-z]\})", latex)
                or re.match(r"^\\boxed\{.*\\(?:vec|overrightarrow)\{[A-Za-z]\}", latex)
                or re.match(r"^\\mid(?=\\(?:vec|overrightarrow)\{[A-Za-z]\})", latex)
            )
        ]
        if retry_indices:
            tight_predictions = self._formula_recognizer.recognize(
                [tight_crops[index] for index in retry_indices]
            )
            for index, tight_prediction in zip(retry_indices, tight_predictions):
                recognized[index] = FormulaImageRecognizer.reconcile_vector_prefix(
                    recognized[index],
                    tight_prediction,
                )
        predictions = {
            formula_prediction_key(page_index, region.bbox): latex
            for region, latex in zip(usable, recognized)
            if latex
        }
        logger.info(
            "Formula regions recognized",
            request_id=request_id,
            candidates=len(usable),
            recognized=len(predictions),
        )
        return predictions

    def _recognize_inline_vector_pairs(
        self,
        image: np.ndarray,
        text_blocks: List[TextBlock],
        request_id: str,
        page_index: int,
    ) -> Tuple[List[LayoutRegion], FormulaPredictions]:
        """Recover vector symbols that sit inline after a printed 'angle between vectors' phrase.

        The layout model often misses the small arrows above inline symbols. The text OCR still gives us a
        bounded line and the phrase that identifies the expected pair, so FormulaNet sees only the pixels after
        that phrase. A result is accepted only if it independently reads exactly ``v e a`` with vector marks;
        otherwise no symbols are synthesized.
        """
        if not settings.ocr_formula_latex or not settings.ocr_formula_model_enabled:
            return [], {}

        regions: List[LayoutRegion] = []
        crops: List[np.ndarray] = []
        height, width = image.shape[:2]
        for block in text_blocks:
            normalized = unicodedata.normalize("NFD", block.text.casefold())
            normalized = re.sub(r"[\u0300-\u036f]", "", normalized)
            phrase = re.search(r"\bangulo\b.*?\bentre\s+(?:os\s+)?(?:vectores?|vectors?)\b", normalized)
            if not phrase or block.page_index != page_index:
                continue

            vector_marker = re.search(r"\b(?:vectores?|vectors?)\b", normalized[phrase.start():phrase.end()])
            if not vector_marker:
                continue
            vector_end = phrase.start() + vector_marker.end()
            source_length = max(1, len(block.text))
            x1, y1, x2, y2 = (int(round(value)) for value in block.bbox)
            if x2 <= x1 or y2 <= y1:
                continue

            # Preserve the prose word and the detected line's reading order. The recognition crop gets a small
            # left pad; the synthetic region begins after that word so replacement cannot consume "vectores".
            replacement_index = vector_end
            while replacement_index < len(block.text) and block.text[replacement_index].isspace():
                replacement_index += 1
            region_x1 = x1 + round(replacement_index / source_length * (x2 - x1))
            vector_end_x = x1 + round(vector_end / source_length * (x2 - x1))
            recognition_x1 = max(x1, vector_end_x - max(4, round((y2 - y1) * 0.22)))
            recognition_x2 = min(x2, x1 + round(min(len(block.text), replacement_index + 8) / source_length * (x2 - x1)))
            vertical_pad = max(4, round((y2 - y1) * 0.3))
            crop_y1 = max(0, y1 - vertical_pad)
            crop_y2 = min(height, y2 + vertical_pad)
            recognition_x2 = max(recognition_x1 + 1, recognition_x2)
            crop = image[crop_y1:crop_y2, recognition_x1:recognition_x2]
            if crop.size == 0:
                continue

            crops.append(cv2.resize(crop, None, fx=3, fy=3, interpolation=cv2.INTER_CUBIC))
            regions.append(LayoutRegion(
                kind="formula",
                label="inline_vector_pair",
                score=0.55,
                bbox=(region_x1, y1, recognition_x2, y2),
            ))

        if not crops:
            return [], {}

        predictions: FormulaPredictions = {}
        accepted_regions: List[LayoutRegion] = []
        first_pass = self._formula_recognizer.recognize(crops)
        unresolved = [index for index, value in enumerate(first_pass) if not self._canonical_vector_pair(value)]
        threshold_crops = []
        for index in unresolved:
            crop = crops[index]
            gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
            binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[1]
            margin = min(binary.shape[1] - 1, max(1, round(binary.shape[0] * 0.08)))
            thresholded = cv2.cvtColor(binary[:, margin:], cv2.COLOR_GRAY2BGR)
            threshold_crops.append(thresholded)
        threshold_results = self._formula_recognizer.recognize(threshold_crops) if threshold_crops else []
        thresholded = dict(zip(unresolved, threshold_results))
        for index, (region, raw_latex) in enumerate(zip(regions, first_pass)):
            latex = self._canonical_vector_pair(raw_latex)
            if not latex and index in thresholded:
                latex = self._canonical_vector_pair(thresholded[index])
            if latex:
                predictions[formula_prediction_key(page_index, region.bbox)] = latex
                accepted_regions.append(region)

        logger.info(
            "Inline vector pairs recognized",
            request_id=request_id,
            candidates=len(regions),
            recognized=len(predictions),
        )
        return accepted_regions, predictions

    @staticmethod
    def _canonical_vector_pair(raw_latex: Optional[str]) -> Optional[str]:
        latex = normalize_model_latex(raw_latex)
        if not latex:
            return None
        latex = re.sub(r"\\\\\s*", " ", latex)
        latex = re.sub(r"\\[;,!:]\s*", " ", latex)
        latex = re.sub(r"\{\s*(\\vec\{[va]\})\s*\}", r"\1", latex)
        latex = re.sub(r"\s+", " ", latex).strip()
        latex = re.sub(
            r"(\\vec\{v\})\s*\\vec\{e\}\s*(\\vec\{a\})",
            r"\1 e \2",
            latex,
            flags=re.IGNORECASE,
        )
        if not re.fullmatch(r"\\vec\{v\}\s*e\s*\\vec\{a\}", latex, re.IGNORECASE):
            return None
        return r"\vec{v}\;e\;\vec{a}"

    @staticmethod
    def _merge_fallback_formula_predictions(
        primary_regions: Optional[List[LayoutRegion]],
        primary_predictions: FormulaPredictions,
        fallback_regions: Optional[List[LayoutRegion]],
        fallback_predictions: FormulaPredictions,
        page_index: int,
    ) -> FormulaPredictions:
        """Keep formula reads from the enhanced image when the secondary OCR pass uses the raw photo.

        A separately configured fallback detector may recover faint prose, but its formula
        crops can lose contrast or become clipped. Layout boxes can shift slightly
        between the two image frames, so match them by overlap rather than exact keys.
        An explicit fallback prediction remains authoritative; a primary prediction
        only fills a missing result for the same printed region.
        """
        merged = dict(fallback_predictions)
        primary = [
            (region, primary_predictions.get(formula_prediction_key(page_index, region.bbox)))
            for region in primary_regions or []
            if region.kind == "formula" and not region.handwritten
        ]
        for fallback in fallback_regions or []:
            if fallback.kind != "formula" or fallback.handwritten:
                continue
            fallback_key = formula_prediction_key(page_index, fallback.bbox)
            if merged.get(fallback_key):
                continue

            best_prediction = None
            best_overlap = 0.0
            fx1, fy1, fx2, fy2 = fallback.bbox
            fallback_area = max(1, (fx2 - fx1) * (fy2 - fy1))
            for primary_region, prediction in primary:
                if not prediction:
                    continue
                px1, py1, px2, py2 = primary_region.bbox
                intersection = max(0, min(fx2, px2) - max(fx1, px1)) * max(
                    0, min(fy2, py2) - max(fy1, py1)
                )
                smaller_area = min(
                    fallback_area,
                    max(1, (px2 - px1) * (py2 - py1)),
                )
                overlap = intersection / smaller_area
                if overlap > best_overlap:
                    best_overlap = overlap
                    best_prediction = prediction
            if best_overlap >= 0.6 and best_prediction:
                merged[fallback_key] = best_prediction
        return merged

    def initialize(self) -> None:
        """
        Initialize PaddleOCR engine.

        This is called lazily on first use or can be called explicitly
        at application startup to pre-warm the models.
        """
        if self._initialized:
            return

        logger.info(
            "Initializing PaddleOCR engine",
            pipeline=settings.ocr_pipeline,
            lang=self.lang,
            use_gpu=self.use_gpu,
            det_model=self.det_model_name,
            rec_model=self.rec_model_name,
        )

        try:
            if settings.ocr_pipeline == "vl":
                from paddleocr import PaddleOCRVL

                self._vl = PaddleOCRVL(device="gpu:0" if self.use_gpu else "cpu")
            else:
                ocr_options = self._ocr_options(self.det_model_name)
                if self.det_model_dir:
                    ocr_options["text_detection_model_dir"] = self.det_model_dir
                self._ocr = _get_paddle_ocr()(**ocr_options)

            self._initialized = True
            logger.info("PaddleOCR engine initialized successfully")

        except Exception as e:
            logger.error(
                "Failed to initialize PaddleOCR engine",
                error_type=type(e).__name__,
                error=str(e),
                exc_info=True,
            )
            raise RuntimeError(f"Failed to initialize OCR engine: {e}")

    def _ensure_initialized(self) -> None:
        """Ensure the OCR engine is initialized."""
        if not self._initialized:
            self.initialize()

    def _ocr_options(self, det_model_name: str) -> Dict[str, Any]:
        """The PaddleOCR 3.x constructor options, for a given detector."""
        options: Dict[str, Any] = {
            "use_doc_orientation_classify": False,
            "use_doc_unwarping": False,
            "use_textline_orientation": self.use_angle_cls,
            "device": "gpu:0" if self.use_gpu else "cpu",
            # The pair is named, not chosen by lang/ocr_version: in PaddleOCR 3.x the PP-OCRv4 family only
            # ships ch and en, so ocr_version="PP-OCRv4" with lang="pt" has no model at all (every request
            # answered 500). latin_PP-OCRv5_mobile_rec reads Portuguese accents.
            "text_detection_model_name": det_model_name,
            "text_recognition_model_name": self.rec_model_name,
            # oneDNN works with paddlepaddle 3.2.x (pinned in requirements.txt); with 3.3.1 it dies on the
            # first real page.
            "enable_mkldnn": settings.ocr_enable_mkldnn,
        }
        if settings.ocr_det_thresh is not None:
            options["text_det_thresh"] = settings.ocr_det_thresh
        if settings.ocr_det_box_thresh is not None:
            options["text_det_box_thresh"] = settings.ocr_det_box_thresh
        if self.rec_model_dir:
            options["text_recognition_model_dir"] = self.rec_model_dir
        if self.cls_model_dir:
            options["textline_orientation_model_dir"] = self.cls_model_dir
        return options

    def _run_ocr(self, image: np.ndarray, fallback: bool = False) -> List[Any]:
        """
        Run PaddleOCR on an image.

        Args:
            image: Image as numpy array (BGR format)
            fallback: Run the second pass at higher resolution or with the separately configured detector

        Returns:
            PaddleOCR 3.x results: one dict-like per image with rec_texts, rec_scores and rec_polys
        """
        self._ensure_initialized()

        if fallback:
            if settings.ocr_fallback_det_model_name == self.det_model_name:
                if self._ocr is None:
                    raise RuntimeError("OCR engine is not initialized")
                return list(self._ocr.predict(image, text_det_limit_side_len=1920))
            if self._fallback_ocr is None:
                self._fallback_ocr = _get_paddle_ocr()(**self._ocr_options(settings.ocr_fallback_det_model_name))
            return list(self._fallback_ocr.predict(image))

        if self._ocr is None:
            raise RuntimeError("OCR engine not initialized")

        return list(self._ocr.predict(image))

    def _parse_ocr_results(
        self,
        raw_results: List[Any],
        page_index: int = 0,
    ) -> List[TextBlock]:
        """
        Parse PaddleOCR 3.x results into TextBlock objects (one per text line).

        Args:
            raw_results: Result of ``PaddleOCR.predict``
            page_index: Index of the page being processed
        """
        text_blocks: List[TextBlock] = []
        if not raw_results:
            return text_blocks

        index = 0
        for result in raw_results:
            texts = result.get("rec_texts") or []
            scores = result.get("rec_scores")
            scores = [0.5] * len(texts) if scores is None else scores
            polys = result.get("rec_polys")
            polys = result.get("dt_polys") if polys is None or len(polys) == 0 else polys
            polys = [] if polys is None else polys
            for position, text in enumerate(texts):
                if not str(text).strip():
                    continue
                confidence = float(scores[position]) if position < len(scores) else 0.5
                if position < len(polys):
                    poly = np.asarray(polys[position]).reshape(-1, 2)
                    x1, y1 = int(poly[:, 0].min()), int(poly[:, 1].min())
                    x2, y2 = int(poly[:, 0].max()), int(poly[:, 1].max())
                else:
                    x1, y1, x2, y2 = 0, 0, 0, 0
                text_blocks.append(TextBlock(
                    text=str(text),
                    confidence=confidence,
                    bbox=(x1, y1, x2, y2),
                    page_index=page_index,
                    line_index=index,
                ))
                index += 1

        return text_blocks

    # Blocks of the PaddleOCR-VL parse that are pictures of the page, and the kind each becomes.
    _VL_REGION_KINDS = {
        "image": "figure", "chart": "figure", "figure": "figure",
        "table": "table",
        "formula": "formula", "display_formula": "formula",
    }
    # Blocks whose text is not part of the exam (page furniture and captions of the logos).
    _VL_SKIPPED = {"header", "footer", "number", "aside_text", "seal", "formula_number", "reference"}

    def _run_vl(self, image: np.ndarray, page_index: int) -> Tuple[List[TextBlock], Optional[List[LayoutRegion]]]:
        """
        Read the page with PaddleOCR-VL (``OCR_PIPELINE=vl``): text lines and figure/table/formula regions in one
        pass, from the document parser instead of the line detector plus the layout model.

        Written against the PaddleOCR-VL result in paddlex (``parsing_res_list`` blocks with ``label``,
        ``content`` and ``bbox``). It needs a GPU to be usable: on CPU the 0.9B model did not finish one page in
        ten minutes and ran out of 6 GB of memory, so the default pipeline is ``ppocr``.
        """
        self._ensure_initialized()
        if self._vl is None:
            raise RuntimeError("PaddleOCR-VL not initialized")

        blocks: List[TextBlock] = []
        regions: List[LayoutRegion] = []
        for result in self._vl.predict(image):
            for parsed in result["parsing_res_list"]:
                label = str(getattr(parsed, "label", ""))
                x1, y1, x2, y2 = (int(round(float(v))) for v in getattr(parsed, "bbox", (0, 0, 0, 0))[:4])
                kind = self._VL_REGION_KINDS.get(label)
                if kind is not None:
                    regions.append(LayoutRegion(kind=kind, label=label, score=1.0, bbox=(x1, y1, x2, y2)))
                    continue
                if label in self._VL_SKIPPED:
                    continue
                lines = [line.strip() for line in str(getattr(parsed, "content", "") or "").splitlines() if line.strip()]
                if not lines:
                    continue
                step = max(1, (y2 - y1) // len(lines))
                for position, line in enumerate(lines):
                    top = y1 + position * step
                    blocks.append(TextBlock(
                        text=line,
                        confidence=0.9,
                        bbox=(x1, top, x2, min(y2, top + step) if position < len(lines) - 1 else y2),
                        page_index=page_index,
                        line_index=len(blocks),
                    ))
        regions.sort(key=lambda r: (r.bbox[1], r.bbox[0]))
        return blocks, regions

    @staticmethod
    def _mark_handwriting(
        colour: np.ndarray,
        frame: np.ndarray,
        blocks: List[TextBlock],
        layout: Optional[List[LayoutRegion]],
    ) -> Tuple[List[TextBlock], Optional[List[LayoutRegion]]]:
        """Flag the text lines and formula regions written in pen: they are not part of the printed exam.

        ``colour`` is the photo as it came (preprocessing turns it grey, and the pen is told apart by colour);
        ``frame`` is the image the boxes are in. When deskew grew the canvas the two differ by a centred offset.
        """
        dx = max(0, (frame.shape[1] - colour.shape[1]) // 2)
        dy = max(0, (frame.shape[0] - colour.shape[0]) // 2)

        def pen(box) -> bool:
            return is_handwriting(colour, (box[0] - dx, box[1] - dy, box[2] - dx, box[3] - dy))

        marked = [replace(b, handwritten=True) if pen(b.bbox) else b for b in blocks]
        regions = None if layout is None else [
            replace(r, handwritten=True) if r.kind in ("formula", "figure") and pen(r.bbox) else r for r in layout
        ]
        return marked, regions

    @staticmethod
    def _numbering_has_gaps(questions: List[ExtractedQuestion]) -> bool:
        """Detect gaps within a page without assuming its first question is numbered one."""
        numbers = [int(q.number) for q in questions if str(q.number).isdigit()]
        return bool(numbers) and numbers != list(range(numbers[0], numbers[0] + len(numbers)))

    @staticmethod
    def _may_have_missing_first_subitem(questions: List[ExtractedQuestion]) -> bool:
        """Flag an instruction ending in a colon when its extracted list begins after ``a``.

        This is a focused signal for a missed first row, not an assumption that every list must begin at a.
        Some exam questions intentionally start at c) or d), usually without a colon-led list instruction.
        """
        for question in questions:
            if not question.subitems_content or not question.text.strip().endswith(":"):
                continue
            first = question.subitems_content[0].label.lower().strip()
            if first in {"b", "c", "d"}:
                return True
        return False

    def _detect_layout(self, image: np.ndarray, request_id: str) -> Optional[List[LayoutRegion]]:
        """Figures, tables and formulas of the page; None when the layout was not run (disabled or failed).

        A failure here must not cost the text, so it only logs.
        """
        if not settings.ocr_layout_enabled:
            return None
        try:
            return self._layout.detect(image)
        except Exception as e:
            logger.error(
                "Layout detection failed",
                request_id=request_id,
                error_type=type(e).__name__,
                error=str(e),
            )
            return None

    def _detect_tables(self, text_blocks: List[TextBlock], layout: Optional[List[LayoutRegion]] = None) -> bool:
        """Whether the page has a table: a table region from the layout, else rows of aligned text."""
        if layout and any(region.kind == "table" for region in layout):
            return True
        if len(text_blocks) < 3:
            return False

        y_positions = sorted((b.bbox[1] + b.bbox[3]) / 2 for b in text_blocks)
        tolerance = 20
        similar_count = sum(
            1 for i in range(1, len(y_positions)) if abs(y_positions[i] - y_positions[i - 1]) < tolerance
        )
        return similar_count >= 3

    def _calculate_overall_confidence(
        self,
        text_blocks: List[TextBlock],
        metadata: ExtractedMetadata,
        questions: List[ExtractedQuestion],
    ) -> float:
        """Calculate overall confidence score for the extraction."""
        confidences = []

        # Add text block confidences
        for block in text_blocks:
            if block.confidence >= settings.min_confidence_threshold:
                confidences.append(block.confidence)

        # Add metadata confidences
        for field in [
            metadata.school_year_start,
            metadata.school_year_end,
            metadata.subject_name,
            metadata.class_grade,
            metadata.exam_type,
        ]:
            if field.value is not None:
                confidences.append(field.confidence)

        # Add question confidences
        for question in questions:
            confidences.append(question.confidence)

        if not confidences:
            return 0.0

        return sum(confidences) / len(confidences)

    def process_image(
        self,
        image: np.ndarray,
        page_index: int = 0,
        request_id: Optional[str] = None,
        source_file_index: int = 0,
        native_text_page: Optional[Dict[str, Any]] = None,
    ) -> OCRResult:
        """
        Process a single image and extract text with structure.

        Args:
            image: Image as numpy array (BGR format)
            page_index: Index of the page being processed
            request_id: Optional request ID for tracking

        Returns:
            OCRResult with extracted data
        """
        start_time = time.perf_counter()
        request_id = request_id or f"req-{uuid.uuid4().hex[:12]}"

        logger.info(
            "Processing image",
            request_id=request_id,
            page_index=page_index,
            image_shape=image.shape,
        )

        try:
            # Preprocess the image
            preprocessing_started = time.perf_counter()
            preprocessing_result = self.preprocessor.preprocess(image)
            preprocessing_ms = (time.perf_counter() - preprocessing_started) * 1000
            processed_image = preprocessing_result.image

            preprocessing_meta: Dict[str, Any] = {
                "sheetCropped": preprocessing_result.sheet_cropped,
                "shadowReduced": preprocessing_result.shadow_reduced,
                "rotationAngle": round(preprocessing_result.rotation_angle, 2),
                "qualityScore": round(preprocessing_result.quality_score, 2),
                "isLegible": preprocessing_result.is_legible,
                "appliedSteps": preprocessing_result.preprocessing_applied,
                "durationMs": round(preprocessing_result.duration_ms, 1),
            }
            if preprocessing_result.rejection_reason:
                preprocessing_meta["rejectionReason"] = preprocessing_result.rejection_reason

            logger.debug(
                "Preprocessing complete",
                request_id=request_id,
                preprocessing_applied=preprocessing_result.preprocessing_applied,
                rotation_angle=preprocessing_result.rotation_angle,
                is_legible=preprocessing_result.is_legible,
                duration_ms=preprocessing_result.duration_ms,
            )

            # #263: an explicit, visible signal when the photo itself is the problem - not a
            # silent empty result the caller has no way to act on.
            legibility_warnings: List[Warning] = []
            if not preprocessing_result.is_legible:
                logger.warning(
                    "Image flagged as possibly illegible before OCR",
                    request_id=request_id,
                    reason=preprocessing_result.rejection_reason,
                    quality_score=preprocessing_result.quality_score,
                )
                legibility_warnings.append(Warning(
                    code="image_quality",
                    field="image",
                    confidence=0.0,
                    message=f"A imagem pode estar ilegível ({preprocessing_result.rejection_reason}); tente fotografar de novo com mais luz e sem tremer.",
                ))

            # Run OCR: text lines, and the layout that says where the figures are
            ocr_started = time.perf_counter()
            native_lines = (native_text_page or {}).get("lines") or []
            native_gap_count = sum(
                1 for line in native_lines
                if re.search(r"\S[ \t]{3,}\S", str(line.get("text", "")))
            )
            native_usable = (
                len(native_lines) >= 3
                and sum(len(str(line.get("text", "")).strip()) for line in native_lines) >= 80
            )
            if native_usable:
                page_width = float((native_text_page or {}).get("width") or 0)
                page_height = float((native_text_page or {}).get("height") or 0)
                # Native text coordinates belong to the source PDF page. Keep the
                # unmodified render as the layout/crop frame so preprocessing cannot
                # shift formula and figure associations away from their source region.
                analysis_image = image
                image_height, image_width = analysis_image.shape[:2]
                text_blocks = []
                for line_index, line in enumerate(native_lines):
                    try:
                        x0, y0, x1, y1 = line["bbox"]
                        bbox = (
                            round(float(x0) * image_width / page_width),
                            round(float(y0) * image_height / page_height),
                            round(float(x1) * image_width / page_width),
                            round(float(y1) * image_height / page_height),
                        )
                        text_blocks.append(TextBlock(
                            text=str(line["text"]), confidence=0.99, bbox=bbox,
                            page_index=page_index, line_index=line_index,
                        ))
                    except (KeyError, TypeError, ValueError, ZeroDivisionError):
                        continue
                layout = self._detect_layout(analysis_image, request_id)
            elif settings.ocr_pipeline == "vl":
                text_blocks, layout = self._run_vl(processed_image, page_index)
                analysis_image = processed_image
            else:
                raw_results = self._run_ocr(processed_image)
                text_blocks = self._parse_ocr_results(raw_results, page_index)
                layout = self._detect_layout(processed_image, request_id)
                analysis_image = processed_image
            text_blocks, layout = self._mark_handwriting(image, processed_image, text_blocks, layout)

            formula_started = time.perf_counter()
            # FormulaNet is sensitive to the denoising/contrast pass: on clean,
            # geometrically unchanged pages it can turn vector arrows into bars
            # and split subscripts. Use original pixels while retaining the layout
            # coordinate frame; transformed pages must use the matching processed
            # frame to avoid cropping the wrong location.
            formula_image = analysis_image
            if (
                not native_usable
                and not preprocessing_result.sheet_cropped
                and abs(preprocessing_result.rotation_angle) < 0.05
                and image.shape[:2] == processed_image.shape[:2]
            ):
                formula_image = image
            formula_predictions = self._recognize_formula_regions(
                formula_image,
                layout,
                request_id,
                page_index,
            )
            vector_regions, vector_predictions = self._recognize_inline_vector_pairs(
                formula_image,
                text_blocks,
                request_id,
                page_index,
            )
            if vector_regions:
                layout = [*(layout or []), *vector_regions]
                formula_predictions.update(vector_predictions)
            formula_recognition_ms = (time.perf_counter() - formula_started) * 1000

            logger.debug(
                "OCR complete",
                request_id=request_id,
                num_text_blocks=len(text_blocks),
            )

            # Detect tables
            has_tables = self._detect_tables(text_blocks, layout)

            # Detect language
            full_text = " ".join(b.text for b in text_blocks)
            main_language = detect_language(full_text) if full_text else self.lang
            ocr_ms = max(0.0, (time.perf_counter() - ocr_started) * 1000 - formula_recognition_ms)

            # Postprocess to extract structured data. The boxes are in the preprocessed image's frame (deskew
            # can grow the canvas by a few pixels), so that is the size the crops are scaled from.
            def interpret(
                blocks: List[TextBlock],
                frame: np.ndarray = analysis_image,
                regions=layout,
                predictions: FormulaPredictions = formula_predictions,
            ):
                return self.postprocessor.process(
                    blocks,
                    page_count=1,
                    page_dimensions={page_index: (int(frame.shape[1]), int(frame.shape[0]))},
                    source_file_indices={page_index: source_file_index},
                    layout_regions=None if regions is None else {page_index: regions},
                    formula_images=settings.ocr_formula_images,
                    recognize_formulas=settings.ocr_formula_latex,
                    formula_predictions=predictions,
                )

            parsing_started = time.perf_counter()
            metadata, questions, images_to_upload, unmapped, warnings = interpret(text_blocks)
            parsing_ms = time.perf_counter() - parsing_started

            # A page the primary pass could not make questions of, or numbered with gaps, is read again on the
            # original photo at higher detector resolution. Layout and formula crops keep the same image frame.
            if (
                settings.ocr_pipeline != "vl"
                and settings.ocr_fallback_det_model_name
                and (
                    len(questions) < settings.ocr_min_questions
                    or self._numbering_has_gaps(questions)
                    or self._may_have_missing_first_subitem(questions)
                )
            ):
                logger.info("Reading again with the fallback detector", request_id=request_id, questions=len(questions))
                fallback_ocr_started = time.perf_counter()
                retry_blocks = self._parse_ocr_results(self._run_ocr(image, fallback=True), page_index)
                retry_layout = self._detect_layout(image, request_id)
                retry_blocks, retry_layout = self._mark_handwriting(image, image, retry_blocks, retry_layout)
                retry_formula_started = time.perf_counter()
                retry_formula_predictions = self._recognize_formula_regions(
                    image,
                    retry_layout,
                    request_id,
                    page_index,
                )
                retry_vector_regions, retry_vector_predictions = self._recognize_inline_vector_pairs(
                    image,
                    retry_blocks,
                    request_id,
                    page_index,
                )
                if retry_vector_regions:
                    retry_layout = [*(retry_layout or []), *retry_vector_regions]
                    retry_formula_predictions.update(retry_vector_predictions)
                retry_formula_ms = (time.perf_counter() - retry_formula_started) * 1000
                formula_recognition_ms += retry_formula_ms
                ocr_ms += max(0.0, (time.perf_counter() - fallback_ocr_started) * 1000 - retry_formula_ms)
                fallback_parse_started = time.perf_counter()
                retry = interpret(retry_blocks, image, retry_layout, retry_formula_predictions)
                parsing_ms += time.perf_counter() - fallback_parse_started
                if len(retry[1]) > len(questions) or (
                    len(retry[1]) == len(questions) and self._numbering_has_gaps(questions) and not self._numbering_has_gaps(retry[1])
                ) or (
                    len(retry[1]) == len(questions)
                    and self._may_have_missing_first_subitem(questions)
                    and not self._may_have_missing_first_subitem(retry[1])
                ):
                    retry_formula_predictions = self._merge_fallback_formula_predictions(
                        layout,
                        formula_predictions,
                        retry_layout,
                        retry_formula_predictions,
                        page_index,
                    )
                    retry = interpret(
                        retry_blocks,
                        image,
                        retry_layout,
                        retry_formula_predictions,
                    )
                    text_blocks = retry_blocks
                    layout = retry_layout
                    analysis_image = image
                    formula_predictions = retry_formula_predictions
                    metadata, questions, images_to_upload, unmapped, warnings = retry

            # Calculate confidence
            overall_confidence = self._calculate_overall_confidence(
                text_blocks, metadata, questions
            )

            native_text_warnings = ([Warning(
                code="NATIVE_TEXT_GAP",
                field="formulas",
                confidence=0.0,
                message="O texto do PDF contém espaços que podem corresponder a fórmulas ou símbolos não extraídos. Confirme estes trechos com o documento original.",
            )] if native_gap_count else [])
            warnings = [*legibility_warnings, *native_text_warnings, *warnings]

            # Determine status
            error_message = None
            if not text_blocks:
                status = "error"
                # #263: an explicit reason beats a bare "error" when the photo itself (not
                # the service) is why nothing came back.
                error_message = (
                    f"Imagem ilegível ({preprocessing_result.rejection_reason})"
                    if not preprocessing_result.is_legible
                    else "No text could be extracted from the image"
                )
            elif not questions:
                status = "partial"
                warnings.append(Warning(
                    code="NO_QUESTIONS",
                    field="questions",
                    confidence=overall_confidence,
                    message="Foi lido texto, mas não foi possível estruturar questões nesta página.",
                ))
            elif overall_confidence < 0.5:
                status = "partial"
            else:
                status = "success"

            processing_time_ms = int((time.perf_counter() - start_time) * 1000)

            result = OCRResult(
                status=status,
                request_id=request_id,
                processing_time_ms=processing_time_ms,
                overall_confidence=overall_confidence,
                document=DocumentInfo(
                    page_count=1,
                    main_language=main_language,
                    has_tables=has_tables,
                ),
                metadata=metadata,
                questions=questions,
                images_to_upload=images_to_upload,
                unmapped_content=unmapped,
                warnings=warnings,
                error_message=error_message,
                preprocessing=preprocessing_meta,
                timings={
                    "preprocessingMs": round(preprocessing_ms, 1),
                    "ocrMs": round(ocr_ms, 1),
                    "formulaRecognitionMs": round(formula_recognition_ms, 1),
                    "parsingMs": round(parsing_ms * 1000, 1),
                    "totalMs": round((time.perf_counter() - start_time) * 1000, 1),
                },
                page_context=PageOcrContext(
                    page_index=page_index,
                    source_file_index=source_file_index,
                    text_blocks=list(text_blocks),
                    layout_regions=list(layout) if layout is not None else None,
                    formula_predictions=formula_predictions,
                    width=int(analysis_image.shape[1]),
                    height=int(analysis_image.shape[0]),
                ),
            )

            logger.info(
                "Image processing complete",
                request_id=request_id,
                status=status,
                processing_time_ms=processing_time_ms,
                num_questions=len(questions),
                overall_confidence=overall_confidence,
            )

            return result

        except Exception as e:
            processing_time_ms = int((time.perf_counter() - start_time) * 1000)

            logger.error(
                "Image processing failed",
                request_id=request_id,
                error_type=type(e).__name__,
            )

            return OCRResult(
                status="error",
                request_id=request_id,
                processing_time_ms=processing_time_ms,
                overall_confidence=0.0,
                document=DocumentInfo(page_count=0, main_language=self.lang, has_tables=False),
                metadata=ExtractedMetadata(),
                questions=[],
                images_to_upload=[],
                unmapped_content=[],
                warnings=[],
                error_message="Image processing failed",
                timings={"totalMs": round((time.perf_counter() - start_time) * 1000, 1)},
            )

    async def process_image_async(
        self,
        image: np.ndarray,
        page_index: int = 0,
        request_id: Optional[str] = None,
        source_file_index: int = 0,
    ) -> OCRResult:
        """
        Asynchronously process a single image.

        Args:
            image: Image as numpy array (BGR format)
            page_index: Index of the page being processed
            request_id: Optional request ID for tracking

        Returns:
            OCRResult with extracted data
        """
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(
            self._executor,
            self.process_image,
            image,
            page_index,
            request_id,
            source_file_index,
        )

    def process_images(
        self,
        images: List[np.ndarray],
        request_id: Optional[str] = None,
        source_file_indices: Optional[List[int]] = None,
    ) -> OCRResult:
        return self.process_images_with_progress(images, request_id, source_file_indices)

    def process_images_with_progress(
        self,
        images: List[np.ndarray],
        request_id: Optional[str] = None,
        source_file_indices: Optional[List[int]] = None,
        on_page_complete: Optional[Callable[[Dict[str, Any]], None]] = None,
        cancelled: Optional[threading.Event] = None,
        native_text_pages: Optional[List[Optional[Dict[str, Any]]]] = None,
        page_count: Optional[int] = None,
        on_page_rasterized: Optional[Callable[[int], Optional[Dict[str, Any]]]] = None,
    ) -> OCRResult:
        """
        Process pages sequentially and report measured completion after each page.

        Args:
            images: Images as numpy arrays. Iterators require an explicit page_count.
            request_id: Optional request ID for tracking

        Returns:
            Combined OCRResult with data from all pages
        """
        start_time = time.perf_counter()
        request_id = request_id or f"req-{uuid.uuid4().hex[:12]}"

        if page_count is None:
            if not hasattr(images, "__len__"):
                raise ValueError("page_count is required for an image iterator")
            page_count = len(images)  # type: ignore[arg-type]

        logger.info(
            "Processing multiple images",
            request_id=request_id,
            num_images=page_count,
        )

        if page_count == 0:
            return OCRResult(
                status="error",
                request_id=request_id,
                processing_time_ms=0,
                overall_confidence=0.0,
                document=DocumentInfo(page_count=0, main_language=self.lang, has_tables=False),
                metadata=ExtractedMetadata(),
                questions=[],
                images_to_upload=[],
                unmapped_content=[],
                warnings=[],
                error_message="No images provided",
            )

        all_questions = []
        all_images_to_upload = []
        all_unmapped = []
        all_warnings = []
        has_tables = False
        languages = []
        read_any_text = False
        has_partial_page = False
        has_partial_content_page = False
        has_unreadable_page = False
        page_contexts: List[PageOcrContext] = []
        page_timings: Dict[str, float] = {
            "preprocessingMs": 0.0,
            "ocrMs": 0.0,
            "formulaRecognitionMs": 0.0,
            "parsingMs": 0.0,
        }

        processed_pages = 0
        image_iterator = iter(images)
        while True:
            if cancelled is not None and cancelled.is_set():
                break
            try:
                image = next(image_iterator)
            except StopIteration:
                break
            idx = processed_pages
            if on_page_rasterized is not None:
                on_page_rasterized(idx + 1)
                if cancelled is not None and cancelled.is_set():
                    break
            result = self.process_image(
                image,
                page_index=idx,
                request_id=request_id,
                source_file_index=(source_file_indices[idx]
                                   if source_file_indices and idx < len(source_file_indices)
                                   else 0),
                native_text_page=(native_text_pages[idx]
                                  if native_text_pages and idx < len(native_text_pages)
                                  else None),
            )

            # ``process_image`` only marks ``error`` when it saw no text blocks at all, so a
            # page that is not an error did read text, even if it became no question.
            if result.status != "error":
                read_any_text = True
            has_partial_page = has_partial_page or result.status == "partial"
            has_partial_content_page = has_partial_content_page or (
                result.status == "partial" and bool(result.questions)
            )
            has_unreadable_page = has_unreadable_page or result.status == "error"
            if result.page_context is not None:
                page_contexts.append(result.page_context)

            # Merge results
            all_questions.extend(result.questions)
            all_images_to_upload.extend(result.images_to_upload)
            all_unmapped.extend(result.unmapped_content)
            all_warnings.extend(result.warnings)

            if result.document.has_tables:
                has_tables = True
            languages.append(result.document.main_language)
            for key in page_timings:
                page_timings[key] += float((result.timings or {}).get(key, 0))

            if on_page_complete is not None:
                on_page_complete({
                    "page": idx + 1,
                    "pageCount": page_count,
                    "timings": result.timings or {},
                })
            processed_pages += 1

            # Use metadata from first page
            if idx == 0:
                metadata = result.metadata

        # Parse the complete document after each page has been recognized. This retains streaming page
        # progress but lets a question or alínea continue across a physical page break without dropping text.
        combined_parsing_ms = 0.0
        if processed_pages == page_count and page_contexts:
            combined_started = time.perf_counter()
            all_blocks = [block for page in page_contexts for block in page.text_blocks]
            page_dimensions = {page.page_index: (page.width, page.height) for page in page_contexts}
            source_indices = {page.page_index: page.source_file_index for page in page_contexts}
            layout_regions = {
                page.page_index: page.layout_regions
                for page in page_contexts
                if page.layout_regions is not None
            }
            formula_predictions = {
                key: latex
                for page in page_contexts
                for key, latex in page.formula_predictions.items()
            }
            metadata, all_questions, all_images_to_upload, all_unmapped, document_warnings = self.postprocessor.process(
                all_blocks,
                page_count=page_count,
                page_dimensions=page_dimensions,
                source_file_indices=source_indices,
                layout_regions=layout_regions,
                formula_images=settings.ocr_formula_images,
                recognize_formulas=settings.ocr_formula_latex,
                formula_predictions=formula_predictions,
            )
            combined_parsing_ms = (time.perf_counter() - combined_started) * 1000
            page_timings["parsingMs"] += combined_parsing_ms
            # Keep page-local acquisition warnings (e.g. image quality and missing native PDF glyphs),
            # while replacing per-page structural warnings with the document-level parser result.
            page_warnings = [
                warning for warning in all_warnings
                if warning.code in {"image_quality", "NATIVE_TEXT_GAP"}
            ]
            all_warnings = []
            seen_warnings = set()
            for warning in [*document_warnings, *page_warnings]:
                key = (warning.code, warning.field, warning.confidence, warning.message)
                if key not in seen_warnings:
                    seen_warnings.add(key)
                    all_warnings.append(warning)

        # Determine main language (most common)
        if languages:
            language_counts = {language: languages.count(language) for language in set(languages)}
            highest_language_count = max(language_counts.values())
            main_language = (
                self.lang
                if language_counts.get(self.lang) == highest_language_count
                else next(language for language in languages if language_counts[language] == highest_language_count)
            )
        else:
            main_language = self.lang

        # Calculate overall confidence
        if all_questions:
            overall_confidence = sum(q.confidence for q in all_questions) / len(all_questions)
        else:
            overall_confidence = 0.0

        processing_time_ms = int((time.perf_counter() - start_time) * 1000)

        # Determine status. Text the parser could not turn into questions is still a reading:
        # the caller gets it with ``partial`` (207) instead of a 500 it cannot tell apart from a
        # service failure. Only a document with no text at all is an error.
        if not all_questions and not read_any_text:
            status = "error"
        elif has_unreadable_page or has_partial_content_page or (
            has_partial_page and len(page_contexts) < page_count
        ) or not all_questions or overall_confidence < 0.5:
            status = "partial"
        else:
            status = "success"

        return OCRResult(
            status=status,
            request_id=request_id,
            processing_time_ms=processing_time_ms,
            overall_confidence=overall_confidence,
            document=DocumentInfo(
                page_count=page_count,
                main_language=main_language,
                has_tables=has_tables,
            ),
            metadata=metadata if 'metadata' in dir() else ExtractedMetadata(),
            questions=all_questions,
            images_to_upload=all_images_to_upload,
            unmapped_content=all_unmapped,
            warnings=all_warnings,
            timings={
                **{key: round(value, 1) for key, value in page_timings.items()},
                **({"combinedParsingMs": round(combined_parsing_ms, 1)} if combined_parsing_ms else {}),
                "totalMs": round((time.perf_counter() - start_time) * 1000, 1),
            },
        )

    async def process_images_progress_async(
        self,
        images: Iterable[np.ndarray],
        request_id: Optional[str] = None,
        source_file_indices: Optional[List[int]] = None,
        native_text_pages: Optional[List[Optional[Dict[str, Any]]]] = None,
        page_count: Optional[int] = None,
        on_page_rasterized: Optional[Callable[[int], Optional[Dict[str, Any]]]] = None,
    ):
        """Yield a bounded, backpressured event after each completed page and then the result."""
        loop = asyncio.get_running_loop()
        events: asyncio.Queue = asyncio.Queue(maxsize=1)
        cancelled = threading.Event()

        def report(event: Dict[str, Any]) -> None:
            pending = asyncio.run_coroutine_threadsafe(events.put(event), loop)
            while not cancelled.is_set():
                try:
                    pending.result(timeout=0.1)
                    return
                except TimeoutError:
                    continue
                except Exception:
                    return
            pending.cancel()

        def report_rasterized(page: int) -> None:
            if on_page_rasterized is not None:
                extra = on_page_rasterized(page) or {}
                report({"type": "rasterized", "page": page, "pageCount": page_count, **extra})

        task = loop.run_in_executor(
            self._executor,
            self.process_images_with_progress,
            images,
            request_id,
            source_file_indices,
            report,
            cancelled,
            native_text_pages,
            page_count,
            report_rasterized,
        )
        try:
            while not task.done():
                try:
                    yield await asyncio.wait_for(events.get(), timeout=0.1)
                except asyncio.TimeoutError:
                    continue
            result = await task
            while not events.empty():
                yield events.get_nowait()
            yield {"type": "result", "result": result}
        finally:
            cancelled.set()

    async def process_images_async(
        self,
        images: List[np.ndarray],
        request_id: Optional[str] = None,
        source_file_indices: Optional[List[int]] = None,
    ) -> OCRResult:
        """
        Asynchronously process multiple images.

        Args:
            images: List of images as numpy arrays
            request_id: Optional request ID for tracking

        Returns:
            Combined OCRResult with data from all pages
        """
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(
            self._executor,
            self.process_images,
            images,
            request_id,
            source_file_indices,
        )

    def process_bytes(
        self,
        image_bytes: bytes,
        request_id: Optional[str] = None,
    ) -> OCRResult:
        """
        Process image from bytes.

        Args:
            image_bytes: Raw image bytes
            request_id: Optional request ID for tracking

        Returns:
            OCRResult with extracted data
        """
        try:
            image = load_image_from_bytes(image_bytes)
            return self.process_image(image, request_id=request_id)
        except Exception as e:
            return OCRResult(
                status="error",
                request_id=request_id or f"req-{uuid.uuid4().hex[:12]}",
                processing_time_ms=0,
                overall_confidence=0.0,
                document=DocumentInfo(page_count=0, main_language=self.lang, has_tables=False),
                metadata=ExtractedMetadata(),
                questions=[],
                images_to_upload=[],
                unmapped_content=[],
                warnings=[],
                error_message="Failed to load image",
            )

    async def process_bytes_async(
        self,
        image_bytes: bytes,
        request_id: Optional[str] = None,
    ) -> OCRResult:
        """
        Asynchronously process image from bytes.

        Args:
            image_bytes: Raw image bytes
            request_id: Optional request ID for tracking

        Returns:
            OCRResult with extracted data
        """
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(
            self._executor,
            self.process_bytes,
            image_bytes,
            request_id,
        )

    def health_check(self) -> Dict[str, Any]:
        """
        Perform a health check on the OCR engine.

        Returns:
            Dictionary with health status information
        """
        status = {
            "initialized": self._initialized,
            "lang": self.lang,
            "use_gpu": self.use_gpu,
        }

        if self._initialized:
            try:
                # Create a simple test image
                test_image = np.zeros((100, 100, 3), dtype=np.uint8)
                test_image.fill(255)  # White background

                # Try to run OCR (should return empty results)
                self._run_ocr(test_image)
                status["status"] = "healthy"
                status["message"] = "OCR engine is operational"
            except Exception as e:
                status["status"] = "unhealthy"
                status["message"] = f"OCR engine error: {e}"
        else:
            status["status"] = "not_initialized"
            status["message"] = "OCR engine not yet initialized"

        return status

    def shutdown(self) -> None:
        """Shutdown the OCR engine and release resources."""
        logger.info("Shutting down OCR engine")
        self._executor.shutdown(wait=True)
        self._ocr = None
        self._initialized = False


def _ocr_process_main(connection) -> None:
    """Own Paddle in a killable process so cancellation can stop native inference."""
    engine = OCREngine(
        lang=settings.ocr_lang,
        use_gpu=settings.ocr_use_gpu,
        use_angle_cls=settings.ocr_use_angle_cls,
        det_model_dir=settings.ocr_det_model_dir,
        rec_model_dir=settings.ocr_rec_model_dir,
        cls_model_dir=settings.ocr_cls_model_dir,
        show_log=settings.ocr_show_log,
    )
    try:
        engine.initialize()
        connection.send({"type": "ready"})
        while True:
            command = connection.recv()
            if command["type"] == "shutdown":
                engine.shutdown()
                return
            if command["type"] == "progress":
                source_indices: List[int] = []
                native_pages: List[Optional[Dict[str, Any]]] = []

                def pages():
                    while True:
                        item = connection.recv()
                        if item["type"] == "end":
                            return
                        if item["type"] == "feed_error":
                            raise RuntimeError("OCR page input could not be prepared")
                        source_indices.append(item["source_file_index"])
                        native_pages.append(item["native_text_page"])
                        yield item["image"]

                try:
                    result = engine.process_images_with_progress(
                        pages(),
                        command["request_id"],
                        source_indices,
                        lambda event: connection.send({"type": "event", "event": event}),
                        native_text_pages=native_pages,
                        page_count=command["page_count"],
                        on_page_rasterized=lambda page: connection.send(
                            {"type": "rasterized", "page": page}
                        ),
                    )
                    connection.send({"type": "result", "result": result})
                except BaseException as error:
                    connection.send({
                        "type": "error",
                        "error_type": type(error).__name__,
                    })
                continue

            try:
                operation = command["type"]
                if operation == "process_image":
                    result = engine.process_image(**command["kwargs"])
                elif operation == "process_images":
                    result = engine.process_images(**command["kwargs"])
                else:
                    raise ValueError("Unsupported OCR worker operation")
                connection.send({"type": "result", "result": result})
            except BaseException as error:
                connection.send({"type": "error", "error_type": type(error).__name__})
    except BaseException as error:
        try:
            connection.send({"type": "startup_error", "error_type": type(error).__name__})
        except (BrokenPipeError, EOFError, OSError):
            pass
    finally:
        connection.close()


class OCRProcessWorker:
    """Serialize OCR through a persistent, killable Paddle process."""

    def __init__(self, process_target=_ocr_process_main):
        self._context = multiprocessing.get_context("spawn")
        self._process_target = process_target
        self._process = None
        self._connection = None
        self._lock = asyncio.Lock()
        self._ready = False

    def initialize(self) -> None:
        self._ensure_process()

    def _ensure_process(self) -> None:
        if self._process is not None and self._process.is_alive() and self._ready:
            return
        self._stop_process()
        parent, child = self._context.Pipe(duplex=True)
        process = self._context.Process(
            target=self._process_target,
            args=(child,),
            name="kixi-ocr-inference",
            daemon=True,
        )
        try:
            process.start()
        except BaseException:
            parent.close()
            child.close()
            raise
        child.close()
        self._process, self._connection = process, parent
        if not parent.poll(240):
            self._stop_process()
            raise TimeoutError("OCR inference worker initialization timed out")
        message = parent.recv()
        if message.get("type") != "ready":
            self._stop_process()
            raise RuntimeError(
                f"OCR inference worker failed to initialize ({message.get('error_type', 'unknown')})"
            )
        self._ready = True

    def _stop_process(self) -> None:
        process, connection = self._process, self._connection
        self._process = self._connection = None
        self._ready = False
        if connection is not None:
            try:
                connection.close()
            except OSError:
                pass
        if process is not None:
            if process.is_alive():
                process.terminate()
                process.join(timeout=2)
            if process.is_alive():
                process.kill()
                process.join(timeout=2)
            if not process.is_alive():
                process.close()

    def shutdown(self) -> None:
        process, connection = self._process, self._connection
        if process is not None and process.is_alive() and connection is not None:
            try:
                connection.send({"type": "shutdown"})
                process.join(timeout=5)
            except (BrokenPipeError, EOFError, OSError):
                pass
        self._stop_process()

    def health_check(self) -> Dict[str, Any]:
        alive = self._process is not None and self._process.is_alive() and self._ready
        return {
            "status": "healthy" if alive else "not_initialized",
            "initialized": bool(alive),
            "message": "OCR worker ready" if alive else "OCR worker starts on demand",
        }

    async def _call(self, operation: str, **kwargs):
        async with self._lock:
            await asyncio.to_thread(self._ensure_process)
            connection = self._connection
            await asyncio.to_thread(connection.send, {"type": operation, "kwargs": kwargs})
            message = await asyncio.to_thread(self._receive)
            if message["type"] != "result":
                raise RuntimeError(f"OCR worker failed ({message.get('error_type', 'unknown')})")
            return message["result"]

    def _receive(self):
        connection = self._connection
        while True:
            if connection.poll(0.25):
                return connection.recv()
            if self._process is None or not self._process.is_alive():
                raise RuntimeError("OCR inference worker exited unexpectedly")

    def process_image(self, image: np.ndarray, page_index: int = 0,
                      request_id: Optional[str] = None, source_file_index: int = 0,
                      native_text_page: Optional[Dict[str, Any]] = None) -> OCRResult:
        return asyncio.run(self._call(
            "process_image", image=image, page_index=page_index,
            request_id=request_id, source_file_index=source_file_index,
            native_text_page=native_text_page,
        ))

    async def process_image_async(self, image: np.ndarray, page_index: int = 0,
                                  request_id: Optional[str] = None,
                                  source_file_index: int = 0) -> OCRResult:
        return await self._call("process_image", image=image, page_index=page_index,
                                request_id=request_id, source_file_index=source_file_index)

    def process_images(self, images: List[np.ndarray], request_id: Optional[str] = None,
                       source_file_indices: Optional[List[int]] = None) -> OCRResult:
        return asyncio.run(self.process_images_async(images, request_id, source_file_indices))

    async def process_images_async(self, images: List[np.ndarray],
                                   request_id: Optional[str] = None,
                                   source_file_indices: Optional[List[int]] = None) -> OCRResult:
        return await self._call("process_images", images=images, request_id=request_id,
                                source_file_indices=source_file_indices)

    async def process_images_progress_async(
        self,
        images: Iterable[np.ndarray],
        request_id: Optional[str] = None,
        source_file_indices: Optional[List[int]] = None,
        native_text_pages: Optional[List[Optional[Dict[str, Any]]]] = None,
        page_count: Optional[int] = None,
        on_page_rasterized: Optional[Callable[[int], Optional[Dict[str, Any]]]] = None,
    ):
        if page_count is None:
            if not hasattr(images, "__len__"):
                raise ValueError("page_count is required for an image iterator")
            page_count = len(images)  # type: ignore[arg-type]
        sources = source_file_indices if source_file_indices is not None else []
        native_pages = native_text_pages if native_text_pages is not None else []

        async with self._lock:
            await asyncio.to_thread(self._ensure_process)
            connection = self._connection
            connection.send({"type": "progress", "request_id": request_id,
                             "page_count": page_count})
            feeder = asyncio.create_task(asyncio.to_thread(
                self._feed_pages, connection, images, sources, native_pages
            ))
            completed = False
            try:
                while True:
                    message = await asyncio.to_thread(self._receive)
                    if message["type"] == "event":
                        yield message["event"]
                    elif message["type"] == "rasterized":
                        extra = on_page_rasterized(message["page"]) if on_page_rasterized else {}
                        yield {"type": "rasterized", "page": message["page"], **(extra or {})}
                    elif message["type"] == "result":
                        await feeder
                        completed = True
                        yield {"type": "result", "result": message["result"]}
                        return
                    else:
                        raise RuntimeError(
                            f"OCR worker failed ({message.get('error_type', 'unknown')})"
                        )
            finally:
                if not completed:
                    process = self._process
                    logger.info(
                        "Terminating OCR inference process after request cancellation",
                        request_id=request_id,
                        worker_pid=process.pid if process is not None else None,
                    )
                    await asyncio.to_thread(self._stop_process)
                    if not feeder.done():
                        feeder.cancel()

    @staticmethod
    def _feed_pages(connection, images, source_indices, native_pages) -> None:
        try:
            for index, image in enumerate(images):
                source_index = source_indices[index] if index < len(source_indices) else 0
                native_page = native_pages[index] if index < len(native_pages) else None
                connection.send({"type": "page", "image": image,
                                 "source_file_index": source_index,
                                 "native_text_page": native_page})
            connection.send({"type": "end"})
        except (BrokenPipeError, EOFError, OSError, RuntimeError):
            return
        except Exception as error:
            try:
                connection.send({"type": "feed_error", "error_type": type(error).__name__})
            except (BrokenPipeError, EOFError, OSError):
                return


# Default engine process
_default_engine: Optional[OCRProcessWorker] = None


def get_engine() -> OCRProcessWorker:
    """
    Get the default OCR engine instance.

    Creates and initializes the engine if not already done.
    """
    global _default_engine

    if _default_engine is None:
        _default_engine = OCRProcessWorker()

    return _default_engine


def initialize_engine() -> None:
    """Initialize the default OCR engine (for startup preloading)."""
    engine = get_engine()
    engine.initialize()


def shutdown_engine() -> None:
    """Shutdown the default OCR engine."""
    global _default_engine

    if _default_engine is not None:
        _default_engine.shutdown()
        _default_engine = None

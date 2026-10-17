"""
OCR Engine Module

Core OCR processing with PaddleOCR 3.x: PP-OCRv5 text lines plus PP-DocLayout regions (figures, tables,
formulas) that the postprocessor ties to the questions. PaddleOCR-VL is available as an opt-in pipeline.
"""

import asyncio
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, replace
from typing import Optional, List, Dict, Any, Tuple
import hashlib
import uuid

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
from app.ocr.postprocessing import (
    OCRPostprocessor,
    TextBlock,
    ExtractedMetadata,
    ExtractedQuestion,
    ImageToUpload,
    UnmappedContent,
    Warning,
    detect_language,
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

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return {
            "status": self.status,
            "requestId": self.request_id,
            "processingTimeMs": self.processing_time_ms,
            "overallConfidence": round(self.overall_confidence, 3),
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
        self._initialized = False
        self._executor = ThreadPoolExecutor(max_workers=settings.workers)

        self.preprocessor = ImagePreprocessor(
            enable_deskew=settings.enable_deskew,
            enable_denoise=settings.enable_denoise,
            target_dpi=settings.target_dpi,
            min_confidence_threshold=settings.min_confidence_threshold,
        )

        self.postprocessor = OCRPostprocessor(
            min_confidence_threshold=settings.min_confidence_threshold,
        )

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
            fallback: Use the slower detector that sees the faint lines the fast one misses

        Returns:
            PaddleOCR 3.x results: one dict-like per image with rec_texts, rec_scores and rec_polys
        """
        self._ensure_initialized()

        if fallback:
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
        """Questions numbered 1, 4, 5 are missing two: the lines that carried the numbers were not read."""
        numbers = [int(q.number) for q in questions if str(q.number).isdigit()]
        return bool(numbers) and numbers != list(range(1, len(numbers) + 1))

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
        start_time = time.time()
        request_id = request_id or f"req-{uuid.uuid4().hex[:12]}"

        logger.info(
            "Processing image",
            request_id=request_id,
            page_index=page_index,
            image_shape=image.shape,
        )

        try:
            # Preprocess the image
            preprocessing_result = self.preprocessor.preprocess(image)
            processed_image = preprocessing_result.image

            logger.debug(
                "Preprocessing complete",
                request_id=request_id,
                preprocessing_applied=preprocessing_result.preprocessing_applied,
                rotation_angle=preprocessing_result.rotation_angle,
            )

            # Run OCR: text lines, and the layout that says where the figures are
            if settings.ocr_pipeline == "vl":
                text_blocks, layout = self._run_vl(processed_image, page_index)
            else:
                raw_results = self._run_ocr(processed_image)
                text_blocks = self._parse_ocr_results(raw_results, page_index)
                layout = self._detect_layout(processed_image, request_id)
            text_blocks, layout = self._mark_handwriting(image, processed_image, text_blocks, layout)

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

            # Postprocess to extract structured data. The boxes are in the preprocessed image's frame (deskew
            # can grow the canvas by a few pixels), so that is the size the crops are scaled from.
            def interpret(blocks: List[TextBlock], frame: np.ndarray = processed_image, regions=layout):
                return self.postprocessor.process(
                    blocks,
                    page_count=1,
                    page_dimensions={page_index: (int(frame.shape[1]), int(frame.shape[0]))},
                    source_file_indices={page_index: source_file_index},
                    layout_regions=None if regions is None else {page_index: regions},
                    formula_images=settings.ocr_formula_images,
                    recognize_formulas=settings.ocr_formula_latex,
                )

            metadata, questions, images_to_upload, unmapped, warnings = interpret(text_blocks)

            # A page the fast detector could not make questions of, or numbered with gaps, is read again with the
            # thorough detector. The second read is on the photo as it came: the denoise and deskew that help the
            # fast detector hide faint lines from the thorough one (the parcelar exam gave 3 of its 5 questions on
            # the preprocessed image, and finds the lines of the other two on the raw one). Its layout is taken
            # on the same image, so text, figures and the crop frame all share one set of coordinates.
            if (
                settings.ocr_pipeline != "vl"
                and settings.ocr_fallback_det_model_name
                and (len(questions) < settings.ocr_min_questions or self._numbering_has_gaps(questions))
            ):
                logger.info("Reading again with the fallback detector", request_id=request_id, questions=len(questions))
                retry_blocks = self._parse_ocr_results(self._run_ocr(image, fallback=True), page_index)
                retry_layout = self._detect_layout(image, request_id)
                retry_blocks, retry_layout = self._mark_handwriting(image, image, retry_blocks, retry_layout)
                retry = interpret(retry_blocks, image, retry_layout)
                if len(retry[1]) > len(questions) or (
                    len(retry[1]) == len(questions) and self._numbering_has_gaps(questions) and not self._numbering_has_gaps(retry[1])
                ):
                    text_blocks = retry_blocks
                    metadata, questions, images_to_upload, unmapped, warnings = retry

            # Calculate confidence
            overall_confidence = self._calculate_overall_confidence(
                text_blocks, metadata, questions
            )

            # Determine status
            if not text_blocks:
                status = "error"
            elif overall_confidence < 0.5:
                status = "partial"
            else:
                status = "success"

            processing_time_ms = int((time.time() - start_time) * 1000)

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
            processing_time_ms = int((time.time() - start_time) * 1000)

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
        """
        Process multiple images (multi-page document).

        Args:
            images: List of images as numpy arrays
            request_id: Optional request ID for tracking

        Returns:
            Combined OCRResult with data from all pages
        """
        start_time = time.time()
        request_id = request_id or f"req-{uuid.uuid4().hex[:12]}"

        logger.info(
            "Processing multiple images",
            request_id=request_id,
            num_images=len(images),
        )

        if not images:
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

        for idx, image in enumerate(images):
            result = self.process_image(
                image,
                page_index=idx,
                request_id=request_id,
                source_file_index=(source_file_indices[idx]
                                   if source_file_indices and idx < len(source_file_indices)
                                   else 0),
            )

            # ``process_image`` only marks ``error`` when it saw no text blocks at all, so a
            # page that is not an error did read text, even if it became no question.
            if result.status != "error":
                read_any_text = True

            # Merge results
            all_questions.extend(result.questions)
            all_images_to_upload.extend(result.images_to_upload)
            all_unmapped.extend(result.unmapped_content)
            all_warnings.extend(result.warnings)

            if result.document.has_tables:
                has_tables = True
            languages.append(result.document.main_language)

            # Use metadata from first page
            if idx == 0:
                metadata = result.metadata

        # Determine main language (most common)
        main_language = max(set(languages), key=languages.count) if languages else self.lang

        # Calculate overall confidence
        if all_questions:
            overall_confidence = sum(q.confidence for q in all_questions) / len(all_questions)
        else:
            overall_confidence = 0.0

        processing_time_ms = int((time.time() - start_time) * 1000)

        # Determine status. Text the parser could not turn into questions is still a reading:
        # the caller gets it with ``partial`` (207) instead of a 500 it cannot tell apart from a
        # service failure. Only a document with no text at all is an error.
        if not all_questions and not read_any_text:
            status = "error"
        elif not all_questions or overall_confidence < 0.5:
            status = "partial"
        else:
            status = "success"

        return OCRResult(
            status=status,
            request_id=request_id,
            processing_time_ms=processing_time_ms,
            overall_confidence=overall_confidence,
            document=DocumentInfo(
                page_count=len(images),
                main_language=main_language,
                has_tables=has_tables,
            ),
            metadata=metadata if 'metadata' in dir() else ExtractedMetadata(),
            questions=all_questions,
            images_to_upload=all_images_to_upload,
            unmapped_content=all_unmapped,
            warnings=all_warnings,
        )

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


# Default engine instance
_default_engine: Optional[OCREngine] = None


def get_engine() -> OCREngine:
    """
    Get the default OCR engine instance.

    Creates and initializes the engine if not already done.
    """
    global _default_engine

    if _default_engine is None:
        _default_engine = OCREngine(
            lang=settings.ocr_lang,
            use_gpu=settings.ocr_use_gpu,
            use_angle_cls=settings.ocr_use_angle_cls,
            det_model_dir=settings.ocr_det_model_dir,
            rec_model_dir=settings.ocr_rec_model_dir,
            cls_model_dir=settings.ocr_cls_model_dir,
            show_log=settings.ocr_show_log,
        )

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

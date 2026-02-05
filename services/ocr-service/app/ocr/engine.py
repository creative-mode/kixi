"""
OCR Engine Module

Core OCR processing using PaddleOCR-VL.
Provides the main engine for text extraction from images.
"""

import asyncio
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
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
        use_angle_cls: bool = True,
        det_model_dir: Optional[str] = None,
        rec_model_dir: Optional[str] = None,
        cls_model_dir: Optional[str] = None,
        show_log: bool = False,
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

        self._ocr = None
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
            lang=self.lang,
            use_gpu=self.use_gpu,
            use_angle_cls=self.use_angle_cls,
        )

        try:
            from paddleocr import PaddleOCR

            # Initialize PaddleOCR with configured options
            self._ocr = PaddleOCR(
                lang=self.lang,
                use_gpu=self.use_gpu,
                use_angle_cls=self.use_angle_cls,
                det_model_dir=self.det_model_dir,
                rec_model_dir=self.rec_model_dir,
                cls_model_dir=self.cls_model_dir,
                show_log=self.show_log,
                # Use PP-OCRv4 models for better accuracy
                ocr_version="PP-OCRv4",
                # Enable structure detection for better layout analysis
                structure_version="PP-StructureV2",
            )

            self._initialized = True
            logger.info("PaddleOCR engine initialized successfully")

        except Exception as e:
            logger.error("Failed to initialize PaddleOCR engine", error=str(e))
            raise RuntimeError(f"Failed to initialize OCR engine: {e}")

    def _ensure_initialized(self) -> None:
        """Ensure the OCR engine is initialized."""
        if not self._initialized:
            self.initialize()

    def _run_ocr(self, image: np.ndarray) -> List[Any]:
        """
        Run PaddleOCR on an image.

        Args:
            image: Image as numpy array (BGR format)

        Returns:
            Raw OCR results from PaddleOCR
        """
        self._ensure_initialized()

        if self._ocr is None:
            raise RuntimeError("OCR engine not initialized")

        result = self._ocr.ocr(image, cls=self.use_angle_cls)
        return result

    def _parse_ocr_results(
        self,
        raw_results: List[Any],
        page_index: int = 0,
    ) -> List[TextBlock]:
        """
        Parse raw PaddleOCR results into TextBlock objects.

        Args:
            raw_results: Raw results from PaddleOCR
            page_index: Index of the page being processed

        Returns:
            List of TextBlock objects
        """
        text_blocks = []

        if not raw_results or not raw_results[0]:
            return text_blocks

        for idx, line in enumerate(raw_results[0]):
            if not line:
                continue

            # PaddleOCR returns [[box], (text, confidence)]
            box = line[0]
            text_info = line[1]

            if len(text_info) >= 2:
                text = text_info[0]
                confidence = float(text_info[1])
            else:
                text = str(text_info)
                confidence = 0.5

            # Extract bounding box
            # box is [[x1,y1], [x2,y1], [x2,y2], [x1,y2]]
            if len(box) >= 4:
                x1 = int(min(p[0] for p in box))
                y1 = int(min(p[1] for p in box))
                x2 = int(max(p[0] for p in box))
                y2 = int(max(p[1] for p in box))
            else:
                x1, y1, x2, y2 = 0, 0, 0, 0

            text_blocks.append(TextBlock(
                text=text,
                confidence=confidence,
                bbox=(x1, y1, x2, y2),
                page_index=page_index,
                line_index=idx,
            ))

        return text_blocks

    def _detect_tables(self, raw_results: List[Any]) -> bool:
        """
        Detect if the document contains tables.

        Simple heuristic based on text alignment patterns.
        """
        if not raw_results or not raw_results[0]:
            return False

        # Check for aligned text blocks (potential table rows)
        y_positions = []
        for line in raw_results[0]:
            if line and len(line) >= 2:
                box = line[0]
                if len(box) >= 4:
                    y_center = (box[0][1] + box[2][1]) / 2
                    y_positions.append(y_center)

        if len(y_positions) < 3:
            return False

        # Check for repeated Y positions (table rows)
        y_positions.sort()
        similar_count = 0
        tolerance = 20  # pixels

        for i in range(1, len(y_positions)):
            if abs(y_positions[i] - y_positions[i-1]) < tolerance:
                similar_count += 1

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

            # Run OCR
            raw_results = self._run_ocr(processed_image)

            # Parse results into text blocks
            text_blocks = self._parse_ocr_results(raw_results, page_index)

            logger.debug(
                "OCR complete",
                request_id=request_id,
                num_text_blocks=len(text_blocks),
            )

            # Detect tables
            has_tables = self._detect_tables(raw_results)

            # Detect language
            full_text = " ".join(b.text for b in text_blocks)
            main_language = detect_language(full_text) if full_text else self.lang

            # Postprocess to extract structured data
            metadata, questions, images_to_upload, unmapped, warnings = self.postprocessor.process(
                text_blocks,
                page_count=1,
            )

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
                error=str(e),
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
                error_message=str(e),
            )

    async def process_image_async(
        self,
        image: np.ndarray,
        page_index: int = 0,
        request_id: Optional[str] = None,
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
        )

    def process_images(
        self,
        images: List[np.ndarray],
        request_id: Optional[str] = None,
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

        all_text_blocks = []
        all_questions = []
        all_images_to_upload = []
        all_unmapped = []
        all_warnings = []
        has_tables = False
        languages = []

        for idx, image in enumerate(images):
            result = self.process_image(image, page_index=idx, request_id=request_id)

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

        # Determine status
        if not all_questions:
            status = "error"
        elif overall_confidence < 0.5:
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
                unmapped_content=[],
                warnings=[],
                error_message=f"Failed to load image: {e}",
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

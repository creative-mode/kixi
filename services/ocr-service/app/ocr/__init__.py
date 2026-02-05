"""
OCR Module

Core OCR processing functionality using PaddleOCR-VL.

Components:
- engine: Main OCR engine with PaddleOCR integration
- preprocessing: Image preprocessing utilities
- postprocessing: OCR result parsing and structuring
"""

from .engine import (
    OCREngine,
    OCRResult,
    DocumentInfo,
    get_engine,
    initialize_engine,
    shutdown_engine,
)
from .preprocessing import (
    ImagePreprocessor,
    PreprocessingResult,
    load_image_from_bytes,
    load_image_from_pil,
    image_to_bytes,
    default_preprocessor,
)
from .postprocessing import (
    OCRPostprocessor,
    TextBlock,
    ExtractedMetadata,
    ExtractedQuestion,
    ExtractedOption,
    ImageToUpload,
    UnmappedContent,
    Warning,
    QuestionType,
    MetadataField,
    normalize_text,
    detect_language,
)

__all__ = [
    # Engine
    "OCREngine",
    "OCRResult",
    "DocumentInfo",
    "get_engine",
    "initialize_engine",
    "shutdown_engine",
    # Preprocessing
    "ImagePreprocessor",
    "PreprocessingResult",
    "load_image_from_bytes",
    "load_image_from_pil",
    "image_to_bytes",
    "default_preprocessor",
    # Postprocessing
    "OCRPostprocessor",
    "TextBlock",
    "ExtractedMetadata",
    "ExtractedQuestion",
    "ExtractedOption",
    "ImageToUpload",
    "UnmappedContent",
    "Warning",
    "QuestionType",
    "MetadataField",
    "normalize_text",
    "detect_language",
]

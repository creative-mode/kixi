"""
OCR Service API Routes

FastAPI endpoints for OCR text extraction from images.
Provides the main API for document processing.
"""

import time
import uuid
from typing import Optional, List

from fastapi import APIRouter, File, UploadFile, HTTPException, Depends, Form, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from app.config import settings
from app.ocr import (
    get_engine,
    OCREngine,
    OCRResult,
    load_image_from_bytes,
)
from app.api.pdf_handler import extract_images_from_pdf, is_pdf

import structlog

logger = structlog.get_logger(__name__)

# Create API router
router = APIRouter(prefix="/ocr", tags=["OCR"])


# Request/Response Models
class OCRContext(BaseModel):
    """Optional context for OCR processing."""
    language_hint: Optional[str] = Field(
        default=None,
        alias="languageHint",
        description="Hint for primary document language (pt, en, etc.)"
    )
    detect_tables: Optional[bool] = Field(
        default=True,
        alias="detectTables",
        description="Whether to detect tables in the document"
    )
    enable_preprocessing: Optional[bool] = Field(
        default=True,
        alias="enablePreprocessing",
        description="Whether to apply image preprocessing"
    )


class HealthResponse(BaseModel):
    """Health check response."""
    status: str
    service: str
    version: str
    initialized: bool
    message: Optional[str] = None


class ExtractResponse(BaseModel):
    """OCR extraction response wrapper."""
    status: str
    request_id: str = Field(alias="requestId")
    processing_time_ms: int = Field(alias="processingTimeMs")
    overall_confidence: float = Field(alias="overallConfidence")
    document: dict
    metadata: dict
    questions: List[dict]
    unmapped_content: List[dict] = Field(alias="unmappedContent")
    warnings: List[dict]
    error_message: Optional[str] = Field(default=None, alias="errorMessage")

    class Config:
        populate_by_name = True


# Dependency for OCR engine
def get_ocr_engine() -> OCREngine:
    """Dependency to get the OCR engine instance."""
    return get_engine()


# Validation helpers
def validate_file_type(filename: str) -> str:
    """Validate file type and return the type."""
    if not filename:
        raise HTTPException(status_code=400, detail="Filename is required")

    filename_lower = filename.lower()

    if filename_lower.endswith(('.jpg', '.jpeg')):
        return 'jpeg'
    elif filename_lower.endswith('.png'):
        return 'png'
    elif filename_lower.endswith('.pdf'):
        return 'pdf'
    elif filename_lower.endswith('.webp'):
        return 'webp'
    elif filename_lower.endswith(('.tif', '.tiff')):
        return 'tiff'
    elif filename_lower.endswith('.bmp'):
        return 'bmp'
    else:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported file type. Supported: jpg, jpeg, png, pdf, webp, tiff, bmp"
        )


def validate_file_size(content: bytes, max_size_mb: float = None) -> None:
    """Validate file size."""
    max_size = max_size_mb or settings.max_image_size_mb
    max_bytes = int(max_size * 1024 * 1024)

    if len(content) > max_bytes:
        raise HTTPException(
            status_code=400,
            detail=f"File size exceeds maximum allowed size of {max_size}MB"
        )


def _status_code_for_result(status: str) -> int:
    """Map an OCR result status to the HTTP status used by the API."""
    if status == "success":
        return 200
    if status == "partial":
        return 207
    return 500


# Routes
@router.get("/health", response_model=HealthResponse)
async def health_check(engine: OCREngine = Depends(get_ocr_engine)) -> HealthResponse:
    """
    Health check endpoint.

    Returns the status of the OCR service and engine initialization state.
    """
    health = engine.health_check()

    return HealthResponse(
        status=health.get("status", "unknown"),
        service=settings.service_name,
        version=settings.service_version,
        initialized=health.get("initialized", False),
        message=health.get("message"),
    )


@router.post("/v1/extract")
async def extract_text(
    images: List[UploadFile] = File(..., description="Image files to process"),
    context: Optional[str] = Form(default=None, description="JSON context string"),
    engine: OCREngine = Depends(get_ocr_engine),
) -> JSONResponse:
    """
    Extract text from uploaded images.

    This is the main OCR endpoint that processes one or more images
    and returns structured data including metadata, questions, and options.

    **Supported formats:** JPEG, PNG, PDF, WebP, TIFF, BMP

    **Request:**
    - `images[]`: Image files (multipart/form-data)
    - `context`: Optional JSON string with processing hints

    **Response:**
    - Structured JSON with extracted metadata, questions, and confidence scores
    """
    request_id = f"req-{uuid.uuid4().hex[:12]}"
    start_time = time.time()

    logger.info(
        "OCR extraction request received",
        request_id=request_id,
        num_files=len(images),
    )

    # Validate number of images
    if len(images) > settings.max_images_per_request:
        raise HTTPException(
            status_code=400,
            detail=f"Maximum {settings.max_images_per_request} images allowed per request"
        )

    if not images:
        raise HTTPException(status_code=400, detail="At least one image is required")

    # Parse context if provided
    ocr_context = None
    if context:
        try:
            import json
            context_data = json.loads(context)
            ocr_context = OCRContext(**context_data)
        except Exception as e:
            logger.warning(
                "Failed to parse context",
                request_id=request_id,
                error=str(e),
            )

    # Process images
    all_images = []

    for upload_file in images:
        try:
            # Validate file type
            file_type = validate_file_type(upload_file.filename)

            # Read file content
            content = await upload_file.read()

            # Validate file size
            validate_file_size(content)

            logger.debug(
                "Processing file",
                request_id=request_id,
                filename=upload_file.filename,
                file_type=file_type,
                size_bytes=len(content),
            )

            # Handle PDF files
            if file_type == 'pdf' or is_pdf(content):
                pdf_images = extract_images_from_pdf(content)
                all_images.extend(pdf_images)
                logger.debug(
                    "Extracted images from PDF",
                    request_id=request_id,
                    num_pages=len(pdf_images),
                )
            else:
                # Load regular image
                image = load_image_from_bytes(content)
                all_images.append(image)

        except HTTPException:
            raise
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        except Exception as e:
            logger.error(
                "Failed to process uploaded file",
                request_id=request_id,
                filename=upload_file.filename,
                error=str(e),
            )
            raise HTTPException(
                status_code=400,
                detail=f"Failed to process file '{upload_file.filename}': {str(e)}"
            )

    if not all_images:
        raise HTTPException(status_code=400, detail="No valid images could be extracted")

    # Run OCR
    try:
        if len(all_images) == 1:
            result = await engine.process_image_async(
                all_images[0],
                page_index=0,
                request_id=request_id,
            )
        else:
            result = await engine.process_images_async(
                all_images,
                request_id=request_id,
            )

        processing_time = int((time.time() - start_time) * 1000)

        logger.info(
            "OCR extraction complete",
            request_id=request_id,
            status=result.status,
            processing_time_ms=processing_time,
            num_questions=len(result.questions),
            overall_confidence=result.overall_confidence,
        )

        return JSONResponse(
            content=result.to_dict(),
            status_code=_status_code_for_result(result.status),
        )

    except Exception as e:
        logger.error(
            "OCR processing failed",
            request_id=request_id,
            error=str(e),
        )
        raise HTTPException(
            status_code=500,
            detail=f"OCR processing failed: {str(e)}"
        )


@router.post("/v1/extract/simple")
async def extract_text_simple(
    image: UploadFile = File(..., description="Single image file to process"),
    engine: OCREngine = Depends(get_ocr_engine),
) -> JSONResponse:
    """
    Simplified text extraction for a single image.

    This endpoint is optimized for quick single-image processing
    without additional context or configuration.

    **Supported formats:** JPEG, PNG, WebP, BMP
    """
    request_id = f"req-{uuid.uuid4().hex[:12]}"

    logger.info(
        "Simple OCR extraction request received",
        request_id=request_id,
        filename=image.filename,
    )

    # Validate file type
    file_type = validate_file_type(image.filename)

    if file_type == 'pdf':
        raise HTTPException(
            status_code=400,
            detail="PDF files are not supported in simple mode. Use /v1/extract instead."
        )

    try:
        # Read and validate
        content = await image.read()
        validate_file_size(content)

        # Load image
        img = load_image_from_bytes(content)

        # Process
        result = await engine.process_image_async(
            img,
            page_index=0,
            request_id=request_id,
        )

        return JSONResponse(
            content=result.to_dict(),
            status_code=_status_code_for_result(result.status),
        )

    except HTTPException:
        raise
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        logger.error(
            "Simple OCR extraction failed",
            request_id=request_id,
            error=str(e),
        )
        raise HTTPException(
            status_code=500,
            detail=f"OCR processing failed: {str(e)}"
        )


@router.get("/v1/status/{request_id}")
async def get_request_status(
    request_id: str,
) -> JSONResponse:
    """
    Get the status of a previous OCR request.

    Note: This is a placeholder for future async processing support.
    Currently, all requests are processed synchronously.
    """
    # Placeholder for future async job tracking
    return JSONResponse(
        content={
            "requestId": request_id,
            "status": "unknown",
            "message": "Async job tracking not yet implemented. All requests are processed synchronously.",
        },
        status_code=501,
    )


@router.get("/v1/supported-languages")
async def get_supported_languages() -> JSONResponse:
    """
    Get list of supported OCR languages.
    """
    # PaddleOCR supported languages
    languages = [
        {"code": "pt", "name": "Portuguese", "primary": True},
        {"code": "en", "name": "English", "primary": True},
        {"code": "es", "name": "Spanish", "primary": False},
        {"code": "fr", "name": "French", "primary": False},
        {"code": "de", "name": "German", "primary": False},
        {"code": "it", "name": "Italian", "primary": False},
        {"code": "ch", "name": "Chinese", "primary": False},
        {"code": "japan", "name": "Japanese", "primary": False},
        {"code": "korean", "name": "Korean", "primary": False},
        {"code": "ar", "name": "Arabic", "primary": False},
        {"code": "latin", "name": "Latin", "primary": False},
    ]

    return JSONResponse(
        content={
            "languages": languages,
            "default": settings.ocr_lang,
        }
    )

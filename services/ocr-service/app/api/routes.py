"""
OCR Service API Routes

FastAPI endpoints for OCR text extraction from images.
Provides the main API for document processing.
"""

import io
import json
import time
from typing import Optional, List

from fastapi import APIRouter, File, UploadFile, HTTPException, Depends, Form, Query, Request, Header
from fastapi.responses import JSONResponse, StreamingResponse
from PIL import Image
from pydantic import BaseModel, Field

from app.config import settings
from app.ocr import (
    get_engine,
    OCRProcessWorker,
    OCRPageInputError,
    OCRResult,
    load_image_from_bytes,
)
from app.api.pdf_handler import (
    extract_images_from_pdf,
    extract_text_pages_from_pdf,
    get_pdf_info,
    is_pdf,
    validate_pdf,
)
from app.api.security import require_ocr_api_key

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
def get_ocr_engine() -> OCRProcessWorker:
    """Dependency to get the OCR engine instance."""
    return get_engine()


# Validation helpers
# What a filename extension claims a file is. A hint only, never the decision (#291).
EXTENSION_FILE_TYPES = {
    '.jpg': 'jpeg',
    '.jpeg': 'jpeg',
    '.png': 'png',
    '.pdf': 'pdf',
    '.webp': 'webp',
    '.tif': 'tiff',
    '.tiff': 'tiff',
    '.bmp': 'bmp',
}

# What Pillow reads inside the bytes, normalised to the names above.
PILLOW_FILE_TYPES = {
    'JPEG': 'jpeg',
    'PNG': 'png',
    'WEBP': 'webp',
    'TIFF': 'tiff',
    'BMP': 'bmp',
}

UNSUPPORTED_FILE_TYPE = (
    "Unsupported file type. Supported: jpg, jpeg, png, pdf, webp, tiff, bmp"
)


def file_type_from_name(filename: Optional[str]) -> Optional[str]:
    """The type the name claims, or None when the extension is missing or unknown."""
    if not filename:
        return None

    filename_lower = filename.lower()
    for extension, file_type in EXTENSION_FILE_TYPES.items():
        if filename_lower.endswith(extension):
            return file_type
    return None


def file_type_from_content(content: bytes) -> Optional[str]:
    """The type the bytes really are: PDF by magic bytes, images as Pillow reads them."""
    if is_pdf(content):
        return 'pdf'

    try:
        with Image.open(io.BytesIO(content)) as probe:
            return PILLOW_FILE_TYPES.get((probe.format or '').upper())
    except Exception:
        return None


def resolve_file_type(filename: Optional[str], content: bytes) -> str:
    """Decide the type from the content, with the name as a fallback (#291).

    A phone photo called ``IMG_20250116`` has no extension at all; the bytes are
    still a JPEG, and that is what decides. The name is only used when Pillow
    cannot name the content, so a valid image is never refused over its name and
    an unsupported file is still refused.
    """
    detected = file_type_from_content(content)
    if detected:
        return detected

    declared = file_type_from_name(filename)
    if declared:
        return declared

    raise HTTPException(status_code=400, detail=UNSUPPORTED_FILE_TYPE)


def image_pixel_count(content: bytes) -> int:
    """Read image dimensions without decoding the raster into an array."""
    try:
        with Image.open(io.BytesIO(content)) as probe:
            width, height = probe.size
    except Exception as exc:
        raise ValueError("Failed to read image dimensions") from exc

    pixels = int(width) * int(height)
    if width < 1 or height < 1 or pixels > settings.max_image_pixels:
        raise ValueError("Image exceeds the maximum decoded pixel count")
    return pixels



def validate_file_size(content: bytes, max_size_mb: float = None) -> None:
    """Validate file size."""
    max_size = max_size_mb or settings.max_image_size_mb
    max_bytes = int(max_size * 1024 * 1024)

    if len(content) > max_bytes:
        raise HTTPException(
            status_code=400,
            detail=f"File size exceeds maximum allowed size of {max_size}MB"
        )


async def read_bounded_upload(
    upload_file: UploadFile, remaining_request_bytes: int = None
) -> bytes:
    """Read within both the per-file and aggregate request limits."""
    if remaining_request_bytes is None:
        remaining_request_bytes = settings.max_upload_size_bytes
    max_bytes = min(settings.max_image_size_bytes, remaining_request_bytes)
    content = await upload_file.read(max_bytes + 1)
    if len(content) > max_bytes:
        if remaining_request_bytes < settings.max_image_size_bytes:
            raise HTTPException(
                status_code=400,
                detail=(
                    "Total upload size exceeds maximum allowed size of "
                    f"{settings.max_upload_size_mb}MB"
                ),
            )
        validate_file_size(content)
    return content


def _status_code_for_result(status: str) -> int:
    """Map an OCR result status to the HTTP status used by the API."""
    if status == "success":
        return 200
    if status == "partial":
        return 207
    return 500


# Routes
@router.get("/health", response_model=HealthResponse)
async def health_check(engine: OCRProcessWorker = Depends(get_ocr_engine)) -> HealthResponse:
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
    request: Request,
    images: List[UploadFile] = File(..., description="Image files to process"),
    context: Optional[str] = Form(default=None, description="JSON context string"),
    _: None = Depends(require_ocr_api_key),
    engine: OCRProcessWorker = Depends(get_ocr_engine),
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
    request_id = request.state.request_id
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
                error_type=type(e).__name__,
            )

    # Keep compressed uploads, but decode only the current page. A PDF's rendered
    # rasters are much larger than its upload and should not all remain resident.
    prepared_files = []
    source_file_indices = []
    page_count = 0
    total_upload_bytes = 0
    total_rendered_pixels = 0

    for source_file_index, upload_file in enumerate(images):
        try:
            # Read the bytes first: the content decides the type, not the name (#291).
            remaining_request_bytes = settings.max_upload_size_bytes - total_upload_bytes
            content = await read_bounded_upload(upload_file, remaining_request_bytes)
            total_upload_bytes += len(content)

            file_type = resolve_file_type(upload_file.filename, content)

            logger.debug(
                "Processing file",
                request_id=request_id,
                file_type=file_type,
                size_bytes=len(content),
            )

            # Handle PDF files
            if file_type == 'pdf':
                remaining_pixels = settings.max_ocr_pixels_per_request - total_rendered_pixels
                file_rendered_pixels = validate_pdf(
                    content,
                    max_pages=settings.max_pdf_pages,
                    max_total_pixels=remaining_pixels,
                )
                file_page_count = int(get_pdf_info(content).get("page_count") or 0)
                if file_page_count < 1:
                    raise ValueError("Invalid PDF page count")
            else:
                file_page_count = 1
                file_rendered_pixels = image_pixel_count(content)
                if file_rendered_pixels > settings.max_ocr_pixels_per_request - total_rendered_pixels:
                    raise ValueError("Request exceeds the maximum total rendered pixel count")

            if page_count + file_page_count > settings.max_ocr_pages_per_request:
                raise HTTPException(
                    status_code=400,
                    detail=(
                        "Maximum "
                        f"{settings.max_ocr_pages_per_request} pages allowed per request"
                    ),
                )
            page_count += file_page_count
            total_rendered_pixels += file_rendered_pixels
            prepared_files.append((source_file_index, file_type, content, file_page_count))

        except HTTPException:
            raise
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid uploaded file")
        except Exception as e:
            logger.error(
                "Failed to process uploaded file",
                request_id=request_id,
                error_type=type(e).__name__,
            )
            raise HTTPException(
                status_code=400,
                detail="Failed to process uploaded file"
            )

    if page_count == 0:
        raise HTTPException(status_code=400, detail="No valid images could be extracted")

    rasterization_ms = 0.0

    def rasterized_pages():
        nonlocal rasterization_ms
        rasterized_pixels = 0
        for source_file_index, file_type, content, file_page_count in prepared_files:
            for local_page in range(1, file_page_count + 1):
                remaining_pixels = settings.max_ocr_pixels_per_request - rasterized_pixels
                raster_started = time.perf_counter()
                try:
                    if file_type == "pdf":
                        pages = extract_images_from_pdf(
                            content,
                            dpi=settings.target_dpi,
                            first_page=local_page,
                            last_page=local_page,
                            max_total_pixels=remaining_pixels,
                        )
                        if len(pages) != 1:
                            raise ValueError("PDF page rendering returned an unexpected page count")
                        image = pages[0]
                    else:
                        image = load_image_from_bytes(content, max_pixels=remaining_pixels)
                except Exception as exc:
                    raise HTTPException(status_code=400, detail="Failed to process uploaded file") from exc

                rasterization_ms += (time.perf_counter() - raster_started) * 1000
                rasterized_pixels += int(image.shape[0]) * int(image.shape[1])
                source_file_indices.append(source_file_index)
                yield image

    # Run OCR
    try:
        if page_count == 1:
            image = next(rasterized_pages())
            result = await engine.process_image_async(
                image,
                page_index=0,
                request_id=request_id,
                source_file_index=source_file_indices[0],
            )
        else:
            result = None
            async for update in engine.process_images_progress_async(
                rasterized_pages(),
                request_id=request_id,
                source_file_indices=source_file_indices,
                page_count=page_count,
            ):
                if update.get("type") == "result":
                    result = update["result"]
            if result is None:
                raise RuntimeError("OCR processing returned no result")

        if page_count > 1:
            result.processing_time_ms = max(0, result.processing_time_ms - int(rasterization_ms))
            if result.timings and "totalMs" in result.timings:
                result.timings = {
                    **result.timings,
                    "totalMs": round(max(0.0, result.timings["totalMs"] - rasterization_ms), 1),
                }

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

    except OCRPageInputError as exc:
        raise HTTPException(
            status_code=400,
            detail="Failed to process uploaded file",
        ) from exc
    except HTTPException:
        raise
    except Exception as e:
        logger.error(
            "OCR processing failed",
            request_id=request_id,
            error_type=type(e).__name__,
        )
        raise HTTPException(
            status_code=500,
            detail="OCR processing failed"
        )


@router.post("/v1/extract/simple")
async def extract_text_simple(
    request: Request,
    image: UploadFile = File(..., description="Single image file to process"),
    _: None = Depends(require_ocr_api_key),
    engine: OCRProcessWorker = Depends(get_ocr_engine),
) -> JSONResponse:
    """
    Simplified text extraction for a single image.

    This endpoint is optimized for quick single-image processing
    without additional context or configuration.

    **Supported formats:** JPEG, PNG, WebP, BMP
    """
    request_id = request.state.request_id

    logger.info(
        "Simple OCR extraction request received",
        request_id=request_id,
    )

    # Read and validate
    try:
        content = await read_bounded_upload(image)
        file_type = resolve_file_type(image.filename, content)

        if file_type == 'pdf':
            raise HTTPException(
                status_code=400,
                detail="PDF files are not supported in simple mode. Use /v1/extract instead."
            )

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
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid image")
    except Exception as e:
        logger.error(
            "Simple OCR extraction failed",
            request_id=request_id,
            error_type=type(e).__name__,
        )
        raise HTTPException(
            status_code=500,
            detail="OCR processing failed"
        )


@router.post("/v1/extract/stream")
async def extract_text_stream(
    request: Request,
    images: List[UploadFile] = File(..., description="Image files or PDFs to process"),
    upload_ms: Optional[float] = Header(default=None, alias="X-OCR-Upload-Ms"),
    _: None = Depends(require_ocr_api_key),
    engine: OCRProcessWorker = Depends(get_ocr_engine),
) -> StreamingResponse:
    """Stream request-scoped, measured progress and the final extraction as SSE."""
    request_id = request.state.request_id
    request_started = time.perf_counter()
    if not images:
        raise HTTPException(status_code=400, detail="At least one image is required")
    if len(images) > settings.max_images_per_request:
        raise HTTPException(
            status_code=400,
            detail=f"Maximum {settings.max_images_per_request} images allowed per request",
        )

    prepared_files = []
    page_count = 0
    total_upload_bytes = 0
    total_rendered_pixels = 0
    preparation_started = time.perf_counter()
    for source_file_index, upload_file in enumerate(images):
        try:
            content = await read_bounded_upload(
                upload_file,
                settings.max_upload_size_bytes - total_upload_bytes,
            )
            total_upload_bytes += len(content)
            file_type = resolve_file_type(upload_file.filename, content)
            if file_type == "pdf":
                remaining_pixels = settings.max_ocr_pixels_per_request - total_rendered_pixels
                file_rendered_pixels = validate_pdf(
                    content,
                    max_pages=settings.max_pdf_pages,
                    max_total_pixels=remaining_pixels,
                )
                file_page_count = int(get_pdf_info(content).get("page_count") or 0)
                if file_page_count < 1:
                    raise HTTPException(status_code=400, detail="Invalid PDF page count")
            else:
                file_page_count = 1
                file_rendered_pixels = image_pixel_count(content)
                if file_rendered_pixels > settings.max_ocr_pixels_per_request - total_rendered_pixels:
                    raise ValueError("Request exceeds the maximum total rendered pixel count")
            if page_count + file_page_count > settings.max_ocr_pages_per_request:
                raise HTTPException(
                    status_code=400,
                    detail=f"Maximum {settings.max_ocr_pages_per_request} pages allowed per request",
                )
            page_count += file_page_count
            total_rendered_pixels += file_rendered_pixels
            prepared_files.append((source_file_index, file_type, content, file_page_count))
        except HTTPException:
            raise
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid uploaded file")
        except Exception as exc:
            logger.warning(
                "Failed to prepare streamed OCR upload",
                request_id=request_id,
                error_type=type(exc).__name__,
            )
            raise HTTPException(status_code=400, detail="Failed to process uploaded file")

    if page_count == 0:
        raise HTTPException(status_code=400, detail="No valid images could be extracted")

    preparation_ms = (time.perf_counter() - preparation_started) * 1000
    upload_ms = upload_ms if upload_ms is not None and 0 <= upload_ms <= 180_000 else None

    async def events():
        native_text_pages = []
        source_file_indices = []
        rasterization_ms = 0.0
        rasterized_pixels = 0
        yield _sse_event("prepared", {
            "requestId": request_id,
            "pageCount": page_count,
            "timings": {
                **({"uploadMs": round(upload_ms, 1)} if upload_ms is not None else {}),
                "preparationMs": round(preparation_ms, 1),
                "rasterizationMs": 0.0,
            },
        })
        try:
            def rasterized_pages():
                nonlocal rasterization_ms, rasterized_pixels
                global_page = 0
                for source_file_index, file_type, content, file_page_count in prepared_files:
                    for local_page in range(1, file_page_count + 1):
                        raster_started = time.perf_counter()
                        remaining_pixels = settings.max_ocr_pixels_per_request - rasterized_pixels
                        if file_type == "pdf":
                            pages = extract_images_from_pdf(
                                content,
                                first_page=local_page,
                                last_page=local_page,
                                max_total_pixels=remaining_pixels,
                            )
                            if len(pages) != 1:
                                raise ValueError("PDF page rendering returned an unexpected page count")
                            text_pages = extract_text_pages_from_pdf(
                                content,
                                first_page=local_page,
                                last_page=local_page,
                            )
                            image = pages[0]
                            native_text_pages.append(text_pages[0] if len(text_pages) == 1 else None)
                        else:
                            image = load_image_from_bytes(content, max_pixels=remaining_pixels)
                            native_text_pages.append(None)
                        rasterized_pixels += int(image.shape[0]) * int(image.shape[1])
                        source_file_indices.append(source_file_index)
                        rasterization_ms += (time.perf_counter() - raster_started) * 1000
                        global_page += 1
                        yield image

            def report_rasterized(page: int) -> dict:
                # Progress events are emitted by the engine as soon as this page is ready,
                # before OCR starts; only one rasterized page is retained at a time.
                return {"timings": {"rasterizationMs": round(rasterization_ms, 1)}}

            async for update in engine.process_images_progress_async(
                rasterized_pages(),
                request_id=request_id,
                source_file_indices=source_file_indices,
                native_text_pages=native_text_pages,
                page_count=page_count,
                on_page_rasterized=report_rasterized,
            ):
                if update.get("type") == "rasterized":
                    yield _sse_event("rasterized", update)
                    continue
                if "type" not in update:
                    yield _sse_event("progress", update)
                    continue
                result = update["result"]
                assembly_started = time.perf_counter()
                payload = result.to_dict()
                response_assembly_ms = (time.perf_counter() - assembly_started) * 1000
                payload["timings"] = {
                    **(payload.get("timings") or {}),
                    "preparationMs": round(preparation_ms, 1),
                    "rasterizationMs": round(rasterization_ms, 1),
                    "responseAssemblyMs": round(response_assembly_ms, 1),
                    "totalMs": round((time.perf_counter() - request_started) * 1000, 1),
                }
                yield _sse_event("result", payload)
        except Exception as exc:
            logger.error(
                "Streamed OCR processing failed",
                request_id=request_id,
                error_type=type(exc).__name__,
            )
            yield _sse_event("error", {"message": "OCR processing failed"})

    return StreamingResponse(
        events(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache, no-transform",
            "X-Accel-Buffering": "no",
            "X-Request-ID": request_id,
        },
    )


def _sse_event(name: str, payload: dict) -> str:
    return f"event: {name}\ndata: {json.dumps(payload, ensure_ascii=False, separators=(',', ':'))}\n\n"


@router.get("/v1/status/{request_id}")
async def get_request_status(
    request_id: str,
) -> JSONResponse:
    """Jobs are request-scoped SSE streams; no durable status is retained."""
    return JSONResponse(
        content={
            "requestId": request_id,
            "status": "not_trackable",
            "message": "Progress is available only on POST /ocr/v1/extract/stream; no job state is persisted.",
        },
        status_code=410,
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

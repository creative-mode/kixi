"""
PDF Handler Module

Utilities for extracting images from PDF files for OCR processing.
Supports multi-page PDFs and various PDF rendering qualities.
"""

import io
from typing import List, Optional

import numpy as np
from PIL import Image

import structlog

logger = structlog.get_logger(__name__)


def is_pdf(content: bytes) -> bool:
    """
    Check if content is a PDF file based on magic bytes.

    Args:
        content: File content as bytes

    Returns:
        True if content is a PDF file
    """
    return content[:4] == b'%PDF'


def extract_images_from_pdf(
    pdf_content: bytes,
    dpi: int = 300,
    first_page: Optional[int] = None,
    last_page: Optional[int] = None,
) -> List[np.ndarray]:
    """
    Extract images from PDF pages.

    Uses PyMuPDF (fitz) for PDF rendering. Falls back to pdf2image
    if PyMuPDF is not available.

    Args:
        pdf_content: PDF file content as bytes
        dpi: Resolution for rendering (default 300 DPI)
        first_page: First page to extract (1-indexed, optional)
        last_page: Last page to extract (1-indexed, optional)

    Returns:
        List of images as numpy arrays (BGR format for OpenCV)

    Raises:
        ValueError: If the PDF cannot be processed
    """
    images = []

    try:
        # Try PyMuPDF first (faster and more reliable)
        images = _extract_with_pymupdf(pdf_content, dpi, first_page, last_page)
    except ImportError:
        logger.warning("PyMuPDF not available, falling back to pdf2image")
        try:
            images = _extract_with_pdf2image(pdf_content, dpi, first_page, last_page)
        except ImportError:
            raise ValueError(
                "No PDF processing library available. "
                "Install either PyMuPDF (fitz) or pdf2image with poppler."
            )
    except Exception as e:
        logger.error("PDF extraction failed", error_type=type(e).__name__)
        raise ValueError("Failed to extract images from PDF")

    if not images:
        raise ValueError("No pages could be extracted from PDF")

    logger.info(
        "PDF extraction complete",
        num_pages=len(images),
        dpi=dpi,
    )

    return images


def _extract_with_pymupdf(
    pdf_content: bytes,
    dpi: int = 300,
    first_page: Optional[int] = None,
    last_page: Optional[int] = None,
) -> List[np.ndarray]:
    """
    Extract images using PyMuPDF (fitz).

    Args:
        pdf_content: PDF file content as bytes
        dpi: Resolution for rendering
        first_page: First page to extract (1-indexed)
        last_page: Last page to extract (1-indexed)

    Returns:
        List of images as numpy arrays (BGR format)
    """
    import fitz  # PyMuPDF

    images = []

    # Open PDF from bytes
    pdf_document = fitz.open(stream=pdf_content, filetype="pdf")

    try:
        # Calculate page range
        total_pages = len(pdf_document)
        start_page = (first_page - 1) if first_page else 0
        end_page = last_page if last_page else total_pages

        # Ensure valid range
        start_page = max(0, min(start_page, total_pages - 1))
        end_page = max(1, min(end_page, total_pages))

        # Calculate zoom factor for desired DPI
        # Default PDF resolution is 72 DPI
        zoom = dpi / 72.0
        matrix = fitz.Matrix(zoom, zoom)

        for page_num in range(start_page, end_page):
            page = pdf_document[page_num]

            # Render page to pixmap
            pixmap = page.get_pixmap(matrix=matrix, alpha=False)

            # Convert to PIL Image
            img_data = pixmap.tobytes("ppm")
            pil_image = Image.open(io.BytesIO(img_data))

            # Convert to numpy array (RGB)
            img_array = np.array(pil_image)

            # Convert RGB to BGR for OpenCV compatibility
            if len(img_array.shape) == 3 and img_array.shape[2] == 3:
                img_array = img_array[:, :, ::-1].copy()

            images.append(img_array)

            logger.debug(
                "Extracted PDF page",
                page_num=page_num + 1,
                size=f"{pixmap.width}x{pixmap.height}",
            )

    finally:
        pdf_document.close()

    return images


def _extract_with_pdf2image(
    pdf_content: bytes,
    dpi: int = 300,
    first_page: Optional[int] = None,
    last_page: Optional[int] = None,
) -> List[np.ndarray]:
    """
    Extract images using pdf2image (requires poppler).

    Args:
        pdf_content: PDF file content as bytes
        dpi: Resolution for rendering
        first_page: First page to extract (1-indexed)
        last_page: Last page to extract (1-indexed)

    Returns:
        List of images as numpy arrays (BGR format)
    """
    from pdf2image import convert_from_bytes

    images = []

    # Convert PDF to images
    pil_images = convert_from_bytes(
        pdf_content,
        dpi=dpi,
        first_page=first_page,
        last_page=last_page,
        fmt="RGB",
    )

    for idx, pil_image in enumerate(pil_images):
        # Convert to numpy array (RGB)
        img_array = np.array(pil_image)

        # Convert RGB to BGR for OpenCV compatibility
        if len(img_array.shape) == 3 and img_array.shape[2] == 3:
            img_array = img_array[:, :, ::-1].copy()

        images.append(img_array)

        logger.debug(
            "Extracted PDF page",
            page_num=idx + 1,
            size=f"{pil_image.width}x{pil_image.height}",
        )

    return images


def get_pdf_info(pdf_content: bytes) -> dict:
    """
    Get information about a PDF file.

    Args:
        pdf_content: PDF file content as bytes

    Returns:
        Dictionary with PDF metadata
    """
    info = {
        "page_count": 0,
        "title": None,
        "author": None,
        "subject": None,
        "creator": None,
        "encrypted": False,
    }

    try:
        import fitz

        pdf_document = fitz.open(stream=pdf_content, filetype="pdf")

        try:
            info["page_count"] = len(pdf_document)
            info["encrypted"] = pdf_document.is_encrypted

            # Get metadata
            metadata = pdf_document.metadata
            if metadata:
                info["title"] = metadata.get("title")
                info["author"] = metadata.get("author")
                info["subject"] = metadata.get("subject")
                info["creator"] = metadata.get("creator")

        finally:
            pdf_document.close()

    except ImportError:
        # Fallback: just check if it's a valid PDF
        if is_pdf(pdf_content):
            info["page_count"] = -1  # Unknown
        else:
            raise ValueError("Invalid PDF file")

    except Exception as e:
        logger.error("Failed to get PDF info", error_type=type(e).__name__)
        raise ValueError("Failed to read PDF")

    return info


def validate_pdf(pdf_content: bytes, max_pages: int = 50) -> None:
    """
    Validate a PDF file for OCR processing.

    Args:
        pdf_content: PDF file content as bytes
        max_pages: Maximum allowed pages

    Raises:
        ValueError: If the PDF is invalid or exceeds limits
    """
    if not is_pdf(pdf_content):
        raise ValueError("File is not a valid PDF")

    try:
        info = get_pdf_info(pdf_content)

        if info["encrypted"]:
            raise ValueError("Encrypted PDFs are not supported")

        if info["page_count"] > max_pages:
            raise ValueError(
                f"PDF has {info['page_count']} pages, "
                f"maximum allowed is {max_pages}"
            )

    except ValueError:
        raise
    except Exception as e:
        raise ValueError(f"Failed to validate PDF: {e}")

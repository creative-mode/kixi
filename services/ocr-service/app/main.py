"""
OCR Service - Main Application Entry Point

FastAPI application for OCR text extraction using PaddleOCR-VL.
Provides endpoints for extracting structured text from images and PDFs.
"""

import sys
from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from app.config import settings
from app.api.routes import router as ocr_router
from app.ocr import initialize_engine, shutdown_engine

# Configure structured logging
structlog.configure(
    processors=[
        structlog.stdlib.filter_by_level,
        structlog.stdlib.add_logger_name,
        structlog.stdlib.add_log_level,
        structlog.stdlib.PositionalArgumentsFormatter(),
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
        structlog.processors.UnicodeDecoder(),
        structlog.processors.JSONRenderer() if settings.log_format == "json" else structlog.dev.ConsoleRenderer(),
    ],
    wrapper_class=structlog.stdlib.BoundLogger,
    context_class=dict,
    logger_factory=structlog.stdlib.LoggerFactory(),
    cache_logger_on_first_use=True,
)

logger = structlog.get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    Application lifespan handler.

    Manages startup and shutdown of the OCR engine.
    """
    # Startup
    logger.info(
        "Starting OCR Service",
        service=settings.service_name,
        version=settings.service_version,
        environment=settings.environment,
    )

    try:
        # Pre-initialize OCR engine for faster first request
        if not settings.debug:
            logger.info("Pre-initializing OCR engine...")
            initialize_engine()
            logger.info("OCR engine initialized successfully")
    except Exception as e:
        logger.error("Failed to initialize OCR engine", error=str(e))
        # Don't fail startup - engine will initialize on first request

    yield

    # Shutdown
    logger.info("Shutting down OCR Service...")
    shutdown_engine()
    logger.info("OCR Service shutdown complete")


# Create FastAPI application
app = FastAPI(
    title="Kixi OCR Service",
    description="""
OCR Service for extracting structured text from exam images.

## Features

- **Text Extraction**: Extract text from images using PaddleOCR-VL
- **Multi-page Support**: Process multi-page documents and PDFs
- **Structured Output**: Returns questions, options, and metadata
- **Confidence Scores**: Provides confidence scores for all extracted data
- **Portuguese Support**: Optimized for Portuguese language documents

## Endpoints

- `POST /ocr/v1/extract` - Main OCR extraction endpoint
- `POST /ocr/v1/extract/simple` - Simplified single-image extraction
- `GET /ocr/health` - Health check endpoint
- `GET /ocr/v1/supported-languages` - List supported OCR languages
    """,
    version=settings.service_version,
    docs_url="/docs" if settings.debug else None,
    redoc_url="/redoc" if settings.debug else None,
    openapi_url="/openapi.json" if settings.debug else None,
    lifespan=lifespan,
)

# Configure CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"] if settings.debug else settings.service_name,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# Global exception handler
@app.exception_handler(Exception)
async def global_exception_handler(request: Request, exc: Exception):
    """Handle uncaught exceptions globally."""
    logger.error(
        "Unhandled exception",
        path=request.url.path,
        method=request.method,
        error=str(exc),
        exc_info=True,
    )

    return JSONResponse(
        status_code=500,
        content={
            "status": "error",
            "detail": "An internal error occurred",
            "message": str(exc) if settings.debug else "Internal server error",
        },
    )


# Root endpoint
@app.get("/", tags=["Root"])
async def root():
    """Root endpoint with service information."""
    return {
        "service": settings.service_name,
        "version": settings.service_version,
        "status": "running",
        "docs": "/docs" if settings.debug else None,
    }


# Health check at root level
@app.get("/health", tags=["Health"])
async def health():
    """Simple health check endpoint."""
    return {
        "status": "healthy",
        "service": settings.service_name,
        "version": settings.service_version,
    }


# Include OCR routes
app.include_router(ocr_router)


# Metrics endpoint (if enabled)
if settings.enable_metrics:
    try:
        from prometheus_client import make_asgi_app, Counter, Histogram

        # Create metrics
        REQUEST_COUNT = Counter(
            "ocr_requests_total",
            "Total OCR requests",
            ["method", "endpoint", "status"],
        )
        REQUEST_LATENCY = Histogram(
            "ocr_request_latency_seconds",
            "OCR request latency in seconds",
            ["method", "endpoint"],
        )

        # Mount metrics endpoint
        metrics_app = make_asgi_app()
        app.mount("/metrics", metrics_app)

        logger.info("Prometheus metrics enabled at /metrics")

    except ImportError:
        logger.warning("prometheus_client not installed, metrics disabled")


def main():
    """Run the application using uvicorn."""
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=settings.host,
        port=settings.port,
        workers=settings.workers if not settings.debug else 1,
        reload=settings.debug,
        log_level=settings.log_level.lower(),
    )


if __name__ == "__main__":
    main()

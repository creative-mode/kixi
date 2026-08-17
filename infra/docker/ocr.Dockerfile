# OCR Service Dockerfile
#
# Multi-stage build for the Kixi OCR Service using PaddleOCR-VL
# Optimized for production deployments with minimal image size
#
# Build from project root:
#   docker build -f infra/docker/ocr.Dockerfile -t kixi-ocr-service .
#
# Or using docker-compose (recommended):
#   docker-compose up --build ocr-service

# =============================================================================
# Stage 1: Builder
# =============================================================================
FROM python:3.11-slim AS builder

# Set environment variables
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

# Install build dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    gcc \
    g++ \
    libffi-dev \
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

# Create virtual environment
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# Copy requirements and install dependencies
WORKDIR /app
COPY services/ocr-service/requirements.txt .

# Install Python dependencies
RUN pip install --upgrade pip setuptools wheel && \
    pip install -r requirements.txt

# =============================================================================
# Stage 2: Runtime
# =============================================================================
FROM python:3.11-slim AS runtime

# Labels
LABEL maintainer="Kixi Team <team@kixi.ao>" \
    org.opencontainers.image.title="Kixi OCR Service" \
    org.opencontainers.image.description="OCR Service using PaddleOCR-VL for text extraction from exam images" \
    org.opencontainers.image.version="1.0.0" \
    org.opencontainers.image.vendor="Creative Mode" \
    org.opencontainers.image.source="https://github.com/creative-mode/kixi"

# Set environment variables
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONFAULTHANDLER=1 \
    PATH="/opt/venv/bin:$PATH" \
    # Application settings
    SERVICE_NAME=ocr-service \
    SERVICE_VERSION=1.0.0 \
    ENVIRONMENT=production \
    DEBUG=false \
    HOST=0.0.0.0 \
    PORT=8000 \
    WORKERS=1 \
    # OCR settings
    OCR_LANG=pt \
    OCR_USE_GPU=false \
    OCR_USE_ANGLE_CLS=true \
    OCR_SHOW_LOG=false \
    # Processing settings
    MAX_IMAGE_SIZE_MB=20.0 \
    MAX_IMAGES_PER_REQUEST=10 \
    PROCESSING_TIMEOUT_SECONDS=120 \
    MIN_CONFIDENCE_THRESHOLD=0.5 \
    ENABLE_DESKEW=true \
    ENABLE_DENOISE=true \
    TARGET_DPI=300 \
    # Logging
    LOG_LEVEL=INFO \
    LOG_FORMAT=json \
    # Metrics
    ENABLE_METRICS=true \
    # Timezone
    TZ=Africa/Luanda

# Install runtime dependencies
# Note: libgl1-mesa-glx was renamed to libgl1 in Debian Trixie
RUN apt-get update && apt-get install -y --no-install-recommends \
    # OpenCV dependencies (Debian Trixie compatible)
    libgl1 \
    libglib2.0-0t64 \
    libsm6 \
    libxext6 \
    libxrender1 \
    libgomp1 \
    # PDF processing dependencies
    poppler-utils \
    # Fonts for proper text rendering
    fonts-liberation \
    fonts-dejavu-core \
    fonts-freefont-ttf \
    # Curl for health checks
    curl \
    # Timezone data
    tzdata \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/* /tmp/* /var/tmp/*

# Create non-root user for security
RUN groupadd --gid 1000 ocr && \
    useradd --uid 1000 --gid ocr --shell /bin/bash --create-home ocr

# Copy virtual environment from builder
COPY --from=builder /opt/venv /opt/venv

# Set working directory
WORKDIR /app

# Copy application code from services/ocr-service
COPY --chown=ocr:ocr services/ocr-service/app ./app
COPY --chown=ocr:ocr services/ocr-service/tests ./tests

# Create directories for models and cache
RUN mkdir -p /home/ocr/.paddleocr && \
    chown -R ocr:ocr /home/ocr/.paddleocr && \
    # Create tmp directory for processing
    mkdir -p /tmp/ocr && \
    chown -R ocr:ocr /tmp/ocr

# Switch to non-root user
USER ocr

# Expose port
EXPOSE 8000

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
    CMD curl -f http://localhost:8000/health || exit 1

# Default command - run with uvicorn
CMD ["python", "-m", "app.main"]

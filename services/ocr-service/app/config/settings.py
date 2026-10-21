"""
OCR Service Configuration

Environment-based configuration using pydantic-settings.
Supports .env files and environment variables.
"""

from functools import lru_cache
from typing import Optional

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Application settings loaded from environment variables."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )

    # Service configuration
    service_name: str = Field(default="ocr-service", description="Service name for logging and metrics")
    service_version: str = Field(default="1.0.0", description="Service version")
    environment: str = Field(default="development", description="Environment (development, staging, production)")
    debug: bool = Field(default=False, description="Enable debug mode")

    # Server configuration
    host: str = Field(default="0.0.0.0", description="Server host")
    port: int = Field(default=8000, description="Server port")
    workers: int = Field(default=1, description="Number of worker processes")

    # OCR configuration
    ocr_lang: str = Field(default="pt", description="Primary OCR language (pt, en, etc.)")
    ocr_use_gpu: bool = Field(default=False, description="Use GPU for OCR processing")
    ocr_use_angle_cls: bool = Field(default=False, description="Use angle classification for rotated text")
    ocr_det_model_dir: Optional[str] = Field(default=None, description="Custom detection model directory")
    ocr_rec_model_dir: Optional[str] = Field(default=None, description="Custom recognition model directory")
    ocr_cls_model_dir: Optional[str] = Field(default=None, description="Custom classification model directory")
    ocr_show_log: bool = Field(default=False, description="Show PaddleOCR logs")
    ocr_enable_mkldnn: bool = Field(
        default=True,
        description=(
            "Use the oneDNN accelerated kernels on CPU. Needs paddlepaddle 3.2.x: with 3.3.1 the detection "
            "model dies on any real page with NotImplementedError: ConvertPirAttribute2RuntimeAttribute not "
            "support, and without oneDNN a page takes 30 to 140 s instead of 9 to 11 s."
        ),
    )
    ocr_det_model_name: str = Field(
        default="PP-OCRv5_mobile_det",
        description="Text detection model (PaddleOCR 3.x name). The mobile one is as good on photographed exams and much faster.",
    )
    ocr_rec_model_name: str = Field(
        default="latin_PP-OCRv5_mobile_rec",
        description="Text recognition model. The latin one covers Portuguese accents; PP-OCRv4 has no pt model in PaddleOCR 3.x.",
    )
    ocr_det_thresh: Optional[float] = Field(
        default=None, gt=0, lt=1,
        description="Pixel threshold of the text detector; unset keeps PaddleOCR's 0.3. Lowering it on the mobile model found more lines but also merged boxes and cost questions on the real exams, so it stays opt-in.",
    )
    ocr_det_box_thresh: Optional[float] = Field(
        default=None, gt=0, lt=1,
        description="Box score threshold of the text detector; unset keeps PaddleOCR's 0.6. Opt-in for the same reason as ocr_det_thresh.",
    )
    ocr_fallback_det_model_name: Optional[str] = Field(
        default="PP-OCRv5_server_det",
        description="Detector for a second read when the fast one finds fewer than ocr_min_questions questions. Slower (25 to 40 s) and sees faint lines the mobile model misses. Empty disables the second read.",
    )
    ocr_min_questions: int = Field(default=2, ge=0, description="Below this many questions the page is read again with the fallback detector")
    ocr_pipeline: str = Field(
        default="ppocr",
        description=(
            "ppocr: PP-OCRv5 text lines + PP-DocLayout regions (CPU, seconds per photo). "
            "vl: PaddleOCR-VL 0.9B document parsing, meant for a machine with a GPU."
        ),
    )
    ocr_layout_enabled: bool = Field(default=True, description="Detect figures, tables and formulas and tie them to the question they belong to")
    ocr_layout_model_name: str = Field(default="PP-DocLayout_plus-L", description="Layout detection model")
    ocr_layout_min_score: float = Field(default=0.5, ge=0, le=1, description="Minimum score of a layout region")
    ocr_formula_images: bool = Field(default=True, description="Crop the printed formulas as pictures that travel with their question: the text engine cannot read maths, so the picture is the formula. Formulas in a pen colour are never cropped.")
    ocr_formula_latex: bool = Field(default=True, description="Recognize printed and inline formulas as LaTeX text (#262). Disable to keep raw OCR text and formula crops only.")
    ocr_formula_model_enabled: bool = Field(default=True, description="Run the local image-to-LaTeX model on printed formula regions detected by the layout model.")
    ocr_formula_model_name: str = Field(default="PP-FormulaNet_plus-S", description="PaddleOCR formula recognition model. The plus-S model is the bounded CPU default.")
    ocr_formula_batch_size: int = Field(default=8, ge=1, le=32, description="Formula crops inferred together per page.")
    ocr_formula_max_latex_chars: int = Field(default=2000, ge=16, le=10000, description="Reject unexpectedly large model output before it reaches a renderer.")

    # Processing configuration
    max_image_size_mb: float = Field(default=20.0, gt=0, description="Maximum image size in MB")
    max_upload_size_mb: float = Field(default=50.0, gt=0, description="Maximum total upload size per request in MB")
    max_images_per_request: int = Field(default=10, ge=1, description="Maximum images per request")
    max_pdf_pages: int = Field(default=50, ge=1, description="Maximum pages in one uploaded PDF")
    max_ocr_pages_per_request: int = Field(default=50, ge=1, description="Maximum pages processed per request")
    processing_timeout_seconds: int = Field(default=120, description="Timeout for OCR processing in seconds")
    min_confidence_threshold: float = Field(default=0.5, description="Minimum confidence threshold for text extraction")

    # Image preprocessing
    enable_deskew: bool = Field(default=True, description="Enable automatic deskewing")
    enable_denoise: bool = Field(default=True, description="Enable noise reduction")
    target_dpi: int = Field(default=300, description="Target DPI for image processing")
    enable_sheet_detection: bool = Field(
        default=True,
        description="Detect the exam sheet's own edges and perspective-correct/crop to them (#263). "
                     "Falls back to the full photo when no convincing quadrilateral is found - a photo "
                     "taken flat against a scanner, or with an object over an edge, should still OCR.",
    )
    enable_shadow_removal: bool = Field(
        default=True,
        description="Estimate the page's own illumination (a large-kernel background blur) and divide it "
                     "out, so a shadow across half the page does not read as darker ink there (#263).",
    )
    min_sharpness_score: float = Field(
        default=15.0, ge=0,
        description="Below this Laplacian-variance score an image is flagged illegible (too blurred to "
                     "read) rather than silently fed to the OCR, which would otherwise just return "
                     "nothing with no explanation (#263).",
    )
    min_brightness_score: float = Field(
        default=20.0, ge=0, le=255,
        description="Below this mean brightness (0-255) an image is flagged illegible as too dark.",
    )
    max_brightness_score: float = Field(
        default=245.0, ge=0, le=255,
        description="Above this mean brightness (0-255) an image is flagged illegible as blown out / too bright.",
    )

    # Caching
    enable_cache: bool = Field(default=True, description="Enable result caching")
    cache_ttl_seconds: int = Field(default=3600, description="Cache TTL in seconds")
    redis_url: Optional[str] = Field(default=None, description="Redis URL for caching")

    # Security
    api_key: Optional[str] = Field(default=None, description="API key for simple authentication")
    enable_auth: bool = Field(default=False, description="Enable authentication")
    cors_origins: str = Field(default="", description="Comma-separated allowed CORS origins")
    trusted_proxy_ips: str = Field(default="", description="Comma-separated trusted proxy IPs or CIDRs")

    # Logging
    log_level: str = Field(default="INFO", description="Logging level")
    log_format: str = Field(default="json", description="Log format (json, text)")

    # Metrics
    enable_metrics: bool = Field(default=True, description="Enable Prometheus metrics")
    metrics_port: int = Field(default=9090, description="Metrics server port")

    # Health check
    health_check_path: str = Field(default="/health", description="Health check endpoint path")

    @property
    def is_production(self) -> bool:
        """Check if running in production environment."""
        return self.environment.lower() == "production"

    @property
    def cors_origin_list(self) -> list[str]:
        """Return configured CORS origins without empty entries."""
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]

    @property
    def trusted_proxy_list(self) -> list[str]:
        """Return explicitly configured trusted proxy addresses."""
        return [ip.strip() for ip in self.trusted_proxy_ips.split(",") if ip.strip()]

    @model_validator(mode="after")
    def validate_production_auth(self) -> "Settings":
        """Production must not start with an unauthenticated extraction API."""
        if self.environment.lower() in {"staging", "production"} and (
            not self.enable_auth or not self.api_key
        ):
            raise ValueError("ENABLE_AUTH=true and API_KEY are required in deployed environments")
        return self

    @property
    def is_development(self) -> bool:
        """Check if running in development environment."""
        return self.environment.lower() == "development"

    @property
    def max_image_size_bytes(self) -> int:
        """Get maximum image size in bytes."""
        return int(self.max_image_size_mb * 1024 * 1024)

    @property
    def max_upload_size_bytes(self) -> int:
        """Get maximum aggregate upload size in bytes."""
        return int(self.max_upload_size_mb * 1024 * 1024)

    @property
    def ocr_languages(self) -> list[str]:
        """Get list of OCR languages."""
        return [lang.strip() for lang in self.ocr_lang.split(",")]


@lru_cache
def get_settings() -> Settings:
    """
    Get cached settings instance.

    Uses lru_cache to ensure settings are only loaded once.
    """
    return Settings()


# Convenience instance for direct import
settings = get_settings()

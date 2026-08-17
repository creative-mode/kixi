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
    ocr_use_angle_cls: bool = Field(default=True, description="Use angle classification for rotated text")
    ocr_det_model_dir: Optional[str] = Field(default=None, description="Custom detection model directory")
    ocr_rec_model_dir: Optional[str] = Field(default=None, description="Custom recognition model directory")
    ocr_cls_model_dir: Optional[str] = Field(default=None, description="Custom classification model directory")
    ocr_show_log: bool = Field(default=False, description="Show PaddleOCR logs")

    # Processing configuration
    max_image_size_mb: float = Field(default=20.0, description="Maximum image size in MB")
    max_images_per_request: int = Field(default=10, description="Maximum images per request")
    processing_timeout_seconds: int = Field(default=120, description="Timeout for OCR processing in seconds")
    min_confidence_threshold: float = Field(default=0.5, description="Minimum confidence threshold for text extraction")

    # Image preprocessing
    enable_deskew: bool = Field(default=True, description="Enable automatic deskewing")
    enable_denoise: bool = Field(default=True, description="Enable noise reduction")
    target_dpi: int = Field(default=300, description="Target DPI for image processing")

    # Caching
    enable_cache: bool = Field(default=True, description="Enable result caching")
    cache_ttl_seconds: int = Field(default=3600, description="Cache TTL in seconds")
    redis_url: Optional[str] = Field(default=None, description="Redis URL for caching")

    # Security
    jwt_secret_key: Optional[str] = Field(default=None, description="JWT secret key for authentication")
    jwt_algorithm: str = Field(default="HS256", description="JWT algorithm")
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

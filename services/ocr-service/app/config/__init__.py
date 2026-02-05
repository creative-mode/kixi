"""
OCR Service Configuration Module

Provides environment-based configuration using pydantic-settings.
"""

from .settings import Settings, get_settings, settings

__all__ = ["Settings", "get_settings", "settings"]

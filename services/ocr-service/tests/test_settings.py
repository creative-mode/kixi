import pytest
from pydantic import ValidationError

from app.config.settings import Settings


@pytest.mark.parametrize(
    "overrides",
    [
        {"max_image_size_mb": 0},
        {"max_upload_size_mb": -1},
        {"max_images_per_request": 0},
        {"max_pdf_pages": 0},
        {"max_ocr_pages_per_request": 0},
    ],
)
def test_upload_limits_reject_non_positive_values(overrides):
    with pytest.raises(ValidationError):
        Settings(**overrides)

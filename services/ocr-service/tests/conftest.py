"""
Pytest Configuration and Fixtures

Shared fixtures for OCR service tests.
"""

import io
import pytest
import numpy as np
from PIL import Image
from unittest.mock import MagicMock, patch


# =============================================================================
# Image Fixtures
# =============================================================================

@pytest.fixture
def sample_image():
    """Create a sample test image (BGR format for OpenCV)."""
    # Create a white image with some dark regions simulating text
    img = np.ones((480, 640, 3), dtype=np.uint8) * 255

    # Add dark regions to simulate text lines
    img[50:80, 50:300] = 0      # Header line
    img[100:130, 50:350] = 0    # Title line
    img[150:175, 50:250] = 0    # Metadata line 1
    img[180:205, 50:200] = 0    # Metadata line 2

    # Question 1
    img[240:265, 50:400] = 0    # Question text
    img[280:300, 70:150] = 0    # Option A
    img[310:330, 70:160] = 0    # Option B
    img[340:360, 70:155] = 0    # Option C
    img[370:390, 70:165] = 0    # Option D

    return img


@pytest.fixture
def sample_grayscale_image():
    """Create a sample grayscale test image."""
    img = np.ones((480, 640), dtype=np.uint8) * 255
    img[50:100, 50:300] = 0
    img[150:200, 50:350] = 0
    return img


@pytest.fixture
def sample_pil_image():
    """Create a sample PIL Image."""
    img = Image.new("RGB", (640, 480), color="white")
    return img


@pytest.fixture
def sample_image_bytes(sample_pil_image):
    """Create sample image as PNG bytes."""
    buffer = io.BytesIO()
    sample_pil_image.save(buffer, format="PNG")
    buffer.seek(0)
    return buffer.getvalue()


@pytest.fixture
def sample_jpeg_bytes(sample_pil_image):
    """Create sample image as JPEG bytes."""
    buffer = io.BytesIO()
    sample_pil_image.save(buffer, format="JPEG")
    buffer.seek(0)
    return buffer.getvalue()


@pytest.fixture
def large_image():
    """Create a large test image for resize testing."""
    return np.ones((5000, 5000, 3), dtype=np.uint8) * 255


@pytest.fixture
def small_image():
    """Create a small test image."""
    return np.ones((100, 100, 3), dtype=np.uint8) * 255


# =============================================================================
# Text Block Fixtures
# =============================================================================

@pytest.fixture
def sample_text_blocks():
    """Sample text blocks for postprocessing tests."""
    from app.ocr.postprocessing import TextBlock

    return [
        TextBlock(
            text="PROVA DE MATEMÁTICA",
            confidence=0.95,
            bbox=(50, 20, 300, 50),
            page_index=0,
            line_index=0
        ),
        TextBlock(
            text="Ano Letivo 2024/2025",
            confidence=0.92,
            bbox=(50, 60, 250, 90),
            page_index=0,
            line_index=1
        ),
        TextBlock(
            text="1º Trimestre",
            confidence=0.90,
            bbox=(50, 100, 200, 130),
            page_index=0,
            line_index=2
        ),
        TextBlock(
            text="Duração: 120 minutos",
            confidence=0.88,
            bbox=(50, 140, 250, 170),
            page_index=0,
            line_index=3
        ),
        TextBlock(
            text="Versão A",
            confidence=0.94,
            bbox=(450, 100, 520, 130),
            page_index=0,
            line_index=4
        ),
        TextBlock(
            text="1. Calcule o valor de x na equação 2x + 5 = 15:",
            confidence=0.94,
            bbox=(50, 200, 450, 230),
            page_index=0,
            line_index=5
        ),
        TextBlock(
            text="A) 5",
            confidence=0.91,
            bbox=(70, 240, 120, 270),
            page_index=0,
            line_index=6
        ),
        TextBlock(
            text="B) 10",
            confidence=0.92,
            bbox=(70, 280, 130, 310),
            page_index=0,
            line_index=7
        ),
        TextBlock(
            text="C) 15",
            confidence=0.93,
            bbox=(70, 320, 130, 350),
            page_index=0,
            line_index=8
        ),
        TextBlock(
            text="D) 20",
            confidence=0.90,
            bbox=(70, 360, 130, 390),
            page_index=0,
            line_index=9
        ),
        TextBlock(
            text="(5 pontos)",
            confidence=0.87,
            bbox=(460, 200, 540, 230),
            page_index=0,
            line_index=10
        ),
        TextBlock(
            text="2. Justifique por que o triângulo ABC é isósceles:",
            confidence=0.89,
            bbox=(50, 420, 400, 450),
            page_index=0,
            line_index=11
        ),
    ]


@pytest.fixture
def english_text_blocks():
    """Sample English text blocks."""
    from app.ocr.postprocessing import TextBlock

    return [
        TextBlock(
            text="MATHEMATICS EXAM",
            confidence=0.95,
            bbox=(50, 20, 300, 50),
            page_index=0,
            line_index=0
        ),
        TextBlock(
            text="School Year 2024/2025",
            confidence=0.92,
            bbox=(50, 60, 250, 90),
            page_index=0,
            line_index=1
        ),
        TextBlock(
            text="1. Calculate the value of x:",
            confidence=0.94,
            bbox=(50, 200, 350, 230),
            page_index=0,
            line_index=2
        ),
    ]


# =============================================================================
# Mock Fixtures
# =============================================================================

@pytest.fixture
def mock_paddle_ocr():
    """Mock PaddleOCR class."""
    with patch("app.ocr.engine.PaddleOCR") as mock_cls:
        mock_instance = MagicMock()
        mock_cls.return_value = mock_instance

        # Mock OCR result format
        mock_instance.ocr.return_value = [
            [
                [[[10, 10], [200, 10], [200, 40], [10, 40]], ("PROVA DE MATEMÁTICA", 0.95)],
                [[[10, 50], [250, 50], [250, 80], [10, 80]], ("Ano Letivo 2024/2025", 0.92)],
                [[[10, 100], [200, 100], [200, 130], [10, 130]], ("1º Trimestre", 0.90)],
                [[[10, 150], [300, 150], [300, 180], [10, 180]], ("1. Calcule o valor de x:", 0.95)],
                [[[30, 200], [100, 200], [100, 230], [30, 230]], ("A) 5", 0.92)],
                [[[30, 240], [110, 240], [110, 270], [30, 270]], ("B) 10", 0.91)],
                [[[30, 280], [110, 280], [110, 310], [30, 310]], ("C) 15", 0.93)],
                [[[30, 320], [110, 320], [110, 350], [30, 350]], ("D) 20", 0.90)],
            ]
        ]

        yield mock_cls


@pytest.fixture
def mock_paddle_ocr_empty():
    """Mock PaddleOCR with empty results."""
    with patch("app.ocr.engine.PaddleOCR") as mock_cls:
        mock_instance = MagicMock()
        mock_cls.return_value = mock_instance
        mock_instance.ocr.return_value = [[]]
        yield mock_cls


@pytest.fixture
def mock_paddle_ocr_error():
    """Mock PaddleOCR that raises an error."""
    with patch("app.ocr.engine.PaddleOCR") as mock_cls:
        mock_instance = MagicMock()
        mock_cls.return_value = mock_instance
        mock_instance.ocr.side_effect = Exception("OCR processing failed")
        yield mock_cls


# =============================================================================
# PDF Fixtures
# =============================================================================

@pytest.fixture
def sample_pdf_content():
    """Create minimal valid PDF content for testing."""
    # This is a minimal valid PDF structure
    pdf_content = b"""%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>
endobj
xref
0 4
0000000000 65535 f
0000000009 00000 n
0000000058 00000 n
0000000115 00000 n
trailer
<< /Size 4 /Root 1 0 R >>
startxref
196
%%EOF"""
    return pdf_content


@pytest.fixture
def invalid_pdf_content():
    """Create invalid PDF content for testing."""
    return b"This is not a valid PDF file"


# =============================================================================
# Configuration Fixtures
# =============================================================================

@pytest.fixture
def mock_settings():
    """Mock settings for testing."""
    with patch("app.config.settings") as mock:
        mock.service_name = "ocr-service"
        mock.service_version = "1.0.0"
        mock.environment = "test"
        mock.debug = True
        mock.host = "0.0.0.0"
        mock.port = 8000
        mock.workers = 1
        mock.ocr_lang = "pt"
        mock.ocr_use_gpu = False
        mock.ocr_use_angle_cls = True
        mock.ocr_show_log = False
        mock.max_image_size_mb = 20.0
        mock.max_images_per_request = 10
        mock.min_confidence_threshold = 0.5
        mock.enable_deskew = True
        mock.enable_denoise = True
        mock.target_dpi = 300
        mock.log_level = "INFO"
        mock.log_format = "json"
        mock.enable_metrics = False
        yield mock


# =============================================================================
# API Client Fixtures
# =============================================================================

@pytest.fixture
def test_client():
    """Create FastAPI test client."""
    from fastapi.testclient import TestClient
    from app.main import app

    with TestClient(app) as client:
        yield client


@pytest.fixture
def async_client():
    """Create async test client."""
    import httpx
    from app.main import app

    return httpx.AsyncClient(app=app, base_url="http://test")


# =============================================================================
# Pytest Configuration
# =============================================================================

def pytest_configure(config):
    """Configure pytest markers."""
    config.addinivalue_line(
        "markers", "slow: marks tests as slow (deselect with '-m \"not slow\"')"
    )
    config.addinivalue_line(
        "markers", "integration: marks tests as integration tests"
    )
    config.addinivalue_line(
        "markers", "gpu: marks tests that require GPU"
    )


def pytest_collection_modifyitems(config, items):
    """Modify test collection based on markers."""
    # Skip slow tests unless explicitly requested
    if not config.getoption("--runslow", default=False):
        skip_slow = pytest.mark.skip(reason="need --runslow option to run")
        for item in items:
            if "slow" in item.keywords:
                item.add_marker(skip_slow)

    # Skip integration tests unless explicitly requested
    if not config.getoption("--runintegration", default=False):
        skip_integration = pytest.mark.skip(reason="need --runintegration option to run")
        for item in items:
            if "integration" in item.keywords:
                item.add_marker(skip_integration)


def pytest_addoption(parser):
    """Add custom command line options."""
    parser.addoption(
        "--runslow",
        action="store_true",
        default=False,
        help="run slow tests"
    )
    parser.addoption(
        "--runintegration",
        action="store_true",
        default=False,
        help="run integration tests"
    )

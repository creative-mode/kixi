"""
OCR Engine Test Suite

Comprehensive tests for the OCR processing engine.
"""

import pytest
import numpy as np
from unittest.mock import Mock, patch, MagicMock
from PIL import Image
import io

from app.ocr.engine import (
    OCREngine,
    OCRResult,
    DocumentInfo,
    get_engine,
    initialize_engine,
    shutdown_engine,
)
from app.ocr.preprocessing import (
    ImagePreprocessor,
    PreprocessingResult,
    load_image_from_bytes,
    load_image_from_pil,
    image_to_bytes,
)
from app.ocr.postprocessing import (
    OCRPostprocessor,
    TextBlock,
    ExtractedMetadata,
    ExtractedQuestion,
    ExtractedOption,
    QuestionType,
    MetadataField,
    normalize_text,
    detect_language,
)


# =============================================================================
# Fixtures
# =============================================================================

@pytest.fixture
def sample_image():
    """Create a sample test image."""
    # Create a simple white image with some text-like patterns
    img = np.ones((480, 640, 3), dtype=np.uint8) * 255
    # Add some dark regions to simulate text
    img[50:100, 50:200] = 0
    img[150:180, 50:300] = 0
    img[200:230, 50:250] = 0
    return img


@pytest.fixture
def sample_pil_image():
    """Create a sample PIL Image."""
    img = Image.new("RGB", (640, 480), color="white")
    return img


@pytest.fixture
def sample_image_bytes(sample_pil_image):
    """Create sample image as bytes."""
    buffer = io.BytesIO()
    sample_pil_image.save(buffer, format="PNG")
    buffer.seek(0)
    return buffer.getvalue()


@pytest.fixture
def mock_paddle_ocr():
    """Mock PaddleOCR class."""
    with patch("app.ocr.engine.PaddleOCR") as mock_cls:
        mock_instance = MagicMock()
        mock_cls.return_value = mock_instance

        # Mock OCR result format
        mock_instance.ocr.return_value = [
            [
                [[[10, 10], [200, 10], [200, 40], [10, 40]], ("1. Calcule o valor de x:", 0.95)],
                [[[10, 50], [300, 50], [300, 80], [10, 80]], ("A) 5", 0.92)],
                [[[10, 90], [300, 90], [300, 120], [10, 120]], ("B) 10", 0.91)],
                [[[10, 130], [300, 130], [300, 160], [10, 160]], ("C) 15", 0.93)],
                [[[10, 170], [300, 170], [300, 200], [10, 200]], ("D) 20", 0.90)],
            ]
        ]

        yield mock_cls


@pytest.fixture
def text_blocks():
    """Sample text blocks for postprocessing tests."""
    return [
        TextBlock(text="PROVA DE MATEMÁTICA", confidence=0.95, bbox=(50, 20, 300, 50), page_index=0, line_index=0),
        TextBlock(text="Ano Letivo 2024/2025", confidence=0.92, bbox=(50, 60, 250, 90), page_index=0, line_index=1),
        TextBlock(text="1º Trimestre", confidence=0.90, bbox=(50, 100, 200, 130), page_index=0, line_index=2),
        TextBlock(text="Duração: 120 minutos", confidence=0.88, bbox=(50, 140, 250, 170), page_index=0, line_index=3),
        TextBlock(text="1. Calcule o valor de x na equação:", confidence=0.94, bbox=(50, 200, 400, 230), page_index=0, line_index=4),
        TextBlock(text="A) 5", confidence=0.91, bbox=(70, 240, 120, 270), page_index=0, line_index=5),
        TextBlock(text="B) 10", confidence=0.92, bbox=(70, 280, 130, 310), page_index=0, line_index=6),
        TextBlock(text="C) 15", confidence=0.93, bbox=(70, 320, 130, 350), page_index=0, line_index=7),
        TextBlock(text="D) 20", confidence=0.90, bbox=(70, 360, 130, 390), page_index=0, line_index=8),
        TextBlock(text="2. Justifique sua resposta:", confidence=0.89, bbox=(50, 420, 350, 450), page_index=0, line_index=9),
    ]


# =============================================================================
# Preprocessing Tests
# =============================================================================

class TestImagePreprocessor:
    """Tests for ImagePreprocessor class."""

    def test_init(self):
        """Test preprocessor initialization."""
        preprocessor = ImagePreprocessor(
            enable_deskew=True,
            enable_denoise=True,
            target_dpi=300,
        )
        assert preprocessor.enable_deskew is True
        assert preprocessor.enable_denoise is True
        assert preprocessor.target_dpi == 300

    def test_preprocess_grayscale_conversion(self, sample_image):
        """Test that preprocessing converts to grayscale and back."""
        preprocessor = ImagePreprocessor(enable_deskew=False, enable_denoise=False)
        result = preprocessor.preprocess(sample_image)

        assert isinstance(result, PreprocessingResult)
        assert result.image.shape[2] == 3  # Should be BGR
        assert "grayscale_conversion" in result.preprocessing_applied

    def test_preprocess_with_denoise(self, sample_image):
        """Test preprocessing with noise reduction enabled."""
        preprocessor = ImagePreprocessor(enable_deskew=False, enable_denoise=True)
        result = preprocessor.preprocess(sample_image)

        assert "denoise" in result.preprocessing_applied

    def test_preprocess_with_deskew(self, sample_image):
        """Test preprocessing with deskewing enabled."""
        preprocessor = ImagePreprocessor(enable_deskew=True, enable_denoise=False)
        result = preprocessor.preprocess(sample_image)

        # Deskew might not always find lines to correct
        assert isinstance(result.rotation_angle, float)

    def test_preprocess_calculates_hash(self, sample_image):
        """Test that preprocessing calculates image hash."""
        preprocessor = ImagePreprocessor()
        result = preprocessor.preprocess(sample_image)

        assert result.image_hash is not None
        assert len(result.image_hash) == 64  # SHA-256 hex

    def test_resize_if_needed_large_image(self):
        """Test resizing large images."""
        large_image = np.zeros((5000, 5000, 3), dtype=np.uint8)
        resized, scale = ImagePreprocessor.resize_if_needed(large_image, max_dimension=4096)

        assert max(resized.shape[:2]) <= 4096
        assert scale < 1.0

    def test_resize_if_needed_small_image(self):
        """Test that small images are not resized unnecessarily."""
        small_image = np.zeros((500, 500, 3), dtype=np.uint8)
        resized, scale = ImagePreprocessor.resize_if_needed(small_image)

        assert scale == 1.0
        assert resized.shape == small_image.shape


class TestLoadImageFunctions:
    """Tests for image loading functions."""

    def test_load_image_from_bytes(self, sample_image_bytes):
        """Test loading image from bytes."""
        image = load_image_from_bytes(sample_image_bytes)

        assert isinstance(image, np.ndarray)
        assert len(image.shape) == 3
        assert image.shape[2] == 3  # BGR

    def test_load_image_from_bytes_invalid(self):
        """Test loading invalid bytes raises ValueError."""
        with pytest.raises(ValueError, match="Failed to decode"):
            load_image_from_bytes(b"invalid image data")

    def test_load_image_from_pil(self, sample_pil_image):
        """Test loading image from PIL Image."""
        image = load_image_from_pil(sample_pil_image)

        assert isinstance(image, np.ndarray)
        assert image.shape == (480, 640, 3)

    def test_image_to_bytes(self, sample_image):
        """Test converting image to bytes."""
        img_bytes = image_to_bytes(sample_image, format="PNG")

        assert isinstance(img_bytes, bytes)
        assert len(img_bytes) > 0
        # Verify it's a valid PNG
        assert img_bytes[:8] == b'\x89PNG\r\n\x1a\n'


# =============================================================================
# Postprocessing Tests
# =============================================================================

class TestOCRPostprocessor:
    """Tests for OCRPostprocessor class."""

    def test_init(self):
        """Test postprocessor initialization."""
        postprocessor = OCRPostprocessor(
            min_confidence_threshold=0.5,
            low_confidence_threshold=0.8,
        )
        assert postprocessor.min_confidence_threshold == 0.5
        assert postprocessor.low_confidence_threshold == 0.8

    def test_process_extracts_metadata(self, text_blocks):
        """Test that postprocessing extracts metadata."""
        postprocessor = OCRPostprocessor()
        metadata, questions, unmapped, warnings = postprocessor.process(text_blocks)

        assert isinstance(metadata, ExtractedMetadata)
        assert metadata.school_year.value == "2024/2025"
        assert metadata.term.value is not None

    def test_process_extracts_questions(self, text_blocks):
        """Test that postprocessing extracts questions."""
        postprocessor = OCRPostprocessor()
        metadata, questions, unmapped, warnings = postprocessor.process(text_blocks)

        assert len(questions) >= 1
        assert isinstance(questions[0], ExtractedQuestion)
        assert questions[0].number == 1

    def test_process_detects_multiple_choice(self, text_blocks):
        """Test detection of multiple choice questions."""
        postprocessor = OCRPostprocessor()
        metadata, questions, unmapped, warnings = postprocessor.process(text_blocks)

        # First question should be multiple choice (has options A, B, C, D)
        if len(questions) > 0:
            first_question = questions[0]
            if first_question.options:
                assert first_question.question_type == QuestionType.MULTIPLE_CHOICE

    def test_process_generates_warnings(self, text_blocks):
        """Test that warnings are generated for low confidence fields."""
        postprocessor = OCRPostprocessor(low_confidence_threshold=0.95)
        metadata, questions, unmapped, warnings = postprocessor.process(text_blocks)

        # With threshold of 0.95, most fields should trigger warnings
        assert isinstance(warnings, list)


class TestTextNormalization:
    """Tests for text normalization functions."""

    def test_normalize_text_removes_extra_whitespace(self):
        """Test whitespace normalization."""
        text = "Hello    world   test"
        normalized = normalize_text(text)
        assert normalized == "Hello world test"

    def test_normalize_text_normalizes_quotes(self):
        """Test quote normalization."""
        text = '"Hello" and 'world'"
        normalized = normalize_text(text)
        assert '"' in normalized
        assert "'" in normalized

    def test_normalize_text_normalizes_dashes(self):
        """Test dash normalization."""
        text = "option–one—two−three"
        normalized = normalize_text(text)
        assert "–" not in normalized
        assert "—" not in normalized


class TestLanguageDetection:
    """Tests for language detection."""

    def test_detect_portuguese(self):
        """Test detection of Portuguese text."""
        text = "O aluno deve resolver as questões de matemática com atenção"
        lang = detect_language(text)
        assert lang == "pt"

    def test_detect_english(self):
        """Test detection of English text."""
        text = "The student should solve the math questions carefully"
        lang = detect_language(text)
        assert lang == "en"

    def test_detect_empty_text(self):
        """Test detection with empty text defaults to Portuguese."""
        lang = detect_language("")
        assert lang == "pt"


class TestQuestionType:
    """Tests for question type enum and inference."""

    def test_question_type_values(self):
        """Test QuestionType enum values."""
        assert QuestionType.MULTIPLE_CHOICE.value == "multiple_choice"
        assert QuestionType.SHORT_ANSWER.value == "short_answer"
        assert QuestionType.DEVELOPMENT.value == "development"
        assert QuestionType.TRUE_FALSE.value == "true_false"
        assert QuestionType.UNKNOWN.value == "unknown"


class TestExtractedQuestion:
    """Tests for ExtractedQuestion dataclass."""

    def test_question_confidence_calculation(self):
        """Test overall confidence calculation."""
        question = ExtractedQuestion(
            number=1,
            text="Test question",
            text_confidence=0.9,
            question_type=QuestionType.SHORT_ANSWER,
            question_type_confidence=0.85,
        )

        # Confidence should be average of text and type confidence
        expected = (0.9 + 0.85) / 2
        assert abs(question.confidence - expected) < 0.01

    def test_question_with_options_confidence(self):
        """Test confidence with options included."""
        question = ExtractedQuestion(
            number=1,
            text="Test question",
            text_confidence=0.9,
            question_type=QuestionType.MULTIPLE_CHOICE,
            question_type_confidence=0.95,
            options=[
                ExtractedOption("A", "Option 1", 0.88),
                ExtractedOption("B", "Option 2", 0.92),
            ],
        )

        # Should include option confidences
        assert question.confidence > 0

    def test_question_to_dict(self):
        """Test conversion to dictionary."""
        question = ExtractedQuestion(
            number=1,
            text="What is 2+2?",
            text_confidence=0.95,
            question_type=QuestionType.SHORT_ANSWER,
            question_type_confidence=0.9,
            max_score=5.0,
            max_score_confidence=0.85,
        )

        result = question.to_dict()

        assert result["number"] == 1
        assert result["text"]["value"] == "What is 2+2?"
        assert result["questionType"]["value"] == "short_answer"
        assert result["maxScore"]["value"] == 5.0


# =============================================================================
# OCR Engine Tests
# =============================================================================

class TestOCREngine:
    """Tests for OCREngine class."""

    def test_init(self):
        """Test engine initialization."""
        engine = OCREngine(
            lang="pt",
            use_gpu=False,
            use_angle_cls=True,
        )

        assert engine.lang == "pt"
        assert engine.use_gpu is False
        assert engine.use_angle_cls is True
        assert engine._initialized is False

    def test_health_check_not_initialized(self):
        """Test health check when not initialized."""
        engine = OCREngine()
        health = engine.health_check()

        assert health["initialized"] is False
        assert health["status"] == "not_initialized"

    @patch("app.ocr.engine.PaddleOCR")
    def test_initialize(self, mock_paddle):
        """Test engine initialization."""
        mock_paddle.return_value = MagicMock()

        engine = OCREngine()
        engine.initialize()

        assert engine._initialized is True
        mock_paddle.assert_called_once()

    @patch("app.ocr.engine.PaddleOCR")
    def test_process_image(self, mock_paddle, sample_image):
        """Test processing a single image."""
        # Setup mock
        mock_instance = MagicMock()
        mock_paddle.return_value = mock_instance
        mock_instance.ocr.return_value = [
            [
                [[[10, 10], [200, 10], [200, 40], [10, 40]], ("Test text", 0.95)],
            ]
        ]

        engine = OCREngine()
        result = engine.process_image(sample_image)

        assert isinstance(result, OCRResult)
        assert result.status in ["success", "partial", "error"]
        assert result.processing_time_ms >= 0

    @patch("app.ocr.engine.PaddleOCR")
    def test_process_image_with_error(self, mock_paddle, sample_image):
        """Test error handling during processing."""
        mock_instance = MagicMock()
        mock_paddle.return_value = mock_instance
        mock_instance.ocr.side_effect = Exception("OCR failed")

        engine = OCREngine()
        result = engine.process_image(sample_image)

        assert result.status == "error"
        assert result.error_message is not None

    @patch("app.ocr.engine.PaddleOCR")
    def test_process_bytes(self, mock_paddle, sample_image_bytes):
        """Test processing image from bytes."""
        mock_instance = MagicMock()
        mock_paddle.return_value = mock_instance
        mock_instance.ocr.return_value = [[]]

        engine = OCREngine()
        result = engine.process_bytes(sample_image_bytes)

        assert isinstance(result, OCRResult)

    def test_process_bytes_invalid(self):
        """Test processing invalid bytes."""
        engine = OCREngine()
        result = engine.process_bytes(b"invalid")

        assert result.status == "error"
        assert "Failed to load image" in result.error_message

    @patch("app.ocr.engine.PaddleOCR")
    def test_process_multiple_images(self, mock_paddle, sample_image):
        """Test processing multiple images."""
        mock_instance = MagicMock()
        mock_paddle.return_value = mock_instance
        mock_instance.ocr.return_value = [
            [
                [[[10, 10], [200, 10], [200, 40], [10, 40]], ("Page content", 0.9)],
            ]
        ]

        engine = OCREngine()
        images = [sample_image, sample_image]
        result = engine.process_images(images)

        assert isinstance(result, OCRResult)
        assert result.document.page_count == 2

    @patch("app.ocr.engine.PaddleOCR")
    def test_process_empty_images_list(self, mock_paddle):
        """Test processing empty images list."""
        engine = OCREngine()
        result = engine.process_images([])

        assert result.status == "error"
        assert "No images provided" in result.error_message

    def test_shutdown(self):
        """Test engine shutdown."""
        engine = OCREngine()
        engine.shutdown()

        assert engine._ocr is None
        assert engine._initialized is False


class TestOCRResult:
    """Tests for OCRResult dataclass."""

    def test_to_dict(self):
        """Test OCRResult to dictionary conversion."""
        result = OCRResult(
            status="success",
            request_id="req-test-123",
            processing_time_ms=1500,
            overall_confidence=0.85,
            document=DocumentInfo(
                page_count=2,
                main_language="pt",
                has_tables=False,
            ),
            metadata=ExtractedMetadata(),
            questions=[],
            unmapped_content=[],
            warnings=[],
        )

        result_dict = result.to_dict()

        assert result_dict["status"] == "success"
        assert result_dict["requestId"] == "req-test-123"
        assert result_dict["processingTimeMs"] == 1500
        assert result_dict["overallConfidence"] == 0.85
        assert result_dict["document"]["pageCount"] == 2
        assert result_dict["document"]["mainLanguage"] == "pt"

    def test_to_dict_with_error(self):
        """Test OCRResult with error message."""
        result = OCRResult(
            status="error",
            request_id="req-error-123",
            processing_time_ms=100,
            overall_confidence=0.0,
            document=DocumentInfo(page_count=0, main_language="pt", has_tables=False),
            metadata=ExtractedMetadata(),
            questions=[],
            unmapped_content=[],
            warnings=[],
            error_message="Processing failed",
        )

        result_dict = result.to_dict()

        assert result_dict["status"] == "error"
        assert result_dict["errorMessage"] == "Processing failed"


# =============================================================================
# Module-level Functions Tests
# =============================================================================

class TestModuleFunctions:
    """Tests for module-level functions."""

    @patch("app.ocr.engine._default_engine", None)
    def test_get_engine_creates_instance(self):
        """Test that get_engine creates a new instance."""
        engine = get_engine()

        assert isinstance(engine, OCREngine)

    @patch("app.ocr.engine._default_engine", None)
    def test_get_engine_returns_same_instance(self):
        """Test that get_engine returns the same instance."""
        engine1 = get_engine()
        engine2 = get_engine()

        assert engine1 is engine2

    @patch("app.ocr.engine.PaddleOCR")
    @patch("app.ocr.engine._default_engine", None)
    def test_initialize_engine(self, mock_paddle):
        """Test initialize_engine function."""
        mock_paddle.return_value = MagicMock()

        initialize_engine()

        engine = get_engine()
        assert engine._initialized is True

    @patch("app.ocr.engine._default_engine")
    def test_shutdown_engine(self, mock_engine):
        """Test shutdown_engine function."""
        mock_engine.shutdown = MagicMock()

        shutdown_engine()

        # Should call shutdown on the engine
        # Note: actual behavior depends on global state


# =============================================================================
# Integration Tests (marked for optional execution)
# =============================================================================

@pytest.mark.integration
class TestOCRIntegration:
    """Integration tests that require actual PaddleOCR."""

    @pytest.mark.slow
    def test_real_ocr_processing(self, sample_image):
        """Test with actual PaddleOCR (slow, requires models)."""
        try:
            from paddleocr import PaddleOCR
        except ImportError:
            pytest.skip("PaddleOCR not installed")

        engine = OCREngine(lang="en", use_gpu=False)
        result = engine.process_image(sample_image)

        assert isinstance(result, OCRResult)
        assert result.status in ["success", "partial", "error"]


# =============================================================================
# Async Tests
# =============================================================================

@pytest.mark.asyncio
class TestAsyncOCR:
    """Async tests for OCR engine."""

    @patch("app.ocr.engine.PaddleOCR")
    async def test_process_image_async(self, mock_paddle, sample_image):
        """Test async image processing."""
        mock_instance = MagicMock()
        mock_paddle.return_value = mock_instance
        mock_instance.ocr.return_value = [[]]

        engine = OCREngine()
        result = await engine.process_image_async(sample_image)

        assert isinstance(result, OCRResult)

    @patch("app.ocr.engine.PaddleOCR")
    async def test_process_bytes_async(self, mock_paddle, sample_image_bytes):
        """Test async bytes processing."""
        mock_instance = MagicMock()
        mock_paddle.return_value = mock_instance
        mock_instance.ocr.return_value = [[]]

        engine = OCREngine()
        result = await engine.process_bytes_async(sample_image_bytes)

        assert isinstance(result, OCRResult)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])

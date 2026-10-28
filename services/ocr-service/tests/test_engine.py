"""
OCR Engine Test Suite

Comprehensive tests for the OCR processing engine.
"""

import pytest
import cv2
import fitz
import numpy as np
from unittest.mock import Mock, patch, MagicMock
from PIL import Image
import io
from pathlib import Path

from tests.conftest import to_predict
from app.config import settings
from app.ocr.engine import (
    OCREngine,
    OCRResult,
    PageOcrContext,
    DocumentInfo,
    OCRProcessWorker,
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
    UnmappedContent,
    normalize_text,
    detect_language,
    formula_prediction_key,
)
from app.ocr.layout import LayoutRegion


@pytest.mark.parametrize(
    "raw,expected",
    [
        (r"\vec{v}\;e\;\vec{a}", r"\vec{v}\;e\;\vec{a}"),
        (r"\vec{v}\;\vec{e}\;\vec{a}", r"\vec{v}\;e\;\vec{a}"),
        (r"\vec{v}e\\ {\vec{a}}", r"\vec{v}\;e\;\vec{a}"),
        (r"\vec{v}\;\vec{x}\;\vec{a}", None),
        (r"\vec{v}\;e", None),
    ],
)
def test_canonical_vector_pair_accepts_only_the_expected_inline_semantics(raw, expected):
    assert OCREngine._canonical_vector_pair(raw) == expected


@pytest.mark.parametrize(
    "numbers,expected_gap",
    [
        (["1", "2", "3"], False),
        (["7", "8", "9"], False),
        (["7", "9", "10"], True),
        (["34", "35", "37"], True),
    ],
)
def test_numbering_gap_detection_is_relative_to_the_page_start(numbers, expected_gap):
    questions = [Mock(number=number) for number in numbers]
    assert OCREngine._numbering_has_gaps(questions) is expected_gap


def test_first_alinea_gap_triggers_fallback_only_for_colon_led_lists():
    missed_first = Mock(text="Apresente os conhecimentos sobre:", subitems_content=[Mock(label="b")])
    intentional_start = Mock(text="Calcule o fluxo magnético.", subitems_content=[Mock(label="c")])
    already_complete = Mock(text="Apresente os conhecimentos sobre:", subitems_content=[Mock(label="a")])

    assert OCREngine._may_have_missing_first_subitem([missed_first]) is True
    assert OCREngine._may_have_missing_first_subitem([intentional_start]) is False
    assert OCREngine._may_have_missing_first_subitem([already_complete]) is False


def test_fallback_formula_read_uses_enhanced_prediction_when_raw_crop_is_unreadable():
    primary = LayoutRegion("formula", "formula", 0.9, (170, 937, 458, 968))
    fallback = LayoutRegion("formula", "formula", 0.88, (162, 929, 468, 977))
    primary_key = formula_prediction_key(0, primary.bbox)
    fallback_key = formula_prediction_key(0, fallback.bbox)

    merged = OCREngine._merge_fallback_formula_predictions(
        [primary], {primary_key: r"\vec{r}=(1-t)"},
        [fallback], {}, 0,
    )

    assert merged == {fallback_key: r"\vec{r}=(1-t)"}


def test_explicit_fallback_formula_read_is_not_overwritten_by_primary():
    primary = LayoutRegion("formula", "formula", 0.9, (170, 937, 458, 968))
    fallback = LayoutRegion("formula", "formula", 0.88, (162, 929, 468, 977))
    primary_key = formula_prediction_key(0, primary.bbox)
    fallback_key = formula_prediction_key(0, fallback.bbox)

    merged = OCREngine._merge_fallback_formula_predictions(
        [primary], {primary_key: r"\vec{r}=(1-t)"},
        [fallback], {fallback_key: r"\vec{r}=(11-t)"}, 0,
    )

    assert merged[fallback_key] == r"\vec{r}=(11-t)"


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
        mock_instance.predict.return_value = to_predict([
            [
                [[[10, 10], [200, 10], [200, 40], [10, 40]], ("1. Calcule o valor de x:", 0.95)],
                [[[10, 50], [300, 50], [300, 80], [10, 80]], ("A) 5", 0.92)],
                [[[10, 90], [300, 90], [300, 120], [10, 120]], ("B) 10", 0.91)],
                [[[10, 130], [300, 130], [300, 160], [10, 160]], ("C) 15", 0.93)],
                [[[10, 170], [300, 170], [300, 200], [10, 200]], ("D) 20", 0.90)],
            ]
        ])

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

    # -------------------------------------------------------------------------
    # #263: sheet detection/crop, shadow reduction, legibility, cost/time.
    # -------------------------------------------------------------------------

    def test_preprocess_measures_its_own_duration(self, sample_image):
        """Preprocessing reports its own wall-clock cost (#263)."""
        preprocessor = ImagePreprocessor()
        result = preprocessor.preprocess(sample_image)

        assert result.duration_ms >= 0.0
        assert isinstance(result.duration_ms, float)

    def test_sharp_high_contrast_image_is_legible(self, sample_image):
        """A sharp image with real content passes the legibility check."""
        preprocessor = ImagePreprocessor()
        result = preprocessor.preprocess(sample_image)

        assert result.is_legible is True
        assert result.rejection_reason is None
        assert result.quality_score > 0.0

    def test_heavily_blurred_image_is_flagged_illegible(self):
        """A photo too blurred to read is flagged, not silently fed to the OCR (#263)."""
        # The same high-contrast content as sample_image, but blurred into mush.
        img = np.ones((480, 640, 3), dtype=np.uint8) * 255
        img[50:100, 50:200] = 0
        img[150:180, 50:300] = 0
        blurred = cv2.GaussianBlur(img, (99, 99), 30)

        preprocessor = ImagePreprocessor()
        result = preprocessor.preprocess(blurred)

        assert result.is_legible is False
        assert result.rejection_reason == "blurred"

    def test_too_dark_image_is_flagged_illegible(self):
        """A near-black photo (bad lighting) is flagged too dark (#263)."""
        img = np.full((480, 640, 3), 5, dtype=np.uint8)

        preprocessor = ImagePreprocessor()
        result = preprocessor.preprocess(img)

        assert result.is_legible is False
        assert result.rejection_reason == "too_dark"

    def test_too_bright_image_is_flagged_illegible(self):
        """A near-white, blown-out photo is flagged too bright (#263)."""
        img = np.full((480, 640, 3), 253, dtype=np.uint8)

        preprocessor = ImagePreprocessor()
        result = preprocessor.preprocess(img)

        assert result.is_legible is False
        assert result.rejection_reason == "too_bright"

    def test_shadow_removal_flattens_an_illumination_gradient(self):
        """A shadow cast across half the page is divided out, not read as darker ink (#263)."""
        height, width = 400, 400
        # A linear brightness gradient standing in for an uneven shadow across the sheet.
        gradient = np.tile(np.linspace(100, 220, width, dtype=np.uint8), (height, 1))
        gray = gradient.copy()
        # A few dark "ink" strokes thin enough to survive the background estimate.
        gray[100:104, 50:350] = 10
        gray[200:204, 50:350] = 10

        preprocessor = ImagePreprocessor()
        corrected = preprocessor._reduce_shadows(gray)

        # The left and right halves of the raw gradient differ a lot; after dividing out
        # the (slowly-varying) background they should be far closer to each other.
        before_gap = abs(int(gray[:, 10].mean()) - int(gray[:, -10].mean()))
        after_gap = abs(int(corrected[:, 10].mean()) - int(corrected[:, -10].mean()))
        assert after_gap < before_gap

    def test_sheet_detection_crops_a_clear_quadrilateral(self):
        """A sheet photographed with visible margin around it is detected and cropped (#263)."""
        canvas = np.zeros((600, 800, 3), dtype=np.uint8)  # a dark desk around the sheet
        cv2.rectangle(canvas, (100, 80), (700, 520), (255, 255, 255), thickness=-1)  # the sheet
        # Some "text" on the sheet so it is not a featureless rectangle.
        cv2.rectangle(canvas, (150, 120), (500, 150), (0, 0, 0), thickness=-1)

        preprocessor = ImagePreprocessor()
        cropped, sheet_cropped = preprocessor._detect_and_crop_sheet(canvas)

        assert sheet_cropped is True
        # The crop should land close to the sheet's own size, not the full 800x600 canvas.
        assert cropped.shape[1] < canvas.shape[1]
        assert cropped.shape[0] < canvas.shape[0]

    def test_sheet_detection_backs_off_when_no_quadrilateral_is_found(self, sample_image):
        """A photo already cropped tight to the page (no visible edge) is left untouched."""
        preprocessor = ImagePreprocessor()
        result, sheet_cropped = preprocessor._detect_and_crop_sheet(sample_image)

        assert sheet_cropped is False
        assert result is sample_image

    def test_preprocess_reports_sheet_and_shadow_flags(self, sample_image):
        """The full pipeline surfaces the #263 metadata on its result object."""
        preprocessor = ImagePreprocessor(enable_sheet_detection=True, enable_shadow_removal=True)
        result = preprocessor.preprocess(sample_image)

        assert isinstance(result.sheet_cropped, bool)
        assert result.shadow_reduced is True
        assert "shadow_removal" in result.preprocessing_applied

    def test_disabling_sheet_detection_and_shadow_removal_skips_them(self, sample_image):
        preprocessor = ImagePreprocessor(
            enable_sheet_detection=False,
            enable_shadow_removal=False,
            enable_deskew=False,
            enable_denoise=False,
        )
        result = preprocessor.preprocess(sample_image)

        assert result.sheet_cropped is False
        assert result.shadow_reduced is False
        assert "shadow_removal" not in result.preprocessing_applied
        assert "sheet_detection_crop" not in result.preprocessing_applied


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

    def test_load_image_rejects_oversized_header_before_opencv_decode(self):
        from unittest.mock import MagicMock, patch

        image_header = MagicMock()
        image_header.__enter__.return_value.size = (50_000, 50_000)
        with patch("app.ocr.preprocessing.Image.open", return_value=image_header), \
             patch("app.ocr.preprocessing.cv2.imdecode") as decode:
            with pytest.raises(ValueError, match="maximum decoded pixel count"):
                load_image_from_bytes(b"synthetic image bytes")

        decode.assert_not_called()

    def test_load_image_accepts_the_configured_pixel_boundary(self, monkeypatch):
        from unittest.mock import MagicMock, patch

        monkeypatch.setattr(settings, "max_image_pixels", 25_000_000)
        image_header = MagicMock()
        image_header.__enter__.return_value.size = (5_000, 5_000)
        with patch("app.ocr.preprocessing.Image.open", return_value=image_header), \
             patch("app.ocr.preprocessing.cv2.imdecode", return_value=np.zeros((1, 1, 3))) as decode:
            image = load_image_from_bytes(b"synthetic 5000x5000 image")

        assert image.shape == (1, 1, 3)
        decode.assert_called_once()

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
        metadata, questions, images_to_upload, unmapped, warnings = postprocessor.process(text_blocks)

        assert isinstance(metadata, ExtractedMetadata)
        assert metadata.school_year.value == "2024/2025"
        assert metadata.term.value is not None

    def test_process_extracts_questions(self, text_blocks):
        """Test that postprocessing extracts questions."""
        postprocessor = OCRPostprocessor()
        metadata, questions, images_to_upload, unmapped, warnings = postprocessor.process(text_blocks)

        assert len(questions) >= 1
        assert isinstance(questions[0], ExtractedQuestion)
        assert questions[0].number == "1"

    def test_process_detects_multiple_choice(self, text_blocks):
        """Test detection of multiple choice questions."""
        postprocessor = OCRPostprocessor()
        metadata, questions, images_to_upload, unmapped, warnings = postprocessor.process(text_blocks)

        # First question should be multiple choice (has options A, B, C, D)
        if len(questions) > 0:
            first_question = questions[0]
            if first_question.options:
                assert first_question.question_type == QuestionType.MULTIPLA_ESCOLHA

    def test_process_generates_warnings(self, text_blocks):
        """Test that warnings are generated for low confidence fields."""
        postprocessor = OCRPostprocessor(low_confidence_threshold=0.95)
        metadata, questions, images_to_upload, unmapped, warnings = postprocessor.process(text_blocks)

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
        text = "\"Hello\" and 'world'"
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
        assert QuestionType.MULTIPLA_ESCOLHA.value == "multipla_escolha"
        assert QuestionType.DISSERTATIVA.value == "dissertativa"
        assert QuestionType.UNKNOWN.value == "unknown"


class TestExtractedQuestion:
    """Tests for ExtractedQuestion dataclass."""

    def test_question_confidence_calculation(self):
        """Test overall confidence calculation."""
        question = ExtractedQuestion(
            number=1,
            text="Test question",
            text_confidence=0.9,
            question_type=QuestionType.DISSERTATIVA,
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
            question_type=QuestionType.MULTIPLA_ESCOLHA,
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
            question_type=QuestionType.DISSERTATIVA,
            question_type_confidence=0.9,
            cotacao=5.0,
            cotacao_confidence=0.85,
        )

        result = question.to_dict()

        assert result["number"] == 1
        assert result["text"]["value"] == "What is 2+2?"
        assert result["type"] == "dissertativa"
        assert result["cotacao"] == 5.0


# =============================================================================
# OCR Engine Tests
# =============================================================================

class TestOCREngine:
    """Tests for OCREngine class."""

    @pytest.mark.parametrize("error", [ValueError("bad page"), RuntimeError("renderer failed")])
    def test_page_feeder_notifies_worker_when_lazy_rasterization_fails(self, error):
        connection = MagicMock()

        def pages():
            yield np.zeros((2, 2, 3), dtype=np.uint8)
            raise error

        OCRProcessWorker._feed_pages(connection, pages(), [0], [])

        messages = [call.args[0] for call in connection.send.call_args_list]
        assert [message["type"] for message in messages] == ["page", "feed_error"]
        assert messages[-1]["error_type"] == type(error).__name__

    def test_page_feeder_reports_unexpected_send_errors_when_the_pipe_remains_usable(self):
        connection = MagicMock()
        sent = []

        def send(message):
            if message["type"] == "page":
                raise RuntimeError("page could not be serialized")
            sent.append(message)

        connection.send.side_effect = send
        pages = [np.zeros((2, 2, 3), dtype=np.uint8)]

        OCRProcessWorker._feed_pages(connection, pages, [0], [])

        assert [message["type"] for message in sent] == ["feed_error"]
        assert sent[0]["error_type"] == "RuntimeError"

    def test_ocr_process_preserves_lazy_page_input_failure(self, monkeypatch):
        from app.ocr import engine as engine_module

        class Connection:
            def __init__(self):
                self.incoming = iter([
                    {"type": "progress", "request_id": "req-test", "page_count": 2},
                    {"type": "page", "image": np.zeros((2, 2, 3)),
                     "source_file_index": 0, "native_text_page": None},
                    {"type": "feed_error", "error_type": "RuntimeError"},
                    {"type": "shutdown"},
                ])
                self.sent = []

            def recv(self):
                return next(self.incoming)

            def send(self, message):
                self.sent.append(message)

            def close(self):
                pass

        class Engine:
            def __init__(self, **_kwargs):
                pass

            def initialize(self):
                pass

            def process_images_with_progress(self, pages, *_args, **_kwargs):
                list(pages)

            def shutdown(self):
                pass

        connection = Connection()
        monkeypatch.setattr(engine_module, "OCREngine", Engine)

        engine_module._ocr_process_main(connection)

        assert connection.sent[0] == {"type": "ready"}
        assert connection.sent[1] == {
            "type": "input_error",
            "error_type": "OCRPageInputError",
        }

    def test_fallback_reuses_primary_detector_at_higher_resolution_when_configured_identically(
        self, sample_image, monkeypatch
    ):
        engine = OCREngine()
        engine._initialized = True
        engine._ocr = MagicMock()
        engine._ocr.predict.return_value = [{"rec_texts": ["1."], "rec_scores": [0.9]}]
        monkeypatch.setattr("app.ocr.engine.settings.ocr_fallback_det_model_name", engine.det_model_name)
        paddle_ocr = MagicMock()
        monkeypatch.setattr("app.ocr.engine._get_paddle_ocr", lambda: paddle_ocr)

        result = engine._run_ocr(sample_image, fallback=True)

        assert len(result) == 1
        engine._ocr.predict.assert_called_once_with(sample_image, text_det_limit_side_len=1920)
        paddle_ocr.assert_not_called()
        assert engine._fallback_ocr is None

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
        mock_instance.predict.return_value = to_predict([
            [
                [[[10, 10], [200, 10], [200, 40], [10, 40]], ("Test text", 0.95)],
            ]
        ])

        engine = OCREngine()
        result = engine.process_image(sample_image)

        assert isinstance(result, OCRResult)
        assert result.status in ["success", "partial", "error"]
        assert result.processing_time_ms >= 0

    def test_searchable_pdf_text_skips_text_ocr_and_keeps_question_content(self, sample_image, monkeypatch):
        engine = OCREngine()
        preprocessed = PreprocessingResult(
            image=sample_image,
            original_size=(sample_image.shape[1], sample_image.shape[0]),
            processed_size=(sample_image.shape[1], sample_image.shape[0]),
            rotation_angle=0.0,
            image_hash="native-pdf-fixture",
            preprocessing_applied=[],
        )
        monkeypatch.setattr(engine.preprocessor, "preprocess", Mock(return_value=preprocessed))
        monkeypatch.setattr(engine, "_detect_layout", Mock(return_value=None))
        run_ocr = Mock(side_effect=AssertionError("searchable PDF text should bypass text OCR"))
        monkeypatch.setattr(engine, "_run_ocr", run_ocr)
        monkeypatch.setattr("app.ocr.engine.settings.ocr_fallback_det_model_name", "")
        lines = [
            {"text": "1. Determine a velocidade da partícula para os dados apresentados.", "bbox": (20, 20, 620, 50)},
            {"text": "Sabendo que a velocidade inicial é v0 = 2 m/s.", "bbox": (20, 70, 600, 100)},
            {"text": "Considere aceleração constante a = 3 m/s2 e tempo t = 4 s.", "bbox": (20, 120, 620, 150)},
        ]

        result = engine.process_image(
            sample_image,
            native_text_page={"width": sample_image.shape[1], "height": sample_image.shape[0], "lines": lines},
        )

        run_ocr.assert_not_called()
        assert result.status in ("success", "partial")
        assert result.questions

    def test_native_pdf_text_with_blank_formula_slots_requires_review(self, sample_image, monkeypatch):
        engine = OCREngine()
        preprocessed = PreprocessingResult(
            image=sample_image,
            original_size=(sample_image.shape[1], sample_image.shape[0]),
            processed_size=(sample_image.shape[1], sample_image.shape[0]),
            rotation_angle=0.0,
            image_hash="native-pdf-formula-gap",
            preprocessing_applied=[],
        )
        monkeypatch.setattr(engine.preprocessor, "preprocess", Mock(return_value=preprocessed))
        monkeypatch.setattr(engine, "_detect_layout", Mock(return_value=None))
        monkeypatch.setattr(engine, "_run_ocr", Mock(side_effect=AssertionError("native text should keep the fast path")))
        monkeypatch.setattr("app.ocr.engine.settings.ocr_fallback_det_model_name", "")
        lines = [
            {"text": "1) A velocidade inicial é      com ângulo de 30 graus.", "bbox": (20, 20, 620, 50)},
            {"text": "A altura máxima foi de      e o alcance de      .", "bbox": (20, 70, 620, 100)},
            {"text": "Calcule o tempo, a velocidade e a trajetória do projéctil.", "bbox": (20, 120, 620, 150)},
        ]

        result = engine.process_image(
            sample_image,
            native_text_page={"width": sample_image.shape[1], "height": sample_image.shape[0], "lines": lines},
        )

        assert result.status in ("success", "partial")
        warning = next(w for w in result.warnings if w.code == "NATIVE_TEXT_GAP")
        assert warning.field == "formulas"
        assert "documento original" in warning.message
        assert "velocidade" in result.questions[0].text.lower()

    @patch("app.ocr.engine.PaddleOCR")
    def test_process_image_with_error(self, mock_paddle, sample_image):
        """Test error handling during processing."""
        mock_instance = MagicMock()
        mock_paddle.return_value = mock_instance
        mock_instance.predict.side_effect = Exception("OCR failed")

        engine = OCREngine()
        result = engine.process_image(sample_image)

        assert result.status == "error"
        assert result.error_message is not None

    @patch("app.ocr.engine.PaddleOCR")
    def test_process_bytes(self, mock_paddle, sample_image_bytes):
        """Test processing image from bytes."""
        mock_instance = MagicMock()
        mock_paddle.return_value = mock_instance
        mock_instance.predict.return_value = to_predict([[]])

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
        mock_instance.predict.return_value = to_predict([
            [
                [[[10, 10], [200, 10], [200, 40], [10, 40]], ("Page content", 0.9)],
            ]
        ])

        engine = OCREngine()
        images = [sample_image, sample_image]
        result = engine.process_images(images)

        assert isinstance(result, OCRResult)
        assert result.document.page_count == 2

    def test_multi_page_aggregation_keeps_question_continuation_on_next_page(self, sample_image):
        engine = OCREngine()
        engine.postprocessor = OCRPostprocessor(repair_text=False, filter_noise=False)
        first = self._page_result("success")
        first.page_context = PageOcrContext(
            page_index=0,
            source_file_index=0,
            text_blocks=[TextBlock("1. Determine a aceleração", 0.95, (10, 20, 500, 40), 0)],
            layout_regions=None,
            width=600,
            height=800,
        )
        second = self._page_result("partial")
        second.page_context = PageOcrContext(
            page_index=1,
            source_file_index=0,
            text_blocks=[
                TextBlock("com base nos dados fornecidos.", 0.94, (10, 20, 500, 40), 1),
                TextBlock("2. Calcule a velocidade.", 0.96, (10, 70, 500, 90), 1),
            ],
            layout_regions=None,
            width=600,
            height=800,
        )
        engine.process_image = MagicMock(side_effect=[first, second])

        result = engine.process_images([sample_image, sample_image], request_id="req-page-continuation")

        assert [question.number for question in result.questions] == ["1", "2"]
        assert "com base nos dados fornecidos." in result.questions[0].text
        assert "com base nos dados fornecidos." in result.questions[0].raw_text

    @patch("app.ocr.engine.PaddleOCR")
    def test_process_empty_images_list(self, mock_paddle):
        """Test processing empty images list."""
        engine = OCREngine()
        result = engine.process_images([])

        assert result.status == "error"
        assert "No images provided" in result.error_message

    def _page_result(self, status: str, unmapped=None) -> OCRResult:
        return OCRResult(
            status=status,
            request_id="req-page",
            processing_time_ms=1,
            overall_confidence=0.9 if status != "error" else 0.0,
            document=DocumentInfo(page_count=1, main_language="pt", has_tables=False),
            metadata=ExtractedMetadata(),
            questions=[],
            images_to_upload=[],
            unmapped_content=unmapped or [],
            warnings=[],
        )

    def test_process_images_with_text_but_no_questions_is_partial_not_error(self, sample_image):
        """Text read without questions is a reading, not a server failure (#292)."""
        engine = OCREngine()
        text = [UnmappedContent(0, "Tabela de valores", 0.9, "Texto lido sem perguntas reconhecidas")]
        engine.process_image = MagicMock(return_value=self._page_result("success", text))

        result = engine.process_images([sample_image, sample_image], request_id="req-292")

        assert result.status == "partial"
        assert result.questions == []
        assert {item.text for item in result.unmapped_content} == {"Tabela de valores"}

    def test_page_without_structured_questions_makes_multi_page_result_partial(self, sample_image):
        """A high-confidence page must not hide another page where no question was structured."""
        engine = OCREngine()
        first = self._page_result("success", [UnmappedContent(0, "1. Questão", 0.9)])
        first.questions = [MagicMock(confidence=0.95)]
        second = self._page_result("partial", [UnmappedContent(1, "texto sem questão", 0.96)])
        engine.process_image = MagicMock(side_effect=[first, second])

        result = engine.process_images([sample_image, sample_image], request_id="req-partial-page")

        assert result.status == "partial"
        assert len(result.questions) == 1
        assert result.warnings == []

    def test_process_images_with_no_text_at_all_is_still_error(self, sample_image):
        """A document the OCR read nothing from keeps the error status."""
        engine = OCREngine()
        engine.process_image = MagicMock(return_value=self._page_result("error"))

        result = engine.process_images([sample_image, sample_image], request_id="req-292")

        assert result.status == "error"

    def test_main_language_ties_prefer_configured_language(self, sample_image):
        engine = OCREngine()
        first = self._page_result("success")
        first.document.main_language = "en"
        second = self._page_result("success")
        second.document.main_language = "pt"
        engine.process_image = MagicMock(side_effect=[first, second])

        result = engine.process_images([sample_image, sample_image], request_id="req-language-tie")

        assert result.document.main_language == "pt"

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
            images_to_upload=[],
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
            images_to_upload=[],
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

        from app.ocr.engine import OCRProcessWorker
        assert isinstance(engine, OCRProcessWorker)

    @patch("app.ocr.engine._default_engine", None)
    def test_get_engine_returns_same_instance(self):
        """Test that get_engine returns the same instance."""
        engine1 = get_engine()
        engine2 = get_engine()

        assert engine1 is engine2

    @patch("app.ocr.engine.OCRProcessWorker.initialize")
    @patch("app.ocr.engine._default_engine", None)
    def test_initialize_engine(self, mock_initialize):
        """Test initialize_engine function."""
        initialize_engine()
        mock_initialize.assert_called_once()

    @patch("app.ocr.engine._default_engine")
    def test_shutdown_engine(self, mock_engine):
        """Test shutdown_engine function."""
        mock_engine.shutdown = MagicMock()

        shutdown_engine()

        # Should call shutdown on the engine
        # Note: actual behavior depends on global state


# =============================================================================
# PaddleOCR 3.x: construction, result format, layout
# =============================================================================

class TestPaddleOcr3:
    """The engine speaks PaddleOCR 3.x: named models, oneDNN on, ``predict`` results and a layout step."""

    @patch("app.ocr.engine.PaddleOCR")
    def test_initialize_names_the_models_and_turns_onednn_on(self, mock_paddle):
        from app.config import settings

        OCREngine().initialize()

        options = mock_paddle.call_args.kwargs
        # PP-OCRv4 has no Portuguese model in PaddleOCR 3.x: asking for it by lang/ocr_version is a 500 per request
        assert "ocr_version" not in options and "lang" not in options
        assert options["text_detection_model_name"] == settings.ocr_det_model_name
        assert options["text_recognition_model_name"].startswith("latin_")
        assert options["enable_mkldnn"] is True
        assert options["device"] == "cpu"

    def test_parse_reads_the_predict_dicts_into_text_lines_with_boxes(self):
        import numpy as np

        raw = [{
            "rec_texts": ["1. Calcule x", "", "A) 5"],
            "rec_scores": [0.97, 0.9, 0.88],
            "rec_polys": [
                np.array([[10, 10], [200, 10], [200, 40], [10, 40]]),
                np.array([[0, 0], [1, 0], [1, 1], [0, 1]]),
                np.array([[30, 50], [100, 52], [101, 80], [29, 78]]),
            ],
        }]
        blocks = OCREngine()._parse_ocr_results(raw, page_index=2)

        assert [(b.text, b.bbox, b.page_index) for b in blocks] == [
            ("1. Calcule x", (10, 10, 200, 40), 2),
            ("A) 5", (29, 50, 101, 80), 2),
        ]
        assert blocks[0].confidence == pytest.approx(0.97)

    def test_a_layout_failure_costs_the_figures_and_not_the_text(self, monkeypatch):
        import numpy as np
        from app.config import settings

        monkeypatch.setattr(settings, "ocr_layout_enabled", True)
        engine = OCREngine()
        engine._layout.detect = MagicMock(side_effect=RuntimeError("model missing"))

        assert engine._detect_layout(np.zeros((10, 10, 3), dtype="uint8"), "req-1") is None

    @patch("app.ocr.engine.PaddleOCR")
    def test_a_figure_under_a_question_comes_out_tied_to_it(self, mock_paddle, monkeypatch):
        import numpy as np
        from app.config import settings
        from app.ocr.layout import LayoutRegion

        def row(text, y):
            return [[[60, y], [480, y], [480, y + 22], [60, y + 22]], (text, 0.95)]

        mock_paddle.return_value.predict.return_value = to_predict([[
            row("REPÚBLICA DE ANGOLA", 40), row("PROVA DO EXAME DE FÍSICA", 100), row("Série: A", 140),
            row("1. Sobre a teoria dos semicondutores, responda:", 330),
            row("2. Encontre as correntes representadas no circuito da figura abaixo.", 560),
            row("3. Apresente a polarização dos transístores no modo saturação (2V)", 900),
        ]])
        monkeypatch.setattr(settings, "ocr_layout_enabled", True)
        engine = OCREngine()
        engine._layout.detect = MagicMock(return_value=[LayoutRegion("figure", "image", 0.9, (239, 590, 540, 780))])

        result = engine.process_image(np.full((1280, 720, 3), 255, dtype="uint8"))

        figures = [i for i in result.images_to_upload if i.region.startswith("questao_")]
        assert [(f.region, f.kind) for f in figures] == [("questao_2", "figure")]
        assert result.to_dict()["imagesToUpload"][-1]["kind"] == "figure" or any(
            img.get("kind") == "figure" for img in result.to_dict()["imagesToUpload"]
        )


class TestVlPipeline:
    """The opt-in PaddleOCR-VL pipeline's reading of a parsed page (the model itself needs a GPU)."""

    def test_the_parsed_blocks_become_text_lines_and_figure_regions(self):
        import numpy as np
        from types import SimpleNamespace as Block

        engine = OCREngine()
        engine._initialized = True
        engine._vl = MagicMock()
        engine._vl.predict.return_value = [{"parsing_res_list": [
            Block(label="header", content="REPÚBLICA DE ANGOLA", bbox=[0, 0, 700, 30]),
            Block(label="text", content="1. Sobre os semicondutores\na) Quantos electrões?", bbox=[40, 100, 600, 160]),
            Block(label="image", content="", bbox=[200, 200, 500, 400]),
            Block(label="table", content="<table/>", bbox=[50, 420, 600, 520]),
            Block(label="display_formula", content="B=B_{max}e^{-2t}", bbox=[100, 540, 400, 580]),
        ]}]

        blocks, regions = engine._run_vl(np.zeros((700, 700, 3), dtype="uint8"), page_index=1)

        assert [b.text for b in blocks] == ["1. Sobre os semicondutores", "a) Quantos electrões?"]
        assert all(b.page_index == 1 for b in blocks) and blocks[0].bbox[1] < blocks[1].bbox[1]
        assert [(r.kind, r.bbox) for r in regions] == [
            ("figure", (200, 200, 500, 400)), ("table", (50, 420, 600, 520)), ("formula", (100, 540, 400, 580)),
        ]


# =============================================================================
# Integration Tests (marked for optional execution)
# =============================================================================

@pytest.mark.integration
class TestOCRIntegration:
    """Integration tests that require actual PaddleOCR."""

    @pytest.mark.slow
    def test_real_paddle_predict_accepts_fallback_detection_limit(self, monkeypatch):
        """Exercise the fallback keyword against PaddleOCR, not a mocked predict()."""
        fixture_path = Path(__file__).parent / "fixtures" / "ogi_12b.png"
        image = cv2.imread(str(fixture_path))
        assert image is not None, f"Could not read integration fixture: {fixture_path}"

        engine = OCREngine()
        monkeypatch.setattr(
            "app.ocr.engine.settings.ocr_fallback_det_model_name",
            engine.det_model_name,
        )

        results = engine._run_ocr(image, fallback=True)

        assert isinstance(results, list)
        assert results

    @pytest.mark.slow
    def test_real_exam_upload_pipeline_reads_image_and_pdf(self):
        """Exercise the real upload path with a versioned exam image and PDF.

        The old integration test sent synthetic black bars directly to the engine
        and accepted an empty/``error`` result. This test keeps the real fixture
        out of the unit suite, sends both supported upload formats through
        FastAPI, and requires structured OCR content in the response.
        """
        from fastapi.testclient import TestClient
        from app.main import app

        fixture_path = Path(__file__).parent / "fixtures" / "ogi_12b.png"
        image_bytes = fixture_path.read_bytes()

        pdf = fitz.open()
        # Match a 1280 px scan at 300 DPI; using 1280 pt here would make the
        # PDF renderer expand the same page to roughly 5334 px per side.
        page_size = 1280 * 72 / 300
        page = pdf.new_page(width=page_size, height=page_size)
        page.insert_image(fitz.Rect(0, 0, page_size, page_size), stream=image_bytes)
        pdf_bytes = pdf.tobytes()
        pdf.close()

        with TestClient(app) as client:
            response = client.post(
                "/ocr/v1/extract",
                files=[
                    ("images", (fixture_path.name, image_bytes, "image/png")),
                    ("images", ("ogi_12b.pdf", pdf_bytes, "application/pdf")),
                ],
            )

        assert response.status_code in {200, 207}, response.text
        payload = response.json()
        assert payload["status"] in {"success", "partial"}
        assert payload.get("errorMessage") is None
        assert payload["document"]["pageCount"] == 2
        assert payload["questions"], "real OCR returned no structured questions"

        serialized = response.text.casefold()
        assert any(
            marker in serialized
            for marker in ("república", "republica", "prova", "ogi", "aprovisionamento")
        ), "real OCR returned no recognizable exam content"


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
        mock_instance.predict.return_value = to_predict([[]])

        engine = OCREngine()
        result = await engine.process_image_async(sample_image)

        assert isinstance(result, OCRResult)

    @patch("app.ocr.engine.PaddleOCR")
    async def test_process_bytes_async(self, mock_paddle, sample_image_bytes):
        """Test async bytes processing."""
        mock_instance = MagicMock()
        mock_paddle.return_value = mock_instance
        mock_instance.predict.return_value = to_predict([[]])

        engine = OCREngine()
        result = await engine.process_bytes_async(sample_image_bytes)

        assert isinstance(result, OCRResult)


if __name__ == "__main__":
    pytest.main([__file__, "-v"])

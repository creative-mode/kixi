"""
Image Preprocessing Module for OCR Service

Provides utilities for image preprocessing to improve OCR accuracy:
- Deskewing (rotation correction)
- Noise reduction
- Contrast enhancement
- Binarization
- Resolution normalization
"""

import io
import hashlib
from typing import Optional, Tuple, List
from dataclasses import dataclass

import cv2
import numpy as np
from PIL import Image

from app.config import settings


@dataclass
class PreprocessingResult:
    """Result of image preprocessing."""
    image: np.ndarray
    original_size: Tuple[int, int]
    processed_size: Tuple[int, int]
    rotation_angle: float
    image_hash: str
    preprocessing_applied: List[str]


class ImagePreprocessor:
    """
    Image preprocessor for OCR optimization.

    Applies various preprocessing techniques to improve OCR accuracy:
    - Grayscale conversion
    - Noise reduction
    - Deskewing
    - Contrast enhancement
    - Binarization
    """

    def __init__(
        self,
        enable_deskew: bool = True,
        enable_denoise: bool = True,
        target_dpi: int = 300,
        min_confidence_threshold: float = 0.5,
    ):
        """
        Initialize the image preprocessor.

        Args:
            enable_deskew: Whether to apply deskewing
            enable_denoise: Whether to apply noise reduction
            target_dpi: Target DPI for resolution normalization
            min_confidence_threshold: Minimum confidence threshold
        """
        self.enable_deskew = enable_deskew
        self.enable_denoise = enable_denoise
        self.target_dpi = target_dpi
        self.min_confidence_threshold = min_confidence_threshold

    def preprocess(
        self,
        image: np.ndarray,
        apply_binarization: bool = False,
    ) -> PreprocessingResult:
        """
        Apply full preprocessing pipeline to an image.

        Args:
            image: Input image as numpy array (BGR or grayscale)
            apply_binarization: Whether to apply adaptive binarization

        Returns:
            PreprocessingResult with processed image and metadata
        """
        preprocessing_applied = []
        original_size = (image.shape[1], image.shape[0])
        rotation_angle = 0.0

        # Calculate image hash for caching
        image_hash = self._calculate_hash(image)

        # Convert to grayscale if needed
        if len(image.shape) == 3 and image.shape[2] == 3:
            gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
            preprocessing_applied.append("grayscale_conversion")
        else:
            gray = image.copy()

        # Apply noise reduction
        if self.enable_denoise:
            gray = self._denoise(gray)
            preprocessing_applied.append("denoise")

        # Apply deskewing
        if self.enable_deskew:
            gray, rotation_angle = self._deskew(gray)
            if abs(rotation_angle) > 0.1:
                preprocessing_applied.append(f"deskew_{rotation_angle:.2f}deg")

        # Apply contrast enhancement
        gray = self._enhance_contrast(gray)
        preprocessing_applied.append("contrast_enhancement")

        # Apply binarization if requested
        if apply_binarization:
            gray = self._binarize(gray)
            preprocessing_applied.append("binarization")

        # Convert back to BGR for PaddleOCR (expects color images)
        processed = cv2.cvtColor(gray, cv2.COLOR_GRAY2BGR)

        processed_size = (processed.shape[1], processed.shape[0])

        return PreprocessingResult(
            image=processed,
            original_size=original_size,
            processed_size=processed_size,
            rotation_angle=rotation_angle,
            image_hash=image_hash,
            preprocessing_applied=preprocessing_applied,
        )

    def preprocess_for_ocr(self, image: np.ndarray) -> np.ndarray:
        """
        Apply minimal preprocessing optimized for PaddleOCR.

        PaddleOCR has its own preprocessing, so we apply only
        essential corrections that improve accuracy.

        Args:
            image: Input image as numpy array

        Returns:
            Preprocessed image ready for OCR
        """
        result = self.preprocess(image, apply_binarization=False)
        return result.image

    def _denoise(self, image: np.ndarray) -> np.ndarray:
        """
        Apply noise reduction using non-local means denoising.

        Args:
            image: Grayscale input image

        Returns:
            Denoised image
        """
        # Use fastNlMeansDenoising for grayscale images
        # h=10 is a good balance between noise removal and detail preservation
        denoised = cv2.fastNlMeansDenoising(
            image,
            None,
            h=10,
            templateWindowSize=7,
            searchWindowSize=21,
        )
        return denoised

    def _deskew(self, image: np.ndarray) -> Tuple[np.ndarray, float]:
        """
        Detect and correct image skew.

        Uses Hough Line Transform to detect the dominant angle
        and rotates the image to correct it.

        Args:
            image: Grayscale input image

        Returns:
            Tuple of (deskewed image, rotation angle in degrees)
        """
        # Detect edges
        edges = cv2.Canny(image, 50, 150, apertureSize=3)

        # Detect lines using Hough Transform
        lines = cv2.HoughLinesP(
            edges,
            rho=1,
            theta=np.pi / 180,
            threshold=100,
            minLineLength=100,
            maxLineGap=10,
        )

        if lines is None:
            return image, 0.0

        # Calculate angles of detected lines
        angles = []
        for line in lines:
            x1, y1, x2, y2 = line[0]
            if x2 - x1 != 0:
                angle = np.degrees(np.arctan2(y2 - y1, x2 - x1))
                # Only consider small angles (likely text lines)
                if abs(angle) < 45:
                    angles.append(angle)

        if not angles:
            return image, 0.0

        # Use median angle to avoid outliers
        median_angle = np.median(angles)

        # Only correct if the angle is significant but not too large
        if abs(median_angle) < 0.5 or abs(median_angle) > 15:
            return image, 0.0

        # Rotate the image
        height, width = image.shape[:2]
        center = (width // 2, height // 2)
        rotation_matrix = cv2.getRotationMatrix2D(center, median_angle, 1.0)

        # Calculate new image bounds
        cos = np.abs(rotation_matrix[0, 0])
        sin = np.abs(rotation_matrix[0, 1])
        new_width = int((height * sin) + (width * cos))
        new_height = int((height * cos) + (width * sin))

        # Adjust the rotation matrix
        rotation_matrix[0, 2] += (new_width / 2) - center[0]
        rotation_matrix[1, 2] += (new_height / 2) - center[1]

        # Apply rotation
        rotated = cv2.warpAffine(
            image,
            rotation_matrix,
            (new_width, new_height),
            flags=cv2.INTER_LINEAR,
            borderMode=cv2.BORDER_REPLICATE,
        )

        return rotated, median_angle

    def _enhance_contrast(self, image: np.ndarray) -> np.ndarray:
        """
        Enhance image contrast using CLAHE.

        Contrast Limited Adaptive Histogram Equalization (CLAHE)
        improves local contrast while limiting noise amplification.

        Args:
            image: Grayscale input image

        Returns:
            Contrast-enhanced image
        """
        clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
        enhanced = clahe.apply(image)
        return enhanced

    def _binarize(self, image: np.ndarray) -> np.ndarray:
        """
        Apply adaptive binarization.

        Uses Gaussian adaptive thresholding for better results
        with varying lighting conditions.

        Args:
            image: Grayscale input image

        Returns:
            Binarized image
        """
        binary = cv2.adaptiveThreshold(
            image,
            255,
            cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
            cv2.THRESH_BINARY,
            blockSize=11,
            C=2,
        )
        return binary

    def _calculate_hash(self, image: np.ndarray) -> str:
        """
        Calculate SHA-256 hash of the image for caching.

        Args:
            image: Input image

        Returns:
            Hexadecimal hash string
        """
        image_bytes = image.tobytes()
        return hashlib.sha256(image_bytes).hexdigest()

    @staticmethod
    def resize_if_needed(
        image: np.ndarray,
        max_dimension: int = 4096,
        min_dimension: int = 32,
    ) -> Tuple[np.ndarray, float]:
        """
        Resize image if it exceeds maximum dimensions.

        Args:
            image: Input image
            max_dimension: Maximum allowed dimension
            min_dimension: Minimum allowed dimension

        Returns:
            Tuple of (resized image, scale factor)
        """
        height, width = image.shape[:2]
        max_current = max(height, width)
        min_current = min(height, width)

        scale = 1.0

        # Scale down if too large
        if max_current > max_dimension:
            scale = max_dimension / max_current
        # Scale up if too small
        elif min_current < min_dimension:
            scale = min_dimension / min_current

        if scale != 1.0:
            new_width = int(width * scale)
            new_height = int(height * scale)
            interpolation = cv2.INTER_AREA if scale < 1 else cv2.INTER_LINEAR
            image = cv2.resize(image, (new_width, new_height), interpolation=interpolation)

        return image, scale


def load_image_from_bytes(image_bytes: bytes) -> np.ndarray:
    """
    Load an image from bytes.

    Args:
        image_bytes: Raw image bytes

    Returns:
        Image as numpy array (BGR format)

    Raises:
        ValueError: If the image cannot be decoded
    """
    nparr = np.frombuffer(image_bytes, np.uint8)
    image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

    if image is None:
        raise ValueError("Failed to decode image from bytes")

    return image


def load_image_from_pil(pil_image: Image.Image) -> np.ndarray:
    """
    Convert PIL Image to numpy array (BGR format).

    Args:
        pil_image: PIL Image object

    Returns:
        Image as numpy array (BGR format)
    """
    # Ensure RGB mode
    if pil_image.mode != "RGB":
        pil_image = pil_image.convert("RGB")

    # Convert to numpy array
    image = np.array(pil_image)

    # Convert RGB to BGR for OpenCV
    image = cv2.cvtColor(image, cv2.COLOR_RGB2BGR)

    return image


def image_to_bytes(image: np.ndarray, format: str = "PNG") -> bytes:
    """
    Convert numpy array image to bytes.

    Args:
        image: Image as numpy array
        format: Output format (PNG, JPEG, etc.)

    Returns:
        Image as bytes
    """
    # Convert to PIL Image
    if len(image.shape) == 3:
        image_rgb = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
    else:
        image_rgb = image

    pil_image = Image.fromarray(image_rgb)

    # Save to bytes
    buffer = io.BytesIO()
    pil_image.save(buffer, format=format)
    buffer.seek(0)

    return buffer.getvalue()


# Default preprocessor instance
default_preprocessor = ImagePreprocessor(
    enable_deskew=settings.enable_deskew,
    enable_denoise=settings.enable_denoise,
    target_dpi=settings.target_dpi,
    min_confidence_threshold=settings.min_confidence_threshold,
)

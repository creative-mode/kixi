# PaddleOCR-VL engine implementation
import pytesseract
from PIL import Image
from typing import Optional


class OCREngine:
    """
    OCR Engine using Tesseract for text extraction.
    """
    
    def __init__(self, lang: str = "por+eng"):
        """
        Initialize OCR Engine.
        
        Args:
            lang: Language(s) to use for OCR. Default is Portuguese + English.
        """
        self.lang = lang
        self.config = "--oem 3 --psm 6"  # LSTM engine, uniform text block
    
    def extract_text(self, image: Image.Image) -> dict:
        """
        Extract text from a PIL Image.
        
        Args:
            image: PIL Image object
            
        Returns:
            Dictionary with extracted text and confidence
        """
        try:
            # Get text with confidence data
            data = pytesseract.image_to_data(
                image, 
                lang=self.lang, 
                config=self.config,
                output_type=pytesseract.Output.DICT
            )
            
            # Calculate average confidence
            confidences = [int(c) for c in data['conf'] if int(c) > 0]
            avg_confidence = sum(confidences) / len(confidences) if confidences else 0
            
            # Get full text
            text = pytesseract.image_to_string(
                image, 
                lang=self.lang, 
                config=self.config
            )
            
            return {
                "text": text.strip(),
                "confidence": round(avg_confidence, 2),
                "word_count": len([w for w in data['text'] if w.strip()])
            }
            
        except Exception as e:
            raise RuntimeError(f"OCR extraction failed: {str(e)}")
    
    def extract_text_with_boxes(self, image: Image.Image) -> dict:
        """
        Extract text with bounding box coordinates.
        
        Args:
            image: PIL Image object
            
        Returns:
            Dictionary with text blocks and their coordinates
        """
        try:
            data = pytesseract.image_to_data(
                image,
                lang=self.lang,
                config=self.config,
                output_type=pytesseract.Output.DICT
            )
            
            blocks = []
            for i, text in enumerate(data['text']):
                if text.strip():
                    blocks.append({
                        "text": text,
                        "x": data['left'][i],
                        "y": data['top'][i],
                        "width": data['width'][i],
                        "height": data['height'][i],
                        "confidence": data['conf'][i]
                    })
            
            return {"blocks": blocks}
            
        except Exception as e:
            raise RuntimeError(f"OCR extraction with boxes failed: {str(e)}")

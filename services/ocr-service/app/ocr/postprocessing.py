"""
OCR Postprocessing Module

Provides utilities for processing and structuring raw OCR output:
- Text normalization and cleaning
- Question detection and segmentation
- Metadata extraction (school year, term, subject, etc.)
- Question type inference (multiple_choice, short_answer, development, true_false)
- Option extraction for multiple choice questions
- Confidence score aggregation
"""

import re
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any, Tuple
from enum import Enum


class QuestionType(str, Enum):
    """Supported question types."""
    MULTIPLE_CHOICE = "multiple_choice"
    SHORT_ANSWER = "short_answer"
    DEVELOPMENT = "development"
    TRUE_FALSE = "true_false"
    UNKNOWN = "unknown"


@dataclass
class TextBlock:
    """Represents a block of text with position and confidence."""
    text: str
    confidence: float
    bbox: Tuple[int, int, int, int]  # x1, y1, x2, y2
    page_index: int = 0
    line_index: int = 0


@dataclass
class ExtractedOption:
    """Represents an extracted question option."""
    option_label: str
    option_text: str
    confidence: float


@dataclass
class ExtractedQuestion:
    """Represents an extracted question with all its components."""
    number: int
    text: str
    text_confidence: float
    question_type: QuestionType
    question_type_confidence: float
    max_score: Optional[float] = None
    max_score_confidence: float = 0.0
    options: List[ExtractedOption] = field(default_factory=list)
    page_index: int = 0
    start_y: int = 0
    end_y: int = 0

    @property
    def confidence(self) -> float:
        """Calculate overall question confidence."""
        confidences = [self.text_confidence, self.question_type_confidence]
        if self.options:
            confidences.extend(opt.confidence for opt in self.options)
        if self.max_score is not None:
            confidences.append(self.max_score_confidence)
        return sum(confidences) / len(confidences) if confidences else 0.0

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return {
            "number": self.number,
            "confidence": round(self.confidence, 3),
            "text": {"value": self.text, "confidence": round(self.text_confidence, 3)},
            "questionType": {"value": self.question_type.value, "confidence": round(self.question_type_confidence, 3)},
            "maxScore": {"value": self.max_score, "confidence": round(self.max_score_confidence, 3)} if self.max_score else {"value": None, "confidence": 0.0},
            "options": [
                {
                    "optionLabel": opt.option_label,
                    "optionText": opt.option_text,
                    "confidence": round(opt.confidence, 3),
                }
                for opt in self.options
            ],
            "pageIndex": self.page_index,
            "startY": self.start_y,
            "endY": self.end_y,
        }


@dataclass
class MetadataField:
    """Represents an extracted metadata field with confidence."""
    value: Optional[Any]
    confidence: float

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return {"value": self.value, "confidence": round(self.confidence, 3)}


@dataclass
class ExtractedMetadata:
    """Represents extracted document metadata."""
    school_year: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    term: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    subject: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    course: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    class_info: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    exam_type: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    duration_minutes: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    variant: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    title: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    instructions: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return {
            "schoolYear": self.school_year.to_dict(),
            "term": self.term.to_dict(),
            "subject": self.subject.to_dict(),
            "course": self.course.to_dict(),
            "class": self.class_info.to_dict(),
            "examType": self.exam_type.to_dict(),
            "durationMinutes": self.duration_minutes.to_dict(),
            "variant": self.variant.to_dict(),
            "title": self.title.to_dict(),
            "instructions": self.instructions.to_dict(),
        }


@dataclass
class UnmappedContent:
    """Represents content that couldn't be mapped to questions or metadata."""
    page_index: int
    text: str
    confidence: float

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return {
            "pageIndex": self.page_index,
            "text": self.text,
            "confidence": round(self.confidence, 3),
        }


@dataclass
class Warning:
    """Represents a processing warning."""
    code: str
    field: str
    confidence: float
    message: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        result = {
            "code": self.code,
            "field": self.field,
            "confidence": round(self.confidence, 3),
        }
        if self.message:
            result["message"] = self.message
        return result


class OCRPostprocessor:
    """
    Postprocessor for OCR results.

    Transforms raw OCR text blocks into structured data including:
    - Document metadata (school year, subject, exam type, etc.)
    - Questions with their types and options
    - Confidence scores for all extracted values
    """

    # Regex patterns for metadata extraction
    PATTERNS = {
        # School year patterns (e.g., "2024/2025", "Ano Letivo 2024-2025", "2024 - 2025")
        "school_year": [
            r"(?:ano\s*let[ií]vo|school\s*year)?\s*(\d{4})\s*[/-]\s*(\d{4})",
            r"(\d{4})\s*/\s*(\d{2,4})",
        ],
        # Term patterns (e.g., "1º Trimestre", "2nd Term", "III Trimestre")
        "term": [
            r"(\d)[ºª°]?\s*(?:trimestre|term|período|bimestre)",
            r"(?:trimestre|term|período|bimestre)\s*(\d)",
            r"(I{1,3}|IV)\s*(?:trimestre|term|período)",
            r"(primeiro|segundo|terceiro|quarto|first|second|third|fourth)\s*(?:trimestre|term|período)",
        ],
        # Subject patterns
        "subject": [
            r"(?:disciplina|subject|matéria|cadeira)[:\s]+([A-Za-záàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ\s\-]+)",
            r"(?:prova\s+de|exame\s+de|avaliação\s+de)[:\s]+([A-Za-záàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ\s\-]+)",
        ],
        # Duration patterns (e.g., "120 minutos", "2 horas", "Duration: 90 min")
        "duration": [
            r"(?:duração|duration|tempo)[:\s]*(\d+)\s*(?:min(?:utos)?|minutes?)",
            r"(\d+)\s*(?:min(?:utos)?|minutes?)",
            r"(\d+)\s*(?:horas?|hours?)\s*(?:e\s*(\d+)\s*min(?:utos)?)?",
        ],
        # Variant patterns (e.g., "Versão A", "Variant B", "Prova A")
        "variant": [
            r"(?:versão|variant|prova|versao)\s*([A-Za-z])",
            r"(?:grupo|group)\s*([A-Za-z])",
        ],
        # Class patterns (e.g., "12ª Classe", "10º Ano", "Class 9A")
        "class": [
            r"(\d{1,2})[ºª°]?\s*(?:classe|class|ano|grade|série)",
            r"(?:classe|class|turma)[:\s]*(\d{1,2})\s*([A-Za-z])?",
            r"(\d{1,2})[ºª°]?\s*(?:ano)?\s*-?\s*(?:turma)?\s*([A-Za-z])?",
        ],
        # Exam type patterns
        "exam_type": [
            r"(avaliação\s*(?:periódica|sumativa|formativa|diagnóstica|final|contínua))",
            r"(prova\s*(?:escrita|oral|prática|final|parcial))",
            r"(exame\s*(?:final|nacional|regional|de\s*época))",
            r"(teste\s*(?:escrito|sumativo|formativo))",
            r"(examination|assessment|test|exam|quiz)",
        ],
        # Question number patterns
        "question_number": [
            r"^(?:questão|pergunta|question|exercício|problema|item)\s*n?[ºª°]?\s*(\d+)",
            r"^(\d+)\s*[.)\-:]\s*",
            r"^([IVX]+)\s*[.)\-:]\s*",
        ],
        # Option patterns (e.g., "A)", "a.", "(A)", "1.")
        "option": [
            r"^\(?([A-Da-d])\)?[.):]\s*(.+)",
            r"^\(?(\d)\)?[.):]\s*(.+)",
            r"^([A-Da-d])\s*[-–—]\s*(.+)",
        ],
        # Score patterns (e.g., "(5 pontos)", "[10 pts]", "5 valores")
        "score": [
            r"[(\[]\s*(\d+(?:[.,]\d+)?)\s*(?:pontos?|pts?|valores?|marks?|points?)\s*[)\]]",
            r"(\d+(?:[.,]\d+)?)\s*(?:pontos?|pts?|valores?|marks?|points?)",
        ],
        # True/False patterns
        "true_false": [
            r"(?:verdadeiro|falso|true|false|v\/f|t\/f)",
            r"(?:certo|errado|correto|incorreto)",
        ],
    }

    # Keywords for question type inference
    QUESTION_TYPE_KEYWORDS = {
        QuestionType.MULTIPLE_CHOICE: [
            "escolha", "assinale", "marque", "alternativa", "opção",
            "select", "choose", "mark", "circle", "option",
        ],
        QuestionType.DEVELOPMENT: [
            "justifique", "explique", "desenvolva", "comente", "discuta",
            "argumente", "analise", "compare", "descreva", "fundamente",
            "justify", "explain", "discuss", "describe", "analyze", "compare",
        ],
        QuestionType.SHORT_ANSWER: [
            "calcule", "determine", "resolva", "encontre", "simplifique",
            "calculate", "solve", "find", "determine", "simplify", "compute",
        ],
        QuestionType.TRUE_FALSE: [
            "verdadeiro", "falso", "v/f", "certo", "errado",
            "true", "false", "t/f", "correct", "incorrect",
        ],
    }

    def __init__(
        self,
        min_confidence_threshold: float = 0.5,
        low_confidence_threshold: float = 0.8,
    ):
        """
        Initialize the postprocessor.

        Args:
            min_confidence_threshold: Minimum confidence to include results
            low_confidence_threshold: Threshold below which to add warnings
        """
        self.min_confidence_threshold = min_confidence_threshold
        self.low_confidence_threshold = low_confidence_threshold

    def process(
        self,
        text_blocks: List[TextBlock],
        page_count: int = 1,
    ) -> Tuple[ExtractedMetadata, List[ExtractedQuestion], List[UnmappedContent], List[Warning]]:
        """
        Process OCR text blocks into structured data.

        Args:
            text_blocks: List of text blocks from OCR
            page_count: Number of pages in the document

        Returns:
            Tuple of (metadata, questions, unmapped_content, warnings)
        """
        warnings = []
        unmapped = []

        # Sort blocks by page and position
        sorted_blocks = sorted(text_blocks, key=lambda b: (b.page_index, b.bbox[1], b.bbox[0]))

        # Extract metadata from header blocks (first ~20% of first page)
        metadata = self._extract_metadata(sorted_blocks)
        warnings.extend(self._generate_metadata_warnings(metadata))

        # Segment and extract questions
        questions = self._extract_questions(sorted_blocks)
        warnings.extend(self._generate_question_warnings(questions))

        # Collect unmapped content
        unmapped = self._collect_unmapped(sorted_blocks, metadata, questions)

        return metadata, questions, unmapped, warnings

    def _extract_metadata(self, blocks: List[TextBlock]) -> ExtractedMetadata:
        """Extract document metadata from text blocks."""
        metadata = ExtractedMetadata()

        # Combine all text for pattern matching
        full_text = " ".join(b.text for b in blocks[:30])  # Use first ~30 blocks
        full_text_lower = full_text.lower()

        # Extract school year
        for pattern in self.PATTERNS["school_year"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                start_year = match.group(1)
                end_year = match.group(2)
                if len(end_year) == 2:
                    end_year = start_year[:2] + end_year
                value = f"{start_year}/{end_year}"
                confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                metadata.school_year = MetadataField(value, confidence)
                break

        # Extract term
        for pattern in self.PATTERNS["term"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                term_value = match.group(1)
                # Normalize term value
                term_map = {
                    "primeiro": "1", "first": "1", "i": "1",
                    "segundo": "2", "second": "2", "ii": "2",
                    "terceiro": "3", "third": "3", "iii": "3",
                    "quarto": "4", "fourth": "4", "iv": "4",
                }
                normalized = term_map.get(term_value.lower(), term_value)
                confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                metadata.term = MetadataField(f"{normalized}º Trimestre", confidence)
                break

        # Extract subject
        for pattern in self.PATTERNS["subject"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                subject = match.group(1).strip()
                confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                metadata.subject = MetadataField(subject, confidence)
                break

        # Extract duration
        for pattern in self.PATTERNS["duration"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                minutes = int(match.group(1))
                if match.lastindex >= 2 and match.group(2):
                    minutes = minutes * 60 + int(match.group(2))
                elif "hora" in match.group(0).lower() or "hour" in match.group(0).lower():
                    minutes = minutes * 60
                confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                metadata.duration_minutes = MetadataField(minutes, confidence)
                break

        # Extract variant
        for pattern in self.PATTERNS["variant"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                variant = match.group(1).upper()
                confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                metadata.variant = MetadataField(variant, confidence)
                break

        # Extract class info
        for pattern in self.PATTERNS["class"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                class_num = match.group(1)
                class_letter = match.group(2).upper() if match.lastindex >= 2 and match.group(2) else ""
                value = f"{class_num}ª Classe" + (f" - Turma {class_letter}" if class_letter else "")
                confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                metadata.class_info = MetadataField(value, confidence)
                break

        # Extract exam type
        for pattern in self.PATTERNS["exam_type"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                exam_type = match.group(1).strip().title()
                confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                metadata.exam_type = MetadataField(exam_type, confidence)
                break

        # Extract title (usually the first prominent text)
        if blocks:
            title_candidates = [b for b in blocks[:10] if len(b.text) > 10 and b.confidence > 0.8]
            if title_candidates:
                title_block = max(title_candidates, key=lambda b: b.confidence)
                metadata.title = MetadataField(title_block.text, title_block.confidence)

        # Extract instructions (look for instruction keywords)
        instruction_keywords = ["leia", "responda", "atenção", "instruções", "read", "answer", "attention", "instructions"]
        for block in blocks[:20]:
            if any(kw in block.text.lower() for kw in instruction_keywords):
                metadata.instructions = MetadataField(block.text, block.confidence)
                break

        return metadata

    def _extract_questions(self, blocks: List[TextBlock]) -> List[ExtractedQuestion]:
        """Extract and structure questions from text blocks."""
        questions = []
        current_question = None
        question_text_parts = []

        for block in blocks:
            # Check if this block starts a new question
            question_number = self._detect_question_number(block.text)

            if question_number is not None:
                # Save previous question if exists
                if current_question is not None:
                    current_question.text = self._clean_text(" ".join(question_text_parts))
                    questions.append(current_question)

                # Start new question
                current_question = ExtractedQuestion(
                    number=question_number,
                    text="",
                    text_confidence=block.confidence,
                    question_type=QuestionType.UNKNOWN,
                    question_type_confidence=0.0,
                    page_index=block.page_index,
                    start_y=block.bbox[1],
                    end_y=block.bbox[3],
                )
                question_text_parts = [self._remove_question_prefix(block.text)]

            elif current_question is not None:
                # Check if this is an option
                option = self._detect_option(block.text)
                if option:
                    current_question.options.append(ExtractedOption(
                        option_label=option[0],
                        option_text=option[1],
                        confidence=block.confidence,
                    ))
                else:
                    # Add to question text
                    question_text_parts.append(block.text)

                # Update end position
                current_question.end_y = max(current_question.end_y, block.bbox[3])

                # Extract score if present
                score = self._detect_score(block.text)
                if score is not None:
                    current_question.max_score = score
                    current_question.max_score_confidence = block.confidence

        # Save last question
        if current_question is not None:
            current_question.text = self._clean_text(" ".join(question_text_parts))
            questions.append(current_question)

        # Infer question types
        for question in questions:
            question.question_type, question.question_type_confidence = self._infer_question_type(question)

        return questions

    def _detect_question_number(self, text: str) -> Optional[int]:
        """Detect if text starts with a question number."""
        text = text.strip()

        for pattern in self.PATTERNS["question_number"]:
            match = re.match(pattern, text, re.IGNORECASE)
            if match:
                num_str = match.group(1)
                # Convert Roman numerals
                roman_map = {"I": 1, "II": 2, "III": 3, "IV": 4, "V": 5, "VI": 6, "VII": 7, "VIII": 8, "IX": 9, "X": 10}
                if num_str.upper() in roman_map:
                    return roman_map[num_str.upper()]
                return int(num_str)

        return None

    def _remove_question_prefix(self, text: str) -> str:
        """Remove question number prefix from text."""
        for pattern in self.PATTERNS["question_number"]:
            text = re.sub(pattern, "", text, flags=re.IGNORECASE).strip()
        return text

    def _detect_option(self, text: str) -> Optional[Tuple[str, str]]:
        """Detect if text is a question option."""
        text = text.strip()

        for pattern in self.PATTERNS["option"]:
            match = re.match(pattern, text, re.IGNORECASE)
            if match:
                label = match.group(1).upper()
                option_text = match.group(2).strip()
                return (label, option_text)

        return None

    def _detect_score(self, text: str) -> Optional[float]:
        """Detect score/points in text."""
        for pattern in self.PATTERNS["score"]:
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                score_str = match.group(1).replace(",", ".")
                return float(score_str)
        return None

    def _infer_question_type(self, question: ExtractedQuestion) -> Tuple[QuestionType, float]:
        """Infer question type based on text and options."""
        text_lower = question.text.lower()

        # If has options, likely multiple choice
        if question.options:
            return QuestionType.MULTIPLE_CHOICE, 0.95

        # Check for true/false patterns
        for pattern in self.PATTERNS["true_false"]:
            if re.search(pattern, text_lower, re.IGNORECASE):
                return QuestionType.TRUE_FALSE, 0.90

        # Check keywords
        max_confidence = 0.0
        detected_type = QuestionType.UNKNOWN

        for q_type, keywords in self.QUESTION_TYPE_KEYWORDS.items():
            matches = sum(1 for kw in keywords if kw in text_lower)
            if matches > 0:
                confidence = min(0.7 + (matches * 0.1), 0.95)
                if confidence > max_confidence:
                    max_confidence = confidence
                    detected_type = q_type

        if detected_type != QuestionType.UNKNOWN:
            return detected_type, max_confidence

        # Default to short answer if we can't determine
        return QuestionType.SHORT_ANSWER, 0.5

    def _estimate_confidence_from_match(
        self,
        match: re.Match,
        full_text: str,
        blocks: List[TextBlock],
    ) -> float:
        """Estimate confidence for a regex match based on surrounding text blocks."""
        # Find blocks that contain the match
        match_start = match.start()
        match_text = match.group(0)

        base_confidence = 0.85

        # Adjust based on block confidence
        for block in blocks:
            if match_text in block.text:
                base_confidence = (base_confidence + block.confidence) / 2
                break

        return base_confidence

    def _clean_text(self, text: str) -> str:
        """Clean and normalize extracted text."""
        # Remove extra whitespace
        text = re.sub(r"\s+", " ", text).strip()

        # Remove score annotations
        for pattern in self.PATTERNS["score"]:
            text = re.sub(pattern, "", text)

        return text.strip()

    def _collect_unmapped(
        self,
        blocks: List[TextBlock],
        metadata: ExtractedMetadata,
        questions: List[ExtractedQuestion],
    ) -> List[UnmappedContent]:
        """Collect text blocks that weren't mapped to metadata or questions."""
        # For simplicity, return empty list - full implementation would track used blocks
        return []

    def _generate_metadata_warnings(self, metadata: ExtractedMetadata) -> List[Warning]:
        """Generate warnings for low-confidence metadata fields."""
        warnings = []

        fields = [
            ("schoolYear", metadata.school_year),
            ("term", metadata.term),
            ("subject", metadata.subject),
            ("class", metadata.class_info),
            ("examType", metadata.exam_type),
        ]

        for field_name, field_value in fields:
            if field_value.value is not None and field_value.confidence < self.low_confidence_threshold:
                warnings.append(Warning(
                    code="LOW_CONFIDENCE",
                    field=field_name,
                    confidence=field_value.confidence,
                ))

        return warnings

    def _generate_question_warnings(self, questions: List[ExtractedQuestion]) -> List[Warning]:
        """Generate warnings for low-confidence questions."""
        warnings = []

        for question in questions:
            if question.confidence < self.low_confidence_threshold:
                warnings.append(Warning(
                    code="LOW_CONFIDENCE",
                    field=f"question_{question.number}",
                    confidence=question.confidence,
                ))

            if question.question_type == QuestionType.UNKNOWN:
                warnings.append(Warning(
                    code="UNKNOWN_QUESTION_TYPE",
                    field=f"question_{question.number}",
                    confidence=question.question_type_confidence,
                ))

        return warnings


def normalize_text(text: str) -> str:
    """
    Normalize text for consistent processing.

    - Removes extra whitespace
    - Normalizes quotes and dashes
    - Fixes common OCR errors
    """
    # Normalize whitespace
    text = re.sub(r"\s+", " ", text).strip()

    # Normalize quotes
    text = re.sub(r"[""„‟]", '"', text)
    text = re.sub(r"[''‚‛]", "'", text)

    # Normalize dashes
    text = re.sub(r"[–—−]", "-", text)

    # Common OCR fixes
    ocr_fixes = {
        r"\bl\b": "I",  # lowercase L to uppercase I
        r"0(?=[A-Za-z])": "O",  # zero before letter to O
        r"(?<=[A-Za-z])0": "O",  # zero after letter to O
    }

    for pattern, replacement in ocr_fixes.items():
        text = re.sub(pattern, replacement, text)

    return text


def detect_language(text: str) -> str:
    """
    Detect the primary language of the text.

    Returns ISO 639-1 language code (pt, en, etc.)
    """
    # Simple keyword-based detection
    pt_keywords = ["de", "da", "do", "em", "para", "com", "uma", "não", "que", "se", "os", "as"]
    en_keywords = ["the", "and", "is", "are", "for", "with", "you", "that", "this", "have"]

    text_lower = text.lower()
    words = text_lower.split()

    pt_count = sum(1 for w in words if w in pt_keywords)
    en_count = sum(1 for w in words if w in en_keywords)

    if pt_count > en_count:
        return "pt"
    elif en_count > pt_count:
        return "en"
    else:
        return "pt"  # Default to Portuguese


# Default postprocessor instance
default_postprocessor = OCRPostprocessor()

"""
OCR Postprocessing Module

Provides utilities for processing and structuring raw OCR output:
- Text normalization and cleaning
- Question detection and segmentation
- Metadata extraction (school year, term, subject, etc.)
- Question type inference (dissertativa, multipla_escolha)
- Option extraction for multiple choice questions
- Image detection and region identification
- Confidence score aggregation

Optimized for Angolan exam papers (12ª classe).
"""

import re
from dataclasses import dataclass, field
from typing import Optional, List, Dict, Any, Tuple
from enum import Enum


class QuestionType(str, Enum):
    """Supported question types."""
    DISSERTATIVA = "dissertativa"
    MULTIPLA_ESCOLHA = "multipla_escolha"
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
class ImageToUpload:
    """Represents an image region to be uploaded."""
    suggested_filename: str
    description: str
    region: str  # questao_1, cabecalho, rodape, etc.
    bbox: Optional[Tuple[int, int, int, int]] = None
    page_index: int = 0
    source_width: Optional[int] = None
    source_height: Optional[int] = None
    contract_version: int = 1
    source_file_index: int = 0


@dataclass
class SubitemContent:
    """Represents a subitem (alínea) with its label and content."""
    label: str  # "a)", "b)", etc.
    text: str   # The content of the subitem
    cotacao: Optional[float] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "label": self.label,
            "text": self.text,
            "cotacao": self.cotacao,
        }


@dataclass
class ExtractedQuestion:
    """Represents an extracted question with all its components."""
    number: str  # Can be "1", "2a", "3-b)", etc.
    text: str
    text_confidence: float
    question_type: QuestionType
    question_type_confidence: float
    subitems: List[str] = field(default_factory=list)  # ["a)", "b)", "c)"] - labels only
    subitems_content: List[SubitemContent] = field(default_factory=list)  # Full subitem with content
    cotacao: Optional[float] = None
    cotacao_confidence: float = 0.0
    options: Optional[List[ExtractedOption]] = None
    has_image: bool = False
    image_description: Optional[str] = None
    page_index: int = 0
    start_y: int = 0
    end_y: int = 0

    @property
    def confidence(self) -> float:
        """Calculate overall question confidence."""
        confidences = [self.text_confidence, self.question_type_confidence]
        if self.options:
            confidences.extend(opt.confidence for opt in self.options)
        if self.cotacao is not None:
            confidences.append(self.cotacao_confidence)
        return sum(confidences) / len(confidences) if confidences else 0.0

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        result = {
            "number": self.number,
            "confidence": round(self.confidence, 3),
            "subitems": self.subitems,
            "subitemsContent": [s.to_dict() for s in self.subitems_content] if self.subitems_content else [],
            "text": {"value": self.text, "confidence": round(self.text_confidence, 3)},
            "type": self.question_type.value,
            "cotacao": self.cotacao,
            "hasImage": self.has_image,
            "pageIndex": self.page_index,
            "startY": self.start_y,
            "endY": self.end_y,
        }

        if self.image_description:
            result["imageDescription"] = self.image_description

        if self.options:
            result["options"] = [
                {
                    "optionLabel": opt.option_label,
                    "optionText": opt.option_text,
                    "confidence": round(opt.confidence, 3),
                }
                for opt in self.options
            ]
        else:
            result["options"] = None

        return result


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
    """Represents extracted document metadata - Angolan exam format."""
    exam_type: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    duration_minutes: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    variant: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    title: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    instructions: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    school_year_start: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    school_year_end: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    class_grade: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    course_name: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    subject_name: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    total_max_score: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    # Legacy fields for compatibility
    school_year: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    term: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    subject: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    course: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))
    class_info: MetadataField = field(default_factory=lambda: MetadataField(None, 0.0))

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        return {
            # New structured fields
            "examType": self.exam_type.to_dict(),
            "durationMinutes": self.duration_minutes.to_dict(),
            "variant": self.variant.to_dict(),
            "title": self.title.to_dict(),
            "instructions": self.instructions.to_dict(),
            "schoolYearStart": self.school_year_start.to_dict(),
            "schoolYearEnd": self.school_year_end.to_dict(),
            "classGrade": self.class_grade.to_dict(),
            "courseName": self.course_name.to_dict(),
            "subjectName": self.subject_name.to_dict(),
            "totalMaxScore": self.total_max_score.to_dict(),
            # Legacy fields
            "schoolYear": self.school_year.to_dict(),
            "term": self.term.to_dict(),
            "subject": self.subject.to_dict(),
            "course": self.course.to_dict(),
            "class": self.class_info.to_dict(),
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
    Postprocessor for OCR results - Optimized for Angolan exams.

    Transforms raw OCR text blocks into structured data including:
    - Document metadata (school year, subject, exam type, etc.)
    - Questions with their types and options
    - Image regions for upload
    - Confidence scores for all extracted values
    """

    # Regex patterns for metadata extraction
    PATTERNS = {
        # School year patterns (e.g., "2024/2025", "Ano Letivo 2024-2025", "Ano Lectivo: 2024/2025")
        "school_year": [
            # Explicit "Ano Letivo" patterns
            r"ano\s*let[ií]vo\s*[:\s]*(\d{4})\s*[/-]\s*(\d{2,4})",
            r"ano\s*lect[ií]vo\s*[:\s]*(\d{4})\s*[/-]\s*(\d{2,4})",
            r"ano\s*lectivo\s*[:\s]*(\d{4})\s*[/-]\s*(\d{2,4})",
            # Inline patterns like "12ª Classe Ano Lectivo: 2024/2025"
            r"classe\s*ano\s*lect[ií]vo\s*[:\s]*(\d{4})\s*[/-]\s*(\d{2,4})",
            # Standalone year patterns (less specific, use last)
            r"(\d{4})\s*[/-]\s*(\d{4})",
            r"(\d{4})\s*/\s*(\d{2,4})",
            # Pattern with colon: "2024/2025"
            r"[:\s](\d{4})\s*/\s*(\d{2,4})",
        ],
        # Term patterns (e.g., "1º Trimestre")
        "term": [
            r"(\d)[ºª°]?\s*(?:trimestre|term|período|bimestre)",
            r"(?:trimestre|term|período|bimestre)\s*(\d)",
            r"(I{1,3}|IV)\s*(?:trimestre|term|período)",
        ],
        # Subject patterns - Angolan format (improved for "PROVA DE EXAME DE MATEMÁTICA")
        "subject": [
            # Most specific: "PROVA DE EXAME DE MATEMÁTICA"
            r"prova\s+de\s+exame\s+de\s+([A-Za-záàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ]+)",
            # "PROVA DE RECURSO DE MATEMÁTICA"
            r"prova\s+de\s+recurso\s+de\s+([A-Za-záàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ]+)",
            # "EXAME DE MATEMÁTICA"
            r"exame\s+de\s+([A-Za-záàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ]+)",
            # Generic patterns
            r"(?:recurso\s+de\s+)([A-Za-záàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ]+)",
            r"(?:disciplina|subject|matéria|cadeira)[:\s]+([A-Za-záàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ\s\-]+)",
            # "PROVA DE MATEMÁTICA"; never treat the exam type as a subject.
            r"prova\s+de\s+(?!exame\b|recurso\b)([A-Za-záàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ]+)",
        ],
        # Duration patterns (e.g., "90 Min", "Duração: 90 minutos")
        "duration": [
            r"(?:duração|duration|tempo)[:\s]*(\d+)\s*(?:min(?:utos?)?|minutes?)",
            r"(\d+)\s*(?:min(?:utos?)?)\b",
        ],
        # Variant/Series patterns (e.g., "Série: B", "Variante A", "Serie: B")
        "variant": [
            r"s[ée]rie\s*[:\s]*([A-Za-z])\b",
            r"(?:versão|variant|variante)\s*[:\s]*([A-Za-z])\b",
            r"(?:série|serie)\s*([A-Za-z])\b",
        ],
        # Class/Grade patterns (e.g., "12ª Classe", "10º Ano", "12a Classe")
        # More flexible patterns to catch various OCR outputs
        "class": [
            r"(\d{1,2})\s*[ºª°aᵃ]?\s*classe",
            r"(\d{1,2})\s*[ºª°oᵒ]?\s*ano",
            r"(\d{1,2})\s*classe",
            r"classe\s*[:\s]*(\d{1,2})",
            r"(\d{1,2})\s*[ºª]\s*cl",
            # Pattern for inline text like "12a Classe Ano Lectivo"
            r"(\d{1,2})[aª]\s*classe",
        ],
        # Course patterns (e.g., "CURSO: TODOS", "Curso: Ciências")
        # More restrictive to avoid capturing garbage - must have CURSO: prefix
        "course": [
            r"curso\s*:\s*([A-Z]+)(?:\s|$|[^A-Za-z])",
            r"curso\s*:\s+([A-Za-z]+)(?:\s|$)",
        ],
        # Footer/garbage patterns to filter out
        "footer_garbage": [
            r"COORDENA[CÇ][AÃ]O",
            r"minttics\.gov\.ao",
            r"LUANDA[\s-]*ANGOLA",
            r"ANGOLA",
            r"gov\.ao",
            r"\d+/E\d+/\d+",
            r"kaixa",
            r"klvs",
            r"GOIK",
        ],
        # Inline cotação patterns (e.g., "(3V)", "(2,5V)", "(4 valores)")
        "inline_cotacao": [
            r"\((\d+(?:[.,]\d+)?)\s*[Vv]\)",
            r"\((\d+(?:[.,]\d+)?)\s*(?:valores?|pontos?|pts?)\)",
            r"\[(\d+(?:[.,]\d+)?)\s*[Vv]\]",
        ],
        # Exam type patterns (improved for recurso, época, etc.)
        "exam_type": [
            # Most specific patterns first
            r"(prova\s+de\s+exame\s+de\s+\w+)",
            r"(prova\s+de\s+recurso\s+de\s+\w+)",
            r"(prova\s+de\s+recurso)",
            r"(prova\s+de\s+exame)",
            r"(exame\s+de\s+recurso)",
            r"(exame\s+de\s+época)",
            r"(avaliação\s*(?:periódica|sumativa|formativa|diagnóstica|final|contínua))",
            r"(prova\s*(?:escrita|oral|prática|final|parcial|de\s+recurso))",
            r"(exame\s*(?:final|nacional|regional|provincial|de\s+época)?)",
            r"(teste\s*(?:escrito|sumativo|formativo)?)",
            r"(recurso)",
        ],
        # Question number patterns - More flexible for Angolan format
        "question_number": [
            r"^(\d+)\s*[-.):]",
            r"^(?:questão|pergunta|question|exercício|problema|item)\s*n?[ºª°]?\s*(\d+)",
        ],
        # Subitem patterns (e.g., "a)", "b.", "(a)")
        "subitem": [
            r"^\(?([a-z])\)?[.):]\s*",
        ],
        # Option patterns for multiple choice
        "option": [
            r"^\(?([A-Da-d])\)?[.):]\s*(.+)",
            r"^([A-Da-d])\s*[-–—]\s*(.+)",
        ],
        # Score/Cotação patterns - Angolan format
        "cotacao": [
            r"(\d+(?:[.,]\d+)?)\s*(?:valores?|pontos?|pts?|marks?|points?)",
            r"[(\[]\s*(\d+(?:[.,]\d+)?)\s*(?:valores?|pontos?|pts?|marks?|points?)?\s*[)\]]",
        ],
        # Cotação block pattern (e.g., "Cotação 1-a) 3 valores 2-) 4 valores")
        # Updated to be more flexible and capture multi-line cotação blocks
        "cotacao_block": [
            r"(?:cotação|cotacao|pontuação|pontuacao)[:\s]*(.+?)(?:\.|$|(?=\n\n))",
            r"(?:cotação|cotacao|pontuação|pontuacao)[:\s]*(.+)",
        ],
        # Individual cotação item patterns for parsing (more comprehensive)
        "cotacao_item": [
            # Pattern: "1-a) 3 valores" or "1-a) 3,5 valores" or "1a) 3 valores"
            r"(\d+)\s*[-\s]?\s*([a-z])\s*\)?\s*(\d+(?:[.,]\d+)?)\s*(?:valores?|pontos?|pts?)",
            # Pattern: "1-) 3 valores" or "1) 3 valores" (no subitem)
            r"(\d+)\s*[-\s]?\s*\)?\s*(\d+(?:[.,]\d+)?)\s*(?:valores?|pontos?|pts?)",
            # Pattern: "2-) 4 valores" with explicit dash
            r"(\d+)\s*-\s*\)\s*(\d+(?:[.,]\d+)?)\s*(?:valores?|pontos?|pts?)",
            # Pattern: "5-a) 2,5 valores 5-b) 2,5 valores" - captures with subitem
            r"(\d+)\s*-\s*([a-z])\s*\)\s*(\d+(?:[.,]\d+)?)\s*(?:valores?|pontos?|pts?)",
        ],
        # Image indicators
        "image_indicator": [
            r"(?:figura|gráfico|tabela|diagrama|imagem|graph|table|figure|image)",
            r"(?:veja|observe|analise|considere)\s+(?:a|o)\s+(?:figura|gráfico|tabela)",
            r"(?:na\s+)?(?:figura|gráfico|tabela)\s+(?:abaixo|seguinte|acima)",
        ],
        # Coordination signature patterns
        "coordination": [
            r"(?:a\s+)?coordena[çc][ãa]o",
            r"coordenador(?:a)?",
            r"assinatura",
            r"(?:fim\s+da?\s+prova)",
        ],
    }

    # Keywords for question type inference
    QUESTION_TYPE_KEYWORDS = {
        QuestionType.MULTIPLA_ESCOLHA: [
            "escolha", "assinale", "marque", "alternativa", "opção",
            "select", "choose", "mark", "circle", "option",
        ],
        QuestionType.DISSERTATIVA: [
            "resolva", "resolve", "calcule", "calcular", "determine", "determina",
            "justifique", "explique", "desenvolva", "comente", "discuta",
            "demonstre", "prove", "mostre", "encontre", "simplifique",
            "analise", "compare", "descreva", "fundamente",
        ],
    }

    # Subject name corrections for OCR errors
    SUBJECT_CORRECTIONS = {
        "matematica": "Matemática",
        "matemática": "Matemática",
        "fisica": "Física",
        "física": "Física",
        "fisíca": "Física",
        "fìsica": "Física",
        "quimica": "Química",
        "química": "Química",
        "quìmica": "Química",
        "biologia": "Biologia",
        "portugues": "Português",
        "português": "Português",
        "ingles": "Inglês",
        "inglês": "Inglês",
        "frances": "Francês",
        "francês": "Francês",
        "historia": "História",
        "história": "História",
        "geografia": "Geografia",
        "filosofia": "Filosofia",
        "educacao": "Educação",
        "educação": "Educação",
        "desenho": "Desenho",
        "geometria": "Geometria",
        "informatica": "Informática",
        "informática": "Informática",
        "economia": "Economia",
        "sociologia": "Sociologia",
        "psicologia": "Psicologia",
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
        page_dimensions: Optional[Dict[int, Tuple[int, int]]] = None,
        source_file_indices: Optional[Dict[int, int]] = None,
    ) -> Tuple[ExtractedMetadata, List[ExtractedQuestion], List[ImageToUpload], List[UnmappedContent], List[Warning]]:
        """
        Process OCR text blocks into structured data.

        Args:
            text_blocks: List of text blocks from OCR
            page_count: Number of pages in the document

        Returns:
            Tuple of (metadata, questions, images_to_upload, unmapped_content, warnings)
        """
        warnings = []
        unmapped = []
        images_to_upload = []

        # Sort blocks by page and position
        sorted_blocks = sorted(text_blocks, key=lambda b: (b.page_index, b.bbox[1], b.bbox[0]))

        # Combine all text for pattern matching
        full_text = " ".join(b.text for b in sorted_blocks)

        # Extract metadata from header blocks
        metadata = self._extract_metadata(sorted_blocks, full_text)

        # Clean up null/empty fields to avoid persisting empty data
        metadata = self._clean_null_fields(metadata)

        warnings.extend(self._generate_metadata_warnings(metadata))

        # Parse cotação block if present
        cotacao_map = self._parse_cotacao_block(full_text)

        # Segment and extract questions
        questions = self._extract_questions(sorted_blocks, cotacao_map)
        warnings.extend(self._generate_question_warnings(questions))

        # Calculate total max score from questions or cotação map
        total_score = sum(q.cotacao for q in questions if q.cotacao is not None)
        if total_score > 0:
            metadata.total_max_score = MetadataField(round(total_score, 1), 0.9)
        elif cotacao_map:
            # If questions don't have cotação yet, sum from cotação map
            total_from_map = sum(cotacao_map.values())
            if total_from_map > 0:
                metadata.total_max_score = MetadataField(round(total_from_map, 1), 0.85)

        # Detect images to upload
        images_to_upload = self._detect_images_to_upload(
            metadata,
            questions,
            sorted_blocks,
            full_text,
            page_dimensions or {},
            source_file_indices or {},
        )

        # Collect unmapped content
        unmapped = self._collect_unmapped(sorted_blocks, metadata, questions)

        return metadata, questions, images_to_upload, unmapped, warnings

    def _extract_metadata(self, blocks: List[TextBlock], full_text: str) -> ExtractedMetadata:
        """Extract document metadata from text blocks."""
        metadata = ExtractedMetadata()
        full_text_lower = full_text.lower()

        # Extract school year - try multiple approaches
        school_year_found = False
        for pattern in self.PATTERNS["school_year"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                start_year = int(match.group(1))
                end_year_str = match.group(2)
                if len(end_year_str) == 2:
                    end_year = int(str(start_year)[:2] + end_year_str)
                else:
                    end_year = int(end_year_str)

                # Validate years are reasonable (2000-2100)
                if 2000 <= start_year <= 2100 and 2000 <= end_year <= 2100:
                    confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                    metadata.school_year_start = MetadataField(start_year, confidence)
                    metadata.school_year_end = MetadataField(end_year, confidence)
                    metadata.school_year = MetadataField(f"{start_year}/{end_year}", confidence)
                    school_year_found = True
                    break

        # Fallback: look in header blocks specifically
        if not school_year_found:
            header_text = " ".join(b.text for b in blocks[:20])
            year_patterns = [
                r"(\d{4})\s*[/-]\s*(\d{4})",
                r"(\d{4})\s*/\s*(\d{2,4})",
            ]
            for pattern in year_patterns:
                match = re.search(pattern, header_text)
                if match:
                    start_year = int(match.group(1))
                    end_year_str = match.group(2)
                    if len(end_year_str) == 2:
                        end_year = int(str(start_year)[:2] + end_year_str)
                    else:
                        end_year = int(end_year_str)

                    if 2000 <= start_year <= 2100 and 2000 <= end_year <= 2100:
                        metadata.school_year_start = MetadataField(start_year, 0.8)
                        metadata.school_year_end = MetadataField(end_year, 0.8)
                        metadata.school_year = MetadataField(f"{start_year}/{end_year}", 0.8)
                        break

        # Extract term
        for pattern in self.PATTERNS["term"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                term_value = match.group(1)
                term_map = {
                    "i": "1", "ii": "2", "iii": "3", "iv": "4",
                }
                normalized = term_map.get(term_value.lower(), term_value)
                confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                metadata.term = MetadataField(f"{normalized}º Trimestre", confidence)
                break

        # Extract subject (try multiple approaches)
        subject_found = False
        for pattern in self.PATTERNS["subject"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                subject = match.group(1).strip()
                # Skip if it's just "Recurso" - we want the actual subject
                if subject.lower() == "recurso":
                    continue
                # Clean and normalize subject name
                subject = self._normalize_subject_name(subject)
                confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                metadata.subject_name = MetadataField(subject, confidence)
                metadata.subject = MetadataField(subject, confidence)
                subject_found = True
                break

        # If not found, try to find subject keywords in text
        if not subject_found:
            for subject_key, subject_value in self.SUBJECT_CORRECTIONS.items():
                if subject_key in full_text_lower:
                    metadata.subject_name = MetadataField(subject_value, 0.75)
                    metadata.subject = MetadataField(subject_value, 0.75)
                    break

        # Extract duration
        for pattern in self.PATTERNS["duration"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                minutes = int(match.group(1))
                confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                metadata.duration_minutes = MetadataField(minutes, confidence)
                break

        # Extract variant/series
        for pattern in self.PATTERNS["variant"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                variant = match.group(1).upper()
                confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                metadata.variant = MetadataField(variant, confidence)
                break

        # Extract class/grade - try multiple approaches
        grade_found = False

        # First, try patterns on full text
        for pattern in self.PATTERNS["class"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                grade = match.group(1)
                # Validate grade is reasonable (1-13 for Angola)
                try:
                    grade_int = int(grade)
                    if 1 <= grade_int <= 13:
                        confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                        metadata.class_grade = MetadataField(str(grade_int), confidence)
                        metadata.class_info = MetadataField(f"{grade_int}ª Classe", confidence)
                        grade_found = True
                        break
                except ValueError:
                    continue

        # Fallback 1: look for common class patterns in first blocks (header area)
        if not grade_found:
            header_text = " ".join(b.text for b in blocks[:20])
            # Multiple patterns for class extraction
            class_patterns = [
                r"(\d{1,2})\s*[ºª°aᵃ]?\s*classe",
                r"(\d{1,2})\s*[ºª°aᵃ]\s*cl\b",
                r"(\d{1,2})a\s+classe",
                r"(\d{1,2})ª\s+classe",
                # Pattern like "12a Classe Ano Lectivo"
                r"(\d{1,2})[aª]\s*classe\s*ano",
            ]
            for pattern in class_patterns:
                class_match = re.search(pattern, header_text, re.IGNORECASE)
                if class_match:
                    try:
                        grade_int = int(class_match.group(1))
                        if 1 <= grade_int <= 13:
                            metadata.class_grade = MetadataField(str(grade_int), 0.8)
                            metadata.class_info = MetadataField(f"{grade_int}ª Classe", 0.8)
                            grade_found = True
                            break
                    except ValueError:
                        continue

        # Fallback 2: look for class in instructions text (common in Angolan exams)
        if not grade_found and metadata.instructions.value:
            instr_text = metadata.instructions.value
            for pattern in class_patterns:
                class_match = re.search(pattern, instr_text, re.IGNORECASE)
                if class_match:
                    try:
                        grade_int = int(class_match.group(1))
                        if 1 <= grade_int <= 13:
                            metadata.class_grade = MetadataField(str(grade_int), 0.75)
                            metadata.class_info = MetadataField(f"{grade_int}ª Classe", 0.75)
                            grade_found = True
                            break
                    except ValueError:
                        continue

        # Fallback 3: search for standalone pattern like "12 Classe" anywhere
        if not grade_found:
            standalone_pattern = r"\b(\d{1,2})\s*classe\b"
            standalone_match = re.search(standalone_pattern, full_text, re.IGNORECASE)
            if standalone_match:
                try:
                    grade_int = int(standalone_match.group(1))
                    if 1 <= grade_int <= 13:
                        metadata.class_grade = MetadataField(str(grade_int), 0.7)
                        metadata.class_info = MetadataField(f"{grade_int}ª Classe", 0.7)
                except ValueError:
                    pass

        # Extract course - be more careful to get clean value
        # First try explicit "CURSO:" pattern
        course_match = re.search(r"curso\s*:\s*([A-Za-záàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ\s]+?)(?:\s{2,}|$|\n|SEGIEM|N[°º])", full_text, re.IGNORECASE)
        if course_match:
            course = course_match.group(1).strip().upper()
            # Clean course name - remove trailing garbage
            course = re.sub(r'\s+', ' ', course)
            # Only take first meaningful word(s)
            course_words = course.split()
            if len(course_words) > 0:
                # Common course names
                valid_courses = ['TODOS', 'CIENCIAS', 'HUMANIDADES', 'LETRAS', 'ARTES',
                                'ECONOMIA', 'INFORMATICA', 'PEDAGOGIA', 'AGRONOMIA', 'GERAL']
                # Check if first word is a valid course
                if course_words[0] in valid_courses:
                    course = course_words[0]
                # Check if "TODOS" appears anywhere
                elif 'TODOS' in course_words:
                    course = 'TODOS'
                elif len(course_words) <= 2:
                    course = ' '.join(course_words)
                else:
                    # Take only first meaningful word, skip garbage
                    for word in course_words:
                        if word in valid_courses or len(word) > 3:
                            course = word
                            break
                confidence = 0.85
                metadata.course_name = MetadataField(course, confidence)
                metadata.course = MetadataField(course, confidence)

        # Extract exam type
        for pattern in self.PATTERNS["exam_type"]:
            match = re.search(pattern, full_text, re.IGNORECASE)
            if match:
                exam_type = match.group(1).strip().title()
                # Normalize exam types - more specific normalization
                exam_type_lower = exam_type.lower()
                if "prova de exame de" in exam_type_lower:
                    # Extract subject from "prova de exame de matemática"
                    exam_type = "Prova de Exame"
                elif "prova de recurso" in exam_type_lower:
                    exam_type = "Prova de Recurso"
                elif "exame de recurso" in exam_type_lower:
                    exam_type = "Exame de Recurso"
                elif "exame de época" in exam_type_lower or "época" in exam_type_lower:
                    exam_type = "Exame de Época"
                elif "prova de exame" in exam_type_lower or "exame" in exam_type_lower:
                    exam_type = "Prova de Exame"
                elif "avaliação" in exam_type_lower:
                    exam_type = exam_type  # Keep as is (Avaliação Periódica, etc.)
                elif "teste" in exam_type_lower:
                    exam_type = exam_type  # Keep as is
                confidence = self._estimate_confidence_from_match(match, full_text, blocks)
                metadata.exam_type = MetadataField(exam_type, confidence)
                break

        # Build title from extracted components
        title = self._build_title(metadata)
        if title:
            metadata.title = MetadataField(title, 0.85)

        # Extract instructions (look for instruction keywords)
        instruction_keywords = ["leia", "responda", "atenção", "instruções", "coloque", "forma clara"]
        for block in blocks[:25]:
            if any(kw in block.text.lower() for kw in instruction_keywords):
                # Check if this looks like an instruction block
                if len(block.text) > 30 and not self._detect_question_number(block.text):
                    metadata.instructions = MetadataField(block.text.strip(), block.confidence)
                    break

        return metadata

    def _normalize_subject_name(self, subject: str) -> str:
        """Normalize and correct subject name."""
        if not subject:
            return subject

        subject_lower = subject.lower().strip()

        # Remove common OCR noise
        subject_lower = re.sub(r'\s+', ' ', subject_lower)
        subject_lower = subject_lower.replace('í', 'i').replace('ì', 'i')

        # Check for exact matches in corrections
        if subject_lower in self.SUBJECT_CORRECTIONS:
            return self.SUBJECT_CORRECTIONS[subject_lower]

        # Check for partial matches (more strict - at word boundaries)
        for key, value in self.SUBJECT_CORRECTIONS.items():
            if re.search(rf'\b{re.escape(key)}\b', subject_lower):
                return value

        # Return with proper capitalization
        return subject.strip().title()

    def _build_title(self, metadata: ExtractedMetadata) -> Optional[str]:
        """Build a title from extracted metadata components."""
        parts = []

        if metadata.exam_type.value:
            parts.append(metadata.exam_type.value)

        if metadata.subject_name.value:
            if not parts or "de" not in parts[-1].lower():
                parts.append(f"de {metadata.subject_name.value}")
            else:
                parts.append(metadata.subject_name.value)

        if metadata.class_grade.value:
            parts.append(f"{metadata.class_grade.value}ª Classe")

        if metadata.variant.value:
            parts.append(f"- Série {metadata.variant.value}")

        if metadata.school_year_start.value and metadata.school_year_end.value:
            parts.append(f"- {metadata.school_year_start.value}/{metadata.school_year_end.value}")

        return " ".join(parts) if parts else None

    def _parse_cotacao_block(self, full_text: str) -> Dict[str, float]:
        """
        Parse cotação/scoring block to map question numbers to scores.

        Handles Angolan exam format like:
        "Cotação 1-a) 3 valores 2-) 4 valores 3-a) 2,5 valores 3-b) 2,5 valores 4-) 3 valores 5-a) 2,5 valores 5-b) 2,5 valores"

        Also handles inline format like:
        "COTACAO:1) a - 4 V,b - 4 V/2) 5 V/ 3) 4 V/4) 3 V"
        """
        cotacao_map = {}

        # First, try to find cotação block
        cotacao_text = None
        for pattern in self.PATTERNS["cotacao_block"]:
            match = re.search(pattern, full_text, re.IGNORECASE | re.DOTALL)
            if match:
                cotacao_text = match.group(1)
                break

        if not cotacao_text:
            # Try to find cotação anywhere in text
            cotacao_match = re.search(r"cotaç[aã]o\s+(.+?)(?:\.|A\s+COORDENA|$)", full_text, re.IGNORECASE | re.DOTALL)
            if cotacao_match:
                cotacao_text = cotacao_match.group(1)

        # Also try inline format: "COTACAO:1) a - 4 V,b - 4 V/2) 5 V..."
        if not cotacao_text:
            inline_match = re.search(r"COTACAO\s*:\s*(.+?)(?:\s*$|\n\n)", full_text, re.IGNORECASE)
            if inline_match:
                cotacao_text = inline_match.group(1)

        if not cotacao_text:
            return cotacao_map

        # Clean up the cotação text
        cotacao_text = cotacao_text.replace('\n', ' ').replace('\r', ' ')
        cotacao_text = re.sub(r'\s+', ' ', cotacao_text)

        # Parse using multiple patterns for flexibility
        # Pattern 1: "1-a) 3 valores" - question with subitem
        pattern1 = r"(\d+)\s*-?\s*([a-z])\s*\)?\s*(\d+(?:[.,]\d+)?)\s*(?:valores?|pontos?|pts?)"
        for match in re.finditer(pattern1, cotacao_text, re.IGNORECASE):
            q_num = match.group(1)
            subitem = match.group(2).lower()
            score = float(match.group(3).replace(",", "."))
            key = f"{q_num}{subitem}"
            cotacao_map[key] = score

        # Pattern 2: "2-) 4 valores" - question without subitem (just dash and parenthesis)
        pattern2 = r"(\d+)\s*-\s*\)\s*(\d+(?:[.,]\d+)?)\s*(?:valores?|pontos?|pts?)"
        for match in re.finditer(pattern2, cotacao_text, re.IGNORECASE):
            q_num = match.group(1)
            score = float(match.group(2).replace(",", "."))
            # Only add if not already mapped with subitem
            if q_num not in cotacao_map:
                cotacao_map[q_num] = score

        # Pattern 3: "4-) 3 valores" or "4) 3 valores" - standalone questions
        pattern3 = r"(?<![a-z])(\d+)\s*[-\s]?\s*\)\s*(\d+(?:[.,]\d+)?)\s*(?:valores?|pontos?|pts?)"
        for match in re.finditer(pattern3, cotacao_text, re.IGNORECASE):
            q_num = match.group(1)
            score = float(match.group(2).replace(",", "."))
            # Only add if not already present
            if q_num not in cotacao_map and not any(k.startswith(q_num) for k in cotacao_map):
                cotacao_map[q_num] = score

        # Also extract inline cotação from question text (e.g., at end of question "(3 valores)")
        inline_pattern = r"[(\[]\s*(\d+(?:[.,]\d+)?)\s*(?:valores?|pontos?|pts?)\s*[)\]]"
        # This will be handled in question extraction

        # Parse alternate inline format: "1) a - 4 V,b - 4 V/2) 5 V/ 3) 4 V/4) 3 V"
        # Pattern: number) [optional letter -] score V
        if cotacao_text:
            # Format: "1) a - 4 V" or "1) 4 V" or "2) 5 V"
            alt_pattern1 = r"(\d+)\)\s*([a-z])\s*[-–]\s*(\d+(?:[.,]\d+)?)\s*V"
            for match in re.finditer(alt_pattern1, cotacao_text, re.IGNORECASE):
                q_num = match.group(1)
                subitem = match.group(2).lower()
                score = float(match.group(3).replace(",", "."))
                key = f"{q_num}{subitem}"
                if key not in cotacao_map:
                    cotacao_map[key] = score

            # Format: "2) 5 V" or "3) 4 V" - question without subitem using V abbreviation
            alt_pattern2 = r"(\d+)\)\s*(\d+(?:[.,]\d+)?)\s*V(?:\b|/|,)"
            for match in re.finditer(alt_pattern2, cotacao_text, re.IGNORECASE):
                q_num = match.group(1)
                score = float(match.group(2).replace(",", "."))
                if q_num not in cotacao_map and not any(k.startswith(q_num) for k in cotacao_map):
                    cotacao_map[q_num] = score

        return cotacao_map

    def _extract_questions(
        self,
        blocks: List[TextBlock],
        cotacao_map: Dict[str, float]
    ) -> List[ExtractedQuestion]:
        """Extract and structure questions from text blocks."""
        questions = []
        current_question = None
        question_text_parts = []
        current_subitems = []
        current_subitems_content = []
        current_subitem_label = None
        current_subitem_text_parts = []

        for block in blocks:
            text = block.text.strip()

            # Skip empty blocks
            if not text:
                continue

            # Skip cotação blocks
            if re.match(r"^\s*cotaç[aã]o\s", text, re.IGNORECASE):
                continue

            # Skip coordination/signature blocks
            if self._is_coordination_block(text):
                continue

            # Check if this block starts a new question
            question_info = self._detect_question_number(text)

            if question_info is not None:
                # Save current subitem if exists
                if current_subitem_label and current_subitem_text_parts:
                    raw_subitem_text = " ".join(current_subitem_text_parts)
                    subitem_cotacao = self._extract_inline_cotacao(raw_subitem_text)
                    subitem_text = self._clean_text(raw_subitem_text)
                    if subitem_cotacao:
                        subitem_text = self._remove_inline_cotacao(subitem_text)
                    current_subitems_content.append(SubitemContent(
                        label=current_subitem_label,
                        text=self._clean_footer_garbage(subitem_text),
                        cotacao=subitem_cotacao,
                    ))

                # Save previous question if exists
                if current_question is not None:
                    raw_question_text = " ".join(question_text_parts)
                    inline_cotacao = self._extract_inline_cotacao(raw_question_text)
                    current_question.text = self._clean_text(raw_question_text)
                    if inline_cotacao is not None and current_question.cotacao is None:
                        current_question.cotacao = inline_cotacao
                        current_question.cotacao_confidence = 0.8
                    current_question.subitems = current_subitems
                    current_question.subitems_content = current_subitems_content
                    self._finalize_question(current_question, cotacao_map)
                    questions.append(current_question)

                q_num, remainder = question_info

                # Start new question
                current_question = ExtractedQuestion(
                    number=str(q_num),
                    text="",
                    text_confidence=block.confidence,
                    question_type=QuestionType.UNKNOWN,
                    question_type_confidence=0.0,
                    page_index=block.page_index,
                    start_y=block.bbox[1],
                    end_y=block.bbox[3],
                )
                question_text_parts = [remainder] if remainder else []
                current_subitems = []
                current_subitems_content = []
                current_subitem_label = None
                current_subitem_text_parts = []

                # Check for image indicators
                has_image, image_desc = self._detect_image_reference(text)
                if has_image:
                    current_question.has_image = True
                    current_question.image_description = image_desc

            elif current_question is not None:
                subitem_info = self._detect_subitem_with_text(text)
                is_subitem = subitem_info is not None and self._is_subitem_context(
                    text,
                    current_question,
                    cotacao_map,
                    question_text_parts,
                    current_subitems,
                )

                # Lowercase alíneas and an established subitem context take
                # precedence over the permissive option regex (a) also matches.
                if not is_subitem and self._is_option_block(text):
                    option = self._detect_option(text)
                    if option:
                        if current_question.options is None:
                            current_question.options = []
                        current_question.options.append(ExtractedOption(
                            option_label=option[0],
                            option_text=option[1],
                            confidence=block.confidence,
                        ))
                else:
                    if subitem_info:
                        subitem_label, subitem_remainder = subitem_info

                        # Save previous subitem if exists
                        if current_subitem_label and current_subitem_text_parts:
                            raw_subitem_text = " ".join(current_subitem_text_parts)
                            subitem_cotacao = self._extract_inline_cotacao(raw_subitem_text)
                            subitem_text = self._clean_text(raw_subitem_text)
                            if subitem_cotacao:
                                subitem_text = self._remove_inline_cotacao(subitem_text)
                            current_subitems_content.append(SubitemContent(
                                label=current_subitem_label,
                                text=self._clean_footer_garbage(subitem_text),
                                cotacao=subitem_cotacao,
                            ))

                        # Start new subitem
                        if subitem_label not in current_subitems:
                            current_subitems.append(subitem_label)
                        current_subitem_label = subitem_label
                        current_subitem_text_parts = [subitem_remainder] if subitem_remainder else []

                        # Still add full text to question
                        question_text_parts.append(text)
                    else:
                        # Add to question text
                        question_text_parts.append(text)
                        # Also add to current subitem if we're in one
                        if current_subitem_label:
                            current_subitem_text_parts.append(text)

                # Update end position
                current_question.end_y = max(current_question.end_y, block.bbox[3])

                # Check for image references in this block
                has_image, image_desc = self._detect_image_reference(text)
                if has_image and not current_question.has_image:
                    current_question.has_image = True
                    current_question.image_description = image_desc

        # Save last subitem
        if current_subitem_label and current_subitem_text_parts:
            raw_subitem_text = " ".join(current_subitem_text_parts)
            subitem_cotacao = self._extract_inline_cotacao(raw_subitem_text)
            subitem_text = self._clean_text(raw_subitem_text)
            if subitem_cotacao:
                subitem_text = self._remove_inline_cotacao(subitem_text)
            current_subitems_content.append(SubitemContent(
                label=current_subitem_label,
                text=self._clean_footer_garbage(subitem_text),
                cotacao=subitem_cotacao,
            ))

        # Save last question
        if current_question is not None:
            raw_question_text = " ".join(question_text_parts)
            inline_cotacao = self._extract_inline_cotacao(raw_question_text)
            current_question.text = self._clean_text(raw_question_text)
            if inline_cotacao is not None and current_question.cotacao is None:
                current_question.cotacao = inline_cotacao
                current_question.cotacao_confidence = 0.8
            current_question.subitems = current_subitems
            current_question.subitems_content = current_subitems_content
            self._finalize_question(current_question, cotacao_map)
            questions.append(current_question)

        return questions

    def _finalize_question(self, question: ExtractedQuestion, cotacao_map: Dict[str, float]):
        """Finalize question with type inference and cotação lookup."""
        # Infer question type
        question.question_type, question.question_type_confidence = self._infer_question_type(question)

        # Look up cotação - try multiple strategies
        cotacao_found = False

        # Strategy 1: Direct question number lookup from cotação block
        if question.number in cotacao_map:
            question.cotacao = cotacao_map[question.number]
            question.cotacao_confidence = 0.9
            cotacao_found = True

        # Strategy 2: Sum subitems cotação from cotação block
        if not cotacao_found:
            total_cotacao = 0.0
            found_subitems = False

            # Try with detected subitems
            for subitem in question.subitems:
                # Clean subitem: "a)" -> "a"
                clean_subitem = subitem.replace(')', '').replace('(', '').lower().strip()
                key = f"{question.number}{clean_subitem}"
                if key in cotacao_map:
                    total_cotacao += cotacao_map[key]
                    found_subitems = True

            # If no subitems detected, try common letters
            if not found_subitems:
                for letter in ['a', 'b', 'c', 'd', 'e']:
                    key = f"{question.number}{letter}"
                    if key in cotacao_map:
                        total_cotacao += cotacao_map[key]
                        found_subitems = True

            if found_subitems:
                question.cotacao = total_cotacao
                question.cotacao_confidence = 0.85
                cotacao_found = True

        # Strategy 3: Sum cotação from subitems_content (extracted inline)
        if not cotacao_found and question.subitems_content:
            total_cotacao = 0.0
            found_any = False
            for subitem in question.subitems_content:
                if subitem.cotacao is not None:
                    total_cotacao += subitem.cotacao
                    found_any = True
            if found_any:
                question.cotacao = total_cotacao
                question.cotacao_confidence = 0.85
                cotacao_found = True

        # Strategy 4: Look for inline cotação in question text (e.g., "(3V)", "(4 valores)")
        if not cotacao_found and question.text:
            inline_cotacao = self._extract_inline_cotacao(question.text)
            if inline_cotacao:
                question.cotacao = inline_cotacao
                question.cotacao_confidence = 0.8
                # Remove the cotação from question text
                question.text = self._remove_inline_cotacao(question.text)
                cotacao_found = True

        # Clean question text of any remaining inline cotação
        if question.text:
            question.text = self._remove_inline_cotacao(question.text)

    def _detect_question_number(self, text: str) -> Optional[Tuple[int, str]]:
        """Detect if text starts with a question number. Returns (number, remainder) or None."""
        text = text.strip()

        # Skip blocks that look like class/grade indicators (e.g., "12ª Classe", "12a Classe")
        class_pattern = r"^\d{1,2}\s*[ºª°a]?\s*(?:classe|class|ano|grade)"
        if re.match(class_pattern, text, re.IGNORECASE):
            return None

        # Skip blocks that look like header metadata (contain "Ano Lectivo", "Duração", etc.)
        header_keywords = [
            r"ano\s*let[ií]vo", r"ano\s*lect[ií]vo", r"duração", r"duracao",
            r"curso", r"proc\.", r"nome:", r"n[°º]?\s*proc"
        ]
        text_lower = text.lower()
        for kw in header_keywords:
            if re.search(kw, text_lower):
                return None

        for pattern in self.PATTERNS["question_number"]:
            match = re.match(pattern, text, re.IGNORECASE)
            if match:
                num_str = match.group(1)
                try:
                    num = int(num_str)
                    remainder = text[match.end():].strip()

                    # Additional validation: question numbers should be reasonable (1-50)
                    if num < 1 or num > 50:
                        continue

                    # Skip if the remainder looks like metadata (class, year, etc.)
                    if remainder:
                        remainder_lower = remainder.lower()
                        if any(kw in remainder_lower for kw in ['classe', 'class', 'ano lectivo', 'ano letivo', 'duração', 'duracao']):
                            continue

                    return (num, remainder)
                except ValueError:
                    continue

        return None

    def _detect_subitem(self, text: str) -> Optional[str]:
        """Detect if text starts with a subitem (a), b), etc.)"""
        text = text.strip()

        for pattern in self.PATTERNS["subitem"]:
            match = re.match(pattern, text, re.IGNORECASE)
            if match:
                return f"{match.group(1)})"

        return None

    def _detect_subitem_with_text(self, text: str) -> Optional[Tuple[str, str]]:
        """Detect subitem and return (label, remainder text)."""
        text = text.strip()

        # Patterns for subitems with their content
        patterns = [
            r"^\(?([a-z])\)?[.):]\s*(.*)$",  # a) text, a. text, (a) text
            r"^([a-z])\s*[-–—]\s*(.*)$",     # a - text, a – text
        ]

        for pattern in patterns:
            match = re.match(pattern, text, re.IGNORECASE)
            if match:
                label = f"{match.group(1).lower()})"
                remainder = match.group(2).strip()
                return (label, remainder)

        return None

    def _is_subitem_context(
        self,
        text: str,
        question: ExtractedQuestion,
        cotacao_map: Dict[str, float],
        question_text_parts: List[str],
        current_subitems: List[str],
    ) -> bool:
        """Disambiguate lowercase alíneas from multiple-choice options."""
        label_match = re.match(
            r"^\(?([A-Za-z])\)?[.):]\s*|^([A-Za-z])\s*[-–—]\s*",
            text.strip(),
        )
        if not label_match:
            return False

        label = next(group for group in label_match.groups() if group is not None)
        prompt = " ".join(question_text_parts).lower()
        multiple_choice_hint = any(
            keyword in prompt
            for keyword in self.QUESTION_TYPE_KEYWORDS[QuestionType.MULTIPLA_ESCOLHA]
        )

        # A prompt such as "assinale a alternativa" makes lowercase labels
        # valid options. Existing options also establish that interpretation.
        if question.options or multiple_choice_hint:
            return False

        if label.islower():
            return True

        # OCR may normalize a) to A). Cotação per alínea or a prior subitem
        # gives enough context to retain it as a multipart question.
        score_key = f"{question.number}{label.lower()}"
        return bool(current_subitems) or (
            score_key in cotacao_map and question.number not in cotacao_map
        )

    def _extract_inline_cotacao(self, text: str) -> Optional[float]:
        """Extract inline cotação like (3V), (2,5V), (4 valores) from text."""
        if not text:
            return None

        for pattern in self.PATTERNS.get("inline_cotacao", []):
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                value_str = match.group(1).replace(",", ".")
                try:
                    return float(value_str)
                except ValueError:
                    continue

        # Fallback patterns
        fallback_patterns = [
            r"\((\d+(?:[.,]\d+)?)\s*[Vv]\)",
            r"\((\d+(?:[.,]\d+)?)\s*valores?\)",
            r"\((\d+(?:[.,]\d+)?)\s*pontos?\)",
            r";(\d+(?:[.,]\d+)?)\s*[Vv]\)",  # Pattern like ";2V)"
        ]
        for pattern in fallback_patterns:
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                value_str = match.group(1).replace(",", ".")
                try:
                    return float(value_str)
                except ValueError:
                    continue

        return None

    def _remove_inline_cotacao(self, text: str) -> str:
        """Remove inline cotação from text."""
        if not text:
            return text

        patterns = [
            r"\s*\(\d+(?:[.,]\d+)?\s*[Vv]\)",
            r"\s*\(\d+(?:[.,]\d+)?\s*valores?\)",
            r"\s*\(\d+(?:[.,]\d+)?\s*pontos?\)",
            r"\s*\[\d+(?:[.,]\d+)?\s*[Vv]\]",
            r";\s*\d+(?:[.,]\d+)?\s*[Vv]\)",  # Pattern like ";2V)"
        ]
        for pattern in patterns:
            text = re.sub(pattern, "", text, flags=re.IGNORECASE)

        return text.strip()

    def _clean_footer_garbage(self, text: str) -> str:
        """Remove footer garbage like coordination signatures, website URLs, etc."""
        if not text:
            return text

        # Remove common footer patterns
        garbage_patterns = [
            r"A?\s*COORDENA[CÇ][AÃ]O.*$",
            r"minttics\.gov\.ao.*$",
            r"LUANDA[\s-]*ANGOLA.*$",
            r"gov\.ao.*$",
            r"\d+/E\d+/\d+.*$",
            r"kaixa\d*.*$",
            r"klvs.*$",
            r"GOIK.*$",
            r"b\s*=\s*N\.m\.I.*$",
            r"A7IUANDA.*$",
            r"\.1\.10\s+kaixa.*$",
        ]
        for pattern in garbage_patterns:
            text = re.sub(pattern, "", text, flags=re.IGNORECASE)

        # Clean up extra whitespace
        text = re.sub(r'\s+', ' ', text).strip()

        return text

    def _is_option_block(self, text: str) -> bool:
        """Check if text looks like a multiple choice option."""
        text = text.strip()
        for pattern in self.PATTERNS["option"]:
            if re.match(pattern, text, re.IGNORECASE):
                return True
        return False

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

    def _detect_image_reference(self, text: str) -> Tuple[bool, Optional[str]]:
        """Detect if text contains image/figure references."""
        text_lower = text.lower()

        for pattern in self.PATTERNS["image_indicator"]:
            match = re.search(pattern, text_lower)
            if match:
                # Try to extract a description
                desc = self._extract_image_description(text)
                return True, desc

        # Also check for mathematical expressions that might be images
        math_indicators = [
            r"\\int", r"\\frac", r"\\sqrt", r"\\sum", r"\\lim",
            r"\^{", r"_{", r"→", r"∫", r"√",
        ]
        for indicator in math_indicators:
            if indicator in text:
                return True, "Expressão matemática complexa"

        return False, None

    def _extract_image_description(self, text: str) -> str:
        """Extract a description for an image reference."""
        # Look for descriptive text near the image indicator
        patterns = [
            r"(?:figura|gráfico|tabela)\s+(?:que\s+)?(?:mostra|representa|ilustra)\s+(.+?)(?:\.|$)",
            r"(?:observe|analise|considere)\s+(.+?)(?:\.|$)",
        ]

        for pattern in patterns:
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                return match.group(1).strip()

        # Default description
        if "gráfico" in text.lower():
            return "Gráfico"
        elif "tabela" in text.lower():
            return "Tabela"
        elif "figura" in text.lower():
            return "Figura"

        return "Imagem ou expressão visual"

    def _is_coordination_block(self, text: str) -> bool:
        """Check if text is a coordination/signature block."""
        text_lower = text.lower()

        for pattern in self.PATTERNS["coordination"]:
            if re.search(pattern, text_lower):
                return True

        return False

    def _detect_images_to_upload(
        self,
        metadata: ExtractedMetadata,
        questions: List[ExtractedQuestion],
        blocks: List[TextBlock],
        full_text: str,
        page_dimensions: Dict[int, Tuple[int, int]],
        source_file_indices: Dict[int, int],
    ) -> List[ImageToUpload]:
        """Detect regions that should be uploaded as images."""
        images = []

        def dimensions(page_index: int) -> Tuple[Optional[int], Optional[int]]:
            return page_dimensions.get(page_index, (None, None))

        def source_file_index(page_index: int) -> int:
            return source_file_indices.get(page_index, 0)

        def region_bbox(
            page_index: int,
            y_start: int,
            y_end: int,
            full_width: bool = True,
        ) -> Optional[Tuple[int, int, int, int]]:
            page_blocks = [b for b in blocks if b.page_index == page_index]
            selected = [
                b for b in page_blocks
                if b.bbox[3] >= y_start and b.bbox[1] <= y_end
            ]
            if not selected:
                return None

            width, height = dimensions(page_index)
            padding = 24
            x1 = 0 if full_width and width else min(b.bbox[0] for b in selected) - padding
            x2 = width if full_width and width else max(b.bbox[2] for b in selected) + padding
            top = max(0, y_start - padding)
            bottom = min(height, y_end + padding) if height else y_end + padding
            return (max(0, x1), top, max(x1 + 1, x2), max(top + 1, bottom))

        # Build base filename
        base_name = "prova"
        if metadata.subject_name.value:
            base_name = f"prova-{metadata.subject_name.value.lower().replace(' ', '-')}"
        if metadata.school_year_start.value and metadata.school_year_end.value:
            base_name = f"{base_name}-{metadata.school_year_start.value}-{metadata.school_year_end.value}"
        if metadata.variant.value:
            base_name = f"{base_name}-serie-{metadata.variant.value.lower()}"

        # Check for header/logo region
        if blocks and len(blocks) > 5:
            header_text = " ".join(b.text for b in blocks[:5])
            if any(kw in header_text.lower() for kw in ["república", "angola", "ministério", "governo", "gabinete"]):
                page_index = blocks[0].page_index
                bbox = region_bbox(page_index, 0, max(b.bbox[3] for b in blocks[:5]))
                source_width, source_height = dimensions(page_index)
                images.append(ImageToUpload(
                    suggested_filename=f"{base_name}-cabecalho.png",
                    description="Cabeçalho oficial com brasão/logo institucional",
                    region="cabecalho",
                    bbox=bbox,
                    page_index=page_index,
                    source_width=source_width,
                    source_height=source_height,
                    source_file_index=source_file_index(page_index),
                ))

        # Add images for questions with visual content
        for question in questions:
            if question.has_image:
                source_width, source_height = dimensions(question.page_index)
                bbox = region_bbox(
                    question.page_index,
                    question.start_y,
                    max(question.end_y, question.start_y + 1),
                )
                images.append(ImageToUpload(
                    suggested_filename=f"{base_name}-questao-{question.number}.png",
                    description=question.image_description or f"Imagem da questão {question.number}",
                    region=f"questao_{question.number}",
                    bbox=bbox,
                    page_index=question.page_index,
                    source_width=source_width,
                    source_height=source_height,
                    source_file_index=source_file_index(question.page_index),
                ))

        # Check for coordination/signature at footer
        for pattern in self.PATTERNS["coordination"]:
            if re.search(pattern, full_text.lower()):
                page_index = max((b.page_index for b in blocks), default=0)
                footer_blocks = [b for b in blocks if b.page_index == page_index][-5:]
                footer_start = min((b.bbox[1] for b in footer_blocks), default=0)
                footer_end = max((b.bbox[3] for b in footer_blocks), default=footer_start)
                bbox = region_bbox(page_index, footer_start, footer_end)
                source_width, source_height = dimensions(page_index)
                images.append(ImageToUpload(
                    suggested_filename=f"{base_name}-assinatura-coordenacao.png",
                    description="Assinatura da coordenação no rodapé da prova com texto A COORDENAÇÃO",
                    region="rodape",
                    bbox=bbox,
                    page_index=page_index,
                    source_width=source_width,
                    source_height=source_height,
                    source_file_index=source_file_index(page_index),
                ))
                break

        return images

    def _infer_question_type(self, question: ExtractedQuestion) -> Tuple[QuestionType, float]:
        """Infer question type based on text and options."""
        text_lower = question.text.lower()

        # If has options, it's multiple choice
        if question.options and len(question.options) > 0:
            return QuestionType.MULTIPLA_ESCOLHA, 0.95

        # Check keywords for question type
        for q_type, keywords in self.QUESTION_TYPE_KEYWORDS.items():
            matches = sum(1 for kw in keywords if kw in text_lower)
            if matches > 0:
                confidence = min(0.7 + (matches * 0.1), 0.95)
                return q_type, confidence

        # Default to dissertativa for math/science exams
        return QuestionType.DISSERTATIVA, 0.7

    def _estimate_confidence_from_match(
        self,
        match: re.Match,
        full_text: str,
        blocks: List[TextBlock],
    ) -> float:
        """Estimate confidence for a regex match based on surrounding text blocks."""
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
        for pattern in self.PATTERNS["cotacao"]:
            text = re.sub(pattern, "", text, flags=re.IGNORECASE)

        # Remove inline cotação blocks (e.g., "COTACAO:1) a - 4 V,b - 4 V/2) 5 V...")
        text = re.sub(r"COTACAO\s*:\s*[^.]*(?:\.|$)", "", text, flags=re.IGNORECASE)
        text = re.sub(r"COTAÇÃO\s*:\s*[^.]*(?:\.|$)", "", text, flags=re.IGNORECASE)

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
            ("schoolYearStart", metadata.school_year_start),
            ("schoolYearEnd", metadata.school_year_end),
            ("subjectName", metadata.subject_name),
            ("classGrade", metadata.class_grade),
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


    def _clean_null_fields(self, metadata: ExtractedMetadata) -> ExtractedMetadata:
        """
        Clean up metadata fields that have no value or low confidence.
        This prevents persisting empty/null data to the database.
        """
        # Fields that should not be persisted if null/empty
        # We keep the structure but ensure confidence is 0 for null values

        # For fields with None value, ensure confidence is 0
        if metadata.school_year_start.value is None:
            metadata.school_year_start = MetadataField(None, 0.0)
        if metadata.school_year_end.value is None:
            metadata.school_year_end = MetadataField(None, 0.0)
        if metadata.class_grade.value is None:
            metadata.class_grade = MetadataField(None, 0.0)
        if metadata.class_info.value is None:
            metadata.class_info = MetadataField(None, 0.0)
        if metadata.course_name.value is None:
            metadata.course_name = MetadataField(None, 0.0)
        if metadata.subject_name.value is None:
            metadata.subject_name = MetadataField(None, 0.0)
        if metadata.exam_type.value is None:
            metadata.exam_type = MetadataField(None, 0.0)
        if metadata.duration_minutes.value is None:
            metadata.duration_minutes = MetadataField(None, 0.0)
        if metadata.variant.value is None:
            metadata.variant = MetadataField(None, 0.0)
        if metadata.total_max_score.value is None:
            metadata.total_max_score = MetadataField(None, 0.0)

        # Clean course name if it contains garbage
        if metadata.course_name.value:
            course = metadata.course_name.value
            # If course is just "DE" or similar garbage, set to None
            if course in ['DE', 'DA', 'DO', 'DAS', 'DOS', 'E', 'A', 'O']:
                metadata.course_name = MetadataField(None, 0.0)
                metadata.course = MetadataField(None, 0.0)

        return metadata


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

    # Common OCR fixes for Portuguese
    ocr_fixes = {
        r"12ª": "12ª",
        r"12a": "12ª",
        r"12°": "12ª",
        r"Séria": "Série",
        r"Sèrie": "Série",
        r"Matematic[ao]": "Matemática",
    }

    for pattern, replacement in ocr_fixes.items():
        text = re.sub(pattern, replacement, text, flags=re.IGNORECASE)

    return text


def detect_language(text: str) -> str:
    """
    Detect the primary language of the text.

    Returns language code (pt, en, etc.)
    """
    # Portuguese indicators
    pt_words = ["prova", "exame", "classe", "ano", "duração", "valores", "questão",
                "questões", "calcule", "calcular", "determine", "determinar", "resolva",
                "resolver", "trimestre", "letivo", "aluno", "matemática", "atenção"]

    # English indicators
    en_words = ["exam", "class", "year", "duration", "points", "question",
                "calculate", "determine", "solve", "term"]

    text_lower = text.lower()
    pt_count = sum(1 for word in pt_words if word in text_lower)
    en_count = sum(1 for word in en_words if word in text_lower)

    if pt_count > en_count:
        return "pt"
    elif en_count > pt_count:
        return "en"
    else:
        return "pt"  # Default to Portuguese for Angolan context

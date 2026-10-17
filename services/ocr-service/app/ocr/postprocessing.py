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

import math
import re
from dataclasses import dataclass, field, replace
from typing import Optional, List, Dict, Any, Tuple
from enum import Enum

from .formulas import FORMULA, FormulaSegment, extract_inline_formulas, recognize_region
from .layout import LayoutRegion
from .ptlex import Lexicon, load_lexicon, repair_text


class QuestionType(str, Enum):
    """Supported question types.

    - ``dissertativa``: resposta escrita.
    - ``multipla_escolha``: opções A/B/C/D (duas ou mais, seguidas).
    - ``verdadeiro_falso``: um enunciado comum e alíneas que se assinalam com V ou F.
    - ``composta``: um enunciado comum e alíneas (a, b, ...) que têm cada uma a sua resposta.
    """
    DISSERTATIVA = "dissertativa"
    MULTIPLA_ESCOLHA = "multipla_escolha"
    VERDADEIRO_FALSO = "verdadeiro_falso"
    COMPOSTA = "composta"
    UNKNOWN = "unknown"


@dataclass
class TextBlock:
    """Represents a block of text with position and confidence."""
    text: str
    confidence: float
    bbox: Tuple[int, int, int, int]  # x1, y1, x2, y2
    page_index: int = 0
    line_index: int = 0
    # In blue pen: a student's working or a teacher's note, never part of the printed exam (set by the engine,
    # which is the one that sees the colours).
    handwritten: bool = False


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
    kind: Optional[str] = None  # figure, table or formula; None for the header and signature crops
    score: Optional[float] = None


@dataclass
class SubitemContent:
    """Represents a subitem (alínea) with its label and content.

    ``label`` sai em minúscula e sem parêntese ("a", "b", ...). ``ExtractedQuestion.subitems`` continua a
    trazer só os rótulos no formato antigo ("a)", "b)"). ``type`` diz como se responde à alínea:
    ``dissertativa``, ``verdadeiro_falso`` ou ``multipla_escolha``; é omitido quando não foi inferido.
    """
    label: str
    text: str   # The content of the subitem
    cotacao: Optional[float] = None
    type: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {
            "label": self.label,
            "text": self.text,
            "cotacao": self.cotacao,
        }
        if self.type is not None:
            result["type"] = self.type
        return result


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
    # Fórmulas reconhecidas no enunciado, nas alíneas ou nas opções (#262); vazio quando
    # nada no texto da questão pareceu matemática. "text" já traz os trechos reconhecidos
    # entre $...$ - esta lista é o mesmo resultado, estruturado, com o texto em bruto e a
    # confiança de cada um.
    formulas: List[FormulaSegment] = field(default_factory=list)

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
            "formulas": [f.to_dict() for f in self.formulas],
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
    # (page, y) where the instructions end; internal, used to find the text between them and question 1.
    instruction_end: Optional[Tuple[int, int]] = field(default=None, repr=False, compare=False)

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
    reason: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert to dictionary for JSON serialization."""
        result: Dict[str, Any] = {
            "pageIndex": self.page_index,
            "text": self.text,
            "confidence": round(self.confidence, 3),
        }
        if self.reason:
            result["reason"] = self.reason
        return result


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


@dataclass
class _Alinea:
    """An alínea being collected (label without the parenthesis: "a", "b"...)."""
    label: str
    parts: List[str] = field(default_factory=list)


@dataclass
class _Draft:
    """A question being collected: its stem lines and its alíneas, before they are assembled."""
    question: ExtractedQuestion
    stem_parts: List[str] = field(default_factory=list)
    alineas: List[_Alinea] = field(default_factory=list)


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
            r"exame\s+(?:especial\s+)?de\s+([A-Za-záàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ]+)",
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
            r"(\d{1,2})\s*[ºª°aᵃ@]?\s*classe",
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
            # With the unit required: "(3)" alone is maths ("f(3)"), not a score.
            r"[(\[]\s*(\d+(?:[.,]\d+)?)\s*(?:valores?|pontos?|pts?|marks?|points?)\s*[)\]]",
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
        repair_text: bool = True,
        filter_noise: bool = True,
    ):
        """
        Initialize the postprocessor.

        Args:
            min_confidence_threshold: Minimum confidence to include results
            low_confidence_threshold: Threshold below which to add warnings
            repair_text: Repair accents, one-letter OCR slips and glued words with the Portuguese lexicon
            filter_noise: Move handwriting/margin noise out of the questions into ``unmapped_content``
        """
        self.min_confidence_threshold = min_confidence_threshold
        self.low_confidence_threshold = low_confidence_threshold
        self.repair_text = repair_text
        self.filter_noise = filter_noise
        self._lexicon: Optional[Lexicon] = load_lexicon() if repair_text else None

    def process(
        self,
        text_blocks: List[TextBlock],
        page_count: int = 1,
        page_dimensions: Optional[Dict[int, Tuple[int, int]]] = None,
        source_file_indices: Optional[Dict[int, int]] = None,
        layout_regions: Optional[Dict[int, List[LayoutRegion]]] = None,
        formula_images: bool = False,
        recognize_formulas: bool = True,
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

        # Reading order: by page, then row by row, left to right inside each row
        sorted_blocks = self._reading_order(text_blocks)

        # Repair the OCR text (accents, one-letter slips, glued words), then move handwriting out of the way.
        sorted_blocks = self._repair_blocks(sorted_blocks)
        noise: List[Tuple[TextBlock, str]] = []
        page_limits: Dict[int, int] = {}
        if self.filter_noise:
            sorted_blocks, noise = self._split_noise(sorted_blocks)
            # Nothing the exam says comes after its cotação line: below it there are signatures, stamps, logos and
            # whatever a student scribbled in the free space.
            sorted_blocks, after, page_limits = self._cut_after_cotacao(sorted_blocks, page_dimensions or {})
            noise.extend(after)
        # Maths the text engine cannot read is garbage in the text: the layout knows where the printed formulas are.
        sorted_blocks = self._mask_formulas(sorted_blocks, layout_regions, page_limits, recognize=recognize_formulas)

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
        warnings.extend(self._generate_cotacao_warnings(metadata, questions))

        # Recognize formulas inline in the already-extracted question text (#262): done last, on the
        # finished questions, so it never risks the numbering/cotação/option parsing above, which depends
        # on the exact (unmodified) text the OCR and _mask_formulas produced.
        if recognize_formulas:
            questions = [self._recognize_question_formulas(q) for q in questions]

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
            layout_regions,
            formula_images,
            page_limits,
        )

        # Collect unmapped content
        unmapped = self._collect_unmapped(sorted_blocks, metadata, questions, noise)

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
        # The label usually sits in its own printed line ("CURSO: TODOS"): read it from there first, so the
        # text that follows in the next lines cannot interfere.
        course_match = None
        for block in blocks[:25]:
            course_match = re.match(r"^\W*curso\s*:\s*([A-Za-záàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ\s]+?)\s*$", block.text, re.IGNORECASE)
            if course_match:
                break
        if course_match is None:
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

        # Extract instructions (look for instruction keywords). The paragraph often continues on the next
        # printed lines ("... e responda de" / "uma forma clara."), so those are joined to it.
        instruction_keywords = ["leia", "responda", "atenção", "instruções", "coloque", "forma clara"]
        for index, block in enumerate(blocks[:25]):
            if any(kw in block.text.lower() for kw in instruction_keywords):
                # Check if this looks like an instruction block
                if len(block.text) > 30 and not self._detect_question_number(block.text):
                    text, last = block.text.strip(), block
                    for nxt in blocks[index + 1:index + 4]:
                        if re.search(r'[.!?:"”»]\s*$', text):
                            break
                        height = max(1, last.bbox[3] - last.bbox[1])
                        close = 0 <= nxt.bbox[1] - last.bbox[3] <= 1.5 * height and abs(nxt.bbox[0] - block.bbox[0]) <= 30
                        if not close or self._detect_question_number(nxt.text) or nxt.text.lstrip().startswith(('"', "“")):
                            break
                        text, last = f"{text} {nxt.text.strip()}", nxt
                    metadata.instructions = MetadataField(text, block.confidence)
                    metadata.instruction_end = (last.page_index, last.bbox[1])
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

        # An acronym (OGI, TLP) stays as written; only full words are title-cased.
        if re.fullmatch(r"[A-ZÀ-Ý]{2,5}", subject.strip()):
            return subject.strip()

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

    # ------------------------------------------------------------------ preparation: order, repair, noise

    @staticmethod
    def _reading_order(blocks: List[TextBlock]) -> List[TextBlock]:
        """Page by page, row by row, left to right inside a row.

        Sorting by ``(y, x)`` alone scrambles a row of options: the engine reports "A) 7" two pixels lower than
        "B) 8 C) 9" beside it, so B came first. Two blocks are on the same row when they overlap vertically by at
        least half of the shorter one.
        """
        ordered: List[TextBlock] = []
        for page in sorted({b.page_index for b in blocks}):
            page_blocks = [b for b in blocks if b.page_index == page]
            heights = sorted(max(1, b.bbox[3] - b.bbox[1]) for b in page_blocks)
            typical = heights[len(heights) // 2] if heights else 1
            rows: List[List[TextBlock]] = []
            spans: List[Tuple[int, int]] = []
            for block in sorted(page_blocks, key=lambda b: (b.bbox[1], b.bbox[0])):
                top, bottom = block.bbox[1], block.bbox[3]
                if bottom - top > 1.4 * typical:
                    # A tall box (a handwritten note, a stamp) must not stretch a row until it swallows the next
                    # one, which scrambles two lines of options: it joins the row at its vertical centre.
                    centre = (top + bottom) / 2
                    home = next((i for i in range(len(rows) - 1, max(-1, len(rows) - 4), -1) if spans[i][0] <= centre <= spans[i][1]), None)
                    if home is not None:
                        rows[home].append(block)
                    else:
                        rows.append([block])
                        spans.append((int(centre - typical / 2), int(centre + typical / 2)))
                    continue
                if rows:
                    row_top, row_bottom = spans[-1]
                    overlap = min(bottom, row_bottom) - max(top, row_top)
                    if overlap >= 0.5 * min(bottom - top, row_bottom - row_top):
                        rows[-1].append(block)
                        spans[-1] = (min(row_top, top), max(row_bottom, bottom))
                        continue
                rows.append([block])
                spans.append((top, bottom))
            for row in rows:
                ordered.extend(sorted(row, key=lambda b: b.bbox[0]))
        return ordered

    def _repair_blocks(self, blocks: List[TextBlock]) -> List[TextBlock]:
        """Repair accents, one-letter OCR slips and glued words in every block (see ``ptlex``)."""
        if not self.repair_text or self._lexicon is None:
            return blocks
        repaired: List[TextBlock] = []
        for block in blocks:
            text = repair_text(block.text, self._lexicon)
            if text:
                repaired.append(replace(block, text=text))
        return repaired

    _LETTER_WORD = re.compile(r"[A-Za-zÀ-ÖØ-öø-ÿ]{3,}")

    def _words3(self, text: str) -> int:
        return len(self._LETTER_WORD.findall(text))

    def _is_structural(self, text: str) -> bool:
        """Lines that carry exam structure (numbers, alíneas, options, cotação) even when they hold few words."""
        stripped = text.strip()
        return bool(
            re.match(r"^\(?\d{1,2}\s*[).:\-–—º°]", stripped)
            or re.match(r"^\(?[A-Za-z]\)\s*", stripped)
            or re.search(r"\d\s*(?:valores?|pontos?|pts?)\b|\(\s*\d+(?:[.,]\d+)?\s*[Vv]\s*\)", stripped, re.IGNORECASE)
            or re.match(r"^\s*cota[çc][ãa]o\b", stripped, re.IGNORECASE)
        )

    def _split_noise(self, blocks: List[TextBlock]) -> Tuple[List[TextBlock], List[Tuple[TextBlock, str]]]:
        """Separate handwriting and margin marks from the printed text.

        Deliberately conservative: a block is noise only when it is not prose (fewer than two words of three
        letters) AND one of these holds:

        1. it sits above the first printed line of the page (page numbers, scribbles over the header);
        2. it sits beside a printed line (to the right or left of it, past a 30 px gap) with confidence below
           0.92 (handwritten answers and annotations are read worse than print);
        3. its confidence is below 0.6 and it has at most six letters.

        Lines that carry structure (question numbers, alíneas, cotação) are never noise, and a page with too
        little printed text to tell what "beside a printed line" means is left alone.
        """
        reasons: Dict[int, str] = {}
        for page in sorted({b.page_index for b in blocks}):
            page_blocks = [b for b in blocks if b.page_index == page]
            prose = [b for b in page_blocks if b.confidence >= 0.85 and self._words3(b.text) >= 2]
            if len(prose) < 4:
                continue
            first_y = min(b.bbox[1] for b in prose)
            # A line of values ("192.168.1.100") is not prose, but a scribble to its right is as much a note as
            # one beside a sentence: the near-certain lines of print count as the line it sits beside.
            solid = [b for b in page_blocks if b.confidence >= 0.95 and len(b.text.strip()) >= 5]
            width = max((b.bbox[2] for b in page_blocks), default=0)
            for b in page_blocks:
                if b.handwritten:
                    reasons[id(b)] = "Manuscrito (tinta que não é de impressão)"
                    continue
                if self._words3(b.text) > 1:
                    continue
                # A bare number is "structural" (it looks like a cotação), but one far out in the right margin, on a
                # line of print, is a value the teacher wrote ("0.0.3.255" beside the option it corrects).
                scribbled_number = (
                    re.fullmatch(r"[\d.,\s]+", b.text.strip()) is not None
                    and b.confidence < 0.97
                    and self._far_right_of(b, solid, width)
                )
                if self._is_structural(b.text) and not scribbled_number:
                    continue
                letters = len(re.findall(r"[A-Za-zÀ-ÖØ-öø-ÿ]", b.text))
                if b.bbox[3] < first_y - 5:
                    reasons[id(b)] = "Marca ou manuscrito acima do texto impresso"
                elif (b.confidence < 0.92 and self._beside_prose(b, prose)) or (
                    b.confidence < 0.97 and self._far_right_of(b, solid, width)
                ):
                    reasons[id(b)] = "Marca ou manuscrito ao lado de uma linha impressa"
                elif b.confidence < 0.6 and letters <= 6:
                    reasons[id(b)] = "Leitura de confiança muito baixa"
        # The reading order the caller established is kept; only the noise leaves.
        kept = [b for b in blocks if id(b) not in reasons]
        noise = [(b, reasons[id(b)]) for b in blocks if id(b) in reasons]
        return kept, noise

    # "2) 4 valores 3) 4valores 4) 5valores": the rest of a cotação line that the engine cut in two.
    _COTACAO_CONTINUATION = re.compile(
        r"^\s*(?:\d{1,2}\s*[-.)]\s*(?:[a-z]\s*[-–)]\s*)?\d+(?:[.,]\d+)?\s*(?:valores?|v)\b[\s,;/]*)+$",
        re.IGNORECASE,
    )
    _COTACAO_LINE = re.compile(r"^\s*cota[cç][aã]o\b", re.IGNORECASE)
    _LABEL_PREFIX = re.compile(r"^\(?[A-Za-z]\)\s*|^\d{1,2}\s*[-.)]\s*")

    def _cut_after_cotacao(
        self,
        blocks: List[TextBlock],
        page_dimensions: Dict[int, Tuple[int, int]],
    ) -> Tuple[List[TextBlock], List[Tuple[TextBlock, str]], Dict[int, int]]:
        """Drop everything below the «Cotação…» line of the page, in the lower half of the page.

        Also returns, per page, the y below which nothing belongs to the exam (figures and formulas included)."""
        cut: Dict[int, int] = {}
        region_limit: Dict[int, int] = {}
        for block in blocks:
            if not self._COTACAO_LINE.match(block.text) or block.page_index in cut:
                continue
            height = page_dimensions.get(block.page_index, (0, 0))[1]
            if height and block.bbox[1] < 0.5 * height:
                continue  # a cotação near the top is part of the header, not the end of the exam
            row_bottom = max(
                (b.bbox[3] for b in blocks
                 if b.page_index == block.page_index
                 and min(b.bbox[3], block.bbox[3]) - max(b.bbox[1], block.bbox[1]) >= 0.4 * (block.bbox[3] - block.bbox[1])),
                default=block.bbox[3],
            )
            row_top = min(
                (b.bbox[1] for b in blocks
                 if b.page_index == block.page_index
                 and min(b.bbox[3], block.bbox[3]) - max(b.bbox[1], block.bbox[1]) >= 0.4 * (block.bbox[3] - block.bbox[1])),
                default=block.bbox[1],
            )
            cut[block.page_index] = row_bottom + 6
            region_limit[block.page_index] = row_top - 4
        if not cut:
            return blocks, [], {}
        kept: List[TextBlock] = []
        dropped: List[Tuple[TextBlock, str]] = []
        for block in blocks:
            limit = cut.get(block.page_index)
            if limit is not None and block.bbox[1] > limit:
                dropped.append((block, "Depois da linha de cotação: fora da prova"))
            else:
                kept.append(block)
        return kept, dropped, region_limit

    @staticmethod
    def _recognize_question_formulas(question: ExtractedQuestion) -> ExtractedQuestion:
        """Looks for formulas inside a question's own text, alíneas and options (#262).

        Runs after the question is fully assembled, so it only ever touches `.text` content - never the
        numbering, cotação or option labels the rest of the postprocessor already relied on to get here. A
        question with nothing that looks like maths comes back unchanged (same object, no `formulas`).
        """
        segments: List[FormulaSegment] = []

        new_text, found = extract_inline_formulas(question.text)
        segments.extend(found)

        new_subitems = question.subitems_content
        if question.subitems_content:
            updated = []
            for item in question.subitems_content:
                item_text, item_found = extract_inline_formulas(item.text)
                segments.extend(item_found)
                updated.append(replace(item, text=item_text) if item_found else item)
            new_subitems = updated

        new_options = question.options
        if question.options:
            updated_opts = []
            for opt in question.options:
                opt_text, opt_found = extract_inline_formulas(opt.option_text)
                segments.extend(opt_found)
                updated_opts.append(replace(opt, option_text=opt_text) if opt_found else opt)
            new_options = updated_opts

        if not segments:
            return question
        return replace(question, text=new_text, subitems_content=new_subitems, options=new_options, formulas=segments)

    def _printed_formulas(
        self,
        regions: Optional[Dict[int, List[LayoutRegion]]],
        page_limits: Optional[Dict[int, int]] = None,
    ) -> Dict[int, List[LayoutRegion]]:
        """The formulas of the layout that are print, above the cotação line of their page."""
        limits = page_limits or {}
        return {
            page: [
                r for r in found
                if r.kind == "formula" and not r.handwritten and r.score >= 0.5
                and (page not in limits or r.bbox[1] < limits[page])
            ]
            for page, found in (regions or {}).items()
        }

    def _mask_formulas(
        self,
        blocks: List[TextBlock],
        layout_regions: Optional[Dict[int, List[LayoutRegion]]],
        page_limits: Optional[Dict[int, int]] = None,
        recognize: bool = True,
    ) -> List[TextBlock]:
        """Replace a printed formula's text with its recognized LaTeX (#262), delimited («$...$»), or with the
        «[fórmula]» marker when nothing could be read from it with confidence - keeping the label before it («c)»).

        A block is a formula when most of its area lies inside a printed formula region of the layout. One output
        stands for one formula region, however many lines the engine cut it into: every line is joined (in the
        order they were read) before being handed to the recognizer, so it sees the whole formula, not just its
        first line. The formula itself is still cropped as a picture (when ``ocr_formula_images`` is on) and
        travels with the question either way - the picture is the audit trail; the LaTeX is for showing and
        searching the formula as text.
        """
        formulas = self._printed_formulas(layout_regions, page_limits)
        if not any(formulas.values()):
            return blocks

        def home_of(block: TextBlock) -> Optional[Tuple[int, int]]:
            if self._COTACAO_LINE.match(block.text) or self._COTACAO_CONTINUATION.match(block.text):
                return None  # the scoring line is read by its text; a formula box over it is a mistake
            x1, y1, x2, y2 = block.bbox
            area = max(1, (x2 - x1) * (y2 - y1))
            idx = next(
                (i for i, r in enumerate(formulas.get(block.page_index, []))
                 if self._overlap(block.bbox, r.bbox) >= 0.6 * area),
                None,
            )
            return None if idx is None else (block.page_index, idx)

        homes = [home_of(b) for b in blocks]
        group_text: Dict[Tuple[int, int], str] = {}
        for block, key in zip(blocks, homes):
            if key is None:
                continue
            group_text[key] = f"{group_text[key]} {block.text}".strip() if key in group_text else block.text

        out: List[TextBlock] = []
        seen: set = set()
        for block, key in zip(blocks, homes):
            if key is None:
                out.append(block)
                continue
            if key in seen:
                continue  # the rest of this formula's lines contributed to group_text already; drop the line
            seen.add(key)
            prefix = self._LABEL_PREFIX.match(block.text.strip())
            lead = prefix.group(0).strip() + " " if prefix else ""
            recognized = recognize_region(group_text[key]) if recognize else None
            if recognized is not None and recognized.kind == FORMULA and recognized.latex:
                text = f"{lead}${recognized.latex}$"
            else:
                text = f"{lead}[fórmula]"
            out.append(replace(block, text=text))
        return out

    @staticmethod
    def _far_right_of(block: TextBlock, lines: List[TextBlock], page_width: int) -> bool:
        """A scribble in the right part of the page, on the row of a line of print and clear of it."""
        x1, y1, x2, y2 = block.bbox
        if x1 < 0.55 * page_width:
            return False
        for line in lines:
            if line is block:
                continue
            lx1, ly1, lx2, ly2 = line.bbox
            overlap = min(y2, ly2) - max(y1, ly1)
            if overlap >= 0.5 * min(y2 - y1, ly2 - ly1) and x1 > lx2 + 30:
                return True
        return False

    @staticmethod
    def _beside_prose(block: TextBlock, prose: List[TextBlock]) -> bool:
        x1, y1, x2, y2 = block.bbox
        for line in prose:
            if line is block:
                continue
            lx1, ly1, lx2, ly2 = line.bbox
            overlap = min(y2, ly2) - max(y1, ly1)
            if overlap >= 0.5 * min(y2 - y1, ly2 - ly1) and (x1 > lx2 + 30 or x2 < lx1 - 30):
                return True
        return False

    # ------------------------------------------------------------------ question extraction

    _TRUE_FALSE_STEM = re.compile(
        r"verdadeir[oa]s?\b.{0,90}\bfals[oa]s?\b|\bfals[oa]s?\b.{0,90}\bverdadeir[oa]s?\b|\bv\s*(?:ou|/)\s*f\b",
        re.IGNORECASE,
    )
    _TRUE_FALSE_TAIL = re.compile(r"\(\s*[VvFf]?\s*\)\s*[.;]?\s*$")
    _CHOICE_INSTRUCTION = re.compile(
        r"\b(?:assinale|marque|seleccione|selecione|circule|escolha)\s+(?:\w+\s+){0,3}?"
        r"(?:alternativa|op[çc][ãa]o|op[çc][õo]es|resposta\s+(?:certa|correcta|correta)|(?:certa|correcta|correta))",
        re.IGNORECASE,
    )

    def _extract_questions(
        self,
        blocks: List[TextBlock],
        cotacao_map: Dict[str, float]
    ) -> List[ExtractedQuestion]:
        """Extract and structure questions from text blocks.

        Question numbers and alíneas are recognised by **sequence**: the next question is the last number + 1
        and the next alínea is the next letter. That is what lets "1 O que…" (no punctuation) and "aDeve…" (no
        parenthesis) count, while "2.500 unidades" or "90 mil" in the middle of a sentence do not.
        """
        questions: List[ExtractedQuestion] = []
        draft: Optional[_Draft] = None
        blocks = self._repair_lost_labels(blocks)
        blocks = self._label_by_cotacao(blocks)

        def close() -> None:
            nonlocal draft
            if draft is not None:
                questions.append(self._build_question(draft, cotacao_map))
                draft = None

        last_number = 0
        for block in blocks:
            text = block.text.strip()
            if not text:
                continue
            if re.match(r"^\s*cotaç[aã]o\s", text, re.IGNORECASE) or self._COTACAO_CONTINUATION.match(text):
                continue
            if self._is_coordination_block(text):
                continue

            question_info = self._detect_question_number(text, expected=last_number + 1)
            if question_info is not None:
                close()
                q_num, remainder = question_info
                last_number = q_num
                question = ExtractedQuestion(
                    number=str(q_num),
                    text="",
                    text_confidence=block.confidence,
                    question_type=QuestionType.UNKNOWN,
                    question_type_confidence=0.0,
                    page_index=block.page_index,
                    start_y=block.bbox[1],
                    end_y=block.bbox[3],
                )
                # "1) Quanto é 2 + 2? A)3 B)4 C)5 D)6": the options may share the line with the stem.
                inline_here = self._split_inline_options(remainder, 0) if remainder else None
                if inline_here is not None:
                    remainder = inline_here[0]
                    question.options = [
                        ExtractedOption(option_label=label, option_text=option_text, confidence=block.confidence)
                        for label, option_text in inline_here[1]
                    ]
                draft = _Draft(question=question, stem_parts=[remainder] if remainder else [])
                has_image, image_desc = self._detect_image_reference(text)
                if has_image:
                    question.has_image = True
                    question.image_description = image_desc
                continue

            if draft is None:
                continue

            question = draft.question
            inline = None if draft.alineas else self._split_inline_options(text, len(question.options or []))
            if inline is not None:
                lead, found = inline
                if lead:
                    self._append_part(draft, lead)
                if question.options is None:
                    question.options = []
                question.options.extend(
                    ExtractedOption(option_label=label, option_text=option_text, confidence=block.confidence)
                    for label, option_text in found
                )
                question.end_y = max(question.end_y, block.bbox[3])
                continue
            alinea = self._detect_alinea(text, draft, cotacao_map)
            if alinea is not None:
                label, remainder = alinea
                draft.alineas.append(_Alinea(label=label, parts=[remainder] if remainder else []))
            elif self._is_option_block(text):
                option = self._detect_option(text)
                if option:
                    if question.options is None:
                        question.options = []
                    question.options.append(ExtractedOption(
                        option_label=option[0],
                        option_text=option[1],
                        confidence=block.confidence,
                    ))
                else:
                    self._append_part(draft, text)
            else:
                self._append_part(draft, text)

            question.end_y = max(question.end_y, block.bbox[3])
            has_image, image_desc = self._detect_image_reference(text)
            if has_image and not question.has_image:
                question.has_image = True
                question.image_description = image_desc

        close()
        return questions

    _UNIT_END = re.compile(r"\(\s*\d+(?:[.,]\d+)?\s*valores?\s*\)\s*\.?\s*$", re.IGNORECASE)

    def _label_by_cotacao(self, blocks: List[TextBlock]) -> List[TextBlock]:
        """Number the questions of a paper whose numbers the photo did not carry, by their «( N VALORES )» ends.

        On a curved page the numbers of the left margin are the first thing the detector loses: the thorough read of
        the parcelar exam has every stem but only the "4" and the "5-". Such a paper marks the end of each question
        with its cotação, so a line after one starts the next question, unless it is the next alínea (a), b)…).
        It only runs when no question carries a number, which is when the stems would otherwise be lost, and only
        when every question would start on a capital letter: a lowercase start is the tail of a stem whose first
        line was not read, and numbering that would turn fragments into questions.
        """
        ends = [i for i, b in enumerate(blocks) if self._UNIT_END.search(b.text.strip())]
        if len(ends) < 2:
            return blocks

        labeled, last = 0, 0
        for block in blocks:
            info = self._detect_question_number(block.text.strip(), expected=last + 1)
            if info is not None:
                labeled, last = labeled + 1, info[0]
        if labeled >= 1:
            return blocks

        def body_like(block: TextBlock) -> bool:
            return len(block.text.strip()) >= 25 and block.confidence >= self.min_confidence_threshold

        start = ends[0]
        while start > 0 and body_like(blocks[start - 1]):
            start -= 1

        starts = [start]
        for i in range(start, len(blocks) - 1):
            if self._UNIT_END.search(blocks[i].text.strip()):
                following = blocks[i + 1].text.strip()
                if not self._ALINEA_LABEL.match(following) and not re.match(r"^[a-z](?=[A-ZÀ-ÝÇ])", following):
                    starts.append(i + 1)
        if len(starts) < 2 or any(not re.match(r"^[A-ZÀ-ÝÇ0-9]", blocks[i].text.strip()) for i in starts):
            return blocks

        out = list(blocks)
        number = 0
        for i in starts:
            text = blocks[i].text.strip()
            info = self._detect_question_number(text, expected=number + 1)
            if info is not None:
                number = info[0]
            else:
                number += 1
                out[i] = replace(blocks[i], text=f"{number}. {text}")
        return out

    _LEADING_NOISE = re.compile(r"^[.,;:·•\-–—]+\s*(?=[A-ZÀ-ÝÇ][a-zà-ÿç])")
    _ALINEA_LABEL = re.compile(r"^\(?([a-z])\s*[.)]")

    def _repair_lost_labels(self, blocks: List[TextBlock]) -> List[TextBlock]:
        """Give back the label that a photographed line lost: ".Quantos…" is alínea a when b follows, and
        ". Dado o circuito…" is the next question when it is a that follows; a "6." among the alíneas is a b.

        A printed "4." or "a." that the OCR read as a bare dot leaves a line with no number or letter, which
        then glues to the previous text and shifts every number after it. The label that comes next says
        which one was lost: a question stem is followed by its first alínea (a), an alínea by the second (b).
        """
        repaired: List[TextBlock] = []
        last_number = 0
        last_alinea: Optional[str] = None
        for position, block in enumerate(blocks):
            text = block.text.strip()
            following = blocks[position + 1].text.strip() if position + 1 < len(blocks) else ""
            next_label = self._ALINEA_LABEL.match(following)
            next_letter = next_label.group(1) if next_label else None

            if last_number == 0 and re.match(r"^[Il|]\s*[-–.)]\s*\S", text):
                text = "1" + text[1:]  # the first question's 1, read as the capital I it resembles
            elif last_alinea == "a" and re.match(r"^6\s*[.)]\s*\S", text):
                text = "b" + text[1:]  # the printed b, read as the digit it resembles
            elif last_number and self._LEADING_NOISE.match(text):
                rest = self._LEADING_NOISE.sub("", text, count=1)
                if next_letter == "a":
                    text = f"{last_number + 1}. {rest}"
                elif next_letter == "b" and last_alinea is None:
                    text = f"a. {rest}"

            if text != block.text.strip():
                block = replace(block, text=text)
            repaired.append(block)

            info = self._detect_question_number(text, expected=last_number + 1)
            if info is not None:
                last_number = info[0]
                last_alinea = None
                continue
            label = self._ALINEA_LABEL.match(text)
            if label:
                last_alinea = label.group(1)
        return repaired

    _OPTION_MARK = re.compile(r"(?:(?<=\s)|^)\(?([A-D])\)\s*")

    def _split_inline_options(self, text: str, already: int) -> Optional[Tuple[str, List[Tuple[str, str]]]]:
        """Options that share one printed line ("A)14 B)20 C)24 D)9"), with or without a stem before them.

        Returns ``(text before the first option, [(label, text), ...])``. They are options only when the labels
        run in sequence: from A on a fresh question, or from the next letter after the ones already read. A line
        that merely mentions "A)" is left alone.
        """
        start = chr(ord("A") + already)
        marks = list(self._OPTION_MARK.finditer(text))
        first = next((i for i, m in enumerate(marks) if m.group(1) == start), None)
        if first is None:
            return None
        chosen = [marks[first]]
        for mark in marks[first + 1:]:
            if mark.group(1) == chr(ord(chosen[-1].group(1)) + 1):
                chosen.append(mark)
            else:
                break
        if len(chosen) < (1 if already else 2):
            return None
        found: List[Tuple[str, str]] = []
        for index, mark in enumerate(chosen):
            end = chosen[index + 1].start() if index + 1 < len(chosen) else len(text)
            option_text = text[mark.end():end].strip()
            if not option_text:
                return None
            found.append((mark.group(1), option_text))
        return text[:chosen[0].start()].strip(), found

    @staticmethod
    def _append_part(draft: "_Draft", text: str) -> None:
        if draft.alineas:
            draft.alineas[-1].parts.append(text)
        else:
            draft.stem_parts.append(text)

    def _detect_alinea(
        self,
        text: str,
        draft: "_Draft",
        cotacao_map: Dict[str, float],
    ) -> Optional[Tuple[str, str]]:
        """Return ``(letter, remainder)`` when ``text`` opens the next alínea of the current question."""
        question = draft.question
        has_alineas = bool(draft.alineas)
        expected = chr(ord(draft.alineas[-1].label) + 1) if has_alineas else "a"
        stripped = text.strip()

        # "aDeve ser…", "bA escolha…": the label lost its parenthesis and sits glued to the next word.
        glued = re.match(r"^([a-z])(?=[A-ZÀ-ÝÇ])(.+)$", stripped)
        if glued and glued.group(1) == expected and not question.options:
            return glued.group(1), glued.group(2).strip()

        info = self._detect_subitem_with_text(stripped)
        if info is None:
            return None
        letter = info[0][0]
        if has_alineas:
            # The next letter, or one skipped when a line was lost; never an option of a choice question.
            if letter.lower() in (expected, chr(ord(expected) + 1)) and not question.options:
                return letter.lower(), info[1]
            return None
        if letter.lower() not in ("a", "b", "c", "d"):
            return None
        # The first alínea of a question is not always "a": a photo can lose it, and a paper may start at "c)".
        if self._is_subitem_context(stripped, question, cotacao_map, draft.stem_parts, []):
            return letter.lower(), info[1]
        return None

    def _detect_question_number(self, text: str, expected: Optional[int] = None) -> Optional[Tuple[int, str]]:
        """Detect if text starts with a question number. Returns (number, remainder) or None.

        With ``expected`` (the number the sequence is waiting for) the detection also accepts a bare number
        ("1 O que consiste…") and rejects numbers that do not fit the sequence. Without it the old
        punctuation-only behaviour applies.
        """
        text = text.strip()

        # Skip blocks that look like class/grade indicators (e.g., "12ª Classe", "12a Classe")
        class_pattern = r"^\d{1,2}\s*[ºª°a@]?\s*(?:classe|class|ano|grade)"
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

        # "2.500 unidades", "1,8% da demanda": a decimal number is not a question number.
        if re.match(r"^\d+[.,]\d", text):
            return None

        def acceptable(num: int, strong: bool) -> bool:
            if num < 1 or num > 50:
                return False
            if expected is None:
                return True
            if not strong:
                return num == expected
            if expected == 1:
                return num <= 3
            return expected <= num <= expected + 2

        for pattern in self.PATTERNS["question_number"]:
            match = re.match(pattern, text, re.IGNORECASE)
            if match:
                try:
                    num = int(match.group(1))
                except ValueError:
                    continue
                remainder = text[match.end():].strip()
                # Whole words: "classifique" is not the "classe" of a header line.
                if remainder and re.search(
                    r"\b(?:classe|class|ano\s+lectivo|ano\s+letivo|dura[çc][ãa]o)\b", remainder, re.IGNORECASE
                ):
                    continue
                if acceptable(num, strong=True):
                    return (num, remainder)

        # "(1) …", "1º …"
        wrapped = re.match(r"^\((\d{1,2})\)\s*(.*)$", text)
        if wrapped and acceptable(int(wrapped.group(1)), strong=True):
            return (int(wrapped.group(1)), wrapped.group(2).strip())

        # "1 O que consiste…": a bare number is only a question when it is exactly the one expected.
        if expected is not None:
            bare = re.match(r"^(\d{1,2})\s+(?=[A-ZÀ-Ý\"“(])(\S.{5,})$", text)
            if bare and acceptable(int(bare.group(1)), strong=False):
                return (int(bare.group(1)), bare.group(2).strip())

        return None

    # ------------------------------------------------------------------ building a question

    def _build_question(self, draft: "_Draft", cotacao_map: Dict[str, float]) -> ExtractedQuestion:
        question = draft.question
        stem_parts = list(draft.stem_parts)

        # A choice question needs two or more consecutive options starting at A; one stray "C)" is text.
        options = question.options or []
        if options and not self._valid_options(options):
            for option in options:
                stem_parts.append(f"{option.option_label}) {option.option_text}")
            question.options = None
            options = []

        subs = [self._finish_subitem(a) for a in draft.alineas]
        raw_stem = " ".join(stem_parts)

        # "a) 10.1.0.1  b) 192.168.1.100  c) …" under a question: the alíneas are the answers to choose from
        if not options and self._choice_alineas(raw_stem, subs):
            options = [
                ExtractedOption(
                    option_label=chr(ord("A") + i),
                    # a lone "X" after an option is the mark of the answer the teacher ticked, not part of it
                    option_text=re.sub(r"\s+[xX×]$", "", sub.text).strip(),
                    confidence=question.text_confidence,
                )
                for i, sub in enumerate(subs)
            ]
            question.options = options
            draft.alineas = []
            subs = []

        if len(subs) >= 2 and not options:
            stem_cotacao = self._extract_inline_cotacao(raw_stem)
            if stem_cotacao is not None:
                raw_stem = self._remove_inline_cotacao(raw_stem)
                if question.cotacao is None:
                    question.cotacao, question.cotacao_confidence = stem_cotacao, 0.8
            stem = self._clean_footer_garbage(self._clean_text(raw_stem))
            if self._is_true_false(stem, subs):
                question.question_type = QuestionType.VERDADEIRO_FALSO
                question.question_type_confidence = 0.92
                for sub in subs:
                    sub.type = "verdadeiro_falso"
                    sub.text = self._clean_true_false_statement(sub.text)
            else:
                question.question_type = QuestionType.COMPOSTA
                question.question_type_confidence = 0.9
                for sub in subs:
                    sub.type = self._infer_subitem_type(sub.text)
            question.text = stem
        else:
            parts = list(stem_parts)
            for alinea in draft.alineas:
                parts.append(f"{alinea.label}) " + " ".join(alinea.parts))
            raw = " ".join(parts)
            inline = self._extract_inline_cotacao(raw)
            if inline is not None:
                raw = self._remove_inline_cotacao(raw)
            question.text = self._clean_text(raw)
            if inline is not None and question.cotacao is None:
                question.cotacao = inline
                question.cotacao_confidence = 0.8
            if len(subs) == 1:
                for sub in subs:
                    sub.type = self._infer_subitem_type(sub.text)

        question.subitems = [f"{sub.label})" for sub in subs]
        question.subitems_content = subs
        self._finalize_question(question, cotacao_map)
        return question

    _IMPERATIVE_START = re.compile(
        r"^(?:calcul|demonstr|determin|explic|diga|dig|apresent|represent|encontr|indiqu|justific|escrev|defin|resolv|analis|descrev|identific|esquematiz|compar|classific|enumer|cite|prov|mostr|obtenh|esboc|trac)",
        re.IGNORECASE,
    )

    def _choice_alineas(self, stem: str, subs: List[SubitemContent]) -> bool:
        """Whether the alíneas of a question are the options of a choice question rather than sub-questions.

        Options answer a question asked in the stem ("…qual seria o ID?"), are short statements or values, carry
        no score of their own and do not start with an instruction ("Calcule…", "Demonstre…") or ask something
        themselves. A true/false stem or ticks to fill ("( )") keep them as true/false alíneas.
        """
        if not 2 <= len(subs) <= 8 or "?" not in stem:
            return False
        if self._TRUE_FALSE_STEM.search(stem) or sum(1 for sub in subs if self._TRUE_FALSE_TAIL.search(sub.text)) >= 2:
            return False
        for sub in subs:
            text = sub.text.strip()
            if not text or sub.cotacao is not None or text.endswith("?") or len(text.split()) > 12:
                return False
            if self._IMPERATIVE_START.match(text):
                return False
        return True

    @staticmethod
    def _valid_options(options: List[ExtractedOption]) -> bool:
        labels = [o.option_label.upper() for o in options]
        return len(labels) >= 2 and labels == [chr(ord("A") + i) for i in range(len(labels))]

    def _finish_subitem(self, alinea: "_Alinea") -> SubitemContent:
        raw = " ".join(alinea.parts)
        cotacao = self._extract_inline_cotacao(raw)
        if cotacao is not None:
            # Before the general cleaning: that one would strip "2 valores" and leave an empty "()" behind.
            raw = self._remove_inline_cotacao(raw)
        text = self._clean_text(raw)
        return SubitemContent(label=alinea.label, text=self._clean_footer_garbage(text), cotacao=cotacao)

    def _is_true_false(self, stem: str, subs: List[SubitemContent]) -> bool:
        """V/F when the statement says so, or when most alíneas end in an empty "( )" to be ticked."""
        if self._TRUE_FALSE_STEM.search(stem):
            return True
        ticks = sum(1 for sub in subs if self._TRUE_FALSE_TAIL.search(sub.text))
        return ticks >= max(2, math.ceil(len(subs) / 2))

    @staticmethod
    def _clean_true_false_statement(text: str) -> str:
        """A V/F statement without the empty "( )" (or the lone ")" the OCR leaves) at its end."""
        original = text.strip()
        ends_with_period = original.rstrip().endswith(".")
        cleaned = re.sub(r"\s*\(\s*[VvFf]?\s*\)\s*[.;]*\s*$", "", original)
        if cleaned == original and original.count(")") > original.count("("):
            cleaned = re.sub(r"\s*\)\s*[.;]*\s*$", "", original)
        cleaned = cleaned.rstrip(" .;")
        if ends_with_period or cleaned != original.rstrip(" .;"):
            cleaned += "."
        return cleaned.strip()

    def _infer_subitem_type(self, text: str) -> str:
        if self._TRUE_FALSE_TAIL.search(text):
            return "verdadeiro_falso"
        if self._CHOICE_INSTRUCTION.search(text):
            return "multipla_escolha"
        return "dissertativa"


    def _finalize_question(self, question: ExtractedQuestion, cotacao_map: Dict[str, float]):
        """Finalize question with type inference and cotação lookup."""
        # Infer question type (composta / verdadeiro_falso are decided when the question is built)
        if question.question_type == QuestionType.UNKNOWN:
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
            r"(?<=[?.:])\s*(\d+(?:[.,]\d+)?)\s*[Vv](?![\w.])",  # "…Branch1? 5V": the cotação after the sentence
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
            r"(?<=[?.:])\s*\d+(?:[.,]\d+)?\s*[Vv](?![\w.])",  # "…Branch1? 5V"
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
            r"BAIRRO\s+DOS\s+CTT.*$",
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
        layout_regions: Optional[Dict[int, List[LayoutRegion]]] = None,
        formula_images: bool = False,
        page_limits: Optional[Dict[int, int]] = None,
    ) -> List[ImageToUpload]:
        """Detect regions that should be uploaded as images.

        ``layout_regions`` (page -> regions) comes from the layout model: the figures, tables and formulas that
        are tied to the question they sit under. ``None`` means no layout was run (disabled, or it failed): then
        a question that mentions a figure falls back to a crop of its whole text span.
        """
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

        # Figures, tables and formulas, each tied to the question it sits under
        if layout_regions is not None:
            images.extend(self._question_figures(
                questions, blocks, layout_regions, dimensions, source_file_index, base_name, formula_images,
                page_limits or {},
            ))
        else:
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

    # Figures smaller than this share of the page are marks, stamps or a circled number, not drawings.
    _MIN_FIGURE_AREA = 0.02
    _MIN_FORMULA_WIDTH = 0.08
    _FIGURE_LABELS = {
        "figure": "Figura da questão {n}",
        "table": "Tabela da questão {n}",
        "formula": "Fórmula da questão {n}",
    }

    @staticmethod
    def _overlap(a: Tuple[int, int, int, int], b: Tuple[int, int, int, int]) -> int:
        width = min(a[2], b[2]) - max(a[0], b[0])
        height = min(a[3], b[3]) - max(a[1], b[1])
        return max(0, width) * max(0, height)

    def _coordination_y(self, blocks: List[TextBlock], page_index: int) -> Optional[int]:
        """Top of the «A coordenação» signature line of the page, if there is one."""
        for block in blocks:
            if block.page_index != page_index:
                continue
            text = block.text.lower()
            if any(re.search(pattern, text) for pattern in self.PATTERNS["coordination"]):
                return block.bbox[1]
        return None

    def _standalone_formula(self, region: LayoutRegion, blocks: List[TextBlock], page_width: Optional[int]) -> bool:
        """A display formula: wide enough, and no other text on its line (an inline one is part of the sentence)."""
        x1, y1, x2, y2 = region.bbox
        if page_width and (x2 - x1) < self._MIN_FORMULA_WIDTH * page_width:
            return False
        height = max(1, y2 - y1)
        for block in blocks:
            overlap_y = min(y2, block.bbox[3]) - max(y1, block.bbox[1])
            if overlap_y < 0.5 * height:
                continue
            outside = (block.bbox[2] - block.bbox[0]) - max(0, min(x2, block.bbox[2]) - max(x1, block.bbox[0]))
            if outside > 40:
                return False
        return True

    def _question_figures(
        self,
        questions: List[ExtractedQuestion],
        blocks: List[TextBlock],
        layout_regions: Dict[int, List[LayoutRegion]],
        dimensions,
        source_file_index,
        base_name: str,
        formula_images: bool = False,
        page_limits: Optional[Dict[int, int]] = None,
    ) -> List[ImageToUpload]:
        """Tie each figure, table and standalone formula of the layout to the question it sits under."""
        if not questions:
            return []
        ordered = sorted(questions, key=lambda q: (q.page_index, q.start_y))
        found: List[ImageToUpload] = []
        per_question: Dict[str, int] = {}

        for page_index in sorted(layout_regions):
            width, height = dimensions(page_index)
            page_area = (width * height) if width and height else 0
            coordination = self._coordination_y(blocks, page_index)
            page_blocks = [b for b in blocks if b.page_index == page_index]
            kept: List[LayoutRegion] = []

            for region in sorted(layout_regions[page_index], key=lambda r: (r.bbox[1], r.bbox[0])):
                x1, y1, x2, y2 = region.bbox
                area = region.area
                if region.kind in ("figure", "table") and page_area and area < self._MIN_FIGURE_AREA * page_area:
                    continue
                if coordination is not None and y1 >= coordination - 30:
                    continue  # the signature and the logos of the footer
                if (page_limits or {}).get(page_index) is not None and y1 >= page_limits[page_index]:
                    continue  # below the cotação line: the stamp, the signature, scribbles in the free space
                inside = sum(self._overlap(b.bbox, region.bbox) for b in page_blocks)
                if region.kind == "figure" and area and inside > 0.5 * area:
                    continue  # mostly text: the layout model took a paragraph for a picture
                if region.kind == "formula" and (not formula_images or region.handwritten or region.score < 0.5):
                    continue  # formulas are cropped on request, and never the ones in a student's pen
                if any(
                    self._overlap(region.bbox, other.bbox) > 0.5 * min(area, other.area)
                    for other in kept
                    if other.kind == region.kind
                ):
                    continue  # the same drawing found twice
                kept.append(region)

            for region in kept:
                center = (region.bbox[1] + region.bbox[3]) / 2
                owner = None
                for question in ordered:
                    if (question.page_index, question.start_y) <= (page_index, center):
                        owner = question
                if owner is None:
                    continue  # above the first question: the header
                number = owner.number
                count = per_question.get(number, 0) + 1
                per_question[number] = count
                owner.has_image = True
                padding = 8
                bbox = (
                    max(0, region.bbox[0] - padding),
                    max(0, region.bbox[1] - padding),
                    min(width, region.bbox[2] + padding) if width else region.bbox[2] + padding,
                    min(height, region.bbox[3] + padding) if height else region.bbox[3] + padding,
                )
                description = self._FIGURE_LABELS[region.kind].format(n=number)
                if region.kind == "figure" and owner.image_description and len(owner.image_description.split()) > 2:
                    description = owner.image_description
                found.append(ImageToUpload(
                    suggested_filename=f"{base_name}-questao-{number}-{region.kind}-{count}.png",
                    description=description,
                    region=f"questao_{number}",
                    bbox=bbox,
                    page_index=page_index,
                    source_width=width,
                    source_height=height,
                    source_file_index=source_file_index(page_index),
                    kind=region.kind,
                    score=round(region.score, 3),
                ))

        return found

    def _infer_question_type(self, question: ExtractedQuestion) -> Tuple[QuestionType, float]:
        """Infer question type based on text and options."""
        text_lower = question.text.lower()

        # If has options, it's multiple choice
        if question.options and len(question.options) > 0:
            return QuestionType.MULTIPLA_ESCOLHA, 0.95

        # "Assinale a alternativa correta" asks for a choice even before the options are read. The bare word
        # "escolha" does not: "A escolha das matérias…" is a noun in an open question.
        if self._CHOICE_INSTRUCTION.search(question.text):
            return QuestionType.MULTIPLA_ESCOLHA, 0.8

        for keyword in self.QUESTION_TYPE_KEYWORDS[QuestionType.DISSERTATIVA]:
            if keyword in text_lower:
                return QuestionType.DISSERTATIVA, 0.8

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
        noise: Optional[List[Tuple[TextBlock, str]]] = None,
    ) -> List[UnmappedContent]:
        """Content the structure could not place: handwriting/margin noise and the text between the
        instructions and the first question (a quoted introduction, for instance)."""
        unmapped: List[UnmappedContent] = []
        for block, reason in (noise or []):
            unmapped.append(UnmappedContent(block.page_index, block.text.strip(), block.confidence, reason))

        if not questions:
            # Nothing was structured. Keep what the OCR read in the response so the caller still
            # has the text (a table of values, an unusual layout) instead of losing it behind an
            # error status. Noise left `blocks` before this call, so a block is not listed twice;
            # skipping any already collected keeps that true even if a caller ever passes both.
            already = {id(block) for block, _ in (noise or [])}
            for block in blocks:
                if id(block) in already:
                    continue
                if block.text and block.text.strip():
                    unmapped.append(UnmappedContent(
                        block.page_index, block.text.strip(), block.confidence,
                        "Texto lido sem perguntas reconhecidas",
                    ))
            return unmapped

        instruction_end = metadata.instruction_end
        if instruction_end is not None and questions:
            first = min(questions, key=lambda q: (q.page_index, q.start_y))
            for block in blocks:
                position = (block.page_index, block.bbox[1])
                if instruction_end < position < (first.page_index, first.start_y):
                    unmapped.append(UnmappedContent(
                        block.page_index, block.text.strip(), block.confidence,
                        "Texto introdutório antes da primeira questão",
                    ))
        return unmapped

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

    def _generate_cotacao_warnings(
        self,
        metadata: ExtractedMetadata,
        questions: List[ExtractedQuestion],
    ) -> List[Warning]:
        """A paper with no scores at all: nothing to infer, someone has to distribute the total."""
        if questions and metadata.total_max_score.value is None and all(q.cotacao is None for q in questions):
            return [Warning(
                code="NO_COTACAO",
                field="questions",
                confidence=1.0,
                message=f"{len(questions)} questões sem cotação: a pontuação tem de ser definida na revisão.",
            )]
        return []

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

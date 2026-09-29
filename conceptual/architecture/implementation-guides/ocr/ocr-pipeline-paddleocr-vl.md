Aqui está a tradução completa para inglês, mantendo fidelidade técnica e consistência com os nomes das entidades da tua BD:

---

# Full Implementation Guide

**OCR Pipeline with PaddleOCR-VL**
**Status:** Proposed for Review and Acceptance via ADR

This document consolidates the implementation of the OCR microservice as an isolated, stateless component, responsible for structured extraction of exam statements from images. The JSON output structure is designed to mirror the main fields of the `statements` and `questions` entities from the ERM model, without strong coupling: free-text values, granular confidence scores, and no domain validation (ID lookups, subject existence, etc. are handled exclusively in the backend).

The service **never** accesses the database, persists data, or enforces business rules. It functions solely as an image → structured JSON transformer.

## 0. Flow

```markdown
UI (Web/Mobile)

   ↓ multipart/form-data (images + context)

Backend API (Spring Boot WebFlux) → POST /api/statements/upload-ocr

   ↓ HTTP POST (Reactive WebClient + Resilience4j)

OCR Service (Python FastAPI + PaddleOCR-VL) → POST /ocr/v1/extract

   ↓ structured JSON (contract below)

Backend API (StatementService)

   ↓ mapping + domain lookup/validation

   ↓ reactive persistence (R2DBC)

   ↓ asynchronous event: StatementCreatedEvent → Kafka topic "statements.created"

AI Service (consumes event and indexes for RAG)
```

## 1. Goals and Architectural Principles

* **Strict separation**: OCR extracts; backend maps/validates/persists.
* **Clear contract**: Hierarchical JSON defined via schema in `libs/contracts/ocr-extract-response.json`.
* **Scalability**: Supports high concurrency via async + multiprocessing/GPU.
* **Observability**: Prometheus metrics, Jaeger tracing, structured logs.
* **Idempotency**: Image hash enables result caching.

## 2. OCR Microservice Internal Architecture

The service is built with **FastAPI** (Python 3.11+), **PaddleOCR-VL** (PP-OCRv4 models + layout analysis), and **OpenCV** for pre-processing.

### Layers

* **API Layer** (`app/api/endpoints.py`):
  Single endpoint: `POST /ocr/extract` (multipart/form-data).
  Validation: Pydantic + JWT middleware.
  Delegates to orchestration layer.

* **Orchestration** (`app/ocr/engine.py`):
  Pre-loads models at startup.
  Processes images in parallel (asyncio + multiprocessing).
  Pre-processing: deskew, binarization, resize.
  Runs PaddleOCR-VL (detection + recognition + layout parsing).
  Post-processing: filters by confidence, infers question type, reconstructs hierarchy.

* **Parsing** (`app/ocr/postprocessing.py`):
  Extracts metadata via regex + layout (header/footer).
  Segments questions by numbering and visual blocks.
  Infers `questionType` (multiple_choice, short_answer, development, true_false).
  Extracts options when table or list is detected.

## 3. HTTP Contract

**Endpoint:** `POST /ocr/extract`
**Headers:**

* `Authorization: Bearer <JWT>`

**Body (multipart/form-data):**

* `images[]`: image files (JPEG/PNG, multiple allowed)
* `context` (optional): JSON string (e.g., `{"languageHint": "pt"}`)

**Responses:**

* 200 OK: structured JSON (see below)
* 400/422: validation error
* 500: internal failure (with retry in backend via Resilience4j)

## 4. JSON Response Structure

Defined in `libs/contracts/ocr-extract-response.json` (JSON Schema).

### General Structure

```json
{
  "status": "success" | "partial" | "error",
  "requestId": "string",
  "processingTimeMs": number,
  "overallConfidence": number (0.0–1.0),
  "document": {
    "pageCount": number,
    "mainLanguage": "pt" | "en" | ...,
    "hasTables": boolean
  },
  "metadata": { ... fields mirroring statements ... },
  "questions": [ ... array mirroring questions + questionOptions ... ],
  "unmappedContent": [ ... residual text ... ],
  "warnings": [ ... alerts ... ]
}
```

### Full Example (Mathematics Exam – 3 pages)

```json
{
  "status": "success",
  "requestId": "req-20260124-0945-abc123",
  "processingTimeMs": 4870,
  "overallConfidence": 0.892,
  "document": {
    "pageCount": 3,
    "mainLanguage": "pt",
    "hasTables": true
  },
  "metadata": {
    "schoolYear": { "value": "2024/2025", "confidence": 0.97 },
    "term": { "value": "2º Term", "confidence": 0.94 },
    "subject": { "value": "Mathematics A", "confidence": 0.93 },
    "course": { "value": null, "confidence": 0.0 },
    "class": { "value": "12th Grade - Class A", "confidence": 0.89 },
    "examType": { "value": "Periodic Assessment", "confidence": 0.91 },
    "durationMinutes": { "value": 120, "confidence": 0.88 },
    "variant": { "value": "A", "confidence": 0.96 },
    "title": { "value": "Summative Assessment - 2nd Term", "confidence": 0.90 },
    "instructions": { "value": "Read carefully. Answer in the designated space.", "confidence": 0.85 }
  },
  "questions": [
    {
      "number": 1,
      "confidence": 0.935,
      "text": { "value": "Solve the equation: 3x - 7 = 14", "confidence": 0.96 },
      "questionType": { "value": "short_answer", "confidence": 0.89 },
      "maxScore": { "value": 5, "confidence": 0.92 },
      "options": [],
      "pageIndex": 0,
      "startY": 280,
      "endY": 420
    },
    {
      "number": 2,
      "confidence": 0.918,
      "text": { "value": "Which of the following options represents the square root of 64?", "confidence": 0.95 },
      "questionType": { "value": "multiple_choice", "confidence": 0.94 },
      "maxScore": { "value": 4, "confidence": 0.90 },
      "options": [
        { "optionLabel": "A", "optionText": "6", "confidence": 0.97 },
        { "optionLabel": "B", "optionText": "8", "confidence": 0.96 },
        { "optionLabel": "C", "optionText": "7", "confidence": 0.94 },
        { "optionLabel": "D", "optionText": "9", "confidence": 0.95 }
      ],
      "pageIndex": 1,
      "startY": 120,
      "endY": 380
    },
    {
      "number": 3,
      "confidence": 0.862,
      "text": { "value": "Justify why triangle ABC is congruent to triangle DEF.", "confidence": 0.89 },
      "questionType": { "value": "development", "confidence": 0.91 },
      "maxScore": { "value": 10, "confidence": 0.87 },
      "options": [],
      "pageIndex": 2,
      "startY": 90,
      "endY": 520
    }
  ],
  "unmappedContent": [
    { "pageIndex": 2, "text": "Preliminary answer key (internal use)", "confidence": 0.94 }
  ],
  "warnings": [
    { "code": "LOW_CONFIDENCE", "field": "class", "confidence": 0.76 }
  ]
}
```

## 5. Backend Mapping (Spring Boot WebFlux)

In `StatementService`:

1. **Receives JSON** via WebClient (reactive).
2. **Confidence validation** (e.g., threshold 0.80 per field).
3. **Conditional lookup/creation**:

   ```java
   // Simplified example
   SchoolYear sy = schoolYearRepository.findByYears(meta.schoolYear.value)
       .orElseGet(() -> schoolYearService.createFromString(meta.schoolYear.value));
   ```
4. **Builds Statement**:

   ```java
   Statement stmt = new Statement();
   stmt.setExamType(meta.examType.value);
   stmt.setDurationMinutes(meta.durationMinutes.value);
   stmt.setVariant(meta.variant.value);
   stmt.setTitle(meta.title.value);
   stmt.setInstructions(meta.instructions.value);
   stmt.setSchoolYear(sy);
   stmt.setTerm(termLookup(meta.term.value));
   stmt.setSubject(subjectLookup(meta.subject.value));
   stmt.setClass(classLookup(meta.class.value));
   ```
5. **Builds Questions + Options**:

   ```java
   for (var q : ocrResponse.questions) {
       Question question = new Question();
       question.setNumber(q.number);
       question.setText(q.text.value);
       question.setQuestionType(q.questionType.value);
       question.setMaxScore(q.maxScore.value);
       question.setOrderIndex(q.number);

       for (var opt : q.options) {
           QuestionOption option = new QuestionOption();
           option.setOptionLabel(opt.optionLabel);
           option.setOptionText(opt.optionText);
           question.addOption(option);
       }

       stmt.addQuestion(question);
   }
   ```
6. **Persists** via reactive repository.
7. **Marks for review** if confidence is low (`needsReview` field).

## 6. Scalability and Operationalization

* **Pre-load models** at startup (reduces cold latency).
* **GPU** for PaddleOCR-VL (deploy on dedicated nodes).
* **Queue** (Celery + Redis) for peak loads.
* **Metrics**: page latency, average confidence, warning rate.
* **Circuit Breaker**: Resilience4j in backend.
* **Cache**: Redis with key = image hash.

## 7. Next Steps

* Implement JSON Schema validation in backend.
* Add support for LaTeX formulas (via math block detection).
* End-to-end testing: upload → OCR → persistence.
* Complementary ADR: decision on queue vs synchronous processing.

**Pending Approval:**
[ ] Tech Lead
[ ] Architecture

Last update: January 2026

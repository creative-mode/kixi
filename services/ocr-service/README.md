# Kixi OCR Service

A high-performance OCR (Optical Character Recognition) microservice built with FastAPI and PaddleOCR-VL for extracting structured text from exam images and PDFs.

## Overview

This service is part of the Kixi platform and is responsible for:

- Extracting text from exam paper images
- Detecting and structuring questions, options, and metadata
- Processing multi-page PDF documents
- Providing confidence scores for all extracted data
- Supporting Portuguese and other languages

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      OCR Service                            │
├─────────────────────────────────────────────────────────────┤
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │   FastAPI   │──│  OCR Engine │──│  PaddleOCR-VL       │ │
│  │   Routes    │  │  (engine.py)│  │  (PP-OCRv4 models)  │ │
│  └─────────────┘  └─────────────┘  └─────────────────────┘ │
│         │                │                                  │
│         │         ┌──────┴──────┐                          │
│         │         │             │                          │
│  ┌──────┴──────┐  │  ┌──────────┴───────┐                 │
│  │ PDF Handler │  │  │ Image Preprocessor│                 │
│  │             │  │  │ (OpenCV)          │                 │
│  └─────────────┘  │  └──────────────────┘                 │
│                   │                                        │
│            ┌──────┴──────┐                                 │
│            │Postprocessor│                                 │
│            │ (Regex + ML)│                                 │
│            └─────────────┘                                 │
└─────────────────────────────────────────────────────────────┘
```

## Features

- **PaddleOCR-VL Integration**: Uses PP-OCRv4 models for high accuracy
- **Multi-language Support**: Optimized for Portuguese, with support for 80+ languages
- **PDF Processing**: Extract text from multi-page PDF documents
- **Image Preprocessing**: Automatic deskewing, noise reduction, and contrast enhancement
- **Structured Output**: Extracts metadata, questions, options with confidence scores
- **Question Type Detection**: Identifies multiple choice, short answer, development, and true/false questions
- **RESTful API**: Clean FastAPI-based HTTP interface
- **Health Checks**: Built-in health check endpoints for container orchestration
- **Prometheus Metrics**: Optional metrics endpoint for monitoring

## API Endpoints

### Core Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/ocr/v1/extract` | Extract text from one or more images/PDFs |
| POST | `/ocr/v1/extract/simple` | Simple single-image extraction |
| GET | `/ocr/health` | Detailed health check |
| GET | `/health` | Simple health check |
| GET | `/ocr/v1/supported-languages` | List supported OCR languages |

### Request Format

**POST /ocr/v1/extract**

```bash
curl -X POST http://localhost:8000/ocr/v1/extract \
  -H "Content-Type: multipart/form-data" \
  -F "images[]=@exam_page1.jpg" \
  -F "images[]=@exam_page2.jpg" \
  -F 'context={"languageHint": "pt"}'
```

### Response Format

```json
{
  "status": "success",
  "requestId": "req-abc123def456",
  "processingTimeMs": 4870,
  "overallConfidence": 0.892,
  "document": {
    "pageCount": 2,
    "mainLanguage": "pt",
    "hasTables": true
  },
  "metadata": {
    "schoolYear": { "value": "2024/2025", "confidence": 0.97 },
    "term": { "value": "2º Trimestre", "confidence": 0.94 },
    "subject": { "value": "Matemática", "confidence": 0.93 },
    "examType": { "value": "Avaliação Periódica", "confidence": 0.91 },
    "durationMinutes": { "value": 120, "confidence": 0.88 },
    "variant": { "value": "A", "confidence": 0.96 }
  },
  "questions": [
    {
      "number": 1,
      "confidence": 0.935,
      "text": { "value": "Resolva a equação: 3x - 7 = 14", "confidence": 0.96 },
      "questionType": { "value": "short_answer", "confidence": 0.89 },
      "maxScore": { "value": 5, "confidence": 0.92 },
      "options": [],
      "pageIndex": 0
    },
    {
      "number": 2,
      "confidence": 0.918,
      "text": { "value": "Qual das opções representa a raiz quadrada de 64?", "confidence": 0.95 },
      "questionType": { "value": "multiple_choice", "confidence": 0.94 },
      "options": [
        { "optionLabel": "A", "optionText": "6", "confidence": 0.97 },
        { "optionLabel": "B", "optionText": "8", "confidence": 0.96 },
        { "optionLabel": "C", "optionText": "7", "confidence": 0.94 },
        { "optionLabel": "D", "optionText": "9", "confidence": 0.95 }
      ],
      "pageIndex": 0
    }
  ],
  "warnings": [
    { "code": "LOW_CONFIDENCE", "field": "class", "confidence": 0.76 }
  ]
}
```

## Quick Start

### Prerequisites

- Python 3.11+
- Docker (optional, recommended)

### Local Development

1. **Create virtual environment:**

```bash
cd services/ocr-service
python -m venv venv
source venv/bin/activate  # Linux/macOS
# or: venv\Scripts\activate  # Windows
```

2. **Install dependencies:**

```bash
pip install -r requirements.txt
```

3. **Configure environment:**

```bash
cp env.example .env
# Edit .env as needed
```

4. **Run the service:**

```bash
python -m app.main
# or
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

5. **Access API documentation:**

Open http://localhost:8000/docs (available in debug mode)

### Docker

**Build and run:**

```bash
# Build image
docker build -t kixi-ocr-service .

# Run container
docker run -d \
  --name ocr-service \
  -p 8000:8000 \
  -e DEBUG=true \
  -e OCR_LANG=pt \
  kixi-ocr-service
```

**Using docker-compose (from project root):**

```bash
docker-compose up --build ocr-service
```

## Configuration

All configuration is done through environment variables. See `env.example` for the complete list.

### Key Configuration Options

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | 8000 | Server port |
| `OCR_LANG` | pt | Primary OCR language |
| `OCR_USE_GPU` | false | Enable GPU acceleration |
| `OCR_USE_ANGLE_CLS` | true | Detect rotated text |
| `MAX_IMAGE_SIZE_MB` | 20.0 | Maximum upload size |
| `MAX_IMAGES_PER_REQUEST` | 10 | Maximum images per request |
| `MIN_CONFIDENCE_THRESHOLD` | 0.5 | Minimum confidence threshold |
| `ENABLE_DESKEW` | true | Auto-correct image rotation |
| `ENABLE_DENOISE` | true | Apply noise reduction |
| `DEBUG` | false | Enable debug mode |

## Project Structure

```
ocr-service/
├── app/
│   ├── __init__.py
│   ├── main.py              # FastAPI application entry point
│   ├── api/
│   │   ├── __init__.py
│   │   ├── routes.py        # API endpoints
│   │   └── pdf_handler.py   # PDF processing utilities
│   ├── config/
│   │   ├── __init__.py
│   │   └── settings.py      # Configuration management
│   └── ocr/
│       ├── __init__.py
│       ├── engine.py        # PaddleOCR engine wrapper
│       ├── preprocessing.py # Image preprocessing
│       └── postprocessing.py# OCR result parsing
├── tests/
│   ├── fixtures/            # Representative exam OCR fixtures
│   └── test_engine.py       # Unit tests
├── Dockerfile
├── requirements.txt
├── env.example
└── README.md
```

## Testing

### Run Tests

```bash
# Run all tests
pytest

# Run with coverage
pytest --cov=app --cov-report=html

# Run specific test file
pytest tests/test_engine.py -v
```

### Manual Testing

```bash
# Test with a local exam image
curl -X POST http://localhost:8000/ocr/v1/extract/simple \
  -F "image=@/path/to/exam.png"
```

## Performance Considerations

### Optimization Tips

1. **GPU Acceleration**: Set `OCR_USE_GPU=true` for 5-10x faster processing (requires CUDA)
2. **Pre-warming**: The service pre-loads models on startup in production mode
3. **Image Size**: Optimal input resolution is 300 DPI
4. **Batch Processing**: Use the multi-image endpoint for multi-page documents

### Resource Requirements

| Mode | RAM | CPU | GPU (optional) |
|------|-----|-----|----------------|
| Minimum | 2GB | 2 cores | - |
| Recommended | 4GB | 4 cores | NVIDIA (CUDA 11+) |
| Production | 8GB+ | 4+ cores | NVIDIA T4 or better |

## Integration with Backend API

The OCR service is called by the Spring Boot backend API:

```java
// Example WebClient call from backend-api
WebClient webClient = WebClient.create("http://ocr-service:8000");

Mono<OCRResponse> response = webClient.post()
    .uri("/ocr/v1/extract")
    .contentType(MediaType.MULTIPART_FORM_DATA)
    .body(BodyInserters.fromMultipartData("images", imageResource))
    .retrieve()
    .bodyToMono(OCRResponse.class);
```

## Troubleshooting

### Common Issues

**Model Download Slow/Failing:**
```bash
# Pre-download models manually
python -c "from paddleocr import PaddleOCR; PaddleOCR(lang='pt')"
```

**Out of Memory:**
- Reduce `MAX_IMAGE_SIZE_MB`
- Process fewer images per request
- Consider GPU acceleration

**Low Accuracy:**
- Ensure input images are at least 300 DPI
- Enable preprocessing (`ENABLE_DESKEW=true`, `ENABLE_DENOISE=true`)
- Check if the correct language is configured

**PDF Processing Fails:**
- Install poppler-utils: `apt-get install poppler-utils`
- Ensure PyMuPDF is installed: `pip install PyMuPDF`

## Contributing

See the main project's [CONTRIBUTING.md](../../CONTRIBUTING.md) for guidelines.

## License

This service is part of the Kixi platform and is licensed under Apache License 2.0 with Commons Clause. See [LICENSE](../../LICENSE) for details.

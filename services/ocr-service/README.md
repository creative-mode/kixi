# Kixi OCR Service

A high-performance OCR (Optical Character Recognition) microservice built with FastAPI and PaddleOCR 3.x for extracting structured text from exam images and PDFs.

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
│  │   FastAPI   │──│  OCR Engine │──│  PaddleOCR 3.x      │ │
│  │   Routes    │  │  (engine.py)│  │  PP-OCRv5 + layout  │ │
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

- **PaddleOCR 3.x on CPU**: PP-OCRv5 text lines (`latin_PP-OCRv5_mobile_rec` reads Portuguese) with oneDNN, 4 to 16 s per photo, models baked into the image
- **Figures tied to questions**: PP-DocLayout finds the figures, circuits and tables; each is assigned to the question it sits under (`imagesToUpload`: `region` `questao_<n>`, `kind`, `bbox`)
- **PaddleOCR-VL (opt-in)**: `OCR_PIPELINE=vl` parses the page with the 0.9B document model; it needs a GPU (it did not finish one page in 10 minutes on CPU and ran out of 6 GB)
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

### Ficheiros aceites

JPEG, PNG, WebP, TIFF, BMP e PDF. O que decide o tipo é **o conteúdo**, não o nome: a extensão serve
de pista quando o conteúdo não é reconhecido por nenhum destes formatos. Assim uma fotografia de
telemóvel chamada `IMG_20250116`, sem extensão nenhuma, é aceite como o JPEG que os bytes dizem que
é; e um ficheiro cujo conteúdo não é nenhuma destas imagens nem um PDF continua a ser recusado com
`400 Unsupported file type`.

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

### Tipos de questão e alíneas

O exemplo acima é resumido; o formato real de cada questão em `questions[]` é este (campo `type`):

| `type` | O que é | Como vem |
|---|---|---|
| `dissertativa` | resposta escrita | `text` com a pergunta completa |
| `multipla_escolha` | opções A/B/C/D (duas ou mais, seguidas, começando em A) | `text` só com a pergunta, `options[]` |
| `verdadeiro_falso` | enunciado comum e alíneas a assinalar com V ou F | `text` só com o enunciado, `subitemsContent[]` com `type: "verdadeiro_falso"` e a afirmação **sem** o `( )` final |
| `composta` | enunciado comum e alíneas que têm cada uma a sua resposta | `text` só com o enunciado, `subitemsContent[]` com o `type` de cada alínea |

```json
{
  "number": "3",
  "type": "verdadeiro_falso",
  "text": { "value": "Para cada uma das questões abaixo, diga quais são verdadeiras (assinalar com V) ou falsas (assinalar com F):", "confidence": 0.93 },
  "subitems": ["a)", "b)"],
  "subitemsContent": [
    { "label": "a", "text": "Deve ser preocupação da organização comprar ao melhor preço possível.", "cotacao": null, "type": "verdadeiro_falso" },
    { "label": "b", "text": "A escolha das matérias só deve ser feita após a determinação das aplicações.", "cotacao": null, "type": "verdadeiro_falso" }
  ],
  "cotacao": null,
  "options": null
}
```

- `subitems` traz só os rótulos no formato antigo (`"a)"`); em `subitemsContent` o `label` é `"a"` (minúscula, sem parêntese)
  e `type` pode ser `dissertativa`, `verdadeiro_falso` ou `multipla_escolha`.
- **A numeração e as alíneas leem-se por sequência**: a próxima questão é o último número + 1 e a próxima alínea é a
  letra seguinte. Por isso `1 O que consiste…` (sem `)`) e `aDeve…` (alínea colada) contam, e `2.500 unidades` ou
  `90 mil` no meio de uma frase não.
- Uma prova **sem cotações** devolve `cotacao: null`, `totalMaxScore: null` e o aviso
  `{ "code": "NO_COTACAO", "field": "questions", "message": "6 questões sem cotação: …" }`. O serviço não inventa notas.
- `unmappedContent[]` traz o que não coube na estrutura, cada item com um `reason`: manuscrito e marcas nas margens
  (acima do texto, ao lado de uma linha impressa, leitura de confiança muito baixa) e o texto introdutório entre as
  instruções e a questão 1.
- `metadata.courseName` vale `"TODOS"` quando a prova diz `CURSO: TODOS`; a sigla da disciplina (`OGI`) mantém-se.

### Reparação do texto

O motor lê bem as letras mas tropeça no português: perde acentos (`organizacao`), troca letras parecidas
(`cmpresa`, `quc`), cola palavras (`PROVADEEXAMEDEOGI`, `segurancaéde`) e números a palavras (`4mil`, `Duracao9o`).
`app/ocr/ptlex.py` repara isto com um léxico de 36 mil palavras (`app/ocr/data/`, origem e licença no README ao lado)
e regras prudentes: palavra que já está no léxico não se altera, só se corrige o que tem uma única correção clara
(acentos, ou uma troca típica de OCR), e números, siglas, códigos e nomes próprios ficam como estão. Desliga-se com
`OCRPostprocessor(repair_text=False)`. O léxico regenera-se com `python tools/build_pt_lexicon.py`.

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

### Real OCR Integration

The deterministic suite intentionally does not download PaddleOCR models. The
real engine test is isolated in the `OCR integration` workflow and can be
started manually from GitHub Actions. Locally, run:

```bash
pytest -m integration --runintegration --runslow -v
```

This test is slower and requires model downloads; it is not a required pull
request check.

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


## Pipeline, models and what to know

| Setting | Default | Why |
|---|---|---|
| `OCR_PIPELINE` | `ppocr` | `vl` = PaddleOCR-VL, GPU only |
| `OCR_DET_MODEL_NAME` / `OCR_REC_MODEL_NAME` | `PP-OCRv5_mobile_det` / `latin_PP-OCRv5_mobile_rec` | PP-OCRv4 has no `pt` model in PaddleOCR 3.x (it answered every request with 500) |
| `OCR_FALLBACK_DET_MODEL_NAME` | `PP-OCRv5_server_det` | pages that give fewer than `OCR_MIN_QUESTIONS` (2) questions are read again with the thorough detector (25 to 40 s) |
| `OCR_ENABLE_MKLDNN` | `true` | needs `paddlepaddle==3.2.2`; 3.3.1 dies with `ConvertPirAttribute2RuntimeAttribute not support` and the read goes from ~10 s to 30 to 140 s |
| `OCR_LAYOUT_*` | on, `PP-DocLayout_plus-L` | figure/table regions; a layout failure only costs the figures, never the text |
| `OCR_FORMULA_IMAGES` | `false` | the layout model takes the cotação line and handwriting for a formula |

The models are downloaded **when the image is built** (`tools/prefetch_models.py`), so a recreated container does
not depend on the internet and needs no model volume.

Limits found on the real exams: a photo whose lines the mobile detector cannot see (a faded block, a red circle over
a number) loses those questions even after the second read; the numbering repair (`_repair_lost_labels`) gives back
a label the photo turned into a dot or a digit, but a whole missing line stays missing.

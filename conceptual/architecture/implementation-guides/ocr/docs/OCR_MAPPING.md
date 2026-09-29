# Mapeamento OCR para Entidades do Banco de Dados

Este documento descreve como os dados extraídos via OCR são mapeados para as entidades do banco de dados do sistema Kixi - Banco de Enunciados.

## Visão Geral do Fluxo

```
Imagem/PDF → OCR Service (Python) → Backend API (Spring) → Banco de Dados (PostgreSQL)
```

## Estrutura da Prova Angolana (12ª Classe)

### Exemplo de Cabeçalho

```
REPÚBLICA DE ANGOLA
GOVERNO DA PROVÍNCIA DE LUANDA
GABINETE PROVINCIAL DE EDUCAÇÃO
DEPARTAMENTO DE EDUCAÇÃO E ENSINO

PROVA DE EXAME DE MATEMÁTICA
12ª Classe  Ano Lectivo: 2024/2025  Duração: 90 Min.  Série: B
CURSO: TODOS
```

### Exemplo de Cotação (Rodapé)

```
Cotação 1-a) 3 valores 2-) 4 valores 3-a) 2,5 valores 3-b) 2,5 valores 4-) 3 valores 5-a) 2,5 valores 5-b) 2,5 valores.
```

---

## Mapeamento: OCR → Entidades

### 1. Statement (Enunciado)

| Campo OCR | Campo Entidade | Tipo | Exemplo |
|-----------|----------------|------|---------|
| `examType` | `exam_type` | String | "Prova de Exame" |
| `durationMinutes` | `duration_minutes` | Integer | 90 |
| `variant` | `variant` | String | "B" |
| `title` | `title` | String | "Prova de Exame de Matemática 12ª Classe - Série B - 2024/2025" |
| `instructions` | `instructions` | String | "Leia com atenção, coloque na folha de prova..." |
| `totalMaxScore` | `total_max_score` | Double | 20.0 |
| `overallConfidence` | `ocr_confidence` | Double | 0.85 |
| `requestId` | `ocr_request_id` | String | "req-abc123xyz" |
| - | `source` | String | "ocr" (fixo) |
| - | `needs_review` | Boolean | true/false (baseado na confiança) |

### 2. SchoolYear (Ano Letivo)

| Campo OCR | Campo Entidade | Tipo | Exemplo |
|-----------|----------------|------|---------|
| `schoolYearStart` | `start_year` | Integer | 2024 |
| `schoolYearEnd` | `end_year` | Integer | 2025 |

**Padrões de Extração:**
- `Ano Letivo: 2024/2025`
- `Ano Lectivo: 2024/2025`
- `2024/2025`
- `2024-2025`

### 3. Subject (Disciplina)

| Campo OCR | Campo Entidade | Tipo | Exemplo |
|-----------|----------------|------|---------|
| `subjectName` | `name` | String | "Matemática" |
| - | `code` | String | "MAT" (gerado) |
| - | `short_name` | String | "Matemática" (gerado) |

**Padrões de Extração:**
- `PROVA DE EXAME DE MATEMÁTICA`
- `PROVA DE RECURSO DE FÍSICA`
- `EXAME DE QUÍMICA`

**Normalização de Nomes:**
- "matematica" → "Matemática"
- "fisica" → "Física"
- "quimica" → "Química"
- "portugues" → "Português"
- (etc.)

### 4. Course (Curso)

| Campo OCR | Campo Entidade | Tipo | Exemplo |
|-----------|----------------|------|---------|
| `courseName` | `name` | String | "TODOS" |
| - | `code` | String | "TOD" (gerado) |

**Padrões de Extração:**
- `CURSO: TODOS`
- `Curso: Ciências`

### 5. Class (Turma)

| Campo OCR | Campo Entidade | Tipo | Exemplo |
|-----------|----------------|------|---------|
| `classGrade` | `grade` | Integer | 12 |
| - | `code` | String | "12A-TOD" (gerado) |

**Padrões de Extração:**
- `12ª Classe`
- `12º Ano`
- `10ª classe`

### 6. Question (Questão)

| Campo OCR | Campo Entidade | Tipo | Exemplo |
|-----------|----------------|------|---------|
| `number` | `number` | Integer | 1 |
| `text.value` | `text` | String | "Resolve a seguinte equação exponencial..." |
| `type` | `question_type` | String | "development" / "multiple_choice" |
| `cotacao` | `max_score` | Double | 3.0 |
| `confidence` | `ocr_confidence` | Double | 0.9 |
| `pageIndex` | `page_index` | Integer | 0 |
| - | `order_index` | Integer | 0 (sequencial) |
| - | `needs_review` | Boolean | true/false (baseado na confiança) |

**Mapeamento de Tipos:**
| Tipo OCR | Tipo BD |
|----------|---------|
| `dissertativa` | `development` |
| `multipla_escolha` | `multiple_choice` |
| `unknown` | `unknown` |

---

## Extração de Cotação

### Formato Angolano

A cotação geralmente aparece no final da prova no formato:

```
Cotação 1-a) 3 valores 2-) 4 valores 3-a) 2,5 valores 3-b) 2,5 valores 4-) 3 valores 5-a) 2,5 valores 5-b) 2,5 valores.
```

### Padrões Suportados

1. **Questão com subitem:** `1-a) 3 valores`
2. **Questão sem subitem:** `2-) 4 valores`
3. **Valores decimais:** `3-b) 2,5 valores`
4. **Formato alternativo:** `(3 valores)` no final da questão

### Mapeamento Interno

O sistema cria um mapa de cotação:

```json
{
  "1a": 3.0,
  "2": 4.0,
  "3a": 2.5,
  "3b": 2.5,
  "4": 3.0,
  "5a": 2.5,
  "5b": 2.5
}
```

E depois associa a cada questão:
- Se a questão tem subitems, soma as cotações dos subitems
- Se não tem subitems, usa a cotação direta

---

## Endpoints para Teste

### Python OCR Service (porta 8000)

```bash
# Health check
curl http://localhost:8000/ocr/health

# Extração simples
curl -X POST http://localhost:8000/ocr/v1/extract/simple \
  -F "image=@prova.jpg"

# Extração completa (múltiplas imagens/PDF)
curl -X POST http://localhost:8000/ocr/v1/extract \
  -F "images=@prova.pdf"

# Idiomas suportados
curl http://localhost:8000/ocr/v1/supported-languages
```

### Spring Backend API (porta 8080)

```bash
# Health check
curl http://localhost:8080/api/v1/ocr/health

# Extração simples
curl -X POST http://localhost:8080/api/v1/ocr/extract/single \
  -F "file=@prova.jpg"

# Extração de exame estruturado
curl -X POST http://localhost:8080/api/v1/ocr/extract/exam \
  -F "files=@prova.pdf"

# Extração e persistência no banco
curl -X POST http://localhost:8080/api/v1/ocr/extract-and-persist \
  -F "files=@prova.pdf"
```

---

## Resposta JSON de Exemplo

### Extração de Exame (`/api/v1/ocr/extract/exam`)

```json
{
  "exam_type": "Prova de Exame",
  "duration_minutes": 90,
  "variant": "B",
  "title": "Prova de Exame de Matemática 12ª Classe - Série B - 2024/2025",
  "instructions": "Leia a prova com atenção...",
  "school_year_start": 2024,
  "school_year_end": 2025,
  "class_grade": "12",
  "course_name": "TODOS",
  "subject_name": "Matemática",
  "total_max_score": 20.0,
  "questions": [
    {
      "number": "1",
      "subitems": ["a)"],
      "text": "Resolve a seguinte equação exponencial...",
      "type": "dissertativa",
      "cotacao": 3.0,
      "options": null,
      "has_image": true,
      "image_description": "Expressão matemática complexa",
      "confidence": 0.85
    },
    {
      "number": "2",
      "subitems": [],
      "text": "Em um meio de cultura especial, a quantidade de bactérias...",
      "type": "dissertativa",
      "cotacao": 4.0,
      "options": null,
      "has_image": false,
      "confidence": 0.9
    }
  ],
  "images_to_upload": [
    {
      "suggested_filename": "prova-matematica-2024-2025-serie-b-cabecalho.png",
      "description": "Cabeçalho oficial com brasão/logo institucional",
      "region": "cabecalho"
    },
    {
      "suggested_filename": "prova-matematica-2024-2025-serie-b-questao-1.png",
      "description": "Expressão matemática complexa",
      "region": "questao_1"
    }
  ],
  "request_id": "req-abc123xyz",
  "processing_time_ms": 2500,
  "overall_confidence": 0.87,
  "needs_review": false,
  "warnings": []
}
```

---

## Script de Teste

Use o script `test-ocr.sh` na raiz do projeto:

```bash
# Ver ajuda
./test-ocr.sh --help

# Verificar saúde dos serviços
./test-ocr.sh --check

# Testar extração de exame
./test-ocr.sh -e prova.jpg

# Testar todos os endpoints
./test-ocr.sh prova.pdf
```

---

## Troubleshooting

### Cotação não extraída

1. Verifique se a cotação está no formato esperado
2. A cotação deve estar no final da prova
3. Formatos suportados: `X valores`, `X pontos`, `X pts`

### Disciplina não reconhecida

1. Verifique se o nome está na lista de normalização
2. O padrão `PROVA DE [EXAME|RECURSO] DE <DISCIPLINA>` é prioritário

### Baixa confiança

1. Melhore a qualidade da imagem (resolução mínima: 150 DPI)
2. Evite imagens com muito ruído ou anotações manuscritas
3. PDFs digitais têm melhor resultado que fotos

---

## Formatos Suportados

| Formato | Extensões | Notas |
|---------|-----------|-------|
| JPEG | .jpg, .jpeg | Fotos de provas |
| PNG | .png | Scans de alta qualidade |
| PDF | .pdf | Multipáginas suportado |
| WebP | .webp | Compressão moderna |
| BMP | .bmp | Sem compressão |
| TIFF | .tiff, .tif | Scans profissionais |

**Limite de tamanho:** 20MB por arquivo
**Máximo de arquivos:** 10 por requisição

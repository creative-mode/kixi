# Guia de Implementação Completo  
**Pipeline OCR com PaddleOCR-VL**  
**Kixi Platform**  
**Versão:** 1.1  
**Data:** Janeiro 2026  
**Status:** Proposto para Revisão e Aceitação via ADR  

Este documento consolida a implementação do microserviço OCR como componente isolado e stateless, responsável pela extração estruturada de enunciados de provas a partir de imagens. A estrutura de saída JSON é projetada para espelhar os campos principais das entidades `statements` e `questions` do modelo ERM, sem acoplamento forte: valores textuais livres, confiança granular e ausência de validação de domínio (lookup de IDs, existência de disciplinas etc. ficam exclusivamente no backend).

O serviço **nunca** acessa a base de dados, persiste dados ou aplica regras de negócio. Atua apenas como transformador de imagem → JSON estruturado.

## 0. FLuxo

```markdown

UI (Web/Mobile)

   ↓ multipart/form-data (imagens + contexto)

Backend API (Spring Boot WebFlux) → POST /api/statements/upload-ocr

   ↓ HTTP POST (WebClient reativo + Resilience4j)

OCR Service (Python FastAPI + PaddleOCR-VL) → POST /ocr/v1/extract

   ↓ JSON estruturado (contrato abaixo)

Backend API (StatementService)

   ↓ mapeamento + lookup/validação de domínio

   ↓ persistência reativa (R2DBC)

   ↓ evento assíncrono: StatementCreatedEvent → Kafka topic "statements.created"

AI Service (consome evento e indexa para RAG)
```

## 1. Objetivos e Princípios Arquiteturais
- **Separação estrita**: OCR extrai; backend mapeia/valida/persiste.  
- **Contrato claro**: JSON hierárquico definido via schema em `libs/contracts/ocr-extract-response.json`.  
- **Escalabilidade**: Suporte a alta concorrência via assincronia + multiprocessing/GPU.  
- **Observabilidade**: Métricas Prometheus, tracing Jaeger, logs estruturados.  
- **Idempotência**: Hash da imagem permite cache de resultados.  

## 2. Arquitetura Interna do Microserviço OCR
O serviço é construído com **FastAPI** (Python 3.11+), **PaddleOCR-VL** (modelos PP-OCRv4 + layout analysis) e **OpenCV** para pré-processamento.

### Camadas
- **API Layer** (`app/api/endpoints.py`):  
  Endpoint único: `POST /ocr/extract` (multipart/form-data).  
  Validação: Pydantic + middleware JWT.  
  Delega para camada de orquestração.

- **Orquestração** (`app/ocr/engine.py`):  
  Pré-carrega modelos no startup.  
  Processa imagens em paralelo (asyncio + multiprocessing).  
  Pré-processa: deskew, binarização, resize.  
  Executa PaddleOCR-VL (detecção + reconhecimento + layout parsing).  
  Pós-processa: filtra confiança, infere tipo de questão, reconstrói hierarquia.

- **Parsing** (`app/ocr/postprocessing.py`):  
  Extrai metadados via regex + layout (cabeçalho/rodapé).  
  Segmenta questões por numeração e blocos visuais.  
  Infere `questionType` (multiple_choice, short_answer, development, true_false).  
  Extrai opções quando tabela ou lista é detetada.

## 3. Contrato HTTP
**Endpoint:** `POST /ocr/extract`  
**Cabeçalhos:**  
- `Authorization: Bearer <JWT>`  

**Corpo (multipart/form-data):**  
- `images[]`: ficheiros de imagem (JPEG/PNG, múltiplos permitidos).  
- `context` (opcional): JSON como string (ex: `{"languageHint": "pt"}`).

**Respostas:**  
- 200 OK: JSON estruturado (abaixo).  
- 400/422: Erro de validação.  
- 500: Falha interna (com retry no backend via Resilience4j).

## 4. Estrutura da Resposta JSON
Definida em `libs/contracts/ocr-extract-response.json` (JSON Schema).

### Estrutura Geral
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
  "metadata": { ... campos espelhando statements ... },
  "questions": [ ... array espelhando questions + questionOptions ... ],
  "unmappedContent": [ ... texto residual ... ],
  "warnings": [ ... alertas ... ]
}
```

### Exemplo Completo (Prova de Matemática – 3 páginas)
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
    "schoolYear": {
      "value": "2024/2025",
      "confidence": 0.97
    },
    "term": {
      "value": "2º Trimestre",
      "confidence": 0.94
    },
    "subject": {
      "value": "Matemática A",
      "confidence": 0.93
    },
    "course": {
      "value": null,
      "confidence": 0.0
    },
    "class": {
      "value": "12º Ano - Turma A",
      "confidence": 0.89
    },
    "examType": {
      "value": "Prova de Avaliação Periódica",
      "confidence": 0.91
    },
    "durationMinutes": {
      "value": 120,
      "confidence": 0.88
    },
    "variant": {
      "value": "A",
      "confidence": 0.96
    },
    "title": {
      "value": "Avaliação Sumativa - 2º Trimestre",
      "confidence": 0.90
    },
    "instructions": {
      "value": "Leia atentamente. Responda no espaço destinado.",
      "confidence": 0.85
    }
  },
  "questions": [
    {
      "number": 1,
      "confidence": 0.935,
      "text": {
        "value": "Resolva a equação: 3x - 7 = 14",
        "confidence": 0.96
      },
      "questionType": {
        "value": "short_answer",
        "confidence": 0.89
      },
      "maxScore": {
        "value": 5,
        "confidence": 0.92
      },
      "options": [],
      "pageIndex": 0,
      "startY": 280,
      "endY": 420
    },
    {
      "number": 2,
      "confidence": 0.918,
      "text": {
        "value": "Qual das seguintes opções representa a raiz quadrada de 64?",
        "confidence": 0.95
      },
      "questionType": {
        "value": "multiple_choice",
        "confidence": 0.94
      },
      "maxScore": {
        "value": 4,
        "confidence": 0.90
      },
      "options": [
        {
          "optionLabel": "A",
          "optionText": "6",
          "confidence": 0.97
        },
        {
          "optionLabel": "B",
          "optionText": "8",
          "confidence": 0.96
        },
        {
          "optionLabel": "C",
          "optionText": "7",
          "confidence": 0.94
        },
        {
          "optionLabel": "D",
          "optionText": "9",
          "confidence": 0.95
        }
      ],
      "pageIndex": 1,
      "startY": 120,
      "endY": 380
    },
    {
      "number": 3,
      "confidence": 0.862,
      "text": {
        "value": "Justifique por que o triângulo ABC é congruente ao triângulo DEF.",
        "confidence": 0.89
      },
      "questionType": {
        "value": "development",
        "confidence": 0.91
      },
      "maxScore": {
        "value": 10,
        "confidence": 0.87
      },
      "options": [],
      "pageIndex": 2,
      "startY": 90,
      "endY": 520
    }
  ],
  "unmappedContent": [
    {
      "pageIndex": 2,
      "text": "Gabarito preliminar (uso interno)",
      "confidence": 0.94
    }
  ],
  "warnings": [
    {
      "code": "LOW_CONFIDENCE",
      "field": "class",
      "confidence": 0.76
    }
  ]
}
```

## 5. Mapeamento no Backend (Spring Boot WebFlux)
No serviço `StatementService`:

1. **Recebe JSON** via WebClient (reativo).  
2. **Valida confiança** (ex: threshold 0.80 por campo).  
3. **Lookup/criação condicional**:
   ```java
   // Exemplo simplificado
   SchoolYear sy = schoolYearRepository.findByYears(meta.schoolYear.value)
       .orElseGet(() -> schoolYearService.createFromString(meta.schoolYear.value));
   ```
4. **Constrói Statement**:
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
5. **Constrói Questions + Options**:
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
6. **Persiste** via repositório reativo.  
7. **Marca revisão** se confiança baixa (campo `needsReview`).

## 6. Escalabilidade e Operacionalização
- **Pré-carga modelos** no startup (reduz latência fria).  
- **GPU** para PaddleOCR-VL (deploy em nós dedicados).  
- **Fila** (Celery + Redis) para picos.  
- **Métricas**: latência por página, confiança média, taxa de warnings.  
- **Circuit Breaker**: Resilience4j no backend.  
- **Cache**: Redis com chave = hash da imagem.

## 7. Próximos Passos
- Implementar JSON Schema validation no backend.  
- Adicionar suporte a fórmulas LaTeX (via detecção de blocos matemáticos).  
- Testes end-to-end: upload → OCR → persistência.  
- ADR complementar: decisão sobre fila vs. processamento síncrono.

**Aprovação pendente:**  
[ ] Tech Lead  
[ ] Arquitetura  

Última atualização: Janeiro 2026
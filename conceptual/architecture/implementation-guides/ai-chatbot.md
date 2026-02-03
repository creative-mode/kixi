# Implementation Guide: Chatbot and AI Layer (RAG)

**Status:** Proposed for Review and Acceptance via ADR

The AI and chatbot layer of the Enuncia Platform operates exclusively on structured data already persisted in the relational database, never on raw OCR outputs or unvalidated information. Its purpose is to provide intelligent study support, content explanations, generation of similar exercises, and complementary recommendations, always keeping the database as the single source of truth.

The layer is implemented as an independent microservice (`ai-service`) in Python using FastAPI and LangChain, ensuring full decoupling from the ingestion pipeline (OCR + backend) and allowing independent evolution.

### Core Principles

The AI layer is read-only with respect to domain data. It does not persist, modify, or create entities. It uses Retrieval-Augmented Generation (RAG) to fetch relevant context from the vector database and enrich language model responses.

All processing respects authorization rules (RBAC) applied in the main backend. The chatbot supports explicit references to statements in the form `@statement.[subject].[schoolYear].[term].[examType]`, loads the full statement context when referenced, and keeps it active during the conversation while the user refers to questions or parts of it.

### Technology Decision

The `ai-service` microservice is built on Python 3.11+ with FastAPI for reactive API and LangChain for orchestration of chains and retrieval.

The chosen embedding model is multilingual and lightweight (e.g., `paraphrase-multilingual-mpnet-base-v2` or `intfloat/multilingual-e5-large`). The initial vector database is PgVector (PostgreSQL extension in the main database), with the possibility to migrate to Pinecone or Milvus at larger scale.

The LLM is configurable via environment variables (Grok-2 via xAI API recommended initially, with native support for Gemini, GPT-4o-mini, or local models via Ollama/vLLM). The final decision on the main provider will be formalized in a complementary ADR.

### Indexing Flow

After a Statement (exam/statement) is persisted or updated, the backend publishes an asynchronous event (Kafka topic `enuncia.events.statement` or Spring ApplicationEvent).

The `ai-service` consumes the event, retrieves the full Statement including `Questions` and `QuestionOptions` (via secure backend API or direct read with row-level security).

Each question is transformed into a cohesive document including: statement, question type, max score, options (if applicable), and statement metadata (subject, schoolYear, term, examType).

The document is embedded and indexed in the vector database with filterable metadata (`statement_id`, `subjectId`, `schoolYearId`, etc.). Indexing is eventually consistent and idempotent.

### Query Flow and Main Features

The user sends a message via frontend → backend API (`POST /ai/chat`). The backend validates JWT authentication and RBAC authorization before forwarding the query to the `ai-service`.

The `ai-service` processes the query as follows:

1. Parser identifies references in the format `@statement.[subject].[schoolYear].[term].[examType]` and applies a mandatory retrieval filter for the corresponding statement.
2. If the query mentions a specific question (e.g., “explain question 3”), the full statement context is loaded and kept active for the entire subsequent conversation about that statement.
3. Semantic retrieval fetches top-k relevant documents (k configurable, minimum threshold ~0.75).
4. Prompt construction includes: retrieved context, system instructions, recent conversation history, and any user-specified filters.
5. LLM call (streaming when supported).

Specific implemented features:

* Detailed explanation of questions, with clear steps and logical reasoning.
* Generation of new exercises of the same level, theme, and difficulty as the referenced statement (dedicated chain with specific prompt).
* Automatic generation of correct answers when options do not have `isCorrect` defined in the database (explicit prompt to avoid hallucination).
* Adaptation to a specific teacher’s grading style, when indicated (e.g., “respond as Prof. João prefers: formal and encouraging”). Preferences stored per user/teacher via configuration.
* Dynamic recommendation of complementary materials (Portuguese/Angolan textbooks and YouTube videos), retrieved via integrated external search (LangChain tools for web search and YouTube). Recommendations include title, author/publisher, and direct link.

### Vector Database Document Structure

Each entry represents a question and includes:

* `id`: question ID
* `statement_id`: reference to the statement
* `statement_reference`: string in the format `@statement...`
* `content`: full text enriched with metadata
* `metadata`: filters (`schoolYearId`, `subjectId`, `questionType`, etc.)
* `embedding`: generated vector

### Main Endpoints

* **POST /chat/query**
  Body: `{ "query": string, "userId": id, "contextFilters": optional object }`
  Response: streaming JSON or object with `answer`, `sources`, `generatedExercises`, `complementaryMaterials` (array with title + link)

* **POST /admin/reindex** (protected)
  Forces reindexing of a specific statement

### Communication and Security

The backend acts as an authenticated proxy. JWT is validated and propagated. User-level rate limiting (Redis) supports a future freemium model. Asynchronous events ensure decoupling.

### Scalability and Observability

Horizontal scaling via K8s replicas. Redis cache for embeddings and frequent responses. Prometheus metrics monitor latency, recall@K, generation time, and token consumption. Distributed tracing (Jaeger) correlates full flows.

### Recommended Initial Configuration

* Embedding: `paraphrase-multilingual-mpnet-base-v2`
* Vector DB: PgVector
* LLM: Grok-2 (xAI API) as default configurable
* Base system prompt:
  “You are an expert tutor for the Enuncia Platform. Always respond in European Portuguese clearly, educationally, and structured. Use only the provided context. Cite relevant questions. Generate similar exercises when requested. Recommend useful complementary materials (books and videos) with links. If no correct answer exists in the database, generate correct answers rigorously. Adapt to the teacher’s style when indicated.”

### Next Steps

* Prototype full flow: persist statement → index → reference via @ → explain + generate exercise + recommend material
* Validate accuracy of generated keys and exercises with teachers
* Conduct A/B tests for k, threshold, and embedding model
* Formalize LLM choice via ADR
* Define SLOs: 95% of answers under 4 seconds, recall@5 above 0.85

Pending Approval:
[ ] Tech Lead
[ ] Architecture

Last update: January 2026

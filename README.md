# Kixi

Kixi is a modular platform for managing, organizing, and reusing exam papers, with support for automated content extraction from images.

---

## Services

The repository is a monorepo with two runnable services:

- `services/backend-api` – Main Spring Boot (WebFlux) backend: identity, academic structure, statements, simulations and the AI tutor
- `services/ocr-service` – PaddleOCR-VL service for text extraction from images

The `ocr-service` has a dedicated [`README`](services/ocr-service/README.md); the
backend is documented here and in [`conceptual/`](conceptual/).

---

## Delivered modules

The backend is organised by domain. Each module is a package under
`services/backend-api/src/main/java/ao/creativemode/kixi/`, and the dependency
rules between them are enforced by
[`ArchitectureTest`](services/backend-api/src/test/java/ao/creativemode/kixi/architecture/ArchitectureTest.java).

| Module | Responsibility | Main endpoints |
| --- | --- | --- |
| `identity` | Accounts, users, roles, sessions, teachers and JWT authentication | `/api/v1/auth`, `/api/v1/accounts`, `/api/v1/users`, `/api/v1/roles`, `/api/v1/sessions`, `/api/v1/teachers` |
| `academic` | School years, terms, subjects, courses and classes | `/api/v1/school-years`, `/api/v1/terms`, `/api/v1/subjects`, `/api/v1/courses`, `/api/v1/classes` |
| `institutions` | Institutions, enrollments, teaching assignments and the caller's profile | `/api/v1/institutions`, `/api/v1/enrollments`, `/api/v1/teaching-assignments`, `/api/v1/me` |
| `exams` | Statements, questions, options, answer keys and question images | `/api/v1/statements`, `/api/v1/statements/manual`, `/api/v1/questions`, `/api/v1/question-images` |
| `simulations` | Simulations and the answers submitted to them | `/api/v1/simulations` (legacy alias `/api/simulations`), `/api/v1/simulation-answers` |
| `chat` | AI tutor: sessions and streaming answers scoped to one statement | `/api/v1/chat/sessions` |
| `ocr` | Backend bridge to the OCR service | `/api/v1/ocr` |
| `shared` | Cross-cutting code: error handling, configuration and storage | — |

The `ocr-service` runs as a separate container (Python/FastAPI + PaddleOCR-VL).

---

## Documentation

Project-wide documentation lives in `conceptual/`:

- `conceptual/adr/` – Architectural Decision Records (ADR-0001 … ADR-0012)
- `conceptual/architecture/implementation-guides/` – implementation and usage guides
- `conceptual/architecture/db/` – entity model (UML and ERM)
- `conceptual/architecture/diagrams/` – system and process diagrams

Where to start:

- [Arranque em 10 minutos](conceptual/architecture/implementation-guides/getting-started.md) – run the whole platform locally
- [API e OpenAPI](conceptual/architecture/implementation-guides/api.md) – the published contract and the main flows
- [Reactive CRUD reference](conceptual/architecture/implementation-guides/crud-flux.md)

---

## Getting Started

The full walkthrough is in the
[Arranque em 10 minutos](conceptual/architecture/implementation-guides/getting-started.md)
guide. The short version:

```bash
cp .env.example .env   # then set APP_JWT_SECRET and OCR_API_KEY (>= 32 chars)
docker compose up --build
```

Verify:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8000/health
```

Swagger UI: <http://localhost:8080/swagger-ui.html>

---

## Contribution

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for guidelines on contributing, code style, tests, and legal compliance.

---

## License

Kixi is licensed under **Apache License 2.0 with Commons Clause restriction**.
You may use, modify, and contribute, but may **not sell or redistribute** the project or derivative works as a standalone product or competing service. See [LICENSE](LICENSE) for details.

# Kixi

Kixi is a modular platform for managing, organizing, and reusing exam papers, with support for automated content extraction from images.

---

## Services

Each service has its own repository and README:

- `services/backend-api` – Main Spring Boot backend  
- `services/ocr-service` – PaddleOCR-VL service for text extraction  

---

## Documentation

Project-wide documentation is located in:

- `docs/architecture/` – architectural guides and patterns  
- `docs/adr/` – Architectural Decision Records  
- `docs/diagrams/` – system and process diagrams  

Reference guide for reactive CRUD implementations:  
`docs/architecture/crud-flux.md`

---

## Getting Started

Run the full platform locally:

```bash
docker compose up --build
````

Follow each service README for service-specific instructions.

---

## Contribution

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for guidelines on contributing, code style, tests, and legal compliance.

---

## License

Kixi is licensed under **Apache License 2.0 with Commons Clause restriction**.
You may use, modify, and contribute, but may **not sell or redistribute** the project or derivative works as a standalone product or competing service. See [LICENSE](LICENSE) for details.


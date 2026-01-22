
# Enuncia Platform

A modular platform for managing, organizing, and reusing exam papers, designed with a service-oriented architecture and support for automated content extraction from images.

This repository serves as the **single source of truth** for the entire system, aggregating services, infrastructure, shared contracts, and architectural documentation.

---

## Repository Structure

```text
enuncia-platform/
│
├─ services/        # Executable services (each with its own README)
├─ libs/            # Shared contracts and common libraries
├─ infra/           # Infrastructure and deployment assets
├─ docs/            # Architectural and technical documentation
├─ .github/         # CI/CD pipelines and repository governance
│
├─ docker-compose.yml
├─ README.md
└─ CONTRIBUTING.md
````

---

## Overview

The system is composed of multiple independent services, each responsible for a well-defined set of concerns.
This approach promotes clear domain boundaries, low coupling, and long-term scalability.

* Business logic is encapsulated within backend services
* Specialized processing (e.g. OCR) is isolated into dedicated services
* Infrastructure and automation are versioned alongside application code

Technical details, APIs, and runtime instructions are documented in each service’s own `README.md`.

---

## Documentation

Project-wide documentation is centralized under:

```text
docs/
```

This includes:

* Architectural guidelines and reference implementations
* Implementation standards and reusable patterns
* Architectural Decision Records (ADR)
* Technical and system diagrams

Key documents:

* `docs/architecture/crud-flux.md` – reference guide for implementing reactive CRUD modules
* `docs/adr/` – records of significant architectural decisions

---

## Local Execution

To run the full platform locally:

```bash
docker compose up --build
```

Individual services can also be started independently by following the instructions in their respective directories.

---

## Contribution

Contribution guidelines, development workflow, and quality standards are described in:

```text
CONTRIBUTING.md
```

All contributions should follow the defined workflow and adhere to the documented architectural principles.

---

## Notes

* This repository defines the canonical structure and standards of the platform
* Each service is expected to maintain its own documentation
* Architectural changes should be accompanied by an ADR
  
## License

This project is licensed under the Apache License 2.0
with the Commons Clause restriction.

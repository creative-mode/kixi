# ADR 0011: OCR Flow and Python Service Boundary

## Status
Accepted

## Context

The Kixi platform supports importing exam papers from images. This process requires
optical character recognition (OCR), image processing, and future AI-based reasoning.

Because OCR relies on Python-based libraries and is computationally expensive, it is
necessary to clearly define service boundaries, responsibilities, and the data flow
between the UI, backend API, and OCR processing layer.

This ADR formalizes the OCR integration flow and clarifies the role of the Python service
within the system architecture.

---

## Decision

OCR will be implemented as a **dedicated Python microservice** that exposes an HTTP API
and internally executes **PaddleOCR-VL**.

The Python API **is part of the OCR service itself**. There is no separate “API layer”
preceding the OCR logic.

The backend API (Spring) remains the sole owner of business rules and persistence.

---

## OCR Import Flow

```

UI
│
│  (multipart/form-data)
▼
Backend API (Spring WebFlux)
│
│  (HTTP / JSON)
▼
OCR Service (Python + FastAPI + PaddleOCR-VL)
│
│  (structured JSON)
▼
Backend API
│
▼
Database

```

---

## Responsibilities

### UI
- Upload exam images
- Display OCR import results and validation feedback

### Backend API (Spring)
- Authenticate and authorize requests (JWT + RBAC)
- Validate input and user permissions
- Call the OCR service
- Map OCR output to domain entities
- Apply business rules and validation
- Persist data in the database
- Return structured responses to the UI

### OCR Service (Python)
- Expose an HTTP API (FastAPI)
- Receive image uploads
- Execute PaddleOCR-VL
- Extract textual content from images
- Perform basic semantic structuring (e.g., question detection)
- Return structured JSON
- Remain stateless and database-agnostic

### Database
- Store validated domain data only
- Be accessed exclusively by the backend API

---

## Alternatives Considered

### Embedding OCR inside the backend API
Rejected due to tight coupling, limited scalability, resource contention, and dependency
management complexity.

### Splitting OCR into multiple Python services
Rejected due to unnecessary complexity, increased latency, and overengineering for the
current system scope.

---

## Consequences

### Positive
- Clear separation of concerns
- Independent scalability of OCR processing
- Cleaner domain model and backend architecture
- Simplified deployment and maintenance
- Easier future extension with AI and paid features

### Negative
- Network communication overhead
- Need for strict API contract versioning

---

## Notes

- Future versions may introduce asynchronous OCR processing via message queues.
- AI and chatbot features will operate on structured data persisted by the backend API.
- This ADR is the authoritative reference for OCR-related architectural decisions.

---

## Decision Summary

The OCR capability is implemented as a **self-contained Python microservice with its own
HTTP API**. There is no standalone “Python API” outside of the OCR service. All business
logic and persistence remain within the backend API.

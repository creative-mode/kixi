# ADR 0002: Microservices Architecture

## Status
Accepted

## Context

The platform includes heterogeneous workloads such as OCR processing, business logic,
and future AI reasoning. These workloads have different scalability and runtime
requirements.

---

## Decision

The system adopts a **microservices architecture**, separating responsibilities into
independent services communicating via HTTP.

---

## Consequences

### Positive
- Independent scalability
- Clear separation of concerns
- Technology freedom per service

### Negative
- Network communication overhead
- Need for API contracts and versioning

---

## Decision Summary

Kixi is composed of independently deployable services with clear responsibility
boundaries.

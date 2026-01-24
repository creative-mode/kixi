# ADR 0003: Backend Technology Stack

## Status
Accepted

## Context

The backend API must support high concurrency, stateless authentication, and integration
with multiple services.

---

## Decision

The backend API is implemented using:
- Spring Boot
- Spring WebFlux
- PostgreSQL
- HTTP/JSON communication

---

## Consequences

### Positive
- High throughput and scalability
- Non-blocking I/O
- Strong ecosystem support

### Negative
- Higher complexity compared to traditional MVC

---

## Decision Summary

Spring Boot with WebFlux is the standard backend stack for Kixi.

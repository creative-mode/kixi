# ADR 0001: Monorepo Strategy

## Status
Accepted

## Context

The Kixi platform consists of multiple services (backend API, OCR service, future AI
services) that evolve together and share domain concepts, contracts, and infrastructure.

Managing these services in separate repositories would increase coordination overhead
and reduce visibility across the system.

---

## Decision

We adopt a **monorepo strategy**, hosting all services, shared libraries, infrastructure
definitions, and documentation in a single repository.

---

## Consequences

### Positive
- Unified versioning and visibility
- Easier refactoring across services
- Centralized documentation and ADRs
- Simplified CI/CD coordination

### Negative
- Larger repository size
- Requires discipline in service boundaries

---

## Decision Summary

Kixi uses a single monorepo with clear directory boundaries between services,
libraries, infrastructure, and documentation.

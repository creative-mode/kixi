# ADR 0008: OCR Output Contract

## Status
Accepted

## Context

The OCR service must return data that can evolve without breaking the backend.

---

## Decision

The OCR service returns **versioned structured JSON**, focused on extracted content,
not business rules.

---

## Consequences

- Backend handles validation and mapping
- OCR remains domain-agnostic

---

## Decision Summary

A versioned JSON contract defines the OCR output.

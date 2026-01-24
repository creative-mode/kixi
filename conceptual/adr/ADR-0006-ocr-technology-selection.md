# ADR 0006: OCR Technology Selection

## Status
Accepted

## Context

The system must extract structured textual data from exam images with support for
complex layouts.

---

## Decision

We select **PaddleOCR-VL** as the OCR engine.

---

## Alternatives Considered

- Tesseract OCR
- Cloud-based OCR services

---

## Consequences

### Positive
- High accuracy
- Open-source
- Good support for structured documents

### Negative
- Requires Python runtime and ML dependencies

---

## Decision Summary

PaddleOCR-VL is the OCR engine used by Kixi.

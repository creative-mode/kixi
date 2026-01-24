# ADR 0010: AI and Chatbot Architecture

## Status
Accepted

## Context

Users will interact with the system via a chatbot that answers questions based on stored
exam content.

---

## Decision

AI and chatbot features will:
- Consume structured data from the database
- Be separated from OCR
- Be implemented as independent services when needed

---

## Consequences

- Clean separation of concerns
- Easier evolution of AI capabilities

---

## Decision Summary

AI services operate on validated domain data, not raw OCR output.

# ADR 0004: Authentication with JWT

## Status
Accepted

## Context

The platform requires stateless authentication compatible with microservices and APIs.

---

## Decision

Authentication is implemented using **JWT (JSON Web Tokens)**.

- Tokens are issued by the backend API
- Tokens are validated on each request
- No server-side session storage is used

---

## Consequences

### Positive
- Stateless and scalable
- Easy integration with APIs and frontend

### Negative
- Token revocation requires additional strategies

---

## Decision Summary

JWT is the standard authentication mechanism for Kixi.

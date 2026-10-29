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

## Revocation (kixi#450)

The token lives 24 h and carries the roles. On its own, a disabled or deleted account, or a role taken away, kept working until the token expired.

`AccountSessionGate` closes that without making the API stateful for every request: at most once per `app.jwt.revalidation-ms` (30 s by default)
and per account, `JwtAuthenticationFilter` confirms in the database that the account exists, is active and still has the roles the token claims.

- **Account deleted or inactive:** the token authenticates nobody (401). Takes effect within the window.
- **Role removed:** only the token roles the account still has are granted. A role **added** after the token was issued is not granted until a new
  login, so nobody gets access through a token that already exists.
- **Database unreachable:** the filter trusts the token, as before. The API is already unavailable in that case and should not also lock everyone out;
  the failure is logged and not cached.
- `app.jwt.revalidation-ms=0` turns the check off.

Cost: three small reads per active account every 30 s (the concurrent requests of one account share a single read).

**Not done:** refresh tokens and a shorter expiry. They need both frontends to renew the session without losing a student's answers mid-exam
(kixi-frontend#587 keeps unsaved answers locally, which makes it viable), so they are a separate decision.

---

## Decision Summary

JWT is the standard authentication mechanism for Kixi.

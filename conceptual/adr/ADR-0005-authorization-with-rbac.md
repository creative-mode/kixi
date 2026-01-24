# ADR 0005: Authorization with RBAC

## Status
Accepted

## Context

Different users (admin, professor, student) require different access levels.

---

## Decision

Authorization is implemented using **Role-Based Access Control (RBAC)**.

- Roles are assigned to accounts
- Access is enforced at endpoint and service levels

---

## Consequences

### Positive
- Clear permission model
- Easy to reason about access rules

### Negative
- Less flexible than attribute-based models

---

## Decision Summary

RBAC is used to control access across the Kixi platform.

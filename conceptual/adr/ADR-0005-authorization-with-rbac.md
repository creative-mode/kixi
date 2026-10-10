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

---

## Update — RBAC por recurso (2026-10)

O modelo mantém-se RBAC, mas a decisão foi concretizada de forma mais estrita do
que a versão original sugeria: a autorização é **por recurso**, avaliada num único
ponto — o `SecurityConfig` do `backend-api` (`authorizeExchange`) — e não apenas
nos serviços.

Papéis: `ADMIN`, `TEACHER`, `STUDENT`, atribuídos a contas (`account_roles`).

| Acesso | Recursos |
| --- | --- |
| Público | `/api/v1/auth/**`, `/actuator/health`, documentação (`/v3/api-docs/**`, `/swagger-ui/**`) |
| `ADMIN` | `accounts`, `users`, `roles`, `sessions`, `teachers`; escrita de instituições; CRUD de `teaching-assignments` |
| `ADMIN` ou `TEACHER` | Escrita de enunciados, questões, opções e imagens; vistas de revisão/estatísticas de enunciados; escrita de académicos (`school-years`, `terms`, `subjects`, `courses`, `classes`) e do OCR; `GET /teaching-assignments/me` |
| Autenticado (qualquer papel) | `/me`, `enrollments`, `chat`, leitura de instituições, leitura de enunciados, leitura de académicos, simulações |
| `ADMIN`, `TEACHER` ou `STUDENT` | `POST` de `simulations` e `simulation-answers` |

A decisão por papel é complementada por verificação de posse/afiliação no serviço:
o catálogo de enunciados fica dentro da escola do chamador, cada sessão de chat
está amarrada ao seu dono e a um enunciado visível, e a escrita de enunciados pesa
a escola do enunciado.

A mudança face à versão original deve-se a falhas concretas de *fall-through*: as
rotas não listadas caiam no `anyExchange()`, e um aluno chegou a alcançar material
que não devia (atribuições de docência e o gabarito). A regra passou a ser
**explicitar cada árvore de rotas** em vez de confiar no serviço.

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

---

## Update — O tutor é um módulo do backend (2026-10)

A hipótese original — "serviços independentes quando necessário" — não se
concretizou. O tutor (issue #114, entregue na #159) é um módulo **`chat`** dentro
do `backend-api`, e não um serviço à parte.

A razão é de fronteiras, não de conveniência: o tutor precisa de ler os enunciados
já estruturados *com a mesma autorização do chamador*. Num serviço separado, o RBAC,
a resolução da conta e a visibilidade dos enunciados teriam de ser duplicados ou
replicados por chamada — mais superfície para divergir do que a plataforma já
garante. O que varia por fornecedor (Groq) e o transporte (SSE) são detalhes de
implementação, não razões para partir o serviço.

As fronteiras que o ADR pedia mantêm-se, agora verificadas por testes:

- o módulo `chat` só depende de `shared` e `exams` — não de `identity`, `academic`
  ou `institutions` (regra `chatOnlyDependsOnSharedAndExams` no `ArchitectureTest`);
- o tutor **nunca** fala directamente com o OCR: consome o enunciado já extraído e
  validado, nunca o output cru;
- o `ocr-service` continua a ser um serviço próprio e independente.

Ou seja, a "independência" exigida é de **fronteira e de dados**, não de processo
ou de deploy.

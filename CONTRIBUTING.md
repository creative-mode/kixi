# Contributing to Kixi

Thank you for your interest in contributing to Kixi.  
Before submitting contributions, please review these guidelines carefully to ensure legal compliance and maintain project quality.

---

## 1. Code of Conduct

All contributors must follow the [Code of Conduct](CODE_OF_CONDUCT.md).  
Respect, professionalism, and constructive feedback are required in all interactions.

---

## 2. How to Contribute

You can contribute by:

- Fixing bugs
- Adding new features
- Improving documentation
- Refactoring code for readability, performance, or maintainability

---

### 2.1 Workflow

We follow a standard Git workflow:

1. **Issue creation** – Issues are created and prioritized by the Project Manager.
2. **Branching** – Create a branch from `main` using a clear descriptive name:
```

feature/<short-description>
bugfix/<short-description>

```
3. **Commit messages** – Use clear, descriptive messages, preferably in the format:
```

type(scope): short description

```
Example:  
```

feat(ocr-service): add new endpoint for structured text extraction

````
4. **Pull Request (PR)** – Once ready, open a PR against `main`. Include:
- Issue reference
- Description of changes
- Any required testing steps
5. **Code Review** – All PRs require approval by at least one maintainer.

---

### 2.2 Tests

- All new features or bug fixes should include automated tests.
- Maintain high coverage for critical modules.
- Run all tests locally before submitting a PR:
```bash
./mvnw test  # for backend-api
pytest       # for ocr-service
````

---

### 2.3 Texto: encoding e indentação

Duas coisas não falham o build e já custaram revisão humana mais do que uma vez, por isso
passaram a ser verificadas na CI (`.github/scripts/text-hygiene.js`). Corre-as localmente
antes de fazeres push:

```bash
node .github/scripts/text-hygiene.js
```

**Encoding.** Ao resolver conflitos entre ficheiros em encodings diferentes, caracteres como
`─` (U+2500) ficam reinterpretados como Latin-1 e aparecem como `â”€`. Todos os ficheiros
`.java`, `.sql`, `.yml`, `.md` e `.json` são guardados em UTF-8. Nota: nenhuma regra do
`.gitattributes` impede isto — `working-tree-encoding` assume que a working tree já está em
UTF-8 e grava os bytes tal e qual. É a verificação que apanha.

**Indentação.** Um `@Test`, um `@ExceptionHandler`, um `fieldErrors` ou um javadoc de membro
encostados à coluna zero enquanto o resto da classe usa quatro espaços. Cabeçalhos de
`record` e o `{` de uma interface multilínea ficam na coluna zero por convenção e não são
assinalados.

**Um teste que confirma o que acabaste de inventar não vale nada.** Vale a pena confirmar
que o teste falha se inverteres a asserção: um payload tem de ser verificado contra aquilo
que o cliente já lê, não contra a forma que escolheste. Foi assim que entrou um erro de
validação aninhado que nenhum dos três frontends conseguia ler, e que só apareceu quando um
teste de integração o exercitou a sério.

---

## 3. Legal and Licensing

By contributing to this project, you agree that:

1. Your contributions will be licensed under **Apache License 2.0 with Commons Clause**.
2. You do **not have the right to sell or redistribute** the contributions as a standalone product or competing service.
3. Your contributions can be used by the project maintainers for both free and paid features of Kixi.
4. You affirm that you have the right to submit your contributions (i.e., you are the copyright owner or authorized to contribute).

---

## 4. Code Style

* Follow Java conventions for backend-api (Spring Boot)
* Use PEP8 conventions for Python ocr-service
* Keep commits small and atomic
* Write clear, self-explanatory code with comments when needed

---

## 5. Documentation

* All contributions affecting functionality must include or update documentation.
* API changes must be reflected in OpenAPI/Swagger specs (`libs/contracts`).

---

## 6. Reporting Security Issues

If you discover a security vulnerability:

* Do **not** open a public issue
* Report privately to the maintainers via email: `security@kixi.org`

---

## 7. Acknowledgment

We appreciate your contributions. Following these guidelines ensures that Kixi remains secure, maintainable, and legally compliant.

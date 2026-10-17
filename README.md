<p align="center">
  <img src=".github/assets/kixi-logo.svg" alt="Kixi" width="260">
</p>

<p align="center">
  <strong>A plataforma angolana para preparar, aplicar e acompanhar provas.</strong><br>
  Gestão de enunciados, simulados, ranking entre escolas e um tutor com IA, num só lugar.
</p>

<p align="center">
  <a href="https://github.com/creative-mode/kixi/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/creative-mode/kixi/actions/workflows/ci.yml/badge.svg?branch=dev"></a>
  <img alt="Java 17" src="https://img.shields.io/badge/Java-17-1f5a2c">
  <img alt="Spring WebFlux" src="https://img.shields.io/badge/Spring-WebFlux-1f5a2c">
  <img alt="PostgreSQL" src="https://img.shields.io/badge/PostgreSQL-R2DBC-1f5a2c">
  <img alt="Licença" src="https://img.shields.io/badge/licen%C3%A7a-Apache%202.0%20%2B%20Commons%20Clause-1c2420">
</p>

---

## O que é o Kixi

O Kixi junta quem prepara as provas e quem as faz. Os professores montam enunciados à mão ou a partir de uma
fotografia, e um serviço de OCR lê a prova e preenche o construtor. Os alunos fazem simulados, recebem a nota,
conversam com um tutor que conhece o enunciado e comparam o seu desempenho com a turma, a escola e o país, sem
nunca expor os dados de outras escolas.

Este repositório contém o **backend** e o serviço de **OCR**. As aplicações web (aluno, gestor e landing) vivem
em [creative-mode/kixi-frontend](https://github.com/creative-mode/kixi-frontend) e estão incluídas aqui como
submódulo, na pasta [`frontend/`](frontend).

## Visão geral

```
                 ┌──────────────────────────────────────────────┐
                 │  Frontend (submódulo, repo kixi-frontend)    │
                 │  Landing  ·  App do aluno  ·  Gestor         │
                 └───────────────────────┬──────────────────────┘
                                         │ HTTPS, JWT
                                         ▼
 ┌───────────────────────────────────────────────────────────────────────┐
 │  services/backend-api   Spring Boot WebFlux                           │
 │  identity · academic · institutions · exams · examrooms · simulations │
 │  gamification · feed · chat · analytics · donations · ocr (ponte)     │
 └─────────────┬───────────────────────────────────────┬─────────────────┘
               │ R2DBC                                  │ HTTP + chave de API
               ▼                                        ▼
        ┌─────────────┐                       ┌─────────────────────────┐
        │ PostgreSQL  │                       │ services/ocr-service     │
        └─────────────┘                       │ FastAPI + PaddleOCR-VL   │
                                              └─────────────────────────┘
```

## Funcionalidades

- **Provas e enunciados.** Construtor com questões dissertativas, de escolha múltipla, verdadeiro ou falso e com
  alíneas. Importação por imagem com OCR, revisão assistida e gabarito.
- **Salas e simulados.** O professor cria uma sala para uma turma, os alunos fazem o simulado e a correcção
  automática usa o gabarito.
- **Tutor com IA.** Sessões de conversa limitadas a um enunciado, com resposta em streaming.
- **Ranking.** Por turma, escola, amigos e global, por média ou por XP. A comparação global é feita por percentil
  dentro da própria escola do aluno e exige consentimento e um mínimo de alunos por instituição
  ([ADR-0013](conceptual/adr/ADR-0013-cross-institution-leaderboard.md)).
- **Perfis e comunidade.** Perfis de aluno, professor e escola com selo de verificação, amigos, feed de publicações
  e visibilidade controlada por quem publica.
- **Estrutura académica.** Anos lectivos, períodos, cursos, disciplinas, turmas, matrículas e atribuições de ensino.
- **Segurança.** Autenticação com JWT e autorização por papéis (RBAC).

## Módulos do backend

Cada módulo é um pacote em `services/backend-api/src/main/java/ao/creativemode/kixi/`. As regras de dependência
entre eles são verificadas por
[`ArchitectureTest`](services/backend-api/src/test/java/ao/creativemode/kixi/architecture/ArchitectureTest.java),
por isso uma dependência indevida falha o build.

| Módulo | Responsabilidade | Endpoints principais |
| --- | --- | --- |
| `identity` | Contas, utilizadores, papéis, sessões, professores e autenticação JWT | `/api/v1/auth`, `/accounts`, `/users`, `/roles`, `/sessions`, `/teachers` |
| `academic` | Anos lectivos, períodos, disciplinas, cursos e turmas | `/api/v1/school-years`, `/terms`, `/subjects`, `/courses`, `/classes` |
| `institutions` | Instituições, matrículas, atribuições de ensino, perfis e verificação | `/api/v1/institutions`, `/enrollments`, `/teaching-assignments`, `/me`, `/profiles` |
| `exams` | Enunciados, questões, opções, gabaritos e imagens | `/api/v1/statements`, `/statements/manual`, `/questions`, `/question-images` |
| `examrooms` | Salas de prova de uma turma | `/api/v1/exam-rooms` |
| `simulations` | Simulados e respostas submetidas | `/api/v1/simulations`, `/simulation-answers` |
| `gamification` | Ranking, XP e consentimento de comparação | `/api/v1/leaderboard` |
| `feed` | Publicações e interacções | `/api/v1/feed/posts` |
| `chat` | Tutor com IA, por enunciado | `/api/v1/chat/sessions` |
| `analytics` | Indicadores de desempenho para professores | `/api/v1/analytics` |
| `donations` | Apoio ao projecto | `/api/v1/donations` |
| `ocr` | Ponte para o serviço de OCR | `/api/v1/ocr` |
| `shared` | Tratamento de erros, configuração e armazenamento | sem endpoints |

O contrato está publicado em OpenAPI (Swagger UI em `/swagger-ui.html`), e o guia
[API e OpenAPI](conceptual/architecture/implementation-guides/api.md) explica os fluxos principais.

## Arranque rápido

Precisa de Docker. O guia completo é o
[Arranque em 10 minutos](conceptual/architecture/implementation-guides/getting-started.md).

```bash
git clone --recurse-submodules https://github.com/creative-mode/kixi.git
cd kixi
cp .env.example .env   # defina APP_JWT_SECRET e OCR_API_KEY (mínimo 32 caracteres)
docker compose up --build
```

Verifique que tudo subiu:

```bash
curl http://localhost:8080/actuator/health   # backend
curl http://localhost:8000/health            # OCR
```

Swagger UI: <http://localhost:8080/swagger-ui.html>

Para correr só os testes do backend: `./mvnw -B test` dentro de `services/backend-api`.

## Frontend

A pasta `frontend/` é um submódulo Git que aponta para o repositório das aplicações web
([creative-mode/kixi-frontend](https://github.com/creative-mode/kixi-frontend)), fixado num commit do ramo `PRD`.
Contém a landing, a app do aluno e o gestor, servidas por um gateway único.

```bash
# num clone já existente:
git submodule update --init frontend
# mover o ponteiro para o frontend de produção mais recente:
git submodule update --remote frontend
```

Para correr as apps contra este backend, veja o README do frontend, secção «Contra o backend real».

## Estrutura do repositório

```
.
├── services/
│   ├── backend-api/     API Spring Boot WebFlux
│   └── ocr-service/     Serviço de OCR (FastAPI + PaddleOCR-VL)
├── frontend/            Submódulo: creative-mode/kixi-frontend
├── conceptual/          ADRs, modelo de dados, diagramas e guias
├── infra/               Deploy, Docker e testes de carga
├── .github/             CI, promoção entre ramos e agente de notas de versão
└── docker-compose.yml   Backend, OCR e base de dados locais
```

## Documentação

Tudo o que explica o porquê das decisões está em [`conceptual/`](conceptual):

- [`conceptual/adr/`](conceptual/adr): registos de decisão de arquitectura (ADR-0001 a ADR-0013), da estratégia de
  monorepo à comparação entre escolas.
- [`conceptual/architecture/implementation-guides/`](conceptual/architecture/implementation-guides): guias de
  implementação e de uso.
- [`conceptual/architecture/db/`](conceptual/architecture/db): modelo de entidades (UML e ER).
- [`conceptual/architecture/diagrams/`](conceptual/architecture/diagrams): diagramas de sistema e de processos.

O serviço de OCR tem o seu próprio [README](services/ocr-service/README.md).

## Fluxo de trabalho e entregas

O trabalho segue três ramos de longa duração e uma regra simples: tudo entra por pull request.

| Ramo | Para que serve |
| --- | --- |
| `dev` | Integração. Todo o trabalho novo parte daqui e volta para aqui. |
| `qua` | Qualidade. Recebe o que foi promovido de `dev` para validação. |
| `prod` | Produção. Cada promoção para aqui é uma versão. |

- Cada alteração começa num **issue** e numa branch `feat/<n>-…` ou `fix/<n>-…`, e o pull request fecha o issue com
  `Closes #<n>`. Um check recusa pull requests que não cumpram isto.
- O CI corre os testes do backend (com PostgreSQL), os do OCR e a higiene de texto. Um robô mantém aberto o pull
  request de promoção `qua` para `prod` e só faz merge com os checks verdes e a aprovação de quem tem escrita.
- A decisão de arquitectura por trás disto está no
  [ADR-0011](conceptual/adr/ADR-0011-github-workflow-and-ci-cd.md).

## Notas de versão

A cada promoção para `prod`, o agente de notas de versão (`.github/workflows/release-notes.yml`) recolhe os pull
requests que chegaram a este repositório e ao frontend desde a última versão, agrupa-os em novidades, correcções e
melhorias internas, e publica uma release no GitHub com a marca do Kixi. As versões seguem o formato
`AAAA.MM.DD`, com um sufixo `.n` quando há mais de uma no mesmo dia.

Para pré-visualizar as notas sem publicar, execute o workflow à mão com a opção «Simular».

## Contribuir

Leia o [`CONTRIBUTING.md`](CONTRIBUTING.md) para o estilo de código, os testes e as regras legais, e o
[`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) para a conduta esperada.

## Licença

O Kixi usa a **Apache License 2.0 com a restrição Commons Clause**. Pode usar, modificar e contribuir, mas **não
pode vender nem redistribuir** o projecto, nem obras derivadas, como produto autónomo ou serviço concorrente.
Os detalhes estão em [LICENSE](LICENSE).

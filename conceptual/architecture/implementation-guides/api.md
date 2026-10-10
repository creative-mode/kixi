# API e OpenAPI

O contrato HTTP do backend é publicado automaticamente pelo springdoc-openapi a
partir das anotações dos controllers. Esta página explica onde o encontrar, como
autenticar e como percorrer os fluxos principais.

---

## Onde está publicado

Com o `backend-api` a correr:

| Recurso | URL |
| --- | --- |
| Swagger UI (interactivo) | <http://localhost:8080/swagger-ui.html> |
| JSON OpenAPI | <http://localhost:8080/v3/api-docs> |

Ambos são públicos: não exigem autenticação, porque um mapa da API não expõe dados.
O esquema de autenticação é `bearerAuth` (JWT), configurado em
[`OpenApiConfig`](../../../services/backend-api/src/main/java/ao/creativemode/kixi/shared/config/OpenApiConfig.java).

### Desligar em produção

A documentação deve ficar acessível em desenvolvimento e escondida em produção.
Basta pôr as duas variáveis a `false` (ou omiti-las com esse valor):

```bash
SPRINGDOC_API_DOCS_ENABLED=false
SPRINGDOC_SWAGGER_UI_ENABLED=false
```

No `docker compose` estas variáveis já estão mapeadas a partir do `.env` raiz. Com
os dois `false`, os endpoints `/v3/api-docs` e `/swagger-ui/**` deixam de existir.

---

## Autenticação

Todos os pedidos protegidos usam o cabeçalho:

```
Authorization: Bearer <accessToken>
```

O token vem do registo (`POST /api/v1/auth/register`, cria sempre `STUDENT`) ou do
login. O `LoginResponse` devolve `accessToken`, `tokenType`, `expiresAt`,
`accountId` e `roles`.

## Formato de erro

Os erros seguem o **ProblemDetail** (RFC 7807), com `type`, `title`, `status`,
`detail` e `instance`. O corpo é consistente em toda a API, incluindo os `400` de
validação.

---

## Fluxo 1 — Autenticação

> Os ids usados nos exemplos (`1`, `2`, …) são ilustrativos. Os reais vêm dos
> enumeradores (`/api/v1/school-years`, `/api/v1/subjects`, `/api/v1/classes`, …)
> e do catálogo de enunciados.

```bash
HOST=http://localhost:8080

# Registo de aluno (papel STUDENT atribuído pelo servidor)
curl -s $HOST/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"aluno1","email":"aluno1@kixi.ao","password":"password123","firstName":"Ana","lastName":"Manuel"}'

# Login (devolve accessToken)
TOKEN=$(curl -s $HOST/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"aluno1","password":"password123"}' | jq -r .accessToken)

# Perfil do utilizador autenticado
curl -s $HOST/api/v1/me -H "Authorization: Bearer $TOKEN"
```

O fluxo Google OAuth2 começa em `GET /api/v1/auth/google` e volta a
`GET /api/v1/auth/google/callback`.

---

## Fluxo 2 — Enunciados

O `ADMIN`/`TEACHER` cria um enunciado manual (`POST /api/v1/statements/manual`);
os enunciados criados por OCR entram em revisão e são aprovados depois
(`POST /api/v1/statements/{id}/approve`). A leitura está aberta a qualquer conta
autenticada.

```bash
# Criar um enunciado manual (ADMIN ou TEACHER)
curl -s $HOST/api/v1/statements/manual \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
        "institutionId": 1,
        "subjectId": 1,
        "title": "Teste de Matemática — Funções",
        "examType": "TESTE",
        "durationMinutes": 90,
        "schoolYearId": 1,
        "visible": true,
        "questions": [
          {
            "text": "Qual é o domínio de f(x) = 1/x?",
            "maxScore": 2.0,
            "options": [
              {"label":"A","text":"ℝ","correct":false},
              {"label":"B","text":"ℝ \\ {0}","correct":true}
            ]
          }
        ]
      }'

# Catálogo visível ao aluno (autenticado)
curl -s "$HOST/api/v1/statements/catalog?classId=1&subjectId=1" \
  -H "Authorization: Bearer $TOKEN"

# Pesquisa por título
curl -s "$HOST/api/v1/statements/search?query=Matemática" \
  -H "Authorization: Bearer $TOKEN"
```

Endpoints de enumeradores, para consultares os ids usados acima:

```bash
curl -s $HOST/api/v1/school-years -H "Authorization: Bearer $TOKEN"
curl -s $HOST/api/v1/subjects     -H "Authorization: Bearer $TOKEN"
curl -s $HOST/api/v1/classes      -H "Authorization: Bearer $TOKEN"
```

---

## Fluxo 3 — Atribuições de docência

Só o `ADMIN` cria atribuições. O campo `tutorStyle` (máx. 100 caracteres) é o
estilo que o tutor de IA usa nas respostas daquela turma/disciplina. O professor
consulta as suas em `GET /api/v1/teaching-assignments/me`.

```bash
# Criar uma atribuição (ADMIN)
curl -s $HOST/api/v1/teaching-assignments \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"teacherId":1,"classId":1,"subjectId":1,"schoolYearId":1,"tutorStyle":"Explica passo a passo, com exemplos do dia-a-dia"}'

# As minhas atribuições (ADMIN ou TEACHER)
curl -s $HOST/api/v1/teaching-assignments/me -H "Authorization: Bearer $TEACHER_TOKEN"
```

---

## Fluxo 4 — Matrículas

O aluno matricula-se a si próprio (basta enviar `classId` e `schoolYearId`). Um
`ADMIN`/`TEACHER` pode matricular outra conta indicando `accountId`.

```bash
curl -s $HOST/api/v1/enrollments \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"classId":1,"schoolYearId":1}'

# Listar as minhas matrículas
curl -s $HOST/api/v1/enrollments -H "Authorization: Bearer $TOKEN"
```

---

## Fluxo 5 — Simulações

O aluno inicia uma simulação sobre um enunciado visível, submete respostas e
consulta o resultado. O gabarito só é devolvido quando a prova termina.

```bash
# Iniciar uma simulação
SIM=$(curl -s $HOST/api/v1/simulations \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"accountId":1,"statementId":1,"schoolYearId":1,"status":"IN_PROGRESS"}' | jq -r .id)

# Responder a uma questão
curl -s $HOST/api/v1/simulation-answers \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"simulationId\":$SIM,\"questionId\":1,\"selectedOptionId\":2}"

# Resultado (após terminar)
curl -s $HOST/api/v1/simulations/$SIM/result -H "Authorization: Bearer $TOKEN"
```

A rota de simulações existe em `/api/v1/simulations` e no alias legado
`/api/simulations`.

---

## Fluxo 6 — Tutor de IA

Cada sessão de chat está amarrada a **um** enunciado visível para o autor. O envio
de mensagens responde por **Server-Sent Events** (streaming).

```bash
# Abrir uma sessão sobre um enunciado
SESSION=$(curl -s $HOST/api/v1/chat/sessions \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"statementId":1}' | jq -r .id)

# Enviar uma mensagem e ler o stream
curl -N $HOST/api/v1/chat/sessions/$SESSION/messages \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"content":"Não percebi a questão 1. Podes explicar?"}'
```

O tutor responde apenas com base no enunciado da sessão. Sem `GROQ_API_KEY`
configurada, estes endpoints respondem `503`.

---

## Notas de manutenção

- Os caminhos reais de cada operação estão em `/v3/api-docs`; esta página segue os
  fluxos principais e pode simplificar DTOs.
- Ao acrescentar um controller, o springdoc publica-o automaticamente — não há
  lista manual de rotas para manter.
- O `SecurityConfig` é a fonte de verdade das regras de acesso por papel; os
  exemplos acima indicam o papel necessário em cada operação.

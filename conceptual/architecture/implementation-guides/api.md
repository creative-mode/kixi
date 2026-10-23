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

**`POST /api/v1/auth/login` responde `429`** com `Retry-After` quando a janela de
tentativas se esgota. A janela conta por endereço e por identidade ao mesmo tempo: 10
tentativas por 60 segundos para cada uma. Se a tabela de limites não responder, o login
responde `503` em vez de passar sem limite.

Atrás de um proxy reverso, o endereço que a aplicação vê é o do proxy e é partilhado por
todos. Para que o limite conte a pessoa e não o proxy, nomeia o proxy em
`APP_TRUSTED_PROXY_IPS`. Sem isso a cabeça `X-Forwarded-For` é ignorada, que é o
comportamento certo por omissão: um cabeçalho que qualquer um escreve não é prova de nada.

Em produção, a variável deve conter apenas os IPs dos pares que ligam diretamente ao
backend (por exemplo, Caddy e os contentores manager/aluno quando fazem pedidos server-side).
Não liste o IP público do visitante nem confie numa sub-rede partilhada com serviços não
proxy. O Caddy substitui os valores `X-Forwarded-*` não confiáveis; o manager e o aluno
validam a cadeia recebida e encaminham apenas o último salto. O backend também usa o último
salto quando o peer TCP é confiável. Se existir CDN ou outro proxy antes do Caddy, configure
essa topologia no próprio gateway e confirme os IPs reais antes de preencher a variável.
No perfil `prod`, uma lista vazia gera um aviso explícito no arranque; a aplicação continua
a ignorar o cabeçalho e a contar pelo peer TCP.

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

O aluno inicia uma simulação sobre um enunciado visível, submete respostas,
**termina a prova** e só depois consulta o resultado. O gabarito só é devolvido
quando a prova termina — e terminá-la tem um passo próprio, sem o qual o
`/result` responde `409`.

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

# Terminar a prova — sem este passo o /result responde 409
curl -s -X POST $HOST/api/v1/simulations/$SIM/submit \
  -H "Authorization: Bearer $TOKEN"

# Resultado (a prova já terminou)
curl -s $HOST/api/v1/simulations/$SIM/result -H "Authorization: Bearer $TOKEN"
```

O `submit` é o passo que fecha a prova e **devolve já o resultado**. Não é
idempotente: chamá-lo numa prova já terminada responde `409`, tal como o `/result`
numa prova por terminar.

**O estado da simulação não se escreve por `PUT`.** Mandar
`status: "FINISHED"` no `PUT /api/v1/simulations/{id}` é recusado com `400` e a
resposta diz porque:

```
Use POST /simulations/{id}/submit to finish a simulation
```

Pelo `PUT` só passa `CANCELLED`. Qualquer outro estado é `400`.

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

## Fluxo 7 — Aprendizagem social

O aluno compara o seu desempenho com o da comunidade no mesmo enunciado e,
se quiser, partilha a sua resolução — de forma anónima ou com nome. Nada é
partilhado sem um opt-in explícito, e a partilha pode ser revogada.

```bash
# Agregado da comunidade (entre instituições) + "tu vs. média"
curl -s $HOST/api/v1/statements/1/community -H "Authorization: Bearer $TOKEN"

# Partilhar a minha simulação terminada (anónima por omissão)
curl -s -X POST $HOST/api/v1/simulations/$SIM/share \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"anonymous":false,"displayName":"Ana"}'

# Resoluções partilhadas, as mais recentes primeiro (filtro opcional por escola)
curl -s "$HOST/api/v1/statements/1/community/solutions?institutionId=1" \
  -H "Authorization: Bearer $TOKEN"

# Revogar a partilha (a nota não é tocada)
curl -s -X DELETE $HOST/api/v1/simulations/$SIM/share \
  -H "Authorization: Bearer $TOKEN" -o /dev/null -w '%{http_code}\n'
```

Só simulações `FINISHED` com nota final entram no agregado (as que ainda têm
respostas em revisão ficam de fora até a correção terminar); respostas ainda em
revisão não contam como erro. A listagem lê apenas consentimentos ativos e
nunca expõe o gabarito, a conta por trás de uma partilha anónima — nem a escola
dela. Enquanto houver uma sala de exame não fechada para o enunciado, as duas
rotas de comunidade respondem `409`, pela mesma regra que esconde o gabarito
nos resultados; uma tentativa de sala só se partilha depois de a sala fechar.

---

## Fluxo 8 — Ranking relativo

O aluno compara-se com a turma, com a escola, com os amigos ou com alunos de
outras instituições. O cohort nunca vem na query: é resolvido a partir da
matrícula ativa de quem pergunta, pelo que nenhuma combinação de parâmetros
alcança o grupo de outra pessoa.

```bash
# A turma de quem pergunta (scope por omissão: class)
curl -s "$HOST/api/v1/leaderboard?scope=class&period=month" \
  -H "Authorization: Bearer $TOKEN"

# A escola de quem pergunta
curl -s "$HOST/api/v1/leaderboard?scope=school" \
  -H "Authorization: Bearer $TOKEN"

# Os amigos aceite de quem pergunta
curl -s "$HOST/api/v1/leaderboard?scope=friends" \
  -H "Authorization: Bearer $TOKEN"

# Autorizar (ou revogar) a comparação com outras instituições
curl -s -X PUT $HOST/api/v1/leaderboard/consent \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"crossInstitution":true}'

# Só depois de autorizado, e com matrícula ativa numa escola
curl -s "$HOST/api/v1/leaderboard?scope=global" \
  -H "Authorization: Bearer $TOKEN"
```

`period` é `all` (por omissão) ou `month`; `statementId` opcional isola um
enunciado. A resposta traz a posição e a média de quem pergunta, mais os vizinhos
imediatos e — em grupos com dez ou mais — o pódio. Um colega nunca leva o
`accountId`: um id estável numa resposta que existe para esconder quem é alguém
é um identificador à espera de ser cruzado com as rotas que nomeam pessoas.

`scope=global` é opt-in e responde `403` sem consentimento — uma tabela vazia
não distingue "recusei" de "ninguém fez nada", e um cliente que não distingue as
duas não consegue oferecer a opção que resolve. As quatro decisões de produto e
privacidade estão em [ADR-0013](../../adr/ADR-0013-cross-institution-leaderboard.md):

- **Consentimento.** Só entra quem consentiu, e a ausência de resposta conta como
  "não". `class`, `school` e `friends` publicam para pares e não são condicionados.
- **Percentil, não percentagem.** A ordenação é o `CUME_DIST` de cada aluno dentro
  da sua escola (0–100). A percentagem bruta desempata e é devolvida, mas não
  decide: provas e curvaturas diferentes não são a mesma escala.
- **Sem nomes.** Em `global` um par é `"Aluno de outra escola"` — os nomes nem
  chegam a ser lidos da base de dados. A resposta não traz escola, turma nem ano
  de mais ninguém, e `groupId` é `null` porque não há grupo por trás.
- **Escolas pequenas não entram.** Uma instituição só contribui com dez ou mais
  alunos consentidos com nota no ano letivo; abaixo disso é descartada inteira.
  Uma escola de três seria identificável por eliminação.

`institutionsCounted` diz de quantas escolas veio a comparação — é o que torna
uma tabela anónima legível em vez de sinistra.

---

## Notas de manutenção

- Os caminhos reais de cada operação estão em `/v3/api-docs`; esta página segue os
  fluxos principais e pode simplificar DTOs.
- Ao acrescentar um controller, o springdoc publica-o automaticamente — não há
  lista manual de rotas para manter.
- O `SecurityConfig` é a fonte de verdade das regras de acesso por papel; os
  exemplos acima indicam o papel necessário em cada operação.

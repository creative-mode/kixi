# Arranque em 10 minutos

Guia para pôr a plataforma Kixi a correr do zero numa máquina de desenvolvimento.
No fim tens o backend, o serviço de OCR e uma base de dados populada com os dados
de referência.

---

## Pré-requisitos

| Ferramenta | Versão | Verificar |
| --- | --- | --- |
| Docker Engine | 24+ | `docker --version` |
| Docker Compose | v2 | `docker compose version` |
| OpenSSL | qualquer | `openssl version` |

Portas usadas por omissão (todas configuráveis no `.env`):

| Porta | Serviço |
| --- | --- |
| `8080` | `backend-api` |
| `8000` | `ocr-service` |
| `5433` | PostgreSQL |
| `6379` | Redis (`--profile cache`) |
| `9000` / `9001` | MinIO (`--profile storage`) |

O `ocr-service` reserva cerca de 4 GB de memória — confirma que tens espaço livre
antes de arrancar.

---

## 1. Configurar o `.env`

```bash
cp .env.example .env
```

O `docker compose` recusa arrancar sem os dois segredos obrigatórios. Gera-os com:

```bash
openssl rand -hex 32   # para APP_JWT_SECRET
openssl rand -hex 32   # para OCR_API_KEY
```

| Variável | Obrigatória | Descrição |
| --- | --- | --- |
| `APP_JWT_SECRET` | ✅ | Segredo de assinatura dos JWT. **Mínimo 32 caracteres**; em produção o backend recusa arrancar sem ele. |
| `OCR_API_KEY` | ✅ | Chave que autentica o backend no `ocr-service`. |
| `APP_CORS_ALLOWED_ORIGINS` | — | Origens de browser autorizadas (lista separada por vírgulas). Por omissão, `localhost:3000,localhost:8080`. |
| `SPRINGDOC_API_DOCS_ENABLED` | — | Publica (`true`, omissão) ou esconde (`false`) o JSON OpenAPI. Desliga em produção. |
| `SPRINGDOC_SWAGGER_UI_ENABLED` | — | Publica (`true`, omissão) ou esconde (`false`) o Swagger UI. Desliga em produção. |
| `APP_BOOTSTRAP_ADMIN_*` | — | Cria o primeiro ADMIN no arranque (ver passo 2). |
| `GROQ_API_KEY` | — | Chave do tutor de IA. Sem ela, `/api/v1/chat` responde `503` e o resto da plataforma funciona normalmente. |

---

## 2. (Opcional) Criar o primeiro ADMIN

**Não há contas semeadas** — o `/api/v1/auth/register` público cria apenas contas
`STUDENT`, e o `POST /api/v1/accounts` é reservado a `ADMIN`. Para criares o
primeiro administrador sem tocar na base de dados, define as três variáveis
antes do primeiro arranque:

```bash
# em .env
APP_BOOTSTRAP_ADMIN_USERNAME=admin
APP_BOOTSTRAP_ADMIN_EMAIL=admin@kixi.ao
APP_BOOTSTRAP_ADMIN_PASSWORD=uma-senha-forte-e-unica
```

No arranque, o backend cria o ADMIN **apenas se ainda não existir nenhum**. As
regras (implementadas na issue #160) são:

- não cria nada se já houver um ADMIN ou se faltar alguma das três variáveis;
- não tem senha por omissão;
- recusa senhas fracas em produção.

> ⚠️ Depois do primeiro arranque, **remove a senha do ambiente** e muda-a no
> gestor de credenciais. O bloco no `.env.example` já está comentado por isso.

Se preferires, deixa as variáveis por definir e cria o ADMIN mais tarde; a
plataforma arranca na mesma.

---

## 3. Subir a plataforma

```bash
docker compose up --build
```

O Compose espera que o PostgreSQL e o OCR estejam saudáveis antes de arrancar o
backend, por isso o primeiro arranque demora **1 a 2 minutos** (o OCR descarrega
os modelos na primeira vez). Os serviços por omissão são `postgres`,
`ocr-service` e `backend-api`.

Para subires também o Redis e/ou o MinIO (S3 local):

```bash
docker compose --profile cache --profile storage up --build
```

---

## 4. Verificar

```bash
curl http://localhost:8080/actuator/health   # backend       → {"status":"UP"}
curl http://localhost:8000/health            # ocr-service   → {"status":"healthy", ...}
```

Documentação interativa da API:

- Swagger UI → <http://localhost:8080/swagger-ui.html>
- JSON OpenAPI → <http://localhost:8080/v3/api-docs>

Os detalhes do contrato estão em [api.md](api.md).

---

## 5. Dados semeados

As migrações Flyway (`V1` … `V33`) correm automaticamente no arranque do backend.
Das migrações de referência ficas com:

| Dados | Origem | Conteúdo |
| --- | --- | --- |
| Papéis | `V17` | `ADMIN`, `TEACHER`, `STUDENT` |
| Trimestres | `V17` | 1.º, 2.º e 3.º trimestre |
| Disciplinas | `V17` | Matemática, Português, Física, Química, Biologia, História, Geografia, Inglês, Filosofia |
| Ano lectivo | `V17` | 2024/2025 |
| Instituição | `V26` | ITEL — Instituto de Telecomunicações (com todas as disciplinas associadas) |

Não há **contas** semeadas: o primeiro ADMIN vem do passo 2 e os alunos registam-se
pelo endpoint público.

---

## 6. Primeiro pedido

Registar um aluno (papel `STUDENT` atribuído pelo servidor), autenticar e consultar
o próprio perfil:

```bash
# Registo (devolve logo um token)
curl -s http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"aluno1","email":"aluno1@kixi.ao","password":"password123","firstName":"Ana","lastName":"Manuel"}'

# Login
curl -s http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"usernameOrEmail":"aluno1","password":"password123"}'

# Perfil (usa o accessToken devolvido acima)
curl -s http://localhost:8080/api/v1/me \
  -H "Authorization: Bearer $TOKEN"
```

Os restantes fluxos (enunciados, atribuições, matrículas e tutor) estão em
[api.md](api.md).

---

## Comandos úteis

```bash
docker compose logs -f backend-api     # acompanhar o backend
docker compose ps                      # estado e saúde dos serviços
docker compose down                    # parar (mantém os dados)
docker compose down -v                 # parar e apagar a base de dados (reset total)
docker compose up --build backend-api  # reconstruir só o backend
```

### Problemas comuns

- **`APP_JWT_SECRET must be at least 32 characters`** — gera um segredo maior com
  `openssl rand -hex 32` e actualiza o `.env`.
- **`port is already allocated`** — muda `BACKEND_PORT`, `OCR_PORT` ou
  `POSTGRES_PORT` no `.env`.
- **`/api/v1/chat` responde 503** — falta `GROQ_API_KEY`; é opcional, o resto da
  plataforma continua a funcionar.
- **Migrações falham num volume antigo** — o `init.sql` legado é baselined em
  `V16`; para começar limpo, `docker compose down -v` e sobe de novo.

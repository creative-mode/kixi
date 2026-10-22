# Implementation Guide: Authentication and Authorization (JWT + RBAC + Google OAuth2)

**Status:** Implemented; keep this guide aligned with the running API contract.

Sistema de autenticação e autorização seguro com JWT, RBAC (Role e AccountRole) e login via Google OAuth2.

---

## 1. Visão geral

- **Login tradicional:** `POST /api/v1/auth/login` (username ou email + password) → valida contra `Account` (BCrypt), carrega roles via `AccountRole`, emite JWT.
- **Login Google:** utilizador é redirecionado para o Google → callback com `code` → backend troca por access token, exige e-mail verificado e o `sub` estável do provedor, encontra a conta pelo `googleSub` ou por e-mail local verificado, cria `Account`/`User` quando necessário e emite o nosso JWT.
- **Rotas:** login, registo e início do OAuth são públicos; `POST /api/v1/auth/google/link/authorize` é privado e exige bearer token, password local e state OAuth curto, assinado e ligado à conta.

---

## 2. Fluxos

### 2.1 Login tradicional

1. Cliente envia `POST /api/v1/auth/login` com `{ "usernameOrEmail": "...", "password": "..." }`.
2. Backend resolve account por username ou email, verifica password com BCrypt, verifica conta ativa.
3. Carrega roles do account via `AccountRole` + `Role`.
4. Gera JWT com claims: `sub` (accountId), `roles` (lista de nomes), `exp` (expiração).
5. Resposta: `{ "accessToken": "...", "tokenType": "Bearer", "expiresAt": "...", "accountId": 1, "roles": ["ADMIN"] }`.

### 2.2 Login Google OAuth2

1. Cliente acede a `GET /api/v1/auth/google` → backend redireciona para Google (URL de autorização com `client_id`, `redirect_uri`, `scope=openid email profile`).
2. Utilizador autentica-se no Google; Google redireciona para `GET /api/v1/auth/google/callback?code=...`.
3. Backend troca `code` por access_token (POST ao token endpoint do Google com client_id, client_secret, code, redirect_uri).
4. Backend chama userinfo do Google e exige `email_verified`, `email` válido e `sub` não vazio.
5. O backend procura primeiro por `googleSub`. Uma conta encontrada mas inativa falha com 401; só uma pesquisa vazia permite fallback ao e-mail local verificado.
6. Se não existir: cria `Account` com `googleSub`, username derivado sem reutilizar usernames soft-deleted, password aleatório calculado fora da thread reativa, cria `User` e atribui a role padrão via `AccountRole`.
7. Emite JWT (mesmo formato do login tradicional) e devolve JSON no body.

### 2.3 Ligação explícita de uma conta existente

1. O cliente envia `POST /api/v1/auth/google/link/authorize` com bearer token e password local.
2. O backend verifica a conta autenticada, aplica o limite de endereço/identidade e emite um state assinado, curto e account-bound.
3. O callback valida o state, o e-mail Google verificado e o `sub`; exige que o e-mail Google coincida com o e-mail local, liga a identidade apenas à conta autenticada e nunca muda o e-mail local.

---

## 3. Componentes

| Componente | Responsabilidade |
|------------|------------------|
| **JwtService** | Gerar JWT (accountId, roles, exp), validar token, extrair claims. |
| **AuthController** | Endpoints: login, registo, redirect/callback Google e início da ligação explícita. |
| **AuthService** | Validar credenciais (username/email + password), carregar roles, orquestrar login e callback Google. |
| **SecurityWebFilterChain** | Permitir as rotas públicas de auth, exigir autenticação em `/api/v1/auth/google/link/authorize` e no resto da API; filtro que lê `Authorization: Bearer <token>`, valida JWT e preenche SecurityContext (principal = accountId, authorities = roles). |
| **RBAC** | Em endpoints protegidos: ler roles do SecurityContext; opcionalmente `@PreAuthorize("hasRole('ADMIN')")` ou verificação manual. |

---

## 4. Entidades envolvidas

- **Account:** username, email, `googleSub` (único quando presente), passwordHash, emailVerified, active, lastLogin (já existente).
- **User:** accountId, firstName, lastName, photo (já existente; perfil ligado ao Account).
- **Role / AccountRole:** N:N já implementado; roles do account usados no JWT e na autorização.

---

## 5. Configuração

- **application.properties / env:**  
  - `jwt.secret` (base64 ou string), `jwt.expiration-ms`  
  - `google.client-id`, `google.client-secret`, `app.auth.google.redirect-uri` (ex.: `http://localhost:8080/api/v1/auth/google/callback`)
- **Google Cloud Console:** criar credenciais OAuth 2.0 (tipo “Web application”), definir redirect URI igual ao configurado.

---

## 6. Rotas públicas vs privadas

- **Públicas (sem JWT):**  
  - `POST /api/v1/auth/login`  
  - `GET /api/v1/auth/google` (redirect)  
  - `GET /api/v1/auth/google/callback`  
  - `POST /api/v1/auth/google/link/authorize` exige JWT
  - Opcional: health, actuator, docs.
- **Privadas:** todas as outras sob `/api/v1/**`; filtro JWT obrigatório; RBAC por endpoint conforme necessário.

---

## 7. Erros

- **401 Unauthorized:** token em falta, inválido ou expirado.
- **403 Forbidden:** token válido mas sem permissão (role insuficiente).
- Mensagens claras em JSON (ex.: ProblemDetail) para o cliente.

---

## 8. Testes

- Unitários: JwtService (gerar/validar), AuthService (validação de password, carga de roles).
- Integração: login tradicional, callback Google (mock do token endpoint e userinfo), conta Google inativa, colisão com username soft-deleted, ligação sem token e acesso a rota protegida com/sem token e com/sem role.
- Cenários: token expirado (401), role insuficiente (403).

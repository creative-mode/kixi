# Implementation Guide: Authentication and Authorization (JWT + RBAC + Google OAuth2)

**Status:** Proposed for implementation

Sistema de autenticação e autorização seguro com JWT, RBAC (Role e AccountRole) e login via Google OAuth2.

---

## 1. Visão geral

- **Login tradicional:** `POST /api/v1/auth/login` (username ou email + password) → valida contra `Account` (BCrypt), carrega roles via `AccountRole`, emite JWT.
- **Login Google:** utilizador é redirecionado para o Google → callback com `code` → backend troca por access token, obtém email/nome, encontra ou cria `Account` e `User`, atribui role padrão se novo → emite o nosso JWT.
- **Rotas:** públicas = `/api/v1/auth/**`; privadas = resto da API, com filtro JWT e opcionalmente RBAC por endpoint.

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
4. Backend chama userinfo do Google (email, name) e procura `Account` por email.
5. Se não existir: cria `Account` (email, username derivado do email, sem password ou password aleatório), cria `User` (firstName/lastName a partir do name), atribui role padrão (ex.: USER) via `AccountRole`.
6. Se existir: garante que tem pelo menos um role.
7. Emite JWT (mesmo formato do login tradicional) e devolve (ex.: redirect para frontend com token em query ou cookie, ou JSON no body).

---

## 3. Componentes

| Componente | Responsabilidade |
|------------|------------------|
| **JwtService** | Gerar JWT (accountId, roles, exp), validar token, extrair claims. |
| **AuthController** | Endpoints: login, redirect Google, callback Google. |
| **AuthService** | Validar credenciais (username/email + password), carregar roles, orquestrar login e callback Google. |
| **SecurityWebFilterChain** | Permitir `/api/v1/auth/**`, exigir autenticação no resto; filtro que lê `Authorization: Bearer <token>`, valida JWT e preenche SecurityContext (principal = accountId, authorities = roles). |
| **RBAC** | Em endpoints protegidos: ler roles do SecurityContext; opcionalmente `@PreAuthorize("hasRole('ADMIN')")` ou verificação manual. |

---

## 4. Entidades envolvidas

- **Account:** username, email, passwordHash, emailVerified, active, lastLogin (já existente).
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
- Integração: login tradicional, callback Google (mock do token endpoint e userinfo), acesso a rota protegida com/sem token e com/sem role.
- Cenários: token expirado (401), role insuficiente (403).

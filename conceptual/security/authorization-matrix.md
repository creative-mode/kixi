# Matriz de autorização da API

Esta matriz descreve as regras de `SecurityConfig`. As verificações de propriedade e de âmbito continuam a ser feitas pelos serviços, com a conta do token autenticado; um papel autorizado não dá acesso automático a dados de outros alunos, turmas ou escolas.

| Área / operações | Anónimo | STUDENT | TEACHER | ADMIN |
| --- | --- | --- | --- | --- |
| Login, registo e OAuth (`/api/v1/auth/**`) | Permitido | Permitido | Permitido | Permitido |
| Health e documentação OpenAPI | Permitido | Permitido | Permitido | Permitido |
| Contas, utilizadores, papéis, sessões e gestão de professores (exceto `GET /teachers/me`) | Negado | Negado | Negado | Permitido |
| Perfil próprio, instituições próprias e amizades | Negado | Autorizado, com âmbito da própria conta | Autorizado, com âmbito da própria conta | Autorizado |
| Catálogos académicos: leitura ativa | Negado | Autenticado | Autenticado | Autorizado |
| Lixeira dos catálogos e imagens | Negado | Negado | Permitido | Permitido |
| Catálogos académicos: escrita | Negado | Negado | Permitido | Permitido |
| Enunciados: leitura | Negado | Visíveis para o aluno | Inclui pendentes e respeita atribuições | Permitido |
| Enunciados, perguntas, opções e imagens: escrita/moderação | Negado | Negado | Permitido | Permitido |
| Simulações e respostas: leitura | Negado | Próprias | Autenticado; âmbito validado pelo serviço | Permitido |
| Submeter simulação / resposta | Negado | Permitido | Permitido | Permitido |
| Corrigir respostas, recuperar ou purgar registos | Negado | Negado | Permitido | Permitido |
| Salas de prova: entrar e consultar a própria prova | Negado | Permitido | Autenticado, sujeito ao âmbito da sala | Permitido |
| Salas de prova: gerir, transmitir ao vivo e consultar notas | Negado | Negado | Permitido | Permitido |
| Analytics e moderação do feed | Negado | Negado | Permitido | Permitido |
| Ranking, feed, partilhas e amizades | Negado | Autenticado, com âmbito da conta | Autenticado, com âmbito da conta | Permitido |
| Rota sem regra explícita | Negado | Negado | Negado | Negado |

## Leitura de folhas de resposta (`POST /api/v1/ocr/answer-sheets`)

Multipart com `statementId` e `files` (até 10). Só quem pode **escrever o enunciado** (`StatementWriteAccessService`: administrador, o professor da turma e da disciplina, ou a conta `INTEGRATION`) o chama; o aluno recebe 403. A estrutura que o OCR procura (questões, tipos, letras) sai do enunciado e não do pedido. Devolve o contrato v1 do OCR (`sheets`, `warnings`, confiança por campo): o que foi lido, nunca uma nota. Tem o mesmo limite de uploads por conta que o resto do OCR.

## Papel `INTEGRATION` (serviços)

Papel para serviços que falam com o Kixi, hoje o motor SchoolOps. Em vez de `ADMIN`, uma conta `INTEGRATION` só chega à lista fixa de `IntegrationScope`; tudo o resto responde 403 antes de qualquer outra regra, e o papel não se soma a outros (um token com `INTEGRATION` e `ADMIN` vale só `INTEGRATION`). Dentro da lista a conta vê o que um administrador veria.

| Pode | Caminhos |
|---|---|
| Ler | `GET` simulações (lista, uma, resultado), respostas, enunciado completo e questões, anos letivos (por id), disciplinas, salas próprias e as suas notas, alunos de uma turma (analytics) |
| Escrever | `PATCH` da pontuação de uma resposta; criar, atualizar e submeter simulações e respostas; fechar uma sala; `POST /ocr/answer-sheets` (ler as folhas de uma prova em papel) |
| Não pode | contas, instituições, apagar ou purgar, lixeiras, editar enunciados, o resto da API e `/actuator` |

A autenticação continua a ser por login com palavra-passe (o JWT expira); trocar por um token de serviço próprio fica fora deste âmbito.

## Casos cobertos pelos testes

`AuthorizationIntegrationTest` cobre pedidos anónimos, STUDENT, TEACHER e ADMIN em rotas de leitura e escrita, incluindo leitura de simulações e respostas do próprio aluno, bloqueio de operações administrativas, acesso a salas de prova e uploads OCR. O teste `deniesUnmappedRoutesToEveryAuthenticatedRole` garante que uma rota nova sem regra explícita não fica acessível por herança ao papel autenticado.

O teste `rejectsStudentWritesAndTrashReadsAcrossAcademicCatalogs` bloqueia escrita de catálogos e leitura das respetivas lixeiras por STUDENT.

Os testes `QuestionWriteAuthorizationTest`, `StatementTeacherScopeTest`, `EnrollmentSecurityTest` e `StatementApprovalAuthorizationTest` exercitam autorização e âmbito nos respetivos módulos.

## Proteções de abuso e dados

- Login: limite persistente por endereço de origem; não bloqueia uma identidade específica, para impedir que terceiros bloqueiem a conta de uma vítima. A chave é guardada como SHA-256, nunca como email ou endereço IP.
- Registo: limite persistente por endereço de origem; a identidade do email não é bloqueada por tentativas de terceiros.
- Chat: limite persistente por conta; uploads OCR e de imagens: limite persistente por conta. As respostas de limite incluem `Retry-After`.
- Login tradicional e OAuth (`/google` e `/google/callback`) têm limite por endereço de origem. Registo e uploads também têm limite persistente; chaves guardadas na base de dados são derivadas por SHA-256.
- OCR limita quantidade e extensão de ficheiros, cada upload a 20 MiB, o conjunto de ficheiros por pedido a 50 MiB, cada PDF a 50 páginas e cada pedido a 50 páginas processadas. O upload de imagens de questões valida tipo real, conteúdo e tamanho antes de persistir.
- JWT, chaves de serviços e credenciais de base de dados são configuração de ambiente. O perfil `prod` recusa segredo JWT ausente, curto ou reconhecido como placeholder. O Compose não fornece uma palavra-passe de base de dados por omissão.
- Logs não devem conter tokens, passwords, emails, nomes de utilizador, nomes originais de ficheiros ou IDs de conta. Usar request ID e tipo de erro para correlacionar incidentes.

## CORS efetivo (auditado em 28/10/2026)

Definido por `CorsConfig` a partir de `APP_CORS_ALLOWED_ORIGINS` (lista separada por vírgulas, sem curingas): só as origens listadas recebem `Access-Control-Allow-Origin`, com `allowCredentials=true`, métodos `GET, POST, PUT, PATCH, DELETE, OPTIONS`, cabeçalhos `Authorization, Content-Type, X-Request-Id` e `max-age` de 1 h. Lista vazia significa só mesma origem.

Em produção o valor é `https://kixi.creativemode.ao` (uma origem exata, lida do contentor `backend-api`). Está certo: como há credenciais, nunca se usa `*`, e as outras apps (aluno e gestor) falam com o backend a partir do servidor Next, não do navegador. Cobertura: `CorsSecurityIntegrationTest`. Ao acrescentar um domínio, acrescentá-lo à variável, nunca relaxar o código.

## Isolamento entre alunos

`StudentIsolationPostgresIntegrationTest` percorre, com o aluno A, cada rota de simulações e respostas sobre recursos do aluno B (ler, alterar, submeter, apagar, avaliar) e exige 401, 403 ou 404 sem alterar nada. Encontrou que uma simulação livre de outro aluno respondia 400 ("Id must not be null") em vez de 404, o que permitia distinguir ids existentes de inexistentes; corrigido em `SimulationService.authorize`. Uma rota nova entra na lista de `routesOfStudentB`.

## Revisão de dependências

Em 14/10/2026, `pip-audit -r services/ocr-service/requirements.txt` não encontrou vulnerabilidades conhecidas após atualizar o serviço OCR para PaddleOCR 3.7.0/PaddlePaddle 3.3.1 e atualizar as dependências com avisos corrigidos. A dependência `python-jose` foi removida do OCR porque não era usada; autenticação desse serviço usa API key.

`./mvnw -f services/backend-api/pom.xml versions:display-dependency-updates` foi executado para listar atualizações disponíveis. Esse comando não é um scanner de CVEs, por isso as versões maiores do Spring não foram atualizadas sem uma análise de compatibilidade e de avisos específicos.

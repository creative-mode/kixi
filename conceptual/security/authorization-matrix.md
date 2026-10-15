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

## Revisão de dependências

Em 14/10/2026, `pip-audit -r services/ocr-service/requirements.txt` não encontrou vulnerabilidades conhecidas após atualizar o serviço OCR para PaddleOCR 3.7.0/PaddlePaddle 3.3.1 e atualizar as dependências com avisos corrigidos. A dependência `python-jose` foi removida do OCR porque não era usada; autenticação desse serviço usa API key.

`./mvnw -f services/backend-api/pom.xml versions:display-dependency-updates` foi executado para listar atualizações disponíveis. Esse comando não é um scanner de CVEs, por isso as versões maiores do Spring não foram atualizadas sem uma análise de compatibilidade e de avisos específicos.

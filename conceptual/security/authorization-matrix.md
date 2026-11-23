# Matriz de autorização da API

Esta matriz descreve as regras de `SecurityConfig` e de `IntegrationScope`. As verificações de propriedade e de âmbito continuam a ser feitas pelos serviços, com a conta do token autenticado; um papel autorizado não dá acesso automático a dados de outros alunos, turmas ou escolas.

Perfis: `COORDINATOR` é um papel que se soma a `STUDENT` (a conta coordenadora também é aluna, por isso herda o que o aluno pode e não ganha mais nada fora de `/term-plans` e `/coordination`); `INTEGRATION` é a conta de serviço do motor SchoolOps e só chega à lista fixa de `IntegrationScope` (`services/backend-api/src/main/java/ao/creativemode/kixi/identity/security/IntegrationScope.java`), ver a secção «Papel `INTEGRATION`». «403» na coluna `INTEGRATION` é o que lhe responde fora dessa lista, mesmo em rotas abertas ao anónimo.

| Área / operações | Anónimo | STUDENT | TEACHER | COORDINATOR | ADMIN | INTEGRATION |
| --- | --- | --- | --- | --- | --- | --- |
| Login, registo e OAuth (`/api/v1/auth/**`) | Permitido | Permitido | Permitido | Permitido | Permitido | 403 |
| Health e documentação OpenAPI (só o anónimo; com token de `INTEGRATION` é 403) | Permitido | Permitido | Permitido | Permitido | Permitido | 403 |
| Contas, utilizadores, papéis, sessões e gestão de professores (exceto `GET /teachers/me`; `GET /teachers/me/overview` e `PUT /teaching-assignments/*/tutor-style` são `TEACHER` e `ADMIN`) | Negado | Negado | Negado | Negado | Permitido | Negado |
| Perfil próprio, instituições próprias e amizades | Negado | Autorizado, com âmbito da própria conta | Autorizado, com âmbito da própria conta | Autorizado, com âmbito da própria conta | Autorizado | Negado |
| Catálogos académicos: leitura ativa | Negado | Autenticado | Autenticado | Autenticado | Autorizado | Só o que o motor lê (anos letivos por id, disciplinas) |
| Lixeira dos catálogos académicos (`/{school-years,terms,subjects,courses,classes}/trash`) | Negado | Negado | Permitido | Negado | Permitido | Negado |
| Lista e lixeira das imagens de questões (`GET /question-images`, `/question-images/trash`) | Negado | Negado | Negado | Negado | Permitido | Negado |
| Catálogos académicos: escrita (anos, trimestres, disciplinas, cursos, turmas; kixi#253) | Negado | Negado | Negado | Negado | Permitido | Negado |
| Enunciados, questões e opções: leitura (com o gabarito `isCorrect`; kixi#174) | Negado | Visíveis para o aluno, sem gabarito | Inclui pendentes e lixeira, só da turma e disciplina que leciona (403 fora disso, como `GET /statements/{id}`) | Como o aluno | Permitido | Enunciado completo e questões |
| Enunciados, perguntas, opções e imagens: escrita/moderação | Negado | Negado | Permitido, só da turma e disciplina que leciona | Negado | Permitido | Negado |
| Simulações e respostas: leitura | Negado | Próprias | Autenticado; âmbito validado pelo serviço | Próprias | Permitido | Leitura (simulações, respostas) |
| Submeter simulação / resposta | Negado | Permitido | Permitido | Permitido | Permitido | Abre e entrega em nome do aluno |
| Corrigir respostas, recuperar ou purgar registos | Negado | Negado | Permitido (não nas provas de um plano) | Negado | Permitido (não nas provas de um plano, ver «Correção pela IA») | `PATCH …/grade` e `PUT …/ai-grade`; não apaga nem purga |
| Salas de prova: entrar e consultar a própria prova | Negado | Permitido | Autenticado, sujeito ao âmbito da sala | Permitido | Permitido | Negado |
| Salas de prova: gerir, transmitir ao vivo e consultar notas | Negado | Negado | Permitido | Negado (lê as da instituição em `/coordination/exam-rooms`) | Permitido | Fechar sala, `GET /exam-rooms/owned` e `…/grades` |
| Analytics e moderação do feed | Negado | Negado | Permitido | Negado | Permitido | Só os alunos de uma turma (analytics) |
| Ranking, feed, partilhas e amizades | Negado | Autenticado, com âmbito da conta | Autenticado, com âmbito da conta | Autenticado, com âmbito da conta | Permitido | Negado |
| Rota sem regra explícita | Negado | Negado | Negado | Negado | Negado | Negado |

## Registo escolar (`/api/v1/school-register/**`)

Notas por prova, reclamações e pautas trimestrais que o motor SchoolOps escreve e lê (kixi#501). Só `ADMIN` e a conta `INTEGRATION` (dentro do `IntegrationScope`); professor e aluno recebem 403, e a sua leitura é do ecrã de pautas. Todas as escritas são idempotentes por chave; uma pauta `FECHADA` ou `ANULADA` não reabre.

## Plano do trimestre e coordenador (`/api/v1/term-plans/**`, `/api/v1/institutions/{id}/coordinators/**`) (kixi#523, kixi#513)

| Papel | Plano do trimestre | Coordenadores de uma instituição |
|---|---|---|
| `ADMIN` | tudo | atribui e retira |
| `TEACHER` | cria, lê e edita **só** os planos da turma e disciplina que leciona (par exato); marca uma MAC sem sala como concluída; **conclui o agendamento** (`POST …/conclude`, pede P1 e P2 com enunciado) e com isso a pauta abre no motor; não aprova nem dispensa | 403 |
| `COORDINATOR` | lê os planos da **sua instituição** (tabela `institution_coordinators`), aprova, dispensa uma avaliação de MAC com motivo e **reagenda** a P1 ou a P2 e também conclui o agendamento (a P1 e a P2 não se dispensam: repetem-se, kixi#534; só antes de a pauta abrir); não edita datas nem liga salas; lê também as pautas da instituição (`/term-sheets`) | 403 |
| `STUDENT`, `INTEGRATION` | 403 | 403 |

O estado de cada avaliação é derivado do enunciado, da sala e das respostas; a pauta abre quando o professor (ou o coordenador) conclui o agendamento, cada prova P1/P2 é encerrada no motor quando a sua sala fecha ou a data passa, e `readyForPauta` só indica que tudo está corrigido. O vigilante não tem papel: é um professor. Um plano que não se pode ler dá 403 (não revela planos de outras turmas).

## Salas da instituição para o coordenador (`GET /api/v1/coordination/exam-rooms`, `/{id}/grades`) (kixi#529)

Só leitura. `COORDINATOR`: as salas das turmas da(s) instituição(ões) que coordena (estado, professor, entradas, concluídas, respostas por rever) e as notas por aluno; `ADMIN`: todas; os outros papéis e a conta de integração: 403. Uma sala que não existe e uma de outra instituição dão ambas 403. O coordenador não fecha salas nem corrige: isso continua a ser do professor dono da sala.

## Visibilidade dos enunciados de provas de sala (kixi#519)

Um enunciado ligado a uma sala **não é visível ao aluno** até todas as salas dele estarem `CLOSED` e não restar nenhuma resposta `PENDING_REVIEW` nas simulações dessas salas; só então volta a `visible` (a intenção de publicar fica em `statements.release_after_exam`). O professor pode mostrá-lo mais cedo (`visible = true` acaba a retenção). Garantido por triggers na base de dados, por isso vale para qualquer via de escrita. As salas não dependem de `visible`: o aluno da sala continua a fazer a prova. Enunciados sem sala (treino, donativos) não mudam.

## Re-correção de uma resposta (`PATCH /api/v1/simulation-answers/{id}/grade?rectify=true`)

Mesma autorização da correção (administrador, professor atribuído à turma e disciplina, conta de integração). Com `rectify=true` só aceita respostas **já corrigidas** (por uma pessoa, pela IA ou automaticamente, como uma objetiva que uma reclamação deferida corrige; 409 se ainda pendentes) e, sem ele, só as pendentes: uma rectificação nunca passa por primeira correção nem o contrário. Nunca acima da cotação da questão; a nota final da simulação é recalculada (kixi#509). Uma resposta corrigida pela IA ou automaticamente e depois rectificada passa a `GRADED`. A rectificação de uma resposta **corrigida automaticamente** (uma objetiva) é só da conta de integração (o motor, ao deferir uma reclamação) e do administrador: o professor recebe 409 nessas, e continua a poder rectificar as corrigidas por uma pessoa ou pela IA.

## Correção pela IA das provas de um plano (kixi#542)

As respostas abertas das salas ligadas a um plano do trimestre (`term_assessments.room_id`) são corrigidas pela IA no fluxo do motor SchoolOps, não pelo professor:

- `PUT /api/v1/simulation-answers/{id}/ai-grade` (`{scoreObtained, feedback, level?, confidence?}`): **só `ADMIN` e `INTEGRATION`** (regra explícita antes da genérica, que deixaria passar o professor). Grava a nota, a observação, o nível e a confiança e põe a resposta em `AI_GRADED`; repetir o mesmo corpo não altera nada; 409 se a resposta já foi corrigida por uma pessoa (`GRADED`) ou é objetiva; nunca acima da cotação.
- `PATCH …/grade` (sem `rectify`) e `POST /exam-rooms/{id}/grade` (correção em massa) **recusam com 409** nas respostas e salas de um plano, para professor e administrador. A conta de integração e a rectificação (`?rectify=true`) não são afetadas. Salas livres e simulações dos alunos mantêm o comportamento anterior.
- `GET /exam-rooms/{id}/review` devolve também as respostas `AI_GRADED` com a observação, o nível e a confiança, e `planLinked`: o ecrã do professor mostra-as só para leitura. O aluno vê a observação da IA depois de a prova estar corrigida (mesma regra de visibilidade da nota).

## Leitura das pautas (`GET /api/v1/term-sheets`, `GET /api/v1/term-sheets/{sheetId}`, `GET /api/v1/me/term-sheets`)

O motor escreve as pautas em `/school-register` (só `ADMIN` e `INTEGRATION`); estas rotas são a **leitura** para quem as consulta (kixi#506). O acesso decide-se assim:

| Papel | `GET /term-sheets` e `/{sheetId}` | `GET /me/term-sheets` |
|---|---|---|
| `ADMIN` | todas as pautas (`REGISTADA` e `FECHADA`); uma que não existe dá 404 | 200 com a lista vazia (ver abaixo) |
| `TEACHER` | só as pautas da **turma e da disciplina** que leciona (`teaching_assignments`, o par exato, como a leitura de enunciados); o que não pode ler e o que não existe dão ambos 403, para não revelar que pautas há | 403 |
| `STUDENT`, `COORDINATOR` | 403 | só a **sua** linha (MAC, P1, P2, MT, estado), só de pautas `FECHADA`: nada de outros alunos nem de estatísticas da turma |
| `INTEGRATION` | 403 (fora do âmbito do motor, ver `IntegrationScope`) | 403 |

`GET /me/term-sheets` exige o papel `STUDENT`, que toda a conta registada tem (o `ADMIN` e o `COORDINATOR` também; um professor sem `STUDENT` recebe 403): é uma leitura **da própria conta**, por isso quem não é aluno de nenhuma pauta recebe `200 []`, e nunca linhas alheias. O código mantém-se assim (nada a proteger) e é a documentação que se alinha.

O campo `dispensed` (`dispensed` em `GET /term-sheets/{id}` e `row.dispensed` em `GET /me/term-sheets`) é uma lista de textos, por esta ordem: `P1` e `P2` se a avaliação do plano da pauta foi dispensada, e `MAC` quando o plano tem avaliações de MAC e **todas** estão dispensadas (a mesma condição com que o Kixi envia `macDispensada` ao motor); distingue uma componente dispensada (nota em branco) de uma falta. Uma MAC por dispensar, ou um plano sem MAC, não a inclui.

Uma pauta `ANULADA` não se mostra a ninguém. A turma e a disciplina de uma pauta vêm do contexto que o motor registou (`turmaId` = "turma-disciplina", `disciplinaId`), por isso um contexto sem elas só é visível ao administrador. Métodos de escrita em `/term-sheets` são recusados (403). Os PDFs das pautas não estão nesta rota.

## Leitura de folhas de resposta (`POST /api/v1/ocr/answer-sheets`)

Multipart com `statementId` e `files` (até 10). Só quem pode **escrever o enunciado** (`StatementWriteAccessService`: administrador, o professor da turma e da disciplina, ou a conta `INTEGRATION`) o chama; o aluno recebe 403. A estrutura que o OCR procura (questões, tipos, letras) sai do enunciado e não do pedido. Devolve o contrato v1 do OCR (`sheets`, `warnings`, confiança por campo): o que foi lido, nunca uma nota. Tem o mesmo limite de uploads por conta que o resto do OCR.

## Visibilidade no ranking por âmbito (kixi#611)

`GET /api/v1/leaderboard/preferences` e `PUT /api/v1/leaderboard/preferences/{scope}` são `authenticated()` e operam **só sobre a conta do token**: o âmbito vem no caminho (`class`, `school` ou `friends`) e não há `accountId` no corpo. O corpo aceita `rankingVisibility` igual a `visible`, `anonymous` ou `out`; qualquer outro valor é 400. `scope=global` é recusado com 400 porque essa pergunta é o consentimento de `PUT /api/v1/leaderboard/consent`. Quem escolhe `out` continua a ler a sua própria posição; os colegas deixam de receber a linha. O aluno anónimo é publicado como «Um colega» e o nome dele não é lido da base de dados.

## Simulações e respostas dos alunos (kixi#596)

A resposta de um aluno muda o que o recálculo das notas vê, por isso ninguém escreve nela em nome dele:

| Ação | Quem | Regra |
| --- | --- | --- |
| Abrir uma simulação, responder, editar a resposta | `STUDENT` | Só a sua: o serviço exige o dono, mesmo para `ADMIN` ou professor (toda a conta registada tem `STUDENT`, por isso bloquear só por papel não bastava). |
| Abrir a simulação de um aluno e entregar a resposta dele | `INTEGRATION` (o motor SchoolOps) | O `IntegrationScope` já o previa (`POST /simulations`, `PUT /simulations/{id}`, `POST /simulation-answers`). Não edita respostas nem apaga; a nota corrige-se por `PATCH …/grade` e `PUT …/ai-grade`. |
| Apagar, restaurar | `ADMIN` | Motivo obrigatório (5 a 500 caracteres). |
| Purgar (irreversível) | `ADMIN` | Em duas etapas: já na lixeira (um apagar auditado, à parte) e `confirm=<id>` no pedido; mais o motivo. |
| Professor | — | 403 em tudo o que acima não o inclui; corrige a nota, não as respostas. |

Cada criação, edição, apagar, restaurar e purgar grava uma linha em `simulation_audit` (quem, quando, simulação ou resposta, ação e, nos que apagam, restauram ou purgam, o motivo), **na mesma transação** da mutação: se o registo falhar, a mutação desfaz-se. Sem chave estrangeira para a linha auditada, para o registo de um purge sobreviver.

## Papel `INTEGRATION` (serviços)

Papel para serviços que falam com o Kixi, hoje o motor SchoolOps. Em vez de `ADMIN`, uma conta `INTEGRATION` só chega à lista fixa de `IntegrationScope`; tudo o resto responde 403 antes de qualquer outra regra, e o papel não se soma a outros (um token com `INTEGRATION` e `ADMIN` vale só `INTEGRATION`). Dentro da lista a conta vê o que um administrador veria. Uma exceção, porque a conta não é dona de sala nenhuma: `GET /exam-rooms/owned` devolve-lhe **todas** as salas (e, com `institutionId`, as da escola), ao passo que o professor e o `ADMIN` continuam a ver só as suas. É por aí que o motor descobre se uma prova é digital ou em papel (kixi#600).

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

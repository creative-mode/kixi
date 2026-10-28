# Auditoria de segurança de 28/10/2026 (issue #459)

Feita sobre o `dev` e sobre o esquema migrado pelo Flyway (61 migrações, 52 tabelas). Um parágrafo por ponto: o que se verificou, o que se encontrou, o que mudou.

## 1. SQL por concatenação
Nenhum vetor de injeção. Os filtros são texto constante com parâmetros ligados; o `ORDER BY` sai de um `switch` sobre um enum fechado; os conjuntos de âmbito vão ligados como arrays e vêm do servidor. A única concatenação em `src/main` é a constante `QUEUE_LOCK_KEY` de `OcrJobService`. Detalhe sem risco: `AnalyticsRepository.query` pode ligar um parâmetro por prefixo comum (`:filterClass` e `:filterClassId`).

## 2. Logs com dados pessoais
Revistas as 88 chamadas de `log.*`. Nenhuma regista e-mail, nome de utilizador, palavra-passe, token ou respostas. Três quebravam a regra da matriz de autorização (sem IDs de conta nem exceções com conteúdo): `AccountSessionGate` (id da conta e a exceção) e dois registos de `SimulationExpirationJob` (a exceção inteira). Passaram a registar só o tipo (`type=`), como o resto do código.

## 3. Chaves estrangeiras e soft delete
- **FKs em falta** (colunas `*_id` sem chave): `ocr_jobs.request_id` e `statements.ocr_request_id` são identificadores externos, não referências, e estão certos. `posts.class_id`, `course_id`, `institution_id`, `school_year_id` e `simulation_id` são cópias do âmbito no momento da publicação, sem FK por desenho (um post sobrevive a mudanças da turma); o efeito é poderem apontar para linhas apagadas, e o feed já filtra por `deleted_at`. Fica registado, sem alteração.
- **`ON DELETE`**: 53 das 97 FKs não têm regra (comportamento `NO ACTION`); como o sistema apaga por `deleted_at`, o `DELETE` físico só acontece nas rotas de purga, e aí a base recusa se houver dependentes, que é o que se quer.
- **Únicos sem filtro parcial em tabelas com `deleted_at`** (21 índices): o que bloqueia reutilizar o valor depois de apagar. Para `questions (statement_id, number)` é intencional: `findNextQuestionNumber` conta as apagadas e "os números nunca se reutilizam". Para `accounts` (e-mail, username) impede o registo de novo com o mesmo e-mail de uma conta apagada, que é o comportamento esperado hoje. Não se alterou nada.

## 4. Idempotência
- Respostas dos alunos: gravar a mesma resposta duas vezes dá `409` e fica numa só linha; duas submissões simultâneas fecham a simulação uma só vez (`StudentIsolationPostgresIntegrationTest`).
- `POST /v1/correcoes` (SchoolOps) é idempotente pelo identificador do pedido; as falhas do Groq voltam a ser tentadas pelo Camunda com recuo e, esgotadas as tentativas, abrem um incidente visível (não perdem a submissão).

## 5. Uploads de PDF/OCR
Existem limites nas duas pontas: no máximo 10 ficheiros por pedido, 20 MiB por ficheiro e 50 MiB por pedido, 50 páginas (doações: 40); `OcrImageAssociationService` recusa páginas acima de 20 milhões de pixels e o serviço OCR faz o mesmo (`max_image_pixels`, DPI reduzido por página). Não se fez um teste de ficheiro hostil (bomba de descompressão); a defesa verificada é por tamanho, páginas e pixels.

## Seguimento
Nenhuma vulnerabilidade a corrigir além dos registos. Pontos de melhoria registados nas outras issues (#489: isolamento de outras famílias de rotas).

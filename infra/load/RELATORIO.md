# Relatório — teste de carga (issue #124)

**Data:** 14/10/2026 · **Commit base:** `dev` @ `14c274c` · **Ferramenta:** k6 (`grafana/k6`)

## Resumo

| Critério da issue | 250 alunos | 500 alunos |
| --- | --- | --- |
| p95 < 800 ms nas leituras | ✅ **40 ms** | ❌ **2,7 s** |
| 0 erros 5xx | ✅ 0 | ✅ 0 (≈ 95 000 pedidos) |
| Falhas funcionais (checks) | ✅ 0 | ✅ 0 |

- **A API aguenta 500 alunos sem erros**, mas na máquina de teste a latência só
  cumpre a meta até ≈ 250. Acima disso a CPU satura e o débito fica em ≈ 230 req/s.
- O teste encontrou **dois gargalos no código, já corrigidos neste PR**: um lock
  exclusivo partilhado por toda a turma e o bcrypt a correr nas threads de I/O.
- O critério dos 500 tem de ser confirmado no ambiente de produção (#125), com o
  k6 a correr **fora** da máquina da API. Na máquina de teste os dois partilhavam
  os mesmos 8 núcleos.

## Ambiente

| | |
| --- | --- |
| Máquina | Portátil, 8 núcleos, 15 GB RAM: **API, Postgres e k6 na mesma máquina** |
| API | `dev` + correções deste PR, JDK 17, `-Xmx1g`, `show-sql=false` |
| Base | Postgres 16 (Docker), `max_connections=100` |
| Dados | 1 escola, 1 turma, 500 alunos matriculados, 1 enunciado visível com 20 questões (`seed.js`) |
| OCR | **Fora do teste** (ver abaixo) |

## Cenário (`kixi-load.js`)

Cada VU é um aluno diferente: faz login uma vez e repete este percurso, com pausas
de 0,5 s a 5 s entre passos.

`catálogo → abrir prova → criar simulação → 10 respostas → submeter → resultado → ranking da turma → /me/stats`

Perfil: 1 min a subir até ao pico, **5 min no pico** e 30 s a descer.

## Resultados

| # | Configuração | VUs | Pedidos | req/s | p95 leituras | p95 total | 5xx |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `dev` como em produção (pool R2DBC 10) | 500 | 81 335 | 199 | 3 027 ms | 2 749 ms | 0 |
| 2 | pool 40 | 500 | 94 102 | 230 | 2 465 ms | 2 218 ms | 0 |
| 3 | pool 40 + correção do lock | 500 | 96 210 | 235 | 2 656 ms | 2 029 ms | 0 |
| 4 | pool 40 + lock + bcrypt | 500 | 94 867 | 232 | 2 725 ms | 2 004 ms | 0 |
| 5 | igual à 4 | **250** | 52 457 | 183 | **40 ms** | 92 ms | 0 |

p95 por endpoint (ms):

| Endpoint | #1 pool 10 | #2 pool 40 | #4 com correções | #5 (250 VUs) |
| --- | --- | --- | --- | --- |
| `POST /auth/login` | — | 4 310 | 3 710 | 149 |
| `GET /statements/catalog` | — | 4 880 | 3 900 | 47 |
| `GET /statements/{id}/full` | — | 2 460 | 2 120 | 39 |
| `POST /simulations` | — | **2 780** | **912** | 56 |
| `POST /simulation-answers` | — | 1 650 | 1 600 | 77 |
| `POST /simulations/{id}/submit` | — | 4 620 | 4 470 | 196 |
| `GET /simulations/{id}/result` | — | 2 760 | 3 110 | 48 |
| `GET /leaderboard` | — | 1 120 | 1 250 | 21 |
| `GET /me/stats` | — | 1 080 | 1 210 | 17 |

A corrida 1 ainda não tinha métricas por endpoint; foram acrescentadas a partir da 2.

## O que foi encontrado e corrigido

### 1. Lock exclusivo no enunciado (corrigido)

Ao criar uma simulação, `ExamRoomService.lockStatementForSimulation` fazia
`SELECT … FROM statements … FOR UPDATE`. O lock serve para impedir que se abra uma
sala sobre o enunciado ao mesmo tempo. Mas, sendo exclusivo, **as simulações
também se bloqueavam umas às outras**: uma turma inteira a começar a mesma prova
ficava numa fila. Nas amostras do `pg_stat_activity`, 2/3 das ligações estavam em
espera de `Lock` nessa query.

**Correção:** as simulações passam a usar `FOR SHARE`, que pode ter vários
titulares ao mesmo tempo. Abrir uma sala continua a usar `FOR UPDATE`. Assim as
simulações deixam de esperar umas pelas outras, mas continuam a excluir a abertura
de uma sala. Os waits de `Lock` desapareceram e o p95 de `POST /simulations` desceu
de **2,8 s para 0,9 s**.

### 2. Bcrypt nas threads de I/O (corrigido)

`AuthService.login` chamava `passwordEncoder.matches` (cerca de 100 ms de CPU,
propositadamente) dentro do `filter`. Esse código corre na thread que entregou a
linha da base, ou seja, **a thread de I/O do R2DBC partilhada por todas as
ligações**. Cada login parava a leitura da base para os outros pedidos: o Postgres
ficava em `idle in transaction / ClientRead` à espera da aplicação.

**Correção:** o `matches` e o `encode` (registo) correm em `Schedulers.boundedElastic()`.

### 3. Pool R2DBC de 10 ligações (ajustado)

O `docker-compose.yml` não definia o pool, por isso valia o padrão do Spring (10).
Com 500 alunos, o pool esgotava-se e os pedidos ficavam em fila (corrida 1 → 2:
p95 de 3,0 s para 2,5 s). O pool passa a ser configurável, com padrão 10/40
(`R2DBC_POOL_INITIAL_SIZE` e `R2DBC_POOL_MAX_SIZE`), abaixo do `max_connections=100`.

## O que limita os 500 nesta máquina

Com as correções aplicadas, os locks e as esperas desapareceram, e o limite passou
a ser a **CPU**:

- **Uso de CPU durante o pico:** JVM ≈ 540% (5,4 núcleos), Postgres ≈ 90%, k6 ≈ 20–50%.
- **Load average de 23 em 8 núcleos.**
- **Débito:** estabiliza em ≈ 230 req/s com 500 VUs, que pedem mais do que isso.
  Com 250 VUs (183 req/s) há folga e o p95 é de 40 ms.

Perfil por amostragem de stacks (`jcmd Thread.print`, 15 amostras):

- **18% da CPU da aplicação** vai para `Throwable.fillInStackTrace` dentro de
  `org.springframework.data.mapping.PropertyPath.<init>`, chamado pelo
  `QueryMapper` do **Spring Data R2DBC** (Spring Boot 3.2.1). A biblioteca cria e
  descarta uma exceção para cada nome de coluna que mapeia em queries derivadas e
  `save()`. É custo da framework, não do nosso código.
- **5%** vai para `ApiException` construídas eagerly, por exemplo
  `switchIfEmpty(Mono.error(ApiException.notFound(...)))` monta a exceção e o
  stack trace mesmo quando o resultado existe.

## Recomendações (fora deste PR)

1. **Confirmar os 500 no ambiente de produção (#125)**, com o k6 noutra máquina.
   Esta máquina mede o pior caso: o gerador de carga partilha a CPU com a API.
2. **Atualizar o Spring Boot 3.2.1 para a última 3.2.x/3.3.x** e repetir o perfil.
   O ponto 1 do perfil (18% da CPU) está no Spring Data. Não convém fazê-lo no dia
   do lançamento.
3. **Usar `Mono.error(() -> ApiException…)` ou `Mono.defer`** nos `switchIfEmpty`
   dos caminhos quentes, para criar a exceção só quando é precisa.
4. **Login Google:** `findOrCreateAccountFromGoogle` faz `passwordEncoder.encode`
   em *assembly* (no `switchIfEmpty(createAccount…)`), a cada login Google, mesmo
   para contas que já existem.

## OCR sob carga

Ficou **fora** deste teste. A imagem do PaddleOCR tem vários GB e o OCR é usado
por professores (poucos uploads), não pelos 500 alunos. Pelo código, o backend
limita o tempo de cada chamada (`OCR_SERVICE_TIMEOUT_MS`, 120 s por defeito) e o
serviço OCR corre com `WORKERS=1`. Sob uploads simultâneos, os pedidos acima de 1
ficam em fila no OCR até esse timeout. Recomenda-se medir isto à parte no ambiente
de produção, com 5 a 10 uploads simultâneos.

## Reproduzir

Ver [`README.md`](README.md). Os ficheiros de cada corrida (resumo `.txt`, `.json`
e dashboard `.html` do k6) ficam em `infra/load/results/`, que não vai para o git.

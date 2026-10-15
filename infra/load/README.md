# Teste de carga (k6)

Valida o objectivo do MVP (ADR-0009): **500 alunos em simultâneo** com
**p95 < 800 ms nas leituras** e **0 erros 5xx** (issue #124).

| Ficheiro | O que faz |
| --- | --- |
| `seed.js` | Cria, pela API, curso + turma + enunciado visível com gabarito (20 questões) e regista/matricula `STUDENTS` alunos. Idempotente. |
| `kixi-load.js` | Cada VU é um aluno: login → catálogo → prova → simulação (10 respostas + submit) → resultado → ranking → `/me/stats`. Os critérios da issue são `thresholds`: o k6 sai com erro se falharem. |
| `lib.js` | Utilitários partilhados. |
| `RELATORIO.md` | Resultados medidos. |

## Correr

Sem instalar o k6: usa-se a imagem oficial `grafana/k6`.

1. **Backend com um admin de bootstrap** e o limite de registo alto, só para o seed
   (`APP_BOOTSTRAP_ADMIN_*` e `APP_AUTH_REGISTRATION_RATE_LIMIT_MAX_ATTEMPTS=100000`).
   Não apontar a produção: o seed cria 500 contas `loadstudentN@kixi.test`.

2. **Seed** (uma vez; imprime `SEED={"statementId":…}`):

   ```bash
   docker run --rm --network host -v $PWD/infra/load:/scripts \
     -e ADMIN_USER=<admin> -e ADMIN_PASSWORD=<password> \
     grafana/k6 run /scripts/seed.js
   ```

3. **Carga** (≈ 7 min: 1 min de subida, 5 min a 500 VUs, 30 s de descida):

   ```bash
   docker run --rm --network host -u $(id -u) -v $PWD/infra/load:/scripts \
     -e STATEMENT_ID=<id do seed> \
     -e K6_WEB_DASHBOARD=true -e K6_WEB_DASHBOARD_EXPORT=/scripts/results/report.html \
     grafana/k6 run /scripts/kixi-load.js
   ```

   O relatório HTML fica em `infra/load/results/` (ignorado pelo git).

| Variável | Defeito | |
| --- | --- | --- |
| `BASE_URL` | `http://localhost:8080` | |
| `STUDENTS` / `VUS` | `500` | alunos no seed / VUs no pico |
| `HOLD` | `5m` | tempo no pico |
| `ANSWERS` | `10` | respostas por simulação |
| `STUDENT_PASSWORD` | `Kixi-load-2026!` | só para as contas de carga |

## Notas

- **Rate limit de login.** Os 500 alunos saem do mesmo IP. Quando o limite de login
  por IP (#123) estiver no `dev`, subir esse limite no ambiente de carga; caso
  contrário o teste mede o limiter e não a API.
- **Gerador e API na mesma máquina** competem pela CPU: para medir a capacidade
  real, correr o k6 noutra máquina que não a do backend.
- **OCR fica de fora** deste cenário: ver `RELATORIO.md`.

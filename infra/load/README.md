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

## Medir a entrada: primeiro byte e login (#566)

O k6 acima mede carga. Para a pergunta «porque é que a entrada é lenta em produção?» há uma ferramenta mais pequena,
`medir-entrada.mjs`, sem dependências (Node 18+), que mede **de fora** e **em separado**:

```bash
# o primeiro byte das páginas de entrada, decomposto em ligação TCP, handshake TLS e espera do servidor
node infra/load/medir-entrada.mjs --pages https://kixi.creativemode.ao --samples 20

# o POST /auth/login, certo e errado, com uma turma a entrar (contas diferentes, o mesmo endereço)
node infra/load/medir-entrada.mjs --api http://localhost:8080 \
  --login-user-prefix perf- --login-user-count 60 --login-pass '…' --samples 160 --concurrency 8 --wrong-pass
```

- **Só com contas de teste.** Cada tentativa de login conta para o limite da conta e do endereço (10 por minuto e 60 por
  minuto): numa conta real isso pode bloqueá-la. Em local, sobe os limites com
  `APP_AUTH_LOGIN_RATE_LIMIT_MAX_ATTEMPTS` e `APP_AUTH_LOGIN_RATE_LIMIT_MAX_ATTEMPTS_PER_ADDRESS`.
- **Como ler a decomposição.** Se a variação está na *ligação TCP* ou no *handshake TLS*, é a rede (perda de pacotes: um SYN
  que se repete demora ~1 s, 3 s…). Se está na *espera do servidor*, é o servidor. Mede de mais do que uma origem: uma só
  não distingue o caminho do servidor.
- **No servidor.** Quando um login passa de 750 ms numa fase, o backend regista
  `Slow login phase: phase=bcrypt durationMs=…` (inclui a espera por uma thread livre, que é o que uma turma a entrar
  em conjunto numa máquina pequena paga). Procura essa linha nos registos antes de mexer em mais nada.

### O que se mediu (04/11/2026)

**Produção, a partir de uma só máquina (indicativo):** a *espera do servidor* é estável (180 a 640 ms, mesmo nas páginas
servidas da cache do Next); o que varia é a *ligação TCP* (0,2 a 2,5 s) e o *handshake TLS* (0,2 a 9,9 s), com `ping`
a perder 60% dos pacotes. Os tempos de ligação perto de 1 s e 2,5 s são retransmissões de SYN. O «primeiro byte de 0,8 a
4,9 s» da issue é, daqui, sobretudo rede, não aplicação.

**Login, local (8 núcleos, Postgres na mesma máquina, 60 contas, um só endereço):**

| | antes | depois |
| --- | --- | --- |
| idas à base de dados por login | 8 a 9 | 6 a 7 |
| p75 com 8 em paralelo | 624 ms | 475 ms (−24%) |
| p75 com 16 em paralelo | 903 ms | 648 ms (−28%) |
| 1.º login após o arranque (mediana de 5, 5 s depois) | 2,5 s | 1,2 s |
| bcrypt (custo 10), uma verificação | ≈ 83 ms de CPU | igual |

O bcrypt (≈ 83 ms de CPU por login) domina, e não se baixa sem enfraquecer as palavras-passe. Numa máquina pequena
e partilhada com o OCR, uma turma de 30 a entrar em conjunto soma 30 × 83 ms de CPU a dividir pelos núcleos disponíveis:
é esse o limite, e só se mede em produção, no servidor, com a linha `Slow login phase` acima.
Experimentado e **descartado**: o nível de log `DEBUG` da aplicação (que o `docker-compose.yml` define por omissão) não
mexeu no login (39 linhas de log numa corrida de 120 pedidos).

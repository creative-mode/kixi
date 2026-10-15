// Teste de carga do MVP (issue #124, ADR-0009): 500 alunos em simultâneo.
//
// Cada VU é um aluno do seed.js e faz o percurso de uma sessão de estudo:
//   login → catálogo → abrir prova → simulação (responder + submeter) → resultado
//   → ranking da turma → estatísticas de gamificação
//
// Critério de aceitação da issue, aplicado como thresholds (o k6 sai com erro se falhar):
//   - p95 < 800 ms nos endpoints de leitura
//   - 0 respostas 5xx
//
//   k6 run -e STATEMENT_ID=1 infra/load/kixi-load.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { API, STUDENTS, STUDENT_PASSWORD, jsonHeaders, login, studentUsername } from './lib.js';

const STATEMENT_ID = Number(__ENV.STATEMENT_ID || 1);
const PEAK_VUS = Number(__ENV.VUS || STUDENTS);
const HOLD = __ENV.HOLD || '5m';
// Quantas questões cada aluno responde antes de submeter (as restantes ficam em branco).
const ANSWERS = Number(__ENV.ANSWERS || 10);

const serverErrors = new Counter('server_errors_5xx');

export const options = {
    scenarios: {
        students: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '1m', target: PEAK_VUS },
                { duration: HOLD, target: PEAK_VUS },
                { duration: '30s', target: 0 },
            ],
            gracefulRampDown: '30s',
        },
    },
    thresholds: {
        'http_req_duration{kind:read}': ['p(95)<800'],
        'server_errors_5xx': ['count==0'],
        checks: ['rate>0.99'],
        // Sem critério próprio: só para o resumo mostrar o p95 de cada endpoint.
        ...Object.fromEntries([
            'POST /auth/login', 'GET /statements/catalog', 'GET /statements/{id}/full',
            'POST /simulations', 'POST /simulation-answers', 'POST /simulations/{id}/submit',
            'GET /simulations/{id}/result', 'GET /leaderboard', 'GET /me/stats',
        ].map((name) => [`http_req_duration{name:${name}}`, ['max>=0']])),
    },
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function track(res) {
    if (res.status >= 500) serverErrors.add(1, { name: res.request && res.request.url });
    return res;
}

function get(token, path, name) {
    return track(http.get(`${API}${path}`, jsonHeaders(token, { name, kind: 'read' })));
}

function post(token, path, body, name) {
    return track(http.post(`${API}${path}`, body === undefined ? null : JSON.stringify(body),
        jsonHeaders(token, { name, kind: 'write' })));
}

// Um aluno por VU, e o login acontece uma vez por VU (como numa sessão real),
// não em cada iteração: um JWT dura 24 h.
let session = null;

export default function () {
    if (!session) {
        session = login(studentUsername(exec.vu.idInTest), STUDENT_PASSWORD, { kind: 'write' });
        if (!session) { sleep(1); return; }
    }
    const t = session.token;

    const catalog = get(t, '/statements/catalog?size=20', 'GET /statements/catalog');
    check(catalog, { 'catálogo 200': (r) => r.status === 200 });
    sleep(1 + Math.random() * 2);

    const full = get(t, `/statements/${STATEMENT_ID}/full`, 'GET /statements/{id}/full');
    if (!check(full, { 'prova 200': (r) => r.status === 200 })) return;
    const questions = full.json('questions') || [];
    sleep(1 + Math.random() * 2);

    const sim = post(t, '/simulations',
        { accountId: session.accountId, statementId: STATEMENT_ID }, 'POST /simulations');
    if (!check(sim, { 'simulação 201': (r) => r.status === 201 })) return;
    const simulationId = sim.json('id');

    for (const q of questions.slice(0, ANSWERS)) {
        const opts = q.options || [];
        const pick = opts[Math.floor(Math.random() * opts.length)];
        const ans = post(t, '/simulation-answers', {
            simulationId, questionId: q.id, selectedOptionId: pick && pick.id,
        }, 'POST /simulation-answers');
        check(ans, { 'resposta 201': (r) => r.status === 201 });
        sleep(0.5 + Math.random());
    }

    const submit = post(t, `/simulations/${simulationId}/submit`, undefined,
        'POST /simulations/{id}/submit');
    check(submit, { 'submit 200': (r) => r.status === 200 });

    const result = get(t, `/simulations/${simulationId}/result`, 'GET /simulations/{id}/result');
    check(result, { 'resultado 200': (r) => r.status === 200 });
    sleep(1);

    const ranking = get(t, '/leaderboard?scope=class', 'GET /leaderboard');
    check(ranking, { 'ranking 200': (r) => r.status === 200 });

    const stats = get(t, '/me/stats', 'GET /me/stats');
    check(stats, { 'stats 200': (r) => r.status === 200 });

    sleep(2 + Math.random() * 3);
}

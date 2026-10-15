// Prepara os dados do teste de carga pela própria API (sem SQL à mão):
//   - curso, turma e um enunciado visível com gabarito na escola ITEL;
//   - STUDENTS alunos registados e matriculados nessa turma.
//
// É idempotente: correr duas vezes reaproveita o que já existe.
// O resultado (ids) é escrito no stdout como JSON, numa linha que começa por SEED=.
//
//   k6 run -e ADMIN_USER=... -e ADMIN_PASSWORD=... infra/load/seed.js
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';
import {
    API, PREFIX, STUDENTS, STUDENT_PASSWORD,
    jsonHeaders, login, loginOrFail, studentEmail, studentUsername,
} from './lib.js';

const QUESTIONS = Number(__ENV.QUESTIONS || 20);

export const options = {
    setupTimeout: '120s',
    scenarios: {
        students: {
            executor: 'shared-iterations',
            vus: 25,
            iterations: STUDENTS,
            maxDuration: '15m',
        },
    },
    thresholds: {
        checks: ['rate==1.0'],
    },
};

function findOrCreate(token, path, match, body) {
    const list = http.get(`${API}${path}`, jsonHeaders(token));
    const items = Array.isArray(list.json()) ? list.json() : (list.json('content') || []);
    const found = items.find(match);
    if (found) return found;
    const res = http.post(`${API}${path}`, JSON.stringify(body), jsonHeaders(token));
    if (res.status !== 201) fail(`POST ${path} → ${res.status}: ${res.body}`);
    return res.json();
}

export function setup() {
    const admin = loginOrFail(__ENV.ADMIN_USER, __ENV.ADMIN_PASSWORD);
    const t = admin.token;

    const institution = http.get(`${API}/institutions`, jsonHeaders(t)).json()
        .find((i) => i.code === 'ITEL');
    if (!institution) fail('Instituição ITEL não encontrada (seed V26)');

    const schoolYear = http.get(`${API}/school-years`, jsonHeaders(t)).json()[0];
    const subject = http.get(`${API}/subjects`, jsonHeaders(t)).json()
        .find((s) => s.code === 'MAT');

    const code = `${PREFIX.toUpperCase()}TI`;
    const course = findOrCreate(t, '/courses', (c) => c.code === code, {
        code, name: 'Curso de carga', institutionId: institution.id,
    });
    const klass = findOrCreate(t, '/classes', (c) => c.code === `${code}-12A`, {
        code: `${code}-12A`, grade: 12, courseId: course.id, schoolYearId: schoolYear.id,
    });

    const title = `Prova de carga ${PREFIX}`;
    const catalog = http.get(`${API}/statements/catalog?q=${encodeURIComponent(title)}&scope=all`,
        jsonHeaders(t)).json('content') || [];
    let statement = catalog.find((s) => s.title === title);
    if (!statement) {
        const questions = [];
        for (let q = 1; q <= QUESTIONS; q++) {
            questions.push({
                text: `Questão ${q}: quanto é ${q} + ${q}?`,
                maxScore: 1,
                options: ['A', 'B', 'C', 'D'].map((label, i) => ({
                    label, text: String(2 * q + i), correct: i === 0,
                })),
            });
        }
        const res = http.post(`${API}/statements/manual`, JSON.stringify({
            institutionId: institution.id, subjectId: subject.id, title,
            examType: 'Prova do Professor', durationMinutes: 90,
            schoolYearId: schoolYear.id, classId: klass.id, courseId: course.id,
            visible: true, questions,
        }), jsonHeaders(t));
        if (res.status !== 201) fail(`POST /statements/manual → ${res.status}: ${res.body}`);
        statement = res.json();
    }

    return { classId: klass.id, schoolYearId: schoolYear.id, statementId: statement.id };
}

export default function (data) {
    const n = exec.scenario.iterationInTest + 1;

    const reg = http.post(`${API}/auth/register`, JSON.stringify({
        username: studentUsername(n), email: studentEmail(n), password: STUDENT_PASSWORD,
        firstName: 'Aluno', lastName: `Carga ${n}`,
    }), jsonHeaders());
    // 409 = já existia de uma corrida anterior; serve igual.
    check(reg, { 'registo 201/409': (r) => r.status === 201 || r.status === 409 });

    const session = login(studentUsername(n), STUDENT_PASSWORD);
    if (!session) return;

    const enroll = http.post(`${API}/enrollments`, JSON.stringify({
        classId: data.classId, schoolYearId: data.schoolYearId,
    }), jsonHeaders(session.token));
    check(enroll, { 'matrícula 201/409': (r) => r.status === 201 || r.status === 409 });
}

export function teardown(data) {
    console.log(`SEED=${JSON.stringify(data)}`);
}

// Utilitários partilhados pelos scripts de carga (seed.js e kixi-load.js).
import http from 'k6/http';
import { check, fail } from 'k6';

export const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
export const API = `${BASE_URL}/api/v1`;

export const STUDENTS = Number(__ENV.STUDENTS || 500);
export const STUDENT_PASSWORD = __ENV.STUDENT_PASSWORD || 'Kixi-load-2026!';

// Prefixo das contas e dos dados de carga: torna-os fáceis de reconhecer e
// de apagar, e evita colisões com dados reais se alguém apontar para outra base.
export const PREFIX = __ENV.LOAD_PREFIX || 'load';

export function studentUsername(n) {
    return `${PREFIX}student${n}`;
}

export function studentEmail(n) {
    return `${PREFIX}student${n}@kixi.test`;
}

export function jsonHeaders(token, tags = {}) {
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers.Authorization = `Bearer ${token}`;
    return { headers, tags };
}

/** Login; devolve { token, accountId } ou null. */
export function login(usernameOrEmail, password, tags = {}) {
    const res = http.post(`${API}/auth/login`,
        JSON.stringify({ usernameOrEmail, password }),
        jsonHeaders(null, { name: 'POST /auth/login', ...tags }));
    const ok = check(res, { 'login 200': (r) => r.status === 200 });
    if (!ok) return null;
    return { token: res.json('accessToken'), accountId: res.json('accountId') };
}

/** Login que aborta o teste: só para o setup, onde sem token não há teste. */
export function loginOrFail(usernameOrEmail, password) {
    const session = login(usernameOrEmail, password);
    if (!session) fail(`Não consegui autenticar ${usernameOrEmail}`);
    return session;
}

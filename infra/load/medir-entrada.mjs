#!/usr/bin/env node
// Mede, de fora, o que a #566 pede para medir em separado: o primeiro byte (TTFB) das páginas de entrada e o
// `POST /auth/login`, com p50/p75/p95. Sem dependências (Node 18+).
//
//   node infra/load/medir-entrada.mjs --pages https://kixi.creativemode.ao --samples 20
//   node infra/load/medir-entrada.mjs --api http://localhost:8080 --login-user aluno@x.ao --login-pass '...' --samples 30 --concurrency 4
//
// Cada secção é opcional. O login só corre com --login-user/--login-pass (ou KIXI_LOGIN_USER/KIXI_LOGIN_PASS): usa uma
// conta de teste, nunca uma real, porque cada tentativa conta para o limite de tentativas da conta e do endereço.
// --wrong-pass mede também o caminho da palavra-passe errada (o que a #543 viu a demorar ~6,5 s).
// --login-user-prefix perf- --login-user-count 60 roda por contas perf-1..perf-60 (todas com a mesma palavra-passe), como
// uma turma a entrar: contas diferentes mas o mesmo endereço, que é o que acontece atrás do NAT de uma escola.
import http from 'node:http';
import https from 'node:https';
import { performance } from 'node:perf_hooks';

const args = Object.fromEntries(
  process.argv.slice(2).flatMap((a, i, all) => (a.startsWith('--') ? [[a.slice(2), all[i + 1] && !all[i + 1].startsWith('--') ? all[i + 1] : 'true']] : [])),
);
const SAMPLES = Number(args.samples ?? 20);
const CONCURRENCY = Math.max(1, Number(args.concurrency ?? 1));
const PAGES = ['/', '/doar', '/aluno/entrar', '/aluno/cadastro'];

const quantile = (sorted, q) => (sorted.length ? sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * q) - 1)] : NaN);
const ms = (n) => (Number.isFinite(n) ? `${Math.round(n)} ms` : '—');

/**
 * Um pedido com os tempos de cada fase, sem keep-alive, como quem chega pela primeira vez:
 * `tcp` (ligação), `tls` (handshake) e `wait` (do fim do TLS ao primeiro byte: o que o servidor demora a responder).
 * Separar estas fases é o que distingue a rede (SYN e handshake a repetir por perda de pacotes) do servidor.
 */
function request(url, { method = 'GET', headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const lib = u.protocol === 'https:' ? https : http;
    const t0 = performance.now();
    let tTcp = 0;
    let tSecure = 0;
    let tFirstByte = 0;
    const req = lib.request(
      { method, hostname: u.hostname, port: u.port || undefined, path: u.pathname + u.search, agent: false, headers: { 'user-agent': 'kixi-medir-entrada/1', ...headers } },
      (res) => {
        tFirstByte = performance.now();
        res.resume();
        res.on('end', () => {
          const ready = tSecure || tTcp;
          resolve({ status: res.statusCode, tcp: tTcp - t0, tls: tSecure ? tSecure - tTcp : 0, wait: tFirstByte - ready, ttfb: tFirstByte - t0, total: performance.now() - t0 });
        });
      },
    );
    req.on('socket', (s) => {
      s.once('connect', () => { tTcp = performance.now(); });
      if (u.protocol === 'https:') s.once('secureConnect', () => { tSecure = performance.now(); });
    });
    req.setTimeout(60_000, () => req.destroy(new Error('timeout de 60 s')));
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

async function pool(n, concurrency, task) {
  const out = [];
  let next = 0;
  await Promise.all(Array.from({ length: concurrency }, async () => {
    while (next < n) out.push(await task(next++).catch((e) => ({ error: e.message })));
  }));
  return out;
}

function resumo(titulo, medidas, campo) {
  const ok = medidas.filter((m) => !m.error);
  const v = ok.map((m) => m[campo]).sort((a, b) => a - b);
  const erros = medidas.length - ok.length;
  console.log(`${titulo.padEnd(34)} n=${String(ok.length).padStart(3)}  p50 ${ms(quantile(v, 0.5)).padStart(8)}  p75 ${ms(quantile(v, 0.75)).padStart(8)}  p95 ${ms(quantile(v, 0.95)).padStart(8)}  máx ${ms(v[v.length - 1]).padStart(8)}${erros ? `  (${erros} erros)` : ''}`);
}

if (args.pages) {
  console.log(`\nPrimeiro byte das páginas (${args.pages}), ${SAMPLES} amostras, concorrência ${CONCURRENCY}`);
  console.log('  total = ligação + TLS + espera do servidor. Se a variação está na ligação/TLS é a rede; se está na espera é o servidor.');
  for (const rota of PAGES) {
    const r = await pool(SAMPLES, CONCURRENCY, () => request(args.pages.replace(/\/$/, '') + rota, { headers: { 'accept-encoding': 'identity' } }));
    resumo(`GET ${rota}`, r, 'ttfb');
    for (const [fase, campo] of [['ligação TCP', 'tcp'], ['handshake TLS', 'tls'], ['espera do servidor', 'wait']]) resumo(`    ${fase}`, r, campo);
  }
}

if (args.api) {
  const fixo = args['login-user'] ?? process.env.KIXI_LOGIN_USER;
  const prefixo = args['login-user-prefix'];
  const total = Number(args['login-user-count'] ?? 0);
  const pass = args['login-pass'] ?? process.env.KIXI_LOGIN_PASS;
  if ((!fixo && !(prefixo && total > 0)) || !pass) {
    console.error('\n--api pede --login-pass e --login-user (ou --login-user-prefix com --login-user-count): uma conta de teste.');
    process.exit(2);
  }
  const login = (password) => (i) =>
    request(`${args.api.replace(/\/$/, '')}/api/v1/auth/login`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ usernameOrEmail: prefixo && total > 0 ? `${prefixo}${(i % total) + 1}` : fixo, password }),
    });
  console.log(`\nPOST /auth/login (${args.api}), ${SAMPLES} amostras, concorrência ${CONCURRENCY}`);
  const certo = await pool(SAMPLES, CONCURRENCY, login(pass));
  resumo('palavra-passe certa (200)', certo, 'ttfb');
  const estados = [...new Set(certo.filter((m) => !m.error).map((m) => m.status))];
  if (estados.some((s) => s !== 200)) console.log(`  atenção: respostas ${estados.join(', ')} (o limite de tentativas pode estar a travar)`);
  if (args['wrong-pass']) {
    const errado = await pool(SAMPLES, CONCURRENCY, login(`${pass}-errada`));
    resumo('palavra-passe errada (401)', errado, 'ttfb');
  }
}

if (!args.pages && !args.api) {
  console.error('Usa --pages <url> e/ou --api <url> --login-user <u> --login-pass <p>. Ver o cabeçalho do ficheiro.');
  process.exit(2);
}

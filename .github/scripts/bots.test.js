// Testes dos bots:  node --test .github/scripts/bots.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const { evaluate, closingRefs } = require('./pr-check.js');
const alert = require('./dependency-alert.js');
const sync = require('./project-sync.js');

const B = { dev: 'dev', qua: 'qua', prod: 'prod' };
const base = { head: 'feature/23-login-com-jwt', base: 'dev', body: 'Closes #23', ownRepo: 'creative-mode/kixi', branches: B };

test('PR conforme', () => assert.deepEqual(evaluate(base), []));
test('branch fora do padrão', () => assert.equal(evaluate({ ...base, head: 'feat/auth-register' }).length, 1));
test('destino errado', () => assert.match(evaluate({ ...base, base: 'prod' })[0], /apontam para `dev`/));
test('sem Closes', () => assert.match(evaluate({ ...base, body: 'só texto' })[0], /fechar uma issue/));
test('Closes de outra issue', () => assert.match(evaluate({ ...base, body: 'Closes #99' })[0], /refere a issue #23/));
test('Closes em comentário/código não conta', () => assert.equal(evaluate({ ...base, body: '<!-- Closes #23 -->\n`Closes #23`' }).length, 1));
test('várias issues e fixes', () => assert.deepEqual(evaluate({ ...base, body: 'Fixes #5, #23 e #7' }), []));
test('issue de outro repo não satisfaz', () => assert.equal(evaluate({ ...base, body: 'Closes creative-mode/kixi-frontend#23' }).length, 1));
test('promoções', () => {
  assert.deepEqual(evaluate({ ...base, head: 'dev', base: 'qua', body: '' }), []);
  assert.deepEqual(evaluate({ ...base, head: 'qua', base: 'prod', body: '' }), []);
  assert.equal(evaluate({ ...base, head: 'dev', base: 'prod', body: '' }).length, 1);
});
test('hotfix pode ir a prod; dependabot isento', () => {
  assert.deepEqual(evaluate({ ...base, head: 'hotfix/40-crash', base: 'prod', body: 'Closes #40' }), []);
  assert.deepEqual(evaluate({ ...base, head: 'dependabot/npm/x', body: '' }), []);
});
test('closingRefs', () => assert.deepEqual(closingRefs('Closes #1, #2', 'a/b'), [1, 2]));

// ---------- Alerta de bloqueio ----------
const issuesDb = {
  'creative-mode/kixi': [
    { number: 105, title: 'BE-09', state: 'open', assignees: [{ login: 'Marcilio11-du' }], milestone: { title: 'M3', due_on: '2026-10-09T00:00:00Z' },
      body: '## 🎯 Objetivo\nx\n\n## 🔗 Depende de\n#104 (BE-08)\n\n---\n📅 Prazo\n📋 PR com `Closes #<n.º>`' },
    { number: 110, title: 'BE-14', state: 'open', assignees: [], milestone: null,
      body: '## 🔗 Depende de\n#105 (BE-09), #104 (BE-08), creative-mode/kixi-frontend#9 (FE-09)\n\n---' },
    { number: 111, title: 'BE-X', state: 'open', assignees: [], milestone: null, body: '## 🔗 Depende de\nnenhuma — pode começar já\n\n---' },
  ],
  'creative-mode/kixi-frontend': [
    { number: 22, title: 'FE-11', state: 'open', assignees: [{ login: 'CreadorLanda' }], milestone: null, body: '## 🔗 Depende de\ncreative-mode/kixi#104 (BE-08), creative-mode/kixi#102 (BE-06)\n\n---' },
    { number: 9, title: 'FE-09', state: 'open', assignees: [], milestone: null, body: '' },
  ],
};
function mockGithub(posted, fail = new Set()) {
  return {
    paginate: async (fn, o) => fn(o),
    rest: { issues: {
      listForRepo: async ({ owner, repo }) => issuesDb[`${owner}/${repo}`],
      listComments: async () => [],
      get: async ({ owner, repo, issue_number }) => ({ data: issuesDb[`${owner}/${repo}`].find((i) => i.number === issue_number) || { state: 'closed', number: issue_number } }),
      createComment: async (a) => { if (fail.has(a.repo)) throw new Error('403'); posted.push(a); return {}; },
      update: async (a) => { const i = issuesDb[`${a.owner}/${a.repo}`].find((x) => x.number === a.issue_number); i.state = 'closed'; return { data: i }; },
    } },
  };
}
const core = { info() {}, warning() {}, setFailed(m) { throw new Error(m); } };

test('parseDeps lê a secção e ignora o rodapé', () => {
  assert.deepEqual(alert.parseDeps(issuesDb['creative-mode/kixi'][0].body, 'creative-mode/kixi'), ['creative-mode/kixi#104']);
  assert.deepEqual(alert.parseDeps(issuesDb['creative-mode/kixi'][1].body, 'creative-mode/kixi'),
    ['creative-mode/kixi#105', 'creative-mode/kixi#104', 'creative-mode/kixi-frontend#9']);
  assert.deepEqual(alert.parseDeps('nada', 'a/b'), []);
});

test('issue fechada desbloqueia dependentes (mesmo e outro repo)', async () => {
  const posted = [];
  await alert({ github: mockGithub(posted), core, context: { eventName: 'issues', repo: { owner: 'creative-mode', repo: 'kixi' },
    payload: { issue: { number: 104, title: 'Gabarito', state: 'closed' } } } });
  const by = (r, n) => posted.find((p) => p.repo === r && p.issue_number === n);
  assert.match(by('kixi', 105).body, /Desbloqueada/);
  assert.match(by('kixi', 105).body, /@Marcilio11-du/);
  assert.match(by('kixi', 110).body, /Dependência concluída/);            // ainda falta #105 e FE-09
  assert.match(by('kixi-frontend', 22).body, /Desbloqueada/); // #102 não está na db do mock → conta como fechada
  assert.equal(posted.some((p) => p.issue_number === 111), false);
});

test('merge do PR fecha a issue e avisa', async () => {
  issuesDb['creative-mode/kixi'].push({ number: 104, title: 'Gabarito', state: 'open', assignees: [], milestone: null, body: '' });
  const posted = [];
  await alert({ github: mockGithub(posted), core, context: { eventName: 'pull_request', repo: { owner: 'creative-mode', repo: 'kixi' },
    payload: { pull_request: { merged: true, number: 200, base: { ref: 'dev' }, body: 'Closes #104' } } } });
  assert.ok(posted.some((p) => p.issue_number === 104 && /Concluída pelo merge/.test(p.body)));
  assert.ok(posted.some((p) => p.issue_number === 105 && /Desbloqueada/.test(p.body)));
});

test('sem permissão cross-repo só gera aviso', async () => {
  const posted = [];
  await alert({ github: mockGithub(posted, new Set(['kixi-frontend'])), core, context: { eventName: 'issues', repo: { owner: 'creative-mode', repo: 'kixi' },
    payload: { issue: { number: 104, title: 'x', state: 'closed' } } } });
  assert.ok(posted.length > 0);
});

// ---------- Sincronizador do Projeto ----------
function mockProject(calls) {
  const options = ['Backlog', 'In progress', 'In review', 'Done'].map((n) => ({ id: 'o-' + n, name: n }));
  const github = {
    graphql: async (q, v) => {
      calls.push({ q: q.slice(0, 40).replace(/\s+/g, ' '), v });
      if (q.includes('organization(login')) return { organization: { projectV2: { id: 'P', fields: { nodes: [{}, { id: 'F', name: 'Status', options }] } } } };
      if (q.includes('addProjectV2ItemById')) return { addProjectV2ItemById: { item: { id: 'item-' + v.c } } };
      if (q.includes('fieldValueByName')) return { node: { fieldValueByName: { name: v.i === 'item-DONE' ? 'Done' : 'Backlog' } } };
      return {};
    },
    rest: { issues: { get: async ({ issue_number }) => ({ data: { node_id: issue_number === 99 ? 'DONE' : 'N' + issue_number } }) } },
  };
  return github;
}
const sets = (calls) => calls.filter((c) => c.v.o).map((c) => `${c.v.i}:${c.v.o}`);
const ctx = (eventName, payload) => ({ eventName, payload, repo: { owner: 'creative-mode', repo: 'kixi' } });

test('sync: sem token não faz nada', async () => {
  delete process.env.PROJECT_TOKEN_SET; const calls = [];
  await sync({ github: mockProject(calls), core, context: ctx('issues', { action: 'opened', issue: { node_id: 'I1' } }) });
  assert.equal(calls.length, 0);
});
test('sync: issue aberta → Backlog; branch → In progress; PR → In review; merge → Done', async () => {
  process.env.PROJECT_TOKEN_SET = 'true';
  let calls = [];
  await sync({ github: mockProject(calls), core, context: ctx('issues', { action: 'opened', issue: { node_id: 'I1' } }) });
  assert.deepEqual(sets(calls), ['item-I1:o-Backlog']);
  calls = [];
  await sync({ github: mockProject(calls), core, context: ctx('create', { ref_type: 'branch', ref: 'feature/104-gabarito' }) });
  assert.deepEqual(sets(calls), ['item-N104:o-In progress']);
  calls = [];
  await sync({ github: mockProject(calls), core, context: ctx('create', { ref_type: 'branch', ref: 'feature/99-ja-feita' }) });
  assert.deepEqual(sets(calls), []);   // já está Done: não recua
  calls = [];
  await sync({ github: mockProject(calls), core, context: ctx('pull_request', { action: 'opened', pull_request: { node_id: 'PR1', draft: false, body: 'Closes #104' } }) });
  assert.deepEqual(sets(calls), ['item-PR1:o-In review', 'item-N104:o-In review']);
  calls = [];
  await sync({ github: mockProject(calls), core, context: ctx('pull_request', { action: 'closed', pull_request: { node_id: 'PR1', merged: true, body: 'Closes #104' } }) });
  assert.deepEqual(sets(calls), ['item-PR1:o-Done', 'item-N104:o-Done']);
  calls = [];
  await sync({ github: mockProject(calls), core, context: ctx('pull_request', { action: 'converted_to_draft', pull_request: { node_id: 'PR1', draft: true, body: 'Closes #104' } }) });
  assert.deepEqual(sets(calls), ['item-N104:o-In progress']);
  delete process.env.PROJECT_TOKEN_SET;
});

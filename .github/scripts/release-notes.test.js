// Testes do agente de notas de versão:  node --test .github/scripts/release-notes.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const notes = require('./release-notes.js');

test('o título perde o número, o prefixo convencional e os travessões', () => {
  assert.equal(notes.cleanTitle('feat(aluno): Perfis de escolas (#346)'), 'Perfis de escolas');
  assert.equal(notes.cleanTitle('corrige o ranking — amigos (#9)'), 'Corrige o ranking: amigos');
  assert.ok(!/[—–]/.test(notes.cleanTitle('a — b – c — d')));
});

test('só as issues fechadas de verdade contam', () => {
  assert.deepEqual(notes.closingIssues('Closes #331\n\nFecha também #320'), [331]);
  assert.deepEqual(notes.closingIssues('Fixes #5, #23 e #7'), [5, 23, 7]);
  assert.deepEqual(notes.closingIssues('<!-- Closes #1 -->\n`Closes #2`'), []);
  assert.deepEqual(notes.closingIssues(null), []);
});

test('a classificação segue etiqueta, branch e título, por esta ordem', () => {
  assert.equal(notes.classify({ title: 'x', head: { ref: 'fix/12-bug' } }), 'fix');
  assert.equal(notes.classify({ title: 'x', head: { ref: 'feat/12-algo' } }), 'feature');
  assert.equal(notes.classify({ title: 'x', head: { ref: 'feat/1-a' }, labels: [{ name: 'bug' }] }), 'fix');
  assert.equal(notes.classify({ title: 'docs: mudança', head: { ref: 'docs/3-x' } }), 'improvement');
  assert.equal(notes.classify({ title: 'qualquer coisa', head: { ref: 'xyz' } }), 'improvement');
});

test('promoções e bots não entram nas notas', () => {
  assert.equal(notes.isTransport({ title: 'Promover DEV para QUA' }), true);
  assert.equal(notes.isTransport({ title: 'Promoção qua → prod' }), true);
  assert.equal(notes.isTransport({ title: 'x', user: { login: 'dependabot[bot]', type: 'Bot' } }), true);
  assert.equal(notes.isTransport({ title: 'Perfis', user: { login: 'abnerLouren', type: 'User' } }), false);
});

test('duas versões no mesmo dia ganham sufixo', () => {
  const d = new Date('2026-10-17T10:00:00Z');
  assert.equal(notes.nextVersion(d, []), '2026.10.17');
  assert.equal(notes.nextVersion(d, ['v2026.10.17']), '2026.10.17.2');
  assert.equal(notes.nextVersion(d, ['v2026.10.17', 'v2026.10.17.2']), '2026.10.17.3');
});

test('o marcador escondido volta a ser lido', () => {
  const sha = { backend: 'a'.repeat(40), frontend: 'b'.repeat(40) };
  assert.deepEqual(notes.readMarker(`texto\n${notes.marker(sha)}`), sha);
  assert.equal(notes.readMarker('sem marcador'), null);
});

const pr = (number, title, head, extra = {}) => ({ number, title, author: 'ana', issues: [], head: { ref: head }, labels: [], ...extra });

test('as notas têm logo, secções, ligações, contribuidores e marcador', () => {
  const sha = { backend: 'a'.repeat(40), frontend: 'b'.repeat(40) };
  const groups = notes.group({
    backend: [pr(339, 'Perfil por conta', 'feat/338-x', { issues: [338] })],
    frontend: [pr(346, 'Perfis de escolas', 'feat/319-x'), pr(351, 'Início sem ecrã em branco', 'fix/350-x')],
  });
  const body = notes.render({
    version: '2026.10.17',
    date: new Date('2026-10-17T10:00:00Z'),
    logoUrl: 'https://raw.githubusercontent.com/creative-mode/kixi/abc/.github/assets/kixi-logo.svg',
    groups,
    sha,
    contributors: ['ana'],
    previous: { backend: 'c'.repeat(40), frontend: 'd'.repeat(40) },
  });
  assert.match(body, /<img src="https:\/\/raw\.githubusercontent\.com\/creative-mode\/kixi\/abc\/\.github\/assets\/kixi-logo\.svg"/);
  assert.match(body, /# Kixi 2026\.10\.17/);
  assert.match(body, /2 novidades, 1 correções e 0 melhorias internas/);
  assert.match(body, /## Novidades/);
  assert.match(body, /## Correções/);
  assert.doesNotMatch(body, /## Melhorias internas/);
  assert.match(body, /- Perfil por conta \(#339\) por @ana, fecha #338/);
  assert.match(body, /\[creative-mode\/kixi-frontend#346\]\(https:\/\/github\.com\/creative-mode\/kixi-frontend\/pull\/346\)/);
  assert.match(body, /@ana/);
  assert.match(body, /compare\/ccccccc\.\.\.aaaaaaa/);
  assert.deepEqual(notes.readMarker(body), sha);
  assert.ok(!/[—–]/.test(body));
});

test('sem PRs novos as notas dizem isso em vez de inventar secções', () => {
  const sha = { backend: 'a'.repeat(40), frontend: 'b'.repeat(40) };
  const body = notes.render({ version: '2026.10.18', date: new Date('2026-10-18T00:00:00Z'), logoUrl: 'x', groups: notes.group({ backend: [], frontend: [] }), sha, contributors: [], previous: null });
  assert.match(body, /Sem alterações novas/);
  assert.doesNotMatch(body, /## Novidades/);
});

test('a ordem de integração dentro de cada secção é mantida', () => {
  const groups = notes.group({ backend: [pr(1, 'A', 'feat/1-a'), pr(2, 'B', 'feat/2-b')], frontend: [] });
  assert.deepEqual(groups.feature.backend.map((p) => p.number), [1, 2]);
});

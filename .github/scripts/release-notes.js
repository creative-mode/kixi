// Agente de notas de versão: a cada promoção para prod junta o que saiu no backend e no frontend,
// agrupa por tipo e publica uma versão no GitHub com a marca do Kixi.
//
//   node --test .github/scripts/release-notes.test.js     (testes)
//
// Como sabe o que é novo: a versão anterior guarda no fim das notas um marcador escondido com o
// commit de cada repositório (`<!-- kixi-release: backend=<sha> frontend=<sha> -->`). A nova versão
// compara esse commit com a ponta atual de prod. Sem versão anterior (a primeira), lê os últimos
// commits. Os PRs saem das mensagens dos commits ("título (#12)" ou "Merge pull request #12"), a
// mesma regra do promotor, e os PRs de promoção ficam de fora: são o transporte, não o conteúdo.

const { parsePrNumbers } = require('./promote.js');

const BACKEND = 'creative-mode/kixi';
const FRONTEND = 'creative-mode/kixi-frontend';
const SECTIONS = [
  { key: 'feature', title: 'Novidades' },
  { key: 'fix', title: 'Correções' },
  { key: 'improvement', title: 'Melhorias internas' },
];
const FIRST_RELEASE_COMMITS = 100;
const MARKER = /<!--\s*kixi-release:\s*backend=([0-9a-f]{7,40})\s+frontend=([0-9a-f]{7,40})\s*-->/;

/** Promoções e PRs de dependências automáticas: não são conteúdo da versão. */
function isTransport(pr) {
  const title = pr.title || '';
  return /^(Promo(ção|ver)\b|Promover\b)/i.test(title) || /^(dependabot|renovate)/i.test(pr.user?.login || '') || pr.user?.type === 'Bot';
}

/** Sem o número no fim do título, sem travessões (a escrita do projeto não os usa) e com a primeira letra grande. */
function cleanTitle(title) {
  const text = (title || '')
    .replace(/\s*\(#\d+\)\s*$/, '')
    .replace(/\s+[—–]\s+/g, ': ')
    .replace(/[—–]/g, ', ')
    .replace(/^\s*(feat|fix|docs|chore|refactor|ci|test|perf)(\([^)]*\))?:\s*/i, '')
    .trim();
  return text ? text[0].toUpperCase() + text.slice(1) : text;
}

/** Issues que o PR fecha ("Closes #3", "Fixes #4"). Ignora o que está em comentários e em código. */
function closingIssues(body) {
  const clean = (body || '').replace(/<!--[\s\S]*?-->/g, '').replace(/```[\s\S]*?```/g, '').replace(/`[^`]*`/g, '');
  const found = [];
  for (const m of clean.matchAll(/\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s+((?:#\d+(?:\s*(?:,|e|and)\s*)?)+)/gi)) {
    for (const n of m[1].matchAll(/#(\d+)/g)) if (!found.includes(Number(n[1]))) found.push(Number(n[1]));
  }
  return found;
}

/** Novidade, correção ou melhoria interna, por etiqueta, depois pela branch e por fim pelo título. */
function classify(pr) {
  const labels = (pr.labels || []).map((l) => (typeof l === 'string' ? l : l.name || '').toLowerCase());
  const branch = (pr.head?.ref || pr.branch || '').toLowerCase();
  const title = (pr.title || '').toLowerCase();
  if (labels.some((l) => /bug|fix/.test(l)) || /^(fix|hotfix|bugfix)\//.test(branch) || /^fix(\(|:|\s)/.test(title)) return 'fix';
  if (labels.some((l) => /feature|enhancement|feat/.test(l)) || /^(feat|feature)\//.test(branch) || /^feat(\(|:|\s)/.test(title)) return 'feature';
  if (/^(docs|chore|refactor|ci|test|perf|build)\//.test(branch) || labels.some((l) => /docs|chore|ci|refactor|dependencies/.test(l))) return 'improvement';
  return 'improvement';
}

/** Data-versão `2026.10.17`; se já existir (duas versões no mesmo dia) acrescenta `.2`, `.3`. */
function nextVersion(date, existingTags) {
  const base = `${date.getUTCFullYear()}.${String(date.getUTCMonth() + 1).padStart(2, '0')}.${String(date.getUTCDate()).padStart(2, '0')}`;
  const taken = new Set((existingTags || []).map((t) => t.replace(/^v/, '')));
  if (!taken.has(base)) return base;
  let n = 2;
  while (taken.has(`${base}.${n}`)) n += 1;
  return `${base}.${n}`;
}

function readMarker(body) {
  const m = MARKER.exec(body || '');
  return m ? { backend: m[1], frontend: m[2] } : null;
}

const marker = ({ backend, frontend }) => `<!-- kixi-release: backend=${backend} frontend=${frontend} -->`;

function entry(pr, repo) {
  const issues = pr.issues.length ? `, fecha ${pr.issues.map((n) => `#${n}`).join(' e ')}` : '';
  const by = pr.author ? ` por @${pr.author}` : '';
  const link = repo === FRONTEND ? `[${FRONTEND}#${pr.number}](https://github.com/${FRONTEND}/pull/${pr.number})` : `#${pr.number}`;
  return `- ${pr.title} (${link})${by}${issues}`;
}

/**
 * As notas em Markdown. `groups` é { feature: { backend: [...], frontend: [...] }, fix: ..., improvement: ... }
 * com PRs { number, title, author, issues }.
 */
function render({ version, date, logoUrl, groups, sha, contributors, previous }) {
  const total = SECTIONS.reduce((n, s) => n + (groups[s.key]?.backend?.length || 0) + (groups[s.key]?.frontend?.length || 0), 0);
  const count = (key) => (groups[key]?.backend?.length || 0) + (groups[key]?.frontend?.length || 0);
  const day = date.toISOString().slice(0, 10);
  const lines = [
    `<p align="center"><img src="${logoUrl}" alt="Kixi" width="240"></p>`,
    '',
    `# Kixi ${version}`,
    '',
    `Versão de ${day}. ${total === 0 ? 'Sem alterações novas desde a última versão.' : `${count('feature')} novidades, ${count('fix')} correções e ${count('improvement')} melhorias internas.`}`,
    '',
  ];
  for (const s of SECTIONS) {
    const back = groups[s.key]?.backend || [];
    const front = groups[s.key]?.frontend || [];
    if (!back.length && !front.length) continue;
    lines.push(`## ${s.title}`, '');
    if (front.length) lines.push('### Aplicações (aluno, gestor e página pública)', '', ...front.map((p) => entry(p, FRONTEND)), '');
    if (back.length) lines.push('### Plataforma (backend)', '', ...back.map((p) => entry(p, BACKEND)), '');
  }
  if (contributors.length) lines.push('## Quem contribuiu', '', contributors.map((c) => `@${c}`).join(', '), '');
  if (previous) lines.push(`Alterações completas: [backend](https://github.com/${BACKEND}/compare/${previous.backend.slice(0, 7)}...${sha.backend.slice(0, 7)}) e [frontend](https://github.com/${FRONTEND}/compare/${previous.frontend.slice(0, 7)}...${sha.frontend.slice(0, 7)}).`, '');
  lines.push('---', '', `Backend em \`${sha.backend.slice(0, 7)}\` e frontend em \`${sha.frontend.slice(0, 7)}\`. Notas geradas pelo agente de versões do Kixi.`, '', marker(sha));
  return lines.join('\n');
}

/** Agrupa PRs já descritos por tipo e por repositório, mantendo a ordem de integração. */
function group(prsByRepo) {
  const groups = Object.fromEntries(SECTIONS.map((s) => [s.key, { backend: [], frontend: [] }]));
  for (const [side, prs] of [['backend', prsByRepo.backend], ['frontend', prsByRepo.frontend]]) {
    for (const pr of prs) groups[classify(pr)][side].push(pr);
  }
  return groups;
}

async function prsFor(github, repo, messages) {
  const [owner, name] = repo.split('/');
  const out = [];
  for (const number of parsePrNumbers(messages)) {
    let data;
    try {
      ({ data } = await github.rest.pulls.get({ owner, repo: name, pull_number: number }));
    } catch {
      continue;
    }
    if (!data.merged_at || isTransport(data)) continue;
    out.push({ number, title: cleanTitle(data.title), rawTitle: data.title, author: data.user?.login, issues: closingIssues(data.body), labels: data.labels, head: data.head });
  }
  return out;
}

async function commitMessages(github, repo, from, head) {
  const [owner, name] = repo.split('/');
  if (from) {
    const { data } = await github.rest.repos.compareCommitsWithBasehead({ owner, repo: name, basehead: `${from}...${head}`, per_page: 250 });
    return data.commits.map((c) => c.commit.message);
  }
  const { data } = await github.rest.repos.listCommits({ owner, repo: name, sha: head, per_page: FIRST_RELEASE_COMMITS });
  return data.map((c) => c.commit.message);
}

module.exports = async ({ github, context, core, dryRun = false, prodBranch = 'prod', frontendBranch = 'PRD' }) => {
  const [owner, repo] = BACKEND.split('/');
  const backendHead = (await github.rest.repos.getBranch({ owner, repo, branch: prodBranch })).data.commit.sha;
  const frontendHead = (await github.rest.repos.getBranch({ owner: 'creative-mode', repo: 'kixi-frontend', branch: frontendBranch })).data.commit.sha;

  const releases = (await github.rest.repos.listReleases({ owner, repo, per_page: 30 })).data;
  const previous = releases.map((r) => readMarker(r.body)).find(Boolean) || null;
  if (previous && previous.backend === backendHead && previous.frontend === frontendHead) {
    core.info('Nada de novo em prod desde a última versão: não há notas a publicar.');
    return;
  }

  const [backendMessages, frontendMessages] = await Promise.all([
    commitMessages(github, BACKEND, previous?.backend, backendHead),
    commitMessages(github, FRONTEND, previous?.frontend, frontendHead),
  ]);
  const [backendPrs, frontendPrs] = await Promise.all([prsFor(github, BACKEND, backendMessages), prsFor(github, FRONTEND, frontendMessages)]);
  const date = new Date();
  const version = nextVersion(date, releases.map((r) => r.tag_name));
  const contributors = [...new Set([...backendPrs, ...frontendPrs].map((p) => p.author).filter(Boolean))].sort();
  const sha = { backend: backendHead, frontend: frontendHead };
  const body = render({
    version,
    date,
    logoUrl: `https://raw.githubusercontent.com/${BACKEND}/${backendHead}/.github/assets/kixi-logo.svg`,
    groups: group({ backend: backendPrs, frontend: frontendPrs }),
    sha,
    contributors,
    previous,
  });

  if (dryRun) {
    core.info(body);
    await core.summary.addRaw(body).write();
    return;
  }
  await github.rest.repos.createRelease({ owner, repo, tag_name: `v${version}`, target_commitish: backendHead, name: `Kixi ${version}`, body, draft: false, prerelease: false });
  core.info(`Versão v${version} publicada.`);
};

Object.assign(module.exports, { isTransport, cleanTitle, closingIssues, classify, nextVersion, readMarker, marker, render, group, SECTIONS });

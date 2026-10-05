// Alerta de bloqueio — quando uma issue fecha (ou o PR dela faz merge), avisa as issues que dependiam dela.
// As dependências vêm da secção "## 🔗 Depende de" do corpo da issue, ex.:
//   #104 (BE-08), creative-mode/kixi#110 (BE-14)

const REPOS = ['creative-mode/kixi', 'creative-mode/kixi-frontend'];

/** Lê a secção "Depende de" e devolve chaves "owner/repo#n". */
function parseDeps(body, ownRepo) {
  const sec = /##[^\n]*Depende de[^\n]*\n([\s\S]*?)(?=\n---|\n##\s|$)/i.exec(body || '');
  if (!sec) return [];
  const keys = new Set();
  for (const m of sec[1].matchAll(/(?:([\w.-]+\/[\w.-]+))?#(\d+)/g)) keys.add(`${m[1] || ownRepo}#${m[2]}`.toLowerCase());
  return [...keys];
}

const split = (key) => { const [repo, n] = key.split('#'); return { repo, number: Number(n) }; };

const { closingRefs } = require('./pr-check.js');

async function alertFor({ github, context, core }, closed) {
  const closedKey = `${context.repo.owner}/${context.repo.repo}#${closed.number}`.toLowerCase();
  const notPlanned = closed.state_reason === 'not_planned';
  const stateCache = new Map([[closedKey, 'closed']]);

  async function isClosed(key) {
    if (stateCache.has(key)) return stateCache.get(key) === 'closed';
    const { repo, number } = split(key);
    const [o, r] = repo.split('/');
    try {
      const { data } = await github.rest.issues.get({ owner: o, repo: r, issue_number: number });
      stateCache.set(key, data.state);
    } catch (e) { stateCache.set(key, 'open'); core.warning(`Sem acesso a ${key}: ${e.message}`); }
    return stateCache.get(key) === 'closed';
  }

  let notified = 0;
  for (const full of REPOS) {
    const [owner, repo] = full.split('/');
    const open = await github.paginate(github.rest.issues.listForRepo, { owner, repo, state: 'open', per_page: 100 });
    for (const issue of open) {
      if (issue.pull_request) continue;
      const deps = parseDeps(issue.body, full);
      if (!deps.includes(closedKey)) continue;

      const remaining = [];
      for (const d of deps.filter((d) => d !== closedKey)) if (!(await isClosed(d))) remaining.push(d);

      const marker = `<!-- dep-alert:${closedKey} -->`;
      const comments = await github.paginate(github.rest.issues.listComments, { owner, repo, issue_number: issue.number, per_page: 100 });
      if (comments.some((c) => c.body && c.body.includes(marker))) continue;

      const who = (issue.assignees || []).map((a) => `@${a.login}`).join(' ');
      const ref = `${context.repo.owner}/${context.repo.repo}#${closed.number}`;
      const ownRef = full.toLowerCase() === `${context.repo.owner}/${context.repo.repo}`.toLowerCase() ? `#${closed.number}` : ref;
      const note = notPlanned ? ' (fechada como *não planeada* — confirma se a dependência ainda se aplica)' : '';
      const body = remaining.length === 0
        ? `${marker}\n### 🔓 Desbloqueada\n${who ? who + ' — ' : ''}a dependência ${ownRef} **${closed.title}** foi fechada${note} e já não há dependências em aberto. Podes avançar${issue.milestone ? ` (prazo do milestone *${issue.milestone.title}*: ${issue.milestone.due_on ? issue.milestone.due_on.slice(0, 10) : 'sem data'})` : ''}.`
        : `${marker}\n### ℹ️ Dependência concluída\n${ownRef} **${closed.title}** foi fechada${note}. Ainda faltam: ${remaining.map((k) => k.replace(`${full.toLowerCase()}#`, '#')).join(', ')}.`;
      try {
        await github.rest.issues.createComment({ owner, repo, issue_number: issue.number, body });
        notified++;
      } catch (e) {
        core.warning(`Sem permissão para comentar em ${full}#${issue.number} (configura o secret CROSS_REPO_TOKEN para alertas entre repositórios): ${e.message}`);
      }
    }
  }
  core.info(`Alertas enviados para #${closed.number}: ${notified}`);
}

module.exports = async (ctx) => {
  const { github, context, core } = ctx;
  const { owner, repo } = context.repo;

  // 1) Issue fechada (manualmente ou pelo GitHub)
  if (context.eventName === 'issues') {
    if (!context.payload.issue.pull_request) await alertFor(ctx, context.payload.issue);
    return;
  }

  // 2) PR com merge: como os PRs vão para dev (não para a branch por defeito) o GitHub não fecha
  //    as issues sozinho — fechamos aqui as que o PR declara com "Closes #n" e avisamos os dependentes.
  const pr = context.payload.pull_request;
  if (!pr || !pr.merged) return;
  for (const n of [...new Set(closingRefs(pr.body, `${owner}/${repo}`))]) {
    let issue;
    try { issue = (await github.rest.issues.get({ owner, repo, issue_number: n })).data; } catch { continue; }
    if (issue.pull_request) continue;
    if (issue.state === 'open') {
      await github.rest.issues.createComment({ owner, repo, issue_number: n, body: `✅ Concluída pelo merge do PR #${pr.number} em \`${pr.base.ref}\`.` });
      issue = (await github.rest.issues.update({ owner, repo, issue_number: n, state: 'closed', state_reason: 'completed' })).data;
    }
    await alertFor(ctx, issue);
  }
};
module.exports.parseDeps = parseDeps;

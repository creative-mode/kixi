// Promotor qua → prod — mantém aberto o PR de promoção e faz o merge quando o gate passa.
//
// `prod` é o release (um push a prod publica as imagens), por isso o bot NUNCA promove às cegas:
//   - prepara sozinho o PR `qua → prod` com a lista do que vai sair;
//   - só faz merge quando o gate passa:
//       1. os checks obrigatórios estão verdes no head do PR (= head de `qua`);
//       2. há ≥1 aprovação, no head actual, de quem tem escrita (e que não é o autor do PR);
//       3. ninguém pediu alterações, não há conflitos e o PR não é rascunho.
//   - depois do merge dispara o publish.yml, porque merges feitos com GITHUB_TOKEN não disparam workflows.

const MARKER_START = '<!-- promover:estado -->';
const MARKER_END = '<!-- /promover:estado -->';
const TITLE = 'Promoção qua → prod';
const WRITE_LEVELS = new Set(['admin', 'maintain', 'write']);

/** Números dos PRs referidos nas mensagens ("Merge pull request #12 …" ou "título (#12)"). */
function parsePrNumbers(messages) {
  const found = [];
  for (const msg of messages) {
    const line = (msg || '').split('\n')[0];
    const m = /^Merge pull request #(\d+)\b/.exec(line) || /\(#(\d+)\)\s*$/.exec(line);
    if (m && !found.includes(Number(m[1]))) found.push(Number(m[1]));
  }
  return found;
}

/** Último estado relevante (APPROVED / CHANGES_REQUESTED) de cada revisor; comentários e dispensados não contam. */
function latestReviewStates(reviews) {
  const byUser = new Map();
  for (const r of reviews) {
    if (!r.user || !r.user.login) continue;
    if (r.state === 'APPROVED' || r.state === 'CHANGES_REQUESTED') {
      byUser.set(r.user.login, { state: r.state, commit: r.commit_id, type: r.user.type });
    }
  }
  return byUser;
}

/**
 * Decide se a promoção pode avançar. Devolve { ok, items:[{ ok, text }] } para o PR mostrar o que falta.
 * `checkRuns` são os check-runs do head do PR; `permissions` mapeia login → nível no repositório.
 */
function gate({ pr, requiredChecks, checkRuns, reviews, permissions, minApprovals = 1 }) {
  const items = [];
  const add = (ok, text) => items.push({ ok: Boolean(ok), text });
  const short = pr.head.sha.slice(0, 7);

  add(!pr.draft, pr.draft ? 'O PR está em rascunho' : 'O PR não está em rascunho');

  if (pr.mergeable === true) add(true, 'Sem conflitos com `prod`');
  else if (pr.mergeable === false) add(false, 'O PR tem conflitos com `prod`');
  else add(false, 'O GitHub ainda está a calcular os conflitos');

  for (const name of requiredChecks) {
    const latest = checkRuns
      .filter((c) => c.name === name)
      .sort((a, b) => new Date(b.started_at || 0) - new Date(a.started_at || 0))[0];
    if (!latest) add(false, `Check \`${name}\` ainda não correu em \`${short}\``);
    else if (latest.status !== 'completed') add(false, `Check \`${name}\` ainda está a correr em \`${short}\``);
    else add(latest.conclusion === 'success', `Check \`${name}\`: ${latest.conclusion} em \`${short}\``);
  }

  const states = latestReviewStates(reviews);
  const blockers = [...states].filter(([, v]) => v.state === 'CHANGES_REQUESTED').map(([login]) => `@${login}`);
  add(blockers.length === 0, blockers.length ? `Alterações pedidas por ${blockers.join(', ')}` : 'Sem pedidos de alterações');

  const approvals = [...states].filter(
    ([login, v]) => v.state === 'APPROVED'
      && v.commit === pr.head.sha
      && login !== pr.user.login
      && v.type !== 'Bot'
      && WRITE_LEVELS.has(permissions[login]),
  );
  add(
    approvals.length >= minApprovals,
    approvals.length >= minApprovals
      ? `Aprovado por ${approvals.map(([login]) => `@${login}`).join(', ')} em \`${short}\``
      : `Falta aprovação (${minApprovals} necessária) de quem tem escrita, sobre \`${short}\``,
  );

  return { ok: items.every((i) => i.ok), items };
}

/** Texto do PR (a parte gerada fica entre marcadores, para o bot a poder actualizar sem tocar no resto). */
function buildBody({ commitCount, partial, changes, result, merging }) {
  const lines = [MARKER_START, `### Promoção \`qua\` → \`prod\``];
  lines.push(`Publica em produção o que está em \`qua\` (${commitCount}${partial ? '+' : ''} commits). O merge arranca a publicação das imagens.`);
  lines.push('', '#### Incluído');
  if (changes.length === 0) lines.push('- _(sem PRs identificados nas mensagens dos commits)_');
  for (const c of changes) lines.push(c.title ? `- #${c.number} ${c.title} (@${c.author})` : `- #${c.number}`);
  if (partial) lines.push('- _(lista parcial: há mais commits do que os analisados)_');
  lines.push('', '#### Estado da promoção');
  if (result) for (const i of result.items) lines.push(`- [${i.ok ? 'x' : ' '}] ${i.text}`);
  lines.push('');
  lines.push(result && result.ok
    ? (merging ? '✅ Gate cumprido — o bot vai fazer o merge.' : '✅ Gate cumprido (merge automático desligado nesta execução).')
    : '⏳ Para promover: aprovar este PR (quem tem escrita) quando os checks estiverem verdes. O bot faz o resto.');
  lines.push(MARKER_END);
  return lines.join('\n');
}

/** Substitui a parte gerada de um corpo existente; se não houver marcadores, acrescenta no fim. */
function mergeBody(existing, generated) {
  const start = (existing || '').indexOf(MARKER_START);
  const end = (existing || '').indexOf(MARKER_END);
  if (start === -1 || end === -1 || end < start) return `${(existing || '').trim()}\n\n${generated}`.trim();
  return existing.slice(0, start) + generated + existing.slice(end + MARKER_END.length);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

module.exports = async ({ github, context, core }) => {
  const { owner, repo } = context.repo;
  const QUA = process.env.QUA_BRANCH;
  const PROD = process.env.PROD_BRANCH;
  const requiredChecks = (process.env.REQUIRED_CHECKS || '').split(',').map((s) => s.trim()).filter(Boolean);
  const usingPat = process.env.PROMOTE_TOKEN_SET === 'true';
  const mergeAllowed = process.env.MERGE !== 'false';
  const sleepMs = Number(process.env.RETRY_SLEEP_MS ?? 2000);

  // 1. Há algo novo em qua?
  const cmp = await github.rest.repos.compareCommitsWithBasehead({ owner, repo, basehead: `${PROD}...${QUA}`, per_page: 100 });
  const { data: existing } = await github.rest.pulls.list({ owner, repo, state: 'open', head: `${owner}:${QUA}`, base: PROD });
  let pr = existing[0];
  if (cmp.data.ahead_by === 0) {
    core.info(`\`${QUA}\` não tem nada que \`${PROD}\` não tenha: nada a promover.`);
    if (pr) core.warning(`O PR #${pr.number} está aberto mas já não tem alterações.`);
    return;
  }

  // 2. O que vai sair
  const numbers = parsePrNumbers(cmp.data.commits.map((c) => c.commit.message)).slice(-50);
  const changes = [];
  for (const number of numbers) {
    try {
      const { data } = await github.rest.pulls.get({ owner, repo, pull_number: number });
      changes.push({ number, title: data.title, author: data.user.login });
    } catch (e) {
      changes.push({ number });
    }
  }
  const info = { commitCount: cmp.data.ahead_by, partial: cmp.data.commits.length < cmp.data.ahead_by, changes };

  // 3. Garantir o PR de promoção
  if (!pr) {
    try {
      const created = await github.rest.pulls.create({
        owner, repo, head: QUA, base: PROD, title: TITLE, body: buildBody({ ...info, result: null }),
      });
      pr = created.data;
      core.info(`Criado o PR #${pr.number}.`);
    } catch (e) {
      core.setFailed(`Não consegui criar o PR de promoção: ${e.message}. Activa "Allow GitHub Actions to create and approve pull requests" (Settings → Actions → General) ou cria o secret PROMOTE_TOKEN.`);
      return;
    }
  }

  // 4. Estado actual do PR (a mergeabilidade demora uns segundos a ser calculada)
  let fresh = pr;
  for (let i = 0; i < 4; i += 1) {
    fresh = (await github.rest.pulls.get({ owner, repo, pull_number: pr.number })).data;
    if (fresh.mergeable !== null && fresh.mergeable !== undefined) break;
    await sleep(sleepMs);
  }

  const checkRuns = await github.paginate(github.rest.checks.listForRef, { owner, repo, ref: fresh.head.sha, per_page: 100 });
  const reviews = await github.paginate(github.rest.pulls.listReviews, { owner, repo, pull_number: pr.number, per_page: 100 });
  const permissions = {};
  for (const login of new Set(reviews.filter((r) => r.user && r.user.type !== 'Bot').map((r) => r.user.login))) {
    try {
      permissions[login] = (await github.rest.repos.getCollaboratorPermissionLevel({ owner, repo, username: login })).data.permission;
    } catch (e) {
      permissions[login] = 'none';
    }
  }

  const result = gate({ pr: fresh, requiredChecks, checkRuns, reviews, permissions });
  const generated = buildBody({ ...info, result, merging: mergeAllowed });
  const body = mergeBody(fresh.body, generated);
  if (body !== fresh.body || fresh.title !== TITLE) {
    await github.rest.pulls.update({ owner, repo, pull_number: pr.number, title: TITLE, body });
  }

  // 5. Merge + publicação
  if (!result.ok) {
    core.info(`Gate por cumprir:\n${result.items.filter((i) => !i.ok).map((i) => `- ${i.text}`).join('\n')}`);
    return;
  }
  if (!mergeAllowed) {
    core.info('Gate cumprido, mas o merge está desligado nesta execução.');
    return;
  }

  try {
    await github.rest.pulls.merge({ owner, repo, pull_number: pr.number, merge_method: 'merge', sha: fresh.head.sha });
    core.info(`PR #${pr.number} integrado em ${PROD}.`);
  } catch (e) {
    core.warning(`Não consegui fazer o merge do PR #${pr.number}: ${e.message}`);
    return;
  }

  if (usingPat) {
    core.info('Merge feito com PROMOTE_TOKEN: o push a prod dispara o publish.yml por si.');
    return;
  }
  try {
    await github.rest.actions.createWorkflowDispatch({ owner, repo, workflow_id: process.env.PUBLISH_WORKFLOW || 'publish.yml', ref: PROD });
    core.info('publish.yml disparado manualmente (merges com GITHUB_TOKEN não disparam workflows).');
  } catch (e) {
    core.warning(`Merge feito, mas não consegui disparar o publish.yml: ${e.message}. Corre-o manualmente em prod.`);
  }
};
module.exports.parsePrNumbers = parsePrNumbers;
module.exports.latestReviewStates = latestReviewStates;
module.exports.gate = gate;
module.exports.buildBody = buildBody;
module.exports.mergeBody = mergeBody;
module.exports.TITLE = TITLE;

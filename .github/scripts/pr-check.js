// Verificador de PR — aplica as regras do handbook (conceptual/workflow/@index.md).
// Issue → Branch → Commit → Pull Request → Merge.
//
// Regras:
//  1. A branch segue  <tipo>/<n.º da issue>-<descricao-curta>   (ex.: feature/23-login-com-jwt)
//  2. O PR fecha a issue:  "Closes #23"  (o n.º tem de coincidir com o da branch)
//  3. O PR aponta para a branch de integração (dev). Promoções seguem dev → qua → prod.
//  4. hotfix/* pode apontar a qualquer ambiente.

const TYPES = 'feature|feat|bug|fix|hotfix|refactor|docs|test|tests|chore|ci|devops|perf';
const BRANCH_RE = new RegExp(`^(${TYPES})/(\\d+)-[a-z0-9][a-z0-9._-]*$`);
const CLOSE_RE = /\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\s*:?\s+((?:(?:[\w.-]+\/[\w.-]+)?#\d+(?:\s*,\s*|\s+e\s+|\s+and\s+)?)+)/gi;
const MARKER = '<!-- pr-check -->';

function closingRefs(body, ownRepo) {
  const clean = (body || '').replace(/<!--[\s\S]*?-->/g, '').replace(/```[\s\S]*?```/g, '').replace(/`[^`]*`/g, '');
  const nums = [];
  for (const m of clean.matchAll(CLOSE_RE)) {
    for (const r of m[1].matchAll(/(?:([\w.-]+\/[\w.-]+))?#(\d+)/g)) {
      if (!r[1] || r[1].toLowerCase() === ownRepo.toLowerCase()) nums.push(Number(r[2]));
    }
  }
  return nums;
}

/** Devolve a lista de problemas (vazia = tudo certo). */
function evaluate({ head, base, body, ownRepo, branches, isBot = false }) {
  if (isBot || /^(dependabot|renovate)\//.test(head)) return [];
  const { dev, qua, prod } = branches;
  const problems = [];

  // Promoções entre ambientes
  if (head === dev || head === qua) {
    const expected = head === dev ? qua : prod;
    if (base !== expected) problems.push(`Promoção inválida: \`${head}\` só pode ir para \`${expected}\` (este PR aponta para \`${base}\`).`);
    return problems;
  }

  const m = BRANCH_RE.exec(head);
  if (!m) {
    problems.push(`O nome da branch \`${head}\` não segue o padrão \`<tipo>/<n.º da issue>-<descricao-curta>\` (ex.: \`feature/23-login-com-jwt\`). Tipos: ${TYPES.split('|').join(', ')}. Usa minúsculas, sem espaços.`);
  }

  if (m && m[1] !== 'hotfix' && base !== dev) {
    problems.push(`Os PRs de funcionalidade/correção apontam para \`${dev}\`, não para \`${base}\`.`);
  }

  const refs = closingRefs(body, ownRepo);
  if (refs.length === 0) {
    problems.push('O PR tem de fechar uma issue: escreve `Closes #<n.º>` na descrição.');
  } else if (m && !refs.includes(Number(m[2]))) {
    problems.push(`A branch refere a issue #${m[2]}, mas o PR só fecha ${refs.map((n) => `#${n}`).join(', ')}. Acrescenta \`Closes #${m[2]}\` ou corrige o nome da branch.`);
  }
  return problems;
}

module.exports = async ({ github, context, core }) => {
  const pr = context.payload.pull_request;
  const { owner, repo } = context.repo;
  const branches = { dev: process.env.DEV_BRANCH, qua: process.env.QUA_BRANCH, prod: process.env.PROD_BRANCH };
  const problems = evaluate({
    head: pr.head.ref, base: pr.base.ref, body: pr.body, ownRepo: `${owner}/${repo}`,
    branches, isBot: pr.user?.type === 'Bot',
  });

  // Comentário único (atualizado a cada execução)
  try {
    const comments = await github.paginate(github.rest.issues.listComments, { owner, repo, issue_number: pr.number, per_page: 100 });
    const mine = comments.find((c) => c.body && c.body.includes(MARKER));
    const body = problems.length
      ? `${MARKER}\n### ❌ Verificador de PR\n${problems.map((p) => `- ${p}`).join('\n')}\n\n_Corrige e este comentário actualiza-se sozinho._`
      : `${MARKER}\n### ✅ Verificador de PR\nBranch, destino e issue associada estão conformes com o workflow.`;
    if (mine) await github.rest.issues.updateComment({ owner, repo, comment_id: mine.id, body });
    else if (problems.length) await github.rest.issues.createComment({ owner, repo, issue_number: pr.number, body });
  } catch (e) {
    core.warning(`Não consegui comentar no PR: ${e.message}`);
  }

  if (problems.length) core.setFailed(problems.join('\n'));
  else core.info('PR conforme.');
};
module.exports.evaluate = evaluate;
module.exports.closingRefs = closingRefs;

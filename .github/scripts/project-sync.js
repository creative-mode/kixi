// Sincronizador do Projeto — mantém o GitHub Project (org) alinhado com issues e PRs.
//
//  issue aberta .............. entra no projeto, estado Backlog
//  branch <tipo>/<n>-xxx ..... issue #n  → In progress
//  PR aberto / pronto ........ PR e issues que fecha → In review
//  PR passa a rascunho ....... issues → In progress
//  PR fechado sem merge ...... issues → In progress
//  PR com merge / issue fechada → Done
//
// Precisa do secret PROJECT_TOKEN (PAT com permissões "project" e "repo"/Issues).
// O GITHUB_TOKEN normal NÃO consegue escrever em Projects de organização.

const { closingRefs } = require('./pr-check.js');

const ALIASES = {
  backlog: ['backlog', 'todo', 'to do', 'a fazer', 'por fazer'],
  progress: ['in progress', 'em progresso', 'em curso', 'doing', 'a fazer agora'],
  review: ['in review', 'review', 'em revisão', 'em revisao', 'revisão', 'revisao'],
  done: ['done', 'concluído', 'concluido', 'feito', 'fechado'],
};

const Q_PROJECT = `query($org:String!,$n:Int!){organization(login:$org){projectV2(number:$n){id
  fields(first:30){nodes{... on ProjectV2SingleSelectField{id name options{id name}}}}}}}`;
const M_ADD = `mutation($p:ID!,$c:ID!){addProjectV2ItemById(input:{projectId:$p,contentId:$c}){item{id}}}`;
const M_SET = `mutation($p:ID!,$i:ID!,$f:ID!,$o:String!){updateProjectV2ItemFieldValue(input:{projectId:$p,itemId:$i,fieldId:$f,value:{singleSelectOptionId:$o}}){projectV2Item{id}}}`;
const Q_STATUS = `query($i:ID!){node(id:$i){... on ProjectV2Item{fieldValueByName(name:"Status"){... on ProjectV2ItemFieldSingleSelectValue{name}}}}}`;

function pickOption(field, kind) {
  const wanted = ALIASES[kind];
  return field.options.find((o) => wanted.includes(o.name.trim().toLowerCase()));
}

module.exports = async ({ github, context, core }) => {
  if (!process.env.PROJECT_TOKEN_SET) { core.warning('Secret PROJECT_TOKEN em falta — sincronização ignorada.'); return; }
  const org = process.env.PROJECT_ORG || 'creative-mode';
  const number = Number(process.env.PROJECT_NUMBER || 4);
  const { owner, repo } = context.repo;
  const ev = context.eventName, p = context.payload;

  const data = await github.graphql(Q_PROJECT, { org, n: number });
  const project = data.organization.projectV2;
  const status = project.fields.nodes.find((f) => f && f.name === 'Status' && f.options);
  if (!status) { core.warning('O projeto não tem um campo "Status" de seleção única.'); return; }

  async function addItem(nodeId) {
    const r = await github.graphql(M_ADD, { p: project.id, c: nodeId });
    return r.addProjectV2ItemById.item.id;
  }
  async function currentStatus(itemId) {
    const r = await github.graphql(Q_STATUS, { i: itemId });
    return (r.node?.fieldValueByName?.name || '').trim().toLowerCase();
  }
  async function setStatus(nodeId, kind, { keepDone = false } = {}) {
    const opt = pickOption(status, kind);
    if (!opt) { core.warning(`Sem coluna "${kind}" no campo Status (${status.options.map((o) => o.name).join(', ')}).`); return; }
    const itemId = await addItem(nodeId);
    if (keepDone && ALIASES.done.includes(await currentStatus(itemId))) return;
    await github.graphql(M_SET, { p: project.id, i: itemId, f: status.id, o: opt.id });
    core.info(`→ ${kind}: ${nodeId}`);
  }
  async function issueNode(n) {
    try { return (await github.rest.issues.get({ owner, repo, issue_number: n })).data.node_id; }
    catch { core.warning(`Issue #${n} não encontrada.`); return null; }
  }
  async function linked(pr) { return Promise.all(closingRefs(pr.body, `${owner}/${repo}`).map(issueNode)); }

  if (ev === 'issues') {
    if (p.action === 'opened') await setStatus(p.issue.node_id, 'backlog');
    else if (p.action === 'reopened') await setStatus(p.issue.node_id, 'progress');
    else if (p.action === 'closed') await setStatus(p.issue.node_id, 'done');
  } else if (ev === 'create' && p.ref_type === 'branch') {
    const m = /^[a-z]+\/(\d+)-/.exec(p.ref);
    if (m) { const id = await issueNode(Number(m[1])); if (id) await setStatus(id, 'progress', { keepDone: true }); }
  } else if (ev === 'pull_request') {
    const pr = p.pull_request;
    const ids = (await linked(pr)).filter(Boolean);
    if (p.action === 'closed' && pr.merged) {
      await setStatus(pr.node_id, 'done');
      for (const id of ids) await setStatus(id, 'done');
    } else if (p.action === 'closed' || p.action === 'converted_to_draft') {
      for (const id of ids) await setStatus(id, 'progress', { keepDone: true });
    } else if (!pr.draft) {
      await setStatus(pr.node_id, 'review');
      for (const id of ids) await setStatus(id, 'review', { keepDone: true });
    } else {
      for (const id of ids) await setStatus(id, 'progress', { keepDone: true });
    }
  }
};
module.exports.pickOption = pickOption;

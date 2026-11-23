# #627 — a pauta da turma em PDF

O terceiro documento da cadeia dos relatórios (depois do #64 e do #626): o Kixi monta o pedido `POST /v1/pautas-turma`
a partir do registo escolar e o **motor real** valida-o e devolve o PDF. Não há ligação pelo meio: o PDF vem na
resposta, o Kixi não o guarda.

```
ok    professor da turma: 200 com PDF do motor
ok    a resposta não fica em cache
ok    aluno: 403
ok    outro trimestre sem pautas: 409
ok    auditoria regista a exportação como PAUTA_TURMA
pauta da turma verificada
```

Ensaio: Kixi deste ramo (PostgreSQL, Flyway) + motor SchoolOps `dev` com `relatorios.pdf=true` em `18091` + fixture do
registo escolar (instituição, turma, curso, duas disciplinas, um aluno com número, folhas `PT-627-TLP` e `PT-627-MAT`,
entradas NPP/NPT/MT). O motor respondeu 200 com um PDF de **22 KB** — a validação do contrato é dele, por isso o 200 é a
prova de que o pedido está certo.

## O que fica provado

1. **O pedido é o do contrato** — org, sessão, turma, curso, alunos (número e nome) e, por disciplina, P1/P2/MAC/MD com as
   notas e a situação. O motor recusa com 422 se faltar algo; deu 200.
2. **As notas vêm do registo** — `register_term_sheets`/`register_term_entries`, os componentes NPP/NPT/MAC/MT do Kixi
   traduzidos para P1/P2/MAC/MD.
3. **Permissões** — quem pode ler o plano exporta (professor da turma e disciplina, coordenador, ADMIN); o aluno não chega
   à rota (403).
4. **Sem pautas no trimestre** → 409 com mensagem; **sem cache** (`Cache-Control: no-store`); **auditada** em
   `report_exports` como `PAUTA_TURMA`.

## Ficheiros

| Ficheiro | O que é |
|---|---|
| `pauta-turma.pdf` | O PDF que o motor devolveu |
| `resumo.json` | Status, bytes, `Cache-Control`, 403 do aluno e a linha de auditoria |

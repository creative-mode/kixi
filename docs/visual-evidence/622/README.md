# #622 — exportar a pauta e os resultados em PDF pelo backend

Ensaio real: o Kixi deste ramo a correr com PostgreSQL (Flyway até à `V89`) e o **motor simulado**, como a issue pede —
um servidor que responde a `POST /v1/relatorios` com uma ligação assinada e serve o PDF de uma pauta real (`#64`). A conta
de integração do Kixi é a que pede a ligação; o PDF passa pelo backend e **não fica guardado** (nem o link).

```
ok    professor da turma: 200 com PDF real
ok    professor da turma: nome do ficheiro inline
ok    professor da turma: resultado da prova 200 com PDF
ok    coordenador da instituição: 200
ok    administrador: 200
ok    aluno: 403
ok    professor de outra escola: 403
ok    o motor recebeu MINI_PAUTA da pauta do plano
ok    o motor recebeu RESULTADO_PROVA com o enunciado da avaliação
ok    plano sem pauta no motor: 409
ok    motor sem PDF gerado: 409
ok    ligação do motor para outro anfitrião: 502
ok    avaliação sem enunciado: 409
ok    motor em baixo: 503 com mensagem
ok    motor de volta: 200
ok    auditoria regista as exportações com quem, plano e documento
exportação verificada
```

## O que fica provado

1. **Papéis** — o professor da turma e disciplina, o coordenador da instituição e o administrador exportam; o aluno e o
   professor de outra escola recebem 403 (o aluno nem chega ao serviço: a regra das rotas não o deixa entrar).
2. **O pedido certo ao motor** — `MINI_PAUTA` com a pauta do plano; `RESULTADO_PROVA` com o enunciado da avaliação
   (`provaId`), tal como o motor o conhece.
3. **O PDF é o do motor** — o `GET` da ligação devolve `application/pdf`; os bytes entregues ao professor são os mesmos
   (`mini-pauta.pdf` aqui).
4. **Erros legíveis** — plano sem pauta no motor: 409; PDF ainda por gerar: 409; ligação para outro anfitrião (SSRF): 502;
   avaliação sem enunciado: 409; motor em baixo: 503 e volta a 200 quando ele volta.
5. **Auditoria** — `report_exports` com quem, plano, documento e (nas provas) a avaliação; a exportação que falhou não
   deixa linha.

## Ficheiros

| Ficheiro | O que é |
|---|---|
| `ensaio.txt` | A saída verbatim do ensaio (16 verificações) |
| `auditoria.txt` | As linhas de `report_exports` no fim do ensaio |
| `pedidos-ao-motor.json` | O que o Kixi pediu ao motor (pauta, tipo e prova) |
| `mini-pauta.pdf` | O PDF que o Kixi devolveu ao professor |

## Fora deste PR

A **pauta da turma** (o terceiro documento da issue) precisa de juntar, por disciplina do curso, as notas de todos os
alunos num pedido `POST /v1/pautas-turma`; fica para uma issue de seguimento, com o endpoint do motor já pronto.

## Revisão (Noé) — o que ficou resolvido

- **Buffer do PDF**: o codec do `WebClient` traz **256 KB** por omissão e uma pauta real de turma grande passa disso; o
  cliente passou a fixar **5 MB** (`ExchangeStrategies`, como o `OcrServiceClient` e o `GroqClient`). O ensaio agora serve
  uma pauta de **1,5 MB** (`mini-pauta.pdf` abaixo, o PDF real do #64 com preenchimento) e o Kixi devolve os **mesmos
  bytes** — antes do limite isto acabava em 500. Há também um teste unitário com 1,5 MB.
- **`Cache-Control: no-store`** na resposta do PDF (dado pessoal; um proxy partilhado da escola não o guarda).
- **`report_exports` sem FK para `accounts`**, agora documentado na migração: o registo é a prova do acesso e sobrevive
  à eliminação da conta.
- **Auditoria com falha fechada**: se o registo falhar, a resposta é **503** com «Não foi possível registar a exportação;
  tente de novo.» em vez de um 500 de base de dados.

A auditoria do ensaio (`auditoria.txt`) e os pedidos ao motor (`pedidos-ao-motor.json`) são os da corrida com o PDF grande.

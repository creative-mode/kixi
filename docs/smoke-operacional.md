# Smoke operacional: professor → OCR → prova → aluno → nota

Fecha, quando for corrido no ambiente de teste com o resultado anexado, os critérios operacionais da #403 (e, com ela,
da #365). **Não há servidor de QUA: o ambiente em que isto se testa é a PRD.** Os testes automáticos e o stack local
**não** substituem esse smoke: o que se comprovou localmente está no fim, separado do que continua por comprovar. Onde
as issues dizem «QUA», lê-se «PRD».

## Como correr

O script `services/backend-api/scripts/smoke_teacher_student.py` faz o fluxo inteiro pela API real e só usa a
biblioteca standard do Python:

```bash
BASE=https://<host-da-prd>/api/v1 \
ADMIN_USER=<administrador> ADMIN_PASS=<palavra-passe> \
OCR_PDF=/caminho/prova-varias-paginas.pdf \
OCR_IMAGE=/caminho/prova.jpg:/caminho/outra-prova.png \
SMOKE_OUT=smoke-prd.json \
python3 services/backend-api/scripts/smoke_teacher_student.py
```

- O administrador serve só para criar os dados de teste (escola «Smoke <sufixo>», curso, turma, disciplina,
  professor e aluno). Nada é apagado.
- **Em PRD, sem criar escola, curso nem disciplina novos (modo «escola existente»):** `ADMIN_USER`, `ADMIN_PASS`,
  `SMOKE_INSTITUTION_ID`, `SMOKE_COURSE_ID`, `SMOKE_SUBJECT_ID` (uma disciplina já no currículo do curso) e
  `SMOKE_SCHOOL_YEAR_ID`. Cria só uma turma «SMK<sufixo>», um professor e um aluno de teste, isolados das turmas
  reais. Com `SMOKE_CLEANUP=1` tira tudo no fim (código de matrícula, matrícula, atribuição, afiliação, conta e ficha
  do professor, conta do aluno e a turma) e verifica que cada passo respondeu 200 ou 204; as provas e as simulações
  vão para a lixeira (apagadas, recuperáveis). Testado localmente: a turma desaparece e não fica nada.
- **Em PRD, sem povoar a base com escolas e contas de teste:** em vez do administrador, usa um professor e um
  aluno que já existem (o aluno matriculado na turma, o professor com a disciplina atribuída) e os ids da turma:
  `TEACHER_USER`, `TEACHER_PASS`, `STUDENT_USER`, `STUDENT_PASS`, `SMOKE_INSTITUTION_ID`, `SMOKE_COURSE_ID`,
  `SMOKE_CLASS_ID`, `SMOKE_SUBJECT_ID`, `SMOKE_SCHOOL_YEAR_ID`. Cria só uma prova por ficheiro e uma simulação
  por prova, com o título «Smoke <sufixo>». Com `SMOKE_CLEANUP=1` manda as provas para a lixeira no fim; as
  simulações só um administrador as apaga (junta `ADMIN_USER` e `ADMIN_PASS`), porque o aluno recebe 403.
- Cada ficheiro de OCR_PDF/OCR_IMAGE é enviado ao OCR pelo professor, e com o que se leu constrói-se, aprova-se e
  publica-se uma prova (cotação distribuída por 20 valores, gabarito de teste na primeira opção). O aluno
  matriculado abre a prova, responde (certo em metade das questões fechadas), submete e lê a nota.
- Confere: resposta 200 do OCR, questões lidas, a prova publicada tem as mesmas questões e o mesmo texto que o OCR
  leu, a cotação soma 20, o aluno não recebe o gabarito, a nota calculada pelo servidor bate com o gabarito nas
  questões fechadas, e o aluno volta a ver o resultado.
- Sem `OCR_PDF` o PDF não é testado: o resultado é «PARCIAL» e o código de saída é 3. Com falhas, 1. Tudo certo, 0.
- Não imprime palavras-passe nem tokens. Os ficheiros de prova são dados de quem corre: não vão para o repositório.

## O que anexar na #403 (resultado da PRD)

| Critério | Onde sai |
|---|---|
| Ambiente (PRD) e commit implantado | URL em `BASE`; o commit implantado (`git rev-parse` do que foi publicado, ou o que o ambiente expõe) anota-se à mão |
| PDF real, várias páginas | passo `ocr pdf` e `OCR do pdf` no relatório; `pages` > 1 |
| Imagem real | passo `ocr imagem` |
| Request IDs e tempos | `requestIds` e `steps` no JSON de `SMOKE_OUT` (um `X-Request-ID` por etapa crítica) |
| Smoke professor → aluno → nota | `RESULTADO: OK` e a secção `results` |
| Construtor, impressão/resultado | screenshots à mão: a prova aberta em «Rever enunciado» / «Ver a folha» do Manager, impressa, e o resultado do aluno. Sem credenciais nem dados pessoais visíveis (as contas de teste têm nomes neutros) |
| Logs | os do backend e do OCR para os request IDs acima |

## O que se comprovou na PRD (23/10/2026)

Corrido dentro do contentor do OCR, pela rede interna (`http://backend-api:8080/api/v1`), no modo «escola existente»
com `SMOKE_CLEANUP=1`: Instituto de Telecomunicações, curso INFO, disciplina Física, ano lectivo 2026. Cria uma turma
«SMK<sufixo>», um professor e um aluno de teste, e desfaz tudo no fim. Imagens em produção a ser as de `prod` em
`52d8bae`; os contentores em execução foram criados a 20/10/2026 (backend `sha256:a20caab6…`, OCR `sha256:ad55acba…`).

| Etapa | Resultado |
|---|---|
| OCR da prova de OGI (imagem) | 200, 6 questões, 8,5 s, `req-3ccc17bbb95e4f9da35ff3ce5e4833d5` |
| OCR da prova de Física (imagem) | 200, 4 questões (3 com fórmulas), 8 figuras/regiões, 8,9 s, `req-8a13738f46154512ab6ad23966c19706` |
| Prova publicada | mesmas questões, ordem e texto que o OCR leu; cotação soma 20; aprovada pelo gate (`req-8766814a…`, `req-ec830d48…`) |
| Aluno | não recebe o gabarito; responde a tudo; submete; a nota é calculada no servidor (`req-53a1e942…`, `req-406171d0…`) |
| Nota (OGI) | 2,22 de 20, 2 certas em 9, 6 por rever; bate com o gabarito nas 3 questões fechadas |
| Nota (Física) | 0 de 20: só tem questões abertas, por isso a nota fica por corrigir e este caso **não** prova o cálculo |
| Limpeza | 2 provas e 2 simulações para a lixeira; código de matrícula, matrícula, atribuição, afiliação, conta e ficha do professor, conta do aluno e turma de teste retirados (200/204); confirmado depois que não ficou turma, professor nem conta de teste |

32 de 32 verificações passam (a segunda corrida; a primeira, igual, só falhou ao gravar o relatório). **PDF de várias páginas: ver abaixo.**

### PDF de várias páginas: falhou em produção e derrubou o OCR

Um PDF de 2 páginas (as duas fotografias, ~280 KB) enviado ao OCR de produção fez o processo ser morto pelo limite de
memória do contentor (4 GB: `Memory cgroup out of memory`, `anon-rss` 4,16 GB). O backend viu a ligação cair 46 s
depois do pedido e o Docker reiniciou o OCR sozinho, em cerca de 12 s. O smoke corria dentro desse contentor e foi morto
com ele, por isso não fez a limpeza e a turma, o professor e o aluno de teste foram retirados à mão (todos 200/204).
Está na #407; enquanto não estiver corrigido, **não se deve repetir este smoke com PDF em PRD** nem enviar PDFs de várias
páginas ao OCR de produção. Para correr o smoke com PDF sem arriscar o OCR, corre-se o script de fora do contentor.

## O que se comprovou localmente (não é a PRD)

Stack local real, sem mocks: backend de `origin/dev` (Spring Boot 3.5), Postgres 16, OCR construído do
`infra/docker/ocr.Dockerfile`, com o limite de 4 GB de produção. Duas fotografias reais de provas (Física de 12.ª,
com fórmulas, e OGI de 12.ª) enviadas como imagem:

| Etapa | Resultado |
|---|---|
| OCR da prova de OGI | 200, 6 questões, 44 s |
| OCR da prova de Física | 200, 4 questões (10 alíneas), 3 delas com fórmulas, 9 figuras/regiões, 54 s |
| Prova publicada | mesmas questões, mesma ordem, mesmo texto; cotação soma 20; aprovada pelo gate |
| Aluno | não recebe o gabarito; responde a tudo; submete; nota calculada no servidor; volta a ver o resultado |
| Nota (OGI) | 2,22 de 20, 2 certas em 9, 6 por rever; bate com o gabarito nas questões fechadas |
| Nota (Física) | 0 de 20: só tem questões abertas, por isso a nota fica por corrigir e este caso **não** prova o cálculo da nota |

31 de 31 verificações passam. No Manager, as duas provas aparecem aprovadas e publicadas, com as questões lidas.

## O que continua por comprovar na PRD (critérios da #403 ainda abertos)

- **PDF real com várias páginas**: não passa em PRD (#407). Falta a correcção e voltar a correr com `OCR_PDF`.
- **Screenshots da PRD**: do construtor com a importação do OCR, da folha impressa e do resultado do aluno. O smoke é só
  pela API; aqui só se capturou, localmente, o Manager com as provas publicadas.
- **Rodapé**: o OCR devolve o cabeçalho e a assinatura como regiões à parte (`cabecalho`, `rodape`) e não os mistura nas
  questões; o smoke não verifica o rodapé na prova impressa.
- **Páginas múltiplas** (depende do PDF).
- **Logs da PRD** para os request IDs acima, e a actualização do critério na #365 depois de tudo isto.

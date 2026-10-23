# Smoke operacional: professor → OCR → prova → aluno → nota

Fecha, quando for corrido em QUA com o resultado anexado, os critérios operacionais da #403 (e, com ela, da #365).
Os testes automáticos e o stack local **não** substituem o QUA: o que se comprovou localmente está no fim,
separado do que continua por comprovar.

## Como correr

O script `services/backend-api/scripts/smoke_teacher_student.py` faz o fluxo inteiro pela API real e só usa a
biblioteca standard do Python:

```bash
BASE=https://<host-do-qua>/api/v1 \
ADMIN_USER=<administrador> ADMIN_PASS=<palavra-passe> \
OCR_PDF=/caminho/prova-varias-paginas.pdf \
OCR_IMAGE=/caminho/prova.jpg:/caminho/outra-prova.png \
SMOKE_OUT=smoke-qua.json \
python3 services/backend-api/scripts/smoke_teacher_student.py
```

- O administrador serve só para criar os dados de teste (escola «Smoke <sufixo>», curso, turma, disciplina,
  professor e aluno). Nada é apagado.
- Cada ficheiro de OCR_PDF/OCR_IMAGE é enviado ao OCR pelo professor, e com o que se leu constrói-se, aprova-se e
  publica-se uma prova (cotação distribuída por 20 valores, gabarito de teste na primeira opção). O aluno
  matriculado abre a prova, responde (certo em metade das questões fechadas), submete e lê a nota.
- Confere: resposta 200 do OCR, questões lidas, a prova publicada tem as mesmas questões e o mesmo texto que o OCR
  leu, a cotação soma 20, o aluno não recebe o gabarito, a nota calculada pelo servidor bate com o gabarito nas
  questões fechadas, e o aluno volta a ver o resultado.
- Sem `OCR_PDF` o PDF não é testado: o resultado é «PARCIAL» e o código de saída é 3. Com falhas, 1. Tudo certo, 0.
- Não imprime palavras-passe nem tokens. Os ficheiros de prova são dados de quem corre: não vão para o repositório.

## O que anexar na #403

| Critério | Onde sai |
|---|---|
| Ambiente QUA e commit implantado | URL em `BASE`; o commit implantado (`git rev-parse` do que foi publicado, ou o que o ambiente expõe) anota-se à mão |
| PDF real, várias páginas | passo `ocr pdf` e `OCR do pdf` no relatório; `pages` > 1 |
| Imagem real | passo `ocr imagem` |
| Request IDs e tempos | `requestIds` e `steps` no JSON de `SMOKE_OUT` (um `X-Request-ID` por etapa crítica) |
| Smoke professor → aluno → nota | `RESULTADO: OK` e a secção `results` |
| Construtor, impressão/resultado | screenshots à mão: a prova aberta em «Rever enunciado» / «Ver a folha» do Manager, impressa, e o resultado do aluno. Sem credenciais nem dados pessoais visíveis (as contas de teste têm nomes neutros) |
| Logs | os do backend e do OCR para os request IDs acima |

## O que se comprovou localmente (não é QUA)

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

## O que continua por comprovar em QUA (critérios da #403 ainda abertos)

- **QUA identificado e commit implantado**: não existe QUA acessível a partir da máquina de desenvolvimento.
- **PDF real com várias páginas**: não foi testado. Na máquina local a memória estava esgotada por outros serviços
  (RAM e swap cheias) e o contentor do OCR era morto pelo sistema (código 137) assim que começava a rasterizar
  um PDF, mesmo de uma só página; não é uma conclusão sobre o OCR, só que ali não deu para o medir. Precisa de
  uma máquina com folga de memória.
- **Construtor com a importação do OCR, impressão e resultado do aluno em browser**: só ficaram capturas do
  Manager a mostrar a prova aprovada e publicada; a folha impressa e o ecrã do aluno não foram capturados (o
  servidor de desenvolvimento do front não compilou a tempo com a máquina sem memória).
- **Rodapé**: o OCR devolve o cabeçalho e a assinatura como regiões à parte (`cabecalho`, `rodape`) e não os mistura
  nas questões; o smoke não verifica o rodapé na prova impressa.
- **Páginas múltiplas** (depende do PDF acima).
- **Request IDs e logs de QUA**, screenshots de QUA, e a actualização do critério na #365 depois de tudo isto.

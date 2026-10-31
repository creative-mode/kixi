# Medir o OCR de folhas de resposta com folhas reais (kixi#499)

O OCR de folhas de resposta só se dá por pronto quando for medido com **letra de alunos**. Os ensaios com letra simulada
(`make kixi-e2e-papel` no SchoolOps) provam o caminho e a convenção da folha, não a leitura de caligrafia real. Este documento diz como
recolher as folhas e como medir; a ferramenta é `tools/medir_folhas.py`.

## 1. A folha

Use a folha-modelo, que segue a convenção que o OCR lê (cabeçalho «Nº» e «Nome:», e cada resposta a começar por «1)»):

```
python tools/folha_modelo.py --objectivas 8 --abertas 2 -o folha-modelo.pdf
```

Imprima uma por aluno. O aluno escreve a letra (ou V/F) depois do «)» e o texto das dissertativas nas linhas, de caneta azul ou preta.

## 2. Recolha, com proteção de dados

- **Consentimento.** A professora (ou a direção da escola) autoriza o uso das folhas para medir o sistema. Os alunos são menores: peça a autorização
  que a escola já usa para trabalhos e provas, e diga para que servem as folhas.
- **Anonimização, antes de sair da escola.** No campo «Nome:» o aluno escreve um código (`Aluno 07`), nunca o nome verdadeiro; o «Nº» é o do código. Nada
  de rostos, carimbos nem assinaturas na foto. Apague os metadados das fotos (EXIF, localização): `exiftool -all= *.jpg` ou, num telemóvel, partilhe como «imagem
  sem localização».
- **Onde guardar.** Fora do Git (as fotos nunca vão para o repositório nem para um PR). Uma pasta local com `fotos/`, `gabarito.csv` e `estrutura.json`.
- **Quantidade e variedade.** Pelo menos **30 folhas**, de pelo menos **3 caligrafias** diferentes, com **2 condições** de captura (digitalização ou foto com boa luz;
  foto com luz fraca, sombra ou inclinada). Misture alunos que escrevem bem e mal: é o que uma turma real tem.

## 3. Gabarito

`gabarito.csv` (UTF-8), uma linha por foto, com o que o aluno **escreveu** (não a resposta certa da prova), conferido por uma pessoa olhando para a folha:

```
ficheiro,numero,nome,q1,q2,q3,q4,q5,q6,q7,q8
aluno07.jpg,7,Aluno 07,B,C,A,,D,B,A,C
```

Vazio = o aluno deixou a questão em branco. `estrutura.json` é o contexto da prova, como o Kixi o envia:

```json
{"questions": [{"id": "1", "number": 1, "type": "multiple_choice", "options": ["A", "B", "C", "D"]}]}
```

## 4. Medir

```
python tools/medir_folhas.py --pasta fotos/ --gabarito gabarito.csv --estrutura estrutura.json \
    --url http://localhost:8000 --api-key "$OCR_API_KEY" --json relatorio.json
```

O relatório separa o que importa:

| Métrica | Significado |
|---|---|
| Certas | respostas objetivas lidas corretamente |
| Não lidas | o OCR não leu (`NOT_FOUND`); a professora transcreve na validação, não é erro silencioso |
| Erradas e duvidosas | lidas mal com confiança baixa; a validação humana apanha |
| **Erradas e confiantes** | **lidas mal com confiança alta (>= 0,8): o erro silencioso, o que decide** |
| Nº de aluno / nome certos | a identificação do aluno |
| Limite superior do erro silencioso | limite a 95 % (Wilson): com poucas respostas, 0 erros não prova <= 1 %. Com `--rigoroso` é ele que decide |

O código de saída é 0 só se cumprir a definição de pronto: exatidão >= 95 % (`--min-objectivas`), erros silenciosos <= 1 %
(`--max-erros-silenciosos`), >= 30 folhas (`--min-folhas`), nº de aluno >= 95 % (`--min-numero`), nenhuma linha do gabarito sem
foto e nenhuma falha do OCR. Gabarito ilegível ou inconsistente com a estrutura sai com código 2. O CSV pode ter BOM e usar `;`.
Passe a chave por `OCR_API_KEY` (ambiente) em vez de `--api-key`, para não ficar no histórico da shell.

## 5. Quando é que a #499 se dá por fechada

Com as folhas reais anonimizadas (>= 30, >= 3 caligrafias, >= 2 condições):

- exatidão nas objetivas **>= 95 %**;
- erros silenciosos (erradas e confiantes) **<= 1 %** das objetivas;
- nº de aluno certo **>= 95 %**;
- tudo o que o OCR não lê bem vem sinalizado (`NOT_FOUND`/`LOW_CONFIDENCE`/`AMBIGUOUS`), nunca como certeza.

Se não cumprir, o relatório lista cada erro (ficheiro, questão, esperada, lida, confiança): é a lista de trabalho para melhorar o OCR, não para baixar o limiar.
Cada ronda de medição deve ser comentada na #499 com o resumo (sem fotos).

## 6. O que já se mediu com letra simulada (não substitui folhas reais)

Teste de esforço com a própria `medir_folhas.py`: 7 fontes manuscritas muito diferentes (Patrick Hand, Kalam, Indie Flower, Reenie Beanie, Shadows Into Light,
Gloria Hallelujah, Covered By Your Grace) × 4 condições (digitalização, foto, foto escura, foto com sombra e perspetiva) = 28 folhas, 224 respostas objetivas (8 por folha). A exatidão (84,8 %) fica abaixo do limiar de 95 %, por isso o `medir_folhas.py` diria "NÃO cumpre"; é o resultado esperado de letra simulada:

| | |
|---|---|
| Certas | 190 (84,8 %) |
| Não lidas (a professora transcreve) | 34 (15,2 %) |
| Erradas e confiantes (erro silencioso) | **0** |
| Exatidão das lidas | 100 % |
| Nome certo / nº de aluno certo | 100 % / 75 % (os 7 erros foram «não lido», nunca outro aluno) |
| Tempo médio | 35 s por folha, com o host sobrecarregado |

Lê-se assim: o OCR falha **de forma segura** (não lê em vez de adivinhar), mas não lê tudo: 15 % das respostas vão para a transcrição. As fontes mais estilizadas
(Gloria Hallelujah, Reenie Beanie) são piores do que a letra típica de um aluno, e as simuladas são mais limpas do que a letra real: por isso fica por provar com folhas reais.

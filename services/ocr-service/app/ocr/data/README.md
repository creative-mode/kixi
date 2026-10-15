# Léxico português do OCR

`pt_words.tsv.gz` (palavra, frequência) alimenta `app/ocr/ptlex.py`: restaura acentos, corrige erros de OCR de uma
letra e separa palavras coladas. Gera-se com `python tools/build_pt_lexicon.py`.

## Origem e licença

- **Dados:** [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (Hermit Dave), lista
  `content/2018/pt/pt_50k.txt`, derivada de legendas do [OpenSubtitles](https://www.opensubtitles.org/).
  As 40 000 palavras mais frequentes que são só letras, sem as variantes acentuadas ruidosas.
  Licença dos dados: **Creative Commons Attribution-ShareAlike 4.0** (CC BY-SA 4.0).
  Este ficheiro de dados é uma obra derivada e mantém essa licença; o código do repositório não é afetado,
  porque o ficheiro é lido como dados e não faz parte do código.
- **Suplemento:** `pt_extra.txt`, vocabulário de provas escolares angolanas que a lista de legendas não traz
  (lectivo, aprovisionamento, esquematize…). Escrito à mão, sem restrições.

## Limites

É uma lista de legendas: tem pouca terminologia técnica e algum registo oral. Uma palavra fora do léxico não é
alterada, a não ser que haja uma correção inequívoca (restauro de acentos ou uma única letra trocada).

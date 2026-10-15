#!/usr/bin/env python3
"""Gera app/ocr/data/pt_words.tsv.gz, o léxico português usado para reparar o texto do OCR.

Fonte: FrequencyWords (hermitdave), lista `pt_50k` de 2018, derivada de legendas do OpenSubtitles.
Licença dos dados: CC-BY-SA 4.0 (ver app/ocr/data/README.md). Este script é MIT como o resto do repo.

Uso (a partir de services/ocr-service):
    python tools/build_pt_lexicon.py [caminho/para/pt_50k.txt]
Sem argumento, descarrega a lista do GitHub.

O que faz:
  1. Fica com as 40 000 palavras mais frequentes que sejam só letras.
  2. Remove variantes acentuadas ruidosas (ex.: "säo", "estã"): uma forma acentuada só fica se tiver pelo
     menos 2 % da frequência da forma mais frequente com a mesma letra-base. As legendas trazem erros de
     codificação e é isso que isto apanha.
  3. Tira as palavras de pt_exclude.txt (ruído das legendas, como "lsso", que impediria corrigir "isso").
  4. Gera as formas regulares dos verbos de enunciado (pt_exam_verbs.txt) com um piso de frequência.
  5. Junta um suplemento de vocabulário de provas escolares angolanas (app/ocr/data/pt_extra.txt) que a lista
     de legendas não traz. Palavras já presentes mantêm a frequência original.
"""
from __future__ import annotations

import gzip
import sys
import unicodedata
import urllib.request
from pathlib import Path

URL = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/pt/pt_50k.txt"
ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "ocr" / "data" / "pt_words.tsv.gz"
EXTRA = ROOT / "app" / "ocr" / "data" / "pt_extra.txt"
EXCLUDE = ROOT / "app" / "ocr" / "data" / "pt_exclude.txt"
VERBS = ROOT / "app" / "ocr" / "data" / "pt_exam_verbs.txt"
TOP = 40_000
MIN_VARIANT_SHARE = 0.02
EXTRA_FREQ = 600
VERB_FREQ = 30_000  # piso: um verbo de enunciado nunca é raro numa prova


def unaccent(text: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn")


def conjugate(infinitive: str) -> set[str]:
    """Formas regulares de um verbo em -ar/-er/-ir que as provas usam (imperativo, conjuntivo, presente, ...).

    Cobre as mudanças de grafia que interessam: -car/-gar/-çar (justifique, pague, esboce), -ger/-gir (escolha,
    dirija), -cer (esclareça), -guir (distinga). Verbos irregulares não entram: ficam como estão na lista.
    """
    ending, stem = infinitive[-2:], infinitive[:-2]
    forms = {infinitive}
    if ending == "ar":
        soft = stem[:-1] + "qu" if stem.endswith("c") else stem[:-1] + "gu" if stem.endswith("g") else stem[:-1] + "c" if stem.endswith("ç") else stem
        forms |= {stem + "a", soft + "e", soft + "em", stem + "am", stem + "ando", stem + "ado", stem + "ada",
                  stem + "ados", stem + "adas", stem + "amos", soft + "emos", stem + "ai"}
    elif ending in ("er", "ir"):
        soft = stem[:-1] + "j" if stem.endswith("g") else stem[:-1] + "ç" if stem.endswith("c") else stem[:-2] + "g" if stem.endswith("gu") else stem
        forms |= {stem + "e", soft + "a", soft + "am", stem + "em", stem + ("endo" if ending == "er" else "indo"),
                  stem + "ido", stem + "ida", stem + "idos", stem + "idas", soft + "amos", stem + ("emos" if ending == "er" else "imos")}
    return forms


def read_source(path: str | None) -> list[tuple[str, int]]:
    raw = Path(path).read_text(encoding="utf-8") if path else urllib.request.urlopen(URL, timeout=60).read().decode("utf-8")
    rows = []
    for line in raw.splitlines():
        parts = line.split()
        if len(parts) == 2 and parts[1].isdigit():
            rows.append((parts[0].lower(), int(parts[1])))
    return rows[:TOP]


def main() -> None:
    rows = [(w, f) for w, f in read_source(sys.argv[1] if len(sys.argv) > 1 else None) if w.isalpha() and len(w) <= 25]
    freq: dict[str, int] = {}
    for word, count in rows:
        freq[word] = freq.get(word, 0) + count

    by_key: dict[str, list[str]] = {}
    for word in freq:
        by_key.setdefault(unaccent(word), []).append(word)
    for words in by_key.values():
        if len(words) < 2:
            continue
        top = max(freq[w] for w in words)
        for word in words:
            if word != unaccent(word) and freq[word] < top * MIN_VARIANT_SHARE:
                del freq[word]

    for line in EXCLUDE.read_text(encoding="utf-8").splitlines():
        freq.pop(line.strip().lower(), None)

    for line in VERBS.read_text(encoding="utf-8").splitlines():
        verb = line.strip().lower()
        if verb and not verb.startswith("#"):
            for form in conjugate(verb):
                freq[form] = max(freq.get(form, 0), VERB_FREQ)

    added = 0
    for line in EXTRA.read_text(encoding="utf-8").splitlines():
        word = line.strip().lower()
        if word and not word.startswith("#") and word not in freq:
            freq[word] = EXTRA_FREQ
            added += 1

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(OUT, "wt", encoding="utf-8", compresslevel=9, newline="\n") as handle:
        for word, count in sorted(freq.items(), key=lambda item: (-item[1], item[0])):
            handle.write(f"{word}\t{count}\n")
    print(f"{len(freq)} palavras ({added} do suplemento) -> {OUT} ({OUT.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()

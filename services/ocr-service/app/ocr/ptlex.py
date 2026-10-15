"""Léxico português compacto e reparação do texto lido pelo OCR.

O motor lê bem as letras mas tropeça no português: perde acentos (``organizacao``), troca letras parecidas
(``cmpresa`` por ``empresa``, ``quc`` por ``que``), cola palavras (``PROVADEEXAMEDEOGI``, ``segurancaéde``) e
cola números a palavras (``4mil``, ``Duracao9o``). Este módulo repara isso com um léxico pequeno (ver
``data/README.md``) e três regras prudentes:

* uma palavra que já está no léxico **não se altera** (salvo uma forma sem acentos muito mais rara do que a
  acentuada, como ``sao`` → ``são``);
* só se corrige o que tem uma única correção clara: restaurar acentos, ou trocar/inserir/apagar UMA letra;
* nunca se mexe em números, siglas curtas em maiúsculas, códigos, endereços nem em palavras com dígitos.

Nada aqui conhece uma prova em concreto: são regras gerais do português.
"""

from __future__ import annotations

import gzip
import math
import re
import unicodedata
from functools import lru_cache
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import structlog

logger = structlog.get_logger(__name__)

DATA_PATH = Path(__file__).parent / "data" / "pt_words.tsv.gz"

LETTERS = "A-Za-zÀ-ÖØ-öø-ÿ"
_ALPHABET = "abcdefghijklmnopqrstuvwxyz"

# Uma forma sem acentos só é "corrigida" se a acentuada for pelo menos tantas vezes mais frequente.
_RESTORE_RATIO = 20
# Entre duas correções de uma letra, só se escolhe a melhor se for claramente mais frequente.
_EDIT_RATIO = 3
# Palavras-função que podem aparecer coladas a outras ("todasas", "segurancaéde"). Só de duas letras ou mais:
# "a", "e" e "o" soltos dentro de uma palavra comprida são quase sempre um falso positivo.
_FUNCTION_WORDS = frozenset(
    "as os um de da do em no na se ao eu ou já há às nos nas dos das num numa por com que"
    " mais sem sob ate até".split()
)
# Numa palavra em minúsculas só se separa em palavras MUITO comuns: "con"+"cavidade" não conta.
_COMMON_PART_FREQ = 30_000
_PREPOSITIONS = frozenset({"de", "da", "do", "dos", "das", "em", "na", "no", "para", "por"})
# Fragmentos que não são palavras soltas: separar "mensalmente" em "mensal mente" seria um erro.
_BOUND_MORPHEMES = frozenset(
    "mente mento mentos dade dades ção ções cao coes ismo ista eiro eira oso osa ável ivel vel".split()
)
# Moeda/unidade colada à conjunção seguinte ("4 mil kze a demanda" -> "4 mil kz e a demanda").
_UNIT_PREFIXES = ("kz", "aoa", "usd", "eur", "kg", "km")
_UNIT_TAILS = frozenset({"e", "a", "o", "de", "ou"})


def unaccent(text: str) -> str:
    """Tira os acentos (e a cedilha): ``organização`` → ``organizacao``."""
    return "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn")


def apply_case(original: str, new: str) -> str:
    """Dá a ``new`` a capitalização de ``original`` (TUDO MAIÚSCULAS, Capitalizada ou minúscula)."""
    if len(original) > 1 and original.isupper():
        return new.upper()
    if original[:1].isupper():
        return new[:1].upper() + new[1:]
    return new


class Lexicon:
    """Frequências de palavras portuguesas e os índices para as consultar sem acentos."""

    def __init__(self, freq: Dict[str, int]):
        self.freq = freq
        self.total = float(sum(freq.values())) or 1.0
        self._best: Dict[str, str] = {}
        self._build_accent_index()

    def _build_accent_index(self) -> None:
        groups: Dict[str, List[str]] = {}
        for word in self.freq:
            groups.setdefault(unaccent(word), []).append(word)
        for key, words in groups.items():
            # O português de Angola segue a norma europeia: "económico", não "econômico".
            europeans = {w.replace("ê", "é").replace("ô", "ó").replace("â", "á") for w in words}
            candidates = [w for w in words if not (re.search("[êôâ]", w) and w.replace("ê", "é").replace("ô", "ó").replace("â", "á") in europeans and w not in europeans)]
            self._best[key] = max(candidates or words, key=lambda w: self.freq[w])

    def __contains__(self, word: str) -> bool:
        return word.lower() in self.freq

    def knows(self, word: str) -> bool:
        """A palavra existe, com ou sem acentos."""
        lower = word.lower()
        return lower in self.freq or unaccent(lower) in self._best

    def restore(self, lower: str) -> Optional[str]:
        """A forma acentuada de ``lower`` (``organizacao`` → ``organização``), ou None se não houver."""
        key = unaccent(lower)
        best = self._best.get(key)
        if best is None:
            return None
        if lower in self.freq:
            # Já é uma palavra: só se troca por uma forma acentuada muito mais frequente (sao → são).
            if lower != key or best == lower or self.freq[best] < self.freq[lower] * _RESTORE_RATIO:
                return None
            return best
        return best

    def correct(self, lower: str, allow_short: bool = False) -> Optional[str]:
        """A palavra a uma letra de distância, se for claramente a mais provável; senão None."""
        key = unaccent(lower)
        if len(key) < 3 or (len(key) == 3 and not allow_short):
            return None
        found: Dict[str, int] = {}
        for candidate in _edits1(key):
            best = self._best.get(candidate)
            if best is not None and candidate != key:
                found[best] = self.freq[best]
        if not found:
            return None
        ranked = sorted(found.items(), key=lambda item: -item[1])
        if len(ranked) > 1 and ranked[0][1] < ranked[1][1] * _EDIT_RATIO:
            return None
        if len(key) == 3 and ranked[0][1] < 1_000_000:
            return None  # três letras: só a correção para uma palavra muito comum ("quc" → "que")
        return ranked[0][0]

    def _word_cost(self, key: str, unknown_acronym: bool) -> Optional[float]:
        best = self._best.get(key)
        if best is not None:
            if len(key) == 1:
                return None
            if len(key) == 2 and key not in _FUNCTION_WORDS and self.freq[best] < 100_000:
                return None
            if key in _BOUND_MORPHEMES:
                return None
            return -math.log(self.freq[best] / self.total) + 3.0
        if unknown_acronym and 2 <= len(key) <= 4:
            return 26.0 + 2 * len(key)
        return None

    def segment(self, key: str, unknown_acronym: bool = False) -> Optional[List[str]]:
        """Separa ``key`` (sem acentos, minúsculas) em palavras do léxico, ou None se não houver separação limpa."""
        n = len(key)
        if n < 4:
            return None
        inf = float("inf")
        cost = [inf] * (n + 1)
        back = [0] * (n + 1)
        cost[0] = 0.0
        for i in range(n):
            if cost[i] == inf:
                continue
            for j in range(i + 1, min(n, i + 20) + 1):
                word_cost = self._word_cost(key[i:j], unknown_acronym)
                if word_cost is not None and cost[i] + word_cost < cost[j]:
                    cost[j] = cost[i] + word_cost
                    back[j] = i
        if cost[n] == inf:
            return None
        parts: List[str] = []
        end = n
        while end > 0:
            parts.append(key[back[end]:end])
            end = back[end]
        parts.reverse()
        if len(parts) < 2 or len(parts) > 6:
            return None
        # No máximo uma sigla desconhecida e nunca só siglas.
        unknown = [i for i, p in enumerate(parts) if p not in self._best]
        if len(unknown) > 1 or len(unknown) == len(parts):
            return None
        # Uma sigla desconhecida (OGI) só aparece depois de uma preposição: "PROVA DE EXAME DE OGI".
        if unknown and (unknown[0] == 0 or parts[unknown[0] - 1] not in _PREPOSITIONS):
            return None
        # Uma colagem tem uma palavra-função no meio ou no fim (todas|as, departamento|de|educação). Sem ela é
        # uma palavra comprida que o léxico não traz (concavidade, verticalmente, hipotenusa): fica como está.
        if not any(p in _FUNCTION_WORDS for p in parts):
            return None
        if not unknown_acronym:
            # Em minúsculas (texto corrido) é mais exigente: uma palavra-função a abrir é uma palavra normal
            # ("de"+"termine"), e as outras partes têm de ser palavras muito comuns ("sub"+"linha"+"da" não).
            if parts[0] in _FUNCTION_WORDS:
                return None
            for part in parts:
                if part not in _FUNCTION_WORDS and self.freq.get(self._best[part], 0) < _COMMON_PART_FREQ:
                    return None
        return parts


# Letras que o motor confunde: o que ele leu -> o que estava impresso.
_CONFUSABLE = {
    "c": "eo",
    "e": "c",
    "o": "c",
    "l": "it",
    "i": "l",
    "t": "l",
    "r": "n",
    "n": "r",
    "u": "v",
    "v": "u",
}
# O traço a mais que o motor inventa dentro de uma palavra (urma -> uma, fundarmental -> fundamental). Só o "r":
# apagar "l", "n" ou "t" transformava verbos válidos noutras palavras ("complete" -> "compete").
_SPURIOUS = "r"


def _edits1(word: str):
    """As palavras a uma edição *típica de OCR* de ``word``: uma troca de letras parecidas ou um traço a mais.

    Não são todas as edições possíveis de propósito. Apagar um "u" de "possua" dá "possa", uma palavra
    melhor classificada mas errada: um verbo válido que o léxico não traz não é um erro de leitura.
    """
    for i, char in enumerate(word):
        for printed in _CONFUSABLE.get(char, ""):
            yield word[:i] + printed + word[i + 1:]
        if char in _SPURIOUS:
            yield word[:i] + word[i + 1:]


@lru_cache(maxsize=1)
def load_lexicon() -> Optional[Lexicon]:
    """O léxico do repositório, ou None se o ficheiro faltar (o OCR segue sem reparação)."""
    try:
        freq: Dict[str, int] = {}
        with gzip.open(DATA_PATH, "rt", encoding="utf-8") as handle:
            for line in handle:
                word, _, count = line.rstrip("\n").partition("\t")
                if word and count.isdigit():
                    freq[word] = int(count)
        return Lexicon(freq)
    except OSError as error:
        logger.warning("Portuguese lexicon unavailable; OCR text will not be repaired", error=str(error))
        return None


# --- reparação de linhas ---------------------------------------------------------------------------------------

_STRAY_QUOTE = re.compile(r"(?<=\s)[‘’'`´](?=\s)|^[‘’'`´](?=\s)")
_SENTENCE_GLUE = re.compile(rf"(?<=[A-Za-zà-ÿ]{{3}}[.!?;])(?=[A-ZÀ-Ý][a-zà-ÿ])")
_COLON_GLUE = re.compile(rf"(?<=[A-Za-zà-ÿ]{{3}}:)(?=[A-Za-zÀ-ÿ0-9])")
_DOT_GLUE = re.compile(rf"\b([a-zà-ÿ]{{4,}})\.(?!(?:gov|com|org|net|edu|info|ao|pt|br|co)\b)([a-zà-ÿ]{{3,}})\b")
_DIGITS_THEN_LETTERS = re.compile(rf"(\d)([{LETTERS}]{{3,}})")
_LETTERS_THEN_DIGITS = re.compile(rf"([{LETTERS}]{{4,}})(\d{{2,}})")
_CAMEL = re.compile(r"(?<=[a-zà-ÿ])(?=[A-ZÀ-Ý])")
_TOKEN = re.compile(rf"^([^{LETTERS}0-9]*)([{LETTERS}][{LETTERS}'\-]*)([^{LETTERS}0-9]*)$")
_ASSINALAR = re.compile(r"\((assinalar\s+com\s+[VvFf])\b(?!\s*\))")
_ORDINAL_CLASS = re.compile(r"\b(\d{1,2})\s*[aáàoº°]\s*(classe)\b", re.IGNORECASE)
_DURATION = re.compile(rf"(dura[çc][ãa]o)\s*[:.]?\s*([0-9oOlI]{{1,3}})\s*(min(?:utos?)?\b\.?)", re.IGNORECASE)


def repair_text(text: str, lexicon: Optional[Lexicon] = None, line_start: bool = True) -> str:
    """Repara o texto de uma linha de OCR com o léxico dado (por omissão o do repositório).

    ``line_start`` diz se ``text`` é uma linha inteira: nesse caso "aDeve" no princípio é uma alínea colada ao
    texto e fica intacta para quem interpreta a estrutura. Se o léxico faltar só se fazem as limpezas simples.
    """
    lexicon = lexicon if lexicon is not None else load_lexicon()
    text = unicodedata.normalize("NFC", text)
    text = _STRAY_QUOTE.sub("", text)
    text = _ASSINALAR.sub(lambda m: f"({m.group(1)})", text)
    text = _SENTENCE_GLUE.sub(" ", text)
    text = _COLON_GLUE.sub(" ", text)
    if lexicon is not None:
        text = _DOT_GLUE.sub(lambda m: f"{m.group(1)}. {m.group(2).capitalize()}" if lexicon.knows(m.group(1)) else m.group(0), text)
    text = re.sub(r"\s+", " ", text).strip()
    if not text:
        return text

    tokens = text.split(" ")
    if lexicon is not None:
        tokens = _split_glued_tokens(tokens, lexicon, line_start)
        glued_label = line_start and bool(tokens) and re.match(r"^[a-z][A-ZÀ-Ý]", tokens[0]) is not None
        tokens = [
            tok if (i == 0 and glued_label)
            else _repair_token(tok, lexicon, sentence_start=(i == 0 or _ends_sentence(tokens[i - 1])))
            for i, tok in enumerate(tokens)
        ]
        tokens = [t for tok in tokens for t in tok.split(" ") if t]
    tokens = _fix_lone_letters(tokens)
    text = " ".join(tokens)

    text = _ORDINAL_CLASS.sub(lambda m: f"{m.group(1)}ª {m.group(2).capitalize() if m.group(2)[0].isupper() else m.group(2)}", text)
    text = _DURATION.sub(_duration_repl, text)
    return re.sub(r"\s+", " ", text).strip()


def _duration_repl(match: re.Match) -> str:
    digits = match.group(2).translate(str.maketrans("oOlI", "0011"))
    if not any(c.isdigit() for c in digits):
        return match.group(0)
    return f"Duração: {digits} {match.group(3)}"


def _ends_sentence(token: str) -> bool:
    return bool(re.search(r"[.!?:]\W*$", token))


def _split_glued_tokens(tokens: List[str], lexicon: Lexicon, line_start: bool) -> List[str]:
    out: List[str] = []
    for index, token in enumerate(tokens):
        # "aDeve" no início da linha é uma alínea colada ao texto: quem interpreta a estrutura trata disso.
        if index == 0 and line_start and re.match(r"^[a-z][A-ZÀ-Ý]", token):
            out.append(token)
            continue
        out.extend(_split_token(token, lexicon).split(" "))
    return out


def _split_token(token: str, lexicon: Lexicon) -> str:
    core_match = re.match(rf"^([^{LETTERS}0-9]*)(.*?)([^{LETTERS}0-9]*)$", token)
    pre, core, post = core_match.groups() if core_match else ("", token, "")
    if not core or "@" in core or "://" in core:
        return token
    parts = _split_core(core, lexicon)
    return pre + " ".join(parts) + post if len(parts) > 1 else token


def _split_core(core: str, lexicon: Lexicon) -> List[str]:
    # 2024/2025Duracao -> 2024/2025 Duracao ; Lectivo2024 -> Lectivo 2024 ; 4mil -> 4 mil
    def after_digits(m: re.Match) -> str:
        return f"{m.group(1)} {m.group(2)}" if lexicon.knows(m.group(2)) and len(m.group(2)) > 2 else m.group(0)

    core = _DIGITS_THEN_LETTERS.sub(after_digits, core)
    core = _LETTERS_THEN_DIGITS.sub(r"\1 \2", core)
    result: List[str] = []
    for piece in core.split(" "):
        # "pecaé" -> "peça é", "segurancaéde" -> "segurança é de": um é/à no meio de uma palavra que o léxico
        # não conhece, com o que vem antes (e depois) a ser palavras, é uma palavra colada.
        glued = re.fullmatch(rf"([{LETTERS}]{{3,}})([éà])([{LETTERS}]{{2,}})?", piece)
        if glued and glued.group(1).lower() not in lexicon.freq and lexicon.knows(glued.group(1)) \
                and (glued.group(3) is None or lexicon.knows(glued.group(3))):
            result.extend(p for p in glued.groups() if p)
            continue
        result.extend(_split_camel(piece, lexicon))
    return result


def _split_camel(piece: str, lexicon: Lexicon) -> List[str]:
    if not _CAMEL.search(piece):
        return [piece]
    chunks = _CAMEL.split(piece)
    if len(chunks) < 2:
        return [piece]

    def acceptable(chunk: str) -> bool:
        return (
            bool(re.fullmatch(r"\d{1,2}[aáàoº°]?", chunk))
            or (len(chunk) == 1 and chunk.isupper())
            or (len(chunk) > 1 and lexicon.knows(chunk))
        )

    return chunks if all(acceptable(c) for c in chunks) else [piece]


def _repair_token(token: str, lexicon: Lexicon, sentence_start: bool) -> str:
    match = _TOKEN.match(token)
    if match is None:
        return token
    pre, core, post = match.groups()
    lower_core = core.lower()
    # Moeda/unidade colada à conjunção seguinte: "kze" -> "kz e".
    for prefix in _UNIT_PREFIXES:
        if lower_core.startswith(prefix) and lower_core[len(prefix):] in _UNIT_TAILS:
            return f"{pre}{core[:len(prefix)]} {core[len(prefix):]}{post}"
    words = core.split("-")
    fixed = [_repair_word(word, lexicon, sentence_start and i == 0) for i, word in enumerate(words)]
    return f"{pre}{'-'.join(fixed)}{post}"


def _repair_word(word: str, lexicon: Lexicon, sentence_start: bool) -> str:
    if not word or "'" in word or (len(word) < 3 and word.lower() != "cm"):
        return word
    lower = word.lower()
    upper_short = word.isupper() and len(word) <= 4
    if upper_short and lower not in lexicon._best and unaccent(lower) not in lexicon._best:
        return word  # sigla (OGI, IVA): não se mexe
    restored = lexicon.restore(lower)
    if restored is not None and restored != lower:
        return apply_case(word, restored)
    if lower in lexicon.freq or upper_short:
        return word
    # Nomes próprios (Luanda) e TUDO EM MAIÚSCULAS não levam correção de uma letra: "LUANDA" não é "RUANDA".
    proper_like = word.isupper() or (word[0].isupper() and not sentence_start)
    if not proper_like:
        fixed = lexicon.correct(lower, allow_short=(len(lower) == 3))
        if fixed is not None:
            return apply_case(word, fixed)
    key = unaccent(lower)
    if len(key) >= 5:
        parts = lexicon.segment(key, unknown_acronym=word.isupper())
        if parts is not None:
            return " ".join(_case_part(word, original_slice(word, parts, i), parts[i], lexicon, i) for i in range(len(parts)))
    # Nomes terminados em -ção/-ções que o léxico não traz: "laboracao" -> "laboração".
    if key.endswith("coes") and len(key) > 6:
        return apply_case(word, lower[:-4] + "ções")
    if key.endswith("cao") and len(key) > 5:
        return apply_case(word, lower[:-3] + "ção")
    return word


def original_slice(word: str, parts: List[str], index: int) -> str:
    """O pedaço de ``word`` que corresponde a ``parts[index]`` (as palavras têm o mesmo comprimento sem acentos)."""
    start = sum(len(p) for p in parts[:index])
    return word[start:start + len(parts[index])]


def _case_part(word: str, original: str, key: str, lexicon: Lexicon, index: int) -> str:
    lower = original.lower()
    # Se o próprio OCR já trouxe uma forma válida (ex.: "é"), mantém-na.
    chosen = lower if lower in lexicon.freq else (lexicon._best.get(key) or lower)
    if word.isupper():
        return chosen.upper()
    if index == 0 and word[:1].isupper():
        return chosen[:1].upper() + chosen[1:]
    return chosen


def _fix_lone_letters(tokens: List[str]) -> List[str]:
    """``c`` solto entre palavras é um "e" mal lido; ``cm`` sem número antes é "em"."""
    fixed = list(tokens)
    for i, token in enumerate(tokens):
        prev = tokens[i - 1] if i > 0 else ""
        nxt = tokens[i + 1] if i + 1 < len(tokens) else ""
        between_words = (
            re.fullmatch(rf"[{LETTERS}]{{2,}}[,;]?", prev or "") is not None
            and re.match(rf"[a-zà-ÿ]{{2,}}", nxt or "") is not None
        )
        if token == "c" and between_words:
            fixed[i] = "e"
        elif token == "cm" and nxt[:1].islower() and not re.search(r"\d", prev):
            fixed[i] = "em"
    return fixed

"""
Reconhecimento de fórmulas matemáticas e físicas em texto já lido pelo OCR (#262).

O motor de texto lê as letras mas não sabe matemática: devolve "8π" tal como está, mas
devolve "B = Bmaxe-2t" sem perceber que "max" é um índice e "-2t" um expoente, e uma fórmula
impressa em bloco (detectada pelo modelo de layout) sai tão corrompida que o código que
monta as questões a substitui por um marcador "[fórmula]" e, no máximo, recorta-a como
imagem - o aluno ou o professor vê um retângulo em vez da fórmula.

Este módulo não vê a imagem - só o texto que o OCR já leu - e tenta reconstruir a notação
a partir de padrões conhecidos de provas angolanas de Física e Matemática: coeficiente e
letra grega colados ("8π"), notação científica com "×" ("5 × 10-³"), expoente colado à
base em unicode ou em ASCII ("x²", "e-2t"), índice físico colado à variável ("Bmax") e
vector marcado com o acento combinável de seta (quando o OCR o preserva).

Como qualquer heurística sobre texto já corrompido, isto falha de forma previsível: um
trecho que pareça matemática mas não bata com nenhum padrão conhecido (ou que misture um
símbolo que o próprio OCR não soube ler, como o "†" que normalmente está no lugar de uma
seta de vector) fica "unrecognized", com ``latex=None`` - nunca se inventa uma fórmula a
partir de texto que não se percebe. Quem chama decide o que fazer com isso: mostrar o
texto em bruto, ou manter a imagem recortada da fórmula (``ocr_formula_images``) como
único resultado auditável.

Não há nenhum fornecedor externo aqui dentro: tudo corre localmente, sobre texto que o
OCR já extraiu. Reconhecer uma fórmula a partir da própria imagem recortada (mais fiel,
mas mais caro) pediria um fornecedor à parte, explicitamente configurado - sem essa
configuração, nenhuma imagem sai deste serviço para reconhecer uma fórmula.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Callable, Dict, List, Optional, Tuple

# kind que um FormulaSegment pode ter: "formula" quando há LaTeX de confiança
# suficiente, "unrecognized" quando o trecho parece matemática mas nenhum padrão bateu.
FORMULA = "formula"
UNRECOGNIZED = "unrecognized"


@dataclass
class FormulaSegment:
    """Um trecho de texto que o reconhecedor tratou como fórmula (ou tentou tratar).

    ``raw`` é sempre o texto que o OCR leu, nunca alterado - fica como o resultado
    auditável quando ``latex`` falha. ``latex`` é ``None`` em ``unrecognized``: nunca se
    inventa uma fórmula a partir de texto que não se consegue reconstruir.
    """
    raw: str
    latex: Optional[str]
    confidence: float
    kind: str  # FORMULA | UNRECOGNIZED
    ocr_confidence: Optional[float] = None
    layout_confidence: Optional[float] = None

    def to_dict(self) -> Dict[str, object]:
        result: Dict[str, object] = {
            "raw": self.raw,
            "kind": self.kind,
            "confidence": round(self.confidence, 3),
        }
        if self.ocr_confidence is not None:
            result["ocrConfidence"] = round(self.ocr_confidence, 3)
        if self.layout_confidence is not None:
            result["layoutConfidence"] = round(self.layout_confidence, 3)
        if self.latex is not None:
            result["latex"] = self.latex
        return result


# Letras gregas que aparecem em provas de Física e Matemática, pelo glifo unicode (o
# motor lê o glifo certo na maioria dos casos - isto não cobre o nome por extenso).
_GREEK_LATEX: Dict[str, str] = {
    "π": r"\pi", "Π": r"\Pi",
    "α": r"\alpha",
    "β": r"\beta",
    "θ": r"\theta", "Θ": r"\Theta",
    "Δ": r"\Delta", "δ": r"\delta",
    "Ω": r"\Omega", "ω": r"\omega",
    "μ": r"\mu",
    "λ": r"\lambda",
    "φ": r"\varphi", "Φ": r"\Phi",
    "Σ": r"\Sigma", "σ": r"\sigma",
}

_OP_LATEX: Dict[str, str] = {
    "×": r"\times",
    "÷": r"\div",
    "±": r"\pm",
    "√": r"\sqrt",
    "≈": r"\approx",
    "≤": r"\leq",
    "≥": r"\geq",
    "≠": r"\neq",
    "∞": r"\infty",
}

_SUPER_TO_ASCII = str.maketrans("⁰¹²³⁴⁵⁶⁷⁸⁹⁻⁺", "0123456789-+")

# Índices físicos que o OCR cola à variável ("Bmax", "Vmin"): só os de uma letra
# maiúscula seguida por um destes, para não ler "Clímax" como "Cl_{imax}".
_SUBSCRIPT_WORDS = ("max", "min")

_GREEK_CHARS = "".join(_GREEK_LATEX)
_OP_CHARS = "".join(_OP_LATEX)


def _from_superscript(digits: str) -> str:
    """Unicode ou ASCII, devolve sempre dígitos ASCII (com sinal, se houver)."""
    return digits.translate(_SUPER_TO_ASCII)


def _sci_to_latex(raw: str) -> str:
    body = raw.replace(" ", "")
    mant, _, rest = body.partition("×")
    rest = rest.replace("10", "", 1)
    sign = "-" if rest.startswith("-") else ""
    exp = _from_superscript(rest[1:] if sign else rest)
    return rf"{mant.replace(',', '.')} \times 10^{{{sign}{exp}}}"


def _parexp_to_latex(raw: str) -> str:
    # separa o expoente (unicode, no fim) do "(...)" que o precede
    i = len(raw) - 1
    while i >= 0 and raw[i] in "⁻⁺⁰¹²³⁴⁵⁶⁷⁸⁹":
        i -= 1
    inner, exp = raw[: i + 1], raw[i + 1:]
    return f"{inner}^{{{_from_superscript(exp)}}}"


# Passos aplicados nesta ordem, cada um só sobre o texto que o passo anterior ainda não
# reconheceu. A ordem importa de duas formas: um padrão mais específico vem antes de um
# mais genérico (ex. "8π" inteiro antes do "π" solto), e "sub" vem antes dos expoentes
# porque o LaTeX que insere ("B_{max}") quebra a letra colada ao resto ("Bmaxe-2t"),
# abrindo uma fronteira de palavra onde antes não havia nenhuma - sem isto, o "e-2t" de
# "Bmaxe-2t" nunca teria um `\b` para o padrão de expoente ASCII reconhecer.
_PASSES: List[Tuple[str, re.Pattern, Callable[[str], str], float]] = [
    ("sci", re.compile(r"\d+(?:[.,]\d+)?\s*×\s*10\s*-?\s*(?:[⁰¹²³⁴⁵⁶⁷⁸⁹]+|\d+)"), _sci_to_latex, 0.92),
    ("vec", re.compile(r"[A-Za-z]⃗"), lambda t: rf"\vec{{{t[0]}}}", 0.9),
    ("coefgreek", re.compile(r"\d+(?:[.,]\d+)?\s*[" + _GREEK_CHARS + r"]"),
     lambda t: f"{t[:-1].strip().replace(',', '.')}{_GREEK_LATEX[t[-1]]}", 0.88),
    ("sub", re.compile(r"\b[A-Z](?:" + "|".join(_SUBSCRIPT_WORDS) + r")"),
     lambda t: f"{t[0]}_{{{t[1:]}}}", 0.85),
    ("parexp", re.compile(r"\([^()\n]{1,30}\)[⁻⁺]?[⁰¹²³⁴⁵⁶⁷⁸⁹]+"), _parexp_to_latex, 0.88),
    ("uniexp", re.compile(r"[A-Za-z0-9][⁻⁺]?[⁰¹²³⁴⁵⁶⁷⁸⁹]+"),
     lambda t: f"{t[0]}^{{{_from_superscript(t[1:])}}}", 0.75),
    ("asciiexp", re.compile(r"\b[a-zA-Z]-\d+[a-zA-Z]?\b"), lambda t: f"{t[0]}^{{-{t[2:]}}}", 0.75),
    ("greek", re.compile(r"[" + _GREEK_CHARS + r"]"), lambda t: _GREEK_LATEX[t], 0.8),
    ("op", re.compile(r"[" + _OP_CHARS + r"]"), lambda t: _OP_LATEX[t], 0.7),
]


def _scan(text: str) -> Tuple[str, List[FormulaSegment]]:
    """Substitui, em `text`, cada trecho que bate com um padrão conhecido pela sua forma
    LaTeX entre `$...$`, e devolve o texto novo junto com os segmentos reconhecidos.

    Corre em passos (`_PASSES`), não numa só expressão combinada: o LaTeX que um passo
    insere pode criar a fronteira de que o passo seguinte precisa (ver o comentário em
    `_PASSES`). Nunca marca nada como "unrecognized" aqui: um trecho que não bate com
    nenhum padrão fica como estava, sem reivindicar que é (ou não é) uma fórmula - essa
    decisão só faz sentido quando já se sabe, por outra via (o modelo de layout), que um
    trecho inteiro é mesmo uma fórmula impressa; ver `recognize_region`.
    """
    chunks: List[Tuple[str, bool]] = [(text, False)]
    segments: List[FormulaSegment] = []
    for kind, pattern, to_latex, confidence in _PASSES:
        next_chunks: List[Tuple[str, bool]] = []
        for chunk, is_formula in chunks:
            if is_formula or not chunk:
                next_chunks.append((chunk, is_formula))
                continue
            last = 0
            for m in pattern.finditer(chunk):
                if m.start() > last:
                    next_chunks.append((chunk[last:m.start()], False))
                raw = m.group(0)
                latex = to_latex(raw)
                next_chunks.append((f"${latex}$", True))
                segments.append(FormulaSegment(raw=raw, latex=latex, confidence=confidence, kind=FORMULA))
                last = m.end()
            if last < len(chunk) or last == 0:
                next_chunks.append((chunk[last:], False))
        chunks = next_chunks
    return "".join(c for c, _ in chunks), segments


def extract_inline_formulas(text: str) -> Tuple[str, List[FormulaSegment]]:
    """Reconhece, dentro de uma frase normal, os trechos que são claramente uma fórmula
    (ex. "Uma espira circular de 8π cm de perímetro" -> "...de $8\\pi$ cm de perímetro"),
    deixando o resto da frase como está. Só devolve segmentos "formula": uma frase comum
    não vira "unrecognized" por não ter matemática nenhuma dentro."""
    return _scan(text)


# Depois de aplicados os padrões conhecidos, o que sobrar fora do que foi reconhecido tem
# de ser ASCII simples (letras, dígitos, espaço e pontuação de fórmula) para a região
# contar como reconhecida. Um caractere fora disto - como o "†" que costuma estar no
# lugar de uma seta de vector que o OCR não leu - é o sinal de que ainda há qualquer coisa
# por perceber, e por isso a região fica "unrecognized" em vez de uma fórmula a meio.
_PLAIN_OK = re.compile(r"^[\x00-\x7F]*$")


def recognize_region(raw_text: str) -> FormulaSegment:
    """Reconhece o texto completo de uma região que o modelo de layout já identificou
    como uma fórmula impressa (`_mask_formulas`, em `postprocessing.py`).

    Ao contrário de `extract_inline_formulas`, aqui já se sabe, por outra via, que o
    trecho inteiro é uma fórmula - por isso, quando nenhum padrão bate ou sobra algo que
    os padrões conhecidos não explicam, o resultado é "unrecognized" (não apenas texto
    comum): há maths ali, só não se conseguiu lê-la com confiança. A imagem recortada da
    região (`ocr_formula_images`) continua a ser o resultado auditável nesse caso.
    """
    text = raw_text.strip()
    if not text:
        return FormulaSegment(raw=raw_text, latex=None, confidence=0.0, kind=UNRECOGNIZED)

    # Um "$" já no texto lido não é nosso: é sinal de que a imagem já vinha corrompida com
    # alguma notação que o motor de texto não devia ter produzido sozinho (viu-se em provas
    # reais um recorte de fórmula lido como lixo cheio de "$" e "{}" sem nexo). Não se tenta
    # dar sentido a isso - mais vale a imagem recortada (resultado auditável) do que uma
    # fórmula inventada a partir de ruído que já trazia a própria sintaxe do LaTeX.
    if "$" in text:
        return FormulaSegment(raw=raw_text, latex=None, confidence=0.1, kind=UNRECOGNIZED)

    converted, segments = _scan(text)
    if not segments:
        return FormulaSegment(raw=raw_text, latex=None, confidence=0.15, kind=UNRECOGNIZED)

    # O que não foi reconhecido é o que sobra depois de se tirarem os "$...$" já postos.
    leftover = re.sub(r"\$[^$]*\$", "", converted)
    if not _PLAIN_OK.match(leftover):
        return FormulaSegment(raw=raw_text, latex=None, confidence=0.25, kind=UNRECOGNIZED)

    # Uma fórmula impressa não devia ter muito texto por explicar à volta do que se
    # reconheceu: se o que se reconheceu é uma fração pequena do texto todo, o mais
    # provável é que isto seja uma frase normal (ou uma mistura de frase com fórmula)
    # dentro da região, não uma fórmula que se conseguiu ler na maior parte.
    recognized_chars = sum(len(s.raw) for s in segments)
    total_chars = len(re.sub(r"\s+", "", text))
    if total_chars == 0 or recognized_chars / total_chars < 0.55:
        return FormulaSegment(raw=raw_text, latex=None, confidence=0.3, kind=UNRECOGNIZED)

    latex = converted.replace("$", "")
    confidence = min(0.95, sum(s.confidence for s in segments) / len(segments) + 0.05 * (len(segments) - 1))
    return FormulaSegment(raw=raw_text, latex=latex, confidence=confidence, kind=FORMULA)

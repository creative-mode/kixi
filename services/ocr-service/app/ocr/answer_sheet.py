"""Reads the answers a student wrote on a sheet, guided by the structure of the exam.

The text engine gives lines with a box, a confidence and whether they are in pen. Everything here is a pure function
over those lines, so it is tested without the engine. Nothing here knows about grades or business rules (ADR-0008):
it only says what was read, and how sure it is.

Convention the sheet must follow: the header carries the student ("Nº 3", "Nome: Ana Domingos") and each answer starts
with its question number ("1) B", "2. A", "3 - texto da resposta"). A page with a header starts a new student's
sheet; a page without one continues the previous sheet.
"""

import re
from typing import TYPE_CHECKING, Any, Dict, List, Optional, Sequence, Tuple

if TYPE_CHECKING:  # only for annotations: importing the engine package here would load OpenCV and Paddle
    from app.ocr.postprocessing import TextBlock

LOW_CONFIDENCE = 0.9
HEADER_LINES = 12

_NUMBER = re.compile(r"\bn(?:[ºo°.]{1,2}|\s)?\s*[:.\-]?\s*(\d{1,4})\b", re.IGNORECASE)
_NUMBER_WORD = re.compile(r"\bn[uú]mero\s*[:.\-]?\s*(\d{1,4})\b", re.IGNORECASE)
_NAME = re.compile(r"\bnome\s*[:.\-]?\s*(\S.*)$", re.IGNORECASE)
_ANCHOR = re.compile(r"^\s*(?:quest[aã]o\s*)?(\d{1,3})\s*[.)\-:]\s*(.*)$", re.IGNORECASE)
_LETTER = re.compile(r"^\(?\s*([A-Ea-e])\s*\)?(?=$|[\s.):,;-])")
_TRUE_FALSE = {"v": "V", "verdadeiro": "V", "verdadeira": "V", "t": "V", "true": "V",
               "f": "F", "falso": "F", "falsa": "F", "false": "F"}


def _value(value: Any, confidence: float) -> Dict[str, Any]:
    return {"value": value, "confidence": round(max(0.0, min(1.0, confidence)), 3)}


def _ordered(blocks: Sequence['TextBlock']) -> List['TextBlock']:
    return sorted(blocks, key=lambda b: (b.bbox[1], b.bbox[0]))


def read_header(blocks: Sequence['TextBlock']) -> Dict[str, Optional[Dict[str, Any]]]:
    """The student's number and name from the first lines of a page; either may be missing."""
    number: Optional[Dict[str, Any]] = None
    name: Optional[Dict[str, Any]] = None
    for block in _ordered(blocks)[:HEADER_LINES]:
        text = block.text.strip()
        if name is None:
            match = _NAME.search(text)
            if match and len(match.group(1).strip()) >= 2:
                name = _value(match.group(1).strip(), block.confidence)
        if number is None:
            match = _NUMBER_WORD.search(text) or _NUMBER.search(text)
            if match:
                number = _value(int(match.group(1)), block.confidence)
    return {"number": number, "name": name}


def _letter_from(text: str, options: Sequence[str], question_type: str) -> Optional[str]:
    text = text.strip()
    if not text:
        return None
    if question_type == "true_false":
        token = re.split(r"[\s.):,;-]+", text.strip("() "))[0].lower()
        return _TRUE_FALSE.get(token)
    match = _LETTER.match(text)
    if not match:
        return None
    letter = match.group(1).upper()
    return letter if not options or letter in {o.upper() for o in options} else None


def _answer_span(blocks: Sequence['TextBlock'], questions: Sequence[Dict[str, Any]]) -> List[Tuple[Dict[str, Any], str, 'TextBlock', List['TextBlock']]]:
    """Each found question with the text after its number and the lines that follow it until the next one."""
    by_number = {int(q["number"]): q for q in questions}
    ordered = _ordered(blocks)
    anchors: List[Tuple[int, int, str]] = []  # (index in `ordered`, question number, rest of the line)
    last = 0
    for index, block in enumerate(ordered):
        match = _ANCHOR.match(block.text)
        if not match:
            continue
        number = int(match.group(1))
        # Anchors only go forward: a "2." inside an open answer must not reopen question 2.
        if number in by_number and number > last:
            anchors.append((index, number, match.group(2)))
            last = number
    spans = []
    for position, (index, number, rest) in enumerate(anchors):
        end = anchors[position + 1][0] if position + 1 < len(anchors) else len(ordered)
        spans.append((by_number[number], rest, ordered[index], list(ordered[index + 1:end])))
    return spans


def read_answers(blocks: Sequence['TextBlock'], questions: Sequence[Dict[str, Any]]) -> Tuple[List[Dict[str, Any]], List[Dict[str, Any]]]:
    """The answers of one sheet in the order of the exam, and the warnings about them."""
    found = {int(q["number"]): (q, rest, head, body) for q, rest, head, body in _answer_span(blocks, questions)}
    answers: List[Dict[str, Any]] = []
    warnings: List[Dict[str, Any]] = []
    for question in sorted(questions, key=lambda q: int(q["number"])):
        number = int(question["number"])
        qtype = question.get("type", "open")
        base = {"questionId": str(question["id"]), "number": number, "type": qtype}
        field = f"answers[{number}]"
        if number not in found:
            key = "text" if qtype == "open" else "selected"
            answers.append({**base, key: _value(None, 0.0)})
            warnings.append({"code": "NOT_FOUND", "field": field, "confidence": 0.0})
            continue
        _, rest, head, body = found[number]
        if qtype == "open":
            lines = ([rest.strip()] if rest.strip() else []) and [(rest.strip(), head)]
            lines = list(lines) + [(b.text.strip(), b) for b in body if b.text.strip()]
            pen = [(t, b) for t, b in lines if b.handwritten]
            chosen = pen if pen else lines  # an answer written in pen is the student's, not the sheet's print
            text = " ".join(t for t, _ in chosen).strip()
            confidence = sum(b.confidence for _, b in chosen) / len(chosen) if chosen else 0.0
            answers.append({**base, "text": _value(text or None, confidence if text else 0.0)})
            if not text:
                warnings.append({"code": "NOT_FOUND", "field": field, "confidence": 0.0})
            elif confidence < LOW_CONFIDENCE:
                warnings.append({"code": "LOW_CONFIDENCE", "field": field, "confidence": round(confidence, 3)})
            continue
        options = question.get("options") or []
        candidates: List[Tuple[str, float]] = []
        for text, block in [(rest, head)] + [(b.text, b) for b in body]:
            letter = _letter_from(text, options, qtype)
            if letter:
                candidates.append((letter, block.confidence))
        if not candidates:
            answers.append({**base, "selected": _value(None, 0.0)})
            warnings.append({"code": "NOT_FOUND", "field": field, "confidence": 0.0})
            continue
        letter, confidence = candidates[0]
        if len({c[0] for c in candidates}) > 1:
            # Two different marks under one question: say so rather than choose.
            confidence = min(confidence, 0.5)
            warnings.append({"code": "AMBIGUOUS", "field": field, "confidence": round(confidence, 3)})
        elif confidence < LOW_CONFIDENCE:
            warnings.append({"code": "LOW_CONFIDENCE", "field": field, "confidence": round(confidence, 3)})
        answers.append({**base, "selected": _value(letter, confidence)})
    return answers, warnings


def group_sheets(pages: Sequence[Tuple[int, Sequence['TextBlock']]]) -> List[List[Tuple[int, Sequence['TextBlock']]]]:
    """Pages into sheets: a page whose header names a student opens a new sheet."""
    sheets: List[List[Tuple[int, Sequence['TextBlock']]]] = []
    for page in pages:
        header = read_header(page[1])
        if header["number"] is not None or header["name"] is not None or not sheets:
            sheets.append([page])
        else:
            sheets[-1].append(page)
    return sheets


def extract_sheets(pages: Sequence[Tuple[int, Sequence['TextBlock']]], structure: Dict[str, Any]) -> Dict[str, Any]:
    """The contract's ``sheets`` and ``warnings`` for pages already read by the engine."""
    questions = structure.get("questions") or []
    sheets_out: List[Dict[str, Any]] = []
    warnings: List[Dict[str, Any]] = []
    for sheet_index, sheet in enumerate(group_sheets(pages)):
        blocks: List['TextBlock'] = []
        for _, page_blocks in sheet:
            blocks.extend(page_blocks)
        header = read_header(sheet[0][1])
        answers, answer_warnings = read_answers(blocks, questions)
        if header["number"] is None and header["name"] is None:
            warnings.append({"code": "STUDENT_NOT_IDENTIFIED", "field": f"sheets[{sheet_index}].student", "confidence": 0.0})
        for w in answer_warnings:
            warnings.append({**w, "field": f"sheets[{sheet_index}].{w['field']}"})
        sheets_out.append({
            "pageIndexes": [index for index, _ in sheet],
            "student": {"number": header["number"], "name": header["name"]},
            "answers": answers,
        })
    read = [a.get("selected") or a.get("text") for s in sheets_out for a in s["answers"]]
    confidences = [r["confidence"] for r in read if r and r["value"] is not None]
    overall = sum(confidences) / len(confidences) if confidences else 0.0
    return {"sheets": sheets_out, "warnings": warnings, "overallConfidence": round(overall, 3)}

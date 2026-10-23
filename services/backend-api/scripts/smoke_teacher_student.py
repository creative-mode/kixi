#!/usr/bin/env python3
"""Smoke operacional, professor -> OCR -> prova -> aluno -> nota (issues #365 e #403).

Corre o fluxo inteiro pela API real, contra qualquer ambiente (local, QUA...): um professor envia um PDF e uma
imagem de prova ao OCR, constrói e publica uma prova com o que o OCR leu, e um aluno matriculado responde,
submete e lê a nota. Imprime, por etapa, o tempo e o `X-Request-ID`, e confere que o enunciado publicado é o
que o OCR leu. Só usa a biblioteca standard.

Variáveis de ambiente
  BASE          URL da API, com /api/v1 (por omissão http://localhost:8080/api/v1)
  ADMIN_USER / ADMIN_PASS   conta de administrador (para criar a escola, a turma, o professor e o aluno de teste)
  Num ambiente que não se quer povoar com dados de teste (PRD), em vez do administrador usa contas e dados que já
  existem, sem criar escola, turma nem contas:
  TEACHER_USER / TEACHER_PASS, STUDENT_USER / STUDENT_PASS   professor e aluno existentes (o aluno já matriculado)
  SMOKE_INSTITUTION_ID, SMOKE_COURSE_ID, SMOKE_CLASS_ID, SMOKE_SUBJECT_ID, SMOKE_SCHOOL_YEAR_ID   a turma e a
                disciplina que o professor leciona (o aluno é dessa turma)
  Terceiro modo, para uma escola que já existe sem a povoar com escola, curso e disciplina nova: ADMIN_USER e ADMIN_PASS
                mais SMOKE_INSTITUTION_ID, SMOKE_COURSE_ID, SMOKE_SUBJECT_ID e SMOKE_SCHOOL_YEAR_ID (a disciplina tem de
                estar no currículo do curso). Cria só uma turma «SMK<sufixo>», um professor e um aluno de teste, isolados
                das turmas reais; com SMOKE_CLEANUP=1 tira tudo isso no fim, pela ordem inversa.
  SMOKE_CLEANUP=1   no fim, manda para a lixeira as provas que o smoke criou (e as simulações, se houver ADMIN_USER e
                ADMIN_PASS: o aluno não pode apagar as suas simulações)
  OCR_PDF       caminho do PDF de prova (pode ter várias páginas). Sem ele o PDF NÃO é testado: o relatório
                diz-o e o código de saída é 3 (parcial), nunca 0
  OCR_IMAGE     caminho da imagem de prova (jpeg/png); várias imagens separadas por «:» (cada uma é uma prova).
                Tem de haver pelo menos OCR_IMAGE ou OCR_PDF
  SMOKE_OUT     (opcional) ficheiro onde guardar o relatório em JSON
  OCR_TIMEOUT   (opcional) segundos a esperar por cada leitura do OCR (por omissão 300)

Os ficheiros de prova NÃO entram no repositório: são dados de quem corre o smoke. Sem SMOKE_CLEANUP cria dados com um
sufixo único por execução (podem ver-se pelo nome «Smoke <sufixo>») e não apaga nada. Sai com código 1 se alguma
verificação falhar e 3 se tudo passou mas o PDF não foi testado. Não imprime palavras-passe nem tokens.
"""
import json
import mimetypes
import os
import secrets
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = os.environ.get("BASE", "http://localhost:8080/api/v1").rstrip("/")
RUN = secrets.token_hex(3)
OCR_TIMEOUT = int(os.environ.get("OCR_TIMEOUT", "300"))
REPORT = {"base": BASE, "run": RUN, "steps": [], "checks": [], "requestIds": {}}
OK = True
CREATED = {"statements": [], "simulations": [], "teacher": None, "student": None, "class": None, "teacher_id": None,
           "assignment": None, "code": None, "enrollment": None, "school": None}


def now():
    return time.time()


def request(method, path, body=None, token=None, raw=None, content_type=None, timeout=60):
    headers = {}
    data = None
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode()
    if raw is not None:
        headers["Content-Type"] = content_type
        data = raw
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            text = response.read().decode()
            return response.status, (json.loads(text) if text else {}), response.headers.get("X-Request-ID")
    except urllib.error.HTTPError as error:
        text = error.read().decode()
        try:
            parsed = json.loads(text)
        except ValueError:
            parsed = {"raw": text[:300]}
        return error.code, parsed, error.headers.get("X-Request-ID")


def multipart(files):
    boundary = uuid.uuid4().hex
    parts = []
    for path in files:
        name = os.path.basename(path)
        kind = mimetypes.guess_type(name)[0] or "application/octet-stream"
        with open(path, "rb") as handle:
            content = handle.read()
        parts.append(
            (f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{name}"\r\n'
             f"Content-Type: {kind}\r\n\r\n").encode() + content + b"\r\n"
        )
    return b"".join(parts) + f"--{boundary}--\r\n".encode(), f"multipart/form-data; boundary={boundary}"


def check(name, condition, detail=""):
    global OK
    mark = "PASS" if condition else "FAIL"
    print(f"{mark} {name}" + ("" if condition or not detail else f"  -> {str(detail)[:300]}"))
    REPORT["checks"].append({"name": name, "ok": bool(condition)})
    OK = OK and bool(condition)


def step(name, started, request_id=None, **extra):
    seconds = round(now() - started, 1)
    REPORT["steps"].append({"step": name, "seconds": seconds, **extra})
    if request_id:
        REPORT["requestIds"][name] = request_id
    print(f"     [{seconds:>6}s] {name}" + (f"  X-Request-ID={request_id}" if request_id else ""))


def login(user, password):
    status, body, _ = request("POST", "/auth/login", {"usernameOrEmail": user, "password": password})
    return body.get("accessToken") if status == 200 else None


def need(variable):
    value = os.environ.get(variable)
    if not value:
        sys.exit(f"Falta a variável {variable} (ver o topo deste ficheiro).")
    return value


# ---------------------------------------------------------------------------------------------------------
# OCR: o que a extração devolve -> o pedido de uma prova manual (o que o construtor faz com o que se rever)
# ---------------------------------------------------------------------------------------------------------

def label(index):
    return chr(97 + index)


def options_of(extraction_options):
    """Escolha múltipla: as opções lidas, com a primeira marcada como certa (gabarito do smoke)."""
    options = []
    for index, option in enumerate(extraction_options or []):
        text = (option.get("option_text") or option.get("text") or "").strip()
        if text:
            options.append({"label": (option.get("option_label") or chr(65 + index)).strip()[:10] or chr(65 + index), "text": text, "correct": False})
    if options:
        options[0]["correct"] = True
    return options


def leaf(text, kind, points, extraction_options=None):
    base = {"text": text.strip(), "type": kind, "maxScore": points}
    if kind == "true_false":
        base["options"] = [{"label": "A", "text": "Verdadeiro", "correct": True}, {"label": "B", "text": "Falso", "correct": False}]
    elif kind == "multiple_choice":
        options = options_of(extraction_options)
        if len(options) < 2:
            base["type"] = "open"
            base["options"] = []
        else:
            base["options"] = options
    else:
        base["options"] = []
    return base


KINDS = {"multipla_escolha": "multiple_choice", "multiple_choice": "multiple_choice", "verdadeiro_falso": "true_false", "true_false": "true_false"}


def questions_from(extraction, total=20.0):
    """O que o professor faz no construtor: aceitar as questões lidas, dar o gabarito e distribuir a cotação."""
    raw = [q for q in (extraction.get("questions") or []) if (q.get("text") or "").strip() or (q.get("subitems_content") or [])]
    share = round(total / max(len(raw), 1), 2)
    questions = []
    for q in raw:
        points = q.get("cotacao") if isinstance(q.get("cotacao"), (int, float)) and q.get("cotacao") > 0 else share
        items = [i for i in (q.get("subitems_content") or []) if (i.get("text") or "").strip()]
        kind = KINDS.get((q.get("type") or "").lower(), "open")
        if items:
            each = round(points / len(items), 2)
            group = {"text": (q.get("text") or "Questão").strip(), "type": "group", "maxScore": None, "options": [], "items": []}
            for index, item in enumerate(items):
                sub = leaf(item["text"], KINDS.get((item.get("type") or "").lower(), "open"), each, None)
                sub["label"] = (item.get("label") or label(index)).strip()[:10] or label(index)
                group["items"].append(sub)
            questions.append(group)
        else:
            questions.append(leaf(q.get("text") or "", kind, points, q.get("options")))
    # a soma das cotações tem de bater com o total (o gate da aprovação recusa se não bater)
    leaves = [x for q in questions for x in (q["items"] if q["type"] == "group" else [q])]
    drift = round(total - sum(x["maxScore"] for x in leaves), 2)
    if leaves and drift:
        leaves[-1]["maxScore"] = round(leaves[-1]["maxScore"] + drift, 2)
    return questions


def flatten(statement_questions):
    out = []
    for q in statement_questions:
        out.append(q)
    return out


def main():
    pdf = os.environ.get("OCR_PDF")
    images = [p for p in os.environ.get("OCR_IMAGE", "").split(":") if p]
    if not images and not pdf:
        sys.exit("Falta OCR_IMAGE ou OCR_PDF (ver o topo deste ficheiro).")
    for path in [pdf] + images:
        if path and not os.path.isfile(path):
            sys.exit(f"Ficheiro não encontrado: {path}")
    if not pdf:
        REPORT["notTested"] = ["pdf"]
        print("AVISO: sem OCR_PDF, o caminho do PDF não é testado e o resultado final será parcial.\n")
    reuse = bool(os.environ.get("TEACHER_USER"))
    existing_school = (not reuse) and bool(os.environ.get("SMOKE_INSTITUTION_ID"))
    admin_user, admin_pass = (None, None) if reuse else (need("ADMIN_USER"), need("ADMIN_PASS"))
    REPORT["inputs"] = {"pdf": os.path.basename(pdf) if pdf else None, "pdfBytes": os.path.getsize(pdf) if pdf else None, "images": [{"name": os.path.basename(i), "bytes": os.path.getsize(i)} for i in images]}
    print(f"Smoke {RUN} contra {BASE}\n")

    # --- 1. conta de administrador e dados de teste (escola, curso, turma, disciplina, professor, aluno)
    t = now()
    if reuse:
        teacher_token = login(need("TEACHER_USER"), need("TEACHER_PASS"))
        student = login(need("STUDENT_USER"), need("STUDENT_PASS"))
        ids = {k: int(need("SMOKE_" + k)) for k in ("INSTITUTION_ID", "COURSE_ID", "CLASS_ID", "SUBJECT_ID", "SCHOOL_YEAR_ID")}
        school, course, klass, year = ids["INSTITUTION_ID"], ids["COURSE_ID"], ids["CLASS_ID"], ids["SCHOOL_YEAR_ID"]
        subject = {"id": ids["SUBJECT_ID"]}
        check("login do professor e do aluno existentes (sem criar contas nem estrutura)", bool(teacher_token) and bool(student))
        step("preparação", t)
        if not (teacher_token and student):
            return finish()
    else:
        admin = login(admin_user, admin_pass)
        check("login do administrador", bool(admin))
        if not admin:
            return finish()
        years = request("GET", "/school-years", token=admin)[1] if not existing_school else []
        year = None
        if isinstance(years, list) and years:
            year = max(years, key=lambda y: y.get("startYear", 0))["id"]
        if year is None and not existing_school:
            year = request("POST", "/school-years", {"startYear": 2026, "endYear": 2027}, token=admin)[1].get("id")
        if existing_school:
            school, course = int(need("SMOKE_INSTITUTION_ID")), int(need("SMOKE_COURSE_ID"))
            year = int(need("SMOKE_SCHOOL_YEAR_ID"))
            subject = {"id": int(need("SMOKE_SUBJECT_ID"))}
            klass = request("POST", "/classes", {"code": f"SMK{RUN}".upper(), "grade": 12, "courseId": course, "schoolYearId": year}, token=admin)[1].get("id")
            CREATED["class"] = klass
        else:
            school = request("POST", "/institutions", {"code": f"SM{RUN}".upper(), "name": f"Smoke {RUN}", "shortName": "SM"}, token=admin)[1].get("id")
            course = request("POST", "/courses", {"code": "SMK", "name": "Smoke", "institutionId": school}, token=admin)[1].get("id")
            klass = request("POST", "/classes", {"code": "12S", "grade": 12, "courseId": course, "schoolYearId": year}, token=admin)[1].get("id")
            subject = request("POST", "/subjects", {"code": f"SMK{RUN}".upper(), "name": f"Smoke {RUN}", "shortName": "SMK"}, token=admin)[1]
            request("POST", f"/courses/{course}/subjects", {"subjectId": subject.get("id"), "grade": None}, token=admin)
        teacher = request("POST", "/teachers", {"firstName": "Professor", "lastName": f"Smoke{RUN}", "email": f"prof{RUN}@example.com"}, token=admin)[1].get("id")
        CREATED["teacher_id"] = teacher
        request("POST", f"/teachers/{teacher}/account", {"username": f"prof{RUN}", "email": f"prof{RUN}@example.com", "password": f"Smk#{RUN}Prof1"}, token=admin)
        request("POST", f"/institutions/{school}/teachers/{teacher}", {}, token=admin)
        a_status, a_body, _ = request("POST", "/teaching-assignments", {"teacherId": teacher, "classId": klass, "subjectId": subject.get("id"), "schoolYearId": year}, token=admin)
        assigned = a_status
        CREATED["assignment"] = a_body.get("id") if isinstance(a_body, dict) else None
        code_body = request("POST", "/enrollment-codes", {"classId": klass, "maxUses": 5}, token=admin)[1]
        code = code_body.get("code")
        CREATED["code"] = code_body.get("id")
        st, student_reg, _ = request("POST", "/auth/register", {"username": f"aluno{RUN}", "email": f"aluno{RUN}@example.com", "password": f"Smk#{RUN}Aluno1", "firstName": "Aluno", "lastName": f"Smoke{RUN}"})
        student = student_reg.get("accessToken")
        e_status, e_body, _ = request("POST", "/enrollments", {"code": code}, token=student) if student and code else (0, {}, None)
        enrolled = e_status
        CREATED["enrollment"] = e_body.get("id") if isinstance(e_body, dict) else None
        teacher_token = login(f"prof{RUN}", f"Smk#{RUN}Prof1")
        check("estrutura de teste criada (escola, curso, turma, disciplina)", all([school, course, klass, subject.get("id")]))
        check("professor com conta, afiliado e com a disciplina atribuída", bool(teacher_token) and assigned in (200, 201), assigned)
        check("aluno registado e matriculado com o código da turma", bool(student) and enrolled in (200, 201), enrolled)
        step("preparação", t)
        if not (teacher_token and student):
            return finish()


    CREATED["teacher"], CREATED["student"], CREATED["school"] = teacher_token, student, school
    CREATED["admin"] = admin if not reuse else None

    # --- 2. OCR: PDF e imagem, pelo professor
    published = []
    sources = [("pdf", pdf)] * bool(pdf) + [("imagem" if len(images) == 1 else f"imagem {n + 1}", path) for n, path in enumerate(images)]
    for label_, path in sources:
        body, kind = multipart([path])
        t = now()
        status, extraction, rid = request("POST", "/ocr/extract/exam", token=teacher_token, raw=body, content_type=kind, timeout=OCR_TIMEOUT)
        step(f"ocr {label_}", t, rid, status=status)
        questions = extraction.get("questions") or [] if isinstance(extraction, dict) else []
        check(f"OCR do {label_}: resposta 200 e estado de sucesso", status == 200 and extraction.get("status", "success") in ("success", "partial"), (status, str(extraction)[:200]))
        check(f"OCR do {label_}: leu questões", len(questions) > 0, len(questions))
        pages = (extraction.get("document") or {}).get("pageCount") if isinstance(extraction, dict) else None
        REPORT.setdefault("ocr", {})[label_] = {"questions": len(questions), "pages": pages, "figures": len(extraction.get("images_to_upload") or []) if isinstance(extraction, dict) else 0}
        print(f"     {label_}: {len(questions)} questões, páginas={pages}, figuras={REPORT['ocr'][label_]['figures']}")
        if status != 200 or not questions:
            continue

        # --- 3. construir, aprovar e publicar com o que se leu
        built = questions_from(extraction)
        title = f"Smoke {RUN} {label_}"
        payload = {"institutionId": school, "subjectId": subject.get("id"), "title": title, "examType": "Teste", "durationMinutes": 60,
                   "classId": klass, "courseId": course, "schoolYearId": year, "visible": False, "questions": built}
        t = now()
        created, statement, rid = request("POST", "/statements/manual", payload, token=teacher_token)
        check(f"prova do {label_} criada", created in (200, 201), (created, str(statement)[:200]))
        if created not in (200, 201):
            continue
        sid = statement["id"]
        CREATED["statements"].append(sid)
        approved, appr, rid2 = request("POST", f"/statements/{sid}/approve", token=teacher_token)
        check(f"prova do {label_} aprovada (o gate aceita a cotação e o gabarito)", approved in (200, 201, 204), (approved, str(appr)[:300]))
        visible, _, _ = request("PATCH", f"/statements/{sid}/visibility?visible=true", token=teacher_token)
        check(f"prova do {label_} publicada", visible in (200, 204), visible)
        step(f"construir e publicar {label_}", t, rid, statementId=sid)
        if visible not in (200, 204):
            continue

        # fidelidade: o que está publicado é o que o OCR leu
        full = request("GET", f"/statements/{sid}/full", token=teacher_token)[1]
        saved = [q for q in full.get("questions", []) if q.get("parentId") is None]
        read_texts = [(q.get("text") or "").strip() for q in built]
        saved_texts = [(q.get("text") or "").strip() for q in saved]
        check(f"enunciado do {label_} preservado: mesmas questões, na mesma ordem, com o mesmo texto", saved_texts == read_texts,
              [(a[:40], b[:40]) for a, b in zip(read_texts, saved_texts) if a != b][:2] or (len(read_texts), len(saved_texts)))
        math = sum(1 for text in read_texts if "$" in text or "\\(" in text)
        REPORT["ocr"][label_]["questionsWithFormulas"] = math
        total = sum((q.get("maxScore") or 0) for q in full.get("questions", []) if q.get("parentId") is not None or not any(x.get("parentId") == q.get("id") for x in full.get("questions", [])))
        check(f"cotação do {label_} soma 20", abs(total - 20.0) < 0.01, total)
        published.append((label_, sid, built))

    # --- 4. aluno: abrir, responder, submeter, ver a nota
    me = request("GET", "/me", token=student)[1]
    account = me.get("accountId") or me.get("account_id") or me.get("id")
    for label_, sid, built in published:
        t = now()
        st, sim, rid = request("POST", "/simulations", {"accountId": account, "statementId": sid, "schoolYearId": year}, token=student)
        check(f"aluno inicia a prova do {label_}", st in (200, 201), (st, str(sim)[:200]))
        if st not in (200, 201):
            continue
        CREATED["simulations"].append(sim["id"])
        view = request("GET", f"/statements/{sid}/full", token=student)[1]
        questions = view.get("questions", [])
        leaks = [q for q in questions for o in (q.get("options") or []) if o.get("isCorrect") or o.get("correct")]
        check(f"o aluno não recebe o gabarito da prova do {label_}", not leaks, len(leaks))
        expected = 0.0
        answered = 0
        leaf_ids = {q["id"] for q in questions if not any(x.get("parentId") == q["id"] for x in questions)}
        key = {}
        teacher_view = request("GET", f"/statements/{sid}/full", token=teacher_token)[1]
        for q in teacher_view.get("questions", []):
            correct = [o["id"] for o in (q.get("options") or []) if o.get("isCorrect") or o.get("correct")]
            if correct:
                key[q["id"]] = correct[0]
        for q in questions:
            if q["id"] not in leaf_ids:
                continue
            options = q.get("options") or []
            if options:
                # responde certo em metade e errado na outra metade, para a nota ser verificável
                choose_right = answered % 2 == 0
                right = key.get(q["id"])
                pick = right if choose_right else next((o["id"] for o in options if o["id"] != right), options[0]["id"])
                body = {"simulationId": sim["id"], "questionId": q["id"], "selectedOptionId": pick, "answerText": None}
                if choose_right and right is not None:
                    expected += q.get("maxScore") or 0
            else:
                body = {"simulationId": sim["id"], "questionId": q["id"], "selectedOptionId": None, "answerText": "Resposta de teste do smoke."}
            code_, _, _ = request("POST", "/simulation-answers", body, token=student)
            answered += 1 if code_ in (200, 201) else 0
        check(f"aluno respondeu a todas as questões do {label_}", answered == len(leaf_ids), (answered, len(leaf_ids)))
        status, result, rid = request("POST", f"/simulations/{sim['id']}/submit", {}, token=student)
        step(f"aluno responde e submete {label_}", t, rid, simulationId=sim["id"])
        check(f"submissão da prova do {label_}", status in (200, 201), (status, str(result)[:200]))
        if status in (200, 201):
            score = result.get("finalScore")
            pending = result.get("pendingReviewCount") or 0
            print(f"     nota {score} de 20, certas {result.get('correctAnswers')} de {result.get('totalQuestions')}, por rever {pending}")
            REPORT.setdefault("results", {})[label_] = {"score": score, "correct": result.get("correctAnswers"), "total": result.get("totalQuestions"), "pendingReview": pending}
            check(f"nota do {label_} calculada pelo servidor", isinstance(score, (int, float)))
            closed = [q for q in questions if q["id"] in leaf_ids and q.get("options")]
            if not closed:
                print(f"     INFO: a prova do {label_} só tem questões abertas: a nota fica por corrigir ({pending} por rever) e a conta das fechadas não se exerce")
                REPORT.setdefault("notes", []).append(f"{label_}: sem questões fechadas, a nota (0) não prova o cálculo")
            elif isinstance(score, (int, float)):
                check(f"nota do {label_} bate com o gabarito (certas em metade das {len(closed)} questões fechadas; as abertas ficam por rever)",
                      abs(score - expected) < 0.01, f"esperado {expected}; recebido {score}; por rever {pending}")
            seen = request("GET", f"/simulations/{sim['id']}/result", token=student)[0]
            check(f"o aluno volta a ver o resultado do {label_}", seen == 200, seen)
    return finish()


def cleanup():
    """Com SMOKE_CLEANUP=1, tira o que o smoke criou. As provas apaga-as o professor; as simulações só um administrador as
    apaga (o aluno recebe 403). No modo de escola existente, desfaz também a turma de teste, o professor e o aluno, pela
    ordem inversa à da criação, e diz o que cada passo respondeu (um 409 conta como «ficou»)."""
    if os.environ.get("SMOKE_CLEANUP") != "1":
        return
    admin = CREATED.get("admin") or (login(os.environ["ADMIN_USER"], os.environ["ADMIN_PASS"]) if os.environ.get("ADMIN_USER") and os.environ.get("ADMIN_PASS") else None)
    removed = {"simulations": 0, "statements": 0}
    for sid in CREATED["simulations"]:
        removed["simulations"] += request("DELETE", f"/simulations/{sid}", token=admin or CREATED["student"])[0] in (200, 204)
    for sid in CREATED["statements"]:
        removed["statements"] += request("DELETE", f"/statements/{sid}", token=CREATED["teacher"])[0] in (200, 204)
    REPORT["cleanup"] = removed
    left = len(CREATED["simulations"]) - removed["simulations"]
    print(f"     limpeza: {removed['statements']} de {len(CREATED['statements'])} provas e {removed['simulations']} de {len(CREATED['simulations'])} simulações para a lixeira"
          + (f"; {left} simulação(ões) ficam (só um administrador as apaga: junta ADMIN_USER e ADMIN_PASS)" if left else ""))
    if not (CREATED["class"] and admin):
        return
    steps = []
    def drop(name, path):
        status = request("DELETE", path, token=admin)[0]
        steps.append((name, status))
    if CREATED["code"]:
        drop("código de matrícula", f"/enrollment-codes/{CREATED['code']}")
    if CREATED["enrollment"]:
        drop("matrícula do aluno", f"/enrollments/{CREATED['enrollment']}")
    if CREATED["assignment"]:
        drop("atribuição do professor", f"/teaching-assignments/{CREATED['assignment']}")
    if CREATED["teacher_id"]:
        drop("afiliação do professor à escola", f"/institutions/{CREATED['school']}/teachers/{CREATED['teacher_id']}")
        drop("conta do professor", f"/teachers/{CREATED['teacher_id']}/account")
        drop("professor", f"/teachers/{CREATED['teacher_id']}")
    student_account = request("GET", f"/accounts/username/aluno{RUN}", token=admin)[1].get("id")
    if student_account:
        drop("conta do aluno", f"/accounts/{student_account}")
    drop("turma de teste", f"/classes/{CREATED['class']}")
    REPORT["cleanup"]["structure"] = [{"step": n, "status": c} for n, c in steps]
    print("     limpeza da estrutura de teste: " + ", ".join(f"{n} {c}" for n, c in steps))
    stuck = [n for n, c in steps if c not in (200, 204)]
    check("a turma de teste, o professor e o aluno foram retirados", not stuck, ", ".join(stuck))


def finish():
    cleanup()
    REPORT["ok"] = OK
    partial = OK and bool(REPORT.get("notTested"))
    REPORT["partial"] = partial
    out = os.environ.get("SMOKE_OUT")
    if out:
        with open(out, "w", encoding="utf-8") as handle:
            json.dump(REPORT, handle, ensure_ascii=False, indent=2)
    print("\nRESULTADO:", "FALHOU" if not OK else ("PARCIAL, não testado: " + ", ".join(REPORT["notTested"]) if partial else "OK"))
    sys.exit(1 if not OK else (3 if partial else 0))


if __name__ == "__main__":
    main()

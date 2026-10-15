#!/usr/bin/env python3
"""Fluxo ponta a ponta da estrutura académica (issue #253) contra um backend real.

Uso: BASE=http://localhost:8092/api/v1 ADMIN_USER=admin ADMIN_PASS=... python3 academic_structure_e2e.py
Cria dados com um sufixo único por execução (pode correr-se várias vezes na mesma base) e não
apaga nada. Sai com código 1 se alguma verificação falhar.
"""
import json, os, sys, time, urllib.request, urllib.error

B = os.environ.get("BASE", "http://localhost:8092/api/v1")
RUN = str(int(time.time()))[-7:]
ok = True


def call(method, path, body=None, tok=None):
    h = {"Content-Type": "application/json"} if body is not None else {}
    if tok:
        h["Authorization"] = "Bearer " + tok
    r = urllib.request.Request(B + path, data=json.dumps(body).encode() if body is not None else None, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=60) as x:
            t = x.read().decode()
            return x.status, (json.loads(t) if t else {})
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        try:
            return e.code, json.loads(t)
        except Exception:
            return e.code, {"raw": t[:300]}


def check(name, cond, detail=""):
    global ok
    print(("PASS " if cond else "FAIL ") + name + ("" if cond or not detail else "  -> " + str(detail)[:300]))
    ok = ok and bool(cond)


def login(u, p):
    return call("POST", "/auth/login", {"usernameOrEmail": u, "password": p})[1].get("accessToken")


A = login(os.environ.get("ADMIN_USER", "admin"), os.environ["ADMIN_PASS"])
check("login do administrador", bool(A))

# --- contas: aluno (registo público) e professor (ficha + acesso)
st, aluno = call("POST", "/auth/register", {"username": f"aluno{RUN}", "email": f"aluno{RUN}@example.com", "password": "Aluno#12345", "firstName": "Aluno", "lastName": RUN})
S = aluno.get("accessToken")
st, t = call("POST", "/teachers", {"firstName": "Rita", "lastName": f"Prof{RUN}", "email": f"prof{RUN}@example.com"}, tok=A)
TID = t["id"]
call("POST", f"/teachers/{TID}/account", {"username": f"prof{RUN}", "email": f"prof{RUN}@example.com", "password": "Prof#12345"}, tok=A)
P = login(f"prof{RUN}", "Prof#12345")
check("contas de aluno e professor", bool(S) and bool(P))

# --- escolas, ano, disciplinas
yr = call("POST", "/school-years", {"startYear": 3000 + int(RUN) % 900, "endYear": 3001 + int(RUN) % 900}, tok=A)[1]["id"]
sch = {}
for k in "AB":
    sch[k] = call("POST", "/institutions", {"code": f"E{k}{RUN}", "name": f"Escola {k} {RUN}", "shortName": f"E{k}"}, tok=A)[1]["id"]
sch["C"] = call("POST", "/institutions", {"code": f"EC{RUN}", "name": f"Escola C {RUN}"}, tok=A)[1]["id"]
mat = call("POST", "/subjects", {"code": f"MAT{RUN}", "name": f"Matemática {RUN}", "shortName": "MAT"}, tok=A)[1]
fis = call("POST", "/subjects", {"code": f"FIS{RUN}", "name": f"Física {RUN}", "shortName": "FIS"}, tok=A)[1]

# --- o mesmo código de curso em duas escolas; duplicado na mesma escola recusado
cA = call("POST", "/courses", {"code": "INF", "name": "Informática", "institutionId": sch["A"]}, tok=A)
cB = call("POST", "/courses", {"code": "INF", "name": "Informática", "institutionId": sch["B"]}, tok=A)
check("o código INF existe nas duas escolas", cA[0] in (200, 201) and cB[0] in (200, 201), (cA, cB))
dup = call("POST", "/courses", {"code": "INF", "name": "Outra", "institutionId": sch["A"]}, tok=A)
check("INF repetido na mesma escola → 409", dup[0] == 409, dup)
courseA, courseB = cA[1]["id"], cB[1]["id"]
clA = call("POST", "/classes", {"code": "12A", "grade": 12, "courseId": courseA, "schoolYearId": yr}, tok=A)[1]["id"]
clB = call("POST", "/classes", {"code": "12B", "grade": 12, "courseId": courseB, "schoolYearId": yr}, tok=A)[1]["id"]

# --- currículos que partilham Matemática
st, l1 = call("POST", f"/courses/{courseA}/subjects", {"subjectId": mat["id"], "grade": None}, tok=A)
check("currículo A: Matemática (todas as classes) → 201", st == 201, l1)
check("currículo A: Física só 12.ª → 201", call("POST", f"/courses/{courseA}/subjects", {"subjectId": fis["id"], "grade": 12}, tok=A)[0] == 201)
check("currículo B: Matemática → 201", call("POST", f"/courses/{courseB}/subjects", {"subjectId": mat["id"]}, tok=A)[0] == 201)
check("currículo repetido → 409", call("POST", f"/courses/{courseA}/subjects", {"subjectId": mat["id"]}, tok=A)[0] == 409)
check("disciplina inexistente → 422", call("POST", f"/courses/{courseA}/subjects", {"subjectId": 99999999}, tok=A)[0] == 422)
catalogA = [s["id"] for s in call("GET", f"/institutions/{sch['A']}/subjects", tok=A)[1]]
check("o catálogo da escola A ganhou as disciplinas do currículo", mat["id"] in catalogA and fis["id"] in catalogA, catalogA)

# --- professor afiliado às duas escolas (e a uma terceira, sem aulas)
for k in "ABC":
    call("POST", f"/institutions/{sch[k]}/teachers/{TID}", {}, tok=A)

# --- atribuições válidas e inválidas
def assign(cl, subj, tid=TID):
    return call("POST", "/teaching-assignments", {"teacherId": tid, "classId": cl, "subjectId": subj, "schoolYearId": yr}, tok=A)

a1 = assign(clA, mat["id"]); a2 = assign(clB, mat["id"]); a3 = assign(clA, fis["id"])
check("professor dá Matemática em A e em B e Física em A", all(x[0] in (200, 201) for x in (a1, a2, a3)), (a1, a2, a3))
check("disciplina fora do currículo da turma (Física em B) → 422", assign(clB, fis["id"])[0] == 422)
t2 = call("POST", "/teachers", {"firstName": "Outro", "lastName": f"Prof{RUN}"}, tok=A)[1]["id"]
check("professor não afiliado à escola da turma → 422", assign(clA, mat["id"], t2)[0] == 422)

# --- estrutura, visão do professor, ofertas, disciplinas da turma
st, tree = call("GET", f"/institutions/{sch['A']}/structure", tok=A)
check("estrutura de A: 1 curso, 2 disciplinas no currículo, 1 turma com 2 disciplinas com professor",
      st == 200 and len(tree["courses"]) == 1 and len(tree["courses"][0]["curriculum"]) == 2
      and len(tree["courses"][0]["classes"][0]["subjects"]) == 2
      and all(s["teachers"] and s["teachers"][0]["id"] == TID for s in tree["courses"][0]["classes"][0]["subjects"]), tree)
check("estrutura de A: contadores e sem avisos", tree["counts"]["courses"] == 1 and tree["counts"]["classes"] == 1 and tree["counts"]["teachers"] == 1 and tree["warnings"] == [], tree["counts"])
treeC = call("GET", f"/institutions/{sch['C']}/structure", tok=A)[1]
check("estrutura de C: aviso de professor sem atribuições", [w["code"] for w in treeC["warnings"]] == ["TEACHER_WITHOUT_ASSIGNMENTS"], treeC["warnings"])
ov = call("GET", f"/teachers/{TID}/overview", tok=A)[1]
by = {i["id"]: len(i["assignments"]) for i in ov["institutions"]}
check("visão do professor: A=2, B=1, C=0 (afiliada sem aulas)", by == {sch["A"]: 2, sch["B"]: 1, sch["C"]: 0}, by)
check("visão própria do professor (/teachers/me/overview)", call("GET", "/teachers/me/overview", tok=P)[1]["teacher"]["id"] == TID)
off = call("GET", f"/subjects/{mat['code']}/offerings", tok=A)[1]
check("Matemática é lecionada em 2 escolas", sorted(i["id"] for i in off["institutions"]) == sorted([sch["A"], sch["B"]]), off)
cs_t = call("GET", f"/classes/{clA}/subjects", tok=P)[1]
cs_s = call("GET", f"/classes/{clA}/subjects", tok=S)[1]
check("disciplinas da turma: professor vê professores, aluno não", all(x["teachers"] for x in cs_t) and all(x["teachers"] == [] for x in cs_s) and len(cs_s) == 2, (cs_t, cs_s))
only = [i["id"] for i in call("GET", f"/institutions?onlyWithClassesInYear={yr}", tok=S)[1]]
check("?onlyWithClassesInYear só devolve A e B", sorted(only) == sorted([sch["A"], sch["B"]]), only)

# --- enunciados: coerente e incoerente
def stm(inst, cl, course, subj):
    return call("POST", "/statements/manual", {"institutionId": inst, "subjectId": subj, "title": "Prova", "examType": "Teste", "classId": cl, "courseId": course, "schoolYearId": yr, "visible": False,
                                               "questions": [{"text": "1+1?", "type": "open", "maxScore": 20.0}]}, tok=A)
check("enunciado coerente → 2xx", stm(sch["A"], clA, courseA, mat["id"])[0] in (200, 201))
check("enunciado com turma de outra escola → 422", stm(sch["B"], clA, courseA, mat["id"])[0] == 422)
check("enunciado com disciplina fora do currículo da turma → 422", stm(sch["B"], clB, courseB, fis["id"])[0] == 422)

# --- apagar com dependentes
check("apagar curso com turmas → 409", call("DELETE", f"/courses/{courseA}", tok=A)[0] == 409)
check("apagar turma com atribuições → 409", call("DELETE", f"/classes/{clA}", tok=A)[0] == 409)
check("apagar disciplina em uso → 409", call("DELETE", f"/subjects/{mat['code']}", tok=A)[0] == 409)
check("apagar escola com cursos → 409", call("DELETE", f"/institutions/{sch['A']}", tok=A)[0] == 409)
links = call("GET", f"/courses/{courseA}/subjects", tok=A)[1]
matlink = next(l["id"] for l in links if l["subjectId"] == mat["id"])
check("tirar do currículo o que tem atribuição → 409", call("DELETE", f"/courses/{courseA}/subjects/{matlink}", tok=A)[0] == 409)

# --- permissões: professor não escreve estrutura mas lê
check("professor não cria curso → 403", call("POST", "/courses", {"code": "X", "name": "Xyz", "institutionId": sch["A"]}, tok=P)[0] == 403)
check("professor não cria turma → 403", call("POST", "/classes", {"code": "Z", "grade": 10, "courseId": courseA, "schoolYearId": yr}, tok=P)[0] == 403)
check("professor não altera currículo → 403", call("POST", f"/courses/{courseA}/subjects", {"subjectId": mat["id"], "grade": 10}, tok=P)[0] == 403)
check("professor continua a ler cursos → 200", call("GET", "/courses", tok=P)[0] == 200)
check("ofertas e estrutura só ADMIN", call("GET", f"/subjects/{mat['code']}/offerings", tok=P)[0] == 403 and call("GET", f"/institutions/{sch['A']}/structure", tok=P)[0] == 403)

print("\nRESULTADO:", "OK" if ok else "FALHOU")
sys.exit(0 if ok else 1)

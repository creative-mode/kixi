import json, os, random, struct, sys, time, urllib.request, urllib.error, uuid, zlib
B = os.environ.get("BASE", "http://localhost:8062/api/v1")
random.seed(7)
def call(method, path, body=None, tok=None, raw=None, ctype=None):
    h = {}
    if tok: h["Authorization"] = "Bearer " + tok
    data = None
    if body is not None: h["Content-Type"] = "application/json"; data = json.dumps(body).encode()
    if raw is not None: h["Content-Type"] = ctype; data = raw
    r = urllib.request.Request(B + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=60) as x:
            t = x.read().decode(); return x.status, (json.loads(t) if t else {})
    except urllib.error.HTTPError as e:
        t = e.read().decode()
        try: return e.code, json.loads(t)
        except Exception: return e.code, {"raw": t[:200]}
def png(w, h, seed):
    rnd = random.Random(seed); rows = []
    for y in range(h):
        row = bytearray([0])
        for x in range(w):
            base = (x * 255 // w, y * 255 // h, (x + y) * 255 // (w + h))
            n = rnd.randint(-1, 1); row += bytes(min(255, max(0, c + n)) for c in base)
        rows.append(bytes(row))
    def ch(t, d): c = struct.pack('>I', len(d)) + t + d; return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + ch(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + ch(b'IDAT', zlib.compress(b''.join(rows), 6)) + ch(b'IEND', b'')
def upload(tok, data, name):
    b = uuid.uuid4().hex
    body = (f'--{b}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\nContent-Type: image/png\r\n\r\n').encode() + data + f'\r\n--{b}--\r\n'.encode()
    return call("POST", "/images?kind=avatar", tok=tok, raw=body, ctype=f"multipart/form-data; boundary={b}")
N = int(os.environ.get("STUDENTS", "40"))
login = lambda u, p: call("POST", "/auth/login", {"usernameOrEmail": u, "password": p})[1].get("accessToken")
A = login("admin", "Kixi#Admin2026")
RUN = str(int(time.time()))[-6:]
yr = call("GET", "/school-years", tok=A)[1]
yrid = (yr[0]["id"] if isinstance(yr, list) and yr else call("POST", "/school-years", {"startYear": 2026, "endYear": 2027}, tok=A)[1]["id"])
inst = call("POST", "/institutions", {"code": f"PF{RUN}", "name": "Escola de Desempenho", "shortName": "ED"}, tok=A)[1]["id"]
course = call("POST", "/courses", {"code": "INF", "name": "Informática", "institutionId": inst}, tok=A)[1]["id"]
cls = call("POST", "/classes", {"code": "12A", "grade": 12, "courseId": course, "schoolYearId": yrid}, tok=A)[1]["id"]
code = call("POST", "/enrollment-codes", {"classId": cls, "maxUses": 500}, tok=A)[1]["code"]
print("turma", cls, "código", code)
png_cache = {}
students = []
for i in range(N):
    u = f"perf{RUN}x{i}"
    st, r = call("POST", "/auth/register", {"username": u, "email": f"{u}@example.com", "password": "Perf#12345", "firstName": f"Aluno{i}", "lastName": f"Teste{i}"})
    tok = r.get("accessToken")
    if not tok: print("registo falhou", st, r); continue
    st, e = call("POST", "/enrollments", {"code": code}, tok=tok)
    if st not in (200, 201): print("matrícula falhou", st, e); continue
    me = call("GET", "/me", tok=tok)[1]
    aid = me.get("accountId") or me.get("account_id") or me.get("id")
    photo = None
    if i % 4 != 3:  # três em cada quatro têm foto grande
        size = 1000 + (i % 3) * 100
        key = i % 5
        if key not in png_cache: png_cache[key] = png(size, size, key)
        st, up = upload(tok, png_cache[key], f"a{i}.png")
        photo = up.get("url") if st in (200, 201) else None
        if not photo: print('upload', st, str(up)[:120])
        if photo: call("PUT", "/me", {"photo": photo}, tok=tok)
    students.append((u, tok, aid, photo))
print("alunos", len(students), "com foto", sum(1 for s in students if s[3]))
posts = []
for i, (u, tok, aid, _) in enumerate(students[: int(N * 0.6)]):
    for k in range(2):
        st, p = call("POST", "/feed/posts", {"type": "DOUBT", "classId": cls, "content": f"Alguém explica o exercício {i}-{k} da prova de Física? Fiquei com dúvidas na alínea b."}, tok=tok)
        if st in (200, 201): posts.append(p["id"])
print("posts", len(posts))
total = 0
for pid in posts:
    for c in range(random.randint(8, 22)):
        u, tok, aid, _ = random.choice(students)
        st, _r = call("POST", f"/feed/posts/{pid}/comments", {"content": f"Comentário {c}: eu fiz assim, vê se ajuda a perceber o passo {c}."}, tok=tok)
        total += st in (200, 201)
print("comentários", total)
json.dump({"classId": cls, "viewer": {"user": students[0][0], "pass": "Perf#12345"}, "posts": posts[:10], "students": [s[0] for s in students]}, open("/tmp/perf/seed.json", "w"))

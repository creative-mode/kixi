import json, statistics, sys, time, urllib.request
B = sys.argv[1]; label = sys.argv[2]
seed = json.load(open("/tmp/perf/seed.json"))
def req(path, tok=None, n=1):
    h = {"Authorization": "Bearer " + tok} if tok else {}
    t = time.perf_counter(); r = urllib.request.urlopen(urllib.request.Request(B + path, headers=h), timeout=60); b = r.read(); return (time.perf_counter() - t) * 1000, len(b), b
def login():
    r = urllib.request.Request(B + "/api/v1/auth/login", data=json.dumps({"usernameOrEmail": seed["viewer"]["user"], "password": seed["viewer"]["pass"]}).encode(), headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(r))["accessToken"]
tok = login()
def bench(name, path, n=40, tok_=tok):
    for _ in range(200): req(path, tok_)  # aquecimento (JIT)
    ts = []; size = 0
    for _ in range(n):
        ms, size, _b = req(path, tok_); ts.append(ms)
    ts.sort(); print(f"| {name} | {statistics.median(ts):.1f} | {ts[int(len(ts)*0.95)-1]:.1f} | {size/1024:.1f} KB |")
print(f"### {label}\n| pedido | mediana ms | p95 ms | resposta |\n|---|---|---|---|")
cid = seed["classId"]
bench("feed (10 posts)", f"/api/v1/feed/posts?classId={cid}&page=1&limit=10")
for pid in seed["posts"][:1]:
    bench("comentários de 1 post (≈15)", f"/api/v1/feed/posts/{pid}/comments?page=1&limit=20")
ms, sz, body = req(f"/api/v1/feed/posts/{seed['posts'][0]}/comments?page=1&limit=20", tok)
cs = json.loads(body); print(f"\ncomentários devolvidos: {len(cs)}, autores distintos: {len({c['authorAccountId'] for c in cs})}, com authorPhoto: {sum(1 for c in cs if c.get('authorPhoto'))}")
# imagens: um avatar real
me = json.loads(req("/api/v1/me", tok)[2]); photo = None
for c in cs:
    pass
import glob, os
files = sorted(glob.glob("/tmp/perf-uploads/images/avatar/*"), key=os.path.getsize, reverse=True)
if files:
    name = os.path.basename(files[0])
    for q in ("", "?w=96", "?w=192"):
        ts = []; size = 0
        for _ in range(15):
            ms, size, _b = req(f"/uploads/images/avatar/{name}{q}"); ts.append(ms)
        print(f"imagem {name[:8]}… {q or '(original)'}: {size/1024:.1f} KB, mediana {statistics.median(ts):.1f} ms")

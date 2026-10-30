#!/usr/bin/env python3
"""Mede a exatidão do OCR de folhas de resposta numa pasta de fotos com gabarito (kixi#499).

Uso:
  python tools/medir_folhas.py --pasta fotos/ --gabarito gabarito.csv --estrutura estrutura.json \\
      --url http://localhost:8000 --api-key "$OCR_API_KEY" [--json relatorio.json]

  --pasta       fotos ou digitalizações, uma folha de aluno por ficheiro (jpg, png ou pdf)
  --gabarito    CSV UTF-8: ficheiro,numero,nome,q1,q2,... (as colunas qN são as respostas objetivas certas
                do que o aluno ESCREVEU, não as respostas corretas da prova; vazio = o aluno deixou em branco)
  --estrutura   JSON do contexto do endpoint: {"questions": [{"id": "1", "number": 1, "type": "multiple_choice",
                "options": ["A","B","C","D"]}, ...]} (o mesmo que o Kixi envia)

A métrica que decide é o erro silencioso: uma resposta lida ERRADA com confiança alta (>= --confianca-alta).
Uma leitura errada com confiança baixa é apanhada pela validação da professora; uma errada e confiante, não.

Só usa a biblioteca standard. Sai com código 1 se não cumprir --min-objectivas ou --max-erros-silenciosos.
"""
import argparse
import csv
import json
import mimetypes
import os
import sys
import time
import unicodedata
import urllib.error
import urllib.request
import uuid
from pathlib import Path


def _normal(texto):
    """Minúsculas, sem acentos nem pontuação: para comparar nomes escritos à mão."""
    sem_acentos = unicodedata.normalize("NFKD", texto or "").encode("ascii", "ignore").decode()
    return " ".join("".join(c if c.isalnum() else " " for c in sem_acentos.lower()).split())


def nome_certo(lido, esperado):
    """O nome conta como certo se o primeiro e o último nome coincidem (a letra de um nome do meio varia muito)."""
    a, b = _normal(lido).split(), _normal(esperado).split()
    return bool(a) and bool(b) and a[0] == b[0] and a[-1] == b[-1]


def avaliar_folha(folha, esperado, confianca_alta=0.8):
    """Compara uma folha lida com o gabarito dela. Puro: sem rede.

    `folha` é um elemento de `sheets` da resposta do OCR; `esperado` = {"numero": int|None, "nome": str, "objectivas": {n: letra|""}}.
    """
    aluno = folha.get("student") or {}
    numero = (aluno.get("number") or {}).get("value")
    nome = (aluno.get("name") or {}).get("value")
    resultado = {
        "numero_lido": numero,
        "nome_lido": nome,
        "numero_certo": esperado.get("numero") is not None and numero == esperado["numero"],
        "nome_certo": nome_certo(nome, esperado.get("nome", "")),
        "objectivas": [],
    }
    lidas = {a["number"]: a for a in folha.get("answers", [])}
    for n, certa in sorted(esperado["objectivas"].items()):
        lida = (lidas.get(n) or {}).get("selected") or {}
        valor, confianca = lida.get("value"), float(lida.get("confidence") or 0.0)
        esperada = certa or None  # em branco = não há resposta para ler
        if valor is None:
            estado = "nao_lida" if esperada else "certa"  # deixar em branco e não ler nada é o certo
        elif valor == esperada:
            estado = "certa"
        else:
            estado = "errada_confiante" if confianca >= confianca_alta else "errada_duvidosa"
        resultado["objectivas"].append({"questao": n, "esperada": esperada, "lida": valor, "confianca": confianca, "estado": estado})
    return resultado


def resumir(resultados, avisos, segundos):
    obj = [o for r in resultados for o in r["objectivas"]]
    total = len(obj)
    contagem = {e: sum(1 for o in obj if o["estado"] == e) for e in ("certa", "nao_lida", "errada_duvidosa", "errada_confiante")}
    lidas = total - contagem["nao_lida"]
    return {
        "folhas": len(resultados),
        "objectivas": total,
        **contagem,
        "exatidao_objectivas": contagem["certa"] / total if total else None,
        "erros_silenciosos": contagem["errada_confiante"] / total if total else None,
        "exatidao_das_lidas": contagem["certa"] / lidas if lidas else None,
        "numero_certo": sum(1 for r in resultados if r["numero_certo"]) / len(resultados) if resultados else None,
        "nome_certo": sum(1 for r in resultados if r["nome_certo"]) / len(resultados) if resultados else None,
        "avisos": avisos,
        "segundos_por_folha": segundos / len(resultados) if resultados else None,
    }


def ler_gabarito(caminho):
    esperados = {}
    with open(caminho, encoding="utf-8", newline="") as f:
        for linha in csv.DictReader(f):
            objectivas = {int(k[1:]): (v or "").strip().upper() for k, v in linha.items() if k and k[0] == "q" and k[1:].isdigit()}
            numero = (linha.get("numero") or "").strip()
            esperados[linha["ficheiro"].strip()] = {"numero": int(numero) if numero else None, "nome": linha.get("nome", ""), "objectivas": objectivas}
    return esperados


def pedir_ocr(url, api_key, ficheiro, estrutura, tempo_limite=300, tentativas=2):
    """Pede ao OCR; um servidor sobrecarregado pode repor a ligação, por isso tenta de novo antes de dar a folha como falhada."""
    for tentativa in range(1, tentativas + 1):
        try:
            return _pedir_uma_vez(url, api_key, ficheiro, estrutura, tempo_limite)
        except (urllib.error.URLError, OSError):
            if tentativa == tentativas:
                raise
            time.sleep(5)


def _pedir_uma_vez(url, api_key, ficheiro, estrutura, tempo_limite):
    limite = uuid.uuid4().hex
    tipo = mimetypes.guess_type(ficheiro.name)[0] or "application/octet-stream"
    corpo = (
        f'--{limite}\r\nContent-Disposition: form-data; name="context"\r\n\r\n{json.dumps(estrutura)}\r\n'
        f'--{limite}\r\nContent-Disposition: form-data; name="images"; filename="{ficheiro.name}"\r\nContent-Type: {tipo}\r\n\r\n'
    ).encode() + ficheiro.read_bytes() + f"\r\n--{limite}--\r\n".encode()
    cabecalhos = {"Content-Type": f"multipart/form-data; boundary={limite}"}
    if api_key:
        cabecalhos["X-OCR-API-Key"] = api_key
    pedido = urllib.request.Request(url.rstrip("/") + "/ocr/v1/answer-sheets/extract", data=corpo, headers=cabecalhos)
    with urllib.request.urlopen(pedido, timeout=tempo_limite) as r:
        return json.load(r)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--pasta", required=True, type=Path)
    p.add_argument("--gabarito", required=True, type=Path)
    p.add_argument("--estrutura", required=True, type=Path)
    p.add_argument("--url", default=os.environ.get("OCR_URL", "http://localhost:8000"))
    p.add_argument("--api-key", default=os.environ.get("OCR_API_KEY", ""))
    p.add_argument("--confianca-alta", type=float, default=0.8)
    p.add_argument("--min-objectivas", type=float, default=0.95, help="exatidão mínima nas objetivas (omissão 0,95)")
    p.add_argument("--max-erros-silenciosos", type=float, default=0.01, help="máximo de erros confiantes (omissão 0,01)")
    p.add_argument("--json", type=Path, help="grava o relatório completo neste ficheiro")
    a = p.parse_args(argv)

    estrutura = json.loads(a.estrutura.read_text(encoding="utf-8"))
    esperados = ler_gabarito(a.gabarito)
    resultados, avisos, ficheiros_sem_gabarito, erros_de_rede = [], {}, [], []
    inicio = time.time()
    for f in sorted(x for x in a.pasta.iterdir() if x.is_file() and x.suffix.lower() in (".jpg", ".jpeg", ".png", ".pdf")):
        if f.name not in esperados:
            ficheiros_sem_gabarito.append(f.name)
            continue
        try:
            resposta = pedir_ocr(a.url, a.api_key, f, estrutura)
        except (urllib.error.URLError, OSError) as e:  # ConnectionResetError, timeouts e afins
            erros_de_rede.append(f"{f.name}: {e}")
            continue
        for w in resposta.get("warnings", []):
            avisos[w["code"]] = avisos.get(w["code"], 0) + 1
        folhas = resposta.get("sheets") or []
        if not folhas:
            resultados.append({"ficheiro": f.name, "numero_certo": False, "nome_certo": False,
                               "objectivas": [{"questao": n, "esperada": c or None, "lida": None, "confianca": 0.0, "estado": "nao_lida" if c else "certa"}
                                              for n, c in sorted(esperados[f.name]["objectivas"].items())]})
            continue
        r = avaliar_folha(folhas[0], esperados[f.name], a.confianca_alta)
        r["ficheiro"] = f.name
        resultados.append(r)

    resumo = resumir(resultados, avisos, time.time() - inicio)
    pct = lambda x: "n/d" if x is None else f"{100 * x:.1f}%"
    print(f"Folhas medidas: {resumo['folhas']}  ({resumo['objectivas']} respostas objetivas)")
    print(f"  Certas:               {resumo['certa']}  ({pct(resumo['exatidao_objectivas'])})")
    print(f"  Não lidas:            {resumo['nao_lida']}  (a professora transcreve; não é erro silencioso)")
    print(f"  Erradas e duvidosas:  {resumo['errada_duvidosa']}  (a validação humana apanha)")
    print(f"  Erradas e CONFIANTES: {resumo['errada_confiante']}  ({pct(resumo['erros_silenciosos'])})  <- a métrica que decide")
    print(f"  Exatidão das lidas:   {pct(resumo['exatidao_das_lidas'])}")
    print(f"  Nº de aluno certo:    {pct(resumo['numero_certo'])}   Nome certo: {pct(resumo['nome_certo'])}")
    print(f"  Avisos:               {resumo['avisos'] or '-'}   Tempo médio: {resumo['segundos_por_folha']:.1f} s/folha" if resultados else "  Sem folhas medidas.")
    for r in resultados:
        for o in r["objectivas"]:
            if o["estado"] in ("errada_confiante", "errada_duvidosa", "nao_lida"):
                print(f"    {r['ficheiro']} q{o['questao']}: esperada {o['esperada']}, lida {o['lida']} ({o['confianca']:.2f}) [{o['estado']}]")
    if ficheiros_sem_gabarito:
        print(f"  Ficheiros sem gabarito (ignorados): {', '.join(ficheiros_sem_gabarito)}")
    if erros_de_rede:
        print("  Falhas do OCR:\n    " + "\n    ".join(erros_de_rede))
    if a.json:
        a.json.write_text(json.dumps({"resumo": resumo, "folhas": resultados}, ensure_ascii=False, indent=2), encoding="utf-8")

    cumpre = (resumo["exatidao_objectivas"] or 0) >= a.min_objectivas and (resumo["erros_silenciosos"] if resumo["erros_silenciosos"] is not None else 1) <= a.max_erros_silenciosos and not erros_de_rede
    print("RESULTADO: " + ("cumpre os limiares" if cumpre else "NÃO cumpre os limiares") + f" (exatidão >= {a.min_objectivas:.0%}, erros silenciosos <= {a.max_erros_silenciosos:.0%})")
    return 0 if cumpre else 1


if __name__ == "__main__":
    sys.exit(main())

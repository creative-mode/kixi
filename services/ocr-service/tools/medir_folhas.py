#!/usr/bin/env python3
"""Mede a exatidão do OCR de folhas de resposta numa pasta de fotos com gabarito (kixi#499).

Uso:
  python tools/medir_folhas.py --pasta fotos/ --gabarito gabarito.csv --estrutura estrutura.json \\
      --url http://localhost:8000 --api-key "$OCR_API_KEY" [--json relatorio.json]

  --pasta       fotos ou digitalizações, uma folha de aluno por ficheiro (jpg, png ou pdf)
  --gabarito    CSV UTF-8 (com ou sem BOM; separador `,` ou `;`): ficheiro,numero,nome,q1,q2,... (as colunas qN,
                maiúsculas ou minúsculas, são as respostas objetivas certas do que o aluno ESCREVEU, não as
                respostas corretas da prova; vazio = o aluno deixou em branco)
  --estrutura   JSON do contexto do endpoint: {"questions": [{"id": "1", "number": 1, "type": "multiple_choice",
                "options": ["A","B","C","D"]}, ...]} (o mesmo que o Kixi envia)

A métrica que decide é o erro silencioso: uma resposta lida ERRADA com confiança alta (>= --confianca-alta).
Uma leitura errada com confiança baixa é apanhada pela validação da professora; uma errada e confiante, não.

Só usa a biblioteca standard. Sai com código 1 se não cumprir a definição de pronto: --min-objectivas,
--max-erros-silenciosos, --min-folhas, --min-numero, folhas do gabarito sem foto ou falhas do OCR. O relatório
mostra o limite superior (95 %) do erro silencioso; com --rigoroso é esse limite, e não a taxa observada, que decide.
"""
import argparse
import csv
import datetime
import json
import math
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


def _simbolo(token):
    return str(int(token)) if token.isdigit() else token  # "07" e "7" são o mesmo número


def nome_certo(lido, esperado):
    """O nome conta como certo se o primeiro e o último nome coincidem (a letra de um nome do meio varia muito)."""
    a, b = [_simbolo(t) for t in _normal(lido).split()], [_simbolo(t) for t in _normal(esperado).split()]
    return bool(a) and bool(b) and a[0] == b[0] and a[-1] == b[-1]


def _inteiro(valor):
    try:
        return int(str(valor).strip())
    except (TypeError, ValueError):
        return None


def _letra(valor):
    return None if valor is None else (str(valor).strip().upper() or None)


def limite_superior(erros, total, z=1.645):
    """Limite superior (95 %, unilateral) de Wilson da taxa de erro: com poucas respostas, 0 erros não prova ≤ 1 %."""
    if not total:
        return None
    p = erros / total
    return (p + z * z / (2 * total) + z * math.sqrt(p * (1 - p) / total + z * z / (4 * total * total))) / (1 + z * z / total)


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
        "numero_certo": esperado.get("numero") is not None and _inteiro(numero) == esperado["numero"],
        "nome_certo": nome_certo(nome, esperado.get("nome", "")),
        "objectivas": [],
    }
    lidas = {a["number"]: a for a in folha.get("answers", [])}
    for n, certa in sorted(esperado["objectivas"].items()):
        lida = (lidas.get(n) or {}).get("selected") or {}
        valor, confianca = _letra(lida.get("value")), float(lida.get("confidence") or 0.0)
        esperada = certa or None  # em branco = não há resposta para ler
        if valor is None:
            estado = "nao_lida" if esperada else "em_branco"  # deixar em branco e não ler nada é o certo, mas não conta como leitura
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
    em_branco = sum(1 for o in obj if o["estado"] == "em_branco")
    total -= em_branco  # branco esperado e nada lido não é leitura: não sobe a exatidão nem o denominador
    lidas = total - contagem["nao_lida"]
    return {
        "folhas": len(resultados),
        "objectivas": total,
        "em_branco": em_branco,
        **contagem,
        "exatidao_objectivas": contagem["certa"] / total if total else None,
        "erros_silenciosos": contagem["errada_confiante"] / total if total else None,
        "erros_silenciosos_limite_superior": limite_superior(contagem["errada_confiante"], total),
        "exatidao_das_lidas": contagem["certa"] / lidas if lidas else None,
        "numero_certo": sum(1 for r in resultados if r["numero_certo"]) / len(resultados) if resultados else None,
        "nome_certo": sum(1 for r in resultados if r["nome_certo"]) / len(resultados) if resultados else None,
        "avisos": avisos,
        "segundos_por_folha": segundos / len(resultados) if resultados else None,
    }


def ler_gabarito(caminho):
    """Lê o gabarito. Aceita BOM (Excel "CSV UTF-8"), `,` ou `;` e colunas qN/QN; falha com a linha e o motivo."""
    texto = Path(caminho).read_text(encoding="utf-8-sig")
    primeira = texto.splitlines()[0] if texto.strip() else ""
    delimitador = ";" if primeira.count(";") > primeira.count(",") else ","
    esperados = {}
    leitor = csv.DictReader(texto.splitlines(), delimiter=delimitador)
    for n_linha, bruta in enumerate(leitor, start=2):
        linha = {(k or "").strip().lower(): v for k, v in bruta.items()}
        ficheiro = (linha.get("ficheiro") or "").strip()
        if not ficheiro:
            raise ValueError(f"gabarito, linha {n_linha}: falta o nome do ficheiro (coluna 'ficheiro')")
        objectivas = {int(k[1:]): (v or "").strip().upper() for k, v in linha.items() if k.startswith("q") and k[1:].isdigit()}
        if not objectivas:
            raise ValueError(f"gabarito, linha {n_linha}: nenhuma coluna q1, q2, ... (cabeçalho lido: {list(linha)})")
        numero = (linha.get("numero") or "").strip()
        if numero and _inteiro(numero) is None:
            raise ValueError(f"gabarito, linha {n_linha}: 'numero' tem de ser inteiro, veio {numero!r}")
        esperados[ficheiro] = {"numero": _inteiro(numero) if numero else None, "nome": (linha.get("nome") or "").strip(), "objectivas": objectivas}
    return esperados


class FalhaOcr(Exception):
    """O OCR respondeu com erro que repetir não resolve (credencial errada, pedido inválido)."""


def pedir_ocr(url, api_key, ficheiro, estrutura, tempo_limite=300, tentativas=2):
    """Pede ao OCR; um servidor sobrecarregado pode repor a ligação, por isso tenta de novo antes de dar a folha como falhada."""
    for tentativa in range(1, tentativas + 1):
        try:
            return _pedir_uma_vez(url, api_key, ficheiro, estrutura, tempo_limite)
        except urllib.error.HTTPError as e:
            if e.code < 500:
                raise FalhaOcr(f"HTTP {e.code} {e.reason}") from e  # 401/403/422: repetir não ajuda
            if tentativa == tentativas:
                raise
            time.sleep(5)
        except (urllib.error.URLError, OSError):
            if tentativa == tentativas:
                raise
            time.sleep(5)


def _pedir_uma_vez(url, api_key, ficheiro, estrutura, tempo_limite):
    limite = uuid.uuid4().hex
    tipo = mimetypes.guess_type(ficheiro.name)[0] or "application/octet-stream"
    nome = ficheiro.name.replace("\\", "_").replace('"', "%22").replace("\r", "").replace("\n", "")  # não parte o cabeçalho multipart
    corpo = (
        f'--{limite}\r\nContent-Disposition: form-data; name="context"\r\n\r\n{json.dumps(estrutura)}\r\n'
        f'--{limite}\r\nContent-Disposition: form-data; name="images"; filename="{nome}"\r\nContent-Type: {tipo}\r\n\r\n'
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
    p.add_argument("--min-folhas", type=int, default=30, help="folhas mínimas medidas (omissão 30)")
    p.add_argument("--min-numero", type=float, default=0.95, help="exatidão mínima do nº de aluno (omissão 0,95)")
    p.add_argument("--rigoroso", action="store_true", help="decide pelo limite superior do erro silencioso, não pela taxa observada")
    p.add_argument("--json", type=Path, help="grava o relatório completo neste ficheiro")
    a = p.parse_args(argv)

    estrutura = json.loads(a.estrutura.read_text(encoding="utf-8"))
    try:
        esperados = ler_gabarito(a.gabarito)
    except (ValueError, OSError) as e:
        print(f"Gabarito inválido: {e}", file=sys.stderr)
        return 2
    perguntas = {_inteiro(q.get("number")) for q in estrutura.get("questions", [])}
    for ficheiro, esperado in esperados.items():
        em_falta = sorted(set(esperado["objectivas"]) - perguntas)
        if em_falta:
            print(f"Gabarito: {ficheiro} tem q{em_falta} que não existem em --estrutura", file=sys.stderr)
            return 2
    resultados, avisos, ficheiros_sem_gabarito, erros_de_rede = [], {}, [], []
    medidos = set()
    inicio = time.time()
    for f in sorted(x for x in a.pasta.iterdir() if x.is_file() and x.suffix.lower() in (".jpg", ".jpeg", ".png", ".pdf")):
        if f.name not in esperados:
            ficheiros_sem_gabarito.append(f.name)
            continue
        medidos.add(f.name)
        try:
            resposta = pedir_ocr(a.url, a.api_key, f, estrutura)
        except (FalhaOcr, urllib.error.URLError, OSError, ValueError) as e:  # rede, HTTP 4xx, JSON que não é JSON
            erros_de_rede.append(f"{f.name}: {e}")
            continue
        for w in resposta.get("warnings", []):
            codigo = w.get("code", "?")
            avisos[codigo] = avisos.get(codigo, 0) + 1
        folhas = resposta.get("sheets") or []
        if len(folhas) > 1:
            avisos["VARIAS_FOLHAS_NO_FICHEIRO"] = avisos.get("VARIAS_FOLHAS_NO_FICHEIRO", 0) + 1  # só a 1.ª é medida
        if not folhas:
            resultados.append({"ficheiro": f.name, "numero_certo": False, "nome_certo": False,
                               "objectivas": [{"questao": n, "esperada": c or None, "lida": None, "confianca": 0.0, "estado": "nao_lida" if c else "em_branco"}
                                              for n, c in sorted(esperados[f.name]["objectivas"].items())]})
            continue
        r = avaliar_folha(folhas[0], esperados[f.name], a.confianca_alta)
        r["ficheiro"] = f.name
        resultados.append(r)
    sem_foto = sorted(set(esperados) - medidos)

    resumo = resumir(resultados, avisos, time.time() - inicio)
    pct = lambda x: "n/d" if x is None else f"{100 * x:.1f}%"
    print(f"Folhas medidas: {resumo['folhas']}  ({resumo['objectivas']} respostas objetivas)")
    if resumo["em_branco"]:
        print(f"  Em branco (certo):    {resumo['em_branco']}  (deixadas em branco e não lidas; fora das percentagens)")
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
    if sem_foto:
        print(f"  Linhas do gabarito sem foto na pasta: {', '.join(sem_foto)}")
    if erros_de_rede:
        print("  Falhas do OCR:\n    " + "\n    ".join(erros_de_rede))
    limite = resumo["erros_silenciosos_limite_superior"]
    if limite is not None:
        print(f"  Erro silencioso: limite superior a 95 % = {pct(limite)} (a taxa observada sozinha não prova o alvo com tão poucas respostas)")
    if a.json:
        meta = {"data": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"), "url": a.url, "confianca_alta": a.confianca_alta,
                "ficheiros": sorted(medidos), "sem_foto": sem_foto, "sem_gabarito": ficheiros_sem_gabarito, "falhas": erros_de_rede}
        a.json.write_text(json.dumps({"meta": meta, "resumo": resumo, "folhas": resultados}, ensure_ascii=False, indent=2), encoding="utf-8")

    silencioso = limite if a.rigoroso else resumo["erros_silenciosos"]
    falhas = []
    if (resumo["exatidao_objectivas"] or 0) < a.min_objectivas:
        falhas.append(f"exatidão < {a.min_objectivas:.0%}")
    if silencioso is None or silencioso > a.max_erros_silenciosos:
        falhas.append(f"erros silenciosos > {a.max_erros_silenciosos:.0%}" + (" (limite superior)" if a.rigoroso else ""))
    if resumo["folhas"] < a.min_folhas:
        falhas.append(f"menos de {a.min_folhas} folhas medidas")
    if (resumo["numero_certo"] or 0) < a.min_numero:
        falhas.append(f"nº de aluno < {a.min_numero:.0%}")
    if sem_foto:
        falhas.append(f"{len(sem_foto)} folha(s) do gabarito sem foto")
    if erros_de_rede:
        falhas.append(f"{len(erros_de_rede)} falha(s) do OCR")
    cumpre = not falhas
    print("RESULTADO: " + ("cumpre a definição de pronto" if cumpre else "NÃO cumpre: " + "; ".join(falhas)))
    return 0 if cumpre else 1


if __name__ == "__main__":
    sys.exit(main())

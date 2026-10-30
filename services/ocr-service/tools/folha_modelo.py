#!/usr/bin/env python3
"""Gera a folha de resposta modelo (PDF A4) com a convenção que o OCR do Kixi lê (kixi#499).

  python tools/folha_modelo.py --objectivas 8 --abertas 2 -o folha-modelo.pdf

O cabeçalho tem «Nº» e «Nome:» e cada resposta começa pelo número da questão seguido de «)». Imprima uma por aluno; o aluno escreve a
letra (ou V/F) depois do «)» e o texto das dissertativas nas linhas. Usa Pillow (já no serviço de OCR). Sem fonte TrueType
no sistema, sai em ASCII (use --fonte com um .ttf para ter os acentos).
"""
import argparse
import os

from PIL import Image, ImageDraw, ImageFont

FONTES = (
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/dejavu/DejaVuSans.ttf",
    "/Library/Fonts/Arial.ttf",
    "C:\\Windows\\Fonts\\arial.ttf",
)


def fonte(tamanho, caminho=None):
    """TrueType (com acentos) se houver; senão a embutida do Pillow, que não os tem: nesse caso o texto sai em ASCII."""
    for c in ([caminho] if caminho else []) + list(FONTES):
        if c and os.path.exists(c):
            return ImageFont.truetype(c, tamanho), True
    return ImageFont.load_default(tamanho), False


def gerar(objectivas, abertas, linhas_por_aberta, titulo, caminho_fonte=None):
    L, A = 1240, 1754  # A4 a 150 dpi
    img = Image.new("RGB", (L, A), "white")
    d = ImageDraw.Draw(img)
    f_titulo, acentos = fonte(34, caminho_fonte)
    f_rotulo, _ = fonte(40, caminho_fonte)
    f_pequena, _ = fonte(24, caminho_fonte)
    numero = "N\u00ba" if acentos else "N."
    d.text((80, 50), titulo, font=f_titulo, fill="black")
    d.text((80, 130), numero, font=f_rotulo, fill="black")
    d.rectangle((150, 120, 290, 185), outline="black", width=3)
    d.text((330, 130), "Nome:", font=f_rotulo, fill="black")
    d.line((470, 180, L - 80, 180), fill="black", width=3)
    instrucao = ("Escreva em letra legivel, de caneta azul ou preta, depois do \")\" de cada questao.", "Nao escreva fora das linhas.")
    if acentos:
        instrucao = ("Escreva em letra leg\u00edvel, de caneta azul ou preta, depois do \")\" de cada quest\u00e3o.", "N\u00e3o escreva fora das linhas.")
    for i, linha in enumerate(instrucao):
        d.text((80, 215 + i * 34), linha, font=f_pequena, fill=(90, 90, 90))
    y = 310
    colunas = 4
    for i in range(objectivas):
        x = 80 + (i % colunas) * 280
        if i % colunas == 0 and i:
            y += 110
        d.text((x, y), f"{i + 1})", font=f_rotulo, fill="black")
        d.line((x + 70, y + 55, x + 230, y + 55), fill="black", width=3)
    y += 150
    for j in range(abertas):
        d.text((80, y), f"{objectivas + j + 1})", font=f_rotulo, fill="black")
        for k in range(linhas_por_aberta):
            d.line((150, y + 55 + k * 70, L - 80, y + 55 + k * 70), fill=(150, 150, 150), width=2)
        y += 70 * linhas_por_aberta + 90
    return img


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--objectivas", type=int, default=8)
    p.add_argument("--abertas", type=int, default=2)
    p.add_argument("--linhas", type=int, default=5, help="linhas por resposta dissertativa")
    p.add_argument("--titulo", default="Folha de resposta")
    p.add_argument("--fonte", help="ficheiro .ttf com acentos (omissão: DejaVu Sans, Arial)")
    p.add_argument("-o", "--saida", default="folha-modelo.pdf")
    a = p.parse_args()
    gerar(a.objectivas, a.abertas, a.linhas, a.titulo, a.fonte).save(a.saida, "PDF", resolution=150.0)
    print(f"gravada {a.saida}")


if __name__ == "__main__":
    main()

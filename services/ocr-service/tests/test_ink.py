"""Pen against print, by colour."""

import numpy as np

from app.ocr.ink import blue_ink_share, is_handwriting


def page(width=300, height=80):
    return np.full((height, width, 3), 235, dtype=np.uint8)  # BGR, paper


def stroke(image, colour, y=30):
    image[y:y + 3, 20:200] = colour
    image[y + 14:y + 17, 20:150] = colour
    return image


def test_a_blue_pen_line_is_handwriting_even_when_the_pen_is_dull():
    # a phone photo of a blue ballpoint: dark, barely saturated, but bluer than red
    line = stroke(page(), (110, 100, 96))
    assert is_handwriting(line, (10, 10, 260, 70))
    assert blue_ink_share(line, (10, 10, 260, 70)) > 0.9


def test_black_print_and_a_warm_cast_are_not():
    assert not is_handwriting(stroke(page(), (40, 40, 40)), (10, 10, 260, 70))
    assert not is_handwriting(stroke(page(), (30, 40, 60)), (10, 10, 260, 70))  # slightly warm ink


def test_empty_paper_and_tiny_boxes_are_not_handwriting():
    assert not is_handwriting(page(), (10, 10, 260, 70))
    assert not is_handwriting(stroke(page(), (110, 100, 96)), (10, 10, 12, 12))

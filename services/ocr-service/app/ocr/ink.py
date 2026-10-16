"""Tells print from pen by the colour of the ink.

An exam is printed in black; what a student or a teacher writes on it is mostly in a blue ballpoint. The text engine
reads both the same, so the working of a limit, a margin note or a total scribbled beside a question comes out as
"text" and ends up in the question. The colour is a far better witness than the shape of the strokes.

On a phone photo the pen is barely saturated (hue ~120 but saturation ~20 of 255), so saturation and hue thresholds
miss it. What separates it is the sign of blue minus red on the darkest strokes: print is slightly warm (blue below red),
the pen slightly cool. Measured on a photographed maths exam: every printed line scored 0.0 to 0.1 and the handwriting
0.35 to 1.0.
"""

from typing import Tuple

import numpy as np

# The darkest part of the box is the ink: its share is taken, so the paper and the light do not matter.
INK_PERCENTILE = 12
MIN_INK_PIXELS = 15
# How much darker than the paper a pixel must be to be ink at all.
PAPER_MARGIN = 25
# How much bluer than red a stroke pixel must be to count as pen.
BLUE_OVER_RED = 4
# A box is handwriting when at least this share of its ink leans blue.
HANDWRITING_SHARE = 0.34


def blue_ink_share(image: np.ndarray, bbox: Tuple[int, int, int, int]) -> float:
    """The share of the darkest pixels inside ``bbox`` (BGR image) that lean blue; 0 when there is too little ink."""
    height, width = image.shape[:2]
    x1, y1, x2, y2 = (int(v) for v in bbox)
    x1, y1, x2, y2 = max(0, x1), max(0, y1), min(width, x2), min(height, y2)
    if x2 - x1 < 3 or y2 - y1 < 3 or image.ndim != 3 or image.shape[2] != 3:
        return 0.0
    crop = image[y1:y2, x1:x2].astype(np.int16)
    gray = crop.mean(axis=2)
    # The darkest part, but never paper: a box with little ink would otherwise count its own background as strokes.
    ink = gray <= min(np.percentile(gray, INK_PERCENTILE), np.median(gray) - PAPER_MARGIN)
    if int(ink.sum()) < MIN_INK_PIXELS:
        return 0.0
    lean = (crop[..., 0] - crop[..., 2])[ink]
    return float((lean >= BLUE_OVER_RED).mean())


def is_handwriting(image: np.ndarray, bbox: Tuple[int, int, int, int]) -> bool:
    return blue_ink_share(image, bbox) >= HANDWRITING_SHARE

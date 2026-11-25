#!/usr/bin/env python3
"""Genere les icones de l'app kiosque (grille de touches, fond sombre)."""

import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.normpath(os.path.join(HERE, "..", "res"))

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

COLORS = ["#7c5cff", "#22d3ee", "#facc15", "#34d399", "#f472b6", "#60a5fa"]
BACKGROUND = "#121212"


def draw(size):
    img = Image.new("RGBA", (size, size), BACKGROUND)
    painter = ImageDraw.Draw(img)
    pad = round(size * 0.15)
    gap = round(size * 0.055)
    cols, rows = 3, 2
    width = (size - 2 * pad - (cols - 1) * gap) // cols
    height = (size - 2 * pad - (rows - 1) * gap) // rows
    radius = max(2, round(size * 0.045))
    for row in range(rows):
        for col in range(cols):
            x = pad + col * (width + gap)
            y = pad + row * (height + gap)
            painter.rounded_rectangle([x, y, x + width, y + height],
                                      radius=radius,
                                      fill=COLORS[(row * cols + col) % len(COLORS)])
    return img


def main():
    for folder, size in DENSITIES.items():
        target = os.path.join(RES, folder)
        os.makedirs(target, exist_ok=True)
        icon = draw(size)
        icon.save(os.path.join(target, "ic_launcher.png"))
        print(f"{folder}/ic_launcher.png ({size}px)")


if __name__ == "__main__":
    main()

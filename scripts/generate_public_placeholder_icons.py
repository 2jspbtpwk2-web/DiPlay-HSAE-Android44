#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Create two original PNG resources using only Python's standard library.

No image, font, network, metadata or third-party package is read. These raw
resources must be PNG bytes: the Android host sends/decodes them as images,
whereas its normal drawable and launcher resources use geometric XML.
"""
from pathlib import Path
import binascii
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
SIZE = 256
SCALE = SIZE / 64
BACKGROUND = (20, 34, 52, 255)
FOREGROUND = (244, 247, 251, 255)
ACCENT = (245, 174, 72, 255)


def rounded(x, y, left, top, right, bottom, radius):
    if not (left <= x < right and top <= y < bottom):
        return False
    cx = min(max(x, left + radius), right - radius)
    cy = min(max(y, top + radius), bottom - radius)
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2


def pixel(x, y):
    color = BACKGROUND if rounded(x, y, 0, 0, 64, 64, 12) else (0, 0, 0, 0)
    if rounded(x, y, 12, 14, 52, 42, 3):
        color = FOREGROUND
    if 17 <= x < 47 and 19 <= y < 37:
        color = BACKGROUND
    if (29 <= x < 35 and 42 <= y < 49) or (23 <= x < 41 and 49 <= y < 53):
        color = FOREGROUND
    if (x - 47) ** 2 + (y - 48) ** 2 <= 9:
        color = ACCENT
    return bytes(color)


def chunk(kind, data):
    return (struct.pack(">I", len(data)) + kind + data
            + struct.pack(">I", binascii.crc32(kind + data) & 0xFFFFFFFF))


def image():
    rows = bytearray()
    for y in range(SIZE):
        rows.append(0)  # PNG filter: none.
        for x in range(SIZE):
            rows.extend(pixel((x + 0.5) / SCALE, (y + 0.5) / SCALE))
    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))


def main():
    destination = ROOT / "common/src/main/res/raw"
    destination.mkdir(parents=True, exist_ok=True)
    data = image()
    for name in ("ic_car_home.png", "placeholder_icon.png"):
        (destination / name).write_bytes(data)
        print("Wrote original public placeholder:", (destination / name).relative_to(ROOT))


if __name__ == "__main__":
    main()

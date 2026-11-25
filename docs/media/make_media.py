#!/usr/bin/env python3
"""Renders the animated artwork the README uses.

GitHub does not animate SVG inside a markdown image, so every moving piece of the README is a
GIF built here from plain drawing code: no screenshots to retake, no binary sources to keep in
sync with the app.

    python3 docs/media/make_media.py

Fonts are downloaded once into ``docs/media/fonts/`` (git-ignored) on first run; the script needs
Pillow and numpy.
"""

from __future__ import annotations

import argparse
import math
import pathlib
import urllib.request

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = pathlib.Path(__file__).resolve().parent
FONT_DIR = HERE / "fonts"

FONT_URLS = {
    "Inter.ttf": "https://github.com/google/fonts/raw/main/ofl/inter/Inter%5Bopsz,wght%5D.ttf",
    "JetBrainsMono-Regular.ttf":
        "https://github.com/JetBrains/JetBrainsMono/raw/master/fonts/ttf/JetBrainsMono-Regular.ttf",
    "JetBrainsMono-Bold.ttf":
        "https://github.com/JetBrains/JetBrainsMono/raw/master/fonts/ttf/JetBrainsMono-Bold.ttf",
}

INK = (5, 7, 14)
INK_2 = (10, 17, 32)
LINE = (30, 41, 59)
TEXT = (226, 232, 240)
MUTED = (148, 163, 184)
ACCENT = (56, 189, 248)
GREEN = (52, 211, 153)
VIOLET = (139, 92, 246)


def font(name: str, size: int, weight: str | None = None) -> ImageFont.FreeTypeFont:
    face = ImageFont.truetype(str(FONT_DIR / name), size)
    if weight:
        try:
            face.set_variation_by_name(weight)
        except Exception:  # noqa: BLE001 - a static face simply has no axes
            pass
    return face


def ensure_fonts() -> None:
    FONT_DIR.mkdir(parents=True, exist_ok=True)
    for name, url in FONT_URLS.items():
        path = FONT_DIR / name
        if path.exists():
            continue
        print(f"  font   {name}")
        urllib.request.urlretrieve(url, path)


def vgradient(size: tuple[int, int], top: tuple[int, int, int],
              bottom: tuple[int, int, int]) -> Image.Image:
    width, height = size
    ramp = np.linspace(0.0, 1.0, height, dtype=np.float32)[:, None]
    top_row = np.array(top, dtype=np.float32)
    bottom_row = np.array(bottom, dtype=np.float32)
    rows = top_row + (bottom_row - top_row) * ramp
    data = np.repeat(rows[:, None, :], width, axis=1).astype(np.uint8)
    return Image.fromarray(data, "RGB")


def radial(size: int, color: tuple[int, int, int], peak: int) -> Image.Image:
    coords = np.linspace(-1.0, 1.0, size, dtype=np.float32)
    x, y = np.meshgrid(coords, coords)
    distance = np.clip(np.sqrt(x * x + y * y), 0.0, 1.0)
    alpha = (peak * (1.0 - distance) ** 2.2).astype(np.uint8)
    layer = np.zeros((size, size, 4), dtype=np.uint8)
    layer[..., 0], layer[..., 1], layer[..., 2] = color
    layer[..., 3] = alpha
    return Image.fromarray(layer, "RGBA")


def glow(base: Image.Image, blob: Image.Image, center: tuple[int, int],
         scale: float, alpha: float, blur: float = 0.0) -> None:
    size = max(2, int(blob.width * scale))
    layer = blob.resize((size, size), Image.BICUBIC)
    if alpha < 1.0:
        layer.putalpha(layer.getchannel("A").point(lambda value: int(value * alpha)))
    if blur:
        layer = layer.filter(ImageFilter.GaussianBlur(blur))
    base.alpha_composite(layer, (int(center[0] - size / 2), int(center[1] - size / 2)))


def grid(base: Image.Image, step: int, alpha: int, offset: float = 0.0) -> None:
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    width, height = base.size
    for x in range(-step, width + step, step):
        draw.line([(x + offset, 0), (x + offset, height)], fill=(148, 163, 184, alpha))
    for y in range(-step, height + step, step):
        draw.line([(0, y + offset), (width, y + offset)], fill=(148, 163, 184, alpha))
    base.alpha_composite(layer)


def spaced(draw: ImageDraw.ImageDraw, xy: tuple[int, int], text: str,
           face: ImageFont.FreeTypeFont, fill: tuple[int, int, int], tracking: int) -> int:
    x, y = xy
    for char in text:
        draw.text((x, y), char, font=face, fill=fill)
        x += face.getlength(char) + tracking
    return x


def gradient_text(text: str, face: ImageFont.FreeTypeFont,
                  colors: tuple[tuple[int, int, int], tuple[int, int, int]]) -> Image.Image:
    left, top, right, bottom = face.getbbox(text, anchor="la")
    width, height = int(right - left) + 2, int(bottom - top) + 2
    mask = Image.new("L", (width, height), 0)
    ImageDraw.Draw(mask).text((-left, -top), text, font=face, fill=255)
    paint = vgradient((width, height), colors[0], colors[1]).convert("RGBA")
    paint.putalpha(mask)
    return paint


def pill(draw: ImageDraw.ImageDraw, xy: tuple[int, int], label: str,
         face: ImageFont.FreeTypeFont, fg: tuple[int, int, int]) -> int:
    x, y = xy
    padding = 14
    width = int(face.getlength(label)) + padding * 2
    height = 34
    draw.rounded_rectangle([x, y, x + width, y + height], radius=height // 2,
                           fill=(15, 23, 42, 190), outline=(45, 60, 84, 255), width=1)
    draw.text((x + padding, y + height / 2), label, font=face, fill=fg, anchor="lm")
    return width


def save(frames: list[Image.Image], name: str, duration: int, colors: int = 128) -> None:
    path = HERE / name
    quantised = [frame.convert("RGB").quantize(colors=colors, method=Image.MEDIANCUT,
                                               dither=Image.NONE) for frame in frames]
    quantised[0].save(path, save_all=True, append_images=quantised[1:], duration=duration,
                      loop=0, optimize=True, disposal=2)
    print(f"  write  {name} ({path.stat().st_size / 1024:.0f} KiB, {len(frames)} frames)")


# --------------------------------------------------------------------------- hero


def hero(frames: int = 30) -> list[Image.Image]:
    width, height = 1200, 380
    base = vgradient((width, height), (4, 6, 12), (9, 16, 30)).convert("RGBA")
    grid(base, 48, 7)

    teal = radial(360, (16, 185, 129), 120)
    blue = radial(420, (59, 130, 246), 120)
    violet = radial(300, VIOLET, 80)

    eyebrow = font("Inter.ttf", 17, "SemiBold")
    headline = font("Inter.ttf", 116, "ExtraBold")
    subtitle = font("Inter.ttf", 23)
    chip = font("Inter.ttf", 15, "Medium")

    word = gradient_text("OPX OSS", headline, ((255, 255, 255), (125, 211, 252)))

    images = []
    for index in range(frames):
        phase = index / frames
        frame = base.copy()
        glow(frame, teal, (150 + 40 * math.sin(phase * math.tau), 330), 2.4, 0.55)
        glow(frame, blue, (1010 + 30 * math.cos(phase * math.tau), 60), 2.6, 0.5)
        glow(frame, violet, (780, 330 + 20 * math.sin(phase * math.tau + 1)), 1.8, 0.35)
        grid(frame, 48, 7, offset=-phase * 48)

        draw = ImageDraw.Draw(frame)

        # Right-hand motif: three rings and a dot that orbits the innermost one.
        cx, cy = 985, 190
        for ring, radius in enumerate((150, 112, 74)):
            alpha = 70 - ring * 18
            draw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                         outline=(125, 211, 252, alpha), width=1)
        for tick in range(48):
            start = tick / 48 * math.tau + phase * math.tau
            inner, outer = 132, 138 + (6 if tick % 4 == 0 else 0)
            draw.line([
                (cx + inner * math.cos(start), cy + inner * math.sin(start)),
                (cx + outer * math.cos(start), cy + outer * math.sin(start)),
            ], fill=(56, 189, 248, 120 if tick % 4 == 0 else 45), width=1)
        orbit = phase * math.tau
        dot = (cx + 112 * math.cos(orbit), cy + 112 * math.sin(orbit))
        draw.ellipse([dot[0] - 13, dot[1] - 13, dot[0] + 13, dot[1] + 13],
                     fill=(56, 189, 248, 40))
        draw.ellipse([dot[0] - 5, dot[1] - 5, dot[0] + 5, dot[1] + 5], fill=ACCENT)
        # Wi-Fi arcs in the middle of the rings.
        for radius, alpha in ((46, 255), (30, 210), (15, 170)):
            draw.arc([cx - radius, cy - radius + 24, cx + radius, cy + radius + 24],
                     start=210, end=330, fill=(226, 232, 240, alpha), width=5)
        draw.ellipse([cx - 5, cy + 22, cx + 5, cy + 32], fill=TEXT)

        # Left-hand block: eyebrow, wordmark, subtitle, chips, accent rule.
        spaced(draw, (78, 92), "NATIVE ANDROID WI-FI ENGINE", eyebrow, (56, 189, 248), 4)
        frame.alpha_composite(word, (74, 118))
        draw.text((80, 252), "One APK · self-installing runtime · USB host bridge",
                  font=subtitle, fill=MUTED)
        x = 80
        for label, colour in (("Material 3", TEXT), ("python · libusb · wifi4", TEXT),
                              ("GPL-3.0", GREEN)):
            x += pill(draw, (x, 296), label, chip, colour) + 10

        # A light band sweeps the wordmark once per loop, then the accent rule draws itself.
        sweep = (phase * 1.6 - 0.3) * width
        band = Image.new("RGBA", (width, height), (0, 0, 0, 0))
        band_draw = ImageDraw.Draw(band)
        band_draw.polygon([(sweep, 0), (sweep + 90, 0), (sweep + 210, height),
                           (sweep + 120, height)], fill=(186, 230, 253, 26))
        frame.alpha_composite(band.filter(ImageFilter.GaussianBlur(18)))

        rule = 80 + int(420 * min(1.0, phase * 1.5))
        draw.line([(80, 356), (rule, 356)], fill=(56, 189, 248, 220), width=3)
        if index % 12 < 7:
            draw.rectangle([rule + 6, 344, rule + 18, 356], fill=(226, 232, 240, 230))

        images.append(frame)
    return images


# ----------------------------------------------------------------------- pipeline


BOXES = (
    ("UI", "Material 3 screens"),
    ("WifiBridge", "JSON lines"),
    ("opx-engine", "headless wifi4"),
    ("opx-usb", "runtime shim"),
    ("UsbHostBridge", "SCM_RIGHTS fd"),
    ("USB Wi-Fi", "OTG adapter"),
)


def fit(name: str, text: str, size: int, max_width: float, weight: str | None = None):
    """The largest face at or below ``size`` whose ``text`` still fits ``max_width``."""
    while size > 9:
        face = font(name, size, weight)
        if face.getlength(text) <= max_width:
            return face
        size -= 1
    return font(name, 9, weight)


def pipeline(frames: int = 44) -> list[Image.Image]:
    width, height = 1120, 320
    base = vgradient((width, height), (6, 9, 18), (11, 18, 32)).convert("RGBA")
    grid(base, 40, 6)
    halo = radial(520, (59, 130, 246), 90)

    title = font("Inter.ttf", 18, "SemiBold")
    caption = font("JetBrainsMono-Regular.ttf", 13)
    legend = font("JetBrainsMono-Regular.ttf", 14)

    margin, gap = 40, 20
    box_w = (width - margin * 2 - gap * (len(BOXES) - 1)) // len(BOXES)
    box_h = 96
    top = 104
    wire = top + box_h + 30
    centres = [margin + slot * (box_w + gap) + box_w / 2 for slot in range(len(BOXES))]
    labels = [fit("Inter.ttf", label, 17, box_w - 20, "Bold") for label, _ in BOXES]

    images = []
    for index in range(frames):
        frame = base.copy()
        glow(frame, halo, (width * 0.5, height * 0.34), 2.6,
             0.5 + 0.1 * math.sin(index / frames * math.tau))
        draw = ImageDraw.Draw(frame)
        draw.text((margin, 44), "HOW A SCAN REACHES THE ADAPTER", font=title, fill=(125, 211, 252))
        draw.text((margin, 70), "one process · one UID · no second app",
                  font=caption, fill=(100, 116, 139))

        # A pulse walks the wire under the row; the box above it lights as it passes.
        travel = (index / frames) * (len(BOXES) - 1 + 0.35)
        pulse_x = centres[0] + travel * (box_w + gap)

        draw.line([(centres[0], wire), (centres[-1], wire)], fill=(45, 58, 78, 255), width=2)
        for slot in range(len(BOXES) - 1):
            tip = centres[slot + 1] - 26
            draw.line([(tip - 8, wire), (tip, wire)], fill=(71, 85, 105, 255), width=2)
            draw.polygon([(tip, wire - 4), (tip + 5, wire), (tip, wire + 4)],
                         fill=(71, 85, 105, 255))

        for slot, (label, note) in enumerate(BOXES):
            active = max(0.0, 1.0 - abs(travel - slot) / 1.15)
            left = margin + slot * (box_w + gap)
            right = left + box_w
            draw.rounded_rectangle([left, top, right, top + box_h], radius=14,
                                   fill=(17, 33, 58, 240) if active > 0.5 else (12, 22, 40, 235),
                                   outline=(56, 189, 248, int(70 + 150 * active)), width=2)
            if active > 0.05:
                halo_box = Image.new("RGBA", (width, height), (0, 0, 0, 0))
                ImageDraw.Draw(halo_box).rounded_rectangle(
                    [left, top, right, top + box_h], radius=14,
                    outline=(56, 189, 248, int(120 * active)), width=6)
                frame.alpha_composite(halo_box.filter(ImageFilter.GaussianBlur(10)))
            draw.text((left + box_w / 2, top + 34), label, font=labels[slot],
                      fill=(255, 255, 255) if active > 0.5 else TEXT, anchor="mm")
            draw.text((left + box_w / 2, top + 64), note, font=caption, fill=MUTED, anchor="mm")
            draw.line([(left + box_w / 2, top + box_h), (left + box_w / 2, wire)],
                      fill=(45, 58, 78, 255), width=2)

        for trail in range(7):
            tx = pulse_x - trail * 8
            if not centres[0] <= tx <= centres[-1]:
                continue
            alpha = 200 - trail * 26
            draw.ellipse([tx - 5, wire - 5, tx + 5, wire + 5], fill=(125, 211, 252, alpha))

        step = min(len(BOXES) - 1, int(travel))
        draw.text((margin, height - 40), f"→ {BOXES[step][0]}  ·  {BOXES[step][1]}",
                  font=legend, fill=(125, 211, 252))
        draw.text((width - margin, height - 40), f"frame {index + 1:02d}/{frames:02d}",
                  font=legend, fill=(71, 85, 105), anchor="ra")
        images.append(frame)
    return images


# ----------------------------------------------------------------------- terminal


def colour_line(text: str) -> list[tuple[str, tuple[int, int, int]]]:
    """Splits one protocol line into coloured spans: keys cyan, strings green, rest grey."""
    spans: list[tuple[str, tuple[int, int, int]]] = []
    index = 0
    while index < len(text):
        char = text[index]
        if char == '"':
            end = text.find('"', index + 1)
            if end == -1:
                end = len(text) - 1
            token = text[index:end + 1]
            following = text[end + 1:end + 2]
            if following == ":":
                spans.append((token, (125, 211, 252)))
            else:
                spans.append((token, (52, 211, 153)))
            index = end + 1
            continue
        if char in "{}[],:":
            spans.append((char, (100, 116, 139)))
            index += 1
            continue
        spans.append((char, (203, 213, 225)))
        index += 1
    return spans


SCRIPT = (
    ("$ opx-engine --native", 1),
    ('{"cmd":"ping"}', 0),
    ('{"event":"ready","version":"0.6.0","native":true}', 2),
    ('{"cmd":"devices"}', 0),
    ('{"event":"devices","count":1,"chipset":"RTL8188FTV"}', 2),
    ('{"cmd":"scan","seconds":25}', 0),
    ('{"event":"network","ssid":"Arif","band":"2.4","signal":-59}', 2),
    ('{"event":"network","ssid":"Saju","band":"2.4","signal":-70}', 2),
    ('{"event":"scan_done","count":13}', 2),
)


def terminal(frames: int = 150) -> list[Image.Image]:
    width, height = 960, 430
    window = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(window)
    draw.rounded_rectangle([0, 0, width - 1, height - 1], radius=18, fill=(8, 12, 22, 255),
                           outline=(30, 41, 59, 255), width=1)
    draw.rounded_rectangle([0, 0, width - 1, 40], radius=18, fill=(14, 21, 36, 255))
    draw.rectangle([0, 20, width - 1, 40], fill=(14, 21, 36, 255))
    draw.line([(0, 40), (width, 40)], fill=(30, 41, 59, 255))
    for spot, colour in enumerate(((248, 113, 113), (251, 191, 36), (52, 211, 153))):
        cx = 24 + spot * 22
        draw.ellipse([cx - 6, 14, cx + 6, 26], fill=colour)
    draw.text((width / 2, 20), "opx-engine — line protocol", font=font("Inter.ttf", 14, "Medium"),
              fill=(148, 163, 184), anchor="mm")

    mono = font("JetBrainsMono-Regular.ttf", 15)
    body_top = 66
    line_height = 34
    ledger = [(line, style, colour_line(line)) for line, style in SCRIPT]
    total = sum(len(line) for line, _style in SCRIPT)

    images = []
    for index in range(frames):
        progress = min(1.0, (index + 1) / (frames * 0.85))
        budget = int(total * progress)
        canvas = window.copy()
        painter = ImageDraw.Draw(canvas)
        y = body_top
        used = 0
        caret_xy = (60, y)
        for line, style, spans in ledger:
            if used >= budget:
                break
            take = min(len(line), budget - used)
            used += len(line)
            x = 34
            if style == 1:
                painter.text((x, y), "$", font=mono, fill=GREEN)
                x += 18
            elif style == 2:
                painter.text((x - 18, y), "←", font=mono, fill=(56, 189, 248))
            drawn = 0
            for token, colour in spans:
                if drawn >= take:
                    break
                piece = token[:take - drawn]
                painter.text((x, y), piece, font=mono, fill=colour)
                x += mono.getlength(piece)
                drawn += len(piece)
            caret_xy = (x + 2, y)
            y += line_height
        if index % 14 < 8:
            painter.rectangle([caret_xy[0], caret_xy[1], caret_xy[0] + 9,
                               caret_xy[1] + 16], fill=(125, 211, 252, 220))
        if progress >= 1.0:
            painter.text((width - 30, height - 26), "ready · 13 networks · adapter attached",
                         font=font("Inter.ttf", 13, "Medium"), fill=(52, 211, 153), anchor="rm")
        images.append(canvas)
    return images


# --------------------------------------------------------------------------- scan


NETWORKS = (
    ("Md Sagor", "dc:62:79:b0:c9:4b · ch 2", -68, 3),
    ("Arif", "c0:3a:55:cb:9f:ac · ch 3", -59, 4),
    ("Saju", "50:0f:f5:fb:ab:e0 · ch 6", -66, 3),
    ("Jonota", "10:5a:95:b3:b2:44 · ch 4", -77, 2),
    ("Tenda_06DE78", "50:0f:f5:06:de:78 · ch 11", -86, 1),
)


def scan(frames: int = 72) -> list[Image.Image]:
    width, height = 460, 720
    background = vgradient((width, height), (7, 10, 20), (13, 20, 36)).convert("RGBA")
    halo = radial(460, (37, 99, 235), 90)

    title = font("Inter.ttf", 25, "Bold")
    label = font("Inter.ttf", 16, "SemiBold")
    note = font("JetBrainsMono-Regular.ttf", 11)
    chip = font("Inter.ttf", 11, "Medium")

    images = []
    for index in range(frames):
        frame = background.copy()
        glow(frame, halo, (width / 2, 120), 1.5, 0.55)
        draw = ImageDraw.Draw(frame)

        draw.rounded_rectangle([8, 8, width - 8, height - 8], radius=34,
                               fill=(9, 14, 26, 255), outline=(30, 41, 59, 255), width=1)
        draw.rounded_rectangle([width / 2 - 46, 18, width / 2 + 46, 28], radius=5,
                               fill=(24, 34, 52, 255))
        draw.text((34, 58), "Wi-Fi", font=title, fill=TEXT)
        pulse = 0.5 + 0.5 * math.sin(index / frames * math.tau * 2)
        draw.ellipse([width - 46, 66, width - 34, 78],
                     fill=(52, 211, 153, int(120 + 110 * pulse)))
        draw.text((width - 56, 64), "scanning", font=note, fill=(94, 234, 212), anchor="rt")

        # Tabs.
        for slot, (caption, active) in enumerate((("Networks", True), ("Clients", False),
                                                 ("Saved", False))):
            x = 34 + slot * 108
            draw.text((x, 104), caption, font=chip,
                      fill=(226, 232, 240) if active else (100, 116, 139))
            if active:
                draw.line([(x, 126), (x + 58, 126)], fill=(56, 189, 248), width=2)

        top = 150
        row_height = 96
        for slot, (ssid, meta, rssi, bars) in enumerate(NETWORKS):
            appear = (index / frames) * (len(NETWORKS) + 2) - slot
            if appear <= 0:
                continue
            fade = min(1.0, appear)
            offset = int(18 * (1 - fade))
            flicker = int(3 * math.sin(index / 6 + slot))
            box_top = top + slot * row_height + offset
            box_bottom = box_top + row_height - 12
            draw.rounded_rectangle([26, box_top, width - 26, box_bottom], radius=16,
                                   fill=(15, 23, 40, int(235 * fade)),
                                   outline=(34, 48, 70, int(255 * fade)), width=1)
            draw.text((44, box_top + 18), ssid, font=label,
                      fill=(226, 232, 240, int(255 * fade)))
            draw.text((44, box_top + 44), meta, font=note,
                      fill=(148, 163, 184, int(255 * fade)))
            badge = "WPA2"
            badge_w = int(chip.getlength(badge)) + 18
            draw.rounded_rectangle([44, box_top + 62, 44 + badge_w, box_top + 78], radius=8,
                                   fill=(12, 74, 110, int(220 * fade)))
            draw.text((44 + 9, box_top + 70), badge, font=chip,
                      fill=(125, 211, 252, int(255 * fade)), anchor="lm")
            draw.text((60 + badge_w, box_top + 70), "WPS", font=chip,
                      fill=(52, 211, 153, int(220 * fade)), anchor="lm")

            # Signal bars, each one a little taller than the last.
            for bar in range(4):
                bar_height = 6 + bar * 5
                bx = width - 78 + bar * 11
                by = box_top + 46 - bar_height
                filled = bar < bars
                colour = (56, 189, 248) if filled else (51, 65, 85)
                draw.rounded_rectangle([bx, by, bx + 6, by + bar_height], radius=2,
                                       fill=colour + (int(255 * fade),))
            draw.text((width - 52, box_top + 52), f"{rssi + flicker} dBm", font=note,
                      fill=(148, 163, 184, int(230 * fade)), anchor="rm")

        # Bottom status strip with a moving shimmer.
        shimmer = (index / frames) * 2 - 0.5
        draw.rounded_rectangle([26, height - 92, width - 26, height - 40], radius=14,
                               fill=(12, 20, 36, 255), outline=(30, 41, 59, 255), width=1)
        draw.text((44, height - 66), "13 networks · hopping 1–13", font=note,
                  fill=(148, 163, 184), anchor="lm")
        bar_width = 120
        draw.rounded_rectangle([width - 44 - bar_width, height - 71, width - 44, height - 61],
                               radius=5, fill=(24, 34, 52, 255))
        head = int(bar_width * min(1.0, index / (frames * 0.8)))
        draw.rounded_rectangle([width - 44 - bar_width, height - 71,
                                width - 44 - bar_width + head, height - 61],
                               radius=5, fill=(56, 189, 248, 230))
        if 0 <= shimmer <= 1:
            sx = 26 + shimmer * (width - 52)
            sheen = Image.new("RGBA", (width, height), (0, 0, 0, 0))
            ImageDraw.Draw(sheen).line([(sx, 0), (sx - 120, height)],
                                       fill=(186, 230, 253, 18), width=60)
            frame.alpha_composite(sheen.filter(ImageFilter.GaussianBlur(24)))

        images.append(frame)
    return images


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", choices=("hero", "pipeline", "terminal", "scan"))
    args = parser.parse_args()

    ensure_fonts()
    builders = {"hero": hero, "pipeline": pipeline, "terminal": terminal, "scan": scan}
    durations = {"hero": 70, "pipeline": 60, "terminal": 45, "scan": 60}
    palette = {"hero": 80, "pipeline": 72, "terminal": 64, "scan": 80}
    for key, builder in builders.items():
        if args.only and args.only != key:
            continue
        print(f"  build  {key}")
        save(builder(), f"{key}.gif", durations[key], palette[key])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

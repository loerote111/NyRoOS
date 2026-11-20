#!/usr/bin/env python3
"""Render the NyRoOS boot animation, wallpaper and emulator evidence."""

import argparse
from io import BytesIO
from math import cos, pi, sin
from pathlib import Path
from zipfile import ZIP_STORED, ZipFile, ZipInfo

from PIL import Image, ImageDraw, ImageFont


SIZE = (1080, 2400)
BACKGROUND = (17, 19, 26)
TEXT = (239, 237, 246)
FONT = Path(__file__).resolve().parents[1] / "custom/media/fonts/NotoSans-Bold.ttf"


def blend(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def icon():
    size = 600
    tile = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(tile)
    for y in range(size):
        color = blend((188, 167, 255), (99, 66, 214), y / (size - 1))
        draw.line((0, y, size, y), fill=color)
    mask = Image.new("L", tile.size)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), 192, fill=255)
    tile.putalpha(mask)
    draw = ImageDraw.Draw(tile)
    points = [(188, 408), (188, 192), (412, 408), (412, 192)]
    draw.line(points, fill=TEXT, width=46, joint="curve")
    for x, y in (points[0], points[-1]):
        draw.ellipse((x - 23, y - 23, x + 23, y + 23), fill=TEXT)
    return tile.resize((184, 184), Image.Resampling.LANCZOS)


def boot_frame(tile, progress, pulse):
    # Only the small animated area needs quantization. Every frame is full size.
    panel = Image.new("RGB", (600, 470), BACKGROUND)
    draw = ImageDraw.Draw(panel)
    y = 172 + round(20 * (1 - progress))
    glow = round(18 * sin(pulse * pi) ** 2)
    size = round(184 * (0.84 + 0.16 * progress))
    scaled = tile.resize((size, size), Image.Resampling.LANCZOS)
    if progress < 1:
        scaled.putalpha(scaled.getchannel("A").point(lambda value: round(value * progress)))
    panel.paste(scaled, (300 - size // 2, y - size // 2), scaled)
    draw.text((300, y + 152), "NyRoOS", font=ImageFont.truetype(FONT, 72),
              anchor="mm", fill=blend(BACKGROUND, TEXT, progress))
    draw.rounded_rectangle((281 - glow, y + 224, 319 + glow, y + 228), 2,
                           fill=blend(BACKGROUND, (178, 154, 255), progress * 0.8))
    indexed = panel.quantize(colors=224, dither=Image.Dither.NONE)
    background_index = indexed.getpixel((0, 0))
    frame = Image.new("P", SIZE, background_index)
    frame.putpalette(indexed.getpalette())
    frame.paste(indexed, (240, 916))
    result = BytesIO()
    frame.save(result, format="PNG", compress_level=2)
    return result.getvalue()


def render(directory):
    directory.mkdir(parents=True, exist_ok=True)
    tile = icon()
    with ZipFile(directory / "bootanimation.zip", "w", compression=ZIP_STORED) as archive:
        archive.writestr(ZipInfo("desc.txt"), "1080 2400 60\nc 1 0 part0 #11131a\np 0 0 part1 #11131a\n")
        for name, duration in (("part0", 108), ("part1", 60)):
            for number in range(duration):
                position = number / duration
                progress = 1 - max(0, 1 - position * 2.6) ** 3 if name == "part0" else 1
                archive.writestr(ZipInfo(f"{name}/{number:04d}.png"),
                                 boot_frame(tile, progress, position if name == "part1" else 0))

    # Low contrast at the top keeps launcher labels and status icons legible.
    wallpaper = Image.new("RGB", (270, 600))
    pixels = wallpaper.load()
    for y in range(600):
        for x in range(270):
            distance = (((x - 208) / 194) ** 2 + ((y - 473) / 250) ** 2) ** 0.5
            light = max(0, 1 - distance) ** 1.3
            band = 0.5 + 0.5 * cos(34 * distance + 0.9 * y / 600)
            pixels[x, y] = blend(BACKGROUND, (76, 49, 139), light * (0.48 + 0.32 * band))
    wallpaper.resize(SIZE, Image.Resampling.BICUBIC).save(directory / "nyroos_wallpaper.png")


def capture_boot_frame(candidate, destination):
    with Image.open(candidate) as image:
        if image.size != SIZE:
            return False
        image = image.convert("RGB")
        backdrop = image.getpixel((80, 900))
        violet = image.getpixel((480, 1070))
        if max(abs(a - b) for a, b in zip(backdrop, BACKGROUND)) > 7:
            return False
        if violet[2] < 156 or violet[2] - violet[1] < 42:
            return False
        image.save(destination)
        return True


def compose(directory):
    screens = (("Início", "home"), ("Painel rápido", "quick_settings"),
               ("Configurações", "settings"))
    sheet = Image.new("RGB", (1232, 938), BACKGROUND)
    draw = ImageDraw.Draw(sheet)
    for index, (label, filename) in enumerate(screens):
        x = 40 + index * 400
        with Image.open(directory / f"{filename}.png") as screenshot:
            image = screenshot.convert("RGB").resize((352, 782), Image.Resampling.LANCZOS)
        draw.rounded_rectangle((x - 2, 101, x + 353, 886), 25, fill=(48, 49, 63))
        mask = Image.new("L", image.size)
        ImageDraw.Draw(mask).rounded_rectangle((0, 0, 351, 781), 24, fill=255)
        sheet.paste(image, (x, 103), mask)
        draw.text((x, 61), label, fill=TEXT, font=ImageFont.truetype(FONT, 27), anchor="ls")
    sheet.save(directory.parent / "nyroos_theme_test.png")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    for command in ("render", "compose"):
        commands.add_parser(command).add_argument("directory", type=Path)
    frame = commands.add_parser("boot-frame")
    frame.add_argument("candidate", type=Path)
    frame.add_argument("destination", type=Path)
    args = parser.parse_args()
    if args.action == "render":
        render(args.directory)
    elif args.action == "compose":
        compose(args.directory)
    else:
        try:
            return 0 if capture_boot_frame(args.candidate, args.destination) else 1
        except (OSError, ValueError):
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

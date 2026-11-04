#!/usr/bin/env python3
"""Render listing artwork and web images from the app's demo screenshots."""
from pathlib import Path
from shutil import copyfile
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
IMAGES = ROOT / "fastlane/metadata/android/en-US/images"
WEB = ROOT / "site/assets"


def radiator(size=512):
    scale = size * 4 / 72
    image = Image.new("RGB", (size * 4, size * 4), "#252e2a")
    draw = ImageDraw.Draw(image)
    def box(coords):
        return tuple((v - 18) * scale for v in coords)
    for coordinates in ((34, 40, 42, 72), (46, 35, 54, 72), (58, 40, 66, 72)):
        draw.rounded_rectangle(box(coordinates), radius=4 * scale, fill="#f4b468")
    for coordinates in ((39, 60, 65, 65), (66, 49, 73, 54), (71, 43, 76, 50)):
        draw.rectangle(box(coordinates), fill="#f4b468")
    return image.resize((size, size), Image.Resampling.LANCZOS)


def banner(size):
    image = Image.new("RGB", size, "#f8f6f0")
    draw = ImageDraw.Draw(image)
    width, height = size
    unit = width / 1024
    icon = radiator(round(112 * unit))
    image.paste(icon, (round(62 * unit), round(58 * unit)))
    draw.text((60 * unit, 210 * unit), "EvoBuddy", font=ImageFont.load_default(size=round(92 * unit)), fill="#252e2a")
    draw.text((64 * unit, 322 * unit), "Room control for Evohome", font=ImageFont.load_default(size=round(30 * unit)), fill="#62665f")
    draw.text((64 * unit, 377 * unit), "Android · European TCC", font=ImageFont.load_default(size=round(21 * unit)), fill="#985014")
    screen = Image.open(IMAGES / "phoneScreenshots/1.png").convert("RGB")
    screen.thumbnail((round(280 * unit), round(490 * unit)), Image.Resampling.LANCZOS)
    image.paste(screen, (width - screen.width - round(45 * unit), round(28 * unit)))
    return image


def main():
    IMAGES.mkdir(parents=True, exist_ok=True)
    WEB.mkdir(parents=True, exist_ok=True)
    radiator().save(IMAGES / "icon.png", optimize=True)
    banner((1024, 500)).save(IMAGES / "featureGraphic.png", optimize=True)
    banner((1200, 630)).save(WEB / "social.png", optimize=True)
    for source, name in (("1.png", "home.webp"), ("2.png", "room.webp"), ("3.png", "schedule.webp")):
        image = Image.open(IMAGES / "phoneScreenshots" / source).convert("RGB")
        image.thumbnail((720, 1280), Image.Resampling.LANCZOS)
        image.save(WEB / name, "WEBP", quality=85, method=6)
    copyfile(ROOT / "LICENSE", ROOT / "site/license.txt")
    destination = ROOT / "app/src/main/assets/opensource"
    destination.mkdir(parents=True, exist_ok=True)
    copyfile(ROOT / "LICENSE", destination / "LICENSE.txt")
    copyfile(ROOT / "NOTICE", destination / "NOTICE.txt")
    print("Rendered store artwork and site assets")


if __name__ == "__main__":
    main()

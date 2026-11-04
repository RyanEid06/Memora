"""Package approved Memora artwork; preserve exact originals in docs/branding/memora.

Development-only dependency: Pillow. Launcher uses the full-bleed border-removal edit.
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ORIGINALS = ROOT / "docs/branding/memora/originals"
RES = ROOT / "app/src/main/res"
DRAWABLE = RES / "drawable-nodpi"
DRAWABLE.mkdir(parents=True, exist_ok=True)


def wordmark(source: str, target: str) -> None:
    image = Image.open(ORIGINALS / source).convert("RGBA")
    alpha = image.getchannel("A").point(lambda value: 255 if value > 64 else 0)
    # Ignore isolated low-opacity export speckles; retain a generous artwork gutter.
    rows = [y for y in range(image.height)
            if alpha.crop((0, y, image.width, y + 1)).histogram()[255] > 20]
    columns = [x for x in range(image.width)
               if alpha.crop((x, 0, x + 1, image.height)).histogram()[255] > 20]
    gutter = 32
    image = image.crop((max(0, min(columns) - gutter), max(0, min(rows) - gutter),
                        min(image.width, max(columns) + 1 + gutter),
                        min(image.height, max(rows) + 1 + gutter)))
    image.thumbnail((1000, 300), Image.Resampling.LANCZOS)
    image.save(DRAWABLE / target, optimize=True)


wordmark("wordmark-dark-original.png", "memora_wordmark_dark.png")
wordmark("wordmark-light-original.png", "memora_wordmark_light.png")

launcher = Image.open(ORIGINALS.parent / "launcher-fullbleed.png").convert("RGB")
# Android supplies the mask; the branded background must reach every icon edge.
master = launcher.resize((512, 512), Image.Resampling.LANCZOS)
master.save(DRAWABLE / "memora_launcher.png", optimize=True)
# The 90dp image covers the 72dp viewport while keeping the emblem inside the safe zone.
foreground = launcher.resize((1080, 1080), Image.Resampling.LANCZOS).convert("RGBA")
foreground.paste(launcher.resize((900, 900), Image.Resampling.LANCZOS), (90, 90))
foreground.save(DRAWABLE / "memora_launcher_foreground.png", optimize=True)
for density, pixels in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    folder = RES / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    for name in ["ic_launcher.png", "ic_launcher_round.png"]:
        master.resize((pixels, pixels), Image.Resampling.LANCZOS).save(folder / name, optimize=True)
print("Prepared 4 drawable assets and 10 launcher density assets.")

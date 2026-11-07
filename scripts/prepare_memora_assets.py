"""Package approved Memora artwork; preserve exact originals in docs/branding/memora.

Development-only dependency: Pillow. Launcher uses the full-bleed border-removal edit.
"""
from pathlib import Path
from PIL import Image, ImageOps

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
# Match the adaptive icon's visible 72dp of a 90dp artwork image.
inset = launcher.width // 10
master = launcher.resize((512, 512), Image.Resampling.LANCZOS,
                         box=(inset, inset, launcher.width - inset, launcher.height - inset))
master.save(DRAWABLE / "memora_launcher.png", optimize=True)
# Extend only the outermost background pixels into the adaptive motion gutter.
artwork = launcher.resize((900, 900), Image.Resampling.LANCZOS)
foreground = ImageOps.expand(artwork, border=90)
foreground.paste(artwork.crop((0, 0, 1, 900)).resize((90, 900), Image.Resampling.NEAREST), (0, 90))
foreground.paste(artwork.crop((899, 0, 900, 900)).resize((90, 900), Image.Resampling.NEAREST), (990, 90))
foreground.paste(foreground.crop((0, 90, 1080, 91)).resize((1080, 90), Image.Resampling.NEAREST), (0, 0))
foreground.paste(foreground.crop((0, 989, 1080, 990)).resize((1080, 90), Image.Resampling.NEAREST), (0, 990))
foreground.save(DRAWABLE / "memora_launcher_foreground.png", optimize=True)
for density, pixels in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    folder = RES / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    for name in ["ic_launcher.png", "ic_launcher_round.png"]:
        master.resize((pixels, pixels), Image.Resampling.LANCZOS).save(folder / name, optimize=True)
print("Prepared 4 drawable assets and 10 launcher density assets.")

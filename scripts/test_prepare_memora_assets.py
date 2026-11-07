"""Regenerate in a disposable workspace; never modify the immutable artwork or app resources."""
import hashlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parents[1]


class LauncherAssetsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="launcher-contract-", dir=ROOT / "artifacts")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        (cls.root / "scripts").mkdir()
        shutil.copyfile(ROOT / "scripts/prepare_memora_assets.py", cls.root / "scripts/prepare_memora_assets.py")
        shutil.copytree(ROOT / "docs/branding/memora/originals", cls.root / "docs/branding/memora/originals")
        shutil.copyfile(ROOT / "docs/branding/memora/launcher-fullbleed.png", cls.root / "docs/branding/memora/launcher-fullbleed.png")
        for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
            (cls.root / f"app/src/main/res/mipmap-{density}").mkdir(parents=True)
        subprocess.run([sys.executable, "-X", "utf8", str(cls.root / "scripts/prepare_memora_assets.py")], check=True, capture_output=True)
        cls.res = cls.root / "app/src/main/res"

    def test_immutable_original_and_full_bleed_launcher_without_black_border(self):
        source = ROOT / "docs/branding/memora/originals/launcher-original.png"
        self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest().upper(),
                         "E8B9DEDBFCF568FB2F32B3A0593330D3E5BEB2625120A0D8D985650E9E3C825E")
        with Image.open(self.res / "drawable-nodpi/memora_launcher.png") as master:
            self.assertEqual(master.size, (512, 512))
            pixels = master.convert("RGB").load()
            black_border_pixels = sum(
                max(pixels[x, y]) <= 8
                for y in range(512) for x in range(512)
                # Sample the outer rim, beyond the enlarged emblem's own shadow.
                if (x - 255.5) ** 2 + (y - 255.5) ** 2 > 240 ** 2
            )
            self.assertLessEqual(black_border_pixels, 512 * 512 // 10000,
                                 "Only isolated emblem shadow pixels are allowed, never the black export frame")

    def test_density_assets_come_from_the_same_legacy_master(self):
        with Image.open(self.res / "drawable-nodpi/memora_launcher.png") as master:
            for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
                for name in ("ic_launcher.png", "ic_launcher_round.png"):
                    with Image.open(self.res / f"mipmap-{density}" / name) as icon:
                        self.assertEqual(icon.size, (size, size))
                        self.assertIsNone(ImageChops.difference(icon.convert("RGB"), master.convert("RGB").resize((size, size), Image.Resampling.LANCZOS)).getbbox())

    def test_adaptive_layer_has_full_bleed_opaque_background(self):
        foreground = self.res / "drawable-nodpi/memora_launcher_foreground.png"
        with Image.open(foreground) as layer:
            layer = layer.convert("RGBA")
            self.assertEqual(layer.size, (1080, 1080))
            self.assertEqual(layer.getchannel("A").getextrema(), (255, 255),
                             "Adaptive artwork must fill the whole layer; Android supplies the mask")
            with Image.open(ROOT / "docs/branding/memora/launcher-fullbleed.png") as source:
                expected = source.convert("RGB").resize((900, 900), Image.Resampling.LANCZOS)
                self.assertIsNone(ImageChops.difference(layer.crop((90, 90, 990, 990)).convert("RGB"), expected).getbbox())

    def test_adaptive_background_extends_one_image_without_a_nested_square(self):
        with Image.open(self.res / "drawable-nodpi/memora_launcher_foreground.png") as layer:
            layer = layer.convert("RGB")
            # Outside the single artwork image, continue its boundary pixels. A second
            # larger copy creates the reported square seam and fails these comparisons.
            for actual_box, edge_box in [
                ((0, 90, 90, 990), (90, 90, 91, 990)),
                ((990, 90, 1080, 990), (989, 90, 990, 990)),
                ((90, 0, 990, 90), (90, 90, 990, 91)),
                ((90, 990, 990, 1080), (90, 989, 990, 990)),
            ]:
                actual = layer.crop(actual_box)
                expected = layer.crop(edge_box).resize(actual.size, Image.Resampling.NEAREST)
                self.assertIsNone(ImageChops.difference(actual, expected).getbbox(),
                                  "Adaptive background must extend one image, never composite two scales")

    def test_legacy_and_adaptive_symbols_are_large_and_survive_launcher_masks(self):
        for name, box in [("memora_launcher.png", None),
                          ("memora_launcher_foreground.png", (180, 180, 900, 900))]:
            with Image.open(self.res / "drawable-nodpi" / name) as image:
                viewport = image.convert("RGB")
                if box:
                    viewport = viewport.crop(box)
                viewport = viewport.resize((360, 360), Image.Resampling.LANCZOS)
                artwork = Image.new("L", viewport.size)
                artwork.putdata([255 if (r > 170 and g > 90 and b < 140) or
                                (r < 120 and g > 150 and b > 150) else 0
                                for r, g, b in viewport.getdata()])
                bounds = artwork.getbbox()
                self.assertGreaterEqual(bounds[2] - bounds[0], 250, name)
                self.assertGreaterEqual(bounds[3] - bounds[1], 220, name)
                for shape in ("circle", "squircle"):
                    mask = Image.new("L", viewport.size)
                    draw = ImageDraw.Draw(mask)
                    if shape == "circle":
                        draw.ellipse((0, 0, 359, 359), fill=255)
                    else:
                        draw.rounded_rectangle((0, 0, 359, 359), radius=80, fill=255)
                    removed = ImageChops.subtract(artwork, mask).histogram()[255]
                    self.assertEqual(removed, 0, f"{name}: {shape} clips the approved symbol")


if __name__ == "__main__":
    unittest.main()

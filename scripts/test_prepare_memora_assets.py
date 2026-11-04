"""Regenerate in a disposable workspace; never modify the immutable artwork or app resources."""
import hashlib
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageChops

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
                if (x - 255.5) ** 2 + (y - 255.5) ** 2 > 175 ** 2
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


if __name__ == "__main__":
    unittest.main()

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
NAVY = (30, 41, 59)


class LauncherAssetsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="launcher-contract-", dir=ROOT / "artifacts")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        (cls.root / "scripts").mkdir()
        shutil.copyfile(ROOT / "scripts/prepare_memora_assets.py", cls.root / "scripts/prepare_memora_assets.py")
        shutil.copytree(ROOT / "docs/branding/memora/originals", cls.root / "docs/branding/memora/originals")
        for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
            (cls.root / f"app/src/main/res/mipmap-{density}").mkdir(parents=True)
        subprocess.run([sys.executable, "-X", "utf8", str(cls.root / "scripts/prepare_memora_assets.py")], check=True, capture_output=True)
        cls.res = cls.root / "app/src/main/res"

    def test_immutable_source_and_legacy_canvas_preserve_full_artwork_with_navy_gutter(self):
        source = ROOT / "docs/branding/memora/originals/launcher-original.png"
        self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest().upper(),
                         "E8B9DEDBFCF568FB2F32B3A0593330D3E5BEB2625120A0D8D985650E9E3C825E")
        with Image.open(self.res / "drawable-nodpi/memora_launcher.png") as master:
            self.assertEqual(master.size, (512, 512))
            self.assertEqual(master.convert("RGB").getpixel((0, 0)), NAVY)
            # The approved legacy layout keeps the entire square original at 70% of the canvas.
            expected = Image.new("RGB", (512, 512), NAVY)
            with Image.open(source) as original:
                expected.paste(original.convert("RGB").resize((358, 358), Image.Resampling.LANCZOS), (77, 77))
            self.assertIsNone(ImageChops.difference(master.convert("RGB"), expected).getbbox(), "Launcher must not crop to visible artwork")

    def test_density_assets_come_from_the_same_legacy_master(self):
        with Image.open(self.res / "drawable-nodpi/memora_launcher.png") as master:
            for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
                for name in ("ic_launcher.png", "ic_launcher_round.png"):
                    with Image.open(self.res / f"mipmap-{density}" / name) as icon:
                        self.assertEqual(icon.size, (size, size))
                        self.assertIsNone(ImageChops.difference(icon.convert("RGB"), master.convert("RGB").resize((size, size), Image.Resampling.LANCZOS)).getbbox())

    def test_adaptive_layer_uses_108dp_canvas_and_whole_original_inside_66dp(self):
        foreground = self.res / "drawable-nodpi/memora_launcher_foreground.png"
        self.assertTrue(foreground.is_file(), "Adaptive artwork needs its separately measured layer")
        with Image.open(foreground) as layer:
            layer = layer.convert("RGBA")
            self.assertEqual(layer.size, (1080, 1080))
            self.assertEqual(layer.getchannel("A").getbbox(), (210, 210, 870, 870))
            with Image.open(ROOT / "docs/branding/memora/originals/launcher-original.png") as source:
                self.assertIsNone(ImageChops.difference(layer.crop((210, 210, 870, 870)).convert("RGB"), source.convert("RGB").resize((660, 660), Image.Resampling.LANCZOS)).getbbox())
            # Exclude the original black export background, then check the 66dp circular safe zone.
            pixels = layer.load()
            radius_squared = max((x - 539.5) ** 2 + (y - 539.5) ** 2
                                 for y in range(210, 870) for x in range(210, 870)
                                 if max(pixels[x, y][:3]) > 24)
            self.assertLessEqual(radius_squared, 330 ** 2, "Meaningful artwork must survive adaptive safe-zone masks")


if __name__ == "__main__":
    unittest.main()

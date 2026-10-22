"""Host bridge smoke test on synthetic pixels + existing third-party recipes; never NX evidence."""
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from PIL import Image, ImageDraw

import calibrate


class ProductBridgeTest(unittest.TestCase):
    def test_full_size_product_path_and_unsupported_base(self):
        archive = calibrate.REPO / "dist/nikon-cloud-recipes-20261018/nikon-cloud-recipes-142.zip"
        if not archive.is_file():
            self.skipTest("Existing corpus is absent; no samples downloaded")
        try:
            java, javac = calibrate.binaries(None)
        except ValueError as error:
            self.skipTest(str(error))
        sources = sorted((calibrate.REPO / "app/src/main/java/cc/huevia/ncp").glob("*.java")) + [calibrate.HERE / "CalibrationRender.java"]
        with tempfile.TemporaryDirectory(prefix="synthetic-not-nx-bridge-test-") as scratch:
            root = Path(scratch)
            copied = []
            for source in sources:
                target = root / "sources" / source.relative_to(calibrate.REPO)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read_bytes())
                copied.append(str(target))
            subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-d", str(root), *copied], check=True)
            # Deliberately exceeds the preview's 4 Mi-pixel cap; no silent downscaling.
            source = Image.new("RGB", (2049, 2048), (100, 130, 180))
            ImageDraw.Draw(source).rectangle((300, 300, 1500, 1400), fill=(200, 60, 30))
            source.save(root / "synthetic-A.png")
            with zipfile.ZipFile(archive) as package:
                (root / "supported.NP3").write_bytes(package.read("Color Grading/BleachBypass_01a.NP3"))
                (root / "unsupported.NP3").write_bytes(package.read("Nikon Creators/Bluegrain_jyota.NP3"))
            command = [java, "-Dfile.encoding=UTF-8", "-cp", str(root), "CalibrationRender", str(root / "synthetic-A.png")]
            result = subprocess.run(command + [str(root / "supported.NP3"), str(root / "C.png")], check=True,
                                    capture_output=True, text=True, encoding="utf-8")
            self.assertTrue(result.stdout.startswith("MODEL="))
            with Image.open(root / "C.png") as output:
                self.assertEqual(source.size, output.size)
                self.assertNotEqual(source.getpixel((100, 100)), output.convert("RGB").getpixel((100, 100)))
            rejected = subprocess.run(command + [str(root / "unsupported.NP3"), str(root / "must-not-exist.png")],
                                      capture_output=True, text=True, encoding="utf-8")
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("Flexible Color", rejected.stderr)
            self.assertFalse((root / "must-not-exist.png").exists())


if __name__ == "__main__":
    unittest.main()

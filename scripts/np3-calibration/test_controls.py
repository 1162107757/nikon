"""Policy/distribution unit tests; synthetic reports are not NX provenance evidence."""
import json
from pathlib import Path
import tempfile
import unittest
from PIL import Image
import analyze_controls as controls


class ControlDiagnosticTests(unittest.TestCase):
    def test_second_reserved_raw_requires_explicit_source_before_creating_plan(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            folder = root / "in/tone-controls"
            folder.mkdir(parents=True)
            # Pending plan policy fixture only: no images, processing or official claims.
            (folder / "stimuli-index.json").write_text(json.dumps({"entries": [
                {"registration_name": name} for name in controls.NAMES]}), encoding="utf-8")
            (root / "SOURCE-SYNTHETIC.NEF").write_bytes(b"NOT A REAL RAW")
            (root / "RESERVED-SYNTHETIC.NEF").write_bytes(b"NOT A REAL RAW")
            output = root / "pending.json"
            with self.assertRaisesRegex(ValueError, "explicitly with --raw"):
                controls.plan(root, output, "SYNTHETIC_TEST")
            self.assertFalse(output.exists())

    def test_exact_black_white_statistics_follow_recorded_metric_grid(self):
        image = Image.new("RGB", (2, 2))
        image.putdata([(0, 0, 0), (255, 255, 255), (128, 128, 128), (1, 2, 3)])
        measured = controls.sample_distribution(image, (1, 1))
        self.assertEqual(4, measured["sampled_pixels"])
        self.assertEqual(.25, measured["exact_black_pixel_fraction"])
        self.assertEqual(.25, measured["exact_white_pixel_fraction"])
        self.assertEqual([96, 96.25, 96.5], measured["mean_rgb_8bit"])
        sparse = controls.sample_distribution(image, (2, 2))
        self.assertEqual(1, sparse["sampled_pixels"])
        self.assertEqual(1, sparse["exact_black_pixel_fraction"])

    def test_gui_report_must_bind_actual_requested_filename_raw_and_version(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp).resolve()
            path = root / "SYNTHETIC-NOT-NX-REPORT.json"
            report = {"schema": "huevia.nx-gui-operation-report.v1", "provenance_kind": "nx_studio_ui",
                      "nx_version_reported": "SYNTHETIC_TEST", "operator": "SYNTHETIC_POLICY_TEST_NO_GUI",
                      "source_raw_sha256_reported": "a" * 64, "exports_reported": [{"file": "out/control.tif"}]}
            path.write_text(json.dumps(report), encoding="utf-8")
            target = root / "out/control.tif"
            # This verifies only explicit report binding, never proves software origin.
            controls.gui_bindings([path], root, "SYNTHETIC_TEST", [target], "a" * 64)
            with self.assertRaisesRegex(ValueError, "does not cover"):
                controls.gui_bindings([path], root, "SYNTHETIC_TEST", [root / "out/different.tif"], "a" * 64)
            with self.assertRaisesRegex(ValueError, "GUI operation report"):
                controls.gui_bindings([path], root, "SYNTHETIC_TEST", [target], "b" * 64)
            with self.assertRaisesRegex(ValueError, "GUI operation report"):
                controls.gui_bindings([path], root, "DIFFERENT_VERSION", [target], "a" * 64)


if __name__ == "__main__":
    unittest.main()

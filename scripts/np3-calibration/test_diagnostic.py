"""Synthetic policy tests: nothing generated here is an official NX image or evidence."""
import ctypes.util
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
import calibrate
import diagnostic
import manifest as mf
import normalize16
from test_calibration import synthetic_baseline
from test_normalize16 import synthetic_tiff


class DiagnosticTests(unittest.TestCase):
    def fixture(self, root):
        if not ctypes.util.find_library("lcms2"):
            self.skipTest("No local LittleCMS; nothing installed")
        raw, preset, evidence, source = [root / x for x in ("SYNTHETIC.NEF", "SYNTHETIC.NP3", "NOT-NX.txt", "SYNTHETIC16.tif")]
        raw.write_bytes(b"SYNTHETIC-NOT-A-REAL-RAW")
        preset.write_bytes(synthetic_baseline())
        evidence.write_text("SYNTHETIC UNIT TEST ONLY, NO GUI EXPORT", encoding="utf-8")
        synthetic_tiff(source)
        args = SimpleNamespace(source=str(source), output=str(root / "derived.png"), report=str(root / "normalize.json"), lcms=None)
        normalize16.normalize(args, calibrate.write_json)
        value = {"schema": diagnostic.INPUT_SCHEMA, "status": "diagnostic_only", "experiment_id": "synthetic-only",
                 "source_raw": mf.file_ref(raw, root), "baseline_np3": mf.file_ref(preset, root), "recipe_np3": mf.file_ref(preset, root),
                 "A_normalization": mf.file_ref(root / "normalize.json", root), "B_normalization": mf.file_ref(root / "normalize.json", root),
                 "provenance_evidence": [mf.file_ref(evidence, root)], "limitations": ["Synthetic inputs; not official or calibrated"]}
        path = root / "input.json"
        calibrate.write_json(path, value)
        return path, source

    def test_binding_rejects_changed_native_source_and_cannot_be_loaded_as_official_capture(self):
        with tempfile.TemporaryDirectory() as temp:
            path, source = self.fixture(Path(temp))
            self.assertTrue(diagnostic.load_inputs(path)[4]["eligible_for_pixel_fit"])
            with self.assertRaisesRegex(ValueError, "manifest"):
                mf.load(path)
            source.write_bytes(source.read_bytes() + b"changed")
            with self.assertRaisesRegex(ValueError, "SHA/size mismatch"):
                diagnostic.load_inputs(path)

    def test_evaluation_reports_diagnostic_even_when_pixels_equal(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path, _ = self.fixture(root)
            inventory = root / "SYNTHETIC-NOT-PRODUCTION.json"
            calibrate.write_json(inventory, {"kind": "SYNTHETIC TEST ONLY"})
            report = {"schema": diagnostic.RENDER_SCHEMA, "status": "diagnostic_only_no_calibration_acceptance",
                      "created_at_utc": "2026-10-21T00:00:00+00:00", "input_manifest": mf.file_ref(path, root),
                      "prediction": mf.file_ref(root / "derived.png", root), "model_version": "SYNTHETIC-NOT-PRODUCTION",
                      "renderer_inventory": mf.file_ref(inventory, root), "image_size": [3, 2], "diagnostic_only": True}
            rendered = root / "rendered.json"
            calibrate.write_json(rendered, report)
            args = SimpleNamespace(samples=6, directory=str(root / "comparison"), manifests=[str(rendered)])
            diagnostic.evaluate(args, calibrate.write_json)
            measured = json.loads((root / "comparison/report.json").read_text(encoding="utf-8"))
            experiment = measured["experiments"][0]
            self.assertEqual("diagnostic_only_normalized_16bit", experiment["decision"])
            self.assertEqual(0, experiment["C_to_B"]["median_de00"])
            self.assertEqual([3, 2], experiment["full_size"])
            self.assertFalse(experiment["resized"])
            self.assertIsNone(experiment["explicit_acceptance"])


if __name__ == "__main__":
    unittest.main()

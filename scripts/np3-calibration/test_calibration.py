"""Tool/metric tests only. All generated fixtures are synthetic and are not NX reference captures."""
import copy
import json
import struct
import tempfile
import unittest
from types import SimpleNamespace
from pathlib import Path
from PIL import Image

import calibrate
import manifest as mf
import metrics
import np3
import session
import sidecar
import xml.etree.ElementTree as ET
import base64


def synthetic_np3(value=128, base=b"\0\x20", name=b"SYNTHETIC"):
    result = b"NCP\0"
    for tag, payload in ((0x100, b"0310"), (0x200, name.ljust(20, b"\0")), (0x300, base), (0x1400, bytes((value, 1)))):
        result += struct.pack(">II", tag, len(payload)) + payload
    return result + b"\0\0\0\0"


def synthetic_baseline(spatial=(0, 0, 0), saturation=0):
    """Complete generated envelope for policy tests only; never an NX export."""
    result = b"NCP\0"
    for index in range(1, 33):
        payload = b"\xff\x01"
        if index == 1:
            payload = b"0310"
        elif index == 2:
            payload = b"SYNTHETIC NOT NX".ljust(20, b"\0")
        elif index == 3:
            payload = b"\0\x20"
        elif index == 4:
            payload = b"\0\0"
        elif index in (6, 22, 7):
            offset = {6: 0, 22: 1, 7: 2}[index]
            payload = bytes((128 + int(spatial[offset] * 4), 4))
        elif index in (8, 9, 10, 11, 14, 23, 24):
            payload = b"\xff\x04"
        elif index in (12, 13):
            payload = b"\xff\0"
        elif index == 21:
            payload = b"\xff\x0a"
        elif 25 <= index <= 30:
            payload = bytes((128 + (saturation if index == 30 else 0), 1))
        elif index == 31:
            payload = bytes([128] * 24 + [1, 1, 1, 0])
        elif index == 32:
            payload = b"\x80\0\x80\x80" * 3 + b"\x01\x01\x01\0\xb2\x01\x80\x01"
        result += struct.pack(">II", index * 256, len(payload)) + payload
    return result + b"\0\0\0\0"


def schema_fixture():
    # verify=False: exercises schema policy without pretending these references are real files.
    value = calibrate.template("field_0x1400", "holdout")
    ref = {"path": "SYNTHETIC-NOT-NX.nef", "sha256": "1" * 64, "bytes": 1}
    for key in ("source_raw", "baseline_np3", "recipe_np3", "A_baseline", "B_recipe"):
        value[key] = ref.copy()
    value.update(status="captured", experiment_id="synthetic-schema-unit-test", scene_group="test-only")
    value["settings"]["white_balance"]["value"] = "fixed 5200 K, tint 0 (test-only)"
    value["settings"]["lens_corrections"] = "off"
    value["provenance"].update(operator="schema test", captured_at="2026-10-21T00:00:00+00:00", nx_version="test-not-a-real-NX-build",
        same_raw_and_settings=True, manual_export_attested=True, a_uses_exported_baseline_np3=True,
        b_uses_recorded_recipe_np3=True, steps=["Schema fixture only; no NX processing occurred"], evidence=[ref.copy()])
    value["changed_controls"] = [{"ui_label": "test-only label", "before": "0", "after": "1"}]
    return value


class CalibrationTests(unittest.TestCase):
    def test_field1400_diff_is_bytes_not_semantics(self):
        with tempfile.TemporaryDirectory() as temp:
            a, b = Path(temp) / "a.NP3", Path(temp) / "b.NP3"
            a.write_bytes(synthetic_np3(127))
            b.write_bytes(synthetic_np3(129, name=b"SECOND SYNTHETIC"))
            result = np3.diff(a, b)
            self.assertTrue(result["isolated_0x1400_byte_pair"])
            self.assertEqual(["0x1400"], result["non_metadata_changed_tags"])
            self.assertNotIn("meaning", result)
            records = np3.parse(synthetic_np3(129))
            reordered = b"NCP\0" + b"".join(struct.pack(">II", r["tag"], len(r["value"])) + r["value"]
                                         for r in (records[0], records[1], records[3], records[2])) + b"\0\0\0\0"
            b.write_bytes(reordered)
            self.assertTrue(np3.diff(a, b)["non_metadata_structure_changed"])
            self.assertFalse(np3.diff(a, b)["isolated_0x1400_byte_pair"])
            b.write_bytes(synthetic_np3(129, base=b"\x08\x02"))
            self.assertFalse(np3.diff(a, b)["isolated_0x1400_byte_pair"])

    def test_truncation_duplicate_and_trailing_bytes_fail(self):
        data = synthetic_np3()
        for damaged in (data[:-1], data + b"x", data[:-4] + struct.pack(">II", 0x1400, 2) + b"\x80\x01" + data[-4:],
                        data[:8] + b"\xff\xff\xff\xff" + data[12:]):
            with self.assertRaises(ValueError):
                np3.parse(damaged)

    def test_reference_hash_detects_changes(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source.nef"
            source.write_bytes(b"synthetic hashing fixture, not RAW")
            ref = mf.file_ref(source, root)
            mf.resolve(ref, root, "source")
            source.write_bytes(b"changed bytes")
            with self.assertRaisesRegex(ValueError, "SHA/size"):
                mf.resolve(ref, root, "source")

    def test_pending_and_synthetic_provenance_are_not_official_captures(self):
        value = schema_fixture()
        value["baseline_kind"] = "neutral_zero_adjustment"
        mf.validate(value, Path("."), verify=False)
        automated = copy.deepcopy(value)
        automated["provenance"].update(kind="nx_studio_ui", operator="Codex computer-use")
        mf.validate(automated, Path("."), verify=False)
        for change in (lambda v: v.update(status="pending"),
                       lambda v: v["provenance"].update(kind="synthetic_test"),
                       lambda v: v["provenance"].update(manual_export_attested=False),
                       lambda v: v["provenance"].update(a_uses_exported_baseline_np3=False),
                       lambda v: v["settings"]["white_balance"].update(mode="auto"),
                       lambda v: v["settings"].update(active_d_lighting="auto"),
                       lambda v: v.update(unexpected_field=True)):
            bad = copy.deepcopy(value)
            change(bad)
            with self.assertRaises(ValueError):
                mf.validate(bad, Path("."), verify=False)

    def test_ciede2000_reference_pairs(self):
        # Small selection of Sharma/Wu/Dalal supplementary numerical test data:
        # https://hajim.rochester.edu/ece/sites/gsharma/ciede2000/dataNprograms/ciede2000testdata.txt
        pairs = [((50, 2.6772, -79.7751), (50, 0, -82.7485), 2.0425),
                 ((50, 3.1571, -77.2803), (50, 0, -82.7485), 2.8615),
                 ((50, 2.8361, -74.0200), (50, 0, -82.7485), 3.4412),
                 ((50, 0, 0), (50, -1, 2), 2.3669),
                 ((50, 2.49, -.001), (50, -2.49, .0011), 7.2195),
                 ((50, -.001, 2.49), (50, .0011, -2.49), 4.7461)]
        for a, b, expected in pairs:
            self.assertAlmostEqual(expected, metrics.de00(a, b), delta=.00005)
            self.assertAlmostEqual(metrics.de00(a, b), metrics.de00(b, a), places=10)

    def test_identity_alignment_sampling_and_clipping(self):
        a = Image.new("RGB", (1000, 1), (80, 120, 160))
        result = metrics.compare(a, a, limit=100)
        self.assertEqual(0, result["median_de00"])
        self.assertLessEqual(result["sampled_pixels"], 100)
        b = Image.new("RGB", a.size, (0, 120, 160))
        self.assertEqual(1, metrics.compare(a, b)["new_clip_fraction"])
        with self.assertRaisesRegex(ValueError, "geometry"):
            metrics.compare(a, Image.new("RGB", (1, 1000)))

    def test_profiles_and_acceptance_are_explicit(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "toy.png"
            Image.new("RGB", (2, 2), (80, 120, 160)).save(path)
            _, unknown = metrics.read_image(path)
            self.assertFalse(unknown["verified_srgb"])
            Image.new("RGB", (2, 2), (80, 120, 160)).save(path, icc_profile=metrics.srgb_profile())
            _, embedded = metrics.read_image(path)
            self.assertTrue(embedded["verified_srgb"])
        value = schema_fixture()
        value["baseline_kind"] = "neutral_zero_adjustment"
        result = {"median_de00": 0, "p95_de00": 0, "new_clip_fraction": 0}
        proof = {"A": embedded, "B": embedded, "C": embedded}
        self.assertEqual("report_only_no_thresholds", metrics.decision(value, result, proof))
        value["acceptance"] = {"median_de00_max": 1, "p95_de00_max": 2, "new_clip_fraction_max": 0}
        zero = {"eligible_for_pixel_fit": True}
        self.assertEqual("meets_explicit_internal_thresholds", metrics.decision(value, result, proof, zero))
        self.assertEqual("indeterminate_unknown_color_space", metrics.decision(value, result, {"A": unknown}, zero))
        self.assertEqual("indeterminate_non_neutral_baseline", metrics.decision(value, result, proof))
        self.assertEqual("indeterminate_non_neutral_baseline", metrics.decision(value, result, proof, {"eligible_for_pixel_fit": False}))
        value["partition"] = "train"
        self.assertEqual("report_only_training_split", metrics.decision(value, result, proof))

    def test_zero_baseline_checks_the_three_spatial_values_not_quick_sharp_label(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "SYNTHETIC-NOT-NX.NP3"
            path.write_bytes(synthetic_baseline())
            self.assertTrue(np3.baseline_state(path)["eligible_for_pixel_fit"])
            path.write_bytes(synthetic_baseline((2, .5, 1)))
            state = np3.baseline_state(path)
            self.assertFalse(state["eligible_for_pixel_fit"])
            self.assertEqual({"sharpening": 2, "mid_range_sharpening": .5, "clarity": 1}, state["spatial_ui_values"])
            path.write_bytes(synthetic_baseline(saturation=1))
            self.assertFalse(np3.baseline_state(path)["eligible_for_pixel_fit"])
            data = synthetic_baseline()
            path.write_bytes(data[:-4] + struct.pack(">II", 2, 578) + bytes(578) + data[-4:])
            self.assertFalse(np3.baseline_state(path)["eligible_for_pixel_fit"])

    def test_observed_nx_default_is_allowed_for_byte_research_but_not_pixel_fit(self):
        with tempfile.TemporaryDirectory(prefix="synthetic-not-nx-manifest-policy-") as temp:
            root = Path(temp)
            value = schema_fixture()
            for key, filename in (("source_raw", "SYNTHETIC-NOT-NX.NEF"), ("baseline_np3", "baseline.NP3"),
                                  ("recipe_np3", "recipe.NP3"), ("A_baseline", "A.bin"), ("B_recipe", "B.bin")):
                path = root / filename
                path.write_bytes(synthetic_baseline((2, .5, 1)) if key.endswith("np3") else b"schema fixture only, not NX output")
                value[key] = mf.file_ref(path, root)
            proof = root / "not-real-evidence.txt"
            proof.write_text("Synthetic schema test; no manual NX export occurred", encoding="utf-8")
            value["provenance"]["evidence"] = [mf.file_ref(proof, root)]
            value["settings"].update(sharpening=2, mid_range_sharpening=.5, clarity=1)
            # Only exercises validation of an explicitly observed baseline in a temporary test fixture.
            mf.validate(value, root)
            value["purpose"] = "flexible_color"
            with self.assertRaisesRegex(ValueError, "zero-adjustment"):
                mf.validate(value, root)
            value["baseline_kind"] = "neutral_zero_adjustment"
            with self.assertRaisesRegex(ValueError, "NP3 semantics"):
                mf.validate(value, root)
            value.update(purpose="field_0x1400", baseline_kind="nx_default_or_observed")
            value["settings"]["sharpening"] = 0
            with self.assertRaisesRegex(ValueError, "baseline UI value"):
                mf.validate(value, root)

    def test_split_leakage_is_rejected(self):
        a, b = schema_fixture(), schema_fixture()
        b.update(experiment_id="second", partition="train", scene_group="other")
        with self.assertRaisesRegex(ValueError, "leakage"):
            calibrate.check_splits([a, b])
        b["source_raw"] = dict(b["source_raw"], sha256="2" * 64)
        calibrate.check_splits([a, b])
        b["scene_group"] = a["scene_group"]
        with self.assertRaisesRegex(ValueError, "leakage"):
            calibrate.check_splits([a, b])

    def test_existing_corpus_keeps_unknown_and_creative_groups(self):
        path = calibrate.REPO / "dist/nikon-cloud-recipes-20261018/nikon-cloud-recipes-142.zip"
        if not path.is_file():
            self.skipTest("Existing third-party corpus is absent; no download performed")
        result = np3.audit(path)
        self.assertEqual(142, result["np3_members"])
        self.assertEqual([], result["errors"])
        values = {row["hex_or_sha256"]: row["count"] for row in result["fields"]["0x1400"]}
        self.assertEqual({"7f01": 1, "8001": 38, "8101": 7, "ff01": 96}, values)
        bases = {row["hex_or_sha256"]: row["count"] for row in result["fields"]["0x0300"]}
        self.assertEqual({"0020": 141, "0802": 1}, bases)

    def test_private_session_prep_preserves_nine_originals_without_claiming_gui_exports(self):
        archive = calibrate.REPO / "dist/nikon-cloud-recipes-20261018/nikon-cloud-recipes-142.zip"
        if not archive.is_file():
            self.skipTest("Existing corpus is absent; no samples downloaded")
        with tempfile.TemporaryDirectory(prefix="synthetic-not-nx-session-prep-") as temp:
            root = Path(temp)
            raw = root / "SYNTHETIC-NOT-A-REAL-RAW.NEF"
            raw.write_bytes(b"Synthetic file identity test only; not an NX benchmark or real RAW")
            args = SimpleNamespace(workspace=str(root / "private"), raw=str(raw), raw_bytes=raw.stat().st_size,
                                   raw_sha256=mf.sha_file(raw), nx_version="test-not-real-NX")
            result = session.prepare(args, calibrate.template, calibrate.write_json, calibrate.REPO)
            self.assertEqual("prepared_only", result["status"])
            prepared = Path(args.workspace)
            index = json.loads((prepared / "inputs-index.json").read_text(encoding="utf-8"))
            self.assertEqual(9, len(index["presets"]))
            self.assertEqual([], list((prepared / "out").iterdir()))
            for item in index["presets"]:
                target = mf.resolve(item["private_file"], prepared, "private original")
                self.assertEqual(item["source_sha256"], mf.sha_file(target))
                self.assertTrue(item["bytes_unchanged"])
            pending = json.loads((prepared / "manifests/quick-minus.pending.json").read_text(encoding="utf-8"))
            self.assertEqual("pending", pending["status"])
            self.assertEqual("nx_studio_ui", pending["provenance"]["kind"])
            self.assertEqual("Codex computer-use", pending["provenance"]["operator"])
            self.assertFalse(pending["provenance"]["manual_export_attested"])
            with self.assertRaisesRegex(ValueError, "already exists"):
                session.prepare(args, calibrate.template, calibrate.write_json, calibrate.REPO)

    def test_nksc_working_xml_is_not_embedded_defaults_or_a_decoded_wb_claim(self):
        # Synthetic parser fixture only. No NX export is generated by this test.
        wrapper = ET.Element("xmp")
        edits = ET.SubElement(wrapper, "NineEdits")
        edits.text = ('<userData><filter id="nikon::WhiteBalance"><active>false</active><parameters>'
                      '<binary name="Data">AQID</binary></parameters></filter>'
                      '<filter id="nikon::NoiseReduction"><active>true</active><parameters>'
                      '<integer name="NoiseReduction.enableNoiseReduction">0</integer></parameters></filter>'
                      '<filter id="nik::GrainNoise"><active>false</active><parameters>'
                      '<integer name="Strength">36</integer></parameters></filter></userData>')
        packet = ET.SubElement(wrapper, "XMLPackets")
        ET.SubElement(packet, "value").text = base64.b64encode(b'<defaults><Sharpness>72</Sharpness></defaults>').decode()
        ET.SubElement(wrapper, "Trim").text = base64.b64encode(bytes([255] * 16)).decode()
        state = sidecar.parse_bytes(ET.tostring(wrapper))["settings_observed"]
        self.assertEqual("false", state["white_balance"]["active_raw"])
        self.assertEqual("opaque_unless_independently_documented", state["white_balance"]["values"]["Data"]["semantic_status"])
        self.assertNotIn("kelvin", state["white_balance"]["values"])
        self.assertEqual("true", state["noise_reduction"]["active_raw"])
        self.assertEqual("0", state["noise_reduction"]["values"]["NoiseReduction.enableNoiseReduction"])
        self.assertEqual("false", state["film_grain"]["active_raw"])
        self.assertEqual("36", state["film_grain"]["values"]["Strength"])

    def test_capture_log_only_appends_and_detects_tampering(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "synthetic-test-log.jsonl"
            first = sidecar.append_entry(path, {"event": "synthetic-test-one"})
            original = path.read_bytes()
            second = sidecar.append_entry(path, {"event": "synthetic-test-two"})
            self.assertTrue(path.read_bytes().startswith(original))
            self.assertEqual(first["entry_sha256"], second["previous_entry_sha256"])
            self.assertEqual(2, len(sidecar.checked_history(path)))
            with self.assertRaisesRegex(ValueError, "generated"):
                sidecar.append_entry(path, {"event": "test", "sequence": 100})
            path.write_bytes(path.read_bytes().replace(b"synthetic-test-two", b"changed-test-value"))
            with self.assertRaisesRegex(ValueError, "chain"):
                sidecar.checked_history(path)


if __name__ == "__main__":
    unittest.main()

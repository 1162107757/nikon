"""Synthetic precision/layout tests only; no fixture is an official NX reference."""
from pathlib import Path
from types import SimpleNamespace
import ctypes.util
import json
import math
import struct
import tempfile
import unittest
from PIL import Image
import calibrate
import manifest as mf
import metrics
import normalize16 as n16


def synthetic_tiff(path, endian="<", compression=1, include_icc=True, preview=False):
    icc = n16.canonical_profile() if include_icc else b""
    width, height = 3, 2
    samples = [0, 32768, 65535, 32896, 128, 129, 257, 514, 771] * 2
    tags = [(256, 4, 1, width), (257, 4, 1, height), (258, 3, 3, None), (259, 3, 1, compression),
            (262, 3, 1, 2), (273, 4, 1, None), (274, 3, 1, 1), (277, 3, 1, 3),
            (278, 4, 1, height), (279, 4, 1, len(samples) * 2), (284, 3, 1, 1), (339, 3, 1, 1)]
    if include_icc:
        tags.append((34675, 7, len(icc), None))
    bits_offset = 8 + 2 + 12 * len(tags) + 4
    icc_offset = bits_offset + 6
    pixels_offset = icc_offset + len(icc)
    content = (b"II*\0" if endian == "<" else b"MM\0*") + struct.pack(endian + "I", 8)
    content += struct.pack(endian + "H", len(tags))
    for tag, kind, count, value in tags:
        if tag == 258:
            value = bits_offset
        elif tag == 273:
            value = pixels_offset
        elif tag == 34675:
            value = icc_offset
        field = struct.pack(endian + "H", value) + bytes(2) if kind == 3 and count == 1 else struct.pack(endian + "I", value)
        content += struct.pack(endian + "HHI", tag, kind, count) + field
    content += bytes(4) + struct.pack(endian + "3H", 16, 16, 16) + icc + struct.pack(endian + str(len(samples)) + "H", *samples)
    if preview:
        next_ifd = len(content)
        pointer = 8 + 2 + 12 * len(tags)
        content = content[:pointer] + struct.pack(endian + "I", next_ifd) + content[pointer + 4:]
        preview_tags = [(256, 4, 1, 1), (257, 4, 1, 1), (258, 3, 3, 0), (259, 3, 1, 1),
                        (262, 3, 1, 2), (273, 4, 1, 0), (277, 3, 1, 3), (278, 4, 1, 1),
                        (279, 4, 1, 3), (284, 3, 1, 1)]
        bits_at = next_ifd + 2 + 12 * len(preview_tags) + 4
        content += struct.pack(endian + "H", len(preview_tags))
        for tag, kind, count, value in preview_tags:
            value = bits_at if tag == 258 else bits_at + 6 if tag == 273 else value
            field = struct.pack(endian + "H", value) + bytes(2) if kind == 3 and count == 1 else struct.pack(endian + "I", value)
            content += struct.pack(endian + "HHI", tag, kind, count) + field
        content += bytes(4) + struct.pack(endian + "3H", 8, 8, 8) + bytes((128, 128, 128))
    path.write_bytes(content)
    return samples


class Normalize16Tests(unittest.TestCase):
    def test_exact_quantizer_exhaustive(self):
        self.assertEqual(bytes(math.floor(x / 257 + .5) for x in range(65536)), n16.quantize(range(65536)))
        self.assertEqual(bytes((0, 0, 1, 128, 255)), n16.quantize((0, 128, 129, 32896, 65535)))

    def test_native_read_preserves_low_bytes_and_endianness(self):
        with tempfile.TemporaryDirectory() as temp:
            for endian in ("<", ">"):
                path = Path(temp) / ("little.tif" if endian == "<" else "big.tif")
                expected = synthetic_tiff(path, endian)
                info = n16.layout(path)
                decoded = [value for _, _, values in n16.native_bands(path, info, 1) for value in values]
                self.assertEqual(expected, decoded)
                self.assertIn(128, decoded)
                self.assertIn(129, decoded)

    def test_unsupported_layout_and_unknown_icc_fail(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "unsupported.tif"
            synthetic_tiff(path, compression=5)
            with self.assertRaisesRegex(ValueError, "uncompressed"):
                n16.layout(path)
            synthetic_tiff(path, include_icc=False)
            with self.assertRaisesRegex(ValueError, "embedded sRGB"):
                n16.layout(path)

    def test_multi_ifd_requires_explicit_primary_selection_and_rejects_rgb8_preview(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "SYNTHETIC-MULTI-IFD-NOT-NX.tif"
            expected = synthetic_tiff(path, preview=True)
            with self.assertRaisesRegex(ValueError, "explicit --frame"):
                n16.layout(path)
            info = n16.layout(path, 0)
            self.assertEqual(2, info["source_frame_count"])
            self.assertEqual([1, 1], info["source_frames"][1]["size"])
            self.assertEqual(expected, [v for _, _, values in n16.native_bands(path, info) for v in values])
            with self.assertRaisesRegex(ValueError, "16-bit RGB"):
                n16.layout(path, 1)
            with self.assertRaisesRegex(ValueError, "frame range"):
                n16.layout(path, -1)

    def test_native_icc_normalization_is_marked_diagnostic_and_never_passes_acceptance(self):
        if not ctypes.util.find_library("lcms2"):
            self.skipTest("No local LittleCMS shared library; no library downloaded")
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "SYNTHETIC-NOT-NX16.tif"
            synthetic_tiff(source)
            before = mf.sha_file(source)
            results = []
            for suffix in ("a", "b"):
                args = SimpleNamespace(source=str(source), output=str(root / (suffix + ".png")),
                                       report=str(root / (suffix + ".json")), lcms=None)
                results.append(n16.normalize(args, calibrate.write_json))
            self.assertEqual(before, mf.sha_file(source))
            self.assertEqual(results[0]["target_sha256"], results[1]["target_sha256"])
            image, proof = metrics.read_image(root / "a.png")
            self.assertEqual((3, 2), image.size)
            self.assertEqual((0, 128, 255), image.getpixel((0, 0)))
            self.assertTrue(proof["verified_srgb"])
            self.assertTrue(proof["diagnostic_normalized_16bit"])
            self.assertEqual("diagnostic_only_normalized_16bit", metrics.decision({}, {}, {"A": proof}))
            report = json.loads((root / "a.json").read_text(encoding="utf-8"))
            self.assertEqual(before, report["source"]["sha256"])
            self.assertEqual(mf.sha_file(root / "a.png"), report["target"]["sha256"])
            self.assertFalse(report["engine"]["dither"])
            self.assertTrue(report["source_unchanged_verified"])


if __name__ == "__main__":
    unittest.main()

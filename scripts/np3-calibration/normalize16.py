"""Diagnostic RGB16 TIFF -> canonical sRGB8 PNG. Never asks Pillow to decode RGB16 pixels."""
from array import array
import ctypes
import ctypes.util
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import struct
import sys
import tempfile
from PIL import Image, ImageCms, PngImagePlugin, __version__ as pillow_version
import manifest as mf

MARKER = "huevia.normalize16"
TYPE_RGB_16 = (4 << 16) | (3 << 3) | 2
FLAGS = 0x0040 | 0x0100  # LCMS public NOCACHE | NOOPTIMIZE; no BPC, no dithering.


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical_profile():
    # Only target metadata is canonicalized. The source ICC is never modified.
    data = bytearray(ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes())
    data[24:36] = struct.pack(">6H", 2000, 1, 1, 0, 0, 0)
    data[84:100] = bytes(16)  # ICC permits an unspecified/zero profile ID.
    return bytes(data)


def profile_description(data):
    if len(data) < 128 or data[36:40] != b"acsp" or int.from_bytes(data[:4], "big") != len(data):
        raise ValueError("Invalid or truncated ICC header")
    if data[16:20] != b"RGB ":
        raise ValueError("ICC must describe RGB samples")
    try:
        description = ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(data))).strip()
    except Exception as error:
        raise ValueError("ICC cannot be opened by LittleCMS") from error
    if "srgb" not in description.lower().replace(" ", ""):
        raise ValueError("This diagnostic command currently accepts only identified embedded sRGB ICC profiles")
    return description


def layout(path, frame=None):
    path = Path(path)
    size = path.stat().st_size
    with path.open("rb") as source:
        header = source.read(4)
    if header not in (b"II*\0", b"MM\0*"):
        raise ValueError("Only classic little/big-endian TIFF is supported; BigTIFF is not silently reinterpreted")
    with Image.open(path) as image:
        frame_count = getattr(image, "n_frames", 1)
        if not 1 <= frame_count <= 64:
            raise ValueError("Unsupported TIFF frame count")
        if frame is None:
            if frame_count != 1:
                raise ValueError("Multi-IFD TIFF requires explicit --frame (normally 0 for the full-resolution NX image)")
            frame = 0
        if not 0 <= frame < frame_count:
            raise ValueError("Selected --frame is outside the TIFF frame range")
        frames = []
        for index in range(frame_count):
            image.seek(index)
            frames.append({"index": index, "size": list(image.size), "mode": image.mode,
                           "bits_per_sample": list(image.tag_v2.get(258, ())),
                           "new_subfile_type": image.tag_v2.get(254)})
        image.seek(frame)
        tags = image.tag_v2
        width, height = image.size
        if image.format != "TIFF" or not 0 < width <= 65535 or not 0 < height <= 65535 or width * height > 100000000:
            raise ValueError("Unsupported TIFF geometry")
        if tuple(tags.get(258, ())) != (16, 16, 16) or tags.get(277, 1) != 3 or tags.get(262) != 2:
            raise ValueError("Expected exactly three unsigned 16-bit RGB samples")
        sample_format = tags.get(339, (1, 1, 1))
        if isinstance(sample_format, int):
            sample_format = (sample_format,)
        if any(value != 1 for value in sample_format):
            raise ValueError("Signed/floating-point RGB16 is unsupported")
        if (tags.get(259, 1) != 1 or tags.get(284, 1) != 1 or tags.get(266, 1) != 1
                or tags.get(317, 1) != 1 or tags.get(274, 1) != 1 or tags.get(338) or 324 in tags):
            raise ValueError("Only uncompressed, interleaved, normal-order/orientation RGB16 strips without alpha/predictor/tiles are supported")
        rows = tags.get(278, height)
        offsets, counts = tuple(tags.get(273, ())), tuple(tags.get(279, ()))
        if not isinstance(rows, int) or rows <= 0 or len(offsets) != (height + rows - 1) // rows or len(counts) != len(offsets):
            raise ValueError("Invalid RGB16 strip table")
        strips = []
        for index, (offset, count) in enumerate(zip(offsets, counts)):
            row, row_count = index * rows, min(rows, height - index * rows)
            if count != width * row_count * 6 or offset < 8 or offset + count > size:
                raise ValueError("Truncated, padded or inconsistent RGB16 strip")
            strips.append({"offset": offset, "bytes": count, "first_row": row, "rows": row_count})
        ordered = sorted((item["offset"], item["offset"] + item["bytes"]) for item in strips)
        if any(right[0] < left[1] for left, right in zip(ordered, ordered[1:])):
            raise ValueError("Overlapping RGB16 strips are unsupported")
        icc = tags.get(34675, b"")
        if not icc or len(icc) > 1024 * 1024:
            raise ValueError("A bounded embedded sRGB ICC is required; EXIF/default assumptions are insufficient")
        description = profile_description(icc)
    return {"width": width, "height": height, "byte_order": "little" if header[:2] == b"II" else "big",
            "selected_frame": frame, "source_frame_count": frame_count, "source_frames": frames,
            "strips": strips, "source_icc": bytes(icc), "source_icc_description": description}


def native_bands(path, info, band_rows=32):
    if not 1 <= band_rows <= 64:
        raise ValueError("band_rows must be 1..64")
    with open(path, "rb") as source:
        for strip in info["strips"]:
            source.seek(strip["offset"])
            for local in range(0, strip["rows"], band_rows):
                rows = min(band_rows, strip["rows"] - local)
                data = source.read(info["width"] * rows * 6)
                if len(data) != info["width"] * rows * 6:
                    raise ValueError("RGB16 samples were truncated during reading")
                values = array("H")
                values.frombytes(data)
                if sys.byteorder != info["byte_order"]:
                    values.byteswap()
                yield strip["first_row"] + local, rows, values


def quantize(values):
    """Nearest u16/257; integer formula explicitly fixes rounding without stochastic dither."""
    return bytes((int(value) + 128) // 257 for value in values)


class Lcms16:
    def __init__(self, source_icc, target_icc, library=None):
        name = library or ctypes.util.find_library("lcms2")
        if not name or not Path(name).is_file():
            raise ValueError("A resolved local LittleCMS 2 shared library is required; specify --lcms with its absolute path")
        self.path = Path(name).resolve()
        self.directory_handle = os.add_dll_directory(str(self.path.parent)) if os.name == "nt" else None
        self.handles, self.transform_handle = [], None
        try:
            loader = ctypes.WinDLL if os.name == "nt" else ctypes.CDLL
            self.lib = loader(str(self.path))
        except OSError:
            self.close()
            raise
        signatures = {
            "cmsOpenProfileFromMem": ([ctypes.c_void_p, ctypes.c_uint32], ctypes.c_void_p),
            "cmsGetColorSpace": ([ctypes.c_void_p], ctypes.c_uint32),
            "cmsCreateTransform": ([ctypes.c_void_p, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_uint32], ctypes.c_void_p),
            "cmsDoTransform": ([ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_uint32], None),
            "cmsDeleteTransform": ([ctypes.c_void_p], None), "cmsCloseProfile": ([ctypes.c_void_p], ctypes.c_int),
            "cmsGetEncodedCMMversion": ([], ctypes.c_uint32),
        }
        try:
            for function, (arguments, result) in signatures.items():
                target = getattr(self.lib, function)
                target.argtypes, target.restype = arguments, result
        except AttributeError as error:
            self.close()
            raise ValueError("Selected library does not provide the required LittleCMS 2 API") from error
        self.buffers = [ctypes.create_string_buffer(data) for data in (source_icc, target_icc)]
        try:
            for data, buffer in zip((source_icc, target_icc), self.buffers):
                handle = self.lib.cmsOpenProfileFromMem(buffer, len(data))
                if not handle:
                    raise ValueError("Native LittleCMS rejected an ICC profile")
                self.handles.append(handle)
                if self.lib.cmsGetColorSpace(handle) != 0x52474220:
                    raise ValueError("Native ICC colour space is not RGB")
            self.transform_handle = self.lib.cmsCreateTransform(self.handles[0], TYPE_RGB_16, self.handles[1], TYPE_RGB_16, 1, FLAGS)
            if not self.transform_handle:
                raise ValueError("Native LittleCMS could not create the RGB16->RGB16 sRGB transform")
            self.version = self.lib.cmsGetEncodedCMMversion()
        except Exception:
            self.close()
            raise

    def transform(self, values):
        output = array("H", [0]) * len(values)
        source_buffer = (ctypes.c_uint16 * len(values)).from_buffer(values)
        target_buffer = (ctypes.c_uint16 * len(output)).from_buffer(output)
        self.lib.cmsDoTransform(self.transform_handle, source_buffer, target_buffer, len(values) // 3)
        return output

    def close(self):
        if self.transform_handle:
            self.lib.cmsDeleteTransform(self.transform_handle)
            self.transform_handle = None
        for handle in self.handles:
            self.lib.cmsCloseProfile(handle)
        self.handles = []
        if self.directory_handle is not None:
            self.directory_handle.close()
            self.directory_handle = None


def normalize(args, writer):
    source, target, report_path = Path(args.source).resolve(), Path(args.output).resolve(), Path(args.report).resolve()
    if target == report_path or source in (target, report_path) or target.exists() or report_path.exists():
        raise ValueError("Source, new output PNG and new report must be distinct; existing artifacts are never overwritten")
    if target.suffix.lower() != ".png":
        raise ValueError("Diagnostic normalized output must be a lossless PNG")
    source_identity = mf.file_ref(source, report_path.parent)
    info = layout(source, getattr(args, "frame", None))
    target_icc = canonical_profile()
    target_description = profile_description(target_icc)
    engine = Lcms16(info["source_icc"], target_icc, args.lcms)
    image = Image.new("RGB", (info["width"], info["height"]))
    native_sha, output_sha = hashlib.sha256(), hashlib.sha256()
    try:
        for row, rows, values in native_bands(source, info):
            little = values if sys.byteorder == "little" else array("H", values)
            if sys.byteorder != "little":
                little.byteswap()
            native_sha.update(little.tobytes())
            rgb8 = quantize(engine.transform(values))
            output_sha.update(rgb8)
            band = Image.frombytes("RGB", (info["width"], rows), rgb8)
            image.paste(band, (0, row))
            band.close()
        engine_record = {"library": str(engine.path), "library_sha256": mf.sha_file(engine.path),
                         "encoded_cmm_version": engine.version, "intent": "relative_colorimetric (1)",
                         "input_format": "TYPE_RGB_16 native-endian", "output_format": "TYPE_RGB_16 native-endian",
                         "flags": FLAGS, "black_point_compensation": False, "dither": False}
    except BaseException:
        image.close()
        raise
    finally:
        engine.close()
    marker = {"schema": "huevia.rgb16-diagnostic.v1", "diagnostic_only": True,
              "source_sha256": source_identity["sha256"], "source_icc_sha256": sha(info["source_icc"]),
              "target_icc_sha256": sha(target_icc), "quantizer": "u8=(u16+128)//257 after ICC16 conversion"}
    metadata = PngImagePlugin.PngInfo()
    metadata.add_text(MARKER, json.dumps(marker, sort_keys=True, separators=(",", ":")))
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix="normalize16-", suffix=".png", dir=target.parent, delete=False) as stream:
            temporary = Path(stream.name)
            image.save(stream, format="PNG", icc_profile=target_icc, pnginfo=metadata, compress_level=6)
            stream.flush()
            os.fsync(stream.fileno())
        image.close()
        if mf.sha_file(source) != source_identity["sha256"]:
            raise ValueError("Source TIFF changed during normalization; result is not published")
        with Image.open(temporary) as verified:
            verified.load()
            if verified.size != (info["width"], info["height"]) or verified.mode != "RGB" or verified.info.get("icc_profile") != target_icc or MARKER not in verified.info:
                raise ValueError("Normalized PNG geometry/ICC/provenance verification failed")
            decoded_sha = hashlib.sha256()
            for row in range(0, verified.height, 32):
                band = verified.crop((0, row, verified.width, min(row + 32, verified.height)))
                decoded_sha.update(band.tobytes())
                band.close()
            if decoded_sha.hexdigest() != output_sha.hexdigest():
                raise ValueError("PNG encoder changed the diagnostic RGB samples")
        with temporary.open("rb") as input_file, target.open("xb") as output_file:
            shutil.copyfileobj(input_file, output_file, 1024 * 1024)
            output_file.flush()
            os.fsync(output_file.fileno())
    finally:
        image.close()
        if temporary is not None and temporary.exists():
            temporary.unlink()
    report = {"schema": "huevia.rgb16-normalization.v1", "status": "diagnostic_only_not_official_8bit_export_or_calibration",
              "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(), "source": source_identity,
              "target": mf.file_ref(target, report_path.parent), "size": [info["width"], info["height"]],
              "native_rgb16_sha256_little_endian": native_sha.hexdigest(), "target_rgb8_sha256": output_sha.hexdigest(),
              "source_layout": {key: value for key, value in info.items() if key not in ("source_icc", "source_icc_description")},
              "source_icc": {"bytes": len(info["source_icc"]), "sha256": sha(info["source_icc"]), "description": info["source_icc_description"]},
              "target_icc": {"bytes": len(target_icc), "sha256": sha(target_icc), "description": target_description,
                             "creation_metadata": "fixed 2000-01-01T00:00:00; unspecified zero profile ID"},
              "engine": engine_record, "rounding": "nearest integer u16/257; exact integer u8=(u16+128)//257",
              "resize": False, "orientation_change": False, "source_unchanged_verified": True,
              "driver_sha256": mf.sha_file(Path(__file__)), "png_provenance_marker": MARKER,
              "runtime": {"python": sys.version, "pillow": pillow_version},
              "limits": ["Native uint16 samples are read directly from strips; Pillow is used only for metadata and final RGB8 PNG.",
                         "ICC conversion itself returns uint16 before the declared 8-bit quantization.",
                         "This derivative is diagnostic only and must not produce a colour/spatial calibrated PASS.",
                         "Source GUI provenance must be supplied separately; this command does not prove the source came from NX."]}
    writer(report_path, report)
    return {"status": report["status"], "target": str(target), "report": str(report_path),
            "source_sha256": source_identity["sha256"], "target_sha256": report["target"]["sha256"],
            "target_rgb8_sha256": output_sha.hexdigest()}

"""Read private NKSC bytes and preserve observations; never calls NX or equates defaults with working settings."""
import base64
import copy
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import xml.etree.ElementTree as ET
import manifest as mf


def local_name(tag):
    return tag.rsplit("}", 1)[-1]


def binary_summary(text):
    clean = "".join((text or "").split())
    try:
        data = base64.b64decode(clean, validate=True)
        return {"encoding": "base64", "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
                "hex_head": data[:80].hex(), "semantic_status": "opaque_unless_independently_documented"}
    except ValueError:
        return {"encoding": "unrecognized", "text_chars": len(text or ""), "semantic_status": "not_interpreted"}


def parse_bytes(data):
    if len(data) > 2 * 1024 * 1024:
        raise ValueError("NKSC observation is limited to 2 MiB")
    root = ET.fromstring(data)
    outer = {local_name(element.tag): (element.text or "").strip() for element in root.iter()
             if local_name(element.tag) in ("appversion", "appname")}
    edits = next((element for element in root.iter() if local_name(element.tag) == "NineEdits"), None)
    if edits is None or not (edits.text or "").lstrip().startswith("<"):
        raise ValueError("NineEdits is not the researched literal XML string; refusing to guess another encoding")
    inner = ET.fromstring(edits.text)
    filters = []
    for element in inner.findall("filter"):
        leaves = []
        for parameter in element.iter():
            if list(parameter) or parameter.tag == "active":
                continue
            value = (parameter.text or "").strip()
            item = {"tag": parameter.tag, "attributes": dict(parameter.attrib)}
            if parameter.tag in ("binary", "ExportData"):
                item["binary"] = binary_summary(value)
            else:
                item["text"] = value
            leaves.append(item)
        filters.append({"id": element.attrib.get("id"), "active_raw": element.findtext("active"), "parameters": leaves})
    by_id = {item["id"]: item for item in filters}

    def view(identity):
        item = by_id.get(identity)
        if item is None:
            return None
        values = {}
        for parameter in item["parameters"]:
            key = parameter["attributes"].get("name", parameter["attributes"].get("id", parameter["tag"]))
            values[key] = parameter.get("text", parameter.get("binary"))
        return {"active_raw": item["active_raw"], "values": values}

    trim = next((element.text for element in root.iter() if local_name(element.tag) == "Trim"), None)
    packet_summaries = []
    for element in root.iter():
        if local_name(element.tag) != "XMLPackets":
            continue
        packet = next(iter(element), None)
        if packet is not None:
            summary = binary_summary(packet.text)
            summary["use"] = "embedded metadata/defaults; not authoritative NineEdits working controls"
            packet_summaries.append(summary)
    return {"app": outer, "working_payload": "nine:NineEdits literal inner XML", "filters": filters,
            "settings_observed": {
                "white_balance": view("nikon::WhiteBalance"), "exposure": view("nikon::ExposureSettings"),
                "noise_reduction": view("nikon::NoiseReduction"), "active_d_lighting": view("nikon::ActiveDLighting"),
                "film_grain": view("nik::GrainNoise"),
                "d_lighting_hs": view("nikon::DLightingHS"), "d_lighting_hq": view("nikon::DLightingHQ"),
                "lens_vignette": view("nikon::Vignette"), "lens_chromatic_aberration": view("nikon::ChrAb"),
                "lens_diffraction": view("nikon::Diffraction"), "lens_distortion": view("nikon::Distortion"),
                "straighten": view("nik::Straighten"), "transform": view("nikon::transform"),
                "size_resolution": view("nikon::SizeRes"), "trim_binary": binary_summary(trim)},
            "embedded_xml_packets": packet_summaries,
            "limits": ["Current working-state snapshot only; not a per-export state history.",
                       "Opaque WhiteBalance Data does not prove Kelvin/tint or fixed manual WB.",
                       "Module active flags may coexist with disabled parameter flags; raw values are retained without collapsing them to ON/OFF.",
                       "Opaque Trim/SizeRes data does not alone prove crop or resize state.",
                       "No colour/spatial calibration acceptance is inferred."]}


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")


def checked_history(path):
    entries, previous = [], None
    if path.exists():
        with path.open("r", encoding="utf-8") as source:
            for line in source:
                record = json.loads(line)
                unsigned = {key: value for key, value in record.items() if key != "entry_sha256"}
                if (record.get("previous_entry_sha256") != previous or record.get("sequence") != len(entries) + 1
                        or record.get("entry_sha256") != hashlib.sha256(canonical(unsigned)).hexdigest()):
                    raise ValueError("Existing append-only GUI capture log chain is inconsistent")
                entries.append(record)
                previous = record["entry_sha256"]
    return entries


def append_entry(path, value):
    path = Path(path)
    entries = checked_history(path)
    if {"sequence", "previous_entry_sha256", "entry_sha256"} & set(value):
        raise ValueError("Append-only chain identity fields are generated by the logger")
    if path.exists() and path.stat().st_size:
        with path.open("rb") as source:
            source.seek(-1, 2)
            if source.read(1) != b"\n":
                raise ValueError("Existing log has an incomplete final line; refusing to append")
    record = copy.deepcopy(value)
    record.update(sequence=len(entries) + 1,
                  previous_entry_sha256=entries[-1]["entry_sha256"] if entries else None)
    record["entry_sha256"] = hashlib.sha256(canonical(record)).hexdigest()
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("ab") as output:
        output.write(canonical(record) + b"\n")
        output.flush()
        os.fsync(output.fileno())
    checked_history(path)
    return record


def tiff_metadata(path):
    """Metadata only; no pixel comparison or interpretation of opaque Nikon MakerNote fields."""
    from PIL import Image
    import re
    with Image.open(path) as image:
        xmp, icc = image.tag_v2.get(700, b""), image.tag_v2.get(34675, b"")
        exif = image.getexif().get_ifd(34665)
        maker = exif.get(37500, b"")
        result = {"size": list(image.size), "bits_per_sample": list(image.tag_v2.get(258, ())),
                  "compression_raw": image.tag_v2.get(259), "software_raw": image.tag_v2.get(305),
                  "icc_bytes": len(icc), "icc_sha256": hashlib.sha256(icc).hexdigest(),
                  "xmp_bytes": len(xmp), "xmp_sha256": hashlib.sha256(xmp).hexdigest(),
                  "maker_note_bytes": len(maker), "maker_note_sha256": hashlib.sha256(maker).hexdigest(),
                  "maker_note_grain_or_nineedits_plaintext_found": bool(re.search(rb"grain|film|NineEdits", maker, re.I)),
                  "grain_status": "not recovered; opaque MakerNote is not proof of grain absence"}
        if xmp:
            packet = ET.fromstring(xmp)
            result["xmp_fields"] = [{"qualified_tag": element.tag, "text": (element.text or "").strip()}
                                    for element in packet.iter() if not list(element)]
            result["xmp_nineedits_present"] = any(local_name(element.tag) == "NineEdits" for element in packet.iter())
            result["xmp_grain_named_fields"] = [element.tag for element in packet.iter() if re.search("grain|film", element.tag, re.I)]
        return result


def observe(args, writer):
    root, source = Path(args.workspace).resolve(), Path(args.sidecar).resolve()
    before = source.stat()
    data = source.read_bytes()
    after = source.stat()
    if before.st_mtime_ns != after.st_mtime_ns or before.st_size != after.st_size or len(data) != after.st_size:
        raise ValueError("NKSC changed during read; repeat after the GUI has completed its save")
    parsed = parse_bytes(data)
    digest = hashlib.sha256(data).hexdigest()
    now = dt.datetime.now(dt.timezone.utc)
    folder = root / "provenance"
    snapshot = folder / "sidecar-snapshots" / (digest + ".nksc")
    snapshot.parent.mkdir(parents=True, exist_ok=True)
    if snapshot.exists():
        if snapshot.read_bytes() != data:
            raise ValueError("Existing content-addressed NKSC snapshot is inconsistent")
    else:
        with snapshot.open("xb") as output:
            output.write(data)
    report_path = folder / ("sidecar-state-" + now.strftime("%Y%m%dT%H%M%S%fZ") + ".json")
    report = {"schema": "huevia.nksc-observation.v1", "status": "read_only_working_state_not_calibration",
              "observed_at_utc": now.isoformat(), "live_sidecar_path": str(source),
              "live_sidecar_mtime_utc": dt.datetime.fromtimestamp(after.st_mtime, dt.timezone.utc).isoformat(),
              "snapshot": mf.file_ref(snapshot, root), "observation": parsed}
    writer(report_path, report)
    gui_report_path = Path(args.gui_report).resolve()
    gui_report = json.loads(gui_report_path.read_text(encoding="utf-8"))
    entry = {"schema": "huevia.nx-gui-capture-log.v1", "event": "reported_gui_exports_and_file_identities_observed",
             "observed_at_utc": now.isoformat(), "provenance_kind": "nx_studio_ui", "operator": args.operator,
             "log_writer_role": "Codex file-only observation; did not operate NX GUI",
             "gui_operation_report": mf.file_ref(gui_report_path, root), "reported_operations": gui_report,
             "files_observed": [mf.file_ref(Path(path), root) for path in args.files],
             "nksc_snapshot": mf.file_ref(snapshot, root), "nksc_observation": mf.file_ref(report_path, root),
             "sidecar_scope": "working state at observation only; cannot prove every earlier export had identical non-treatment settings",
             "calibration_acceptance": "not_evaluated; no color/spatial calibrated PASS"}
    entry["file_mtime_utc"] = {str(Path(path).resolve()): dt.datetime.fromtimestamp(Path(path).stat().st_mtime, dt.timezone.utc).isoformat()
                              for path in args.files}
    entry["tiff_metadata_observed"] = {str(Path(path).resolve()): tiff_metadata(path) for path in args.files
                                      if Path(path).suffix.lower() in (".tif", ".tiff")}
    log_path = folder / "gui-capture-log.jsonl"
    added = append_entry(log_path, entry)
    return {"status": "appended_real_gui_report_and_read_only_file_observations", "sequence": added["sequence"],
            "entry_sha256": added["entry_sha256"], "log": str(log_path), "sidecar_report": str(report_path),
            "sidecar_snapshot_sha256": digest}

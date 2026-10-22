"""Strict provenance validation. An operator attestation is evidence, not proof of vendor origin."""
import hashlib
import json
import math
import datetime as dt
from pathlib import Path

SCHEMA = "huevia.np3-calibration.v2"
SETTING_KEYS = {"color_reproduction", "tone_mode", "white_balance", "exposure_ev", "active_d_lighting",
                "d_lighting", "noise_reduction", "lens_corrections", "crop", "sharpening",
                "mid_range_sharpening", "clarity", "export"}
TOP_KEYS = {"schema", "status", "experiment_id", "scene_group", "partition", "purpose", "baseline_kind", "source_raw",
            "baseline_np3", "recipe_np3", "A_baseline", "B_recipe", "prediction", "settings",
            "provenance", "changed_controls", "regions", "acceptance"}


def strict(value, keys, label):
    if not isinstance(value, dict) or set(value) != set(keys):
        raise ValueError(f"{label}: expected exactly {sorted(keys)}")


def text(value, label):
    if not isinstance(value, str) or not value.strip() or value.strip().startswith("TODO"):
        raise ValueError(f"{label}: non-placeholder text required")


def number(value, label):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{label}: finite number required")


def file_ref(path, root):
    path = Path(path)
    resolved = path.resolve()
    try:
        name = str(resolved.relative_to(root.resolve())).replace("\\", "/")
    except ValueError:
        name = str(resolved)
    return {"path": name, "sha256": sha_file(resolved), "bytes": resolved.stat().st_size}


def sha_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def resolve(ref, root, label, verify=True):
    strict(ref, {"path", "sha256", "bytes"}, label)
    text(ref["path"], label + ".path")
    if not isinstance(ref["sha256"], str) or len(ref["sha256"]) != 64 or any(c not in "0123456789abcdef" for c in ref["sha256"]):
        raise ValueError(label + ": invalid SHA-256")
    if isinstance(ref["bytes"], bool) or not isinstance(ref["bytes"], int) or ref["bytes"] <= 0:
        raise ValueError(label + ": invalid byte count")
    path = Path(ref["path"])
    if not path.is_absolute():
        path = root / path
    if verify and (not path.is_file() or path.stat().st_size != ref["bytes"] or sha_file(path) != ref["sha256"]):
        raise ValueError(label + ": missing file or SHA/size mismatch")
    return path


def load(path, require_prediction=False):
    path = Path(path)
    value = json.loads(path.read_text(encoding="utf-8"))
    validate(value, path.parent, require_prediction)
    return value


def validate(value, root, require_prediction=False, verify=True):
    strict(value, TOP_KEYS, "manifest")
    if value["schema"] != SCHEMA or value["status"] != "captured":
        raise ValueError("Only captured manifests using the current schema can be evaluated; plans are not NX evidence")
    for key in ("experiment_id", "scene_group"):
        text(value[key], key)
    if value["partition"] not in ("train", "holdout") or value["purpose"] not in ("flexible_color", "field_0x1400", "creative_base_0802"):
        raise ValueError("Invalid partition or experiment group")
    if value["baseline_kind"] not in ("neutral_zero_adjustment", "nx_default_or_observed"):
        raise ValueError("baseline_kind must distinguish a zero-adjustment baseline from an NX default/observed baseline")
    if value["purpose"] == "flexible_color" and value["baseline_kind"] != "neutral_zero_adjustment":
        raise ValueError("Flexible colour/spatial fitting requires a declared zero-adjustment A baseline, not NX Reset/Quick sharp=0")
    for key in ("source_raw", "baseline_np3", "recipe_np3", "A_baseline", "B_recipe"):
        resolve(value[key], root, key, verify)
    if Path(value["source_raw"]["path"]).suffix.lower() not in (".nef", ".nrw"):
        raise ValueError("source_raw must identify the actual RAW/merged-RAW source, not a rendered or synthetic image")
    if verify:
        import np3
        record_lists = [np3.parse(np3.read_bytes(resolve(value[key], root, key))) for key in ("baseline_np3", "recipe_np3")]
        for entries in record_lists:
            if len(entries) < 32:
                raise ValueError("NP3 capture needs all 32 base TLV records")
            for index, record in enumerate(entries[:32], 1):
                size = 4 if index == 1 else 20 if index in (2, 32) else 28 if index == 31 else 2
                if record["tag"] != index * 256 or len(record["value"]) != size:
                    raise ValueError("NP3 base TLV order or length is not the researched 0310 envelope")
        records = [{r["tag"]: r["value"] for r in entries} for entries in record_lists]
        expected_base = b"\x08\x02" if value["purpose"] == "creative_base_0802" else b"\x00\x20"
        if any(r.get(0x100) != b"0310" or r.get(0x300) != expected_base for r in records):
            raise ValueError("NP3 version/base does not match the experiment group; Creative 0802 needs its own baseline")
        baseline = np3.baseline_state(resolve(value["baseline_np3"], root, "baseline_np3"))
        if value["baseline_kind"] == "neutral_zero_adjustment" and not baseline["eligible_for_pixel_fit"]:
            raise ValueError("Declared zero-adjustment A baseline failed NP3 semantics: " + "; ".join(baseline["reasons"]))
    strict(value["provenance"], {"kind", "operator", "captured_at", "nx_version", "same_raw_and_settings",
                                 "a_uses_exported_baseline_np3", "b_uses_recorded_recipe_np3",
                                 "manual_export_attested", "steps", "evidence"}, "provenance")
    p = value["provenance"]
    if (p["kind"] not in ("manual_nx_studio", "nx_studio_ui") or p["same_raw_and_settings"] is not True or p["manual_export_attested"] is not True
            or p["a_uses_exported_baseline_np3"] is not True or p["b_uses_recorded_recipe_np3"] is not True):
        raise ValueError("Explicit real NX Studio GUI provenance, same-RAW/settings and export attestations are required")
    for key in ("operator", "captured_at", "nx_version"):
        text(p[key], "provenance." + key)
    try:
        stamp = dt.datetime.fromisoformat(p["captured_at"])
        if stamp.tzinfo is None:
            raise ValueError()
    except ValueError:
        raise ValueError("captured_at must be an ISO timestamp with timezone")
    if not isinstance(p["steps"], list) or not p["steps"] or not isinstance(p["evidence"], list) or not p["evidence"]:
        raise ValueError("Actual GUI steps and at least one screenshot/log evidence file are required")
    for step in p["steps"]:
        text(step, "GUI step")
    for i, ref in enumerate(p["evidence"]):
        resolve(ref, root, f"evidence[{i}]", verify)
    s = value["settings"]
    strict(s, SETTING_KEYS, "settings")
    if s["color_reproduction"] != "Latest Picture Control" or s["tone_mode"] != "SDR":
        raise ValueError("Lock Latest Picture Control and SDR")
    strict(s["white_balance"], {"mode", "value"}, "white_balance")
    if s["white_balance"]["mode"] != "fixed":
        raise ValueError("Automatic white balance is not a controlled baseline")
    text(s["white_balance"]["value"], "fixed WB/Kelvin/tint UI values")
    number(s["exposure_ev"], "exposure_ev")
    for key in ("active_d_lighting", "d_lighting", "noise_reduction", "lens_corrections", "crop"):
        text(s[key], key)
        if "auto" in s[key].lower():
            raise ValueError(key + ": automatic processing must be disabled")
    for key in ("sharpening", "mid_range_sharpening", "clarity"):
        number(s[key], key)
        if verify and baseline["spatial_ui_values"][key] != s[key]:
            raise ValueError(key + ": recorded A-baseline UI value differs from the exported baseline NP3")
    e = s["export"]
    strict(e, {"color_space", "bit_depth", "format", "quality", "resize", "orientation"}, "export")
    if e["color_space"] != "sRGB" or e["bit_depth"] != 8 or e["format"] not in ("TIFF", "PNG", "JPEG") or e["resize"] is not False or e["orientation"] != "normalized":
        raise ValueError("This tool evaluates unresized, normalized-orientation 8-bit SDR sRGB exports only")
    if e["format"] == "JPEG":
        if isinstance(e["quality"], bool) or not isinstance(e["quality"], int) or not 1 <= e["quality"] <= 100:
            raise ValueError("JPEG quality must be recorded explicitly")
    elif e["quality"] is not None:
        raise ValueError("Lossless exports have quality=null")
    if not isinstance(value["changed_controls"], list):
        raise ValueError("changed_controls must be a list")
    for item in value["changed_controls"]:
        strict(item, {"ui_label", "before", "after"}, "changed control")
        for key in item:
            text(item[key], "changed_control." + key)
        if item["before"] == item["after"]:
            raise ValueError("A recorded changed control must have different before/after UI values")
    if value["purpose"] == "field_0x1400" and len(value["changed_controls"]) != 1:
        raise ValueError("0x1400 research requires exactly one recorded UI control change")
    if not isinstance(value["regions"], list):
        raise ValueError("regions must be a list")
    for region in value["regions"]:
        strict(region, {"name", "kind", "box"}, "region")
        text(region["name"], "region.name")
        if region["kind"] not in ("gray", "skin", "saturated", "other") or not isinstance(region["box"], list) or len(region["box"]) != 4:
            raise ValueError("Invalid region")
        for coordinate in region["box"]:
            number(coordinate, "region coordinate")
        x0, y0, x1, y1 = region["box"]
        if not 0 <= x0 < x1 <= 1 or not 0 <= y0 < y1 <= 1:
            raise ValueError("Regions use normalized [x0,y0,x1,y1] bounds")
    acceptance = value["acceptance"]
    if acceptance is not None:
        strict(acceptance, {"median_de00_max", "p95_de00_max", "new_clip_fraction_max"}, "acceptance")
        for key, bound in acceptance.items():
            number(bound, key)
            if bound < 0 or key == "new_clip_fraction_max" and bound > 1:
                raise ValueError("Invalid acceptance bound")
    prediction = value["prediction"]
    if prediction is None:
        if require_prediction:
            raise ValueError("C prediction is required")
    else:
        strict(prediction, {"image", "renderer"}, "prediction")
        resolve(prediction["image"], root, "C prediction", verify)
        strict(prediction["renderer"], {"kind", "model_version", "source_sha256", "source_inventory", "command", "input_a_sha256", "recipe_sha256"}, "renderer")
        r = prediction["renderer"]
        if r["kind"] not in ("huevia_jvm", "android_app"):
            raise ValueError("C must identify the actual Huevia renderer, not an unrelated Python approximation")
        for key in set(r) - {"source_inventory"}:
            text(r[key], "renderer." + key)
        resolve(r["source_inventory"], root, "renderer source inventory", verify)
        if r["source_inventory"]["sha256"] != r["source_sha256"]:
            raise ValueError("Renderer source inventory identity mismatch")
        if r["input_a_sha256"] != value["A_baseline"]["sha256"] or r["recipe_sha256"] != value["recipe_np3"]["sha256"]:
            raise ValueError("Prediction was produced from different A/recipe bytes")
    return value

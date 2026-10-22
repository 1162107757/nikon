"""Explicit local derivatives; deliberately separate from an attested official capture manifest."""
import datetime as dt
import json
from pathlib import Path
import manifest as mf
import metrics
import np3

INPUT_SCHEMA = "huevia.np3-diagnostic-input.v1"
RENDER_SCHEMA = "huevia.np3-diagnostic-render.v1"
INPUT_KEYS = {"schema", "status", "experiment_id", "source_raw", "baseline_np3", "recipe_np3",
              "A_normalization", "B_normalization", "provenance_evidence", "limitations"}
RENDER_KEYS = {"schema", "status", "created_at_utc", "input_manifest", "prediction", "model_version",
               "renderer_inventory", "image_size", "diagnostic_only"}


def normalization(path):
    path = Path(path)
    report = json.loads(path.read_text(encoding="utf-8"))
    if (report.get("schema") != "huevia.rgb16-normalization.v1"
            or report.get("status") != "diagnostic_only_not_official_8bit_export_or_calibration"
            or report.get("source_unchanged_verified") is not True):
        raise ValueError("Expected an explicit diagnostic RGB16 normalization report")
    source = mf.resolve(report["source"], path.parent, "native RGB16 source")
    target = mf.resolve(report["target"], path.parent, "normalized diagnostic PNG")
    image, proof = metrics.read_image(target)
    try:
        marker = proof.get("diagnostic_normalization")
        if (not proof.get("diagnostic_normalized_16bit") or not proof["verified_srgb"] or not isinstance(marker, dict)
                or marker.get("source_sha256") != report["source"]["sha256"]
                or marker.get("source_icc_sha256") != report["source_icc"]["sha256"]
                or marker.get("target_icc_sha256") != report["target_icc"]["sha256"]
                or list(image.size) != report["size"]):
            raise ValueError("Diagnostic PNG marker/ICC/geometry does not match its normalization report")
    finally:
        image.close()
    return {"report": report, "source": source, "target": target, "profile_proof": proof}


def load_inputs(path):
    path = Path(path).resolve()
    value = json.loads(path.read_text(encoding="utf-8"))
    mf.strict(value, INPUT_KEYS, "diagnostic input")
    if value["schema"] != INPUT_SCHEMA or value["status"] != "diagnostic_only":
        raise ValueError("Only an explicit diagnostic input manifest is accepted")
    mf.text(value["experiment_id"], "experiment_id")
    resolved = {key: mf.resolve(value[key], path.parent, key) for key in
                ("source_raw", "baseline_np3", "recipe_np3", "A_normalization", "B_normalization")}
    if resolved["source_raw"].suffix.lower() not in (".nef", ".nrw"):
        raise ValueError("Diagnostic source must identify the actual RAW; no official origin is inferred from its extension")
    if not isinstance(value["provenance_evidence"], list) or not value["provenance_evidence"]:
        raise ValueError("Diagnostic input needs explicit provenance evidence references")
    for index, ref in enumerate(value["provenance_evidence"]):
        mf.resolve(ref, path.parent, f"provenance_evidence[{index}]")
    if not isinstance(value["limitations"], list) or not value["limitations"]:
        raise ValueError("Diagnostic input must state its unresolved limitations")
    for item in value["limitations"]:
        mf.text(item, "limitation")
    a, b = [normalization(resolved[key]) for key in ("A_normalization", "B_normalization")]
    if a["report"]["size"] != b["report"]["size"]:
        raise ValueError("Diagnostic A/B have different full-size geometry; no automatic resize")
    if a["report"]["target_icc"]["sha256"] != b["report"]["target_icc"]["sha256"]:
        raise ValueError("Diagnostic A/B were standardized to different target profiles")
    np3.parse(np3.read_bytes(resolved["recipe_np3"]))
    baseline = np3.baseline_state(resolved["baseline_np3"])
    return value, resolved, a, b, baseline


def render(args, writer, render_product):
    path = Path(args.manifest).resolve()
    output, result_path = Path(args.output).resolve(), Path(args.output_manifest).resolve()
    if output == result_path or path in (output, result_path) or output.exists() or result_path.exists():
        raise ValueError("Diagnostic output and report must use distinct new paths")
    _, resolved, a, _, _ = load_inputs(path)
    model, inventory = render_product(a["target"], resolved["recipe_np3"], output, args.jdk)
    load_inputs(path)  # Verify the input identities again after the full-size render.
    result = {"schema": RENDER_SCHEMA, "status": "diagnostic_only_no_calibration_acceptance",
              "created_at_utc": dt.datetime.now(dt.timezone.utc).isoformat(), "diagnostic_only": True,
              "input_manifest": mf.file_ref(path, result_path.parent), "prediction": mf.file_ref(output, result_path.parent),
              "model_version": model, "renderer_inventory": mf.file_ref(inventory, result_path.parent),
              "image_size": a["report"]["size"]}
    writer(result_path, result)
    print(json.dumps({"status": result["status"], "model_version": model,
                      "image_size": result["image_size"], "prediction": str(output), "report": str(result_path)}, indent=2))


def evaluate(args, writer):
    if not 1 <= args.samples <= 1000000:
        raise ValueError("--samples must be 1..1000000")
    root = Path(args.directory).resolve()
    if (root / "report.json").exists():
        raise ValueError("Diagnostic report already exists; choose a new directory")
    root.mkdir(parents=True, exist_ok=True)
    results = []
    for index, name in enumerate(args.manifests):
        path = Path(name).resolve()
        rendered = json.loads(path.read_text(encoding="utf-8"))
        mf.strict(rendered, RENDER_KEYS, "diagnostic render")
        if (rendered["schema"] != RENDER_SCHEMA or rendered["status"] != "diagnostic_only_no_calibration_acceptance"
                or rendered["diagnostic_only"] is not True):
            raise ValueError("Only an explicit diagnostic render report is accepted")
        input_path = mf.resolve(rendered["input_manifest"], path.parent, "input_manifest")
        value, _, a_info, b_info, baseline = load_inputs(input_path)
        c_path = mf.resolve(rendered["prediction"], path.parent, "prediction")
        mf.resolve(rendered["renderer_inventory"], path.parent, "renderer_inventory")
        loaded = [metrics.read_image(p) for p in (a_info["target"], b_info["target"], c_path)]
        a, b, c = [item[0] for item in loaded]
        try:
            if list(a.size) != rendered["image_size"]:
                raise ValueError("Render geometry does not match original diagnostic input")
            comparison = root / f"{index + 1:03d}-diagnostic-comparison.png"
            if comparison.exists():
                raise ValueError("Diagnostic comparison already exists")
            results.append({"experiment_id": value["experiment_id"], "render_report": mf.file_ref(path, root),
                "model_version": rendered["model_version"], "full_size": list(a.size), "resized": False,
                "sampling": "deterministic grid over full-resolution images; no rescaling; maximum requested samples is not total pixels",
                "profile_provenance": dict(zip(("A", "B", "C"), [item[1] for item in loaded])),
                "metric_roles": {"A_to_B": {"reference": "B", "candidate": "A"},
                                 "A_to_C": {"reference": "C", "candidate": "A"},
                                 "C_to_B": {"reference": "B", "candidate": "C"}},
                "A_to_B": metrics.compare(b, a, args.samples), "A_to_C": metrics.compare(c, a, args.samples),
                "C_to_B": metrics.compare(b, c, args.samples), "baseline_semantics": baseline,
                "decision": "diagnostic_only_normalized_16bit", "explicit_acceptance": None,
                "limitations": value["limitations"], "comparison": {"path": comparison.name,
                "role": "downscaled contact sheet for visual inspection only; not the metric input"}})
            metrics.contact_sheet(a, b, c, comparison, labels=("A: NX16 -> sRGB8 diagnostic",
                                  "B: NX16 -> sRGB8 diagnostic", "C: Huevia from diagnostic A"))
            results[-1]["comparison"].update(mf.file_ref(comparison, root))
        finally:
            for image, _ in loaded:
                image.close()
    writer(root / "report.json", {"schema": "huevia.np3-diagnostic-evaluation.v1", "status": "diagnostic_only_no_calibration_acceptance",
        "max_requested_samples": args.samples, "experiments": results,
        "limits": ["No fitting, holdout acceptance, vendor certification or arbitrary JPEG fidelity is claimed.",
                   "These RGB8 derivatives are explicitly standardized from GUI-reported RGB16 sources, not official NX RGB8 exports.",
                   "Unresolved RAW working settings and model operation ordering remain potential error sources."]})
    print(json.dumps({"status": "diagnostic_only_no_calibration_acceptance", "report": str(root / "report.json"),
                      "experiments": len(results)}, indent=2))

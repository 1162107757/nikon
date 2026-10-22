#!/usr/bin/env python3
"""Read official-GUI-reported control TIFF16s; create diagnostic derivatives, never fit or accept a model."""
import argparse
import json
from pathlib import Path
from types import SimpleNamespace
import sys

import calibrate
import diagnostic
import manifest as mf
import normalize16
import np3

NAMES = ("QConP50", "QConM50", "QShaP45", "QWhiP5", "QBlkM20", "QSatM25", "QBluM100", "QCyaM74", "QSunColor")
GROUPS = {"tone-controls": NAMES, "contrast-validation": ("QConP25", "QConP100"),
          "contrast-orange-validation": ("QConP25", "QConP100", "QOraP20")}
ALL_NAMES = NAMES + GROUPS["contrast-orange-validation"]
PLAN_KEYS = {"schema", "status", "workspace", "nx_version_expected", "source_raw", "baseline_np3", "A_normalization",
             "stimuli_index", "captures", "limitations"}


def plan(workspace, output, nx_version, group="tone-controls", raw_path=None, baseline_gui_report=None):
    workspace, output = Path(workspace).resolve(), Path(output).resolve()
    index_path = (workspace / "in/contrast-validation/stimuli-index-with-orange.json" if group == "contrast-orange-validation"
                  else workspace / "in" / group / "stimuli-index.json")
    index = json.loads(index_path.read_text(encoding="utf-8"))
    entries = {entry["registration_name"]: entry for entry in index["entries"]}
    names = GROUPS[group]
    if set(entries) != set(names):
        raise ValueError("Stimulus index does not match the selected prepared control group")
    raw = [Path(raw_path).resolve()] if raw_path else list(workspace.glob("*.NEF")) + list(workspace.glob("*.NRW"))
    if len(raw) != 1:
        raise ValueError("Workspace has multiple/no RAWs; select the actual A/B source explicitly with --raw")
    if raw[0].suffix.lower() not in (".nef", ".nrw"):
        raise ValueError("Explicit source must be the actual NEF/NRW")
    baseline = workspace / "out/z.NP3"
    if not np3.baseline_state(baseline)["eligible_for_pixel_fit"]:
        raise ValueError("z baseline is not semantically zero-adjustment Flexible")
    a_report = workspace / "diagnostic/z16a-normalization.json"
    a_info = diagnostic.normalization(a_report)
    baseline_report = (Path(baseline_gui_report).resolve() if baseline_gui_report
                       else workspace / "provenance/gui-reported-exports-zero16.json")
    gui_bindings([baseline_report], workspace, nx_version, [a_info["source"].resolve()], mf.sha_file(raw[0]))
    captures = []
    for name in names:
        source = mf.resolve(entries[name]["target"], index_path.parent, "indexed stimulus")
        captures.append({"name": name, "stimulus": mf.file_ref(source, output.parent),
                         "expected_gui_output16": str(workspace / "out" / (name + "-16a.tif")), "frame": 0})
    value = {"schema": "huevia.np3-controls-diagnostic-plan.v1", "status": "pending_actual_gui_exports",
             "workspace": str(workspace), "nx_version_expected": nx_version,
             "source_raw": mf.file_ref(raw[0], output.parent), "baseline_np3": mf.file_ref(baseline, output.parent),
             "A_normalization": mf.file_ref(a_report, output.parent), "stimuli_index": mf.file_ref(index_path, output.parent),
             "baseline_gui_report": mf.file_ref(baseline_report, output.parent),
             "captures": captures,
             "limitations": ["Derived stimulus NP3 bytes are not official NX preset exports or pixel evidence.",
                "Actual NX GUI output identity and operator reports are required before an individual control is analyzed.",
                "A/B are explicitly quantized sRGB8 diagnostic derivatives of original TIFF16, not official NX8 exports.",
                "One source RAW has no holdout; unresolved per-export RAW working settings remain a limitation.",
                "No fitting, automatic model changes, thresholds or calibration PASS are performed.",
                "QSunColor is a compound colour-isolation control and must not be presented as a single-field colour experiment."]}
    calibrate.write_json(output, value)
    print(json.dumps({"status": value["status"], "plan": str(output), "names": list(names)}, indent=2))


def gui_bindings(report_paths, workspace, nx_version, expected_files, raw_sha):
    reported = set()
    for path in report_paths:
        value = json.loads(Path(path).read_text(encoding="utf-8"))
        if (value.get("schema") != "huevia.nx-gui-operation-report.v1"
                or value.get("provenance_kind") not in ("nx_studio_ui", "manual_nx_studio")
                or value.get("nx_version_reported") != nx_version
                or value.get("source_raw_sha256_reported") != raw_sha):
            raise ValueError("GUI operation report must identify the actual source, operator and expected NX version")
        mf.text(value.get("operator"), "GUI report operator")
        for item in value.get("exports_reported", []):
            name = Path(item["file"])
            reported.add((name if name.is_absolute() else workspace / name).resolve())
    missing = set(expected_files) - reported
    if missing:
        raise ValueError("Actual GUI export report does not cover: " + ", ".join(str(p) for p in sorted(missing)))


def source_identity():
    sources = sorted((calibrate.REPO / "app/src/main/java/cc/huevia/ncp").glob("*.java"))
    return {str(path.relative_to(calibrate.REPO)).replace("\\", "/"): mf.sha_file(path) for path in sources}


def analyze(args):
    path = Path(args.plan).resolve()
    value = json.loads(path.read_text(encoding="utf-8"))
    # Older immutable plans predate this binding; resolve the recorded session's
    # original baseline report rather than rewriting those plans or trusting --raw alone.
    mf.strict(value, PLAN_KEYS | ({"baseline_gui_report"} if "baseline_gui_report" in value else set()), "control analysis plan")
    if value["schema"] != "huevia.np3-controls-diagnostic-plan.v1" or value["status"] != "pending_actual_gui_exports":
        raise ValueError("Expected a pending control-diagnostic plan; it is never promoted to an official calibration capture")
    workspace = Path(value["workspace"]).resolve()
    resolved = {key: mf.resolve(value[key], path.parent, key) for key in
                ("source_raw", "baseline_np3", "A_normalization", "stimuli_index")}
    if not np3.baseline_state(resolved["baseline_np3"])["eligible_for_pixel_fit"]:
        raise ValueError("Control analysis requires zero-adjustment baseline NP3")
    a_info = diagnostic.normalization(resolved["A_normalization"])
    baseline_report = (mf.resolve(value["baseline_gui_report"], path.parent, "baseline_gui_report")
                       if "baseline_gui_report" in value else workspace / "provenance/gui-reported-exports-zero16.json")
    gui_bindings([baseline_report], workspace, value["nx_version_expected"], [a_info["source"].resolve()], value["source_raw"]["sha256"])
    planned_names = [item["name"] for item in value["captures"]]
    if (len(planned_names) != len(set(planned_names))
            or not any(set(planned_names) == set(names) for names in GROUPS.values())):
        raise ValueError("Plan must retain one complete, uniquely named prepared control group")
    requested = set(args.only or planned_names)
    if not requested or not requested <= set(ALL_NAMES):
        raise ValueError("Unknown or empty requested control list")
    captures = [item for item in value["captures"] if item["name"] in requested]
    if len(captures) != len(requested) or {item["name"] for item in captures} != requested:
        raise ValueError("Plan control names are duplicated or missing")
    expected_files = [Path(item["expected_gui_output16"]).resolve() for item in captures]
    gui_paths = [Path(p).resolve() for p in args.gui_report]
    gui_bindings(gui_paths, workspace, value["nx_version_expected"], expected_files, value["source_raw"]["sha256"])
    # Validate every selected original before creating any derived output.
    stimuli = {}
    for item, image in zip(captures, expected_files):
        mf.strict(item, {"name", "stimulus", "expected_gui_output16", "frame"}, "control capture plan")
        stimuli[item["name"]] = mf.resolve(item["stimulus"], path.parent, "stimulus")
        info = normalize16.layout(image, item["frame"])
        if [info["width"], info["height"]] != a_info["report"]["size"]:
            raise ValueError("A/control B full-size geometry differs; no automatic resize")
    root = Path(args.directory).resolve()
    if root.exists():
        raise ValueError("Choose a new diagnostic output directory; previous results are never overwritten")
    root.mkdir(parents=True)
    pinned_sources = source_identity()
    pinned_driver = mf.file_ref(Path(__file__), root)
    results = []
    for index, (item, native) in enumerate(zip(captures, expected_files), 1):
        name = item["name"]
        if source_identity() != pinned_sources or mf.sha_file(Path(__file__)) != pinned_driver["sha256"]:
            raise ValueError("Product or analysis sources changed during the batch; refusing to mix implementations")
        print(f"[{index}/{len(captures)}] {name}: direct uint16 -> ICC16 -> explicit RGB8; full-size production render", flush=True)
        target = root / name
        target.mkdir()
        normalize_args = SimpleNamespace(source=str(native), frame=item["frame"], output=str(target / "B-srgb8.png"),
                                         report=str(target / "B-normalization.json"), lcms=args.lcms)
        normalize16.normalize(normalize_args, calibrate.write_json)
        input_path = target / "input.json"
        input_value = {"schema": diagnostic.INPUT_SCHEMA, "status": "diagnostic_only", "experiment_id": name + "-single-RAW-diagnostic",
            "source_raw": mf.file_ref(resolved["source_raw"], target), "baseline_np3": mf.file_ref(resolved["baseline_np3"], target),
            "recipe_np3": mf.file_ref(stimuli[name], target), "A_normalization": mf.file_ref(resolved["A_normalization"], target),
            "B_normalization": mf.file_ref(target / "B-normalization.json", target),
            "provenance_evidence": [mf.file_ref(p, target) for p in (*gui_paths, baseline_report, resolved["stimuli_index"], path)],
            "limitations": value["limitations"]}
        calibrate.write_json(input_path, input_value)
        render_args = SimpleNamespace(manifest=str(input_path), output=str(target / "C.png"),
                                      output_manifest=str(target / "render.json"), jdk=args.jdk)
        diagnostic.render(render_args, calibrate.write_json, calibrate.render_product)
        if source_identity() != pinned_sources or mf.sha_file(Path(__file__)) != pinned_driver["sha256"]:
            raise ValueError("Product sources changed during a control render; preserve output but do not combine batch results")
        rendered = json.loads((target / "render.json").read_text(encoding="utf-8"))
        inventory = json.loads(mf.resolve(rendered["renderer_inventory"], target, "inventory").read_text(encoding="utf-8"))
        actual_sources = {row["path"]: row["sha256"] for row in inventory["files"] if row["path"].startswith("app/")}
        if actual_sources != pinned_sources:
            raise ValueError("Rendered source snapshot differs from the pinned batch sources")
        eval_args = SimpleNamespace(manifests=[str(target / "render.json")], directory=str(target / "comparison"), samples=args.samples)
        diagnostic.evaluate(eval_args, calibrate.write_json)
        measured = json.loads((target / "comparison/report.json").read_text(encoding="utf-8"))["experiments"][0]
        # Sampling positions match the global C-to-B metric grid, not the visual contact sheet.
        import metrics
        images = [metrics.read_image(p)[0] for p in (target / "B-srgb8.png", target / "C.png")]
        try:
            distributions = {label: sample_distribution(image, measured["C_to_B"]["grid_step"])
                             for label, image in zip(("B", "C"), images)}
        finally:
            for image in images:
                image.close()
        results.append({"name": name, "control_kind": "compound_colour_isolation" if name == "QSunColor" else "single_known_adjustment",
            "full_size": measured["full_size"], "resized": False, "model_version": measured["model_version"],
            "C_to_B": measured["C_to_B"], "sampled_distributions": distributions,
            "report": mf.file_ref(target / "comparison/report.json", root), "decision": "diagnostic_only_normalized_16bit"})
        print(json.dumps(results[-1], ensure_ascii=False), flush=True)
    calibrate.write_json(root / "controls-report.json", {"schema": "huevia.np3-controls-diagnostic-evaluation.v1",
        "status": "diagnostic_only_no_fitting_or_calibration_acceptance", "plan": mf.file_ref(path, root),
        "pinned_product_sources": pinned_sources, "analysis_driver": pinned_driver,
        "baseline_gui_report": mf.file_ref(baseline_report, root),
        "experiments": results, "limitations": value["limitations"]})


def sample_distribution(image, step):
    pixels = image.load()
    count, black, white = 0, 0, 0
    totals = [0, 0, 0]
    for y in range(0, image.height, step[1]):
        for x in range(0, image.width, step[0]):
            rgb = pixels[x, y]
            count += 1
            black += rgb == (0, 0, 0)
            white += rgb == (255, 255, 255)
            for channel in range(3):
                totals[channel] += rgb[channel]
    return {"sampled_pixels": count, "grid_step": list(step), "exact_black_pixel_fraction": black / count,
            "exact_white_pixel_fraction": white / count, "mean_rgb_8bit": [value / count for value in totals]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("plan"); p.add_argument("workspace"); p.add_argument("--output", required=True); p.add_argument("--nx-version", required=True)
    p.add_argument("--group", choices=tuple(GROUPS), default="tone-controls")
    p.add_argument("--raw", help="Explicit source NEF/NRW, required when workspace contains a reserved validation RAW as well")
    p.add_argument("--baseline-gui-report", help="Actual GUI report binding A's original TIFF16 to the same source RAW")
    p = sub.add_parser("analyze"); p.add_argument("plan"); p.add_argument("--directory", required=True)
    p.add_argument("--gui-report", nargs="+", required=True); p.add_argument("--only", nargs="+", choices=ALL_NAMES)
    p.add_argument("--samples", type=int, default=65536); p.add_argument("--jdk"); p.add_argument("--lcms")
    args = parser.parse_args()
    try:
        if args.command == "plan":
            plan(args.workspace, args.output, args.nx_version, args.group, args.raw, args.baseline_gui_report)
        else:
            if not 1 <= args.samples <= 1000000:
                raise ValueError("--samples must be 1..1000000")
            analyze(args)
    except (ValueError, OSError) as error:
        parser.exit(2, f"ERROR: {error}\n")


if __name__ == "__main__":
    main()

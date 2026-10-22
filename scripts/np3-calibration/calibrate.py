#!/usr/bin/env python3
"""NX capture planning, read-only NP3 auditing, exact-product rendering and held-out evaluation."""
import argparse
import copy
import datetime as dt
import json
import math
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import manifest as mf
import np3

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8", newline="\n") as output:
        json.dump(value, output, ensure_ascii=False, indent=2, allow_nan=False)
        output.write("\n")


def template(purpose, partition):
    return {"schema": mf.SCHEMA, "status": "pending", "experiment_id": "TODO unique capture ID",
            "scene_group": "TODO group related scenes/bursts to prevent split leakage", "partition": partition,
            "purpose": purpose, "baseline_kind": "neutral_zero_adjustment" if purpose == "flexible_color" else "nx_default_or_observed",
            "source_raw": None, "baseline_np3": None, "recipe_np3": None,
            "A_baseline": None, "B_recipe": None, "prediction": None,
            "settings": {"color_reproduction": "Latest Picture Control", "tone_mode": "SDR",
                         "white_balance": {"mode": "fixed", "value": "TODO exact Kelvin/tint UI values"},
                         "exposure_ev": 0.0, "active_d_lighting": "off", "d_lighting": "off",
                         "noise_reduction": "off", "lens_corrections": "TODO exact fixed settings", "crop": "none",
                         "sharpening": 0.0 if purpose == "flexible_color" else 2.0,
                         "mid_range_sharpening": 0.0 if purpose == "flexible_color" else .5,
                         "clarity": 0.0 if purpose == "flexible_color" else 1.0,
                         "export": {"color_space": "sRGB", "bit_depth": 8, "format": "TIFF", "quality": None,
                                    "resize": False, "orientation": "normalized"}},
            "provenance": {"kind": "manual_nx_studio", "operator": "TODO operator", "captured_at": "TODO timestamp",
                           "nx_version": "TODO exact version/build", "same_raw_and_settings": False,
                           "a_uses_exported_baseline_np3": False, "b_uses_recorded_recipe_np3": False,
                           "manual_export_attested": False,
                           "steps": ["TODO actual manual actions that exported baseline NP3/A and recipe NP3/B"], "evidence": []},
            "changed_controls": [{"ui_label": "TODO exact UI label", "before": "TODO baseline value", "after": "TODO changed value"}]
                                if purpose == "field_0x1400" else [],
            "regions": [], "acceptance": None}


def plan(args):
    root = Path(args.directory)
    root.mkdir(parents=True, exist_ok=True)
    write_json(root / "manifest-template.json", template(args.purpose, args.partition))
    tasks = [
        {"group": "field_0x1400", "id": "flexible-observed-roundtrip", "action": "Reset to the observed NX default Flexible Color. Record the three actual spatial values separately: Quick sharp=0 may mean (2,.5,1), not (0,0,0). Export NP3 twice without changes. This default is valid byte-diff evidence but not a neutral pixel-fitting baseline."},
        {"group": "field_0x1400", "id": "one-visible-control-at-a-time", "action": "From the same saved default, vary ONE visible NX control neutral→one step lower→one step higher, exporting NP3 and same-RAW image at each step. Record exact UI label/values. Inspect 0x1400; never patch bytes to manufacture evidence."},
        {"group": "field_0x1400", "id": "negative-controls", "action": "Include contrast/highlights/shadows/white/black/saturation, sharpening/mid-range/clarity, one Blender channel and one Grading axis. Restore baseline before every control. Preserve experiments where 0x1400 does not change."},
        {"group": "field_0x1400", "id": "observed-value-roundtrips", "action": "Independently import/export the eight corpus recipes listed below. Record whether NX preserves/normalizes/rejects 0x7f01 or 0x8101. Round-trip preservation alone does not identify semantics."},
        {"group": "creative_base_0802", "id": "separate-creative-baseline", "action": "Use an actual NX Creative 0x0802 baseline and its own same-base single-control variants. Do not treat the entire 0x0020→0x0802 difference as one unknown scalar. Current product renderer must still reject this group."},
        {"group": "flexible_color", "id": "colour-singles-and-holdout", "action": "Build A with every colour adjustment neutral and sharpening/mid-range/clarity INDIVIDUALLY zero; disable custom curve. Export baseline NP3 and let semantic validation verify it. B changes only the treatment preset/control; retain identical RAW development settings. Then capture single-control and compound recipes with held-out scenes and unseen recipes."},
    ]
    observed = {"0x7f01": ["24Look_24Frames"], "0x8101": ["BlueHour_Luna", "GoldenHourGlow_DAnn", "HKReminisce_Hiuman",
                "PainterlyLight_DAnn", "Pastelpink_Yuki", "SunsetFilm_JKBoy", "WarmDimLight_Luna"], "creative_0x0802": ["Bluegrain_jyota"]}
    write_json(root / "export-plan.json", {"status": "pending_no_official_captures", "tasks": tasks, "observed_corpus_names": observed,
               "unknown_field": "0x1400 (value byte 127/128/129 with type 1; no meaning assigned)",
               "warning": "Synthetic fixtures, hand-patched NP3 and third-party files are not manual NX single-variable export evidence."})
    print("Created pending plan and strict manifest template. No NX reference images or compatibility evidence have been created.")


def seal(args):
    value = json.loads(Path(args.template).read_text(encoding="utf-8"))
    root = Path(args.output).resolve().parent
    if not args.attest_nx_gui_export:
        raise ValueError("--attest-nx-gui-export is required after actual GUI exports; synthetic fixtures cannot be sealed as NX evidence")
    for key, path in (("source_raw", args.raw), ("baseline_np3", args.baseline_np3), ("recipe_np3", args.recipe_np3),
                      ("A_baseline", args.a), ("B_recipe", args.b)):
        value[key] = mf.file_ref(path, root)
    p = value["provenance"]
    if args.provenance_kind is not None:
        p["kind"] = args.provenance_kind
    p.update(operator=args.operator, nx_version=args.nx_version, captured_at=dt.datetime.now(dt.timezone.utc).isoformat(),
             same_raw_and_settings=True, manual_export_attested=True,
             a_uses_exported_baseline_np3=True, b_uses_recorded_recipe_np3=True,
             evidence=[mf.file_ref(path, root) for path in args.evidence])
    value["status"] = "captured"
    mf.validate(value, root)
    write_json(args.output, value)
    print("Sealed file identities and recorded GUI operator provenance. Attestation is not independent proof of vendor processing.")


def binaries(jdk):
    candidates = [Path(jdk)] if jdk else [REPO / ".tooling/jdk/jdk-17.0.20.1+1", Path("D:/dev/Java/jdk-17.0.19")]
    suffix = ".exe" if sys.platform == "win32" else ""
    for candidate in candidates:
        java, javac = candidate / ("bin/java" + suffix), candidate / ("bin/javac" + suffix)
        if java.is_file() and javac.is_file():
            return str(java), str(javac)
    java, javac = shutil.which("java"), shutil.which("javac")
    if not java or not javac:
        raise ValueError("A real JDK 17+ is required; use --jdk")
    return java, javac


def render_product(a_path, recipe, output, jdk):
    """One full-size production path shared by captured and explicit diagnostic runs."""
    from PIL import Image, PngImagePlugin
    from metrics import read_image, srgb_profile
    output = Path(output).resolve()
    if output.exists():
        raise ValueError("Prediction output already exists")
    a, profile_proof = read_image(a_path)
    if not profile_proof["verified_srgb"]:
        raise ValueError("A needs a recognized embedded sRGB ICC or PNG sRGB chunk before product rendering")
    java, javac = binaries(jdk)
    sources = sorted((REPO / "app/src/main/java/cc/huevia/ncp").glob("*.java")) + [HERE / "CalibrationRender.java"]
    if not any(p.name == "SpatialPreviewFilter.java" for p in sources):
        raise ValueError("Unified spatial renderer source is missing; refusing a point-only substitute")
    import PIL
    snapshots = [(source, source.read_bytes()) for source in sources]
    inventory = {"kind": "actual_product_sources", "files": [{"path": str(p.relative_to(REPO)).replace("\\", "/"),
                 "sha256": np3.digest(data)} for p, data in snapshots], "bridge_path": "profile.renderPixels", "A_profile": profile_proof,
                 "driver_files": [{"path": p.name, "sha256": mf.sha_file(p)} for p in (HERE / "calibrate.py", HERE / "manifest.py", HERE / "metrics.py", HERE / "np3.py", HERE / "normalize16.py", HERE / "diagnostic.py")],
                 "python": sys.version, "pillow": PIL.__version__, "prediction_codec": "lossless PNG, not Android JPEG codec"}
    output.parent.mkdir(parents=True, exist_ok=True)
    inventory_path = output.with_suffix(output.suffix + ".renderer.json")
    if inventory_path.exists():
        raise ValueError("Renderer inventory already exists")
    with tempfile.TemporaryDirectory(prefix="huevia-np3-calibration-") as scratch:
        scratch = Path(scratch)
        a.save(scratch / "A.png", icc_profile=srgb_profile())
        copied = []
        for source, data in snapshots:
            target = scratch / "source" / source.relative_to(REPO)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            copied.append(str(target))
        subprocess.run([javac, "--release", "17", "-encoding", "UTF-8", "-d", str(scratch), *copied], check=True)
        result = subprocess.run([java, "-Dfile.encoding=UTF-8", "-cp", str(scratch), "CalibrationRender", str(scratch / "A.png"),
                                str(recipe), str(scratch / "C.png")], check=True, capture_output=True, text=True, encoding="utf-8")
        model_lines = [line.removeprefix("MODEL=") for line in result.stdout.splitlines() if line.startswith("MODEL=")]
        if len(model_lines) != 1:
            raise ValueError("Renderer did not identify its model")
        with Image.open(scratch / "C.png") as c:
            c.load()
            metadata = PngImagePlugin.PngInfo()
            if profile_proof.get("diagnostic_normalized_16bit"):
                metadata.add_text("huevia.normalize16", json.dumps({"diagnostic_only": True,
                    "kind": "model_prediction_from_diagnostic_normalized_16bit", "a_sha256": mf.sha_file(a_path)}))
            with output.open("xb") as stream:
                c.convert("RGB").save(stream, format="PNG", icc_profile=srgb_profile(), pnginfo=metadata)
    a.close()
    write_json(inventory_path, inventory)
    return model_lines[0], inventory_path


def render(args):
    if args.diagnostic:
        import diagnostic
        return diagnostic.render(args, write_json, render_product)
    path = Path(args.manifest).resolve()
    value = mf.load(path)
    state = np3.baseline_state(mf.resolve(value["baseline_np3"], path.parent, "baseline_np3"))
    if not state["eligible_for_pixel_fit"]:
        print("WARNING: A is an observed/non-neutral baseline. C is diagnostic only and cannot qualify colour/spatial calibration.", file=sys.stderr)
    output, output_manifest = Path(args.output).resolve(), Path(args.output_manifest).resolve()
    if value["prediction"] is not None or output.exists() or output_manifest.exists():
        raise ValueError("Use an unpredicted captured manifest and new output paths")
    a_path = mf.resolve(value["A_baseline"], path.parent, "A_baseline")
    recipe = mf.resolve(value["recipe_np3"], path.parent, "recipe_np3")
    model_version, inventory_path = render_product(a_path, recipe, output, args.jdk)
    out_root = output_manifest.parent
    # Rebase every file reference when writing the manifest elsewhere.
    for key in ("source_raw", "baseline_np3", "recipe_np3", "A_baseline", "B_recipe"):
        value[key] = mf.file_ref(mf.resolve(value[key], path.parent, key), out_root)
    value["provenance"]["evidence"] = [mf.file_ref(mf.resolve(ref, path.parent, "evidence"), out_root) for ref in value["provenance"]["evidence"]]
    value["prediction"] = {"image": mf.file_ref(output, out_root), "renderer": {"kind": "huevia_jvm", "model_version": model_version,
        "source_sha256": mf.sha_file(inventory_path), "source_inventory": mf.file_ref(inventory_path, out_root),
        "command": subprocess.list2cmdline(sys.argv), "input_a_sha256": value["A_baseline"]["sha256"], "recipe_sha256": value["recipe_np3"]["sha256"]}}
    mf.validate(value, out_root, require_prediction=True)
    write_json(output_manifest, value)
    print("Rendered actual product colour + spatial path; C and renderer inventory were saved separately from A/B.")


def check_splits(values):
    scene_splits, raw_splits, identifiers = {}, {}, set()
    for value in values:
        if value["experiment_id"] in identifiers:
            raise ValueError("Duplicate experiment_id in evaluation cohort")
        identifiers.add(value["experiment_id"])
        for table, key in ((scene_splits, value["scene_group"]), (raw_splits, value["source_raw"]["sha256"])):
            if key in table and table[key] != value["partition"]:
                raise ValueError("Train/holdout leakage: the same RAW or scene_group appears in both partitions")
            table[key] = value["partition"]


def validate_capture(args):
    path = Path(args.manifest).resolve()
    value = mf.load(path)
    difference = np3.diff(mf.resolve(value["baseline_np3"], path.parent, "baseline_np3"),
                          mf.resolve(value["recipe_np3"], path.parent, "recipe_np3"))
    print(json.dumps({"status": "validated_file_identities_and_operator_attestation", "experiment_id": value["experiment_id"],
                      "source_raw_sha256": value["source_raw"]["sha256"], "purpose": value["purpose"],
                      "baseline_kind": value["baseline_kind"], "baseline_semantics": np3.baseline_state(mf.resolve(value["baseline_np3"], path.parent, "baseline_np3")),
                      "changed_controls": value["changed_controls"], "np3_diff": difference,
                      "single_variable_0x1400_evidence_candidate": bool(difference["isolated_0x1400_byte_pair"] and len(value["changed_controls"]) == 1),
                      "provenance_kind": value["provenance"]["kind"], "operator": value["provenance"]["operator"],
                      "notice": "GUI operator provenance is recorded, not independently proved. Unknown field semantics and product compatibility remain unchanged."},
                     indent=2, ensure_ascii=False))


def verify_baseline(args):
    state = np3.baseline_state(args.source)
    print(json.dumps(state, indent=2, ensure_ascii=False))
    if args.require_zero and not state["eligible_for_pixel_fit"]:
        raise ValueError("Baseline is not zero-adjustment; do not use it for absolute colour/spatial model acceptance")


def prepare_session(args):
    import session
    print(json.dumps(session.prepare(args, template, write_json, REPO), ensure_ascii=False, indent=2))


def observe_sidecar(args):
    import sidecar
    print(json.dumps(sidecar.observe(args, write_json), ensure_ascii=False, indent=2))


def normalize_16(args):
    import normalize16
    print(json.dumps(normalize16.normalize(args, write_json), ensure_ascii=False, indent=2))


def evaluate(args):
    if args.diagnostic:
        import diagnostic
        return diagnostic.evaluate(args, write_json)
    from metrics import read_image, compare, decision, contact_sheet
    paths = [Path(x).resolve() for x in args.manifests]
    values = [mf.load(path, require_prediction=True) for path in paths]
    check_splits(values)
    if not 1 <= args.samples <= 1000000:
        raise ValueError("--samples must be 1..1000000")
    root = Path(args.directory).resolve()
    if (root / "report.json").exists():
        raise ValueError("Report already exists; choose a new directory")
    root.mkdir(parents=True, exist_ok=True)
    reports = []
    for index, (path, value) in enumerate(zip(paths, values)):
        files = {"A": value["A_baseline"], "B": value["B_recipe"], "C": value["prediction"]["image"]}
        loaded = {key: read_image(mf.resolve(ref, path.parent, key)) for key, ref in files.items()}
        a, b, c = [loaded[key][0] for key in ("A", "B", "C")]
        proof = {key: loaded[key][1] for key in loaded}
        if any(proof[key]["decoded_format"] != value["settings"]["export"]["format"] for key in ("A", "B")):
            raise ValueError("A/B actual image formats do not match the recorded NX export format")
        baseline, prediction = compare(b, a, args.samples), compare(b, c, args.samples)
        regions = [{"name": r["name"], "kind": r["kind"], "box": r["box"],
                    "C_to_B": compare(b, c, args.samples, r["box"])} for r in value["regions"]]
        difference = np3.diff(mf.resolve(value["baseline_np3"], path.parent, "baseline_np3"),
                              mf.resolve(value["recipe_np3"], path.parent, "recipe_np3"))
        baseline_state = np3.baseline_state(mf.resolve(value["baseline_np3"], path.parent, "baseline_np3"))
        candidate = difference["isolated_0x1400_byte_pair"] and len(value["changed_controls"]) == 1
        sheet = root / f"{index + 1:03d}-comparison.png"
        contact_sheet(a, b, c, sheet)
        renderer = copy.deepcopy(value["prediction"]["renderer"])
        renderer["source_inventory"] = mf.file_ref(mf.resolve(renderer["source_inventory"], path.parent, "source inventory"), root)
        reports.append({"experiment_id": value["experiment_id"], "partition": value["partition"], "scene_group": value["scene_group"],
                        "manifest": mf.file_ref(path, root), "renderer": renderer, "image_size": list(a.size),
                        "profile_provenance": proof, "A_to_B": baseline, "C_to_B": prediction, "regions": regions,
                        "baseline_kind": value["baseline_kind"], "baseline_semantics": baseline_state,
                        "decision": decision(value, prediction, proof, baseline_state), "explicit_acceptance": value["acceptance"],
                        "np3_diff": difference, "single_variable_0x1400_evidence_candidate": bool(candidate),
                        "comparison": mf.file_ref(sheet, root)})
    train_recipes = {v["recipe_np3"]["sha256"] for v in values if v["partition"] == "train"}
    write_json(root / "report.json", {"schema": "huevia.np3-evaluation.v1", "status": "measured_not_vendor_certified",
               "sampling": "deterministic spatial grid; all clipping fractions are sampled", "max_requested_samples": args.samples,
               "holdout_unseen_recipe_experiments": sum(v["partition"] == "holdout" and v["recipe_np3"]["sha256"] not in train_recipes for v in values),
               "experiments": reports, "warning": "No fitting occurs here. Same-RAW/NX provenance is a recorded GUI operator attestation; arbitrary JPEG fidelity is not implied."})
    print(f"Measured {len(reports)} experiments. Decisions use only explicit manifest thresholds on recognized-sRGB holdout data.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("plan"); p.add_argument("directory"); p.add_argument("--purpose", choices=("field_0x1400", "flexible_color", "creative_base_0802"), default="field_0x1400"); p.add_argument("--partition", choices=("train", "holdout"), default="train"); p.set_defaults(run=plan)
    p = sub.add_parser("audit"); p.add_argument("source"); p.set_defaults(run=lambda a: print(json.dumps(np3.audit(a.source), indent=2, ensure_ascii=False)))
    p = sub.add_parser("diff"); p.add_argument("before"); p.add_argument("after"); p.set_defaults(run=lambda a: print(json.dumps(np3.diff(a.before, a.after), indent=2, ensure_ascii=False)))
    p = sub.add_parser("baseline"); p.add_argument("source"); p.add_argument("--require-zero", action="store_true"); p.set_defaults(run=verify_baseline)
    p = sub.add_parser("prepare-session"); p.add_argument("workspace")
    for name in ("raw", "raw-sha256", "nx-version"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--raw-bytes", type=int, required=True); p.set_defaults(run=prepare_session)
    p = sub.add_parser("observe-sidecar"); p.add_argument("workspace"); p.add_argument("sidecar")
    p.add_argument("--gui-report", required=True); p.add_argument("--operator", required=True)
    p.add_argument("--files", nargs="+", required=True); p.set_defaults(run=observe_sidecar)
    p = sub.add_parser("normalize16"); p.add_argument("source"); p.add_argument("--output", required=True)
    p.add_argument("--report", required=True); p.add_argument("--frame", type=int); p.add_argument("--lcms"); p.set_defaults(run=normalize_16)
    p = sub.add_parser("seal"); p.add_argument("template"); p.add_argument("output")
    for name in ("raw", "baseline-np3", "recipe-np3", "a", "b", "operator", "nx-version"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--evidence", action="append", required=True)
    p.add_argument("--provenance-kind", choices=("manual_nx_studio", "nx_studio_ui"))
    p.add_argument("--attest-nx-gui-export", "--attest-manual-nx", dest="attest_nx_gui_export", action="store_true"); p.set_defaults(run=seal)
    p = sub.add_parser("validate"); p.add_argument("manifest"); p.set_defaults(run=validate_capture)
    p = sub.add_parser("render"); p.add_argument("manifest"); p.add_argument("--output", required=True); p.add_argument("--output-manifest", required=True); p.add_argument("--jdk"); p.add_argument("--diagnostic", action="store_true"); p.set_defaults(run=render)
    p = sub.add_parser("evaluate"); p.add_argument("manifests", nargs="+"); p.add_argument("--directory", required=True); p.add_argument("--samples", type=int, default=65536); p.add_argument("--diagnostic", action="store_true"); p.set_defaults(run=evaluate)
    args = parser.parse_args(argv)
    try:
        args.run(args)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        if isinstance(error, subprocess.CalledProcessError) and error.stderr:
            print(error.stderr, file=sys.stderr)
        parser.exit(2, f"ERROR: {error}\n")


if __name__ == "__main__":
    main()

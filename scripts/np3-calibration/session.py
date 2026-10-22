"""Private inputs and pending GUI worklists only; this module never drives or claims NX exports."""
import zipfile
from pathlib import Path
import json
import manifest as mf
import np3

ARCHIVE_SHA256 = "7a1cf3cd719ed9ec69dae4936625afb1fc3cc1871bc6d61f46c27903a32a087c"
RECIPES = [
    ("u1", "24Look_24Frames", "7f01", "0020"),
    ("u2", "BlueHour_Luna", "8101", "0020"),
    ("u3", "GoldenHourGlow_DAnn", "8101", "0020"),
    ("u4", "HKReminisce_Hiuman", "8101", "0020"),
    ("u5", "PainterlyLight_DAnn", "8101", "0020"),
    ("u6", "Pastelpink_Yuki", "8101", "0020"),
    ("u7", "SunsetFilm_JKBoy", "8101", "0020"),
    ("u8", "WarmDimLight_Luna", "8101", "0020"),
    ("c1", "Bluegrain_jyota", "ff01", "0802"),
]


def prepare(args, template_factory, writer, repo):
    root = Path(args.workspace).resolve()
    raw = Path(args.raw).resolve()
    if not raw.is_file() or raw.stat().st_size != args.raw_bytes or mf.sha_file(raw) != args.raw_sha256.lower():
        raise ValueError("RAW identity does not match the explicitly supplied trusted SHA/byte count")
    archive = repo / "dist/nikon-cloud-recipes-20261018/nikon-cloud-recipes-142.zip"
    inventory_path = archive.parent / "cloud-only-inventory.json"
    if mf.sha_file(archive) != ARCHIVE_SHA256:
        raise ValueError("The fixed 142-recipe ZIP has changed; no private inputs were prepared")
    inventory = json.loads(inventory_path.read_text(encoding="utf-8"))
    if inventory["sha256"] != ARCHIVE_SHA256:
        raise ValueError("Corpus inventory/ZIP identity mismatch")
    listed = {entry["path"]: entry for entry in inventory["files"]}
    staged = []
    with zipfile.ZipFile(archive) as source:
        for alias, name, field1400, base in RECIPES:
            member = f"Nikon Creators/{name}.NP3"
            data = source.read(member)
            if np3.digest(data) != listed[member]["sha256"] or len(data) != listed[member]["bytes"]:
                raise ValueError("Original member identity mismatch: " + member)
            fields = {record["tag"]: record["value"] for record in np3.parse(data)}
            if fields[0x1400].hex() != field1400 or fields[0x300].hex() != base:
                raise ValueError("Unexpected researched field/base bytes: " + member)
            staged.append((alias, member, data, fields))
    targets = [root / "inputs-index.json", root / "sequence.json", root / "sequence.md",
               root / "manifests/quick-minus.pending.json", root / "manifests/quick-plus.pending.json",
               root / "manifests/pixel.pending.json"] + [root / f"in/{alias}.NP3" for alias, _, _, _ in staged]
    if any(target.exists() for target in targets):
        raise ValueError("A prepared session file already exists; refusing to overwrite inputs, plans or evidence")
    for directory in ("in", "out", "evidence", "manifests"):
        (root / directory).mkdir(parents=True, exist_ok=True)
    index = []
    for alias, member, data, fields in staged:
        target = root / f"in/{alias}.NP3"
        with target.open("xb") as output:
            output.write(data)
        if target.read_bytes() != data:
            raise ValueError("Private copy verification failed")
        index.append({"alias": alias, "source_zip_member": member, "source_sha256": np3.digest(data),
                      "private_file": mf.file_ref(target, root), "bytes_unchanged": True,
                      "base_hex": fields[0x300].hex(), "field_0x1400_hex": fields[0x1400].hex(),
                      "registration_name": fields[0x200].rstrip(b"\0").decode("ascii"),
                      "role": "unknown_field_research" if alias.startswith("u") else "separate_creative_base_group"})
    writer(root / "inputs-index.json", {"schema": "huevia.np3-private-inputs.v1", "status": "prepared_inputs_only_no_gui_export_claim",
        "source_raw": mf.file_ref(raw, root), "nx_version_supplied": args.nx_version,
        "source_archive": mf.file_ref(archive, root), "source_inventory": mf.file_ref(inventory_path, root),
        "presets": index, "warning": "These are unchanged third-party source recipes, not NX single-variable exports or official reference images."})
    sequence = [
        {"id": "q0", "np3": "out/q0.NP3", "action": "Reset Flexible Color; set Quick sharp=0 and record actual sharpening/mid/clarity separately (observed NX may show 2/.5/1). Export the observed baseline unchanged.", "compare_to": None},
        {"id": "qm", "np3": "out/qm.NP3", "action": "From q0 state change ONLY Quick sharp 0 to -1. Record all three resulting spatial values. Export.", "compare_to": "q0"},
        {"id": "qp", "np3": "out/qp.NP3", "action": "Restore q0, then change ONLY Quick sharp 0 to +1. Record all three resulting spatial values. Export.", "compare_to": "q0"},
        {"id": "qr", "np3": "out/qr.NP3", "action": "Restore Quick sharp=0 and the complete q0 state. Export the repeat to check reversibility/metadata noise.", "compare_to": "q0"},
        {"id": "s1", "np3": "out/s1.NP3", "action": "Restore q0. Manually change ONLY Sharpening by +0.25 (e.g. 2 to 2.25); keep mid/clarity at q0 values. Export. Record what Quick sharp displays without adjusting it.", "compare_to": "q0"},
        {"id": "m1", "np3": "out/m1.NP3", "action": "Restore q0. Manually change ONLY Mid-range sharpening by +0.25 (e.g. .5 to .75); keep sharpening/clarity at q0. Export.", "compare_to": "q0"},
        {"id": "l1", "np3": "out/l1.NP3", "action": "Restore q0. Manually change ONLY Clarity by +0.25 (e.g. 1 to 1.25); keep sharpening/mid at q0. Export.", "compare_to": "q0"},
        {"id": "z0", "np3": "out/z0.NP3", "image": "out/A0.tif", "action": "Restore neutral colour adjustments, custom curve OFF. Manually set sharpening/mid/clarity INDIVIDUALLY to 0/0/0. Do not touch Quick sharp afterward. Export NP3; require-zero preflight must pass before exporting the zero A image.", "compare_to": "q0"},
    ]
    optional = [{"id": "mm", "np3": "out/mm.NP3", "action": "If Quick sharp changes 0x1400 together with the three spatial fields: restore q0, then manually set the three spatial values to exactly the observed qm values without operating Quick sharp. Compare qm vs mm."},
                {"id": "mp", "np3": "out/mp.NP3", "action": "Repeat the manual matched-triple control using qp's three observed values. Compare qp vs mp. Do not infer 0x1400 meaning from correlation alone."}]
    writer(root / "sequence.json", {"status": "planned_not_executed", "nx_version_supplied": args.nx_version,
        "planned_gui_operator": "Codex computer-use", "provenance_kind": "nx_studio_ui", "steps": sequence,
        "optional_disambiguation": optional, "all_same_scene_group": raw.stem,
        "raw_settings": "Fix WB/tint/exposure/ADL/noise/lens/geometry/export settings; record actual UI values and screenshots. One RAW is training evidence only, not holdout."})
    lines = ["# Pending NX GUI sequence", "", "Operator: Codex computer-use; provenance kind: nx_studio_ui.",
             "This is a worklist, not evidence that NX exports have occurred.", "", "| Step | Short NP3 path | Planned operation |", "|---|---|---|"]
    lines += [f"| {step['id']} | {step['np3']} | {step['action']} |" for step in sequence]
    lines += ["", "Optional matched-triple controls:", ""] + [f"- {step['np3']}: {step['action']}" for step in optional]
    lines += ["", "For pixel fitting: out/z0.NP3 and out/A0.tif are the neutral baseline; q0 is NOT a zero-adjustment baseline.",
              "8 unknown originals: in/u1.NP3 .. in/u8.NP3. Creative original: in/c1.NP3 (separate group).",
              "Preserve screenshots/logs under evidence/. Never seal pending drafts until actual matching GUI exports exist."]
    with (root / "sequence.md").open("x", encoding="utf-8", newline="\n") as output:
        output.write("\n".join(lines) + "\n")
    for filename, purpose, control in (("quick-minus.pending.json", "field_0x1400", "-1"),
                                      ("quick-plus.pending.json", "field_0x1400", "+1"),
                                      ("pixel.pending.json", "flexible_color", None)):
        value = template_factory(purpose, "train")
        value["source_raw"] = mf.file_ref(raw, root / "manifests")
        value.update(experiment_id=raw.stem + "-" + filename.removesuffix(".pending.json"), scene_group=raw.stem)
        value["provenance"].update(kind="nx_studio_ui", operator="Codex computer-use", nx_version=args.nx_version)
        if control is not None:
            value["changed_controls"] = [{"ui_label": "Quick sharp (planned; verify exact GUI label)", "before": "0", "after": control}]
        writer(root / "manifests" / filename, value)
    return {"workspace": str(root), "source_raw_sha256": args.raw_sha256.lower(), "private_presets": len(index),
            "status": "prepared_only", "inputs_index": str(root / "inputs-index.json"), "sequence": str(root / "sequence.md")}

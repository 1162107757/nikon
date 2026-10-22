"""Read-only NP3 TLV inspection. Unknown records are data, never guessed controls."""
import hashlib
import struct
import zipfile
from collections import Counter, defaultdict
from pathlib import Path

MAX_BYTES = 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_bytes(reference):
    if "::" in str(reference):
        archive, member = str(reference).split("::", 1)
        with zipfile.ZipFile(archive) as source:
            info = source.getinfo(member)
            if info.file_size > MAX_BYTES:
                raise ValueError("NP3 member exceeds 1 MiB")
            with source.open(info) as stream:
                return stream.read(MAX_BYTES + 1)
    with open(reference, "rb") as source:
        return source.read(MAX_BYTES + 1)


def parse(data):
    if not 16 <= len(data) <= MAX_BYTES or data[:4] != b"NCP\0":
        raise ValueError("Not a bounded NP3 container")
    records, offset, seen = [], 4, set()
    while offset < len(data):
        if len(data) - offset < 4:
            raise ValueError("Truncated record ID")
        tag = struct.unpack_from(">I", data, offset)[0]
        offset += 4
        if tag == 0:
            if offset != len(data):
                raise ValueError("Data after terminator")
            if not records or records[0]["tag"] != 0x100 or len(records[0]["value"]) != 4:
                raise ValueError("Missing first NP3 version record")
            return records
        if len(data) - offset < 4:
            raise ValueError("Truncated record length")
        length = struct.unpack_from(">I", data, offset)[0]
        offset += 4
        if length > len(data) - offset or tag in seen:
            raise ValueError("Truncated payload or duplicate record")
        seen.add(tag)
        records.append({"tag": tag, "offset": offset, "value": data[offset:offset + length]})
        offset += length
    raise ValueError("Missing terminator")


def describe(record):
    data = record["value"]
    result = {"tag": f"0x{record['tag']:04x}", "offset": record["offset"],
              "bytes": len(data), "sha256": digest(data), "hex": data[:64].hex()}
    if len(data) == 2:
        result.update(value_byte=data[0], type_byte=data[1], uint16_be=int.from_bytes(data, "big"))
    if record["tag"] in (0x100, 0x200):
        result["ascii"] = data.rstrip(b"\0").decode("ascii", errors="replace")
    return result


def inspect(reference):
    data = read_bytes(reference)
    return {"source": str(reference), "sha256": digest(data), "bytes": len(data),
            "records": [describe(record) for record in parse(data)]}


def baseline_state(reference):
    """Effective zero-adjustment Flexible baseline, independent of NX's Reset/Quick sharp labels."""
    records = parse(read_bytes(reference))
    fields = {record["tag"]: record["value"] for record in records}
    reasons = []

    def exact(index, expected, label):
        if fields.get(index * 256) != expected:
            reasons.append(f"{label}: record 0x{index * 256:04x} is not the required zero/inactive value")

    exact(1, b"0310", "version")
    exact(3, b"\0\x20", "Flexible base")
    exact(4, b"\0\0", "modification state")
    exact(5, b"\xff\x01", "adjustment state")
    spatial = {}
    for index, name in ((6, "sharpening"), (22, "mid_range_sharpening"), (7, "clarity")):
        data = fields.get(index * 256, b"")
        spatial[name] = (data[0] - 128) / 4 if len(data) == 2 and data[1] == 4 else None
        exact(index, b"\x80\x04", name)
    for index in (8, 9, 10, 11, 14, 23, 24):
        exact(index, b"\xff\x04", "inactive legacy adjustment")
    for index in (12, 13):
        exact(index, b"\xff\0", "inactive monochrome adjustment")
    for index in range(15, 20):
        exact(index, b"\xff\x01", "inactive legacy colour")
    if fields.get(0x1400) not in (b"\x80\x01", b"\xff\x01"):
        reasons.append("Unknown 0x1400 is not the observed neutral/inactive value")
    exact(21, b"\xff\x0a", "inactive Creative strength")
    for index in range(25, 31):
        exact(index, b"\x80\x01", "zero basic tone/saturation")
    exact(31, bytes([128] * 24 + [1, 1, 1, 0]), "zero Color Blender")
    grade = fields.get(0x2000, b"")
    if len(grade) != 20 or grade[12:16] != b"\x01\x01\x01\0":
        reasons.append("Invalid Color Grading structure")
    else:
        for zone in range(3):
            offset = zone * 4
            hue = ((grade[offset] & 15) << 8) | grade[offset + 1]
            if grade[offset] & 0xf0 != 0x80 or hue > 360 or grade[offset + 2:offset + 4] != b"\x80\x80":
                reasons.append(f"Color Grading zone {zone} is not a valid zero-chroma/zero-brightness adjustment")
        if grade[17] != 1 or grade[19] != 1 or not 128 <= grade[16] <= 228 or not 28 <= grade[18] <= 228:
            reasons.append("Invalid inactive Grading blending/balance encoding")
    known = {i * 256 for i in range(1, 33)} | {0x10100}
    if 2 in fields:
        reasons.append("A zero-adjustment baseline must have custom tone curve disabled, even if its samples appear linear")
    if any(tag not in known and tag != 2 for tag in fields):
        reasons.append("Unresearched extra adjustment record in baseline")
    return {"eligible_for_pixel_fit": not reasons, "spatial_ui_values": spatial,
            "has_custom_curve": 2 in fields, "reasons": reasons,
            "definition": "Flexible 0020, zero tone/saturation/Blender, zero Grading chroma/brightness, sharpening/mid/clarity individually zero; no custom curve"}


def diff(left, right):
    a, b = read_bytes(left), read_bytes(right)
    a_records, b_records = parse(a), parse(b)
    aa, bb = {r["tag"]: r for r in a_records}, {r["tag"]: r for r in b_records}
    changes = []
    for tag in sorted(aa.keys() | bb.keys()):
        av, bv = aa.get(tag), bb.get(tag)
        if av is not None and bv is not None and av["value"] == bv["value"]:
            continue
        offsets = []
        if av is not None and bv is not None:
            for i in range(max(len(av["value"]), len(bv["value"]))):
                x = av["value"][i] if i < len(av["value"]) else None
                y = bv["value"][i] if i < len(bv["value"]) else None
                if x != y:
                    offsets.append({"payload_offset": i, "before": x, "after": y})
        changes.append({"tag": f"0x{tag:04x}", "before": describe(av) if av else None,
                        "after": describe(bv) if bv else None, "byte_changes": offsets})
    effective = [r["tag"] for r in changes if r["tag"] not in ("0x0200", "0x10100")]
    structures = [[(r["tag"], len(r["value"])) for r in records if r["tag"] not in (0x200, 0x10100)]
                  for records in (a_records, b_records)]
    structure_changed = structures[0] != structures[1]
    base_a, base_b = aa.get(0x300), bb.get(0x300)
    values = [aa.get(0x1400), bb.get(0x1400)]
    candidate = (not structure_changed and effective == ["0x1400"] and base_a and base_b
                 and base_a["value"] == base_b["value"] == b"\0\x20"
                 and all(v and v["value"] in (b"\x7f\x01", b"\x80\x01", b"\x81\x01") for v in values))
    return {"before": {"source": str(left), "sha256": digest(a)},
            "after": {"source": str(right), "sha256": digest(b)}, "changes": changes,
            "record_order_before": [f"0x{r['tag']:04x}" for r in a_records],
            "record_order_after": [f"0x{r['tag']:04x}" for r in b_records],
            "non_metadata_structure_changed": structure_changed,
            "non_metadata_changed_tags": effective, "isolated_0x1400_byte_pair": bool(candidate),
            "interpretation": "Byte evidence only. UI provenance and repeatable independent NX exports are required; no control meaning is inferred."}


def audit(reference):
    path = Path(reference)
    members = []
    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path) as source:
            members = [f"{path}::{x.filename}" for x in source.infolist()
                       if not x.is_dir() and x.filename.lower().endswith(".np3")]
    else:
        return inspect(reference)
    counts, examples, errors = defaultdict(Counter), defaultdict(lambda: defaultdict(list)), []
    for member in members:
        try:
            for record in parse(read_bytes(member)):
                key, value = f"0x{record['tag']:04x}", record["value"]
                encoded = value.hex() if len(value) <= 32 else "sha256:" + digest(value)
                counts[key][encoded] += 1
                examples[key][encoded].append(member.split("::", 1)[1])
        except (ValueError, OSError, zipfile.BadZipFile) as error:
            errors.append({"source": member, "error": str(error)})
    fields = {key: [{"hex_or_sha256": value, "count": count,
                     "examples": examples[key][value] if key in ("0x0300", "0x1400") else examples[key][value][:3]}
                    for value, count in sorted(values.items())] for key, values in sorted(counts.items())}
    return {"source": str(path), "archive_sha256": digest(path.read_bytes()), "np3_members": len(members),
            "errors": errors, "fields": fields,
            "notice": "0x1400 remains unknown; 0x0802 Creative bases are a separate experiment group."}

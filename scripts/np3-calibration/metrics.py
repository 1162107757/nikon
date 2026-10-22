"""Deterministic sampled SDR comparisons. No vendor or default pass thresholds."""
import io
import math
import json
from pathlib import Path
from PIL import Image, ImageCms, ImageDraw


def srgb_profile():
    return ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()


def read_image(path):
    with open(path, "rb") as source:
        header = source.read(25)
    if header[:8] == b"\x89PNG\r\n\x1a\n" and (len(header) < 25 or header[24] != 8):
        raise ValueError(f"{path}: only 8-bit PNG is supported")
    with Image.open(path) as image:
        image.load()
        if image.mode != "RGB":
            raise ValueError(f"{path}: expected explicit 8-bit RGB, got {image.mode}")
        if getattr(image, "bits", 8) != 8:
            raise ValueError(f"{path}: only 8-bit image samples are supported")
        if image.getexif().get(274, 1) != 1:
            raise ValueError(f"{path}: normalize orientation before capture")
        if image.format == "TIFF" and any(x != 8 for x in image.tag_v2.get(258, (8, 8, 8))):
            raise ValueError("Only 8-bit TIFF RGB is supported by this current-model benchmark")
        proof = {"verified_srgb": False, "source": "manifest declaration only; embedded space unknown"}
        embedded = image.info.get("icc_profile")
        if embedded:
            profile = ImageCms.ImageCmsProfile(io.BytesIO(embedded))
            description = ImageCms.getProfileDescription(profile).strip()
            if "srgb" not in description.lower().replace(" ", ""):
                raise ValueError(f"{path}: embedded profile is not identified as sRGB: {description}")
            normalized = ImageCms.profileToProfile(image, profile, ImageCms.createProfile("sRGB"), outputMode="RGB")
            proof = {"verified_srgb": True, "source": "embedded ICC converted through LittleCMS", "description": description}
        elif image.format == "PNG" and "srgb" in image.info:
            normalized = image.copy()
            proof = {"verified_srgb": True, "source": "PNG sRGB chunk", "intent": image.info["srgb"]}
        else:
            normalized = image.copy()
            if image.getexif().get(40961) == 1:
                proof["source"] = "EXIF sRGB declaration only; no embedded ICC"
        proof.update(decoded_format=image.format, pixel_mode=image.mode)
        if "huevia.normalize16" in image.info:
            proof["diagnostic_normalized_16bit"] = True
            try:
                proof["diagnostic_normalization"] = json.loads(image.info["huevia.normalize16"])
            except (ValueError, TypeError):
                proof["diagnostic_normalization"] = "unparsed diagnostic provenance marker"
        return normalized, proof


def lab(rgb):
    # IEC sRGB inverse transfer and D65 XYZ reference white, consistently used for both sides.
    channels = [x / 255.0 for x in rgb]
    r, g, b = [x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4 for x in channels]
    xyz = ((.4124564 * r + .3575761 * g + .1804375 * b) / .95047,
           .2126729 * r + .7151522 * g + .0721750 * b,
           (.0193339 * r + .1191920 * g + .9503041 * b) / 1.08883)
    f = [x ** (1 / 3) if x > (6 / 29) ** 3 else x / (3 * (6 / 29) ** 2) + 4 / 29 for x in xyz]
    return 116 * f[1] - 16, 500 * (f[0] - f[1]), 200 * (f[1] - f[2])


def de00(one, two):
    """CIEDE2000, kL=kC=kH=1; validated against Sharma's published reference pairs."""
    l1, a1, b1 = one
    l2, a2, b2 = two
    c1, c2 = math.hypot(a1, b1), math.hypot(a2, b2)
    cp = ((c1 + c2) / 2) ** 7
    factor = .5 * (1 - math.sqrt(cp / (cp + 25 ** 7)))
    ap1, ap2 = (1 + factor) * a1, (1 + factor) * a2
    c1, c2 = math.hypot(ap1, b1), math.hypot(ap2, b2)
    h1 = math.degrees(math.atan2(b1, ap1)) % 360 if c1 else 0
    h2 = math.degrees(math.atan2(b2, ap2)) % 360 if c2 else 0
    dl, dc = l2 - l1, c2 - c1
    dh = h2 - h1
    if c1 * c2 == 0:
        dh = 0
    elif dh > 180:
        dh -= 360
    elif dh < -180:
        dh += 360
    dh = 2 * math.sqrt(c1 * c2) * math.sin(math.radians(dh / 2))
    lm, cm = (l1 + l2) / 2, (c1 + c2) / 2
    if c1 * c2 == 0:
        hm = h1 + h2
    elif abs(h1 - h2) <= 180:
        hm = (h1 + h2) / 2
    elif h1 + h2 < 360:
        hm = (h1 + h2 + 360) / 2
    else:
        hm = (h1 + h2 - 360) / 2
    t = (1 - .17 * math.cos(math.radians(hm - 30)) + .24 * math.cos(math.radians(2 * hm))
         + .32 * math.cos(math.radians(3 * hm + 6)) - .20 * math.cos(math.radians(4 * hm - 63)))
    sl = 1 + .015 * (lm - 50) ** 2 / math.sqrt(20 + (lm - 50) ** 2)
    sc, sh = 1 + .045 * cm, 1 + .015 * cm * t
    rt = -2 * math.sqrt(cm ** 7 / (cm ** 7 + 25 ** 7)) * math.sin(math.radians(60 * math.exp(-((hm - 275) / 25) ** 2)))
    x, y, z = dl / sl, dc / sc, dh / sh
    return math.sqrt(max(0, x * x + y * y + z * z + rt * y * z))


def percentile(values, p):
    values = sorted(values)
    index = (len(values) - 1) * p
    lower = int(index)
    return values[lower] + (values[min(lower + 1, len(values) - 1)] - values[lower]) * (index - lower)


def compare(reference, candidate, limit=65536, box=(0, 0, 1, 1)):
    if reference.size != candidate.size:
        raise ValueError("Images must have identical full-size geometry; no alignment or rescaling is silently performed")
    width, height = reference.size
    x0, y0 = int(box[0] * width), int(box[1] * height)
    x1, y1 = max(x0 + 1, int(box[2] * width)), max(y0 + 1, int(box[3] * height))
    region_width, region_height = x1 - x0, y1 - y0
    columns = min(region_width, limit, max(1, int(math.sqrt(limit * region_width / region_height))))
    rows = min(region_height, max(1, limit // columns))
    step_x, step_y = math.ceil(region_width / columns), math.ceil(region_height / rows)
    aa, bb = reference.load(), candidate.load()
    errors, luma_error, rgb_error, ref_clip, candidate_clip, new_clip = [], 0.0, 0.0, 0, 0, 0
    for y in range(y0, y1, step_y):
        for x in range(x0, x1, step_x):
            a, b = aa[x, y], bb[x, y]
            errors.append(de00(lab(a), lab(b)))
            luma_error += (.2126 * (a[0] - b[0]) + .7152 * (a[1] - b[1]) + .0722 * (a[2] - b[2])) ** 2
            rgb_error += sum(abs(p - q) for p, q in zip(a, b)) / 3
            ref_clip += any(v in (0, 255) for v in a)
            candidate_clip += any(v in (0, 255) for v in b)
            new_clip += any(q in (0, 255) and p not in (0, 255) for p, q in zip(a, b))
    count = len(errors)
    return {"sampled_pixels": count, "grid_step": [step_x, step_y], "median_de00": percentile(errors, .5),
            "p95_de00": percentile(errors, .95), "mean_de00": sum(errors) / count,
            "encoded_luma_rmse_8bit": math.sqrt(luma_error / count), "rgb_mae_8bit": rgb_error / count,
            "reference_clip_fraction": ref_clip / count, "candidate_clip_fraction": candidate_clip / count,
            "new_clip_fraction": new_clip / count}


def decision(manifest, metrics, proof, baseline=None):
    if any(item.get("diagnostic_normalized_16bit") for item in proof.values()):
        return "diagnostic_only_normalized_16bit"
    if manifest["acceptance"] is None:
        return "report_only_no_thresholds"
    if manifest["partition"] != "holdout":
        return "report_only_training_split"
    if (manifest["baseline_kind"] != "neutral_zero_adjustment" or baseline is None
            or not baseline["eligible_for_pixel_fit"]):
        return "indeterminate_non_neutral_baseline"
    if not all(p["verified_srgb"] for p in proof.values()):
        return "indeterminate_unknown_color_space"
    bounds = manifest["acceptance"]
    passed = (metrics["median_de00"] <= bounds["median_de00_max"]
              and metrics["p95_de00"] <= bounds["p95_de00_max"]
              and metrics["new_clip_fraction"] <= bounds["new_clip_fraction_max"])
    return "meets_explicit_internal_thresholds" if passed else "outside_explicit_internal_thresholds"


def contact_sheet(a, b, c, target, labels=("A: NX baseline", "B: NX recipe", "C: Huevia model")):
    panels = []
    for label, source in zip(labels, (a, b, c)):
        copy = source.copy()
        copy.thumbnail((480, 480))
        panel = Image.new("RGB", (480, 512), "#202124")
        ImageDraw.Draw(panel).text((12, 10), label, fill="white")
        panel.paste(copy, ((480 - copy.width) // 2, 30 + (480 - copy.height) // 2))
        panels.append(panel)
    sheet = Image.new("RGB", (480 * 3, 512), "#202124")
    for i, panel in enumerate(panels):
        sheet.paste(panel, (480 * i, 0))
    sheet.save(target, icc_profile=srgb_profile())

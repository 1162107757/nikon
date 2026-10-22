package cc.huevia.ncp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

/** Behaviour and malformed-container tests for our approximation, not Nikon colour fidelity. */
public final class Np3PreviewProfileTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        pointColourIsIndependentOfSpatialValues();
        verifiedQuickSharpStatesUseStoredSpatialAmountsExactlyOnce();
        spatialValuesActuallyAffectImageRendering();
        curveInterpolationUsesSamplesAndIsNotAppliedTwice();
        basicToneAndSaturationAffectPixels();
        contrastUsesSharedInputLumaAndKeepsGrayBoundsAndAlpha();
        blenderBrightnessUsesOriginalInputSaturation();
        curveToneIsRetainedWithSourceWeightedBrightnessAndExtremeEdits();
        allEightColorBlenderAnchorsAndHueWrapWork();
        gradingZonesBrightnessBalanceAndBlendingAffectPixels();
        commentsDoNotMoveOrChangeCurveRendering();
        ownershipAlphaRepeatabilityAndBounds();
        malformedUnsupportedAndAmbiguousFieldsFail();
        System.out.println("PASS: " + checks + " NP3 phone approximation checks");
    }

    private static void verifiedQuickSharpStatesUseStoredSpatialAmountsExactlyOnce() throws Exception {
        int width = 61, height = 47;
        int[] input = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int gray = 128 + (int) Math.round(41 * Math.sin(x * .27) + 29 * Math.sin(y * .39));
            input[y * width + x] = ((x * 5 + y * 7) & 255) << 24 | gray * 0x010101;
        }
        for (int[] tuple : new int[][] {{127, 128, 128, 128}, {129, 142, 134, 140}}) {
            byte[] bytes = identityNp3();
            field(bytes, 20, 0, tuple[0]); field(bytes, 6, 0, tuple[1]);
            field(bytes, 7, 0, tuple[2]); field(bytes, 22, 0, tuple[3]);
            byte[] before = bytes.clone();
            Np3PreviewProfile linked = Np3PreviewProfile.fromNp3(bytes);
            near((tuple[1] - 128) / 4.0, linked.sharpening());
            near((tuple[2] - 128) / 4.0, linked.clarity());
            near((tuple[3] - 128) / 4.0, linked.midRangeSharpening());
            byte[] manualBytes = bytes.clone(); field(manualBytes, 20, 0, 255);
            Np3PreviewProfile manual = Np3PreviewProfile.fromNp3(manualBytes);
            int[] linkedOutput = linked.render(input, width, height);
            equal(linkedOutput, manual.render(input, width, height));
            check(Arrays.equals(bytes, before), "Verified Quick sharp parse/render preserves original preset bytes");
            if (tuple[0] == 127) equal(input, linkedOutput);
            else check(!Arrays.equals(input, linkedOutput), "Quick sharp +1 uses the stored non-zero spatial amounts");
            for (int spatial : new int[] {6, 7, 22}) {
                for (int delta : new int[] {-1, 1}) {
                    byte[] mismatch = before.clone();
                    field(mismatch, spatial, 0, (mismatch[fieldOffset(mismatch, spatial)] & 255) + delta);
                    reject(mismatch);
                }
            }
            for (int state : new int[] {0, 1, 126, 130, 254}) {
                byte[] unknown = before.clone(); field(unknown, 20, 0, state); reject(unknown);
            }
            for (int type : new int[] {0, 4, 255}) {
                byte[] wrongType = before.clone(); field(wrongType, 20, 1, type); reject(wrongType);
            }
        }
        // Existing neutral/inactive states stay permissive for independently stored
        // manual spatial amounts; only newly added non-neutral states require tuples.
        for (int state : new int[] {128, 255}) {
            byte[] manual = identityNp3(); field(manual, 20, 0, state);
            field(manual, 6, 0, 151); field(manual, 7, 0, 117); field(manual, 22, 0, 144);
            Np3PreviewProfile profile = Np3PreviewProfile.fromNp3(manual);
            near(5.75, profile.sharpening()); near(-2.75, profile.clarity()); near(4, profile.midRangeSharpening());
        }
    }

    private static void pointColourIsIndependentOfSpatialValues() throws Exception {
        byte[] bytes = identityNp3();
        Np3PreviewProfile profile = Np3PreviewProfile.fromNp3(bytes);
        equal("HOST NP3", profile.registrationName());
        equal(Np3PreviewProfile.MODEL_VERSION, profile.modelVersion());
        check(!profile.hasCustomCurve(), "Basic NP3 has no stored curve");
        near(2, profile.sharpening()); near(.5, profile.clarity()); near(1, profile.midRangeSharpening());
        Random random = new Random(147L);
        for (int i = 0; i < 4000; i++) {
            int pixel = random.nextInt(); equal(pixel, profile.applyArgb(pixel));
        }
        for (int value = 0; value < 256; value++) near(value / 255.0, profile.mapTone(value / 255.0));
        field(bytes, 6, 0, 164); field(bytes, 7, 0, 108); field(bytes, 22, 0, 148);
        Np3PreviewProfile other = Np3PreviewProfile.fromNp3(bytes);
        near(9, other.sharpening()); near(-5, other.clarity()); near(5, other.midRangeSharpening());
        for (int pixel : new int[] {0, -1, 0x019ac173, 0xff80501a, 0x80405060})
            equal(profile.applyArgb(pixel), other.applyArgb(pixel));
        field(bytes, 20, 0, 255); // The inactive marker is also accepted as neutral metadata.
        equal(0xff335577, Np3PreviewProfile.fromNp3(bytes).applyArgb(0xff335577));
    }

    private static void spatialValuesActuallyAffectImageRendering() throws Exception {
        int width = 67, height = 53;
        int[] input = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int value = 128 + (int) Math.round(45 * Math.sin(x * .19) + 24 * Math.sin(y * .43));
            input[y * width + x] = ((x * 7 + y * 3) & 255) << 24 | value * 0x010101;
        }
        int[] before = input.clone();
        byte[] neutral = identityNp3();
        for (int field : new int[] {6, 7, 22}) field(neutral, field, 0, 128);
        equal(input, Np3PreviewProfile.fromNp3(neutral).render(input, width, height));
        for (int field : new int[] {6, 7, 22}) {
            byte[] changed = neutral.clone(); field(changed, field, 0, field == 6 ? 164 : 148);
            Np3PreviewProfile profile = Np3PreviewProfile.fromNp3(changed);
            int[] output = profile.render(input, width, height);
            check(!Arrays.equals(input, output), "Spatial parameter " + field + " changes actual textured image pixels");
            for (int i = 0; i < output.length; i++) equal(input[i] >>> 24, output[i] >>> 24);
            equal(output, profile.render(input, width, height));
            equal(before, input);
        }
    }

    private static void curveInterpolationUsesSamplesAndIsNotAppliedTwice() throws Exception {
        byte[] curve = identityCurve();
        sample(curve, 0, 1024); sample(curve, 1, 3072); sample(curve, 256, 30000);
        sample(curve, 128, 8192); sample(curve, 129, 24576);
        byte[] bytes = withCurve(curve, false);
        Np3PreviewProfile profile = Np3PreviewProfile.fromNp3(bytes);
        check(profile.hasCustomCurve(), "Curve presence is exposed");
        near(1024 / 32767.0, profile.mapTone(0)); near(30000 / 32767.0, profile.mapTone(1));
        near(2048 / 32767.0, profile.mapTone(1 / 512.0));
        near(12288 / 32767.0, profile.mapTone(128.25 / 256.0));
        check(profile.mapTone(.5) < profile.mapTone(127 / 256.0), "Legal non-monotone raw samples are retained");
        byte[] metadata = bytes.clone();
        int offset = curveOffset(metadata);
        metadata[offset + 2] = 12; metadata[offset + 3] = (byte) 240;
        metadata[offset + 4] = 8; metadata[offset + 5] = (byte) 220;
        metadata[offset + 6] = 2; metadata[offset + 7] = 50;
        Np3PreviewProfile other = Np3PreviewProfile.fromNp3(metadata);
        for (int value = 0; value <= 256; value++) near(profile.mapTone(value / 256.0), other.mapTone(value / 256.0));
        for (int i = 0; i <= 256; i++) sample(curve, i, 32767 - (int) Math.round(i * 32767 / 256.0));
        equal(0xffdfbf7f, Np3PreviewProfile.fromNp3(withCurve(curve, false)).applyArgb(0xff204080));
        byte[] identity = withCurve(identityCurve(), false);
        for (int value = 0; value < 256; value++) {
            int pixel = 0xff000000 | value << 16 | (255 - value) << 8 | ((value * 17) & 255);
            equal(pixel, Np3PreviewProfile.fromNp3(identity).applyArgb(pixel));
        }
    }

    private static void basicToneAndSaturationAffectPixels() throws Exception {
        for (int index = 25; index <= 29; index++) {
            byte[] bytes = identityNp3(); field(bytes, index, 0, 178);
            Np3PreviewProfile positive = Np3PreviewProfile.fromNp3(bytes);
            field(bytes, index, 0, 78);
            Np3PreviewProfile negative = Np3PreviewProfile.fromNp3(bytes);
            double position = index == 25 ? .75 : .5;
            check(positive.mapTone(position) > negative.mapTone(position), "Tone field " + index + " has an independent visible effect");
        }
        byte[] muted = identityNp3(); field(muted, 30, 0, 28); // Saturation -100.
        Np3PreviewProfile profile = Np3PreviewProfile.fromNp3(muted);
        equal(0xffff0000, Np3PreviewProfile.fromNp3(identityNp3()).applyArgb(0xffff0000));
        equal(0xff808080, profile.applyArgb(0xffff0000));
        for (int gray = 0; gray < 256; gray++) {
            int pixel = 0xff000000 | gray << 16 | gray << 8 | gray;
            equal(pixel, profile.applyArgb(pixel));
        }
        for (int value = 28; value <= 228; value += 200) {
            byte[] extreme = identityNp3();
            for (int index = 25; index <= 30; index++) field(extreme, index, 0, value);
            Np3PreviewProfile bounded = Np3PreviewProfile.fromNp3(extreme);
            for (int i = 0; i <= 1000; i++) {
                double mapped = bounded.mapTone(i / 1000.0);
                check(Double.isFinite(mapped) && mapped >= 0 && mapped <= 1, "Extreme supported tone values remain bounded");
            }
        }
    }

    private static void contrastUsesSharedInputLumaAndKeepsGrayBoundsAndAlpha() throws Exception {
        for (int contrast : new int[] {-100, 100}) {
            byte[] bytes = identityNp3(); field(bytes, 25, 0, 128 + contrast);
            Np3PreviewProfile profile = Np3PreviewProfile.fromNp3(bytes);
            int previous = -1;
            for (int gray = 0; gray < 256; gray++) {
                int rgb = gray * 0x010101;
                int result = profile.applyArgb(0xff000000 | rgb);
                check(red(result) == green(result) && green(result) == blue(result), "Contrast retains the gray axis");
                check(red(result) >= previous, "Extreme contrast remains monotone across all gray levels");
                previous = red(result);
                equal((int)Math.round(profile.mapTone(gray / 255.0) * 255), red(result));
                for (int alpha : new int[] {0, 1, 127, 255})
                    equal((alpha << 24) | (result & 0xffffff), profile.applyArgb((alpha << 24) | rgb));
            }
            equal(0xff000000, profile.applyArgb(0xff000000));
            equal(0xffffffff, profile.applyArgb(0xffffffff));
            for (int rgb : new int[] {0xff507080, 0xff80a090, 0xffb09080}) {
                int result = profile.applyArgb(rgb);
                int dr = red(result) - red(rgb), dg = green(result) - green(rgb), db = blue(result) - blue(rgb);
                check(dr != 0, "Contrast changes a non-midpoint chromatic input");
                check(Math.abs(dr - dg) <= 1 && Math.abs(dr - db) <= 1,
                        "Unclipped chromatic contrast uses a common RGB offset rather than independent channel slopes");
            }
        }
    }

    private static void blenderBrightnessUsesOriginalInputSaturation() throws Exception {
        for (int amount : new int[] {-20, 20}) {
            byte[] bytes = identityNp3(); setBlenderBrightness(bytes, amount);
            Np3PreviewProfile profile = Np3PreviewProfile.fromNp3(bytes);
            equal(0xff808080, profile.applyArgb(0xff808080));
            equal(0xff818080, profile.applyArgb(0xff818080));
            equal(0xff000000, profile.applyArgb(0xff000000));
            equal(0xffffffff, profile.applyArgb(0xffffffff));
            equal(amount > 0 ? 0xffff3333 : 0xffcc0000, profile.applyArgb(0xffff0000));
            for (int alpha : new int[] {0, 1, 127, 255}) {
                int result = profile.applyArgb((alpha << 24) | 0x00b64f20);
                equal(alpha, result >>> 24);
                equal(profile.applyArgb(0xffb64f20) & 0xffffff, result & 0xffffff);
            }
        }
        // The source has HSL saturation 0.2. Changing a later saturation control
        // must not change its brightness gain, even when it becomes achromatic.
        int input = 0xff996666;
        for (int saturation : new int[] {-100, 0, 100}) {
            byte[] plain = identityNp3(); field(plain, 30, 0, 128 + saturation);
            byte[] bright = plain.clone(); setBlenderBrightness(bright, 20);
            int before = Np3PreviewProfile.fromNp3(plain).applyArgb(input);
            int after = Np3PreviewProfile.fromNp3(bright).applyArgb(input);
            double gain = lightness8(after) - lightness8(before);
            check(gain >= 4 && gain <= 6, "Blender brightness weight comes from the input before global saturation");
        }
        byte[] desaturated = identityNp3(); field(desaturated, 30, 0, 28); setBlenderBrightness(desaturated, 20);
        equal(0xff999999, Np3PreviewProfile.fromNp3(desaturated).applyArgb(0xffff0000));
    }

    private static void curveToneIsRetainedWithSourceWeightedBrightnessAndExtremeEdits() throws Exception {
        byte[] curve = identityCurve();
        for (int i = 0; i <= 256; i++) sample(curve, i, (int)Math.round((.25 + .5 * i / 256.0) * 32767));
        byte[] plain = withCurve(curve, false), bright = plain.clone(); setBlenderBrightness(bright, 20);
        Np3PreviewProfile base = Np3PreviewProfile.fromNp3(plain), edited = Np3PreviewProfile.fromNp3(bright);
        for (int gray = 0; gray < 256; gray++) {
            int input = 0xff000000 | gray * 0x010101;
            equal(base.applyArgb(input), edited.applyArgb(input));
            near(base.mapTone(gray / 255.0), edited.mapTone(gray / 255.0));
        }
        // This curve halves chroma around middle gray. Brightness still uses the
        // original saturation, not the lower saturation after applying the curve.
        int input = 0xff996666;
        double gain = lightness8(edited.applyArgb(input)) - lightness8(base.applyArgb(input));
        check(gain >= 4 && gain <= 6, "Curve and Blender brightness retain the original-input saturation weight");
        byte[] flat = identityCurve();
        for (int i = 0; i <= 256; i++) sample(flat, i, 16384);
        byte[] flatBright = withCurve(flat, false); setBlenderBrightness(flatBright, 100);
        equal(0xff808080, Np3PreviewProfile.fromNp3(flatBright).applyArgb(0xffff0000));

        for (boolean customCurve : new boolean[] {false, true}) for (int amount : new int[] {-100, 100}) {
            byte[] bytes = customCurve ? withCurve(curve, false) : identityNp3();
            if (!customCurve) for (int index = 25; index <= 29; index++) field(bytes, index, 0, 128 + amount);
            field(bytes, 30, 0, 128 + amount);
            for (int i = 0; i < 24; i++) field(bytes, 31, i, 128 + amount);
            Np3PreviewProfile profile = Np3PreviewProfile.fromNp3(bytes);
            for (int rgb : new int[] {0, 0xffffff, 0x010000, 0xfffefe, 0x818080, 0xff0000, 0x996666, 0x1234ef}) {
                int expectedRgb = profile.applyArgb(0xff000000 | rgb) & 0xffffff;
                for (int alpha : new int[] {0, 1, 127, 255}) {
                    int pixel = (alpha << 24) | rgb, result = profile.applyArgb(pixel);
                    equal((alpha << 24) | expectedRgb, result);
                    equal(result, profile.applyArgb(pixel));
                }
            }
            for (int i = 0; i <= 256; i++) {
                double mapped = profile.mapTone(i / 256.0);
                check(Double.isFinite(mapped) && mapped >= 0 && mapped <= 1, "Curve/basic extremes retain finite bounded tone values");
            }
        }
    }

    private static void setBlenderBrightness(byte[] bytes, int amount) {
        for (int i = 0; i < 8; i++) field(bytes, 31, i * 3 + 2, 128 + amount);
    }

    private static double lightness8(int argb) {
        return (Math.max(red(argb), Math.max(green(argb), blue(argb)))
                + Math.min(red(argb), Math.min(green(argb), blue(argb)))) / 2.0;
    }

    private static void allEightColorBlenderAnchorsAndHueWrapWork() throws Exception {
        int[] anchors = {0xffff0000, 0xffff8000, 0xffffff00, 0xff00ff00,
                0xff00ffff, 0xff0000ff, 0xff8000ff, 0xffff00ff};
        for (int i = 0; i < 8; i++) {
            byte[] bytes = identityNp3(); field(bytes, 31, i * 3, 228);
            Np3PreviewProfile hue = Np3PreviewProfile.fromNp3(bytes);
            check(hue.applyArgb(anchors[i]) != anchors[i], "Each blender hue anchor is used: " + i);
            field(bytes, 31, i * 3, 128); field(bytes, 31, i * 3 + 1, 28);
            int muted = Np3PreviewProfile.fromNp3(bytes).applyArgb(anchors[i]);
            check(Math.abs(red(muted) - green(muted)) <= 2 && Math.abs(blue(muted) - green(muted)) <= 2,
                    "Each blender chroma anchor can desaturate its own colour: " + i);
            field(bytes, 31, i * 3 + 1, 128); field(bytes, 31, i * 3 + 2, 178);
            check(Np3PreviewProfile.fromNp3(bytes).applyArgb(anchors[i]) != anchors[i], "Each blender brightness anchor is used: " + i);
        }
        byte[] redHue = identityNp3(); field(redHue, 31, 0, 228);
        Np3PreviewProfile profile = Np3PreviewProfile.fromNp3(redHue);
        equal(0xffff8000, profile.applyArgb(0xffff0000));
        equal(0xff00ff00, profile.applyArgb(0xff00ff00));
        int left = profile.applyArgb(0xffff0001), right = profile.applyArgb(0xffff0100);
        check(Math.abs(green(left) - green(right)) <= 3 && Math.abs(blue(left) - blue(right)) <= 3,
                "Blender interpolation stays continuous through 0/360 hue");
        equal(0xff808080, profile.applyArgb(0xff808080));
    }

    private static void gradingZonesBrightnessBalanceAndBlendingAffectPixels() throws Exception {
        byte[] bytes = identityNp3();
        // All zones blue: gray acquires a blue tint independently of its original saturation.
        for (int i = 0; i < 3; i++) { field(bytes, 32, i * 4 + 1, 240); field(bytes, 32, i * 4 + 2, 228); }
        Np3PreviewProfile blue = Np3PreviewProfile.fromNp3(bytes);
        int tinted = blue.applyArgb(0x7f808080);
        equal(127, tinted >>> 24);
        check(blue(tinted) > red(tinted) && blue(tinted) > green(tinted), "Grading tints neutral pixels");
        byte[] shadowBlue = identityNp3(); field(shadowBlue, 32, 9, 240); field(shadowBlue, 32, 10, 228);
        Np3PreviewProfile shadow = Np3PreviewProfile.fromNp3(shadowBlue);
        int dark = shadow.applyArgb(0xff303030), light = shadow.applyArgb(0xffd0d0d0);
        check(blue(dark) - red(dark) > blue(light) - red(light), "Shadow colour preferentially affects shadows");
        byte[] highlightRed = identityNp3(); field(highlightRed, 32, 2, 228);
        Np3PreviewProfile highlight = Np3PreviewProfile.fromNp3(highlightRed);
        dark = highlight.applyArgb(0xff303030); light = highlight.applyArgb(0xffd0d0d0);
        check(red(light) - blue(light) > red(dark) - blue(dark), "Highlight colour preferentially affects highlights");
        field(highlightRed, 32, 18, 228);
        int shifted = Np3PreviewProfile.fromNp3(highlightRed).applyArgb(0xff808080);
        check(shifted != highlight.applyArgb(0xff808080), "Balance shifts grading zone weights");
        field(highlightRed, 32, 18, 128); field(highlightRed, 32, 16, 128);
        check(Np3PreviewProfile.fromNp3(highlightRed).applyArgb(0xff808080) != highlight.applyArgb(0xff808080),
                "Blending adjusts grading zone overlap");
        byte[] brightness = identityNp3();
        for (int i = 0; i < 3; i++) field(brightness, 32, i * 4 + 3, 178);
        equal(0xff808080, Np3PreviewProfile.fromNp3(brightness).applyArgb(0xff404040));
    }

    private static void commentsDoNotMoveOrChangeCurveRendering() throws Exception {
        byte[] curve = identityCurve(); sample(curve, 80, 18000);
        Np3PreviewProfile plain = Np3PreviewProfile.fromNp3(withCurve(curve, false));
        Np3PreviewProfile commented = Np3PreviewProfile.fromNp3(withCurve(curve, true));
        Np3PreviewProfile commentOnly = Np3PreviewProfile.fromNp3(append(identityNp3(), 0x10100, note()));
        equal(0xff123456, commentOnly.applyArgb(0xff123456));
        for (int i = 0; i <= 256; i++) near(plain.mapTone(i / 256.0), commented.mapTone(i / 256.0));
        int[] pixels = {0x0055aaff, 0x7f554433, 0xffcccccc, 0xff123456};
        equal(plain.render(pixels, 2, 2), commented.render(pixels, 2, 2));
    }

    private static void ownershipAlphaRepeatabilityAndBounds() throws Exception {
        byte[] bytes = identityNp3(); field(bytes, 31, 0, 228);
        byte[] before = bytes.clone(); Np3PreviewProfile profile = Np3PreviewProfile.fromNp3(bytes);
        check(Arrays.equals(before, bytes), "Parsing retains original bytes");
        int[] pixels = {0x0055aaff, 0x7f554433, 0xffcccccc, 0xff123456}; int[] original = pixels.clone();
        int[] rendered = profile.render(pixels, 2, 2);
        check(rendered != pixels, "Rendering returns a separate output"); equal(original, pixels);
        Arrays.fill(bytes, (byte) 0); equal(rendered, profile.render(pixels, 2, 2));
        rendered[0] = 0; equal(original, pixels);
        for (int alpha = 0; alpha <= 255; alpha++) equal(alpha, profile.applyArgb(alpha << 24 | 0x00b64f20) >>> 24);
        for (int i = 0; i < 10; i++) equal(profile.render(pixels, 2, 2), profile.render(pixels, 2, 2));
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -.1, 1.1})
            invalid(() -> profile.mapTone(value));
        invalid(() -> profile.render(null, 1, 1)); invalid(() -> profile.render(new int[1], 0, 1));
        invalid(() -> profile.render(new int[1], -1, -1)); invalid(() -> profile.render(new int[1], 2, 2));
        invalid(() -> profile.render(new int[1], Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    private static void malformedUnsupportedAndAmbiguousFieldsFail() throws Exception {
        rejected(() -> Np3PreviewProfile.fromNp3(null));
        rejected(() -> Np3PreviewProfile.fromNp3(new byte[1024 * 1024 + 1]));
        byte[] valid = withCurve(identityCurve(), true);
        for (int length = 0; length < valid.length; length++) {
            byte[] truncated = Arrays.copyOf(valid, length);
            rejected(() -> Np3PreviewProfile.fromNp3(truncated));
        }
        for (int[] mutation : new int[][] {
                {1, 0, '9'}, {3, 0, 8}, {3, 1, 2}, {4, 0, 1}, {5, 0, 128},
                {6, 0, 255}, {6, 1, 1}, {7, 0, 149}, {22, 0, 107},
                {8, 0, 128}, {12, 1, 1}, {14, 0, 128}, {15, 0, 128},
                {20, 0, 129}, {20, 0, 127}, {20, 1, 4}, {21, 0, 128},
                {25, 0, 255}, {25, 0, 1}, {26, 1, 4}, {30, 0, 255},
                {31, 0, 229}, {31, 24, 4}, {31, 27, 1},
                {32, 0, 0}, {32, 0, 130}, {32, 2, 27},
                {32, 12, 4}, {32, 15, 1}, {32, 16, 127}, {32, 16, 229}, {32, 17, 4}, {32, 19, 0}
        }) {
            byte[] malformed = identityNp3(); field(malformed, mutation[0], mutation[1], mutation[2]);
            rejected(() -> Np3PreviewProfile.fromNp3(malformed));
        }
        byte[] malformed = identityNp3(); malformed[0] = 'X'; reject(malformed);
        malformed = identityNp3(); malformed[7] = 2; reject(malformed);
        malformed = identityNp3(); malformed[11] = 3; reject(malformed);
        malformed = identityNp3(); Arrays.fill(malformed, 24, 44, (byte) 'A'); reject(malformed);
        malformed = identityNp3(); malformed[24] = 1; reject(malformed);
        malformed = identityNp3(); malformed[43] = 1; reject(malformed);
        malformed = Arrays.copyOf(identityNp3(), 393); reject(malformed);
        reject(append(identityNp3(), 99, new byte[16]));
        reject(append(append(identityNp3(), 0x10100, note()), 0x10100, note()));
        byte[] badNote = note(); badNote[0] = (byte) 0xc0; reject(append(identityNp3(), 0x10100, badNote));
        badNote = note(); badNote[15] = 1; reject(append(identityNp3(), 0x10100, badNote));
        reject(append(identityNp3(), 0x10100, new byte[17]));
        reject(append(identityNp3(), 0x10100, new byte[14]));
        reject(append(withCurve(identityCurve(), false), 2, identityCurve()));
        reject(append(withCurve(identityCurve(), false), 0x10100, note()));
        for (int[] mutation : new int[][] {{0, 0}, {2, 255}, {6, 21}, {7, 101}, {8, 21}, {11, 0}, {63, 1}, {64, 128}}) {
            byte[] curve = identityCurve(); curve[mutation[0]] = (byte) mutation[1];
            reject(withCurve(curve, false));
        }
        byte[] badLevels = identityCurve(); badLevels[4] = (byte) 255; badLevels[5] = 0; reject(withCurve(badLevels, false));
        byte[] mismatch = withCurve(identityCurve(), false); field(mismatch, 25, 0, 128); reject(mismatch);
    }

    /** Fully neutral supported 392-byte Flexible Color container; no user's data is embedded. */
    public static byte[] identityNp3() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('N'); out.write('C'); out.write('P'); out.write(0);
        for (int index = 1; index <= 32; index++) {
            int length = index == 1 ? 4 : index == 2 || index == 32 ? 20 : index == 31 ? 28 : 2;
            byte[] payload = new byte[length];
            switch (index) {
                case 1: payload = "0310".getBytes(StandardCharsets.US_ASCII); break;
                case 2: System.arraycopy("HOST NP3".getBytes(StandardCharsets.US_ASCII), 0, payload, 0, 8); break;
                case 3: payload[1] = 32; break;
                case 4: break;
                case 6: payload[0] = (byte) 136; payload[1] = 4; break;
                case 7: payload[0] = (byte) 130; payload[1] = 4; break;
                case 8: case 9: case 10: case 11: case 14: case 23: case 24:
                    payload[0] = (byte) 255; payload[1] = 4; break;
                case 12: case 13: payload[0] = (byte) 255; break;
                case 20: payload[0] = (byte) 128; payload[1] = 1; break;
                case 21: payload[0] = (byte) 255; payload[1] = 10; break;
                case 22: payload[0] = (byte) 132; payload[1] = 4; break;
                case 25: case 26: case 27: case 28: case 29: case 30:
                    payload[0] = (byte) 128; payload[1] = 1; break;
                case 31:
                    Arrays.fill(payload, 0, 24, (byte) 128); payload[24] = payload[25] = payload[26] = 1; break;
                case 32:
                    for (int i = 0; i < 3; i++) {
                        payload[i * 4] = payload[i * 4 + 2] = payload[i * 4 + 3] = (byte) 128;
                    }
                    payload[12] = payload[13] = payload[14] = payload[17] = payload[19] = 1;
                    payload[16] = (byte) 178; payload[18] = (byte) 128; break;
                default: payload[0] = (byte) 255; payload[1] = 1;
            }
            u32(out, index * 256); u32(out, payload.length); out.write(payload, 0, payload.length);
        }
        u32(out, 0); return out.toByteArray();
    }
    private static byte[] identityCurve() {
        byte[] curve = new byte[578]; curve[0] = 0x49; curve[1] = 0x30;
        curve[3] = curve[5] = (byte) 255; curve[6] = 1; curve[8] = 2;
        curve[11] = curve[12] = (byte) 255;
        for (int i = 0; i <= 256; i++) sample(curve, i, (int) Math.round(i * 32767 / 256.0));
        return curve;
    }
    private static byte[] withCurve(byte[] curve, boolean comment) {
        byte[] base = identityNp3(); for (int i = 25; i <= 29; i++) field(base, i, 0, 1);
        if (comment) base = append(base, 0x10100, note());
        return append(base, 2, curve);
    }
    private static byte[] note() {
        byte[] result = new byte[16]; byte[] text = "合成 test".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(text, 0, result, 0, text.length); return result;
    }
    private static byte[] append(byte[] base, int id, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(base, 0, base.length - 4);
        u32(out, id); u32(out, payload.length); out.write(payload, 0, payload.length); u32(out, 0); return out.toByteArray();
    }
    private static void sample(byte[] curve, int index, int value) {
        curve[64 + index * 2] = (byte) (value >>> 8); curve[65 + index * 2] = (byte) value;
    }
    private static void field(byte[] bytes, int index, int position, int value) { bytes[fieldOffset(bytes, index) + position] = (byte) value; }
    private static int fieldOffset(byte[] bytes, int index) {
        int offset = 4;
        for (int i = 1; i < index; i++) offset += 8 + read32(bytes, offset + 4);
        return offset + 8;
    }
    private static int curveOffset(byte[] bytes) { return 396; }
    private static int read32(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16 | (bytes[offset + 2] & 255) << 8 | bytes[offset + 3] & 255;
    }
    private static void u32(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24); out.write(value >>> 16); out.write(value >>> 8); out.write(value);
    }
    private static int red(int color) { return color >>> 16 & 255; }
    private static int green(int color) { return color >>> 8 & 255; }
    private static int blue(int color) { return color & 255; }
    private interface Action { void run() throws Exception; }
    private static void reject(byte[] bytes) throws Exception { rejected(() -> Np3PreviewProfile.fromNp3(bytes)); }
    private static void rejected(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected malformed or unsupported NP3 rejection");
    }
    private static void invalid(Action action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected invalid rendering input rejection");
    }
    private static void near(double expected, double actual) { check(Math.abs(expected - actual) < 1e-12, "Unexpected tone value: " + expected + " vs " + actual); }
    private static void equal(Object expected, Object actual) {
        check(expected instanceof int[] && actual instanceof int[] ? Arrays.equals((int[]) expected, (int[]) actual)
                : java.util.Objects.equals(expected, actual), "Unexpected result: " + expected + " vs " + actual);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}

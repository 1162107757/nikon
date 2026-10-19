package com.nikonpreset.usbprobe.ncp;

import java.io.IOException;
import java.util.Arrays;

/** Synthetic fixture checks for the documented phone approximation, not Nikon colour fidelity. */
public final class NcpPreviewProfileTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        identityCurvePreservesEveryChannelAndAlpha();
        endpointsAndFractionalPositionsUseRawSamples();
        presetToneAndApproximateColorSettingsAffectOutput();
        omittedCameraProcessingIsNotInvented();
        modificationStateKeepsReadOnlyPreviewAndRecognizedCameraStatesSeparate();
        inputAndOutputOwnershipAndRepeatability();
        unsupportedFormatsAndInvalidInputsAreRejected();
        System.out.println("PASS: " + checks + " NCP phone-template approximation checks");
    }

    private static void identityCurvePreservesEveryChannelAndAlpha() throws Exception {
        NcpPreviewProfile profile = NcpPreviewProfile.fromNcp(identityNcp());
        for (int value = 0; value < 256; value++) {
            int pixel = (value << 24) | (value << 16) | ((255 - value) << 8) | ((value * 17) & 255);
            equal(pixel, profile.applyArgb(pixel));
        }
        equal("HOST NCP", profile.registrationName());
        equal(2, profile.basePictureControl());
        equal(2, profile.sharpening());
        equal(0, profile.saturation());
        equal(0, profile.hue());
    }

    private static void endpointsAndFractionalPositionsUseRawSamples() throws Exception {
        byte[] ncp = identityNcp();
        sample(ncp, 0, 1024); sample(ncp, 1, 3072); sample(ncp, 256, 30000);
        sample(ncp, 128, 8192); sample(ncp, 129, 24576);
        NcpPreviewProfile profile = NcpPreviewProfile.fromNcp(ncp);
        near(1024 / 32767.0, profile.mapTone(0));
        near(30000 / 32767.0, profile.mapTone(1));
        near(2048 / 32767.0, profile.mapTone(1 / 512.0));
        near(12288 / 32767.0, profile.mapTone(128.25 / 256));
        // Legal decreasing segments are not silently replaced by a monotonic/sorted curve.
        check(profile.mapTone(128 / 256.0) < profile.mapTone(127 / 256.0), "Raw non-monotonic samples are retained");
        int black = profile.applyArgb(0xff000000) & 255;
        int white = profile.applyArgb(0xffffffff) & 255;
        equal((int) Math.round(1024 / 32767.0 * 255), black);
        equal((int) Math.round(30000 / 32767.0 * 255), white);
    }

    private static void presetToneAndApproximateColorSettingsAffectOutput() throws Exception {
        byte[] inverted = identityNcp();
        for (int i = 0; i <= 256; i++) sample(inverted, i, 32767 - (int) Math.round(i * 32767 / 256.0));
        NcpPreviewProfile identity = NcpPreviewProfile.fromNcp(identityNcp());
        NcpPreviewProfile reverse = NcpPreviewProfile.fromNcp(inverted);
        equal(0xffdfbf7f, reverse.applyArgb(0xff204080));
        check(identity.applyArgb(0xff204080) != reverse.applyArgb(0xff204080), "Different tone curves produce different templates");

        byte[] lowSaturation = identityNcp(); lowSaturation[43] = 0x7d;
        NcpPreviewProfile muted = NcpPreviewProfile.fromNcp(lowSaturation);
        equal(-3, muted.saturation());
        // App mapping -3 => 0.5 HSV saturation: full red becomes half-saturated red.
        equal(0xffff8080, muted.applyArgb(0xffff0000));
        byte[] positiveHue = identityNcp(); positiveHue[44] = (byte) 0x83;
        NcpPreviewProfile shifted = NcpPreviewProfile.fromNcp(positiveHue);
        equal(3, shifted.hue());
        // App mapping +3 => +12 degrees: red moves slightly toward yellow.
        equal(0xffff3300, shifted.applyArgb(0xffff0000));
        byte[] negativeHue = identityNcp(); negativeHue[44] = 0x7d;
        equal(0xffff0033, NcpPreviewProfile.fromNcp(negativeHue).applyArgb(0xffff0000));
        for (int gray = 0; gray < 256; gray++) {
            int argb = 0xff000000 | (gray << 16) | (gray << 8) | gray;
            equal(argb, shifted.applyArgb(argb));
            equal(argb, muted.applyArgb(argb));
        }
        for (int alpha = 0; alpha < 256; alpha++) {
            equal(alpha, shifted.applyArgb((alpha << 24) | 0x0071ba42) >>> 24);
        }
    }

    private static void omittedCameraProcessingIsNotInvented() throws Exception {
        byte[] modified = identityNcp();
        modified[36] = 0; modified[37] = 1; // A supported, different camera base profile.
        modified[40] = (byte) 0x89; // Sharpening metadata differs, but no spatial effect is claimed.
        modified[62] = 2; modified[63] = 50; // Gamma header is validated, not interpreted by this model.
        NcpPreviewProfile profile = NcpPreviewProfile.fromNcp(modified);
        equal(1, profile.basePictureControl());
        equal(9, profile.sharpening());
        int[] colors = {0xff000000, 0xffffffff, 0xff315fad, 0x80808080, 0x00112233};
        equal(colors, profile.render(colors, 5, 1));
    }

    private static void inputAndOutputOwnershipAndRepeatability() throws Exception {
        byte[] ncp = NcpCompatibilityFixtures.ncp();
        byte[] beforeNcp = ncp.clone();
        NcpPreviewProfile profile = NcpPreviewProfile.fromNcp(ncp);
        equal(beforeNcp, ncp);
        int[] pixels = {0x0066aaee, 0x7f345678, 0xffc07020, 0xffffffff};
        int[] beforePixels = pixels.clone();
        int[] first = profile.render(pixels, 2, 2);
        check(first != pixels, "Render returns independently owned pixels");
        equal(beforePixels, pixels);
        equal(first, profile.render(pixels, 2, 2));
        Arrays.fill(ncp, (byte) 0); // Caller changes cannot alter an already parsed profile.
        equal(first, profile.render(pixels, 2, 2));
        first[0] ^= 0x00ffffff;
        equal(beforePixels, pixels);
        int[] repeated = profile.render(pixels, 2, 2);
        check(first[0] != repeated[0], "Caller output edits do not mutate the profile or its next result");
        for (int i = 0; i < 20; i++) equal(repeated, profile.render(pixels, 2, 2));
    }

    private static void modificationStateKeepsReadOnlyPreviewAndRecognizedCameraStatesSeparate() throws Exception {
        int[] colors = {0xff000000, 0xffffffff, 0xff315fad, 0x80808080, 0x00112233};
        NcpPreviewProfile reference = NcpPreviewProfile.fromNcp(NcpCompatibilityFixtures.ncp());
        int[] expected = reference.render(colors, colors.length, 1);
        for (int flag : new int[] {0, 1, 2, 255}) {
            byte[] original = NcpCompatibilityFixtures.ncp(); original[38] = (byte) flag;
            byte[] before = original.clone();
            NcpPreviewProfile preview = NcpPreviewProfile.fromNcp(original);
            equal(before, original);
            equal(expected, preview.render(colors, colors.length, 1));
            equal(reference.registrationName(), preview.registrationName());
            equal(reference.basePictureControl(), preview.basePictureControl());
            equal(reference.sharpening(), preview.sharpening());
            equal(reference.saturation(), preview.saturation());
            equal(reference.hue(), preview.hue());
            for (int i = 0; i <= 256; i++) near(reference.mapTone(i / 256.0), preview.mapTone(i / 256.0));
            if (flag > 2) unsupported(() -> NcpPictureControlConverter.convert(original, 0, 0));
            else equal(NcpPictureControlConverter.convert(NcpCompatibilityFixtures.ncp(), 0, 0).dataBytes(),
                    NcpPictureControlConverter.convert(original, 0, 0).dataBytes());
        }
    }

    private static void unsupportedFormatsAndInvalidInputsAreRejected() throws Exception {
        unsupported(() -> NcpPreviewProfile.fromNcp(null));
        for (int length : new int[] {0, 8, 637, 639, 1024 * 1024 + 1}) {
            unsupported(() -> NcpPreviewProfile.fromNcp(Arrays.copyOf(identityNcp(), length)));
        }
        for (int[] mutation : new int[][] {
                {0, 'X'}, {12, '9'}, {11, 35}, {51, 3}, {637, 1},
                {36, 0x7f}, {39, 0}, {40, 0xff}, {41, 0}, {43, 0xff}, {44, 0x84},
                {45, 0}, {56, 0}, {62, 21}, {64, 1}, {105, 1}, {120, 0x80}
        }) {
            byte[] invalid = identityNcp(); invalid[mutation[0]] = (byte) mutation[1];
            unsupported(() -> NcpPreviewProfile.fromNcp(invalid));
        }
        NcpPreviewProfile profile = NcpPreviewProfile.fromNcp(identityNcp());
        for (double invalid : new double[] {-0.001, 1.001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            invalidInput(() -> profile.mapTone(invalid));
        }
        invalidInput(() -> profile.render(null, 1, 1));
        invalidInput(() -> profile.render(new int[0], 0, 0));
        invalidInput(() -> profile.render(new int[1], -1, -1));
        invalidInput(() -> profile.render(new int[1], 1, 2));
        invalidInput(() -> profile.render(new int[1], Integer.MAX_VALUE, Integer.MAX_VALUE));
        invalidInput(() -> profile.render(new int[1], NcpPreviewProfile.MAX_PIXELS + 1, 1));
        invalidInput(() -> profile.render(new int[NcpPreviewProfile.MAX_PIXELS + 1], NcpPreviewProfile.MAX_PIXELS + 1, 1));
    }

    private static byte[] identityNcp() {
        byte[] bytes = NcpCompatibilityFixtures.ncp();
        bytes[43] = bytes[44] = (byte) 0x80;
        for (int i = 0; i <= 256; i++) sample(bytes, i, (int) Math.round(i * 32767 / 256.0));
        return bytes;
    }

    private static void sample(byte[] bytes, int index, int value) {
        int offset = 120 + index * 2;
        bytes[offset] = (byte) (value >>> 8); bytes[offset + 1] = (byte) value;
    }

    private interface Action { void run() throws Exception; }
    private static void unsupported(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected malformed or unsupported NCP rejection");
    }
    private static void invalidInput(Action action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected invalid rendering-input rejection");
    }
    private static void near(double expected, double actual) {
        check(Math.abs(expected - actual) < 1e-12, "Expected tone " + expected + ", got " + actual);
    }
    private static void equal(Object expected, Object actual) {
        boolean same;
        if (expected instanceof int[] && actual instanceof int[]) same = Arrays.equals((int[]) expected, (int[]) actual);
        else if (expected instanceof byte[] && actual instanceof byte[]) same = Arrays.equals((byte[]) expected, (byte[]) actual);
        else same = java.util.Objects.equals(expected, actual);
        check(same, "Unexpected approximation result");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

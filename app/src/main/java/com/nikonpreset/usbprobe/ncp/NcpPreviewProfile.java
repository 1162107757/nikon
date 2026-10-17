package com.nikonpreset.usbprobe.ncp;

import java.io.IOException;

/**
 * Deterministic phone-only approximation for the converter's researched legacy NCP subset.
 *
 * <p>This is not Nikon's image pipeline or a reproduction of the selected base Picture Control.
 * The app treats the 257 curve samples as a normalized tone table in encoded display RGB,
 * applies it independently to each channel, then approximates saturation and hue in HSV.
 * The app-specific mappings are saturation factor {@code 1 + step / 6.0} and hue rotation
 * {@code step * 4} degrees. They are deliberately documented assumptions, not recovered
 * Nikon parameter semantics. No linear-light conversion, curve-header Gamma adjustment,
 * base-profile colour transform, sharpening, clarity or spatial processing is performed.
 * The original NCP and template pixels are never modified.</p>
 */
public final class NcpPreviewProfile {
    /** Include this in any persistent thumbnail cache key when changing the approximation. */
    public static final String MODEL_VERSION = "encoded-rgb-tone-hsv-v1";
    public static final int MAX_PIXELS = 4 * 1024 * 1024;
    private static final int FILE_BYTES = 638;
    private static final int CURVE_SAMPLES_OFFSET = 56 + 64;
    private static final int SAMPLE_MAX = 32767;

    private final int[] samples;
    private final int[] tone = new int[256];
    private final String registrationName;
    private final int basePictureControl, sharpening, saturation, hue;

    private NcpPreviewProfile(byte[] verified, NcpPictureControlConverter.Conversion conversion) {
        registrationName = conversion.registrationName();
        basePictureControl = conversion.basePictureControl();
        sharpening = (verified[40] & 255) - 0x80;
        saturation = (verified[43] & 255) - 0x80;
        hue = (verified[44] & 255) - 0x80;
        samples = new int[257];
        for (int i = 0; i < samples.length; i++) {
            int offset = CURVE_SAMPLES_OFFSET + 2 * i;
            samples[i] = ((verified[offset] & 255) << 8) | (verified[offset + 1] & 255);
        }
        for (int i = 0; i < tone.length; i++) tone[i] = channel(mapTone(i / 255.0));
    }

    /** Reject unverified structures rather than inventing a replacement photographic effect. */
    public static NcpPreviewProfile fromNcp(byte[] original) throws IOException {
        if (original == null || original.length != FILE_BYTES) {
            throw new IOException("模板模拟仅支持已验证的 638 字节 NCP 0100 子集。");
        }
        byte[] snapshot = original.clone();
        NcpPictureControlConverter.Conversion validated;
        try {
            // Share all container, version, base, metadata and curve-range compatibility guards.
            validated = NcpPictureControlConverter.convert(snapshot, 0, 0);
        } catch (IOException unsupported) {
            throw new IOException("此预设暂不支持模板模拟。" + unsupported.getMessage(), unsupported);
        }
        return new NcpPreviewProfile(snapshot, validated);
    }

    public String registrationName() { return registrationName; }
    /** Metadata only; this base profile's proprietary colour transform is not applied. */
    public int basePictureControl() { return basePictureControl; }
    /** Metadata only; no sharpening is applied to the template. */
    public int sharpening() { return sharpening; }
    public int saturation() { return saturation; }
    public int hue() { return hue; }

    /** Linear interpolation of the raw normalized samples, without inferred Gamma processing. */
    public double mapTone(double normalized) {
        if (!Double.isFinite(normalized) || normalized < 0 || normalized > 1) {
            throw new IllegalArgumentException("Tone input must be finite and within 0..1.");
        }
        double position = normalized * 256;
        int lower = (int) position;
        if (lower == 256) return samples[256] / (double) SAMPLE_MAX;
        double fraction = position - lower;
        return (samples[lower] + (samples[lower + 1] - samples[lower]) * fraction) / SAMPLE_MAX;
    }

    /** Apply the phone approximation to one unpremultiplied ARGB pixel, retaining its alpha. */
    public int applyArgb(int argb) {
        int alpha = argb & 0xff000000;
        int red = tone[(argb >>> 16) & 255], green = tone[(argb >>> 8) & 255], blue = tone[argb & 255];
        if (saturation == 0 && hue == 0) return alpha | (red << 16) | (green << 8) | blue;

        double r = red / 255.0, g = green / 255.0, b = blue / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double delta = max - min;
        // Achromatic pixels remain achromatic, even when the preset has a hue adjustment.
        if (delta == 0) return alpha | (red << 16) | (green << 8) | blue;
        double angle;
        if (max == r) angle = 60 * ((g - b) / delta);
        else if (max == g) angle = 60 * ((b - r) / delta + 2);
        else angle = 60 * ((r - g) / delta + 4);
        angle = (angle + hue * 4 + 360) % 360;
        double hsvSaturation = Math.min(1, delta / max * (1 + saturation / 6.0));
        double chroma = max * hsvSaturation;
        double x = chroma * (1 - Math.abs((angle / 60) % 2 - 1));
        double m = max - chroma;
        double outRed, outGreen, outBlue;
        int sector = (int) (angle / 60);
        switch (sector) {
            case 0: outRed = chroma; outGreen = x; outBlue = 0; break;
            case 1: outRed = x; outGreen = chroma; outBlue = 0; break;
            case 2: outRed = 0; outGreen = chroma; outBlue = x; break;
            case 3: outRed = 0; outGreen = x; outBlue = chroma; break;
            case 4: outRed = x; outGreen = 0; outBlue = chroma; break;
            default: outRed = chroma; outGreen = 0; outBlue = x; break;
        }
        return alpha | (channel(outRed + m) << 16) | (channel(outGreen + m) << 8) | channel(outBlue + m);
    }

    /** Return a new exact-sized ARGB array. Dimensions and allocation are bounded before work. */
    public int[] render(int[] argb, int width, int height) {
        long pixels = (long) width * height;
        if (argb == null || width <= 0 || height <= 0 || pixels > MAX_PIXELS
                || pixels != argb.length) {
            throw new IllegalArgumentException("Template dimensions must match 1.." + MAX_PIXELS + " pixels.");
        }
        int[] output = new int[argb.length];
        for (int i = 0; i < argb.length; i++) output[i] = applyArgb(argb[i]);
        return output;
    }

    private static int channel(double normalized) {
        return Math.max(0, Math.min(255, (int) Math.round(normalized * 255)));
    }
}

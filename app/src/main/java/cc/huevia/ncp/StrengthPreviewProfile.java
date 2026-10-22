package cc.huevia.ncp;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** Encoded-sRGB interpolation; identical implementation for preview and full-size export. */
public final class StrengthPreviewProfile implements PresetPreviewProfile {
    private final PresetPreviewProfile base;
    private final int strength;
    private StrengthPreviewProfile(PresetPreviewProfile base, int strength) { this.base = base; this.strength = strength; }
    public static PresetPreviewProfile of(PresetPreviewProfile base, int strength) {
        if (base == null || strength < 0 || strength > 100) throw new IllegalArgumentException("Invalid filter strength");
        return strength == 100 ? base : new StrengthPreviewProfile(base, strength);
    }
    public String registrationName() { return base.registrationName(); }
    public String modelVersion() { return base.modelVersion() + ":mix-srgb-v1-" + strength; }
    public double mapTone(double input) { return input + (base.mapTone(input) - input) * strength / 100.0; }
    @Override public SpatialPreviewFilter.Settings spatialSettings() { return base.spatialSettings(); }

    /** Mix the complete colour plus spatial result, including nested strength wrappers. */
    @Override public void renderPixels(SpatialPreviewFilter.Pixels pixels, BooleanSupplier cancel,
            DoubleConsumer progress) throws IOException {
        if ((cancel != null && cancel.getAsBoolean()) || Thread.currentThread().isInterrupted()) throw new IOException("预设处理已取消。");
        if (strength == 0) { if (progress != null) progress.accept(1); return; }
        base.renderPixels(new SpatialPreviewFilter.Pixels() {
            private int[] original = new int[0];
            public int width() { return pixels.width(); }
            public int height() { return pixels.height(); }
            public void read(int[] target, int firstRow, int rows) throws IOException { pixels.read(target, firstRow, rows); }
            public void write(int[] result, int firstRow, int rows) throws IOException {
                int count = width() * rows;
                if (original.length < count) original = new int[count];
                // This output row is still untouched. The spatial engine has already retained
                // any source neighbourhood it needs before the delegate writes it in place.
                pixels.read(original, firstRow, rows);
                for (int i = 0; i < count; i++) original[i] = mix(original[i], result[i]);
                pixels.write(original, firstRow, rows);
            }
        }, cancel, progress);
    }
    public int applyArgb(int input) {
        if (strength == 0) return input;
        return mix(input, base.applyArgb(input));
    }
    private int mix(int input, int filtered) {
        int result = input & 0xff000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = (input >>> shift) & 255, b = (filtered >>> shift) & 255;
            result |= ((a * (100 - strength) + b * strength + 50) / 100) << shift;
        }
        return result;
    }
}

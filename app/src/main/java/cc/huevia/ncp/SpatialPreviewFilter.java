package cc.huevia.ncp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.IntUnaryOperator;

/**
 * Streaming, in-place colour and spatial preview approximation, independent of Android.
 *
 * <p>This is a custom encoded-sRGB model, not a reproduction or calibration of NX Studio.
 * Its v2 spatial units use alpha-weighted, edge-replicated box means B1, B4 and B12
 * at radii 1, 4 and 12 pixels. With L=(54R+183G+19B)/256 the correction is
 * 1.15*s*soft(L-B1,2.5) + .85*m*soft(B1-B4,1.5)
 * + .65*c*soft(B4-B12,1.0), where soft(v,t)=sign(v)*max(abs(v)-t,0).
 * Negative settings soften the corresponding band. The resulting luma is limited to
 * the visible 3x3 source neighbourhood's luma range to suppress bright and dark halos.
 * A common luma offset is applied to RGB, then channels are rounded and clipped.
 * Alpha always comes from the original pixel, including when the colour function
 * returns a different alpha. Fully transparent pixels do not influence neighbours.
 *
 * <p>The colour function is evaluated exactly once for each original pixel. A single
 * source-row ring supplies all scales; it is not reset at strip boundaries. Before
 * writing an output row the engine retains its entire required future halo. The
 * outgoing vertical contribution is removed before reusing a ring slot, so a row
 * that has been written is never read again by the engine. Working payload is at
 * most 32*width*min(height,25)+60*width bytes, plus at most 16 KiB of array bookkeeping.
 * The complete estimate is limited to 64 MiB. Memory is independent of image height.
 * The zero-spatial fast path uses one ARGB row; strength zero performs no pixel I/O.
 *
 * <p>Pixel storage is modified as rows complete. On cancellation, interruption or
 * I/O failure callers must discard an incomplete destination rather than publish it.
 */
public final class SpatialPreviewFilter {
    private static final int[] RADII = {1, 4, 12};
    private static final int MAX_RADIUS = 12;
    private static final int RING_ROWS = MAX_RADIUS * 2 + 1;
    private static final long MAX_WORKING_BYTES = 64L * 1024 * 1024;
    private static final int ARRAY_OVERHEAD_ALLOWANCE = 16 * 1024;
    private static final double[] THRESHOLDS = {2.5, 1.5, 1.0};
    private static final double[] GAINS = {1.15, .85, .65};

    private SpatialPreviewFilter() { }

    /** Normalized spatial amounts; -1 softens, 0 disables, and +1 enhances a band. */
    public static final class Settings {
        public static final Settings NONE = new Settings(0, 0, 0);
        public final double sharpening;
        public final double midRangeSharpening;
        public final double clarity;

        public Settings(double sharpening, double midRangeSharpening, double clarity) {
            this.sharpening = amount(sharpening);
            this.midRangeSharpening = amount(midRangeSharpening);
            this.clarity = amount(clarity);
        }

        public boolean isNone() {
            return sharpening == 0 && midRangeSharpening == 0 && clarity == 0;
        }

        private static double amount(double value) {
            if (!Double.isFinite(value) || value < -1 || value > 1) {
                throw new IllegalArgumentException("Spatial amount must be finite and between -1 and 1");
            }
            return value;
        }
    }

    public static final Settings NONE = Settings.NONE;

    /**
     * Row storage. read/write copy {@code width()*rows} packed ARGB pixels between
     * the beginning of the provided array and consecutive image rows. Neither
     * method may retain or alter the provided array after returning. Calls made
     * by this engine use one row, in increasing row order, with each row read once.
     * Implementations may wrap write to read its still-original row before commit.
     */
    public interface Pixels {
        int width();
        int height();
        void read(int[] dest, int firstRow, int rows) throws IOException;
        void write(int[] source, int firstRow, int rows) throws IOException;
    }

    /**
     * Colour, spatial processing, then exact integer interpolation with the original.
     * strengthPercent is in [0,100]. Null cancellation and progress callbacks are
     * allowed. Cancellation and interruption throw InterruptedIOException; the
     * thread interruption flag is preserved. Storage IOException instances propagate.
     */
    public static void apply(Pixels pixels, Settings settings, IntUnaryOperator colourTransform,
            int strengthPercent, BooleanSupplier cancel, DoubleConsumer progress) throws IOException {
        Objects.requireNonNull(pixels, "pixels");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(colourTransform, "colourTransform");
        if (strengthPercent < 0 || strengthPercent > 100) {
            throw new IllegalArgumentException("Filter strength must be between 0 and 100");
        }
        int width = pixels.width(), height = pixels.height();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid pixel dimensions");
        checkCancelled(cancel);
        if (progress != null) progress.accept(0);
        if (strengthPercent == 0) {
            checkCancelled(cancel);
            if (progress != null) progress.accept(1);
            return;
        }
        if (settings.isNone()) {
            checkWorkingBytes(4L * width + ARRAY_OVERHEAD_ALLOWANCE);
            applyColourOnly(pixels, colourTransform, strengthPercent, cancel, progress, width, height);
            return;
        }
        int slots = Math.min(height, RING_ROWS);
        checkWorkingBytes((32L * slots + 60L) * width + ARRAY_OVERHEAD_ALLOWANCE);
        new Filter(pixels, settings, colourTransform, strengthPercent, cancel, progress,
                width, height, slots, RADII).apply();
    }

    /** Manual details use the same normalized image scale in a small preview and a full export.
     * Legacy preset radii and identities remain unchanged. */
    public static void applyScaled(Pixels pixels, Settings settings, IntUnaryOperator transform,
            BooleanSupplier cancel, DoubleConsumer progress) throws IOException {
        Objects.requireNonNull(pixels);Objects.requireNonNull(settings);Objects.requireNonNull(transform);
        int width=pixels.width(),height=pixels.height();
        if(width<=0||height<=0)throw new IllegalArgumentException("Invalid pixel dimensions");
        checkCancelled(cancel);
        if(settings.isNone()){checkWorkingBytes(4L*width+ARRAY_OVERHEAD_ALLOWANCE);applyColourOnly(pixels,transform,100,cancel,progress,width,height);return;}
        double scale=Math.max(width,height)/1600.0;
        int[] radii={Math.max(1,(int)Math.round(scale)),Math.max(1,(int)Math.round(4*scale)),Math.max(1,(int)Math.round(12*scale))};
        int slots=Math.min(height,2*radii[2]+1);
        checkWorkingBytes((32L*slots+60L)*width+ARRAY_OVERHEAD_ALLOWANCE);
        if(progress!=null)progress.accept(0);
        new Filter(pixels,settings,transform,100,cancel,progress,width,height,slots,radii).apply();
    }

    private static void applyColourOnly(Pixels pixels, IntUnaryOperator transform, int strength,
            BooleanSupplier cancel, DoubleConsumer progress, int width, int height) throws IOException {
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            checkCancelled(cancel);
            pixels.read(row, y, 1);
            for (int x = 0; x < width; x++) {
                if ((x & 255) == 0) checkCancelled(cancel);
                int original = row[x];
                row[x] = mix(original, transform.applyAsInt(original), strength);
            }
            checkCancelled(cancel);
            pixels.write(row, y, 1);
            checkCancelled(cancel);
            if (progress != null) progress.accept((y + 1.0) / height);
        }
    }

    private static void checkWorkingBytes(long bytes) throws IOException {
        if (bytes > MAX_WORKING_BYTES) throw new IOException("Image row exceeds spatial processing memory limit");
    }

    private static void checkCancelled(BooleanSupplier cancel) throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Spatial processing interrupted");
        if (cancel != null && cancel.getAsBoolean()) throw new InterruptedIOException("Spatial processing cancelled");
    }

    private static int mix(int original, int filtered, int strength) {
        int result = original & 0xff000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = (original >>> shift) & 255, b = (filtered >>> shift) & 255;
            result |= ((a * (100 - strength) + b * strength + 50) / 100) << shift;
        }
        return result;
    }

    private static double luma(int argb) {
        return (54 * ((argb >>> 16) & 255) + 183 * ((argb >>> 8) & 255) + 19 * (argb & 255)) / 256.0;
    }

    private static double soft(double value, double threshold) {
        return Math.copySign(Math.max(Math.abs(value) - threshold, 0), value);
    }

    private static int channel(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    private static final class Filter {
        private final Pixels pixels;
        private final IntUnaryOperator transform;
        private final int strength, width, height, slots;
        private final int[] radii;
        private final int maxRadius;
        private final BooleanSupplier cancel;
        private final DoubleConsumer progress;
        private final double[] amounts;
        private final int[][] original, coloured;
        private final float[][][] horizontalLuma, horizontalAlpha;
        private final double[][] verticalLuma, verticalAlpha;
        private final float[] premultLuma, alpha;
        private final int[] output;
        private int loadedThrough = -1;

        Filter(Pixels pixels, Settings settings, IntUnaryOperator transform, int strength,
                BooleanSupplier cancel, DoubleConsumer progress, int width, int height, int slots, int[] radii) {
            this.pixels = pixels;
            this.transform = transform;
            this.strength = strength;
            this.cancel = cancel;
            this.progress = progress;
            this.width = width;
            this.height = height;
            this.slots = slots;
            this.radii = radii; this.maxRadius=radii[2];
            amounts = new double[] {settings.sharpening, settings.midRangeSharpening, settings.clarity};
            original = new int[slots][width];
            coloured = new int[slots][width];
            horizontalLuma = new float[3][slots][width];
            horizontalAlpha = new float[3][slots][width];
            verticalLuma = new double[3][width];
            verticalAlpha = new double[3][width];
            premultLuma = new float[width];
            alpha = new float[width];
            output = new int[width];
        }

        void apply() throws IOException {
            loadThrough(Math.min(height - 1, maxRadius));
            for (int scale = 0; scale < radii.length; scale++) {
                for (int dy = -radii[scale]; dy <= radii[scale]; dy++) {
                    addRow(scale, clampRow(dy), 1);
                }
            }
            for (int y = 0; y < height; y++) {
                checkCancelled(cancel);
                renderRow(y);
                checkCancelled(cancel);
                pixels.write(output, y, 1);
                checkCancelled(cancel);
                if (progress != null) progress.accept((y + 1.0) / height);
                if (y == height - 1) return;
                // Remove all uses of the oldest slot before loadThrough can reuse it.
                for (int scale = 0; scale < radii.length; scale++) {
                    addRow(scale, clampRow(y - radii[scale]), -1);
                }
                loadThrough((int) Math.min((long) height - 1, (long) y + maxRadius + 1));
                for (int scale = 0; scale < radii.length; scale++) {
                    addRow(scale, clampRow((long) y + radii[scale] + 1), 1);
                }
            }
        }

        private int clampRow(long y) {
            return (int) Math.max(0, Math.min(height - 1L, y));
        }

        private int clampColumn(long x) {
            return (int) Math.max(0, Math.min(width - 1L, x));
        }

        private void loadThrough(int target) throws IOException {
            while (loadedThrough < target) {
                checkCancelled(cancel);
                int y = ++loadedThrough, slot = y % slots;
                int[] source = original[slot], colour = coloured[slot];
                pixels.read(source, y, 1);
                for (int x = 0; x < width; x++) {
                    if ((x & 255) == 0) checkCancelled(cancel);
                    int argb = source[x];
                    int converted = (argb & 0xff000000) | (transform.applyAsInt(argb) & 0x00ffffff);
                    colour[x] = converted;
                    alpha[x] = (argb >>> 24) / 255f;
                    premultLuma[x] = (float) (luma(converted) * alpha[x]);
                }
                for (int scale = 0; scale < radii.length; scale++) {
                    horizontalSums(scale, slot);
                }
            }
        }

        private void horizontalSums(int scale, int slot) throws IOException {
            int radius = radii[scale];
            double sumL = 0, sumA = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                int column = clampColumn(dx);
                sumL += premultLuma[column];
                sumA += alpha[column];
            }
            float[] rowL = horizontalLuma[scale][slot], rowA = horizontalAlpha[scale][slot];
            for (int x = 0; x < width; x++) {
                if ((x & 255) == 0) checkCancelled(cancel);
                rowL[x] = (float) sumL;
                rowA[x] = (float) sumA;
                int leaving = clampColumn((long) x - radius), entering = clampColumn((long) x + radius + 1);
                sumL += (double) premultLuma[entering] - premultLuma[leaving];
                sumA += (double) alpha[entering] - alpha[leaving];
            }
        }

        private void addRow(int scale, int row, int sign) throws IOException {
            float[] sourceL = horizontalLuma[scale][row % slots], sourceA = horizontalAlpha[scale][row % slots];
            double[] sumL = verticalLuma[scale], sumA = verticalAlpha[scale];
            for (int x = 0; x < width; x++) {
                if ((x & 255) == 0) checkCancelled(cancel);
                sumL[x] += sign * (double) sourceL[x];
                sumA[x] += sign * (double) sourceA[x];
            }
        }

        private void renderRow(int y) throws IOException {
            int[] centre = coloured[y % slots], source = original[y % slots];
            int[] above = coloured[clampRow(y - 1L) % slots];
            int[] below = coloured[clampRow(y + 1L) % slots];
            for (int x = 0; x < width; x++) {
                if ((x & 255) == 0) checkCancelled(cancel);
                int colour = centre[x];
                if ((colour >>> 24) == 0) {
                    output[x] = mix(source[x], colour, strength);
                    continue;
                }
                double current = luma(colour);
                double b1 = mean(0, x, current), b4 = mean(1, x, current), b12 = mean(2, x, current);
                double delta = GAINS[0] * amounts[0] * soft(current - b1, THRESHOLDS[0])
                        + GAINS[1] * amounts[1] * soft(b1 - b4, THRESHOLDS[1])
                        + GAINS[2] * amounts[2] * soft(b4 - b12, THRESHOLDS[2]);
                if (delta == 0) {
                    output[x] = mix(source[x], colour, strength);
                    continue;
                }
                double minimum = current, maximum = current;
                for (int dx = -1; dx <= 1; dx++) {
                    int column = clampColumn((long) x + dx);
                    int a = above[column], b = centre[column], c = below[column];
                    if ((a >>> 24) != 0) { double l = luma(a); minimum = Math.min(minimum, l); maximum = Math.max(maximum, l); }
                    if ((b >>> 24) != 0) { double l = luma(b); minimum = Math.min(minimum, l); maximum = Math.max(maximum, l); }
                    if ((c >>> 24) != 0) { double l = luma(c); minimum = Math.min(minimum, l); maximum = Math.max(maximum, l); }
                }
                delta = Math.max(minimum, Math.min(maximum, current + delta)) - current;
                int filtered = (colour & 0xff000000)
                        | (channel(((colour >>> 16) & 255) + delta) << 16)
                        | (channel(((colour >>> 8) & 255) + delta) << 8)
                        | channel((colour & 255) + delta);
                output[x] = mix(source[x], filtered, strength);
            }
        }

        private double mean(int scale, int x, double fallback) {
            double weight = verticalAlpha[scale][x];
            return weight > 0 ? verticalLuma[scale][x] / weight : fallback;
        }
    }
}

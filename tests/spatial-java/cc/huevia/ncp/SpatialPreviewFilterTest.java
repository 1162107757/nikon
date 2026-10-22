package cc.huevia.ncp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;

/** Behavioural checks for the standalone custom approximation; no NX Studio claim. */
public final class SpatialPreviewFilterTest {
    private static final IntUnaryOperator IDENTITY = pixel -> pixel;
    private static final SpatialPreviewFilter.Settings ALL = settings(1, 1, 1);
    private static int assertions, cases;

    public static void main(String[] args) throws Exception {
        run("colour-only legacy mixing and original alpha", SpatialPreviewFilterTest::legacyMixing);
        run("strength zero performs no I/O or colour work", SpatialPreviewFilterTest::zeroStrength);
        run("constant, degenerate and alpha-varying regions", SpatialPreviewFilterTest::constantRegions);
        run("all three scales enhance and soften", SpatialPreviewFilterTest::threeScales);
        run("positive and negative settings avoid overshoot halos", SpatialPreviewFilterTest::haloLimits);
        run("low-amplitude noise gate", SpatialPreviewFilterTest::noiseGate);
        run("transparent hidden RGB cannot bleed", SpatialPreviewFilterTest::transparentEdges);
        run("spatial strength blends the complete result", SpatialPreviewFilterTest::spatialStrength);
        run("single source read and colour evaluation per pixel", SpatialPreviewFilterTest::inPlaceSafety);
        run("translation across ring boundaries has no seams", SpatialPreviewFilterTest::noRingSeams);
        run("reflection and transpose preserve square-kernel response", SpatialPreviewFilterTest::symmetry);
        run("monotone progress and cancellation", SpatialPreviewFilterTest::progressAndCancellation);
        run("wide-row cancellation and interrupted flag", SpatialPreviewFilterTest::wideCancellation);
        run("I/O failures propagate without false completion", SpatialPreviewFilterTest::ioFailures);
        run("settings, dimensions, strength and memory validation", SpatialPreviewFilterTest::validation);
        run("height-independent streaming under a 64 MiB heap", SpatialPreviewFilterTest::streamingStress);
        System.out.println("PASS " + cases + " cases, " + assertions + " assertions");
    }

    private static void legacyMixing() throws Exception {
        Random random = new Random(0x4e5033);
        int[] source = new int[67 * 41];
        for (int i = 0; i < source.length; i++) source[i] = random.nextInt();
        IntUnaryOperator transform = pixel -> 0x7f000000 | ((~pixel) & 0x00ffff00) | (((pixel & 255) * 3 + 17) & 255);
        for (int strength : new int[] {1, 13, 50, 99, 100}) {
            int[] actual = render(source, 67, 41, SpatialPreviewFilter.Settings.NONE, transform, strength);
            for (int i = 0; i < source.length; i++) {
                eq(mix(source[i], transform.applyAsInt(source[i]), strength), actual[i], "legacy colour/mix pixel");
            }
        }
        for (int strength : new int[] {1, 50, 100}) {
            arrayEq(source, render(source, 67, 41, SpatialPreviewFilter.NONE, IDENTITY, strength), "zero spatial identity");
        }
    }

    private static void zeroStrength() throws Exception {
        AtomicInteger work = new AtomicInteger();
        SpatialPreviewFilter.Pixels neverRead = new SpatialPreviewFilter.Pixels() {
            public int width() { return Integer.MAX_VALUE; }
            public int height() { return Integer.MAX_VALUE; }
            public void read(int[] row, int first, int rows) { throw new AssertionError("zero-strength read"); }
            public void write(int[] row, int first, int rows) { throw new AssertionError("zero-strength write"); }
        };
        double[] last = {-1};
        SpatialPreviewFilter.apply(neverRead, ALL, p -> { work.incrementAndGet(); return p; }, 0,
                null, value -> { check(value >= last[0], "zero progress monotone"); last[0] = value; });
        eq(0, work.get(), "zero colour invocations");
        check(last[0] == 1, "zero-strength completed progress");
        expect(InterruptedIOException.class, () -> SpatialPreviewFilter.apply(neverRead, ALL, IDENTITY, 0, () -> true, null));
    }

    private static void constantRegions() throws Exception {
        SpatialPreviewFilter.Settings[] variants = {
                ALL, settings(-1, -1, -1), settings(1, -.75, .4), settings(0, -1, 0), settings(0, 0, 1)
        };
        for (int[] dimension : new int[][] {{1, 1}, {1, 73}, {73, 1}, {2, 3}, {7, 9}, {37, 91}}) {
            int width = dimension[0], height = dimension[1];
            for (int colour : new int[] {0, 0xff000000, 0xffffffff, 0x6f1b79d1, 0x011122ee}) {
                int[] source = new int[width * height];
                Arrays.fill(source, colour);
                for (SpatialPreviewFilter.Settings setting : variants) {
                    arrayEq(source, render(source, width, height, setting, IDENTITY, 100), "constant source exact");
                }
            }
        }
        int[] varyingAlpha = new int[43 * 67];
        Random random = new Random(8);
        for (int i = 0; i < varyingAlpha.length; i++) varyingAlpha[i] = (random.nextInt(256) << 24) | 0x004b8ba9;
        arrayEq(varyingAlpha, render(varyingAlpha, 43, 67, ALL, IDENTITY, 100), "constant colour with varying alpha");
    }

    private static void threeScales() throws Exception {
        double[] periods = {7.3, 31.0, 79.0};
        SpatialPreviewFilter.Settings[] positive = {settings(1, 0, 0), settings(0, 1, 0), settings(0, 0, 1)};
        SpatialPreviewFilter.Settings[] negative = {settings(-1, 0, 0), settings(0, -1, 0), settings(0, 0, -1)};
        for (int scale = 0; scale < 3; scale++) {
            int[] source = wave(193, 31, periods[scale]);
            int[] enhanced = render(source, 193, 31, positive[scale], IDENTITY, 100);
            int[] softened = render(source, 193, 31, negative[scale], IDENTITY, 100);
            check(!Arrays.equals(source, enhanced), "scale " + scale + " has visible enhancement");
            check(!Arrays.equals(source, softened), "scale " + scale + " has visible softening");
            check(gradientEnergy(softened, 193, 31) < gradientEnergy(source, 193, 31), "scale " + scale + " lowers gradient energy");
            check(gradientEnergy(enhanced, 193, 31) > gradientEnergy(source, 193, 31), "scale " + scale + " raises gradient energy");
        }
    }

    private static void haloLimits() throws Exception {
        int width = 113, height = 37;
        int[] source = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) source[y * width + x] = gray(x == 29 || (x >= 57 && x < 89) ? 190 : 70);
        }
        for (SpatialPreviewFilter.Settings setting : new SpatialPreviewFilter.Settings[] {
                ALL, settings(-1, -1, -1), settings(0, -1, 0), settings(0, 0, -1), settings(1, -.8, .6)}) {
            int[] result = render(source, width, height, setting, IDENTITY, 100);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int lo = 255, hi = 0;
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        int value = source[clamp(y + dy, height) * width + clamp(x + dx, width)] & 255;
                        lo = Math.min(lo, value); hi = Math.max(hi, value);
                    }
                    int value = result[y * width + x] & 255;
                    check(value >= lo && value <= hi, "no bright/dark local overshoot");
                    if (lo == hi) eq(source[y * width + x], result[y * width + x], "flat area stays flat near remote edge");
                }
            }
        }
    }

    private static void noiseGate() throws Exception {
        int[] source = new int[91 * 67];
        Random random = new Random(113);
        for (int i = 0; i < source.length; i++) source[i] = gray(128 + random.nextInt(3) - 1);
        arrayEq(source, render(source, 91, 67, ALL, IDENTITY, 100), "one-level sensor noise not amplified");
    }

    private static void transparentEdges() throws Exception {
        int width = 53, height = 77;
        int[] source = wave(width, height, 14.7), altered = source.clone();
        Random random = new Random(42);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int i = y * width + x, alpha = (x < 21 || (x + y) % 13 == 0) ? 0 : random.nextInt(255) + 1;
            source[i] = (alpha << 24) | (source[i] & 0xffffff);
            altered[i] = alpha == 0 ? random.nextInt() & 0xffffff : source[i];
        }
        IntUnaryOperator transform = p -> 0x17000000 | ((p ^ 0x0037192b) & 0xffffff);
        int[] result = render(source, width, height, ALL, transform, 100);
        int[] compare = render(altered, width, height, ALL, transform, 100);
        for (int i = 0; i < source.length; i++) {
            eq(source[i] >>> 24, result[i] >>> 24, "alpha preserved after colour and space");
            if ((source[i] >>> 24) != 0) eq(result[i], compare[i], "invisible RGB does not affect visible result");
        }
    }

    private static void spatialStrength() throws Exception {
        int width = 83, height = 67;
        int[] source = wave(width, height, 17.1);
        for (int i = 0; i < source.length; i++) source[i] = ((i * 71 & 255) << 24) | (source[i] & 0xffffff);
        IntUnaryOperator transform = p -> 0x99000000 | (((p >>> 16) & 255) << 16) | ((255 - (p & 255)) << 8) | 81;
        int[] full = render(source, width, height, settings(.8, -.4, .7), transform, 100);
        for (int strength : new int[] {1, 37, 50, 99}) {
            int[] result = render(source, width, height, settings(.8, -.4, .7), transform, strength);
            for (int i = 0; i < source.length; i++) eq(mix(source[i], full[i], strength), result[i], "blend includes spatial result");
        }
    }

    private static void inPlaceSafety() throws Exception {
        int width = 47, height = 233;
        int[] source = wave(width, height, 11.3);
        ArrayPixels pixels = new ArrayPixels(source.clone(), width, height);
        AtomicInteger conversions = new AtomicInteger();
        SpatialPreviewFilter.apply(pixels, ALL, p -> { conversions.incrementAndGet(); return p ^ 0x00271935; }, 100, null, null);
        eq(width * height, conversions.get(), "one colour evaluation per pixel");
        eq(height, pixels.reads, "each source row read exactly once");
        eq(height, pixels.writes, "each output row written exactly once");
        eq(1, pixels.maxRows, "adapter requests bounded to one row");
        // The strict adapter rejects any read-after-write, non-monotonic read or write.
        // A wrapper is also allowed to reread the current row before it commits it.
        int[] destination = source.clone();
        SpatialPreviewFilter.Pixels wrapped = new SpatialPreviewFilter.Pixels() {
            public int width() { return width; }
            public int height() { return height; }
            public void read(int[] row, int first, int rows) { System.arraycopy(destination, first * width, row, 0, width * rows); }
            public void write(int[] row, int first, int rows) {
                int[] before = new int[width * rows];
                read(before, first, rows);
                for (int i = 0; i < before.length; i++) before[i] = mix(before[i], row[i], 37);
                System.arraycopy(before, 0, destination, first * width, before.length);
            }
        };
        SpatialPreviewFilter.apply(wrapped, ALL, p -> p ^ 0x00271935, 100, null, null);
        for (int i = 0; i < source.length; i++) eq(mix(source[i], pixels.data[i], 37), destination[i], "precommit strength wrapper");
    }

    private static void noRingSeams() throws Exception {
        int width = 31, height = 167;
        int[] source = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int phase = y % 64;
            source[y * width + x] = gray((int) Math.round(126 + 58 * Math.sin(phase * Math.PI / 32)
                    + 27 * Math.sin((x + phase) * Math.PI * 2 / 9)));
        }
        int[] result = render(source, width, height, settings(.9, -.5, .8), IDENTITY, 100);
        for (int y = 20; y <= 80; y++) for (int x = 0; x < width; x++) {
            eq(result[y * width + x], result[(y + 64) * width + x], "periodic feature unchanged across cached-row wrap");
        }
    }

    private static void symmetry() throws Exception {
        int width = 43, height = 71;
        int[] source = new int[width * height], flipped = new int[source.length], transposed = new int[source.length];
        Random random = new Random(711);
        for (int i = 0; i < source.length; i++) source[i] = gray(random.nextInt(256));
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            flipped[(height - 1 - y) * width + width - 1 - x] = source[y * width + x];
            transposed[x * height + y] = source[y * width + x];
        }
        int[] actual = render(source, width, height, settings(.6, -.7, .8), IDENTITY, 100);
        int[] mirror = render(flipped, width, height, settings(.6, -.7, .8), IDENTITY, 100);
        int[] transpose = render(transposed, height, width, settings(.6, -.7, .8), IDENTITY, 100);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            eq(actual[y * width + x], mirror[(height - 1 - y) * width + width - 1 - x], "reflect square response");
            eq(actual[y * width + x], transpose[x * height + y], "transpose square response");
        }
    }

    private static void progressAndCancellation() throws Exception {
        int width = 37, height = 103;
        ArrayPixels pixels = new ArrayPixels(wave(width, height, 19), width, height);
        double[] previous = {-1};
        AtomicInteger updates = new AtomicInteger();
        SpatialPreviewFilter.apply(pixels, ALL, IDENTITY, 100, () -> false, value -> {
            check(value >= previous[0] && value <= 1 && value >= 0, "progress bounded/monotone");
            previous[0] = value; updates.incrementAndGet();
        });
        check(previous[0] == 1 && updates.get() == height + 1, "all output rows account for progress");
        ArrayPixels cancelled = new ArrayPixels(wave(width, height, 19), width, height);
        AtomicBoolean stop = new AtomicBoolean();
        expect(InterruptedIOException.class, () -> SpatialPreviewFilter.apply(cancelled, ALL, IDENTITY, 100,
                stop::get, value -> { if (value >= .3) stop.set(true); }));
        check(cancelled.writes > 0 && cancelled.writes < height, "cancelled output remains explicitly incomplete");
        ArrayPixels beforeRead = new ArrayPixels(wave(width, height, 19), width, height);
        expect(InterruptedIOException.class, () -> SpatialPreviewFilter.apply(beforeRead, ALL, IDENTITY, 100, () -> true, null));
        eq(0, beforeRead.reads, "already cancelled does no reads");
    }

    private static void wideCancellation() throws Exception {
        for (SpatialPreviewFilter.Settings setting : new SpatialPreviewFilter.Settings[] {ALL, SpatialPreviewFilter.NONE}) {
            int width = 8192;
            ArrayPixels pixels = new ArrayPixels(new int[width], width, 1);
            AtomicBoolean stop = new AtomicBoolean();
            AtomicInteger converted = new AtomicInteger();
            expect(InterruptedIOException.class, () -> SpatialPreviewFilter.apply(pixels, setting, p -> {
                if (converted.incrementAndGet() == 601) stop.set(true);
                return p;
            }, 100, stop::get, null));
            check(converted.get() <= 856, "wide row notices cancellation within 256 pixels");
            eq(0, pixels.writes, "no half-row write after cancel");
        }
        SpatialPreviewFilter.Pixels interruptedWrite = new SpatialPreviewFilter.Pixels() {
            public int width() { return 1; }
            public int height() { return 1; }
            public void read(int[] row, int first, int rows) { row[0] = gray(80); }
            public void write(int[] row, int first, int rows) { Thread.currentThread().interrupt(); }
        };
        try {
            double[] progress = {0};
            expect(InterruptedIOException.class, () -> SpatialPreviewFilter.apply(interruptedWrite, ALL, IDENTITY, 100, null, v -> progress[0] = v));
            check(progress[0] < 1, "interrupted commit does not report completed progress");
            check(Thread.currentThread().isInterrupted(), "commit interruption flag preserved");
        } finally {
            Thread.interrupted();
        }
        try {
            Thread.currentThread().interrupt();
            expect(InterruptedIOException.class, () -> SpatialPreviewFilter.apply(new ArrayPixels(new int[1], 1, 1), ALL, IDENTITY, 100, null, null));
            check(Thread.currentThread().isInterrupted(), "interrupted status preserved");
        } finally {
            Thread.interrupted();
        }
    }

    private static void ioFailures() throws Exception {
        for (SpatialPreviewFilter.Settings settings : new SpatialPreviewFilter.Settings[] {ALL, SpatialPreviewFilter.NONE})
        for (boolean onRead : new boolean[] {true, false}) {
            IOException expected = new IOException("storage failure");
            AtomicInteger writes = new AtomicInteger();
            double[] progress = {0};
            SpatialPreviewFilter.Pixels failing = new SpatialPreviewFilter.Pixels() {
                public int width() { return 29; }
                public int height() { return 101; }
                public void read(int[] row, int first, int rows) throws IOException {
                    if (onRead && first == 23) throw expected;
                    Arrays.fill(row, gray(110 + first % 41));
                }
                public void write(int[] row, int first, int rows) throws IOException {
                    if (!onRead && first == 7) throw expected;
                    writes.incrementAndGet();
                }
            };
            try {
                SpatialPreviewFilter.apply(failing, settings, IDENTITY, 100, null, value -> progress[0] = value);
                throw new AssertionError("storage error swallowed");
            } catch (IOException error) {
                check(error == expected, "original I/O error retained");
            }
            check(writes.get() < 101 && progress[0] < 1, "I/O failure never claims complete");
        }
    }

    private static void validation() throws Exception {
        for (double bad : new double[] {Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -1.0001, 1.0001}) {
            expect(IllegalArgumentException.class, () -> settings(bad, 0, 0));
            expect(IllegalArgumentException.class, () -> settings(0, bad, 0));
            expect(IllegalArgumentException.class, () -> settings(0, 0, bad));
        }
        for (int[] dims : new int[][] {{0, 10}, {10, 0}, {-1, 1}, {1, -1}}) {
            expect(IllegalArgumentException.class, () -> SpatialPreviewFilter.apply(dimensions(dims[0], dims[1]), ALL, IDENTITY, 100, null, null));
        }
        for (int strength : new int[] {-1, 101}) {
            expect(IllegalArgumentException.class, () -> SpatialPreviewFilter.apply(dimensions(1, 1), ALL, IDENTITY, strength, null, null));
        }
        expect(IOException.class, () -> SpatialPreviewFilter.apply(dimensions(Integer.MAX_VALUE, 500), ALL, IDENTITY, 100, null, null));
        expect(IOException.class, () -> SpatialPreviewFilter.apply(dimensions(Integer.MAX_VALUE, 1), SpatialPreviewFilter.NONE, IDENTITY, 100, null, null));
        check(settings(-0.0, 0, -0.0).isNone(), "signed zero takes exact fast path");
    }

    private static void streamingStress() throws Exception {
        // 30.6 million virtual pixels would require 122 MB for even one int[] frame.
        // Rows are generated on demand; the complete suite runs with -Xmx64m.
        int width = 17, height = 1_800_001;
        int[] reads = {0}, writes = {0};
        SpatialPreviewFilter.Pixels stream = new SpatialPreviewFilter.Pixels() {
            public int width() { return width; }
            public int height() { return height; }
            public void read(int[] row, int first, int rows) {
                eq(1, rows, "stream read has one row");
                eq(reads[0], first, "stream source never reread");
                check(first >= writes[0], "stream source not already committed");
                for (int x = 0; x < width; x++) row[x] = gray(60 + ((first % 83) * 3 + x * 7) % 136);
                reads[0]++;
            }
            public void write(int[] row, int first, int rows) {
                eq(1, rows, "stream writes one row");
                eq(writes[0], first, "stream commits in order");
                check(reads[0] > first, "source retained before commit");
                for (int x = 0; x < width; x++) check((row[x] >>> 24) == 255, "stream keeps alpha");
                writes[0]++;
            }
        };
        SpatialPreviewFilter.apply(stream, settings(.6, -.4, .8), IDENTITY, 61, null, null);
        eq(height, reads[0], "large stream reads all rows");
        eq(height, writes[0], "large stream commits all rows");
    }

    private static int[] render(int[] original, int width, int height, SpatialPreviewFilter.Settings settings,
            IntUnaryOperator transform, int strength) throws IOException {
        ArrayPixels pixels = new ArrayPixels(original.clone(), width, height);
        SpatialPreviewFilter.apply(pixels, settings, transform, strength, null, null);
        return pixels.data;
    }

    private static int[] wave(int width, int height, double period) {
        int[] source = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            source[y * width + x] = gray((int) Math.round(128 + 71 * Math.sin(x * Math.PI * 2 / period)));
        }
        return source;
    }

    private static double gradientEnergy(int[] pixels, int width, int height) {
        double energy = 0;
        for (int y = 0; y < height; y++) for (int x = 1; x < width; x++) {
            int difference = (pixels[y * width + x] & 255) - (pixels[y * width + x - 1] & 255);
            energy += difference * difference;
        }
        return energy;
    }

    private static SpatialPreviewFilter.Pixels dimensions(int width, int height) {
        return new SpatialPreviewFilter.Pixels() {
            public int width() { return width; }
            public int height() { return height; }
            public void read(int[] row, int first, int rows) { throw new AssertionError("invalid dimensions must not read"); }
            public void write(int[] row, int first, int rows) { throw new AssertionError("invalid dimensions must not write"); }
        };
    }

    private static final class ArrayPixels implements SpatialPreviewFilter.Pixels {
        final int[] data;
        final int width, height;
        int reads, writes, maxRows;
        ArrayPixels(int[] data, int width, int height) { this.data = data; this.width = width; this.height = height; }
        public int width() { return width; }
        public int height() { return height; }
        public void read(int[] row, int first, int rows) {
            eq(reads, first, "strict monotonic source reads");
            check(first >= writes, "source read before row overwritten");
            check(rows == 1 && first < height, "valid source row");
            System.arraycopy(data, first * width, row, 0, width * rows);
            reads += rows; maxRows = Math.max(maxRows, rows);
        }
        public void write(int[] row, int first, int rows) {
            eq(writes, first, "strict monotonic commits");
            check(first < reads && rows == 1, "source row loaded before commit");
            System.arraycopy(row, 0, data, first * width, width * rows);
            writes += rows; maxRows = Math.max(maxRows, rows);
        }
    }

    private static int gray(int value) { return 0xff000000 | (value << 16) | (value << 8) | value; }
    private static int clamp(int value, int max) { return Math.max(0, Math.min(max - 1, value)); }
    private static SpatialPreviewFilter.Settings settings(double sharp, double mid, double clarity) {
        return new SpatialPreviewFilter.Settings(sharp, mid, clarity);
    }
    private static int mix(int original, int filtered, int strength) {
        int red = (((original >>> 16) & 255) * (100 - strength) + ((filtered >>> 16) & 255) * strength + 50) / 100;
        int green = (((original >>> 8) & 255) * (100 - strength) + ((filtered >>> 8) & 255) * strength + 50) / 100;
        int blue = ((original & 255) * (100 - strength) + (filtered & 255) * strength + 50) / 100;
        return (original & 0xff000000) | (red << 16) | (green << 8) | blue;
    }
    private static void arrayEq(int[] expected, int[] actual, String message) {
        check(Arrays.equals(expected, actual), message);
    }
    private static void eq(int expected, int actual, String message) {
        check(expected == actual, message + ": " + expected + " != " + actual);
    }
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static void expect(Class<? extends Throwable> type, Throwing action) throws Exception {
        try { action.run(); }
        catch (Throwable error) { check(type.isInstance(error), "expected " + type + " got " + error); return; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void run(String name, Throwing action) throws Exception {
        action.run(); cases++; System.out.println("PASS " + name);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}

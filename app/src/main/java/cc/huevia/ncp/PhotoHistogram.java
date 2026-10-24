package cc.huevia.ncp;

import java.util.Locale;

/** Immutable 8-bit encoded-sRGB preview statistics. This is not a RAW exposure measurement. */
public final class PhotoHistogram {
    public enum Channel { LUMINANCE, RED, GREEN, BLUE }
    private final long[][] counts;
    private final long[] maxima;
    private final long pixels, highlights, shadows;

    private PhotoHistogram(Builder builder) {
        counts = new long[Channel.values().length][];
        maxima = new long[counts.length];
        for (int c = 0; c < counts.length; c++) {
            counts[c] = builder.counts[c].clone();
            for (long count : counts[c]) maxima[c] = Math.max(maxima[c], count);
        }
        pixels = builder.pixels; highlights = builder.highlights; shadows = builder.shadows;
    }

    public static PhotoHistogram fromArgb(int[] pixels) {
        if (pixels == null) throw new IllegalArgumentException("预览像素不可用。");
        return new Builder().addArgb(pixels, 0, pixels.length).build();
    }

    public boolean hasData() { return pixels > 0; }
    public long pixelCount() { return pixels; }
    public long highlightCount() { return highlights; }
    public long shadowCount() { return shadows; }
    public long count(Channel channel, int bin) {
        if (bin < 0 || bin > 255) throw new IllegalArgumentException("直方图区间须为 0–255。");
        return counts[channel.ordinal()][bin];
    }
    public long[] bins(Channel channel) { return counts[channel.ordinal()].clone(); }
    public long max(Channel channel) { return maxima[channel.ordinal()]; }
    /** All RGB curves must use this one denominator, never their individual maxima. */
    public long sharedRgbMaximum() { return Math.max(maxima[1], Math.max(maxima[2], maxima[3])); }
    public String highlightPercent() { return percent(highlights, pixels); }
    public String shadowPercent() { return percent(shadows, pixels); }

    /** Encoded sRGB display-luminance indicator; no conversion to linear light. */
    public static int luminance(int argb) {
        return (int)Math.round(.2126 * ((argb >>> 16) & 255)
                + .7152 * ((argb >>> 8) & 255) + .0722 * (argb & 255));
    }
    public static boolean isHighlight(int argb) {
        return ((argb >>> 16) & 255) == 255 || ((argb >>> 8) & 255) == 255 || (argb & 255) == 255;
    }
    public static boolean isShadow(int argb) { return (argb & 0xffffff) == 0; }
    /** No-data is deliberately different from a measured zero. */
    public static String percent(long hits, long total) {
        if (total < 0 || hits < 0 || hits > total) throw new IllegalArgumentException("预览统计计数无效。");
        if (total == 0) return "—";
        if (hits == 0) return "0%";
        double value = hits * 100.0 / total;
        if (value < .1) return "<0.1%";
        return String.format(Locale.ROOT, "%.1f%%", value);
    }

    /** Row-wise accumulation avoids holding a second full preview pixel array. Alpha is not a channel:
     * callers must supply opaque sRGB working content, including the existing PNG white composite. */
    public static final class Builder {
        private final long[][] counts = new long[Channel.values().length][256];
        private long pixels, highlights, shadows;
        public Builder addArgb(int argb) {
            if (pixels == Long.MAX_VALUE) throw new IllegalStateException("预览像素数量超出范围。");
            counts[0][luminance(argb)]++;
            counts[1][(argb >>> 16) & 255]++;
            counts[2][(argb >>> 8) & 255]++;
            counts[3][argb & 255]++;
            pixels++;
            if (isHighlight(argb)) highlights++;
            if (isShadow(argb)) shadows++;
            return this;
        }
        public Builder addArgb(int[] source, int offset, int count) {
            if (source == null || offset < 0 || count < 0 || offset > source.length - count)
                throw new IllegalArgumentException("预览像素范围无效。");
            for (int i = offset; i < offset + count; i++) addArgb(source[i]);
            return this;
        }
        public PhotoHistogram build() { return new PhotoHistogram(this); }
    }
}

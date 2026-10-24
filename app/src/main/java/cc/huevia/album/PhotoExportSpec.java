package cc.huevia.album;

/** Immutable export intent. Identities describe actual pixels and encoding, never a UI tab name. */
public final class PhotoExportSpec {
    public enum Mode { FULL, SHARE, CUSTOM }
    public static final int SHARE_LONG_EDGE = 2048, MIN_CUSTOM_EDGE = 640, MAX_CUSTOM_EDGE = 8192;
    public static final String METADATA_POLICY = "capture-no-gps-v1";
    private static final PhotoExportSpec FULL = new PhotoExportSpec(Mode.FULL, 0, 95);
    private static final PhotoExportSpec SHARE = new PhotoExportSpec(Mode.SHARE, SHARE_LONG_EDGE, 90);
    public final Mode mode;
    public final int longEdge, quality;
    private PhotoExportSpec(Mode mode, int longEdge, int quality) { this.mode = mode; this.longEdge = longEdge; this.quality = quality; }
    public static PhotoExportSpec full() { return FULL; }
    public static PhotoExportSpec share() { return SHARE; }
    public static PhotoExportSpec custom(int longEdge, int quality) {
        if (longEdge < MIN_CUSTOM_EDGE || longEdge > MAX_CUSTOM_EDGE) throw new IllegalArgumentException("自定义长边须为 640–8192 px。");
        if (quality < 70 || quality > 100) throw new IllegalArgumentException("JPEG 质量须为 70–100。");
        return new PhotoExportSpec(Mode.CUSTOM, longEdge, quality);
    }
    public Resolved resolve(int fullWidth, int fullHeight) {
        if (fullWidth < 1 || fullHeight < 1 || fullWidth > 65535 || fullHeight > 65535) throw new IllegalArgumentException("完整成品尺寸无效。");
        int fullEdge = Math.max(fullWidth, fullHeight);
        // A batch choice is a per-photo ceiling: smaller finished canvases retain their dimensions.
        // The single-photo form separately disables custom below640 and rejects an input over its own full edge.
        int edge = mode == Mode.FULL ? fullEdge : Math.min(fullEdge, longEdge);
        int width = fullWidth >= fullHeight ? edge : Math.max(1, (int)Math.round(fullWidth * (double)edge / fullEdge));
        int height = fullHeight >= fullWidth ? edge : Math.max(1, (int)Math.round(fullHeight * (double)edge / fullEdge));
        return new Resolved(fullWidth, fullHeight, width, height, quality);
    }
    public static final class Resolved {
        public final int fullWidth, fullHeight, width, height, quality;
        private Resolved(int fullWidth, int fullHeight, int width, int height, int quality) {
            this.fullWidth = fullWidth; this.fullHeight = fullHeight; this.width = width; this.height = height; this.quality = quality;
        }
        public boolean isFullJpeg95() { return width == fullWidth && height == fullHeight && quality == 95; }
        public String identity() { return identity(width, height, quality); }
        public static String identity(int width, int height, int quality) { return "jpeg-srgb-v1:" + width + "x" + height + ":q" + quality + ":" + METADATA_POLICY; }
    }
}

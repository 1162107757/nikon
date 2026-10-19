package android.graphics;
/** Host-only sRGB identity. It is not an Android color-management implementation. */
public final class ColorSpace {
    public enum Named { SRGB }
    private static final ColorSpace SRGB = new ColorSpace();
    public static ColorSpace get(Named named) { return SRGB; }
}

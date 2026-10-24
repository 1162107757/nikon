package cc.huevia.album;

/** Pure rules only; actual bitmap/EXIF/JPEG quality and composition are native acceptance checks. */
public final class PhotoExportSpecTest {
    private static int checks;
    public static void main(String[] args) {
        expect(PhotoExportSpec.full().resolve(12001, 8001), 12001, 8001, 95);
        expect(PhotoExportSpec.share().resolve(6001, 4001), 2048, 1365, 90);
        expect(PhotoExportSpec.share().resolve(4001, 6001), 1365, 2048, 90);
        expect(PhotoExportSpec.share().resolve(319, 511), 319, 511, 90);
        expect(PhotoExportSpec.share().resolve(65535, 1), 2048, 1, 90);
        expect(PhotoExportSpec.custom(640, 70).resolve(640, 1), 640, 1, 70);
        expect(PhotoExportSpec.custom(8192, 100).resolve(65535, 65535), 8192, 8192, 100);
        expect(PhotoExportSpec.custom(1000, 95).resolve(9001, 6001), 1000, 667, 95);
        check(PhotoExportSpec.full().resolve(640, 400).isFullJpeg95(), "Full means natural finished-canvas size and Q95");
        check(PhotoExportSpec.custom(640, 95).resolve(640, 400).isFullJpeg95(), "Custom equal to natural full Q95 is the same result");
        check(PhotoExportSpec.share().resolve(6000, 4000).identity().equals(PhotoExportSpec.custom(2048, 90).resolve(6000, 4000).identity()), "Names never split identical actual specs");
        check(!PhotoExportSpec.custom(2048, 90).resolve(6000, 4000).identity().equals(PhotoExportSpec.custom(2048, 95).resolve(6000, 4000).identity()), "Quality isolates results even at equal dimensions");
        check(!PhotoExportSpec.share().resolve(6000, 4000).identity().equals(PhotoExportSpec.share().resolve(4000, 6000).identity()), "Pixel orientation isolates actual specs");
        invalid(() -> PhotoExportSpec.custom(639, 90)); invalid(() -> PhotoExportSpec.custom(8193, 90));
        invalid(() -> PhotoExportSpec.custom(640, 69)); invalid(() -> PhotoExportSpec.custom(640, 101));
        expect(PhotoExportSpec.custom(640, 90).resolve(639, 400), 639, 400, 90);
        expect(PhotoExportSpec.custom(2048, 90).resolve(2000, 1500), 2000, 1500, 90);
        invalid(() -> PhotoExportSpec.full().resolve(0, 100)); invalid(() -> PhotoExportSpec.full().resolve(100, 65536));
        for (int width = 1; width < 65536; width += 443) for (int height = 1; height < 65536; height += 1373) {
            PhotoExportSpec.Resolved r = PhotoExportSpec.share().resolve(width, height);
            check(r.width >= 1 && r.height >= 1 && r.width <= width && r.height <= height, "No enlargement and no zero axes");
            check(Math.max(r.width, r.height) == Math.min(2048, Math.max(width, height)), "The final framed long edge is exact");
        }
        System.out.println("PASS: " + checks + " export sizing/identity assertions");
    }
    private static void expect(PhotoExportSpec.Resolved r, int w, int h, int q) { check(r.width == w && r.height == h && r.quality == q, "Expected " + w + "x" + h + "/Q" + q); }
    private static void invalid(Runnable action) { try { action.run(); throw new AssertionError("Invalid export was accepted"); } catch (IllegalArgumentException expected) { checks++; } }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}

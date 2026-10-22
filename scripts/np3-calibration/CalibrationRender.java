import cc.huevia.ncp.PresetPreviewProfile;
import cc.huevia.ncp.PresetPreviewProfiles;
import cc.huevia.ncp.SpatialPreviewFilter;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/** Calibration adapter only: invokes the same full-size colour/spatial path as the app. */
public final class CalibrationRender {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("A-normalized.png recipe.NP3 C.png");
        BufferedImage image = ImageIO.read(new File(args[0]));
        if (image == null) throw new IllegalArgumentException("Unable to decode normalized sRGB A");
        PresetPreviewProfile profile = PresetPreviewProfiles.fromPreset(Files.readAllBytes(new File(args[1]).toPath()));
        profile.renderPixels(new SpatialPreviewFilter.Pixels() {
            public int width() { return image.getWidth(); }
            public int height() { return image.getHeight(); }
            public void read(int[] pixels, int row, int rows) {
                image.getRGB(0, row, width(), rows, pixels, 0, width());
            }
            public void write(int[] pixels, int row, int rows) {
                image.setRGB(0, row, width(), rows, pixels, 0, width());
            }
        }, () -> false, null);
        if (!ImageIO.write(image, "PNG", new File(args[2]))) throw new IllegalArgumentException("PNG encoder unavailable");
        System.out.println("MODEL=" + profile.modelVersion());
    }
}

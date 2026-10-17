import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Geometry-only fixtures for the local album UI server; never included in the APK. */
public final class SyntheticAlbumPhotos {
    public static void main(String[] args) throws IOException {
        File directory = new File(args.length == 0 ? "app/build/album-ui-fixtures" : args[0]);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create " + directory);
        for (int number = 1; number <= 49; number++) {
            BufferedImage image = new BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB);
            Graphics2D canvas = image.createGraphics();
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float hue = (number * 0.077f) % 1;
            canvas.setPaint(new GradientPaint(0, 0, Color.getHSBColor(hue, .75f, .65f),
                    1600, 1000, Color.getHSBColor((hue + .14f) % 1, .55f, .25f)));
            canvas.fillRect(0, 0, 1600, 1000);
            canvas.setColor(new Color(255, 255, 255, 30));
            for (int x = 0; x <= 1600; x += 100) canvas.drawLine(x, 0, x, 1000);
            for (int y = 0; y <= 1000; y += 100) canvas.drawLine(0, y, 1600, y);
            canvas.setStroke(new BasicStroke(6));
            canvas.setColor(new Color(255, 255, 255, 150));
            canvas.drawOval(980, 130, 360, 360);
            canvas.drawRect(100, 100, 1400, 800);
            canvas.setColor(Color.WHITE);
            canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 230));
            canvas.drawString(String.format("%02d", number), 130, 435);
            canvas.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 74));
            canvas.drawString("SYNTHETIC / TEST PHOTO", 135, 620);
            canvas.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 42));
            canvas.drawString("1600 x 1000  |  Local PTP/IP fixture", 140, 700);
            canvas.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 20));
            canvas.drawString("Zoom check: 0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ", 140, 780);
            canvas.dispose();
            File original = new File(directory, String.format("TEST_%04d.JPG", number));
            if (!ImageIO.write(image, "jpeg", original)) throw new IOException("JPEG writer missing");
            BufferedImage large = new BufferedImage(1280, 800, BufferedImage.TYPE_INT_RGB);
            Graphics2D largePreview = large.createGraphics();
            largePreview.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            largePreview.drawImage(image, 0, 0, 1280, 800, null);
            largePreview.dispose();
            if (!ImageIO.write(large, "jpeg", new File(directory, String.format("LARGE_%04d.JPG", number)))) {
                throw new IOException("JPEG writer missing");
            }
            BufferedImage thumb = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
            Graphics2D thumbnail = thumb.createGraphics();
            thumbnail.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            thumbnail.drawImage(image, 0, 0, 320, 200, null);
            thumbnail.dispose();
            if (!ImageIO.write(thumb, "jpeg", new File(directory, String.format("THUMB_%04d.JPG", number)))) {
                throw new IOException("JPEG writer missing");
            }
        }
        System.out.println("Created 49 SYNTHETIC JPEGs (1600x1000), large previews (1280x800) and thumbnails (320x200) in " + directory.getCanonicalPath());
    }
}

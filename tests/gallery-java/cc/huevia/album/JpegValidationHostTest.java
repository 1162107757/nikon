package cc.huevia.album;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/** Real baseline/progressive JPEGs: primary EOI is not necessarily the physical file end. */
public final class JpegValidationHostTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("jpeg-validation-");
        try {
            for (boolean progressive : new boolean[]{false, true}) {
                byte[] jpeg = jpeg(progressive);
                accept(root, jpeg);
                accept(root, join(jpeg, new byte[]{12, 34, 56, 78, 0}));
                accept(root, join(jpeg, new byte[]{0, (byte)255, (byte)217}));
                accept(root, join(jpeg, jpeg));
                // Embedded APP JPEG must be skipped using its segment length.
                ByteArrayOutputStream embedded = new ByteArrayOutputStream();
                DataOutputStream app = new DataOutputStream(embedded);
                app.writeShort(0xffd8); app.writeShort(0xffe1); app.writeShort(jpeg.length + 2); app.write(jpeg); app.write(jpeg, 2, jpeg.length - 2);
                accept(root, embedded.toByteArray());
                reject(root, Arrays.copyOf(jpeg, jpeg.length - 2));
                reject(root, join(Arrays.copyOf(jpeg, jpeg.length - 2), new byte[]{0, 0, 0}));
                reject(root, new byte[]{(byte)255,(byte)216,(byte)255,(byte)217});
                reject(root, join(new byte[]{(byte)255,(byte)216,(byte)255,(byte)217}, jpeg));
                reject(root, new byte[]{(byte)255,(byte)216,(byte)255,(byte)225,0,1,(byte)255,(byte)217});
                // An APP thumbnail cannot supply the outer frame, scan or EOI.
                byte[] thumbnailOnly = Arrays.copyOf(embedded.toByteArray(), jpeg.length + 6);
                reject(root, join(thumbnailOnly, new byte[]{(byte)255,(byte)217}));
                Path file = root.resolve("cancel.jpg"); Files.write(file, jpeg);
                try { PhotoPreviewCache.validateCompleteJpeg(file.toFile(), () -> true); throw new AssertionError("cancel accepted"); }
                catch (InterruptedIOException expected) { checks++; }
                try { PhotoPreviewCache.validateJpeg(file.toFile(), jpeg.length + 1); throw new AssertionError("size mismatch accepted"); }
                catch (IOException expected) { checks++; }
                byte[] broken = jpeg.clone(); broken[broken.length - 1] = 0;
                try { PhotoPreviewCache.validateJpeg(write(root, broken).toFile(), broken.length); throw new AssertionError("missing EOI accepted"); }
                catch (IOException expected) { checks++; }
            }
            for (String arg : args) {
                Path file = Path.of(arg); byte[] before = Files.readAllBytes(file);
                PhotoPreviewCache.validateCompleteJpeg(file.toFile(), () -> false);
                PhotoPreviewCache.validateJpeg(file.toFile(), before.length);
                check(Arrays.equals(before, Files.readAllBytes(file)), "real fixture changed");
            }
            System.out.println("PASS: " + checks + " JPEG primary-stream and suffix checks");
        } finally {
            try (var files = Files.walk(root)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    private static Path write(Path root, byte[] bytes) throws Exception { Path p = Files.createTempFile(root, "image-", ".jpg"); Files.write(p, bytes); return p; }
    private static void accept(Path root, byte[] bytes) throws Exception {
        Path p = write(root, bytes); PhotoPreviewCache.validateCompleteJpeg(p.toFile(), () -> false); checks++;
        PhotoPreviewCache.validateJpeg(p.toFile(), bytes.length); checks++;
        check(ImageIO.read(p.toFile()) != null, "accepted fixture cannot decode");
        check(Arrays.equals(bytes, Files.readAllBytes(p)), "validation changed bytes");
    }
    private static void reject(Path root, byte[] bytes) throws Exception {
        Path p = write(root, bytes);
        try { PhotoPreviewCache.validateCompleteJpeg(p.toFile(), () -> false); throw new AssertionError("incomplete primary accepted"); }
        catch (IOException expected) { checks++; }
    }
    private static byte[] join(byte[] a, byte[] b) { byte[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b,0,r,a.length,b.length); return r; }
    private static byte[] jpeg(boolean progressive) throws Exception {
        BufferedImage image = new BufferedImage(32,24,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<24;y++)for(int x=0;x<32;x++)image.setRGB(x,y,(x*8<<16)|(y*10<<8)|((x+y)*4));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageWriter writer = ImageIO.getImageWritersByFormatName("JPEG").next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output); ImageWriteParam params = writer.getDefaultWriteParam();
            if(progressive)params.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null,new javax.imageio.IIOImage(image,null,null),params);
        } finally { writer.dispose(); }
        return bytes.toByteArray();
    }
    private static void check(boolean ok, String message) { if(!ok)throw new AssertionError(message); checks++; }
}

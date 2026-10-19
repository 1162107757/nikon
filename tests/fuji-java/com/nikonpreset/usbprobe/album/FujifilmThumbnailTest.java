package com.nikonpreset.usbprobe.album;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Real decodable JPEGs plus adversarial boundaries; no camera payload is fabricated as user evidence. */
final class FujifilmThumbnailTest {
    static byte[] jpeg(boolean progressive) throws Exception {
        BufferedImage image = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 24; y++) for (int x = 0; x < 32; x++) image.setRGB(x, y, (x * 7 << 16) | (y * 9 << 8) | 80);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(stream);
            ImageWriteParam settings = writer.getDefaultWriteParam();
            if (progressive) settings.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), settings);
        } finally { writer.dispose(); }
        return output.toByteArray();
    }

    static void run() throws Exception {
        for (boolean progressive : new boolean[]{false, true}) {
            byte[] jpeg = jpeg(progressive);
            check(FujifilmPhotoClient.trimThumbnailSuffix(jpeg) == jpeg, "complete JPEG does not need a copy");
            for (int padding : new int[]{1, 2, 512, 21314 - jpeg.length}) {
                byte[] padded = Arrays.copyOf(jpeg, jpeg.length + padding);
                Arrays.fill(padded, jpeg.length, padded.length, (byte)0x5a);
                byte[] snapshot = padded.clone();
                byte[] result = FujifilmPhotoClient.trimThumbnailSuffix(padded);
                check(Arrays.equals(result, jpeg) && Arrays.equals(snapshot, padded), "trim only suffix; preserve input and every JPEG byte");
                BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(result));
                check(decoded != null && decoded.getWidth() == 32 && decoded.getHeight() == 24, "trimmed baseline/progressive JPEG decodes");
            }
            // An embedded JPEG's EOI in APP1 must never end the outer image.
            ByteArrayOutputStream exif = new ByteArrayOutputStream();
            exif.write(jpeg, 0, 2); exif.write(0xff); exif.write(0xe1);
            int segmentLength = 2 + jpeg.length;
            exif.write(segmentLength >>> 8); exif.write(segmentLength & 255); exif.write(jpeg);
            exif.write(jpeg, 2, jpeg.length - 2);
            byte[] outer = exif.toByteArray();
            byte[] padded = Arrays.copyOf(outer, outer.length + 24);
            check(Arrays.equals(FujifilmPhotoClient.trimThumbnailSuffix(padded), outer), "skip embedded APP1 JPEG rather than taking its EOI");
            for (int cut = 0; cut < outer.length; cut++) {
                byte[] truncated = Arrays.copyOf(outer, cut);
                check(FujifilmPhotoClient.trimThumbnailSuffix(truncated) == truncated, "never repair a truncated outer JPEG or select embedded EOI");
            }
        }
        // Minimal marker stream exercises stuffing, restart markers, fill bytes and multiple scans.
        byte[] scans = {(byte)0xff,(byte)0xd8, (byte)0xff,(byte)0xc2,0,11,8,0,1,0,1,1,1,0x11,0,
                (byte)0xff,(byte)0xda,0,8,1,1,0,0,0,0, 1,(byte)0xff,0,(byte)0xd9,2,(byte)0xff,(byte)0xd0,3,
                (byte)0xff,(byte)0xda,0,8,1,1,0,1,63,0, 4,(byte)0xff,(byte)0xff,(byte)0xd9};
        check(Arrays.equals(FujifilmPhotoClient.trimThumbnailSuffix(Arrays.copyOf(scans, scans.length + 10)), scans), "scan marker handling finds the outer EOI");
        for (int length : new int[]{0, 1, 65535}) {
            byte[] bad = {(byte)0xff,(byte)0xd8,(byte)0xff,(byte)0xe1,(byte)(length >>> 8),(byte)length,
                    (byte)0xff,(byte)0xd9,1,2,3};
            check(FujifilmPhotoClient.trimThumbnailSuffix(bad) == bad, "invalid segment length is not shortened");
        }
        for (byte[] bad : new byte[][]{new byte[0], {1,2,3}, {(byte)0xff,(byte)0xd8,(byte)0xff,(byte)0xd9,0},
                {(byte)0xff,(byte)0xd8,(byte)0xff}, {(byte)0xff,(byte)0xd8,(byte)0xff,0,1}}) {
            check(FujifilmPhotoClient.trimThumbnailSuffix(bad) == bad, "incomplete or non-image payload is not repaired");
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

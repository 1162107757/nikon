import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;

/** 24 MP synthetic source and EXIF/pixel inspection for Android batch integration checks. */
public final class SyntheticBatchPhoto {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].startsWith("make")) {
            BufferedImage image = new BufferedImage(6000, 4000, BufferedImage.TYPE_INT_RGB);
            int[] row = new int[6000];
            for (int y = 0; y < 4000; y++) {
                for (int x = 0; x < 6000; x++) row[x] = (((32 + x * 190 / 5999) << 16)
                        | ((24 + y * 200 / 3999) << 8) | (40 + (x + y) % 512 * 120 / 511))
                        ^ (args[0].equals("make-variant") ? 0x00301020 : 0);
                image.setRGB(0, y, 6000, 1, row, 0, 6000);
            }
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(); ImageIO.write(image, "jpeg", encoded);
            byte[] jpeg = encoded.toByteArray(), exif = exif();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(jpeg, 0, 2); output.write(0xff); output.write(0xe1);
            int length = exif.length + 2; output.write(length >>> 8); output.write(length & 255); output.write(exif);
            output.write(jpeg, 2, jpeg.length - 2); Files.write(Path.of(args[1]), output.toByteArray());
            System.out.println("Created SYNTHETIC 6000x4000 JPEG, orientation=6, DateTime=" + fixtureDate());
            return;
        }
        if (args.length == 2 && args[0].equals("inspect")) {
            byte[] bytes = Files.readAllBytes(Path.of(args[1]));
            BufferedImage image = ImageIO.read(Path.of(args[1]).toFile());
            if (image == null) throw new IllegalArgumentException("Not a JPEG");
            System.out.println("dimensions=" + image.getWidth() + "x" + image.getHeight() + " bytes=" + bytes.length
                    + " sha256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            for (int[] sample : new int[][]{{100,100},{1000,1000},{3000,2000},{5500,3500}}) {
                if (sample[0] < image.getWidth() && sample[1] < image.getHeight())
                    System.out.printf("pixel(%d,%d)=%08x%n",sample[0],sample[1],image.getRGB(sample[0],sample[1]));
            }
            for (int offset = 2; offset + 4 < bytes.length && (bytes[offset] & 255) == 255;) {
                int marker = bytes[offset + 1] & 255;
                if (marker == 0xda || marker == 0xd9) break;
                int length = ((bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
                int start = offset + 4;
                if (marker == 0xe1 && length >= 10 && new String(bytes,start,6,StandardCharsets.ISO_8859_1).equals("Exif\0\0")) {
                    int tiff = start + 6; ByteBuffer data = ByteBuffer.wrap(bytes);
                    data.order(bytes[tiff] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
                    int ifd = tiff + data.getInt(tiff + 4), count = data.getShort(ifd) & 65535;
                    for (int i = 0; i < count; i++) {
                        int entry = ifd + 2 + i * 12, tag = data.getShort(entry) & 65535;
                        if (tag == 0x112) System.out.println("orientation=" + (data.getShort(entry + 8) & 65535));
                        if (tag == 0x132) System.out.println("DateTime=" + new String(bytes,tiff + data.getInt(entry + 8),19,StandardCharsets.US_ASCII));
                    }
                    break;
                }
                offset += length + 2;
            }
            return;
        }
        throw new IllegalArgumentException("Usage: make file | inspect file");
    }
    private static byte[] exif() {
        ByteBuffer data = ByteBuffer.allocate(6 + 8 + 2 + 24 + 4 + 20).order(ByteOrder.LITTLE_ENDIAN);
        data.put("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
        data.put((byte)'I').put((byte)'I').putShort((short)42).putInt(8).putShort((short)2);
        data.putShort((short)0x112).putShort((short)3).putInt(1).putShort((short)6).putShort((short)0);
        data.putShort((short)0x132).putShort((short)2).putInt(20).putInt(38).putInt(0);
        data.put((fixtureDate() + "\0").getBytes(StandardCharsets.US_ASCII));
        return data.array();
    }
    private static String fixtureDate() {
        String date = System.getProperty("fixture.datetime", "2026:10:17 10:20:30");
        if (!date.matches("[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")) throw new IllegalArgumentException("Invalid fixture date");
        return date;
    }
}

import com.nikonpreset.usbprobe.ncp.NcpCompatibilityFixtures;
import com.nikonpreset.usbprobe.ncp.NcpFile;
import com.nikonpreset.usbprobe.ncp.NcpPictureControlConverter;
import com.nikonpreset.usbprobe.ncp.NcpPreviewProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Hand-authored preset fixtures for document-picker UI checks; never included in the APK. */
public final class SyntheticPresetPreview {
    public static void main(String[] args) throws IOException {
        if (args.length > 1) throw new IllegalArgumentException("Usage: SyntheticPresetPreview [output-directory]");
        Path directory = Path.of(args.length == 0 ? "app/build/preset-preview-fixtures" : args[0]);
        byte[] identity = preset("PreviewIdentity", false);
        byte[] toned = preset("PreviewTone", true);
        NcpPreviewProfile identityProfile = validate(identity);
        NcpPreviewProfile toneProfile = validate(toned);
        int[] probes = {0xff000000, 0xff204080, 0xff808080, 0xffe04020, 0xffffffff};
        if (!Arrays.equals(probes, identityProfile.render(probes, probes.length, 1))) {
            throw new IOException("Synthetic identity fixture changes its probe pixels.");
        }
        if (Arrays.equals(probes, toneProfile.render(probes, probes.length, 1))) {
            throw new IOException("Synthetic tone fixture does not visibly differ from identity.");
        }
        Files.createDirectories(directory);
        Files.write(directory.resolve("PreviewIdentity.NCP"), identity);
        Files.write(directory.resolve("PreviewTone.NCP"), toned);
        System.out.println("Created and validated two SYNTHETIC 638-byte NCP files in " + directory.toAbsolutePath());
    }

    private static byte[] preset(String name, boolean strongTone) {
        byte[] bytes = NcpCompatibilityFixtures.ncp();
        Arrays.fill(bytes, 16, 36, (byte) 0);
        byte[] asciiName = name.getBytes(StandardCharsets.US_ASCII);
        if (asciiName.length > 19) throw new IllegalArgumentException("Synthetic preset name is too long");
        System.arraycopy(asciiName, 0, bytes, 16, asciiName.length);
        bytes[40] = (byte) 0x80; // Sharpness zero: this fixture does not imply spatial processing.
        bytes[43] = (byte) (0x80 + (strongTone ? 2 : 0));
        bytes[44] = (byte) (0x80 + (strongTone ? 3 : 0));
        for (int i = 0; i <= 256; i++) {
            double input = i / 256.0;
            // Intentional strong lift of midtones and raised black, solely for visible UI checks.
            double output = strongTone ? 0.04 + 0.90 * Math.sqrt(input) : input;
            int sample = (int) Math.round(output * 32767);
            int offset = 120 + i * 2;
            bytes[offset] = (byte) (sample >>> 8);
            bytes[offset + 1] = (byte) sample;
        }
        return bytes;
    }

    private static NcpPreviewProfile validate(byte[] bytes) throws IOException {
        if (!"0100".equals(NcpFile.validate(bytes))) throw new IOException("Unexpected synthetic NCP version");
        NcpPictureControlConverter.convert(bytes, 0, 0);
        return NcpPreviewProfile.fromNcp(bytes);
    }
}

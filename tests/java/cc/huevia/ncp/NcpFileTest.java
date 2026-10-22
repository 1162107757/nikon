package cc.huevia.ncp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Structural-envelope fixtures only; none are claimed to load on a Nikon camera. */
public final class NcpFileTest {
    private static int checks;

    public static void main(String[] args) {
        byte[] valid = syntheticEnvelope("0100");
        equal("0100", NcpFile.validate(valid));
        for (int cut = 0; cut < valid.length; cut++) {
            byte[] truncated = Arrays.copyOf(valid, cut);
            rejects(() -> NcpFile.validate(truncated));
        }
        rejects(() -> NcpFile.validate(null));
        rejects(() -> NcpFile.validate(new byte[NcpFile.MAX_BYTES + 1]));
        byte[] counterfeitSignature = valid.clone();
        counterfeitSignature[0] = (byte) 0xff;
        counterfeitSignature[1] = (byte) 0xd8;
        rejects(() -> NcpFile.validate(counterfeitSignature));
        // Giving JPEG bytes an .NCP extension cannot alter their on-disk signature.
        rejects(() -> NcpFile.validate(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff,
                (byte) 0xe0, 0, 16, 74, 70, 73, 70, 0, 1}));
        byte[] unsignedMaxLength = valid.clone();
        Arrays.fill(unsignedMaxLength, 8, 12, (byte) 0xff);
        rejects(() -> NcpFile.validate(unsignedMaxLength));
        byte[] unsignedHighBitLength = valid.clone();
        unsignedHighBitLength[8] = (byte) 0x80;
        unsignedHighBitLength[9] = 0;
        unsignedHighBitLength[10] = 0;
        unsignedHighBitLength[11] = 0;
        rejects(() -> NcpFile.validate(unsignedHighBitLength));
        rejects(() -> NcpFile.validate(concat(valid, new byte[] {0})));
        rejects(() -> NcpFile.validate(syntheticEnvelope("0101")));
        rejects(() -> NcpFile.validate(syntheticEnvelope("9999")));
        rejects(() -> NcpFile.validate(concat(signature(), bigEndian(0))));
        rejects(() -> NcpFile.validate(concat(signature(), bigEndian(2), bigEndian(0), bigEndian(0))));
        rejects(() -> NcpFile.validate(concat(signature(), bigEndian(1), bigEndian(35), new byte[35], bigEndian(0))));
        rejects(() -> NcpFile.validate(concat(signature(), record(1, presetPayload("0100")),
                record(1, presetPayload("0100")), bigEndian(0))));
        // Unknown records have bounded lengths and can be skipped around the known envelope.
        equal("0100", NcpFile.validate(concat(signature(), record(2, new byte[] {5, 6, 7}),
                record(1, presetPayload("0100")), record(99, new byte[0]), bigEndian(0))));
        byte[] littleEndianLength = valid.clone();
        littleEndianLength[8] = 36;
        littleEndianLength[11] = 0;
        rejects(() -> NcpFile.validate(littleEndianLength));
        System.out.println("PASS: " + checks + " NCP structural checks (synthetic fixtures only)");
    }

    public static byte[] syntheticEnvelope(String version) {
        return concat(signature(), record(1, presetPayload(version)), bigEndian(0));
    }

    private static byte[] signature() { return new byte[] {'N', 'C', 'P', 0}; }

    private static byte[] presetPayload(String version) {
        byte[] payload = new byte[36];
        byte[] text = version.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, payload, 0, Math.min(4, text.length));
        return payload;
    }

    private static byte[] record(long id, byte[] payload) {
        return concat(bigEndian(id), bigEndian(payload.length), payload);
    }

    private static byte[] bigEndian(long value) {
        return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
    }

    private static byte[] concat(byte[]... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] value : values) output.write(value, 0, value.length);
        return output.toByteArray();
    }

    private static void rejects(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Malformed NCP fixture was accepted");
    }

    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
        checks++;
    }
}

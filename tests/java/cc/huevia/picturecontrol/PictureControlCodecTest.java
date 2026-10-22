package cc.huevia.picturecontrol;

import cc.huevia.ptp.PtpProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Independent synthetic fixtures for the documented read datasets; no NCP conversion. */
public final class PictureControlCodecTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        currentDataPreservesRawFieldsAndBytes();
        curveLengthAndNameStructureAreEnforced();
        capabilityPreservesSignedLevelsAndUnknownFlags();
        System.out.println("PASS: " + checks + " Picture Control read-dataset checks");
    }

    private static void currentDataPreservesRawFieldsAndBytes() throws Exception {
        byte[] bytes = profile(36, 0x1234, "READ ONLY", 0);
        bytes[22] = (byte) 0xff; // UINT8 ApplyLevel.
        bytes[23] = (byte) 0xfe; // UINT8 QuickSharpFlag.
        bytes[24] = (byte) 0x80; // INT8 QuickSharp.
        bytes[25] = (byte) 0xff; // INT8 Sharpening.
        bytes[26] = 0x7f;
        bytes[32] = (byte) 0xff; // UINT8 FilterEffects.
        bytes[33] = (byte) 0xfe; // UINT8 Toning.
        bytes[34] = (byte) 0x80; // INT8 ToningDensity.
        PictureControlData data = PictureControlData.parse(bytes);
        equal(0x1234, data.basePictureControl());
        equal("READ ONLY", data.registrationName());
        equal(255, data.applyLevel());
        equal(254, data.quickSharpFlag());
        equal(-128, data.quickSharp());
        equal(-1, data.sharpening());
        equal(127, data.middleRangeSharpening());
        equal(255, data.filterEffects());
        equal(254, data.toning());
        equal(-128, data.toningDensity());
        equal(14, data.rawParameters().size());
        check(!data.customCurveEnabled() && !data.hasNoBasePictureControl(), "Raw sentinel values do not invent semantic validation");
        byte[] expected = bytes.clone();
        bytes[0] = 0;
        equal(expected, data.rawBytes());
        byte[] returned = data.rawBytes();
        returned[1] ^= 1;
        equal(expected, data.rawBytes());
        immutable(() -> data.rawParameters().clear());
        PictureControlData empty = PictureControlData.parse(profile(36, 0, "", 0));
        check(empty.hasNoBasePictureControl() && !empty.customCurveEnabled(), "Base zero is retained as an empty-slot state");
        equal("", empty.registrationName());
    }

    private static void curveLengthAndNameStructureAreEnforced() throws Exception {
        for (int length : new int[] {0, 35, 37, 613, 615, 638}) rejects(() -> PictureControlData.parse(new byte[length]));
        rejects(() -> PictureControlData.parse(null));
        rejects(() -> PictureControlData.parse(profile(36, 1, "PC", 1)));
        rejects(() -> PictureControlData.parse(profile(36, 1, "PC", 2)));
        rejects(() -> PictureControlData.parse(profile(614, 1, "PC", 255)));
        byte[] withCurve = profile(614, 1, "PC", 1);
        for (int i = 36; i < withCurve.length; i++) withCurve[i] = (byte) (i * 17);
        PictureControlData curve = PictureControlData.parse(withCurve);
        check(curve.customCurveEnabled(), "The enabled-curve dataset includes its full 578-byte curve tail");
        equal(withCurve, curve.rawBytes());
        byte[] disabledTail = withCurve.clone();
        disabledTail[35] = 0;
        PictureControlData disabled = PictureControlData.parse(disabledTail);
        check(!disabled.customCurveEnabled(), "A long returned dataset can retain an unused curve tail");
        equal(disabledTail, disabled.rawBytes());
        byte[] noTerminator = profile(36, 1, "PC", 0);
        Arrays.fill(noTerminator, 2, 22, (byte) 'A');
        rejects(() -> PictureControlData.parse(noTerminator));
        byte[] nonAscii = profile(36, 1, "PC", 0);
        nonAscii[2] = (byte) 0x80;
        rejects(() -> PictureControlData.parse(nonAscii));
        byte[] opaquePadding = profile(36, 1, "PC", 0);
        opaquePadding[10] = (byte) 0xff; // Padding after the first NUL is not a second name.
        equal("PC", PictureControlData.parse(opaquePadding).registrationName());
        equal(opaquePadding, PictureControlData.parse(opaquePadding).rawBytes());
    }

    private static void capabilityPreservesSignedLevelsAndUnknownFlags() throws Exception {
        byte[] bytes = new byte[28];
        for (int i = 0; i < 12; i++) bytes[i] = (byte) (240 + i);
        bytes[12] = (byte) 0xff;
        bytes[13] = (byte) 0x80;
        bytes[14] = 0x7f;
        bytes[15] = (byte) 0xff;
        PictureControlCapability capability = PictureControlCapability.parse(bytes);
        equal(12, capability.rawCapabilityFlags().size());
        check(capability.rawCapabilityFlags().containsValue(240) && capability.rawCapabilityFlags().containsValue(251),
                "Unknown capability bits/values are preserved as UINT8 rather than rejected");
        equal(-1, capability.defaultQuickSharpLevel());
        equal(5, capability.defaultLevels().size());
        equal(-128, capability.defaultLevels().get(0).sharpening());
        equal(127, capability.defaultLevels().get(0).middleRangeSharpening());
        equal(-1, capability.defaultLevels().get(0).clarity());
        byte[] expected = bytes.clone();
        bytes[0] = 0;
        equal(expected, capability.rawBytes());
        byte[] returned = capability.rawBytes();
        returned[1] = 0;
        equal(expected, capability.rawBytes());
        immutable(() -> capability.rawCapabilityFlags().clear());
        immutable(() -> capability.defaultLevels().clear());
        for (int length : new int[] {0, 27, 29}) rejects(() -> PictureControlCapability.parse(new byte[length]));
        rejects(() -> PictureControlCapability.parse(null));
    }

    private static byte[] profile(int length, int base, String name, int flag) {
        byte[] bytes = new byte[length];
        bytes[0] = (byte) base;
        bytes[1] = (byte) (base >>> 8);
        byte[] encoded = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, bytes, 2, encoded.length);
        bytes[35] = (byte) flag;
        return bytes;
    }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        try { action.run(); }
        catch (PtpProtocolException expected) { checks++; return; }
        throw new AssertionError("Expected invalid Picture Control read dataset");
    }
    private static void immutable(Action action) throws Exception {
        try { action.run(); }
        catch (UnsupportedOperationException expected) { checks++; return; }
        throw new AssertionError("Expected an immutable raw-field view");
    }
    private static void equal(Object expected, Object actual) {
        boolean same = expected instanceof byte[] && actual instanceof byte[]
                ? Arrays.equals((byte[]) expected, (byte[]) actual) : java.util.Objects.equals(expected, actual);
        check(same, "Expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

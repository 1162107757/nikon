package com.nikonpreset.usbprobe.ncp;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Checks compatibility adaptation bytes, not native equivalence or photographic appearance. */
public final class NcpPictureControlConverterTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        exactIndependentOutputAndProtectedCopies();
        documentedSubsetMapsWithoutGuessingMissingChoices();
        canonicalContainerAndUnknownFieldsAreRejected();
        curveIsPreservedAndStructuralBoundsAreEnforced();
        System.out.println("PASS: " + checks + " NCP compatibility adaptation checks");
    }

    private static void exactIndependentOutputAndProtectedCopies() throws Exception {
        byte[] input = NcpCompatibilityFixtures.ncp();
        byte[] expected = NcpCompatibilityFixtures.expectedData(4, -4);
        var converted = NcpPictureControlConverter.convert(input, 4, -4);
        equal(expected, converted.dataBytes());
        equal("HOST NCP", converted.registrationName());
        equal(2, converted.basePictureControl());
        equal(4, converted.middleRangeSharpeningQuarter());
        equal(-4, converted.clarityQuarter());
        equal(hash(input), converted.sourceSha256());
        equal(hash(NcpCompatibilityFixtures.curve()), converted.curveSha256());
        equal(hash(expected), converted.dataSha256());
        input[16] = 'X';
        byte[] exported = converted.dataBytes();
        exported[0] = 6;
        equal(expected, converted.dataBytes());
        check(!converted.toDisplayText().isEmpty(), "The compatibility conversion provides a reviewable adaptation preview");
    }

    private static void documentedSubsetMapsWithoutGuessingMissingChoices() throws Exception {
        int[] legacy = {0x0001, 0x03c2, 0x00c3, 0x0486, 0x04c7};
        int[] wire = {1, 2, 3, 5, 6};
        for (int i = 0; i < legacy.length; i++) {
            byte[] input = NcpCompatibilityFixtures.ncp();
            input[36] = (byte) (legacy[i] >>> 8); input[37] = (byte) legacy[i];
            byte[] expected = NcpCompatibilityFixtures.expectedData(0, 0);
            expected[0] = (byte) wire[i];
            equal(expected, NcpPictureControlConverter.convert(input, 0, 0).dataBytes());
        }
        byte[] values = NcpCompatibilityFixtures.ncp();
        values[40] = (byte) 0x89; values[43] = 0x7d; values[44] = (byte) 0x80;
        byte[] expected = NcpCompatibilityFixtures.expectedData(-20, 20);
        expected[25] = 36; expected[30] = -12; expected[31] = 0;
        equal(expected, NcpPictureControlConverter.convert(values, -20, 20).dataBytes());
        values[40] = (byte) 0x80;
        expected[25] = 0;
        equal(expected, NcpPictureControlConverter.convert(values, -20, 20).dataBytes());
        for (int invalid : new int[] {-21, 21, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            rejects(() -> NcpPictureControlConverter.convert(NcpCompatibilityFixtures.ncp(), invalid, 0));
            rejects(() -> NcpPictureControlConverter.convert(NcpCompatibilityFixtures.ncp(), 0, invalid));
        }
    }

    private static void canonicalContainerAndUnknownFieldsAreRejected() throws Exception {
        List<byte[]> invalid = new ArrayList<>();
        invalid.add(null);
        for (int length : new int[] {0, 3, 11, 47, 55, 633, 637, 639}) invalid.add(Arrays.copyOf(NcpCompatibilityFixtures.ncp(), length));
        for (int[] mutation : new int[][] {{0, 'X'}, {3, 1}, {7, 2}, {11, 35}, {11, 37}, {12, '1'},
                {36, 0xff}, {37, 0xff}, {38, 1}, {39, 0}, {40, 0x7f}, {40, 0x8a}, {40, 0xff},
                {41, 0}, {42, 0}, {43, 0x7c}, {43, 0x84}, {44, 0x7c}, {44, 0x84},
                {45, 0}, {46, 0}, {47, 0}, {51, 3}, {55, 0x41}, {55, 0x43}, {637, 1}}) {
            byte[] bytes = NcpCompatibilityFixtures.ncp(); bytes[mutation[0]] = (byte) mutation[1]; invalid.add(bytes);
        }
        byte[] noTerminator = NcpCompatibilityFixtures.ncp(); Arrays.fill(noTerminator, 16, 36, (byte) 'A'); invalid.add(noTerminator);
        byte[] nonAscii = NcpCompatibilityFixtures.ncp(); nonAscii[16] = (byte) 0x80; invalid.add(nonAscii);
        byte[] control = NcpCompatibilityFixtures.ncp(); control[16] = 10; invalid.add(control);
        byte[] padding = NcpCompatibilityFixtures.ncp(); padding[35] = 'X'; invalid.add(padding);
        byte[] emptyName = NcpCompatibilityFixtures.ncp(); Arrays.fill(emptyName, 16, 36, (byte) 0); invalid.add(emptyName);
        for (byte[] bytes : invalid) rejects(() -> NcpPictureControlConverter.convert(bytes, 4, 0));
    }

    private static void curveIsPreservedAndStructuralBoundsAreEnforced() throws Exception {
        byte[] input = NcpCompatibilityFixtures.ncp();
        byte[] converted = NcpPictureControlConverter.convert(input, 4, 0).dataBytes();
        equal(Arrays.copyOfRange(input, 56, 634), Arrays.copyOfRange(converted, 36, 614));
        equal((byte) 0, converted[100]); equal((byte) 0x80, converted[103]);
        // A legal decreasing segment remains raw data; the converter must not sort or resample it.
        input[120] = 0x7f; input[121] = (byte) 0xff;
        equal(Arrays.copyOfRange(input, 56, 634), Arrays.copyOfRange(
                NcpPictureControlConverter.convert(input, 4, 0).dataBytes(), 36, 614));
        for (int[] mutation : new int[][] {{56, 0}, {57, 0}, {62, 21}, {63, 101}, {64, 1}, {64, 21}, {105, 1}, {120, 0x80}}) {
            byte[] bytes = NcpCompatibilityFixtures.ncp(); bytes[mutation[0]] = (byte) mutation[1];
            rejects(() -> NcpPictureControlConverter.convert(bytes, 4, 0));
        }
    }

    private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected an unsupported or malformed NCP compatibility input");
    }
    private static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text = new StringBuilder();
        for (byte value : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
    private static void equal(Object expected, Object actual) {
        boolean same = expected instanceof byte[] && actual instanceof byte[]
                ? Arrays.equals((byte[]) expected, (byte[]) actual) : java.util.Objects.equals(expected, actual);
        check(same, "Expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message); checks++;
    }
}

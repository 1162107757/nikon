package com.nikonpreset.usbprobe.ptp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Dependency-free executable regression tests for on-wire invariants and malformed input. */
public final class PtpCoreTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        commandsHaveExactWireFormat();
        fragmentsAndJoinedContainersRemainFramed();
        malformedContainersFailBeforeAllocation();
        transactionAndResponseValidation();
        unicodeAndUnsignedDeviceInfo();
        malformedDatasetsAreBounded();
        unsignedStorageMetadata();
        objectMetadataAndUploadPaths();
        redactedDeviceIdentity();
        System.out.println("PASS: " + checks + " PTP core checks");
    }

    private static void commandsHaveExactWireFormat() throws Exception {
        byte[] openSession = PtpContainer.command(PtpConstants.OPEN_SESSION, 0, 1).toBytes();
        // Literal wire fixture: length=16, command=1, OpenSession=0x1002, txn=0, session=1.
        equal(new byte[] {16, 0, 0, 0, 1, 0, 2, 16, 0, 0, 0, 0, 1, 0, 0, 0}, openSession);
        PtpContainer maxFields = read(PtpContainer.command(0xffff, 0xffffffffL,
                0, 0xffffffffL, 0x80000000L, 1, 2).toBytes());
        equal(0xffffffffL, maxFields.transactionId());
        equal(new long[] {0, 0xffffffffL, 0x80000000L, 1, 2}, maxFields.parameters());
        expect(IllegalArgumentException.class, () -> PtpContainer.command(0x1001, 1, 1, 2, 3, 4, 5, 6));
        expect(IllegalArgumentException.class, () -> PtpContainer.command(0x1001, -1));
        expect(IllegalArgumentException.class, () -> PtpContainer.command(0x1001, 0x100000000L));
        expect(IllegalArgumentException.class, () -> PtpContainer.command(0x10000, 0));
        expect(IllegalArgumentException.class, () -> PtpContainer.response(0x2001, 0, -1));
        expect(IllegalArgumentException.class, () -> PtpContainer.event(0x4002, 1, 1, 2, 3, 4));
        byte[] mutable = {1, 2, 3};
        PtpContainer immutable = PtpContainer.data(0x1001, 0, mutable);
        mutable[0] = 8;
        byte[] returned = immutable.payload();
        returned[1] = 9;
        equal(new byte[] {1, 2, 3}, immutable.payload());
    }

    private static void fragmentsAndJoinedContainersRemainFramed() throws Exception {
        byte[] payload = new byte[1027];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        byte[] data = PtpContainer.data(0x1001, 7, payload).toBytes();
        byte[] response = PtpContainer.response(0x2001, 7).toBytes();
        byte[] event = PtpContainer.event(0x4002, 7, 0xffffffffL).toBytes();
        byte[] joined = concat(data, response, event);
        for (int fragment : new int[] {1, 2, 7, 12, 64, 512, 4096}) {
            ByteArrayInputStream input = new ByteArrayInputStream(joined);
            PtpContainerReader reader = new PtpContainerReader(
                    (target, offset, length) -> input.read(target, offset, Math.min(length, fragment)),
                    2048);
            PtpContainer first = reader.read();
            equal(payload, PtpValidation.requireData(first, 7, 0x1001));
            PtpValidation.requireOkResponse(reader.read(), 7);
            equal(new long[] {0xffffffffL}, reader.read().parameters());
            equal(null, reader.read());
        }
        PtpContainer emptyData = read(PtpContainer.data(0x1001, 0, new byte[0]).toBytes());
        equal(0, emptyData.payload().length);
        expect(IllegalStateException.class, emptyData::parameters);
    }

    private static void malformedContainersFailBeforeAllocation() throws Exception {
        expect(PtpProtocolException.class, () -> read(header(11, 2, 0x1001, 0)));
        expect(PtpProtocolException.class, () -> read(header(0xffffffffL, 2, 0x1001, 0)));
        expect(PtpProtocolException.class, () -> read(header(13, 1, 0x1001, 0)));
        expect(PtpProtocolException.class, () -> read(header(36, 3, 0x2001, 0)));
        expect(PtpProtocolException.class, () -> read(header(28, 4, 0x4002, 0)));
        expect(PtpProtocolException.class, () -> read(header(12, 5, 0x1001, 0)));
        expect(EOFException.class, () -> read(new byte[] {12, 0, 0}));
        expect(EOFException.class, () -> read(concat(header(15, 2, 0x1001, 0), new byte[] {1})));
        PtpContainerReader bounded = new PtpContainerReader(new ByteArrayInputStream(
                header(45, 2, 0x1001, 0)), 32);
        expect(PtpProtocolException.class, bounded::read);
        expect(IOException.class, new PtpContainerReader((b, o, n) -> 0, 32)::read);
        expect(IOException.class, new PtpContainerReader((b, o, n) -> n + 1, 32)::read);
        expect(IllegalArgumentException.class, () -> new PtpContainerReader(new ByteArrayInputStream(
                new byte[0]), -1));
        IOException transportFailure = new IOException("USB timeout");
        try {
            new PtpContainerReader((b, o, n) -> { throw transportFailure; }, 32).read();
            fail("Expected original transport error");
        } catch (IOException actual) {
            check(actual == transportFailure, "Transport errors preserve their original identity");
        }
    }

    private static void transactionAndResponseValidation() throws Exception {
        PtpContainer data = PtpContainer.data(0x1001, 7, new byte[] {1});
        expect(PtpProtocolException.class, () -> PtpValidation.requireData(data, 8, 0x1001));
        expect(PtpProtocolException.class, () -> PtpValidation.requireData(data, 7, 0x1005));
        expect(PtpProtocolException.class, () -> PtpValidation.requireOkResponse(data, 7));
        expect(PtpProtocolException.class, () -> PtpValidation.requireOkResponse(null, 7));
        expect(PtpProtocolException.class, () -> PtpValidation.requireData(
                PtpContainer.response(0x2001, 7), 7, 0x1001));
        for (int responseCode : new int[] {0x2019, 0x201e, 0x2005}) {
            try {
                PtpValidation.requireData(PtpContainer.response(responseCode, 7), 7, 0x1001);
                fail("Expected failed response");
            } catch (PtpResponseException error) {
                equal(responseCode, error.responseCode());
                equal(7L, error.transactionId());
                check(error.getMessage().contains(PtpConstants.responseName(responseCode)),
                        "Response name is diagnostic");
            }
        }
    }

    private static void unicodeAndUnsignedDeviceInfo() throws Exception {
        PtpDeviceInfo device = PtpDeviceInfo.parse(deviceInfo(0x1001, 0x100c, 0x100d, 0x9201, 0xffff));
        equal(100, device.standardVersion());
        equal(0xffffffffL, device.vendorExtensionId());
        equal(200, device.vendorExtensionVersion());
        equal("扩展 🙂", device.vendorExtensionDescription());
        equal("Nikon 尼康", device.manufacturer());
        equal("Z 6II", device.model());
        equal("1.62", device.deviceVersion());
        equal("", device.serialNumber());
        equal(new int[] {0x4002, 0x4008}, device.eventsSupported());
        equal(new int[] {0x5001, 0xd001}, device.devicePropertiesSupported());
        equal(new int[] {0x3801}, device.captureFormats());
        equal(new int[] {0x3801, 0xb101}, device.imageFormats());
        equal(List.of("0x1001", "0x100C", "0x100D", "0x9201", "0xFFFF"), device.operationsAsHex());
        check(device.supportsStandardObjectSend(), "Both standard object-send operations advertised");
        check(!PtpDeviceInfo.parse(deviceInfo(0x100c)).supportsStandardObjectSend(),
                "Only one object-send operation is insufficient");
        int[] mutable = device.operationsSupported();
        mutable[0] = 0;
        check(device.supportsOperation(0x1001), "Device operation arrays are immutable");
        expect(UnsupportedOperationException.class, () -> device.operationsAsHex().add("0x1002"));
    }

    private static void malformedDatasetsAreBounded() throws Exception {
        byte[] valid = deviceInfo(0x1001);
        expect(PtpProtocolException.class, () -> PtpDeviceInfo.parse(Arrays.copyOf(valid, valid.length - 1)));
        expect(PtpProtocolException.class, () -> PtpDeviceInfo.parse(concat(valid, new byte[] {0})));
        // First string starts at byte 8: set its count to 255 without providing enough bytes.
        byte[] overrunString = valid.clone();
        overrunString[8] = (byte) 255;
        expect(PtpProtocolException.class, () -> PtpDeviceInfo.parse(overrunString));
        Writer prefix = new Writer().u16(100).u32(10).u16(100).string("").u16(0);
        expect(PtpProtocolException.class, () -> PtpDeviceInfo.parse(
                prefix.u32(0xffffffffL).bytes()));
        expect(PtpProtocolException.class, () -> PtpStorageIds.parse(new Writer().u32(2).u32(1).bytes()));
        expect(PtpProtocolException.class, () -> PtpStorageIds.parse(new Writer().u32(0x80000000L).bytes()));
        byte[] tooManyElements = new byte[4 + 65537 * 4];
        tooManyElements[0] = 1;
        tooManyElements[2] = 1;
        expect(PtpProtocolException.class, () -> PtpStorageIds.parse(tooManyElements));
        PtpDatasetReader noTerminator = new PtpDatasetReader(new byte[] {2, 65, 0, 66, 0});
        expect(PtpProtocolException.class, noTerminator::string);
        PtpDatasetReader badSurrogate = new PtpDatasetReader(new byte[] {2, 0, (byte) 0xd8, 0, 0});
        expect(PtpProtocolException.class, badSurrogate::string);
        PtpDatasetReader embeddedNull = new PtpDatasetReader(new byte[] {3, 0, 0, 65, 0, 0, 0});
        expect(PtpProtocolException.class, embeddedNull::string);
        equal("", new PtpDatasetReader(new byte[] {0}).string());
        equal("", new PtpDatasetReader(new byte[] {1, 0, 0}).string());
    }

    private static void unsignedStorageMetadata() throws Exception {
        equal(new long[] {1, 0x80000001L, 0xffffffffL}, PtpStorageIds.parse(
                new Writer().u32(3).u32(1).u32(0x80000001L).u32(0xffffffffL).bytes()));
        equal(new long[0], PtpStorageIds.parse(new Writer().u32(0).bytes()));
        expect(PtpProtocolException.class, () -> PtpStorageIds.parse(new Writer().u32(0).u32(1).bytes()));
        for (int access : new int[] {0, 1, 2, 99}) {
            byte[] bytes = storageInfo(access);
            PtpStorageInfo storage = PtpStorageInfo.parse(bytes);
            equal(4, storage.storageType());
            equal(2, storage.fileSystemType());
            equal(access, storage.accessCapability());
            equal(new BigInteger("18446744073709551615"), storage.maxCapacity());
            equal(new BigInteger("9223372036854775808"), storage.freeSpaceBytes());
            equal(0xffffffffL, storage.freeSpaceImages());
            equal("存储卡", storage.description());
            equal("SD", storage.volumeLabel());
            if (access == 99) check(storage.readOnly().isEmpty(), "Unknown access is not writable evidence");
            else equal(access != 0, storage.readOnly().orElseThrow());
            expect(PtpProtocolException.class, () -> PtpStorageInfo.parse(Arrays.copyOf(bytes, 20)));
        }
    }

    private static void objectMetadataAndUploadPaths() throws Exception {
        PtpObjectInfo file = PtpObjectInfo.file(0x80000001L, 0xffffffffL, "PICCON01.NCP", 8192);
        byte[] bytes = file.toBytes();
        // Independent layout checks: 52 fixed bytes before the PTP filename string.
        equal(0x80000001L, PtpContainer.uint32(bytes, 0));
        equal(0x3000, PtpContainer.uint16(bytes, 4));
        equal(8192L, PtpContainer.uint32(bytes, 8));
        equal(0xffffffffL, PtpContainer.uint32(bytes, 38));
        equal(0, PtpContainer.uint16(bytes, 42));
        equal(13, bytes[52] & 0xff);
        PtpObjectInfo decodedFile = PtpObjectInfo.parse(bytes);
        equal("PICCON01.NCP", decodedFile.filename());
        equal(8192L, decodedFile.compressedSize());
        equal(bytes, decodedFile.toBytes());

        PtpObjectInfo folder = PtpObjectInfo.parse(PtpObjectInfo.folder(1, 42, "CUSTOMPC").toBytes());
        equal(0x3001, folder.objectFormat());
        equal(1, folder.associationType());
        equal(0L, folder.compressedSize());
        equal(42L, folder.parentObject());

        PtpObjectInfo.Builder builder = new PtpObjectInfo.Builder().storageId(0xffffffffL)
                .objectFormat(0x3801).protectionStatus(1).compressedSize(0xffffffffL)
                .thumbFormat(0x3801).thumbCompressedSize(65536).thumbPixWidth(160).thumbPixHeight(120)
                .imagePixWidth(6048).imagePixHeight(4024).imageBitDepth(24).parentObject(512)
                .associationType(0).associationDescription(0x80000001L).sequenceNumber(999)
                .filename("预设 🙂.NCP").captureDate("20261014T123000").modificationDate("20261014T130000")
                .keywords("自定义,预设");
        PtpObjectInfo immutable = builder.build();
        builder.filename("changed").compressedSize(0);
        PtpObjectInfo decoded = PtpObjectInfo.parse(immutable.toBytes());
        equal("预设 🙂.NCP", decoded.filename());
        equal(0xffffffffL, decoded.compressedSize());
        equal(1, decoded.protectionStatus());
        equal(0x3801, decoded.thumbFormat());
        equal(65536L, decoded.thumbCompressedSize());
        equal(160L, decoded.thumbPixWidth());
        equal(120L, decoded.thumbPixHeight());
        equal(6048L, decoded.imagePixWidth());
        equal(4024L, decoded.imagePixHeight());
        equal(24L, decoded.imageBitDepth());
        equal(0x80000001L, decoded.associationDescription());
        equal(999L, decoded.sequenceNumber());
        equal("20261014T123000", decoded.captureDate());
        equal("20261014T130000", decoded.modificationDate());
        equal("自定义,预设", decoded.keywords());
        equal(immutable.toBytes(), decoded.toBytes());
        equal(new long[] {1, 0xffffffffL}, PtpObjectHandles.parse(new Writer().u32(2).u32(1)
                .u32(0xffffffffL).bytes()));

        for (String invalid : new String[] {"", ".", "..", "../PICCON01.NCP", "NIKON/CUSTOMPC",
                "NIKON\\CUSTOMPC", "a:b.NCP", "x\n.NCP", "bad?.NCP", "trailing.", "trailing "}) {
            expect(IllegalArgumentException.class, () -> PtpObjectInfo.file(1, 0, invalid, 1024));
        }
        expect(IllegalArgumentException.class, () -> PtpObjectInfo.file(1, 0, "PICCON01.NCP", -1));
        expect(IllegalArgumentException.class, () -> PtpObjectInfo.file(1, 0, "PICCON01.NCP", 0x100000000L));
        expect(IllegalArgumentException.class, () -> PtpObjectInfo.folder(1, 0, "x".repeat(255)));
        expect(IllegalArgumentException.class, () -> new PtpObjectInfo.Builder().filename("\ud800").build());
        expect(IllegalArgumentException.class, () -> new PtpObjectInfo.Builder().filename("a\u0000b").build());
        expect(PtpProtocolException.class, () -> PtpObjectInfo.parse(Arrays.copyOf(bytes, 51)));
        expect(PtpProtocolException.class, () -> PtpObjectInfo.parse(concat(bytes, new byte[] {0})));
    }

    private static void redactedDeviceIdentity() throws Exception {
        byte[] emptySerial = deviceInfo(0x1001);
        // The fixture's final empty string is encoded with count=1 plus a terminator.
        byte[] withSerial = concat(Arrays.copyOf(emptySerial, emptySerial.length - 3),
                new Writer().string("SECRET-序列🙂").bytes());
        equal("SECRET-序列🙂", PtpDeviceInfo.parse(withSerial).serialNumber());
        PtpDeviceInfo redacted = PtpDeviceInfo.parseRedacted(withSerial);
        equal("", redacted.serialNumber());
        equal("Z 6II", redacted.model());
        byte[] malformed = withSerial.clone();
        malformed[malformed.length - 2] = 65;
        expect(PtpProtocolException.class, () -> PtpDeviceInfo.parseRedacted(malformed));
        PtpDatasetReader badSurrogate = new PtpDatasetReader(new byte[] {2, 0, (byte) 0xd8, 0, 0});
        expect(PtpProtocolException.class, badSurrogate::skipString);
        PtpDatasetReader lowSurrogate = new PtpDatasetReader(new byte[] {2, 0, (byte) 0xdc, 0, 0});
        expect(PtpProtocolException.class, lowSurrogate::skipString);
    }

    private static byte[] deviceInfo(int... operations) {
        return new Writer().u16(100).u32(0xffffffffL).u16(200).string("扩展 🙂").u16(0)
                .u16Array(operations).u16Array(0x4002, 0x4008).u16Array(0x5001, 0xd001)
                .u16Array(0x3801).u16Array(0x3801, 0xb101).string("Nikon 尼康")
                .string("Z 6II").string("1.62").string("").bytes();
    }

    private static byte[] storageInfo(int access) {
        return new Writer().u16(4).u16(2).u16(access)
                .u64(new BigInteger("18446744073709551615"))
                .u64(new BigInteger("9223372036854775808"))
                .u32(0xffffffffL).string("存储卡").string("SD").bytes();
    }

    private static PtpContainer read(byte[] bytes) throws IOException {
        return new PtpContainerReader(new ByteArrayInputStream(bytes), 2048).read();
    }

    private static byte[] header(long length, int type, int code, long transactionId) {
        return new Writer().u32(length).u16(type).u16(code).u32(transactionId).bytes();
    }

    private static byte[] concat(byte[]... segments) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] segment : segments) output.write(segment, 0, segment.length);
        return output.toByteArray();
    }

    private static final class Writer {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Writer u16(int value) {
            bytes.write(value);
            bytes.write(value >>> 8);
            return this;
        }

        Writer u32(long value) {
            for (int i = 0; i < 4; i++) bytes.write((int) (value >>> (i * 8)));
            return this;
        }

        Writer u64(BigInteger value) {
            for (int i = 0; i < 8; i++) bytes.write(value.shiftRight(i * 8).intValue());
            return this;
        }

        Writer u16Array(int... values) {
            u32(values.length);
            for (int value : values) u16(value);
            return this;
        }

        Writer string(String value) {
            byte[] utf16 = value.getBytes(StandardCharsets.UTF_16LE);
            bytes.write(value.length() + 1);
            bytes.write(utf16, 0, utf16.length);
            u16(0);
            return this;
        }

        byte[] bytes() { return bytes.toByteArray(); }
    }

    private interface ThrowingAction { void run() throws Exception; }

    private static void expect(Class<? extends Throwable> type, ThrowingAction action) throws Exception {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) { checks++; return; }
            throw new AssertionError("Expected " + type.getSimpleName() + " but got " + error, error);
        }
        fail("Expected " + type.getSimpleName());
    }

    private static void equal(Object expected, Object actual) {
        boolean same;
        if (expected instanceof byte[] && actual instanceof byte[]) {
            same = Arrays.equals((byte[]) expected, (byte[]) actual);
        } else if (expected instanceof long[] && actual instanceof long[]) {
            same = Arrays.equals((long[]) expected, (long[]) actual);
        } else if (expected instanceof int[] && actual instanceof int[]) {
            same = Arrays.equals((int[]) expected, (int[]) actual);
        } else {
            same = java.util.Objects.equals(expected, actual);
        }
        check(same, "Expected " + display(expected) + ", got " + display(actual));
    }

    private static String display(Object value) {
        if (value instanceof byte[]) return Arrays.toString((byte[]) value);
        if (value instanceof long[]) return Arrays.toString((long[]) value);
        if (value instanceof int[]) return Arrays.toString((int[]) value);
        return String.valueOf(value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) fail(message);
        checks++;
    }

    private static void fail(String message) { throw new AssertionError(message); }
}

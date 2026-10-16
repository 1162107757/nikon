package com.nikonpreset.usbprobe.ptp;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Standard PTP ObjectInfo dataset. Encoding metadata does not establish whether a
 * camera accepts this object type or can load the file as a Picture Control.
 */
public final class PtpObjectInfo {
    private final Builder fields;

    private PtpObjectInfo(Builder source) {
        fields = new Builder(source);
        requireU32(fields.storageId, "storageId");
        requireU16(fields.objectFormat, "objectFormat");
        requireU16(fields.protectionStatus, "protectionStatus");
        requireU32(fields.compressedSize, "compressedSize");
        requireU16(fields.thumbFormat, "thumbFormat");
        requireU32(fields.thumbCompressedSize, "thumbCompressedSize");
        requireU32(fields.thumbPixWidth, "thumbPixWidth");
        requireU32(fields.thumbPixHeight, "thumbPixHeight");
        requireU32(fields.imagePixWidth, "imagePixWidth");
        requireU32(fields.imagePixHeight, "imagePixHeight");
        requireU32(fields.imageBitDepth, "imageBitDepth");
        requireU32(fields.parentObject, "parentObject");
        requireU16(fields.associationType, "associationType");
        requireU32(fields.associationDescription, "associationDescription");
        requireU32(fields.sequenceNumber, "sequenceNumber");
        validateString(fields.filename);
        validateString(fields.captureDate);
        validateString(fields.modificationDate);
        validateString(fields.keywords);
    }

    public static PtpObjectInfo parse(byte[] dataset) throws PtpProtocolException {
        PtpDatasetReader reader = new PtpDatasetReader(dataset);
        Builder values = new Builder().storageId(reader.uint32()).objectFormat(reader.uint16())
                .protectionStatus(reader.uint16()).compressedSize(reader.uint32())
                .thumbFormat(reader.uint16()).thumbCompressedSize(reader.uint32())
                .thumbPixWidth(reader.uint32()).thumbPixHeight(reader.uint32())
                .imagePixWidth(reader.uint32()).imagePixHeight(reader.uint32())
                .imageBitDepth(reader.uint32()).parentObject(reader.uint32())
                .associationType(reader.uint16()).associationDescription(reader.uint32())
                .sequenceNumber(reader.uint32()).filename(reader.string()).captureDate(reader.string())
                .modificationDate(reader.string()).keywords(reader.string());
        reader.requireEnd();
        return values.build();
    }

    /** Create metadata for one file, never a path; parentObject identifies its directory. */
    public static PtpObjectInfo file(long storageId, long parentObject, String filename,
                                     long compressedSize) {
        validateUploadFilename(filename);
        return new Builder().storageId(storageId).parentObject(parentObject).filename(filename)
                .compressedSize(compressedSize).objectFormat(PtpConstants.FORMAT_UNDEFINED_FILE).build();
    }

    /** Create metadata for a standard generic folder association. */
    public static PtpObjectInfo folder(long storageId, long parentObject, String filename) {
        validateUploadFilename(filename);
        return new Builder().storageId(storageId).parentObject(parentObject).filename(filename)
                .objectFormat(PtpConstants.FORMAT_ASSOCIATION)
                .associationType(PtpConstants.ASSOCIATION_GENERIC_FOLDER).build();
    }

    public byte[] toBytes() {
        Writer writer = new Writer();
        return writer.u32(storageId()).u16(objectFormat()).u16(protectionStatus())
                .u32(compressedSize()).u16(thumbFormat()).u32(thumbCompressedSize())
                .u32(thumbPixWidth()).u32(thumbPixHeight()).u32(imagePixWidth()).u32(imagePixHeight())
                .u32(imageBitDepth()).u32(parentObject()).u16(associationType())
                .u32(associationDescription()).u32(sequenceNumber()).string(filename())
                .string(captureDate()).string(modificationDate()).string(keywords()).bytes();
    }

    public long storageId() { return fields.storageId; }
    public int objectFormat() { return fields.objectFormat; }
    public int protectionStatus() { return fields.protectionStatus; }
    public long compressedSize() { return fields.compressedSize; }
    public int thumbFormat() { return fields.thumbFormat; }
    public long thumbCompressedSize() { return fields.thumbCompressedSize; }
    public long thumbPixWidth() { return fields.thumbPixWidth; }
    public long thumbPixHeight() { return fields.thumbPixHeight; }
    public long imagePixWidth() { return fields.imagePixWidth; }
    public long imagePixHeight() { return fields.imagePixHeight; }
    public long imageBitDepth() { return fields.imageBitDepth; }
    public long parentObject() { return fields.parentObject; }
    public int associationType() { return fields.associationType; }
    public long associationDescription() { return fields.associationDescription; }
    public long sequenceNumber() { return fields.sequenceNumber; }
    public String filename() { return fields.filename; }
    public String captureDate() { return fields.captureDate; }
    public String modificationDate() { return fields.modificationDate; }
    public String keywords() { return fields.keywords; }

    private static void validateUploadFilename(String filename) {
        validateString(filename);
        if (filename.isEmpty() || filename.equals(".") || filename.equals("..")
                || filename.endsWith(" ") || filename.endsWith(".")) {
            throw new IllegalArgumentException("Invalid upload filename");
        }
        for (int i = 0; i < filename.length(); i++) {
            char value = filename.charAt(i);
            if (value < 32 || "\\/:*?\"<>|".indexOf(value) >= 0) {
                throw new IllegalArgumentException("Upload filename must be a single safe path component");
            }
        }
    }

    private static void validateString(String value) {
        Objects.requireNonNull(value, "PTP string");
        if (value.length() > 254) throw new IllegalArgumentException("PTP string exceeds 254 UTF-16 units");
        for (int i = 0; i < value.length(); i++) {
            char unit = value.charAt(i);
            if (unit == 0) throw new IllegalArgumentException("PTP string contains a null character");
            if (Character.isHighSurrogate(unit)) {
                if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("Malformed UTF-16 string");
                }
                i++;
            } else if (Character.isLowSurrogate(unit)) {
                throw new IllegalArgumentException("Malformed UTF-16 string");
            }
        }
    }

    private static void requireU32(long value, String name) { PtpContainer.requireUInt32(value, name); }
    private static void requireU16(int value, String name) {
        if (value < 0 || value > 0xffff) throw new IllegalArgumentException(name + " is not UINT16");
    }

    /** Full standard field builder; empty strings and zero numeric values are defaults. */
    public static final class Builder {
        private long storageId;
        private int objectFormat;
        private int protectionStatus;
        private long compressedSize;
        private int thumbFormat;
        private long thumbCompressedSize;
        private long thumbPixWidth;
        private long thumbPixHeight;
        private long imagePixWidth;
        private long imagePixHeight;
        private long imageBitDepth;
        private long parentObject;
        private int associationType;
        private long associationDescription;
        private long sequenceNumber;
        private String filename = "";
        private String captureDate = "";
        private String modificationDate = "";
        private String keywords = "";

        public Builder() {}

        private Builder(Builder source) {
            storageId = source.storageId;
            objectFormat = source.objectFormat;
            protectionStatus = source.protectionStatus;
            compressedSize = source.compressedSize;
            thumbFormat = source.thumbFormat;
            thumbCompressedSize = source.thumbCompressedSize;
            thumbPixWidth = source.thumbPixWidth;
            thumbPixHeight = source.thumbPixHeight;
            imagePixWidth = source.imagePixWidth;
            imagePixHeight = source.imagePixHeight;
            imageBitDepth = source.imageBitDepth;
            parentObject = source.parentObject;
            associationType = source.associationType;
            associationDescription = source.associationDescription;
            sequenceNumber = source.sequenceNumber;
            filename = source.filename;
            captureDate = source.captureDate;
            modificationDate = source.modificationDate;
            keywords = source.keywords;
        }

        public Builder storageId(long value) { storageId = value; return this; }
        public Builder objectFormat(int value) { objectFormat = value; return this; }
        public Builder protectionStatus(int value) { protectionStatus = value; return this; }
        public Builder compressedSize(long value) { compressedSize = value; return this; }
        public Builder thumbFormat(int value) { thumbFormat = value; return this; }
        public Builder thumbCompressedSize(long value) { thumbCompressedSize = value; return this; }
        public Builder thumbPixWidth(long value) { thumbPixWidth = value; return this; }
        public Builder thumbPixHeight(long value) { thumbPixHeight = value; return this; }
        public Builder imagePixWidth(long value) { imagePixWidth = value; return this; }
        public Builder imagePixHeight(long value) { imagePixHeight = value; return this; }
        public Builder imageBitDepth(long value) { imageBitDepth = value; return this; }
        public Builder parentObject(long value) { parentObject = value; return this; }
        public Builder associationType(int value) { associationType = value; return this; }
        public Builder associationDescription(long value) { associationDescription = value; return this; }
        public Builder sequenceNumber(long value) { sequenceNumber = value; return this; }
        public Builder filename(String value) { filename = value; return this; }
        public Builder captureDate(String value) { captureDate = value; return this; }
        public Builder modificationDate(String value) { modificationDate = value; return this; }
        public Builder keywords(String value) { keywords = value; return this; }
        public PtpObjectInfo build() { return new PtpObjectInfo(this); }
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        Writer u16(int value) {
            output.write(value);
            output.write(value >>> 8);
            return this;
        }

        Writer u32(long value) {
            for (int i = 0; i < 4; i++) output.write((int) (value >>> (i * 8)));
            return this;
        }

        Writer string(String value) {
            if (value.isEmpty()) {
                output.write(0);
                return this;
            }
            output.write(value.length() + 1);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
            output.write(bytes, 0, bytes.length);
            output.write(0);
            output.write(0);
            return this;
        }

        byte[] bytes() { return output.toByteArray(); }
    }
}

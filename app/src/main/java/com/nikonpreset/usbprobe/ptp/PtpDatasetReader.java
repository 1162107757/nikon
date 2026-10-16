package com.nikonpreset.usbprobe.ptp;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Bounds-checked internal reader for standard PTP datasets. */
final class PtpDatasetReader {
    private static final int MAX_ARRAY_ELEMENTS = 65536;
    private final byte[] data;
    private int position;

    PtpDatasetReader(byte[] data) { this.data = Objects.requireNonNull(data, "data"); }

    int uint16() throws PtpProtocolException {
        require(2);
        int value = PtpContainer.uint16(data, position);
        position += 2;
        return value;
    }

    long uint32() throws PtpProtocolException {
        require(4);
        long value = PtpContainer.uint32(data, position);
        position += 4;
        return value;
    }

    BigInteger uint64() throws PtpProtocolException {
        require(8);
        byte[] bigEndian = new byte[8];
        for (int i = 0; i < 8; i++) bigEndian[7 - i] = data[position + i];
        position += 8;
        return new BigInteger(1, bigEndian);
    }

    int[] uint16Array() throws PtpProtocolException {
        int count = arrayCount(2);
        int[] values = new int[count];
        for (int i = 0; i < count; i++) values[i] = uint16();
        return values;
    }

    long[] uint32Array() throws PtpProtocolException {
        int count = arrayCount(4);
        long[] values = new long[count];
        for (int i = 0; i < count; i++) values[i] = uint32();
        return values;
    }

    private int arrayCount(int elementSize) throws PtpProtocolException {
        long count = uint32();
        if (count > MAX_ARRAY_ELEMENTS || count > (data.length - position) / elementSize) {
            throw new PtpProtocolException("PTP array count exceeds available data or limit: " + count);
        }
        return (int) count;
    }

    String string() throws PtpProtocolException {
        require(1);
        int units = data[position++] & 0xff;
        if (units == 0) return "";
        int byteCount = units * 2;
        require(byteCount);
        int terminator = position + byteCount - 2;
        if (data[terminator] != 0 || data[terminator + 1] != 0) {
            throw new PtpProtocolException("PTP string lacks its UTF-16 terminator");
        }
        for (int i = position; i < terminator; i += 2) {
            if (data[i] == 0 && data[i + 1] == 0) {
                throw new PtpProtocolException("PTP string contains an embedded terminator");
            }
        }
        try {
            String value = StandardCharsets.UTF_16LE.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data, position, byteCount - 2)).toString();
            position += byteCount;
            return value;
        } catch (CharacterCodingException error) {
            throw new PtpProtocolException("Malformed PTP UTF-16 string", error);
        }
    }

    /** Validate and skip a string without creating or retaining its Java String value. */
    void skipString() throws PtpProtocolException {
        require(1);
        int units = data[position++] & 0xff;
        if (units == 0) return;
        int byteCount = units * 2;
        require(byteCount);
        int terminator = position + byteCount - 2;
        if (data[terminator] != 0 || data[terminator + 1] != 0) {
            throw new PtpProtocolException("PTP string lacks its UTF-16 terminator");
        }
        for (int i = position; i < terminator; i += 2) {
            int unit = PtpContainer.uint16(data, i);
            if (unit == 0) throw new PtpProtocolException("PTP string contains an embedded terminator");
            if (unit >= 0xd800 && unit <= 0xdbff) {
                if (i + 2 >= terminator) throw new PtpProtocolException("Malformed PTP UTF-16 string");
                int next = PtpContainer.uint16(data, i + 2);
                if (next < 0xdc00 || next > 0xdfff) {
                    throw new PtpProtocolException("Malformed PTP UTF-16 string");
                }
                i += 2;
            } else if (unit >= 0xdc00 && unit <= 0xdfff) {
                throw new PtpProtocolException("Malformed PTP UTF-16 string");
            }
        }
        position += byteCount;
    }

    void requireEnd() throws PtpProtocolException {
        if (position != data.length) {
            throw new PtpProtocolException("Unexpected trailing PTP dataset bytes: "
                    + (data.length - position));
        }
    }

    private void require(int count) throws PtpProtocolException {
        if (count < 0 || count > data.length - position) {
            throw new PtpProtocolException("Truncated PTP dataset at byte " + position
                    + ": need " + count + ", remaining " + (data.length - position));
        }
    }
}

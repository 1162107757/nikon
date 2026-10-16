package com.nikonpreset.usbprobe.ptp;

import java.util.Objects;

/** Immutable USB PTP container, with unsigned integer fields represented by long. */
public final class PtpContainer {
    public static final int HEADER_LENGTH = 12;
    public static final int COMMAND = 1;
    public static final int DATA = 2;
    public static final int RESPONSE = 3;
    public static final int EVENT = 4;

    private final int type;
    private final int code;
    private final long transactionId;
    private final byte[] payload;

    private PtpContainer(int type, int code, long transactionId, byte[] payload) {
        requireUInt16(code, "code");
        requireUInt32(transactionId, "transactionId");
        Objects.requireNonNull(payload, "payload");
        if (payload.length > Integer.MAX_VALUE - HEADER_LENGTH) {
            throw new IllegalArgumentException("Container payload is too large");
        }
        this.type = type;
        this.code = code;
        this.transactionId = transactionId;
        this.payload = payload.clone();
    }

    public static PtpContainer command(int operation, long transactionId, long... parameters) {
        return withParameters(COMMAND, operation, transactionId, parameters, 5);
    }

    public static PtpContainer data(int operation, long transactionId, byte[] payload) {
        return new PtpContainer(DATA, operation, transactionId, payload);
    }

    public static PtpContainer response(int responseCode, long transactionId, long... parameters) {
        return withParameters(RESPONSE, responseCode, transactionId, parameters, 5);
    }

    public static PtpContainer event(int eventCode, long transactionId, long... parameters) {
        return withParameters(EVENT, eventCode, transactionId, parameters, 3);
    }

    private static PtpContainer withParameters(int type, int code, long transactionId,
                                               long[] parameters, int maximum) {
        Objects.requireNonNull(parameters, "parameters");
        if (parameters.length > maximum) {
            throw new IllegalArgumentException("Too many PTP parameters: " + parameters.length);
        }
        byte[] bytes = new byte[parameters.length * 4];
        for (int i = 0; i < parameters.length; i++) {
            requireUInt32(parameters[i], "parameter");
            putUInt32(bytes, i * 4, parameters[i]);
        }
        return new PtpContainer(type, code, transactionId, bytes);
    }

    static PtpContainer decoded(int type, int code, long transactionId, byte[] payload) {
        return new PtpContainer(type, code, transactionId, payload);
    }

    public int type() { return type; }
    public int code() { return code; }
    public long transactionId() { return transactionId; }
    public byte[] payload() { return payload.clone(); }

    public long[] parameters() {
        if (type == DATA) throw new IllegalStateException("Data containers contain bytes, not parameters");
        long[] result = new long[payload.length / 4];
        for (int i = 0; i < result.length; i++) result[i] = uint32(payload, i * 4);
        return result;
    }

    /** Encodes the 12-byte header and payload in USB PTP little-endian order. */
    public byte[] toBytes() {
        byte[] bytes = new byte[HEADER_LENGTH + payload.length];
        putUInt32(bytes, 0, bytes.length);
        putUInt16(bytes, 4, type);
        putUInt16(bytes, 6, code);
        putUInt32(bytes, 8, transactionId);
        System.arraycopy(payload, 0, bytes, HEADER_LENGTH, payload.length);
        return bytes;
    }

    static int uint16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    static long uint32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }

    static void putUInt16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    static void putUInt32(byte[] bytes, int offset, long value) {
        for (int i = 0; i < 4; i++) bytes[offset + i] = (byte) (value >>> (i * 8));
    }

    private static void requireUInt16(int value, String field) {
        if (value < 0 || value > 0xffff) throw new IllegalArgumentException(field + " is not UINT16");
    }

    static void requireUInt32(long value, String field) {
        if (value < 0 || value > 0xffffffffL) throw new IllegalArgumentException(field + " is not UINT32");
    }
}

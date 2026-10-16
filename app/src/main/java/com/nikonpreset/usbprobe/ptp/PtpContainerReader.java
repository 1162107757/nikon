package com.nikonpreset.usbprobe.ptp;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Frames PTP containers independently of USB bulk packet fragmentation. */
public final class PtpContainerReader {
    public static final int DEFAULT_MAX_DATA_BYTES = 16 * 1024 * 1024;

    /**
     * Returns 1..length bytes or -1 at EOF; throws on transport failure/timeout.
     * A USB adapter must retain bulk bytes beyond the requested length for the next read.
     */
    @FunctionalInterface
    public interface ByteSource {
        int read(byte[] buffer, int offset, int length) throws IOException;
    }

    private final ByteSource source;
    private final int maxDataBytes;

    public PtpContainerReader(InputStream stream) {
        this(stream, DEFAULT_MAX_DATA_BYTES);
    }

    public PtpContainerReader(InputStream stream, int maxDataBytes) {
        this(Objects.requireNonNull(stream, "stream")::read, maxDataBytes);
    }

    public PtpContainerReader(ByteSource source, int maxDataBytes) {
        this.source = Objects.requireNonNull(source, "source");
        if (maxDataBytes < 0 || maxDataBytes > Integer.MAX_VALUE - PtpContainer.HEADER_LENGTH) {
            throw new IllegalArgumentException("Invalid maximum PTP data size");
        }
        this.maxDataBytes = maxDataBytes;
    }

    /** Returns null only at EOF between containers, never for truncated containers. */
    public PtpContainer read() throws IOException {
        byte[] header = new byte[PtpContainer.HEADER_LENGTH];
        if (!readFully(header, true)) return null;
        long totalLength = PtpContainer.uint32(header, 0);
        int type = PtpContainer.uint16(header, 4);
        int code = PtpContainer.uint16(header, 6);
        long transactionId = PtpContainer.uint32(header, 8);
        if (totalLength < PtpContainer.HEADER_LENGTH) {
            throw new PtpProtocolException("PTP container length is less than 12: " + totalLength);
        }
        long payloadLength = totalLength - PtpContainer.HEADER_LENGTH;
        if (type == PtpContainer.DATA) {
            if (payloadLength > maxDataBytes) {
                throw new PtpProtocolException("PTP data exceeds limit: " + payloadLength
                        + " > " + maxDataBytes);
            }
        } else if (type == PtpContainer.COMMAND || type == PtpContainer.RESPONSE
                || type == PtpContainer.EVENT) {
            int maximum = type == PtpContainer.EVENT ? 12 : 20;
            if (payloadLength > maximum || payloadLength % 4 != 0) {
                throw new PtpProtocolException("Invalid PTP parameter payload length: " + payloadLength);
            }
        } else {
            throw new PtpProtocolException("Unknown PTP container type: " + type);
        }
        byte[] payload = new byte[(int) payloadLength];
        readFully(payload, false);
        return PtpContainer.decoded(type, code, transactionId, payload);
    }

    private boolean readFully(byte[] target, boolean allowBoundaryEof) throws IOException {
        int position = 0;
        while (position < target.length) {
            int requested = target.length - position;
            int read = source.read(target, position, requested);
            if (read == -1) {
                if (position == 0 && allowBoundaryEof) return false;
                throw new EOFException("Truncated PTP container: received " + position
                        + " of " + target.length + " bytes");
            }
            if (read <= 0 || read > requested) {
                throw new IOException("PTP byte source returned invalid byte count: " + read);
            }
            position += read;
        }
        return true;
    }
}

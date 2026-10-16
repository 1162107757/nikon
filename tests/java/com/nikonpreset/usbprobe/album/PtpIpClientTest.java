package com.nikonpreset.usbprobe.album;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** Synthetic TCP peers; no Android runtime, camera, private SDK, or photographs. */
public final class PtpIpClientTest {
    private static final byte[] GUID = {0, 1, 2, 3, 4, 5, 6, 7,
            8, 9, 10, 11, 12, 13, (byte) 0xfe, (byte) 0xff};
    private static final String FRIENDLY_NAME = "Preset \u6d4b\u8bd5";
    private static final byte[] INFO = {1, 4, 9, 16, 25};
    private static final byte[] SMALL = {9, 8, 7, 6, 5, 4, 3};
    private static final long CONNECTION_NUMBER = 0x89abcdefL;
    private static int checks;

    public static void main(String[] args) throws Exception {
        handshakeAndFragmentedTransfers();
        rejectedWritesNeverReachTheSocket();
        handshakeFailuresReleaseEveryCreatedSocket();
        framingAndTransactionFailuresCloseBothChannels();
        remoteLengthsAreBoundedWithoutTruncation();
        streamingDoesNotBufferTheCompleteObject();
        failedOutputClosesBothChannels();
        cancelledClientCannotOpenSockets();
        completeCameraErrorsKeepTheBoundaryReusable();
        System.out.println("PASS: " + checks + " PTP/IP client checks");
    }

    private static void cancelledClientCannotOpenSockets() throws Exception {
        final int[] opened = {0};
        PtpIpClient client = new PtpIpClient((host, port, timeout) -> {
            opened[0]++;
            throw new IOException("Connector must not be reached after cancellation");
        });
        client.close();
        rejects(() -> client.connect("camera.test", 15740, GUID.clone(), FRIENDLY_NAME));
        equal(0, opened[0]);
        check(!client.isOpen(), "Cancelling before connect cannot create a live connection");
    }

    private static void completeCameraErrorsKeepTheBoundaryReusable() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = peer.connect();
        peer.fault = Fault.ERROR_RESPONSE;
        try {
            client.getData(0x1008, 64, 4);
            throw new AssertionError("Expected a complete camera rejection");
        } catch (com.nikonpreset.usbprobe.ptp.PtpResponseException error) {
            equal(0x2009, error.responseCode());
            equal(peer.lastTransaction, error.transactionId());
        }
        check(client.isOpen(), "A complete matching camera error leaves a reusable transaction boundary");
        peer.fault = Fault.NONE;
        equal(SMALL, client.getData(0x1008, 64, 5));
        client.close();
        peer.assertClosed();
    }

    private static void handshakeAndFragmentedTransfers() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = peer.connect();
        check(client.isOpen(), "A connection opens after both handshakes and OpenSession");
        equal(2, peer.sockets.size());
        equal(1, peer.eventRequests);
        equal(Arrays.asList(0x1001, 0x1002), peer.operations);
        equal(SMALL, client.getData(0x1007, 64, 0xffffffffL, 0, 0xffffffffL));
        equal(new long[] {0xffffffffL, 0, 0xffffffffL}, peer.lastParameters);
        long firstTransaction = peer.lastTransaction;
        equal(SMALL, client.getData(0x100a, 64, 0x12345678L));
        check(peer.lastTransaction != firstTransaction, "Different operations use different transaction IDs");
        equal(new long[] {0x12345678L}, peer.lastParameters);
        client.close();
        check(!client.isOpen(), "close clears the connection state");
        peer.assertClosed();
        int commands = peer.operations.size();
        client.close();
        equal(commands, peer.operations.size());
        rejects(() -> client.getData(0x1004, 64));
    }

    private static void rejectedWritesNeverReachTheSocket() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = peer.connect();
        int commands = peer.operations.size();
        for (int operation : new int[] {0x100b, 0x100c, 0x100d, 0x100e, 0x1016, 0x90cd}) {
            rejects(() -> client.getData(operation, 64, 1));
            equal(commands, peer.operations.size());
        }
        client.close();
        peer.assertClosed();
    }

    private static void handshakeFailuresReleaseEveryCreatedSocket() throws Exception {
        for (Fault fault : new Fault[] {Fault.INIT_REJECTED, Fault.EVENT_REJECTED, Fault.SECOND_CONNECT_FAIL}) {
            Peer peer = new Peer();
            peer.fault = fault;
            PtpIpClient client = new PtpIpClient(peer::openSocket);
            rejects(() -> client.connect("camera.test", 15740, GUID.clone(), FRIENDLY_NAME));
            check(!client.isOpen(), "A failed handshake cannot leave an open client: " + fault);
            peer.assertClosed();
            equal(0, peer.operations.size());
        }
    }

    private static void framingAndTransactionFailuresCloseBothChannels() throws Exception {
        for (Fault fault : new Fault[] {Fault.SHORT_HEADER_LENGTH, Fault.START_WRONG_TRANSACTION,
                Fault.DATA_WRONG_TRANSACTION, Fault.END_WRONG_TRANSACTION, Fault.RESPONSE_WRONG_TRANSACTION,
                Fault.PREMATURE_END, Fault.TOO_MUCH_DATA, Fault.MISSING_END, Fault.TRUNCATED_FRAME}) {
            Peer peer = new Peer();
            PtpIpClient client = peer.connect();
            peer.fault = fault;
            rejects(() -> client.getData(0x1004, 64));
            check(!client.isOpen(), "Malformed transfers invalidate the connection: " + fault);
            peer.assertClosed();
            int commands = peer.operations.size();
            rejects(() -> client.getData(0x1004, 64));
            equal(commands, peer.operations.size());
        }
    }

    private static void remoteLengthsAreBoundedWithoutTruncation() throws Exception {
        for (Fault fault : new Fault[] {Fault.OVER_METADATA_CAP, Fault.UINT64_LENGTH}) {
            Peer peer = new Peer();
            PtpIpClient client = peer.connect();
            peer.fault = fault;
            rejects(() -> client.getData(0x1004, 64));
            check(!client.isOpen(), "Oversized declared data closes the client: " + fault);
            peer.assertClosed();
        }
        Peer peer = new Peer();
        PtpIpClient client = peer.connect();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        rejects(() -> client.readTo(0x1009, output, 64, SMALL.length + 1L, null, 1));
        check(!client.isOpen(), "A download with an unexpected object size cannot be successful");
        peer.assertClosed();
    }

    private static void streamingDoesNotBufferTheCompleteObject() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = peer.connect();
        peer.fault = Fault.LARGE_STREAM;
        final long total = 2L * 1024 * 1024 + 31;
        final long[] written = {0};
        final boolean[] firstWriteWasStreaming = {false};
        OutputStream output = new OutputStream() {
            @Override public void write(int value) throws IOException {
                byte[] one = {(byte) value};
                write(one, 0, 1);
            }
            @Override public void write(byte[] bytes, int offset, int length) {
                if (written[0] == 0) firstWriteWasStreaming[0] = peer.command.input.remaining() > total / 2;
                for (int i = 0; i < length; i++) {
                    if (bytes[offset + i] != streamByte(written[0] + i)) {
                        throw new AssertionError("Streaming altered payload at " + (written[0] + i));
                    }
                }
                written[0] += length;
            }
        };
        final long[] progress = {-1, -1};
        long received = client.readTo(0x1009, output, total, total, (done, expected) -> {
            check(done >= progress[0], "Download progress is monotonic");
            check(done <= total, "Download progress never exceeds the object size");
            if (expected >= 0) equal(total, expected);
            progress[0] = done;
            progress[1] = expected;
        }, 0xfedcba98L);
        equal(total, received);
        equal(total, written[0]);
        check(firstWriteWasStreaming[0], "Object bytes reach the destination while most wire bytes remain unread");
        equal(total, progress[0]);
        check(client.isOpen(), "A verified large download retains the connection");
        client.close();
        peer.assertClosed();
    }

    private static void failedOutputClosesBothChannels() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = peer.connect();
        IOException diskFailure = new IOException("synthetic disk full");
        OutputStream output = new OutputStream() {
            @Override public void write(int value) throws IOException { throw diskFailure; }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { throw diskFailure; }
        };
        try {
            client.readTo(0x1009, output, 64, SMALL.length, null, 1);
            throw new AssertionError("Expected the destination failure");
        } catch (IOException expected) {
            check(expected == diskFailure || hasCause(expected, diskFailure), "Destination failure remains identifiable");
        }
        check(!client.isOpen(), "A partially consumed object cannot leave a reusable session");
        peer.assertClosed();
    }

    private static boolean hasCause(Throwable error, Throwable expected) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) if (cursor == expected) return true;
        return false;
    }

    private enum Fault {
        NONE, INIT_REJECTED, EVENT_REJECTED, SECOND_CONNECT_FAIL,
        SHORT_HEADER_LENGTH, START_WRONG_TRANSACTION, DATA_WRONG_TRANSACTION,
        END_WRONG_TRANSACTION, RESPONSE_WRONG_TRANSACTION, PREMATURE_END,
        TOO_MUCH_DATA, MISSING_END, TRUNCATED_FRAME, OVER_METADATA_CAP, UINT64_LENGTH, LARGE_STREAM,
        ERROR_RESPONSE
    }

    private static final class Peer {
        private final List<FakeSocket> sockets = new ArrayList<>();
        private final List<Integer> operations = new ArrayList<>();
        private FakeSocket command;
        private Fault fault = Fault.NONE;
        private int eventRequests;
        private long lastTransaction;
        private long[] lastParameters;

        PtpIpClient connect() throws Exception {
            PtpIpClient client = new PtpIpClient(this::openSocket);
            equal(INFO, client.connect("camera.test", 15740, GUID.clone(), FRIENDLY_NAME));
            return client;
        }

        Socket openSocket(String host, int port, int timeout) throws IOException {
            equal("camera.test", host);
            equal(15740, port);
            check(timeout > 0, "Socket connects use a finite timeout");
            if (sockets.size() == 1 && fault == Fault.SECOND_CONNECT_FAIL) throw new IOException("event connect failed");
            boolean event = !sockets.isEmpty();
            check(sockets.size() < 2, "A client uses exactly the two requested channels");
            FakeSocket socket = new FakeSocket(this, event);
            sockets.add(socket);
            if (!event) command = socket;
            return socket;
        }

        void receive(FakeSocket socket, byte[] packet) throws IOException {
            long type = u32(packet, 4);
            if (socket.event) {
                equal(3L, type);
                equal(12, packet.length);
                equal(CONNECTION_NUMBER, u32(packet, 8));
                eventRequests++;
                socket.input.add(fault == Fault.EVENT_REJECTED ? frame(5, le32(1)) : frame(4, new byte[0]));
                return;
            }
            if (type == 1) {
                equal(GUID, Arrays.copyOfRange(packet, 8, 24));
                byte[] encodedName = (FRIENDLY_NAME + '\0').getBytes(StandardCharsets.UTF_16LE);
                equal(28 + encodedName.length, packet.length);
                equal(encodedName, Arrays.copyOfRange(packet, 24, packet.length - 4));
                equal(0x00010000L, u32(packet, packet.length - 4));
                byte[] ack = concat(le32(CONNECTION_NUMBER), GUID,
                        "Synthetic Camera\0".getBytes(StandardCharsets.UTF_16LE), le32(0x10000));
                socket.input.add(fault == Fault.INIT_REJECTED ? frame(5, le32(1)) : frame(2, ack));
                return;
            }
            equal(6L, type);
            check(packet.length >= 18 && (packet.length - 18) % 4 == 0, "Operation packet has only complete parameters");
            equal(1L, u32(packet, 8));
            int operation = u16(packet, 12);
            long transaction = u32(packet, 14);
            lastTransaction = transaction;
            lastParameters = new long[(packet.length - 18) / 4];
            for (int i = 0; i < lastParameters.length; i++) lastParameters[i] = u32(packet, 18 + i * 4);
            operations.add(operation);
            if (operation == 0x1002) {
                equal(new long[] {1}, lastParameters);
                socket.input.add(response(transaction));
            } else if (operation == 0x1001) {
                equal(0, lastParameters.length);
                addTransfer(socket.input, transaction, INFO, Fault.NONE);
            } else if (operation == 0x1003) {
                socket.input.add(response(transaction));
            } else {
                addTransfer(socket.input, transaction, SMALL, fault);
            }
        }

        void assertClosed() {
            check(!sockets.isEmpty(), "The failure exercised a created socket");
            for (FakeSocket socket : sockets) check(socket.closed, "Every created channel is closed");
        }
    }

    private static void addTransfer(QueuedInput input, long transaction, byte[] payload, Fault fault) {
        if (fault == Fault.ERROR_RESPONSE) {
            input.add(frame(7, concat(le16(0x2009), le32(transaction))));
            return;
        }
        if (fault == Fault.SHORT_HEADER_LENGTH) {
            input.add(concat(le32(7), le32(9)));
            return;
        }
        long declared = payload.length;
        if (fault == Fault.PREMATURE_END) declared++;
        if (fault == Fault.TOO_MUCH_DATA) declared--;
        if (fault == Fault.OVER_METADATA_CAP) declared = 65;
        if (fault == Fault.UINT64_LENGTH) declared = 0x100000001L;
        if (fault == Fault.LARGE_STREAM) declared = 2L * 1024 * 1024 + 31;
        long startTx = transaction + (fault == Fault.START_WRONG_TRANSACTION ? 1 : 0);
        input.add(frame(9, concat(le32(startTx), le64(declared))));
        if (fault == Fault.LARGE_STREAM) {
            for (long offset = 0; offset < declared;) {
                int count = (int) Math.min(4093, declared - offset);
                byte[] chunk = new byte[count];
                for (int i = 0; i < count; i++) chunk[i] = streamByte(offset + i);
                offset += count;
                input.add(frame(offset == declared ? 12 : 10, concat(le32(transaction), chunk)));
            }
        } else {
            long dataTx = transaction + (fault == Fault.DATA_WRONG_TRANSACTION ? 1 : 0);
            if (fault == Fault.MISSING_END) {
                input.add(frame(10, concat(le32(dataTx), payload)));
            } else {
                input.add(frame(10, concat(le32(dataTx), Arrays.copyOfRange(payload, 0, 3))));
                long endTx = transaction + (fault == Fault.END_WRONG_TRANSACTION ? 1 : 0);
                byte[] end = frame(12, concat(le32(endTx), Arrays.copyOfRange(payload, 3, payload.length)));
                input.add(fault == Fault.TRUNCATED_FRAME ? Arrays.copyOf(end, end.length - 1) : end);
                if (fault == Fault.TRUNCATED_FRAME) return;
            }
        }
        input.add(response(transaction + (fault == Fault.RESPONSE_WRONG_TRANSACTION ? 1 : 0)));
    }

    private static byte streamByte(long offset) { return (byte) (offset * 31 + (offset >>> 7)); }
    private static byte[] response(long transaction) { return frame(7, concat(le16(0x2001), le32(transaction))); }

    private static final class FakeSocket extends Socket {
        private final QueuedInput input;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final Peer peer;
        private final boolean event;
        private volatile boolean closed;

        FakeSocket(Peer peer, boolean event) { this.peer = peer; this.event = event; input = new QueuedInput(event); }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (closed) throw new IOException("closed socket");
                    pending.write(bytes, offset, length);
                    while (pending.size() >= 8) {
                        byte[] buffered = pending.toByteArray();
                        long frameLength = u32(buffered, 0);
                        if (frameLength < 8 || frameLength > 65536) throw new IOException("bad client frame length");
                        if (buffered.length < frameLength) return;
                        pending.reset();
                        pending.write(buffered, (int) frameLength, buffered.length - (int) frameLength);
                        peer.receive(FakeSocket.this, Arrays.copyOf(buffered, (int) frameLength));
                    }
                }
            };
        }
        @Override public void setSoTimeout(int timeout) { }
        @Override public void setTcpNoDelay(boolean enabled) { }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; input.close(); }
    }

    private static final class QueuedInput extends InputStream {
        private final Deque<byte[]> chunks = new ArrayDeque<>();
        private final boolean holdOpen;
        private int offset;
        private long remaining;
        private boolean closed;
        QueuedInput(boolean holdOpen) { this.holdOpen = holdOpen; }
        synchronized void add(byte[] bytes) {
            if (bytes.length != 0) { chunks.add(bytes); remaining += bytes.length; notifyAll(); }
        }
        synchronized long remaining() { return remaining; }
        private boolean hasData() throws IOException {
            while (chunks.isEmpty() && holdOpen && !closed) {
                try { wait(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("synthetic socket read interrupted", interrupted);
                }
            }
            return !closed && !chunks.isEmpty();
        }
        @Override public synchronized int read() throws IOException {
            if (!hasData()) return -1;
            int value = chunks.peek()[offset++] & 0xff;
            remaining--;
            if (offset == chunks.peek().length) { chunks.remove(); offset = 0; }
            return value;
        }
        @Override public synchronized int read(byte[] bytes, int start, int length) throws IOException {
            if (length == 0) return 0;
            if (!hasData()) return -1;
            // Deliberately split both headers and payload fields across many TCP reads.
            int count = Math.min(3, Math.min(length, chunks.peek().length - offset));
            System.arraycopy(chunks.peek(), offset, bytes, start, count);
            offset += count;
            remaining -= count;
            if (offset == chunks.peek().length) { chunks.remove(); offset = 0; }
            return count;
        }
        @Override public synchronized void close() { closed = true; notifyAll(); }
    }

    private static byte[] frame(int type, byte[] payload) { return concat(le32(8L + payload.length), le32(type), payload); }
    private static byte[] le16(long value) { return new byte[] {(byte) value, (byte) (value >>> 8)}; }
    private static byte[] le32(long value) { return new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)}; }
    private static byte[] le64(long value) { return concat(le32(value), le32(value >>> 32)); }
    private static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }
    private static int u16(byte[] bytes, int offset) { return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8); }
    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] bytes : arrays) length += bytes.length;
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] bytes : arrays) { System.arraycopy(bytes, 0, result, offset, bytes.length); offset += bytes.length; }
        return result;
    }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        try { action.run(); }
        catch (IOException | IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Expected a rejected PTP/IP operation");
    }
    private static void equal(Object expected, Object actual) {
        boolean same;
        if (expected instanceof byte[] && actual instanceof byte[]) same = Arrays.equals((byte[]) expected, (byte[]) actual);
        else if (expected instanceof long[] && actual instanceof long[]) same = Arrays.equals((long[]) expected, (long[]) actual);
        else same = java.util.Objects.equals(expected, actual);
        check(same, "Expected " + printable(expected) + ", got " + printable(actual));
    }
    private static String printable(Object value) {
        if (value instanceof byte[]) return Arrays.toString((byte[]) value);
        if (value instanceof long[]) return Arrays.toString((long[]) value);
        return String.valueOf(value);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

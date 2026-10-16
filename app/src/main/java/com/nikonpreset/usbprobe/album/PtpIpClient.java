package com.nikonpreset.usbprobe.album;

import com.nikonpreset.usbprobe.ptp.PtpResponseException;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** A bounded, read-only standard PTP/IP initiator. No Nikon private commands or pairing protocol. */
public final class PtpIpClient implements AutoCloseable {
    public interface SocketConnector { Socket connect(String host, int port, int timeoutMs) throws IOException; }
    public interface Progress { void onProgress(long received, long total); }
    private static final int CONTROL_TIMEOUT = 30000;
    private static final int DOWNLOAD_TIMEOUT = 600000;
    private static final long MAX_DOWNLOAD = 512L * 1024 * 1024;
    private final SocketConnector connector;
    private final Object lifecycle = new Object();
    private volatile Socket commandSocket, eventSocket;
    private InputStream input;
    private OutputStream output;
    private volatile boolean closed = true;
    private volatile boolean sessionOpen;
    private volatile Runnable onClosed;
    private boolean started;
    private boolean cancelled;
    private long nextTransaction = 1;

    public PtpIpClient(SocketConnector connector) { this.connector = Objects.requireNonNull(connector); }

    public synchronized byte[] connect(String host, int port, byte[] guid, String friendlyName) throws IOException {
        synchronized (lifecycle) {
            if (started || cancelled) throw new IOException("无线连接实例已使用或已取消。");
            started = true;
        }
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535 || guid == null || guid.length != 16) {
            throw new IOException("无线连接地址或客户端标识无效。");
        }
        if (friendlyName == null || friendlyName.isEmpty() || friendlyName.length() > 80 || friendlyName.indexOf(0) >= 0) {
            throw new IOException("无线客户端名称无效。");
        }
        synchronized (lifecycle) {
            if (cancelled) throw new IOException("无线连接已取消。");
            closed = false;
        }
        try {
            Socket command = connector.connect(host, port, 5000);
            install(command, false);
            command.setTcpNoDelay(true);
            command.setSoTimeout(15000);
            input = command.getInputStream(); output = command.getOutputStream();
            byte[] name = (friendlyName + "\0").getBytes(StandardCharsets.UTF_16LE);
            ByteArrayOutputStream init = new ByteArrayOutputStream();
            init.write(guid.clone()); init.write(name); put32(init, 0x00010000);
            send(output, 1, init.toByteArray());
            Header ack = header(input, deadline(CONTROL_TIMEOUT), command);
            byte[] ackData = body(input, ack, 1024, deadline(CONTROL_TIMEOUT), command);
            if (ack.type == 5) throw new IOException("相机拒绝无线连接。请在相机中确认连接，或检查其是否要求配对认证。");
            if (ack.type != 2 || ackData.length < 26) throw new IOException("相机没有返回受支持的无线握手。");
            long connectionNumber = u32(ackData, 0);
            validatePeerName(ackData);
            Socket events = connector.connect(host, port, 5000);
            install(events, true);
            events.setTcpNoDelay(true); events.setSoTimeout(15000);
            ByteArrayOutputStream eventInit = new ByteArrayOutputStream(); put32(eventInit, connectionNumber);
            send(events.getOutputStream(), 3, eventInit.toByteArray());
            Header eventAck = header(events.getInputStream(), deadline(CONTROL_TIMEOUT), events);
            byte[] eventBody = body(events.getInputStream(), eventAck, 1024, deadline(CONTROL_TIMEOUT), events);
            if (eventAck.type != 4 || eventBody.length != 0) throw new IOException("相机无线事件通道未建立。");
            byte[] info = receive(0x1001, 0, new long[0], 1024 * 1024, null, -1, null);
            request(0x1002, 0, new long[] {1});
            response(nextControl(deadline(CONTROL_TIMEOUT)), 0);
            sessionOpen = true;
            events.setSoTimeout(0);
            Thread eventReader = new Thread(() -> drainEvents(events), "camera-album-events");
            eventReader.setDaemon(true); eventReader.start();
            return info;
        } catch (IOException | RuntimeException error) { close(); throw error; }
    }

    public boolean isOpen() { return !closed && sessionOpen; }
    public void setOnClosed(Runnable listener) { onClosed = listener; }

    public synchronized byte[] getData(int operation, int maxBytes, long... parameters) throws IOException {
        requireOpen();
        if (operation == 0x1009 || maxBytes < 1 || maxBytes > 8 * 1024 * 1024) {
            throw new IOException("照片须使用流式下载；查询或缩略图大小超出限制。");
        }
        validateRead(operation, parameters);
        return receive(operation, transaction(), parameters, maxBytes, null, -1, null);
    }

    public synchronized long readTo(int operation, OutputStream destination, long maxBytes, long expectedBytes,
                       Progress progress, long... parameters) throws IOException {
        requireOpen();
        if (operation != 0x1009 || destination == null || maxBytes < 1 || maxBytes > MAX_DOWNLOAD
                || expectedBytes < 0 || expectedBytes > maxBytes) throw new IOException("照片下载参数无效。");
        validateRead(operation, parameters);
        long[] received = new long[1];
        receive(operation, transaction(), parameters, maxBytes, destination, expectedBytes,
                (done, total) -> { received[0] = done; if (progress != null) progress.onProgress(done, total); });
        return received[0];
    }

    private byte[] receive(int operation, long transaction, long[] parameters, long maxBytes,
                           OutputStream destination, long expectedBytes, Progress progress) throws IOException {
        validateRead(operation, parameters);
        try {
            request(operation, transaction, parameters);
            long until = deadline(destination == null ? CONTROL_TIMEOUT : DOWNLOAD_TIMEOUT);
            Header first = nextHeader(until);
            if (first.type == 7) {
                response(body(input, first, 26, until, commandSocket), transaction);
                throw new IOException("相机未返回所需数据。");
            }
            if (first.type != 9) throw new IOException("无线数据起始包类型不符。");
            byte[] start = body(input, first, 12, until, commandSocket);
            if (start.length != 12 || u32(start, 0) != transaction) throw new IOException("无线数据事务不匹配。");
            long total = u64(start, 4);
            if (total < 0 || total > maxBytes) throw new IOException("相机返回的数据超过本次接收上限。");
            ByteArrayOutputStream memory = destination == null ? new ByteArrayOutputStream((int) Math.min(total, 65536)) : null;
            OutputStream sink = destination == null ? memory : destination;
            long received = 0;
            int emptyPackets = 0;
            byte[] buffer = new byte[32768];
            if (progress != null) progress.onProgress(0, total);
            while (true) {
                Header packet = nextHeader(until);
                if (packet.type == 7) {
                    response(body(input, packet, 26, until, commandSocket), transaction);
                    throw new IOException("无线数据未结束就收到完成响应。");
                }
                if ((packet.type != 10 && packet.type != 12) || packet.payload < 4) throw new IOException("无线数据包类型或长度无效。");
                byte[] id = new byte[4]; fully(input, id, 0, 4, until, commandSocket);
                if (u32(id, 0) != transaction) throw new IOException("无线数据包事务不匹配。");
                long count = packet.payload - 4;
                if (count > total - received) throw new IOException("无线数据长度超过起始声明。");
                if (count == 0 && ++emptyPackets > 32) throw new IOException("无线数据连续无进展。");
                while (count > 0) {
                    int step = (int) Math.min(count, buffer.length);
                    fully(input, buffer, 0, step, until, commandSocket);
                    sink.write(buffer, 0, step); received += step; count -= step;
                    if (progress != null) progress.onProgress(received, total);
                }
                if (packet.type == 12) break;
            }
            if (received != total) throw new IOException("无线照片数据被截断。");
            response(nextControl(until), transaction);
            if (expectedBytes > 0 && received != expectedBytes) throw new IOException("照片大小已变化，请刷新相机照片列表。");
            return memory == null ? null : memory.toByteArray();
        } catch (PtpResponseException error) { throw error; }
        catch (IOException | RuntimeException error) { close(); throw error; }
    }

    private void request(int operation, long transaction, long[] parameters) throws IOException {
        checkClosed();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        put32(payload, 1); payload.write(operation); payload.write(operation >>> 8); put32(payload, transaction);
        for (long value : parameters) { if (value < 0 || value > 0xffffffffL) throw new IOException("无线命令参数越界。"); put32(payload, value); }
        send(output, 6, payload.toByteArray());
    }

    private static void validateRead(int operation, long[] p) throws IOException {
        boolean valid = p != null && ((operation == 0x1001 || operation == 0x1004) && p.length == 0
                || operation == 0x1007 && p.length == 3
                || (operation == 0x1008 || operation == 0x1009 || operation == 0x100a) && p.length == 1);
        if (!valid) throw new IOException("无线相册只允许读取设备、照片列表、缩略图和照片。");
        for (long value : p) if (value < 0 || value > 0xffffffffL) throw new IOException("无线命令参数越界。");
    }

    private Header nextHeader(long until) throws IOException {
        for (int i = 0; i < 32; i++) {
            Header h = header(input, until, commandSocket);
            if (h.type != 13) return h;
            if (h.payload != 0) throw new IOException("无线探测包长度无效。");
            send(output, 14, new byte[0]);
        }
        throw new IOException("无线探测包过多。");
    }
    private byte[] nextControl(long until) throws IOException {
        Header h = nextHeader(until);
        if (h.type != 7) throw new IOException("无线完成响应类型不符。");
        return body(input, h, 26, until, commandSocket);
    }
    private static void response(byte[] p, long transaction) throws IOException {
        if (p.length < 6 || p.length > 26 || (p.length - 6) % 4 != 0 || u32(p, 2) != transaction) throw new IOException("无线响应长度或事务不匹配。");
        int code = (p[0] & 255) | ((p[1] & 255) << 8);
        if (code != 0x2001) throw new PtpResponseException(code, transaction);
    }

    private void drainEvents(Socket socket) {
        try {
            InputStream events = socket.getInputStream();
            while (!closed) {
                Header h = header(events, Long.MAX_VALUE, socket);
                if (h.type == 13 && h.payload == 0) send(socket.getOutputStream(), 14, new byte[0]);
                else if (h.type == 8 && h.payload >= 6 && h.payload <= 18 && (h.payload - 6) % 4 == 0) body(events, h, 18, Long.MAX_VALUE, socket);
                else throw new IOException("相机无线事件通道返回未知数据。");
            }
        } catch (IOException | RuntimeException error) { close(); }
    }

    private static void validatePeerName(byte[] p) throws IOException {
        int end = 20;
        while (end + 1 < p.length - 4 && (p[end] != 0 || p[end + 1] != 0)) end += 2;
        if (end + 6 != p.length || u32(p, end + 2) != 0x00010000) throw new IOException("相机无线握手版本或名称无效。");
        StandardCharsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(p, 20, end - 20));
    }
    private void install(Socket socket, boolean event) throws IOException {
        if (socket == null) throw new IOException("无法建立相机网络连接。");
        synchronized (lifecycle) {
            if (closed) { socket.close(); throw new IOException("无线连接已取消。"); }
            if (event) eventSocket = socket; else commandSocket = socket;
        }
    }
    private long transaction() throws IOException { if (nextTransaction > 0xffffffffL) throw new IOException("无线会话编号已用尽，请重新连接。"); return nextTransaction++; }
    private void requireOpen() throws IOException { if (!isOpen()) throw new IOException("请先连接相机无线网络。"); }
    private void checkClosed() throws IOException { if (closed || Thread.currentThread().isInterrupted()) throw new IOException("无线连接已关闭或操作已取消。"); }
    @Override public void close() {
        boolean changed;
        synchronized (lifecycle) {
            changed = !closed;
            cancelled = true; closed = true; sessionOpen = false;
            for (Socket socket : new Socket[] {commandSocket, eventSocket}) if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
        Runnable listener = onClosed;
        if (changed && listener != null) try { listener.run(); } catch (RuntimeException ignored) { }
    }
    private static long deadline(int millis) { return System.nanoTime() + millis * 1000000L; }
    private static Header header(InputStream in, long until, Socket socket) throws IOException {
        byte[] b = new byte[8]; fully(in, b, 0, 8, until, socket);
        long length = u32(b, 0); if (length < 8) throw new IOException("无线包头长度无效。");
        return new Header(u32(b, 4), length - 8);
    }
    private static byte[] body(InputStream in, Header h, int max, long until, Socket socket) throws IOException {
        if (h.payload > max) throw new IOException("无线控制数据超出上限。");
        byte[] data = new byte[(int) h.payload]; fully(in, data, 0, data.length, until, socket); return data;
    }
    private static void fully(InputStream in, byte[] b, int offset, int length, long until, Socket socket) throws IOException {
        int end = offset + length;
        while (offset < end) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("无线操作已取消。");
            if (until != Long.MAX_VALUE) {
                long remaining = (until - System.nanoTime()) / 1000000L;
                if (remaining <= 0) throw new IOException("相机无线响应超时。");
                socket.setSoTimeout((int) Math.min(15000, Math.max(1, remaining)));
            }
            int n = in.read(b, offset, end - offset); if (n < 0) throw new EOFException("相机无线连接提前断开。");
            if (n == 0) throw new IOException("无线读取没有进展。"); offset += n;
        }
    }
    private static void send(OutputStream out, int type, byte[] p) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(p.length + 8); put32(bytes, p.length + 8L); put32(bytes, type); bytes.write(p); out.write(bytes.toByteArray()); out.flush();
    }
    private static void put32(OutputStream out, long value) throws IOException { for (int i = 0; i < 4; i++) out.write((int) (value >>> (8 * i)) & 255); }
    private static long u32(byte[] p, int off) { return (p[off] & 255L) | ((p[off + 1] & 255L) << 8) | ((p[off + 2] & 255L) << 16) | ((p[off + 3] & 255L) << 24); }
    private static long u64(byte[] p, int off) { return u32(p, off) | (u32(p, off + 4) << 32); }
    private static final class Header { final long type, payload; Header(long type, long payload) { this.type = type; this.payload = payload; } }
}

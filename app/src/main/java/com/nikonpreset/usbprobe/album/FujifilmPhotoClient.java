package com.nikonpreset.usbprobe.album;

import com.nikonpreset.usbprobe.ptp.PtpContainer;
import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
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
import java.util.Arrays;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.function.LongConsumer;

/**
 * Experimental Camera Remote era Fujifilm Wi-Fi JPEG reader (X-T20).
 * Fuji uses USB PTP containers on TCP, not Nikon's standard PTP/IP framing.
 * Protocol facts: petabyt/libfuji lib/fuji.c and lib/fujiptp.h (MIT).
 * No capture, delete, preset, or generic property-write API is exposed.
 */
public final class FujifilmPhotoClient implements PhotoCameraClient {
    public static final int DEFAULT_PORT = 55740;
    private static final int MAX_METADATA = 65536;
    private static final int MAX_HANDLES = 250000;
    private static final int CHUNK = 0x100000;
    private static final long MAX_PHOTO = 512L * 1024 * 1024;
    private static final int GET_PROPERTY = 0x1015, SET_PROPERTY = 0x1016;
    private final PtpIpClient.SocketConnector connector;
    private final Object lifecycle = new Object();
    private volatile Socket commandSocket, eventSocket, videoSocket;
    private volatile boolean closed = true, started, cancelled, protocolUnconfirmed;
    private volatile Runnable onClosed;
    private volatile String closeReason = "";
    private InputStream input;
    private OutputStream output;
    private long nextTransaction = 1;
    private int cameraState = -1, objectCount = -1;
    private String connectedHost = "", cameraIdentity = "";
    private int connectedPort;
    private volatile byte[] deviceInfo;
    private long responseDeadline;
    private boolean metadataPage;
    private static final int THUMB_ENTRIES = 48, THUMB_BYTES = 8 * 1024 * 1024;
    private final LinkedHashMap<Long, byte[]> descriptors = new LinkedHashMap<>();
    private final LinkedHashMap<Long, byte[]> thumbnails = new LinkedHashMap<>(16, .75f, true);
    private int thumbnailBytes;

    public FujifilmPhotoClient(PtpIpClient.SocketConnector connector) {
        this.connector = Objects.requireNonNull(connector);
    }

    @Override public synchronized byte[] connect(String host, int port, byte[] guid, String name) throws IOException {
        synchronized (lifecycle) {
            if (started || cancelled) throw new IOException("富士连接实例已使用或已取消。");
            started = true;
            if (host == null || host.trim().isEmpty() || port < 1 || port > 65535
                    || guid == null || guid.length != 16 || name == null || name.isEmpty() || name.indexOf(0) >= 0) {
                throw new IOException("富士连接地址或客户端名称无效。");
            }
            connectedHost = host.trim(); connectedPort = port; closed = false;
        }
        try {
            Socket command = openSocket(port);
            input = command.getInputStream(); output = command.getOutputStream();
            byte[] init = new byte[82];
            put32(init, 0, 82); put32(init, 4, 1); put32(init, 8, 0x8f53e4f2L);
            put32(init, 12, 0x5d48a5adL); put32(init, 16, 0x0b7fb287L);
            put32(init, 20, 0xd0ded5d3L);
            String shortName = name.substring(0, Math.min(26, name.length()));
            if (!shortName.isEmpty() && Character.isHighSurrogate(shortName.charAt(shortName.length() - 1))) {
                shortName = shortName.substring(0, shortName.length() - 1);
            }
            byte[] encoded = shortName.getBytes(StandardCharsets.UTF_16LE);
            System.arraycopy(encoded, 0, init, 28, encoded.length);
            responseDeadline = System.nanoTime() + 30000000000L;
            write(init);
            byte[] ack = readPacket(4096, 8);
            if (u32(ack, 4) != 2 || ack.length < 30 || (ack.length & 1) != 0) {
                throw new IOException("富士相机未接受连接，请在相机上确认连接并重新尝试。");
            }
            cameraIdentity = readUnicode(ack, 28, ack.length - 28);
            if (!"X-T20".equalsIgnoreCase(cameraIdentity.trim())) {
                throw new IOException("富士实验适配仅支持相机实际返回的 X-T20；请检查机型。");
            }
            pause(50);
            transact(0x1002, null, 0, 1); // Fuji starts OpenSession at transaction 1.
            pollEvents();
            long accessDeadline = System.nanoTime() + 30000000000L;
            while (cameraState == -1 || cameraState == 0) {
                if (System.nanoTime() >= accessDeadline) throw new IOException("等待相机确认超时，请在 X-T20 上按 OK。");
                pause(100); pollEvents();
            }
            if (cameraState != 2 && cameraState != 6) {
                throw new IOException("请选择富士相机的“无线通信”，再从应用浏览照片。");
            }
            property(0xdf22);
            long galleryVersion = property(0xdf25);
            property(0xdf21);
            long remoteVersion = property(0xdf24);
            if (remoteVersion == 0xffffffffL || galleryVersion == 0xffffffffL) {
                throw new IOException("此富士无线协议版本暂未验证；当前适配面向 X-T20。");
            }
            set16(0xdf01, 5); pollEvents();
            set32(0xdf24, 0x2000c);
            optionalProperty(0xd244);
            // libfuji requires entering/exiting remote preview before gallery initialization.
            // InitiateOpenCapture opens remote channels; it does not release the shutter.
            if (cameraState == 6) {
                long openCaptureTransaction = nextTransaction;
                transact(0x101c, null, 0, 0, 0);
                pollEvents(); pause(50);
                openSocket(55741); openSocket(55742);
                pollEvents();
                transact(0x1018, null, 0, openCaptureTransaction);
                pollEvents(); set16(0xdf00, 6); pollEvents(); pollEvents();
                set16(0xdf01, 11); pollEvents(); property(0xdf25); set32(0xdf25, 5); pollEvents();
            } else {
                throw new IOException("相机未进入 X-T20 远程照片浏览模式，请重新连接。");
            }
            if (objectCount < 0) pollEvents();
            if (objectCount < 0) throw new IOException("富士相机未返回照片数量。");
            deviceInfo = adapterDeviceInfo(cameraIdentity);
            return deviceInfo.clone();
        } catch (IOException | RuntimeException error) {
            fail(error instanceof IOException ? (IOException)error : new IOException("富士连接失败。", error));
            throw error;
        }
    }

    @Override public synchronized byte[] readAlbumDeviceInfo() throws IOException {
        requireOpen();
        try { pollEvents(); return deviceInfo.clone(); }
        catch (IOException failure) { fail(failure); throw failure; }
    }

    @Override public synchronized void beginPhotoPage() throws IOException {
        requireOpen();
        if (metadataPage) throw new IOException("富士照片信息批次尚未结束。");
        try { pollEvents(); set16(0xd227, 1); metadataPage = true; }
        catch (IOException failure) { fail(failure); throw failure; }
    }

    @Override public synchronized void endPhotoPage() throws IOException {
        if (!metadataPage) return;
        metadataPage = false;
        if (closed || cancelled) return;
        try { pollEvents(); set16(0xd227, 0); }
        catch (IOException failure) { fail(failure); throw failure; }
    }

    @Override public synchronized byte[] getData(int operation, int maxBytes, long... parameters) throws IOException {
        requireOpen();
        if (maxBytes < 0 || maxBytes > 32 * 1024 * 1024) throw new IOException("富士读取上限无效。");
        try {
            byte[] result;
            switch (operation) {
                case 0x1004:
                    requireParameters(parameters, 0); result = array(1); break;
                case 0x1007:
                    requireParameters(parameters, 3);
                    if ((parameters[0] != 1 && parameters[0] != 0xffffffffL)
                            || (parameters[1] != 0 && parameters[1] != 0x3801) || parameters[2] != 0) {
                        throw new IOException("富士相册查询条件不受支持。");
                    }
                    pollEvents();
                    if (objectCount < 0 || objectCount > MAX_HANDLES || 4L + 4L * objectCount > maxBytes) {
                        throw new IOException("富士照片列表超出读取上限。");
                    }
                    result = new byte[4 + 4 * objectCount]; put32(result, 0, objectCount);
                    for (int i = 0; i < objectCount; i++) put32(result, 4 + i * 4, i + 1);
                    break;
                case 0x1008:
                    requireParameters(parameters, 1); validHandle(parameters[0]);
                    result = objectInfo(parameters[0]).toBytes(); break;
                case 0x100a:
                    requireParameters(parameters, 1); validHandle(parameters[0]);
                    byte[] cached = thumbnails.get(parameters[0]);
                    if (cached != null && cached.length <= maxBytes) result = cached.clone();
                    else {
                        result = trimThumbnailSuffix(transact(operation, null, maxBytes, parameters));
                        rememberThumbnail(parameters[0], result);
                    }
                    break;
                default: throw new IOException("富士照片连接不允许此操作：" + Integer.toHexString(operation));
            }
            if (result.length > maxBytes) throw new IOException("富士响应超出读取上限。");
            return result;
        } catch (PtpResponseException response) {
            throw response;
        } catch (IOException failure) {
            fail(failure); throw failure;
        }
    }

    @Override public byte[] getLargeThumbnail(long handle) throws IOException {
        throw new IOException("富士 X-T20 不支持尼康大缩略图操作。");
    }

    @Override public synchronized long readTo(int operation, OutputStream destination, long maxBytes,
            long expectedBytes, PhotoCameraClient.Progress progress, long... parameters) throws IOException {
        requireOpen(); Objects.requireNonNull(destination);
        if (operation != 0x1009 || parameters.length != 1 || maxBytes < 1 || maxBytes > MAX_PHOTO) {
            throw new IOException("富士连接仅允许下载 JPEG 照片。");
        }
        validHandle(parameters[0]);
        try {
            pollEvents(); set16(0xd227, 1);
            PtpObjectInfo info = normalizeObjectInfo(transact(0x1008, null, MAX_METADATA, parameters));
            if (info.objectFormat() != 0x3801) throw new IOException("富士 X-T20 当前仅支持 JPEG 下载。");
            long size = info.compressedSize();
            if (size < 1 || size > maxBytes || (expectedBytes > 0 && expectedBytes != size)) {
                throw new IOException("富士照片大小已变化或超出下载上限，请刷新相册。");
            }
            long received = 0;
            if (progress != null) progress.onProgress(0, size);
            while (received < size) {
                requireOpen();
                int request = (int)Math.min(CHUNK, size - received);
                byte[] chunk = transact(0x101b, null, request, parameters[0], received, request);
                if (chunk.length != request) throw new IOException("富士照片分块长度不符，下载未完成。");
                if (received == 0 && (chunk.length < 2 || (chunk[0] & 255) != 255 || (chunk[1] & 255) != 216)) {
                    throw new IOException("富士相机返回的照片不是 JPEG。");
                }
                destination.write(chunk); received += chunk.length;
                if (progress != null) progress.onProgress(received, size);
            }
            pollEvents(); set16(0xd227, 0);
            return received;
        } catch (IOException | RuntimeException failure) {
            fail(failure instanceof IOException ? (IOException)failure : new IOException("富士下载中断。", failure));
            throw failure;
        }
    }

    private PtpObjectInfo objectInfo(long handle) throws IOException {
        if (metadataPage) return readObjectInfo(handle);
        pollEvents(); set16(0xd227, 1);
        PtpObjectInfo info = readObjectInfo(handle);
        pollEvents(); set16(0xd227, 0); return info;
    }

    private PtpObjectInfo readObjectInfo(long handle) throws IOException {
        PtpObjectInfo info = normalizeObjectInfo(transact(0x1008, null, MAX_METADATA, handle));
        byte[] current = info.toBytes(), previous = descriptors.put(handle, current);
        if (previous == null || !Arrays.equals(previous, current)) forgetThumbnail(handle);
        while (descriptors.size() > 64) {
            Long oldest = descriptors.keySet().iterator().next(); descriptors.remove(oldest); forgetThumbnail(oldest);
        }
        return info;
    }

    /** Only GetThumb uses this: original downloads must retain every received byte. */
    static byte[] trimThumbnailSuffix(byte[] payload) {
        int end = thumbnailJpegEnd(payload);
        return end > 0 && end < payload.length ? Arrays.copyOf(payload, end) : payload;
    }

    // Walk segment lengths and entropy markers rather than searching for FF D9:
    // EXIF/APP segments can contain another JPEG, and scan bytes use FF 00 stuffing.
    // Leave unrecognized payloads intact; never add EOI or promote an embedded JPEG.
    private static int thumbnailJpegEnd(byte[] data) {
        if (data.length < 4 || (data[0] & 255) != 255 || (data[1] & 255) != 216) return -1;
        int at = 2;
        boolean scan = false, sawFrame = false, sawScan = false;
        while (at < data.length) {
            if (scan) while (at < data.length && (data[at] & 255) != 255) at++;
            if (at >= data.length || (data[at++] & 255) != 255) return -1;
            while (at < data.length && (data[at] & 255) == 255) at++;
            if (at >= data.length) return -1;
            int marker = data[at++] & 255;
            if (marker == 0) { if (!scan) return -1; continue; }
            if (marker == 1) continue; // TEM, no length field
            if (marker >= 0xd0 && marker <= 0xd7) { if (!scan) return -1; continue; }
            if (marker == 0xd9) return sawFrame && sawScan ? at : -1;
            if (marker == 0xd8 || at + 2 > data.length) return -1;
            int length = ((data[at] & 255) << 8) | (data[at + 1] & 255);
            if (length < 2 || length > data.length - at) return -1;
            boolean frame = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc;
            if (frame) {
                if (length < 11 || (data[at + 7] & 255) == 0 || length != 8 + 3 * (data[at + 7] & 255)) return -1;
                sawFrame = true;
            }
            if (marker == 0xda) {
                if (!sawFrame || length < 8 || (data[at + 2] & 255) == 0 || length != 6 + 2 * (data[at + 2] & 255)) return -1;
                scan = true; sawScan = true;
            } else scan = scan && marker == 0xdc; // DNL can occur inside a scan.
            at += length;
        }
        return -1;
    }

    private void rememberThumbnail(long handle, byte[] jpeg) {
        if (!descriptors.containsKey(handle) || jpeg.length < 4 || jpeg.length > THUMB_BYTES
                || (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216
                || (jpeg[jpeg.length - 2] & 255) != 255 || (jpeg[jpeg.length - 1] & 255) != 217) return;
        forgetThumbnail(handle);
        while (!thumbnails.isEmpty() && (thumbnails.size() >= THUMB_ENTRIES || thumbnailBytes + jpeg.length > THUMB_BYTES)) {
            forgetThumbnail(thumbnails.keySet().iterator().next());
        }
        thumbnails.put(handle, jpeg.clone()); thumbnailBytes += jpeg.length;
    }

    private void forgetThumbnail(long handle) {
        byte[] removed = thumbnails.remove(handle); if (removed != null) thumbnailBytes -= removed.length;
    }

    private static PtpObjectInfo normalizeObjectInfo(byte[] bytes) throws IOException {
        // Follow libfuji's maintained download path through libpict's standard
        // ObjectInfo parser. Only the negotiated remote-gallery mode is enabled;
        // an unexpected layout fails parsing rather than guessing field offsets.
        PtpObjectInfo info = PtpObjectInfo.parse(bytes);
        byte[] standard = info.toBytes(); put32(standard, 0, 1);
        return PtpObjectInfo.parse(standard);
    }

    private void pollEvents() throws IOException {
        byte[] events = transact(GET_PROPERTY, null, MAX_METADATA, 0xd212);
        if (events.length < 2 || events.length != 2 + 6 * u16(events, 0)) throw new IOException("富士事件列表格式无效。");
        for (int at = 2; at < events.length; at += 6) {
            int code = u16(events, at); long value = u32(events, at + 2);
            if (code == 0xdf00) cameraState = (int)value;
            if (code == 0xd222) {
                if (value > MAX_HANDLES) throw new IOException("富士照片数量超出上限。");
                if (objectCount >= 0 && objectCount != value) {
                    descriptors.clear(); thumbnails.clear(); thumbnailBytes = 0;
                }
                objectCount = (int)value;
            }
        }
    }

    private long optionalProperty(int code) throws IOException {
        try { return property(code); } catch (PtpResponseException response) {
            if (response.responseCode() == 0x200a || response.responseCode() == 0x2005) return 0xffffffffL;
            throw response;
        }
    }
    private long property(int code) throws IOException {
        final byte[] bytes;
        try { bytes = transact(GET_PROPERTY, null, 4, code); }
        catch (PtpResponseException response) { throw response; }
        catch (IOException failure) {
            throw new IOException(String.format(java.util.Locale.ROOT, "读取富士属性 0x%04X 失败：", code) + failure.getMessage(), failure);
        }
        // libpict ptp_parse_prop_value accepts UINT8 / UINT16 / UINT32. A
        // complete 1-byte scalar is valid; do not reinterpret truncated values.
        if (bytes.length == 1) return bytes[0] & 255L;
        if (bytes.length == 2) return u16(bytes, 0);
        if (bytes.length == 4) return u32(bytes, 0);
        throw new IOException(String.format(java.util.Locale.ROOT,
                "富士属性 0x%04X 返回 %d 字节，当前仅支持 1、2 或 4 字节的数值。", code, bytes.length));
    }
    private void set16(int code, long value) throws IOException {
        byte[] bytes = new byte[2]; put16(bytes, 0, (int)value); setProperty(code, bytes);
    }
    private void set32(int code, long value) throws IOException {
        byte[] bytes = new byte[4]; put32(bytes, 0, value); setProperty(code, bytes);
    }
    private void setProperty(int code, byte[] bytes) throws IOException {
        // Only negotiation, gallery state and correct-size properties may be set.
        if (code != 0xdf00 && code != 0xdf01 && code != 0xdf24 && code != 0xdf25 && code != 0xd227) {
            throw new IOException("不允许修改此富士相机属性。");
        }
        transact(SET_PROPERTY, bytes, 0, code);
    }

    private byte[] transact(int operation, byte[] outgoing, int maximum, long... parameters) throws IOException {
        requireOpen();
        if (nextTransaction > 0xffffffffL) throw new IOException("富士事务编号已耗尽，请重新连接。");
        responseDeadline = System.nanoTime() + 30000000000L;
        long transaction = nextTransaction++;
        write(PtpContainer.command(operation, transaction, parameters).toBytes());
        if (outgoing != null) write(PtpContainer.data(operation, transaction, outgoing).toBytes());
        byte[] packet = readPacket(Math.max(32, maximum + 12), 12);
        if (u32(packet, 8) != transaction) throw new IOException("富士响应事务编号不符。");
        byte[] payload = null;
        if (u16(packet, 4) == 2) {
            if (u16(packet, 6) != operation || packet.length - 12 > maximum) throw new IOException("富士数据响应不符。");
            payload = Arrays.copyOfRange(packet, 12, packet.length);
            packet = readPacket(32, 12);
        }
        if (u16(packet, 4) != 3 || u32(packet, 8) != transaction || ((packet.length - 12) & 3) != 0) {
            throw new IOException("富士 PTP 响应格式无效。");
        }
        int response = u16(packet, 6);
        if (response != 0x2001) throw new PtpResponseException(response, transaction);
        if (maximum > 0 && payload == null) throw new IOException("富士响应缺少数据。");
        if (maximum == 0 && payload != null) throw new IOException("富士命令出现意外数据。");
        if (operation == 0x101b && packet.length >= 16 && u32(packet, 12) != payload.length) {
            throw new IOException("富士分块响应的字节数量不符。");
        }
        return payload == null ? new byte[0] : payload;
    }

    private Socket openSocket(int port) throws IOException {
        requireOpen();
        final Socket socket;
        String channel = commandSocket == null ? "命令" : port == 55741 ? "事件" : "预览";
        try { socket = connector.connect(connectedHost, port, 5000); }
        catch (java.net.ConnectException refused) {
            throw new IOException("富士" + channel + "通道连接被拒绝（" + connectedHost + ":" + port + "）。"
                    + (commandSocket == null ? "Wi-Fi 已连接，但相机尚未接受传图连接；请重新进入相机“无线通信”后重试。"
                    : "命令握手已完成，但辅助通道尚未建立；请退出并重新开启相机“无线通信”后重试。"), refused);
        } catch (java.net.SocketTimeoutException timeout) {
            throw new IOException("建立富士" + channel + "通道超时（" + connectedHost + ":" + port + "）。请检查相机是否仍在无线通信中。", timeout);
        }
        synchronized (lifecycle) {
            if (closed || cancelled) { socket.close(); throw new IOException("富士连接已取消。"); }
            if (port == connectedPort && commandSocket == null) commandSocket = socket;
            else if (port == 55741 && eventSocket == null) eventSocket = socket;
            else if (port == 55742 && videoSocket == null) videoSocket = socket;
            else { socket.close(); throw new IOException("富士连接通道无效。"); }
        }
        socket.setTcpNoDelay(true); socket.setSoTimeout(30000); return socket;
    }
    private void write(byte[] bytes) throws IOException { requireOpen(); output.write(bytes); output.flush(); }
    private byte[] readPacket(int maximum, int minimum) throws IOException {
        byte[] length = new byte[4]; readFully(length, 0, 4);
        long size = u32(length, 0);
        if (size < minimum || size > maximum) throw new IOException("富士数据包长度超出边界。");
        byte[] packet = new byte[(int)size]; System.arraycopy(length, 0, packet, 0, 4);
        readFully(packet, 4, packet.length - 4); return packet;
    }
    private void readFully(byte[] bytes, int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            requireOpen();
            long remaining = responseDeadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("富士相机响应超时。");
            commandSocket.setSoTimeout((int)Math.max(1, Math.min(30000, remaining / 1000000)));
            int count = input.read(bytes, offset + done, length - done);
            if (count < 0) throw new EOFException("富士相机连接已结束。");
            if (count == 0) continue; done += count;
        }
    }
    private void requireOpen() throws IOException { if (closed || cancelled) throw new IOException("富士连接已关闭。"); }
    private static void requireParameters(long[] values, int count) throws IOException {
        if (values.length != count) throw new IOException("富士操作参数不符。");
    }
    private static void validHandle(long handle) throws IOException {
        if (handle < 1 || handle > MAX_HANDLES) throw new IOException("富士照片编号无效。");
    }
    private static void pause(long milliseconds) throws IOException {
        try { Thread.sleep(milliseconds); } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IOException("富士连接已取消。", interrupted);
        }
    }
    @Override public boolean isOpen() { return !closed && deviceInfo != null; }
    public boolean isClosed() { return closed; }
    @Override public boolean isProtocolUnconfirmed() { return protocolUnconfirmed; }
    @Override public String closeReason() { return closeReason; }
    @Override public String connectedHost() { return connectedHost; }
    @Override public int connectedPort() { return connectedPort; }
    @Override public String cameraIdentity() { return cameraIdentity; }
    @Override public void setOnClosed(Runnable callback) { onClosed = callback; }
    @Override public void setOnObjectAdded(LongConsumer ignored) { /* X-T20 photo live is not verified. */ }
    @Override public void abortProtocol(IOException cause) { fail(cause); }
    private void fail(IOException cause) { protocolUnconfirmed = true; closeReason = cause.getMessage(); close(); }
    @Override public void close() {
        Runnable callback;
        synchronized (lifecycle) {
            cancelled = true;
            if (closed) return;
            closed = true; callback = onClosed; onClosed = null;
            closeSocket(commandSocket); closeSocket(eventSocket); closeSocket(videoSocket);
            commandSocket = null; eventSocket = null; videoSocket = null;
        }
        if (callback != null) callback.run();
    }
    private static void closeSocket(Socket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) {} }
    private static byte[] array(long... values) {
        byte[] result = new byte[4 + values.length * 4]; put32(result, 0, values.length);
        for (int i = 0; i < values.length; i++) put32(result, 4 + i * 4, values[i]); return result;
    }
    private static byte[] adapterDeviceInfo(String cameraName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        write16(bytes, 100); write32(bytes, 14); write16(bytes, 1);
        writeString(bytes, "Experimental Fujifilm Wi-Fi JPEG adapter"); write16(bytes, 0);
        write32(bytes, 6); for (int op : new int[]{0x1001, 0x1004, 0x1007, 0x1008, 0x1009, 0x100a}) write16(bytes, op);
        write32(bytes, 0); write32(bytes, 0); write32(bytes, 0);
        write32(bytes, 1); write16(bytes, 0x3801);
        writeString(bytes, "FUJIFILM"); writeString(bytes, cameraName); writeString(bytes, ""); writeString(bytes, "");
        return bytes.toByteArray();
    }
    private static void writeString(OutputStream output, String value) throws IOException {
        output.write(value.length() + 1); output.write(value.getBytes(StandardCharsets.UTF_16LE)); write16(output, 0);
    }
    private static void write16(OutputStream output, int value) throws IOException { output.write(value); output.write(value >>> 8); }
    private static void write32(OutputStream output, long value) throws IOException { for (int i = 0; i < 4; i++) output.write((int)(value >>> (8 * i))); }
    private static String readUnicode(byte[] bytes, int offset, int length) throws IOException {
        int end = offset;
        while (end + 1 < offset + length && u16(bytes, end) != 0) end += 2;
        if (end + 1 >= offset + length) throw new IOException("富士名称缺少结束标记。");
        return StandardCharsets.UTF_16LE.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, end - offset)).toString();
    }
    private static int u16(byte[] b, int at) { return (b[at] & 255) | ((b[at + 1] & 255) << 8); }
    private static long u32(byte[] b, int at) { return (b[at] & 255L) | ((b[at + 1] & 255L) << 8) | ((b[at + 2] & 255L) << 16) | ((b[at + 3] & 255L) << 24); }
    private static void put16(byte[] b, int at, int value) { b[at] = (byte)value; b[at + 1] = (byte)(value >>> 8); }
    private static void put32(byte[] b, int at, long value) { for (int i = 0; i < 4; i++) b[at + i] = (byte)(value >>> (8 * i)); }
}

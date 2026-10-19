package com.nikonpreset.usbprobe.album;

import com.nikonpreset.usbprobe.picturecontrol.PictureControlWriteValidation;
import com.nikonpreset.usbprobe.ptp.PtpContainer;
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
import java.util.Arrays;

/** Bounded PTP/IP initiator with separate album and explicitly requested Picture Control modes. */
public final class PtpIpClient implements PhotoCameraClient {
    public interface SocketConnector { Socket connect(String host, int port, int timeoutMs) throws IOException; }
    private static final int CONTROL_TIMEOUT = 30000;
    private static final int DOWNLOAD_TIMEOUT = 600000;
    private static final long MAX_DOWNLOAD = 512L * 1024 * 1024;
    private enum Mode { ALBUM_READ_ONLY, PICTURE_CONTROL }
    private final SocketConnector connector;
    private final Mode mode;
    private final ConnectionOwnership ownership;
    private final Object lifecycle;
    private volatile Socket commandSocket, eventSocket;
    private InputStream input;
    private OutputStream output;
    private volatile boolean closed = true;
    private volatile boolean sessionOpen;
    private volatile boolean protocolUnconfirmed;
    private volatile Runnable onClosed;
    private volatile java.util.function.LongConsumer onObjectAdded;
    private String cameraIdentity = "";
    private boolean started;
    private boolean cancelled;
    private boolean pictureControlWriteAttempted;
    private boolean pictureControlWriteAcknowledged;
    private boolean pictureControlReadbackVerified;
    private byte[] pictureControlWrittenData;
    private int pictureControlWrittenTarget = -1;
    private long pictureControlWriteTransaction;
    private long lastPictureControlTransaction;
    private long nextTransaction = 1;
    private boolean transferredSession;
    private boolean handoffClaimed;
    private volatile String closeReason = "";
    private String connectedHost = "";
    private int connectedPort;

    /** The event reader follows the physical connection, not an obsolete API handle. */
    private static final class ConnectionOwnership {
        final long traceId = ConnectionTrace.nextConnectionId();
        PtpIpClient owner;
    }

    public PtpIpClient(SocketConnector connector) { this(connector, Mode.ALBUM_READ_ONLY); }

    private PtpIpClient(SocketConnector connector, Mode mode) {
        this(connector, mode, new ConnectionOwnership());
        ownership.owner = this;
    }

    private PtpIpClient(SocketConnector connector, Mode mode, ConnectionOwnership ownership) {
        this.connector = Objects.requireNonNull(connector);
        this.mode = mode;
        this.ownership = ownership;
        lifecycle = ownership;
    }

    /** Creates a distinct instance; album instances can never gain upload permission. */
    public static PtpIpClient forPictureControl(SocketConnector connector) {
        return new PtpIpClient(connector, Mode.PICTURE_CONTROL);
    }

    /**
     * Moves this app-owned open album session without another socket or OpenSession.
     * Call only after the album worker has drained and retired its queued work.
     * The old read-only handle is permanently revoked; closing it is then inert.
     */
    public synchronized PtpIpClient transferToPictureControl() throws IOException {
        requireMode(Mode.ALBUM_READ_ONLY);
        checkClosed();
        synchronized (lifecycle) {
            requireAlbumSession();
            if (ownership.owner != this || protocolUnconfirmed || commandSocket == null || eventSocket == null
                    || input == null || output == null || nextTransaction < 1 || nextTransaction > 0xffffffffL) {
                throw new IOException("相册会话已失效，无法移交给预设导入。");
            }
            return transferOwnedConnection(nextTransaction, Mode.PICTURE_CONTROL);
        }
    }

    /**
     * Continues a completed registration as a new, explicitly owned PC facade.
     * Only this transport's acknowledged write and exact target readback can
     * authorize continuation; a rejected or uncertain attempt never rearms it.
     */
    public synchronized PtpIpClient transferAfterVerifiedPictureControlImport(long nextTransactionId)
            throws IOException {
        requireMode(Mode.PICTURE_CONTROL);
        checkClosed();
        synchronized (lifecycle) {
            requireOpen();
            if (ownership.owner != this || protocolUnconfirmed || !pictureControlWriteAttempted
                    || !pictureControlWriteAcknowledged || !pictureControlReadbackVerified
                    || pictureControlWrittenData == null || nextTransactionId < 1
                    || nextTransactionId > 0xffffffffL
                    || nextTransactionId != lastPictureControlTransaction + 1) {
                throw new IOException("本次登记尚未由同一连接完整确认并精确读回，不能延续或再次登记。");
            }
            return transferOwnedConnection(nextTransactionId, Mode.PICTURE_CONTROL);
        }
    }

    /** Moves a healthy read-only PC owner to album mode without rearming a consumed write attempt. */
    public synchronized PtpIpClient transferToAlbum(long nextTransactionId) throws IOException {
        requireMode(Mode.PICTURE_CONTROL);
        checkClosed();
        requirePictureControlConnection();
        requirePictureControlSession(nextTransactionId);
        synchronized (lifecycle) {
            requireOpen();
            if (ownership.owner != this || protocolUnconfirmed || pictureControlWriteAttempted
                    || commandSocket == null || eventSocket == null || input == null || output == null
                    || nextTransactionId != lastPictureControlTransaction + 1) {
                throw new IOException("只有健康且未尝试登记的只读会话可以交给相册。");
            }
            return transferOwnedConnection(nextTransactionId, Mode.ALBUM_READ_ONLY);
        }
    }

    /** Caller holds both this API monitor and the physical ownership lock. */
    private PtpIpClient transferOwnedConnection(long nextTransactionId, Mode nextMode) {
        PtpIpClient next = new PtpIpClient(connector, nextMode, ownership);
        next.commandSocket = commandSocket; next.eventSocket = eventSocket;
        next.input = input; next.output = output;
        next.closed = false; next.sessionOpen = true; next.started = true;
        next.nextTransaction = nextTransactionId; next.transferredSession = true;
        if (nextMode == Mode.PICTURE_CONTROL) next.lastPictureControlTransaction = nextTransactionId - 1;
        next.connectedHost = connectedHost; next.connectedPort = connectedPort;
        next.cameraIdentity = cameraIdentity;
        // The physical event reader stays unique. New registration permission
        // belongs only to the new explicit owner; the old facade is revoked.
        ownership.owner = next;
        commandSocket = null; eventSocket = null; input = null; output = null;
        cancelled = true; closed = true; sessionOpen = false; onClosed = null; onObjectAdded = null;
        return next;
    }

    /** One-time backend claim; the returned transaction continues the album sequence. */
    public synchronized long claimTransferredSession() throws IOException {
        requireMode(Mode.PICTURE_CONTROL);
        return claimTransferredOwner();
    }

    /** One-time album claim; no command is sent and reads remain blocked until it succeeds. */
    public synchronized long claimTransferredAlbumSession() throws IOException {
        requireMode(Mode.ALBUM_READ_ONLY);
        return claimTransferredOwner();
    }

    public boolean isPendingAlbumHandoff() {
        synchronized (lifecycle) {
            return mode == Mode.ALBUM_READ_ONLY && ownership.owner == this && transferredSession
                    && !handoffClaimed && !closed && sessionOpen && !protocolUnconfirmed;
        }
    }

    private long claimTransferredOwner() throws IOException {
        checkClosed();
        synchronized (lifecycle) {
            requireOpen();
            if (ownership.owner != this || !transferredSession || handoffClaimed || protocolUnconfirmed) {
                throw new IOException("无线会话移交已消费或无效，不能重复接管。");
            }
            handoffClaimed = true;
            return nextTransaction;
        }
    }

    public synchronized byte[] connect(String host, int port, byte[] guid, String friendlyName) throws IOException {
        requireMode(Mode.ALBUM_READ_ONLY);
        return connectInternal(host, port, guid, friendlyName, true);
    }

    /** Establishes both PTP/IP channels; the bounded Picture Control session owns Open/CloseSession. */
    public synchronized void connectForPictureControl(String host, int port, byte[] guid, String friendlyName)
            throws IOException {
        requireMode(Mode.PICTURE_CONTROL);
        connectInternal(host, port, guid, friendlyName, false);
    }

    private byte[] connectInternal(String host, int port, byte[] guid, String friendlyName, boolean album)
            throws IOException {
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
            connectedHost = host.trim(); connectedPort = port;
            closed = false;
        }
        try {
            checkClosed();
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
            StringBuilder camera = new StringBuilder(32);
            for (int i = 4; i < 20; i++) camera.append(String.format(java.util.Locale.ROOT, "%02x", ackData[i] & 255));
            cameraIdentity = camera.toString();
            Socket events = connector.connect(host, port, 5000);
            install(events, true);
            events.setTcpNoDelay(true); events.setSoTimeout(15000);
            ByteArrayOutputStream eventInit = new ByteArrayOutputStream(); put32(eventInit, connectionNumber);
            send(events.getOutputStream(), 3, eventInit.toByteArray());
            Header eventAck = header(events.getInputStream(), deadline(CONTROL_TIMEOUT), events);
            byte[] eventBody = body(events.getInputStream(), eventAck, 1024, deadline(CONTROL_TIMEOUT), events);
            if (eventAck.type != 4 || eventBody.length != 0) throw new IOException("相机无线事件通道未建立。");
            byte[] info = null;
            if (album) {
                info = receive(0x1001, 0, new long[0], 1024 * 1024, null, -1, null);
                long traceStart = ConnectionTrace.operationStarted(ownership.traceId, 0x1002, 0);
                try {
                    request(0x1002, 0, new long[] {1});
                    response(nextControl(deadline(CONTROL_TIMEOUT)), 0);
                    ConnectionTrace.operationFinished(ownership.traceId, 0x1002, 0, traceStart, null);
                } catch (IOException | RuntimeException error) {
                    ConnectionTrace.operationFinished(ownership.traceId, 0x1002, 0, traceStart, error);
                    throw error;
                }
                sessionOpen = true;
            }
            checkClosed();
            events.setSoTimeout(0);
            Thread eventReader = new Thread(() -> drainEvents(events), album ? "camera-album-events" : "camera-preset-events");
            eventReader.setDaemon(true); eventReader.start();
            return info;
        } catch (IOException | RuntimeException error) {
            closeWithReason("无线握手：" + failureText(error), false, ConnectionTrace.category(error));
            throw error;
        }
    }

    public boolean isOpen() { return !closed && sessionOpen; }
    public boolean isClosed() { return closed; }
    public boolean isProtocolUnconfirmed() { return protocolUnconfirmed; }
    public String closeReason() { return closeReason; }
    public String connectedHost() { return connectedHost; }
    public int connectedPort() { return connectedPort; }
    public void abortProtocol(IOException failure) { closeWithReason("协议中止：" + failureText(failure), true); }
    public void setOnClosed(Runnable listener) { onClosed = listener; }

    /** Called only for the current read-only owner; callbacks must enqueue work, never perform protocol I/O. */
    public void setOnObjectAdded(java.util.function.LongConsumer listener) { onObjectAdded = listener; }

    public String cameraIdentity() { return cameraIdentity; }

    /** Fresh album capability discovery in the already opened and claimed session. */
    public synchronized byte[] readAlbumDeviceInfo() throws IOException {
        requireMode(Mode.ALBUM_READ_ONLY);
        requireAlbumSession();
        return receive(0x1001, transaction(), new long[0], 1024 * 1024, null, -1, null);
    }

    public synchronized byte[] getData(int operation, int maxBytes, long... parameters) throws IOException {
        requireMode(Mode.ALBUM_READ_ONLY);
        requireAlbumSession();
        if (operation == 0x1009 || maxBytes < 1 || maxBytes > 8 * 1024 * 1024) {
            throw new IOException("照片须使用流式下载；查询或缩略图大小超出限制。");
        }
        validateRead(operation, parameters);
        return receive(operation, transaction(), parameters, maxBytes, null, -1, null);
    }

    /** Nikon GetLargeThumb is a bounded read of a camera-generated preview, never GetObject. */
    public synchronized byte[] getLargeThumbnail(long handle) throws IOException {
        requireMode(Mode.ALBUM_READ_ONLY);
        requireAlbumSession();
        if (handle <= 0 || handle >= 0xffffffffL) throw new IOException("照片索引无效。");
        // Keep the vendor operation out of the generic query and PC write allowlists.
        return receive(0x90c4, transaction(), new long[] {handle}, 8 * 1024 * 1024, null, -1, null);
    }

    public synchronized long readTo(int operation, OutputStream destination, long maxBytes, long expectedBytes,
                       Progress progress, long... parameters) throws IOException {
        requireMode(Mode.ALBUM_READ_ONLY);
        requireAlbumSession();
        if (operation != 0x1009 || destination == null || maxBytes < 1 || maxBytes > MAX_DOWNLOAD
                || expectedBytes < 0 || expectedBytes > maxBytes) throw new IOException("照片下载参数无效。");
        validateRead(operation, parameters);
        long[] received = new long[1];
        receive(operation, transaction(), parameters, maxBytes, destination, expectedBytes,
                (done, total) -> { received[0] = done; if (progress != null) progress.onProgress(done, total); });
        return received[0];
    }

    public synchronized byte[] getPictureControlData(int operation, long transaction, long... parameters)
            throws IOException {
        requireMode(Mode.PICTURE_CONTROL);
        requirePictureControlConnection();
        validatePictureControlRead(operation, parameters);
        validateTransaction(transaction);
        if (operation == 0x1001) {
            if (sessionOpen) requirePictureControlSession(transaction);
            else if (transaction != 0) throw new IOException("打开会话前的设备信息必须以事务 0 读取。");
        } else requirePictureControlSession(transaction);
        lastPictureControlTransaction = Math.max(lastPictureControlTransaction, transaction);
        byte[] bytes = receive(operation, transaction, parameters, 1024 * 1024, null, -1, null);
        if (operation == 0x90cc && pictureControlWriteAcknowledged
                && parameters[0] == pictureControlWrittenTarget) {
            pictureControlReadbackVerified = transaction > pictureControlWriteTransaction
                    && Arrays.equals(bytes, pictureControlWrittenData);
        }
        return bytes;
    }

    public synchronized PtpContainer pictureControlCommand(int operation, long transaction, long... parameters)
            throws IOException {
        requireMode(Mode.PICTURE_CONTROL);
        requirePictureControlConnection();
        validateTransaction(transaction);
        boolean opening = operation == 0x1002;
        if (parameters == null || (!opening && operation != 0x1003)
                || (opening && (parameters.length != 1 || parameters[0] != 1 || transaction != 0 || sessionOpen))
                || (!opening && (parameters.length != 0 || !sessionOpen || transaction == 0))) {
            throw new IOException("优化校准仅允许 OpenSession(1, 事务 0) / CloseSession()。");
        }
        long traceStart = ConnectionTrace.operationStarted(ownership.traceId, operation, transaction);
        try {
            // A verified registration proof belongs to one uninterrupted PTP
            // session. Even an explicit session-close/reopen attempt must not
            // carry it forward or reset this facade's consumed write budget.
            pictureControlWriteAcknowledged = false;
            pictureControlReadbackVerified = false;
            pictureControlWrittenData = null;
            lastPictureControlTransaction = Math.max(lastPictureControlTransaction, transaction);
            request(operation, transaction, parameters);
            PtpContainer result = response(nextControl(deadline(CONTROL_TIMEOUT)), transaction);
            sessionOpen = opening;
            ConnectionTrace.operationFinished(ownership.traceId, operation, transaction, traceStart, null);
            return result;
        } catch (PtpResponseException error) {
            ConnectionTrace.operationFinished(ownership.traceId, operation, transaction, traceStart, error); throw error;
        } catch (IOException | RuntimeException error) {
            ConnectionTrace.operationFinished(ownership.traceId, operation, transaction, traceStart, error);
            abortProtocolFailure(error); throw error;
        }
    }

    /** Sends at most one validated registration per instance, including rejected/uncertain attempts. */
    public synchronized PtpContainer sendPictureControlData(int operation, long transaction, byte[] data,
                                                           long... parameters) throws IOException {
        requireMode(Mode.PICTURE_CONTROL);
        requirePictureControlConnection();
        requirePictureControlSession(transaction);
        if (operation != 0x90cd || parameters == null || parameters.length != 2
                || parameters[0] < 201 || parameters[0] > 209 || parameters[1] != 0) {
            throw new IOException("无线登记仅允许 SetPicCtrlData(C1–C9, new=0)。");
        }
        byte[] bytes = data == null ? null : data.clone();
        PictureControlWriteValidation.validateConvertedData(bytes);
        if (pictureControlWriteAttempted) throw new IOException("本次无线连接已尝试一次登记，请先重新只读扫描。");
        pictureControlWriteAttempted = true;
        pictureControlWrittenData = bytes.clone();
        pictureControlWrittenTarget = (int) parameters[0];
        pictureControlWriteTransaction = transaction;
        lastPictureControlTransaction = Math.max(lastPictureControlTransaction, transaction);
        long traceStart = ConnectionTrace.operationStarted(ownership.traceId, operation, transaction);
        try {
            checkClosed();
            request(operation, transaction, parameters, 2);
            ByteArrayOutputStream start = new ByteArrayOutputStream(12);
            put32(start, transaction); put32(start, bytes.length); put32(start, 0);
            checkClosed();
            send(output, 9, start.toByteArray());
            // A validated dataset is at most 614 bytes. The final EndData packet carries all bytes.
            ByteArrayOutputStream end = new ByteArrayOutputStream(bytes.length + 4);
            put32(end, transaction); end.write(bytes);
            checkClosed();
            send(output, 12, end.toByteArray());
            PtpContainer result = response(nextControl(deadline(CONTROL_TIMEOUT)), transaction);
            pictureControlWriteAcknowledged = true;
            ConnectionTrace.operationFinished(ownership.traceId, operation, transaction, traceStart, null);
            return result;
        } catch (PtpResponseException error) {
            ConnectionTrace.operationFinished(ownership.traceId, operation, transaction, traceStart, error); throw error;
        } catch (IOException | RuntimeException error) {
            ConnectionTrace.operationFinished(ownership.traceId, operation, transaction, traceStart, error);
            abortProtocolFailure(error); throw error;
        }
    }

    private byte[] receive(int operation, long transaction, long[] parameters, long maxBytes,
                           OutputStream destination, long expectedBytes, Progress progress) throws IOException {
        long traceStart = ConnectionTrace.operationStarted(ownership.traceId, operation, transaction);
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
            ConnectionTrace.operationFinished(ownership.traceId, operation, transaction, traceStart, null);
            return memory == null ? null : memory.toByteArray();
        } catch (PtpResponseException error) {
            ConnectionTrace.operationFinished(ownership.traceId, operation, transaction, traceStart, error); throw error;
        } catch (IOException | RuntimeException error) {
            ConnectionTrace.operationFinished(ownership.traceId, operation, transaction, traceStart, error);
            abortProtocolFailure(error); throw error;
        }
    }

    private void request(int operation, long transaction, long[] parameters) throws IOException {
        request(operation, transaction, parameters, 1);
    }

    private void request(int operation, long transaction, long[] parameters, int dataPhase) throws IOException {
        checkClosed();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        put32(payload, dataPhase); payload.write(operation); payload.write(operation >>> 8); put32(payload, transaction);
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

    private static void validatePictureControlRead(int operation, long[] p) throws IOException {
        boolean valid = p != null && ((operation == 0x1001 || operation == 0x90ca) && p.length == 0
                || operation == 0x90cf && p.length == 1 && p[0] == 1
                || operation == 0x90cc && p.length == 2 && p[1] == 0
                        && (p[0] == 1 || p[0] >= 201 && p[0] <= 209));
        if (!valid) throw new IOException("无线优化校准仅允许设备信息、厂商属性编号、Standard 能力和 C1–C9 数据查询。");
    }

    private void requireMode(Mode expected) throws IOException {
        if (mode != expected) throw new IOException("无线相册与优化校准传输必须使用各自独立的连接实例。");
    }

    private void requirePictureControlConnection() throws IOException {
        checkClosed();
        if (transferredSession && !handoffClaimed) throw new IOException("请先消费本工具的无线会话移交。");
        if (commandSocket == null || eventSocket == null) throw new IOException("请先建立相机无线连接。");
    }

    private void requirePictureControlSession(long transaction) throws IOException {
        validateTransaction(transaction);
        if (!sessionOpen || transaction == 0) throw new IOException("无线优化校准操作需要已打开的会话和非零事务。");
    }

    private static void validateTransaction(long transaction) throws IOException {
        if (transaction < 0 || transaction > 0xffffffffL) throw new IOException("无线事务编号越界。");
    }

    private void abortProtocolFailure(Exception failure) {
        closeWithReason("命令通道：" + failureText(failure), true, ConnectionTrace.category(failure));
    }

    private Header nextHeader(long until) throws IOException {
        for (int i = 0; i < 32; i++) {
            Header h = header(input, until, commandSocket);
            if (h.type != 13) return h;
            if (h.payload != 0) throw new IOException("无线探测包长度无效。");
            send(output, 14, new byte[0]);
            ConnectionTrace.pingReplied(ownership.traceId, false);
        }
        throw new IOException("无线探测包过多。");
    }
    private byte[] nextControl(long until) throws IOException {
        Header h = nextHeader(until);
        if (h.type != 7) throw new IOException("无线完成响应类型不符。");
        return body(input, h, 26, until, commandSocket);
    }
    private static PtpContainer response(byte[] p, long transaction) throws IOException {
        if (p.length < 6 || p.length > 26 || (p.length - 6) % 4 != 0 || u32(p, 2) != transaction) throw new IOException("无线响应长度或事务不匹配。");
        int code = (p[0] & 255) | ((p[1] & 255) << 8);
        if (code != 0x2001) throw new PtpResponseException(code, transaction);
        long[] parameters = new long[(p.length - 6) / 4];
        for (int i = 0; i < parameters.length; i++) parameters[i] = u32(p, 6 + i * 4);
        return PtpContainer.response(code, transaction, parameters);
    }

    private void drainEvents(Socket socket) {
        try {
            InputStream events = socket.getInputStream();
            while (physicalConnectionOpen()) {
                Header h = header(events, Long.MAX_VALUE, socket);
                if (h.type == 13 && h.payload == 0) {
                    send(socket.getOutputStream(), 14, new byte[0]);
                    ConnectionTrace.pingReplied(ownership.traceId, true);
                }
                else if (h.type == 8 && h.payload >= 6 && h.payload <= 18 && (h.payload - 6) % 4 == 0) {
                    byte[] event = body(events, h, 18, Long.MAX_VALUE, socket);
                    int code = (event[0] & 255) | ((event[1] & 255) << 8);
                    if (code == 0x4002) {
                        if (event.length != 10) throw new IOException("新增照片事件参数无效。");
                        long handle = u32(event, 6);
                        if (handle == 0 || handle == 0xffffffffL) throw new IOException("新增照片事件索引无效。");
                        java.util.function.LongConsumer listener = null;
                        synchronized (lifecycle) {
                            PtpIpClient owner = ownership.owner;
                            if (owner != null && !owner.closed && owner.mode == Mode.ALBUM_READ_ONLY
                                    && (!owner.transferredSession || owner.handoffClaimed)) listener = owner.onObjectAdded;
                        }
                        if (listener != null) try { listener.accept(handle); } catch (RuntimeException ignored) { }
                    }
                }
                else throw new IOException("相机无线事件通道返回未知数据。");
            }
        } catch (IOException | RuntimeException error) {
            Runnable listener = null;
            synchronized (lifecycle) {
                PtpIpClient owner = ownership.owner;
                if (owner != null && !owner.closed) {
                    listener = owner.closeOwnedLocked("事件通道：" + failureText(error), true, ConnectionTrace.category(error));
                }
            }
            notifyClosed(listener);
        }
    }

    private boolean physicalConnectionOpen() {
        synchronized (lifecycle) {
            return ownership.owner != null && !ownership.owner.closed;
        }
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
    private void requireAlbumSession() throws IOException {
        requireOpen();
        if (transferredSession && !handoffClaimed) throw new IOException("相册移交会话尚未接管。");
    }
    private void checkClosed() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            closeWithReason("调用线程已取消无线操作。", true, "CANCEL");
            throw new IOException("无线操作已取消。");
        }
        if (closed) throw new IOException("无线连接已关闭或操作已取消。");
    }
    @Override public void close() {
        closeWithReason("应用释放无线连接。", false);
    }

    private void closeWithReason(String reason, boolean abort) {
        closeWithReason(reason, abort, abort ? "PROTOCOL" : "EXPLICIT");
    }

    private void closeWithReason(String reason, boolean abort, String category) {
        Runnable listener;
        synchronized (lifecycle) {
            if (ownership.owner != this) {
                cancelled = true; closed = true; sessionOpen = false;
                return;
            }
            listener = closeOwnedLocked(reason, abort, category);
        }
        notifyClosed(listener);
    }

    /** Caller holds the shared ownership lock; notifications run outside it. */
    private Runnable closeOwnedLocked(String reason, boolean abort, String category) {
        if (ownership.owner != this) return null;
        boolean changed = !closed;
        if (changed) ConnectionTrace.closed(ownership.traceId, category);
        if (closeReason.isEmpty()) closeReason = reason;
        protocolUnconfirmed |= abort;
        cancelled = true; closed = true; sessionOpen = false;
        for (Socket socket : new Socket[] {commandSocket, eventSocket}) {
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
        return changed ? onClosed : null;
    }

    private static void notifyClosed(Runnable listener) {
        if (listener != null) try { listener.run(); } catch (RuntimeException ignored) { }
    }

    private static String failureText(Exception failure) {
        if (failure == null) return "原因未提供";
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (message == null || message.isEmpty() ? "" : ": " + message);
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

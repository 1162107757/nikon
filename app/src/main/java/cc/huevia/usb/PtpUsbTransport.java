package cc.huevia.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.SystemClock;

import cc.huevia.ptp.PtpConstants;
import cc.huevia.ptp.PtpContainer;
import cc.huevia.ptp.PtpContainerReader;
import cc.huevia.ptp.PtpResponseException;
import cc.huevia.ptp.PtpValidation;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded USB transport with separate fixed transfer and read-only probe allow-lists. */
final class PtpUsbTransport implements AutoCloseable, NcpTransferSession.Operations,
        PictureControlReadSession.Operations, PictureControlImportSession.Operations {
    enum Mode { STANDARD_TRANSFER, PC_READ_ONLY, WRITE_EMPTY_PC, WRITE_CONFIRMED_PC }
    private static final int BUFFER_BYTES = 16384;
    private static final int BULK_TIMEOUT_MS = 5000;
    private static final int TRANSACTION_TIMEOUT_MS = 30000;
    private final UsbDeviceConnection connection;
    private final UsbInterface intf;
    private final UsbEndpoint in;
    private final UsbEndpoint out;
    private final List<String> commandsSent;
    private final PtpContainerReader reader;
    private final Mode mode;
    private final byte[] buffer = new byte[BUFFER_BYTES];
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean protocolUnconfirmed;
    private int offset;
    private int limit;
    private long deadline;
    private boolean pictureControlWriteAttempted;

    PtpUsbTransport(UsbDevice device, UsbManager manager, List<String> commandsSent)
            throws IOException {
        this(device, manager, commandsSent, false);
    }

    /** The read-only mode cannot send any object, property or PictureControl data. */
    PtpUsbTransport(UsbDevice device, UsbManager manager, List<String> commandsSent,
                    boolean pictureControlReadOnly) throws IOException {
        this(device, manager, commandsSent,
                pictureControlReadOnly ? Mode.PC_READ_ONLY : Mode.STANDARD_TRANSFER);
    }

    PtpUsbTransport(UsbDevice device, UsbManager manager, List<String> commandsSent,
                    Mode mode) throws IOException {
        if (mode == null) throw new IOException("必须明确选择 PTP 传输模式。");
        this.mode = mode;
        if (!UsbProbe.isNikonPtpDevice(device)) {
            throw new IOException("需要尼康 PTP 相机和双向 bulk USB 接口。");
        }
        if (manager == null || !manager.hasPermission(device)) {
            throw new IOException("尚未获得相机 USB 访问授权。");
        }
        UsbInterface selected = null;
        UsbEndpoint selectedIn = null;
        UsbEndpoint selectedOut = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() != UsbConstants.USB_CLASS_STILL_IMAGE) continue;
            UsbEndpoint bulkIn = null;
            UsbEndpoint bulkOut = null;
            for (int j = 0; j < candidate.getEndpointCount(); j++) {
                UsbEndpoint endpoint = candidate.getEndpoint(j);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) bulkIn = endpoint;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) bulkOut = endpoint;
            }
            if (bulkIn != null && bulkOut != null && bulkIn.getMaxPacketSize() > 0
                    && bulkOut.getMaxPacketSize() > 0
                    && BUFFER_BYTES % bulkIn.getMaxPacketSize() == 0
                    && BUFFER_BYTES % bulkOut.getMaxPacketSize() == 0) {
                selected = candidate;
                selectedIn = bulkIn;
                selectedOut = bulkOut;
                break;
            }
        }
        if (selected == null) throw new IOException("相机没有可用的 PTP bulk 接口。");
        intf = selected;
        in = selectedIn;
        out = selectedOut;
        this.commandsSent = commandsSent;
        connection = manager.openDevice(device);
        if (connection == null) throw new IOException("USB 相机无法打开。请检查数据线、权限和 USB 主机模式。");
        PtpContainerReader preparedReader;
        try {
            if (!connection.claimInterface(intf, false)) {
                throw new IOException("USB 接口被占用。请关闭其它相机应用并断开重连。");
            }
            preparedReader = new PtpContainerReader(this::read, UsbNcpTransfer.MAX_NCP_BYTES);
        } catch (IOException | RuntimeException failure) {
            try { connection.close(); }
            catch (RuntimeException closeError) { failure.addSuppressed(closeError); }
            throw failure;
        }
        reader = preparedReader;
    }

    @Override public byte[] getData(int operation, long transactionId, long... parameters) throws IOException {
        if (mode == Mode.PC_READ_ONLY) validatePictureControlQuery(operation, parameters);
        if (isPictureControlWriteMode()) {
            validatePictureControlImportQuery(operation, parameters, mode == Mode.WRITE_CONFIRMED_PC);
        }
        return guarded(() -> getDataInternal(operation, transactionId, parameters));
    }

    private byte[] getDataInternal(int operation, long transactionId, long... parameters) throws IOException {
        if (mode == Mode.STANDARD_TRANSFER) {
            switch (operation) {
                case 0x1001: case 0x1004: case 0x1005: case 0x1007: case 0x1008: case 0x1009:
                    break;
                default: throw new IOException("此传输层不允许该查询操作。");
            }
        }
        begin(operation, transactionId, parameters);
        PtpContainer data = next();
        if (data.type() == PtpContainer.RESPONSE) {
            requireResponse(data, transactionId);
            throw new IOException("相机返回成功响应，但没有返回所需的数据。");
        }
        byte[] bytes = PtpValidation.requireData(data, transactionId, operation);
        requireResponse(next(), transactionId);
        return bytes;
    }

    @Override public PtpContainer command(int operation, long transactionId, long... parameters) throws IOException {
        if (mode != Mode.STANDARD_TRANSFER) {
            if (parameters == null || (operation != 0x1002 && operation != 0x1003)
                    || (operation == 0x1002 && (parameters.length != 1 || parameters[0] != 1))
                    || (operation == 0x1003 && parameters.length != 0)) {
                throw new IOException("优化校准专用模式仅允许 OpenSession(1) / CloseSession()。");
            }
        }
        return guarded(() -> commandInternal(operation, transactionId, parameters));
    }

    private PtpContainer commandInternal(int operation, long transactionId, long... parameters) throws IOException {
        if (operation != 0x1002 && operation != 0x1003) {
            throw new IOException("此传输层仅允许 OpenSession / CloseSession 无数据命令。");
        }
        begin(operation, transactionId, parameters);
        PtpContainer response = next();
        requireResponse(response, transactionId);
        return response;
    }

    @Override public PtpContainer sendData(int operation, long transactionId, byte[] bytes, long... parameters)
            throws IOException {
        if (mode == Mode.PC_READ_ONLY) {
            throw new IOException("优化校准只读模式禁止所有数据上传、属性修改和删除操作。");
        }
        if (isPictureControlWriteMode()) {
            if (operation != 0x90cd || parameters == null || parameters.length != 2
                    || parameters[0] < 201 || parameters[0] > 209 || parameters[1] != 0) {
                throw new IOException("优化校准登记模式仅允许 SetPicCtrlData(C1–C9, new=0)。");
            }
            PictureControlImportSession.validateConvertedData(bytes);
            if (pictureControlWriteAttempted) throw new IOException("本次连接已尝试一次登记，禁止重复写入。");
            pictureControlWriteAttempted = true;
        }
        return guarded(() -> sendDataInternal(operation, transactionId, bytes, parameters));
    }

    private boolean isPictureControlWriteMode() {
        return mode == Mode.WRITE_EMPTY_PC || mode == Mode.WRITE_CONFIRMED_PC;
    }

    private static void validatePictureControlImportQuery(int operation, long[] parameters,
                                                         boolean allowCapability) throws IOException {
        boolean allowed = parameters != null && ((operation == 0x1001 && parameters.length == 0)
                || (allowCapability && operation == 0x90cf && parameters.length == 1 && parameters[0] == 1)
                || (operation == 0x90cc && parameters.length == 2 && parameters[0] >= 201
                && parameters[0] <= 209 && parameters[1] == 0));
        if (!allowed) throw new IOException("优化校准登记模式不允许此查询操作或参数；未发送 USB 命令。");
    }

    private static void validatePictureControlQuery(int operation, long[] parameters) throws IOException {
        if (parameters == null) throw new IOException("优化校准查询参数不能为空。");
        boolean allowed = false;
        if (operation == 0x1001 || operation == 0x90ca) {
            allowed = parameters.length == 0;
        } else if (operation == 0x90cf) {
            allowed = parameters.length == 1 && parameters[0] == 1;
        } else if (operation == 0x90cc) {
            allowed = parameters.length == 2 && parameters[1] == 0
                    && (parameters[0] == 1 || (parameters[0] >= 201 && parameters[0] <= 209));
        }
        if (!allowed) {
            throw new IOException("优化校准只读模式不允许此操作或参数；未发送 USB 命令。");
        }
    }

    private PtpContainer sendDataInternal(int operation, long transactionId, byte[] bytes, long... parameters)
            throws IOException {
        if (mode == Mode.STANDARD_TRANSFER && operation != 0x100c && operation != 0x100d) {
            throw new IOException("此传输层仅允许标准 SendObjectInfo / SendObject 数据命令。");
        }
        if (bytes == null || bytes.length > UsbNcpTransfer.MAX_NCP_BYTES) {
            throw new IOException("上传数据超出本工具的大小限制。");
        }
        begin(operation, transactionId, parameters);
        byte[] data = PtpContainer.data(operation, transactionId, bytes).toBytes();
        write(data);
        // The USB still-image transport ends a data stage with a short packet
        // or ZLP, including when header plus payload fills an exact packet count.
        if (data.length % out.getMaxPacketSize() == 0) {
            checkOpen();
            int count = connection.bulkTransfer(out, new byte[0], 0, timeout());
            if (count < 0) throw new IOException("USB 数据阶段结束包发送失败。");
        }
        PtpContainer response = next();
        requireResponse(response, transactionId);
        return response;
    }

    private void begin(int operation, long transactionId, long[] parameters) throws IOException {
        checkOpen();
        deadline = SystemClock.elapsedRealtime() + TRANSACTION_TIMEOUT_MS;
        StringBuilder command = new StringBuilder(PtpConstants.hex16(operation))
                .append(" transaction=").append(transactionId).append(" parameters=[");
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) command.append(',');
            command.append(String.format(java.util.Locale.ROOT, "0x%08X", parameters[i]));
        }
        commandsSent.add(command.append(']').toString());
        write(PtpContainer.command(operation, transactionId, parameters).toBytes());
    }

    private PtpContainer next() throws IOException {
        PtpContainer container = reader.read();
        if (container == null) throw new IOException("相机在返回完整 PTP 容器前断开。");
        return container;
    }

    private static void requireResponse(PtpContainer response, long transactionId) throws IOException {
        // Use the codec's typed response exception; the caller renders clear error text.
        PtpValidation.requireOkResponse(response, transactionId);
    }

    private <T> T guarded(Exchange<T> exchange) throws IOException {
        try {
            return exchange.run();
        } catch (PtpResponseException error) {
            // Validation verified type and matching transaction before constructing
            // this exception. A complete error response leaves a known boundary.
            throw error;
        } catch (IOException error) {
            invalidate(error);
            throw error;
        } catch (RuntimeException error) {
            IOException failure = new IOException("PTP 协议阶段未确认，USB 已关闭。", error);
            invalidate(failure);
            throw failure;
        }
    }

    private void invalidate(IOException failure) {
        protocolUnconfirmed = true;
        try { close(); }
        catch (RuntimeException closeError) { failure.addSuppressed(closeError); }
    }

    @FunctionalInterface private interface Exchange<T> { T run() throws IOException; }

    private void write(byte[] bytes) throws IOException {
        int position = 0;
        while (position < bytes.length) {
            checkOpen();
            int requested = Math.min(BUFFER_BYTES, bytes.length - position);
            int count = connection.bulkTransfer(out, bytes, position, requested, timeout());
            // A partial failed write must stop immediately: re-sending could finish
            // an unconfirmed command or upload and conceal the actual result.
            if (count != requested) {
                checkOpen();
                if (mode == Mode.PC_READ_ONLY) {
                    throw new IOException("USB 查询发送未完整确认（" + count + "/" + requested
                            + " 字节）。只读验证已停止，未请求写入预设。");
                }
                if (isPictureControlWriteMode()) {
                    throw new IOException("USB 优化校准传输未完整确认（" + count + "/" + requested
                            + " 字节）。目标槽可能已改变；未自动重试，请先重新只读扫描。");
                }
                throw new IOException("USB 写入未完整确认（" + count + "/" + requested
                        + " 字节）。已停止；相机上可能已留下部分文件。");
            }
            position += count;
        }
    }

    private int read(byte[] destination, int destinationOffset, int length) throws IOException {
        if (length == 0) return 0;
        checkOpen();
        while (offset == limit) {
            int count = connection.bulkTransfer(in, buffer, buffer.length, timeout());
            if (count < 0) {
                checkOpen();
                throw new IOException("USB 读取超时或失败。请检查相机电源、忙碌状态和数据线。");
            }
            offset = 0;
            limit = count;
            if (count == 0) { checkOpen(); timeout(); }
        }
        int count = Math.min(length, limit - offset);
        System.arraycopy(buffer, offset, destination, destinationOffset, count);
        offset += count;
        return count;
    }

    private int timeout() throws IOException {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new IOException("PTP 事务超时；已停止传输。");
        return (int) Math.min(BULK_TIMEOUT_MS, Math.max(1, remaining));
    }

    private void checkOpen() throws IOException {
        if (closed.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("USB 连接已关闭或传输已取消。");
        }
    }

    boolean isClosed() { return closed.get(); }
    boolean isProtocolUnconfirmed() { return protocolUnconfirmed; }
    void abortProtocol(IOException failure) { invalidate(failure); }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { connection.releaseInterface(intf); }
        finally { connection.close(); }
    }
}

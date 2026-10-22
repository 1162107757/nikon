package cc.huevia.usb;

import cc.huevia.picturecontrol.PictureControlCapability;
import cc.huevia.picturecontrol.PictureControlData;
import cc.huevia.ptp.PtpContainer;
import cc.huevia.ptp.PtpDeviceInfo;
import cc.huevia.ptp.PtpProtocolException;
import cc.huevia.ptp.PtpResponseException;
import cc.huevia.ptp.PtpValidation;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.Objects;

/** One bounded photo-menu snapshot; its operations interface cannot upload data. */
public final class PictureControlReadSession {
    public interface Operations {
        byte[] getData(int operation, long transactionId, long... parameters) throws IOException;
        PtpContainer command(int operation, long transactionId, long... parameters) throws IOException;
    }

    private final Operations operations;
    private final UsbPictureControlProbe.Result result;
    private long nextTransactionId = 1;
    private boolean started;
    private boolean sessionOwned;
    private boolean protocolAbortRequired;
    private boolean snapshotComplete;
    private boolean retainedSession;

    public PictureControlReadSession(Operations operations, UsbPictureControlProbe.Result result) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.result = Objects.requireNonNull(result, "result");
    }

    /** Only the owning wireless adapter may continue an already opened session. */
    PictureControlReadSession(Operations operations, UsbPictureControlProbe.Result result,
                              long nextTransactionId) {
        this(operations, result);
        if (nextTransactionId < 1 || nextTransactionId > 0xffffffffL) {
            throw new IllegalArgumentException("无线会话事务编号无效。");
        }
        this.nextTransactionId = nextTransactionId;
        retainedSession = true;
        sessionOwned = true;
        result.sessionOpened = true;
    }

    /** Package-only owner handback after the wireless adapter and transport verified registration. */
    static PictureControlReadSession fromVerifiedImportSnapshot(Operations operations,
            UsbPictureControlProbe.Result result, long nextTransactionId) throws IOException {
        if (!result.completed || !result.capabilityParsed || result.items.size() != 10) {
            throw new IOException("登记后的只读持有状态不完整。");
        }
        boolean[] seen = new boolean[10];
        for (UsbPictureControlProbe.ItemRecord item : result.items) {
            if (item.itemCode != 1 && (item.itemCode < 201 || item.itemCode > 209)) {
                throw new IOException("登记后的只读槽位快照包含不允许的项目。");
            }
            int index = item.itemCode == 1 ? 0 : item.itemCode - 200;
            if (index < 0 || index >= seen.length || seen[index] || !item.parsed) {
                throw new IOException("登记后的只读槽位快照不完整。");
            }
            seen[index] = true;
            PictureControlData.parse(item.rawBytes());
        }
        PictureControlReadSession session = new PictureControlReadSession(operations, result, nextTransactionId);
        session.snapshotComplete = true;
        return session;
    }

    public void run() throws IOException {
        if (started) throw new IllegalStateException("优化校准读取会话不可重复使用。");
        started = true;
        try {
            runDetailed();
        } catch (PtpResponseException error) {
            result.failurePhase = result.phase;
            if (error.transactionId() != result.lastTransactionId) {
                protocolAbortRequired = true;
                result.protocolPhaseUnconfirmed = true;
                throw new PtpProtocolException("优化校准错误响应的事务编号不匹配。", error);
            }
            result.failedOperation = result.lastOperation;
            result.failedTransactionId = error.transactionId();
            result.failedParameters = result.lastParameters.clone();
            result.failedItemCode = result.lastItemCode;
            result.error = error.getMessage();
            throw error;
        } catch (IOException error) {
            failLocal(error);
            throw error;
        } catch (RuntimeException error) {
            IOException failure = new IOException("优化校准响应格式或协议阶段未确认。", error);
            failLocal(failure);
            throw failure;
        }
    }

    private void failLocal(IOException error) {
        result.failurePhase = result.phase;
        result.error = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (result.phase.equals("PtpOperation")) {
            protocolAbortRequired = true;
            result.protocolPhaseUnconfirmed = true;
        }
    }

    private void runDetailed() throws IOException {
        byte[] deviceBytes = query(0x1001, retainedSession ? nextTransactionId++ : 0);
        result.phase = "DeviceInfoParse";
        PtpDeviceInfo device = PtpDeviceInfo.parseRedacted(deviceBytes);
        result.manufacturer = device.manufacturer();
        result.model = device.model();
        result.firmware = device.deviceVersion();
        result.operations = device.operationsSupported();
        result.deviceInfoRead = true;
        result.phase = "CameraModelCheck";
        if (!isZ6II(device.model())) {
            throw new IOException("本次专用只读验证仅支持尼康 Z6II；未发送优化校准专用命令。");
        }
        result.phase = "PictureControlCapabilityCheck";
        for (int required : new int[] {0x1002, 0x1003, 0x90cc, 0x90cf}) {
            if (!device.supportsOperation(required)) {
                throw new IOException("相机未声明支持所需操作 " + operationName(required)
                        + "；已停止，未猜测其它命令。");
            }
        }

        if (!retainedSession) {
            recordStep(0x1002, 0, 1);
            PtpValidation.requireOkResponse(operations.command(0x1002, 0, 1), 0);
            recordSuccess();
            sessionOwned = true;
            result.sessionOpened = true;
        }

        if (device.supportsOperation(0x90ca)) {
            byte[] properties = query(0x90ca, nextTransactionId++);
            result.phase = "VendorPropertiesParse";
            result.vendorProperties = parseVendorProperties(properties);
            result.vendorPropertiesRead = true;
        } else {
            result.diagnostics.add("相机未声明 GetVendorPropCodes，跳过此可选属性列表查询。");
        }

        byte[] capability = query(0x90cf, nextTransactionId++, 1);
        result.standardCapabilityRawBytes = capability.clone();
        result.standardCapabilitySha256 = UsbPictureControlProbe.sha256(capability);
        result.capabilityRead = true;
        result.phase = "PictureControlCapabilityParse";
        PictureControlCapability.parse(capability);
        result.capabilityParsed = true;

        readItem(1);
        for (int item = 201; item <= 209; item++) readItem(item);
        result.phase = "ReadComplete";
        snapshotComplete = true;
        result.diagnostics.add("已读取照片菜单 Standard 和 C1–C9；未切换相机模式、未选择预设、未修改任何优化校准。");
        result.diagnostics.add("读取成功只验证专用读取路径；NCP 转换与直接写入仍未验证。");
    }

    private void readItem(int itemCode) throws IOException {
        byte[] bytes = query(0x90cc, nextTransactionId++, itemCode, 0);
        result.phase = "PictureControlDataParse";
        UsbPictureControlProbe.ItemRecord item = new UsbPictureControlProbe.ItemRecord(itemCode, bytes);
        // Keep the received bytes even if the semantic dataset parser rejects them.
        result.items.add(item);
        PictureControlData data = PictureControlData.parse(bytes);
        item.basePictureControl = data.basePictureControl();
        item.registrationName = data.registrationName();
        item.customCurveEnabled = data.customCurveEnabled();
        item.empty = itemCode >= 201 && item.basePictureControl == 0;
        item.parsed = true;
    }

    private byte[] query(int operation, long transactionId, long... parameters) throws IOException {
        recordStep(operation, transactionId, parameters);
        byte[] data = operations.getData(operation, transactionId, parameters);
        recordSuccess();
        return data;
    }

    private void recordStep(int operation, long transactionId, long... parameters) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("优化校准只读验证已取消。");
        }
        result.phase = "PtpOperation";
        result.lastOperation = operationName(operation);
        result.lastTransactionId = transactionId;
        result.lastParameters = parameters.clone();
        result.lastItemCode = -1;
        if ((operation == 0x90cc || operation == 0x90cf) && parameters.length > 0) {
            result.lastItemCode = (int) parameters[0];
        }
    }

    private void recordSuccess() {
        result.lastPtpOperation = result.lastOperation;
        result.lastPtpTransactionId = result.lastTransactionId;
    }

    public boolean ownsSession() { return sessionOwned; }
    public boolean requiresProtocolAbort() { return protocolAbortRequired; }

    /** A bounded read in the session this reader opened; never reconnects or uploads. */
    void keepAlive() throws IOException {
        if (!snapshotComplete || !sessionOwned || protocolAbortRequired) {
            throw new IOException("待确认的无线会话已失效，请重新读取空槽。");
        }
        try {
            byte[] bytes = query(0x90cc, nextTransactionId++, 1, 0);
            result.phase = "PictureControlDataParse";
            PictureControlData.parse(bytes);
            result.phase = "ReadComplete";
        } catch (PtpResponseException error) {
            result.failurePhase = result.phase;
            if (error.transactionId() != result.lastTransactionId) {
                protocolAbortRequired = true;
                result.protocolPhaseUnconfirmed = true;
                throw new PtpProtocolException("无线保活响应的事务编号不匹配。", error);
            }
            result.failedOperation = result.lastOperation;
            result.failedTransactionId = error.transactionId();
            result.failedParameters = result.lastParameters.clone();
            result.failedItemCode = result.lastItemCode;
            throw error;
        } catch (IOException error) {
            failLocal(error);
            throw error;
        } catch (RuntimeException error) {
            IOException failure = new IOException("无线保活响应格式未确认。", error);
            failLocal(failure);
            throw failure;
        }
    }

    /** Transfers only the session opened by this reader, preserving its transaction sequence. */
    long transferOwnedSession() throws IOException {
        long next = ownedNextTransaction();
        sessionOwned = false;
        return next;
    }

    /** Validates the next transaction without surrendering ownership before a transport handoff. */
    long ownedNextTransaction() throws IOException {
        if (!snapshotComplete || !sessionOwned || protocolAbortRequired
                || nextTransactionId < 1 || nextTransactionId > 0xffffffffL) {
            throw new IOException("待确认的无线会话已失效，请重新读取空槽。");
        }
        return nextTransactionId;
    }

    /** Cleanup has its own context and never overwrites the failing read's context. */
    public void closeSession() throws IOException {
        if (!sessionOwned) return;
        long transactionId = nextTransactionId++;
        result.cleanupOperation = operationName(0x1003);
        result.cleanupTransactionId = transactionId;
        PtpValidation.requireOkResponse(operations.command(0x1003, transactionId), transactionId);
        sessionOwned = false;
        result.sessionClosed = true;
    }

    private static int[] parseVendorProperties(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 4) throw new PtpProtocolException("厂商属性数组缺少计数。");
        long count = (bytes[0] & 255L) | ((bytes[1] & 255L) << 8)
                | ((bytes[2] & 255L) << 16) | ((bytes[3] & 255L) << 24);
        if (count > 65536 || 4L + count * 2L != bytes.length) {
            throw new PtpProtocolException("厂商属性数组的数量、长度或上限不符。");
        }
        int[] properties = new int[(int) count];
        for (int i = 0; i < properties.length; i++) {
            properties[i] = (bytes[4 + i * 2] & 255) | ((bytes[5 + i * 2] & 255) << 8);
        }
        return properties;
    }

    private static boolean isZ6II(String model) {
        if (model == null) return false;
        String normalized = model.trim().toUpperCase(Locale.ROOT)
                .replaceFirst("^NIKON\\s+", "").replaceAll("[\\s_-]+", "");
        return normalized.equals("Z6II") || normalized.equals("Z62");
    }

    static String operationName(int operation) {
        switch (operation) {
            case 0x1001: return "GetDeviceInfo (0x1001)";
            case 0x1002: return "OpenSession (0x1002)";
            case 0x1003: return "CloseSession (0x1003)";
            case 0x90ca: return "GetVendorPropCodes (0x90CA)";
            case 0x90cc: return "GetPicCtrlData (0x90CC)";
            case 0x90cf: return "GetPicCtrlCapability (0x90CF)";
            default: throw new IllegalArgumentException("不允许此优化校准读取操作。");
        }
    }
}

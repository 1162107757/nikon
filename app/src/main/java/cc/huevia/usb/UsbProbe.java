package cc.huevia.usb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Looper;
import android.os.SystemClock;

import cc.huevia.ptp.PtpContainer;
import cc.huevia.ptp.PtpContainerReader;
import cc.huevia.ptp.PtpDeviceInfo;
import cc.huevia.ptp.PtpResponseException;
import cc.huevia.ptp.PtpStorageIds;
import cc.huevia.ptp.PtpStorageInfo;
import cc.huevia.ptp.PtpValidation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standard PTP diagnostic probe. All I/O must run on a worker thread.
 * This class sends no vendor operation, object upload or property write.
 * A closed instance cannot be reused; construct a new instance for a retry.
 */
public final class UsbProbe implements AutoCloseable {
    public static final int NIKON_VENDOR_ID = 0x04b0;
    private static final int GET_DEVICE_INFO = 0x1001;
    private static final int OPEN_SESSION = 0x1002;
    private static final int CLOSE_SESSION = 0x1003;
    private static final int GET_STORAGE_IDS = 0x1004;
    private static final int GET_STORAGE_INFO = 0x1005;
    private static final int GET_OBJECT_HANDLES = 0x1007;
    private static final int GET_OBJECT_INFO = 0x1008;
    private static final int RESPONSE_OK = 0x2001;
    private static final int RESPONSE_STORE_NOT_AVAILABLE = 0x2013;
    private static final int RESPONSE_DEVICE_BUSY = 0x2019;
    private static final int RESPONSE_SESSION_ALREADY_OPEN = 0x201e;
    private static final int CONTAINER_RESPONSE = 3;
    private static final int TRANSFER_TIMEOUT_MS = 5000;
    private static final int TRANSACTION_TIMEOUT_MS = 12000;
    // Keep reads packet-aligned and below the pre-Android-P transfer limit.
    private static final int USB_READ_BYTES = 16384;
    private static final int MAX_DATA_BYTES = 1024 * 1024;
    private static final int MAX_STORAGES = 32;

    private final Object lifecycleLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean closed;
    private volatile Transport active;
    private volatile int activeDeviceId = -1;

    /** Only Nikon still-image interfaces with both bulk directions qualify. */
    public static boolean isNikonPtpDevice(UsbDevice device) {
        return device != null && device.getVendorId() == NIKON_VENDOR_ID
                && selectInterface(device) != null;
    }

    /** Does not request USB permission or access device serial numbers. */
    public static List<UsbDevice> findNikonDevices(UsbManager manager) {
        if (manager == null) return Collections.emptyList();
        List<UsbDevice> devices = new ArrayList<>();
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (isNikonPtpDevice(device)) devices.add(device);
        }
        Collections.sort(devices, (left, right) ->
                left.getDeviceName().compareTo(right.getDeviceName()));
        return devices;
    }

    /** Matches a detach broadcast to the current probe without reading serial numbers. */
    public boolean isProbingDevice(UsbDevice device) {
        return device != null && running.get() && device.getDeviceId() == activeDeviceId;
    }

    /**
     * Permission must already have been granted by UsbManager. Results describe
     * advertised operations, not a verified capability to import NCP files.
     */
    public Result probe(UsbDevice device, UsbManager manager) throws Exception {
        return probe(device, manager, false);
    }

    /** Optional directory inspection uses only standard object-list and metadata queries. */
    public Result probe(UsbDevice device, UsbManager manager, boolean inspectDirectories) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("USB 探测必须在后台线程运行。");
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("同一探测实例正在运行。");
        }
        activeDeviceId = device == null ? -1 : device.getDeviceId();
        Result result = new Result();
        result.directoryInspectionRequested = inspectDirectories;
        result.directoryInspectionStatus = inspectDirectories ? "not_started" : "not_requested";
        Transport transport = null;
        boolean sessionOwned = false;
        // Shared by the ordinary probe, survey adapter and cleanup, including failed attempts.
        final long[] nextTransactionId = {1};
        ProbeException failure = null;
        try {
            checkCanceled();
            if (device == null || manager == null) {
                throw new ProbeException("没有选择 USB 相机。", null, result);
            }
            InterfaceEndpoints endpoints = selectInterface(device);
            if (device.getVendorId() != NIKON_VENDOR_ID || endpoints == null) {
                throw new ProbeException("设备不是可用的尼康 PTP 相机（需要 class 6 和双向 bulk 端点）。",
                        null, result);
            }
            if (!manager.hasPermission(device)) {
                throw new ProbeException("尚未获得此相机的 USB 访问授权。", null, result);
            }
            result.vendorId = device.getVendorId();
            result.productId = device.getProductId();
            result.interfaceId = endpoints.intf.getId();
            result.bulkInAddress = endpoints.in.getAddress();
            result.bulkOutAddress = endpoints.out.getAddress();
            UsbDeviceConnection connection = manager.openDevice(device);
            if (connection == null) {
                throw new ProbeException("无法打开 USB 相机。请检查授权、数据线和手机 USB 主机模式。",
                        null, result);
            }
            transport = new Transport(connection, endpoints, result);
            synchronized (lifecycleLock) {
                if (closed) {
                    transport.close();
                    throw new ProbeException("探测已取消或相机已断开。", null, result);
                }
                active = transport;
            }
            if (!connection.claimInterface(endpoints.intf, false)) {
                throw new ProbeException("无法独占相机 USB 接口。请关闭占用相机的应用后重试。",
                        null, result);
            }
            transport.claimed = true;
            result.diagnostics.add("已取得 PTP 接口；未强制断开其它驱动。");

            byte[] deviceInfoPayload = transport.exchange(GET_DEVICE_INFO, 0, true);
            PtpDeviceInfo info = PtpDeviceInfo.parseRedacted(deviceInfoPayload);
            result.manufacturer = info.manufacturer();
            result.model = info.model();
            result.firmware = info.deviceVersion();
            result.standardVersion = info.standardVersion();
            result.vendorExtensionId = info.vendorExtensionId();
            result.vendorExtensionVersion = info.vendorExtensionVersion();
            result.vendorExtensionDescription = info.vendorExtensionDescription();
            result.operations = info.operationsSupported();
            result.events = info.eventsSupported();
            result.deviceProperties = info.devicePropertiesSupported();
            result.captureFormats = info.captureFormats();
            result.imageFormats = info.imageFormats();
            result.deviceInfoRead = true;

            // Transaction zero and nonzero session id are mandatory here.
            // SessionAlreadyOpen is an error: never borrow/close another session.
            transport.exchange(OPEN_SESSION, 0, false, 1);
            sessionOwned = true;
            result.sessionOpened = true;
            if (info.supportsOperation(GET_STORAGE_IDS)) {
                long[] ids = PtpStorageIds.parse(
                        transport.exchange(GET_STORAGE_IDS, nextTransactionId[0]++, true));
                result.storageIdsRead = true;
                result.storageCount = ids.length;
                for (int i = 0; i < Math.min(ids.length, MAX_STORAGES); i++) {
                    result.storages.add(new StorageResult(ids[i], null));
                }
                if (ids.length > MAX_STORAGES) {
                    result.diagnostics.add("存储数量超过探测上限，仅读取前 " + MAX_STORAGES + " 个。");
                }
                for (int i = 0; i < Math.min(ids.length, MAX_STORAGES); i++) {
                    checkCanceled();
                    if ((ids[i] & 0xffffL) == 0) {
                        result.storages.set(i, StorageResult.notMounted(ids[i]));
                        result.unavailableStorageCount++;
                        result.diagnostics.add("存储 " + hex32(ids[i])
                                + " 未挂载逻辑存储；未发送 GetStorageInfo，也未假定相机返回错误码。");
                        continue;
                    }
                    if (info.supportsOperation(GET_STORAGE_INFO)) {
                        long storageInfoTransaction = nextTransactionId[0]++;
                        try {
                            PtpStorageInfo storage = PtpStorageInfo.parse(
                                    transport.exchange(GET_STORAGE_INFO, storageInfoTransaction, true, ids[i]));
                            result.storages.set(i, new StorageResult(ids[i], storage));
                            result.availableStorageCount++;
                        } catch (ProbeException error) {
                            if (error.responseCode == null || error.responseCode != RESPONSE_STORE_NOT_AVAILABLE
                                    || error.operationCode == null || error.operationCode != GET_STORAGE_INFO
                                    || error.transactionId == null || error.transactionId != storageInfoTransaction
                                    || error.storageId == null || error.storageId != ids[i]) throw error;
                            result.storages.set(i, StorageResult.unavailable(ids[i], error.getMessage()));
                            result.unavailableStorageCount++;
                            result.diagnostics.add(error.getMessage() + "；继续检查其它存储槽位。");
                        }
                    }
                }
                result.storageAvailabilityKnown = ids.length <= MAX_STORAGES
                        && result.availableStorageCount + result.unavailableStorageCount == ids.length;
                if (result.storageAvailabilityKnown && result.availableStorageCount == 0) {
                    result.diagnostics.add("相机已连接，但未找到可用存储卡；请确认 SD 卡已在相机中就绪。");
                }
                if (!info.supportsOperation(GET_STORAGE_INFO)) {
                    result.diagnostics.add("相机没有声明支持 GetStorageInfo，未读取存储详情。");
                }
            } else {
                result.diagnostics.add("相机没有声明支持 GetStorageIDs，未查询存储卡。");
            }
            if (inspectDirectories) {
                if (!info.supportsOperation(GET_OBJECT_HANDLES) || !info.supportsOperation(GET_OBJECT_INFO)) {
                    result.directoryInspectionStatus = "not_supported";
                    result.diagnostics.add("目录诊断 notSupported：相机未声明支持所需的 GetObjectHandles (0x1007)"
                            + " 和 GetObjectInfo (0x1008)，未猜测或发送替代命令。");
                } else {
                    List<StorageResult> targets = directorySurveyTargets(result.storages);
                    result.directoryInspectionStatus = targets.isEmpty() ? "no_available_storage" : "running";
                    final Transport surveyTransport = transport;
                    for (StorageResult target : targets) {
                        checkCanceled();
                        final long surveyStorageId = target.id;
                        PtpDirectorySurvey survey = new PtpDirectorySurvey((operation, parameters) -> {
                            long transactionId = nextTransactionId[0]++;
                            surveyTransport.directorySurveyStorageId = surveyStorageId;
                            try {
                                return surveyTransport.exchange(operation, transactionId, true, parameters);
                            } catch (ProbeException error) {
                                if (error.responseCode != null && error.operationCode != null
                                        && error.operationCode == operation && error.transactionId != null
                                        && error.transactionId == transactionId) {
                                    PtpResponseException response = new PtpResponseException(error.responseCode, transactionId);
                                    response.initCause(error);
                                    throw response;
                                }
                                throw error;
                            }
                        }, surveyStorageId);
                        // Store the same mutable result before scanning so failures retain evidence.
                        result.directorySurveys.add(survey.result());
                        try {
                            survey.scan();
                        } catch (PtpResponseException error) {
                            result.directoryInspectionStatus = "failed";
                            throw error;
                        } catch (IOException error) {
                            result.directoryInspectionStatus = "failed";
                            surveyTransport.invalidate(error);
                            throw error;
                        } catch (RuntimeException error) {
                            IOException failureError = new IOException("目录诊断数据格式或协议阶段未确认，USB 已关闭。", error);
                            result.directoryInspectionStatus = "failed";
                            surveyTransport.invalidate(failureError);
                            throw failureError;
                        } finally {
                            surveyTransport.directorySurveyStorageId = null;
                        }
                    }
                    if (!targets.isEmpty()) {
                        boolean allCompleted = true;
                        for (PtpDirectorySurvey.Result surveyResult : result.directorySurveys) {
                            if (!surveyResult.completed) allCompleted = false;
                            for (PtpDirectorySurvey.QueryResult query : surveyResult.queries) {
                                if (!query.complete) allCompleted = false;
                            }
                        }
                        result.directoryInspectionStatus = allCompleted ? "completed" : "partial";
                    }
                }
            }
            result.diagnostics.add("仅完成标准 PTP 查询；NCP 写入与机内导入能力仍未验证。");
            if (info.supportsStandardObjectSend()) {
                result.diagnostics.add("相机声明支持标准对象上传；这不能证明能够写入或载入 NCP。");
            }
            result.completed = true;
        } catch (Exception error) {
            if (error instanceof ProbeException) {
                failure = (ProbeException) error;
            } else if (error instanceof PtpResponseException) {
                if (error.getCause() instanceof ProbeException) {
                    failure = (ProbeException) error.getCause();
                } else {
                    failure = new ProbeException(error.getMessage(), ((PtpResponseException) error).responseCode(),
                            result, error);
                }
            } else {
                String reason = error.getMessage();
                failure = new ProbeException("PTP 探测失败：" +
                        (reason == null ? error.getClass().getSimpleName() : reason), null, result, error);
            }
        } finally {
            boolean preservePrimaryStep = failure != null;
            String primaryOperation = result.lastOperation;
            long primaryTransactionId = result.lastTransactionId;
            String primaryStorageId = result.lastStorageId;
            long[] primaryParameters = result.lastParameters.clone();
            if (transport != null) {
                if (sessionOwned && !closed && !transport.isClosed()) {
                    try {
                        transport.exchange(CLOSE_SESSION, nextTransactionId[0]++, false);
                        result.sessionClosed = true;
                        result.diagnostics.add("已关闭本工具创建的 PTP 会话。");
                    } catch (Exception cleanupError) {
                        result.diagnostics.add("关闭 PTP 会话未获确认：" + cleanupError.getMessage());
                        if (failure == null) {
                            result.completed = false;
                            failure = new ProbeException("查询完成，但关闭 PTP 会话未获确认。请断开数据线后重试。",
                                    null, result, cleanupError);
                        }
                    }
                } else if (sessionOwned) {
                    result.diagnostics.add(transport.protocolUnconfirmed
                            ? "协议阶段未确认，USB 已关闭，未发送 CloseSession。"
                            : "连接已取消或断开，无法确认 PTP 会话关闭。");
                } else if (transport.protocolUnconfirmed) {
                    result.diagnostics.add("协议阶段未确认，USB 已关闭，未发送 CloseSession。");
                }
                result.protocolPhaseUnconfirmed = transport.protocolUnconfirmed;
                if (transport.protocolUnconfirmed) {
                    result.diagnostics.add("协议阶段未确认，USB 已关闭；未发送任何后续 PTP 命令。");
                }
                transport.close();
            }
            if (preservePrimaryStep) {
                result.lastOperation = primaryOperation;
                result.lastTransactionId = primaryTransactionId;
                result.lastStorageId = primaryStorageId;
                result.lastParameters = primaryParameters;
            }
            synchronized (lifecycleLock) {
                active = null;
                activeDeviceId = -1;
                running.set(false);
            }
        }
        if (failure != null) {
            result.error = failure.getMessage();
            throw failure;
        }
        return result;
    }

    /** Cancels in-flight transfers and closes the USB descriptor, including on detach. */
    @Override public void close() {
        Transport transport;
        synchronized (lifecycleLock) {
            closed = true;
            transport = active;
        }
        if (transport != null) transport.close();
    }

    private void checkCanceled() throws IOException {
        if (closed || Thread.currentThread().isInterrupted()) {
            throw new IOException("探测已取消或相机已断开。");
        }
    }

    private static List<StorageResult> directorySurveyTargets(List<StorageResult> storages) {
        List<StorageResult> targets = new ArrayList<>();
        // Prefer a positively described SD card, then other available logical media.
        for (int pass = 0; pass < 2 && targets.size() < 2; pass++) {
            for (StorageResult storage : storages) {
                if (targets.size() >= 2) break;
                if (storage.info == null || storage.id == 0 || storage.id == 0xffffffffL
                        || (storage.id & 0xffffL) == 0) continue;
                boolean isSd = storage.info.description().toUpperCase(Locale.ROOT)
                        .matches(".*\\bSD(?:HC|XC)?\\b.*");
                if ((pass == 0) != isSd) continue;
                boolean seen = false;
                for (StorageResult existing : targets) if (existing.id == storage.id) seen = true;
                if (!seen) targets.add(storage);
            }
        }
        return targets;
    }

    private static InterfaceEndpoints selectInterface(UsbDevice device) {
        if (device == null || device.getVendorId() != NIKON_VENDOR_ID) return null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_STILL_IMAGE) continue;
            UsbEndpoint in = null;
            UsbEndpoint out = null;
            for (int j = 0; j < intf.getEndpointCount(); j++) {
                UsbEndpoint endpoint = intf.getEndpoint(j);
                if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) in = endpoint;
                if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) out = endpoint;
            }
            if (in != null && out != null && in.getMaxPacketSize() > 0
                    && in.getMaxPacketSize() <= USB_READ_BYTES) {
                return new InterfaceEndpoints(intf, in, out);
            }
        }
        return null;
    }

    private static final class InterfaceEndpoints {
        final UsbInterface intf;
        final UsbEndpoint in;
        final UsbEndpoint out;
        InterfaceEndpoints(UsbInterface intf, UsbEndpoint in, UsbEndpoint out) {
            this.intf = intf;
            this.in = in;
            this.out = out;
        }
    }

    private final class Transport implements AutoCloseable {
        final UsbDeviceConnection connection;
        final InterfaceEndpoints endpoints;
        final Result result;
        final byte[] readBuffer = new byte[USB_READ_BYTES];
        final PtpContainerReader reader;
        final AtomicBoolean disconnected = new AtomicBoolean(false);
        volatile boolean protocolUnconfirmed;
        volatile boolean claimed;
        int readOffset;
        int readLimit;
        long deadline;
        Long directorySurveyStorageId;

        Transport(UsbDeviceConnection connection, InterfaceEndpoints endpoints, Result result) {
            this.connection = connection;
            this.endpoints = endpoints;
            this.result = result;
            this.reader = new PtpContainerReader(this::read, MAX_DATA_BYTES);
        }

        byte[] exchange(int operation, long transactionId, boolean expectsData, long... params)
                throws IOException {
            result.lastOperation = operationName(operation) + " (" + hex16(operation) + ")";
            result.lastTransactionId = transactionId;
            // Keep the nullable context boxed. Mixing a primitive long and a
            // nullable Long in a conditional expression unboxes the null branch.
            Long storageContext = null;
            if ((operation == GET_STORAGE_INFO || operation == GET_OBJECT_HANDLES) && params.length > 0) {
                storageContext = Long.valueOf(params[0]);
            } else if (operation == GET_OBJECT_INFO) {
                storageContext = directorySurveyStorageId;
            }
            result.lastStorageId = storageContext == null ? "" : hex32(storageContext);
            result.lastParameters = params.clone();
            try {
                return exchangeInternal(operation, transactionId, expectsData, params);
            } catch (IOException error) {
                boolean confirmedErrorResponse = error instanceof ProbeException
                        && ((ProbeException) error).responseCode != null;
                if (!confirmedErrorResponse) invalidate(error);
                Integer responseCode = confirmedErrorResponse ? ((ProbeException) error).responseCode : null;
                String step = result.lastOperation + ", transaction=" + transactionId
                        + parameterDescription(operation, params)
                        + (result.lastStorageId.isEmpty() ? "" : ", storageId=" + result.lastStorageId);
                throw new ProbeException(error.getMessage() + "\n步骤：" + step, responseCode, result,
                        error, operation, transactionId, storageContext);
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

        private byte[] exchangeInternal(int operation, long transactionId, boolean expectsData, long... params)
                throws IOException {
            checkCanceled();
            String name = operationName(operation); // Validate the allow-list before any I/O.
            deadline = SystemClock.elapsedRealtime() + TRANSACTION_TIMEOUT_MS;
            byte[] command = PtpContainer.command(operation, transactionId, params).toBytes();
            write(command);
            result.commandsSent.add(name + " (" + hex16(operation) + "), transaction=" + transactionId
                    + parameterDescription(operation, params)
                    + (result.lastStorageId.isEmpty() ? "" : ", storageId=" + result.lastStorageId));
            PtpContainer first = reader.read();
            if (first == null) throw new IOException("相机在返回 PTP 容器前断开。");
            if (first.type() == CONTAINER_RESPONSE) {
                requireResponse(first, transactionId);
                if (expectsData) throw new IOException(operationName(operation) + " 没有返回数据。");
                return new byte[0];
            }
            if (!expectsData) {
                throw new IOException(operationName(operation) + " 返回了意外的数据容器。");
            }
            byte[] data = PtpValidation.requireData(first, transactionId, operation);
            PtpContainer response = reader.read();
            if (response == null) throw new IOException("相机在返回 PTP 响应前断开。");
            requireResponse(response, transactionId);
            return data;
        }

        private void requireResponse(PtpContainer response, long transactionId) throws IOException {
            if (response.type() == CONTAINER_RESPONSE && response.transactionId() == transactionId
                    && response.code() != RESPONSE_OK) {
                String reason;
                if (response.code() == RESPONSE_SESSION_ALREADY_OPEN) {
                    reason = "相机已有 PTP 会话（SessionAlreadyOpen）。请关闭其它相机应用并断开重连；本工具没有接管或关闭该会话。";
                } else if (response.code() == RESPONSE_DEVICE_BUSY) {
                    reason = "相机忙（DeviceBusy）。请结束拍摄、录像或其它应用连接后重试。";
                } else if (response.code() == RESPONSE_STORE_NOT_AVAILABLE) {
                    reason = "相机报告存储介质不可用（0x2013 / StoreNotAvailable）。"
                            + "存储卡可能尚未就绪，请结合查询步骤和诊断报告判断。";
                } else {
                    reason = "相机返回 PTP 错误 " + hex16(response.code()) + "。";
                }
                throw new ProbeException(reason, response.code(), result);
            }
            PtpValidation.requireOkResponse(response, transactionId);
        }

        private void write(byte[] data) throws IOException {
            int offset = 0;
            while (offset < data.length) {
                checkOpen();
                int length = Math.min(USB_READ_BYTES, data.length - offset);
                int count = connection.bulkTransfer(endpoints.out, data, offset, length, timeout());
                if (count != length) {
                    checkOpen();
                    throw new IOException("USB 发送未完整确认（" + count + "/" + length
                            + " 字节）。请检查数据线、相机电源和 USB 连接模式。");
                }
                offset += count;
            }
        }

        private int read(byte[] destination, int offset, int length) throws IOException {
            if (length == 0) return 0;
            checkOpen();
            while (readOffset == readLimit) {
                // Always receive a complete USB packet, then serve codec-sized reads.
                int count = connection.bulkTransfer(endpoints.in, readBuffer, readBuffer.length, timeout());
                if (count < 0) {
                    checkOpen();
                    throw new IOException("USB 读取超时或失败。请检查相机是否忙碌、断电或数据线已拔出。");
                }
                readOffset = 0;
                readLimit = count;
                if (count == 0) {
                    // A zero-length packet can terminate a packet-aligned data stage.
                    // The shared transaction deadline also bounds repeated ZLPs.
                    checkOpen();
                    timeout();
                }
            }
            int count = Math.min(length, readLimit - readOffset);
            System.arraycopy(readBuffer, readOffset, destination, offset, count);
            readOffset += count;
            return count;
        }

        private int timeout() throws IOException {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) throw new IOException("PTP 事务超过 " +
                    (TRANSACTION_TIMEOUT_MS / 1000) + " 秒，探测已停止。");
            return (int) Math.min(TRANSFER_TIMEOUT_MS, Math.max(1, remaining));
        }

        private void checkOpen() throws IOException {
            checkCanceled();
            if (disconnected.get()) throw new IOException("USB 连接已关闭。");
        }

        boolean isClosed() { return disconnected.get(); }

        @Override public void close() {
            if (!disconnected.compareAndSet(false, true)) return;
            try {
                if (claimed) connection.releaseInterface(endpoints.intf);
            } finally {
                connection.close();
            }
        }
    }

    public static final class ProbeException extends IOException {
        public final Integer responseCode;
        public final Result partialResult;
        public final Integer operationCode;
        public final Long transactionId;
        public final Long storageId;
        ProbeException(String message, Integer responseCode, Result result) {
            this(message, responseCode, result, null);
        }
        ProbeException(String message, Integer responseCode, Result result, Throwable cause) {
            this(message, responseCode, result, cause, null, null, null);
        }
        ProbeException(String message, Integer responseCode, Result result, Throwable cause,
                       Integer operationCode, Long transactionId, Long storageId) {
            super(message, cause);
            this.responseCode = responseCode;
            this.partialResult = result;
            this.operationCode = operationCode;
            this.transactionId = transactionId;
            this.storageId = storageId;
        }
    }

    public static final class StorageResult {
        public final long id;
        public final PtpStorageInfo info;
        public final String status;
        public final Integer responseCode;
        public final String error;
        StorageResult(long id, PtpStorageInfo info) {
            this(id, info, info == null ? "not_checked" : "available", null, "");
        }
        private StorageResult(long id, PtpStorageInfo info, String status, Integer responseCode, String error) {
            this.id = id;
            this.info = info;
            this.status = status;
            this.responseCode = responseCode;
            this.error = error;
        }
        static StorageResult unavailable(long id, String error) {
            return new StorageResult(id, null, "unavailable", RESPONSE_STORE_NOT_AVAILABLE, error);
        }
        static StorageResult notMounted(long id) {
            return new StorageResult(id, null, "not_mounted", null,
                    "未挂载逻辑存储，未发送 GetStorageInfo。");
        }
        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject().put("id", hex32(id))
                    .put("status", status)
                    .put("responseCode", responseCode == null ? JSONObject.NULL : hex16(responseCode))
                    .put("error", error);
            if (info == null) return json.put("detailsRead", false);
            return json.put("detailsRead", true)
                    .put("storageType", hex16(info.storageType()))
                    .put("fileSystemType", hex16(info.fileSystemType()))
                    .put("accessCapability", hex16(info.accessCapability()))
                    .put("maxCapacityBytes", info.maxCapacity().toString())
                    .put("freeSpaceBytes", info.freeSpaceBytes().toString())
                    .put("freeSpaceImages", info.freeSpaceImages())
                    .put("description", info.description())
                    .put("volumeLabel", info.volumeLabel());
        }
    }

    /** No USB or PTP serial number is copied into this report. */
    public static final class Result {
        public final String timestamp = timestamp();
        public String manufacturer = "";
        public String model = "";
        public String firmware = "";
        public int vendorId;
        public int productId;
        public int interfaceId;
        public int bulkInAddress;
        public int bulkOutAddress;
        public int standardVersion;
        public long vendorExtensionId;
        public int vendorExtensionVersion;
        public String vendorExtensionDescription = "";
        public int[] operations = new int[0];
        public int[] events = new int[0];
        public int[] deviceProperties = new int[0];
        public int[] captureFormats = new int[0];
        public int[] imageFormats = new int[0];
        public boolean deviceInfoRead;
        public boolean sessionOpened;
        public boolean sessionClosed;
        public boolean storageIdsRead;
        public boolean completed;
        public boolean protocolPhaseUnconfirmed;
        public int storageCount;
        public int availableStorageCount;
        public int unavailableStorageCount;
        public boolean storageAvailabilityKnown;
        public String lastOperation = "";
        public long lastTransactionId = -1;
        public String lastStorageId = "";
        public String error = "";
        public long[] lastParameters = new long[0];
        public boolean directoryInspectionRequested;
        public String directoryInspectionStatus = "not_requested";
        public final List<PtpDirectorySurvey.Result> directorySurveys = new ArrayList<>();
        public final List<StorageResult> storages = new ArrayList<>();
        public final List<String> diagnostics = new ArrayList<>();
        public final List<String> commandsSent = new ArrayList<>();

        public JSONObject toJson() throws JSONException {
            JSONArray storageJson = new JSONArray();
            for (StorageResult storage : storages) storageJson.put(storage.toJson());
            JSONArray vendorOperations = new JSONArray();
            JSONArray surveys = new JSONArray();
            for (PtpDirectorySurvey.Result survey : directorySurveys) surveys.put(directorySurveyJson(survey));
            for (int operation : operations) {
                if (operation >= 0x9000) vendorOperations.put(hex16(operation));
            }
            return new JSONObject()
                    .put("reportVersion", 1)
                    .put("timestampUtc", timestamp)
                    .put("completed", completed)
                    .put("protocolPhaseUnconfirmed", protocolPhaseUnconfirmed)
                    .put("probeMode", directoryInspectionRequested
                            ? "standard_ptp_directory_queries_only" : "standard_ptp_queries_only")
                    .put("readOnly", true)
                    .put("ncpDirectImportCapability", "unverified")
                    .put("ncpWriteAttempted", false)
                    .put("presetWriteAttempted", false)
                    .put("serialNumberCollected", false)
                    .put("manufacturer", manufacturer)
                    .put("model", model)
                    .put("firmware", firmware)
                    .put("usbVendorId", hex16(vendorId))
                    .put("usbProductId", hex16(productId))
                    .put("usbInterfaceId", interfaceId)
                    .put("bulkInAddress", bulkInAddress)
                    .put("bulkOutAddress", bulkOutAddress)
                    .put("standardVersion", standardVersion)
                    .put("vendorExtensionId", hex32(vendorExtensionId))
                    .put("vendorExtensionVersion", vendorExtensionVersion)
                    .put("vendorExtensionDescription", vendorExtensionDescription)
                    .put("operationsAdvertised", hexArray(operations))
                    .put("vendorOperationsAdvertised", vendorOperations)
                    .put("eventsAdvertised", hexArray(events))
                    .put("devicePropertiesAdvertised", hexArray(deviceProperties))
                    .put("captureFormatsAdvertised", hexArray(captureFormats))
                    .put("imageFormatsAdvertised", hexArray(imageFormats))
                    .put("deviceInfoRead", deviceInfoRead)
                    .put("sessionOpenedByProbe", sessionOpened)
                    .put("sessionCloseConfirmed", sessionClosed)
                    .put("storageIdsRead", storageIdsRead)
                    .put("storageCount", storageCount)
                    .put("availableStorageCount", availableStorageCount)
                    .put("unavailableStorageCount", unavailableStorageCount)
                    .put("storageAvailabilityKnown", storageAvailabilityKnown)
                    .put("lastOperation", lastOperation)
                    .put("lastTransactionId", lastTransactionId)
                    .put("lastStorageId", lastStorageId)
                    .put("lastParameters", hex32Array(lastParameters))
                    .put("directoryInspectionRequested", directoryInspectionRequested)
                    .put("directoryInspectionStatus", directoryInspectionStatus)
                    .put("directorySurveys", surveys)
                    .put("error", error)
                    .put("storages", storageJson)
                    .put("commandsSent", new JSONArray(commandsSent))
                    .put("diagnostics", new JSONArray(diagnostics));
        }

        public String toJsonString() {
            try { return toJson().toString(2); }
            catch (JSONException error) { throw new IllegalStateException("无法生成探测报告。", error); }
        }

        public String toDisplayText() {
            StringBuilder text = new StringBuilder();
            text.append("相机：").append(model.isEmpty() ? "尚未读取" : model)
                    .append("\n制造商：").append(manufacturer)
                    .append("\n固件：").append(firmware)
                    .append("\nUSB：").append(hex16(vendorId)).append(" / ").append(hex16(productId))
                    .append("\nPTP 查询：").append(completed ? "完成" : "未完成")
                    .append("\n声明支持的操作：").append(operations.length)
                    .append("\n存储槽位：").append(storageIdsRead ? storageCount : "尚未读取")
                    .append("，可用：").append(availableStorageCount)
                    .append("，不可用：").append(unavailableStorageCount)
                    .append("\nNCP 数据线直接导入：尚未验证\n");
            if (directoryInspectionRequested) {
                text.append("\n目录诊断（只读，未请求写入）：")
                        .append(directoryInspectionStatusText(directoryInspectionStatus)).append('\n');
            }
            if (deviceInfoRead && storageAvailabilityKnown && availableStorageCount == 0) {
                text.append("\n相机已连接，但未找到可用存储卡。\n");
            }
            if (!error.isEmpty()) text.append("\n结果：").append(error).append('\n');
            for (StorageResult storage : storages) {
                text.append("\n存储 ").append(hex32(storage.id));
                if (storage.info != null) {
                    text.append("：").append(storage.info.description())
                            .append("\nStorageType=").append(hex16(storage.info.storageType()))
                            .append("（").append(StorageAssessment.storageTypeName(storage.info.storageType())).append("）")
                            .append("，FileSystemType=").append(hex16(storage.info.fileSystemType()))
                            .append("（").append(StorageAssessment.fileSystemTypeName(storage.info.fileSystemType())).append("）")
                            .append("，AccessCapability=").append(hex16(storage.info.accessCapability()))
                            .append("（").append(StorageAssessment.accessCapabilityName(storage.info.accessCapability())).append("）")
                            .append("\n容量 / 可用（字节）：")
                            .append(storage.info.maxCapacity()).append(" / ")
                            .append(storage.info.freeSpaceBytes());
                } else if (storage.status.equals("unavailable")) {
                    text.append("：存储介质不可用（0x2013 / StoreNotAvailable）");
                } else if (storage.status.equals("not_mounted")) {
                    text.append("：未挂载逻辑存储（未发送查询）");
                } else {
                    text.append("：尚未检测");
                }
                text.append('\n');
            }
            for (PtpDirectorySurvey.Result survey : directorySurveys) {
                text.append("\n").append(survey.toDisplayText()).append('\n');
            }
            for (String diagnostic : diagnostics) text.append("\n• ").append(diagnostic);
            return text.toString();
        }

        @Override public String toString() { return toDisplayText(); }
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static JSONArray hexArray(int[] values) {
        JSONArray array = new JSONArray();
        for (int value : values) array.put(hex16(value));
        return array;
    }

    private static JSONArray hex32Array(long[] values) {
        JSONArray array = new JSONArray();
        for (long value : values) array.put(hex32(value));
        return array;
    }

    private static JSONObject directorySurveyJson(PtpDirectorySurvey.Result survey) throws JSONException {
        JSONArray queries = new JSONArray();
        for (PtpDirectorySurvey.QueryResult query : survey.queries) {
            JSONArray objects = new JSONArray();
            for (PtpDirectorySurvey.ObjectRecord record : query.objects) objects.put(directoryObjectJson(record));
            queries.put(new JSONObject()
                    .put("label", query.label)
                    .put("status", query.status)
                    .put("formatCode", hex16(query.formatCode))
                    .put("parentHandle", hex32(query.parentHandle))
                    .put("handlesRead", query.handlesRead)
                    .put("handleCount", query.handleCount)
                    .put("metadataReadCount", query.metadataReadCount)
                    .put("cacheHitCount", query.cacheHitCount)
                    .put("complete", query.complete)
                    .put("truncated", query.truncated)
                    .put("selectorHonored", query.selectorHonored)
                    .put("responseCode", query.responseCode < 0 ? JSONObject.NULL : hex16(query.responseCode))
                    .put("failedOperation", query.failedOperation == 0 ? JSONObject.NULL : hex16(query.failedOperation))
                    .put("failedHandle", query.failedOperation == GET_OBJECT_INFO
                            ? hex32(query.failedHandle) : JSONObject.NULL)
                    .put("error", query.error)
                    .put("objects", objects)
                    .put("issues", new JSONArray(query.issues)));
        }
        JSONArray additionalRoots = new JSONArray();
        for (PtpDirectorySurvey.ObjectRecord record : survey.additionalRootDirectories) {
            additionalRoots.put(directoryObjectJson(record));
        }
        return new JSONObject()
                .put("storageId", hex32(survey.storageId))
                .put("readOnly", true)
                .put("completed", survey.completed)
                .put("metadataRequests", survey.metadataRequests)
                .put("deadlineExceeded", survey.deadlineExceeded)
                .put("interrupted", survey.interrupted)
                .put("elapsedMillis", survey.elapsedMillis)
                .put("nikonRootVisible", survey.nikonRootVisible)
                .put("customPcPathVisible", survey.customPcPathVisible)
                .put("error", survey.error)
                .put("queries", queries)
                .put("additionalRootDirectories", additionalRoots);
    }

    private static JSONObject directoryObjectJson(PtpDirectorySurvey.ObjectRecord record) throws JSONException {
        return new JSONObject()
                .put("handle", hex32(record.handle))
                .put("storageId", hex32(record.storageId))
                .put("parentObject", hex32(record.parentObject))
                .put("name", record.name)
                .put("objectFormat", hex16(record.objectFormat))
                .put("associationType", hex16(record.associationType));
    }

    private static String directoryInspectionStatusText(String status) {
        switch (status) {
            case "completed": return "所有查询完整返回";
            case "partial": return "部分视图未完整取得，请查看各查询状态";
            case "failed": return "已停止，保留已取得的部分结果";
            case "not_supported": return "notSupported：相机未声明所需查询操作";
            case "no_available_storage": return "没有可调查的已就绪存储卡";
            case "running": return "调查进行中";
            case "not_started": return "尚未开始";
            default: return "未请求";
        }
    }

    private static String parameterDescription(int operation, long[] params) {
        StringBuilder description = new StringBuilder(", parameters=[");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) description.append(',');
            description.append(hex32(params[i]));
        }
        description.append(']');
        if (operation == GET_OBJECT_HANDLES) {
            if (params.length > 1) description.append(", formatCode=").append(hex16((int) params[1]));
            if (params.length > 2) description.append(", parentHandle=").append(hex32(params[2]));
        } else if (operation == GET_OBJECT_INFO && params.length > 0) {
            description.append(", objectHandle=").append(hex32(params[0]));
        }
        return description.toString();
    }

    private static String hex16(int value) { return String.format(Locale.US, "0x%04X", value & 0xffff); }
    private static String hex32(long value) { return String.format(Locale.US, "0x%08X", value & 0xffffffffL); }
    private static String operationName(int operation) {
        switch (operation) {
            case GET_DEVICE_INFO: return "GetDeviceInfo";
            case OPEN_SESSION: return "OpenSession";
            case CLOSE_SESSION: return "CloseSession";
            case GET_STORAGE_IDS: return "GetStorageIDs";
            case GET_STORAGE_INFO: return "GetStorageInfo";
            case GET_OBJECT_HANDLES: return "GetObjectHandles";
            case GET_OBJECT_INFO: return "GetObjectInfo";
            default: throw new IllegalArgumentException("不允许发送此 PTP 操作。");
        }
    }
}

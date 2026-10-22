package cc.huevia.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Looper;

import cc.huevia.ncp.NcpFile;
import cc.huevia.ptp.PtpConstants;
import cc.huevia.ptp.PtpResponseException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Experimental standard-PTP file transfer for Nikon Z6II only. Uploading bytes
 * to a card does not establish that the camera can load this file as a preset.
 * The caller must authorize the visible experimental transfer before calling.
 * No vendor command, deletion, overwrite or camera-property write is sent.
 */
public final class UsbNcpTransfer implements AutoCloseable {
    public static final int MAX_NCP_BYTES = 1024 * 1024;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile PtpUsbTransport active;
    private volatile int activeDeviceId = -1;
    private volatile boolean closed;

    /** The selected file is copied before I/O; run this method on a worker thread. */
    public Result transfer(UsbDevice device, UsbManager manager, byte[] ncp) throws Exception {
        return transfer(device, manager, ncp, false);
    }

    /** The explicit experiment only attempts an existing directory; it grants no write capability. */
    public Result transfer(UsbDevice device, UsbManager manager, byte[] ncp,
                           boolean existingDirectoryExperiment) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("USB 传输必须在后台线程运行。");
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("此 USB 传输实例正在运行。");
        }
        activeDeviceId = device == null ? -1 : device.getDeviceId();
        Result result = new Result();
        result.existingDirectoryExperimentRequested = existingDirectoryExperiment;
        result.transferMode = existingDirectoryExperiment ? "existing_directory_experiment" : "normal";
        PtpUsbTransport transport = null;
        NcpTransferSession session = null;
        TransferException failure = null;
        try {
            result.phase = "NcpValidation";
            if (closed || Thread.currentThread().isInterrupted()) {
                throw new IOException("传输已取消。");
            }
            if (ncp == null || ncp.length == 0 || ncp.length > MAX_NCP_BYTES) {
                throw new IOException("请选择非空且不超过 1 MiB 的 NCP 文件。");
            }
            byte[] file = ncp.clone();
            result.ncpVersion = NcpFile.validate(file);
            result.fileSize = file.length;
            result.sha256 = sha256(file);
            result.phase = "UsbConnection";
            transport = new PtpUsbTransport(device, manager, result.commandsSent);
            synchronized (lifecycleLock) {
                if (closed) {
                    transport.close();
                    throw new IOException("传输已取消或数据线已断开。");
                }
                active = transport;
            }
            session = new NcpTransferSession(transport, result, existingDirectoryExperiment);
            session.run(file);
        } catch (Exception error) {
            failure = toException(error, result);
        } finally {
            // Preserve the operation that actually failed, even if cleanup sends
            // a successful CloseSession or returns a second error of its own.
            boolean preservePrimaryStep = failure != null;
            String primaryOperation = result.lastOperation;
            long primaryTransactionId = result.lastTransactionId;
            String primaryStorageId = result.lastStorageId;
            if (transport != null) {
                if (session != null && session.ownsSession()) {
                    if (!closed && !transport.isClosed()) {
                        try {
                            session.closeSession();
                        } catch (Exception cleanupError) {
                            result.diagnostics.add("关闭 PTP 会话未获确认：" + readableError(cleanupError));
                            if (failure == null) {
                                result.failurePhase = "SessionCleanup";
                                result.lastOperation = "CloseSession (0x1003)";
                                result.lastTransactionId = session.closeSessionTransactionId();
                                result.lastStorageId = "";
                                Integer cleanupResponse = cleanupError instanceof PtpResponseException
                                        ? ((PtpResponseException) cleanupError).responseCode() : null;
                                if (cleanupResponse != null) {
                                    result.failedOperation = result.lastOperation;
                                    result.failedTransactionId = result.lastTransactionId;
                                }
                                failure = new TransferException("文件已读回验证，但关闭 PTP 会话未获确认。"
                                        + "请检查相机并断开重连，避免重复上传。", cleanupResponse, result, cleanupError);
                            }
                        }
                    } else {
                        result.diagnostics.add(transport.isProtocolUnconfirmed()
                                ? "协议阶段未确认，USB 已关闭，未发送 CloseSession。相机可能仍持有未完成会话。"
                                : "USB 连接已关闭，无法确认本工具创建的 PTP 会话是否关闭。");
                    }
                }
                if (transport.isProtocolUnconfirmed() && (session == null || !session.ownsSession())) {
                    result.diagnostics.add("协议阶段未确认，USB 已关闭，未发送 CloseSession。");
                }
                result.protocolPhaseUnconfirmed = transport.isProtocolUnconfirmed();
                if (transport.isProtocolUnconfirmed()) {
                    result.diagnostics.add("协议阶段未确认，USB 已关闭；未发送任何后续 PTP 命令。");
                }
                transport.close();
            }
            if (preservePrimaryStep) {
                result.lastOperation = primaryOperation;
                result.lastTransactionId = primaryTransactionId;
                result.lastStorageId = primaryStorageId;
            }
            synchronized (lifecycleLock) {
                active = null;
                activeDeviceId = -1;
                running.set(false);
            }
        }
        result.completed = failure == null && result.verifiedTransfer;
        if (failure != null) {
            result.error = failure.getMessage();
            if (result.writeAttempted) {
                result.diagnostics.add("相机上可能保留报告所列目录或文件；本工具未回滚或删除，请检查相机存储卡。");
            }
            throw failure;
        }
        return result;
    }

    public boolean isProbingDevice(UsbDevice device) {
        return device != null && running.get() && device.getDeviceId() == activeDeviceId;
    }

    /** Use on matching USB detach and Activity teardown. A closed instance cannot be reused. */
    @Override public void close() {
        PtpUsbTransport transport;
        synchronized (lifecycleLock) { closed = true; transport = active; }
        if (transport != null) transport.close();
    }

    private static TransferException toException(Exception error, Result result) {
        Integer responseCode = error instanceof PtpResponseException
                ? ((PtpResponseException) error).responseCode() : null;
        if (result.failurePhase.isEmpty()) result.failurePhase = result.phase;
        String step;
        if (responseCode != null) {
            result.failedOperation = result.lastOperation;
            result.failedTransactionId = ((PtpResponseException) error).transactionId();
            result.failedStorageId = result.lastStorageId;
            step = "\nPTP 失败操作：" + result.failedOperation
                    + ", transaction=" + result.failedTransactionId
                    + (result.failedStorageId.isEmpty() ? "" : ", storageId=" + result.failedStorageId);
        } else {
            step = "\n失败阶段：" + result.failurePhase;
            if (!result.lastPtpOperation.isEmpty()) {
                step += "\n最后成功 PTP 操作：" + result.lastPtpOperation
                        + ", transaction=" + result.lastPtpTransactionId
                        + (result.lastPtpStorageId.isEmpty() ? "" : ", storageId=" + result.lastPtpStorageId);
            }
            if (result.failurePhase.equals("StorageEligibilityCheck")) {
                for (StorageAssessment assessment : result.storageAssessment) {
                    step += "\n" + assessment.toDisplayText();
                }
            }
        }
        return new TransferException(readableError(error) + step, responseCode, result, error);
    }

    private static String readableError(Exception error) {
        if (error instanceof PtpResponseException) {
            int response = ((PtpResponseException) error).responseCode();
            if (response == 0x201e) {
                return "相机已有 PTP 会话（SessionAlreadyOpen）。请关闭其它相机应用并断开重连；本工具未接管或关闭该会话。";
            }
            if (response == 0x2019) {
                return "相机忙（DeviceBusy），已停止。请结束拍摄、录像和其它连接后重试。";
            }
            if (response == 0x2013) {
                return "相机报告存储介质不可用（0x2013 / StoreNotAvailable），已停止。"
                        + "存储卡可能尚未就绪，请查看失败步骤和诊断报告。";
            }
            return "相机拒绝标准 PTP 操作（" + PtpConstants.hex16(response) + " / "
                    + PtpConstants.responseName(response) + "），已停止；没有改用其它协议或厂商命令。";
        }
        String message = error.getMessage();
        return message == null ? "USB 传输失败：" + error.getClass().getSimpleName() : message;
    }

    static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder(64);
            for (byte value : digest) output.append(String.format(Locale.US, "%02x", value & 255));
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("当前环境无法计算 SHA-256。", error);
        }
    }

    public static final class TransferException extends IOException {
        public final Integer responseCode;
        public final Result partialResult;
        TransferException(String message, Integer responseCode, Result result, Throwable cause) {
            super(message, cause);
            this.responseCode = responseCode;
            this.partialResult = result;
        }
    }

    public static final class Result {
        public final String timestamp = timestamp();
        public String manufacturer = "";
        public String model = "";
        public String firmware = "";
        public String ncpVersion = "";
        public String transferMode = "normal";
        public boolean existingDirectoryExperimentRequested;
        public boolean readOnlyExceptionSelected;
        public boolean readOnlyExceptionAttempted;
        public boolean existingPathVerified;
        public int selectedAccessCapability = -1;
        public long storageId;
        public String storageDescription = "";
        public String freeSpaceBytes = "";
        public long parentHandle;
        public long fileHandle;
        public String fileName = "";
        public int fileSize;
        public String sha256 = "";
        public String readbackSha256 = "";
        public boolean writeAttempted;
        public boolean fileMetadataAccepted;
        public boolean uploadStarted;
        public boolean uploadResponseOk;
        public boolean readbackVerified;
        public boolean verifiedTransfer;
        public final boolean menuImportVerified = false;
        public boolean sessionOpened;
        public boolean sessionClosed;
        public boolean completed;
        public boolean protocolPhaseUnconfirmed;
        public int storageCount;
        public int availableStorageCount;
        public int unavailableStorageCount;
        public String lastOperation = "";
        public long lastTransactionId = -1;
        public String lastStorageId = "";
        public String phase = "";
        public String failurePhase = "";
        public String lastPtpOperation = "";
        public long lastPtpTransactionId = -1;
        public String lastPtpStorageId = "";
        public String failedOperation = "";
        public long failedTransactionId = -1;
        public String failedStorageId = "";
        public String error = "";
        public final List<StorageAssessment> storageAssessment = new ArrayList<>();
        public final List<Long> unavailableStorageIds = new ArrayList<>();
        public final List<Long> unmountedStorageIds = new ArrayList<>();
        public final List<String> changes = new ArrayList<>();
        public final List<String> diagnostics = new ArrayList<>();
        public final List<String> commandsSent = new ArrayList<>();

        public JSONObject toJson() throws JSONException {
            JSONArray assessments = new JSONArray();
            for (StorageAssessment assessment : storageAssessment) {
                assessments.put(new JSONObject()
                        .put("storageId", hex(assessment.storageId))
                        .put("storageType", hex16(assessment.storageType))
                        .put("storageTypeName", StorageAssessment.storageTypeName(assessment.storageType))
                        .put("fileSystemType", hex16(assessment.fileSystemType))
                        .put("fileSystemTypeName", StorageAssessment.fileSystemTypeName(assessment.fileSystemType))
                        .put("accessCapability", hex16(assessment.accessCapability))
                        .put("accessCapabilityName", StorageAssessment.accessCapabilityName(assessment.accessCapability))
                        .put("maxCapacityBytes", assessment.maxCapacityBytes)
                        .put("freeSpaceBytes", assessment.freeSpaceBytes)
                        .put("requiredBytes", assessment.requiredBytes)
                        .put("description", assessment.description)
                        .put("eligible", assessment.eligible)
                        .put("experimentCandidate", assessment.experimentCandidate)
                        .put("rejectionReasons", new JSONArray(assessment.rejectionReasons)));
            }
            return new JSONObject()
                    .put("reportVersion", 1)
                    .put("timestampUtc", timestamp)
                    .put("mode", "experimental_standard_ptp_card_file_transfer")
                    .put("transferMode", transferMode)
                    .put("existingDirectoryExperimentRequested", existingDirectoryExperimentRequested)
                    .put("readOnlyExceptionSelected", readOnlyExceptionSelected)
                    .put("readOnlyExceptionAttempted", readOnlyExceptionAttempted)
                    .put("existingPathVerified", existingPathVerified)
                    .put("selectedAccessCapability", selectedAccessCapability < 0
                            ? JSONObject.NULL : hex16(selectedAccessCapability))
                    .put("completed", completed)
                    .put("protocolPhaseUnconfirmed", protocolPhaseUnconfirmed)
                    .put("manufacturer", manufacturer)
                    .put("model", model)
                    .put("firmware", firmware)
                    .put("ncpVersion", ncpVersion)
                    .put("storageId", hex(storageId))
                    .put("storageDescription", storageDescription)
                    .put("freeSpaceBytes", freeSpaceBytes)
                    .put("storageCount", storageCount)
                    .put("availableStorageCount", availableStorageCount)
                    .put("unavailableStorageCount", unavailableStorageCount)
                    .put("unavailableStorageIds", storageIdsJson(unavailableStorageIds))
                    .put("unmountedStorageIds", storageIdsJson(unmountedStorageIds))
                    .put("parentHandle", hex(parentHandle))
                    .put("fileHandle", hex(fileHandle))
                    .put("fileName", fileName)
                    .put("relativePath", fileName.isEmpty() ? "" : "NIKON/CUSTOMPC/" + fileName)
                    .put("fileSizeBytes", fileSize)
                    .put("sha256", sha256)
                    .put("readbackSha256", readbackSha256)
                    .put("writeAttempted", writeAttempted)
                    .put("fileMetadataAccepted", fileMetadataAccepted)
                    .put("uploadStarted", uploadStarted)
                    .put("uploadResponseOk", uploadResponseOk)
                    .put("readbackVerified", readbackVerified)
                    .put("verifiedTransfer", verifiedTransfer)
                    .put("menuImportVerified", menuImportVerified)
                    .put("presetInstalledAutomatically", false)
                    .put("serialNumberCollected", false)
                    .put("sessionOpenedByTransfer", sessionOpened)
                    .put("sessionCloseConfirmed", sessionClosed)
                    .put("error", error)
                    .put("lastOperation", lastOperation)
                    .put("lastTransactionId", lastTransactionId)
                    .put("lastStorageId", lastStorageId)
                    .put("phase", phase)
                    .put("failurePhase", failurePhase)
                    .put("lastPtpOperation", lastPtpOperation)
                    .put("lastPtpTransactionId", lastPtpTransactionId)
                    .put("lastPtpStorageId", lastPtpStorageId)
                    .put("failedOperation", failedOperation)
                    .put("failedTransactionId", failedTransactionId)
                    .put("failedStorageId", failedStorageId)
                    .put("storageAssessment", assessments)
                    .put("changes", new JSONArray(changes))
                    .put("commandsSent", new JSONArray(commandsSent))
                    .put("diagnostics", new JSONArray(diagnostics));
        }

        public String toJsonString() {
            try { return toJson().toString(2); }
            catch (JSONException error) { throw new IllegalStateException("无法生成传输报告。", error); }
        }

        public String toDisplayText() {
            StringBuilder text = new StringBuilder("实验性数据线文件传输\n");
            text.append("模式：").append(existingDirectoryExperimentRequested ? "手动现有目录实验" : "普通模式")
                    .append("\n相机：").append(model.isEmpty() ? "尚未读取" : model)
                    .append("\n固件：").append(firmware)
                    .append("\n相机声明的访问权限：").append(selectedAccessCapability < 0 ? "尚未选卡"
                            : hex16(selectedAccessCapability) + "（"
                            + StorageAssessment.accessCapabilityName(selectedAccessCapability) + "）")
                    .append("\n只读例外：").append(readOnlyExceptionAttempted ? "已发送实际上传请求"
                            : readOnlyExceptionSelected ? "已选择，尚未发送上传请求" : "未进入")
                    .append("\n现有目录验证：").append(existingDirectoryExperimentRequested
                            ? existingPathVerified ? "通过（USB 对象视图）" : "尚未通过"
                            : "普通模式未请求此实验")
                    .append("\n存储卡：").append(hex(storageId)).append(" ").append(storageDescription)
                    .append("\n存储槽位：").append(storageCount).append("，可用：")
                    .append(availableStorageCount).append("，不可用：").append(unavailableStorageCount)
                    .append("\n路径：").append(fileName.isEmpty() ? "尚未分配" : "NIKON/CUSTOMPC/" + fileName)
                    .append("\n字节内容读回验证：").append(readbackVerified ? "通过" : "尚未通过")
                    .append("\n相机菜单载入：尚未验证\n");
            if (!sha256.isEmpty()) text.append("\n原文件 SHA-256：").append(sha256).append('\n');
            if (!error.isEmpty()) text.append("\n结果：").append(error).append('\n');
            for (StorageAssessment assessment : storageAssessment) {
                if (!error.contains(assessment.toDisplayText())) {
                    text.append("\n").append(assessment.toDisplayText()).append('\n');
                }
            }
            if (readbackVerified) text.append("\n文件已传至相机存储卡，请在相机管理优化校准菜单载入。\n");
            for (String change : changes) text.append("\n• ").append(change);
            for (String diagnostic : diagnostics) {
                boolean assessmentAlreadyShown = false;
                for (StorageAssessment assessment : storageAssessment) {
                    if (diagnostic.equals(assessment.toDisplayText())) {
                        assessmentAlreadyShown = true;
                        break;
                    }
                }
                if (!assessmentAlreadyShown) text.append("\n• ").append(diagnostic);
            }
            return text.toString();
        }

        @Override public String toString() { return toDisplayText(); }
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String hex(long value) { return String.format(Locale.US, "0x%08X", value); }
    private static String hex16(int value) { return String.format(Locale.US, "0x%04X", value & 0xffff); }

    private static JSONArray storageIdsJson(List<Long> values) {
        JSONArray array = new JSONArray();
        for (long value : values) array.put(hex(value));
        return array;
    }
}

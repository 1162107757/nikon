package com.nikonpreset.usbprobe.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Looper;

import com.nikonpreset.usbprobe.ncp.NcpPictureControlConverter;
import com.nikonpreset.usbprobe.ptp.PtpConstants;
import com.nikonpreset.usbprobe.ptp.PtpResponseException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit photo-menu registration; NCP adaptation is always recomputed from the source file. */
public final class UsbPictureControlImport implements AutoCloseable {
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile PtpUsbTransport active;
    private volatile int activeDeviceId = -1;
    private volatile boolean closed;
    private volatile String cancellationCloseError = "";

    /** Call only after the user selects and confirms different source and target custom slots. */
    public Result importPreset(UsbDevice device, UsbManager manager, byte[] snapshot,
                               int sourceItem, int targetItem) throws Exception {
        return execute(device, manager, snapshot, sourceItem, targetItem, true);
    }

    // Internal registration entry point; the current UI must use source-verified copy above.
    Result importPreset(UsbDevice device, UsbManager manager, byte[] data, int targetItem) throws Exception {
        return execute(device, manager, data, -1, targetItem, false);
    }

    /** The caller confirms the adaptation preview; arbitrary preconverted bytes are not accepted here. */
    public Result importNcpPreset(UsbDevice device, UsbManager manager, byte[] originalNcp,
                                  int middleQuarter, int clarityQuarter, int targetItem) throws Exception {
        return execute(device, manager, originalNcp, -1, targetItem, false, true,
                middleQuarter, clarityQuarter);
    }

    private Result execute(UsbDevice device, UsbManager manager, byte[] bytes,
                           int sourceItem, int targetItem, boolean requireSource) throws Exception {
        return execute(device, manager, bytes, sourceItem, targetItem, requireSource, false, 0, 0);
    }

    private Result execute(UsbDevice device, UsbManager manager, byte[] bytes,
                           int sourceItem, int targetItem, boolean requireSource, boolean ncpMode,
                           int middleQuarter, int clarityQuarter) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("优化校准空槽登记必须在后台线程运行。");
        }
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("此登记实例正在运行。");
        activeDeviceId = device == null ? -1 : device.getDeviceId();
        Result result = new Result();
        result.sourceItem = sourceItem;
        result.sourceLabel = PictureControlImportSession.label(sourceItem);
        result.targetItem = targetItem;
        result.targetLabel = PictureControlImportSession.label(targetItem);
        if (ncpMode) {
            result.ncpMode = true;
            result.modePurpose = "ncp_compatibility_import";
            result.type = "ncp_compatibility_adaptation";
            result.adaptationMiddleQuarter = middleQuarter;
            result.adaptationClarityQuarter = clarityQuarter;
        }
        PtpUsbTransport transport = null;
        PictureControlImportSession session = null;
        ImportException failure = null;
        try {
            result.phase = "RequestedDataValidation";
            byte[] requestedInput = bytes;
            if (ncpMode) {
                // Hash, convert and register the same private source copy. Metadata
                // survives converter, USB, PTP and cleanup failures in this Result.
                requestedInput = bytes == null ? null : bytes.clone();
                result.sourceNcpSize = requestedInput == null ? 0 : requestedInput.length;
                if (requestedInput != null) result.sourceNcpSha256 = UsbPictureControlProbe.sha256(requestedInput);
            }
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("空槽登记已取消。");
            if (targetItem < 201 || targetItem > 209 || (requireSource
                    && (sourceItem < 201 || sourceItem > 209 || sourceItem == targetItem))) {
                throw new IOException("必须明确选择不同的 C1–C9 来源槽和目标空槽。");
            }
            if (ncpMode) {
                result.phase = "NcpCompatibilityAdaptation";
                NcpPictureControlConverter.Conversion conversion = NcpPictureControlConverter.convert(
                        requestedInput, middleQuarter, clarityQuarter);
                result.sourceNcpName = conversion.registrationName();
                result.sourceCurveSha256 = conversion.curveSha256();
                result.basePictureControl = conversion.basePictureControl();
                result.registrationName = conversion.registrationName();
                result.adaptationPreview = conversion.toDisplayText();
                result.adaptationPrepared = true;
                requestedInput = conversion.dataBytes();
            }
            result.phase = "RequestedDataValidation";
            byte[] requested = PictureControlImportSession.validateConvertedData(requestedInput).rawBytes();
            result.convertedRawBytes = requested.clone();
            result.convertedSha256 = UsbPictureControlProbe.sha256(requested);
            result.phase = "UsbConnection";
            transport = new PtpUsbTransport(device, manager, result.commandsSent, PtpUsbTransport.Mode.WRITE_EMPTY_PC);
            synchronized (lifecycleLock) {
                if (closed) throw new IOException("空槽登记已取消或相机已断开。");
                active = transport;
            }
            if (requireSource) {
                session = new PictureControlImportSession(transport, result, requested, sourceItem, targetItem);
            } else {
                session = new PictureControlImportSession(transport, result, requested, targetItem);
            }
            session.run();
        } catch (Exception error) {
            failure = toException(error, result);
        } finally {
            try {
                if (transport != null) {
                    if (session != null && session.requiresProtocolAbort() && !transport.isClosed()) {
                        transport.abortProtocol(new IOException("优化校准登记的 PTP 阶段未确认。"));
                    }
                    if (session != null && session.ownsSession()) {
                        if (!closed && !Thread.currentThread().isInterrupted() && !transport.isClosed()) {
                            try {
                                session.closeSession();
                            } catch (Exception cleanupError) {
                                result.cleanupError = readableError(cleanupError);
                                Integer code = null;
                                if (cleanupError instanceof PtpResponseException) {
                                    result.cleanupResponseCode = ((PtpResponseException) cleanupError).responseCode();
                                    code = Integer.valueOf(result.cleanupResponseCode);
                                }
                                result.diagnostics.add("CloseSession 未获确认：" + result.cleanupError);
                                if (failure == null) {
                                    result.failurePhase = "SessionCleanup";
                                    if (code != null) {
                                        result.failedOperation = result.cleanupOperation;
                                        result.failedTransactionId = result.cleanupTransactionId;
                                        result.failedParameters = new long[0];
                                        result.failedItemCode = -1;
                                    }
                                    failure = new ImportException("目标槽已读回核对，但会话关闭未获确认。"
                                            + "请先重新只读扫描，勿重复写入。", code, result, cleanupError);
                                } else failure.addSuppressed(cleanupError);
                            }
                        } else {
                            result.diagnostics.add("连接已取消或协议阶段未确认，未发送 CloseSession。");
                            if (failure == null) {
                                result.failurePhase = "SessionCleanupCancelled";
                                result.cleanupError = "取消或连接关闭后，未确认自有会话关闭。";
                                failure = new ImportException("目标槽已读回核对，但会话关闭未获确认。"
                                        + "请先重新只读扫描，勿重复写入。", null, result, null);
                            }
                        }
                    }
                    result.protocolPhaseUnconfirmed |= transport.isProtocolUnconfirmed();
                    if (result.protocolPhaseUnconfirmed) {
                        result.diagnostics.add("PTP 阶段未确认，USB 已关闭，未发送后续相机命令。");
                    }
                }
            } finally {
                try {
                    if (transport != null) {
                        try { transport.close(); }
                        catch (RuntimeException closeError) {
                            result.diagnostics.add("USB 释放异常：" + readableError(closeError));
                            if (failure == null) {
                                result.failurePhase = "UsbCleanup";
                                failure = new ImportException("USB 释放未获确认，请先重新只读扫描，勿重复写入。",
                                        null, result, closeError);
                            } else failure.addSuppressed(closeError);
                        }
                    }
                    if (!cancellationCloseError.isEmpty()) result.diagnostics.add(cancellationCloseError);
                } finally {
                    synchronized (lifecycleLock) {
                        active = null;
                        activeDeviceId = -1;
                        running.set(false);
                    }
                }
            }
        }
        result.completed = failure == null && result.readbackVerified && result.sessionClosed;
        result.ncpRegistrationVerified = result.ncpMode && result.completed && result.readbackVerified;
        if (failure != null) {
            result.error = failure.getMessage();
            throw failure;
        }
        return result;
    }

    public boolean isProbingDevice(UsbDevice device) {
        return device != null && running.get() && device.getDeviceId() == activeDeviceId;
    }
    @Override public void close() {
        PtpUsbTransport transport;
        synchronized (lifecycleLock) { closed = true; transport = active; }
        if (transport != null) {
            try { transport.close(); }
            catch (RuntimeException error) { cancellationCloseError = "取消时 USB 释放异常：" + readableError(error); }
        }
    }

    private static ImportException toException(Exception error, Result result) {
        Integer responseCode = null;
        if (error instanceof PtpResponseException) responseCode = Integer.valueOf(((PtpResponseException) error).responseCode());
        if (result.failurePhase.isEmpty()) result.failurePhase = result.phase;
        StringBuilder text = new StringBuilder(readableError(error)).append("\n失败阶段：").append(result.failurePhase);
        if (!result.lastOperation.isEmpty()) {
            text.append("\nPTP 上下文：").append(result.lastOperation).append(", transaction=")
                    .append(result.lastTransactionId).append(", parameters=")
                    .append(java.util.Arrays.toString(result.lastParameters));
            if (result.lastItemCode >= 0) text.append(", item=").append(result.lastItemCode);
        }
        if (result.writeAttempted) {
            text.append("\n目标槽可能已登记；请先重新只读扫描，勿重复写入。本工具未删除、自动还原或重试。");
        } else text.append("\n未发送优化校准登记请求。");
        return new ImportException(text.toString(), responseCode, result, error);
    }

    private static String readableError(Exception error) {
        if (error instanceof PtpResponseException) {
            int code = ((PtpResponseException) error).responseCode();
            return "相机返回 PTP 错误 " + PtpConstants.hex16(code) + " / " + PtpConstants.responseName(code) + "。";
        }
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    public static final class ImportException extends IOException {
        public final Integer responseCode;
        public final Result partialResult;
        ImportException(String message, Integer responseCode, Result result, Throwable cause) {
            super(message, cause);
            this.responseCode = responseCode;
            partialResult = result;
        }
    }

    public static final class Result {
        public final String timestamp = timestamp();
        public String modePurpose = "camera_preset_copy";
        public String type = "camera_preset_copy";
        public boolean ncpMode;
        public String sourceNcpSha256 = "";
        public int sourceNcpSize;
        /** Name stored inside the NCP; the byte-array API does not receive a filesystem name. */
        public String sourceNcpName = "";
        public String sourceCurveSha256 = "";
        public int adaptationMiddleQuarter;
        public int adaptationClarityQuarter;
        public final int adaptationContrastQuarter = 0;
        public final int adaptationBrightnessQuarter = 0;
        public boolean adaptationPrepared;
        public String adaptationPreview = "";
        public boolean ncpRegistrationVerified;
        public final boolean ncpImportVerified = false;
        public final boolean conversionEquivalentToNative = false;
        public final boolean photoEffectVerified = false;
        public String manufacturer = "";
        public String model = "";
        public String firmware = "";
        public int[] operations = new int[0];
        public int sourceItem = -1;
        public int targetItem = -1;
        public String sourceLabel = "未指定";
        public String targetLabel = "未指定";
        public int basePictureControl = -1;
        public String registrationName = "";
        // Requested wire data: a camera snapshot or the explicitly selected NCP adaptation.
        public byte[] convertedRawBytes = new byte[0];
        public String convertedSha256 = "";
        public boolean sourceVerified;
        public boolean initialTargetEmpty;
        public boolean recheckTargetEmpty;
        public boolean writeAttempted;
        public boolean writeResponseOk;
        public boolean readbackParsed;
        public boolean readbackVerified;
        public boolean normalizationObserved;
        public boolean completed;
        public boolean sessionOpened;
        public boolean sessionClosed;
        public boolean protocolPhaseUnconfirmed;
        public int differenceCount;
        public final List<Integer> differenceOffsets = new ArrayList<>();
        public final List<UsbPictureControlProbe.ItemRecord> initialSlots = new ArrayList<>();
        public UsbPictureControlProbe.ItemRecord sourceSnapshot;
        public UsbPictureControlProbe.ItemRecord recheck;
        public UsbPictureControlProbe.ItemRecord readback;
        public String phase = "NotStarted";
        public String failurePhase = "";
        public String lastOperation = "";
        public long lastTransactionId = -1;
        public long[] lastParameters = new long[0];
        public int lastItemCode = -1;
        public String failedOperation = "";
        public long failedTransactionId = -1;
        public long[] failedParameters = new long[0];
        public int failedItemCode = -1;
        public String cleanupOperation = "";
        public long cleanupTransactionId = -1;
        public int cleanupResponseCode = -1;
        public String cleanupError = "";
        public String error = "";
        public final List<String> commandsSent = new ArrayList<>();
        public final List<String> diagnostics = new ArrayList<>();

        public JSONObject toJson() throws JSONException {
            JSONArray before = new JSONArray();
            for (UsbPictureControlProbe.ItemRecord item : initialSlots) before.put(item.toJson());
            JSONArray advertised = new JSONArray();
            for (int operation : operations) advertised.put(PtpConstants.hex16(operation));
            return new JSONObject().put("reportVersion", 1).put("timestampUtc", timestamp)
                    .put("operationMode", ncpMode ? "nikon_ncp_compatibility_registration"
                            : "nikon_picture_control_copy_to_observed_empty_slot")
                    .put("modePurpose", modePurpose).put("type", type).put("ncpMode", ncpMode)
                    .put("readOnly", false).put("shootingMenu", "photo").put("completed", completed)
                    .put("serialNumberCollected", false).put("ncpConversionVerified", false)
                    .put("ncpRegistrationVerified", ncpMode && completed && readbackVerified)
                    .put("ncpImportVerified", ncpImportVerified)
                    .put("conversionEquivalentToNative", conversionEquivalentToNative)
                    .put("photoEffectVerified", photoEffectVerified)
                    .put("sourceNcpSha256", sourceNcpSha256).put("sourceNcpSize", sourceNcpSize)
                    .put("sourceNcpName", sourceNcpName).put("sourceCurveSha256", sourceCurveSha256)
                    .put("adaptationPrepared", adaptationPrepared)
                    .put("adaptationMiddleQuarter", adaptationMiddleQuarter)
                    .put("adaptationClarityQuarter", adaptationClarityQuarter)
                    .put("adaptationContrastQuarter", adaptationContrastQuarter)
                    .put("adaptationBrightnessQuarter", adaptationBrightnessQuarter)
                    .put("adaptationPreview", adaptationPreview)
                    .put("currentSelectionWriteRequested", false)
                    .put("modeChangeRequested", false).put("deletionRequested", false).put("atomicEmptyGuard", false)
                    .put("manufacturer", manufacturer).put("model", model).put("firmware", firmware)
                    .put("operationsAdvertised", advertised).put("sourceItem", sourceItem).put("sourceLabel", sourceLabel)
                    .put("targetItem", targetItem).put("targetLabel", targetLabel).put("sourceVerified", sourceVerified)
                    .put("basePictureControl", basePictureControl).put("registrationName", registrationName)
                    .put("requestedByteLength", convertedRawBytes.length).put("requestedSha256", convertedSha256)
                    .put("requestedDataSha256", convertedSha256)
                    .put("requestedRawBase64", Base64.getEncoder().encodeToString(convertedRawBytes))
                    .put("initialSlots", before)
                    .put("sourceSnapshot", sourceSnapshot == null ? JSONObject.NULL : sourceSnapshot.toJson())
                    .put("recheck", recheck == null ? JSONObject.NULL : recheck.toJson())
                    .put("readback", readback == null ? JSONObject.NULL : readback.toJson())
                    .put("initialTargetEmpty", initialTargetEmpty).put("recheckTargetEmpty", recheckTargetEmpty)
                    .put("writeAttempted", writeAttempted).put("writeResponseOk", writeResponseOk)
                    .put("readbackParsed", readbackParsed).put("readbackVerified", readbackVerified)
                    .put("normalizationObserved", normalizationObserved)
                    .put("differenceInterpretation", normalizationObserved ? "cause_unconfirmed" : "none_observed")
                    .put("differenceCount", differenceCount).put("differenceOffsets", new JSONArray(differenceOffsets))
                    .put("sessionOpenedByTool", sessionOpened).put("sessionCloseConfirmed", sessionClosed)
                    .put("protocolPhaseUnconfirmed", protocolPhaseUnconfirmed)
                    .put("phase", phase).put("failurePhase", failurePhase).put("lastOperation", lastOperation)
                    .put("lastTransactionId", lastTransactionId).put("lastParameters", parameterJson(lastParameters))
                    .put("lastItemCode", lastItemCode).put("failedOperation", failedOperation)
                    .put("failedTransactionId", failedTransactionId).put("failedParameters", parameterJson(failedParameters))
                    .put("failedItemCode", failedItemCode).put("cleanupOperation", cleanupOperation)
                    .put("cleanupTransactionId", cleanupTransactionId)
                    .put("cleanupResponseCode", cleanupResponseCode < 0 ? JSONObject.NULL : PtpConstants.hex16(cleanupResponseCode))
                    .put("cleanupError", cleanupError).put("error", error)
                    .put("commandsSent", new JSONArray(commandsSent)).put("diagnostics", new JSONArray(diagnostics));
        }

        public String toJsonString() {
            try { return toJson().toString(2); }
            catch (JSONException error) { throw new IllegalStateException("无法生成空槽登记报告。", error); }
        }

        public String toDisplayText() {
            StringBuilder text = new StringBuilder(ncpMode ? "NCP 兼容适配试导入（照片菜单）"
                    : "机内优化校准复制实验（照片菜单）")
                    .append("\n相机：").append(model.isEmpty() ? "尚未读取" : model).append(" / ").append(firmware);
            if (ncpMode) {
                text.append("\nNCP 内注册名：").append(sourceNcpName.isEmpty() ? "尚未解析" : sourceNcpName)
                        .append("，").append(sourceNcpSize).append(" 字节")
                        .append("\n源 NCP SHA-256：").append(sourceNcpSha256)
                        .append("\n中范围锐化/清晰度（1/4 单位）：").append(adaptationMiddleQuarter)
                        .append(" / ").append(adaptationClarityQuarter)
                        .append("\n对比度/亮度适配值：0 / 0；不承诺与相机原生载入等效。")
                        .append("\n目标：").append(targetLabel);
                if (!adaptationPreview.isEmpty()) text.append("\n适配预览：\n").append(adaptationPreview);
            } else {
                text.append("\n来源：").append(sourceLabel).append(" → 目标：").append(targetLabel)
                        .append("\n来源重新核对：").append(sourceVerified);
            }
            text.append("\n名称：").append(registrationName).append("，base=").append(basePictureControl)
                    .append("\n目标初查/写前复查为空：").append(initialTargetEmpty).append(" / ").append(recheckTargetEmpty)
                    .append("\n登记请求已尝试/相机已确认：").append(writeAttempted).append(" / ").append(writeResponseOk)
                    .append("\n读回字节精确一致：").append(readbackVerified)
                    .append("\n会话关闭已确认：").append(sessionClosed);
            if (normalizationObserved) text.append("\n读回存在 ").append(differenceCount)
                    .append(" 个字节差异，原因未确定；可能包含相机规范化，不能宣称精确验证。");
            if (writeAttempted && !completed) text.append("\n目标槽可能已经登记。请先重新只读扫描，勿重复写入。");
            if (!error.isEmpty()) text.append("\n结果：").append(error);
            if (!cleanupError.isEmpty()) text.append("\n清理：").append(cleanupError);
            text.append("\n未请求删除、自动还原、设置当前选择或修改相机模式。")
                    .append("\n连续空槽检查不是相机原子保护；操作期间请勿从相机或其它应用修改槽位。");
            if (ncpMode) {
                text.append("\n兼容数据登记并精确读回核对：").append(ncpMode && completed && readbackVerified)
                        .append("\n原生载入等效性与实际拍摄效果：均未验证。\n");
            } else text.append("\n本实验复制机内数据，未验证 NCP 转换、导入或所有拍摄效果。\n");
            for (String diagnostic : diagnostics) text.append("\n• ").append(diagnostic);
            return text.toString();
        }
        @Override public String toString() { return toDisplayText(); }
    }

    private static JSONArray parameterJson(long[] values) {
        JSONArray array = new JSONArray();
        for (long value : values) array.put(String.format(Locale.ROOT, "0x%08X", value));
        return array;
    }
    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}

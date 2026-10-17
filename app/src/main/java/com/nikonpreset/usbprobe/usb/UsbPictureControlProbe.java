package com.nikonpreset.usbprobe.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Looper;

import com.nikonpreset.usbprobe.ptp.PtpConstants;
import com.nikonpreset.usbprobe.ptp.PtpResponseException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/** Nikon Z6II photo-menu PictureControl snapshot. This class has no data-upload API. */
public final class UsbPictureControlProbe implements AutoCloseable {
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile PtpUsbTransport active;
    private volatile int activeDeviceId = -1;
    private volatile boolean closed;
    private volatile String cancellationCloseError = "";

    /** USB permission must already be granted; run on a worker thread without selecting an NCP. */
    public Result probe(UsbDevice device, UsbManager manager) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("优化校准只读验证必须在后台线程运行。");
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("此优化校准只读验证实例正在运行。");
        }
        activeDeviceId = device == null ? -1 : device.getDeviceId();
        Result result = new Result();
        PtpUsbTransport transport = null;
        PictureControlReadSession session = null;
        ProbeException failure = null;
        try {
            result.phase = "UsbConnection";
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("只读验证已取消。");
            transport = new PtpUsbTransport(device, manager, result.commandsSent, true);
            synchronized (lifecycleLock) {
                if (closed) throw new IOException("只读验证已取消或相机已断开。");
                active = transport;
            }
            session = new PictureControlReadSession(transport, result);
            session.run();
        } catch (Exception error) {
            failure = toException(error, result);
        } finally {
            // USB cleanup is independent of lifecycle reset, even if a native close throws.
            try {
                if (transport != null) {
                    if (session != null && session.requiresProtocolAbort() && !transport.isClosed()) {
                        IOException protocolError = new IOException("优化校准数据解析或协议阶段未确认。");
                        transport.abortProtocol(protocolError);
                    }
                    if (session != null && session.ownsSession()) {
                        if (!closed && !Thread.currentThread().isInterrupted() && !transport.isClosed()) {
                            try {
                                session.closeSession();
                            } catch (Exception cleanupError) {
                                result.cleanupError = readableError(cleanupError);
                                Integer responseCode = null;
                                if (cleanupError instanceof PtpResponseException) {
                                    int code = ((PtpResponseException) cleanupError).responseCode();
                                    result.cleanupResponseCode = code;
                                    responseCode = Integer.valueOf(code);
                                }
                                result.diagnostics.add("CloseSession 未获确认：" + result.cleanupError);
                                if (failure == null) {
                                    result.failurePhase = "SessionCleanup";
                                    if (responseCode != null) {
                                        result.failedOperation = result.cleanupOperation;
                                        result.failedTransactionId = result.cleanupTransactionId;
                                        result.failedParameters = new long[0];
                                        result.failedItemCode = -1;
                                    }
                                    failure = new ProbeException("读取已结束，但本工具创建的会话关闭未获确认。"
                                            + result.cleanupError, responseCode, result, cleanupError);
                                } else {
                                    failure.addSuppressed(cleanupError);
                                }
                            }
                        } else {
                            result.diagnostics.add("连接已取消、关闭或协议阶段未确认，未发送 CloseSession。");
                        }
                    }
                    result.protocolPhaseUnconfirmed |= transport.isProtocolUnconfirmed();
                    if (result.protocolPhaseUnconfirmed) {
                        result.diagnostics.add("协议阶段未确认，USB 已关闭；未发送任何后续 PTP 命令。");
                    }
                }
            } finally {
                try {
                    if (transport != null) {
                        try {
                            transport.close();
                        } catch (RuntimeException closeError) {
                            result.diagnostics.add("USB 连接释放异常：" + readableError(closeError));
                            if (failure == null) {
                                result.failurePhase = "UsbCleanup";
                                failure = new ProbeException("读取已结束，但 USB 连接释放未获确认。",
                                        null, result, closeError);
                            } else {
                                failure.addSuppressed(closeError);
                            }
                        }
                    }
                    if (!cancellationCloseError.isEmpty()) {
                        result.diagnostics.add("取消连接时释放异常：" + cancellationCloseError);
                    }
                } finally {
                    synchronized (lifecycleLock) {
                        active = null;
                        activeDeviceId = -1;
                        running.set(false);
                    }
                }
            }
        }
        result.completed = failure == null && result.capabilityParsed && result.items.size() == 10;
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
            catch (RuntimeException error) { cancellationCloseError = readableError(error); }
        }
    }

    private static ProbeException toException(Exception error, Result result) {
        Integer responseCode = null;
        if (error instanceof PtpResponseException) {
            responseCode = Integer.valueOf(((PtpResponseException) error).responseCode());
        }
        if (result.failurePhase.isEmpty()) result.failurePhase = result.phase;
        StringBuilder message = new StringBuilder(readableError(error))
                .append("\n失败阶段：").append(result.failurePhase);
        if (!result.lastOperation.isEmpty()) {
            message.append("\nPTP 上下文：").append(result.lastOperation)
                    .append(", transaction=").append(result.lastTransactionId)
                    .append(", parameters=").append(parametersText(result.lastParameters));
            if (result.lastItemCode >= 0) message.append(", item=").append(result.lastItemCode);
        }
        return new ProbeException(message.toString(), responseCode, result, error);
    }

    private static String readableError(Exception error) {
        if (error instanceof PtpResponseException) {
            int code = ((PtpResponseException) error).responseCode();
            if (code == 0x201e) return "相机已有 PTP 会话；本工具未接管或关闭该会话。";
            if (code == 0x2019) return "相机忙（DeviceBusy），只读验证已停止。";
            return "相机返回 PTP 错误 " + hex16(code) + " / " + PtpConstants.responseName(code) + "。";
        }
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message;
    }

    static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder(64);
            for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("无法计算优化校准快照的 SHA-256。", error);
        }
    }

    public static final class ProbeException extends IOException {
        public final Integer responseCode;
        public final Result partialResult;
        ProbeException(String message, Integer responseCode, Result result, Throwable cause) {
            super(message, cause);
            this.responseCode = responseCode;
            partialResult = result;
        }
    }

    public static final class ItemRecord {
        public final int itemCode;
        public final String label;
        public final int byteLength;
        public final String sha256;
        private final byte[] raw;
        public boolean parsed;
        public int basePictureControl = -1;
        public String registrationName = "";
        public boolean customCurveEnabled;
        public boolean empty;

        ItemRecord(int itemCode, byte[] bytes) throws IOException {
            this.itemCode = itemCode;
            label = itemCode == 1 ? "Standard" : "C" + (itemCode - 200);
            raw = bytes.clone();
            byteLength = raw.length;
            sha256 = UsbPictureControlProbe.sha256(raw);
        }

        public byte[] rawBytes() { return raw.clone(); }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("itemCode", itemCode).put("label", label)
                    .put("parsed", parsed).put("basePictureControl", basePictureControl)
                    .put("registrationName", registrationName).put("customCurveEnabled", customCurveEnabled)
                    .put("empty", empty).put("byteLength", byteLength).put("sha256", sha256)
                    .put("rawBase64", Base64.getEncoder().encodeToString(raw));
        }
    }

    /** Camera serial numbers and NCP files are not read by this snapshot. */
    public static final class Result {
        public String transport = "usb";
        public String wirelessHost = "";
        public int wirelessPort;
        public final String timestamp = timestamp();
        public String manufacturer = "";
        public String model = "";
        public String firmware = "";
        public int[] operations = new int[0];
        public int[] vendorProperties = new int[0];
        public boolean deviceInfoRead;
        public boolean sessionOpened;
        public boolean sessionClosed;
        public boolean sessionRetained;
        public boolean vendorPropertiesRead;
        public boolean capabilityRead;
        public boolean capabilityParsed;
        public boolean completed;
        public boolean protocolPhaseUnconfirmed;
        public byte[] standardCapabilityRawBytes = new byte[0];
        public String standardCapabilitySha256 = "";
        public String phase = "NotStarted";
        public String failurePhase = "";
        public String lastOperation = "";
        public long lastTransactionId = -1;
        public long[] lastParameters = new long[0];
        public int lastItemCode = -1;
        public String lastPtpOperation = "";
        public long lastPtpTransactionId = -1;
        public String failedOperation = "";
        public long failedTransactionId = -1;
        public long[] failedParameters = new long[0];
        public int failedItemCode = -1;
        public String cleanupOperation = "";
        public long cleanupTransactionId = -1;
        public int cleanupResponseCode = -1;
        public String cleanupError = "";
        public String error = "";
        public final List<ItemRecord> items = new ArrayList<>();
        public final List<String> commandsSent = new ArrayList<>();
        public final List<String> diagnostics = new ArrayList<>();

        public JSONObject toJson() throws JSONException {
            JSONArray records = new JSONArray();
            for (ItemRecord item : items) records.put(item.toJson());
            JSONObject capability = new JSONObject().put("itemCode", 1)
                    .put("read", capabilityRead).put("parsed", capabilityParsed)
                    .put("byteLength", standardCapabilityRawBytes.length)
                    .put("sha256", standardCapabilitySha256)
                    .put("rawBase64", Base64.getEncoder().encodeToString(standardCapabilityRawBytes));
            return new JSONObject().put("reportVersion", 1).put("timestampUtc", timestamp)
                    .put("transport", transport).put("wirelessHost", wirelessHost).put("wirelessPort", wirelessPort)
                    .put("probeMode", "nikon_picture_control_read_only").put("readOnly", true)
                    .put("shootingMenu", "photo").put("completed", completed)
                    .put("serialNumberCollected", false).put("presetWriteAttempted", false)
                    .put("pictureControlWriteAttempted", false).put("ncpWriteAttempted", false)
                    .put("directImportVerified", false).put("menuImportVerified", false)
                    .put("manufacturer", manufacturer).put("model", model).put("firmware", firmware)
                    .put("operationsAdvertised", hexArray(operations))
                    .put("vendorPropertiesRead", vendorPropertiesRead)
                    .put("vendorProperties", hexArray(vendorProperties))
                    .put("deviceInfoRead", deviceInfoRead).put("sessionOpenedByProbe", sessionOpened)
                    .put("sessionCloseConfirmed", sessionClosed)
                    .put("sessionRetainedForConfirmation", sessionRetained)
                    .put("protocolPhaseUnconfirmed", protocolPhaseUnconfirmed)
                    .put("standardCapability", capability).put("items", records)
                    .put("phase", phase).put("failurePhase", failurePhase)
                    .put("lastOperation", lastOperation).put("lastTransactionId", lastTransactionId)
                    .put("lastParameters", parameterJson(lastParameters)).put("lastItemCode", lastItemCode)
                    .put("lastPtpOperation", lastPtpOperation).put("lastPtpTransactionId", lastPtpTransactionId)
                    .put("failedOperation", failedOperation).put("failedTransactionId", failedTransactionId)
                    .put("failedParameters", parameterJson(failedParameters)).put("failedItemCode", failedItemCode)
                    .put("cleanupOperation", cleanupOperation).put("cleanupTransactionId", cleanupTransactionId)
                    .put("cleanupResponseCode", cleanupResponseCode < 0 ? JSONObject.NULL : hex16(cleanupResponseCode))
                    .put("cleanupError", cleanupError).put("error", error)
                    .put("commandsSent", new JSONArray(commandsSent)).put("diagnostics", new JSONArray(diagnostics));
        }

        public String toJsonString() {
            try { return toJson().toString(2); }
            catch (JSONException error) { throw new IllegalStateException("无法生成优化校准只读报告。", error); }
        }

        public String toDisplayText() {
            StringBuilder text = new StringBuilder("优化校准专用接口：只读验证（照片菜单）")
                    .append("\n连接方式：").append(transport.equals("wifi") ? "Wi-Fi / PTP-IP" : "USB")
                    .append("\n相机：").append(model.isEmpty() ? "尚未读取" : model)
                    .append("\n固件：").append(firmware)
                    .append("\n读取流程：").append(completed ? "完成" : "未完成")
                    .append("\n本工具会话：").append(sessionRetained ? "保持连接，等待确认" : sessionClosed ? "已关闭" : "关闭未确认")
                    .append("\n未执行上传、删除、预设选择或模式切换。")
                    .append("\nStandard 能力：").append(capabilityParsed ? "已解析" : "尚未完成")
                    .append("（").append(standardCapabilityRawBytes.length).append(" 字节）")
                    .append("\n已收到优化校准数据：").append(items.size()).append(" / 10\n");
            for (ItemRecord item : items) {
                text.append('\n').append(item.label).append(" (item=").append(item.itemCode).append(")：");
                if (!item.parsed) text.append("数据尚未通过解析");
                else if (item.empty) text.append("空槽（BasePictureControl=0）");
                else text.append(item.registrationName).append("，base=").append(item.basePictureControl)
                        .append("，自定义曲线=").append(item.customCurveEnabled);
                text.append("，").append(item.byteLength).append(" 字节\nSHA-256：").append(item.sha256).append('\n');
            }
            if (!error.isEmpty()) text.append("\n结果：").append(error).append('\n');
            if (!cleanupError.isEmpty()) text.append("\n会话清理：").append(cleanupError).append('\n');
            text.append("\n直接写入 NCP：尚未验证。读取成功不代表导入成功。\n");
            for (String diagnostic : diagnostics) text.append("\n• ").append(diagnostic);
            return text.toString();
        }

        @Override public String toString() { return toDisplayText(); }
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
    private static String hex16(int value) { return PtpConstants.hex16(value); }
    private static JSONArray hexArray(int[] values) {
        JSONArray array = new JSONArray();
        for (int value : values) array.put(hex16(value));
        return array;
    }
    private static JSONArray parameterJson(long[] values) {
        JSONArray array = new JSONArray();
        for (long value : values) array.put(String.format(Locale.ROOT, "0x%08X", value));
        return array;
    }
    private static String parametersText(long[] values) { return java.util.Arrays.toString(values); }
}

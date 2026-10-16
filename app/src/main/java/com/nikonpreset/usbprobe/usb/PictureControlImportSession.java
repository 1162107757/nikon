package com.nikonpreset.usbprobe.usb;

import com.nikonpreset.usbprobe.picturecontrol.PictureControlData;
import com.nikonpreset.usbprobe.ptp.PtpContainer;
import com.nikonpreset.usbprobe.ptp.PtpDeviceInfo;
import com.nikonpreset.usbprobe.ptp.PtpProtocolException;
import com.nikonpreset.usbprobe.ptp.PtpResponseException;
import com.nikonpreset.usbprobe.ptp.PtpValidation;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** One explicitly requested registration into a twice-observed empty photo-menu slot. */
public final class PictureControlImportSession {
    public interface Operations {
        byte[] getData(int operation, long transactionId, long... parameters) throws IOException;
        PtpContainer command(int operation, long transactionId, long... parameters) throws IOException;
        PtpContainer sendData(int operation, long transactionId, byte[] bytes, long... parameters) throws IOException;
    }

    private final Operations operations;
    private final UsbPictureControlImport.Result result;
    private final byte[] requestedData;
    private final int sourceItem;
    private final int targetItem;
    private final boolean requireSource;
    private long nextTransactionId = 1;
    private boolean sessionOwned;
    private boolean started;
    private boolean abortRequired;

    public PictureControlImportSession(Operations operations, UsbPictureControlImport.Result result,
                                       byte[] data, int sourceItem, int targetItem) {
        this(operations, result, data, sourceItem, targetItem, true);
    }

    private PictureControlImportSession(Operations operations, UsbPictureControlImport.Result result,
                                        byte[] data, int sourceItem, int targetItem, boolean requireSource) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.result = Objects.requireNonNull(result, "result");
        requestedData = data == null ? null : data.clone();
        this.sourceItem = sourceItem;
        this.targetItem = targetItem;
        this.requireSource = requireSource;
        result.sourceItem = sourceItem;
        result.sourceLabel = label(sourceItem);
        result.targetItem = targetItem;
        result.targetLabel = label(targetItem);
    }

    PictureControlImportSession(Operations operations, UsbPictureControlImport.Result result,
                                byte[] data, int targetItem) {
        this(operations, result, data, -1, targetItem, false);
    }

    public void run() throws IOException {
        if (started) throw new IllegalStateException("空槽登记会话不能重复使用。");
        started = true;
        try {
            runDetailed();
        } catch (PtpResponseException error) {
            result.failurePhase = result.phase;
            if (error.transactionId() != result.lastTransactionId) {
                abortRequired = true;
                result.protocolPhaseUnconfirmed = true;
                throw new PtpProtocolException("登记响应的事务编号不匹配。", error);
            }
            result.failedOperation = result.lastOperation;
            result.failedTransactionId = error.transactionId();
            result.failedParameters = result.lastParameters.clone();
            result.failedItemCode = result.lastItemCode;
            throw error;
        } catch (IOException error) {
            failLocal();
            throw error;
        } catch (RuntimeException error) {
            failLocal();
            throw new IOException("空槽登记处理失败。", error);
        }
    }

    private void failLocal() {
        result.failurePhase = result.phase;
        if (result.phase.equals("PtpOperation")) {
            abortRequired = true;
            result.protocolPhaseUnconfirmed = true;
        }
    }

    private void runDetailed() throws IOException {
        result.phase = "ConvertedDataValidation";
        requireTarget(targetItem);
        if ((requireSource || sourceItem != -1)
                && (sourceItem < 201 || sourceItem > 209 || sourceItem == targetItem)) {
            throw new IOException("来源和目标必须是不同的 C1–C9 槽位。");
        }
        PictureControlData requested = validateConvertedData(requestedData);
        result.convertedRawBytes = requested.rawBytes();
        result.convertedSha256 = UsbPictureControlProbe.sha256(requestedData);
        result.basePictureControl = requested.basePictureControl();
        result.registrationName = requested.registrationName();

        byte[] deviceBytes = query(0x1001, 0);
        result.phase = "DeviceInfoParse";
        PtpDeviceInfo info = PtpDeviceInfo.parseRedacted(deviceBytes);
        result.manufacturer = info.manufacturer();
        result.model = info.model();
        result.firmware = info.deviceVersion();
        result.operations = info.operationsSupported();
        result.phase = "CameraModelCheck";
        String normalized = info.model().trim().toUpperCase(Locale.ROOT)
                .replaceFirst("^NIKON\\s+", "").replaceAll("[\\s_-]+", "");
        if (!normalized.equals("Z6II") && !normalized.equals("Z62")) {
            throw new IOException("受控空槽登记仅支持尼康 Z6II，未发送写入请求。");
        }
        result.phase = "RequiredOperationsCheck";
        for (int required : new int[] {0x1002, 0x1003, 0x90cc, 0x90cd}) {
            if (!info.supportsOperation(required)) {
                throw new IOException("相机未声明支持 " + operationName(required) + "，未发送写入请求。");
            }
        }
        recordStep(0x1002, 0, 1);
        PtpValidation.requireOkResponse(operations.command(0x1002, 0, 1), 0);
        sessionOwned = true;
        result.sessionOpened = true;

        for (int item = 201; item <= 209; item++) observe(item, 0);
        result.phase = "SourceVerification";
        if (sourceItem != -1) {
            for (UsbPictureControlProbe.ItemRecord item : result.initialSlots) {
                if (item.itemCode == sourceItem) result.sourceSnapshot = item;
            }
            if (result.sourceSnapshot == null || result.sourceSnapshot.empty
                    || !Arrays.equals(result.sourceSnapshot.rawBytes(), requestedData)) {
                throw new IOException("本次读取的来源槽为空或与已选择快照不同，未发送写入请求；请重新只读扫描。");
            }
            result.sourceVerified = true;
        }
        result.phase = "EmptySlotCheck";
        for (UsbPictureControlProbe.ItemRecord item : result.initialSlots) {
            if (item.itemCode == targetItem) result.initialTargetEmpty = item.empty;
        }
        if (!result.initialTargetEmpty) throw new IOException("指定目标槽已有优化校准，未发送写入请求。");

        observe(targetItem, 1);
        result.phase = "EmptySlotRecheck";
        result.recheckTargetEmpty = result.recheck.empty;
        if (!result.recheckTargetEmpty) {
            throw new IOException("写入前复查发现目标槽已被占用，已停止且未发送写入请求。");
        }
        // There is no atomic compare-and-set operation in this protocol. No other
        // camera request is issued between this final empty observation and Set.
        long writeTransaction = nextTransactionId++;
        recordStep(0x90cd, writeTransaction, targetItem, 0);
        result.writeAttempted = true;
        PtpValidation.requireOkResponse(
                operations.sendData(0x90cd, writeTransaction, requestedData.clone(), targetItem, 0),
                writeTransaction);
        result.writeResponseOk = true;
        observe(targetItem, 2);
        result.phase = "ReadbackComparison";
        byte[] actual = result.readback.rawBytes();
        int maximum = Math.max(actual.length, requestedData.length);
        for (int i = 0; i < maximum; i++) {
            if (i >= actual.length || i >= requestedData.length || actual[i] != requestedData[i]) {
                result.differenceCount++;
                if (result.differenceOffsets.size() < 64) result.differenceOffsets.add(i);
            }
        }
        if (result.differenceCount != 0) {
            result.normalizationObserved = true;
            throw new IOException("相机已接受登记请求，但读回字节不同；可能存在相机规范化或其它差异，未通过精确验证。"
                    + "未自动重试或还原，请先重新只读扫描。");
        }
        result.readbackVerified = true;
        result.phase = "RegistrationVerified";
        result.diagnostics.add("目标槽读回字节与请求数据完全一致；未发送预设选择、删除或相机模式修改命令。");
        if (result.ncpMode) {
            result.diagnostics.add("只验证兼容适配数据的登记与读回；未验证与相机原生载入等效或实际拍摄效果。");
        } else result.diagnostics.add("这次只验证机内优化校准复制，不验证 NCP 转换或所有拍摄效果。");
    }

    private void observe(int itemCode, int kind) throws IOException {
        byte[] bytes = query(0x90cc, nextTransactionId++, itemCode, 0);
        result.phase = "PictureControlDataParse";
        UsbPictureControlProbe.ItemRecord item = new UsbPictureControlProbe.ItemRecord(itemCode, bytes);
        if (kind == 0) result.initialSlots.add(item);
        else if (kind == 1) result.recheck = item;
        else result.readback = item;
        PictureControlData data = PictureControlData.parse(bytes);
        item.basePictureControl = data.basePictureControl();
        item.registrationName = data.registrationName();
        item.customCurveEnabled = data.customCurveEnabled();
        item.empty = data.basePictureControl() == 0;
        item.parsed = true;
        if (kind == 2) result.readbackParsed = true;
    }

    private byte[] query(int operation, long transactionId, long... parameters) throws IOException {
        recordStep(operation, transactionId, parameters);
        return operations.getData(operation, transactionId, parameters);
    }

    private void recordStep(int operation, long transactionId, long... parameters) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("空槽登记已取消。");
        result.phase = "PtpOperation";
        result.lastOperation = operationName(operation);
        result.lastTransactionId = transactionId;
        result.lastParameters = parameters.clone();
        result.lastItemCode = -1;
        if ((operation == 0x90cc || operation == 0x90cd) && parameters.length > 0) {
            result.lastItemCode = (int) parameters[0];
        }
    }

    public boolean ownsSession() { return sessionOwned; }
    public boolean requiresProtocolAbort() { return abortRequired; }
    public void closeSession() throws IOException {
        if (!sessionOwned) return;
        long transactionId = nextTransactionId++;
        result.cleanupOperation = operationName(0x1003);
        result.cleanupTransactionId = transactionId;
        PtpValidation.requireOkResponse(operations.command(0x1003, transactionId), transactionId);
        sessionOwned = false;
        result.sessionClosed = true;
    }

    /** Strict write validation, separate from the permissive read-only snapshot decoder. */
    public static PictureControlData validateConvertedData(byte[] bytes) throws IOException {
        PictureControlData data = PictureControlData.parse(bytes);
        int base = data.basePictureControl();
        if (base < 1 || base > 6) throw new IOException("登记数据仅支持已核实的基础优化校准 1–6。");
        if (data.registrationName().trim().isEmpty()) throw new IOException("登记名称不能为空。");
        for (char c : data.registrationName().toCharArray()) {
            if (c < 32 || c > 126) throw new IOException("登记名称必须是可打印 ASCII 字符。");
        }
        if (data.applyLevel() != 0 || data.quickSharpFlag() > 1) throw new IOException("不支持的优化校准控制标志。");
        boolean quickAuto = data.quickSharpFlag() == 1 && data.quickSharp() == -128;
        if (!quickAuto) range(data.quickSharp(), -2, 2, "QuickSharp");
        rangeOrAuto(data.sharpening(), -12, 36, quickAuto, "Sharpening");
        rangeOrAuto(data.middleRangeSharpening(), -20, 20, quickAuto, "MiddleRangeSharpening");
        rangeOrAuto(data.clarity(), -20, 20, quickAuto, "Clarity");
        rangeOrAuto(data.contrast(), -12, 12, true, "Contrast");
        range(data.brightness(), -6, 6, "Brightness");
        if (base == 4) {
            if (data.saturation() != 0 || data.hue() != 0) throw new IOException("单色优化校准的色彩字段必须为零。");
            range(data.filterEffects(), 0, 4, "FilterEffects");
            range(data.toning(), 0, 9, "Toning");
            range(data.toningDensity(), 0, 28, "ToningDensity");
        } else {
            rangeOrAuto(data.saturation(), -12, 12, true, "Saturation");
            range(data.hue(), -12, 12, "Hue");
            if (data.filterEffects() != 0 || data.toning() != 0 || data.toningDensity() != 0) {
                throw new IOException("彩色基础优化校准不支持单色滤镜或调色字段。");
            }
        }
        int expectedLength = data.customCurveEnabled() ? 614 : 36;
        if (bytes.length != expectedLength) throw new IOException("曲线标志与登记数据长度不匹配。");
        if (data.customCurveEnabled() && bytes[36] == 0 && bytes[37] == 0) {
            throw new IOException("自定义曲线缺少有效的主机头标识。");
        }
        return data;
    }

    private static void rangeOrAuto(int value, int min, int max, boolean auto, String field) throws IOException {
        if (auto && value == -128) return;
        range(value, min, max, field);
    }
    private static void range(int value, int min, int max, String field) throws IOException {
        if (value < min || value > max) throw new IOException(field + " 超出已核实范围。");
    }
    private static void requireTarget(int target) throws IOException {
        if (target < 201 || target > 209) throw new IOException("必须明确选择 C1–C9 目标槽。");
    }
    static String label(int item) { return item >= 201 && item <= 209 ? "C" + (item - 200) : "未指定"; }
    static String operationName(int op) {
        if (op == 0x90cd) return "SetPicCtrlData (0x90CD)";
        return PictureControlReadSession.operationName(op);
    }
}

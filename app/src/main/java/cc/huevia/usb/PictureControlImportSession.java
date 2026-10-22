package cc.huevia.usb;

import cc.huevia.picturecontrol.PictureControlData;
import cc.huevia.picturecontrol.PictureControlCapability;
import cc.huevia.ptp.PtpContainer;
import cc.huevia.ptp.PtpDeviceInfo;
import cc.huevia.ptp.PtpProtocolException;
import cc.huevia.ptp.PtpResponseException;
import cc.huevia.ptp.PtpValidation;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/** One registration into a twice-checked empty slot or explicitly confirmed occupied snapshot. */
public final class PictureControlImportSession {
    public interface Operations {
        byte[] getData(int operation, long transactionId, long... parameters) throws IOException;
        PtpContainer command(int operation, long transactionId, long... parameters) throws IOException;
        PtpContainer sendData(int operation, long transactionId, byte[] bytes, long... parameters) throws IOException;
    }

    /** Immutable evidence of the occupied target shown in an explicit overwrite confirmation. */
    public static final class OverwriteConfirmation {
        private final int targetItem;
        private final byte[] snapshot;
        private final String sha256;
        private final String registrationName;
        private final int basePictureControl;

        public OverwriteConfirmation(int targetItem, byte[] confirmedTargetSnapshot) throws IOException {
            requireTarget(targetItem);
            PictureControlData data = PictureControlData.parse(confirmedTargetSnapshot);
            if (data.hasNoBasePictureControl()) {
                throw new IOException("覆盖确认必须来自已有预设的目标槽；空槽请使用普通导入。");
            }
            this.targetItem = targetItem;
            snapshot = data.rawBytes();
            sha256 = UsbPictureControlProbe.sha256(snapshot);
            registrationName = data.registrationName();
            basePictureControl = data.basePictureControl();
        }

        public int targetItem() { return targetItem; }
        public String sha256() { return sha256; }
        public String registrationName() { return registrationName; }
        public int basePictureControl() { return basePictureControl; }
        public byte[] rawBytes() { return snapshot.clone(); }

        void requireTargetMatch(int target) throws IOException {
            if (target != targetItem) throw new IOException("覆盖确认与目标槽不一致，未发送写入请求。");
        }

        void recordInto(UsbPictureControlImport.Result result) {
            result.overwriteRequested = true;
            result.confirmedTargetItem = targetItem;
            result.confirmedTargetSha256 = sha256;
            result.confirmedTargetName = registrationName;
            result.confirmedTargetBasePictureControl = basePictureControl;
        }

        private boolean matches(UsbPictureControlProbe.ItemRecord item) {
            return item != null && item.parsed && !item.empty && item.itemCode == targetItem
                    && item.basePictureControl == basePictureControl
                    && registrationName.equals(item.registrationName) && sha256.equals(item.sha256)
                    && Arrays.equals(snapshot, item.rawBytes());
        }
    }

    private final Operations operations;
    private final UsbPictureControlImport.Result result;
    private final byte[] requestedData;
    private final int sourceItem;
    private final int targetItem;
    private final boolean requireSource;
    private final OverwriteConfirmation overwrite;
    private boolean retainedSession;
    private long nextTransactionId = 1;
    private boolean sessionOwned;
    private boolean started;
    private boolean abortRequired;
    private boolean registrationComplete;
    private byte[] freshCapability;

    public PictureControlImportSession(Operations operations, UsbPictureControlImport.Result result,
                                       byte[] data, int sourceItem, int targetItem) {
        this(operations, result, data, sourceItem, targetItem, true, null);
    }

    public PictureControlImportSession(Operations operations, UsbPictureControlImport.Result result,
                                       byte[] data, int sourceItem, int targetItem,
                                       OverwriteConfirmation overwrite) {
        this(operations, result, data, sourceItem, targetItem, true, overwrite);
    }

    private PictureControlImportSession(Operations operations, UsbPictureControlImport.Result result,
                                        byte[] data, int sourceItem, int targetItem, boolean requireSource,
                                        OverwriteConfirmation overwrite) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.result = Objects.requireNonNull(result, "result");
        requestedData = data == null ? null : data.clone();
        this.sourceItem = sourceItem;
        this.targetItem = targetItem;
        this.requireSource = requireSource;
        this.overwrite = overwrite;
        result.sourceItem = sourceItem;
        result.sourceLabel = label(sourceItem);
        result.targetItem = targetItem;
        result.targetLabel = label(targetItem);
        if (overwrite != null) overwrite.recordInto(result);
    }

    PictureControlImportSession(Operations operations, UsbPictureControlImport.Result result,
                                byte[] data, int targetItem) {
        this(operations, result, data, -1, targetItem, false, null);
    }

    PictureControlImportSession(Operations operations, UsbPictureControlImport.Result result,
                                byte[] data, int targetItem, OverwriteConfirmation overwrite) {
        this(operations, result, data, -1, targetItem, false, overwrite);
    }

    /** Package-only handoff from the reader that opened this wireless session. */
    PictureControlImportSession(Operations operations, UsbPictureControlImport.Result result,
                                byte[] data, int targetItem, long nextTransactionId) {
        this(operations, result, data, targetItem, nextTransactionId, null);
    }

    PictureControlImportSession(Operations operations, UsbPictureControlImport.Result result,
                                byte[] data, int targetItem, long nextTransactionId,
                                OverwriteConfirmation overwrite) {
        this(operations, result, data, -1, targetItem, false, overwrite);
        if (nextTransactionId < 1 || nextTransactionId > 0xffffffffL) {
            throw new IllegalArgumentException("无线会话事务编号无效。");
        }
        this.nextTransactionId = nextTransactionId;
        retainedSession = true;
        sessionOwned = true;
        result.sessionOpened = true;
    }

    public void run() throws IOException {
        if (started) throw new IllegalStateException("登记会话不能重复使用。");
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
            throw new IOException("优化校准登记处理失败。", error);
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
        if (overwrite != null) overwrite.requireTargetMatch(targetItem);
        if ((requireSource || sourceItem != -1)
                && (sourceItem < 201 || sourceItem > 209 || sourceItem == targetItem)) {
            throw new IOException("来源和目标必须是不同的 C1–C9 槽位。");
        }
        PictureControlData requested = validateConvertedData(requestedData);
        result.convertedRawBytes = requested.rawBytes();
        result.convertedSha256 = UsbPictureControlProbe.sha256(requestedData);
        result.basePictureControl = requested.basePictureControl();
        result.registrationName = requested.registrationName();

        byte[] deviceBytes = query(0x1001, retainedSession ? nextTransactionId++ : 0);
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
            throw new IOException("受控登记仅支持尼康 Z6II，未发送写入请求。");
        }
        result.phase = "RequiredOperationsCheck";
        for (int required : new int[] {0x1002, 0x1003, 0x90cc, 0x90cd}) {
            if (!info.supportsOperation(required)) {
                throw new IOException("相机未声明支持 " + operationName(required) + "，未发送写入请求。");
            }
        }
        if (!retainedSession) {
            recordStep(0x1002, 0, 1);
            PtpValidation.requireOkResponse(operations.command(0x1002, 0, 1), 0);
            sessionOwned = true;
            result.sessionOpened = true;
        }
        if (retainedSession || overwrite != null) {
            // The initial snapshot is for the confirmation screen only. Re-read
            // capability data and every custom slot before the guarded write.
            if (!info.supportsOperation(0x90cf)) {
                throw new IOException("相机未声明支持 GetPicCtrlCapability (0x90CF)，未发送写入请求。");
            }
            byte[] capability = query(0x90cf, nextTransactionId++, 1);
            result.phase = "PictureControlCapabilityParse";
            PictureControlCapability.parse(capability);
            freshCapability = capability.clone();
            result.freshCapabilityVerified = true;
        }

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
        result.phase = overwrite == null ? "EmptySlotCheck" : "OverwriteTargetCheck";
        UsbPictureControlProbe.ItemRecord initialTarget = null;
        for (UsbPictureControlProbe.ItemRecord item : result.initialSlots) {
            if (item.itemCode == targetItem) { initialTarget = item; result.initialTargetEmpty = item.empty; }
        }
        if (overwrite == null) {
            if (!result.initialTargetEmpty) throw new IOException("指定目标槽已有优化校准，未发送写入请求。");
        } else {
            result.initialTargetMatched = overwrite.matches(initialTarget);
            if (!result.initialTargetMatched) {
                throw new IOException("目标槽与已确认覆盖的原预设不同，已停止；请重新读取并确认，未发送写入请求。");
            }
        }

        observe(targetItem, 1);
        result.phase = overwrite == null ? "EmptySlotRecheck" : "OverwriteTargetRecheck";
        result.recheckTargetEmpty = result.recheck.empty;
        if (overwrite == null) {
            if (!result.recheckTargetEmpty) {
                throw new IOException("写入前复查发现目标槽已被占用，已停止且未发送写入请求。");
            }
        } else {
            result.recheckTargetMatched = overwrite.matches(result.recheck);
            if (!result.recheckTargetMatched) {
                throw new IOException("写入前复查发现待覆盖预设已改变，已停止；请重新读取并确认，未发送写入请求。");
            }
            result.diagnostics.add("明确覆盖已确认的 " + label(targetItem) + "「" + overwrite.registrationName()
                    + "」；本次完整扫描与紧接写入前的目标字节均匹配确认快照。SHA-256：" + overwrite.sha256());
        }
        // There is no atomic compare-and-set operation in this protocol. No other
        // camera request is issued between this final verified observation and Set.
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
        registrationComplete = true;
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
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("优化校准登记已取消。");
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

    /** Only a complete exact registration may hand its owned session back to read-only holding. */
    long transferAfterVerifiedRegistration() throws IOException {
        if (!registrationComplete || !sessionOwned || abortRequired || !result.writeResponseOk
                || !result.readbackVerified || nextTransactionId < 1 || nextTransactionId > 0xffffffffL) {
            throw new IOException("登记尚未完整核对，不能保留或再次使用本次会话。");
        }
        sessionOwned = false;
        return nextTransactionId;
    }

    byte[] verifiedCapabilityBytes() throws IOException {
        if (!registrationComplete || !result.readbackVerified || freshCapability == null) {
            throw new IOException("缺少本次登记前完整核对的能力数据。");
        }
        return freshCapability.clone();
    }
    public void closeSession() throws IOException {
        if (!sessionOwned) return;
        long transactionId = nextTransactionId++;
        result.cleanupOperation = operationName(0x1003);
        result.cleanupTransactionId = transactionId;
        PtpValidation.requireOkResponse(operations.command(0x1003, transactionId), transactionId);
        sessionOwned = false;
        result.sessionClosed = true;
    }

    /** Strict write validation, shared with the isolated wireless transport. */
    public static PictureControlData validateConvertedData(byte[] bytes) throws IOException {
        return cc.huevia.picturecontrol.PictureControlWriteValidation.validateConvertedData(bytes);
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

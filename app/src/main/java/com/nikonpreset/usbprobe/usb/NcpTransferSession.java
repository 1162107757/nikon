package com.nikonpreset.usbprobe.usb;

import com.nikonpreset.usbprobe.ptp.PtpContainer;
import com.nikonpreset.usbprobe.ptp.PtpDeviceInfo;
import com.nikonpreset.usbprobe.ptp.PtpObjectHandles;
import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import com.nikonpreset.usbprobe.ptp.PtpResponseException;
import com.nikonpreset.usbprobe.ptp.PtpStorageIds;
import com.nikonpreset.usbprobe.ptp.PtpStorageInfo;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Standard-PTP transfer plan, independent of Android so it can be simulated. */
final class NcpTransferSession {
    interface Operations {
        byte[] getData(int operation, long transactionId, long... parameters) throws IOException;
        PtpContainer command(int operation, long transactionId, long... parameters) throws IOException;
        PtpContainer sendData(int operation, long transactionId, byte[] data, long... parameters)
                throws IOException;
    }

    private static final long ROOT = 0xffffffffL;
    private static final int MAX_CHILDREN = 2048;
    private static final int[] REQUIRED_OPERATIONS = {
            0x1002, 0x1003, 0x1004, 0x1005, 0x1007, 0x1008, 0x1009, 0x100c, 0x100d
    };
    private final Operations operations;
    private final UsbNcpTransfer.Result result;
    private final boolean existingDirectoryExperiment;
    private final Set<Long> knownHandles = new HashSet<>();
    private long nextTransactionId = 1;
    private long closeSessionTransactionId = -1;
    private boolean sessionOwned;

    NcpTransferSession(Operations operations, UsbNcpTransfer.Result result) {
        this(operations, result, false);
    }

    NcpTransferSession(Operations operations, UsbNcpTransfer.Result result, boolean existingDirectoryExperiment) {
        this.operations = operations;
        this.result = result;
        this.existingDirectoryExperiment = existingDirectoryExperiment;
        result.existingDirectoryExperimentRequested = existingDirectoryExperiment;
        result.transferMode = existingDirectoryExperiment ? "existing_directory_experiment" : "normal";
    }

    void run(byte[] ncp) throws IOException {
        try {
            runInternal(ncp);
        } catch (PtpResponseException error) {
            result.failurePhase = "PtpOperation";
            result.failedOperation = result.lastOperation;
            result.failedTransactionId = error.transactionId();
            result.failedStorageId = result.lastStorageId;
            throw error;
        } catch (IOException error) {
            result.failurePhase = result.phase;
            throw error;
        }
    }

    private void runInternal(byte[] ncp) throws IOException {
        if (ncp == null || ncp.length == 0 || ncp.length > UsbNcpTransfer.MAX_NCP_BYTES) {
            throw new IOException("请选择非空且不超过 1 MiB 的 NCP 文件。");
        }
        PtpDeviceInfo device = PtpDeviceInfo.parseRedacted(getData(0x1001, 0));
        result.manufacturer = device.manufacturer();
        result.model = device.model();
        result.firmware = device.deviceVersion();
        if (!isZ6II(device.model())) {
            throw new IOException("实验性上传仅允许尼康 Z6II，当前相机为 " + device.model() + "；未写入文件。");
        }
        for (int operation : REQUIRED_OPERATIONS) {
            if (!device.supportsOperation(operation)) {
                throw new IOException("相机未声明支持所需标准 PTP 操作 " + hex(operation)
                        + "，已停止；未写入文件。");
            }
        }
        recordStep(0x1002, 0);
        operations.command(0x1002, 0, 1);
        recordSuccess();
        sessionOwned = true;
        result.sessionOpened = true;
        long[] storageIds = PtpStorageIds.parse(read(0x1004));
        result.storageCount = storageIds.length;
        if (storageIds.length > 16) throw new IOException("相机报告存储卡数量异常，未写入文件。");
        StorageChoice selected = null;
        List<StorageChoice> experimentChoices = new ArrayList<>();
        BigInteger requiredBytes = BigInteger.valueOf(ncp.length + 8192L);
        for (long storageId : storageIds) {
            // A physical slot without a logical medium has a zero low word.
            // Retain the original ID in the report, but do not query it as media.
            if ((storageId & 0xffffL) == 0) {
                result.unavailableStorageIds.add(storageId);
                result.unmountedStorageIds.add(storageId);
                result.unavailableStorageCount++;
                result.diagnostics.add("存储 " + hex(storageId)
                        + " 未挂载逻辑存储；未发送 GetStorageInfo，也未假定相机返回错误码。");
                continue;
            }
            PtpStorageInfo info;
            long storageInfoTransaction = nextTransactionId;
            try {
                info = PtpStorageInfo.parse(read(0x1005, storageId));
            } catch (PtpResponseException error) {
                // Only this one read-only slot query may recover from a complete,
                // matching StoreNotAvailable response. Other operations still fail.
                if (error.responseCode() != 0x2013
                        || error.transactionId() != storageInfoTransaction) throw error;
                result.unavailableStorageIds.add(storageId);
                result.unavailableStorageCount++;
                result.diagnostics.add("GetStorageInfo (0x1005), transaction="
                        + storageInfoTransaction + ", storageId=" + hex(storageId)
                        + "：存储介质不可用（0x2013 / StoreNotAvailable）；继续检查其它存储槽位。");
                continue;
            }
            result.availableStorageCount++;
            result.phase = "StorageEligibilityCheck";
            StorageAssessment assessment = StorageAssessment.assess(storageId, info, requiredBytes,
                    existingDirectoryExperiment);
            result.storageAssessment.add(assessment);
            result.diagnostics.add(assessment.toDisplayText());
            if (!assessment.eligible) {
                if (assessment.experimentCandidate) experimentChoices.add(new StorageChoice(storageId, info));
                continue;
            }
            StorageChoice candidate = new StorageChoice(storageId, info);
            if (selected == null || (!looksLikeSd(selected.info) && looksLikeSd(info))) {
                selected = candidate;
            }
        }
        result.phase = "StorageEligibilityCheck";
        if (selected == null && existingDirectoryExperiment && !experimentChoices.isEmpty()) {
            if (experimentChoices.size() == 1) {
                selected = experimentChoices.get(0);
            } else {
                int matchingSdCount = 0;
                for (StorageChoice candidate : experimentChoices) {
                    if (looksLikeSd(candidate.info)) {
                        selected = candidate;
                        matchingSdCount++;
                    }
                }
                if (matchingSdCount != 1) {
                    throw new IOException("存在多个只读实验候选存储卡，无法唯一识别 SD 卡；未发送任何写入请求。");
                }
            }
        }
        if (selected == null) {
            if (result.availableStorageCount == 0) {
                throw new IOException("相机已连接，但未找到可用存储卡；请确认 SD 卡已在相机中就绪。未写入文件。");
            }
            for (StorageAssessment assessment : result.storageAssessment) {
                if (assessment.accessCapability == 1 || assessment.accessCapability == 2) {
                    if (existingDirectoryExperiment) {
                        throw new IOException("没有只读卡同时满足本次现有目录实验的全部条件；"
                                + "实验仅可对 AccessCapability=0x0002 破例，其它权限值及存储、目录、空间条件仍受限制。"
                                + "尚未实际测试指定目录上传，未写入文件；详见逐槽拒绝原因。");
                    }
                    throw new IOException("相机在 USB/PTP 下将卡报告为只读（可删除不代表可写），"
                            + "普通模式按只读标志停止，尚未实际测试指定目录上传。未写入文件；逐槽原始字段与其它拒绝原因见诊断报告。");
                }
            }
            throw new IOException("没有可写、空间充足且支持目录的可移动存储卡，未写入文件。");
        }
        result.storageId = selected.id;
        result.selectedAccessCapability = selected.info.accessCapability();
        result.readOnlyExceptionSelected = existingDirectoryExperiment && selected.info.accessCapability() == 2;
        result.storageDescription = selected.info.description();
        result.freeSpaceBytes = selected.info.freeSpaceBytes().toString();
        result.diagnostics.add("选择相机存储卡 " + hex(selected.id) + "：" + selected.info.description());
        if (!looksLikeSd(selected.info)) {
            result.diagnostics.add("存储描述未确认 SD 卡类型，请在相机菜单选择报告所列存储卡。");
        }

        if (result.readOnlyExceptionSelected) {
            result.diagnostics.add("手动实验选中 AccessCapability=0x0002 的卡；声明权限仍是只读。"
                    + "仅尝试已存在的 NIKON/CUSTOMPC，不认为该目录有特殊写权限。");
        }
        long nikon;
        long customPc;
        if (existingDirectoryExperiment) {
            result.phase = "ExistingDirectoryCheck";
            nikon = requireExistingDirectory(selected.id, ROOT, "NIKON", "NIKON");
            customPc = requireExistingDirectory(selected.id, nikon, "CUSTOMPC", "NIKON/CUSTOMPC");
            result.existingPathVerified = true;
            result.diagnostics.add("已确认 USB 对象视图中的现有 NIKON/CUSTOMPC 标准目录，未请求创建目录。"
                    + "目录列表未必暴露卡上全部文件，编号仅依据此 USB 视图选择。");
        } else {
            nikon = findOrCreateDirectory(selected.id, ROOT, "NIKON", "NIKON");
            customPc = findOrCreateDirectory(selected.id, nikon, "CUSTOMPC", "NIKON/CUSTOMPC");
        }
        result.parentHandle = customPc;
        List<Child> children = listChildren(selected.id, customPc);
        String filename = availableFilename(children);
        if (filename == null) throw new IOException("PICCON01.NCP 到 PICCON99.NCP 均已占用，未覆盖任何文件。");
        result.fileName = filename;

        // Confirm the chosen name remains absent immediately before reservation.
        List<Child> beforeReservation = listChildren(selected.id, customPc);
        for (Child child : beforeReservation) {
            if (child.info.filename().equalsIgnoreCase(filename)) {
                throw new IOException("目标文件名在上传前已被占用，已停止；未覆盖任何文件。");
            }
        }
        result.writeAttempted = true;
        result.changes.add("即将请求创建 NIKON/CUSTOMPC/" + filename
                + "；若连接失败，相机可能已保留空文件或未完成对象。");
        PtpContainer reservation = send(0x100c,
                PtpObjectInfo.file(selected.id, customPc, filename, ncp.length).toBytes(),
                selected.id, customPc);
        result.fileMetadataAccepted = true;
        long handle = acceptedHandle(reservation, selected.id, customPc);
        result.fileHandle = handle;
        result.changes.add("相机已接受新文件元数据，handle=" + hex(handle) + "；内容尚未验证。");
        if (knownHandles.contains(handle)) {
            throw new IOException("相机返回已知对象或父目录的 handle，已停止且未发送文件内容，避免覆盖该对象。");
        }
        for (Child child : beforeReservation) {
            if (child.handle == handle) {
                throw new IOException("相机返回已存在对象的 handle，已停止且未发送文件内容，避免覆盖该对象。");
            }
        }
        result.uploadStarted = true;
        send(0x100d, ncp);
        result.uploadResponseOk = true;
        result.changes.add("相机已确认接收 " + ncp.length + " 字节 NCP 内容；正在读回验证。");

        PtpObjectInfo written = objectInfo(handle);
        requireLocation(written, selected.id, customPc, filename);
        if (written.objectFormat() != 0x3000 || written.compressedSize() != ncp.length) {
            throw new IOException("上传后对象格式或大小不符合请求。文件可能已写入，但未通过验证。");
        }
        byte[] readback = read(0x1009, handle);
        result.readbackSha256 = UsbNcpTransfer.sha256(readback);
        if (!Arrays.equals(ncp, readback) || !result.sha256.equals(result.readbackSha256)) {
            throw new IOException("读回内容与原文件不一致。上传文件未通过验证，请在相机中检查；未执行删除或覆盖。");
        }
        // Verify the new object is visible in the requested directory too.
        boolean found = false;
        for (Child child : listChildren(selected.id, customPc)) {
            if (child.handle == handle && child.info.filename().equals(filename)) found = true;
        }
        if (!found) throw new IOException("上传对象未出现在指定目录；文件可能已写入，但未通过路径验证。");
        result.readbackVerified = true;
        result.verifiedTransfer = true;
        result.changes.add("已验证 NIKON/CUSTOMPC/" + filename + " 的目录、字节内容与 SHA-256。");
        result.diagnostics.add("文件已传至相机存储卡，请在相机“管理优化校准 → 载入/保存”菜单载入。");
        result.diagnostics.add("机内菜单载入结果尚未验证，未自动安装或覆盖自定义优化校准槽位。");
    }

    boolean ownsSession() { return sessionOwned; }

    void closeSession() throws IOException {
        if (!sessionOwned) return;
        long transactionId = nextTransactionId++;
        // Cleanup must never replace the primary operation's diagnostic context.
        closeSessionTransactionId = transactionId;
        operations.command(0x1003, transactionId);
        sessionOwned = false;
        result.sessionClosed = true;
    }

    long closeSessionTransactionId() { return closeSessionTransactionId; }

    private byte[] read(int operation, long... parameters) throws IOException {
        long transactionId = nextTransactionId++;
        return getData(operation, transactionId, parameters);
    }

    private byte[] getData(int operation, long transactionId, long... parameters) throws IOException {
        recordStep(operation, transactionId, parameters);
        byte[] data = operations.getData(operation, transactionId, parameters);
        recordSuccess();
        return data;
    }

    private PtpContainer send(int operation, byte[] data, long... parameters) throws IOException {
        long transactionId = nextTransactionId++;
        recordStep(operation, transactionId, parameters);
        if (result.readOnlyExceptionSelected && (operation == 0x100c || operation == 0x100d)) {
            result.readOnlyExceptionAttempted = true;
        }
        PtpContainer response = operations.sendData(operation, transactionId, data, parameters);
        recordSuccess();
        return response;
    }

    private void recordStep(int operation, long transactionId, long... parameters) {
        result.phase = "PtpOperation";
        result.lastOperation = operationName(operation);
        result.lastTransactionId = transactionId;
        result.lastStorageId = operation == 0x1005 && parameters.length > 0
                ? hex(parameters[0]) : result.storageId != 0 && operation != 0x1003
                ? hex(result.storageId) : "";
    }

    private void recordSuccess() {
        result.lastPtpOperation = result.lastOperation;
        result.lastPtpTransactionId = result.lastTransactionId;
        result.lastPtpStorageId = result.lastStorageId;
    }

    private static String operationName(int operation) {
        switch (operation) {
            case 0x1001: return "GetDeviceInfo (0x1001)";
            case 0x1002: return "OpenSession (0x1002)";
            case 0x1003: return "CloseSession (0x1003)";
            case 0x1004: return "GetStorageIDs (0x1004)";
            case 0x1005: return "GetStorageInfo (0x1005)";
            case 0x1007: return "GetObjectHandles (0x1007)";
            case 0x1008: return "GetObjectInfo (0x1008)";
            case 0x1009: return "GetObject (0x1009)";
            case 0x100c: return "SendObjectInfo (0x100C)";
            case 0x100d: return "SendObject (0x100D)";
            default: throw new IllegalArgumentException("Unknown transfer operation");
        }
    }

    private PtpObjectInfo objectInfo(long handle) throws IOException {
        return PtpObjectInfo.parse(read(0x1008, handle));
    }

    private List<Child> listChildren(long storage, long parent) throws IOException {
        long[] handles = PtpObjectHandles.parse(read(0x1007, storage, 0, parent));
        result.phase = "DirectoryValidation";
        if (handles.length > MAX_CHILDREN) {
            throw new IOException("目录对象数量超出探测上限，无法保证目标名空闲；已停止上传。");
        }
        List<Child> children = new ArrayList<>();
        for (long handle : handles) {
            if (handle == 0 || handle == ROOT) throw new IOException("相机返回无效的对象 handle。");
            knownHandles.add(handle);
            PtpObjectInfo info = objectInfo(handle);
            result.phase = "DirectoryValidation";
            if (info.storageId() != storage) {
                throw new IOException("相机返回了其它存储卡的对象，已停止以避免错误写入。");
            }
            if (sameParent(info.parentObject(), parent)) children.add(new Child(handle, info));
        }
        return children;
    }

    private long findOrCreateDirectory(long storage, long parent, String name, String path)
            throws IOException {
        Child existing = null;
        for (Child child : listChildren(storage, parent)) {
            if (!child.info.filename().equalsIgnoreCase(name)) continue;
            if (!child.info.filename().equals(name) || child.info.objectFormat() != 0x3001
                    || child.info.associationType() != 1 || existing != null) {
                throw new IOException(path + " 存在名称、类型或重复对象冲突；已停止，未覆盖或删除。");
            }
            existing = child;
        }
        if (existing != null) return existing.handle;
        result.writeAttempted = true;
        result.changes.add("即将请求创建目录 " + path
                + "；若连接失败，该目录可能已创建。");
        PtpContainer response = send(0x100c,
                PtpObjectInfo.folder(storage, parent == ROOT ? 0 : parent, name).toBytes(), storage, parent);
        result.changes.add("相机已接受目录 " + path + " 的创建请求；位置待验证。");
        long handle = acceptedHandle(response, storage, parent);
        if (knownHandles.contains(handle)) {
            throw new IOException("相机返回已存在目录或对象的 handle，目录创建未通过验证，已停止。");
        }
        PtpObjectInfo created = objectInfo(handle);
        requireLocation(created, storage, parent, name);
        if (created.objectFormat() != 0x3001 || created.associationType() != 1) {
            throw new IOException("新对象不是标准目录，已停止；相机上可能留下该对象。");
        }
        knownHandles.add(handle);
        result.changes.add("已验证新目录 " + path + "，handle=" + hex(handle));
        return handle;
    }

    private long requireExistingDirectory(long storage, long parent, String name, String path)
            throws IOException {
        List<Child> children = listChildren(storage, parent);
        result.phase = "ExistingDirectoryCheck";
        Child found = null;
        for (Child child : children) {
            if (!child.info.filename().equalsIgnoreCase(name)) continue;
            if (!child.info.filename().equals(name) || child.info.objectFormat() != 0x3001
                    || child.info.associationType() != 1 || found != null) {
                throw new IOException(path + " 存在名称、对象类型或重复目录冲突；实验模式不创建、覆盖或删除目录。");
            }
            found = child;
        }
        if (found == null) {
            throw new IOException("USB 对象视图中未找到已存在的 " + path + "；实验模式不创建目录。"
                    + "请先在相机“管理优化校准 → 载入/保存 → 复制到存储卡”将一个自定义优化校准复制到 SD 卡以建立目录，再重新连接检测。"
                    + "卡上可能已有但未通过 USB 暴露的目录，本结果不等于目录实际不存在。未发送写入请求。");
        }
        return found.handle;
    }

    private static long acceptedHandle(PtpContainer response, long storage, long parent)
            throws IOException {
        long[] parameters = response.parameters();
        if (parameters.length != 3 || parameters[0] != storage
                || !sameParent(parameters[1], parent) || parameters[2] == 0 || parameters[2] == ROOT) {
            throw new IOException("相机返回的创建位置或 handle 与请求不符。对象可能已创建，已停止；未回滚或删除。");
        }
        return parameters[2];
    }

    private static void requireLocation(PtpObjectInfo info, long storage, long parent, String name)
            throws IOException {
        if (info.storageId() != storage || !sameParent(info.parentObject(), parent)
                || !info.filename().equals(name)) {
            throw new IOException("创建后的对象目录、存储卡或文件名与请求不符。对象可能已创建，已停止。");
        }
    }

    private static boolean sameParent(long actual, long expected) {
        return actual == expected || (expected == ROOT && actual == 0);
    }

    private static String availableFilename(List<Child> children) {
        for (int slot = 1; slot <= 99; slot++) {
            String candidate = String.format(Locale.US, "PICCON%02d.NCP", slot);
            boolean occupied = false;
            for (Child child : children) {
                if (child.info.filename().equalsIgnoreCase(candidate)) { occupied = true; break; }
            }
            if (!occupied) return candidate;
        }
        return null;
    }

    static boolean isZ6II(String model) {
        if (model == null) return false;
        String normalized = model.trim().toUpperCase(Locale.ROOT)
                .replaceFirst("^NIKON\\s+", "").replaceAll("[\\s_-]+", "");
        return normalized.equals("Z6II") || normalized.equals("Z62");
    }

    private static boolean looksLikeSd(PtpStorageInfo info) {
        String description = info.description().toUpperCase(Locale.ROOT);
        return description.matches(".*\\bSD(?:HC|XC)?\\b.*");
    }

    private static String hex(long value) { return String.format(Locale.US, "0x%08X", value); }
    private static final class Child {
        final long handle;
        final PtpObjectInfo info;
        Child(long handle, PtpObjectInfo info) { this.handle = handle; this.info = info; }
    }
    private static final class StorageChoice {
        final long id;
        final PtpStorageInfo info;
        StorageChoice(long id, PtpStorageInfo info) { this.id = id; this.info = info; }
    }
}

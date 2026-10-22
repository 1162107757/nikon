package cc.huevia.usb;

import cc.huevia.ptp.PtpStorageInfo;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A storage's raw PTP fields and all reasons it cannot be an upload target. */
public final class StorageAssessment {
    public static final int STORAGE_REMOVABLE_RAM = 4;
    public static final int FILESYSTEM_GENERIC_HIERARCHICAL = 2;
    public static final int FILESYSTEM_DCF = 3;
    public static final int ACCESS_READ_WRITE = 0;

    public final long storageId;
    public final int storageType;
    public final int fileSystemType;
    public final int accessCapability;
    public final String maxCapacityBytes;
    public final String freeSpaceBytes;
    public final String requiredBytes;
    public final String description;
    public final List<String> rejectionReasons;
    public final boolean eligible;
    public final boolean experimentCandidate;

    private StorageAssessment(long id, PtpStorageInfo info, BigInteger required, List<String> reasons,
                              boolean experimentCandidate) {
        storageId = id;
        storageType = info.storageType();
        fileSystemType = info.fileSystemType();
        accessCapability = info.accessCapability();
        maxCapacityBytes = info.maxCapacity().toString();
        freeSpaceBytes = info.freeSpaceBytes().toString();
        requiredBytes = required.toString();
        description = info.description();
        rejectionReasons = Collections.unmodifiableList(new ArrayList<>(reasons));
        eligible = reasons.isEmpty();
        this.experimentCandidate = experimentCandidate;
    }

    public static StorageAssessment assess(long id, PtpStorageInfo info, BigInteger required) {
        return assess(id, info, required, false);
    }

    public static StorageAssessment assess(long id, PtpStorageInfo info, BigInteger required,
                                           boolean existingDirectoryExperiment) {
        boolean logicalMedia = id != 0 && id != 0xffffffffL && (id & 0xffffL) != 0;
        boolean directoryFileSystem = info.fileSystemType() == FILESYSTEM_GENERIC_HIERARCHICAL
                || info.fileSystemType() == FILESYSTEM_DCF;
        boolean removableRam = info.storageType() == STORAGE_REMOVABLE_RAM;
        boolean enoughSpace = info.freeSpaceBytes().compareTo(required) >= 0;
        List<String> reasons = new ArrayList<>();
        if (!logicalMedia) {
            reasons.add("存储 ID 未标识有效的逻辑介质。");
        }
        if (info.accessCapability() != ACCESS_READ_WRITE) {
            if (info.accessCapability() == 1 || info.accessCapability() == 2) {
                reasons.add("相机在 USB/PTP 下将卡报告为只读（可删除不代表可写）；"
                        + "普通模式会按只读标志停止；该标志本身不代表指定目录上传已被实际拒绝。");
            } else {
                reasons.add("访问能力为未知值 " + hex(info.accessCapability()) + "，未确认可写。");
            }
        }
        if (!directoryFileSystem) {
            reasons.add(info.fileSystemType() == 1
                    ? "文件系统为平面结构，不支持所需目录路径。"
                    : "文件系统为未知或未定义类型 " + hex(info.fileSystemType()) + "，未确认目录支持。");
        }
        if (!removableRam) {
            reasons.add("存储类型为 " + storageTypeName(info.storageType()) + "（"
                    + hex(info.storageType()) + "），不是可移动 RAM 存储卡。");
        }
        if (!enoughSpace) {
            reasons.add("可用空间不足：" + info.freeSpaceBytes() + " 字节，需要至少 " + required + " 字节。");
        }
        boolean experimentCandidate = existingDirectoryExperiment && info.accessCapability() == 2
                && logicalMedia && directoryFileSystem && removableRam && enoughSpace;
        return new StorageAssessment(id, info, required, reasons, experimentCandidate);
    }

    public String toDisplayText() {
        StringBuilder text = new StringBuilder("存储 ").append(String.format(Locale.US, "0x%08X", storageId))
                .append("：").append(description)
                .append("\nStorageType=").append(hex(storageType)).append("（").append(storageTypeName(storageType)).append("）")
                .append("，FileSystemType=").append(hex(fileSystemType)).append("（").append(fileSystemTypeName(fileSystemType)).append("）")
                .append("，AccessCapability=").append(hex(accessCapability)).append("（").append(accessCapabilityName(accessCapability)).append("）")
                .append("\n可用空间 / 所需空间（字节）：").append(freeSpaceBytes).append(" / ").append(requiredBytes);
        if (eligible) text.append("\n本地上传预检：通过；相机是否接受后续上传仍需验证。");
        if (experimentCandidate) {
            text.append("\n仅作为手动实验候选：声明权限仍为只读；本条评估不判断后续上传结果，未认定目录具有特权。");
        }
        for (String reason : rejectionReasons) {
            text.append(experimentCandidate ? "\n普通模式限制：" : "\n拒绝原因：").append(reason);
        }
        return text.toString();
    }

    public static String storageTypeName(int value) {
        switch (value) {
            case 0: return "未定义";
            case 1: return "固定 ROM";
            case 2: return "可移动 ROM";
            case 3: return "固定 RAM";
            case 4: return "可移动 RAM";
            default: return "未知存储类型";
        }
    }

    public static String fileSystemTypeName(int value) {
        switch (value) {
            case 0: return "未定义";
            case 1: return "通用平面";
            case 2: return "通用层级目录";
            case 3: return "DCF 相机目录";
            default: return "未知文件系统";
        }
    }

    public static String accessCapabilityName(int value) {
        switch (value) {
            case 0: return "读写";
            case 1: return "只读，不可删除";
            case 2: return "只读，可删除；不代表可写";
            default: return "未知访问能力";
        }
    }

    private static String hex(int value) { return String.format(Locale.US, "0x%04X", value & 0xffff); }
}

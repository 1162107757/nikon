package com.nikonpreset.usbprobe.usb;

import com.nikonpreset.usbprobe.ptp.PtpStorageInfo;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Exercises production storage eligibility against independently encoded PTP datasets. */
public final class StorageAssessmentTest {
    private static final long STORAGE = 0x00020001;
    private static final BigInteger REQUIRED = BigInteger.valueOf(8244);
    private static int checks;

    public static void main(String[] args) throws Exception {
        directoryAndDcfFilesystemsAreEligibleOnlyWhenWritable();
        flatUnknownAndUnsupportedStorageAreRejected();
        requiredSpaceBoundaryAndAllReasonsAreReported();
        experimentalCandidateRequiresExplicitChoiceAndAllOtherConditions();
        System.out.println("PASS: " + checks + " storage eligibility checks");
    }

    private static void directoryAndDcfFilesystemsAreEligibleOnlyWhenWritable() throws Exception {
        for (int fileSystem : new int[] {2, 3}) {
            StorageAssessment assessment = assess(4, fileSystem, 0, 100000);
            check(assessment.eligible, "Generic hierarchical and DCF support directory candidates");
            equal(0, assessment.rejectionReasons.size());
            equal(STORAGE, assessment.storageId);
            equal(4, assessment.storageType);
            equal(fileSystem, assessment.fileSystemType);
            equal(0, assessment.accessCapability);
            equal("100000", assessment.freeSpaceBytes);
            equal("1000000", assessment.maxCapacityBytes);
            equal("8244", assessment.requiredBytes);
            equal("SDXC test card", assessment.description);
            for (int access : new int[] {1, 2, 99}) {
                StorageAssessment readOnly = assess(4, fileSystem, access, 100000);
                check(!readOnly.eligible, "Filesystem compatibility does not grant write capability");
                equal(access, readOnly.accessCapability);
                equal(fileSystem, readOnly.fileSystemType);
                check(!readOnly.rejectionReasons.isEmpty(), "Rejected raw capability has a reason");
                String reasons = String.join(";", readOnly.rejectionReasons);
                check(reasons.contains(access == 99 ? "未知" : "只读"), "Access rejection names the actual constraint");
                if (access == 2) {
                    check(reasons.contains("可删除"), "Read-only-with-deletion is explained without granting writes");
                    String display = readOnly.toDisplayText();
                    check(display.contains("StorageType=0x0004"), "Visible assessment includes raw storage type");
                    check(display.contains(String.format(java.util.Locale.ROOT, "FileSystemType=0x%04X", fileSystem)),
                            "Visible assessment includes the real filesystem code");
                    check(display.contains("AccessCapability=0x0002"), "Visible assessment includes raw read-only-with-deletion code");
                    check(display.contains("100000 / 8244"), "Visible assessment retains byte counts");
                    for (String reason : readOnly.rejectionReasons) check(display.contains(reason), "Visible assessment retains each reason");
                }
            }
        }
    }

    private static void flatUnknownAndUnsupportedStorageAreRejected() throws Exception {
        StorageAssessment flat = assess(4, 1, 0, 100000);
        check(!flat.eligible, "A flat filesystem cannot host NIKON/CUSTOMPC directories");
        equal(1, flat.fileSystemType);
        check(String.join(";", flat.rejectionReasons).contains("平面"), "Flat filesystem rejection is explicit");
        for (int unknown : new int[] {0, 99, 0xffff}) {
            StorageAssessment filesystem = assess(4, unknown, 0, 100000);
            check(!filesystem.eligible, "Unknown filesystem is never assumed directory-capable");
            equal(unknown, filesystem.fileSystemType);
            check(String.join(";", filesystem.rejectionReasons).contains("未知"), "Unknown filesystem is identified");
            StorageAssessment storage = assess(unknown, 3, 0, 100000);
            check(!storage.eligible, "Unknown storage type is not treated as removable writable media");
            equal(unknown, storage.storageType);
            String storageReason = String.join(";", storage.rejectionReasons);
            check(storageReason.contains(unknown == 0 ? "未定义" : "未知"), "Unknown or undefined storage type is identified precisely");
        }
        for (int unsupported : new int[] {1, 2, 3}) {
            StorageAssessment storage = assess(unsupported, 3, 0, 100000);
            check(!storage.eligible, "Non-removable-RAM storage guard remains enforced");
            equal(unsupported, storage.storageType);
            check(!storage.rejectionReasons.isEmpty(), "Known unsupported storage type has a reason");
        }
    }

    private static void requiredSpaceBoundaryAndAllReasonsAreReported() throws Exception {
        StorageAssessment exact = assess(4, 3, 0, 8244);
        check(exact.eligible, "Exact required byte count is sufficient");
        StorageAssessment shortByOne = assess(4, 3, 0, 8243);
        check(!shortByOne.eligible, "One byte below the required reserve is rejected");
        equal("8243", shortByOne.freeSpaceBytes);
        equal("8244", shortByOne.requiredBytes);
        check(String.join(";", shortByOne.rejectionReasons).contains("空间"), "Insufficient bytes have a space reason");
        StorageAssessment multiple = assess(99, 1, 2, 1);
        check(!multiple.eligible, "Multiple constraints remain a rejection");
        check(multiple.rejectionReasons.size() >= 4, "All four independent rejection reasons are retained");
        String reasons = String.join(";", multiple.rejectionReasons);
        for (String word : new String[] {"未知", "平面", "只读", "空间"}) {
            check(reasons.contains(word), "Multiple-constraint report includes " + word);
        }
        String display = multiple.toDisplayText();
        for (String reason : multiple.rejectionReasons) check(display.contains(reason), "The visible report preserves every constraint");
        equal(99, multiple.storageType);
        equal(1, multiple.fileSystemType);
        equal(2, multiple.accessCapability);
        equal("1", multiple.freeSpaceBytes);
    }

    private static void experimentalCandidateRequiresExplicitChoiceAndAllOtherConditions() throws Exception {
        for (int fileSystem : new int[] {2, 3}) {
            StorageAssessment normal = assess(STORAGE, 4, fileSystem, 2, 100000, false);
            check(!normal.eligible && !normal.experimentCandidate,
                    "Read-only-with-deletion is not an experimental candidate without explicit choice");
            StorageAssessment experiment = assess(STORAGE, 4, fileSystem, 2, 100000, true);
            check(!experiment.eligible && experiment.experimentCandidate,
                    "A candidate still retains its ordinary read-only rejection");
            equal(2, experiment.accessCapability);
            equal(1, experiment.rejectionReasons.size());
            check(String.join(";", experiment.rejectionReasons).contains("只读"),
                    "The experiment never rewrites the camera's access metadata");
            String display = experiment.toDisplayText();
            check(display.contains("0x0002") && display.contains("实验") && display.contains("候选"),
                    "Raw access metadata and experimental candidacy remain visible together");

            StorageAssessment writable = assess(STORAGE, 4, fileSystem, 0, 100000, true);
            check(writable.eligible && !writable.experimentCandidate,
                    "Writable cards use ordinary eligibility even when the directory experiment is chosen");
            for (int access : new int[] {1, 99, 0xffff}) {
                StorageAssessment rejected = assess(STORAGE, 4, fileSystem, access, 100000, true);
                check(!rejected.eligible && !rejected.experimentCandidate,
                        "Only access 2 is considered by the explicit exception experiment");
            }
        }
        for (int type : new int[] {0, 1, 2, 3, 99}) {
            StorageAssessment assessment = assess(STORAGE, type, 3, 2, 100000, true);
            check(!assessment.eligible && !assessment.experimentCandidate, "Storage type remains enforced in experimental mode");
        }
        for (int filesystem : new int[] {0, 1, 99}) {
            StorageAssessment assessment = assess(STORAGE, 4, filesystem, 2, 100000, true);
            check(!assessment.eligible && !assessment.experimentCandidate, "Directory filesystem remains enforced in experimental mode");
        }
        StorageAssessment enough = assess(STORAGE, 4, 3, 2, 8244, true);
        check(enough.experimentCandidate && !enough.eligible, "Exact reserve is enough for a read-only experiment candidate");
        StorageAssessment tooSmall = assess(STORAGE, 4, 3, 2, 8243, true);
        check(!tooSmall.experimentCandidate && !tooSmall.eligible, "The exception does not relax the byte reserve");
        check(tooSmall.rejectionReasons.size() >= 2, "Space and access reasons are retained independently");
        for (long id : new long[] {0, 0xffffffffL, 0x00010000}) {
            StorageAssessment invalid = assess(id, 4, 3, 2, 100000, true);
            check(!invalid.eligible && !invalid.experimentCandidate, "Invalid and unmounted IDs remain excluded");
            equal(id, invalid.storageId);
        }
    }

    private static StorageAssessment assess(int type, int fileSystem, int access, long free) throws Exception {
        return assess(STORAGE, type, fileSystem, access, free, false);
    }

    private static StorageAssessment assess(long id, int type, int fileSystem, int access, long free,
                                            boolean experiment) throws Exception {
        PtpStorageInfo info = PtpStorageInfo.parse(new Writer().u16(type).u16(fileSystem).u16(access)
                .u64(1000000).u64(free).u32(999).string("SDXC test card").string("SD").bytes());
        return StorageAssessment.assess(id, info, REQUIRED, experiment);
    }

    private static final class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Writer u16(int value) { output.write(value); output.write(value >>> 8); return this; }
        Writer u32(long value) { for (int i = 0; i < 4; i++) output.write((int) (value >>> (8 * i))); return this; }
        Writer u64(long value) { for (int i = 0; i < 8; i++) output.write((int) (value >>> (8 * i))); return this; }
        Writer string(String value) {
            output.write(value.length() + 1);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
            output.write(bytes, 0, bytes.length);
            return u16(0);
        }
        byte[] bytes() { return output.toByteArray(); }
    }

    private static void equal(Object expected, Object actual) {
        check(Objects.equals(expected, actual), "Expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

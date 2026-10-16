package com.nikonpreset.usbprobe.usb;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JVM-only facade for the Android outer class. Tests compile the real production
 * NcpTransferSession, but do not test Android lifecycle, JSON reports or USB I/O.
 */
public final class UsbNcpTransfer {
    public static final int MAX_NCP_BYTES = 1024 * 1024;
    private UsbNcpTransfer() {}

    static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder output = new StringBuilder(64);
            for (byte value : digest) output.append(String.format(Locale.US, "%02x", value & 255));
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable", error);
        }
    }

    public static final class Result {
        public String manufacturer = "";
        public String model = "";
        public String firmware = "";
        public long storageId;
        public String storageDescription = "";
        public String freeSpaceBytes = "";
        public String transferMode = "normal";
        public boolean existingDirectoryExperimentRequested;
        public boolean readOnlyExceptionSelected;
        public boolean readOnlyExceptionAttempted;
        public boolean existingPathVerified;
        public int selectedAccessCapability = -1;
        public int storageCount;
        public int availableStorageCount;
        public int unavailableStorageCount;
        public final List<Long> unavailableStorageIds = new ArrayList<>();
        public final List<Long> unmountedStorageIds = new ArrayList<>();
        public String lastOperation = "";
        public long lastTransactionId = -1;
        public String lastStorageId = "";
        public String phase = "";
        public String failurePhase = "";
        public String lastPtpOperation = "";
        public String lastPtpStorageId = "";
        public long lastPtpTransactionId = -1;
        public String failedOperation = "";
        public String failedStorageId = "";
        public long failedTransactionId = -1;
        public final List<StorageAssessment> storageAssessment = new ArrayList<>();
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
        public String error = "";
        public final List<String> changes = new ArrayList<>();
        public final List<String> diagnostics = new ArrayList<>();
        public final List<String> commandsSent = new ArrayList<>();
    }
}

package cc.huevia.ptp;

import java.math.BigInteger;
import java.util.Optional;

/** Standard storage metadata; UINT64 capacities remain unsigned via BigInteger. */
public final class PtpStorageInfo {
    private final int storageType;
    private final int fileSystemType;
    private final int accessCapability;
    private final BigInteger maxCapacity;
    private final BigInteger freeSpaceBytes;
    private final long freeSpaceImages;
    private final String description;
    private final String volumeLabel;

    private PtpStorageInfo(PtpDatasetReader reader) throws PtpProtocolException {
        storageType = reader.uint16();
        fileSystemType = reader.uint16();
        accessCapability = reader.uint16();
        maxCapacity = reader.uint64();
        freeSpaceBytes = reader.uint64();
        freeSpaceImages = reader.uint32();
        description = reader.string();
        volumeLabel = reader.string();
        reader.requireEnd();
    }

    public static PtpStorageInfo parse(byte[] dataset) throws PtpProtocolException {
        return new PtpStorageInfo(new PtpDatasetReader(dataset));
    }

    public int storageType() { return storageType; }
    public int fileSystemType() { return fileSystemType; }
    public int accessCapability() { return accessCapability; }
    public BigInteger maxCapacity() { return maxCapacity; }
    public BigInteger freeSpaceBytes() { return freeSpaceBytes; }
    public long freeSpaceImages() { return freeSpaceImages; }
    public String description() { return description; }
    public String volumeLabel() { return volumeLabel; }

    /** Empty means a reserved/vendor access value; never infer that it is writable. */
    public Optional<Boolean> readOnly() {
        if (accessCapability == 0) return Optional.of(false);
        if (accessCapability == 1 || accessCapability == 2) return Optional.of(true);
        return Optional.empty();
    }
}

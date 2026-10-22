package cc.huevia.album;

import java.io.File;

/** A verified, completed NEF original. This is never a rendered preset effect. */
public final class RawPhotoDownload {
    private final File file;
    private final String filename, sha256, sourceIdentity;
    private final long byteSize, savedAt;

    RawPhotoDownload(File file, String filename, long byteSize, String sha256, long savedAt, String sourceIdentity) {
        this.file = file; this.filename = filename; this.byteSize = byteSize;
        this.sha256 = sha256; this.savedAt = savedAt; this.sourceIdentity = sourceIdentity;
    }

    public File file() { return file; }
    public String filename() { return filename; }
    public long byteSize() { return byteSize; }
    public String sha256() { return sha256; }
    public long savedAt() { return savedAt; }
    public String sourceIdentity() { return sourceIdentity; }
}

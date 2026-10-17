package com.nikonpreset.usbprobe.album;

import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;

/** One immutable JPEG entry, scoped to the wireless session which enumerated it. */
public final class PhotoItem {
    final String session;
    private final long handle;
    private final PtpObjectInfo info;
    PhotoItem(String session, long handle, PtpObjectInfo info) { this.session = session; this.handle = handle; this.info = info; }
    public long handle() { return handle; }
    public long storageId() { return info.storageId(); }
    public String filename() { return info.filename(); }
    public long byteSize() { return info.compressedSize(); }
    public long thumbnailByteSize() { return info.thumbCompressedSize(); }
    public long thumbnailWidth() { return info.thumbPixWidth(); }
    public long thumbnailHeight() { return info.thumbPixHeight(); }
    public long width() { return info.imagePixWidth(); }
    public long height() { return info.imagePixHeight(); }
    public String captureDate() { return info.captureDate(); }
}

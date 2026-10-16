package com.nikonpreset.usbprobe.album;

import java.io.File;

/** A completed JPEG in this app's private picture directory, not yet a MediaStore export. */
public final class DownloadedPhoto {
    private final File file;
    private final String filename;
    DownloadedPhoto(File file, String filename) { this.file = file; this.filename = filename; }
    public File file() { return file; }
    public String filename() { return filename; }
    public long byteSize() { return file.length(); }
    public long downloadedAt() { return file.lastModified(); }
}

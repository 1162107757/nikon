package com.nikonpreset.usbprobe.album;

import java.io.File;

/** A completed JPEG in this app's private picture directory, not yet a MediaStore export. */
public final class DownloadedPhoto {
    private final File file;
    private final String filename;
    private final String projectId, projectName;
    private final long photoDate;
    DownloadedPhoto(File file, String filename) { this(file, filename, "", ""); }
    DownloadedPhoto(File file, String filename, String projectId, String projectName) {
        this(file, filename, projectId, projectName, file == null ? 0 : file.lastModified());
    }
    DownloadedPhoto(File file, String filename, String projectId, String projectName, long photoDate) {
        this.file = file; this.filename = filename;
        this.projectId = projectId == null ? "" : projectId; this.projectName = projectName == null ? "" : projectName;
        this.photoDate = Math.max(0, photoDate);
    }
    public File file() { return file; }
    public String filename() { return filename; }
    public long byteSize() { return file.length(); }
    public long downloadedAt() { return file.lastModified(); }
    public String projectId() { return projectId; }
    public String projectName() { return projectName; }
    /** Already resolved on a local worker; reading a date on the UI never opens EXIF. */
    public long photoDate() { return photoDate; }
    public DownloadedPhoto withPhotoDate(long date) {
        return new DownloadedPhoto(file, filename, projectId, projectName, date);
    }
}

package com.nikonpreset.usbprobe.album;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/** A completed camera-generated preview JPEG. Keep it open while displaying it, then close it. */
public final class PhotoPreview implements AutoCloseable {
    private final File file;
    private final String filename;
    private final Runnable release;
    private final boolean largeThumbnail;
    private final AtomicBoolean closed = new AtomicBoolean();

    PhotoPreview(File file, String filename, Runnable release) {
        this(file, filename, false, release);
    }

    PhotoPreview(File file, String filename, boolean largeThumbnail, Runnable release) {
        this.file = file;
        this.filename = filename;
        this.largeThumbnail = largeThumbnail;
        this.release = release;
    }

    public File file() { return file; }
    public String filename() { return filename; }
    public long byteSize() { return file.length(); }
    public boolean isLargeThumbnail() { return largeThumbnail; }
    @Override public void close() { if (closed.compareAndSet(false, true)) release.run(); }
}

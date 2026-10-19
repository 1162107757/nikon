package com.nikonpreset.usbprobe.album;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transferable reservations: queued saving/processing and deletion cannot overtake one another. */
final class PhotoLocalTasks {
    private static int users;
    private static boolean deleting;
    static synchronized Lease use() throws IOException {
        if (deleting) throw new IOException("正在删除本地照片，请稍后重试。");
        users++; return new Lease(false);
    }
    static synchronized Lease delete() throws IOException {
        if (deleting || users != 0) throw new IOException("照片正在接收、保存或处理，请完成后再删除。");
        deleting = true; return new Lease(true);
    }
    static final class Lease implements AutoCloseable {
        private final boolean exclusive;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Lease(boolean exclusive) { this.exclusive = exclusive; }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (PhotoLocalTasks.class) { if (exclusive) deleting = false; else users--; }
        }
    }
}

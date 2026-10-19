package com.nikonpreset.usbprobe.album;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Application-owned star alias publication; camera reception and Activity lifetime are independent. */
public final class PhotoStars {
    private static PhotoStars instance;
    private final Context application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "photo-star-publication"); thread.setDaemon(true); return thread;
    });
    private final Set<Runnable> observers = new LinkedHashSet<>();
    private volatile String error = "";

    public static synchronized PhotoStars get(Context context) {
        if (context == null) throw new IllegalArgumentException("application context");
        if (instance == null) {
            Context application = context.getApplicationContext();
            if (application == null) throw new IllegalArgumentException("application context unavailable");
            instance = new PhotoStars(application);
        }
        return instance;
    }

    private PhotoStars(Context application) { this.application = application; }

    /** Enqueue metadata only. Even malformed input or a failed journal cannot invalidate the original. */
    public void bindDownloaded(String identity, DownloadedPhoto photo) {
        try {
            worker.execute(() -> {
                try {
                    String remote = PhotoStarStore.remoteKey(identity);
                    if (photo == null || photo.file() == null) throw new IllegalArgumentException("原图文件信息尚未就绪。");
                    String local = PhotoStarStore.localKey(photo.file());
                    // UI writers own separate cached stores; a retained store here would miss their changes.
                    PhotoStarStore store = new PhotoStarStore(new File(application.getFilesDir(), "photo-stars"));
                    Set<String> latest = store.snapshot();
                    if (!latest.contains(remote) && !latest.contains(local)) {
                        if (!error.isEmpty()) { error = ""; changed(); }
                        return;
                    }
                    store.bindLocal(remote, local);
                    error = "";
                    changed(); // Only after the durable alias is committed; new UIs can now reload it safely.
                } catch (Exception failure) { failed(failure.getMessage()); }
                catch (OutOfMemoryError memory) { failed("星标同步内存不足，原图和已有星标仍保留。"); }
            });
        } catch (RuntimeException unavailable) { failed(unavailable.getMessage()); }
    }

    public String error() { return error; }
    public synchronized void addObserver(Runnable observer) { if (observer != null) observers.add(observer); }
    public synchronized void removeObserver(Runnable observer) { observers.remove(observer); }

    private void failed(String detail) {
        String message = detail == null ? "星标同步未完成，原图和已有星标仍保留。" : detail.trim();
        if (message.isEmpty()) message = "星标同步未完成，原图和已有星标仍保留。";
        error = message.length() > 2000 ? message.substring(0, 2000) : message;
        changed();
    }

    private void changed() {
        main.post(() -> {
            List<Runnable> current;
            synchronized (this) { current = new ArrayList<>(observers); }
            for (Runnable observer : current) try { observer.run(); } catch (RuntimeException ignored) { }
        });
    }
}

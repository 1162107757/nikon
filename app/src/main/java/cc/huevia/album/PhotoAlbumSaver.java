package cc.huevia.album;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Application-owned local saving queue. No camera session or Activity is retained. */
public final class PhotoAlbumSaver {
    private static PhotoAlbumSaver instance;
    private static final int MAX_PENDING = 64, MAX_WAITERS = 64;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "photo-album-save"); thread.setDaemon(true); return thread;
    });
    private final PhotoLiveGallery gallery;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Set<Runnable> observers = new LinkedHashSet<>();

    private static final class Pending {
        final DownloadedPhoto photo;
        final PhotoLocalTasks.Lease lease;
        final List<AlbumController.Callback<String>> callbacks = new ArrayList<>();
        Pending(DownloadedPhoto photo, PhotoLocalTasks.Lease lease) { this.photo = photo; this.lease = lease; }
    }

    public static synchronized PhotoAlbumSaver get(Context context) {
        if (instance == null) instance = new PhotoAlbumSaver(context);
        return instance;
    }

    private PhotoAlbumSaver(Context context) { gallery = new PhotoLiveGallery(context); }

    public synchronized boolean isBusy() { return !pending.isEmpty(); }
    public synchronized void addObserver(Runnable observer) { if (observer != null) observers.add(observer); }
    public synchronized void removeObserver(Runnable observer) { observers.remove(observer); }

    /** Status lookups share the local worker, so UI rendering never hashes originals or queries MediaStore. */
    public void queryStatus(DownloadedPhoto photo, AlbumController.Callback<PhotoLiveGallery.PublicationState> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        try { worker.execute(() -> {
            PhotoLiveGallery.PublicationState state = gallery.publicationStatus(photo);
            main.post(() -> callback.onSuccess(state));
        }); } catch (RejectedExecutionException failure) { fail(callback, failure); }
    }

    /** Concurrent requests for one immutable source share one publication and all receive a main-thread result. */
    public void save(DownloadedPhoto photo, AlbumController.Callback<String> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        final String key;
        try {
            if (photo == null || photo.file() == null) throw new IOException("原图信息无效。");
            key = photo.file().getCanonicalPath();
        } catch (IOException | RuntimeException failure) { fail(callback, failure); return; }
        Pending request;
        synchronized (this) {
            request = pending.get(key);
            if (request != null) {
                if (request.callbacks.size() >= MAX_WAITERS) fail(callback, new IOException("这张照片正在保存，请稍后重试。"));
                else request.callbacks.add(callback);
                return;
            }
            if (pending.size() >= MAX_PENDING) { fail(callback, new IOException("照片保存队列已满，请稍后重试。")); return; }
            PhotoLocalTasks.Lease lease;
            try { lease = PhotoLocalTasks.use(); }
            catch (IOException busy) { fail(callback, busy); return; }
            request = new Pending(photo, lease); request.callbacks.add(callback); pending.put(key, request);
        }
        changed();
        Pending queued = request;
        try { worker.execute(() -> publish(key, queued)); }
        catch (RejectedExecutionException failure) { complete(key, queued, "", failure); }
    }

    private void publish(String key, Pending request) {
        String uri = ""; Exception failure = null; boolean success = false;
        try { uri = gallery.publish(request.photo); success = true; }
        catch (Exception problem) { failure = problem; }
        catch (OutOfMemoryError memory) { failure = new IOException("系统相册保存内存不足，应用内原图已保留，请重试。", memory); }
        finally {
            if (!success && failure == null) failure = new IOException("系统相册保存已中断，应用内原图已保留。");
            complete(key, request, uri, failure);
        }
    }

    private void complete(String key, Pending request, String uri, Exception failure) {
        List<AlbumController.Callback<String>> callbacks;
        synchronized (this) {
            if (pending.get(key) != request) return;
            pending.remove(key); callbacks = new ArrayList<>(request.callbacks);
        }
        request.lease.close();
        changed();
        for (AlbumController.Callback<String> callback : callbacks) {
            if (failure != null) fail(callback, failure);
            else main.post(() -> callback.onSuccess(uri == null ? "" : uri));
        }
    }

    private <T> void fail(AlbumController.Callback<T> callback, Exception failure) {
        String message = failure.getMessage();
        String error = message == null || message.isEmpty() ? "系统相册保存未完成，原图仍保留在应用内。" : message;
        main.post(() -> callback.onError(error));
    }

    private void changed() {
        main.post(() -> {
            List<Runnable> snapshot;
            synchronized (this) { snapshot = new ArrayList<>(observers); }
            for (Runnable observer : snapshot) observer.run();
        });
    }
}

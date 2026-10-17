package com.nikonpreset.usbprobe.album;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import com.nikonpreset.usbprobe.ptp.PtpDeviceInfo;
import com.nikonpreset.usbprobe.ptp.PtpObjectHandles;
import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import com.nikonpreset.usbprobe.ptp.PtpResponseException;
import com.nikonpreset.usbprobe.ptp.PtpStorageIds;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Serialized asynchronous wireless browsing. Callbacks run on the main thread. */
public final class AlbumController implements AutoCloseable {
    public static final int DEFAULT_PORT = 15740;
    public interface Callback<T> { void onSuccess(T result); void onError(String message); }
    public interface DownloadCallback extends Callback<DownloadedPhoto> { void onProgress(long received, long total); }
    public interface PreviewCallback extends Callback<PhotoPreview> { void onProgress(long received, long total); }
    private interface Work<T> { T run() throws Exception; }
    private static final int MAX_HANDLES = 20000;
    private static final long MAX_PHOTO_BYTES = 512L * 1024 * 1024;
    private static final int MAX_PREVIEW_BYTES = 4 * 1024 * 1024;
    private final Context context;
    private final CameraWifiNetwork wifi;
    private final PhotoPreviewCache previews;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger previewGeneration = new AtomicInteger();
    private final Object lifecycle = new Object();
    private volatile PtpIpClient active;
    private volatile boolean closed;
    private volatile boolean transferring;
    private volatile boolean connecting;
    private volatile Runnable connectionListener;
    private volatile Runnable lifecycleObserver;
    private volatile ConnectionInfo cachedConnectionInfo;
    private long[] handles = new long[0]; // Worker-owned snapshot.
    private String session = "";
    private PtpDeviceInfo deviceInfo;
    private boolean largeThumbnailUnavailable; // Worker-owned, reset for each new camera session.

    public AlbumController(Context context) {
        this.context = context.getApplicationContext();
        wifi = new CameraWifiNetwork(this.context);
        previews = new PhotoPreviewCache(new File(this.context.getCacheDir(), "camera-album-preview"), 2,
                16L * 1024 * 1024);
    }

    /** A suggestion only. The UI must allow editing and confirming the camera address. */
    public String suggestHost() {
        return wifi.suggestHost();
    }

    public void connect(String host, int port, Callback<ConnectionInfo> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        disconnect();
        final int connectionGeneration = generation.get();
        synchronized (lifecycle) {
            if (closed) { main.post(() -> callback.onError("相册已关闭，未开始连接。")); return; }
            connecting = true;
        }
        notifyStateChanged();
        submit(connectionGeneration, callback, () -> {
            try {
            PtpIpClient client = new PtpIpClient(wifi.connector(host.trim(), port));
            client.setOnClosed(() -> deliver(connectionGeneration, () -> {
                if (active == client) {
                    cachedConnectionInfo = null;
                    notifyStateChanged();
                }
            }));
            synchronized (lifecycle) {
                if (closed || connectionGeneration != generation.get()) { client.close(); throw new IOException("无线连接已取消。"); }
                active = client;
            }
            try {
                byte[] info = client.connect(host.trim(), port, wifi.clientGuid(), "Preset Album");
                deviceInfo = PtpDeviceInfo.parseRedacted(info);
                largeThumbnailUnavailable = false;
                if (!deviceInfo.manufacturer().toLowerCase(Locale.ROOT).contains("nikon")) {
                    throw new IOException("这不是已支持的尼康无线相机。");
                }
                for (int op : new int[] {0x1004, 0x1007, 0x1008, 0x1009}) if (!deviceInfo.supportsOperation(op)) {
                    throw new IOException("相机未提供当前相册需要的照片读取能力。");
                }
                LinkedHashSet<Long> collected = new LinkedHashSet<>();
                long[] storages = PtpStorageIds.parse(client.getData(0x1004, 4096));
                if (storages.length > 64) throw new IOException("相机存储空间数量超过浏览上限。");
                for (long storage : storages) {
                    byte[] entries;
                    try {
                        try { entries = client.getData(0x1007, 1024 * 1024, storage, 0x3801, 0); }
                        catch (PtpResponseException unsupportedFormat) {
                            if (unsupportedFormat.responseCode() != 0x2014) throw unsupportedFormat;
                            entries = client.getData(0x1007, 1024 * 1024, storage, 0, 0);
                        }
                    } catch (PtpResponseException storageError) {
                        if (storageError.responseCode() == 0x2013) continue;
                        throw storageError;
                    }
                    for (long handle : PtpObjectHandles.parse(entries)) {
                        if (handle == 0 || handle == 0xffffffffL) throw new IOException("相机返回了无效照片索引。");
                        collected.add(handle);
                        if (collected.size() > MAX_HANDLES) throw new IOException("相机对象过多，本版一次最多浏览 20000 个对象。");
                    }
                }
                handles = new long[collected.size()]; int i = 0;
                for (long handle : collected) handles[i++] = handle;
                session = UUID.randomUUID().toString();
                ConnectionInfo connected = new ConnectionInfo(deviceInfo.model(), host.trim(), port, handles.length);
                synchronized (lifecycle) {
                    if (closed || connectionGeneration != generation.get() || active != client || !client.isOpen()) {
                        throw new IOException("无线连接已取消或相机已断开。");
                    }
                    cachedConnectionInfo = connected;
                }
                return connected;
            } catch (Exception error) { client.close(); throw error; }
            } finally {
                synchronized (lifecycle) {
                    if (connectionGeneration == generation.get()) connecting = false;
                }
                notifyStateChanged();
            }
        });
    }

    public boolean isConnected() {
        PtpIpClient client = active;
        return !closed && !connecting && !transferring && cachedConnectionInfo != null && client != null && client.isOpen();
    }
    public boolean isConnecting() { return !closed && connecting; }

    /** Complete metadata for the current owned session; no network query or reconnect. */
    public ConnectionInfo connectionInfo() { return isConnected() ? cachedConnectionInfo : null; }

    /** Application lifecycle notification is independent of the currently attached UI. */
    public void setLifecycleObserver(Runnable observer) { lifecycleObserver = observer; }

    /** One bounded read on the existing album worker; cancellation still delivers its completion. */
    public void keepAlive(Callback<Void> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        final int request = generation.get();
        if (closed || transferring) {
            main.post(() -> callback.onError("相册连接已关闭或正在移交，未重新连接。"));
            return;
        }
        try {
            worker.execute(() -> {
                Exception failure = null;
                PtpIpClient operationClient = null;
                try {
                    if (closed || transferring || request != generation.get()) {
                        throw new IOException("相册连接已切换，未发送保活请求。");
                    }
                    PtpIpClient client = requireClient();
                    operationClient = client;
                    if (cachedConnectionInfo == null || deviceInfo == null || !deviceInfo.supportsOperation(0x1004)) {
                        throw new IOException("相册连接尚未完整建立，未发送保活请求。");
                    }
                    long[] storages = PtpStorageIds.parse(client.getData(0x1004, 4096));
                    if (storages.length > 64) throw new IOException("相机存储空间数量超过浏览上限。");
                } catch (Exception error) {
                    failure = error;
                    boolean terminate = false;
                    if (!(error instanceof PtpResponseException) && operationClient != null) {
                        synchronized (lifecycle) {
                            if (!closed && !transferring && request == generation.get() && active == operationClient) {
                                cachedConnectionInfo = null; connecting = false;
                                terminate = true;
                            }
                        }
                    }
                    if (terminate) { operationClient.close(); notifyStateChanged(); }
                }
                final Exception error = failure;
                main.post(() -> {
                    if (error != null) {
                        String message = error.getMessage();
                        callback.onError(message == null ? "相册只读保活未完成。" : message);
                    } else if (closed || transferring || request != generation.get() || !isConnected()) {
                        callback.onError("相册连接已切换，未重新连接。");
                    } else callback.onSuccess(null);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            main.post(() -> callback.onError("相册已关闭，未发送保活请求。"));
        }
    }

    /** Drains an in-flight read before moving the owned connection; never disconnects/reconnects. */
    public void transferForPictureControl(Callback<PtpIpClient> callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        final int request;
        synchronized (lifecycle) {
            if (closed || connecting || transferring || active == null || !active.isOpen()) {
                main.post(() -> callback.onError("当前相册连接不可移交，请重新连接相机。"));
                return;
            }
            transferring = true;
            previewGeneration.incrementAndGet();
            request = generation.incrementAndGet();
        }
        notifyStateChanged();
        try {
            worker.execute(() -> {
                PtpIpClient transferred = null;
                try {
                    synchronized (lifecycle) {
                        if (closed || request != generation.get()) return;
                        PtpIpClient source = active;
                        if (source == null || !source.isOpen()) throw new IOException("相册连接已断开，未重新连接。");
                        transferred = source.transferToPictureControl();
                        active = null;
                        cachedConnectionInfo = null;
                        handles = new long[0]; session = ""; deviceInfo = null;
                        transferring = false;
                    }
                    final PtpIpClient owned = transferred;
                    main.post(() -> {
                        if (closed || request != generation.get()) owned.close();
                        else {
                            try { callback.onSuccess(owned); }
                            catch (RuntimeException failure) { owned.close(); throw failure; }
                        }
                    });
                    notifyStateChanged();
                } catch (Exception failure) {
                    if (transferred != null) transferred.close();
                    synchronized (lifecycle) { if (request == generation.get()) transferring = false; }
                    notifyStateChanged();
                    String message = failure.getMessage();
                    deliver(request, () -> callback.onError(message == null ? "无法移交相册连接，未开始导入。" : message));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            synchronized (lifecycle) { if (request == generation.get()) transferring = false; }
            notifyStateChanged();
            callback.onError("相册已关闭，未开始导入。");
        }
    }
    /** Main-thread state notification for the currently attached UI only. */
    public void setConnectionListener(Runnable listener) {
        synchronized (lifecycle) { connectionListener = listener; }
    }

    /** An obsolete panel must not detach a newer panel's listener. */
    public boolean clearConnectionListener(Runnable expected) {
        synchronized (lifecycle) {
            if (connectionListener != expected) return false;
            connectionListener = null;
            return true;
        }
    }

    private void notifyStateChanged() {
        main.post(() -> {
            Runnable observer = lifecycleObserver;
            if (observer != null) try { observer.run(); } catch (RuntimeException ignored) { }
            Runnable listener = closed ? null : connectionListener;
            if (listener != null) try { listener.run(); } catch (RuntimeException ignored) { }
        });
    }

    /** nextOffset is a snapshot cursor, not a camera-side page number. */
    public void loadPage(int offset, int limit, Callback<PhotoPage> callback) {
        final int requestedGeneration = generation.get();
        submit(callback, () -> {
            PtpIpClient client = requireClient();
            if (offset < 0 || offset > handles.length || limit < 1 || limit > 60) throw new IOException("照片分页参数无效。");
            List<PhotoItem> photos = new ArrayList<>();
            int cursor = offset, scanned = 0;
            while (cursor < handles.length && photos.size() < limit && scanned++ < 300) {
                if (requestedGeneration != generation.get()) throw new IOException("照片读取已交还相机连接。");
                long handle = handles[cursor++];
                PtpObjectInfo info;
                try { info = PtpObjectInfo.parse(client.getData(0x1008, 65536, handle)); }
                catch (PtpResponseException error) { if (error.responseCode() == 0x2009) continue; throw error; }
                if (info.objectFormat() == 0x3801) photos.add(new PhotoItem(session, handle, info));
            }
            return new PhotoPage(photos, cursor, handles.length);
        });
    }

    public void loadThumbnail(PhotoItem photo, Callback<byte[]> callback) {
        submit(callback, () -> {
            return readBestThumbnail(photo).bytes;
        });
    }

    /** Prefers advertised Nikon GetLargeThumb, with GetThumb fallback; never requests the original. */
    public void loadPreview(PhotoItem photo, PreviewCallback callback) {
        if (callback == null) throw new IllegalArgumentException("callback");
        final int requestedGeneration = generation.get();
        final int previewRequest = previewGeneration.incrementAndGet();
        PreviewCallback current = new PreviewCallback() {
            @Override public void onSuccess(PhotoPreview result) {
                if (previewRequest == previewGeneration.get()) callback.onSuccess(result);
                else result.close();
            }
            @Override public void onError(String message) {
                if (previewRequest == previewGeneration.get()) callback.onError(message);
            }
            @Override public void onProgress(long received, long total) {
                if (previewRequest == previewGeneration.get()) callback.onProgress(received, total);
            }
        };
        submit(requestedGeneration, current, () -> {
            if (previewRequest != previewGeneration.get()) throw new IOException("照片预览已取消。");
            PhotoPreview cached = previews.acquire(photo);
            if (cached != null) return cached;
            CameraThumbnail thumbnail = readBestThumbnail(photo);
            byte[] jpeg = thumbnail.bytes;
            File temporary = previews.createTemporary();
            try {
                try (FileOutputStream file = new FileOutputStream(temporary)) {
                    file.write(jpeg);
                    file.getFD().sync();
                }
                deliver(requestedGeneration, () -> current.onProgress(jpeg.length, jpeg.length));
                return previews.publish(photo, temporary, thumbnail.large);
            } finally { previews.discardTemporary(temporary); }
        });
    }

    /** Cancels queued/display callbacks; an in-flight PTP read finishes to keep the session usable. */
    public void cancelPreview() { previewGeneration.incrementAndGet(); }

    public void download(PhotoItem photo, DownloadCallback callback) {
        final int requestedGeneration = generation.get();
        submit(requestedGeneration, callback, () -> {
            if (photo == null) throw new IOException("照片信息无效，请刷新相机照片列表。");
            if (photo.byteSize() > MAX_PHOTO_BYTES) throw new IOException("照片超过本版 512 MB 下载上限。");
            PtpIpClient client = requirePhoto(photo);
            File directory = downloadDirectory();
            String display = safeJpegName(photo.filename());
            File destination = new File(directory, System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + "__" + display);
            File temporary = File.createTempFile("receiving-", ".part", directory);
            boolean completed = false;
            try {
                final long[] last = {0};
                try (FileOutputStream file = new FileOutputStream(temporary)) {
                    client.readTo(0x1009, file, MAX_PHOTO_BYTES, photo.byteSize(), (received, total) -> {
                        long now = System.nanoTime();
                        if (received == total || now - last[0] >= 200000000L) {
                            last[0] = now;
                            deliver(requestedGeneration, () -> callback.onProgress(received, total));
                        }
                    }, photo.handle());
                    file.getFD().sync();
                }
                PhotoPreviewCache.validateJpeg(temporary, photo.byteSize() > 0 ? photo.byteSize() : temporary.length());
                if (destination.exists() || !temporary.renameTo(destination)) throw new IOException("照片已接收，但本机保存未完成。");
                completed = true;
                return new DownloadedPhoto(destination, display);
            } finally {
                if (!completed && temporary.exists()) temporary.delete();
            }
        });
    }

    public void listDownloads(Callback<List<DownloadedPhoto>> callback) {
        submit(callback, () -> {
            File[] files = downloadDirectory().listFiles(file -> file.isFile() && jpegFilename(file.getName()));
            if (files == null) throw new IOException("无法读取已下载照片目录。");
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            List<DownloadedPhoto> photos = new ArrayList<>();
            for (File file : files) {
                String name = file.getName(); int marker = name.indexOf("__");
                photos.add(new DownloadedPhoto(file, marker < 0 ? name : name.substring(marker + 2)));
            }
            return Collections.unmodifiableList(photos);
        });
    }

    /** Disconnect cancels network activity; queued callbacks from the old generation are suppressed. */
    public void disconnect() {
        cancelPreview();
        generation.incrementAndGet();
        synchronized (lifecycle) {
            transferring = false; connecting = false; cachedConnectionInfo = null;
            PtpIpClient client = active; active = null; if (client != null) client.close();
        }
        notifyStateChanged();
    }
    @Override public void close() { closed = true; disconnect(); worker.shutdownNow(); previews.close(); }

    private <T> void submit(Callback<T> callback, Work<T> work) {
        submit(generation.get(), callback, work);
    }
    private <T> void submit(int request, Callback<T> callback, Work<T> work) {
        if (callback == null) throw new IllegalArgumentException("callback");
        if (closed || transferring) return;
        try {
            worker.execute(() -> {
                if (closed || request != generation.get()) return;
                try {
                    T result = work.run();
                    if (closed) discard(result);
                    else main.post(() -> {
                        if (!closed && request == generation.get()) callback.onSuccess(result);
                        else discard(result);
                    });
                }
                catch (Exception error) {
                    String message = error instanceof PtpResponseException
                            ? "相机未完成照片读取（" + String.format(Locale.ROOT, "%04X", ((PtpResponseException) error).responseCode()) + "），请检查相机状态。"
                            : error.getMessage();
                    deliver(request, () -> callback.onError(message == null ? "无线相册操作未完成。" : message));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private void deliver(int request, Runnable callback) { if (!closed) main.post(() -> { if (!closed && request == generation.get()) callback.run(); }); }
    private static void discard(Object result) { if (result instanceof PhotoPreview) ((PhotoPreview) result).close(); }
    private PtpIpClient requireClient() throws IOException { PtpIpClient c = active; if (c == null || !c.isOpen()) throw new IOException("无线连接已断开，请重新连接相机。"); return c; }
    private PtpIpClient requirePhoto(PhotoItem photo) throws IOException {
        PtpIpClient c = requireClient();
        if (photo == null || !session.equals(photo.session)) throw new IOException("照片来自旧的连接，请刷新列表后再操作。");
        return c;
    }
    private File downloadDirectory() throws IOException {
        File parent = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (parent == null) parent = new File(context.getFilesDir(), "pictures");
        File directory = new File(parent, "camera-album");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建本机照片目录。");
        return directory;
    }
    private byte[] readThumbnail(PhotoItem photo) throws IOException {
        PtpIpClient client = requirePhoto(photo);
        if (!deviceInfo.supportsOperation(0x100a)) {
            throw new IOException("相机未提供缩略图读取能力，可选择下载原图后查看。");
        }
        byte[] jpeg = client.getData(0x100a, MAX_PREVIEW_BYTES, photo.handle());
        if (!completeJpeg(jpeg)) {
            throw new IOException("相机没有返回完整的 JPEG 缩略图，可选择下载原图后查看。");
        }
        return jpeg;
    }

    private CameraThumbnail readBestThumbnail(PhotoItem photo) throws IOException {
        PtpIpClient client = requirePhoto(photo);
        if (deviceInfo.supportsOperation(0x90c4) && !largeThumbnailUnavailable) {
            try {
                byte[] large = client.getLargeThumbnail(photo.handle());
                if (completeJpeg(large)) return new CameraThumbnail(large, true);
                throw new IOException("相机返回的高清 JPEG 预览不完整，请重试预览或下载原图。");
            } catch (PtpResponseException response) {
                int code = response.responseCode();
                if (code != 0x2005 && code != 0x2010) throw response;
                // Do not repeatedly ask a camera which explicitly rejects the advertised operation.
                if (code == 0x2005) largeThumbnailUnavailable = true;
            }
        }
        return new CameraThumbnail(readThumbnail(photo), false);
    }

    private static boolean completeJpeg(byte[] jpeg) {
        return jpeg.length >= 4 && (jpeg[0] & 255) == 255 && (jpeg[1] & 255) == 216
                && (jpeg[jpeg.length - 2] & 255) == 255 && (jpeg[jpeg.length - 1] & 255) == 217;
    }

    private static final class CameraThumbnail {
        final byte[] bytes;
        final boolean large;
        CameraThumbnail(byte[] bytes, boolean large) { this.bytes = bytes; this.large = large; }
    }
    private static String safeJpegName(String name) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < name.length() && safe.length() < 100; i++) {
            char c = name.charAt(i); safe.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        if (safe.length() == 0) safe.append("photo");
        String result = safe.toString();
        if (!jpegFilename(result)) result += ".jpg";
        return result;
    }
    private static boolean jpegFilename(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }
}

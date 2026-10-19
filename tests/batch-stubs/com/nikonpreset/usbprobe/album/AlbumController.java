package com.nikonpreset.usbprobe.album;
/** Host-only acquisition boundary. No sockets or Android lifecycle behavior is simulated. */
public final class AlbumController {
    public interface Callback<T> { void onSuccess(T result); void onError(String message); }
    public interface DownloadCallback extends Callback<DownloadedPhoto> { void onProgress(long received, long total); }
    public volatile boolean connected = true;
    public volatile int downloads;
    public volatile DownloadCallback pending;
    public void download(PhotoItem photo, DownloadCallback callback) { downloads++; pending = callback; }
    public boolean hasLiveSession() { return connected; }
    public void complete(DownloadedPhoto photo) { DownloadCallback callback = pending; pending = null; callback.onSuccess(photo); }
}

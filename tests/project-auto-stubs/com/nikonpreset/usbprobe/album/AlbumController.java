package com.nikonpreset.usbprobe.album;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local catalog/acquisition facade only. No camera, MediaStore or foreground service is exercised. */
public final class AlbumController {
    public interface Callback<T> { void onSuccess(T result); void onError(String message); }
    public interface DownloadCallback extends Callback<DownloadedPhoto> { void onProgress(long received, long total); }
    public interface PhotoLiveProjectSetup { void configure(PhotoLiveProject project) throws Exception; }
    public final List<PhotoLiveProject> projects = new java.util.concurrent.CopyOnWriteArrayList<>();
    public final Map<String, List<PhotoLiveReceipt>> receipts = new ConcurrentHashMap<>();
    public volatile DownloadCallback pending;
    public volatile int downloads;
    private final List<Runnable> liveObservers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Runnable> libraryObservers = new java.util.concurrent.CopyOnWriteArrayList<>();
    public void addPhotoLiveObserver(Runnable observer) { liveObservers.add(observer); }
    public void addPhotoLibraryObserver(Runnable observer) { libraryObservers.add(observer); }
    public void notifyLiveProgress() { for (Runnable observer : liveObservers) observer.run(); }
    public void notifyLibraryChanged() { for (Runnable observer : libraryObservers) observer.run(); }
    public void listPhotoLiveProjects(Callback<List<PhotoLiveProject>> callback) { callback.onSuccess(new ArrayList<>(projects)); }
    public void listPhotoLiveReceipts(String id, Callback<List<PhotoLiveReceipt>> callback) { callback.onSuccess(receipts.getOrDefault(id, Collections.emptyList())); }
    public PhotoLiveState photoLiveState() { return new PhotoLiveState(false, false, false, 0, 0, 0, 0, "", 0, Collections.emptyList()); }
    public void download(PhotoItem ignored, DownloadCallback callback) { downloads++; pending = callback; }
    public boolean hasLiveSession() { return true; }
    public void complete(DownloadedPhoto photo) { DownloadCallback callback = pending; pending = null; callback.onSuccess(photo); }
}

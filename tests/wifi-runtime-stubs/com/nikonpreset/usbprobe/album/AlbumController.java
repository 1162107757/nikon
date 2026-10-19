package com.nikonpreset.usbprobe.album;
import android.content.Context;
public final class AlbumController {
    public interface Callback<T> { void onSuccess(T value); void onError(String error); }
    public interface Original { void saved(String project, String identity, DownloadedPhoto photo); }
    public interface Identity { void saved(String identity, DownloadedPhoto photo); }
    public interface Publisher { void save(DownloadedPhoto photo, Callback<String> callback); }
    public boolean live, connecting, connected;
    public int disconnects;
    private Runnable observer;
    public AlbumController(Context context) { }
    public void setPhotoLiveOriginalObserver(Original value) { }
    public void setOriginalDownloadIdentityObserver(Identity value) { }
    public void setPhotoLiveAsyncPublisher(Publisher value) { }
    public void setOriginalDownloadObserver(java.util.function.Consumer<DownloadedPhoto> value) { }
    public void setLifecycleObserver(Runnable value) { observer = value; }
    public boolean hasLiveSession() { return live; }
    public boolean isConnected() { return connected; }
    public boolean isConnecting() { return connecting; }
    public boolean isDeletingDownloadedPhotos() { return false; }
    public PhotoLiveState photoLiveState() { return new PhotoLiveState(live, false, false, 0, 0, 0, 0, "", 0, java.util.List.of()); }
    public ConnectionInfo sessionConnectionInfo() { return live ? new ConnectionInfo("Synthetic", "192.168.1.1", 15740, 0) : null; }
    public String browseError() { return ""; }
    public void retryBrowse(Callback<ConnectionInfo> completion) { }
    public boolean connectFromHandoff(PtpIpClient client, String host, int port, Callback<ConnectionInfo> completion) { return true; }
    public void keepAlive(Callback<Void> completion) { completion.onSuccess(null); }
    public void disconnect() { disconnects++; live = connecting = connected = false; if (observer != null) observer.run(); }
}

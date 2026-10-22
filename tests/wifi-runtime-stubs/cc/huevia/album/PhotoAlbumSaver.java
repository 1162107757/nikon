package cc.huevia.album;
import android.content.Context;
public final class PhotoAlbumSaver {
    public boolean busy;
    public static PhotoAlbumSaver get(Context context) { return new PhotoAlbumSaver(); }
    public boolean isBusy() { return busy; }
    public void save(DownloadedPhoto photo, AlbumController.Callback<String> callback) { }
}

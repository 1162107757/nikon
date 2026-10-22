package cc.huevia.album;
import android.content.Context;
public final class PhotoProjectPresets {
    public interface WorkHost { AutoCloseable beginPhotoWork(); void onChanged(); }
    public boolean working;
    public int pauses;
    public PhotoProjectPresets(Context context, AlbumController album, PhotoPresetBatch batch, WorkHost host) { }
    public void onOriginalSaved(String project, String identity, DownloadedPhoto photo) { }
    public boolean hasWork() { return working; }
    public void pause(String message) { pauses++; working = false; }
}

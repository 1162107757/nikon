package cc.huevia.album;
import android.content.Context;
public final class PhotoPresetBatch {
    public boolean active;
    public int cancels;
    public PhotoPresetBatch(Context context, AlbumController album) { }
    public void setLifecycleObserver(Runnable observer) { }
    public boolean isActive() { return active; }
    public void cancel() { cancels++; active = false; }
}

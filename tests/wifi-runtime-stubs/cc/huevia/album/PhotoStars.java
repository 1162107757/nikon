package cc.huevia.album;
import android.content.Context;
public final class PhotoStars {
    public static PhotoStars get(Context context) { return new PhotoStars(); }
    public void bindDownloaded(String identity, DownloadedPhoto photo) { }
}

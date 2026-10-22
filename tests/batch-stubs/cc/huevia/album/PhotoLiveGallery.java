package cc.huevia.album;
import android.content.Context;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
/** Host-only publication boundary. It cannot establish actual MediaStore or permission behavior. */
public final class PhotoLiveGallery {
    public static volatile boolean failDerived;
    public static volatile java.util.function.Consumer<DownloadedPhoto> beforePublish;
    public static final Map<String, String> rows = new ConcurrentHashMap<>();
    public static final java.util.List<String> publications = new java.util.concurrent.CopyOnWriteArrayList<>();
    public PhotoLiveGallery(Context context) { }
    public String publish(DownloadedPhoto photo) throws IOException {
        java.util.function.Consumer<DownloadedPhoto> callback = beforePublish; if (callback != null) callback.accept(photo);
        publications.add(photo.file().getAbsolutePath());
        if (failDerived && photo.file().getName().startsWith("effect-")) throw new IOException("synthetic publication failure");
        return rows.computeIfAbsent(photo.file().getAbsolutePath(), path -> "content://synthetic/" + rows.size());
    }
}

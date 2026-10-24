package cc.huevia.album;
import android.content.Context;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
/** Host-only publication boundary. It cannot establish actual MediaStore or permission behavior. */
public final class PhotoLiveGallery {
    public static volatile boolean failDerived;
    public static volatile String failOnlyFilenamePrefix = "";
    public static volatile java.util.function.Consumer<DownloadedPhoto> beforePublish;
    public static final Map<String, String> rows = new ConcurrentHashMap<>();
    private static final Map<String, String> latest = new ConcurrentHashMap<>();
    public static final java.util.List<String> publications = new java.util.concurrent.CopyOnWriteArrayList<>();
    public PhotoLiveGallery(Context context) { }
    public String publish(DownloadedPhoto photo) throws IOException {
        return publish(photo, false);
    }
    public String publish(DownloadedPhoto photo, boolean forceNewCopy) throws IOException {
        java.util.function.Consumer<DownloadedPhoto> callback = beforePublish; if (callback != null) callback.accept(photo);
        publications.add(photo.file().getAbsolutePath());
        if (failDerived && photo.file().getName().startsWith("effect-") && (failOnlyFilenamePrefix.isEmpty() || photo.filename().startsWith(failOnlyFilenamePrefix))) throw new IOException("synthetic publication failure");
        String source = photo.file().getAbsolutePath(), existing = latest.get(source);
        if (!forceNewCopy && existing != null && rows.containsValue(existing)) return existing;
        String key = source + (forceNewCopy ? ":copy:" + java.util.UUID.randomUUID() : "");
        String uri = rows.computeIfAbsent(key, path -> "content://synthetic/" + rows.size()); latest.put(source, uri); return uri;
    }
}

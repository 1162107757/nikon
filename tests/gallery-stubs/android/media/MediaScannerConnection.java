package android.media;
import android.content.Context;
import android.net.Uri;
import java.io.File;
public final class MediaScannerConnection {
    public interface OnScanCompletedListener { void onScanCompleted(String path, Uri uri); }
    public static void scanFile(Context context, String[] paths, String[] types, OnScanCompletedListener listener) {
        context.scanRequests++;
        listener.onScanCompleted(paths[0], context.scanReturnsNull ? null : context.resolver.scan(new File(paths[0])));
    }
}

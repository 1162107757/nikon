package android.provider;
import android.net.Uri;
public final class MediaStore {
    public static final String VOLUME_EXTERNAL_PRIMARY = "external_primary";
    public static Uri setIncludePending(Uri uri) { return uri; }
    public static final class Images { public static final class Media {
        public static final String _ID = "_id", DISPLAY_NAME = "_display_name", MIME_TYPE = "mime_type", RELATIVE_PATH = "relative_path", OWNER_PACKAGE_NAME = "owner_package_name", IS_PENDING = "is_pending", DATA = "_data";
        public static final Uri EXTERNAL_CONTENT_URI = Uri.parse("content://media/external/images/media");
        public static Uri getContentUri(String volume) { return Uri.parse("content://media/" + volume + "/images/media"); }
    } }
}

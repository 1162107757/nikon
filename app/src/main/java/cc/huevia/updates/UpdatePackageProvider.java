package cc.huevia.updates;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** A temporary read grant can expose one verified APK, never photos or arbitrary private paths. */
public final class UpdatePackageProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        if (getContext() == null || !uri.getAuthority().equals(getContext().getPackageName() + ".updates")
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPathSegments().size() != 2
                || !"apk".equals(uri.getPathSegments().get(0))) throw new FileNotFoundException("Unknown update");
        String hash = uri.getPathSegments().get(1);
        if (!UpdatePolicy.hash(hash) || !hash.equals(getContext().getSharedPreferences(AppUpdateManager.PREFS, Context.MODE_PRIVATE).getString("verified", ""))) throw new FileNotFoundException("Unverified update");
        File result = new File(new File(getContext().getFilesDir(), "app-updates"), hash + ".apk");
        if (!result.isFile()) throw new FileNotFoundException("Missing update");
        return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try { File file = file(uri); String[] names = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor result = new MatrixCursor(names); Object[] row = new Object[names.length];
            for (int i = 0; i < names.length; i++) { if (OpenableColumns.DISPLAY_NAME.equals(names[i])) row[i] = "Huevia-update.apk"; else if (OpenableColumns.SIZE.equals(names[i])) row[i] = file.length(); }
            result.addRow(row); return result;
        } catch (FileNotFoundException ignored) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}

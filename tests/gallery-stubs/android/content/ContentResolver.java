package android.content;

import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** File-backed provider double; deliberate failures preserve rows exactly like a provider crash gap. */
public final class ContentResolver {
    public static final class Row {
        public long id; public String name, path, owner, uri, mime; public int pending; public File file;
    }
    private final File root;
    private final String owner;
    public final Map<Long, Row> rows = new LinkedHashMap<>();
    public int inserts, writes, updates, deletes, queries;
    public boolean failAfterInsert, failBeforePublish, nullQuery, failDelete, deleteNoOp, failReadMemory;
    public final java.util.Set<String> unreadableUris = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public volatile CountDownLatch writeStarted, allowWrite;
    private long nextId = 1;
    public ContentResolver(File root, String owner) { this.root = root; this.owner = owner; root.mkdirs(); }
    public synchronized Uri insert(Uri collection, ContentValues values) {
        Row row = new Row(); row.id = nextId++; row.name = (String) values.get(MediaStore.Images.Media.DISPLAY_NAME);
        row.path = (String) values.get(MediaStore.Images.Media.RELATIVE_PATH); row.pending = (Integer) values.get(MediaStore.Images.Media.IS_PENDING);
        row.mime = values.containsKey(MediaStore.Images.Media.MIME_TYPE) ? (String) values.get(MediaStore.Images.Media.MIME_TYPE) : "image/jpeg";
        row.owner = owner; row.uri = collection + "/" + row.id; row.file = new File(root, row.id + ".jpg"); rows.put(row.id, row); inserts++;
        if (failAfterInsert) { failAfterInsert = false; throw new IllegalStateException("synthetic insert response lost"); }
        return Uri.parse(row.uri);
    }
    public synchronized Uri scan(File file) {
        for (Row row : rows.values()) if (row.file.equals(file)) return Uri.parse(row.uri);
        Row row = new Row(); row.id = nextId++; row.name = file.getName(); row.path = ""; row.owner = owner; row.file = file; row.mime = "image/jpeg";
        row.uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI + "/" + row.id; rows.put(row.id, row); return Uri.parse(row.uri);
    }
    public synchronized Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) {
        if (unreadableUris.contains(uri.toString())) throw new SecurityException("synthetic revoked source permission");
        queries++; if (nullQuery) return null;
        List<Object[]> values = new ArrayList<>();
        for (Row row : rows.values()) {
            String address = uri.toString();
            if (!address.equals(row.uri) && !row.uri.startsWith(address + "/")) continue;
            if (selection != null && selection.contains(" LIKE ") && !row.name.startsWith(arguments[arguments.length - 1].replace("%", ""))) continue;
            if (selection != null && selection.equals("_data=?") && !row.file.getAbsolutePath().equals(arguments[0])) continue;
            Object[] columns = new Object[projection.length];
            for (int i = 0; i < projection.length; i++) columns[i] = switch (projection[i]) {
                case "_id" -> row.id; case "_display_name" -> row.name; case "relative_path" -> row.path;
                case "owner_package_name" -> row.owner; case "is_pending" -> row.pending; case "_data" -> row.file.getAbsolutePath();
                case "mime_type" -> row.mime; case "_size" -> row.file.length(); default -> null;
            };
            values.add(columns);
        }
        return new Cursor() {
            int at = -1;
            public boolean moveToNext() { return ++at < values.size(); }
            public boolean moveToFirst() { at = 0; return !values.isEmpty(); }
            public String getString(int column) { Object value = values.get(at)[column]; return value == null ? null : value.toString(); }
            public long getLong(int column) { return ((Number) values.get(at)[column]).longValue(); }
            public int getInt(int column) { return ((Number) values.get(at)[column]).intValue(); }
            public void close() { }
        };
    }
    public synchronized InputStream openInputStream(Uri uri) throws FileNotFoundException {
        if (unreadableUris.contains(uri.toString())) throw new SecurityException("synthetic revoked source permission");
        if (failReadMemory) { failReadMemory = false; throw new OutOfMemoryError("synthetic gallery memory pressure"); }
        return new FileInputStream(find(uri).file);
    }
    public synchronized String getType(Uri uri) {
        if (unreadableUris.contains(uri.toString())) throw new SecurityException("synthetic revoked source permission");
        return find(uri).mime;
    }
    public ParcelFileDescriptor openFileDescriptor(Uri uri, String mode) throws FileNotFoundException {
        CountDownLatch began = writeStarted, release = allowWrite;
        if (began != null) began.countDown();
        if (release != null) try { if (!release.await(10, TimeUnit.SECONDS)) throw new FileNotFoundException("synthetic write gate timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new FileNotFoundException("synthetic write interrupted"); }
        synchronized (this) { writes++; return new ParcelFileDescriptor(find(uri).file); }
    }
    public synchronized int update(Uri uri, ContentValues values, String selection, String[] arguments) {
        if (failBeforePublish) { failBeforePublish = false; throw new IllegalStateException("synthetic publish unavailable"); }
        Row row = find(uri); row.pending = (Integer) values.get(MediaStore.Images.Media.IS_PENDING); updates++; return 1;
    }
    public synchronized int delete(Uri uri, String selection, String[] arguments) {
        if (failDelete) { failDelete = false; throw new IllegalStateException("synthetic delete denied"); }
        if (deleteNoOp) return 0;
        Row row = find(uri); rows.remove(row.id); row.file.delete(); deletes++; return 1;
    }
    private Row find(Uri uri) { return rows.values().stream().filter(row -> row.uri.equals(uri.toString())).findFirst().orElseThrow(() -> new IllegalArgumentException("unknown URI " + uri)); }
}

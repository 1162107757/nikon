package cc.huevia.album;

import android.media.ExifInterface;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Local-worker EXIF date lookup; never modifies a JPEG or runs on the camera worker. */
public final class PhotoDateReader {
    private static final int MAX_CACHE = 4096;
    private static final String[] DATE_TAGS = {"DateTimeOriginal", "DateTimeDigitized", "DateTime"};
    private static final String[] OFFSET_TAGS = {"OffsetTimeOriginal", "OffsetTimeDigitized", "OffsetTime"};
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    interface Attributes { String get(String name); }
    interface ExifLoader { Attributes read(File file) throws IOException; }
    private final ExifLoader loader;
    private final Map<Key, Fields> cache = new LinkedHashMap<Key, Fields>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, Fields> eldest) { return size() > MAX_CACHE; }
    };

    public PhotoDateReader() {
        this(file -> { ExifInterface exif = new ExifInterface(file.getPath()); return exif::getAttribute; });
    }
    PhotoDateReader(ExifLoader loader) { this.loader = Objects.requireNonNull(loader, "loader"); }

    public List<DownloadedPhoto> read(List<DownloadedPhoto> photos, BooleanSupplier cancel) throws InterruptedIOException {
        return read(photos, cancel, ZoneId.systemDefault());
    }
    List<DownloadedPhoto> read(List<DownloadedPhoto> photos, BooleanSupplier cancel, ZoneId zone) throws InterruptedIOException {
        Objects.requireNonNull(photos, "photos"); Objects.requireNonNull(cancel, "cancel"); Objects.requireNonNull(zone, "zone");
        List<DownloadedPhoto> result = new ArrayList<>(photos.size());
        for (DownloadedPhoto photo : photos) {
            check(cancel); Objects.requireNonNull(photo, "photo");
            result.add(photo.withPhotoDate(date(photo, cancel, zone)));
        }
        check(cancel); return Collections.unmodifiableList(result);
    }

    private long date(DownloadedPhoto photo, BooleanSupplier cancel, ZoneId zone) throws InterruptedIOException {
        File file = photo.file(); if (file == null) return 0;
        long fallback = Math.max(0, file.lastModified());
        final File canonical;
        try { canonical = file.getCanonicalFile(); } catch (IOException failure) { return fallback; }
        Key key = new Key(canonical.getPath(), canonical.length(), fallback); Fields fields;
        synchronized (cache) { fields = cache.get(key); }
        if (fields == null) {
            check(cancel);
            try { fields = new Fields(loader.read(canonical)); }
            catch (InterruptedIOException stopped) { throw stopped; }
            catch (IOException | RuntimeException unreadable) { fields = Fields.EMPTY; }
            check(cancel);
            long after = Math.max(0, canonical.lastModified());
            if (canonical.length() != key.bytes || after != key.modified) return after;
            synchronized (cache) { cache.put(key, fields); }
        }
        for (int i = 0; i < fields.dates.length; i++) {
            long value = parse(fields.dates[i], fields.offsets[i], zone); if (value > 0) return value;
        }
        return fallback;
    }

    static long parse(String date, String offset, ZoneId zone) {
        if (date == null) return 0;
        try {
            String value = date.trim();
            if (!value.matches("[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}")) return 0;
            LocalDateTime local = LocalDateTime.parse(value, FORMAT);
            if (local.getYear() == 0) return 0;
            ZoneOffset actual;
            if (offset != null && !offset.trim().isEmpty()) {
                String text = offset.trim(); if (!text.matches("[+-][0-9]{2}:[0-9]{2}")) return 0;
                actual = ZoneOffset.of(text);
            } else {
                List<ZoneOffset> valid = zone.getRules().getValidOffsets(local);
                if (valid.isEmpty()) return 0; // Do not silently shift a nonexistent DST wall-clock time.
                actual = valid.get(0); // Deterministic earlier offset in a repeated hour.
            }
            return Math.max(0, local.toInstant(actual).toEpochMilli());
        } catch (java.time.DateTimeException | ArithmeticException invalid) { return 0; }
    }

    private static void check(BooleanSupplier cancel) throws InterruptedIOException {
        if (cancel.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("照片日期读取已取消。");
    }
    private static final class Fields {
        static final Fields EMPTY = new Fields(null);
        final String[] dates = new String[DATE_TAGS.length], offsets = new String[OFFSET_TAGS.length];
        Fields(Attributes attributes) {
            if (attributes != null) for (int i = 0; i < dates.length; i++) { dates[i] = attribute(attributes, DATE_TAGS[i]); offsets[i] = attribute(attributes, OFFSET_TAGS[i]); }
        }
        private static String attribute(Attributes attributes, String tag) {
            try { return attributes.get(tag); } catch (RuntimeException unsupported) { return null; }
        }
    }
    private static final class Key {
        final String path; final long bytes, modified;
        Key(String path, long bytes, long modified) { this.path = path; this.bytes = bytes; this.modified = modified; }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Key)) return false; Key value = (Key) other;
            return path.equals(value.path) && bytes == value.bytes && modified == value.modified;
        }
        @Override public int hashCode() { return Objects.hash(path, bytes, modified); }
    }
}

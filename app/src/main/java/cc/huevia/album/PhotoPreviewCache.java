package cc.huevia.album;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Iterator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Small file cache; acquired previews cannot be evicted until the viewer releases them. */
final class PhotoPreviewCache implements AutoCloseable {
    /** An activity replacement must not clean another controller's displayed or receiving file. */
    private static final Set<File> OWNED_FILES = new HashSet<>();
    private static final class Entry {
        final File file;
        final long bytes;
        final boolean largeThumbnail;
        int readers;
        boolean removed;
        Entry(File file, boolean largeThumbnail) {
            this.file = file; bytes = file.length(); this.largeThumbnail = largeThumbnail;
        }
    }

    private final File directory;
    private final int maxEntries;
    private final long maxBytes;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(4, .75f, true);
    private final Set<File> temporaries = new HashSet<>();
    private boolean initialized;
    private boolean closed;

    PhotoPreviewCache(File directory, int maxEntries, long maxBytes) {
        this.directory = Objects.requireNonNull(directory).toPath().toAbsolutePath().normalize().toFile();
        if (maxEntries < 1 || maxBytes < 1) throw new IllegalArgumentException("cache bounds");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    synchronized File createTemporary() throws IOException {
        initialize();
        synchronized (OWNED_FILES) {
            File temporary = File.createTempFile("preview-", ".part", directory);
            temporaries.add(temporary);
            OWNED_FILES.add(temporary);
            return temporary;
        }
    }

    synchronized void discardTemporary(File temporary) {
        if (temporaries.remove(temporary)) deleteOwned(temporary);
    }

    /** Returns a pinned completed JPEG, never an in-progress receive. */
    synchronized PhotoPreview acquire(PhotoItem photo) throws IOException {
        initialize();
        String key = key(photo);
        Entry entry = entries.get(key);
        if (entry == null) return null;
        try { validateJpeg(entry.file, entry.bytes); }
        catch (IOException invalid) {
            entries.remove(key);
            remove(entry);
            return null;
        }
        return pin(entry, photo.filename());
    }

    /** A reused camera handle with changed metadata must not show the previous object's pixels. */
    synchronized void invalidate(PhotoItem photo) throws IOException {
        Entry entry = entries.remove(key(photo));
        if (entry != null) remove(entry);
    }

    /** Ownership of the temporary file transfers only after publication succeeds. */
    synchronized PhotoPreview publish(PhotoItem photo, File temporary) throws IOException {
        return publish(photo, temporary, false);
    }

    synchronized PhotoPreview publish(PhotoItem photo, File temporary, boolean largeThumbnail) throws IOException {
        initialize();
        if (!temporaries.contains(temporary)) {
            throw new IOException("预览缓存文件无效。");
        }
        String key = key(photo);
        // Preview transfers have their own framed size, independent of the original object.
        validateJpeg(temporary, temporary.length());
        if (temporary.length() > maxBytes) throw new IOException("照片超过本机预览缓存上限。");
        Entry previous = entries.get(key);
        if (previous != null) {
            PhotoPreview ready = acquire(photo);
            if (ready != null) {
                discardTemporary(temporary);
                return ready;
            }
        }
        makeRoom(temporary.length());
        File complete = new File(directory, "preview-" + UUID.randomUUID() + ".jpg");
        synchronized (OWNED_FILES) {
            if (complete.exists() || !temporary.renameTo(complete)) throw new IOException("照片预览接收完成，但缓存保存未完成。");
            temporaries.remove(temporary);
            OWNED_FILES.remove(temporary);
            OWNED_FILES.add(complete);
        }
        Entry entry = new Entry(complete, largeThumbnail);
        entries.put(key, entry);
        return pin(entry, photo.filename());
    }

    static void validateJpeg(File file, long expectedBytes) throws IOException {
        if (expectedBytes < 4 || !file.isFile() || file.length() != expectedBytes) {
            throw new IOException("照片大小已变化或接收不完整，请刷新相机照片列表。");
        }
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.read() != 0xff || input.read() != 0xd8) throw new IOException("相机返回的照片不是受支持的 JPEG。");
            input.seek(expectedBytes - 2);
            if (input.read() != 0xff || input.read() != 0xd9) throw new IOException("相机返回的 JPEG 照片不完整。");
        }
    }

    @Override public synchronized void close() {
        closed = true;
        for (Entry entry : entries.values()) remove(entry);
        entries.clear();
    }

    private void initialize() throws IOException {
        if (closed) throw new IOException("照片预览已关闭。");
        if (initialized) return;
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建照片预览缓存。");
        File[] leftovers = directory.listFiles(file -> file.isFile()
                && file.getName().startsWith("preview-")
                && (file.getName().endsWith(".part") || file.getName().endsWith(".jpg")));
        if (leftovers == null) throw new IOException("无法读取照片预览缓存。");
        synchronized (OWNED_FILES) {
            for (File file : leftovers) if (!OWNED_FILES.contains(file) && file.exists() && !file.delete()) {
                throw new IOException("无法清理上次的照片预览缓存。");
            }
        }
        initialized = true;
    }

    private void makeRoom(long bytes) throws IOException {
        long total = 0;
        for (Entry entry : entries.values()) total += entry.bytes;
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while ((entries.size() >= maxEntries || total + bytes > maxBytes) && iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.readers > 0) continue;
            iterator.remove();
            total -= entry.bytes;
            remove(entry);
        }
        if (entries.size() >= maxEntries || total + bytes > maxBytes) {
            throw new IOException("正在查看的照片占满预览缓存，请先关闭照片后重试。");
        }
    }

    private PhotoPreview pin(Entry entry, String filename) {
        entry.readers++;
        return new PhotoPreview(entry.file, filename, entry.largeThumbnail, () -> release(entry));
    }

    private synchronized void release(Entry entry) {
        entry.readers--;
        if (entry.readers == 0 && entry.removed) deleteOwned(entry.file);
    }

    private static void remove(Entry entry) {
        entry.removed = true;
        if (entry.readers == 0) deleteOwned(entry.file);
    }

    private static void deleteOwned(File file) {
        synchronized (OWNED_FILES) {
            OWNED_FILES.remove(file);
            file.delete();
        }
    }

    private static String key(PhotoItem photo) throws IOException {
        if (photo == null || photo.session == null || photo.session.isEmpty()
                || photo.handle() <= 0 || photo.handle() >= 0xffffffffL) {
            throw new IOException("照片信息不完整，请刷新相机照片列表。");
        }
        return photo.session + ":" + photo.storageId() + ":" + photo.handle() + ":" + photo.byteSize()
                + ":" + photo.thumbnailByteSize() + ":" + photo.thumbnailWidth() + ":" + photo.thumbnailHeight();
    }
}

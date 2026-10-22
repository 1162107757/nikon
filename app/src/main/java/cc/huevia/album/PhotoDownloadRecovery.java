package cc.huevia.album;

import cc.huevia.ptp.PtpObjectInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/** Memory-only unfinished originals. Never retains an Activity, credentials or partial bytes. */
public final class PhotoDownloadRecovery {
    /** Immutable identity captured while the original camera session is current. */
    static final class Entry {
        final String identity;
        final long handle;
        final PtpObjectInfo descriptor;
        Entry(String camera, PhotoItem photo) {
            handle = photo.handle(); descriptor = photo.objectInfo();
            identity = PhotoLiveLedger.identity(camera, handle, descriptor);
        }
        boolean matches(PtpObjectInfo current) { return Arrays.equals(descriptor.toBytes(), current.toBytes()); }
    }

    final String camera;
    private final LinkedHashMap<String, Entry> pending = new LinkedHashMap<>();

    PhotoDownloadRecovery(String camera, List<PhotoItem> photos) {
        this.camera = camera;
        for (PhotoItem photo : photos) {
            Entry entry = new Entry(camera, photo); pending.put(entry.identity, entry);
        }
    }

    synchronized int size() { return pending.size(); }
    synchronized List<Entry> snapshot() { return Collections.unmodifiableList(new ArrayList<>(pending.values())); }
    synchronized boolean completed(String identity) { return pending.remove(identity) != null; }
}

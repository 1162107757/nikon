package cc.huevia.album;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable snapshot of one named photo-live project; originals remain in the app photo directory. */
public final class PhotoLiveProject {
    public final String id, name;
    public final long createdAt, totalBytes;
    public final List<DownloadedPhoto> photos;
    public final int photoCount;

    PhotoLiveProject(String id, String name, long createdAt, List<DownloadedPhoto> photos, long totalBytes) {
        this.id = id; this.name = name; this.createdAt = createdAt; this.totalBytes = totalBytes;
        this.photos = Collections.unmodifiableList(new ArrayList<>(photos));
        this.photoCount = this.photos.size();
    }
}

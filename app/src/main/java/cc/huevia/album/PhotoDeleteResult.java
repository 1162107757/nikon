package cc.huevia.album;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Per-file terminal outcome. A failed item retains its app source for a local retry. */
public final class PhotoDeleteResult {
    public final int total, deleted, failed;
    public final List<DownloadedPhoto> deletedPhotos;
    public final List<Failure> failures;
    public static final class Failure {
        public final DownloadedPhoto photo;
        public final String message;
        Failure(DownloadedPhoto photo, String message) { this.photo = photo; this.message = message; }
    }
    PhotoDeleteResult(List<DownloadedPhoto> deletedPhotos, List<Failure> failures) {
        this.deletedPhotos = Collections.unmodifiableList(new ArrayList<>(deletedPhotos));
        this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
        deleted = deletedPhotos.size(); failed = failures.size(); total = deleted + failed;
    }
}

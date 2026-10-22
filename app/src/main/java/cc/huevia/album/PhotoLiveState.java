package cc.huevia.album;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable application-owned live-receive snapshot; no Activity or bitmap ownership. */
public final class PhotoLiveState {
    public final boolean enabled, starting, receiving;
    public final int queued, completed;
    public final long receivedBytes, totalBytes, revision;
    public final String error;
    public final String projectId, projectName;
    /** Empty while idle; otherwise preview, receiving, or saving. Saving includes gallery publication. */
    public final String transferStage, currentIdentity, currentFilename;
    public final List<LivePhoto> photos;

    PhotoLiveState(boolean enabled, boolean starting, boolean receiving, int queued, int completed,
                   long receivedBytes, long totalBytes, String error, long revision, List<LivePhoto> photos) {
        this(enabled, starting, receiving, queued, completed, receivedBytes, totalBytes, error, revision, photos, "", "");
    }

    PhotoLiveState(boolean enabled, boolean starting, boolean receiving, int queued, int completed,
                   long receivedBytes, long totalBytes, String error, long revision, List<LivePhoto> photos,
                   String projectId, String projectName) {
        this(enabled, starting, receiving, queued, completed, receivedBytes, totalBytes, error, revision, photos,
                projectId, projectName, "", "", "");
    }

    PhotoLiveState(boolean enabled, boolean starting, boolean receiving, int queued, int completed,
                   long receivedBytes, long totalBytes, String error, long revision, List<LivePhoto> photos,
                   String projectId, String projectName, String transferStage, String currentIdentity, String currentFilename) {
        this.enabled = enabled; this.starting = starting; this.receiving = receiving;
        this.queued = queued; this.completed = completed; this.receivedBytes = receivedBytes;
        this.totalBytes = totalBytes; this.error = error == null ? "" : error; this.revision = revision;
        this.photos = Collections.unmodifiableList(new ArrayList<>(photos));
        this.projectId = projectId == null ? "" : projectId;
        this.projectName = projectName == null ? "" : projectName;
        this.transferStage = transferStage == null ? "" : transferStage;
        this.currentIdentity = currentIdentity == null ? "" : currentIdentity;
        this.currentFilename = currentFilename == null ? "" : currentFilename;
    }

    public static final class LivePhoto {
        public final PhotoItem photo;
        public final DownloadedPhoto saved;
        /** queued, preview, receiving, saved, error, or stopped. */
        public final String identity, status, error, galleryUri, galleryError;
        LivePhoto(String identity, PhotoItem photo, DownloadedPhoto saved, String status, String error,
                  String galleryUri, String galleryError) {
            this.identity = identity; this.photo = photo; this.saved = saved; this.status = status;
            this.error = error == null ? "" : error;
            this.galleryUri = galleryUri == null ? "" : galleryUri;
            this.galleryError = galleryError == null ? "" : galleryError;
        }
    }
}

package cc.huevia.album;

/** A durable project receive entry. Historic metadata never authorizes a camera read by itself. */
public final class PhotoLiveReceipt {
    public final String projectId, identity, status, filename, captureDate, error;
    public final long byteSize, createdAt, updatedAt;
    public final DownloadedPhoto saved;
    public final PhotoSaveTarget saveTarget;
    final PhotoItem photo;

    PhotoLiveReceipt(String projectId, String identity, String status, PhotoItem photo,
                     DownloadedPhoto saved, String error, long updatedAt) {
        this(projectId, identity, status, photo, saved, error, updatedAt, updatedAt);
    }
    PhotoLiveReceipt(String projectId, String identity, String status, PhotoItem photo,
                     DownloadedPhoto saved, String error, long createdAt, long updatedAt) {
        this(projectId, identity, status, photo, saved, error, createdAt, updatedAt, PhotoSaveTarget.PHONE_ALBUM);
    }
    PhotoLiveReceipt(String projectId, String identity, String status, PhotoItem photo,
                     DownloadedPhoto saved, String error, long createdAt, long updatedAt, PhotoSaveTarget saveTarget) {
        this.projectId = projectId; this.identity = identity; this.status = status; this.photo = photo;
        this.filename = photo.filename(); this.byteSize = photo.byteSize(); this.captureDate = photo.captureDate();
        this.saved = saved; this.error = error == null ? "" : error; this.createdAt = createdAt; this.updatedAt = updatedAt;
        this.saveTarget = java.util.Objects.requireNonNull(saveTarget, "saveTarget");
    }

    PhotoLiveReceipt archived() {
        return status.equals("queued") || status.equals("preview") || status.equals("receiving")
                ? new PhotoLiveReceipt(projectId, identity, "stopped", photo, saved, error, createdAt, updatedAt, saveTarget) : this;
    }
}

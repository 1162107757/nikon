package cc.huevia.album;

import java.io.IOException;
import java.io.InterruptedIOException;

/** A valid historical JPEG remains viewable even after its editable original is unavailable. */
public final class PhotoSavedVersion {
    public final DownloadedPhoto photo;
    public final boolean unrecoverable;

    private PhotoSavedVersion(DownloadedPhoto photo, boolean unrecoverable) {
        this.photo = photo;
        this.unrecoverable = unrecoverable;
    }

    public static PhotoSavedVersion resolve(PhotoPresetBatch batch, DownloadedPhoto selected) throws IOException {
        try {
            return new PhotoSavedVersion(batch.processingOriginalFor(selected), false);
        } catch (InterruptedIOException cancelled) {
            throw cancelled;
        } catch (IOException unavailable) {
            if (!PhotoPresetBatch.isSavedEffect(selected)) throw unavailable;
            PhotoPreviewCache.validateJpeg(selected.file(), selected.file().length());
            return new PhotoSavedVersion(selected, true);
        }
    }
}

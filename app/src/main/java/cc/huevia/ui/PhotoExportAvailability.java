package cc.huevia.ui;

import cc.huevia.album.DownloadedPhoto;
import cc.huevia.album.PhotoExportSpec;
import cc.huevia.album.PhotoLiveGallery;
import cc.huevia.album.PhotoPresetBatch;
import cc.huevia.album.PhotoSaveTarget;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only export availability. All result/source hashing and provider queries run on a worker. */
public final class PhotoExportAvailability {
    private PhotoExportAvailability() {}

    public interface Provider {
        /** The caller applies spec to its frozen edits, then completes from any thread. */
        void query(PhotoExportSpec spec, PhotoSaveTarget target, Callback callback);
    }
    public interface Callback { void complete(Result result); }
    public interface ViewSaved { void open(Result result); }
    public enum Status { NOT_GENERATED, APP_RESULT, PHONE_SAVED, PHONE_MISSING, PENDING, UNKNOWN }

    public static final class Entry {
        public final Status status;
        /** Verified app-owned result. Null when lookup failed or no canonical result exists. */
        public final DownloadedPhoto photo;
        /** Only a verified, currently published phone copy carries a URI. */
        public final String uri, message;
        public Entry(Status status, DownloadedPhoto photo, String uri, String message) {
            if (status == null) throw new IllegalArgumentException("status");
            this.status = status; this.photo = photo;
            this.uri = status == Status.PHONE_SAVED && uri != null ? uri : "";
            this.message = message == null ? "" : message;
        }
    }
    public static final class Result {
        public final List<Entry> entries;
        public Result(List<Entry> entries) {
            if (entries == null) throw new IllegalArgumentException("entries");
            ArrayList<Entry> copy = new ArrayList<>();
            for (Entry entry : entries) { if (entry == null) throw new IllegalArgumentException("entry"); copy.add(entry); }
            this.entries = Collections.unmodifiableList(copy);
        }
        public int count(Status status) { int count = 0; for (Entry entry : entries) if (entry.status == status) count++; return count; }
        /** Phone visibility is never inferred merely from a valid app result or an unavailable receipt. */
        public boolean allSaved(PhotoSaveTarget target) {
            if (entries.isEmpty()) return false;
            for (Entry entry : entries) {
                if (entry.photo == null) return false;
                if (target == PhotoSaveTarget.PHONE_ALBUM && entry.status != Status.PHONE_SAVED) return false;
                if (target == PhotoSaveTarget.APP_ONLY && entry.status == Status.NOT_GENERATED) return false;
            }
            return true;
        }
        public String summary(PhotoSaveTarget target) {
            if (entries.isEmpty()) return "暂无法核对已保存照片";
            if (allSaved(target)) return target == PhotoSaveTarget.APP_ONLY ? "此规格的成品已保存在 App 内" : "已核对此规格的照片仍在手机相册";
            if (entries.size() == 1) {
                switch (entries.get(0).status) {
                    case APP_RESULT: return "已生成，可直接保存";
                    case PHONE_MISSING: return "已生成，可直接保存 · 手机相册暂无此照片";
                    case PENDING: return "上次保存尚未完成 · 将继续核对原保存请求";
                    case UNKNOWN: return "暂无法核对已保存照片 · 保存时继续核对原回执";
                    default: return "尚未生成此规格的成品";
                }
            }
            int saved = target == PhotoSaveTarget.APP_ONLY ? 0 : count(Status.PHONE_SAVED);
            int generated = count(Status.APP_RESULT) + count(Status.PHONE_MISSING);
            int pending = count(Status.PENDING), unknown = count(Status.UNKNOWN), fresh = count(Status.NOT_GENERATED);
            List<String> parts = new ArrayList<>();
            if (saved > 0) parts.add(saved + " 张已保存");
            if (generated > 0) parts.add(generated + " 张已生成，可直接保存");
            if (fresh > 0) parts.add(fresh + " 张待生成");
            if (pending > 0) parts.add(pending + " 张继续上次保存");
            if (unknown > 0) parts.add(unknown + " 张暂无法核对已保存照片");
            return String.join(" · ", parts);
        }
        public String primaryLabel(PhotoSaveTarget target, boolean newCopy) {
            if (!newCopy && allSaved(target)) return "查看已保存照片";
            if (newCopy) return entries.size() > 1 ? "另存 " + entries.size() + " 张新副本" : "另存新副本";
            if (target == PhotoSaveTarget.PHONE_ALBUM && count(Status.UNKNOWN) > 0) return "核对并继续保存";
            if (target == PhotoSaveTarget.PHONE_ALBUM && count(Status.PENDING) > 0) return "继续保存到手机相册";
            if (target == PhotoSaveTarget.PHONE_ALBUM && count(Status.PHONE_MISSING) == entries.size() && !entries.isEmpty()) return "重新保存到手机相册";
            if (target == PhotoSaveTarget.PHONE_ALBUM && count(Status.APP_RESULT) == entries.size() && !entries.isEmpty()) return "保存到手机相册";
            return entries.size() > 1 ? "导出 " + entries.size() + " 张照片" : "导出照片";
        }
    }

    public static Result unknown(int count) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) entries.add(new Entry(Status.UNKNOWN, null, "", "暂无法核对已保存照片"));
        return new Result(entries);
    }

    /** Applies only encoding settings to a frozen source/edit/account; publication choices stay separate. */
    public static List<PhotoPresetBatch.Edit> withSpec(List<PhotoPresetBatch.Edit> frozen, PhotoExportSpec spec) {
        if (frozen == null || spec == null) throw new IllegalArgumentException("frozen/spec");
        List<PhotoPresetBatch.Edit> edits = new ArrayList<>();
        for (PhotoPresetBatch.Edit edit : frozen) {
            if (edit == null) throw new IllegalArgumentException("edit");
            PhotoPresetBatch.Edit changed = edit.draft == null
                    ? PhotoPresetBatch.Edit.saved(edit.source, spec, edit.expectedSourceSha)
                    : PhotoPresetBatch.Edit.draft(edit.source, edit.draft, edit.owner, spec);
            if (edit.accountBound) changed = changed.withAccount(edit.accountScope);
            edits.add(changed);
        }
        return Collections.unmodifiableList(edits);
    }

    /** No rendering, publication, preference changes, or record writes occur in this worker helper. */
    public static Result check(PhotoPresetBatch batch, PhotoLiveGallery gallery, List<PhotoPresetBatch.Edit> edits, PhotoSaveTarget target) {
        if (batch == null || gallery == null || edits == null || target == null) throw new IllegalArgumentException("availability inputs");
        List<Entry> entries = new ArrayList<>();
        for (PhotoPresetBatch.Edit edit : edits) {
            DownloadedPhoto result = null;
            try {
                result = batch.findResult(edit);
                if (result == null) { entries.add(new Entry(Status.NOT_GENERATED, null, "", "")); continue; }
                if (target == PhotoSaveTarget.APP_ONLY) { entries.add(new Entry(Status.APP_RESULT, result, "", "")); continue; }
                PhotoLiveGallery.PublicationState state = gallery.publicationStatus(result);
                if (state == null) { entries.add(new Entry(Status.UNKNOWN, result, "", "暂无法核对已保存照片")); continue; }
                switch (state.status) {
                    case PUBLISHED:
                    case EXISTING_SOURCE:
                        entries.add(new Entry(state.uri == null || state.uri.isEmpty() ? Status.UNKNOWN : Status.PHONE_SAVED, result, state.uri, state.message)); break;
                    case MISSING: entries.add(new Entry(Status.PHONE_MISSING, result, "", state.message)); break;
                    case PENDING: entries.add(new Entry(Status.PENDING, result, "", state.message)); break;
                    default: entries.add(new Entry(Status.UNKNOWN, result, "", state.message)); break;
                }
            } catch (Exception failure) {
                entries.add(new Entry(Status.UNKNOWN, result, "", failure.getMessage()));
            } catch (OutOfMemoryError memory) {
                entries.add(new Entry(Status.UNKNOWN, result, "", "核对照片时内存不足，请稍后重试。"));
            }
        }
        return new Result(entries);
    }
}

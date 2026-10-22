package cc.huevia.album;

import android.util.AtomicFile;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Worker-only snapshot of completed private JPEGs. A photo is one original (with
 * any old exact-byte aliases) and its strictly verified preset versions. Lists
 * are immutable so UI selection/filtering cannot silently change the group.
 */
public final class PhotoLibraryCatalog {
    private static final int CHOICE_MAGIC = 0x504c4331;
    private static final long MAX_PHOTO = 512L * 1024 * 1024;
    private final File files, choices;
    private final List<File> directories = new ArrayList<>();
    private final PhotoPresetSources sources;
    // Completed private JPEGs are immutable in normal app operation. This memo
    // serves presentation only; deletion, rendering and camera reuse keep their
    // independent strict verification boundaries. forceVerify bypasses it.
    private final Map<String, String> displayHashes = new HashMap<>();
    private volatile List<PhotoGroup> lastSnapshot = List.of();

    public PhotoLibraryCatalog(File filesDirectory, File externalPicturesDirectory) throws IOException {
        files = filesDirectory.getCanonicalFile();
        choices = new File(files, "photo-library-display").getCanonicalFile();
        if (!files.equals(choices.getParentFile())) throw new IOException("照片展示记录路径无效。");
        directories.add(new File(new File(files, "pictures"), "camera-album").getCanonicalFile());
        if (externalPicturesDirectory != null) {
            File external = new File(externalPicturesDirectory, "camera-album").getCanonicalFile();
            if (!directories.contains(external)) directories.add(external);
        }
        sources = new PhotoPresetSources(files, externalPicturesDirectory);
    }

    public List<PhotoGroup> list(List<DownloadedPhoto> completed, BooleanSupplier cancel) throws IOException {
        return list(completed, cancel, false);
    }
    public synchronized List<PhotoGroup> list(List<DownloadedPhoto> completed, BooleanSupplier cancel, boolean forceVerify) throws IOException {
        if (completed == null || cancel == null) throw new IllegalArgumentException("completed/cancel");
        check(cancel);
        if (forceVerify || displayHashes.size() > 60000) displayHashes.clear();
        Map<String, String> hashes = displayHashes;
        Map<String, DownloadedPhoto> unique = new LinkedHashMap<>();
        for (DownloadedPhoto photo : completed) {
            check(cancel);
            if (photo == null || photo.file() == null) continue;
            try { File file = owned(photo.file()); unique.putIfAbsent(file.getPath(), copy(photo, file, photo.filename())); }
            catch (IOException unavailable) { /* An atomically deleted/missing JPEG is not a completed version. */ }
        }
        Map<String, MutableGroup> groups = new LinkedHashMap<>();
        List<DownloadedPhoto> effects = new ArrayList<>();
        for (DownloadedPhoto photo : unique.values()) {
            check(cancel);
            if (PhotoPresetSources.isEffect(photo.file())) { effects.add(photo); continue; }
            try {
                String sha = PhotoPresetSources.snapshotHash(photo.file(), cancel, hashes);
                MutableGroup group = groups.computeIfAbsent("photo-" + sha, MutableGroup::new);
                group.addOriginal(photo);
            } catch (InterruptedIOException interrupted) { throw interrupted; }
            catch (IOException unavailable) { /* A changed/vanished original is never linked by its old filename. */ }
        }
        for (DownloadedPhoto photo : effects) {
            check(cancel);
            PhotoPresetSources.Metadata metadata;
            try { metadata = sources.metadata(photo); }
            catch (IOException unavailable) { metadata = PhotoPresetSources.Metadata.EMPTY; }
            String presetSha = "", presetName = metadata.presetName;
            DownloadedPhoto named = copy(photo, photo.file(), metadata.filename.isEmpty() ? photo.filename() : metadata.filename);
            try {
                PhotoPresetSources.Relation relation = sources.relation(photo, cancel, hashes);
                if (relation == null) throw new IOException("效果图缺少关联。");
                MutableGroup group = groups.computeIfAbsent("photo-" + relation.originalSha, MutableGroup::new);
                // The exact, validated root is safe even if the caller's list was a project subset.
                DownloadedPhoto listed = unique.get(relation.original.file().getPath());
                group.addOriginal(listed == null ? relation.original : listed);
                presetSha = relation.presetSha;
                if (!relation.presetName.isEmpty()) presetName = relation.presetName;
                group.addEffect(new Version(named, presetSha, presetName, false));
            } catch (InterruptedIOException interrupted) { throw interrupted; }
            catch (IOException unavailable) {
                // An orphan remains visible/deletable by itself. Metadata is presentation, never proof.
                String id = "orphan-" + digest("orphan\n" + photo.file().getCanonicalPath());
                MutableGroup group = groups.computeIfAbsent(id, MutableGroup::new);
                group.addEffect(new Version(named, "", presetName, false));
            }
        }
        List<PhotoGroup> result = new ArrayList<>();
        for (MutableGroup group : groups.values()) {
            check(cancel); result.add(group.freeze(readChoice(group.id)));
        }
        result.sort(Comparator.comparingLong((PhotoGroup group) -> group.photoDate).reversed().thenComparing(group -> group.id));
        List<PhotoGroup> snapshot = Collections.unmodifiableList(result);
        lastSnapshot = snapshot;
        return snapshot;
    }
    /** Call after external repair/restore or before a diagnostic strict reload. */
    public synchronized void invalidate() { displayHashes.clear(); }

    /** Only a member of the exact immutable group may become its remembered display version. */
    public void selectDisplay(PhotoGroup group, DownloadedPhoto version) throws IOException {
        if (group == null || version == null) throw new IllegalArgumentException("group/version");
        String path = owned(version.file()).getPath();
        if (group.findByFile(version.file()) == null) throw new IOException("这张照片不属于当前照片组。");
        writeChoice(group.id, path);
    }
    /** Convenience for a catalog instance that has already loaded its immutable snapshot. */
    public void selectDisplay(String groupId, DownloadedPhoto version) throws IOException {
        PhotoGroup group = null;
        for (PhotoGroup candidate : lastSnapshot) if (candidate.id.equals(groupId)) { group = candidate; break; }
        if (group == null) throw new IOException("照片组已变化，请刷新后重试。");
        selectDisplay(group, version);
    }
    public static PhotoGroup groupFor(List<PhotoGroup> groups, DownloadedPhoto photo) {
        return photo == null ? null : findByFile(groups, photo.file());
    }
    public static PhotoGroup findByFile(List<PhotoGroup> groups, File file) {
        if (groups == null || file == null) return null;
        for (PhotoGroup group : groups) if (group.findByFile(file) != null) return group;
        return null;
    }

    public static final class Version {
        public final DownloadedPhoto photo;
        public final String presetSha, presetName;
        public final boolean original;
        private Version(DownloadedPhoto photo, String presetSha, String presetName, boolean original) {
            this.photo = photo; this.presetSha = presetSha; this.presetName = presetName; this.original = original;
        }
        public String label() { return original ? "原图" : presetName.isEmpty() ? "预设效果" : presetName; }
    }
    public static final class PhotoGroup {
        public final String id;
        /** Null only for a strictly unresolvable/orphan effect. */
        public final DownloadedPhoto original, display;
        /** One original version + all verified effect versions. */
        public final List<Version> versions;
        /** Every private original alias is retained for an explicit whole-group deletion. */
        public final List<DownloadedPhoto> originals;
        public final long photoDate;
        private final List<DownloadedPhoto> photos;
        private final Map<String, Version> byPath;
        private PhotoGroup(String id, DownloadedPhoto original, DownloadedPhoto display, List<Version> versions,
                           List<DownloadedPhoto> originals, List<DownloadedPhoto> photos, Map<String, Version> byPath) {
            this.id = id; this.original = original; this.display = display;
            this.versions = Collections.unmodifiableList(new ArrayList<>(versions));
            this.originals = Collections.unmodifiableList(new ArrayList<>(originals));
            this.photos = Collections.unmodifiableList(new ArrayList<>(photos));
            this.byPath = Collections.unmodifiableMap(new HashMap<>(byPath));
            this.photoDate = original == null ? display.photoDate() : original.photoDate();
        }
        public List<DownloadedPhoto> photos() { return photos; }
        public boolean orphan() { return original == null; }
        /** Read-only path equality; callers pass completed canonical DownloadedPhoto files. */
        public Version findByFile(File file) {
            if (file == null) return null;
            return byPath.get(file.getAbsolutePath());
        }
    }
    private static final class MutableGroup {
        final String id;
        final Map<String, DownloadedPhoto> originals = new LinkedHashMap<>();
        final Map<String, Version> effects = new LinkedHashMap<>();
        MutableGroup(String id) { this.id = id; }
        void addOriginal(DownloadedPhoto photo) { originals.putIfAbsent(photo.file().getAbsolutePath(), photo); }
        void addEffect(Version version) { effects.putIfAbsent(version.photo.file().getAbsolutePath(), version); }
        PhotoGroup freeze(String choice) {
            List<DownloadedPhoto> aliases = new ArrayList<>(originals.values());
            aliases.sort(Comparator.comparingLong(DownloadedPhoto::photoDate).thenComparing(photo -> photo.file().getPath()));
            DownloadedPhoto original = aliases.isEmpty() ? null : aliases.get(0);
            // Two supported private directories may hold one verified output.
            // Keep every path for deletion while presenting that version once.
            Map<String, Version> distinct = new LinkedHashMap<>();
            for (Version version : effects.values()) distinct.putIfAbsent(version.photo.file().getName(), version);
            List<Version> derived = new ArrayList<>(distinct.values());
            derived.sort(Comparator.comparingLong((Version version) -> version.photo.downloadedAt()).reversed().thenComparing(version -> version.photo.file().getPath()));
            List<Version> versions = new ArrayList<>();
            if (original != null) versions.add(new Version(original, "", "", true));
            versions.addAll(derived);
            List<DownloadedPhoto> photos = new ArrayList<>(aliases);
            for (Version version : effects.values()) photos.add(version.photo);
            Map<String, Version> byPath = new HashMap<>();
            for (DownloadedPhoto alias : aliases) byPath.put(alias.file().getAbsolutePath(), versions.get(0));
            for (Version version : effects.values()) byPath.put(version.photo.file().getAbsolutePath(), distinct.get(version.photo.file().getName()));
            DownloadedPhoto display = choice.isEmpty() ? (derived.isEmpty() ? original : derived.get(0).photo) : original;
            for (DownloadedPhoto photo : photos) if (photo.file().getPath().equals(choice)) { display = photo; break; }
            // A remembered removed effect falls back to the original, never an unrelated effect.
            if (display == null) display = derived.get(0).photo;
            return new PhotoGroup(id, original, display, versions, aliases, photos, byPath);
        }
    }
    private File owned(File input) throws IOException {
        if (input == null) throw new IOException("照片不存在。");
        File file = input.getCanonicalFile();
        if (!directories.contains(file.getParentFile()) || file.length() > MAX_PHOTO) throw new IOException("照片不在应用已完成的相册目录中。");
        PhotoPreviewCache.validateJpeg(file, file.length()); return file;
    }
    private static DownloadedPhoto copy(DownloadedPhoto photo, File file, String filename) {
        return new DownloadedPhoto(file, filename, photo.projectId(), photo.projectName(), photo.photoDate());
    }
    private AtomicFile choice(String id) throws IOException {
        if (id == null || !id.matches("(?:photo|orphan)-[0-9a-f]{64}")) throw new IOException("照片组身份无效。");
        for (String suffix : new String[]{".choice", ".choice.bak", ".choice.new"}) {
            File file = new File(choices, id + suffix);
            if (!choices.equals(file.getCanonicalFile().getParentFile())) throw new IOException("照片展示记录路径无效。");
        }
        return new AtomicFile(new File(choices, id + ".choice"));
    }
    private String readChoice(String id) throws IOException {
        AtomicFile record = choice(id); File base = record.getBaseFile();
        if (!base.exists() && !new File(base.getPath() + ".bak").exists()) return "";
        if (base.length() > 32768) return "";
        try (DataInputStream input = new DataInputStream(record.openRead())) {
            if (input.readInt() != CHOICE_MAGIC || !id.equals(input.readUTF())) return "";
            String path = input.readUTF(); return input.read() == -1 ? path : "";
        } catch (IOException malformed) { return ""; }
    }
    private void writeChoice(String id, String path) throws IOException {
        if (!files.equals(choices.getCanonicalFile().getParentFile()) || !choices.isDirectory() && !choices.mkdirs() && !choices.isDirectory()) throw new IOException("无法保存照片展示选择。");
        AtomicFile record = choice(id); FileOutputStream output = null;
        try {
            output = record.startWrite(); DataOutputStream data = new DataOutputStream(output);
            data.writeInt(CHOICE_MAGIC); data.writeUTF(id); data.writeUTF(path); data.flush(); output.getFD().sync();
            record.finishWrite(output); output = null;
        } finally { if (output != null) record.failWrite(output); }
    }
    private static void check(BooleanSupplier cancel) throws InterruptedIOException {
        if (cancel.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("照片版本整理已取消。");
    }
    private static String digest(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}

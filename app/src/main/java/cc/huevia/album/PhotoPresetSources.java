package cc.huevia.album;

import android.util.AtomicFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Private, durable links from an effect to the exact bytes of its immediate input. */
final class PhotoPresetSources {
    private static final int MAGIC = 0x50425331, OUTPUT_MAGIC = 0x50424531, META_MAGIC = 0x50424d31;
    private static final int MAX_RECORD = 32768, MAX_CANDIDATES = 2048, MAX_DIRECTORY_ENTRIES = 20000, MAX_PRESETS = 2048, MAX_SOURCE_DEPTH = 64;
    private static final long MAX_PHOTO = 512L * 1024 * 1024, MAX_SCAN_BYTES = 2L * 1024 * 1024 * 1024;
    private static final ReentrantLock LOCK = new ReentrantLock();
    private final File files, records;
    private final List<File> directories = new ArrayList<>();
    interface PresetShas { List<String> read() throws IOException; }

    PhotoPresetSources(File filesDirectory, File externalPicturesDirectory) throws IOException {
        files = filesDirectory.getCanonicalFile();
        records = new File(files, "photo-preset-results").getCanonicalFile();
        if (!files.equals(records.getParentFile())) throw new IOException("效果图记录路径无效。");
        directories.add(new File(new File(files, "pictures"), "camera-album").getCanonicalFile());
        if (externalPicturesDirectory != null) {
            File external = new File(externalPicturesDirectory, "camera-album").getCanonicalFile();
            if (!directories.contains(external)) directories.add(external);
        }
    }

    /** Called for newly completed output and verified cache hits. Never changes an old output ledger. */
    void bind(DownloadedPhoto effect, DownloadedPhoto source, String presetSha) throws IOException {
        bind(effect, source, presetSha, () -> false);
    }
    void bind(DownloadedPhoto effect, DownloadedPhoto source, String presetSha, BooleanSupplier cancel) throws IOException {
        bindRelationship(effect, source, presetSha, PhotoPresetRenderer.MODEL_VERSION, cancel);
    }
    /** Friendly names are independent of the immutable content/association evidence. */
    void bind(DownloadedPhoto effect, DownloadedPhoto source, String presetSha, String presetName, BooleanSupplier cancel) throws IOException {
        bind(effect, source, presetSha, presetName, PhotoPresetRenderer.MODEL_VERSION, cancel);
    }
    void bind(DownloadedPhoto effect, DownloadedPhoto source, String presetSha, String presetName, String model, BooleanSupplier cancel) throws IOException {
        lock(cancel);
        try {
            bindRelationship(effect, source, presetSha, model, cancel);
            check(cancel);
            writeMetadata(effectKey(owned(effect.file())), cleanName(effect.filename()), cleanName(presetName));
        } finally { LOCK.unlock(); }
    }
    private void bindRelationship(DownloadedPhoto effect, DownloadedPhoto source, String presetSha, String model, BooleanSupplier cancel) throws IOException {
        lock(cancel);
        try {
            check(cancel);
            File target = owned(effect.file()), input = owned(source.file());
            String key = effectKey(target);
            if (key == null || target.equals(input)) throw new IOException("效果图原图关联无效。");
            String sourceSha = hash(input, cancel), outputSha = verifyOutput(key, target, cancel);
            if (model == null || model.isEmpty() || model.length() > 512 || model.indexOf('\n') >= 0 || model.indexOf('\r') >= 0) throw new IOException("效果图模型身份无效。");
            if (!key.equals(contentKey(sourceSha, presetSha, model))) throw new IOException("效果图与原图身份不符。");
            Link actual = read(key);
            if (actual != null) {
                checkOutput(actual, key, target, outputSha);
                if (actual.sourceBytes != input.length() || !actual.sourceSha.equals(sourceSha)) throw new IOException("已有原图关联不符，未覆盖。");
                if (!actual.key.equals(contentKey(actual.sourceSha, actual.presetSha, actual.model))) throw new IOException("已有原图关联身份不符。");
                // Keep the first valid alias. Only an explicit, byte-identical batch
                // input may repair a vanished/changed first path; lookup never guesses.
                try { source(actual, cancel); return; }
                catch (InterruptedIOException stopped) { throw stopped; }
                catch (IOException unavailable) { /* The verified current input below has the same identity. */ }
            }
            Link link = new Link(key, target.getPath(), target.length(), outputSha, input.getPath(), input.length(), sourceSha,
                    source.filename(), source.projectId(), source.projectName(), presetSha, model);
            check(cancel); write(link);
        } finally { LOCK.unlock(); }
    }

    /** Persist the exact renderer identity before image commit, so interrupted strength exports recover. */
    void rememberModel(String key, String model) throws IOException {
        lock(() -> false);
        try {
            if (model == null || model.isBlank() || model.length() > 256 || model.matches("(?s).*[\\p{Cntrl}].*")) throw new IOException("效果模型无效。");
            AtomicFile file = record(key, ".model"); FileOutputStream output = null;
            try {
                output = file.startWrite(); DataOutputStream out = new DataOutputStream(output);
                out.writeInt(0x50454d31); out.writeUTF(model); out.flush(); output.getFD().sync();
                file.finishWrite(output); output = null;
            } finally { if (output != null) file.failWrite(output); }
        } finally { LOCK.unlock(); }
    }
    private List<String> recoveryModels(String key) throws IOException {
        List<String> models = new ArrayList<>(); models.add(PhotoPresetRenderer.MODEL_VERSION);
        models.add(PhotoPresetRenderer.NP3_MODEL_VERSION);
        models.add(PhotoPresetRenderer.NP3_MONOCHROME_MODEL_VERSION);
        models.add(PhotoPresetRenderer.NP3_STANDARD_MONOCHROME_MODEL_VERSION);
        models.add(PhotoPresetRenderer.LEGACY_NP3_SPATIAL_MODEL_VERSION);
        models.add(PhotoPresetRenderer.LEGACY_NP3_MODEL_VERSION);
        AtomicFile file = record(key, ".model");
        if (recordExists(file)) {
            if (file.getBaseFile().length() > 1024) throw new IOException("效果模型记录无效。");
            try (DataInputStream in = new DataInputStream(file.openRead())) {
                if (in.readInt() != 0x50454d31) throw new IOException("效果模型记录损坏。");
                String model = in.readUTF();
                if (model.isBlank() || model.length() > 256 || model.matches("(?s).*[\\p{Cntrl}].*") || in.read() != -1) throw new IOException("效果模型记录无效。");
                if (!models.contains(model)) models.add(model);
            }
        }
        return models;
    }
    /** Verified read-only relation. Unlike find(), this never scans/migrates neighbouring files. */
    Relation relation(DownloadedPhoto effect, BooleanSupplier cancel) throws IOException {
        return relation(effect, cancel, new java.util.HashMap<>());
    }
    /** Hash memo belongs to one worker snapshot only, never to UI or a later reload. */
    Relation relation(DownloadedPhoto effect, BooleanSupplier cancel, Map<String, String> hashes) throws IOException {
        lock(cancel);
        try {
            check(cancel);
            File target = owned(effect.file()); String key = effectKey(target);
            if (key == null) return null;
            Link first = read(key);
            if (first == null) throw new IOException("效果图原图关联尚未保存。");
            checkOutput(first, key, target, verifyOutput(key, target, cancel, hashes));
            DownloadedPhoto immediate = source(first, cancel, hashes), original = immediate;
            Set<String> visited = new LinkedHashSet<>(); visited.add(target.getPath());
            String originalSha = first.sourceSha;
            for (int depth = 1; ; depth++) {
                check(cancel); File input = owned(original.file());
                if (!visited.add(input.getPath())) throw new IOException("效果图原图关联出现循环。");
                String sourceKey = effectKey(input);
                if (sourceKey == null) break;
                if (depth >= MAX_SOURCE_DEPTH) throw new IOException("效果图原图关联层级过多。");
                Link ancestor = read(sourceKey);
                if (ancestor == null) throw new IOException("效果图原图关联尚未保存。");
                checkOutput(ancestor, sourceKey, input, verifyOutput(sourceKey, input, cancel, hashes));
                original = source(ancestor, cancel, hashes); originalSha = ancestor.sourceSha;
            }
            Metadata metadata = metadata(key);
            return new Relation(immediate, original, first.presetSha, originalSha, metadata.filename, metadata.presetName);
        } finally { LOCK.unlock(); }
    }
    static final class Relation {
        final DownloadedPhoto input, original;
        final String presetSha, originalSha, filename, presetName;
        Relation(DownloadedPhoto input, DownloadedPhoto original, String presetSha, String originalSha, String filename, String presetName) {
            this.input = input; this.original = original; this.presetSha = presetSha; this.originalSha = originalSha;
            this.filename = filename; this.presetName = presetName;
        }
    }
    /** Presentation-only metadata never establishes a source relationship. */
    Metadata metadata(DownloadedPhoto effect) throws IOException {
        if (effect == null || effect.file() == null) return Metadata.EMPTY;
        return metadata(effectKey(owned(effect.file())));
    }
    static boolean isEffect(File file) { return file != null && effectKey(file) != null; }
    static final class Metadata {
        static final Metadata EMPTY = new Metadata("", "");
        final String filename, presetName;
        Metadata(String filename, String presetName) { this.filename = filename; this.presetName = presetName; }
    }
    private Metadata metadata(String key) throws IOException {
        if (key == null) return Metadata.EMPTY;
        AtomicFile record = record(key, ".meta"); if (!recordExists(record)) return Metadata.EMPTY;
        try (DataInputStream input = new DataInputStream(record.openRead())) {
            if (input.readInt() != META_MAGIC) return Metadata.EMPTY;
            String filename = input.readUTF(), presetName = input.readUTF();
            if (filename.length() > 256 || presetName.length() > 256 || input.read() != -1) return Metadata.EMPTY;
            return new Metadata(cleanName(filename), cleanName(presetName));
        } catch (IOException malformed) { return Metadata.EMPTY; }
    }
    private void writeMetadata(String key, String filename, String presetName) throws IOException {
        Metadata old = metadata(key);
        // Old APIs never erase names, and a renamed library preset does not rename an existing export.
        if (!old.filename.isEmpty()) filename = old.filename;
        if (!old.presetName.isEmpty()) presetName = old.presetName;
        if (old.filename.equals(filename) && old.presetName.equals(presetName)) return;
        AtomicFile record = record(key, ".meta"); FileOutputStream output = null;
        try {
            output = record.startWrite(); DataOutputStream data = new DataOutputStream(output);
            data.writeInt(META_MAGIC); data.writeUTF(filename); data.writeUTF(presetName); data.flush(); output.getFD().sync();
            record.finishWrite(output); output = null;
        } finally { if (output != null) record.failWrite(output); }
    }
    private static String cleanName(String name) {
        String clean = name == null ? "" : name.replaceAll("[\\p{Cntrl}/\\\\]", "_").trim();
        return clean.substring(0, Math.min(256, clean.length()));
    }

    /** Normal originals have no source. Damaged links/changed files fail instead of showing a wrong comparison. */
    DownloadedPhoto find(DownloadedPhoto effect, PresetShas committedPresetShas, BooleanSupplier cancel) throws IOException {
        lock(cancel);
        try {
            check(cancel);
            if (effect == null || effect.file() == null) return null;
            File target = owned(effect.file()); String key = effectKey(target);
            if (key == null) return null;
            Link link = read(key);
            if (link != null) {
                String outputSha = verifyOutput(key, target, cancel); checkOutput(link, key, target, outputSha);
                return source(link, cancel);
            }
            // Older outputs, or an interruption after the image commit but before the
            // source-link commit, can have only the output ledger. Recover only a full
            // source/preset/registered-model identity, never a filename or visual guess.
            // Only legacy NCP outputs predate links; newer models bind their verified
            // input immediately and never participate in this recovery scan.
            if (!recordExists(record(key, ".record"))) return null;
            verifyOutput(key, target, cancel); check(cancel);
            Set<String> presets = new LinkedHashSet<>();
            int presetCount = 0;
            for (String sha : committedPresetShas.read()) {
                check(cancel); if (presetCount++ >= MAX_PRESETS) break;
                if (sha != null && sha.matches("[0-9a-f]{64}")) presets.add(sha);
            }
            if (presets.isEmpty()) return null;
            Comparator<File> recent = Comparator.comparingLong(File::lastModified).thenComparing(File::getPath);
            PriorityQueue<File> newest = new PriorityQueue<>(MAX_CANDIDATES, recent);
            int entries = 0;
            for (File directory : directories) {
                if (!directory.isDirectory()) continue;
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory.toPath())) {
                    for (Path path : stream) {
                        check(cancel); if (entries++ >= MAX_DIRECTORY_ENTRIES) break;
                        File file = path.toFile();
                        if (!file.isFile() || !file.getName().matches("(?i).+\\.jpe?g")) continue;
                        if (newest.size() < MAX_CANDIDATES) newest.add(file);
                        else if (recent.compare(file, newest.peek()) > 0) { newest.poll(); newest.add(file); }
                    }
                }
                if (entries >= MAX_DIRECTORY_ENTRIES) break;
            }
            List<File> candidates = new ArrayList<>(newest); candidates.sort(recent.reversed());
            List<String> registeredModels = recoveryModels(key);
            int inspected = 0; long bytes = 0;
            for (File candidate : candidates) {
                check(cancel);
                if (inspected++ >= MAX_CANDIDATES) break;
                File input;
                try { input = owned(candidate); }
                catch (IOException unavailable) { continue; }
                if (input.equals(target)) continue;
                if (input.length() > MAX_SCAN_BYTES - bytes) continue;
                bytes += input.length(); String sourceSha = hash(input, cancel);
                for (String presetSha : presets) {
                    check(cancel);
                    String matchedModel = null;
                    for (String model : registeredModels) {
                        if (key.equals(contentKey(sourceSha, presetSha, model))) { matchedModel = model; break; }
                    }
                    if (matchedModel == null) continue;
                    String name = input.getName(); int marker = name.indexOf("__");
                    DownloadedPhoto direct = new DownloadedPhoto(input, marker < 0 ? name : name.substring(marker + 2), effect.projectId(), effect.projectName());
                    check(cancel); bindRelationship(effect, direct, presetSha, matchedModel, cancel);
                    return source(read(key), cancel);
                }
            }
            return null;
        } finally { LOCK.unlock(); }
    }

    /** Processing always starts at a verified original, while find() retains immediate-input comparison semantics. */
    DownloadedPhoto processingOriginalFor(DownloadedPhoto photo, PresetShas committedPresetShas, BooleanSupplier cancel) throws IOException {
        if (photo == null || cancel == null || committedPresetShas == null) throw new IllegalArgumentException("photo/presets/cancel");
        lock(cancel);
        try {
            Set<String> visited = new LinkedHashSet<>();
            DownloadedPhoto current = photo;
            for (int depth = 0; ; depth++) {
                check(cancel);
                File file = owned(current.file());
                if (!visited.add(file.getPath())) throw new IOException("效果图原图关联出现循环，请重新选择原图。");
                if (effectKey(file) == null) return current;
                if (depth >= MAX_SOURCE_DEPTH) throw new IOException("效果图原图关联层级过多，请直接选择原图。");
                DownloadedPhoto input = find(current, committedPresetShas, cancel);
                if (input == null) throw new IOException("无法核对这张效果图的原图，请选择原图后套用预设。");
                current = input;
            }
        } finally { LOCK.unlock(); }
    }

    private DownloadedPhoto source(Link link, BooleanSupplier cancel) throws IOException {
        return source(link, cancel, null);
    }
    private DownloadedPhoto source(Link link, BooleanSupplier cancel, Map<String, String> hashes) throws IOException {
        if (link == null) throw new IOException("效果图原图关联尚未保存。");
        File input = owned(new File(link.sourcePath));
        if (!input.getPath().equals(link.sourcePath) || input.length() != link.sourceBytes || !snapshotHash(input, cancel, hashes).equals(link.sourceSha)) throw new IOException("效果图的原图已变化或不可用。");
        if (!link.key.equals(contentKey(link.sourceSha, link.presetSha, link.model))) throw new IOException("效果图原图关联损坏。");
        return new DownloadedPhoto(input, link.filename, link.projectId, link.projectName);
    }
    private void checkOutput(Link link, String key, File target, String sha) throws IOException {
        // The same content-addressed output can live in both supported private
        // directories. Its strict key plus verified bytes identify it, not which
        // directory was available first; the first valid input stays authoritative.
        if (!key.equals(link.key) || target.length() != link.outputBytes || !sha.equals(link.outputSha)
                || link.outputPath.equals(link.sourcePath) || target.getPath().equals(link.sourcePath)) throw new IOException("效果图原图关联校验不符。");
    }
    private File owned(File candidate) throws IOException {
        if (candidate == null) throw new IOException("照片不存在。");
        File file = candidate.getCanonicalFile();
        if (!directories.contains(file.getParentFile()) || file.length() > MAX_PHOTO) throw new IOException("照片不在应用已完成的相册目录中。");
        PhotoPreviewCache.validateJpeg(file, file.length()); return file;
    }
    private static String effectKey(File file) {
        String name = file.getName();
        return name.matches("effect-[0-9a-f]{64}__Effect\\.JPG") ? name.substring(7, 71) : null;
    }
    private AtomicFile record(String key, String suffix) throws IOException {
        if (!key.matches("[0-9a-f]{64}")) throw new IOException("效果图记录身份无效。");
        for (String ending : new String[]{suffix, suffix + ".bak", suffix + ".new"}) {
            File path = new File(records, key + ending);
            if (!records.equals(path.getCanonicalFile().getParentFile())) throw new IOException("效果图记录路径无效。");
        }
        return new AtomicFile(new File(records, key + suffix));
    }
    private static boolean recordExists(AtomicFile record) {
        return record.getBaseFile().exists() || new File(record.getBaseFile().getPath() + ".bak").exists();
    }
    private String verifyOutput(String key, File target, BooleanSupplier cancel) throws IOException {
        return verifyOutput(key, target, cancel, null);
    }
    private String verifyOutput(String key, File target, BooleanSupplier cancel, Map<String, String> hashes) throws IOException {
        AtomicFile ledger = record(key, ".record");
        try (DataInputStream input = new DataInputStream(ledger.openRead())) {
            if (input.readInt() != OUTPUT_MAGIC) throw new IOException("效果图完成记录损坏。");
            long bytes = input.readLong(); String sha = input.readUTF();
            if (bytes < 4 || bytes > MAX_PHOTO || bytes != target.length() || !sha.matches("[0-9a-f]{64}") || input.read() != -1 || !sha.equals(snapshotHash(target, cancel, hashes))) throw new IOException("已保存的效果图校验不符。");
            return sha;
        }
    }
    private Link read(String key) throws IOException {
        AtomicFile record = record(key, ".source"); if (!recordExists(record)) return null;
        try (DataInputStream input = new DataInputStream(record.openRead())) {
            if (input.readInt() != MAGIC) throw new IOException("效果图原图关联损坏，未覆盖。");
            int bytes = input.readInt(); if (bytes < 1 || bytes > MAX_RECORD) throw new IOException("效果图原图关联无效。");
            byte[] payload = new byte[bytes], checksum = new byte[32]; input.readFully(payload); input.readFully(checksum);
            if (input.read() != -1 || !MessageDigest.isEqual(checksum, digest().digest(payload))) throw new IOException("效果图原图关联校验失败。");
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload))) {
                Link link = new Link(data.readUTF(), data.readUTF(), data.readLong(), data.readUTF(), data.readUTF(), data.readLong(), data.readUTF(),
                        data.readUTF(), data.readUTF(), data.readUTF(), data.readUTF(), data.readUTF());
                if (!key.equals(link.key) || link.sourceBytes < 4 || link.sourceBytes > MAX_PHOTO || link.outputBytes < 4 || link.outputBytes > MAX_PHOTO
                        || !link.sourceSha.matches("[0-9a-f]{64}") || !link.outputSha.matches("[0-9a-f]{64}") || !link.presetSha.matches("[0-9a-f]{64}")
                        || link.filename.length() > 2048 || link.projectId.length() > 128 || link.projectName.length() > 256 || link.model.length() > 512 || data.read() != -1) throw new IOException("效果图原图关联无效。");
                return link;
            }
        }
    }
    private void write(Link link) throws IOException {
        if (!files.equals(records.getCanonicalFile().getParentFile()) || !records.isDirectory() && !records.mkdirs() && !records.isDirectory()) throw new IOException("无法保存效果图原图关联。");
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(buffer)) {
            data.writeUTF(link.key); data.writeUTF(link.outputPath); data.writeLong(link.outputBytes); data.writeUTF(link.outputSha);
            data.writeUTF(link.sourcePath); data.writeLong(link.sourceBytes); data.writeUTF(link.sourceSha);
            data.writeUTF(link.filename); data.writeUTF(link.projectId); data.writeUTF(link.projectName); data.writeUTF(link.presetSha); data.writeUTF(link.model);
        }
        byte[] payload = buffer.toByteArray(); if (payload.length > MAX_RECORD) throw new IOException("效果图原图关联过长。");
        AtomicFile record = record(link.key, ".source"); FileOutputStream output = null;
        try {
            output = record.startWrite(); DataOutputStream data = new DataOutputStream(output);
            data.writeInt(MAGIC); data.writeInt(payload.length); data.write(payload); data.write(digest().digest(payload)); data.flush(); output.getFD().sync();
            record.finishWrite(output); output = null;
            Link actual = read(link.key);
            if (actual == null || !actual.sourcePath.equals(link.sourcePath) || !actual.outputSha.equals(link.outputSha)) throw new IOException("效果图原图关联尚未确认保存。");
        } finally { if (output != null) record.failWrite(output); }
    }
    private static final class Link {
        final String key, outputPath, outputSha, sourcePath, sourceSha, filename, projectId, projectName, presetSha, model;
        final long outputBytes, sourceBytes;
        Link(String key, String outputPath, long outputBytes, String outputSha, String sourcePath, long sourceBytes, String sourceSha,
                String filename, String projectId, String projectName, String presetSha, String model) {
            this.key = key; this.outputPath = outputPath; this.outputBytes = outputBytes; this.outputSha = outputSha;
            this.sourcePath = sourcePath; this.sourceBytes = sourceBytes; this.sourceSha = sourceSha; this.filename = filename;
            this.projectId = projectId; this.projectName = projectName; this.presetSha = presetSha; this.model = model;
        }
    }
    private static String contentKey(String sourceSha, String presetSha, String model) throws IOException {
        if (!sourceSha.matches("[0-9a-f]{64}") || !presetSha.matches("[0-9a-f]{64}")) throw new IOException("效果图身份无效。");
        return hex(digest().digest((sourceSha + "\n" + presetSha + "\n" + model).getBytes(StandardCharsets.UTF_8)));
    }
    private static String hash(File file, BooleanSupplier cancel) throws IOException {
        MessageDigest digest = digest(); byte[] buffer = new byte[65536];
        check(cancel);
        try (FileInputStream input = new FileInputStream(file)) { int count; long read = 0; while ((count = input.read(buffer)) != -1) { check(cancel); read += count; if (read > MAX_PHOTO) throw new IOException("照片超过 512 MB 上限。"); digest.update(buffer, 0, count); } }
        check(cancel);
        return hex(digest.digest());
    }
    static String snapshotHash(File file, BooleanSupplier cancel, Map<String, String> hashes) throws IOException {
        check(cancel);
        long bytes = file.length(), modified = file.lastModified();
        String key = file.getCanonicalPath() + "\n" + bytes + "\n" + modified;
        String previous = hashes == null ? null : hashes.get(key);
        if (previous != null) return previous;
        String sha = hash(file, cancel);
        if (bytes != file.length() || modified != file.lastModified()) throw new IOException("照片在读取时发生变化，请重试。");
        if (hashes != null) hashes.put(key, sha);
        return sha;
    }
    private static void check(BooleanSupplier cancel) throws InterruptedIOException {
        if (cancel.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("原图对比读取已取消。");
    }
    private static void lock(BooleanSupplier cancel) throws InterruptedIOException {
        boolean acquired = false;
        try {
            check(cancel);
            while (!(acquired = LOCK.tryLock(100, TimeUnit.MILLISECONDS))) check(cancel);
            check(cancel);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("原图对比读取已取消。");
        } catch (InterruptedIOException | RuntimeException | Error cancelled) {
            if (acquired) LOCK.unlock(); throw cancelled;
        }
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static String hex(byte[] bytes) { StringBuilder value = new StringBuilder(64); for (byte item : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255)); return value.toString(); }
}

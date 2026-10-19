package com.nikonpreset.usbprobe.album;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;

/** Local worker only. Caller holds the exclusive PhotoLocalTasks reservation. No camera API is used. */
final class PhotoAlbumDeletion {
    private final Context context;
    PhotoAlbumDeletion(Context context) { this.context = context; }
    PhotoDeleteResult delete(List<DownloadedPhoto> photos) {
        List<DownloadedPhoto> deleted = new ArrayList<>(); List<PhotoDeleteResult.Failure> failures = new ArrayList<>();
        Map<String, DownloadedPhoto> unique = new LinkedHashMap<>();
        for (DownloadedPhoto photo : photos) try { unique.putIfAbsent(photo.file().getCanonicalPath(), photo); }
        catch (Exception invalid) { failures.add(new PhotoDeleteResult.Failure(photo, "照片信息无效，未继续删除。")); }
        List<DownloadedPhoto> ordered = new ArrayList<>(unique.values());
        // Effects are removed before their original. A failed child keeps the whole verified dependency chain.
        ordered.sort(Comparator.comparingInt(photo -> PhotoPresetSources.isEffect(photo.file()) ? 0 : 1));
        Set<String> protectedShas = new LinkedHashSet<>(), protectedPaths = new LinkedHashSet<>();
        Map<String, PhotoPresetSources.Relation> relations = new LinkedHashMap<>();
        boolean protectOriginals = false;
        PhotoPresetSources sources;
        try {
            sources = new PhotoPresetSources(context.getFilesDir(), context.getExternalFilesDir(Environment.DIRECTORY_PICTURES));
            // A single-original request must not orphan an effect that was not included in the request.
            List<File> directories = new ArrayList<>();
            directories.add(new File(new File(context.getFilesDir(), "pictures"), "camera-album"));
            File external = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (external != null) directories.add(new File(external, "camera-album"));
            int inspected = 0;
            for (File directory : directories) {
                File[] files = directory.listFiles(PhotoPresetSources::isEffect);
                if (files == null) { if (directory.isDirectory()) protectOriginals = true; continue; }
                for (File file : files) {
                    if (++inspected > 20000) { protectOriginals = true; break; }
                    try {
                        PhotoPresetSources.Relation relation = sources.relation(new DownloadedPhoto(file, file.getName()), () -> false);
                        if (relation == null) { if (!unique.containsKey(file.getCanonicalPath())) protectOriginals = true; continue; }
                        relations.put(file.getCanonicalPath(), relation);
                        if (!unique.containsKey(file.getCanonicalPath())) {
                            protectedShas.add(relation.originalSha);
                            protectedPaths.add(relation.input.file().getCanonicalPath());
                        }
                    } catch (IOException unresolved) { if (!unique.containsKey(file.getCanonicalPath())) protectOriginals = true; }
                }
            }
            // Old stacked effects retain immediate-input links; delete leaves before selected parent effects.
            ordered.sort(Comparator.<DownloadedPhoto>comparingInt(photo -> effectDepth(photo.file(), relations)).reversed());
        } catch (IOException unavailable) { protectOriginals = true; }
        for (DownloadedPhoto photo : ordered) {
            try {
                String path = photo.file().getCanonicalPath();
                boolean effect = PhotoPresetSources.isEffect(photo.file());
                if (protectedPaths.contains(path) || !effect && (protectOriginals || protectedShas.contains(hash(owned(photo))))) {
                    throw new IOException("仍有效果图依赖这张原图，原图已保留；请先完成效果图删除后重试。");
                }
                remove(photo); deleted.add(photo);
            } catch (Exception | OutOfMemoryError failure) {
                if (PhotoPresetSources.isEffect(photo == null ? null : photo.file())) {
                    try {
                        PhotoPresetSources.Relation relation = relations.get(photo.file().getCanonicalPath());
                        if (relation == null) protectOriginals = true;
                        else {
                            protectedShas.add(relation.originalSha);
                            String parent = relation.input.file().getCanonicalPath();
                            for (int depth = 0; depth < 64 && protectedPaths.add(parent); depth++) {
                                PhotoPresetSources.Relation ancestor = relations.get(parent); if (ancestor == null) break;
                                parent = ancestor.input.file().getCanonicalPath();
                            }
                        }
                    } catch (IOException unresolved) { protectOriginals = true; }
                }
                String message = failure.getMessage();
                failures.add(new PhotoDeleteResult.Failure(photo, message == null || message.isEmpty() ? "照片删除未完成，应用内文件已保留，请重试。" : message));
            }
        }
        return new PhotoDeleteResult(deleted, failures);
    }
    private static int effectDepth(File file, Map<String, PhotoPresetSources.Relation> relations) {
        if (!PhotoPresetSources.isEffect(file)) return 0;
        try {
            Set<String> seen = new LinkedHashSet<>(); String path = file.getCanonicalPath(); int depth = 1;
            while (depth < 64 && seen.add(path)) {
                PhotoPresetSources.Relation relation = relations.get(path);
                if (relation == null || !PhotoPresetSources.isEffect(relation.input.file())) break;
                path = relation.input.file().getCanonicalPath(); depth++;
            }
            return depth;
        } catch (IOException invalid) { return 1; }
    }
    private void remove(DownloadedPhoto photo) throws IOException {
        File source = owned(photo); long bytes = source.length(), modified = source.lastModified(); String sha = hash(source);
        new PhotoLiveGallery(context).deletePublished(photo); // Provider failure leaves every private source/reference intact.
        PhotoLiveProjectStore projects = new PhotoLiveProjectStore(new File(context.getFilesDir(), "photo-live-projects"), source.getParentFile());
        projects.removeFile(source, bytes, sha); // Commit removals before unlink: a crash never creates a new dangling member.
        File checked = owned(photo);
        if (!source.equals(checked) || bytes != checked.length() || modified != checked.lastModified() || !sha.equals(hash(checked))) throw new IOException("照片在删除过程中发生变化，应用内文件未删除。");
        Files.delete(checked.toPath());
    }
    private File owned(DownloadedPhoto photo) throws IOException {
        if (photo == null || photo.file() == null) throw new IOException("照片信息无效。");
        File expected = photo.file().getAbsoluteFile(), file = expected.getCanonicalFile();
        File internal = new File(new File(context.getFilesDir(), "pictures"), "camera-album").getCanonicalFile();
        File externalPictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        File external = externalPictures == null ? null : new File(externalPictures, "camera-album").getCanonicalFile();
        if (!expected.equals(file) || Files.isSymbolicLink(expected.toPath()) || !internal.equals(file.getParentFile()) && (external == null || !external.equals(file.getParentFile()))
                || !file.isFile() || !file.getName().toLowerCase(Locale.ROOT).matches(".*\\.jpe?g") || file.length() > 512L * 1024 * 1024) throw new IOException("仅可删除应用内已完成的 JPEG 照片。");
        PhotoPreviewCache.validateJpeg(file, file.length()); return file;
    }
    private static String hash(File file) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        try (FileInputStream input = new FileInputStream(file)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
        StringBuilder result = new StringBuilder(64); for (byte value : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", value & 255)); return result.toString();
    }
}

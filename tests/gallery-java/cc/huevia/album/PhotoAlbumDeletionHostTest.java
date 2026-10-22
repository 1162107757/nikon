package cc.huevia.album;

import android.content.Context;
import android.os.Build;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Production local deletion and project sequencing against the real gallery/provider host facade. */
public final class PhotoAlbumDeletionHostTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("photo-album-deletion-");
        try {
            removesBothCopiesAndEveryProjectReference(root.resolve("normal"));
            removesInternalSourceReferencesWithExternalStorageAvailable(root.resolve("internal"));
            providerFailureRetainsSourceAndSupportsPartialRetry(root.resolve("partial"));
            metadataFailureKeepsSourceAfterPublicDeletion(root.resolve("metadata"));
            rejectsForeignAndIncompleteFiles(root.resolve("invalid"));
            transferableReservationsExcludeQueuedMutations();
            effectsDeleteBeforeOriginalsAndFailuresProtectEveryAlias(root.resolve("effect-failure"));
            effectOnlyKeepsTheOriginalAndUnselectedEffectsProtectIt(root.resolve("effect-only"));
            failedStackedEffectProtectsItsImmediateInput(root.resolve("stacked"));
            wholeGroupDeletesBothPrivateEffectAliasesAndOnePublication(root.resolve("alias-delete"), 35);
            wholeGroupDeletesBothPrivateEffectAliasesAndOnePublication(root.resolve("alias-delete-legacy"), 28);
            System.out.println("PASS: " + checks + " local photo deletion checks");
        } finally { try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); } }
    }
    private static void removesBothCopiesAndEveryProjectReference(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto one = f.photo("original.JPG", 11), two = f.photo("kept.JPG", 22);
        PhotoLiveProject a = f.store.create("A"), b = f.store.create("B");
        f.store.record(a.id, "1".repeat(64), one); f.store.record(a.id, "2".repeat(64), two); f.store.record(b.id, "3".repeat(64), one);
        String uri = f.gallery.publish(one); f.gallery.publish(two);
        var row = f.context.resolver.rows.values().stream().filter(item -> item.uri.equals(uri)).findFirst().orElseThrow(); row.name = "renamed.jpg"; row.path = "Pictures/Moved/";
        PhotoDeleteResult result = f.delete(List.of(one, one));
        check(result.total == 1 && result.deleted == 1 && result.failed == 0, "Duplicate selected paths have one successful terminal deletion");
        check(!one.file().exists() && two.file().isFile() && f.context.resolver.rows.size() == 1, "Only the selected private original and its moved/renamed owned public copy are removed");
        check(f.store.get(a.id).photoCount == 1 && f.store.get(b.id).photoCount == 0 && f.store.get(a.id).totalBytes == two.byteSize(), "Every project count and persisted byte total excludes the deleted source");
        PhotoLiveProjectStore restored = new PhotoLiveProjectStore(f.catalog.toFile(), f.originals.toFile());
        check(restored.list().size() == 2 && restored.get(a.id).photos.get(0).file().equals(two.file().getCanonicalFile()), "Catalog remains readable after unlink and cold reconstruction");
        try { result.deletedPhotos.clear(); throw new AssertionError("mutable result"); } catch (UnsupportedOperationException expected) { checks++; }
    }
    private static void providerFailureRetainsSourceAndSupportsPartialRetry(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto good = f.photo("good.JPG", 32), protectedPhoto = f.photo("protected.JPG", 47);
        PhotoLiveProject p = f.store.create("Partial"); f.store.record(p.id, "a".repeat(64), good); f.store.record(p.id, "b".repeat(64), protectedPhoto);
        f.gallery.publish(good); String protectedUri = f.gallery.publish(protectedPhoto);
        var protectedRow = f.context.resolver.rows.values().stream().filter(item -> item.uri.equals(protectedUri)).findFirst().orElseThrow(); protectedRow.owner = "foreign.application";
        byte[] original = Files.readAllBytes(protectedPhoto.file().toPath());
        PhotoDeleteResult result = f.delete(List.of(good, protectedPhoto));
        check(result.total == 2 && result.deleted == 1 && result.failed == 1 && !result.failures.get(0).message.isEmpty(), "Mixed provider outcomes remain explicit per-file partial results");
        check(!good.file().exists() && Arrays.equals(original, Files.readAllBytes(protectedPhoto.file().toPath())), "Provider ownership failure never deletes its private source");
        check(f.store.get(p.id).photoCount == 1 && f.store.get(p.id).photos.get(0).file().equals(protectedPhoto.file().getCanonicalFile()), "Failed item retains project membership while successful membership is removed");
        protectedRow.owner = f.context.getPackageName(); PhotoDeleteResult retry = f.delete(List.of(protectedPhoto));
        check(retry.deleted == 1 && retry.failed == 0 && f.context.resolver.rows.isEmpty() && f.store.get(p.id).photoCount == 0, "A later ownership-correct retry completes locally without camera acquisition");
    }
    private static void removesInternalSourceReferencesWithExternalStorageAvailable(Path root) throws Exception {
        Fixture f = new Fixture(root); Path internal = f.context.getFilesDir().toPath().resolve("pictures/camera-album"); Files.createDirectories(internal);
        Path file = internal.resolve("internal-original.JPG"); Files.write(file, jpeg(27)); DownloadedPhoto photo = new DownloadedPhoto(file.toFile(), "internal-original.JPG");
        PhotoLiveProjectStore internalStore = new PhotoLiveProjectStore(f.catalog.toFile(), internal.toFile()); PhotoLiveProject project = internalStore.create("Internal");
        internalStore.record(project.id, "d".repeat(64), photo); f.gallery.publish(photo);
        PhotoDeleteResult result = f.delete(List.of(photo));
        check(result.deleted == 1 && result.failed == 0 && !Files.exists(file) && f.context.resolver.rows.isEmpty(), "An internal original deletes both copies even when external Pictures is available");
        check(internalStore.get(project.id).photoCount == 0 && f.store.get(project.id).photoCount == 0, "Internal-source cleanup removes every catalog entry rather than leaving a missing-source member");
    }
    private static void metadataFailureKeepsSourceAfterPublicDeletion(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto photo = f.photo("source.JPG", 58); PhotoLiveProject p = f.store.create("Blocked"); f.store.record(p.id, "c".repeat(64), photo); f.gallery.publish(photo);
        Path blocked = f.catalog.resolve(p.id + ".project.part"); Files.createDirectories(blocked); Files.writeString(blocked.resolve("keep"), "occupied");
        PhotoDeleteResult failed = f.delete(List.of(photo));
        check(failed.failed == 1 && failed.deleted == 0 && photo.file().isFile() && f.context.resolver.rows.isEmpty(), "Failed project cleanup keeps the app source even after its public copy was deleted");
        Files.delete(blocked.resolve("keep")); Files.delete(blocked); check(f.store.get(p.id).photoCount == 1, "Committed membership survives failed project cleanup");
        PhotoDeleteResult retried = f.delete(List.of(photo));
        check(retried.deleted == 1 && retried.failed == 0 && !photo.file().exists() && f.store.get(p.id).photoCount == 0, "Retry tolerates the already-deleted public URI and commits private removal");
    }
    private static void rejectsForeignAndIncompleteFiles(Path root) throws Exception {
        Fixture f = new Fixture(root); Path foreign = root.resolve("foreign.JPG"); Files.write(foreign, jpeg(91));
        DownloadedPhoto incomplete = f.photo("incomplete.JPG", 17); Files.write(incomplete.file().toPath(), new byte[]{(byte) 255, (byte) 216, 1});
        PhotoDeleteResult result = f.delete(List.of(new DownloadedPhoto(foreign.toFile(), "foreign.JPG"), incomplete));
        check(result.failed == 2 && result.deleted == 0 && Files.isRegularFile(foreign) && incomplete.file().isFile(), "Foreign paths and incomplete input do not reach destructive provider/file operations");
        check(f.context.resolver.deletes == 0, "Rejected app files cause no system-gallery deletion");
    }
    private static void transferableReservationsExcludeQueuedMutations() throws Exception {
        PhotoLocalTasks.Lease saving = PhotoLocalTasks.use();
        try { PhotoLocalTasks.delete(); throw new AssertionError("active use ignored"); } catch (IOException expected) { checks++; }
        Thread completed = new Thread(saving::close); completed.start(); completed.join(); saving.close();
        try (PhotoLocalTasks.Lease deleting = PhotoLocalTasks.delete()) {
            try { PhotoLocalTasks.use(); throw new AssertionError("exclusive use ignored"); } catch (IOException expected) { checks++; }
            try { PhotoLocalTasks.delete(); throw new AssertionError("second delete ignored"); } catch (IOException expected) { checks++; }
        }
        try (PhotoLocalTasks.Lease usableAgain = PhotoLocalTasks.use()) { checks++; }
    }
    private static void effectsDeleteBeforeOriginalsAndFailuresProtectEveryAlias(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.photo("DSC_1.JPG", 60), alias = f.photo("legacy-alias.JPG", 60), other = f.photo("DSC_2.JPG", 62);
        DownloadedPhoto effect = f.effect(original, "a".repeat(64), "Bluesky", 61);
        f.gallery.publish(original); f.gallery.publish(alias); String effectUri = f.gallery.publish(effect); f.gallery.publish(other);
        var row = f.context.resolver.rows.values().stream().filter(item -> item.uri.equals(effectUri)).findFirst().orElseThrow(); row.owner = "foreign.application";
        PhotoDeleteResult failed = f.delete(List.of(original, alias, effect, other));
        check(failed.deleted == 1 && failed.failed == 3 && !other.file().exists(), "An unrelated group deletes while a failed effect protects all original aliases");
        check(original.file().isFile() && alias.file().isFile() && effect.file().isFile() && f.context.resolver.rows.size() == 2, "Both private aliases and the original system-album copy remain usable after effect deletion fails");
        row.owner = f.context.getPackageName(); PhotoDeleteResult retry = f.delete(List.of(original, alias, effect));
        check(retry.deleted == 3 && retry.failed == 0 && f.context.resolver.rows.isEmpty(), "A corrected retry removes effects first and tolerates shared original publication URIs");
    }
    private static void effectOnlyKeepsTheOriginalAndUnselectedEffectsProtectIt(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.photo("DSC_3.JPG", 70), effect = f.effect(original, "b".repeat(64), "Film", 71);
        String originalUri = f.gallery.publish(original); f.gallery.publish(effect);
        PhotoDeleteResult blocked = f.delete(List.of(original));
        check(blocked.deleted == 0 && blocked.failed == 1 && original.file().isFile() && f.context.resolver.rows.size() == 2, "A request omitting an existing dependent effect cannot orphan it");
        PhotoDeleteResult effectOnly = f.delete(List.of(effect));
        check(effectOnly.deleted == 1 && effectOnly.failed == 0 && original.file().isFile() && f.context.resolver.rows.size() == 1, "Effect-only deletion preserves the original in the app and in the system album");
        check(f.context.resolver.rows.values().iterator().next().uri.equals(originalUri), "The original retains its existing system-album URI");
        check(f.delete(List.of(original)).deleted == 1, "An original becomes removable after its effects are gone");
    }
    private static void failedStackedEffectProtectsItsImmediateInput(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.photo("DSC_4.JPG", 80), parent = f.effect(original, "c".repeat(64), "First", 81), child = f.effect(parent, "d".repeat(64), "Second", 82);
        f.gallery.publish(original); f.gallery.publish(parent); String childUri = f.gallery.publish(child);
        var row = f.context.resolver.rows.values().stream().filter(item -> item.uri.equals(childUri)).findFirst().orElseThrow(); row.owner = "foreign.application";
        PhotoDeleteResult result = f.delete(List.of(original, parent, child));
        check(result.deleted == 0 && result.failed == 3 && original.file().isFile() && parent.file().isFile() && child.file().isFile(), "A failed old stacked effect preserves every immediate input and its original");
    }
    private static void wholeGroupDeletesBothPrivateEffectAliasesAndOnePublication(Path root, int sdk) throws Exception {
        Fixture f = new Fixture(root); Build.VERSION.SDK_INT = sdk;
        android.os.Environment.setPublicPicturesForTest(root.resolve("public/Pictures").toFile());
        DownloadedPhoto original = f.photo("DSC_5.JPG", 90), effect = f.effect(original, "e".repeat(64), "Shared", 91);
        Path internal = f.context.getFilesDir().toPath().resolve("pictures/camera-album"); Files.createDirectories(internal);
        Path copy = internal.resolve(effect.file().getName()); Files.copy(effect.file().toPath(), copy);
        DownloadedPhoto alias = new DownloadedPhoto(copy.toFile(), effect.filename());
        f.gallery.publish(original); String first = f.gallery.publish(effect), second = f.gallery.publish(alias);
        check(first.equals(second) && f.context.resolver.rows.size() == 2, "Two verified private effect aliases share one public version while the original remains separate");
        PhotoLiveProject project = f.store.create("Shared aliases"); f.store.record(project.id, "f".repeat(64), original);
        int before = f.context.resolver.deletes; PhotoDeleteResult result = f.delete(List.of(original, effect, alias));
        check(result.deleted == 3 && result.failed == 0 && !original.file().exists() && !effect.file().exists() && !alias.file().exists(), "Whole-group deletion removes the original and every selected private effect alias across both directories");
        check(f.context.resolver.rows.isEmpty() && f.context.resolver.deletes == before + 2, "The shared effect URI is deleted once, plus the one separate original URI");
        check(f.store.get(project.id).photoCount == 0, "The group deletion also removes its project reference");
    }
    private static final class Fixture {
        final Context context; final Path originals, catalog; final PhotoLiveGallery gallery; final PhotoLiveProjectStore store;
        Fixture(Path root) throws Exception {
            Build.VERSION.SDK_INT = 35; context = new Context(root.toFile()); originals = root.resolve("external/Pictures/camera-album"); catalog = context.getFilesDir().toPath().resolve("photo-live-projects");
            Files.createDirectories(originals); store = new PhotoLiveProjectStore(catalog.toFile(), originals.toFile()); gallery = new PhotoLiveGallery(context);
        }
        DownloadedPhoto photo(String name, int seed) throws IOException { File file = originals.resolve(name).toFile(); Files.write(file.toPath(), jpeg(seed)); return new DownloadedPhoto(file, name); }
        DownloadedPhoto effect(DownloadedPhoto source, String preset, String name, int seed) throws Exception {
            String sourceSha = PhotoOriginalStore.contentId(source.file());
            String key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((sourceSha + "\n" + preset + "\n" + PhotoPresetRenderer.MODEL_VERSION).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            DownloadedPhoto effect = photo("effect-" + key + "__Effect.JPG", seed);
            Path records = context.getFilesDir().toPath().resolve("photo-preset-results"); Files.createDirectories(records);
            try (var output = new java.io.DataOutputStream(Files.newOutputStream(records.resolve(key + ".record")))) { output.writeInt(0x50424531); output.writeLong(effect.byteSize()); output.writeUTF(PhotoOriginalStore.contentId(effect.file())); }
            new PhotoPresetSources(context.getFilesDir(), context.getExternalFilesDir("Pictures")).bind(effect, source, preset, name, () -> false);
            return effect;
        }
        PhotoDeleteResult delete(List<DownloadedPhoto> photos) throws IOException { try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.delete()) { return new PhotoAlbumDeletion(context).delete(photos); } }
    }
    private static byte[] jpeg(int seed) { return new byte[]{(byte) 255, (byte) 216, (byte) seed, 1, 2, 3, (byte) 255, (byte) 217}; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}

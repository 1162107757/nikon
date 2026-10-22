package cc.huevia.album;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Real private JPEG/ledger/name/choice bytes; no Android UI or MediaStore claims. */
public final class PhotoLibraryCatalogTest {
    private static int checks;
    private static final String A = "a".repeat(64), B = "b".repeat(64);
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("photo-library-catalog-");
        try {
            aliasesAndDurableChoices(root.resolve("groups"));
            brokenRelationsStaySeparate(root.resolve("orphan"));
            historicChainsAndOutputAliases(root.resolve("history"));
            cachedPresentationAndStrictReload(root.resolve("cache"));
            cancellationAndBoundaries(root.resolve("boundaries"));
            System.out.println("PASS: " + checks + " photo-library grouping/version checks");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static void aliasesAndDurableChoices(Path root) throws Exception {
        Fixture f = new Fixture(root); byte[] bytes = jpeg(640, 17);
        DownloadedPhoto original = f.photo("camera__DSC_1234.JPG", "DSC_1234.JPG", bytes).withPhotoDate(10000);
        DownloadedPhoto alias = f.photo("download__DSC_1234.JPG", "DSC_1234.JPG", bytes).withPhotoDate(10000);
        DownloadedPhoto third = f.photo("other__same.JPG", "Another.JPG", bytes).withPhotoDate(10001);
        DownloadedPhoto agfa = f.effect(original, A, "Agfa_Optima.JPG", "Agfa Optima", jpeg(700, 27));
        DownloadedPhoto sky = f.effect(original, B, "DSC_1234_Bluesky.JPG", "Bluesky", jpeg(740, 37));
        List<DownloadedPhoto> complete = List.of(original, alias, third, raw(agfa), raw(sky));
        var catalog = f.catalog(); var groups = catalog.list(complete, () -> false);
        check(groups.size() == 1, "Exact-byte original aliases and verified effects form one card");
        var group = groups.get(0);
        check(group.originals.size() == 3 && group.versions.size() == 3 && group.photos().size() == 5, "Original aliases are hidden as versions but retained in full deletion membership");
        check(group.photoDate == 10000 && group.id.equals("photo-" + sha(bytes)), "Original date and full byte identity drive grouping");
        check(group.versions.stream().anyMatch(v -> v.presetName.equals("Agfa Optima") && v.photo.filename().equals("Agfa_Optima.JPG")), "Preset and friendly export names survive list reconstruction");
        check(PhotoLibraryCatalog.groupFor(groups, alias) == group && PhotoLibraryCatalog.findByFile(groups, sky.file()) == group, "Aliases and effects resolve back to their one card");
        check(group.findByFile(alias.file()).original, "An original alias resolves to the original version");
        final var initial = group;
        immutable(() -> groups.clear()); immutable(() -> initial.versions.clear()); immutable(() -> initial.photos().clear()); immutable(() -> initial.originals.clear());
        catalog.selectDisplay(group, original);
        check(f.catalog().list(complete, () -> false).get(0).display.file().equals(original.file().getCanonicalFile()), "Remembering original persists across catalog/app reopen");
        catalog.selectDisplay(group.id, sky);
        check(f.catalog().list(complete, () -> false).get(0).display.file().equals(sky.file().getCanonicalFile()), "Selected effect survives reopen without changing original metadata");
        Files.delete(sky.file().toPath());
        group = f.catalog().list(List.of(original, alias, third, raw(agfa)), () -> false).get(0);
        check(group.display.file().equals(group.original.file()), "A deleted selected effect falls back to original instead of another effect");
        DownloadedPhoto unrelated = f.photo("different.JPG", "different.JPG", jpeg(640, 18));
        final var fixed = group;
        fails(() -> catalog.selectDisplay(fixed, unrelated), "Cannot persist a version outside this immutable group");
        fails(() -> catalog.selectDisplay("../../escape", original), "Arbitrary group IDs cannot create choice files");
        byte[] originalBefore = Files.readAllBytes(original.file().toPath()); long modified = original.file().lastModified();
        var reopened = f.catalog().list(List.of(original, raw(agfa)), () -> false).get(0);
        check(Arrays.equals(originalBefore, Files.readAllBytes(original.file().toPath())) && modified == original.file().lastModified(), "Grouping, friendly naming and selection never rewrite photo bytes or dates");
        check(reopened.versions.get(1).presetName.equals("Agfa Optima"), "Friendly version identity does not depend on still having a local preset installed");
    }
    private static void brokenRelationsStaySeparate(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.photo("old__Same.JPG", "Same.JPG", jpeg(400, 7));
        DownloadedPhoto effect = f.effect(original, A, "Same_Agfa.JPG", "Agfa", jpeg(500, 21));
        byte[] link = Files.readAllBytes(f.source(effect));
        Files.delete(original.file().toPath());
        DownloadedPhoto neighbour = f.photo("new__Same.JPG", "Same.JPG", jpeg(400, 8));
        var groups = f.catalog().list(List.of(neighbour, raw(effect)), () -> false);
        check(groups.size() == 2, "Missing original does not attach by same name/size/project");
        var orphan = PhotoLibraryCatalog.groupFor(groups, effect);
        check(orphan.orphan() && orphan.photos().size() == 1 && orphan.versions.size() == 1, "Orphan is independently visible and safely deletable");
        check(orphan.display.filename().equals("Same_Agfa.JPG") && orphan.versions.get(0).presetName.equals("Agfa") && orphan.versions.get(0).presetSha.isEmpty(), "Orphan retains presentation names but no unverified preset/source identity");
        check(Arrays.equals(link, Files.readAllBytes(f.source(effect))), "A broken link is never silently repaired during listing");
        Files.write(original.file().toPath(), jpeg(400, 7));
        byte[] corrupt = link.clone(); corrupt[corrupt.length - 1] ^= 1; Files.write(f.source(effect), corrupt);
        groups = f.catalog().list(List.of(original, raw(effect)), () -> false);
        check(groups.size() == 2 && PhotoLibraryCatalog.groupFor(groups, effect).orphan(), "Corrupt durable evidence cannot merge otherwise plausible images");
        Files.write(f.source(effect), link); byte[] changed = Files.readAllBytes(effect.file().toPath()); changed[4] ^= 1; Files.write(effect.file().toPath(), changed);
        check(PhotoLibraryCatalog.groupFor(f.catalog().list(List.of(original, effect), () -> false), effect).orphan(), "Changed completed output is shown alone rather than trusted as a version");
        DownloadedPhoto ordinary = f.photo("effect-almost.JPG", "effect-almost.JPG", jpeg(80, 13));
        check(!f.catalog().list(List.of(ordinary), () -> false).get(0).orphan(), "Ordinary non-content-addressed filenames are not classified as preset effects");
    }
    private static void historicChainsAndOutputAliases(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.photo("one.JPG", "one.JPG", jpeg(300, 5));
        DownloadedPhoto first = f.effect(original, A, "one_A.JPG", "A", jpeg(330, 19));
        DownloadedPhoto second = f.effect(first, B, "one_A_B.JPG", "B historic", jpeg(360, 27));
        Path internal = f.files.resolve("pictures/camera-album"); Files.createDirectories(internal);
        Path copied = internal.resolve(first.file().getName()); Files.copy(first.file().toPath(), copied);
        DownloadedPhoto outputAlias = new DownloadedPhoto(copied.toFile(), "Effect.JPG");
        var groups = f.catalog().list(List.of(original, raw(first), raw(second), outputAlias), () -> false);
        check(groups.size() == 1 && groups.get(0).versions.size() == 3, "Strict historic chain resolves to one root without presenting copied output aliases twice");
        check(groups.get(0).photos().size() == 4 && groups.get(0).findByFile(outputAlias.file()) != null, "Whole-photo deletion and file lookup retain a hidden output alias");
        check(f.sources().find(second, List::of, () -> false).file().equals(first.file().getCanonicalFile()), "Catalog root grouping preserves immediate-input comparison evidence");
        Files.delete(f.source(second));
        byte[] before = Files.readAllBytes(f.record(second));
        check(PhotoLibraryCatalog.groupFor(f.catalog().list(List.of(original, first, second), () -> false), second).orphan(), "Listing does not run expensive legacy source migration or guess a chain");
        check(!Files.exists(f.source(second)) && Arrays.equals(before, Files.readAllBytes(f.record(second))), "Read-only catalog leaves unresolved legacy ledgers untouched");
    }
    private static void cachedPresentationAndStrictReload(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.photo("large.JPG", "large.JPG", jpeg(1024 * 1024, 5));
        DownloadedPhoto effect = f.effect(original, A, "large_A.JPG", "A", jpeg(1024 * 1024, 21));
        var catalog = f.catalog(); AtomicInteger reads = new AtomicInteger();
        catalog.list(List.of(original, effect), () -> { reads.incrementAndGet(); return false; }); int cold = reads.get(); reads.set(0);
        catalog.list(List.of(original, effect), () -> { reads.incrementAndGet(); return false; });
        check(reads.get() + 20 < cold, "Repeated UI snapshots do not hash all unchanged full-size private JPEGs again");
        long modified = original.file().lastModified(); byte[] changed = Files.readAllBytes(original.file().toPath()); changed[20] ^= 1; Files.write(original.file().toPath(), changed); original.file().setLastModified(modified);
        var strict = catalog.list(List.of(original, effect), () -> false, true);
        check(strict.size() == 2 && PhotoLibraryCatalog.groupFor(strict, effect).orphan(), "Explicit force verification bypasses presentation memo and rejects same-size/mtime byte changes");
        catalog.invalidate(); check(catalog.list(List.of(original, effect), () -> false).size() == 2, "Invalidation also forces fresh original identity and relationship verification");
    }
    private static void cancellationAndBoundaries(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.photo("ok.JPG", "ok.JPG", jpeg(80, 7));
        var catalog = f.catalog();
        try { catalog.list(List.of(original), () -> true); throw new AssertionError("cancel ignored"); } catch (InterruptedIOException expected) { checks++; }
        Path outside = root.resolve("outside.JPG"); Files.write(outside, jpeg(80, 7));
        check(catalog.list(List.of(new DownloadedPhoto(outside.toFile(), "outside.JPG")), () -> false).isEmpty(), "Outside private paths are omitted even if their bytes equal a local photo");
        check(catalog.list(List.of(original, original), () -> false).get(0).photos().size() == 1, "Repeated caller rows never duplicate deletion membership");
        Thread.currentThread().interrupt();
        try { catalog.list(List.of(original), () -> false); throw new AssertionError("interrupt ignored"); } catch (InterruptedIOException expected) { checks++; }
        finally { Thread.interrupted(); }
    }
    private static final class Fixture {
        final Path files, pictures, originals, records;
        Fixture(Path root) throws Exception {
            files = root.resolve("files"); pictures = root.resolve("external/Pictures"); originals = pictures.resolve("camera-album"); records = files.resolve("photo-preset-results");
            Files.createDirectories(originals); Files.createDirectories(records);
        }
        PhotoLibraryCatalog catalog() throws IOException { return new PhotoLibraryCatalog(files.toFile(), pictures.toFile()); }
        PhotoPresetSources sources() throws IOException { return new PhotoPresetSources(files.toFile(), pictures.toFile()); }
        DownloadedPhoto photo(String path, String name, byte[] bytes) throws Exception {
            Path target = originals.resolve(path); Files.write(target, bytes); return new DownloadedPhoto(target.toFile(), name, "project", "Shoot");
        }
        DownloadedPhoto effect(DownloadedPhoto source, String preset, String friendly, String name, byte[] bytes) throws Exception {
            String key = PhotoPresetBatch.key(sha(Files.readAllBytes(source.file().toPath())), preset);
            DownloadedPhoto effect = photo("effect-" + key + "__Effect.JPG", friendly, bytes);
            try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(records.resolve(key + ".record")))) { data.writeInt(0x50424531); data.writeLong(bytes.length); data.writeUTF(sha(bytes)); }
            sources().bind(effect, source, preset, name, () -> false); return effect;
        }
        Path source(DownloadedPhoto photo) { return records.resolve(photo.file().getName().substring(7, 71) + ".source"); }
        Path record(DownloadedPhoto photo) { return records.resolve(photo.file().getName().substring(7, 71) + ".record"); }
    }
    private static DownloadedPhoto raw(DownloadedPhoto photo) { return new DownloadedPhoto(photo.file(), "Effect.JPG", photo.projectId(), photo.projectName(), photo.photoDate()); }
    private interface Action { void run() throws Exception; }
    private static void fails(Action action, String message) throws Exception { try { action.run(); throw new AssertionError(message); } catch (IOException expected) { checks++; } }
    private static void immutable(Action action) throws Exception { try { action.run(); throw new AssertionError("mutable snapshot"); } catch (UnsupportedOperationException expected) { checks++; } }
    private static byte[] jpeg(int count, int seed) { byte[] data = new byte[Math.max(4, count)]; for (int n = 2; n < data.length - 2; n++) data[n] = (byte) (seed + n); data[0] = (byte) 255; data[1] = (byte) 216; data[data.length - 2] = (byte) 255; data[data.length - 1] = (byte) 217; return data; }
    private static String sha(byte[] bytes) throws Exception { StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02x", b & 255)); return out.toString(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}

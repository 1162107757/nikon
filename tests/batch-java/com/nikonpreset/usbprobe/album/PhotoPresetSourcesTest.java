package com.nikonpreset.usbprobe.album;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Real link/legacy-ledger bytes and private-file boundaries; no Android codec claims. */
public final class PhotoPresetSourcesTest {
    private static int checks;
    private static final String PRESET = "a".repeat(64), OTHER_PRESET = "b".repeat(64);
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("photo-preset-sources-");
        try {
            durableExactInputAndAliases(root.resolve("durable"));
            separateModelsRetainDurableOriginals(root.resolve("models"));
            interruptedNp3CommitRecoversExactOriginal(root.resolve("np3-interrupted"));
            directDerivedInputAndLegacyMigration(root.resolve("legacy"));
            corruptionAndPrivateBoundaries(root.resolve("invalid"));
            strictOutputAliasesAcrossDirectories(root.resolve("aliases"));
            cancelledLookupDoesNotWaitForOtherHashes(root.resolve("concurrent"));
            cancellationAndBoundedCandidates(root.resolve("bounds"));
            processingWalksHistoricChainsButComparisonStaysImmediate(root.resolve("processing-chain"));
            processingRejectsMissingOrDamagedEvidence(root.resolve("processing-invalid"));
            boundedAndCancelledProcessingWalkDoesNotWrite(root.resolve("processing-bounds"));
            System.out.println("PASS: " + checks + " photo-preset source-link checks");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static void separateModelsRetainDurableOriginals(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.photo("original.JPG", jpeg(32, 17));
        String model = "np3-phone-approximation-v1:full-jpeg95-srgb-v1";
        DownloadedPhoto effect = f.effect(original, PRESET, jpeg(40, 83), model);
        String sourceSha = sha(Files.readAllBytes(original.file().toPath()));
        check(!PhotoPresetBatch.key(sourceSha, PRESET).equals(PhotoPresetBatch.key(sourceSha, PRESET, model)), "NP3-style models have a separate output identity from legacy NCP");
        fails(() -> f.store().bind(effect, original, PRESET), "Legacy bind cannot label another model's completed output as NCP");
        check(!Files.exists(f.link(effect)), "A mismatched model never creates source evidence");
        f.store().bind(effect, original, PRESET, "NP3 recipe", model, () -> false);
        PhotoPresetSources reopened = f.store();
        check(reopened.find(effect, () -> { throw new AssertionError("new model must not migrate"); }, () -> false).file().equals(original.file().getCanonicalFile()), "A separate model persists its exact original across restart without legacy scanning");
        check(reopened.processingOriginalFor(effect, List::of, () -> false).file().equals(original.file().getCanonicalFile()), "Processing a separate-model effect resolves the verified original");
        check(reopened.metadata(effect).presetName.equals("NP3 recipe"), "Separate-model source links preserve the preset display metadata");
        byte[] link = Files.readAllBytes(f.link(effect));
        fails(() -> reopened.bind(effect, original, PRESET, "wrong", model + "-changed", () -> false), "A changed model cannot rebind an existing output");
        check(Arrays.equals(link, Files.readAllBytes(f.link(effect))), "A model mismatch leaves existing evidence intact");
        Files.delete(f.link(effect));
        check(reopened.find(effect, () -> List.of(PRESET), () -> false) == null && !Files.exists(f.link(effect)), "Legacy migration never guesses a separate-model original from adjacent files");
        try { reopened.bind(effect, original, PRESET, "NP3 recipe", model, () -> true); throw new AssertionError("cancel ignored"); }
        catch (InterruptedIOException expected) { checks++; }
        check(!Files.exists(f.link(effect)), "Cancelled separate-model binding does not create an association");
        reopened.bind(effect, original, PRESET, "NP3 recipe", model, () -> false);
        check(reopened.relation(effect, () -> false).original.file().equals(original.file().getCanonicalFile()), "Explicit verified input restores the separate-model relation used by photo grouping");
    }
    private static void interruptedNp3CommitRecoversExactOriginal(Path root) throws Exception {
        Fixture f = new Fixture(root);
        DownloadedPhoto original = f.photo("np3-original.JPG", jpeg(42, 18));
        DownloadedPhoto effect = f.effect(original, PRESET, jpeg(64, 87), PhotoPresetRenderer.NP3_MODEL_VERSION);
        byte[] sourceBytes = Files.readAllBytes(original.file().toPath()), outputBytes = Files.readAllBytes(effect.file().toPath());
        check(!Files.exists(f.link(effect)), "Fixture interrupts NP3 commit after output and ledger, before source link");
        check(f.store().find(effect, () -> List.of(OTHER_PRESET), () -> false) == null, "NP3 recovery requires the exact committed preset SHA");
        try { f.store().find(effect, () -> List.of(PRESET), () -> true); throw new AssertionError("cancel ignored"); }
        catch (InterruptedIOException expected) { checks++; }
        check(!Files.exists(f.link(effect)), "Cancelled NP3 recovery does not publish a source link");
        DownloadedPhoto recovered = f.store().find(effect, () -> List.of(PRESET), () -> false);
        check(recovered != null && recovered.file().equals(original.file().getCanonicalFile()), "Interrupted NP3 output recovers only the exact source/preset/current-model identity");
        check(Files.isRegularFile(f.link(effect)), "Recovered NP3 source evidence is durable");
        check(f.store().processingOriginalFor(effect, () -> { throw new AssertionError("must use durable link"); }, () -> false).file().equals(original.file().getCanonicalFile()), "Recovered NP3 effects support original comparison and changing preset after restart");
        check(Arrays.equals(sourceBytes, Files.readAllBytes(original.file().toPath())) && Arrays.equals(outputBytes, Files.readAllBytes(effect.file().toPath())), "Recovery never changes original or completed NP3 pixels");
    }
    private static void durableExactInputAndAliases(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.photo("camera__Original.JPG", jpeg(12, 37));
        original = new DownloadedPhoto(original.file(), "Original.JPG", "project-id", "Portraits");
        DownloadedPhoto effect = f.effect(original, PRESET, jpeg(24, 89));
        byte[] before = Files.readAllBytes(original.file().toPath()), output = Files.readAllBytes(effect.file().toPath());
        long time = original.file().lastModified();
        f.store().bind(effect, original, PRESET);
        byte[] link = Files.readAllBytes(f.link(effect));
        PhotoPresetSources reopened = f.store();
        DownloadedPhoto found = reopened.find(effect, () -> { throw new AssertionError("durable lookup must not read presets/scan"); }, () -> false);
        check(found.file().equals(original.file().getCanonicalFile()) && found.filename().equals("Original.JPG"), "Restart resolves the precise direct source and display name");
        check(found.projectId().equals("project-id") && found.projectName().equals("Portraits"), "Direct source fields survive restart");
        check(Arrays.equals(before, Files.readAllBytes(original.file().toPath())) && time == original.file().lastModified(), "Association never changes source bytes or modification time");
        check(Arrays.equals(output, Files.readAllBytes(effect.file().toPath())), "Association never rewrites the completed effect");
        check(reopened.find(original, () -> { throw new AssertionError("normal original must not scan"); }, () -> false) == null, "Ordinary originals never acquire a comparison from neighbouring records");
        DownloadedPhoto alias = f.photo("same-content.JPG", before);
        reopened.bind(effect, alias, PRESET);
        check(Arrays.equals(link, Files.readAllBytes(f.link(effect))), "A valid first byte-identical source remains fixed and is not rewritten");
        Files.delete(original.file().toPath()); reopened.bind(effect, alias, PRESET);
        check(reopened.find(effect, List::of, () -> false).file().equals(alias.file().getCanonicalFile()), "Explicit identical-byte cache input repairs a missing first path");
        byte[] changed = before.clone(); changed[2] ^= 1; Files.write(alias.file().toPath(), changed);
        fails(() -> reopened.find(effect, () -> List.of(PRESET), () -> false), "A changed source does not fall back to a nearby photo");
        check(Arrays.equals(output, Files.readAllBytes(effect.file().toPath())), "Failed comparison leaves output intact");
    }
    private static void directDerivedInputAndLegacyMigration(Path root) throws Exception {
        Fixture f = new Fixture(root);
        DownloadedPhoto original = f.photo("capture__Camera.JPG", jpeg(16, 21));
        DownloadedPhoto first = f.effect(original, PRESET, jpeg(19, 51));
        DownloadedPhoto second = f.effect(first, OTHER_PRESET, jpeg(21, 72));
        f.store().bind(first, original, PRESET); f.store().bind(second, first, OTHER_PRESET);
        check(f.store().find(second, List::of, () -> false).file().equals(first.file().getCanonicalFile()), "Applying a preset to an effect compares its immediate input, without flattening the chain");
        Files.delete(f.link(second));
        check(f.store().find(second, () -> List.of(PRESET), () -> false) == null && !Files.exists(f.link(second)), "A legacy output cannot migrate using an unrelated preset");
        DownloadedPhoto migrated = f.store().find(second, () -> List.of(PRESET, OTHER_PRESET), () -> false);
        check(migrated.file().equals(first.file().getCanonicalFile()) && Files.isRegularFile(f.link(second)), "Legacy effect-source chains migrate only by exact content/preset/model key");
        check(f.store().find(second, () -> { throw new AssertionError("migration not durable"); }, () -> false).file().equals(first.file().getCanonicalFile()), "Migrated association survives another store instance");
        Files.delete(f.link(first));
        DownloadedPhoto alias = f.photo("unrelated-name.JPG", Files.readAllBytes(original.file().toPath()));
        Files.delete(original.file().toPath());
        check(f.store().find(first, () -> List.of(PRESET), () -> false).file().equals(alias.file().getCanonicalFile()), "Legacy migration allows an exact-byte alias without relying on its name");
        DownloadedPhoto unlinked = f.effect(f.photo("gone.JPG", jpeg(23, 42)), OTHER_PRESET, jpeg(25, 90));
        Files.delete(f.originals.resolve("gone.JPG"));
        check(f.store().find(unlinked, () -> List.of(PRESET, OTHER_PRESET), () -> false) == null && !Files.exists(f.link(unlinked)), "No matching source yields no guessed or persisted association");
    }
    private static void corruptionAndPrivateBoundaries(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto source = f.photo("source.JPG", jpeg(20, 17));
        DownloadedPhoto effect = f.effect(source, PRESET, jpeg(30, 81)); f.store().bind(effect, source, PRESET);
        byte[] association = Files.readAllBytes(f.link(effect)), corrupt = association.clone(); corrupt[corrupt.length - 1] ^= 1;
        Files.write(f.link(effect), corrupt);
        fails(() -> f.store().find(effect, () -> List.of(PRESET), () -> false), "A corrupt source sidecar fails rather than being lazily overwritten");
        fails(() -> f.store().bind(effect, source, PRESET), "A cache hit preserves and reports the corrupt sidecar");
        check(Arrays.equals(corrupt, Files.readAllBytes(f.link(effect))), "Corrupt durable evidence remains untouched");
        Files.write(f.link(effect), association);
        byte[] output = Files.readAllBytes(effect.file().toPath()), changed = output.clone(); changed[2] ^= 1; Files.write(effect.file().toPath(), changed);
        fails(() -> f.store().find(effect, List::of, () -> false), "Changed output cannot use an otherwise valid source sidecar");
        Files.write(effect.file().toPath(), output); Files.delete(f.link(effect));
        byte[] ledger = Files.readAllBytes(f.ledger(effect)); Files.write(f.ledger(effect), new byte[]{0, 1, 2});
        fails(() -> f.store().find(effect, () -> List.of(PRESET), () -> false), "Legacy migration first validates the old output completion record");
        check(!Files.exists(f.link(effect)), "Bad legacy ledger creates no sidecar"); Files.write(f.ledger(effect), ledger);
        Path outside = root.resolve("outside.JPG"); Files.write(outside, jpeg(20, 17));
        DownloadedPhoto foreign = new DownloadedPhoto(outside.toFile(), "outside.JPG");
        fails(() -> f.store().bind(effect, foreign, PRESET), "Source paths outside app camera-album are rejected");
        fails(() -> f.store().find(foreign, List::of, () -> false), "Outside targets cannot consult private source records");
        fails(() -> f.store().bind(effect, f.photo("different.JPG", jpeg(20, 19)), PRESET), "Same filename/size with different content is never a valid source");
        f.store().bind(effect, source, PRESET);
        Path backup = Path.of(f.link(effect) + ".bak"); Files.copy(f.link(effect), backup); Files.write(f.link(effect), new byte[]{1});
        check(f.store().find(effect, List::of, () -> false).file().equals(source.file().getCanonicalFile()), "Interrupted AtomicFile write recovers its previous source association");
        DownloadedPhoto lookalike = f.photo("effect-" + "f".repeat(64) + "__Effect.JPG", jpeg(17, 25));
        check(f.store().find(lookalike, () -> List.of(PRESET), () -> false) == null, "An effect-looking JPEG without a completed ledger is not misclassified");
    }
    private static void cancellationAndBoundedCandidates(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto source = f.photo("source.JPG", jpeg(256 * 1024, 12));
        DownloadedPhoto effect = f.effect(source, PRESET, jpeg(256 * 1024, 42));
        AtomicInteger chunks = new AtomicInteger();
        try { f.store().find(effect, () -> List.of(PRESET), () -> chunks.incrementAndGet() >= 6); throw new AssertionError("cancel ignored"); }
        catch (InterruptedIOException expected) { checks++; }
        check(chunks.get() == 6 && !Files.exists(f.link(effect)), "Cancellation interrupts a SHA chunk and does not commit a partial link");
        f.store().bind(effect, source, PRESET); chunks.set(0);
        try { f.store().find(effect, List::of, () -> chunks.incrementAndGet() >= 6); throw new AssertionError("cancel ignored"); }
        catch (InterruptedIOException expected) { checks++; }
        check(chunks.get() == 6, "Existing sidecar verification also honors cancellation within a file hash");
        Thread.currentThread().interrupt();
        try { f.store().find(effect, List::of, () -> false); throw new AssertionError("interrupt ignored"); }
        catch (InterruptedIOException expected) { checks++; }
        finally { Thread.interrupted(); }
        Files.delete(f.link(effect));
        check(source.file().setLastModified(1), "Can establish an old source for the migration-bound test");
        for (int index = 0; index < 2050; index++) f.photo("recent-" + index + ".JPG", jpeg(4, index));
        check(f.store().find(effect, () -> List.of(PRESET), () -> false) == null && !Files.exists(f.link(effect)), "Migration examines a bounded recent candidate set and never invents an older match");
        check(source.file().setLastModified(System.currentTimeMillis() + 10000), "Can promote the exact source to the bounded candidate set");
        check(f.store().find(effect, () -> List.of(PRESET), () -> false).file().equals(source.file().getCanonicalFile()), "Exact source migrates when available in the recent bounded set");
    }
    private static void strictOutputAliasesAcrossDirectories(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto source = f.photo("source.JPG", jpeg(20, 13));
        DownloadedPhoto effect = f.effect(source, PRESET, jpeg(28, 67)); f.store().bind(effect, source, PRESET);
        Path internal = f.files.resolve("pictures/camera-album"); Files.createDirectories(internal);
        Path copy = internal.resolve(effect.file().getName()); Files.copy(effect.file().toPath(), copy);
        DownloadedPhoto alias = new DownloadedPhoto(copy.toFile(), "Effect.JPG");
        check(f.store().find(alias, List::of, () -> false).file().equals(source.file().getCanonicalFile()), "Strict key and complete-byte equivalent output can resolve from the other supported directory");
        f.store().bind(alias, source, PRESET);
        Files.delete(effect.file().toPath());
        check(f.store().find(alias, List::of, () -> false).file().equals(source.file().getCanonicalFile()), "An exact output alias survives deletion of the first output path");
        byte[] changed = Files.readAllBytes(copy); changed[2] ^= 1; Files.write(copy, changed);
        fails(() -> f.store().find(alias, List::of, () -> false), "A same-key filename in another directory with different bytes is rejected");
    }
    private static void cancelledLookupDoesNotWaitForOtherHashes(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto source = f.photo("source.JPG", jpeg(65536 * 4, 12));
        DownloadedPhoto effect = f.effect(source, PRESET, jpeg(65536 * 4, 29)); f.store().bind(effect, source, PRESET);
        CountDownLatch holdsLock = new CountDownLatch(1), release = new CountDownLatch(1), stopped = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(); AtomicBoolean cancel = new AtomicBoolean(); AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = new Thread(() -> {
            try { f.store().find(effect, List::of, () -> {
                if (calls.incrementAndGet() == 4) {
                    holdsLock.countDown(); try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                    catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
                }
                return false;
            }); } catch (Throwable unexpected) { failure.set(unexpected); }
        });
        first.start(); if (!holdsLock.await(5, TimeUnit.SECONDS)) throw new AssertionError("lock not acquired");
        Thread second = new Thread(() -> {
            try { f.store().find(effect, List::of, cancel::get); failure.set(new AssertionError("waiting lookup ignored cancellation")); }
            catch (InterruptedIOException expected) { }
            catch (Throwable unexpected) { failure.set(unexpected); }
            finally { stopped.countDown(); }
        });
        try {
            second.start(); Thread.sleep(150); cancel.set(true);
            check(stopped.await(1, TimeUnit.SECONDS) && release.getCount() == 1, "Cancelled comparison stops while another large-file lookup still owns the metadata lock");
        } finally { release.countDown(); first.join(5000); second.join(5000); }
        if (failure.get() != null) throw new AssertionError(failure.get());
        check(!first.isAlive() && !second.isAlive(), "Cancellation releases waiters without leaking the shared metadata lock");
    }
    private static void processingWalksHistoricChainsButComparisonStaysImmediate(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.photo("camera__Original.JPG", jpeg(28, 13));
        original = new DownloadedPhoto(original.file(), "Original.JPG", "original-project", "Shoot");
        DownloadedPhoto first = f.effect(original, PRESET, jpeg(32, 37)); f.store().bind(first, original, PRESET);
        DownloadedPhoto second = f.effect(first, OTHER_PRESET, jpeg(36, 59)); f.store().bind(second, first, OTHER_PRESET);
        DownloadedPhoto third = f.effect(second, PRESET, jpeg(40, 83)); f.store().bind(third, second, PRESET);
        byte[] input = Files.readAllBytes(original.file().toPath()), output = Files.readAllBytes(third.file().toPath());
        long originalTime = original.file().lastModified(), effectTime = third.file().lastModified();
        DownloadedPhoto rootInput = f.store().processingOriginalFor(third, () -> { throw new AssertionError("durable processing walk must not scan"); }, () -> false);
        check(rootInput.file().equals(original.file().getCanonicalFile()) && rootInput.filename().equals("Original.JPG"), "Processing follows three historic applications back to the precise first original");
        check(rootInput.projectId().equals("original-project") && rootInput.projectName().equals("Shoot"), "Root processing retains original metadata rather than the selected effect's display metadata");
        check(f.store().find(third, List::of, () -> false).file().equals(second.file().getCanonicalFile()), "Comparison of a historic third effect still returns its immediate second-effect input");
        check(f.store().processingOriginalFor(original, () -> { throw new AssertionError("ordinary original must not scan"); }, () -> false).file().equals(original.file()), "A plain original is returned directly without effect migration");
        Files.delete(f.link(third));
        check(f.store().processingOriginalFor(third, () -> List.of(PRESET, OTHER_PRESET), () -> false).file().equals(original.file().getCanonicalFile()), "A legacy top link can migrate by exact bytes before root normalization");
        check(f.store().find(third, List::of, () -> false).file().equals(second.file().getCanonicalFile()), "Legacy root normalization persists the immediate link instead of flattening comparison history");
        check(Arrays.equals(input, Files.readAllBytes(original.file().toPath())) && originalTime == original.file().lastModified(), "Historic normalization never changes original bytes or mtime");
        check(Arrays.equals(output, Files.readAllBytes(third.file().toPath())) && effectTime == third.file().lastModified(), "Historic normalization never rewrites existing effects");
    }
    private static void processingRejectsMissingOrDamagedEvidence(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.photo("original.JPG", jpeg(32, 19));
        DownloadedPhoto effect = f.effect(original, PRESET, jpeg(38, 61)); f.store().bind(effect, original, PRESET);
        byte[] originalBytes = Files.readAllBytes(original.file().toPath()), effectBytes = Files.readAllBytes(effect.file().toPath());
        byte[] link = Files.readAllBytes(f.link(effect)), corrupt = link.clone(); corrupt[corrupt.length - 1] ^= 1; Files.write(f.link(effect), corrupt);
        fails(() -> f.store().processingOriginalFor(effect, () -> List.of(PRESET), () -> false), "Processing rejects a corrupt source association rather than treating the effect as an original");
        check(Arrays.equals(corrupt, Files.readAllBytes(f.link(effect))), "Processing never silently repairs a damaged association");
        Files.write(f.link(effect), link); byte[] changed = effectBytes.clone(); changed[2] ^= 1; Files.write(effect.file().toPath(), changed);
        fails(() -> f.store().processingOriginalFor(effect, List::of, () -> false), "Processing validates a selected effect's completed bytes before following its link");
        Files.write(effect.file().toPath(), effectBytes); Files.delete(original.file().toPath());
        fails(() -> f.store().processingOriginalFor(effect, List::of, () -> false), "A vanished root fails instead of falling back to the selected effect");
        Files.write(original.file().toPath(), originalBytes); changed = originalBytes.clone(); changed[2] ^= 1; Files.write(original.file().toPath(), changed);
        fails(() -> f.store().processingOriginalFor(effect, List::of, () -> false), "A changed root fails even when its name and byte length still match");
        Files.write(original.file().toPath(), originalBytes);
        DownloadedPhoto lookalike = f.photo("effect-" + "f".repeat(64) + "__Effect.JPG", jpeg(24, 47));
        fails(() -> f.store().processingOriginalFor(lookalike, () -> List.of(PRESET), () -> false), "A strict effect-looking filename without a completion ledger cannot bypass normalization");
        check(f.store().find(lookalike, () -> List.of(PRESET), () -> false) == null, "Strict processing rejection does not change the existing optional comparison lookup contract");
        DownloadedPhoto absent = f.photo("gone.JPG", jpeg(26, 71)); DownloadedPhoto orphan = f.effect(absent, PRESET, jpeg(34, 97));
        Files.delete(absent.file().toPath());
        fails(() -> f.store().processingOriginalFor(orphan, () -> List.of(PRESET), () -> false), "A valid legacy output with no exact surviving input is rejected without guessing");
        check(!Files.exists(f.link(orphan)) && Arrays.equals(effectBytes, Files.readAllBytes(effect.file().toPath())), "Unresolvable legacy inputs create no misleading link or altered prior output");
    }
    private static void boundedAndCancelledProcessingWalkDoesNotWrite(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.photo("original.JPG", jpeg(32, 17)), last = original;
        for (int index = 0; index < 64; index++) {
            DownloadedPhoto next = f.effect(last, index % 2 == 0 ? PRESET : OTHER_PRESET, jpeg(36 + index, index + 23));
            f.store().bind(next, last, index % 2 == 0 ? PRESET : OTHER_PRESET); last = next;
        }
        final DownloadedPhoto deepest = last; AtomicInteger calls = new AtomicInteger();
        check(f.store().processingOriginalFor(deepest, List::of, () -> { calls.incrementAndGet(); return false; }).file().equals(original.file().getCanonicalFile()), "A supported 64-link history resolves to its original without truncating to an intermediate effect");
        java.util.Map<String, String> recordsBefore = fingerprints(f.records), imagesBefore = fingerprints(f.originals);
        AtomicInteger cancelledChecks = new AtomicInteger(); int stop = Math.max(2, calls.get() / 2);
        try { f.store().processingOriginalFor(deepest, List::of, () -> cancelledChecks.incrementAndGet() >= stop); throw new AssertionError("processing cancellation ignored"); }
        catch (InterruptedIOException expected) { checks++; }
        check(cancelledChecks.get() == stop && recordsBefore.equals(fingerprints(f.records)) && imagesBefore.equals(fingerprints(f.originals)), "Cancellation during a long verified history walk writes neither metadata nor JPEG bytes");
        DownloadedPhoto tooDeep = f.effect(deepest, PRESET, jpeg(104, 117)); f.store().bind(tooDeep, deepest, PRESET);
        recordsBefore = fingerprints(f.records);
        fails(() -> f.store().processingOriginalFor(tooDeep, List::of, () -> false), "A 65-link history reports the safety bound rather than processing an intermediate effect");
        check(recordsBefore.equals(fingerprints(f.records)) && f.store().find(tooDeep, List::of, () -> false).file().equals(deepest.file().getCanonicalFile()), "A depth rejection leaves the historic direct comparison and its records intact");
    }
    private static java.util.Map<String, String> fingerprints(Path directory) throws Exception {
        java.util.Map<String, String> result = new java.util.TreeMap<>();
        try (var paths = Files.list(directory)) { for (Path path : paths.toList()) if (Files.isRegularFile(path)) result.put(path.getFileName().toString(), sha(Files.readAllBytes(path))); }
        return result;
    }
    private static final class Fixture {
        final Path files, pictures, originals, records;
        Fixture(Path root) throws Exception { files = root.resolve("files"); pictures = root.resolve("external/Pictures"); originals = pictures.resolve("camera-album"); records = files.resolve("photo-preset-results"); Files.createDirectories(originals); Files.createDirectories(records); }
        PhotoPresetSources store() throws IOException { return new PhotoPresetSources(files.toFile(), pictures.toFile()); }
        DownloadedPhoto photo(String name, byte[] bytes) throws IOException { Path path = originals.resolve(name); Files.write(path, bytes); return new DownloadedPhoto(path.toFile(), name); }
        DownloadedPhoto effect(DownloadedPhoto source, String preset, byte[] bytes) throws Exception {
            return effect(source, preset, bytes, PhotoPresetRenderer.MODEL_VERSION);
        }
        DownloadedPhoto effect(DownloadedPhoto source, String preset, byte[] bytes, String model) throws Exception {
            String key = PhotoPresetBatch.key(sha(Files.readAllBytes(source.file().toPath())), preset, model);
            DownloadedPhoto effect = photo("effect-" + key + "__Effect.JPG", bytes);
            try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(records.resolve(key + ".record")))) { data.writeInt(0x50424531); data.writeLong(bytes.length); data.writeUTF(sha(bytes)); }
            return effect;
        }
        Path link(DownloadedPhoto effect) { return records.resolve(effect.file().getName().substring(7, 71) + ".source"); }
        Path ledger(DownloadedPhoto effect) { return records.resolve(effect.file().getName().substring(7, 71) + ".record"); }
    }
    private interface Action { void run() throws Exception; }
    private static void fails(Action action, String message) throws Exception { try { action.run(); throw new AssertionError(message); } catch (IOException expected) { checks++; } }
    private static byte[] jpeg(int count, int seed) { byte[] data = new byte[Math.max(4, count)]; for (int n = 2; n < data.length - 2; n++) data[n] = (byte) (seed + n); data[0] = (byte) 255; data[1] = (byte) 216; data[data.length - 2] = (byte) 255; data[data.length - 1] = (byte) 217; return data; }
    private static String sha(byte[] bytes) throws Exception { StringBuilder out = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format("%02x", b & 255)); return out.toString(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}

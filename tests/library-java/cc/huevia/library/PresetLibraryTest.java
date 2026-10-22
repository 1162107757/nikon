package cc.huevia.library;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.net.ssl.HttpsURLConnection;

/** Real library/files/strict JSON/HTTPS policy; injected HTTP responses perform no network calls. */
public final class PresetLibraryTest {
    private static int checks;
    private static File tempRoot;
    public static void main(String[] args) throws Exception {
        tempRoot = new File(args[0]); tempRoot.mkdirs();
        multipleFilesAndPersistence(); legacyMigration(); strictParsing(); catalogConfigurationAndCache(); defaultCatalogConfiguration(); managedCatalogMigration();
        downloadedFiles(); categoriesAndPersistence(); categoryCatalogAndDownload();
        removedPresetsStayRemoved(); removalPreservesUnrelatedImages(); removalCommitAndCleanupFailures();
        removalAcrossLibraryInstances();
        np3ContainersAndPersistence(); np3CatalogAndDownload();
        readOnlyOnlinePreviews(); rejectedOnlinePreviews(); boundedRecipeCache();
        redirectAndNetworkBoundaries(); cancelledAndFailedDownloads(); previewRejectsHtml();
        if (args.length > 1) verifyDownloadedArchive(new File(args[1]));
        System.out.println("Preset library: " + checks + " checks passed (no network or camera I/O).");
    }

    private static void multipleFilesAndPersistence() throws Exception {
        File dir = fresh(); PresetLibrary lib = new PresetLibrary(dir);
        eq(0, lib.list().size(), "empty library");
        byte[] first = ncp("HOST A", 1), second = ncp("HOST B", 2);
        PresetLibrary.AddResult a = lib.importFile("same.ncp", first);
        PresetLibrary.AddResult b = lib.importFile("same.ncp", second);
        yes(a.added && b.added, "same name distinct content stored"); eq(2, lib.list().size(), "two records");
        eq(hash(first), a.record.id, "content address independently hashed"); eq("HOST A", a.record.displayName, "NCP registration name");
        eq("phone", a.record.source, "phone provenance");
        PresetLibrary.AddResult duplicate = lib.importFile("renamed.ncp", first);
        yes(!duplicate.added, "duplicate reused"); eq("same.ncp", duplicate.record.originalFileName, "duplicate retains original metadata");
        eq(2, lib.list().size(), "no duplicate record");
        lib.attachPreview(a.record.id, "content://photos/real%20sample/1");
        PresetLibrary restored = new PresetLibrary(dir);
        eq(2, restored.list().size(), "multiple records survive reopen");
        eq("content://photos/real%20sample/1", restored.findBySha256(a.record.id).previewUri, "sample URI survives reopen");
        yes(Arrays.equals(first, restored.readNcp(a.record.id)), "exact original NCP bytes preserved");
        byte[] copy = restored.readNcp(a.record.id); copy[0] = 0;
        yes(Arrays.equals(first, restored.readNcp(a.record.id)), "caller's byte array cannot mutate stored data");
        fail(() -> restored.attachPreview(a.record.id, "https://host/sample.jpg"), "local sample only");
        fail(() -> restored.readNcp("../../selected.ncp"), "ID path traversal rejected");
        fail(() -> restored.importFile("bad.txt", first), "extension required");
        fail(() -> restored.importFile("bad.ncp", new byte[50]), "invalid NCP rejected");
        fail(() -> restored.importFile("huge.ncp", new byte[1024 * 1024 + 1]), "local NCP size bounded");
        eq(2, restored.list().size(), "rejected imports not committed");
        Files.write(new File(dir, "preset-library/" + a.record.id + ".ncp").toPath(), new byte[]{1});
        fail(() -> restored.readNcp(a.record.id), "corruption caught before camera use");
        PresetLibrary afterCorruption = new PresetLibrary(dir);
        eq(1, afterCorruption.list().size(), "corrupt item not offered as importable");
        yes(!afterCorruption.diagnostics().isEmpty(), "corruption is diagnosable");
        File atomicDir = fresh(); PresetLibrary atomic = new PresetLibrary(atomicDir);
        new File(atomicDir, "preset-library/" + hash(first) + ".json").mkdirs();
        fail(() -> atomic.importFile("x.ncp", first), "metadata commit failure surfaced");
        eq(0, atomic.list().size(), "commit failure leaves no in-memory record");
        eq(0, new PresetLibrary(atomicDir).list().size(), "orphan payload not offered on reopen");
    }

    private static void legacyMigration() throws Exception {
        byte[] original = ncp("LEGACY", 4); File dir = fresh();
        Files.write(new File(dir, "selected.ncp").toPath(), original);
        String old = "{\"fileName\":\"old.ncp\",\"sha256\":\"" + hash(original) + "\",\"byteLength\":52}";
        Files.write(new File(dir, "preset.json").toPath(), old.getBytes(StandardCharsets.UTF_8));
        PresetLibrary library = new PresetLibrary(dir);
        eq(1, library.list().size(), "legacy imported"); eq("legacy", library.list().get(0).source, "legacy source");
        eq("old.ncp", library.list().get(0).originalFileName, "legacy original name");
        eq(1, new PresetLibrary(dir).list().size(), "migration idempotent");
        yes(Arrays.equals(original, Files.readAllBytes(new File(dir, "selected.ncp").toPath())), "legacy bytes untouched");
        eq(old, Files.readString(new File(dir, "preset.json").toPath()), "legacy metadata untouched");
        File mismatch = fresh(); Files.write(new File(mismatch, "selected.ncp").toPath(), original);
        Files.writeString(new File(mismatch, "preset.json").toPath(), old.replace(hash(original), "0".repeat(64)));
        PresetLibrary refused = new PresetLibrary(mismatch);
        eq(0, refused.list().size(), "stale legacy hash not trusted"); yes(!refused.diagnostics().isEmpty(), "migration failure visible");
        File noMeta = fresh(); Files.write(new File(noMeta, "selected.ncp").toPath(), original);
        eq(1, new PresetLibrary(noMeta).list().size(), "original without metadata recovered");
    }

    private static void removedPresetsStayRemoved() throws Exception {
        byte[] original = ncp("REMOVE LEGACY", 4), other = ncp("KEEP", 5);
        File dir = fresh(); File legacy = new File(dir, "selected.ncp"); Files.write(legacy.toPath(), original);
        File legacyMetadata = new File(dir, "preset.json");
        Files.writeString(legacyMetadata.toPath(), "{\"fileName\":\"legacy.ncp\",\"sha256\":\"" + hash(original) + "\"}");
        byte[] oldMetadata = Files.readAllBytes(legacyMetadata.toPath());
        PresetLibrary library = new PresetLibrary(dir), stale = new PresetLibrary(dir);
        PresetRecord kept = library.importFile("keep.ncp", other).record;
        PresetRecord removed = library.findBySha256(hash(original));
        PresetLibrary.RemoveResult result = library.remove(removed.id.toUpperCase(java.util.Locale.ROOT));
        eq(removed.id, result.record.id, "removal identifies normalized target");
        eq(0, result.cleanupWarnings.size(), "ordinary removal completes cleanup");
        boolean immutable = false; try { result.cleanupWarnings.add("alter"); } catch (UnsupportedOperationException expected) { immutable = true; }
        yes(immutable, "cleanup warnings immutable");
        eq(1, library.list().size(), "only chosen preset removed from memory");
        yes(library.findBySha256(removed.id) == null, "removed preset no longer found");
        yes(!new File(dir, "preset-library/" + removed.id + ".json").exists(), "target metadata cleaned");
        yes(!new File(dir, "preset-library/" + removed.id + ".ncp").exists(), "target payload cleaned");
        yes(new File(dir, "preset-library/removed-" + removed.id + ".json").isFile(), "durable removal marker retained");
        fail(() -> library.readPreset(removed.id), "removed item cannot be read");
        fail(() -> stale.readPreset(removed.id), "previously opened library cannot read removed bytes");
        fail(() -> stale.attachPreview(removed.id, "content://photos/one"), "stale preview update cannot resurrect metadata");
        fail(() -> stale.setCategory(removed.id, "风景"), "stale category update cannot resurrect metadata");
        eq(1, new PresetLibrary(dir).list().size(), "removed legacy preset does not resurrect on restart");
        yes(Arrays.equals(original, Files.readAllBytes(legacy.toPath())), "legacy source preserved");
        yes(Arrays.equals(oldMetadata, Files.readAllBytes(legacyMetadata.toPath())), "legacy metadata preserved");
        yes(Arrays.equals(other, library.readPreset(kept.id)), "unrelated recipe bytes remain identical");
        library.refresh(); eq(1, library.list().size(), "refresh respects removal marker");
        PresetLibrary.AddResult restored = library.importFile("explicit.ncp", original);
        yes(restored.added, "explicit import can restore removed content");
        yes(!new File(dir, "preset-library/removed-" + removed.id + ".json").exists(), "explicit successful add clears marker");
        yes(Arrays.equals(original, library.readPreset(restored.record.id)), "restored original bytes intact");
        eq(2, new PresetLibrary(dir).list().size(), "explicit restoration persists");

        byte[] np3 = np3("REMOVE NP3", 0, false); File np3Dir = fresh(); PresetLibrary np3Library = new PresetLibrary(np3Dir);
        PresetRecord np3Record = np3Library.importFile("remove.np3", np3).record; np3Library.remove(np3Record.id);
        yes(!new File(np3Dir, "preset-library/" + np3Record.id + ".np3").exists(), "NP3 source payload removed by real extension");
        eq(0, new PresetLibrary(np3Dir).list().size(), "NP3 removal persists");
        yes(!np3Library.importFile("again.np3", np3).record.fileFormat.isEmpty(), "NP3 explicit restoration supported");
    }

    private static void removalPreservesUnrelatedImages() throws Exception {
        File dir = fresh(); PresetLibrary library = new PresetLibrary(dir);
        PresetRecord first = library.importFile("first.ncp", ncp("SAMPLE FIRST", 1)).record;
        PresetRecord second = library.importFile("second.ncp", ncp("SAMPLE SECOND", 2)).record;
        File samples = new File(dir, "preset-samples"); samples.mkdirs();
        File owned = new File(samples, first.id + "-101.image"), backup = new File(samples, first.id + "-102.image.bak"), staged = new File(samples, first.id + "-103.image.new");
        File shared = new File(samples, first.id + "-104.image");
        File unrelated = new File(samples, second.id + "-201.image"), note = new File(samples, first.id + "-note.image");
        File external = new File(dir, "user-picked-external.jpg"), photograph = new File(dir, "photo-library/saved/original.JPG"); photograph.getParentFile().mkdirs();
        File raw = new File(dir, "photo-library/saved/original.NEF");
        File systemCopy = new File(dir, "system-album/Huevia/effect.JPG"); systemCopy.getParentFile().mkdirs();
        List<File> files = List.of(owned, backup, staged, shared, unrelated, note, external, photograph, raw, systemCopy);
        for (File file : files) Files.writeString(file.toPath(), "keep exact pixels: " + file.getName());
        library.attachPreview(first.id, owned.toURI().toString()); library.attachPreview(second.id, shared.toURI().toString());
        PresetLibrary.RemoveResult result = library.remove(first.id); eq(0, result.cleanupWarnings.size(), "private sample cleanup succeeds");
        for (File file : List.of(owned, backup, staged)) yes(!file.exists(), "proven owned sample/stage removed: " + file.getName());
        for (File file : List.of(shared, unrelated, note, external, photograph, raw, systemCopy))
            eq("keep exact pixels: " + file.getName(), Files.readString(file.toPath()), "shared/external/photo content retained: " + file.getName());
        new PresetLibrary(dir); yes(shared.isFile(), "startup cleanup respects another preset's shared sample");
        library.remove(second.id); yes(!shared.exists(), "shared sample cleaned after final reference removed");
        yes(!unrelated.exists(), "second record's own sample cleaned");

        File cachedDir = fresh(); PresetLibrary cached = new PresetLibrary(cachedDir);
        PresetRecord a = cached.importFile("a.ncp", ncp("CACHE A", 1)).record;
        File cache = new File(cachedDir, "online-presets/previews/" + "a".repeat(64) + ".image"); cache.getParentFile().mkdirs(); Files.writeString(cache.toPath(), "cached sample");
        cached.attachPreview(a.id, cache.toURI().toString()); cached.remove(a.id); yes(!cache.exists(), "unshared managed preview cache removed");
        PresetRecord b = cached.importFile("b.ncp", ncp("EXTERNAL B", 2)).record;
        File outside = new File(cachedDir, "user-pictures/" + "b".repeat(64) + ".image"); outside.getParentFile().mkdirs(); Files.writeString(outside.toPath(), "user source");
        cached.attachPreview(b.id, outside.toURI().toString()); cached.remove(b.id); eq("user source", Files.readString(outside.toPath()), "file URI outside managed preview roots retained");
        PresetRecord c = cached.importFile("c.ncp", ncp("CONTENT C", 3)).record;
        cached.attachPreview(c.id, "content://media/external/images/media/14"); cached.remove(c.id);
        eq("user source", Files.readString(outside.toPath()), "content URI removal performs no external source deletion");
    }

    private static void removalCommitAndCleanupFailures() throws Exception {
        File dir = fresh(); PresetLibrary library = new PresetLibrary(dir); byte[] bytes = ncp("COMMIT FAILURE", 1);
        PresetRecord record = library.importFile("commit.ncp", bytes).record;
        File metadata = new File(dir, "preset-library/" + record.id + ".json"); byte[] before = Files.readAllBytes(metadata.toPath());
        for (String invalid : new String[]{"../selected.ncp", "", "0".repeat(63), "g".repeat(64), "f".repeat(64)})
            fail(() -> library.remove(invalid), "invalid or absent removal cannot report success");
        fail(() -> library.remove(null), "null removal rejected"); eq(1, library.list().size(), "invalid requests keep committed record");
        File marker = new File(dir, "preset-library/removed-" + record.id + ".json"); marker.mkdirs(); File blocker = new File(marker, "blocker"); Files.writeString(blocker.toPath(), "occupied");
        fail(() -> library.remove(record.id), "failed removal marker commit not reported as success");
        yes(Arrays.equals(before, Files.readAllBytes(metadata.toPath())), "failed marker commit leaves metadata untouched");
        yes(Arrays.equals(bytes, Files.readAllBytes(new File(dir, "preset-library/" + record.id + ".ncp").toPath())), "failed marker commit leaves original payload untouched");
        Files.delete(blocker.toPath()); Files.delete(marker.toPath());
        eq(1, new PresetLibrary(dir).list().size(), "record still usable after failed marker destination removed");
        File payload = new File(dir, "preset-samples/" + record.id + "-101.image"); payload.mkdirs();
        File payloadBlocker = new File(payload, "blocker"); Files.writeString(payloadBlocker.toPath(), "simulate busy sample cleanup");
        PresetLibrary.RemoveResult partial = library.remove(record.id);
        yes(!partial.cleanupWarnings.isEmpty(), "partial disk cleanup is surfaced in removal result");
        eq(0, library.list().size(), "committed removal remains removed despite cleanup failure");
        PresetLibrary retry = new PresetLibrary(dir); eq(0, retry.list().size(), "startup does not resurrect partial removal");
        yes(!retry.diagnostics().isEmpty(), "startup reports still blocked cleanup");
        Files.delete(payloadBlocker.toPath()); new PresetLibrary(dir);
        yes(!payload.exists(), "next startup retries and finishes leftover cleanup"); yes(marker.isFile(), "cleanup retains tombstone to prevent legacy resurrection");
        Files.writeString(marker.toPath(), "broken marker"); PresetLibrary corrupt = new PresetLibrary(dir);
        eq(0, corrupt.list().size(), "unreadable marker does not resurrect removed item");
        yes(!corrupt.diagnostics().isEmpty(), "unreadable cleanup marker is diagnosable");

        File wrongDir = fresh(); PresetLibrary mismatched = new PresetLibrary(wrongDir);
        PresetRecord retained = mismatched.importFile("retained.ncp", ncp("MISMATCH KEEP", 3)).record;
        String absent = "c".repeat(64); File wrongMarker = new File(wrongDir, "preset-library/removed-" + absent + ".json");
        Map<String, Object> markerData = new java.util.LinkedHashMap<>(); markerData.put("schemaVersion", 1); markerData.put("record", retained.toMap());
        Files.writeString(wrongMarker.toPath(), LibraryJson.stringify(markerData));
        PresetLibrary guarded = new PresetLibrary(wrongDir);
        eq(1, guarded.list().size(), "marker identity mismatch cannot delete another preset");
        yes(Arrays.equals(ncp("MISMATCH KEEP", 3), guarded.readPreset(retained.id)), "mismatched marker leaves unrelated bytes intact");
        yes(!guarded.diagnostics().isEmpty(), "marker identity mismatch reported");
    }

    private static void removalAcrossLibraryInstances() throws Exception {
        File dir = fresh(); PresetLibrary first = new PresetLibrary(dir);
        PresetRecord target = first.importFile("target.ncp", ncp("STALE TARGET", 1)).record;
        PresetRecord other = first.importFile("other.ncp", ncp("STALE OTHER", 2)).record;
        File shared = new File(dir, "preset-samples/" + target.id + "-999.image"); shared.getParentFile().mkdirs(); Files.writeString(shared.toPath(), "shared pixels");
        PresetLibrary writer = new PresetLibrary(dir); writer.attachPreview(other.id, shared.toURI().toString());
        first.remove(target.id);
        eq("shared pixels", Files.readString(shared.toPath()), "removal observes another Activity's latest sample reference");
        eq(shared.toURI().toString(), new PresetLibrary(dir).findBySha256(other.id).previewUri, "other Activity's committed sample metadata preserved");
    }

    private static void strictParsing() throws Exception {
        fail(() -> LibraryJson.parse("{\"version\":1,\"version\":1}"), "duplicate JSON keys rejected");
        fail(() -> LibraryJson.parse("{\"x\":1} trailing"), "trailing JSON rejected");
        fail(() -> LibraryJson.parse("[01]"), "leading zero rejected");
        fail(() -> LibraryJson.parse("[\"\\uD800\"]"), "unpaired surrogate rejected");
        fail(() -> LibraryJson.parse("[1,]"), "trailing comma rejected");
        fail(() -> LibraryFiles.utf8(new byte[]{(byte) 0xc0, (byte) 0xaf}), "malformed UTF8 rejected");
        String valid = manifest(ncp("JSON", 1));
        eq(1, parse(valid).size(), "version1 valid item");
        fail(() -> parse(valid.replace("\"version\":1", "\"version\":2")), "future schema rejected");
        fail(() -> parse(valid.replace("\"byteLength\":52", "\"byteLength\":52.5")), "fractional length rejected");
        fail(() -> parse(valid.replace("\"byteLength\":52", "\"byteLength\":1048577")), "oversized NCP declaration rejected");
        fail(() -> parse(valid.replace("https://host/preset.ncp", "http://host/preset.ncp")), "HTTP file rejected");
        fail(() -> parse(valid.replace("https://host/sample.jpg", "http://host/sample.jpg")), "HTTP sample rejected");
        fail(() -> parse(valid.replace("\"compatibleModels\":[\"Z6II\"]", "\"compatibleModels\":\"Z6II\"")), "wrong models type rejected");
        String item = valid.substring(valid.indexOf("[{") + 1, valid.length() - 2);
        fail(() -> parse("{\"version\":1,\"presets\":[" + item + "," + item + "]}"), "duplicate catalog IDs rejected");
        eq(0, parse("{\"version\":1,\"presets\":[]}").size(), "empty catalog valid without fake content");
    }

    private static void catalogConfigurationAndCache() throws Exception {
        File dir = fresh(); PresetLibrary library = new PresetLibrary(dir); Mock server = new Mock();
        OnlinePresetRepository repo = new OnlinePresetRepository(dir, library, server.client());
        eq("not_configured", repo.fetchCatalog().status, "unconfigured explicit status"); eq(0, server.calls.size(), "no invented endpoint requests");
        fail(() -> repo.setEndpoint("http://host/catalog.json"), "HTTPS endpoint required");
        fail(() -> repo.setEndpoint("https://u:p@host/catalog.json"), "URL credentials rejected");
        fail(() -> repo.setEndpoint("https://host/catalog.json#fragment"), "fragment rejected");
        repo.setEndpoint("https://host/catalog.json");
        eq("https://host/catalog.json", new OnlinePresetRepository(dir, library, server.client()).getEndpoint(), "endpoint persisted");
        server.add(200, manifest(ncp("REMOTE", 1)).getBytes(StandardCharsets.UTF_8));
        OnlinePresetRepository.CatalogResult result = repo.fetchCatalog();
        eq("ok", result.status, "catalog fetch"); eq(1, result.presets.size(), "one actual supplied item"); yes(!result.fromCache, "online result marked live");
        eq("cached", repo.cachedCatalog().status, "explicit cache available");
        yes(repo.cachedCatalog().fromCache, "cache label explicit");
        eq(result.presets.get(0).sha256, repo.cachedCatalog().presets.get(0).sha256, "cache retains digest");
        repo.setEndpoint(""); eq("not_configured", repo.fetchCatalog().status, "clear endpoint state");
        eq(1, repo.cachedCatalog().presets.size(), "clearing address keeps explicit cache");
        eq(0, library.list().size(), "catalog browsing does not commit NCP files");
        Mock bad = new Mock(); OnlinePresetRepository failed = new OnlinePresetRepository(fresh(), new PresetLibrary(fresh()), bad.client());
        failed.setEndpoint("https://host/catalog.json"); bad.add(200, "<html>error</html>".getBytes(StandardCharsets.UTF_8));
        eq("error", failed.fetchCatalog().status, "HTML manifest rejected");
        eq("no_cache", failed.cachedCatalog().status, "invalid directory not cached");
    }

    private static void defaultCatalogConfiguration() throws Exception {
        String shipped = "https://nikon-d8girdjsr252fd90e-1252602548.ap-shanghai.app.tcloudbase.com/preset-library/catalog.json";
        String custom = "https://custom.example.test/my-catalog.json";
        File dir = fresh(); PresetLibrary library = new PresetLibrary(dir); Mock server = new Mock();
        File config = new File(dir, "online-presets/config.json");
        OnlinePresetRepository first = new OnlinePresetRepository(dir, library, server.client());
        eq("", first.getEndpoint(), "constructor remains unconfigured without explicit initialization");
        yes(!config.exists(), "constructor does not save a default");
        yes(first.initializeDefaultEndpoint(shipped), "first configuration initializes shipped catalog");
        eq(shipped, first.getEndpoint(), "initialized endpoint available immediately");
        eq(0, server.calls.size(), "default initialization performs no network request");
        byte[] initialConfig = Files.readAllBytes(config.toPath());
        OnlinePresetRepository reopened = new OnlinePresetRepository(dir, library, server.client());
        eq(shipped, reopened.getEndpoint(), "default survives reopen");
        yes(!reopened.initializeDefaultEndpoint(custom), "later default cannot replace saved configuration");
        yes(Arrays.equals(initialConfig, Files.readAllBytes(config.toPath())), "reinitialization leaves saved configuration unchanged");
        server.add(200, "{\"version\":1,\"presets\":[]}".getBytes(StandardCharsets.UTF_8));
        eq("ok", reopened.fetchCatalog().status, "initialized catalog loads through normal validated path");
        eq(shipped, server.urls.get(0), "catalog request uses exact shipped HTTPS address");

        reopened.setEndpoint(custom);
        byte[] customConfig = Files.readAllBytes(config.toPath());
        OnlinePresetRepository customized = new OnlinePresetRepository(dir, library, server.client());
        yes(!customized.initializeDefaultEndpoint(shipped), "existing custom catalog is preserved");
        eq(custom, customized.getEndpoint(), "custom address remains active");
        yes(Arrays.equals(customConfig, Files.readAllBytes(config.toPath())), "custom settings are not rewritten");

        reopened.setEndpoint("");
        byte[] disabledConfig = Files.readAllBytes(config.toPath());
        OnlinePresetRepository disabled = new OnlinePresetRepository(dir, library, server.client());
        yes(!disabled.initializeDefaultEndpoint(shipped), "explicitly disabled catalog remains disabled on reopen");
        eq("", disabled.getEndpoint(), "disabled endpoint stays blank");
        eq("not_configured", disabled.fetchCatalog().status, "disabled catalog performs no fetch");
        eq(1, server.calls.size(), "disabled or initialization paths add no network calls");
        yes(Arrays.equals(disabledConfig, Files.readAllBytes(config.toPath())), "disabled settings are not rewritten");

        File sharedDir = fresh(); PresetLibrary sharedLibrary = new PresetLibrary(sharedDir);
        OnlinePresetRepository stale = new OnlinePresetRepository(sharedDir, sharedLibrary, new Mock().client());
        OnlinePresetRepository writer = new OnlinePresetRepository(sharedDir, sharedLibrary, new Mock().client());
        writer.setEndpoint(custom);
        yes(!stale.initializeDefaultEndpoint(shipped), "another Activity's newly saved custom choice takes precedence");
        eq(custom, stale.getEndpoint(), "initialization observes latest saved choice");
        writer.setEndpoint("");
        yes(!stale.initializeDefaultEndpoint(shipped), "another Activity's disabled choice takes precedence");
        eq("", stale.getEndpoint(), "latest disabled choice is observed");

        String[] damaged = { "{", "{\"schemaVersion\":2,\"endpoint\":\"\"}",
                "{\"schemaVersion\":1,\"endpoint\":\"http://host/catalog.json\"}",
                "{\"schemaVersion\":1,\"endpoint\":false}" };
        for (String contents : damaged) {
            File brokenDir = fresh(); PresetLibrary brokenLibrary = new PresetLibrary(brokenDir);
            File badConfig = new File(brokenDir, "online-presets/config.json"); badConfig.getParentFile().mkdirs();
            byte[] bytes = contents.getBytes(StandardCharsets.UTF_8); Files.write(badConfig.toPath(), bytes);
            Mock brokenServer = new Mock();
            OnlinePresetRepository broken = new OnlinePresetRepository(brokenDir, brokenLibrary, brokenServer.client());
            yes(!broken.initializeDefaultEndpoint(shipped), "unreadable saved choice never falls back to default");
            yes(Arrays.equals(bytes, Files.readAllBytes(badConfig.toPath())), "unreadable configuration bytes preserved");
            yes(broken.fetchCatalog().message.contains("配置无法读取"), "damaged configuration remains diagnosable");
            eq(0, brokenServer.calls.size(), "damaged settings cause no unsolicited request");
        }

        File directoryDir = fresh(); PresetLibrary directoryLibrary = new PresetLibrary(directoryDir);
        File directoryConfig = new File(directoryDir, "online-presets/config.json"); directoryConfig.mkdirs();
        OnlinePresetRepository directory = new OnlinePresetRepository(directoryDir, directoryLibrary, new Mock().client());
        yes(!directory.initializeDefaultEndpoint(shipped), "non-file configuration is not overwritten");
        yes(directoryConfig.isDirectory(), "invalid configuration directory retained");
        yes(directory.fetchCatalog().message.contains("配置无法读取"), "non-file configuration remains diagnosable");

        File failureDir = fresh(); PresetLibrary failureLibrary = new PresetLibrary(failureDir);
        OnlinePresetRepository failure = new OnlinePresetRepository(failureDir, failureLibrary, new Mock().client());
        File cacheRoot = new File(failureDir, "online-presets"); Files.delete(cacheRoot.toPath());
        Files.writeString(cacheRoot.toPath(), "blocked");
        fail(() -> failure.initializeDefaultEndpoint(shipped), "default commit failure is surfaced");
        eq("", failure.getEndpoint(), "failed default commit does not change active address");
        eq("blocked", Files.readString(cacheRoot.toPath()), "failed commit preserves existing filesystem contents");
        Files.delete(cacheRoot.toPath()); Files.createDirectory(cacheRoot.toPath());
        yes(failure.initializeDefaultEndpoint(shipped), "failed commit can be retried after filesystem recovery");
        eq(shipped, new OnlinePresetRepository(failureDir, failureLibrary).getEndpoint(), "only successful default commit is persisted");
        File invalidDir = fresh(); OnlinePresetRepository invalid = new OnlinePresetRepository(invalidDir, new PresetLibrary(invalidDir));
        fail(() -> invalid.initializeDefaultEndpoint("http://host/catalog.json"), "default must use HTTPS");
        fail(() -> invalid.initializeDefaultEndpoint(""), "missing default cannot silently disable catalog");
        yes(!new File(invalidDir, "online-presets/config.json").exists(), "invalid default creates no configuration");
    }

    private static void managedCatalogMigration() throws Exception {
        String managed = "https://nikon-d8girdjsr252fd90e-1252602548.ap-shanghai.app.tcloudbase.com/preset-library/catalog.json";
        String custom = "https://custom.example.test/catalog.json";
        File dir = fresh(); PresetLibrary library = new PresetLibrary(dir); Mock server = new Mock();
        byte[] original = ncp("OLD DOWNLOAD", 11), official = ncp("MANAGED", 12), local = ncp("LOCAL", 13);
        OnlinePresetRepository repo = new OnlinePresetRepository(dir, library, server.client());
        repo.setEndpoint(custom);
        server.add(200, manifest(original).getBytes(StandardCharsets.UTF_8));
        OnlinePreset old = repo.fetchCatalog().presets.get(0);
        server.add(200, original); PresetRecord downloaded = repo.download(old, null).record;
        PresetRecord imported = library.importFile("local.ncp", local).record;
        File config = new File(dir, "online-presets/config.json"), cache = new File(dir, "online-presets/catalog.json");
        byte[] legacyCache = Files.readAllBytes(cache.toPath());
        int requests = server.calls.size();
        repo.useManagedEndpoint(managed);
        eq(managed, repo.getEndpoint(), "custom configuration migrates to managed service");
        eq(requests, server.calls.size(), "migration itself makes no requests to either service");
        eq("no_cache", repo.cachedCatalog().status, "legacy catalog cannot appear as managed catalog");
        eq(0, repo.cachedCatalog().presets.size(), "legacy catalog entries are hidden after migration");
        yes(Arrays.equals(legacyCache, Files.readAllBytes(cache.toPath())), "cache isolation does not destroy saved legacy data");
        yes(Arrays.equals(original, library.readNcp(downloaded.id)), "downloaded preset bytes survive migration");
        yes(Arrays.equals(local, library.readNcp(imported.id)), "phone imported preset bytes survive migration");
        eq(2, new PresetLibrary(dir).list().size(), "all local presets survive reopen");
        fail(() -> repo.setEndpoint(custom), "managed instance cannot re-enable custom endpoint");
        fail(() -> repo.setEndpoint(""), "managed instance cannot be disabled by generic API");
        eq(managed, repo.getEndpoint(), "rejected custom changes preserve managed endpoint");
        eq(managed, new OnlinePresetRepository(dir, library, server.client()).getEndpoint(), "managed migration persists configuration");
        server.add(200, manifest(official).getBytes(StandardCharsets.UTF_8));
        OnlinePresetRepository.CatalogResult fetched = repo.fetchCatalog();
        eq("ok", fetched.status, "managed directory loads normally");
        eq(managed, server.urls.get(requests), "first post-migration request targets only managed service");
        eq(hash(official), fetched.presets.get(0).sha256, "managed directory contains actual managed response");
        eq(hash(official), repo.cachedCatalog().presets.get(0).sha256, "managed cache contains no legacy item");
        OnlinePresetRepository reopened = new OnlinePresetRepository(dir, library, server.client());
        reopened.useManagedEndpoint(managed);
        eq("cached", reopened.cachedCatalog().status, "managed cache available offline after reopening");
        eq(hash(official), reopened.cachedCatalog().presets.get(0).sha256, "matching cache remains isolated across reopen");
        int beforeOffline = server.calls.size();
        eq("error", reopened.fetchCatalog().status, "offline error does not fabricate live content");
        eq("cached", reopened.cachedCatalog().status, "explicit cache still available after offline fetch failure");
        eq(beforeOffline, server.calls.size(), "offline fetch never requests an alternate endpoint");

        for (String saved : new String[]{"", "{", "{\"schemaVersion\":2,\"endpoint\":\"http://legacy/\"}"}) {
            File legacyDir = fresh(); PresetLibrary legacyLibrary = new PresetLibrary(legacyDir); Mock legacyServer = new Mock();
            OnlinePresetRepository legacy = new OnlinePresetRepository(legacyDir, legacyLibrary, legacyServer.client());
            File legacyConfig = new File(legacyDir, "online-presets/config.json");
            if (saved.isEmpty()) legacy.setEndpoint("");
            else Files.writeString(legacyConfig.toPath(), saved);
            legacy = new OnlinePresetRepository(legacyDir, legacyLibrary, legacyServer.client());
            legacy.useManagedEndpoint(managed);
            eq(managed, legacy.getEndpoint(), "disabled or readable damaged settings migrate to managed service");
            eq(managed, new OnlinePresetRepository(legacyDir, legacyLibrary).getEndpoint(), "migration repairs persisted legacy configuration");
            eq(0, legacyServer.calls.size(), "legacy settings repair has no network side effects");
        }

        // The same instance used the old service before a filesystem fault. It must now fail closed.
        OnlinePresetRepository blocked = new OnlinePresetRepository(dir, library, server.client());
        blocked.setEndpoint(custom);
        byte[] previousConfig = Files.readAllBytes(config.toPath());
        Files.delete(config.toPath()); Files.createDirectory(config.toPath());
        fail(() -> blocked.useManagedEndpoint(managed), "non-file configuration migration fails explicitly");
        eq("", blocked.getEndpoint(), "non-file configuration cannot leave legacy endpoint in memory");
        eq("not_configured", blocked.fetchCatalog().status, "failed migration cannot fetch stale endpoint");
        yes(blocked.fetchCatalog().message.contains("重新加载"), "failed migration provides actionable retry message");
        eq("no_cache", blocked.cachedCatalog().status, "failed migration cannot expose even a previously matching cache");
        eq(0, blocked.cachedCatalog().presets.size(), "failed migration has no visible stale catalog");
        yes(config.isDirectory(), "non-file configuration is never removed or replaced");
        eq(beforeOffline, server.calls.size(), "failed migration performs zero network I/O");
        Files.delete(config.toPath()); Files.write(config.toPath(), previousConfig);
        blocked.useManagedEndpoint(managed);
        eq(managed, blocked.getEndpoint(), "migration can retry after non-file configuration repair");
        yes(Arrays.equals(original, library.readNcp(downloaded.id)), "failed and retried migration leaves downloads intact");

        File fullDir = fresh(); PresetLibrary fullLibrary = new PresetLibrary(fullDir); Mock fullServer = new Mock();
        OnlinePresetRepository full = new OnlinePresetRepository(fullDir, fullLibrary, fullServer.client());
        full.setEndpoint(custom);
        File fullConfig = new File(fullDir, "online-presets/config.json");
        byte[] oversized = new byte[8193]; Arrays.fill(oversized, (byte) 'x'); Files.write(fullConfig.toPath(), oversized);
        fail(() -> full.useManagedEndpoint(managed), "bounded configuration read failure surfaces");
        eq("", full.getEndpoint(), "read failure closes previous custom endpoint");
        eq("not_configured", full.fetchCatalog().status, "read failure never contacts previous service");
        yes(Arrays.equals(oversized, Files.readAllBytes(fullConfig.toPath())), "unreadable oversized configuration is preserved");
        eq(0, fullServer.calls.size(), "read failure has no network side effects");

        File diskDir = fresh(); PresetLibrary diskLibrary = new PresetLibrary(diskDir); Mock diskServer = new Mock();
        OnlinePresetRepository disk = new OnlinePresetRepository(diskDir, diskLibrary, diskServer.client());
        disk.setEndpoint(custom);
        File diskRoot = new File(diskDir, "online-presets");
        Files.delete(new File(diskRoot, "config.json").toPath()); Files.delete(diskRoot.toPath());
        Files.writeString(diskRoot.toPath(), "blocked");
        fail(() -> disk.useManagedEndpoint(managed), "managed configuration commit failure surfaces");
        eq("", disk.getEndpoint(), "failed commit closes former custom endpoint");
        eq("not_configured", disk.fetchCatalog().status, "failed commit prevents old-service fetch");
        eq("no_cache", disk.cachedCatalog().status, "failed commit prevents stale cache fallback");
        eq("blocked", Files.readString(diskRoot.toPath()), "failed commit preserves unrelated filesystem contents");
        eq(0, diskServer.calls.size(), "failed managed commit has no network side effects");
        Files.delete(diskRoot.toPath()); Files.createDirectory(diskRoot.toPath());
        disk.useManagedEndpoint(managed);
        eq(managed, new OnlinePresetRepository(diskDir, diskLibrary).getEndpoint(), "managed endpoint commits after filesystem recovery");

        File invalidDir = fresh(); OnlinePresetRepository invalid = new OnlinePresetRepository(invalidDir, new PresetLibrary(invalidDir));
        fail(() -> invalid.useManagedEndpoint(""), "managed endpoint cannot be empty");
        fail(() -> invalid.useManagedEndpoint("http://host/catalog.json"), "managed endpoint requires HTTPS");
        yes(!new File(invalidDir, "online-presets/config.json").exists(), "invalid managed address creates no configuration");
    }

    private static void downloadedFiles() throws Exception {
        byte[] original = ncp("REMOTE", 5); OnlinePreset preset = parse(manifest(original)).get(0);
        File dir = fresh(); PresetLibrary library = new PresetLibrary(dir); Mock server = new Mock();
        OnlinePresetRepository repo = new OnlinePresetRepository(dir, library, server.client());
        server.add(200, original); List<Long> progress = new ArrayList<>();
        PresetLibrary.AddResult added = repo.download(preset, (done, total) -> { eq(52L, total, "known download length"); progress.add(done); });
        yes(added.added, "verified download added"); eq("online", added.record.source, "online provenance");
        eq("Catalog name", added.record.displayName, "publisher display name stored");
        eq("Description", added.record.description, "description stored");
        yes(Arrays.equals(original, library.readNcp(added.record.id)), "download exact original bytes");
        eq(0L, progress.get(0), "progress starts zero"); eq(52L, progress.get(progress.size() - 1), "progress reaches exact bytes");
        int before = server.calls.size(); yes(!repo.download(preset, null).added, "repeated content reused");
        eq(before, server.calls.size(), "duplicate reuse performs no network");
        eq(1, new PresetLibrary(dir).list().size(), "download survives reopen");

        assertRejectedDownload(preset, ncp("BAD HASH", 5), 200, "digest mismatch");
        assertRejectedDownload(preset, Arrays.copyOf(original, 30), 200, "short download");
        assertRejectedDownload(preset, original, 404, "HTTP error");
        byte[] nonNcp = new byte[52]; OnlinePreset badNcp = parse(manifest(nonNcp)).get(0);
        assertRejectedDownload(badNcp, nonNcp, 200, "digest correct but invalid NCP");
    }

    private static void categoriesAndPersistence() throws Exception {
        eq("", PresetCategories.normalize(""), "empty category is uncategorized");
        eq("", PresetCategories.normalize(" \u00a0\u3000\ufeff "), "surrounding Unicode spaces are trimmed");
        eq("胶片", PresetCategories.normalize("\u00a0 胶片 \ufeff"), "Unicode category trim");
        eq("Café", PresetCategories.normalize(" Cafe\u0301 "), "category normalized to NFC");
        String supplementary = "\ud83d\udcf7".repeat(24);
        eq(supplementary, PresetCategories.normalize(supplementary), "24 supplementary code points accepted despite 48 UTF16 units");
        eq("人".repeat(24), PresetCategories.normalize("人".repeat(24)), "24 Chinese code points accepted");
        fail(() -> PresetCategories.normalize("人".repeat(25)), "25 Chinese code points rejected");
        fail(() -> PresetCategories.normalize(supplementary + "人"), "25 supplementary category rejected");
        for (String invalid : new String[]{"全部", " 未分类 ", "人\n像", "\t人像", "\u001c人像", "街拍\u007f", "夜\u0085景", "\ud800", "\udc00"})
            fail(() -> PresetCategories.normalize(invalid), "invalid category value rejected");
        eq("", PresetCategories.normalize(null), "null legacy category defaults to uncategorized");
        String trimSpaces = "\u0020\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff";
        eq("人像", PresetCategories.normalize(trimSpaces + "人像" + trimSpaces), "ECMAScript whitespace matches server trim");
        eq("\u200b人像\u200b", PresetCategories.normalize("\u200b人像\u200b"), "non-trim zero-width space retained consistently");
        eq("未分类", PresetCategories.label(""), "empty category display label");
        eq("人像", PresetCategories.label("人像"), "custom category label");

        byte[] original = ncp("CATEGORY", 31);
        File dir = fresh(); PresetLibrary library = new PresetLibrary(dir);
        PresetRecord initial = library.importFile("category.ncp", original).record;
        eq("", initial.category, "phone import starts uncategorized");
        File meta = new File(dir, "preset-library/" + initial.id + ".json");
        // Actual version 1 file without the new key, rather than a synthetic new record.
        Map<String, Object> oldMap = LibraryJson.object(LibraryJson.parse(Files.readString(meta.toPath())));
        oldMap.remove("category"); Files.writeString(meta.toPath(), LibraryJson.stringify(oldMap));
        library = new PresetLibrary(dir);
        eq("", library.findBySha256(initial.id).category, "existing metadata missing category is recovered");
        oldMap.put("category", null); Files.writeString(meta.toPath(), LibraryJson.stringify(oldMap));
        library = new PresetLibrary(dir);
        eq("", library.findBySha256(initial.id).category, "existing metadata with null category is recovered");
        PresetRecord categorized = library.setCategory(initial.id, " 胶片 ");
        eq("胶片", categorized.category, "category change normalized");
        eq(initial.id, categorized.id, "category does not change content ID");
        eq(initial.addedAt, categorized.addedAt, "category preserves added time");
        eq(initial.displayName, categorized.displayName, "category preserves registration name");
        eq(initial.originalFileName, categorized.originalFileName, "category preserves file name");
        eq(initial.byteLength, categorized.byteLength, "category preserves byte count");
        eq(initial.ncpVersion, categorized.ncpVersion, "category preserves NCP version");
        eq(initial.source, categorized.source, "category preserves source");
        library.attachPreview(initial.id, "content://photos/category/1");
        PresetLibrary reopened = new PresetLibrary(dir);
        PresetRecord restored = reopened.findBySha256(initial.id);
        eq("胶片", restored.category, "category survives preview attachment and reopen");
        eq("content://photos/category/1", restored.previewUri, "category save preserves preview association");
        yes(Arrays.equals(original, reopened.readNcp(initial.id)), "category save leaves NCP bytes exactly intact");
        eq(hash(original), hash(Files.readAllBytes(new File(dir, "preset-library/" + initial.id + ".ncp").toPath())),
                "independent file hash unchanged after category update");
        byte[] beforeInvalid = Files.readAllBytes(meta.toPath());
        fail(() -> reopened.setCategory(initial.id, "全部"), "reserved category rejected at worker API");
        yes(Arrays.equals(beforeInvalid, Files.readAllBytes(meta.toPath())), "invalid category never rewrites metadata");
        fail(() -> reopened.setCategory("0".repeat(64), "胶片"), "category requires existing preset");
        fail(() -> reopened.setCategory("../../other", "胶片"), "category API validates content ID");
        PresetLibrary.AddResult duplicate = reopened.importFile("another-name.ncp", original);
        yes(!duplicate.added, "categorized import still deduplicates by content");
        eq(restored.toMap(), duplicate.record.toMap(), "duplicate import preserves entire metadata including category");
        reopened.setCategory(initial.id, "");
        eq("", new PresetLibrary(dir).findBySha256(initial.id).category, "clearing a category persists");

        // A failed atomic rename must not publish an in-memory category that was not committed.
        byte[] beforeFailure = Files.readAllBytes(meta.toPath());
        Files.delete(meta.toPath()); Files.createDirectory(meta.toPath());
        fail(() -> reopened.setCategory(initial.id, "夜景"), "category metadata commit failure surfaced");
        eq("", reopened.findBySha256(initial.id).category, "failed category commit retains previous in-memory value");
        yes(Arrays.equals(original, reopened.readNcp(initial.id)), "failed category commit leaves payload untouched");
        Files.delete(meta.toPath()); Files.write(meta.toPath(), beforeFailure);
        eq("", new PresetLibrary(dir).findBySha256(initial.id).category, "prior committed category still opens after storage repair");
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.list(meta.getParentFile().toPath())) {
            yes(paths.noneMatch(p -> p.getFileName().toString().startsWith(".pending-")), "failed atomic metadata save leaves no stage file");
        }
    }

    private static void categoryCatalogAndDownload() throws Exception {
        byte[] original = ncp("ONLINE CATEGORY", 32);
        String oldManifest = manifest(original);
        eq("", parse(oldManifest).get(0).category, "old manifest without category remains supported");
        eq("", parse(oldManifest.replace("\"description\":", "\"category\":null,\"description\":")).get(0).category,
                "null catalog category remains compatible with old metadata");
        for (String invalid : new String[]{"42", "[]", "\"全部\"", "\"未分类\"", "\"人\\u0085像\""})
            fail(() -> parse(oldManifest.replace("\"description\":", "\"category\":" + invalid + ",\"description\":")),
                    "invalid online category rejected");
        String categorizedManifest = oldManifest.replace("\"description\":", "\"category\":\" 胶片 \",\"description\":");
        OnlinePreset online = parse(categorizedManifest).get(0);
        eq("胶片", online.category, "catalog category parsed and normalized");
        eq("胶片", OnlinePreset.fromMap(LibraryJson.object(LibraryJson.parse(LibraryJson.stringify(online.toMap())))).category,
                "clean catalog serialization retains category");
        File dir = fresh(); PresetLibrary library = new PresetLibrary(dir); Mock server = new Mock();
        OnlinePresetRepository repo = new OnlinePresetRepository(dir, library, server.client());
        repo.setEndpoint("https://host/catalog.json");
        server.add(200, categorizedManifest.getBytes(StandardCharsets.UTF_8));
        eq("胶片", repo.fetchCatalog().presets.get(0).category, "fetched category available");
        OnlinePresetRepository reopened = new OnlinePresetRepository(dir, library, server.client());
        eq("胶片", reopened.cachedCatalog().presets.get(0).category, "online category survives cache reopen");
        server.add(200, original);
        PresetRecord downloaded = reopened.download(online, null).record;
        eq("胶片", downloaded.category, "first online download copies publisher category");
        eq("胶片", new PresetLibrary(dir).findBySha256(downloaded.id).category, "download category persists locally");
        library.setCategory(downloaded.id, "我的收藏"); library.attachPreview(downloaded.id, "content://photos/custom/1");
        PresetRecord customized = library.findBySha256(downloaded.id);
        OnlinePreset republished = parse(categorizedManifest.replace(" 胶片 ", "人像")).get(0);
        int before = server.calls.size();
        PresetLibrary.AddResult duplicate = reopened.download(republished, null);
        yes(!duplicate.added, "republished same bytes reuse local copy");
        eq(before, server.calls.size(), "republished same bytes perform no network");
        eq(customized.toMap(), duplicate.record.toMap(), "same-hash download never overwrites user metadata");
        eq(customized.toMap(), library.addDownloaded(republished, original).record.toMap(),
                "download commit race also preserves category and full metadata");
        library.setCategory(downloaded.id, "");
        eq("", reopened.download(republished, null).record.category, "explicitly uncategorized local item is not silently recategorized");
        PresetLibrary localFirst = new PresetLibrary(fresh());
        PresetRecord local = localFirst.importFile("phone-original.ncp", original).record;
        PresetLibrary.AddResult localDuplicate = localFirst.addDownloaded(online, original);
        yes(!localDuplicate.added, "online download of phone import reuses content");
        eq(local.toMap(), localDuplicate.record.toMap(), "online download never replaces original phone metadata");
        yes(Arrays.equals(original, library.readNcp(downloaded.id)), "online category operations preserve NCP bytes");
    }

    private static void np3ContainersAndPersistence() throws Exception {
        for (int noteLength : new int[]{0, 16, 248}) for (boolean curve : new boolean[]{false, true}) {
            byte[] bytes = np3("NP3 SOURCE", noteLength, curve);
            PresetFile info = PresetFile.validate(bytes);
            eq("NP3", info.fileFormat, "NP3 identified by record layout rather than common magic");
            eq("0310", info.version, "NP3 version"); eq("NP3 SOURCE", info.registrationName, "NP3 internal name");
            fail(() -> cc.huevia.ncp.NcpFile.validate(bytes), "legacy NCP validator remains strict");
        }
        byte[] original = np3("REAL NP3 NAME", 16, true), base = np3("BASE", 0, false);
        byte[] version = original.clone(); version[13] = '2';
        fail(() -> PresetFile.validate(version), "unverified NP3 versions rejected");
        byte[] reordered = original.clone(); reordered[6] = 2;
        fail(() -> PresetFile.validate(reordered), "first NP3 version record required");
        byte[] duplicate = original.clone(); duplicate[18] = 1;
        fail(() -> PresetFile.validate(duplicate), "duplicate base record rejected");
        byte[] badLength = original.clone(); badLength[23] = 19;
        fail(() -> PresetFile.validate(badLength), "base name record has exact length");
        for (int length : new int[]{0, 3, 7, 10, 14, 22, base.length - 4, base.length - 1}) {
            byte[] truncated = Arrays.copyOf(base, length);
            fail(() -> PresetFile.validate(truncated), "NP3 truncated fields/payload/terminator rejected");
        }
        byte[] overflow = original.clone(); Arrays.fill(overflow, 20, 24, (byte) 0xff);
        fail(() -> PresetFile.validate(overflow), "unsigned record overflow rejected");
        fail(() -> PresetFile.validate(Arrays.copyOf(original, original.length + 1)), "NP3 trailing bytes rejected");
        byte[] unknown = Arrays.copyOf(original, original.length);
        unknown[base.length - 4] = 1;
        fail(() -> PresetFile.validate(unknown), "unknown optional record rejected");
        for (int length : new int[]{1, 15, 249}) {
            byte[] note = np3("NOTE", length, false);
            fail(() -> PresetFile.validate(note), "unverified note lengths rejected");
        }
        byte[] invalidUtf8 = np3("NOTE", 16, false);
        invalidUtf8[base.length + 4] = (byte) 0xc0; invalidUtf8[base.length + 5] = (byte) 0xaf;
        fail(() -> PresetFile.validate(invalidUtf8), "malformed UTF8 creator note rejected");
        byte[] unicodeNote = np3("NOTE", 16, false), noteText = "云创 📷".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(noteText, 0, unicodeNote, base.length + 4, noteText.length);
        eq("NP3", PresetFile.validate(unicodeNote).fileFormat, "valid UTF8 creator note preserved");
        byte[] curveLength = np3("CURVE", 0, true); curveLength[base.length + 3] = 0;
        fail(() -> PresetFile.validate(curveLength), "curve record length must be 578");
        byte[] secondNote = withExtraRecord(np3("NOTE", 16, false), 0x10100, new byte[16]);
        fail(() -> PresetFile.validate(secondNote), "duplicate note rejected");
        byte[] secondCurve = withExtraRecord(np3("CURVE", 0, true), 2, new byte[578]);
        fail(() -> PresetFile.validate(secondCurve), "duplicate curve rejected");
        byte[] lateNote = withExtraRecord(np3("LATE", 0, true), 0x10100, new byte[16]);
        fail(() -> PresetFile.validate(lateNote), "unverified note-after-curve ordering rejected");

        File dir = fresh(); PresetLibrary library = new PresetLibrary(dir);
        PresetRecord record = library.importFile("Original.NP3", original).record;
        eq("NP3", record.fileFormat, "local NP3 format stored"); eq("0310", record.ncpVersion, "legacy version field retained for NP3");
        eq("REAL NP3 NAME", record.displayName, "NP3 record name used");
        yes(new File(dir, "preset-library/" + record.id + ".np3").isFile(), "NP3 payload uses true extension");
        yes(!new File(dir, "preset-library/" + record.id + ".ncp").exists(), "NP3 never disguised as NCP");
        yes(Arrays.equals(original, library.readPreset(record.id)), "all NP3 source bytes preserved");
        fail(() -> library.readNcp(record.id), "NP3 blocked from legacy conversion/rendering readers");
        fail(() -> library.importFile("Disguised.ncp", original), "NP3 renamed NCP rejected");
        fail(() -> library.importFile("Disguised.np3", ncp("OLD", 4)), "NCP renamed NP3 rejected");
        library.setCategory(record.id, "我的收藏"); library.attachPreview(record.id, "content://photos/np3/1");
        PresetRecord customized = library.findBySha256(record.id);
        PresetLibrary reopened = new PresetLibrary(dir); PresetRecord restored = reopened.findBySha256(record.id);
        eq(customized.toMap(), restored.toMap(), "NP3 format/category/preview metadata survives reopen");
        eq(customized.toMap(), reopened.importFile("Renamed.np3", original).record.toMap(), "NP3 SHA duplicate retains original metadata");
        yes(Arrays.equals(original, reopened.readPreset(record.id)), "reopened NP3 exact source bytes");
        byte[] returned = reopened.readPreset(record.id); returned[0] = 0;
        yes(Arrays.equals(original, reopened.readPreset(record.id)), "NP3 reader returns detached bytes");
        fail(() -> reopened.readNcp(record.id), "NP3 guard persists after reopening");
        Files.write(new File(dir, "preset-library/" + record.id + ".np3").toPath(), base);
        fail(() -> reopened.readPreset(record.id), "corrupt NP3 rejected before reuse");
        eq(0, new PresetLibrary(dir).list().size(), "corrupt NP3 not restored");

        File legacyDir = fresh(); PresetLibrary legacy = new PresetLibrary(legacyDir);
        PresetRecord old = legacy.importFile("legacy.ncp", ncp("LEGACY FORMAT", 2)).record;
        Map<String, Object> oldMap = old.toMap(); oldMap.remove("fileFormat");
        Files.writeString(new File(legacyDir, "preset-library/" + old.id + ".json").toPath(), LibraryJson.stringify(oldMap));
        PresetLibrary oldReopened = new PresetLibrary(legacyDir);
        eq("NCP", oldReopened.findBySha256(old.id).fileFormat, "old schema1 metadata defaults to NCP");
        yes(Arrays.equals(legacy.readNcp(old.id), oldReopened.readPreset(old.id)), "old NCP path and SHA remain compatible");
        for (Object invalid : new Object[]{null, "", "np3", "NP2", Boolean.TRUE}) {
            Map<String, Object> map = old.toMap(); map.put("fileFormat", invalid);
            fail(() -> PresetRecord.fromMap(LibraryJson.object(LibraryJson.parse(LibraryJson.stringify(map)))), "invalid local format rejected");
        }
    }

    private static void np3CatalogAndDownload() throws Exception {
        byte[] original = np3("CLOUD ORIGINAL", 16, true);
        OnlinePreset online = parse(np3Manifest(original)).get(0);
        eq("NP3", online.fileFormat, "online NP3 format parsed");
        eq("NCP", parse(manifest(ncp("OLD ONLINE", 3))).get(0).fileFormat, "legacy online manifest defaults to NCP");
        eq("NP3", OnlinePreset.fromMap(LibraryJson.object(LibraryJson.parse(LibraryJson.stringify(online.toMap())))).fileFormat,
                "clean online cache retains NP3 format");
        for (String invalid : new String[]{"null", "\"\"", "\"np3\"", "\"NP2\"", "true"})
            fail(() -> parse(np3Manifest(original).replace("\"fileFormat\":\"NP3\"", "\"fileFormat\":" + invalid)),
                    "invalid online format rejected");
        File dir = fresh(); PresetLibrary library = new PresetLibrary(dir); Mock server = new Mock();
        OnlinePresetRepository repository = new OnlinePresetRepository(dir, library, server.client());
        repository.setEndpoint("https://host/catalog.json");
        server.add(200, np3Manifest(original).getBytes(StandardCharsets.UTF_8));
        eq("NP3", repository.fetchCatalog().presets.get(0).fileFormat, "live directory exposes declared NP3 format");
        eq("NP3", new OnlinePresetRepository(dir, library, server.client()).cachedCatalog().presets.get(0).fileFormat,
                "offline directory cache preserves NP3");
        server.add(200, original); PresetRecord record = repository.download(online, null).record;
        eq("NP3", record.fileFormat, "download stores checked actual NP3 format");
        eq("Catalog name.np3", record.originalFileName, "download file name preserves NP3 extension");
        yes(Arrays.equals(original, library.readPreset(record.id)), "download leaves NP3 bytes unchanged");
        fail(() -> library.readNcp(record.id), "downloaded NP3 cannot enter NCP renderer/converter");
        library.setCategory(record.id, "自定义"); library.attachPreview(record.id, "content://photos/cloud/1");
        PresetRecord customized = library.findBySha256(record.id);
        int before = server.calls.size(); PresetLibrary.AddResult duplicate = repository.download(online, null);
        yes(!duplicate.added, "NP3 repeated download reuses SHA"); eq(before, server.calls.size(), "NP3 reuse has no network call");
        eq(customized.toMap(), duplicate.record.toMap(), "NP3 duplicate preserves category/sample/source metadata");
        eq(customized.toMap(), library.addDownloaded(online, original).record.toMap(), "NP3 commit race also preserves metadata");
        PresetLibrary restored = new PresetLibrary(dir);
        OnlinePresetRepository reopened = new OnlinePresetRepository(dir, restored, server.client());
        yes(!reopened.download(online, null).added, "NP3 repeated download works after library reopening");
        eq(customized.toMap(), restored.findBySha256(record.id).toMap(), "NP3 reopening retains complete metadata");
        OnlinePreset wrongDeclaration = parse(manifest(original)).get(0);
        fail(() -> repository.download(wrongDeclaration, null), "reused SHA cannot be relabeled NCP by directory");
        eq(before, server.calls.size(), "format mismatch on local reuse causes no network");
        assertRejectedDownload(wrongDeclaration, original, 200, "NP3 bytes advertised as NCP rejected before commit");
        byte[] old = ncp("NCP CONTENT", 9);
        assertRejectedDownload(parse(np3Manifest(old)).get(0), old, 200, "NCP bytes advertised as NP3 rejected before commit");
        byte[] malformed = original.clone(); malformed[23] = 19;
        assertRejectedDownload(parse(np3Manifest(malformed)).get(0), malformed, 200, "correct digest cannot bypass NP3 container validation");
    }

    /** Optional local archive audit uses source bytes only; never executes or extracts archive contents. */
    private static void verifyDownloadedArchive(File archive) throws Exception {
        File dir = fresh(); PresetLibrary library = new PresetLibrary(dir); int ncp = 0, np3 = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement(); if (entry.isDirectory()) continue;
                byte[] bytes;
                try (InputStream stream = zip.getInputStream(entry)) { bytes = stream.readNBytes(1024 * 1024 + 1); }
                if (bytes.length < 4 || bytes[0] != 'N' || bytes[1] != 'C' || bytes[2] != 'P' || bytes[3] != 0) continue;
                PresetFile info = PresetFile.validate(bytes);
                String name = entry.getName();
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(PresetFile.extension(info.fileFormat)))
                    name += PresetFile.extension(info.fileFormat);
                PresetRecord record = library.importFile(name, bytes).record;
                eq(hash(bytes), record.sha256, "archive source digest unchanged");
                yes(Arrays.equals(bytes, library.readPreset(record.id)), "archive original source bytes preserved");
                if ("NP3".equals(info.fileFormat)) { np3++; fail(() -> library.readNcp(record.id), "real NP3 blocked from legacy conversion"); }
                else { ncp++; yes(Arrays.equals(bytes, library.readNcp(record.id)), "real NCP still readable through legacy API"); }
            }
        }
        eq(236, np3, "all original NP3 archive members accepted"); eq(4, ncp, "all NCP archive members including three extensionless files accepted");
        eq(240, library.list().size(), "all archive items have distinct SHA identities");
        PresetLibrary reopened = new PresetLibrary(dir);
        eq(240, reopened.list().size(), "complete real archive library survives reopening");
        eq(0, reopened.diagnostics().size(), "real archive persistence has no recovery failures");
        for (PresetRecord record : library.list())
            eq(record.toMap(), reopened.findBySha256(record.id).toMap(), "real archive metadata preserved after reopen");
        System.out.println("Downloaded archive: " + np3 + " NP3 + " + ncp + " NCP verified.");
    }

    private static byte[] np3(String name, int noteLength, boolean curve) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(new byte[]{'N', 'C', 'P', 0});
        for (int index = 1; index <= 32; index++) {
            int length = index == 1 ? 4 : index == 2 || index == 32 ? 20 : index == 31 ? 28 : 2;
            byte[] payload = new byte[length];
            if (index == 1) System.arraycopy("0310".getBytes(StandardCharsets.US_ASCII), 0, payload, 0, 4);
            if (index == 2) { byte[] label = name.getBytes(StandardCharsets.US_ASCII); System.arraycopy(label, 0, payload, 0, Math.min(20, label.length)); }
            writeRecord(output, index * 0x100, payload);
        }
        if (noteLength > 0) writeRecord(output, 0x10100, new byte[noteLength]);
        if (curve) writeRecord(output, 2, new byte[578]);
        output.write(new byte[4]); return output.toByteArray();
    }

    private static byte[] withExtraRecord(byte[] bytes, int id, byte[] payload) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(bytes, 0, bytes.length - 4);
        writeRecord(output, id, payload); output.write(new byte[4]); return output.toByteArray();
    }

    private static void writeRecord(ByteArrayOutputStream output, int id, byte[] payload) throws IOException {
        java.io.DataOutputStream data = new java.io.DataOutputStream(output); data.writeInt(id); data.writeInt(payload.length); data.write(payload);
    }

    private static String np3Manifest(byte[] bytes) throws Exception {
        return manifest(bytes).replace("\"name\":", "\"fileFormat\":\"NP3\",\"name\":").replace("preset.ncp", "preset.np3");
    }

    private static void redirectAndNetworkBoundaries() throws Exception {
        Mock server = new Mock(); Response redirect = server.add(302, new byte[0]); redirect.location = "/next";
        server.add(200, new byte[]{1, 2});
        byte[] result = server.client().get("https://host/start", 10, -1, "application/octet-stream", null);
        eq(2, result.length, "HTTPS relative redirect accepted"); eq("https://host/next", server.urls.get(1), "relative redirect resolved");
        for (MockConnection call : server.calls) {
            yes(!call.getInstanceFollowRedirects(), "automatic redirects disabled"); yes(call.disconnected, "connection always closed");
            eq(8000, call.getConnectTimeout(), "connect timeout"); eq(10000, call.getReadTimeout(), "read timeout");
            eq("identity", call.getRequestProperty("Accept-Encoding"), "bounded decoded transfer policy");
        }
        Mock downgrade = new Mock(); downgrade.add(302, new byte[0]).location = "http://host/file";
        fail(() -> downgrade.client().get("https://host/start", 10, -1, "*/*", null), "HTTPS downgrade rejected");
        eq(1, downgrade.calls.size(), "no request to HTTP redirect");
        Mock loop = new Mock(); for (int i = 0; i < 4; i++) loop.add(302, new byte[0]).location = "/again";
        fail(() -> loop.client().get("https://host/start", 10, -1, "*/*", null), "redirect cap"); eq(4, loop.calls.size(), "at most three redirects");
        Mock hugeHeader = new Mock(); hugeHeader.add(200, new byte[]{1}).declared = 999;
        fail(() -> hugeHeader.client().get("https://host/start", 10, -1, "*/*", null), "declared oversize");
        yes(!hugeHeader.calls.get(0).bodyOpened, "oversize header stopped before body");
        Mock chunked = new Mock(); chunked.add(200, new byte[11]).declared = -1;
        fail(() -> chunked.client().get("https://host/start", 10, -1, "*/*", null), "actual streaming size bounded");
        Mock gzip = new Mock(); gzip.add(200, new byte[]{1}).encoding = "gzip";
        fail(() -> gzip.client().get("https://host/start", 10, -1, "*/*", null), "unexpected compressed stream refused");
    }

    private static void cancelledAndFailedDownloads() throws Exception {
        byte[] original = ncp("CANCEL", 1); OnlinePreset preset = parse(manifest(original)).get(0);
        File dir = fresh(); PresetLibrary lib = new PresetLibrary(dir); Mock server = new Mock(); server.add(200, original);
        OnlinePresetRepository repo = new OnlinePresetRepository(dir, lib, server.client());
        fail(() -> repo.download(preset, (done, total) -> { if (done > 0) throw new IOException("cancel"); }), "callback cancellation propagated");
        eq(0, lib.list().size(), "cancelled download not committed"); yes(server.calls.get(0).disconnected, "cancelled HTTP closed");
        Mock interrupted = new Mock(); Thread.currentThread().interrupt();
        try { fail(() -> interrupted.client().get("https://host/a", 10, -1, "*/*", null), "thread cancellation before request"); }
        finally { Thread.interrupted(); }
        eq(0, interrupted.calls.size(), "pre-cancelled operation performs zero network");
        Mock failure = new Mock(); Response response = failure.add(200, original); response.declared = -1;
        response.stream = new InputStream() { int calls;
            public int read() throws IOException { throw new IOException("network failure"); }
            public int read(byte[] b, int o, int n) throws IOException {
                if (calls++ > 0) throw new IOException("network failure");
                System.arraycopy(original, 0, b, o, 10); return 10;
            }
        };
        OnlinePresetRepository broken = new OnlinePresetRepository(fresh(), lib, failure.client());
        fail(() -> broken.download(preset, null), "partial network failure propagated");
        eq(0, lib.list().size(), "partial response not added"); yes(failure.calls.get(0).disconnected, "partial response connection closed");
    }

    private static void previewRejectsHtml() throws Exception {
        byte[] original = ncp("PHOTO", 1); File dir = fresh(); PresetLibrary lib = new PresetLibrary(dir);
        PresetRecord record = lib.importFile("photo.ncp", original).record;
        OnlinePreset preset = parse(manifest(original)).get(0); Mock server = new Mock();
        server.add(200, "<html>not an image</html>".getBytes(StandardCharsets.UTF_8));
        OnlinePresetRepository repo = new OnlinePresetRepository(dir, lib, server.client());
        fail(() -> repo.downloadPreview(preset), "HTML sample rejected before decoder/cache");
        eq("", lib.findBySha256(record.id).previewUri, "invalid sample not associated");
        eq(1, lib.list().size(), "sample failure does not discard valid preset");
        yes(!new File(dir, "online-presets/previews").exists(), "HTML is not cached as image");
        fail(() -> PreviewImages.validate(new byte[]{(byte) 0xff, (byte) 0xd8, 0}), "truncated image envelope refused");
    }

    private static void readOnlyOnlinePreviews() throws Exception {
        for (boolean flexible : new boolean[]{false, true}) {
            byte[] original = flexible ? np3("PREVIEW NP3", 0, false) : ncp("PREVIEW NCP", 1);
            OnlinePreset preset = parse(flexible ? np3Manifest(original) : manifest(original)).get(0);
            File dir = fresh(); PresetLibrary library = new PresetLibrary(dir); Mock server = new Mock(); server.add(200, original);
            OnlinePresetRepository repository = new OnlinePresetRepository(dir, library, server.client());
            byte[] first = repository.readForPreview(preset);
            yes(Arrays.equals(original, first), "preview returns checked original " + preset.fileFormat);
            eq(0, library.list().size(), "browsing does not add a library record");
            eq(0, new PresetLibrary(dir).list().size(), "browsing does not become a download on restart");
            File cache = new File(dir, "online-presets/recipes/" + preset.sha256 + (flexible ? ".np3" : ".ncp"));
            yes(cache.isFile(), "preview recipe has separate content addressed cache");
            yes(Arrays.equals(original, Files.readAllBytes(cache.toPath())), "cache preserves complete original bytes");
            first[0] = 0;
            OnlinePresetRepository reopened = new OnlinePresetRepository(dir, library, server.client());
            yes(Arrays.equals(original, reopened.readForPreview(preset)), "caller mutations and restart cannot corrupt cache");
            eq(1, server.calls.size(), "repeat preview is offline and deduplicated");
            Files.write(cache.toPath(), new byte[]{1, 2}); server.add(200, original);
            yes(Arrays.equals(original, reopened.readForPreview(preset)), "corrupt cache is fetched and verified again");
            eq(2, server.calls.size(), "corrupt cached recipe is not trusted");
            List<Long> progress = new ArrayList<>();
            PresetLibrary.AddResult saved = reopened.download(preset, (done, total) -> progress.add(done));
            yes(saved.added, "explicit download commits an already cached recipe");
            eq(2, server.calls.size(), "explicit download reuses verified recipe without a second transfer");
            yes(Arrays.equals(original, library.readPreset(saved.record.id)), "explicit download keeps original bytes");
            eq((long) original.length, progress.get(progress.size() - 1), "cached download reports complete byte progress");
            library.setCategory(saved.record.id, "我的分类");
            Map<String, Object> before = library.findBySha256(saved.record.id).toMap();
            yes(Arrays.equals(original, reopened.readForPreview(preset)), "downloaded local recipe is used for preview");
            eq(before, library.findBySha256(saved.record.id).toMap(), "preview leaves user's local metadata intact");
            eq(2, server.calls.size(), "local preview never requests internet");
        }
    }

    private static void rejectedOnlinePreviews() throws Exception {
        byte[] original = ncp("PREVIEW SAFE", 1); OnlinePreset preset = parse(manifest(original)).get(0);
        byte[] changed = original.clone(); changed[40]++;
        for (byte[] body : new byte[][]{Arrays.copyOf(original, 20), changed}) {
            File dir = fresh(); PresetLibrary library = new PresetLibrary(dir); Mock server = new Mock(); server.add(200, body);
            OnlinePresetRepository repository = new OnlinePresetRepository(dir, library, server.client());
            fail(() -> repository.readForPreview(preset), "preview rejects short or hash mismatched response");
            eq(0, library.list().size(), "failed preview creates no local preset");
            yes(!new File(dir, "online-presets/recipes").exists(), "unverified response is never cached");
            yes(server.calls.get(0).disconnected, "failed preview closes HTTP connection");
        }
        byte[] np3 = np3("WRONG FORMAT", 0, false);
        OnlinePreset lie = parse(manifest(np3)).get(0);
        File mismatchDir = fresh(); PresetLibrary mismatchLibrary = new PresetLibrary(mismatchDir); Mock mismatch = new Mock(); mismatch.add(200, np3);
        OnlinePresetRepository mismatchRepository = new OnlinePresetRepository(mismatchDir, mismatchLibrary, mismatch.client());
        fail(() -> mismatchRepository.readForPreview(lie), "preview rejects catalog format different from binary container");
        yes(!new File(mismatchDir, "online-presets/recipes").exists(), "wrong format bytes not cached");
        eq(0, mismatchLibrary.list().size(), "wrong format preview not stored in user library");
        File cancelledDir = fresh(); PresetLibrary cancelledLibrary = new PresetLibrary(cancelledDir); Mock cancel = new Mock(); cancel.add(200, original);
        OnlinePresetRepository cancelled = new OnlinePresetRepository(cancelledDir, cancelledLibrary, cancel.client());
        fail(() -> cancelled.readForPreview(preset, (done, total) -> { if (done > 0) throw new IOException("cancelled"); }), "offscreen preview can cancel transfer");
        yes(!new File(cancelledDir, "online-presets/recipes").exists(), "cancelled transfer leaves no payload cache");
        eq(0, cancelledLibrary.list().size(), "cancelled preview leaves no library record");
        int calls = cancel.calls.size();
        fail(() -> cancelled.readForPreview(preset, (done, total) -> { throw new IOException("cancelled before start"); }), "pre-cancelled preview stopped");
        eq(calls, cancel.calls.size(), "pre-cancelled preview makes zero extra network requests");
    }

    private static void boundedRecipeCache() throws Exception {
        File dir = fresh(); File recipes = new File(dir, "online-presets/recipes"); recipes.mkdirs();
        File untouched = new File(recipes, "user-note.txt"); Files.writeString(untouched.toPath(), "preserve");
        for (int index = 0; index < 9; index++) {
            File old = new File(recipes, String.format("%064x.ncp", index));
            try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(old, "rw")) { output.setLength(1024 * 1024); }
            old.setLastModified(index + 1000);
        }
        byte[] original = ncp("TRIM BYTE", 1); OnlinePreset preset = parse(manifest(original)).get(0);
        Mock server = new Mock(); server.add(200, original);
        OnlinePresetRepository repository = new OnlinePresetRepository(dir, new PresetLibrary(dir), server.client());
        repository.readForPreview(preset);
        long bytes = 0; for (File file : recipes.listFiles()) if (file.getName().matches("[0-9a-f]{64}\\.(ncp|np3)")) bytes += file.length();
        yes(bytes <= 8L * 1024 * 1024, "recipe cache has bounded total payload bytes");
        eq("preserve", Files.readString(untouched.toPath()), "trimming never touches unrecognized files");
        for (int index = 10; index < 280; index++) {
            File old = new File(recipes, String.format("%064x.ncp", index)); Files.write(old.toPath(), new byte[]{1}); old.setLastModified(index + 1000);
        }
        byte[] next = ncp("TRIM COUNT", 2); OnlinePreset other = parse(manifest(next)).get(0); server.add(200, next);
        repository.readForPreview(other);
        int count = 0; for (File file : recipes.listFiles()) if (file.getName().matches("[0-9a-f]{64}\\.(ncp|np3)")) count++;
        yes(count <= 256, "recipe cache file count is bounded");
        yes(Arrays.equals(next, repository.readForPreview(other)), "current recipe survives trimming");
        eq("preserve", Files.readString(untouched.toPath()), "cache count pruning preserves unknown files");
    }

    private static void assertRejectedDownload(OnlinePreset preset, byte[] body, int status, String message) throws Exception {
        File dir = fresh(); PresetLibrary lib = new PresetLibrary(dir); Mock server = new Mock();
        server.add(status, body); OnlinePresetRepository repo = new OnlinePresetRepository(dir, lib, server.client());
        fail(() -> repo.download(preset, null), message); eq(0, lib.list().size(), message + " no record");
        eq(0, new PresetLibrary(dir).list().size(), message + " no record after reopen");
        yes(server.calls.get(0).disconnected, message + " connection closed");
    }
    private static List<OnlinePreset> parse(String json) throws Exception {
        return OnlinePresetRepository.parseManifest(LibraryJson.object(LibraryJson.parse(json)));
    }
    private static String manifest(byte[] bytes) throws Exception {
        return "{\"version\":1,\"presets\":[{\"id\":\"host-one\",\"name\":\"Catalog name\",\"description\":\"Description\","
                + "\"fileUrl\":\"https://host/preset.ncp\",\"sha256\":\"" + hash(bytes) + "\",\"byteLength\":" + bytes.length
                + ",\"previewUrl\":\"https://host/sample.jpg\",\"compatibleModels\":[\"Z6II\"]}]}";
    }
    private static byte[] ncp(String name, int marker) {
        byte[] b = new byte[52]; b[0] = 'N'; b[1] = 'C'; b[2] = 'P'; b[7] = 1; b[11] = 36;
        b[12] = '0'; b[13] = '1'; b[14] = '0'; b[15] = '0';
        byte[] text = name.getBytes(StandardCharsets.US_ASCII); System.arraycopy(text, 0, b, 16, text.length);
        b[40] = (byte) marker; return b; // Synthetic structural envelope, not a claim of camera compatibility.
    }
    private static String hash(byte[] data) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) s.append(String.format("%02x", b & 255));
        return s.toString();
    }
    private static File fresh() throws IOException { return Files.createTempDirectory(tempRoot.toPath(), "library-").toFile(); }
    private interface Action { void run() throws Exception; }
    private static void fail(Action action, String label) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection: " + label); }
        catch (IOException | IllegalArgumentException expected) { checks++; }
    }
    private static void yes(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void eq(Object expected, Object actual, String label) {
        checks++; if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
    }

    private static final class Response {
        final int status; final byte[] bytes; long declared; String location, encoding; InputStream stream;
        Response(int status, byte[] bytes) { this.status = status; this.bytes = bytes; this.declared = bytes.length; }
    }
    private static final class Mock {
        final Queue<Response> pending = new ArrayDeque<>(); final List<MockConnection> calls = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        Response add(int code, byte[] bytes) { Response r = new Response(code, bytes); pending.add(r); return r; }
        SecureHttpClient client() { return new SecureHttpClient(url -> {
            if (pending.isEmpty()) throw new IOException("unexpected test request");
            MockConnection connection = new MockConnection(url, pending.remove()); calls.add(connection); urls.add(url.toString()); return connection;
        }); }
    }
    private static final class MockConnection extends HttpsURLConnection {
        final Response response; boolean disconnected, bodyOpened;
        MockConnection(URL url, Response response) { super(url); this.response = response; }
        public int getResponseCode() { return response.status; }
        public long getContentLengthLong() { return response.declared; }
        public String getHeaderField(String name) {
            if ("Location".equalsIgnoreCase(name)) return response.location;
            if ("Content-Encoding".equalsIgnoreCase(name)) return response.encoding;
            return null;
        }
        public InputStream getInputStream() { bodyOpened = true; return response.stream == null ? new ByteArrayInputStream(response.bytes) : response.stream; }
        public void disconnect() { disconnected = true; }
        public boolean usingProxy() { return false; }
        public void connect() {}
        public String getCipherSuite() { return "TEST"; }
        public Certificate[] getLocalCertificates() { return new Certificate[0]; }
        public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }
}

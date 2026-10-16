package com.nikonpreset.usbprobe.library;

import java.io.ByteArrayInputStream;
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
import java.util.Queue;
import javax.net.ssl.HttpsURLConnection;

/** Real library/files/strict JSON/HTTPS policy; injected HTTP responses perform no network calls. */
public final class PresetLibraryTest {
    private static int checks;
    private static File tempRoot;
    public static void main(String[] args) throws Exception {
        tempRoot = new File(args[0]); tempRoot.mkdirs();
        multipleFilesAndPersistence(); legacyMigration(); strictParsing(); catalogConfigurationAndCache();
        downloadedFiles(); redirectAndNetworkBoundaries(); cancelledAndFailedDownloads(); previewRejectsHtml();
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

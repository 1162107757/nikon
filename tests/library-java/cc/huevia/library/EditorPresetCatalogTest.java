package cc.huevia.library;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Real editor/catalog/cache/library path with bounded injected HTTPS responses; no network I/O. */
public final class EditorPresetCatalogTest {
    private static int checks;
    private static File scratch;

    public static void main(String[] args) throws Exception {
        scratch = new File(args[0]); scratch.mkdirs();
        onlineCategoriesAndReadOnlyRecipes();
        offlineFallbackAndMissingCache();
        cacheValidationAndIsolation();
        recipeValidation();
        recipeReadsDoNotWaitForCatalog();
        System.out.println("Editor preset catalog: " + checks + " checks passed (no network I/O).");
    }

    private static void onlineCategoriesAndReadOnlyRecipes() throws Exception {
        File dir = fresh(); PresetLibrary local = new PresetLibrary(dir); Mock server = new Mock();
        byte[] portrait = ncp("SOFT", 1), landscape = ncp("VIVID", 2);
        OnlinePreset first = preset(portrait, "portrait", "人像", "NCP");
        OnlinePreset second = preset(landscape, "landscape", "风景", "NCP");
        server.add(200, manifest(first, second));
        EditorPresetCatalog source = new EditorPresetCatalog(dir, local, server.client());
        eq(0, server.urls.size(), "construction performs no network I/O");
        OnlinePresetRepository.CatalogResult loaded = source.load();
        eq("ok", loaded.status, "live catalog succeeds");
        yes(!loaded.fromCache, "fresh catalog is marked online");
        eq(2, loaded.presets.size(), "all catalog presets included");
        eq("人像", loaded.presets.get(0).category, "publisher portrait category retained");
        eq("风景", loaded.presets.get(1).category, "publisher landscape category retained");
        eq(OnlinePresetRepository.MANAGED_CATALOG_URL, server.urls.get(0), "editor uses shared managed endpoint");
        eq(0, local.list().size(), "catalog does not add local presets");
        server.add(200, portrait);
        byte[] recipe = source.read(first);
        yes(Arrays.equals(portrait, recipe), "read returns exact verified recipe");
        eq(first.fileUrl, server.urls.get(1), "recipe is fetched only when requested");
        eq(0, new PresetLibrary(dir).list().size(), "browsing and applying stay outside local library");
        recipe[0] = 0;
        EditorPresetCatalog reopened = new EditorPresetCatalog(dir, local, server.client());
        yes(Arrays.equals(portrait, reopened.read(first)), "verified offline recipe survives restart and caller mutation");
        eq(2, server.urls.size(), "cached recipe read requires no catalog or network");

        PresetRecord saved = local.importFile("mine.ncp", portrait).record;
        local.setCategory(saved.id, "我自己的分类");
        Map<String, Object> before = local.findBySha256(saved.id).toMap();
        yes(Arrays.equals(portrait, source.read(first)), "same-SHA local recipe can render online effect");
        eq(before, local.findBySha256(saved.id).toMap(), "online category does not overwrite local metadata");
        eq(2, server.urls.size(), "same-SHA local recipe does not request network");
    }

    private static void offlineFallbackAndMissingCache() throws Exception {
        File dir = fresh(); PresetLibrary local = new PresetLibrary(dir); Mock server = new Mock();
        OnlinePreset first = preset(ncp("OFFLINE", 3), "offline", "胶片", "NCP");
        server.add(200, manifest(first));
        EditorPresetCatalog source = new EditorPresetCatalog(dir, local, server.client());
        eq("ok", source.load().status, "initial catalog stored");
        OnlinePresetRepository.CatalogResult offline = source.load(); // No response simulates network failure.
        eq("cached", offline.status, "network failure falls back automatically");
        yes(offline.fromCache, "fallback explicitly marked cached");
        eq(first.sha256, offline.presets.get(0).sha256, "fallback retains verified catalog digest");
        eq("胶片", offline.presets.get(0).category, "fallback retains publisher category");
        yes(offline.message.contains("缓存") && offline.message.contains("联网"), "fallback explains offline recipe limitations");
        server.add(200, "<html>not a catalog</html>".getBytes(StandardCharsets.UTF_8));
        eq("cached", source.load().status, "invalid live response also preserves last verified catalog");
        server.add(200, manifest());
        OnlinePresetRepository.CatalogResult empty = source.load();
        eq("ok", empty.status, "valid empty catalog remains authoritative");
        eq(0, empty.presets.size(), "valid empty response does not restore old entries");
        eq(0, local.list().size(), "fallback never adds local records");

        File noCache = fresh(); Mock unavailable = new Mock();
        OnlinePresetRepository.CatalogResult missing = new EditorPresetCatalog(noCache, new PresetLibrary(noCache), unavailable.client()).load();
        eq("error", missing.status, "missing cache preserves actionable live error");
        yes(!missing.fromCache, "failure without cache is not described as cached content");
        eq(0, missing.presets.size(), "missing cache supplies no fabricated presets");
        yes(!missing.message.isEmpty(), "missing cache explains failed loading");
    }

    private static void cacheValidationAndIsolation() throws Exception {
        File dir = fresh(); PresetLibrary local = new PresetLibrary(dir); Mock server = new Mock();
        OnlinePreset item = preset(ncp("CACHE", 4), "cached", "日常", "NCP");
        server.add(200, manifest(item));
        EditorPresetCatalog source = new EditorPresetCatalog(dir, local, server.client());
        eq("ok", source.load().status, "cache fixture created");
        File cache = new File(dir, "online-presets/catalog.json");
        String valid = Files.readString(cache.toPath());
        Files.writeString(cache.toPath(), valid.replace(OnlinePresetRepository.MANAGED_CATALOG_URL,
                "https://unrelated.example.test/catalog.json"));
        eq("error", source.load().status, "foreign catalog cache cannot appear in editor");
        Files.writeString(cache.toPath(), valid.replace("\"sha256\":\"" + item.sha256, "\"sha256\":\"invalid"));
        eq("error", source.load().status, "malformed cached digest is rejected");
        Files.writeString(cache.toPath(), valid);
        server.add(200, new byte[OnlinePresetRepository.MAX_MANIFEST_BYTES + 1]);
        OnlinePresetRepository.CatalogResult oversized = source.load();
        eq("cached", oversized.status, "oversized live response cannot replace verified cache");
        eq(valid, Files.readString(cache.toPath()), "oversized manifest leaves prior cache byte-identical");

        File boundedDir = fresh(); Mock tooMany = new Mock();
        List<OnlinePreset> rows = new ArrayList<>();
        for (int index = 0; index < 257; index++) rows.add(preset(ncp("BOUNDED", 4), "row-" + index, "人像", "NCP"));
        tooMany.add(200, manifest(rows.toArray(new OnlinePreset[0])));
        EditorPresetCatalog bounded = new EditorPresetCatalog(boundedDir, new PresetLibrary(boundedDir), tooMany.client());
        eq("error", bounded.load().status, "editor keeps existing 256-entry manifest bound");
        yes(!new File(boundedDir, "online-presets/catalog.json").exists(), "unvalidated catalog is never cached");
    }

    private static void recipeValidation() throws Exception {
        byte[] bytes = ncp("CHECKED", 5);
        OnlinePreset item = preset(bytes, "checked", "夜景", "NCP");
        byte[] changed = bytes.clone(); changed[40]++;
        for (byte[] payload : new byte[][]{changed, Arrays.copyOf(bytes, 20), new byte[1024 * 1024 + 1]}) {
            File dir = fresh(); PresetLibrary local = new PresetLibrary(dir); Mock server = new Mock(); server.add(200, payload);
            EditorPresetCatalog source = new EditorPresetCatalog(dir, local, server.client());
            rejected(() -> source.read(item), "recipe hash/length/limit validation retained");
            eq(0, local.list().size(), "failed recipe leaves no local record");
            yes(!new File(dir, "online-presets/recipes").exists(), "failed recipe is not cached");
        }
        File dir = fresh(); PresetLibrary local = new PresetLibrary(dir); Mock server = new Mock(); server.add(200, bytes);
        EditorPresetCatalog source = new EditorPresetCatalog(dir, local, server.client());
        OnlinePreset wrongContainer = preset(bytes, "wrong-format", "人像", "NP3");
        rejected(() -> source.read(wrongContainer), "declared container must match real bytes");
        rejected(() -> source.read(null), "no selection reports an actionable error");
        eq(0, local.list().size(), "invalid container does not add local preset");
    }

    private static void recipeReadsDoNotWaitForCatalog() throws Exception {
        File dir = fresh(); PresetLibrary local = new PresetLibrary(dir); Mock server = new Mock();
        byte[] recipe = ncp("PARALLEL", 6); OnlinePreset item = preset(recipe, "parallel", "人像", "NCP");
        CountDownLatch loading = new CountDownLatch(1), release = new CountDownLatch(1);
        Response slow = server.add(200, manifest(item));
        slow.stream = new ByteArrayInputStream(slow.body) {
            private boolean blocked;
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                if (!blocked) {
                    blocked = true; loading.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("catalog test was not released"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                }
                return super.read(bytes, offset, length);
            }
        };
        server.add(200, recipe);
        EditorPresetCatalog source = new EditorPresetCatalog(dir, local, server.client());
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<OnlinePresetRepository.CatalogResult> catalog = workers.submit(source::load);
            yes(loading.await(2, TimeUnit.SECONDS), "catalog network response can remain in flight");
            Future<byte[]> preview = workers.submit(() -> source.read(item));
            yes(Arrays.equals(recipe, preview.get(2, TimeUnit.SECONDS)), "recipe read is independent of pending catalog loading");
            release.countDown();
            eq("ok", catalog.get(2, TimeUnit.SECONDS).status, "catalog completes after independent recipe");
            eq(0, local.list().size(), "parallel browsing remains read-only");
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    private static OnlinePreset preset(byte[] bytes, String id, String category, String format) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id); row.put("name", "Preset " + id); row.put("category", category);
        row.put("fileUrl", "https://recipes.example.test/" + id + "." + format.toLowerCase(java.util.Locale.ROOT));
        row.put("sha256", LibraryFiles.sha256(bytes)); row.put("byteLength", bytes.length); row.put("fileFormat", format);
        return OnlinePreset.fromMap(LibraryJson.object(LibraryJson.parse(LibraryJson.stringify(row))));
    }

    private static byte[] manifest(OnlinePreset... presets) {
        List<Object> rows = new ArrayList<>(); for (OnlinePreset item : presets) rows.add(item.toMap());
        Map<String, Object> data = new LinkedHashMap<>(); data.put("version", 1); data.put("presets", rows);
        return LibraryJson.stringify(data).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] ncp(String name, int marker) {
        byte[] bytes = new byte[52]; bytes[0] = 'N'; bytes[1] = 'C'; bytes[2] = 'P'; bytes[7] = 1; bytes[11] = 36;
        bytes[12] = '0'; bytes[13] = '1'; bytes[14] = '0'; bytes[15] = '0';
        byte[] label = name.getBytes(StandardCharsets.US_ASCII); System.arraycopy(label, 0, bytes, 16, label.length);
        bytes[40] = (byte) marker; return bytes;
    }

    private static File fresh() throws IOException { return Files.createTempDirectory(scratch.toPath(), "editor-catalog-").toFile(); }
    private interface Action { void run() throws Exception; }
    private static void rejected(Action action, String label) throws Exception {
        try { action.run(); throw new AssertionError("Expected rejection: " + label); }
        catch (IOException | IllegalArgumentException expected) { checks++; }
    }
    private static void yes(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void eq(Object expected, Object actual, String label) {
        checks++; if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
    }

    private static final class Response {
        final int status; final byte[] body; InputStream stream;
        Response(int status, byte[] body) { this.status = status; this.body = body; }
    }

    private static final class Mock {
        private final Queue<Response> responses = new ArrayDeque<>();
        final List<String> urls = new ArrayList<>();
        synchronized Response add(int status, byte[] body) { Response response = new Response(status, body); responses.add(response); return response; }
        SecureHttpClient client() { return new SecureHttpClient(this::open); }
        private synchronized HttpsURLConnection open(URL url) throws IOException {
            if (responses.isEmpty()) throw new IOException("offline test connection");
            urls.add(url.toString()); return new Connection(url, responses.remove());
        }
    }

    private static final class Connection extends HttpsURLConnection {
        final Response response;
        Connection(URL url, Response response) { super(url); this.response = response; }
        @Override public int getResponseCode() { return response.status; }
        @Override public long getContentLengthLong() { return response.body.length; }
        @Override public InputStream getInputStream() { return response.stream == null ? new ByteArrayInputStream(response.body) : response.stream; }
        @Override public void disconnect() {}
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() {}
        @Override public String getCipherSuite() { return "TEST"; }
        @Override public Certificate[] getLocalCertificates() { return new Certificate[0]; }
        @Override public Certificate[] getServerCertificates() { return new Certificate[0]; }
    }
}

package cc.huevia.library;

import java.io.File;
import java.io.IOException;

/**
 * Worker-only online source for the photo editor. Catalog browsing and recipe reads use
 * the repository's validated, bounded cache without adding anything to the user's library.
 * Catalog loading and recipe reads may run on separate workers.
 */
public final class EditorPresetCatalog {
    private final OnlinePresetRepository repository;

    public EditorPresetCatalog(File appFilesDir, PresetLibrary local) throws IOException {
        this(appFilesDir, local, new SecureHttpClient());
    }

    EditorPresetCatalog(File appFilesDir, PresetLibrary local, SecureHttpClient http) throws IOException {
        repository = new OnlinePresetRepository(appFilesDir, local, http);
        repository.useManagedEndpoint(OnlinePresetRepository.MANAGED_CATALOG_URL);
    }

    /**
     * Fetch all editor categories from the managed manifest. A network/validation failure
     * may fall back only to an independently validated cache from the same managed service.
     * A valid empty online catalog remains authoritative and does not restore older entries.
     */
    public OnlinePresetRepository.CatalogResult load() {
        OnlinePresetRepository.CatalogResult fresh = repository.fetchCatalog();
        if ("ok".equals(fresh.status)) return fresh;
        OnlinePresetRepository.CatalogResult cached = repository.cachedCatalog();
        if ("cached".equals(cached.status)) {
            return new OnlinePresetRepository.CatalogResult("cached",
                    "在线预设暂时无法更新，正在显示上次缓存的分类与预设；已缓存的配方可继续套用，其他配方仍需联网。",
                    cached.presets, true);
        }
        return fresh;
    }

    /**
     * Return the exact SHA-256/length/container-checked recipe. This does not depend on a
     * preceding load() call, change a same-SHA local preset's category, or import a preset.
     */
    public byte[] read(OnlinePreset preset) throws IOException {
        return repository.readForPreview(preset);
    }
}

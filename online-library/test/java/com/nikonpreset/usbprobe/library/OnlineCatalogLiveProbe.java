package com.nikonpreset.usbprobe.library;

import java.io.File;

/** Host-JDK probe using the App's real catalog, HTTP and local-library implementation. */
public final class OnlineCatalogLiveProbe {
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("Expected catalog, owned ID, two hashes and scratch directory.");
        File scratch = new File(args[4]);
        if (scratch.exists()) throw new IllegalArgumentException("Scratch directory must be new.");
        PresetLibrary library = new PresetLibrary(scratch);
        OnlinePresetRepository repository = new OnlinePresetRepository(scratch, library);
        check(repository.initializeDefaultEndpoint(args[0]), "New installation must accept its default endpoint.");
        OnlinePresetRepository.CatalogResult catalog = repository.fetchCatalog();
        check("ok".equals(catalog.status) && !catalog.fromCache, "Live catalog must pass the native parser.");
        OnlinePreset target = null;
        for (OnlinePreset item : catalog.presets) if (args[1].equals(item.id)) target = item;
        check(target != null && "胶片".equals(target.category),
                "Owned synthetic published item must appear with its initial category in the catalog.");
        check(args[2].equals(target.sha256), "Catalog digest must match the uploaded fixture.");
        PresetLibrary.AddResult downloaded = repository.download(target, null);
        check(downloaded.added, "First download must add a local preset.");
        PresetRecord restored = new PresetLibrary(scratch).findBySha256(downloaded.record.id);
        check("online".equals(downloaded.record.source) && "胶片".equals(downloaded.record.category)
                        && restored != null && "胶片".equals(restored.category),
                "Downloaded record must retain its online source and published category after reopening local metadata.");
        byte[] ncp = library.readNcp(downloaded.record.id);
        check(args[2].equals(LibraryFiles.sha256(ncp)) && ncp.length == target.byteLength,
                "Native download and NCP envelope validation must preserve exact bytes.");
        PresetLibrary.AddResult repeated = repository.download(target, null);
        check(!repeated.added && library.list().size() == 1, "Repeated download must reuse the verified local file.");
        OnlinePresetRepository.CatalogResult cached = new OnlinePresetRepository(scratch, library).cachedCatalog();
        boolean cachedTarget = false;
        for (OnlinePreset item : cached.presets)
            if (args[1].equals(item.id) && "胶片".equals(item.category)) cachedTarget = true;
        check("cached".equals(cached.status) && cachedTarget,
                "Reopened native catalog cache must retain the published item and category.");
        check(!target.previewUrl.isEmpty(), "Published fixture must provide its sample URL.");
        // BitmapFactory belongs to the Android runtime. Here only its real HTTP transport is exercised.
        byte[] preview = new SecureHttpClient().get(target.previewUrl,
                OnlinePresetRepository.MAX_PREVIEW_BYTES, -1, "image/png", null);
        check(args[3].equals(LibraryFiles.sha256(preview)), "Native sample transport must preserve exact bytes.");
        System.out.println("Native public catalog probe passed: " + checks + " checks (host JDK; image decoding excluded).");
    }
}

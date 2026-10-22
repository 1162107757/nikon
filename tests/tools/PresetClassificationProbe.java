import cc.huevia.ncp.PresetPreviewProfile;
import cc.huevia.ncp.PresetPreviewProfiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Read-only style measurements using exactly the colour approximation used by the Android editor. */
public final class PresetClassificationProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: PresetClassificationProbe <verified-preset-directory>");
        try (var paths = Files.list(Path.of(args[0]))) {
            for (Path path : paths.sorted().toList()) {
                if (!path.getFileName().toString().matches("[a-f0-9]{64}\\.(ncp|np3)")) continue;
                String hash = path.getFileName().toString().substring(0, 64);
                try {
                    PresetPreviewProfile profile = PresetPreviewProfiles.fromPreset(Files.readAllBytes(path));
                    double lift = 0, saturation = 0, neutralChroma = 0;
                    for (int i = 32; i <= 224; i += 16) {
                        int pixel = profile.applyArgb(0xff000000 | i << 16 | i << 8 | i);
                        lift += luma(pixel) - i / 255.0;
                        neutralChroma += chroma(pixel);
                    }
                    for (int i = 0; i < 12; i++) {
                        int input = java.awt.Color.HSBtoRGB(i / 12f, .55f, .75f);
                        saturation += chroma(profile.applyArgb(input)) / chroma(input);
                    }
                    int dark = profile.applyArgb(0xff333333), light = profile.applyArgb(0xffcccccc);
                    System.out.printf(Locale.ROOT,
                        "{\"sha256\":\"%s\",\"supported\":true,\"model\":\"%s\",\"lift\":%.5f,\"contrast\":%.5f,\"chromaRatio\":%.5f,\"neutralTint\":%.5f}%n",
                        hash, profile.modelVersion(), lift / 13, (luma(light) - luma(dark)) / .6, saturation / 12, neutralChroma / 13);
                } catch (Exception unsupported) {
                    // Unsupported proprietary parameters are not silently interpreted.
                    System.out.println("{\"sha256\":\"" + hash + "\",\"supported\":false}");
                }
            }
        }
    }
    private static double luma(int pixel) {
        return (.2126 * ((pixel >>> 16) & 255) + .7152 * ((pixel >>> 8) & 255) + .0722 * (pixel & 255)) / 255;
    }
    private static double chroma(int pixel) {
        double r = ((pixel >>> 16) & 255) / 255.0, g = ((pixel >>> 8) & 255) / 255.0, b = (pixel & 255) / 255.0;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }
}

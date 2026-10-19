package com.nikonpreset.usbprobe.ncp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only regression against the existing downloaded corpus; never extracts or rewrites recipes. */
public final class Np3CorpusTest {
    private static final String ARCHIVE_SHA256 = "7a1cf3cd719ed9ec69dae4936625afb1fc3cc1871bc6d61f46c27903a32a087c";
    private static final String CREATIVE_RECIPE = "Nikon Creators/Bluegrain_jyota.NP3";
    private static final Set<String> UNKNOWN_FIELD_RECIPES = Set.of(
            "Nikon Creators/24Look_24Frames.NP3",
            "Nikon Creators/BlueHour_Luna.NP3",
            "Nikon Creators/GoldenHourGlow_DAnn.NP3",
            "Nikon Creators/HKReminisce_Hiuman.NP3",
            "Nikon Creators/PainterlyLight_DAnn.NP3",
            "Nikon Creators/Pastelpink_Yuki.NP3",
            "Nikon Creators/SunsetFilm_JKBoy.NP3",
            "Nikon Creators/WarmDimLight_Luna.NP3");
    private static final int EXPECTED_TOTAL = 142, EXPECTED_SUPPORTED = 133;
    private static int checks;

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Pass the existing nikon-cloud-recipes-142.zip path.");
        Path archive = Path.of(args[0]);
        require(Files.isRegularFile(archive), "Corpus ZIP is missing.");
        require(ARCHIVE_SHA256.equals(sha256(Files.readAllBytes(archive))),
                "Corpus identity changed: update the inventory and explicit expectations before accepting new samples.");
        int supported = 0, unsupported = 0, withCurve = 0, changed = 0, informationalFiles = 0;
        Set<String> seen = new HashSet<>(), rejected = new HashSet<>(), outputs = new HashSet<>();
        int[] colors = colorGrid(), originalColors = colors.clone();
        try (ZipFile zip = new ZipFile(archive.toFile(), StandardCharsets.UTF_8)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (!name.endsWith(".NP3")) {
                    require(name.endsWith(".txt") && entry.getSize() == 781, "Unexpected corpus member: " + name);
                    informationalFiles++;
                    continue;
                }
                require(seen.add(name), "Duplicate corpus member: " + name);
                byte[] bytes;
                try (InputStream input = zip.getInputStream(entry)) { bytes = input.readNBytes(1024 * 1024 + 1); }
                require(bytes.length == entry.getSize() && bytes.length <= 1024 * 1024,
                        name + ": unbounded or truncated ZIP member");
                byte[] originalBytes = bytes.clone();
                if (bytes.length == 978) withCurve++;
                try {
                    Np3PreviewProfile profile = Np3PreviewProfile.fromNp3(bytes);
                    require(!CREATIVE_RECIPE.equals(name) && !UNKNOWN_FIELD_RECIPES.contains(name),
                            name + ": unsupported Creative base / unknown non-neutral field must be explicitly rejected.");
                    require(!profile.registrationName().isBlank(), name + ": registration name is blank");
                    require(Np3PreviewProfile.MODEL_VERSION.equals(profile.modelVersion()), name + ": cache model version mismatch");
                    require(Arrays.equals(originalBytes, bytes), name + ": parser mutated source bytes");
                    int[] output = profile.render(colors, 64, 64);
                    require(output != colors, name + ": render reused input array");
                    require(Arrays.equals(originalColors, colors), name + ": render mutated source pixels");
                    require(!Arrays.equals(output, colors), name + ": real recipe has no effect on the colour grid");
                    require(Arrays.equals(output, profile.render(colors, 64, 64)), name + ": repeated render differs");
                    for (int i = 0; i < colors.length; i++) {
                        require((colors[i] >>> 24) == (output[i] >>> 24), name + ": alpha changed");
                        require(output[i] == profile.applyArgb(colors[i]), name + ": full-array and per-pixel render differ");
                    }
                    int outputSample = output[1234];
                    output[1234] ^= 0x00ffffff;
                    require(profile.render(colors, 64, 64)[1234] == outputSample, name + ": returned array aliases model state");
                    output[1234] = outputSample;
                    Arrays.fill(bytes, (byte) 0);
                    require(Arrays.equals(output, profile.render(colors, 64, 64)), name + ": model retained caller byte array");
                    for (int i = 0; i <= 256; i++) {
                        double tone = profile.mapTone(i / 256.0);
                        require(Double.isFinite(tone) && tone >= 0 && tone <= 1, name + ": tone leaves normalized bounds");
                    }
                    outputs.add(sha256Pixels(output));
                    supported++; changed++;
                } catch (IOException | IllegalArgumentException error) {
                    require(CREATIVE_RECIPE.equals(name) || UNKNOWN_FIELD_RECIPES.contains(name),
                            name + ": unexpected rejection: " + error.getMessage());
                    require(error.getMessage() != null && !error.getMessage().isBlank(), "Unsupported recipe needs an actionable reason.");
                    require(Arrays.equals(originalBytes, bytes), name + ": rejected parser mutated source bytes");
                    if (CREATIVE_RECIPE.equals(name)) {
                        require(((bytes[52] & 255) << 8 | (bytes[53] & 255)) == 0x0802,
                                "Allowlisted rejection no longer uses the researched Creative base.");
                        require(error.getMessage().contains("Flexible Color"), name + ": rejection does not identify the unsupported base");
                    } else {
                        int value = ((bytes[222] & 255) << 8) | (bytes[223] & 255);
                        require(value == 0x7f01 || value == 0x8101,
                                "Allowlisted rejection no longer uses a non-neutral unknown 0x1400 record.");
                        require(error.getMessage().contains("0x1400"), name + ": rejection does not identify the unknown field");
                    }
                    rejected.add(name);
                    System.out.println("UNSUPPORTED: " + name + " — " + error.getMessage());
                    unsupported++;
                }
            }
        }
        require(seen.size() == EXPECTED_TOTAL, "Expected all 142 original NP3 files.");
        require(supported == EXPECTED_SUPPORTED, "Expected 133 supported Flexible Color profiles, got " + supported);
        require(unsupported == 9 && rejected.contains(CREATIVE_RECIPE) && rejected.containsAll(UNKNOWN_FIELD_RECIPES),
                "Only the known Creative profile and eight non-neutral unknown-field profiles may be rejected.");
        require(informationalFiles == 1, "Expected the archive's original informational text file.");
        require(withCurve == 47, "Expected 47 custom-curve samples and 95 parameter-only samples.");
        require(changed == EXPECTED_SUPPORTED, "Every supported real recipe must affect the colour grid.");
        require(outputs.size() >= 100, "Expected substantial recipe variation; fewer than 100 distinct colour-grid outputs.");
        System.out.println("PASS: " + supported + " supported / " + unsupported + " explicitly unsupported / " + seen.size()
                + " NP3 recipes; " + withCurve + " custom curves; " + outputs.size() + " distinct outputs; " + checks + " checks.");
    }

    private static int[] colorGrid() {
        int[] colors = new int[4096];
        int i = 0;
        for (int r = 0; r < 16; r++) for (int g = 0; g < 16; g++) for (int b = 0; b < 16; b++) {
            colors[i] = ((i & 255) << 24) | ((r * 17) << 16) | ((g * 17) << 8) | (b * 17);
            i++;
        }
        return colors;
    }

    private static String sha256Pixels(int[] pixels) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int pixel : pixels) {
            digest.update((byte) (pixel >>> 24)); digest.update((byte) (pixel >>> 16));
            digest.update((byte) (pixel >>> 8)); digest.update((byte) pixel);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
}

package com.nikonpreset.usbprobe.album;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Real Java dates/cache/file checks with an injected EXIF attribute facade, not Android codec QA. */
public final class PhotoDateReaderTest {
    private static int checks;
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final long FALLBACK = stamp("2026-10-01T02:03:04Z");
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("photo-date-test-");
        try {
            strictDatesOffsetsAndDst();
            precedenceFallbackAndImmutability(root.resolve("fields"));
            canonicalCacheAndTimezoneReparse(root.resolve("cache"));
            cacheIsBoundedAndRecencyBased(root.resolve("lru"));
            cancellationAndChangedFileDoNotCommitStaleDates(root.resolve("cancel"));
            System.out.println("PASS: " + checks + " photo date reader checks");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) throw new AssertionError("cleanup escaped test root");
                    Files.deleteIfExists(path);
                }
            }
        }
    }
    private static void strictDatesOffsetsAndDst() {
        equal(stamp("2026-10-16T22:30:00Z"), PhotoDateReader.parse("2026:10:17 00:30:00", "+02:00", UTC));
        equal(stamp("2026-10-18T03:15:00Z"), PhotoDateReader.parse("2026:10:17 23:45:00", "-03:30", UTC));
        equal(stamp("2026-10-16T16:30:00Z"), PhotoDateReader.parse("2026:10:17 00:30:00", null, ZoneId.of("Asia/Shanghai")));
        equal(stamp("2024-02-29T12:00:00Z"), PhotoDateReader.parse("2024:02:29 12:00:00", "+00:00", UTC));
        for (String invalid : new String[]{"2026:02:29 12:00:00", "2026:04:31 12:00:00", "2026:00:01 00:00:00", "2026:10:17 24:00:00", "2026:10:17 12:60:00", "0000:01:01 00:00:00", "2026:1:01 00:00:00", "2026-10-17 12:00:00", "2026:10:17 12:00:00extra", "0000:00:00 00:00:00", ""}) equal(0L, PhotoDateReader.parse(invalid, null, UTC));
        for (String invalid : new String[]{"Z", "+25:00", "+18:30", "+08:99", "0800", "+8:00", "+08:00junk"}) equal(0L, PhotoDateReader.parse("2026:10:17 12:00:00", invalid, UTC));
        equal(0L, PhotoDateReader.parse("2026:03:08 02:30:00", null, ZoneId.of("America/New_York")));
        equal(stamp("2026-03-08T07:30:00Z"), PhotoDateReader.parse("2026:03:08 02:30:00", "-05:00", UTC));
        equal(stamp("2026-11-01T05:30:00Z"), PhotoDateReader.parse("2026:11:01 01:30:00", null, ZoneId.of("America/New_York")));
        equal(0L, PhotoDateReader.parse(null, null, UTC));
    }
    private static void precedenceFallbackAndImmutability(Path root) throws Exception {
        DownloadedPhoto photo = fixture(root, "one.jpg"); byte[] unchanged = Files.readAllBytes(photo.file().toPath());
        Map<String, String> tags = Map.of("DateTimeOriginal", "2026:10:17 12:00:00", "OffsetTimeOriginal", "+08:00", "DateTimeDigitized", "2026:10:16 12:00:00", "DateTime", "2026:10:15 12:00:00");
        List<DownloadedPhoto> result = reader(tags).read(List.of(photo), () -> false, UTC);
        equal(stamp("2026-10-17T04:00:00Z"), result.get(0).photoDate()); equal(FALLBACK, photo.photoDate());
        yes(result.get(0) != photo); yes(result.get(0).file().equals(photo.file())); equal(photo.filename(), result.get(0).filename());
        equal("project-id", result.get(0).projectId()); equal("Project", result.get(0).projectName()); equal(FALLBACK, photo.file().lastModified());
        yes(Arrays.equals(unchanged, Files.readAllBytes(photo.file().toPath())));
        equal(stamp("2026-10-16T12:00:00Z"), reader(Map.of("DateTimeOriginal", "bad", "DateTimeDigitized", "2026:10:16 12:00:00", "DateTime", "2026:10:15 12:00:00")).read(List.of(photo), () -> false, UTC).get(0).photoDate());
        equal(stamp("2026-10-16T12:00:00Z"), reader(Map.of("DateTimeOriginal", "2026:10:17 12:00:00", "OffsetTimeOriginal", "+99:00", "DateTimeDigitized", "2026:10:16 12:00:00")).read(List.of(photo), () -> false, UTC).get(0).photoDate());
        equal(stamp("2026-10-15T10:00:00Z"), reader(Map.of("DateTime", "2026:10:15 12:00:00", "OffsetTime", "+02:00")).read(List.of(photo), () -> false, UTC).get(0).photoDate());
        equal(stamp("2026-10-16T15:30:00Z"), reader(Map.of("DateTimeDigitized", "2026:10:16 12:00:00", "OffsetTimeDigitized", "-03:30")).read(List.of(photo), () -> false, UTC).get(0).photoDate());
        equal(stamp("2026-10-17T12:00:00Z"), new PhotoDateReader(file -> tag -> {
            if (tag.startsWith("Offset")) throw new IllegalArgumentException("unsupported tag on an older platform");
            return tag.equals("DateTimeOriginal") ? "2026:10:17 12:00:00" : null;
        }).read(List.of(photo), () -> false, UTC).get(0).photoDate());
        equal(FALLBACK, reader(Map.of("DateTimeOriginal", "2026:02:30 12:00:00")).read(List.of(photo), () -> false, UTC).get(0).photoDate());
        equal(FALLBACK, new PhotoDateReader(file -> { throw new IOException("damaged EXIF"); }).read(List.of(photo), () -> false, UTC).get(0).photoDate());
        equal(FALLBACK, reader(Map.of()).read(List.of(photo), () -> false, UTC).get(0).photoDate());
        Files.setLastModifiedTime(photo.file().toPath(), FileTime.fromMillis(0));
        equal(FALLBACK, photo.photoDate());
        equal(0L, reader(Map.of()).read(List.of(photo), () -> false, UTC).get(0).photoDate());
        try { result.clear(); throw new AssertionError("mutable result"); } catch (UnsupportedOperationException expected) { checks++; }
    }
    private static void canonicalCacheAndTimezoneReparse(Path root) throws Exception {
        DownloadedPhoto photo = fixture(root, "cache.jpg"); AtomicInteger reads = new AtomicInteger();
        PhotoDateReader reader = new PhotoDateReader(file -> { reads.incrementAndGet(); return tag -> tag.equals("DateTimeOriginal") ? "2026:10:17 00:30:00" : null; });
        equal(stamp("2026-10-17T00:30:00Z"), reader.read(List.of(photo), () -> false, UTC).get(0).photoDate());
        equal(stamp("2026-10-16T16:30:00Z"), reader.read(List.of(photo), () -> false, ZoneId.of("Asia/Shanghai")).get(0).photoDate()); equal(1, reads.get());
        Files.createDirectories(root.resolve("alias")); DownloadedPhoto alias = new DownloadedPhoto(root.resolve("alias/../cache.jpg").toFile(), "alias");
        reader.read(List.of(alias), () -> false, UTC); equal(1, reads.get());
        Files.write(photo.file().toPath(), new byte[]{1, 2, 3, 4}); Files.setLastModifiedTime(photo.file().toPath(), FileTime.fromMillis(FALLBACK));
        reader.read(List.of(photo), () -> false, UTC); equal(2, reads.get());
        Files.setLastModifiedTime(photo.file().toPath(), FileTime.fromMillis(FALLBACK + 2000)); reader.read(List.of(photo), () -> false, UTC); equal(3, reads.get());
    }
    private static void cacheIsBoundedAndRecencyBased(Path root) throws Exception {
        AtomicInteger reads = new AtomicInteger(); PhotoDateReader reader = new PhotoDateReader(file -> { reads.incrementAndGet(); return tag -> null; });
        List<DownloadedPhoto> photos = new ArrayList<>(); for (int i = 0; i < 4096; i++) photos.add(fixture(root, i + ".jpg"));
        reader.read(photos, () -> false, UTC); equal(4096, reads.get());
        reader.read(List.of(photos.get(0)), () -> false, UTC); equal(4096, reads.get());
        reader.read(List.of(fixture(root, "new.jpg")), () -> false, UTC); equal(4097, reads.get());
        reader.read(List.of(photos.get(0)), () -> false, UTC); equal(4097, reads.get());
        reader.read(List.of(photos.get(1)), () -> false, UTC); equal(4098, reads.get());
    }
    private static void cancellationAndChangedFileDoNotCommitStaleDates(Path root) throws Exception {
        List<DownloadedPhoto> photos = List.of(fixture(root, "a.jpg"), fixture(root, "b.jpg"), fixture(root, "c.jpg"));
        AtomicInteger reads = new AtomicInteger(); PhotoDateReader reader = new PhotoDateReader(file -> { reads.incrementAndGet(); return tag -> null; });
        cancelled(() -> reader.read(photos, () -> true, UTC)); equal(0, reads.get());
        cancelled(() -> reader.read(photos, () -> reads.get() >= 2, UTC)); equal(2, reads.get()); equal(FALLBACK, photos.get(0).photoDate());
        Thread.currentThread().interrupt(); try { cancelled(() -> reader.read(List.of(), () -> false, UTC)); } finally { Thread.interrupted(); }
        cancelled(() -> new PhotoDateReader(file -> { throw new InterruptedIOException("stop"); }).read(photos, () -> false, UTC));
        AtomicInteger changes = new AtomicInteger(); PhotoDateReader changing = new PhotoDateReader(file -> {
            if (changes.incrementAndGet() == 1) { Files.write(file.toPath(), new byte[]{9, 8, 7, 6}); Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(FALLBACK + 5000)); }
            return tag -> tag.equals("DateTimeOriginal") ? "2026:10:17 12:00:00" : null;
        });
        equal(FALLBACK + 5000, changing.read(List.of(photos.get(0)), () -> false, UTC).get(0).photoDate());
        equal(stamp("2026-10-17T12:00:00Z"), changing.read(List.of(photos.get(0)), () -> false, UTC).get(0).photoDate()); equal(2, changes.get());
    }
    private static PhotoDateReader reader(Map<String, String> tags) { return new PhotoDateReader(file -> tags::get); }
    private static DownloadedPhoto fixture(Path root, String name) throws IOException {
        Files.createDirectories(root); File file = root.resolve(name).toFile(); Files.write(file.toPath(), new byte[]{1, 2, 3});
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(FALLBACK)); return new DownloadedPhoto(file, name, "project-id", "Project");
    }
    private static long stamp(String value) { return Instant.parse(value).toEpochMilli(); }
    private interface Checked { void run() throws Exception; }
    private static void cancelled(Checked action) throws Exception {
        checks++; try { action.run(); } catch (InterruptedIOException expected) { return; } throw new AssertionError("expected cancellation");
    }
    private static void equal(Object expected, Object actual) { checks++; if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual); }
    private static void yes(boolean value) { checks++; if (!value) throw new AssertionError("expected true"); }
}

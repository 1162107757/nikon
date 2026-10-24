package cc.huevia.exportqa;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.*;
import android.media.ExifInterface;
import android.os.*;
import cc.huevia.album.*;
import cc.huevia.ncp.*;
import java.io.*;
import java.nio.file.Files;
import java.security.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Isolated synthetic fixtures and real Android codecs. Never opens camera, network, MediaStore or user files. */
public final class PhotoExportProbe extends Instrumentation {
    private final JSONArray assertions = new JSONArray();
    private final JSONObject report = new JSONObject();
    private int checks;
    private File root;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"), "emulator-only");
            Context context = getTargetContext();
            report.put("apkSha256", sha(new File(context.getApplicationInfo().sourceDir)));
            report.put("versionCode", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode());
            root = new File(context.getCacheDir(), "export-qa-" + UUID.randomUUID());
            check(root.mkdir(), "private-unique-scratch-created");
            PhotoPresetRenderer renderer = new PhotoPresetRenderer(context.getAssets());
            PresetPreviewProfile neutral = AdjustedPreviewProfile.of(null, PhotoAdjustments.NONE);
            File original = fixture("landscape", 3073, 2051, 1);
            String originalSha = sha(original);
            PhotoBorder border = PhotoBorder.NONE.withTemplate(3).withCamera(false, "NIKON", "NIKON Z 8");
            PresetPreviewProfile framed = BorderPreviewProfile.of(neutral, border);
            PhotoPresetRenderer.Dimensions natural = PhotoPresetRenderer.dimensions(original, framed);
            int[] expectedFull = border.dimensions(3073, 2051);
            check(natural.width == expectedFull[0] && natural.height == expectedFull[1], "natural-size-includes-final-frame");
            File share = new File(root, "share.JPG");
            renderer.render(original, share, framed, PhotoExportSpec.share(), () -> false, null);
            PhotoExportSpec.Resolved expectedShare = PhotoExportSpec.share().resolve(natural.width, natural.height);
            checkBounds(share, expectedShare.width, expectedShare.height, "share-final-2048-framed");
            metadata(share, expectedShare.width, expectedShare.height, true);
            File equivalent = new File(root, "custom-equivalent.JPG");
            renderer.render(original, equivalent, framed, PhotoExportSpec.custom(2048, 90), () -> false, null);
            check(sha(share).equals(sha(equivalent)), "share-custom-identical-actual-spec-identical-encoded-bytes");
            check(PhotoPresetRenderer.modelVersion(framed, expectedShare).equals(PhotoPresetRenderer.modelVersion(framed, PhotoExportSpec.custom(2048, 90).resolve(natural.width, natural.height))), "share-custom-cache-model-equal");
            File qualitySource = fixture("quality-source", 769, 513, 1);
            long lastQuantization = Long.MAX_VALUE;
            for (int quality : new int[]{70, 90, 95, 100}) {
                File encoded = new File(root, "quality-" + quality + ".JPG");
                renderer.render(qualitySource, encoded, neutral, PhotoExportSpec.custom(640, quality), () -> false, null);
                PhotoExportSpec.Resolved actual = PhotoExportSpec.custom(640, quality).resolve(769, 513);
                checkBounds(encoded, actual.width, actual.height, "custom-quality-" + quality);
                metadata(encoded, actual.width, actual.height, true);
                long quantization = quantization(encoded);
                check(quantization < lastQuantization, "actual-jpeg-quantization-improves-Q" + quality);
                lastQuantization = quantization;
            }
            File small = fixture("small", 513, 389, 1);
            File smallCustom = new File(root, "small-custom-ceiling.JPG");
            renderer.render(small, smallCustom, neutral, PhotoExportSpec.custom(640, 90), () -> false, null);
            checkBounds(smallCustom, 513, 389, "batch-custom-ceiling-small-source-does-not-upscale");
            File smallShare = new File(root, "small-share.JPG"); renderer.render(small, smallShare, neutral, PhotoExportSpec.share(), () -> false, null);
            checkBounds(smallShare, 513, 389, "share-small-does-not-enlarge");
            File huge = fixture("long-edge-over-8192", 8201, 8, 1), hugeResult = new File(root, "large-full.JPG");
            renderer.render(huge, hugeResult, neutral, PhotoExportSpec.full(), () -> false, null);
            checkBounds(hugeResult, 8201, 8, "full-size-does-not-cap-8192");
            check(PhotoPresetRenderer.modelVersion(neutral).equals(PhotoPresetRenderer.modelVersion(neutral, PhotoExportSpec.full().resolve(8201, 8))), "legacy-full95-model-kept");
            for (int exif = 1; exif <= 8; exif++) {
                File input = fixture("orientation-" + exif, 513, 389, exif), output = new File(root, "orientation-" + exif + "-share.JPG");
                renderer.render(input, output, neutral, PhotoExportSpec.share(), () -> false, null);
                int width = exif >= 5 ? 389 : 513, height = exif >= 5 ? 513 : 389;
                checkBounds(output, width, height, "all-exif-actual-oriented-pixels-" + exif);
                metadata(output, width, height, true);
            }
            PhotoGeometry geometry = new PhotoGeometry(1, 73, 100000, 70000, 900000, 920000);
            PhotoAdjustments manual = PhotoAdjustments.NONE.with(PhotoAdjustments.Parameter.CLARITY, 100).with(PhotoAdjustments.Parameter.SHARPENING, 100).with(PhotoAdjustments.Parameter.EXPOSURE, 7);
            PresetPreviewProfile detailProfile = BorderPreviewProfile.of(GeometryPreviewProfile.of(AdjustedPreviewProfile.of(null, manual), geometry), border);
            File detailCurrent = new File(root, "detail-current.png"), detailBefore = new File(root, "detail-before.png");
            PhotoPresetRenderer.DetailResult current = renderer.renderDetail(small, detailCurrent, detailProfile, false, () -> false, null);
            PhotoPresetRenderer.DetailResult before = renderer.renderDetail(small, detailBefore, detailProfile, true, () -> false, null);
            PhotoGeometry.Plan plan = geometry.plan(513, 389, 1);
            check(current.width == plan.width && current.height == plan.height && before.width == current.width && before.height == current.height, "detail-full-composed-size-same-for-both-no-frame");
            Bitmap source = decode(small); int[] pixels = new int[513 * 389]; source.getPixels(pixels, 0, 513, 0, 0, 513, 389);
            int[] adjusted = detailProfile.render(pixels, 513, 389);
            Bitmap transformed = Bitmap.createBitmap(adjusted, 513, 389, Bitmap.Config.ARGB_8888); transformed.setDensity(Bitmap.DENSITY_NONE);
            Bitmap expectedCurrent = PhotoComposition.apply(transformed, geometry, 1), expectedBefore = PhotoComposition.apply(source, geometry, 1);
            exactPixels(detailCurrent, expectedCurrent, "detail-current-matches-entire-pre-JPEG-render-all-pixels");
            exactPixels(detailBefore, expectedBefore, "detail-before-matches-full-source-same-composition-all-pixels");
            if (expectedCurrent != transformed) expectedCurrent.recycle(); transformed.recycle();
            if (expectedBefore != source) expectedBefore.recycle(); source.recycle();
            check(sha(original).equals(originalSha), "original-byte-sha-preserved-after-all-processing");
            boolean cancelled = false; File cancelledTarget = new File(root, "cancelled.JPG");
            try { renderer.render(original, cancelledTarget, framed, PhotoExportSpec.share(), () -> true, null); } catch (IOException expected) { cancelled = true; }
            check(cancelled && !cancelledTarget.exists(), "cancel-before-decode-produces-no-file");
            orphanBatch(context, small, renderer, neutral);
        } catch (Throwable failure) {
            try { report.put("status", "FAIL").put("failure", failure.toString()); } catch (Exception ignored) { }
        } finally {
            try {
                if (!report.has("status")) report.put("status", "PASS");
                if (root != null) delete(root);
                report.put("scratchRemoved", root == null || !root.exists());
                report.put("checks", checks).put("assertions", assertions).put("scope", "Real Android JPEG/PNG/EXIF on synthetic private fixtures; no MediaStore, physical phone, camera or UI claim.");
                File output = new File(getTargetContext().getExternalFilesDir(null), "photo-export-qa"); output.mkdirs();
                Files.writeString(new File(output, "report.json").toPath(), report.toString(2));
                result.putString("report", report.toString());
            } catch (Throwable failure) { result.putString("error", failure.toString()); }
            finish("PASS".equals(report.optString("status")) ? -1 : 0, result);
        }
    }
    private void orphanBatch(Context context, File validJpeg, PhotoPresetRenderer renderer, PresetPreviewProfile neutral) throws Exception {
        // The production batch receives real Android storage paths rooted entirely in this run's
        // UUID. No source/output/ledger is created in the user's actual app album or preferences.
        File isolated = new File(root, "isolated-batch"); check(isolated.mkdir(), "orphan-batch-owned-scratch-created");
        Context scoped = new ContextWrapper(context) {
            private File directory(String name) { File file = new File(isolated, name); if (!file.isDirectory() && !file.mkdirs()) throw new IllegalStateException("QA scoped directory unavailable"); return file; }
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return directory("files"); }
            @Override public File getCacheDir() { return directory("cache"); }
            @Override public File getExternalFilesDir(String type) { return directory("external/" + (type == null ? "root" : type)); }
        };
        File albumDirectory = new File(scoped.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "camera-album");
        check(albumDirectory.mkdir(), "orphan-production-owned-directory-created");
        String key = PhotoEditStore.hash(UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        File source = new File(albumDirectory, "effect-" + key + "__Effect.JPG"); Files.copy(validJpeg.toPath(), source.toPath());
        File completedRecord = new File(new File(scoped.getFilesDir(), "photo-preset-results"), key + ".record");
        check(!completedRecord.exists(), "orphan-has-no-completion-record"); String sourceSha = sha(source);
        report.put("orphanSource", source.getCanonicalPath()).put("orphanSourceSha256", sourceSha).put("isolatedBatchRoot", isolated.getCanonicalPath());
        AlbumController album = new AlbumController(scoped);
        try {
            PhotoPresetBatch batch = new PhotoPresetBatch(scoped, album);
            PhotoPresetBatch.Edit edit = PhotoPresetBatch.Edit.saved(PhotoPresetBatch.Source.local(photo(source,"Historical.JPG")), PhotoExportSpec.full(), sourceSha).withAccount("").withDestination(PhotoSaveTarget.APP_ONLY,false);
            check(batch.findResult(edit) == null, "native-orphan-lookup-does-not-assume-existing-q95");
            PhotoPresetBatchState first = runBatch(batch, edit);
            check(first.completed == 1 && first.failed == 0 && first.reused == 0 && first.results.size() == 1, "native-valid-orphan-full95-completes-as-new-result");
            DownloadedPhoto output = first.results.get(0); String resultSha = sha(output.file());
            check(!output.file().getCanonicalFile().equals(source.getCanonicalFile()) && !resultSha.equals(sourceSha), "native-orphan-new-path-and-new-encoded-bytes");
            checkBounds(output.file(), 513, 389, "native-orphan-full-size-encoded");
            File reference = new File(root, "orphan-verified-q95-reference.JPG"); renderer.render(validJpeg, reference, neutral, PhotoExportSpec.full(), () -> false, null);
            check(quantization(output.file()) == quantization(reference) && quantization(output.file()) > quantization(source), "native-orphan-output-real-q95-dqt-not-q100-source-copy");
            check(sha(source).equals(sourceSha), "native-orphan-source-bytes-preserved-after-encoding");
            check(batch.findResult(edit).file().equals(output.file()), "native-orphan-lookup-finds-new-verified-encoding");
            PhotoPresetBatchState second = runBatch(batch, edit);
            check(second.completed == 1 && second.failed == 0 && second.reused == 1 && second.results.get(0).file().equals(output.file()) && sha(output.file()).equals(resultSha), "native-orphan-second-export-reuses-exact-encoded-result");

            String badKey = PhotoEditStore.hash((UUID.randomUUID() + "invalid").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            File invalid = new File(albumDirectory, "effect-" + badKey + "__Effect.JPG"); Files.write(invalid.toPath(), new byte[]{(byte)255,(byte)216,1,2,3});
            String invalidSha = sha(invalid);
            PhotoPresetBatch.Edit broken = PhotoPresetBatch.Edit.saved(PhotoPresetBatch.Source.local(photo(invalid,"Damaged.JPG")), PhotoExportSpec.full(), invalidSha).withAccount("").withDestination(PhotoSaveTarget.APP_ONLY,false);
            boolean rejected = false; try { batch.findResult(broken); } catch(IOException expected) { rejected = true; }
            check(rejected, "native-corrupt-orphan-lookup-rejected");
            PhotoPresetBatchState failure = runBatch(batch, broken);
            check(failure.failed == 1 && failure.completed == 0 && failure.results.isEmpty() && failure.items.get(0).status.equals("failed") && sha(invalid).equals(invalidSha), "native-corrupt-orphan-export-fails-with-source-intact");
            check(sha(source).equals(sourceSha), "native-orphan-source-bytes-preserved-after-reuse-and-invalid-input");
        } finally { album.close(); }
    }
    private static DownloadedPhoto photo(File file, String name) throws Exception {
        java.lang.reflect.Constructor<DownloadedPhoto> constructor=DownloadedPhoto.class.getDeclaredConstructor(File.class,String.class);
        constructor.setAccessible(true);return constructor.newInstance(file,name);
    }
    private PhotoPresetBatchState runBatch(PhotoPresetBatch batch, PhotoPresetBatch.Edit edit) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<PhotoPresetBatchState> result = new AtomicReference<>(); AtomicReference<String> error = new AtomicReference<>();
        batch.startEdits(Collections.singletonList(edit), PhotoSaveTarget.APP_ONLY, false,
            new AlbumController.Callback<Void>() { public void onSuccess(Void value) { } public void onError(String message) { error.set(message); done.countDown(); } },
            new AlbumController.Callback<PhotoPresetBatchState>() { public void onSuccess(PhotoPresetBatchState value) { result.set(value); done.countDown(); } public void onError(String message) { error.set(message); done.countDown(); } },
            () -> "");
        check(done.await(60, TimeUnit.SECONDS) && error.get() == null && result.get() != null, "native-batch-terminal-callback-" + error.get());
        return result.get();
    }
    private Bitmap decode(File file) { BitmapFactory.Options o = new BitmapFactory.Options(); o.inSampleSize = 1; o.inScaled = false; o.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB); Bitmap b = BitmapFactory.decodeFile(file.getPath(), o); b.setDensity(Bitmap.DENSITY_NONE); return b; }
    private void exactPixels(File file, Bitmap expected, String label) throws Exception {
        Bitmap actual = decode(file);
        check(actual.getWidth() == expected.getWidth() && actual.getHeight() == expected.getHeight(), label + "-dimensions");
        int width = actual.getWidth(); int[] a = new int[width], b = new int[width]; boolean same = true;
        for (int y = 0; y < actual.getHeight(); y++) { actual.getPixels(a, 0, width, 0, y, width, 1); expected.getPixels(b, 0, width, 0, y, width, 1); if (!Arrays.equals(a, b)) { same = false; break; } }
        actual.recycle(); check(same, label);
    }
    private static long quantization(File file) throws IOException {
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            if (input.readUnsignedShort() != 0xffd8) throw new IOException("JPEG fixture missing SOI"); long total = 0;
            while (true) { int b; do { b = input.readUnsignedByte(); } while (b != 255); do { b = input.readUnsignedByte(); } while (b == 255);
                if (b == 0xda || b == 0xd9) return total;
                int length = input.readUnsignedShort() - 2;
                if (b == 0xdb) { while (length > 0) { int type = input.readUnsignedByte(); length--; for (int n = 0; n < 64; n++) { total += (type >>> 4) == 0 ? input.readUnsignedByte() : input.readUnsignedShort(); length -= (type >>> 4) == 0 ? 1 : 2; } } }
                else { byte[] skip = new byte[length]; input.readFully(skip); }
            }
        }
    }
    private File fixture(String name, int width, int height, int orientation) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); bitmap.setDensity(Bitmap.DENSITY_NONE);
        int[] row = new int[width]; int random = 0x1633;
        for (int y = 0; y < height; y++) { for (int x = 0; x < width; x++) { random ^= random << 13; random ^= random >>> 17; random ^= random << 5; int v = ((x ^ y) & 1) == 0 ? 220 : 30; row[x] = 0xff000000 | ((random & 63) + v / 2) << 16 | (((x * 37 + y * 11) & 255) << 8) | (random & 255); } bitmap.setPixels(row, 0, width, 0, y, width, 1); }
        File file = new File(root, name + ".JPG");
        try (FileOutputStream stream = new FileOutputStream(file)) { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream), "fixture-encoded-" + name); } finally { bitmap.recycle(); }
        ExifInterface exif = new ExifInterface(file.getPath());
        exif.setAttribute("Orientation", String.valueOf(orientation)); exif.setAttribute("Make", "NIKON"); exif.setAttribute("Model", "NIKON Z 8");
        exif.setAttribute("DateTimeOriginal", "2026:10:23 12:34:56"); exif.setAttribute("OffsetTimeOriginal", "+08:00");
        exif.setAttribute("ExposureTime", "0.0015625"); exif.setAttribute("FNumber", "2.8");
        exif.setAttribute("GPSLatitude", "31/1,12/1,1/1"); exif.setAttribute("GPSLatitudeRef", "N");
        exif.setAttribute("GPSLongitude", "121/1,30/1,1/1"); exif.setAttribute("GPSLongitudeRef", "E"); exif.saveAttributes();
        double frameworkExposure = new ExifInterface(file.getPath()).getAttributeDouble("ExposureTime", 0);
        patchExactFixtureExposure(file);
        double exactExposure = new ExifInterface(file.getPath()).getAttributeDouble("ExposureTime", 0);
        JSONArray fixtures=report.optJSONArray("fixtureExif");if(fixtures==null){fixtures=new JSONArray();report.put("fixtureExif",fixtures);}fixtures.put(new JSONObject().put("fixture",name).put("frameworkExposure",frameworkExposure).put("exactPatchedExposure",exactExposure));
        check(Math.abs(exactExposure - 1.0/640) < 1e-12,"fixture-exact-rational-before-render-"+name);
        return file;
    }
    /** Framework writer itself quantizes ExposureTime. Patch only our known synthetic TIFF to exact camera-style 1/640. */
    private static void patchExactFixtureExposure(File file)throws Exception {
        byte[] bytes=Files.readAllBytes(file.toPath());int start=2;
        while(start+4<bytes.length){int marker=bytes[start+1]&255,length=((bytes[start+2]&255)<<8)|(bytes[start+3]&255);if(marker==0xda)break;
            if(marker==0xe1&&bytes[start+4]=='E'&&bytes[start+5]=='x'){
                int base=start+10;java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(bytes).order(bytes[base]=='I'?java.nio.ByteOrder.LITTLE_ENDIAN:java.nio.ByteOrder.BIG_ENDIAN);
                int directory=base+buffer.getInt(base+4),exif=0;
                for(int i=0;i<(buffer.getShort(directory)&65535);i++){int at=directory+2+i*12;if((buffer.getShort(at)&65535)==0x8769)exif=base+buffer.getInt(at+8);}
                if(exif>0)for(int i=0;i<(buffer.getShort(exif)&65535);i++){int at=exif+2+i*12;if((buffer.getShort(at)&65535)==0x829a){int rational=base+buffer.getInt(at+8);buffer.putInt(rational,1);buffer.putInt(rational+4,640);Files.write(file.toPath(),bytes);return;}}
            }if(length<2)break;start+=length+2;
        }throw new AssertionError("Synthetic fixture has no exposure rational");
    }
    private void metadata(File file, int width, int height, boolean normalized) throws Exception {
        ExifInterface exif = new ExifInterface(file.getPath());
        check(exif.getAttribute("GPSLatitude") == null && exif.getAttribute("GPSLongitude") == null, "gps-stripped-" + file.getName());
        check("NIKON Z 8".equals(exif.getAttribute("Model")) && "2026:10:23 12:34:56".equals(exif.getAttribute("DateTimeOriginal")), "capture-preserved-" + file.getName());
        check(Math.abs(exif.getAttributeDouble("ExposureTime", 0) - 1.0 / 640) < 1e-12, "exact-rational-shutter-" + file.getName());
        check(exif.getAttributeInt("PixelXDimension", -1) == width && exif.getAttributeInt("PixelYDimension", -1) == height, "exif-pixel-dimensions-" + file.getName());
        check(exif.getAttributeInt("ColorSpace", -1) == 1, "exif-srgb-" + file.getName());
        if (normalized) check(exif.getAttributeInt("Orientation", -1) == 1, "exif-orientation-normalized-" + file.getName());
    }
    private void checkBounds(File file, int width, int height, String label) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeFile(file.getPath(), bounds);
        check(bounds.outWidth == width && bounds.outHeight == height, label + ":" + bounds.outWidth + "x" + bounds.outHeight);
        BitmapFactory.Options actual = new BitmapFactory.Options(); actual.inScaled = false; actual.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), actual);
        check(bitmap != null && bitmap.getWidth() == width && bitmap.getHeight() == height && ColorSpace.get(ColorSpace.Named.SRGB).equals(bitmap.getColorSpace()), "full-decode-srgb-" + label); if (bitmap != null) bitmap.recycle();
    }
    private void check(boolean passed, String label) throws Exception { checks++; assertions.put(new JSONObject().put("name", label).put("passed", passed)); if (!passed) throw new AssertionError(label); }
    private static String sha(File file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream input = new FileInputStream(file)) { byte[] b = new byte[65536]; int n; while ((n = input.read(b)) != -1) digest.update(b, 0, n); } StringBuilder out = new StringBuilder(); for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString(); }
    private static void delete(File file) throws IOException { File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); if (!file.delete() && file.exists()) throw new IOException("Own scratch cleanup failed: " + file); }
}

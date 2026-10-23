package cc.huevia.album;

import android.content.Context;
import cc.huevia.library.PresetLibrary;
import cc.huevia.library.PresetRecord;
import cc.huevia.ncp.NcpCompatibilityFixtures;
import cc.huevia.ncp.NcpPreviewProfile;
import cc.huevia.ncp.PresetPreviewProfile;
import cc.huevia.ncp.PresetPreviewProfiles;
import cc.huevia.ptp.PtpObjectInfo;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Tests pixel-strip policy plus real batch control/ledger with explicit host acquisition/publication facades.
 * Does not test Android bitmap codecs, EXIF implementation, camera framing, MediaStore or lifecycle. */
public final class PhotoPresetBatchTest {
    private static int checks;
    private static final byte[] JPEG = {(byte) 255, (byte) 216, 10, 20, 30, 40, (byte) 255, (byte) 217};
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("photo-preset-batch-");
        try {
            fullResolutionStripsAndCancellation();
            rendererProgressIncludesEncodingAndVerification(root.resolve("renderer-progress"));
            batchProgressHasPerPhotoOwnershipAndThrottles(root.resolve("batch-progress"));
            np3RenderingReuseOriginalsAndCancellation(root.resolve("np3"));
            localReuseAndPublicationRetryPreserveSources(root.resolve("local"));
            legacyPublicLookupRunsWithoutCameraOrBatch(root.resolve("legacy"));
            deletedInputsAndResultsDoNotRevive(root.resolve("deleted"));
            cancelledRemoteDrainsAndNeverFetchesLocalAgain(root.resolve("remote"));
            disconnectAndLateCallbacksCannotReviveBatch(root.resolve("disconnect"));
            effectInputsAlwaysUseRootAndReuse(root.resolve("root-effects"));
            mixedAliasesMergeOnceIncludingPublicationFailure(root.resolve("mixed"));
            invalidEffectInputsNeverStack(root.resolve("invalid-effects"));
            cancelledRootResolutionAndDeletedEffectRecovery(root.resolve("cancel-root"));
            crossDirectoryOriginalAliasesReuseOneEffect(root.resolve("cross-directory"));
            individualDraftExports(root.resolve("individual-edits"));
            explicitDestinationsAndFrozenRetry(root.resolve("save-destinations"));
            newCopyRetryAfterDraftWriteFailureDoesNotPublishAgain(root.resolve("post-publication-retry"));
            System.out.println("PASS: " + checks + " photo-preset batch checks");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static void rendererProgressIncludesEncodingAndVerification(Path root) throws Exception {
        Files.createDirectories(root); File source = root.resolve("source.jpg").toFile(), target = root.resolve("target.part").toFile();
        Files.write(source.toPath(), JPEG); PresetPreviewProfile profile = PresetPreviewProfiles.fromPreset(NcpCompatibilityFixtures.ncp());
        List<Double> values = new ArrayList<>();
        android.graphics.Bitmap.beforeCompress = () -> check(Math.abs(values.get(values.size() - 1) - .80) < .000001,
                "Pixel completion reports 80%, leaving encoding, metadata and verification outstanding");
        try {
            new PhotoPresetRenderer().render(source, target, profile, () -> false, values::add);
            check(values.get(0) == 0 && values.get(values.size() - 1) == 1, "A verified render emits a complete 0..1 work range");
            double previous = -1;
            for (double value : values) { check(Double.isFinite(value) && value >= previous && value >= 0 && value <= 1, "Renderer progress remains bounded and monotonic"); previous = value; }
            check(values.contains(.10) && values.contains(.93) && values.contains(.97), "Decode, encode and metadata completion have real progress checkpoints");
            values.clear(); android.graphics.Bitmap.failCompress = true;
            try { new PhotoPresetRenderer().render(source, target, profile, () -> false, values::add); throw new AssertionError("encoding failure ignored"); }
            catch (IOException expected) { check(!values.contains(1.0) && Math.abs(values.get(values.size() - 1) - .80) < .000001, "Encoding failure never reports a verified 100% render"); }
            android.graphics.Bitmap.failCompress = false; values.clear(); AtomicBoolean cancelled = new AtomicBoolean();
            try { new PhotoPresetRenderer().render(source, target, profile, cancelled::get, value -> { values.add(value); if (value >= .79) cancelled.set(true); }); throw new AssertionError("render cancellation ignored"); }
            catch (PhotoPresetRenderer.Cancelled expected) { check(!values.contains(1.0), "A cancelled render does not claim completed output"); }
            check(Arrays.equals(Files.readAllBytes(source.toPath()), JPEG), "Progress, encoding failure and cancellation preserve original bytes");
        } finally { android.graphics.Bitmap.beforeCompress = null; android.graphics.Bitmap.failCompress = false; }
    }

    private static void batchProgressHasPerPhotoOwnershipAndThrottles(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto first = f.original("FIRST.JPG"), second = f.original("SECOND.JPG");
        byte[] other = JPEG.clone(); other[3]++; Files.write(second.file().toPath(), other);
        List<PhotoPresetBatchState> values = new java.util.concurrent.CopyOnWriteArrayList<>(); Result<PhotoPresetBatchState> finished = new Result<>();
        android.graphics.Bitmap.beforeCompress = () -> {
            PhotoPresetBatchState value = f.batch.state();
            check(value.active && value.currentProgress < 1 && value.progress < 1, "Batch completion waits for encoding, atomic commit and gallery publication");
        };
        try {
            f.batch.startTracked(List.of(PhotoPresetBatch.Source.local(first), PhotoPresetBatch.Source.local(second)), f.preset.id, new Result<>(), finished, values::add);
            PhotoPresetBatchState terminal = finished.await();
            check(!terminal.active && terminal.progress == 1 && terminal.completed == 2, "A successful two-photo job reaches 100% only after both results complete");
            double overall = -1, current = -1; int number = 0;
            for (PhotoPresetBatchState value : values) {
                check(value.progress >= overall && value.progress >= 0 && value.progress <= 1, "Overall progress is bounded and never moves backwards"); overall = value.progress;
                if (!value.active) continue;
                check(value.currentNumber >= number && value.currentNumber <= 2, "Current input number tracks its own batch position");
                if (number != value.currentNumber) current = -1;
                check(value.currentProgress >= current && value.currentProgress <= 1, "A photo's progress is monotonic until the next input");
                check(!value.currentStage.isEmpty(), "Every active snapshot includes a readable work stage");
                current = value.currentProgress; number = value.currentNumber;
                if (value.progress == 1) check(value.completed + value.failed == value.total, "100% means all inputs have reached a terminal outcome");
            }
            check(number == 2 && values.stream().anyMatch(value -> value.currentStage.equals("保存到相册")), "Observers see both inputs and the real gallery-save boundary");
        } finally { android.graphics.Bitmap.beforeCompress = null; }

        PhotoItem remote = new PhotoItem("progress-session", 43, new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).compressedSize(JPEG.length).filename("REMOTE.JPG").build());
        values.clear(); Result<PhotoPresetBatchState> cancelled = new Result<>();
        f.batch.startTracked(List.of(PhotoPresetBatch.Source.remote(remote)), f.preset.id, new Result<>(), cancelled, values::add);
        until(() -> f.album.pending != null); AlbumController.DownloadCallback callback = f.album.pending;
        for (int index = 0; index < 10000; index++) callback.onProgress(index, 10000);
        check(values.size() < 100, "Thousands of byte updates are throttled before reaching UI observers");
        f.batch.cancel(); f.album.complete(first); PhotoPresetBatchState stopped = cancelled.await();
        check(stopped.cancelled == 1 && stopped.progress < 1 && stopped.currentStage.equals("已取消"), "Cancellation preserves the work fraction instead of falsely jumping to 100%");
        int count = values.size(); callback.onProgress(10000, 10000);
        check(values.size() == count && !f.batch.state().active, "Late progress cannot revive a cancelled tracked job");
        check(Arrays.equals(Files.readAllBytes(first.file().toPath()), JPEG), "Batch progress and cancellation leave original bytes untouched");
    }
    private static void np3RenderingReuseOriginalsAndCancellation(Path root) throws Exception {
        Fixture f = new Fixture(root); byte[] presetBytes = np3(true), presetBefore = presetBytes.clone();
        PresetRecord np3 = new PresetLibrary(f.context.getFilesDir()).importFile("Phone Color.NP3", presetBytes).record;
        PresetPreviewProfile profile = PresetPreviewProfiles.fromPreset(presetBytes);
        String model = PhotoPresetRenderer.modelVersion(profile);
        check(!model.equals(PhotoPresetRenderer.MODEL_VERSION), "NP3 uses its own photo output model identity");
        SpatialSurface surface = new SpatialSurface(37, 97); int[] preview = profile.render(surface.data, 37, 97);
        PhotoPresetRenderer.applyRows(surface, profile, () -> false, null);
        check(surface.written == 97 && surface.maxBuffer < 262144 && Arrays.equals(surface.data, preview),
                "NP3 full-size rows and image preview share the complete colour plus neighbourhood pipeline");
        AtomicBoolean cancel = new AtomicBoolean(); SpatialSurface partial = new SpatialSurface(37, 97);
        try { PhotoPresetRenderer.applyRows(partial, profile, cancel::get, progress -> { if (progress >= .5) cancel.set(true); }); throw new AssertionError("NP3 cancellation ignored"); }
        catch (PhotoPresetRenderer.Cancelled expected) { checks++; }
        check(partial.written > 0 && partial.written < 97 && Arrays.equals(presetBytes, presetBefore), "NP3 strip cancellation preserves untouched rows and preset bytes");
        DownloadedPhoto original = f.original("NP3-ORIGINAL.JPG"); long originalTime = original.file().lastModified();
        PhotoLiveGallery.failDerived = true; f.start(List.of(PhotoPresetBatch.Source.local(original)), np3.id); f.await();
        check(f.batch.state().failed == 1 && f.batch.state().results.size() == 1, "NP3 publication failure retains its complete freshly rendered effect for retry");
        DownloadedPhoto effect = f.batch.state().results.get(0); byte[] output = Files.readAllBytes(effect.file().toPath());
        int mapped = profile.applyArgb(0x0a141e28);
        check(output[2] == (byte)(mapped >>> 24) && output[3] == (byte)(mapped >>> 16) && output[4] == (byte)(mapped >>> 8) && output[5] == (byte)mapped,
                "The fresh NP3 batch applies the parsed profile to actual source pixels through the host codec facade");
        check(!Arrays.equals(output, JPEG), "The synthetic NP3 tone curve changes the original pixels");
        String sourceSha = hex(MessageDigest.getInstance("SHA-256").digest(JPEG));
        check(effect.file().getName().equals("effect-" + PhotoPresetBatch.key(sourceSha, np3.sha256, model) + "__Effect.JPG"), "NP3 output filename includes its own renderer version");
        PhotoPresetBatch reopened = new PhotoPresetBatch(f.context, new AlbumController());
        check(reopened.sourceFor(effect).file().equals(original.file().getCanonicalFile()) && reopened.processingOriginalFor(effect).file().equals(original.file().getCanonicalFile()), "NP3 comparison and processing retain the exact original across controller restart");
        for (String legacyModel : List.of(PhotoPresetRenderer.LEGACY_NP3_MODEL_VERSION,
                PhotoPresetRenderer.LEGACY_NP3_SPATIAL_MODEL_VERSION)) {
            String legacyKey = PhotoPresetBatch.key(sourceSha, np3.sha256, legacyModel);
            Path legacyPath = f.originals.resolve("effect-" + legacyKey + "__Effect.JPG");
            Files.write(legacyPath, output);
            Path legacyRecords = f.context.getFilesDir().toPath().resolve("photo-preset-results");
            Path legacyRecord = legacyRecords.resolve(legacyKey + ".record");
            try (DataOutputStream record = new DataOutputStream(Files.newOutputStream(legacyRecord))) {
                record.writeInt(0x50424531); record.writeLong(output.length); record.writeUTF(hex(MessageDigest.getInstance("SHA-256").digest(output)));
            }
            byte[] historicLedger = Files.readAllBytes(legacyRecord);
            DownloadedPhoto legacy = new DownloadedPhoto(legacyPath.toFile(), "Synthetic historic NP3.JPG");
            check(!legacyKey.equals(PhotoPresetBatch.key(sourceSha, np3.sha256, model))
                    && reopened.sourceFor(legacy).file().equals(original.file().getCanonicalFile()),
                    "Historic NP3 v1/v2 identity recovers its exact original without reusing the v3 cache");
            check(Arrays.equals(output, Files.readAllBytes(legacyPath))
                    && Arrays.equals(historicLedger, Files.readAllBytes(legacyRecord)),
                    "Recovering a historic NP3 original preserves prior pixels and output ledger");
        }
        f.resetObservations(); PhotoLiveGallery.failDerived = false;
        f.start(List.of(PhotoPresetBatch.Source.local(effect), PhotoPresetBatch.Source.local(original)), np3.id); f.await();
        check(f.batch.state().completed == 2 && f.batch.state().reused == 1 && f.batch.state().merged == 1 && f.batch.state().results.size() == 1, "Reapplying NP3 to its effect and original merges one verified reused output");
        check(android.graphics.BitmapFactory.decodedPaths.isEmpty() && Arrays.equals(output, Files.readAllBytes(effect.file().toPath())), "NP3 publication retry and reuse never decode or rewrite complete effects");
        PhotoItem remote = new PhotoItem("np3-session", 43, new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).compressedSize(JPEG.length).filename("NP3-REMOTE.JPG").build());
        f.start(List.of(PhotoPresetBatch.Source.remote(remote)), np3.id); until(() -> f.album.pending != null);
        f.batch.cancel(); f.album.complete(original); f.await();
        check(f.batch.state().cancelled == 1 && f.batch.state().results.isEmpty(), "Cancelling an NP3 acquisition drains its original without publishing an effect");
        f.start(List.of(PhotoPresetBatch.Source.remote(remote)), np3.id); f.await();
        check(f.album.downloads == 1 && f.batch.state().completed == 1 && f.batch.state().reused == 1, "NP3 retry uses the drained local original and existing effect without another camera read");
        check(Arrays.equals(JPEG, Files.readAllBytes(original.file().toPath())) && originalTime == original.file().lastModified(), "NP3 rendering, retries and cancellation leave original bytes and mtime intact");
        f.resetObservations(); f.start(List.of(PhotoPresetBatch.Source.local(effect))); f.await();
        check(android.graphics.BitmapFactory.decodedPaths.equals(List.of(original.file().getCanonicalPath())) && f.batch.state().completed == 1,
                "Changing an NP3 effect to NCP renders from its verified original instead of stacking effects");
    }

    /** Hand-authored Flexible Color record structure; no user recipe or Nikon image result is embedded. */
    private static byte[] np3(boolean customCurve) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); DataOutputStream data = new DataOutputStream(output);
        data.write(new byte[]{'N','C','P',0});
        for (int index = 1; index <= 32; index++) {
            byte[] payload = new byte[index == 1 ? 4 : index == 2 || index == 32 ? 20 : index == 31 ? 28 : 2];
            if (index == 1) payload = "0310".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            else if (index == 2) System.arraycopy("PHONE NP3".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, payload, 0, 9);
            else if (index == 3) payload[1] = 0x20;
            else if (index == 31) { Arrays.fill(payload, 0, 24, (byte)0x80); payload[24] = payload[25] = payload[26] = 1; }
            else if (index == 32) {
                for (int offset = 0; offset < 12; offset += 4) { payload[offset] = (byte)0x80; payload[offset+2] = payload[offset+3] = (byte)0x80; }
                payload[12] = payload[13] = payload[14] = 1; payload[16] = (byte)0xb2; payload[17] = 1; payload[18] = (byte)0x80; payload[19] = 1;
            } else if (index != 4) {
                payload[0] = (byte)(index == 6 ? 0x88 : index == 7 ? 0x82 : index == 22 ? 0x84 : index >= 25 || index == 20 ? 0x80 : 0xff);
                payload[1] = (byte)(index == 12 || index == 13 ? 0 : index == 21 ? 10 : index >= 6 && index <= 11 || index == 14 || index >= 22 && index <= 24 ? 4 : 1);
                if (customCurve && index >= 25 && index <= 29) payload[0] = 1;
            }
            data.writeInt(index * 0x100); data.writeInt(payload.length); data.write(payload);
        }
        if (customCurve) {
            byte[] curve = NcpCompatibilityFixtures.curve();
            for (int index = 0; index <= 256; index++) { int value = (int)Math.round(Math.sqrt(index / 256.0) * 32767); curve[64+index*2] = (byte)(value >>> 8); curve[65+index*2] = (byte)value; }
            data.writeInt(2); data.writeInt(curve.length); data.write(curve);
        }
        data.writeInt(0); data.flush(); return output.toByteArray();
    }
    private static void fullResolutionStripsAndCancellation() throws Exception {
        byte[] ncp = NcpCompatibilityFixtures.ncp(), before = ncp.clone();
        NcpPreviewProfile profile = NcpPreviewProfile.fromNcp(ncp);
        Surface full = new Surface(6000, 4000, profile);
        PhotoPresetRenderer.applyRows(full, profile, () -> false, null);
        check(full.written == 4000 && full.read == 4000, "24MP coverage is exact; template MAX_PIXELS does not cap full originals");
        check(full.maxBuffer <= 262144 && full.reused, "One bounded strip is reused instead of allocating a duplicate full image");
        check(Arrays.equals(ncp, before), "Pixel processing leaves preset source bytes unchanged");
        AtomicBoolean cancel = new AtomicBoolean(); Surface partial = new Surface(101, 257, profile);
        try { PhotoPresetRenderer.applyRows(partial, profile, cancel::get, progress -> { if (progress >= .5) cancel.set(true); }); throw new AssertionError("cancel ignored"); }
        catch (PhotoPresetRenderer.Cancelled expected) { checks++; }
        check(partial.written >= 128 && partial.written < 257, "Cancellation occurs at a bounded row or strip boundary");
        Surface untouched = new Surface(10, 10, profile);
        try { PhotoPresetRenderer.applyRows(untouched, profile, () -> true, null); throw new AssertionError("cancel ignored"); }
        catch (PhotoPresetRenderer.Cancelled expected) { checks++; }
        check(untouched.read == 0 && untouched.written == 0, "Already-cancelled work never reads or mutates pixels");
        try { PhotoPresetRenderer.applyRows(new Surface(65536, 1, profile), profile, () -> false, null); throw new AssertionError("invalid dimensions"); }
        catch (IOException expected) { checks++; }
        String a = "a".repeat(64), b = "b".repeat(64);
        String legacyModel = "encoded-rgb-tone-hsv-v1:full-jpeg95-srgb-v1";
        check(PhotoPresetRenderer.MODEL_VERSION.equals(legacyModel) && PhotoPresetRenderer.modelVersion(profile).equals(legacyModel), "Legacy NCP model identity remains unchanged after the generic-profile integration");
        check(PhotoPresetBatch.key(a,b).equals(hex(MessageDigest.getInstance("SHA-256").digest((a + "\n" + b + "\n" + legacyModel).getBytes(java.nio.charset.StandardCharsets.UTF_8)))), "Legacy NCP effect filenames keep their original source/preset/model key");
        check(PhotoPresetBatch.key(a,b).equals(PhotoPresetBatch.key(a,b)), "Content/preset/model key is deterministic");
        check(!PhotoPresetBatch.key(a,b).equals(PhotoPresetBatch.key(b,a)), "Source and preset content have distinct key roles");
    }
    private static final class Surface implements PhotoPresetRenderer.Pixels {
        final int width, height; final PresetPreviewProfile profile; int read, written, maxBuffer; int[] first; boolean reused = true;
        Surface(int width, int height, PresetPreviewProfile profile) { this.width=width; this.height=height; this.profile=profile; }
        public int width() { return width; } public int height() { return height; }
        private int pixel(int row, int x) { return ((x & 255) << 24) | ((row & 255) << 16) | ((x * 3 & 255) << 8) | (row * 7 & 255); }
        public void read(int[] buffer, int row, int rows) {
            if (row != read) throw new AssertionError("read gap"); read += rows; maxBuffer=Math.max(maxBuffer,buffer.length);
            if (first == null) first=buffer; else reused &= first==buffer;
            for(int y=0;y<rows;y++) for(int x=0;x<width;x++) buffer[y*width+x]=pixel(row+y,x);
        }
        public void write(int[] buffer, int row, int rows) {
            if(row!=written)throw new AssertionError("write gap"); written+=rows;
            for(int y=0;y<rows;y++) for(int x=0;x<width;x++) if(buffer[y*width+x]!=profile.applyArgb(pixel(row+y,x)))throw new AssertionError("pixel mismatch");
        }
    }
    private static final class SpatialSurface implements PhotoPresetRenderer.Pixels {
        final int width, height; final int[] data; int written, maxBuffer;
        SpatialSurface(int width, int height) {
            this.width = width; this.height = height; data = new int[width * height];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int value = 128 + (int) Math.round(44 * Math.sin(x * .31) + 27 * Math.sin(y * .17));
                data[y * width + x] = 0xff000000 | value * 0x010101;
            }
        }
        public int width() { return width; } public int height() { return height; }
        public void read(int[] pixels, int row, int rows) {
            check(row >= written, "Spatial pipeline never rereads overwritten original rows");
            maxBuffer = Math.max(maxBuffer, pixels.length);
            System.arraycopy(data, row * width, pixels, 0, width * rows);
        }
        public void write(int[] pixels, int row, int rows) {
            check(row == written, "Spatial output rows are complete and ordered");
            System.arraycopy(pixels, 0, data, row * width, width * rows); written += rows;
        }
    }
    private static void localReuseAndPublicationRetryPreserveSources(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto source=f.original("source.jpg"); File output=f.seed(source);
        byte[] before=Files.readAllBytes(source.file().toPath()), derived=Files.readAllBytes(output.toPath());
        long modified=source.file().lastModified(); AtomicInteger changed=new AtomicInteger(); Runnable observer=changed::incrementAndGet; f.batch.addObserver(observer);
        check(f.presets().size()==1, "Only committed local preset library records are listed");
        Result<Void> over=new Result<>(); f.batch.start(Collections.nCopies(101,PhotoPresetBatch.Source.local(source)),f.preset.id,over);
        check(over.error!=null && !f.batch.state().active, "Over-limit selection is rejected explicitly rather than truncated");
        PhotoLiveGallery.failDerived=true; f.start(List.of(PhotoPresetBatch.Source.local(source))); f.await();
        check(f.batch.state().failed==1 && f.batch.state().completed==0 && f.batch.state().results.size()==1, "Publication failure keeps a complete result and per-file error for retry");
        check(output.isFile() && f.album.downloads==0, "Local processing/retry never downloads from the camera");
        DownloadedPhoto compared = new PhotoPresetBatch(f.context, new AlbumController()).sourceFor(f.batch.state().results.get(0));
        check(compared.file().equals(source.file().getCanonicalFile()), "Verified cache hits backfill a source link usable by a fresh application batch instance");
        PhotoLiveGallery.failDerived=false; f.start(List.of(PhotoPresetBatch.Source.local(source))); f.await();
        check(f.batch.state().completed==1 && f.batch.state().failed==0 && PhotoLiveGallery.rows.size()==1, "Retry reuses one derived copy and coalesces its publication");
        check(Arrays.equals(before,Files.readAllBytes(source.file().toPath())) && source.file().lastModified()==modified, "Original content and modification time are never changed");
        check(Arrays.equals(derived,Files.readAllBytes(output.toPath())), "Reapplying identical source/preset validates and reuses derived bytes");
        DownloadedPhoto alias=f.original("same-bytes-other-name.jpg"); f.start(List.of(PhotoPresetBatch.Source.local(alias))); f.await();
        check(f.batch.state().results.get(0).file().equals(output.getCanonicalFile()), "Display filename changes cannot duplicate content-addressed effect output");
        byte[] damaged=derived.clone(); damaged[2]++; Files.write(output.toPath(),damaged);
        f.start(List.of(PhotoPresetBatch.Source.local(source))); f.await();
        check(f.batch.state().failed==1 && Arrays.equals(damaged,Files.readAllBytes(output.toPath())), "Changed completed output is reported, never overwritten");
        check(changed.get()>0 && Arrays.equals(before,Files.readAllBytes(source.file().toPath())), "Observers report progress while source remains intact across errors");
        f.batch.removeObserver(observer);
    }
    private static void cancelledRemoteDrainsAndNeverFetchesLocalAgain(Path root) throws Exception {
        Fixture f=new Fixture(root); DownloadedPhoto source=f.original("source.jpg"); f.seed(source);
        PhotoItem photo=new PhotoItem("physical-session",43,new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).compressedSize(JPEG.length).filename("REMOTE.JPG").build());
        f.start(List.of(PhotoPresetBatch.Source.remote(photo),PhotoPresetBatch.Source.local(source)));
        until(()->f.album.pending!=null);
        Result<Void> duplicate = new Result<>(); f.batch.start(List.of(PhotoPresetBatch.Source.local(source)), f.preset.id, duplicate);
        check(duplicate.error != null && f.batch.state().total == 2 && f.album.downloads == 1, "A repeated start while acquisition is pending cannot replace or submit another active job");
        f.batch.cancel(); Thread.sleep(100);
        check(f.batch.state().active && f.album.downloads==1, "Cancel retains active task while the in-flight acquisition callback drains");
        f.album.complete(source); f.await();
        check(f.batch.state().cancelled==2 && f.batch.state().results.isEmpty(), "Cancel does not start later source or pixel work after drain");
        f.start(List.of(PhotoPresetBatch.Source.remote(photo))); f.await();
        check(f.album.downloads==1 && f.batch.state().completed==1, "Retry uses completed original from the same session without another GetObject");
        check(Arrays.equals(Files.readAllBytes(source.file().toPath()),JPEG), "Cancelled acquisition preserves the complete original");
    }
    private static void legacyPublicLookupRunsWithoutCameraOrBatch(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto source = f.original("legacy-source.jpg"); File effect = f.seed(source);
        DownloadedPhoto found = f.batch.sourceFor(new DownloadedPhoto(effect, "Effect.JPG"));
        check(found != null && found.file().equals(source.file().getCanonicalFile()), "Public lookup migrates a pre-link 0.8.0 effect using committed local preset content");
        check(f.album.downloads == 0 && !f.batch.state().active && f.batch.state().results.isEmpty(), "Legacy comparison lookup neither downloads nor starts/mutates batch state");
        PhotoPresetBatch fresh = new PhotoPresetBatch(f.context, new AlbumController());
        check(fresh.sourceFor(new DownloadedPhoto(effect, "Effect.JPG")).file().equals(source.file().getCanonicalFile()), "Public migrated comparison survives a fresh batch controller");
    }
    private static void disconnectAndLateCallbacksCannotReviveBatch(Path root) throws Exception {
        Fixture f=new Fixture(root);
        PhotoItem photo=new PhotoItem("session",43,new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).compressedSize(JPEG.length).filename("REMOTE.JPG").build());
        f.start(List.of(PhotoPresetBatch.Source.remote(photo))); until(()->f.album.pending!=null);
        AlbumController.DownloadCallback late=f.album.pending; f.album.connected=false; f.await();
        check(f.batch.state().failed==1 && !f.batch.state().active, "Disconnected acquisition reports a bounded per-file failure");
        late.onProgress(1,2); late.onError("late");
        check(!f.batch.state().active && f.batch.state().failed==1, "Late callbacks cannot restart an already-failed batch");
    }
    private static void deletedInputsAndResultsDoNotRevive(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.original("original.JPG"); f.seed(original);
        try (PhotoLocalTasks.Lease ignored = PhotoLocalTasks.delete()) {
            Result<Void> blocked = new Result<>(); f.batch.start(List.of(PhotoPresetBatch.Source.local(original)), f.preset.id, blocked);
            check(blocked.error != null && !f.batch.state().active, "A deletion reservation rejects a new batch before accepting any work");
        }
        PhotoItem remote = new PhotoItem("session", 43, new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).compressedSize(JPEG.length).filename("REMOTE.JPG").build());
        f.start(List.of(PhotoPresetBatch.Source.remote(remote))); until(() -> f.album.pending != null);
        try { PhotoLocalTasks.delete(); throw new AssertionError("active batch did not reserve inputs"); } catch (IOException expected) { checks++; }
        f.album.complete(original); f.await(); DownloadedPhoto effect = f.batch.state().results.get(0);
        Files.delete(original.file().toPath());
        try { f.batch.sourceFor(effect); throw new AssertionError("deleted original still compared"); } catch (IOException expected) { checks++; }
        Files.delete(effect.file().toPath());
        check(f.batch.state().results.isEmpty(), "Deleted complete results are filtered from the retained batch snapshot");
        DownloadedPhoto replacement = f.original("replacement.JPG"); f.seed(replacement); f.album.pending = null;
        f.start(List.of(PhotoPresetBatch.Source.remote(remote))); until(() -> f.album.pending != null);
        check(f.album.downloads == 2, "Missing acquisition cache is evicted and only a new explicit batch downloads again");
        f.album.complete(replacement); f.await();
        check(f.batch.state().completed == 1 && f.batch.sourceFor(f.batch.state().results.get(0)).file().equals(replacement.file().getCanonicalFile()),
                "Explicit byte-identical replacement restores the direct source link without reviving a deleted old path");
    }
    private static void effectInputsAlwaysUseRootAndReuse(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.original("original.JPG");
        byte[] before = Files.readAllBytes(original.file().toPath()); long modified = original.file().lastModified();
        f.start(List.of(PhotoPresetBatch.Source.local(original))); f.await();
        DownloadedPhoto first = f.batch.state().results.get(0); byte[] firstBytes = Files.readAllBytes(first.file().toPath());
        long firstModified = first.file().lastModified();
        check(android.graphics.BitmapFactory.decodedPaths.equals(List.of(original.file().getCanonicalPath())), "Fresh rendering decodes the actual original path through the explicit host codec facade");
        check(!Arrays.equals(firstBytes, before), "The fixture effect differs from its original so stacking cannot pass accidentally");
        check(f.batch.processingOriginalFor(first).file().equals(original.file().getCanonicalFile()), "Public processing lookup returns the verified original");
        f.resetObservations(); f.start(List.of(PhotoPresetBatch.Source.local(first))); f.await();
        check(f.batch.state().reused == 1 && f.batch.state().merged == 0 && f.batch.state().completed == 1, "Effect A applied with A reports one verified existing output reuse");
        check(f.batch.state().results.size() == 1 && f.batch.state().results.get(0).file().equals(first.file()), "Effect A applied with A returns A instead of creating an A-on-A output");
        check(android.graphics.BitmapFactory.decodedPaths.isEmpty() && f.effectCount() == 1, "Verified reuse does not decode pixels or generate a stacked effect");
        byte[] secondNcp = NcpCompatibilityFixtures.ncp(); secondNcp[44] = (byte) 0x7d;
        PresetRecord second = new PresetLibrary(f.context.getFilesDir()).importFile("second.ncp", secondNcp).record;
        f.resetObservations(); f.start(List.of(PhotoPresetBatch.Source.local(first)), second.id); f.await();
        DownloadedPhoto changed = f.batch.state().results.get(0); byte[] changedBytes = Files.readAllBytes(changed.file().toPath());
        check(android.graphics.BitmapFactory.decodedPaths.equals(List.of(original.file().getCanonicalPath())), "Changing A to B renders from the original rather than decoding effect A");
        check(f.batch.state().reused == 0 && f.effectCount() == 2, "A different preset creates just its own original-based effect");
        f.resetObservations(); f.start(List.of(PhotoPresetBatch.Source.local(original)), second.id); f.await();
        check(f.batch.state().reused == 1 && f.batch.state().results.get(0).file().equals(changed.file()), "Original to B and effect A to B share the same derived identity");
        check(Arrays.equals(changedBytes, Files.readAllBytes(changed.file().toPath())) && android.graphics.BitmapFactory.decodedPaths.isEmpty(), "Returning to the same original/preset preserves and reuses the complete result");
        byte[] legacyBytes = JPEG.clone(); legacyBytes[3] ^= 17;
        DownloadedPhoto historic = f.historicEffect(first, second, legacyBytes);
        check(f.batch.sourceFor(historic).file().equals(first.file()) && f.batch.processingOriginalFor(historic).file().equals(original.file()),
                "The public batch APIs preserve immediate historical comparison while resolving a two-application history to its root for processing");
        f.resetObservations(); f.start(List.of(PhotoPresetBatch.Source.local(historic)), second.id); f.await();
        check(f.batch.state().results.get(0).file().equals(changed.file()) && f.batch.state().reused == 1 && android.graphics.BitmapFactory.decodedPaths.isEmpty(),
                "Reprocessing a historic stacked effect uses the already verified original-based B result instead of producing a third application");
        check(Arrays.equals(before, Files.readAllBytes(original.file().toPath())) && modified == original.file().lastModified(), "Replacing a preset never changes original bytes or mtime");
        check(Arrays.equals(firstBytes, Files.readAllBytes(first.file().toPath())) && firstModified == first.file().lastModified(), "Replacing a preset also preserves the previous effect and its mtime");
    }
    private static void mixedAliasesMergeOnceIncludingPublicationFailure(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.original("original.JPG");
        f.start(List.of(PhotoPresetBatch.Source.local(original))); f.await(); DownloadedPhoto effect = f.batch.state().results.get(0);
        DownloadedPhoto alias = f.original("byte-identical-alias.JPG");
        List<PhotoPresetBatch.Source> mixed = List.of(PhotoPresetBatch.Source.local(original), PhotoPresetBatch.Source.local(effect),
                PhotoPresetBatch.Source.local(alias), PhotoPresetBatch.Source.local(original));
        f.resetObservations(); f.start(mixed); f.await();
        PhotoPresetBatchState state = f.batch.state();
        check(state.total == 4 && state.completed == 4 && state.failed == 0 && state.merged == 3 && state.reused == 1, "Original, effect, byte alias and repeated input merge while accounting for all four selections");
        check(state.results.size() == 1 && PhotoLiveGallery.publications.size() == 1 && android.graphics.BitmapFactory.decodedPaths.isEmpty(), "A merged job returns one result and publishes only its effect once");
        f.resetObservations(); PhotoLiveGallery.failDerived = true; f.start(mixed); f.await(); state = f.batch.state();
        check(state.failed == 4 && state.completed == 0 && state.cancelled == 0 && state.merged == 3 && state.results.size() == 1,
                "Duplicate inputs inherit the first failed publication outcome without becoming false successes");
        check(PhotoLiveGallery.publications.size() == 1 && f.effectCount() == 1, "A failed derived publish is attempted once in the job, not retried secretly by aliases");
        PhotoLiveGallery.failDerived = false; f.resetObservations(); f.start(List.of(PhotoPresetBatch.Source.local(effect))); f.await();
        check(f.batch.state().completed == 1 && f.batch.state().reused == 1 && f.batch.state().merged == 0 && PhotoLiveGallery.publications.size() == 1,
                "A new explicit batch retries publication while reusing the already complete output");
        check(PhotoLiveGallery.rows.size() == 1 && f.effectCount() == 1, "Explicit retry does not multiply private or synthetic gallery copies");
        byte[] freshNcp = NcpCompatibilityFixtures.ncp(); freshNcp[44] = (byte) 0x7d;
        PresetRecord fresh = new PresetLibrary(f.context.getFilesDir()).importFile("fresh-mixed.ncp", freshNcp).record;
        f.resetObservations(); f.start(mixed, fresh.id); f.await(); state = f.batch.state();
        check(state.completed == 4 && state.merged == 3 && state.reused == 0 && state.results.size() == 1 && f.effectCount() == 2,
                "A new preset applied to mixed original/effect/aliases creates exactly one fresh result");
        check(android.graphics.BitmapFactory.decodedPaths.equals(List.of(original.file().getCanonicalPath())) && PhotoLiveGallery.publications.size() == 1,
                "A fresh mixed job performs one original decode and one effect publication");
        f.resetObservations(); android.graphics.BitmapFactory.failDecode = true;
        byte[] alternate = NcpCompatibilityFixtures.ncp(); alternate[43] = (byte) 0x81;
        PresetRecord badRun = new PresetLibrary(f.context.getFilesDir()).importFile("decode-test.ncp", alternate).record;
        f.start(mixed, badRun.id); f.await(); state = f.batch.state();
        check(state.failed == 4 && state.merged == 3 && state.results.isEmpty() && state.reused == 0, "A failed unique render is inherited by every merged input and exposes no partial result");
        check(android.graphics.BitmapFactory.decodedPaths.equals(List.of(original.file().getCanonicalPath())) && PhotoLiveGallery.publications.isEmpty(),
                "A render failure is attempted once from the root and publishes no effect");
        android.graphics.BitmapFactory.failDecode = false;
    }
    private static void invalidEffectInputsNeverStack(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.original("original.JPG");
        f.start(List.of(PhotoPresetBatch.Source.local(original))); f.await(); DownloadedPhoto effect = f.batch.state().results.get(0);
        Path link = f.context.getFilesDir().toPath().resolve("photo-preset-results").resolve(effect.file().getName().substring(7, 71) + ".source");
        byte[] validLink = Files.readAllBytes(link), damagedLink = validLink.clone(), effectBytes = Files.readAllBytes(effect.file().toPath());
        damagedLink[damagedLink.length - 1] ^= 1; Files.write(link, damagedLink); f.resetObservations();
        f.start(List.of(PhotoPresetBatch.Source.local(effect))); f.await();
        check(f.batch.state().failed == 1 && f.batch.state().results.isEmpty() && PhotoLiveGallery.publications.isEmpty()
                && android.graphics.BitmapFactory.decodedPaths.isEmpty(), "A damaged effect association fails before publication or pixel rendering");
        check(Arrays.equals(damagedLink, Files.readAllBytes(link)) && Arrays.equals(effectBytes, Files.readAllBytes(effect.file().toPath())), "A rejected damaged association and existing effect remain untouched");
        Files.write(link, validLink); byte[] changed = effectBytes.clone(); changed[2] ^= 1; Files.write(effect.file().toPath(), changed);
        f.start(List.of(PhotoPresetBatch.Source.local(effect))); f.await();
        check(f.batch.state().failed == 1 && f.effectCount() == 1 && android.graphics.BitmapFactory.decodedPaths.isEmpty(), "Changed effect bytes cannot be adopted as a new original");
        Files.write(effect.file().toPath(), effectBytes); Files.delete(original.file().toPath()); f.start(List.of(PhotoPresetBatch.Source.local(effect))); f.await();
        check(f.batch.state().failed == 1 && f.batch.state().results.isEmpty() && PhotoLiveGallery.publications.isEmpty(), "Missing root original is reported instead of stacking the existing effect");
        DownloadedPhoto lookalike = f.original("effect-" + "f".repeat(64) + "__Effect.JPG");
        f.start(List.of(PhotoPresetBatch.Source.local(lookalike))); f.await();
        check(f.batch.state().failed == 1 && android.graphics.BitmapFactory.decodedPaths.isEmpty() && PhotoLiveGallery.publications.isEmpty(), "An effect-looking file without a completion record is rejected, not rendered as an ordinary original");
    }
    private static void cancelledRootResolutionAndDeletedEffectRecovery(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto original = f.original("original.JPG");
        byte[] originalBytes = Files.readAllBytes(original.file().toPath()); long modified = original.file().lastModified();
        f.start(List.of(PhotoPresetBatch.Source.local(original))); f.await(); DownloadedPhoto effect = f.batch.state().results.get(0);
        byte[] completed = Files.readAllBytes(effect.file().toPath()); f.resetObservations();
        try { f.batch.processingOriginalFor(effect, () -> true); throw new AssertionError("cancelled resolution returned an original"); }
        catch (java.io.InterruptedIOException expected) { checks++; }
        Runnable cancel = () -> { if (f.batch.state().active && !f.batch.state().currentFilename.isEmpty()) f.batch.cancel(); };
        f.batch.addObserver(cancel); f.start(List.of(PhotoPresetBatch.Source.local(effect))); f.await(); f.batch.removeObserver(cancel);
        check(f.batch.state().cancelled == 1 && f.batch.state().failed == 0 && f.batch.state().results.isEmpty(), "Cancelling before root resolution produces cancellation rather than a processing failure");
        check(android.graphics.BitmapFactory.decodedPaths.isEmpty() && PhotoLiveGallery.publications.isEmpty() && Arrays.equals(completed, Files.readAllBytes(effect.file().toPath())),
                "Cancelled root processing neither renders nor republishes or changes the previous effect");
        Files.delete(effect.file().toPath()); f.resetObservations(); f.start(List.of(PhotoPresetBatch.Source.local(original))); f.await();
        check(f.batch.state().completed == 1 && f.batch.state().reused == 0 && f.batch.state().results.get(0).file().equals(effect.file()), "An explicitly deleted effect can be regenerated at its original content identity");
        check(Arrays.equals(completed, Files.readAllBytes(effect.file().toPath())) && android.graphics.BitmapFactory.decodedPaths.equals(List.of(original.file().getCanonicalPath())), "Regeneration checks the retained output ledger against a fresh render of the original");
        check(Arrays.equals(originalBytes, Files.readAllBytes(original.file().toPath())) && modified == original.file().lastModified(), "Cancellation and explicit result recovery preserve original bytes and mtime");
    }
    private static void crossDirectoryOriginalAliasesReuseOneEffect(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto external = f.original("External.JPG");
        f.start(List.of(PhotoPresetBatch.Source.local(external))); f.await(); DownloadedPhoto effect = f.batch.state().results.get(0);
        Path internalDirectory = f.context.getFilesDir().toPath().resolve("pictures/camera-album"); Files.createDirectories(internalDirectory);
        Path internalSource = internalDirectory.resolve("Internal.JPG"); Files.copy(external.file().toPath(), internalSource);
        DownloadedPhoto internal = new DownloadedPhoto(internalSource.toFile(), "Internal.JPG"); f.resetObservations();
        f.start(List.of(PhotoPresetBatch.Source.local(internal))); f.await();
        check(f.batch.state().completed == 1 && f.batch.state().reused == 1 && f.batch.state().results.get(0).file().equals(effect.file()), "A byte-identical internal original reuses the existing external effect for the same preset");
        check(android.graphics.BitmapFactory.decodedPaths.isEmpty() && !Files.exists(internalDirectory.resolve(effect.file().getName())), "Cross-directory reuse neither decodes pixels nor generates a second same-key output");
        Path moved = internalDirectory.resolve(effect.file().getName()); Files.move(effect.file().toPath(), moved); f.resetObservations();
        f.start(List.of(PhotoPresetBatch.Source.local(external))); f.await();
        check(f.batch.state().completed == 1 && f.batch.state().reused == 1 && f.batch.state().results.get(0).file().equals(moved.toFile()), "An external original also reuses a verified same-key output kept only in internal storage");
        check(android.graphics.BitmapFactory.decodedPaths.isEmpty() && !effect.file().exists(), "Reverse-directory reuse keeps the one existing output at its original location");
    }
    private static void individualDraftExports(Path root) throws Exception {
        Fixture f=new Fixture(root); DownloadedPhoto a=f.original("A.JPG"),b=f.original("B.JPG"),c=f.original("C.JPG");
        byte[] bBytes=JPEG.clone();bBytes[4]=77;Files.write(b.file().toPath(),bBytes);
        byte[] cBytes=JPEG.clone();cBytes[4]=99;Files.write(c.file().toPath(),cBytes);
        byte[] ncp=NcpCompatibilityFixtures.ncp(),np=np3(false);new PresetLibrary(f.context.getFilesDir()).importFile("B.np3",np);
        PhotoEditStore store=new PhotoEditStore(f.context.getFilesDir(),"owner-a");
        PhotoEditStore.Draft da=new PhotoEditStore.Draft(PhotoEditStore.hashFile(a.file()),ncp,"A 70",70,false),db=new PhotoEditStore.Draft(PhotoEditStore.hashFile(b.file()),np,"B 40",40,false),dc=new PhotoEditStore.Draft(PhotoEditStore.hashFile(c.file()),null,"原图",0,false);
        for(PhotoEditStore.Draft d:List.of(da,db,dc))store.save(d);
        List<PhotoPresetBatch.Edit> edits=List.of(PhotoPresetBatch.Edit.draft(PhotoPresetBatch.Source.local(a),da,"owner-a"),PhotoPresetBatch.Edit.draft(PhotoPresetBatch.Source.local(b),db,"owner-a"),PhotoPresetBatch.Edit.draft(PhotoPresetBatch.Source.local(c),dc,"owner-a"));
        Result<Void> accepted=new Result<>();f.batch.startEdits(edits,accepted,null);accepted.await();f.await();
        PhotoPresetBatchState state=f.batch.state();check(state.completed==3&&state.failed==0&&state.results.size()==3,"Different per-photo filters, strengths and original export independently");
        check(f.effectCount()==2&&state.results.get(2).file().equals(c.file()),"Explicit original creates no unnecessary effect file");
        for(int i=0;i<2;i++){
            PhotoEditStore.Draft d=i==0?da:db;DownloadedPhoto input=i==0?a:b;
            PresetPreviewProfile p=cc.huevia.ncp.StrengthPreviewProfile.of(PresetPreviewProfiles.fromPreset(d.presetBytes()),d.strength);
            String key=PhotoPresetBatch.key(d.sourceSha,d.presetSha,PhotoPresetRenderer.modelVersion(p));
            check(state.results.get(i).file().getName().startsWith("effect-"+key+"__"),"Result identity includes each frozen strength/model");
            File single=root.resolve("single-"+i+".jpg").toFile();new PhotoPresetRenderer().render(input.file(),single,p,()->false,null);
            check(Arrays.equals(Files.readAllBytes(single.toPath()),Files.readAllBytes(state.results.get(i).file().toPath())),"Batch pixels equal individual render with the same recipe and strength");
            check(store.read(d.sourceSha).exported,"Only successfully completed exact draft is marked exported");
            Path link=f.context.getFilesDir().toPath().resolve("photo-preset-results/"+key+".source");Files.delete(link);
            check(f.batch.processingOriginalFor(state.results.get(i)).file().equals(input.file().getCanonicalFile()),"Interrupted strength source commit recovers using persisted model identity");
        }
        check(Arrays.equals(JPEG,Files.readAllBytes(a.file().toPath()))&&Arrays.equals(bBytes,Files.readAllBytes(b.file().toPath()))&&Arrays.equals(cBytes,Files.readAllBytes(c.file().toPath())),"Mixed export preserves all original bytes");
        PhotoEditStore.Draft newer=new PhotoEditStore.Draft(da.sourceSha,ncp,"A newer",20,false);store.save(newer);
        f.resetObservations();accepted=new Result<>();f.batch.startEdits(edits,accepted,null);accepted.await();f.await();
        check(f.batch.state().reused==2&&f.effectCount()==2&&android.graphics.BitmapFactory.decodedPaths.isEmpty(),"Frozen retry reuses exact results despite subsequent edited settings");
        check(store.read(da.sourceSha).strength==20&&!store.read(da.sourceSha).exported,"Finishing older frozen draft cannot mark newer settings exported");
        PhotoLiveGallery.failDerived=true;PhotoEditStore.Draft alternate=new PhotoEditStore.Draft(da.sourceSha,ncp,"A 30",30,false);
        PhotoPresetBatch.Edit fail=PhotoPresetBatch.Edit.draft(PhotoPresetBatch.Source.local(a),alternate,"owner-a");accepted=new Result<>();f.batch.startEdits(List.of(fail,edits.get(2)),accepted,null);accepted.await();f.await();
        check(f.batch.state().completed==1&&f.batch.state().failed==1&&f.batch.retryEdits().size()==1&&f.batch.retryEdits().get(0).draft==fail.draft&&f.batch.retryEdits().get(0).source==fail.source,"Partial failure retains only unfinished immutable per-photo choice");
        PhotoLiveGallery.failDerived=false;accepted=new Result<>();f.batch.startEdits(f.batch.retryEdits(),accepted,null);accepted.await();f.await();
        check(f.batch.state().completed==1&&f.batch.state().reused==1&&f.batch.retryEdits().isEmpty(),"Retry publishes completed pixels without rerendering or repeating successful photos");
        Runnable cancel=()->{if(f.batch.state().active&&!f.batch.state().currentFilename.isEmpty())f.batch.cancel();};f.batch.addObserver(cancel);
        accepted=new Result<>();f.batch.startEdits(edits,accepted,null);accepted.await();f.await();f.batch.removeObserver(cancel);
        check(f.batch.state().cancelled==3&&f.batch.state().completed==0&&f.batch.retryEdits().size()==3,"Cancellation preserves every not-started independent edit for explicit retry");
        PhotoEditStore.Draft wrong=new PhotoEditStore.Draft("f".repeat(64),ncp,"wrong source",70,false);accepted=new Result<>();
        f.resetObservations();f.batch.startEdits(List.of(PhotoPresetBatch.Edit.draft(PhotoPresetBatch.Source.local(a),wrong,"owner-a")),accepted,null);accepted.await();f.await();
        check(f.batch.state().failed==1&&PhotoLiveGallery.publications.isEmpty()&&android.graphics.BitmapFactory.decodedPaths.isEmpty(),"Mismatched original identity is rejected before rendering or publication");
    }
    private static void explicitDestinationsAndFrozenRetry(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto source = f.original("APP-ONLY.JPG"), other = f.original("SECOND.JPG");
        byte[] secondBytes = JPEG.clone(); secondBytes[4] ^= 31; Files.write(other.file().toPath(), secondBytes);
        byte[] originalBytes = Files.readAllBytes(source.file().toPath()); long originalModified = source.file().lastModified();
        PhotoEditStore.Draft draft = new PhotoEditStore.Draft(PhotoEditStore.hashFile(source.file()), NcpCompatibilityFixtures.ncp(), "Local", 64, false);
        PhotoPresetBatch.Edit local = PhotoPresetBatch.Edit.draft(PhotoPresetBatch.Source.local(source), draft, "target-owner");
        Result<Void> accepted = new Result<>();
        f.batch.startEdits(List.of(local, PhotoPresetBatch.Edit.saved(PhotoPresetBatch.Source.local(other))), PhotoSaveTarget.APP_ONLY, false, accepted, null);
        accepted.await(); f.await();
        check(f.batch.state().completed == 2 && f.batch.state().failed == 0 && f.batch.state().results.size() == 2,
                "APP_ONLY commits both edited and unchanged selections as durable local results");
        check(PhotoLiveGallery.publications.isEmpty() && PhotoLiveGallery.rows.isEmpty(), "APP_ONLY never calls the publication boundary");
        DownloadedPhoto effect = f.batch.state().results.get(0); byte[] pixels = Files.readAllBytes(effect.file().toPath());
        f.resetObservations(); accepted = new Result<>(); f.batch.startEdits(List.of(local), accepted, null); accepted.await(); f.await();
        check(f.batch.state().reused == 1 && PhotoLiveGallery.publications.equals(List.of(effect.file().getAbsolutePath())),
                "Edited export defaults to phone album and publishes only its derived result");
        check(PhotoLiveGallery.rows.size() == 1 && Arrays.equals(pixels, Files.readAllBytes(effect.file().toPath())),
                "Publishing an app-only result reuses its verified pixels");
        accepted = new Result<>(); f.batch.startEdits(List.of(local), accepted, null); accepted.await(); f.await();
        check(PhotoLiveGallery.rows.size() == 1, "Same frozen edit publication reuses its existing row");
        accepted = new Result<>(); f.batch.startEdits(List.of(local), PhotoSaveTarget.PHONE_ALBUM, true, accepted, null); accepted.await(); f.await();
        check(PhotoLiveGallery.rows.size() == 2 && f.batch.state().reused == 1, "Explicit new copy adds one phone row without rerendering");
        PhotoEditStore.Draft alternate = new PhotoEditStore.Draft(draft.sourceSha, NcpCompatibilityFixtures.ncp(), "Failed phone target", 37, false);
        PhotoPresetBatch.Edit fail = PhotoPresetBatch.Edit.draft(PhotoPresetBatch.Source.local(source), alternate, "target-owner");
        PhotoLiveGallery.failDerived = true; accepted = new Result<>();
        f.batch.startEdits(List.of(fail, PhotoPresetBatch.Edit.saved(PhotoPresetBatch.Source.local(other))), PhotoSaveTarget.PHONE_ALBUM, false, accepted, null);
        accepted.await(); f.await();
        List<PhotoPresetBatch.Edit> retry = f.batch.retryEdits();
        check(f.batch.state().completed == 1 && f.batch.state().failed == 1 && retry.size() == 1,
                "Partial publication failure retains only the failed selection");
        check(retry.get(0).saveTarget == PhotoSaveTarget.PHONE_ALBUM && !retry.get(0).forceNewCopy,
                "Failed selection captures its accepted destination and copy policy");
        PhotoLiveGallery.failDerived = false; f.resetObservations(); accepted = new Result<>();
        f.batch.startEdits(retry, PhotoSaveTarget.APP_ONLY, true, accepted, null); accepted.await(); f.await();
        check(f.batch.state().completed == 1 && f.batch.state().reused == 1 && PhotoLiveGallery.publications.size() == 1,
                "Retry preserves phone destination despite a different current UI choice");
        check(f.batch.retryEdits().isEmpty() && Arrays.equals(originalBytes, Files.readAllBytes(source.file().toPath()))
                        && originalModified == source.file().lastModified() && Arrays.equals(secondBytes, Files.readAllBytes(other.file().toPath())),
                "Destination changes and retries preserve every original byte and source modification time");
    }
    private static void newCopyRetryAfterDraftWriteFailureDoesNotPublishAgain(Path root) throws Exception {
        Fixture f = new Fixture(root); DownloadedPhoto source = f.original("AFTER-COMMIT.JPG");
        PhotoEditStore.Draft draft = new PhotoEditStore.Draft(PhotoEditStore.hashFile(source.file()), NcpCompatibilityFixtures.ncp(), "Committed copy", 58, false);
        PhotoEditStore store = new PhotoEditStore(f.context.getFilesDir(), "copy-owner"); store.save(draft);
        Path path; try (var files = Files.walk(f.context.getFilesDir().toPath())) { path = files.filter(file -> file.getFileName().toString().equals(draft.sourceSha + ".draft")).findFirst().orElseThrow(); }
        byte[] valid = Files.readAllBytes(path); PhotoPresetBatch.Edit edit = PhotoPresetBatch.Edit.draft(PhotoPresetBatch.Source.local(source), draft, "copy-owner");
        PhotoLiveGallery.beforePublish = photo -> { try { Files.write(path, new byte[]{1}); } catch (IOException failure) { throw new RuntimeException(failure); } };
        try {
            Result<Void> accepted = new Result<>(); f.batch.startEdits(List.of(edit), PhotoSaveTarget.PHONE_ALBUM, true, accepted, null); accepted.await(); f.await();
            check(f.batch.state().failed == 1 && PhotoLiveGallery.rows.size() == 1 && f.batch.retryEdits().size() == 1,
                    "A history-write failure after a confirmed new copy retains the exact failed edit for retry");
            Files.write(path, valid); PhotoLiveGallery.beforePublish = null; accepted = new Result<>();
            f.batch.startEdits(f.batch.retryEdits(), PhotoSaveTarget.PHONE_ALBUM, true, accepted, null); accepted.await(); f.await();
            check(f.batch.state().completed == 1 && f.batch.state().reused == 1 && PhotoLiveGallery.rows.size() == 1 && store.read(draft.sourceSha).exported,
                    "Retry completes draft history without making a second explicit new phone copy");
        } finally { PhotoLiveGallery.beforePublish = null; }
    }
    private static final class Fixture {
        final Context context; final AlbumController album=new AlbumController(); final PhotoPresetBatch batch; final PresetRecord preset; final Path originals;
        Fixture(Path root)throws Exception{ Files.createDirectories(root); context=new Context(root.toFile()); originals=root.resolve("external/Pictures/camera-album");Files.createDirectories(originals); preset=new PresetLibrary(context.getFilesDir()).importFile("fixture.ncp",NcpCompatibilityFixtures.ncp()).record; batch=new PhotoPresetBatch(context,album);PhotoLiveGallery.rows.clear();PhotoLiveGallery.failDerived=false;resetObservations();android.graphics.BitmapFactory.failDecode=false; }
        DownloadedPhoto original(String name)throws IOException{Path file=originals.resolve(name);Files.write(file,JPEG);return new DownloadedPhoto(file.toFile(),name);}
        File seed(DownloadedPhoto source)throws Exception{
            String key=PhotoPresetBatch.key(hex(MessageDigest.getInstance("SHA-256").digest(JPEG)),preset.sha256); File output=originals.resolve("effect-"+key+"__Effect.JPG").toFile();Files.write(output.toPath(),JPEG);
            Path records=context.getFilesDir().toPath().resolve("photo-preset-results");Files.createDirectories(records);
            try(DataOutputStream data=new DataOutputStream(Files.newOutputStream(records.resolve(key+".record")))){data.writeInt(0x50424531);data.writeLong(JPEG.length);data.writeUTF(hex(MessageDigest.getInstance("SHA-256").digest(JPEG)));}return output;
        }
        DownloadedPhoto historicEffect(DownloadedPhoto source,PresetRecord used,byte[] bytes)throws Exception{
            String key=PhotoPresetBatch.key(hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source.file().toPath()))),used.sha256);
            Path output=originals.resolve("effect-"+key+"__Effect.JPG");Files.write(output,bytes);
            Path records=context.getFilesDir().toPath().resolve("photo-preset-results");Files.createDirectories(records);
            try(DataOutputStream data=new DataOutputStream(Files.newOutputStream(records.resolve(key+".record")))){data.writeInt(0x50424531);data.writeLong(bytes.length);data.writeUTF(hex(MessageDigest.getInstance("SHA-256").digest(bytes)));}
            DownloadedPhoto effect=new DownloadedPhoto(output.toFile(),"Historic.JPG");
            new PhotoPresetSources(context.getFilesDir(),context.getExternalFilesDir("Pictures")).bind(effect,source,used.sha256);return effect;
        }
        List<PresetRecord> presets()throws Exception{Result<List<PresetRecord>> result=new Result<>();batch.listPresets(result);return result.await();}
        void start(List<PhotoPresetBatch.Source> sources)throws Exception{start(sources,preset.id);}
        void start(List<PhotoPresetBatch.Source> sources,String presetId)throws Exception{Result<Void> result=new Result<>(){public void onSuccess(Void value){check(batch.state().active,"Accepted start marks active before acceptance callback");super.onSuccess(value);}};batch.start(sources,presetId,result);result.await();}
        void resetObservations(){PhotoLiveGallery.publications.clear();android.graphics.BitmapFactory.decodedPaths.clear();}
        long effectCount()throws IOException{try(var files=Files.list(originals)){return files.filter(path->path.getFileName().toString().startsWith("effect-")).count();}}
        void await()throws Exception{until(()->!batch.state().active);check(batch.state().completed+batch.state().failed+batch.state().cancelled==batch.state().total,"Every item has exactly one terminal outcome");}
    }
    private static class Result<T>implements AlbumController.Callback<T>{final CountDownLatch done=new CountDownLatch(1);volatile T result;volatile String error;public void onSuccess(T value){result=value;done.countDown();}public void onError(String value){error=value;done.countDown();}T await()throws Exception{if(!done.await(5,TimeUnit.SECONDS))throw new AssertionError("callback timeout");if(error!=null)throw new AssertionError(error);return result;}}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format("%02x",b&255));return value.toString();}
    private static void until(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!condition.getAsBoolean()){if(System.nanoTime()>deadline)throw new AssertionError("state timeout");Thread.sleep(10);}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}

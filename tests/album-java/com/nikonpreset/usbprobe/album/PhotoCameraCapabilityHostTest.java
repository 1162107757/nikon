package com.nikonpreset.usbprobe.album;

import android.content.Context;
import com.nikonpreset.usbprobe.ptp.PtpDeviceInfo;
import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/** Controller capability boundaries with a synthetic read-only photo transport. */
public final class PhotoCameraCapabilityHostTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("photo-camera-capabilities-");
        try {
            strictIdentityUsesTheSelectedTransport();
            fujiPhotosStayReadOnlyAndDoNotStartLive(root);
            fujiPageScopesRestoreOnFailureAndCancellation(root);
            System.out.println("PASS: " + checks + " camera capability controller checks");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void strictIdentityUsesTheSelectedTransport() throws Exception {
        FakeClient fuji = new FakeClient();
        validateIdentity(fuji, "FUJIFILM", "X-T20");
        check(true, "Selected Fuji transport accepts X-T20 identity");
        for (String[] identity : new String[][] {{"Other FUJIFILM", "X-T20"}, {"FUJIFILM", "X-T30"}, {"Nikon", "X-T20"}}) {
            try {
                validateIdentity(fuji, identity[0], identity[1]);
                throw new AssertionError("Foreign manufacturer/model was accepted");
            } catch (IOException expected) {
                check(expected.getMessage().contains("X-T20"), "Fuji identity mismatch explains supported model");
            }
        }
        try (PtpIpClient nikon = new PtpIpClient((h, p, t) -> { throw new IOException("No test socket"); })) {
            validateIdentity(nikon, "Nikon Corporation", "Z6II");
            check(true, "Nikon manufacturer matching remains compatible");
            try {
                validateIdentity(nikon, "FUJIFILM", "X-T20");
                throw new AssertionError("Nikon transport accepted Fuji identity");
            } catch (IOException expected) { check(expected.getMessage().contains("尼康"), "Nikon transport retains manufacturer gate"); }
        }
    }

    private static void fujiPhotosStayReadOnlyAndDoNotStartLive(Path root) throws Exception {
        FakeClient client = new FakeClient();
        try (AlbumController controller = new AlbumController(new Context(root.toFile()))) {
            ConnectionInfo connected = new ConnectionInfo("X-T20", "192.168.0.1", 55740, 1, CameraProtocol.FUJIFILM_X_T20);
            set(controller, "active", client);
            set(controller, "sessionConnectionInfo", connected);
            set(controller, "cachedConnectionInfo", connected);
            set(controller, "deviceInfo", PtpDeviceInfo.parseRedacted(info("FUJIFILM", "X-T20")));
            set(controller, "session", "fuji-host");
            PhotoItem photo = new PhotoItem("fuji-host", 42, client.object);
            check(controller.hasLiveSession() && controller.isConnected(), "Read-only Fuji photo session is available");
            check(!controller.supportsPhotoLive() && !controller.supportsPictureControl(), "Fuji session declares only supported capabilities");

            Result<byte[]> thumb = new Result<>(); controller.loadThumbnail(photo, thumb);
            check(Arrays.equals(thumb.value(), client.jpeg), "Fuji thumbnail reads standard GetThumb bytes");
            check(client.largeThumbs == 0 && client.operations.equals(List.of(0x100a)), "Fuji never sends Nikon vendor preview even if advertised");

            Result<DownloadedPhoto> original = new Result<>();
            controller.download(photo, new AlbumController.DownloadCallback() {
                public void onSuccess(DownloadedPhoto value) { original.onSuccess(value); }
                public void onError(String error) { original.onError(error); }
                public void onProgress(long done, long total) { }
            });
            check(Arrays.equals(Files.readAllBytes(original.value().file().toPath()), client.jpeg), "Fuji download retains the complete verified JPEG");
            check(client.operations.stream().filter(op -> op == 0x1009).count() == 1, "Explicit Fuji download reads one original");

            int generation = ((AtomicInteger) get(controller, "generation")).get();
            int previewGeneration = ((AtomicInteger) get(controller, "previewGeneration")).get();
            List<Integer> before = List.copyOf(client.operations);
            Result<Void> live = new Result<>(); controller.startPhotoLive("must-not-exist", live);
            check(live.error().contains("边拍边传"), "Fuji live start explains unsupported shooting mode");
            Result<Void> resume = new Result<>(); controller.resumePhotoLive("missing-project", resume);
            check(resume.error().contains("照片直播"), "Fuji live continuation stops before reading a project");
            Result<PtpIpClient> handoff = new Result<>(); controller.transferForPictureControl(handoff);
            check(handoff.error().contains("尼康预设"), "Fuji refuses Nikon preset session handoff");
            check(before.equals(client.operations), "Unsupported live/import operations do not query the camera");
            check(controller.hasLiveSession() && controller.isConnected() && client.open, "Unsupported actions preserve the usable photo connection");
            check(generation == ((AtomicInteger) get(controller, "generation")).get()
                    && previewGeneration == ((AtomicInteger) get(controller, "previewGeneration")).get(), "Refused import does not retire browsing callbacks");
            check(!Files.exists(root.resolve("files/photo-live-projects")), "Refused live start creates no project directory");

            Result<Void> browsedKeepAlive = new Result<>(); controller.keepAlive(browsedKeepAlive); browsedKeepAlive.value();
            check(client.deviceInfoReads == 1, "Fuji browsed-session keepalive sends an actual transport read rather than synthetic storage enumeration");
            set(controller, "cachedConnectionInfo", null);
            Result<Void> keepAlive = new Result<>(); controller.keepAlive(keepAlive); keepAlive.value();
            check(controller.hasLiveSession() && client.deviceInfoReads == 2, "Fuji identity remains valid during session-only keepalive");
        }
        check(!client.open, "Controller shutdown closes its Fuji photo transport");
    }

    private static void fujiPageScopesRestoreOnFailureAndCancellation(Path root) throws Exception {
        FakeClient client = new FakeClient();
        try (AlbumController controller = new AlbumController(new Context(root.resolve("pages").toFile()))) {
            set(controller, "active", client); set(controller, "session", "pages");
            set(controller, "handles", new long[]{1, 2, 3, 4, 5, 6, 7});
            Result<PhotoPage> page = new Result<>(); controller.loadPage(0, 6, page);
            check(page.value().items().size() == 6 && page.value().nextOffset() == 6, "small Fuji page returns six metadata entries");
            check(client.pageBegins == 1 && client.pageEnds == 1, "controller shares one transport page scope");
            client.failMetadata = true;
            Result<PhotoPage> failure = new Result<>(); controller.loadPage(0, 6, failure);
            check(failure.error().contains("synthetic metadata"), "metadata failure remains visible");
            check(client.pageBegins == 2 && client.pageEnds == 2, "failed metadata restores page negotiation");
            client.failMetadata = false; client.onMetadata = controller::cancelBrowseRequests;
            int before = client.operations.size();
            Result<PhotoPage> cancelled = new Result<>(); controller.loadPage(0, 6, cancelled);
            check(cancelled.error().contains("取消"), "browse cancellation is delivered");
            check(client.operations.size() == before + 1 && client.pageBegins == 3 && client.pageEnds == 3,
                    "cancelled page drains one metadata read, restores transport, then yields");
        }
    }

    private static void validateIdentity(PhotoCameraClient client, String make, String model) throws Exception {
        Method method = AlbumController.class.getDeclaredMethod("validateCameraIdentity", PhotoCameraClient.class, PtpDeviceInfo.class);
        method.setAccessible(true);
        try { method.invoke(null, client, PtpDeviceInfo.parseRedacted(info(make, model))); }
        catch (InvocationTargetException error) { if (error.getCause() instanceof IOException) throw (IOException) error.getCause(); throw error; }
    }
    private static Object get(Object owner, String name) throws Exception { Field field = AlbumController.class.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static void set(Object owner, String name, Object value) throws Exception { Field field = AlbumController.class.getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); checks++; }

    private static final class Result<T> implements AlbumController.Callback<T> {
        final CountDownLatch done = new CountDownLatch(1); T value; String failure;
        public void onSuccess(T result) { value = result; done.countDown(); }
        public void onError(String message) { failure = message; done.countDown(); }
        T value() throws Exception { await(); if (failure != null) throw new AssertionError(failure); return value; }
        String error() throws Exception { await(); if (failure == null) throw new AssertionError("Expected capability refusal"); return failure; }
        void await() throws Exception { if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("Callback timed out"); }
    }

    private static final class FakeClient implements PhotoCameraClient {
        final byte[] jpeg = {(byte) 255, (byte) 216, 1, 2, 3, 4, (byte) 255, (byte) 217};
        final PtpObjectInfo object = new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).filename("DSCF0042.JPG").compressedSize(jpeg.length).build();
        final List<Integer> operations = new ArrayList<>();
        boolean open = true; int largeThumbs, deviceInfoReads;
        int pageBegins, pageEnds; boolean failMetadata; Runnable onMetadata;
        public void beginPhotoPage() { pageBegins++; }
        public void endPhotoPage() { pageEnds++; }
        public byte[] connect(String host, int port, byte[] guid, String name) { return info("FUJIFILM", "X-T20"); }
        public boolean isOpen() { return open; }
        public boolean isProtocolUnconfirmed() { return false; }
        public String closeReason() { return ""; }
        public String connectedHost() { return "192.168.0.1"; }
        public int connectedPort() { return 55740; }
        public String cameraIdentity() { return ""; }
        public void setOnClosed(Runnable listener) { }
        public void setOnObjectAdded(LongConsumer listener) { }
        public byte[] readAlbumDeviceInfo() { deviceInfoReads++; return info("FUJIFILM", "X-T20"); }
        public byte[] getData(int operation, int maximum, long... parameters) throws IOException {
            operations.add(operation);
            if (operation == 0x1008) {
                if (failMetadata) throw new IOException("synthetic metadata failure");
                if (onMetadata != null) onMetadata.run();
                return object.toBytes();
            }
            if (operation == 0x100a) return jpeg.clone();
            if (operation == 0x1004) return concat(le32(1), le32(1));
            throw new IOException("Unexpected synthetic operation " + Integer.toHexString(operation));
        }
        public byte[] getLargeThumbnail(long handle) throws IOException { largeThumbs++; throw new IOException("Nikon operation forbidden"); }
        public long readTo(int operation, OutputStream target, long maximum, long expected, Progress progress, long... parameters) throws IOException {
            if (operation != 0x1009) throw new IOException("Unexpected synthetic original operation");
            operations.add(operation); target.write(jpeg); if (progress != null) progress.onProgress(jpeg.length, jpeg.length); return jpeg.length;
        }
        public void abortProtocol(IOException error) { open = false; }
        public void close() { open = false; }
    }

    private static byte[] info(String make, String model) {
        int[] operations = {0x1001, 0x1002, 0x1003, 0x1004, 0x1007, 0x1008, 0x1009, 0x100a, 0x90c4};
        ByteArrayOutputStream ops = new ByteArrayOutputStream(); ops.writeBytes(le32(operations.length));
        for (int operation : operations) ops.writeBytes(le16(operation));
        return concat(le16(100), le32(14), le16(100), string("Host fixture"), le16(0), ops.toByteArray(),
                le32(0), le32(0), le32(0), le32(0), string(make), string(model), string("1"), string(""));
    }
    private static byte[] string(String value) { return value.isEmpty() ? new byte[] {0} : concat(new byte[] {(byte) (value.length() + 1)}, (value + '\0').getBytes(StandardCharsets.UTF_16LE)); }
    private static byte[] le16(long value) { return new byte[] {(byte) value, (byte) (value >>> 8)}; }
    private static byte[] le32(long value) { return new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)}; }
    private static byte[] concat(byte[]... values) { ByteArrayOutputStream result = new ByteArrayOutputStream(); for (byte[] value : values) result.writeBytes(value); return result.toByteArray(); }
}

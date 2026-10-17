package com.nikonpreset.usbprobe.album;

import android.content.Context;
import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Production asynchronous controller + PTP/IP client against framed synthetic camera sockets. */
public final class AlbumControllerHostTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("album-controller-host-");
        try {
            previewAndOriginalStaySeparate(root.resolve("normal"));
            largeOriginalDoesNotBlockThumbnail(root.resolve("large-original"));
            missingThumbnailDoesNotFetchOriginal(root.resolve("unsupported"));
            rejectedThumbnailDoesNotFetchOriginal(root.resolve("rejected"));
            incompleteThumbnailDoesNotFetchOriginal(root.resolve("incomplete"));
            oversizedThumbnailIsBounded(root.resolve("oversized"));
            advertisedLargePreviewImprovesGridAndViewerWithoutOriginal(root.resolve("large-preview"));
            largePreviewFallsBackOnlyForKnownRejections(root.resolve("large-preview-fallback"));
            busyAndInvalidLargePreviewDoNotFetchAnotherRepresentation(root.resolve("large-preview-failures"));
            malformedAndOversizedLargePreviewCloseTheBoundary(root.resolve("large-preview-protocol"));
            disconnectedDownloadNeverCopiesThumbnail(root.resolve("disconnected"));
            handoffDrainsInflightThumbnailAndRetiresOldCallbacks(root.resolve("handoff-drain"));
            handoffDiscardsAnInflightPreviewPin(root.resolve("handoff-preview"));
            cancelledQueuedHandoffClosesTheOriginalConnection(root.resolve("handoff-cancel"));
            throwingHandoffCallbackCannotLeakTheTransferredOwner(root.resolve("handoff-throw"));
            backgroundKeepAlivePreservesTheSnapshotAndPagingCursor(root.resolve("keepalive"));
            sharedMetadataAndLifecycleObserverOutliveTheUiListener(root.resolve("shared-listener"));
            eventDisconnectClearsSharedMetadataWithoutAUiListener(root.resolve("shared-event"));
            keepAliveRejectsInvalidStorageListsWithoutEnumeratingPhotos(root.resolve("keepalive-invalid"));
            keepAliveWaitsForInflightReadAndRespectsCancellation(root.resolve("keepalive-queue"));
            sharedConnectingStateTerminatesOnSuccessFailureAndCancellation(root.resolve("shared-connecting"));
            matchingCameraBusyPreservesTheAlbumForTheNextKeepAlive(root.resolve("keepalive-busy"));
            obsoleteKeepAliveFailureCannotClearAReplacementConnection(root.resolve("keepalive-replacement"));
            System.out.println("PASS: " + checks + " production album controller checks");
        } finally {
            CameraWifiNetwork.testConnector = null;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) {
                        throw new AssertionError("cleanup escaped test directory");
                    }
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void previewAndOriginalStaySeparate(Path root) throws Exception {
        Peer peer = new Peer();
        try (AlbumController controller = connected(root, peer)) {
            PhotoItem photo = firstPhoto(controller);
            byte[] grid = call(callback -> controller.loadThumbnail(photo, callback));
            check(Arrays.equals(grid, peer.thumbnail), "Grid reads camera thumbnail bytes");
            check(photo.thumbnailByteSize() != peer.thumbnail.length, "Fixture distinguishes advertised and framed thumbnail sizes");
            try (PhotoPreview preview = preview(controller, photo)) {
                check(Arrays.equals(Files.readAllBytes(preview.file().toPath()), peer.thumbnail), "Immersive preview contains exactly GetThumb bytes");
                check(preview.byteSize() == peer.thumbnail.length, "Preview reports actual thumbnail length");
                check(peer.count(0x1009) == 0, "Opening a photo never requests GetObject");
                List<DownloadedPhoto> before = call(controller::listDownloads);
                check(before.isEmpty(), "Viewing does not add a downloaded photo");
                int thumbsBefore = peer.count(0x100a);
                try (PhotoPreview cached = preview(controller, photo)) {
                    check(cached.file().equals(preview.file()), "Repeated viewing reuses pinned thumbnail cache");
                    check(peer.count(0x100a) == thumbsBefore, "Cached viewer makes no duplicate thumbnail request");
                }
                DownloadedPhoto downloaded = download(controller, photo);
                check(peer.count(0x1009) == 1, "Explicit download requests exactly one GetObject");
                byte[] saved = Files.readAllBytes(downloaded.file().toPath());
                check(Arrays.equals(saved, peer.original), "Download stores full original response bytes");
                check(!Arrays.equals(saved, peer.thumbnail), "A thumbnail can never be copied into downloads as an original");
                check(downloaded.byteSize() == photo.byteSize(), "Downloaded original matches original metadata size");
                check(preview.file().exists(), "Original transfer preserves the displayed thumbnail pin");
                List<DownloadedPhoto> after = call(controller::listDownloads);
                check(after.size() == 1 && after.get(0).byteSize() == peer.original.length, "Only explicit original download appears in downloads");
            }
        }
    }

    private static void largeOriginalDoesNotBlockThumbnail(Path root) throws Exception {
        Peer peer = new Peer(); peer.originalSize = 100L * 1024 * 1024;
        try (AlbumController controller = connected(root, peer)) {
            PhotoItem photo = firstPhoto(controller);
            try (PhotoPreview preview = preview(controller, photo)) {
                check(preview.byteSize() == peer.thumbnail.length, "A 100 MiB original still opens as a small thumbnail");
                check(peer.count(0x100a) == 1 && peer.count(0x1009) == 0, "Large original preview uses only GetThumb");
            }
        }
    }

    private static void missingThumbnailDoesNotFetchOriginal(Path root) throws Exception {
        Peer peer = new Peer(); peer.advertiseThumbnail = false;
        try (AlbumController controller = connected(root, peer)) {
            PhotoItem photo = firstPhoto(controller);
            check(previewError(controller, photo).contains("缩略图"), "Missing GetThumb produces a clear preview error");
            check(peer.count(0x100a) == 0 && peer.count(0x1009) == 0, "Unsupported thumbnail causes no fallback transfer");
            check(Arrays.equals(Files.readAllBytes(download(controller, photo).file().toPath()), peer.original),
                    "An explicit original download still works without thumbnail support");
        }
    }

    private static void rejectedThumbnailDoesNotFetchOriginal(Path root) throws Exception {
        Peer peer = new Peer(); peer.rejectThumbnail = true;
        try (AlbumController controller = connected(root, peer)) {
            PhotoItem photo = firstPhoto(controller);
            check(previewError(controller, photo).contains("2005"), "Camera GetThumb rejection reaches the viewer");
            check(peer.count(0x100a) == 1 && peer.count(0x1009) == 0, "Camera thumbnail rejection never falls back to GetObject");
            download(controller, photo);
            check(peer.count(0x1009) == 1, "Original is transferred only after explicit download following preview failure");
        }
    }

    private static void incompleteThumbnailDoesNotFetchOriginal(Path root) throws Exception {
        Peer peer = new Peer(); peer.thumbnail[peer.thumbnail.length - 1] = 0;
        try (AlbumController controller = connected(root, peer)) {
            PhotoItem photo = firstPhoto(controller);
            check(previewError(controller, photo).contains("完整"), "Missing thumbnail JPEG end marker is rejected");
            check(peer.count(0x1009) == 0, "Invalid thumbnail never triggers original fallback");
            List<DownloadedPhoto> downloads = call(controller::listDownloads);
            check(downloads.isEmpty(), "Invalid previews leave downloads empty");
            peer.thumbnail = jpeg(32, 3);
            try (PhotoPreview preview = preview(controller, photo)) {
                check(preview.byteSize() == 32 && peer.count(0x100a) == 2, "Invalid preview was not cached and can be retried");
            }
        }
    }

    private static void oversizedThumbnailIsBounded(Path root) throws Exception {
        Peer peer = new Peer(); peer.declaredThumbnailSize = 4L * 1024 * 1024 + 1;
        try (AlbumController controller = connected(root, peer)) {
            check(!previewError(controller, firstPhoto(controller)).isEmpty(), "Declared thumbnail over 4 MiB is rejected");
            check(peer.count(0x100a) == 1 && peer.count(0x1009) == 0, "Oversized thumbnail is bounded without original fallback");
            check(!controller.isConnected(), "Oversized framing closes the unconfirmed stream safely");
        }
    }

    private static void disconnectedDownloadNeverCopiesThumbnail(Path root) throws Exception {
        Peer peer = new Peer();
        try (AlbumController controller = connected(root, peer)) {
            PhotoItem photo = firstPhoto(controller);
            try (PhotoPreview preview = preview(controller, photo)) {
                controller.disconnect();
                DownloadResult result = new DownloadResult(); controller.download(photo, result);
                check(result.error().contains("断开"), "Original download after disconnect asks to reconnect");
                check(peer.count(0x1009) == 0, "Disconnected download cannot transfer an original");
                check(preview.file().exists(), "Disconnected download does not destroy the current preview");
                List<DownloadedPhoto> downloads = call(controller::listDownloads);
                check(downloads.isEmpty(), "Cached thumbnail is never saved as a substitute original while offline");
            }
        }
    }

    private static void advertisedLargePreviewImprovesGridAndViewerWithoutOriginal(Path root) throws Exception {
        Peer peer = new Peer(); peer.advertiseLargeThumbnail = true; peer.advertiseThumbnail = false;
        try (AlbumController controller = connected(root, peer)) {
            PhotoItem photo = firstPhoto(controller);
            byte[] grid = call(callback -> controller.loadThumbnail(photo, callback));
            check(Arrays.equals(grid, peer.largeThumbnail), "Visible grid receives the larger camera-generated preview");
            try (PhotoPreview first = preview(controller, photo); PhotoPreview cached = preview(controller, photo)) {
                check(Arrays.equals(Files.readAllBytes(first.file().toPath()), peer.largeThumbnail), "Viewer caches exact GetLargeThumb bytes");
                check(first.isLargeThumbnail() && cached.isLargeThumbnail(), "Viewer source metadata survives cache reuse");
                check(peer.count(0x90c4) == 2, "Grid and first viewer read large previews while repeated viewer reuses cache");
                check(peer.count(0x100a) == 0 && peer.count(0x1009) == 0, "Large preview never fetches a standard thumbnail or original");
                check(call(controller::listDownloads).isEmpty(), "A large preview does not appear as a downloaded original");
            }
            Result<Void> pulse = new Result<>(); controller.keepAlive(pulse); pulse.value();
            check(controller.isConnected(), "Large-preview viewing preserves shared connection and heartbeat");
            DownloadedPhoto saved = download(controller, photo);
            check(Arrays.equals(Files.readAllBytes(saved.file().toPath()), peer.original), "Explicit download remains the full original");
        }
    }

    private static void largePreviewFallsBackOnlyForKnownRejections(Path root) throws Exception {
        for (int code : new int[] {0x2005, 0x2010}) {
            Peer peer = new Peer(); peer.advertiseLargeThumbnail = true; peer.largeThumbnailResponseCode = code;
            try (AlbumController controller = connected(root.resolve(Integer.toHexString(code)), peer)) {
                PhotoItem photo = firstPhoto(controller);
                byte[] first = call(callback -> controller.loadThumbnail(photo, callback));
                byte[] second = call(callback -> controller.loadThumbnail(photo, callback));
                check(Arrays.equals(first, peer.thumbnail) && Arrays.equals(second, peer.thumbnail), "Explicit unsupported/no-thumbnail response permits GetThumb fallback");
                check(peer.count(0x90c4) == (code == 0x2005 ? 1 : 2), "Unsupported large operation is disabled only for this session; missing one thumbnail is not global");
                try (PhotoPreview preview = preview(controller, photo)) {
                    check(!preview.isLargeThumbnail(), "Fallback preview reports standard thumbnail provenance");
                }
                check(peer.count(0x1009) == 0 && controller.isConnected(), "Known fallback never downloads the original or closes a valid session");
            }
        }
    }

    private static void busyAndInvalidLargePreviewDoNotFetchAnotherRepresentation(Path root) throws Exception {
        for (int code : new int[] {0x2002, 0x2009, 0x2014, 0x2019}) {
            Peer peer = new Peer(); peer.advertiseLargeThumbnail = true; peer.largeThumbnailResponseCode = code;
            try (AlbumController controller = connected(root.resolve(Integer.toHexString(code)), peer)) {
                PhotoItem photo = firstPhoto(controller);
                check(previewError(controller, photo).contains(String.format("%04X", code)), "Ambiguous/busy/error response reaches the viewer without fallback");
                check(peer.count(0x100a) == 0 && peer.count(0x1009) == 0, "Error does not trigger another representation or original download");
                check(controller.isConnected(), "A fully matched camera rejection leaves framing usable");
                peer.largeThumbnailResponseCode = 0x2001;
                try (PhotoPreview recovered = preview(controller, photo)) {
                    check(recovered.isLargeThumbnail(), "An explicit retry after camera recovery still uses large previews");
                }
            }
        }
        Peer peer = new Peer(); peer.advertiseLargeThumbnail = true; peer.largeThumbnail[peer.largeThumbnail.length - 1] = 0;
        try (AlbumController controller = connected(root.resolve("invalid-jpeg"), peer)) {
            check(previewError(controller, firstPhoto(controller)).contains("不完整"), "Invalid JPEG is reported without claiming a high-quality preview");
            check(peer.count(0x100a) == 0 && peer.count(0x1009) == 0, "Bad large JPEG does not silently fetch other data");
        }
    }

    private static void malformedAndOversizedLargePreviewCloseTheBoundary(Path root) throws Exception {
        for (boolean oversized : new boolean[] {false, true}) {
            Peer peer = new Peer(); peer.advertiseLargeThumbnail = true;
            if (oversized) peer.declaredLargeThumbnailSize = 8L * 1024 * 1024 + 1;
            else peer.largeThumbnailWrongTransaction = true;
            try (AlbumController controller = connected(root.resolve(oversized ? "oversized" : "wrong-tx"), peer)) {
                check(!previewError(controller, firstPhoto(controller)).isEmpty(), "Invalid large-preview framing produces an error");
                check(!controller.isConnected(), "Wrong transaction/oversized preview retires the invalid boundary");
                check(peer.count(0x90c4) == 1 && peer.count(0x100a) == 0 && peer.count(0x1009) == 0,
                        "A broken boundary never sends a fallback or original read");
            }
        }
    }

    private static void handoffDrainsInflightThumbnailAndRetiresOldCallbacks(Path root) throws Exception {
        Peer peer = new Peer();
        AlbumController controller = connected(root, peer);
        PtpIpClient transferred = null;
        try {
            PhotoItem photo = firstPhoto(controller);
            peer.pauseThumbnail = true;
            AtomicInteger oldCallbacks = new AtomicInteger();
            controller.loadThumbnail(photo, counting(oldCallbacks));
            check(peer.thumbnailPaused.await(5, TimeUnit.SECONDS), "Thumbnail pauses after receiving part of its framed response");
            controller.loadThumbnail(photo, counting(oldCallbacks)); // Queued before retirement; must never reach the camera.
            Result<PtpIpClient> handoff = new Result<>();
            controller.transferForPictureControl(new AlbumController.Callback<>() {
                @Override public void onSuccess(PtpIpClient value) {
                    check(peer.thumbnailDrained.getCount() == 0, "Handoff waits for the full in-flight data and response frames");
                    handoff.onSuccess(value);
                }
                @Override public void onError(String message) { handoff.onError(message); }
            });
            check(!handoff.isDone(), "A partial thumbnail cannot be handed to a new command owner");
            check(peer.count(0x100a) == 1, "Only the in-flight thumbnail reached the camera before the barrier");
            check(peer.openSocketCount() == 2, "Transfer setup does not close either PTP/IP channel");
            peer.releaseThumbnail.countDown();
            transferred = handoff.value();
            check(oldCallbacks.get() == 0, "Neither in-flight nor old queued thumbnail callbacks survive retirement");
            check(peer.count(0x100a) == 1, "The old queued thumbnail is suppressed before sending a request");
            check(!controller.isConnected() && transferred.isOpen(), "Only the transferred owner remains connected");
            check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                    "Handoff reuses exactly two sockets and one OpenSession without CloseSession");
            long next = transferred.claimTransferredSession();
            check(next == 5, "Handoff continues after enumeration, metadata and the completed thumbnail transaction");
            transferred.getPictureControlData(0x1001, next);
            check(peer.lastTransaction() == next, "The first Picture Control query consumes the inherited next transaction");
            int beforeOldPhoto = peer.operationCount();
            Result<byte[]> oldPhoto = new Result<>(); controller.loadThumbnail(photo, oldPhoto);
            check(oldPhoto.error().contains("断开"), "An old photo cannot use the retired album controller");
            check(peer.operationCount() == beforeOldPhoto, "The retired photo request emits no camera command");
            controller.close();
            check(transferred.isOpen() && peer.openSocketCount() == 2, "Closing the old controller cannot close the transferred owner");
            transferred.getPictureControlData(0x1001, next + 1);
            check(peer.lastTransaction() == next + 1, "The new owner remains usable after old controller cleanup");
        } finally {
            peer.releaseThumbnail.countDown();
            controller.close();
            if (transferred != null) transferred.close();
            check(peer.openSocketCount() == 0, "The final owner releases both channels exactly at cleanup");
        }
    }

    private static void handoffDiscardsAnInflightPreviewPin(Path root) throws Exception {
        Peer peer = new Peer();
        AlbumController controller = connected(root, peer);
        PtpIpClient transferred = null;
        try {
            PhotoItem photo = firstPhoto(controller);
            peer.pauseThumbnail = true;
            AtomicInteger callbacks = new AtomicInteger();
            controller.loadPreview(photo, new AlbumController.PreviewCallback() {
                @Override public void onSuccess(PhotoPreview value) { callbacks.incrementAndGet(); value.close(); }
                @Override public void onError(String message) { callbacks.incrementAndGet(); }
                @Override public void onProgress(long received, long total) { callbacks.incrementAndGet(); }
            });
            check(peer.thumbnailPaused.await(5, TimeUnit.SECONDS), "Viewer preview has an in-flight thumbnail at handoff");
            Result<PtpIpClient> handoff = new Result<>(); controller.transferForPictureControl(handoff);
            peer.releaseThumbnail.countDown();
            transferred = handoff.value();
            check(callbacks.get() == 0, "Retired viewer receives no success, progress or failure callback");
            controller.close();
            try (var files = Files.walk(root)) {
                check(files.noneMatch(path -> Files.isRegularFile(path) && (path.toString().endsWith(".jpg") || path.toString().endsWith(".part"))),
                        "Discarding the obsolete preview releases its cache pin and temporary file");
            }
            check(transferred.isOpen(), "Releasing the old preview cache preserves the transferred network owner");
        } finally {
            peer.releaseThumbnail.countDown(); controller.close();
            if (transferred != null) transferred.close();
        }
    }

    private static void cancelledQueuedHandoffClosesTheOriginalConnection(Path root) throws Exception {
        Peer peer = new Peer();
        AlbumController controller = connected(root, peer);
        try {
            PhotoItem photo = firstPhoto(controller);
            peer.pauseThumbnail = true;
            AtomicInteger callbacks = new AtomicInteger();
            controller.loadThumbnail(photo, counting(callbacks));
            check(peer.thumbnailPaused.await(5, TimeUnit.SECONDS), "Cancellation starts while the old thumbnail is in flight");
            controller.transferForPictureControl(counting(callbacks));
            controller.disconnect();
            peer.releaseThumbnail.countDown();
            // This worker barrier is queued after the obsolete transfer task.
            List<DownloadedPhoto> downloads = call(controller::listDownloads);
            check(downloads.isEmpty(), "Cancellation does not create a local photo");
            check(callbacks.get() == 0, "Cancelled read and queued transfer callbacks are both suppressed");
            check(!controller.isConnected() && peer.openSocketCount() == 0, "Cancelling before handoff leaves no original or transferred socket owner");
            check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x90cd) == 0,
                    "Cancelling transfer does not reconnect, reopen or write");
        } finally { peer.releaseThumbnail.countDown(); controller.close(); }
    }

    private static void throwingHandoffCallbackCannotLeakTheTransferredOwner(Path root) throws Exception {
        Peer peer = new Peer();
        AlbumController controller = connected(root, peer);
        try {
            AtomicInteger successes = new AtomicInteger();
            CountDownLatch failed = new CountDownLatch(1);
            controller.transferForPictureControl(new AlbumController.Callback<>() {
                @Override public void onSuccess(PtpIpClient owner) { successes.incrementAndGet(); throw new IllegalStateException("synthetic receiver failure"); }
                @Override public void onError(String message) { failed.countDown(); }
            });
            check(failed.await(5, TimeUnit.SECONDS), "A failed owner receiver reaches guarded handoff cleanup");
            check(successes.get() == 1 && !controller.isConnected() && peer.openSocketCount() == 0,
                    "Throwing out of the transfer callback cannot leak a transferred connection");
            check(peer.sockets == 2 && peer.count(0x1002) == 1, "Receiver failure cannot cause implicit reconnect");
        } finally { controller.close(); }
    }

    private static <T> AlbumController.Callback<T> counting(AtomicInteger callbacks) {
        return new AlbumController.Callback<>() {
            @Override public void onSuccess(T value) { callbacks.incrementAndGet(); if (value instanceof PtpIpClient client) client.close(); }
            @Override public void onError(String message) { callbacks.incrementAndGet(); }
        };
    }

    private static void backgroundKeepAlivePreservesTheSnapshotAndPagingCursor(Path root) throws Exception {
        Peer peer = new Peer();
        peer.photoHandles = new long[] {42, 43, 44};
        try (AlbumController controller = connected(root, peer)) {
            ConnectionInfo cached = controller.connectionInfo();
            check(cached != null && cached.totalHandles() == 3 && cached.host().equals("camera.test") && cached.port() == 15740,
                    "The shared controller exposes complete cached connection metadata");
            PhotoPage first = call(callback -> controller.loadPage(0, 1, callback));
            check(first.items().size() == 1 && first.items().get(0).handle() == 42 && first.nextOffset() == 1 && first.hasMore(),
                    "The first page has a continuation cursor in the original handle snapshot");
            int before = peer.operationCount();
            int enumerations = peer.count(0x1007);
            int metadataReads = peer.count(0x1008);
            peer.photoHandles = new long[] {99}; // A keepalive must not replace the existing album snapshot.
            call(controller::keepAlive);
            check(peer.operationCount() == before + 1 && peer.count(0x1004) == 2,
                    "Background keepalive sends exactly one bounded GetStorageIDs read");
            check(peer.count(0x1007) == enumerations && peer.count(0x1008) == metadataReads,
                    "Keepalive does not enumerate photos or prefetch their metadata");
            check(peer.count(0x1009) == 0 && peer.count(0x100a) == 0 && peer.count(0x90cd) == 0,
                    "Keepalive never downloads originals, thumbnails or writes camera settings");
            check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                    "Background keepalive reuses the existing physical session");
            int beforeCachedRead = peer.operationCount();
            ConnectionInfo restored = controller.connectionInfo();
            check(restored != null && restored.model().equals(cached.model()) && restored.totalHandles() == 3,
                    "A recreated view can restore the original metadata from the shared controller");
            check(peer.operationCount() == beforeCachedRead, "Reading cached connection metadata performs no camera I/O");
            PhotoPage second = call(callback -> controller.loadPage(first.nextOffset(), 1, callback));
            check(second.items().size() == 1 && second.items().get(0).handle() == 43 && second.nextOffset() == 2 && second.hasMore(),
                    "Keepalive preserves the exact continuation cursor and original handle ordering");
            check(second.items().get(0).session.equals(first.items().get(0).session),
                    "Keepalive does not silently create a replacement album session");
        }
    }

    private static void sharedMetadataAndLifecycleObserverOutliveTheUiListener(Path root) throws Exception {
        Peer peer = new Peer();
        CameraWifiNetwork.testConnector = peer::open;
        AlbumController controller = new AlbumController(new Context(root.toFile()));
        PtpIpClient handoff = null;
        try {
            check(controller.connectionInfo() == null, "An unconnected shared controller has no cached camera metadata");
            AtomicInteger lifecycleChanges = new AtomicInteger(), oldUiChanges = new AtomicInteger(), currentUiChanges = new AtomicInteger();
            Runnable lifecycle = lifecycleChanges::incrementAndGet;
            Runnable oldUi = oldUiChanges::incrementAndGet;
            Runnable currentUi = currentUiChanges::incrementAndGet;
            controller.setLifecycleObserver(lifecycle);
            controller.setConnectionListener(oldUi);
            ConnectionInfo first = call(callback -> controller.connect("camera.test", 15740, callback));
            check(first.totalHandles() == 1 && controller.connectionInfo() != null,
                    "Successful connection publishes cached metadata for later UI recreation");
            check(lifecycleChanges.get() > 0 && oldUiChanges.get() > 0,
                    "Connection success notifies both the runtime lifecycle observer and current UI listener");
            controller.setConnectionListener(currentUi);
            controller.clearConnectionListener(oldUi);
            int lifecycleBefore = lifecycleChanges.get(), oldBefore = oldUiChanges.get(), currentBefore = currentUiChanges.get();
            controller.disconnect();
            check(lifecycleChanges.get() > lifecycleBefore && currentUiChanges.get() > currentBefore,
                    "Clearing a stale UI listener cannot clear the current listener or independent runtime observer");
            check(oldUiChanges.get() == oldBefore && controller.connectionInfo() == null,
                    "Explicit disconnect clears cached metadata without reviving an obsolete UI callback");

            controller.clearConnectionListener(currentUi);
            currentBefore = currentUiChanges.get();
            lifecycleBefore = lifecycleChanges.get();
            peer.photoHandles = new long[] {51, 52};
            peer.model = "Synthetic replacement camera";
            ConnectionInfo replacement = call(callback -> controller.connect("second.camera", 15741, callback));
            ConnectionInfo restored = controller.connectionInfo();
            check(replacement.totalHandles() == 2 && restored != null && restored.totalHandles() == 2
                            && restored.model().equals("Synthetic replacement camera") && restored.host().equals("second.camera") && restored.port() == 15741,
                    "Shared reconnection replaces all cached camera metadata for a newly attached UI");
            check(lifecycleChanges.get() > lifecycleBefore && currentUiChanges.get() == currentBefore,
                    "The runtime observer continues receiving connection changes after the UI listener is detached");
            check(controller.isConnected() && peer.openSocketCount() == 2,
                    "Detaching a UI listener does not close the shared connection");
            lifecycleBefore = lifecycleChanges.get();
            CountDownLatch transferNotified = new CountDownLatch(1);
            controller.setLifecycleObserver(() -> {
                lifecycleChanges.incrementAndGet();
                if (!controller.isConnected() && !controller.isConnecting()) transferNotified.countDown();
            });
            Result<PtpIpClient> moved = new Result<>(); controller.transferForPictureControl(moved);
            handoff = moved.value();
            check(controller.connectionInfo() == null && !controller.isConnected() && handoff.isOpen(),
                    "Ownership transfer clears album metadata while preserving the new physical owner");
            check(transferNotified.await(5, TimeUnit.SECONDS) && lifecycleChanges.get() > lifecycleBefore,
                    "Ownership transfer notifies the independent runtime lifecycle observer");
            int commands = peer.operationCount();
            Result<Void> oldKeepAlive = new Result<>(); controller.keepAlive(oldKeepAlive);
            check(!oldKeepAlive.error().isEmpty() && peer.operationCount() == commands,
                    "A retired shared album cannot reconnect or send keepalive on its transferred session");
        } finally {
            controller.close();
            if (handoff != null) handoff.close();
        }
    }

    private static void eventDisconnectClearsSharedMetadataWithoutAUiListener(Path root) throws Exception {
        Peer peer = new Peer();
        try (AlbumController controller = connected(root, peer)) {
            CountDownLatch disconnected = new CountDownLatch(1);
            controller.setLifecycleObserver(() -> { if (!controller.isConnected()) disconnected.countDown(); });
            AtomicInteger detachedUiCalls = new AtomicInteger();
            Runnable ui = detachedUiCalls::incrementAndGet;
            controller.setConnectionListener(ui);
            controller.clearConnectionListener(ui);
            int commands = peer.operationCount();
            peer.openedSockets.get(1).input.close();
            check(disconnected.await(5, TimeUnit.SECONDS), "Idle event EOF notifies the runtime without any attached UI");
            check(!controller.isConnected() && controller.connectionInfo() == null,
                    "The shared cached state cannot advertise a camera after its event channel disconnects");
            Result<Void> keepAlive = new Result<>(); controller.keepAlive(keepAlive);
            check(!keepAlive.error().isEmpty() && peer.operationCount() == commands,
                    "Keepalive after event failure does not reconnect or send another camera request");
            check(peer.sockets == 2 && peer.openSocketCount() == 0,
                    "Event failure releases the original channels without a replacement connection");
            check(detachedUiCalls.get() == 0, "Idle event failure never revives the detached UI listener");
        }
    }

    private static void keepAliveRejectsInvalidStorageListsWithoutEnumeratingPhotos(Path root) throws Exception {
        for (boolean oversized : new boolean[] {false, true}) {
            Peer peer = new Peer();
            try (AlbumController controller = connected(root.resolve(oversized ? "oversized" : "malformed"), peer)) {
                AtomicInteger invalidations = new AtomicInteger();
                controller.setLifecycleObserver(() -> { if (!controller.isConnected()) invalidations.incrementAndGet(); });
                int enumerations = peer.count(0x1007), metadata = peer.count(0x1008);
                peer.storageListOverride = oversized ? storageList(65) : le32(1);
                Result<Void> result = new Result<>(); controller.keepAlive(result);
                check(!result.error().isEmpty(), "Malformed or excessive storage lists fail bounded background keepalive");
                check(peer.count(0x1007) == enumerations && peer.count(0x1008) == metadata,
                        "Rejected keepalive storage metadata cannot trigger photo enumeration or change its cursor");
                check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x90cd) == 0,
                        "Invalid keepalive data never causes implicit reconnect, session reopen or write");
                check(!controller.isConnected() && !controller.isConnecting() && controller.connectionInfo() == null
                                && peer.openSocketCount() == 0 && invalidations.get() > 0,
                        "Semantically invalid storage metadata terminates only the current connection and notifies its lifecycle observer");
                int before = peer.operationCount();
                Result<Void> terminal = new Result<>(); controller.keepAlive(terminal);
                check(!terminal.error().isEmpty() && peer.operationCount() == before && peer.sockets == 2,
                        "A semantically invalidated album cannot reconnect from a later heartbeat");
            }
        }
    }

    private static byte[] storageList(int count) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.writeBytes(le32(count));
        for (int i = 0; i < count; i++) bytes.writeBytes(le32(i + 1));
        return bytes.toByteArray();
    }

    private static void keepAliveWaitsForInflightReadAndRespectsCancellation(Path root) throws Exception {
        for (boolean cancel : new boolean[] {false, true}) {
            Peer peer = new Peer();
            AlbumController controller = connected(root.resolve(cancel ? "cancel" : "complete"), peer);
            try {
                PhotoItem photo = firstPhoto(controller);
                peer.pauseThumbnail = true;
                AtomicInteger callbacks = new AtomicInteger();
                Result<byte[]> thumbnail = new Result<>();
                controller.loadThumbnail(photo, cancel ? counting(callbacks) : thumbnail);
                check(peer.thumbnailPaused.await(5, TimeUnit.SECONDS), "Background keepalive is requested while a framed thumbnail is incomplete");
                Result<Void> keepAlive = new Result<>();
                controller.keepAlive(keepAlive);
                check(!keepAlive.isDone() && peer.count(0x1004) == 1,
                        "Keepalive waits in the serialized worker instead of interleaving a camera command into an active read");
                if (cancel) controller.disconnect();
                peer.releaseThumbnail.countDown();
                if (cancel) {
                    call(controller::listDownloads); // Worker barrier after the obsolete queued keepalive.
                    check(callbacks.get() == 0 && !keepAlive.error().isEmpty() && peer.count(0x1004) == 1,
                            "Disconnect suppresses obsolete photo callbacks and completes the unsent heartbeat with cancellation");
                    check(!controller.isConnected() && controller.connectionInfo() == null && peer.openSocketCount() == 0,
                            "Cancelling queued keepalive releases shared connection state and both channels");
                } else {
                    check(Arrays.equals(peer.thumbnail, thumbnail.value()), "The original thumbnail completes without heartbeat framing corruption");
                    keepAlive.value();
                    check(peer.thumbnailDrained.getCount() == 0 && peer.count(0x1004) == 2 && controller.isConnected(),
                            "Only after the complete thumbnail response does the queued read-only keepalive run");
                }
                check(peer.sockets == 2 && peer.count(0x90cd) == 0,
                        "Serialized or cancelled heartbeat never opens another connection or writes settings");
            } finally { peer.releaseThumbnail.countDown(); controller.close(); }
        }
    }

    private static void sharedConnectingStateTerminatesOnSuccessFailureAndCancellation(Path root) throws Exception {
        for (String outcome : List.of("success", "failure", "cancel")) {
            Peer peer = new Peer();
            peer.pauseStorageRead = true;
            CameraWifiNetwork.testConnector = peer::open;
            AlbumController controller = new AlbumController(new Context(root.resolve(outcome).toFile()));
            try {
                AtomicInteger pendingNotifications = new AtomicInteger(), settledNotifications = new AtomicInteger();
                controller.setLifecycleObserver(() -> {
                    if (controller.isConnecting()) pendingNotifications.incrementAndGet();
                    else settledNotifications.incrementAndGet();
                });
                Result<ConnectionInfo> result = new Result<>();
                controller.connect("camera.test", 15740, result);
                check(peer.storagePaused.await(5, TimeUnit.SECONDS), "Connection pauses while reading its initial bounded storage metadata");
                check(controller.isConnecting() && !controller.isConnected() && controller.connectionInfo() == null,
                        "A recreated UI sees an in-progress shared connection without premature ready metadata");
                check(pendingNotifications.get() > 0, "The runtime lifecycle observer receives the connecting transition");
                int beforeSettle = settledNotifications.get();
                if (outcome.equals("cancel")) controller.disconnect();
                else if (outcome.equals("failure")) peer.openedSockets.get(0).close();
                peer.releaseStorage.countDown();
                if (outcome.equals("success")) {
                    ConnectionInfo info = result.value();
                    check(info.totalHandles() == 1 && controller.isConnected() && controller.connectionInfo() != null,
                            "Shared connection publishes complete metadata only after enumeration succeeds");
                } else if (outcome.equals("failure")) {
                    check(!result.error().isEmpty() && !controller.isConnected() && controller.connectionInfo() == null,
                            "A failed shared connection never leaves cached ready metadata");
                } else {
                    call(controller::listDownloads); // Wait for the cancelled connection task to retire.
                    check(!result.isDone() && !controller.isConnected() && controller.connectionInfo() == null,
                            "Cancelling shared connect suppresses the old callback and clears pending ready state");
                }
                check(!controller.isConnecting() && settledNotifications.get() > beforeSettle,
                        "Success, failure and cancellation each settle connecting state and notify the runtime observer");
                check(peer.sockets == 2 && peer.count(0x90cd) == 0,
                        "Shared connecting lifecycle never retries a connection or writes camera settings");
            } finally { peer.releaseStorage.countDown(); controller.close(); }
        }
    }

    private static void matchingCameraBusyPreservesTheAlbumForTheNextKeepAlive(Path root) throws Exception {
        Peer peer = new Peer();
        try (AlbumController controller = connected(root, peer)) {
            AtomicInteger disconnectedEvents = new AtomicInteger();
            controller.setLifecycleObserver(() -> { if (!controller.isConnected()) disconnectedEvents.incrementAndGet(); });
            ConnectionInfo original = controller.connectionInfo();
            int enumerations = peer.count(0x1007);
            int before = peer.operationCount();
            peer.storageResponseCode = 0x2019;
            Result<Void> busy = new Result<>(); controller.keepAlive(busy);
            check(!busy.error().isEmpty(), "DeviceBusy completes the album heartbeat with a camera response");
            long busyTransaction = peer.lastTransaction();
            check(controller.isConnected() && controller.connectionInfo() == original && peer.openSocketCount() == 2,
                    "A complete matching camera-busy response preserves the same ready album metadata and channels");
            check(peer.operationCount() == before + 1 && disconnectedEvents.get() == 0,
                    "Busy does not trigger immediate retry or a false disconnected lifecycle transition");
            peer.storageResponseCode = 0x2001;
            call(controller::keepAlive);
            check(controller.isConnected() && peer.lastTransaction() > busyTransaction,
                    "A later independent heartbeat remains usable and advances the same transaction sequence");
            check(peer.count(0x1004) == 3 && peer.count(0x1007) == enumerations,
                    "The busy and recovery ticks each send one read without re-enumerating photos");
            check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0 && peer.count(0x90cd) == 0,
                    "Camera busy never causes reconnect, session reopen or settings writes");
        }
    }

    private static void obsoleteKeepAliveFailureCannotClearAReplacementConnection(Path root) throws Exception {
        Peer peer = new Peer();
        CountDownLatch releaseOldReadFailure = new CountDownLatch(1);
        try (AlbumController controller = connected(root, peer)) {
            peer.openedSockets.get(0).input.closedReadRelease = releaseOldReadFailure;
            peer.pauseStorageRead = true;
            peer.storageListOverride = storageList(65);
            AtomicInteger pendingAtOldFailure = new AtomicInteger();
            Result<Void> oldHeartbeat = new Result<>() {
                @Override public void onError(String message) {
                    if (controller.isConnecting()) pendingAtOldFailure.incrementAndGet();
                    super.onError(message);
                }
            };
            controller.keepAlive(oldHeartbeat);
            check(peer.storagePaused.await(5, TimeUnit.SECONDS), "Old heartbeat pauses in a partially received storage dataset");
            peer.pauseStorageRead = false;
            peer.storageListOverride = null;
            peer.photoHandles = new long[] {51, 52};
            Result<ConnectionInfo> replacement = new Result<>();
            controller.connect("replacement.camera", 15741, replacement);
            peer.releaseStorage.countDown();
            releaseOldReadFailure.countDown();
            check(!oldHeartbeat.error().isEmpty() && pendingAtOldFailure.get() == 1,
                    "An obsolete heartbeat error completes without clearing the replacement connection's pending state");
            ConnectionInfo info = replacement.value();
            check(controller.isConnected() && !controller.isConnecting() && controller.connectionInfo() == info
                            && info.host().equals("replacement.camera") && info.port() == 15741 && info.totalHandles() == 2,
                    "An old generation's protocol failure cannot clear or close the replacement session metadata");
            call(controller::keepAlive);
            check(peer.sockets == 4 && peer.openSocketCount() == 2 && peer.count(0x1002) == 2 && peer.count(0x90cd) == 0,
                    "Only the explicit replacement connect creates new channels and its heartbeat stays usable");
        } finally { peer.releaseStorage.countDown(); releaseOldReadFailure.countDown(); }
    }

    private static AlbumController connected(Path root, Peer peer) throws Exception {
        CameraWifiNetwork.testConnector = peer::open;
        AlbumController controller = new AlbumController(new Context(root.toFile()));
        try {
            ConnectionInfo info = call(callback -> controller.connect("camera.test", 15740, callback));
            check(info.totalHandles() == peer.photoHandles.length, "Production connect enumerates the synthetic camera");
            return controller;
        } catch (Exception | Error error) { controller.close(); throw error; }
    }
    private static PhotoItem firstPhoto(AlbumController controller) throws Exception {
        PhotoPage page = call(callback -> controller.loadPage(0, 24, callback));
        check(page.items().size() == 1 && !page.hasMore(), "Production page decodes one JPEG's metadata");
        return page.items().get(0);
    }
    private static PhotoPreview preview(AlbumController controller, PhotoItem photo) throws Exception {
        PreviewResult result = new PreviewResult(); controller.loadPreview(photo, result); return result.value();
    }
    private static String previewError(AlbumController controller, PhotoItem photo) throws Exception {
        PreviewResult result = new PreviewResult(); controller.loadPreview(photo, result); return result.error();
    }
    private static DownloadedPhoto download(AlbumController controller, PhotoItem photo) throws Exception {
        DownloadResult result = new DownloadResult(); controller.download(photo, result); return result.value();
    }
    private static <T> T call(Consumer<AlbumController.Callback<T>> operation) throws Exception {
        Result<T> result = new Result<>(); operation.accept(result); return result.value();
    }
    private static class Result<T> implements AlbumController.Callback<T> {
        private final CountDownLatch done = new CountDownLatch(1);
        private T result;
        private String error;
        @Override public void onSuccess(T value) { result = value; done.countDown(); }
        @Override public void onError(String message) { error = message; done.countDown(); }
        private void await() throws Exception { if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("Controller callback timed out"); }
        final boolean isDone() { return done.getCount() == 0; }
        final T value() throws Exception { await(); if (error != null) throw new AssertionError(error); return result; }
        final String error() throws Exception { await(); if (error == null) throw new AssertionError("Expected controller error"); return error; }
    }
    private static final class PreviewResult extends Result<PhotoPreview> implements AlbumController.PreviewCallback {
        @Override public void onProgress(long received, long total) { }
    }
    private static final class DownloadResult extends Result<DownloadedPhoto> implements AlbumController.DownloadCallback {
        @Override public void onProgress(long received, long total) { }
    }

    /** A bounded synthetic camera peer, parsing the actual operation frames written by PtpIpClient. */
    private static final class Peer {
        final byte[] original = jpeg(4096, 7);
        byte[] thumbnail = jpeg(32, 3);
        byte[] largeThumbnail = jpeg(1024, 5);
        long originalSize = original.length;
        long declaredThumbnailSize;
        long declaredLargeThumbnailSize;
        boolean advertiseThumbnail = true, rejectThumbnail;
        boolean advertiseLargeThumbnail, largeThumbnailWrongTransaction;
        int largeThumbnailResponseCode = 0x2001;
        long[] photoHandles = new long[] {42};
        String model = "Synthetic camera";
        byte[] storageListOverride;
        int storageResponseCode = 0x2001;
        final List<Integer> operations = new ArrayList<>();
        final List<Long> transactions = new ArrayList<>();
        final List<PeerSocket> openedSockets = new ArrayList<>();
        final CountDownLatch thumbnailPaused = new CountDownLatch(1), releaseThumbnail = new CountDownLatch(1), thumbnailDrained = new CountDownLatch(1);
        final CountDownLatch storagePaused = new CountDownLatch(1), releaseStorage = new CountDownLatch(1), storageDrained = new CountDownLatch(1);
        boolean pauseThumbnail;
        boolean pauseStorageRead;
        int sockets;
        synchronized int count(int operation) { return (int) operations.stream().filter(op -> op == operation).count(); }
        synchronized int operationCount() { return operations.size(); }
        synchronized long lastTransaction() { return transactions.get(transactions.size() - 1); }
        synchronized int openSocketCount() { return (int) openedSockets.stream().filter(socket -> !socket.closed).count(); }
        synchronized Socket open(String host, int port, int timeout) { PeerSocket socket = new PeerSocket(this, sockets++ % 2 == 1); openedSockets.add(socket); return socket; }
        synchronized void receive(PeerSocket socket, byte[] packet) throws IOException {
            long type = u32(packet, 4);
            if (socket.event) { socket.input.add(frame(4, new byte[0])); return; }
            if (type == 1) {
                socket.input.add(frame(2, concat(le32(1), new byte[16], "Synthetic Camera\0".getBytes(StandardCharsets.UTF_16LE), le32(0x10000))));
                return;
            }
            if (type != 6) throw new IOException("Unexpected client frame " + type);
            int operation = u16(packet, 12); long transaction = u32(packet, 14);
            operations.add(operation);
            transactions.add(transaction);
            byte[] data;
            switch (operation) {
                case 0x1001: data = info(advertiseThumbnail, advertiseLargeThumbnail, model); break;
                case 0x1002: case 0x1003: socket.input.add(response(transaction, 0x2001)); return;
                case 0x1004:
                    if (storageResponseCode != 0x2001) { socket.input.add(response(transaction, storageResponseCode)); return; }
                    data = storageListOverride == null ? concat(le32(1), le32(1)) : storageListOverride; break;
                case 0x1007:
                    ByteArrayOutputStream entries = new ByteArrayOutputStream(); entries.writeBytes(le32(photoHandles.length));
                    for (long handle : photoHandles) entries.writeBytes(le32(handle));
                    data = entries.toByteArray(); break;
                case 0x1008:
                    data = new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).compressedSize(originalSize)
                            .thumbFormat(0x3801).thumbCompressedSize(16).thumbPixWidth(320).thumbPixHeight(200)
                            .imagePixWidth(6000).imagePixHeight(4000).filename("SYNTHETIC.JPG").build().toBytes();
                    break;
                case 0x1009: data = original; break;
                case 0x100a:
                    if (rejectThumbnail) { socket.input.add(response(transaction, 0x2005)); return; }
                    if (declaredThumbnailSize > 0) {
                        socket.input.add(frame(9, concat(le32(transaction), le64(declaredThumbnailSize)))); return;
                    }
                    data = thumbnail; break;
                case 0x90c4:
                    if (packet.length != 22 || u32(packet, 18) != 42) throw new IOException("Large thumbnail must request exactly one valid handle");
                    if (largeThumbnailResponseCode != 0x2001) { socket.input.add(response(transaction, largeThumbnailResponseCode)); return; }
                    if (declaredLargeThumbnailSize > 0) {
                        socket.input.add(frame(9, concat(le32(transaction), le64(declaredLargeThumbnailSize)))); return;
                    }
                    data = largeThumbnail; break;
                default: throw new IOException("Unexpected operation " + Integer.toHexString(operation));
            }
            byte[] start = frame(9, concat(le32(transaction), le64(data.length)));
            byte[] end = frame(12, concat(le32(transaction), data));
            byte[] finish = response(transaction + (operation == 0x90c4 && largeThumbnailWrongTransaction ? 1 : 0), 0x2001);
            if (operation == 0x100a && pauseThumbnail) {
                socket.input.gate(36, start.length + end.length + finish.length, thumbnailPaused, releaseThumbnail, thumbnailDrained);
            }
            if (operation == 0x1004 && pauseStorageRead) {
                socket.input.gate(24, start.length + end.length + finish.length, storagePaused, releaseStorage, storageDrained);
            }
            socket.input.add(start); socket.input.add(end); socket.input.add(finish);
        }
    }
    private static final class PeerSocket extends Socket {
        final Peer peer;
        final boolean event;
        final QueueInput input;
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        volatile boolean closed;
        PeerSocket(Peer peer, boolean event) { this.peer = peer; this.event = event; input = new QueueInput(event); }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (closed) throw new IOException("Socket closed");
                    pending.write(bytes, offset, length);
                    while (pending.size() >= 8) {
                        byte[] buffered = pending.toByteArray(); int lengthOfFrame = (int) u32(buffered, 0);
                        if (lengthOfFrame < 8 || lengthOfFrame > 65536) throw new IOException("Invalid client frame length");
                        if (buffered.length < lengthOfFrame) return;
                        pending.reset(); pending.write(buffered, lengthOfFrame, buffered.length - lengthOfFrame);
                        peer.receive(PeerSocket.this, Arrays.copyOf(buffered, lengthOfFrame));
                    }
                }
            };
        }
        @Override public void setSoTimeout(int timeout) { }
        @Override public void setTcpNoDelay(boolean enabled) { }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; input.close(); }
    }
    private static final class QueueInput extends InputStream {
        final Deque<byte[]> chunks = new ArrayDeque<>();
        final boolean holdOpen;
        int offset;
        boolean closed;
        long received, pauseAt = -1, drainAt = -1;
        CountDownLatch paused, release, drained;
        CountDownLatch closedReadRelease;
        QueueInput(boolean holdOpen) { this.holdOpen = holdOpen; }
        synchronized void add(byte[] bytes) { chunks.add(bytes); notifyAll(); }
        synchronized void gate(int pauseAfter, int total, CountDownLatch paused, CountDownLatch release, CountDownLatch drained) {
            pauseAt = received + pauseAfter; drainAt = received + total;
            this.paused = paused; this.release = release; this.drained = drained;
        }
        private boolean availableChunk() throws IOException {
            while (chunks.isEmpty() && holdOpen && !closed) {
                try { wait(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
            return !closed && !chunks.isEmpty();
        }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] bytes, int start, int length) throws IOException {
            if (length == 0) return 0;
            while (true) {
                CountDownLatch waitFor;
                synchronized (this) {
                    if (closed) {
                        waitFor = closedReadRelease != null && closedReadRelease.getCount() != 0 ? closedReadRelease : null;
                        if (waitFor == null) return -1;
                    } else {
                        waitFor = pauseAt >= 0 && received >= pauseAt && release.getCount() != 0 ? release : null;
                        if (waitFor == null) {
                            if (!availableChunk()) return -1;
                            int count = Math.min(length, Math.min(997, chunks.peek().length - offset));
                            if (pauseAt >= 0 && received < pauseAt) count = (int) Math.min(count, pauseAt - received);
                            System.arraycopy(chunks.peek(), offset, bytes, start, count); offset += count; received += count;
                            if (offset == chunks.peek().length) { chunks.remove(); offset = 0; }
                            if (drainAt >= 0 && received >= drainAt) drained.countDown();
                            return count;
                        }
                        paused.countDown();
                    }
                }
                try { if (!waitFor.await(5, TimeUnit.SECONDS)) throw new IOException("Synthetic thumbnail release timed out"); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
        }
        @Override public synchronized void close() { closed = true; if (release != null) release.countDown(); notifyAll(); }
    }
    private static byte[] info(boolean thumbnail, boolean largeThumbnail, String model) {
        List<Integer> operations = new ArrayList<>(List.of(0x1001, 0x1002, 0x1003, 0x1004, 0x1007, 0x1008, 0x1009));
        if (thumbnail) operations.add(0x100a);
        if (largeThumbnail) operations.add(0x90c4);
        ByteArrayOutputStream ops = new ByteArrayOutputStream(); ops.writeBytes(le32(operations.size()));
        for (int op : operations) ops.writeBytes(le16(op));
        return concat(le16(100), le32(10), le16(100), string("Test"), le16(0), ops.toByteArray(),
                le32(0), le32(0), le32(0), le32(0), string("Nikon"), string(model), string("1"), string(""));
    }
    private static byte[] string(String text) { return text.isEmpty() ? new byte[] {0}
            : concat(new byte[] {(byte) (text.length() + 1)}, (text + '\0').getBytes(StandardCharsets.UTF_16LE)); }
    private static byte[] jpeg(int bytes, int seed) {
        byte[] result = new byte[bytes]; Arrays.fill(result, (byte) seed);
        result[0] = (byte) 0xff; result[1] = (byte) 0xd8; result[bytes - 2] = (byte) 0xff; result[bytes - 1] = (byte) 0xd9;
        return result;
    }
    private static byte[] frame(int type, byte[] payload) { return concat(le32(8 + payload.length), le32(type), payload); }
    private static byte[] response(long transaction, int code) { return frame(7, concat(le16(code), le32(transaction))); }
    private static byte[] le16(long value) { return new byte[] {(byte) value, (byte) (value >>> 8)}; }
    private static byte[] le32(long value) { return new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)}; }
    private static byte[] le64(long value) { return concat(le32(value), le32(value >>> 32)); }
    private static int u16(byte[] bytes, int offset) { return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8); }
    private static long u32(byte[] bytes, int offset) { return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8)
            | ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24); }
    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); for (byte[] part : parts) bytes.writeBytes(part); return bytes.toByteArray();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

/** Production asynchronous controller + PTP/IP client against framed synthetic camera sockets. */
public final class AlbumControllerHostTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("album-controller-host-");
        try {
            if (args.length > 0 && args[0].equals("original-identity")) {
                completedOriginalPublicationOutlivesUiCallbacks(root.resolve("download-publication"));
                manualOriginalIdentityCommitOutlivesUiCallbacks(root.resolve("manual-original-identity"));
                manualAndLiveReuseOneVerifiedOriginal(root.resolve("cross-entry"));
                untrustedCameraReconnectionsVerifyBytesAgain(root.resolve("untrusted"));
                staleManualDescriptorsNeverReuseOrCommitAnOriginal(root.resolve("stale"));
                liveCompletedFilesAreDurableAndNeverCopiedFromPreviews(root.resolve("live-durable"));
                System.out.println("PASS: " + checks + " unified original identity checks"); return;
            }
            if (args.length > 0 && args[0].equals("live-progress")) {
                liveTransferStagesFollowPreviewBytesAndIndependentPublication(root.resolve("live-progress"));
                liveAsyncPublicationLeavesTheCameraWorkerAvailable(root.resolve("live-gallery-async"));
                liveGalleryPublicationRetriesWithoutDownloadingAgain(root.resolve("live-gallery"));
                liveIdentityChangesAndIncompleteOriginalsNeverCommit(root.resolve("live-invalid"));
                System.out.println("PASS: " + checks + " production photo-live progress checks"); return;
            }
            if (args.length > 0 && args[0].equals("receipts")) {
                manualOriginalIdentityCommitOutlivesUiCallbacks(root.resolve("manual-original-identity"));
                liveReceiptsResumeOnlyTheirOriginalCameraAndDescriptor(root.resolve("live-receipts"));
                liveProjectSetupAndOriginalObserverAreOrderedAndIndependent(root.resolve("live-project-hooks"));
                liveStoppedReceiptSurvivesAndUnidentifiedCameraCannotResume(root.resolve("live-receipt-stop"));
                liveResumeContinuesPastArchivedFailureWindow(root.resolve("live-receipt-window"));
                System.out.println("PASS: " + checks + " production photo-live receipt checks"); return;
            }
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
            completedOriginalPublicationOutlivesUiCallbacks(root.resolve("download-publication"));
            manualOriginalIdentityCommitOutlivesUiCallbacks(root.resolve("manual-original-identity"));
            manualAndLiveReuseOneVerifiedOriginal(root.resolve("cross-entry"));
            untrustedCameraReconnectionsVerifyBytesAgain(root.resolve("untrusted"));
            staleManualDescriptorsNeverReuseOrCommitAnOriginal(root.resolve("stale"));
            localDeleteReportsPartialAndSurvivesUiReplacement(root.resolve("delete-local"));
            localDeleteCannotRaceReceivingAndClearsStoppedLive(root.resolve("delete-live"));
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
            cameraConnectionDefersPhotoInventoryAndKeepsIdentityAlive(root.resolve("camera-only"));
            cameraConnectionRetainsUnavailableBrowseForPresetHandoff(root.resolve("camera-only-browse"));
            cameraConnectionRejectsForeignIdentityAndCancelsCleanly(root.resolve("camera-only-cancel"));
            cameraConnectionEventLossClearsItsIdentity(root.resolve("camera-only-event"));
            matchingCameraBusyPreservesTheAlbumForTheNextKeepAlive(root.resolve("keepalive-busy"));
            obsoleteKeepAliveFailureCannotClearAReplacementConnection(root.resolve("keepalive-replacement"));
            presetHandoffInitializesAlbumMetadataAndLargePreview(root.resolve("preset-handoff"));
            cancellingQueuedAdoptionReleasesItsAcceptedOwner(root.resolve("preset-handoff-queued"));
            albumAdoptionRejectsUnrelatedEndpointsAndBusyControllers(root.resolve("preset-handoff-rejected"));
            eventFailureOrCancellationDuringAdoptionCannotLeaveReadyMetadata(root.resolve("preset-handoff-event"));
            recoverableBrowseFailureKeepsTheAdoptedSessionForRetryOrPresetReturn(root.resolve("preset-handoff-browse"));
            invalidAdoptedBrowseCannotAdvertiseALiveSession(root.resolve("preset-handoff-invalid"));
            liveBaselineDoesNotDownloadOldPhotosOrMoveTheBrowseCursor(root.resolve("live-baseline"));
            liveFastBaselineHasConstantRoundTripsAndPrioritizesNewPhotos(root.resolve("live-fast-baseline"));
            liveEventsDuringHandleInventoryAreBufferedUntilReady(root.resolve("live-baseline-event-race"));
            liveCachedBaselineMetadataCannotReplayChangesBeforeStartup(root.resolve("live-baseline-cached"));
            liveNamedProjectsSeparateRunsAndSurviveOfflineRecreation(root.resolve("live-project-roundtrip"));
            liveProjectRenameWorksOfflineWithoutCameraOperations(root.resolve("live-project-rename-offline"));
            liveProjectRenameDrainsTransferAndKeepsFutureMembership(root.resolve("live-project-rename-active"));
            liveInvalidProjectNamesNeverQueryOrDisconnectTheCamera(root.resolve("live-project-invalid"));
            liveBusyAndCancelledStartupCannotMisclassifyTheNextProject(root.resolve("live-project-startup"));
            cancellingOldBrowseRequestsDrainsOnlyOneFrameBeforeFastLiveStartup(root.resolve("live-browse-cancel"));
            liveEmptyCardSlotDoesNotStopTheReadableCard(root.resolve("live-empty-slot"));
            liveUnavailableSlotRecoveryBaselinesOldPhotos(root.resolve("live-slot-first-ready"));
            liveOldMetadataUnavailabilityDoesNotBlockFastBaselineOrNewPhotos(root.resolve("live-baseline-slot-outage"));
            liveLazyBaselineFingerprintingNeverReplaysUnknownOldHandles(root.resolve("live-lazy-baseline"));
            liveUnknownStorageEventCannotBypassItsFirstRecoveredBaseline(root.resolve("live-storage-event-baseline"));
            liveTemporaryCardOutagePreservesKnownIdentities(root.resolve("live-slot-outage"));
            liveNoAvailableStorageWaitsWithoutCompletingABlindBaseline(root.resolve("live-no-storage"));
            liveStorageErrorsDoNotHideTerminalSessionFailures(root.resolve("live-storage-terminal"));
            liveEventsAndPollingReceiveNewPhotosOnceWithPreviewFirst(root.resolve("live-events"));
            liveTransferStagesFollowPreviewBytesAndIndependentPublication(root.resolve("live-progress"));
            liveTemporaryCameraErrorsBackOffWithoutReconnecting(root.resolve("live-retry"));
            livePhotoStoreUnavailableResponseRetriesWithoutClosing(root.resolve("live-photo-storage-retry"));
            liveQueueBackpressureDoesNotExhaustCameraRetries(root.resolve("live-backpressure"));
            liveHistoryRetainsOutstandingRetriesAndBoundsPendingIdentity(root.resolve("live-history"));
            liveUiListenersCanDetachWithoutLosingTheReceiveTask(root.resolve("live-ui"));
            liveStopDisconnectAndHandoffRetireTheOldQueue(root.resolve("live-cancel"));
            liveQueuedStartCompletesExactlyOnceWhenCancelled(root.resolve("live-start-cancel"));
            liveCompletedFilesAreDurableAndNeverCopiedFromPreviews(root.resolve("live-durable"));
            liveGalleryPublicationRetriesWithoutDownloadingAgain(root.resolve("live-gallery"));
            liveAsyncPublicationLeavesTheCameraWorkerAvailable(root.resolve("live-gallery-async"));
            liveAsyncPublisherSettlesThrowsAndCallbacksExactlyOnce(root.resolve("live-gallery-async-once"));
            liveAsyncCompletionCannotReplaceANewerRun(root.resolve("live-gallery-async-owner"));
            liveIdentityChangesAndIncompleteOriginalsNeverCommit(root.resolve("live-invalid"));
            liveProtocolFailureAndEventLossStopWithoutReplayingOriginals(root.resolve("live-terminal"));
            liveNonJpegEventsNeverDownloadRawFiles(root.resolve("live-non-jpeg"));
            liveLedgerValidatesStableIdentityAndCompletedFileIntegrity(root.resolve("live-ledger"));
            liveReceiptsResumeOnlyTheirOriginalCameraAndDescriptor(root.resolve("live-receipts"));
            liveProjectSetupAndOriginalObserverAreOrderedAndIndependent(root.resolve("live-project-hooks"));
            liveStoppedReceiptSurvivesAndUnidentifiedCameraCannotResume(root.resolve("live-receipt-stop"));
            liveResumeContinuesPastArchivedFailureWindow(root.resolve("live-receipt-window"));
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

    private static void completedOriginalPublicationOutlivesUiCallbacks(Path root) throws Exception {
        Peer peer = new Peer();
        try (AlbumController controller = connected(root, peer)) {
            PhotoItem photo = firstPhoto(controller);
            java.util.concurrent.atomic.AtomicReference<DownloadedPhoto> published = new java.util.concurrent.atomic.AtomicReference<>();
            controller.setOriginalDownloadObserver(published::set);
            DownloadedPhoto first = download(controller, photo);
            check(published.get() == first, "Application publication receives the committed original independently of the UI listener");
            check(Arrays.equals(Files.readAllBytes(first.file().toPath()), peer.original), "Publication receives complete original bytes");
            controller.setOriginalDownloadObserver(saved -> { throw new IllegalStateException("synthetic local queue unavailable"); });
            DownloadedPhoto second = download(controller, photo);
            check(second.file().isFile() && Arrays.equals(Files.readAllBytes(second.file().toPath()), peer.original),
                    "A failed publication enqueue retains the source and does not turn a successful camera read into a failed transfer");
            check(controller.hasLiveSession() && peer.count(0x1009) == 1 && first.file().equals(second.file()),
                    "Publication failure retries locally using the verified completed original without replaying its camera transfer");
            Result<Void> keepAlive = new Result<>(); controller.keepAlive(keepAlive); keepAlive.value();
            check(controller.hasLiveSession(), "The camera remains usable after a local publication error");
        }
    }

    private static void manualOriginalIdentityCommitOutlivesUiCallbacks(Path root) throws Exception {
        Peer peer = new Peer(); peer.pauseOriginal = true; Arrays.fill(peer.cameraGuid, (byte) 3);
        try (AlbumController controller = connected(root, peer)) {
            PhotoItem photo = firstPhoto(controller); String expectedIdentity = controller.stablePhotoIdentity(photo);
            List<String> order = new ArrayList<>(); AtomicInteger aliases = new AtomicInteger();
            Object lifecycle = privateField(controller, "lifecycle").get(controller);
            controller.setOriginalDownloadIdentityObserver((identity, saved) -> {
                check(identity.equals(expectedIdentity) && identity.length() == 64 && saved.file().isFile(),
                        "Manual download identity is derived from its original camera descriptor after the source is committed");
                check(!Thread.holdsLock(lifecycle), "Manual identity persistence never runs under the camera lifecycle lock");
                aliases.incrementAndGet(); order.add("identity");
            });
            controller.setOriginalDownloadObserver(saved -> {
                check(order.equals(List.of("identity")), "Identity persistence precedes local publication and the UI callback");
                order.add("publication");
            });
            DownloadResult result = new DownloadResult(); controller.download(photo, result);
            check(peer.originalPaused.await(5, TimeUnit.SECONDS), "Manual original is in flight before its UI connection listener is replaced");
            Runnable previousUi = () -> { throw new AssertionError("Detached UI listener invoked"); };
            controller.setConnectionListener(previousUi); controller.clearConnectionListener(previousUi);
            peer.releaseOriginal.countDown(); DownloadedPhoto first = result.value();
            check(aliases.get() == 1 && order.equals(List.of("identity", "publication")) && Arrays.equals(Files.readAllBytes(first.file().toPath()), peer.original),
                    "Runtime identity persistence and publication complete without an attached Activity connection listener");
            controller.setOriginalDownloadIdentityObserver((identity, saved) -> { throw new IllegalStateException("identity journal unavailable"); });
            AtomicInteger publications = new AtomicInteger(); controller.setOriginalDownloadObserver(saved -> publications.incrementAndGet());
            DownloadedPhoto second = download(controller, photo);
            check(second.file().isFile() && publications.get() == 1 && peer.count(0x1009) == 1 && first.file().equals(second.file()) && controller.hasLiveSession(),
                    "An identity journal error preserves the successful source, publication, and camera session without replay");
        } finally { peer.releaseOriginal.countDown(); }
    }

    private static void manualAndLiveReuseOneVerifiedOriginal(Path root) throws Exception {
        Peer peer = new Peer(); Arrays.fill(peer.cameraGuid, (byte) 5);
        try (AlbumController controller = connected(root, peer)) {
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive); peer.setHandles(42, 43); peer.objectAdded(43);
            awaitCondition(() -> controller.photoLiveState().completed == 1 && controller.photoLiveState().photos.get(0).saved != null, "A photo-live original finishes before a manual request");
            PhotoLiveState.LivePhoto incoming = controller.photoLiveState().photos.get(0);
            DownloadedPhoto manual = download(controller, incoming.photo);
            check(incoming.saved.file().equals(manual.file()) && peer.count(0x1009, 43) == 1 && call(controller::listDownloads).size() == 1,
                    "Downloading an already received live JPEG reuses its verified private original without another GetObject");
            peer.setHandles(42, 43, 44); peer.objectAdded(44);
            awaitCondition(() -> controller.photoLiveState().completed == 2 && controller.photoLiveState().photos.stream().allMatch(item -> item.saved != null && item.galleryError.isEmpty()), "A second incoming identity completes after the shared original");
            check(call(controller::listDownloads).size() == 1 && controller.photoLiveState().error.isEmpty(), "Different camera identities with exact identical bytes keep one original without a project classification error");
            controller.stopPhotoLive();
            var projects = AlbumControllerHostTest.<List<PhotoLiveProject>>call(controller::listPhotoLiveProjects);
            check(projects.size() == 1 && projects.get(0).photoCount == 1, "A project counts a shared physical original once while preserving both receive receipts");
        }
    }

    private static void untrustedCameraReconnectionsVerifyBytesAgain(Path root) throws Exception {
        Peer first = new Peer(); DownloadedPhoto old;
        try (AlbumController controller = connected(root, first)) { old = download(controller, firstPhoto(controller)); }
        Peer sameAddress = new Peer(); byte[] different = sameAddress.original.clone(); different[15] ^= 61; sameAddress.originals.put(42L, different);
        try (AlbumController controller = connected(root, sameAddress)) {
            DownloadedPhoto actual = download(controller, firstPhoto(controller));
            check(sameAddress.count(0x1009) == 1 && !actual.file().equals(old.file()) && Arrays.equals(Files.readAllBytes(actual.file().toPath()), different),
                    "A camera with no trusted GUID cannot reuse another body's old bytes merely because address, model, filename and descriptor match");
            check(Arrays.equals(Files.readAllBytes(old.file().toPath()), first.original), "The previous camera's original remains untouched");
        }
    }

    private static void staleManualDescriptorsNeverReuseOrCommitAnOriginal(Path root) throws Exception {
        Peer peer = new Peer(); Arrays.fill(peer.cameraGuid, (byte) 6);
        try (AlbumController controller = connected(root, peer)) {
            PhotoItem photo = firstPhoto(controller); DownloadedPhoto original = download(controller, photo);
            peer.filenames.put(42L, "REPLACED.JPG"); DownloadResult stale = new DownloadResult(); controller.download(photo, stale);
            check(stale.error().contains("变化") && peer.count(0x1009) == 1 && original.file().isFile(), "A changed camera descriptor rejects a cached original shortcut and asks for a fresh list");
            peer.filenames.clear(); peer.changeIdentityAfterOriginal = true; peer.setHandles(42, 43);
            PhotoPage page = call(callback -> controller.loadPage(0, 24, callback)); PhotoItem second = page.items().stream().filter(item -> item.handle() == 43).findFirst().orElse(null);
            // The existing browse snapshot deliberately does not grow during live events; use its immutable session for a controlled new descriptor.
            if (second == null) second = new PhotoItem(photo.session, 43, photo.objectInfo());
            DownloadResult changed = new DownloadResult(); controller.download(second, changed);
            check(changed.error().contains("变化") && call(controller::listDownloads).size() == 1, "A descriptor change during GetObject leaves no incorrectly identified completed original");
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

    private static void cameraConnectionDefersPhotoInventoryAndKeepsIdentityAlive(Path root) throws Exception {
        Peer peer = new Peer(); CameraWifiNetwork.testConnector = peer::open;
        try (AlbumController controller = new AlbumController(new Context(root.toFile()))) {
            ConnectionInfo identity = call(callback -> controller.connectCamera("camera.test", 15740, callback));
            check(identity.model().equals(peer.model) && identity.host().equals("camera.test") && identity.port() == 15740,
                    "Camera-only connection returns validated Nikon identity and the requested endpoint");
            check(controller.hasLiveSession() && controller.sessionConnectionInfo() == identity && !controller.isConnected()
                            && controller.connectionInfo() == null && !controller.isConnecting(),
                    "Camera-ready state remains distinct from a completed photo inventory");
            check(peer.count(0x1001) == 1 && peer.count(0x1002) == 1 && peer.operationCount() == 2 && peer.sockets == 2,
                    "Camera-only startup performs one device-info read and one OpenSession on two channels");
            check(peer.count(0x1004) == 0 && peer.count(0x1007) == 0 && peer.count(0x90cd) == 0,
                    "Connecting a camera neither enumerates card/photo inventory nor requires or sends preset operations");
            call(controller::keepAlive);
            check(peer.count(0x1001) == 2 && peer.count(0x1004) == 0 && peer.count(0x1007) == 0
                            && controller.sessionConnectionInfo() == identity,
                    "An unbrowsed camera heartbeat reads identity without touching storage or replacing its camera-ready state");
            ConnectionInfo browsed = call(controller::retryBrowse);
            check(browsed.totalHandles() == 1 && controller.isConnected() && controller.connectionInfo() == browsed,
                    "Explicit browse initializes photo-ready metadata on the original camera-only session");
            check(peer.count(0x1004) == 1 && peer.count(0x1007) == 1 && peer.sockets == 2 && peer.count(0x1002) == 1,
                    "Deferred browse enumerates once without another physical handshake or OpenSession");
            check(firstPhoto(controller).handle() == 42, "Deferred photo inventory supports ordinary photo metadata reads");
            call(controller::keepAlive);
            check(peer.count(0x1004) == 2 && peer.count(0x1007) == 1,
                    "A photo-ready heartbeat preserves the existing bounded storage read and never re-enumerates photographs");
            assertOwnedTransactionsAdvance(peer);
        }
    }

    private static void cameraConnectionRetainsUnavailableBrowseForPresetHandoff(Path root) throws Exception {
        for (String outcome : List.of("direct", "missing_operation", "storage_busy")) {
            Peer peer = new Peer();
            if (outcome.equals("missing_operation")) peer.missingOperation = 0x1004;
            if (outcome.equals("storage_busy")) peer.storageResponseCode = 0x2002;
            CameraWifiNetwork.testConnector = peer::open;
            AlbumController controller = new AlbumController(new Context(root.resolve(outcome).toFile()));
            PtpIpClient transferred = null;
            try {
                ConnectionInfo identity = call(callback -> controller.connectCamera("camera.test", 15740, callback));
                check(controller.hasLiveSession() && identity.model().equals(peer.model) && peer.count(0x1004) == 0,
                        "A camera without available photo storage still connects before the user chooses a feature");
                if (!outcome.equals("direct")) {
                    Result<ConnectionInfo> browse = new Result<>(); controller.retryBrowse(browse);
                    check(!browse.error().isEmpty() && controller.hasLiveSession() && !controller.isConnected()
                                    && controller.sessionConnectionInfo() != null && controller.connectionInfo() == null
                                    && !controller.browseError().isEmpty(),
                            "Unavailable photo operations or a complete temporary card error preserves camera identity and both channels");
                    int storageReads = peer.count(0x1004);
                    call(controller::keepAlive);
                    check(controller.hasLiveSession() && peer.count(0x1004) == storageReads,
                            "A camera-ready heartbeat after unavailable browsing does not repeatedly query the failing storage");
                }
                Result<PtpIpClient> moved = new Result<>(); controller.transferForPictureControl(moved); transferred = moved.value();
                check(transferred.isOpen() && !controller.hasLiveSession() && controller.sessionConnectionInfo() == null,
                        "The independently connected camera can transfer directly to the preset session owner");
                long next = transferred.claimTransferredSession(); transferred.getPictureControlData(0x1001, next);
                check(peer.sockets == 2 && peer.openSocketCount() == 2 && peer.count(0x1002) == 1
                                && peer.count(0x1003) == 0 && peer.count(0x90cd) == 0,
                        "Direct or post-failure preset handoff reuses the physical connection without reopening or writing");
                assertOwnedTransactionsAdvance(peer);
            } finally { controller.close(); if (transferred != null) transferred.close(); }
        }
    }

    private static void cameraConnectionRejectsForeignIdentityAndCancelsCleanly(Path root) throws Exception {
        for (String outcome : List.of("foreign", "disconnect", "close")) {
            Peer peer = new Peer(); peer.pauseDeviceInfo = true;
            if (outcome.equals("foreign")) peer.manufacturer = "Other Camera";
            CameraWifiNetwork.testConnector = peer::open;
            AlbumController controller = new AlbumController(new Context(root.resolve(outcome).toFile()));
            try {
                Result<ConnectionInfo> connection = new Result<>(); controller.connectCamera("camera.test", 15740, connection);
                check(peer.deviceInfoPaused.await(5, TimeUnit.SECONDS) && controller.isConnecting() && !controller.hasLiveSession(),
                        "Camera-only startup exposes connecting state until its bounded device identity has completed");
                if (outcome.equals("disconnect")) controller.disconnect();
                if (outcome.equals("close")) controller.close();
                peer.releaseDeviceInfo.countDown();
                if (outcome.equals("foreign")) check(connection.error().contains("尼康"), "A non-Nikon identity is rejected before camera-ready state can be published");
                else if (outcome.equals("disconnect")) call(controller::listDownloads);
                if (!outcome.equals("foreign")) check(!connection.isDone(), "Cancelled camera-only connect suppresses its obsolete completion callback");
                check(!controller.isConnecting() && !controller.hasLiveSession() && !controller.isConnected()
                                && controller.sessionConnectionInfo() == null && controller.connectionInfo() == null && peer.openSocketCount() == 0,
                        "Rejected or cancelled camera-only connection leaves neither ready metadata nor an open socket owner");
                check(peer.count(0x1004) == 0 && peer.count(0x1007) == 0 && peer.count(0x90cd) == 0 && peer.sockets == 2,
                        "Identity failure and cancellation cannot start card/photo reads, preset writes or replacement channels");
            } finally { peer.releaseDeviceInfo.countDown(); controller.close(); }
        }
    }

    private static void cameraConnectionEventLossClearsItsIdentity(Path root) throws Exception {
        Peer peer = new Peer(); CameraWifiNetwork.testConnector = peer::open;
        try (AlbumController controller = new AlbumController(new Context(root.toFile()))) {
            AlbumControllerHostTest.<ConnectionInfo>call(callback -> controller.connectCamera("camera.test", 15740, callback));
            peer.openedSockets.get(1).input.close();
            check(peer.channelClosed.await(5, TimeUnit.SECONDS), "An idle camera-only event-channel failure is observed by its single physical reader");
            call(controller::listDownloads);
            check(!controller.hasLiveSession() && controller.sessionConnectionInfo() == null && peer.openSocketCount() == 0,
                    "Idle event loss clears camera-only identity and closes both channels without waiting for photo browsing");
            check(peer.sockets == 2 && peer.count(0x1004) == 0 && peer.count(0x1007) == 0,
                    "An event failure cannot automatically reconnect or enumerate camera photos");
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

    private static void presetHandoffInitializesAlbumMetadataAndLargePreview(Path root) throws Exception {
        Peer peer = new Peer(); peer.advertiseLargeThumbnail = true; peer.photoHandles = new long[] {42, 43, 44};
        PtpIpClient oldPreset = openPreset(peer);
        PtpIpClient albumOwner = oldPreset.transferToAlbum(1);
        AlbumController controller = new AlbumController(new Context(root.toFile()));
        PtpIpClient returned = null;
        try {
            ConnectionInfo info = call(callback -> controller.connectFromHandoff(albumOwner, "camera.test", 15740, callback));
            check(controller.isConnected() && !controller.isConnecting() && controller.connectionInfo() == info
                            && info.totalHandles() == 3 && info.host().equals("camera.test") && info.port() == 15740,
                    "Adopting preset ownership initializes complete album metadata and handle indices");
            oldPreset.close(); oldPreset.abortProtocol(new IOException("retired preset callback"));
            PhotoPage page = call(callback -> controller.loadPage(0, 2, callback));
            check(page.items().size() == 2 && page.items().get(0).handle() == 42 && page.nextOffset() == 2 && page.hasMore(),
                    "An adopted album starts paging its freshly initialized handle snapshot");
            try (PhotoPreview preview = preview(controller, page.items().get(0))) {
                check(Arrays.equals(peer.largeThumbnail, Files.readAllBytes(preview.file().toPath())) && preview.isLargeThumbnail(),
                        "An adopted album may read the declared large preview through its inherited physical session");
            }
            check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0
                            && peer.count(0x1009) == 0 && peer.count(0x90cd) == 0,
                    "Preset-to-album adoption does not reconnect, reopen, close, download originals or write presets");
            assertOwnedTransactionsAdvance(peer);
            Result<PtpIpClient> moved = new Result<>(); controller.transferForPictureControl(moved); returned = moved.value();
            check(controller.connectionInfo() == null && !controller.isConnected() && returned.isOpen(),
                    "The initialized album can return ownership for a later explicit preset flow");
            long next = returned.claimTransferredSession();
            check(next > peer.lastTransaction(), "Returning to preset operations inherits every album metadata and preview transaction");
            controller.close();
            returned.getPictureControlData(0x1001, next);
            check(returned.isOpen() && peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                    "Closing the obsolete adopted album cannot close the returned preset owner");
        } finally { controller.close(); oldPreset.close(); if (returned != null) returned.close(); else albumOwner.close(); }
    }

    private static void cancellingQueuedAdoptionReleasesItsAcceptedOwner(Path root) throws Exception {
        for (boolean close : new boolean[] {false, true}) {
            Peer peer = new Peer();
            PtpIpClient oldPreset = openPreset(peer);
            PtpIpClient albumOwner = oldPreset.transferToAlbum(1);
            AlbumController controller = new AlbumController(new Context(root.resolve(close ? "close" : "disconnect").toFile()));
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
            try {
                controller.listDownloads(new AlbumController.Callback<>() {
                    @Override public void onSuccess(List<DownloadedPhoto> photos) {
                        entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Synthetic worker gate timed out"); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        finally { exited.countDown(); }
                    }
                    @Override public void onError(String message) { throw new AssertionError(message); }
                });
                check(entered.await(5, TimeUnit.SECONDS), "The album worker is occupied before a handoff is queued");
                AtomicInteger callbacks = new AtomicInteger();
                Result<ConnectionInfo> adoption = new Result<>() {
                    @Override public void onSuccess(ConnectionInfo info) { callbacks.incrementAndGet(); super.onSuccess(info); }
                    @Override public void onError(String message) { callbacks.incrementAndGet(); super.onError(message); }
                };
                check(controller.connectFromHandoff(albumOwner, "camera.test", 15740, adoption),
                        "Queued adoption explicitly acknowledges accepting caller ownership");
                check(controller.isConnecting() && !controller.isConnected(), "A synchronously accepted queued handoff is exposed as connecting");
                if (close) controller.close(); else controller.disconnect();
                check(albumOwner.isClosed() && peer.openSocketCount() == 0,
                        "Disconnect or close releases an accepted handoff even before its worker claim can run");
                release.countDown();
                check(exited.await(5, TimeUnit.SECONDS), "The synthetic old worker callback retires after cancellation");
                if (!close) call(controller::listDownloads);
                check(!adoption.error().isEmpty() && callbacks.get() == 1 && !controller.isConnecting() && controller.connectionInfo() == null,
                        "Cancelled queued adoption completes once with cancellation and never publishes ready metadata");
                check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                        "Cancelling queued adoption never creates another connection or session");
            } finally { release.countDown(); controller.close(); albumOwner.close(); oldPreset.close(); }
        }
    }

    private static void albumAdoptionRejectsUnrelatedEndpointsAndBusyControllers(Path root) throws Exception {
        for (String state : List.of("wrong_host", "wrong_port", "live", "connecting", "closed")) {
            Peer callerPeer = new Peer();
            PtpIpClient oldPreset = openPreset(callerPeer), caller = oldPreset.transferToAlbum(1);
            Peer existingPeer = new Peer(); CameraWifiNetwork.testConnector = existingPeer::open;
            AlbumController controller = new AlbumController(new Context(root.resolve(state).toFile()));
            Result<ConnectionInfo> pending = null;
            try {
                if (state.equals("live")) AlbumControllerHostTest.<ConnectionInfo>call(callback -> controller.connect("existing.camera", 15741, callback));
                if (state.equals("connecting")) {
                    existingPeer.pauseStorageRead = true; pending = new Result<>();
                    controller.connect("existing.camera", 15741, pending);
                    check(existingPeer.storagePaused.await(5, TimeUnit.SECONDS), "An existing ordinary album connect is pending at adoption refusal");
                }
                if (state.equals("closed")) controller.close();
                Result<ConnectionInfo> adoption = new Result<>();
                controller.connectFromHandoff(caller, state.equals("wrong_host") ? "unrelated.camera" : "camera.test",
                        state.equals("wrong_port") ? 15742 : 15740, adoption);
                check(!adoption.error().isEmpty() && caller.isOpen(),
                        "A mismatched endpoint or unavailable controller rejects adoption without consuming caller ownership");
                check(caller.claimTransferredAlbumSession() == 1 && callerPeer.sockets == 2,
                        "The caller retains its unconsumed read-only handoff after a rejected adoption");
                if (state.equals("live")) check(controller.isConnected() && controller.connectionInfo().host().equals("existing.camera"),
                        "A rejected incoming handoff cannot replace the controller's current live album");
                if (pending != null) { existingPeer.releaseStorage.countDown(); pending.value(); }
            } finally { existingPeer.releaseStorage.countDown(); controller.close(); caller.close(); oldPreset.close(); }
        }
    }

    private static void eventFailureOrCancellationDuringAdoptionCannotLeaveReadyMetadata(Path root) throws Exception {
        for (boolean cancel : new boolean[] {false, true}) {
            Peer peer = new Peer();
            PtpIpClient oldPreset = openPreset(peer), owner = oldPreset.transferToAlbum(1);
            peer.pauseStorageRead = true;
            AlbumController controller = new AlbumController(new Context(root.resolve(cancel ? "cancel" : "event").toFile()));
            try {
                Result<ConnectionInfo> adoption = new Result<>();
                controller.connectFromHandoff(owner, "camera.test", 15740, adoption);
                check(peer.storagePaused.await(5, TimeUnit.SECONDS), "Adoption has claimed its owner and is initializing storage indices");
                if (cancel) controller.disconnect();
                else {
                    peer.openedSockets.get(1).input.close();
                    check(peer.channelClosed.await(5, TimeUnit.SECONDS), "The inherited event reader confirms its terminal channel failure before browse can resume");
                }
                peer.releaseStorage.countDown();
                if (cancel) { call(controller::listDownloads); check(!adoption.error().isEmpty(), "An explicitly cancelled adoption completes with cancellation instead of ready metadata"); }
                else check(!adoption.error().isEmpty(), "Event failure while adopting is reported as an initialization failure");
                check(!controller.isConnected() && !controller.isConnecting() && controller.connectionInfo() == null && owner.isClosed(),
                        "Failed or cancelled adoption clears all ready metadata and releases its accepted owner");
                check(peer.openSocketCount() == 0 && peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x90cd) == 0,
                        "Failed adoption never reconnects, reopens or registers a preset");
            } finally { peer.releaseStorage.countDown(); controller.close(); owner.close(); oldPreset.close(); }
        }
    }

    private static PtpIpClient openPreset(Peer peer) throws Exception {
        PtpIpClient preset = PtpIpClient.forPictureControl(peer::open);
        preset.connectForPictureControl("camera.test", 15740, new byte[16], "Synthetic preset");
        preset.getPictureControlData(0x1001, 0);
        preset.pictureControlCommand(0x1002, 0, 1);
        return preset;
    }

    private static void recoverableBrowseFailureKeepsTheAdoptedSessionForRetryOrPresetReturn(Path root) throws Exception {
        for (String failure : List.of("missing_operation", "general_error", "return_to_presets")) {
            Peer peer = new Peer();
            PtpIpClient oldPreset = openPreset(peer), owner = oldPreset.transferToAlbum(1);
            if (failure.equals("missing_operation")) peer.missingOperation = 0x1007;
            else peer.storageResponseCode = 0x2002;
            AlbumController controller = new AlbumController(new Context(root.resolve(failure).toFile()));
            PtpIpClient returned = null;
            try {
                Result<ConnectionInfo> adoption = new Result<>();
                check(controller.connectFromHandoff(owner, "camera.test", 15740, adoption), "A healthy handed-off session is accepted before browse initialization");
                check(!adoption.error().isEmpty(), "Missing browse capabilities or a complete temporary camera error is still reported as a browse failure");
                check(controller.hasLiveSession() && !controller.isConnected() && controller.connectionInfo() == null
                                && controller.sessionConnectionInfo() != null && !controller.browseError().isEmpty() && owner.isOpen(),
                        "A recoverable browse failure preserves healthy read-only session ownership independently of album readiness");
                check(peer.sockets == 2 && peer.openSocketCount() == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                        "Failed browse setup never disconnects or reconnects the live preset-origin session");
                peer.missingOperation = -1; peer.storageResponseCode = 0x2001;
                if (failure.equals("return_to_presets")) {
                    Result<PtpIpClient> handoff = new Result<>(); controller.transferForPictureControl(handoff); returned = handoff.value();
                    check(returned.isOpen() && !controller.hasLiveSession(), "A live session with failed browsing can return for a later explicit preset action");
                    long next = returned.claimTransferredSession(); returned.getPictureControlData(0x1001, next);
                    check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x90cd) == 0,
                            "Returning from failed browsing does not register a preset or reopen its session");
                } else {
                    ConnectionInfo ready = call(controller::retryBrowse);
                    check(controller.isConnected() && controller.hasLiveSession() && controller.connectionInfo() == ready
                                    && ready.totalHandles() == 1 && controller.browseError().isEmpty(),
                            "Explicit read-only browse retry initializes complete metadata on the same healthy session");
                    check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                            "Retrying browse never issues another Init, OpenSession or CloseSession");
                }
                assertOwnedTransactionsAdvance(peer);
            } finally { controller.close(); if (returned != null) returned.close(); owner.close(); oldPreset.close(); }
        }
    }

    private static void invalidAdoptedBrowseCannotAdvertiseALiveSession(Path root) throws Exception {
        for (String failure : List.of("session_not_open", "invalid_transaction", "wrong_transaction", "bad_storage", "bad_handles")) {
            Peer peer = new Peer();
            PtpIpClient oldPreset = openPreset(peer), owner = oldPreset.transferToAlbum(1);
            if (failure.equals("session_not_open")) peer.storageResponseCode = 0x2003;
            if (failure.equals("invalid_transaction")) peer.storageResponseCode = 0x2004;
            if (failure.equals("wrong_transaction")) peer.storageWrongTransaction = true;
            if (failure.equals("bad_storage")) peer.storageListOverride = le32(1);
            if (failure.equals("bad_handles")) peer.photoHandles = new long[] {0};
            AlbumController controller = new AlbumController(new Context(root.resolve(failure).toFile()));
            try {
                Result<ConnectionInfo> adoption = new Result<>(); controller.connectFromHandoff(owner, "camera.test", 15740, adoption);
                check(!adoption.error().isEmpty(), "Session-invalid or malformed adopted browse data is reported as a failure");
                check(!controller.hasLiveSession() && !controller.isConnected() && !controller.isConnecting()
                                && controller.connectionInfo() == null && controller.sessionConnectionInfo() == null && owner.isClosed(),
                        "Invalid adopted browse data terminates its current owner rather than publishing stale connected metadata");
                check(peer.openSocketCount() == 0 && peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x90cd) == 0,
                        "Invalid adoption never reconnects or registers anything");
            } finally { controller.close(); owner.close(); oldPreset.close(); }
        }
    }

    private static void assertOwnedTransactionsAdvance(Peer peer) {
        boolean owned = false; long previous = 0;
        for (int i = 0; i < peer.operations.size(); i++) {
            if (peer.operations.get(i) == 0x1002) { owned = true; continue; }
            if (owned) {
                check(peer.transactions.get(i) > previous, "Every operation after either handoff advances the single physical session transaction sequence");
                previous = peer.transactions.get(i);
            }
        }
    }

    private static void liveBaselineDoesNotDownloadOldPhotosOrMoveTheBrowseCursor(Path root) throws Exception {
        Peer peer = new Peer(); peer.setHandles(40, 41, 42);
        try (AlbumController controller = connected(root, peer)) {
            PhotoPage before = call(callback -> controller.loadPage(0, 1, callback));
            ConnectionInfo connection = controller.connectionInfo();
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
            check(controller.photoLiveState().enabled && !controller.photoLiveState().starting,
                    "Live reception becomes enabled only after its old-photo baseline is complete");
            peer.objectAdded(40); peer.objectAdded(42);
            Thread.sleep(100); call(controller::listDownloads);
            check(peer.count(0x1009) == 0 && controller.photoLiveState().photos.isEmpty(),
                    "Starting live mode and delayed events for baseline photos never download old originals");
            PhotoPage after = call(callback -> controller.loadPage(0, 1, callback));
            check(after.totalHandles() == before.totalHandles() && after.items().get(0).handle() == before.items().get(0).handle()
                            && controller.connectionInfo() == connection,
                    "A live baseline never replaces browse metadata or advances the user's paging snapshot");
            controller.stopPhotoLive();
            check(!controller.photoLiveState().enabled && controller.isConnected() && peer.sockets == 2 && peer.count(0x90cd) == 0,
                    "Stopping live reception keeps the original read-only camera connection");
        }
    }

    private static void liveFastBaselineHasConstantRoundTripsAndPrioritizesNewPhotos(Path root) throws Exception {
        for (int count : new int[] {1, 5000, 20000}) {
            Peer peer = new Peer(); long[] existing = new long[count];
            for (int i = 0; i < count; i++) existing[i] = i + 1;
            peer.setHandles(existing);
            try (AlbumController controller = connected(root.resolve(Integer.toString(count)), peer)) {
                int storageBefore = peer.count(0x1004), handlesBefore = peer.count(0x1007), infoBefore = peer.count(0x1008);
                AtomicInteger storageAtReady = new AtomicInteger(), handlesAtReady = new AtomicInteger(), infoAtReady = new AtomicInteger();
                Result<Void> start = new Result<>() {
                    @Override public void onSuccess(Void value) {
                        storageAtReady.set(peer.count(0x1004) - storageBefore);
                        handlesAtReady.set(peer.count(0x1007) - handlesBefore);
                        infoAtReady.set(peer.count(0x1008) - infoBefore);
                        super.onSuccess(value);
                    }
                };
                controller.startPhotoLive(start); start.value();
                check(storageAtReady.get() == 1 && handlesAtReady.get() == 1 && infoAtReady.get() == 0,
                        "Fast live startup needs one fresh storage/handle inventory and zero per-object metadata reads for " + count + " old photographs");
                check(peer.count(0x1009) == 0 && peer.count(0x100a) == 0 && peer.count(0x90c4) == 0,
                        "Capturing a large old-photo baseline cannot prefetch thumbnails, previews or originals");
                long[] withNew = Arrays.copyOf(existing, count == 20000 ? count : count + 1);
                withNew[withNew.length - 1] = 30000; // Stay within the 20000-object cap for the largest inventory.
                peer.setHandles(withNew); peer.objectAdded(1); peer.objectAdded(1); peer.objectAdded(30000);
                awaitCondition(() -> controller.photoLiveState().completed == 1,
                        "An immediate new-photo event is received without waiting behind " + count + " old metadata requests");
                check(peer.count(0x1008) - infoBefore <= 8 && peer.count(0x1009, 30000) == 1,
                        "New-photo processing has bounded metadata work independent of the old inventory size");
                check(peer.count(0x1009, 1) == 0 && peer.count(0x100a, 1) == 0 && peer.count(0x90c4, 1) == 0,
                        "Repeated delayed baseline events never fetch the old photograph's preview or original");
                check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0 && controller.isConnected(),
                        "Fast baseline capture and immediate live reception reuse the same uninterrupted camera session");
            }
        }
    }

    private static void liveEventsDuringHandleInventoryAreBufferedUntilReady(Path root) throws Exception {
        Peer peer = new Peer();
        try (AlbumController controller = connected(root, peer)) {
            peer.pauseHandles = true;
            AtomicInteger metadataAtReady = new AtomicInteger(-1);
            Result<Void> start = new Result<>() {
                @Override public void onSuccess(Void value) { metadataAtReady.set(peer.count(0x1008)); super.onSuccess(value); }
            };
            controller.startPhotoLive(start);
            check(peer.handlesPaused.await(5, TimeUnit.SECONDS), "The initial handle inventory is paused after the camera captured its old-photo dataset");
            peer.setHandles(42, 43); peer.objectAdded(42); peer.objectAdded(43); peer.objectAdded(43);
            check(!start.isDone(), "Events during an incomplete handle frame cannot prematurely complete baseline startup");
            peer.pauseHandles = false; peer.releaseHandles.countDown();
            start.value();
            awaitCondition(() -> controller.photoLiveState().completed == 1, "A new handle event arriving during baseline inventory is buffered and received as soon as startup completes");
            check(metadataAtReady.get() == 0 && peer.count(0x1009, 42) == 0 && peer.count(0x1009, 43) == 1,
                    "A buffered old event remains excluded while duplicate events for a post-snapshot new handle transfer its original once");
        } finally { peer.releaseHandles.countDown(); }
    }

    private static void liveCachedBaselineMetadataCannotReplayChangesBeforeStartup(Path root) throws Exception {
        Peer peer = new Peer();
        try (AlbumController controller = connected(root, peer)) {
            AlbumControllerHostTest.<PhotoPage>call(callback -> controller.loadPage(0, 1, callback)); // A genuine but potentially stale browser descriptor.
            int metadataBefore = peer.count(0x1008, 42);
            synchronized (peer) { peer.filenames.put(42L, "CHANGED_BEFORE_LIVE.JPG"); }
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
            peer.objectAdded(42);
            awaitCondition(() -> peer.count(0x1008, 42) > metadataBefore, "The first post-start descriptor refreshes an old handle whose browser cache was stale");
            call(controller::listDownloads);
            check(controller.photoLiveState().photos.isEmpty() && peer.count(0x1009) == 0,
                    "Cached metadata from before startup cannot classify a changed baseline photograph as a new capture");
            synchronized (peer) { peer.filenames.put(42L, "CHANGED_AFTER_OBSERVATION.JPG"); }
            peer.objectAdded(42);
            awaitCondition(() -> controller.photoLiveState().completed == 1, "A later reuse after establishing a post-start baseline descriptor still receives normally");
            check(peer.count(0x1009, 42) == 1 && peer.sockets == 2 && peer.count(0x1002) == 1,
                    "Conservative cache seeding neither replays old photographs nor disables later observed handle-reuse detection");
        }
    }

    private static PhotoLiveProject projectWithId(List<PhotoLiveProject> projects, String id) {
        for (PhotoLiveProject project : projects) if (project.id.equals(id)) return project;
        throw new AssertionError("Expected durable photo-live project " + id);
    }

    private static void liveNamedProjectsSeparateRunsAndSurviveOfflineRecreation(Path root) throws Exception {
        Peer peer = new Peer(); byte[] secondOriginal = jpeg(5056, 9); peer.originals.put(44L, secondOriginal);
        String firstId, secondId; Path firstFile, secondFile;
        try (AlbumController controller = connected(root, peer)) {
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("活动A", callback));
            firstId = controller.photoLiveState().projectId;
            check(!firstId.isEmpty() && controller.photoLiveState().projectName.equals("活动A"),
                    "Named live startup publishes the durable project identity and normalized user-visible name");
            peer.setHandles(42, 43); peer.objectAdded(43);
            awaitCondition(() -> controller.photoLiveState().completed == 1, "The first named live project receives a new original");
            List<PhotoLiveProject> firstCatalog = call(controller::listPhotoLiveProjects);
            PhotoLiveProject first = projectWithId(firstCatalog, firstId);
            PhotoLiveState.LivePhoto received = controller.photoLiveState().photos.get(0);
            check(firstCatalog.size() == 1 && first.photoCount == 1 && first.name.equals("活动A")
                            && received.saved.projectId().equals(firstId) && received.saved.projectName().equals("活动A")
                            && first.photos.get(0).file().equals(received.saved.file()),
                    "The current live state, downloaded original and persisted project all refer to the same file and project");
            firstFile = first.photos.get(0).file().toPath();
            controller.stopPhotoLive();
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("活动A", callback));
            secondId = controller.photoLiveState().projectId;
            check(!secondId.equals(firstId) && controller.photoLiveState().projectName.equals("活动A")
                            && controller.photoLiveState().completed == 0 && controller.photoLiveState().photos.isEmpty(),
                    "Starting another run with the same visible name creates a new project identity and a fresh receive state");
            peer.setHandles(42, 43, 44); peer.objectAdded(43); peer.objectAdded(44);
            awaitCondition(() -> controller.photoLiveState().completed == 1, "The second same-name project receives only its later new original");
            List<PhotoLiveProject> both = call(controller::listPhotoLiveProjects);
            PhotoLiveProject old = projectWithId(both, firstId), next = projectWithId(both, secondId);
            secondFile = next.photos.get(0).file().toPath();
            check(both.size() == 2 && old.photoCount == 1 && next.photoCount == 1 && !firstFile.equals(secondFile)
                            && old.photos.get(0).file().toPath().equals(firstFile)
                            && next.photos.get(0).projectId().equals(secondId) && next.photos.get(0).projectName().equals("活动A"),
                    "Separate same-name projects cannot mix their completed originals or overwrite the earlier run's membership");
            check(peer.count(0x1009, 42) == 0 && peer.count(0x1009, 43) == 1 && peer.count(0x1009, 44) == 1,
                    "Project boundaries retain baseline exclusion and do not redownload the preceding project's original");
        }
        int commands = peer.operationCount(), sockets = peer.sockets;
        try (AlbumController offline = new AlbumController(new Context(root.toFile()))) {
            List<PhotoLiveProject> restored = call(offline::listPhotoLiveProjects);
            PhotoLiveProject first = projectWithId(restored, firstId), second = projectWithId(restored, secondId);
            check(restored.size() == 2 && first.photoCount == 1 && second.photoCount == 1
                            && first.photos.get(0).file().toPath().equals(firstFile) && second.photos.get(0).file().toPath().equals(secondFile),
                    "A recreated controller lists both durable projects and their original file membership while entirely offline");
            check(java.security.MessageDigest.isEqual(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(firstFile)),
                            java.security.MessageDigest.getInstance("SHA-256").digest(peer.original))
                            && java.security.MessageDigest.isEqual(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(secondFile)),
                            java.security.MessageDigest.getInstance("SHA-256").digest(secondOriginal)),
                    "Offline project recovery preserves the complete original hashes for both named runs");
            check(!offline.hasLiveSession() && peer.operationCount() == commands && peer.sockets == sockets,
                    "Listing local project history after recreation sends no camera commands and cannot reconnect");
        }
    }

    private static void liveInvalidProjectNamesNeverQueryOrDisconnectTheCamera(Path root) throws Exception {
        Peer peer = new Peer();
        try (AlbumController controller = connected(root, peer)) {
            int commands = peer.operationCount();
            for (String invalid : new String[] {null, "", "   ", "../bad", "A/B", "A\\B", "A\nB", "\u200b", "X".repeat(61)}) {
                Result<Void> start = new Result<>(); controller.startPhotoLive(invalid, start);
                check(!start.error().isEmpty() && !controller.photoLiveState().enabled && controller.photoLiveState().projectId.isEmpty(),
                        "An invalid project name fails startup locally without leaving an enabled or misidentified live run");
                check(peer.operationCount() == commands && controller.isConnected() && controller.hasLiveSession()
                                && peer.sockets == 2 && peer.count(0x1003) == 0,
                        "Invalid project names cannot read storage, destroy the connection or reopen the physical camera session");
            }
            check(AlbumControllerHostTest.<List<PhotoLiveProject>>call(controller::listPhotoLiveProjects).isEmpty(),
                    "Invalid project names never persist an empty or partially created catalog entry");
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("合法项目", callback));
            check(controller.photoLiveState().enabled && !controller.photoLiveState().projectId.isEmpty(),
                    "The same healthy camera session remains usable for a later valid named project");
        }
    }

    private static void liveProjectRenameWorksOfflineWithoutCameraOperations(Path root) throws Exception {
        Peer peer = new Peer(); String projectId; Path original;
        try (AlbumController controller = connected(root, peer)) {
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("拍摄项目", callback));
            projectId = controller.photoLiveState().projectId;
            peer.setHandles(42, 43); peer.objectAdded(43);
            awaitCondition(() -> controller.photoLiveState().completed == 1, "Offline rename fixture receives one complete original");
            PhotoLiveProject project = projectWithId(call(controller::listPhotoLiveProjects), projectId);
            original = project.photos.get(0).file().toPath();
            check(project.totalBytes == peer.original.length, "Project capacity is calculated from committed entry bytes");
            controller.stopPhotoLive();
        }
        int operations = peer.operationCount(), sockets = peer.sockets; long modified = Files.getLastModifiedTime(original).toMillis();
        try (AlbumController offline = new AlbumController(new Context(root.toFile()))) {
            PhotoLiveProject before = projectWithId(call(offline::listPhotoLiveProjects), projectId);
            PhotoLiveProject renamed = call(callback -> offline.renamePhotoLiveProject(projectId, "  夏日拍摄 📷  ", callback));
            check(renamed.id.equals(before.id) && renamed.createdAt == before.createdAt && renamed.name.equals("夏日拍摄 📷")
                            && renamed.photoCount == 1 && renamed.totalBytes == peer.original.length,
                    "Offline rename changes only the project name and retains ID, date, count and persisted byte total");
            check(renamed.photos.get(0).file().toPath().equals(original) && renamed.photos.get(0).projectName().equals(renamed.name)
                            && Files.getLastModifiedTime(original).toMillis() == modified && Arrays.equals(Files.readAllBytes(original), peer.original),
                    "Offline rename projects new display metadata without moving or rewriting the complete original");
            for (String invalid : new String[] {null, "", "..", "A/B", "A\nB", "x".repeat(61)}) {
                Result<PhotoLiveProject> failed = new Result<>(); offline.renamePhotoLiveProject(projectId, invalid, failed);
                check(!failed.error().isEmpty() && projectWithId(call(offline::listPhotoLiveProjects), projectId).name.equals(renamed.name),
                        "Invalid offline rename keeps the last committed name");
            }
            check(!offline.hasLiveSession() && peer.operationCount() == operations && peer.sockets == sockets,
                    "Offline rename and invalid-name handling do not read, connect or write the camera");
        }
        try (AlbumController restored = new AlbumController(new Context(root.toFile()))) {
            PhotoLiveProject project = projectWithId(call(restored::listPhotoLiveProjects), projectId);
            check(project.name.equals("夏日拍摄 📷") && project.photoCount == 1 && project.totalBytes == peer.original.length
                            && project.photos.get(0).file().toPath().equals(original),
                    "A recreated controller retains renamed project membership and capacity while offline");
        }
    }

    private static void liveProjectRenameDrainsTransferAndKeepsFutureMembership(Path root) throws Exception {
        Peer peer = new Peer(); peer.pauseOriginal = true; byte[] secondOriginal = jpeg(5056, 9); peer.originals.put(44L, secondOriginal);
        List<DownloadedPhoto> published = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (AlbumController controller = connected(root, peer)) {
            controller.setPhotoLivePublisher(photo -> { published.add(photo); return "content://synthetic/" + photo.filename(); });
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("活动旧名", callback));
            String projectId = controller.photoLiveState().projectId;
            peer.setHandles(42, 43); peer.objectAdded(43);
            check(peer.originalPaused.await(5, TimeUnit.SECONDS), "A project original is in flight before rename is queued");
            Result<PhotoLiveProject> rename = new Result<>(); controller.renamePhotoLiveProject(projectId, "活动新名", rename);
            check(!rename.isDone() && controller.photoLiveState().enabled && controller.photoLiveState().receiving
                            && controller.photoLiveState().projectName.equals("活动旧名"),
                    "Rename waits behind the framed original read without canceling reception or exposing an uncommitted name");
            peer.setHandles(42, 43, 44); peer.objectAdded(44);
            peer.pauseOriginal = false; peer.releaseOriginal.countDown();
            PhotoLiveProject renamed = rename.value();
            check(renamed.id.equals(projectId) && renamed.photoCount == 1 && renamed.name.equals("活动新名")
                            && renamed.totalBytes == peer.original.length,
                    "Durable rename occurs after the in-flight original completes and retains its committed bytes");
            PhotoLiveState state = controller.photoLiveState();
            PhotoLiveState.LivePhoto first = state.photos.stream().filter(photo -> photo.photo.handle() == 43).findFirst().orElseThrow();
            check(state.enabled && state.projectId.equals(projectId) && state.projectName.equals("活动新名")
                            && first.saved.projectName().equals("活动新名") && first.saved.projectId().equals(projectId)
                            && first.galleryUri.equals("content://synthetic/" + first.photo.filename()),
                    "Live state and saved candidates change their display name coherently while preserving the published URI");
            awaitCondition(() -> controller.photoLiveState().completed == 2, "Queued later photograph continues into the renamed project");
            PhotoLiveProject after = projectWithId(call(controller::listPhotoLiveProjects), projectId);
            check(after.photoCount == 2 && after.totalBytes == (long) peer.original.length + secondOriginal.length
                            && after.photos.stream().allMatch(photo -> photo.projectId().equals(projectId) && photo.projectName().equals("活动新名")),
                    "Future originals and prior entries retain one UUID and expose the renamed project metadata");
            check(published.size() == 2 && published.get(0).projectName().equals("活动旧名") && published.get(1).projectName().equals("活动新名")
                            && peer.count(0x1009, 43) == 1 && peer.count(0x1009, 44) == 1,
                    "Rename does not republish or redownload old originals and future publication receives the new display name");
            Result<PhotoLiveProject> invalid = new Result<>(); controller.renamePhotoLiveProject(projectId, "../bad", invalid);
            check(!invalid.error().isEmpty() && controller.photoLiveState().enabled && controller.photoLiveState().projectName.equals("活动新名"),
                    "Invalid active rename preserves both the durable name and ongoing reception");
            controller.stopPhotoLive(); AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("另一个项目", callback));
            String otherId = controller.photoLiveState().projectId;
            AlbumControllerHostTest.<PhotoLiveProject>call(callback -> controller.renamePhotoLiveProject(projectId, "历史新名称", callback));
            check(!otherId.equals(projectId) && controller.photoLiveState().projectId.equals(otherId)
                            && controller.photoLiveState().projectName.equals("另一个项目") && controller.photoLiveState().enabled,
                    "Renaming a historical project cannot relabel or stop a different active run");
            peer.setHandles(42, 43, 44, 45); peer.objectAdded(45);
            awaitCondition(() -> controller.photoLiveState().completed == 1, "The other active project still receives its own next original");
            List<PhotoLiveProject> catalog = call(controller::listPhotoLiveProjects);
            check(projectWithId(catalog, projectId).name.equals("历史新名称") && projectWithId(catalog, projectId).photoCount == 2
                            && projectWithId(catalog, otherId).photoCount == 1 && published.get(2).projectId().equals(otherId),
                    "Historical rename and new-run membership remain isolated after stop and restart");
            check(controller.hasLiveSession() && controller.isConnected() && peer.sockets == 2 && peer.count(0x1002) == 1
                            && peer.count(0x1003) == 0,
                    "Active rename and later run boundaries retain the same healthy physical camera session");
        } finally { peer.releaseOriginal.countDown(); }
    }

    private static void liveBusyAndCancelledStartupCannotMisclassifyTheNextProject(Path root) throws Exception {
        for (boolean cancel : new boolean[] {false, true}) {
            Peer peer = new Peer();
            try (AlbumController controller = connected(root.resolve(cancel ? "cancel" : "busy"), peer)) {
                AtomicInteger cancelledCallbacks = new AtomicInteger();
                Result<Void> oldStart = new Result<>() {
                    @Override public void onError(String error) { cancelledCallbacks.incrementAndGet(); super.onError(error); }
                };
                if (cancel) peer.pauseHandles = true; else peer.storageResponseCode = 0x2019;
                int storageBefore = peer.count(0x1004);
                controller.startPhotoLive("未接收项目", oldStart);
                if (cancel) check(peer.handlesPaused.await(5, TimeUnit.SECONDS), "Named startup pauses in its initial inventory after persisting an empty project");
                else {
                    awaitCondition(() -> peer.count(0x1004) > storageBefore, "A busy camera defers initial inventory after the named project was persisted");
                    call(controller::listDownloads);
                }
                String emptyId = controller.photoLiveState().projectId;
                check(!emptyId.isEmpty() && controller.photoLiveState().starting && controller.photoLiveState().completed == 0,
                        "Busy or pending startup has one identified empty project and no completed photographs");
                Result<Void> duplicate = new Result<>(); controller.startPhotoLive("不应创建", duplicate);
                check(!duplicate.error().isEmpty() && controller.photoLiveState().projectId.equals(emptyId),
                        "A second start while preparation is active is rejected without replacing the pending project");
                if (cancel) {
                    controller.stopPhotoLive();
                    check(!oldStart.error().isEmpty() && cancelledCallbacks.get() == 1, "Stopping named startup completes its original callback exactly once");
                } else {
                    peer.storageResponseCode = 0x2001; peer.objectAdded(42); oldStart.value();
                    check(controller.photoLiveState().projectId.equals(emptyId), "Retrying a busy initial inventory reuses the one empty project rather than creating another");
                    controller.stopPhotoLive();
                }
                Result<Void> nextStart = new Result<>(); controller.startPhotoLive("后续项目", nextStart);
                peer.pauseHandles = false; peer.releaseHandles.countDown(); nextStart.value();
                String nextId = controller.photoLiveState().projectId;
                peer.setHandles(42, 43); peer.objectAdded(42); peer.objectAdded(43);
                awaitCondition(() -> controller.photoLiveState().completed == 1, "A replacement named run receives its own new original after old startup retires");
                List<PhotoLiveProject> projects = call(controller::listPhotoLiveProjects);
                PhotoLiveProject empty = projectWithId(projects, emptyId), next = projectWithId(projects, nextId);
                check(projects.size() == 2 && empty.photoCount == 0 && empty.name.equals("未接收项目")
                                && !emptyId.equals(nextId) && next.photoCount == 1 && next.name.equals("后续项目")
                                && next.photos.get(0).projectId().equals(nextId),
                        "Busy and cancelled startup leave only their own empty project and cannot classify a later run's original into it");
                check(peer.count(0x1009, 42) == 0 && peer.count(0x1009, 43) == 1 && peer.sockets == 2
                                && peer.count(0x1002) == 1 && peer.count(0x1003) == 0 && (!cancel || cancelledCallbacks.get() == 1),
                        "Late startup completion cannot replay old photographs, duplicate callbacks or reconnect across project boundaries");
            } finally { peer.releaseHandles.countDown(); }
        }
    }

    private static void cancellingOldBrowseRequestsDrainsOnlyOneFrameBeforeFastLiveStartup(Path root) throws Exception {
        Peer peer = new Peer(); peer.setHandles(40, 41, 42); peer.pauseMetadata = true;
        try (AlbumController controller = connected(root, peer)) {
            AtomicInteger pageErrors = new AtomicInteger(), thumbnailErrors = new AtomicInteger();
            Result<PhotoPage> page = new Result<>() {
                @Override public void onError(String message) { pageErrors.incrementAndGet(); super.onError(message); }
            };
            controller.loadPage(0, 24, page);
            check(peer.metadataPaused.await(5, TimeUnit.SECONDS), "An old browse page pauses inside its first complete PTP metadata frame");
            String session = (String) privateField(controller, "session").get(controller);
            PhotoItem old = new PhotoItem(session, 40, new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801)
                    .compressedSize(peer.original.length).filename("OLD.JPG").build());
            Result<byte[]> thumbnail = new Result<>() {
                @Override public void onError(String message) { thumbnailErrors.incrementAndGet(); super.onError(message); }
            };
            controller.loadThumbnail(old, thumbnail);
            controller.cancelBrowseRequests();
            Result<Void> start = new Result<>(); controller.startPhotoLive(start);
            check(!start.isDone(), "Fast live startup waits for the existing framed read rather than interleaving another camera transaction");
            peer.pauseMetadata = false; peer.releaseMetadata.countDown();
            check(!page.error().isEmpty() && !thumbnail.error().isEmpty(), "Cancelled page and queued thumbnail requests both settle with a cancellation error");
            start.value(); call(controller::listDownloads);
            check(pageErrors.get() == 1 && thumbnailErrors.get() == 1 && peer.count(0x1008) == 1
                            && peer.count(0x100a) == 0 && peer.count(0x90c4) == 0,
                    "Browse cancellation drains only one metadata frame, skips the remaining page and queued thumbnail, and completes each callback once");
            check(controller.hasLiveSession() && controller.isConnected() && peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                    "Cancelling obsolete browse work cannot disconnect, replace or reopen the live camera session");
            PhotoPage fresh = call(callback -> controller.loadPage(0, 1, callback));
            byte[] nextThumbnail = call(callback -> controller.loadThumbnail(fresh.items().get(0), callback));
            check(fresh.items().size() == 1 && Arrays.equals(nextThumbnail, peer.thumbnail),
                    "New browse requests after cancellation still use the current session and complete normally");
        } finally { peer.releaseMetadata.countDown(); }
    }

    private static Peer twoStoragePeer() {
        Peer peer = new Peer();
        peer.storageListOverride = concat(le32(2), le32(1), le32(2));
        peer.handlesByStorage.put(2L, new long[0]);
        return peer;
    }

    private static void liveEmptyCardSlotDoesNotStopTheReadableCard(Path root) throws Exception {
        for (boolean fallback : new boolean[] {false, true}) {
            Peer peer = twoStoragePeer(); peer.handlesResponseCodes.put(2L, 0x2013);
            if (fallback) peer.unsupportedFormatStorages.add(2L);
            try (AlbumController controller = connected(root.resolve(fallback ? "format-fallback" : "direct"), peer)) {
                AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
                check(controller.photoLiveState().enabled && !controller.photoLiveState().starting && controller.isConnected(),
                        "An unavailable second slot cannot stop the readable card's live baseline, including format-filter fallback");
                peer.setHandles(42, 43); peer.objectAdded(43);
                awaitCondition(() -> controller.photoLiveState().completed == 1, "The readable card receives its new JPEG while the other slot remains unavailable");
                check(peer.count(0x1009, 42) == 0 && peer.count(0x1009, 43) == 1,
                        "Skipping an empty card slot neither downloads the old baseline nor duplicates the new original");
                check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0 && peer.count(0x90cd) == 0,
                        "A card-local StoreNotAvailable response does not reconnect, reopen, close or write the camera session");
            }
        }
    }

    private static void liveUnavailableSlotRecoveryBaselinesOldPhotos(Path root) throws Exception {
        Peer peer = twoStoragePeer(); peer.handlesByStorage.put(2L, new long[] {52, 53});
        peer.storageIds.put(52L, 2L); peer.storageIds.put(53L, 2L); peer.storageIds.put(54L, 2L);
        peer.handlesResponseCodes.put(2L, 0x2013);
        try (AlbumController controller = connected(root, peer)) {
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
            int previousInventories = peer.count(0x1007, 2);
            synchronized (peer) { peer.handlesResponseCodes.remove(2L); }
            awaitCondition(() -> peer.count(0x1007, 2) > previousInventories,
                    "The first successful inventory of an initially unavailable slot establishes its own old-photo baseline");
            call(controller::listDownloads);
            check(controller.photoLiveState().photos.isEmpty() && peer.count(0x1009) == 0,
                    "Making a previously unavailable card readable never automatically downloads the old photographs on that card");
            synchronized (peer) { peer.handlesByStorage.put(2L, new long[] {52, 53, 54}); }
            peer.objectAdded(54); peer.objectAdded(52);
            awaitCondition(() -> controller.photoLiveState().completed == 1, "A later new JPEG on the recovered card is automatically received");
            check(peer.count(0x1009, 52) == 0 && peer.count(0x1009, 53) == 0 && peer.count(0x1009, 54) == 1,
                    "Late baseline events remain ignored after slot recovery and only its later new JPEG is downloaded once");
        }
    }

    private static void liveOldMetadataUnavailabilityDoesNotBlockFastBaselineOrNewPhotos(Path root) throws Exception {
        for (int code : new int[] {0x2013, 0x2019}) {
            Peer peer = twoStoragePeer(); peer.handlesByStorage.put(2L, new long[] {52});
            peer.storageIds.put(52L, 2L); peer.storageIds.put(53L, 2L);
            peer.metadataBusyCode = code; peer.metadataBusy.put(42L, 1000); peer.metadataBusy.put(52L, 1000);
            try (AlbumController controller = connected(root.resolve(Integer.toHexString(code)), peer, 2)) {
                AtomicInteger metadataAtReady = new AtomicInteger(-1);
                Result<Void> start = new Result<>() {
                    @Override public void onSuccess(Void value) { metadataAtReady.set(peer.count(0x1008)); super.onSuccess(value); }
                };
                controller.startPhotoLive(start); start.value();
                check(!controller.photoLiveState().starting && controller.photoLiveState().enabled && metadataAtReady.get() == 0,
                        "Old metadata that remains busy or storage-unavailable cannot delay handle-only baseline readiness");
                awaitCondition(() -> peer.count(0x1008, 42) > 0,
                        "After readiness an idle old-descriptor read actually observes the persistent busy or storage-unavailable response");
                synchronized (peer) { peer.handlesByStorage.put(2L, new long[] {52, 53}); }
                peer.objectAdded(42); peer.objectAdded(52); peer.objectAdded(53);
                awaitCondition(() -> controller.photoLiveState().completed == 1,
                        "A new JPEG is received while delayed events for both old cards still return unavailable metadata");
                check(peer.count(0x1009, 42) == 0 && peer.count(0x1009, 52) == 0 && peer.count(0x1009, 53) == 1,
                        "Deferring inaccessible old metadata never turns either card's baseline inventory into new originals");
                synchronized (peer) { peer.metadataBusy.remove(42L); peer.setHandles(42, 43); }
                peer.objectAdded(42); peer.objectAdded(43);
                awaitCondition(() -> controller.photoLiveState().completed == 2,
                        "A card whose old metadata recovers receives its later new JPEG without preparing all old descriptors");
                check(peer.count(0x1009, 42) == 0 && peer.count(0x1009, 43) == 1 && peer.count(0x1009, 52) == 0
                                && peer.count(0x1009, 53) == 1 && peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                        "Unknown old descriptors remain excluded after recovery while new handles transfer once on the original session");
            }
        }
    }

    private static void liveLazyBaselineFingerprintingNeverReplaysUnknownOldHandles(Path root) throws Exception {
        Peer peer = new Peer(); peer.setHandles(42, 84);
        try (AlbumController controller = connected(root, peer)) {
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
            check(peer.count(0x1008, 84) == 0, "A descriptor which has never been read stays unknown at fast startup");
            synchronized (peer) { peer.filenames.put(84L, "UNOBSERVED_REUSE.JPG"); }
            peer.objectAdded(84); peer.objectAdded(84);
            awaitCondition(() -> peer.count(0x1008, 84) > 0, "Idle sampling eventually establishes an unknown baseline handle's first descriptor despite delayed old events");
            call(controller::listDownloads);
            check(peer.count(0x1009, 84) == 0 && controller.photoLiveState().photos.isEmpty(),
                    "First metadata for an unknown baseline handle is conservative: it cannot replay an old photograph even after an unobserved identity change");
            awaitCondition(() -> peer.count(0x1008, 42) > 0, "Idle lazy sampling establishes a baseline fingerprint without reading every old object first");
            call(controller::listDownloads);
            check(peer.count(0x1009) == 0 && peer.count(0x90c4) == 0 && peer.count(0x100a) == 0,
                    "Idle baseline sampling seeds identities without prefetching old pixels or originals");
            synchronized (peer) { peer.filenames.put(42L, "OBSERVED_REUSE.JPG"); }
            peer.objectAdded(42); peer.objectAdded(42);
            awaitCondition(() -> controller.photoLiveState().completed == 1,
                    "A changed handle whose prior identity was lazily observed still receives its newly reused photograph");
            synchronized (peer) { peer.filenames.put(84L, "LATER_REUSE.JPG"); }
            peer.objectAdded(84);
            awaitCondition(() -> controller.photoLiveState().completed == 2,
                    "A later identity change after an event seeded the baseline descriptor is detected normally");
            check(peer.count(0x1009, 42) == 1 && peer.count(0x1009, 84) == 1 && peer.sockets == 2 && peer.count(0x1002) == 1,
                    "Lazy and event-based baseline fingerprints retain reuse detection and duplicate-event suppression without reconnects");
        }
    }

    private static void liveUnknownStorageEventCannotBypassItsFirstRecoveredBaseline(Path root) throws Exception {
        Peer peer = twoStoragePeer(); peer.handlesByStorage.put(2L, new long[] {52, 53});
        peer.storageIds.put(52L, 2L); peer.storageIds.put(53L, 2L); peer.storageIds.put(54L, 2L);
        peer.handlesResponseCodes.put(2L, 0x2013);
        try (AlbumController controller = connected(root, peer)) {
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
            int before = peer.count(0x1007, 2);
            synchronized (peer) { peer.handlesResponseCodes.remove(2L); }
            peer.objectAdded(52); peer.objectAdded(52); // Metadata is now readable, before the scheduled recovery poll.
            awaitCondition(() -> peer.count(0x1007, 2) > before, "An unknown event from an uninitialized slot forces a fresh inventory before admission");
            call(controller::listDownloads);
            check(peer.count(0x1009) == 0 && controller.photoLiveState().photos.isEmpty(),
                    "A now-readable old event cannot bypass a previously unavailable slot's first successful baseline");
            synchronized (peer) { peer.handlesByStorage.put(2L, new long[] {52, 53, 54}); }
            peer.objectAdded(53); peer.objectAdded(54);
            awaitCondition(() -> controller.photoLiveState().completed == 1, "New photographs on the initialized recovered slot still receive normally");
            check(peer.count(0x1009, 52) == 0 && peer.count(0x1009, 53) == 0 && peer.count(0x1009, 54) == 1
                            && peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                    "Recovered-slot baseline events remain old while a later new handle transfers once without a new session");
        }
    }

    private static void liveTemporaryCardOutagePreservesKnownIdentities(Path root) throws Exception {
        Peer peer = twoStoragePeer(); peer.handlesByStorage.put(2L, new long[] {52});
        peer.storageIds.put(52L, 2L); peer.storageIds.put(53L, 2L);
        try (AlbumController controller = connected(root, peer, 2)) {
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
            int reads = peer.count(0x1007, 2);
            synchronized (peer) { peer.handlesResponseCodes.put(2L, 0x2013); }
            awaitCondition(() -> peer.count(0x1007, 2) > reads, "Polling observes a previously baselined card becoming temporarily unavailable");
            call(controller::listDownloads);
            int metadata = peer.count(0x1008, 52);
            peer.objectAdded(52); peer.setHandles(42, 43); peer.objectAdded(43);
            awaitCondition(() -> controller.photoLiveState().completed == 1, "Another card continues receiving while the first card is temporarily unavailable");
            check(peer.count(0x1008, 52) == metadata && controller.photoLiveState().enabled,
                    "A delayed event for a known unavailable slot is deferred rather than repeatedly reading its inaccessible metadata");
            synchronized (peer) { peer.handlesResponseCodes.remove(2L); peer.handlesByStorage.put(2L, new long[] {52, 53}); }
            awaitCondition(() -> controller.photoLiveState().completed == 2, "A new JPEG captured during a known card's outage is recovered by polling");
            check(peer.count(0x1009, 42) == 0 && peer.count(0x1009, 52) == 0 && peer.count(0x1009, 43) == 1 && peer.count(0x1009, 53) == 1,
                    "Temporary card absence retains old identities and receives only new originals across both cards");
            check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                    "Card recovery and missed-event compensation reuse the same uninterrupted session");
            synchronized (peer) { peer.handlesResponseCodes.put(1L, 0x2013); peer.handlesResponseCodes.put(2L, 0x2013); }
            awaitCondition(() -> !controller.photoLiveState().error.isEmpty(), "A complete outage after startup is exposed as temporary storage waiting");
            check(controller.photoLiveState().enabled && controller.isConnected(), "Losing all usable cards does not discard the live session or completed queue identities");
            synchronized (peer) { peer.handlesResponseCodes.clear(); }
            peer.objectAdded(42);
            awaitCondition(() -> controller.photoLiveState().error.isEmpty(), "Successful storage recovery clears its waiting notice without requiring another new photograph");
            check(controller.photoLiveState().completed == 2 && peer.count(0x1009) == 2,
                    "All-card recovery neither replays completed originals nor downloads the original baseline");
        }
    }

    private static void liveNoAvailableStorageWaitsWithoutCompletingABlindBaseline(Path root) throws Exception {
        for (String unavailable : List.of("slots", "storage_ids", "empty_list")) {
            Peer peer = twoStoragePeer();
            try (AlbumController controller = connected(root.resolve(unavailable), peer)) {
                int operation = unavailable.equals("slots") ? 0x1007 : 0x1004;
                int before = peer.count(operation);
                synchronized (peer) {
                    if (unavailable.equals("slots")) { peer.handlesResponseCodes.put(1L, 0x2013); peer.handlesResponseCodes.put(2L, 0x2013); }
                    else if (unavailable.equals("storage_ids")) peer.storageResponseCode = 0x2013;
                    else peer.storageListOverride = le32(0);
                }
                Result<Void> start = new Result<>(); controller.startPhotoLive(start);
                awaitCondition(() -> peer.count(operation) > before, "Unavailable card discovery begins on the existing session");
                Thread.sleep(100);
                check(!start.isDone() && controller.photoLiveState().enabled && controller.photoLiveState().starting,
                        "No available card leaves live startup pending instead of accepting an empty baseline and old photos later");
                int expected = unavailable.equals("slots") ? before + 8 : before + 4;
                if (!unavailable.equals("empty_list")) {
                    awaitCondition(() -> peer.count(operation) >= expected, "Persistent StoreNotAvailable passes the normal startup retry limit with bounded waiting");
                    call(controller::listDownloads);
                    check(!start.isDone() && controller.photoLiveState().enabled && controller.isConnected(),
                            "Four unavailable-storage responses do not stop live startup or terminate its healthy session");
                }
                synchronized (peer) {
                    peer.handlesResponseCodes.clear(); peer.storageResponseCode = 0x2001;
                    peer.storageListOverride = concat(le32(2), le32(1), le32(2));
                }
                peer.objectAdded(42); start.value();
                check(!controller.photoLiveState().starting && peer.count(0x1009) == 0,
                        "Storage recovery completes the real baseline before accepting any original download");
                peer.setHandles(42, 43); peer.objectAdded(43);
                awaitCondition(() -> controller.photoLiveState().completed == 1, "Live reception resumes automatically after storage becomes usable");
                check(peer.count(0x1009, 42) == 0 && peer.count(0x1009, 43) == 1 && peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                        "Waiting for storage never replays old photographs or reconnects the physical camera session");
            }
        }
    }

    private static void liveStorageErrorsDoNotHideTerminalSessionFailures(Path root) throws Exception {
        for (int response : new int[] {0x2003, 0x2004, 0}) {
            Peer peer = twoStoragePeer();
            try (AlbumController controller = connected(root.resolve(Integer.toHexString(response)), peer)) {
                if (response == 0) peer.handlesWrongTransaction = true;
                else peer.handlesResponseCodes.put(2L, response);
                Result<Void> start = new Result<>(); controller.startPhotoLive(start);
                check(!start.error().isEmpty(), "Invalid-session and mismatched-transaction storage reads still fail live startup");
                awaitCondition(() -> !controller.hasLiveSession() && !controller.photoLiveState().enabled,
                        "Recoverable empty-slot handling never hides a genuinely invalid or desynchronized camera session");
                check(peer.count(0x1009) == 0 && peer.sockets == 2 && peer.count(0x1002) == 1,
                        "Terminal storage errors cannot download old photographs, reconnect or reopen the camera");
            }
        }
    }

    private static void livePhotoStoreUnavailableResponseRetriesWithoutClosing(Path root) throws Exception {
        for (boolean metadata : new boolean[] {false, true}) {
            Peer peer = new Peer();
            if (metadata) peer.metadataBusyCode = 0x2013; else peer.originalBusyCode = 0x2013;
            try (AlbumController controller = connected(root.resolve(metadata ? "metadata" : "original"), peer)) {
                AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
                synchronized (peer) { (metadata ? peer.metadataBusy : peer.originalBusy).put(43L, 1); }
                peer.setHandles(42, 43); peer.objectAdded(43);
                awaitCondition(() -> controller.photoLiveState().completed == 1, "A complete photo-local StoreNotAvailable response receives bounded automatic retry");
                check(peer.count(0x1009, 42) == 0 && peer.count(0x1009, 43) == (metadata ? 1 : 2)
                                && controller.photoLiveState().enabled && peer.sockets == 2 && peer.count(0x1002) == 1,
                        "A temporary photo storage response preserves live reception and does not replay old originals or reconnect");
            }
        }
    }

    private static void liveEventsAndPollingReceiveNewPhotosOnceWithPreviewFirst(Path root) throws Exception {
        Peer peer = new Peer(); peer.advertiseLargeThumbnail = true;
        try (AlbumController controller = connected(root, peer)) {
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
            peer.setHandles(42, 43, 44); peer.objectAdded(43); peer.objectAdded(43);
            awaitCondition(() -> controller.photoLiveState().completed == 2, "An event photo and a missed-event photo both finish automatically");
            PhotoLiveState result = controller.photoLiveState();
            check(result.photos.size() == 2 && peer.count(0x1009, 42) == 0 && peer.count(0x1009, 43) == 1 && peer.count(0x1009, 44) == 1,
                    "Duplicate ObjectAdded events and poll compensation share one original attempt per new JPEG");
            for (PhotoLiveState.LivePhoto entry : result.photos) {
                check(entry.saved != null && Arrays.equals(Files.readAllBytes(entry.saved.file().toPath()), peer.original),
                        "Live reception commits the complete original bytes to its app photo file");
                check(peer.first(0x90c4, entry.photo.handle()) >= 0 && peer.first(0x90c4, entry.photo.handle()) < peer.first(0x1009, entry.photo.handle()),
                        "A camera-generated large preview is acquired before automatically receiving the original");
            }
            check(peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0 && peer.count(0x90cd) == 0,
                    "Live discovery never reconnects, reopens, closes the session, or writes camera data");
            controller.stopPhotoLive();
        }
    }

    private static void liveTransferStagesFollowPreviewBytesAndIndependentPublication(Path root) throws Exception {
        Peer peer = new Peer(); peer.pauseThumbnail = true; peer.pauseOriginal = true;
        var publications = new java.util.concurrent.LinkedBlockingQueue<AlbumController.Callback<String>>();
        var observed = new java.util.concurrent.CopyOnWriteArrayList<PhotoLiveState>();
        try (AlbumController controller = connected(root, peer)) {
            controller.addPhotoLiveObserver(() -> observed.add(controller.photoLiveState()));
            controller.setPhotoLiveAsyncPublisher((photo, callback) -> publications.add(callback));
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
            check(controller.photoLiveState().transferStage.isEmpty() && controller.photoLiveState().currentIdentity.isEmpty(),
                    "Waiting for a new photograph has no fake byte progress or current identity");
            peer.setHandles(42, 43); peer.objectAdded(43);
            check(peer.thumbnailPaused.await(5, TimeUnit.SECONDS), "Synthetic preview is paused before any original byte acquisition");
            PhotoLiveState preview = controller.photoLiveState();
            check(preview.transferStage.equals("preview") && !preview.currentIdentity.isEmpty()
                            && preview.currentFilename.equals("SYNTHETIC.JPG") && preview.receivedBytes == 0,
                    "The preview stage exposes the precise current object without claiming original transfer progress");
            peer.releaseThumbnail.countDown();
            check(peer.originalPaused.await(5, TimeUnit.SECONDS), "Synthetic original pauses with its transfer still incomplete");
            PhotoLiveState receiving = controller.photoLiveState();
            check(receiving.transferStage.equals("receiving") && receiving.currentIdentity.equals(preview.currentIdentity)
                            && receiving.totalBytes == peer.original.length && receiving.receivedBytes < receiving.totalBytes,
                    "Original receiving publishes the camera's actual byte total under the same stable photo identity");
            peer.releaseOriginal.countDown();
            AlbumController.Callback<String> publication = publications.poll(10, TimeUnit.SECONDS);
            check(publication != null, "The completed original reaches its independent gallery publisher");
            AlbumControllerHostTest.<Void>call(controller::keepAlive);
            PhotoLiveState saving = controller.photoLiveState();
            check(saving.transferStage.equals("saving") && saving.currentIdentity.equals(preview.currentIdentity)
                            && saving.receivedBytes == peer.original.length && saving.totalBytes == peer.original.length
                            && livePhoto(controller, 43).saved != null && livePhoto(controller, 43).galleryUri.isEmpty(),
                    "One hundred percent received remains saving until gallery publication is confirmed, even after the camera worker is free");
            check(observed.stream().anyMatch(state -> state.transferStage.equals("saving") && state.receivedBytes == state.totalBytes
                            && state.photos.stream().anyMatch(photo -> photo.photo.handle() == 43 && photo.saved == null)),
                    "Saving begins immediately after the actual byte read and before durable private-file commit");
            check(preview.transferStage.equals("preview") && preview.receivedBytes == 0,
                    "Previously published transfer stages and byte snapshots remain immutable");
            publication.onError("Synthetic gallery failure");
            PhotoLiveState failed = controller.photoLiveState();
            check(failed.transferStage.isEmpty() && failed.currentIdentity.isEmpty() && failed.currentFilename.isEmpty()
                            && livePhoto(controller, 43).saved != null && !livePhoto(controller, 43).galleryError.isEmpty(),
                    "A gallery failure ends the save spinner truthfully while retaining the original and explicit retry error");
            controller.retryPhotoLive();
            AlbumController.Callback<String> retry = publications.poll(10, TimeUnit.SECONDS);
            check(retry != null && controller.photoLiveState().transferStage.equals("saving") && peer.count(0x1009, 43) == 1,
                    "Retrying a failed gallery save exposes saving without retransmitting an original");
            retry.onSuccess("content://synthetic/progress/43");
            check(controller.photoLiveState().transferStage.isEmpty() && !livePhoto(controller, 43).galleryUri.isEmpty()
                            && livePhoto(controller, 43).galleryError.isEmpty(),
                    "Only a confirmed gallery callback completes saving and clears its separate failure");
            check(controller.isConnected() && peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                    "Transfer phase reporting never changes connection ownership or camera protocol operations");
            controller.stopPhotoLive();
        } finally { peer.releaseThumbnail.countDown(); peer.releaseOriginal.countDown(); }
    }

    private static void liveTemporaryCameraErrorsBackOffWithoutReconnecting(Path root) throws Exception {
        for (boolean metadata : new boolean[] {true, false}) {
            Peer peer = new Peer();
            try (AlbumController controller = connected(root.resolve(metadata ? "not-ready" : "busy"), peer)) {
                AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
                synchronized (peer) { (metadata ? peer.metadataBusy : peer.originalBusy).put(43L, 1); }
                peer.setHandles(42, 43); peer.objectAdded(43);
                int operation = metadata ? 0x1008 : 0x1009;
                awaitCondition(() -> peer.count(operation, 43) >= 1, "The synthetic new photo reports a complete temporary camera error");
                Thread.sleep(100);
                check(peer.count(operation, 43) == 1, "A temporarily unavailable file is not retried in a hot loop");
                check(controller.photoLiveState().enabled && controller.isConnected(), "A complete busy or not-ready response preserves live reception and its healthy session");
                try { awaitCondition(() -> controller.photoLiveState().completed == 1, "The new JPEG completes after bounded read-only backoff"); }
                catch (AssertionError failure) {
                    PhotoLiveState observed = controller.photoLiveState();
                    throw new AssertionError("Live temporary-response regression: metadata=" + metadata + " enabled=" + observed.enabled
                            + " error=" + observed.error + " entries=" + observed.photos.stream().map(p -> p.status + ":" + p.error).toList()
                            + " metadataRequests=" + peer.count(0x1008, 43) + " originalRequests=" + peer.count(0x1009, 43), failure);
                }
                check(peer.count(0x1009, 43) == (metadata ? 1 : 2) && peer.sockets == 2 && peer.count(0x1002) == 1,
                        "Only a rejected complete original attempt is repeated, with no reconnect or old-photo transfer");
                controller.stopPhotoLive();
            }
        }
    }

    /** Exercises the bounded worker queue without transferring hundreds of irrelevant originals. */
    @SuppressWarnings("unchecked")
    private static void liveQueueBackpressureDoesNotExhaustCameraRetries(Path root) throws Exception {
        Peer peer = new Peer();
        try (AlbumController controller = connected(root, peer)) {
            PtpIpClient client = (PtpIpClient) privateField(controller, "active").get(controller);
            Class<?> runType = Arrays.stream(AlbumController.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("LiveRun")).findFirst().orElseThrow();
            var constructor = runType.getDeclaredConstructor(AlbumController.class, PhotoCameraClient.class,
                    int.class, String.class, AlbumController.Callback.class);
            constructor.setAccessible(true);
            int generation = ((AtomicInteger) privateField(controller, "generation").get(controller)).get();
            Object run = constructor.newInstance(controller, client, generation, "Queue backpressure", new Result<Void>());
            privateField(controller, "liveRun").set(controller, run);
            privateField(run, "baselineRead").setBoolean(run, true);
            privateField(run, "starting").setBoolean(run, false);
            privateField(run, "lastScan").setLong(run, System.nanoTime());
            ((java.util.Set<Long>) privateField(run, "initializedStorages").get(run)).add(1L);
            // Hold the scheduler so every iteration is explicitly executed on the real serial worker.
            var clock = (java.util.concurrent.ScheduledExecutorService) privateField(controller, "liveClock").get(controller);
            privateField(run, "timer").set(run, clock.schedule(() -> { }, 1, TimeUnit.DAYS));
            privateField(run, "due").setLong(run, Long.MIN_VALUE);
            var references = (Map<Long, Long>) privateField(run, "references").get(run);
            references.put(43L, 1L);
            var inspect = (java.util.Set<Long>) privateField(run, "inspect").get(run);
            inspect.add(43L);
            var attempts = (Map<Long, Integer>) privateField(run, "metadataAttempts").get(run);
            var ready = (Map<Long, Long>) privateField(run, "metadataReady").get(run);
            var queue = (Deque<Object>) privateField(run, "queue").get(run);
            Class<?> candidateType = Arrays.stream(AlbumController.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("LiveCandidate")).findFirst().orElseThrow();
            var candidateConstructor = candidateType.getDeclaredConstructor(String.class, PhotoItem.class);
            candidateConstructor.setAccessible(true);
            PhotoItem placeholder = new PhotoItem((String) privateField(controller, "session").get(controller), 42,
                    new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).compressedSize(peer.original.length)
                            .filename("SYNTHETIC.JPG").build());
            for (int i = 0; i < 128; i++) {
                Object waiting = candidateConstructor.newInstance("waiting-" + i, placeholder);
                privateField(waiting, "ready").setLong(waiting, Long.MAX_VALUE);
                queue.addLast(waiting);
            }
            var work = AlbumController.class.getDeclaredMethod("runLiveWork", runType); work.setAccessible(true);
            var worker = (java.util.concurrent.ExecutorService) privateField(controller, "worker").get(controller);
            for (int i = 0; i < 6; i++) {
                ready.remove(43L); // Advance this handle's local 250 ms backpressure deadline deterministically.
                worker.submit(() -> { try { work.invoke(controller, run); }
                    catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); } }).get(5, TimeUnit.SECONDS);
                check(ready.containsKey(43L) && ready.get(43L) != Long.MAX_VALUE && inspect.contains(43L),
                        "Local queue pressure stays retryable after more than four inspection rounds");
                check(!attempts.containsKey(43L) && queue.size() == 128,
                        "A full local queue never consumes camera metadata retries or exceeds its bound");
            }
            queue.removeFirst(); ready.remove(43L);
            worker.submit(() -> { try { work.invoke(controller, run); }
                catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); } }).get(5, TimeUnit.SECONDS);
            Object accepted = queue.peekLast();
            check(queue.size() == 128 && ((PhotoItem) privateField(accepted, "photo").get(accepted)).handle() == 43,
                    "The deferred new photo enters the queue as soon as a waiting slot is available");
            check(!inspect.contains(43L) && !ready.containsKey(43L) && !attempts.containsKey(43L),
                    "Successfully enqueued metadata retires its backpressure inspection state");
            check(peer.count(0x1009) == 0 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0 && peer.sockets == 2,
                    "Queue backpressure alone never downloads, reconnects, reopens or closes the camera session");
        }
    }

    private static java.lang.reflect.Field privateField(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }

    @SuppressWarnings("unchecked")
    private static void liveHistoryRetainsOutstandingRetriesAndBoundsPendingIdentity(Path root) throws Exception {
        Peer peer = new Peer();
        try (AlbumController controller = connected(root, peer)) {
            Class<?> runType = Arrays.stream(AlbumController.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("LiveRun")).findFirst().orElseThrow();
            var constructor = runType.getDeclaredConstructor(AlbumController.class, PhotoCameraClient.class,
                    int.class, String.class, AlbumController.Callback.class); constructor.setAccessible(true);
            Object run = constructor.newInstance(controller, privateField(controller, "active").get(controller),
                    ((AtomicInteger) privateField(controller, "generation").get(controller)).get(), "History bounds", new Result<Void>());
            privateField(controller, "liveRun").set(controller, run);
            privateField(run, "baselineRead").setBoolean(run, true);
            privateField(run, "starting").setBoolean(run, false);
            privateField(run, "lastScan").setLong(run, System.nanoTime());
            ((java.util.Set<Long>) privateField(run, "initializedStorages").get(run)).add(1L);
            var clock = (java.util.concurrent.ScheduledExecutorService) privateField(controller, "liveClock").get(controller);
            privateField(run, "timer").set(run, clock.schedule(() -> { }, 1, TimeUnit.DAYS));
            privateField(run, "due").setLong(run, Long.MIN_VALUE);
            var recent = (Map<String, Object>) privateField(run, "recent").get(run);
            var queue = (Deque<Object>) privateField(run, "queue").get(run);
            Class<?> candidateType = Arrays.stream(AlbumController.class.getDeclaredClasses())
                    .filter(type -> type.getSimpleName().equals("LiveCandidate")).findFirst().orElseThrow();
            var candidateConstructor = candidateType.getDeclaredConstructor(String.class, PhotoItem.class);
            candidateConstructor.setAccessible(true);
            PhotoItem photo = new PhotoItem((String) privateField(controller, "session").get(controller), 42,
                    new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).compressedSize(peer.original.length)
                            .filename("SYNTHETIC.JPG").build());
            Files.createDirectories(root);
            Path completedFile = root.resolve("completed.jpg"); Files.write(completedFile, peer.original);
            DownloadedPhoto completed = new DownloadedPhoto(completedFile.toFile(), "completed.jpg");
            for (String identity : List.of("queued", "current", "failed", "gallery-failed", "pending")) {
                Object candidate = candidateConstructor.newInstance(identity, photo);
                if (!identity.equals("pending")) privateField(candidate, "saved").set(candidate, completed);
                if (identity.equals("queued")) queue.add(candidate);
                if (identity.equals("current")) privateField(run, "current").set(run, candidate);
                if (identity.equals("failed")) privateField(candidate, "error").set(candidate, "Synthetic original error");
                if (identity.equals("gallery-failed")) privateField(candidate, "galleryError").set(candidate, "Synthetic gallery failure");
                recent.put(identity, candidate);
            }
            for (int i = 0; i < 125; i++) {
                Object candidate = candidateConstructor.newInstance("success-" + i, photo);
                privateField(candidate, "saved").set(candidate, completed); recent.put("success-" + i, candidate);
            }
            var trim = AlbumController.class.getDeclaredMethod("trimLiveRecent", runType); trim.setAccessible(true);
            trim.invoke(controller, run);
            for (String identity : List.of("queued", "current", "failed", "gallery-failed", "pending")) {
                check(recent.containsKey(identity), "History trimming preserves outstanding " + identity + " identity");
            }
            check(recent.size() == 120 && !recent.containsKey("success-0") && !recent.containsKey("success-9")
                            && recent.containsKey("success-10") && recent.containsKey("success-124"),
                    "Only oldest completed successes are retired when mixed history exceeds its ordinary bound");
            Object publishing = candidateConstructor.newInstance("gallery-publishing", photo);
            privateField(publishing, "saved").set(publishing, completed);
            privateField(publishing, "galleryPublishing").setBoolean(publishing, true);
            recent.put("gallery-publishing", publishing); trim.invoke(controller, run);
            check(recent.size() == 120 && recent.containsKey("gallery-publishing") && !recent.containsKey("success-10"),
                    "A completed original awaiting its asynchronous gallery callback is retained ahead of successful history");

            recent.clear(); queue.clear(); privateField(run, "current").set(run, null);
            for (int i = 0; i < 256; i++) {
                Object failed = candidateConstructor.newInstance("failed-" + i, photo);
                privateField(failed, "error").set(failed, "Synthetic original error"); recent.put("failed-" + i, failed);
            }
            var references = (Map<Long, Long>) privateField(run, "references").get(run); references.put(43L, 1L);
            var inspect = (java.util.Set<Long>) privateField(run, "inspect").get(run); inspect.add(43L);
            var known = (Map<Long, String>) privateField(run, "known").get(run);
            var ready = (Map<Long, Long>) privateField(run, "metadataReady").get(run);
            var attempts = (Map<Long, Integer>) privateField(run, "metadataAttempts").get(run);
            var work = AlbumController.class.getDeclaredMethod("runLiveWork", runType); work.setAccessible(true);
            var worker = (java.util.concurrent.ExecutorService) privateField(controller, "worker").get(controller);
            worker.submit(() -> { try { work.invoke(controller, run); }
                catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); } }).get(5, TimeUnit.SECONDS);
            check(recent.size() == 256 && queue.isEmpty() && !known.containsKey(43L),
                    "The pending-identity cap rejects new admission before changing known or losing failed retries");
            check(inspect.contains(43L) && ready.get(43L) != Long.MAX_VALUE && !attempts.containsKey(43L),
                    "Identity-cap pressure keeps the unseen JPEG eligible for automatic retry");
            Object recovered = recent.get("failed-0");
            privateField(recovered, "saved").set(recovered, completed); privateField(recovered, "error").set(recovered, "");
            var inspectOne = AlbumController.class.getDeclaredMethod("inspectLiveObject", runType, long.class); inspectOne.setAccessible(true);
            worker.submit(() -> { try { inspectOne.invoke(controller, run, 43L); }
                catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); } }).get(5, TimeUnit.SECONDS);
            check(recent.size() == 256 && !recent.containsKey("failed-0") && recent.containsKey("failed-255")
                            && queue.size() == 1 && known.containsKey(43L),
                    "Completing one failed original releases exactly one slot for the deferred new JPEG");
            check(((PhotoItem) privateField(queue.peekFirst(), "photo").get(queue.peekFirst())).handle() == 43
                            && peer.count(0x1009) == 0 && peer.sockets == 2 && peer.count(0x1002) == 1 && peer.count(0x1003) == 0,
                    "History capacity recovery queues the intended photo without duplicate download or connection changes");
        }
    }

    private static void liveUiListenersCanDetachWithoutLosingTheReceiveTask(Path root) throws Exception {
        Peer peer = new Peer(); peer.pauseOriginal = true;
        try (AlbumController controller = connected(root, peer)) {
            AtomicInteger oldUi = new AtomicInteger(), newUi = new AtomicInteger(), runtime = new AtomicInteger(), lifecycle = new AtomicInteger();
            Runnable first = oldUi::incrementAndGet, replacement = newUi::incrementAndGet;
            Runnable observer = runtime::incrementAndGet;
            controller.addPhotoLiveObserver(observer); controller.setLifecycleObserver(lifecycle::incrementAndGet);
            controller.setPhotoLiveListener(first); AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
            peer.setHandles(42, 43); peer.objectAdded(43);
            check(peer.originalPaused.await(5, TimeUnit.SECONDS), "A live original is in flight while its UI detaches");
            controller.clearPhotoLiveListener(first); int oldCount = oldUi.get(), runtimeBefore = runtime.get();
            PhotoLiveState immutableBefore = controller.photoLiveState();
            controller.setPhotoLiveListener(replacement); controller.clearPhotoLiveListener(first);
            peer.releaseOriginal.countDown();
            awaitCondition(() -> controller.photoLiveState().completed == 1, "The application-owned receive task finishes with a replacement UI listener");
            check(oldUi.get() == oldCount && newUi.get() > 0 && controller.photoLiveState().enabled,
                    "A detached UI is not called again and clearing its old listener cannot remove the replacement");
            check(runtime.get() > runtimeBefore && lifecycle.get() == 0,
                    "Live progress notifies its runtime observer independently without reentering connection lifecycle notifications");
            check(immutableBefore.completed == 0 && controller.photoLiveState() != immutableBefore,
                    "Previously published live snapshots remain immutable as reception progresses");
            check(call(controller::listDownloads).size() == 1 && peer.count(0x1009, 43) == 1,
                    "Closing and recreating a UI observer neither loses nor duplicates its original receive task");
            controller.removePhotoLiveObserver(observer); int runtimeAfter = runtime.get(); controller.stopPhotoLive();
            check(runtime.get() == runtimeAfter, "Removing a live observer prevents later stop-state callbacks without removing the UI listener");
        } finally { peer.releaseOriginal.countDown(); }
    }

    private static void liveStopDisconnectAndHandoffRetireTheOldQueue(Path root) throws Exception {
        for (String action : List.of("stop", "disconnect", "handoff")) {
            Peer peer = new Peer(); peer.pauseOriginal = true;
            AlbumController controller = connected(root.resolve(action), peer);
            PtpIpClient preset = null;
            try {
                AlbumControllerHostTest.<Void>call(controller::startPhotoLive);
                peer.setHandles(42, 43, 44); peer.objectAdded(43); peer.objectAdded(44);
                check(peer.originalPaused.await(5, TimeUnit.SECONDS), "A live original is paused at a framed read boundary before lifecycle cancellation");
                Result<PtpIpClient> handoff = null;
                if (action.equals("stop")) controller.stopPhotoLive();
                else if (action.equals("disconnect")) controller.disconnect();
                else { handoff = new Result<>(); controller.transferForPictureControl(handoff); }
                peer.releaseOriginal.countDown();
                if (handoff != null) preset = handoff.value(); else call(controller::listDownloads);
                check(!controller.photoLiveState().enabled && peer.count(0x1009, 44) == 0,
                        "Stop, disconnect, or handoff prevents the next queued new-photo original from starting");
                int originalAttempts = peer.count(0x1009);
                peer.setHandles(42, 43, 44, 45); peer.objectAdded(45); Thread.sleep(100);
                check(peer.count(0x1009) == originalAttempts && peer.sockets == 2 && peer.count(0x90cd) == 0,
                        "Late events from a retired live task cannot download, reconnect, or register through the new owner");
                if (preset != null) {
                    check(preset.isOpen(), "A live transfer drains the current original before granting preset ownership");
                    controller.close(); check(preset.isOpen(), "Closing the old live controller cannot close the transferred preset connection");
                } else if (action.equals("stop")) {
                    check(controller.isConnected() && peer.openSocketCount() == 2, "Stopping reception drains safely without dropping a healthy camera connection");
                } else check(peer.openSocketCount() == 0 && !controller.hasLiveSession(), "Disconnect terminates both live transfer channels");
            } finally { peer.releaseOriginal.countDown(); controller.close(); if (preset != null) preset.close(); }
        }
    }

    private static void liveQueuedStartCompletesExactlyOnceWhenCancelled(Path root) throws Exception {
        for (String action : List.of("stop", "disconnect", "close")) {
            Peer peer = new Peer(); AlbumController controller = connected(root.resolve(action), peer);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), exited = new CountDownLatch(1);
            try {
                controller.listDownloads(new AlbumController.Callback<>() {
                    @Override public void onSuccess(List<DownloadedPhoto> result) {
                        entered.countDown();
                        try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                        finally { exited.countDown(); }
                    }
                    @Override public void onError(String message) { throw new AssertionError(message); }
                });
                check(entered.await(5, TimeUnit.SECONDS), "A synthetic UI callback holds the worker before live baseline can start");
                AtomicInteger completions = new AtomicInteger();
                Result<Void> start = new Result<>() {
                    @Override public void onSuccess(Void value) { completions.incrementAndGet(); super.onSuccess(value); }
                    @Override public void onError(String message) { completions.incrementAndGet(); super.onError(message); }
                };
                controller.startPhotoLive(start);
                if (action.equals("stop")) controller.stopPhotoLive();
                else if (action.equals("disconnect")) controller.disconnect(); else controller.close();
                release.countDown(); check(exited.await(5, TimeUnit.SECONDS), "The synthetic worker barrier retires after live-start cancellation");
                check(!start.error().isEmpty() && completions.get() == 1 && !controller.photoLiveState().enabled,
                        "A queued live start completes once with cancellation even if close discards its executor task");
                check(peer.count(0x1009) == 0 && peer.count(0x90cd) == 0 && peer.sockets == 2,
                        "Cancelled live startup never downloads an old photo, writes a preset, or opens another connection");
            } finally { release.countDown(); controller.close(); }
        }
    }

    private static void liveCompletedFilesAreDurableAndNeverCopiedFromPreviews(Path root) throws Exception {
        Peer first = new Peer(); Arrays.fill(first.cameraGuid, (byte) 7); Path saved;
        try (AlbumController controller = connected(root, first)) {
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive); first.setHandles(42, 43); first.objectAdded(43);
            awaitCondition(() -> controller.photoLiveState().completed == 1, "The first controller records its complete live original durably");
            saved = controller.photoLiveState().photos.get(0).saved.file().toPath();
            check(Arrays.equals(Files.readAllBytes(saved), first.original) && !Arrays.equals(Files.readAllBytes(saved), first.thumbnail),
                    "A persisted live photo contains original response bytes rather than cached preview bytes");
        }
        Peer replacement = new Peer(); Arrays.fill(replacement.cameraGuid, (byte) 7);
        try (AlbumController controller = connected(root, replacement)) {
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive); replacement.setHandles(42, 43); replacement.objectAdded(43);
            awaitCondition(() -> controller.photoLiveState().completed == 1, "A recreated controller recognizes the same completed photo fingerprint");
            check(replacement.count(0x1009, 43) == 0 && call(controller::listDownloads).size() == 1,
                    "Durable completion survives controller recreation and avoids retransmitting or duplicating its file");
            check(controller.photoLiveState().photos.get(0).saved.file().toPath().equals(saved), "The recovered completion references its original stable destination");
            controller.stopPhotoLive();
        }
    }

    private static void liveGalleryPublicationRetriesWithoutDownloadingAgain(Path root) throws Exception {
        Peer peer = new Peer(); AtomicInteger publishes = new AtomicInteger();
        try (AlbumController controller = connected(root, peer)) {
            controller.setPhotoLivePublisher(photo -> { if (publishes.incrementAndGet() == 1) throw new IOException("Synthetic gallery unavailable"); return "content://synthetic/gallery/43"; });
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive); peer.setHandles(42, 43); peer.objectAdded(43);
            awaitCondition(() -> !controller.photoLiveState().photos.isEmpty() && !controller.photoLiveState().photos.get(0).galleryError.isEmpty(),
                    "A gallery failure is published separately after the private original is complete");
            check(controller.photoLiveState().photos.get(0).saved != null && peer.count(0x1009, 43) == 1,
                    "A failed system-gallery publication does not discard the downloaded original");
            controller.retryPhotoLive();
            awaitCondition(() -> !controller.photoLiveState().photos.get(0).galleryUri.isEmpty(), "Explicit retry publishes the already downloaded original to the gallery");
            check(publishes.get() == 2 && peer.count(0x1009, 43) == 1 && controller.photoLiveState().photos.get(0).galleryError.isEmpty(),
                    "Gallery retry never requests another original and clears its separate publication error on success");
            controller.stopPhotoLive();
        }
    }

    private static PhotoLiveState.LivePhoto livePhoto(AlbumController controller, long handle) {
        for (PhotoLiveState.LivePhoto photo : controller.photoLiveState().photos) if (photo.photo.handle() == handle) return photo;
        return null;
    }

    private static void liveAsyncPublicationLeavesTheCameraWorkerAvailable(Path root) throws Exception {
        Peer peer = new Peer();
        var publications = new java.util.concurrent.LinkedBlockingQueue<AlbumController.Callback<String>>();
        AtomicInteger publishes = new AtomicInteger(), fallback = new AtomicInteger();
        try (AlbumController controller = connected(root, peer)) {
            controller.setPhotoLivePublisher(photo -> { fallback.incrementAndGet(); return "content://sync/unused"; });
            controller.setPhotoLiveAsyncPublisher((photo, callback) -> { publishes.incrementAndGet(); publications.add(callback); });
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive); peer.setHandles(42, 43); peer.objectAdded(43);
            AlbumController.Callback<String> first = publications.poll(10, TimeUnit.SECONDS);
            check(first != null && controller.photoLiveState().completed == 1 && livePhoto(controller, 43).saved != null,
                    "The complete private original becomes available while its gallery publication remains gated");
            check(livePhoto(controller, 43).galleryUri.isEmpty() && livePhoto(controller, 43).galleryError.isEmpty(),
                    "An unsettled asynchronous publication cannot advertise a confirmed gallery URI");
            int probes = peer.count(0x1004); AlbumControllerHostTest.<Void>call(controller::keepAlive);
            check(peer.count(0x1004) > probes && controller.isConnected(),
                    "A heartbeat drains on the same camera worker before the gallery gate is released");
            peer.setHandles(42, 43, 44); peer.objectAdded(44);
            AlbumController.Callback<String> second = publications.poll(10, TimeUnit.SECONDS);
            check(second != null && controller.photoLiveState().completed == 2 && peer.count(0x1009, 44) == 1,
                    "The next preview and original complete while the earlier gallery publication is still pending");
            controller.retryPhotoLive(); AlbumControllerHostTest.<Void>call(controller::keepAlive);
            check(publishes.get() == 2 && fallback.get() == 0 && peer.count(0x1009, 43) == 1,
                    "Explicit retry cannot dispatch a second pending publication or fall back to the blocking publisher");
            first.onSuccess("content://async/gallery/43"); first.onError("late duplicate failure");
            check(livePhoto(controller, 43).galleryUri.equals("content://async/gallery/43") && livePhoto(controller, 43).galleryError.isEmpty(),
                    "A duplicate failure cannot overwrite an already confirmed asynchronous URI");
            second.onSuccess("");
            check(!livePhoto(controller, 44).galleryError.isEmpty() && livePhoto(controller, 44).galleryUri.isEmpty(),
                    "An empty publication result is an explicit local-save failure, never fake success");
            controller.retryPhotoLive(); AlbumController.Callback<String> retry = publications.poll(10, TimeUnit.SECONDS);
            check(retry != null && publishes.get() == 3 && peer.count(0x1009, 43) == 1 && peer.count(0x1009, 44) == 1,
                    "Asynchronous gallery retry reuses the completed original without another camera download");
            retry.onSuccess("content://async/gallery/44"); second.onError("duplicate old request");
            check(livePhoto(controller, 44).galleryError.isEmpty() && livePhoto(controller, 44).galleryUri.equals("content://async/gallery/44"),
                    "A settled older request cannot clobber the successful retry");
            check(peer.count(0x1002) == 1 && peer.count(0x1003) == 0 && peer.sockets == 2 && controller.photoLiveState().enabled,
                    "Delayed publication and local retries never reopen, close or replace the healthy camera session");
            controller.stopPhotoLive();
        }
    }

    private static void liveAsyncPublisherSettlesThrowsAndCallbacksExactlyOnce(Path root) throws Exception {
        Peer peer = new Peer(); AtomicInteger publications = new AtomicInteger();
        try (AlbumController controller = connected(root, peer)) {
            controller.setPhotoLiveAsyncPublisher((photo, callback) -> {
                if (publications.incrementAndGet() == 1) throw new IllegalStateException("synthetic enqueue rejection");
                callback.onSuccess("content://async/gallery/settled");
                callback.onError("duplicate callback"); throw new IllegalStateException("throw after settlement");
            });
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive); peer.setHandles(42, 43); peer.objectAdded(43);
            awaitCondition(() -> livePhoto(controller, 43) != null && !livePhoto(controller, 43).galleryError.isEmpty(),
                    "An enqueue exception settles the pending publication as a retryable local error");
            controller.retryPhotoLive();
            awaitCondition(() -> !livePhoto(controller, 43).galleryUri.isEmpty(),
                    "A later asynchronous callback can confirm the same local original");
            check(publications.get() == 2 && livePhoto(controller, 43).galleryError.isEmpty() && peer.count(0x1009, 43) == 1,
                    "Callback plus throw settles once and cannot introduce an extra original or a false retry error");
            controller.stopPhotoLive();
        }
    }

    private static void liveAsyncCompletionCannotReplaceANewerRun(Path root) throws Exception {
        Peer peer = new Peer(); var publications = new java.util.concurrent.LinkedBlockingQueue<AlbumController.Callback<String>>();
        try (AlbumController controller = connected(root, peer)) {
            controller.setPhotoLiveAsyncPublisher((photo, callback) -> publications.add(callback));
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("旧项目", callback));
            peer.setHandles(42, 43); peer.objectAdded(43);
            AlbumController.Callback<String> old = publications.poll(10, TimeUnit.SECONDS);
            check(old != null, "The first run has a completed local original awaiting gallery settlement");
            String oldProject = controller.photoLiveState().projectId; Path original = livePhoto(controller, 43).saved.file().toPath();
            controller.stopPhotoLive();
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("新项目", callback));
            peer.setHandles(42, 43, 44); peer.objectAdded(44);
            AlbumController.Callback<String> current = publications.poll(10, TimeUnit.SECONDS);
            check(current != null && !controller.photoLiveState().projectId.equals(oldProject),
                    "A new run can receive its own original before the previous publication returns");
            AlbumControllerHostTest.<Void>call(controller::keepAlive); // Drain that receive's final state publication before comparing revisions.
            String newProject = controller.photoLiveState().projectId; long revision = controller.photoLiveState().revision;
            old.onError("old publication failed"); old.onSuccess("content://async/obsolete");
            check(controller.photoLiveState().projectId.equals(newProject) && controller.photoLiveState().revision == revision
                            && controller.photoLiveState().completed == 1 && livePhoto(controller, 43) == null
                            && livePhoto(controller, 44).galleryError.isEmpty() && livePhoto(controller, 44).galleryUri.isEmpty(),
                    "An obsolete gallery callback settles its own candidate without publishing into the newer run");
            check(Files.exists(original) && Arrays.equals(Files.readAllBytes(original), peer.original),
                    "An old publication failure never removes or mutates its completed original");
            controller.stopPhotoLive(); current.onSuccess("content://async/current");
            check(!controller.photoLiveState().enabled && livePhoto(controller, 44).galleryUri.equals("content://async/current") && controller.isConnected(),
                    "Stopping reception leaves an already completed original free to finish local publication");
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("断开前项目", callback));
            peer.setHandles(42, 43, 44, 45); peer.objectAdded(45);
            AlbumController.Callback<String> disconnect = publications.poll(10, TimeUnit.SECONDS);
            check(disconnect != null, "A third original is complete before the user disconnects the camera");
            controller.disconnect(); disconnect.onSuccess("content://async/disconnected");
            check(!controller.hasLiveSession() && !controller.photoLiveState().enabled
                            && livePhoto(controller, 45).galleryUri.equals("content://async/disconnected") && peer.count(0x1009, 45) == 1,
                    "Explicit camera disconnect does not cancel a completed file's independent publication");
        }
    }

    private static void liveIdentityChangesAndIncompleteOriginalsNeverCommit(Path root) throws Exception {
        for (boolean race : new boolean[] {false, true}) {
            Peer peer = new Peer();
            if (race) peer.changeIdentityAfterOriginal = true;
            else { byte[] broken = peer.original.clone(); broken[broken.length - 1] = 0; peer.originals.put(43L, broken); }
            try (AlbumController controller = connected(root.resolve(race ? "reused-handle" : "broken-jpeg"), peer)) {
                AlbumControllerHostTest.<Void>call(controller::startPhotoLive); peer.setHandles(42, 43); peer.objectAdded(43);
                awaitCondition(() -> !controller.photoLiveState().photos.isEmpty() && !controller.photoLiveState().photos.get(0).error.isEmpty(),
                        "An incomplete JPEG or changed post-transfer ObjectInfo is exposed as a receive failure");
                controller.stopPhotoLive();
                check(controller.photoLiveState().completed == 0 && call(controller::listDownloads).isEmpty(),
                        "Unverified original content is never committed to the completed-photo journal or downloads");
                try (var files = Files.walk(root.resolve(race ? "reused-handle" : "broken-jpeg"))) {
                    check(files.noneMatch(file -> file.getFileName().toString().endsWith(".part")), "Failed live reception removes every partial output file");
                }
            }
        }
    }

    private static void liveProtocolFailureAndEventLossStopWithoutReplayingOriginals(Path root) throws Exception {
        for (boolean eventLoss : new boolean[] {false, true}) {
            Peer peer = new Peer(); peer.pauseOriginal = eventLoss; peer.originalWrongTransaction = !eventLoss;
            try (AlbumController controller = connected(root.resolve(eventLoss ? "event-eof" : "wrong-tx"), peer)) {
                AlbumControllerHostTest.<Void>call(controller::startPhotoLive); peer.setHandles(42, 43, 44); peer.objectAdded(43); peer.objectAdded(44);
                if (eventLoss) {
                    check(peer.originalPaused.await(5, TimeUnit.SECONDS), "A live original is in flight when its sole event channel fails");
                    peer.openedSockets.get(1).input.close(); peer.releaseOriginal.countDown();
                }
                awaitCondition(() -> !controller.hasLiveSession() && !controller.photoLiveState().enabled,
                        "A protocol mismatch or event EOF terminates live reception and the current physical session");
                call(controller::listDownloads);
                check(peer.count(0x1009, 43) == 1 && peer.count(0x1009, 44) == 0 && peer.sockets == 2 && peer.openSocketCount() == 0,
                        "An unconfirmed original boundary never retries, starts the next original, or reconnects");
                check(call(controller::listDownloads).isEmpty(), "An original without its verified terminal boundary never becomes a completed file");
            } finally { peer.releaseOriginal.countDown(); }
        }
    }

    private static void liveNonJpegEventsNeverDownloadRawFiles(Path root) throws Exception {
        Peer peer = new Peer(); peer.formats.put(43L, 0xb101); peer.filenames.put(43L, "NEW.NEF");
        try (AlbumController controller = connected(root, peer)) {
            AlbumControllerHostTest.<Void>call(controller::startPhotoLive); peer.setHandles(42, 43, 44); peer.objectAdded(43); peer.objectAdded(44);
            awaitCondition(() -> controller.photoLiveState().completed == 1, "A JPEG following a RAW event still completes live reception");
            check(peer.count(0x1009, 43) == 0 && peer.count(0x100a, 43) == 0 && peer.count(0x1009, 44) == 1,
                    "Live JPEG reception skips RAW metadata without downloading a RAW original or preview");
            check(controller.isConnected() && controller.photoLiveState().enabled, "An unsupported photo format does not drop a healthy live session");
            controller.stopPhotoLive();
        }
    }

    private static void liveLedgerValidatesStableIdentityAndCompletedFileIntegrity(Path root) throws Exception {
        Files.createDirectories(root);
        PtpObjectInfo first = new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).compressedSize(32).filename("NEW.JPG").captureDate("20261017T130000").build();
        PtpObjectInfo otherStorage = new PtpObjectInfo.Builder().storageId(2).objectFormat(0x3801).compressedSize(32).filename("NEW.JPG").captureDate("20261017T130000").build();
        PtpObjectInfo reused = new PtpObjectInfo.Builder().storageId(1).objectFormat(0x3801).compressedSize(32).filename("NEW.JPG").captureDate("20261017T130001").build();
        String key = PhotoLiveLedger.identity("camera-a", 43, first);
        check(!key.equals(PhotoLiveLedger.identity("camera-b", 43, first)) && !key.equals(PhotoLiveLedger.identity("camera-a", 43, otherStorage))
                        && !key.equals(PhotoLiveLedger.identity("camera-a", 43, reused)) && !key.equals(PhotoLiveLedger.identity("camera-a", 44, first)),
                "Persistent live identity distinguishes camera, storage, reused ObjectInfo, and handle");
        PhotoLiveLedger ledger = new PhotoLiveLedger(root.toFile());
        java.io.File destination = ledger.destination(key, "NEW.JPG");
        Files.write(destination.toPath(), jpeg(32, 4));
        DownloadedPhoto recovered = ledger.completed(key, destination, "NEW.JPG", 32);
        check(recovered != null && new PhotoLiveLedger(root.toFile()).completed(key, destination, "NEW.JPG", 32) != null,
                "A complete renamed destination recovers the rename-before-journal crash gap and remains valid after reload");
        byte[] modified = Files.readAllBytes(destination.toPath()); modified[10] ^= 1; Files.write(destination.toPath(), modified);
        boolean rejected = false;
        try { new PhotoLiveLedger(root.toFile()).completed(key, destination, "NEW.JPG", 32); } catch (IOException expected) { rejected = true; }
        check(rejected && Files.exists(destination.toPath()), "A locally modified completed file is detected by content hash and never silently overwritten");
    }

    private static void awaitCondition(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        check(condition.getAsBoolean(), message);
    }

    private static AlbumController connected(Path root, Peer peer) throws Exception {
        return connected(root, peer, peer.photoHandles.length);
    }
    private static AlbumController connected(Path root, Peer peer, int expectedHandles) throws Exception {
        CameraWifiNetwork.testConnector = peer::open;
        AlbumController controller = new AlbumController(new Context(root.toFile()));
        try {
            ConnectionInfo info = call(callback -> controller.connect("camera.test", 15740, callback));
            check(info.totalHandles() == expectedHandles, "Production connect enumerates the synthetic camera");
            return controller;
        } catch (Exception | Error error) { controller.close(); throw error; }
    }
    private static PhotoItem firstPhoto(AlbumController controller) throws Exception {
        PhotoPage page = call(callback -> controller.loadPage(0, 24, callback));
        check(page.items().size() == 1 && !page.hasMore(), "Production page decodes one JPEG's metadata");
        return page.items().get(0);
    }
    private static void localDeleteReportsPartialAndSurvivesUiReplacement(Path root) throws Exception {
        Context context = new Context(root.toFile()); Path originals = root.resolve("external/Pictures/camera-album"); Files.createDirectories(originals);
        DownloadedPhoto good = new DownloadedPhoto(originals.resolve("good.JPG").toFile(), "good.JPG"); Files.write(good.file().toPath(), jpeg(32, 1));
        DownloadedPhoto bad = new DownloadedPhoto(originals.resolve("bad.JPG").toFile(), "bad.JPG"); Files.write(bad.file().toPath(), jpeg(48, 2));
        PhotoLiveProjectStore store = new PhotoLiveProjectStore(new java.io.File(context.getFilesDir(), "photo-live-projects"), originals.toFile());
        PhotoLiveProject a = store.create("A"), b = store.create("B");
        store.record(a.id, "1".repeat(64), good); store.record(a.id, "2".repeat(64), bad); store.record(b.id, "3".repeat(64), good);
        PhotoLiveGallery.failName = bad.file().getName(); PhotoLiveGallery.deleting = new CountDownLatch(1); PhotoLiveGallery.releaseDelete = new CountDownLatch(1);
        try (AlbumController controller = new AlbumController(context)) {
            AtomicInteger edges = new AtomicInteger(), oldUi = new AtomicInteger(), newUi = new AtomicInteger();
            controller.setLifecycleObserver(edges::incrementAndGet); Runnable obsolete = oldUi::incrementAndGet; controller.addPhotoLibraryObserver(obsolete);
            Result<PhotoDeleteResult> completion = new Result<>(); controller.deleteDownloadedPhotos(List.of(good, bad, good), completion);
            check(PhotoLiveGallery.deleting.await(5, TimeUnit.SECONDS) && controller.isDeletingDownloadedPhotos(), "Accepted deletion exposes its app-owned reservation before asynchronous provider work finishes");
            Result<PhotoDeleteResult> duplicate = new Result<>(); controller.deleteDownloadedPhotos(List.of(bad), duplicate);
            check(!duplicate.error().isEmpty(), "A simultaneous deletion is rejected without starting another local mutation");
            check(call(controller::listDownloads).size() == 2, "A slow local provider delete never blocks the camera/controller worker");
            Result<List<PhotoLiveProject>> catalog = new Result<>(); controller.listPhotoLiveProjects(catalog);
            check(!catalog.error().isEmpty(), "Catalog reads that may recover journals reject promptly while deletion owns the catalog");
            try { PhotoLocalTasks.use(); throw new AssertionError("exclusive gate ignored"); } catch (IOException expected) { checks++; }
            controller.removePhotoLibraryObserver(obsolete); controller.addPhotoLibraryObserver(newUi::incrementAndGet);
            PhotoLiveGallery.releaseDelete.countDown(); PhotoDeleteResult result = completion.value();
            check(result.total == 2 && result.deleted == 1 && result.failed == 1 && result.failures.get(0).photo == bad, "Canonical duplicate inputs coalesce and partial failures are reported per file");
            check(!good.file().exists() && bad.file().isFile() && !controller.isDeletingDownloadedPhotos(), "Provider failure retains its private source while a successful item is unlinked");
            check(store.get(a.id).photoCount == 1 && store.get(a.id).totalBytes == 48 && store.get(b.id).photoCount == 0, "All project references and persisted totals are removed before source unlink");
            check(oldUi.get() == 0 && newUi.get() == 1 && edges.get() == 2, "Replacement UI receives one library completion and lifecycle only receives task edges");
            PhotoLiveGallery.failName = ""; PhotoLiveGallery.deleting = null; PhotoLiveGallery.releaseDelete = null;
            PhotoDeleteResult retry = call(callback -> controller.deleteDownloadedPhotos(List.of(bad), callback));
            check(retry.deleted == 1 && retry.failed == 0 && store.get(a.id).photoCount == 0 && newUi.get() == 2, "Failed local/provider work can be retried without corrupting remaining project lists");
            Path foreign = root.resolve("outside.JPG"); Files.write(foreign, jpeg(32, 4));
            PhotoDeleteResult outside = call(callback -> controller.deleteDownloadedPhotos(List.of(new DownloadedPhoto(foreign.toFile(), "outside.JPG")), callback));
            check(outside.failed == 1 && Files.isRegularFile(foreign), "An arbitrary JPEG outside app camera-album cannot be removed");
        } finally { PhotoLiveGallery.releaseDelete = null; PhotoLiveGallery.deleting = null; PhotoLiveGallery.failName = ""; }
    }
    private static void localDeleteCannotRaceReceivingAndClearsStoppedLive(Path root) throws Exception {
        Peer peer = new Peer();
        try (AlbumController controller = connected(root, peer)) {
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("删除测试", callback));
            peer.setHandles(42, 43); peer.objectAdded(43);
            awaitCondition(() -> controller.photoLiveState().completed == 1, "Live deletion fixture receives one complete original");
            DownloadedPhoto saved = controller.photoLiveState().photos.get(0).saved; String projectId = controller.photoLiveState().projectId;
            int before = peer.operationCount(); Result<PhotoDeleteResult> active = new Result<>(); controller.deleteDownloadedPhotos(List.of(saved), active);
            check(!active.error().isEmpty() && saved.file().isFile() && peer.operationCount() == before, "Active Photo Live rejects deletion without altering its source or camera");
            controller.stopPhotoLive(); call(controller::listDownloads); before = peer.operationCount();
            PhotoDeleteResult result = call(callback -> controller.deleteDownloadedPhotos(List.of(saved), callback));
            check(result.deleted == 1 && controller.photoLiveState().photos.isEmpty() && controller.photoLiveState().completed == 0,
                    "Stopped live history drops deleted saved entries instead of rebuilding them from stale state");
            check(projectWithId(call(controller::listPhotoLiveProjects), projectId).photoCount == 0 && call(controller::listDownloads).isEmpty(), "Project and downloads stay empty after successful original removal");
            check(peer.operationCount() == before && peer.count(0x100b) == 0 && peer.count(0x90cd) == 0 && controller.isConnected(), "Local deletion never sends PTP DeleteObject/writes or reconnects/disconnects a healthy camera");
            // An in-flight explicit original owns the same task reservation.
            Path existing = root.resolve("external/Pictures/camera-album/manual.JPG"); Files.write(existing, jpeg(32, 6));
            PhotoPage remotePage = call(callback -> controller.loadPage(0, 24, callback)); PhotoItem remote = remotePage.items().get(0);
            peer.pauseOriginal = true; DownloadResult pending = new DownloadResult(); controller.download(remote, pending);
            check(peer.originalPaused.await(5, TimeUnit.SECONDS), "Explicit original pauses while holding the local task reservation");
            Result<PhotoDeleteResult> receiving = new Result<>(); controller.deleteDownloadedPhotos(List.of(new DownloadedPhoto(existing.toFile(), "manual.JPG")), receiving);
            check(!receiving.error().isEmpty() && Files.isRegularFile(existing), "An in-flight camera download cannot race a private-file deletion");
            peer.releaseOriginal.countDown(); pending.value();
        } finally { peer.releaseOriginal.countDown(); }
    }
    private static void liveReceiptsResumeOnlyTheirOriginalCameraAndDescriptor(Path root) throws Exception {
        String projectId; long created;
        Peer initial = new Peer(); Arrays.fill(initial.cameraGuid, (byte) 1);
        initial.originalBusyCode = 0x2005; initial.originalBusy.put(43L, 1);
        try (AlbumController controller = connected(root, initial)) {
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("Receipts", callback));
            projectId = controller.photoLiveState().projectId;
            initial.setHandles(42, 43); initial.objectAdded(43);
            awaitCondition(() -> livePhoto(controller, 43) != null && livePhoto(controller, 43).status.equals("error"), "A failed original remains an explicit live receipt");
            controller.stopPhotoLive(); call(controller::listDownloads);
            List<PhotoLiveReceipt> records = call(callback -> controller.listPhotoLiveReceipts(projectId, callback));
            check(records.size() == 1 && records.get(0).status.equals("error") && records.get(0).saved == null && !records.get(0).error.isEmpty(), "Receive history retains failure metadata instead of only completed originals");
            created = records.get(0).createdAt;
            PhotoItem photo = livePhoto(controller, 43).photo;
            int before = initial.operationCount();
            check(controller.stablePhotoIdentity(photo).equals(records.get(0).identity) && initial.operationCount() == before, "Stable photo identity equals the live ledger fingerprint without camera IO");
            controller.disconnect();
            check(controller.stablePhotoIdentity(photo).isEmpty(), "A retired photo session cannot produce a current stable identity");
        }
        try (AlbumController offline = new AlbumController(new Context(root.toFile()))) {
            List<PhotoLiveReceipt> records = call(callback -> offline.listPhotoLiveReceipts(projectId, callback));
            check(records.size() == 1 && records.get(0).createdAt == created && records.get(0).status.equals("error"), "Failed receipt and first-discovery time survive full controller recreation offline");
            check(!offline.photoLiveState().enabled, "Opening receipt history never automatically restarts an explicitly stopped run");
        }
        Peer other = new Peer(); Arrays.fill(other.cameraGuid, (byte) 2); other.setHandles(42, 43);
        try (AlbumController controller = connected(root, other)) {
            int before = other.operationCount(); Result<Void> resume = new Result<>(); controller.resumePhotoLive(projectId, resume);
            check(!resume.error().isEmpty() && before == other.operationCount() && controller.isConnected(), "Another camera GUID cannot resume a project or download a same-numbered handle");
        }
        Peer changed = new Peer(); Arrays.fill(changed.cameraGuid, (byte) 1); changed.setHandles(42, 43); changed.filenames.put(43L, "OTHER_PHOTO.JPG");
        try (AlbumController controller = connected(root, changed)) {
            AlbumControllerHostTest.<Void>call(callback -> controller.resumePhotoLive(projectId, callback));
            awaitCondition(() -> livePhoto(controller, 43) != null && livePhoto(controller, 43).status.equals("error"), "A reused handle with different object metadata is retained as failed");
            check(changed.count(0x1009, 43) == 0 && controller.photoLiveState().projectId.equals(projectId), "Descriptor mismatch never downloads another image under the historic handle");
            controller.stopPhotoLive();
        }
        Peer original = new Peer(); Arrays.fill(original.cameraGuid, (byte) 1); original.setHandles(42, 43);
        try (AlbumController controller = connected(root, original)) {
            AlbumControllerHostTest.<Void>call(callback -> controller.resumePhotoLive(projectId, callback));
            awaitCondition(() -> controller.photoLiveState().completed == 1, "The original camera explicitly resumes its unfinished original");
            List<PhotoLiveReceipt> records = call(callback -> controller.listPhotoLiveReceipts(projectId, callback));
            check(records.size() == 1 && records.get(0).status.equals("saved") && records.get(0).saved != null && records.get(0).createdAt == created,
                    "Successful continuation preserves project and receipt identity and the original first-discovery time");
            check(call(controller::listPhotoLiveProjects).size() == 1 && original.count(0x1009, 42) == 0 && original.count(0x1009, 43) == 1,
                    "Resume reuses the project, excludes other baseline photos and downloads the pending original once");
            controller.stopPhotoLive(); call(controller::listDownloads);
            AlbumControllerHostTest.<Void>call(callback -> controller.resumePhotoLive(projectId, callback)); call(controller::listDownloads);
            check(original.count(0x1009, 43) == 1 && call(controller::listDownloads).size() == 1, "Resuming an already completed project never duplicates its saved original");
        }
    }

    private static void liveStoppedReceiptSurvivesAndUnidentifiedCameraCannotResume(Path root) throws Exception {
        Peer peer = new Peer(); peer.pauseOriginal = true; String projectId;
        try (AlbumController controller = connected(root, peer)) {
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("Stopped", callback)); projectId = controller.photoLiveState().projectId;
            peer.setHandles(42, 43); peer.objectAdded(43);
            check(peer.originalPaused.await(5, TimeUnit.SECONDS), "A receive receipt is in flight before explicit stop");
            controller.stopPhotoLive(); peer.releaseOriginal.countDown(); call(controller::listDownloads);
            List<PhotoLiveReceipt> records = call(callback -> controller.listPhotoLiveReceipts(projectId, callback));
            check(records.size() == 1 && records.get(0).status.equals("stopped") && records.get(0).saved == null && records.get(0).filename.equals("SYNTHETIC.JPG"),
                    "Stopping a framed receive preserves its paused filename and size without committing a partial original");
        } finally { peer.releaseOriginal.countDown(); }
        Peer unidentified = new Peer(); unidentified.setHandles(42, 43);
        try (AlbumController controller = connected(root, unidentified)) {
            int before = unidentified.operationCount(); Result<Void> resume = new Result<>(); controller.resumePhotoLive(projectId, resume);
            check(!resume.error().isEmpty() && unidentified.operationCount() == before, "A zero GUID cannot authorize historic handle downloads even at the same host and model");
            List<PhotoLiveReceipt> records = call(callback -> controller.listPhotoLiveReceipts(projectId, callback));
            check(records.size() == 1 && records.get(0).status.equals("stopped") && !controller.photoLiveState().enabled,
                    "Paused receipts remain visible after recreation and an unsafe resume rejection");
        }
    }

    private static void liveResumeContinuesPastArchivedFailureWindow(Path root) throws Exception {
        Peer peer = new Peer(); Arrays.fill(peer.cameraGuid, (byte) 1); peer.originalSize = 512L * 1024 * 1024 + 1;
        long[] handles = new long[300]; for (int i = 0; i < handles.length; i++) handles[i] = 43 + i; peer.setHandles(handles);
        try (AlbumController controller = connected(root, peer)) {
            PhotoPage page = call(callback -> controller.loadPage(0, 1, callback)); PtpObjectInfo info = page.items().get(0).objectInfo();
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("Many receipts", callback)); String projectId = controller.photoLiveState().projectId;
            controller.stopPhotoLive(); call(controller::listDownloads);
            PhotoLiveReceiptStore store = new PhotoLiveReceiptStore(root.resolve("files/photo-live-receipts").toFile(), root.resolve("external/Pictures/camera-album").toFile());
            String camera = "01010101010101010101010101010101\nNikon\nSynthetic camera";
            List<PhotoLiveReceipt> records = new ArrayList<>();
            for (long handle : handles) records.add(new PhotoLiveReceipt(projectId, PhotoLiveLedger.identity(camera, handle, info), "error", new PhotoItem("", handle, info), null, "oversized", 1, 2));
            store.write(projectId, camera, true, records);
            AlbumControllerHostTest.<Void>call(callback -> controller.resumePhotoLive(projectId, callback));
            awaitCondition(() -> livePhoto(controller, 342) != null, "Resume visits unfinished records beyond the bounded 256-entry in-memory failure window");
            controller.stopPhotoLive();
            List<PhotoLiveReceipt> all = call(callback -> controller.listPhotoLiveReceipts(projectId, callback));
            check(all.size() == 300 && all.stream().allMatch(item -> item.createdAt == 1 && item.status.equals("error")), "Archiving old in-memory failures never removes durable receive receipts or their discovery times");
            check(peer.count(0x1009) == 0 && call(controller::listPhotoLiveProjects).size() == 1, "Oversized pending receipts neither download originals nor create replacement projects");
        }
    }

    private static void liveProjectSetupAndOriginalObserverAreOrderedAndIndependent(Path root) throws Exception {
        Peer peer = new Peer(); AtomicInteger setups = new AtomicInteger(), originals = new AtomicInteger();
        try (AlbumController controller = connected(root, peer)) {
            controller.setPhotoLiveOriginalObserver((project, identity, photo) -> {
                check(setups.get() == 1 && !project.isEmpty() && identity.length() == 64 && photo.file().isFile(), "Original observer runs after setup and completed source classification");
                originals.incrementAndGet(); throw new IllegalStateException("effect queue unavailable");
            });
            controller.setPhotoLivePublisher(photo -> { throw new IOException("gallery unavailable"); });
            AlbumControllerHostTest.<Void>call(callback -> controller.startPhotoLive("Configured", project -> { setups.incrementAndGet(); check(peer.count(0x1009) == 0, "Project setup precedes all original acquisition"); }, callback));
            peer.setHandles(42, 43); peer.objectAdded(43);
            awaitCondition(() -> livePhoto(controller, 43) != null && !livePhoto(controller, 43).galleryError.isEmpty(), "Original remains saved when independent effect and gallery queues reject work");
            controller.retryPhotoLive(); call(controller::listDownloads);
            check(setups.get() == 1 && originals.get() == 1 && peer.count(0x1009, 43) == 1, "Gallery retries neither repeat project setup nor emit the same original observer twice");
        }
        Peer failurePeer = new Peer();
        try (AlbumController controller = connected(root.resolve("setup-failure"), failurePeer)) {
            Result<Void> start = new Result<>(); controller.startPhotoLive("Setup failure", project -> { throw new IOException("cannot save default preset"); }, start);
            check(!start.error().isEmpty() && !controller.photoLiveState().enabled && failurePeer.count(0x1009) == 0 && call(controller::listPhotoLiveProjects).size() == 1,
                    "A setup write failure preserves the empty project but prevents reception and never silently retries setup");
        }
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
        String model = "Synthetic camera", manufacturer = "Nikon";
        int missingOperation = -1;
        byte[] storageListOverride;
        int storageResponseCode = 0x2001;
        boolean storageWrongTransaction, handlesWrongTransaction;
        final List<Integer> operations = new ArrayList<>();
        final List<Long> transactions = new ArrayList<>();
        final List<Long> requestedHandles = new ArrayList<>();
        final Map<Long, Integer> metadataBusy = new HashMap<>(), originalBusy = new HashMap<>();
        final Map<Long, byte[]> originals = new HashMap<>();
        final Map<Long, String> filenames = new HashMap<>();
        final Map<Long, Integer> formats = new HashMap<>();
        final Map<Long, Long> storageIds = new HashMap<>();
        final Map<Long, long[]> handlesByStorage = new HashMap<>();
        final Map<Long, Integer> handlesResponseCodes = new HashMap<>();
        final java.util.Set<Long> unsupportedFormatStorages = new java.util.HashSet<>();
        final List<PeerSocket> openedSockets = new ArrayList<>();
        final CountDownLatch thumbnailPaused = new CountDownLatch(1), releaseThumbnail = new CountDownLatch(1), thumbnailDrained = new CountDownLatch(1);
        final CountDownLatch storagePaused = new CountDownLatch(1), releaseStorage = new CountDownLatch(1), storageDrained = new CountDownLatch(1);
        final CountDownLatch channelClosed = new CountDownLatch(1);
        final CountDownLatch originalPaused = new CountDownLatch(1), releaseOriginal = new CountDownLatch(1), originalDrained = new CountDownLatch(1);
        final CountDownLatch metadataPaused = new CountDownLatch(1), releaseMetadata = new CountDownLatch(1), metadataDrained = new CountDownLatch(1);
        final CountDownLatch handlesPaused = new CountDownLatch(1), releaseHandles = new CountDownLatch(1), handlesDrained = new CountDownLatch(1);
        final CountDownLatch deviceInfoPaused = new CountDownLatch(1), releaseDeviceInfo = new CountDownLatch(1), deviceInfoDrained = new CountDownLatch(1);
        boolean pauseOriginal, changeIdentityAfterOriginal, originalWrongTransaction;
        int metadataBusyCode = 0x2009;
        int originalBusyCode = 0x2019;
        boolean pauseThumbnail, pauseMetadata, pauseHandles;
        boolean pauseStorageRead, pauseDeviceInfo;
        int sockets;
        final byte[] cameraGuid = new byte[16];
        synchronized int count(int operation) { return (int) operations.stream().filter(op -> op == operation).count(); }
        synchronized int operationCount() { return operations.size(); }
        synchronized int count(int operation, long handle) {
            int result = 0;
            for (int i = 0; i < operations.size(); i++) if (operations.get(i) == operation && requestedHandles.get(i) == handle) result++;
            return result;
        }
        synchronized int first(int operation, long handle) {
            for (int i = 0; i < operations.size(); i++) if (operations.get(i) == operation && requestedHandles.get(i) == handle) return i;
            return -1;
        }
        synchronized void setHandles(long... handles) { photoHandles = handles.clone(); }
        synchronized void objectAdded(long handle) {
            openedSockets.get(1).input.add(frame(8, concat(le16(0x4002), le32(0xffffffffL), le32(handle))));
        }
        synchronized long lastTransaction() { return transactions.get(transactions.size() - 1); }
        synchronized int openSocketCount() { return (int) openedSockets.stream().filter(socket -> !socket.closed).count(); }
        synchronized Socket open(String host, int port, int timeout) { PeerSocket socket = new PeerSocket(this, sockets++ % 2 == 1); openedSockets.add(socket); return socket; }
        synchronized void receive(PeerSocket socket, byte[] packet) throws IOException {
            long type = u32(packet, 4);
            if (socket.event) { socket.input.add(frame(4, new byte[0])); return; }
            if (type == 1) {
                socket.input.add(frame(2, concat(le32(1), cameraGuid, "Synthetic Camera\0".getBytes(StandardCharsets.UTF_16LE), le32(0x10000))));
                return;
            }
            if (type != 6) throw new IOException("Unexpected client frame " + type);
            int operation = u16(packet, 12); long transaction = u32(packet, 14);
            operations.add(operation);
            transactions.add(transaction);
            long handle = packet.length >= 22 ? u32(packet, 18) : 0;
            requestedHandles.add(handle);
            byte[] data;
            switch (operation) {
                case 0x1001: data = info(advertiseThumbnail, advertiseLargeThumbnail, model, missingOperation, manufacturer); break;
                case 0x1002: case 0x1003: socket.input.add(response(transaction, 0x2001)); return;
                case 0x1004:
                    if (storageResponseCode != 0x2001) { socket.input.add(response(transaction, storageResponseCode)); return; }
                    data = storageListOverride == null ? concat(le32(1), le32(1)) : storageListOverride; break;
                case 0x1007:
                    if (unsupportedFormatStorages.contains(handle) && u32(packet, 22) == 0x3801) {
                        socket.input.add(response(transaction, 0x2014)); return;
                    }
                    if (handlesResponseCodes.getOrDefault(handle, 0x2001) != 0x2001) {
                        socket.input.add(response(transaction, handlesResponseCodes.get(handle))); return;
                    }
                    long[] listed = handlesByStorage.getOrDefault(handle, photoHandles);
                    ByteArrayOutputStream entries = new ByteArrayOutputStream(); entries.writeBytes(le32(listed.length));
                    for (long listedHandle : listed) entries.writeBytes(le32(listedHandle));
                    data = entries.toByteArray(); break;
                case 0x1008:
                    if (metadataBusy.getOrDefault(handle, 0) > 0) {
                        metadataBusy.put(handle, metadataBusy.get(handle) - 1); socket.input.add(response(transaction, metadataBusyCode)); return;
                    }
                    data = new PtpObjectInfo.Builder().storageId(storageIds.getOrDefault(handle, 1L)).objectFormat(formats.getOrDefault(handle, 0x3801)).compressedSize(originals.containsKey(handle) ? originals.get(handle).length : originalSize)
                            .thumbFormat(0x3801).thumbCompressedSize(16).thumbPixWidth(320).thumbPixHeight(200)
                            .imagePixWidth(6000).imagePixHeight(4000).filename(filenames.getOrDefault(handle, "SYNTHETIC.JPG")).build().toBytes();
                    break;
                case 0x1009:
                    if (originalBusy.getOrDefault(handle, 0) > 0) {
                        originalBusy.put(handle, originalBusy.get(handle) - 1); socket.input.add(response(transaction, originalBusyCode)); return;
                    }
                    data = originals.getOrDefault(handle, original);
                    if (changeIdentityAfterOriginal) filenames.put(handle, "CHANGED.JPG");
                    break;
                case 0x100a:
                    if (rejectThumbnail) { socket.input.add(response(transaction, 0x2005)); return; }
                    if (declaredThumbnailSize > 0) {
                        socket.input.add(frame(9, concat(le32(transaction), le64(declaredThumbnailSize)))); return;
                    }
                    data = thumbnail; break;
                case 0x90c4:
                    if (packet.length != 22 || !Arrays.stream(photoHandles).anyMatch(value -> value == handle)) throw new IOException("Large thumbnail must request exactly one valid handle");
                    if (largeThumbnailResponseCode != 0x2001) { socket.input.add(response(transaction, largeThumbnailResponseCode)); return; }
                    if (declaredLargeThumbnailSize > 0) {
                        socket.input.add(frame(9, concat(le32(transaction), le64(declaredLargeThumbnailSize)))); return;
                    }
                    data = largeThumbnail; break;
                default: throw new IOException("Unexpected operation " + Integer.toHexString(operation));
            }
            byte[] start = frame(9, concat(le32(transaction), le64(data.length)));
            byte[] end = frame(12, concat(le32(transaction), data));
            byte[] finish = response(transaction + (operation == 0x90c4 && largeThumbnailWrongTransaction
                    || operation == 0x1004 && storageWrongTransaction || operation == 0x1007 && handlesWrongTransaction
                    || operation == 0x1009 && originalWrongTransaction ? 1 : 0), 0x2001);
            if (operation == 0x100a && pauseThumbnail) {
                socket.input.gate(36, start.length + end.length + finish.length, thumbnailPaused, releaseThumbnail, thumbnailDrained);
            }
            if (operation == 0x1004 && pauseStorageRead) {
                socket.input.gate(24, start.length + end.length + finish.length, storagePaused, releaseStorage, storageDrained);
            }
            if (operation == 0x1009 && pauseOriginal) {
                socket.input.gate(128, start.length + end.length + finish.length, originalPaused, releaseOriginal, originalDrained);
            }
            if (operation == 0x1008 && pauseMetadata) {
                socket.input.gate(36, start.length + end.length + finish.length, metadataPaused, releaseMetadata, metadataDrained);
            }
            if (operation == 0x1007 && pauseHandles) {
                socket.input.gate(24, start.length + end.length + finish.length, handlesPaused, releaseHandles, handlesDrained);
            }
            if (operation == 0x1001 && pauseDeviceInfo) {
                socket.input.gate(24, start.length + end.length + finish.length, deviceInfoPaused, releaseDeviceInfo, deviceInfoDrained);
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
        @Override public void close() { closed = true; input.close(); peer.channelClosed.countDown(); }
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
    private static byte[] info(boolean thumbnail, boolean largeThumbnail, String model, int missingOperation, String manufacturer) {
        List<Integer> operations = new ArrayList<>(List.of(0x1001, 0x1002, 0x1003, 0x1004, 0x1007, 0x1008, 0x1009));
        if (thumbnail) operations.add(0x100a);
        if (largeThumbnail) operations.add(0x90c4);
        operations.remove(Integer.valueOf(missingOperation));
        ByteArrayOutputStream ops = new ByteArrayOutputStream(); ops.writeBytes(le32(operations.size()));
        for (int op : operations) ops.writeBytes(le16(op));
        return concat(le16(100), le32(10), le16(100), string("Test"), le16(0), ops.toByteArray(),
                le32(1), le16(0x4002), le32(0), le32(0), le32(0), string(manufacturer), string(model), string("1"), string(""));
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

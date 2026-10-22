package cc.huevia.usb;

import cc.huevia.ncp.NcpCompatibilityFixtures;
import cc.huevia.album.PtpIpClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real wireless wrapper and PTP/IP framing against synthetic sockets, NCP bytes and camera slots. */
public final class UsbWirelessPictureControlHostTest {
    private static final byte[] GUID = new byte[16];
    private static final String HOST = "camera.test";
    private static final int PORT = 15740;
    private static final int TARGET = 207;
    private static int checks;

    public static void main(String[] args) throws Exception {
        snapshotUsesTheSameReadPlanAndClosesItsSession();
        snapshotFailureKeepsReceivedEvidenceAndKnownProtocolBoundaries();
        confirmationRetainsTheOwnedCameraSessionUntilAValidatedImport();
        confirmationRechecksChangedSlotsAndCapabilitiesBeforeAnyWrite();
        cancelledAndDisconnectedConfirmationNeverReconnectsOrRegisters();
        confirmationHeartbeatIsReadOnlyAndAnInvalidReplyInvalidatesTheSession();
        explicitConfirmationReleaseClosesOnlyItsOwnedSession();
        confirmationReleaseFailureKeepsCleanupEvidenceAndCannotBeReused();
        retainedImportNeverRetriesAnUnconfirmedWriteOrReadback();
        failedConfirmationPreparationDoesNotLeaveAReusableSession();
        invalidConfirmedInputsReleaseTheOwnedSessionWithoutRegistration();
        confirmationHeartbeatCannotInterleaveWithTheRegistration();
        albumHandoffAndSettingsRefreshPreserveTheSameSessionUntilImport();
        readOnlyPreparationDoesNotRequireTheRegistrationOperation();
        handoffFailuresAndCancellationReleaseOnlyClaimedOwnership();
        failedSettingsRefreshCannotLeaveAnImportableSession();
        handoffEndpointMismatchDoesNotConsumeOrCloseTheCallerOwner();
        successfulImportKeepsConnectionForRefreshAndAnotherExplicitImport();
        keepingConnectionNeverRetainsOrRetriesFailedRegistration();
        keepingConnectionStillRejectsOccupiedAndRacedTargets();
        idleEventFailureRemainsReportableUntilExplicitRelease();
        cancellationAfterVerifiedReadbackDoesNotRetainConnection();
        matchingDeviceBusyKeepsTheSessionForTheNextReadOnlyHeartbeat();
        wrongTransactionBusyCannotPreserveThePreparedSession();
        overwriteConfirmationIsBoundToAnImmutableOccupiedSnapshot();
        explicitOverwriteKeepsConnectionForASecondFreshConfirmation();
        explicitOverwriteRejectsChangedOrUnconfirmedTargets();
        uncertainOrCancelledOverwriteNeverRetainsOrReplays();
        anOldOverwriteConfirmationCannotAuthorizeASecondRegistration();
        cancellationAfterTheFinalOverwriteCheckNeverSendsAWrite();
        matchingGeneralErrorKeepsTheNextReadOnlyHeartbeatUsable();
        unconfirmedAndNonRecoverableHeartbeatErrorsAreTerminal();
        firstPostImportGeneralErrorDoesNotCloseOrBlockTheNextExplicitImport();
        generalErrorDuringAWriteCannotUseHeartbeatRecovery();
        preparedPresetCanBrowseAndReturnForAFreshExplicitOverwrite();
        returningFromAlbumCannotReuseAnOldOverwriteConfirmation();
        cancelledOrFailedPreparedTransferCannotEscapeAnotherOwner();
        ncpImportRecomputesPrivateSourceAndChecksTheEmptySlotTwice();
        invalidInputsNeverOpenSockets();
        unsupportedCamerasAndOccupiedSlotsNeverWrite();
        completeRejectionKeepsPrimaryAndCleanupEvidence();
        unconfirmedWriteAndReadbackStopAllCommands();
        changedReadbackAndCleanupFailuresCannotComplete();
        cancelledReadbackRequiresSessionCleanupConfirmation();
        concurrentCloseUnblocksTheSocketAndRejectsAnotherFlow();
        closedInstanceCannotConnect();
        mainThreadCannotStartEitherFlow();
        System.out.println("PASS: " + checks + " wireless Picture Control wrapper checks");
    }

    private static void snapshotFailureKeepsReceivedEvidenceAndKnownProtocolBoundaries() throws Exception {
        for (String fault : List.of("malformed_scan", "malformed_capability", "lost_capability", "wrong_scan_transaction")) {
            Camera camera = new Camera();
            camera.fault = fault;
            try {
                new WirelessPictureControl(camera::connect).probe(HOST, PORT, GUID);
                throw new AssertionError("Expected a failed wireless snapshot");
            } catch (UsbPictureControlProbe.ProbeException error) {
                UsbPictureControlProbe.Result result = error.partialResult;
                check(!result.completed && result.transport.equals("wifi") && result.wirelessHost.equals(HOST),
                        "Failed snapshots retain the wireless report and its partial state");
                if (fault.equals("malformed_scan")) {
                    equal(4, result.items.size());
                    UsbPictureControlProbe.ItemRecord received = result.items.get(3);
                    equal(203, received.itemCode);
                    equal(37, received.rawBytes().length);
                    check(!received.parsed && result.sessionClosed && !result.protocolPhaseUnconfirmed,
                            "A fully received malformed dataset is retained and permits owned cleanup");
                } else if (fault.equals("malformed_capability")) {
                    check(result.capabilityRead && !result.capabilityParsed && result.sessionClosed,
                            "Malformed capability bytes survive a semantic parsing failure");
                    equal(29, result.standardCapabilityRawBytes.length);
                } else {
                    check(result.protocolPhaseUnconfirmed && !result.sessionClosed,
                            "Truncation or mismatched transaction cannot be followed by CloseSession");
                    equal(0L, camera.count(0x1003));
                }
                equal(0, camera.uploads.size());
            }
            camera.assertClosed();
        }
    }

    private static void snapshotUsesTheSameReadPlanAndClosesItsSession() throws Exception {
        Camera camera = new Camera();
        UsbPictureControlProbe.Result result = new WirelessPictureControl(camera::connect).probe(HOST, PORT, GUID);
        check(result.completed && result.sessionOpened && result.sessionClosed && result.capabilityParsed,
                "Read success requires the bounded snapshot and confirmed session cleanup");
        equal("wifi", result.transport);
        equal(HOST, result.wirelessHost);
        equal(PORT, result.wirelessPort);
        equal(10, result.items.size());
        equal(0, camera.uploads.size());
        equal(15, camera.commands.size());
        equal(camera.commands.size(), result.commandsSent.size());
        command(camera.commands.get(0), 0x1001, 0);
        command(camera.commands.get(1), 0x1002, 0, 1);
        command(camera.commands.get(2), 0x90ca, 1);
        command(camera.commands.get(3), 0x90cf, 2, 1);
        command(camera.commands.get(4), 0x90cc, 3, 1, 0);
        for (int i = 0; i < 9; i++) command(camera.commands.get(5 + i), 0x90cc, 4 + i, 201 + i, 0);
        command(camera.commands.get(14), 0x1003, 13);
        for (Command command : camera.commands) check(command.operation != 0x90cd, "A wireless scan cannot upload data");
        camera.assertClosed();
    }

    private static void ncpImportRecomputesPrivateSourceAndChecksTheEmptySlotTwice() throws Exception {
        Camera camera = new Camera();
        byte[] original = NcpCompatibilityFixtures.ncp();
        byte[] preserved = original.clone();
        byte[] expected = NcpCompatibilityFixtures.expectedData(4, -4);
        // The caller mutates its source as connection starts. The private conversion must remain stable.
        camera.onFirstConnect = () -> Arrays.fill(original, (byte) 0);
        UsbPictureControlImport.Result result = new WirelessPictureControl(camera::connect).importNcpPreset(
                HOST, PORT, GUID, original, 4, -4, TARGET);
        check(result.completed && result.ncpMode && result.ncpRegistrationVerified && result.readbackVerified
                        && result.sessionClosed && result.initialTargetEmpty && result.recheckTargetEmpty,
                "One write is completed only after conversion, two empty observations and exact readback");
        check(result.adaptationPrepared && !result.sourceVerified && !result.ncpImportVerified
                        && !result.conversionEquivalentToNative && !result.photoEffectVerified,
                "Wireless compatibility registration retains its precise verification scope");
        equal("ncp_compatibility_import", result.modePurpose);
        equal("ncp_compatibility_adaptation", result.type);
        equal("wifi", result.transport);
        equal(HOST, result.wirelessHost);
        equal(PORT, result.wirelessPort);
        equal(UsbPictureControlProbe.sha256(preserved), result.sourceNcpSha256);
        equal(preserved.length, result.sourceNcpSize);
        equal(expected, result.convertedRawBytes);
        equal(expected, result.readback.rawBytes());
        equal(expected, camera.slots.get(TARGET));
        equal(1, camera.uploads.size());
        equal(expected, camera.uploads.get(0));
        equal(3, camera.reads.get(TARGET));
        equal(9, result.initialSlots.size());
        equal(15, camera.commands.size());
        command(camera.commands.get(11), 0x90cc, 10, TARGET, 0);
        command(camera.commands.get(12), 0x90cd, 11, TARGET, 0);
        command(camera.commands.get(13), 0x90cc, 12, TARGET, 0);
        command(camera.commands.get(14), 0x1003, 13);
        assertOnlyGuardedCommands(camera);
        camera.assertClosed();
    }

    private static void confirmationRetainsTheOwnedCameraSessionUntilAValidatedImport() throws Exception {
        Camera camera = new Camera();
        // These cameras drop the camera-hosted Wi-Fi after CloseSession. Preparing a
        // confirmation screen must therefore never rely on a second connection.
        camera.refuseAfterCloseSession = true;
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        UsbPictureControlProbe.Result snapshot = service.prepareImport(HOST, PORT, GUID);
        check(snapshot.completed && snapshot.sessionOpened && !snapshot.sessionClosed && service.isPrepared(),
                "Confirmation keeps a complete read snapshot and its owned PTP session alive");
        check(snapshot.sessionRetained, "The report explicitly distinguishes a retained session from confirmed cleanup");
        equal(10, snapshot.items.size());
        equal(0L, camera.count(0x1003));
        equal(0, camera.uploads.size());
        camera.assertOpen();
        try {
            service.prepareImport("another.camera", PORT, GUID);
            throw new AssertionError("Expected an existing confirmation to reject endpoint replacement");
        } catch (IllegalStateException expected) { checks++; }
        try {
            service.importNcpPreset(HOST, PORT, GUID, NcpCompatibilityFixtures.ncp(), 4, -4, TARGET);
            throw new AssertionError("Expected an existing confirmation to reject a separate import flow");
        } catch (IllegalStateException expected) { checks++; }
        equal(2, camera.connectionAttempts);

        int beforeHeartbeat = camera.commands.size();
        service.keepPreparedAlive();
        equal(beforeHeartbeat + 1, camera.commands.size());
        Command heartbeat = camera.commands.get(beforeHeartbeat);
        equal(0x90cc, heartbeat.operation);
        equal(new long[] {1, 0}, heartbeat.parameters);
        equal(0L, camera.count(0x1003));
        camera.assertOpen();

        int beforeImport = camera.commands.size();
        byte[] expected = NcpCompatibilityFixtures.expectedData(4, -4);
        UsbPictureControlImport.Result result = service.importPreparedNcpPreset(
                NcpCompatibilityFixtures.ncp(), 4, -4, TARGET);
        check(result.completed && result.ncpRegistrationVerified && result.readbackVerified && result.sessionClosed,
                "A retained session completes only after guarded registration, exact readback and cleanup");
        check(!service.isPrepared(), "A consumed confirmation cannot remain reusable");
        check(!snapshot.sessionRetained, "Consuming confirmation clears the live-session report flag");
        equal("wifi", result.transport);
        equal(HOST, result.wirelessHost);
        equal(PORT, result.wirelessPort);
        equal(2, camera.connectionAttempts);
        equal(1L, camera.count(0x1002));
        equal(1L, camera.count(0x1003));
        equal(1L, camera.count(0x90cd));
        equal(1, camera.uploads.size());
        equal(expected, camera.uploads.get(0));
        equal(expected, camera.slots.get(TARGET));
        equal(9, result.initialSlots.size());

        List<Command> imported = camera.commands.subList(beforeImport, camera.commands.size());
        equal(0x1001, imported.get(0).operation);
        check(imported.stream().anyMatch(command -> command.operation == 0x90cf),
                "Confirmation must fetch current Picture Control capability before registering");
        int writeIndex = -1;
        for (int i = 0; i < imported.size(); i++) if (imported.get(i).operation == 0x90cd) writeIndex = i;
        check(writeIndex >= 10, "The fresh read plan must finish before the single requested write");
        Command finalObservation = imported.get(writeIndex - 1);
        equal(0x90cc, finalObservation.operation);
        equal(new long[] {TARGET, 0}, finalObservation.parameters);
        for (int item = 201; item <= 209; item++) {
            final int observedItem = item;
            long observations = imported.subList(0, writeIndex).stream()
                    .filter(command -> command.operation == 0x90cc && command.parameters[0] == observedItem).count();
            equal(item == TARGET ? 2L : 1L, observations);
        }
        equal(0x90cc, imported.get(writeIndex + 1).operation);
        equal(new long[] {TARGET, 0}, imported.get(writeIndex + 1).parameters);
        assertMonotonicOwnedTransactions(camera);
        camera.assertClosed();

        int commandsAfterCompletion = camera.commands.size();
        expectPreparedImportFailure(service);
        equal(commandsAfterCompletion, camera.commands.size());
        equal(2, camera.connectionAttempts);
        equal(1L, camera.count(0x90cd));
    }

    private static void confirmationRechecksChangedSlotsAndCapabilitiesBeforeAnyWrite() throws Exception {
        for (String change : List.of("occupied_during_confirmation", "occupied_at_last_recheck",
                "write_operation_removed", "malformed_capability", "model")) {
            Camera camera = new Camera();
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            service.prepareImport(HOST, PORT, GUID);
            if (change.equals("occupied_during_confirmation")) {
                camera.slots.put(TARGET, NcpCompatibilityFixtures.expectedData(0, 0));
            } else if (change.equals("occupied_at_last_recheck")) {
                camera.race = true;
                camera.raceAtTargetRead = 3;
            } else if (change.equals("write_operation_removed")) camera.missingOperation = 0x90cd;
            else if (change.equals("model")) camera.model = "Another camera";
            else camera.fault = change;
            UsbPictureControlImport.Result result = expectPreparedImportFailure(service).partialResult;
            check(!result.completed && !result.writeAttempted, "Changes after confirmation cannot register a preset");
            equal(0L, camera.count(0x90cd));
            equal(0, camera.uploads.size());
            equal(2, camera.connectionAttempts);
            if (change.equals("occupied_at_last_recheck")) {
                check(result.initialTargetEmpty && !result.recheckTargetEmpty,
                        "The final fresh observation catches a slot occupied after the fresh nine-slot scan");
            }
            camera.assertClosed();
        }
    }

    private static void cancelledAndDisconnectedConfirmationNeverReconnectsOrRegisters() throws Exception {
        for (boolean disconnected : new boolean[] {false, true}) {
            Camera camera = new Camera();
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            service.prepareImport(HOST, PORT, GUID);
            int before = camera.commands.size();
            if (disconnected) camera.disconnectPeer();
            else service.close();
            UsbPictureControlImport.Result result = expectPreparedImportFailure(service).partialResult;
            check(!result.writeAttempted && !result.completed,
                    "A cancelled or disconnected confirmation cannot silently reconnect and write");
            check(!service.isPrepared(), "A cancelled or disconnected confirmation loses its prepared ownership");
            equal(2, camera.connectionAttempts);
            equal(0L, camera.count(0x90cd));
            equal(0L, camera.count(0x1003));
            equal(before, camera.commands.size());
            camera.assertClosed();
        }
    }

    private static void confirmationHeartbeatIsReadOnlyAndAnInvalidReplyInvalidatesTheSession() throws Exception {
        for (String fault : List.of("malformed_heartbeat", "lost_heartbeat", "wrong_heartbeat_transaction")) {
            Camera camera = new Camera();
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            service.prepareImport(HOST, PORT, GUID);
            camera.fault = fault;
            int before = camera.commands.size();
            try {
                service.keepPreparedAlive();
                throw new AssertionError("Expected a failed confirmation heartbeat");
            } catch (IOException expected) { checks++; }
            check(!service.isPrepared(), "Invalid heartbeat evidence invalidates retained confirmation state");
            List<Command> after = camera.commands.subList(before, camera.commands.size());
            check(after.stream().allMatch(command -> command.operation == 0x90cc || command.operation == 0x1003),
                    "Confirmation heartbeat can only read Standard or clean up its owned confirmed session");
            equal(2, camera.connectionAttempts);
            equal(0L, camera.count(0x90cd));
            int afterHeartbeat = camera.commands.size();
            expectPreparedImportFailure(service);
            equal(afterHeartbeat, camera.commands.size());
            equal(2, camera.connectionAttempts);
            camera.assertClosed();
        }
    }

    private static void explicitConfirmationReleaseClosesOnlyItsOwnedSession() throws Exception {
        Camera camera = new Camera();
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        UsbPictureControlProbe.Result snapshot = service.prepareImport(HOST, PORT, GUID);
        UsbPictureControlProbe.Result released = service.releasePrepared();
        check(released == snapshot && released.sessionClosed && !service.isPrepared(),
                "Leaving confirmation closes the same owned session and returns its cleanup evidence");
        check(!released.sessionRetained, "A released snapshot cannot claim an active retained session");
        equal(1L, camera.count(0x1003));
        equal(0L, camera.count(0x90cd));
        equal(0, camera.uploads.size());
        assertMonotonicOwnedTransactions(camera);
        camera.assertClosed();
        int before = camera.commands.size();
        service.close();
        equal(before, camera.commands.size());
        equal(2, camera.connectionAttempts);
    }

    private static void retainedImportNeverRetriesAnUnconfirmedWriteOrReadback() throws Exception {
        for (String fault : List.of("lost_write", "wrong_write_transaction", "lost_readback", "wrong_readback_transaction")) {
            Camera camera = new Camera();
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            service.prepareImport(HOST, PORT, GUID);
            camera.fault = fault;
            UsbPictureControlImport.Result result = expectPreparedImportFailure(service).partialResult;
            check(result.writeAttempted && !result.completed && result.protocolPhaseUnconfirmed && !result.sessionClosed,
                    "An uncertain retained registration preserves its possible effect and abandons the protocol phase");
            equal(1L, camera.count(0x90cd));
            equal(0L, camera.count(0x1003));
            equal(1, camera.uploads.size());
            equal(fault.contains("readback") ? 0x90cc : 0x90cd,
                    camera.commands.get(camera.commands.size() - 1).operation);
            equal(2, camera.connectionAttempts);
            check(!service.isPrepared(), "A failed retained write cannot be retried as the same confirmation");
            int beforeRetry = camera.commands.size();
            expectPreparedImportFailure(service);
            equal(beforeRetry, camera.commands.size());
            equal(1L, camera.count(0x90cd));
            equal(2, camera.connectionAttempts);
            camera.assertClosed();
        }
    }

    private static void confirmationReleaseFailureKeepsCleanupEvidenceAndCannotBeReused() throws Exception {
        Camera camera = new Camera();
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        service.prepareImport(HOST, PORT, GUID);
        camera.cleanupResponse = 0x2002;
        try {
            service.releasePrepared();
            throw new AssertionError("Expected an unconfirmed confirmation-session cleanup");
        } catch (UsbPictureControlProbe.ProbeException error) {
            check(!error.partialResult.sessionClosed && !error.partialResult.cleanupError.isEmpty(),
                    "Leaving confirmation must retain an unacknowledged CloseSession response");
            equal(0x2002, error.partialResult.cleanupResponseCode);
        }
        check(!service.isPrepared(), "A released session remains unavailable even when camera cleanup is rejected");
        equal(1L, camera.count(0x1003));
        equal(0L, camera.count(0x90cd));
        equal(2, camera.connectionAttempts);
        int before = camera.commands.size();
        expectPreparedImportFailure(service);
        equal(before, camera.commands.size());
        camera.assertClosed();
    }

    private static void failedConfirmationPreparationDoesNotLeaveAReusableSession() throws Exception {
        for (String fault : List.of("malformed_scan", "malformed_capability", "lost_capability", "wrong_scan_transaction")) {
            Camera camera = new Camera();
            camera.fault = fault;
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            try {
                service.prepareImport(HOST, PORT, GUID);
                throw new AssertionError("Expected a failed confirmation preparation");
            } catch (UsbPictureControlProbe.ProbeException error) {
                check(!error.partialResult.completed && !service.isPrepared(),
                        "Partial confirmation evidence never becomes a reusable live import session");
            }
            equal(0L, camera.count(0x90cd));
            equal(0, camera.uploads.size());
            int before = camera.commands.size();
            expectPreparedImportFailure(service);
            equal(before, camera.commands.size());
            equal(2, camera.connectionAttempts);
            camera.assertClosed();
        }
    }

    private static void confirmationHeartbeatCannotInterleaveWithTheRegistration() throws Exception {
        Camera camera = new Camera();
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        service.prepareImport(HOST, PORT, GUID);
        camera.fault = "blocked_write";
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { service.importPreparedNcpPreset(NcpCompatibilityFixtures.ncp(), 4, -4, TARGET); }
            catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        try {
            check(camera.blocked.await(5, TimeUnit.SECONDS), "The retained write reaches its blocked response");
            int commandsAtWrite = camera.commands.size();
            try {
                service.keepPreparedAlive();
                throw new AssertionError("Expected heartbeat exclusion during registration");
            } catch (IllegalStateException expected) { checks++; }
            try {
                service.releasePrepared();
                throw new AssertionError("Expected cleanup exclusion during registration");
            } catch (IllegalStateException expected) { checks++; }
            try {
                service.transferPreparedToAlbum();
                throw new AssertionError("Expected album ownership-transfer exclusion during registration");
            } catch (IllegalStateException expected) { checks++; }
            equal(commandsAtWrite, camera.commands.size());
        } finally {
            service.close();
            worker.join(5000);
        }
        check(!worker.isAlive(), "Closing a retained session unblocks a pending registration");
        check(failure.get() instanceof UsbPictureControlImport.ImportException,
                "Cancellation during retained registration keeps its import report");
        UsbPictureControlImport.Result result = ((UsbPictureControlImport.ImportException) failure.get()).partialResult;
        check(result.writeAttempted && result.protocolPhaseUnconfirmed && !result.completed,
                "Cancelling a retained pending write preserves its possible camera effect");
        equal(1L, camera.count(0x90cd));
        equal(0L, camera.count(0x1003));
        equal(2, camera.connectionAttempts);
        camera.assertClosed();
    }

    private static void invalidConfirmedInputsReleaseTheOwnedSessionWithoutRegistration() throws Exception {
        for (String fault : List.of("null", "converted", "malformed", "target", "middle", "clarity")) {
            Camera camera = new Camera();
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            UsbPictureControlProbe.Result snapshot = service.prepareImport(HOST, PORT, GUID);
            byte[] input = NcpCompatibilityFixtures.ncp();
            int target = TARGET, middle = 4, clarity = -4;
            if (fault.equals("null")) input = null;
            if (fault.equals("converted")) input = NcpCompatibilityFixtures.expectedData(4, -4);
            if (fault.equals("malformed")) input[0] = 0;
            if (fault.equals("target")) target = 210;
            if (fault.equals("middle")) middle = 100;
            if (fault.equals("clarity")) clarity = 100;
            try {
                service.importPreparedNcpPreset(input, middle, clarity, target);
                throw new AssertionError("Expected invalid confirmed input to stop registration");
            } catch (UsbPictureControlImport.ImportException error) {
                check(!error.partialResult.writeAttempted && !error.partialResult.completed
                                && error.partialResult.sessionClosed,
                        "Invalid confirmed input stops registration and confirms owned-session cleanup");
            }
            check(!service.isPrepared() && !snapshot.sessionRetained,
                    "Invalid confirmation input releases the retained ownership");
            equal(0L, camera.count(0x90cd));
            equal(1L, camera.count(0x1003));
            equal(0, camera.uploads.size());
            equal(2, camera.connectionAttempts);
            camera.assertClosed();
        }
    }

    private static void albumHandoffAndSettingsRefreshPreserveTheSameSessionUntilImport() throws Exception {
        Camera camera = new Camera();
        camera.refuseAfterCloseSession = true;
        PtpIpClient album = new PtpIpClient(camera::connect);
        album.connect(HOST, PORT, GUID, "Synthetic album");
        equal(le32(0), album.getData(0x1004, 64));
        equal(le32(0), album.getData(0x1004, 64));
        PtpIpClient handoff = album.transferToPictureControl();
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        UsbPictureControlProbe.Result settings = service.prepareFromHandoff(handoff, HOST, PORT, false);
        check(settings.completed && settings.sessionRetained && settings.sessionOpened && !settings.sessionClosed,
                "Settings adopts a complete read-only snapshot on the transferred album session");
        equal(10, settings.items.size());
        equal(2, camera.connectionAttempts);
        equal(1L, camera.count(0x1002));
        equal(0L, camera.count(0x1003));
        equal(0L, camera.count(0x90cd));
        album.close();
        check(service.isPrepared(), "Delayed cleanup of the retired album cannot invalidate settings ownership");

        int beforeRefresh = camera.commands.size();
        UsbPictureControlProbe.Result refreshed = service.refreshPreparedSnapshot(false);
        check(refreshed.completed && refreshed.sessionRetained && !refreshed.sessionClosed,
                "Refreshing settings retains the same live owned session");
        assertFreshReadSnapshot(camera.commands.subList(beforeRefresh, camera.commands.size()));
        int beforeConfirm = camera.commands.size();
        UsbPictureControlProbe.Result confirmation = service.refreshPreparedSnapshot(true);
        check(confirmation.completed && confirmation.sessionRetained && service.isPrepared(),
                "Moving from settings to import confirmation refreshes the held session without reconnecting");
        assertFreshReadSnapshot(camera.commands.subList(beforeConfirm, camera.commands.size()));
        service.keepPreparedAlive();
        camera.assertOpen();
        equal(2, camera.connectionAttempts);
        equal(1L, camera.count(0x1002));
        equal(0L, camera.count(0x1003));
        equal(0L, camera.count(0x90cd));

        UsbPictureControlImport.Result result = service.importPreparedNcpPreset(
                NcpCompatibilityFixtures.ncp(), 4, -4, TARGET);
        check(result.completed && result.readbackVerified && result.ncpRegistrationVerified && result.sessionClosed,
                "The adopted album session still requires fresh guards and exact readback before import success");
        equal(NcpCompatibilityFixtures.expectedData(4, -4), camera.slots.get(TARGET));
        equal(9, result.initialSlots.size());
        check(result.initialTargetEmpty && result.recheckTargetEmpty, "Both empty-target checks survive ownership handoff");
        equal(2, camera.connectionAttempts);
        equal(1L, camera.count(0x1002));
        equal(1L, camera.count(0x90cd));
        equal(1L, camera.count(0x1003));
        equal(1, camera.uploads.size());
        assertMonotonicOwnedTransactions(camera);
        camera.assertClosed();
    }

    private static void readOnlyPreparationDoesNotRequireTheRegistrationOperation() throws Exception {
        for (boolean transfer : new boolean[] {false, true}) {
            Camera camera = new Camera();
            camera.missingOperation = 0x90cd;
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            UsbPictureControlProbe.Result snapshot;
            if (transfer) {
                PtpIpClient album = new PtpIpClient(camera::connect);
                album.connect(HOST, PORT, GUID, "Synthetic album");
                snapshot = service.prepareFromHandoff(album.transferToPictureControl(), HOST, PORT, false);
            } else snapshot = service.prepareReadOnly(HOST, PORT, GUID);
            check(snapshot.completed && snapshot.sessionRetained && service.isPrepared(),
                    "A camera can keep a read-only settings connection without declaring registration support");
            service.keepPreparedAlive();
            UsbPictureControlProbe.Result refreshed = service.refreshPreparedSnapshot(false);
            check(refreshed.completed && service.isPrepared(), "Read-only refresh does not require a write operation");
            equal(0L, camera.count(0x90cd));
            equal(0L, camera.count(0x1003));
            equal(2, camera.connectionAttempts);
            UsbPictureControlImport.Result failure = expectPreparedImportFailure(service).partialResult;
            check(!failure.completed && !failure.writeAttempted && failure.sessionClosed,
                    "A later explicit import still refuses a camera that does not declare registration support");
            equal(0L, camera.count(0x90cd));
            equal(0, camera.uploads.size());
            equal(1L, camera.count(0x1003));
            equal(1L, camera.count(0x1002));
            equal(2, camera.connectionAttempts);
            assertMonotonicOwnedTransactions(camera);
            camera.assertClosed();
        }
    }

    private static void handoffFailuresAndCancellationReleaseOnlyClaimedOwnership() throws Exception {
        for (String fault : List.of("malformed_capability", "malformed_scan", "lost_capability")) {
            Camera camera = new Camera();
            PtpIpClient album = new PtpIpClient(camera::connect);
            album.connect(HOST, PORT, GUID, "Synthetic album");
            PtpIpClient handoff = album.transferToPictureControl();
            camera.fault = fault;
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            try {
                service.prepareFromHandoff(handoff, HOST, PORT, false);
                throw new AssertionError("Expected failed adopted settings snapshot");
            } catch (UsbPictureControlProbe.ProbeException error) {
                check(!error.partialResult.completed && !service.isPrepared(),
                        "An invalid adopted snapshot does not retain reusable ownership");
            }
            equal(2, camera.connectionAttempts);
            equal(1L, camera.count(0x1002));
            equal(0L, camera.count(0x90cd));
            check(handoff.isClosed(), "A failed snapshot releases the ownership it successfully claimed");
            camera.assertClosed();
        }

        Camera camera = new Camera();
        PtpIpClient album = new PtpIpClient(camera::connect);
        album.connect(HOST, PORT, GUID, "Synthetic album");
        PtpIpClient handoff = album.transferToPictureControl();
        WirelessPictureControl cancelled = new WirelessPictureControl(camera::connect);
        cancelled.close();
        try {
            cancelled.prepareFromHandoff(handoff, HOST, PORT, false);
            throw new AssertionError("Expected cancelled settings preparation");
        } catch (UsbPictureControlProbe.ProbeException expected) { checks++; }
        // Adoption never claimed a cancelled request, so the caller still has
        // ownership and must be able to close it without creating any connection.
        check(handoff.isOpen(), "A cancelled service does not close an unclaimed transferred owner");
        equal(2, camera.connectionAttempts);
        equal(0L, camera.count(0x90cd));
        handoff.close();
        camera.assertClosed();

        Camera claimedCamera = new Camera();
        PtpIpClient claimedAlbum = new PtpIpClient(claimedCamera::connect);
        claimedAlbum.connect(HOST, PORT, GUID, "Synthetic album");
        PtpIpClient claimed = claimedAlbum.transferToPictureControl();
        long inheritedTransaction = claimed.claimTransferredSession();
        WirelessPictureControl other = new WirelessPictureControl(claimedCamera::connect);
        try {
            other.prepareFromHandoff(claimed, HOST, PORT, false);
            throw new AssertionError("Expected a transferred session already claimed by another caller to be rejected");
        } catch (UsbPictureControlProbe.ProbeException expected) { checks++; }
        check(claimed.isOpen(), "A failed ownership claim cannot close another caller's live session");
        equal(0L, claimedCamera.count(0x1003));
        equal(2, claimedCamera.connectionAttempts);
        claimed.getPictureControlData(0x1001, inheritedTransaction);
        claimed.close();
        claimedCamera.assertClosed();
    }

    private static void failedSettingsRefreshCannotLeaveAnImportableSession() throws Exception {
        for (String fault : List.of("malformed_capability", "malformed_scan", "lost_capability", "operation")) {
            Camera camera = new Camera();
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            service.prepareReadOnly(HOST, PORT, GUID);
            if (fault.equals("operation")) camera.missingOperation = 0x90cd;
            else camera.fault = fault;
            try {
                service.refreshPreparedSnapshot(fault.equals("operation"));
                throw new AssertionError("Expected a failed settings snapshot refresh");
            } catch (UsbPictureControlProbe.ProbeException error) {
                check(!error.partialResult.completed && !service.isPrepared(),
                        "Failed refresh invalidates settings ownership before confirmation or registration");
            }
            equal(0L, camera.count(0x90cd));
            equal(0, camera.uploads.size());
            equal(2, camera.connectionAttempts);
            int before = camera.commands.size();
            expectPreparedImportFailure(service);
            equal(before, camera.commands.size());
            equal(2, camera.connectionAttempts);
            camera.assertClosed();
        }
    }

    private static void handoffEndpointMismatchDoesNotConsumeOrCloseTheCallerOwner() throws Exception {
        for (boolean wrongPort : new boolean[] {false, true}) {
            Camera camera = new Camera();
            PtpIpClient album = new PtpIpClient(camera::connect);
            album.connect(HOST, PORT, GUID, "Synthetic album");
            PtpIpClient handoff = album.transferToPictureControl();
            WirelessPictureControl mismatched = new WirelessPictureControl(camera::connect);
            int before = camera.commands.size();
            try {
                mismatched.prepareFromHandoff(handoff, wrongPort ? HOST : "other.camera", wrongPort ? PORT + 1 : PORT, false);
                throw new AssertionError("Expected mismatched camera endpoint to reject ownership adoption");
            } catch (UsbPictureControlProbe.ProbeException expected) { checks++; }
            check(handoff.isOpen(), "A mismatched endpoint is rejected before consuming the caller's handoff");
            equal(before, camera.commands.size());
            equal(2, camera.connectionAttempts);
            WirelessPictureControl correct = new WirelessPictureControl(camera::connect);
            UsbPictureControlProbe.Result snapshot = correct.prepareFromHandoff(handoff, HOST, PORT, false);
            check(snapshot.completed && correct.isPrepared(), "The unconsumed handoff can still be adopted for its actual endpoint");
            correct.releasePrepared();
            equal(0L, camera.count(0x90cd));
            equal(1L, camera.count(0x1002));
            equal(1L, camera.count(0x1003));
            equal(2, camera.connectionAttempts);
            camera.assertClosed();
        }
    }

    private static void successfulImportKeepsConnectionForRefreshAndAnotherExplicitImport() throws Exception {
        Camera camera = new Camera();
        camera.refuseAfterCloseSession = true;
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        UsbPictureControlProbe.Result originalSnapshot = service.prepareImport(HOST, PORT, GUID);
        UsbPictureControlImport.Result first = service.importPreparedNcpPresetKeepingConnection(
                NcpCompatibilityFixtures.ncp(), 4, -4, TARGET);
        check(first.completed && first.ncpRegistrationVerified && first.readbackVerified
                        && first.sessionRetainedAfterImport && !first.sessionClosed,
                "Successful opt-in import verifies registration and retains its wireless connection");
        check(service.isPrepared() && service.hasPreparedSession() && !originalSnapshot.sessionRetained,
                "Successful registration hands ownership back to a fresh prepared state and retires its old snapshot");
        equal(1L, camera.count(0x90cd));
        equal(0L, camera.count(0x1003));
        equal(1L, camera.count(0x1002));
        equal(2, camera.connectionAttempts);
        camera.assertOpen();
        service.keepPreparedAlive();
        int beforeRefresh = camera.commands.size();
        UsbPictureControlProbe.Result refreshed = service.refreshPreparedSnapshot(true);
        assertFreshReadSnapshot(camera.commands.subList(beforeRefresh, camera.commands.size()));
        UsbPictureControlProbe.ItemRecord firstSlot = refreshed.items.stream()
                .filter(item -> item.itemCode == TARGET).findFirst().orElseThrow();
        check(firstSlot.parsed && !firstSlot.empty,
                "The next explicit confirmation observes the first registered slot as occupied");
        equal(NcpCompatibilityFixtures.expectedData(4, -4), firstSlot.rawBytes());
        String firstReadbackHash = first.readback.sha256;

        final int secondTarget = TARGET + 1;
        int beforeSecond = camera.commands.size();
        UsbPictureControlImport.Result second = service.importPreparedNcpPresetKeepingConnection(
                NcpCompatibilityFixtures.ncp(), 0, 0, secondTarget);
        check(second.completed && second.readbackVerified && second.sessionRetainedAfterImport
                        && !second.sessionClosed && service.isPrepared(),
                "A second user-requested import may retain the same physical session after its own guarded readback");
        equal(9, second.initialSlots.size());
        check(second.initialTargetEmpty && second.recheckTargetEmpty,
                "Each explicit registration performs its own fresh target checks");
        equal(NcpCompatibilityFixtures.expectedData(4, -4), camera.slots.get(TARGET));
        equal(NcpCompatibilityFixtures.expectedData(0, 0), camera.slots.get(secondTarget));
        equal(2, camera.uploads.size());
        equal(2L, camera.count(0x90cd));
        equal(1L, camera.commands.subList(beforeSecond, camera.commands.size()).stream()
                .filter(command -> command.operation == 0x90cd).count());
        equal(firstReadbackHash, first.readback.sha256);
        check(first.completed && first.readbackVerified,
                "A later explicit registration cannot replace the earlier import's immutable verification evidence");
        equal(0L, camera.count(0x1003));
        equal(1L, camera.count(0x1002));
        equal(2, camera.connectionAttempts);
        service.keepPreparedAlive();
        service.refreshPreparedSnapshot(false);
        equal(2L, camera.count(0x90cd));
        camera.assertOpen();
        service.releasePrepared();
        check(!service.isPrepared() && !service.hasPreparedSession(),
                "Only an explicit release ends the successfully retained session");
        check(first.completed && second.completed && first.sessionRetainedAfterImport && second.sessionRetainedAfterImport,
                "Historical import reports retain their verified outcomes after a later explicit disconnect");
        equal(1L, camera.count(0x1003));
        equal(1L, camera.count(0x1002));
        equal(2, camera.connectionAttempts);
        assertMonotonicOwnedTransactions(camera);
        camera.assertClosed();
    }

    private static void keepingConnectionNeverRetainsOrRetriesFailedRegistration() throws Exception {
        for (String fault : List.of("lost_write", "wrong_write_transaction", "lost_readback",
                "wrong_readback_transaction", "changed_readback", "write_rejected")) {
            Camera camera = new Camera();
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            service.prepareImport(HOST, PORT, GUID);
            if (fault.equals("write_rejected")) camera.writeResponse = 0x200f;
            else camera.fault = fault;
            try {
                service.importPreparedNcpPresetKeepingConnection(NcpCompatibilityFixtures.ncp(), 4, -4, TARGET);
                throw new AssertionError("Expected a failed retained-after-import registration");
            } catch (UsbPictureControlImport.ImportException error) {
                check(error.partialResult.writeAttempted && !error.partialResult.completed
                                && !error.partialResult.sessionRetainedAfterImport,
                        "A failed or uncertain registration cannot claim a successfully retained connection");
            }
            check(!service.isPrepared() && !service.hasPreparedSession(),
                    "Failure is terminal even when successful imports were requested to stay connected");
            equal(1L, camera.count(0x90cd));
            equal(1, camera.uploads.size());
            int before = camera.commands.size();
            expectPreparedImportFailure(service);
            equal(before, camera.commands.size());
            equal(1L, camera.count(0x90cd));
            equal(2, camera.connectionAttempts);
            camera.assertClosed();
        }
    }

    private static void keepingConnectionStillRejectsOccupiedAndRacedTargets() throws Exception {
        for (boolean race : new boolean[] {false, true}) {
            Camera camera = new Camera();
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            service.prepareImport(HOST, PORT, GUID);
            if (race) {
                camera.race = true;
                camera.raceAtTargetRead = 3;
            } else camera.slots.put(TARGET, NcpCompatibilityFixtures.expectedData(0, 0));
            try {
                service.importPreparedNcpPresetKeepingConnection(NcpCompatibilityFixtures.ncp(), 4, -4, TARGET);
                throw new AssertionError("Expected an occupied target to stop the requested import");
            } catch (UsbPictureControlImport.ImportException error) {
                check(!error.partialResult.writeAttempted && !error.partialResult.completed
                                && !error.partialResult.sessionRetainedAfterImport,
                        "Keeping a successful connection never bypasses the fresh empty-slot observations");
            }
            equal(0L, camera.count(0x90cd));
            equal(0, camera.uploads.size());
            equal(2, camera.connectionAttempts);
            camera.assertClosed();
        }
    }

    private static void idleEventFailureRemainsReportableUntilExplicitRelease() throws Exception {
        Camera camera = new Camera();
        PtpIpClient album = new PtpIpClient(camera::connect);
        album.connect(HOST, PORT, GUID, "Synthetic album");
        PtpIpClient owner = album.transferToPictureControl();
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        service.prepareFromHandoff(owner, HOST, PORT, false);
        CountDownLatch closed = new CountDownLatch(1);
        owner.setOnClosed(closed::countDown);
        camera.sockets.get(1).input.close();
        check(closed.await(5, TimeUnit.SECONDS), "Synthetic event EOF closes the retained physical connection");
        check(!service.isPrepared() && service.hasPreparedSession(),
                "A failed idle connection retains ownership evidence until its error is reported and released");
        String originalReason = owner.closeReason();
        check(!originalReason.isEmpty(), "The failed physical owner records the original event-channel error");
        try {
            service.releasePrepared();
            throw new AssertionError("Expected failed idle-session cleanup evidence");
        } catch (UsbPictureControlProbe.ProbeException error) {
            check(error.partialResult.diagnostics.stream().anyMatch(diagnostic -> diagnostic.contains(originalReason)),
                    "Release retains the original event failure diagnostics");
        }
        check(!service.hasPreparedSession(), "Releasing failed idle ownership clears the reporting state");
        equal(0L, camera.count(0x90cd));
        equal(0L, camera.count(0x1003));
        equal(2, camera.connectionAttempts);
        camera.assertClosed();
    }

    private static void cancellationAfterVerifiedReadbackDoesNotRetainConnection() throws Exception {
        Camera camera = new Camera();
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        service.prepareImport(HOST, PORT, GUID);
        camera.fault = "cancel_after_readback";
        try {
            service.importPreparedNcpPresetKeepingConnection(NcpCompatibilityFixtures.ncp(), 4, -4, TARGET);
            throw new AssertionError("Expected late cancellation before connection retention");
        } catch (UsbPictureControlImport.ImportException error) {
            check(error.partialResult.readbackVerified && error.partialResult.writeResponseOk
                            && !error.partialResult.completed && !error.partialResult.sessionRetainedAfterImport,
                    "Late cancellation preserves exact readback evidence but cannot retain or report full completion");
        } finally { Thread.interrupted(); }
        check(!service.isPrepared() && !service.hasPreparedSession(),
                "Cancellation between readback and handoff does not leave a reusable registration owner");
        equal(1L, camera.count(0x90cd));
        equal(0L, camera.count(0x1003));
        equal(2, camera.connectionAttempts);
        camera.assertClosed();
    }

    private static void matchingDeviceBusyKeepsTheSessionForTheNextReadOnlyHeartbeat() throws Exception {
        Camera camera = new Camera();
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        UsbPictureControlProbe.Result snapshot = service.prepareImport(HOST, PORT, GUID);
        camera.fault = "busy_heartbeat";
        int before = camera.commands.size();
        try {
            service.keepPreparedAlive();
            throw new AssertionError("Expected DeviceBusy to complete the heartbeat with a camera response");
        } catch (UsbPictureControlProbe.ProbeException error) {
            equal(Integer.valueOf(0x2019), error.responseCode);
            check(!error.partialResult.protocolPhaseUnconfirmed,
                    "A complete matching DeviceBusy response retains a known protocol boundary");
        }
        check(service.isPrepared() && service.hasPreparedSession() && snapshot.sessionRetained,
                "Matching DeviceBusy leaves the owned prepared session available for a later read-only tick");
        equal(before + 1, camera.commands.size());
        Command busy = camera.commands.get(before);
        equal(0x90cc, busy.operation);
        equal(new long[] {1, 0}, busy.parameters);
        equal(0L, camera.count(0x1003));
        equal(0L, camera.count(0x90cd));
        equal(2, camera.connectionAttempts);
        camera.assertOpen();
        camera.fault = "";
        service.keepPreparedAlive();
        check(camera.commands.get(camera.commands.size() - 1).transaction > busy.transaction,
                "The next independent heartbeat advances the same session transaction sequence after DeviceBusy");
        equal(before + 2, camera.commands.size());
        service.refreshPreparedSnapshot(false);
        check(service.isPrepared(), "A busy heartbeat does not prevent a later complete read-only snapshot refresh");
        equal(0L, camera.count(0x90cd));
        equal(0L, camera.count(0x1003));
        equal(2, camera.connectionAttempts);
        assertMonotonicOwnedTransactions(camera);
        service.releasePrepared();
        equal(1L, camera.count(0x1003));
        camera.assertClosed();
    }

    private static void wrongTransactionBusyCannotPreserveThePreparedSession() throws Exception {
        Camera camera = new Camera();
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        service.prepareImport(HOST, PORT, GUID);
        camera.fault = "wrong_busy_heartbeat_transaction";
        try {
            service.keepPreparedAlive();
            throw new AssertionError("Expected a mismatched DeviceBusy transaction to abort the heartbeat");
        } catch (UsbPictureControlProbe.ProbeException error) {
            check(error.partialResult.protocolPhaseUnconfirmed,
                    "A DeviceBusy code with the wrong transaction is still an unconfirmed protocol phase");
        }
        check(!service.isPrepared() && !service.hasPreparedSession(),
                "Mismatched DeviceBusy cannot retain ownership as a harmless camera-busy response");
        equal(0L, camera.count(0x1003));
        equal(0L, camera.count(0x90cd));
        equal(2, camera.connectionAttempts);
        int before = camera.commands.size();
        expectPreparedImportFailure(service);
        equal(before, camera.commands.size());
        equal(2, camera.connectionAttempts);
        camera.assertClosed();
    }

    private static void overwriteConfirmationIsBoundToAnImmutableOccupiedSnapshot() throws Exception {
        byte[] source = occupiedPreset("CONFIRMED OLD");
        byte[] preserved = source.clone();
        PictureControlImportSession.OverwriteConfirmation confirmation =
                new PictureControlImportSession.OverwriteConfirmation(TARGET, source);
        Arrays.fill(source, (byte) 0);
        byte[] exposed = confirmation.rawBytes(); Arrays.fill(exposed, (byte) 0);
        equal(TARGET, confirmation.targetItem());
        equal("CONFIRMED OLD", confirmation.registrationName());
        equal(2, confirmation.basePictureControl());
        equal(UsbPictureControlProbe.sha256(preserved), confirmation.sha256());
        equal(preserved, confirmation.rawBytes());
        for (String invalid : List.of("null", "empty", "malformed", "low_target", "high_target")) {
            try {
                new PictureControlImportSession.OverwriteConfirmation(invalid.equals("low_target") ? 200
                        : invalid.equals("high_target") ? 210 : TARGET, invalid.equals("null") ? null
                        : invalid.equals("empty") ? new byte[36] : invalid.equals("malformed") ? new byte[37] : preserved);
                throw new AssertionError("Expected invalid occupied-slot confirmation to be refused");
            } catch (IOException expected) { checks++; }
        }
    }

    private static void explicitOverwriteKeepsConnectionForASecondFreshConfirmation() throws Exception {
        Camera camera = new Camera();
        byte[] original = occupiedPreset("CONFIRMED OLD");
        camera.slots.put(TARGET, original.clone());
        camera.refuseAfterCloseSession = true;
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        service.prepareImport(HOST, PORT, GUID);
        PictureControlImportSession.OverwriteConfirmation firstConfirmation =
                new PictureControlImportSession.OverwriteConfirmation(TARGET, original);
        UsbPictureControlImport.Result first = service.importPreparedNcpPresetKeepingConnection(
                NcpCompatibilityFixtures.ncp(), 4, -4, TARGET, firstConfirmation);
        check(first.completed && first.readbackVerified && first.sessionRetainedAfterImport && !first.sessionClosed,
                "An explicitly confirmed occupied-slot overwrite is verified before retaining its connection");
        check(first.overwriteRequested && first.initialTargetMatched && first.recheckTargetMatched && first.freshCapabilityVerified,
                "Overwrite requires fresh capability and matching old contents in both target observations");
        check(!first.initialTargetEmpty && !first.recheckTargetEmpty,
                "An occupied overwrite must not be mislabeled as empty-slot registration");
        equal(firstConfirmation.sha256(), first.confirmedTargetSha256);
        equal("CONFIRMED OLD", first.confirmedTargetName);
        equal(TARGET, first.confirmedTargetItem);
        equal(NcpCompatibilityFixtures.expectedData(4, -4), camera.slots.get(TARGET));
        service.keepPreparedAlive();
        UsbPictureControlProbe.Result current = service.refreshPreparedSnapshot(true);
        UsbPictureControlProbe.ItemRecord item = current.items.stream().filter(record -> record.itemCode == TARGET).findFirst().orElseThrow();
        PictureControlImportSession.OverwriteConfirmation secondConfirmation =
                new PictureControlImportSession.OverwriteConfirmation(TARGET, item.rawBytes());
        UsbPictureControlImport.Result second = service.importPreparedNcpPresetKeepingConnection(
                NcpCompatibilityFixtures.ncp(), 0, 0, TARGET, secondConfirmation);
        check(second.completed && second.initialTargetMatched && second.recheckTargetMatched
                        && second.sessionRetainedAfterImport && service.isPrepared(),
                "A second explicit overwrite requires a new confirmation of the current occupied contents");
        equal(NcpCompatibilityFixtures.expectedData(0, 0), camera.slots.get(TARGET));
        equal(secondConfirmation.sha256(), second.confirmedTargetSha256);
        equal(2L, camera.count(0x90cd));
        equal(2, camera.uploads.size());
        equal(0L, camera.count(0x1003));
        equal(1L, camera.count(0x1002));
        equal(2, camera.connectionAttempts);
        check(first.completed && first.confirmedTargetSha256.equals(firstConfirmation.sha256()),
                "The second overwrite cannot replace the first import's confirmed-target evidence");
        for (int other = 201; other <= 209; other++) if (other != TARGET) equal(new byte[36], camera.slots.get(other));
        assertMonotonicOwnedTransactions(camera);
        service.releasePrepared();
        equal(1L, camera.count(0x1003));
        camera.assertClosed();
    }

    private static void explicitOverwriteRejectsChangedOrUnconfirmedTargets() throws Exception {
        for (String change : List.of("no_confirmation", "wrong_target", "different_bytes", "different_name", "different_base",
                "became_empty", "recheck_bytes", "recheck_name", "recheck_empty")) {
            Camera camera = new Camera();
            byte[] original = occupiedPreset("CONFIRMED OLD");
            camera.slots.put(TARGET, original.clone());
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            service.prepareImport(HOST, PORT, GUID);
            PictureControlImportSession.OverwriteConfirmation confirmation =
                    new PictureControlImportSession.OverwriteConfirmation(change.equals("wrong_target") ? TARGET + 1 : TARGET, original);
            byte[] different = original.clone(); different[26] = 4;
            if (change.equals("different_bytes")) camera.slots.put(TARGET, different);
            if (change.equals("different_name")) camera.slots.put(TARGET, occupiedPreset("OTHER NAME"));
            if (change.equals("different_base")) { byte[] base = original.clone(); base[0] = 1; camera.slots.put(TARGET, base); }
            if (change.equals("became_empty")) camera.slots.put(TARGET, new byte[36]);
            if (change.startsWith("recheck_")) {
                camera.replaceAtTargetRead = 3;
                camera.replacementTarget = change.equals("recheck_bytes") ? different
                        : change.equals("recheck_name") ? occupiedPreset("OTHER NAME") : new byte[36];
            }
            try {
                service.importPreparedNcpPresetKeepingConnection(NcpCompatibilityFixtures.ncp(), 4, -4, TARGET,
                        change.equals("no_confirmation") ? null : confirmation);
                throw new AssertionError("Expected a changed or unconfirmed occupied target to stop");
            } catch (UsbPictureControlImport.ImportException error) {
                check(!error.partialResult.writeAttempted && !error.partialResult.completed,
                        "An occupied target can be overwritten only while both observations match its explicit confirmation");
                if (change.startsWith("recheck_")) {
                    check(error.partialResult.initialTargetMatched && !error.partialResult.recheckTargetMatched,
                            "Overwrite's final recheck detects a last-moment target-content change");
                }
            }
            equal(0L, camera.count(0x90cd));
            equal(0, camera.uploads.size());
            equal(2, camera.connectionAttempts);
            camera.assertClosed();
        }
    }

    private static void uncertainOrCancelledOverwriteNeverRetainsOrReplays() throws Exception {
        for (String fault : List.of("lost_write", "lost_readback", "changed_readback", "cancel_after_readback")) {
            Camera camera = new Camera();
            byte[] original = occupiedPreset("CONFIRMED OLD"); camera.slots.put(TARGET, original);
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            service.prepareImport(HOST, PORT, GUID);
            PictureControlImportSession.OverwriteConfirmation confirmation = new PictureControlImportSession.OverwriteConfirmation(TARGET, original);
            camera.fault = fault;
            try {
                service.importPreparedNcpPresetKeepingConnection(NcpCompatibilityFixtures.ncp(), 4, -4, TARGET, confirmation);
                throw new AssertionError("Expected failed or cancelled overwrite");
            } catch (UsbPictureControlImport.ImportException error) {
                check(error.partialResult.overwriteRequested && error.partialResult.writeAttempted
                                && !error.partialResult.completed && !error.partialResult.sessionRetainedAfterImport,
                        "Failed overwrite retains its possible target effect but cannot remain connected for an automatic replay");
            } finally { Thread.interrupted(); }
            check(!service.isPrepared() && !service.hasPreparedSession(), "Failed overwrite clears retained ownership");
            equal(1L, camera.count(0x90cd));
            int before = camera.commands.size();
            expectPreparedImportFailure(service);
            equal(before, camera.commands.size());
            equal(2, camera.connectionAttempts);
            camera.assertClosed();
        }
    }

    private static void anOldOverwriteConfirmationCannotAuthorizeASecondRegistration() throws Exception {
        Camera camera = new Camera();
        byte[] original = occupiedPreset("CONFIRMED OLD"); camera.slots.put(TARGET, original);
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        service.prepareImport(HOST, PORT, GUID);
        PictureControlImportSession.OverwriteConfirmation confirmation = new PictureControlImportSession.OverwriteConfirmation(TARGET, original);
        service.importPreparedNcpPresetKeepingConnection(NcpCompatibilityFixtures.ncp(), 4, -4, TARGET, confirmation);
        try {
            service.importPreparedNcpPresetKeepingConnection(NcpCompatibilityFixtures.ncp(), 0, 0, TARGET, confirmation);
            throw new AssertionError("Expected stale occupied-slot confirmation to be refused");
        } catch (UsbPictureControlImport.ImportException error) {
            check(!error.partialResult.writeAttempted && !error.partialResult.initialTargetMatched,
                    "An earlier confirmation cannot authorize overwriting bytes registered by a later import");
        }
        equal(1L, camera.count(0x90cd));
        equal(NcpCompatibilityFixtures.expectedData(4, -4), camera.slots.get(TARGET));
        equal(2, camera.connectionAttempts);
        camera.assertClosed();
    }

    private static byte[] occupiedPreset(String name) {
        byte[] bytes = NcpCompatibilityFixtures.expectedData(0, 0);
        Arrays.fill(bytes, 2, 22, (byte) 0);
        byte[] encoded = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, bytes, 2, encoded.length);
        return bytes;
    }

    private static void cancellationAfterTheFinalOverwriteCheckNeverSendsAWrite() throws Exception {
        Camera camera = new Camera();
        byte[] original = occupiedPreset("CONFIRMED OLD"); camera.slots.put(TARGET, original.clone());
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        service.prepareImport(HOST, PORT, GUID);
        PictureControlImportSession.OverwriteConfirmation confirmation = new PictureControlImportSession.OverwriteConfirmation(TARGET, original);
        camera.fault = "cancel_after_overwrite_recheck";
        try {
            service.importPreparedNcpPresetKeepingConnection(NcpCompatibilityFixtures.ncp(), 4, -4, TARGET, confirmation);
            throw new AssertionError("Expected cancellation between the last occupied-target check and registration");
        } catch (UsbPictureControlImport.ImportException error) {
            check(error.partialResult.initialTargetMatched && error.partialResult.recheckTargetMatched
                            && !error.partialResult.writeAttempted && !error.partialResult.completed,
                    "Even a twice-matching overwrite confirmation cannot send Set after cancellation");
        } finally { Thread.interrupted(); }
        equal(0L, camera.count(0x90cd));
        equal(0, camera.uploads.size());
        equal(original, camera.slots.get(TARGET));
        check(!service.isPrepared() && !service.hasPreparedSession(), "Cancelled overwrite releases its owned connection");
        equal(2, camera.connectionAttempts);
        camera.assertClosed();
    }

    private static void matchingGeneralErrorKeepsTheNextReadOnlyHeartbeatUsable() throws Exception {
        check(WirelessPictureControl.isRecoverableKeepAliveResponse(0x2002), "Only classified temporary response codes may preserve heartbeat ownership");
        check(WirelessPictureControl.isRecoverableKeepAliveResponse(0x2019), "DeviceBusy retains the same narrow heartbeat recovery classification");
        for (Integer code : new Integer[] {null, 0x2001, 0x2003, 0x2004, 0x201d, 0x2005}) {
            check(!WirelessPictureControl.isRecoverableKeepAliveResponse(code), "Other or absent response codes cannot be treated as recoverable heartbeat errors");
        }
        Camera camera = new Camera();
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        UsbPictureControlProbe.Result snapshot = service.prepareImport(HOST, PORT, GUID);
        camera.heartbeatResponseCode = 0x2002;
        int before = camera.commands.size();
        try {
            service.keepPreparedAlive();
            throw new AssertionError("Expected GeneralError to be reported rather than counted as a successful heartbeat");
        } catch (UsbPictureControlProbe.ProbeException error) {
            equal(Integer.valueOf(0x2002), error.responseCode);
            check(!error.partialResult.protocolPhaseUnconfirmed,
                    "A complete matching GeneralError preserves a confirmed response boundary");
            check(error.partialResult.diagnostics.stream().anyMatch(message -> message.contains("GeneralError") && message.contains("2002")),
                    "A retained heartbeat still records the actual camera failure code");
        }
        check(service.isPrepared() && service.hasPreparedSession() && snapshot.sessionRetained,
                "Matching GeneralError may keep only its current healthy owned session");
        equal(before + 1, camera.commands.size());
        long failedTransaction = camera.commands.get(before).transaction;
        equal(0L, camera.count(0x1003));
        equal(0L, camera.count(0x90cd));
        equal(2, camera.connectionAttempts);
        camera.assertOpen();
        camera.heartbeatResponseCode = 0;
        service.keepPreparedAlive();
        check(camera.commands.get(camera.commands.size() - 1).transaction > failedTransaction,
                "The next independent heartbeat continues the same transaction sequence after GeneralError");
        equal(before + 2, camera.commands.size());
        equal(0L, camera.count(0x1003));
        equal(0L, camera.count(0x90cd));
        service.releasePrepared();
        equal(1L, camera.count(0x1003));
        camera.assertClosed();
    }

    private static void unconfirmedAndNonRecoverableHeartbeatErrorsAreTerminal() throws Exception {
        for (int code : new int[] {0x2002, 0x2003, 0x2004, 0x201d}) {
            Camera camera = new Camera();
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            service.prepareImport(HOST, PORT, GUID);
            camera.heartbeatResponseCode = code;
            camera.heartbeatWrongTransaction = code == 0x2002;
            try {
                service.keepPreparedAlive();
                throw new AssertionError("Expected mismatched or non-recoverable heartbeat response to stop ownership");
            } catch (UsbPictureControlProbe.ProbeException error) {
                if (code == 0x2002) check(error.partialResult.protocolPhaseUnconfirmed,
                        "A GeneralError with the wrong transaction is an unconfirmed phase, never a temporary camera response");
                else equal(Integer.valueOf(code), error.responseCode);
            }
            check(!service.isPrepared() && !service.hasPreparedSession(),
                    "SessionNotOpen, InvalidTransactionID, InvalidParameter and mismatched GeneralError remain terminal");
            equal(0L, camera.count(0x90cd));
            equal(2, camera.connectionAttempts);
            int afterFailure = camera.commands.size();
            expectPreparedImportFailure(service);
            equal(afterFailure, camera.commands.size());
            equal(2, camera.connectionAttempts);
            camera.assertClosed();
        }
    }

    private static void firstPostImportGeneralErrorDoesNotCloseOrBlockTheNextExplicitImport() throws Exception {
        Camera camera = new Camera();
        byte[] original = occupiedPreset("CONFIRMED OLD"); camera.slots.put(TARGET, original);
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        service.prepareImport(HOST, PORT, GUID);
        PictureControlImportSession.OverwriteConfirmation confirmation = new PictureControlImportSession.OverwriteConfirmation(TARGET, original);
        UsbPictureControlImport.Result first = service.importPreparedNcpPresetKeepingConnection(
                NcpCompatibilityFixtures.ncp(), 4, -4, TARGET, confirmation);
        check(first.completed && first.readbackVerified && first.sessionRetainedAfterImport, "The synthetic overwrite succeeds before the post-import heartbeat fails");
        camera.heartbeatResponseCode = 0x2002;
        try {
            service.keepPreparedAlive();
            throw new AssertionError("Expected first post-import heartbeat GeneralError");
        } catch (UsbPictureControlProbe.ProbeException error) { equal(Integer.valueOf(0x2002), error.responseCode); }
        check(service.isPrepared(), "A complete GeneralError immediately after verified import does not close its retained connection");
        equal(1L, camera.count(0x90cd));
        equal(0L, camera.count(0x1003));
        equal(1L, camera.count(0x1002));
        equal(2, camera.connectionAttempts);
        camera.heartbeatResponseCode = 0;
        service.keepPreparedAlive();
        UsbPictureControlProbe.Result refreshed = service.refreshPreparedSnapshot(true);
        byte[] current = refreshed.items.stream().filter(item -> item.itemCode == TARGET).findFirst().orElseThrow().rawBytes();
        UsbPictureControlImport.Result second = service.importPreparedNcpPresetKeepingConnection(NcpCompatibilityFixtures.ncp(), 0, 0, TARGET,
                new PictureControlImportSession.OverwriteConfirmation(TARGET, current));
        check(second.completed && second.readbackVerified && second.sessionRetainedAfterImport,
                "A later explicit user-confirmed overwrite remains possible after the recovered read-only heartbeat");
        equal(2L, camera.count(0x90cd));
        equal(2, camera.uploads.size());
        equal(0L, camera.count(0x1003));
        equal(1L, camera.count(0x1002));
        equal(2, camera.connectionAttempts);
        assertMonotonicOwnedTransactions(camera);
        service.releasePrepared();
        equal(1L, camera.count(0x1003));
        camera.assertClosed();
    }

    private static void generalErrorDuringAWriteCannotUseHeartbeatRecovery() throws Exception {
        Camera camera = new Camera();
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        service.prepareImport(HOST, PORT, GUID);
        camera.writeResponse = 0x2002;
        try {
            service.importPreparedNcpPresetKeepingConnection(NcpCompatibilityFixtures.ncp(), 4, -4, TARGET);
            throw new AssertionError("Expected GeneralError during registration to stop the write flow");
        } catch (UsbPictureControlImport.ImportException error) {
            equal(Integer.valueOf(0x2002), error.responseCode);
            check(error.partialResult.writeAttempted && !error.partialResult.completed && !error.partialResult.sessionRetainedAfterImport,
                    "Read-only heartbeat recovery cannot widen write success or retention rules");
        }
        check(!service.isPrepared() && !service.hasPreparedSession(), "GeneralError during Set is terminal and cannot be replayed");
        equal(1L, camera.count(0x90cd));
        equal(2, camera.connectionAttempts);
        camera.assertClosed();
    }

    private static void preparedPresetCanBrowseAndReturnForAFreshExplicitOverwrite() throws Exception {
        Camera camera = new Camera(); camera.advertiseAlbum = true; camera.refuseAfterCloseSession = true;
        byte[] original = occupiedPreset("CONFIRMED OLD"); camera.slots.put(TARGET, original);
        WirelessPictureControl firstService = new WirelessPictureControl(camera::connect);
        firstService.prepareImport(HOST, PORT, GUID);
        UsbPictureControlImport.Result first = firstService.importPreparedNcpPresetKeepingConnection(
                NcpCompatibilityFixtures.ncp(), 4, -4, TARGET, new PictureControlImportSession.OverwriteConfirmation(TARGET, original));
        check(first.completed && first.sessionRetainedAfterImport, "A verified import leaves owned preset state available for browsing");
        int beforeHandoff = camera.commands.size();
        PtpIpClient album = firstService.transferPreparedToAlbum();
        check(!firstService.isPrepared() && !firstService.hasPreparedSession() && album.isOpen(),
                "Returning to album revokes the old wrapper and moves its live owned physical connection");
        equal(beforeHandoff, camera.commands.size());
        firstService.close();
        check(album.isOpen(), "Closing an obsolete preset wrapper cannot close its returned album connection");
        int beforeOldImport = camera.commands.size(); expectPreparedImportFailure(firstService); equal(beforeOldImport, camera.commands.size());
        long next = album.claimTransferredAlbumSession();
        check(next > camera.commands.get(beforeHandoff - 1).transaction, "Album inherits the next transaction after verified registration readback");
        album.readAlbumDeviceInfo();
        equal(le32(0), album.getData(0x1004, 64));
        equal(concat(le32(1), le32(42)), album.getData(0x1007, 64, 1, 0x3801, 0));
        equal(camera.albumThumbnail, album.getLargeThumbnail(42));
        equal(0L, camera.count(0x1009));
        equal(0L, camera.count(0x1003));
        equal(1L, camera.count(0x1002));
        equal(2, camera.connectionAttempts);
        PtpIpClient returned = album.transferToPictureControl();
        WirelessPictureControl secondService = new WirelessPictureControl(camera::connect);
        UsbPictureControlProbe.Result refreshed = secondService.prepareFromHandoff(returned, HOST, PORT, true);
        album.close(); album.abortProtocol(new IOException("obsolete album callback"));
        check(secondService.isPrepared(), "Stale album callbacks cannot invalidate the returned preset owner");
        byte[] current = refreshed.items.stream().filter(item -> item.itemCode == TARGET).findFirst().orElseThrow().rawBytes();
        UsbPictureControlImport.Result second = secondService.importPreparedNcpPresetKeepingConnection(
                NcpCompatibilityFixtures.ncp(), 0, 0, TARGET, new PictureControlImportSession.OverwriteConfirmation(TARGET, current));
        check(second.completed && second.initialTargetMatched && second.recheckTargetMatched && second.freshCapabilityVerified,
                "Returning from album still requires a new complete read plan and explicit current-target confirmation");
        equal(2L, camera.count(0x90cd));
        equal(2, camera.uploads.size());
        equal(0L, camera.count(0x1003));
        equal(1L, camera.count(0x1002));
        equal(2, camera.connectionAttempts);
        assertMonotonicOwnedTransactions(camera);
        secondService.releasePrepared();
        equal(1L, camera.count(0x1003));
        camera.assertClosed();
    }

    private static void returningFromAlbumCannotReuseAnOldOverwriteConfirmation() throws Exception {
        Camera camera = new Camera();
        byte[] original = occupiedPreset("CONFIRMED OLD"); camera.slots.put(TARGET, original);
        PictureControlImportSession.OverwriteConfirmation old = new PictureControlImportSession.OverwriteConfirmation(TARGET, original);
        WirelessPictureControl firstService = new WirelessPictureControl(camera::connect);
        firstService.prepareImport(HOST, PORT, GUID);
        firstService.importPreparedNcpPresetKeepingConnection(NcpCompatibilityFixtures.ncp(), 4, -4, TARGET, old);
        PtpIpClient album = firstService.transferPreparedToAlbum(); album.claimTransferredAlbumSession();
        album.readAlbumDeviceInfo();
        WirelessPictureControl nextService = new WirelessPictureControl(camera::connect);
        nextService.prepareFromHandoff(album.transferToPictureControl(), HOST, PORT, true);
        try {
            nextService.importPreparedNcpPresetKeepingConnection(NcpCompatibilityFixtures.ncp(), 0, 0, TARGET, old);
            throw new AssertionError("Expected pre-browse occupied-slot proof to reject changed current contents");
        } catch (UsbPictureControlImport.ImportException error) {
            check(!error.partialResult.writeAttempted && !error.partialResult.initialTargetMatched,
                    "Browsing cannot make a stale overwrite confirmation valid again");
        }
        equal(1L, camera.count(0x90cd));
        equal(2, camera.connectionAttempts);
        equal(1L, camera.count(0x1002));
        camera.assertClosed();
    }

    private static void cancelledOrFailedPreparedTransferCannotEscapeAnotherOwner() throws Exception {
        for (boolean eventFailure : new boolean[] {false, true}) {
            Camera camera = new Camera();
            PtpIpClient initialAlbum = new PtpIpClient(camera::connect);
            initialAlbum.connect(HOST, PORT, GUID, "Synthetic album");
            PtpIpClient preparedOwner = initialAlbum.transferToPictureControl();
            WirelessPictureControl service = new WirelessPictureControl(camera::connect);
            service.prepareFromHandoff(preparedOwner, HOST, PORT, true);
            PtpIpClient[] escaped = {null};
            if (eventFailure) {
                CountDownLatch failed = new CountDownLatch(1); preparedOwner.setOnClosed(failed::countDown);
                camera.sockets.get(1).input.close();
                check(failed.await(5, TimeUnit.SECONDS), "The prepared owner's event failure is confirmed before transfer is attempted");
            } else Thread.currentThread().interrupt();
            try {
                escaped[0] = service.transferPreparedToAlbum();
                throw new AssertionError("Expected a cancelled or disconnected prepared owner to refuse album handoff");
            } catch (IOException expected) { checks++; }
            finally { Thread.interrupted(); service.close(); if (escaped[0] != null) escaped[0].close(); }
            equal(0L, camera.count(0x90cd));
            equal(2, camera.connectionAttempts);
            camera.assertClosed();
        }
    }

    private static void assertFreshReadSnapshot(List<Command> commands) {
        equal(0x1001, commands.get(0).operation);
        check(commands.stream().anyMatch(command -> command.operation == 0x90cf),
                "Refreshing reads current advertised Picture Control capability");
        for (int item = 201; item <= 209; item++) {
            final int expectedItem = item;
            equal(1L, commands.stream().filter(command -> command.operation == 0x90cc
                    && command.parameters[0] == expectedItem).count());
        }
        check(commands.stream().allMatch(command -> command.operation == 0x1001 || command.operation == 0x90ca
                        || command.operation == 0x90cf || command.operation == 0x90cc),
                "Settings refresh never reopens, closes or writes the camera session");
    }

    private static UsbPictureControlImport.ImportException expectPreparedImportFailure(
            WirelessPictureControl service) throws Exception {
        try {
            service.importPreparedNcpPreset(NcpCompatibilityFixtures.ncp(), 4, -4, TARGET);
        } catch (UsbPictureControlImport.ImportException error) { checks++; return error; }
        throw new AssertionError("Expected a stopped retained wireless registration");
    }

    private static void assertMonotonicOwnedTransactions(Camera camera) {
        boolean owned = false;
        long transaction = 0;
        for (Command command : camera.commands) {
            if (command.operation == 0x1002) { owned = true; continue; }
            if (owned) {
                check(command.transaction > transaction,
                        "Every command, heartbeat and final cleanup advances the same owned transaction sequence");
                transaction = command.transaction;
            }
        }
    }

    private static void invalidInputsNeverOpenSockets() throws Exception {
        for (String fault : List.of("null", "converted", "malformed", "target", "middle", "clarity")) {
            Camera camera = new Camera();
            byte[] input = NcpCompatibilityFixtures.ncp();
            int target = TARGET, middle = 4, clarity = -4;
            if (fault.equals("null")) input = null;
            if (fault.equals("converted")) input = NcpCompatibilityFixtures.expectedData(4, -4);
            if (fault.equals("malformed")) input[0] = 0;
            if (fault.equals("target")) target = 210;
            if (fault.equals("middle")) middle = 100;
            if (fault.equals("clarity")) clarity = 100;
            UsbPictureControlImport.Result result = failed(camera, input, middle, clarity, target).partialResult;
            check(!result.writeAttempted && !result.completed, "Invalid source or adaptation cannot become a write");
            equal(0, camera.sockets.size());
            equal(0, camera.commands.size());
            equal(input == null ? 0 : input.length, result.sourceNcpSize);
            if (input != null) equal(UsbPictureControlProbe.sha256(input), result.sourceNcpSha256);
        }
    }

    private static void unsupportedCamerasAndOccupiedSlotsNeverWrite() throws Exception {
        for (String fault : List.of("model", "operation", "occupied", "raced")) {
            Camera camera = new Camera();
            if (fault.equals("model")) camera.model = "Another camera";
            if (fault.equals("operation")) camera.missingOperation = 0x90cd;
            if (fault.equals("occupied")) camera.slots.put(TARGET, NcpCompatibilityFixtures.expectedData(0, 0));
            camera.race = fault.equals("raced");
            UsbPictureControlImport.Result result = failed(camera).partialResult;
            equal(0L, camera.count(0x90cd));
            equal(0, camera.uploads.size());
            check(!result.completed && !result.writeAttempted, "Unsupported or occupied targets never register");
            if (fault.equals("model") || fault.equals("operation")) {
                equal(1, camera.commands.size());
                check(!result.sessionOpened && !result.sessionClosed, "Preflight cannot take over another session");
            } else {
                check(result.sessionOpened && result.sessionClosed, "A known occupied target still closes its owned session");
            }
            if (fault.equals("raced")) {
                check(result.initialTargetEmpty && !result.recheckTargetEmpty, "The final observation catches a raced slot");
                equal("EmptySlotRecheck", result.failurePhase);
            }
            camera.assertClosed();
        }
        Camera camera = new Camera();
        camera.openResponse = 0x201e;
        UsbPictureControlImport.ImportException failure = failed(camera);
        equal(Integer.valueOf(0x201e), failure.responseCode);
        equal(0L, camera.count(0x1003));
        check(!failure.partialResult.sessionOpened, "A pre-existing session remains owned by its original initiator");
        camera.assertClosed();
    }

    private static void completeRejectionKeepsPrimaryAndCleanupEvidence() throws Exception {
        Camera camera = new Camera();
        camera.writeResponse = 0x200f;
        camera.cleanupResponse = 0x2002;
        UsbPictureControlImport.ImportException failure = failed(camera);
        UsbPictureControlImport.Result result = failure.partialResult;
        equal(Integer.valueOf(0x200f), failure.responseCode);
        check(result.writeAttempted && !result.writeResponseOk && !result.readbackVerified && !result.completed,
                "A complete camera rejection retains the sent write and does not read or retry it");
        equal(1L, camera.count(0x90cd));
        equal(1L, camera.count(0x1003));
        equal(11L, result.failedTransactionId);
        equal(TARGET, result.failedItemCode);
        equal(new long[] {TARGET, 0}, result.failedParameters);
        check(result.failedOperation.contains("90CD"), "Cleanup does not replace the primary write context");
        equal(0x2002, result.cleanupResponseCode);
        check(!result.cleanupError.isEmpty() && failure.getSuppressed().length == 1,
                "A separate cleanup failure is retained with its own context");
        camera.assertClosed();
    }

    private static void unconfirmedWriteAndReadbackStopAllCommands() throws Exception {
        for (String fault : List.of("lost_write", "wrong_write_transaction", "lost_readback", "wrong_readback_transaction")) {
            Camera camera = new Camera();
            camera.fault = fault;
            UsbPictureControlImport.Result result = failed(camera).partialResult;
            check(result.writeAttempted && !result.completed && !result.readbackVerified,
                    "An uncertain registration preserves the possible camera effect");
            check(result.protocolPhaseUnconfirmed && !result.sessionClosed,
                    "An unconfirmed protocol phase is never followed by a cleanup command");
            equal(1L, camera.count(0x90cd));
            equal(0L, camera.count(0x1003));
            equal(1, camera.uploads.size());
            equal(fault.contains("readback") ? 0x90cc : 0x90cd,
                    camera.commands.get(camera.commands.size() - 1).operation);
            camera.assertClosed();
        }
    }

    private static void changedReadbackAndCleanupFailuresCannotComplete() throws Exception {
        Camera changed = new Camera();
        changed.fault = "changed_readback";
        UsbPictureControlImport.Result mismatch = failed(changed).partialResult;
        check(mismatch.readbackParsed && mismatch.normalizationObserved && mismatch.differenceCount == 1
                        && !mismatch.readbackVerified && !mismatch.completed && mismatch.sessionClosed,
                "A complete readback mismatch allows owned cleanup but never reports exact success");
        equal(1L, changed.count(0x90cd));
        changed.assertClosed();
        Camera cleanup = new Camera();
        cleanup.cleanupResponse = 0x2002;
        UsbPictureControlImport.Result unclosed = failed(cleanup).partialResult;
        check(unclosed.readbackVerified && !unclosed.completed && !unclosed.sessionClosed,
                "Exact readback cannot hide a failed CloseSession");
        equal("SessionCleanup", unclosed.failurePhase);
        cleanup.assertClosed();
        Camera readCleanup = new Camera();
        readCleanup.cleanupResponse = 0x2002;
        try {
            new WirelessPictureControl(readCleanup::connect).probe(HOST, PORT, GUID);
            throw new AssertionError("Expected read cleanup failure");
        } catch (UsbPictureControlProbe.ProbeException error) {
            check(error.partialResult.items.size() == 10 && !error.partialResult.completed
                            && !error.partialResult.sessionClosed,
                    "A snapshot also requires acknowledged session cleanup");
        }
        readCleanup.assertClosed();
    }

    private static void cancelledReadbackRequiresSessionCleanupConfirmation() throws Exception {
        Camera camera = new Camera();
        camera.fault = "cancel_after_readback";
        try {
            UsbPictureControlImport.Result result = failed(camera).partialResult;
            check(result.readbackVerified && result.writeResponseOk && !result.sessionClosed && !result.completed,
                    "Late cancellation keeps verified write evidence and requires a new scan before retry");
            equal("SessionCleanupCancelled", result.failurePhase);
            equal(0L, camera.count(0x1003));
            check(!result.protocolPhaseUnconfirmed, "A fully received readback still has a confirmed boundary");
        } finally { Thread.interrupted(); }
        camera.assertClosed();
    }

    private static void concurrentCloseUnblocksTheSocketAndRejectsAnotherFlow() throws Exception {
        Camera camera = new Camera();
        camera.fault = "blocked_write";
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { service.importNcpPreset(HOST, PORT, GUID, NcpCompatibilityFixtures.ncp(), 4, -4, TARGET); }
            catch (Throwable error) { failure.set(error); }
        });
        worker.start();
        check(camera.blocked.await(5, TimeUnit.SECONDS), "The write reaches the intentionally blocked response");
        try {
            service.probe(HOST, PORT, GUID);
            throw new AssertionError("Expected single-flow exclusion");
        } catch (IllegalStateException expected) { checks++; }
        service.close();
        worker.join(5000);
        check(!worker.isAlive(), "Concurrent socket cancellation unblocks the worker");
        check(failure.get() instanceof UsbPictureControlImport.ImportException,
                "Cancellation retains the public import exception and partial result");
        UsbPictureControlImport.Result result = ((UsbPictureControlImport.ImportException) failure.get()).partialResult;
        check(result.writeAttempted && result.protocolPhaseUnconfirmed && !result.completed,
                "Cancellation during a pending write preserves uncertain effect evidence");
        equal(0L, camera.count(0x1003));
        equal(1L, camera.count(0x90cd));
        camera.assertClosed();
    }

    private static void closedInstanceCannotConnect() throws Exception {
        Camera camera = new Camera();
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        service.close();
        try {
            service.probe(HOST, PORT, GUID);
            throw new AssertionError("Expected cancelled probe");
        } catch (UsbPictureControlProbe.ProbeException error) {
            check(!error.partialResult.completed && error.partialResult.transport.equals("wifi"),
                    "Cancellation before connection preserves the requested transport in the report");
        }
        equal(0, camera.sockets.size());
    }

    private static void mainThreadCannotStartEitherFlow() throws Exception {
        Camera camera = new Camera();
        WirelessPictureControl service = new WirelessPictureControl(camera::connect);
        android.os.Looper.setMainThreadForTest(true);
        try {
            try {
                service.probe(HOST, PORT, GUID);
                throw new AssertionError("Expected main-thread probe refusal");
            } catch (IllegalStateException expected) { checks++; }
            try {
                service.importNcpPreset(HOST, PORT, GUID, NcpCompatibilityFixtures.ncp(), 4, -4, TARGET);
                throw new AssertionError("Expected main-thread import refusal");
            } catch (IllegalStateException expected) { checks++; }
            try {
                service.prepareImport(HOST, PORT, GUID);
                throw new AssertionError("Expected main-thread confirmation preparation refusal");
            } catch (IllegalStateException expected) { checks++; }
            try {
                service.importPreparedNcpPreset(NcpCompatibilityFixtures.ncp(), 4, -4, TARGET);
                throw new AssertionError("Expected main-thread prepared import refusal");
            } catch (IllegalStateException expected) { checks++; }
        } finally { android.os.Looper.setMainThreadForTest(false); }
        equal(0, camera.sockets.size());
    }

    private static UsbPictureControlImport.ImportException failed(Camera camera) throws Exception {
        return failed(camera, NcpCompatibilityFixtures.ncp(), 4, -4, TARGET);
    }

    private static UsbPictureControlImport.ImportException failed(Camera camera, byte[] input,
                                                                   int middle, int clarity, int target) throws Exception {
        try {
            new WirelessPictureControl(camera::connect).importNcpPreset(HOST, PORT, GUID, input, middle, clarity, target);
        } catch (UsbPictureControlImport.ImportException error) { checks++; return error; }
        throw new AssertionError("Expected a stopped wireless registration");
    }

    private static void assertOnlyGuardedCommands(Camera camera) {
        for (Command command : camera.commands) {
            check(command.operation == 0x1001 || command.operation == 0x1002 || command.operation == 0x1003
                            || command.operation == 0x90cc || command.operation == 0x90cd,
                    "Wireless NCP registration never deletes, selects presets, changes mode or writes files");
            if (command.operation == 0x90cc || command.operation == 0x90cd) {
                equal(2, command.parameters.length);
                equal(0L, command.parameters[1]);
                check(command.parameters[0] >= 201 && command.parameters[0] <= 209,
                        "Registration touches only custom photo-menu slots");
            }
        }
    }

    private static final class Camera {
        final List<FakeSocket> sockets = new ArrayList<>();
        final List<Command> commands = new ArrayList<>();
        final List<byte[]> uploads = new ArrayList<>();
        final Map<Integer, byte[]> slots = new HashMap<>();
        final Map<Integer, Integer> reads = new HashMap<>();
        final CountDownLatch blocked = new CountDownLatch(1);
        String model = "NIKON Z 6II";
        boolean advertiseAlbum;
        final byte[] albumThumbnail = {(byte) 0xff, (byte) 0xd8, 1, 2, 3, 4, (byte) 0xff, (byte) 0xd9};
        int missingOperation = -1, writeResponse = 0x2001, cleanupResponse = 0x2001, openResponse = 0x2001;
        String fault = "";
        boolean race;
        int heartbeatResponseCode;
        boolean heartbeatWrongTransaction;
        int raceAtTargetRead = 2;
        int replaceAtTargetRead = -1;
        byte[] replacementTarget;
        int connectionAttempts;
        boolean refuseAfterCloseSession, wirelessSessionEnded;
        Runnable onFirstConnect;
        Command uploadCommand;
        ByteArrayOutputStream upload;
        long declaredUpload;

        Camera() { for (int item = 201; item <= 209; item++) slots.put(item, new byte[36]); }

        Socket connect(String host, int port, int timeout) throws IOException {
            connectionAttempts++;
            equal(HOST, host);
            equal(PORT, port);
            check(timeout > 0, "Connection attempts have finite timeouts");
            if (refuseAfterCloseSession && wirelessSessionEnded) {
                throw new IOException("EHOSTUNREACH: synthetic camera Wi-Fi ended after CloseSession");
            }
            check(sockets.size() < 2, "Exactly two PTP/IP channels are created per operation");
            if (sockets.isEmpty() && onFirstConnect != null) onFirstConnect.run();
            FakeSocket socket = new FakeSocket(this, !sockets.isEmpty());
            sockets.add(socket);
            return socket;
        }

        void receive(FakeSocket socket, byte[] packet) {
            int type = (int) u32(packet, 4);
            byte[] payload = Arrays.copyOfRange(packet, 8, packet.length);
            if (type == 1) {
                equal(false, socket.event);
                socket.input.add(frame(2, concat(le32(42), new byte[16],
                        "Synthetic Nikon\0".getBytes(StandardCharsets.UTF_16LE), le32(0x00010000))));
                return;
            }
            if (type == 3) {
                equal(true, socket.event);
                equal(42L, u32(payload, 0));
                socket.input.add(frame(4, new byte[0]));
                return;
            }
            check(!socket.event, "Only the command socket receives camera operations");
            if (type == 6) {
                check(payload.length >= 10 && (payload.length - 10) % 4 == 0, "Operation framing has complete parameters");
                int phase = (int) u32(payload, 0), operation = u16(payload, 4);
                long transaction = u32(payload, 6);
                long[] parameters = new long[(payload.length - 10) / 4];
                for (int i = 0; i < parameters.length; i++) parameters[i] = u32(payload, 10 + 4 * i);
                Command command = new Command(operation, transaction, parameters);
                commands.add(command);
                if (operation == 0x90cd) {
                    equal(2, phase);
                    equal(2, parameters.length);
                    equal(0L, parameters[1]);
                    check(parameters[0] >= 201 && parameters[0] <= 209, "Synthetic registration targets only C1–C9");
                    uploadCommand = command;
                    return;
                }
                check(phase == 1 || phase == 3, "Read or no-data operations have no outgoing payload phase");
                respond(socket, command);
            } else if (type == 9) {
                check(uploadCommand != null, "StartData follows the single requested write");
                equal(uploadCommand.transaction, u32(payload, 0));
                equal(12, payload.length);
                declaredUpload = u32(payload, 4) | u32(payload, 8) << 32;
                upload = new ByteArrayOutputStream();
            } else if (type == 10 || type == 12) {
                check(upload != null, "Data follows a declared outgoing length");
                equal(uploadCommand.transaction, u32(payload, 0));
                upload.write(payload, 4, payload.length - 4);
                if (type == 12) {
                    byte[] bytes = upload.toByteArray();
                    equal((long) bytes.length, declaredUpload);
                    uploads.add(bytes);
                    slots.put((int) uploadCommand.parameters[0], bytes.clone());
                    if (fault.equals("lost_write")) return;
                    if (fault.equals("blocked_write")) {
                        socket.input.holdOpen = true;
                        blocked.countDown();
                        return;
                    }
                    socket.input.add(response(writeResponse, uploadCommand.transaction
                            + (fault.equals("wrong_write_transaction") ? 1 : 0)));
                }
            } else throw new AssertionError("Unexpected outgoing packet type " + type);
        }

        void respond(FakeSocket socket, Command command) {
            int operation = command.operation;
            if (operation == 0x1002 || operation == 0x1003) {
                if (operation == 0x1003) wirelessSessionEnded = true;
                socket.input.add(response(operation == 0x1002 ? openResponse : cleanupResponse, command.transaction));
                return;
            }
            byte[] bytes;
            if (operation == 0x1001) bytes = deviceInfo(model, missingOperation, advertiseAlbum);
            else if (operation == 0x1004) bytes = le32(0);
            else if (operation == 0x1007) bytes = concat(le32(1), le32(42));
            else if (operation == 0x100a || operation == 0x90c4) bytes = albumThumbnail.clone();
            else if (operation == 0x90ca) bytes = le32(0);
            else if (operation == 0x90cf) {
                if (fault.equals("lost_capability")) return;
                bytes = new byte[fault.equals("malformed_capability") ? 29 : 28];
            }
            else if (operation == 0x90cc) {
                int item = (int) command.parameters[0];
                int count = reads.getOrDefault(item, 0) + 1;
                reads.put(item, count);
                if (race && item == TARGET && count == raceAtTargetRead) slots.put(item, NcpCompatibilityFixtures.expectedData(0, 0));
                if (item == TARGET && count == replaceAtTargetRead) slots.put(item, replacementTarget.clone());
                if (item == TARGET && count == 3 && fault.equals("cancel_after_overwrite_recheck")) socket.input.interruptWhenEmpty = true;
                bytes = item == 1 ? standard() : slots.get(item).clone();
                if (item == 1 && heartbeatResponseCode != 0) {
                    socket.input.add(response(heartbeatResponseCode, command.transaction + (heartbeatWrongTransaction ? 1 : 0)));
                    return;
                }
                if (item == 1 && (fault.equals("busy_heartbeat") || fault.equals("wrong_busy_heartbeat_transaction"))) {
                    socket.input.add(response(0x2019, command.transaction + (fault.equals("busy_heartbeat") ? 0 : 1)));
                    return;
                }
                if (item == 1 && fault.equals("lost_heartbeat")) return;
                if (item == 1 && fault.equals("malformed_heartbeat")) bytes = new byte[37];
                if (item == 1 && fault.equals("wrong_heartbeat_transaction")) {
                    transfer(socket.input, command.transaction + 1, bytes);
                    return;
                }
                if (fault.equals("malformed_scan") && item == 203) bytes = new byte[37];
                if (fault.equals("wrong_scan_transaction") && item == 201) {
                    transfer(socket.input, command.transaction + 1, bytes);
                    return;
                }
                if (uploadCommand != null && item == uploadCommand.parameters[0]
                        && command.transaction == uploadCommand.transaction + 1 && !uploads.isEmpty()) {
                    if (fault.equals("lost_readback")) return;
                    if (fault.equals("wrong_readback_transaction")) {
                        transfer(socket.input, command.transaction + 1, bytes);
                        return;
                    }
                    if (fault.equals("changed_readback")) bytes[2] = 'X';
                    if (fault.equals("cancel_after_readback")) socket.input.interruptWhenEmpty = true;
                }
            } else throw new AssertionError("Unexpected operation " + operation);
            transfer(socket.input, command.transaction, bytes);
        }

        long count(int operation) { return commands.stream().filter(command -> command.operation == operation).count(); }
        void disconnectPeer() { for (FakeSocket socket : sockets) socket.close(); }
        void assertOpen() {
            equal(2, sockets.size());
            for (FakeSocket socket : sockets) check(!socket.closed, "The prepared command and event channels remain open");
        }
        void assertClosed() {
            equal(2, sockets.size());
            for (FakeSocket socket : sockets) check(socket.closed, "Every created wireless channel is released");
        }
    }

    private static final class FakeSocket extends Socket {
        final Camera camera;
        final boolean event;
        final QueuedInput input;
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        volatile boolean closed;
        FakeSocket(Camera camera, boolean event) { this.camera = camera; this.event = event; input = new QueuedInput(event); }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
                @Override public void write(byte[] bytes, int start, int count) throws IOException {
                    if (closed) throw new IOException("Synthetic socket closed");
                    pending.write(bytes, start, count);
                    while (pending.size() >= 8) {
                        byte[] buffered = pending.toByteArray();
                        long length = u32(buffered, 0);
                        if (length < 8 || length > 65536) throw new IOException("Invalid client packet length");
                        if (buffered.length < length) return;
                        pending.reset();
                        pending.write(buffered, (int) length, buffered.length - (int) length);
                        camera.receive(FakeSocket.this, Arrays.copyOf(buffered, (int) length));
                    }
                }
            };
        }
        @Override public void setSoTimeout(int timeout) { }
        @Override public void setTcpNoDelay(boolean value) { }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closed = true; input.close(); }
    }

    private static final class QueuedInput extends InputStream {
        final Deque<byte[]> chunks = new ArrayDeque<>();
        volatile boolean holdOpen;
        boolean closed, interruptWhenEmpty;
        int offset;
        QueuedInput(boolean holdOpen) { this.holdOpen = holdOpen; }
        synchronized void add(byte[] bytes) { if (bytes.length != 0) { chunks.add(bytes); notifyAll(); } }
        private boolean hasData() throws IOException {
            while (chunks.isEmpty() && holdOpen && !closed) {
                try { wait(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("Interrupted test read", error); }
            }
            return !closed && !chunks.isEmpty();
        }
        @Override public synchronized int read() throws IOException {
            byte[] bytes = new byte[1];
            return read(bytes, 0, 1) < 0 ? -1 : bytes[0] & 255;
        }
        @Override public synchronized int read(byte[] target, int start, int length) throws IOException {
            if (length == 0) return 0;
            if (!hasData()) return -1;
            int count = Math.min(3, Math.min(length, chunks.peek().length - offset));
            System.arraycopy(chunks.peek(), offset, target, start, count);
            offset += count;
            if (offset == chunks.peek().length) {
                chunks.remove(); offset = 0;
                if (chunks.isEmpty() && interruptWhenEmpty) Thread.currentThread().interrupt();
            }
            return count;
        }
        @Override public synchronized void close() { closed = true; notifyAll(); }
    }

    private static byte[] standard() { byte[] bytes = new byte[36]; bytes[0] = 1; return bytes; }
    private static byte[] deviceInfo(String model, int missing, boolean album) {
        int[] supported = album ? new int[] {0x1001, 0x1002, 0x1003, 0x90ca, 0x90cc, 0x90cd, 0x90cf, 0x1004, 0x1007, 0x1008, 0x1009, 0x100a, 0x90c4}
                : new int[] {0x1001, 0x1002, 0x1003, 0x90ca, 0x90cc, 0x90cd, 0x90cf};
        Writer writer = new Writer().u16(100).u32(10).u16(100).string("").u16(0);
        int count = 0;
        for (int operation : supported) if (operation != missing) count++;
        writer.u32(count);
        for (int operation : supported) if (operation != missing) writer.u16(operation);
        return writer.u32(0).u32(0).u32(0).u32(0).string("Nikon").string(model)
                .string("1.62").string("serial-not-collected").bytes();
    }
    private static final class Writer {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        Writer u16(long value) { output.write((int) value); output.write((int) (value >>> 8)); return this; }
        Writer u32(long value) { for (int i = 0; i < 4; i++) output.write((int) (value >>> (8 * i))); return this; }
        Writer string(String value) {
            output.write(value.length() + 1);
            byte[] bytes = value.getBytes(StandardCharsets.UTF_16LE);
            output.write(bytes, 0, bytes.length);
            return u16(0);
        }
        byte[] bytes() { return output.toByteArray(); }
    }
    private static final class Command {
        final int operation;
        final long transaction;
        final long[] parameters;
        Command(int operation, long transaction, long[] parameters) {
            this.operation = operation; this.transaction = transaction; this.parameters = parameters.clone();
        }
    }
    private static void transfer(QueuedInput input, long transaction, byte[] bytes) {
        input.add(frame(9, concat(le32(transaction), le32(bytes.length), le32(0))));
        input.add(frame(12, concat(le32(transaction), bytes)));
        input.add(response(0x2001, transaction));
    }
    private static byte[] response(int code, long transaction) { return frame(7, concat(le16(code), le32(transaction))); }
    private static byte[] frame(int type, byte[] payload) { return concat(le32(8 + payload.length), le32(type), payload); }
    private static byte[] le16(long value) { return new byte[] {(byte) value, (byte) (value >>> 8)}; }
    private static byte[] le32(long value) {
        return new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }
    private static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 255L) | ((bytes[offset + 1] & 255L) << 8)
                | ((bytes[offset + 2] & 255L) << 16) | ((bytes[offset + 3] & 255L) << 24);
    }
    private static int u16(byte[] bytes, int offset) { return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8); }
    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] bytes = new byte[length];
        int offset = 0;
        for (byte[] part : parts) { System.arraycopy(part, 0, bytes, offset, part.length); offset += part.length; }
        return bytes;
    }
    private static void command(Command actual, int operation, long transaction, long... parameters) {
        equal(operation, actual.operation); equal(transaction, actual.transaction); equal(parameters, actual.parameters);
    }
    private static void equal(Object expected, Object actual) {
        boolean same = expected instanceof byte[] && actual instanceof byte[]
                ? Arrays.equals((byte[]) expected, (byte[]) actual)
                : expected instanceof long[] && actual instanceof long[]
                ? Arrays.equals((long[]) expected, (long[]) actual) : java.util.Objects.equals(expected, actual);
        check(same, "Expected " + printable(expected) + ", got " + printable(actual));
    }
    private static String printable(Object value) {
        if (value instanceof byte[]) return Arrays.toString((byte[]) value);
        if (value instanceof long[]) return Arrays.toString((long[]) value);
        return String.valueOf(value);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

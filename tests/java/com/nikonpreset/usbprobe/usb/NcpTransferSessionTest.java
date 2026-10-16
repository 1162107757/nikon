package com.nikonpreset.usbprobe.usb;

import com.nikonpreset.usbprobe.ncp.NcpFileTest;
import com.nikonpreset.usbprobe.ptp.PtpContainer;
import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import com.nikonpreset.usbprobe.ptp.PtpResponseException;
import com.nikonpreset.usbprobe.ptp.PtpValidation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes the production transfer plan against a stateful standard-PTP camera simulator. */
public final class NcpTransferSessionTest {
    private static final long STORAGE = 0x10001;
    private static final long SECOND_STORAGE = 0x20001;
    private static final long ROOT = 0xffffffffL;
    private static final int[] REQUIRED = {0x1002, 0x1003, 0x1004, 0x1005,
            0x1007, 0x1008, 0x1009, 0x100c, 0x100d};
    private static final byte[] NCP = NcpFileTest.syntheticEnvelope("0100");
    private static int checks;

    public static void main(String[] args) throws Exception {
        freshDirectoryUpload();
        existingFileIsPreserved();
        unsupportedCamerasAndOperationsDoNotWrite();
        fileAndStoragePreconditionsDoNotWrite();
        collisionsAndExhaustedSlotsDoNotWriteFiles();
        unexpectedStorageStops();
        readbackMismatchIsNotVerified();
        borrowedSessionIsNotClosed();
        raceAndWrongDirectoryStop();
        reusedChildHandleNeverReceivesContent();
        unavailableFirstSlotContinuesToValidCard();
        unavailableSlotsNeverWrite();
        otherSlotErrorsAreNotSwallowed();
        unavailableResponseMustBelongToStorageTransaction();
        unavailableOutsideStorageInfoNeverFallsBack();
        physicalOnlySlotIsNeverQueried();
        writableDcfStorageIsAccepted();
        localStorageRejectionsKeepRawEvidenceAndStayReadOnly();
        readOnlyExistingDirectoryExperimentRequiresExplicitChoice();
        experimentMetadataRejectionPreservesActualPtpContext();
        experimentContentRejectionRetainsAcceptedMetadata();
        experimentMissingInvisibleAndConflictingDirectoriesNeverWrite();
        experimentNeverCreatesDirectoriesOnWritableCards();
        experimentOtherEligibilityGuardsRemainEnforced();
        experimentCardSelectionMustBeUnambiguousAndPreferWritable();
        experimentPreservesOccupiedFilesAndRejectsExhaustedNames();
        System.out.println("PASS: " + checks + " simulated NCP transfer checks");
    }

    private static void freshDirectoryUpload() throws Exception {
        Camera camera = new Camera();
        UsbNcpTransfer.Result result = result();
        NcpTransferSession session = new NcpTransferSession(camera, result);
        session.run(NCP);
        equal(2, camera.directoryMetadataWrites);
        equal(1, camera.fileMetadataWrites);
        equal(1, camera.contentWrites);
        equal("PICCON01.NCP", result.fileName);
        check(result.verifiedTransfer && result.readbackVerified, "Success requires path and byte verification");
        check(!result.menuImportVerified, "Card transfer does not claim menu import");
        equal(result.sha256, result.readbackSha256);
        equal(NCP, camera.contents.get(result.fileHandle));
        check(session.ownsSession() && result.sessionOpened, "Probe owns the opened session");
        session.closeSession();
        check(!session.ownsSession() && result.sessionClosed, "Owned session is closed once");
        session.closeSession();
        equal(1L, camera.countCommands(0x1003));
        check(camera.operations.stream().allMatch(operation -> operation >= 0x1001 && operation <= 0x100d),
                "No vendor writes are sent");
    }

    private static void existingFileIsPreserved() throws Exception {
        Camera camera = Camera.existingDirectories();
        long parent = camera.customPc;
        byte[] original = {7, 8, 9};
        long oldFile = camera.add(PtpObjectInfo.file(STORAGE, parent, "piccon01.ncp", original.length), original);
        UsbNcpTransfer.Result result = result();
        NcpTransferSession session = new NcpTransferSession(camera, result);
        session.run(NCP);
        equal("PICCON02.NCP", result.fileName);
        equal(original, camera.contents.get(oldFile));
        equal(0, camera.directoryMetadataWrites);
        equal(1, camera.fileMetadataWrites);
        check(result.verifiedTransfer, "Alternate free slot is verified");
        session.closeSession();
    }

    private static void unsupportedCamerasAndOperationsDoNotWrite() throws Exception {
        for (String model : new String[] {"Z 7II", "Z 6", "D850", "unknown"}) {
            Camera camera = new Camera();
            camera.model = model;
            UsbNcpTransfer.Result result = result();
            NcpTransferSession session = new NcpTransferSession(camera, result);
            rejects(() -> session.run(NCP));
            equal(0, camera.totalWrites());
            equal(0L, camera.countCommands(0x1002));
            check(!session.ownsSession(), "Unrecognized model never owns a session");
        }
        for (int missing : REQUIRED) {
            Camera camera = new Camera();
            camera.missingOperation = missing;
            NcpTransferSession session = new NcpTransferSession(camera, result());
            rejects(() -> session.run(NCP));
            equal(0, camera.totalWrites());
            equal(0L, camera.countCommands(0x1002));
        }
        check(NcpTransferSession.isZ6II("NIKON Z 6II"), "Official spaced model is accepted");
        check(NcpTransferSession.isZ6II("Z6II"), "Compact Z6II model is accepted");
    }

    private static void fileAndStoragePreconditionsDoNotWrite() throws Exception {
        for (byte[] invalid : new byte[][] {null, new byte[0], new byte[UsbNcpTransfer.MAX_NCP_BYTES + 1]}) {
            Camera camera = new Camera();
            NcpTransferSession session = new NcpTransferSession(camera, result());
            rejects(() -> session.run(invalid));
            equal(0, camera.operations.size());
            equal(0, camera.totalWrites());
        }
        for (int access : new int[] {1, 2, 99}) {
            Camera camera = new Camera();
            camera.accessCapability = access;
            NcpTransferSession session = new NcpTransferSession(camera, result());
            rejects(() -> session.run(NCP));
            equal(0, camera.totalWrites());
            session.closeSession();
        }
        Camera full = new Camera();
        full.freeBytes = NCP.length;
        NcpTransferSession session = new NcpTransferSession(full, result());
        rejects(() -> session.run(NCP));
        equal(0, full.totalWrites());
        session.closeSession();
    }

    private static void collisionsAndExhaustedSlotsDoNotWriteFiles() throws Exception {
        for (String conflictingName : new String[] {"NIKON", "nikon"}) {
            Camera camera = new Camera();
            camera.add(PtpObjectInfo.file(STORAGE, 0, conflictingName, 1), new byte[] {1});
            NcpTransferSession session = new NcpTransferSession(camera, result());
            rejects(() -> session.run(NCP));
            equal(0, camera.totalWrites());
            session.closeSession();
        }
        Camera wrongCustomPc = new Camera();
        long nikon = wrongCustomPc.add(PtpObjectInfo.folder(STORAGE, 0, "NIKON"), null);
        wrongCustomPc.add(PtpObjectInfo.file(STORAGE, nikon, "CUSTOMPC", 1), new byte[] {1});
        NcpTransferSession collision = new NcpTransferSession(wrongCustomPc, result());
        rejects(() -> collision.run(NCP));
        equal(0, wrongCustomPc.fileMetadataWrites);
        equal(0, wrongCustomPc.contentWrites);
        collision.closeSession();

        Camera exhausted = Camera.existingDirectories();
        for (int i = 1; i <= 99; i++) exhausted.add(PtpObjectInfo.file(STORAGE, exhausted.customPc,
                String.format(java.util.Locale.ROOT, "PICCON%02d.NCP", i), 1), new byte[] {1});
        NcpTransferSession occupied = new NcpTransferSession(exhausted, result());
        rejects(() -> occupied.run(NCP));
        equal(0, exhausted.totalWrites());
        occupied.closeSession();
    }

    private static void unexpectedStorageStops() throws Exception {
        Camera listedOnWrongStorage = new Camera();
        listedOnWrongStorage.badListedStorage = true;
        listedOnWrongStorage.add(PtpObjectInfo.folder(STORAGE + 1, 0, "NIKON"), null);
        NcpTransferSession listCheck = new NcpTransferSession(listedOnWrongStorage, result());
        rejects(() -> listCheck.run(NCP));
        equal(0, listedOnWrongStorage.totalWrites());
        listCheck.closeSession();

        Camera reservationWrongStorage = Camera.existingDirectories();
        reservationWrongStorage.badReservationStorage = true;
        UsbNcpTransfer.Result result = result();
        NcpTransferSession reservation = new NcpTransferSession(reservationWrongStorage, result);
        rejects(() -> reservation.run(NCP));
        equal(1, reservationWrongStorage.fileMetadataWrites);
        equal(0, reservationWrongStorage.contentWrites);
        check(result.fileMetadataAccepted && !result.uploadStarted && !result.verifiedTransfer,
                "Metadata acceptance is retained without claiming content upload");
        reservation.closeSession();
    }

    private static void readbackMismatchIsNotVerified() throws Exception {
        Camera camera = Camera.existingDirectories();
        camera.corruptReadback = true;
        UsbNcpTransfer.Result result = result();
        NcpTransferSession session = new NcpTransferSession(camera, result);
        rejects(() -> session.run(NCP));
        check(result.uploadResponseOk && result.uploadStarted, "Failure reports content was accepted");
        check(!result.readbackVerified && !result.verifiedTransfer, "Corruption never passes verification");
        check(!result.sha256.equals(result.readbackSha256), "Read-back hash exposes mismatch");
        equal(1, camera.contentWrites);
        equal(NCP, camera.contents.get(result.fileHandle));
        session.closeSession();
    }

    private static void borrowedSessionIsNotClosed() throws Exception {
        Camera camera = new Camera();
        camera.alreadyOpen = true;
        UsbNcpTransfer.Result result = result();
        NcpTransferSession session = new NcpTransferSession(camera, result);
        try {
            session.run(NCP);
            throw new AssertionError("Expected SessionAlreadyOpen");
        } catch (PtpResponseException error) {
            equal(0x201e, error.responseCode());
        }
        check(!session.ownsSession() && !result.sessionOpened, "Foreign session is never owned");
        session.closeSession();
        equal(0L, camera.countCommands(0x1003));
        equal(0, camera.totalWrites());
    }

    private static void raceAndWrongDirectoryStop() throws Exception {
        Camera race = Camera.existingDirectories();
        race.raceFilename = true;
        NcpTransferSession raceSession = new NcpTransferSession(race, result());
        rejects(() -> raceSession.run(NCP));
        equal(0, race.totalWrites());
        raceSession.closeSession();

        Camera wrongDirectory = Camera.existingDirectories();
        wrongDirectory.badWrittenParent = true;
        UsbNcpTransfer.Result result = result();
        NcpTransferSession misplaced = new NcpTransferSession(wrongDirectory, result);
        rejects(() -> misplaced.run(NCP));
        equal(1, wrongDirectory.contentWrites);
        check(result.uploadResponseOk && !result.verifiedTransfer, "A misplaced object never passes path verification");
        misplaced.closeSession();
    }

    private static void reusedChildHandleNeverReceivesContent() throws Exception {
        Camera camera = Camera.existingDirectories();
        long existing = camera.add(PtpObjectInfo.folder(STORAGE, camera.customPc, "KEEP"), null);
        byte[] originalMetadata = camera.objects.get(existing).toBytes();
        camera.reusedReservationHandle = existing;
        UsbNcpTransfer.Result result = result();
        NcpTransferSession session = new NcpTransferSession(camera, result);
        rejects(() -> session.run(NCP));
        equal(1, camera.fileMetadataWrites);
        equal(0, camera.contentWrites);
        equal(originalMetadata, camera.objects.get(existing).toBytes());
        check(result.fileMetadataAccepted && !result.uploadStarted && !result.verifiedTransfer,
                "A returned pre-existing child handle never receives file content");
        session.closeSession();
    }

    private static void unavailableFirstSlotContinuesToValidCard() throws Exception {
        Camera camera = new Camera();
        camera.storageIds = new long[] {SECOND_STORAGE, STORAGE};
        camera.storageErrors.put(SECOND_STORAGE, 0x2013);
        UsbNcpTransfer.Result result = result();
        NcpTransferSession session = new NcpTransferSession(camera, result);
        session.run(NCP);
        equal(STORAGE, result.storageId);
        check(result.verifiedTransfer, "An unavailable first slot cannot hide a working SD card");
        equal(2, result.storageCount);
        equal(1, result.availableStorageCount);
        equal(1, result.unavailableStorageCount);
        equal(List.of(SECOND_STORAGE), result.unavailableStorageIds);
        List<Call> slots = camera.callsFor("read", 0x1005);
        equal(2, slots.size());
        assertCall(slots.get(0), "read", 0x1005, 2, SECOND_STORAGE);
        assertCall(slots.get(1), "read", 0x1005, 3, STORAGE);
        equal(0, slots.get(0).writesBefore);
        equal(0, slots.get(1).writesBefore);
        int secondSlotIndex = camera.calls.indexOf(slots.get(1));
        for (Call call : camera.calls) {
            if (call.kind.equals("send")) {
                check(camera.calls.indexOf(call) > secondSlotIndex,
                        "No object write precedes successful storage selection");
                if (call.operation == 0x100c) equal(STORAGE, call.parameters[0]);
            }
            if (call.operation == 0x1007) equal(STORAGE, call.parameters[0]);
        }
        String log = String.join("\n", result.diagnostics).toUpperCase(java.util.Locale.ROOT);
        check(log.contains("0X00020001") && log.contains("0X2013"),
                "The unavailable slot and response code remain visible in diagnostics");
        check(log.contains("GETSTORAGEINFO (0X1005)") && log.contains("TRANSACTION=2")
                        && log.contains("STORAGEID=0X00020001"),
                "Recoverable slot diagnostics preserve the exact operation and transaction");
        equal(2, camera.directoryMetadataWrites);
        equal(1, camera.fileMetadataWrites);
        equal(1, camera.contentWrites);
        session.closeSession();
        equal(1L, camera.countCommands(0x1003));
    }

    private static void unavailableSlotsNeverWrite() throws Exception {
        Camera camera = new Camera();
        camera.storageIds = new long[] {SECOND_STORAGE, STORAGE};
        camera.storageErrors.put(SECOND_STORAGE, 0x2013);
        camera.storageErrors.put(STORAGE, 0x2013);
        UsbNcpTransfer.Result result = result();
        NcpTransferSession session = new NcpTransferSession(camera, result);
        rejects(() -> session.run(NCP));
        equal(2, result.storageCount);
        equal(0, result.availableStorageCount);
        equal(2, result.unavailableStorageCount);
        equal(List.of(SECOND_STORAGE, STORAGE), result.unavailableStorageIds);
        equal(0L, result.storageId);
        equal(0, camera.totalWrites());
        equal(0, camera.callsFor("send", 0x100c).size());
        equal(0, camera.callsFor("read", 0x1007).size());
        List<Call> slots = camera.callsFor("read", 0x1005);
        equal(2, slots.size());
        assertCall(slots.get(0), "read", 0x1005, 2, SECOND_STORAGE);
        assertCall(slots.get(1), "read", 0x1005, 3, STORAGE);
        check(!result.writeAttempted && !result.verifiedTransfer, "No usable slot implies no writes");
        session.closeSession();
        List<Call> closes = camera.callsFor("command", 0x1003);
        equal(1, closes.size());
        assertCall(closes.get(0), "command", 0x1003, 4);
    }

    private static void otherSlotErrorsAreNotSwallowed() throws Exception {
        for (int response : new int[] {0x2008, 0x2019}) {
            Camera firstFails = new Camera();
            firstFails.storageIds = new long[] {SECOND_STORAGE, STORAGE};
            firstFails.storageErrors.put(SECOND_STORAGE, response);
            UsbNcpTransfer.Result result = result();
            NcpTransferSession session = new NcpTransferSession(firstFails, result);
            expectResponse(response, 2, () -> session.run(NCP));
            equal(1, firstFails.callsFor("read", 0x1005).size());
            assertCall(firstFails.callsFor("read", 0x1005).get(0), "read", 0x1005, 2, SECOND_STORAGE);
            equal(0, result.unavailableStorageCount);
            equal(0, firstFails.totalWrites());
            equal(0, firstFails.callsFor("send", 0x100c).size());
            equal("GetStorageInfo (0x1005)", result.lastOperation);
            equal(2L, result.lastTransactionId);
            equal("0x00020001", result.lastStorageId);
            equal("GetStorageInfo (0x1005)", result.failedOperation);
            equal("PtpOperation", result.failurePhase);
            equal(2L, result.failedTransactionId);
            equal("0x00020001", result.failedStorageId);
            equal("GetStorageIDs (0x1004)", result.lastPtpOperation);
            equal(1L, result.lastPtpTransactionId);
            session.closeSession();
            equal("GetStorageInfo (0x1005)", result.lastOperation);
            equal(2L, result.lastTransactionId);
            equal("0x00020001", result.lastStorageId);

            // A previous successful candidate cannot turn a later unrelated error into success.
            Camera secondFails = new Camera();
            secondFails.storageIds = new long[] {STORAGE, SECOND_STORAGE};
            secondFails.storageErrors.put(SECOND_STORAGE, response);
            UsbNcpTransfer.Result secondResult = result();
            NcpTransferSession secondSession = new NcpTransferSession(secondFails, secondResult);
            expectResponse(response, 3, () -> secondSession.run(NCP));
            equal(2, secondFails.callsFor("read", 0x1005).size());
            assertCall(secondFails.callsFor("read", 0x1005).get(1), "read", 0x1005, 3, SECOND_STORAGE);
            equal(0, secondFails.totalWrites());
            equal(0, secondFails.callsFor("read", 0x1007).size());
            check(!secondResult.verifiedTransfer, "A later invalid/busy slot error stops before writing");
            secondSession.closeSession();
        }
    }

    private static void unavailableResponseMustBelongToStorageTransaction() throws Exception {
        Camera camera = new Camera();
        camera.storageIds = new long[] {SECOND_STORAGE, STORAGE};
        camera.storageErrors.put(SECOND_STORAGE, 0x2013);
        camera.storageErrorTransactionDelta = 99;
        NcpTransferSession session = new NcpTransferSession(camera, result());
        expectResponse(0x2013, 101, () -> session.run(NCP));
        equal(1, camera.callsFor("read", 0x1005).size());
        equal(0, camera.totalWrites());
        session.closeSession();
    }

    private static void unavailableOutsideStorageInfoNeverFallsBack() throws Exception {
        Camera openFails = new Camera();
        openFails.commandFailureOperation = 0x1002;
        NcpTransferSession unopened = new NcpTransferSession(openFails, result());
        expectResponse(0x2013, 0, () -> unopened.run(NCP));
        check(!unopened.ownsSession(), "Rejected OpenSession is never owned");
        unopened.closeSession();
        equal(0, openFails.callsFor("read", 0x1005).size());
        equal(0L, openFails.countCommands(0x1003));
        equal(0, openFails.totalWrites());

        Camera idsFail = new Camera();
        idsFail.readFailureOperation = 0x1004;
        NcpTransferSession noIds = new NcpTransferSession(idsFail, result());
        expectResponse(0x2013, 1, () -> noIds.run(NCP));
        equal(0, idsFail.callsFor("read", 0x1005).size());
        equal(0, idsFail.totalWrites());
        noIds.closeSession();

        Camera directoryReadFails = Camera.existingDirectories();
        directoryReadFails.storageIds = new long[] {STORAGE, SECOND_STORAGE};
        directoryReadFails.readFailureOperation = 0x1007;
        NcpTransferSession noDirectory = new NcpTransferSession(directoryReadFails, result());
        expectResponse(0x2013, 4, () -> noDirectory.run(NCP));
        equal(2, directoryReadFails.callsFor("read", 0x1005).size());
        equal(1, directoryReadFails.callsFor("read", 0x1007).size());
        assertCall(directoryReadFails.callsFor("read", 0x1007).get(0), "read", 0x1007, 4,
                STORAGE, 0, ROOT);
        equal(0, directoryReadFails.totalWrites());
        noDirectory.closeSession();

        for (int operation : new int[] {0x100c, 0x100d}) {
            Camera uploadFails = Camera.existingDirectories();
            uploadFails.storageIds = new long[] {STORAGE, SECOND_STORAGE};
            uploadFails.sendFailureOperation = operation;
            UsbNcpTransfer.Result result = result();
            NcpTransferSession upload = new NcpTransferSession(uploadFails, result);
            try {
                upload.run(NCP);
                throw new AssertionError("Expected upload StoreNotAvailable");
            } catch (PtpResponseException error) {
                equal(0x2013, error.responseCode());
                List<Call> failedWrites = uploadFails.callsFor("send", operation);
                equal(1, failedWrites.size());
                equal(failedWrites.get(0).transactionId, error.transactionId());
            }
            equal(2, uploadFails.callsFor("read", 0x1005).size());
            equal(1, uploadFails.callsFor("send", 0x100c).size());
            equal(operation == 0x100d ? 1 : 0, uploadFails.callsFor("send", 0x100d).size());
            for (Call call : uploadFails.callsFor("read", 0x1007)) equal(STORAGE, call.parameters[0]);
            check(result.writeAttempted && !result.verifiedTransfer, "Upload failure stays a partial write");
            if (operation == 0x100d) {
                check(result.fileMetadataAccepted && result.uploadStarted && !result.uploadResponseOk,
                        "Content failure preserves accepted metadata without claiming success");
            }
            upload.closeSession();
            equal(1L, uploadFails.countCommands(0x1003));
        }
    }

    private static void physicalOnlySlotIsNeverQueried() throws Exception {
        Camera camera = new Camera();
        camera.storageIds = new long[] {0x00010000, SECOND_STORAGE};
        camera.activeStorage = SECOND_STORAGE;
        // If code mistakenly queries this physical-only ID, the injected error must fail the test.
        camera.storageErrors.put(0x00010000L, 0x2008);
        UsbNcpTransfer.Result result = result();
        NcpTransferSession session = new NcpTransferSession(camera, result);
        session.run(NCP);
        equal(SECOND_STORAGE, result.storageId);
        equal(2, result.storageCount);
        equal(1, result.availableStorageCount);
        equal(1, result.unavailableStorageCount);
        equal(List.of(0x00010000L), result.unavailableStorageIds);
        equal(List.of(0x00010000L), result.unmountedStorageIds);
        List<Call> slots = camera.callsFor("read", 0x1005);
        equal(1, slots.size());
        assertCall(slots.get(0), "read", 0x1005, 2, SECOND_STORAGE);
        equal(0, slots.get(0).writesBefore);
        check(result.verifiedTransfer, "A physical-only empty slot does not block a valid second card");
        for (Call call : camera.callsFor("read", 0x1007)) equal(SECOND_STORAGE, call.parameters[0]);
        for (Call call : camera.callsFor("send", 0x100c)) equal(SECOND_STORAGE, call.parameters[0]);
        String log = String.join("\n", result.diagnostics).toUpperCase(java.util.Locale.ROOT);
        check(log.contains("0X00010000"), "Unmounted slot remains identified in diagnostics");
        check(!log.contains("0X2013"), "Skipping an unmounted ID never invents a response code");
        session.closeSession();

        Camera noneAvailable = new Camera();
        noneAvailable.storageIds = new long[] {0x00010000, STORAGE};
        noneAvailable.storageErrors.put(STORAGE, 0x2013);
        UsbNcpTransfer.Result failedResult = result();
        NcpTransferSession noCard = new NcpTransferSession(noneAvailable, failedResult);
        rejects(() -> noCard.run(NCP));
        equal(2, failedResult.unavailableStorageCount);
        equal(0, failedResult.availableStorageCount);
        equal(List.of(0x00010000L), failedResult.unmountedStorageIds);
        equal(List.of(0x00010000L, STORAGE), failedResult.unavailableStorageIds);
        equal(1, noneAvailable.callsFor("read", 0x1005).size());
        assertCall(noneAvailable.callsFor("read", 0x1005).get(0), "read", 0x1005, 2, STORAGE);
        equal(0, noneAvailable.totalWrites());
        equal(0, noneAvailable.callsFor("send", 0x100c).size());
        noCard.closeSession();
        assertCall(noneAvailable.callsFor("command", 0x1003).get(0), "command", 0x1003, 3);
    }

    private static void writableDcfStorageIsAccepted() throws Exception {
        Camera camera = new Camera();
        camera.fileSystemType = 3;
        UsbNcpTransfer.Result result = result();
        NcpTransferSession session = new NcpTransferSession(camera, result);
        session.run(NCP);
        check(result.verifiedTransfer, "DCF is a directory-capable filesystem for the normal guarded upload");
        equal(STORAGE, result.storageId);
        equal(1, result.storageAssessment.size());
        StorageAssessment assessment = result.storageAssessment.get(0);
        assertAssessmentRawFields(camera, assessment);
        check(assessment.eligible && assessment.rejectionReasons.isEmpty(), "Writable DCF card is eligible");
        equal(2, camera.directoryMetadataWrites);
        equal(1, camera.fileMetadataWrites);
        equal(1, camera.contentWrites);
        equal(NCP, camera.contents.get(result.fileHandle));
        check(result.failedOperation.isEmpty() && result.failurePhase.isEmpty(), "Success has no claimed PTP or local failure");
        session.closeSession();
    }

    private static void localStorageRejectionsKeepRawEvidenceAndStayReadOnly() throws Exception {
        for (int access : new int[] {1, 2, 99}) {
            Camera camera = new Camera();
            camera.fileSystemType = 3;
            camera.accessCapability = access;
            assertLocalStorageRejection(camera, access == 99 ? "未知" : "只读");
        }
        Camera flat = new Camera();
        flat.fileSystemType = 1;
        assertLocalStorageRejection(flat, "平面");
        for (int fileSystem : new int[] {0, 99}) {
            Camera unknown = new Camera();
            unknown.fileSystemType = fileSystem;
            assertLocalStorageRejection(unknown, "未知");
        }
        Camera unsupportedType = new Camera();
        unsupportedType.fileSystemType = 3;
        unsupportedType.storageType = 2;
        assertLocalStorageRejection(unsupportedType, "");
        Camera unknownType = new Camera();
        unknownType.fileSystemType = 3;
        unknownType.storageType = 99;
        assertLocalStorageRejection(unknownType, "未知");
        Camera insufficientSpace = new Camera();
        insufficientSpace.fileSystemType = 3;
        insufficientSpace.freeBytes = NCP.length + 8191;
        assertLocalStorageRejection(insufficientSpace, "空间");
    }

    private static void assertLocalStorageRejection(Camera camera, String reasonWord) throws Exception {
        UsbNcpTransfer.Result result = result();
        NcpTransferSession session = new NcpTransferSession(camera, result);
        try {
            session.run(NCP);
            throw new AssertionError("Expected local storage eligibility rejection");
        } catch (IOException failure) {
            check(!(failure instanceof PtpResponseException), "Local storage policy is not a failed PTP response");
            if (camera.accessCapability == 1 || camera.accessCapability == 2) {
                check(failure.getMessage().contains("只读"), "Read-only refusal is explicit in the top-level message");
            }
        }
        equal("StorageEligibilityCheck", result.phase);
        equal("StorageEligibilityCheck", result.failurePhase);
        equal("", result.failedOperation);
        equal(-1L, result.failedTransactionId);
        equal("", result.failedStorageId);
        equal("GetStorageInfo (0x1005)", result.lastPtpOperation);
        equal(2L, result.lastPtpTransactionId);
        equal("0x00010001", result.lastPtpStorageId);
        equal(1, result.availableStorageCount);
        equal(0, result.unavailableStorageCount);
        equal(1, result.storageAssessment.size());
        StorageAssessment assessment = result.storageAssessment.get(0);
        assertAssessmentRawFields(camera, assessment);
        check(!assessment.eligible && !assessment.rejectionReasons.isEmpty(), "Rejected card retains its specific reasons");
        check(String.join(";", assessment.rejectionReasons).contains(reasonWord), "Rejection explains " + reasonWord);
        String diagnostics = String.join("\n", result.diagnostics);
        check(diagnostics.contains(String.format(java.util.Locale.ROOT, "FileSystemType=0x%04X", camera.fileSystemType)),
                "Failure diagnostics retain the successful raw filesystem response");
        check(diagnostics.contains(String.format(java.util.Locale.ROOT, "AccessCapability=0x%04X", camera.accessCapability)),
                "Failure diagnostics retain the raw access response");
        for (String reason : assessment.rejectionReasons) check(diagnostics.contains(reason), "Failure diagnostics include each reason");
        if (camera.accessCapability == 2) {
            check(String.join(";", assessment.rejectionReasons).contains("可删除"),
                    "Read-only-with-deletion is not silently treated as writable");
        }
        equal(0L, result.storageId);
        equal(0, camera.totalWrites());
        equal(0, camera.callsFor("send", 0x100c).size());
        equal(0, camera.callsFor("read", 0x1007).size());
        check(!result.writeAttempted && !result.verifiedTransfer, "A local refusal creates neither directories nor files");
        session.closeSession();
        equal("StorageEligibilityCheck", result.failurePhase);
        equal("", result.failedOperation);
        equal("GetStorageInfo (0x1005)", result.lastPtpOperation);
    }

    private static void readOnlyExistingDirectoryExperimentRequiresExplicitChoice() throws Exception {
        Camera ordinary = readOnlyCamera();
        UsbNcpTransfer.Result ordinaryResult = result();
        NcpTransferSession ordinarySession = new NcpTransferSession(ordinary, ordinaryResult);
        rejects(() -> ordinarySession.run(NCP));
        equal(0, ordinary.totalWrites());
        equal(0, ordinary.callsFor("send", 0x100c).size());
        equal("normal", ordinaryResult.transferMode);
        check(!ordinaryResult.existingDirectoryExperimentRequested && !ordinaryResult.readOnlyExceptionSelected
                && !ordinaryResult.readOnlyExceptionAttempted && !ordinaryResult.existingPathVerified,
                "The default constructor cannot silently enable the experiment");
        equal(-1, ordinaryResult.selectedAccessCapability);
        ordinarySession.closeSession();

        Camera camera = readOnlyCamera();
        UsbNcpTransfer.Result result = result();
        camera.observedResult = result;
        NcpTransferSession session = new NcpTransferSession(camera, result, true);
        session.run(NCP);
        equal("existing_directory_experiment", result.transferMode);
        check(result.existingDirectoryExperimentRequested && result.readOnlyExceptionSelected
                        && result.readOnlyExceptionAttempted && result.existingPathVerified,
                "An explicitly requested experiment records selection, visible path and actual request");
        equal(2, result.selectedAccessCapability);
        equal(0, camera.directoryMetadataWrites);
        equal(1, camera.fileMetadataWrites);
        equal(1, camera.contentWrites);
        equal(1, camera.metadataRequests.size());
        PtpObjectInfo request = camera.metadataRequests.get(0);
        equal(STORAGE, request.storageId());
        equal(camera.customPc, request.parentObject());
        equal("PICCON01.NCP", request.filename());
        equal(0x3000, request.objectFormat());
        check(result.verifiedTransfer && result.readbackVerified && !result.menuImportVerified,
                "The experiment still requires the same byte, hash and path verification");
        check(!result.storageAssessment.get(0).eligible && result.storageAssessment.get(0).experimentCandidate,
                "Read-only metadata remains ineligible for ordinary upload even after a simulated success");
        equal(NCP, camera.contents.get(result.fileHandle));
        check(camera.operations.stream().noneMatch(operation -> operation >= 0x9000),
                "The directory experiment sends no guessed vendor commands");
        session.closeSession();
    }

    private static void experimentMetadataRejectionPreservesActualPtpContext() throws Exception {
        for (int response : new int[] {0x200f, 0x2020}) {
            Camera camera = readOnlyCamera();
            camera.sendFailureOperation = 0x100c;
            camera.sendFailureResponse = response;
            UsbNcpTransfer.Result result = result();
            camera.observedResult = result;
            NcpTransferSession session = new NcpTransferSession(camera, result, true);
            try {
                session.run(NCP);
                throw new AssertionError("Expected camera rejection of metadata");
            } catch (PtpResponseException error) {
                equal(response, error.responseCode());
                List<Call> requests = camera.callsFor("send", 0x100c);
                equal(1, requests.size());
                Call request = requests.get(0);
                assertCall(request, "send", 0x100c, error.transactionId(), STORAGE, camera.customPc);
                equal(error.transactionId(), result.failedTransactionId);
            }
            equal("SendObjectInfo (0x100C)", result.failedOperation);
            equal("0x00010001", result.failedStorageId);
            equal("PtpOperation", result.failurePhase);
            check(result.readOnlyExceptionAttempted && result.writeAttempted && result.existingPathVerified,
                    "A camera refusal is distinguished from a local preflight stop");
            check(!result.fileMetadataAccepted && !result.uploadStarted && !result.verifiedTransfer,
                    "A rejected metadata request does not become accepted metadata or content");
            equal(0L, result.fileHandle);
            equal(0, camera.totalWrites());
            equal(0, camera.callsFor("send", 0x100d).size());
            equal(1, camera.metadataRequests.size());
            equal(camera.customPc, camera.metadataRequests.get(0).parentObject());
            session.closeSession();
            equal("SendObjectInfo (0x100C)", result.failedOperation);
            equal(1L, camera.countCommands(0x1003));
        }
    }

    private static void experimentContentRejectionRetainsAcceptedMetadata() throws Exception {
        for (int response : new int[] {0x200f, 0x2020}) {
            Camera camera = readOnlyCamera();
            camera.sendFailureOperation = 0x100d;
            camera.sendFailureResponse = response;
            UsbNcpTransfer.Result result = result();
            NcpTransferSession session = new NcpTransferSession(camera, result, true);
            try {
                session.run(NCP);
                throw new AssertionError("Expected camera rejection of content");
            } catch (PtpResponseException error) {
                equal(response, error.responseCode());
                equal(error.transactionId(), result.failedTransactionId);
            }
            equal(1, camera.callsFor("send", 0x100c).size());
            equal(1, camera.callsFor("send", 0x100d).size());
            equal(1, camera.fileMetadataWrites);
            equal(0, camera.contentWrites);
            check(result.readOnlyExceptionAttempted && result.fileMetadataAccepted && result.uploadStarted,
                    "A rejected content transaction retains accepted metadata and attempted content state");
            check(result.fileHandle != 0 && camera.objects.containsKey(result.fileHandle),
                    "A possible incomplete object remains explicitly represented after metadata acceptance");
            check(!camera.contents.containsKey(result.fileHandle), "The simulator has no confirmed file content");
            check(!result.uploadResponseOk && !result.verifiedTransfer && !result.readbackVerified,
                    "No content acknowledgement or verification is fabricated");
            equal("SendObject (0x100D)", result.failedOperation);
            equal("0x00010001", result.failedStorageId);
            equal("PtpOperation", result.failurePhase);
            check(!result.changes.isEmpty(), "Diagnostic changes survive a failed content transaction");
            session.closeSession();
        }
    }

    private static void experimentMissingInvisibleAndConflictingDirectoriesNeverWrite() throws Exception {
        List<Camera> fixtures = new ArrayList<>();
        fixtures.add(new Camera()); // Missing NIKON.
        Camera noCustomPc = new Camera();
        noCustomPc.add(PtpObjectInfo.folder(STORAGE, 0, "NIKON"), null);
        fixtures.add(noCustomPc);
        Camera invisibleRoot = readOnlyCamera();
        invisibleRoot.hiddenHandles.add(1L);
        fixtures.add(invisibleRoot);
        Camera invisibleChild = readOnlyCamera();
        invisibleChild.hiddenHandles.add(invisibleChild.customPc);
        fixtures.add(invisibleChild);
        Camera wrongRootCase = new Camera();
        wrongRootCase.add(PtpObjectInfo.folder(STORAGE, 0, "nikon"), null);
        fixtures.add(wrongRootCase);
        Camera rootFile = new Camera();
        rootFile.add(PtpObjectInfo.file(STORAGE, 0, "NIKON", 1), new byte[] {9});
        fixtures.add(rootFile);
        Camera rootAssociation = new Camera();
        rootAssociation.add(association(STORAGE, 0, "NIKON", 2), null);
        fixtures.add(rootAssociation);
        Camera duplicateRoot = readOnlyCamera();
        duplicateRoot.add(PtpObjectInfo.folder(STORAGE, 0, "NIKON"), null);
        fixtures.add(duplicateRoot);
        for (int conflict = 0; conflict < 4; conflict++) {
            Camera camera = new Camera();
            long nikon = camera.add(PtpObjectInfo.folder(STORAGE, 0, "NIKON"), null);
            if (conflict == 0) camera.add(PtpObjectInfo.folder(STORAGE, nikon, "custompc"), null);
            if (conflict == 1) camera.add(PtpObjectInfo.file(STORAGE, nikon, "CUSTOMPC", 1), new byte[] {9});
            if (conflict == 2) camera.add(association(STORAGE, nikon, "CUSTOMPC", 2), null);
            if (conflict == 3) {
                camera.add(PtpObjectInfo.folder(STORAGE, nikon, "CUSTOMPC"), null);
                camera.add(PtpObjectInfo.folder(STORAGE, nikon, "CUSTOMPC"), null);
            }
            fixtures.add(camera);
        }
        for (Camera camera : fixtures) {
            camera.accessCapability = 2;
            camera.fileSystemType = 3;
            UsbNcpTransfer.Result result = result();
            NcpTransferSession session = new NcpTransferSession(camera, result, true);
            rejects(() -> session.run(NCP));
            equal(0, camera.totalWrites());
            equal(0, camera.callsFor("send", 0x100c).size());
            equal(0, camera.callsFor("send", 0x100d).size());
            equal("ExistingDirectoryCheck", result.failurePhase);
            check(result.readOnlyExceptionSelected && !result.readOnlyExceptionAttempted
                            && !result.writeAttempted && !result.existingPathVerified,
                    "Selection alone is not an attempted write and ambiguous paths never pass verification");
            equal("", result.failedOperation);
            equal(-1L, result.failedTransactionId);
            session.closeSession();
        }
    }

    private static void experimentNeverCreatesDirectoriesOnWritableCards() throws Exception {
        Camera missing = new Camera();
        UsbNcpTransfer.Result missingResult = result();
        NcpTransferSession missingSession = new NcpTransferSession(missing, missingResult, true);
        rejects(() -> missingSession.run(NCP));
        equal(0, missing.totalWrites());
        equal("ExistingDirectoryCheck", missingResult.failurePhase);
        check(!missingResult.readOnlyExceptionSelected && !missingResult.readOnlyExceptionAttempted,
                "A writable card does not silently switch the requested mode into directory creation");
        missingSession.closeSession();

        Camera existing = Camera.existingDirectories();
        UsbNcpTransfer.Result existingResult = result();
        NcpTransferSession existingSession = new NcpTransferSession(existing, existingResult, true);
        existingSession.run(NCP);
        equal(0, existing.directoryMetadataWrites);
        equal(0, existingResult.selectedAccessCapability);
        check(existingResult.existingPathVerified && existingResult.verifiedTransfer,
                "An existing writable path still supports a verified experimental-mode upload");
        check(!existingResult.readOnlyExceptionSelected && !existingResult.readOnlyExceptionAttempted,
                "A writable upload is never mislabeled as a read-only exception attempt");
        existingSession.closeSession();
    }

    private static void experimentOtherEligibilityGuardsRemainEnforced() throws Exception {
        List<Camera> fixtures = new ArrayList<>();
        for (int access : new int[] {1, 99, 0xffff}) {
            Camera camera = readOnlyCamera();
            camera.accessCapability = access;
            fixtures.add(camera);
        }
        for (int filesystem : new int[] {0, 1, 99}) {
            Camera camera = readOnlyCamera();
            camera.fileSystemType = filesystem;
            fixtures.add(camera);
        }
        for (int type : new int[] {0, 1, 2, 3, 99}) {
            Camera camera = readOnlyCamera();
            camera.storageType = type;
            fixtures.add(camera);
        }
        Camera lowSpace = readOnlyCamera();
        lowSpace.freeBytes = NCP.length + 8191;
        fixtures.add(lowSpace);
        for (Camera camera : fixtures) {
            UsbNcpTransfer.Result result = result();
            NcpTransferSession session = new NcpTransferSession(camera, result, true);
            rejects(() -> session.run(NCP));
            equal(0, camera.totalWrites());
            equal(0, camera.callsFor("read", 0x1007).size());
            equal("StorageEligibilityCheck", result.failurePhase);
            check(!result.readOnlyExceptionSelected && !result.readOnlyExceptionAttempted,
                    "The opt-in does not relax any access, type, filesystem or space condition besides access 2");
            check(!result.storageAssessment.get(0).experimentCandidate, "Other guards exclude experimental candidacy");
            session.closeSession();
        }
        for (String model : new String[] {"Z 6", "Z 7II", "unknown"}) {
            Camera camera = readOnlyCamera();
            camera.model = model;
            NcpTransferSession session = new NcpTransferSession(camera, result(), true);
            rejects(() -> session.run(NCP));
            equal(0, camera.totalWrites());
            equal(0L, camera.countCommands(0x1002));
        }
        for (int missingOperation : REQUIRED) {
            Camera camera = readOnlyCamera();
            camera.missingOperation = missingOperation;
            NcpTransferSession session = new NcpTransferSession(camera, result(), true);
            rejects(() -> session.run(NCP));
            equal(0, camera.totalWrites());
            equal(0L, camera.countCommands(0x1002));
        }
    }

    private static void experimentCardSelectionMustBeUnambiguousAndPreferWritable() throws Exception {
        for (String descriptor : new String[] {"SDXC Card", "Unidentified media"}) {
            Camera ambiguous = readOnlyCamera();
            ambiguous.storageIds = new long[] {STORAGE, SECOND_STORAGE};
            ambiguous.storageDescription = descriptor;
            UsbNcpTransfer.Result result = result();
            NcpTransferSession session = new NcpTransferSession(ambiguous, result, true);
            rejects(() -> session.run(NCP));
            equal(2, result.storageAssessment.size());
            equal(0L, result.storageId);
            equal(0, ambiguous.callsFor("read", 0x1007).size());
            equal(0, ambiguous.totalWrites());
            check(!result.readOnlyExceptionSelected && !result.readOnlyExceptionAttempted,
                    "Several equivalent candidates are not selected by order");
            session.closeSession();
        }

        Camera uniqueSd = new Camera();
        uniqueSd.storageIds = new long[] {STORAGE, SECOND_STORAGE};
        uniqueSd.activeStorage = SECOND_STORAGE;
        uniqueSd.accessCapability = 2;
        uniqueSd.storageDescriptions.put(STORAGE, "CFexpress card");
        uniqueSd.storageDescriptions.put(SECOND_STORAGE, "SDXC Card");
        long nikon = uniqueSd.add(PtpObjectInfo.folder(SECOND_STORAGE, 0, "NIKON"), null);
        uniqueSd.customPc = uniqueSd.add(PtpObjectInfo.folder(SECOND_STORAGE, nikon, "CUSTOMPC"), null);
        UsbNcpTransfer.Result uniqueResult = result();
        NcpTransferSession uniqueSession = new NcpTransferSession(uniqueSd, uniqueResult, true);
        uniqueSession.run(NCP);
        equal(SECOND_STORAGE, uniqueResult.storageId);
        check(uniqueResult.readOnlyExceptionSelected && uniqueResult.verifiedTransfer,
                "A single identifiable SD candidate among distinct cards can be selected");
        equal(0, uniqueSd.directoryMetadataWrites);
        uniqueSession.closeSession();

        for (long[] order : new long[][] {{STORAGE, SECOND_STORAGE}, {SECOND_STORAGE, STORAGE}}) {
            Camera writableFirst = Camera.existingDirectories();
            writableFirst.storageIds = order;
            writableFirst.accessCapability = 2;
            writableFirst.storageAccess.put(STORAGE, 0);
            writableFirst.storageDescriptions.put(STORAGE, "CFexpress card");
            writableFirst.storageDescriptions.put(SECOND_STORAGE, "SDXC Card");
            UsbNcpTransfer.Result result = result();
            NcpTransferSession session = new NcpTransferSession(writableFirst, result, true);
            session.run(NCP);
            equal(STORAGE, result.storageId);
            equal(0, result.selectedAccessCapability);
            check(!result.readOnlyExceptionSelected && !result.readOnlyExceptionAttempted && result.verifiedTransfer,
                    "A normal writable candidate has priority over any read-only SD experiment candidate");
            equal(0, writableFirst.directoryMetadataWrites);
            session.closeSession();
        }
    }

    private static void experimentPreservesOccupiedFilesAndRejectsExhaustedNames() throws Exception {
        Camera camera = readOnlyCamera();
        byte[] original = {77, 88};
        long oldHandle = camera.add(PtpObjectInfo.file(STORAGE, camera.customPc, "piccon01.ncp", original.length), original);
        UsbNcpTransfer.Result result = result();
        NcpTransferSession session = new NcpTransferSession(camera, result, true);
        session.run(NCP);
        equal("PICCON02.NCP", result.fileName);
        equal(original, camera.contents.get(oldHandle));
        equal(0, camera.directoryMetadataWrites);
        check(result.verifiedTransfer, "An occupied filename is preserved in experimental mode");
        session.closeSession();

        Camera exhausted = readOnlyCamera();
        for (int i = 1; i <= 99; i++) exhausted.add(PtpObjectInfo.file(STORAGE, exhausted.customPc,
                String.format(java.util.Locale.ROOT, "PICCON%02d.NCP", i), 1), new byte[] {1});
        UsbNcpTransfer.Result exhaustedResult = result();
        NcpTransferSession exhaustedSession = new NcpTransferSession(exhausted, exhaustedResult, true);
        rejects(() -> exhaustedSession.run(NCP));
        equal(0, exhausted.totalWrites());
        equal(0, exhausted.callsFor("send", 0x100c).size());
        check(exhaustedResult.existingPathVerified && !exhaustedResult.readOnlyExceptionAttempted
                        && !exhaustedResult.writeAttempted,
                "A valid existing directory with no free filename causes no write attempt");
        exhaustedSession.closeSession();

        Camera raced = readOnlyCamera();
        raced.raceFilename = true;
        UsbNcpTransfer.Result racedResult = result();
        NcpTransferSession racedSession = new NcpTransferSession(raced, racedResult, true);
        rejects(() -> racedSession.run(NCP));
        equal(0, raced.totalWrites());
        check(!racedResult.readOnlyExceptionAttempted && !racedResult.writeAttempted,
                "A newly occupied filename immediately before reservation prevents the exception attempt");
        racedSession.closeSession();
    }

    private static Camera readOnlyCamera() {
        Camera camera = Camera.existingDirectories();
        camera.fileSystemType = 3;
        camera.accessCapability = 2;
        return camera;
    }

    private static PtpObjectInfo association(long storage, long parent, String name, int associationType) {
        return new PtpObjectInfo.Builder().storageId(storage).parentObject(parent).filename(name)
                .objectFormat(0x3001).associationType(associationType).build();
    }

    private static void assertAssessmentRawFields(Camera camera, StorageAssessment assessment) {
        equal(STORAGE, assessment.storageId);
        equal(camera.storageType, assessment.storageType);
        equal(camera.fileSystemType, assessment.fileSystemType);
        equal(camera.accessCapability, assessment.accessCapability);
        equal(Long.toString(camera.freeBytes), assessment.freeSpaceBytes);
        equal(Long.toString(256 * 1024 * 1024), assessment.maxCapacityBytes);
        equal(Long.toString(NCP.length + 8192L), assessment.requiredBytes);
        equal("SDXC Card", assessment.description);
    }

    private static void assertCall(Call call, String kind, int operation, long transactionId,
                                   long... parameters) {
        equal(kind, call.kind);
        equal(operation, call.operation);
        equal(transactionId, call.transactionId);
        check(Arrays.equals(parameters, call.parameters), "Operation parameters select the expected storage/path");
    }

    private static UsbNcpTransfer.Result result() throws IOException {
        UsbNcpTransfer.Result result = new UsbNcpTransfer.Result();
        result.sha256 = UsbNcpTransfer.sha256(NCP);
        result.fileSize = NCP.length;
        return result;
    }

    private static final class Camera implements NcpTransferSession.Operations {
        final Map<Long, PtpObjectInfo> objects = new LinkedHashMap<>();
        final Map<Long, byte[]> contents = new LinkedHashMap<>();
        final List<Integer> operations = new ArrayList<>();
        final List<Integer> commands = new ArrayList<>();
        final List<Call> calls = new ArrayList<>();
        final Map<Long, Integer> storageErrors = new LinkedHashMap<>();
        final Map<Long, Integer> storageAccess = new LinkedHashMap<>();
        final Map<Long, String> storageDescriptions = new LinkedHashMap<>();
        final List<Long> hiddenHandles = new ArrayList<>();
        final List<PtpObjectInfo> metadataRequests = new ArrayList<>();
        UsbNcpTransfer.Result observedResult;
        long[] storageIds = {STORAGE};
        long activeStorage = STORAGE;
        long storageErrorTransactionDelta;
        int readFailureOperation = -1;
        int commandFailureOperation = -1;
        int sendFailureOperation = -1;
        int sendFailureResponse = 0x2013;
        String storageDescription = "SDXC Card";
        String model = "NIKON Z 6II";
        int missingOperation = -1;
        int accessCapability;
        int fileSystemType = 2;
        int storageType = 4;
        long freeBytes = 128 * 1024 * 1024;
        long nextHandle = 1;
        long lastReservation;
        long customPc;
        long reusedReservationHandle;
        long lastTransaction;
        int directoryMetadataWrites;
        int fileMetadataWrites;
        int contentWrites;
        int customPcLists;
        boolean alreadyOpen;
        boolean corruptReadback;
        boolean badListedStorage;
        boolean badReservationStorage;
        boolean badWrittenParent;
        boolean raceFilename;

        static Camera existingDirectories() {
            Camera camera = new Camera();
            long nikon = camera.add(PtpObjectInfo.folder(STORAGE, 0, "NIKON"), null);
            camera.customPc = camera.add(PtpObjectInfo.folder(STORAGE, nikon, "CUSTOMPC"), null);
            return camera;
        }

        long add(PtpObjectInfo info, byte[] data) {
            long handle = nextHandle++;
            objects.put(handle, info);
            if (data != null) contents.put(handle, data.clone());
            return handle;
        }

        int totalWrites() { return directoryMetadataWrites + fileMetadataWrites + contentWrites; }
        long countCommands(int operation) { return commands.stream().filter(value -> value == operation).count(); }

        List<Call> callsFor(String kind, int operation) {
            List<Call> matches = new ArrayList<>();
            for (Call call : calls) if (call.kind.equals(kind) && call.operation == operation) matches.add(call);
            return matches;
        }

        private void record(String kind, int operation, long transactionId, long... parameters) {
            operations.add(operation);
            calls.add(new Call(kind, operation, transactionId, parameters, totalWrites()));
            if (operation == 0x1001 || operation == 0x1002) equal(0L, transactionId);
            else {
                check(transactionId > lastTransaction, "Transaction IDs increase for session operations");
                lastTransaction = transactionId;
            }
        }

        @Override public byte[] getData(int operation, long transactionId, long... parameters) throws IOException {
            record("read", operation, transactionId, parameters);
            if (operation == readFailureOperation) throw new PtpResponseException(0x2013, transactionId);
            switch (operation) {
                case 0x1001: return deviceInfo(model, missingOperation);
                case 0x1004: {
                    Writer writer = new Writer().u32(storageIds.length);
                    for (long storage : storageIds) writer.u32(storage);
                    return writer.bytes();
                }
                case 0x1005: {
                    equal(1, parameters.length);
                    check(Arrays.stream(storageIds).anyMatch(storage -> storage == parameters[0]),
                            "GetStorageInfo targets an enumerated storage ID");
                    Integer failure = storageErrors.get(parameters[0]);
                    if (failure != null) throw new PtpResponseException(failure,
                            transactionId + storageErrorTransactionDelta);
                    return new Writer().u16(storageType).u16(fileSystemType)
                            .u16(storageAccess.getOrDefault(parameters[0], accessCapability))
                            .u64(256 * 1024 * 1024).u64(freeBytes).u32(1000)
                            .string(storageDescriptions.getOrDefault(parameters[0], storageDescription)).string("SD").bytes();
                }
                case 0x1007: {
                    equal(3, parameters.length);
                    equal(activeStorage, parameters[0]);
                    equal(0L, parameters[1]);
                    long parent = parameters[2];
                    if (parent == customPc && customPc != 0) {
                        customPcLists++;
                        if (raceFilename && customPcLists == 2) add(PtpObjectInfo.file(STORAGE, customPc,
                                "PICCON01.NCP", 1), new byte[] {1});
                    }
                    List<Long> matches = new ArrayList<>();
                    for (Map.Entry<Long, PtpObjectInfo> object : objects.entrySet()) {
                        PtpObjectInfo info = object.getValue();
                        if (!hiddenHandles.contains(object.getKey()) && (badListedStorage || info.storageId() == parameters[0])
                                && (info.parentObject() == parent || (parent == ROOT && info.parentObject() == 0))) {
                            matches.add(object.getKey());
                        }
                    }
                    Writer writer = new Writer().u32(matches.size());
                    for (long handle : matches) writer.u32(handle);
                    return writer.bytes();
                }
                case 0x1008: {
                    PtpObjectInfo info = objects.get(parameters[0]);
                    if (info == null) throw new IOException("Unknown fake object");
                    if (badWrittenParent && parameters[0] == lastReservation && contentWrites > 0) {
                        return PtpObjectInfo.file(STORAGE, 999, info.filename(), info.compressedSize()).toBytes();
                    }
                    return info.toBytes();
                }
                case 0x1009: {
                    byte[] bytes = contents.get(parameters[0]);
                    if (bytes == null) throw new IOException("No fake content");
                    byte[] returned = bytes.clone();
                    if (corruptReadback) returned[0] ^= 1;
                    return returned;
                }
                default: throw new AssertionError("Unexpected read " + Integer.toHexString(operation));
            }
        }

        @Override public PtpContainer command(int operation, long transactionId, long... parameters) throws IOException {
            record("command", operation, transactionId, parameters);
            commands.add(operation);
            if (operation != 0x1002 && operation != 0x1003) throw new AssertionError("Unexpected command");
            if (operation == commandFailureOperation) throw new PtpResponseException(0x2013, transactionId);
            PtpContainer response = PtpContainer.response(operation == 0x1002 && alreadyOpen ? 0x201e : 0x2001,
                    transactionId);
            PtpValidation.requireOkResponse(response, transactionId);
            return response;
        }

        @Override public PtpContainer sendData(int operation, long transactionId, byte[] data,
                                               long... parameters) throws IOException {
            record("send", operation, transactionId, parameters);
            if (operation == 0x100c) metadataRequests.add(PtpObjectInfo.parse(data));
            if (observedResult != null && observedResult.readOnlyExceptionSelected) {
                check(observedResult.existingPathVerified && observedResult.readOnlyExceptionAttempted,
                        "The path must be verified and actual-attempt flag set before the first outbound write");
            }
            if (operation == sendFailureOperation) throw new PtpResponseException(sendFailureResponse, transactionId);
            if (operation == 0x100c) {
                equal(2, parameters.length);
                equal(activeStorage, parameters[0]);
                PtpObjectInfo info = PtpObjectInfo.parse(data);
                if (info.objectFormat() == 0x3001) directoryMetadataWrites++;
                else fileMetadataWrites++;
                if (info.objectFormat() == 0x3000 && reusedReservationHandle != 0) {
                    return PtpContainer.response(0x2001, transactionId, activeStorage, info.parentObject(),
                            reusedReservationHandle);
                }
                long handle = add(info, null);
                lastReservation = handle;
                if (info.filename().equals("CUSTOMPC")) customPc = handle;
                return PtpContainer.response(0x2001, transactionId,
                        badReservationStorage ? activeStorage + 1 : activeStorage, info.parentObject(), handle);
            }
            if (operation == 0x100d) {
                equal(0, parameters.length);
                PtpObjectInfo info = objects.get(lastReservation);
                check(info != null && info.objectFormat() == 0x3000, "Content follows a file reservation");
                equal((long) data.length, info.compressedSize());
                contentWrites++;
                contents.put(lastReservation, data.clone());
                return PtpContainer.response(0x2001, transactionId);
            }
            throw new AssertionError("Unexpected write " + Integer.toHexString(operation));
        }
    }

    private static final class Call {
        final String kind;
        final int operation;
        final long transactionId;
        final long[] parameters;
        final int writesBefore;

        Call(String kind, int operation, long transactionId, long[] parameters, int writesBefore) {
            this.kind = kind;
            this.operation = operation;
            this.transactionId = transactionId;
            this.parameters = parameters.clone();
            this.writesBefore = writesBefore;
        }
    }

    private static byte[] deviceInfo(String model, int missing) {
        List<Integer> supported = new ArrayList<>();
        supported.add(0x1001);
        for (int operation : REQUIRED) if (operation != missing) supported.add(operation);
        Writer writer = new Writer().u16(100).u32(10).u16(100).string("").u16(0).u32(supported.size());
        for (int operation : supported) writer.u16(operation);
        return writer.u32(0).u32(0).u32(0).u32(0).string("Nikon").string(model).string("1.62")
                .string("private serial").bytes();
    }

    private static final class Writer {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Writer u16(int value) { bytes.write(value); bytes.write(value >>> 8); return this; }
        Writer u32(long value) { for (int i = 0; i < 4; i++) bytes.write((int) (value >>> (8 * i))); return this; }
        Writer u64(long value) { for (int i = 0; i < 8; i++) bytes.write((int) (value >>> (8 * i))); return this; }
        Writer string(String value) {
            bytes.write(value.length() + 1);
            byte[] encoded = value.getBytes(StandardCharsets.UTF_16LE);
            bytes.write(encoded, 0, encoded.length);
            u16(0);
            return this;
        }
        byte[] bytes() { return bytes.toByteArray(); }
    }

    private interface Action { void run() throws Exception; }
    private static void expectResponse(int code, long transactionId, Action action) throws Exception {
        try { action.run(); }
        catch (PtpResponseException expected) {
            equal(code, expected.responseCode());
            equal(transactionId, expected.transactionId());
            return;
        }
        throw new AssertionError("Expected failed PTP response " + Integer.toHexString(code));
    }
    private static void rejects(Action action) throws Exception {
        try { action.run(); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected stopped transfer");
    }

    private static void equal(Object expected, Object actual) {
        boolean same = expected instanceof byte[] && actual instanceof byte[]
                ? Arrays.equals((byte[]) expected, (byte[]) actual) : java.util.Objects.equals(expected, actual);
        check(same, "Expected " + expected + ", got " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

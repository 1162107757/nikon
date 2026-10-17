package com.nikonpreset.usbprobe.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import com.nikonpreset.usbprobe.ptp.PtpContainer;
import com.nikonpreset.usbprobe.ptp.PtpContainerReader;
import com.nikonpreset.usbprobe.ncp.NcpCompatibilityFixtures;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Synthetic wire-copy tests. Fixtures contain no user recipe and prove no NCP mapping. */
public final class UsbPictureControlImportHostTest {
    private static final int SOURCE = 201;
    private static final int TARGET = 207;
    private static final byte[] SNAPSHOT = syntheticProfile("HOST COPY");
    private static int checks;

    public static void main(String[] args) throws Exception {
        onlyExplicitWriterModeCanSendTheExactRegistration();
        strictWriterDataValidationRejectsUnknownFieldsAndEncodings();
        copyUsesFreshSourceAndTwiceObservedEmptyTarget();
        occupiedRacedAndChangedSourcesNeverWrite();
        rejectionRetainsPossibleWriteAndPrimaryContext();
        differentReadbackNeverRetriesOrRestores();
        unconfirmedWriteAndReadbackNeverSendAnotherCommand();
        cancellationAfterVerifiedReadbackStillRequiresCleanupConfirmation();
        ncpEntryRecomputesAdaptationAndPreservesItsPurpose();
        ncpConversionAndOccupiedTargetsNeverBypassTheGuards();
        ncpWriteFailureKeepsConversionMetadata();
        explicitOccupiedOverwriteReadsCapabilityAndClosesTheUsbSession();
        occupiedOverwriteRequiresMatchingConfirmationAndCapability();
        System.out.println("PASS: " + checks + " production empty-slot Picture Control copy checks");
    }

    private static void onlyExplicitWriterModeCanSendTheExactRegistration() throws Exception {
        Camera camera = new Camera();
        PtpUsbTransport writer = new PtpUsbTransport(camera.device, camera.manager, new ArrayList<>(),
                PtpUsbTransport.Mode.WRITE_EMPTY_PC);
        for (int opcode : new int[] {0x100c, 0x100d, 0x1016, 0x90c2, 0x90ce, 0x9fff}) {
            rejects(() -> writer.sendData(opcode, 1, SNAPSHOT, TARGET, 0));
        }
        rejects(() -> writer.sendData(0x90cd, 1, SNAPSHOT, TARGET, 1));
        rejects(() -> writer.sendData(0x90cd, 1, SNAPSHOT, 200, 0));
        rejects(() -> writer.sendData(0x90cd, 1, SNAPSHOT, 210, 0));
        rejects(() -> writer.sendData(0x90cd, 1, SNAPSHOT, TARGET));
        for (int opcode : new int[] {0x1004, 0x1007, 0x1009, 0x90ca, 0x90cf}) {
            rejects(() -> writer.getData(opcode, 1));
        }
        rejects(() -> writer.getData(0x90cc, 1, 1, 0));
        rejects(() -> writer.getData(0x90cc, 1, TARGET, 1));
        equal(0, camera.commands.size());
        equal(0, camera.dataPayloads.size());
        writer.close();
        lifecycle(camera);
        for (boolean readOnly : new boolean[] {false, true}) {
            Camera other = new Camera();
            PtpUsbTransport transport = new PtpUsbTransport(other.device, other.manager, new ArrayList<>(), readOnly);
            rejects(() -> transport.sendData(0x90cd, 1, SNAPSHOT, TARGET, 0));
            equal(0, other.commands.size());
            transport.close();
            lifecycle(other);
        }
        Camera one = new Camera();
        PtpUsbTransport once = new PtpUsbTransport(one.device, one.manager, new ArrayList<>(),
                PtpUsbTransport.Mode.WRITE_EMPTY_PC);
        once.sendData(0x90cd, 1, SNAPSHOT, TARGET, 0);
        rejects(() -> once.sendData(0x90cd, 2, SNAPSHOT, TARGET, 0));
        equal(1, one.commands.size());
        equal(1, one.dataPayloads.size());
        once.close();
        lifecycle(one);
    }

    private static void strictWriterDataValidationRejectsUnknownFieldsAndEncodings() throws Exception {
        equal(SNAPSHOT, PictureControlImportSession.validateConvertedData(SNAPSHOT).rawBytes());
        List<byte[]> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(new byte[36]);
        invalid.add(new byte[638]); // NCP file bytes cannot be passed as camera wire data.
        invalid.add(Arrays.copyOf(SNAPSHOT, 613));
        invalid.add(Arrays.copyOf(SNAPSHOT, 615));
        for (int[] mutation : new int[][] {{0, 0}, {0, 7}, {2, 128}, {22, 1}, {23, 2}, {24, 3},
                {25, 37}, {26, 21}, {27, 21}, {28, 13}, {29, 7}, {30, 13}, {31, 13},
                {32, 1}, {33, 1}, {34, 1}, {35, 2}}) {
            byte[] bytes = SNAPSHOT.clone();
            bytes[mutation[0]] = (byte) mutation[1];
            invalid.add(bytes);
        }
        byte[] missingNameEnd = SNAPSHOT.clone();
        Arrays.fill(missingNameEnd, 2, 22, (byte) 'A');
        invalid.add(missingNameEnd);
        byte[] missingCurveHeader = SNAPSHOT.clone();
        missingCurveHeader[36] = missingCurveHeader[37] = 0;
        invalid.add(missingCurveHeader);
        for (byte[] bytes : invalid) rejects(() -> PictureControlImportSession.validateConvertedData(bytes));

        Camera camera = new Camera();
        UsbPictureControlImport.ImportException failure = failed(camera, new byte[638], SOURCE, TARGET);
        check(!failure.partialResult.writeAttempted, "A file-format input cannot reach a camera write");
        equal(0, camera.commands.size());
        lifecycle(camera);
    }

    private static void copyUsesFreshSourceAndTwiceObservedEmptyTarget() throws Exception {
        Camera camera = new Camera();
        camera.readChunk = 7;
        Map<Integer, byte[]> original = camera.snapshotSlots();
        UsbPictureControlImport.Result result = copy(camera, SNAPSHOT, SOURCE, TARGET);
        check(result.completed && result.sourceVerified && result.initialTargetEmpty && result.recheckTargetEmpty,
                "A successful copy requires the fresh source snapshot and both target observations");
        check(!result.ncpMode && result.modePurpose.equals("camera_preset_copy")
                        && result.sourceNcpSha256.isEmpty() && !result.adaptationPrepared
                        && !result.ncpRegistrationVerified && !result.ncpImportVerified,
                "An existing-camera copy cannot be labeled as an NCP compatibility import");
        check(result.writeAttempted && result.writeResponseOk && result.readbackParsed && result.readbackVerified,
                "Request, acknowledgement and exact readback remain distinct confirmed states");
        check(!result.normalizationObserved && result.differenceCount == 0, "An exact copy reports no byte differences");
        equal(9, result.initialSlots.size());
        equal(SOURCE, result.sourceItem);
        equal(TARGET, result.targetItem);
        equal(SNAPSHOT, result.sourceSnapshot.rawBytes());
        equal(SNAPSHOT, result.readback.rawBytes());
        equal(1L, camera.count(0x90cd));
        equal(1, camera.dataPayloads.size());
        equal(SNAPSHOT, camera.dataPayloads.get(0));
        equal(15, camera.commands.size());
        for (int i = 0; i < 9; i++) assertCommand(camera.commands.get(i + 2), 0x90cc, i + 1, 201 + i, 0);
        assertCommand(camera.commands.get(11), 0x90cc, 10, TARGET, 0);
        assertCommand(camera.commands.get(12), 0x90cd, 11, TARGET, 0);
        assertCommand(camera.commands.get(13), 0x90cc, 12, TARGET, 0);
        assertCommand(camera.commands.get(14), 0x1003, 13);
        check(result.sessionClosed && !result.protocolPhaseUnconfirmed, "The confirmed copy closes its own session");
        equal(SNAPSHOT, camera.slots.get(TARGET));
        for (int slot = 201; slot <= 206; slot++) equal(original.get(slot), camera.slots.get(slot));
        equal(original.get(208), camera.slots.get(208));
        equal(original.get(209), camera.slots.get(209));
        allowedCopyCommands(camera);
        lifecycle(camera);
    }

    private static void occupiedRacedAndChangedSourcesNeverWrite() throws Exception {
        for (String condition : new String[] {"occupied", "raced", "changed_source", "empty_source", "self", "missing_source"}) {
            Camera camera = new Camera();
            if (condition.equals("occupied")) camera.slots.put(TARGET, syntheticProfile("EXISTS"));
            if (condition.equals("raced")) camera.targetBecomesOccupied = true;
            if (condition.equals("changed_source")) camera.slots.put(SOURCE, syntheticProfile("CHANGED"));
            if (condition.equals("empty_source")) camera.slots.put(SOURCE, new byte[36]);
            UsbPictureControlImport.Result result = failed(camera, SNAPSHOT, condition.equals("missing_source") ? -1 : SOURCE,
                    condition.equals("self") ? SOURCE : TARGET).partialResult;
            equal(0L, camera.count(0x90cd));
            equal(0, camera.dataPayloads.size());
            check(!result.writeAttempted && !result.writeResponseOk && !result.readbackVerified,
                    "A failed source or target check causes no registration request");
            if (condition.equals("raced")) {
                check(result.initialTargetEmpty && !result.recheckTargetEmpty,
                        "The second observation catches a target that became occupied");
                equal("EmptySlotRecheck", result.failurePhase);
            }
            allowedCopyCommands(camera);
            lifecycle(camera);
        }
    }

    private static void rejectionRetainsPossibleWriteAndPrimaryContext() throws Exception {
        Camera camera = new Camera();
        camera.writeResponseCode = 0x200f;
        camera.cleanupResponseCode = 0x2002;
        UsbPictureControlImport.ImportException failure = failed(camera, SNAPSHOT, SOURCE, TARGET);
        UsbPictureControlImport.Result result = failure.partialResult;
        equal(0x200f, failure.responseCode);
        check(result.writeAttempted && !result.writeResponseOk && !result.readbackVerified && !result.completed,
                "A failed Set response retains the fact that write data was sent");
        equal(SNAPSHOT, camera.slots.get(TARGET)); // The simulator models an effect despite a failed response.
        equal(1L, camera.count(0x90cd));
        equal(2, camera.slotReads.get(TARGET));
        equal(new long[] {TARGET, 0}, result.failedParameters);
        equal(11L, result.failedTransactionId);
        equal(TARGET, result.failedItemCode);
        check(result.failedOperation.contains("90CD") && result.lastOperation.contains("90CD"),
                "A separate cleanup failure cannot replace the primary registration context");
        equal(0x2002, result.cleanupResponseCode);
        check(!result.cleanupError.isEmpty() && !result.sessionClosed, "Cleanup failure has its own evidence");
        allowedCopyCommands(camera);
        lifecycle(camera);
    }

    private static void differentReadbackNeverRetriesOrRestores() throws Exception {
        Camera camera = new Camera();
        camera.normalizeReadback = true;
        UsbPictureControlImport.Result result = failed(camera, SNAPSHOT, SOURCE, TARGET).partialResult;
        check(result.writeAttempted && result.writeResponseOk && result.readbackParsed,
                "An acknowledged write remains acknowledged when its returned contents differ");
        check(!result.readbackVerified && !result.completed && result.normalizationObserved,
                "Different readback bytes cannot become an exact-copy success");
        equal(1, result.differenceCount);
        equal(List.of(2), result.differenceOffsets);
        check(result.sessionClosed && !result.protocolPhaseUnconfirmed,
                "A fully returned semantic mismatch still allows owned session cleanup");
        equal(1L, camera.count(0x90cd));
        equal(1, camera.dataPayloads.size());
        check(!Arrays.equals(SNAPSHOT, camera.slots.get(TARGET)), "The changed target is left for user inspection, without rollback");
        allowedCopyCommands(camera);
        lifecycle(camera);
    }

    private static void unconfirmedWriteAndReadbackNeverSendAnotherCommand() throws Exception {
        for (String fault : new String[] {"lost_write_response", "partial_data", "wrong_readback_transaction"}) {
            Camera camera = new Camera();
            camera.wireFault = fault;
            UsbPictureControlImport.Result result = failed(camera, SNAPSHOT, SOURCE, TARGET).partialResult;
            check(result.writeAttempted && !result.readbackVerified && !result.completed,
                    "An unconfirmed transfer retains a possible write instead of reporting no changes");
            check(result.protocolPhaseUnconfirmed && !result.sessionClosed,
                    "An unconfirmed wire phase closes USB without another PTP command");
            equal(1L, camera.count(0x90cd));
            equal(0L, camera.count(0x1003));
            equal(fault.equals("wrong_readback_transaction"), result.writeResponseOk);
            int lastOperation = camera.commands.get(camera.commands.size() - 1).operation;
            equal(fault.equals("wrong_readback_transaction") ? 0x90cc : 0x90cd, lastOperation);
            allowedCopyCommands(camera);
            lifecycle(camera);
        }
    }

    private static void cancellationAfterVerifiedReadbackStillRequiresCleanupConfirmation() throws Exception {
        Camera camera = new Camera();
        camera.cancelAfterReadback = true;
        try {
            UsbPictureControlImport.Result result = failed(camera, SNAPSHOT, SOURCE, TARGET).partialResult;
            check(result.readbackVerified && result.writeResponseOk && !result.completed && !result.sessionClosed,
                    "A late cancellation preserves verified write evidence but cannot claim confirmed session cleanup");
            equal("SessionCleanupCancelled", result.failurePhase);
            check(!result.protocolPhaseUnconfirmed, "The fully matched readback retains its known protocol boundary");
            equal(0L, camera.count(0x1003));
            equal(1L, camera.count(0x90cd));
            allowedCopyCommands(camera);
            lifecycle(camera);
        } finally {
            Thread.interrupted();
        }
    }

    private static void ncpEntryRecomputesAdaptationAndPreservesItsPurpose() throws Exception {
        Camera camera = new Camera();
        byte[] ncp = NcpCompatibilityFixtures.ncp();
        camera.expectedWrite = NcpCompatibilityFixtures.expectedData(4, -4);
        UsbPictureControlImport.Result result = new UsbPictureControlImport().importNcpPreset(
                camera.device, camera.manager, ncp, 4, -4, TARGET);
        check(result.ncpMode && result.modePurpose.equals("ncp_compatibility_import")
                        && result.type.equals("ncp_compatibility_adaptation"),
                "The actual NCP entry carries an explicit compatibility-adaptation purpose");
        check(result.completed && result.readbackVerified && !result.sourceVerified,
                "NCP registration uses the same target guards without pretending it copied a fresh camera source");
        check(result.ncpRegistrationVerified && !result.ncpImportVerified
                        && !result.conversionEquivalentToNative && !result.photoEffectVerified,
                "Exact registration success cannot claim native NCP equivalence or verified photographic appearance");
        assertNcpMetadata(result, ncp, 4, -4);
        equal(camera.expectedWrite, result.convertedRawBytes);
        equal(hash(camera.expectedWrite), result.convertedSha256);
        equal(camera.expectedWrite, camera.dataPayloads.get(0));
        equal(9, result.initialSlots.size());
        check(result.initialTargetEmpty && result.recheckTargetEmpty, "Both empty observations also apply to NCP adaptation");
        equal(1L, camera.count(0x90cd));
        equal(3, camera.slotReads.get(TARGET));
        allowedCopyCommands(camera);
        lifecycle(camera);
    }

    private static void ncpConversionAndOccupiedTargetsNeverBypassTheGuards() throws Exception {
        for (boolean invalidChoice : new boolean[] {true, false}) {
            Camera camera = new Camera();
            byte[] ncp = NcpCompatibilityFixtures.ncp();
            if (!invalidChoice) ncp[38] = 1; // Unsupported original metadata, not a wire snapshot.
            int middle = invalidChoice ? 21 : 4;
            UsbPictureControlImport.Result result = failedNcp(camera, ncp, middle, 0).partialResult;
            check(result.ncpMode && !result.adaptationPrepared && !result.writeAttempted,
                    "Conversion must finish successfully inside the backend before USB can be opened");
            equal(hash(ncp), result.sourceNcpSha256);
            equal(ncp.length, result.sourceNcpSize);
            equal(middle, result.adaptationMiddleQuarter);
            equal(0, camera.commands.size());
            lifecycle(camera);
        }
        Camera occupied = new Camera();
        occupied.slots.put(TARGET, syntheticProfile("KEEP TARGET"));
        byte[] ncp = NcpCompatibilityFixtures.ncp();
        occupied.expectedWrite = NcpCompatibilityFixtures.expectedData(4, 0);
        UsbPictureControlImport.Result result = failedNcp(occupied, ncp, 4, 0).partialResult;
        assertNcpMetadata(result, ncp, 4, 0);
        check(!result.initialTargetEmpty && !result.writeAttempted && !result.sourceVerified,
                "Prepared adaptation data cannot authorize overwriting an occupied target or imply a camera-source check");
        equal(0L, occupied.count(0x90cd));
        equal(0, occupied.dataPayloads.size());
        lifecycle(occupied);
    }

    private static void ncpWriteFailureKeepsConversionMetadata() throws Exception {
        Camera camera = new Camera();
        byte[] ncp = NcpCompatibilityFixtures.ncp();
        camera.expectedWrite = NcpCompatibilityFixtures.expectedData(-4, 4);
        camera.writeResponseCode = 0x200f;
        UsbPictureControlImport.ImportException failure = failedNcp(camera, ncp, -4, 4);
        UsbPictureControlImport.Result result = failure.partialResult;
        assertNcpMetadata(result, ncp, -4, 4);
        equal(0x200f, failure.responseCode);
        check(result.ncpMode && result.writeAttempted && !result.writeResponseOk && !result.readbackVerified && !result.completed,
                "A failed registration preserves NCP adaptation metadata and the possibility that data already changed");
        check(!result.ncpRegistrationVerified && !result.ncpImportVerified,
                "A failed NCP registration has neither a registration nor a native-import success flag");
        equal(camera.expectedWrite, camera.slots.get(TARGET));
        equal(1L, camera.count(0x90cd));
        check(result.sessionClosed, "A fully matched negative registration response still permits owned cleanup");
        allowedCopyCommands(camera);
        lifecycle(camera);
    }

    private static void assertNcpMetadata(UsbPictureControlImport.Result result, byte[] ncp,
                                          int middle, int clarity) throws Exception {
        check(result.adaptationPrepared, "The backend independently prepared the adaptation");
        equal(hash(ncp), result.sourceNcpSha256);
        equal(ncp.length, result.sourceNcpSize);
        equal("HOST NCP", result.sourceNcpName);
        equal(hash(NcpCompatibilityFixtures.curve()), result.sourceCurveSha256);
        equal(middle, result.adaptationMiddleQuarter);
        equal(clarity, result.adaptationClarityQuarter);
        equal(0, result.adaptationContrastQuarter);
        equal(0, result.adaptationBrightnessQuarter);
        check(!result.adaptationPreview.isEmpty(), "The chosen adaptations remain reviewable after success or failure");
    }

    private static void explicitOccupiedOverwriteReadsCapabilityAndClosesTheUsbSession() throws Exception {
        Camera camera = new Camera();
        camera.advertiseCapability = true;
        byte[] previous = syntheticProfile("CONFIRMED OLD");
        camera.slots.put(TARGET, previous.clone());
        camera.expectedWrite = NcpCompatibilityFixtures.expectedData(4, -4);
        PictureControlImportSession.OverwriteConfirmation confirmation =
                new PictureControlImportSession.OverwriteConfirmation(TARGET, previous);
        UsbPictureControlImport.Result result = new UsbPictureControlImport().importNcpPreset(
                camera.device, camera.manager, NcpCompatibilityFixtures.ncp(), 4, -4, TARGET, confirmation);
        check(result.completed && result.ncpRegistrationVerified && result.readbackVerified && result.sessionClosed
                        && !result.sessionRetainedAfterImport,
                "Explicit occupied USB overwrite still requires exact readback and acknowledged session cleanup");
        check(result.overwriteRequested && result.initialTargetMatched && result.recheckTargetMatched && result.freshCapabilityVerified,
                "USB overwrite validates capability and both observations of the explicitly confirmed occupied contents");
        equal(confirmation.sha256(), result.confirmedTargetSha256);
        equal("CONFIRMED OLD", result.confirmedTargetName);
        equal(camera.expectedWrite, camera.slots.get(TARGET));
        equal(1L, camera.count(0x90cf));
        equal(1L, camera.count(0x90cd));
        equal(1L, camera.count(0x1003));
        equal(1, camera.dataPayloads.size());
        for (Command command : camera.commands) check(command.operation == 0x1001 || command.operation == 0x1002
                        || command.operation == 0x1003 || command.operation == 0x90cf || command.operation == 0x90cc || command.operation == 0x90cd,
                "USB overwrite never deletes, selects presets, changes mode or writes files");
        lifecycle(camera);
    }

    private static void occupiedOverwriteRequiresMatchingConfirmationAndCapability() throws Exception {
        for (String failure : List.of("no_confirmation", "changed", "became_empty", "raced", "missing_capability", "malformed_capability")) {
            Camera camera = new Camera();
            camera.advertiseCapability = !failure.equals("missing_capability");
            byte[] previous = syntheticProfile("CONFIRMED OLD"); camera.slots.put(TARGET, previous.clone());
            camera.expectedWrite = NcpCompatibilityFixtures.expectedData(4, -4);
            PictureControlImportSession.OverwriteConfirmation confirmation =
                    new PictureControlImportSession.OverwriteConfirmation(TARGET, previous);
            if (failure.equals("changed")) camera.slots.put(TARGET, syntheticProfile("SOMEONE ELSE"));
            if (failure.equals("became_empty")) camera.slots.put(TARGET, new byte[36]);
            if (failure.equals("raced")) camera.targetBecomesOccupied = true;
            if (failure.equals("malformed_capability")) camera.malformedCapability = true;
            try {
                new UsbPictureControlImport().importNcpPreset(camera.device, camera.manager, NcpCompatibilityFixtures.ncp(),
                        4, -4, TARGET, failure.equals("no_confirmation") ? null : confirmation);
                throw new AssertionError("Expected missing occupied-slot proof or invalid capability to stop USB overwrite");
            } catch (UsbPictureControlImport.ImportException error) {
                check(!error.partialResult.completed && !error.partialResult.writeAttempted,
                        "USB cannot overwrite changed, unconfirmed or unsupported occupied contents");
                if (failure.equals("raced")) check(error.partialResult.initialTargetMatched && !error.partialResult.recheckTargetMatched,
                        "USB's final observation detects a changed occupied target");
            }
            equal(0L, camera.count(0x90cd));
            equal(0, camera.dataPayloads.size());
            lifecycle(camera);
        }
    }

    private static UsbPictureControlImport.ImportException failedNcp(Camera camera, byte[] ncp,
                                                                    int middle, int clarity) throws Exception {
        try { new UsbPictureControlImport().importNcpPreset(camera.device, camera.manager, ncp, middle, clarity, TARGET); }
        catch (UsbPictureControlImport.ImportException expected) { checks++; return expected; }
        throw new AssertionError("Expected a stopped NCP adaptation registration");
    }

    private static String hash(byte[] bytes) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder text = new StringBuilder();
        for (byte value : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }

    private static UsbPictureControlImport.Result copy(Camera camera, byte[] data, int source, int target) throws Exception {
        return new UsbPictureControlImport().importPreset(camera.device, camera.manager, data, source, target);
    }
    private static UsbPictureControlImport.ImportException failed(Camera camera, byte[] data, int source, int target) throws Exception {
        try { copy(camera, data, source, target); }
        catch (UsbPictureControlImport.ImportException expected) { checks++; return expected; }
        throw new AssertionError("Expected a stopped empty-slot copy");
    }
    private static void assertCommand(Command command, int operation, long transaction, long... parameters) {
        equal(operation, command.operation);
        equal(transaction, command.transaction);
        equal(parameters, command.parameters);
    }
    private static void allowedCopyCommands(Camera camera) {
        for (Command command : camera.commands) {
            check(command.operation == 0x1001 || command.operation == 0x1002 || command.operation == 0x1003
                    || command.operation == 0x90cc || command.operation == 0x90cd,
                    "Copy cannot select the active preset, delete, change mode, set properties or write files");
            if (command.operation == 0x90cd) equal(new long[] {TARGET, 0}, command.parameters);
        }
    }
    private static void lifecycle(Camera camera) {
        if (camera.claims == 0) {
            check(camera.releases == 0 && camera.closes == 0 && camera.commands.isEmpty(),
                    "A pre-USB validation rejection allocates no connection to clean up");
            return;
        }
        check(camera.closed && camera.claims == 1 && camera.releases == 1 && camera.closes == 1,
                "The actual driver releases and closes its claimed interface once");
    }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        try { action.run(); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected a rejected registration input or command");
    }

    /** Literal independent SDK-shaped bytes, with a synthetic linear LUT rather than a user's curve. */
    private static byte[] syntheticProfile(String name) {
        byte[] bytes = new byte[614];
        bytes[0] = 1;
        byte[] title = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, bytes, 2, title.length);
        byte[] parameters = {0, 0, 0, 12, 8, 0, 0, 0, 0, 0, 0, 0, 0, 1};
        System.arraycopy(parameters, 0, bytes, 22, parameters.length);
        bytes[36] = 0x49;
        bytes[37] = 0x30;
        for (int i = 0; i <= 256; i++) {
            int value = Math.min(i * 256, 65535);
            bytes[100 + i * 2] = (byte) (value >>> 8);
            bytes[101 + i * 2] = (byte) value;
        }
        return bytes;
    }

    private static final class Camera extends UsbDeviceConnection {
        final UsbDevice device = new UsbDevice();
        final UsbManager manager = new UsbManager(device, this);
        final List<Command> commands = new ArrayList<>();
        final List<byte[]> dataPayloads = new ArrayList<>();
        final Map<Integer, byte[]> slots = new LinkedHashMap<>();
        final Map<Integer, Integer> slotReads = new LinkedHashMap<>();
        byte[] incoming = new byte[0];
        byte[] expectedWrite = SNAPSHOT;
        int incomingOffset;
        int readChunk = 16384;
        int claims, releases, closes;
        boolean closed;
        boolean targetBecomesOccupied;
        boolean advertiseCapability, malformedCapability;
        boolean normalizeReadback;
        boolean cancelAfterReadback;
        int readbackResponseBoundary = -1;
        int writeResponseCode = 0x2001;
        int cleanupResponseCode = 0x2001;
        String wireFault = "";
        Command pendingWrite;
        Camera() {
            for (int item = 201; item <= 206; item++) slots.put(item, syntheticProfile(item == SOURCE ? "HOST COPY" : "KEEP C" + (item - 200)));
            for (int item = 207; item <= 209; item++) slots.put(item, new byte[36]);
        }
        Map<Integer, byte[]> snapshotSlots() {
            Map<Integer, byte[]> result = new LinkedHashMap<>();
            for (Map.Entry<Integer, byte[]> entry : slots.entrySet()) result.put(entry.getKey(), entry.getValue().clone());
            return result;
        }
        long count(int opcode) { return commands.stream().filter(c -> c.operation == opcode).count(); }
        @Override public boolean claimInterface(UsbInterface intf, boolean force) { check(!force, "Claim is unforced"); claims++; return true; }
        @Override public boolean releaseInterface(UsbInterface intf) { releases++; return true; }
        @Override public void close() { closes++; closed = true; }
        @Override public int bulkTransfer(UsbEndpoint endpoint, byte[] bytes, int length, int timeout) {
            return bulkTransfer(endpoint, bytes, 0, length, timeout);
        }
        @Override public int bulkTransfer(UsbEndpoint endpoint, byte[] bytes, int offset, int length, int timeout) {
            if (closed || timeout <= 0) throw new AssertionError("I/O after close or without timeout");
            if (endpoint.getDirection() == 128) {
                if (incomingOffset == incoming.length) return -1;
                int count = Math.min(Math.min(length, readChunk), incoming.length - incomingOffset);
                if (readbackResponseBoundary >= 0 && incomingOffset < readbackResponseBoundary) {
                    count = Math.min(count, readbackResponseBoundary - incomingOffset);
                }
                System.arraycopy(incoming, incomingOffset, bytes, offset, count);
                incomingOffset += count;
                if (readbackResponseBoundary >= 0 && incomingOffset == incoming.length) Thread.currentThread().interrupt();
                return count;
            }
            try {
                if (incomingOffset != incoming.length) throw new AssertionError("An earlier response was not consumed");
                PtpContainer container = new PtpContainerReader(new ByteArrayInputStream(
                        Arrays.copyOfRange(bytes, offset, offset + length)), 1024 * 1024).read();
                if (container.type() == PtpContainer.DATA) {
                    check(pendingWrite != null && container.code() == 0x90cd
                            && container.transactionId() == pendingWrite.transaction,
                            "The actual DATA phase matches the unique pending registration");
                    byte[] data = container.payload();
                    equal(expectedWrite, data);
                    dataPayloads.add(data.clone());
                    if (wireFault.equals("partial_data")) return length - 1;
                    byte[] stored = data.clone();
                    if (normalizeReadback) stored[2] ^= 1;
                    slots.put(TARGET, stored);
                    if (wireFault.equals("lost_write_response")) incoming = new byte[0];
                    else incoming = PtpContainer.response(writeResponseCode, pendingWrite.transaction).toBytes();
                    incomingOffset = 0;
                    pendingWrite = null;
                    return length;
                }
                equal(PtpContainer.COMMAND, container.type());
                Command command = new Command(container.code(), container.transactionId(), container.parameters());
                commands.add(command);
                if (command.operation == 0x90cd) {
                    equal(new long[] {TARGET, 0}, command.parameters);
                    pendingWrite = command;
                    return length;
                }
                queueRead(command);
                return length;
            } catch (IOException error) { throw new AssertionError("Invalid actual USB container", error); }
        }
        private void queueRead(Command command) throws IOException {
            byte[] data = null;
            int response = 0x2001;
            long responseTransaction = command.transaction;
            boolean finalReadback = false;
            switch (command.operation) {
                case 0x1001: data = deviceInfo(advertiseCapability); break;
                case 0x1002: equal(new long[] {1}, command.parameters); break;
                case 0x1003: equal(new long[0], command.parameters); response = cleanupResponseCode; break;
                case 0x90cf: equal(new long[] {1}, command.parameters); data = new byte[malformedCapability ? 29 : 28]; break;
                case 0x90cc:
                    equal(2, command.parameters.length);
                    equal(0L, command.parameters[1]);
                    int item = (int) command.parameters[0];
                    if (item < 201 || item > 209) throw new AssertionError("Unexpected slot read");
                    int readCount = slotReads.getOrDefault(item, 0) + 1;
                    slotReads.put(item, readCount);
                    if (targetBecomesOccupied && item == TARGET && readCount == 2) slots.put(TARGET, syntheticProfile("NEW OWNER"));
                    data = slots.get(item).clone();
                    finalReadback = item == TARGET && readCount == 3;
                    if (wireFault.equals("wrong_readback_transaction") && item == TARGET && readCount == 3) responseTransaction++;
                    break;
                default: throw new AssertionError("Unapproved actual copy command " + command.operation);
            }
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            if (data != null) stream.write(PtpContainer.data(command.operation, command.transaction, data).toBytes());
            stream.write(PtpContainer.response(response, responseTransaction).toBytes());
            incoming = stream.toByteArray();
            incomingOffset = 0;
            readbackResponseBoundary = cancelAfterReadback && finalReadback ? 12 + data.length : -1;
        }
    }
    private static byte[] deviceInfo(boolean capability) {
        int[] supported = capability ? new int[] {0x1001, 0x1002, 0x1003, 0x90cc, 0x90cd, 0x90cf}
                : new int[] {0x1001, 0x1002, 0x1003, 0x90cc, 0x90cd};
        Writer writer = new Writer().u16(100).u32(10).u16(100).string("").u16(0).u32(supported.length);
        for (int opcode : supported) writer.u16(opcode);
        return writer.u32(0).u32(0).u32(0).u32(0).string("Nikon").string("Z 6_2").string("V1.62").string("private").bytes();
    }
    private static final class Writer {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Writer u16(int value) { bytes.write(value); bytes.write(value >>> 8); return this; }
        Writer u32(long value) { for (int i = 0; i < 4; i++) bytes.write((int) (value >>> (8 * i))); return this; }
        Writer string(String value) {
            bytes.write(value.length() + 1);
            byte[] encoded = value.getBytes(StandardCharsets.UTF_16LE);
            bytes.write(encoded, 0, encoded.length);
            return u16(0);
        }
        byte[] bytes() { return bytes.toByteArray(); }
    }
    private static final class Command {
        final int operation;
        final long transaction;
        final long[] parameters;
        Command(int operation, long transaction, long[] parameters) {
            this.operation = operation;
            this.transaction = transaction;
            this.parameters = parameters.clone();
        }
    }
    private static void equal(Object expected, Object actual) {
        boolean same = expected instanceof byte[] && actual instanceof byte[]
                ? Arrays.equals((byte[]) expected, (byte[]) actual)
                : expected instanceof long[] && actual instanceof long[]
                ? Arrays.equals((long[]) expected, (long[]) actual) : java.util.Objects.equals(expected, actual);
        check(same, "Expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

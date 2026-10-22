package cc.huevia.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import cc.huevia.ptp.PtpContainer;
import cc.huevia.ptp.PtpContainerReader;
import cc.huevia.ptp.PtpObjectInfo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes the actual Android-facing UsbProbe and its private USB transport on a host JVM. */
public final class UsbProbeHostTest {
    private static final long SD = 0x00020001;
    private static final long ROOT = 0xffffffffL;
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("reproduce-014")) {
            reproduce014ThroughRealProbe();
            System.out.println("PASS: reproduced 0.1.4 null-unboxing failure through actual UsbProbe.probe; "
                    + checks + " checks, zero outbound USB commands");
            return;
        }
        ordinaryProbeRunsTheActualUsbTransport();
        directoryProbeRunsTheActualUsbTransport();
        errorsKeepNullableAndConcreteStorageContexts();
        directoryErrorsKeepOriginalParametersThroughCleanup();
        unsupportedSelectorsRecoverOnlyAfterMatchingResponses();
        System.out.println("PASS: " + checks + " production UsbProbe host integration checks");
    }

    private static void reproduce014ThroughRealProbe() throws Exception {
        Camera camera = new Camera();
        UsbProbe.ProbeException error = failedProbe(camera, false);
        Throwable current = error;
        NullPointerException reproduced = null;
        while (current != null) {
            if (current instanceof NullPointerException) reproduced = (NullPointerException) current;
            current = current.getCause();
        }
        check(reproduced != null, "The restored expression must fail by actual null unboxing");
        boolean inProductionExchange = false;
        for (StackTraceElement frame : reproduced.getStackTrace()) {
            if (frame.getClassName().equals("cc.huevia.usb.UsbProbe$Transport")
                    && frame.getMethodName().equals("exchange")) inProductionExchange = true;
        }
        check(inProductionExchange, "The failure originates in the actual production transport method");
        equal("GetDeviceInfo (0x1001)", error.partialResult.lastOperation);
        equal(0, camera.commands.size());
        check(camera.closed && camera.releaseCount == 1, "Even the original failure releases its claimed interface");
    }

    private static void ordinaryProbeRunsTheActualUsbTransport() throws Exception {
        Camera camera = new Camera();
        UsbProbe.Result result = probe(camera, false);
        check(result.completed && result.deviceInfoRead && result.storageIdsRead,
                "The actual ordinary probe passes through device, session and storage exchanges");
        check(result.sessionOpened && result.sessionClosed, "The probe owns and closes its session");
        equal("NIKON Z 6II", result.model);
        equal(2, result.storageCount);
        equal(1, result.availableStorageCount);
        equal(1, result.unavailableStorageCount);
        equal("not_mounted", result.storages.get(0).status);
        equal(2, result.storages.get(1).info.accessCapability());
        equal("not_requested", result.directoryInspectionStatus);
        equal(0, result.directorySurveys.size());
        equal(List.of(0x1001, 0x1002, 0x1004, 0x1005, 0x1003), camera.operationList());
        assertCommand(camera.commands.get(0), 0x1001, 0);
        assertCommand(camera.commands.get(1), 0x1002, 0, 1);
        assertCommand(camera.commands.get(2), 0x1004, 1);
        assertCommand(camera.commands.get(3), 0x1005, 2, SD);
        assertCommand(camera.commands.get(4), 0x1003, 3);
        equal("CloseSession (0x1003)", result.lastOperation);
        equal("", result.lastStorageId);
        equal(new long[0], result.lastParameters);
        assertLifecycleAndReadOnly(camera);
    }

    private static void directoryProbeRunsTheActualUsbTransport() throws Exception {
        Camera camera = new Camera();
        camera.readChunk = 7; // Fragment both PTP headers and datasets across bulk reads.
        UsbProbe.Result result = probe(camera, true);
        check(result.completed && result.sessionClosed && result.directoryInspectionRequested,
                "The real directory probe completes all exchanges and session cleanup");
        equal("completed", result.directoryInspectionStatus);
        equal(1, result.directorySurveys.size());
        PtpDirectorySurvey.Result survey = result.directorySurveys.get(0);
        check(survey.completed && survey.nikonRootVisible && survey.customPcPathVisible,
                "The actual transport delivers the differing root/all-association views to the scanner");
        equal(3, survey.metadataRequests);
        equal(List.of(0x1001, 0x1002, 0x1004, 0x1005, 0x1007, 0x1008, 0x1007,
                0x1008, 0x1008, 0x1003), camera.operationList());
        assertCommand(camera.commands.get(4), 0x1007, 3, SD, 0, ROOT);
        assertCommand(camera.commands.get(5), 0x1008, 4, 1);
        assertCommand(camera.commands.get(6), 0x1007, 5, SD, 0x3001, 0);
        assertCommand(camera.commands.get(7), 0x1008, 6, 2);
        assertCommand(camera.commands.get(8), 0x1008, 7, 3);
        assertCommand(camera.commands.get(9), 0x1003, 8);
        equal("", result.lastStorageId);
        equal(new long[0], result.lastParameters);
        check(result.toDisplayText().contains("NIKON/CUSTOMPC"), "The real result can render without Android JSON calls");
        assertLifecycleAndReadOnly(camera);
    }

    private static void errorsKeepNullableAndConcreteStorageContexts() throws Exception {
        Camera noStorage = new Camera();
        noStorage.operationErrors.put(0x1001, 0x2019);
        UsbProbe.ProbeException deviceError = failedProbe(noStorage, false);
        assertFailureContext(deviceError, 0x1001, 0, null, new long[0], 0x2019);
        check(!deviceError.partialResult.sessionOpened, "A failed GetDeviceInfo never acquires a PTP session");
        equal(1, noStorage.commands.size());
        assertLifecycleAndReadOnly(noStorage);

        Camera storage = new Camera();
        storage.operationErrors.put(0x1005, 0x2019);
        storage.operationErrors.put(0x1003, 0x2002); // Cleanup also fails, preserving the primary query error.
        UsbProbe.ProbeException storageError = failedProbe(storage, false);
        assertFailureContext(storageError, 0x1005, 2, SD, new long[] {SD}, 0x2019);
        check(storageError.partialResult.sessionOpened && !storageError.partialResult.sessionClosed,
                "A separate cleanup failure remains unconfirmed without replacing the primary failure");
        check(!storageError.partialResult.protocolPhaseUnconfirmed,
                "Matching negative responses are confirmed protocol phases");
        assertCommand(storage.commands.get(storage.commands.size() - 1), 0x1003, 3);
        check(String.join("\n", storageError.partialResult.diagnostics).contains("关闭 PTP 会话未获确认"),
                "The cleanup failure is retained as separate diagnostic evidence");
        assertLifecycleAndReadOnly(storage);
    }

    private static void directoryErrorsKeepOriginalParametersThroughCleanup() throws Exception {
        Camera selector = new Camera();
        selector.associationError = 0x2019;
        UsbProbe.ProbeException selectorError = failedProbe(selector, true);
        assertFailureContext(selectorError, 0x1007, 5, SD, new long[] {SD, 0x3001, 0}, 0x2019);
        equal("failed", selectorError.partialResult.directoryInspectionStatus);
        PtpDirectorySurvey.Result partial = selectorError.partialResult.directorySurveys.get(0);
        equal("complete", partial.queries.get(0).status);
        equal("error", partial.queries.get(1).status);
        equal(0x2019, partial.queries.get(1).responseCode);
        check(selectorError.partialResult.sessionClosed, "A matching failed directory response still permits owned cleanup");
        assertCommand(selector.commands.get(selector.commands.size() - 1), 0x1003, 6);
        equal(0, selector.fallbackCount);
        assertLifecycleAndReadOnly(selector);

        Camera object = new Camera();
        object.failedObjectHandle = 2;
        object.objectError = 0x200f;
        UsbProbe.ProbeException objectError = failedProbe(object, true);
        assertFailureContext(objectError, 0x1008, 6, SD, new long[] {2}, 0x200f);
        equal("failed", objectError.partialResult.directoryInspectionStatus);
        check(objectError.partialResult.sessionClosed,
                "GetObjectInfo's boxed survey storage context survives resetting the adapter and closing the session");
        assertCommand(object.commands.get(object.commands.size() - 1), 0x1003, 7);
        assertLifecycleAndReadOnly(object);
    }

    private static void unsupportedSelectorsRecoverOnlyAfterMatchingResponses() throws Exception {
        for (int response : new int[] {0x2006, 0x2014}) {
            Camera camera = new Camera();
            camera.associationError = response;
            UsbProbe.Result result = probe(camera, true);
            check(result.completed && result.sessionClosed, "A validated unsupported selector permits bounded recovery");
            equal("partial", result.directoryInspectionStatus);
            equal(1, camera.fallbackCount);
            PtpDirectorySurvey.Result survey = result.directorySurveys.get(0);
            equal(3, survey.queries.size());
            equal("unsupported", survey.queries.get(1).status);
            equal(response, survey.queries.get(1).responseCode);
            equal("complete", survey.queries.get(2).status);
            check(survey.customPcPathVisible, "The fallback transports and verifies directory metadata normally");
            assertCommand(camera.commands.get(7), 0x1007, 6, SD, 0, 0);
            assertCommand(camera.commands.get(camera.commands.size() - 1), 0x1003, 9);
            assertLifecycleAndReadOnly(camera);
        }
        Camera wrongTransaction = new Camera();
        wrongTransaction.associationError = 0x2006;
        wrongTransaction.wrongAssociationTransaction = true;
        UsbProbe.ProbeException failure = failedProbe(wrongTransaction, true);
        assertFailureContext(failure, 0x1007, 5, SD, new long[] {SD, 0x3001, 0}, null);
        equal(0, wrongTransaction.fallbackCount);
        equal(0L, wrongTransaction.countOperation(0x1003));
        check(failure.partialResult.protocolPhaseUnconfirmed && !failure.partialResult.sessionClosed,
                "An unsupported code with a mismatched transaction is invalidated and never recovered or cleaned up over PTP");
        assertLifecycleAndReadOnly(wrongTransaction);
    }

    private static UsbProbe.Result probe(Camera camera, boolean directories) throws Exception {
        return new UsbProbe().probe(camera.device, camera.manager, directories);
    }

    private static UsbProbe.ProbeException failedProbe(Camera camera, boolean directories) throws Exception {
        try { probe(camera, directories); }
        catch (UsbProbe.ProbeException expected) { checks++; return expected; }
        throw new AssertionError("Expected actual production probe failure");
    }

    private static void assertFailureContext(UsbProbe.ProbeException error, int operation, long transaction,
                                            Long storage, long[] parameters, Integer response) {
        equal(operation, error.operationCode);
        equal(transaction, error.transactionId);
        equal(storage, error.storageId);
        equal(response, error.responseCode);
        equal(transaction, error.partialResult.lastTransactionId);
        equal(parameters, error.partialResult.lastParameters);
        equal(storage == null ? "" : String.format(java.util.Locale.ROOT, "0x%08X", storage),
                error.partialResult.lastStorageId);
        check(error.partialResult.lastOperation.contains(String.format(java.util.Locale.ROOT, "0x%04X", operation)),
                "Cleanup cannot overwrite the failed operation in the actual report");
        check(error.getMessage().contains("transaction=" + transaction), "The exception retains its requested transaction");
        check(error.getMessage().contains("parameters=["), "The exception retains the original operation parameters");
        check(!error.partialResult.completed && !error.partialResult.error.isEmpty(), "A failed probe remains failed");
    }

    private static void assertCommand(Command command, int operation, long transaction, long... parameters) {
        equal(operation, command.operation);
        equal(transaction, command.transaction);
        equal(parameters, command.parameters);
    }

    private static void assertLifecycleAndReadOnly(Camera camera) {
        check(camera.closed && camera.claimCount == 1 && camera.releaseCount == 1,
                "The actual probe releases its claimed interface and closes its USB connection exactly once");
        equal(1, camera.closeCount);
        for (Command command : camera.commands) {
            check(command.operation == 0x1001 || command.operation == 0x1002 || command.operation == 0x1003
                            || command.operation == 0x1004 || command.operation == 0x1005
                            || command.operation == 0x1007 || command.operation == 0x1008,
                    "No vendor, upload, property write or GetObject content command is sent");
        }
    }

    private static final class Camera extends UsbDeviceConnection {
        final UsbDevice device = new UsbDevice();
        final UsbManager manager = new UsbManager(device, this);
        final List<Command> commands = new ArrayList<>();
        final Map<Integer, Integer> operationErrors = new LinkedHashMap<>();
        byte[] incoming = new byte[0];
        int readOffset;
        int readChunk = 16384;
        int claimCount;
        int releaseCount;
        int closeCount;
        int fallbackCount;
        int associationError;
        long failedObjectHandle = -1;
        int objectError;
        boolean wrongAssociationTransaction;
        boolean closed;

        @Override public boolean claimInterface(UsbInterface intf, boolean force) {
            check(!force && !closed, "The probe requests an unforced live interface claim");
            claimCount++;
            return true;
        }
        @Override public boolean releaseInterface(UsbInterface intf) { releaseCount++; return true; }
        @Override public void close() { closeCount++; closed = true; }
        @Override public int bulkTransfer(UsbEndpoint endpoint, byte[] bytes, int length, int timeout) {
            return bulkTransfer(endpoint, bytes, 0, length, timeout);
        }
        @Override public int bulkTransfer(UsbEndpoint endpoint, byte[] bytes, int offset, int length, int timeout) {
            check(!closed && timeout > 0, "Bulk I/O uses a live connection and bounded timeout");
            if (endpoint.getDirection() == 128) {
                if (readOffset == incoming.length) throw new AssertionError("Unexpected USB read with no queued response");
                int count = Math.min(Math.min(length, readChunk), incoming.length - readOffset);
                System.arraycopy(incoming, readOffset, bytes, offset, count);
                readOffset += count;
                return count;
            }
            try {
                check(readOffset == incoming.length, "The preceding response is consumed before another USB command");
                byte[] wire = Arrays.copyOfRange(bytes, offset, offset + length);
                PtpContainer container = new PtpContainerReader(new ByteArrayInputStream(wire), 1024).read();
                equal(1, container.type());
                Command command = new Command(container.code(), container.transactionId(), container.parameters());
                commands.add(command);
                queueResponse(command);
                return length;
            } catch (IOException error) {
                throw new AssertionError("Invalid production USB command", error);
            }
        }

        long countOperation(int operation) { return commands.stream().filter(c -> c.operation == operation).count(); }
        List<Integer> operationList() {
            List<Integer> result = new ArrayList<>();
            for (Command command : commands) result.add(command.operation);
            return result;
        }

        private void queueResponse(Command command) throws IOException {
            int response = operationErrors.getOrDefault(command.operation, 0x2001);
            byte[] data = null;
            long responseTransaction = command.transaction;
            switch (command.operation) {
                case 0x1001: data = deviceInfo(); break;
                case 0x1002: equal(new long[] {1}, command.parameters); break;
                case 0x1003: equal(new long[0], command.parameters); break;
                case 0x1004: data = handles(0x00010000, SD); break;
                case 0x1005:
                    equal(new long[] {SD}, command.parameters);
                    data = new Writer().u16(4).u16(3).u16(2).u64(64000000000L).u64(62000000000L)
                            .u32(999).string("SDXC Card").string("SD").bytes();
                    break;
                case 0x1007:
                    equal(3, command.parameters.length);
                    equal(SD, command.parameters[0]);
                    if (command.parameters[1] == 0 && command.parameters[2] == ROOT) data = handles(1);
                    else if (command.parameters[1] == 0x3001 && command.parameters[2] == 0) {
                        data = handles(1, 2, 3);
                        if (associationError != 0) response = associationError;
                        if (wrongAssociationTransaction) responseTransaction++;
                    } else if (command.parameters[1] == 0 && command.parameters[2] == 0) {
                        fallbackCount++;
                        data = handles(1, 2, 3);
                    } else throw new AssertionError("Unexpected directory selector");
                    break;
                case 0x1008:
                    equal(1, command.parameters.length);
                    long handle = command.parameters[0];
                    if (handle == failedObjectHandle) response = objectError;
                    if (handle == 1) data = PtpObjectInfo.folder(SD, 0, "DCIM").toBytes();
                    else if (handle == 2) data = PtpObjectInfo.folder(SD, 0, "NIKON").toBytes();
                    else if (handle == 3) data = PtpObjectInfo.folder(SD, 2, "CUSTOMPC").toBytes();
                    else throw new AssertionError("Unexpected object handle");
                    break;
                default: throw new AssertionError("Production probe sent a forbidden opcode " + command.operation);
            }
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            if (data != null && response == 0x2001) stream.write(PtpContainer.data(command.operation,
                    command.transaction, data).toBytes());
            stream.write(PtpContainer.response(response, responseTransaction).toBytes());
            incoming = stream.toByteArray();
            readOffset = 0;
        }
    }

    private static byte[] deviceInfo() {
        int[] operations = {0x1001, 0x1002, 0x1003, 0x1004, 0x1005, 0x1007, 0x1008, 0x100c, 0x100d, 0x90cc};
        Writer writer = new Writer().u16(100).u32(10).u16(100).string("").u16(0).u32(operations.length);
        for (int operation : operations) writer.u16(operation);
        return writer.u32(0).u32(0).u32(0).u32(0).string("Nikon").string("NIKON Z 6II")
                .string("1.62").string("do-not-retain-this-serial").bytes();
    }

    private static byte[] handles(long... handles) {
        Writer writer = new Writer().u32(handles.length);
        for (long handle : handles) writer.u32(handle);
        return writer.bytes();
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
        boolean same = expected instanceof long[] && actual instanceof long[]
                ? Arrays.equals((long[]) expected, (long[]) actual) : java.util.Objects.equals(expected, actual);
        check(same, "Expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}

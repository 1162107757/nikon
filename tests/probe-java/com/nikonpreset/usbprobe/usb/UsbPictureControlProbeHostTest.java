package com.nikonpreset.usbprobe.usb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import com.nikonpreset.usbprobe.ptp.PtpContainer;
import com.nikonpreset.usbprobe.ptp.PtpContainerReader;
import com.nikonpreset.usbprobe.ptp.PtpResponseException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exercises production PC dispatch, session planner and Android wrapper over scripted USB frames. */
public final class UsbPictureControlProbeHostTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        actualReadOnlyDispatcherRejectsWritesAndWrongParameters();
        actualWrapperReadsStandardAndAllCustomSlots();
        pureSessionSkipsOptionalEnumerationAndDoesNotBorrowSessions();
        advertisedModelAndOperationGuardsStopBeforeVendorCommands();
        matchedFailureRetainsPartialItemsAndOriginalContext();
        invalidDatasetsRetainRawEvidenceWithoutReadingFurtherSlots();
        invalidWirePhasesCloseWithoutAnyLaterCommand();
        System.out.println("PASS: " + checks + " production read-only Picture Control checks");
    }

    private static void actualReadOnlyDispatcherRejectsWritesAndWrongParameters() throws Exception {
        Camera camera = new Camera();
        PtpUsbTransport transport = new PtpUsbTransport(camera.device, camera.manager, new ArrayList<>(), true);
        for (int operation : new int[] {0x100c, 0x100d, 0x1016, 0x90c2, 0x90cd, 0x90ce, 0x9fff}) {
            rejects(() -> transport.sendData(operation, 0, new byte[] {1, 2, 3}, 201));
            rejects(() -> transport.getData(operation, 0));
            rejects(() -> transport.command(operation, 0));
        }
        for (int operation : new int[] {0x1004, 0x1005, 0x1007, 0x1008, 0x1009}) {
            rejects(() -> transport.getData(operation, 0));
        }
        rejects(() -> transport.getData(0x1001, 0, 0));
        rejects(() -> transport.getData(0x90ca, 0, 0));
        rejects(() -> transport.getData(0x90cf, 0));
        rejects(() -> transport.getData(0x90cf, 0, 201));
        rejects(() -> transport.getData(0x90cc, 0, 1));
        rejects(() -> transport.getData(0x90cc, 0, 1, 1));
        rejects(() -> transport.getData(0x90cc, 0, 200, 0));
        rejects(() -> transport.getData(0x90cc, 0, 210, 0));
        rejects(() -> transport.command(0x1002, 0));
        rejects(() -> transport.command(0x1002, 0, 2));
        rejects(() -> transport.command(0x1003, 0, 0));
        equal(0, camera.commands.size());
        check(!transport.isClosed(), "Local read-only policy refusals do not pretend any USB request occurred");
        transport.close();
        lifecycle(camera);

        Camera standard = new Camera();
        PtpUsbTransport ordinary = new PtpUsbTransport(standard.device, standard.manager, new ArrayList<>());
        rejects(() -> ordinary.getData(0x90ca, 0));
        equal(0, standard.commands.size());
        ordinary.close();
        lifecycle(standard);
    }

    private static void actualWrapperReadsStandardAndAllCustomSlots() throws Exception {
        Camera camera = new Camera();
        camera.readChunk = 7;
        UsbPictureControlProbe.Result result = probe(camera);
        check(result.completed && result.deviceInfoRead && result.sessionOpened && result.sessionClosed,
                "The actual wrapper completes the owned-session read flow");
        check(result.vendorPropertiesRead && result.capabilityRead && result.capabilityParsed,
                "Advertised vendor enumeration and the standard capability dataset are independently confirmed");
        equal(28, result.standardCapabilityRawBytes.length);
        equal(64, result.standardCapabilitySha256.length());
        equal(10, result.items.size());
        equal(1, result.items.get(0).itemCode);
        equal(36, result.items.get(0).byteLength);
        equal("STANDARD", result.items.get(0).registrationName);
        check(result.items.get(1).empty && result.items.get(1).basePictureControl == 0,
                "An empty C1 slot is a base-zero result and does not trigger an invented capability query");
        check(result.items.get(2).parsed && result.items.get(2).customCurveEnabled,
                "A 614-byte custom dataset is retained and structurally decoded");
        equal(614, result.items.get(2).byteLength);
        for (int slot = 201; slot <= 209; slot++) equal(slot, result.items.get(slot - 200).itemCode);
        equal(1L, camera.count(0x90cf));
        equal(10L, camera.count(0x90cc));
        assertCommand(camera.commands.get(0), 0x1001, 0);
        assertCommand(camera.commands.get(1), 0x1002, 0, 1);
        assertCommand(camera.commands.get(2), 0x90ca, 1);
        assertCommand(camera.commands.get(3), 0x90cf, 2, 1);
        assertCommand(camera.commands.get(4), 0x90cc, 3, 1, 0);
        assertCommand(camera.commands.get(5), 0x90cc, 4, 201, 0);
        assertCommand(camera.commands.get(camera.commands.size() - 1), 0x1003, 13);
        byte[] original = result.items.get(2).rawBytes();
        byte[] changed = result.items.get(2).rawBytes();
        changed[0] ^= 1;
        equal(original, result.items.get(2).rawBytes());
        check(result.toDisplayText().contains("C1"), "The report can render without executing Android JSON stubs");
        readOnly(camera);
        lifecycle(camera);
    }

    private static void pureSessionSkipsOptionalEnumerationAndDoesNotBorrowSessions() throws Exception {
        MemoryOperations operations = new MemoryOperations(false, false);
        UsbPictureControlProbe.Result result = new UsbPictureControlProbe.Result();
        PictureControlReadSession session = new PictureControlReadSession(operations, result);
        session.run();
        check(session.ownsSession() && result.sessionOpened, "The pure production planner owns a newly opened session");
        check(!result.vendorPropertiesRead && result.items.size() == 10,
                "Absent optional enumeration does not prevent the explicitly advertised required reads");
        equal(0L, operations.commands.stream().filter(c -> c.operation == 0x90ca).count());
        session.closeSession();
        check(!session.ownsSession() && result.sessionClosed, "Owned cleanup is explicit and cannot be repeated");
        session.closeSession();
        equal(1L, operations.commands.stream().filter(c -> c.operation == 0x1003).count());

        MemoryOperations borrowed = new MemoryOperations(true, true);
        UsbPictureControlProbe.Result foreignResult = new UsbPictureControlProbe.Result();
        PictureControlReadSession foreign = new PictureControlReadSession(borrowed, foreignResult);
        try {
            foreign.run();
            throw new AssertionError("Expected SessionAlreadyOpen");
        } catch (PtpResponseException expected) { equal(0x201e, expected.responseCode()); }
        check(!foreign.ownsSession() && !foreignResult.sessionOpened, "Another application's session is never borrowed");
        foreign.closeSession();
        equal(0L, borrowed.commands.stream().filter(c -> c.operation == 0x1003).count());
    }

    private static void advertisedModelAndOperationGuardsStopBeforeVendorCommands() throws Exception {
        for (int missing : new int[] {0x90cc, 0x90cf}) {
            Camera camera = new Camera();
            camera.missingOperation = missing;
            UsbPictureControlProbe.ProbeException error = failed(camera);
            check(!error.partialResult.sessionOpened, "A missing required declaration stops before opening a session");
            equal(1, camera.commands.size());
            lifecycle(camera);
        }
        Camera wrongModel = new Camera();
        wrongModel.model = "NIKON Z 6";
        failed(wrongModel);
        equal(1, wrongModel.commands.size());
        lifecycle(wrongModel);
    }

    private static void matchedFailureRetainsPartialItemsAndOriginalContext() throws Exception {
        Camera camera = new Camera();
        camera.failedItem = 202;
        camera.errorCode = 0x2019;
        camera.cleanupError = 0x2002;
        UsbPictureControlProbe.ProbeException error = failed(camera);
        UsbPictureControlProbe.Result result = error.partialResult;
        equal(0x2019, error.responseCode);
        check(result.capabilityParsed && result.items.size() == 2, "A failed C2 read preserves standard data and empty C1 evidence");
        equal(202, result.failedItemCode);
        equal(5L, result.failedTransactionId);
        equal(new long[] {202, 0}, result.lastParameters);
        check(result.failedOperation.contains("90CC") && result.lastOperation.contains("90CC"),
                "Cleanup cannot replace the failed PC command or original selector parameters");
        equal(0x2002, result.cleanupResponseCode);
        check(!result.cleanupError.isEmpty() && !result.sessionClosed,
                "A separate matching cleanup failure remains diagnostic evidence");
        check(!result.completed && !result.protocolPhaseUnconfirmed,
                "A matching negative camera response is a confirmed boundary, not a successful result");
        assertCommand(camera.commands.get(camera.commands.size() - 1), 0x1003, 6);
        equal(3L, camera.count(0x90cc));
        readOnly(camera);
        lifecycle(camera);
    }

    private static void invalidDatasetsRetainRawEvidenceWithoutReadingFurtherSlots() throws Exception {
        Camera capability = new Camera();
        capability.capabilityLength = 27;
        UsbPictureControlProbe.Result capResult = failed(capability).partialResult;
        check(capResult.capabilityRead && !capResult.capabilityParsed,
                "A matched read with malformed capability bytes is distinguishable from no capability response");
        equal(27, capResult.standardCapabilityRawBytes.length);
        equal(0, capResult.items.size());
        equal(0L, capability.count(0x90cc));
        check(capResult.sessionClosed && !capResult.protocolPhaseUnconfirmed,
                "A semantic capability rejection after matching DATA+OK preserves the protocol boundary for owned cleanup");
        assertCommand(capability.commands.get(capability.commands.size() - 1), 0x1003, 3);
        readOnly(capability);
        lifecycle(capability);

        Camera profile = new Camera();
        profile.malformedItem = 201;
        UsbPictureControlProbe.Result profileResult = failed(profile).partialResult;
        equal(2, profileResult.items.size());
        check(profileResult.items.get(0).parsed && !profileResult.items.get(1).parsed,
                "The invalid raw item is retained without granting a parsed or compatible state");
        equal(37, profileResult.items.get(1).rawBytes().length);
        equal(201, profileResult.lastItemCode);
        equal("PictureControlDataParse", profileResult.failurePhase);
        equal(2L, profile.count(0x90cc));
        check(profileResult.sessionClosed && !profileResult.protocolPhaseUnconfirmed,
                "A semantic profile rejection stops slot reads while permitting the owned session to close");
        assertCommand(profile.commands.get(profile.commands.size() - 1), 0x1003, 5);
        readOnly(profile);
        lifecycle(profile);
    }

    private static void invalidWirePhasesCloseWithoutAnyLaterCommand() throws Exception {
        for (String fault : new String[] {"wrong_data_operation", "wrong_response_transaction", "oversized_header", "missing_response", "partial_write"}) {
            Camera camera = new Camera();
            camera.wireFault = fault;
            UsbPictureControlProbe.Result result = failed(camera).partialResult;
            check(result.protocolPhaseUnconfirmed && !result.completed && !result.sessionClosed,
                    "An invalid or unfinished wire phase closes the actual transport");
            equal(0L, camera.count(0x1003));
            equal(0, result.items.size());
            Command last = camera.commands.get(camera.commands.size() - 1);
            equal(fault.equals("partial_write") ? 0x90cf : 0x90cc, last.operation);
            readOnly(camera);
            lifecycle(camera);
        }
    }

    private static UsbPictureControlProbe.Result probe(Camera camera) throws Exception {
        return new UsbPictureControlProbe().probe(camera.device, camera.manager);
    }
    private static UsbPictureControlProbe.ProbeException failed(Camera camera) throws Exception {
        try { probe(camera); }
        catch (UsbPictureControlProbe.ProbeException expected) { checks++; return expected; }
        throw new AssertionError("Expected a stopped production Picture Control probe");
    }
    private static void assertCommand(Command command, int operation, long transaction, long... parameters) {
        equal(operation, command.operation);
        equal(transaction, command.transaction);
        equal(parameters, command.parameters);
    }
    private static void readOnly(Camera camera) {
        for (Command command : camera.commands) {
            check(command.operation == 0x1001 || command.operation == 0x1002 || command.operation == 0x1003
                    || command.operation == 0x90ca || command.operation == 0x90cf || command.operation == 0x90cc,
                    "No file/storage query, content read, vendor write, mode change or slot registration leaves the wrapper");
            if (command.operation == 0x90ca) equal(new long[0], command.parameters);
            if (command.operation == 0x90cf) equal(new long[] {1}, command.parameters);
            if (command.operation == 0x90cc) {
                equal(2, command.parameters.length);
                equal(0L, command.parameters[1]);
                check(command.parameters[0] == 1 || (command.parameters[0] >= 201 && command.parameters[0] <= 209),
                        "Every Picture Control data selector stays in the documented read plan");
            }
        }
    }
    private static void lifecycle(Camera camera) {
        check(camera.closed && camera.claims == 1 && camera.releases == 1 && camera.closes == 1,
                "The real wrapper releases and closes its claimed USB interface exactly once");
    }

    private static final class MemoryOperations implements PictureControlReadSession.Operations {
        final List<Command> commands = new ArrayList<>();
        final boolean enumeration;
        final boolean alreadyOpen;
        MemoryOperations(boolean enumeration, boolean alreadyOpen) {
            this.enumeration = enumeration;
            this.alreadyOpen = alreadyOpen;
        }
        @Override public byte[] getData(int operation, long transaction, long... parameters) throws IOException {
            commands.add(new Command(operation, transaction, parameters));
            if (operation == 0x1001) return deviceInfo("NIKON Z 6II", enumeration ? -1 : 0x90ca);
            if (operation == 0x90cf) return new byte[28];
            if (operation == 0x90ca) return vendorProperties();
            if (operation == 0x90cc) return profile((int) parameters[0]);
            throw new AssertionError("Unexpected pure session operation");
        }
        @Override public PtpContainer command(int operation, long transaction, long... parameters) throws IOException {
            commands.add(new Command(operation, transaction, parameters));
            if (operation == 0x1002 && alreadyOpen) throw new PtpResponseException(0x201e, transaction);
            if (operation != 0x1002 && operation != 0x1003) throw new AssertionError("Unexpected session command");
            return PtpContainer.response(0x2001, transaction);
        }
    }

    private static final class Camera extends UsbDeviceConnection {
        final UsbDevice device = new UsbDevice();
        final UsbManager manager = new UsbManager(device, this);
        final List<Command> commands = new ArrayList<>();
        byte[] incoming = new byte[0];
        int incomingOffset;
        int readChunk = 16384;
        int claims;
        int releases;
        int closes;
        boolean closed;
        int missingOperation = -1;
        String model = "NIKON Z 6II";
        int failedItem = -1;
        int malformedItem = -1;
        int errorCode;
        int cleanupError;
        int capabilityLength = 28;
        String wireFault = "";
        @Override public boolean claimInterface(UsbInterface intf, boolean force) { check(!force, "Claim is unforced"); claims++; return true; }
        @Override public boolean releaseInterface(UsbInterface intf) { releases++; return true; }
        @Override public void close() { closes++; closed = true; }
        @Override public int bulkTransfer(UsbEndpoint endpoint, byte[] bytes, int length, int timeout) {
            return bulkTransfer(endpoint, bytes, 0, length, timeout);
        }
        @Override public int bulkTransfer(UsbEndpoint endpoint, byte[] bytes, int offset, int length, int timeout) {
            if (closed || timeout <= 0) throw new AssertionError("I/O after close or without a timeout");
            if (endpoint.getDirection() == 128) {
                if (incomingOffset == incoming.length) return -1;
                int count = Math.min(Math.min(length, readChunk), incoming.length - incomingOffset);
                System.arraycopy(incoming, incomingOffset, bytes, offset, count);
                incomingOffset += count;
                return count;
            }
            try {
                if (incomingOffset != incoming.length) throw new AssertionError("Command sent before the preceding response was consumed");
                PtpContainer container = new PtpContainerReader(new ByteArrayInputStream(
                        Arrays.copyOfRange(bytes, offset, offset + length)), 1024).read();
                equal(PtpContainer.COMMAND, container.type());
                Command command = new Command(container.code(), container.transactionId(), container.parameters());
                commands.add(command);
                if (wireFault.equals("partial_write") && command.operation == 0x90cf) return length - 1;
                queue(command);
                return length;
            } catch (IOException error) { throw new AssertionError("Invalid actual USB command", error); }
        }
        long count(int operation) { return commands.stream().filter(c -> c.operation == operation).count(); }
        private void queue(Command command) throws IOException {
            byte[] data;
            int response = 0x2001;
            switch (command.operation) {
                case 0x1001: data = deviceInfo(model, missingOperation); break;
                case 0x1002: equal(new long[] {1}, command.parameters); data = null; break;
                case 0x1003: equal(new long[0], command.parameters); data = null; if (cleanupError != 0) response = cleanupError; break;
                case 0x90ca: equal(new long[0], command.parameters); data = vendorProperties(); break;
                case 0x90cf: equal(new long[] {1}, command.parameters); data = new byte[capabilityLength]; break;
                case 0x90cc:
                    equal(2, command.parameters.length);
                    equal(0L, command.parameters[1]);
                    int item = (int) command.parameters[0];
                    data = item == malformedItem ? new byte[37] : profile(item);
                    if (item == failedItem) response = errorCode;
                    break;
                default: throw new AssertionError("Actual PC wrapper emitted an unapproved operation " + command.operation);
            }
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            boolean faultyCurrent = command.operation == 0x90cc && command.parameters[0] == 1;
            if (faultyCurrent && wireFault.equals("oversized_header")) {
                stream.write(new Writer().u32(1024 * 1024 + 13).u16(2).u16(0x90cc).u32(command.transaction).bytes());
            } else {
                if (data != null && response == 0x2001) stream.write(PtpContainer.data(
                        faultyCurrent && wireFault.equals("wrong_data_operation") ? 0x90cf : command.operation,
                        command.transaction, data).toBytes());
                if (!(faultyCurrent && wireFault.equals("missing_response"))) stream.write(PtpContainer.response(response,
                        command.transaction + (faultyCurrent && wireFault.equals("wrong_response_transaction") ? 1 : 0)).toBytes());
            }
            incoming = stream.toByteArray();
            incomingOffset = 0;
        }
    }

    private static byte[] deviceInfo(String model, int missing) {
        int[] supported = {0x1001, 0x1002, 0x1003, 0x90ca, 0x90cf, 0x90cc};
        int count = supported.length;
        for (int operation : supported) if (operation == missing) count--;
        Writer writer = new Writer().u16(100).u32(10).u16(100).string("").u16(0).u32(count);
        for (int operation : supported) if (operation != missing) writer.u16(operation);
        return writer.u32(0).u32(0).u32(0).u32(0).string("Nikon").string(model).string("1.62")
                .string("serial-must-not-be-collected").bytes();
    }
    private static byte[] vendorProperties() { return new Writer().u32(2).u16(0xd000).u16(0xd001).bytes(); }
    private static byte[] profile(int item) {
        byte[] bytes = new byte[item == 202 ? 614 : 36];
        bytes[0] = (byte) (item == 201 ? 0 : 1);
        String name = item == 1 ? "STANDARD" : item == 201 ? "" : "C" + (item - 200);
        byte[] encoded = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, bytes, 2, encoded.length);
        bytes[35] = (byte) (item == 202 ? 1 : 0);
        return bytes;
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
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        try { action.run(); }
        catch (IOException expected) { checks++; return; }
        throw new AssertionError("Expected production read-only policy refusal");
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

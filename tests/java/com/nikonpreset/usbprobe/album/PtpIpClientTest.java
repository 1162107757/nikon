package com.nikonpreset.usbprobe.album;

import com.nikonpreset.usbprobe.ptp.PtpContainer;
import com.nikonpreset.usbprobe.ptp.PtpResponseException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic TCP peers; no Android runtime, camera, private SDK, or photographs. */
public final class PtpIpClientTest {
    private static final byte[] GUID = {0, 1, 2, 3, 4, 5, 6, 7,
            8, 9, 10, 11, 12, 13, (byte) 0xfe, (byte) 0xff};
    private static final String FRIENDLY_NAME = "Preset \u6d4b\u8bd5";
    private static final byte[] INFO = {1, 4, 9, 16, 25};
    private static final byte[] SMALL = {9, 8, 7, 6, 5, 4, 3};
    private static final long CONNECTION_NUMBER = 0x89abcdefL;
    private static int checks;

    public static void main(String[] args) throws Exception {
        handshakeAndFragmentedTransfers();
        rejectedWritesNeverReachTheSocket();
        largeThumbnailIsBoundedReadonlyAndCannotCrossModes();
        handshakeFailuresReleaseEveryCreatedSocket();
        framingAndTransactionFailuresCloseBothChannels();
        remoteLengthsAreBoundedWithoutTruncation();
        streamingDoesNotBufferTheCompleteObject();
        failedOutputClosesBothChannels();
        cancelledClientCannotOpenSockets();
        completeCameraErrorsKeepTheBoundaryReusable();
        pictureControlConnectionsAreIsolatedAndSessionOwned();
        pictureControlReadsAreStrictlyBounded();
        pictureControlSessionResponsesKeepTheirBoundaries();
        pictureControlWriteFramesAndSingleAttempt();
        pictureControlFailuresNeverRetry();
        pictureControlCancellationClosesBothChannels();
        albumHandoffMovesChannelsAndTransactionOwnershipExactlyOnce();
        transferredEventFailuresCloseTheCurrentOwner();
        cancelledAndInvalidHandoffsCannotCreateAnotherOwner();
        obsoleteAlbumCloseCannotCancelTheNewOwner();
        handoffWaitsForTheAlbumTransactionBoundaryAndHonorsCancellation();
        transferredEventReaderStillAnswersCameraPing();
        verifiedRegistrationTransfersOneFreshAttemptWithoutClosingChannels();
        unverifiedRegistrationCannotRefreshItsWriteBudget();
        registrationProofCannotCrossAClosedAndReopenedSession();
        pictureControlCanReturnToAReadOnlyAlbumWithoutReopening();
        pictureControlWriteAttemptsCannotBeLaunderedThroughAlbumHandoff();
        reverseHandoffEventFailureAndCancellationCloseOnlyTheCurrentOwner();
        objectAddedEventsBelongOnlyToTheCurrentReadOnlyOwner();
        objectAddedListenerFailuresCannotKillThePhysicalEventReader();
        malformedObjectAddedEventsCloseTheCurrentOwner();
        connectionTraceHooksNeverExposeCameraData();
        System.out.println("PASS: " + checks + " PTP/IP client checks");
    }

    private static void connectionTraceHooksNeverExposeCameraData() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("huevia-ptp-trace-");
        var file = directory.resolve("connection-trace.log");
        ConnectionTrace.configure(file.toFile());
        try {
            Peer peer = new Peer(); PtpIpClient client = peer.connect();
            equal(SMALL, client.getData(0x100a, 64, 0x12345678L));
            peer.sockets.get(1).input.add(frame(13, new byte[0]));
            check(peer.pong.await(5, TimeUnit.SECONDS), "Trace instrumentation preserves the actual event ping response");
            peer.fault = Fault.ERROR_RESPONSE;
            rejects(() -> client.getData(0x1004, 64));
            check(client.isOpen(), "A traced complete rejection still preserves the reusable protocol boundary");
            peer.fault = Fault.NONE;
            rejects(() -> client.readTo(0x1009, new OutputStream() {
                @Override public void write(int value) throws IOException { throw new IOException("private-file.JPG secret-token"); }
                @Override public void write(byte[] bytes, int start, int length) throws IOException { throw new IOException("private-file.JPG secret-token"); }
            }, 64, SMALL.length, null, 0x12345678L));
            client.close(); client.close();

            Peer remote = new Peer(); PtpIpClient disconnected = remote.connect();
            CountDownLatch closed = new CountDownLatch(1); disconnected.setOnClosed(closed::countDown);
            remote.sockets.get(1).input.close();
            check(closed.await(5, TimeUnit.SECONDS), "An actual event EOF reaches the first-close trace hook");
            disconnected.close();
            Peer explicit = new Peer(); PtpIpClient released = explicit.connect(); released.close(); released.close();
            ConnectionTraceTest.flushForTest("TRACE_FLUSH");
            String evidence = java.nio.file.Files.readString(file);
            check(evidence.contains("event=PTP_OP_BEGIN") && evidence.contains("event=PTP_OP_COMPLETE")
                    && evidence.contains("event=PTP_OP_FAILED") && evidence.contains("op=0x100a tx="),
                    "Real command paths emit operation start, complete, failure and elapsed timing");
            check(evidence.contains("event=PTP_EVENT_PING_REPLY"), "The actual event reader records its successful ping reply");
            equal(3L, evidence.lines().filter(line -> line.contains("event=PTP_CLOSED ")).count());
            check(evidence.lines().anyMatch(line -> line.contains("event=PTP_CLOSED ") && line.endsWith("category=EOF"))
                    && evidence.lines().anyMatch(line -> line.contains("event=PTP_CLOSED ") && line.endsWith("category=IO"))
                    && evidence.lines().anyMatch(line -> line.contains("event=PTP_CLOSED ") && line.endsWith("category=EXPLICIT")),
                    "Only each physical connection's first close is recorded with its fixed cause category");
            check(!evidence.contains("private-file") && !evidence.contains("secret-token") && !evidence.contains("camera.test")
                    && !evidence.contains(FRIENDLY_NAME) && !evidence.contains("305419896") && !evidence.contains("12345678"),
                    "Actual PTP tracing excludes exception messages, host, friendly name, handles and payload");
        } finally {
            ConnectionTrace.configure(null);
            var writer = ConnectionTrace.class.getDeclaredField("WRITER"); writer.setAccessible(true);
            ((java.util.concurrent.ExecutorService) writer.get(null)).submit(() -> { }).get(10, TimeUnit.SECONDS);
            java.nio.file.Files.deleteIfExists(directory.resolve("connection-trace.log.tmp"));
            java.nio.file.Files.deleteIfExists(file); java.nio.file.Files.deleteIfExists(directory);
        }
    }

    private static void pictureControlConnectionsAreIsolatedAndSessionOwned() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = PtpIpClient.forPictureControl(peer::openSocket);
        rejects(() -> client.connect("camera.test", 15740, GUID, FRIENDLY_NAME));
        equal(0, peer.sockets.size());
        client.connectForPictureControl("camera.test", 15740, GUID, FRIENDLY_NAME);
        check(!client.isClosed() && !client.isOpen(), "Picture Control handshake leaves PTP session ownership to the caller");
        equal(0, peer.operations.size());
        rejects(() -> client.getData(0x1004, 64));
        rejects(() -> client.readTo(0x1009, new ByteArrayOutputStream(), 64, 1, null, 1));
        rejects(() -> client.sendPictureControlData(0x90cd, 1, profile(false), 201, 0));
        rejects(() -> client.getPictureControlData(0x90cc, 1, 201, 0));
        rejects(() -> client.pictureControlCommand(0x1002, 1, 1));
        rejects(() -> client.pictureControlCommand(0x1002, 0, 2));
        rejects(() -> client.pictureControlCommand(0x1003, 1));
        equal(0, peer.operations.size());
        equal(INFO, client.getPictureControlData(0x1001, 0));
        PtpContainer opened = client.pictureControlCommand(0x1002, 0, 1);
        equal(PtpContainer.RESPONSE, opened.type());
        equal(0x2001, opened.code());
        check(client.isOpen(), "An explicit successful OpenSession opens the bounded PTP session");
        int commands = peer.operations.size();
        rejects(() -> client.getPictureControlData(0x1001, 0));
        rejects(() -> client.pictureControlCommand(0x1002, 0, 1));
        equal(commands, peer.operations.size());
        equal(SMALL, client.getPictureControlData(0x90ca, 1));
        equal(SMALL, client.getPictureControlData(0x90cf, 2, 1));
        equal(SMALL, client.getPictureControlData(0x90cc, 3, 1, 0));
        equal(SMALL, client.getPictureControlData(0x90cc, 4, 209, 0));
        PtpContainer close = client.pictureControlCommand(0x1003, 5);
        equal(5L, close.transactionId());
        check(!client.isOpen() && !client.isClosed(), "CloseSession releases only the owned PTP session");
        client.close();
        peer.assertClosed();

        Peer album = new Peer();
        PtpIpClient readOnly = album.connect();
        commands = album.operations.size();
        rejects(() -> readOnly.connectForPictureControl("camera.test", 15740, GUID, FRIENDLY_NAME));
        rejects(() -> readOnly.getPictureControlData(0x90cc, 1, 201, 0));
        rejects(() -> readOnly.pictureControlCommand(0x1003, 1));
        rejects(() -> readOnly.sendPictureControlData(0x90cd, 1, profile(false), 201, 0));
        equal(commands, album.operations.size());
        equal(SMALL, readOnly.getData(0x1004, 64));
        readOnly.close();

        final int[] openedSockets = {0};
        PtpIpClient cancelled = PtpIpClient.forPictureControl((h, p, t) -> { openedSockets[0]++; return new Socket(); });
        cancelled.close();
        rejects(() -> cancelled.connectForPictureControl("camera.test", 15740, GUID, FRIENDLY_NAME));
        equal(0, openedSockets[0]);
    }

    private static void pictureControlReadsAreStrictlyBounded() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = peer.connectPictureControl();
        int commands = peer.operations.size();
        for (int operation : new int[] {0x1004, 0x1007, 0x1009, 0x1016, 0x90cd, 0x90ce, 0x9fff}) {
            rejects(() -> client.getPictureControlData(operation, 1, 201, 0));
            rejects(() -> client.pictureControlCommand(operation, 1, 201, 0));
        }
        rejects(() -> client.getPictureControlData(0x90cc, 1, 200, 0));
        rejects(() -> client.getPictureControlData(0x90cc, 1, 210, 0));
        rejects(() -> client.getPictureControlData(0x90cc, 1, 201, 1));
        rejects(() -> client.getPictureControlData(0x90cc, 1, 201));
        rejects(() -> client.getPictureControlData(0x90ca, 1, 0));
        rejects(() -> client.getPictureControlData(0x90cf, 1, 201));
        rejects(() -> client.getPictureControlData(0x90cf, -1, 1));
        rejects(() -> client.getPictureControlData(0x90cf, 0x100000000L, 1));
        rejects(() -> client.getPictureControlData(0x90cf, 0, 1));
        equal(commands, peer.operations.size());
        check(client.isOpen(), "Rejected local parameters leave the connection reusable");
        peer.fault = Fault.ERROR_RESPONSE;
        try {
            client.getPictureControlData(0x90cc, 1, 201, 0);
            throw new AssertionError("Expected camera rejection");
        } catch (PtpResponseException expected) { equal(1L, expected.transactionId()); }
        check(client.isOpen() && !client.isProtocolUnconfirmed(), "A matching read rejection preserves a confirmed boundary");
        peer.fault = Fault.RESPONSE_WRONG_TRANSACTION;
        rejects(() -> client.getPictureControlData(0x90cc, 2, 201, 0));
        check(client.isClosed() && client.isProtocolUnconfirmed(), "Wrong read response transaction aborts the protocol");
        peer.assertClosed();
    }

    private static void pictureControlWriteFramesAndSingleAttempt() throws Exception {
        for (boolean curve : new boolean[] {false, true}) {
            Peer peer = new Peer();
            PtpIpClient client = peer.connectPictureControl();
            byte[] data = profile(curve);
            int commands = peer.operations.size();
            rejects(() -> client.sendPictureControlData(0x100d, 1, data, 201, 0));
            rejects(() -> client.sendPictureControlData(0x90cd, 1, data, 200, 0));
            rejects(() -> client.sendPictureControlData(0x90cd, 1, data, 210, 0));
            rejects(() -> client.sendPictureControlData(0x90cd, 1, data, 201, 1));
            rejects(() -> client.sendPictureControlData(0x90cd, 1, data, 201));
            rejects(() -> client.sendPictureControlData(0x90cd, 1, null, 201, 0));
            byte[] invalid = data.clone(); invalid[0] = 0;
            rejects(() -> client.sendPictureControlData(0x90cd, 1, invalid, 201, 0));
            byte[] invalidField = data.clone(); invalidField[25] = 127;
            rejects(() -> client.sendPictureControlData(0x90cd, 1, invalidField, 201, 0));
            equal(commands, peer.operations.size());
            long transaction = 0x89abcdefL;
            int packetStart = peer.commandPackets.size();
            PtpContainer result = client.sendPictureControlData(0x90cd, transaction, data, 209, 0);
            equal(PtpContainer.RESPONSE, result.type());
            equal(0x2001, result.code());
            equal(transaction, result.transactionId());
            equal(new long[] {0xfedcba98L}, result.parameters());
            equal(3, peer.commandPackets.size() - packetStart);
            equal(frame(6, concat(le32(2), le16(0x90cd), le32(transaction), le32(209), le32(0))),
                    peer.commandPackets.get(packetStart));
            equal(frame(9, concat(le32(transaction), le64(data.length))), peer.commandPackets.get(packetStart + 1));
            equal(frame(12, concat(le32(transaction), data)), peer.commandPackets.get(packetStart + 2));
            equal(data, peer.uploaded.toByteArray());
            int packets = peer.commandPackets.size();
            rejects(() -> client.sendPictureControlData(0x90cd, transaction + 1, data, 208, 0));
            equal(packets, peer.commandPackets.size());
            equal(SMALL, client.getPictureControlData(0x90cc, transaction + 2, 209, 0));
            client.pictureControlCommand(0x1003, transaction + 3);
            client.close();
            peer.assertClosed();
        }
    }

    private static void pictureControlSessionResponsesKeepTheirBoundaries() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = PtpIpClient.forPictureControl(peer::openSocket);
        client.connectForPictureControl("camera.test", 15740, GUID, FRIENDLY_NAME);
        peer.fault = Fault.ERROR_RESPONSE;
        try {
            client.pictureControlCommand(0x1002, 0, 1);
            throw new AssertionError("Expected OpenSession refusal");
        } catch (PtpResponseException expected) { equal(0L, expected.transactionId()); }
        check(!client.isClosed() && !client.isOpen() && !client.isProtocolUnconfirmed(),
                "A refused OpenSession never grants session ownership");
        peer.fault = Fault.NONE;
        client.pictureControlCommand(0x1002, 0, 1);
        peer.fault = Fault.ERROR_RESPONSE;
        try {
            client.pictureControlCommand(0x1003, 1);
            throw new AssertionError("Expected CloseSession refusal");
        } catch (PtpResponseException expected) { equal(1L, expected.transactionId()); }
        check(client.isOpen() && !client.isProtocolUnconfirmed(), "A refused CloseSession leaves ownership with the caller");
        peer.fault = Fault.RESPONSE_WRONG_TRANSACTION;
        rejects(() -> client.pictureControlCommand(0x1003, 2));
        check(client.isClosed() && client.isProtocolUnconfirmed(), "An unconfirmed CloseSession closes the underlying connection");
        peer.assertClosed();
    }

    private static void pictureControlFailuresNeverRetry() throws Exception {
        for (Fault fault : new Fault[] {Fault.WRITE_REJECTED, Fault.WRITE_DROP, Fault.WRITE_START_FAILED,
                Fault.WRITE_TIMEOUT, Fault.WRITE_WRONG_TRANSACTION, Fault.WRITE_TRUNCATED_RESPONSE}) {
            Peer peer = new Peer();
            PtpIpClient client = peer.connectPictureControl();
            peer.fault = fault;
            try {
                client.sendPictureControlData(0x90cd, 1, profile(false), 201, 0);
                throw new AssertionError("Expected write failure: " + fault);
            } catch (PtpResponseException rejected) {
                check(fault == Fault.WRITE_REJECTED, "Only a matching refusal is a complete camera rejection");
                equal(0x200f, rejected.responseCode()); equal(1L, rejected.transactionId());
            } catch (IOException error) {
                check(fault != Fault.WRITE_REJECTED, "A complete refusal retains its response code");
            }
            int commands = peer.operations.size();
            equal(1L, peer.operations.stream().filter(op -> op == 0x90cd).count());
            rejects(() -> client.sendPictureControlData(0x90cd, 2, profile(false), 201, 0));
            equal(commands, peer.operations.size());
            if (fault == Fault.WRITE_REJECTED) {
                check(client.isOpen() && !client.isProtocolUnconfirmed(), "A refused write leaves a known response boundary");
                peer.fault = Fault.NONE;
                equal(SMALL, client.getPictureControlData(0x90cc, 3, 201, 0));
                client.pictureControlCommand(0x1003, 4);
                client.close();
            } else {
                check(client.isClosed() && client.isProtocolUnconfirmed(), "Uncertain writes close both channels: " + fault);
                rejects(() -> client.pictureControlCommand(0x1003, 3));
                equal(commands, peer.operations.size());
            }
            peer.assertClosed();
        }
    }

    private static void pictureControlCancellationClosesBothChannels() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = peer.connectPictureControl();
        peer.fault = Fault.WRITE_BLOCKED;
        final Throwable[] outcome = {null};
        Thread worker = new Thread(() -> {
            try { client.sendPictureControlData(0x90cd, 1, profile(false), 201, 0); }
            catch (Throwable error) { outcome[0] = error; }
        }, "synthetic-wireless-upload");
        worker.start();
        check(peer.writeEnded.await(5, TimeUnit.SECONDS), "Synthetic peer observed the complete upload before cancellation");
        client.close();
        worker.join(5000);
        check(!worker.isAlive() && outcome[0] instanceof IOException, "close interrupts an in-flight response wait");
        equal(1L, peer.operations.stream().filter(op -> op == 0x90cd).count());
        peer.assertClosed();

        Peer interruptedPeer = new Peer();
        PtpIpClient interrupted = interruptedPeer.connectPictureControl();
        int commands = interruptedPeer.operations.size();
        Thread.currentThread().interrupt();
        try {
            rejects(() -> interrupted.sendPictureControlData(0x90cd, 1, profile(false), 201, 0));
        } finally { Thread.interrupted(); }
        equal(commands, interruptedPeer.operations.size());
        check(interrupted.isClosed(), "A cancelled operation closes the connection before sending");
        interruptedPeer.assertClosed();
    }

    private static byte[] profile(boolean curve) {
        byte[] bytes = new byte[curve ? 614 : 36];
        bytes[0] = 1;
        byte[] name = "WIRELESS TEST".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, bytes, 2, name.length);
        if (curve) {
            bytes[35] = 1;
            for (int i = 36; i < bytes.length; i++) bytes[i] = (byte) (i * 13);
        }
        return bytes;
    }

    private static void albumHandoffMovesChannelsAndTransactionOwnershipExactlyOnce() throws Exception {
        Peer peer = new Peer();
        PtpIpClient album = peer.connect();
        equal(SMALL, album.getData(0x1004, 64));
        equal(SMALL, album.getData(0x1008, 64, 7));
        long lastAlbumTransaction = peer.lastTransaction;
        int before = peer.operations.size();
        PtpIpClient preset = album.transferToPictureControl();
        check(preset != album && preset.isOpen() && album.isClosed() && !album.isOpen(),
                "Handoff creates an explicit new owner and permanently retires the album instance");
        equal(before, peer.operations.size());
        equal(2, peer.sockets.size());
        equal("camera.test", preset.connectedHost());
        equal(15740, preset.connectedPort());
        rejects(() -> preset.getPictureControlData(0x90cc, 1, 201, 0));
        rejects(() -> preset.pictureControlCommand(0x1003, 1));
        rejects(() -> preset.sendPictureControlData(0x90cd, 1, profile(false), 201, 0));
        long next = preset.claimTransferredSession();
        check(next > lastAlbumTransaction, "The transferred session inherits the next nonzero album transaction");
        rejects(preset::claimTransferredSession);
        rejects(album::transferToPictureControl);
        rejects(album::claimTransferredSession);
        rejects(() -> album.getData(0x1004, 64));
        rejects(() -> album.readTo(0x1009, new ByteArrayOutputStream(), 64, SMALL.length, null, 7));
        rejects(() -> album.getPictureControlData(0x90cc, 1, 201, 0));
        rejects(() -> album.sendPictureControlData(0x90cd, 1, profile(false), 201, 0));
        rejects(() -> album.connect("camera.test", 15740, GUID, FRIENDLY_NAME));
        rejects(() -> album.connectForPictureControl("camera.test", 15740, GUID, FRIENDLY_NAME));
        equal(before, peer.operations.size());
        album.close();
        album.abortProtocol(new IOException("obsolete album callback"));
        check(preset.isOpen(), "Closing an obsolete album handle cannot release the transferred channels");
        check(!preset.isProtocolUnconfirmed(), "An obsolete album protocol callback cannot taint the new owner's boundary");
        for (FakeSocket socket : peer.sockets) check(!socket.closed, "Handoff keeps both original TCP channels open");
        rejects(preset::transferToPictureControl);
        rejects(() -> preset.connectForPictureControl("camera.test", 15740, GUID, FRIENDLY_NAME));
        rejects(() -> preset.getData(0x1004, 64));
        equal(INFO, preset.getPictureControlData(0x1001, next++));
        equal(SMALL, preset.getPictureControlData(0x90cf, next++, 1));
        equal(SMALL, preset.getPictureControlData(0x90cc, next++, 201, 0));
        preset.sendPictureControlData(0x90cd, next++, profile(false), 201, 0);
        preset.pictureControlCommand(0x1003, next++);
        equal(1L, peer.operations.stream().filter(operation -> operation == 0x1002).count());
        equal(1L, peer.operations.stream().filter(operation -> operation == 0x1003).count());
        equal(1L, peer.operations.stream().filter(operation -> operation == 0x90cd).count());
        assertSessionTransactionsAdvance(peer);
        preset.close();
        peer.assertClosed();
        int after = peer.operations.size();
        rejects(album::transferToPictureControl);
        rejects(preset::claimTransferredSession);
        equal(after, peer.operations.size());
        equal(2, peer.sockets.size());
    }

    private static void transferredEventFailuresCloseTheCurrentOwner() throws Exception {
        for (boolean malformed : new boolean[] {false, true}) {
            Peer peer = new Peer();
            PtpIpClient album = peer.connect();
            PtpIpClient preset = album.transferToPictureControl();
            preset.claimTransferredSession();
            CountDownLatch ownerClosed = new CountDownLatch(1);
            preset.setOnClosed(ownerClosed::countDown);
            // The event reader was started by the original album instance. Its
            // error must now target the transferred owner, not the retired object.
            if (malformed) peer.sockets.get(1).input.add(frame(8, new byte[5]));
            else peer.sockets.get(1).input.close();
            check(ownerClosed.await(5, TimeUnit.SECONDS), "Event failure reaches the current owner after handoff");
            check(preset.isClosed() && preset.isProtocolUnconfirmed(),
                    "Event EOF or malformed framing aborts the transferred connection");
            check(!preset.closeReason().isEmpty(), "The current owner retains the actual event-channel failure reason");
            int before = peer.operations.size();
            rejects(() -> preset.getPictureControlData(0x90cc, 1, 201, 0));
            rejects(() -> preset.sendPictureControlData(0x90cd, 2, profile(false), 201, 0));
            equal(before, peer.operations.size());
            equal(2, peer.sockets.size());
            peer.assertClosed();
        }
    }

    private static void cancelledAndInvalidHandoffsCannotCreateAnotherOwner() throws Exception {
        Peer unopenedPeer = new Peer();
        PtpIpClient unopened = new PtpIpClient(unopenedPeer::openSocket);
        rejects(unopened::transferToPictureControl);
        rejects(unopened::claimTransferredSession);
        equal(0, unopenedPeer.sockets.size());
        Peer closedPeer = new Peer();
        PtpIpClient closed = closedPeer.connect();
        closed.close();
        rejects(closed::transferToPictureControl);
        equal(2, closedPeer.sockets.size());
        closedPeer.assertClosed();

        Peer cancelledPeer = new Peer();
        PtpIpClient cancelled = cancelledPeer.connect();
        PtpIpClient[] escaped = {null};
        Thread.currentThread().interrupt();
        try { rejects(() -> escaped[0] = cancelled.transferToPictureControl()); }
        finally {
            Thread.interrupted();
            cancelled.close();
            if (escaped[0] != null) escaped[0].close();
        }
        equal(2, cancelledPeer.sockets.size());
        cancelledPeer.assertClosed();

        Peer afterTransferPeer = new Peer();
        PtpIpClient obsolete = afterTransferPeer.connect();
        PtpIpClient current = obsolete.transferToPictureControl();
        long next = current.claimTransferredSession();
        int commands = afterTransferPeer.operations.size();
        Thread.currentThread().interrupt();
        try { rejects(() -> current.getPictureControlData(0x90cc, next, 201, 0)); }
        finally { Thread.interrupted(); }
        check(current.isClosed(), "Cancellation after handoff releases the current owner");
        equal(commands, afterTransferPeer.operations.size());
        obsolete.close();
        afterTransferPeer.assertClosed();

        Peer beforeClaimPeer = new Peer();
        PtpIpClient beforeClaimAlbum = beforeClaimPeer.connect();
        PtpIpClient beforeClaim = beforeClaimAlbum.transferToPictureControl();
        commands = beforeClaimPeer.operations.size();
        Thread.currentThread().interrupt();
        try { rejects(beforeClaim::claimTransferredSession); }
        finally { Thread.interrupted(); beforeClaim.close(); }
        check(beforeClaim.isClosed(), "Cancellation before claim releases the unconsumed transferred owner");
        equal(commands, beforeClaimPeer.operations.size());
        equal(2, beforeClaimPeer.sockets.size());
        beforeClaimPeer.assertClosed();
    }

    private static void obsoleteAlbumCloseCannotCancelTheNewOwner() throws Exception {
        Peer peer = new Peer();
        PtpIpClient album = peer.connect();
        PtpIpClient preset = album.transferToPictureControl();
        long next = preset.claimTransferredSession();
        peer.fault = Fault.WRITE_BLOCKED;
        Throwable[] outcome = {null};
        Thread worker = new Thread(() -> {
            try { preset.sendPictureControlData(0x90cd, next, profile(false), 201, 0); }
            catch (Throwable error) { outcome[0] = error; }
        }, "synthetic-transferred-upload");
        worker.start();
        try {
            check(peer.writeEnded.await(5, TimeUnit.SECONDS), "Transferred owner reaches the pending upload response");
            album.close();
            check(preset.isOpen(), "A delayed obsolete album cancellation cannot abort a current pending upload");
            for (FakeSocket socket : peer.sockets) check(!socket.closed, "Obsolete cancellation cannot close transferred TCP channels");
        } finally {
            preset.close();
            worker.join(5000);
        }
        check(!worker.isAlive() && outcome[0] instanceof IOException,
                "The actual owner's cancellation unblocks its pending upload");
        equal(1L, peer.operations.stream().filter(operation -> operation == 0x90cd).count());
        equal(0L, peer.operations.stream().filter(operation -> operation == 0x1003).count());
        equal(2, peer.sockets.size());
        peer.assertClosed();
    }

    private static void assertSessionTransactionsAdvance(Peer peer) {
        boolean opened = false;
        long last = 0;
        for (int i = 0; i < peer.operations.size(); i++) {
            if (peer.operations.get(i) == 0x1002) { opened = true; continue; }
            if (opened) {
                long next = peer.transactions.get(i);
                check(next > last, "Album reads and transferred preset operations share an advancing transaction sequence");
                last = next;
            }
        }
    }

    private static void handoffWaitsForTheAlbumTransactionBoundaryAndHonorsCancellation() throws Exception {
        for (boolean cancel : new boolean[] {false, true}) {
            Peer peer = new Peer();
            PtpIpClient album = peer.connect();
            peer.fault = Fault.READ_BLOCKED;
            AtomicReference<byte[]> read = new AtomicReference<>();
            AtomicReference<Throwable> readFailure = new AtomicReference<>();
            AtomicReference<PtpIpClient> moved = new AtomicReference<>();
            AtomicReference<Throwable> moveFailure = new AtomicReference<>();
            Thread reader = new Thread(() -> {
                try { read.set(album.getData(0x1008, 64, 7)); }
                catch (Throwable error) { readFailure.set(error); }
            }, "synthetic-album-read");
            CountDownLatch moveStarted = new CountDownLatch(1);
            Thread mover = new Thread(() -> {
                moveStarted.countDown();
                try { moved.set(album.transferToPictureControl()); }
                catch (Throwable error) { moveFailure.set(error); }
            }, "synthetic-album-handoff");
            reader.start();
            try {
                check(peer.command.input.waiting.await(5, TimeUnit.SECONDS), "Album read reaches a partial dataset boundary");
                mover.start();
                check(moveStarted.await(5, TimeUnit.SECONDS), "Handoff request starts while the album transaction is pending");
                if (cancel) album.close();
                else {
                    peer.command.input.add(frame(12, concat(le32(peer.lastTransaction), Arrays.copyOfRange(SMALL, 3, SMALL.length))));
                    peer.command.input.add(response(peer.lastTransaction));
                }
                reader.join(5000);
                mover.join(5000);
                check(!reader.isAlive() && !mover.isAlive(), "Both pending album read and handoff reach a terminal boundary");
                if (cancel) {
                    check(readFailure.get() instanceof IOException && moveFailure.get() instanceof IOException && moved.get() == null,
                            "Cancelling a pending album read prevents any transferred owner from escaping");
                    peer.assertClosed();
                } else {
                    equal(SMALL, read.get());
                    check(readFailure.get() == null && moveFailure.get() == null && moved.get() != null,
                            "Handoff waits until every byte and final response of the album transaction is consumed");
                    long next = moved.get().claimTransferredSession();
                    check(next > peer.lastTransaction, "A completed in-flight album read contributes to the inherited transaction sequence");
                    moved.get().close();
                    peer.assertClosed();
                }
                equal(2, peer.sockets.size());
                equal(1L, peer.operations.stream().filter(operation -> operation == 0x1002).count());
                equal(0L, peer.operations.stream().filter(operation -> operation == 0x90cd).count());
            } finally {
                album.close();
                if (moved.get() != null) moved.get().close();
                reader.join(5000);
                if (mover.getState() != Thread.State.NEW) mover.join(5000);
            }
        }
    }

    private static void transferredEventReaderStillAnswersCameraPing() throws Exception {
        Peer peer = new Peer();
        PtpIpClient album = peer.connect();
        PtpIpClient preset = album.transferToPictureControl();
        preset.claimTransferredSession();
        peer.sockets.get(1).input.add(frame(13, new byte[0]));
        check(peer.pong.await(5, TimeUnit.SECONDS), "The original event channel still answers camera ping after handoff");
        check(preset.isOpen() && !preset.isProtocolUnconfirmed(), "A valid event ping preserves the transferred session");
        equal(1, peer.eventPongs);
        equal(1, peer.eventRequests);
        equal(2, peer.sockets.size());
        preset.close();
        peer.assertClosed();
    }

    private static void verifiedRegistrationTransfersOneFreshAttemptWithoutClosingChannels() throws Exception {
        Peer peer = new Peer();
        peer.exactPictureControlReadback = true;
        PtpIpClient first = peer.connectPictureControl();
        byte[] one = profile(false);
        first.sendPictureControlData(0x90cd, 1, one, 201, 0);
        rejects(() -> first.transferAfterVerifiedPictureControlImport(2));
        equal(one, first.getPictureControlData(0x90cc, 2, 201, 0));
        rejects(() -> first.transferAfterVerifiedPictureControlImport(2));
        int before = peer.operations.size();
        PtpIpClient second = first.transferAfterVerifiedPictureControlImport(3);
        check(first.isClosed() && second.isOpen(),
                "Exact registration proof moves the physical session into a new one-attempt owner");
        equal(before, peer.operations.size());
        equal(2, peer.sockets.size());
        rejects(() -> first.transferAfterVerifiedPictureControlImport(3));
        rejects(() -> first.getPictureControlData(0x90cc, 3, 201, 0));
        rejects(() -> first.sendPictureControlData(0x90cd, 3, one, 202, 0));
        first.close();
        first.abortProtocol(new IOException("obsolete first-import callback"));
        check(second.isOpen() && !second.isProtocolUnconfirmed(),
                "Old registration handles cannot cancel or taint the new explicit attempt");
        final long next = second.claimTransferredSession();
        equal(3L, next);
        rejects(second::claimTransferredSession);
        rejects(() -> second.transferAfterVerifiedPictureControlImport(next));
        byte[] two = profile(true);
        second.sendPictureControlData(0x90cd, next, two, 202, 0);
        rejects(() -> second.sendPictureControlData(0x90cd, next + 1, one, 203, 0));
        equal(two, second.getPictureControlData(0x90cc, next + 1, 202, 0));
        PtpIpClient idle = second.transferAfterVerifiedPictureControlImport(next + 2);
        check(second.isClosed() && idle.isOpen(), "A second independently verified registration can return to idle ownership");
        long idleTransaction = idle.claimTransferredSession();
        equal(5L, idleTransaction);
        idle.getPictureControlData(0x90cc, idleTransaction, 1, 0);
        idle.pictureControlCommand(0x1003, idleTransaction + 1);
        equal(2L, peer.operations.stream().filter(operation -> operation == 0x90cd).count());
        equal(1L, peer.operations.stream().filter(operation -> operation == 0x1002).count());
        equal(1L, peer.operations.stream().filter(operation -> operation == 0x1003).count());
        assertSessionTransactionsAdvance(peer);
        idle.close();
        peer.assertClosed();
    }

    private static void unverifiedRegistrationCannotRefreshItsWriteBudget() throws Exception {
        for (String state : new String[] {"no_write", "no_readback", "wrong_target", "different_bytes", "rejected", "unknown", "cancelled"}) {
            Peer peer = new Peer();
            peer.exactPictureControlReadback = true;
            PtpIpClient client = peer.connectPictureControl();
            byte[] bytes = profile(false);
            if (!state.equals("no_write")) {
                if (state.equals("rejected")) peer.fault = Fault.WRITE_REJECTED;
                if (state.equals("unknown")) peer.fault = Fault.WRITE_DROP;
                try { client.sendPictureControlData(0x90cd, 1, bytes, 201, 0); }
                catch (IOException error) {
                    check(state.equals("rejected") || state.equals("unknown"), "Only configured synthetic write failures are expected");
                }
                if (state.equals("wrong_target")) {
                    // Another slot may happen to contain identical bytes. Exact
                    // data from that slot cannot verify the requested target.
                    peer.uploadedTarget = 202;
                    equal(bytes, client.getPictureControlData(0x90cc, 2, 202, 0));
                }
                if (state.equals("different_bytes")) {
                    peer.readbackOverride = bytes.clone();
                    peer.readbackOverride[2] = 'X';
                    client.getPictureControlData(0x90cc, 2, 201, 0);
                }
                if (state.equals("rejected")) {
                    // Matching pre-existing bytes do not turn a rejected Set
                    // response into a successfully verified registration.
                    peer.fault = Fault.NONE;
                    equal(bytes, client.getPictureControlData(0x90cc, 2, 201, 0));
                }
                if (state.equals("cancelled")) {
                    client.getPictureControlData(0x90cc, 2, 201, 0);
                    client.close();
                }
            }
            int before = peer.operations.size();
            rejects(() -> client.transferAfterVerifiedPictureControlImport(3));
            equal(before, peer.operations.size());
            equal(2, peer.sockets.size());
            if (!state.equals("no_write")) {
                rejects(() -> client.sendPictureControlData(0x90cd, 4, bytes, 202, 0));
                equal(1L, peer.operations.stream().filter(operation -> operation == 0x90cd).count());
            }
            client.close();
            peer.assertClosed();
        }
    }

    private static void registrationProofCannotCrossAClosedAndReopenedSession() throws Exception {
        Peer peer = new Peer();
        peer.exactPictureControlReadback = true;
        PtpIpClient client = peer.connectPictureControl();
        byte[] bytes = profile(false);
        client.sendPictureControlData(0x90cd, 1, bytes, 201, 0);
        equal(bytes, client.getPictureControlData(0x90cc, 2, 201, 0));
        client.pictureControlCommand(0x1003, 3);
        client.pictureControlCommand(0x1002, 0, 1);
        int before = peer.operations.size();
        rejects(() -> client.transferAfterVerifiedPictureControlImport(1));
        rejects(() -> client.transferAfterVerifiedPictureControlImport(4));
        rejects(() -> client.sendPictureControlData(0x90cd, 1, bytes, 202, 0));
        equal(before, peer.operations.size());
        equal(1L, peer.operations.stream().filter(operation -> operation == 0x90cd).count());
        equal(2L, peer.operations.stream().filter(operation -> operation == 0x1002).count());
        equal(1L, peer.operations.stream().filter(operation -> operation == 0x1003).count());
        equal(2, peer.sockets.size());
        check(client.isOpen(), "Rejected stale proof cannot grant another write or close the reopened owned session");
        client.close();
        peer.assertClosed();
    }

    private static void pictureControlCanReturnToAReadOnlyAlbumWithoutReopening() throws Exception {
        Peer peer = new Peer();
        PtpIpClient preset = peer.connectPictureControl();
        preset.getPictureControlData(0x90cc, 1, 1, 0);
        rejects(() -> preset.transferToAlbum(1));
        rejects(() -> preset.transferToAlbum(3));
        int before = peer.operations.size();
        PtpIpClient album = preset.transferToAlbum(2);
        check(preset.isClosed() && album.isOpen(), "Reverse handoff revokes the old preset facade while preserving the physical connection");
        equal(before, peer.operations.size());
        equal(2, peer.sockets.size());
        rejects(() -> album.getData(0x1004, 64));
        rejects(album::readAlbumDeviceInfo);
        rejects(() -> album.getPictureControlData(0x90cc, 2, 201, 0));
        rejects(() -> album.sendPictureControlData(0x90cd, 2, profile(false), 201, 0));
        equal(2L, album.claimTransferredAlbumSession());
        rejects(album::claimTransferredAlbumSession);
        rejects(preset::claimTransferredAlbumSession);
        rejects(() -> preset.transferToAlbum(2));
        rejects(() -> preset.getPictureControlData(0x90cc, 2, 1, 0));
        preset.close();
        preset.abortProtocol(new IOException("obsolete preset callback"));
        check(album.isOpen() && !album.isProtocolUnconfirmed(), "Obsolete preset cleanup cannot cancel the adopted read-only album owner");
        equal(INFO, album.readAlbumDeviceInfo());
        equal(2L, peer.lastTransaction);
        equal(SMALL, album.getData(0x1004, 64));
        equal(3L, peer.lastTransaction);
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        album.readTo(0x1009, original, 64, SMALL.length, null, 7);
        equal(SMALL, original.toByteArray());
        equal(4L, peer.lastTransaction);
        int beforeSecondTransfer = peer.operations.size();
        PtpIpClient secondPreset = album.transferToPictureControl();
        equal(beforeSecondTransfer, peer.operations.size());
        equal(5L, secondPreset.claimTransferredSession());
        rejects(album::claimTransferredAlbumSession);
        rejects(() -> album.getData(0x1004, 64));
        album.close();
        album.abortProtocol(new IOException("obsolete album callback"));
        equal(SMALL, secondPreset.getPictureControlData(0x90cc, 5, 201, 0));
        check(secondPreset.isOpen(), "An album-to-preset return remains usable after stale album callbacks");
        equal(1L, peer.operations.stream().filter(operation -> operation == 0x1002).count());
        equal(0L, peer.operations.stream().filter(operation -> operation == 0x1003).count());
        equal(0L, peer.operations.stream().filter(operation -> operation == 0x90cd).count());
        equal(2, peer.sockets.size());
        assertSessionTransactionsAdvance(peer);
        secondPreset.close();
        peer.assertClosed();
    }

    private static void pictureControlWriteAttemptsCannotBeLaunderedThroughAlbumHandoff() throws Exception {
        for (String outcome : new String[] {"acknowledged_unverified", "rejected", "unknown", "verified_old_facade"}) {
            Peer peer = new Peer();
            peer.exactPictureControlReadback = true;
            PtpIpClient preset = peer.connectPictureControl();
            if (outcome.equals("rejected")) peer.fault = Fault.WRITE_REJECTED;
            if (outcome.equals("unknown")) peer.fault = Fault.WRITE_DROP;
            try { preset.sendPictureControlData(0x90cd, 1, profile(false), 201, 0); }
            catch (IOException error) { check(outcome.equals("rejected") || outcome.equals("unknown"), "Only configured write failures are expected"); }
            if (outcome.equals("verified_old_facade")) preset.getPictureControlData(0x90cc, 2, 201, 0);
            int before = peer.operations.size();
            rejects(() -> preset.transferToAlbum(outcome.equals("verified_old_facade") ? 3 : 2));
            equal(before, peer.operations.size());
            equal(1L, peer.operations.stream().filter(operation -> operation == 0x90cd).count());
            equal(2, peer.sockets.size());
            preset.close();
            peer.assertClosed();
        }
    }

    private static void reverseHandoffEventFailureAndCancellationCloseOnlyTheCurrentOwner() throws Exception {
        Peer peer = new Peer();
        PtpIpClient oldPreset = peer.connectPictureControl();
        PtpIpClient album = oldPreset.transferToAlbum(1);
        album.claimTransferredAlbumSession();
        CountDownLatch closed = new CountDownLatch(1);
        album.setOnClosed(closed::countDown);
        peer.sockets.get(1).input.close();
        check(closed.await(5, TimeUnit.SECONDS), "The original preset event reader routes EOF to the current album owner");
        check(album.isClosed() && album.isProtocolUnconfirmed() && !album.closeReason().isEmpty(),
                "Reverse handoff preserves terminal event failure evidence on the current owner");
        oldPreset.close();
        peer.assertClosed();

        Peer cancelledPeer = new Peer();
        PtpIpClient cancelled = cancelledPeer.connectPictureControl();
        PtpIpClient[] escaped = {null};
        Thread.currentThread().interrupt();
        try { rejects(() -> escaped[0] = cancelled.transferToAlbum(1)); }
        finally { Thread.interrupted(); cancelled.close(); if (escaped[0] != null) escaped[0].close(); }
        equal(2, cancelledPeer.sockets.size());
        cancelledPeer.assertClosed();
    }

    private static void objectAddedEventsBelongOnlyToTheCurrentReadOnlyOwner() throws Exception {
        Peer peer = new Peer();
        PtpIpClient original = peer.connect(), preset = null, album = null;
        AtomicInteger oldCalls = new AtomicInteger(), currentCalls = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1), current = new CountDownLatch(1);
        try {
            original.setOnObjectAdded(handle -> { equal(42L, handle); oldCalls.incrementAndGet(); first.countDown(); });
            int commandsBefore = peer.operations.size();
            peer.sockets.get(1).input.add(frame(8, concat(le16(0x4002), le32(0xffffffffL), le32(42))));
            check(first.await(5, TimeUnit.SECONDS), "A valid ObjectAdded event reaches the current album listener");
            equal(commandsBefore, peer.operations.size());
            preset = original.transferToPictureControl(); equal(1L, preset.claimTransferredSession());
            preset.getPictureControlData(0x1001, 1);
            peer.sockets.get(1).input.add(frame(8, concat(le16(0x4002), le32(0xffffffffL), le32(88))));
            album = preset.transferToAlbum(2); equal(2L, album.claimTransferredAlbumSession());
            album.setOnObjectAdded(handle -> { if (handle == 89) { currentCalls.incrementAndGet(); current.countDown(); } });
            original.close(); preset.close();
            commandsBefore = peer.operations.size();
            peer.sockets.get(1).input.add(frame(8, concat(le16(0x4002), le32(0xffffffffL), le32(89))));
            check(current.await(5, TimeUnit.SECONDS), "The same physical reader delivers a later ObjectAdded event to the returned album owner");
            equal(1, oldCalls.get()); equal(1, currentCalls.get()); equal(commandsBefore, peer.operations.size());
            equal(SMALL, album.getData(0x1004, 64));
            equal(2L, peer.transactions.get(peer.transactions.size() - 1).longValue());
            equal(2, peer.sockets.size());
            check(album.isOpen() && peer.operations.stream().noneMatch(op -> op == 0x1009 || op == 0x90cd || op == 0x1003),
                    "Object notifications never cause event-thread original downloads, registration, reconnect, or CloseSession");
        } finally { if (album != null) album.close(); if (preset != null) preset.close(); original.close(); }
        peer.assertClosed();
    }

    private static void objectAddedListenerFailuresCannotKillThePhysicalEventReader() throws Exception {
        Peer peer = new Peer(); PtpIpClient album = peer.connect();
        try {
            album.setOnObjectAdded(handle -> { throw new IllegalStateException("synthetic detached UI listener"); });
            peer.sockets.get(1).input.add(frame(8, concat(le16(0x4002), le32(0xffffffffL), le32(42))));
            peer.sockets.get(1).input.add(frame(13, new byte[0]));
            check(peer.pong.await(5, TimeUnit.SECONDS), "A throwing ObjectAdded callback cannot kill event ping processing");
            check(album.isOpen() && !album.isProtocolUnconfirmed(), "Listener code failure does not corrupt the healthy command or event channel");
            equal(SMALL, album.getData(0x1004, 64));
        } finally { album.close(); }
        peer.assertClosed();
    }

    private static void malformedObjectAddedEventsCloseTheCurrentOwner() throws Exception {
        for (byte[] payload : new byte[][] {
                concat(le16(0x4002), le32(0xffffffffL)),
                concat(le16(0x4002), le32(0xffffffffL), le32(0)),
                concat(le16(0x4002), le32(0xffffffffL), le32(0xffffffffL)),
                concat(le16(0x4002), le32(0xffffffffL), le32(42), le32(43))}) {
            Peer peer = new Peer(); PtpIpClient old = peer.connectPictureControl(), album = old.transferToAlbum(1);
            album.claimTransferredAlbumSession();
            CountDownLatch closed = new CountDownLatch(1); AtomicInteger events = new AtomicInteger();
            album.setOnClosed(closed::countDown); album.setOnObjectAdded(handle -> events.incrementAndGet());
            peer.sockets.get(1).input.add(frame(8, payload));
            check(closed.await(5, TimeUnit.SECONDS), "Malformed ObjectAdded semantic payload terminates the physical current owner");
            equal(0, events.get());
            check(album.isClosed() && album.isProtocolUnconfirmed() && !album.closeReason().isEmpty(),
                    "Invalid live-event handles or arity cannot become a photo candidate and preserve terminal diagnostics");
            old.close(); album.close(); peer.assertClosed();
        }
    }

    private static void cancelledClientCannotOpenSockets() throws Exception {
        final int[] opened = {0};
        PtpIpClient client = new PtpIpClient((host, port, timeout) -> {
            opened[0]++;
            throw new IOException("Connector must not be reached after cancellation");
        });
        client.close();
        rejects(() -> client.connect("camera.test", 15740, GUID.clone(), FRIENDLY_NAME));
        equal(0, opened[0]);
        check(!client.isOpen(), "Cancelling before connect cannot create a live connection");
    }

    private static void completeCameraErrorsKeepTheBoundaryReusable() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = peer.connect();
        peer.fault = Fault.ERROR_RESPONSE;
        try {
            client.getData(0x1008, 64, 4);
            throw new AssertionError("Expected a complete camera rejection");
        } catch (com.nikonpreset.usbprobe.ptp.PtpResponseException error) {
            equal(0x2009, error.responseCode());
            equal(peer.lastTransaction, error.transactionId());
        }
        check(client.isOpen(), "A complete matching camera error leaves a reusable transaction boundary");
        peer.fault = Fault.NONE;
        equal(SMALL, client.getData(0x1008, 64, 5));
        client.close();
        peer.assertClosed();
    }

    private static void handshakeAndFragmentedTransfers() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = peer.connect();
        check(client.isOpen(), "A connection opens after both handshakes and OpenSession");
        equal(2, peer.sockets.size());
        equal(1, peer.eventRequests);
        equal(Arrays.asList(0x1001, 0x1002), peer.operations);
        equal(SMALL, client.getData(0x1007, 64, 0xffffffffL, 0, 0xffffffffL));
        equal(new long[] {0xffffffffL, 0, 0xffffffffL}, peer.lastParameters);
        long firstTransaction = peer.lastTransaction;
        equal(SMALL, client.getData(0x100a, 64, 0x12345678L));
        check(peer.lastTransaction != firstTransaction, "Different operations use different transaction IDs");
        equal(new long[] {0x12345678L}, peer.lastParameters);
        client.close();
        check(!client.isOpen(), "close clears the connection state");
        peer.assertClosed();
        int commands = peer.operations.size();
        client.close();
        equal(commands, peer.operations.size());
        rejects(() -> client.getData(0x1004, 64));
    }

    private static void rejectedWritesNeverReachTheSocket() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = peer.connect();
        int commands = peer.operations.size();
        for (int operation : new int[] {0x100b, 0x100c, 0x100d, 0x100e, 0x1016, 0x90cd}) {
            rejects(() -> client.getData(operation, 64, 1));
            equal(commands, peer.operations.size());
        }
        client.close();
        peer.assertClosed();
    }

    private static void largeThumbnailIsBoundedReadonlyAndCannotCrossModes() throws Exception {
        Peer peer = new Peer();
        try (PtpIpClient client = peer.connect()) {
            int before = peer.operations.size();
            for (long handle : new long[] {0, -1, 0xffffffffL, 0x100000000L}) {
                rejects(() -> client.getLargeThumbnail(handle));
                equal(before, peer.operations.size());
            }
            rejects(() -> client.getData(0x90c4, 64, 42));
            equal(before, peer.operations.size());
            equal(SMALL, client.getLargeThumbnail(42));
            equal(0x90c4, peer.operations.get(peer.operations.size() - 1).intValue());
            equal(new long[] {42}, peer.lastParameters);
            check(client.isOpen(), "A completed large thumbnail keeps the album session open");
        }
        Peer pcPeer = new Peer();
        try (PtpIpClient client = pcPeer.connectPictureControl()) {
            int before = pcPeer.operations.size();
            rejects(() -> client.getLargeThumbnail(42));
            equal(before, pcPeer.operations.size());
        }
        for (Fault fault : new Fault[] {Fault.START_WRONG_TRANSACTION, Fault.END_WRONG_TRANSACTION,
                Fault.RESPONSE_WRONG_TRANSACTION, Fault.TRUNCATED_FRAME, Fault.OVER_LARGE_THUMB_CAP}) {
            Peer broken = new Peer();
            try (PtpIpClient client = broken.connect()) {
                broken.fault = fault;
                rejects(() -> client.getLargeThumbnail(42));
                check(!client.isOpen(), "Malformed or oversized large preview is terminal: " + fault);
                broken.assertClosed();
                int commands = broken.operations.size();
                rejects(() -> client.getData(0x100a, 64, 42));
                equal(commands, broken.operations.size());
            }
        }
    }

    private static void handshakeFailuresReleaseEveryCreatedSocket() throws Exception {
        for (Fault fault : new Fault[] {Fault.INIT_REJECTED, Fault.EVENT_REJECTED, Fault.SECOND_CONNECT_FAIL}) {
            Peer peer = new Peer();
            peer.fault = fault;
            PtpIpClient client = new PtpIpClient(peer::openSocket);
            rejects(() -> client.connect("camera.test", 15740, GUID.clone(), FRIENDLY_NAME));
            check(!client.isOpen(), "A failed handshake cannot leave an open client: " + fault);
            peer.assertClosed();
            equal(0, peer.operations.size());
        }
    }

    private static void framingAndTransactionFailuresCloseBothChannels() throws Exception {
        for (Fault fault : new Fault[] {Fault.SHORT_HEADER_LENGTH, Fault.START_WRONG_TRANSACTION,
                Fault.DATA_WRONG_TRANSACTION, Fault.END_WRONG_TRANSACTION, Fault.RESPONSE_WRONG_TRANSACTION,
                Fault.PREMATURE_END, Fault.TOO_MUCH_DATA, Fault.MISSING_END, Fault.TRUNCATED_FRAME}) {
            Peer peer = new Peer();
            PtpIpClient client = peer.connect();
            peer.fault = fault;
            rejects(() -> client.getData(0x1004, 64));
            check(!client.isOpen(), "Malformed transfers invalidate the connection: " + fault);
            peer.assertClosed();
            int commands = peer.operations.size();
            rejects(() -> client.getData(0x1004, 64));
            equal(commands, peer.operations.size());
        }
    }

    private static void remoteLengthsAreBoundedWithoutTruncation() throws Exception {
        for (Fault fault : new Fault[] {Fault.OVER_METADATA_CAP, Fault.UINT64_LENGTH}) {
            Peer peer = new Peer();
            PtpIpClient client = peer.connect();
            peer.fault = fault;
            rejects(() -> client.getData(0x1004, 64));
            check(!client.isOpen(), "Oversized declared data closes the client: " + fault);
            peer.assertClosed();
        }
        Peer peer = new Peer();
        PtpIpClient client = peer.connect();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        rejects(() -> client.readTo(0x1009, output, 64, SMALL.length + 1L, null, 1));
        check(!client.isOpen(), "A download with an unexpected object size cannot be successful");
        peer.assertClosed();
    }

    private static void streamingDoesNotBufferTheCompleteObject() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = peer.connect();
        peer.fault = Fault.LARGE_STREAM;
        final long total = 2L * 1024 * 1024 + 31;
        final long[] written = {0};
        final boolean[] firstWriteWasStreaming = {false};
        OutputStream output = new OutputStream() {
            @Override public void write(int value) throws IOException {
                byte[] one = {(byte) value};
                write(one, 0, 1);
            }
            @Override public void write(byte[] bytes, int offset, int length) {
                if (written[0] == 0) firstWriteWasStreaming[0] = peer.command.input.remaining() > total / 2;
                for (int i = 0; i < length; i++) {
                    if (bytes[offset + i] != streamByte(written[0] + i)) {
                        throw new AssertionError("Streaming altered payload at " + (written[0] + i));
                    }
                }
                written[0] += length;
            }
        };
        final long[] progress = {-1, -1};
        long received = client.readTo(0x1009, output, total, total, (done, expected) -> {
            check(done >= progress[0], "Download progress is monotonic");
            check(done <= total, "Download progress never exceeds the object size");
            if (expected >= 0) equal(total, expected);
            progress[0] = done;
            progress[1] = expected;
        }, 0xfedcba98L);
        equal(total, received);
        equal(total, written[0]);
        check(firstWriteWasStreaming[0], "Object bytes reach the destination while most wire bytes remain unread");
        equal(total, progress[0]);
        check(client.isOpen(), "A verified large download retains the connection");
        client.close();
        peer.assertClosed();
    }

    private static void failedOutputClosesBothChannels() throws Exception {
        Peer peer = new Peer();
        PtpIpClient client = peer.connect();
        IOException diskFailure = new IOException("synthetic disk full");
        OutputStream output = new OutputStream() {
            @Override public void write(int value) throws IOException { throw diskFailure; }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException { throw diskFailure; }
        };
        try {
            client.readTo(0x1009, output, 64, SMALL.length, null, 1);
            throw new AssertionError("Expected the destination failure");
        } catch (IOException expected) {
            check(expected == diskFailure || hasCause(expected, diskFailure), "Destination failure remains identifiable");
        }
        check(!client.isOpen(), "A partially consumed object cannot leave a reusable session");
        peer.assertClosed();
    }

    private static boolean hasCause(Throwable error, Throwable expected) {
        for (Throwable cursor = error; cursor != null; cursor = cursor.getCause()) if (cursor == expected) return true;
        return false;
    }

    private enum Fault {
        NONE, INIT_REJECTED, EVENT_REJECTED, SECOND_CONNECT_FAIL,
        SHORT_HEADER_LENGTH, START_WRONG_TRANSACTION, DATA_WRONG_TRANSACTION,
        END_WRONG_TRANSACTION, RESPONSE_WRONG_TRANSACTION, PREMATURE_END,
        TOO_MUCH_DATA, MISSING_END, TRUNCATED_FRAME, OVER_METADATA_CAP, OVER_LARGE_THUMB_CAP, UINT64_LENGTH, LARGE_STREAM,
        ERROR_RESPONSE, WRITE_REJECTED, WRITE_DROP, WRITE_START_FAILED, WRITE_TIMEOUT,
        WRITE_WRONG_TRANSACTION, WRITE_TRUNCATED_RESPONSE, WRITE_BLOCKED, READ_BLOCKED
    }

    private static final class Peer {
        private final List<FakeSocket> sockets = new ArrayList<>();
        private final List<Integer> operations = new ArrayList<>();
        private final List<Long> transactions = new ArrayList<>();
        private final List<byte[]> commandPackets = new ArrayList<>();
        private final ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
        private final CountDownLatch writeEnded = new CountDownLatch(1);
        private final CountDownLatch pong = new CountDownLatch(1);
        private FakeSocket command;
        private Fault fault = Fault.NONE;
        private int eventRequests;
        private int eventPongs;
        private boolean exactPictureControlReadback;
        private int uploadedTarget;
        private byte[] readbackOverride;
        private long lastTransaction;
        private long[] lastParameters;

        PtpIpClient connect() throws Exception {
            PtpIpClient client = new PtpIpClient(this::openSocket);
            equal(INFO, client.connect("camera.test", 15740, GUID.clone(), FRIENDLY_NAME));
            return client;
        }

        PtpIpClient connectPictureControl() throws Exception {
            PtpIpClient client = PtpIpClient.forPictureControl(this::openSocket);
            client.connectForPictureControl("camera.test", 15740, GUID, FRIENDLY_NAME);
            equal(INFO, client.getPictureControlData(0x1001, 0));
            client.pictureControlCommand(0x1002, 0, 1);
            return client;
        }

        Socket openSocket(String host, int port, int timeout) throws IOException {
            equal("camera.test", host);
            equal(15740, port);
            check(timeout > 0, "Socket connects use a finite timeout");
            if (sockets.size() == 1 && fault == Fault.SECOND_CONNECT_FAIL) throw new IOException("event connect failed");
            boolean event = !sockets.isEmpty();
            check(sockets.size() < 2, "A client uses exactly the two requested channels");
            FakeSocket socket = new FakeSocket(this, event);
            sockets.add(socket);
            if (!event) command = socket;
            return socket;
        }

        void receive(FakeSocket socket, byte[] packet) throws IOException {
            long type = u32(packet, 4);
            if (socket.event) {
                if (type == 14) {
                    equal(8, packet.length);
                    eventPongs++;
                    pong.countDown();
                    return;
                }
                equal(3L, type);
                equal(12, packet.length);
                equal(CONNECTION_NUMBER, u32(packet, 8));
                eventRequests++;
                socket.input.add(fault == Fault.EVENT_REJECTED ? frame(5, le32(1)) : frame(4, new byte[0]));
                return;
            }
            if (type == 1) {
                equal(GUID, Arrays.copyOfRange(packet, 8, 24));
                byte[] encodedName = (FRIENDLY_NAME + '\0').getBytes(StandardCharsets.UTF_16LE);
                equal(28 + encodedName.length, packet.length);
                equal(encodedName, Arrays.copyOfRange(packet, 24, packet.length - 4));
                equal(0x00010000L, u32(packet, packet.length - 4));
                byte[] ack = concat(le32(CONNECTION_NUMBER), GUID,
                        "Synthetic Camera\0".getBytes(StandardCharsets.UTF_16LE), le32(0x10000));
                socket.input.add(fault == Fault.INIT_REJECTED ? frame(5, le32(1)) : frame(2, ack));
                return;
            }
            commandPackets.add(packet.clone());
            if (type == 9) {
                equal(20, packet.length);
                equal(lastTransaction, u32(packet, 8));
                check(u32(packet, 12) == 36 || u32(packet, 12) == 614, "Only bounded validated datasets reach the wire");
                equal(0L, u32(packet, 16));
                if (fault == Fault.WRITE_START_FAILED) throw new IOException("synthetic send failure");
                return;
            }
            if (type == 10 || type == 12) {
                equal(lastTransaction, u32(packet, 8));
                uploaded.write(packet, 12, packet.length - 12);
                if (type == 12) {
                    if (fault == Fault.WRITE_BLOCKED) socket.input.holdOpen = true;
                    else if (fault == Fault.WRITE_TIMEOUT) socket.input.timeoutWhenEmpty = true;
                    else if (fault != Fault.WRITE_DROP) {
                        byte[] complete = frame(7, concat(le16(fault == Fault.WRITE_REJECTED ? 0x200f : 0x2001),
                                le32(lastTransaction + (fault == Fault.WRITE_WRONG_TRANSACTION ? 1 : 0)), le32(0xfedcba98L)));
                        socket.input.add(fault == Fault.WRITE_TRUNCATED_RESPONSE ? Arrays.copyOf(complete, complete.length - 1) : complete);
                    }
                    writeEnded.countDown();
                }
                return;
            }
            equal(6L, type);
            check(packet.length >= 18 && (packet.length - 18) % 4 == 0, "Operation packet has only complete parameters");
            int operation = u16(packet, 12);
            equal(operation == 0x90cd ? 2L : 1L, u32(packet, 8));
            long transaction = u32(packet, 14);
            lastTransaction = transaction;
            lastParameters = new long[(packet.length - 18) / 4];
            for (int i = 0; i < lastParameters.length; i++) lastParameters[i] = u32(packet, 18 + i * 4);
            operations.add(operation);
            transactions.add(transaction);
            if (operation == 0x90cd) {
                equal(new long[] {lastParameters[0], 0}, lastParameters);
                uploadedTarget = (int) lastParameters[0];
                uploaded.reset();
            } else if (operation == 0x1002) {
                equal(new long[] {1}, lastParameters);
                addCommandResponse(socket.input, transaction);
            } else if (operation == 0x1001) {
                equal(0, lastParameters.length);
                addTransfer(socket.input, transaction, INFO, Fault.NONE);
            } else if (operation == 0x1003) {
                addCommandResponse(socket.input, transaction);
            } else {
                if (fault == Fault.READ_BLOCKED) {
                    socket.input.holdOpen = true;
                    socket.input.add(frame(9, concat(le32(transaction), le64(SMALL.length))));
                    socket.input.add(frame(10, concat(le32(transaction), Arrays.copyOfRange(SMALL, 0, 3))));
                    return;
                }
                byte[] data = exactPictureControlReadback && operation == 0x90cc && lastParameters[0] == uploadedTarget
                        && uploaded.size() > 0 ? (readbackOverride == null ? uploaded.toByteArray() : readbackOverride) : SMALL;
                addTransfer(socket.input, transaction, data, fault);
            }
        }

        private void addCommandResponse(QueuedInput input, long transaction) {
            input.add(frame(7, concat(le16(fault == Fault.ERROR_RESPONSE ? 0x2009 : 0x2001),
                    le32(transaction + (fault == Fault.RESPONSE_WRONG_TRANSACTION ? 1 : 0)))));
        }

        void assertClosed() {
            check(!sockets.isEmpty(), "The failure exercised a created socket");
            for (FakeSocket socket : sockets) check(socket.closed, "Every created channel is closed");
        }
    }

    private static void addTransfer(QueuedInput input, long transaction, byte[] payload, Fault fault) {
        if (fault == Fault.ERROR_RESPONSE) {
            input.add(frame(7, concat(le16(0x2009), le32(transaction))));
            return;
        }
        if (fault == Fault.SHORT_HEADER_LENGTH) {
            input.add(concat(le32(7), le32(9)));
            return;
        }
        long declared = payload.length;
        if (fault == Fault.PREMATURE_END) declared++;
        if (fault == Fault.TOO_MUCH_DATA) declared--;
        if (fault == Fault.OVER_METADATA_CAP) declared = 65;
        if (fault == Fault.OVER_LARGE_THUMB_CAP) declared = 8L * 1024 * 1024 + 1;
        if (fault == Fault.UINT64_LENGTH) declared = 0x100000001L;
        if (fault == Fault.LARGE_STREAM) declared = 2L * 1024 * 1024 + 31;
        long startTx = transaction + (fault == Fault.START_WRONG_TRANSACTION ? 1 : 0);
        input.add(frame(9, concat(le32(startTx), le64(declared))));
        if (fault == Fault.LARGE_STREAM) {
            for (long offset = 0; offset < declared;) {
                int count = (int) Math.min(4093, declared - offset);
                byte[] chunk = new byte[count];
                for (int i = 0; i < count; i++) chunk[i] = streamByte(offset + i);
                offset += count;
                input.add(frame(offset == declared ? 12 : 10, concat(le32(transaction), chunk)));
            }
        } else {
            long dataTx = transaction + (fault == Fault.DATA_WRONG_TRANSACTION ? 1 : 0);
            if (fault == Fault.MISSING_END) {
                input.add(frame(10, concat(le32(dataTx), payload)));
            } else {
                input.add(frame(10, concat(le32(dataTx), Arrays.copyOfRange(payload, 0, 3))));
                long endTx = transaction + (fault == Fault.END_WRONG_TRANSACTION ? 1 : 0);
                byte[] end = frame(12, concat(le32(endTx), Arrays.copyOfRange(payload, 3, payload.length)));
                input.add(fault == Fault.TRUNCATED_FRAME ? Arrays.copyOf(end, end.length - 1) : end);
                if (fault == Fault.TRUNCATED_FRAME) return;
            }
        }
        input.add(response(transaction + (fault == Fault.RESPONSE_WRONG_TRANSACTION ? 1 : 0)));
    }

    private static byte streamByte(long offset) { return (byte) (offset * 31 + (offset >>> 7)); }
    private static byte[] response(long transaction) { return frame(7, concat(le16(0x2001), le32(transaction))); }

    private static final class FakeSocket extends Socket {
        private final QueuedInput input;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        private final Peer peer;
        private final boolean event;
        private volatile boolean closed;

        FakeSocket(Peer peer, boolean event) { this.peer = peer; this.event = event; input = new QueuedInput(event); }
        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException { write(new byte[] {(byte) value}); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (closed) throw new IOException("closed socket");
                    pending.write(bytes, offset, length);
                    while (pending.size() >= 8) {
                        byte[] buffered = pending.toByteArray();
                        long frameLength = u32(buffered, 0);
                        if (frameLength < 8 || frameLength > 65536) throw new IOException("bad client frame length");
                        if (buffered.length < frameLength) return;
                        pending.reset();
                        pending.write(buffered, (int) frameLength, buffered.length - (int) frameLength);
                        peer.receive(FakeSocket.this, Arrays.copyOf(buffered, (int) frameLength));
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

    private static final class QueuedInput extends InputStream {
        private final Deque<byte[]> chunks = new ArrayDeque<>();
        private final CountDownLatch waiting = new CountDownLatch(1);
        private volatile boolean holdOpen;
        private boolean timeoutWhenEmpty;
        private int offset;
        private long remaining;
        private boolean closed;
        QueuedInput(boolean holdOpen) { this.holdOpen = holdOpen; }
        synchronized void add(byte[] bytes) {
            if (bytes.length != 0) { chunks.add(bytes); remaining += bytes.length; notifyAll(); }
        }
        synchronized long remaining() { return remaining; }
        private boolean hasData() throws IOException {
            if (chunks.isEmpty() && timeoutWhenEmpty) throw new SocketTimeoutException("synthetic response timeout");
            while (chunks.isEmpty() && holdOpen && !closed) {
                waiting.countDown();
                try { wait(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("synthetic socket read interrupted", interrupted);
                }
            }
            return !closed && !chunks.isEmpty();
        }
        @Override public synchronized int read() throws IOException {
            if (!hasData()) return -1;
            int value = chunks.peek()[offset++] & 0xff;
            remaining--;
            if (offset == chunks.peek().length) { chunks.remove(); offset = 0; }
            return value;
        }
        @Override public synchronized int read(byte[] bytes, int start, int length) throws IOException {
            if (length == 0) return 0;
            if (!hasData()) return -1;
            // Deliberately split both headers and payload fields across many TCP reads.
            int count = Math.min(3, Math.min(length, chunks.peek().length - offset));
            System.arraycopy(chunks.peek(), offset, bytes, start, count);
            offset += count;
            remaining -= count;
            if (offset == chunks.peek().length) { chunks.remove(); offset = 0; }
            return count;
        }
        @Override public synchronized void close() { closed = true; notifyAll(); }
    }

    private static byte[] frame(int type, byte[] payload) { return concat(le32(8L + payload.length), le32(type), payload); }
    private static byte[] le16(long value) { return new byte[] {(byte) value, (byte) (value >>> 8)}; }
    private static byte[] le32(long value) { return new byte[] {(byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)}; }
    private static byte[] le64(long value) { return concat(le32(value), le32(value >>> 32)); }
    private static long u32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }
    private static int u16(byte[] bytes, int offset) { return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8); }
    private static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] bytes : arrays) length += bytes.length;
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] bytes : arrays) { System.arraycopy(bytes, 0, result, offset, bytes.length); offset += bytes.length; }
        return result;
    }
    private interface Action { void run() throws Exception; }
    private static void rejects(Action action) throws Exception {
        try { action.run(); }
        catch (IOException | IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Expected a rejected PTP/IP operation");
    }
    private static void equal(Object expected, Object actual) {
        boolean same;
        if (expected instanceof byte[] && actual instanceof byte[]) same = Arrays.equals((byte[]) expected, (byte[]) actual);
        else if (expected instanceof long[] && actual instanceof long[]) same = Arrays.equals((long[]) expected, (long[]) actual);
        else same = java.util.Objects.equals(expected, actual);
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

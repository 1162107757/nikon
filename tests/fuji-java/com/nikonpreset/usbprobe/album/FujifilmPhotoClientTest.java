package com.nikonpreset.usbprobe.album;

import com.nikonpreset.usbprobe.ptp.PtpContainer;
import com.nikonpreset.usbprobe.ptp.PtpDeviceInfo;
import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A scripted camera verifies Fuji framing, mode order, chunk bounds and failure containment. */
public final class FujifilmPhotoClientTest {
    public static void main(String[] args) throws Exception {
        FujifilmThumbnailTest.run(); paddedThumbnailTransfer();
        happyPath(); metadataPageAndThumbnailCache(); cacheInvalidationAndBounds(); invalidScalarLengths(); channelFailures(); wrongCamera(); malformedTransaction(); oversizedPacket(); shortDownload(); unsupportedWrite(); cancelledConnection(); cancelledDownload();
        System.out.println("FujifilmPhotoClient tests passed (synthetic camera; physical X-T20 still required).");
    }
    private static void wrongCamera() throws Exception {
        for (String identity : List.of("X-T30", "", "Unknown")) {
            Camera camera = new Camera(); camera.identity = identity; FujifilmPhotoClient client = camera.client();
            expectIOException(() -> client.connect("camera", 55740, new byte[16], "app"));
            check(camera.operations.isEmpty() && camera.allClosed(), "foreign or missing model rejected before OpenSession");
        }
    }
    private static void paddedThumbnailTransfer() throws Exception {
        Camera camera = new Camera(); FujifilmPhotoClient client = camera.client();
        byte[] jpeg = FujifilmThumbnailTest.jpeg(false);
        camera.thumbnail = Arrays.copyOf(jpeg, 21314);
        client.connect("camera", 55740, new byte[16], "app");
        client.getData(0x1008, 65536, 1);
        int before = camera.operations.size();
        byte[] first = client.getData(0x100a, 65536, 1);
        check(Arrays.equals(first, jpeg), "21314-byte thumbnail response is normalized before controller validation");
        first[0] = 0;
        check(Arrays.equals(client.getData(0x100a, 65536, 1), jpeg) && camera.operations.size() == before + 1,
                "grid and detail reuse normalized independent thumbnail bytes");
        camera.thumbnail = Arrays.copyOf(jpeg, jpeg.length - 2);
        camera.metadataRevision = "changed"; client.getData(0x1008, 65536, 1);
        before = camera.operations.size();
        check(Arrays.equals(client.getData(0x100a, 65536, 1), camera.thumbnail), "truncated EOI is never appended");
        client.getData(0x100a, 65536, 1);
        check(camera.operations.size() == before + 2 && client.isOpen(), "truncated preview is not cached and does not close healthy framing");
        // This path must never shorten original downloads, even if their suffix is not EOI.
        camera.jpeg[camera.jpeg.length - 1] = 0;
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        client.readTo(0x1009, original, 4 * 1024 * 1024, camera.jpeg.length, null, 1);
        check(Arrays.equals(original.toByteArray(), camera.jpeg), "original transport remains byte-preserving for controller validation");
        client.close();
    }
    private static void metadataPageAndThumbnailCache() throws Exception {
        Camera camera = new Camera(); camera.objectCount = 64; FujifilmPhotoClient client = camera.client();
        client.connect("camera", 55740, new byte[16], "app");
        int before = camera.operations.size();
        client.beginPhotoPage();
        try { for (int i = 1; i <= 24; i++) client.getData(0x1008, 65536, i); }
        finally { client.endPhotoPage(); }
        check(camera.operations.size() - before == 28, "24 metadata reads use 28 operations instead of 120");
        check(camera.correctSize.equals(List.of(1L, 0L)), "correct-size negotiation restored once per page");
        before = camera.operations.size(); byte[] thumb = client.getData(0x100a, 4096, 1);
        thumb[0] = 0; byte[] reused = client.getData(0x100a, 4096, 1);
        check(camera.operations.size() == before + 1 && (reused[0] & 255) == 255, "repeated preview is cached without caller mutation");
        client.getData(0x1008, 65536, 1); before = camera.operations.size();
        client.getData(0x100a, 4096, 1);
        check(camera.operations.size() == before, "unchanged fresh descriptor retains cached thumbnail");
        client.close();
    }
    private static void cacheInvalidationAndBounds() throws Exception {
        Camera camera = new Camera(); camera.objectCount = 64; FujifilmPhotoClient client = camera.client();
        client.connect("camera", 55740, new byte[16], "app");
        client.beginPhotoPage();
        try { for (int i = 1; i <= 49; i++) client.getData(0x1008, 65536, i); }
        finally { client.endPhotoPage(); }
        for (int i = 1; i <= 49; i++) client.getData(0x100a, 4096, i);
        int before = camera.operations.size(); client.getData(0x100a, 4096, 1);
        check(camera.operations.size() == before + 1, "48-entry bound evicts oldest thumbnail");
        camera.metadataRevision = "-changed"; client.getData(0x1008, 65536, 1);
        before = camera.operations.size(); client.getData(0x100a, 4096, 1);
        check(camera.operations.size() == before + 1, "changed descriptor invalidates reused handle pixels");
        camera.objectCount++; client.readAlbumDeviceInfo(); before = camera.operations.size();
        client.getData(0x100a, 4096, 1);
        check(camera.operations.size() == before + 1, "changed object count invalidates session thumbnail cache");
        client.beginPhotoPage(); camera.wrongTransaction = true;
        expectIOException(() -> client.getData(0x1008, 65536, 2));
        before = camera.operations.size(); client.endPhotoPage();
        check(camera.operations.size() == before && camera.allClosed(), "closed failed page cleanup never sends another command");
    }
    private static void invalidScalarLengths() throws Exception {
        for (int length : new int[]{0, 3, 5}) {
            Camera camera = new Camera(); camera.invalidPropertyLength = length;
            FujifilmPhotoClient client = camera.client();
            try {
                client.connect("camera", 55740, new byte[16], "app");
                throw new AssertionError("Invalid scalar width accepted");
            } catch (IOException error) {
                check(error.getMessage().contains("0xDF21"), "error identifies exact property rather than generic length failure");
                if (length <= 4) check(error.getMessage().contains(length + " 字节"), "invalid property length is reported");
            }
            check(camera.allClosed() && !client.isOpen(), "malformed property closes channels");
            check(!camera.operations.contains(0x101c), "malformed setup property cannot continue to remote mode");
        }
    }
    private static void channelFailures() throws Exception {
        for (int port : new int[]{55740, 55741, 55742}) {
            Camera camera = new Camera(); camera.refusedPort = port; FujifilmPhotoClient client = camera.client();
            try {
                client.connect("192.168.0.1", 55740, new byte[16], "app");
                throw new AssertionError("Refused socket accepted");
            } catch (IOException error) {
                check(error.getMessage().contains("192.168.0.1:" + port), "error names actual failed channel port");
                check(error.getMessage().contains(port == 55740 ? "命令" : port == 55741 ? "事件" : "预览"), "error names failed channel");
                if (port != 55740) check(error.getMessage().contains("命令握手已完成"), "aux failure does not misreport initial connection");
            }
            check(camera.allClosed(), "refused auxiliary closes existing command and other sockets");
        }
    }
    private static void happyPath() throws Exception {
        Camera camera = new Camera(); FujifilmPhotoClient client = camera.client();
        PtpDeviceInfo info = PtpDeviceInfo.parse(client.connect("192.168.0.1", 55740, new byte[16], "Nikon preset app"));
        check(info.manufacturer().equals("FUJIFILM") && info.model().equals("X-T20"), "actual ACK identity");
        check(info.supportsOperation(0x1009) && !info.supportsOperation(0x100b), "adapter is read only");
        check(camera.ports.equals(List.of(55740, 55741, 55742)), "Fuji channels");
        check(camera.operations.indexOf(0x101c) < camera.operations.indexOf(0x1018), "remote opened then terminated");
        check(camera.states.equals(List.of("df01=5", "df24=131084", "df00=6", "df01=11", "df25=5")), "gallery negotiation");
        check(Arrays.equals(client.getData(0x1004, 32), new byte[]{1,0,0,0,1,0,0,0}), "virtual storage");
        check(Arrays.equals(client.getData(0x1007, 32, 1, 0x3801, 0), new byte[]{2,0,0,0,1,0,0,0,2,0,0,0}), "Fuji indexed handles");
        PtpObjectInfo photo = PtpObjectInfo.parse(client.getData(0x1008, 65536, 1));
        check(photo.compressedSize() == camera.jpeg.length && photo.storageId() == 1, "correct metadata size");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        long count = client.readTo(0x1009, output, 4*1024*1024, camera.jpeg.length, null, 1);
        check(count == camera.jpeg.length && Arrays.equals(output.toByteArray(), camera.jpeg), "exact full transfer");
        check(camera.chunks.equals(List.of(0x100000, 37)), "bounded partial-object chunks");
        int before = camera.operations.size(); client.readAlbumDeviceInfo();
        check(camera.operations.size() == before + 1 && camera.operations.get(before) == 0x1015, "network heartbeat");
        client.close(); check(camera.allClosed(), "all channels close");
    }
    private static void malformedTransaction() throws Exception {
        Camera camera = new Camera(); FujifilmPhotoClient client = camera.client();
        client.connect("camera", 55740, new byte[16], "app"); camera.wrongTransaction = true;
        expectIOException(() -> client.readAlbumDeviceInfo());
        check(!client.isOpen() && client.isProtocolUnconfirmed() && camera.allClosed(), "transaction mismatch closes all channels");
    }
    private static void oversizedPacket() throws Exception {
        Camera camera = new Camera(); FujifilmPhotoClient client = camera.client();
        client.connect("camera", 55740, new byte[16], "app"); camera.oversize = true;
        expectIOException(() -> client.getData(0x100a, 4096, 1));
        check(!client.isOpen() && camera.allClosed(), "oversize before allocation");
    }
    private static void shortDownload() throws Exception {
        Camera camera = new Camera(); FujifilmPhotoClient client = camera.client();
        client.connect("camera", 55740, new byte[16], "app"); camera.shortChunk = true;
        expectIOException(() -> client.readTo(0x1009, new ByteArrayOutputStream(), 4*1024*1024, camera.jpeg.length, null, 1));
        check(client.isProtocolUnconfirmed() && camera.allClosed(), "short data cannot succeed");
    }
    private static void unsupportedWrite() throws Exception {
        Camera camera = new Camera(); FujifilmPhotoClient client = camera.client();
        client.connect("camera", 55740, new byte[16], "app"); int before = camera.operations.size();
        expectIOException(() -> client.getData(0x100b, 32, 1));
        check(camera.operations.size() == before, "delete never reaches camera");
    }
    private static void cancelledConnection() throws Exception {
        Camera camera = new Camera(); FujifilmPhotoClient client = camera.client(); client.close();
        expectIOException(() -> client.connect("camera", 55740, new byte[16], "app"));
        check(camera.ports.isEmpty(), "cancel before connect opens no sockets");
    }
    private static void cancelledDownload() throws Exception {
        Camera camera = new Camera(); FujifilmPhotoClient client = camera.client();
        client.connect("camera", 55740, new byte[16], "app");
        expectIOException(() -> client.readTo(0x1009, new ByteArrayOutputStream(), 4*1024*1024, camera.jpeg.length,
                (received, total) -> {
                    if (received > 0) {
                        Thread cancellation = new Thread(client::close); cancellation.start();
                        try { cancellation.join(1000); } catch (InterruptedException e) { throw new AssertionError(e); }
                        check(!cancellation.isAlive(), "cancellation does not wait for transaction monitor");
                    }
                }, 1));
        check(camera.allClosed() && camera.chunks.size() == 1, "cancellation closes every channel and stops further chunks");
    }
    private interface Attempt { void run() throws Exception; }
    private static void expectIOException(Attempt action) throws Exception {
        try { action.run(); throw new AssertionError("Expected IOException"); } catch (IOException expected) {}
    }
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }

    private static final class Camera {
        final byte[] jpeg = new byte[0x100000 + 37];
        final List<Integer> ports = new ArrayList<>(), operations = new ArrayList<>(), chunks = new ArrayList<>();
        final List<String> states = new ArrayList<>();
        final List<Long> correctSize = new ArrayList<>();
        final List<FakeSocket> sockets = new ArrayList<>();
        boolean wrongTransaction, oversize, shortChunk;
        int invalidPropertyLength = -1, refusedPort;
        int objectCount = 2;
        String metadataRevision = "";
        String identity = "X-T20";
        long expectedTransaction = 1, openCaptureTransaction;
        byte[] pendingSet;
        byte[] thumbnail;
        Camera() { jpeg[0] = (byte)255; jpeg[1] = (byte)216; jpeg[jpeg.length - 2] = (byte)255; jpeg[jpeg.length - 1] = (byte)217; }
        FujifilmPhotoClient client() {
            return new FujifilmPhotoClient((host, port, timeout) -> {
                if (port == refusedPort) throw new java.net.ConnectException("synthetic connection refused");
                ports.add(port); FakeSocket socket = new FakeSocket(this, port == 55740); sockets.add(socket); return socket;
            });
        }
        boolean allClosed() { for (FakeSocket socket : sockets) if (!socket.closed) return false; return true; }
        void accept(FakeSocket socket, byte[] packet) throws IOException {
            if (u32(packet, 4) == 1 && packet.length == 82) {
                check(u32(packet, 8) == 0x8f53e4f2L, "Fuji protocol magic");
                byte[] ack = new byte[28 + identity.length() * 2 + 2]; put32(ack, 0, ack.length); put32(ack, 4, 2);
                System.arraycopy(identity.getBytes(StandardCharsets.UTF_16LE), 0, ack, 28, identity.length() * 2); socket.add(ack); return;
            }
            int operation = u16(packet, 6); long transaction = u32(packet, 8);
            if (u16(packet, 4) == 2) {
                check(pendingSet != null && transaction == u32(pendingSet, 8), "property data follows command");
                long code = u32(pendingSet, 12); long value = packet.length == 14 ? u16(packet, 12) : u32(packet, 12);
                if (code != 0xd227) states.add(Long.toHexString(code) + "=" + value);
                else correctSize.add(value);
                pendingSet = null; socket.add(PtpContainer.response(0x2001, transaction).toBytes()); return;
            }
            check(u16(packet, 4) == 1 && transaction == expectedTransaction++, "USB PTP transaction sequence");
            operations.add(operation);
            if (operation == 0x1016) { pendingSet = packet; return; }
            if (operation == 0x101c) openCaptureTransaction = transaction;
            if (operation == 0x1018) check(u32(packet, 12) == openCaptureTransaction, "terminate exact open capture");
            byte[] data = null;
            if (operation == 0x1015) {
                int property = (int)u32(packet, 12);
                if (property == 0xd212) {
                    data = new byte[14]; put16(data, 0, 2); put16(data, 2, 0xdf00); put32(data, 4, 6);
                    put16(data, 8, 0xd222); put32(data, 10, objectCount);
                } else if (property == 0xdf21) {
                    // Mixed scalar widths occur in real protocol implementations;
                    // previously every fixture value was uint32, hiding uint8 rejection.
                    data = new byte[invalidPropertyLength < 0 ? 2 : invalidPropertyLength];
                } else if (property == 0xd244) {
                    data = new byte[]{(byte)255};
                } else {
                    data = new byte[4]; put32(data, 0, property == 0xdf24 ? 0x20004 : 1);
                }
            }
            if (operation == 0x1008) data = new PtpObjectInfo.Builder().storageId(42).objectFormat(0x3801)
                    .compressedSize(jpeg.length).filename("DSCF" + u32(packet, 12) + metadataRevision + ".JPG").imagePixWidth(6000).imagePixHeight(4000).build().toBytes();
            if (operation == 0x100a) {
                if (oversize) { byte[] length = new byte[4]; put32(length, 0, 0xffffffffL); socket.add(length); return; }
                if (thumbnail != null) data = thumbnail.clone();
                else {
                    data = Arrays.copyOf(jpeg, 200);
                    data[data.length - 2] = (byte)255; data[data.length - 1] = (byte)217;
                }
            }
            if (operation == 0x101b) {
                int offset = (int)u32(packet, 16), count = (int)u32(packet, 20); chunks.add(count);
                data = Arrays.copyOfRange(jpeg, offset, offset + count - (shortChunk ? 1 : 0));
            }
            long replyTransaction = wrongTransaction ? transaction + 1 : transaction;
            if (data != null) socket.add(PtpContainer.data(operation, replyTransaction, data).toBytes());
            socket.add(PtpContainer.response(0x2001, replyTransaction).toBytes());
        }
    }
    private static final class FakeSocket extends Socket {
        final Camera camera; final boolean command; boolean closed;
        final ArrayDeque<ByteArrayInputStream> queue = new ArrayDeque<>();
        FakeSocket(Camera camera, boolean command) { this.camera = camera; this.command = command; }
        void add(byte[] bytes) { queue.add(new ByteArrayInputStream(bytes)); }
        @Override public InputStream getInputStream() {
            return new InputStream() {
                @Override public int read() throws IOException { byte[] b = new byte[1]; return read(b, 0, 1) < 0 ? -1 : b[0] & 255; }
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    if (closed) throw new IOException("closed");
                    while (!queue.isEmpty() && queue.peek().available() == 0) queue.remove();
                    if (queue.isEmpty()) throw new IOException("scripted response missing");
                    return queue.peek().read(b, off, Math.min(len, 97)); // force fragmented TCP reads
                }
            };
        }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) { throw new AssertionError("use framed writes"); }
                @Override public void write(byte[] b) throws IOException { camera.accept(FakeSocket.this, b); }
            };
        }
        @Override public void setTcpNoDelay(boolean value) {}
        @Override public void setSoTimeout(int value) {}
        @Override public void close() { closed = true; }
    }
    private static int u16(byte[] b, int at) { return (b[at] & 255) | ((b[at + 1] & 255) << 8); }
    private static long u32(byte[] b, int at) { return (b[at] & 255L) | ((b[at+1]&255L)<<8) | ((b[at+2]&255L)<<16) | ((b[at+3]&255L)<<24); }
    private static void put16(byte[] b, int at, int value) { b[at]=(byte)value; b[at+1]=(byte)(value>>>8); }
    private static void put32(byte[] b, int at, long value) { for(int i=0;i<4;i++) b[at+i]=(byte)(value>>>(8*i)); }
}

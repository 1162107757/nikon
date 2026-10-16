package com.nikonpreset.usbprobe.album;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import com.nikonpreset.usbprobe.ptp.PtpDeviceInfo;
import com.nikonpreset.usbprobe.ptp.PtpObjectHandles;
import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import com.nikonpreset.usbprobe.ptp.PtpResponseException;
import com.nikonpreset.usbprobe.ptp.PtpStorageIds;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Serialized asynchronous wireless browsing. Callbacks run on the main thread. */
public final class AlbumController implements AutoCloseable {
    public static final int DEFAULT_PORT = 15740;
    public interface Callback<T> { void onSuccess(T result); void onError(String message); }
    public interface DownloadCallback extends Callback<DownloadedPhoto> { void onProgress(long received, long total); }
    private interface Work<T> { T run() throws Exception; }
    private static final int MAX_HANDLES = 20000;
    private static final long MAX_PHOTO_BYTES = 512L * 1024 * 1024;
    private final Context context;
    private final ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicInteger generation = new AtomicInteger();
    private final Object lifecycle = new Object();
    private volatile PtpIpClient active;
    private volatile boolean closed;
    private volatile Runnable connectionListener;
    private long[] handles = new long[0]; // Worker-owned snapshot.
    private String session = "";
    private PtpDeviceInfo deviceInfo;

    public AlbumController(Context context) {
        this.context = context.getApplicationContext();
        connectivity = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** A suggestion only. The UI must allow editing and confirming the camera address. */
    public String suggestHost() {
        try {
            Network activeNetwork = connectivity == null ? null : connectivity.getActiveNetwork();
            if (isWifi(activeNetwork)) { String gateway = gateway(activeNetwork); if (!gateway.isEmpty()) return gateway; }
            if (connectivity != null) for (Network n : connectivity.getAllNetworks()) if (isWifi(n)) {
                String gateway = gateway(n); if (!gateway.isEmpty()) return gateway;
            }
        } catch (SecurityException ignored) { }
        return "";
    }

    public void connect(String host, int port, Callback<ConnectionInfo> callback) {
        disconnect();
        final int connectionGeneration = generation.get();
        submit(connectionGeneration, callback, () -> {
            InetAddress address = numericIpv4(host);
            if (port < 1 || port > 65535) throw new IOException("相机端口应为 1–65535。");
            Network network = selectWifi(host.trim());
            PtpIpClient client = new PtpIpClient((ignoredHost, requestedPort, timeout) -> {
                Socket socket = network.getSocketFactory().createSocket();
                try { socket.connect(new InetSocketAddress(address, requestedPort), timeout); return socket; }
                catch (IOException | RuntimeException error) { try { socket.close(); } catch (IOException ignored) { } throw error; }
            });
            client.setOnClosed(() -> deliver(connectionGeneration, () -> {
                Runnable listener = connectionListener;
                if (active == client && listener != null) listener.run();
            }));
            synchronized (lifecycle) {
                if (closed || connectionGeneration != generation.get()) { client.close(); throw new IOException("无线连接已取消。"); }
                active = client;
            }
            try {
                byte[] info = client.connect(host.trim(), port, clientGuid(), "Preset Album");
                deviceInfo = PtpDeviceInfo.parseRedacted(info);
                if (!deviceInfo.manufacturer().toLowerCase(Locale.ROOT).contains("nikon")) {
                    throw new IOException("这不是已支持的尼康无线相机。");
                }
                for (int op : new int[] {0x1004, 0x1007, 0x1008, 0x1009}) if (!deviceInfo.supportsOperation(op)) {
                    throw new IOException("相机未提供当前相册需要的照片读取能力。");
                }
                LinkedHashSet<Long> collected = new LinkedHashSet<>();
                long[] storages = PtpStorageIds.parse(client.getData(0x1004, 4096));
                if (storages.length > 64) throw new IOException("相机存储空间数量超过浏览上限。");
                for (long storage : storages) {
                    byte[] entries;
                    try {
                        try { entries = client.getData(0x1007, 1024 * 1024, storage, 0x3801, 0); }
                        catch (PtpResponseException unsupportedFormat) {
                            if (unsupportedFormat.responseCode() != 0x2014) throw unsupportedFormat;
                            entries = client.getData(0x1007, 1024 * 1024, storage, 0, 0);
                        }
                    } catch (PtpResponseException storageError) {
                        if (storageError.responseCode() == 0x2013) continue;
                        throw storageError;
                    }
                    for (long handle : PtpObjectHandles.parse(entries)) {
                        if (handle == 0 || handle == 0xffffffffL) throw new IOException("相机返回了无效照片索引。");
                        collected.add(handle);
                        if (collected.size() > MAX_HANDLES) throw new IOException("相机对象过多，本版一次最多浏览 20000 个对象。");
                    }
                }
                handles = new long[collected.size()]; int i = 0;
                for (long handle : collected) handles[i++] = handle;
                session = UUID.randomUUID().toString();
                return new ConnectionInfo(deviceInfo.model(), host.trim(), port, handles.length);
            } catch (Exception error) { client.close(); throw error; }
        });
    }

    public boolean isConnected() { PtpIpClient client = active; return !closed && client != null && client.isOpen(); }
    /** Optional main-thread notification for an idle connection lost on the event channel. */
    public void setConnectionListener(Runnable listener) { connectionListener = listener; }

    /** nextOffset is a snapshot cursor, not a camera-side page number. */
    public void loadPage(int offset, int limit, Callback<PhotoPage> callback) {
        submit(callback, () -> {
            PtpIpClient client = requireClient();
            if (offset < 0 || offset > handles.length || limit < 1 || limit > 60) throw new IOException("照片分页参数无效。");
            List<PhotoItem> photos = new ArrayList<>();
            int cursor = offset, scanned = 0;
            while (cursor < handles.length && photos.size() < limit && scanned++ < 300) {
                long handle = handles[cursor++];
                PtpObjectInfo info;
                try { info = PtpObjectInfo.parse(client.getData(0x1008, 65536, handle)); }
                catch (PtpResponseException error) { if (error.responseCode() == 0x2009) continue; throw error; }
                if (info.objectFormat() == 0x3801) photos.add(new PhotoItem(session, handle, info));
            }
            return new PhotoPage(photos, cursor, handles.length);
        });
    }

    public void loadThumbnail(PhotoItem photo, Callback<byte[]> callback) {
        submit(callback, () -> {
            PtpIpClient client = requirePhoto(photo);
            if (!deviceInfo.supportsOperation(0x100a)) throw new IOException("相机未提供缩略图读取能力。");
            byte[] jpeg = client.getData(0x100a, 4 * 1024 * 1024, photo.handle());
            if (!jpegHeader(jpeg)) throw new IOException("相机没有返回可显示的 JPEG 缩略图。");
            return jpeg;
        });
    }

    public void download(PhotoItem photo, DownloadCallback callback) {
        final int requestedGeneration = generation.get();
        submit(requestedGeneration, callback, () -> {
            PtpIpClient client = requirePhoto(photo);
            if (photo.byteSize() > MAX_PHOTO_BYTES) throw new IOException("照片超过本版 512 MB 下载上限。");
            File directory = downloadDirectory();
            String display = safeJpegName(photo.filename());
            File destination = new File(directory, System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + "__" + display);
            File temporary = File.createTempFile("receiving-", ".part", directory);
            boolean completed = false;
            try {
                final long[] last = {0};
                try (FileOutputStream file = new FileOutputStream(temporary)) {
                    client.readTo(0x1009, file, MAX_PHOTO_BYTES, photo.byteSize(), (received, total) -> {
                        long now = System.nanoTime();
                        if (received == total || now - last[0] >= 200000000L) {
                            last[0] = now;
                            deliver(requestedGeneration, () -> callback.onProgress(received, total));
                        }
                    }, photo.handle());
                    file.getFD().sync();
                }
                try (FileInputStream file = new FileInputStream(temporary)) {
                    if (file.read() != 255 || file.read() != 216) throw new IOException("相机返回的照片不是受支持的 JPEG。");
                }
                if (destination.exists() || !temporary.renameTo(destination)) throw new IOException("照片已接收，但本机保存未完成。");
                completed = true;
                return new DownloadedPhoto(destination, display);
            } finally { if (!completed && temporary.exists()) temporary.delete(); }
        });
    }

    public void listDownloads(Callback<List<DownloadedPhoto>> callback) {
        submit(callback, () -> {
            File[] files = downloadDirectory().listFiles(file -> file.isFile() && jpegFilename(file.getName()));
            if (files == null) throw new IOException("无法读取已下载照片目录。");
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            List<DownloadedPhoto> photos = new ArrayList<>();
            for (File file : files) {
                String name = file.getName(); int marker = name.indexOf("__");
                photos.add(new DownloadedPhoto(file, marker < 0 ? name : name.substring(marker + 2)));
            }
            return Collections.unmodifiableList(photos);
        });
    }

    /** Disconnect cancels network activity; queued callbacks from the old generation are suppressed. */
    public void disconnect() {
        generation.incrementAndGet();
        synchronized (lifecycle) { PtpIpClient client = active; active = null; if (client != null) client.close(); }
    }
    @Override public void close() { closed = true; disconnect(); worker.shutdownNow(); }

    private <T> void submit(Callback<T> callback, Work<T> work) {
        submit(generation.get(), callback, work);
    }
    private <T> void submit(int request, Callback<T> callback, Work<T> work) {
        if (callback == null) throw new IllegalArgumentException("callback");
        if (closed) return;
        try {
            worker.execute(() -> {
                if (closed || request != generation.get()) return;
                try { T result = work.run(); deliver(request, () -> callback.onSuccess(result)); }
                catch (Exception error) {
                    String message = error instanceof PtpResponseException
                            ? "相机未完成照片读取（" + String.format(Locale.ROOT, "%04X", ((PtpResponseException) error).responseCode()) + "），请检查相机状态。"
                            : error.getMessage();
                    deliver(request, () -> callback.onError(message == null ? "无线相册操作未完成。" : message));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }
    private void deliver(int request, Runnable callback) { if (!closed) main.post(() -> { if (!closed && request == generation.get()) callback.run(); }); }
    private PtpIpClient requireClient() throws IOException { PtpIpClient c = active; if (c == null || !c.isOpen()) throw new IOException("无线连接已断开，请重新连接相机。"); return c; }
    private PtpIpClient requirePhoto(PhotoItem photo) throws IOException {
        PtpIpClient c = requireClient();
        if (photo == null || !session.equals(photo.session)) throw new IOException("照片来自旧的连接，请刷新列表后再操作。");
        return c;
    }
    private File downloadDirectory() throws IOException {
        File parent = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (parent == null) parent = new File(context.getFilesDir(), "pictures");
        File directory = new File(parent, "camera-album");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建本机照片目录。");
        return directory;
    }
    private Network selectWifi(String host) throws IOException {
        if (connectivity == null) throw new IOException("无法读取手机网络状态。");
        Network preferred = connectivity.getActiveNetwork();
        Network single = null; int count = 0;
        for (Network n : connectivity.getAllNetworks()) if (isWifi(n)) {
            if (gateway(n).equals(host)) return n;
            single = n; count++;
        }
        if (isWifi(preferred)) return preferred;
        if (count == 1) return single;
        if (count > 1) throw new IOException("检测到多条 Wi-Fi 连接，请只保留需要的相机连接后重试。");
        throw new IOException("请先在手机系统中连接相机 Wi-Fi，再返回相册。");
    }
    private boolean isWifi(Network network) {
        NetworkCapabilities c = network == null || connectivity == null ? null : connectivity.getNetworkCapabilities(network);
        return c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
    private String gateway(Network network) {
        LinkProperties properties = connectivity.getLinkProperties(network);
        if (properties != null) for (RouteInfo route : properties.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (route.isDefaultRoute() && gateway instanceof Inet4Address && !gateway.isAnyLocalAddress()) return gateway.getHostAddress();
        }
        return "";
    }
    private byte[] clientGuid() {
        android.content.SharedPreferences preferences = context.getSharedPreferences("wireless-album", Context.MODE_PRIVATE);
        String value = preferences.getString("client-guid", "");
        UUID guid;
        try { guid = UUID.fromString(value); }
        catch (IllegalArgumentException error) { guid = UUID.randomUUID(); preferences.edit().putString("client-guid", guid.toString()).apply(); }
        return ByteBuffer.allocate(16).putLong(guid.getMostSignificantBits()).putLong(guid.getLeastSignificantBits()).array();
    }
    private static InetAddress numericIpv4(String host) throws IOException {
        if (host == null || !host.trim().matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IOException("请输入相机的 IPv4 地址。");
        String[] parts = host.trim().split("\\."); byte[] address = new byte[4];
        for (int i = 0; i < 4; i++) { int value = Integer.parseInt(parts[i]); if (value > 255) throw new IOException("相机 IP 地址无效。"); address[i] = (byte) value; }
        InetAddress parsed = InetAddress.getByAddress(address);
        if (parsed.isAnyLocalAddress() || parsed.isLoopbackAddress() || parsed.isMulticastAddress()) throw new IOException("请输入相机的局域网地址。");
        return parsed;
    }
    private static boolean jpegHeader(byte[] bytes) { return bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216; }
    private static String safeJpegName(String name) {
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < name.length() && safe.length() < 100; i++) {
            char c = name.charAt(i); safe.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' ? c : '_');
        }
        if (safe.length() == 0) safe.append("photo");
        String result = safe.toString();
        if (!jpegFilename(result)) result += ".jpg";
        return result;
    }
    private static boolean jpegFilename(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }
}

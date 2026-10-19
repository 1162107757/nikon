package com.nikonpreset.usbprobe.album;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.UUID;

/** Shared Wi-Fi routing and initiator identity for album reads and preset registration. */
public final class CameraWifiNetwork {
    public static final int DEFAULT_PORT = 15740;
    private final Context context;
    private final ConnectivityManager connectivity;
    private static final ArrayList<SocketNetwork> cameraSockets = new ArrayList<>();
    private static Object routeOwner;
    private static long routeToken, routeRevision;
    private static Network requestedNetwork;

    /** A specific connection owns a fail-closed route until explicitly released. */
    public static synchronized void requireCameraNetwork(Object owner, long token) {
        if (owner == null) throw new IllegalArgumentException("Missing camera network owner");
        routeOwner = owner; routeToken = token; requestedNetwork = null; routeRevision++;
    }
    public static synchronized boolean useCameraNetwork(Object owner, long token, Network network) {
        if (routeOwner != owner || routeToken != token || network == null) return false;
        requestedNetwork = network; routeRevision++; return true;
    }
    public static synchronized boolean cameraNetworkLost(Object owner, long token) {
        if (routeOwner != owner || routeToken != token) return false;
        requestedNetwork = null; routeRevision++; return true;
    }
    public static synchronized boolean releaseCameraNetwork(Object owner, long token) {
        if (routeOwner != owner || routeToken != token) return false;
        routeOwner = null; routeToken = 0; requestedNetwork = null; routeRevision++; return true;
    }
    private static final class SocketNetwork {
        final WeakReference<Socket> socket;
        final Network network;
        SocketNetwork(Socket socket, Network network) { this.socket = new WeakReference<>(socket); this.network = network; }
    }

    /** The physical PTP channels are authoritative; a default-network change is not. */
    public static synchronized Network socketNetwork() {
        Network selected = null;
        boolean ambiguous = false;
        java.util.Iterator<SocketNetwork> entries = cameraSockets.iterator();
        while (entries.hasNext()) {
            SocketNetwork item = entries.next();
            Socket socket = item.socket.get();
            if (socket == null || socket.isClosed()) { entries.remove(); continue; }
            if (selected == null) selected = item.network;
            else if (!selected.equals(item.network)) ambiguous = true;
        }
        return ambiguous ? null : selected;
    }

    private static synchronized void rememberSocket(Socket socket, Network network) {
        socketNetwork();
        cameraSockets.add(new SocketNetwork(socket, network));
    }

    public CameraWifiNetwork(Context context) {
        this.context = context.getApplicationContext();
        connectivity = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** A suggested gateway, never proof that the connected network belongs to a camera. */
    public String suggestHost() {
        try {
            Network active = connectivity == null ? null : connectivity.getActiveNetwork();
            if (isWifi(active)) { String address = gateway(active); if (!address.isEmpty()) return address; }
            if (connectivity != null) for (Network network : connectivity.getAllNetworks()) if (isWifi(network)) {
                String address = gateway(network); if (!address.isEmpty()) return address;
            }
        } catch (SecurityException ignored) { }
        return "";
    }

    /** Both PTP/IP channels stay on this Wi-Fi even when Android prefers mobile data. */
    public PtpIpClient.SocketConnector connector(String host, int port) throws IOException {
        InetAddress address = numericIpv4(host);
        if (port < 1 || port > 65535) throw new IOException("相机端口应为 1–65535。");
        final Network network;
        final long revision;
        synchronized (CameraWifiNetwork.class) {
            if (routeOwner != null) {
                if (requestedNetwork == null || !isWifi(requestedNetwork)) throw new IOException("相机 Wi-Fi 尚未就绪或已断开，请重新连接相机。");
                network = requestedNetwork;
            } else network = selectWifi(host.trim());
            revision = routeRevision;
        }
        return (requestedHost, requestedPort, timeout) -> {
            if (!host.trim().equals(requestedHost) || requestedPort != port) throw new IOException("无线连接地址发生变化，请重新连接。");
            synchronized (CameraWifiNetwork.class) {
                if (revision != routeRevision) throw new IOException("相机 Wi-Fi 连接已切换，已取消旧连接。");
            }
            Socket socket = network.getSocketFactory().createSocket();
            try {
                socket.connect(new InetSocketAddress(address, port), timeout);
                synchronized (CameraWifiNetwork.class) {
                    if (revision != routeRevision) throw new IOException("相机 Wi-Fi 连接已切换，已取消旧连接。");
                    rememberSocket(socket, network);
                }
                return socket;
            }
            catch (IOException | RuntimeException error) { try { socket.close(); } catch (IOException ignored) { } throw error; }
        };
    }

    public byte[] clientGuid() {
        android.content.SharedPreferences preferences = context.getSharedPreferences("wireless-album", Context.MODE_PRIVATE);
        String value = preferences.getString("client-guid", "");
        UUID guid;
        try { guid = UUID.fromString(value); }
        catch (IllegalArgumentException error) {
            guid = UUID.randomUUID();
            if (!preferences.edit().putString("client-guid", guid.toString()).commit()) throw new IllegalStateException("无法保存无线客户端身份，请重试。");
        }
        return ByteBuffer.allocate(16).putLong(guid.getMostSignificantBits()).putLong(guid.getLeastSignificantBits()).array();
    }

    public static void validateAddress(String host, int port) throws IOException {
        numericIpv4(host);
        if (port < 1 || port > 65535) throw new IOException("相机端口应为 1–65535。");
    }

    private Network selectWifi(String host) throws IOException {
        if (connectivity == null) throw new IOException("无法读取手机网络状态。");
        Network preferred = connectivity.getActiveNetwork(), single = null; int count = 0;
        for (Network network : connectivity.getAllNetworks()) if (isWifi(network)) {
            if (gateway(network).equals(host)) return network;
            single = network; count++;
        }
        if (isWifi(preferred)) return preferred;
        if (count == 1) return single;
        if (count > 1) throw new IOException("检测到多条 Wi-Fi 连接，请只保留相机连接后重试。");
        throw new IOException("请先在手机系统中连接相机 Wi-Fi，再返回继续。");
    }

    private boolean isWifi(Network network) {
        NetworkCapabilities capabilities = network == null || connectivity == null ? null : connectivity.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
    private String gateway(Network network) {
        LinkProperties properties = connectivity.getLinkProperties(network);
        if (properties != null) for (RouteInfo route : properties.getRoutes()) {
            InetAddress address = route.getGateway();
            if (route.isDefaultRoute() && address instanceof Inet4Address && !address.isAnyLocalAddress()) return address.getHostAddress();
        }
        return "";
    }
    private static InetAddress numericIpv4(String host) throws IOException {
        if (host == null || !host.trim().matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IOException("请输入相机的 IPv4 地址。");
        String[] parts = host.trim().split("\\."); byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int value = Integer.parseInt(parts[i]);
            if (value > 255) throw new IOException("相机 IP 地址无效。");
            bytes[i] = (byte) value;
        }
        InetAddress address = InetAddress.getByAddress(bytes);
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()
                || host.trim().equals("255.255.255.255")) throw new IOException("请输入相机的局域网地址。");
        return address;
    }
}

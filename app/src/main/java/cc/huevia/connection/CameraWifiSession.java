package cc.huevia.connection;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import cc.huevia.album.CameraWifiNetwork;
import cc.huevia.album.ConnectionTrace;
import java.nio.charset.StandardCharsets;

/** Application-owned camera Wi-Fi route. No Activity is retained after settlement. */
public final class CameraWifiSession {
    public interface Listener {
        void onConnected();
        void onFailure(String message);
    }
    private static final long TIMEOUT_MILLIS = 60_000;
    private final ConnectivityManager connectivity;
    private final CameraWifiNetwork wifiNetwork;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private ConnectivityManager.NetworkCallback callback;
    private Listener listener;
    private Runnable timeout;
    private Network network;
    private long generation;
    private boolean connecting, routeOwned, deliveryPending, specificRequest;
    private String connectedSsid = "";
    private String eventPrefix = "WIFI_SPECIFIC_";

    public CameraWifiSession(Context context, Runnable changed) {
        connectivity = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        wifiNetwork = new CameraWifiNetwork(context);
        this.changed = changed == null ? () -> { } : changed;
    }

    /**
     * Adopt a system-connected Wi-Fi after an explicit user action and service
     * preparation. Returns 0 without side effects when no safe candidate exists.
     * This observes the exact Network; it neither joins Wi-Fi nor guarantees OS
     * retention. The normal service demand continues once PTP opens its sockets.
     */
    public long useConnectedWifi(Listener completion) {
        if (completion == null) throw new IllegalArgumentException("缺少 Wi-Fi 连接结果处理。");
        final long token;
        final Network selected;
        synchronized (this) {
            if (network != null && callback != null) {
                if (deliveryPending) throw new IllegalStateException("正在完成相机 Wi-Fi 连接，请稍候。");
                token = generation;
                deliver(token, completion, null);
                return token;
            }
            selected = wifiNetwork.connectedWifiNetwork();
            if (selected == null) return 0;
            if (connecting) ConnectionTrace.mark(eventPrefix + "REPLACED");
            retireRequestLocked();
            token = ++generation;
            listener = completion; connecting = true; connectedSsid = ""; routeOwned = true;
            deliveryPending = false; specificRequest = false; eventPrefix = "WIFI_CONNECTED_";
            CameraWifiNetwork.requireCameraNetwork(this, token);
        }
        ConnectionTrace.mark("WIFI_CONNECTED_OBSERVING");
        changed.run();
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            ConnectivityManager.NetworkCallback observed = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network available) {
                    if (selected.equals(available)) connected(token, this, available);
                }
                @Override public void onLost(Network lost) {
                    synchronized (CameraWifiSession.this) {
                        if (generation != token || callback != this || !selected.equals(lost)) return;
                        fail(token, "相机 Wi-Fi 已断开。请确认手机仍连接相机热点后重试。", network != null, "WIFI_CONNECTED_LOST");
                    }
                }
                @Override public void onBlockedStatusChanged(Network value, boolean blocked) {
                    synchronized (CameraWifiSession.this) {
                        if (generation != token || callback != this || !selected.equals(value)) return;
                        ConnectionTrace.mark(blocked ? "WIFI_CONNECTED_BLOCKED" : "WIFI_CONNECTED_UNBLOCKED");
                    }
                }
            };
            synchronized (this) {
                if (generation != token || !connecting) return token;
                callback = observed;
                timeout = () -> fail(token, "无法确认当前 Wi-Fi 连接，请检查相机热点后重试。", false, "WIFI_CONNECTED_TIMEOUT");
                main.postDelayed(timeout, TIMEOUT_MILLIS);
                connectivity.registerNetworkCallback(request, observed, main);
            }
        } catch (RuntimeException unavailable) {
            fail(token, "无法观察当前 Wi-Fi 连接，请检查系统网络状态后重试。", false, "WIFI_CONNECTED_FAILED");
        }
        return token;
    }

    /** Call only from an explicit user action after permission and foreground-service preparation. */
    public long connect(String ssid, String password, Listener completion) {
        validate(ssid, password);
        if (completion == null) throw new IllegalArgumentException("缺少 Wi-Fi 连接结果处理。");
        final long token;
        synchronized (this) {
            if (network != null && callback != null) {
                if (deliveryPending) throw new IllegalStateException("正在完成相机 Wi-Fi 连接，请稍候。");
                if (!connectedSsid.equals(ssid)) throw new IllegalStateException("相机 Wi-Fi 仍已连接，请先断开再连接其他相机。");
                token = generation;
                deliver(token, completion, null);
                return token;
            }
            if (connecting) ConnectionTrace.mark(eventPrefix + "REPLACED");
            retireRequestLocked();
            token = ++generation;
            listener = completion; connecting = true; connectedSsid = ssid; routeOwned = true;
            deliveryPending = false; specificRequest = true; eventPrefix = "WIFI_SPECIFIC_";
            CameraWifiNetwork.requireCameraNetwork(this, token);
        }
        ConnectionTrace.mark("WIFI_SPECIFIC_REQUESTED");
        changed.run();
        if (Build.VERSION.SDK_INT < 29) { fail(token, "当前系统需在 Wi-Fi 设置中手动连接相机。", false, "WIFI_SPECIFIC_UNSUPPORTED"); return token; }
        if (connectivity == null) { fail(token, "无法读取手机网络状态，请稍后重试。", false, "WIFI_SPECIFIC_FAILED"); return token; }
        try {
            WifiNetworkSpecifier.Builder peer = new WifiNetworkSpecifier.Builder().setSsid(ssid);
            if (!password.isEmpty()) peer.setWpa2Passphrase(password);
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(peer.build()).build();
            ConnectivityManager.NetworkCallback acquired = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network available) { connected(token, this, available); }
                @Override public void onUnavailable() { fail(token, "未能连接相机 Wi-Fi。请确认相机热点已开启，并检查网络名称与密码后重试。", false, "WIFI_SPECIFIC_UNAVAILABLE"); }
                @Override public void onLost(Network lost) {
                    synchronized (CameraWifiSession.this) { if (generation != token || network == null || !network.equals(lost)) return; }
                    fail(token, "相机 Wi-Fi 已断开。请确认相机热点仍已开启，再返回连接。", true, "WIFI_SPECIFIC_LOST");
                }
                @Override public void onBlockedStatusChanged(Network observed, boolean blocked) {
                    synchronized (CameraWifiSession.this) {
                        if (generation != token || callback != this || network == null || !network.equals(observed)) return;
                        ConnectionTrace.mark(blocked ? "WIFI_SPECIFIC_BLOCKED" : "WIFI_SPECIFIC_UNBLOCKED");
                    }
                }
            };
            synchronized (this) {
                if (generation != token || !connecting) return token;
                callback = acquired;
                timeout = () -> fail(token, "连接相机 Wi-Fi 超时，请确认热点已开启后重试。", false, "WIFI_SPECIFIC_TIMEOUT");
                main.postDelayed(timeout, TIMEOUT_MILLIS);
                connectivity.requestNetwork(request, acquired, main);
            }
        } catch (SecurityException denied) {
            fail(token, "未获准连接相机 Wi-Fi，请在系统中允许附近设备或定位权限后重试。", false, "WIFI_SPECIFIC_PERMISSION_DENIED");
        } catch (RuntimeException unavailable) {
            fail(token, "相机 Wi-Fi 连接未能启动，请检查系统 Wi-Fi 和连接授权后重试。", false, "WIFI_SPECIFIC_FAILED");
        }
        return token;
    }

    private void connected(long token, ConnectivityManager.NetworkCallback source, Network available) {
        Listener completion;
        synchronized (this) {
            if (generation != token || callback != source || !connecting) return;
            if (available == null || !CameraWifiNetwork.useCameraNetwork(this, token, available)) return;
            network = available; connecting = false;
            deliveryPending = true;
            completion = listener; listener = null;
            removeTimeoutLocked();
            ConnectionTrace.mark(eventPrefix + "AVAILABLE");
        }
        changed.run(); deliver(token, completion, null);
    }

    private void fail(long token, String message, boolean lost, String event) {
        Listener completion;
        synchronized (this) {
            if (generation != token || !routeOwned) return;
            if (lost ? network == null : !connecting) return;
            completion = listener; listener = null;
            retireRequestLocked(); connecting = false; network = null;
            // Keep the route guard after loss/failure: an old PTP retry must not
            // silently connect through a different router that has the same IP.
            CameraWifiNetwork.cameraNetworkLost(this, token);
            ConnectionTrace.mark(event);
        }
        changed.run(); deliver(token, completion, message);
    }

    /** Screen recreation may cancel only its own pending request; healthy connections remain service-owned. */
    public void cancel(long token) {
        synchronized (this) {
            if (generation != token || !connecting && !deliveryPending) return;
            ConnectionTrace.mark(eventPrefix + "CANCELLED");
            disconnectLocked();
        }
        changed.run();
    }

    /** Close PTP channels before this explicit release. */
    public void disconnect() {
        synchronized (this) { disconnectLocked(); }
        changed.run();
    }

    void disconnect(long token) {
        synchronized (this) { if (generation != token) return; disconnectLocked(); }
        changed.run();
    }

    private void disconnectLocked() {
        retireRequestLocked(); listener = null; connecting = deliveryPending = false; network = null; connectedSsid = "";
        if (routeOwned) { CameraWifiNetwork.releaseCameraNetwork(this, generation); ConnectionTrace.mark(eventPrefix + "RELEASED"); }
        routeOwned = specificRequest = false; generation++;
    }

    private void retireRequestLocked() {
        removeTimeoutLocked();
        ConnectivityManager.NetworkCallback held = callback; callback = null;
        if (held != null && connectivity != null) try { connectivity.unregisterNetworkCallback(held); }
        catch (RuntimeException alreadyReleased) { /* A platform timeout may already have released this request. */ }
        network = null;
    }
    private void removeTimeoutLocked() { if (timeout != null) { main.removeCallbacks(timeout); timeout = null; } }
    private void deliver(long token, Listener completion, String failure) {
        if (completion == null) return;
        main.post(() -> {
            final boolean connected;
            synchronized (CameraWifiSession.this) {
                if (generation != token) return;
                connected = network != null && callback != null;
                deliveryPending = false;
            }
            if (failure == null && connected) completion.onConnected();
            else completion.onFailure(failure == null ? "相机 Wi-Fi 已断开，请检查热点后重试。" : failure);
        });
    }

    public synchronized boolean isActive() { return connecting || network != null && callback != null; }
    public synchronized boolean isConnecting() { return connecting; }
    public synchronized boolean isConnected() { return network != null && callback != null; }
    public synchronized Network network() { return network; }
    /** A failed/lost specific route remains blocked until a new explicit connection or disconnect. */
    public synchronized boolean isRouteUnavailable() { return routeOwned && !connecting && network == null; }
    synchronized long token() { return generation; }
    /** Only a specifier request replaces the service's generic Wi-Fi demand. */
    synchronized boolean usesSpecificRoute() { return routeOwned && specificRequest; }

    private static void validate(String ssid, String password) {
        if (ssid == null || ssid.isEmpty() || ssid.getBytes(StandardCharsets.UTF_8).length > 32
                || !StandardCharsets.UTF_8.newEncoder().canEncode(ssid)) {
            throw new IllegalArgumentException("请填写相机显示的完整 Wi-Fi 名称（最多 32 字节）。");
        }
        if (password == null) throw new IllegalArgumentException("请填写 Wi-Fi 密码；开放网络可留空。");
        if (!password.isEmpty()) {
            if (password.length() < 8 || password.length() > 63) throw new IllegalArgumentException("Wi-Fi 密码应为 8–63 位英文、数字或符号。");
            for (int index = 0; index < password.length(); index++) if (password.charAt(index) < 32 || password.charAt(index) > 126) {
                throw new IllegalArgumentException("Wi-Fi 密码应使用英文、数字或符号。");
            }
        }
    }
}

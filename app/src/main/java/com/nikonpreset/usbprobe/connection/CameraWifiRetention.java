package com.nikonpreset.usbprobe.connection;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.util.Log;
import com.nikonpreset.usbprobe.album.ConnectionTrace;

/** A service-owned demand for Wi-Fi; observing a Network or holding a Wi-Fi lock is not a demand. */
final class CameraWifiRetention implements AutoCloseable {
    private final ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback callback;
    private Network target;

    CameraWifiRetention(Context context) {
        connectivity = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /** Called on the service thread, never from an Activity pause/stop callback. */
    synchronized void setRequired(Network value) {
        if (java.util.Objects.equals(target, value)) return;
        release();
        target = value;
        if (value == null) return;
        if (connectivity == null) return;
        // The camera hotspot has no Internet. Require only Wi-Fi, so this demand
        // also matches a local network without Internet or validation capabilities.
        // No specifier, SSID change, process-wide route binding or reconnect is used:
        // the PTP sockets stay bound to the Network the user already selected.
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        ConnectivityManager.NetworkCallback acquired = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                synchronized (CameraWifiRetention.this) {
                    if (callback != this) return;
                    if (!value.equals(network)) {
                        release();
                        ConnectionTrace.mark("WIFI_GENERIC_WRONG_NETWORK");
                        Log.w("HueviaConnection", "Wi-Fi retention matched a different network; request released, PTP unchanged");
                    } else { ConnectionTrace.mark("WIFI_GENERIC_MATCHED"); Log.i("HueviaConnection", "Wi-Fi retention matched the active PTP network"); }
                }
            }
            @Override public void onLost(Network network) {
                synchronized (CameraWifiRetention.this) {
                    if (callback != this || !value.equals(network)) return;
                    release();
                    ConnectionTrace.mark("WIFI_GENERIC_LOST");
                    Log.w("HueviaConnection", "Active PTP Wi-Fi was lost; retention released without reconnecting");
                }
            }
        };
        callback = acquired;
        try {
            connectivity.requestNetwork(request, acquired);
            ConnectionTrace.mark("WIFI_GENERIC_REQUESTED");
            Log.i("HueviaConnection", "Wi-Fi retention requested for the active PTP network");
        } catch (RuntimeException unavailable) {
            if (callback == acquired) callback = null;
            ConnectionTrace.mark("WIFI_GENERIC_UNAVAILABLE");
            // A denied optional protection must not close a healthy PTP session.
            // Do not retry on byte-progress notifications or leak callback requests.
            Log.w("HueviaConnection", "Camera Wi-Fi retention is unavailable", unavailable);
        }
    }

    synchronized boolean isRequested() { return callback != null; }

    private void release() {
        ConnectivityManager.NetworkCallback held = callback;
        callback = null;
        if (held == null || connectivity == null) return;
        try { connectivity.unregisterNetworkCallback(held); ConnectionTrace.mark("WIFI_GENERIC_RELEASED"); Log.i("HueviaConnection", "Wi-Fi retention released"); }
        catch (RuntimeException alreadyReleased) {
            Log.w("HueviaConnection", "Camera Wi-Fi retention was already released", alreadyReleased);
        }
    }

    @Override public synchronized void close() { target = null; release(); }
}

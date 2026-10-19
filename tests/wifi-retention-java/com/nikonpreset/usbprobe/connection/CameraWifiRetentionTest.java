package com.nikonpreset.usbprobe.connection;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;

public final class CameraWifiRetentionTest {
    private static int checks;
    public static void main(String[] ignored) {
        requestOutlivesActivityAndProgressUpdates();
        disconnectReleasesExactlyTheOwnedRequest();
        deniedProtectionDoesNotTerminateExistingSessionOrSpin();
        platformReleaseFailureDoesNotLeakOwnership();
        unavailableConnectivityIsOptional();
        neverRetainsAnotherWifiNetwork();
        staleCallbacksCannotReleaseReplacement();
        System.out.println("PASS: " + checks + " camera Wi-Fi retention checks");
    }

    private static void requestOutlivesActivityAndProgressUpdates() {
        ConnectivityManager manager = new ConnectivityManager();
        CameraWifiRetention retention = new CameraWifiRetention(new Context(manager));
        retention.setRequired(null);
        check(manager.requests == 0, "Photo-only/USB work must not request Wi-Fi");
        retention.setRequired(new Network(1));
        check(retention.isRequested() && manager.requests == 1, "Physical camera session retains Wi-Fi");
        check(manager.lastRequest.transports.size() == 1 && manager.lastRequest.transports.contains(NetworkCapabilities.TRANSPORT_WIFI),
                "Camera retention cannot match cellular data");
        check(manager.lastRequest.removedCapabilities.contains(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                "A no-Internet camera hotspot must satisfy the request");
        for (int update = 0; update < 500; update++) {
            retention.setRequired(new Network(1));
            check(retention.isRequested() && manager.requests == 1 && manager.releases == 0,
                    "Idle time, Activity background and per-file progress preserve one request");
        }
        retention.close();
    }

    private static void disconnectReleasesExactlyTheOwnedRequest() {
        ConnectivityManager manager = new ConnectivityManager();
        CameraWifiRetention retention = new CameraWifiRetention(new Context(manager));
        retention.setRequired(new Network(1));
        ConnectivityManager.NetworkCallback first = manager.lastCallback;
        retention.setRequired(null);
        retention.setRequired(null);
        check(!retention.isRequested() && manager.releases == 1 && manager.releasedCallback == first,
                "Explicit session end releases only its own request, once");
        retention.close();
        check(manager.releases == 1, "Destroy after idle stop cannot release twice");
        retention.setRequired(new Network(2));
        check(retention.isRequested() && manager.requests == 2 && manager.lastCallback != first,
                "An explicitly opened replacement session owns a fresh request");
        retention.close(); retention.close();
        check(manager.releases == 2, "Active service destruction releases its own replacement once");
    }

    private static void deniedProtectionDoesNotTerminateExistingSessionOrSpin() {
        ConnectivityManager manager = new ConnectivityManager();
        manager.requestFailure = new SecurityException("denied");
        CameraWifiRetention retention = new CameraWifiRetention(new Context(manager));
        retention.setRequired(new Network(1));
        for (int update = 0; update < 500; update++) retention.setRequired(new Network(1));
        check(manager.requests == 1 && manager.releases == 0 && !retention.isRequested(),
                "Denied optional protection neither throws nor creates a repeated request storm");
        retention.setRequired(null);
        manager.requestFailure = null;
        retention.setRequired(new Network(1));
        check(retention.isRequested() && manager.requests == 2,
                "A later explicit connection can retry protection after a denial");
        retention.close();
    }

    private static void platformReleaseFailureDoesNotLeakOwnership() {
        ConnectivityManager manager = new ConnectivityManager();
        CameraWifiRetention retention = new CameraWifiRetention(new Context(manager));
        retention.setRequired(new Network(1));
        manager.releaseFailure = new IllegalArgumentException("already released");
        retention.close(); retention.close();
        check(!retention.isRequested() && manager.releases == 1,
                "A platform-released request does not keep stale ownership or throw during destruction");
    }

    private static void unavailableConnectivityIsOptional() {
        CameraWifiRetention retention = new CameraWifiRetention(new Context(null));
        retention.setRequired(new Network(1)); retention.setRequired(null); retention.close();
        check(!retention.isRequested(), "Missing optional connectivity service never aborts a camera session");
    }

    private static void neverRetainsAnotherWifiNetwork() {
        ConnectivityManager manager = new ConnectivityManager();
        CameraWifiRetention retention = new CameraWifiRetention(new Context(manager));
        retention.setRequired(new Network(1));
        ConnectivityManager.NetworkCallback first = manager.lastCallback;
        first.onAvailable(new Network(1));
        check(retention.isRequested() && manager.releases == 0, "The exact PTP network is retained");
        first.onAvailable(new Network(2));
        check(!retention.isRequested() && manager.releases == 1 && manager.releasedCallback == first,
                "If the OS selects another Wi-Fi, immediately release instead of retaining a wrong network");
        for (int update = 0; update < 50; update++) retention.setRequired(new Network(1));
        check(manager.requests == 1, "A wrong-network match must not churn requests on every progress event");
        retention.close();
        check(manager.releases == 1, "Mismatched callbacks are already released before service destruction");
    }

    private static void staleCallbacksCannotReleaseReplacement() {
        ConnectivityManager manager = new ConnectivityManager();
        CameraWifiRetention retention = new CameraWifiRetention(new Context(manager));
        retention.setRequired(new Network(1));
        ConnectivityManager.NetworkCallback first = manager.lastCallback;
        retention.setRequired(new Network(2));
        ConnectivityManager.NetworkCallback second = manager.lastCallback;
        check(manager.requests == 2 && manager.releases == 1 && first != second,
                "A genuinely replaced PTP network gets a new request");
        first.onAvailable(new Network(3)); first.onLost(new Network(1));
        check(retention.isRequested() && manager.releases == 1,
                "Stale availability/loss callbacks cannot release the current target");
        second.onAvailable(new Network(2));
        second.onLost(new Network(2));
        check(!retention.isRequested() && manager.releases == 2,
                "Loss of the exact PTP network releases demand without starting another Wi-Fi");
        retention.close();
    }

    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
}

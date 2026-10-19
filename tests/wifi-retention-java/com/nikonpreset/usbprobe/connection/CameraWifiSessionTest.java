package com.nikonpreset.usbprobe.connection;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Handler;
import com.nikonpreset.usbprobe.album.CameraWifiNetwork;
import com.nikonpreset.usbprobe.album.ConnectionTrace;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import javax.net.SocketFactory;

public final class CameraWifiSessionTest {
    private static int checks;
    public static void main(String[] ignored) throws Exception {
        exactSpecificRequestBeforePtp();
        healthyReuseAndActivityCancellation();
        staleCallbacksAndBoundedTimeout();
        pendingCancellationAndNewRequest();
        unavailableAndPermissionFailure();
        lossIsFailClosedAndRequiresExplicitRetry();
        lossBeforeCompletionCannotStartPtp();
        cancelAvailableBeforeBusinessDelivery();
        activityDestructionBeforeBusinessDelivery();
        routeOwnershipAndConnectorCancellation();
        validationCannotDisturbHealthySession();
        traceDoesNotRecordNetworkSecrets();
        System.out.println("PASS: " + checks + " specific camera Wi-Fi session checks");
    }
    private static final class Completion implements CameraWifiSession.Listener {
        int successes, failures; String failure = "";
        @Override public void onConnected() { successes++; }
        @Override public void onFailure(String message) { failures++; failure = message; }
    }
    private static final class Fixture {
        final ConnectivityManager manager = new ConnectivityManager();
        final Context context = new Context(manager);
        int changes;
        final CameraWifiSession session = new CameraWifiSession(context, () -> changes++);
        Fixture() { Handler.reset(); manager.active = new Network(90); manager.networks = new Network[] { manager.active }; }
        void close() { session.disconnect(); Handler.drain(); }
    }
    private static void exactSpecificRequestBeforePtp() throws Exception {
        Fixture value = new Fixture(); Completion listener = new Completion();
        long token = value.session.connect("Camera QA", "testpass8", listener);
        check(token > 0 && value.session.isConnecting() && value.session.isActive() && !value.session.isConnected(), "Pending system authorization keeps camera service work active");
        check(value.manager.requests == 1 && value.manager.lastRequest.specifier instanceof WifiNetworkSpecifier, "A specific rather than generic Wi-Fi request is issued");
        WifiNetworkSpecifier spec = (WifiNetworkSpecifier) value.manager.lastRequest.specifier;
        check(spec.ssid.equals("Camera QA") && spec.password.equals("testpass8"), "Exact user-entered SSID and WPA2 credential are supplied to Android only");
        check(value.manager.lastRequest.transports.contains(NetworkCapabilities.TRANSPORT_WIFI)
                && value.manager.lastRequest.removedCapabilities.contains(NetworkCapabilities.NET_CAPABILITY_INTERNET), "Camera request requires Wi-Fi without Internet capability");
        expectRouteClosed(value.context, "No PTP connector can be created before user Wi-Fi authorization succeeds");
        Network camera = new Network(1); value.manager.lastCallback.onAvailable(camera);
        check(listener.successes == 0 && listener.failures == 0, "Success is delivered asynchronously to the main queue");
        check(value.session.isConnected() && !value.session.isConnecting() && value.session.network().equals(camera), "Actual callback Network becomes the service-owned route");
        check(listenerField(value.session) == null, "Settled manager no longer retains the Activity listener");
        Handler.drain(); check(listener.successes == 1, "Wi-Fi success resumes PTP once");
        value.manager.lastCallback.onAvailable(camera); Handler.drain();
        check(listener.successes == 1, "Repeated available callback cannot start PTP twice"); value.close();
    }
    private static void healthyReuseAndActivityCancellation() {
        Fixture value = new Fixture(); Completion first = new Completion();
        long token = value.session.connect("Camera QA", "", first);
        check(((WifiNetworkSpecifier) value.manager.lastRequest.specifier).password.isEmpty(), "Empty password creates an open-network request");
        var callback = value.manager.lastCallback; callback.onAvailable(new Network(1)); Handler.drain();
        Completion second = new Completion(); long reused = value.session.connect("Camera QA", "", second); Handler.drain();
        check(reused == token && value.manager.requests == 1 && second.successes == 1, "Healthy same-SSID connection reuses its existing request");
        value.session.cancel(token);
        check(value.session.isConnected() && value.manager.releases == 0, "Activity destruction cancellation cannot disconnect a healthy camera");
        callback.onUnavailable(); Handler.drain();
        check(value.session.isConnected(), "A late unavailable callback cannot terminate an already established connection");
        for (int tick = 0; tick < 500; tick++) check(value.session.isActive() && value.session.network().equals(new Network(1)), "Idle/background/progress observation never creates a new request");
        check(value.manager.requests == 1 && value.manager.lastCallback == callback, "The same specific request survives all observations");
        try { value.session.connect("Other Camera", "", new Completion()); throw new AssertionError("Expected explicit disconnect requirement"); }
        catch (IllegalStateException expected) { check(value.session.isConnected(), "Another camera cannot replace a healthy session implicitly"); }
        value.close();
    }
    private static void staleCallbacksAndBoundedTimeout() {
        Fixture value = new Fixture(); Completion old = new Completion(), current = new Completion();
        long first = value.session.connect("Camera A", "", old); var oldCallback = value.manager.lastCallback;
        Handler.advance(30_000);
        long second = value.session.connect("Camera B", "", current); var currentCallback = value.manager.lastCallback;
        check(first != second && value.manager.releases == 1, "Replacing a pending request releases the old request");
        oldCallback.onAvailable(new Network(1)); oldCallback.onUnavailable(); oldCallback.onLost(new Network(1)); value.session.cancel(first); Handler.drain();
        check(value.session.isConnecting() && value.manager.lastCallback == currentCallback && current.failures == 0 && old.successes == 0, "Old callbacks and cancellation cannot consume the newer connection");
        Handler.advance(31_000);
        check(value.session.isConnecting() && current.failures == 0, "A removed old timeout cannot time out its replacement");
        Handler.advance(30_000);
        check(!value.session.isActive() && current.failures == 1 && current.successes == 0 && old.failures == 0, "A pending request has one bounded timeout with no old-listener delivery");
        check(value.session.usesSpecificRoute() && value.manager.releases == 2, "Timeout keeps the fail-closed route guard after releasing the platform demand");
        value.close();
    }
    private static void pendingCancellationAndNewRequest() {
        Fixture value = new Fixture(); Completion first = new Completion();
        long token = value.session.connect("Camera QA", "", first); var stale = value.manager.lastCallback;
        value.session.cancel(token); Handler.drain(); stale.onAvailable(new Network(1)); Handler.advance(61_000);
        check(!value.session.isActive() && !value.session.usesSpecificRoute() && first.successes == 0 && first.failures == 0, "Explicit pending cancellation clears listener, timer and owned route");
        Completion next = new Completion(); long newer = value.session.connect("Camera QA", "", next);
        value.session.disconnect(token); check(value.session.isConnecting(), "Old asynchronous runtime cleanup cannot close a new token");
        value.manager.lastCallback.onAvailable(new Network(2)); Handler.drain();
        check(next.successes == 1 && value.session.isConnected(), "A new explicit connection can succeed after cancellation");
        value.session.disconnect(newer); check(!value.session.isActive(), "Matching runtime cleanup releases the current request"); value.close();
    }
    private static void unavailableAndPermissionFailure() throws Exception {
        Fixture value = new Fixture(); Completion listener = new Completion();
        value.session.connect("Camera QA", "", listener); var request = value.manager.lastCallback;
        request.onUnavailable(); request.onUnavailable(); Handler.drain();
        check(listener.failures == 1 && value.manager.releases == 1 && !value.session.isActive(), "Denied/system-unavailable request settles exactly once");
        expectRouteClosed(value.context, "A failed specific request never silently falls back to a different saved Wi-Fi");
        check(listenerField(value.session) == null, "Failed connection clears its pending Activity listener"); value.close();
        Fixture denied = new Fixture(); denied.manager.requestFailure = new SecurityException("synthetic permission denial"); Completion rejection = new Completion();
        denied.session.connect("Camera QA", "testpass8", rejection); Handler.drain();
        check(rejection.failures == 1 && !denied.session.isActive(), "A missing runtime permission fails through the safe main callback");
        check(!rejection.failure.contains("Camera QA") && !rejection.failure.contains("testpass8"), "Failure text does not leak network identity or credentials"); denied.close();
    }
    private static void lossIsFailClosedAndRequiresExplicitRetry() throws Exception {
        Fixture value = new Fixture(); Completion first = new Completion();
        value.session.connect("Camera QA", "", first); var old = value.manager.lastCallback; old.onAvailable(new Network(1)); Handler.drain();
        old.onLost(new Network(7)); check(value.session.isConnected(), "A loss callback for an unrelated Network cannot close the camera");
        old.onLost(new Network(1)); Handler.drain();
        check(!value.session.isActive() && value.session.network() == null && value.session.usesSpecificRoute(), "Physical loss releases demand but preserves fail-closed routing");
        check(first.successes == 1 && first.failures == 0, "A settled listener is not retained for later camera-loss events");
        expectRouteClosed(value.context, "Lost camera network never falls back to another router");
        old.onAvailable(new Network(2)); check(!value.session.isConnected(), "Old available callback cannot reconnect after loss");
        Completion retry = new Completion(); value.session.connect("Camera QA", "", retry); var next = value.manager.lastCallback;
        old.onUnavailable(); next.onAvailable(new Network(3)); Handler.drain();
        check(value.session.isConnected() && retry.successes == 1 && retry.failures == 0, "Only a new explicit request can establish a new camera network"); value.close();
    }
    private static void lossBeforeCompletionCannotStartPtp() {
        Fixture value = new Fixture(); Completion listener = new Completion();
        value.session.connect("Camera QA", "", listener); var callback = value.manager.lastCallback;
        callback.onAvailable(new Network(1)); callback.onLost(new Network(1)); Handler.drain();
        check(listener.successes == 0 && listener.failures == 1, "Loss before the queued main completion cannot start PTP on a dead route"); value.close();
    }
    private static void cancelAvailableBeforeBusinessDelivery() {
        Fixture value = new Fixture(); Completion listener = new Completion();
        long token = value.session.connect("Camera QA", "", listener);
        value.manager.lastCallback.onAvailable(new Network(1));
        value.session.cancel(token); Handler.drain();
        check(!value.session.isActive() && !value.session.usesSpecificRoute() && value.manager.releases == 1,
                "Cancel between Android availability and business delivery cannot orphan a held Wi-Fi request");
        check(listener.successes == 0 && listener.failures == 0, "Queued success is invalidated after pending business delivery is cancelled"); value.close();
    }
    private static void activityDestructionBeforeBusinessDelivery() {
        Fixture value = new Fixture(); Completion listener = new Completion();
        long token = value.session.connect("Camera QA", "", listener);
        var stale = value.manager.lastCallback; stale.onAvailable(new Network(1));
        value.session.cancel(token); // The Activity destruction hook cancels the UI ticket with the same token.
        stale.onAvailable(new Network(1)); stale.onLost(new Network(1)); Handler.drain();
        check(!value.session.isConnected() && value.manager.releases == 1 && listener.successes == 0,
                "Activity destruction after availability releases only the undelivered request and ignores stale callbacks");
        Completion current = new Completion(); long newer = value.session.connect("Camera QA", "", current);
        value.manager.lastCallback.onAvailable(new Network(2)); Handler.drain();
        value.session.cancel(token); value.session.cancel(newer);
        check(value.session.isConnected() && current.successes == 1 && value.manager.releases == 1,
                "After actual business delivery, Activity cancellation preserves the healthy service-owned connection"); value.close();
    }
    private static void routeOwnershipAndConnectorCancellation() throws Exception {
        Fixture value = new Fixture(); Completion listener = new Completion();
        long token = value.session.connect("Camera QA", "", listener);
        FakeFactory factory = new FakeFactory(); Network camera = new Network(5, factory);
        value.manager.lastCallback.onAvailable(camera); Handler.drain();
        check(!CameraWifiNetwork.useCameraNetwork(new Object(), token, new Network(6)), "Another owner cannot rebind this route");
        check(!CameraWifiNetwork.releaseCameraNetwork(value.session, token - 1), "A stale token cannot release this route");
        CameraWifiNetwork wifi = new CameraWifiNetwork(value.context);
        var connector = wifi.connector("192.168.1.1", 15740); Socket socket = connector.connect("192.168.1.1", 15740, 5000);
        check(factory.creations == 1 && camera.equals(CameraWifiNetwork.socketNetwork()), "PTP uses the specific Network even when another Wi-Fi is the system default"); socket.close();
        value.manager.lastCallback.onLost(camera);
        try { connector.connect("192.168.1.1", 15740, 5000); throw new AssertionError("Expected stale-route rejection"); }
        catch (IOException expected) { check(factory.creations == 1, "A connector captured before loss cannot establish a later stale connection"); }
        value.close();
        check(!CameraWifiNetwork.useCameraNetwork(value.session, token, camera), "A released owner token cannot revive a route");
    }
    private static void validationCannotDisturbHealthySession() {
        Fixture value = new Fixture(); Completion listener = new Completion(); value.session.connect("Camera QA", "", listener);
        value.manager.lastCallback.onAvailable(new Network(1)); Handler.drain(); int requests = value.manager.requests;
        for (String ssid : new String[] { "", "a".repeat(33), "\uD800" }) {
            try { value.session.connect(ssid, "", new Completion()); throw new AssertionError("Expected SSID validation"); }
            catch (IllegalArgumentException expected) { check(value.session.isConnected(), "Invalid SSID does not disturb an existing session"); }
        }
        for (String password : new String[] { "short", "a".repeat(64), "密码密码密码密码", "abcdefgh\n" }) {
            try { value.session.connect("Camera QA", password, new Completion()); throw new AssertionError("Expected password validation"); }
            catch (IllegalArgumentException expected) { check(value.session.isConnected(), "Invalid credentials do not disturb an existing session"); }
        }
        check(value.manager.requests == requests && value.manager.releases == 0, "Input validation precedes all network side effects"); value.close();
    }
    private static void traceDoesNotRecordNetworkSecrets() {
        ConnectionTrace.configure(new java.io.File("tests/.build/wifi-retention/session-trace.txt"));
        Fixture value = new Fixture(); Completion listener = new Completion();
        value.session.connect("PRIVATE_SSID_LITERAL", "PRIV8pass", listener);
        value.manager.lastCallback.onAvailable(new Network(1)); Handler.drain();
        value.manager.lastCallback.onBlockedStatusChanged(new Network(1), true);
        value.manager.lastCallback.onBlockedStatusChanged(new Network(1), false);
        value.manager.lastCallback.onLost(new Network(1)); value.close();
        String trace = ConnectionTrace.snapshot();
        check(trace.contains("event=WIFI_SPECIFIC_REQUESTED") && trace.contains("event=WIFI_SPECIFIC_AVAILABLE")
                && trace.contains("event=WIFI_SPECIFIC_LOST") && trace.contains("event=WIFI_SPECIFIC_RELEASED"),
                "Persistent evidence records specific request availability, loss and release ordering");
        check(!trace.contains("PRIVATE_SSID_LITERAL") && !trace.contains("PRIV8pass"), "Specific Wi-Fi trace never includes SSID or passphrase");
        check(trace.contains("event=WIFI_SPECIFIC_BLOCKED") && trace.contains("event=WIFI_SPECIFIC_UNBLOCKED"), "Trace distinguishes Android UID network blocking from physical network loss");
        ConnectionTrace.configure(null);
    }
    private static Object listenerField(CameraWifiSession session) throws Exception { var field = CameraWifiSession.class.getDeclaredField("listener"); field.setAccessible(true); return field.get(session); }
    private static void expectRouteClosed(Context context, String message) throws Exception {
        try { new CameraWifiNetwork(context).connector("192.168.1.1", 15740); throw new AssertionError(message); }
        catch (IOException expected) { check(true, message); }
    }
    private static final class FakeFactory extends SocketFactory {
        int creations;
        @Override public Socket createSocket() { creations++; return new Socket() { private boolean closed; @Override public void connect(SocketAddress address, int timeout) { } @Override public boolean isClosed() { return closed; } @Override public synchronized void close() { closed = true; } }; }
        @Override public Socket createSocket(String host, int port) { return createSocket(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) { return createSocket(); }
        @Override public Socket createSocket(InetAddress host, int port) { return createSocket(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) { return createSocket(); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}

package cc.huevia.connection;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Handler;
import cc.huevia.album.CameraWifiNetwork;
import cc.huevia.album.ConnectionTrace;
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
        currentWifiAdoptionAndPinnedChannels();
        adoptedWifiAllowsServiceRetentionDemand();
        mobileDefaultWithOneWifi();
        vpnDefaultDoesNotHidePhysicalWifi();
        absentAndAmbiguousWifiHaveNoSideEffects();
        physicalChannelsControlAdoption();
        adoptedLossFailsClosedAndReleasesOnlyItsObserver();
        adoptedCancellationAndStaleCallbacks();
        adoptedObservationFailureAndTimeout();
        adoptedLossBeforeDeliveryCannotStartPtp();
        System.out.println("PASS: " + checks + " camera Wi-Fi session checks");
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

    private static void currentWifiAdoptionAndPinnedChannels() throws Exception {
        Fixture value = new Fixture(); Completion listener = new Completion();
        FakeFactory cameraFactory = new FakeFactory(), otherFactory = new FakeFactory();
        Network camera = new Network(101, cameraFactory), other = new Network(102, otherFactory);
        value.manager.active = camera; value.manager.networks = new Network[] { camera, other };
        long token = value.session.useConnectedWifi(listener); var observer = value.manager.lastObserver;
        check(token > 0 && value.session.isConnecting() && value.session.isActive(), "Current Wi-Fi adoption keeps service work active while availability is confirmed");
        check(value.manager.requests == 0 && value.manager.observations == 1 && value.manager.lastObservation.specifier == null,
                "Adoption observes existing Wi-Fi without requesting or joining a network");
        check(value.manager.lastObservation.transports.contains(NetworkCapabilities.TRANSPORT_WIFI)
                && value.manager.lastObservation.removedCapabilities.contains(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                "A camera Wi-Fi without Internet can satisfy the observation");
        expectRouteClosed(value.context, "PTP is blocked until the selected existing network is observed");
        observer.onAvailable(other); observer.onLost(other); Handler.drain();
        check(value.session.isConnecting() && listener.successes == 0 && listener.failures == 0,
                "An unrelated available/lost Wi-Fi cannot settle the selected current Wi-Fi");
        observer.onAvailable(camera);
        check(listener.successes == 0 && value.session.network().equals(camera), "Exact existing Wi-Fi success is queued on the main thread");
        Handler.drain();
        check(listener.successes == 1 && listenerField(value.session) == null, "Adoption delivers once and clears its Activity listener");
        value.manager.active = other;
        var connector = new CameraWifiNetwork(value.context).connector("192.168.1.1", 15740);
        Socket command = connector.connect("192.168.1.1", 15740, 5000);
        Socket event = connector.connect("192.168.1.1", 15740, 5000);
        check(cameraFactory.creations == 2 && otherFactory.creations == 0 && camera.equals(CameraWifiNetwork.socketNetwork()),
                "Both PTP channels stay pinned to adopted Wi-Fi after the default changes");
        Completion reused = new Completion(); long same = value.session.useConnectedWifi(reused); Handler.drain();
        check(same == token && reused.successes == 1 && value.manager.observations == 1, "Healthy adoption reuses its exact observer and route");
        value.session.cancel(token);
        check(value.session.isConnected() && value.manager.releases == 0, "Activity cancellation leaves a delivered adopted connection service-owned");
        command.close(); event.close(); value.close();
        check(value.manager.releases == 1 && value.manager.releasedCallback == observer && !value.session.isRouteUnavailable(),
                "Explicit release unregisters the adopted observer and releases its route guard");
    }

    private static void adoptedWifiAllowsServiceRetentionDemand() throws Exception {
        Fixture value = new Fixture(); Completion listener = new Completion();
        FakeFactory factory = new FakeFactory(); Network camera = new Network(151, factory);
        value.manager.active = camera; value.manager.networks = new Network[] { camera };
        value.session.useConnectedWifi(listener); var observer = value.manager.lastObserver;
        observer.onAvailable(camera); Handler.drain();
        check(!value.session.usesSpecificRoute(), "An adopted observer does not claim to provide a specifier network demand");
        CameraWifiRetention retention = new CameraWifiRetention(value.context);
        // The service uses this gate both when it starts and when runtime work changes.
        retention.setRequired(!value.session.usesSpecificRoute() ? CameraWifiNetwork.socketNetwork() : null);
        check(value.manager.requests == 0, "Adoption alone leaves generic retention waiting for an authoritative physical PTP channel");
        var connector = new CameraWifiNetwork(value.context).connector("192.168.1.1", 15740);
        Socket command = connector.connect("192.168.1.1", 15740, 5000);
        Socket event = connector.connect("192.168.1.1", 15740, 5000);
        retention.setRequired(!value.session.usesSpecificRoute() ? CameraWifiNetwork.socketNetwork() : null);
        var demand = value.manager.lastCallback; demand.onAvailable(camera);
        check(retention.isRequested() && value.manager.requests == 1 && value.manager.observations == 1
                && demand != observer, "Physical PTP channels activate the existing service demand alongside the adoption observer");
        command.close(); event.close(); value.session.disconnect();
        check(retention.isRequested() && value.manager.releasedCallback == observer && value.manager.releases == 1,
                "Session release unregisters its observer without consuming service retention ownership");
        retention.close();
        check(value.manager.releases == 2 && value.manager.releasedCallback == demand,
                "Service demand releases its own callback independently of the adopted observer"); value.close();
    }

    private static void mobileDefaultWithOneWifi() {
        Fixture value = new Fixture(); Completion listener = new Completion();
        Network mobile = new Network(201), camera = new Network(202);
        value.manager.active = mobile; value.manager.networks = new Network[] { mobile, camera };
        value.manager.capabilities.put(mobile, new NetworkCapabilities(false));
        long token = value.session.useConnectedWifi(listener);
        value.manager.lastObserver.onAvailable(camera); Handler.drain();
        check(token > 0 && listener.successes == 1 && camera.equals(value.session.network()),
                "A single connected camera Wi-Fi is adopted when Android prefers mobile data");
        check(value.manager.requests == 0, "Mobile-default adoption never requests a replacement Wi-Fi"); value.close();
    }

    private static void vpnDefaultDoesNotHidePhysicalWifi() {
        Fixture value = new Fixture(); Completion listener = new Completion();
        Network vpn = new Network(251), camera = new Network(252);
        value.manager.active = vpn; value.manager.networks = new Network[] { vpn, camera };
        value.manager.capabilities.put(vpn, new NetworkCapabilities(true, true));
        long token = value.session.useConnectedWifi(listener); var observer = value.manager.lastObserver;
        observer.onAvailable(vpn); Handler.drain();
        check(token > 0 && value.session.isConnecting() && listener.successes == 0, "VPN reporting an underlying Wi-Fi cannot become the physical camera route");
        observer.onAvailable(camera); Handler.drain();
        check(listener.successes == 1 && camera.equals(value.session.network()), "Sole physical Wi-Fi can be adopted when VPN is the default"); value.close();
    }

    private static void absentAndAmbiguousWifiHaveNoSideEffects() {
        Fixture value = new Fixture(); Completion listener = new Completion();
        Network mobile = new Network(301), first = new Network(302), second = new Network(303);
        value.manager.active = mobile; value.manager.capabilities.put(mobile, new NetworkCapabilities(false));
        value.manager.networks = new Network[] { mobile };
        check(value.session.useConnectedWifi(listener) == 0, "No connected Wi-Fi returns the manual fallback sentinel");
        value.manager.networks = new Network[] { mobile, first, second };
        check(value.session.useConnectedWifi(listener) == 0, "Multiple Wi-Fi routes without a preferred Wi-Fi are ambiguous");
        Handler.drain();
        check(!value.session.isActive() && !value.session.isRouteUnavailable() && value.changes == 0
                && value.manager.requests == 0 && value.manager.observations == 0 && value.manager.releases == 0
                && listener.successes == 0 && listener.failures == 0, "No candidate leaves listener, runtime and routing untouched");
        long manual = value.session.connect("Manual Camera", "", listener); var request = value.manager.lastCallback;
        check(value.session.useConnectedWifi(new Completion()) == 0 && value.session.token() == manual
                && value.session.isConnecting() && value.manager.lastCallback == request && value.manager.releases == 0,
                "An ambiguous adoption does not cancel an existing explicit request"); value.close();
    }

    private static void physicalChannelsControlAdoption() throws Exception {
        Fixture value = new Fixture(); Completion listener = new Completion();
        FakeFactory firstFactory = new FakeFactory(), secondFactory = new FakeFactory();
        Network first = new Network(401, firstFactory), second = new Network(402, secondFactory);
        value.manager.active = first; value.manager.networks = new Network[] { first };
        CameraWifiNetwork wifi = new CameraWifiNetwork(value.context);
        Socket physical = wifi.connector("192.168.1.1", 15740).connect("192.168.1.1", 15740, 5000);
        value.manager.active = second; value.manager.networks = new Network[] { first, second };
        check(first.equals(wifi.connectedWifiNetwork()), "Live physical PTP Wi-Fi takes precedence over a new default Wi-Fi");
        Socket another = wifi.connector("192.168.1.1", 15740).connect("192.168.1.1", 15740, 5000);
        check(wifi.connectedWifiNetwork() == null && value.session.useConnectedWifi(listener) == 0,
                "Conflicting live PTP networks cannot silently adopt the current default");
        another.close();
        value.manager.capabilities.put(first, null);
        check(value.session.useConnectedWifi(listener) == 0, "A vanished physical PTP Wi-Fi cannot fall back to another router");
        value.manager.capabilities.remove(first);
        long token = value.session.useConnectedWifi(listener); value.manager.lastObserver.onAvailable(second); Handler.drain();
        check(token > 0 && value.session.isConnecting() && listener.successes == 0, "Physical-route adoption ignores another default network callback");
        value.manager.lastObserver.onAvailable(first); Handler.drain();
        check(first.equals(value.session.network()) && listener.successes == 1, "Physical camera Wi-Fi is adopted after its exact availability arrives");
        physical.close(); value.close();
    }

    private static void adoptedLossFailsClosedAndReleasesOnlyItsObserver() throws Exception {
        Fixture value = new Fixture(); Completion listener = new Completion();
        Network camera = new Network(501), other = new Network(502);
        value.manager.active = camera; value.manager.networks = new Network[] { camera, other };
        var unrelated = new ConnectivityManager.NetworkCallback();
        value.manager.registerNetworkCallback(new android.net.NetworkRequest.Builder().build(), unrelated, new Handler(android.os.Looper.getMainLooper()));
        value.session.useConnectedWifi(listener); var observer = value.manager.lastObserver;
        observer.onAvailable(camera); Handler.drain();
        var oldConnector = new CameraWifiNetwork(value.context).connector("192.168.1.1", 15740);
        value.manager.active = other;
        observer.onLost(other); observer.onAvailable(other);
        check(value.session.isConnected() && camera.equals(value.session.network()), "Unrelated callbacks cannot retarget or release an adopted route");
        int beforeLoss = value.changes; observer.onLost(camera); Handler.drain();
        check(!value.session.isActive() && value.session.isRouteUnavailable() && value.changes == beforeLoss + 1,
                "Selected-network loss notifies runtime once and preserves its fail-closed route guard");
        check(value.manager.releases == 1 && value.manager.releasedCallback == observer
                && !value.manager.releasedCallbacks.contains(unrelated), "Selected loss unregisters only this session's observer");
        check(listener.successes == 1 && listener.failures == 0 && listenerField(value.session) == null,
                "A delivered Activity listener is not retained for later adopted-network loss");
        expectRouteClosed(value.context, "Selected loss cannot fall back to the newly preferred router");
        try { oldConnector.connect("192.168.1.1", 15740, 5000); throw new AssertionError("Stale adopted connector survived loss"); }
        catch (IOException expected) { check(true, "Connectors captured before loss are invalidated"); }
        observer.onAvailable(camera); observer.onLost(camera); Handler.drain();
        check(!value.session.isConnected() && value.manager.releases == 1 && value.changes == beforeLoss + 1,
                "Retired observer callbacks cannot reconnect or repeat route-loss delivery"); value.close();
    }

    private static void adoptedCancellationAndStaleCallbacks() {
        Fixture value = new Fixture(); Completion old = new Completion();
        Network camera = value.manager.active;
        long token = value.session.useConnectedWifi(old); var observer = value.manager.lastObserver;
        Completion manual = new Completion(); long newer = value.session.connect("Manual Camera", "", manual); var request = value.manager.lastCallback;
        check(newer != token && value.manager.releasedCallback == observer && value.manager.lastRequest.specifier instanceof WifiNetworkSpecifier,
                "Manual fallback can explicitly replace a pending adoption and releases its observer");
        observer.onAvailable(camera); observer.onLost(camera); value.session.cancel(token); Handler.drain();
        check(value.session.isConnecting() && old.successes == 0 && old.failures == 0 && manual.failures == 0,
                "A stale adopted observer/token cannot settle or cancel its manual replacement");
        request.onAvailable(camera); Handler.drain();
        Completion reused = new Completion(); check(value.session.useConnectedWifi(reused) == newer, "Adoption can reuse an existing explicit request"); Handler.drain();
        check(manual.successes == 1 && reused.successes == 1 && value.manager.observations == 1,
                "Healthy explicit route reuse creates no observer and keeps its original demand"); value.close();

        Fixture pending = new Fixture(); Completion cancelled = new Completion();
        long pendingToken = pending.session.useConnectedWifi(cancelled); var stale = pending.manager.lastObserver;
        stale.onAvailable(pending.manager.active); pending.session.cancel(pendingToken); Handler.drain();
        stale.onAvailable(pending.manager.active); stale.onLost(pending.manager.active); Handler.advance(61_000);
        check(!pending.session.isActive() && !pending.session.isRouteUnavailable() && pending.manager.releases == 1
                && cancelled.successes == 0 && cancelled.failures == 0, "Cancellation before adoption business delivery releases once and invalidates queued callbacks");
        pending.close();
    }

    private static void adoptedObservationFailureAndTimeout() throws Exception {
        Fixture denied = new Fixture(); Completion rejected = new Completion();
        denied.manager.observationFailure = new SecurityException("synthetic observer denial");
        long token = denied.session.useConnectedWifi(rejected); Handler.drain();
        check(token > 0 && rejected.failures == 1 && denied.session.isRouteUnavailable() && denied.manager.requests == 0,
                "Observation failure settles safely without requesting or switching Wi-Fi");
        expectRouteClosed(denied.context, "Observation failure keeps camera routing closed"); denied.close();
        Fixture waiting = new Fixture(); Completion listener = new Completion();
        waiting.session.useConnectedWifi(listener); var observer = waiting.manager.lastObserver;
        observer.onAvailable(new Network(601)); Handler.advance(61_000);
        check(listener.failures == 1 && listener.successes == 0 && waiting.session.isRouteUnavailable()
                && waiting.manager.releases == 1, "A selected network that disappears before observation has one bounded timeout");
        observer.onAvailable(waiting.manager.active); Handler.drain();
        check(!waiting.session.isConnected() && listener.failures == 1, "Timed-out adoption never revives from late availability"); waiting.close();
    }

    private static void adoptedLossBeforeDeliveryCannotStartPtp() {
        Fixture value = new Fixture(); Completion pending = new Completion();
        value.session.useConnectedWifi(pending); var observer = value.manager.lastObserver;
        observer.onLost(value.manager.active); Handler.drain();
        check(pending.failures == 1 && pending.successes == 0 && value.session.isRouteUnavailable(),
                "Loss of the selected pending Wi-Fi fails immediately before any availability"); value.close();
        Fixture available = new Fixture(); Completion queued = new Completion();
        available.session.useConnectedWifi(queued); var selected = available.manager.lastObserver;
        selected.onAvailable(available.manager.active); selected.onLost(available.manager.active); Handler.drain();
        check(queued.failures == 1 && queued.successes == 0 && available.session.isRouteUnavailable(),
                "Selected loss before queued adoption delivery cannot start PTP"); available.close();
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

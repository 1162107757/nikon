package cc.huevia.album;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import javax.net.SocketFactory;

/** Uses actual selected-route metadata; never scans addresses or assumes a .1 gateway. */
public final class CameraWifiGatewayTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        selectedGatewayIgnoresOtherNetworksAndRejectsStaleResults();
        selectedGatewayRequiresAnUnambiguousIpv4DefaultRoute();
        selfTargetsNeverCreateASocket();
        System.out.println("PASS: " + checks + " selected camera gateway/self-target checks");
    }

    private static void selectedGatewayIgnoresOtherNetworksAndRejectsStaleResults() throws Exception {
        Fixture fixture = new Fixture();
        check(fixture.wifi.selectedCameraGateway().isEmpty(), "No held request means no authoritative camera gateway");
        check(fixture.wifi.suggestHost().equals("10.8.0.254"), "Unowned legacy suggestions remain compatible");
        fixture.require();
        try {
            check(fixture.wifi.selectedCameraGateway().isEmpty(), "Pending camera request does not inherit another Wi-Fi's gateway");
            check(fixture.wifi.suggestHost().isEmpty(), "Owned pending route disables unrelated-network suggestions");
            fixture.select(); fixture.manager.propertyLookups.clear();
            check(fixture.wifi.selectedCameraGateway().equals("192.168.186.254"), "Selected camera's real default gateway is used without assuming .1");
            check(fixture.wifi.suggestHost().equals("192.168.186.254"), "Existing suggestion API becomes route-bound while the camera request is held");
            check(fixture.manager.propertyLookups.stream().allMatch(fixture.camera::equals), "Gateway reads consult only requestedNetwork LinkProperties");
            check(fixture.wifi.isSelectedCameraLocalAddress("192.168.186.2"), "The selected camera network identifies the phone's current address");
            check(!fixture.wifi.isSelectedCameraLocalAddress("10.8.0.2"), "Other-network phone addresses cannot affect the selected camera route");
            check(fixture.cameraFactory.created == 0 && fixture.otherFactory.created == 0, "Gateway lookup performs no connection attempts");

            fixture.manager.onNextPropertyLookup = () -> CameraWifiNetwork.cameraNetworkLost(fixture.owner, fixture.token);
            check(fixture.wifi.selectedCameraGateway().isEmpty(), "Network loss during LinkProperties read invalidates the stale gateway");
            check(fixture.wifi.suggestHost().isEmpty(), "A lost requested network never falls back to the active Wi-Fi");
            fixture.select();
            fixture.manager.onNextPropertyLookup = () -> CameraWifiNetwork.useCameraNetwork(fixture.owner, fixture.token, fixture.other);
            check(fixture.wifi.selectedCameraGateway().isEmpty(), "Network replacement during lookup cannot publish the old gateway");
            fixture.select();
            fixture.manager.onNextPropertyLookup = () -> CameraWifiNetwork.cameraNetworkLost(fixture.owner, fixture.token);
            check(!fixture.wifi.isSelectedCameraLocalAddress("192.168.186.2"), "Self-address results are also invalidated when the route is lost during lookup");
        } finally { fixture.release(); }
    }

    private static void selectedGatewayRequiresAnUnambiguousIpv4DefaultRoute() throws Exception {
        Fixture fixture = new Fixture(); fixture.require(); fixture.select();
        try {
            fixture.manager.properties.put(fixture.camera, null);
            check(fixture.wifi.selectedCameraGateway().isEmpty(), "Missing LinkProperties provides no target address");
            check(fixture.wifi.suggestHost().isEmpty(), "Missing camera routes do not trigger other-network fallback");
            fixture.manager.properties.put(fixture.camera, properties("192.168.186.254", "192.168.186.2"));
            fixture.manager.capabilities.put(fixture.camera, new NetworkCapabilities(false));
            check(fixture.wifi.selectedCameraGateway().isEmpty(), "A non-Wi-Fi requested Network cannot supply a camera gateway");
            fixture.manager.capabilities.remove(fixture.camera);

            LinkProperties routes = new LinkProperties();
            routes.routes.add(new RouteInfo(InetAddress.getByName("fe80::1"), true));
            routes.routes.add(new RouteInfo(InetAddress.getByName("192.168.186.100"), false));
            fixture.manager.properties.put(fixture.camera, routes);
            check(fixture.wifi.selectedCameraGateway().isEmpty(), "IPv6 default and nondefault IPv4 routes cannot become the IPv4 camera target");
            routes.routes.add(new RouteInfo(InetAddress.getByName("0.0.0.0"), true));
            routes.routes.add(new RouteInfo(InetAddress.getByName("127.0.0.1"), true));
            check(fixture.wifi.selectedCameraGateway().isEmpty(), "Unspecified and loopback routes are not camera targets");
            routes.routes.add(new RouteInfo(InetAddress.getByName("192.168.186.254"), true));
            check(fixture.wifi.selectedCameraGateway().equals("192.168.186.254"), "Valid IPv4 default route is selected from mixed route metadata");
            routes.routes.add(new RouteInfo(InetAddress.getByName("192.168.186.254"), true));
            check(fixture.wifi.selectedCameraGateway().equals("192.168.186.254"), "Duplicate routes to the same gateway are not ambiguous");
            routes.routes.add(new RouteInfo(InetAddress.getByName("192.168.186.253"), true));
            check(fixture.wifi.selectedCameraGateway().isEmpty(), "Different IPv4 default gateways require an explicit address rather than an arbitrary choice");
            check(fixture.cameraFactory.created == 0, "Missing or ambiguous gateway discovery never probes the network");
        } finally { fixture.release(); }
    }

    private static void selfTargetsNeverCreateASocket() throws Exception {
        Fixture fixture = new Fixture(); fixture.require(); fixture.select();
        try {
            PtpIpClient.SocketConnector fuji = fixture.wifi.fujifilmPhotoConnector("192.168.186.2", 55740);
            expectSelfError(() -> fuji.connect("192.168.186.2", 55740, 5000), "192.168.186.254");
            check(fixture.cameraFactory.created == 0, "Fuji self-target fails before creating a command socket");
            PtpIpClient.SocketConnector nikon = fixture.wifi.connector("192.168.186.2", 15740);
            expectSelfError(() -> nikon.connect("192.168.186.2", 15740, 5000), "192.168.186.254");
            check(fixture.cameraFactory.created == 0, "Nikon also rejects a phone-local target before creating a socket");

            try (Socket manual = fixture.wifi.fujifilmPhotoConnector("192.168.186.99", 55740).connect("192.168.186.99", 55740, 5000)) {
                check(fixture.cameraFactory.last.endpoint.getAddress().getHostAddress().equals("192.168.186.99"), "A valid explicit manual address remains the actual socket destination");
            }
            try (Socket unrelatedLocal = fixture.wifi.connector("10.8.0.2", 15740).connect("10.8.0.2", 15740, 5000)) {
                check(fixture.cameraFactory.last.endpoint.getAddress().getHostAddress().equals("10.8.0.2"), "An address local only to a different network is not falsely rejected");
            }
            check(fixture.otherFactory.created == 0, "Manual targets still use only the requested camera network");

            LinkProperties noGateway = new LinkProperties();
            noGateway.linkAddresses.add(new LinkAddress(InetAddress.getByName("192.168.186.2"), 24));
            fixture.manager.properties.put(fixture.camera, noGateway);
            int beforeMissingGateway = fixture.cameraFactory.created;
            expectSelfError(() -> fuji.connect("192.168.186.2", 55740, 5000), null);
            check(fixture.cameraFactory.created == beforeMissingGateway, "A known phone address remains invalid even when gateway metadata is missing");

            fixture.manager.properties.put(fixture.camera, properties("192.168.90.7", "192.168.90.3"));
            check(fixture.wifi.selectedCameraGateway().equals("192.168.90.7"), "Each new camera subnet uses the current actual gateway, not a cached default");
            check(fixture.wifi.isSelectedCameraLocalAddress("192.168.90.3")
                    && !fixture.wifi.isSelectedCameraLocalAddress("192.168.186.2"), "Phone-address detection tracks current LinkProperties after subnet changes");

            PtpIpClient.SocketConnector stale = fixture.wifi.fujifilmPhotoConnector("192.168.90.7", 55740);
            fixture.manager.onNextPropertyLookup = () -> CameraWifiNetwork.cameraNetworkLost(fixture.owner, fixture.token);
            int beforeLost = fixture.cameraFactory.created;
            try { stale.connect("192.168.90.7", 55740, 5000); throw new AssertionError("Lost route created a socket"); }
            catch (IOException expected) { check(fixture.cameraFactory.created == beforeLost, "Route loss during self-address lookup cancels before socket creation"); }
        } finally { fixture.release(); }
    }

    private static void expectSelfError(Attempt attempt, String gateway) throws Exception {
        try { attempt.run(); throw new AssertionError("Phone-local target accepted"); }
        catch (IOException error) {
            check(error.getMessage().contains("这是手机在所选 Wi-Fi 下的 IP，不是相机地址"), "Self-target error explains the address mistake");
            if (gateway != null) check(error.getMessage().contains(gateway), "Self-target error includes the actual selected-network gateway");
        }
    }
    private interface Attempt { void run() throws Exception; }
    private static LinkProperties properties(String gateway, String local) throws Exception {
        LinkProperties properties = new LinkProperties();
        properties.routes.add(new RouteInfo(InetAddress.getByName(gateway), true));
        properties.linkAddresses.add(new LinkAddress(InetAddress.getByName(local), 24));
        return properties;
    }
    private static final class Fixture {
        final Object owner = new Object(); final long token = 81;
        final ConnectivityManager manager = new ConnectivityManager();
        final FakeFactory cameraFactory = new FakeFactory(), otherFactory = new FakeFactory();
        final Network other = new Network(71, otherFactory), camera = new Network(72, cameraFactory);
        final CameraWifiNetwork wifi;
        Fixture() throws Exception {
            manager.active = other; manager.networks = new Network[] {other, camera};
            manager.properties.put(other, properties("10.8.0.254", "10.8.0.2"));
            manager.properties.put(camera, properties("192.168.186.254", "192.168.186.2"));
            wifi = new CameraWifiNetwork(new Context(manager));
        }
        void require() { CameraWifiNetwork.requireCameraNetwork(owner, token); }
        void select() { if (!CameraWifiNetwork.useCameraNetwork(owner, token, camera)) throw new AssertionError("Could not select test camera route"); }
        void release() { CameraWifiNetwork.releaseCameraNetwork(owner, token); }
    }
    private static final class FakeFactory extends SocketFactory {
        int created; FakeSocket last;
        @Override public Socket createSocket() { created++; return last = new FakeSocket(); }
        @Override public Socket createSocket(String host, int port) { return createSocket(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) { return createSocket(); }
        @Override public Socket createSocket(InetAddress host, int port) { return createSocket(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) { return createSocket(); }
    }
    private static final class FakeSocket extends Socket {
        InetSocketAddress endpoint; boolean closed;
        @Override public void connect(SocketAddress address, int timeout) { endpoint = (InetSocketAddress) address; }
        @Override public synchronized void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }
    private static void check(boolean pass, String detail) { if (!pass) throw new AssertionError(detail); checks++; }
}

package com.nikonpreset.usbprobe.album;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import javax.net.SocketFactory;

public final class CameraWifiSocketNetworkTest {
    private static int checks;
    public static void main(String[] ignored) throws Exception {
        ConnectivityManager manager = new ConnectivityManager();
        FakeFactory firstFactory = new FakeFactory(), secondFactory = new FakeFactory();
        Network first = new Network(1, firstFactory), second = new Network(2, secondFactory);
        manager.active = first; manager.networks = new Network[] { first };
        CameraWifiNetwork wifi = new CameraWifiNetwork(new Context(manager));
        check(CameraWifiNetwork.socketNetwork() == null, "No live PTP socket means no retained network");
        PtpIpClient.SocketConnector originalConnector = wifi.connector("192.168.1.1", 15740);
        int initialSockets = firstFactory.sockets.size();
        try { originalConnector.connect("192.168.1.1", 55741, 5000); throw new AssertionError("Nikon accepted a Fuji port"); }
        catch (IOException expected) { check(firstFactory.sockets.size() == initialSockets, "Nikon connector rejects Fuji auxiliary ports before opening a socket"); }
        Socket command = originalConnector.connect("192.168.1.1", 15740, 5000);
        Socket event = originalConnector.connect("192.168.1.1", 15740, 5000);
        check(first.equals(CameraWifiNetwork.socketNetwork()), "Both PTP channels identify their actual bound Wi-Fi");
        manager.active = second; manager.networks = new Network[] { first, second };
        check(first.equals(CameraWifiNetwork.socketNetwork()), "Default-network changes never retarget an existing PTP session");
        Socket stillFirst = originalConnector.connect("192.168.1.1", 15740, 5000);
        check(first.equals(CameraWifiNetwork.socketNetwork()), "An existing connector continues using its originally selected Network");
        Socket another = wifi.connector("192.168.1.1", 15740).connect("192.168.1.1", 15740, 5000);
        check(CameraWifiNetwork.socketNetwork() == null, "Concurrent PTP sockets on different networks do not choose a wrong target");
        another.close();
        check(first.equals(CameraWifiNetwork.socketNetwork()), "A closed replacement socket cannot override the live original");
        command.close();
        check(first.equals(CameraWifiNetwork.socketNetwork()), "Physical event channel still owns Wi-Fi when its peer closes");
        event.close(); stillFirst.close();
        check(CameraWifiNetwork.socketNetwork() == null, "Closed physical channels are pruned and no longer retain Wi-Fi");
        secondFactory.failConnect = true;
        try { wifi.connector("192.168.1.1", 15740).connect("192.168.1.1", 15740, 5000); throw new AssertionError("Expected connect failure"); }
        catch (IOException expected) { check(CameraWifiNetwork.socketNetwork() == null, "A failed socket connection never becomes a target"); }
        check(secondFactory.last.closed, "Failed socket is closed without preserving stale network ownership");
        secondFactory.failConnect = false;
        PtpIpClient.SocketConnector fuji = wifi.fujifilmPhotoConnector("192.168.0.1", 55740);
        Socket fujiCommand = fuji.connect("192.168.0.1", 55740, 5000);
        check(secondFactory.last.endpoint.getPort() == 55740, "Fuji command uses the selected command port");
        Socket fujiEvent = fuji.connect("192.168.0.1", 55741, 5000);
        check(secondFactory.last.endpoint.getPort() == 55741, "Fuji event uses its actual auxiliary port");
        Socket fujiPreview = fuji.connect("192.168.0.1", 55742, 5000);
        check(secondFactory.last.endpoint.getPort() == 55742, "Fuji preview uses its actual auxiliary port");
        check(second.equals(CameraWifiNetwork.socketNetwork()), "All Fuji channels retain the same selected Wi-Fi");
        manager.active = first;
        Socket fujiStillSecond = fuji.connect("192.168.0.1", 55741, 5000);
        check(second.equals(CameraWifiNetwork.socketNetwork()), "Fuji auxiliary channels stay on their original Network after a default-network change");
        int fujiSocketCount = secondFactory.sockets.size();
        for (int forbidden : new int[] {15740, 55739, 55743, 65535}) {
            try { fuji.connect("192.168.0.1", forbidden, 5000); throw new AssertionError("Fuji accepted an unapproved port"); }
            catch (IOException expected) { check(secondFactory.sockets.size() == fujiSocketCount, "Fuji rejects other ports before creating a socket"); }
        }
        try { fuji.connect("192.168.0.2", 55741, 5000); throw new AssertionError("Fuji accepted another host"); }
        catch (IOException expected) { check(secondFactory.sockets.size() == fujiSocketCount, "Fuji auxiliary channels cannot change destination host"); }
        fujiCommand.close(); fujiEvent.close(); fujiPreview.close(); fujiStillSecond.close();
        check(CameraWifiNetwork.socketNetwork() == null, "Closing Fuji channels releases their physical network ownership");

        PtpIpClient.SocketConnector customFuji = wifi.fujifilmPhotoConnector("192.168.0.1", 55880);
        Socket customCommand = customFuji.connect("192.168.0.1", 55880, 5000);
        check(firstFactory.last.endpoint.getPort() == 55880, "Fuji custom command endpoint retains the user's chosen port");
        Socket customEvent = customFuji.connect("192.168.0.1", 55741, 5000);
        check(firstFactory.last.endpoint.getPort() == 55741
                && firstFactory.last.endpoint.getAddress().getHostAddress().equals("192.168.0.1"), "Fuji custom command still opens the fixed event port on the same camera host");
        int customSockets = firstFactory.sockets.size();
        try { customFuji.connect("192.168.0.1", 55740, 5000); throw new AssertionError("Custom Fuji route accepted another command port"); }
        catch (IOException expected) { check(firstFactory.sockets.size() == customSockets, "A custom Fuji route does not also allow the default command port"); }
        customCommand.close(); customEvent.close();

        Object requestedOwner = new Object();
        CameraWifiNetwork.requireCameraNetwork(requestedOwner, 77);
        try {
            check(CameraWifiNetwork.useCameraNetwork(requestedOwner, 77, first), "Fuji explicit network route is selected");
            PtpIpClient.SocketConnector cancelledFuji = wifi.fujifilmPhotoConnector("192.168.0.1", 55740);
            CameraWifiNetwork.cameraNetworkLost(requestedOwner, 77);
            int beforeCancelled = firstFactory.sockets.size();
            try { cancelledFuji.connect("192.168.0.1", 55742, 5000); throw new AssertionError("Lost route opened an auxiliary channel"); }
            catch (IOException expected) { check(firstFactory.sockets.size() == beforeCancelled, "A lost/replaced route prevents Fuji auxiliary sockets from opening"); }
        } finally { CameraWifiNetwork.releaseCameraNetwork(requestedOwner, 77); }
        System.out.println("PASS: " + checks + " camera Wi-Fi socket-target checks");
    }
    private static final class FakeSocket extends Socket {
        private final boolean fail;
        boolean closed;
        InetSocketAddress endpoint;
        FakeSocket(boolean fail) { this.fail = fail; }
        @Override public void connect(SocketAddress address, int timeout) throws IOException { endpoint = (InetSocketAddress) address; if (fail) throw new IOException("synthetic connect failure"); }
        @Override public synchronized void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }
    private static final class FakeFactory extends SocketFactory {
        boolean failConnect;
        FakeSocket last;
        final List<FakeSocket> sockets = new ArrayList<>();
        @Override public Socket createSocket() { last = new FakeSocket(failConnect); sockets.add(last); return last; }
        @Override public Socket createSocket(String host, int port) { return createSocket(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) { return createSocket(); }
        @Override public Socket createSocket(InetAddress host, int port) { return createSocket(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) { return createSocket(); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}

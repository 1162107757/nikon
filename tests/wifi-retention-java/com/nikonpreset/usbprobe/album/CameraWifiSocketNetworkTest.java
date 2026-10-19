package com.nikonpreset.usbprobe.album;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
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
        System.out.println("PASS: " + checks + " camera Wi-Fi socket-target checks");
    }
    private static final class FakeSocket extends Socket {
        private final boolean fail;
        boolean closed;
        FakeSocket(boolean fail) { this.fail = fail; }
        @Override public void connect(SocketAddress address, int timeout) throws IOException { if (fail) throw new IOException("synthetic connect failure"); }
        @Override public synchronized void close() { closed = true; }
        @Override public boolean isClosed() { return closed; }
    }
    private static final class FakeFactory extends SocketFactory {
        boolean failConnect;
        FakeSocket last;
        @Override public Socket createSocket() { return last = new FakeSocket(failConnect); }
        @Override public Socket createSocket(String host, int port) { return createSocket(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) { return createSocket(); }
        @Override public Socket createSocket(InetAddress host, int port) { return createSocket(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) { return createSocket(); }
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}

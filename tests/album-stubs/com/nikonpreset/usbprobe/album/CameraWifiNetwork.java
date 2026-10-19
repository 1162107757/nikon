package com.nikonpreset.usbprobe.album;

import android.content.Context;
import java.io.IOException;

/** Only Android Wi-Fi selection is replaced; all PTP/IP framing remains production code. */
public final class CameraWifiNetwork {
    public static PtpIpClient.SocketConnector testConnector;
    public static volatile String testGateway = "";
    public static volatile java.util.function.Supplier<String> testGatewaySupplier;
    public CameraWifiNetwork(Context context) { }
    public String suggestHost() { return "camera.test"; }
    public String selectedCameraGateway() {
        java.util.function.Supplier<String> supplier = testGatewaySupplier;
        return supplier == null ? testGateway : supplier.get();
    }
    public PtpIpClient.SocketConnector connector(String host, int port) throws IOException {
        if (testConnector == null) throw new IOException("Test connector was not configured");
        return testConnector;
    }
    public PtpIpClient.SocketConnector fujifilmPhotoConnector(String host, int port) throws IOException {
        return connector(host, port);
    }
    public byte[] clientGuid() { return new byte[16]; }
}

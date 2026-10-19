package com.nikonpreset.usbprobe.album;

public final class ConnectionInfo {
    private final String model, host;
    private final int port, handles;
    private final CameraProtocol protocol;
    ConnectionInfo(String model, String host, int port, int handles) { this(model, host, port, handles, CameraProtocol.NIKON); }
    ConnectionInfo(String model, String host, int port, int handles, CameraProtocol protocol) {
        this.model = model; this.host = host; this.port = port; this.handles = handles;
        this.protocol = java.util.Objects.requireNonNull(protocol);
    }
    public String model() { return model; }
    public String host() { return host; }
    public int port() { return port; }
    public int totalHandles() { return handles; }
    public CameraProtocol protocol() { return protocol; }
}

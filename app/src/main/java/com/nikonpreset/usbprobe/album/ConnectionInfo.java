package com.nikonpreset.usbprobe.album;

public final class ConnectionInfo {
    private final String model, host;
    private final int port, handles;
    ConnectionInfo(String model, String host, int port, int handles) { this.model = model; this.host = host; this.port = port; this.handles = handles; }
    public String model() { return model; }
    public String host() { return host; }
    public int port() { return port; }
    public int totalHandles() { return handles; }
}

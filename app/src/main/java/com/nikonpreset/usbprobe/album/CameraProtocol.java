package com.nikonpreset.usbprobe.album;

/** Explicit transport selection; capabilities are deliberately limited per model. */
public enum CameraProtocol {
    NIKON("尼康", "192.168.1.1", 15740, true, true),
    FUJIFILM_X_T20("富士 X-T20（实验）", "", 55740, false, false);

    public final String label, defaultHost;
    public final int defaultPort;
    public final boolean supportsPhotoLive, supportsPictureControl;

    CameraProtocol(String label, String defaultHost, int defaultPort, boolean live, boolean presets) {
        this.label = label; this.defaultHost = defaultHost; this.defaultPort = defaultPort;
        supportsPhotoLive = live; supportsPictureControl = presets;
    }

    public static CameraProtocol fromStored(String value) {
        return FUJIFILM_X_T20.name().equals(value) ? FUJIFILM_X_T20 : NIKON;
    }
}

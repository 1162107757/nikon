package com.nikonpreset.usbprobe.ncp;

import java.io.IOException;

/** Dispatch by the binary record signature, never by an untrusted filename. */
public final class PresetPreviewProfiles {
    private PresetPreviewProfiles() {}

    /** Invalidate template thumbnails whenever either colour approximation changes. */
    public static final String MODEL_VERSION = NcpPreviewProfile.MODEL_VERSION + ":" + Np3PreviewProfile.MODEL_VERSION;

    public static PresetPreviewProfile fromPreset(byte[] bytes) throws IOException {
        if (bytes != null && bytes.length >= 8 && bytes[0] == 'N' && bytes[1] == 'C'
                && bytes[2] == 'P' && bytes[3] == 0 && bytes[4] == 0 && bytes[5] == 0
                && bytes[6] == 1 && bytes[7] == 0) return Np3PreviewProfile.fromNp3(bytes);
        return NcpPreviewProfile.fromNcp(bytes);
    }
}

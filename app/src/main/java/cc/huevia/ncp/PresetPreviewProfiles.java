package cc.huevia.ncp;

import java.io.IOException;

/** Dispatch by the binary record signature, never by an untrusted filename. */
public final class PresetPreviewProfiles {
    private PresetPreviewProfiles() {}

    /** Invalidate template thumbnails whenever either colour approximation changes. */
    public static final String MODEL_VERSION = NcpPreviewProfile.MODEL_VERSION + ":" + Np3PreviewProfile.MODEL_VERSION
            + ":" + Np3MonochromePreviewProfile.MODEL_VERSION + ":" + Np3MonochromePreviewProfile.STANDARD_MODEL_VERSION;

    public static PresetPreviewProfile fromPreset(byte[] bytes) throws IOException {
        if (bytes != null && bytes.length >= 8 && bytes[0] == 'N' && bytes[1] == 'C'
                && bytes[2] == 'P' && bytes[3] == 0 && bytes[4] == 0 && bytes[5] == 0
                && bytes[6] == 1 && bytes[7] == 0) {
            // Routing hint only. The selected parser revalidates every record and
            // active control; unsupported/malformed data never falls through.
            if (monochromeBase(bytes)) return Np3MonochromePreviewProfile.fromNp3(bytes);
            return Np3PreviewProfile.fromNp3(bytes);
        }
        return NcpPreviewProfile.fromNcp(bytes);
    }

    private static boolean monochromeBase(byte[] bytes) {
        int offset = 4;
        for (int index = 1; index <= 3; index++) {
            int length = index == 1 ? 4 : index == 2 ? 20 : 2;
            if (bytes.length - offset < 8 + length || bytes[offset] != 0 || bytes[offset + 1] != 0
                    || (bytes[offset + 2] & 255) != index || bytes[offset + 3] != 0
                    || bytes[offset + 4] != 0 || bytes[offset + 5] != 0 || bytes[offset + 6] != 0
                    || (bytes[offset + 7] & 255) != length) return false;
            offset += 8;
            if (index == 3) return bytes[offset] == 6 && (bytes[offset + 1] == 0x55 || bytes[offset + 1] == 0x4d);
            offset += length;
        }
        return false;
    }
}

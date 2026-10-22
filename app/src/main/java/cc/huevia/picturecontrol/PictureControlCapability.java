package cc.huevia.picturecontrol;

import cc.huevia.ptp.PtpProtocolException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable read-only capability data; unknown flags remain available for diagnosis. */
public final class PictureControlCapability {
    private static final int DATASET_BYTES = 28;
    private static final String[] FLAG_NAMES = {
            "applyLevel", "quickSharp", "sharpening", "middleRangeSharpening",
            "clarity", "contrast", "brightness", "saturation", "hue",
            "filterEffects", "toning", "toningDensity"
    };

    private final byte[] raw;
    private final Map<String, Integer> rawCapabilityFlags;
    private final int defaultQuickSharpLevel;
    private final List<DefaultLevel> defaultLevels;

    private PictureControlCapability(byte[] raw) {
        this.raw = raw;
        Map<String, Integer> flags = new LinkedHashMap<>();
        for (int i = 0; i < FLAG_NAMES.length; i++) {
            flags.put(FLAG_NAMES[i], raw[i] & 0xff);
        }
        rawCapabilityFlags = Collections.unmodifiableMap(flags);
        defaultQuickSharpLevel = raw[12];
        List<DefaultLevel> levels = new ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            int offset = 13 + i * 3;
            levels.add(new DefaultLevel(raw[offset], raw[offset + 1], raw[offset + 2]));
        }
        defaultLevels = Collections.unmodifiableList(levels);
    }

    public static PictureControlCapability parse(byte[] dataset) throws PtpProtocolException {
        if (dataset == null) {
            throw new PtpProtocolException("Missing PictureControl capability data");
        }
        if (dataset.length != DATASET_BYTES) {
            throw new PtpProtocolException("PictureControl capability data must contain 28 bytes");
        }
        return new PictureControlCapability(dataset.clone());
    }

    public Map<String, Integer> rawCapabilityFlags() { return rawCapabilityFlags; }
    public int defaultQuickSharpLevel() { return defaultQuickSharpLevel; }
    public List<DefaultLevel> defaultLevels() { return defaultLevels; }
    public byte[] rawBytes() { return raw.clone(); }

    /** Raw signed parameter values for one default level. */
    public static final class DefaultLevel {
        private final int sharpening;
        private final int middleRangeSharpening;
        private final int clarity;

        private DefaultLevel(int sharpening, int middleRangeSharpening, int clarity) {
            this.sharpening = sharpening;
            this.middleRangeSharpening = middleRangeSharpening;
            this.clarity = clarity;
        }

        public int sharpening() { return sharpening; }
        public int middleRangeSharpening() { return middleRangeSharpening; }
        public int clarity() { return clarity; }
    }
}

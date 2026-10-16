package com.nikonpreset.usbprobe.picturecontrol;

import com.nikonpreset.usbprobe.ptp.PtpProtocolException;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable read-only picture control data; parameter values retain their wire units. */
public final class PictureControlData {
    private static final int HEADER_BYTES = 36;
    private static final int CURVE_BYTES = 578;
    private static final int NAME_OFFSET = 2;
    private static final int NAME_BYTES = 20;

    private final byte[] raw;
    private final int basePictureControl;
    private final String registrationName;
    private final Map<String, Integer> rawParameters;

    private PictureControlData(byte[] raw, String registrationName) {
        this.raw = raw;
        this.basePictureControl = (raw[0] & 0xff) | ((raw[1] & 0xff) << 8);
        this.registrationName = registrationName;
        Map<String, Integer> parameters = new LinkedHashMap<>();
        parameters.put("applyLevel", applyLevel());
        parameters.put("quickSharpFlag", quickSharpFlag());
        parameters.put("quickSharp", quickSharp());
        parameters.put("sharpening", sharpening());
        parameters.put("middleRangeSharpening", middleRangeSharpening());
        parameters.put("clarity", clarity());
        parameters.put("contrast", contrast());
        parameters.put("brightness", brightness());
        parameters.put("saturation", saturation());
        parameters.put("hue", hue());
        parameters.put("filterEffects", filterEffects());
        parameters.put("toning", toning());
        parameters.put("toningDensity", toningDensity());
        parameters.put("customCurveFlag", customCurveFlag());
        rawParameters = Collections.unmodifiableMap(parameters);
    }

    public static PictureControlData parse(byte[] dataset) throws PtpProtocolException {
        if (dataset == null) {
            throw new PtpProtocolException("Missing PictureControl data");
        }
        if (dataset.length != HEADER_BYTES && dataset.length != HEADER_BYTES + CURVE_BYTES) {
            throw new PtpProtocolException("PictureControl data must contain 36 or 614 bytes");
        }
        byte[] raw = dataset.clone();
        int curveFlag = raw[35] & 0xff;
        if (curveFlag != 0 && curveFlag != 1) {
            throw new PtpProtocolException("Invalid PictureControl custom curve flag: " + curveFlag);
        }
        if (curveFlag == 1 && raw.length != HEADER_BYTES + CURVE_BYTES) {
            throw new PtpProtocolException("PictureControl custom curve data is missing");
        }
        int nameEnd = NAME_OFFSET;
        int nameLimit = NAME_OFFSET + NAME_BYTES;
        while (nameEnd < nameLimit && raw[nameEnd] != 0) {
            if ((raw[nameEnd] & 0x80) != 0) {
                throw new PtpProtocolException("PictureControl registration name is not ASCII");
            }
            nameEnd++;
        }
        if (nameEnd == nameLimit) {
            throw new PtpProtocolException("PictureControl registration name lacks a NUL terminator");
        }
        String name = new String(raw, NAME_OFFSET, nameEnd - NAME_OFFSET, StandardCharsets.US_ASCII);
        return new PictureControlData(raw, name);
    }

    public int basePictureControl() { return basePictureControl; }
    public String registrationName() { return registrationName; }
    public boolean customCurveEnabled() { return customCurveFlag() == 1; }

    /** A zero base identifies an unused custom slot; other fields are still retained verbatim. */
    public boolean hasNoBasePictureControl() { return basePictureControl == 0; }

    public int applyLevel() { return raw[22] & 0xff; }
    public int quickSharpFlag() { return raw[23] & 0xff; }
    public int quickSharp() { return raw[24]; }
    public int sharpening() { return raw[25]; }
    public int middleRangeSharpening() { return raw[26]; }
    public int clarity() { return raw[27]; }
    public int contrast() { return raw[28]; }
    public int brightness() { return raw[29]; }
    public int saturation() { return raw[30]; }
    public int hue() { return raw[31]; }
    public int filterEffects() { return raw[32] & 0xff; }
    public int toning() { return raw[33] & 0xff; }
    public int toningDensity() { return raw[34]; }
    public int customCurveFlag() { return raw[35] & 0xff; }

    public Map<String, Integer> rawParameters() { return rawParameters; }
    public byte[] rawBytes() { return raw.clone(); }
}

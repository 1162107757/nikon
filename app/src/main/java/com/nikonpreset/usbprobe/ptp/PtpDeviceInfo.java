package com.nikonpreset.usbprobe.ptp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The standard GetDeviceInfo dataset. Does not establish NCP import capability. */
public final class PtpDeviceInfo {
    private final int standardVersion;
    private final long vendorExtensionId;
    private final int vendorExtensionVersion;
    private final String vendorExtensionDescription;
    private final int functionalMode;
    private final int[] operationsSupported;
    private final int[] eventsSupported;
    private final int[] devicePropertiesSupported;
    private final int[] captureFormats;
    private final int[] imageFormats;
    private final String manufacturer;
    private final String model;
    private final String deviceVersion;
    private final String serialNumber;

    private PtpDeviceInfo(PtpDatasetReader reader, boolean redactSerial) throws PtpProtocolException {
        standardVersion = reader.uint16();
        vendorExtensionId = reader.uint32();
        vendorExtensionVersion = reader.uint16();
        vendorExtensionDescription = reader.string();
        functionalMode = reader.uint16();
        operationsSupported = reader.uint16Array();
        eventsSupported = reader.uint16Array();
        devicePropertiesSupported = reader.uint16Array();
        captureFormats = reader.uint16Array();
        imageFormats = reader.uint16Array();
        manufacturer = reader.string();
        model = reader.string();
        deviceVersion = reader.string();
        if (redactSerial) {
            reader.skipString();
            serialNumber = "";
        } else {
            serialNumber = reader.string();
        }
        reader.requireEnd();
    }

    public static PtpDeviceInfo parse(byte[] dataset) throws PtpProtocolException {
        return new PtpDeviceInfo(new PtpDatasetReader(dataset), false);
    }

    /** Parses all public fields, skips the serial number without decoding/retaining it. */
    public static PtpDeviceInfo parseRedacted(byte[] dataset) throws PtpProtocolException {
        return new PtpDeviceInfo(new PtpDatasetReader(dataset), true);
    }

    public int standardVersion() { return standardVersion; }
    public long vendorExtensionId() { return vendorExtensionId; }
    public int vendorExtensionVersion() { return vendorExtensionVersion; }
    public String vendorExtensionDescription() { return vendorExtensionDescription; }
    public int functionalMode() { return functionalMode; }
    public int[] operationsSupported() { return operationsSupported.clone(); }
    public int[] eventsSupported() { return eventsSupported.clone(); }
    public int[] devicePropertiesSupported() { return devicePropertiesSupported.clone(); }
    public int[] captureFormats() { return captureFormats.clone(); }
    public int[] imageFormats() { return imageFormats.clone(); }
    public String manufacturer() { return manufacturer; }
    public String model() { return model; }
    public String deviceVersion() { return deviceVersion; }
    /** Device identity is sensitive: callers should omit this from exported diagnostics. */
    public String serialNumber() { return serialNumber; }

    public boolean supportsOperation(int operation) {
        for (int value : operationsSupported) if (value == operation) return true;
        return false;
    }

    /** An advertised standard object-send pair is only a lead, not proof of NCP import. */
    public boolean supportsStandardObjectSend() {
        return supportsOperation(PtpConstants.SEND_OBJECT_INFO)
                && supportsOperation(PtpConstants.SEND_OBJECT);
    }

    public List<String> operationsAsHex() {
        List<String> values = new ArrayList<>(operationsSupported.length);
        for (int value : operationsSupported) values.add(PtpConstants.hex16(value));
        return Collections.unmodifiableList(values);
    }
}

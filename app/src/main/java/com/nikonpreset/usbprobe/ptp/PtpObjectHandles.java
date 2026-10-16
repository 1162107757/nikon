package com.nikonpreset.usbprobe.ptp;

/** Decoder for the UINT32-array dataset returned by GetObjectHandles. */
public final class PtpObjectHandles {
    private PtpObjectHandles() {}

    public static long[] parse(byte[] dataset) throws PtpProtocolException {
        return PtpStorageIds.parse(dataset);
    }
}

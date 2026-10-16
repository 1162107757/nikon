package com.nikonpreset.usbprobe.ptp;

/** Decoder for the UINT32-array dataset returned by GetStorageIDs. */
public final class PtpStorageIds {
    private PtpStorageIds() {}

    public static long[] parse(byte[] dataset) throws PtpProtocolException {
        PtpDatasetReader reader = new PtpDatasetReader(dataset);
        long[] result = reader.uint32Array();
        reader.requireEnd();
        return result;
    }
}

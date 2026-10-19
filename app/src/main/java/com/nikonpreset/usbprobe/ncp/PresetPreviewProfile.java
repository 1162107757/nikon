package com.nikonpreset.usbprobe.ncp;

/** Phone-side colour approximation shared by legacy NCP and Flexible Color NP3. */
public interface PresetPreviewProfile {
    String registrationName();
    String modelVersion();
    double mapTone(double normalized);
    int applyArgb(int argb);

    /** A separate, bounded output for thumbnails; full-size photos use bounded strips. */
    default int[] render(int[] argb, int width, int height) {
        long pixels = (long) width * height;
        if (argb == null || width <= 0 || height <= 0 || pixels > NcpPreviewProfile.MAX_PIXELS
                || pixels != argb.length) throw new IllegalArgumentException("Invalid preview dimensions.");
        int[] output = new int[argb.length];
        for (int i = 0; i < output.length; i++) output[i] = applyArgb(argb[i]);
        return output;
    }
}

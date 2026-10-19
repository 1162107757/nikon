package com.nikonpreset.usbprobe.album;
import android.content.Context;
/** The independent publisher suite exercises production MediaStore code. */
public final class RawPhotoPublisher {
    public RawPhotoPublisher(Context context) { }
    public String publish(RawPhotoDownload photo) { return "content://raw-test/" + photo.sha256(); }
}

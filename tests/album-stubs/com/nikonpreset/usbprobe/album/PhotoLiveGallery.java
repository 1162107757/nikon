package com.nikonpreset.usbprobe.album;

import android.content.Context;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Camera suite boundary only; real ownership/MediaStore deletion runs in the gallery suite. */
public final class PhotoLiveGallery {
    public static volatile String failName = "";
    public static volatile CountDownLatch deleting, releaseDelete;
    public PhotoLiveGallery(Context context) { }
    public void deletePublished(DownloadedPhoto photo) throws IOException {
        CountDownLatch began = deleting, release = releaseDelete;
        if (began != null) began.countDown();
        if (release != null) try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("synthetic delete timeout"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
        if (photo.file().getName().equals(failName)) throw new IOException("synthetic provider deletion failure");
    }
}

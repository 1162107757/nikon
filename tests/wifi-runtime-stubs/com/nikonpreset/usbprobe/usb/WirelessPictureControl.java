package com.nikonpreset.usbprobe.usb;
import com.nikonpreset.usbprobe.album.PtpIpClient;
public final class WirelessPictureControl {
    public int closes, releases;
    public boolean prepared = true;
    public void close() { closes++; prepared = false; }
    public boolean isPrepared() { return prepared; }
    public void keepPreparedAlive() throws Exception { }
    public void releasePrepared() throws Exception { releases++; }
    public PtpIpClient transferPreparedToAlbum() { return new PtpIpClient(); }
    public static boolean isRecoverableKeepAliveResponse(int response) { return false; }
}

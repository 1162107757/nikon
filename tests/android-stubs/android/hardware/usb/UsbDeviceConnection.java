package android.hardware.usb;

/** Replaced by a scripted connection in the host integration test. */
public abstract class UsbDeviceConnection {
    public abstract boolean claimInterface(UsbInterface intf, boolean force);
    public abstract boolean releaseInterface(UsbInterface intf);
    public abstract void close();
    public abstract int bulkTransfer(UsbEndpoint endpoint, byte[] bytes, int length, int timeout);
    public abstract int bulkTransfer(UsbEndpoint endpoint, byte[] bytes, int offset, int length, int timeout);
}

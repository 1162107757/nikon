package android.hardware.usb;

public final class UsbEndpoint {
    private final int address;
    public UsbEndpoint(int address) { this.address = address; }
    public int getAddress() { return address; }
    public int getDirection() { return address & 128; }
    public int getType() { return UsbConstants.USB_ENDPOINT_XFER_BULK; }
    public int getMaxPacketSize() { return 512; }
}

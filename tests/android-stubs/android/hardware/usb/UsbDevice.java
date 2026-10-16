package android.hardware.usb;

public final class UsbDevice {
    private final UsbInterface intf = new UsbInterface();
    public int getVendorId() { return 0x04b0; }
    public int getProductId() { return 0x0437; }
    public int getDeviceId() { return 17; }
    public String getDeviceName() { return "/host-test/nikon-z6ii"; }
    public int getInterfaceCount() { return 1; }
    public UsbInterface getInterface(int index) {
        if (index != 0) throw new AssertionError("Unexpected interface");
        return intf;
    }
    public String getSerialNumber() { throw new AssertionError("The probe must not read a serial number"); }
}

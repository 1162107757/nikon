package android.hardware.usb;

public final class UsbInterface {
    private final UsbEndpoint[] endpoints = {new UsbEndpoint(0x81), new UsbEndpoint(0x02)};
    public int getId() { return 0; }
    public int getInterfaceClass() { return UsbConstants.USB_CLASS_STILL_IMAGE; }
    public int getEndpointCount() { return endpoints.length; }
    public UsbEndpoint getEndpoint(int index) { return endpoints[index]; }
}

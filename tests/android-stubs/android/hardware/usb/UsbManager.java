package android.hardware.usb;

import java.util.HashMap;

public final class UsbManager {
    private final UsbDevice device;
    private final UsbDeviceConnection connection;
    public UsbManager(UsbDevice device, UsbDeviceConnection connection) {
        this.device = device;
        this.connection = connection;
    }
    public boolean hasPermission(UsbDevice candidate) { return candidate == device; }
    public UsbDeviceConnection openDevice(UsbDevice candidate) {
        if (candidate != device) throw new AssertionError("Unexpected device");
        return connection;
    }
    public HashMap<String, UsbDevice> getDeviceList() {
        HashMap<String, UsbDevice> devices = new HashMap<>();
        devices.put(device.getDeviceName(), device);
        return devices;
    }
}

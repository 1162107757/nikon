package android.net;

public class LinkAddress {
    private final java.net.InetAddress address;
    public LinkAddress(java.net.InetAddress address, int prefixLength) { this.address = address; }
    public java.net.InetAddress getAddress() { return address; }
}

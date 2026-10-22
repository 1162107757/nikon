package android.net;
public class NetworkCapabilities {
    public static final int TRANSPORT_WIFI = 1;
    public static final int TRANSPORT_VPN = 4;
    public static final int NET_CAPABILITY_INTERNET = 12;
    private final boolean wifi, vpn;
    public NetworkCapabilities() { this(true); }
    public NetworkCapabilities(boolean wifi) { this(wifi, false); }
    public NetworkCapabilities(boolean wifi, boolean vpn) { this.wifi = wifi; this.vpn = vpn; }
    public boolean hasTransport(int value) { return wifi && value == TRANSPORT_WIFI || vpn && value == TRANSPORT_VPN; }
}

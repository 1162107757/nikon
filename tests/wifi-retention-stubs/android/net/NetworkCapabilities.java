package android.net;
public class NetworkCapabilities {
    public static final int TRANSPORT_WIFI = 1;
    public static final int NET_CAPABILITY_INTERNET = 12;
    private final boolean wifi;
    public NetworkCapabilities() { this(true); }
    public NetworkCapabilities(boolean wifi) { this.wifi = wifi; }
    public boolean hasTransport(int value) { return wifi && value == TRANSPORT_WIFI; }
}

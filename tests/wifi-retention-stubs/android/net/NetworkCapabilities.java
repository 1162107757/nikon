package android.net;
public class NetworkCapabilities {
    public static final int TRANSPORT_WIFI = 1;
    public static final int NET_CAPABILITY_INTERNET = 12;
    public boolean hasTransport(int value) { return value == TRANSPORT_WIFI; }
}

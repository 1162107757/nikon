package android.net;
public class RouteInfo {
    private final java.net.InetAddress gateway;
    private final boolean defaultRoute;
    public RouteInfo() { this(null, false); }
    public RouteInfo(java.net.InetAddress gateway, boolean defaultRoute) {
        this.gateway = gateway; this.defaultRoute = defaultRoute;
    }
    public java.net.InetAddress getGateway() { return gateway; }
    public boolean isDefaultRoute() { return defaultRoute; }
}

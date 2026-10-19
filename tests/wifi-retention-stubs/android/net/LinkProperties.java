package android.net;
public class LinkProperties {
    public final java.util.List<RouteInfo> routes = new java.util.ArrayList<>();
    public final java.util.List<LinkAddress> linkAddresses = new java.util.ArrayList<>();
    public java.util.List<RouteInfo> getRoutes() { return routes; }
    public java.util.List<LinkAddress> getLinkAddresses() { return linkAddresses; }
}

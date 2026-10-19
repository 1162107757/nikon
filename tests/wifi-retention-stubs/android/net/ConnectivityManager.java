package android.net;
public class ConnectivityManager {
    public static class NetworkCallback {
        public void onAvailable(Network network) { }
        public void onLost(Network network) { }
        public void onUnavailable() { }
        public void onBlockedStatusChanged(Network network, boolean blocked) { }
    }
    public int requests, releases;
    public NetworkRequest lastRequest;
    public NetworkCallback lastCallback, releasedCallback;
    public RuntimeException requestFailure, releaseFailure;
    public Network active;
    public Network[] networks = new Network[0];
    public final java.util.Map<Network, LinkProperties> properties = new java.util.HashMap<>();
    public final java.util.Map<Network, NetworkCapabilities> capabilities = new java.util.HashMap<>();
    public final java.util.List<Network> propertyLookups = new java.util.ArrayList<>();
    public Runnable onNextPropertyLookup;
    public Network getActiveNetwork() { return active; }
    public Network[] getAllNetworks() { return networks; }
    public NetworkCapabilities getNetworkCapabilities(Network network) {
        return network == null ? null : capabilities.containsKey(network) ? capabilities.get(network) : new NetworkCapabilities();
    }
    public LinkProperties getLinkProperties(Network network) {
        propertyLookups.add(network);
        Runnable callback = onNextPropertyLookup; onNextPropertyLookup = null;
        if (callback != null) callback.run();
        return properties.containsKey(network) ? properties.get(network) : new LinkProperties();
    }
    public void requestNetwork(NetworkRequest request, NetworkCallback callback) {
        requests++;
        if (requestFailure != null) throw requestFailure;
        lastRequest = request; lastCallback = callback;
    }
    public void requestNetwork(NetworkRequest request, NetworkCallback callback, android.os.Handler handler) { requestNetwork(request, callback); }
    public void unregisterNetworkCallback(NetworkCallback callback) {
        releases++; releasedCallback = callback;
        if (releaseFailure != null) throw releaseFailure;
    }
}

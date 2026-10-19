package android.net;
import java.util.HashSet;
import java.util.Set;
public class NetworkRequest {
    public final Set<Integer> transports = new HashSet<>(), removedCapabilities = new HashSet<>();
    public Object specifier;
    public static class Builder {
        private final NetworkRequest request = new NetworkRequest();
        public Builder addTransportType(int value) { request.transports.add(value); return this; }
        public Builder removeCapability(int value) { request.removedCapabilities.add(value); return this; }
        public Builder setNetworkSpecifier(Object value) { request.specifier = value; return this; }
        public NetworkRequest build() { return request; }
    }
}

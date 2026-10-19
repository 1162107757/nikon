package android.net;
public final class Network {
    private final int id;
    private final javax.net.SocketFactory factory;
    public Network(int id) { this(id, javax.net.SocketFactory.getDefault()); }
    public Network(int id, javax.net.SocketFactory factory) { this.id = id; this.factory = factory; }
    public javax.net.SocketFactory getSocketFactory() { return factory; }
    @Override public boolean equals(Object other) { return other instanceof Network && ((Network) other).id == id; }
    @Override public int hashCode() { return id; }
}

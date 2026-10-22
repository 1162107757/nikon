package cc.huevia.album;
public class PtpIpClient {
    public int closeCount;
    public void close() { closeCount++; }
    public interface SocketConnector {
        java.net.Socket connect(String host, int port, int timeout) throws java.io.IOException;
    }
}

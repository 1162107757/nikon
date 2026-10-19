package com.nikonpreset.usbprobe.album;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.LongConsumer;

/** Read-only photo transport. Camera-specific setup never grants preset write access. */
public interface PhotoCameraClient extends AutoCloseable {
    interface Progress { void onProgress(long received, long total); }

    byte[] connect(String host, int port, byte[] guid, String friendlyName) throws IOException;
    boolean isOpen();
    boolean isProtocolUnconfirmed();
    String closeReason();
    String connectedHost();
    int connectedPort();
    String cameraIdentity();
    void setOnClosed(Runnable listener);
    void setOnObjectAdded(LongConsumer listener);
    byte[] readAlbumDeviceInfo() throws IOException;
    /** Serialized page scope; transports may share negotiation across metadata reads. */
    default void beginPhotoPage() throws IOException { }
    default void endPhotoPage() throws IOException { }
    byte[] getData(int operation, int maxBytes, long... parameters) throws IOException;
    byte[] getLargeThumbnail(long handle) throws IOException;
    long readTo(int operation, OutputStream destination, long maxBytes, long expectedBytes,
                Progress progress, long... parameters) throws IOException;
    void abortProtocol(IOException failure);
    @Override void close();
}

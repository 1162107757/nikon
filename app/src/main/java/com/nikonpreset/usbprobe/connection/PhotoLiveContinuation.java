package com.nikonpreset.usbprobe.connection;

/**
 * A one-shot user intent to resume Photo Live setup after connecting a camera.
 * Callers keep only a revision token in queued work; this object retains no UI.
 */
public final class PhotoLiveContinuation {
    private long revision;
    private boolean pending;
    private boolean connectionAccepted;

    /** Starts a new intent; a prior connection attempt cannot authorize this one. */
    public synchronized long request() {
        pending = true;
        connectionAccepted = false;
        return ++revision;
    }

    /** Records the user's explicit connection attempt without consuming the intent. */
    public synchronized void connectionAttempted() {
        if (pending) connectionAccepted = true;
    }

    public synchronized boolean isPending() { return pending; }
    public synchronized boolean isConnectionAccepted() { return connectionAccepted; }
    public synchronized long revision() { return revision; }

    public synchronized boolean matches(long expectedRevision) {
        return pending && revision == expectedRevision;
    }

    /** Claims continuation once, only after the user accepted a connection attempt. */
    public synchronized boolean consume(long expectedRevision) {
        if (!pending || !connectionAccepted || revision != expectedRevision) return false;
        pending = false;
        connectionAccepted = false;
        revision++;
        return true;
    }

    /** Invalidates queued work even when no intent is currently pending. */
    public synchronized void cancel() {
        pending = false;
        connectionAccepted = false;
        revision++;
    }
}

package cc.huevia.ui;

/** UI-thread pagination state for a fixed camera handle snapshot. */
public final class AlbumPaging {
    public enum Result { STALE, ADVANCED, EXHAUSTED, STALLED }

    /** Identity is also the completion token; an old page cannot finish a newer request. */
    public static final class Request {
        private final int offset;
        private Request(int offset) { this.offset = offset; }
        public int offset() { return offset; }
    }

    private int cursor;
    private boolean hasMore, paused;
    private Request pending;

    /** Start a new snapshot, invalidating every outstanding completion. */
    public void reset(boolean more) {
        cursor = 0;
        hasMore = more;
        paused = false;
        pending = null;
    }

    public int cursor() { return cursor; }
    public boolean hasMore() { return hasMore; }
    public boolean isLoading() { return pending != null; }
    public boolean isPaused() { return paused; }

    /** Scroll callbacks may repeat: only the first one obtains a request. */
    public Request begin() {
        if (!hasMore || paused || pending != null) return null;
        pending = new Request(cursor);
        return pending;
    }

    /**
     * A filtered page may have no JPEGs and still advance through camera handles.
     * A nonterminal page must advance, otherwise automatic loading would loop forever.
     */
    public Result succeed(Request request, int nextOffset, boolean more) {
        if (request == null || pending != request) return Result.STALE;
        pending = null;
        if (nextOffset < cursor || (more && nextOffset == cursor)) {
            paused = true;
            return Result.STALLED;
        }
        cursor = nextOffset;
        hasMore = more;
        return more ? Result.ADVANCED : Result.EXHAUSTED;
    }

    /** A failed cursor remains paused through layouts and repeated scroll callbacks. */
    public boolean fail(Request request) {
        if (request == null || pending != request) return false;
        pending = null;
        paused = true;
        return true;
    }

    /** Only an explicit user retry should resume a failed cursor. */
    public void retry() { paused = false; }
}

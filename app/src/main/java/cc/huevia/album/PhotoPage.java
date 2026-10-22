package cc.huevia.album;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PhotoPage {
    private final List<PhotoItem> items;
    private final int nextOffset, total;
    PhotoPage(List<PhotoItem> items, int nextOffset, int total) {
        this.items = Collections.unmodifiableList(new ArrayList<>(items)); this.nextOffset = nextOffset; this.total = total;
    }
    public List<PhotoItem> items() { return items; }
    public int nextOffset() { return nextOffset; }
    public boolean hasMore() { return nextOffset < total; }
    /** Handle snapshot size; when format-filtering is unavailable this can include non-JPEG objects. */
    public int totalHandles() { return total; }
}

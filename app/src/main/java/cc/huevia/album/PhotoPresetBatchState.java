package cc.huevia.album;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable app-owned batch snapshot; results are complete local derived JPEGs. */
public final class PhotoPresetBatchState {
    public final boolean active;
    public final int total, completed, failed, cancelled, reused, merged;
    public final double progress;
    /** Current input's work fraction; separate from overall progress for multi-photo jobs. */
    public final double currentProgress;
    public final int currentNumber;
    public final String currentStage;
    public final String currentFilename, presetName, error;
    public final List<String> errors;
    public final List<DownloadedPhoto> results;
    public final boolean cancelRequested;
    /** Real source-order outcomes. Old snapshots without an item ledger expose an empty list, never inferred success rows. */
    public final List<Item> items;
    public static final class Item {
        public final int index;
        public final String key, filename, status, error;
        public final int width, height, quality;
        Item(int index, String key, String filename, String status, String error, int width, int height, int quality) {
            this.index = index; this.key = key; this.filename = filename; this.status = status; this.error = error == null ? "" : error;
            this.width = width; this.height = height; this.quality = quality;
        }
    }
    PhotoPresetBatchState(boolean active, int total, int completed, int failed, int cancelled, double progress,
                          String currentFilename, String presetName, String error, List<String> errors, List<DownloadedPhoto> results) {
        this(active, total, completed, failed, cancelled, progress, currentFilename, presetName, error, errors, results, 0, 0);
    }
    PhotoPresetBatchState(boolean active, int total, int completed, int failed, int cancelled, double progress,
                          String currentFilename, String presetName, String error, List<String> errors, List<DownloadedPhoto> results,
                          int reused, int merged) {
        this(active, total, completed, failed, cancelled, progress, currentFilename, presetName, error, errors, results,
                reused, merged, active && total > 0 ? Math.min(total, completed + failed + 1) : 0,
                active ? Math.max(0, progress * total - completed - failed) : completed > 0 && failed == 0 && cancelled == 0 ? 1 : 0, "");
    }
    PhotoPresetBatchState(boolean active, int total, int completed, int failed, int cancelled, double progress,
                          String currentFilename, String presetName, String error, List<String> errors, List<DownloadedPhoto> results,
                          int reused, int merged, int currentNumber, double currentProgress, String currentStage) {
        this(active, total, completed, failed, cancelled, progress, currentFilename, presetName, error, errors, results,
                reused, merged, currentNumber, currentProgress, currentStage, Collections.emptyList(), false);
    }
    PhotoPresetBatchState(boolean active, int total, int completed, int failed, int cancelled, double progress,
                          String currentFilename, String presetName, String error, List<String> errors, List<DownloadedPhoto> results,
                          int reused, int merged, int currentNumber, double currentProgress, String currentStage, List<Item> items, boolean cancelRequested) {
        this.active = active; this.total = total; this.completed = completed; this.failed = failed; this.cancelled = cancelled;
        this.reused = reused; this.merged = merged;
        this.progress = bounded(progress); this.currentProgress = bounded(currentProgress);
        this.currentNumber = Math.max(0, Math.min(total, currentNumber)); this.currentStage = currentStage == null ? "" : currentStage;
        this.currentFilename = currentFilename; this.presetName = presetName; this.error = error;
        this.errors = Collections.unmodifiableList(new ArrayList<>(errors)); this.results = Collections.unmodifiableList(new ArrayList<>(results));
        this.items = Collections.unmodifiableList(new ArrayList<>(items)); this.cancelRequested = cancelRequested;
    }
    private static double bounded(double value) { return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0; }
}

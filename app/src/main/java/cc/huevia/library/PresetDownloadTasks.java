package cc.huevia.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Process-owned downloads survive navigation and Activity recreation; only verified commits succeed. */
public final class PresetDownloadTasks {
    private static final PresetDownloadTasks SHARED = new PresetDownloadTasks(Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "preset-download"); thread.setDaemon(true); return thread;
    }));
    private final Executor executor;
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Set<Runnable> observers = new LinkedHashSet<>();
    private long nextIdentity;
    private long libraryRevision;
    public static PresetDownloadTasks shared() { return SHARED; }
    PresetDownloadTasks(Executor executor) { this.executor = executor; }

    public static final class State {
        public final String sha256, name, stage, error, note;
        public final long identity, received, total;
        public final boolean active;
        public final PresetRecord record;
        State(String sha, String name, long identity, String stage, long received, long total,
              boolean active, PresetRecord record, String error, String note) {
            this.sha256 = sha; this.name = name; this.identity = identity; this.stage = stage;
            this.received = received; this.total = total; this.active = active; this.record = record; this.error = error; this.note = note;
        }
        /** -1 is an honest unknown stage; 100 is reserved for a committed, verified result. */
        public int percent() {
            if (record != null && !active && error.isEmpty()) return 100;
            return total > 0 ? (int) Math.max(0, Math.min(99, received * 100 / total)) : -1;
        }
    }

    public synchronized State state(String sha) { return states.get(sha); }
    public synchronized List<State> snapshot() { return new ArrayList<>(states.values()); }
    public synchronized boolean isActive(String sha) { State state = states.get(sha); return state != null && state.active; }
    public synchronized long libraryRevision() { return libraryRevision; }
    public synchronized void addObserver(Runnable observer) { if (observer != null) observers.add(observer); }
    public synchronized void removeObserver(Runnable observer) { observers.remove(observer); }
    public synchronized boolean hasActive() { for (State state : states.values()) if (state.active) return true; return false; }
    public void forget(String sha) { synchronized (this) { State previous = states.get(sha); if (previous != null && previous.active) return; states.remove(sha); libraryRevision++; } changed(); }

    public boolean start(OnlinePreset preset, OnlinePresetRepository repository) throws IOException {
        if (preset == null || repository == null) throw new IOException("请先选择要下载的预设。");
        final long identity;
        synchronized (this) {
            State previous = states.get(preset.sha256); if (previous != null && previous.active) return false;
            int running = 0; for (State state : states.values()) if (state.active) running++;
            if (running >= 4) throw new IOException("已有多份预设正在下载，请等待其中一份完成后再试。");
            identity = ++nextIdentity;
            states.put(preset.sha256, new State(preset.sha256, preset.name, identity, "等待下载", 0, preset.byteLength, true, null, "", ""));
            if (states.size() > 64) {
                var iterator = states.entrySet().iterator(); while (states.size() > 64 && iterator.hasNext()) {
                    Map.Entry<String, State> entry = iterator.next(); if (!entry.getValue().active && !entry.getKey().equals(preset.sha256)) iterator.remove();
                }
            }
        }
        changed();
        try { executor.execute(() -> run(preset, repository, identity)); }
        catch (RuntimeException rejected) { finish(preset.sha256, identity, null, "下载任务暂时无法启动，请重试。", ""); }
        return true;
    }

    private void run(OnlinePreset preset, OnlinePresetRepository repository, long identity) {
        try {
            PresetLibrary.AddResult result = repository.download(preset, (received, total) -> {
                if (Thread.currentThread().isInterrupted()) throw new IOException("下载已中断，请重新下载。");
                update(preset.sha256, identity, received >= total && total > 0 ? "保存中" : "下载预设", received, total);
            });
            if (!preset.sha256.equals(result.record.sha256) || preset.byteLength != result.record.byteLength
                    || !preset.fileFormat.equals(result.record.fileFormat)) throw new IOException("下载结果与所选预设不符。");
            String note = "";
            if (!preset.previewUrl.isEmpty()) {
                update(preset.sha256, identity, "载入样片", 0, -1);
                try { repository.downloadPreview(preset); }
                catch (Exception | OutOfMemoryError optionalSample) { note = "预设已保存，实拍参考可稍后重新加载。"; }
            }
            finish(preset.sha256, identity, result.record, "", note);
        } catch (Exception | OutOfMemoryError failure) {
            String message = failure.getMessage();
            finish(preset.sha256, identity, null, message == null || message.isEmpty() ? "下载未完成，请检查网络后重试。" : message, "");
        }
    }

    private void update(String sha, long identity, String stage, long received, long total) throws IOException {
        synchronized (this) {
            State previous = states.get(sha);
            if (previous == null || previous.identity != identity || !previous.active) throw new IOException("下载任务已更新。");
            if (received < 0 || total > 0 && received > total) throw new IOException("下载进度数据无效。");
            states.put(sha, new State(sha, previous.name, identity, stage, received, total, true, null, "", ""));
        }
        changed();
    }

    private void finish(String sha, long identity, PresetRecord record, String error, String note) {
        synchronized (this) {
            State previous = states.get(sha); if (previous == null || previous.identity != identity) return;
            if (record != null) libraryRevision++;
            states.put(sha, new State(sha, previous.name, identity, record != null ? "已下载" : "下载未完成",
                    previous.received, previous.total, false, record, error, note));
        }
        changed();
    }
    private void changed() {
        List<Runnable> listeners; synchronized (this) { listeners = new ArrayList<>(observers); }
        for (Runnable listener : listeners) try { listener.run(); } catch (RuntimeException ignored) { }
    }
}

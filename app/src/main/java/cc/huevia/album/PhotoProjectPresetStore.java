package cc.huevia.album;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Worker-owned durable project defaults and processing journal; UI reads immutable memory only. */
public final class PhotoProjectPresetStore {
    static final String FILE_NAME = "project-presets.bin";
    private static final int MAGIC = 0x50505031, MAX_TASKS = 20000, MAX_CONFIGS = 1024, MAX_BYTES = 32 * 1024 * 1024;
    private static final Object DISK_LOCK = new Object();
    private final File directory;
    private final AtomicReplace replace;
    private volatile State state;
    private boolean committedFileSeen;
    interface AtomicReplace { void replace(File temporary, File target) throws IOException; }
    private interface Locked<T> { T run() throws IOException; }

    public static final class Config {
        public final String projectId, presetId, presetName;
        public final long enabledSince;
        public Config(String projectId, String presetId, String presetName, long enabledSince) {
            project(projectId); preset(presetId, true); text(presetName, 240, presetId.isEmpty());
            if (presetId.isEmpty() && (!presetName.isEmpty() || enabledSince != 0) || !presetId.isEmpty() && enabledSince <= 0) throw new IllegalArgumentException("项目预设启用时间无效。");
            this.projectId = projectId; this.presetId = presetId; this.presetName = presetName; this.enabledSince = enabledSince;
        }
    }

    /** Exact defaults confirmed by the user; failed history remains available for later retry. */
    public static final class RemovalReferences {
        public final String presetId;
        public final List<Config> configs;
        public final int failedTasks;
        private RemovalReferences(String presetId, List<Config> configs, int failedTasks) {
            this.presetId = presetId; this.configs = Collections.unmodifiableList(configs); this.failedTasks = failedTasks;
        }
    }

    public static final class Task {
        public final String projectId, identity, presetId, presetName, sourceFileName, sourceDisplayName, status, resultFileName, error;
        public final long sourceBytes, updatedAt;
        public Task(String projectId, String identity, String presetId, String presetName,
                    String sourceFileName, String sourceDisplayName, long sourceBytes,
                    String status, String resultFileName, String error, long updatedAt) {
            project(projectId); preset(identity, false); preset(presetId, false); text(presetName, 240, false);
            filename(sourceFileName, false); filename(sourceDisplayName, false); filename(resultFileName, true); text(error, 2048, true);
            if (sourceBytes < 4 || sourceBytes > 512L * 1024 * 1024 || updatedAt < 0) throw new IllegalArgumentException("项目照片大小或时间无效。");
            if (!"queued".equals(status) && !"processing".equals(status) && !"saved".equals(status) && !"error".equals(status)) throw new IllegalArgumentException("项目照片处理状态无效。");
            if ("saved".equals(status) && (resultFileName.isEmpty() || !error.isEmpty())
                    || "error".equals(status) && (error.isEmpty() || !resultFileName.isEmpty())
                    || ("queued".equals(status) || "processing".equals(status)) && (!resultFileName.isEmpty() || !error.isEmpty())) throw new IllegalArgumentException("项目照片处理结果与状态不符。");
            if (!resultFileName.isEmpty() && resultFileName.equals(sourceFileName)) throw new IllegalArgumentException("预设结果必须与原图分开保存。");
            this.projectId = projectId; this.identity = identity; this.presetId = presetId; this.presetName = presetName;
            this.sourceFileName = sourceFileName; this.sourceDisplayName = sourceDisplayName; this.sourceBytes = sourceBytes;
            this.status = status; this.resultFileName = resultFileName; this.error = error; this.updatedAt = updatedAt;
        }
        public Task withStatus(String status, String resultFileName, String error) {
            return new Task(projectId, identity, presetId, presetName, sourceFileName, sourceDisplayName, sourceBytes,
                    status, resultFileName, error, Math.max(System.currentTimeMillis(), updatedAt == Long.MAX_VALUE ? updatedAt : updatedAt + 1));
        }
        public String key() { return projectId + ':' + identity + ':' + presetId; }
    }

    public PhotoProjectPresetStore(File directory) throws IOException {
        this(directory, (temporary, target) -> {
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { throw new IOException("此存储位置不支持原子保存，项目预设未更改。", unsupported); }
        });
    }

    /** Construct once when its runtime starts; interrupted processing records are atomically returned to queued. */
    PhotoProjectPresetStore(File directory, AtomicReplace replace) throws IOException {
        if (directory == null || replace == null) throw new IllegalArgumentException("项目预设目录或写入方式无效。");
        if (Files.isSymbolicLink(directory.toPath())) throw new IOException("项目预设目录不能是符号链接。");
        this.directory = directory.getCanonicalFile(); this.replace = replace;
        if (!this.directory.isDirectory() && !this.directory.mkdirs() && !this.directory.isDirectory()) throw new IOException("无法创建项目预设目录。");
        state = locked(() -> {
            State current = readCommitted(); Map<String, Task> recovered = new TreeMap<>(current.tasks); boolean changed = false;
            for (Task task : current.tasks.values()) if (task.status.equals("processing")) {
                recovered.put(task.key(), new Task(task.projectId, task.identity, task.presetId, task.presetName,
                        task.sourceFileName, task.sourceDisplayName, task.sourceBytes, "queued", "", "", task.updatedAt)); changed = true;
            }
            if (!changed) return current;
            State next = new State(current.configs, recovered); persist(next); return next;
        });
    }

    public Config config(String projectId) {
        project(projectId); Config found = state.configs.get(projectId);
        return found == null ? new Config(projectId, "", "", 0) : found;
    }
    /** Empty presetId disables future automatic queueing; existing history and queued records remain intact. */
    public synchronized Config setConfig(String projectId, String presetId, String presetName) throws IOException {
        project(projectId); preset(presetId, true); text(presetName, 240, presetId.isEmpty());
        if (presetId.isEmpty() && !presetName.isEmpty()) throw new IllegalArgumentException("关闭项目预设时名称应为空。");
        return locked(() -> {
            State current = readCommitted(); Config previous = current.configs.get(projectId);
            if (previous != null && previous.presetId.equals(presetId) && previous.presetName.equals(presetName)) { state = current; return previous; }
            Config nextConfig = new Config(projectId, presetId, presetName, presetId.isEmpty() ? 0
                    : previous != null && previous.presetId.equals(presetId) ? previous.enabledSince : Math.max(1, System.currentTimeMillis()));
            Map<String, Config> configs = new TreeMap<>(current.configs);
            if (presetId.isEmpty()) configs.remove(projectId); else configs.put(projectId, nextConfig);
            if (configs.equals(current.configs)) { state = current; return nextConfig; }
            State next = new State(configs, current.tasks); persist(next); state = next; return nextConfig;
        });
    }

    public synchronized RemovalReferences inspectRemoval(String presetId) throws IOException {
        preset(presetId, false);
        return locked(() -> { State current = readCommitted(); state = current; return removalReferences(current, presetId); });
    }

    /** Single atomic update. A changed confirmation is rejected before clearing any default. */
    public synchronized int clearPresetReferences(RemovalReferences expected) throws IOException {
        if (expected == null) throw new IllegalArgumentException("缺少预设删除确认。");
        return locked(() -> {
            State current = readCommitted(); RemovalReferences actual = removalReferences(current, expected.presetId);
            if (!sameReferences(expected, actual)) throw new IOException("项目默认预设或待重试记录已变化，请重新确认删除。");
            Map<String, Config> configs = new TreeMap<>(current.configs);
            for (Config config : actual.configs) configs.remove(config.projectId);
            if (!actual.configs.isEmpty()) { State next = new State(configs, current.tasks); persist(next); state = next; }
            else state = current;
            return actual.configs.size();
        });
    }

    private static RemovalReferences removalReferences(State current, String presetId) throws IOException {
        List<Config> configs = new ArrayList<>(); int failed = 0;
        for (Config config : current.configs.values()) if (config.presetId.equals(presetId)) configs.add(config);
        for (Task task : current.tasks.values()) if (task.presetId.equals(presetId)) {
            if (task.status.equals("queued") || task.status.equals("processing"))
                throw new IOException("这份预设还有等待或正在处理的照片，请先完成处理，再删除预设。");
            if (task.status.equals("error")) failed++;
        }
        return new RemovalReferences(presetId, configs, failed);
    }

    private static boolean sameReferences(RemovalReferences first, RemovalReferences second) {
        if (!first.presetId.equals(second.presetId) || first.failedTasks != second.failedTasks || first.configs.size() != second.configs.size()) return false;
        for (int index = 0; index < first.configs.size(); index++) {
            Config a = first.configs.get(index), b = second.configs.get(index);
            if (!a.projectId.equals(b.projectId) || !a.presetId.equals(b.presetId) || !a.presetName.equals(b.presetName) || a.enabledSince != b.enabledSince) return false;
        }
        return true;
    }

    /** Deduplication never replaces an existing result or silently changes its source/preset snapshot. */
    public synchronized Task queue(Task task) throws IOException {
        if (task == null || !task.status.equals("queued")) throw new IllegalArgumentException("仅可登记等待处理的项目照片。");
        return locked(() -> {
            State current = readCommitted(); Task previous = current.tasks.get(task.key());
            if (previous != null) {
                state = current; return previous;
            }
            Map<String, Task> tasks = new TreeMap<>(current.tasks); tasks.put(task.key(), task);
            State next = new State(current.configs, tasks); persist(next); state = next; return task;
        });
    }

    public synchronized void update(Task task) throws IOException {
        if (task == null) throw new IllegalArgumentException("项目照片处理记录无效。");
        locked(() -> {
            State current = readCommitted(); Task previous = current.tasks.get(task.key());
            if (previous == null) throw new IOException("项目照片尚未加入处理队列。");
            if (!sameSource(previous, task) || task.updatedAt < previous.updatedAt) throw new IOException("项目照片来源已变化或处理记录已过期，未覆盖现有记录。");
            if (previous.status.equals("saved") && (!task.status.equals("saved") || !previous.resultFileName.equals(task.resultFileName))) throw new IOException("已保存的项目照片结果不能被重新登记。");
            if (same(previous, task)) { state = current; return null; }
            Map<String, Task> tasks = new TreeMap<>(current.tasks); tasks.put(task.key(), task);
            State next = new State(current.configs, tasks); persist(next); state = next; return null;
        });
    }

    /** Oldest update first, with stable identity ordering for ties. No filesystem access. */
    public List<Task> allTasks() { return state.all; }
    public List<Task> tasks(String projectId) {
        project(projectId); List<Task> tasks = state.byProject.get(projectId); return tasks == null ? Collections.emptyList() : tasks;
    }

    private <T> T locked(Locked<T> action) throws IOException {
        synchronized (DISK_LOCK) {
            File lock = owned(".project-presets.lock"); regular(lock);
            try (RandomAccessFile file = new RandomAccessFile(lock, "rw"); FileLock ignored = file.getChannel().lock()) { return action.run(); }
        }
    }
    private State readCommitted() throws IOException {
        File file = owned(FILE_NAME); regular(file);
        if (!exists(file)) {
            if (committedFileSeen) throw new IOException("项目预设记录已丢失，请保留现有数据后重试。");
            return new State(Collections.emptyMap(), Collections.emptyMap());
        }
        State current = decode(file); committedFileSeen = true; return current;
    }
    private void persist(State next) throws IOException {
        File target = owned(FILE_NAME); regular(target); byte[] payload = encode(next);
        File temporary = Files.createTempFile(directory.toPath(), "project-presets-", ".part").toFile();
        try {
            try (FileOutputStream file = new FileOutputStream(temporary); DataOutputStream data = new DataOutputStream(file)) {
                data.writeInt(MAGIC); data.writeInt(payload.length); data.write(payload); data.write(digest().digest(payload)); data.flush(); file.getFD().sync();
            }
            replace.replace(temporary, target); committedFileSeen = true;
        } catch (IOException failure) {
            try { Files.deleteIfExists(temporary.toPath()); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    private File owned(String name) throws IOException {
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) || !directory.equals(directory.getCanonicalFile())) throw new IOException("项目预设目录已变化。");
        File expected = new File(directory, name), actual = expected.getCanonicalFile();
        if (Files.isSymbolicLink(expected.toPath()) || !expected.equals(actual) || !directory.equals(actual.getParentFile())) throw new IOException("项目预设记录指向其他文件。");
        return expected;
    }

    private static State decode(File file) throws IOException {
        long size = file.length(); if (size < 48 || size > MAX_BYTES) throw new IOException("项目预设记录大小无效或超过上限。");
        byte[] encoded = new byte[(int) size];
        try (DataInputStream data = new DataInputStream(new FileInputStream(file))) { data.readFully(encoded); if (data.read() != -1) throw new IOException("项目预设记录在读取时发生变化。"); }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) throw new IOException("项目预设记录版本或格式无效。");
            int length = input.readInt(); if (length != encoded.length - 40) throw new IOException("项目预设记录长度无效。");
            byte[] payload = new byte[length], checksum = new byte[32]; input.readFully(payload); input.readFully(checksum);
            if (!MessageDigest.isEqual(checksum, digest().digest(payload))) throw new IOException("项目预设记录校验失败，请保留记录后重试。");
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload))) {
                Map<String, Config> configs = new TreeMap<>(); Map<String, Task> tasks = new TreeMap<>(); String previous = "";
                int configCount = data.readInt(); if (configCount < 0 || configCount > MAX_CONFIGS) throw new IOException("项目预设配置数量无效。");
                for (int i = 0; i < configCount; i++) {
                    Config config = new Config(data.readUTF(), data.readUTF(), data.readUTF(), data.readLong());
                    if (config.presetId.isEmpty() || config.projectId.compareTo(previous) <= 0) throw new IOException("项目预设配置重复或顺序无效。");
                    configs.put(config.projectId, config); previous = config.projectId;
                }
                int taskCount = data.readInt(); if (taskCount < 0 || taskCount > MAX_TASKS) throw new IOException("项目照片任务数量无效。"); previous = "";
                for (int i = 0; i < taskCount; i++) {
                    Task task = new Task(data.readUTF(), data.readUTF(), data.readUTF(), data.readUTF(), data.readUTF(), data.readUTF(), data.readLong(), data.readUTF(), data.readUTF(), data.readUTF(), data.readLong());
                    if (task.key().compareTo(previous) <= 0) throw new IOException("项目照片任务重复或顺序无效。"); tasks.put(task.key(), task); previous = task.key();
                }
                if (data.read() != -1) throw new IOException("项目预设记录包含多余数据。"); return new State(configs, tasks);
            } catch (IllegalArgumentException invalid) { throw new IOException("项目预设记录包含无效数据。", invalid); }
        }
    }

    private static byte[] encode(State state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(state.configs.size());
            for (Config config : state.configs.values()) { data.writeUTF(config.projectId); data.writeUTF(config.presetId); data.writeUTF(config.presetName); data.writeLong(config.enabledSince); }
            data.writeInt(state.tasks.size());
            for (Task task : state.tasks.values()) {
                data.writeUTF(task.projectId); data.writeUTF(task.identity); data.writeUTF(task.presetId); data.writeUTF(task.presetName); data.writeUTF(task.sourceFileName); data.writeUTF(task.sourceDisplayName);
                data.writeLong(task.sourceBytes); data.writeUTF(task.status); data.writeUTF(task.resultFileName); data.writeUTF(task.error); data.writeLong(task.updatedAt);
                if (bytes.size() > MAX_BYTES - 40) throw new IOException("项目预设记录超过本版保存上限。");
            }
        }
        return bytes.toByteArray();
    }

    private static final class State {
        final Map<String, Config> configs; final Map<String, Task> tasks; final List<Task> all; final Map<String, List<Task>> byProject;
        State(Map<String, Config> configs, Map<String, Task> tasks) throws IOException {
            if (configs.size() > MAX_CONFIGS || tasks.size() > MAX_TASKS) throw new IOException("项目预设配置或处理任务已达到本版上限。");
            this.configs = Collections.unmodifiableMap(new TreeMap<>(configs)); this.tasks = Collections.unmodifiableMap(new TreeMap<>(tasks));
            List<Task> ordered = new ArrayList<>(tasks.values()); ordered.sort(Comparator.comparingLong((Task task) -> task.updatedAt).thenComparing(Task::key));
            all = Collections.unmodifiableList(ordered); Map<String, List<Task>> grouped = new LinkedHashMap<>();
            for (Task task : ordered) grouped.computeIfAbsent(task.projectId, ignored -> new ArrayList<>()).add(task);
            Map<String, List<Task>> immutable = new HashMap<>(); for (var entry : grouped.entrySet()) immutable.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
            byProject = Collections.unmodifiableMap(immutable);
        }
    }
    private static boolean sameSource(Task a, Task b) {
        return a.key().equals(b.key()) && a.presetName.equals(b.presetName) && a.sourceFileName.equals(b.sourceFileName) && a.sourceDisplayName.equals(b.sourceDisplayName) && a.sourceBytes == b.sourceBytes;
    }
    private static boolean same(Task a, Task b) { return sameSource(a, b) && a.status.equals(b.status) && a.resultFileName.equals(b.resultFileName) && a.error.equals(b.error) && a.updatedAt == b.updatedAt; }
    private static void project(String value) { if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException("项目标识无效。"); }
    private static void preset(String value, boolean empty) {
        if (value == null || !(empty && value.isEmpty()) && !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("项目预设或照片标识无效。");
    }
    private static void filename(String value, boolean empty) {
        text(value, 1024, empty);
        if (!value.isEmpty() && (value.equals(".") || value.equals("..") || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0)) throw new IllegalArgumentException("照片必须使用文件名，不能包含路径。");
    }
    private static void text(String value, int limit, boolean empty) {
        if (value == null || !empty && value.trim().isEmpty() || value.length() > limit || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw new IllegalArgumentException("项目预设记录文字无效或过长。");
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) throw new IllegalArgumentException("项目预设记录不能包含控制字符。");
    }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
    private static boolean exists(File file) { return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS); }
    private static void regular(File file) throws IOException { if (exists(file) && !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("项目预设记录不是普通文件。"); }
}

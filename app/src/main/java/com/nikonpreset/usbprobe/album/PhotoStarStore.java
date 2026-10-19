package com.nikonpreset.usbprobe.album;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Construct and mutate on an I/O worker. Readers use an immutable memory snapshot and never wait for disk.
 * Aliases are disjoint member groups, so persisted data cannot contain directed alias chains or cycles.
 */
public final class PhotoStarStore {
    static final String FILE_NAME = "photo-stars.bin";
    private static final int MAGIC = 0x50535431, MAX_KEYS = 100000, MAX_BYTES = 8 * 1024 * 1024;
    private static final Object DISK_LOCK = new Object();
    private final File directory;
    private final AtomicReplace replace;
    private volatile State state;
    private boolean committedFileSeen;

    /** Host fault tests can fail the replacement boundary after the complete temporary file was synced. */
    interface AtomicReplace { void replace(File temporary, File target) throws IOException; }
    private interface Locked<T> { T run() throws IOException; }

    public PhotoStarStore(File directory) throws IOException {
        this(directory, (temporary, target) -> {
            try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { throw new IOException("此存储位置不支持原子保存，星标未更改。", unsupported); }
        });
    }

    PhotoStarStore(File directory, AtomicReplace replace) throws IOException {
        if (directory == null || replace == null) throw new IllegalArgumentException("星标存储目录或写入方式无效。");
        if (Files.isSymbolicLink(directory.toPath())) throw new IOException("星标保存目录不能是符号链接。");
        this.directory = directory.getCanonicalFile(); this.replace = replace;
        if (!this.directory.isDirectory() && !this.directory.mkdirs() && !this.directory.isDirectory()) throw new IOException("无法创建星标保存目录。");
        state = locked(this::readCommitted);
    }

    /** Includes every alias of a starred photo. Previously returned snapshots never change. */
    public Set<String> snapshot() { return state.starred; }

    public boolean isStarred(String key) { checkedKey(key); return state.starred.contains(key); }

    public synchronized void setStarred(String key, boolean starred) throws IOException {
        checkedKey(key);
        locked(() -> {
            State current = readCommitted(); Group previous = current.byKey.get(key);
            if (previous == null && !starred || previous != null && previous.starred == starred) { state = current; return null; }
            List<Group> groups = new ArrayList<>(current.groups);
            if (previous != null) groups.remove(previous);
            if (starred || previous != null && previous.members.size() > 1) {
                groups.add(new Group(previous == null ? Collections.singletonList(key) : previous.members, starred));
            }
            State next = new State(groups); persist(next); state = next; return null;
        });
    }

    /** Either member being starred stars the merged group; subsequent changes through either key agree. */
    public synchronized void bindLocal(String remoteKey, String localKey) throws IOException {
        checkedKey(remoteKey); checkedKey(localKey);
        if (!remoteKey.startsWith("r:") || !localKey.startsWith("f:")) throw new IllegalArgumentException("必须绑定相机照片标识和本机文件标识。");
        locked(() -> {
            State current = readCommitted(); Group remote = current.byKey.get(remoteKey), local = current.byKey.get(localKey);
            if (remote != null && remote == local) { state = current; return null; }
            TreeSet<String> members = new TreeSet<>(); members.add(remoteKey); members.add(localKey);
            if (remote != null) members.addAll(remote.members);
            if (local != null) members.addAll(local.members);
            List<Group> groups = new ArrayList<>(current.groups); groups.remove(remote); groups.remove(local);
            groups.add(new Group(new ArrayList<>(members), remote != null && remote.starred || local != null && local.starred));
            State next = new State(groups); persist(next); state = next; return null;
        });
    }

    public static String remoteKey(String identity) {
        if (identity == null || !hex(identity)) throw new IllegalArgumentException("相机照片标识必须为 64 位小写十六进制。");
        return "r:" + identity;
    }

    /** The app creates unique completed filenames; moving its storage root does not change this identity. */
    public static String localKey(File photo) {
        if (photo == null) throw new IllegalArgumentException("照片文件无效。");
        String name = photo.getName(); byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (name.isEmpty() || name.equals(".") || name.equals("..") || bytes.length > 1024
                || !StandardCharsets.UTF_8.newEncoder().canEncode(name)) throw new IllegalArgumentException("照片文件名无效。");
        for (int i = 0; i < name.length(); i++) if (Character.isISOControl(name.charAt(i))) throw new IllegalArgumentException("照片文件名不能包含控制字符。");
        return "f:" + hex(digest().digest(bytes));
    }

    private <T> T locked(Locked<T> operation) throws IOException {
        synchronized (DISK_LOCK) {
            File lock = owned(".photo-stars.lock"); regularIfPresent(lock);
            try (RandomAccessFile file = new RandomAccessFile(lock, "rw"); FileLock ignored = file.getChannel().lock()) { return operation.run(); }
        }
    }

    private State readCommitted() throws IOException {
        File source = owned(FILE_NAME); regularIfPresent(source);
        if (!exists(source)) {
            if (committedFileSeen) throw new IOException("星标记录已丢失，请保留现有数据后重新打开应用。");
            return new State(Collections.emptyList());
        }
        State result = decode(source); committedFileSeen = true; return result;
    }

    private void persist(State next) throws IOException {
        File target = owned(FILE_NAME); regularIfPresent(target);
        byte[] payload = encode(next);
        File temporary = Files.createTempFile(directory.toPath(), "photo-stars-", ".part").toFile();
        try {
            try (FileOutputStream file = new FileOutputStream(temporary); DataOutputStream data = new DataOutputStream(file)) {
                data.writeInt(MAGIC); data.writeInt(payload.length); data.write(payload); data.write(digest().digest(payload));
                data.flush(); file.getFD().sync();
            }
            // No non-atomic fallback: a failed replacement must retain the last committed snapshot.
            replace.replace(temporary, target); committedFileSeen = true;
        } catch (IOException failure) {
            try { Files.deleteIfExists(temporary.toPath()); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private File owned(String name) throws IOException {
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) || !directory.equals(directory.getCanonicalFile())) throw new IOException("星标保存目录已变化。");
        File expected = new File(directory, name), canonical = expected.getCanonicalFile();
        if (Files.isSymbolicLink(expected.toPath()) || !expected.equals(canonical) || !directory.equals(canonical.getParentFile())) throw new IOException("星标记录指向其他文件。");
        return expected;
    }

    private static State decode(File source) throws IOException {
        long size = source.length();
        if (size < 44 || size > MAX_BYTES) throw new IOException("星标记录大小无效或超过上限。");
        byte[] encoded = new byte[(int) size];
        try (DataInputStream file = new DataInputStream(new FileInputStream(source))) {
            file.readFully(encoded); if (file.read() != -1) throw new IOException("星标记录在读取时发生变化。");
        }
        try (DataInputStream file = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (file.readInt() != MAGIC) throw new IOException("星标记录版本或格式无效。");
            int length = file.readInt();
            if (length != encoded.length - 40) throw new IOException("星标记录长度无效。");
            byte[] payload = new byte[length], checksum = new byte[32]; file.readFully(payload); file.readFully(checksum);
            if (!MessageDigest.isEqual(checksum, digest().digest(payload))) throw new IOException("星标记录校验失败，请保留记录后重试。");
            try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(payload))) {
                int count = data.readInt();
                if (count < 0 || count > MAX_KEYS) throw new IOException("星标分组数量无效。");
                List<Group> groups = new ArrayList<>(); String previousRoot = ""; int total = 0;
                for (int i = 0; i < count; i++) {
                    int flag = data.readUnsignedByte(), members = data.readInt();
                    if (flag > 1 || members < 1 || members > MAX_KEYS - total || flag == 0 && members == 1) throw new IOException("星标别名分组无效。");
                    total += members; List<String> keys = new ArrayList<>(); String previous = "";
                    for (int j = 0; j < members; j++) {
                        byte[] keyBytes = new byte[66]; data.readFully(keyBytes);
                        String key = new String(keyBytes, StandardCharsets.US_ASCII);
                        try { checkedKey(key); } catch (IllegalArgumentException invalid) { throw new IOException("星标照片标识无效。", invalid); }
                        if (key.compareTo(previous) <= 0) throw new IOException("星标别名重复或顺序无效。");
                        previous = key; keys.add(key);
                    }
                    if (keys.get(0).compareTo(previousRoot) <= 0) throw new IOException("星标分组重复或顺序无效。");
                    previousRoot = keys.get(0); groups.add(new Group(keys, flag == 1));
                }
                if (data.read() != -1) throw new IOException("星标记录包含多余数据。");
                return new State(groups);
            }
        }
    }

    private static byte[] encode(State state) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(state.groups.size());
            for (Group group : state.groups) {
                data.writeByte(group.starred ? 1 : 0); data.writeInt(group.members.size());
                for (String key : group.members) data.write(key.getBytes(StandardCharsets.US_ASCII));
            }
        }
        if (bytes.size() > MAX_BYTES - 40) throw new IOException("星标记录超过本版保存上限。");
        return bytes.toByteArray();
    }

    private static final class Group {
        final List<String> members; final boolean starred;
        Group(List<String> members, boolean starred) { this.members = Collections.unmodifiableList(new ArrayList<>(members)); this.starred = starred; }
    }

    private static final class State {
        final List<Group> groups; final Map<String, Group> byKey; final Set<String> starred;
        State(List<Group> records) throws IOException {
            List<Group> ordered = new ArrayList<>(records); ordered.sort(Comparator.comparing(group -> group.members.get(0)));
            Map<String, Group> index = new HashMap<>(); Set<String> selected = new HashSet<>();
            for (Group group : ordered) {
                boolean remote = false, local = false;
                for (String key : group.members) {
                    remote |= key.startsWith("r:"); local |= key.startsWith("f:");
                    if (index.put(key, group) != null) throw new IOException("照片标识出现在多个星标别名分组中。");
                    if (index.size() > MAX_KEYS) throw new IOException("星标照片标识已达到本版上限。");
                    if (group.starred) selected.add(key);
                }
                if (group.members.size() > 1 && (!remote || !local)) throw new IOException("星标别名缺少相机或本机文件标识。");
            }
            groups = Collections.unmodifiableList(ordered); byKey = Collections.unmodifiableMap(index); starred = Collections.unmodifiableSet(selected);
        }
    }

    private static void checkedKey(String key) {
        if (key == null || key.length() != 66 || key.charAt(1) != ':' || key.charAt(0) != 'r' && key.charAt(0) != 'f' || !hex(key.substring(2))) throw new IllegalArgumentException("星标照片标识无效。");
    }
    private static boolean hex(String text) {
        if (text.length() != 64) return false;
        for (int i = 0; i < text.length(); i++) { char c = text.charAt(i); if (!(c >= '0' && c <= '9' || c >= 'a' && c <= 'f')) return false; }
        return true;
    }
    private static String hex(byte[] bytes) {
        char[] text = new char[bytes.length * 2], alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) { text[i * 2] = alphabet[(bytes[i] & 255) >>> 4]; text[i * 2 + 1] = alphabet[bytes[i] & 15]; }
        return new String(text);
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static boolean exists(File file) { return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS); }
    private static void regularIfPresent(File file) throws IOException {
        if (exists(file) && !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("星标记录不是普通文件。");
    }
}

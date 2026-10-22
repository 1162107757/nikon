package cc.huevia.usb;

import cc.huevia.ptp.PtpObjectHandles;
import cc.huevia.ptp.PtpObjectInfo;
import cc.huevia.ptp.PtpResponseException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Bounded, read-only comparison of the camera's standard PTP object views.
 * The owner must supply an already-open session, matching response validation,
 * transaction numbering, per-transaction I/O timeouts and cancellation of I/O.
 * This class never opens a session, reads object contents or sends a write.
 */
public final class PtpDirectorySurvey {
    public static final int MAX_HANDLES = 4096;
    public static final int MAX_METADATA = 128;
    public static final long DEADLINE_MILLIS = 60000L;
    private static final long ROOT = 0xffffffffL;
    private static final int GET_OBJECT_HANDLES = 0x1007;
    private static final int GET_OBJECT_INFO = 0x1008;
    private static final int ASSOCIATION = 0x3001;

    /** Only a fully validated PTP error may be represented as PtpResponseException. */
    public interface Operations {
        byte[] getData(int operation, long... parameters) throws IOException;
    }

    /** Monotonic milliseconds, independent of wall-clock/time-zone changes. */
    public interface Clock {
        long elapsedRealtimeMillis();
    }

    private final Operations operations;
    private final Clock clock;
    private final Result result;
    private final Map<Long, ObjectRecord> metadata = new HashMap<>();
    private boolean started;
    private long startMillis;
    private QueryResult activeQuery;

    public PtpDirectorySurvey(Operations operations, long storageId) {
        this(operations, storageId, () -> System.nanoTime() / 1000000L);
    }

    public PtpDirectorySurvey(Operations operations, long storageId, Clock clock) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (storageId <= 0 || storageId >= ROOT) {
            throw new IllegalArgumentException("One concrete UINT32 storage ID is required");
        }
        result = new Result(storageId);
        result.queries.add(new QueryResult("root", 0, ROOT));
        result.queries.add(new QueryResult("all_associations", ASSOCIATION, 0));
    }

    /** The same partial result survives a failed scan; no error is converted to an empty view. */
    public Result result() { return result; }

    /** One invocation per instance. Limits stop new operations at operation boundaries. */
    public Result scan() throws IOException {
        if (started) throw new IllegalStateException("Directory survey cannot be reused");
        started = true;
        startMillis = clock.elapsedRealtimeMillis();
        try {
            inspect(result.queries.get(0));
            inspect(result.queries.get(1));
            if (result.queries.get(1).status.equals("unsupported")) {
                QueryResult fallback = new QueryResult("all_objects_fallback", 0, 0);
                result.queries.add(fallback);
                inspect(fallback);
            }
            checkBudget();
            result.completed = true;
            return result;
        } catch (IOException error) {
            result.error = message(error);
            if (activeQuery != null) {
                activeQuery.complete = false;
                activeQuery.status = "error";
                activeQuery.error = result.error;
                if (error instanceof PtpResponseException) {
                    activeQuery.responseCode = ((PtpResponseException) error).responseCode();
                }
            }
            throw error;
        } finally {
            updateElapsed();
            summarizeVisiblePaths();
        }
    }

    private void inspect(QueryResult query) throws IOException {
        activeQuery = query;
        query.status = "running";
        query.failedOperation = GET_OBJECT_HANDLES;
        byte[] data;
        try {
            data = read(GET_OBJECT_HANDLES, result.storageId, query.formatCode, query.parentHandle);
        } catch (PtpResponseException error) {
            query.responseCode = error.responseCode();
            if (query.responseCode != 0x2006 && query.responseCode != 0x2014) throw error;
            query.status = "unsupported";
            query.error = message(error);
            query.issues.add("相机明确返回格式或参数不支持；这不是成功返回的空目录列表。");
            checkBudget();
            activeQuery = null;
            return;
        }
        // Read/validate the count before allocating an array. A legitimate large
        // all-object view must be recorded as truncated, not misreported as an
        // empty view or rejected by the general codec's smaller array limit.
        if (data == null || data.length < 4) {
            throw new IOException("GetObjectHandles 返回的计数数据不完整。");
        }
        long handleCount = (data[0] & 0xffL) | ((data[1] & 0xffL) << 8)
                | ((data[2] & 0xffL) << 16) | ((data[3] & 0xffL) << 24);
        if (4L + handleCount * 4L != data.length) {
            throw new IOException("GetObjectHandles 声明的对象数量与数据长度不符。");
        }
        query.handlesRead = true;
        query.handleCount = (int) handleCount;
        // Avoid hundreds of GetObjectInfo transactions on a photo-heavy view.
        // Counts are retained even when no metadata can safely be surveyed.
        if (handleCount > MAX_HANDLES) {
            truncate(query, "返回对象数量超过 " + MAX_HANDLES + " 个 handle 上限；未读取此查询的对象元数据。");
            activeQuery = null;
            return;
        }
        long[] handles = PtpObjectHandles.parse(data);
        Set<Long> unique = new HashSet<>();
        for (long handle : handles) {
            if (handle == 0 || handle == ROOT) {
                throw new IOException("GetObjectHandles 返回无效对象 handle " + hex32(handle) + "。");
            }
            if (!unique.add(handle)) {
                throw new IOException("GetObjectHandles 返回重复对象 handle " + hex32(handle) + "。");
            }
        }
        if (handles.length > MAX_METADATA) {
            truncate(query, "返回对象数量超过 " + MAX_METADATA + " 个元数据上限；未读取此查询的对象元数据。");
            activeQuery = null;
            return;
        }
        for (long handle : handles) {
            checkBudget();
            ObjectRecord record = metadata.get(handle);
            if (record != null) {
                query.cacheHitCount++;
            } else {
                if (result.metadataRequests >= MAX_METADATA) {
                    truncate(query, "已达到所有查询共享的 " + MAX_METADATA + " 次对象元数据请求上限；本视图未完整读取。");
                    activeQuery = null;
                    return;
                }
                query.failedOperation = GET_OBJECT_INFO;
                query.failedHandle = handle;
                result.metadataRequests++;
                PtpObjectInfo info = PtpObjectInfo.parse(read(GET_OBJECT_INFO, handle));
                query.metadataReadCount++;
                record = new ObjectRecord(handle, info);
                if (record.storageId != result.storageId) {
                    // Retain exactly what was returned for diagnosing the mismatch.
                    query.objects.add(record);
                    throw new IOException("GetObjectInfo 返回其它存储卡的对象：handle=" + hex32(handle)
                            + ", storage=" + hex32(record.storageId) + "。");
                }
                metadata.put(handle, record);
            }
            query.objects.add(record);
            if (query.parentHandle == ROOT && !isRootParent(record.parentObject)) {
                query.selectorHonored = false;
                query.issues.add("根目录选择器返回非根对象：handle=" + hex32(handle)
                        + ", parent=" + hex32(record.parentObject) + "。");
            }
            if (query.formatCode != 0 && record.objectFormat != query.formatCode) {
                query.selectorHonored = false;
                query.issues.add("格式选择器返回不匹配对象：handle=" + hex32(handle)
                        + ", format=" + hex16(record.objectFormat) + "。");
            }
        }
        query.failedOperation = 0;
        query.failedHandle = 0;
        query.complete = query.selectorHonored;
        query.status = query.selectorHonored ? "complete" : "selector_mismatch";
        activeQuery = null;
    }

    private byte[] read(int operation, long... parameters) throws IOException {
        checkBudget();
        byte[] data = operations.getData(operation, parameters);
        checkBudget();
        return data;
    }

    private void truncate(QueryResult query, String reason) {
        query.complete = false;
        query.truncated = true;
        query.status = "truncated";
        query.failedOperation = 0;
        query.failedHandle = 0;
        query.issues.add(reason);
    }

    private void checkBudget() throws IOException {
        updateElapsed();
        if (Thread.currentThread().isInterrupted()) {
            result.interrupted = true;
            throw new InterruptedIOException("目录诊断已取消；未发送后续查询。");
        }
        if (result.elapsedMillis >= DEADLINE_MILLIS) {
            result.deadlineExceeded = true;
            throw new IOException("目录诊断达到 60 秒时限；已停止，目录视图可能未完整读取。");
        }
    }

    private void updateElapsed() {
        result.elapsedMillis = Math.max(0L, clock.elapsedRealtimeMillis() - startMillis);
    }

    private void summarizeVisiblePaths() {
        Set<Long> rootListed = new HashSet<>();
        QueryResult rootQuery = result.queries.get(0);
        for (ObjectRecord record : rootQuery.objects) {
            if (record.isGenericDirectory() && isRootParent(record.parentObject)) rootListed.add(record.handle);
        }
        Set<Long> allRootDirectories = new HashSet<>();
        for (QueryResult query : result.queries) {
            for (ObjectRecord record : query.objects) {
                if (record.storageId != result.storageId || !record.isGenericDirectory()) continue;
                if (isRootParent(record.parentObject) && !rootListed.contains(record.handle)
                        && allRootDirectories.add(record.handle)) {
                    result.additionalRootDirectories.add(record);
                }
                if (isRootParent(record.parentObject) && record.name.equals("NIKON")) {
                    result.nikonRootVisible = true;
                    for (ObjectRecord candidate : metadata.values()) {
                        if (candidate.storageId == result.storageId && candidate.isGenericDirectory()
                                && candidate.parentObject == record.handle && candidate.name.equals("CUSTOMPC")) {
                            result.customPcPathVisible = true;
                        }
                    }
                }
            }
        }
    }

    private static boolean isRootParent(long parent) { return parent == 0 || parent == ROOT; }
    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
    private static String hex32(long value) { return String.format(Locale.US, "0x%08X", value); }
    private static String hex16(int value) { return String.format(Locale.US, "0x%04X", value); }

    public static final class Result {
        public final long storageId;
        public final List<QueryResult> queries = new ArrayList<>();
        public final List<ObjectRecord> additionalRootDirectories = new ArrayList<>();
        public int metadataRequests;
        /** All scheduled queries finished; each query separately declares completeness. */
        public boolean completed;
        public boolean deadlineExceeded;
        public boolean interrupted;
        public boolean nikonRootVisible;
        public boolean customPcPathVisible;
        public long elapsedMillis;
        public String error = "";

        private Result(long storageId) { this.storageId = storageId; }

        public String toDisplayText() {
            StringBuilder text = new StringBuilder("存储 ").append(hex32(storageId))
                    .append(" 的只读目录诊断（未请求写入）");
            for (QueryResult query : queries) {
                text.append("\n\n").append(query.label)
                        .append("：GetObjectHandles(storage=").append(hex32(storageId))
                        .append(", format=").append(hex16(query.formatCode))
                        .append(", parent=").append(hex32(query.parentHandle)).append(")")
                        .append("\n状态：").append(query.status)
                        .append("；返回数量：").append(query.handleCount < 0 ? "尚未读取" : query.handleCount)
                        .append("；已完整核对：").append(query.complete ? "是" : "否");
                if (query.responseCode >= 0) text.append("；PTP 响应：").append(hex16(query.responseCode));
                for (ObjectRecord record : query.objects) {
                    text.append("\n  ").append(record.name).append(" [handle=").append(hex32(record.handle))
                            .append(", parent=").append(hex32(record.parentObject))
                            .append(", storage=").append(hex32(record.storageId))
                            .append(", format=").append(hex16(record.objectFormat))
                            .append(", associationType=").append(hex16(record.associationType)).append(']');
                }
                if (!query.error.isEmpty()) text.append("\n错误：").append(query.error);
                for (String issue : query.issues) text.append("\n说明：").append(issue);
            }
            text.append("\n\n已读取对象元数据请求：").append(metadataRequests)
                    .append(" / ").append(MAX_METADATA)
                    .append("；耗时：").append(elapsedMillis).append(" 毫秒")
                    .append("\nUSB 视图已确认 NIKON 根目录：").append(nikonRootVisible ? "是" : "尚未确认")
                    .append("\nUSB 视图已确认 NIKON/CUSTOMPC 路径：").append(customPcPathVisible ? "是" : "尚未确认")
                    .append("\n未见对象不等于存储卡上不存在；本次结果仅描述相机返回的 USB 对象视图。");
            if (!error.isEmpty()) text.append("\n诊断停止：").append(error);
            return text.toString();
        }
    }

    public static final class QueryResult {
        public final String label;
        public final int formatCode;
        public final long parentHandle;
        public String status = "pending";
        public String error = "";
        public int responseCode = -1;
        public int failedOperation;
        public long failedHandle;
        public int handleCount = -1;
        public int metadataReadCount;
        public int cacheHitCount;
        public boolean handlesRead;
        public boolean complete;
        public boolean truncated;
        public boolean selectorHonored = true;
        public final List<ObjectRecord> objects = new ArrayList<>();
        public final List<String> issues = new ArrayList<>();

        private QueryResult(String label, int formatCode, long parentHandle) {
            this.label = label;
            this.formatCode = formatCode;
            this.parentHandle = parentHandle;
        }
    }

    public static final class ObjectRecord {
        public final long handle;
        public final long storageId;
        public final long parentObject;
        public final String name;
        public final int objectFormat;
        public final int associationType;

        private ObjectRecord(long handle, PtpObjectInfo info) {
            this.handle = handle;
            storageId = info.storageId();
            parentObject = info.parentObject();
            name = info.filename();
            objectFormat = info.objectFormat();
            associationType = info.associationType();
        }

        public boolean isGenericDirectory() { return objectFormat == ASSOCIATION && associationType == 1; }
    }
}

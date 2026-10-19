package com.nikonpreset.usbprobe.album;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** Bounded, opt-in connection timing evidence. Never records camera data or exception messages. */
public final class ConnectionTrace {
    static final int MAX_EVENTS = 256;
    static final int MAX_BYTES = 64 * 1024;
    private static final long NORMAL_FLUSH_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final Pattern TOKEN = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern LINE = Pattern.compile("wall_ms=[0-9]+ mono_ms=[0-9]+ event=[A-Z][A-Z0-9_]{0,63}"
            + "(?: conn=[0-9]+)?(?: op=0x[0-9a-f]{4} tx=[0-9]+)?(?: elapsed_ms=[0-9]+)?"
            + "(?: category=(?:EOF|TIMEOUT|IO|CANCEL|EXPLICIT|PROTOCOL))?");
    private static final AtomicLong IDS = new AtomicLong();
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "camera-connection-trace"); thread.setDaemon(true); return thread;
    });
    private static volatile State enabled;

    private ConnectionTrace() { }

    /** Call with an app-owned external-files path. Reads and writes run only on the trace worker. */
    public static synchronized void configure(File file) {
        if (file == null) {
            State previous = enabled; enabled = null;
            if (previous != null) synchronized (previous) {
                previous.ticket++;
                if (previous.pending != null) previous.pending.cancel(false);
                previous.pending = null;
            }
            return;
        }
        File target = file.getAbsoluteFile();
        State previous = enabled;
        if (previous != null && previous.file.equals(target)) return;
        State state = new State(target);
        enabled = state;
        add(state, "TRACE_CONFIGURED", "", true);
        try { WRITER.execute(() -> loadAndWrite(state)); }
        catch (RuntimeException unavailable) { enabled = null; }
    }

    /** Only fixed uppercase event tokens are accepted; arbitrary UI/network text is discarded. */
    public static void mark(String event) {
        State state = enabled;
        if (state == null || event == null || !TOKEN.matcher(event).matches()) return;
        add(state, event, "", true);
    }

    /** Safe bounded text snapshot; performs no file I/O. Empty while tracing is disabled. */
    public static String snapshot() {
        State state = enabled;
        if (state == null) return "";
        synchronized (state) { return joined(state.lines); }
    }

    static long nextConnectionId() { return IDS.incrementAndGet(); }

    static long operationStarted(long connection, int operation, long transaction) {
        long start = System.nanoTime();
        structured("PTP_OP_BEGIN", connection, operation, transaction, -1, null);
        return start;
    }

    static void operationFinished(long connection, int operation, long transaction, long started, Throwable failure) {
        long millis = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
        structured(failure == null ? "PTP_OP_COMPLETE" : "PTP_OP_FAILED", connection, operation, transaction,
                millis, failure == null ? null : category(failure));
    }

    static void pingReplied(long connection, boolean eventChannel) {
        structured(eventChannel ? "PTP_EVENT_PING_REPLY" : "PTP_COMMAND_PING_REPLY", connection, -1, -1, -1, null);
    }

    static void closed(long connection, String category) {
        structured("PTP_CLOSED", connection, -1, -1, -1, category);
    }

    static String category(Throwable failure) {
        if (Thread.currentThread().isInterrupted() || failure instanceof InterruptedException
                || failure instanceof java.io.InterruptedIOException && !(failure instanceof SocketTimeoutException)) return "CANCEL";
        if (failure instanceof SocketTimeoutException) return "TIMEOUT";
        if (failure instanceof EOFException) return "EOF";
        if (failure instanceof com.nikonpreset.usbprobe.ptp.PtpResponseException
                || failure instanceof com.nikonpreset.usbprobe.ptp.PtpProtocolException) return "PROTOCOL";
        return "IO";
    }

    private static void structured(String event, long connection, int operation, long transaction, long elapsed, String category) {
        State state = enabled;
        if (state == null) return;
        StringBuilder fields = new StringBuilder(100).append(" conn=").append(Math.max(0, connection));
        if (operation >= 0) fields.append(" op=0x").append(String.format(Locale.ROOT, "%04x", operation & 0xffff))
                .append(" tx=").append(Math.max(0, transaction));
        if (elapsed >= 0) fields.append(" elapsed_ms=").append(elapsed);
        if (category != null) {
            if (!category.equals("EOF") && !category.equals("TIMEOUT") && !category.equals("IO") && !category.equals("CANCEL")
                    && !category.equals("EXPLICIT") && !category.equals("PROTOCOL")) category = "IO";
            fields.append(" category=").append(category);
        }
        add(state, event, fields.toString(), event.equals("PTP_OP_FAILED") || event.equals("PTP_CLOSED"));
    }

    private static void add(State state, String event, String fields, boolean urgent) {
        // Capture clocks before enqueueing so a slow disk cannot change the observed event order.
        String line = "wall_ms=" + System.currentTimeMillis() + " mono_ms=" + Math.max(0, System.nanoTime() / 1_000_000L)
                + " event=" + event + fields;
        synchronized (state) {
            if (enabled != state) return;
            state.lines.addLast(line);
            while (state.lines.size() > MAX_EVENTS) state.lines.removeFirst();
            state.revision++;
            state.urgent |= urgent;
            if (!state.loaded || state.writing) return;
            scheduleLocked(state, urgent);
        }
    }

    /** One delayed normal batch, or one immediate urgent batch; never one disk task per PTP event. */
    private static void scheduleLocked(State state, boolean urgent) {
        if (enabled != state || state.writing) return;
        if (state.pending != null) {
            if (!urgent) return;
            state.pending.cancel(false); state.pending = null;
        }
        long ticket = ++state.ticket;
        long elapsed = System.nanoTime() - state.lastWrite;
        long delay = urgent || state.lastWrite == 0 ? 0 : Math.max(0, NORMAL_FLUSH_NANOS - elapsed);
        try { state.pending = WRITER.schedule(() -> writeBatch(state, ticket), delay, TimeUnit.NANOSECONDS); }
        catch (RuntimeException unavailable) { state.pending = null; }
    }

    private static void loadAndWrite(State state) {
        List<String> previous = readPrevious(state.file);
        synchronized (state) {
            if (enabled != state) return;
            ArrayDeque<String> merged = new ArrayDeque<>(previous);
            merged.addAll(state.lines);
            while (merged.size() > MAX_EVENTS) merged.removeFirst();
            state.lines.clear(); state.lines.addAll(merged);
            state.loaded = true;
            state.urgent = true;
            scheduleLocked(state, true);
        }
    }

    private static List<String> readPrevious(File file) {
        List<String> lines = new ArrayList<>();
        try {
            if (!file.isFile() || file.length() > MAX_BYTES) return lines;
            try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (bytes.size() + count > MAX_BYTES) return new ArrayList<>();
                    bytes.write(buffer, 0, count);
                }
                for (String line : bytes.toString(StandardCharsets.UTF_8.name()).split("\n")) {
                    if (line.length() <= 240 && LINE.matcher(line).matches()) lines.add(line);
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        return lines;
    }

    private static void writeBatch(State state, long ticket) {
        String text; long revision;
        synchronized (state) {
            if (enabled != state || state.ticket != ticket) return;
            state.pending = null; state.writing = true; state.urgent = false;
            text = joined(state.lines); revision = state.revision;
        }
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_BYTES) return; // The fixed schema also bounds individual entries.
            File parent = state.file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Trace directory unavailable");
            File temporary = new File(parent, state.file.getName() + ".tmp");
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes); output.getFD().sync();
            }
            if (enabled == state) Files.move(temporary.toPath(), state.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            else temporary.delete();
        } catch (IOException | RuntimeException ignored) { /* Diagnostics must never terminate a connection. */ }
        finally {
            synchronized (state) {
                state.lastWrite = System.nanoTime(); state.attemptedRevision = revision; state.writing = false;
                if (enabled == state && revision != state.revision) scheduleLocked(state, state.urgent);
                state.notifyAll();
            }
        }
    }

    private static String joined(Iterable<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) text.append(line).append('\n');
        return text.toString();
    }

    private static final class State {
        final File file;
        final ArrayDeque<String> lines = new ArrayDeque<>();
        long revision, attemptedRevision, lastWrite, ticket;
        boolean loaded, writing, urgent;
        ScheduledFuture<?> pending;
        State(File file) { this.file = file; }
    }
}

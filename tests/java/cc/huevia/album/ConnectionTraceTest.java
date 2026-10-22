package cc.huevia.album;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Trace-only synthetic evidence; never reads an App directory, network, or camera. */
public final class ConnectionTraceTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("huevia-connection-trace-");
        try {
            disabledHasNoOutput(root);
            boundedAndSanitized(root.resolve("bounded.log"));
            normalTrafficIsBatchedAndDirtyTailIsScheduled(root.resolve("batch.log"));
            previousEvidenceSurvivesConfiguration(root.resolve("history.log"));
            failureDoesNotEscape(root.resolve("unwritable.log"));
            categoriesNeverReadExceptionText();
            System.out.println("PASS: " + checks + " bounded connection trace checks");
        } finally {
            ConnectionTrace.configure(null); drain();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void disabledHasNoOutput(Path root) throws Exception {
        ConnectionTrace.configure(null);
        ConnectionTrace.mark("UI_BACKGROUND");
        long started = ConnectionTrace.operationStarted(1, 0x1004, 50);
        ConnectionTrace.operationFinished(1, 0x1004, 50, started, new IOException("private-account-token"));
        ConnectionTrace.closed(1, "IO");
        check(ConnectionTrace.snapshot().isEmpty(), "Unconfigured tracing leaves no memory output");
        drain();
        try (var files = Files.list(root)) { check(files.findAny().isEmpty(), "Unconfigured tracing has no file side effects"); }
    }

    private static void boundedAndSanitized(Path path) throws Exception {
        ConnectionTrace.configure(path.toFile());
        ConnectionTrace.mark("UI_FOREGROUND");
        ConnectionTrace.mark("wifi=private-ssid");
        ConnectionTrace.mark("PRIVATE_SECRET\nUI_BACKGROUND");
        ConnectionTrace.mark("bad_token");
        long started = ConnectionTrace.operationStarted(7, 0x1004, 25);
        ConnectionTrace.operationFinished(7, 0x1004, 25, started, new IOException("private-file.JPG secret-token"));
        ConnectionTrace.pingReplied(7, true);
        ConnectionTrace.closed(7, "PRIVATE_SECRET");
        drain();
        String evidence = Files.readString(path);
        check(evidence.contains("event=UI_FOREGROUND"), "Fixed lifecycle markers are persisted");
        check(evidence.contains("event=PTP_OP_BEGIN conn=7 op=0x1004 tx=25"), "Only operation and transaction numbers are included");
        check(evidence.contains("event=PTP_OP_FAILED conn=7 op=0x1004 tx=25 elapsed_ms="), "Completion records elapsed time");
        check(evidence.contains("event=PTP_EVENT_PING_REPLY conn=7"), "Event pings have a fixed event token");
        check(!evidence.contains("private") && !evidence.contains("secret") && !evidence.contains("PRIVATE_SECRET")
                && !evidence.contains("bad_token"), "Arbitrary marker values and exception messages cannot enter the trace");
        check(evidence.contains("event=PTP_CLOSED conn=7 category=IO"), "Unknown categories become a fixed IO token");
        for (int i = 0; i < 5000; i++) ConnectionTrace.mark("UI_BACKGROUND");
        drain();
        byte[] bytes = Files.readAllBytes(path);
        String[] lines = new String(bytes, StandardCharsets.UTF_8).strip().split("\n");
        check(lines.length == ConnectionTrace.MAX_EVENTS, "The disk ring keeps only the latest 256 events");
        check(bytes.length <= ConnectionTrace.MAX_BYTES, "The disk trace stays within 64 KiB");
        check(!new File(path + ".tmp").exists(), "A completed flush leaves no temporary file");
        long lastMono = -1;
        for (String line : lines) {
            check(line.matches("wall_ms=[0-9]+ mono_ms=[0-9]+ event=UI_BACKGROUND"), "Every ring entry has bounded fixed-schema wall and monotonic clocks");
            long mono = Long.parseLong(line.substring(line.indexOf("mono_ms=") + 8, line.indexOf(" event=")));
            check(mono >= lastMono, "Single-thread marker order preserves monotonic timestamps"); lastMono = mono;
        }
        check(ConnectionTrace.snapshot().equals(new String(bytes, StandardCharsets.UTF_8)), "The memory snapshot matches the drained file");
        ConnectionTrace.configure(null);
        String previous = Files.readString(path);
        ConnectionTrace.mark("UI_FOREGROUND"); drain();
        check(previous.equals(Files.readString(path)), "Disabling prevents subsequent marker writes");
    }

    private static void previousEvidenceSurvivesConfiguration(Path path) throws Exception {
        ConnectionTrace.configure(path.toFile()); ConnectionTrace.mark("NETWORK_LOST"); drain();
        ConnectionTrace.configure(null);
        Files.writeString(path, Files.readString(path) + "private-file.JPG secret-token\n", StandardCharsets.UTF_8);
        ConnectionTrace.configure(path.toFile()); ConnectionTrace.mark("UI_FOREGROUND"); drain();
        String evidence = Files.readString(path);
        check(evidence.contains("event=NETWORK_LOST") && evidence.contains("event=UI_FOREGROUND"), "A new configuration retains prior disconnect evidence");
        check(!evidence.contains("private-file") && !evidence.contains("secret-token"), "Only validated historical trace lines are retained");
        ConnectionTrace.configure(null);
        Files.writeString(path, "x".repeat(ConnectionTrace.MAX_BYTES + 1));
        ConnectionTrace.configure(path.toFile()); ConnectionTrace.mark("UI_BACKGROUND"); drain();
        check(Files.size(path) < 1024 && Files.readString(path).contains("UI_BACKGROUND"), "An oversized prior file is not loaded into memory");
    }

    private static void failureDoesNotEscape(Path path) throws Exception {
        Files.createDirectory(path);
        ConnectionTrace.configure(path.toFile()); ConnectionTrace.mark("UI_BACKGROUND"); drain();
        check(ConnectionTrace.snapshot().contains("UI_BACKGROUND"), "A disk failure preserves in-memory evidence without throwing to its caller");
        Files.deleteIfExists(Path.of(path + ".tmp"));
        ConnectionTrace.configure(null);
    }

    private static void categoriesNeverReadExceptionText() {
        equal("EOF", ConnectionTrace.category(new EOFException("private")));
        equal("TIMEOUT", ConnectionTrace.category(new SocketTimeoutException("private")));
        equal("IO", ConnectionTrace.category(new IOException() {
            @Override public String getMessage() { throw new AssertionError("Do not inspect private messages"); }
        }));
        equal("PROTOCOL", ConnectionTrace.category(new cc.huevia.ptp.PtpResponseException(0x2019, 42)));
        Thread.currentThread().interrupt();
        try { equal("CANCEL", ConnectionTrace.category(new IOException("private"))); }
        finally { Thread.interrupted(); }
    }

    private static void normalTrafficIsBatchedAndDirtyTailIsScheduled(Path path) throws Exception {
        ConnectionTrace.configure(path.toFile()); drain();
        String baseline = Files.readString(path);
        for (int i = 0; i < 5000; i++) {
            long started = ConnectionTrace.operationStarted(8, 0x1004, i);
            ConnectionTrace.operationFinished(8, 0x1004, i, started, null);
            ConnectionTrace.pingReplied(8, true);
        }
        writerBarrier();
        check(Files.readString(path).equals(baseline), "A burst of normal operations and pings schedules one delayed batch without per-event writes");
        long began = System.nanoTime();
        flushForTest("WIFI_LOST");
        check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 5000,
                "An urgent fixed marker preempts the pending ten-second normal batch");
        String urgent = Files.readString(path);
        check(urgent.contains("event=WIFI_LOST") && urgent.contains("tx=4999"), "Urgent publication includes the dirty normal batch");
        long started = ConnectionTrace.operationStarted(8, 0x1004, 7777);
        ConnectionTrace.operationFinished(8, 0x1004, 7777, started, null);
        writerBarrier();
        check(Files.readString(path).equals(urgent), "A normal tail after an urgent flush still observes the ten-second write interval");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        while (System.nanoTime() < deadline && !Files.readString(path).contains("tx=7777")) Thread.sleep(20);
        check(Files.readString(path).contains("tx=7777"), "The final dirty tail is persisted without requiring another event");
        check(!Files.exists(Path.of(path + ".tmp")), "The delayed tail also completes its temporary-file cleanup");
        ConnectionTrace.operationStarted(8, 0x1004, 8888);
        ConnectionTrace.configure(null);
        ConnectionTrace.configure(path.resolveSibling("replacement.log").toFile()); drain();
        check(!Files.readString(path).contains("tx=8888"), "A stale cancelled timer cannot write an obsolete configured state");
    }

    private static void drain() throws Exception { flushForTest("UI_BACKGROUND"); }

    static void flushForTest(String fixedMarker) throws Exception {
        ConnectionTrace.mark(fixedMarker);
        var field = ConnectionTrace.class.getDeclaredField("enabled"); field.setAccessible(true);
        Object state = field.get(null);
        if (state == null) { writerBarrier(); return; }
        var revision = state.getClass().getDeclaredField("revision"); revision.setAccessible(true);
        var attempted = state.getClass().getDeclaredField("attemptedRevision"); attempted.setAccessible(true);
        long expected;
        synchronized (state) { expected = revision.getLong(state); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        synchronized (state) {
            while (attempted.getLong(state) < expected && System.nanoTime() < deadline) state.wait(20);
            if (attempted.getLong(state) < expected) throw new AssertionError("Urgent trace flush did not complete");
        }
    }

    private static void writerBarrier() throws Exception {
        var field = ConnectionTrace.class.getDeclaredField("WRITER"); field.setAccessible(true);
        ((ExecutorService) field.get(null)).submit(() -> { }).get(10, TimeUnit.SECONDS);
    }
    private static void equal(Object expected, Object value) { check(expected.equals(value), "Expected fixed category " + expected); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}

package com.nikonpreset.usbprobe.album;

import android.content.Context;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the production controller's gateway wait, chosen target and cancellation. */
public final class CameraGatewayConnectionHostTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("camera-gateway-controller-");
        try {
            currentGatewayIsResolvedForEveryAttempt(root.resolve("subnets"));
            delayedGatewayWaitsBeforeAnySocket(root.resolve("delayed"));
            missingGatewayDoesNotReuseThePreviousTarget(root.resolve("missing"));
            cancellationWhileWaitingCannotOpenASocket(root.resolve("cancelled"));
            System.out.println("PASS: " + checks + " gateway connection controller checks");
        } finally {
            CameraWifiNetwork.testConnector = null;
            CameraWifiNetwork.testGateway = "";
            CameraWifiNetwork.testGatewaySupplier = null;
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void currentGatewayIsResolvedForEveryAttempt(Path root) throws Exception {
        List<String> targets = refusedTargets();
        try (AlbumController controller = new AlbumController(new Context(root.toFile()))) {
            for (String gateway : List.of("192.168.186.254", "192.168.90.7")) {
                CameraWifiNetwork.testGateway = gateway;
                Result first = new Result(); controller.connectCameraViaGateway(55740, CameraProtocol.FUJIFILM_X_T20, first);
                String error = first.error();
                check(targets.get(targets.size() - 1).equals(gateway + ":55740"), "Each connection attempts the current requested gateway exactly");
                check(controller.connectionAttemptHost().equals(gateway), "Displayed attempted target matches the actual connector target");
                check(error.contains(gateway + ":55740") && error.contains("命令通道连接被拒绝"), "Refused command connections explain the actual target and channel in Chinese");
                check(!controller.isConnecting(), "A refused target ends the current connecting state");
            }
            check(targets.equals(List.of("192.168.186.254:55740", "192.168.90.7:55740")), "New subnets do not reuse old targets or the historical 192.168.0.1 default");
        }
    }

    private static void delayedGatewayWaitsBeforeAnySocket(Path root) throws Exception {
        List<String> targets = refusedTargets();
        AtomicInteger reads = new AtomicInteger();
        CameraWifiNetwork.testGateway = "";
        CameraWifiNetwork.testGatewaySupplier = () -> reads.incrementAndGet() < 4 ? "" : "192.168.90.7";
        try (AlbumController controller = new AlbumController(new Context(root.toFile()))) {
            Result result = new Result(); controller.connectCameraViaGateway(55740, CameraProtocol.FUJIFILM_X_T20, result);
            result.error();
            check(reads.get() >= 4, "Controller tolerates LinkProperties arriving after onAvailable");
            check(targets.equals(List.of("192.168.90.7:55740")), "A delayed route creates only the final resolved target attempt");
            check(controller.connectionAttemptHost().equals("192.168.90.7"), "Delayed route becomes the actual displayed target");
        } finally { CameraWifiNetwork.testGatewaySupplier = null; }
    }

    private static void missingGatewayDoesNotReuseThePreviousTarget(Path root) throws Exception {
        List<String> targets = refusedTargets();
        CameraWifiNetwork.testGateway = "192.168.186.254";
        try (AlbumController controller = new AlbumController(new Context(root.toFile()))) {
            Result previous = new Result(); controller.connectCameraViaGateway(55740, CameraProtocol.FUJIFILM_X_T20, previous); previous.error();
            targets.clear(); CameraWifiNetwork.testGateway = "";
            Result missing = new Result(); long started = System.nanoTime();
            controller.connectCameraViaGateway(55740, CameraProtocol.FUJIFILM_X_T20, missing);
            String error = missing.error(); long elapsed = System.nanoTime() - started;
            check(elapsed >= TimeUnit.MILLISECONDS.toNanos(2500) && elapsed < TimeUnit.SECONDS.toNanos(6), "Missing gateway follows the bounded three-second metadata wait");
            check(targets.isEmpty(), "Missing gateway creates zero socket attempts and cannot reuse the previous target");
            check(controller.connectionAttemptHost().isEmpty(), "Missing metadata clears a previous attempt's displayed host");
            check(error.contains("IPv4 网关") && error.contains("手机"), "Missing gateway produces an actionable Chinese explanation");
            check(!controller.isConnecting() && !controller.hasLiveSession(), "Missing metadata leaves no claimed camera session");
        }
    }

    private static void cancellationWhileWaitingCannotOpenASocket(Path root) throws Exception {
        List<String> targets = refusedTargets();
        CountDownLatch reading = new CountDownLatch(1);
        AtomicReference<String> gateway = new AtomicReference<>("");
        CameraWifiNetwork.testGateway = "";
        CameraWifiNetwork.testGatewaySupplier = () -> { reading.countDown(); return gateway.get(); };
        try (AlbumController controller = new AlbumController(new Context(root.toFile()))) {
            Result result = new Result(); controller.connectCameraViaGateway(55740, CameraProtocol.FUJIFILM_X_T20, result);
            check(reading.await(2, TimeUnit.SECONDS), "Cancellation fixture reaches the metadata wait");
            check(controller.isConnecting() && controller.connectionAttemptHost().isEmpty(), "Waiting for route metadata does not publish an invented target");
            controller.disconnect(); gateway.set("192.168.90.7");
            Field field = AlbumController.class.getDeclaredField("worker"); field.setAccessible(true);
            ((ExecutorService) field.get(controller)).submit(() -> { }).get(2, TimeUnit.SECONDS);
            check(targets.isEmpty(), "A route appearing after cancellation cannot open a socket");
            check(result.callbacks.get() == 0, "Cancelled request suppresses obsolete success/error callbacks");
            check(!controller.isConnecting() && controller.connectionAttemptHost().isEmpty(), "Cancelled metadata wait leaves no current target or connection");
        } finally { CameraWifiNetwork.testGatewaySupplier = null; }
    }

    private static List<String> refusedTargets() {
        List<String> targets = new CopyOnWriteArrayList<>();
        CameraWifiNetwork.testGatewaySupplier = null;
        CameraWifiNetwork.testConnector = (host, port, timeout) -> {
            targets.add(host + ":" + port);
            throw new ConnectException("Synthetic camera refused connection");
        };
        return targets;
    }
    private static final class Result implements AlbumController.Callback<ConnectionInfo> {
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger callbacks = new AtomicInteger();
        volatile String failure;
        public void onSuccess(ConnectionInfo info) { callbacks.incrementAndGet(); finished.countDown(); }
        public void onError(String message) { callbacks.incrementAndGet(); failure = message; finished.countDown(); }
        String error() throws Exception {
            if (!finished.await(6, TimeUnit.SECONDS)) throw new AssertionError("Gateway connection callback timed out");
            if (failure == null) throw new AssertionError("Expected a synthetic refused/missing gateway result");
            return failure;
        }
    }
    private static void check(boolean pass, String detail) { if (!pass) throw new AssertionError(detail); checks++; }
}

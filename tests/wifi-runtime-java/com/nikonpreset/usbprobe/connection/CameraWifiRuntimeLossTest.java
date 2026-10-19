package com.nikonpreset.usbprobe.connection;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import com.nikonpreset.usbprobe.usb.WirelessPictureControl;

public final class CameraWifiRuntimeLossTest {
    private static int checks;
    private static final CameraWifiSession.Listener NO_UI = new CameraWifiSession.Listener() {
        public void onConnected() { }
        public void onFailure(String message) { }
    };
    public static void main(String[] args) throws Exception {
        lossInvalidatesOnlyWirelessHandles();
        initialFailureDoesNotInterruptLocalWork();
        oldGenerationCannotInvalidateNewNetwork();
        System.out.println("PASS: " + checks + " actual runtime Wi-Fi loss checks");
    }
    private static CameraConnectionRuntime runtime(ConnectivityManager manager) throws Exception {
        Handler.reset();
        var constructor = CameraConnectionRuntime.class.getDeclaredConstructor(Context.class); constructor.setAccessible(true);
        return constructor.newInstance(new Context(manager));
    }
    private static void lossInvalidatesOnlyWirelessHandles() throws Exception {
        ConnectivityManager manager = new ConnectivityManager(); CameraConnectionRuntime runtime = runtime(manager);
        try {
            runtime.wifiSession.connect("Synthetic Camera", "", NO_UI); var callback = manager.lastCallback;
            callback.onAvailable(new Network(1)); Handler.drain();
            runtime.album.live = runtime.album.connected = true;
            var wirelessLease = runtime.beginOperation(); var photoLease = runtime.beginPhotoOperation(); var usbLease = runtime.beginOperation();
            WirelessPictureControl owner = new WirelessPictureControl(); wirelessLease.bind(owner);
            check(runtime.hold(wirelessLease, owner, "192.168.1.1", 15740, "Synthetic"), "Fixture owns a prepared wireless handle");
            runtime.photoPresets.active = true; runtime.photoSaver.busy = true; runtime.projectPresets.working = true;
            callback.onLost(new Network(1)); Handler.drain();
            check(runtime.held() == null && !runtime.album.hasLiveSession() && runtime.album.disconnects == 1, "Actual runtime immediately clears prepared/album handles and stops live after Wi-Fi loss");
            check(owner.closes == 1 && owner.releases == 0, "One socket-only close retires a shared owner without issuing CloseSession");
            check(!wirelessLease.isActive() && photoLease.isActive() && usbLease.isActive(), "Only the bound wireless lease is invalidated; photo and USB generations remain valid");
            check(runtime.photoPresets.active && runtime.photoPresets.cancels == 0 && runtime.projectPresets.pauses == 0 && runtime.photoSaver.busy,
                    "Network loss never cancels preset processing, pauses automatic local work or interrupts local saves");
            check(runtime.wifiSession.isRouteUnavailable() && runtime.wifiSession.usesSpecificRoute() && !runtime.wifiSession.isConnected(), "Loss keeps the fail-closed route guard without reconnecting");
            check(runtime.needsService() && runtime.hasPhotoTasks(), "Ongoing local work retains its own foreground service lifetime");
            check(runtime.notice().contains("Wi-Fi") && runtime.notice().contains("已断开"), "Runtime publishes an immediate camera Wi-Fi loss notice");
            long notice = runtime.noticeRevision(); callback.onLost(new Network(1)); callback.onUnavailable(); Handler.drain();
            check(runtime.noticeRevision() == notice && runtime.album.disconnects == 1 && manager.requests == 1, "Duplicate/stale loss does not disconnect twice or automatically reconnect");
            photoLease.close(); usbLease.close();
        } finally { close(runtime); }
    }
    private static void initialFailureDoesNotInterruptLocalWork() throws Exception {
        ConnectivityManager manager = new ConnectivityManager(); CameraConnectionRuntime runtime = runtime(manager);
        try {
            runtime.photoPresets.active = true; runtime.photoSaver.busy = true;
            var local = runtime.beginPhotoOperation();
            runtime.wifiSession.connect("Synthetic Camera", "", NO_UI); manager.lastCallback.onUnavailable(); Handler.drain();
            check(runtime.album.disconnects == 0 && runtime.notice().isEmpty(), "Initial Wi-Fi failure without an old PTP handle performs no spurious camera disconnect");
            check(local.isActive() && runtime.photoPresets.active && runtime.photoSaver.busy && runtime.projectPresets.pauses == 0,
                    "Initial failure preserves all already-running local work");
            check(runtime.wifiSession.isRouteUnavailable(), "Initial failure still blocks accidental fallback to another Wi-Fi"); local.close();
        } finally { close(runtime); }
    }
    private static void oldGenerationCannotInvalidateNewNetwork() throws Exception {
        ConnectivityManager manager = new ConnectivityManager(); CameraConnectionRuntime runtime = runtime(manager);
        try {
            runtime.wifiSession.connect("Synthetic Camera", "", NO_UI); var old = manager.lastCallback; old.onAvailable(new Network(1)); Handler.drain();
            old.onLost(new Network(1));
            runtime.wifiSession.connect("Synthetic Camera", "", NO_UI); manager.lastCallback.onAvailable(new Network(2));
            runtime.album.live = runtime.album.connected = true;
            old.onUnavailable(); Handler.drain();
            check(runtime.wifiSession.isConnected() && runtime.wifiSession.network().equals(new Network(2)) && runtime.album.disconnects == 0,
                    "An old queued generation cannot invalidate a newly connected camera");
        } finally { close(runtime); }
    }
    private static void close(CameraConnectionRuntime runtime) {
        runtime.wifiSession.disconnect(); Handler.drain(); runtime.worker.shutdownNow(); runtime.cameraWorker.shutdownNow();
    }
    private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}

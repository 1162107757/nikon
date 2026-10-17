package com.nikonpreset.usbprobe;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.util.AtomicFile;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.nikonpreset.usbprobe.library.OnlinePreset;
import com.nikonpreset.usbprobe.album.CameraWifiNetwork;
import com.nikonpreset.usbprobe.album.AlbumController;
import com.nikonpreset.usbprobe.album.PtpIpClient;
import com.nikonpreset.usbprobe.connection.CameraConnectionRuntime;
import com.nikonpreset.usbprobe.library.OnlinePresetRepository;
import com.nikonpreset.usbprobe.library.PresetLibrary;
import com.nikonpreset.usbprobe.library.PresetRecord;
import com.nikonpreset.usbprobe.ncp.NcpFile;
import com.nikonpreset.usbprobe.ncp.NcpPictureControlConverter;
import com.nikonpreset.usbprobe.ncp.NcpPreviewProfile;
import com.nikonpreset.usbprobe.picturecontrol.PictureControlData;
import com.nikonpreset.usbprobe.ui.AlbumPanel;
import com.nikonpreset.usbprobe.ui.AppIcon;
import com.nikonpreset.usbprobe.ui.UiPalette;
import com.nikonpreset.usbprobe.ui.GlassBar;
import com.nikonpreset.usbprobe.ui.AppAppearance;
import com.nikonpreset.usbprobe.ui.PageTransitions;
import com.nikonpreset.usbprobe.ui.PresetPreviewRenderer;
import com.nikonpreset.usbprobe.ui.HoldToCompare;
import com.nikonpreset.usbprobe.ui.ToneCurveView;
import com.nikonpreset.usbprobe.usb.UsbPictureControlImport;
import com.nikonpreset.usbprobe.usb.PictureControlImportSession;
import com.nikonpreset.usbprobe.usb.UsbPictureControlProbe;
import com.nikonpreset.usbprobe.usb.UsbProbe;
import com.nikonpreset.usbprobe.usb.WirelessPictureControl;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** Consumer shell. Camera writes remain in the independently guarded transport backends. */
public final class MainActivity extends Activity {
    private static final int PICK_NCP = 100, PICK_SAMPLE = 101, EXPORT_REPORT = 102, PICK_TEMPLATE = 103;
    private static final String USB_PERMISSION = "com.nikonpreset.usbprobe.CONSUMER_USB_PERMISSION";
    private ExecutorService worker;
    private CameraConnectionRuntime connections;
    private CameraConnectionRuntime.OperationLease wirelessReadLease;
    private long connectionNoticeRevision;
    private CameraConnectionRuntime.ImportStatus shownImportStatus;
    private final Runnable connectionObserver = this::syncConnectionState;
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final Set<String> pendingImages = new HashSet<>();
    private final Set<String> failedImages = new HashSet<>();
    private final Map<String, List<PreviewTarget>> imageTargets = new HashMap<>();
    private final Map<String, List<OriginalPreviewTarget>> originalTargets = new HashMap<>();
    private final LruCache<String, NcpPreviewProfile> curveCache = new LruCache<>(8);
    private final Map<String, CurveTarget> pendingCurves = new HashMap<>();
    private UiPalette colors;
    private UsbManager usb;
    private PresetLibrary library;
    private OnlinePresetRepository online;
    private AlbumPanel album;
    private CameraWifiNetwork wifi;
    private PresetPreviewRenderer presetPreviews;
    private HoldToCompare activeComparison;
    private View transitioningContent;
    private String lastRoute = "";
    private int lastRoot;
    private LinearLayout shell, body;
    private TextView title, busyLabel;
    private boolean localTab = true, albumTab, settingsTab, ready, busy, receiverRegistered, importAfterPick;
    private boolean permissionForSettings, settingsReturnToPreset;
    private String preferredTransport = "wifi", usbModel = "";
    private int verifiedUsbId = -1;
    private volatile boolean destroyed;
    private String message = "", progress = "", catalogMessage = "", report = "";
    private String initializationError = "", previewForId = "", detailId = "";
    private boolean catalogLoaded, loadingCatalog, recoveryBlocked;
    private int imageEpoch;
    private OnlinePreset detailOnline;
    private List<OnlinePreset> catalog = new ArrayList<>();
    private PresetRecord selected;
    private byte[] selectedBytes;
    private UsbDevice permissionDevice;
    private boolean permissionRecovery;
    private volatile UsbPictureControlProbe activeProbe;
    private volatile UsbPictureControlImport activeImport;
    private volatile WirelessPictureControl activeWireless;
    private volatile WirelessPictureControl preparedWireless;
    private final AtomicReference<PtpIpClient> pendingWirelessHandoff = new AtomicReference<>();
    private volatile String preparedWirelessHost = "", preparedWirelessModel = "";
    private volatile int preparedWirelessPort;
    private boolean waitingAlbumHandoff;
    private volatile int wirelessScanEpoch;
    private boolean wirelessGuide, wirelessRecovery, wirelessSettingsProbe;
    private String wirelessAddress = "", wirelessPortText = "15740";
    private JSONObject pendingJournal;
    private int deferredRequest;
    private Intent deferredResult;
    private PendingConfirmation confirmation;
    private ImportOutcome importOutcome;

    private static final class CameraConnection {
        final UsbDevice device;
        final String host;
        final int port;
        final WirelessPictureControl wirelessOwner;
        CameraConnection(UsbDevice device) { this.device = device; host = ""; port = 0; wirelessOwner = null; }
        CameraConnection(String host, int port, WirelessPictureControl owner) {
            device = null; this.host = host; this.port = port; wirelessOwner = owner;
        }
        boolean wireless() { return device == null; }
        String label() { return wireless() ? "Wi-Fi · " + host + ":" + port : "USB 数据线"; }
    }

    private static final class ImportSlot {
        final int itemCode;
        final PictureControlImportSession.OverwriteConfirmation overwrite;
        ImportSlot(UsbPictureControlProbe.ItemRecord item) throws IOException {
            itemCode = item.itemCode;
            overwrite = item.empty ? null : new PictureControlImportSession.OverwriteConfirmation(itemCode, item.rawBytes());
        }
        String name() {
            if (overwrite == null) return "空槽";
            String value = overwrite.registrationName().trim();
            return value.isEmpty() ? "未命名预设" : value;
        }
    }

    private static final class PendingConfirmation {
        final CameraConnection connection;
        final String model;
        final PresetRecord preset;
        private final byte[] source;
        final List<ImportSlot> slots = new ArrayList<>();
        int targetIndex, middle = 4, clarity;
        NcpPictureControlConverter.Conversion conversion;
        PendingConfirmation(CameraConnection connection, String model, PresetRecord preset, byte[] source,
                            List<UsbPictureControlProbe.ItemRecord> observedSlots) throws IOException {
            this.connection = connection; this.model = model; this.preset = preset; this.source = source.clone();
            for (UsbPictureControlProbe.ItemRecord item : observedSlots) slots.add(new ImportSlot(item));
            slots.sort((a, b) -> Integer.compare(a.itemCode, b.itemCode));
            for (int i = 0; i < slots.size(); i++) if (slots.get(i).overwrite == null) { targetIndex = i; break; }
        }
        ImportSlot selectedSlot() { return slots.get(targetIndex); }
        int target() { return selectedSlot().itemCode; }
        byte[] sourceBytes() { return source.clone(); }
    }

    private static final class ImportOutcome {
        final String heading, detail, presetName, camera, sourceSha256;
        final int target;
        final boolean verified, uncertain;
        ImportOutcome(String heading, String detail, String presetName, String camera, int target,
                      boolean verified, boolean uncertain) {
            this(heading, detail, presetName, camera, target, verified, uncertain, "");
        }
        ImportOutcome(String heading, String detail, String presetName, String camera, int target,
                      boolean verified, boolean uncertain, String sourceSha256) {
            this.heading = heading; this.detail = detail; this.presetName = presetName;
            this.camera = camera; this.target = target; this.verified = verified; this.uncertain = uncertain;
            this.sourceSha256 = sourceSha256;
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (USB_PERMISSION.equals(intent.getAction())) {
                if (permissionDevice == null || device == null || permissionDevice.getDeviceId() != device.getDeviceId()) return;
                permissionDevice = null;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && usb.hasPermission(device)) {
                    boolean connectOnly = permissionForSettings; permissionForSettings = false;
                    scanSlots(device, permissionRecovery, connectOnly);
                } else { permissionForSettings = false; finishBusy("未获得相机访问权限，请在设置中重新连接并允许访问。"); }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                if (device != null && device.getDeviceId() == verifiedUsbId) { verifiedUsbId = -1; usbModel = ""; }
                UsbPictureControlProbe probe = activeProbe;
                if (probe != null && probe.isProbingDevice(device)) probe.close();
                UsbPictureControlImport importer = activeImport;
                if (importer != null && importer.isProbingDevice(device)) importer.close();
                if (permissionDevice != null && device != null && permissionDevice.getDeviceId() == device.getDeviceId()) {
                    permissionDevice = null; permissionForSettings = false; finishBusy("相机已断开。请连接数据线后重新开始。");
                } else if (!busy) render();
            } else if (!busy) render();
        }
    };

    @Override public void onCreate(Bundle state) {
        AppAppearance.apply(this);
        super.onCreate(state);
        connections = CameraConnectionRuntime.get(this);
        worker = connections.worker;
        colors = new UiPalette(this);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        wifi = new CameraWifiNetwork(this);
        presetPreviews = new PresetPreviewRenderer(this);
        preferredTransport = getPreferences(MODE_PRIVATE).getString("connection-transport", "wifi");
        if (state != null) {
            localTab = state.getBoolean("local", true); albumTab = state.getBoolean("album"); settingsTab = state.getBoolean("settings");
            settingsReturnToPreset = state.getBoolean("settingsReturnToPreset");
            detailId = state.getString("detailId", "");
            previewForId = state.getString("previewForId", ""); importAfterPick = state.getBoolean("importAfterPick");
            wirelessGuide = state.getBoolean("wirelessGuide"); wirelessRecovery = state.getBoolean("wirelessRecovery");
            wirelessSettingsProbe = state.getBoolean("wirelessSettingsProbe");
            wirelessAddress = state.getString("wirelessAddress", ""); wirelessPortText = state.getString("wirelessPort", "15740");
        }
        album = new AlbumPanel(this, new AlbumPanel.Host() {
            @Override public void beforeConnect(Runnable connect) {
                final CameraConnectionRuntime.OperationLease lease;
                try { requestConnectionNotification(); lease = connections.beginOperation(); }
                catch (RuntimeException failure) { album.connectionStartFailed(readable(failure), connect); notice("无法保持后台连接", readable(failure)); return; }
                releasePreparedWireless(preparedWireless);
                connections.cameraWorker.execute(() -> runOnUiThread(() -> {
                    try { if (lease.isActive()) connect.run(); else album.connectionStartFailed("连接已取消，请重新连接。", connect); }
                    finally { lease.close(); }
                }));
            }
            @Override public void onChanged() {
                if (!destroyed) render();
            }
            @Override public void onMessage(String title, String text) { notice(title, text); }
        }, connections.album);
        connections.addObserver(connectionObserver);
        syncConnectionState();
        IntentFilter filter = new IntentFilter(USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, filter);
        receiverRegistered = true;
        render();
        worker.execute(() -> {
            try {
                library = new PresetLibrary(getFilesDir());
                online = new OnlinePresetRepository(getFilesDir(), library);
                synchronized (CameraConnectionRuntime.PERSISTENCE_LOCK) {
                File previous = new File(getFilesDir(), "last-report.json");
                if (previous.isFile()) try { report = readText(new AtomicFile(previous), 4 * 1024 * 1024); }
                catch (IOException e) { message = "最近报告暂时无法读取，本地预设仍可使用。"; }
                AtomicFile journal = journalFile();
                if (journal.getBaseFile().isFile() || new File(journal.getBaseFile().getPath() + ".bak").isFile()) {
                    recoveryBlocked = true;
                    try { pendingJournal = new JSONObject(readText(journal, 32 * 1024)); }
                    catch (Exception e) { message = "上次导入的记录无法读取。请先打开帮助导出报告，勿重复导入。"; }
                }
                }
                onUi(() -> {
                    if (state == null && online.getEndpoint().isEmpty() && !library.list().isEmpty()) localTab = true;
                    ready = true; render();
                    syncConnectionState();
                    if (deferredResult != null) { Intent savedResult = deferredResult; int request = deferredRequest; deferredResult = null; onActivityResult(request, RESULT_OK, savedResult); }
                    else if (!localTab && !albumTab && !settingsTab) loadCatalog(false);
                });
            } catch (Exception e) { onUi(() -> { initializationError = readable(e); ready = true; render(); }); }
        });
    }

    private void render() {
        if (destroyed) return;
        if (activeComparison != null) activeComparison.release();
        activeComparison = null;
        for (List<OriginalPreviewTarget> targets : originalTargets.values()) targets.clear();
        PageTransitions.cancel(transitioningContent);
        AppAppearance.apply(this);
        // Some Android builds require DecorView creation before Window.getInsetsController().
        View decor = getWindow().getDecorView();
        colors = new UiPalette(this); imageEpoch++;
        if (ready && albumTab && !wirelessGuide && album.isImmersive()) {
            getWindow().setStatusBarColor(Color.BLACK); getWindow().setNavigationBarColor(Color.BLACK);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            shell = vertical(); shell.setBackgroundColor(Color.BLACK);
            View viewerContent = album.createView();
            shell.addView(viewerContent, new LinearLayout.LayoutParams(-1, -1));
            setContentView(shell); shell.requestApplyInsets();
            transitionTo(viewerContent);
            if (Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                }
            }
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) controller.show(WindowInsets.Type.systemBars());
        }
        getWindow().setStatusBarColor(colors.surface); getWindow().setNavigationBarColor(colors.surface);
        int system = colors.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(system);
        shell = vertical(); shell.setBackgroundColor(colors.surface); shell.setFitsSystemWindows(false);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        for (List<PreviewTarget> targets : imageTargets.values()) targets.clear();
        // Keep both content and destination navigation readable on tablets and landscape phones.
        LinearLayout pane = vertical(); FrameLayout bounded = new FrameLayout(this);
        shell.addView(bounded, new LinearLayout.LayoutParams(-1, -1));
        int paneWidth = getResources().getConfiguration().screenWidthDp >= 600 ? Math.min(dp(720), getResources().getDisplayMetrics().widthPixels) : -1;
        FrameLayout.LayoutParams paneParams = new FrameLayout.LayoutParams(paneWidth, -1, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bounded.addView(pane, paneParams);
        boolean settingsGuide = settingsTab && album.isConnectionGuide();
        boolean presetPage = !albumTab && !settingsTab;
        boolean back = wirelessGuide || settingsGuide || confirmation != null || importOutcome != null
                || (presetPage && (!detailId.isEmpty() || detailOnline != null)) || (albumTab && album.canGoBack());
        LinearLayout bar = horizontal(); bar.setPadding(dp(back ? 10 : 22), dp(back ? 8 : 17), dp(16), dp(8));
        if (back) bar.addView(iconButton(AppIcon.Kind.BACK, "返回", this::goBack));
        title = text(wirelessGuide ? (wirelessRecovery ? "无线核对预设" : wirelessSettingsProbe ? "无线预设导入" : "读取相机预设")
                : settingsGuide ? "Wi-Fi 无线连接" : settingsTab ? "设置" : albumTab ? album.title()
                : importOutcome != null ? "导入结果" : confirmation != null ? "确认导入"
                : (!detailId.isEmpty() || detailOnline != null ? "预设详情" : "预设库"), back ? 20 : 32, true);
        title.setLetterSpacing(back ? -.015f : -.03f);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (!back && !settingsTab) {
            bar.addView(connectionIndicator(albumTab));
            if (!albumTab) {
                View add = iconButton(AppIcon.Kind.PLUS, "添加预设", () -> pickNcp(false));
                ((ImageView)add).setImageDrawable(new AppIcon(AppIcon.Kind.PLUS, colors.onPrimary, getResources().getDisplayMetrics().density));
                add.setBackground(shape(colors.primary, 24));
                LinearLayout.LayoutParams plus = new LinearLayout.LayoutParams(dp(48), dp(48)); plus.leftMargin = dp(8); bar.addView(add, plus);
            }
        }
        pane.addView(bar);
        if (busy) {
            LinearLayout loading = horizontal(); loading.setPadding(dp(20), dp(10), dp(20), dp(10));
            ProgressBar spinner = new ProgressBar(this); loading.addView(spinner, new LinearLayout.LayoutParams(dp(24), dp(24)));
            busyLabel = text(progress, 14, false); busyLabel.setPadding(dp(12), 0, 0, 0); loading.addView(busyLabel, new LinearLayout.LayoutParams(0, -2, 1)); pane.addView(loading);
        } else busyLabel = null;
        body = vertical(); pane.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!ready) empty(body, "正在准备预设库", "正在读取保存在手机中的预设。", null, null);
        else if (!initializationError.isEmpty()) empty(body, "预设库暂不可用", initializationError, "查看帮助", this::showHelp);
        else if (wirelessGuide) renderWirelessGuide();
        else if (settingsGuide) body.addView(album.createView(), new LinearLayout.LayoutParams(-1, -1));
        else if (settingsTab) renderSettings();
        else if (albumTab) body.addView(album.createView(), new LinearLayout.LayoutParams(-1, -1));
        else if (importOutcome != null) renderImportOutcome();
        else if (confirmation != null) renderConfirmation();
        else if (!detailId.isEmpty() || detailOnline != null) renderDetail();
        else renderHome();
        if (!back && !(albumTab && album.isTransferBusy())) {
            GlassBar material = new GlassBar(this); material.setBackdrop(pane);
            LinearLayout navigation = horizontal(); navigation.setPadding(dp(5), dp(5), dp(5), dp(5));
            nav(navigation, "预设", AppIcon.Kind.PRESETS, presetPage, () -> switchRoot(0));
            nav(navigation, "相册", AppIcon.Kind.ALBUM, albumTab, () -> switchRoot(1));
            nav(navigation, "设置", AppIcon.Kind.SETTINGS, settingsTab, () -> switchRoot(2));
            material.addView(navigation, new FrameLayout.LayoutParams(-1, -2));
            int navWidth = dp(Math.min(360, Math.min(getResources().getConfiguration().screenWidthDp, 720) - 48));
            int navHeight = 68 + Math.round(Math.max(0f, getResources().getConfiguration().fontScale - 1.2f) * 20f);
            FrameLayout.LayoutParams floating = new FrameLayout.LayoutParams(navWidth, dp(navHeight), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            floating.bottomMargin = dp(14); bounded.addView(material, floating);
        }
        setContentView(shell); shell.requestApplyInsets();
        transitionTo(body);
    }

    private String navigationRoute() {
        if (wirelessGuide) return "wireless:" + wirelessSettingsProbe + ":" + wirelessRecovery;
        if (settingsTab) return album.isConnectionGuide() ? "settings:wifi" : "settings";
        if (albumTab) return "album:" + album.navigationKey();
        if (importOutcome != null) return "preset:result";
        if (confirmation != null) return "preset:confirmation";
        if (!detailId.isEmpty() || detailOnline != null) return "preset:detail:" + detailId + (detailOnline == null ? "" : detailOnline.sha256);
        return "preset:" + localTab;
    }

    private void transitionTo(View content) {
        String route = navigationRoute();
        int root = settingsTab ? 2 : albumTab ? 1 : 0;
        if (ready && !lastRoute.isEmpty() && !lastRoute.equals(route)) {
            boolean photo = route.contains("DETAIL") || lastRoute.contains("DETAIL");
            int direction = root != lastRoot ? Integer.signum(root - lastRoot)
                    : (route.startsWith(lastRoute + ":") || route.contains("detail") || route.contains("wifi") || route.startsWith("wireless")) ? 1 : -1;
            PageTransitions.enter(content, photo ? 0 : direction, photo);
        }
        lastRoute = route; lastRoot = root; transitioningContent = content;
    }

    private void renderHome() {
        LinearLayout top = vertical(); top.setPadding(dp(4), 0, dp(4), dp(16));
        TextView intro = text("把喜欢的色彩，带进相机", 12, false); intro.setTextColor(colors.secondary); intro.setPadding(0, 0, 0, dp(17)); top.addView(intro);
        LinearLayout tabs = horizontal();
        sourceTab(tabs, "我的预设", localTab, () -> { localTab = true; render(); });
        sourceTab(tabs, "在线预设", !localTab, () -> { localTab = false; render(); if (!catalogLoaded) loadCatalog(false); });
        top.addView(tabs, new LinearLayout.LayoutParams(dp(216), dp(48)));
        if (recoveryBlocked) {
            TextView warning = text("上次导入的结果需要核对，请先读取相机，避免重复导入。", 14, false);
            warning.setTextColor(colors.error); top.addView(warning);
            top.addView(button("核对上次导入 · 只读", AppIcon.Kind.REFRESH, false, this::recoverImport));
        }
        if (!message.isEmpty()) { TextView note = text(message, 13, false); note.setPadding(0, dp(8), 0, dp(4)); top.addView(note); }
        list = new ListView(this); list.setDivider(null); list.setClipToPadding(false); list.setPadding(dp(18), 0, dp(18), dp(108));
        list.addHeaderView(top, null, false);
        if (!localTab && catalogLoaded && !loadingCatalog && !catalog.isEmpty()) {
            LinearLayout footer = vertical(); footer.addView(button("刷新在线预设", AppIcon.Kind.REFRESH, false, () -> loadCatalog(false))); list.addFooterView(footer, null, false);
        }
        adapter = new PresetAdapter(); list.setAdapter(adapter);
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); refreshPresetRows();
    }

    private View connectionIndicator(boolean forAlbum) {
        boolean connected = currentConnectionReady(forAlbum);
        LinearLayout status = horizontal(); status.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this); dot.setBackground(shape(connected ? colors.success : colors.secondary, 4));
        status.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));
        TextView label = text(connected ? "已连接" : album.isConnecting() ? "连接中" : "未连接", 11, false);
        label.setTextColor(colors.secondary); label.setPadding(dp(6), dp(8), 0, dp(8)); status.addView(label);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return status;
    }

    private UsbDevice verifiedUsb() {
        if (verifiedUsbId < 0 || usb == null) return null;
        for (UsbDevice device : UsbProbe.findNikonDevices(usb))
            if (device.getDeviceId() == verifiedUsbId && usb.hasPermission(device)) return device;
        verifiedUsbId = -1; usbModel = ""; return null;
    }

    private boolean currentConnectionReady(boolean forAlbum) {
        if (forAlbum) return album.isConnected();
        return album.isConnected() || hasPreparedWireless() || verifiedUsb() != null;
    }

    private boolean hasPreparedWireless() {
        WirelessPictureControl owner = preparedWireless;
        return owner != null && owner.isPrepared();
    }

    private void syncConnectionState() {
        if (destroyed || connections == null) return;
        CameraConnectionRuntime.HeldConnection held = connections.held();
        preparedWireless = held == null ? null : held.owner;
        preparedWirelessHost = held == null ? "" : held.host;
        preparedWirelessPort = held == null ? 0 : held.port;
        preparedWirelessModel = held == null ? "" : held.model;
        if (connections.noticeRevision() != connectionNoticeRevision) {
            connectionNoticeRevision = connections.noticeRevision(); message = connections.notice();
            worker.execute(() -> {
                try {
                    String latest = readText(new AtomicFile(new File(getFilesDir(), "last-report.json")), 4 * 1024 * 1024);
                    onUi(() -> report = latest);
                } catch (Exception ignored) { }
            });
        }
        if (ready && !busy) {
            CameraConnectionRuntime.ImportStatus latest = connections.importStatus();
            if (latest != null && latest != shownImportStatus) {
                shownImportStatus = latest;
                importOutcome = new ImportOutcome(latest.heading, latest.detail, latest.presetName, latest.camera,
                        latest.target, latest.verified, latest.uncertain, latest.sourceSha256);
                recoveryBlocked = latest.uncertain;
                if (!recoveryBlocked) pendingJournal = null;
                else if (!latest.pendingJournalJson.isEmpty()) {
                    // The Activity may have initialized before the queued import persisted its journal.
                    // Publish the immutable recovery intent with the outcome, independently of old UI callbacks.
                    try { pendingJournal = new JSONObject(latest.pendingJournalJson); }
                    catch (Exception failure) { message = "上次导入记录无法读取，请先导出报告，勿重复导入。"; }
                }
                confirmation = null; wirelessGuide = false;
            } else if (confirmation != null && !hasPreparedWireless()) {
                PendingConfirmation plan = confirmation; confirmation = null;
                importOutcome = new ImportOutcome("相机连接已断开", "本次尚未写入，请重新连接后读取槽位。",
                        plan.preset.displayName, plan.model, plan.target(), false, false);
            }
            render();
        }
    }

    private void requestConnectionNotification() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 105);
        }
    }

    private void showBackgroundProtection() {
        android.os.PowerManager power = getSystemService(android.os.PowerManager.class);
        boolean unrestricted = power.isIgnoringBatteryOptimizations(getPackageName());
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("后台连接保护")
                .setMessage("连接相机期间会显示常驻通知，并保持后台保活。锁屏时也会继续维护连接，耗电会略有增加。\n\n"
                        + (unrestricted ? "已允许忽略电池优化。" : "允许后台运行可减少系统深度省电对连接的限制。部分手机还需在应用电池设置中选择不限制。"))
                .setNegativeButton("关闭", null).setNeutralButton("显示连接通知", (d, w) -> requestConnectionNotification());
        if (!unrestricted) dialog.setPositiveButton("允许后台运行", (d, w) -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException failure) { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))); }
        });
        dialog.show();
    }

    private void saveTransport() { getPreferences(MODE_PRIVATE).edit().putString("connection-transport", preferredTransport).apply(); }

    private boolean canChangeConnection() {
        if (connections.hasOperations() && !busy) { notice("相机任务进行中", "请等待当前读取或导入完成后继续。"); return false; }
        if (album.isTransferBusy()) { notice("照片正在传输", "请等待当前照片任务结束，再更改相机连接或导入预设。"); return false; }
        return true;
    }

    private void renderSettings() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content = vertical(); content.setPadding(dp(22), 0, dp(22), dp(112)); scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        TextView intro = text("相机连接与应用偏好", 12, false); intro.setTextColor(colors.secondary); intro.setPadding(0, 0, 0, dp(22)); content.addView(intro);
        boolean connected = currentConnectionReady(false);
        boolean wireless = album.isConnected() || hasPreparedWireless();
        LinearLayout camera = vertical(); camera.setPadding(dp(18), dp(18), dp(18), dp(8)); camera.setBackground(shape(colors.card, 26));
        LinearLayout identity = horizontal();
        ImageView glyph = new ImageView(this); glyph.setImageDrawable(new AppIcon(AppIcon.Kind.CAMERA, colors.secondary, getResources().getDisplayMetrics().density));
        glyph.setPadding(dp(10), dp(10), dp(10), dp(10)); glyph.setBackground(shape(colors.surface, 15)); identity.addView(glyph, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout copy = vertical(); copy.setPadding(dp(12), 0, 0, 0);
        TextView eyebrow = text(connected ? "当前相机" : "相机", 11, false); eyebrow.setTextColor(colors.secondary); copy.addView(eyebrow);
        String model = connected ? (wireless ? album.isConnected() ? album.connectionModel() : preparedWirelessModel : usbModel) : "尚未连接相机";
        TextView modelLabel = text(model == null || model.isEmpty() ? "Nikon 相机" : model, 19, true); modelLabel.setPadding(0, dp(4), 0, 0); copy.addView(modelLabel);
        identity.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); camera.addView(identity);
        LinearLayout status = horizontal(); status.setPadding(0, dp(10), 0, dp(8)); status.addView(connectionIndicator(false), new LinearLayout.LayoutParams(0, -2, 1));
        if (connected) { TextView mode = text(wireless ? "Wi-Fi" : "USB", 11, false); mode.setTextColor(colors.secondary); mode.setPadding(dp(9), dp(5), dp(9), dp(5)); mode.setBackground(shape(colors.surface, 16)); status.addView(mode); }
        camera.addView(status); divider(camera);
        LinearLayout footer = horizontal();
        TextView note = text(connected ? (wireless ? album.isConnected() ? "浏览照片与导入预设" : "预设连接已保持" : "USB 用于预设导入") : album.isConnecting() ? "正在验证相机连接…" : "选择下方连接方式", 11, false); note.setTextColor(colors.secondary); note.setPadding(0, dp(12), dp(6), dp(12)); footer.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
        if (connected) {
            TextView disconnect = text("断开连接", 12, false); disconnect.setTextColor(colors.primary); disconnect.setGravity(Gravity.CENTER); disconnect.setMinHeight(dp(48)); disconnect.setPadding(dp(8), 0, 0, 0); disconnect.setFocusable(true); disconnect.setBackground(ripple(colors.card));
            disconnect.setOnClickListener(v -> { if (busy || !canChangeConnection()) return; verifiedUsbId = -1; usbModel = ""; releasePreparedWireless(preparedWireless); album.disconnect(); message = "相机已断开，已下载照片仍可查看。"; render(); }); footer.addView(disconnect);
        }
        camera.addView(footer); content.addView(camera);
        if (settingsReturnToPreset) content.addView(button("返回预设，继续导入", AppIcon.Kind.BACK, false, () -> { settingsReturnToPreset = false; settingsTab = false; albumTab = false; message = ""; render(); }));
        if (!message.isEmpty()) { TextView notice = label(content, message, 13, colors.secondary); notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); }
        settingsHeading(content, "相机连接");
        LinearLayout connections = settingsGroup(content);
        settingsRow(connections, AppIcon.Kind.WIFI, "Wi-Fi 无线连接", "浏览照片、导入预设", connected && wireless ? "使用中" : "", this::openCameraSettings);
        settingsRow(connections, AppIcon.Kind.USB, "USB 数据线连接", "导入预设", connected && !wireless ? "使用中" : "", this::connectUsbSettings);
        settingsHeading(content, "预设导入");
        LinearLayout importing = settingsGroup(content);
        settingsRow(importing, AppIcon.Kind.ADJUST, "导入方式", "支持空槽导入或确认后覆盖", "wifi".equals(preferredTransport) ? "Wi-Fi 无线" : "USB 数据线", this::chooseImportTransport);
        settingsRow(importing, AppIcon.Kind.WIFI, "无线预设导入", "配置地址与只读检测", presetWirelessSummary(), this::openPresetWirelessSettings);
        settingsHeading(content, "应用");
        LinearLayout application = settingsGroup(content);
        settingsRow(application, AppIcon.Kind.ADJUST, "外观", "", AppAppearance.label(this), this::chooseAppearance);
        settingsRow(application, AppIcon.Kind.WIFI, "后台连接保护", "切到后台或锁屏时保持连接", this.connections.needsService() ? "运行中" : "连接后开启", this::showBackgroundProtection);
        settingsRow(application, AppIcon.Kind.ALBUM, "预览模板", "用于预设主图的近似模拟", presetPreviews.templateLabel(), this::choosePreviewTemplate);
        settingsRow(application, AppIcon.Kind.FILE, "在线预设库", "", online.getEndpoint().isEmpty() ? "未配置" : "已配置", this::configureCatalog);
        settingsRow(application, AppIcon.Kind.HELP, "使用帮助", "", "", this::showHelp);
        settingsRow(application, AppIcon.Kind.INFO, "关于", "", "", () -> notice("相机预设", "版本 0.5.5-beta\n\n管理本地预设、模拟色调、连接尼康相机、浏览和下载照片。无线支持范围取决于实际机型与接口。模板模拟为手机近似预览，不代表相机实拍。"));
    }

    private void chooseAppearance() {
        if (busy) return;
        String[] modes = {AppAppearance.SYSTEM, AppAppearance.LIGHT, AppAppearance.DARK};
        String mode = AppAppearance.mode(this); int checked = AppAppearance.LIGHT.equals(mode) ? 1 : AppAppearance.DARK.equals(mode) ? 2 : 0;
        new AlertDialog.Builder(this).setTitle("外观").setSingleChoiceItems(new String[]{"跟随系统", "浅色", "深色"}, checked, (dialog, which) -> {
            AppAppearance.setMode(this, modes[which]); dialog.dismiss(); render();
        }).setNegativeButton("取消", null).show();
    }

    private void choosePreviewTemplate() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("预览模板").setItems(new String[]{"查看当前模板", "从手机选择模板图", "使用内置模板"}, (dialog, which) -> {
            if (which == 0) showTemplateOriginal();
            else if (which == 1) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_TEMPLATE);
            else updatePreviewTemplate(null);
        }).setNegativeButton("取消", null).show();
    }

    private void updatePreviewTemplate(Uri uri) {
        if (busy) return;
        startBusy(uri == null ? "正在恢复内置模板…" : "正在处理模板图…");
        images.execute(() -> {
            try {
                if (uri == null) presetPreviews.resetTemplate(); else presetPreviews.setTemplate(uri);
                onUi(() -> { imageCache.evictAll(); failedImages.clear(); finishBusy("预览模板已更新，预设主图将重新生成。"); });
            } catch (Exception e) { onUi(() -> { finishBusy("模板未更新，原模板仍可使用。"); notice("无法更新模板", readable(e)); }); }
        });
    }

    private void showTemplateOriginal() {
        if (busy) return;
        startBusy("正在读取模板原图…");
        images.execute(() -> {
            try {
                Bitmap bitmap = presetPreviews.originalTemplate();
                onUi(() -> {
                    busy = false; progress = ""; render();
                    ImageView original = new ImageView(this); original.setImageBitmap(bitmap); original.setAdjustViewBounds(true); original.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    original.setContentDescription("当前预览模板原图，未应用预设");
                    new AlertDialog.Builder(this).setTitle("模板原图 · 未应用预设").setView(original).setPositiveButton("关闭", null).show();
                });
            } catch (Exception e) { onUi(() -> { finishBusy("模板原图暂时无法读取。"); notice("无法查看模板", readable(e)); }); }
        });
    }

    private void settingsHeading(LinearLayout parent, String label) {
        TextView heading = text(label, 12, false); heading.setTextColor(colors.secondary); heading.setPadding(dp(12), dp(24), dp(12), dp(9)); parent.addView(heading);
    }

    private LinearLayout settingsGroup(LinearLayout parent) {
        LinearLayout group = vertical(); group.setBackground(shape(colors.card, 22)); group.setClipToOutline(true); parent.addView(group, new LinearLayout.LayoutParams(-1, -2)); return group;
    }

    private void settingsRow(LinearLayout parent, AppIcon.Kind kind, String title, String subtitle, String value, Runnable action) {
        if (parent.getChildCount() > 0) { View line = new View(this); line.setBackgroundColor(colors.outline); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1)); p.leftMargin = dp(58); parent.addView(line, p); }
        LinearLayout row = horizontal(); row.setPadding(dp(14), dp(11), dp(14), dp(11)); row.setMinimumHeight(dp(66)); row.setBackground(ripple(colors.card)); row.setFocusable(true); row.setEnabled(!busy); row.setOnClickListener(v -> { if (!busy) action.run(); });
        ImageView icon = new ImageView(this); icon.setImageDrawable(new AppIcon(kind, kind == AppIcon.Kind.WIFI ? colors.primary : colors.secondary, getResources().getDisplayMetrics().density)); icon.setPadding(dp(7), dp(7), dp(7), dp(7)); icon.setBackground(shape(kind == AppIcon.Kind.WIFI ? colors.primaryContainer : colors.surface, 10)); row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout copy = vertical(); copy.setPadding(dp(11), 0, dp(8), 0); copy.addView(text(title, 14, false));
        if (!subtitle.isEmpty()) { TextView sub = text(subtitle, 11, false); sub.setTextColor(colors.secondary); sub.setPadding(0, dp(3), 0, 0); copy.addView(sub); } row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        if (!value.isEmpty()) { TextView trailing = text(value, 11, false); trailing.setTextColor(colors.secondary); row.addView(trailing); }
        else { ImageView chevron = new ImageView(this); chevron.setImageDrawable(new AppIcon(AppIcon.Kind.CHEVRON, colors.secondary, getResources().getDisplayMetrics().density)); row.addView(chevron, new LinearLayout.LayoutParams(dp(14), dp(14))); }
        row.setContentDescription(title + (subtitle.isEmpty() ? "" : "，" + subtitle) + (value.isEmpty() ? "" : "，" + value)); parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void openCameraSettings() {
        if (busy || !canChangeConnection()) return;
        preferredTransport = "wifi"; saveTransport(); settingsTab = true; albumTab = false; message = "";
        album.openConnectionSettings(); render();
    }

    private void connectUsbSettings() {
        if (busy || album.isConnecting() || !canChangeConnection()) return;
        List<UsbDevice> devices = UsbProbe.findNikonDevices(usb);
        if (devices.isEmpty()) { message = "请开启相机，用支持数据传输的 USB 线连接手机；若手机有 OTG 开关，请先打开。"; render(); return; }
        if (devices.size() == 1) requestUsbSettings(devices.get(0));
        else { String[] names = new String[devices.size()]; for (int i = 0; i < names.length; i++) names[i] = "Nikon USB · " + String.format(Locale.ROOT, "%04X", devices.get(i).getProductId()); new AlertDialog.Builder(this).setTitle("选择相机").setItems(names, (d, which) -> requestUsbSettings(devices.get(which))).setNegativeButton("取消", null).show(); }
    }

    private void requestUsbSettings(UsbDevice device) {
        if (busy || album.isConnecting() || !canChangeConnection()) return;
        releasePreparedWireless(preparedWireless);
        album.disconnect(); verifiedUsbId = -1; usbModel = ""; startBusy("正在读取 USB 相机…");
        if (usb.hasPermission(device)) scanSlots(device, false, true);
        else {
            permissionDevice = device; permissionRecovery = false; permissionForSettings = true;
            int flags = PendingIntent.FLAG_UPDATE_CURRENT; if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            try { usb.requestPermission(device, PendingIntent.getBroadcast(this, device.getDeviceId(), new Intent(USB_PERMISSION).setPackage(getPackageName()), flags)); }
            catch (RuntimeException e) { permissionDevice = null; permissionForSettings = false; finishBusy("无法申请相机访问权限：" + readable(e)); }
        }
    }

    private ListView list;
    private PresetAdapter adapter;
    private final List<Object> visiblePresets = new ArrayList<>();

    private void refreshPresetRows() {
        if (adapter == null || list == null) return;
        visiblePresets.clear();
        if (localTab) {
            visiblePresets.addAll(library.list());
        } else {
            visiblePresets.addAll(catalog);
        }
        adapter.notifyDataSetChanged();
    }

    private final class PresetAdapter extends BaseAdapter {
        @Override public int getCount() { return Math.max(1, visiblePresets.size()); }
        @Override public Object getItem(int position) { return visiblePresets.isEmpty() ? null : visiblePresets.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean isEnabled(int position) { return false; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            if (visiblePresets.isEmpty()) {
                LinearLayout holder = vertical(); holder.setPadding(0, dp(16), 0, dp(20));
                if (localTab) empty(holder, "把喜欢的色彩带在身边", "添加 NCP，自动生成模板效果主图。也可以从在线预设库下载。", "添加本地预设", () -> pickNcp(false));
                else if (loadingCatalog) empty(holder, "正在加载在线预设", "下载后的预设会保存在“我的预设”。", null, null);
                else if (online.getEndpoint().isEmpty()) empty(holder, "在线预设库尚未配置", "在设置中配置预设库地址，即可浏览真实样片并下载。也可以先添加本地 NCP。", "前往设置", () -> switchRoot(2));
                else empty(holder, "暂时没有在线预设", catalogMessage, "重新加载", () -> loadCatalog(false));
                if (!localTab && !loadingCatalog && !online.getEndpoint().isEmpty()) holder.addView(button("查看缓存目录", AppIcon.Kind.PRESETS, false, () -> loadCatalog(true)));
                return holder;
            }
            Object value = visiblePresets.get(position);
            OnlinePreset item = value instanceof OnlinePreset ? (OnlinePreset) value : null;
            PresetRecord record = value instanceof PresetRecord ? (PresetRecord) value : item == null ? null : library.findBySha256(item.sha256);
            LinearLayout row = vertical(); row.setPadding(0, dp(2), 0, dp(16));
            String name = record == null ? item.name : record.displayName;
            FrameLayout photo = preview(record, item, photoHeight(1.68f)); row.addView(photo);
            LinearLayout caption = horizontal(); caption.setPadding(dp(8), dp(10), dp(8), dp(10));
            LinearLayout copy = vertical(); TextView nameView = text(name, 18, true); nameView.setPadding(0, 0, dp(8), dp(4)); copy.addView(nameView);
            boolean downloaded = record != null || library.findBySha256(item.sha256) != null;
            String description = record == null ? item.description : record.description;
            TextView sub = text(record != null ? "模板模拟 · 近似色调预览" : description.isEmpty() ? "在线 NCP · 下载后预览" : description, 12, false); sub.setMaxLines(2); sub.setTextColor(colors.secondary); copy.addView(sub);
            caption.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            Button action = compactButton(downloaded ? "查看" : "下载", downloaded ? null : AppIcon.Kind.DOWNLOAD, false, () -> {
                if (busy) return;
                detailId = record == null ? "" : record.id; detailOnline = item; render();
                if (!downloaded) downloadPreset(item);
            });
            caption.addView(action, new LinearLayout.LayoutParams(-2, -2)); row.addView(caption);
            row.setContentDescription(name + "，" + (downloaded ? "已在本地" : "可下载") + "，查看预设");
            row.setBackground(ripple(colors.card)); row.setClipToOutline(true); row.setFocusable(true); row.setClickable(true);
            row.setOnClickListener(v -> { if (busy) return; detailId = record == null ? "" : record.id; detailOnline = item; render(); });
            return row;
        }
    }

    private void renderDetail() {
        PresetRecord local = detailOnline == null ? library.findBySha256(detailId) : library.findBySha256(detailOnline.sha256);
        if (local == null && detailOnline == null) { detailId = ""; empty(body, "预设已不可用", "请重新从手机添加这份 NCP。", "返回", this::goBack); return; }
        OnlinePreset remote = detailOnline;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); LinearLayout content = vertical();
        content.setPadding(dp(16), dp(4), dp(16), dp(20)); scroll.addView(content); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(preview(local, remote, photoHeight(1.25f), true));
        heading(content, local != null ? local.displayName : remote.name, 24);
        String description = local != null ? local.description : remote.description;
        if (!description.isEmpty()) label(content, description, 15, colors.text);
        if (local != null) {
            LinearLayout.LayoutParams curveLayout = new LinearLayout.LayoutParams(-1, -2);
            curveLayout.topMargin = dp(10); curveLayout.bottomMargin = dp(16);
            content.addView(presetCurve(local), curveLayout);
        }
        informationRow(content, AppIcon.Kind.CHECK, local != null ? "已保存在手机" : "在线预设 · 尚未下载", local != null ? "NCP 预设文件" : "下载后即可导入相机");
        divider(content);
        informationRow(content, AppIcon.Kind.CAMERA, "相机兼容导入", "支持 USB 或 Wi-Fi；可导入空槽，也可确认后覆盖");
        String previewKey = local != null && !local.previewUri.isEmpty() ? local.previewUri : remote == null ? "" : remote.previewUrl;
        if (local == null && !previewKey.isEmpty() && failedImages.contains(previewKey)) content.addView(button("重新加载样片", AppIcon.Kind.REFRESH, false, () -> { failedImages.remove(previewKey); render(); }));
        if (remote != null && !remote.compatibleModels.isEmpty()) label(content, "发布者标注机型：" + String.join("、", remote.compatibleModels) + "。导入前仍会检查文件兼容性。", 12, colors.secondary);
        if (local != null) {
            final String id = local.id;
            if (!previewKey.isEmpty()) content.addView(textLink("查看配套实拍参考", () -> showReferenceSample(previewKey, remote)));
            content.addView(textLink(local.previewUri.isEmpty() ? "添加实拍参考（可选）" : "更换实拍参考", () -> pickSample(id)));
            label(content, local.originalFileName + " · " + local.byteLength + " 字节", 12, colors.secondary);
        }
        TextView sampleNote = label(content, local != null ? "模板模拟根据可解析的曲线、饱和度与色相生成，仅供色调参考；不模拟相机基底、锐化或实拍效果。" : "发布者样片仅作拍摄参考，下载后可查看模板模拟。", 12, colors.secondary); sampleNote.setBackground(shape(colors.container, 14)); sampleNote.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (!message.isEmpty()) label(content, message, 13, colors.secondary);
        LinearLayout footer = vertical(); footer.setPadding(dp(16), dp(4), dp(16), dp(12));
        if (local != null) {
            PresetRecord choice = local;
            footer.addView(button("wifi".equals(preferredTransport) ? "Wi-Fi 无线导入" : "USB 导入相机", AppIcon.Kind.CAMERA, true, () -> beginImport(choice)));
            footer.addView(textLink("更改导入方式", () -> { settingsReturnToPreset = true; settingsTab = true; albumTab = false; render(); }));
            TextView note = label(footer, "导入前检查相机能力与机内槽位", 12, colors.secondary); note.setGravity(Gravity.CENTER);
        } else footer.addView(button("下载到手机", AppIcon.Kind.DOWNLOAD, true, () -> downloadPreset(remote)));
        body.addView(footer);
    }

    private ToneCurveView presetCurve(PresetRecord record) {
        ToneCurveView chart = new ToneCurveView(this);
        String id = record.id;
        chart.setTag(id);
        NcpPreviewProfile cached = curveCache.get(id);
        if (cached != null) { chart.setProfile(cached); return chart; }
        CurveTarget previous = pendingCurves.put(id, new CurveTarget(chart, imageEpoch));
        if (previous != null) return chart;
        images.execute(() -> {
            NcpPreviewProfile profile = null;
            try { profile = NcpPreviewProfile.fromNcp(library.readNcp(id)); }
            catch (Exception | OutOfMemoryError unavailable) { }
            final NcpPreviewProfile result = profile;
            runOnUiThread(() -> {
                CurveTarget target = pendingCurves.remove(id);
                if (destroyed) return;
                if (result != null) curveCache.put(id, result);
                if (target == null || target.epoch != imageEpoch || !id.equals(target.chart.getTag())) return;
                if (result != null) target.chart.setProfile(result);
                else {
                    target.chart.setUnavailable();
                    target.chart.setOnClickListener(v -> render());
                    target.chart.setContentDescription("此预设曲线暂不可用，点击重新读取");
                }
            });
        });
        return chart;
    }

    private static final class CurveTarget {
        final ToneCurveView chart; final int epoch;
        CurveTarget(ToneCurveView chart, int epoch) { this.chart = chart; this.epoch = epoch; }
    }

    private FrameLayout preview(PresetRecord record, OnlinePreset remote, int height) {
        return preview(record, remote, height, false);
    }

    private FrameLayout preview(PresetRecord record, OnlinePreset remote, int height, boolean compare) {
        FrameLayout frame = new FrameLayout(this); frame.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(height)));
        frame.setBackground(shape(colors.container, 26)); frame.setClipToOutline(true);
        TextView placeholder = text("暂无样片", 14, false); placeholder.setGravity(Gravity.CENTER); placeholder.setTextColor(colors.secondary);
        frame.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        boolean simulated = record != null;
        image.setContentDescription(simulated ? "预设模板模拟，非相机实拍" : "预设配套样片");
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        TextView badge = null;
        if (simulated) {
            badge = text("模板模拟", 10, false); badge.setTextColor(Color.WHITE);
            badge.setPadding(dp(8), dp(4), dp(8), dp(4)); badge.setBackground(shape(Color.argb(170, 0, 0, 0), 12));
            FrameLayout.LayoutParams badgePosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            badgePosition.topMargin = dp(8); badgePosition.rightMargin = dp(8); frame.addView(badge, badgePosition);
        }
        String simulationVersion = simulated ? presetPreviews.versionKey() : "";
        String key = simulated ? "sim:" + record.id + ':' + simulationVersion
                : remote == null || remote.previewUrl.isEmpty() ? "" : remote.previewUrl;
        if (key.isEmpty()) { placeholder.setText("暂无配套样片"); return frame; }
        image.setTag(key);
        HoldToCompare comparison = simulated && compare ? new HoldToCompare(frame, image, badge) : null;
        if (comparison != null) {
            activeComparison = comparison;
            prepareComparisonOriginal(comparison, simulationVersion, imageEpoch);
        }
        Bitmap cached = imageCache.get(key);
        if (cached != null) {
            if (comparison == null) image.setImageBitmap(cached); else comparison.setEffect(cached);
            placeholder.setVisibility(View.GONE); return frame;
        }
        if (failedImages.contains(key)) {
            if (comparison != null) comparison.setUnavailable();
            placeholder.setText(simulated ? "模拟暂不可用 · 点按重试" : "样片暂不可用 · 点按重试");
            placeholder.setOnClickListener(v -> { failedImages.remove(key); render(); });
            return frame;
        }
        placeholder.setText(simulated ? "正在模拟预设…" : "正在载入样片…");
        List<PreviewTarget> targets = imageTargets.get(key);
        if (targets == null) { targets = new ArrayList<>(); imageTargets.put(key, targets); }
        targets.add(new PreviewTarget(image, placeholder, imageEpoch, comparison));
        if (pendingImages.contains(key)) return frame;
        // Bound old requests across rapid navigation. Visible rows share one network/decode task per key.
        if (pendingImages.size() >= 32) {
            placeholder.setText(simulated ? "模板模拟 · 点按加载" : "样片 · 点按加载");
            placeholder.setOnClickListener(v -> render());
            return frame;
        }
        pendingImages.add(key);
        images.execute(() -> {
            Bitmap bitmap = null; String error = "", reason = "";
            try {
                if (simulated) {
                    if (!simulationVersion.equals(presetPreviews.versionKey())) throw new IOException("模板已更换。");
                    bitmap = presetPreviews.render(library.readNcp(record.id));
                } else {
                    bitmap = decodePreview(readBounded(Uri.fromFile(online.downloadPreview(remote)), 8 * 1024 * 1024));
                }
            } catch (Exception | OutOfMemoryError e) {
                error = simulated ? "模拟暂不可用 · 点按查看" : "样片暂不可用 · 点按重试";
                reason = e instanceof OutOfMemoryError ? "内存暂时不足，请稍后重试。" : readable(e);
            }
            final Bitmap result = bitmap; final String failure = error, failureReason = reason;
            runOnUiThread(() -> {
                pendingImages.remove(key); List<PreviewTarget> waiting = imageTargets.remove(key);
                if (destroyed) { if (result != null) result.recycle(); return; }
                if (simulated && !simulationVersion.equals(presetPreviews.versionKey())) {
                    // The template changed while this worker ran; never cache or show the obsolete result.
                    if (result != null) result.recycle(); return;
                }
                if (result != null) imageCache.put(key, result); else failedImages.add(key);
                if (waiting != null) for (PreviewTarget target : waiting) if (target.epoch == imageEpoch && key.equals(target.image.getTag())) {
                    if (result != null) {
                        if (target.comparison == null) target.image.setImageBitmap(result); else target.comparison.setEffect(result);
                        target.placeholder.setVisibility(View.GONE);
                    }
                    else {
                        if (target.comparison != null) target.comparison.setUnavailable();
                        target.placeholder.setText(failure);
                        target.placeholder.setOnClickListener(v -> new AlertDialog.Builder(this)
                                .setTitle(simulated ? "无法生成模板模拟" : "无法读取样片").setMessage(failureReason)
                                .setNegativeButton("关闭", null).setPositiveButton("重试", (dialog, which) -> { failedImages.remove(key); render(); }).show());
                    }
                }
            });
        });
        return frame;
    }

    private static final class PreviewTarget {
        final ImageView image; final TextView placeholder; final int epoch;
        final HoldToCompare comparison;
        PreviewTarget(ImageView image, TextView placeholder, int epoch, HoldToCompare comparison) {
            this.image = image; this.placeholder = placeholder; this.epoch = epoch; this.comparison = comparison;
        }
    }

    private void prepareComparisonOriginal(HoldToCompare comparison, String version, int epoch) {
        String key = "original:" + version;
        Bitmap cached = imageCache.get(key);
        if (cached != null) { comparison.setOriginal(cached); return; }
        List<OriginalPreviewTarget> targets = originalTargets.get(key);
        if (targets == null) { targets = new ArrayList<>(); originalTargets.put(key, targets); }
        targets.add(new OriginalPreviewTarget(comparison, epoch));
        if (pendingImages.contains(key)) return;
        pendingImages.add(key);
        images.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (!version.equals(presetPreviews.versionKey())) throw new IOException("模板已更换。");
                bitmap = presetPreviews.originalTemplate();
            } catch (Exception | OutOfMemoryError unavailable) { }
            final Bitmap result = bitmap;
            runOnUiThread(() -> {
                pendingImages.remove(key);
                List<OriginalPreviewTarget> waiting = originalTargets.remove(key);
                if (destroyed || !version.equals(presetPreviews.versionKey())) {
                    if (result != null) result.recycle();
                    return;
                }
                if (result != null) imageCache.put(key, result);
                if (waiting != null) for (OriginalPreviewTarget target : waiting) if (target.epoch == imageEpoch) {
                    if (result != null) target.comparison.setOriginal(result); else target.comparison.setUnavailable();
                }
            });
        });
    }

    private static final class OriginalPreviewTarget {
        final HoldToCompare comparison; final int epoch;
        OriginalPreviewTarget(HoldToCompare comparison, int epoch) { this.comparison = comparison; this.epoch = epoch; }
    }

    private void showReferenceSample(String source, OnlinePreset remote) {
        if (busy) return;
        startBusy("正在读取实拍参考…");
        images.execute(() -> {
            try {
                Uri uri = source.startsWith("https:") && remote != null ? Uri.fromFile(online.downloadPreview(remote)) : Uri.parse(source);
                Bitmap bitmap = decodePreview(readBounded(uri, 8 * 1024 * 1024));
                onUi(() -> {
                    busy = false; progress = ""; render();
                    ImageView view = new ImageView(this); view.setImageBitmap(bitmap); view.setAdjustViewBounds(true); view.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    view.setContentDescription("预设配套实拍参考，与模板模拟分开显示");
                    new AlertDialog.Builder(this).setTitle("实拍参考").setView(view).setPositiveButton("关闭", null).show();
                });
            } catch (Exception e) { onUi(() -> { finishBusy("实拍参考暂不可用。"); notice("无法读取参考图", readable(e)); }); }
        });
    }

    private void loadCatalog(boolean cached) {
        if (!ready || online == null || busy || loadingCatalog) return;
        loadingCatalog = true; if (!albumTab && detailOnline == null && detailId.isEmpty()) render();
        worker.execute(() -> {
            OnlinePresetRepository.CatalogResult result = cached ? online.cachedCatalog() : online.fetchCatalog();
            onUi(() -> { loadingCatalog = false; catalogLoaded = true; catalog = result.presets; catalogMessage = result.message.replace("我的本地", "我的预设");
                render(); });
        });
    }

    private void configureCatalog() {
        if (busy || online == null) return;
        EditText address = new EditText(this); address.setText(online.getEndpoint()); address.setHint("https://…/catalog.json");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); address.setSelectAllOnFocus(true);
        LinearLayout panel = vertical(); panel.setPadding(dp(24), dp(4), dp(24), dp(8)); panel.addView(address);
        label(panel, "请输入统一在线预设库的 HTTPS 地址。留空可关闭在线库，本地预设仍可使用。", 13, colors.secondary);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("在线预设库地址").setView(panel).setNegativeButton("取消", null).setPositiveButton("保存并加载", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = address.getText().toString(); dialog.dismiss(); startBusy("正在保存在线库地址…");
            worker.execute(() -> { try { online.setEndpoint(value); onUi(() -> { busy = false; catalogLoaded = false; catalog = new ArrayList<>(); failedImages.clear(); render(); loadCatalog(false); }); }
                catch (Exception e) { onUi(() -> { finishBusy("地址未保存：" + readable(e)); notice("请检查在线库地址", readable(e)); }); } });
        })); dialog.show();
    }

    private void downloadPreset(OnlinePreset item) {
        if (busy || item == null) return;
        startBusy("正在下载预设…");
        worker.execute(() -> {
            try {
                PresetLibrary.AddResult result = online.download(item, (received, total) -> {
                    if (destroyed || Thread.currentThread().isInterrupted()) throw new IOException("下载已取消。");
                    onUi(() -> { progress = total > 0 ? "正在下载预设 · " + (received * 100 / total) + "%" : "正在下载预设…"; if (busyLabel != null) busyLabel.setText(progress); });
                });
                String sampleWarning = "";
                if (!item.previewUrl.isEmpty()) try { online.downloadPreview(item); } catch (Exception e) { sampleWarning = " 配套样片暂未下载，可稍后重试。"; }
                final String note = (result.added ? "预设已下载到手机，可继续导入相机。" : "这份预设已在本地，无需重复下载。") + sampleWarning;
                onUi(() -> { detailId = result.record.id; finishBusy(note); });
            } catch (Exception e) { onUi(() -> { finishBusy("下载未完成，本地已有预设仍可使用。"); notice("下载未完成", readable(e) + "\n可再次点击下载重试。"); }); }
        });
    }

    private void pickNcp(boolean thenImport) {
        if (busy || !ready) return; importAfterPick = thenImport;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(intent, PICK_NCP);
    }

    private void pickSample(String id) {
        if (busy) return; previewForId = id;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_SAMPLE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == AlbumPanel.REQUEST_EXPORT) { album.onActivityResult(request, result, data); return; }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (!ready && (request == PICK_NCP || request == PICK_SAMPLE)) { deferredRequest = request; deferredResult = data; return; }
        Uri uri = data.getData();
        if (request == PICK_TEMPLATE) { updatePreviewTemplate(uri); return; }
        if (request == PICK_NCP) {
            final boolean shouldImport = importAfterPick; importAfterPick = false; startBusy("正在添加预设…");
            worker.execute(() -> { try {
                PresetLibrary.AddResult added = library.importFile(documentName(uri), readBounded(uri, NcpFile.MAX_BYTES));
                onUi(() -> { busy = false; localTab = true; albumTab = false; settingsTab = false; detailId = added.record.id; detailOnline = null;
                    message = added.added ? "已加入我的预设，正在生成模板效果主图。" : "相同预设已在本地，可直接使用。"; render(); if (shouldImport) beginImport(added.record); });
            } catch (Exception e) { onUi(() -> { finishBusy("预设未添加。"); notice("无法添加这份文件", readable(e)); }); } });
        } else if (request == PICK_SAMPLE) {
            final String id = previewForId; previewForId = ""; startBusy("正在保存配套样片…");
            worker.execute(() -> { try {
                byte[] bytes = readBounded(uri, 8 * 1024 * 1024); Bitmap check = decodePreview(bytes); check.recycle();
                File directory = new File(getFilesDir(), "preset-samples"); if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法保存样片。");
                File target = new File(directory, id + "-" + System.currentTimeMillis() + ".image");
                writeAtomic(new AtomicFile(target), bytes); library.attachPreview(id, target.toURI().toString());
                onUi(() -> { failedImages.clear(); finishBusy("配套样片已保存。请确保所选照片由这款预设实际拍摄。 "); });
            } catch (Exception e) { onUi(() -> { finishBusy("样片未保存，预设仍可使用。"); notice("无法添加样片", readable(e)); }); } });
        } else if (request == EXPORT_REPORT && !report.isEmpty()) {
            final String snapshot = report; startBusy("正在导出报告…"); worker.execute(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IOException("无法写入所选位置。"); output.write(snapshot.getBytes(StandardCharsets.UTF_8)); onUi(() -> finishBusy("报告已导出。"));
                } catch (Exception e) { onUi(() -> { finishBusy("报告导出失败。"); notice("导出失败", readable(e)); }); }
            });
        }
    }

    private void beginImport(PresetRecord record) {
        if (busy || !canChangeConnection()) return;
        if (recoveryBlocked) { notice("请先核对上次导入", "上次操作的结果尚未确认，请在预设首页点击“核对上次导入”，读取实际槽位后再继续。 "); return; }
        selected = record; selectedBytes = null; startBusy("正在检查预设兼容性…");
        worker.execute(() -> { try {
            byte[] bytes = library.readNcp(record.id); NcpPictureControlConverter.convert(bytes, 4, 0);
            onUi(() -> { selectedBytes = bytes; busy = false; chooseConnection(); });
        } catch (Exception e) { onUi(() -> { finishBusy("这份预设暂不能通过当前兼容通道导入。"); notice("暂不支持这份预设", readable(e) + "\n原文件已保留在手机。"); }); } });
    }

    private void recoverImport() {
        if (busy) return;
        if (pendingJournal == null) { notice("导入记录无法读取", "请先通过帮助导出已有报告，并在相机上检查已保存的预设。当前不会发起新写入。 "); return; }
        new AlertDialog.Builder(this).setTitle("核对上次使用的相机")
                .setMessage("请确认当前连接的是上次导入的同一台相机。应用无法区分同型号的不同机身。\n\n接下来只读取相机中的预设，不会写入或覆盖。")
                .setNegativeButton("取消", null).setPositiveButton("已确认，继续核对", (d, w) -> {
                    settingsTab = false; albumTab = false; settingsReturnToPreset = false;
                    if ("wifi".equals(pendingJournal.optString("transport"))) openWirelessGuide(true);
                    else findCamera(true);
                }).show();
    }

    private void chooseConnection() {
        if (!canChangeConnection()) { render(); return; }
        if ("wifi".equals(preferredTransport)) {
            // Photo enumeration is not a prerequisite for the independent Picture Control session.
            if (album.isConnected()) {
                wirelessAddress = album.connectionHost(); wirelessPortText = Integer.toString(album.connectionPort());
            } else if (hasPreparedWireless()) {
                wirelessAddress = preparedWirelessHost; wirelessPortText = Integer.toString(preparedWirelessPort);
            } else {
                wirelessAddress = getPreferences(MODE_PRIVATE).getString("preset-wifi-host", "");
                wirelessPortText = getPreferences(MODE_PRIVATE).getString("preset-wifi-port", "15740");
            }
            try {
                CameraWifiNetwork.validateAddress(wirelessAddress.trim(), Integer.parseInt(wirelessPortText.trim()));
            } catch (Exception e) {
                settingsReturnToPreset = true; settingsTab = true; albumTab = false;
                message = "已保留这份预设。请在“无线预设导入”中配置相机地址并只读检测，再返回继续导入。"; render(); return;
            }
            wirelessRecovery = false; wirelessSettingsProbe = false; wirelessGuide = true;
            confirmation = null; importOutcome = null;
            scanWirelessSlots();
        } else {
            UsbDevice device = verifiedUsb();
            if ("usb".equals(preferredTransport) && device != null) {
                album.disconnect(); requestCamera(device, false);
            } else {
                settingsReturnToPreset = true; settingsTab = true; albumTab = false;
                message = "相机连接已断开。请重新连接后继续导入。"; render();
            }
        }
    }

    private void chooseImportTransport() {
        if (busy || !canChangeConnection()) return;
        String[] choices = {"Wi-Fi 无线", "USB 数据线"};
        new AlertDialog.Builder(this).setTitle("预设导入方式")
                .setSingleChoiceItems(choices, "usb".equals(preferredTransport) ? 1 : 0, (dialog, which) -> {
                    preferredTransport = which == 0 ? "wifi" : "usb"; saveTransport();
                    if (which == 1) releasePreparedWireless(preparedWireless);
                    dialog.dismiss(); message = ""; render();
                }).setNegativeButton("取消", null).show();
    }

    private String presetWirelessSummary() {
        return getPreferences(MODE_PRIVATE).getString("preset-wifi-host", "").isEmpty() ? "未配置" : "已配置";
    }

    private void openPresetWirelessSettings() {
        if (busy || !canChangeConnection()) return;
        settingsTab = true; albumTab = false;
        openWirelessGuide(false, true);
    }

    private void openWirelessGuide(boolean recovery) {
        openWirelessGuide(recovery, false);
    }

    private void openWirelessGuide(boolean recovery, boolean settingsProbe) {
        if (busy || !canChangeConnection()) return;
        if (!recovery && album.isConnected()) {
            wirelessAddress = album.connectionHost(); wirelessPortText = Integer.toString(album.connectionPort());
        }
        wirelessRecovery = recovery; wirelessSettingsProbe = settingsProbe && !recovery;
        wirelessGuide = true; confirmation = null; importOutcome = null;
        if (recovery && pendingJournal != null) {
            wirelessAddress = pendingJournal.optString("wirelessHost", "");
            wirelessPortText = Integer.toString(pendingJournal.optInt("wirelessPort", CameraWifiNetwork.DEFAULT_PORT));
        } else if (wirelessAddress.isEmpty()) {
            wirelessAddress = getPreferences(MODE_PRIVATE).getString("preset-wifi-host", "");
            wirelessPortText = getPreferences(MODE_PRIVATE).getString("preset-wifi-port", "15740");
            if (wirelessAddress.isEmpty()) wirelessAddress = wifi.suggestHost();
        }
        message = ""; render();
    }

    private void renderWirelessGuide() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content = vertical(); content.setPadding(dp(16), dp(4), dp(16), dp(24)); scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        heading(content, wirelessRecovery ? "只读核对上次导入" : wirelessSettingsProbe ? "无线预设导入" : "不用数据线，先连接相机", 24);
        informationRow(content, AppIcon.Kind.CAMERA, "1 · 开启相机 Wi-Fi", "在相机中开启无线连接，查看网络名称与密码，并保持相机开机。");
        informationRow(content, AppIcon.Kind.ALBUM, "2 · 手机连接相机网络", "在系统 Wi-Fi 设置中连接相机。提示无法上网时，选择保持连接，然后返回这里。");
        content.addView(button("打开 Wi-Fi 设置", AppIcon.Kind.HELP, false, () -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)); }
            catch (RuntimeException e) { notice("无法打开设置", "请在手机系统设置中连接相机 Wi-Fi，然后返回。"); }
        }));
        heading(content, "3 · 读取相机能力与槽位", 16);
        label(content, "先只读检查。相机开放无线优化校准接口后，可选择空槽导入，或确认覆盖已有预设。若要求专有配对或未开放接口，可改用 USB。", 14, colors.secondary);
        if (wirelessSettingsProbe || wirelessRecovery) {
            label(content, "相机 IP 地址", 14, colors.text);
            EditText address = new EditText(this); address.setSingleLine(true); address.setText(wirelessAddress);
            address.setHint("相机的 IPv4 地址"); address.setInputType(InputType.TYPE_CLASS_PHONE); address.setEnabled(!busy);
            address.setTextColor(colors.text); address.setHintTextColor(colors.secondary); content.addView(address);
            address.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) { wirelessAddress = s.toString(); }
                public void afterTextChanged(android.text.Editable s) {}
            });
            content.addView(textLink("使用当前 Wi-Fi 网关地址", () -> {
                String suggestion = wifi.suggestHost();
                if (suggestion.isEmpty()) notice("未找到 Wi-Fi 地址", "请先连接相机网络，或按相机显示的信息填写地址。");
                else { wirelessAddress = suggestion; render(); }
            }));
            label(content, "端口", 14, colors.text);
            EditText port = new EditText(this); port.setSingleLine(true); port.setText(wirelessPortText);
            port.setInputType(InputType.TYPE_CLASS_NUMBER); port.setTextColor(colors.text); port.setEnabled(!busy); content.addView(port);
            port.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                public void onTextChanged(CharSequence s, int start, int before, int count) { wirelessPortText = s.toString(); }
                public void afterTextChanged(android.text.Editable s) {}
            });
        } else informationRow(content, AppIcon.Kind.WIFI, "当前相机地址", wirelessAddress + ":" + wirelessPortText + " · 可在设置中更改");
        if (!message.isEmpty()) label(content, message, 14, colors.secondary);
        if (busy) {
            Button cancel = button("取消读取", AppIcon.Kind.BACK, false, this::cancelWirelessScan);
            cancel.setEnabled(true); cancel.setAlpha(1f); cancel.setOnClickListener(v -> cancelWirelessScan()); content.addView(cancel);
        } else content.addView(button(wirelessRecovery ? "只读核对相机" : wirelessSettingsProbe ? "检测预设导入" : "读取能力与槽位", AppIcon.Kind.REFRESH, true, this::scanWirelessSlots));
        if (!wirelessRecovery) content.addView(button("返回设置", AppIcon.Kind.SETTINGS, false, () -> {
            if (!wirelessSettingsProbe) settingsReturnToPreset = true;
            wirelessGuide = false; settingsTab = true; albumTab = false; render();
        }));
    }

    private void cancelWirelessScan() {
        wirelessScanEpoch++;
        stopWirelessHeartbeat();
        if (waitingAlbumHandoff) { waitingAlbumHandoff = false; album.disconnect(); }
        PtpIpClient pending = pendingWirelessHandoff.getAndSet(null);
        if (pending != null) pending.close();
        WirelessPictureControl operation = activeWireless; if (operation != null) operation.close();
        if (preparedWireless == operation) preparedWireless = null;
        connections.forget(operation);
        if (wirelessReadLease != null) { wirelessReadLease.close(); wirelessReadLease = null; }
        finishBusy("已取消读取，未发起相机写入。");
    }

    private void stopWirelessHeartbeat() {
        // Runtime skips heartbeats while an operation lease owns the serial worker.
    }

    private void startWirelessHeartbeat(WirelessPictureControl owner) {
        connections.changed();
    }

    private void releasePreparedWireless(WirelessPictureControl owner) {
        if (owner == null) return;
        CameraConnectionRuntime.OperationLease cleanupLease = null;
        try { cleanupLease = connections.beginOperation(); cleanupLease.bind(owner); }
        catch (RuntimeException ignored) { }
        final CameraConnectionRuntime.OperationLease lease = cleanupLease;
        connections.forget(owner);
        if (preparedWireless == owner) { preparedWireless = null; preparedWirelessHost = ""; preparedWirelessModel = ""; preparedWirelessPort = 0; stopWirelessHeartbeat(); }
        if (activeWireless == owner) activeWireless = null;
        if (worker.isShutdown()) owner.close();
        else connections.cameraWorker.execute(() -> { try { closePreparedQuietly(owner); } finally { if (lease != null) lease.close(); } });
    }

    /** Connection loss after verified storage does not rewrite the historical import outcome. */
    private void saveWirelessConnectionFailure(WirelessPictureControl owner, UsbPictureControlProbe.Result partial) {
        connections.saveConnectionFailure(owner, partial);
    }

    /** Worker only. Releasing the confirmation session never sends a registration. */
    private void closePreparedQuietly(WirelessPictureControl owner) {
        try { if (owner.hasPreparedSession()) owner.releasePrepared(); }
        catch (Exception error) {
            if (error instanceof UsbPictureControlProbe.ProbeException) {
                UsbPictureControlProbe.Result partial = ((UsbPictureControlProbe.ProbeException) error).partialResult;
                if (partial != null) saveWirelessConnectionFailure(owner, partial);
            }
        } finally { owner.close(); }
    }

    private void scanWirelessSlots() {
        if (busy || !canChangeConnection()) return;
        final String host = wirelessAddress.trim(); final int port;
        try { port = Integer.parseInt(wirelessPortText.trim()); CameraWifiNetwork.validateAddress(host, port); }
        catch (Exception e) { notice("请检查相机地址", e instanceof NumberFormatException ? "端口应为 1–65535 的数字。" : readable(e)); return; }
        if (!wirelessRecovery && !wirelessSettingsProbe && (selected == null || selectedBytes == null)) {
            wirelessGuide = false; finishBusy("请重新选择要导入的预设。"); return;
        }
        final boolean recovery = wirelessRecovery, settingsProbe = wirelessSettingsProbe && !wirelessRecovery;
        final int request = ++wirelessScanEpoch;
        final CameraConnectionRuntime.OperationLease lease;
        try { requestConnectionNotification(); lease = connections.beginOperation(); }
        catch (RuntimeException failure) { notice("无法保持后台连接", readable(failure)); return; }
        wirelessReadLease = lease;
        WirelessPictureControl existing = preparedWireless;
        final WirelessPictureControl reuse = !recovery && hasPreparedWireless()
                && host.equals(preparedWirelessHost) && port == preparedWirelessPort ? existing : null;
        if (existing != null && reuse == null) releasePreparedWireless(existing);
        if (reuse != null) stopWirelessHeartbeat();
        message = "";
        startBusy(recovery ? "正在无线只读核对…" : "正在无线读取相机能力与槽位…");
        if (!recovery && reuse == null && album.isConnected()
                && host.equals(album.connectionHost()) && port == album.connectionPort()) {
            waitingAlbumHandoff = true;
            album.transferForPictureControl(new AlbumController.Callback<PtpIpClient>() {
                @Override public void onSuccess(PtpIpClient transferred) {
                    waitingAlbumHandoff = false;
                    if (request != wirelessScanEpoch) { transferred.close(); lease.close(); return; }
                    executeWirelessScan(host, port, request, false, settingsProbe, null, transferred, lease);
                }
                @Override public void onError(String error) {
                    waitingAlbumHandoff = false;
                    lease.close();
                    if (!destroyed && request == wirelessScanEpoch) finishBusy("无线连接移交未完成，未发起写入。\n" + error);
                }
            });
        } else {
            if (album.isConnected() || album.isConnecting()) album.disconnect();
            executeWirelessScan(host, port, request, recovery, settingsProbe, reuse, null, lease);
        }
    }

    private void executeWirelessScan(String host, int port, int request, boolean recovery, boolean settingsProbe,
                                     WirelessPictureControl reuse, PtpIpClient transferred, CameraConnectionRuntime.OperationLease lease) {
        // This holder also owns a queued transfer if shutdownNow discards the worker task.
        if (transferred != null) pendingWirelessHandoff.set(transferred);
        try {
        connections.cameraWorker.execute(() -> {
            WirelessPictureControl operation = null;
            boolean retained = false;
            try {
                if (request != wirelessScanEpoch) return;
                operation = reuse != null ? reuse : new WirelessPictureControl(transferred == null
                        ? wifi.connector(host, port)
                        : (h, p, timeout) -> { throw new IOException("移交连接禁止重新握手。"); });
                activeWireless = operation;
                if (!lease.bind(operation) || request != wirelessScanEpoch) return;
                UsbPictureControlProbe.Result result;
                if (reuse != null) result = operation.refreshPreparedSnapshot(!settingsProbe);
                else if (transferred != null) result = operation.prepareFromHandoff(transferred, host, port, !settingsProbe);
                else if (recovery) result = operation.probe(host, port, wifi.clientGuid());
                else if (settingsProbe) result = operation.prepareReadOnly(host, port, wifi.clientGuid());
                else result = operation.prepareImport(host, port, wifi.clientGuid());
                List<UsbPictureControlProbe.ItemRecord> slots = validateSnapshot(result, recovery ? null : operation);
                if (request != wirelessScanEpoch) return;
                getPreferences(MODE_PRIVATE).edit().putString("preset-wifi-host", host).putString("preset-wifi-port", Integer.toString(port)).apply();
                if (recovery) { completeRecovery(result, slots, request); return; }
                // A read-only recovery may work on cameras that do not expose wireless registration.
                boolean writeAvailable = false; for (int op : result.operations) if (op == 0x90cd) writeAvailable = true;
                String warning = saveReport(result.toJsonString());
                if (!settingsProbe && !writeAvailable) throw new IOException("相机未通过 Wi-Fi 声明预设登记接口（0x90CD）。本次仅完成读取，未写入；请改用 USB 导入。");
                final WirelessPictureControl owner = operation;
                if (!connections.hold(lease, owner, host, port, result.model)) return;
                preparedWireless = owner; preparedWirelessHost = host; preparedWirelessPort = port;
                preparedWirelessModel = result.model; retained = true;
                if (settingsProbe) {
                    final boolean registrationSupported = writeAvailable;
                    int empty = 0; for (UsbPictureControlProbe.ItemRecord item : slots) if (item.empty) empty++;
                    final int emptyCount = empty;
                    getPreferences(MODE_PRIVATE).edit().putString("preset-wifi-model", result.model)
                            .putBoolean("preset-wifi-registration", registrationSupported).apply();
                    onUi(() -> {
                        if (request != wirelessScanEpoch) { releasePreparedWireless(owner); return; }
                        startWirelessHeartbeat(owner);
                        finishBusy("只读检测完成：" + result.model + "，C1–C9 中有 " + emptyCount + " 个空槽。\n"
                                + (registrationSupported ? "连接已保持，可返回选择预设。导入前仍会重新检查，本次没有写入。" : "连接已保持，但相机未声明无线预设登记接口。请改用 USB 导入。")
                                + (warning.isEmpty() ? "" : "\n" + warning));
                    });
                    return;
                }
                onUi(() -> {
                    if (request != wirelessScanEpoch) { releasePreparedWireless(owner); return; }
                    wirelessGuide = false; busy = false; message = warning;
                    chooseImportOptions(new CameraConnection(host, port, owner), result.model, slots);
                });
            } catch (Exception e) {
                if (request != wirelessScanEpoch) return;
                if (e instanceof UsbPictureControlProbe.ProbeException) {
                    UsbPictureControlProbe.Result partial = ((UsbPictureControlProbe.ProbeException) e).partialResult;
                    if (partial != null) saveReport(partial.toJsonString());
                }
                onUi(() -> { if (request == wirelessScanEpoch) finishBusy("无线读取未完成：" + readable(e) + "\n请检查相机 Wi-Fi、地址和配对要求后重试。"); });
            } finally {
                if (!retained) {
                    connections.forget(operation);
                    if (preparedWireless == operation) { preparedWireless = null; stopWirelessHeartbeat(); }
                    if (operation != null) closePreparedQuietly(operation);
                    if (activeWireless == operation) activeWireless = null;
                    if (transferred != null) transferred.close();
                }
                if (transferred != null) pendingWirelessHandoff.compareAndSet(transferred, null);
                lease.close();
                if (wirelessReadLease == lease) wirelessReadLease = null;
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException stopped) {
            if (transferred != null) pendingWirelessHandoff.compareAndSet(transferred, null);
            if (transferred != null) transferred.close();
            if (reuse != null) reuse.close();
            lease.close();
            if (!destroyed) finishBusy("读取已取消，未发起写入。");
        }
    }

    private void findCamera(boolean recovery) {
        if (busy || !canChangeConnection()) return;
        releasePreparedWireless(preparedWireless);
        // Also cancel a wireless connection still being established before opening a USB session.
        album.disconnect();
        List<UsbDevice> devices = UsbProbe.findNikonDevices(usb);
        if (devices.isEmpty()) {
            render();
            new AlertDialog.Builder(this).setTitle("用数据线连接相机")
                    .setMessage("打开相机，用支持数据传输的 USB 线连接手机。\n\n如果手机提供 OTG 开关，请先打开。连接后允许此 App 访问相机，并关闭占用相机的其他应用。")
                    .setNegativeButton("稍后", null).setPositiveButton("已连接，重新检测", (d, w) -> findCamera(recovery)).show(); return;
        }
        if (devices.size() == 1) requestCamera(devices.get(0), recovery);
        else {
            String[] names = new String[devices.size()]; for (int i = 0; i < names.length; i++) names[i] = "Nikon USB · " + String.format(Locale.ROOT, "%04X", devices.get(i).getProductId());
            new AlertDialog.Builder(this).setTitle("选择相机").setItems(names, (d, which) -> requestCamera(devices.get(which), recovery)).setNegativeButton("取消", null).show();
        }
    }

    private void requestCamera(UsbDevice device, boolean recovery) {
        if (busy) return; startBusy(recovery ? "正在核对上次导入…" : "正在读取相机槽位…");
        permissionForSettings = false;
        if (usb.hasPermission(device)) scanSlots(device, recovery);
        else {
            permissionDevice = device; permissionRecovery = recovery;
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            try { usb.requestPermission(device, PendingIntent.getBroadcast(this, device.getDeviceId(), new Intent(USB_PERMISSION).setPackage(getPackageName()), flags)); }
            catch (Exception e) { permissionDevice = null; finishBusy("无法申请相机访问权限：" + readable(e)); }
        }
    }

    private void scanSlots(UsbDevice device, boolean recovery) {
        scanSlots(device, recovery, false);
    }

    private void scanSlots(UsbDevice device, boolean recovery, boolean connectionOnly) {
        UsbPictureControlProbe probe = new UsbPictureControlProbe(); activeProbe = probe;
        worker.execute(() -> {
            try {
                UsbPictureControlProbe.Result result = probe.probe(device, usb);
                List<UsbPictureControlProbe.ItemRecord> slots = validateSnapshot(result);
                if (connectionOnly) {
                    onUi(() -> {
                        boolean attached = false;
                        for (UsbDevice current : UsbProbe.findNikonDevices(usb)) if (current.getDeviceId() == device.getDeviceId() && usb.hasPermission(current)) attached = true;
                        if (attached) { verifiedUsbId = device.getDeviceId(); usbModel = result.model; preferredTransport = "usb"; saveTransport(); }
                        finishBusy(attached ? "USB 相机已连接，可返回预设库导入。" : "相机已断开，请重新连接。");
                    });
                    return;
                }
                if (recovery) { completeRecovery(result, slots, -1); return; }
                String saveWarning = saveReport(result.toJsonString());
                onUi(() -> { busy = false; message = saveWarning; render(); chooseImportOptions(new CameraConnection(device), result.model, slots); });
            } catch (Exception e) {
                if (e instanceof UsbPictureControlProbe.ProbeException) {
                    UsbPictureControlProbe.Result partial = ((UsbPictureControlProbe.ProbeException) e).partialResult;
                    if (partial != null) saveReport(partial.toJsonString());
                }
                onUi(() -> {
                    if (connectionOnly) { verifiedUsbId = -1; usbModel = ""; finishBusy("USB 连接未完成：" + readable(e)); }
                    else if (recovery) {
                        busy = false; confirmation = null;
                        importOutcome = new ImportOutcome("结果仍待核对", readable(e) + "\n\n请关闭其他相机应用，连接上次的相机后重新只读核对。", pendingJournal == null ? "" : pendingJournal.optString("presetName"), pendingJournal == null ? "" : pendingJournal.optString("model"), pendingJournal == null ? -1 : pendingJournal.optInt("targetItem", -1), false, true); render();
                    } else { finishBusy("无法完成相机读取，未开始新的写入。"); notice("请检查相机连接", readable(e) + "\n关闭其他相机应用，重新连接 USB 后再读取。"); }
                });
            } finally { probe.close(); if (activeProbe == probe) activeProbe = null; }
        });
    }

    private List<UsbPictureControlProbe.ItemRecord> validateSnapshot(UsbPictureControlProbe.Result result) throws Exception {
        return validateSnapshot(result, null);
    }

    private List<UsbPictureControlProbe.ItemRecord> validateSnapshot(UsbPictureControlProbe.Result result,
                                                                  WirelessPictureControl owner) throws Exception {
        boolean sessionReady = owner == null ? result.sessionClosed && !result.sessionRetained
                : result.sessionRetained && !result.sessionClosed && owner.isPrepared();
        if (!result.completed || !sessionReady || result.protocolPhaseUnconfirmed) throw new IOException("相机读取或连接状态未确认，请重新连接后读取。");
        List<UsbPictureControlProbe.ItemRecord> slots = new ArrayList<>(); boolean[] seen = new boolean[9];
        for (UsbPictureControlProbe.ItemRecord item : result.items) {
            if (item.itemCode < 201 || item.itemCode > 209) continue;
            if (seen[item.itemCode - 201] || !item.parsed || !sha256(item.rawBytes()).equals(item.sha256)) throw new IOException("槽位记录不完整或校验失败，请重新读取。");
            PictureControlData.parse(item.rawBytes()); seen[item.itemCode - 201] = true; slots.add(item);
        }
        for (boolean present : seen) if (!present) throw new IOException("没有读全相机的九个槽位，请重新读取。");
        return slots;
    }

    private void chooseImportOptions(CameraConnection connection, String model, List<UsbPictureControlProbe.ItemRecord> slots) {
        final PendingConfirmation next;
        try { next = new PendingConfirmation(connection, model, selected, selectedBytes, slots); }
        catch (IOException failure) {
            releasePreparedWireless(connection.wirelessOwner);
            confirmation = null; finishBusy("槽位信息无法核对，本次未写入。"); notice("请重新读取槽位", readable(failure)); return;
        }
        startBusy("正在准备导入信息…");
        worker.execute(() -> {
            try {
                NcpPictureControlConverter.Conversion conversion = NcpPictureControlConverter.convert(next.sourceBytes(), next.middle, next.clarity);
                if (!conversion.sourceSha256().equals(next.preset.sha256)) throw new IOException("文件与已选择的预设不一致，请重新选择。");
                onUi(() -> {
                    if (connection.wireless() && (preparedWireless != connection.wirelessOwner || !connection.wirelessOwner.isPrepared())) {
                        releasePreparedWireless(connection.wirelessOwner);
                        finishBusy("相机连接已断开，请重新读取槽位；本次未写入。"); return;
                    }
                    next.conversion = conversion; confirmation = next; importOutcome = null; busy = false;
                    if (connection.wireless()) startWirelessHeartbeat(connection.wirelessOwner);
                    render();
                });
            } catch (Exception e) { onUi(() -> {
                releasePreparedWireless(connection.wirelessOwner);
                finishBusy("兼容适配未完成，未写入相机。"); notice("无法准备导入", readable(e));
            }); }
        });
    }

    private void renderConfirmation() {
        PendingConfirmation plan = confirmation;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content = vertical(); content.setPadding(dp(16), dp(4), dp(16), dp(24)); scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        heading(content, "将导入的预设", 16);
        LinearLayout summary = horizontal(); summary.setPadding(dp(12), dp(12), dp(12), dp(12)); summary.setBackground(shape(colors.container, 10));
        summary.addView(preview(plan.preset, null, 88), new LinearLayout.LayoutParams(dp(80), dp(88)));
        LinearLayout copy = vertical(); copy.setPadding(dp(14), 0, 0, 0); TextView name = text(plan.preset.displayName, 18, true); copy.addView(name);
        TextView file = text(plan.preset.originalFileName, 12, false); file.setPadding(0, dp(8), 0, 0); file.setTextColor(colors.secondary); copy.addView(file); summary.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2); summaryParams.topMargin = dp(10); summaryParams.bottomMargin = dp(16); content.addView(summary, summaryParams);
        divider(content); heading(content, "连接的相机", 16);
        informationRow(content, AppIcon.Kind.CAMERA, plan.model, plan.connection.label()
                + (plan.connection.wireless() ? " · 会话保持连接，导入前在同一连接复查" : " · 已读取槽位，导入前将重新连接复查"));
        heading(content, "选择存储位置", 16);
        String[] choices = new String[plan.slots.size()];
        for (int i = 0; i < choices.length; i++) {
            ImportSlot slot = plan.slots.get(i);
            choices[i] = slotLabel(slot.itemCode) + " · " + slot.name() + (slot.overwrite == null ? "" : "（已使用）");
        }
        Spinner target = spinner(content, choices, plan.targetIndex); target.setEnabled(!busy);
        target.setContentDescription("选择预设存储槽位");
        TextView slotNote = label(content, "", 12, colors.secondary);
        LinearLayout compatibility = vertical(); compatibility.setPadding(dp(14), dp(6), dp(14), dp(10)); compatibility.setBackground(shape(colors.container, 10));
        LinearLayout compatibilityTitle = horizontal(); TextView compatibilityName = text("兼容适配", 16, true); compatibilityTitle.addView(compatibilityName, new LinearLayout.LayoutParams(0, -2, 1));
        compatibilityTitle.addView(textLink("查看并调整 ›", () -> editCompatibility(plan))); compatibility.addView(compatibilityTitle);
        TextView compatibilityValues = text("中等范围锐化 " + quarter(plan.middle) + " / 清晰度 " + quarter(plan.clarity), 13, false); compatibilityValues.setTextColor(colors.secondary); compatibility.addView(compatibilityValues);
        LinearLayout.LayoutParams compatibilityParams = new LinearLayout.LayoutParams(-1, -2); compatibilityParams.topMargin = dp(16); content.addView(compatibility, compatibilityParams);
        label(content, "兼容适配，效果可能与相机原生载入不同。", 12, colors.secondary);
        LinearLayout footer = vertical(); footer.setPadding(dp(16), dp(4), dp(16), dp(12));
        Button confirm = button("", AppIcon.Kind.CAMERA, true, () -> confirmImport(plan));
        updateImportSlot(plan, slotNote, confirm);
        footer.addView(confirm); TextView footerNote = label(footer, "导入时保持连接，请勿在相机上更改槽位", 12, colors.secondary); footerNote.setGravity(Gravity.CENTER); body.addView(footer);
        target.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!busy && confirmation == plan && position >= 0 && position < plan.slots.size()) {
                    plan.targetIndex = position; updateImportSlot(plan, slotNote, confirm);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void updateImportSlot(PendingConfirmation plan, TextView note, Button confirm) {
        ImportSlot slot = plan.selectedSlot();
        boolean overwrite = slot.overwrite != null;
        note.setText(overwrite ? "将替换「" + slot.name() + "」。原槽位内容会被覆盖。" : "此槽位为空，可直接导入。也可选择已用槽位进行覆盖。");
        note.setTextColor(overwrite ? colors.error : colors.secondary);
        confirm.setText(overwrite ? "覆盖 " + slotLabel(slot.itemCode) + "…" : "导入到 " + slotLabel(slot.itemCode));
    }

    private void confirmImport(PendingConfirmation plan) {
        if (busy || confirmation != plan || plan.conversion == null) return;
        final ImportSlot slot = plan.selectedSlot();
        Runnable write = () -> {
            if (busy || confirmation != plan || plan.selectedSlot() != slot || plan.conversion == null) return;
            writePreset(plan.connection, plan.model, plan.preset, plan.sourceBytes(), slot.itemCode,
                    plan.middle, plan.clarity, plan.conversion.dataSha256(), slot.overwrite);
        };
        if (slot.overwrite == null) { write.run(); return; }
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("覆盖 " + slotLabel(slot.itemCode) + "？")
                .setMessage("将「" + slot.name() + "」替换为「" + plan.preset.displayName + "」。\n\n原槽位内容将被覆盖。写入前会再次核对，若槽位内容已变化，本次导入会停止。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认覆盖 " + slotLabel(slot.itemCode), (d, w) -> write.run()).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(colors.error);
    }

    private void editCompatibility(PendingConfirmation plan) {
        if (busy || confirmation != plan || plan.conversion == null) return;
        LinearLayout content = vertical(); content.setPadding(dp(24), dp(4), dp(24), dp(16));
        label(content, "原 NCP 没有以下两项参数，可根据需要调整兼容适配值。", 14, colors.text);
        label(content, "中等范围锐化", 14, colors.text); Spinner middle = spinner(content, quarters(0, 20), plan.middle);
        label(content, "清晰度", 14, colors.text); Spinner clarity = spinner(content, quarters(-20, 20), plan.clarity + 20);
        heading(content, "当前完整参数说明", 16); label(content, plan.conversion.toDisplayText(), 13, colors.secondary);
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("查看并调整兼容设置").setView(scroll).setNegativeButton("取消", null)
                .setPositiveButton("保存设置", (d, w) -> {
                    final int selectedMiddle = middle.getSelectedItemPosition(), selectedClarity = clarity.getSelectedItemPosition() - 20;
                    startBusy("正在更新兼容设置…");
                    worker.execute(() -> { try {
                        NcpPictureControlConverter.Conversion next = NcpPictureControlConverter.convert(plan.sourceBytes(), selectedMiddle, selectedClarity);
                        if (!next.sourceSha256().equals(plan.preset.sha256)) throw new IOException("源预设校验失败。");
                        onUi(() -> { if (confirmation == plan) { plan.middle = selectedMiddle; plan.clarity = selectedClarity; plan.conversion = next; } finishBusy(""); });
                    } catch (Exception e) { onUi(() -> { finishBusy(""); notice("设置未保存", readable(e)); }); } });
                }).show();
    }

    private void renderImportOutcome() {
        ImportOutcome result = importOutcome;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content = vertical(); content.setGravity(Gravity.CENTER_HORIZONTAL); content.setPadding(dp(20), dp(36), dp(20), dp(24)); scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        ImageView icon = new ImageView(this); icon.setImageDrawable(new AppIcon(result.verified ? AppIcon.Kind.CHECK : AppIcon.Kind.CAMERA,
                result.verified ? colors.onPrimary : colors.primary, getResources().getDisplayMetrics().density));
        icon.setPadding(dp(20), dp(20), dp(20), dp(20)); icon.setBackground(shape(result.verified ? colors.primary : colors.container, 44));
        content.addView(icon, new LinearLayout.LayoutParams(dp(80), dp(80)));
        TextView outcomeTitle = text(result.verified ? "已保存到 " + slotLabel(result.target) : result.heading, 24, true); outcomeTitle.setGravity(Gravity.CENTER); outcomeTitle.setPadding(0, dp(22), 0, dp(8)); content.addView(outcomeTitle, new LinearLayout.LayoutParams(-1, -2));
        if (!result.presetName.isEmpty()) { TextView presetName = label(content, result.presetName, 18, colors.text); presetName.setGravity(Gravity.CENTER); }
        if (result.verified) {
            PresetRecord outcomePreset = library.findBySha256(result.sourceSha256);
            if (outcomePreset != null) {
                FrameLayout photo = preview(outcomePreset, null, 160); LinearLayout.LayoutParams photoParams = new LinearLayout.LayoutParams(dp(200), dp(160)); photoParams.topMargin = dp(24); photoParams.bottomMargin = dp(20); content.addView(photo, photoParams);
            }
            informationRow(content, AppIcon.Kind.CHECK, "已读回核对", "预设已成功保存到相机");
            TextView next = label(content, "请在相机中选择 " + slotLabel(result.target) + "，并试拍确认效果。", 14, colors.secondary); next.setGravity(Gravity.CENTER); next.setPadding(0, dp(24), 0, dp(8));
            TextView caveat = label(content, "兼容适配，实际效果仍需拍摄确认。", 12, colors.secondary); caveat.setGravity(Gravity.CENTER);
            if (result.uncertain) label(content, result.detail, 13, colors.error);
            if (hasPreparedWireless()) {
                TextView connected = label(content, "相机保持连接，可继续导入", 12, colors.success); connected.setGravity(Gravity.CENTER);
            }
            if (!message.isEmpty()) label(content, message, 12, colors.secondary);
        } else {
            if (!result.camera.isEmpty()) { TextView camera = label(content, result.camera + (result.target >= 201 ? " · " + slotLabel(result.target) : ""), 14, colors.secondary); camera.setGravity(Gravity.CENTER); }
            label(content, result.detail, 15, colors.text);
        }
        LinearLayout footer = vertical(); footer.setPadding(dp(16), dp(4), dp(16), dp(12));
        if (result.uncertain && recoveryBlocked && !result.verified) footer.addView(button("只读核对相机", AppIcon.Kind.REFRESH, true, this::recoverImport));
        else footer.addView(button("完成", AppIcon.Kind.CHECK, true, this::finishImportScreen));
        if (result.uncertain && recoveryBlocked && result.verified) footer.addView(button("只读核对并保存报告", AppIcon.Kind.REFRESH, false, this::recoverImport));
        if (!report.isEmpty() && !result.verified) footer.addView(textLink("导出报告", this::exportReport));
        if (result.uncertain && recoveryBlocked) footer.addView(button("返回预设详情", AppIcon.Kind.BACK, false, this::finishImportScreen));
        if (result.verified) { TextView note = label(footer, "不会自动切换相机当前预设", 12, colors.secondary); note.setGravity(Gravity.CENTER); }
        body.addView(footer);
    }

    private void finishImportScreen() {
        if (busy) return;
        if (confirmation != null || importOutcome == null || !importOutcome.verified) releasePreparedWireless(preparedWireless);
        connections.clearImportStatus(); shownImportStatus = null;
        importOutcome = null; confirmation = null; message = ""; render();
    }
    private void field(LinearLayout parent, String name, String value) { label(parent, name, 12, colors.secondary); TextView content = text(value, 16, true); content.setPadding(0, 0, 0, dp(12)); parent.addView(content); }
    private TextView textLink(String value, Runnable action) {
        TextView link = text(value, 13, false); link.setTextColor(colors.primary); link.setGravity(Gravity.CENTER_VERTICAL); link.setMinHeight(dp(48)); link.setPadding(dp(2), 0, dp(2), 0); link.setBackground(ripple(colors.surface)); link.setFocusable(true);
        link.setOnClickListener(v -> { if (!busy) action.run(); }); return link;
    }
    private void informationRow(LinearLayout parent, AppIcon.Kind kind, String heading, String description) {
        LinearLayout row = horizontal(); row.setPadding(dp(12), dp(12), dp(12), dp(12)); row.setBackground(shape(colors.container, 10));
        ImageView icon = new ImageView(this); icon.setImageDrawable(new AppIcon(kind, colors.secondary, getResources().getDisplayMetrics().density)); row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout copy = vertical(); copy.setPadding(dp(12), 0, 0, 0); copy.addView(text(heading, 15, true));
        if (!description.isEmpty()) { TextView note = text(description, 12, false); note.setTextColor(colors.secondary); note.setPadding(0, dp(5), 0, 0); copy.addView(note); }
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(16); params.bottomMargin = dp(16); parent.addView(row, params);
    }
    private static String quarter(int value) { return java.math.BigDecimal.valueOf(value).divide(java.math.BigDecimal.valueOf(4)).stripTrailingZeros().toPlainString(); }

    private void writePreset(CameraConnection connection, String model, PresetRecord preset, byte[] source, int target, int middle, int clarity,
                             String expectedHash, PictureControlImportSession.OverwriteConfirmation overwrite) {
        if (busy || recoveryBlocked) return;
        final WirelessPictureControl owner = connection.wirelessOwner;
        if (connection.wireless() && (owner == null || owner != preparedWireless || !owner.isPrepared())) {
            releasePreparedWireless(owner);
            confirmation = null; finishBusy("相机连接已断开，请重新读取槽位后确认导入；本次未写入。"); return;
        }
        final CameraConnectionRuntime.OperationLease lease;
        try { lease = connection.wireless() ? connections.beginOperation() : null; }
        catch (RuntimeException failure) { notice("无法保持后台连接", readable(failure)); return; }
        if (lease != null && !lease.bind(owner)) { lease.close(); finishBusy("相机连接已取消，本次未写入。"); return; }
        stopWirelessHeartbeat();
        if (preparedWireless == owner) preparedWireless = null;
        connections.forget(owner); connections.beginImport();
        startBusy("正在导入并读回核对，请保持连接…");
        UsbPictureControlImport importer = connection.wireless() ? null : new UsbPictureControlImport(); activeImport = importer;
        (connection.wireless() ? connections.cameraWorker : worker).execute(() -> {
            try {
            WirelessPictureControl wirelessImporter = null;
            UsbPictureControlImport.Result result = null; Exception failure = null; boolean started = false; String storageWarning = "";
            try {
                if (!sha256(source).equals(preset.sha256)) throw new IOException("源预设校验失败。");
                if (connection.wireless()) {
                    wirelessImporter = owner;
                    if (!wirelessImporter.isPrepared()) throw new IOException("确认期间相机连接已断开，请重新读取槽位；未重新连接或写入。");
                }
                JSONObject journal = new JSONObject().put("schemaVersion", 2).put("createdAt", System.currentTimeMillis())
                        .put("presetName", preset.displayName).put("sourceSha256", preset.sha256).put("expectedSha256", expectedHash)
                        .put("targetItem", target).put("model", model).put("transport", connection.wireless() ? "wifi" : "usb")
                        .put("wirelessHost", connection.host).put("wirelessPort", connection.port)
                        .put("overwriteRequested", overwrite != null);
                if (overwrite != null) journal.put("confirmedTargetSha256", overwrite.sha256()).put("confirmedTargetName", overwrite.registrationName());
                if (!connection.wireless()) journal.put("vendorId", connection.device.getVendorId()).put("productId", connection.device.getProductId());
                // A durable intent record precedes the backend call. An interrupted process must not silently repeat a write.
                byte[] journalBytes = journal.toString(2).getBytes(StandardCharsets.UTF_8);
                synchronized (CameraConnectionRuntime.PERSISTENCE_LOCK) {
                writeAtomic(journalFile(), journalBytes);
                try (InputStream savedJournal = journalFile().openRead()) {
                    if (!Arrays.equals(journalBytes, readBounded(savedJournal, 32 * 1024))) throw new IOException("导入记录无法安全保存，已停止，未开始相机写入。");
                }
                }
                pendingJournal = journal; recoveryBlocked = true; started = true;
                result = connection.wireless()
                        ? wirelessImporter.importPreparedNcpPresetKeepingConnection(source.clone(), middle, clarity, target, overwrite)
                        : importer.importNcpPreset(connection.device, usb, source.clone(), middle, clarity, target, overwrite);
            } catch (Exception e) {
                failure = e;
                if (e instanceof UsbPictureControlImport.ImportException) result = ((UsbPictureControlImport.ImportException) e).partialResult;
            } finally {
                if (importer != null) importer.close(); if (activeImport == importer) activeImport = null;
                boolean keepConnection = result != null && result.completed && result.ncpRegistrationVerified
                        && result.readbackVerified && result.sessionRetainedAfterImport;
                if (keepConnection && owner != null && owner.isPrepared()
                        && connections.hold(lease, owner, connection.host, connection.port, model)) {
                    preparedWireless = owner; preparedWirelessHost = connection.host;
                    preparedWirelessPort = connection.port; preparedWirelessModel = model;
                } else {
                    if (wirelessImporter != null) closePreparedQuietly(wirelessImporter);
                    else if (owner != null) closePreparedQuietly(owner);
                    if (activeWireless == owner) activeWireless = null;
                }
            }
            if (result != null) {
                String importJson = "";
                try { importJson = result.toJsonString(); storageWarning = saveReport(importJson); }
                catch (RuntimeException e) { storageWarning = "\n报告生成失败。当前相机写入结果仍有效，请先核对槽位，勿重复导入。"; }
                if (result.completed && result.ncpRegistrationVerified && result.readbackVerified
                        && result.sessionRetainedAfterImport) {
                    connections.recordVerifiedImport(owner, importJson);
                }
                // A reported write without verified readback remains blocked until an explicit read-only recovery.
                boolean settled = result.completed || !result.writeAttempted;
                if (settled && storageWarning.isEmpty()) { synchronized (CameraConnectionRuntime.PERSISTENCE_LOCK) { journalFile().delete(); } pendingJournal = null; recoveryBlocked = false; }
            } else if (!started) recoveryBlocked = false;
            final UsbPictureControlImport.Result outcome = result; final Exception error = failure; final String warning = storageWarning; final boolean invoked = started;
            boolean verified = outcome != null && outcome.ncpRegistrationVerified && outcome.completed && outcome.readbackVerified
                    && (outcome.sessionClosed || outcome.sessionRetainedAfterImport);
            boolean resultUncertain = !verified && invoked && (outcome == null || outcome.writeAttempted);
            connections.publishImportStatus(new CameraConnectionRuntime.ImportStatus(
                    verified ? "已保存到 " + slotLabel(target) + " 并核对" : resultUncertain ? "结果待核对" : "暂未导入",
                    verified ? "预设已保存，相机读回的数据与本次适配数据一致。" + warning
                            : (error == null ? "相机尚未确认完整结果。" : readable(error))
                            + "\n\n" + (resultUncertain ? "相机可能已保存，请先只读核对，勿重复导入。" : "本次未发起写入，请检查连接后重试。") + warning,
                    preset.displayName, model, target, verified, recoveryBlocked, preset.sha256,
                    recoveryBlocked && pendingJournal != null ? pendingJournal.toString() : ""));
            onUi(() -> {
                busy = false;
                boolean success = outcome != null && outcome.ncpRegistrationVerified && outcome.completed && outcome.readbackVerified
                        && (outcome.sessionClosed || outcome.sessionRetainedAfterImport);
                confirmation = null;
                if (success) {
                    importOutcome = new ImportOutcome("已保存到 " + slotLabel(target) + " 并核对", "预设已保存，相机读回的数据与本次适配数据一致。" + warning, preset.displayName, model, target, true, recoveryBlocked, preset.sha256);
                } else {
                    boolean uncertain = invoked && (outcome == null || outcome.writeAttempted);
                    importOutcome = new ImportOutcome(uncertain ? "结果待核对" : "暂未导入", (error == null ? "相机尚未确认完整结果。" : readable(error))
                            + "\n\n" + (uncertain ? "相机可能已经保存预设，请先只读核对，不要重复导入。" : "未发起写入，请检查连接后重新开始。") + warning,
                            preset.displayName, model, target, false, uncertain);
                }
                message = "";
                if (success && outcome.sessionRetainedAfterImport) {
                    if (preparedWireless == owner && owner.isPrepared()) startWirelessHeartbeat(owner);
                    else message = "预设已保存并读回核对，当前相机连接已断开，无需重复导入。";
                }
                render();
            });
            } finally { if (lease != null) lease.close(); }
        });
    }

    private void completeRecovery(UsbPictureControlProbe.Result snapshot, List<UsbPictureControlProbe.ItemRecord> slots, int wirelessRequest) throws Exception {
        JSONObject journal = pendingJournal;
        if (journal == null) throw new IOException("没有可读取的上次导入记录。");
        if (!journal.getString("model").equals(snapshot.model)) throw new IOException("连接的相机型号与上次导入记录不同，请连接原相机。");
        int target = journal.getInt("targetItem"); String expected = journal.getString("expectedSha256");
        UsbPictureControlProbe.ItemRecord observed = null;
        for (UsbPictureControlProbe.ItemRecord slot : slots) if (slot.itemCode == target) observed = slot;
        if (observed == null) throw new IOException("未读到上次操作的目标槽位。");
        boolean match = observed.sha256.equals(expected); boolean empty = observed.empty;
        boolean original = journal.optBoolean("overwriteRequested") && observed.sha256.equals(journal.optString("confirmedTargetSha256"));
        JSONObject json = snapshot.toJson().put("consumerRecovery", true).put("pendingImport", journal)
                .put("expectedDataObserved", match).put("targetObservedEmpty", empty).put("confirmedOriginalObserved", original);
        String warning = saveReport(json.toString(2));
        final String text = match ? "已在 " + slotLabel(target) + " 读到与上次导入一致的数据。请在相机菜单选择它使用。"
                : empty ? slotLabel(target) + " 当前为空，没有读到上次导入的数据。可以重新选择预设并确认导入。"
                : original ? slotLabel(target) + " 仍是覆盖前的「" + journal.optString("confirmedTargetName") + "」，没有读到本次预期数据。请重新读取槽位并确认后再导入。"
                : slotLabel(target) + " 当前已有其他数据，与上次预期不一致。本次核对未修改相机；继续导入前请重新读取并确认目标槽位。";
        onUi(() -> {
            if (wirelessRequest != -1 && wirelessRequest != wirelessScanEpoch) return;
            if (pendingJournal != journal) return;
            // Commit recovery only while this request is current. Cancelling an old scan cannot clear a newer journal.
            if (warning.isEmpty()) { synchronized (CameraConnectionRuntime.PERSISTENCE_LOCK) { journalFile().delete(); } pendingJournal = null; recoveryBlocked = false; }
            busy = false; confirmation = null; wirelessGuide = false;
            if (detailId.isEmpty() && detailOnline == null && library.findBySha256(journal.optString("sourceSha256")) != null) detailId = journal.optString("sourceSha256");
            importOutcome = new ImportOutcome(match ? "已保存到 " + slotLabel(target) + " 并核对" : empty ? "目标槽位为空" : "目标槽位内容不同", text + warning,
                    journal.optString("presetName"), snapshot.model, target, match, recoveryBlocked, journal.optString("sourceSha256")); message = ""; render();
        });
    }

    private String saveReport(String json) {
        synchronized (CameraConnectionRuntime.PERSISTENCE_LOCK) {
        report = json;
        try {
            AtomicFile target = new AtomicFile(new File(getFilesDir(), "last-report.json")); byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            writeAtomic(target, bytes);
            try (InputStream input = target.openRead()) { if (!Arrays.equals(bytes, readBounded(input, 4 * 1024 * 1024))) throw new IOException("报告保存后校验失败。"); }
            return "";
        }
        catch (Exception e) { return "\n报告自动保存失败，当前相机结果仍有效。请从帮助导出报告，避免重复写入。"; }
        }
    }

    private void exportReport() {
        if (busy || report.isEmpty()) return;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "nikon-usb-report.json"), EXPORT_REPORT);
    }

    private void showHelp() {
        List<String> labels = new ArrayList<>();
        labels.add("预设导入说明"); labels.add("在线预设库地址"); labels.add("导出最近报告"); labels.add("高级诊断");
        if (recoveryBlocked) labels.add("核对上次导入 · 只读");
        labels.add("关于相机预设");
        new AlertDialog.Builder(this).setTitle("帮助与设置").setItems(labels.toArray(new String[0]), (d, w) -> {
            switch (w) {
                case 0: notice("导入相机", "先在底部设置中连接 Wi-Fi 或 USB，再打开本地预设并点击导入相机。\n\n无线导入会重新读取能力和槽位。相机必须通过无线开放优化校准接口；要求专有配对认证时本版可能无法连接，可在设置中改用 USB。\n\n选择空槽可直接导入；选择已有预设的槽位时，会显示原名称并要求确认覆盖。确认后重新核对目标内容，只登记一次并读回核对；槽位已变化则停止。结果不明时先只读核对，勿重复导入。\n\n已有相册连接会直接移交给预设读取；设置检测后的连接也会保持。导入成功并读回核对后保持连接，可继续导入；需要断开时请到设置操作。"); break;
                case 1: if (busy) notice("操作进行中", "请等待当前操作结束后再更改地址。"); else configureCatalog(); break;
                case 2: if (busy) notice("操作进行中", "结束后可导出报告。"); else if (report.isEmpty()) notice("暂无报告", "连接相机并执行读取或导入后可导出报告。"); else exportReport(); break;
                case 3: if (busy) notice("操作进行中", "请等待当前任务结束后打开高级诊断。"); else if (recoveryBlocked) notice("先核对上次导入", "请先使用只读核对确认上次操作的结果，再打开包含写入工具的高级诊断。"); else if (canChangeConnection()) { album.disconnect(); startActivity(new Intent(this, DiagnosticsActivity.class)); } break;
                default: if (recoveryBlocked && w == 4) recoverImport(); else notice("相机预设", "预设浏览、本地管理与尼康相机导入。\n当前 USB 兼容通道以 Z6II 实测为基础，并不支持所有 NCP、NP2 或 NP3。\n\n在线库需自行配置，首批精选与真实样片尚未内置。无线相册支持范围以实际连接与读取结果为准。");
            }
        }).setNegativeButton("关闭", null).show();
    }

    private void switchRoot(int destination) {
        if (busy) return;
        if (destination == (settingsTab ? 2 : albumTab ? 1 : 0)) return;
        if (confirmation != null) releasePreparedWireless(preparedWireless);
        confirmation = null; importOutcome = null; wirelessGuide = false;
        albumTab = destination == 1; settingsTab = destination == 2; settingsReturnToPreset = false;
        detailId = ""; detailOnline = null; message = ""; render();
        if (destination == 0 && !localTab && !catalogLoaded) loadCatalog(false);
    }

    private void goBack() {
        if (busy && wirelessGuide) { cancelWirelessScan(); return; }
        if (busy) { notice("操作进行中", "请等待当前任务结束；导入期间保持相机连接。"); return; }
        if (wirelessGuide) { wirelessGuide = false; message = ""; render(); }
        else if (settingsTab && album.isConnectionGuide()) { if (album.isConnecting()) album.disconnect(); album.goBack(); render(); }
        else if (settingsTab && settingsReturnToPreset) { settingsTab = false; settingsReturnToPreset = false; message = ""; render(); }
        else if (settingsTab) switchRoot(0);
        else if (importOutcome != null || confirmation != null) finishImportScreen();
        else if (albumTab && album.canGoBack()) { album.goBack(); render(); }
        else if (!detailId.isEmpty() || detailOnline != null) { detailId = ""; detailOnline = null; message = ""; render(); }
        else if (albumTab) switchRoot(0);
        else super.onBackPressed();
    }
    @Override public void onBackPressed() { goBack(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("local", localTab); out.putBoolean("album", albumTab);
        out.putBoolean("settings", settingsTab); out.putBoolean("settingsReturnToPreset", settingsReturnToPreset);
        out.putString("detailId", detailId);
        out.putString("previewForId", previewForId); out.putBoolean("importAfterPick", importAfterPick);
        out.putBoolean("wirelessGuide", wirelessGuide); out.putBoolean("wirelessRecovery", wirelessRecovery);
        out.putBoolean("wirelessSettingsProbe", wirelessSettingsProbe);
        out.putString("wirelessAddress", wirelessAddress); out.putString("wirelessPort", wirelessPortText);
        super.onSaveInstanceState(out);
    }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); render(); }

    @Override protected void onPause() {
        if (activeComparison != null) activeComparison.release();
        super.onPause();
    }

    private void startBusy(String value) { busy = true; progress = value; render(); }
    private void finishBusy(String value) { busy = false; progress = ""; message = value; render(); }
    private void onUi(Runnable task) { if (!destroyed) runOnUiThread(() -> { if (!destroyed) task.run(); }); }
    private void notice(String title, String message) { if (!destroyed) new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show(); }
    private AtomicFile journalFile() { return new AtomicFile(new File(getFilesDir(), "pending-consumer-import.json")); }

    private LinearLayout vertical() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout horizontal() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int sp, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(colors.text);
        view.setIncludeFontPadding(false); view.setLineSpacing(dp(3), 1f); view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL)); return view;
    }
    private TextView label(LinearLayout parent, String value, int size, int color) { TextView t = text(value, size, false); t.setTextColor(color); t.setPadding(0, dp(8), 0, dp(5)); parent.addView(t); return t; }
    private void heading(LinearLayout parent, String value, int size) { TextView t = text(value, size, true); t.setPadding(0, dp(20), 0, dp(4)); parent.addView(t); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private RippleDrawable ripple(int base) { return new RippleDrawable(ColorStateList.valueOf(colors.primaryContainer), shape(base, 12), null); }
    private void divider(LinearLayout parent) { View line = new View(this); line.setBackgroundColor(colors.outline); parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1))); }
    private int photoHeight(float ratio) {
        int width = getResources().getConfiguration().screenWidthDp;
        return Math.round((Math.min(width, 720) - 32) / ratio);
    }
    private Button button(String value, AppIcon.Kind kind, boolean primary, Runnable click) {
        Button button = new Button(this); button.setText(value); button.setTextSize(15); button.setAllCaps(false);
        button.setGravity(Gravity.CENTER); button.setMinHeight(dp(52)); button.setMinimumWidth(0); button.setMinWidth(0); button.setTextColor(primary ? colors.onPrimary : colors.primary);
        button.setTypeface(Typeface.create("sans-serif", primary ? Typeface.BOLD : Typeface.NORMAL)); button.setIncludeFontPadding(false);
        button.setStateListAnimator(null); button.setElevation(0); button.setTranslationZ(0);
        GradientDrawable background = shape(primary ? colors.primary : colors.card, 26);
        button.setBackgroundTintList(null); button.setBackground(new RippleDrawable(ColorStateList.valueOf(colors.primaryContainer), background, null));
        button.setPadding(dp(16), dp(10), dp(16), dp(10)); button.setCompoundDrawablePadding(dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(12); button.setLayoutParams(params);
        button.setEnabled(!busy); button.setAlpha(busy ? .5f : 1); button.setOnClickListener(v -> { if (!busy) click.run(); }); return button;
    }
    private Button compactButton(String value, AppIcon.Kind kind, boolean primary, Runnable click) {
        Button view = button(value, kind, primary, click); view.setTextSize(12); view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48)); view.setMinWidth(dp(80)); view.setMinimumWidth(dp(80)); view.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable background = shape(primary ? colors.primary : colors.surface, primary ? 7 : 24); if (!primary) background.setStroke(dp(1), colors.primary);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(colors.primaryContainer), background, null));
        if (kind != null) {
            AppIcon icon = new AppIcon(kind, primary ? colors.onPrimary : colors.primary, getResources().getDisplayMetrics().density);
            icon.setBounds(0, 0, dp(18), dp(18)); view.setCompoundDrawablesRelative(icon, null, null, null); view.setCompoundDrawablePadding(dp(5));
        }
        return view;
    }
    private View iconButton(AppIcon.Kind kind, String description, Runnable click) {
        ImageView button = new ImageView(this); button.setImageDrawable(new AppIcon(kind, colors.text, getResources().getDisplayMetrics().density));
        button.setPadding(dp(12), dp(12), dp(12), dp(12)); button.setBackground(ripple(colors.surface));
        button.setContentDescription(description); button.setFocusable(true); button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        button.setOnClickListener(v -> click.run()); return button;
    }
    private void nav(LinearLayout parent, String value, AppIcon.Kind kind, boolean active, Runnable click) {
        LinearLayout cell = vertical(); cell.setGravity(Gravity.CENTER); cell.setPadding(dp(8), dp(7), dp(8), dp(7)); cell.setMinimumHeight(dp(58));
        cell.setBackground(new RippleDrawable(ColorStateList.valueOf(colors.primaryContainer), shape(active ? colors.primaryContainer : Color.TRANSPARENT, 30), null)); cell.setEnabled(!busy); cell.setAlpha(busy ? .5f : 1); cell.setFocusable(true); cell.setContentDescription(value); cell.setSelected(active);
        ImageView icon = new ImageView(this); icon.setImageDrawable(new AppIcon(kind, active ? colors.primary : colors.secondary, getResources().getDisplayMetrics().density));
        cell.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(23))); TextView label = text(value, 10, active); label.setGravity(Gravity.CENTER); label.setTextColor(active ? colors.primary : colors.secondary); label.setPadding(0, dp(3), 0, 0); cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.leftMargin = dp(1); params.rightMargin = dp(1); parent.addView(cell, params);
        cell.setOnClickListener(v -> { if (!busy) click.run(); });
    }
    private void sourceTab(LinearLayout parent, String value, boolean active, Runnable click) {
        FrameLayout cell = new FrameLayout(this); TextView tab = text(value, 13, active); tab.setGravity(Gravity.CENTER); tab.setTextColor(active ? colors.text : colors.secondary);
        if (active) { tab.setBackground(shape(colors.card, 19)); tab.setElevation(dp(1)); }
        FrameLayout.LayoutParams capsule = new FrameLayout.LayoutParams(-1, dp(34), Gravity.CENTER); capsule.leftMargin = capsule.rightMargin = dp(2); cell.addView(tab, capsule);
        cell.setFocusable(true); cell.setSelected(active); cell.setContentDescription(value); cell.setOnClickListener(v -> { if (!busy) click.run(); });
        parent.addView(cell, new LinearLayout.LayoutParams(0, dp(48), 1));
    }
    private void empty(LinearLayout parent, String title, String description, String action, Runnable click) {
        LinearLayout content = vertical(); content.setPadding(dp(16), dp(18), dp(16), dp(24));
        ImageView icon = new ImageView(this); icon.setImageDrawable(new AppIcon(AppIcon.Kind.PRESETS, colors.secondary, getResources().getDisplayMetrics().density));
        content.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32))); heading(content, title, 18);
        if (description != null && !description.isEmpty()) label(content, description, 14, colors.secondary);
        if (action != null && click != null) content.addView(button(action, AppIcon.Kind.FILE, false, click)); parent.addView(content);
    }
    private Spinner spinner(LinearLayout parent, String[] choices, int selected) {
        Spinner spinner = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); spinner.setAdapter(adapter); spinner.setSelection(selected);
        GradientDrawable background = shape(colors.surface, 5); background.setStroke(dp(1), colors.outline); spinner.setBackground(background); spinner.setPadding(dp(12), 0, dp(12), 0);
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, dp(52))); return spinner;
    }
    private static String[] quarters(int min, int max) { String[] result = new String[max - min + 1]; for (int i = 0; i < result.length; i++) result[i] = String.format(Locale.ROOT, "%.2f", (min + i) / 4.0); return result; }
    private static String slotLabel(int target) { return "C" + (target - 200); }
    private static String readable(Throwable error) { return error.getMessage() == null || error.getMessage().trim().isEmpty() ? error.getClass().getSimpleName() : error.getMessage(); }
    private String documentName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) { if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0); }
        return "unknown";
    }
    private byte[] readBounded(Uri uri, int limit) throws IOException {
        try (InputStream stream = getContentResolver().openInputStream(uri)) { if (stream == null) throw new IOException("无法读取文件。"); return readBounded(stream, limit); }
    }
    private static byte[] readBounded(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = stream.read(buffer)) != -1) { if (Thread.currentThread().isInterrupted()) throw new IOException("操作已取消。"); if (output.size() > limit - count) throw new IOException("文件过大，无法安全读取。"); output.write(buffer, 0, count); } return output.toByteArray();
    }
    private static Bitmap decodePreview(byte[] bytes) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true; BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 16384 || bounds.outHeight > 16384 || (long) bounds.outWidth * bounds.outHeight > 64000000L) throw new IOException("图片尺寸无效或过大，请选择常规照片。");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1; options.inPreferredConfig = Bitmap.Config.RGB_565;
        while (bounds.outWidth / options.inSampleSize > 1200 || bounds.outHeight / options.inSampleSize > 1200) options.inSampleSize *= 2;
        try {
            Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options); if (image == null) throw new IOException("图片无法解码。");
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream input = new ByteArrayInputStream(bytes)) { orientation = new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL); }
            catch (IOException | RuntimeException ignored) { /* Non-EXIF image formats remain valid previews. */ }
            Matrix transform = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: transform.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: transform.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: transform.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: transform.setRotate(90); transform.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: transform.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: transform.setRotate(270); transform.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: transform.setRotate(270); break;
            }
            if (!transform.isIdentity()) { Bitmap rotated = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), transform, true); if (rotated != image) image.recycle(); image = rotated; }
            return image;
        } catch (RuntimeException | OutOfMemoryError e) { throw new IOException("图片无法安全解码，请选择较小的照片。", e); }
    }
    private static String sha256(byte[] bytes) throws Exception { StringBuilder text = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) text.append(String.format(Locale.ROOT, "%02x", b & 255)); return text.toString(); }
    private static void writeAtomic(AtomicFile file, byte[] bytes) throws IOException {
        synchronized (CameraConnectionRuntime.PERSISTENCE_LOCK) {
        FileOutputStream output = null;
        try { output = file.startWrite(); output.write(bytes); output.getFD().sync(); file.finishWrite(output); }
        catch (IOException | RuntimeException e) { if (output != null) file.failWrite(output); throw e; }
        }
    }
    private static String readText(AtomicFile file, int limit) throws IOException { synchronized (CameraConnectionRuntime.PERSISTENCE_LOCK) { try (InputStream input = file.openRead()) { return new String(readBounded(input, limit), StandardCharsets.UTF_8); } } }

    @Override protected void onDestroy() {
        PageTransitions.cancel(transitioningContent);
        connections.removeObserver(connectionObserver);
        destroyed = true; if (activeProbe != null) activeProbe.close(); if (activeImport != null) activeImport.close();
        // The connected-device service owns wireless sessions and the serial worker.
        // Recreating or removing this screen only detaches its UI.
        if (album != null) album.close(); images.shutdownNow(); imageCache.evictAll();
        if (receiverRegistered) unregisterReceiver(receiver); super.onDestroy();
    }
}

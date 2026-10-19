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
import android.graphics.Rect;
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
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.nikonpreset.usbprobe.library.OnlinePreset;
import com.nikonpreset.usbprobe.album.CameraWifiNetwork;
import com.nikonpreset.usbprobe.album.AlbumController;
import com.nikonpreset.usbprobe.album.PtpIpClient;
import com.nikonpreset.usbprobe.album.ConnectionTrace;
import com.nikonpreset.usbprobe.connection.CameraWifiSession;
import com.nikonpreset.usbprobe.connection.CameraConnectionRuntime;
import com.nikonpreset.usbprobe.connection.CameraConnectionPreferences;
import com.nikonpreset.usbprobe.connection.PhotoLiveContinuation;
import com.nikonpreset.usbprobe.connection.ConnectionProtection;
import com.nikonpreset.usbprobe.library.OnlinePresetRepository;
import com.nikonpreset.usbprobe.library.PresetLibrary;
import com.nikonpreset.usbprobe.library.PresetRecord;
import com.nikonpreset.usbprobe.library.PresetCategories;
import com.nikonpreset.usbprobe.library.PresetDownloadTasks;
import com.nikonpreset.usbprobe.album.PhotoProjectPresets;
import com.nikonpreset.usbprobe.ncp.NcpFile;
import com.nikonpreset.usbprobe.ncp.NcpPictureControlConverter;
import com.nikonpreset.usbprobe.ncp.PresetPreviewProfile;
import com.nikonpreset.usbprobe.ncp.PresetPreviewProfiles;
import com.nikonpreset.usbprobe.picturecontrol.PictureControlData;
import com.nikonpreset.usbprobe.ui.AlbumPanel;
import com.nikonpreset.usbprobe.ui.AccountPanel;
import com.nikonpreset.usbprobe.ui.CommunityPanel;
import com.nikonpreset.usbprobe.ui.PersonalPresetPanel;
import com.nikonpreset.usbprobe.ui.UpdatePanel;
import com.nikonpreset.usbprobe.ui.AppIcon;
import com.nikonpreset.usbprobe.ui.UiPalette;
import com.nikonpreset.usbprobe.ui.GlassBar;
import com.nikonpreset.usbprobe.ui.AppAppearance;
import com.nikonpreset.usbprobe.ui.PageTransitions;
import com.nikonpreset.usbprobe.ui.PresetPreviewRenderer;
import com.nikonpreset.usbprobe.ui.HoldToCompare;
import com.nikonpreset.usbprobe.ui.ToneCurveView;
import com.nikonpreset.usbprobe.ui.TopNotice;
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
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Consumer shell. Camera writes remain in the independently guarded transport backends. */
public final class MainActivity extends Activity {
    private static final int PICK_NCP = 100, PICK_SAMPLE = 101, EXPORT_REPORT = 102, PICK_TEMPLATE = 103,
            PROTECT_PHOTO_LIVE = 106, SAVE_PHOTOS_PERMISSION = 107, CAMERA_WIFI_PERMISSION = 108;
    private static final String USB_PERMISSION = "com.nikonpreset.usbprobe.CONSUMER_USB_PERMISSION";
    private static final String DEFAULT_CATALOG_URL = "https://nikon-d8girdjsr252fd90e-1252602548.ap-shanghai.app.tcloudbase.com/preset-library/catalog.json";
    private ExecutorService worker;
    private CameraConnectionRuntime connections;
    private CameraConnectionRuntime.OperationLease wirelessReadLease;
    private CameraConnectionRuntime.OperationLease wifiRequestLease;
    private AlertDialog cameraWifiDialog;
    private Runnable pendingWifiConnect;
    private Consumer<String> pendingWifiFailure;
    private long wifiSessionToken;
    private int wifiUiEpoch;
    private long connectionNoticeRevision;
    private TopNotice topNotice;
    private String shownConnectionNotice = "";
    private CameraConnectionRuntime.ImportStatus shownImportStatus;
    private ConnectionUiState renderedConnectionState;
    private final Runnable connectionObserver = this::syncConnectionState;
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final ExecutorService presetImages = Executors.newFixedThreadPool(2);
    private final Map<String, PreviewJob> previewJobs = new HashMap<>();
    private final ArrayDeque<PreviewJob> previewQueue = new ArrayDeque<>();
    private final Map<String, String> previewFailures = new HashMap<>();
    private final Set<String> unsupportedPreviews = new HashSet<>();
    private int activePresetImages;
    private boolean presetGridScrolling, previewDispatchPosted;
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final Set<String> pendingImages = new HashSet<>();
    private final Set<String> failedImages = new HashSet<>();
    private final Map<String, List<PreviewTarget>> imageTargets = new HashMap<>();
    private final PresetDownloadTasks presetDownloads = PresetDownloadTasks.shared();
    private final Map<String, List<PresetDownloadTarget>> presetDownloadTargets = new HashMap<>();
    private final Set<String> refreshedPresetDownloads = new HashSet<>();
    private final Map<String, OnlinePreset> presetDownloadOrigins = new HashMap<>();
    private final Runnable presetDownloadObserver = () -> runOnUiThread(this::refreshPresetDownloadViews);
    private LinearLayout detailPresetFooter, detailPresetDeleteMount;
    private TextView[] detailPresetStorage;
    private String detailPresetSha = "";
    private long observedPresetLibraryRevision = -1;
    private final Map<String, List<OriginalPreviewTarget>> originalTargets = new HashMap<>();
    private final LruCache<String, PresetPreviewProfile> curveCache = new LruCache<>(8);
    private final Map<String, CurveTarget> pendingCurves = new HashMap<>();
    private UiPalette colors;
    private UsbManager usb;
    private PresetLibrary library;
    private OnlinePresetRepository online;
    private AlbumPanel album;
    private AccountPanel account;
    private CommunityPanel community;
    private View settingsUnreadDot, settingsNavigationItem, settingsMessageEntry;
    private PersonalPresetPanel personal;
    private boolean pendingPersonalPick;
    private final Map<String,String> presetDownloadOwners=new HashMap<>();
    private TextView settingsMessageBadge;
    private UpdatePanel updates;
    private int catalogPage, catalogEpoch;
    private boolean catalogHasMore;
    private final Set<String> catalogCategories = new LinkedHashSet<>();
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
    private String usbModel = "";
    private String pendingImportId = "";
    private boolean pendingResumeScheduled, pendingConnectionAccepted, activityVisible;
    private final PhotoLiveContinuation photoLiveContinuation = new PhotoLiveContinuation();
    private String pendingLiveProjectId = "", pendingProtectedLiveProjectId = "";
    private boolean liveResumeScheduled, livePreparationRequested, livePreparationPrompted;
    private AlertDialog livePreparationDialog;
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
    private Runnable pendingProtectedPhotoLive;
    private Runnable pendingPhotoSave;
    private PendingConfirmation confirmation;
    private ImportOutcome importOutcome;

    /** Only changes represented by the Activity shell require recreating its content. */
    private static final class ConnectionUiState {
        final Object preparedOwner, cameraSession;
        final boolean preparedReady, albumReady, connecting, preparing, transferBusy;
        final String preparationError;

        ConnectionUiState(CameraConnectionRuntime runtime, AlbumPanel panel,
                          WirelessPictureControl prepared) {
            preparedOwner = prepared;
            preparedReady = prepared != null && prepared.isPrepared();
            cameraSession = runtime.album.sessionConnectionInfo();
            albumReady = runtime.album.isConnected();
            connecting = panel.isConnecting();
            preparing = runtime.isPreparingAlbum();
            transferBusy = panel.isTransferBusy();
            preparationError = runtime.albumPreparationError();
        }

        boolean sameAs(ConnectionUiState other) {
            return other != null && preparedOwner == other.preparedOwner && cameraSession == other.cameraSession
                    && preparedReady == other.preparedReady && albumReady == other.albumReady
                    && connecting == other.connecting && preparing == other.preparing
                    && transferBusy == other.transferBusy && preparationError.equals(other.preparationError);
        }
    }

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
        topNotice = new TopNotice(this);
        connections = CameraConnectionRuntime.get(this);
        connectionNoticeRevision = connections.noticeRevision();
        shownConnectionNotice = connections.notice();
        worker = connections.worker;
        colors = new UiPalette(this);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        wifi = new CameraWifiNetwork(this);
        File traceDirectory = getExternalFilesDir(null);
        if (traceDirectory != null) ConnectionTrace.configure(new File(traceDirectory, "connection-trace.log"));
        presetPreviews = new PresetPreviewRenderer(this);
        account = new AccountPanel(this, new AccountPanel.Host() {
            @Override public void onChanged() { if (!destroyed && ready) { render(); if(personal!=null)personal.accountChanged();if(pendingPersonalPick&&!account.isOpen()&&personal!=null&&!personal.owner().isEmpty()){pendingPersonalPick=false;openPresetFilePicker(false);} if (community != null && !account.isOpen()) { community.refreshUnread(); community.onAccountChanged(); } } }
            @Override public boolean isVisible() { return activityVisible && !destroyed && !isFinishing(); }
            @Override public void onFeedback(String heading, String detail) { feedback(heading, detail); }
        });
        community = new CommunityPanel(this, new CommunityPanel.Host() {
            @Override public void onChanged() { if (!destroyed && ready) render(); }
            @Override public void onUnreadChanged(int count) { if (!destroyed && ready) refreshMessageBadges(); }
            @Override public boolean isVisible() { return activityVisible && !destroyed && !isFinishing(); }
            @Override public void onFeedback(String heading, String detail) { feedback(heading, detail); }
            @Override public void onLoginRequired() { account.open(); }
            @Override public void onDownloadPreset(OnlinePreset preset) { downloadPreset(preset); }
            @Override public void onOpenPreset(OnlinePreset preset, String focusCommentId) {
                albumTab = false; settingsTab = false; wirelessGuide = false; confirmation = null; importOutcome = null;
                community.focusInlineComment(focusCommentId); openPreset(preset, false);
            }
        });
        updates = new UpdatePanel(this, new UpdatePanel.Host() {
            @Override public void onChanged() { if (!destroyed && ready) render(); }
            @Override public boolean idleForAutomaticCheck() { return activityVisible && ready && !destroyed && !updateTaskActive() && !connections.hasCameraServiceWork(); }
            @Override public void onFeedback(String heading, String detail) { feedback(heading, detail); }
            @Override public void onAvailable(String version, Runnable show) { feedback("Huevia 有新版本", version + " 已可下载。", "查看", show); }
            @Override public void beforeInstall(Runnable proceed) { prepareUpdateInstallation(proceed); }
        });
        if (state != null) {
            localTab = state.getBoolean("local", true); albumTab = state.getBoolean("album"); settingsTab = state.getBoolean("settings");
            localCategoryFilter = state.getString("localPresetCategory", PresetCategories.ALL);
            onlineCategoryFilter = state.getString("onlinePresetCategory", PresetCategories.ALL);
            settingsReturnToPreset = state.getBoolean("settingsReturnToPreset");
            pendingImportId = state.getString("pendingImportId", "");
            pendingConnectionAccepted = state.getBoolean("pendingConnectionAccepted");
            if (state.getBoolean("pendingPhotoLive") && settingsTab) {
                photoLiveContinuation.request();
                pendingLiveProjectId = state.getString("pendingLiveProjectId", "");
                if (state.getBoolean("pendingLiveConnectionAccepted")) photoLiveContinuation.connectionAttempted();
                livePreparationRequested = state.getBoolean("livePreparationRequested");
            }
            detailId = state.getString("detailId", "");
            previewForId = state.getString("previewForId", ""); importAfterPick = state.getBoolean("importAfterPick");
            wirelessGuide = state.getBoolean("wirelessGuide"); wirelessRecovery = state.getBoolean("wirelessRecovery");
            wirelessSettingsProbe = state.getBoolean("wirelessSettingsProbe");
            wirelessAddress = state.getString("wirelessAddress", ""); wirelessPortText = state.getString("wirelessPort", "15740");
        }
        album = new AlbumPanel(this, new AlbumPanel.Host() {
            @Override public AutoCloseable beginPhotoWork() { return connections.beginPhotoOperation(); }
            @Override public void beforePhotoLive(Runnable start) {
                preparePhotoSave(() -> preparePhotoLive(start));
            }
            @Override public void beforePhotoLiveProject(String id, Runnable start) {
                preparePhotoSave(() -> preparePhotoLive(start, id));
            }
            @Override public void beforeSavePhotos(Runnable save) { preparePhotoSave(save); }
            @Override public void beforeConnect(Runnable connect) {
                if (!canChangeConnection()) { album.connectionStartFailed("请完成或取消当前照片任务后再更改连接。", connect); return; }
                pendingConnectionAccepted = !pendingImportId.isEmpty();
                photoLiveContinuation.connectionAttempted();
                livePreparationRequested = livePreparationPrompted = false;
                CameraConnectionRuntime.HeldConnection existing = connections.held();
                String host = album.getConfiguredHost(); int port = album.getConfiguredPort();
                boolean samePreset = existing != null && existing.owner.isPrepared() && existing.host.equals(host) && existing.port == port;
                boolean sameAlbum = album.hasCameraConnection() && album.connectionHost().equals(host) && album.connectionPort() == port;
                if (samePreset || sameAlbum) {
                    album.followSharedConnection(); return;
                }
                ensureCameraWifi(() -> continueAlbumConnection(connect), error -> album.connectionStartFailed(error, connect));
            }
            @Override public void onChanged() {
                if (!destroyed) { render(); resumePendingImport(); resumePendingPhotoLive(); }
            }
            @Override public void onConnectionCancelled() {
                cancelPendingCameraWifi();
                boolean returnToLive = photoLiveContinuation.isPending();
                clearPendingPhotoLive();
                pendingImportId = ""; pendingConnectionAccepted = false;
                if (returnToLive) {
                    if (album.isConnectionGuide()) album.goBack();
                    settingsTab = false; albumTab = true; wirelessGuide = false; message = ""; render();
                } else if (settingsReturnToPreset) returnToPreset();
            }
            @Override public void onConnectionRequired(String operation) { showConnectionRequired(operation); }
            @Override public void onPhotoLiveConnectionRequired() { showPhotoLiveConnectionRequired(); }
            @Override public void onPhotoLiveProjectConnectionRequired(String id) { showPhotoLiveProjectConnectionRequired(id); }
            @Override public void onPresetRequired() { showPresetRequired(); }
            @Override public void onMessage(String title, String text) { notice(title, text); }
            @Override public void onFeedback(String title, String text) { feedback(title, text); }
            @Override public void onActionFeedback(String title, String text, String action, Runnable task) {
                feedback(title, text, action, task);
            }
        }, connections.album, connections.photoPresets, connections.projectPresets);
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
                try { personal=new PersonalPresetPanel(this,library,new PersonalPresetPanel.Host(){
                    @Override public void changed(){if(!destroyed&&ready&&!busy&&!albumTab&&!settingsTab&&!account.isOpen()&&!community.isOpen()&&detailId.isEmpty()&&detailOnline==null)render();}
                    @Override public void login(){account.open();}
                    @Override public boolean canPrompt(){return activityVisible&&ready&&!busy&&!destroyed&&!albumTab&&!settingsTab&&!account.isOpen()&&!community.isOpen()&&detailId.isEmpty()&&detailOnline==null;}
                });}catch(IOException syncFailure){message="个人云端同步记录暂不可读取，本机预设仍可使用。";}
                online = new OnlinePresetRepository(getFilesDir(), library);
                try { online.useManagedEndpoint(DEFAULT_CATALOG_URL); }
                catch (IOException e) { message = "在线预设暂时无法启用，请稍后重新加载。本地预设仍可使用。"; }
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
                    ready = true; render(); refreshPresetDownloadViews();
                    updates.automaticCheck(); community.refreshUnread();if(personal!=null)personal.resumed();
                    syncConnectionState();
                    if (deferredResult != null) { Intent savedResult = deferredResult; int request = deferredRequest; deferredResult = null; onActivityResult(request, RESULT_OK, savedResult); }
                    else if (!localTab && !albumTab && !settingsTab) loadCatalog(false);
                });
            } catch (Exception e) { onUi(() -> { initializationError = readable(e); ready = true; render(); }); }
        });
    }

    private void render() {
        settingsUnreadDot = settingsNavigationItem = settingsMessageEntry = null; settingsMessageBadge = null;
        rememberPresetPosition();
        rememberDetailPosition();
        list = null;
        presetGridScrolling = false;
        detailScroll = null;
        detailCommentsMount = null;
        presetDownloadTargets.clear(); detailPresetFooter = null; detailPresetDeleteMount = null;
        detailPresetStorage = null; detailPresetSha = "";
        if (destroyed) return;
        if (community != null) {
            community.setInlineVisible(false);
            if (albumTab || settingsTab || wirelessGuide || confirmation != null || importOutcome != null
                    || (detailId.isEmpty() && detailOnline == null)) community.detachInlineComments();
        }
        if (activeComparison != null) activeComparison.release();
        activeComparison = null;
        for (List<OriginalPreviewTarget> targets : originalTargets.values()) targets.clear();
        PageTransitions.cancel(transitioningContent);
        AppAppearance.apply(this);
        // Some Android builds require DecorView creation before Window.getInsetsController().
        View decor = getWindow().getDecorView();
        colors = new UiPalette(this); imageEpoch++;
        if (ready && albumTab && !wirelessGuide && !recoveryBlocked && !busy && confirmation == null && importOutcome == null
                && album.wantsCameraPhotos()) connections.prepareAlbumFromCurrent(false);
        album.setSharedCameraState(hasPreparedWireless() || album.hasCameraConnection(), connections.isPreparingAlbum(),
                connections.albumPreparationError(), () -> {
                    if (recoveryBlocked) { notice("请先核对上次导入", "先完成只读核对，再继续浏览相机照片。"); return; }
                    connections.prepareAlbumFromCurrent(true);
                });
        renderedConnectionState = new ConnectionUiState(connections, album, preparedWireless);
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
        postVisiblePresetPreviews();
        // Keep both content and destination navigation readable on tablets and landscape phones.
        LinearLayout pane = vertical(); FrameLayout bounded = new FrameLayout(this);
        shell.addView(bounded, new LinearLayout.LayoutParams(-1, -1));
        int paneWidth = getResources().getConfiguration().screenWidthDp >= 600 ? Math.min(dp(720), getResources().getDisplayMetrics().widthPixels) : -1;
        FrameLayout.LayoutParams paneParams = new FrameLayout.LayoutParams(paneWidth, -1, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bounded.addView(pane, paneParams);
        boolean accountPage = account.isOpen();
        boolean communityPage = !accountPage && community.isOpen();
        boolean updatePage = !accountPage && !communityPage && updates.isOpen();
        boolean settingsGuide = settingsTab && !accountPage && !communityPage && !updatePage && album.isConnectionGuide();
        boolean presetPage = !albumTab && !settingsTab;
        boolean detailPage = presetPage && !wirelessGuide && confirmation == null && importOutcome == null
                && (!detailId.isEmpty() || detailOnline != null);
        community.setInlineVisible(detailPage && !accountPage && !communityPage && !updatePage);
        if (!detailPage) community.detachInlineComments();
        boolean back = accountPage || communityPage || updatePage || wirelessGuide || settingsGuide || confirmation != null || importOutcome != null
                || (presetPage && (!detailId.isEmpty() || detailOnline != null)) || (albumTab && album.canGoBack());
        LinearLayout bar = horizontal(); bar.setPadding(dp(back ? 10 : 22), dp(back ? 8 : 17), dp(16), dp(8));
        if (back) bar.addView(iconButton(AppIcon.Kind.BACK, "返回", this::goBack));
        title = text(accountPage ? account.title() : communityPage ? community.title() : updatePage ? "应用更新" : wirelessGuide ? (wirelessRecovery ? "无线核对预设" : wirelessSettingsProbe ? "预设支持检测" : "读取相机预设")
                : settingsGuide ? "Wi-Fi 无线连接" : settingsTab ? "设置" : albumTab ? album.title()
                : importOutcome != null ? "导入结果" : confirmation != null ? "确认导入"
                : (!detailId.isEmpty() || detailOnline != null ? "预设详情" : "预设库"), back ? 20 : 32, true);
        title.setLetterSpacing(back ? -.015f : -.03f);
        title.setMaxLines(1);title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        View accountAction = accountPage ? account.createHeaderAction(colors) : null;
        if (accountAction != null) bar.addView(accountAction);
        if (!back && !settingsTab) {
            bar.addView(connectionIndicator(albumTab));
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
        else if (accountPage) body.addView(account.createView(colors), new LinearLayout.LayoutParams(-1, -1));
        else if (communityPage) body.addView(community.createView(colors), new LinearLayout.LayoutParams(-1, -1));
        else if (updatePage) body.addView(updates.createView(colors), new LinearLayout.LayoutParams(-1, -1));
        else if (wirelessGuide) renderWirelessGuide();
        else if (settingsGuide) renderCameraConnectionGuide();
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
        if (presetPage && !back && ready && !busy) {
            View add = iconButton(AppIcon.Kind.PLUS, localTab ? "导入本地预设" : "发布预设",
                    () -> { if (localTab) pickNcp(false); else community.openSubmissionComposer(); });
            ((ImageView)add).setImageDrawable(new AppIcon(AppIcon.Kind.PLUS, colors.onPrimary, getResources().getDisplayMetrics().density));
            add.setBackground(shape(colors.primary, 27)); add.setElevation(dp(4));
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.BOTTOM | Gravity.END);
            position.setMargins(dp(24), 0, dp(24), dp(100)); bounded.addView(add, position);
        }
        refreshMessageBadges();
        setContentView(shell); shell.requestApplyInsets();
        transitionTo(body);
        if(personal!=null)personal.offerConsent();
    }

    private String navigationRoute() {
        if (account != null && account.isOpen()) return account.navigationKey();
        if (community != null && community.isOpen()) return community.navigationKey();
        if (updates != null && updates.isOpen()) return "settings:updates";
        if (wirelessGuide) return "wireless:" + wirelessSettingsProbe + ":" + wirelessRecovery;
        if (settingsTab) return account.isOpen() ? account.navigationKey() : album.isConnectionGuide() ? "settings:wifi" : "settings";
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
            topNotice.dismiss();
            boolean photo = route.contains("DETAIL") || lastRoute.contains("DETAIL");
            int direction = root != lastRoot ? Integer.signum(root - lastRoot)
                    : (route.startsWith(lastRoute + ":") || route.contains("detail") || route.contains("wifi") || route.startsWith("wireless")) ? 1 : -1;
            PageTransitions.enter(content, photo ? 0 : direction, photo);
        }
        lastRoute = route; lastRoot = root; transitioningContent = content;
    }

    private void renderHome() {
        visiblePresets.clear();
        List<Object> source = new ArrayList<>();
        if (localTab) {for(PresetRecord r:library.list())if(personal==null||personal.visible(r))source.add(r);} else source.addAll(catalog);
        Set<String> categories = new LinkedHashSet<>();
        for (Object value : source) categories.add(presetCategory(value));
        if (!localTab) categories.addAll(catalogCategories);
        String selected = localTab ? localCategoryFilter : onlineCategoryFilter;
        if (localTab && !PresetCategories.ALL.equals(selected) && !categories.contains(selected)) {
            selected = PresetCategories.ALL;
            if (localTab) localCategoryFilter = selected; else onlineCategoryFilter = selected;
        }
        for (Object value : source) if (PresetCategories.ALL.equals(selected) || selected.equals(presetCategory(value))) visiblePresets.add(value);
        LinearLayout top = vertical(); top.setPadding(dp(22), 0, dp(22), dp(5));
        TextView intro = text("把喜欢的色彩，带进相机", 12, false); intro.setTextColor(colors.secondary); intro.setPadding(0, 0, 0, dp(17)); top.addView(intro);
        LinearLayout sourceRow = horizontal();
        LinearLayout tabs = horizontal();
        sourceTab(tabs, "我的预设", localTab, () -> { localTab = true; render(); });
        sourceTab(tabs, "在线预设", !localTab, () -> { localTab = false; render(); if (!catalogLoaded) loadCatalog(false); });
        sourceRow.addView(tabs, new LinearLayout.LayoutParams(dp(216), dp(48)));
        View sourceSpace = new View(this); sourceRow.addView(sourceSpace, new LinearLayout.LayoutParams(0, 1, 1));
        if (!localTab) sourceRow.addView(iconButton(AppIcon.Kind.REFRESH, "刷新在线预设", () -> requestCatalog(false)));
        top.addView(sourceRow);
        if(localTab&&personal!=null)top.addView(personal.status(colors),new LinearLayout.LayoutParams(-1,-2));
        if (recoveryBlocked) {
            TextView warning = text("上次导入的结果需要核对，请先读取相机，避免重复导入。", 14, false);
            warning.setTextColor(colors.error); top.addView(warning);
            top.addView(button("核对上次导入 · 只读", AppIcon.Kind.REFRESH, false, this::recoverImport));
        }
        if (!message.isEmpty()) { TextView note = text(message, 13, false); note.setPadding(0, dp(8), 0, dp(4)); top.addView(note); }
        body.addView(top);
        HorizontalScrollView filters = new HorizontalScrollView(this); filters.setHorizontalScrollBarEnabled(false);
        filters.setClipToPadding(false); filters.setPadding(dp(18), dp(6), dp(18), dp(10));
        LinearLayout chips = horizontal(); filters.addView(chips, new HorizontalScrollView.LayoutParams(-2, -2));
        addPresetCategoryChip(chips, PresetCategories.ALL, selected);
        for (String category : PresetCategories.SUGGESTED) if (categories.remove(category)) addPresetCategoryChip(chips, category, selected);
        boolean hasUncategorized = categories.remove("");
        for (String category : categories) addPresetCategoryChip(chips, category, selected);
        if (hasUncategorized) addPresetCategoryChip(chips, "", selected);
        body.addView(filters, new LinearLayout.LayoutParams(-1, -2));
        if (visiblePresets.isEmpty()) { renderPresetEmpty(); return; }
        int width = Math.min(getResources().getConfiguration().screenWidthDp, 720);
        int columns = width >= 600 ? 3 : getResources().getConfiguration().fontScale >= 1.8f ? 1 : 2;
        presetCardWidth = (width - 36 - 12 * (columns - 1)) / columns;
        list = new GridView(this); list.setNumColumns(columns); list.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        list.setHorizontalSpacing(dp(12)); list.setVerticalSpacing(dp(14)); list.setClipToPadding(false);
        list.setPadding(dp(18), 0, dp(18), dp(108)); list.setSelector(android.R.color.transparent);
        presetGridKey = (localTab ? "local:" : "online:") + selected;
        adapter = new PresetAdapter(); list.setAdapter(adapter);
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView view, int state) {
                presetGridScrolling = state != SCROLL_STATE_IDLE;
                postVisiblePresetPreviews();
            }
            @Override public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
                postVisiblePresetPreviews();
                if (!localTab && total > 0 && visible > 0 && first + visible >= total - 4 && catalogHasMore && !loadingCatalog) loadCatalogPage(true);
            }
        });
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        Parcelable position = presetPositions.get(presetGridKey);
        if (position != null) list.onRestoreInstanceState(position);
        postVisiblePresetPreviews();
    }

    private void addPresetCategoryChip(LinearLayout parent, String category, String selected) {
        String label = PresetCategories.ALL.equals(category) ? category : PresetCategories.label(category);
        boolean active = category.equals(selected);
        TextView chip = text(label, 13, active); chip.setGravity(Gravity.CENTER); chip.setMinHeight(dp(44));
        chip.setMaxWidth(dp(230)); chip.setSingleLine(true); chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setPadding(dp(14), dp(9), dp(14), dp(9));
        chip.setTextColor(active ? colors.primary : colors.secondary);
        chip.setBackground(ripple(active ? colors.primaryContainer : colors.surface));
        chip.setSelected(active); chip.setFocusable(true); chip.setContentDescription("预设分类：" + label);
        chip.setOnClickListener(v -> selectPresetCategory(category));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2); params.rightMargin = dp(4); parent.addView(chip, params);
    }

    private void selectPresetCategory(String category) {
        if (waitForCurrentTask()) return;
        if (localTab) localCategoryFilter = category; else onlineCategoryFilter = category;
        if (!localTab) { catalog.clear(); catalogLoaded = false; catalogPage = 0; catalogHasMore = false; loadCatalog(false); }
        else render();
    }

    private static String presetCategory(Object value) {
        return value instanceof PresetRecord ? ((PresetRecord) value).category : ((OnlinePreset) value).category;
    }

    private void renderPresetEmpty() {
        LinearLayout holder = vertical(); holder.setPadding(dp(18), dp(12), dp(18), dp(20));
        if (localTab) empty(holder, "把喜欢的色彩带在身边", "添加 NCP、NP3，或从在线预设库下载喜欢的色彩。", "添加本地预设", () -> pickNcp(false));
        else if (loadingCatalog) empty(holder, "正在加载在线预设", "下载后的预设会保存在“我的预设”。", null, null);
        else if (online.getEndpoint().isEmpty()) empty(holder, "在线预设暂不可用", "请稍后重新加载，已下载的本地预设仍可使用。", "重新加载", () -> requestCatalog(false));
        else empty(holder, "暂时没有在线预设", catalogMessage, "重新加载", () -> requestCatalog(false));
        if (!localTab && !loadingCatalog && !online.getEndpoint().isEmpty()) holder.addView(button("查看缓存目录", AppIcon.Kind.PRESETS, false, () -> requestCatalog(true)));
        ScrollView scroll = new ScrollView(this); scroll.addView(holder); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private View connectionIndicator(boolean forAlbum) {
        boolean connected = currentConnectionReady(forAlbum);
        LinearLayout status = horizontal(); status.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this); dot.setBackground(shape(connected ? colors.success : colors.secondary, 4));
        status.addView(dot, new LinearLayout.LayoutParams(dp(6), dp(6)));
        String model=album.hasCameraConnection()?album.connectionModel():hasPreparedWireless()?preparedWirelessModel:usbModel;
        if(model==null||model.trim().isEmpty())model="相机";
        TextView label = text(connected ? model+" · 已连接" : connections.isPreparingAlbum() || album.isConnecting() ? "连接中" : "未连接", 11, false);
        label.setMaxLines(1);label.setEllipsize(TextUtils.TruncateAt.END);label.setMaxWidth(dp(Math.min(190,getResources().getConfiguration().screenWidthDp/2)));
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
        if (forAlbum) return album.hasCameraConnection() || hasPreparedWireless();
        return album.hasCameraConnection() || hasPreparedWireless() || verifiedUsb() != null;
    }

    private boolean hasPreparedWireless() {
        WirelessPictureControl owner = preparedWireless;
        return owner != null && owner.isPrepared();
    }

    private void syncConnectionState() {
        if (destroyed || connections == null) return;
        boolean shellChanged = false;
        CameraConnectionRuntime.HeldConnection held = connections.held();
        preparedWireless = held == null ? null : held.owner;
        preparedWirelessHost = held == null ? "" : held.host;
        preparedWirelessPort = held == null ? 0 : held.port;
        preparedWirelessModel = held == null ? "" : held.model;
        if (connections.noticeRevision() != connectionNoticeRevision) {
            connectionNoticeRevision = connections.noticeRevision();
            String latestNotice = connections.notice();
            shellChanged = !message.equals(latestNotice);
            message = latestNotice;
            if (!message.isEmpty() && !message.equals(shownConnectionNotice)) {
                shownConnectionNotice = message;
                feedback("相机连接提示", message);
            }
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
                shellChanged = true;
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
            } else if (confirmation != null && confirmation.connection.wireless() && !hasPreparedWireless()) {
                shellChanged = true;
                PendingConfirmation plan = confirmation; confirmation = null;
                importOutcome = new ImportOutcome("相机连接已断开", "本次尚未写入，请重新连接后读取槽位。",
                        plan.preset.displayName, plan.model, plan.target(), false, false);
            }
            // Automatic preset work also publishes runtime events for every queued item and lease.
            // AlbumPanel's live/preset listeners update those views in place; rebuilding the shell
            // here would reset the hero image, progress indicator and scroll position on each event.
            ConnectionUiState current = new ConnectionUiState(connections, album, preparedWireless);
            if (shellChanged || !current.sameAs(renderedConnectionState)) render();
        }
        resumePendingImport();
        resumePendingPhotoLive();
    }

    private void requestConnectionNotification() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 105);
        }
    }

    private void preparePhotoSave(Runnable save) {
        if (destroyed || isFinishing() || save == null) return;
        if (waitForCurrentTask()) return;
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            if (pendingPhotoSave != null) { feedback("正在等待存储权限", "请先完成系统存储权限选择，再继续保存照片。"); return; }
            pendingPhotoSave = save;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, SAVE_PHOTOS_PERMISSION);
            return;
        }
        final CameraConnectionRuntime.OperationLease lease;
        try { requestConnectionNotification(); lease = connections.beginPhotoOperation(); }
        catch (RuntimeException unavailable) { feedback("照片任务未开始", readable(unavailable)); return; }
        try { save.run(); }
        finally { lease.close(); }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_WIFI_PERMISSION) {
            Runnable connect = pendingWifiConnect;
            Consumer<String> failure = pendingWifiFailure;
            pendingWifiConnect = null; pendingWifiFailure = null;
            if (destroyed || connect == null || failure == null) return;
            if (cameraWifiPermissionGranted()) ensureCameraWifi(connect, failure);
            else failure.accept("未允许相机 Wi-Fi 权限。请在系统应用权限中允许后，再连接相机。");
            return;
        }
        if (requestCode != SAVE_PHOTOS_PERMISSION) return;
        Runnable save = pendingPhotoSave; pendingPhotoSave = null;
        if (destroyed || save == null) return;
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            preparePhotoSave(save);
        } else feedback("未保存到系统相册", "请允许存储权限后重试。已下载的原图仍保留在 App 内。");
    }

    private void continueAlbumConnection(Runnable connect) {
        if (destroyed) return;
        final CameraConnectionRuntime.OperationLease lease;
        try { requestConnectionNotification(); lease = connections.beginOperation(); }
        catch (RuntimeException failure) { album.connectionStartFailed(readable(failure), connect); return; }
        releasePreparedWireless(preparedWireless);
        connections.cameraWorker.execute(() -> runOnUiThread(() -> {
            try { if (lease.isActive() && !destroyed) connect.run(); else album.connectionStartFailed("连接已取消，请重新连接。", connect); }
            finally { lease.close(); }
        }));
    }

    private boolean cameraWifiPermissionGranted() {
        String permission = Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.NEARBY_WIFI_DEVICES
                : android.Manifest.permission.ACCESS_FINE_LOCATION;
        return checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Request the specific camera network before opening either PTP channel. */
    private void ensureCameraWifi(Runnable connect, Consumer<String> failure) {
        if (destroyed) return;
        if (Build.VERSION.SDK_INT < 29 || connections.wifiSession.isConnected()) { connect.run(); return; }
        if (pendingWifiConnect != null || cameraWifiDialog != null || connections.wifiSession.isConnecting()) {
            failure.accept("正在等待相机 Wi-Fi 连接，请先完成或取消当前连接。"); return;
        }
        if (!cameraWifiPermissionGranted()) {
            pendingWifiConnect = connect; pendingWifiFailure = failure;
            String[] permissions = Build.VERSION.SDK_INT >= 33
                    ? new String[]{android.Manifest.permission.NEARBY_WIFI_DEVICES}
                    : new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION};
            requestPermissions(permissions, CAMERA_WIFI_PERMISSION);
            return;
        }
        if (Build.VERSION.SDK_INT < 33) {
            android.location.LocationManager location = getSystemService(android.location.LocationManager.class);
            if (location != null && !location.isLocationEnabled()) {
                failure.accept("系统要求开启位置信息后才能连接相机 Wi-Fi；本应用不读取位置。");
                feedback("请开启系统位置信息", "Android 版本要求开启此开关才能连接附近 Wi-Fi。", "去开启", () -> {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)); }
                    catch (RuntimeException ignored) { }
                });
                return;
            }
        }
        final int ticket = ++wifiUiEpoch;
        LinearLayout fields = vertical(); fields.setPadding(dp(22), dp(6), dp(22), dp(8));
        TextView explanation = text("开启相机热点，填写相机屏幕上的网络名称与密码，再允许系统连接。", 14, false);
        explanation.setTextColor(colors.secondary); fields.addView(explanation);
        label(fields, "Wi-Fi 名称", 13, colors.text);
        EditText network = cameraWifiInput(getSharedPreferences("camera-wifi-ui", MODE_PRIVATE).getString("ssid", ""), "相机显示的网络名称", false);
        fields.addView(network, new LinearLayout.LayoutParams(-1, dp(52)));
        label(fields, "Wi-Fi 密码", 13, colors.text);
        EditText password = cameraWifiInput("", "相机显示的密码", true);
        fields.addView(password, new LinearLayout.LayoutParams(-1, dp(52)));
        CheckBox open = new CheckBox(this); open.setText("此网络没有密码"); open.setTextSize(13); open.setTextColor(colors.secondary);
        open.setMinHeight(dp(48)); fields.addView(open);
        open.setOnCheckedChangeListener((view, checked) -> { password.setEnabled(!checked); if (checked) password.setText(""); });
        TextView help = text("密码仅用于本次连接，不保存到本机。", 12, false); help.setTextColor(colors.secondary); fields.addView(help);
        TextView validation = text("", 13, false); validation.setTextColor(colors.error); validation.setPadding(0, dp(10), 0, 0); validation.setVisibility(View.GONE); fields.addView(validation);
        ScrollView scroll = new ScrollView(this); scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("连接相机 Wi-Fi").setView(scroll)
                .setNegativeButton("取消", (d, which) -> finishCameraWifiUi(ticket, () -> failure.accept("已取消相机 Wi-Fi 连接。")))
                .setPositiveButton("连接", null).create();
        cameraWifiDialog = dialog;
        dialog.setOnCancelListener(d -> finishCameraWifiUi(ticket, () -> failure.accept("已取消相机 Wi-Fi 连接。")));
        dialog.setOnDismissListener(d -> { password.setText(""); if (cameraWifiDialog == dialog) cameraWifiDialog = null; });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ssid = network.getText().toString(), pass = open.isChecked() ? "" : password.getText().toString();
            if (ssid.isEmpty() || ssid.getBytes(StandardCharsets.UTF_8).length > 32) {
                validation.setText("请填写相机显示的 Wi-Fi 名称（最多 32 字节）。"); validation.setVisibility(View.VISIBLE); network.requestFocus(); return;
            }
            if (!open.isChecked() && (pass.length() < 8 || pass.length() > 63 || !pass.matches("[\\x20-\\x7E]+"))) {
                validation.setText("Wi-Fi 密码需为 8–63 个英文字符或数字；没有密码时请勾选下方选项。"); validation.setVisibility(View.VISIBLE); password.requestFocus(); return;
            }
            try { wifiRequestLease = connections.beginOperation(); }
            catch (RuntimeException unavailable) { validation.setText("无法启动连接保护，请重新打开 App 后重试。"); validation.setVisibility(View.VISIBLE); return; }
            // Persist only the network name; no credential is written to preferences or saved view state.
            getSharedPreferences("camera-wifi-ui", MODE_PRIVATE).edit().putString("ssid", ssid).apply();
            dialog.dismiss();
            AlertDialog waiting = new AlertDialog.Builder(this).setTitle("正在连接相机 Wi-Fi")
                    .setMessage("请在系统提示中允许连接，并保持相机 Wi-Fi 开启。")
                    .setNegativeButton("取消连接", (d2, which) -> cancelCameraWifiUi(ticket, failure)).create();
            waiting.setOnCancelListener(d2 -> cancelCameraWifiUi(ticket, failure));
            cameraWifiDialog = waiting; waiting.show();
            try {
                long token = connections.wifiSession.connect(ssid, pass, new CameraWifiSession.Listener() {
                    @Override public void onConnected() { finishCameraWifiUi(ticket, connect); }
                    @Override public void onFailure(String error) { finishCameraWifiUi(ticket, () -> failure.accept(error)); }
                });
                if (ticket == wifiUiEpoch) wifiSessionToken = token;
                else connections.wifiSession.cancel(token);
            } catch (RuntimeException invalid) {
                finishCameraWifiUi(ticket, () -> failure.accept(invalid instanceof IllegalArgumentException
                        ? "Wi-Fi 名称或密码格式不正确，请核对相机屏幕上的信息。" : "无法发起系统 Wi-Fi 连接，请重试。"));
            }
        }));
        dialog.show();
    }

    private EditText cameraWifiInput(String value, String hint, boolean password) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setTextSize(15);
        input.setTextColor(colors.text); input.setHintTextColor(colors.secondary);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSaveEnabled(false); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setPadding(dp(14), dp(8), dp(14), dp(8)); input.setBackground(shape(colors.container, 14));
        input.setHint(hint); input.setText(value); return input;
    }

    private void finishCameraWifiUi(int ticket, Runnable task) {
        if (destroyed || ticket != wifiUiEpoch) return;
        wifiUiEpoch++; wifiSessionToken = 0;
        AlertDialog dialog = cameraWifiDialog; cameraWifiDialog = null;
        if (dialog != null) dialog.dismiss();
        if (wifiRequestLease != null) { wifiRequestLease.close(); wifiRequestLease = null; }
        task.run();
    }

    private void cancelCameraWifiUi(int ticket, Consumer<String> failure) {
        long token = wifiSessionToken;
        finishCameraWifiUi(ticket, () -> { connections.wifiSession.cancel(token); failure.accept("已取消相机 Wi-Fi 连接。"); });
    }

    private void cancelPendingCameraWifi() {
        wifiUiEpoch++; pendingWifiConnect = null; pendingWifiFailure = null;
        long token = wifiSessionToken; wifiSessionToken = 0;
        if (connections != null) connections.wifiSession.cancel(token);
        AlertDialog dialog = cameraWifiDialog; cameraWifiDialog = null;
        if (dialog != null) dialog.dismiss();
        if (wifiRequestLease != null) { wifiRequestLease.close(); wifiRequestLease = null; }
    }

    private boolean canStartPhotoLive() { return canStartPhotoLive(""); }
    private boolean canStartPhotoLive(String projectId) {
        if (waitForCurrentTask()) return false;
        if (recoveryBlocked) { showRecoveryRequired("开始照片直播"); return false; }
        if (connections.hasOperations()) { feedback("相机任务进行中", "请等待当前读取或导入完成后开始照片直播。"); return false; }
        if (!album.hasCameraConnection() && !hasPreparedWireless()) {
            if (projectId.isEmpty()) showPhotoLiveConnectionRequired(); else showPhotoLiveProjectConnectionRequired(projectId);
            return false;
        }
        return !destroyed;
    }

    private void beginPhotoLive(Runnable start) { beginPhotoLive(start, ""); }
    private void beginPhotoLive(Runnable start, String projectId) {
        if (!canStartPhotoLive(projectId)) return;
        try (CameraConnectionRuntime.OperationLease lease = connections.beginOperation()) {
            if (lease.isActive()) start.run();
            else feedback("照片直播未开始", "相机任务已取消，请检查连接后重新开始。");
        } catch (RuntimeException failure) { feedback("照片直播未开始", readable(failure)); }
    }

    private void preparePhotoLive(Runnable start) { preparePhotoLive(start, ""); }
    private void preparePhotoLive(Runnable start, String projectId) {
        if (!canStartPhotoLive(projectId)) return;
        if (pendingProtectedPhotoLive != null) { feedback("正在等待后台运行授权", "请先完成系统里的后台运行设置，返回后再继续开始照片直播。"); return; }
        if (ConnectionProtection.batteryExempt(this)) { beginPhotoLive(start, projectId); return; }
        new AlertDialog.Builder(this).setTitle("开启锁屏接收")
                .setMessage("允许后台运行后，照片直播才能在深度待机时继续接收。连接期间会保持通知和后台运行，耗电会增加。")
                .setPositiveButton("开启保护", (dialog, which) -> {
                    pendingProtectedPhotoLive = start;
                    pendingProtectedLiveProjectId = projectId;
                    try { startActivityForResult(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())), PROTECT_PHOTO_LIVE); }
                    catch (RuntimeException failure) {
                        try { startActivityForResult(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName())), PROTECT_PHOTO_LIVE); }
                        catch (RuntimeException unavailable) { pendingProtectedPhotoLive = null; pendingProtectedLiveProjectId = ""; feedback("请在设置中允许后台运行", "在系统应用电池设置中选择不限制，再返回开始直播。"); }
                    }
                })
                .setNeutralButton("先在前台使用", (dialog, which) -> beginPhotoLive(start, projectId))
                .setNegativeButton("取消", null).show();
    }

    private void showBackgroundProtection() {
        boolean unrestricted = ConnectionProtection.batteryExempt(this);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("后台连接保护")
                .setMessage((unrestricted ? "已允许后台运行，连接期间会持续维护会话、接收照片，锁屏不会主动断开。" : "尚未允许后台运行。深度待机可能暂停网络，影响锁屏接收照片。")
                        + "\n\n" + (ConnectionProtection.notificationsVisible(this) ? "连接通知已开启。" : "连接通知未开启，可在通知设置中打开。")
                        + "\n\n部分手机还需在系统应用电池设置中选择不限制，并允许后台活动。连接保护会增加耗电。")
                .setNegativeButton("关闭", null).setNeutralButton("通知设置", (d, w) -> {
                    try { startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())); }
                    catch (RuntimeException failure) { requestConnectionNotification(); }
                });
        if (!unrestricted) dialog.setPositiveButton("允许后台运行", (d, w) -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException failure) { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))); }
        });
        dialog.show();
    }

    private boolean canChangeConnection() {
        if (waitForCurrentTask()) return false;
        if (connections.photoPresets.state().active) { feedback("正在套用预设", "可继续浏览照片；请完成或取消批量任务后，再更改相机连接或导入预设。"); return false; }
        if (connections.hasOperations() && !busy) { feedback("相机任务进行中", "请等待当前读取或导入完成后继续。"); return false; }
        if (album.isTransferBusy()) { feedback("照片任务进行中", "请等待当前接收、下载、保存或删除完成，再更改相机连接或导入预设。"); return false; }
        return true;
    }

    private void renderSettings() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content = vertical(); content.setPadding(dp(22), 0, dp(22), dp(112)); scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        TextView intro = text("相机连接与应用偏好", 12, false); intro.setTextColor(colors.secondary); intro.setPadding(0, 0, 0, dp(22)); content.addView(intro);
        boolean connected = currentConnectionReady(false);
        boolean wireless = album.hasCameraConnection() || hasPreparedWireless();
        LinearLayout camera = vertical(); camera.setPadding(dp(18), dp(18), dp(18), dp(8)); camera.setBackground(shape(colors.card, 26));
        LinearLayout identity = horizontal();
        ImageView glyph = new ImageView(this); glyph.setImageDrawable(new AppIcon(AppIcon.Kind.CAMERA, colors.secondary, getResources().getDisplayMetrics().density));
        glyph.setPadding(dp(10), dp(10), dp(10), dp(10)); glyph.setBackground(shape(colors.surface, 15)); identity.addView(glyph, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout copy = vertical(); copy.setPadding(dp(12), 0, 0, 0);
        TextView eyebrow = text(connected ? "当前相机" : "相机", 11, false); eyebrow.setTextColor(colors.secondary); copy.addView(eyebrow);
        String model = connected ? (wireless ? album.hasCameraConnection() ? album.connectionModel() : preparedWirelessModel : usbModel) : "尚未连接相机";
        TextView modelLabel = text(model == null || model.isEmpty() ? "Nikon 相机" : model, 19, true); modelLabel.setPadding(0, dp(4), 0, 0); copy.addView(modelLabel);
        identity.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); camera.addView(identity);
        if (connected) identity.addView(iconButton(AppIcon.Kind.INFO, "相机详情", this::showCameraDetails));
        LinearLayout status = horizontal(); status.setPadding(0, dp(10), 0, dp(8)); status.addView(connectionIndicator(false), new LinearLayout.LayoutParams(0, -2, 1));
        if (connected) { TextView mode = text(wireless ? "Wi-Fi" : "USB", 11, false); mode.setTextColor(colors.secondary); mode.setPadding(dp(9), dp(5), dp(9), dp(5)); mode.setBackground(shape(colors.surface, 16)); status.addView(mode); }
        camera.addView(status); divider(camera);
        LinearLayout footer = horizontal();
        TextView note = text(connected ? (wireless ? "相机连接已保持" : "USB 用于预设导入") : album.isConnecting() ? "正在连接相机…" : connections.isPreparingAlbum() ? "正在读取相机照片…" : "选择下方连接方式", 11, false); note.setTextColor(colors.secondary); note.setPadding(0, dp(12), dp(6), dp(12)); footer.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
        if (connected || connections.wifiSession.isActive()) {
            TextView disconnect = text("断开连接", 12, false); disconnect.setTextColor(colors.primary); disconnect.setGravity(Gravity.CENTER); disconnect.setMinHeight(dp(48)); disconnect.setPadding(dp(8), 0, 0, 0); disconnect.setFocusable(true); disconnect.setBackground(ripple(colors.card));
            disconnect.setOnClickListener(v -> { if (!canChangeConnection()) return; clearPendingPhotoLive(); verifiedUsbId = -1; usbModel = ""; connections.disconnect(); preparedWireless = null; message = "相机已断开，已下载照片仍可查看。"; render(); feedback("相机已断开", "已下载的照片仍可离线查看。"); }); footer.addView(disconnect);
        }
        camera.addView(footer); content.addView(camera);
        if (settingsReturnToPreset) content.addView(button("返回预设", AppIcon.Kind.BACK, false, this::returnToPreset));
        if (photoLiveContinuation.isPending()) content.addView(button("返回照片直播", AppIcon.Kind.BACK, false, this::goBack));
        if (!message.isEmpty()) { TextView notice = label(content, message, 13, colors.secondary); notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); }
        settingsHeading(content, "相机连接");
        LinearLayout connections = settingsGroup(content);
        settingsRow(connections, AppIcon.Kind.WIFI, "Wi-Fi 无线连接", "浏览照片、导入预设", connected && wireless ? "使用中" : "", this::openCameraSettings);
        settingsRow(connections, AppIcon.Kind.USB, "USB 数据线连接", "导入预设", connected && !wireless ? "使用中" : "", this::connectUsbSettings);
        settingsHeading(content, "账号");
        LinearLayout identityGroup = settingsGroup(content);
        settingsRow(identityGroup, AppIcon.Kind.PERSON, account.summaryTitle(), account.summarySubtitle(), account.summaryValue(), account::open);
        settingsRow(identityGroup, AppIcon.Kind.PRESETS, "我的投稿", "管理上传的预设与审核结果", "", community::openSubmissions);
        LinearLayout inbox = settingsRow(identityGroup, AppIcon.Kind.INFO, "消息", "回复与投稿审核通知", "", community::openMessages);
        settingsMessageEntry = inbox;
        settingsMessageBadge = text("", 11, true); settingsMessageBadge.setGravity(Gravity.CENTER);
        settingsMessageBadge.setTextColor(colors.dark ? colors.surface : Color.WHITE);
        settingsMessageBadge.setBackground(shape(colors.error, 12)); settingsMessageBadge.setPadding(dp(6), 0, dp(6), 0);
        settingsMessageBadge.setMinimumWidth(dp(22)); settingsMessageBadge.setVisibility(View.GONE);
        LinearLayout.LayoutParams badgeSize = new LinearLayout.LayoutParams(-2, dp(22)); badgeSize.setMarginEnd(dp(8));
        inbox.addView(settingsMessageBadge, inbox.getChildCount() - 1, badgeSize);
        settingsHeading(content, "应用");
        LinearLayout application = settingsGroup(content);
        settingsRow(application, AppIcon.Kind.ADJUST, "外观", "", AppAppearance.label(this), this::chooseAppearance);
        settingsRow(application, AppIcon.Kind.WIFI, "后台连接保护", "切到后台或锁屏时继续接收", ConnectionProtection.settingsLabel(this), this::showBackgroundProtection);
        settingsRow(application, AppIcon.Kind.ALBUM, "预览模板", "用于预设主图的近似模拟", presetPreviews.templateLabel(), this::choosePreviewTemplate);
        settingsRow(application, AppIcon.Kind.HELP, "使用帮助", "", "", this::showHelp);
        settingsRow(application, AppIcon.Kind.DOWNLOAD, "检查更新", "查看新版本与更新说明", appVersion(), updates::open);
        settingsRow(application, AppIcon.Kind.INFO, "关于", "", "", () -> notice(getString(R.string.app_name), "版本 " + appVersion() + "\n\n管理本地预设、模拟色调、连接尼康相机、浏览和下载照片。可在设置中注册或登录账号，也可继续以游客使用。无线支持范围取决于实际机型与接口。模板模拟为手机近似预览，不代表相机实拍。"));
    }

    private void renderCameraConnectionGuide() {
        boolean importing = settingsReturnToPreset && !pendingImportId.isEmpty();
        if (importing) {
            TextView context = text("连接成功后，继续选择预设导入槽位。", 13, false);
            context.setTextColor(colors.secondary); context.setPadding(dp(24), dp(4), dp(24), dp(10));
            body.addView(context);
        }
        body.addView(album.createView(), new LinearLayout.LayoutParams(-1, 0, 1));
        if (!importing) return;
        LinearLayout actions = horizontal(); actions.setPadding(dp(24), dp(6), dp(24), dp(10));
        Button usbAction = button("改用 USB", AppIcon.Kind.USB, false, () -> {
            if (album.isConnecting()) { connectUsbSettings(); return; }
            if (!canChangeConnection()) return;
            album.goBack(); connectUsbSettings();
        });
        Button cancel = button("取消导入", AppIcon.Kind.BACK, false, this::returnToPreset);
        LinearLayout.LayoutParams usbParams = new LinearLayout.LayoutParams(0, -2, 1); usbParams.rightMargin = dp(8);
        actions.addView(usbAction, usbParams); actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(actions);
    }

    private void chooseAppearance() {
        if (waitForCurrentTask()) return;
        String[] modes = {AppAppearance.SYSTEM, AppAppearance.LIGHT, AppAppearance.DARK};
        String mode = AppAppearance.mode(this); int checked = AppAppearance.LIGHT.equals(mode) ? 1 : AppAppearance.DARK.equals(mode) ? 2 : 0;
        new AlertDialog.Builder(this).setTitle("外观").setSingleChoiceItems(new String[]{"跟随系统", "浅色", "深色"}, checked, (dialog, which) -> {
            AppAppearance.setMode(this, modes[which]); dialog.dismiss(); render();
        }).setNegativeButton("取消", null).show();
    }

    private void choosePreviewTemplate() {
        if (waitForCurrentTask()) return;
        new AlertDialog.Builder(this).setTitle("预览模板").setItems(new String[]{"查看当前模板", "从手机选择模板图", "使用内置模板"}, (dialog, which) -> {
            if (which == 0) showTemplateOriginal();
            else if (which == 1) startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_TEMPLATE);
            else updatePreviewTemplate(null);
        }).setNegativeButton("取消", null).show();
    }

    private void updatePreviewTemplate(Uri uri) {
        if (waitForCurrentTask()) return;
        startBusy(uri == null ? "正在恢复内置模板…" : "正在处理模板图…");
        images.execute(() -> {
            try {
                if (uri == null) presetPreviews.resetTemplate(); else presetPreviews.setTemplate(uri);
                onUi(() -> { imageCache.evictAll(); failedImages.clear(); previewFailures.clear(); unsupportedPreviews.clear(); finishBusy("预览模板已更新，预设主图将重新生成。"); });
            } catch (Exception e) { onUi(() -> { finishBusy("模板未更新，原模板仍可使用。"); notice("无法更新模板", readable(e)); }); }
        });
    }

    private void showTemplateOriginal() {
        if (waitForCurrentTask()) return;
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

    private LinearLayout settingsRow(LinearLayout parent, AppIcon.Kind kind, String title, String subtitle, String value, Runnable action) {
        if (parent.getChildCount() > 0) { View line = new View(this); line.setBackgroundColor(colors.outline); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1)); p.leftMargin = dp(58); parent.addView(line, p); }
        LinearLayout row = horizontal(); row.setPadding(dp(14), dp(11), dp(14), dp(11)); row.setMinimumHeight(dp(66)); row.setBackground(ripple(colors.card)); row.setFocusable(true); row.setOnClickListener(v -> { if (!waitForCurrentTask()) action.run(); });
        ImageView icon = new ImageView(this); icon.setImageDrawable(new AppIcon(kind, kind == AppIcon.Kind.WIFI ? colors.primary : colors.secondary, getResources().getDisplayMetrics().density)); icon.setPadding(dp(7), dp(7), dp(7), dp(7)); icon.setBackground(shape(kind == AppIcon.Kind.WIFI ? colors.primaryContainer : colors.surface, 10)); row.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout copy = vertical(); copy.setPadding(dp(11), 0, dp(8), 0); copy.addView(text(title, 14, false));
        if (!subtitle.isEmpty()) { TextView sub = text(subtitle, 11, false); sub.setTextColor(colors.secondary); sub.setPadding(0, dp(3), 0, 0); copy.addView(sub); } row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        if (!value.isEmpty()) { TextView trailing = text(value, 11, false); trailing.setTextColor(colors.secondary); row.addView(trailing); }
        else { ImageView chevron = new ImageView(this); chevron.setImageDrawable(new AppIcon(AppIcon.Kind.CHEVRON, colors.secondary, getResources().getDisplayMetrics().density)); row.addView(chevron, new LinearLayout.LayoutParams(dp(14), dp(14))); }
        row.setContentDescription(title + (subtitle.isEmpty() ? "" : "，" + subtitle) + (value.isEmpty() ? "" : "，" + value)); parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }

    private void openCameraSettings() {
        if (!canChangeConnection()) return;
        if (album.hasCameraConnection()) album.configureConnection(album.connectionHost(), album.connectionPort());
        else if (hasPreparedWireless()) album.configureConnection(preparedWirelessHost, preparedWirelessPort);
        settingsTab = true; albumTab = false; wirelessGuide = false; message = "";
        album.openConnectionSettings(); render();
    }

    private void connectUsbSettings() {
        if (waitForCurrentTask()) return;
        if (photoLiveContinuation.isPending()) {
            feedback("照片直播需要 Wi-Fi", "连接相机 Wi-Fi 后，继续新建拍摄项目。", "去连接", this::openCameraSettings); return;
        }
        if (album.isConnecting()) { feedback("正在连接相机", "请等待当前 Wi-Fi 连接完成，或先返回并取消连接，再改用 USB。"); return; }
        if (!canChangeConnection()) return;
        List<UsbDevice> devices = UsbProbe.findNikonDevices(usb);
        if (devices.isEmpty()) {
            feedback("未检测到 USB 相机", "请开启相机，用数据线连接手机并打开 OTG。", "重试", this::connectUsbSettings); return;
        }
        if (devices.size() == 1) requestUsbSettings(devices.get(0));
        else { String[] names = new String[devices.size()]; for (int i = 0; i < names.length; i++) names[i] = "Nikon USB · " + String.format(Locale.ROOT, "%04X", devices.get(i).getProductId()); new AlertDialog.Builder(this).setTitle("选择相机").setItems(names, (d, which) -> requestUsbSettings(devices.get(which))).setNegativeButton("取消", null).show(); }
    }

    private void requestUsbSettings(UsbDevice device) {
        if (waitForCurrentTask()) return;
        if (album.isConnecting()) { feedback("正在连接相机", "请等待当前连接完成，或先取消连接，再连接 USB 相机。"); return; }
        if (!canChangeConnection()) return;
        pendingConnectionAccepted = !pendingImportId.isEmpty();
        connections.disconnect(); preparedWireless = null;
        verifiedUsbId = -1; usbModel = ""; startBusy("正在读取 USB 相机…");
        feedback("正在连接相机", "正在检查 USB 连接，请稍候。");
        if (usb.hasPermission(device)) scanSlots(device, false, true);
        else {
            permissionDevice = device; permissionRecovery = false; permissionForSettings = true;
            int flags = PendingIntent.FLAG_UPDATE_CURRENT; if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            try { usb.requestPermission(device, PendingIntent.getBroadcast(this, device.getDeviceId(), new Intent(USB_PERMISSION).setPackage(getPackageName()), flags)); }
            catch (RuntimeException e) { permissionDevice = null; permissionForSettings = false; finishBusy("无法申请相机访问权限：" + readable(e)); }
        }
    }

    private GridView list;
    private PresetAdapter adapter;
    private final List<Object> visiblePresets = new ArrayList<>();
    private String localCategoryFilter = PresetCategories.ALL, onlineCategoryFilter = PresetCategories.ALL;
    private String presetGridKey = "";
    private int presetCardWidth = 170;
    private final Map<String, Parcelable> presetPositions = new HashMap<>();
    private ScrollView detailScroll;
    private String detailScrollKey = "";
    private final Map<String, Integer> detailPositions = new HashMap<>();
    private final Map<String, OnlinePreset> knownDiscussionPresets = new HashMap<>();
    private LinearLayout detailCommentsMount;
    private final Set<String> discussionRecoveryPending = new HashSet<>();
    private final Map<String, String> discussionRecoveryMessages = new HashMap<>();

    private void rememberDetailPosition() {
        if (detailScroll != null && detailScroll.isAttachedToWindow() && !detailScrollKey.isEmpty())
            detailPositions.put(detailScrollKey, detailScroll.getScrollY());
    }

    private String discussionPresetId(PresetRecord record) {
        if (record == null) return "";
        OnlinePreset known = knownDiscussionPresets.get(record.sha256);
        if (known != null) return known.id;
        String saved = getSharedPreferences("downloaded-preset-discussions", MODE_PRIVATE).getString(record.sha256, "");
        return saved != null && saved.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}") ? saved : "";
    }

    /** Pre-discussion downloads have a SHA but no UUID. Recover only an unambiguous managed-catalog match. */
    private void recoverDownloadedDiscussion(PresetRecord record) {
        if (record == null || !"online".equals(record.source) || detailCommentsMount == null) return;
        showDiscussionRecovery(record, discussionRecoveryMessages.get(record.sha256));
        if (discussionRecoveryMessages.containsKey(record.sha256) || !discussionRecoveryPending.add(record.sha256)) return;
        final String sha = record.sha256;
        final OnlinePresetRepository repository = online;
        images.execute(() -> {
            OnlinePresetRepository.CatalogResult result = repository.cachedCatalog();
            Set<String> cachedIds = new HashSet<>();
            if ("cached".equals(result.status)) for (OnlinePreset item : result.presets)
                if (sha.equals(item.sha256) && record.fileFormat.equals(item.fileFormat)) cachedIds.add(item.id);
            if (!"cached".equals(result.status) || cachedIds.size() != 1) result = repository.fetchCatalog();
            Set<String> matches = new LinkedHashSet<>(); OnlinePreset found = null;
            if ("ok".equals(result.status) || "cached".equals(result.status)) for (OnlinePreset item : result.presets)
                if (sha.equals(item.sha256) && record.fileFormat.equals(item.fileFormat)
                        && item.id.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) { matches.add(item.id); found = item; }
            final OnlinePreset match = matches.size() == 1 ? found : null;
            final String note = match != null ? "" : matches.size() > 1
                    ? "这份文件对应多个在线预设，请从在线预设库打开对应条目查看评论。"
                    : "ok".equals(result.status) || "cached".equals(result.status)
                    ? "暂未找到这份文件对应的在线预设，可从在线预设库打开对应条目查看评论。"
                    : "评论暂时无法加载，请检查网络后重试。";
            onUi(() -> {
                discussionRecoveryPending.remove(sha);
                if (match != null) {
                    knownDiscussionPresets.put(sha, match);
                    getSharedPreferences("downloaded-preset-discussions", MODE_PRIVATE).edit().putString(sha, match.id).apply();
                    discussionRecoveryMessages.remove(sha);
                } else discussionRecoveryMessages.put(sha, note);
                // Account overlays and a different preset may have replaced this mount while the read ran.
                String current = detailOnline != null ? detailOnline.sha256 : detailId;
                if (detailCommentsMount == null || detailScroll == null || !sha.equals(current)
                        || account.isOpen() || community.isOpen() || albumTab || settingsTab) return;
                if (match != null) {
                    int position = detailScroll.getScrollY(); ScrollView scroll = detailScroll;
                    detailCommentsMount.removeAllViews();
                    detailCommentsMount.addView(community.createInlineComments(match.id, colors), new LinearLayout.LayoutParams(-1, -2));
                    scroll.post(() -> { if (scroll == detailScroll) scroll.scrollTo(0, position); });
                } else showDiscussionRecovery(record, note);
            });
        });
    }

    private void showDiscussionRecovery(PresetRecord record, String note) {
        if (detailCommentsMount == null) return;
        detailCommentsMount.removeAllViews();
        TextView copy = label(detailCommentsMount, note == null ? "正在加载评论…" : note, 13, colors.secondary);
        copy.setPadding(0, dp(24), 0, dp(8)); copy.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (note != null) detailCommentsMount.addView(textLink("重新加载评论", () -> {
            discussionRecoveryMessages.remove(record.sha256); recoverDownloadedDiscussion(record);
        }));
    }

    private void rememberPresetPosition() {
        if (list == null || !list.isAttachedToWindow() || presetGridKey.isEmpty()) return;
        Parcelable position = list.onSaveInstanceState();
        if (position != null) presetPositions.put(presetGridKey, position);
    }

    private final class PresetAdapter extends BaseAdapter {
        @Override public int getCount() { return visiblePresets.size(); }
        @Override public Object getItem(int position) { return visiblePresets.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Object value = visiblePresets.get(position);
            OnlinePreset item = value instanceof OnlinePreset ? (OnlinePreset) value : null;
            PresetRecord record = value instanceof PresetRecord ? (PresetRecord) value : item == null ? null : library.findBySha256(item.sha256);
            LinearLayout row = vertical(); row.setLayoutParams(new GridView.LayoutParams(-1, -2));
            String name = record == null ? item.name : record.displayName;
            FrameLayout photo = preview(record, item, Math.round(presetCardWidth * 1.25f));
            photo.setBackground(shape(colors.container, 16)); row.addView(photo);
            LinearLayout caption = vertical(); caption.setPadding(dp(10), dp(10), dp(10), dp(4));
            TextView nameView = text(name, 15, true); nameView.setMinLines(2); nameView.setMaxLines(2);
            nameView.setEllipsize(TextUtils.TruncateAt.END); caption.addView(nameView);
            TextView format = text(record == null ? item.fileFormat : record.fileFormat, 11, true);
            format.setTextColor(colors.secondary); caption.addView(format);
            boolean downloaded = record != null;
            LinearLayout meta = horizontal();
            TextView category = text(PresetCategories.label(presetCategory(value)), 11, false); category.setTextColor(colors.secondary);
            category.setMaxLines(1); category.setEllipsize(TextUtils.TruncateAt.END);
            meta.addView(category, new LinearLayout.LayoutParams(0, dp(44), 1)); category.setGravity(Gravity.CENTER_VERTICAL);
            if (item != null) meta.addView(presetDownloadControl(item, true), new LinearLayout.LayoutParams(dp(70), dp(48)));
            caption.addView(meta); row.addView(caption);
            row.setContentDescription(name + "，" + PresetCategories.label(presetCategory(value)) + "，" + (downloaded ? "已在本地" : "可下载") + "，查看预设");
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(colors.primaryContainer), shape(colors.card, 16), null));
            row.setClipToOutline(true); row.setFocusable(true); row.setClickable(true);
            row.setOnClickListener(v -> openPreset(value, false));
            if (record != null) row.setOnLongClickListener(v -> { confirmDeletePreset(record); return true; });
            return row;
        }
    }

    private void openPreset(Object value, boolean download) {
        if (waitForCurrentTask()) return;
        OnlinePreset remote = value instanceof OnlinePreset ? (OnlinePreset) value : null;
        PresetRecord record = value instanceof PresetRecord ? (PresetRecord) value : library.findBySha256(remote.sha256);
        if (remote != null) knownDiscussionPresets.put(remote.sha256, remote);
        detailId = record == null ? "" : record.id; detailOnline = remote; render();
        if (download && remote != null && record == null) downloadPreset(remote);
    }

    private void renderDetail() {
        PresetRecord local = detailOnline == null ? library.findBySha256(detailId) : library.findBySha256(detailOnline.sha256);
        if (local == null && detailOnline == null) { detailId = ""; empty(body, "预设已不可用", "请重新从手机添加这份预设文件。", "返回", this::goBack); return; }
        OnlinePreset remote = detailOnline;
        String fileFormat = local != null ? local.fileFormat : remote.fileFormat;
        boolean np3 = "NP3".equals(fileFormat);
        detailPresetSha = local == null ? remote.sha256 : local.sha256;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); LinearLayout content = vertical();
        scroll.setOnScrollChangeListener((view, x, y, oldX, oldY) -> postVisiblePresetPreviews());
        detailScroll = scroll; detailScrollKey = remote != null ? remote.id : local.sha256;
        Integer savedPosition = detailPositions.get(detailScrollKey);
        if (savedPosition != null) scroll.post(() -> scroll.scrollTo(0, savedPosition));
        content.setPadding(dp(16), dp(4), dp(16), dp(20)); scroll.addView(content); body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content.addView(preview(local, remote, photoHeight(1.25f), true));
        heading(content, local != null ? local.displayName : remote.name, 24);
        label(content, fileFormat, 12, colors.secondary);
        String description = local != null ? local.description : remote.description;
        if (!description.isEmpty()) label(content, description, 15, colors.text);
        if (remote != null) label(content, "分类 · " + PresetCategories.label(remote.category), 13, colors.secondary);
        if (remote != null) {
            label(content, "community".equals(remote.source) ? "用户分享 · " + remote.authorName : "Huevia 收录", 13, colors.secondary);
        }
        if (local != null) content.addView(textLink((remote == null ? "分类 · " : "本地分类 · ") + PresetCategories.label(local.category), () -> choosePresetCategory(local)));
        if (local != null) {
            LinearLayout.LayoutParams curveLayout = new LinearLayout.LayoutParams(-1, -2);
            curveLayout.topMargin = dp(10); curveLayout.bottomMargin = dp(16);
            content.addView(presetCurve(local), curveLayout);
        }
        detailPresetStorage = informationRow(content, AppIcon.Kind.CHECK, local != null ? "已保存在手机" : "在线预设 · 尚未下载", local != null ? fileFormat + " 预设文件" : "可直接预览效果，下载后保留在我的预设");
        divider(content);
        if (np3) informationRow(content, AppIcon.Kind.INFO, "手机近似调色", "支持已验证的灵活色彩配方，可在相册中套用；不支持的配方会显示原因。");
        else informationRow(content, AppIcon.Kind.CAMERA, "相机兼容导入", "支持 USB 或 Wi-Fi；可导入空槽，也可确认后覆盖");
        String previewKey = local != null && !local.previewUri.isEmpty() ? local.previewUri : remote == null ? "" : remote.previewUrl;
        if (remote != null && !remote.compatibleModels.isEmpty()) label(content, "发布者标注机型：" + String.join("、", remote.compatibleModels) + (np3 ? "。手机调色不受此机型列表限制。" : "。导入前仍会检查文件兼容性。"), 12, colors.secondary);
        if (!previewKey.isEmpty()) content.addView(textLink("查看配套实拍参考", () -> showReferenceSample(previewKey, remote)));
        if (local != null) {
            final String id = local.id;
            content.addView(textLink(local.previewUri.isEmpty() ? "添加实拍参考（可选）" : "更换实拍参考", () -> pickSample(id)));
            label(content, local.originalFileName + " · " + local.byteLength + " 字节", 12, colors.secondary);
        }
        String previewNote = np3 ? "将受支持的 NP3 配方应用到当前模板，仅供色彩参考；不模拟相机基底、锐化或清晰度。"
                : "将可解析的曲线、饱和度与色相应用到当前模板，仅供色调参考；不模拟相机基底、锐化或实拍效果。";
        TextView sampleNote = label(content, previewNote, 12, colors.secondary); sampleNote.setBackground(shape(colors.container, 14)); sampleNote.setPadding(dp(12), dp(10), dp(12), dp(10));
        detailPresetDeleteMount = vertical(); content.addView(detailPresetDeleteMount); fillPresetDeleteEntry(local);
        if (!message.isEmpty()) label(content, message, 13, colors.secondary);
        String discussionId = remote != null ? remote.id : discussionPresetId(local);
        detailCommentsMount = vertical(); content.addView(detailCommentsMount, new LinearLayout.LayoutParams(-1, -2));
        if (!discussionId.isEmpty()) detailCommentsMount.addView(community.createInlineComments(discussionId, colors), new LinearLayout.LayoutParams(-1, -2));
        else { community.detachInlineComments(); recoverDownloadedDiscussion(local); }
        LinearLayout footer = vertical(); footer.setPadding(dp(16), dp(4), dp(16), dp(12));
        detailPresetFooter = footer; fillPresetDetailFooter(footer, local, remote);
        body.addView(footer);
    }

    private void fillPresetDetailFooter(LinearLayout footer, PresetRecord local, OnlinePreset remote) {
        boolean np3 = local != null && "NP3".equals(local.fileFormat);
        footer.removeAllViews();
        if (remote != null && presetDownloads.isActive(remote.sha256)) { footer.addView(presetDownloadControl(remote, false)); return; }
        if (local != null && np3) {
            footer.addView(button("前往相册", AppIcon.Kind.ALBUM, true, () -> switchRoot(1)));
            TextView note = label(footer, "选择照片后套用预设 · 手机近似效果", 13, colors.secondary); note.setGravity(Gravity.CENTER);
        } else if (local != null) {
            PresetRecord choice = local;
            footer.addView(button("导入相机", AppIcon.Kind.CAMERA, true, () -> beginImport(choice)));
            String transport = album.hasCameraConnection() || hasPreparedWireless() ? "通过 Wi-Fi · " : verifiedUsb() != null ? "通过 USB · " : "";
            TextView note = label(footer, transport + "导入前检查相机能力与槽位", 12, colors.secondary); note.setGravity(Gravity.CENTER);
        } else if (remote != null) footer.addView(presetDownloadControl(remote, false));
    }

    private void fillPresetDeleteEntry(PresetRecord local) {
        if (detailPresetDeleteMount == null) return;
        detailPresetDeleteMount.removeAllViews();
        if (local != null) {
            TextView entry = textLink(personal!=null&&!personal.owner().isEmpty()?"删除预设":"删除本地预设", () -> confirmDeletePreset(local));
            entry.setTextColor(colors.error); entry.setContentDescription("删除手机里的这份预设，相机预设和照片不受影响");
            detailPresetDeleteMount.addView(entry);
        }
    }

    private void choosePresetCategory(PresetRecord record) {
        if (waitForCurrentTask()) return;
        Set<String> choices = new LinkedHashSet<>(); choices.add(""); choices.addAll(PresetCategories.SUGGESTED);
        for (PresetRecord value : library.list()) if(personal==null||personal.visible(value))choices.add(value.category);
        List<String> values = new ArrayList<>(choices); String[] labels = new String[values.size()];
        int selected = 0;
        for (int i = 0; i < labels.length; i++) { labels[i] = PresetCategories.label(values.get(i)); if (record.category.equals(values.get(i))) selected = i; }
        new AlertDialog.Builder(this).setTitle("预设分类").setSingleChoiceItems(labels, selected, (dialog, which) -> {
            dialog.dismiss(); savePresetCategory(record.id, values.get(which));
        }).setPositiveButton("新建分类", (dialog, which) -> newPresetCategory(record.id)).setNegativeButton("取消", null).show();
    }

    private void newPresetCategory(String id) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint("例如：电影感"); input.setContentDescription("分类名称");
        input.setTextColor(colors.text); input.setHintTextColor(colors.secondary); input.setPadding(dp(20), dp(16), dp(20), dp(16));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("新建分类").setView(input)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                String category = PresetCategories.normalize(input.getText().toString());
                if (category.isEmpty()) { input.setError("请输入分类名称。"); return; }
                dialog.dismiss(); savePresetCategory(id, category);
            } catch (IOException error) { input.setError(readable(error)); }
        })); dialog.show();
    }

    private void savePresetCategory(String id, String category) {
        if (waitForCurrentTask()) return;
        final String owner=personal==null?"":personal.owner();startBusy("正在保存分类…");
        worker.execute(() -> {
            try { PresetRecord updated=library.setCategory(id, category); onUi(() -> {finishBusy("已归入“" + PresetCategories.label(category) + "”。");if(personal!=null)personal.added(updated,owner);}); }
            catch (IOException failure) { onUi(() -> { finishBusy("分类未保存。"); notice("无法修改分类", readable(failure)); }); }
        });
    }

    private ToneCurveView presetCurve(PresetRecord record) {
        ToneCurveView chart = new ToneCurveView(this, record.fileFormat);
        String id = record.id;
        chart.setTag(id);
        PresetPreviewProfile cached = curveCache.get(id);
        if (cached != null) { chart.setProfile(cached); return chart; }
        CurveTarget previous = pendingCurves.put(id, new CurveTarget(chart, imageEpoch));
        if (previous != null) return chart;
        images.execute(() -> {
            PresetPreviewProfile profile = null;
            try { profile = PresetPreviewProfiles.fromPreset(library.readPreset(id)); }
            catch (Exception | OutOfMemoryError unavailable) { }
            final PresetPreviewProfile result = profile;
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
        TextView placeholder = text("正在生成效果…", 14, false); placeholder.setGravity(Gravity.CENTER); placeholder.setTextColor(colors.secondary);
        placeholder.setPadding(dp(14), dp(14), dp(14), dp(14));
        frame.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription("预设模板模拟正在生成，非相机实拍");
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        TextView badge = text("生成中", 10, false); badge.setTextColor(Color.WHITE);
        badge.setPadding(dp(8), dp(4), dp(8), dp(4)); badge.setBackground(shape(Color.argb(170, 0, 0, 0), 12));
        FrameLayout.LayoutParams badgePosition = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        badgePosition.topMargin = dp(8); badgePosition.rightMargin = dp(8); frame.addView(badge, badgePosition);
        String simulationVersion = presetPreviews.versionKey();
        String sha = record == null ? remote.sha256 : record.sha256;
        int edge = compare ? 960 : Math.min(720, Math.max(320, dp(Math.max(1, presetCardWidth))));
        String key = "sim:" + sha + ':' + simulationVersion + ':' + edge;
        image.setTag(key);
        HoldToCompare comparison = compare ? new HoldToCompare(frame, image, badge) : null;
        if (comparison != null) {
            activeComparison = comparison;
            prepareComparisonOriginal(comparison, simulationVersion, imageEpoch, image, badge, key);
        }
        Bitmap cached = imageCache.get(key);
        if (cached != null) {
            badge.setText("模板模拟");
            if (comparison == null) image.setImageBitmap(cached); else comparison.setEffect(cached);
            image.setContentDescription("已应用预设的模板模拟，非相机实拍");
            placeholder.setVisibility(View.GONE); return frame;
        }
        PreviewTarget target = new PreviewTarget(image, placeholder, badge, imageEpoch, comparison,
                record, remote, key, simulationVersion, edge, compare);
        if (previewFailures.containsKey(key)) {
            showPreviewFailure(target, previewFailures.get(key), unsupportedPreviews.contains(key));
            return frame;
        }
        List<PreviewTarget> targets = imageTargets.get(key);
        if (targets == null) { targets = new ArrayList<>(); imageTargets.put(key, targets); }
        targets.add(target);
        image.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { target.attachedOnce = true; postVisiblePresetPreviews(); }
            @Override public void onViewDetachedFromWindow(View view) { postVisiblePresetPreviews(); }
        });
        image.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> postVisiblePresetPreviews());
        postVisiblePresetPreviews();
        return frame;
    }

    private static final class PreviewTarget {
        final ImageView image; final TextView placeholder, badge; final int epoch, edge;
        final HoldToCompare comparison;
        final PresetRecord record; final OnlinePreset remote; final String key, version;
        final boolean detail;
        boolean attachedOnce;
        PreviewTarget(ImageView image, TextView placeholder, TextView badge, int epoch, HoldToCompare comparison,
                      PresetRecord record, OnlinePreset remote, String key, String version, int edge, boolean detail) {
            this.image = image; this.placeholder = placeholder; this.badge = badge; this.epoch = epoch;
            this.comparison = comparison; this.record = record; this.remote = remote; this.key = key;
            this.version = version; this.edge = edge; this.detail = detail;
        }
    }

    private static final class PreviewJob {
        final PreviewTarget source;
        volatile boolean cancelled;
        boolean started;
        PreviewJob(PreviewTarget source) { this.source = source; }
    }

    private void postVisiblePresetPreviews() {
        if (destroyed || previewDispatchPosted) return;
        previewDispatchPosted = true;
        getWindow().getDecorView().post(() -> { previewDispatchPosted = false; dispatchVisiblePresetPreviews(); });
    }

    private boolean visiblePreview(PreviewTarget target) {
        return activityVisible && target.epoch == imageEpoch && target.key.equals(target.image.getTag())
                && target.image.isAttachedToWindow() && target.image.isShown() && target.image.getGlobalVisibleRect(new Rect());
    }

    /** At most two running and eight waiting; flinging or offscreen rows enqueue no work. */
    private void dispatchVisiblePresetPreviews() {
        if (destroyed) return;
        Map<String, PreviewTarget> visible = new HashMap<>();
        java.util.Iterator<Map.Entry<String, List<PreviewTarget>>> iterator = imageTargets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, List<PreviewTarget>> entry = iterator.next();
            entry.getValue().removeIf(target -> target.epoch != imageEpoch || target.attachedOnce && !target.image.isAttachedToWindow());
            if (entry.getValue().isEmpty()) { iterator.remove(); continue; }
            for (PreviewTarget target : entry.getValue()) if (visiblePreview(target)) visible.put(entry.getKey(), target);
        }
        for (PreviewJob job : new ArrayList<>(previewJobs.values())) if (!visible.containsKey(job.source.key)) {
            job.cancelled = true;
            if (!job.started) { previewQueue.remove(job); previewJobs.remove(job.source.key); }
        }
        for (int priority = 0; priority < 2; priority++) for (PreviewTarget target : visible.values()) {
            if (target.detail != (priority == 0) || previewJobs.containsKey(target.key)
                    || imageCache.get(target.key) != null || previewFailures.containsKey(target.key)
                    || presetGridScrolling && !target.detail || previewJobs.size() >= 10) continue;
            PreviewJob job = new PreviewJob(target); previewJobs.put(target.key, job);
            if (target.detail) previewQueue.addFirst(job); else previewQueue.addLast(job);
        }
        while (activePresetImages < 2 && !previewQueue.isEmpty()) {
            PreviewJob job = previewQueue.peekFirst();
            if (presetGridScrolling && !job.source.detail) break;
            previewQueue.removeFirst();
            if (job.cancelled) continue;
            job.started = true; activePresetImages++;
            presetImages.execute(() -> renderPresetPreview(job));
        }
    }

    private void renderPresetPreview(PreviewJob job) {
        PreviewTarget source = job.source;
        Bitmap bitmap = null; String reason = ""; boolean unsupported = false;
        try {
            if (job.cancelled) throw new IOException("预览已取消。");
            byte[] bytes = source.record != null ? library.readPreset(source.record.id)
                    : online.readForPreview(source.remote, (received, total) -> {
                        if (job.cancelled || Thread.currentThread().isInterrupted()) throw new IOException("预览已取消。");
                    });
            if (job.cancelled || !source.version.equals(presetPreviews.versionKey())) throw new IOException("预览已更新。");
            try { PresetPreviewProfiles.fromPreset(bytes); }
            catch (IOException invalid) { unsupported = true; throw invalid; }
            bitmap = presetPreviews.render(bytes, source.edge);
        } catch (Exception | OutOfMemoryError error) {
            reason = error instanceof OutOfMemoryError ? "内存暂时不足，请稍后重试。" : readable(error);
        }
        final Bitmap result = bitmap; final String failure = reason; final boolean unavailable = unsupported;
        runOnUiThread(() -> {
            activePresetImages--; previewJobs.remove(source.key);
            if (destroyed || job.cancelled || !source.version.equals(presetPreviews.versionKey())) {
                if (result != null) result.recycle();
                if (!destroyed) postVisiblePresetPreviews();
                return;
            }
            if (result != null) imageCache.put(source.key, result);
            else {
                // Failure metadata is bounded too; a changed model/template has a separate identity.
                if (previewFailures.size() >= 256) { previewFailures.clear(); unsupportedPreviews.clear(); }
                previewFailures.put(source.key, failure);
                if (unavailable) unsupportedPreviews.add(source.key);
            }
            List<PreviewTarget> waiting = imageTargets.remove(source.key);
            if (waiting != null) for (PreviewTarget target : waiting) if (target.epoch == imageEpoch && target.key.equals(target.image.getTag())) {
                if (result != null) {
                    target.badge.setText("模板模拟");
                    if (target.comparison == null) target.image.setImageBitmap(result); else target.comparison.setEffect(result);
                    target.image.setContentDescription("已应用预设的模板模拟，非相机实拍");
                    target.placeholder.setVisibility(View.GONE);
                } else showPreviewFailure(target, failure, unavailable);
            }
            postVisiblePresetPreviews();
        });
    }

    private void showPreviewFailure(PreviewTarget target, String reason, boolean unsupported) {
        if (target.comparison != null) target.comparison.setUnavailable();
        target.badge.setText("效果未生成");
        target.placeholder.setText(unsupported ? "暂不支持手机模拟\n"+(target.detail?"点按查看原因":"可查看详情或删除") : "效果暂不可用\n"+(target.detail?"点按重试":"点按查看详情"));
        target.image.setContentDescription(unsupported ? "此配方暂不支持手机模拟，未生成效果" : "效果加载失败，未生成效果");
        if(!target.detail){target.placeholder.setOnClickListener(null);target.placeholder.setClickable(false);target.placeholder.setFocusable(false);target.placeholder.setLongClickable(false);return;}
        target.placeholder.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(unsupported ? "暂不支持手机模拟" : "无法生成效果")
                .setMessage(reason + (unsupported ? "\n原预设仍可下载保留。" : "\n请检查可上网的网络后重试，已下载预设可离线预览。"))
                .setNegativeButton("关闭", null).setPositiveButton("重试", (dialog, which) -> {
                    previewFailures.remove(target.key); unsupportedPreviews.remove(target.key); render();
                }).show());
    }

    private void prepareComparisonOriginal(HoldToCompare comparison, String version, int epoch,
                                           ImageView image, TextView badge, String effectKey) {
        String key = "original:" + version;
        Bitmap cached = imageCache.get(key);
        if (cached != null) {
            comparison.setOriginal(cached);
            restoreUnreadyPreviewLabel(comparison, image, badge, effectKey);
            return;
        }
        List<OriginalPreviewTarget> targets = originalTargets.get(key);
        if (targets == null) { targets = new ArrayList<>(); originalTargets.put(key, targets); }
        targets.add(new OriginalPreviewTarget(comparison, epoch, image, badge, effectKey));
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
                    restoreUnreadyPreviewLabel(target.comparison, target.image, target.badge, target.effectKey);
                }
            });
        });
    }

    private static final class OriginalPreviewTarget {
        final HoldToCompare comparison; final int epoch;
        final ImageView image; final TextView badge; final String effectKey;
        OriginalPreviewTarget(HoldToCompare comparison, int epoch, ImageView image, TextView badge, String effectKey) {
            this.comparison = comparison; this.epoch = epoch; this.image = image; this.badge = badge; this.effectKey = effectKey;
        }
    }

    private void restoreUnreadyPreviewLabel(HoldToCompare comparison, ImageView image, TextView badge, String key) {
        if (comparison.getButtonView().isEnabled()) return;
        boolean effect = image.getDrawable() != null, failed = previewFailures.containsKey(key);
        badge.setText(effect ? "模板模拟" : failed ? "效果未生成" : "生成中");
        if (!effect) image.setContentDescription(failed ? "效果未生成" : "预设模板模拟正在生成，非相机实拍");
    }

    private void showReferenceSample(String source, OnlinePreset remote) {
        if (waitForCurrentTask()) return;
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

    private void requestCatalog(boolean cached) {
        if (waitForCurrentTask() || !requirePresetLibrary()) return;
        if (loadingCatalog) { feedback("正在加载在线预设", "目录正在读取，请等待完成后再刷新。"); return; }
        loadCatalog(cached);
    }

    private void loadCatalog(boolean cached) {
        if (!ready || online == null || busy || loadingCatalog) return;
        if (!cached) { catalogPage = 0; catalogHasMore = false; loadCatalogPage(false); return; }
        loadingCatalog = true; if (!albumTab && detailOnline == null && detailId.isEmpty()) render();
        worker.execute(() -> {
            try { online.useManagedEndpoint(DEFAULT_CATALOG_URL); }
            catch (IOException ignored) { /* Repository reports the failure and never requests a legacy endpoint. */ }
            OnlinePresetRepository.CatalogResult result = cached ? online.cachedCatalog() : online.fetchCatalog();
            onUi(() -> { loadingCatalog = false; catalogLoaded = true; catalog = new ArrayList<>(result.presets); catalogMessage = result.message.replace("我的本地", "我的预设");
                render(); });
        });
    }

    private void loadCatalogPage(boolean append) {
        if (!ready || online == null || busy || loadingCatalog) return;
        final int next = append ? catalogPage + 1 : 1, token = ++catalogEpoch;
        final String category = PresetCategories.ALL.equals(onlineCategoryFilter) ? "" : onlineCategoryFilter.isEmpty() ? "__uncategorized__" : onlineCategoryFilter;
        loadingCatalog = true;
        if (!append && !albumTab && !settingsTab && detailOnline == null && detailId.isEmpty()) render();
        images.execute(() -> {
            try {
                OnlinePresetRepository.PageResult result = online.fetchPage(next, 20, category);
                onUi(() -> {
                    if (token != catalogEpoch) return;
                    loadingCatalog = false; catalogLoaded = true; catalogPage = result.page; catalogHasMore = result.hasMore;
                    if (!append) catalog = new ArrayList<>();
                    Set<String> ids = new HashSet<>(); for (OnlinePreset row : catalog) ids.add(row.id);
                    for (OnlinePreset row : result.presets) if (ids.add(row.id)) catalog.add(row);
                    catalogCategories.clear(); catalogCategories.addAll(result.categories);
                    catalogMessage = ""; render();
                });
            } catch (Exception error) {
                OnlinePresetRepository.CatalogResult fallback = !append ? online.cachedCatalog() : null;
                onUi(() -> {
                    if (token != catalogEpoch) return;
                    loadingCatalog = false; catalogHasMore = false;
                    if (fallback != null && !fallback.presets.isEmpty()) { catalog = new ArrayList<>(fallback.presets); catalogLoaded = true; }
                    catalogMessage = append ? "更多预设暂时无法加载，请检查网络后刷新。" : "在线预设暂时无法加载，已下载预设仍可使用。";
                    render(); if (append) feedback("加载未完成", catalogMessage);
                });
            }
        });
    }

    private void downloadPreset(OnlinePreset item) {
        if (waitForCurrentTask() || !requirePresetLibrary()) return;
        if (item == null) { feedback("请先选择预设", "请从在线预设中选择一份预设，再下载到手机。"); return; }
        if (presetDownloads.isActive(item.sha256)) { feedback("正在下载这份预设", "下载进度会显示在按钮上，完成后自动标记为已下载。"); return; }
        PresetDownloadTasks.State previousDownload = presetDownloads.state(item.sha256);
        if (previousDownload != null && previousDownload.record != null) { refreshPresetDownloadViews(); feedback("预设已下载", "正在同步我的预设，无需重新下载。"); return; }
        if (library.findBySha256(item.sha256) != null) { feedback("预设已下载", "这份预设已保存在我的预设，无需重复下载。"); return; }
        try {
            presetDownloadOrigins.put(item.sha256, item);
            presetDownloadOwners.put(item.sha256,personal==null?"":personal.owner());
            presetDownloads.start(item, online); refreshPresetDownloadViews();
        } catch (IOException failure) { feedback("暂时无法下载", readable(failure)); }
    }

    private static final class PresetDownloadTarget {
        final OnlinePreset preset; final TextView label, note; final ProgressBar bar; final View action; final boolean compact; final int epoch;
        PresetDownloadTarget(OnlinePreset preset, TextView label, TextView note, ProgressBar bar, View action, boolean compact, int epoch) {
            this.preset = preset; this.label = label; this.note = note; this.bar = bar; this.action = action; this.compact = compact; this.epoch = epoch;
        }
    }

    private View presetDownloadControl(OnlinePreset item, boolean compact) {
        LinearLayout control = vertical();
        TextView label = compact ? text("下载", 12, true) : button("下载到手机", AppIcon.Kind.DOWNLOAD, true, () -> downloadPreset(item));
        label.setGravity(Gravity.CENTER);
        if (compact) { label.setTextColor(colors.primary); label.setBackground(ripple(colors.card)); label.setOnClickListener(view -> downloadPreset(item)); }
        control.addView(label, new LinearLayout.LayoutParams(-1, dp(compact ? 44 : 52)));
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(colors.primary)); bar.setIndeterminateTintList(ColorStateList.valueOf(colors.primary));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(colors.outline));
        control.addView(bar, new LinearLayout.LayoutParams(-1, dp(3)));
        TextView note = compact ? null : label(control, "", 12, colors.secondary);
        PresetDownloadTarget target = new PresetDownloadTarget(item, label, note, bar, label, compact, imageEpoch);
        presetDownloadTargets.computeIfAbsent(item.sha256, ignored -> new ArrayList<>()).add(target); updatePresetDownloadTarget(target);
        return control;
    }

    private void updatePresetDownloadTarget(PresetDownloadTarget target) {
        PresetDownloadTasks.State state = presetDownloads.state(target.preset.sha256);
        boolean active = state != null && state.active;
        boolean saved = !active && (library.findBySha256(target.preset.sha256) != null || state != null && state.record != null);
        boolean failed = !saved && !active && state != null && !state.error.isEmpty();
        int percent = state == null ? 0 : state.percent();
        String caption = saved ? "已下载" : active ? (percent >= 0 ? (target.compact ? percent + "%" : state.stage + " · " + percent + "%") : state.stage + "…")
                : failed ? (target.compact ? "重试" : "重试下载") : target.compact ? "下载" : "下载到手机";
        target.label.setText(caption); target.action.setEnabled(!saved);
        target.action.setContentDescription(target.preset.name + "，" + (active ? state.stage + (percent >= 0 ? "，" + percent + "%" : "") : saved ? "已下载到我的预设" : failed ? "下载失败，点击重试：" + state.error : "下载预设"));
        target.bar.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        target.bar.setIndeterminate(active && percent < 0); if (percent >= 0) target.bar.setProgress(percent);
        if (target.note != null) {
            String note = failed ? state.error : active ? "可离开此页，下载完成后会自动更新。" : saved && state != null ? state.note : "";
            target.note.setText(note); target.note.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
            target.note.setTextColor(failed ? colors.error : colors.secondary);
        }
    }

    private void refreshPresetDownloadViews() {
        if (destroyed || !ready || library == null) return;
        long revision = presetDownloads.libraryRevision();
        if (revision != observedPresetLibraryRevision) {
            observedPresetLibraryRevision = revision; PresetLibrary current = library;
            worker.execute(() -> {
                try { current.refresh(); onUi(() -> { if (library == current) applyRefreshedPresetLibrary(); }); }
                catch (IOException failure) { onUi(() -> { observedPresetLibraryRevision = -1; feedback("预设库未刷新", readable(failure)); }); }
            });
        }
        for (List<PresetDownloadTarget> targets : presetDownloadTargets.values())
            for (PresetDownloadTarget target : targets) if (target.epoch == imageEpoch) updatePresetDownloadTarget(target);
        for (PresetDownloadTasks.State state : presetDownloads.snapshot()) if (!state.active && state.record != null) {
            String token = state.sha256 + ':' + state.identity;
            if (!refreshedPresetDownloads.add(token)) continue;
            if(personal!=null&&presetDownloadOwners.containsKey(state.sha256))personal.added(state.record,presetDownloadOwners.remove(state.sha256));
            OnlinePreset origin = presetDownloadOrigins.get(state.sha256);
            if (origin != null) { knownDiscussionPresets.put(state.sha256, origin); getSharedPreferences("downloaded-preset-discussions", MODE_PRIVATE).edit().putString(state.sha256, origin.id).apply(); }
        }
    }

    private void applyRefreshedPresetLibrary() {
        for (List<PresetDownloadTarget> targets : presetDownloadTargets.values())
            for (PresetDownloadTarget target : targets) if (target.epoch == imageEpoch) updatePresetDownloadTarget(target);
        if (busy || albumTab || settingsTab || account.isOpen() || community.isOpen() || updates.isOpen()) return;
        if (!detailPresetSha.isEmpty() && detailPresetFooter != null) {
            PresetRecord local = library.findBySha256(detailPresetSha);
            if (local == null && detailOnline == null) { detailId = ""; render(); return; }
            if (detailPresetStorage != null) {
                detailPresetStorage[0].setText(local == null ? "在线预设 · 尚未下载" : "已保存在手机");
                detailPresetStorage[1].setText(local == null ? "可直接预览效果，下载后保留在我的预设" : local.fileFormat + " 预设文件");
            }
            fillPresetDetailFooter(detailPresetFooter, local, detailOnline); fillPresetDeleteEntry(local);
        } else if (localTab && detailId.isEmpty() && detailOnline == null) render();
    }

    private String presetDeletionBlock(String id) {
        if (presetDownloads.isActive(id)) return "这份预设正在下载或保存，请完成后再删除。";
        if (busy) return (progress.isEmpty() ? "当前操作尚未完成" : progress) + "，请完成后再删除。";
        if (confirmation != null || activeImport != null || activeWireless != null || connections.hasOperations()) return "相机正在读取或导入预设，请完成后再删除。";
        if (recoveryBlocked) return "请先核对上次相机导入结果，再删除本地预设。";
        if (id.equals(pendingImportId)) return "这份预设正在等待连接相机，请先返回并结束导入流程。";
        com.nikonpreset.usbprobe.album.PhotoLiveState live = connections.album.photoLiveState();
        if (live.enabled || live.starting || live.receiving) return "请先停止照片直播，再删除本地预设。";
        if (connections.hasPhotoTasks()) return "照片正在处理或保存，请完成后再删除本地预设。";
        return "";
    }

    private void confirmDeletePreset(PresetRecord preset) {
        if (preset == null) return;
        String block = presetDeletionBlock(preset.id); if (!block.isEmpty()) { feedback("暂时无法删除", block); return; }
        startBusy("正在检查预设使用情况…");
        connections.projectPresets.inspectPresetDeletion(preset.id, new AlbumController.Callback<PhotoProjectPresets.PresetDeletion>() {
            @Override public void onSuccess(PhotoProjectPresets.PresetDeletion inspection) {
                if (destroyed) return; finishBusy("");
                StringBuilder text = new StringBuilder(personal!=null&&!personal.owner().isEmpty()?"删除本机预设和个人云端记录；离线时排队等待同步。相机槽位、拍摄原图与已生成照片保留。":"删除手机中的这份预设和 App 保存的配套样片。相机槽位、拍摄原图与已生成照片保留。");
                if (!inspection.projectNames.isEmpty()) text.append("\n\n以下项目将取消默认预设：\n").append(String.join("\n", inspection.projectNames));
                if (inspection.references.failedTasks > 0) text.append("\n\n还有 ").append(inspection.references.failedTasks).append(" 张效果待重试，删除后需重新下载同一预设才能继续；处理记录会保留。");
                new AlertDialog.Builder(MainActivity.this).setTitle("删除“" + preset.displayName + "”？").setMessage(text.toString())
                        .setNegativeButton("取消", null).setPositiveButton("删除预设", (dialog, which) -> deleteConfirmedPreset(preset, inspection)).show();
            }
            @Override public void onError(String error) { if (!destroyed) { finishBusy(""); feedback("暂时无法删除", error); } }
        });
    }

    private void deleteConfirmedPreset(PresetRecord preset, PhotoProjectPresets.PresetDeletion inspection) {
        String block = presetDeletionBlock(preset.id); if (!block.isEmpty()) { feedback("暂时无法删除", block); return; }
        final String owner=personal==null?"":personal.owner();startBusy("正在删除本地预设…");
        connections.projectPresets.removeLocalPreset(library, inspection, new AlbumController.Callback<PhotoProjectPresets.PresetDeletionResult>() {
            @Override public void onSuccess(PhotoProjectPresets.PresetDeletionResult result) {
                if (destroyed) return;
                if(personal!=null)personal.removed(preset.sha256,owner);
                presetDownloadOrigins.remove(preset.sha256);
                knownDiscussionPresets.remove(preset.sha256); getSharedPreferences("downloaded-preset-discussions", MODE_PRIVATE).edit().remove(preset.sha256).apply();
                if (detailOnline == null && preset.sha256.equals(detailId)) detailId = "";
                String note = "本地预设已删除。" + (result.clearedDefaults > 0 ? "已取消 " + result.clearedDefaults + " 个项目的默认预设。" : "");
                if (!result.removal.cleanupWarnings.isEmpty()) note += String.join("", new LinkedHashSet<>(result.removal.cleanupWarnings));
                finishBusy(note);
            }
            @Override public void onError(String error) { if (!destroyed) { finishBusy("删除未完成。"); notice("本地预设未删除", error); } }
        });
    }

    private void pickNcp(boolean thenImport) {
        if (waitForCurrentTask() || !requirePresetLibrary()) return;
        boolean logged=personal!=null&&!personal.owner().isEmpty();
        AlertDialog.Builder prompt=new AlertDialog.Builder(this).setTitle("添加到我的预设").setMessage(logged?"所选预设文件、名称和分类将自动保存到个人云端，仅你可见；本机保留离线副本。\n模板和自选样片保留在本机。":"当前未登录，所选预设仅保存在本机。登录后可选择同步到个人云端，换手机也能恢复。")
            .setNegativeButton("取消",null).setPositiveButton(logged?"选择预设文件":"先保存到本机",(d,w)->openPresetFilePicker(thenImport));
        if(!logged)prompt.setNeutralButton("登录后同步",(d,w)->{pendingPersonalPick=true;account.open();});prompt.show();
    }
    private void openPresetFilePicker(boolean thenImport){
        if(waitForCurrentTask()||!requirePresetLibrary())return;importAfterPick=thenImport;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(intent, PICK_NCP);
    }

    private void pickSample(String id) {
        if (waitForCurrentTask() || !requirePresetLibrary()) return;
        if (id == null || id.isEmpty() || library.findBySha256(id) == null) { showPresetRequired(); return; }
        previewForId = id;
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"), PICK_SAMPLE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (account != null && account.onActivityResult(request, result, data)) return;
        if (community != null && community.onActivityResult(request, result, data)) return;
        if (request == PROTECT_PHOTO_LIVE) {
            Runnable start = pendingProtectedPhotoLive; pendingProtectedPhotoLive = null;
            String projectId = pendingProtectedLiveProjectId; pendingProtectedLiveProjectId = "";
            if (start != null && !destroyed) {
                if (ConnectionProtection.batteryExempt(this)) beginPhotoLive(start, projectId);
                else feedback("锁屏保护未开启", "系统尚未允许后台运行。可以重新开始并选择前台使用，或在设置中开启后台连接保护。");
            }
            return;
        }
        if (request == AlbumPanel.REQUEST_EXPORT) { album.onActivityResult(request, result, data); return; }
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (!ready && (request == PICK_NCP || request == PICK_SAMPLE)) { deferredRequest = request; deferredResult = data; return; }
        Uri uri = data.getData();
        if (request == PICK_TEMPLATE) { updatePreviewTemplate(uri); return; }
        if (request == PICK_NCP) {
            final String owner=personal==null?"":personal.owner();final boolean shouldImport = importAfterPick; importAfterPick = false; startBusy("正在添加预设…");
            worker.execute(() -> { try {
                PresetLibrary.AddResult added = library.importFile(documentName(uri), readBounded(uri, NcpFile.MAX_BYTES));
                onUi(() -> { busy = false; localTab = true; albumTab = false; settingsTab = false; detailId = added.record.id; detailOnline = null;
                    message = added.added ? "已加入我的预设，正在生成模板效果主图。" : "相同预设已在本地，可直接使用。"; render();if(personal!=null)personal.added(added.record,owner); if (shouldImport) beginImport(added.record); });
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
        clearPendingPhotoLive();
        if (!canChangeConnection() || !requirePresetLibrary()) return;
        if (record == null) { showPresetRequired(); return; }
        if ("NP3".equals(record.fileFormat)) { notice("NP3 文件已保留", "当前暂不支持将 NP3 导入相机，原文件仍保存在手机预设库中。"); return; }
        pendingImportId = "";
        if (recoveryBlocked) { showRecoveryRequired("导入预设"); return; }
        selected = record; selectedBytes = null; startBusy("正在检查预设兼容性…");
        worker.execute(() -> { try {
            byte[] bytes = library.readNcp(record.id); NcpPictureControlConverter.convert(bytes, 4, 0);
            onUi(() -> { selectedBytes = bytes; busy = false; chooseConnection(); });
        } catch (Exception e) { onUi(() -> { finishBusy("这份预设暂不能通过当前兼容通道导入。"); notice("暂不支持这份预设", readable(e) + "\n原文件已保留在手机。"); }); } });
    }

    private void recoverImport() {
        clearPendingPhotoLive();
        if (waitForCurrentTask()) return;
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
        if (album.hasCameraConnection() || hasPreparedWireless()) {
            // Photo enumeration is not a prerequisite for the independent Picture Control session.
            if (album.hasCameraConnection()) {
                wirelessAddress = album.connectionHost(); wirelessPortText = Integer.toString(album.connectionPort());
            } else if (hasPreparedWireless()) {
                wirelessAddress = preparedWirelessHost; wirelessPortText = Integer.toString(preparedWirelessPort);
            }
            try {
                CameraWifiNetwork.validateAddress(wirelessAddress.trim(), Integer.parseInt(wirelessPortText.trim()));
            } catch (Exception e) {
                requestImportConnection(); return;
            }
            wirelessRecovery = false; wirelessSettingsProbe = false; wirelessGuide = true;
            confirmation = null; importOutcome = null;
            scanWirelessSlots();
        } else {
            UsbDevice device = verifiedUsb();
            if (device != null) {
                album.disconnect(); requestCamera(device, false);
            } else requestImportConnection();
        }
    }

    private void requestImportConnection() {
        if (selected == null) { finishBusy("请重新选择要导入的预设。"); showPresetSelectionRequired(); return; }
        pendingImportId = selected.id;
        pendingConnectionAccepted = false;
        settingsReturnToPreset = true; settingsTab = true; albumTab = false; wirelessGuide = false;
        confirmation = null; importOutcome = null;
        message = "连接相机后，将继续导入「" + selected.displayName + "」。";
        busy = false; progress = "";
        album.openConnectionSettings(); render();
        feedback("未连接相机", "请先连接，连接成功后继续选择导入槽位。");
    }

    /** Consume once, only in the foreground and after the connection task releases its lease. */
    private void resumePendingImport() {
        if (destroyed || !activityVisible || pendingResumeScheduled || !ready || library == null || busy
                || !settingsTab || !settingsReturnToPreset || pendingImportId.isEmpty() || !pendingConnectionAccepted || recoveryBlocked
                || album.isConnecting() || album.isPhotoLiveEnabled() || album.isTransferBusy()
                || connections.hasOperations() || connections.photoPresets.state().active
                || !currentConnectionReady(false)) return;
        pendingResumeScheduled = true;
        new Handler(Looper.getMainLooper()).post(() -> {
            pendingResumeScheduled = false;
            if (destroyed || !activityVisible || busy || !settingsTab || !settingsReturnToPreset
                    || pendingImportId.isEmpty() || !pendingConnectionAccepted || recoveryBlocked || album.isConnecting()
                    || album.isPhotoLiveEnabled() || album.isTransferBusy() || connections.hasOperations()
                    || connections.photoPresets.state().active || !currentConnectionReady(false)) return;
            PresetRecord record = library.findBySha256(pendingImportId);
            pendingImportId = ""; pendingConnectionAccepted = false; settingsReturnToPreset = false;
            settingsTab = false; albumTab = false;
            if (record == null) { finishBusy("这份预设已不存在，请重新选择。"); return; }
            // Read the persisted source again after Activity recreation; connection never writes by itself.
            beginImport(record);
        });
    }

    private void clearPendingPhotoLive() {
        photoLiveContinuation.cancel();
        pendingLiveProjectId = "";
        livePreparationRequested = livePreparationPrompted = false;
        AlertDialog dialog = livePreparationDialog; livePreparationDialog = null;
        if (dialog != null) dialog.dismiss();
    }

    private boolean canResumePhotoLive() {
        return !destroyed && activityVisible && ready && settingsTab && !wirelessGuide && !busy
                && photoLiveContinuation.isPending() && photoLiveContinuation.isConnectionAccepted()
                && !album.isConnecting() && !connections.isPreparingAlbum() && !connections.hasOperations()
                && !album.isTransferBusy() && !connections.photoPresets.state().active
                && (album.hasCameraConnection() || hasPreparedWireless());
    }

    /** Return only this foreground connection request to project naming, once photos are ready. */
    private void resumePendingPhotoLive() {
        if (liveResumeScheduled || !canResumePhotoLive()) return;
        final long revision = photoLiveContinuation.revision();
        liveResumeScheduled = true;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (!photoLiveContinuation.matches(revision) || !canResumePhotoLive()) return;
                if (recoveryBlocked) { clearPendingPhotoLive(); showRecoveryRequired("开始照片直播"); return; }
                if (connections.album.photoLiveState().enabled || connections.album.photoLiveState().starting) {
                    clearPendingPhotoLive(); return;
                }
                if (connections.album.photoLiveState().receiving) return;
                if (!album.isConnected() && connections.album.isConnected()) album.followSharedConnection();
                if (!album.isConnected()) {
                    if (!livePreparationRequested) {
                        livePreparationRequested = true;
                        if (connections.prepareAlbumFromCurrent(false)) {
                            message = "相机已连接，正在准备照片直播…"; render();
                        } else showLivePreparationFailure(revision);
                    } else showLivePreparationFailure(revision);
                    return;
                }
                if (!photoLiveContinuation.consume(revision)) return;
                String projectId = pendingLiveProjectId; pendingLiveProjectId = "";
                livePreparationRequested = livePreparationPrompted = false;
                settingsReturnToPreset = false; pendingImportId = ""; pendingConnectionAccepted = false;
                settingsTab = false; albumTab = true; wirelessGuide = false; message = "";
                if (projectId.isEmpty()) album.resumePhotoLiveAfterConnection();
                else album.resumePhotoLiveProjectAfterConnection(projectId);
            } finally {
                liveResumeScheduled = false;
                if (photoLiveContinuation.isPending() && photoLiveContinuation.revision() != revision) resumePendingPhotoLive();
            }
        });
    }

    private void showLivePreparationFailure(long revision) {
        if (!photoLiveContinuation.matches(revision) || livePreparationPrompted || !activityVisible) return;
        livePreparationPrompted = true;
        String error = connections.albumPreparationError();
        if (error.isEmpty()) error = "相机照片暂时无法读取，请检查存储卡后重试。";
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("照片直播尚未就绪")
                .setMessage(error + "\n\n相机连接仍保持。重试成功后会继续新建项目。")
                .setNegativeButton("取消", (d, w) -> {
                    if (photoLiveContinuation.matches(revision)) {
                        clearPendingPhotoLive(); message = "可稍后从照片直播重新开始。"; render();
                    }
                }).setPositiveButton("重试读取", (d, w) -> {
                    if (!photoLiveContinuation.matches(revision) || !canResumePhotoLive()) return;
                    livePreparationPrompted = false;
                    if (connections.prepareAlbumFromCurrent(true)) {
                        message = "正在准备照片直播…"; render();
                    } else showLivePreparationFailure(revision);
                }).create();
        livePreparationDialog = dialog;
        dialog.setOnCancelListener(d -> { if (photoLiveContinuation.matches(revision)) clearPendingPhotoLive(); });
        dialog.setOnDismissListener(d -> { if (livePreparationDialog == dialog) livePreparationDialog = null; });
        dialog.show();
    }

    private void returnToPreset() {
        if (waitForCurrentTask()) return;
        topNotice.dismiss();
        pendingImportId = ""; settingsReturnToPreset = false;
        if (album.isConnectionGuide()) { if (album.isConnecting()) album.disconnect(); album.goBack(); }
        settingsTab = false; albumTab = false; wirelessGuide = false; message = ""; render();
    }

    private void showCameraDetails() {
        if (waitForCurrentTask()) return;
        if (!currentConnectionReady(false)) { showConnectionRequired("查看相机详情"); return; }
        boolean wireless = album.hasCameraConnection() || hasPreparedWireless();
        String model = wireless ? album.hasCameraConnection() ? album.connectionModel() : preparedWirelessModel : usbModel;
        String address = wireless ? (album.hasCameraConnection() ? album.connectionHost() : preparedWirelessHost)
                + ":" + (album.hasCameraConnection() ? album.connectionPort() : preparedWirelessPort) : "USB 数据线";
        AlertDialog.Builder details = new AlertDialog.Builder(this).setTitle("相机详情")
                .setMessage(model + "\n" + (wireless ? "Wi-Fi · " : "") + address)
                .setPositiveButton("关闭", null);
        if (wireless) details.setNeutralButton("检测预设支持", (d, w) -> {
            if (!canChangeConnection()) return;
            pendingImportId = ""; settingsReturnToPreset = false;
            openWirelessGuide(false, true);
            scanWirelessSlots();
        });
        details.show();
    }

    private void openWirelessGuide(boolean recovery) {
        openWirelessGuide(recovery, false);
    }

    private void openWirelessGuide(boolean recovery, boolean settingsProbe) {
        clearPendingPhotoLive();
        if (!canChangeConnection()) return;
        if (!recovery && album.hasCameraConnection()) {
            wirelessAddress = album.connectionHost(); wirelessPortText = Integer.toString(album.connectionPort());
        } else if (!recovery && hasPreparedWireless()) {
            wirelessAddress = preparedWirelessHost; wirelessPortText = Integer.toString(preparedWirelessPort);
        }
        wirelessRecovery = recovery; wirelessSettingsProbe = settingsProbe && !recovery;
        wirelessGuide = true; confirmation = null; importOutcome = null;
        if (recovery && pendingJournal != null) {
            wirelessAddress = pendingJournal.optString("wirelessHost", "");
            wirelessPortText = Integer.toString(pendingJournal.optInt("wirelessPort", CameraWifiNetwork.DEFAULT_PORT));
        } else if (wirelessAddress.isEmpty()) {
            wirelessAddress = CameraConnectionPreferences.loadHost(this);
            wirelessPortText = Integer.toString(CameraConnectionPreferences.loadPort(this));
            if (wirelessAddress.isEmpty()) wirelessAddress = Build.VERSION.SDK_INT >= 29 ? "192.168.1.1" : wifi.suggestHost();
        }
        message = ""; render();
    }

    private void renderWirelessGuide() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout content = vertical(); content.setPadding(dp(16), dp(4), dp(16), dp(24)); scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        if (!wirelessRecovery) {
            heading(content, wirelessSettingsProbe ? "检查预设支持" : "准备导入相机", 24);
            informationRow(content, AppIcon.Kind.CAMERA, "当前相机", wirelessAddress + ":" + wirelessPortText);
            label(content, wirelessSettingsProbe ? "读取相机支持能力和机内槽位，本次不会写入。"
                    : "正在检查预设支持和机内槽位，下一步选择存储位置。", 14, colors.secondary);
            if (!message.isEmpty()) label(content, message, 14, colors.secondary);
            if (busy) {
                Button cancel = button("取消读取", AppIcon.Kind.BACK, false, this::cancelWirelessScan);
                cancel.setEnabled(true); cancel.setAlpha(1f); cancel.setOnClickListener(v -> cancelWirelessScan()); content.addView(cancel);
            } else {
                content.addView(button(wirelessSettingsProbe ? "重新检测" : "重试读取", AppIcon.Kind.REFRESH, true, this::scanWirelessSlots));
                content.addView(textLink("相机连接设置", () -> {
                    if (!wirelessSettingsProbe && selected != null) requestImportConnection();
                    else { wirelessGuide = false; settingsTab = true; albumTab = false; message = ""; render(); }
                    openCameraSettings();
                }));
            }
            return;
        }
        heading(content, "只读核对上次导入", 24);
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
                if (suggestion.isEmpty()) feedback("未找到 Wi-Fi 地址", "请先连接相机网络，或按相机显示的信息填写地址。");
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
        if (!canChangeConnection()) return;
        final String host = wirelessAddress.trim(); final int port;
        try { port = Integer.parseInt(wirelessPortText.trim()); CameraWifiNetwork.validateAddress(host, port); }
        catch (Exception e) { notice("请检查相机地址", e instanceof NumberFormatException ? "端口应为 1–65535 的数字。" : readable(e)); return; }
        if (!wirelessRecovery && !wirelessSettingsProbe && (selected == null || selectedBytes == null)) {
            wirelessGuide = false; finishBusy("请重新选择要导入的预设。"); showPresetSelectionRequired(); return;
        }
        if (Build.VERSION.SDK_INT >= 29 && !connections.wifiSession.isConnected()
                && !hasPreparedWireless() && !album.hasCameraConnection()) {
            ensureCameraWifi(this::scanWirelessSlots, error -> feedback("相机 Wi-Fi 未连接", error));
            return;
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
        if (!recovery && reuse == null && album.hasCameraConnection()
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
            if (album.hasCameraConnection() || album.isConnecting()) album.disconnect();
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
                CameraConnectionPreferences.save(this, host, port);
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
                        finishBusy("检测完成：" + result.model + "，C1–C9 中有 " + emptyCount + " 个空槽。\n"
                                + (registrationSupported ? "支持无线导入。打开预设详情，点击“导入相机”即可。" : "相机未声明无线预设登记接口，可在相机连接中改用 USB。")
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
        if (!canChangeConnection()) return;
        // Also cancel a wireless connection still being established before opening a USB session.
        connections.disconnect(); preparedWireless = null;
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
        if (waitForCurrentTask()) return; startBusy(recovery ? "正在核对上次导入…" : "正在读取相机槽位…");
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
                        if (attached) { verifiedUsbId = device.getDeviceId(); usbModel = result.model; }
                        finishBusy(attached ? "USB 相机已连接。" : "相机已断开，请重新连接。");
                        feedback(attached ? "相机已连接" : "相机已断开", attached ? "USB 连接就绪，可以继续导入预设。" : "请检查数据线连接后重试。");
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
        if (!requireCurrentConfirmation(plan)) return;
        final ImportSlot slot = plan.selectedSlot();
        Runnable write = () -> {
            if (!requireCurrentConfirmation(plan)) return;
            if (plan.selectedSlot() != slot) { feedback("槽位已变化", "请按确认页中当前选择的槽位重新确认导入。"); return; }
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
        if (!requireCurrentConfirmation(plan)) return;
        LinearLayout content = vertical(); content.setPadding(dp(24), dp(4), dp(24), dp(16));
        label(content, "原 NCP 没有以下两项参数，可根据需要调整兼容适配值。", 14, colors.text);
        label(content, "中等范围锐化", 14, colors.text); Spinner middle = spinner(content, quarters(0, 20), plan.middle);
        label(content, "清晰度", 14, colors.text); Spinner clarity = spinner(content, quarters(-20, 20), plan.clarity + 20);
        heading(content, "当前完整参数说明", 16); label(content, plan.conversion.toDisplayText(), 13, colors.secondary);
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("查看并调整兼容设置").setView(scroll).setNegativeButton("取消", null)
                .setPositiveButton("保存设置", (d, w) -> {
                    if (!requireCurrentConfirmation(plan)) return;
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
        if (waitForCurrentTask()) return;
        connections.clearImportStatus(); shownImportStatus = null;
        importOutcome = null; confirmation = null; message = ""; render();
    }
    private void field(LinearLayout parent, String name, String value) { label(parent, name, 12, colors.secondary); TextView content = text(value, 16, true); content.setPadding(0, 0, 0, dp(12)); parent.addView(content); }
    private TextView textLink(String value, Runnable action) {
        TextView link = text(value, 13, false); link.setTextColor(colors.primary); link.setGravity(Gravity.CENTER_VERTICAL); link.setMinHeight(dp(48)); link.setPadding(dp(2), 0, dp(2), 0); link.setBackground(ripple(colors.surface)); link.setFocusable(true);
        link.setOnClickListener(v -> { if (!waitForCurrentTask()) action.run(); }); return link;
    }
    private TextView[] informationRow(LinearLayout parent, AppIcon.Kind kind, String heading, String description) {
        LinearLayout row = horizontal(); row.setPadding(dp(12), dp(12), dp(12), dp(12)); row.setBackground(shape(colors.container, 10));
        ImageView icon = new ImageView(this); icon.setImageDrawable(new AppIcon(kind, colors.secondary, getResources().getDisplayMetrics().density)); row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout copy = vertical(); copy.setPadding(dp(12), 0, 0, 0); TextView title = text(heading, 15, true); copy.addView(title);
        TextView note = text(description, 12, false); note.setTextColor(colors.secondary); note.setPadding(0, dp(5), 0, 0);
        if (!description.isEmpty()) copy.addView(note);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(16); params.bottomMargin = dp(16); parent.addView(row, params);
        return new TextView[]{title, note};
    }
    private static String quarter(int value) { return java.math.BigDecimal.valueOf(value).divide(java.math.BigDecimal.valueOf(4)).stripTrailingZeros().toPlainString(); }

    private void writePreset(CameraConnection connection, String model, PresetRecord preset, byte[] source, int target, int middle, int clarity,
                             String expectedHash, PictureControlImportSession.OverwriteConfirmation overwrite) {
        if (waitForCurrentTask()) return;
        if (recoveryBlocked) { showRecoveryRequired("导入预设"); return; }
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
        if (waitForCurrentTask()) return;
        if (report.isEmpty()) { notice("暂无可导出的报告", "检测预设支持、读取槽位或导入预设后会生成报告。可在设置中连接相机，再从相机详情检测预设支持。"); return; }
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "nikon-usb-report.json"), EXPORT_REPORT);
    }

    private String appVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null ? "unknown" : version;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return "unknown";
        }
    }

    private void showHelp() {
        List<String> labels = new ArrayList<>();
        labels.add("预设导入说明"); labels.add("导出最近报告"); labels.add("高级诊断");
        if (recoveryBlocked) labels.add("核对上次导入 · 只读");
        labels.add("关于 " + getString(R.string.app_name));
        new AlertDialog.Builder(this).setTitle("帮助与设置").setItems(labels.toArray(new String[0]), (d, w) -> {
            switch (w) {
                case 0: notice("导入相机", "打开本地预设，点击“导入相机”。应用使用当前 Wi-Fi 或 USB 连接；尚未连接时会前往设置，连接完成后自动继续到槽位确认。\n\n无线连接不需要先读取相册。导入时自动检查相机能力与槽位，无需提前手动检测；相机详情中仍可按需检测预设支持。若无线接口不支持导入，可在设置中改用 USB。\n\n选择空槽可直接导入；选择已有预设的槽位时，会显示原名称并要求确认覆盖。确认后重新核对目标内容，只登记一次并读回核对；槽位已变化则停止。结果不明时先只读核对，勿重复导入。\n\n导入成功并读回核对后保持连接，需要断开时请到设置操作。"); break;
                case 1: exportReport(); break;
                case 2: if (waitForCurrentTask()) break; else if (recoveryBlocked) showRecoveryRequired("打开高级诊断"); else if (canChangeConnection()) { clearPendingPhotoLive(); album.disconnect(); startActivity(new Intent(this, DiagnosticsActivity.class)); } break;
                default: if (recoveryBlocked && w == 3) recoverImport(); else notice(getString(R.string.app_name), "预设浏览、本地管理与尼康相机导入。\n当前 USB 兼容通道以 Z6II 实测为基础，并不支持所有 NCP、NP2 或 NP3。\n\n打开预设库中的“在线预设”，即可浏览和下载管理后台已发布的预设，无需配置地址。无线相册支持范围以实际连接与读取结果为准。");
            }
        }).setNegativeButton("关闭", null).show();
    }

    private void switchRoot(int destination) {
        if (waitForCurrentTask()) return;
        if (destination == (settingsTab ? 2 : albumTab ? 1 : 0)) return;
        topNotice.dismiss();
        clearPendingPhotoLive();
        connections.clearImportStatus(); shownImportStatus = null;
        confirmation = null; importOutcome = null; wirelessGuide = false;
        albumTab = destination == 1; settingsTab = destination == 2; settingsReturnToPreset = false; pendingImportId = "";
        detailId = ""; detailOnline = null; message = ""; render();
        if (destination == 0 && !localTab && !catalogLoaded) loadCatalog(false);
    }

    private void goBack() {
        if (account != null && account.isOpen()) { account.goBack(); return; }
        if (community != null && community.isOpen()) { community.goBack(); return; }
        if (updates != null && updates.isOpen()) { updates.goBack(); return; }
        if (busy && wirelessGuide) { cancelWirelessScan(); return; }
        if (waitForCurrentTask()) return;
        topNotice.dismiss();
        if (settingsTab && photoLiveContinuation.isPending()) {
            clearPendingPhotoLive();
            if (album.isConnectionGuide()) { if (album.isConnecting()) album.disconnect(); album.goBack(); }
            settingsTab = false; albumTab = true; wirelessGuide = false; message = ""; render(); return;
        }
        if (wirelessGuide) { wirelessGuide = false; message = ""; render(); }
        else if (settingsTab && album.isConnectionGuide()) {
            if (album.isConnecting()) album.disconnect();
            album.goBack();
            if (settingsReturnToPreset) returnToPreset(); else render();
        }
        else if (settingsTab && settingsReturnToPreset) returnToPreset();
        else if (settingsTab) switchRoot(0);
        else if (importOutcome != null || confirmation != null) finishImportScreen();
        else if (albumTab && album.canGoBack()) { album.goBack(); render(); }
        else if (!detailId.isEmpty() || detailOnline != null) { detailId = ""; detailOnline = null; message = ""; render(); }
        else if (albumTab) switchRoot(0);
        else super.onBackPressed();
    }
    @Override public void onBackPressed() { goBack(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("localPresetCategory", localCategoryFilter);
        out.putString("onlinePresetCategory", onlineCategoryFilter);
        out.putBoolean("local", localTab); out.putBoolean("album", albumTab);
        out.putBoolean("settings", settingsTab); out.putBoolean("settingsReturnToPreset", settingsReturnToPreset);
        out.putString("pendingImportId", pendingImportId);
        out.putBoolean("pendingConnectionAccepted", pendingConnectionAccepted);
        out.putBoolean("pendingPhotoLive", photoLiveContinuation.isPending());
        out.putString("pendingLiveProjectId", pendingLiveProjectId);
        out.putBoolean("pendingLiveConnectionAccepted", photoLiveContinuation.isConnectionAccepted());
        out.putBoolean("livePreparationRequested", livePreparationRequested);
        out.putString("detailId", detailId);
        out.putString("previewForId", previewForId); out.putBoolean("importAfterPick", importAfterPick);
        out.putBoolean("wirelessGuide", wirelessGuide); out.putBoolean("wirelessRecovery", wirelessRecovery);
        out.putBoolean("wirelessSettingsProbe", wirelessSettingsProbe);
        out.putString("wirelessAddress", wirelessAddress); out.putString("wirelessPort", wirelessPortText);
        super.onSaveInstanceState(out);
    }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); render(); }

    @Override protected void onPause() {
        ConnectionTrace.mark("UI_BACKGROUND");
        activityVisible = false;
        presetDownloads.removeObserver(presetDownloadObserver);
        postVisiblePresetPreviews();
        if (account != null) account.onHostPaused();
        if (community != null) community.onHostPaused();
        if (updates != null) updates.onPause();
        if (topNotice != null) topNotice.onHostPaused();
        if (activeComparison != null) activeComparison.release();
        if (album != null) album.releasePhotoComparison();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        ConnectionTrace.mark("UI_FOREGROUND");
        activityVisible = true;
        presetDownloads.addObserver(presetDownloadObserver); refreshPresetDownloadViews();
        postVisiblePresetPreviews();
        if (account != null) account.onHostResumed();
        if(personal!=null&&ready)personal.resumed();
        if (community != null) community.onHostResumed();
        if (updates != null) { updates.onResume(); updates.automaticCheck(); }
        if (album != null) album.refreshGalleryStatus();
        // OS battery/notification settings have no useful result payload. Refresh their actual state.
        if (connections != null) connections.changed();
        if (ready && settingsTab && !destroyed) render();
        resumePendingImport();
        resumePendingPhotoLive();
    }

    private boolean waitForCurrentTask() {
        if (!busy) return false;
        feedback("操作进行中", (progress.isEmpty() ? "当前任务正在处理。" : progress) + "\n\n请等待完成后再试。");
        return true;
    }

    private boolean updateTaskActive() {
        if (connections == null || album == null) return true;
        com.nikonpreset.usbprobe.album.PhotoLiveState live = connections.album.photoLiveState();
        return busy || presetDownloads.hasActive() || confirmation != null || connections.hasOperations() || connections.hasPhotoTasks()
                || live.enabled || live.starting || live.receiving || album.isTransferBusy() || album.isConnecting()
                || connections.isPreparingAlbum() || activeImport != null || activeWireless != null;
    }

    private void prepareUpdateInstallation(Runnable proceed) {
        if (destroyed || !activityVisible || updates == null || !updates.isOpen()) return;
        if (updateTaskActive()) {
            feedback("请先完成照片任务", "直播、预设导入或照片处理期间不能安装更新。请先停止直播，并等待传输和保存完成。"); return;
        }
        if (connections.hasCameraServiceWork() || verifiedUsb() != null) {
            new AlertDialog.Builder(this).setTitle("断开相机并安装更新？")
                    .setMessage("更新会重新启动 Huevia。当前相机连接将结束，已保存的照片和项目会保留。")
                    .setNegativeButton("稍后", null).setPositiveButton("断开并继续", (dialog, which) -> {
                        if (destroyed || !activityVisible || !updates.isOpen()) return;
                        if (updateTaskActive()) { feedback("相机任务已开始", "请等待当前任务完成后再安装更新。"); return; }
                        clearPendingPhotoLive(); pendingImportId = ""; pendingConnectionAccepted = false;
                        verifiedUsbId = -1; usbModel = ""; connections.disconnect(); preparedWireless = null;
                        awaitUpdateDisconnect(proceed, android.os.SystemClock.elapsedRealtime() + 5000);
                    }).show();
        } else proceed.run();
    }

    private void awaitUpdateDisconnect(Runnable proceed, long deadline) {
        if (destroyed || !activityVisible) return;
        if (!updateTaskActive() && !connections.hasCameraServiceWork()) { proceed.run(); return; }
        if (android.os.SystemClock.elapsedRealtime() >= deadline) { feedback("正在结束相机连接", "请稍后再次点击安装更新。"); return; }
        new Handler(Looper.getMainLooper()).postDelayed(() -> awaitUpdateDisconnect(proceed, deadline), 150);
    }

    private boolean requirePresetLibrary() {
        if (!ready) { feedback("正在准备预设库", "正在读取保存在手机中的预设，请稍候再试。"); return false; }
        if (!initializationError.isEmpty() || library == null || online == null) {
            feedback("预设库暂不可用", (initializationError.isEmpty() ? "预设库尚未准备完成。" : initializationError) + "\n请重新打开 App 后再试，可在使用帮助中查看说明。"); return false;
        }
        return true;
    }

    private void showPhotoLiveConnectionRequired() {
        if (recoveryBlocked) { showRecoveryRequired("开始照片直播"); return; }
        openPhotoLiveConnection("");
    }

    private void showPhotoLiveProjectConnectionRequired(String id) {
        if (recoveryBlocked) { showRecoveryRequired("继续接收照片"); return; }
        if (waitForCurrentTask()) return;
        if (album.hasCameraConnection() || hasPreparedWireless()) {
            switchRoot(2); clearPendingPhotoLive(); pendingLiveProjectId = id;
            photoLiveContinuation.request(); photoLiveContinuation.connectionAttempted();
            feedback("正在准备项目", "照片列表就绪后，将继续接收这个项目。");
            resumePendingPhotoLive(); return;
        }
        openPhotoLiveConnection(id);
    }

    /** An explicit connect action opens the Wi-Fi page immediately and remembers what to resume. */
    private void openPhotoLiveConnection(String projectId) {
        if (destroyed || isFinishing() || waitForCurrentTask() || !canChangeConnection()) return;
        if (album.isConnecting() || connections.isPreparingAlbum()) {
            feedback("正在连接相机", "请等待当前连接或相册准备完成后再试。需要取消时，可返回连接页面操作。"); return;
        }
        switchRoot(2);
        clearPendingPhotoLive();
        pendingImportId = ""; settingsReturnToPreset = false; pendingConnectionAccepted = false;
        pendingLiveProjectId = projectId == null ? "" : projectId;
        photoLiveContinuation.request();
        if (album.hasCameraConnection() || hasPreparedWireless()) photoLiveContinuation.connectionAttempted();
        openCameraSettings();
        feedback("请连接相机 Wi-Fi", pendingLiveProjectId.isEmpty()
                ? "连接成功后，将自动返回照片直播新建项目。" : "连接成功后，将返回原项目继续接收。");
        resumePendingPhotoLive();
    }

    private void showConnectionRequired(String operation) { showConnectionRequired(operation, false); }

    private void showConnectionRequired(String operation, boolean continuePhotoLive) { showConnectionRequired(operation, continuePhotoLive, ""); }
    private void showConnectionRequired(String operation, boolean continuePhotoLive, String projectId) {
        if (destroyed || isFinishing() || waitForCurrentTask()) return;
        if (album.isConnecting() || connections.isPreparingAlbum()) {
            feedback("正在连接相机", "请等待当前连接或相册准备完成后再试。需要取消时，可返回连接页面操作。"); return;
        }
        if (!canChangeConnection()) return;
        String action = operation == null || operation.trim().isEmpty() ? "此操作" : operation.trim();
        String transport = "查看相机详情".equals(action) ? "相机（Wi-Fi 或 USB）" : "相机 Wi-Fi";
        feedback("未连接相机", continuePhotoLive ? projectId.isEmpty() ? "请先连接，连接成功后继续新建拍摄项目。" : "请连接原相机，连接后继续接收这个项目。"
                : "请先连接" + transport + "，再" + action + "。", "去连接", () -> {
                    if (continuePhotoLive) { openPhotoLiveConnection(projectId); return; }
                    if (!canChangeConnection()) return;
                    clearPendingPhotoLive();
                    switchRoot(2);
                    openCameraSettings();
                });
    }

    private void showPresetRequired() {
        if (destroyed || isFinishing() || waitForCurrentTask() || !requirePresetLibrary()) return;
        if (!canChangeConnection()) return;
        feedback("还没有本地预设", "添加 NCP 或 NP3 预设后，即可选择受支持的配方套用到照片。", "添加", () -> {
                    if (!canChangeConnection() || !requirePresetLibrary()) return;
                    switchRoot(0); pickNcp(false);
                });
    }

    private void showPresetSelectionRequired() {
        if (destroyed || isFinishing() || waitForCurrentTask()) return;
        feedback("请先选择预设", "从预设库打开一份本地预设后再继续。", "去选择", () -> switchRoot(0));
    }

    private void showRecoveryRequired(String operation) {
        if (destroyed || isFinishing() || waitForCurrentTask()) return;
        new AlertDialog.Builder(this).setTitle("请先核对上次导入")
                .setMessage("上次导入结果尚未确认，请先只读核对相机中的预设，再" + operation + "。")
                .setNegativeButton("取消", null).setPositiveButton("核对上次导入", (d, w) -> {
                    if (!canChangeConnection()) return;
                    recoverImport();
                }).show();
    }

    private boolean requireCurrentConfirmation(PendingConfirmation plan) {
        if (waitForCurrentTask()) return false;
        if (plan == null || confirmation != plan) { feedback("请重新确认导入", "当前导入信息已变化，请返回当前确认页重新选择槽位并确认。"); return false; }
        if (plan.conversion == null) { feedback("正在准备导入信息", "预设兼容信息尚未准备完成，请稍候再试。"); return false; }
        return true;
    }

    private void startBusy(String value) { busy = true; progress = value; render(); }
    private void finishBusy(String value) { busy = false; progress = ""; message = value; render(); resumePendingImport(); resumePendingPhotoLive(); }
    private void onUi(Runnable task) { if (!destroyed) runOnUiThread(() -> { if (!destroyed) task.run(); }); }
    private void notice(String title, String message) { if (!destroyed) new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show(); }
    private void feedback(String title, String text) {
        if (text != null && (text.length() > 52 || text.contains("\n"))) {
            String summary = text.split("[。\\n]", 2)[0];
            if (summary.length() > 40) summary = "查看详情了解原因后，再继续操作。";
            feedback(title, summary, "详情", () -> notice(title, text));
        } else feedback(title, text, null, null);
    }
    private void feedback(String title, String text, String action, Runnable task) {
        if (destroyed || !activityVisible || isFinishing() || topNotice == null) return;
        final String sourceRoute = navigationRoute();
        topNotice.show(title, text, action, task == null ? null : () -> {
            if (!destroyed && activityVisible && sourceRoute.equals(navigationRoute())) task.run();
        });
    }
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
        button.setAlpha(busy ? .5f : 1); button.setOnClickListener(v -> { if (!waitForCurrentTask()) click.run(); }); return button;
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
        cell.setBackground(new RippleDrawable(ColorStateList.valueOf(colors.primaryContainer), shape(active ? colors.primaryContainer : Color.TRANSPARENT, 30), null)); cell.setAlpha(busy ? .5f : 1); cell.setFocusable(true); cell.setContentDescription(value); cell.setSelected(active);
        ImageView icon = new ImageView(this); icon.setImageDrawable(new AppIcon(kind, active ? colors.primary : colors.secondary, getResources().getDisplayMetrics().density));
        FrameLayout iconSlot = new FrameLayout(this); iconSlot.addView(icon, new FrameLayout.LayoutParams(dp(23), dp(23), Gravity.CENTER));
        if (kind == AppIcon.Kind.SETTINGS) {
            settingsNavigationItem = cell; settingsUnreadDot = new View(this); settingsUnreadDot.setBackground(shape(colors.error, 4));
            settingsUnreadDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            iconSlot.addView(settingsUnreadDot, new FrameLayout.LayoutParams(dp(8), dp(8), Gravity.TOP | Gravity.END));
        }
        cell.addView(iconSlot, new LinearLayout.LayoutParams(dp(34), dp(23))); TextView label = text(value, 10, active); label.setGravity(Gravity.CENTER); label.setTextColor(active ? colors.primary : colors.secondary); label.setPadding(0, dp(3), 0, 0); cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.leftMargin = dp(1); params.rightMargin = dp(1); parent.addView(cell, params);
        cell.setOnClickListener(v -> { if (!waitForCurrentTask()) click.run(); });
    }
    private void refreshMessageBadges() {
        int unread = community == null ? 0 : community.unreadCount();
        if (settingsUnreadDot != null) settingsUnreadDot.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        if (settingsNavigationItem != null) settingsNavigationItem.setContentDescription(unread > 0 ? "设置，" + unread + " 条未读消息" : "设置");
        if (settingsMessageBadge != null) {
            settingsMessageBadge.setText(unread > 99 ? "99+" : String.valueOf(unread));
            settingsMessageBadge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        }
        if (settingsMessageEntry != null) settingsMessageEntry.setContentDescription(unread > 0 ? "消息，" + unread + " 条未读，回复与投稿审核通知" : "消息，回复与投稿审核通知");
    }
    private void sourceTab(LinearLayout parent, String value, boolean active, Runnable click) {
        FrameLayout cell = new FrameLayout(this); TextView tab = text(value, 13, active); tab.setGravity(Gravity.CENTER); tab.setTextColor(active ? colors.text : colors.secondary);
        if (active) { tab.setBackground(shape(colors.card, 19)); tab.setElevation(dp(1)); }
        FrameLayout.LayoutParams capsule = new FrameLayout.LayoutParams(-1, dp(34), Gravity.CENTER); capsule.leftMargin = capsule.rightMargin = dp(2); cell.addView(tab, capsule);
        cell.setFocusable(true); cell.setSelected(active); cell.setContentDescription(value); cell.setOnClickListener(v -> { if (!waitForCurrentTask()) click.run(); });
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
        presetDownloads.removeObserver(presetDownloadObserver);
        cancelPendingCameraWifi();
        if (account != null) account.close();
        if(personal!=null)personal.close();
        if (community != null) community.close();
        if (updates != null) updates.close();
        if (topNotice != null) topNotice.close();
        clearPendingPhotoLive();
        PageTransitions.cancel(transitioningContent);
        connections.removeObserver(connectionObserver);
        pendingProtectedPhotoLive = null;
        pendingProtectedLiveProjectId = "";
        pendingPhotoSave = null;
        destroyed = true; if (activeProbe != null) activeProbe.close(); if (activeImport != null) activeImport.close();
        // The connected-device service owns wireless sessions and the serial worker.
        // Recreating or removing this screen only detaches its UI.
        if (album != null) album.close(); images.shutdownNow();
        for (PreviewJob job : previewJobs.values()) job.cancelled = true;
        previewQueue.clear(); presetImages.shutdownNow(); imageCache.evictAll();
        if (receiverRegistered) unregisterReceiver(receiver); super.onDestroy();
    }
}

package cc.huevia.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import cc.huevia.R;
import cc.huevia.album.AlbumController;
import cc.huevia.album.ConnectionInfo;
import cc.huevia.album.DownloadedPhoto;
import cc.huevia.album.PhotoLibraryCatalog;
import cc.huevia.album.PhotoLiveGallery;
import cc.huevia.album.PhotoItem;
import cc.huevia.album.PhotoLiveState;
import cc.huevia.album.PhotoLiveProject;
import cc.huevia.album.PhotoStarStore;
import cc.huevia.album.PhotoStars;
import cc.huevia.album.PhotoLiveReceipt;
import cc.huevia.album.PhotoProjectPresets;
import cc.huevia.album.PhotoProjectPresetStore;
import cc.huevia.album.PhotoAlbumSaver;
import cc.huevia.album.PhotoPresetBatch;
import cc.huevia.album.PhotoPresetBatchState;
import cc.huevia.album.PhotoSaveTarget;
import cc.huevia.album.PhotoDateGroups;
import cc.huevia.album.PhotoDateReader;
import cc.huevia.album.PhotoDeleteResult;
import cc.huevia.album.PhotoPage;
import cc.huevia.album.PhotoPreview;
import cc.huevia.album.PtpIpClient;
import cc.huevia.connection.CameraConnectionPreferences;
import cc.huevia.album.CameraProtocol;
import cc.huevia.library.PresetRecord;
import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Wireless JPEG browsing and app-local downloads. The Activity owns the app bar and navigation. */
public final class AlbumPanel implements AutoCloseable {
    public interface Host {
        void onChanged();
        void onMessage(String title, String text);
        default void onFeedback(String title, String text) { onMessage(title, text); }
        default void onActionFeedback(String title, String text, String label, Runnable action) { onMessage(title, text); }
        default void onConnectionRequired(String operation) { onFeedback("请先连接相机", "连接相机后才能" + operation + "。请前往设置连接相机。"); }
        default void onPhotoLiveConnectionRequired() { onConnectionRequired("开始照片直播"); }
        default void onPhotoLiveProjectConnectionRequired(String projectId) { onPhotoLiveConnectionRequired(); }
        default void onPresetRequired() { onFeedback("请先添加预设", "在预设库添加本地预设后，即可为照片套用效果。"); }
        default void onProcessPhoto(DownloadedPhoto photo) { }
        default boolean isLocalWorkspaceVisible() { return true; }
        default void beforeConnect(Runnable connect) { connect.run(); }
        default void onConnectionCancelled() { }
        default void beforePhotoLive(Runnable start) { start.run(); }
        default void beforePhotoLiveProject(String projectId, Runnable start) { beforePhotoLive(start); }
        default void beforeSavePhotos(Runnable save) { save.run(); }
        default void beforeSavePhotos(PhotoSaveTarget target, Runnable save) { beforeSavePhotos(save); }
        default AutoCloseable beginPhotoWork() { return () -> { }; }
    }

    public static final int REQUEST_EXPORT = 7312;
    private static final int PAGE_SIZE = 24;
    private static final int FUJI_PAGE_SIZE = 6;
    private static final int MAX_THUMB_REQUESTS = 6;
    private static final String EXPORT_PREFERENCES = "album-panel";
    private enum Screen { OVERVIEW, GUIDE, DETAIL }

    private final Activity activity;
    private final Host host;
    private final AlbumController controller;
    private final PhotoPresetBatch photoPresets;
    private final PhotoProjectPresets projectPresets;
    private final RawPhotoPanel rawPanel;
    private final Runnable projectPresetListener;
    private final PhotoReceiptPanel receipts;
    private Button projectPresetAction;
    private String receiptRefreshKey = "", aliasRefreshKey = "";
    private PresetSelectionPage presetSelectionPage;
    private int presetSelectionRequest;
    private final boolean ownsController;
    private final boolean localWorkspace;
    private PhotoSaveTarget batchSaveTarget = PhotoSaveTarget.APP_ONLY;
    private DownloadedPhoto lastSavedOriginal;
    private final Runnable connectionListener;
    private final Runnable photoLiveListener;
    private final Runnable photoPresetListener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final ExecutorService dates = Executors.newSingleThreadExecutor();
    private final PhotoDateReader dateReader = new PhotoDateReader();
    private final Runnable photoLibraryListener = this::photoLibraryChanged;
    private volatile int dateRequest;
    private final List<PhotoItem> photos = new ArrayList<>();
    private final List<DownloadedPhoto> downloads = new ArrayList<>();
    private final List<PhotoLibraryCatalog.PhotoGroup> photoGroups = new ArrayList<>();
    private final List<DownloadedPhoto> rawDownloads = new ArrayList<>();
    private PhotoLibraryCatalog libraryCatalog;
    private int libraryRevision;
    private AlertDialog photoVersionsDialog;
    private Button viewerVersions;
    private String viewerPublicationKey = "";
    private PhotoLiveGallery.PublicationState viewerPublication;
    private final List<DownloadedPhoto> allDownloads = new ArrayList<>();
    private final ExecutorService starsWorker = Executors.newSingleThreadExecutor();
    private PhotoStarStore starStore;
    private Set<String> starredKeys = java.util.Collections.emptySet();
    private final Set<String> boundStars = new HashSet<>();
    private boolean starsReady, starSaving, onlyStarredDownloads, onlyStarredLive;
    private PopupMenu photoFilterPopup;
    private String starsError = "";
    private int starRevision;
    private final Runnable starsChanged = this::loadStars;
    private final Set<Long> selected = new LinkedHashSet<>();
    private final Set<String> selectedLocal = new LinkedHashSet<>();
    private final Map<Long, DownloadedPhoto> savedThisSession = new HashMap<>();
    private final Set<String> pendingThumbs = new HashSet<>();
    private final Set<String> failedThumbs = new HashSet<>();
    private final ArrayDeque<PhotoItem> downloadQueue = new ArrayDeque<>();
    private final List<String> downloadErrors = new ArrayList<>();
    private boolean recoveringDownloads;
    private int downloadRecoveryRequest;
    private int observedRecoveryCount = -1;
    private View downloadRecoveryAction;
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private UiPalette palette;
    private LinearLayout root;
    private GridView grid;
    private PhotoAdapter adapter;
    private ListView downloadDateList;
    private DownloadDateAdapter downloadDateAdapter;
    private android.os.Parcelable downloadDateSavedState;
    private List<PhotoDateGroups.Group> downloadGroups = java.util.Collections.emptyList();
    private int groupedDownloadCount = -1;
    private String downloadGroupDay = "";
    private final AlbumPaging paging = new AlbumPaging();
    private AlbumPaging.Request pendingPage;
    private int browseEpoch;
    private TextView transferLabel, transferFile, transferBytes;
    private ProgressBar transferBar;
    private Screen screen = Screen.OVERVIEW;
    private ConnectionInfo connection;
    private boolean sharedCameraConnected, sharedCameraPreparing;
    private String sharedCameraError = "";
    private Runnable retrySharedCamera;
    private boolean downloadedTab, connecting, loadingDownloads, localLoaded, selectionMode;
    private boolean liveTab, liveDetail;
    private PhotoLiveState liveState;
    private String liveDetailIdentity = "";
    private final List<PhotoLiveState.LivePhoto> liveDetailPhotos = new ArrayList<>();
    private final List<PhotoLiveProject> liveProjects = new ArrayList<>();
    private final List<LiveGridPhoto> liveGridPhotos = new ArrayList<>();
    private final List<DownloadedPhoto> projectDetailPhotos = new ArrayList<>();
    private boolean projectDetail, loadingLiveProjects, liveProjectsDirty, selectNewLiveProject;
    private String selectedLiveProjectId = "", liveProjectsError = "", previousLiveProjectId = "";
    private Button liveProjectChoice;
    private android.widget.ImageButton liveProjectInfo;
    private AlertDialog projectChooser;
    private ProjectChoiceAdapter projectChooserAdapter;
    private ListView projectChooserList;
    private FrameLayout projectChooserRegion;
    private TextView projectChooserEmpty, projectChooserError;
    private String projectChooserKey = "";
    private AlertDialog projectInfoDialog, projectRenameDialog, liveProjectCreateDialog;
    private ProjectInfoViews projectInfo;
    private TextView liveGridEmpty;
    private ListView liveGrid;
    private LivePhotoAdapter liveAdapter;
    private LiveRowsAdapter liveRowsAdapter;
    private Button liveBackTop;
    private ProgressBar livePresetProgress;
    private TextView livePresetDetail;
    private ImageView liveHero;
    private TextView liveHeroPlaceholder;
    private PhotoPreview liveHeroPreview;
    private Bitmap liveHeroBitmap;
    private LiveGridPhoto liveHeroPhoto;
    private String liveHeroKey = "";
    private String liveHeroProjectId = "", liveHeroDisplayedKey = "";
    private boolean liveHeroLoading;
    private int liveHeroRequest;
    private int liveProjectsRevision;
    private String liveGridSourceKey = "";
    private TextView liveHeading, liveSummary, livePhotoCount;
    private FrameLayout liveCanvas, liveTransferPanel;
    private TextView livePercent, liveStats, liveSelectionCount;
    private LinearLayout liveSelectionBar;
    private Button liveFilter;
    private int liveProjectSavedCount, liveProjectPendingCount;
    private Button liveAction, liveRetry;
    private Button liveSelect;
    private ProgressBar liveProgress;
    private String liveHistoryKey = "";
    private int liveSavedCount;
    private boolean ownsConnectionRequest;
    private long connectionIntent;
    private Runnable pendingConnectionStart;
    private boolean closed, downloading, cancelRemaining, exporting;
    private boolean batchReadInFlight;
    private int batchPendingSaves, batchRun;
    private boolean addressManuallyEdited;
    private int epoch, sessionNumber, batchTotal, batchCompleted, batchFailed, batchCancelled, batchUnconfirmed;
    private int batchGallerySaved, batchGalleryFailed;
    private boolean publishingDownload, presetWasActive, presetCancelRequested;
    private TextView presetBatchTitle, presetBatchDetail;
    private ProgressBar presetBatchProgress;
    private Button presetBatchCancel, presetBatchView;
    private AlertDialog presetPicker, deleteDialog;
    private android.os.Parcelable liveGridSavedState;
    private long receivedBytes, expectedBytes;
    private String cameraError = "", localError = "", connectionError = "", batchMessage = "", currentFilename = "";
    private String cameraAddress = "", portText = String.valueOf(AlbumController.DEFAULT_PORT);
    private CameraProtocol cameraProtocol = CameraProtocol.NIKON;
    private boolean fujiAutoAddress = true;
    private String resolvedAutoAddress = "";
    private PhotoItem remoteDetail;
    private DownloadedPhoto localDetail;
    private Bitmap detailBitmap;
    private String detailImageKey = "";
    private PhotoPreview remotePreview;
    private PhotoImageView viewerImage;
    private PhotoFilterEditor filterEditor;
    private boolean viewerEditing;
    private View viewerTools;
    private boolean preparingEditedExport;
    private Bitmap viewerPhoto;
    private View viewerTop, viewerBottom;
    private TextView viewerStatus,viewerPreset;
    private Button viewerSave;
    private android.widget.ImageButton viewerStar;
    private HoldToCompare viewerComparison;
    private FrameLayout viewerCompareLayer;
    private Bitmap comparisonOriginal;
    private String comparisonImageKey = "";
    private volatile int comparisonRequest;
    private boolean comparisonUnavailable;
    private PhotoItem pendingNextDetail;
    private TextView photoCountLabel, pageStatus;
    private PullRefreshLayout albumRefresh;
    private View cameraPageFooter;
    private ProgressBar cameraPageSpinner;
    private boolean refreshingCamera;
    private boolean viewerControlsVisible = true, previewLoading;
    private int previewRequest;
    private long previewReceived, previewTotal;
    private String previewError = "";
    private android.os.Parcelable gridSavedState;
    private boolean resetGridPosition;
    private boolean restoringGridPosition;
    private ScrollView guideScroll;
    private int guideScrollY;

    public AlbumPanel(Activity activity, Host host) {
        this(activity, host, new AlbumController(activity), true, null, null);
    }

    /** The application runtime owns a shared controller across Activity recreation. */
    public AlbumPanel(Activity activity, Host host, AlbumController controller) {
        this(activity, host, controller, false, null, null);
    }

    public AlbumPanel(Activity activity, Host host, AlbumController controller, PhotoPresetBatch photoPresets) {
        this(activity, host, controller, false, photoPresets, null);
    }

    public AlbumPanel(Activity activity, Host host, AlbumController controller, PhotoPresetBatch photoPresets, PhotoProjectPresets projectPresets) {
        this(activity, host, controller, false, photoPresets, projectPresets);
    }

    /** Local editing shares application workers, without taking over the Sync page's camera listeners. */
    public AlbumPanel(Activity activity, Host host, AlbumController controller, PhotoPresetBatch photoPresets, PhotoProjectPresets projectPresets, boolean localWorkspace) {
        this(activity, host, controller, false, photoPresets, projectPresets, localWorkspace);
    }

    private AlbumPanel(Activity activity, Host host, AlbumController controller, boolean ownsController, PhotoPresetBatch photoPresets, PhotoProjectPresets projectPresets) {
        this(activity, host, controller, ownsController, photoPresets, projectPresets, false);
    }
    private AlbumPanel(Activity activity, Host host, AlbumController controller, boolean ownsController, PhotoPresetBatch photoPresets, PhotoProjectPresets projectPresets, boolean localWorkspace) {
        if (controller == null) throw new IllegalArgumentException("缺少相册连接管理器。");
        this.activity = activity;
        this.host = host;
        this.controller = controller;
        this.photoPresets = photoPresets;
        this.projectPresets = projectPresets;
        this.localWorkspace = localWorkspace;
        receipts = new PhotoReceiptPanel(activity, controller, new PhotoReceiptPanel.Host() {
            @Override public void onResumeProject(String id) { requestResumeProject(id); }
            @Override public void onRetryProject(String id) { requestRetryProject(id); }
            @Override public void onFeedback(String title, String text) { host.onFeedback(title, text); }
        });
        receipts.setPresetStatusProvider(identity -> projectPresets == null ? "" : projectPresets.statusFor(selectedLiveProjectId, identity));
        projectPresetListener = () -> { if (!closed) { updatePhotoLiveView(); receipts.refresh(); } };
        if (projectPresets != null) projectPresets.addObserver(projectPresetListener);
        this.ownsController = ownsController;
        rawPanel = new RawPhotoPanel(activity, controller, new RawPhotoPanel.Host() {
            @Override public void onChanged() { if (!closed) { changed(); updateViewerStatus(); } }
            @Override public void onFeedback(String title, String text) { if (!closed) host.onFeedback(title, text); }
            @Override public void onConnectionRequired(String operation) { if (!closed) host.onConnectionRequired(operation); }
            @Override public void beforeSavePhotos(Runnable save) { host.beforeSavePhotos(save); }
            @Override public void beforeSavePhotos(PhotoSaveTarget target,Runnable save) { host.beforeSavePhotos(target,save); }
            @Override public AutoCloseable beginPhotoWork() { return host.beginPhotoWork(); }
            @Override public boolean isOtherPhotoWorkBusy() {
                return downloading || exporting || publishingDownload || presetBusy()
                        || controller.isDeletingDownloadedPhotos() || PhotoAlbumSaver.get(activity).isBusy();
            }
        });
        this.palette = new UiPalette(activity);
        onlyStarredDownloads = activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE).getBoolean("starred-downloads", false);
        onlyStarredLive = activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE).getBoolean("starred-live", false);
        loadStars();
        PhotoStars.get(activity).addObserver(starsChanged);
        cameraProtocol = CameraConnectionPreferences.loadProtocol(activity);
        fujiAutoAddress = CameraConnectionPreferences.loadFujiAutoAddress(activity);
        cameraAddress = CameraConnectionPreferences.loadHost(activity);
        portText = CameraConnectionPreferences.loadPortText(activity);
        addressManuallyEdited = !cameraAddress.isEmpty();
        connectionListener = this::controllerStateChanged;
        if (!localWorkspace) controller.setConnectionListener(connectionListener);
        if (!localWorkspace) controller.addPhotoLibraryObserver(photoLibraryListener);
        liveState = controller.photoLiveState();
        photoPresetListener = this::photoPresetChanged;
        if (photoPresets != null) { presetWasActive = photoPresets.state().active; photoPresets.addObserver(photoPresetListener); }
        liveSavedCount = liveState.completed;
        selectedLiveProjectId = activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE)
                .getString("photo-live-project", "");
        if (selectedLiveProjectId.isEmpty() && !liveState.projectId.isEmpty()) selectedLiveProjectId = liveState.projectId;
        liveTab = liveState.enabled || activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE).getBoolean("photo-live-tab", false);
        photoLiveListener = this::photoLiveChanged;
        if (!localWorkspace) controller.setPhotoLiveListener(photoLiveListener);
        if (!localWorkspace) {
            ConnectionInfo restored = controller.connectionInfo();
            if (restored != null) restoreConnection(restored);
            else if (controller.hasLiveSession()) restoreCameraSession(controller.sessionConnectionInfo());
            else connecting = controller.isConnecting();
        }
        if (!localWorkspace) { refreshDownloads(); refreshLiveProjects(); }
    }

    private void controllerStateChanged() {
        if (closed) return;
        int recoveryCount = controller.pendingDownloadRecoveryCount();
        boolean recoveryChanged = recoveryCount != observedRecoveryCount; observedRecoveryCount = recoveryCount;
        ConnectionInfo current = controller.connectionInfo();
        if (current != null) {
            // The initiating UI's callback already restores its own page. A new
            // UI can instead recover a connection started by a destroyed one.
            if (ownsConnectionRequest && connecting) return;
            if (connection != current) { restoreConnection(current); changed(); }
            else if (recoveryChanged) changed();
            return;
        }
        if (controller.isConnecting()) {
            if (downloading) finishInterruptedBatch();
            if (recoveringDownloads) { recoveringDownloads = false; downloadRecoveryRequest++; }
            if (connection != null && !controller.hasLiveSession()) resetRemoteConnection(false);
            if (!connecting) { connecting = true; connectionError = ""; changed(); }
            return;
        }
        if (controller.hasLiveSession()) {
            if (ownsConnectionRequest && connecting) return;
            ConnectionInfo held = controller.sessionConnectionInfo();
            if (connection != held || connecting || !cameraError.equals(controller.browseError())) {
                restoreCameraSession(held); changed();
            }
            return;
        }
        // Runtime.disconnect() retires the controller generation, so an old
        // download callback may never arrive. Retire busy state here instead.
        if (downloading) finishInterruptedBatch();
        if (recoveringDownloads) { recoveringDownloads = false; downloadRecoveryRequest++; }
        boolean hadConnection = connection != null;
        if (!hadConnection && !connecting) { if (recoveryChanged) changed(); return; }
        connection = null; connecting = false; ownsConnectionRequest = false;
        paging.reset(false);
        if (hadConnection) cameraError = "无线连接已断开。已下载照片仍可查看，请重新连接相机后浏览。";
        else connectionError = "相册连接未完成，请检查相机网络后重试。";
        changed();
    }

    private void restoreConnection(ConnectionInfo restored) {
        recoveringDownloads = false; downloadRecoveryRequest++;
        refreshingCamera = false;
        retireGrid(true);
        epoch++; dateRequest++;
        photos.clear(); clearSelection(); savedThisSession.clear();
        pendingThumbs.clear(); failedThumbs.clear(); selectionMode = false; loadingDownloads = false;
        clearDetailImage();
        if (screen == Screen.DETAIL && remoteDetail != null && localDetail == null) {
            screen = Screen.OVERVIEW; remoteDetail = null;
        }
        sessionNumber++;
        connection = restored; connecting = false; ownsConnectionRequest = false;
        configureConnection(restored.host(), restored.port(), restored.protocol());
        cameraError = connectionError = "";
        paging.reset(restored.totalHandles() > 0);
        if (downloading) finishInterruptedBatch();
        refreshDownloads();
    }

    /** A camera session remains connected even before a photo inventory is requested. */
    private void restoreCameraSession(ConnectionInfo restored) {
        if (restored == null) return;
        restoreConnection(restored);
        paging.reset(false);
        cameraError = controller.browseError();
    }

    public View createView() {
        palette = new UiPalette(activity);
        root = vertical();
        root.setBackgroundColor(palette.surface);
        render();
        return root;
    }

    public String title() {
        if (rawPanel.isOpen()) return rawPanel.title();
        if (screen == Screen.GUIDE) return "Wi-Fi 连接";
        if (screen == Screen.DETAIL) return "照片详情";
        return selectionMode ? "已选择 " + selectionCount() + " 张" : localWorkspace ? "相册" : "同步";
    }

    public boolean canGoBack() { return rawPanel.isOpen() || screen != Screen.OVERVIEW || selectionMode; }
    public boolean isImmersive() { return !rawPanel.isOpen() && screen == Screen.DETAIL; }
    public void releasePhotoComparison() { if (viewerComparison != null) viewerComparison.release(); if(filterEditor!=null)filterEditor.flushDraft(); }
    public String navigationKey() {
        if (rawPanel.isOpen()) return "RAW:" + rawPanel.title();
        String detail = screen != Screen.DETAIL ? "" : remoteDetail != null ? Long.toString(remoteDetail.handle())
                : localDetail != null ? localDetail.file().getAbsolutePath() : "";
        return screen.name() + ":" + downloadedTab + ":" + liveTab + ":" + selectionMode + ":" + onlyStarredDownloads + ":" + onlyStarredLive + ":" + detail;
    }

    public void goBack() {
        if (filterEditor != null && filterEditor.dismissPopup()) return;
        if (rawPanel.isOpen()) { rawPanel.goBack(); return; }
        if (screen != Screen.OVERVIEW) {
            screen = Screen.OVERVIEW;
            remoteDetail = null;
            localDetail = null;
            liveDetail = false; liveDetailIdentity = ""; liveDetailPhotos.clear();
            projectDetail = false; projectDetailPhotos.clear();
            clearDetailImage();
        } else if (selectionMode) { selectionMode = false; clearSelection(); }
        applyDownloadFilter(); changed();
    }

    public boolean isConnected() { return !closed && !connecting && connection != null && controller.isConnected(); }
    public boolean hasCameraConnection() { return !closed && controller.hasLiveSession(); }
    public boolean supportsPictureControl() { return controller.supportsPictureControl(); }
    public boolean wantsCameraPhotos() { return !closed && !downloadedTab && screen != Screen.GUIDE; }

    /** Returns to Photo Live after a user-requested connection; project creation still needs confirmation. */
    public void resumePhotoLiveAfterConnection() {
        if (!canShowProjectDialog()) return;
        screen = Screen.OVERVIEW;
        remoteDetail = null; localDetail = null;
        liveDetail = false; liveDetailIdentity = ""; liveDetailPhotos.clear();
        projectDetail = false; projectDetailPhotos.clear();
        clearDetailImage();
        cancelCameraBrowse();
        liveTab = true; downloadedTab = false; selectionMode = false; clearSelection();
        resetGridPosition = true;
        activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE).edit().putBoolean("photo-live-tab", true).apply();
        changed();
        PhotoLiveState state = controller.photoLiveState();
        if (isConnected() && !state.enabled && !state.starting) requestPhotoLive();
    }

    public void resumePhotoLiveProjectAfterConnection(String projectId) {
        if (!canShowProjectDialog()) return;
        screen = Screen.OVERVIEW; remoteDetail = null; localDetail = null; liveDetail = projectDetail = false;
        clearDetailImage(); cancelCameraBrowse(); liveTab = true; downloadedTab = false;
        selectionMode = false; clearSelection(); selectLiveProject(projectId);
        activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE).edit().putBoolean("photo-live-tab", true).apply();
        changed(); requestResumeProject(projectId);
    }
    public void setSharedCameraState(boolean connected, boolean preparing, String error, Runnable retry) {
        sharedCameraConnected = connected; sharedCameraPreparing = preparing;
        sharedCameraError = error == null ? "" : error; retrySharedCamera = retry;
    }
    public void followSharedConnection() {
        if (closed) return;
        boolean userConnection = ownsConnectionRequest && pendingConnectionStart != null;
        connectionIntent++; pendingConnectionStart = null; ownsConnectionRequest = false; connecting = false;
        screen = Screen.OVERVIEW; downloadedTab = false;
        cameraError = connectionError = "";
        controllerStateChanged(); changed();
        if (userConnection) connectedFeedback(connectionModel());
    }
    public boolean isConnecting() { return !closed && (connecting || controller.isConnecting()); }
    public boolean isTransferBusy() { return !closed && (downloading || recoveringDownloads || exporting || rawPanel.isBusy() || controller.isRawBusy() || controller.isDeletingDownloadedPhotos()); }
    public void clearPendingDownloadRecovery() {
        controller.clearDownloadRecovery(); recoveringDownloads = false; downloadRecoveryRequest++;
        if (downloading) cancelRemaining = true;
    }
    public boolean isPhotoLiveEnabled() { return !closed && controller.photoLiveState().enabled; }
    public boolean isConnectionGuide() { return screen == Screen.GUIDE; }
    public String connectionModel() { ConnectionInfo live = controller.sessionConnectionInfo(); return live == null ? "" : live.model().trim().isEmpty() ? "相机" : live.model(); }
    public String connectionHost() { ConnectionInfo live = controller.sessionConnectionInfo(); return live == null ? "" : live.host(); }
    public int connectionPort() { ConnectionInfo live = controller.sessionConnectionInfo(); return live == null ? 0 : live.port(); }
    public CameraProtocol configuredProtocol() { return cameraProtocol; }
    public CameraProtocol connectionProtocol() { ConnectionInfo live = controller.sessionConnectionInfo(); return live == null ? null : live.protocol(); }
    public String getConfiguredHost() { return cameraAddress.trim(); }
    public int getConfiguredPort() {
        try { int port = Integer.parseInt(portText.trim()); return port >= 1 && port <= 65535 ? port : cameraProtocol.defaultPort; }
        catch (NumberFormatException error) { return cameraProtocol.defaultPort; }
    }

    /** Synchronize the sole address editor after an existing camera session is adopted. */
    public void configureConnection(String host, int port) {
        ConnectionInfo held = controller.sessionConnectionInfo();
        configureConnection(host, port, held == null ? CameraProtocol.NIKON : held.protocol());
    }

    private void configureConnection(String host, int port, CameraProtocol protocol) {
        if (closed || host == null || host.trim().isEmpty() || port < 1 || port > 65535) return;
        cameraProtocol = protocol;
        cameraAddress = host.trim(); portText = Integer.toString(port);
        addressManuallyEdited = true;
        CameraConnectionPreferences.save(activity, cameraAddress, port, protocol);
    }

    /** The Activity routes this editor exclusively through the Settings destination. */
    public void openConnectionSettings() { if (!closed) showGuide(); }

    public void disconnect() {
        if (closed) return;
        connectionIntent++;
        pendingConnectionStart = null;
        resetRemoteConnection(true);
    }

    /** A host can fail before the controller starts, so no controller callback will settle the UI. */
    public void connectionStartFailed(String message) {
        connectionStartFailed(message, pendingConnectionStart);
    }

    /** Ignore a late failure for an already consumed or superseded deferred start. */
    public void connectionStartFailed(String message, Runnable expectedStart) {
        if (expectedStart == null || pendingConnectionStart != expectedStart) return;
        connectionIntent++;
        pendingConnectionStart = null;
        connecting = false;
        ownsConnectionRequest = false;
        if (closed) return;
        ConnectionInfo current = controller.connectionInfo();
        if (current != null) restoreConnection(current);
        else if (controller.hasLiveSession()) restoreCameraSession(controller.sessionConnectionInfo());
        else connection = null;
        String details = message == null || message.trim().isEmpty() ? "未能开始连接，请重试。" : message;
        connectionError = "连接未开始，请查看详情后重试。";
        changed();
        feedbackDetails("连接未开始", "请检查当前任务或连接设置后重试。", details);
    }

    private void connectedFeedback(String model) {
        String name = model == null ? "" : model.trim();
        String available = controller.supportsPictureControl() ? "可浏览照片或导入预设。" : "可浏览和下载 JPEG 照片，实验适配待真机验证。";
        host.onFeedback("相机已连接", name.isEmpty() ? available : name + " · " + available);
    }

    private void feedbackDetails(String title, String summary, String details) {
        if (!canShowProjectDialog()) return;
        host.onActionFeedback(title, summary, "详情", () -> {
            if (!canShowProjectDialog()) return;
            new AlertDialog.Builder(activity).setTitle(title).setMessage(details)
                    .setPositiveButton("关闭", null).show();
        });
    }

    /** Stops old photo work before the controller drains and hands off the live session. */
    public void transferForPictureControl(AlbumController.Callback<PtpIpClient> callback) {
        if (closed || !hasCameraConnection() || isTransferBusy()) {
            callback.onError("当前相册连接无法移交，请结束照片传输后重试。"); return;
        }
        controller.stopPhotoLive();
        retireGrid(!downloadedTab);
        epoch++; dateRequest++; loadingDownloads = false;
        pendingThumbs.clear();
        controller.transferForPictureControl(new AlbumController.Callback<PtpIpClient>() {
            @Override public void onSuccess(PtpIpClient owner) {
                if (closed) {
                    if (ownsController) owner.close();
                    else callback.onSuccess(owner);
                    return;
                }
                resetRemoteConnection(false);
                callback.onSuccess(owner);
            }
            @Override public void onError(String message) { callback.onError(message); }
        });
    }

    private void resetRemoteConnection(boolean disconnect) {
        recoveringDownloads = false; downloadRecoveryRequest++;
        refreshingCamera = false;
        // The old grid may still be retained while Settings is visible. Retire it before
        // changing the adapter's backing list, so it cannot save an obsolete row index.
        retireGrid(!downloadedTab);
        epoch++; dateRequest++;
        if (disconnect) controller.disconnect();
        connection = null;
        connecting = false;
        ownsConnectionRequest = false;
        paging.reset(false);
        loadingDownloads = false;
        photos.clear();
        clearSelection();
        savedThisSession.clear();
        selectionMode = false;
        pendingThumbs.clear();
        failedThumbs.clear();
        clearDetailImage();
        if (downloading) finishInterruptedBatch();
        cameraError = "";
        if (screen == Screen.DETAIL && remoteDetail != null && localDetail == null) {
            screen = Screen.OVERVIEW;
            remoteDetail = null;
            clearDetailImage();
        }
        changed();
        refreshDownloads();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        rawPanel.close();
        dismissPhotoFilter();
        dismissDefaultPresetPicker();
        receipts.close();
        PhotoStars.get(activity).removeObserver(starsChanged);
        if (projectPresets != null) projectPresets.removeObserver(projectPresetListener);
        if (projectChooser != null) projectChooser.dismiss();
        if (projectRenameDialog != null) projectRenameDialog.dismiss();
        if (projectInfoDialog != null) projectInfoDialog.dismiss();
        if (liveProjectCreateDialog != null) liveProjectCreateDialog.dismiss();
        if (presetPicker != null) presetPicker.dismiss();
        if (deleteDialog != null) deleteDialog.dismiss();
        if (photoVersionsDialog != null) photoVersionsDialog.dismiss();
        epoch++;
        // A shared controller may still need to start a user-requested connection
        // after another owned session finishes closing. Only its UI is retired.
        if (ownsController) { connectionIntent++; pendingConnectionStart = null; }
        boolean detached = controller.clearConnectionListener(connectionListener);
        controller.clearPhotoLiveListener(photoLiveListener);
        controller.removePhotoLibraryObserver(photoLibraryListener);
        if (filterEditor != null) filterEditor.close();
        if (photoPresets != null) photoPresets.removeObserver(photoPresetListener);
        if (ownsController) controller.close();
        else if (detached) { controller.cancelPreview(); controller.cancelBrowseRequests(); }
        dateRequest++; dates.shutdownNow(); images.shutdownNow(); starsWorker.shutdown();
        main.removeCallbacksAndMessages(null);
        thumbs.evictAll();
        clearPhotoComparison(); detailBitmap = null;
        releaseLiveHero();
        releasePreview(false);
        if (viewerImage != null) { viewerImage.setOnSingleTap(null); viewerImage.setOnSwipe(null); viewerImage.setOnPageBoundary(null, false); viewerImage.setPhoto(null); }
        viewerImage = null; viewerPhoto = null;
        root = null;
    }

    private void render() {
        dismissPhotoFilter();
        if (root == null || closed) return;
        rememberGridPosition();
        if (liveGrid != null) liveGridSavedState = liveGrid.onSaveInstanceState();
        if (guideScroll != null) guideScrollY = guideScroll.getScrollY();
        guideScroll = null;
        grid = null;
        adapter = null;
        downloadDateList = null; downloadDateAdapter = null;
        transferBar = null;
        transferLabel = transferFile = transferBytes = null;
        downloadRecoveryAction = null;
        photoCountLabel = pageStatus = null;
        albumRefresh = null; cameraPageFooter = null; cameraPageSpinner = null;
        liveHeading = liveSummary = null;
        liveAction = liveRetry = null; liveProgress = null; liveProjectChoice = null; liveProjectInfo = null;
        liveSelect = null;
        liveCanvas = liveTransferPanel = null; livePercent = liveStats = liveSelectionCount = null;
        liveSelectionBar = null; liveFilter = null;
        presetBatchTitle = presetBatchDetail = null; presetBatchProgress = null; presetBatchCancel = null;
        liveHero = null; liveHeroPlaceholder = null;
        liveGrid = null; liveAdapter = null; liveRowsAdapter = null; liveBackTop = null;
        livePresetProgress = null; livePresetDetail = null;
        liveGridEmpty = null; liveHistoryKey = "initial"; liveGridSourceKey = "";
        root.removeAllViews();
        if (rawPanel.isOpen()) { root.addView(rawPanel.createView(palette), new LinearLayout.LayoutParams(-1, 0, 1)); return; }
        if (screen == Screen.GUIDE) renderGuide();
        else if (screen == Screen.DETAIL) renderDetail();
        else renderOverview();
    }

    private void changed() {
        if (closed) return;
        rememberGridPosition();
        grid = null;
        adapter = null;
        downloadDateList = null; downloadDateAdapter = null;
        host.onChanged();
    }

    private void renderOverview() {
        LinearLayout header = vertical();
        header.setPadding(dp(24), dp(2), dp(24), 0);
        header.addView(text(downloadedTab ? "按拍摄日期整理，离线也可查看" : liveTab ? "拍完，照片就到手机。" : "浏览预览，按需下载原图", 14, palette.secondary, false));
        LinearLayout tabs = horizontal();
        tabs.addView(tab("相机照片", !downloadedTab && !liveTab, () -> chooseTab(false)), weight());
        tabs.addView(tab("照片直播", liveTab, this::choosePhotoLive), weight());
        tabs.addView(tab("已下载", downloadedTab, () -> chooseTab(true)), weight());
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(-1, dp(48));
        tabsParams.topMargin = dp(14);
        header.addView(tabs, tabsParams);
        root.addView(header);
        if (liveTab) { renderPhotoLive(); return; }
        renderPresetBatchStatus(root);

        if (!batchMessage.isEmpty()) addNotice(root, batchMessage, false);
        if (!downloadedTab) renderDownloadRecovery(root);
        if (controller.isDeletingDownloadedPhotos()) addNotice(root, "正在删除照片…", false);
        String error = downloadedTab ? localError : cameraError;
        if (!error.isEmpty()) addNotice(root, error, true);

        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(24), dp(10), dp(14), dp(6));
        String caption = downloadedTab ? "已下载 · " + downloads.size() + " 张" : "相机照片";
        if (!downloadedTab && !photos.isEmpty()) caption += " · " + photos.size() + (paging.hasMore() ? "+" : " 张");
        photoCountLabel = text(caption, 15, palette.text, true);
        if (downloadedTab) addFilterHeading(heading, photoCountLabel, false);
        else heading.addView(photoCountLabel, new LinearLayout.LayoutParams(0, -2, 1));
        Button rawFiles = textAction("RAW", () -> {
            if (showPhotoTaskBlocked("查看 RAW 文件")) return;
            rawPanel.open(downloadedTab);
        });
        rawFiles.setContentDescription(downloadedTab ? "查看手机已保存的 NEF 原文件" : "查看相机 NEF 原文件");
        if (downloadedTab || controller.supportsRawDownload()) heading.addView(rawFiles, new LinearLayout.LayoutParams(-2, dp(48)));
        if (downloadedTab ? !downloads.isEmpty() : isConnected() && !photos.isEmpty()) {
            Button select = textAction(selectionMode ? "取消选择" : "选择", () -> {
                if (showPhotoTaskBlocked("选择照片")) return;
                selectionMode = !selectionMode;
                clearSelection();
                changed();
            });
            select.setMinWidth(0); select.setMinimumWidth(0);
            heading.addView(select);
        }
        int count = downloadedTab ? downloads.size() : photos.size();
        if (count > 0 || downloadedTab || isConnected()) root.addView(heading);
        if (count > 0 && downloadedTab) renderDownloadedDates();
        else if (count > 0) {
            if (hasDownloadRecoveryLayout()) boundDownloadRecoveryHeader();
            grid = new GridView(activity);
            grid.setNumColumns(GridView.AUTO_FIT);
            grid.setColumnWidth(dp(104));
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setHorizontalSpacing(dp(8));
            grid.setVerticalSpacing(dp(8));
            boolean compactAlbum = activity.getResources().getConfiguration().screenHeightDp < 500;
            grid.setPadding(dp(20), dp(4), dp(20), dp(compactAlbum ? 100 : 156));
            grid.setClipToPadding(false);
            grid.setDrawSelectorOnTop(true);
            grid.setSelector(new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), null, null));
            adapter = new PhotoAdapter(downloadedTab);
            grid.setAdapter(adapter);
            grid.setOnItemClickListener((parent, view, position, id) -> {
                if (downloadedTab && selectionMode) toggleLocalSelection(downloads.get(position));
                else if (downloadedTab) openLocal(downloads.get(position));
                else if (selectionMode) toggleSelection(photos.get(position));
                else openRemote(photos.get(position));
            });
            grid.setOnItemLongClickListener((parent, view, position, id) -> {
                if (downloading || exporting) return false;
                selectionMode = true;
                if (downloadedTab) selectedLocal.add(downloads.get(position).file().getAbsolutePath());
                else selected.add(photos.get(position).handle());
                changed();
                return true;
            });
            FrameLayout cameraListStage = new FrameLayout(activity);
            cameraListStage.addView(wrapAlbumContent(grid), new FrameLayout.LayoutParams(-1, -1));
            LinearLayout footer = horizontal(); footer.setGravity(Gravity.CENTER);
            footer.setBackground(new InsetDrawable(interactive(palette.glass, 14), 0, dp(6), 0, dp(6)));
            footer.setPadding(dp(16), 0, dp(16), 0); footer.setFocusable(true);
            cameraPageSpinner = new ProgressBar(activity, null, android.R.attr.progressBarStyleSmall);
            cameraPageSpinner.setIndeterminateTintList(ColorStateList.valueOf(palette.primary));
            LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(16), dp(16)); spinnerParams.rightMargin = dp(8);
            footer.addView(cameraPageSpinner, spinnerParams);
            pageStatus = text("", 12, palette.secondary, false); pageStatus.setSingleLine(true); pageStatus.setEllipsize(TextUtils.TruncateAt.END);
            pageStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); footer.addView(pageStatus);
            footer.setOnClickListener(view -> {
                if (!paging.isPaused() || selectionMode || !requirePhotoProcessingIdle("读取照片") || !requireCameraPhotos("读取照片")) return;
                paging.retry(); loadNextPage();
            });
            cameraPageFooter = footer;
            FrameLayout.LayoutParams footerParams = new FrameLayout.LayoutParams(compactAlbum ? dp(136) : -2, dp(48), Gravity.BOTTOM | (compactAlbum ? Gravity.END : Gravity.CENTER_HORIZONTAL));
            footerParams.setMargins(dp(compactAlbum ? 12 : 24), 0, dp(compactAlbum ? 12 : 24), dp(compactAlbum ? 8 : 96)); cameraListStage.addView(footer, footerParams);
            root.addView(cameraListStage, new LinearLayout.LayoutParams(-1, 0, 1)); updateCameraPageFooter();
            final GridView visibleGrid = grid;
            restoringGridPosition = true;
            if (gridSavedState != null) grid.onRestoreInstanceState(gridSavedState);
            grid.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                public void onGlobalLayout() {
                    if (grid != visibleGrid) { visibleGrid.getViewTreeObserver().removeOnGlobalLayoutListener(this); return; }
                    if (visibleGrid.getChildCount() == 0) return;
                    restoringGridPosition = false;
                    visibleGrid.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    visibleGrid.post(() -> maybeLoadMore(visibleGrid));
                }
            });
            grid.setOnScrollListener(new AbsListView.OnScrollListener() {
                public void onScrollStateChanged(AbsListView view, int state) {}
                public void onScroll(AbsListView view, int first, int visible, int total) {
                    updateCameraPageFooter();
                    if (visible > 0 && total > 0 && first + visible >= total) visibleGrid.post(() -> maybeLoadMore(visibleGrid));
                }
            });
            grid.post(() -> maybeLoadMore(visibleGrid));
        } else {
            LinearLayout empty = vertical();
            empty.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            boolean compactEmpty = activity.getResources().getConfiguration().screenHeightDp < 500;
            empty.setPadding(dp(compactEmpty ? 16 : 28), dp(compactEmpty ? 12 : 44), dp(compactEmpty ? 16 : 28), dp(compactEmpty ? 12 : 24));
            if (!compactEmpty) {
                FrameLayout illustration = new FrameLayout(activity);
                illustration.setBackground(shape(palette.card, 26));
                ImageView icon = icon(downloadedTab ? AppIcon.Kind.ALBUM : AppIcon.Kind.CAMERA, palette.secondary);
                illustration.addView(icon, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
                empty.addView(illustration, new LinearLayout.LayoutParams(dp(84), dp(84)));
            }
            String title, body;
            boolean pending = downloadedTab ? loadingDownloads : (sharedCameraPreparing || connecting || paging.isLoading());
            if (pending) {
                title = downloading ? "正在下载照片" : downloadedTab ? "正在加载已下载照片" : sharedCameraPreparing ? "正在加载相机照片列表" : connecting ? "正在连接相机" : "正在查找 JPEG 照片";
                body = "请稍候";
            } else if (downloadedTab) {
                title = onlyStarredDownloads ? "还没有星标照片" : "还没有已下载照片";
                body = onlyStarredDownloads ? "打开照片，轻点星标即可收藏。\n再次点击上方星标可查看全部照片。" : "从相机下载的原图会保存在这里，\n断开连接也能继续查看。";
            } else if (!isConnected() && (sharedCameraConnected || hasCameraConnection())) {
                title = "相机已连接，照片暂不可用";
                body = !sharedCameraError.isEmpty() ? sharedCameraError : !controller.browseError().isEmpty()
                        ? controller.browseError() : "读取照片列表后即可浏览，无需重新连接相机。";
            } else if (!isConnected()) {
                title = "连接相机，开始浏览";
                body = "在底部「设置」中连接相机 Wi-Fi，\n照片会显示在这里。";
            } else if (paging.hasMore()) {
                title = "当前范围未找到 JPEG";
                body = "正在继续查找存储卡中的 JPEG 照片。";
            } else {
                title = "没有找到 JPEG 照片";
                body = "JPG 照片会显示在这里。若只拍摄 RAW，可点上方「RAW」查看并保存 NEF 原文件。";
            }
            TextView emptyTitle = text(title, compactEmpty ? 18 : 19, palette.text, true);
            emptyTitle.setGravity(Gravity.CENTER);
            addSpaced(empty, emptyTitle, compactEmpty ? 0 : 16);
            TextView emptyBody = text(body, compactEmpty ? 13 : 14, palette.secondary, false);
            emptyBody.setGravity(Gravity.CENTER);
            addSpaced(empty, emptyBody, 8);
            if (pending) addSpaced(empty, spinner(), 16);
            else if (!downloadedTab && !isConnected() && (sharedCameraConnected || hasCameraConnection()) && retrySharedCamera != null) {
                addSpaced(empty, button("重试读取照片", true, retrySharedCamera), 16);
            }
            ScrollView emptyScroll = new ScrollView(activity); emptyScroll.setFillViewport(true);
            emptyScroll.setPadding(0, 0, 0, dp(100));
            emptyScroll.setClipToPadding(false);
            if (!downloadedTab && hasDownloadRecoveryLayout()) {
                LinearLayout content = takeOverviewHeader();
                content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
                ScrollView page = new ScrollView(activity); page.setFillViewport(true);
                page.setPadding(0, 0, 0, dp(100)); page.setClipToPadding(false);
                page.addView(content, new ScrollView.LayoutParams(-1, -2));
                root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
            } else {
                emptyScroll.addView(empty, new ScrollView.LayoutParams(-1, -2));
                root.addView(wrapAlbumContent(emptyScroll), new LinearLayout.LayoutParams(-1, 0, 1));
            }
            if (!downloadedTab && photos.isEmpty()) root.post(() -> maybeLoadMore(null));
        }
        if (!downloadedTab && isConnected() && grid == null) {
            if (paging.isPaused()) {
                Button retry = button("重试读取照片", false, () -> {
                    if (showPhotoTaskBlocked("读取照片")) return;
                    if (selectionMode) { host.onFeedback("请先取消选择", "退出照片多选后，再重试读取相机照片。"); return; }
                    if (!requireCameraPhotos("读取相机照片")) return;
                    paging.retry(); loadNextPage();
                });
                root.addView(retry);
            }
        }
        if (downloading) renderTransfer(root);
        else if (selectionMode) renderSelectionActions(root, !downloadedTab);
    }

    private View wrapAlbumContent(View content) {
        albumRefresh = new PullRefreshLayout(activity, this::canPullRefreshAlbum, this::refreshAlbumFromPull);
        albumRefresh.setColors(palette.primary, palette.card); albumRefresh.setContent(content);
        albumRefresh.setRefreshing(downloadedTab ? loadingDownloads : refreshingCamera);
        return albumRefresh;
    }

    private boolean canPullRefreshAlbum() {
        if (closed || screen != Screen.OVERVIEW || liveTab || selectionMode || downloading || exporting || presetBusy()
                || rawPanel.isBusy() || controller.isRawBusy() || controller.isDeletingDownloadedPhotos()) return false;
        if (downloadedTab) return !loadingDownloads;
        PhotoLiveState live = controller.photoLiveState();
        return !refreshingCamera && !connecting && !controller.isConnecting() && !paging.isLoading()
                && controller.hasLiveSession() && !live.enabled && !live.receiving && !live.starting;
    }

    private void refreshAlbumFromPull() {
        if (!canPullRefreshAlbum()) { if (albumRefresh != null) albumRefresh.setRefreshing(false); return; }
        resetGridPosition = true;
        if (downloadedTab) { refreshDownloads(); return; }
        cancelCameraBrowse();
        refreshingCamera = true; connecting = true; ownsConnectionRequest = true;
        final int request = epoch;
        controller.retryBrowse(new AlbumController.Callback<ConnectionInfo>() {
            @Override public void onSuccess(ConnectionInfo refreshed) {
                if (!current(request)) return;
                refreshingCamera = false; restoreConnection(refreshed); render();
            }
            @Override public void onError(String message) {
                if (!current(request)) return;
                refreshingCamera = false; connecting = false; ownsConnectionRequest = false;
                if (controller.hasLiveSession()) restoreCameraSession(controller.sessionConnectionInfo());
                cameraError = message; render();
            }
        });
    }

    private void updateCameraPageFooter() {
        if (cameraPageFooter == null || pageStatus == null || cameraPageSpinner == null) return;
        boolean loading = paging.isLoading();
        boolean atEnd = grid != null && grid.getChildCount() > 0 && grid.getLastVisiblePosition() >= photos.size() - 1;
        cameraPageFooter.setVisibility(loading || atEnd ? View.VISIBLE : View.GONE);
        String label = loading ? "加载中…" : paging.isPaused() ? "加载失败 · 轻点重试" : paging.hasMore() ? "上滑加载更多" : "已加载全部照片";
        if (!label.contentEquals(pageStatus.getText())) pageStatus.setText(label);
        cameraPageSpinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        cameraPageFooter.setContentDescription(paging.isPaused() ? cameraError + "，轻点重试加载照片" : label);
        cameraPageFooter.setTooltipText(paging.isPaused() ? cameraError : label);
        cameraPageFooter.setClickable(paging.isPaused() && !selectionMode && !downloading && !exporting);
    }

    private View tab(String label, boolean checked, Runnable action) {
        FrameLayout group = new FrameLayout(activity);
        if (checked) {
            View capsule = new View(activity);
            GradientDrawable fill = shape(palette.glass, 17);
            fill.setStroke(dp(0.5f), palette.rim);
            capsule.setBackground(fill);
            group.addView(capsule, new FrameLayout.LayoutParams(-1, dp(34), Gravity.CENTER));
        }
        Button tab = textAction(label, action);
        tab.setTextSize(13);
        tab.setPadding(dp(8), 0, dp(8), 0);
        tab.setTextColor(checked ? palette.text : palette.secondary);
        tab.setTypeface(Typeface.create(checked ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        tab.setBackground(interactive(Color.TRANSPARENT, 17));
        tab.setSelected(checked);
        tab.setContentDescription(label + (checked ? "，当前标签" : ""));
        group.addView(tab, new FrameLayout.LayoutParams(-1, dp(48), Gravity.CENTER));
        return group;
    }

    private void chooseTab(boolean local) {
        if (downloadedTab == local && !liveTab) return;
        liveTab = false;
        activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE).edit().putBoolean("photo-live-tab", false).apply();
        releaseLiveHero();
        downloadedTab = local;
        resetGridPosition = true;
        selectionMode = false;
        clearSelection();
        if (local && !localLoaded && !loadingDownloads) refreshDownloads();
        changed();
        if (!local && photos.isEmpty() && paging.hasMore()) loadNextPage();
    }

    private void choosePhotoLive() {
        if (liveTab) return;
        cancelCameraBrowse();
        liveTab = true; downloadedTab = false; selectionMode = false; clearSelection();
        resetGridPosition = true;
        activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE).edit().putBoolean("photo-live-tab", true).apply();
        changed();
    }

    private void cancelCameraBrowse() {
        browseEpoch++;
        controller.cancelBrowseRequests();
        if (pendingPage != null && paging.fail(pendingPage)) paging.retry();
        pendingPage = null;
        pendingThumbs.clear();
    }

    private void loadStars() {
        starsWorker.execute(() -> {
            try {
                PhotoStarStore store = new PhotoStarStore(new File(activity.getFilesDir(), "photo-stars"));
                Set<String> snapshot = store.snapshot(); starStore = store;
                main.post(() -> { if (!closed) { starsReady = true; starsError = ""; applyStars(snapshot); } });
            } catch (Exception error) {
                main.post(() -> { if (!closed) { starsError = error.getMessage() == null ? "星标记录暂时无法读取。" : error.getMessage(); starsReady = false; } });
            }
        });
    }

    private String starKey(DownloadedPhoto photo) {
        if (photo == null) return "";
        PhotoLibraryCatalog.PhotoGroup group = photoGroup(photo);
        return PhotoStarStore.localKey(group != null && group.original != null ? group.original.file() : photo.file());
    }
    private String starKey(PhotoItem photo) {
        String identity = photo == null ? "" : controller.stablePhotoIdentity(photo);
        return identity.isEmpty() ? "" : PhotoStarStore.remoteKey(identity);
    }
    private boolean starred(DownloadedPhoto photo) {
        PhotoLibraryCatalog.PhotoGroup group = photoGroup(photo);
        if (group != null) for (DownloadedPhoto member : group.photos()) if (starredKeys.contains(PhotoStarStore.localKey(member.file()))) return true;
        return starredKeys.contains(starKey(photo));
    }

    private PhotoLibraryCatalog.PhotoGroup photoGroup(DownloadedPhoto photo) { return PhotoLibraryCatalog.groupFor(photoGroups, photo); }
    private DownloadedPhoto displayedVersion(DownloadedPhoto photo) {
        PhotoLibraryCatalog.PhotoGroup group = photoGroup(photo); return group == null ? photo : group.display.withPhotoDate(group.photoDate);
    }
    private boolean samePhoto(DownloadedPhoto a, DownloadedPhoto b) {
        if (a == null || b == null) return false;
        if (a.file().equals(b.file())) return true;
        PhotoLibraryCatalog.PhotoGroup group = photoGroup(a); return group != null && group.findByFile(b.file()) != null;
    }
    private boolean starred(PhotoItem photo) { return photo != null && (starredKeys.contains(starKey(photo)) || starred(savedThisSession.get(photo.handle()))); }

    private void applyStars(Set<String> snapshot) {
        starredKeys = snapshot; starRevision++;
        if (screen != Screen.DETAIL) applyDownloadFilter(); liveGridSourceKey = "";
        hydrateStarAliases();
        if (root != null) {
            if (screen == Screen.DETAIL) updateViewerStar();
            else if (liveTab) updatePhotoLiveView();
            else if (downloadedTab) render();
            if (adapter != null) adapter.notifyDataSetChanged();
            if (downloadDateAdapter != null) downloadDateAdapter.notifyDataSetChanged();
        }
    }

    private void applyDownloadFilter() {
        downloads.clear();
        for (DownloadedPhoto photo : allDownloads) if (!onlyStarredDownloads || starred(photo)) downloads.add(photo);
        downloadGroups = PhotoDateGroups.group(downloads); groupedDownloadCount = downloads.size(); downloadGroupDay = currentDownloadDay();
    }

    private boolean requireStars() {
        if (starsReady && starStore != null) return true;
        host.onActionFeedback(starsError.isEmpty() ? "正在读取星标" : "星标暂不可用",
                starsError.isEmpty() ? "请稍候再试。" : "星标记录未更改，可以重试读取。", "重试", this::loadStars);
        return false;
    }

    private void bindStar(String identity, DownloadedPhoto photo) {
        if (photo == null || identity == null || !identity.matches("[0-9a-f]{64}") || !starsReady || starStore == null) return;
        String remote = PhotoStarStore.remoteKey(identity), local = starKey(photo), binding = remote + local;
        if (!starredKeys.contains(remote) && !starredKeys.contains(local)) return;
        if (!boundStars.add(binding)) return;
        starsWorker.execute(() -> {
            try { starStore.bindLocal(remote, local); Set<String> next = starStore.snapshot(); main.post(() -> { if (!closed) applyStars(next); }); }
            catch (Exception error) { main.post(() -> { boundStars.remove(binding); if (!closed) host.onFeedback("星标同步未完成", "原有星标保留，可稍后重试。"); }); }
        });
    }

    private void toggleCurrentStar() {
        if (!requireStars() || starSaving) return;
        String key = localDetail != null ? starKey(localDetail) : starKey(remoteDetail);
        if (key.isEmpty()) { host.onFeedback("照片暂时无法标星", "请连接原相机后重试；已下载照片可离线标星。"); return; }
        final boolean next = !(starred(localDetail) || starred(remoteDetail));
        final String remoteKey = starKey(remoteDetail), localKey = localDetail == null ? "" : starKey(localDetail);
        List<String> memberStarKeys = new ArrayList<>(); PhotoLibraryCatalog.PhotoGroup starGroup = photoGroup(localDetail);
        if (starGroup != null) for (DownloadedPhoto member : starGroup.photos()) memberStarKeys.add(PhotoStarStore.localKey(member.file()));
        starSaving = true; updateViewerStar();
        starsWorker.execute(() -> {
            try {
                if (!remoteKey.isEmpty() && !localKey.isEmpty()) starStore.bindLocal(remoteKey, localKey);
                if (!next) for (String memberKey : memberStarKeys) starStore.setStarred(memberKey, false);
                starStore.setStarred(key, next); Set<String> snapshot = starStore.snapshot();
                main.post(() -> { if (!closed) { starSaving = false; applyStars(snapshot); host.onFeedback(next ? "已标星" : "已取消星标", next ? "可在项目或已下载中筛选星标照片。" : "照片和原图均保留。"); } });
            } catch (Exception error) { main.post(() -> { if (!closed) { starSaving = false; updateViewerStar(); host.onMessage("星标未保存", error.getMessage()); } }); }
        });
    }

    private void updateViewerStar() {
        if (viewerStar == null) return;
        boolean checked = starred(localDetail) || starred(remoteDetail);
        viewerStar.setImageDrawable(new AppIcon(checked ? AppIcon.Kind.STAR_FILLED : AppIcon.Kind.STAR, checked ? 0xFFFFD369 : Color.WHITE,
                activity.getResources().getDisplayMetrics().density));
        viewerStar.setContentDescription(checked ? "取消星标" : "标星照片"); viewerStar.setSelected(checked);
        viewerStar.setEnabled(!starSaving); viewerStar.setAlpha(starSaving ? 0.5f : 1f);
    }

    private void addFilterHeading(LinearLayout heading, TextView count, boolean live) {
        Button filter = photoFilter(live);
        count.setMaxLines(2); count.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout label = new LinearLayout(activity) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                filter.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                        MeasureSpec.makeMeasureSpec(dp(48), MeasureSpec.EXACTLY));
                if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
                    count.setMaxWidth(Math.max(0, MeasureSpec.getSize(widthSpec) - filter.getMeasuredWidth() - dp(4)));
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        label.setOrientation(LinearLayout.HORIZONTAL); label.setGravity(Gravity.CENTER_VERTICAL);
        label.addView(count, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(-2, dp(48)); filterParams.setMarginStart(dp(4));
        label.addView(filter, filterParams);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private Button photoFilter(boolean live) {
        Button filter = textAction("筛选", () -> {});
        filter.setOnClickListener(view -> showPhotoFilter(filter, live));
        filter.setMinWidth(dp(48)); filter.setMinimumWidth(dp(48)); filter.setTextSize(13);
        filter.setPadding(dp(8), dp(8), dp(8), dp(8)); filter.setSingleLine(true);
        updatePhotoFilter(filter, live); return filter;
    }

    private void updatePhotoFilter(Button filter, boolean live) {
        boolean checked = live ? onlyStarredLive : onlyStarredDownloads;
        filter.setText(checked ? "星标" : "筛选");
        AppIcon chevron = new AppIcon(AppIcon.Kind.CHEVRON_DOWN, checked ? palette.primary : palette.secondary,
                activity.getResources().getDisplayMetrics().density); chevron.setBounds(0, 0, dp(12), dp(12));
        AppIcon check = checked ? new AppIcon(AppIcon.Kind.CHECK, palette.primary, activity.getResources().getDisplayMetrics().density) : null;
        if (check != null) check.setBounds(0, 0, dp(14), dp(14));
        filter.setCompoundDrawablesRelative(check, null, chevron, null); filter.setCompoundDrawablePadding(dp(4));
        filter.setSelected(checked); filter.setTextColor(checked ? palette.primary : palette.secondary);
        filter.setBackground(interactive(checked ? palette.primaryContainer : Color.TRANSPARENT, 14));
        filter.setContentDescription("照片筛选，" + (checked ? "当前仅看星标" : "当前未筛选") + "，展开筛选菜单");
    }

    private void showPhotoFilter(View anchor, boolean live) {
        if (!canShowProjectDialog() || !anchor.isAttachedToWindow() || !anchor.isShown()) return;
        dismissPhotoFilter();
        PopupMenu popup = new PopupMenu(new ContextThemeWrapper(activity,
                palette.dark ? R.style.AppThemeDark : R.style.AppThemeLight), anchor, Gravity.START);
        popup.getMenu().add(0, 1, 0, "星标").setCheckable(true).setChecked(live ? onlyStarredLive : onlyStarredDownloads);
        photoFilterPopup = popup;
        View.OnAttachStateChangeListener anchorState = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { if (photoFilterPopup == popup) dismissPhotoFilter(); }
        };
        anchor.addOnAttachStateChangeListener(anchorState);
        popup.setOnDismissListener(menu -> {
            anchor.removeOnAttachStateChangeListener(anchorState);
            if (photoFilterPopup == popup) photoFilterPopup = null;
        });
        popup.setOnMenuItemClickListener(item -> {
            if (photoFilterPopup != popup || closed || screen != Screen.OVERVIEW || live != liveTab || (!live && !downloadedTab)) return true;
            dismissPhotoFilter();
            if (!requireStars()) return true;
            if (live) onlyStarredLive = !onlyStarredLive; else onlyStarredDownloads = !onlyStarredDownloads;
            activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE).edit()
                    .putBoolean(live ? "starred-live" : "starred-downloads", live ? onlyStarredLive : onlyStarredDownloads).apply();
            clearSelection(); selectionMode = false; resetGridPosition = true; liveGridSourceKey = "";
            if (live) { updatePhotoLiveView(); updateLiveSelection(); }
            else { applyDownloadFilter(); changed(); }
            return true;
        });
        popup.show();
    }

    private void dismissPhotoFilter() {
        PopupMenu popup = photoFilterPopup; photoFilterPopup = null;
        if (popup != null) popup.dismiss();
    }

    private void hydrateStarAliases() {
        if (!starsReady || selectedLiveProjectId.isEmpty()) return;
        int hash = 0, count = 0;
        for (String key : starredKeys) if (key.startsWith("r:")) { hash += key.hashCode(); count++; }
        if (count == 0) return;
        PhotoLiveProject project = findLiveProject(selectedLiveProjectId);
        String key = selectedLiveProjectId + ":" + hash + ":" + (project == null ? 0 : project.photoCount);
        if (key.equals(aliasRefreshKey)) return;
        aliasRefreshKey = key;
        controller.listPhotoLiveReceipts(selectedLiveProjectId, new AlbumController.Callback<List<PhotoLiveReceipt>>() {
            @Override public void onSuccess(List<PhotoLiveReceipt> items) { if (!closed) for (PhotoLiveReceipt item : items) bindStar(item.identity, item.saved); }
            @Override public void onError(String ignored) { if (key.equals(aliasRefreshKey)) aliasRefreshKey = ""; }
        });
    }

    private boolean showPhotoTaskBlocked(String operation) {
        String reason = controller.isDeletingDownloadedPhotos() ? "正在删除照片，请等待删除完成。"
                : rawPanel.isBusy() || controller.isRawBusy() ? "正在读取或保存 NEF 原文件，请等待完成。"
                : recoveringDownloads ? "正在核对未完成照片，可先取消未完成下载。"
                : downloading ? "正在下载照片，请等待下载完成，或先停止当前下载。"
                : exporting ? "正在保存照片到手机相册，请等待保存完成。" : "";
        if (reason.isEmpty()) return false;
        host.onFeedback("暂时无法" + operation, reason); return true;
    }

    private boolean requirePhotoProcessingIdle(String operation) {
        if (showPhotoTaskBlocked(operation)) return false;
        if (presetBusy()) { host.onFeedback("正在套用预设", "请等待当前处理完成，或先停止处理后再" + operation + "。"); return false; }
        return true;
    }

    private boolean requireCameraPhotos(String operation) {
        if (isConnected()) return true;
        if (connecting || sharedCameraPreparing || controller.isConnecting()) {
            host.onFeedback("正在准备相机照片", "请等待连接和照片列表读取完成后，再" + operation + "。");
        } else if (sharedCameraConnected || hasCameraConnection()) {
            Runnable retry = retrySharedCamera;
            if (retry == null) host.onFeedback("相机照片尚未就绪", "读取照片列表后，才能" + operation + "。");
            else host.onActionFeedback("相机照片尚未就绪", "读取照片列表后，才能" + operation + "。", "读取照片", () -> {
                if (!canShowProjectDialog() || !requirePhotoProcessingIdle("读取照片")) return;
                if (isConnected()) { host.onFeedback("照片已就绪", "可以继续" + operation + "。"); return; }
                if (connecting || sharedCameraPreparing || controller.isConnecting()) {
                    host.onFeedback("正在准备相机照片", "请等待当前连接和照片列表读取完成。"); return;
                }
                if (!sharedCameraConnected && !hasCameraConnection()) { host.onConnectionRequired(operation); return; }
                retry.run();
            });
        } else host.onConnectionRequired(operation);
        return false;
    }

    private boolean requirePhotoSelection(String operation) {
        if (selectionCount() > 0) return true;
        host.onFeedback("请先选择照片", "选择至少一张照片后，再" + operation + "。"); return false;
    }

    private void requestPhotoLive() {
        if (closed) return;
        if (showUnsupportedPhotoLive()) return;
        PhotoLiveState state = controller.photoLiveState();
        if (state.enabled) { controller.stopPhotoLive(); return; }
        if (state.starting) { host.onFeedback("正在准备直播", "正在确认现有照片，请等待准备完成。"); return; }
        if (state.receiving) { host.onFeedback("照片仍在接收", "请等待当前原图接收和保存完成后，再开始新的直播。"); return; }
        if (showPhotoTaskBlocked("开始照片直播")) return;
        if (!isConnected() && !connecting && !sharedCameraPreparing && !controller.isConnecting()
                && !sharedCameraConnected && !hasCameraConnection()) {
            host.onPhotoLiveConnectionRequired(); return;
        }
        if (!requireCameraPhotos("开始照片直播")) return;
        showNewLiveProject();
    }

    private void renderPhotoLive() {
        FrameLayout stage = new FrameLayout(activity);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        // A single recycled list owns the hero and the photo rows. There is no nested scrolling grid.
        liveGrid = new ListView(activity); liveGrid.setContentDescription("照片直播，整页滚动的项目照片");
        liveGrid.setDivider(null); liveGrid.setDividerHeight(0); liveGrid.setClipToPadding(false);
        liveGrid.setPadding(dp(20), dp(8), dp(20), dp(108));
        liveGrid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        liveGrid.setItemsCanFocus(true);
        LinearLayout content = vertical();
        liveGrid.addHeaderView(content, null, false);
        stage.addView(liveGrid, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout projectRow = horizontal(); projectRow.setGravity(Gravity.CENTER_VERTICAL);
        liveProjectChoice = liveCompactAction("选择项目", this::chooseLiveProject, palette.text);
        liveProjectChoice.setTextSize(16); liveProjectChoice.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        liveProjectChoice.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        liveProjectChoice.setSingleLine(true); liveProjectChoice.setEllipsize(TextUtils.TruncateAt.END);
        liveProjectChoice.setPaddingRelative(0, 0, dp(8), 0);
        AppIcon chevron = new AppIcon(AppIcon.Kind.CHEVRON_DOWN, palette.secondary, density());
        chevron.setBounds(0, 0, dp(15), dp(15));
        liveProjectChoice.setCompoundDrawablesRelative(null, null, chevron, null); liveProjectChoice.setCompoundDrawablePadding(dp(8));
        projectRow.addView(liveProjectChoice, new LinearLayout.LayoutParams(0, dp(44), 1));
        liveProjectInfo = new android.widget.ImageButton(activity);
        liveProjectInfo.setImageDrawable(new AppIcon(AppIcon.Kind.INFO, palette.secondary, density()));
        liveProjectInfo.setPadding(dp(12), dp(12), dp(12), dp(12));
        liveProjectInfo.setBackground(interactive(Color.TRANSPARENT, 22)); liveProjectInfo.setContentDescription("项目详情");
        liveProjectInfo.setOnClickListener(view -> showProjectInfo());
        projectRow.addView(liveProjectInfo, new LinearLayout.LayoutParams(dp(44), dp(44))); content.addView(projectRow);

        liveCanvas = new LiveCanvas(); liveCanvas.setBackground(shape(0xFF10151B, 21)); liveCanvas.setClipToOutline(true);
        liveCanvas.setContentDescription("直播当前照片");
        liveHero = new ImageView(activity); liveHero.setScaleType(ImageView.ScaleType.CENTER_CROP);
        liveHero.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        liveCanvas.addView(liveHero, new FrameLayout.LayoutParams(-1, -1));
        liveHeroPlaceholder = text("新照片会显示在这里", 13, 0xFFCAD0D8, false); liveHeroPlaceholder.setGravity(Gravity.CENTER);
        liveHeroPlaceholder.setPadding(dp(18), dp(58), dp(18), dp(88));
        liveCanvas.addView(liveHeroPlaceholder, new FrameLayout.LayoutParams(-1, -1));
        liveCanvas.setFocusable(true); liveCanvas.setOnClickListener(view -> openLiveHero());

        projectPresetAction = liveCompactAction("默认 · 不使用", this::chooseProjectPreset, Color.WHITE);
        projectPresetAction.setTextSize(12); projectPresetAction.setSingleLine(true); projectPresetAction.setEllipsize(TextUtils.TruncateAt.END);
        projectPresetAction.setPadding(dp(12), 0, dp(10), 0); projectPresetAction.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        projectPresetAction.setBackground(interactive(0xDC1B2532, 23));
        AppIcon presetIcon = new AppIcon(AppIcon.Kind.CHEVRON_DOWN, 0xFFD4DCE8, density()); presetIcon.setBounds(0, 0, dp(13), dp(13));
        projectPresetAction.setCompoundDrawablesRelative(null, null, presetIcon, null); projectPresetAction.setCompoundDrawablePadding(dp(6));
        FrameLayout.LayoutParams presetParams = new FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP | Gravity.START);
        presetParams.setMargins(dp(11), dp(11), dp(62), 0); liveCanvas.addView(projectPresetAction, presetParams);
        liveCanvas.addOnLayoutChangeListener((view,l,t,r,b,ol,ot,or,ob) -> projectPresetAction.setMaxWidth(Math.max(dp(100), r-l-dp(82))));
        android.widget.ImageButton expand = new android.widget.ImageButton(activity);
        expand.setImageDrawable(new AppIcon(AppIcon.Kind.EXPAND, Color.WHITE, density())); expand.setPadding(dp(13), dp(13), dp(13), dp(13));
        expand.setBackground(interactive(0xB8202731, 22)); expand.setContentDescription("放大当前照片"); expand.setOnClickListener(view -> openLiveHero());
        FrameLayout.LayoutParams expandParams = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP | Gravity.END);
        expandParams.topMargin = expandParams.rightMargin = dp(11); liveCanvas.addView(expand, expandParams);

        liveTransferPanel = new FrameLayout(activity); liveTransferPanel.setBackground(shape(0xE8202732, 15));
        liveTransferPanel.setContentDescription("当前原图接收进度");
        LinearLayout lines = vertical(); lines.setPadding(dp(12), dp(8), dp(10), dp(14));
        LinearLayout top = horizontal(); top.setGravity(Gravity.CENTER_VERTICAL);
        liveHeading = text("", 12, Color.WHITE, true); liveHeading.setGravity(Gravity.CENTER_VERTICAL); liveHeading.setSingleLine(true); liveHeading.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(liveHeading, new LinearLayout.LayoutParams(0, dp(24), 1));
        livePercent = text("", 15, Color.WHITE, true); livePercent.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        livePercent.setSingleLine(true); livePercent.setMinWidth(dp(52)); livePercent.setFontFeatureSettings("tnum");
        top.addView(livePercent, new LinearLayout.LayoutParams(-2, dp(24))); lines.addView(top);
        LinearLayout bottom = horizontal(); bottom.setGravity(Gravity.CENTER_VERTICAL);
        liveSummary = text("", 10, 0xFFCCD5E1, false); liveSummary.setGravity(Gravity.CENTER_VERTICAL); liveSummary.setSingleLine(true); liveSummary.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        liveSummary.setFontFeatureSettings("tnum"); liveSummary.setPadding(0, 0, dp(54), 0); bottom.addView(liveSummary, new LinearLayout.LayoutParams(-1, dp(26))); lines.addView(bottom);
        liveAction = liveCompactAction("开始", this::onLiveTransferAction, 0xFFA6CCFF); liveAction.setTextSize(12);
        liveTransferPanel.addView(lines, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams transferActionParams = new FrameLayout.LayoutParams(-2, dp(44), Gravity.BOTTOM | Gravity.END);
        transferActionParams.setMargins(0, 0, dp(8), dp(7)); liveTransferPanel.addView(liveAction, transferActionParams);
        // Explicit full-width horizontal track; the generic spinner helper is intentionally not used.
        liveProgress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        liveProgress.setMax(1000); liveProgress.setProgressTintList(ColorStateList.valueOf(0xFFA6CCFF));
        liveProgress.setIndeterminateTintList(ColorStateList.valueOf(0xFFA6CCFF));
        liveProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF586576));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM);
        progressParams.setMargins(dp(12), 0, dp(12), dp(10)); liveTransferPanel.addView(liveProgress, progressParams);
        FrameLayout.LayoutParams transferParams = new FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM);
        transferParams.setMargins(dp(10), 0, dp(10), dp(10)); liveCanvas.addView(liveTransferPanel, transferParams);
        LinearLayout.LayoutParams canvasParams = new LinearLayout.LayoutParams(-1, -2); canvasParams.topMargin = dp(6); content.addView(liveCanvas, canvasParams);

        LinearLayout summaryRow = horizontal(); summaryRow.setGravity(Gravity.CENTER_VERTICAL);
        liveStats = text("", 11, palette.secondary, false); liveStats.setGravity(Gravity.CENTER_VERTICAL); liveStats.setSingleLine(true); liveStats.setEllipsize(TextUtils.TruncateAt.END);
        liveStats.setOnClickListener(view -> showLivePresetStatus());
        LinearLayout progressWords = vertical();
        progressWords.addView(liveStats, new LinearLayout.LayoutParams(-1, dp(21)));
        livePresetDetail = text("", 10, palette.secondary, false); livePresetDetail.setSingleLine(true);
        livePresetDetail.setEllipsize(TextUtils.TruncateAt.MIDDLE); livePresetDetail.setFontFeatureSettings("tnum");
        livePresetDetail.setGravity(Gravity.CENTER_VERTICAL); livePresetDetail.setOnClickListener(view -> showLivePresetStatus());
        progressWords.addView(livePresetDetail, new LinearLayout.LayoutParams(-1, dp(20)));
        livePresetProgress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        livePresetProgress.setMax(1000); livePresetProgress.setProgressTintList(ColorStateList.valueOf(palette.primary));
        livePresetProgress.setProgressBackgroundTintList(ColorStateList.valueOf(palette.outline));
        livePresetProgress.setContentDescription("照片套用预设进度");
        progressWords.addView(livePresetProgress, new LinearLayout.LayoutParams(-1, dp(3)));
        LinearLayout.LayoutParams progressWordsParams = new LinearLayout.LayoutParams(0, dp(44), 1);
        progressWordsParams.rightMargin = dp(10); summaryRow.addView(progressWords, progressWordsParams);
        Button records = liveCompactAction("接收记录", this::showReceipts, palette.primary); records.setTextSize(12);
        summaryRow.addView(records, new LinearLayout.LayoutParams(-2, dp(44))); content.addView(summaryRow);
        LinearLayout photoHeading = horizontal(); photoHeading.setGravity(Gravity.CENTER_VERTICAL);
        livePhotoCount = text("项目照片", 14, palette.text, true); livePhotoCount.setGravity(Gravity.CENTER_VERTICAL); livePhotoCount.setSingleLine(true); livePhotoCount.setEllipsize(TextUtils.TruncateAt.END);
        photoHeading.addView(livePhotoCount, new LinearLayout.LayoutParams(0, dp(44), 1));
        liveFilter = photoFilter(true); liveFilter.setTextSize(12); photoHeading.addView(liveFilter, new LinearLayout.LayoutParams(-2, dp(44)));
        liveSelect = liveCompactAction("选择", () -> {
            if (showPhotoTaskBlocked("选择照片")) return;
            selectionMode = !selectionMode; clearSelection(); updateLiveSelection();
        }, palette.primary); liveSelect.setTextSize(12);
        photoHeading.addView(liveSelect, new LinearLayout.LayoutParams(-2, dp(44))); content.addView(photoHeading);
        liveGridEmpty = text("", 13, palette.secondary, false); liveGridEmpty.setGravity(Gravity.CENTER);
        liveGridEmpty.setPadding(dp(20), dp(32), dp(20), dp(32)); content.addView(liveGridEmpty);
        liveAdapter = new LivePhotoAdapter(); liveRowsAdapter = new LiveRowsAdapter(); liveGrid.setAdapter(liveRowsAdapter);
        liveGrid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { updateLiveBackTop(); }
        });
        liveBackTop = liveCompactAction("顶部", () -> {
            if (liveGrid != null) { liveGrid.smoothScrollToPositionFromTop(0, 0, 260); }
        }, palette.primary);
        liveBackTop.setContentDescription("返回直播页顶部"); liveBackTop.setBackground(interactive(palette.glass, 22));
        liveBackTop.setElevation(dp(3)); liveBackTop.setVisibility(View.GONE);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(dp(64), dp(44), Gravity.BOTTOM | Gravity.END);
        topParams.setMargins(dp(20), 0, dp(20), dp(116)); stage.addView(liveBackTop, topParams);
        liveSelectionBar = horizontal(); liveSelectionBar.setPadding(dp(12), dp(3), dp(6), dp(3)); liveSelectionBar.setGravity(Gravity.CENTER_VERTICAL);
        liveSelectionBar.setBackground(shape(palette.glass, 16)); liveSelectionBar.setElevation(dp(5));
        liveSelectionCount = text("已选 0 张", 12, palette.text, false); liveSelectionCount.setGravity(Gravity.CENTER_VERTICAL); liveSelectionCount.setSingleLine(true);
        liveSelectionBar.addView(liveSelectionCount, new LinearLayout.LayoutParams(0, dp(44), 1));
        liveSelectionBar.addView(liveCompactAction("全选", this::selectVisibleLivePhotos, palette.primary), new LinearLayout.LayoutParams(-2, dp(44)));
        liveSelectionBar.addView(liveCompactAction("星标", this::starSelectedLivePhotos, palette.primary), new LinearLayout.LayoutParams(-2, dp(44)));
        liveSelectionBar.addView(liveCompactAction("导出", this::exportSelectedEdits, palette.primary), new LinearLayout.LayoutParams(-2, dp(44)));
        liveSelectionBar.addView(liveCompactAction("套用效果", this::choosePhotoPreset, palette.primary), new LinearLayout.LayoutParams(-2, dp(44)));
        FrameLayout.LayoutParams selectionParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        selectionParams.setMargins(dp(20), 0, dp(20), dp(108)); stage.addView(liveSelectionBar, selectionParams);
        updatePhotoLiveView(); updateLiveSelection();
        if (liveGridSavedState != null) liveGrid.onRestoreInstanceState(liveGridSavedState);
        liveGrid.post(this::updateLiveBackTop);
    }

    private float density() { return activity.getResources().getDisplayMetrics().density; }

    private Button liveCompactAction(String label, Runnable action, int color) {
        Button result = textAction(label, action); result.setTextColor(color); result.setTextSize(12);
        result.setMinWidth(dp(44)); result.setMinimumWidth(dp(44)); result.setMinHeight(dp(44)); result.setMinimumHeight(dp(44));
        result.setPadding(dp(8), 0, dp(8), 0); result.setSingleLine(true); return result;
    }

    private final class LiveCanvas extends FrameLayout {
        LiveCanvas() { super(activity); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            float screenHeight = activity.getResources().getDisplayMetrics().heightPixels / density();
            float font = activity.getResources().getConfiguration().fontScale;
            int cap = screenHeight < 540 ? 140 : screenHeight < 720 || font > 1.35f ? 205 : 284;
            int height = Math.min(dp(cap), Math.max(dp(130), Math.round(width * .74f)));
            // This depends only on viewport and font, never progress, buttons or grid contents.
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    private void openLiveHero() {
        if (liveHeroPhoto == null || liveHeroBitmap == null) { host.onFeedback("还没有可查看的照片", "新照片预览就绪后会显示在这里，已保存照片仍可在下方查看。"); return; }
        if (liveHeroPhoto.saved != null) openProjectPhoto(liveHeroPhoto.saved);
        else if (liveHeroPhoto.live != null) openLivePhoto(liveHeroPhoto.live);
    }

    private boolean liveHasRetry(PhotoLiveState state) {
        if (!state.error.isEmpty()) return true;
        for (PhotoLiveState.LivePhoto item : state.photos) if (!item.error.isEmpty() || !item.galleryError.isEmpty()) return true;
        return false;
    }

    private void onLiveTransferAction() {
        PhotoLiveState state = controller.photoLiveState();
        if (state.starting) { controller.stopPhotoLive(); return; }
        if (!isConnected()) { if (!selectedLiveProjectId.isEmpty()) host.onPhotoLiveProjectConnectionRequired(selectedLiveProjectId); else host.onPhotoLiveConnectionRequired(); return; }
        if (state.enabled && liveHasRetry(state) && state.transferStage.isEmpty()) { controller.retryPhotoLive(); return; }
        if (!state.enabled && !state.receiving && !selectedLiveProjectId.isEmpty()) { requestResumeProject(selectedLiveProjectId); return; }
        requestPhotoLive();
    }

    private void updateLiveSelection() {
        if (liveSelect == null || liveGrid == null) return;
        liveSelect.setText(selectionMode ? "取消" : "选择");
        if (liveSelectionBar != null) liveSelectionBar.setVisibility(selectionMode ? View.VISIBLE : View.INVISIBLE);
        if (liveSelectionCount != null) liveSelectionCount.setText("已选 " + selectedLocal.size() + " 张");
        liveGrid.setPadding(dp(20), dp(8), dp(20), dp(selectionMode ? 174 : 108));
        // Only selection marks change; decoded images and the main canvas remain attached.
        forEachLiveTile(tile -> {
            LiveGridPhoto photo = tile.photo;
            if (photo == null) return;
            boolean selected = photo.saved != null && selectedLocal.contains(photo.saved.file().getAbsolutePath());
            tile.check.setChecked(selected); tile.check.setVisibility(selectionMode && photo.saved != null ? View.VISIBLE : View.GONE);
            tile.setAlpha(selectionMode && photo.saved == null ? .65f : 1f);
        });
        updateLiveBackTop();
    }

    private void updateLiveBackTop() {
        if (liveGrid == null || liveBackTop == null) return;
        View first = liveGrid.getChildCount() == 0 ? null : liveGrid.getChildAt(0);
        boolean away = liveGrid.getFirstVisiblePosition() > 0 || first != null && first.getTop() < -dp(80);
        liveBackTop.setVisibility(away ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) liveBackTop.getLayoutParams();
        int bottom = dp(selectionMode ? 174 : 116);
        if (params.bottomMargin != bottom) { params.bottomMargin = bottom; liveBackTop.setLayoutParams(params); }
    }

    private void forEachLiveTile(java.util.function.Consumer<LiveThumbTile> action) {
        if (liveGrid == null) return;
        for (int i=0; i<liveGrid.getChildCount(); i++) {
            View item = liveGrid.getChildAt(i);
            if (item instanceof LivePhotoRow) for (int j=0; j<((LivePhotoRow)item).getChildCount(); j++) {
                View tile = ((LivePhotoRow)item).getChildAt(j);
                if (tile instanceof LiveThumbTile && tile.getVisibility() == View.VISIBLE) action.accept((LiveThumbTile)tile);
            }
        }
    }

    private void selectVisibleLivePhotos() {
        boolean all = true; int available = 0;
        for (LiveGridPhoto photo : liveGridPhotos) if (photo.saved != null) { available++; all &= selectedLocal.contains(photo.saved.file().getAbsolutePath()); }
        if (available == 0) { host.onFeedback("还没有可选择的照片", "原图保存完成后，才能批量选择。"); return; }
        selectedLocal.clear();
        if (!all) for (LiveGridPhoto photo : liveGridPhotos) if (photo.saved != null) selectedLocal.add(photo.saved.file().getAbsolutePath());
        updateLiveSelection();
    }

    private void starSelectedLivePhotos() {
        if (!requireStars() || starSaving) return;
        List<DownloadedPhoto> chosen = new ArrayList<>(); boolean allStarred = true;
        for (LiveGridPhoto photo : liveGridPhotos) if (photo.saved != null && selectedLocal.contains(photo.saved.file().getAbsolutePath())) { chosen.add(photo.saved); allStarred &= starred(photo.saved); }
        if (chosen.isEmpty()) { host.onFeedback("请先选择照片", "选择已保存照片后，再批量标记星标。"); return; }
        final boolean mark = !allStarred; starSaving = true;
        starsWorker.execute(() -> {
            try { for (DownloadedPhoto photo : chosen) starStore.setStarred(starKey(photo), mark); Set<String> snapshot = starStore.snapshot(); main.post(() -> { if (closed) return; starSaving = false; applyStars(snapshot); updateLiveSelection(); host.onFeedback(mark ? "已标记星标" : "已取消星标", "已更新 " + chosen.size() + " 张照片。"); }); }
            catch (Exception error) { main.post(() -> { starSaving = false; if (!closed) host.onMessage("星标未全部保存", "请重新读取星标后重试。\n" + error.getMessage()); }); }
        });
    }

    private void showNewLiveProject() {
        if (!canShowProjectDialog() || liveProjectCreateDialog != null) return;
        LinearLayout form = vertical(); form.setPadding(dp(24), dp(10), dp(24), dp(4));
        form.addView(text("项目名称", 13, palette.secondary, false));
        EditText name = new EditText(activity); name.setSingleLine(true); name.setTextColor(palette.text);
        name.setTextSize(16); name.setMinHeight(dp(48));
        name.setText(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date()));
        name.setSelectAllOnFocus(true); form.addView(name, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("新建拍摄项目").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("开始直播", null).create();
        final String[] defaultPreset = {"", ""};
        Button presetChoice = textAction("默认预设 · 不使用", () -> {});
        presetChoice.setOnClickListener(view -> chooseDefaultPreset(defaultPreset[0], () -> liveProjectCreateDialog == dialog, (id, title) -> {
            if (liveProjectCreateDialog != dialog) return;
            defaultPreset[0] = id; defaultPreset[1] = title; presetChoice.setText("默认预设 · " + (id.isEmpty() ? "不使用" : title));
        }));
        form.addView(presetChoice, new LinearLayout.LayoutParams(-1, -2));
        addSpaced(form, text("原图自动保存，套用预设的效果图另存。", 12, palette.secondary, false), 4);
        liveProjectCreateDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (liveProjectCreateDialog == dialog) liveProjectCreateDialog = null;
            dismissDefaultPresetPicker();
        });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!canShowProjectDialog() || liveProjectCreateDialog != dialog) return;
            String rawName = name.getText().toString();
            String projectName = rawName.trim();
            String error = projectNameError(rawName);
            if (error != null) {
                name.setError(error); name.requestFocus(); return;
            }
            if (showPhotoTaskBlocked("开始照片直播") || !requireCameraPhotos("开始照片直播")) return;
            dialog.dismiss();
            PhotoSaveChoice.show(activity,"直播保存位置","live:new",PhotoSaveTarget.APP_ONLY,false,(target,extra)->{
            controller.setPhotoLiveSaveTarget(target);
            host.beforePhotoLive(() -> {
                if (closed || activity.isFinishing()) return;
                if (showPhotoTaskBlocked("开始照片直播") || !requireCameraPhotos("开始照片直播")) return;
                cancelCameraBrowse();
                previousLiveProjectId = controller.photoLiveState().projectId; selectNewLiveProject = true;
                AlbumController.PhotoLiveProjectSetup setup = projectPresets == null ? null : projectPresets.setupForNewProject(defaultPreset[0], defaultPreset[1]);
                controller.startPhotoLive(projectName, setup, new AlbumController.Callback<Void>() {
                    @Override public void onSuccess(Void value) { if (!closed) { PhotoSaveChoice.remember(activity,"live:"+controller.photoLiveState().projectId,target);followStartedProject(); refreshLiveProjects(); photoLiveChanged(); } }
                    @Override public void onError(String error) {
                        selectNewLiveProject = false;
                        if (!closed) { photoLiveChanged(); feedbackDetails("照片直播未开始", "请检查相机连接与存储卡后重试。", error); }
                    }
                });
            });});
        }));
        dialog.show();
    }

    private void showReceipts() {
        PhotoLiveProject project = findLiveProject(selectedLiveProjectId);
        if (project == null) { host.onFeedback("请先选择拍摄项目", "新建或选择一个项目后，即可查看接收记录。"); return; }
        final String id = project.id;
        receipts.setPresetStatusProvider(identity -> projectPresets == null ? "" : projectPresets.statusFor(id, identity));
        receipts.setPresetPendingProvider(identity -> projectPresets != null && projectPresets.isPending(id, identity),
                identity -> projectPresets != null && projectPresets.needsRetry(id, identity));
        receipts.show(id, project.name);
    }

    private void requestRetryProject(String id) {
        if (closed) return;
        if (projectPresets != null) projectPresets.retryProject(id);
        controller.listPhotoLiveReceipts(id, new AlbumController.Callback<List<PhotoLiveReceipt>>() {
            @Override public void onSuccess(List<PhotoLiveReceipt> items) {
                if (closed) return;
                for (PhotoLiveReceipt item : items) if (item.saved == null || !item.error.isEmpty()) { requestResumeProject(id); return; }
                host.onFeedback("已重试未完成的效果图", "原图保留，无需连接相机。");
            }
            @Override public void onError(String error) { if (!closed) host.onMessage("接收记录未读取", error); }
        });
    }

    private void requestResumeProject(String id) {
        if (closed) return;
        if (showUnsupportedPhotoLive()) return;
        PhotoLiveProject project = findLiveProject(id);
        if (project == null) { host.onFeedback("项目暂不可用", "请重新选择拍摄项目后再试。"); refreshLiveProjects(); return; }
        PhotoLiveState state = controller.photoLiveState();
        if (state.enabled && id.equals(state.projectId)) {
            if (state.starting) { host.onFeedback("正在准备直播", "照片准备完成后会继续接收。"); return; }
            controller.retryPhotoLive(); if (projectPresets != null) projectPresets.retryProject(id);
            host.onFeedback("已重试未完成的照片", "已保存的原图不会重复下载。"); return;
        }
        if (state.enabled || state.receiving) { host.onFeedback("另一个项目正在直播", "请先停止当前直播，再继续这个项目。"); return; }
        if (!isConnected()) { host.onPhotoLiveProjectConnectionRequired(id); return; }
        if (showPhotoTaskBlocked("继续接收照片")) return;
        PhotoSaveChoice.show(activity,"直播保存位置","live:"+id,PhotoSaveTarget.APP_ONLY,false,(target,extra)->{
        controller.setPhotoLiveSaveTarget(target);
        host.beforePhotoLiveProject(id, () -> {
            if (closed) return;
            if (!isConnected()) { host.onPhotoLiveProjectConnectionRequired(id); return; }
            cancelCameraBrowse();
            controller.resumePhotoLive(id, new AlbumController.Callback<Void>() {
                @Override public void onSuccess(Void value) {
                    if (closed) return; selectLiveProject(id); refreshLiveProjects(); photoLiveChanged();
                    if (projectPresets != null) projectPresets.retryProject(id);
                    host.onFeedback("已继续接收", "新照片和待重试照片将保存在原项目中。");
                }
                @Override public void onError(String error) { if (!closed) host.onMessage("暂时无法继续接收", error); }
            });
        });});
    }

    private void chooseProjectPreset() {
        PhotoLiveProject project = findLiveProject(selectedLiveProjectId);
        if (project == null) { host.onFeedback("请先选择拍摄项目", "选择项目后，即可设置默认预设。"); return; }
        if (projectPresets == null) { host.onFeedback("默认预设尚未就绪", "请稍后重试。"); return; }
        chooseDefaultPreset(projectPresets.config(project.id).presetId, () -> screen == Screen.OVERVIEW && liveTab && project.id.equals(selectedLiveProjectId), (id, name) -> {
            if (!canShowProjectDialog() || !project.id.equals(selectedLiveProjectId)) return;
            projectPresets.setProjectPreset(project.id, id, new AlbumController.Callback<Void>() {
            @Override public void onSuccess(Void value) {
                if (!closed) { updatePhotoLiveView(); host.onFeedback(id.isEmpty() ? "已关闭默认预设" : "已设置默认预设", "仅影响后续新照片，原图始终保留。"); }
            }
            @Override public void onError(String error) { if (!closed) host.onMessage("默认预设未保存", error); }
            });
        });
    }

    private interface DefaultPresetChoice { void selected(String id, String name); }
    private void dismissDefaultPresetPicker() {
        presetSelectionRequest++;
        if (presetSelectionPage != null) { presetSelectionPage.close(); presetSelectionPage = null; }
    }

    private void chooseDefaultPreset(String selectedId, java.util.function.BooleanSupplier sourceValid, DefaultPresetChoice choice) {
        if (projectPresets == null) { host.onFeedback("默认预设尚未就绪", "请稍后重试。"); return; }
        showPresetSelection("默认预设", selectedId, true, sourceValid,
                preset -> choice.selected(preset == null ? "" : preset.id, preset == null ? "" : preset.displayName));
    }

    private void showPresetSelection(String title, String selectedId, boolean allowNone,
            java.util.function.BooleanSupplier sourceValid, java.util.function.Consumer<PresetRecord> selectedPreset) {
        if (!canShowProjectDialog() || presetSelectionPage != null || presetPicker != null) return;
        final int request = ++presetSelectionRequest;
        java.util.function.BooleanSupplier valid = () -> request == presetSelectionRequest && canShowProjectDialog()
                && (localWorkspace ? host.isLocalWorkspaceVisible() : root != null && root.isAttachedToWindow() && root.isShown()) && sourceValid.getAsBoolean();
        if (!valid.getAsBoolean()) return;
        PresetSelectionPage page = new PresetSelectionPage(activity, liveTab);
        presetSelectionPage = page;
        page.show(title, selectedId, allowNone, valid, new PresetSelectionPage.Callback() {
            @Override public void onSelected(PresetRecord preset) { if (valid.getAsBoolean()) selectedPreset.accept(preset); }
            @Override public void onDismissed() { if (presetSelectionPage == page) presetSelectionPage = null; }
        });
    }

    private static String projectNameError(String rawName) {
        String name = rawName.trim();
        if (name.replaceAll("[\\s\\u00a0\\u3000]+", "").isEmpty()) return "请输入项目名称";
        if (name.codePointCount(0, name.length()) > 60) return "项目名称最多 60 个字符";
        if (name.contains("..") || name.equals(".") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return "项目名称不能包含 /、\\ 或 ..";
        for (int offset = 0; offset < rawName.length();) {
            int codePoint = rawName.codePointAt(offset); offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || type == Character.FORMAT || type == Character.SURROGATE) return "项目名称不能包含控制字符";
        }
        return null;
    }

    private PhotoLiveProject findLiveProject(String id) {
        for (PhotoLiveProject project : liveProjects) if (project.id.equals(id)) return project;
        return null;
    }

    private boolean canShowProjectDialog() {
        return !closed && !activity.isFinishing() && !activity.isDestroyed();
    }

    private void showProjectInfo() {
        PhotoLiveProject project = findLiveProject(selectedLiveProjectId);
        if (!canShowProjectDialog() || projectInfoDialog != null) return;
        if (project == null) {
            host.onFeedback(loadingLiveProjects ? "正在读取项目" : "请先选择拍摄项目", loadingLiveProjects ? "项目读取完成后即可查看详细信息。"
                    : liveProjects.isEmpty() ? "开始照片直播时可新建拍摄项目，之后即可查看创建时间和照片数量。" : "请先在拍摄项目中选择一个项目，再查看详细信息。"); return;
        }
        ProjectInfoViews info = new ProjectInfoViews(project.id);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("项目详情").setView(info.scroll)
                .setNeutralButton("重命名", null).setPositiveButton("关闭", null).create();
        projectInfo = info; projectInfoDialog = dialog;
        dialog.setOnShowListener(ignored -> {
            Button rename = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            rename.setMinHeight(dp(48)); rename.setMinimumHeight(dp(48));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setMinHeight(dp(48));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setMinimumHeight(dp(48));
            rename.setOnClickListener(view -> showProjectRename(info.id));
            updateProjectInfo();
        });
        dialog.setOnDismissListener(ignored -> {
            if (projectInfoDialog == dialog) { projectInfoDialog = null; projectInfo = null; }
        });
        updateProjectInfo(); dialog.show();
    }

    private final class ProjectInfoViews {
        final String id;
        final ScrollView scroll;
        final TextView name, created, count, size, status, latest;
        final LinearLayout latestRow;
        ProjectInfoViews(String id) {
            this.id = id;
            scroll = new ScrollView(activity) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int maximum = Math.max(dp(144), activity.getResources().getDisplayMetrics().heightPixels / 2);
                    if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) maximum = Math.min(maximum, MeasureSpec.getSize(heightSpec));
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST));
                }
            };
            scroll.setClipToPadding(false);
            LinearLayout content = vertical(); content.setPadding(dp(24), dp(8), dp(24), dp(12));
            content.addView(text("项目名称", 12, palette.secondary, false));
            name = text("", 19, palette.text, true); name.setTextIsSelectable(true); addSpaced(content, name, 7);
            LinearLayout metadata = vertical(); metadata.setPadding(dp(14), dp(2), dp(14), dp(2));
            metadata.setBackground(shape(palette.container, 16));
            created = addInfoRow(metadata, "创建时间", true);
            count = addInfoRow(metadata, "照片数量", false);
            size = addInfoRow(metadata, "原图总大小", false);
            status = addInfoRow(metadata, "直播状态", false);
            latestRow = vertical(); latestRow.setPadding(0, dp(12), 0, dp(12));
            latestRow.addView(text("最近保存", 12, palette.secondary, false));
            latest = text("", 14, palette.text, false); latest.setTextIsSelectable(true); addSpaced(latestRow, latest, 5);
            metadata.addView(latestRow, new LinearLayout.LayoutParams(-1, -2));
            addSpaced(content, metadata, 16);
            addSpaced(content, text("项目与原图保存在本机，离线也可切换查看。", 12, palette.secondary, false), 12);
            scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        }
    }

    private TextView addInfoRow(LinearLayout parent, String label, boolean first) {
        if (!first) {
            View divider = new View(activity); divider.setBackgroundColor(palette.rim);
            parent.addView(divider, new LinearLayout.LayoutParams(-1, dp(0.5f)));
        }
        LinearLayout row = vertical(); row.setPadding(0, dp(12), 0, dp(12));
        row.addView(text(label, 12, palette.secondary, false));
        TextView value = text("", 15, palette.text, false); addSpaced(row, value, 5);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); return value;
    }

    private void updateProjectInfo() {
        if (closed || projectInfoDialog == null || projectInfo == null) return;
        PhotoLiveProject project = findLiveProject(projectInfo.id);
        if (project == null) return;
        PhotoLiveState state = controller.photoLiveState();
        boolean active = state.enabled && project.id.equals(state.projectId);
        String status = active ? state.starting ? "正在准备直播" : state.receiving ? "正在接收原图" : "等待拍摄" : "已保存在本机";
        if (active && state.queued > 0) status += " · 待接收 " + state.queued + " 张";
        if (active && !state.error.isEmpty()) status += "\n" + state.error;
        setProjectInfoText(projectInfo.name, project.name);
        setProjectInfoText(projectInfo.created, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(project.createdAt)));
        setProjectInfoText(projectInfo.count, project.photoCount + " 张");
        setProjectInfoText(projectInfo.size, formatProjectBytes(project.totalBytes));
        setProjectInfoText(projectInfo.status, status);
        projectInfo.latestRow.setVisibility(project.photos.isEmpty() ? View.GONE : View.VISIBLE);
        if (!project.photos.isEmpty()) setProjectInfoText(projectInfo.latest, project.photos.get(0).filename());
    }

    private static void setProjectInfoText(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private static String formatProjectBytes(long size) {
        if (size < 1024 * 1024 * 1024L) return formatBytes(size);
        return String.format(Locale.ROOT, "%.2f GB", size / (1024d * 1024d * 1024d));
    }

    private void showProjectRename(String id) {
        PhotoLiveProject project = findLiveProject(id);
        if (!canShowProjectDialog() || project == null || projectRenameDialog != null) return;
        LinearLayout form = vertical(); form.setPadding(dp(24), dp(10), dp(24), dp(8));
        TextView label = text("项目名称", 13, palette.secondary, false); form.addView(label);
        EditText name = input(project.name, "1–60 个字符", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setId(View.generateViewId()); name.setSelectAllOnFocus(true); label.setLabelFor(name.getId()); addSpaced(form, name, 8);
        TextView validationError = text("", 13, palette.error, false);
        validationError.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        validationError.setVisibility(View.GONE); addSpaced(form, validationError, 8);
        name.addTextChangedListener(watcher(value -> {
            validationError.setText(""); validationError.setVisibility(View.GONE);
        }));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("重命名项目").setView(form)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        projectRenameDialog = dialog;
        final boolean[] saving = {false};
        dialog.setOnShowListener(ignored -> {
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE), cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            save.setMinHeight(dp(48)); save.setMinimumHeight(dp(48)); cancel.setMinHeight(dp(48)); cancel.setMinimumHeight(dp(48));
            save.setOnClickListener(view -> {
                if (!canShowProjectDialog() || projectRenameDialog != dialog || saving[0]) return;
                String rawName = name.getText().toString(), error = projectNameError(rawName);
                validationError.setText(""); validationError.setVisibility(View.GONE);
                if (error != null) {
                    validationError.setText(error); validationError.setVisibility(View.VISIBLE); name.requestFocus(); return;
                }
                saving[0] = true; name.setEnabled(false);
                save.setEnabled(false); save.setText("保存中…"); cancel.setEnabled(false);
                dialog.setCancelable(false); dialog.setCanceledOnTouchOutside(false);
                controller.renamePhotoLiveProject(id, rawName.trim(), new AlbumController.Callback<PhotoLiveProject>() {
                    @Override public void onSuccess(PhotoLiveProject renamed) {
                        if (!canShowProjectDialog()) return;
                        // UUID and photo snapshots stay selected; only the persisted project metadata changes.
                        for (int i = 0; i < liveProjects.size(); i++) if (liveProjects.get(i).id.equals(renamed.id)) { liveProjects.set(i, renamed); break; }
                        liveProjectsRevision++;
                        updatePhotoLiveView(); updateProjectChooser(); updateProjectInfo(); refreshLiveProjects();
                        if (projectRenameDialog == dialog) dialog.dismiss();
                    }
                    @Override public void onError(String error) {
                        if (!canShowProjectDialog() || projectRenameDialog != dialog) return;
                        saving[0] = false; name.setEnabled(true); save.setEnabled(true); save.setText("保存"); cancel.setEnabled(true);
                        dialog.setCancelable(true); dialog.setCanceledOnTouchOutside(true);
                        validationError.setText(error); validationError.setVisibility(View.VISIBLE); name.requestFocus();
                    }
                });
            });
        });
        dialog.setOnDismissListener(ignored -> { if (projectRenameDialog == dialog) projectRenameDialog = null; });
        dialog.show();
    }

    private void followStartedProject() {
        PhotoLiveState state = controller.photoLiveState();
        if (selectNewLiveProject && !state.projectId.isEmpty() && !state.projectId.equals(previousLiveProjectId)) {
            selectNewLiveProject = false; selectLiveProject(state.projectId);
            refreshLiveProjects();
        }
    }

    private void selectLiveProject(String projectId) {
        String nextId = projectId == null ? "" : projectId;
        boolean selectionCleared = selectionMode && !selectedLiveProjectId.equals(nextId);
        if (!selectedLiveProjectId.equals(nextId)) {
            selectionMode = false; clearSelection(); liveGridSavedState = null;
        }
        selectedLiveProjectId = nextId;
        activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE).edit()
                .putString("photo-live-project", selectedLiveProjectId).apply();
        liveHistoryKey = ""; aliasRefreshKey = ""; hydrateStarAliases();
        updatePhotoLiveView();
        updateProjectChooser();
        if (liveGrid != null) liveGrid.setSelection(0);
        if (selectionCleared) changed();
    }

    private void chooseLiveProject() {
        if (closed || activity.isFinishing() || projectChooser != null) return;
        LinearLayout heading = vertical(); heading.setPadding(dp(24), dp(20), dp(24), dp(8));
        heading.addView(text("拍摄项目", 20, palette.text, true));
        addSpaced(heading, text("保存在本机，可随时切换查看", 13, palette.secondary, false), 6);
        LinearLayout content = vertical(); content.setPadding(dp(16), 0, dp(16), 0);
        projectChooserRegion = new FrameLayout(activity);
        projectChooserList = new ListView(activity); projectChooserList.setDivider(null);
        projectChooserList.setChoiceMode(ListView.CHOICE_MODE_SINGLE); projectChooserList.setClipToPadding(false);
        projectChooserList.setPadding(0, dp(4), 0, dp(4));
        projectChooserAdapter = new ProjectChoiceAdapter(); projectChooserList.setAdapter(projectChooserAdapter);
        projectChooserRegion.addView(projectChooserList, new FrameLayout.LayoutParams(-1, -1));
        projectChooserEmpty = text("", 14, palette.secondary, false); projectChooserEmpty.setGravity(Gravity.CENTER);
        projectChooserEmpty.setPadding(dp(16), dp(16), dp(16), dp(16));
        projectChooserRegion.addView(projectChooserEmpty, new FrameLayout.LayoutParams(-1, -1));
        projectChooserList.setEmptyView(projectChooserEmpty);
        content.addView(projectChooserRegion, new LinearLayout.LayoutParams(-1, dp(160)));
        projectChooserError = text("", 12, palette.error, false); projectChooserError.setPadding(dp(8), dp(10), dp(8), 0);
        content.addView(projectChooserError, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AlertDialog.Builder(activity).setCustomTitle(heading).setView(content)
                .setNeutralButton("刷新", null).setNegativeButton("关闭", null).setPositiveButton("新建项目", null).create();
        projectChooser = dialog; projectChooserKey = "";
        projectChooserList.setOnItemClickListener((parent, view, position, id) -> {
            if (projectChooserAdapter == null || position < 0 || position >= projectChooserAdapter.getCount()) return;
            String projectId = projectChooserAdapter.getItem(position).id;
            selectNewLiveProject = false;
            selectLiveProject(projectId); dialog.dismiss();
        });
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> refreshLiveProjects());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.dismiss();
                PhotoLiveState state = controller.photoLiveState();
                if (state.enabled || state.starting || state.receiving || !state.transferStage.isEmpty()) {
                    host.onFeedback("请先停止当前直播", "当前照片接收和保存完成后，即可新建另一个拍摄项目。"); return;
                }
                requestPhotoLive();
            });
            updateProjectChooser();
        });
        dialog.setOnDismissListener(ignored -> {
            if (projectChooser != dialog) return;
            projectChooser = null; projectChooserAdapter = null; projectChooserList = null;
            projectChooserRegion = null; projectChooserEmpty = projectChooserError = null; projectChooserKey = "";
        });
        dialog.show();
    }

    private void updateProjectChooser() {
        if (projectChooser == null || projectChooserAdapter == null) return;
        PhotoLiveState state = controller.photoLiveState();
        StringBuilder key = new StringBuilder(selectedLiveProjectId).append(':').append(state.enabled)
                .append(':').append(state.projectId).append(':').append(loadingLiveProjects).append(':').append(liveProjectsError);
        for (PhotoLiveProject project : liveProjects) key.append(';').append(project.id).append(':').append(project.name)
                .append(':').append(project.createdAt).append(':').append(project.photos.size());
        if (!key.toString().equals(projectChooserKey)) {
            projectChooserKey = key.toString();
            android.os.Parcelable scroll = projectChooserList.onSaveInstanceState();
            projectChooserAdapter.projects.clear(); projectChooserAdapter.projects.addAll(liveProjects);
            projectChooserAdapter.notifyDataSetChanged();
            if (scroll != null) projectChooserList.onRestoreInstanceState(scroll);
            for (int i = 0; i < liveProjects.size(); i++) if (liveProjects.get(i).id.equals(selectedLiveProjectId)) projectChooserList.setItemChecked(i, true);
            int maximum = Math.max(dp(160), activity.getResources().getDisplayMetrics().heightPixels / 2);
            projectChooserRegion.getLayoutParams().height = Math.min(maximum, Math.max(dp(144), liveProjects.size() * dp(80)));
            projectChooserRegion.requestLayout();
        }
        projectChooserEmpty.setText(loadingLiveProjects ? "正在读取本机项目…" : !liveProjectsError.isEmpty()
                ? "项目暂时无法读取\n请重试" : "还没有拍摄项目\n开始直播时新建，照片会按项目保存在本机。");
        projectChooserError.setText(liveProjectsError);
        projectChooserError.setVisibility(!liveProjectsError.isEmpty() && !liveProjects.isEmpty() ? View.VISIBLE : View.GONE);
        Button retry = projectChooser.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (retry != null) { retry.setText(!liveProjectsError.isEmpty() ? "重试读取" : "刷新"); retry.setEnabled(!loadingLiveProjects); }
    }

    private final class ProjectChoiceAdapter extends BaseAdapter {
        final List<PhotoLiveProject> projects = new ArrayList<>();
        @Override public int getCount() { return projects.size(); }
        @Override public PhotoLiveProject getItem(int position) { return projects.get(position); }
        @Override public long getItemId(int position) { return getItem(position).id.hashCode(); }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            ProjectChoiceRow row = recycled instanceof ProjectChoiceRow ? (ProjectChoiceRow) recycled : new ProjectChoiceRow();
            PhotoLiveProject project = getItem(position); PhotoLiveState state = controller.photoLiveState();
            boolean selected = project.id.equals(selectedLiveProjectId), active = state.enabled && project.id.equals(state.projectId);
            String date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(project.createdAt));
            row.name.setText(project.name); row.meta.setText(date + " · " + project.photos.size() + " 张");
            row.active.setVisibility(active ? View.VISIBLE : View.GONE);
            row.check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE); row.setSelected(selected);
            row.setContentDescription(project.name + "，" + date + "，" + project.photos.size() + " 张"
                    + (selected ? "，当前选中" : "") + (active ? "，接收中" : ""));
            return row;
        }
    }

    private final class ProjectChoiceRow extends LinearLayout {
        final TextView name = text("", 15, palette.text, false);
        final TextView meta = text("", 12, palette.secondary, false);
        final TextView active = text("接收中", 12, palette.success, false);
        final ImageView check = icon(AppIcon.Kind.CHECK, palette.primary);
        ProjectChoiceRow() {
            super(activity); setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(12), dp(12), dp(10), dp(12)); setMinimumHeight(dp(72));
            setBackground(interactive(Color.TRANSPARENT, 12));
            LinearLayout words = vertical(); name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); words.addView(name, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout detail = horizontal(); detail.setGravity(Gravity.CENTER_VERTICAL);
            meta.setMaxLines(2); detail.addView(meta, new LinearLayout.LayoutParams(0, -2, 1));
            active.setPadding(dp(8), 0, 0, 0); detail.addView(active, new LinearLayout.LayoutParams(-2, -2));
            addSpaced(words, detail, 5); addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams mark = new LinearLayout.LayoutParams(dp(20), dp(20)); mark.leftMargin = dp(12); addView(check, mark);
            for (View child : new View[]{name, meta, active}) child.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    private void refreshLiveProjects() {
        if (closed) return;
        if (loadingLiveProjects) { liveProjectsDirty = true; return; }
        loadingLiveProjects = true;
        updateProjectChooser();
        controller.listPhotoLiveProjects(new AlbumController.Callback<List<PhotoLiveProject>>() {
            @Override public void onSuccess(List<PhotoLiveProject> result) {
                if (closed) return;
                loadingLiveProjects = false; liveProjectsError = "";
                liveProjects.clear(); liveProjects.addAll(result);
                liveProjectsRevision++;
                PhotoLiveState state = controller.photoLiveState();
                if (selectedLiveProjectId.isEmpty()) {
                    if (!state.projectId.isEmpty()) selectLiveProject(state.projectId);
                    else if (!liveProjects.isEmpty()) selectLiveProject(liveProjects.get(0).id);
                }
                updatePhotoLiveView();
                updateProjectChooser();
                updateProjectInfo();
                hydrateStarAliases();
                if (liveProjectsDirty) { liveProjectsDirty = false; refreshLiveProjects(); }
            }
            @Override public void onError(String error) {
                if (closed) return;
                loadingLiveProjects = false; liveProjectsError = error; updatePhotoLiveView(); updateProjectChooser(); updateProjectInfo();
                if (liveProjectsDirty) { liveProjectsDirty = false; refreshLiveProjects(); }
            }
        });
    }

    private void photoLiveChanged() {
        if (closed) return;
        liveState = controller.photoLiveState();
        StringBuilder receiptKey = new StringBuilder(liveState.projectId).append(liveState.enabled).append(liveState.starting);
        for (PhotoLiveState.LivePhoto entry : liveState.photos) receiptKey.append(entry.identity).append(entry.status).append(entry.error).append(entry.galleryError);
        if (!receiptKey.toString().equals(receiptRefreshKey)) { receiptRefreshKey = receiptKey.toString(); receipts.refresh(); }
        followStartedProject();
        if (liveState.completed != liveSavedCount) { liveSavedCount = liveState.completed; refreshDownloads(); refreshLiveProjects(); }
        if (screen == Screen.DETAIL && liveDetail) {
            restoreLiveDetailOriginal();
            requestRemotePreview();
            updateViewerStatus(); updateDetailNavigation();
        } else if (screen == Screen.OVERVIEW && liveTab) updatePhotoLiveView();
        updateProjectChooser();
        updateProjectInfo();
    }

    private void restoreLiveDetailOriginal() {
        if (!liveDetail || screen != Screen.DETAIL) return;
        PhotoLiveState.LivePhoto entry = currentLiveDetail();
        DownloadedPhoto saved = entry == null ? null : entry.saved;
        if (saved == null && liveDetailIdentity.matches("[0-9a-f]{64}")) {
            String prefix = "live-" + liveDetailIdentity + "__";
            for (DownloadedPhoto downloaded : downloads) if (downloaded.file().getName().startsWith(prefix)) { saved = downloaded; break; }
        }
        if (saved != null && (localDetail == null || !samePhoto(localDetail, saved))) {
            localDetail = saved; detailImageKey = "";
            requestDetailFile(saved.file(), localKey(saved));
        }
    }

    private void updatePhotoLiveView() {
        if (liveHeading == null || !liveTab || screen != Screen.OVERVIEW) return;
        PhotoLiveState state = controller.photoLiveState(); liveState = state;
        String phase = state.transferStage;
        boolean transfer = !phase.isEmpty();
        boolean otherProject = (state.enabled || state.starting || transfer) && !state.projectId.isEmpty() && !state.projectId.equals(selectedLiveProjectId);
        boolean receiving = phase.equals("receiving");
        boolean known = receiving && state.totalBytes > 0;
        boolean retry = liveHasRetry(state);
        String heading, percent = "", summary;
        if (state.starting) { heading = "正在准备直播"; summary = "正在确认现有照片，只接收之后新拍的 JPEG"; }
        else if (phase.equals("saving")) { heading = "接收完成，正在保存"; percent = "保存中"; summary = state.currentFilename + " · " + formatBytes(state.receivedBytes); }
        else if (phase.equals("preview")) { heading = "正在读取预览"; summary = state.currentFilename; }
        else if (receiving) {
            heading = "正在接收原图";
            percent = known ? liveTransferPercent(state) + "%" : "接收中";
            summary = state.currentFilename + " · " + formatBytes(state.receivedBytes)
                    + (known ? " / " + formatBytes(state.totalBytes) : "");
        } else if (!isConnected()) { heading = state.enabled ? "等待重新连接相机" : "连接相机，开始直播"; summary = "请先连接相机 Wi-Fi，已保存照片可随时查看"; }
        else if (retry && state.enabled) {
            heading = "有照片待重试"; summary = "轻点重试，详情可查看接收记录";
            for (PhotoLiveState.LivePhoto item : state.photos) if (!item.galleryError.isEmpty()) {
                heading = item.saveTarget==PhotoSaveTarget.APP_ONLY?"项目记录待重试":"相册保存待重试"; summary = item.saveTarget==PhotoSaveTarget.APP_ONLY?"原图已保留在 App 内，可重试项目记录":"原图已保留，重试后保存到系统相册"; break;
            }
        } else if (state.enabled) { heading = "等待拍摄"; summary = controller.photoLiveSaveTarget()==PhotoSaveTarget.APP_ONLY?"新照片仅保存在 App 内，可选片后再导出":"新照片将保存到手机相册"; }
        else { heading = selectedLiveProjectId.isEmpty() ? "拍完，照片就到手机" : "直播已停止"; summary = selectedLiveProjectId.isEmpty() ? "开始直播，创建本次拍摄项目" : "继续接收新照片，或切换拍摄项目"; }
        if (otherProject) {
            heading = "其他项目 · " + heading; summary = state.projectName + " · " + summary;
        }
        liveHeading.setText(heading); livePercent.setText(percent); liveSummary.setText(summary);
        liveAction.setText(state.starting ? "停止" : !isConnected() ? "去连接" : state.enabled ? retry && !transfer ? "重试" : "停止" : selectedLiveProjectId.isEmpty() ? "开始" : "继续");
        liveAction.setEnabled(true);
        liveProgress.setVisibility(state.starting || transfer ? View.VISIBLE : View.INVISIBLE);
        boolean indeterminate = !known;
        if (liveProgress.isIndeterminate() != indeterminate) liveProgress.setIndeterminate(indeterminate);
        if (known) liveProgress.setProgress((int) Math.min(1000, Math.max(0, state.receivedBytes / (double) state.totalBytes * 1000)));
        liveTransferPanel.setContentDescription(heading + (percent.isEmpty() ? "" : "，" + percent) + "，" + summary);
        PhotoLiveProject selected = findLiveProject(selectedLiveProjectId);
        String projectName = selected != null ? selected.name : selectedLiveProjectId.equals(state.projectId) ? state.projectName : "";
        liveProjectChoice.setText(projectName.isEmpty() ? loadingLiveProjects ? "读取项目…" : "选择项目" : projectName);
        if (projectPresetAction != null) {
            PhotoProjectPresetStore.Config config = projectPresets == null || selectedLiveProjectId.isEmpty() ? null : projectPresets.config(selectedLiveProjectId);
            projectPresetAction.setText("默认 · " + (config == null || config.presetId.isEmpty() ? "不使用" : config.presetName));
            projectPresetAction.setContentDescription(projectPresetAction.getText() + "，点击更改，仅影响后续新照片");
        }
        liveProjectChoice.setContentDescription("切换拍摄项目，" + (projectName.isEmpty() ? "尚未选择" : projectName));
        liveProjectChoice.setEnabled(true);
        liveProjectInfo.setEnabled(true); liveProjectInfo.setAlpha(1f);
        liveProjectInfo.setContentDescription("项目详情" + (selected != null ? "，" + selected.name : "，尚未选择项目"));
        rebuildLiveGrid(state, selected);
        liveSelect.setText(selectionMode ? "取消" : "选择");
        boolean hasSaved = false;
        for (LiveGridPhoto photo : liveGridPhotos) if (photo.saved != null) { hasSaved = true; break; }
        liveSelect.setVisibility(selectionMode || hasSaved ? View.VISIBLE : View.INVISIBLE);
        liveSelect.setEnabled(true);
        livePhotoCount.setText((onlyStarredLive ? "星标照片" : "项目照片") + " · " + liveGridPhotos.size() + " 张");
        String stats = "已保存 " + liveProjectSavedCount + " 张 · 待接收 " + liveProjectPendingCount + " 张";
        if (otherProject) stats = "正在接收到「" + state.projectName + "」";
        PhotoPresetBatchState batch = photoPresets == null ? null : photoPresets.state();
        if (batch != null && batch.total > 0) stats += batch.active ? " · 套用 " + batch.completed + "/" + batch.total : " · 预设处理已结束 ›";
        liveStats.setText(stats); liveStats.setClickable(batch != null && batch.total > 0);
        liveStats.setContentDescription(stats + (batch != null && batch.total > 0 ? "，点击查看预设处理详情" : ""));
        updateLivePresetProgress(batch);
        updatePhotoFilter(liveFilter, true); updateLiveSelection();
        forEachLiveTile(tile -> updateLiveBadge(tile, state));
    }

    private void updateLivePresetProgress(PhotoPresetBatchState batch) {
        if (livePresetProgress == null || livePresetDetail == null) return;
        PhotoProjectPresets.Processing automatic = projectPresets == null ? null : projectPresets.processing();
        double fraction = -1; String label;
        if (automatic != null) {
            fraction = automatic.progress;
            label = (automatic.projectId.equals(selectedLiveProjectId) ? "自动套用" : "其他项目套用")
                    + " · " + progressPercent(fraction) + "% · " + automatic.stage;
        } else if (batch != null && batch.active) {
            fraction = batch.progress;
            label = "套用预设 · " + progressPercent(fraction) + "% · " + batch.currentNumber + "/" + batch.total + " 张";
        } else {
            PhotoProjectPresetStore.Config config = projectPresets == null || selectedLiveProjectId.isEmpty()
                    ? null : projectPresets.config(selectedLiveProjectId);
            label = config == null || config.presetId.isEmpty() ? "未启用自动套用" : projectPresets.failedCount() > 0
                    ? "有预设效果待重试" : projectPresets.queuedCount() > 0 ? "预设排队中 · " + projectPresets.queuedCount() + " 张"
                    : "自动套用已就绪 · 保留原图";
        }
        livePresetDetail.setText(label); livePresetDetail.setTextColor(fraction >= 0 ? palette.primary : palette.secondary);
        livePresetDetail.setContentDescription(label + "，点击查看套用详情");
        livePresetProgress.setVisibility(fraction >= 0 ? View.VISIBLE : View.INVISIBLE);
        if (fraction >= 0) livePresetProgress.setProgress((int)Math.floor(fraction * 1000));
    }

    private static int progressPercent(double fraction) { return (int)Math.floor(Math.max(0, Math.min(1, fraction)) * 100); }

    private int liveTransferPercent(PhotoLiveState state) {
        return state.totalBytes <= 0 ? 0 : (int) Math.min(100, Math.max(0, Math.floor(state.receivedBytes / (double) state.totalBytes * 100)));
    }

    private void showLivePresetStatus() {
        if (photoPresets == null || photoPresets.state().total == 0) return;
        PhotoPresetBatchState state = photoPresets.state();
        String detail = state.presetName + "\n完成 " + state.completed + " / " + state.total + " 张 · 失败 " + state.failed
                + (state.active ? "\n总进度 " + progressPercent(state.progress) + "% · " + state.currentStage + " " + progressPercent(state.currentProgress) + "%\n" + state.currentFilename : state.failed == 0 && state.cancelled == 0 && state.completed > 0 && state.errors.isEmpty()
                        ? "\n效果图已保留在 App 内；手机相册保存遵循该照片接收时的选择。" : "\n处理已结束，未完成项见下方；原图仍保留。")
                + (state.reused > 0 ? "\n已复用 " + state.reused + " 张现有效果。" : "");
        for (int i=0; i<Math.min(5, state.errors.size()); i++) detail += "\n" + state.errors.get(i);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(state.active ? "正在套用预设" : "预设处理详情").setMessage(detail).setNegativeButton("关闭", null);
        if (state.active) builder.setPositiveButton("停止处理", (dialog,which) -> { presetCancelRequested = true; photoPresets.cancel(); });
        else if (!state.results.isEmpty()) builder.setPositiveButton("查看效果", (dialog,which) -> {
            DownloadedPhoto effect = state.results.get(0);
            if (!effect.file().isFile()) { host.onFeedback("效果图暂不可用", "照片文件已移动或删除，请重新套用预设。"); return; }
            downloadedTab = true; liveTab = false; selectionMode = false; clearSelection(); openLocal(effect);
        });
        builder.show();
    }

    private static final class LiveGridPhoto {
        final String key;
        final DownloadedPhoto saved;
        final PhotoLiveState.LivePhoto live;
        LiveGridPhoto(String key, DownloadedPhoto saved, PhotoLiveState.LivePhoto live) {
            this.key = key; this.saved = saved; this.live = live;
        }
        String filename() { return saved != null ? saved.filename() : live.photo.filename(); }
    }

    private void rebuildLiveGrid(PhotoLiveState state, PhotoLiveProject project) {
        if (liveGrid == null || liveAdapter == null) return;
        boolean activeProject = !selectedLiveProjectId.isEmpty() && selectedLiveProjectId.equals(state.projectId);
        StringBuilder source = new StringBuilder(selectedLiveProjectId).append(':').append(liveProjectsRevision).append(':').append(libraryRevision)
                .append(':').append(liveProjectsError).append(':').append(loadingLiveProjects).append(':').append(onlyStarredLive).append(':').append(starRevision);
        if (activeProject) for (PhotoLiveState.LivePhoto entry : state.photos) source.append(';').append(entry.identity)
                .append(':').append(entry.status).append(':').append(entry.saved != null).append(':').append(entry.error).append(':').append(entry.galleryError);
        // Transfer byte counters update often; only structural/status changes rebuild grid data.
        if (source.toString().equals(liveGridSourceKey)) return;
        liveGridSourceKey = source.toString();
        List<LiveGridPhoto> next = new ArrayList<>();
        Map<String, PhotoLiveState.LivePhoto> currentByFile = new HashMap<>();
        Set<String> included = new HashSet<>();
        if (activeProject) for (PhotoLiveState.LivePhoto entry : state.photos) {
            if (entry.saved != null) { currentByFile.put(entry.saved.file().getAbsolutePath(), entry); bindStar(entry.identity, entry.saved); }
            else next.add(new LiveGridPhoto("live:" + entry.identity, null, entry));
        }
        List<DownloadedPhoto> completed = new ArrayList<>();
        if (project != null) for (DownloadedPhoto saved : project.photos) {
            if (included.add(saved.file().getAbsolutePath())) completed.add(saved);
        }
        List<DownloadedPhoto> newlyCompleted = new ArrayList<>();
        if (activeProject) for (PhotoLiveState.LivePhoto entry : state.photos) {
            if (entry.saved != null && included.add(entry.saved.file().getAbsolutePath())) newlyCompleted.add(entry.saved);
        }
        // Project snapshots are already newest-first; merge just-finished originals without per-file stat/sort work.
        completed.addAll(0, newlyCompleted);
        Set<String> displayedGroups = new HashSet<>(); List<DownloadedPhoto> groupedCompleted = new ArrayList<>();
        for (DownloadedPhoto saved : completed) {
            PhotoLibraryCatalog.PhotoGroup group = photoGroup(saved);
            if (!displayedGroups.add(group == null ? saved.file().getAbsolutePath() : group.id)) continue;
            groupedCompleted.add(saved);
            DownloadedPhoto display = displayedVersion(saved);
            next.add(new LiveGridPhoto(localKey(display), display, currentByFile.get(saved.file().getAbsolutePath())));
        }
        completed = groupedCompleted;
        liveProjectSavedCount = completed.size(); liveProjectPendingCount = next.size() - completed.size();
        // The main photo follows reception, independent of filtering or selection in the grid.
        LiveGridPhoto hero = next.isEmpty() ? null : next.get(0);
        if (activeProject && !state.currentIdentity.isEmpty()) for (LiveGridPhoto candidate : next) {
            if (candidate.live != null && state.currentIdentity.equals(candidate.live.identity)) { hero = candidate; break; }
        }
        updateLiveHero(hero);
        if (onlyStarredLive) next.removeIf(photo -> photo.saved != null ? !starred(photo.saved)
                : photo.live == null || !starredKeys.contains(PhotoStarStore.remoteKey(photo.live.identity)));
        StringBuilder key = new StringBuilder(selectedLiveProjectId);
        for (LiveGridPhoto photo : next) key.append(';').append(photo.key).append(':').append(photo.live == null ? "saved" : photo.live.status)
                .append(':').append(photo.live == null ? "" : photo.live.error + photo.live.galleryError);
        if (!key.toString().equals(liveHistoryKey)) {
            liveHistoryKey = key.toString();
            int first = liveGrid.getFirstVisiblePosition();
            View firstView = liveGrid.getChildCount() == 0 ? null : liveGrid.getChildAt(0);
            int offset = firstView == null ? 0 : firstView.getTop() - liveGrid.getPaddingTop();
            int firstPhoto = (first - liveGrid.getHeaderViewsCount()) * 3;
            String anchor = firstPhoto >= 0 && firstPhoto < liveGridPhotos.size() ? liveGridPhotos.get(firstPhoto).key : "";
            android.os.Parcelable position = liveGrid.onSaveInstanceState();
            liveGridPhotos.clear(); liveGridPhotos.addAll(next);
            liveAdapter.notifyDataSetChanged();
            int anchorIndex = -1;
            for (int i=0; i<liveGridPhotos.size() && !anchor.isEmpty(); i++) if (anchor.equals(liveGridPhotos.get(i).key)) { anchorIndex = i; break; }
            if (anchorIndex >= 0) liveGrid.setSelectionFromTop(liveGrid.getHeaderViewsCount() + anchorIndex / 3, offset);
            else if (position != null) liveGrid.onRestoreInstanceState(position);
        }
        liveGridEmpty.setText(!liveProjectsError.isEmpty() ? "项目暂时无法读取\n轻点重试" : loadingLiveProjects && liveProjects.isEmpty() ? "正在读取拍摄项目…"
                : selectedLiveProjectId.isEmpty() ? "开始直播，创建第一个拍摄项目" : onlyStarredLive ? starsReady ? "还没有星标照片\n打开照片，轻点星标即可收藏" : "正在读取星标…" : activeProject && state.enabled ? "新照片会显示在这里" : "这个项目还没有照片");
        liveGridEmpty.setOnClickListener(!liveProjectsError.isEmpty() ? view -> refreshLiveProjects() : null);
        liveGridEmpty.setVisibility(next.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private final class LivePhotoAdapter extends BaseAdapter {
        @Override public void notifyDataSetChanged() {
            super.notifyDataSetChanged(); if (liveRowsAdapter != null) liveRowsAdapter.notifyDataSetChanged();
        }
        @Override public int getCount() { return liveGridPhotos.size(); }
        @Override public LiveGridPhoto getItem(int position) { return liveGridPhotos.get(position); }
        @Override public long getItemId(int position) { return getItem(position).key.hashCode(); }
        @Override public boolean hasStableIds() { return true; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LiveThumbTile tile = recycled instanceof LiveThumbTile ? (LiveThumbTile) recycled : new LiveThumbTile();
            LiveGridPhoto photo = getItem(position);
            String visualKey = photo.live == null ? photo.key : "live:" + photo.live.identity;
            if (!visualKey.equals(tile.visualKey)) tile.image.setImageDrawable(null);
            tile.key = photo.key; tile.visualKey = visualKey; tile.photo = photo;
            String status = photo.live == null ? "原图已保存" : livePhotoStatus(photo.live);
            boolean favorite = photo.saved != null ? starred(photo.saved) : photo.live != null && starredKeys.contains(PhotoStarStore.remoteKey(photo.live.identity));
            boolean checked = photo.saved != null && selectedLocal.contains(photo.saved.file().getAbsolutePath());
            tile.setContentDescription(photo.filename() + "，" + status + (favorite ? "，已标星" : "") + (checked ? "，已选择" : selectionMode && photo.saved == null ? "，接收完成后可选择" : ""));
            tile.favorite.setVisibility(favorite ? View.VISIBLE : View.GONE);
            tile.check.setChecked(checked); tile.check.setVisibility(selectionMode && photo.saved != null ? View.VISIBLE : View.GONE);
            tile.setSelected(checked); tile.setAlpha(selectionMode && photo.saved == null ? 0.65f : 1f);
            Bitmap cached = thumbs.get(photo.key);
            if (cached == null && photo.live != null) cached = thumbs.get("live:" + photo.live.identity);
            tile.placeholder.setText(failedThumbs.contains(photo.key) ? "预览暂不可用" : photo.saved != null ? "正在读取…"
                    : photo.live.status.equals("queued") ? "等待接收" : "正在接收");
            tile.placeholder.setVisibility(cached == null && tile.image.getDrawable() == null ? View.VISIBLE : View.GONE);
            updateLiveBadge(tile, liveState);
            if (cached != null) tile.image.setImageBitmap(cached);
            if (photo.saved != null && thumbs.get(photo.key) == null) requestLocalThumb(photo.saved);
            else if (photo.saved == null && cached == null) requestCachedLiveThumb(photo.live, photo.key);
            return tile;
        }
    }

    private final class LiveRowsAdapter extends BaseAdapter {
        @Override public int getCount() { return (liveGridPhotos.size() + 2) / 3; }
        @Override public Object getItem(int position) { return liveGridPhotos.get(position * 3); }
        @Override public long getItemId(int position) { return liveGridPhotos.get(position * 3).key.hashCode(); }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return false; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LivePhotoRow row = recycled instanceof LivePhotoRow ? (LivePhotoRow)recycled : new LivePhotoRow();
            for (int column=0; column<3; column++) {
                int index = position * 3 + column;
                LiveThumbTile tile = (LiveThumbTile)row.getChildAt(column);
                if (index >= liveGridPhotos.size()) { tile.photo = null; tile.setVisibility(View.INVISIBLE); continue; }
                liveAdapter.getView(index, tile, row); tile.setVisibility(View.VISIBLE);
            }
            return row;
        }
    }

    private final class LivePhotoRow extends LinearLayout {
        LivePhotoRow() {
            super(activity); setOrientation(HORIZONTAL); setPadding(0, 0, 0, dp(7));
            for (int i=0; i<3; i++) {
                LiveThumbTile tile = new LiveThumbTile();
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                if (i > 0) params.leftMargin = dp(7); addView(tile, params);
                tile.setOnClickListener(view -> openLiveTile(tile.photo));
                tile.setOnLongClickListener(view -> {
                    LiveGridPhoto photo = tile.photo;
                    if (photo == null || downloading || exporting) return false;
                    if (photo.saved == null) { host.onFeedback("照片正在接收", "原图保存完成后，才能选择并套用预设。"); return true; }
                    selectionMode = true; selectedLocal.add(photo.saved.file().getAbsolutePath()); updateLiveSelection(); return true;
                });
            }
        }
    }

    private void openLiveTile(LiveGridPhoto photo) {
        if (photo == null) return;
        if (selectionMode) {
            if (photo.saved != null) { toggleLocalSelection(photo.saved); updateLiveSelection(); }
            else host.onFeedback("照片正在接收", "原图保存完成后，才能选择并套用预设。");
        } else if (photo.saved != null) openProjectPhoto(photo.saved);
        else if (photo.live != null) openLivePhoto(photo.live);
    }

    private final class LiveThumbTile extends FrameLayout {
        final ImageView image = new ImageView(activity);
        final TextView placeholder = text("", 12, palette.secondary, false);
        final TextView badge = text("", 11, Color.WHITE, false);
        final SelectionMark check = new SelectionMark();
        final ImageView favorite = favoriteBadge();
        String key, visualKey;
        LiveGridPhoto photo;
        LiveThumbTile() {
            super(activity); setBackground(shape(palette.container, 14)); setClipToOutline(true);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(image, new FrameLayout.LayoutParams(-1, -1)); placeholder.setGravity(Gravity.CENTER);
            addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            badge.setPadding(dp(7), dp(4), dp(7), dp(4)); badge.setBackground(shape(0xB8202328, 9));
            FrameLayout.LayoutParams caption = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            caption.rightMargin = caption.bottomMargin = dp(6); addView(badge, caption);
            check.setClickable(false); check.setFocusable(false); check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(check, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.END));
            FrameLayout.LayoutParams star = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP | Gravity.START);
            star.leftMargin = star.topMargin = dp(6); addView(favorite, star);
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec); super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
        }
    }

    private void updateLiveBadge(LiveThumbTile tile, PhotoLiveState state) {
        LiveGridPhoto photo = tile.photo; if (photo == null) return;
        String badge = "";
        if (photo.live != null) {
            if (!photo.live.error.isEmpty() || !photo.live.galleryError.isEmpty()) badge = "待重试";
            else if (state != null && state.currentIdentity.equals(photo.live.identity) && !state.transferStage.isEmpty()) {
                badge = state.transferStage.equals("saving") ? "保存中" : state.transferStage.equals("preview") ? "预览中"
                        : state.totalBytes > 0 ? liveTransferPercent(state) + "%" : "接收中";
            } else if (photo.saved == null) badge = photo.live.status.equals("queued") ? "排队中" : "预览";
            double applying = projectPresets == null ? -1 : projectPresets.progressFor(selectedLiveProjectId, photo.live.identity);
            if (applying >= 0) badge = "套用 " + progressPercent(applying) + "%";
        }
        if(badge.isEmpty()&&photo.saved!=null)badge=photoDraftBadge(photo.saved);
        tile.badge.setText(badge); tile.badge.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void requestCachedLiveThumb(PhotoLiveState.LivePhoto entry, String key) {
        if (closed || pendingThumbs.size() >= MAX_THUMB_REQUESTS || pendingThumbs.contains(key) || failedThumbs.contains(key)) return;
        if (!entry.status.equals("preview") && !entry.status.equals("receiving")) return;
        pendingThumbs.add(key); final int request = epoch;
        controller.loadCachedPreview(entry.photo, new AlbumController.Callback<PhotoPreview>() {
            @Override public void onSuccess(PhotoPreview preview) {
                if (!current(request)) { if (preview != null) preview.close(); return; }
                if (preview == null) { pendingThumbs.remove(key); return; }
                decodeThumb(key, request, () -> {
                    try { return decodeFile(preview.file(), 320, 320, true); }
                    finally { preview.close(); }
                }, null);
            }
            @Override public void onError(String error) { if (current(request)) { pendingThumbs.remove(key); failedThumbs.add(key); } }
        });
    }

    private void updateLiveHero(LiveGridPhoto photo) {
        if (liveHero == null) return;
        if (!selectedLiveProjectId.equals(liveHeroProjectId)) {
            releaseLiveHero(); liveHeroProjectId = selectedLiveProjectId;
        }
        if (photo == null) {
            releaseLiveHero(); liveHeroPlaceholder.setText("新照片会显示在这里"); liveHeroPlaceholder.setVisibility(View.VISIBLE); return;
        }
        String identity = photo.saved != null ? photo.saved.file().getAbsolutePath() : photo.live.identity;
        String key = selectedLiveProjectId + ":" + identity + ":" + (photo.saved == null ? "preview" : "original");
        if (key.equals(liveHeroDisplayedKey)) {
            if (!key.equals(liveHeroKey)) {
                liveHeroRequest++; liveHeroKey = key; liveHeroLoading = false;
                if (liveHeroPreview != null) { liveHeroPreview.close(); liveHeroPreview = null; }
            }
            // Refresh click metadata only for the photograph whose pixels are actually displayed.
            liveHeroPhoto = photo;
            liveHero.setImageBitmap(liveHeroBitmap); liveHeroPlaceholder.setVisibility(liveHeroBitmap == null ? View.VISIBLE : View.GONE); return;
        }
        if (key.equals(liveHeroKey)) {
            // A pending or failed source is not retried by every progress/status callback.
            liveHero.setImageBitmap(liveHeroBitmap);
            liveHeroPlaceholder.setVisibility(liveHeroBitmap == null ? View.VISIBLE : View.GONE); return;
        }
        if (photo.saved == null && !photo.live.status.equals("preview") && !photo.live.status.equals("receiving")) {
            if (liveHeroBitmap == null) {
                liveHeroPlaceholder.setText("新照片预览准备中…"); liveHeroPlaceholder.setVisibility(View.VISIBLE);
            }
            return;
        }
        final int request = ++liveHeroRequest;
        liveHeroKey = key; liveHeroLoading = true;
        PhotoPreview previous = liveHeroPreview; liveHeroPreview = null;
        if (previous != null) previous.close();
        // Keep the previous image and its click target while a new source is decoded.
        liveHeroPlaceholder.setText("正在读取预览…");
        liveHeroPlaceholder.setVisibility(liveHeroBitmap == null ? View.VISIBLE : View.GONE);
        if (photo.saved != null) decodeLiveHero(photo.saved.file(), photo, key, request);
        else controller.loadCachedPreview(photo.live.photo, new AlbumController.Callback<PhotoPreview>() {
            @Override public void onSuccess(PhotoPreview preview) {
                if (closed || request != liveHeroRequest || !key.equals(liveHeroKey)) { if (preview != null) preview.close(); return; }
                if (preview == null) { liveHeroUnavailable(key, request, "原图正在接收…"); return; }
                liveHeroPreview = preview; decodeLiveHero(preview.file(), photo, key, request, preview);
            }
            @Override public void onError(String error) {
                liveHeroUnavailable(key, request, "预览暂不可用");
            }
        });
    }

    private void decodeLiveHero(File file, LiveGridPhoto photo, String key, int request) {
        decodeLiveHero(file, photo, key, request, null);
    }

    private void decodeLiveHero(File file, LiveGridPhoto photo, String key, int request, PhotoPreview ownedPreview) {
        try {
            images.execute(() -> {
                Bitmap bitmap = null;
                try { bitmap = decodeFile(file, 1080, 720); }
                catch (RuntimeException | OutOfMemoryError ignored) { }
                finally { if (ownedPreview != null) ownedPreview.close(); }
                final Bitmap ready = bitmap;
                boolean posted = main.post(() -> {
                    if (liveHeroPreview == ownedPreview) liveHeroPreview = null;
                    if (closed || request != liveHeroRequest || !key.equals(liveHeroKey)) { if (ready != null) ready.recycle(); return; }
                    liveHeroLoading = false;
                    if (ready == null) { liveHeroUnavailable(key, request, "照片暂不可用"); return; }
                    Bitmap old = liveHeroBitmap;
                    liveHeroBitmap = ready; liveHeroPhoto = photo; liveHeroDisplayedKey = key;
                    if (liveHero != null) liveHero.setImageBitmap(ready);
                    if (liveHeroPlaceholder != null) liveHeroPlaceholder.setVisibility(View.GONE);
                    if (old != null && old != ready && !old.isRecycled()) old.recycle();
                });
                if (!posted && ready != null) ready.recycle();
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            if (ownedPreview != null) ownedPreview.close();
            if (liveHeroPreview == ownedPreview) liveHeroPreview = null;
            liveHeroUnavailable(key, request, "照片暂不可用");
        }
    }

    private void liveHeroUnavailable(String key, int request, String message) {
        if (closed || request != liveHeroRequest || !key.equals(liveHeroKey)) return;
        liveHeroLoading = false;
        if (liveHeroPlaceholder != null) {
            liveHeroPlaceholder.setText(message);
            liveHeroPlaceholder.setVisibility(liveHeroBitmap == null ? View.VISIBLE : View.GONE);
        }
    }

    private void releaseLiveHero() {
        liveHeroRequest++; liveHeroKey = ""; liveHeroDisplayedKey = ""; liveHeroProjectId = ""; liveHeroLoading = false;
        liveHeroPhoto = null; Bitmap old = liveHeroBitmap; liveHeroBitmap = null;
        if (liveHero != null) liveHero.setImageDrawable(null);
        if (old != null && !old.isRecycled()) old.recycle();
        if (liveHeroPreview != null) { liveHeroPreview.close(); liveHeroPreview = null; }
    }

    private String livePhotoStatus(PhotoLiveState.LivePhoto entry) {
        if (entry.saved != null) return !entry.galleryError.isEmpty() ? entry.saveTarget==PhotoSaveTarget.APP_ONLY?"原图已保留，项目记录待重试":"原图已保留，系统相册待重试"
                : !entry.galleryUri.isEmpty() ? "已存入系统相册" : "已保存在 App 内";
        if (!entry.error.isEmpty()) return "接收待重试";
        switch (entry.status) {
            case "preview": return "预览已就绪";
            case "receiving": return "正在接收原图";
            case "stopped": return "已暂停";
            case "error": return "接收待重试";
            default: return "等待接收";
        }
    }

    private boolean showUnsupportedPhotoLive() {
        if (!controller.hasLiveSession() || controller.supportsPhotoLive()) return false;
        host.onMessage("X-T20 暂不支持照片直播", "当前富士适配仅支持浏览和下载 JPEG。X-T20 不提供官方联机拍摄，尚未验证按机身快门持续自动传图；可以先拍摄，再进入无线通信下载照片。已保存的直播项目仍可查看。");
        return true;
    }

    private String suggestedCameraHost() {
        if (cameraProtocol == CameraProtocol.FUJIFILM_X_T20) return "";
        return android.os.Build.VERSION.SDK_INT >= 29 ? cameraProtocol.defaultHost : controller.suggestHost();
    }

    private boolean usesAutomaticCameraAddress() {
        return cameraProtocol == CameraProtocol.FUJIFILM_X_T20 && fujiAutoAddress && android.os.Build.VERSION.SDK_INT >= 29;
    }

    private void showGuide() {
        if (!addressManuallyEdited && cameraAddress.isEmpty()) {
            cameraAddress = suggestedCameraHost();
            if (!cameraAddress.isEmpty()) CameraConnectionPreferences.saveDraft(activity, cameraAddress, portText);
        }
        if (screen != Screen.GUIDE) guideScrollY = 0;
        screen = Screen.GUIDE;
        connectionError = "";
        changed();
    }

    private void renderGuide() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = vertical();
        body.setPadding(dp(24), dp(2), dp(24), dp(28));
        boolean fuji = cameraProtocol == CameraProtocol.FUJIFILM_X_T20;
        TextView introduction = text(fuji ? "连接富士相机的 Wi-Fi，浏览并下载 JPEG 照片。" : "连接相机的 Wi-Fi，浏览照片与导入预设。", 14, palette.secondary, false);
        body.addView(introduction);
        addSpaced(body, text("相机类型", 12, palette.secondary, false), 20);
        Button protocol = button(cameraProtocol.label + "  ›", false, () -> {
            CameraProtocol[] choices = CameraProtocol.values();
            String[] labels = new String[choices.length];
            for (int i = 0; i < choices.length; i++) labels[i] = choices[i].label;
            new AlertDialog.Builder(activity).setTitle("选择相机类型")
                    .setSingleChoiceItems(labels, cameraProtocol.ordinal(), (dialog, which) -> {
                        dialog.dismiss();
                        if (choices[which] == cameraProtocol) return;
                        CameraConnectionPreferences.saveDraft(activity, cameraAddress, portText, cameraProtocol);
                        cameraProtocol = choices[which];
                        CameraConnectionPreferences.selectProtocol(activity, cameraProtocol);
                        cameraAddress = CameraConnectionPreferences.loadHost(activity);
                        portText = CameraConnectionPreferences.loadPortText(activity);
                        addressManuallyEdited = !cameraAddress.isEmpty();
                        if (cameraAddress.isEmpty()) cameraAddress = suggestedCameraHost();
                        connectionError = ""; changed();
                    }).setNegativeButton("取消", null).show();
        });
        protocol.setEnabled(!connecting);
        protocol.setContentDescription("相机类型：" + cameraProtocol.label);
        addSpaced(body, protocol, 8);
        addSpaced(body, text("连接准备", 12, palette.secondary, false), 24);
        LinearLayout preparation = vertical();
        preparation.setPadding(dp(18), dp(2), dp(18), dp(2));
        preparation.setBackground(shape(palette.card, 24));
        step(preparation, "1", "开启相机 Wi-Fi", fuji ? "X-T20 菜单：拍摄设置或回放菜单 → 无线通信。" : "相机菜单：连接至智能设备 → Wi-Fi 连接 → 建立 Wi-Fi 连接");
        step(preparation, "2", "手机加入相机网络",
                "手机已连接相机 Wi-Fi 时，点击下方连接相机即可继续。尚未连接时先打开系统 Wi-Fi 设置选择相机热点；手动填写名称与密码仅作为备用方式。提示无法上网时仍保持连接。");
        step(preparation, "3", fuji ? "在相机上确认连接" : "保持相机 Wi-Fi 开启", fuji ? "关闭 Camera Remote 等相机应用，在 X-T20 上按 OK 确认；保持无线通信页面开启。" : "关闭其他相机应用，确认下方地址；连接后可切后台继续接收。");
        addSpaced(body, preparation, 8);
        Button systemWifi = textAction(android.os.Build.VERSION.SDK_INT >= 29 ? "查看系统 Wi-Fi 设置" : "打开系统 Wi-Fi 设置", () -> {
            try { activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
            catch (RuntimeException error) { host.onFeedback("无法打开设置", "请从手机系统设置中连接相机 Wi-Fi。"); }
        });
        systemWifi.setEnabled(!connecting);
        addSpaced(body, systemWifi, 6);
        addSpaced(body, text("相机地址", 12, palette.secondary, false), 20);
        LinearLayout fields = vertical();
        fields.setPadding(dp(18), dp(16), dp(18), dp(18));
        fields.setBackground(shape(palette.card, 24));
        if (fuji && android.os.Build.VERSION.SDK_INT >= 29) {
            CheckBox automatic = new CheckBox(activity);
            automatic.setText("自动识别相机地址"); automatic.setTextSize(14); automatic.setTextColor(palette.text);
            automatic.setMinHeight(dp(48)); automatic.setChecked(fujiAutoAddress); automatic.setEnabled(!connecting);
            automatic.setOnCheckedChangeListener((view, checked) -> {
                fujiAutoAddress = checked;
                if (checked) resolvedAutoAddress = "";
                CameraConnectionPreferences.saveFujiAutoAddress(activity, checked);
                connectionError = ""; changed();
            });
            fields.addView(automatic);
            addSpaced(fields, text("连接所选相机热点后读取实际网关，每次重新识别。手机 WLAN 详情里的 IPv4 地址是手机地址，不能填在这里。", 12, palette.secondary, false), 4);
        }
        fields.addView(text("IP 地址", 13, palette.secondary, false));
        EditText address = input(usesAutomaticCameraAddress() ? resolvedAutoAddress : cameraAddress,
                usesAutomaticCameraAddress() ? "连接相机热点后自动识别" : "填写相机的网关地址", InputType.TYPE_CLASS_PHONE);
        address.setContentDescription("相机 IPv4 地址");
        // input() initializes its text before this watcher is attached.
        address.addTextChangedListener(watcher(value -> {
            cameraAddress = value;
            addressManuallyEdited = true;
            CameraConnectionPreferences.saveDraft(activity, cameraAddress, portText, cameraProtocol);
        }));
        address.setEnabled(!connecting && !usesAutomaticCameraAddress());
        addSpaced(fields, address, 8);
        addSpaced(fields, text("端口", 13, palette.secondary, false), 16);
        EditText port = input(portText, Integer.toString(cameraProtocol.defaultPort), InputType.TYPE_CLASS_NUMBER);
        port.setContentDescription("相机无线连接端口");
        port.addTextChangedListener(watcher(value -> {
            portText = value;
            CameraConnectionPreferences.saveDraft(activity, cameraAddress, portText, cameraProtocol);
        }));
        port.setEnabled(!connecting);
        addSpaced(fields, port, 8);
        addSpaced(body, fields, 8);
        addSpaced(body, text(fuji ? "相机地址以本次热点的实际网关为准，端口通常为 55740。"
                : android.os.Build.VERSION.SDK_INT >= 29 ? "默认地址为 192.168.1.1；如相机显示不同地址，请手动修改。"
                : "地址建议来自当前 Wi-Fi 网关，可手动修改；它不是相机自动发现结果。", 12, palette.secondary, false), 10);
        if (!connectionError.isEmpty()) {
            TextView error = text(connectionError, 14, palette.error, false);
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            addSpaced(body, error, 16);
        }
        Button connect = button(connecting ? "正在连接相机…" : "连接相机", true, this::connect);
        connect.setEnabled(!connecting);
        addSpaced(body, connect, 20);
        if (connecting) {
            addSpaced(body, spinner(), 12);
            addSpaced(body, textAction("取消连接", () -> {
                disconnect(); screen = Screen.GUIDE;
                host.onConnectionCancelled(); changed();
            }), 8);
        }
        TextView compatibility = text(fuji ? "实验适配，尚未通过 X-T20 真机验证。仅支持 JPEG 下载；照片直播、RAF 和向相机导入尼康预设暂不支持。" : "连接后可浏览照片、开启照片直播或导入预设。", 12, palette.secondary, false);
        compatibility.setGravity(Gravity.CENTER);
        addSpaced(body, compatibility, 16);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        guideScroll = scroll;
        scroll.post(() -> { if (guideScroll == scroll) scroll.scrollTo(0, guideScrollY); });
    }

    private void connect() {
        if (closed) return;
        if (connecting || controller.isConnecting()) {
            host.onFeedback("正在连接相机", "请等待当前连接完成，或先取消连接。"); return;
        }
        if (showPhotoTaskBlocked("连接相机")) return;
        if (!usesAutomaticCameraAddress() && !addressManuallyEdited && cameraAddress.isEmpty()) {
            cameraAddress = suggestedCameraHost();
            if (cameraAddress.isEmpty()) {
                connectionError = "当前 Wi-Fi 没有可用的地址建议。请先连接相机 Wi-Fi，或手动填写相机 IP。";
                render();
                feedbackDetails("请检查相机地址", "先连接相机 Wi-Fi，或手动填写 IP 地址。", connectionError);
                return;
            }
        }
        int port;
        try { port = Integer.parseInt(portText.trim()); }
        catch (NumberFormatException error) {
            connectionError = "请输入 1–65535 之间的端口。"; render();
            feedbackDetails("请检查端口", "端口应为 1–65535 的数字。", connectionError); return;
        }
        if (port < 1 || port > 65535) {
            connectionError = "请输入 1–65535 之间的端口。"; render();
            feedbackDetails("请检查端口", "端口应为 1–65535 的数字。", connectionError); return;
        }
        if (!usesAutomaticCameraAddress() && !validIpv4(cameraAddress.trim())) {
            connectionError = "请输入相机热点的网关地址；不要填写手机自身的 IPv4 地址。";
            render();
            feedbackDetails("请检查相机地址", "填写有效的 IP 地址后重试。", connectionError);
            return;
        }
        View focus = activity.getCurrentFocus();
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (keyboard != null && focus != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        retireGrid(true);
        epoch++; dateRequest++;
        final int request = epoch;
        final long intent = ++connectionIntent;
        connecting = true;
        ownsConnectionRequest = true;
        connection = null;
        loadingDownloads = false;
        connectionError = cameraError = "";
        resolvedAutoAddress = "";
        photos.clear(); clearSelection(); savedThisSession.clear();
        pendingThumbs.clear(); failedThumbs.clear();
        paging.reset(false);
        changed();
        final String requestedHost = cameraAddress.trim();
        final CameraProtocol requestedProtocol = cameraProtocol;
        final boolean automaticAddress = usesAutomaticCameraAddress();
        if (!automaticAddress) CameraConnectionPreferences.save(activity, requestedHost, port, requestedProtocol);
        Runnable start = new Runnable() {
            @Override public void run() {
                if (connectionIntent != intent || pendingConnectionStart != this || (closed && ownsController)) return;
                pendingConnectionStart = null;
                AlbumController.Callback<ConnectionInfo> completion = new AlbumController.Callback<ConnectionInfo>() {
                    @Override public void onSuccess(ConnectionInfo result) {
                        if (!current(request)) return;
                        sessionNumber++;
                        connecting = false;
                        ownsConnectionRequest = false;
                        connection = result;
                        if (automaticAddress) resolvedAutoAddress = result.host();
                        if (screen == Screen.GUIDE) {
                            screen = Screen.OVERVIEW;
                            downloadedTab = false;
                        }
                        configureConnection(result.host(), result.port());
                        paging.reset(false);
                        changed();
                        connectedFeedback(result.model());
                        refreshDownloads();
                    }
                    @Override public void onError(String message) {
                        if (!current(request)) return;
                        connecting = false;
                        ownsConnectionRequest = false;
                        ConnectionInfo current = controller.connectionInfo();
                        if (current != null) restoreConnection(current);
                        else if (controller.hasLiveSession()) restoreCameraSession(controller.sessionConnectionInfo());
                        else connection = null;
                        if (automaticAddress && !controller.connectionAttemptHost().isEmpty()) {
                            cameraAddress = controller.connectionAttemptHost();
                            resolvedAutoAddress = cameraAddress;
                            CameraConnectionPreferences.save(activity, cameraAddress, port, requestedProtocol);
                        }
                        connectionError = message;
                        changed();
                        feedbackDetails("相机连接失败", "请检查相机 Wi-Fi 和连接地址后重试。", message);
                    }
                };
                if (automaticAddress) controller.connectCameraViaGateway(port, requestedProtocol, completion);
                else controller.connectCamera(requestedHost, port, requestedProtocol, completion);
            }
        };
        pendingConnectionStart = start;
        host.onFeedback("正在连接相机", "请保持相机 Wi-Fi 开启。");
        host.beforeConnect(start);
    }

    private void maybeLoadMore(GridView expectedGrid) {
        if (closed || screen != Screen.OVERVIEW || liveTab || downloadedTab || selectionMode || downloading || exporting
                || !isConnected() || paging.isLoading() || paging.isPaused() || !paging.hasMore()) return;
        if (connection != null && connection.protocol() == CameraProtocol.FUJIFILM_X_T20 && !pendingThumbs.isEmpty()) return;
        if (expectedGrid != null) {
            if (grid != expectedGrid || grid.getChildCount() == 0 || grid.getLastVisiblePosition() < photos.size() - 1) return;
        } else if (!photos.isEmpty()) return;
        loadNextPage();
    }

    private void loadNextPage() {
        if (closed || liveTab || downloading || selectionMode || !isConnected()) return;
        AlbumPaging.Request pageRequest = paging.begin();
        if (pageRequest == null) return;
        pendingPage = pageRequest;
        cameraError = "";
        final int request = epoch;
        final int browseRequest = browseEpoch;
        if (screen == Screen.OVERVIEW && !downloadedTab && grid != null && pageStatus != null) updateCameraPageFooter();
        else if (screen == Screen.DETAIL) updateDetailNavigation();
        else render();
        final boolean fuji = connection != null && connection.protocol() == CameraProtocol.FUJIFILM_X_T20;
        controller.loadPage(pageRequest.offset(), fuji ? FUJI_PAGE_SIZE : PAGE_SIZE, new AlbumController.Callback<PhotoPage>() {
            @Override public void onSuccess(PhotoPage page) {
                if (!current(request) || browseRequest != browseEpoch) return;
                if (pendingPage == pageRequest) pendingPage = null;
                AlbumPaging.Result result = paging.succeed(pageRequest, page.nextOffset(), page.hasMore());
                if (result == AlbumPaging.Result.STALE) return;
                if (result == AlbumPaging.Result.STALLED) cameraError = "相机未返回新的照片位置，已暂停读取。请重试或重新连接。";
                else photos.addAll(page.items());
                if (screen == Screen.OVERVIEW && !downloadedTab && grid != null && adapter != null) {
                    // Append into the current grid. Replacing it during a fast page response loses the scroll anchor.
                    View first = grid.getChildAt(0);
                    boolean restoreAnchor = !restoringGridPosition && first != null;
                    android.os.Parcelable anchor = restoreAnchor ? grid.onSaveInstanceState() : null;
                    adapter.notifyDataSetChanged(); if (anchor != null) grid.onRestoreInstanceState(anchor);
                    if (photoCountLabel != null) photoCountLabel.setText("相机照片 · " + photos.size() + (paging.hasMore() ? "+" : " 张"));
                    updateCameraPageFooter();
                    // Queue this small page's first visible thumbnails before another metadata page.
                    if (fuji) for (int i = 0; i < Math.min(MAX_THUMB_REQUESTS, page.items().size()); i++) requestRemoteThumb(page.items().get(i), null);
                    final GridView currentGrid = grid; grid.post(() -> maybeLoadMore(currentGrid));
                } else if (screen == Screen.DETAIL) {
                    int index = detailIndex();
                    if (!paging.hasMore() && index + 1 >= photos.size()) pendingNextDetail = null;
                    if (pendingNextDetail != null && pendingNextDetail == remoteDetail && index >= 0 && index + 1 < photos.size()) {
                        moveDetail(1);
                    } else {
                        updateDetailNavigation(); updateViewerStatus();
                        maybeLoadDetailMore();
                    }
                } else render();
            }
            @Override public void onError(String message) {
                if (!current(request) || browseRequest != browseEpoch) return;
                if (pendingPage == pageRequest) pendingPage = null;
                if (!paging.fail(pageRequest)) return;
                cameraError = message;
                if (screen == Screen.DETAIL) { pendingNextDetail = null; updateDetailNavigation(); updateViewerStatus(); }
                else if (screen == Screen.OVERVIEW && !downloadedTab && grid != null && cameraPageFooter != null) updateCameraPageFooter();
                else render();
            }
        });
    }

    private void refreshDownloads() {
        if (closed || loadingDownloads) return;
        loadingDownloads = true;
        final int request = epoch;
        final int localRequest = ++dateRequest;
        final int completedAtRequest = controller.photoLiveState().completed;
        controller.listDownloads(new AlbumController.Callback<List<DownloadedPhoto>>() {
            @Override public void onSuccess(List<DownloadedPhoto> result) {
                if (!current(request) || localRequest != dateRequest) return;
                dates.execute(() -> {
                    List<DownloadedPhoto> prepared;
                    try { prepared = dateReader.read(result, () -> localRequest != dateRequest || Thread.currentThread().isInterrupted()); }
                    catch (java.io.InterruptedIOException cancelled) { return; }
                    catch (Exception failedDate) { prepared = result; }
                    List<DownloadedPhoto> ordered = new ArrayList<>();
                    List<DownloadedPhoto> present = new ArrayList<>();
                    for (DownloadedPhoto photo : prepared) if (photo.file().isFile()) present.add(photo);
                    List<PhotoLibraryCatalog.PhotoGroup> catalogSnapshot;
                    String catalogError = "";
                    try {
                        if (libraryCatalog == null) libraryCatalog = new PhotoLibraryCatalog(activity.getFilesDir(), activity.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES));
                        catalogSnapshot = libraryCatalog.list(present, () -> localRequest != dateRequest || Thread.currentThread().isInterrupted());
                    } catch (java.io.InterruptedIOException stopped) { return; }
                    catch (Exception failure) { catalogSnapshot = new ArrayList<>(); catalogError = "照片版本暂时无法整理，当前显示全部文件；请重新读取。"; }
                    List<DownloadedPhoto> cards = new ArrayList<>();
                    for (PhotoLibraryCatalog.PhotoGroup group : catalogSnapshot) cards.add(group.display.withPhotoDate(group.photoDate));
                    if (!catalogError.isEmpty()) cards.addAll(present);
                    final List<PhotoLibraryCatalog.PhotoGroup> preparedCatalog = catalogSnapshot;
                    final String preparedError = catalogError;
                    String groupDay = currentDownloadDay();
                    List<PhotoDateGroups.Group> groups = PhotoDateGroups.group(cards);
                    for (PhotoDateGroups.Group group : groups) ordered.addAll(group.photos);
                    main.post(() -> {
                        if (!current(request) || localRequest != dateRequest) return;
                        loadingDownloads = false; localLoaded = true; localError = preparedError;
                        if (downloadDateList != null || adapter != null && adapter.local) retireGrid(false);
                        Set<String> selectedGroups = new HashSet<>();
                        for (PhotoLibraryCatalog.PhotoGroup group : photoGroups) for (DownloadedPhoto member : group.photos()) if (selectedLocal.contains(member.file().getAbsolutePath())) selectedGroups.add(group.id);
                        rawDownloads.clear(); rawDownloads.addAll(present);
                        photoGroups.clear(); photoGroups.addAll(preparedCatalog); libraryRevision++; liveGridSourceKey = "";
                        allDownloads.clear(); allDownloads.addAll(ordered); applyDownloadFilter();
                        Set<String> available = new HashSet<>();
                        for (DownloadedPhoto photo : rawDownloads) available.add(photo.file().getAbsolutePath());
                        selectedLocal.retainAll(available);
                        for (PhotoLibraryCatalog.PhotoGroup group : photoGroups) if (selectedGroups.contains(group.id)) { for (DownloadedPhoto member : group.photos()) selectedLocal.remove(member.file().getAbsolutePath()); selectedLocal.add(group.display.file().getAbsolutePath()); }
                        savedThisSession.entrySet().removeIf(entry -> !available.contains(entry.getValue().file().getAbsolutePath()));
                        restoreLiveDetailOriginal();
                        if (downloadedTab && screen == Screen.OVERVIEW) render();
                        else if (screen == Screen.DETAIL) { updateDetailNavigation(); updateViewerStatus(); }
                        else if (liveTab) updatePhotoLiveView();
                        if (completedAtRequest != controller.photoLiveState().completed) refreshDownloads();
                    });
                });
            }
            @Override public void onError(String message) {
                if (!current(request) || localRequest != dateRequest) return;
                loadingDownloads = false;
                localError = message;
                if (downloadedTab && screen == Screen.OVERVIEW) render();
            }
        });
    }

    private boolean presetBusy() { return photoPresets != null && photoPresets.state().active; }
    private int selectionCount() { return downloadedTab || liveTab ? selectedLocal.size() : selected.size(); }
    private void clearSelection() { selected.clear(); selectedLocal.clear(); }

    private void toggleLocalSelection(DownloadedPhoto photo) {
        if (showPhotoTaskBlocked("选择照片")) return;
        String path = photo.file().getAbsolutePath();
        if (!selectedLocal.remove(path)) selectedLocal.add(path);
        if (liveTab && screen == Screen.OVERVIEW) updateLiveSelection(); else changed();
    }

    private void renderSelectionActions(LinearLayout parent, boolean camera) {
        LinearLayout region = vertical(); region.setPadding(dp(liveTab ? 0 : 20), dp(4), dp(liveTab ? 0 : 20), dp(8));
        LinearLayout actions = horizontal(); actions.setGravity(Gravity.CENTER_VERTICAL);
        boolean stacked = activity.getResources().getConfiguration().fontScale > 1.4f;
        TextView count = text("已选 " + selectionCount() + " 张", 13, palette.secondary, false);
        count.setMaxLines(2);
        if (stacked) addSpaced(region, count, 0);
        else actions.addView(count, new LinearLayout.LayoutParams(0, -2, 1));
        if (camera) {
            Button download = textAction("下载照片", () -> {
                List<PhotoItem> chosen = new ArrayList<>();
                for (PhotoItem item : photos) if (selected.contains(item.handle())) chosen.add(item);
                if (!requirePhotoSelection("下载照片") || !requirePhotoProcessingIdle("下载照片")) return;
                rawPanel.chooseBatchFormat(chosen, () -> startBatch(chosen));
            });
            actions.addView(download, stacked ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-2, -2));
        }
        if (!camera && photoPresets != null) { actions.addView(textAction("批量导出", this::exportSelectedEdits), new LinearLayout.LayoutParams(-2, dp(48))); }
        if (!camera && photoPresets != null) {
            Button apply = textAction("套用效果", this::choosePhotoPreset);
            apply.setBackground(interactive(palette.primaryContainer, 14));
            LinearLayout.LayoutParams params = stacked ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-2, -2);
            params.leftMargin = dp(8); actions.addView(apply, params);
        }
        if (downloadedTab) {
            Button delete = textAction("删除", () -> {
                List<DownloadedPhoto> chosen = new ArrayList<>();
                for (DownloadedPhoto photo : downloads) if (selectedLocal.contains(photo.file().getAbsolutePath())) chosen.add(photo);
                confirmDeletePhotos(chosen);
            });
            delete.setTextColor(palette.error);
            LinearLayout.LayoutParams params = stacked ? new LinearLayout.LayoutParams(0, -2, 1) : new LinearLayout.LayoutParams(-2, -2);
            params.leftMargin = dp(8); actions.addView(delete, params);
        }
        addSpaced(region, actions, stacked ? 4 : 0); parent.addView(region, new LinearLayout.LayoutParams(-1, -2));
    }

    private String deletionBlockedReason() {
        if (controller.isDeletingDownloadedPhotos()) return "正在删除照片，请等待完成。";
        PhotoLiveState state = controller.photoLiveState();
        if (state.enabled || state.starting || state.receiving) return "请先停止照片直播，等待照片保存完成后再删除。";
        if (presetBusy()) return "正在套用预设，请等待完成或停止处理后再删除。";
        if (downloading || exporting || PhotoAlbumSaver.get(activity).isBusy()) return "请等待照片下载或保存完成后再删除。";
        return "";
    }

    private void confirmDeletePhotos(List<DownloadedPhoto> photosToDelete) { confirmDeleteSelection(photosToDelete, false); }

    private void confirmDeleteVersion(DownloadedPhoto effect) {
        if (!isEffectPhoto(effect)) { host.onFeedback("原图需要保留", "可删除某个预设效果，或从照片列表删除整张照片及全部效果。"); return; }
        List<DownloadedPhoto> one = new ArrayList<>(); one.add(effect); confirmDeleteSelection(one, true);
    }

    private void confirmDeleteSelection(List<DownloadedPhoto> photosToDelete, boolean effectOnly) {
        if (closed || deleteDialog != null && deleteDialog.isShowing()) return;
        if (photosToDelete.isEmpty()) { host.onFeedback("请先选择照片", "选择至少一张已下载照片后，再执行删除。"); return; }
        if (loadingDownloads || libraryCatalog == null) { host.onFeedback("正在整理照片版本", "请等待整理完成，再删除照片。"); return; }
        for (DownloadedPhoto photo : photosToDelete) if (photoGroup(photo) == null) {
            host.onFeedback("照片版本尚未就绪", "请重新读取照片后再删除，现有文件会保留。"); refreshDownloads(); return;
        }
        if (!effectOnly && photosToDelete.size() == 1 && photoGroup(photosToDelete.get(0)).orphan()) { confirmDeleteSelection(photosToDelete, true); return; }
        String blocked = deletionBlockedReason();
        if (!blocked.isEmpty()) { host.onFeedback("暂时无法删除", blocked); return; }
        if (photosToDelete.size() > 1000) { host.onFeedback("请分批删除", "每次最多删除 1,000 张照片。"); return; }
        Map<String, DownloadedPhoto> members = new java.util.LinkedHashMap<>(); Set<String> groups = new HashSet<>();
        for (DownloadedPhoto photo : photosToDelete) {
            PhotoLibraryCatalog.PhotoGroup group = photoGroup(photo);
            groups.add(group == null ? photo.file().getAbsolutePath() : group.id);
            if (group == null) members.put(photo.file().getAbsolutePath(), photo);
            else for (DownloadedPhoto member : group.photos()) if (!effectOnly || member.file().getName().equals(photo.file().getName())) members.put(member.file().getAbsolutePath(), member);
        }
        final List<DownloadedPhoto> chosen = new ArrayList<>(members.values());
        if (chosen.size() > 1000) { host.onFeedback("请分批删除", "所选照片包含较多效果版本，请减少选择后重试。"); return; }
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(effectOnly ? "删除这个效果？" : "删除 " + groups.size() + " 张照片及全部效果？")
                .setMessage(effectOnly ? "将删除这个预设效果的 App 文件和系统相册副本。\n\n原图和其他效果会保留，此操作不可撤销。"
                        : "将删除所选照片组中现有的原图、预设效果及对应系统相册副本，并从所属项目中移除。原图关联不可用的效果仅删除自身。\n\n相机存储卡上的照片会保留，此操作不可撤销。")
                .setNegativeButton("取消", null).setPositiveButton("删除", null).create();
        deleteDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (deleteDialog == dialog) deleteDialog = null; });
        dialog.setOnShowListener(ignored -> {
            Button delete = dialog.getButton(AlertDialog.BUTTON_POSITIVE); delete.setTextColor(palette.error);
            delete.setOnClickListener(v -> {
                String reason = deletionBlockedReason();
                if (!reason.isEmpty()) { dialog.dismiss(); host.onFeedback("暂时无法删除", reason); return; }
                dialog.dismiss();
                host.beforeSavePhotos(() -> {
                    if (closed) return;
                    controller.deleteDownloadedPhotos(chosen, new AlbumController.Callback<PhotoDeleteResult>() {
                        @Override public void onSuccess(PhotoDeleteResult result) {
                            if (closed) return;
                            Set<String> removed = new HashSet<>();
                            for (DownloadedPhoto photo : result.deletedPhotos) removed.add(photo.file().getAbsolutePath());
                            downloads.removeIf(photo -> removed.contains(photo.file().getAbsolutePath()));
                            savedThisSession.entrySet().removeIf(entry -> removed.contains(entry.getValue().file().getAbsolutePath()));
                            selectionMode = false; clearSelection();
                            if (localDetail != null && removed.contains(localDetail.file().getAbsolutePath())) { clearDetailImage(); localDetail = null; screen = Screen.OVERVIEW; }
                            refreshDownloads(); changed();
                            if (result.failed == 0) android.widget.Toast.makeText(activity, effectOnly ? "已删除这个效果，原图保留" : "已删除所选照片及全部效果", android.widget.Toast.LENGTH_SHORT).show();
                            else {
                                StringBuilder message = new StringBuilder("已删除 ").append(result.deleted).append(" 张，未完成 ").append(result.failed).append(" 张。未完成的照片仍保留在 App 内。");
                                for (int i = 0; i < Math.min(5, result.failures.size()); i++) {
                                    PhotoDeleteResult.Failure failure = result.failures.get(i);
                                    message.append("\n\n").append(failure.photo.filename()).append("：").append(failure.message);
                                }
                                host.onMessage("部分照片未删除", message.toString());
                            }
                        }
                        @Override public void onError(String error) { if (!closed) { feedbackDetails("删除未开始", "照片仍保留，可查看详情后重试。", error); changed(); } }
                    });
                    changed();
                });
            });
        }); dialog.show();
    }

    private void showPhotoVersions() {
        if (!canShowProjectDialog() || photoVersionsDialog != null || localDetail == null) return;
        PhotoLibraryCatalog.PhotoGroup group = photoGroup(localDetail);
        if (group == null) { host.onFeedback("正在整理照片版本", "请稍候重试，原图和效果文件均保留。"); refreshDownloads(); return; }
        String[] labels = new String[group.versions.size()]; int checked = -1;
        for (int i=0; i<labels.length; i++) { PhotoLibraryCatalog.Version version = group.versions.get(i); labels[i] = version.label(); if (version.photo.file().equals(localDetail.file())) checked = i; }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("照片版本")
                .setSingleChoiceItems(labels, checked, (dialog,index) -> { dialog.dismiss(); choosePhotoVersion(group, group.versions.get(index)); })
                .setNegativeButton("取消", null);
        DownloadedPhoto current = localDetail;
        if (isEffectPhoto(current)) builder.setNeutralButton("删除当前效果", (dialog,which) -> confirmDeleteVersion(current));
        AlertDialog dialog = builder.create(); photoVersionsDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (photoVersionsDialog == dialog) photoVersionsDialog = null; }); dialog.show();
    }

    private void choosePhotoVersion(PhotoLibraryCatalog.PhotoGroup group, PhotoLibraryCatalog.Version version) {
        if (closed || screen != Screen.DETAIL || localDetail == null || !samePhoto(localDetail, version.photo)) return;
        if (showPhotoTaskBlocked("切换照片版本")) return;
        if (localDetail.file().equals(version.photo.file())) { if(filterEditor!=null)filterEditor.bindSavedVersion(localDetail);return; }
        final DownloadedPhoto previous = localDetail;
        dates.execute(() -> {
            try {
                if (libraryCatalog == null) throw new IOException("照片版本尚未就绪。");
                libraryCatalog.selectDisplay(group, version.photo);
                main.post(() -> {
                    if (closed || screen != Screen.DETAIL || localDetail != previous) return;
                    clearDetailImage(); localDetail = version.photo;if(filterEditor!=null)filterEditor.bindSavedVersion(localDetail);render();refreshDownloads();
                });
            } catch (Exception failure) { main.post(() -> { if (!closed) host.onFeedback("照片版本未切换", "文件可能已变化，请重新读取照片后重试。"); }); }
        });
    }

    private void photoLibraryChanged() {
        if (closed) return;
        retireGrid(false); dateRequest++; loadingDownloads = false;
        clearSelection();
        thumbs.evictAll(); failedThumbs.clear();
        if (localDetail != null && !localDetail.file().isFile()) goBack();
        refreshDownloads(); refreshLiveProjects();
        if (screen == Screen.OVERVIEW) render();
    }

    private List<PhotoPresetBatch.Source> selectedPresetSources() {
        List<PhotoPresetBatch.Source> result = new ArrayList<>();
        if (liveTab) {
            for (LiveGridPhoto photo : liveGridPhotos) if (photo.saved != null && selectedLocal.contains(photo.saved.file().getAbsolutePath())) result.add(PhotoPresetBatch.Source.local(photo.saved));
        } else if (downloadedTab) {
            for (DownloadedPhoto photo : downloads) if (selectedLocal.contains(photo.file().getAbsolutePath())) result.add(PhotoPresetBatch.Source.local(photo));
        } else {
            for (PhotoItem photo : photos) if (selected.contains(photo.handle())) {
                DownloadedPhoto saved = savedThisSession.get(photo.handle());
                result.add(saved != null && saved.file().isFile() ? PhotoPresetBatch.Source.local(saved) : PhotoPresetBatch.Source.remote(photo));
            }
        }
        return result;
    }

    private void choosePhotoPreset() {
        if(!requirePhotoSelection("套用效果")||!requirePhotoProcessingIdle("套用效果"))return;
        new AlertDialog.Builder(activity).setTitle("为所选照片套用效果").setItems(new String[]{"滤镜预设","我的配方"},(d,index)->{if(index==0)chooseFilterPreset();else chooseBatchRecipe();}).setNegativeButton("取消",null).show();
    }
    private void chooseBatchRecipe(){
        if(photoPresets==null||preparingEditedExport||!requirePhotoSelection("套用配方")||!requirePhotoProcessingIdle("套用配方"))return;
        final List<PhotoPresetBatch.Source> sources=selectedPresetSources();
        if(sources.isEmpty()||sources.size()>PhotoPresetBatch.MAX_BATCH){host.onMessage("请分批处理","每次请选择 1–"+PhotoPresetBatch.MAX_BATCH+" 张照片。");return;}
        for(PhotoPresetBatch.Source source:sources)if(source.localPhoto()==null){host.onMessage("先下载原图","配方需要完整本地原图，请先下载所选照片。");return;}
        final String owner=PhotoFilterEditor.accountOwner(activity);preparingEditedExport=true;
        dates.execute(()->{try{List<cc.huevia.album.PhotoRecipeStore.Recipe> recipes=new cc.huevia.album.PhotoRecipeStore(activity.getFilesDir(),owner).list();main.post(()->{preparingEditedExport=false;if(closed||!owner.equals(PhotoFilterEditor.accountOwner(activity)))return;if(recipes.isEmpty()){host.onMessage("还没有配方","先打开一张照片，在“配方”中保存当前滤镜和调节，再回来批量套用。");return;}String[] names=new String[recipes.size()];for(int i=0;i<names.length;i++)names[i]=recipes.get(i).name+" · "+recipes.get(i).summary();new AlertDialog.Builder(activity).setTitle("我的配方 · "+sources.size()+" 张照片").setItems(names,(d,index)->{cc.huevia.album.PhotoRecipeStore.Recipe recipe=recipes.get(index);new AlertDialog.Builder(activity).setTitle("套用“"+recipe.name+"”？").setMessage("将替换所选 "+sources.size()+" 张照片的滤镜、强度和调节，保留每张照片已有的构图。\n只更新草稿；检查效果后再批量导出。\n"+recipe.summary()).setNegativeButton("取消",null).setPositiveButton("应用配方",(dialog,which)->applySelectedRecipe(sources,recipe,owner)).show();}).setNegativeButton("取消",null).show();});}catch(Exception failure){main.post(()->{preparingEditedExport=false;if(!closed)host.onMessage("无法读取配方",failure.getMessage());});}});
    }
    private void applySelectedRecipe(List<PhotoPresetBatch.Source> sources,cc.huevia.album.PhotoRecipeStore.Recipe recipe,String owner){
        if(closed||preparingEditedExport||!owner.equals(PhotoFilterEditor.accountOwner(activity))||!requirePhotoProcessingIdle("套用配方"))return;preparingEditedExport=true;
        Runnable apply=()->dates.execute(()->{int applied=0;try{
            cc.huevia.album.PhotoEditStore store=new cc.huevia.album.PhotoEditStore(activity.getFilesDir(),owner);
            for(PhotoPresetBatch.Source source:sources){if(closed||!owner.equals(PhotoFilterEditor.accountOwner(activity)))throw new java.io.IOException("账号或页面已变化，剩余照片未处理。");DownloadedPhoto photo=source.localPhoto();DownloadedPhoto original=photoPresets.processingOriginalFor(photo);String sha=cc.huevia.album.PhotoEditStore.hashFile(original.file());cc.huevia.album.PhotoEditStore.Draft stored=store.read(sha);if(PhotoPresetBatch.isSavedEffect(photo)&&(stored==null||stored.exported&&!PhotoPresetBatch.matchesSavedDraft(photo,stored)))throw new java.io.IOException("此保存版本的构图暂不可恢复，请先选择原图或恢复草稿。");cc.huevia.ncp.PhotoGeometry composition=stored!=null&&(!stored.exported||PhotoPresetBatch.matchesSavedDraft(photo,stored))?stored.geometry:cc.huevia.ncp.PhotoGeometry.NONE;store.save(recipe.apply(sha,composition));applied++;}
            final int count=applied;main.post(()->{preparingEditedExport=false;if(closed||!owner.equals(PhotoFilterEditor.accountOwner(activity)))return;thumbs.evictAll();refreshDownloads();if(liveTab)updatePhotoLiveView();host.onMessage("已套用配方",count+" 张照片已更新草稿，原图不变。可以逐张检查，再点“批量导出”。");});
        }catch(Exception failure){final int count=applied;main.post(()->{preparingEditedExport=false;if(!closed&&owner.equals(PhotoFilterEditor.accountOwner(activity)))host.onMessage("配方套用未全部完成","已更新 "+count+" / "+sources.size()+" 张；其余未处理，可重试。\n"+failure.getMessage());});}});
        if(filterEditor!=null)filterEditor.afterPendingSaves(apply);else apply.run();
    }
    private void chooseFilterPreset() {
        if (!liveTab && !downloadedTab) return;
        if (!canShowProjectDialog() || presetPicker != null || presetSelectionPage != null) return;
        if (photoPresets == null) { host.onFeedback("预设处理尚未就绪", "请返回相册后重试。"); return; }
        if (!requirePhotoSelection("套用预设") || !requirePhotoProcessingIdle("套用预设")) return;
        final List<PhotoPresetBatch.Source> sources = selectedPresetSources();
        if (sources.isEmpty()) { host.onFeedback("照片尚未保存", "原图接收完成后才能套用预设，请稍后重试。"); return; }
        for (PhotoPresetBatch.Source source : sources) if (source.requiresCamera() && !requireCameraPhotos("下载照片并套用预设")) return;
        if (sources.size() > PhotoPresetBatch.MAX_BATCH) {
            host.onFeedback("请分批处理", "每次最多套用 " + PhotoPresetBatch.MAX_BATCH + " 张照片，当前已选择 " + sources.size() + " 张。"); return;
        }
        final String sourcePage = navigationKey(), sourceProject = selectedLiveProjectId;
        final Set<Long> sourceSelection = new LinkedHashSet<>(selected);
        final Set<String> sourceLocalSelection = new LinkedHashSet<>(selectedLocal);
        showPresetSelection("套用预设", "", false,
                () -> sourcePage.equals(navigationKey()) && sourceProject.equals(selectedLiveProjectId)
                        && sourceSelection.equals(selected) && sourceLocalSelection.equals(selectedLocal),
                preset -> { if (preset != null) confirmPhotoPreset(sources, preset); });
    }

    private void confirmPhotoPreset(List<PhotoPresetBatch.Source> sources, PresetRecord preset) {
        if (!canShowProjectDialog()) return;
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("套用「" + preset.displayName + "」")
                .setMessage("已选 " + sources.size() + " 张照片\n\n本地照片只更新滤镜草稿，保留调节和构图。\n相机照片先保存原图，再按所选位置保留效果。")
                .setNegativeButton("取消", null).setPositiveButton("开始处理", null).create();
        presetPicker = dialog;
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!requirePhotoProcessingIdle("套用预设")) return;
            for (PhotoPresetBatch.Source source : sources) if (source.requiresCamera() && !requireCameraPhotos("下载照片并套用预设")) return;
            dialog.dismiss();
            if(sources.stream().allMatch(source->source.localPhoto()!=null)){applySelectedFilter(sources,preset);return;}
            PhotoSaveChoice.show(activity,"套用预设并保存","sync-effects",PhotoSaveTarget.APP_ONLY,false,(target,extra)->host.beforeSavePhotos(target,() -> {
                if (closed || !requirePhotoProcessingIdle("套用预设")) return;
                for (PhotoPresetBatch.Source source : sources) if (source.requiresCamera() && !requireCameraPhotos("下载照片并套用预设")) return;
                presetCancelRequested = false;
                if (!liveTab && !downloadedTab) cancelCameraBrowse();
                photoPresets.startTracked(new ArrayList<>(sources), preset.id,target,false,new AlbumController.Callback<Void>() {
                    @Override public void onSuccess(Void value) {
                        if (!closed) {
                            batchMessage = ""; downloadErrors.clear(); selectionMode = false; clearSelection();
                            if (liveTab && screen == Screen.OVERVIEW) updatePhotoLiveView(); else changed();
                        }
                    }
                    @Override public void onError(String error) { if (!closed) feedbackDetails("未开始套用预设", "请检查所选照片和预设后重试。", error); }
                },null);
            }));
        }));
        dialog.setOnDismissListener(ignored -> { if (presetPicker == dialog) presetPicker = null; }); dialog.show();
    }

    private void applySelectedFilter(List<PhotoPresetBatch.Source> sources,PresetRecord preset){
        final String owner=PhotoFilterEditor.accountOwner(activity);if(preparingEditedExport||closed)return;preparingEditedExport=true;
        Runnable apply=()->dates.execute(()->{int count=0;try{
            byte[] bytes=new cc.huevia.library.PresetLibrary(activity.getFilesDir()).readPreset(preset.id);cc.huevia.ncp.PresetPreviewProfiles.fromPreset(bytes);
            cc.huevia.album.PhotoEditStore store=new cc.huevia.album.PhotoEditStore(activity.getFilesDir(),owner);
            for(PhotoPresetBatch.Source source:sources){if(closed||!owner.equals(PhotoFilterEditor.accountOwner(activity)))throw new IOException("页面或账号已变化，其余照片未处理。");DownloadedPhoto photo=source.localPhoto(),original=photoPresets.processingOriginalFor(photo);String sha=cc.huevia.album.PhotoEditStore.hashFile(original.file());cc.huevia.album.PhotoEditStore.Draft previous=store.read(sha);boolean usable=previous!=null&&(!previous.exported||PhotoPresetBatch.matchesSavedDraft(photo,previous));if(PhotoPresetBatch.isSavedEffect(photo)&&!usable)throw new IOException("该保存版本的编辑设置暂不可恢复，请先选择原图。");store.save(new cc.huevia.album.PhotoEditStore.Draft(sha,bytes,preset.displayName,100,usable?previous.adjustments:cc.huevia.ncp.PhotoAdjustments.NONE,usable?previous.geometry:cc.huevia.ncp.PhotoGeometry.NONE,false));count++;}
            final int done=count;main.post(()->{preparingEditedExport=false;if(closed)return;thumbs.evictAll();host.onActionFeedback("已更新 "+done+" 张照片","滤镜已写入各照片草稿，手动调节和构图保留；手机相册未新增照片。","导出设置",()->exportEditsForSources(sources));refreshDownloads();});
        }catch(Exception failure){final int done=count;main.post(()->{preparingEditedExport=false;if(!closed)host.onMessage("部分照片未处理","已更新 "+done+" 张，其余可重试。\n"+failure.getMessage());});}});
        if(filterEditor!=null)filterEditor.afterPendingSaves(apply);else apply.run();
    }
    private void exportEditsForSources(List<PhotoPresetBatch.Source> sources){List<DownloadedPhoto> photos=new ArrayList<>();for(PhotoPresetBatch.Source source:sources)if(source.localPhoto()!=null)photos.add(source.localPhoto());exportLocalWorkspace(photos);}

    private void renderPresetBatchStatus(LinearLayout parent) {
        if (photoPresets == null || photoPresets.state().total == 0) return;
        LinearLayout card = vertical(); card.setPadding(dp(14), dp(8), dp(6), dp(10)); card.setBackground(shape(palette.card, 18));
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = vertical(); presetBatchTitle = text("", 14, palette.text, true);
        presetBatchTitle.setSingleLine(true); presetBatchTitle.setEllipsize(TextUtils.TruncateAt.END); words.addView(presetBatchTitle);
        presetBatchDetail = text("", 12, palette.secondary, false); presetBatchDetail.setMaxLines(2); presetBatchDetail.setEllipsize(TextUtils.TruncateAt.MIDDLE); addSpaced(words, presetBatchDetail, 4);
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        presetBatchView = textAction("查看效果", () -> {
            PhotoPresetBatchState state = photoPresets.state();
            if (state.active || state.results.isEmpty()) return;
            DownloadedPhoto effect = state.results.get(0);
            if (!effect.file().isFile()) { host.onFeedback("效果图暂不可用", "照片文件已移动或删除，请重新套用预设。"); return; }
            downloadedTab = true; liveTab = false; selectionMode = false; clearSelection();
            for (int i = state.results.size() - 1; i >= 0; i--) {
                DownloadedPhoto result = state.results.get(i); boolean included = false;
                for (DownloadedPhoto saved : downloads) if (saved.file().equals(result.file())) { included = true; break; }
                if (!included && result.file().isFile()) downloads.add(0, result);
            }
            openLocal(effect);
        }); row.addView(presetBatchView, new LinearLayout.LayoutParams(-2, dp(48)));
        presetBatchCancel = textAction("停止", () -> {
            if (presetBusy()) { presetCancelRequested = true; photoPresets.cancel(); updatePresetBatchStatus(); }
            else {
                PhotoPresetBatchState state = photoPresets.state(); StringBuilder details = new StringBuilder("从原图生成 · 保留原图");
                if (state.reused > 0) details.append("\n\n已复用 ").append(state.reused).append(" 张现有效果。");
                if (state.merged > 0) details.append("\n\n已合并 ").append(state.merged).append(" 张重复选择。");
                for (int i = 0; i < Math.min(8, state.errors.size()); i++) details.append("\n\n").append(state.errors.get(i));
                if (state.errors.size() > 8) details.append("\n\n另有 ").append(state.errors.size() - 8).append(" 项未完成。");
                host.onMessage("处理详情", details.toString());
            }
        }); row.addView(presetBatchCancel, new LinearLayout.LayoutParams(-2, dp(48))); card.addView(row);
        presetBatchProgress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); presetBatchProgress.setMax(1000);
        presetBatchProgress.setProgressTintList(ColorStateList.valueOf(palette.primary));
        LinearLayout.LayoutParams progress = new LinearLayout.LayoutParams(-1, dp(4)); progress.topMargin = dp(4); progress.rightMargin = dp(8); card.addView(presetBatchProgress, progress);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(dp(20), dp(8), dp(20), dp(4)); parent.addView(card, params);
        updatePresetBatchStatus();
    }

    private void updatePresetBatchStatus() {
        if (photoPresets == null || presetBatchTitle == null) return;
        PhotoPresetBatchState state = photoPresets.state();
        boolean allReused = !state.active && state.failed == 0 && state.cancelled == 0 && state.reused > 0
                && state.reused == state.results.size();
        presetBatchTitle.setText(state.active ? "套用预设 · " + progressPercent(state.progress) + "%"
                : allReused ? "已使用现有效果" : "预设处理已结束");
        String detail = "完成 " + state.completed + " / " + state.total + " 张" + (state.failed > 0 ? " · 失败 " + state.failed : "") + (state.cancelled > 0 ? " · 已停止 " + state.cancelled : "");
        if (state.active && !state.currentFilename.isEmpty()) detail = "第 " + state.currentNumber + " / " + state.total + " 张 · "
                + state.currentStage + " " + progressPercent(state.currentProgress) + "%\n" + state.currentFilename;
        else if (!state.active && state.failed == 0 && state.completed > 0 && state.reused == 0 && state.merged == 0) detail += " · 结果已保留";
        if (state.reused > 0 || state.merged > 0) detail += "\n" + (state.reused > 0 ? "复用 " + state.reused + " 张" : "")
                + (state.reused > 0 && state.merged > 0 ? " · " : "") + (state.merged > 0 ? "合并 " + state.merged + " 项" : "");
        presetBatchDetail.setText(detail);
        presetBatchView.setVisibility(!state.active && !state.results.isEmpty() ? View.VISIBLE : View.GONE);
        presetBatchProgress.setVisibility(state.active ? View.VISIBLE : View.GONE); presetBatchProgress.setProgress((int) (state.progress * 1000));
        presetBatchProgress.setContentDescription("套用总进度 " + progressPercent(state.progress) + "%");
        presetBatchCancel.setVisibility(state.active || !state.errors.isEmpty() || state.reused > 0 || state.merged > 0 ? View.VISIBLE : View.GONE);
        presetBatchCancel.setText(state.active ? presetCancelRequested ? "停止中…" : "停止" : "详情"); presetBatchCancel.setEnabled(!state.active || !presetCancelRequested);
    }

    private void photoPresetChanged() {
        if (closed || photoPresets == null) return;
        boolean active = photoPresets.state().active;
        if (presetWasActive != active) {
            presetWasActive = active;
            if (!active) { presetCancelRequested = false; refreshDownloads(); }
            if (screen == Screen.OVERVIEW && root != null && root.isAttachedToWindow()) {
                if (liveTab) updatePhotoLiveView(); else render();
            }
        }
        if (liveTab && screen == Screen.OVERVIEW) updatePhotoLiveView();
        updatePresetBatchStatus(); updateViewerStatus();
    }

    private void toggleSelection(PhotoItem photo) {
        if (downloading || exporting) return;
        if (!selected.remove(photo.handle())) selected.add(photo.handle());
        changed();
    }

    private void openRemote(PhotoItem photo) {
        clearDetailImage(); viewerControlsVisible = true;
        liveDetail = false; liveDetailIdentity = ""; liveDetailPhotos.clear();
        projectDetail = false; projectDetailPhotos.clear();
        remoteDetail = photo;
        localDetail = savedThisSession.get(photo.handle());
        screen = Screen.DETAIL;
        changed();
    }

    private void openLocal(DownloadedPhoto photo) {
        clearDetailImage(); viewerControlsVisible = true;
        liveDetail = false; liveDetailIdentity = ""; liveDetailPhotos.clear();
        projectDetail = false; projectDetailPhotos.clear();
        remoteDetail = null;
        localDetail = photo;
        screen = Screen.DETAIL;
        changed();
    }

    /** Opens a frozen local selection without changing the Sync page's selected project or source. */
    public void openLocalWorkspace(List<DownloadedPhoto> photos, int index) {
        if (closed || photos == null || photos.isEmpty()) return;
        List<DownloadedPhoto> snapshot=new ArrayList<>();for(DownloadedPhoto photo:photos)if(photo!=null&&photo.file().isFile())snapshot.add(photo);
        if(snapshot.isEmpty()){host.onFeedback("照片暂不可用","原图已移动或删除，请重新选择。");return;}
        clearDetailImage();viewerControlsVisible=true;downloadedTab=true;liveTab=false;selectionMode=false;clearSelection();
        liveDetail=false;liveDetailIdentity="";liveDetailPhotos.clear();projectDetail=true;projectDetailPhotos.clear();projectDetailPhotos.addAll(snapshot);
        remoteDetail=null;localDetail=snapshot.get(Math.max(0,Math.min(index,snapshot.size()-1)));screen=Screen.DETAIL;
        refreshDownloads();changed();
    }

    private void selectWorkspacePhotos(List<DownloadedPhoto> photos) {
        dateRequest++;loadingDownloads=false;
        downloadedTab=true;liveTab=false;selectionMode=true;clearSelection();downloads.clear();allDownloads.clear();rawDownloads.clear();
        for(DownloadedPhoto photo:photos)if(photo!=null&&photo.file().isFile()){downloads.add(photo);allDownloads.add(photo);rawDownloads.add(photo);selectedLocal.add(photo.file().getAbsolutePath());}
    }
    /** Batch export bridge; settings remain independent for every selected original. */
    public void exportLocalWorkspace(List<DownloadedPhoto> photos) {
        if(closed||photos==null||photos.isEmpty())return;selectWorkspacePhotos(photos);exportSelectedEdits();
    }
    public void chooseLocalWorkspaceAction(List<DownloadedPhoto> photos) {
        if(closed||photos==null||photos.isEmpty())return;selectWorkspacePhotos(photos);
        new AlertDialog.Builder(activity).setTitle("处理 "+selectedLocal.size()+" 张照片").setItems(new String[]{"批量套用滤镜","批量套用我的配方","导出各照片设置"},(d,which)->{if(which==0)chooseFilterPreset();else if(which==1)chooseBatchRecipe();else exportSelectedEdits();}).setNegativeButton("取消",null).show();
    }

    private void openLivePhoto(PhotoLiveState.LivePhoto photo) {
        if (closed) return;
        clearDetailImage(); releaseLiveHero(); viewerControlsVisible = true;
        liveDetail = true; liveDetailIdentity = photo.identity;
        projectDetail = false; projectDetailPhotos.clear();
        liveDetailPhotos.clear(); liveDetailPhotos.addAll(controller.photoLiveState().photos);
        remoteDetail = photo.photo; localDetail = displayedVersion(photo.saved);
        screen = Screen.DETAIL; changed();
    }

    private void openProjectPhoto(DownloadedPhoto photo) {
        if (closed) return;
        clearDetailImage(); releaseLiveHero(); viewerControlsVisible = true;
        liveDetail = false; liveDetailIdentity = ""; liveDetailPhotos.clear(); projectDetail = true;
        projectDetailPhotos.clear();
        boolean included = false;
        for (LiveGridPhoto item : liveGridPhotos) if (item.saved != null) {
            projectDetailPhotos.add(item.saved);
            if (item.saved.file().equals(photo.file())) included = true;
        }
        if (!included) projectDetailPhotos.add(0, photo);
        remoteDetail = null; localDetail = photo;
        screen = Screen.DETAIL; changed();
    }

    private PhotoLiveState.LivePhoto liveDetailAt(int index) {
        PhotoLiveState.LivePhoto original = liveDetailPhotos.get(index);
        for (PhotoLiveState.LivePhoto entry : controller.photoLiveState().photos) if (entry.identity.equals(original.identity)) return entry;
        return original;
    }

    private PhotoLiveState.LivePhoto currentLiveDetail() {
        if (!liveDetail) return null;
        for (PhotoLiveState.LivePhoto entry : controller.photoLiveState().photos) if (entry.identity.equals(liveDetailIdentity)) return entry;
        for (PhotoLiveState.LivePhoto entry : liveDetailPhotos) if (entry.identity.equals(liveDetailIdentity)) return entry;
        return null;
    }

    private int detailCount() { return projectDetail ? projectDetailPhotos.size() : liveDetail ? liveDetailPhotos.size() : remoteDetail != null ? photos.size() : downloads.size(); }

    private int detailIndex() {
        if (projectDetail && localDetail != null) {
            for (int i = 0; i < projectDetailPhotos.size(); i++) if (samePhoto(projectDetailPhotos.get(i), localDetail)) return i;
        } else if (liveDetail) {
            for (int i = 0; i < liveDetailPhotos.size(); i++) if (liveDetailPhotos.get(i).identity.equals(liveDetailIdentity)) return i;
        } else if (remoteDetail != null) {
            for (int i = 0; i < photos.size(); i++) if (photos.get(i).handle() == remoteDetail.handle()) return i;
        } else if (localDetail != null) {
            for (int i = 0; i < downloads.size(); i++) if (samePhoto(downloads.get(i), localDetail)) return i;
        }
        return -1;
    }

    private void moveDetail(int direction) {
        if (closed || screen != Screen.DETAIL || downloading || exporting
                || direction != -1 && direction != 1) return;
        int index = detailIndex();
        if (index < 0) return;
        int target = index + direction;
        if (!liveDetail && remoteDetail != null && target >= photos.size() && direction > 0 && paging.hasMore() && isConnected()) {
            pendingNextDetail = remoteDetail;
            if (paging.isPaused()) paging.retry();
            loadNextPage(); updateDetailNavigation();
            return;
        }
        int count = detailCount();
        if (target < 0 || target >= count) return;
        if (projectDetail) {
            clearDetailImage(); remoteDetail = null; localDetail = displayedVersion(projectDetailPhotos.get(target)); render(); return;
        }
        if (liveDetail) {
            PhotoLiveState.LivePhoto entry = liveDetailAt(target);
            clearDetailImage(); liveDetailIdentity = entry.identity;
            remoteDetail = entry.photo; localDetail = displayedVersion(entry.saved); render(); return;
        }
        PhotoItem nextRemote = remoteDetail != null ? photos.get(target) : null;
        DownloadedPhoto nextLocal = nextRemote != null ? savedThisSession.get(nextRemote.handle()) : downloads.get(target);
        clearDetailImage();
        remoteDetail = nextRemote; localDetail = nextLocal;
        // This is a photo change inside one screen; keep the grid anchor and immersive chrome state.
        render();
    }

    private void maybeLoadDetailMore() {
        if (closed || liveDetail || screen != Screen.DETAIL || remoteDetail == null || downloading || exporting
                || !isConnected() || !paging.hasMore() || paging.isLoading() || paging.isPaused()) return;
        int index = detailIndex();
        if (index >= 0 && index >= photos.size() - 3) loadNextPage();
    }

    private void updateDetailNavigation() {
        if (screen != Screen.DETAIL || viewerImage == null) return;
        int index = detailIndex();
        int count = detailCount();
        boolean unlocked = !downloading && !exporting;
        boolean previous = unlocked && index > 0;
        boolean next = unlocked && index >= 0 && index + 1 < count;
        viewerImage.setPagingNeighbors(previous ? cachedDetailNeighbor(index - 1) : null, previous,
                next ? cachedDetailNeighbor(index + 1) : null, next);
        boolean retryMore = unlocked && !liveDetail && remoteDetail != null && index >= count - 1 && paging.hasMore()
                && paging.isPaused() && isConnected();
        viewerImage.setOnPageBoundary(direction -> {
            if (direction == 1 && !closed && !liveDetail && screen == Screen.DETAIL && remoteDetail != null
                    && !downloading && !exporting && isConnected() && paging.hasMore() && paging.isPaused()) {
                paging.retry(); loadNextPage();
            }
        }, retryMore);
        if (previous) requestDetailNeighbor(index - 1);
        if (next) requestDetailNeighbor(index + 1);
    }

    private Bitmap cachedDetailNeighbor(int index) {
        if (projectDetail) return thumbs.get(localKey(displayedVersion(projectDetailPhotos.get(index))));
        if (liveDetail) {
            PhotoLiveState.LivePhoto entry = liveDetailAt(index);
            Bitmap local = entry.saved == null ? null : thumbs.get(localKey(displayedVersion(entry.saved)));
            return local != null ? local : thumbs.get("live:" + entry.identity);
        }
        if (remoteDetail != null) {
            PhotoItem photo = photos.get(index);
            DownloadedPhoto saved = savedThisSession.get(photo.handle());
            Bitmap local = saved == null ? null : thumbs.get(localKey(saved));
            return local != null ? local : thumbs.get(remoteKey(photo));
        }
        return thumbs.get(localKey(downloads.get(index)));
    }

    private void requestDetailNeighbor(int index) {
        if (cachedDetailNeighbor(index) != null) return;
        if (projectDetail) { requestLocalThumb(displayedVersion(projectDetailPhotos.get(index))); return; }
        if (liveDetail) {
            PhotoLiveState.LivePhoto entry = liveDetailAt(index);
            if (entry.saved != null) requestLocalThumb(displayedVersion(entry.saved));
            else requestCachedLiveThumb(entry, "live:" + entry.identity);
            return;
        }
        if (remoteDetail != null) {
            PhotoItem photo = photos.get(index);
            DownloadedPhoto saved = savedThisSession.get(photo.handle());
            if (saved != null) requestLocalThumb(saved);
            else requestRemoteThumb(photo, null);
        } else requestLocalThumb(downloads.get(index));
    }

    private void updateDetailPhoto() {
        if (viewerImage == null || screen != Screen.DETAIL) return;
        Bitmap photo = detailBitmap;
        if (viewerEditing && hasEditableDetail() && filterEditor != null && filterEditor.preview() != null) photo = filterEditor.preview();
        if (photo == null && localDetail != null) photo = thumbs.get(localKey(localDetail));
        if (photo == null && remoteDetail != null) photo = thumbs.get(remoteKey(remoteDetail));
        if (viewerPhoto != photo) {
            viewerPhoto = photo;
            // A first preview may arrive while its loading page is already being dragged.
            if (photo != null) viewerImage.setPhotoPreservingTransform(photo);
            else viewerImage.setPhoto(photo);
        }
        if (viewerComparison != null) viewerComparison.setEffect(photo);
        boolean retry = !previewError.isEmpty() && remoteDetail != null && isConnected();
        viewerImage.setPlaceholderText(previewError.isEmpty() ? "正在加载…" : retry ? "预览失败，轻点查看原因" : "暂无预览");
    }

    private void ensureFilterEditor() {
        if (filterEditor != null || photoPresets == null) return;
        filterEditor = new PhotoFilterEditor(activity, photoPresets, new PhotoFilterEditor.Host() {
            public void changed() {
                if (closed || !viewerEditing || !hasEditableDetail()) return;
                thumbs.evictAll();
                if (screen == Screen.DETAIL) {
                    updateDetailPhoto(); updateViewerStatus();
                    if (viewerComparison != null && filterEditor.original() != null) viewerComparison.setOriginal(filterEditor.original());
                }
            }
            public void export(List<PhotoPresetBatch.Edit> edits) { startEditedExport(edits); }
            public void raw() { chooseExportFormat(); }
            public boolean canExportRaw() { return controller.canOfferRawExport(); }
            public void download() { exportCurrentJpeg(); }
            public void addFilters() { host.onPresetRequired(); }
            public void error(String error) { if (!closed) host.onMessage("照片编辑", error); }
        });
    }

    /** DownloadedPhoto represents a committed local JPEG; preview and .part files do not qualify. */
    private boolean hasEditableDetail() {
        if (photoPresets == null || localDetail == null) return false;
        File file = localDetail.file();
        if (file == null || !file.isFile() || file.length() == 0) return false;
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jpg") || name.endsWith(".jpeg");
    }

    private void renderDetail() {
        if (remoteDetail == null && localDetail == null) { screen = Screen.OVERVIEW; renderOverview(); return; }
        restoreLiveDetailOriginal();
        if (!hasEditableDetail()) { renderPhotoViewer(); return; }
        viewerEditing = true; ensureFilterEditor(); root.setBackgroundColor(0xff141415);
        LinearLayout workspace = vertical(); workspace.setBackgroundColor(0xff141415);
        LinearLayout top = horizontal(); top.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout back = new FrameLayout(activity); back.setBackground(interactive(Color.TRANSPARENT,24));
        back.addView(icon(AppIcon.Kind.BACK,Color.WHITE),new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER));
        back.setContentDescription("返回相册"); back.setFocusable(true);back.setOnClickListener(v->goBack());top.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout names=vertical();String filename=localDetail!=null?localDetail.filename():remoteDetail.filename();
        TextView name=text(filename,15,Color.WHITE,true);name.setGravity(Gravity.CENTER);name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.MIDDLE);names.addView(name);
        viewerStatus=text((detailIndex()+1)+" / "+detailCount(),11,0xffadb6c3,false);viewerStatus.setSingleLine(true);viewerStatus.setGravity(Gravity.CENTER);viewerStatus.setEllipsize(TextUtils.TruncateAt.END);names.addView(viewerStatus);
        top.addView(names,new LinearLayout.LayoutParams(0,-2,1));viewerSave=viewerButton("导出",()->{if(viewerEditing&&hasEditableDetail()&&filterEditor!=null&&filterEditor.isReady())filterEditor.showExport();else chooseExportFormat();});
        viewerSave.setTextColor(0xff07244a);viewerSave.setTextSize(13);viewerSave.setSingleLine(true);viewerSave.setEllipsize(TextUtils.TruncateAt.END);viewerSave.setBackground(new InsetDrawable(interactive(0xff8dbbff,12),0,dp(6),0,dp(6)));viewerSave.setPadding(dp(10),0,dp(10),0);viewerSave.setMinWidth(0);viewerSave.setMinimumWidth(0);top.addView(viewerSave,new LinearLayout.LayoutParams(dp(64),dp(48)));
        workspace.addView(top);viewerTop=top;
        boolean landscape=activity.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout body=new LinearLayout(activity);body.setOrientation(landscape?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);workspace.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout photoArea=vertical();body.addView(photoArea,landscape?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(-1,0,1));
        FrameLayout stage=new FrameLayout(activity);photoArea.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        releasePhotoComparison();viewerComparison=null;viewerCompareLayer=null;
        if(viewerImage==null)viewerImage=new PhotoImageView(activity);
        if(viewerImage.getParent() instanceof ViewGroup)((ViewGroup)viewerImage.getParent()).removeView(viewerImage);
        viewerImage.setOnSingleTap(()->{viewerControlsVisible=!viewerControlsVisible;updateViewerChrome();});viewerImage.setOnSwipe(this::moveDetail);stage.addView(viewerImage,new FrameLayout.LayoutParams(-1,-1));updateDetailPhoto();
        FrameLayout tools=new FrameLayout(activity);
        viewerPreset=text("",11,0xffadb6c3,false);viewerPreset.setSingleLine(true);viewerPreset.setEllipsize(TextUtils.TruncateAt.END);viewerPreset.setGravity(Gravity.CENTER_VERTICAL);viewerPreset.setMaxWidth(dp(220));viewerPreset.setPadding(dp(8),0,dp(8),0);viewerPreset.setBackground(new InsetDrawable(interactive(0xee141415,4),0,dp(10),0,dp(10)));viewerPreset.setOnClickListener(v->{if(filterEditor!=null)filterEditor.retryPreparation();});FrameLayout.LayoutParams presetPosition=new FrameLayout.LayoutParams(-2,dp(48),Gravity.TOP|Gravity.START);presetPosition.setMargins(dp(14),0,dp(110),0);tools.addView(viewerPreset,presetPosition);
        FrameLayout compare=new FrameLayout(activity);tools.addView(compare,new FrameLayout.LayoutParams(dp(68),dp(68),Gravity.BOTTOM|Gravity.END));
        viewerComparison=new HoldToCompare(compare,viewerImage);viewerComparison.setIconOnly(true);viewerComparison.setEffect(viewerPhoto);viewerCompareLayer=compare;
        if(comparisonOriginal!=null)viewerComparison.setOriginal(comparisonOriginal);
        viewerStar=new android.widget.ImageButton(activity);viewerStar.setBackground(interactive(Color.TRANSPARENT,24));viewerStar.setPadding(dp(12),dp(12),dp(12),dp(12));viewerStar.setOnClickListener(v->toggleCurrentStar());FrameLayout.LayoutParams starPosition=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.END);starPosition.setMarginEnd(dp(56));tools.addView(viewerStar,starPosition);
        viewerVersions=null;android.widget.ImageButton more=new android.widget.ImageButton(activity);more.setImageDrawable(new AppIcon(AppIcon.Kind.MORE,Color.WHITE,activity.getResources().getDisplayMetrics().density));more.setPadding(dp(12),dp(12),dp(12),dp(12));more.setBackground(interactive(Color.TRANSPARENT,24));more.setContentDescription("更多照片操作和重置");more.setOnClickListener(v->{PopupMenu menu=new PopupMenu(activity,more);menu.getMenu().add("照片版本").setOnMenuItemClickListener(item->{showPhotoVersions();return true;});if(filterEditor!=null)filterEditor.addMenuItems(menu.getMenu());if(downloadedTab&&localDetail!=null&&remoteDetail==null&&!liveDetail&&!projectDetail)menu.getMenu().add("删除照片").setOnMenuItemClickListener(item->{confirmDeletePhotos(java.util.Collections.singletonList(localDetail));return true;});menu.show();});FrameLayout.LayoutParams morePosition=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.END);morePosition.setMarginEnd(dp(8));tools.addView(more,morePosition);
        viewerTools=tools;stage.addView(tools,new FrameLayout.LayoutParams(-1,-1));LinearLayout bottom=vertical();bottom.setBackgroundColor(Color.TRANSPARENT);
        if(filterEditor!=null){View editorDock=filterEditor.createDock();if(landscape){ScrollView scrolling=new ScrollView(activity);scrolling.setFillViewport(false);scrolling.addView(editorDock);bottom.addView(scrolling);}else bottom.addView(editorDock);}
        body.addView(bottom,landscape?new LinearLayout.LayoutParams(dp(300),-1):new LinearLayout.LayoutParams(-1,-2));viewerBottom=bottom;
        workspace.setOnApplyWindowInsetsListener((view,insets)->{
            int left,right,upper,lower;
            if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets safe=insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());left=safe.left;right=safe.right;upper=safe.top;lower=safe.bottom;}else{left=insets.getStableInsetLeft();right=insets.getStableInsetRight();upper=insets.getStableInsetTop();lower=insets.getStableInsetBottom();}
            top.setPadding(left+dp(8),upper+dp(4),right+dp(12),dp(4));body.setPadding(left,0,right,lower);return insets;
        });
        root.addView(workspace,new LinearLayout.LayoutParams(-1,-1));workspace.requestApplyInsets();
        if(localDetail!=null){requestDetailFile(localDetail.file(),localKey(localDetail));requestPhotoComparison(localDetail);}else requestRemotePreview();
        if(filterEditor!=null)filterEditor.bind(localDetail);
        updateViewerStar();updateViewerStatus();updateViewerChrome();updateDetailNavigation();maybeLoadDetailMore();
    }

    private void renderPhotoViewer() {
        if (remoteDetail == null && localDetail == null) { screen = Screen.OVERVIEW; renderOverview(); return; }
        viewerEditing = false; viewerTools = null;viewerPreset=null;
        if (filterEditor != null) filterEditor.unbind();
        root.setBackgroundColor(Color.BLACK);
        FrameLayout stage = new FrameLayout(activity); stage.setBackgroundColor(Color.BLACK);
        releasePhotoComparison(); viewerComparison = null; viewerCompareLayer = null;
        if (viewerImage == null) viewerImage = new PhotoImageView(activity);
        if (viewerImage.getParent() instanceof ViewGroup) ((ViewGroup) viewerImage.getParent()).removeView(viewerImage);
        viewerImage.setOnSingleTap(() -> {
            if (viewerPhoto == null && !previewError.isEmpty() && remoteDetail != null && isConnected()) {
                new AlertDialog.Builder(activity).setTitle("预览未能加载").setMessage(previewError)
                        .setNegativeButton("关闭", null).setPositiveButton("重试预览", (dialog, which) -> { clearDetailImage(); render(); }).show();
            }
            else { viewerControlsVisible = !viewerControlsVisible; updateViewerChrome(); }
        });
        viewerImage.setOnSwipe(this::moveDetail);
        stage.addView(viewerImage, new FrameLayout.LayoutParams(-1, -1));
        updateDetailPhoto();
        String filename = localDetail != null ? localDetail.filename() : remoteDetail.filename();
        LinearLayout top = vertical(); top.setBackgroundColor(0xB8000000);
        LinearLayout titleRow = horizontal(); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout back = new FrameLayout(activity); back.setBackground(interactive(0x00000000, 24));
        ImageView backIcon = icon(AppIcon.Kind.BACK, Color.WHITE); back.addView(backIcon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        back.setContentDescription("返回相册"); back.setFocusable(true); back.setOnClickListener(v -> goBack());
        titleRow.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView name = text(filename, 15, Color.WHITE, false); name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1); nameParams.leftMargin = dp(8); titleRow.addView(name, nameParams);
        viewerStar = new android.widget.ImageButton(activity);
        viewerStar.setBackground(interactive(0x00000000, 24)); viewerStar.setPadding(dp(12), dp(12), dp(12), dp(12));
        viewerStar.setOnClickListener(v -> toggleCurrentStar()); titleRow.addView(viewerStar, new LinearLayout.LayoutParams(dp(48), dp(48)));
        viewerVersions = liveCompactAction("版本", this::showPhotoVersions, Color.WHITE);
        viewerVersions.setBackground(interactive(0x6638404A, 18)); viewerVersions.setMaxWidth(dp(116)); viewerVersions.setEllipsize(TextUtils.TruncateAt.END);
        AppIcon versionArrow = new AppIcon(AppIcon.Kind.CHEVRON_DOWN, Color.WHITE, density()); versionArrow.setBounds(0,0,dp(12),dp(12));
        viewerVersions.setCompoundDrawablesRelative(null,null,versionArrow,null); viewerVersions.setCompoundDrawablePadding(dp(4));
        titleRow.addView(viewerVersions, new LinearLayout.LayoutParams(-2, dp(44)));
        updateViewerStar();
        if (downloadedTab && localDetail != null && remoteDetail == null && !liveDetail && !projectDetail) {
            FrameLayout delete = new FrameLayout(activity); delete.setBackground(interactive(0x00000000, 24));
            delete.addView(icon(AppIcon.Kind.DELETE, Color.WHITE), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
            delete.setContentDescription("删除照片"); delete.setFocusable(true);
            delete.setOnClickListener(v -> { List<DownloadedPhoto> one = new ArrayList<>(); one.add(localDetail); confirmDeletePhotos(one); });
            titleRow.addView(delete, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        top.addView(titleRow);
        stage.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP)); viewerTop = top;
        LinearLayout bottom = horizontal(); bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0xCC000000}));
        viewerStatus = text("", 13, 0xFFE1E3E6, false);
        viewerStatus.setMaxLines(1); viewerStatus.setEllipsize(TextUtils.TruncateAt.END);
        bottom.addView(viewerStatus, new LinearLayout.LayoutParams(0, -2, 1));
        viewerSave = viewerButton("导出", this::chooseExportFormat);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(-2, -2); saveParams.leftMargin = dp(16);
        bottom.addView(viewerSave, saveParams);
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        stage.addView(bottom, bottomParams); viewerBottom = bottom;
        if (isEffectPhoto(localDetail)) {
            FrameLayout compare = new FrameLayout(activity);
            viewerComparison = new HoldToCompare(compare, viewerImage);
            viewerComparison.setEffect(viewerPhoto);
            if (comparisonOriginal != null) viewerComparison.setOriginal(comparisonOriginal);
            else if (comparisonUnavailable) viewerComparison.setUnavailable();
            FrameLayout.LayoutParams compareParams = new FrameLayout.LayoutParams(-1, -1);
            compareParams.bottomMargin = dp(76); stage.addView(compare, compareParams);
            viewerCompareLayer = compare;
        }
        stage.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, right, upper, lower;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                left = safe.left; right = safe.right; upper = safe.top; lower = safe.bottom;
            } else { left = insets.getStableInsetLeft(); right = insets.getStableInsetRight(); upper = insets.getStableInsetTop(); lower = insets.getStableInsetBottom(); }
            top.setPadding(left + dp(8), upper + dp(8), right + dp(16), dp(8));
            bottom.setPadding(left + dp(24), dp(12), right + dp(24), lower + dp(14));
            if (viewerCompareLayer != null) {
                FrameLayout.LayoutParams compareParams = (FrameLayout.LayoutParams) viewerCompareLayer.getLayoutParams();
                compareParams.leftMargin = left; compareParams.rightMargin = right;
                compareParams.bottomMargin = lower + dp(76); viewerCompareLayer.setLayoutParams(compareParams);
            }
            return insets;
        });
        root.addView(stage, new LinearLayout.LayoutParams(-1, -1)); stage.requestApplyInsets();
        if (localDetail != null) {
            requestDetailFile(localDetail.file(), localKey(localDetail));
            requestPhotoComparison(localDetail);
        }
        else requestRemotePreview();
        updateViewerStatus(); updateViewerChrome(); updateDetailNavigation(); maybeLoadDetailMore();
    }

    private void startEditedExport(List<PhotoPresetBatch.Edit> edits) {
        if(closed||photoPresets==null||!requirePhotoProcessingIdle("导出照片"))return;
        if(edits==null||edits.isEmpty())return;
        PhotoPresetBatch.Edit first=edits.get(0);
        if(first.saveTarget!=null){runEditedExport(edits,first.saveTarget,first.forceNewCopy);return;}
        PhotoSaveChoice.show(activity,"导出 "+edits.size()+" 张照片","edited",PhotoSaveTarget.PHONE_ALBUM,true,(target,extra)->runEditedExport(edits,target,extra));
    }
    private void runEditedExport(List<PhotoPresetBatch.Edit> edits,PhotoSaveTarget target,boolean extra) {
        if(closed||photoPresets==null||!requirePhotoProcessingIdle("导出照片"))return;
        host.beforeSavePhotos(target,()->{
            if(closed||!requirePhotoProcessingIdle("导出照片"))return;
            photoPresets.startEdits(edits,target,extra,new AlbumController.Callback<Void>(){
                public void onSuccess(Void ignored){selectionMode=false;clearSelection();if(screen==Screen.OVERVIEW)changed();else updateViewerStatus();}
                public void onError(String error){if(!closed)host.onMessage("未开始导出",error);}
            },new AlbumController.Callback<PhotoPresetBatchState>(){
                public void onSuccess(PhotoPresetBatchState result){
                    if(closed)return;thumbs.evictAll();
                    if(edits.size()==1&&result.completed==1&&result.results.size()==1){
                        DownloadedPhoto saved=result.results.get(0);rememberExportedDisplay(saved);
                        if(filterEditor!=null&&localDetail!=null)filterEditor.refreshExportStatus();
                    }else if(filterEditor!=null&&localDetail!=null)filterEditor.refreshExportStatus();
                    refreshDownloads();
                    String summary="成功 "+result.completed+" 张"+(result.reused>0?" · 复用 "+result.reused+" 张":"")+"\n"+(target==PhotoSaveTarget.APP_ONLY?"手机相册未新增照片。":result.completed>0?"已按所选版本保存到手机相册；已存在的版本不重复新增。":"本次没有照片完成保存。")+"\n原图与各照片的独立设置均保留。";
                    if(result.failed+result.cancelled>0)summary+="\n待重试 "+(result.failed+result.cancelled)+" 张\n"+String.join("\n",result.errors.subList(0,Math.min(5,result.errors.size())));
                    AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle(result.failed+result.cancelled==0?target==PhotoSaveTarget.APP_ONLY?"已保存到 App 内":"已保存到手机相册":"部分照片尚未导出").setMessage(summary).setNegativeButton("关闭",null);
                    if(!photoPresets.retryEdits().isEmpty()){final List<PhotoPresetBatch.Edit> retry=new ArrayList<>(photoPresets.retryEdits());dialog.setPositiveButton("重试未完成项",(d,w)->startEditedExport(retry));}dialog.show();
                }
                public void onError(String error){if(!closed)host.onMessage("导出未完成",error);}
            });
        });
    }

    private void exportSelectedEdits() {
        if (!liveTab && !downloadedTab) return;
        if(preparingEditedExport)return;
        if(!requirePhotoSelection("批量导出")||!requirePhotoProcessingIdle("批量导出")||photoPresets==null)return;
        final List<PhotoPresetBatch.Source> sources=selectedPresetSources();
        if(sources.isEmpty()||sources.size()>PhotoPresetBatch.MAX_BATCH){host.onMessage("请分批导出","每次请选择 1–"+PhotoPresetBatch.MAX_BATCH+" 张照片。");return;}
        for(PhotoPresetBatch.Source source:sources)if(source.requiresCamera()&&!requireCameraPhotos("导出照片"))return;
        final String owner=PhotoFilterEditor.accountOwner(activity);
        preparingEditedExport=true;
        Runnable prepare=()->dates.execute(()->{
            try{
                cc.huevia.album.PhotoEditStore store=new cc.huevia.album.PhotoEditStore(activity.getFilesDir(),owner);List<PhotoPresetBatch.Edit> edits=new ArrayList<>();
                for(PhotoPresetBatch.Source source:sources){
                    DownloadedPhoto photo=source.localPhoto();if(photo==null){edits.add(PhotoPresetBatch.Edit.saved(source));continue;}
                    DownloadedPhoto original=photoPresets.processingOriginalFor(photo);String sha=cc.huevia.album.PhotoEditStore.hashFile(original.file());cc.huevia.album.PhotoEditStore.Draft draft=store.readPending(sha);
                    edits.add(draft==null?PhotoPresetBatch.Edit.saved(source):PhotoPresetBatch.Edit.draft(PhotoPresetBatch.Source.local(original),draft,owner));
                }
                main.post(()->{preparingEditedExport=false;if(closed||!owner.equals(PhotoFilterEditor.accountOwner(activity)))return;startEditedExport(edits);});
            }catch(Exception error){main.post(()->{preparingEditedExport=false;if(!closed)host.onMessage("无法准备批量导出",error.getMessage());});}
        });
        if(filterEditor!=null)filterEditor.afterPendingSaves(prepare);else prepare.run();
    }

    private void rememberExportedDisplay(DownloadedPhoto saved){
        final List<DownloadedPhoto> candidates=new ArrayList<>(rawDownloads);if(!candidates.contains(saved))candidates.add(saved);
        dates.execute(()->{try{
            if(libraryCatalog==null)libraryCatalog=new PhotoLibraryCatalog(activity.getFilesDir(),activity.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES));
            List<PhotoLibraryCatalog.PhotoGroup> groups=libraryCatalog.list(candidates,()->closed||Thread.currentThread().isInterrupted());
            PhotoLibraryCatalog.PhotoGroup group=PhotoLibraryCatalog.groupFor(groups,saved);if(group!=null)libraryCatalog.selectDisplay(group,saved);
        }catch(Exception error){main.post(()->{if(!closed)host.onFeedback("照片已保存","默认版本暂未更新，可在版本菜单选择新效果。");});}
        main.post(()->{if(!closed)refreshDownloads();});});
    }

    private Button viewerButton(String label, Runnable action) {
        Button result = new Button(activity); result.setText(label); result.setAllCaps(false);
        result.setTextSize(14); result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        result.setIncludeFontPadding(false); result.setLetterSpacing(0);
        result.setMinWidth(dp(88)); result.setMinimumWidth(dp(88));
        result.setMinHeight(dp(48)); result.setMinimumHeight(dp(48));
        result.setPadding(dp(18), dp(10), dp(18), dp(10));
        result.setStateListAnimator(null); result.setElevation(0);
        result.setOnClickListener(v -> action.run());
        result.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {0xFFCBCDD1, 0xFF151619}));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] {-android.R.attr.state_enabled}, shape(0xFF383A3E, 22));
        states.addState(new int[] {}, new RippleDrawable(ColorStateList.valueOf(0x22000000), shape(0xFFF4F5F7, 22), shape(Color.WHITE, 22)));
        result.setBackground(states);
        return result;
    }

    private void chooseExportFormat() {
        if (showPhotoTaskBlocked("导出照片")) return;
        final DownloadedPhoto displayed = localDetail;
        final PhotoItem remote = remoteDetail;
        PhotoLibraryCatalog.PhotoGroup group = photoGroup(displayed);
        DownloadedPhoto original = group != null ? group.original : !isEffectPhoto(displayed) ? displayed : null;
        rawPanel.chooseFormat(remote, original, () -> {
            if (closed || screen != Screen.DETAIL || localDetail != displayed || remoteDetail != remote) return;
            if (viewerEditing && hasEditableDetail() && filterEditor != null && filterEditor.isReady()) filterEditor.showExport(); else exportCurrentJpeg();
        });
    }

    private void exportCurrentJpeg() {
        if (showPhotoTaskBlocked("保存照片")) return;
        PhotoLiveState.LivePhoto entry = currentLiveDetail();
        if (localDetail == null && entry != null && entry.saved == null && entry.error.isEmpty()
                && (controller.photoLiveState().enabled || controller.photoLiveState().receiving)) {
            host.onFeedback("原图正在自动接收", "将按本轮直播的保存位置保留照片，无需重复下载。"); return;
        }
        if (localDetail != null) {
            saveToGallery(localDetail);
        } else if (liveDetail && controller.photoLiveState().enabled) {
            if (requireCameraPhotos("重试接收照片")) controller.retryPhotoLive();
        } else if (remoteDetail != null) {
            List<PhotoItem> one = new ArrayList<>(); one.add(remoteDetail); startBatch(one);
        }
    }

    private void updateViewerChrome() {
        if (viewerTop != null) viewerTop.setVisibility(viewerControlsVisible ? View.VISIBLE : View.GONE);
        if (viewerBottom != null) viewerBottom.setVisibility(viewerControlsVisible ? View.VISIBLE : View.GONE);
        if (viewerCompareLayer != null) viewerCompareLayer.setVisibility(viewerControlsVisible ? View.VISIBLE : View.GONE);
        if (viewerTools != null) viewerTools.setVisibility(viewerControlsVisible ? View.VISIBLE : View.GONE);
    }

    private void updateViewerStatus() {
        if (screen == Screen.DETAIL && viewerEditing != hasEditableDetail()) { render(); return; }
        if (screen == Screen.DETAIL && viewerEditing && filterEditor != null) filterEditor.bind(localDetail);
        updateViewerStar();
        if (viewerStatus == null) return;
        if (localDetail != null && !localKey(localDetail).equals(viewerPublicationKey)) requestViewerPublication();
        PhotoLibraryCatalog.PhotoGroup group = photoGroup(localDetail);
        if (viewerVersions != null) {
            viewerVersions.setVisibility(localDetail != null && group != null ? View.VISIBLE : View.GONE);
            String versionLabel = "原图";
            if (group != null) for (PhotoLibraryCatalog.Version version : group.versions) if (version.photo.file().equals(localDetail.file())) versionLabel = version.label();
            viewerVersions.setText(versionLabel); viewerVersions.setContentDescription("照片版本，当前" + versionLabel + "，点击切换或删除效果");
        }
        long size = localDetail != null ? localDetail.byteSize() : remoteDetail != null ? remoteDetail.byteSize() : 0;
        viewerStatus.setText((isEffectPhoto(localDetail) ? "效果图 " : "原图 ") + formatBytes(size));
        if (viewerEditing && filterEditor != null) {viewerStatus.setText((detailIndex()+1)+" / "+detailCount());if(viewerPreset!=null){viewerPreset.setText(filterEditor.contextLabel());viewerPreset.setTooltipText(filterEditor.label());viewerPreset.setContentDescription(filterEditor.label());}}
        if (photoPresets != null && photoPresets.isActive()) { PhotoPresetBatchState work = photoPresets.state(); viewerStatus.setText(work.currentNumber + "/" + work.total + " · " + work.currentStage + " " + Math.round(work.progress*100) + "%"); }
        if (viewerSave != null) {
            PhotoLiveState.LivePhoto entry = currentLiveDetail();
            boolean autoSaving = localDetail == null && entry != null && entry.saved == null && entry.error.isEmpty()
                    && (controller.photoLiveState().enabled || controller.photoLiveState().receiving);
            String label = rawPanel.isBusy() ? "RAW 保存中…" : exporting || publishingDownload ? "保存中…" : downloading
                    ? expectedBytes > 0 ? "下载中 " + Math.min(100, receivedBytes * 100 / expectedBytes) + "%" : "下载中…"
                    : localDetail == null && !liveDetail ? "下载照片" : "导出";
            if (autoSaving) label = "自动接收中";
            else if (entry != null && entry.saved == null && !entry.error.isEmpty() && controller.photoLiveState().enabled) label = "重试";
            if (!label.contentEquals(viewerSave.getText())) viewerSave.setText(label);
            viewerSave.setContentDescription(downloading ? label : viewerEditing ? "导出当前照片与滤镜设置" : localDetail == null && !liveDetail ? "下载照片到手机" : "导出照片到手机");
            viewerSave.setEnabled(true);
        }
        requestViewerPublication();
    }

    public void refreshGalleryStatus() {
        viewerPublicationKey = ""; viewerPublication = null;
        if (screen == Screen.DETAIL) { updateViewerStatus(); }
    }

    private void requestViewerPublication() {
        if (closed || screen != Screen.DETAIL || localDetail == null) return;
        final DownloadedPhoto photo = localDetail; final String key = localKey(photo);
        if (key.equals(viewerPublicationKey)) return;
        viewerPublicationKey = key; viewerPublication = null;
        PhotoAlbumSaver.get(activity).queryStatus(photo, new AlbumController.Callback<PhotoLiveGallery.PublicationState>() {
            @Override public void onSuccess(PhotoLiveGallery.PublicationState state) {
                if (closed || screen != Screen.DETAIL || localDetail == null || !key.equals(localKey(localDetail)) || !key.equals(viewerPublicationKey)) return;
                viewerPublication = state; updateViewerStatus();
            }
            @Override public void onError(String error) {
                if (closed || localDetail == null || !key.equals(localKey(localDetail)) || !key.equals(viewerPublicationKey)) return;
                if (viewerSave != null) viewerSave.setText("保存到相册");
            }
        });
    }

    private void requestRemotePreview() {
        if (downloading) return;
        if (remoteDetail == null || localDetail != null || remotePreview != null || previewLoading || !previewError.isEmpty()) return;
        PhotoLiveState.LivePhoto live = currentLiveDetail();
        if (liveDetail && (live == null || !live.status.equals("preview") && !live.status.equals("receiving"))) return;
        previewLoading = true; previewReceived = previewTotal = 0;
        final int request = ++previewRequest; final PhotoItem photo = remoteDetail;
        AlbumController.PreviewCallback callback = new AlbumController.PreviewCallback() {
            public void onProgress(long received, long total) {
                if (closed || request != previewRequest || remoteDetail != photo) return;
                previewReceived = received; previewTotal = total; updateViewerStatus();
            }
            public void onSuccess(PhotoPreview preview) {
                if (preview == null) { onError("原图仍在自动接收"); return; }
                if (closed || request != previewRequest || screen != Screen.DETAIL || remoteDetail != photo) { preview.close(); return; }
                previewLoading = false; remotePreview = preview;
                requestDetailFile(preview.file(), "preview:" + remoteKey(photo)); updateViewerStatus();
            }
            public void onError(String message) {
                if (closed || request != previewRequest || remoteDetail != photo) return;
                previewLoading = false; previewError = "预览未加载：" + message; updateDetailPhoto(); updateViewerStatus();
            }
        };
        if (liveDetail) controller.loadCachedPreview(photo, callback);
        else controller.loadPreview(photo, callback);
    }

    private void startBatch(List<PhotoItem> chosen) {
        if (closed) return;
        if (chosen.isEmpty()) { host.onFeedback("请先选择照片", "选择至少一张相机照片后，再下载原图。"); return; }
        if (!requirePhotoProcessingIdle("下载照片") || !requireCameraPhotos("下载相机原图")) return;
        List<PhotoItem> snapshot = new ArrayList<>(chosen);
        PhotoSaveChoice.show(activity,"保存 "+snapshot.size()+" 张相机照片","sync",PhotoSaveTarget.APP_ONLY,false,(target,extra)->{batchSaveTarget=target;host.beforeSavePhotos(target,() -> beginDownloadBatch(snapshot));});
    }

    private void beginDownloadBatch(List<PhotoItem> chosen) {
        beginDownloadBatch(chosen, true);
    }

    private void beginDownloadBatch(List<PhotoItem> chosen, boolean captureRecovery) {
        if (closed) return;
        if (chosen.isEmpty()) { host.onFeedback("请先选择照片", "选择至少一张相机照片后，再下载原图。"); return; }
        if (!requirePhotoProcessingIdle("下载照片") || !requireCameraPhotos("下载相机原图")) return;
        if (captureRecovery) {
            try { controller.captureDownloadRecovery(chosen); }
            catch (IOException stale) { host.onFeedback("下载未开始", stale.getMessage()); return; }
            PhotoSaveChoice.remember(activity,"sync-recovery",batchSaveTarget);
        } else batchSaveTarget=PhotoSaveChoice.preferred(activity,"sync-recovery",PhotoSaveTarget.APP_ONLY);
        // Drop queued metadata and thumbnails before the original occupies the shared PTP channel.
        cancelCameraBrowse();
        controller.cancelPreview();
        previewRequest++; previewLoading = false;
        downloadRecoveryRequest++;
        batchRun++;
        pendingNextDetail = null;
        downloadQueue.clear(); downloadQueue.addAll(chosen);
        downloadErrors.clear();
        batchTotal = chosen.size(); batchCompleted = batchFailed = batchCancelled = batchUnconfirmed = 0;
        batchGallerySaved = batchGalleryFailed = batchPendingSaves = 0;lastSavedOriginal=null;
        publishingDownload = batchReadInFlight = false;
        downloading = true; cancelRemaining = false; batchMessage = "";
        selectionMode = false; clearSelection();
        changed();
        downloadNext();
    }

    private void downloadNext() {
        if (closed || !downloading) return;
        if (batchReadInFlight) return;
        if (cancelRemaining || downloadQueue.isEmpty() || !isConnected()) {
            publishingDownload = batchPendingSaves > 0;
            if (publishingDownload) updateTransfer(); else finishBatch();
            return;
        }
        // Bound disk work while overlapping one local publication with the next camera read.
        if (batchPendingSaves >= 2) { publishingDownload = true; updateTransfer(); return; }
        PhotoItem photo = downloadQueue.removeFirst();
        batchReadInFlight = true; publishingDownload = false;
        currentFilename = photo.filename(); receivedBytes = 0; expectedBytes = photo.byteSize();
        final int request = epoch, run = batchRun;
        render();
        // The controller verifies its completed-original index before reuse;
        // a UI handle cache alone is not completion evidence for recovery.
        controller.download(photo, new AlbumController.DownloadCallback() {
            @Override public void onProgress(long received, long total) {
                if (!currentDownload(request, run)) return;
                receivedBytes = received; expectedBytes = total; updateTransfer();
            }
            @Override public void onSuccess(DownloadedPhoto result) {
                if (!currentDownload(request, run)) return;
                batchReadInFlight = false;
                downloadedOriginalReady(photo, result, request, run);
            }
            @Override public void onError(String message) {
                if (!currentDownload(request, run)) return;
                batchReadInFlight = false;
                batchFailed++;
                downloadErrors.add(photo.filename() + "：" + message);
                downloadNext();
            }
        });
    }

    private boolean currentDownload(int request, int run) {
        return current(request) && downloading && run == batchRun;
    }

    private void downloadedOriginalReady(PhotoItem photo, DownloadedPhoto result, int request, int run) {
        bindStar(controller.stablePhotoIdentity(photo), result);
        batchCompleted++; savedThisSession.put(photo.handle(), result);lastSavedOriginal=result;
        if (remoteDetail != null && remoteDetail.handle() == photo.handle()) {
            localDetail = result; releasePreview(); previewLoading = false; previewError = "";
            detailImageKey = "";
        }
        receivedBytes = expectedBytes;
        if(batchSaveTarget==PhotoSaveTarget.APP_ONLY){downloadNext();return;}
        batchPendingSaves++;
        final PhotoSaveTarget frozenTarget=batchSaveTarget;
        host.beforeSavePhotos(frozenTarget,() -> {
            if (!currentDownload(request, run)) return;
            PhotoAlbumSaver.get(activity).save(result, frozenTarget, new AlbumController.Callback<String>() {
                @Override public void onSuccess(String uri) {
                    if (!currentDownload(request, run)) return;
                    batchGallerySaved++; batchPendingSaves--; downloadNext();
                }
                @Override public void onError(String error) {
                    if (!currentDownload(request, run)) return;
                    batchGalleryFailed++; batchPendingSaves--;
                    downloadErrors.add(photo.filename() + "：保存到相册未完成，原图已保留，可在“已下载”中点“保存到相册”重试。\n" + error);
                    downloadNext();
                }
            });
            downloadNext();
        });
    }

    private void finishInterruptedBatch() {
        // Explicit disconnect suppresses that callback; the final local save may already be running.
        batchUnconfirmed += Math.max(0, batchTotal - batchCompleted - batchFailed - downloadQueue.size());
        batchUnconfirmed += batchPendingSaves;
        batchReadInFlight = false; batchPendingSaves = 0;
        cancelRemaining = true;
        finishBatch();
    }

    private void finishBatch() {
        if (!downloading) return;
        batchRun++;
        batchCancelled += downloadQueue.size();
        downloadQueue.clear(); downloading = false; publishingDownload = batchReadInFlight = false;
        batchPendingSaves = 0;
        batchMessage = batchSaveTarget==PhotoSaveTarget.APP_ONLY?"已保存在 App 内 "+batchCompleted+" 张，手机相册未新增照片":"已保存到手机相册 " + batchGallerySaved + " 张";
        if (batchGalleryFailed > 0) batchMessage += "，" + batchGalleryFailed + " 张原图已保留、相册保存待重试";
        if (batchFailed > 0) batchMessage += "，失败 " + batchFailed + " 张";
        if (batchCancelled > 0) batchMessage += "，未下载 " + batchCancelled + " 张";
        if (batchUnconfirmed > 0) batchMessage += "，" + batchUnconfirmed + " 张结果待确认。请查看“已下载”确认当前照片。";
        else if (batchCompleted > 0) batchMessage += "。原图也可在“已下载”中离线查看。";
        else batchMessage += "。";
        currentFilename = "";
        changed();
        refreshDownloads();
        if(batchCompleted>0&&lastSavedOriginal!=null){final DownloadedPhoto recent=lastSavedOriginal;host.onActionFeedback("照片已保存",batchMessage,"去处理",()->host.onProcessPhoto(recent));}
        if (!downloadErrors.isEmpty()) {
            StringBuilder details = new StringBuilder(batchMessage);
            int count = Math.min(8, downloadErrors.size());
            for (int i = 0; i < count; i++) details.append("\n\n").append(downloadErrors.get(i));
            if (downloadErrors.size() > count) details.append("\n\n另有 ").append(downloadErrors.size() - count).append(" 张未完成。");
            host.onMessage("部分照片未完成", details.toString());
        }
    }

    private void renderDownloadRecovery(LinearLayout parent) {
        int count = controller.pendingDownloadRecoveryCount();
        if (downloading || count == 0 && !recoveringDownloads) return;
        LinearLayout region = vertical(); region.setPadding(dp(24), dp(6), dp(24), dp(6));
        TextView explanation = text("有 " + count + " 张照片待下载。重新连接原相机后，可核对并继续；已保存原图会跳过。", 13, palette.secondary, false);
        region.addView(explanation);
        Button resume = button(recoveringDownloads ? "正在核对照片…" : isConnected() ? "继续未完成下载" : "连接后继续下载", false, this::resumePendingDownloads);
        downloadRecoveryAction = resume;
        resume.setEnabled(!recoveringDownloads && !isTransferBusy() && !presetBusy());
        addSpaced(region, resume, 6);
        addSpaced(region, textAction("取消未完成下载", () -> { clearPendingDownloadRecovery(); changed(); }), 2);
        parent.addView(region);
    }

    private boolean hasDownloadRecoveryLayout() {
        return !downloading && (controller.pendingDownloadRecoveryCount() > 0 || recoveringDownloads);
    }

    private LinearLayout takeOverviewHeader() {
        LinearLayout content = vertical();
        while (root.getChildCount() > 0) {
            View child = root.getChildAt(0);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams((LinearLayout.LayoutParams) child.getLayoutParams());
            params.height = -2; params.weight = 0;
            root.removeViewAt(0); content.addView(child, params);
        }
        return content;
    }

    /** The photo grid keeps an independent bounded viewport below the paused header. */
    private void boundDownloadRecoveryHeader() {
        LinearLayout expectedRoot = root, content = takeOverviewHeader();
        View action = downloadRecoveryAction;
        ScrollView header = new ScrollView(activity); header.setFillViewport(false);
        header.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(64)));
        header.post(() -> {
            if (root != expectedRoot || header.getParent() != root) return;
            int available = Math.max(dp(64), root.getHeight() - dp(196));
            int natural = content.getMeasuredHeight();
            int height = Math.min(available, natural > 0 ? natural : available);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) header.getLayoutParams();
            if (params.height != height) { params.height = height; header.setLayoutParams(params); }
            header.post(() -> {
                if (root != expectedRoot || action == null || !action.isAttachedToWindow()) return;
                Rect bounds = new Rect(); action.getDrawingRect(bounds); content.offsetDescendantRectToMyCoords(action, bounds);
                header.scrollTo(0, Math.max(0, bounds.top - dp(6)));
            });
        });
    }

    private void resumePendingDownloads() {
        if (closed || !requirePhotoProcessingIdle("继续下载") || !requireCameraPhotos("继续未完成下载")) return;
        if (controller.pendingDownloadRecoveryCount() == 0) { changed(); return; }
        final int request = epoch, ticket = ++downloadRecoveryRequest;
        recoveringDownloads = true; changed();
        controller.rebindDownloadRecovery(new AlbumController.Callback<List<PhotoItem>>() {
            @Override public void onSuccess(List<PhotoItem> rebound) {
                if (!current(request) || ticket != downloadRecoveryRequest || !recoveringDownloads) return;
                recoveringDownloads = false; changed();
                host.beforeSavePhotos(PhotoSaveChoice.preferred(activity,"sync-recovery",PhotoSaveTarget.APP_ONLY),() -> {
                    if (current(request) && ticket == downloadRecoveryRequest && controller.pendingDownloadRecoveryCount() > 0) beginDownloadBatch(rebound, false);
                });
            }
            @Override public void onError(String error) {
                if (!current(request) || ticket != downloadRecoveryRequest || !recoveringDownloads) return;
                recoveringDownloads = false; changed();
                host.onFeedback("未继续下载", error);
            }
        });
    }

    private void renderTransfer(LinearLayout parent) {
        LinearLayout region = vertical();
        region.setPadding(dp(16), dp(12), dp(16), dp(12));
        region.setBackgroundColor(palette.container);
        transferLabel = text("", 15, palette.text, true);
        transferLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        transferFile = text(currentFilename, 13, palette.secondary, false);
        transferFile.setMaxLines(2); transferFile.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        transferBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        transferBar.setProgressTintList(ColorStateList.valueOf(palette.primary));
        transferBar.setMax(1000);
        transferBytes = text("", 12, palette.secondary, false);
        region.addView(transferLabel);
        addSpaced(region, transferFile, 4);
        addSpaced(region, transferBar, 8);
        addSpaced(region, transferBytes, 4);
        Button cancel = button(cancelRemaining ? "当前照片完成后停止" : "取消剩余下载", false, () -> {
            controller.clearDownloadRecovery();
            cancelRemaining = true;
            render();
        });
        cancel.setEnabled(!cancelRemaining);
        addSpaced(region, cancel, 4);
        parent.addView(region, new LinearLayout.LayoutParams(-1, -2));
        updateTransfer();
    }

    private void updateTransfer() {
        updateViewerStatus();
        if (transferLabel == null) return;
        int current = Math.min(batchTotal, batchCompleted + batchFailed + 1);
        transferLabel.setText(publishingDownload ? "正在保存到相册 · 剩余 " + batchPendingSaves + " 张"
                : "正在下载照片 " + current + " / " + batchTotal + " · 已完成 " + batchCompleted + " 张");
        transferFile.setText(currentFilename);
        transferBar.setIndeterminate(expectedBytes <= 0);
        if (expectedBytes > 0) transferBar.setProgress((int) Math.min(1000, receivedBytes * 1000 / expectedBytes));
        transferBytes.setText(formatBytes(receivedBytes) + (expectedBytes > 0 ? " / " + formatBytes(expectedBytes) : "") + (cancelRemaining ? " · 完成当前照片后停止" : " · 请保持连接"));
    }

    private void renderDownloadedDates() {
        int available = Math.max(1, activity.getResources().getConfiguration().screenWidthDp - 40);
        int columns = Math.max(3, Math.min(8, (available + 8) / 112));
        downloadDateList = new ListView(activity); downloadDateList.setDivider(null);
        downloadDateList.setPadding(dp(20), 0, dp(20), dp(selectionMode ? 12 : 100));
        downloadDateList.setClipToPadding(false); downloadDateList.setItemsCanFocus(true);
        downloadDateList.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        downloadDateList.setContentDescription("已下载照片，按日期分组");
        String day = currentDownloadDay();
        if (groupedDownloadCount != downloads.size() || !day.equals(downloadGroupDay)) {
            downloadGroups = PhotoDateGroups.group(downloads); groupedDownloadCount = downloads.size(); downloadGroupDay = day;
        }
        downloadDateAdapter = new DownloadDateAdapter(PhotoDateGroups.rows(downloadGroups, columns), columns);
        downloadDateList.setAdapter(downloadDateAdapter);
        root.addView(wrapAlbumContent(downloadDateList), new LinearLayout.LayoutParams(-1, 0, 1));
        if (downloadDateSavedState != null) downloadDateList.onRestoreInstanceState(downloadDateSavedState);
    }

    private static String currentDownloadDay() { return java.time.LocalDate.now() + "|" + java.util.TimeZone.getDefault().getID(); }

    private final class DownloadDateAdapter extends BaseAdapter {
        private final List<PhotoDateGroups.Row> rows;
        private final int columns;
        DownloadDateAdapter(List<PhotoDateGroups.Row> rows, int columns) { this.rows = rows; this.columns = columns; }
        @Override public int getCount() { return rows.size(); }
        @Override public PhotoDateGroups.Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) {
            PhotoDateGroups.Row row = getItem(position);
            return (row.header ? "day:" + row.group.dateKey : "photo:" + row.photos.get(0).file().getAbsolutePath()).hashCode();
        }
        @Override public boolean hasStableIds() { return true; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return getItem(position).header ? 0 : 1; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return false; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            PhotoDateGroups.Row row = getItem(position);
            if (row.header) {
                DateHeader header = recycled instanceof DateHeader ? (DateHeader) recycled : new DateHeader();
                header.label.setText(row.group.label); header.count.setText(row.group.photoCount + " 张");
                header.select.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
                header.select.setEnabled(true);
                boolean all = true;
                for (DownloadedPhoto photo : row.group.photos) if (!selectedLocal.contains(photo.file().getAbsolutePath())) { all = false; break; }
                final boolean selectedDay = all;
                header.select.setText(all ? "取消" : "全选");
                header.select.setContentDescription((all ? "取消选择" : "选择") + row.group.label + "的全部照片");
                header.select.setOnClickListener(v -> {
                    if (loadingDownloads) { host.onFeedback("正在读取照片", "读取完成后即可选择这一天的照片。"); return; }
                    if (showPhotoTaskBlocked("选择照片")) return;
                    for (DownloadedPhoto photo : row.group.photos) {
                        if (selectedDay) selectedLocal.remove(photo.file().getAbsolutePath());
                        else selectedLocal.add(photo.file().getAbsolutePath());
                    }
                    changed();
                });
                return header;
            }
            LinearLayout line = recycled instanceof LinearLayout ? (LinearLayout) recycled : horizontal();
            if (line.getChildCount() != columns) {
                line.removeAllViews();
                for (int i = 0; i < columns; i++) {
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, -2, 1);
                    if (i > 0) cell.leftMargin = dp(8);
                    line.addView(new ThumbTile(), cell);
                }
                line.setPadding(0, 0, 0, dp(8));
            }
            for (int i = 0; i < columns; i++) {
                ThumbTile tile = (ThumbTile) line.getChildAt(i);
                if (i >= row.photos.size()) { tile.setVisibility(View.INVISIBLE); tile.setOnClickListener(null); tile.setOnLongClickListener(null); continue; }
                tile.setVisibility(View.VISIBLE);
                DownloadedPhoto photo = row.photos.get(i); String key = localKey(photo); tile.key = key;
                tile.favorite.setVisibility(starred(photo) ? View.VISIBLE : View.GONE);
                boolean checked = selectedLocal.contains(photo.file().getAbsolutePath());
                tile.setContentDescription(photo.filename() + "，" + row.group.label + "，已下载" + (starred(photo) ? "，已标星" : "") + (checked ? "，已选择" : ""));
                tile.setSelected(checked); tile.check.setChecked(checked); tile.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
                updateVersionBadge(tile.versionBadge,photo);
                tile.saved.setVisibility(View.GONE); tile.image.setImageDrawable(null); tile.placeholder.setVisibility(View.VISIBLE);
                Bitmap bitmap = thumbs.get(key);
                if (bitmap != null) { tile.image.setImageBitmap(bitmap); tile.placeholder.setVisibility(View.GONE); }
                else requestLocalThumb(photo);
                tile.setBackground(interactive(palette.container, 14)); tile.setFocusable(true);
                tile.setOnClickListener(v -> { if (!loadingDownloads && !controller.isDeletingDownloadedPhotos()) { if (selectionMode) toggleLocalSelection(photo); else openLocal(photo); } });
                tile.setOnLongClickListener(v -> {
                    if (downloading || exporting || controller.isDeletingDownloadedPhotos()) return true;
                    selectionMode = true; selectedLocal.add(photo.file().getAbsolutePath()); changed(); return true;
                });
            }
            return line;
        }
    }

    private final class DateHeader extends LinearLayout {
        final TextView label, count;
        final Button select;
        DateHeader() {
            super(activity); setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL); setPadding(dp(4), dp(12), 0, dp(6));
            label = text("", 17, palette.text, true); label.setMaxLines(2);
            if (android.os.Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
            addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            count = text("", 12, palette.secondary, false); addView(count, new LinearLayout.LayoutParams(-2, -2));
            select = textAction("全选", () -> {}); addView(select, new LinearLayout.LayoutParams(-2, dp(48)));
        }
    }

    private final class PhotoAdapter extends BaseAdapter {
        private final boolean local;
        PhotoAdapter(boolean local) { this.local = local; }
        @Override public int getCount() { return local ? downloads.size() : photos.size(); }
        @Override public Object getItem(int position) { return local ? downloads.get(position) : photos.get(position); }
        @Override public long getItemId(int position) { return local ? downloads.get(position).file().getAbsolutePath().hashCode() : photos.get(position).handle(); }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            ThumbTile tile = recycled instanceof ThumbTile ? (ThumbTile) recycled : new ThumbTile();
            String key, name;
            if (local) { DownloadedPhoto photo = downloads.get(position); key = localKey(photo); name = photo.filename(); }
            else { PhotoItem photo = photos.get(position); key = remoteKey(photo); name = photo.filename(); }
            tile.key = key;
            boolean checked = local ? selectedLocal.contains(downloads.get(position).file().getAbsolutePath()) : selected.contains(photos.get(position).handle());
            boolean favorite = local ? starred(downloads.get(position)) : starred(photos.get(position));
            tile.favorite.setVisibility(favorite ? View.VISIBLE : View.GONE);
            tile.setContentDescription(name + (local ? "，已下载" : "") + (favorite ? "，已标星" : "") + (checked ? "，已选择" : ""));
            tile.setSelected(checked);
            tile.placeholder.setVisibility(View.VISIBLE);
            tile.placeholder.setText(failedThumbs.contains(key) ? "预览暂不可用" : "正在加载预览…");
            tile.image.setImageDrawable(null);
            Bitmap cached = thumbs.get(key);
            if (cached != null) { tile.image.setImageBitmap(cached); tile.placeholder.setVisibility(View.GONE); }
            else if (local) requestLocalThumb(downloads.get(position));
            else requestRemoteThumb(photos.get(position), null);
            tile.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            tile.check.setChecked(checked);
            updateVersionBadge(tile.versionBadge,local?downloads.get(position):savedThisSession.get(photos.get(position).handle()));tile.saved.setVisibility(local || (!local && savedThisSession.containsKey(photos.get(position).handle())) ? View.VISIBLE : View.GONE);
            return tile;
        }
    }

    private String photoDraftBadge(DownloadedPhoto photo){PhotoLibraryCatalog.PhotoGroup group=photoGroup(photo);return group!=null&&group.id.startsWith("photo-")?PhotoFilterEditor.draftBadge(activity,group.id.substring(6)):"";}
    private void updateVersionBadge(TextView badge,DownloadedPhoto photo){
        PhotoLibraryCatalog.PhotoGroup group=photoGroup(photo);String draft=photoDraftBadge(photo);
        badge.setText(!draft.isEmpty()?draft:group!=null?group.versions.size()+" 个版本":"");
        badge.setVisibility(!draft.isEmpty()||group!=null&&group.versions.size()>1?View.VISIBLE:View.GONE);
    }
    private final class ThumbTile extends FrameLayout {
        final TextView versionBadge = text("", 11, Color.WHITE, false);
        final ImageView image;
        final View saved;
        final TextView placeholder;
        final SelectionMark check;
        final ImageView favorite = favoriteBadge();
        String key;
        ThumbTile() {
            super(activity);
            setBackground(shape(palette.container, 14)); setClipToOutline(true);
            placeholder = text("暂无预览", 12, palette.secondary, false);
            placeholder.setGravity(Gravity.CENTER);
            addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(image, new FrameLayout.LayoutParams(-1, -1));
            check = new SelectionMark();
            check.setClickable(false); check.setFocusable(false);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams selection = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.END);
            addView(check, selection);
            saved = new PhoneMarker();
            FrameLayout.LayoutParams marker = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.BOTTOM | Gravity.END);
            marker.rightMargin = dp(6); marker.bottomMargin = dp(6);
            addView(saved, marker);
            versionBadge.setPadding(dp(6),dp(3),dp(6),dp(3)); versionBadge.setBackground(shape(0xB8202732,8)); versionBadge.setVisibility(View.GONE);
            FrameLayout.LayoutParams versionMarker = new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM | Gravity.END);
            versionMarker.rightMargin = versionMarker.bottomMargin = dp(6); addView(versionBadge,versionMarker);
            FrameLayout.LayoutParams star = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP | Gravity.START);
            star.leftMargin = star.topMargin = dp(6); addView(favorite, star);
            setMinimumHeight(dp(104));
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
        }
    }

    private ImageView favoriteBadge() {
        ImageView badge = icon(AppIcon.Kind.STAR_FILLED, 0xFFFFD369); badge.setPadding(dp(5), dp(5), dp(5), dp(5));
        badge.setBackground(shape(0xAA16191E, 14)); badge.setVisibility(View.GONE);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return badge;
    }

    private void requestRemoteThumb(PhotoItem photo, Runnable after) {
        final String key = remoteKey(photo);
        if (thumbs.get(key) != null) { if (after != null) after.run(); return; }
        if (closed || downloading || !isConnected() || pendingThumbs.size() >= MAX_THUMB_REQUESTS || pendingThumbs.contains(key) || failedThumbs.contains(key)) return;
        pendingThumbs.add(key);
        final int request = epoch;
        final int browseRequest = browseEpoch;
        controller.loadThumbnail(photo, new AlbumController.Callback<byte[]>() {
            @Override public void onSuccess(byte[] bytes) {
                if (!current(request) || browseRequest != browseEpoch) return;
                final int target = gridThumbnailTarget();
                decodeThumb(key, request, () -> decodeJpegThumbnail(bytes, target), after, browseRequest);
            }
            @Override public void onError(String message) {
                if (!current(request) || browseRequest != browseEpoch) return;
                pendingThumbs.remove(key); failedThumbs.add(key); notifyThumbnails();
            }
        });
    }

    private void requestLocalThumb(DownloadedPhoto photo) {
        String key = localKey(photo);
        if (closed || pendingThumbs.size() >= MAX_THUMB_REQUESTS || pendingThumbs.contains(key) || failedThumbs.contains(key)) return;
        pendingThumbs.add(key);
        final int request = epoch;
        final int target = gridThumbnailTarget();
        final PhotoLibraryCatalog.PhotoGroup group = photoGroup(photo);
        decodeThumb(key, request, () -> {
            if (group != null && group.original != null && group.id.startsWith("photo-")) try {
                Bitmap edited = PhotoFilterEditor.draftThumbnail(activity, group.original, group.id.substring(6), target);
                if (edited != null) return edited;
            } catch (Exception ignored) { }
            return decodeFile(photo.file(), target, target, true);
        }, null);
    }

    private interface Decode { Bitmap run(); }
    private void decodeThumb(String key, int request, Decode decode, Runnable after) {
        decodeThumb(key, request, decode, after, null);
    }
    private void decodeThumb(String key, int request, Decode decode, Runnable after, Integer browseRequest) {
        images.execute(() -> {
            Bitmap bitmap = null;
            try { bitmap = decode.run(); } catch (RuntimeException | OutOfMemoryError ignored) { }
            final Bitmap result = bitmap;
            main.post(() -> {
                if (!current(request) || browseRequest != null && browseRequest != browseEpoch) { if (result != null) result.recycle(); return; }
                pendingThumbs.remove(key);
                if (result == null) failedThumbs.add(key); else thumbs.put(key, result);
                notifyThumbnails();
                if (after != null && result != null) after.run();
            });
        });
    }

    private void notifyThumbnails() {
        if (adapter != null) adapter.notifyDataSetChanged();
        if (downloadDateAdapter != null) downloadDateAdapter.notifyDataSetChanged();
        if (liveAdapter != null) liveAdapter.notifyDataSetChanged();
        if (screen == Screen.DETAIL) {
            updateDetailPhoto(); updateViewerStatus(); updateDetailNavigation();
        }
        if (screen == Screen.OVERVIEW && !downloadedTab && !liveTab && grid != null
                && connection != null && connection.protocol() == CameraProtocol.FUJIFILM_X_T20) {
            final GridView currentGrid = grid; currentGrid.post(() -> maybeLoadMore(currentGrid));
        }
    }

    /** Capture the current grid geometry on the main thread before decoding in images. */
    private int gridThumbnailTarget() {
        android.util.DisplayMetrics display = activity.getResources().getDisplayMetrics();
        GridView visibleGrid = grid;
        int availableWidth = Math.max(1, display.widthPixels - dp(40));
        int columns = Math.max(1, (availableWidth + dp(8)) / Math.max(1, dp(104) + dp(8)));
        int tileWidth = Math.max(1, (availableWidth - (columns - 1) * dp(8)) / columns);
        int gridHeight = display.heightPixels;
        if (visibleGrid != null && visibleGrid.getWidth() > 0 && visibleGrid.getNumColumns() > 0) {
            columns = visibleGrid.getNumColumns();
            tileWidth = Math.max(1, visibleGrid.getColumnWidth());
            if (visibleGrid.getHeight() > 0) gridHeight = visibleGrid.getHeight();
        }
        // AUTO_FIT may show many more than three columns in landscape/tablet layouts.
        // Include partial rows and leave 2 MiB for the next cells arriving during scroll.
        long visibleCells = (long) columns * (Math.max(1, (gridHeight + tileWidth - 1) / tileWidth) + 1);
        int cacheTarget = (int) Math.sqrt((10L * 1024 * 1024) / Math.max(4L, visibleCells * 4));
        // A <=31 px rounding allowance avoids halving a 320 px image for a 308 px tile.
        return Math.max(1, Math.min(320, Math.min((tileWidth + 31) / 32 * 32, cacheTarget)));
    }

    private BitmapFactory.Options thumbnailOptions(byte[] bytes, int target) {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        return gridDecodeOptions(bounds.outWidth, bounds.outHeight, target);
    }

    private BitmapFactory.Options gridDecodeOptions(int width, int height, int target) {
        // Grid cells share a 12 MiB cache. Unlike the detail decoder, do not allow
        // twice the target: 15 visible 640 x 400 ARGB previews would evict each
        // other and notifyDataSetChanged would continuously fetch them again.
        int sample = 1;
        while (width / sample > target || height / sample > target) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return options;
    }

    private Bitmap decodeJpegThumbnail(byte[] bytes, int target) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, thumbnailOptions(bytes, target));
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            return orient(bitmap, new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL));
        } catch (java.io.IOException | RuntimeException ignored) { return bitmap; }
    }

    private Bitmap decodeFile(File file, int width, int height) {
        return decodeFile(file, width, height, false);
    }

    private Bitmap decodeFile(File file, int width, int height, boolean gridPreview) {
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = gridPreview ? gridDecodeOptions(bounds.outWidth, bounds.outHeight, Math.min(width, height))
                : sampledOptions(bounds.outWidth, bounds.outHeight, width, height);
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        try {
            return orient(bitmap, new ExifInterface(file.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL));
        } catch (java.io.IOException | RuntimeException ignored) { return bitmap; }
    }

    private Bitmap orient(Bitmap bitmap, int orientation) {
        if (bitmap == null) return null;
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
            default: return bitmap;
        }
        Bitmap adjusted = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
        if (adjusted != bitmap) bitmap.recycle();
        return adjusted;
    }

    private BitmapFactory.Options sampledOptions(int width, int height, int targetWidth, int targetHeight) {
        int sample = 1;
        while (width / sample > targetWidth * 2 || height / sample > targetHeight * 2 || (long) (width / sample) * (height / sample) > 4000000L) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return options;
    }

    private void requestDetailFile(File file, String key) {
        if (key.equals(detailImageKey)) return;
        detailImageKey = key;
        final int request = epoch, viewerRequest = previewRequest;
        final int target = Math.min(2048, Math.max(1280, activity.getResources().getDisplayMetrics().widthPixels));
        images.execute(() -> {
            Bitmap bitmap = null;
            try { bitmap = decodeFile(file, target, target); } catch (RuntimeException | OutOfMemoryError ignored) { }
            final Bitmap decoded = bitmap;
            main.post(() -> {
                if (!current(request) || viewerRequest != previewRequest || !key.equals(detailImageKey) || screen != Screen.DETAIL) { if (decoded != null) decoded.recycle(); return; }
                detailBitmap = decoded;
                if (decoded == null) previewError = "照片暂时无法解码，请返回后重试或另存原文件。";
                // Upgrade only the bitmap. Rebuilding the stage here would interrupt an active pinch.
                updateDetailPhoto(); updateViewerStatus();
            });
        });
    }

    private static boolean isEffectPhoto(DownloadedPhoto photo) {
        return photo != null && photo.file().getName().matches("effect-[0-9a-f]{64}__Effect\\.JPG");
    }

    /** Decode both already-oriented images before a press; touching never does disk or camera I/O. */
    private void requestPhotoComparison(DownloadedPhoto effect) {
        if (photoPresets == null || !isEffectPhoto(effect)) return;
        String key = localKey(effect);
        if (key.equals(comparisonImageKey)) return;
        comparisonImageKey = key; comparisonUnavailable = false;
        final int request = epoch, viewerRequest = previewRequest, compareRequest = ++comparisonRequest;
        final int target = Math.min(2048, Math.max(1280, activity.getResources().getDisplayMetrics().widthPixels));
        images.execute(() -> {
            Bitmap bitmap = null;
            try {
                DownloadedPhoto source = photoPresets.processingOriginalFor(effect, () -> compareRequest != comparisonRequest || Thread.currentThread().isInterrupted());
                if (source != null) bitmap = decodeFile(source.file(), target, target);
            } catch (Exception | OutOfMemoryError unavailable) { }
            final Bitmap decoded = bitmap;
            main.post(() -> {
                if (!current(request) || viewerRequest != previewRequest || compareRequest != comparisonRequest
                        || !key.equals(comparisonImageKey) || screen != Screen.DETAIL || localDetail == null || !key.equals(localKey(localDetail))) {
                    if (decoded != null) decoded.recycle(); return;
                }
                comparisonOriginal = decoded; comparisonUnavailable = decoded == null;
                if (viewerComparison != null) {
                    if (decoded != null) viewerComparison.setOriginal(decoded);
                    else viewerComparison.setUnavailable();
                }
            });
        });
    }

    private void clearPhotoComparison() {
        comparisonRequest++; comparisonImageKey = ""; comparisonUnavailable = false;
        if (viewerComparison != null) { viewerComparison.release(); viewerComparison.setOriginal(null); viewerComparison.setEffect(null); }
        viewerComparison = null; viewerCompareLayer = null;
        if (comparisonOriginal != null) { comparisonOriginal.recycle(); comparisonOriginal = null; }
    }

    private void releasePreview() {
        releasePreview(true);
    }

    private void releasePreview(boolean cancelController) {
        previewRequest++;
        if (cancelController) controller.cancelPreview();
        if (remotePreview != null) { remotePreview.close(); remotePreview = null; }
    }

    private void clearDetailImage() {
        if (filterEditor != null) filterEditor.unbind();
        clearPhotoComparison();
        releasePreview(); detailBitmap = null; detailImageKey = "";
        previewLoading = false; previewError = ""; previewReceived = previewTotal = 0;
        pendingNextDetail = null;
        if (viewerImage != null) { viewerImage.setOnSingleTap(null); viewerImage.setOnSwipe(null); viewerImage.setOnPageBoundary(null, false); viewerImage.setPhoto(null); }
        viewerImage = null; viewerPhoto = null; viewerStatus = viewerPreset = null; viewerTop = viewerBottom = null;
        viewerSave = null; viewerStar = null; viewerVersions = null;
        viewerPublicationKey = ""; viewerPublication = null;
        if (photoVersionsDialog != null) { photoVersionsDialog.dismiss(); photoVersionsDialog = null; }
    }

    private void saveToGallery(DownloadedPhoto photo) {
        if (closed) return;
        if (photo == null) { host.onFeedback("照片尚未就绪", "请等待原图接收完成后，再保存到手机相册。"); return; }
        if (showPhotoTaskBlocked("保存照片")) return;
        PhotoSaveChoice.show(activity,"保存照片","sync",PhotoSaveTarget.APP_ONLY,true,(target,extra)->saveChosenPhoto(photo,target,extra));
    }
    private void saveChosenPhoto(DownloadedPhoto photo,PhotoSaveTarget target,boolean extra){
        if(closed)return;
        host.beforeSavePhotos(target,() -> {
            if (closed || showPhotoTaskBlocked("保存照片")) return;
            exporting = true; pendingNextDetail = null;
            updateDetailNavigation(); updateViewerStatus();
            PhotoAlbumSaver.get(activity).save(photo, target, extra, new AlbumController.Callback<String>() {
                @Override public void onSuccess(String uri) {
                    if (closed) return;
                    exporting = false; refreshGalleryStatus(); updateDetailNavigation(); updateViewerStatus();
                    if (screen == Screen.OVERVIEW) changed();
                    if (canShowProjectDialog()) host.onFeedback(target==PhotoSaveTarget.APP_ONLY?"已保存在 App 内":"已保存到手机相册",target==PhotoSaveTarget.APP_ONLY?"手机相册未新增照片，可在 App 内继续处理。":"同一版本的有效相册副本已核对，原图保留。");
                }
                @Override public void onError(String error) {
                    if (closed) return;
                    exporting = false; updateDetailNavigation(); updateViewerStatus();
                    if (screen == Screen.OVERVIEW) changed();
                    if (canShowProjectDialog()) feedbackDetails("保存到相册未完成", "原图仍保留在本机，可稍后重试。", error);
                }
            });
        });
    }

    /** Compatibility for an old Activity result; saving no longer opens a document picker. */
    public void onActivityResult(int requestCode, int resultCode, Intent data) { }

    private void rememberGridPosition() {
        if (resetGridPosition) {
            gridSavedState = null;
            downloadDateSavedState = null;
            resetGridPosition = false;
            return;
        }
        if (downloadDateList != null && downloadDateList.getChildCount() > 0) {
            downloadDateSavedState = downloadDateList.onSaveInstanceState(); return;
        }
        if (grid == null || adapter == null || restoringGridPosition || grid.getChildCount() == 0) return;
        int count = adapter.getCount();
        int first = grid.getFirstVisiblePosition();
        int last = grid.getLastVisiblePosition();
        int selectedPosition = grid.getSelectedItemPosition();
        if (grid.getAdapter() != adapter || count == 0 || first < 0 || first >= count
                || last < first || last >= count || selectedPosition >= count) {
            gridSavedState = null;
            return;
        }
        gridSavedState = grid.onSaveInstanceState();
    }

    /** Release retained views before clearing or replacing the list they read. */
    private void retireGrid(boolean resetPosition) {
        if (resetPosition) {
            gridSavedState = null;
            resetGridPosition = true;
        } else rememberGridPosition();
        grid = null;
        adapter = null;
        downloadDateList = null; downloadDateAdapter = null;
        restoringGridPosition = false;
    }
    private boolean current(int request) { return !closed && request == epoch; }
    private static boolean validIpv4(String address) {
        if (!address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        for (String part : address.split("\\.")) if (Integer.parseInt(part) > 255) return false;
        return true;
    }
    private String remoteKey(PhotoItem photo) { return "camera:" + sessionNumber + ":" + photo.handle(); }
    private String localKey(DownloadedPhoto photo) { return "local:" + photo.file().getAbsolutePath() + ":" + photo.downloadedAt(); }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private LinearLayout vertical() { LinearLayout result = new LinearLayout(activity); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private LinearLayout horizontal() { LinearLayout result = new LinearLayout(activity); result.setOrientation(LinearLayout.HORIZONTAL); return result; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, -2, 1); }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView result = new TextView(activity); result.setText(value); result.setTextSize(size); result.setTextColor(color);
        result.setTypeface(null, bold ? Typeface.BOLD : Typeface.NORMAL); result.setLineSpacing(dp(2), 1);
        result.setIncludeFontPadding(false); result.setLetterSpacing(0);
        return result;
    }
    private Button button(String label, boolean primary, Runnable action) {
        Button result = new Button(activity); result.setText(label); result.setTextSize(15); result.setAllCaps(false);
        result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        result.setLetterSpacing(0); result.setIncludeFontPadding(false);
        result.setMinHeight(dp(50)); result.setMinimumHeight(dp(50));
        result.setPadding(dp(16), dp(12), dp(16), dp(12));
        result.setStateListAnimator(null); result.setElevation(0); result.setTranslationZ(0);
        result.setBackgroundTintList(null);
        result.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {palette.secondary, primary ? palette.onPrimary : palette.primary}));
        if (primary) result.setBackground(primaryButtonBackground());
        else {
            GradientDrawable outline = shape(palette.card, 25); outline.setStroke(dp(0.5f), palette.outline);
            result.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.dark ? 0x22FFFFFF : 0x15000000), outline, shape(palette.text, 25)));
        }
        result.setOnClickListener(v -> action.run());
        return result;
    }
    private Button textAction(String label, Runnable action) {
        Button result = button(label, false, action);
        result.setMinHeight(dp(48)); result.setMinimumHeight(dp(48));
        result.setBackground(interactive(0x00000000, 4));
        result.setPadding(dp(12), dp(8), dp(12), dp(8));
        return result;
    }
    private android.graphics.drawable.Drawable primaryButtonBackground() {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, shape(palette.container, 25));
        states.addState(new int[]{}, interactive(palette.primary, 25));
        return states;
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result;
    }
    private android.graphics.drawable.Drawable interactive(int color, int radius) {
        GradientDrawable content = shape(color, radius);
        GradientDrawable mask = shape(palette.text, radius);
        return new RippleDrawable(ColorStateList.valueOf(palette.dark ? 0x33FFFFFF : 0x22000000), content, mask);
    }
    private ImageView icon(AppIcon.Kind kind, int color) {
        ImageView result = new ImageView(activity);
        result.setImageDrawable(new AppIcon(kind, color, activity.getResources().getDisplayMetrics().density));
        result.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return result;
    }
    private EditText input(String value, String hint, int type) {
        EditText result = new EditText(activity);
        result.setText(value); result.setHint(hint); result.setTextSize(16);
        result.setTextColor(palette.text); result.setHintTextColor(palette.secondary);
        result.setInputType(type); result.setSingleLine(true); result.setMinHeight(dp(50));
        result.setPadding(dp(14), dp(12), dp(14), dp(12));
        result.setBackgroundTintList(null);
        GradientDrawable field = shape(palette.container, 14);
        field.setStroke(dp(0.5f), palette.rim);
        result.setBackground(field);
        return result;
    }
    private interface OnText { void changed(String value); }
    private TextWatcher watcher(OnText onText) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { onText.changed(value.toString()); }
            @Override public void afterTextChanged(Editable value) { }
        };
    }
    private ProgressBar spinner() {
        ProgressBar result = new ProgressBar(activity); result.setIndeterminateTintList(ColorStateList.valueOf(palette.primary));
        result.setContentDescription("正在读取"); return result;
    }
    private void addSpaced(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top);
        if (child instanceof ProgressBar && !(child == transferBar)) { params.width = dp(32); params.height = dp(32); params.gravity = Gravity.CENTER_HORIZONTAL; }
        parent.addView(child, params);
    }
    private void addNotice(LinearLayout parent, String message, boolean error) {
        TextView notice = text(message, 13, error ? palette.error : palette.text, false);
        notice.setPadding(dp(12), dp(10), dp(12), dp(10));
        notice.setBackground(shape(error ? palette.container : palette.primaryContainer, 16));
        notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(16), dp(10), dp(16), dp(4)); parent.addView(notice, params);
    }
    private void addSavedNotice(LinearLayout parent) {
        LinearLayout notice = horizontal(); notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.setPadding(dp(14), dp(12), dp(14), dp(12));
        notice.setBackground(shape(palette.card, 20));
        ImageView check = icon(AppIcon.Kind.CHECK, palette.primary);
        notice.addView(check, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout words = vertical();
        words.addView(text(downloads.size() + " 张照片已保存到手机", 15, palette.text, false));
        addSpaced(words, text("断开相机后仍可在应用内查看", 12, palette.secondary, false), 4);
        LinearLayout.LayoutParams wordsParams = new LinearLayout.LayoutParams(0, -2, 1); wordsParams.leftMargin = dp(12);
        notice.addView(words, wordsParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(20), dp(16), dp(20), dp(2)); parent.addView(notice, params);
    }
    private View detailStatus(boolean saved) {
        LinearLayout status = horizontal(); status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(18), dp(18), dp(18), dp(18));
        status.setBackground(shape(palette.container, 8));
        status.addView(icon(saved ? AppIcon.Kind.CHECK : AppIcon.Kind.CAMERA, palette.secondary), new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout words = vertical();
        words.addView(text(saved ? "已保存到手机" : "尚未保存到手机", 16, palette.text, true));
        addSpaced(words, text(saved ? "断开相机后仍可查看" : "可下载到手机后离线查看", 13, palette.secondary, false), 5);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.leftMargin = dp(16);
        status.addView(words, params);
        return status;
    }

    /** Geometry-only diagrams explain devices and steps; they do not imitate actual photos or connection state. */
    private abstract class Diagram extends View {
        final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        Diagram() { super(activity); setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void line(Canvas canvas, int color, float width, float... points) {
            ink.setColor(color); ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(width);
            ink.setStrokeCap(Paint.Cap.ROUND); ink.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path(); path.moveTo(points[0], points[1]);
            for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
            canvas.drawPath(path, ink);
        }
        void round(Canvas canvas, int color, float x, float y, float width, float height, float radius, boolean fill, float stroke) {
            ink.setColor(color); ink.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE); ink.setStrokeWidth(stroke);
            canvas.drawRoundRect(new RectF(x, y, x + width, y + height), radius, radius, ink);
        }
        void pictogram(Canvas canvas, AppIcon.Kind kind, int color, int left, int top, int size) {
            AppIcon icon = new AppIcon(kind, color, 1); icon.setBounds(left, top, left + size, top + size); icon.draw(canvas);
        }
    }
    private final class ConnectionDiagram extends Diagram {
        ConnectionDiagram() { super(); }
        @Override protected void onMeasure(int width, int height) { setMeasuredDimension(resolveSize(dp(224), width), resolveSize(dp(104), height)); }
        @Override protected void onDraw(Canvas canvas) {
            int save = canvas.save(); canvas.translate((getWidth() - Math.min(getWidth(), dp(224))) / 2f, 0);
            canvas.scale(Math.min(getWidth(), dp(224)) / 224f, getHeight() / 104f);
            pictogram(canvas, AppIcon.Kind.CAMERA, palette.secondary, 20, 22, 64);
            ink.setColor(palette.outline); ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(3); ink.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawArc(new RectF(99, 39, 119, 65), -65, 130, false, ink);
            canvas.drawArc(new RectF(94, 31, 132, 73), -60, 120, false, ink);
            canvas.drawArc(new RectF(89, 23, 145, 81), -55, 110, false, ink);
            round(canvas, palette.secondary, 166, 15, 42, 74, 7, false, 3);
            line(canvas, palette.outline, 2, 181, 21, 193, 21);
            line(canvas, palette.outline, 2, 183, 82, 191, 82);
            canvas.restoreToCount(save);
        }
    }
    private final class StepDiagram extends Diagram {
        final int step;
        StepDiagram(int step) { super(); this.step = step; }
        @Override protected void onDraw(Canvas canvas) {
            int save = canvas.save(); canvas.scale(getWidth() / 68f, getHeight() / 84f);
            if (step == 1) {
                round(canvas, palette.outline, 2, 17, 64, 52, 6, false, 2);
                round(canvas, palette.outline, 21, 11, 26, 8, 3, false, 2);
                round(canvas, palette.surface, 8, 24, 52, 37, 2, true, 0);
                line(canvas, palette.secondary, 2, 14, 32, 50, 32);
                round(canvas, palette.outline, 12, 38, 44, 9, 2, true, 0);
                line(canvas, palette.secondary, 2, 16, 42, 45, 42);
                line(canvas, palette.secondary, 2, 14, 53, 43, 53);
            } else {
                round(canvas, palette.secondary, 8, 2, 52, 80, 8, false, 2.4f);
                round(canvas, palette.surface, 12, 7, 44, 69, 4, true, 0);
                line(canvas, palette.outline, 2, 29, 7, 39, 7);
                if (step == 2) {
                    pictogram(canvas, AppIcon.Kind.WIFI, palette.secondary, 17, 24, 20);
                    line(canvas, palette.outline, 3, 40, 35, 49, 35);
                    line(canvas, palette.outline, 3, 19, 51, 48, 51);
                    line(canvas, palette.outline, 3, 19, 61, 43, 61);
                } else {
                    for (int row = 0; row < 2; row++) for (int column = 0; column < 2; column++) {
                        int x = 17 + column * 18, y = 26 + row * 20;
                        round(canvas, palette.container, x, y, 15, 17, 2, true, 0);
                        pictogram(canvas, AppIcon.Kind.ALBUM, palette.outline, x + 2, y + 3, 11);
                    }
                }
            }
            canvas.restoreToCount(save);
        }
    }
    private final class Chevron extends Diagram {
        @Override protected void onDraw(Canvas canvas) {
            int save = canvas.save(); canvas.scale(getWidth() / 20f, getHeight() / 20f);
            line(canvas, palette.secondary, 1.6f, 7, 4, 13, 10, 7, 16); canvas.restoreToCount(save);
        }
    }
    private final class SelectionMark extends Diagram {
        boolean checked;
        void setChecked(boolean value) { checked = value; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, radius = dp(10);
            ink.setStyle(Paint.Style.FILL); ink.setColor(checked ? palette.primary : 0x50000000); canvas.drawCircle(cx, cy, radius, ink);
            ink.setStyle(Paint.Style.STROKE); ink.setStrokeWidth(dp(1.5f)); ink.setColor(0xFFFFFFFF); canvas.drawCircle(cx, cy, radius, ink);
            if (checked) line(canvas, 0xFFFFFFFF, dp(1.8f), cx - dp(5), cy, cx - dp(1), cy + dp(4), cx + dp(6), cy - dp(4));
        }
    }
    private final class PhoneMarker extends Diagram {
        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            ink.setColor(palette.surface); ink.setStyle(Paint.Style.FILL); canvas.drawCircle(cx, cy, dp(13), ink);
            round(canvas, palette.text, cx - dp(4), cy - dp(7), dp(8), dp(14), dp(1), false, dp(1.5f));
            line(canvas, palette.text, dp(1), cx - dp(1), cy + dp(4.5f), cx + dp(1), cy + dp(4.5f));
        }
    }
    private void step(LinearLayout parent, String number, String title, String body) {
        if (!"1".equals(number)) {
            View divider = new View(activity);
            divider.setBackgroundColor(palette.rim);
            LinearLayout.LayoutParams rule = new LinearLayout.LayoutParams(-1, dp(0.5f));
            rule.leftMargin = dp(38);
            parent.addView(divider, rule);
        }
        LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(16), 0, dp(16));
        TextView marker = text(number, 13, palette.primary, true); marker.setGravity(Gravity.CENTER);
        marker.setBackground(shape(palette.primaryContainer, 14));
        LinearLayout.LayoutParams mark = new LinearLayout.LayoutParams(dp(28), dp(28)); mark.gravity = Gravity.TOP;
        row.addView(marker, mark);
        LinearLayout words = vertical(); words.addView(text(title, 15, palette.text, true));
        addSpaced(words, text(body, 13, palette.secondary, false), 6);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.leftMargin = dp(10);
        row.addView(words, params);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    private static String formatBytes(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", size / 1024d);
        return String.format(Locale.ROOT, "%.1f MB", size / (1024d * 1024d));
    }
}

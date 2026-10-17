package com.nikonpreset.usbprobe.ui;

import android.app.Activity;
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
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.media.ExifInterface;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.nikonpreset.usbprobe.album.AlbumController;
import com.nikonpreset.usbprobe.album.ConnectionInfo;
import com.nikonpreset.usbprobe.album.DownloadedPhoto;
import com.nikonpreset.usbprobe.album.PhotoItem;
import com.nikonpreset.usbprobe.album.PhotoPage;
import com.nikonpreset.usbprobe.album.PhotoPreview;
import com.nikonpreset.usbprobe.album.PtpIpClient;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.DateFormat;
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
        default void beforeConnect(Runnable connect) { connect.run(); }
    }

    public static final int REQUEST_EXPORT = 7312;
    private static final int PAGE_SIZE = 24;
    private static final int MAX_THUMB_REQUESTS = 6;
    private static final String EXPORT_PREFERENCES = "album-panel";
    private static final String PENDING_EXPORT = "pending-export-source";
    private enum Screen { OVERVIEW, GUIDE, DETAIL }

    private final Activity activity;
    private final Host host;
    private final AlbumController controller;
    private final boolean ownsController;
    private final Runnable connectionListener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final List<PhotoItem> photos = new ArrayList<>();
    private final List<DownloadedPhoto> downloads = new ArrayList<>();
    private final Set<Long> selected = new LinkedHashSet<>();
    private final Map<Long, DownloadedPhoto> savedThisSession = new HashMap<>();
    private final Set<String> pendingThumbs = new HashSet<>();
    private final Set<String> failedThumbs = new HashSet<>();
    private final ArrayDeque<PhotoItem> downloadQueue = new ArrayDeque<>();
    private final List<String> downloadErrors = new ArrayList<>();
    private final LruCache<String, Bitmap> thumbs = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private UiPalette palette;
    private LinearLayout root;
    private GridView grid;
    private PhotoAdapter adapter;
    private final AlbumPaging paging = new AlbumPaging();
    private TextView transferLabel, transferFile, transferBytes;
    private ProgressBar transferBar;
    private Screen screen = Screen.OVERVIEW;
    private ConnectionInfo connection;
    private boolean downloadedTab, connecting, loadingDownloads, localLoaded, selectionMode;
    private boolean ownsConnectionRequest;
    private long connectionIntent;
    private Runnable pendingConnectionStart;
    private boolean closed, downloading, cancelRemaining, exporting;
    private boolean addressManuallyEdited;
    private int epoch, sessionNumber, batchTotal, batchCompleted, batchFailed, batchCancelled, batchUnconfirmed;
    private long receivedBytes, expectedBytes;
    private String cameraError = "", localError = "", connectionError = "", batchMessage = "", currentFilename = "";
    private String cameraAddress = "", portText = String.valueOf(AlbumController.DEFAULT_PORT);
    private PhotoItem remoteDetail;
    private DownloadedPhoto localDetail;
    private Bitmap detailBitmap;
    private String detailImageKey = "";
    private PhotoPreview remotePreview;
    private PhotoImageView viewerImage;
    private Bitmap viewerPhoto;
    private View viewerTop, viewerBottom;
    private TextView viewerStatus;
    private TextView photoCountLabel, pageStatus;
    private boolean viewerControlsVisible = true, previewLoading;
    private int previewRequest;
    private long previewReceived, previewTotal;
    private String previewError = "";
    private File exportFile;
    private android.os.Parcelable gridSavedState;
    private boolean resetGridPosition;
    private boolean restoringGridPosition;
    private ScrollView guideScroll;
    private int guideScrollY;

    public AlbumPanel(Activity activity, Host host) {
        this(activity, host, new AlbumController(activity), true);
    }

    /** The application runtime owns a shared controller across Activity recreation. */
    public AlbumPanel(Activity activity, Host host, AlbumController controller) {
        this(activity, host, controller, false);
    }

    private AlbumPanel(Activity activity, Host host, AlbumController controller, boolean ownsController) {
        if (controller == null) throw new IllegalArgumentException("缺少相册连接管理器。");
        this.activity = activity;
        this.host = host;
        this.controller = controller;
        this.ownsController = ownsController;
        this.palette = new UiPalette(activity);
        exportFile = restoreExportFile();
        connectionListener = this::controllerStateChanged;
        controller.setConnectionListener(connectionListener);
        ConnectionInfo restored = controller.connectionInfo();
        if (restored != null) restoreConnection(restored);
        else connecting = controller.isConnecting();
        refreshDownloads();
    }

    private void controllerStateChanged() {
        if (closed) return;
        ConnectionInfo current = controller.connectionInfo();
        if (current != null) {
            // The initiating UI's callback already restores its own page. A new
            // UI can instead recover a connection started by a destroyed one.
            if (ownsConnectionRequest && connecting) return;
            if (connection != current) { restoreConnection(current); changed(); }
            return;
        }
        if (controller.isConnecting()) {
            if (connection != null) resetRemoteConnection(false);
            if (!connecting) { connecting = true; connectionError = ""; changed(); }
            return;
        }
        boolean hadConnection = connection != null;
        if (!hadConnection && !connecting) return;
        connection = null; connecting = false; ownsConnectionRequest = false;
        paging.reset(false);
        if (hadConnection) cameraError = "无线连接已断开。已下载照片仍可查看，请重新连接相机后浏览。";
        else connectionError = "相册连接未完成，请检查相机网络后重试。";
        // An in-flight file may already be saving locally. Its callback remains
        // authoritative for that photo; only stop the remaining batch queue.
        if (downloading) cancelRemaining = true;
        changed();
    }

    private void restoreConnection(ConnectionInfo restored) {
        retireGrid(true);
        epoch++;
        photos.clear(); selected.clear(); savedThisSession.clear();
        pendingThumbs.clear(); failedThumbs.clear(); selectionMode = false; loadingDownloads = false;
        clearDetailImage();
        if (screen == Screen.DETAIL && remoteDetail != null && localDetail == null) {
            screen = Screen.OVERVIEW; remoteDetail = null;
        }
        sessionNumber++;
        connection = restored; connecting = false; ownsConnectionRequest = false;
        cameraAddress = restored.host(); portText = Integer.toString(restored.port()); addressManuallyEdited = true;
        cameraError = connectionError = "";
        paging.reset(restored.totalHandles() > 0);
        if (downloading) finishInterruptedBatch();
        if (paging.hasMore()) loadNextPage();
        refreshDownloads();
    }

    public View createView() {
        palette = new UiPalette(activity);
        root = vertical();
        root.setBackgroundColor(palette.surface);
        render();
        return root;
    }

    public String title() {
        if (screen == Screen.GUIDE) return "Wi-Fi 连接";
        if (screen == Screen.DETAIL) return "照片详情";
        return selectionMode ? "已选择 " + selected.size() + " 张" : "相册";
    }

    public boolean canGoBack() { return screen != Screen.OVERVIEW || selectionMode; }
    public boolean isImmersive() { return screen == Screen.DETAIL; }
    public String navigationKey() {
        String detail = screen != Screen.DETAIL ? "" : remoteDetail != null ? Long.toString(remoteDetail.handle())
                : localDetail != null ? localDetail.file().getAbsolutePath() : "";
        return screen.name() + ":" + downloadedTab + ":" + selectionMode + ":" + detail;
    }

    public void goBack() {
        if (screen != Screen.OVERVIEW) {
            screen = Screen.OVERVIEW;
            remoteDetail = null;
            localDetail = null;
            clearDetailImage();
        } else if (selectionMode) { selectionMode = false; selected.clear(); }
        changed();
    }

    public boolean isConnected() { return !closed && !connecting && connection != null && controller.isConnected(); }
    public boolean isConnecting() { return !closed && (connecting || controller.isConnecting()); }
    public boolean isTransferBusy() { return !closed && (downloading || exporting); }
    public boolean isConnectionGuide() { return screen == Screen.GUIDE; }
    public String connectionModel() { return isConnected() && connection != null ? connection.model() : ""; }
    public String connectionHost() { return isConnected() && connection != null ? connection.host() : ""; }
    public int connectionPort() { return isConnected() && connection != null ? connection.port() : 0; }
    public String getConfiguredHost() { return cameraAddress.trim(); }
    public int getConfiguredPort() {
        try { int port = Integer.parseInt(portText.trim()); return port >= 1 && port <= 65535 ? port : AlbumController.DEFAULT_PORT; }
        catch (NumberFormatException error) { return AlbumController.DEFAULT_PORT; }
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
        else connection = null;
        connectionError = message == null || message.trim().isEmpty() ? "未能开始连接，请重试。" : message;
        changed();
    }

    /** Stops old photo work before the controller drains and hands off the live session. */
    public void transferForPictureControl(AlbumController.Callback<PtpIpClient> callback) {
        if (closed || !isConnected() || isTransferBusy()) {
            callback.onError("当前相册连接无法移交，请结束照片传输后重试。"); return;
        }
        retireGrid(!downloadedTab);
        epoch++;
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
        // The old grid may still be retained while Settings is visible. Retire it before
        // changing the adapter's backing list, so it cannot save an obsolete row index.
        retireGrid(!downloadedTab);
        epoch++;
        if (disconnect) controller.disconnect();
        connection = null;
        connecting = false;
        ownsConnectionRequest = false;
        paging.reset(false);
        loadingDownloads = false;
        photos.clear();
        selected.clear();
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
        epoch++;
        // A shared controller may still need to start a user-requested connection
        // after another owned session finishes closing. Only its UI is retired.
        if (ownsController) { connectionIntent++; pendingConnectionStart = null; }
        boolean detached = controller.clearConnectionListener(connectionListener);
        if (ownsController) controller.close();
        else if (detached) controller.cancelPreview();
        images.shutdownNow();
        main.removeCallbacksAndMessages(null);
        thumbs.evictAll();
        detailBitmap = null;
        releasePreview(false);
        if (viewerImage != null) { viewerImage.setOnSingleTap(null); viewerImage.setPhoto(null); }
        viewerImage = null; viewerPhoto = null;
        root = null;
    }

    private void render() {
        if (root == null || closed) return;
        rememberGridPosition();
        if (guideScroll != null) guideScrollY = guideScroll.getScrollY();
        guideScroll = null;
        grid = null;
        adapter = null;
        transferBar = null;
        transferLabel = transferFile = transferBytes = null;
        photoCountLabel = pageStatus = null;
        root.removeAllViews();
        if (screen == Screen.GUIDE) renderGuide();
        else if (screen == Screen.DETAIL) renderDetail();
        else renderOverview();
    }

    private void changed() {
        if (closed) return;
        rememberGridPosition();
        grid = null;
        adapter = null;
        host.onChanged();
    }

    private void renderOverview() {
        LinearLayout header = vertical();
        header.setPadding(dp(24), dp(2), dp(24), 0);
        header.addView(text("浏览预览，按需下载原图", 14, palette.secondary, false));
        LinearLayout tabs = horizontal();
        tabs.addView(tab("相机照片", !downloadedTab, () -> chooseTab(false)), weight());
        tabs.addView(tab("已下载", downloadedTab, () -> chooseTab(true)), weight());
        int tabWidth = activity.getResources().getConfiguration().fontScale > 1.2f ? 248 : 216;
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(dp(tabWidth), dp(48));
        tabsParams.topMargin = dp(14);
        header.addView(tabs, tabsParams);
        root.addView(header);

        if (!batchMessage.isEmpty()) addNotice(root, batchMessage, false);
        if (downloadedTab && !downloads.isEmpty()) addSavedNotice(root);
        String error = downloadedTab ? localError : cameraError;
        if (!error.isEmpty()) addNotice(root, error, true);

        LinearLayout heading = horizontal();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(24), dp(10), dp(14), dp(6));
        String caption = downloadedTab ? "已下载照片" : "相机照片";
        if (!downloadedTab && !photos.isEmpty()) caption += " · " + photos.size() + (paging.hasMore() ? "+" : " 张");
        photoCountLabel = text(caption, 15, palette.text, true);
        heading.addView(photoCountLabel, new LinearLayout.LayoutParams(0, -2, 1));
        if (downloadedTab) {
            Button refresh = textAction("刷新", () -> { resetGridPosition = true; refreshDownloads(); render(); });
            refresh.setEnabled(!loadingDownloads && !downloading);
            heading.addView(refresh);
        } else if (isConnected() && !photos.isEmpty()) {
            Button select = textAction(selectionMode ? "取消选择" : "选择", () -> {
                selectionMode = !selectionMode;
                selected.clear();
                changed();
            });
            select.setEnabled(!downloading);
            heading.addView(select);
        }
        int count = downloadedTab ? downloads.size() : photos.size();
        if (count > 0) root.addView(heading);
        if (count > 0) {
            grid = new GridView(activity);
            grid.setNumColumns(GridView.AUTO_FIT);
            grid.setColumnWidth(dp(104));
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setHorizontalSpacing(dp(8));
            grid.setVerticalSpacing(dp(8));
            grid.setPadding(dp(20), dp(4), dp(20), dp(100));
            grid.setClipToPadding(false);
            grid.setDrawSelectorOnTop(true);
            grid.setSelector(new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), null, null));
            adapter = new PhotoAdapter(downloadedTab);
            grid.setAdapter(adapter);
            grid.setOnItemClickListener((parent, view, position, id) -> {
                if (downloadedTab) openLocal(downloads.get(position));
                else if (selectionMode) toggleSelection(photos.get(position));
                else openRemote(photos.get(position));
            });
            grid.setOnItemLongClickListener((parent, view, position, id) -> {
                if (downloadedTab || downloading) return false;
                selectionMode = true;
                selected.add(photos.get(position).handle());
                changed();
                return true;
            });
            root.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
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
            boolean pending = downloadedTab ? loadingDownloads : (connecting || paging.isLoading());
            if (pending) {
                title = downloadedTab ? "正在读取已下载照片" : connecting ? "正在连接相机" : "正在查找 JPEG 照片";
                body = "请稍候";
            } else if (downloadedTab) {
                title = "还没有已下载照片";
                body = "从相机下载的原图会保存在这里，\n断开连接也能继续查看。";
            } else if (!isConnected()) {
                title = "连接相机，开始浏览";
                body = "在底部「设置」中连接相机 Wi-Fi，\n照片会显示在这里。";
            } else if (paging.hasMore()) {
                title = "当前范围未找到 JPEG";
                body = "正在继续查找存储卡中的 JPEG 照片。";
            } else {
                title = "没有找到 JPEG 照片";
                body = "请检查存储卡中的照片格式。本版暂不浏览 RAW 和视频。";
            }
            TextView emptyTitle = text(title, compactEmpty ? 18 : 19, palette.text, true);
            emptyTitle.setGravity(Gravity.CENTER);
            addSpaced(empty, emptyTitle, compactEmpty ? 0 : 16);
            TextView emptyBody = text(body, compactEmpty ? 13 : 14, palette.secondary, false);
            emptyBody.setGravity(Gravity.CENTER);
            addSpaced(empty, emptyBody, 8);
            if (pending) addSpaced(empty, spinner(), 16);
            ScrollView emptyScroll = new ScrollView(activity); emptyScroll.setFillViewport(true);
            emptyScroll.setPadding(0, 0, 0, dp(100));
            emptyScroll.setClipToPadding(false);
            emptyScroll.addView(empty, new ScrollView.LayoutParams(-1, -2));
            root.addView(emptyScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            if (!downloadedTab && photos.isEmpty()) root.post(() -> maybeLoadMore(null));
        }
        if (!downloadedTab && isConnected()) {
            if (paging.isPaused()) {
                Button retry = button("重试读取照片", false, () -> { paging.retry(); loadNextPage(); });
                retry.setEnabled(!downloading && !selectionMode); root.addView(retry);
            } else if (paging.isLoading() || paging.hasMore() || !photos.isEmpty()) {
                TextView status = text(paging.isLoading() ? "正在读取更多照片…" : paging.hasMore() ? "继续下滑浏览更多照片" : "已显示全部照片", 12, palette.secondary, false);
                status.setGravity(Gravity.START); status.setPadding(dp(24), dp(2), dp(24), dp(6)); root.addView(status, 1);
                pageStatus = status;
            }
        }
        if (downloading) renderTransfer(root);
        else if (selectionMode && !downloadedTab) {
            LinearLayout actions = horizontal();
            actions.setPadding(dp(20), dp(12), dp(20), dp(16));
            Button cancel = button("取消选择", false, () -> { selectionMode = false; selected.clear(); changed(); });
            actions.addView(cancel, weight());
            Button download = button("下载 " + selected.size() + " 张原图", true, () -> {
                List<PhotoItem> chosen = new ArrayList<>();
                for (PhotoItem item : photos) if (selected.contains(item.handle())) chosen.add(item);
                startBatch(chosen);
            });
            download.setEnabled(!selected.isEmpty() && isConnected());
            LinearLayout.LayoutParams primary = weight(); primary.leftMargin = dp(8);
            actions.addView(download, primary);
            root.addView(actions);
        }
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
        if (downloadedTab == local) return;
        downloadedTab = local;
        resetGridPosition = true;
        selectionMode = false;
        selected.clear();
        if (local && !localLoaded && !loadingDownloads) refreshDownloads();
        changed();
    }

    private void showGuide() {
        if (!addressManuallyEdited) cameraAddress = controller.suggestHost();
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
        TextView introduction = text("连接相机的 Wi-Fi，浏览照片与导入预设。", 14, palette.secondary, false);
        body.addView(introduction);
        addSpaced(body, text("连接准备", 12, palette.secondary, false), 24);
        LinearLayout preparation = vertical();
        preparation.setPadding(dp(18), dp(2), dp(18), dp(2));
        preparation.setBackground(shape(palette.card, 24));
        step(preparation, "1", "开启相机 Wi-Fi", "相机菜单：连接至智能设备 → Wi-Fi 连接 → 建立 Wi-Fi 连接");
        step(preparation, "2", "手机加入相机网络", "使用相机显示的名称与密码；提示无法上网时仍保持连接。");
        step(preparation, "3", "返回并连接相机", "关闭其他相机应用，保持相机 Wi-Fi 开启，再确认下方地址。");
        addSpaced(body, preparation, 8);
        Button systemWifi = textAction("打开系统 Wi-Fi 设置", () -> {
            try { activity.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
            catch (RuntimeException error) { host.onMessage("无法打开设置", "请从手机系统设置中连接相机 Wi-Fi。"); }
        });
        systemWifi.setEnabled(!connecting);
        addSpaced(body, systemWifi, 6);
        addSpaced(body, text("相机地址", 12, palette.secondary, false), 20);
        LinearLayout fields = vertical();
        fields.setPadding(dp(18), dp(16), dp(18), dp(18));
        fields.setBackground(shape(palette.card, 24));
        fields.addView(text("IP 地址", 13, palette.secondary, false));
        EditText address = input(cameraAddress, "填写相机的 IPv4 地址", InputType.TYPE_CLASS_PHONE);
        address.setContentDescription("相机 IPv4 地址");
        // input() initializes its text before this watcher is attached.
        address.addTextChangedListener(watcher(value -> {
            cameraAddress = value;
            addressManuallyEdited = true;
        }));
        address.setEnabled(!connecting);
        addSpaced(fields, address, 8);
        addSpaced(fields, text("端口", 13, palette.secondary, false), 16);
        EditText port = input(portText, "15740", InputType.TYPE_CLASS_NUMBER);
        port.setContentDescription("相机无线连接端口");
        port.addTextChangedListener(watcher(value -> portText = value));
        port.setEnabled(!connecting);
        addSpaced(fields, port, 8);
        addSpaced(body, fields, 8);
        addSpaced(body, text("地址建议来自当前 Wi-Fi 网关，可手动修改；它不是相机自动发现结果。", 12, palette.secondary, false), 10);
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
            addSpaced(body, textAction("取消连接", () -> { disconnect(); screen = Screen.GUIDE; changed(); }), 8);
        }
        TextView compatibility = text("标准无线读取测试版 · 机型兼容性仍待验证", 12, palette.secondary, false);
        compatibility.setGravity(Gravity.CENTER);
        addSpaced(body, compatibility, 16);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        guideScroll = scroll;
        scroll.post(() -> { if (guideScroll == scroll) scroll.scrollTo(0, guideScrollY); });
    }

    private void connect() {
        if (connecting || downloading || closed) return;
        if (!addressManuallyEdited) {
            cameraAddress = controller.suggestHost();
            if (cameraAddress.isEmpty()) {
                connectionError = "当前 Wi-Fi 没有可用的地址建议。请先连接相机 Wi-Fi，或手动填写相机 IP。";
                render();
                return;
            }
        }
        int port;
        try { port = Integer.parseInt(portText.trim()); }
        catch (NumberFormatException error) { connectionError = "请输入 1–65535 之间的端口。"; render(); return; }
        if (port < 1 || port > 65535) { connectionError = "请输入 1–65535 之间的端口。"; render(); return; }
        if (!validIpv4(cameraAddress.trim())) {
            connectionError = "请输入相机的 IPv4 地址，可在相机网络设置中确认。";
            render();
            return;
        }
        View focus = activity.getCurrentFocus();
        InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (keyboard != null && focus != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        retireGrid(true);
        epoch++;
        final int request = epoch;
        final long intent = ++connectionIntent;
        connecting = true;
        ownsConnectionRequest = true;
        loadingDownloads = false;
        connectionError = cameraError = "";
        photos.clear(); selected.clear(); savedThisSession.clear();
        pendingThumbs.clear(); failedThumbs.clear();
        paging.reset(false);
        changed();
        final String requestedHost = cameraAddress.trim();
        Runnable start = new Runnable() {
            @Override public void run() {
                if (connectionIntent != intent || pendingConnectionStart != this || (closed && ownsController)) return;
                pendingConnectionStart = null;
                controller.connect(requestedHost, port, new AlbumController.Callback<ConnectionInfo>() {
                    @Override public void onSuccess(ConnectionInfo result) {
                        if (!current(request)) return;
                        sessionNumber++;
                        connecting = false;
                        ownsConnectionRequest = false;
                        connection = result;
                        if (screen == Screen.GUIDE) {
                            screen = Screen.OVERVIEW;
                            downloadedTab = false;
                        }
                        paging.reset(result.totalHandles() > 0);
                        changed();
                        if (paging.hasMore()) loadNextPage();
                        refreshDownloads();
                    }
                    @Override public void onError(String message) {
                        if (!current(request)) return;
                        connecting = false;
                        ownsConnectionRequest = false;
                        connection = null;
                        connectionError = message;
                        changed();
                    }
                });
            }
        };
        pendingConnectionStart = start;
        host.beforeConnect(start);
    }

    private void maybeLoadMore(GridView expectedGrid) {
        if (closed || screen != Screen.OVERVIEW || downloadedTab || selectionMode || downloading || exporting
                || !isConnected() || paging.isLoading() || paging.isPaused() || !paging.hasMore()) return;
        if (expectedGrid != null) {
            if (grid != expectedGrid || grid.getChildCount() == 0 || grid.getLastVisiblePosition() < photos.size() - 1) return;
        } else if (!photos.isEmpty()) return;
        loadNextPage();
    }

    private void loadNextPage() {
        if (closed || downloading || selectionMode || !isConnected()) return;
        AlbumPaging.Request pageRequest = paging.begin();
        if (pageRequest == null) return;
        cameraError = "";
        final int request = epoch;
        if (screen == Screen.OVERVIEW && !downloadedTab && grid != null && pageStatus != null) pageStatus.setText("正在读取更多照片…");
        else render();
        controller.loadPage(pageRequest.offset(), PAGE_SIZE, new AlbumController.Callback<PhotoPage>() {
            @Override public void onSuccess(PhotoPage page) {
                if (!current(request)) return;
                AlbumPaging.Result result = paging.succeed(pageRequest, page.nextOffset(), page.hasMore());
                if (result == AlbumPaging.Result.STALE) return;
                if (result == AlbumPaging.Result.STALLED) cameraError = "相机未返回新的照片位置，已暂停读取。请重试或重新连接。";
                else photos.addAll(page.items());
                if (result != AlbumPaging.Result.STALLED && screen == Screen.OVERVIEW && !downloadedTab && grid != null && adapter != null) {
                    // Append into the current grid. Replacing it during a fast page response loses the scroll anchor.
                    View first = grid.getChildAt(0);
                    boolean restoreAnchor = !restoringGridPosition && first != null;
                    android.os.Parcelable anchor = restoreAnchor ? grid.onSaveInstanceState() : null;
                    adapter.notifyDataSetChanged(); if (anchor != null) grid.onRestoreInstanceState(anchor);
                    if (photoCountLabel != null) photoCountLabel.setText("相机照片 · " + photos.size() + (paging.hasMore() ? "+" : " 张"));
                    if (pageStatus != null) pageStatus.setText(paging.hasMore() ? "继续下滑浏览更多照片" : "已显示全部照片");
                    final GridView currentGrid = grid; grid.post(() -> maybeLoadMore(currentGrid));
                } else render();
            }
            @Override public void onError(String message) {
                if (!current(request)) return;
                if (!paging.fail(pageRequest)) return;
                cameraError = message;
                render();
            }
        });
    }

    private void refreshDownloads() {
        if (closed || loadingDownloads) return;
        loadingDownloads = true;
        final int request = epoch;
        controller.listDownloads(new AlbumController.Callback<List<DownloadedPhoto>>() {
            @Override public void onSuccess(List<DownloadedPhoto> result) {
                if (!current(request)) return;
                loadingDownloads = false;
                localLoaded = true;
                localError = "";
                // Save against the old download list while all visible rows still exist.
                if (adapter != null && adapter.local) retireGrid(false);
                downloads.clear(); downloads.addAll(result);
                if (downloadedTab && screen == Screen.OVERVIEW) render();
            }
            @Override public void onError(String message) {
                if (!current(request)) return;
                loadingDownloads = false;
                localError = message;
                if (downloadedTab && screen == Screen.OVERVIEW) render();
            }
        });
    }

    private void toggleSelection(PhotoItem photo) {
        if (downloading) return;
        if (!selected.remove(photo.handle())) selected.add(photo.handle());
        changed();
    }

    private void openRemote(PhotoItem photo) {
        clearDetailImage(); viewerControlsVisible = true;
        remoteDetail = photo;
        localDetail = savedThisSession.get(photo.handle());
        screen = Screen.DETAIL;
        changed();
    }

    private void openLocal(DownloadedPhoto photo) {
        clearDetailImage(); viewerControlsVisible = true;
        remoteDetail = null;
        localDetail = photo;
        screen = Screen.DETAIL;
        changed();
    }

    private void renderDetail() {
        if (remoteDetail == null && localDetail == null) { screen = Screen.OVERVIEW; renderOverview(); return; }
        root.setBackgroundColor(Color.BLACK);
        FrameLayout stage = new FrameLayout(activity); stage.setBackgroundColor(Color.BLACK);
        if (viewerImage == null) viewerImage = new PhotoImageView(activity);
        if (viewerImage.getParent() instanceof ViewGroup) ((ViewGroup) viewerImage.getParent()).removeView(viewerImage);
        viewerImage.setOnSingleTap(() -> { viewerControlsVisible = !viewerControlsVisible; updateViewerChrome(); });
        stage.addView(viewerImage, new FrameLayout.LayoutParams(-1, -1));
        Bitmap photo = detailBitmap;
        if (photo == null && remoteDetail != null) photo = thumbs.get(remoteKey(remoteDetail));
        if (viewerPhoto != photo) {
            boolean preserveView = viewerPhoto != null && photo != null;
            viewerPhoto = photo;
            if (preserveView) viewerImage.setPhotoPreservingTransform(photo);
            else viewerImage.setPhoto(photo);
        }
        if (photo == null) {
            TextView placeholder = text(previewError.isEmpty() ? "正在加载照片…" : "暂无预览", 15, Color.LTGRAY, false); placeholder.setGravity(Gravity.CENTER);
            placeholder.setClickable(false); placeholder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            stage.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        }
        String filename = localDetail != null ? localDetail.filename() : remoteDetail.filename();
        LinearLayout top = horizontal(); top.setGravity(Gravity.CENTER_VERTICAL); top.setBackgroundColor(0xB8000000);
        FrameLayout back = new FrameLayout(activity); back.setBackground(interactive(0x00000000, 24));
        ImageView backIcon = icon(AppIcon.Kind.BACK, Color.WHITE); back.addView(backIcon, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        back.setContentDescription("返回相册"); back.setFocusable(true); back.setOnClickListener(v -> goBack());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView name = text(filename, 15, Color.WHITE, false); name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1); nameParams.leftMargin = dp(8); top.addView(name, nameParams);
        stage.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP)); viewerTop = top;
        LinearLayout bottom = vertical();
        GradientDrawable toolbar = shape(0xD91C1D21, 26);
        toolbar.setStroke(dp(0.5f), 0x33FFFFFF);
        bottom.setBackground(toolbar);
        viewerStatus = text("", 13, 0xFFE1E3E6, false); viewerStatus.setGravity(Gravity.CENTER);
        viewerStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); bottom.addView(viewerStatus);
        if (localDetail != null) {
            Button save = viewerButton(exporting ? "正在另存…" : "另存到系统文件", () -> export(localDetail));
            save.setEnabled(!exporting); addSpaced(bottom, save, 12);
        } else {
            Button save = viewerButton(downloading ? "正在下载原图…" : "下载原图", () -> { List<PhotoItem> one = new ArrayList<>(); one.add(remoteDetail); startBatch(one); });
            save.setEnabled(!downloading && isConnected()); addSpaced(bottom, save, 12);
            if (!previewError.isEmpty() && isConnected() && !downloading) {
                Button retry = textAction("重新加载预览", () -> { clearDetailImage(); render(); });
                retry.setTextColor(Color.WHITE); addSpaced(bottom, retry, 4);
            }
        }
        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        bottomParams.setMargins(dp(16), 0, dp(16), dp(14));
        stage.addView(bottom, bottomParams); viewerBottom = bottom;
        stage.setOnApplyWindowInsetsListener((view, insets) -> {
            int left, right, upper, lower;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                left = safe.left; right = safe.right; upper = safe.top; lower = safe.bottom;
            } else { left = insets.getStableInsetLeft(); right = insets.getStableInsetRight(); upper = insets.getStableInsetTop(); lower = insets.getStableInsetBottom(); }
            top.setPadding(left + dp(8), upper + dp(8), right + dp(16), dp(8));
            bottom.setPadding(left + dp(16), dp(12), right + dp(16), lower + dp(16));
            return insets;
        });
        root.addView(stage, new LinearLayout.LayoutParams(-1, -1)); stage.requestApplyInsets();
        if (localDetail != null) requestDetailFile(localDetail.file(), localKey(localDetail));
        else requestRemotePreview();
        updateViewerStatus(); updateViewerChrome();
    }

    private Button viewerButton(String label, Runnable action) {
        Button result = button(label, true, action);
        result.setTextColor(Color.WHITE);
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[] {-android.R.attr.state_enabled}, shape(0xFF383A3E, 25));
        int filledBlue = palette.dark ? 0xFF0065DC : palette.primary;
        states.addState(new int[] {}, new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape(filledBlue, 25), shape(Color.WHITE, 25)));
        result.setBackground(states);
        return result;
    }

    private void updateViewerChrome() {
        if (viewerTop != null) viewerTop.setVisibility(viewerControlsVisible ? View.VISIBLE : View.GONE);
        if (viewerBottom != null) viewerBottom.setVisibility(viewerControlsVisible ? View.VISIBLE : View.GONE);
    }

    private void updateViewerStatus() {
        if (viewerStatus == null) return;
        String status;
        if (downloading) status = "正在下载原图 · " + formatBytes(receivedBytes) + (expectedBytes > 0 ? " / " + formatBytes(expectedBytes) : "");
        else if (!previewError.isEmpty()) status = previewError;
        else if (localDetail != null) status = "已保存到手机 · " + formatBytes(localDetail.byteSize());
        else if (previewLoading) status = "正在加载预览…";
        else if (remoteDetail != null) {
            Bitmap preview = detailBitmap != null ? detailBitmap : thumbs.get(remoteKey(remoteDetail));
            String quality = detailBitmap != null && remotePreview != null && remotePreview.isLargeThumbnail()
                    ? "高清预览" : "缩略预览";
            status = preview == null ? "暂无预览" : quality + " · " + preview.getWidth() + " × " + preview.getHeight();
            status += isConnected() ? "\n原图 " + formatBytes(remoteDetail.byteSize()) + " · 下载后可查看细节" : "\n连接已断开，重新连接后可下载原图";
        } else status = "正在加载照片…";
        viewerStatus.setText(status);
    }

    private void requestRemotePreview() {
        if (remoteDetail == null || localDetail != null || remotePreview != null || previewLoading || !previewError.isEmpty()) return;
        previewLoading = true; previewReceived = previewTotal = 0;
        final int request = ++previewRequest; final PhotoItem photo = remoteDetail;
        controller.loadPreview(photo, new AlbumController.PreviewCallback() {
            public void onProgress(long received, long total) {
                if (closed || request != previewRequest || remoteDetail != photo) return;
                previewReceived = received; previewTotal = total; updateViewerStatus();
            }
            public void onSuccess(PhotoPreview preview) {
                if (closed || request != previewRequest || screen != Screen.DETAIL || remoteDetail != photo) { preview.close(); return; }
                previewLoading = false; remotePreview = preview;
                requestDetailFile(preview.file(), "preview:" + remoteKey(photo)); updateViewerStatus();
            }
            public void onError(String message) {
                if (closed || request != previewRequest || remoteDetail != photo) return;
                previewLoading = false; previewError = "预览未加载：" + message; render();
            }
        });
    }

    private void startBatch(List<PhotoItem> chosen) {
        if (chosen.isEmpty() || downloading || !isConnected()) return;
        downloadQueue.clear(); downloadQueue.addAll(chosen);
        downloadErrors.clear();
        batchTotal = chosen.size(); batchCompleted = batchFailed = batchCancelled = batchUnconfirmed = 0;
        downloading = true; cancelRemaining = false; batchMessage = "";
        selectionMode = false; selected.clear();
        changed();
        downloadNext();
    }

    private void downloadNext() {
        if (closed || !downloading) return;
        if (cancelRemaining || downloadQueue.isEmpty() || !isConnected()) { finishBatch(); return; }
        PhotoItem photo = downloadQueue.removeFirst();
        currentFilename = photo.filename(); receivedBytes = 0; expectedBytes = photo.byteSize();
        final int request = epoch;
        render();
        controller.download(photo, new AlbumController.DownloadCallback() {
            @Override public void onProgress(long received, long total) {
                if (!current(request)) return;
                receivedBytes = received; expectedBytes = total; updateTransfer();
            }
            @Override public void onSuccess(DownloadedPhoto result) {
                if (!current(request) || !downloading) return;
                batchCompleted++;
                savedThisSession.put(photo.handle(), result);
                if (remoteDetail != null && remoteDetail.handle() == photo.handle()) {
                    localDetail = result; releasePreview(); previewLoading = false; previewError = "";
                    // Keep the visible preview while decoding the explicitly downloaded original.
                    detailImageKey = "";
                }
                downloadNext();
            }
            @Override public void onError(String message) {
                if (!current(request) || !downloading) return;
                batchFailed++;
                downloadErrors.add(photo.filename() + "：" + message);
                downloadNext();
            }
        });
    }

    private void finishInterruptedBatch() {
        // Explicit disconnect suppresses that callback; the final local save may already be running.
        batchUnconfirmed += Math.max(0, batchTotal - batchCompleted - batchFailed - downloadQueue.size());
        cancelRemaining = true;
        finishBatch();
    }

    private void finishBatch() {
        if (!downloading) return;
        batchCancelled += downloadQueue.size();
        downloadQueue.clear(); downloading = false;
        batchMessage = "已下载 " + batchCompleted + " 张";
        if (batchFailed > 0) batchMessage += "，失败 " + batchFailed + " 张";
        if (batchCancelled > 0) batchMessage += "，未下载 " + batchCancelled + " 张";
        if (batchUnconfirmed > 0) batchMessage += "，" + batchUnconfirmed + " 张结果待确认。请查看“已下载”确认当前照片。";
        else if (batchCompleted > 0) batchMessage += "。可在“已下载”中离线查看。";
        else batchMessage += "。";
        currentFilename = "";
        changed();
        refreshDownloads();
        if (!downloadErrors.isEmpty()) {
            StringBuilder details = new StringBuilder(batchMessage);
            int count = Math.min(8, downloadErrors.size());
            for (int i = 0; i < count; i++) details.append("\n\n").append(downloadErrors.get(i));
            if (downloadErrors.size() > count) details.append("\n\n另有 ").append(downloadErrors.size() - count).append(" 张未完成。");
            host.onMessage("部分照片未下载", details.toString());
        }
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
        transferLabel.setText("正在下载 " + current + " / " + batchTotal + " · 已完成 " + batchCompleted + " 张");
        transferFile.setText(currentFilename);
        transferBar.setIndeterminate(expectedBytes <= 0);
        if (expectedBytes > 0) transferBar.setProgress((int) Math.min(1000, receivedBytes * 1000 / expectedBytes));
        transferBytes.setText(formatBytes(receivedBytes) + (expectedBytes > 0 ? " / " + formatBytes(expectedBytes) : "") + (cancelRemaining ? " · 完成当前照片后停止" : " · 请保持连接"));
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
            tile.setContentDescription(name + (local ? "，已下载" : selected.contains(photos.get(position).handle()) ? "，已选择" : ""));
            tile.setSelected(!local && selected.contains(photos.get(position).handle()));
            tile.placeholder.setVisibility(View.VISIBLE);
            tile.image.setImageDrawable(null);
            Bitmap cached = thumbs.get(key);
            if (cached != null) { tile.image.setImageBitmap(cached); tile.placeholder.setVisibility(View.GONE); }
            else if (local) requestLocalThumb(downloads.get(position));
            else requestRemoteThumb(photos.get(position), null);
            tile.check.setVisibility(!local && selectionMode ? View.VISIBLE : View.GONE);
            tile.check.setChecked(!local && selected.contains(photos.get(position).handle()));
            tile.saved.setVisibility(local || (!local && savedThisSession.containsKey(photos.get(position).handle())) ? View.VISIBLE : View.GONE);
            return tile;
        }
    }

    private final class ThumbTile extends FrameLayout {
        final ImageView image;
        final View saved;
        final TextView placeholder;
        final SelectionMark check;
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
            setMinimumHeight(dp(104));
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
        }
    }

    private void requestRemoteThumb(PhotoItem photo, Runnable after) {
        final String key = remoteKey(photo);
        if (closed || downloading || !isConnected() || pendingThumbs.size() >= MAX_THUMB_REQUESTS || pendingThumbs.contains(key) || failedThumbs.contains(key)) return;
        pendingThumbs.add(key);
        final int request = epoch;
        controller.loadThumbnail(photo, new AlbumController.Callback<byte[]>() {
            @Override public void onSuccess(byte[] bytes) {
                if (!current(request)) return;
                final int target = gridThumbnailTarget();
                decodeThumb(key, request, () -> decodeJpegThumbnail(bytes, target), after);
            }
            @Override public void onError(String message) {
                if (!current(request)) return;
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
        decodeThumb(key, request, () -> decodeFile(photo.file(), target, target, true), null);
    }

    private interface Decode { Bitmap run(); }
    private void decodeThumb(String key, int request, Decode decode, Runnable after) {
        images.execute(() -> {
            Bitmap bitmap = null;
            try { bitmap = decode.run(); } catch (RuntimeException | OutOfMemoryError ignored) { }
            final Bitmap result = bitmap;
            main.post(() -> {
                if (!current(request)) { if (result != null) result.recycle(); return; }
                pendingThumbs.remove(key);
                if (result == null) failedThumbs.add(key); else thumbs.put(key, result);
                notifyThumbnails();
                if (after != null && result != null) after.run();
            });
        });
    }

    private void notifyThumbnails() {
        if (adapter != null) adapter.notifyDataSetChanged();
        if (screen == Screen.DETAIL && remoteDetail != null && localDetail == null) render();
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
                // The shell may have recreated the detail while decoding; render into its current views.
                render();
            });
        });
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
        releasePreview(); detailBitmap = null; detailImageKey = "";
        previewLoading = false; previewError = ""; previewReceived = previewTotal = 0;
        if (viewerImage != null) { viewerImage.setOnSingleTap(null); viewerImage.setPhoto(null); }
        viewerImage = null; viewerPhoto = null; viewerStatus = null; viewerTop = viewerBottom = null;
    }

    private void export(DownloadedPhoto photo) {
        if (photo == null || exporting || closed) return;
        exportFile = validatedExportFile(photo.file().getAbsolutePath());
        if (exportFile == null) { host.onMessage("无法另存照片", "原照片暂时无法读取，请刷新“已下载”后重试。"); return; }
        if (!activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE).edit()
                .putString(PENDING_EXPORT, exportFile.getAbsolutePath()).commit()) {
            exportFile = null;
            host.onMessage("无法准备另存", "保存准备未完成。原照片仍在“已下载”中，请重试。");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/jpeg").putExtra(Intent.EXTRA_TITLE, photo.filename());
        try { activity.startActivityForResult(intent, REQUEST_EXPORT); }
        catch (RuntimeException error) { clearPendingExport(); host.onMessage("无法另存照片", "手机未提供文件保存位置选择器。照片仍保存在“已下载”中。"); }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_EXPORT || closed) return;
        File source = exportFile == null ? restoreExportFile() : validatedExportFile(exportFile.getAbsolutePath());
        clearPendingExport();
        if (resultCode != Activity.RESULT_OK) return;
        if (data == null || data.getData() == null || source == null) {
            host.onMessage("另存未完成", "无法恢复原照片或保存位置。请返回“已下载”检查原照片，再重新选择另存。");
            return;
        }
        Uri target = data.getData();
        exporting = true;
        render();
        images.execute(() -> {
            String error = null;
            try (FileInputStream input = new FileInputStream(source); OutputStream output = activity.getContentResolver().openOutputStream(target, "w")) {
                if (output == null) throw new java.io.IOException("无法打开选择的位置。");
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) >= 0) { if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("另存已停止。"); output.write(buffer, 0, count); }
                output.flush();
            } catch (Exception problem) {
                error = problem.getMessage();
                if (error == null || error.trim().isEmpty()) error = "所选位置未能完成保存，请选择其他位置重试。";
            }
            final String failure = error;
            main.post(() -> {
                if (closed) return;
                exporting = false;
                render();
                host.onMessage(failure == null ? "已另存照片" : "另存未完成", failure == null ? "照片已保存到你选择的位置。" : "照片仍保存在“已下载”中。\n" + failure);
            });
        });
    }

    private File restoreExportFile() {
        String path = activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE).getString(PENDING_EXPORT, "");
        return validatedExportFile(path);
    }

    private File validatedExportFile(String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            File parent = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
            if (parent == null) parent = new File(activity.getFilesDir(), "pictures");
            File directory = new File(parent, "camera-album").getCanonicalFile();
            File candidate = new File(path).getCanonicalFile();
            String name = candidate.getName().toLowerCase(Locale.ROOT);
            return directory.equals(candidate.getParentFile()) && candidate.isFile() && (name.endsWith(".jpg") || name.endsWith(".jpeg")) ? candidate : null;
        } catch (java.io.IOException | RuntimeException ignored) { return null; }
    }

    private void clearPendingExport() {
        exportFile = null;
        activity.getSharedPreferences(EXPORT_PREFERENCES, Activity.MODE_PRIVATE).edit().remove(PENDING_EXPORT).apply();
    }

    private void rememberGridPosition() {
        if (resetGridPosition) {
            gridSavedState = null;
            resetGridPosition = false;
            return;
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

package cc.huevia.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import cc.huevia.album.DownloadedPhoto;
import cc.huevia.album.LocalPhotoRepository;
import cc.huevia.album.PhotoLibraryCatalog;
import cc.huevia.album.PhotoPresetBatch;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * THESIS: Choose local photographs, refine them, and publish only the chosen versions.
 * OWN-WORLD: Existing UiPalette cloud-white/graphite, native sp labels and Canvas line icons.
 * STORY: Browse phone or durable app photos, select, edit, then explicitly choose a destination.
 * FIRST VIEWPORT: Two sources, visible access/scope status, date groups and a lazy photo grid.
 * FORM: Approved two-source local library inside the incumbent Android application; code-led extension.
 * FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review,
 * the verdict, DESIGN.md, and every shipping raster carrying its provenance.
 */
public final class LocalAlbumPanel implements AutoCloseable {
    public interface Host {
        void changed();
        void open(DownloadedPhoto photo);
        void batch(List<DownloadedPhoto> photos);
        void feedback(String title, String message);
        default void addFilters() { }
    }
    public static final int REQUEST_PHONE_ACCESS = 7410, REQUEST_PICK_PHOTOS = 7411;
    private static final int PAGE_SIZE = 120;
    private final Activity activity;
    private final Host host;
    private final PhotoPresetBatch photoPresets;
    private final LocalPhotoRepository repository;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor images = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(24));
    private final LruCache<String, Bitmap> thumbnails = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final Set<String> pendingThumbs = new HashSet<>(), failedThumbs = new HashSet<>();
    private final List<Item> phonePhotos = new ArrayList<>(), appPhotos = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Parcelable[] positions = new Parcelable[2];
    private final List<Row> rows = new ArrayList<>();
    private final Runnable presetChanged = () -> main.post(this::refreshApp);
    private final Runnable thumbnailChanged = this::thumbnailsChanged;
    private UiPalette palette;
    private LinearLayout root, toolbar, accessActions, selectionBar;
    private Button phoneTab, appTab, permissionButton, batchButton, selectButton;
    private ImageView pickerFab, moreButton;
    private TextView scope, status, selectionCount;
    private ProgressBar progress;
    private ListView list;
    private FrameLayout body;
    private LinearLayout empty;
    private View emptyContainer;
    private TextView emptyTitle, emptyText;
    private PhotoAdapter adapter;
    private boolean phone, selecting, closed, active, loadingPhone, loadingApp, importing, phoneMore, phoneLoaded, appLoaded, compactLayout;
    private int phoneOffset, phoneRequest, appRequest, importRequest, imageEpoch, gridColumns = 3;
    private String phoneError = "", appError = "", workStatus = "";

    public LocalAlbumPanel(Activity activity, PhotoPresetBatch photoPresets, Host host) {
        this.activity = activity; this.photoPresets = photoPresets; this.host = host; repository = new LocalPhotoRepository(activity);
        if (photoPresets != null) photoPresets.addObserver(presetChanged);
    }
    public String title() { return selecting ? "已选 " + selected.size() + " 张" : "相册"; }
    public void saveState(Bundle state) {
        savePosition(); state.putBoolean("localAlbumPhone", phone);
        state.putParcelable("localAlbumAppPosition", positions[0]); state.putParcelable("localAlbumPhonePosition", positions[1]);
    }
    public void restoreState(Bundle state) {
        if (state == null) return; phone = state.getBoolean("localAlbumPhone", false);
        positions[0] = state.getParcelable("localAlbumAppPosition"); positions[1] = state.getParcelable("localAlbumPhonePosition");
    }
    public View createView() {
        boolean compact = compactHeight();
        if (root != null) {
            if (palette.dark == new UiPalette(activity).dark && compactLayout == compact) { rebuildRows(); return root; }
            savePosition(); root = null;
        }
        compactLayout = compact;
        palette = new UiPalette(activity);
        root = new LinearLayout(activity); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(palette.surface);
        LinearLayout tabs = new LinearLayout(activity); tabs.setGravity(Gravity.CENTER_VERTICAL); tabs.setPadding(dp(compactLayout ? 8 : 16), dp(compactLayout ? 0 : 4), dp(8), dp(compactLayout ? 0 : 4));
        appTab = button("App 内", () -> switchTab(false)); phoneTab = button("手机", () -> switchTab(true));
        tabs.addView(appTab, compactLayout ? new LinearLayout.LayoutParams(dp(88), -2) : new LinearLayout.LayoutParams(0, -2, 1));
        tabs.addView(phoneTab, compactLayout ? new LinearLayout.LayoutParams(dp(72), -2) : new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(tabs);
        scope = text("", 13, palette.secondary); scope.setMaxLines(1); scope.setEllipsize(TextUtils.TruncateAt.END);
        status = text("", 13, palette.secondary); status.setMaxLines(1); status.setEllipsize(TextUtils.TruncateAt.END);
        status.setGravity(Gravity.CENTER_VERTICAL); status.setPadding(dp(8), 0, dp(8), 0);
        progress = new ProgressBar(activity);
        if (compactLayout) {
            tabs.addView(scope, new LinearLayout.LayoutParams(0, -2, 1));
            tabs.addView(progress, new LinearLayout.LayoutParams(dp(20), dp(20)));
            tabs.addView(status, new LinearLayout.LayoutParams(-2, -2));
        }
        moreButton = new ImageView(activity); moreButton.setImageDrawable(new AppIcon(AppIcon.Kind.MORE, palette.primary, density())); moreButton.setPadding(dp(12), dp(12), dp(12), dp(12));
        moreButton.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), null, shape(palette.card, dp(12))));
        moreButton.setContentDescription("相册更多操作"); moreButton.setTag("local-album-more"); moreButton.setFocusable(true); moreButton.setOnClickListener(view -> showMoreActions());
        tabs.addView(moreButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        if (!compactLayout) {
            LinearLayout summary = new LinearLayout(activity); summary.setGravity(Gravity.CENTER_VERTICAL); summary.setPadding(dp(20), 0, dp(12), dp(4)); summary.setMinimumHeight(dp(32));
            summary.addView(scope, new LinearLayout.LayoutParams(0, -2, 1)); summary.addView(progress, new LinearLayout.LayoutParams(dp(20), dp(20))); summary.addView(status, new LinearLayout.LayoutParams(-2, -2)); root.addView(summary);
        }
        // Kept as a compatibility mount; visible actions live in the native overflow menu and FAB.
        toolbar = new LinearLayout(activity); toolbar.setGravity(Gravity.CENTER_VERTICAL); toolbar.setPadding(dp(12), 0, dp(12), 0);
        Button refresh = button("刷新", this::refresh); icon(refresh, AppIcon.Kind.REFRESH);
        selectButton = button("选择", () -> { selecting = !selecting; selected.clear(); updateSelection(); });
        Button picker = button("选择照片", this::pickPhotos); icon(picker, AppIcon.Kind.PLUS);
        toolbar.addView(refresh); toolbar.addView(selectButton); toolbar.addView(picker, new LinearLayout.LayoutParams(0, -2, 1)); toolbar.setVisibility(View.GONE); root.addView(toolbar);
        accessActions = new LinearLayout(activity); accessActions.setPadding(dp(12), 0, dp(12), dp(4)); accessActions.setGravity(Gravity.CENTER_VERTICAL);
        accessActions.setVisibility(View.GONE); root.addView(accessActions);
        body = new FrameLayout(activity); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        list = new ListView(activity); list.setDivider(null); list.setClipToPadding(false); list.setPadding(dp(compactLayout ? 8 : 12), 0, dp(compactLayout ? 80 : 12), dp(navReserve() + 8));
        list.setSelector(android.R.color.transparent); list.setCacheColorHint(palette.surface); adapter = new PhotoAdapter(); list.setAdapter(adapter); body.addView(list, new FrameLayout.LayoutParams(-1, -1));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int state) { if (state == SCROLL_STATE_IDLE) adapter.notifyDataSetChanged(); }
            public void onScroll(AbsListView view, int first, int count, int total) { if (phone && count > 0 && total > 0 && first + count >= total - 2 && phoneMore && !loadingPhone && phoneError.isEmpty()) loadPhone(false); }
        });
        empty = new LinearLayout(activity); empty.setOrientation(LinearLayout.VERTICAL); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(compactLayout ? 16 : 32), dp(compactLayout ? 8 : 24), dp(compactLayout ? 16 : 32), dp(compactLayout ? 8 : 24));
        ImageView emptyIcon = new ImageView(activity); emptyIcon.setImageDrawable(new AppIcon(AppIcon.Kind.ALBUM, palette.secondary, density())); empty.addView(emptyIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        if (compactLayout) emptyIcon.setVisibility(View.GONE);
        emptyTitle = text("", 20, palette.text); emptyTitle.setGravity(Gravity.CENTER); emptyTitle.setTypeface(null, Typeface.BOLD); emptyTitle.setPadding(0, dp(compactLayout ? 0 : 20), 0, dp(8)); empty.addView(emptyTitle);
        emptyText = text("", 15, palette.secondary); emptyText.setGravity(Gravity.CENTER); emptyText.setLineSpacing(dp(3), 1); empty.addView(emptyText);
        permissionButton = button("点击获取授权", this::requestAccess); permissionButton.setTag("local-album-permission");
        LinearLayout.LayoutParams permissionParams = new LinearLayout.LayoutParams(-2, -2); permissionParams.topMargin = dp(compactLayout ? 8 : 16); empty.addView(permissionButton, permissionParams);
        ScrollView guidance = new ScrollView(activity); guidance.setFillViewport(true); guidance.addView(empty, new ScrollView.LayoutParams(-1, -2)); emptyContainer = guidance;
        FrameLayout.LayoutParams guidanceParams = new FrameLayout.LayoutParams(-1, -1); guidanceParams.bottomMargin = dp(navReserve()); body.addView(guidance, guidanceParams);
        pickerFab = new ImageView(activity); pickerFab.setImageDrawable(new AppIcon(AppIcon.Kind.PLUS, palette.onPrimary, density())); pickerFab.setPadding(dp(16), dp(16), dp(16), dp(16));
        pickerFab.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), shape(palette.primary, dp(28)), null)); pickerFab.setElevation(dp(4));
        pickerFab.setContentDescription("选择照片"); pickerFab.setTag("local-album-picker"); pickerFab.setFocusable(true); pickerFab.setOnClickListener(view -> pickPhotos());
        FrameLayout.LayoutParams pickerParams = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.END); pickerParams.rightMargin = dp(12); pickerParams.bottomMargin = dp(navReserve() + 12); body.addView(pickerFab, pickerParams);
        selectionBar = new LinearLayout(activity); selectionBar.setGravity(Gravity.CENTER_VERTICAL); selectionBar.setPadding(dp(12), dp(8), dp(12), dp(8)); selectionBar.setBackgroundColor(palette.card);
        selectionCount = text("", 14, palette.text); selectionBar.addView(selectionCount, new LinearLayout.LayoutParams(0, -2, 1));
        Button cancel = button("取消", () -> { selecting = false; selected.clear(); updateSelection(); }); selectionBar.addView(cancel);
        batchButton = button("批量编辑", () -> prepareSelected(false)); selectionBar.addView(batchButton); root.addView(selectionBar);
        body.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (compactLayout && resolvedColumns() != gridColumns) { savePosition(); rebuildRows(); restorePosition(); }
        });
        rebuildRows(); restorePosition(); updateState(); refreshApp(); return root;
    }
    private int navReserve() { return 92 + Math.round(Math.max(0f, activity.getResources().getConfiguration().fontScale - 1.2f) * 20f); }
    private boolean compactHeight() {
        android.content.res.Configuration config = activity.getResources().getConfiguration();
        return config.screenHeightDp < 480 && config.screenWidthDp > config.screenHeightDp;
    }
    public void resume() {
        if (closed) return; active = true;
        // Permission/selection can change in Settings or after backgrounding; never trust an old snapshot.
        invalidatePhone(); refreshApp(); if (phone && repository.access() != LocalPhotoRepository.Access.NONE) loadPhone(true);
        updateState();
    }
    public void pause() { active = false; savePosition(); }
    public boolean canGoBack() { return selecting || importing; }
    public boolean goBack() {
        if (importing) { importRequest++; importing = false; workStatus = ""; updateState(); return true; }
        if (selecting) { selecting = false; selected.clear(); updateSelection(); return true; }
        return false;
    }
    public void refresh() {
        if (closed || importing) return;
        if (phone) { invalidatePhone(); if (repository.access() != LocalPhotoRepository.Access.NONE) loadPhone(true); else updateState(); }
        else refreshApp();
    }
    private void refreshApp() {
        if (closed || loadingApp) return; loadingApp = true; appError = ""; int request = ++appRequest; updateState();
        worker.execute(() -> {
            List<PhotoLibraryCatalog.PhotoGroup> groups = null; String error = "";
            try { groups = repository.appPhotos(() -> closed || request != appRequest); }
            catch (IOException | RuntimeException failure) { error = message(failure); }
            List<PhotoLibraryCatalog.PhotoGroup> result = groups; String failure = error;
            main.post(() -> {
                if (closed || request != appRequest) return; loadingApp = false; appLoaded = true; appError = failure;
                if (result != null) { appPhotos.clear(); for (PhotoLibraryCatalog.PhotoGroup group : result) appPhotos.add(Item.local(group)); }
                if (!phone) { preserveSelection(); savePosition(); rebuildRows(); restorePosition(); } updateState();
            });
        });
    }
    private void invalidatePhone() {
        phoneRequest++; loadingPhone = false; phonePhotos.clear(); phoneOffset = 0; phoneMore = false; phoneLoaded = false; phoneError = "";
        imageEpoch++; thumbnails.evictAll(); failedThumbs.clear();
        if (phone) { selected.clear(); rebuildRows(); }
    }
    private void loadPhone(boolean replace) {
        if (closed || loadingPhone || repository.access() == LocalPhotoRepository.Access.NONE) return;
        loadingPhone = true; phoneError = ""; int request = ++phoneRequest; int offset = replace ? 0 : phoneOffset; updateState();
        worker.execute(() -> {
            LocalPhotoRepository.Page page = null; String error = "";
            try { page = repository.phonePage(offset, PAGE_SIZE); } catch (IOException | RuntimeException failure) { error = message(failure); }
            LocalPhotoRepository.Page result = page; String failure = error;
            main.post(() -> {
                if (closed || request != phoneRequest) return; loadingPhone = false; phoneLoaded = true; phoneError = failure;
                if (result != null) {
                    if (replace) phonePhotos.clear(); Set<String> known = new HashSet<>(); for (Item item : phonePhotos) known.add(item.key);
                    for (LocalPhotoRepository.PhonePhoto photo : result.photos) if (known.add(photo.key)) phonePhotos.add(Item.phone(photo));
                    phoneOffset = result.nextOffset; phoneMore = result.hasMore;
                } else if (repository.access() == LocalPhotoRepository.Access.NONE) phonePhotos.clear();
                if (phone) { preserveSelection(); rebuildRows(); if (replace) restorePosition(); } updateState();
            });
        });
    }
    private void switchTab(boolean value) {
        if (phone == value || importing) return; savePosition(); phone = value; selecting = false; selected.clear();
        rebuildRows(); restorePosition(); updateState(); host.changed();
        if (phone && !phoneLoaded && repository.access() != LocalPhotoRepository.Access.NONE) loadPhone(true);
        if (!phone && !appLoaded) refreshApp();
    }
    private void requestAccess() {
        if (Build.VERSION.SDK_INT >= 34) activity.requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, REQUEST_PHONE_ACCESS);
        else if (Build.VERSION.SDK_INT >= 33) activity.requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_PHONE_ACCESS);
        else activity.requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_PHONE_ACCESS);
    }
    private void showMoreActions() {
        PopupMenu menu = new PopupMenu(activity, moreButton);
        menu.getMenu().add(0, 1, 0, "刷新").setEnabled(!importing);
        menu.getMenu().add(0, 2, 1, selecting ? "完成选择" : "选择").setEnabled(!importing && !current().isEmpty());
        if (phone && repository.access() == LocalPhotoRepository.Access.PARTIAL) menu.getMenu().add(0, 3, 2, "重新选择可访问照片").setEnabled(!importing);
        if (phone) menu.getMenu().add(0, 4, 3, "权限设置");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) refresh();
            else if (item.getItemId() == 2) { selecting = !selecting; selected.clear(); updateSelection(); }
            else if (item.getItemId() == 3) requestAccess();
            else if (item.getItemId() == 4) {
                try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName()))); }
                catch (ActivityNotFoundException unavailable) { host.feedback("无法打开设置", "请在系统设置中找到本应用，调整照片访问权限。"); }
            }
            return true;
        }); menu.show();
    }
    private void pickPhotos() {
        if (importing) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/jpeg", "image/png"}); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { activity.startActivityForResult(intent, REQUEST_PICK_PHOTOS); }
        catch (ActivityNotFoundException unavailable) { host.feedback("无法选择照片", "系统未提供照片文件选择器。请授权浏览手机照片后重试。"); }
    }
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQUEST_PHONE_ACCESS) return false;
        invalidatePhone(); if (repository.access() != LocalPhotoRepository.Access.NONE) loadPhone(true); else { phoneError = "未获得手机照片访问权限。仍可使用「选择照片」，App 内照片可继续编辑。"; updateState(); } return true;
    }
    public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK_PHOTOS) return false;
        if (resultCode != Activity.RESULT_OK || data == null) return true;
        LinkedHashSet<Uri> chosen = new LinkedHashSet<>(); if (data.getData() != null) chosen.add(data.getData());
        ClipData clips = data.getClipData(); if (clips != null) for (int i = 0; i < clips.getItemCount(); i++) chosen.add(clips.getItemAt(i).getUri());
        chosen.remove(null);
        if (chosen.size() > LocalPhotoRepository.MAX_BATCH) { host.feedback("最多选择 100 张", "本次未读取照片。请重新选择不超过 100 张 JPEG 或 PNG 照片。"); return true; }
        if (chosen.isEmpty()) return true;
        int offered = data.getFlags();
        if ((offered & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) for (Uri uri : chosen) {
            try { activity.getContentResolver().takePersistableUriPermission(uri, offered & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException | IllegalArgumentException unavailable) { /* Durable app copies, rather than this grant, are the editing boundary. */ }
        }
        List<Uri> uris = new ArrayList<>(chosen); prepareUris(uris); return true;
    }
    private void prepareUris(List<Uri> uris) {
        if (importing) return; beginImport(); int request = importRequest;
        worker.execute(() -> {
            List<Item> items = new ArrayList<>(); String error = "";
            try { for (Uri uri : uris) { if (cancelImport(request)) return; items.add(Item.phone(repository.describe(uri))); } }
            catch (IOException | RuntimeException failure) { error = message(failure); }
            List<Item> result = items; String failure = error;
            main.post(() -> {
                if (cancelImport(request)) return;
                if (!failure.isEmpty()) { finishImport(); host.feedback("未打开照片", failure); return; }
                boolean png = result.stream().anyMatch(item -> item.phone.png());
                if (png) { finishImport(); confirmPng(result, result.size() == 1); } else runImport(result, result.size() == 1, request);
            });
        });
    }
    private void clicked(Item item) {
        if (importing) return;
        if (selecting) { toggle(item); return; }
        if (item.photo != null) host.open(item.photo); else confirmPng(Collections.singletonList(item), true);
    }
    private void toggle(Item item) {
        if (!selected.remove(item.key)) {
            if (selected.size() >= LocalPhotoRepository.MAX_BATCH) { host.feedback("最多选择 100 张", "请先处理当前所选照片，再选择其他照片。"); return; }
            selected.add(item.key);
        }
        selecting = true; updateSelection();
    }
    private void prepareSelected(boolean single) {
        if (importing || selected.isEmpty()) return; List<Item> items = new ArrayList<>();
        for (Item item : current()) if (selected.contains(item.key)) items.add(item);
        confirmPng(items, single);
    }
    private void confirmPng(List<Item> items, boolean single) {
        if (items.stream().noneMatch(item -> item.phone != null && item.phone.png())) { beginImport(); runImport(items, single, importRequest); return; }
        new AlertDialog.Builder(activity).setTitle("以白底编辑 PNG")
                .setMessage("PNG 源文件会完整保留在 App 内。编辑使用白底 JPEG 工作副本，透明区域会变白。手机原图保持原样。")
                .setNegativeButton("取消", null).setPositiveButton("继续编辑", (dialog, which) -> { beginImport(); runImport(items, single, importRequest); }).show();
    }
    private void beginImport() { importing = true; importRequest++; workStatus = "正在准备照片…"; updateState(); host.changed(); }
    private boolean cancelImport(int request) { return closed || request != importRequest || Thread.currentThread().isInterrupted(); }
    private void runImport(List<Item> items, boolean single, int request) {
        worker.execute(() -> {
            List<DownloadedPhoto> ready = new ArrayList<>(); String error = ""; long total = 0;
            try {
                for (int i = 0; i < items.size(); i++) {
                    if (cancelImport(request)) return; Item item = items.get(i); final int index = i + 1;
                    main.post(() -> { if (!cancelImport(request)) { workStatus = "正在准备 " + index + " / " + items.size() + " 张"; updateState(); } });
                    if (item.phone != null && item.phone.bytes > 0 && total + item.phone.bytes > 1024L * 1024 * 1024) throw new IOException("一次处理的手机照片总量不能超过 1 GB。已完成的工作副本保留在 App 内。");
                    DownloadedPhoto photo = item.photo == null ? repository.importPhoto(item.phone, () -> cancelImport(request)) : item.photo;
                    if (item.phone != null) {
                        total += Math.max(photo.byteSize(), Math.max(0, item.phone.bytes));
                        if (total > 1024L * 1024 * 1024) throw new IOException("一次处理的手机照片总量不能超过 1 GB。已完成的工作副本保留在 App 内。");
                    }
                    // Same strict source boundary as the existing editor and batch renderer.
                    if (photoPresets != null) photoPresets.processingOriginalFor(photo, () -> cancelImport(request));
                    ready.add(photo);
                }
            } catch (IOException | RuntimeException failure) { error = message(failure); }
            catch (OutOfMemoryError memory) { error = "照片超出当前可用内存，请减少照片数量或选择较小的照片。"; }
            List<DownloadedPhoto> result = ready; String failure = error;
            main.post(() -> {
                if (cancelImport(request)) return; finishImport(); refreshApp();
                if (!failure.isEmpty()) { host.feedback("未开始编辑", failure + (result.isEmpty() ? "" : "\n已准备的 " + result.size() + " 张副本保留在 App 内。")); return; }
                selecting = false; selected.clear(); updateSelection();
                if (single && !result.isEmpty()) host.open(result.get(0)); else if (!result.isEmpty()) host.batch(Collections.unmodifiableList(result));
            });
        });
    }
    private void finishImport() { importing = false; workStatus = ""; updateState(); host.changed(); }
    private List<Item> current() { return phone ? phonePhotos : appPhotos; }
    private void preserveSelection() { Set<String> available = new HashSet<>(); for (Item item : current()) available.add(item.key); selected.retainAll(available); }
    private void savePosition() { if (list != null) positions[phone ? 1 : 0] = list.onSaveInstanceState(); }
    private void restorePosition() { Parcelable saved = positions[phone ? 1 : 0]; if (list != null && saved != null) list.onRestoreInstanceState(saved); }
    private void rebuildRows() {
        gridColumns = resolvedColumns();
        rows.clear(); String lastDate = ""; Row row = null;
        DateFormat format = DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault());
        for (Item item : current()) {
            String date = item.date <= 0 ? "日期未知" : format.format(new Date(item.date));
            if (!date.equals(lastDate)) { rows.add(Row.header(date)); lastDate = date; row = null; }
            if (row == null || row.items.size() == columns()) { row = new Row(); rows.add(row); } row.items.add(item);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }
    private int columns() { return gridColumns; }
    private int resolvedColumns() {
        if (!compactLayout) return activity.getResources().getConfiguration().screenWidthDp >= 600 ? 5 : 3;
        float scale = activity.getResources().getConfiguration().fontScale;
        int width = body != null && body.getWidth() > 0 ? Math.round(body.getWidth() / density()) : Math.min(720, activity.getResources().getConfiguration().screenWidthDp);
        int height = body != null && body.getHeight() > 0 ? Math.round(body.getHeight() / density()) : activity.getResources().getConfiguration().screenHeightDp - 140;
        int availableWidth = Math.max(48, width - 88);
        int edge = Math.max(48, Math.min(120, height - navReserve() - Math.round(48 * scale)));
        int maximum = Math.max(1, availableWidth / 54);
        return Math.min(maximum, Math.max(3, (availableWidth + edge + 5) / (edge + 6)));
    }
    private void updateSelection() { if (adapter != null) adapter.notifyDataSetChanged(); updateState(); host.changed(); }
    private void updateState() {
        if (root == null) return;
        LocalPhotoRepository.Access access = repository.access(); boolean loading = phone ? loadingPhone : loadingApp;
        phoneTab.setTextColor(phone ? palette.primary : palette.secondary); appTab.setTextColor(phone ? palette.secondary : palette.primary);
        phoneTab.setTypeface(null, phone ? Typeface.BOLD : Typeface.NORMAL); appTab.setTypeface(null, phone ? Typeface.NORMAL : Typeface.BOLD);
        scope.setText(phone ? (access == LocalPhotoRepository.Access.PARTIAL ? "已授权 JPEG / PNG · 按添加时间" : access == LocalPhotoRepository.Access.FULL ? "JPEG / PNG · 按添加时间" : "仅在授权或选择后读取") : "原图与效果版本 · 按照片日期");
        accessActions.setVisibility(View.GONE);
        permissionButton.setText(access == LocalPhotoRepository.Access.PARTIAL ? "选择更多照片" : "点击获取授权");
        permissionButton.setVisibility(phone && access != LocalPhotoRepository.Access.FULL ? View.VISIBLE : View.GONE);
        selectButton.setText(selecting ? "完成选择" : "选择"); selectButton.setEnabled(!current().isEmpty() && !importing);
        phoneTab.setEnabled(!importing); appTab.setEnabled(!importing); permissionButton.setEnabled(!importing);
        pickerFab.setVisibility(selecting || importing ? View.GONE : View.VISIBLE); pickerFab.setEnabled(!importing); moreButton.setEnabled(!importing);
        progress.setVisibility(loading || importing ? View.VISIBLE : View.GONE);
        String error = phone ? phoneError : appError;
        status.setText(importing ? workStatus : loading ? "读取中…" : phone && access == LocalPhotoRepository.Access.NONE ? "未授权" : !error.isEmpty() ? "读取失败" : current().size() + " 张");
        status.setTextColor(!error.isEmpty() && !importing ? palette.error : palette.secondary);
        boolean showEmpty = current().isEmpty(); emptyContainer.setVisibility(showEmpty ? View.VISIBLE : View.GONE); list.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
        emptyTitle.setText(loading ? "正在整理照片" : phone && access == LocalPhotoRepository.Access.NONE ? "选择你想编辑的照片" : !error.isEmpty() ? "照片暂时无法读取" : phone ? "没有可显示的照片" : "App 内还没有照片");
        emptyText.setText(loading ? "照片将在这里按日期排列。" : phone && access == LocalPhotoRepository.Access.NONE ? "授权后即可浏览手机照片。\n也可点击 + 只选择要编辑的照片。" : !error.isEmpty() ? error + "\n可在更多操作中刷新重试。" : phone ? "这里只显示已授权的 JPEG 和 PNG。\n可重新选择授权范围，或点击 + 选择照片。" : "点击 + 选择照片，或从同步接收开始。\n原图和编辑后的效果版本都会保留在这里。");
        selectionBar.setVisibility(selecting || importing ? View.VISIBLE : View.GONE); selectionCount.setText(importing ? "准备照片中" : "已选 " + selected.size() + " / 100");
        batchButton.setEnabled(!selected.isEmpty() && !importing);
    }
    private final class PhotoAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return position; }
        public int getViewTypeCount() { return 2; }
        public int getItemViewType(int position) { return rows.get(position).header != null ? 0 : 1; }
        public boolean areAllItemsEnabled() { return false; }
        public boolean isEnabled(int position) { return false; }
        public View getView(int position, View recycled, ViewGroup parent) {
            Row row = rows.get(position);
            if (row.header != null) {
                TextView label = recycled instanceof TextView ? (TextView) recycled : text("", 14, palette.text);
                label.setText(row.header); label.setTextSize(compactLayout ? 12 : 14); label.setTypeface(null, Typeface.BOLD); label.setPadding(dp(8), dp(compactLayout ? 4 : 20), dp(8), dp(compactLayout ? 4 : 10)); return label;
            }
            LinearLayout strip = recycled instanceof LinearLayout ? (LinearLayout) recycled : new LinearLayout(activity); strip.removeAllViews(); strip.setBaselineAligned(false);
            int count = columns();
            for (int column = 0; column < count; column++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.setMargins(dp(3), 0, dp(3), dp(8));
                if (column >= row.items.size()) { strip.addView(new View(activity), params); continue; }
                Item item = row.items.get(column); LinearLayout tile = new LinearLayout(activity); tile.setOrientation(LinearLayout.VERTICAL);
                FrameLayout frame = new FrameLayout(activity) { @Override protected void onMeasure(int width, int height) { super.onMeasure(width, View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(width), View.MeasureSpec.EXACTLY)); } };
                frame.setBackgroundColor(palette.container); ImageView image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.CENTER_CROP); frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
                String key = item.thumbKey(); image.setTag(key); Bitmap cached = thumbnails.get(key); if (cached != null) image.setImageBitmap(cached); else { image.setImageDrawable(new AppIcon(failedThumbs.contains(key) ? AppIcon.Kind.INFO : AppIcon.Kind.ALBUM, palette.secondary, density())); requestThumbnail(item, image); }
                if (selecting) {
                    TextView check = text(selected.contains(item.key) ? "已选" : "选择", 12, selected.contains(item.key) ? palette.onPrimary : palette.text); check.setGravity(Gravity.CENTER); check.setPadding(dp(6), dp(4), dp(6), dp(4)); check.setBackground(shape(selected.contains(item.key) ? palette.primary : palette.card, dp(8)));
                    FrameLayout.LayoutParams mark = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END); mark.setMargins(dp(4), dp(4), dp(4), 0); frame.addView(check, mark);
                }
                tile.addView(frame, new LinearLayout.LayoutParams(-1, -2));
                TextView filename = text(item.name(), 12, palette.text); filename.setMaxLines(1); filename.setEllipsize(TextUtils.TruncateAt.END); filename.setPadding(dp(2), dp(compactLayout ? 4 : 6), dp(2), dp(compactLayout ? 2 : 4)); tile.addView(filename);
                tile.setContentDescription(item.name() + (item.group == null ? "" : "，" + item.group.versions.size() + " 个版本") + (selected.contains(item.key) ? "，已选择" : ""));
                tile.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), null, shape(palette.card, 0)));
                tile.setOnClickListener(view -> clicked(item)); tile.setOnLongClickListener(view -> { if (!importing) toggle(item); return true; });
                if (item.group != null && item.group.versions.size() > 1) {
                    Button versions = button(item.group.versions.size() + " 个版本", () -> chooseVersion(item.group)); versions.setTextSize(12); versions.setPadding(dp(2), 0, dp(2), 0); tile.addView(versions);
                }
                strip.addView(tile, params);
            }
            return strip;
        }
    }
    private void chooseVersion(PhotoLibraryCatalog.PhotoGroup group) {
        if (selecting || importing) return; String[] labels = new String[group.versions.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = group.versions.get(i).label();
        new AlertDialog.Builder(activity).setTitle("选择照片版本").setItems(labels, (dialog, which) -> host.open(group.versions.get(which).photo)).setNegativeButton("取消", null).show();
    }
    private void requestThumbnail(Item item, ImageView view) {
        String key = item.thumbKey(); if (closed || pendingThumbs.contains(key) || failedThumbs.contains(key)) return;
        int epoch = imageEpoch; pendingThumbs.add(key);
        try {
            images.execute(() -> {
                Bitmap bitmap = null; try { bitmap = item.phone == null ? repository.thumbnail(item.photo, dp(180)) : repository.thumbnail(item.phone, dp(180)); }
                catch (IOException | RuntimeException | OutOfMemoryError ignored) { }
                Bitmap result = bitmap;
                main.post(() -> {
                    pendingThumbs.remove(key); if (closed || epoch != imageEpoch) { if (result != null) result.recycle(); return; }
                    if (result != null) { thumbnails.put(key, result); if (key.equals(view.getTag())) view.setImageBitmap(result); }
                    else { failedThumbs.add(key); if (key.equals(view.getTag())) view.setImageDrawable(new AppIcon(AppIcon.Kind.INFO, palette.secondary, density())); }
                    main.removeCallbacks(thumbnailChanged); main.postDelayed(thumbnailChanged, 45);
                });
            });
        } catch (RejectedExecutionException full) { pendingThumbs.remove(key); }
    }
    private void thumbnailsChanged() { if (!closed && adapter != null) adapter.notifyDataSetChanged(); }
    private static final class Item {
        String key; long date; DownloadedPhoto photo; LocalPhotoRepository.PhonePhoto phone; PhotoLibraryCatalog.PhotoGroup group;
        static Item local(PhotoLibraryCatalog.PhotoGroup group) { Item item = new Item(); item.group = group; item.photo = group.display; item.key = group.id; item.date = group.photoDate; return item; }
        static Item phone(LocalPhotoRepository.PhonePhoto photo) { Item item = new Item(); item.phone = photo; item.key = photo.key; item.date = photo.date; return item; }
        String name() { return phone == null ? photo.filename() : phone.filename; }
        String thumbKey() { return phone == null ? photo.file().getPath() + "|" + photo.byteSize() + "|" + photo.downloadedAt() : phone.thumbnailKey(); }
    }
    private static final class Row { String header; final List<Item> items = new ArrayList<>(); static Row header(String date) { Row row = new Row(); row.header = date; return row; } }
    private Button button(String label, Runnable action) {
        Button button = new Button(activity); button.setText(label); button.setAllCaps(false); button.setTextSize(14); button.setTextColor(palette.primary);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setMinWidth(dp(48)); button.setMinimumWidth(dp(48)); button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), null, shape(palette.card, dp(10))));
        button.setOnClickListener(view -> action.run()); return button;
    }
    private void icon(Button button, AppIcon.Kind kind) { AppIcon icon = new AppIcon(kind, palette.primary, density()); icon.setBounds(0, 0, dp(18), dp(18)); button.setCompoundDrawables(icon, null, null, null); button.setCompoundDrawablePadding(dp(6)); }
    private TextView text(String value, int size, int color) { TextView text = new TextView(activity); text.setText(value); text.setTextSize(size); text.setTextColor(color); return text; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(radius); return drawable; }
    private float density() { return activity.getResources().getDisplayMetrics().density; }
    private int dp(float value) { return Math.round(value * density()); }
    private static String message(Throwable error) { String text = error.getMessage(); return text == null || text.isBlank() ? "无法读取照片，请刷新或重新选择后重试。" : text; }
    @Override public void close() {
        if (closed) return; closed = true; phoneRequest++; appRequest++; importRequest++; imageEpoch++;
        if (photoPresets != null) photoPresets.removeObserver(presetChanged);
        worker.shutdownNow(); images.shutdownNow(); thumbnails.evictAll(); pendingThumbs.clear(); failedThumbs.clear();
        main.removeCallbacks(thumbnailChanged);
    }
}

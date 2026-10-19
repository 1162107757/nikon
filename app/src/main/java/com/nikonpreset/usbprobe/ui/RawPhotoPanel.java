package com.nikonpreset.usbprobe.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Parcelable;
import android.text.TextUtils;
import android.view.ContextThemeWrapper;
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
import android.widget.TextView;
import com.nikonpreset.usbprobe.album.AlbumController;
import com.nikonpreset.usbprobe.album.DownloadedPhoto;
import com.nikonpreset.usbprobe.album.PhotoItem;
import com.nikonpreset.usbprobe.album.PhotoLiveState;
import com.nikonpreset.usbprobe.album.PhotoPage;
import com.nikonpreset.usbprobe.album.RawPhotoDownload;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Explicit NEF export and file management. It never decodes RAW or changes the shared camera connection. */
public final class RawPhotoPanel implements AutoCloseable {
    public interface Host {
        void onChanged();
        void onFeedback(String title, String text);
        void onConnectionRequired(String operation);
        void beforeSavePhotos(Runnable save);
        boolean isOtherPhotoWorkBusy();
        /** Runtime foreground-service ownership for the complete download -> system-gallery chain. */
        default AutoCloseable beginPhotoWork() { return () -> { }; }
    }

    private static final int PAGE_SIZE = 30;
    private final Activity activity;
    private final AlbumController controller;
    private final Host host;
    private final List<PhotoItem> cameraFiles = new ArrayList<>();
    private final List<RawPhotoDownload> savedFiles = new ArrayList<>();
    private final Set<String> published = new LinkedHashSet<>();
    private boolean open, local, closed, loading, loaded, hasMore;
    private int pageEpoch, nextOffset;
    private String pageError = "";
    private UiPalette palette;
    private LinearLayout root, taskRegion;
    private ListView list;
    private FileAdapter adapter;
    private FrameLayout listRegion;
    private LinearLayout emptyRegion;
    private TextView emptyTitle, emptyBody, footerText, pageCount;
    private Button cameraTab, savedTab, emptyAction, footerAction;
    private ProgressBar footerSpinner;
    private ProgressViews pageProgress, dialogProgress;
    private AlertDialog formatDialog, fileDialog, progressDialog;
    private Operation operation;
    private Batch batch;
    private AutoCloseable photoWorkLease;

    private static final class Operation {
        final PhotoItem remote;
        final DownloadedPhoto original;
        String filename;
        RawPhotoDownload saved;
        Batch batch;
        boolean busy = true, complete;
        long received, total;
        double progress;
        String stage = "准备 RAW 原文件", error = "";
        Operation(PhotoItem remote, DownloadedPhoto original, RawPhotoDownload saved) {
            this.remote = remote; this.original = original; this.saved = saved;
            filename = saved != null ? saved.filename() : remote != null ? remote.filename() : original != null ? original.filename() : "RAW 原文件";
        }
    }
    private static final class Batch {
        final List<Operation> inputs;
        final List<Operation> failedInputs = new ArrayList<>();
        int index, completed, failed;
        boolean active = true, stop;
        String reason = "";
        Batch(List<Operation> inputs) { this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs)); for (Operation input : inputs) input.batch = this; }
    }
    private static final class ProgressViews {
        TextView filename, stage, percent;
        ProgressBar bar;
        Button action;
    }

    public RawPhotoPanel(Activity activity, AlbumController controller, Host host) {
        if (activity == null || controller == null || host == null) throw new IllegalArgumentException("Missing RAW host");
        this.activity = activity; this.controller = controller; this.host = host;
    }

    public boolean isOpen() { return open; }
    /** Includes local gallery publication, which does not hold the controller's remote-read reservation. */
    public boolean isBusy() { return batch != null && batch.active || operation != null && operation.busy; }
    public String title() { return "RAW 文件"; }

    public void open(boolean local) {
        if (closed) return;
        dismissFormat(); dismissFile();
        open = true; this.local = local; pageEpoch++; loaded = false; loading = false; pageError = "";
        cameraFiles.clear(); savedFiles.clear(); nextOffset = 0; hasMore = !local;
        clearPage(); host.onChanged(); loadPage(true);
    }

    public boolean goBack() {
        if (formatDialog != null) { dismissFormat(); return true; }
        if (fileDialog != null) { dismissFile(); return true; }
        if (progressDialog != null) { dismissProgress(); return true; }
        if (!open) return false;
        open = false; pageEpoch++; loading = false; clearPage(); host.onChanged(); return true;
    }

    /** The host resolves an effect to its verified original before passing original here. */
    public void chooseFormat(PhotoItem remote, DownloadedPhoto original, Runnable jpg) {
        if (closed || jpg == null || !canShow()) return;
        if (isBusy()) { feedback("RAW 正在保存", "当前文件保存完成后，再导出其他照片。可以收起进度继续查看。"); showProgress(); return; }
        ensurePalette(); dismissFormat();
        LinearLayout choices = vertical(); choices.setPadding(dp(18), dp(4), dp(18), dp(12));
        choices.addView(formatChoice("JPG（当前版本）", "保存当前照片版本，保留已套用的预设效果", () -> {
            dismissFormat(); if (!closed) jpg.run();
        }));
        choices.addView(formatChoice("RAW 原文件（NEF）", "相机拍摄的原始文件，不包含手机预设效果", () -> {
            dismissFormat();
            if (remote == null && original == null) {
                feedback("请明确选择 RAW 原文件", "这张照片没有可核对的相机来源，请在 RAW 文件列表选择 NEF 原文件。"); open(false); return;
            }
            host.beforeSavePhotos(() -> beginDownload(remote, original));
        }));
        AlertDialog dialog = dialog().setTitle("导出格式").setView(choices).setNegativeButton("取消", null).create();
        formatDialog = dialog; dialog.setOnDismissListener(ignored -> { if (formatDialog == dialog) formatDialog = null; });
        show(dialog);
    }

    /** The JPG action retains the host's existing batch workflow. RAW uses the exact selected descriptors. */
    public void chooseBatchFormat(List<PhotoItem> sources, Runnable jpg) {
        if (closed || jpg == null || sources == null || sources.isEmpty() || !canShow()) return;
        if (isBusy()) { feedback("RAW 正在保存", "请等待当前文件任务完成，再导出其他照片。"); showProgress(); return; }
        List<PhotoItem> selection = Collections.unmodifiableList(new ArrayList<>(sources));
        ensurePalette(); dismissFormat();
        LinearLayout choices = vertical(); choices.setPadding(dp(18), dp(4), dp(18), dp(12));
        choices.addView(formatChoice("JPG（相机原图）", "保存所选 " + selection.size() + " 张照片的 JPG", () -> { dismissFormat(); if (!closed) jpg.run(); }));
        choices.addView(formatChoice("RAW 原文件（NEF）", "逐张核对并保存相机 NEF，不包含手机预设效果", () -> {
            dismissFormat();
            if (selection.size() > 100 || selection.contains(null)) { feedback("请分批导出 RAW", "每批可选择 1–100 张照片；只有相机实际拍摄的 NEF 原文件可以导出。"); return; }
            host.beforeSavePhotos(() -> {
                if (closed || !rawReadAllowed(true)) return;
                if (!controller.hasLiveSession()) { host.onConnectionRequired("批量下载 RAW 原文件"); return; }
                List<Operation> inputs = new ArrayList<>(); for (PhotoItem source : selection) inputs.add(new Operation(source, null, null));
                beginBatch(inputs);
            });
        }));
        AlertDialog dialog = dialog().setTitle("导出 " + selection.size() + " 张照片").setView(choices).setNegativeButton("取消", null).create();
        formatDialog = dialog; dialog.setOnDismissListener(ignored -> { if (formatDialog == dialog) formatDialog = null; }); show(dialog);
    }

    public View createView(UiPalette palette) {
        this.palette = palette == null ? new UiPalette(activity) : palette;
        Parcelable position = list == null ? null : list.onSaveInstanceState();
        root = vertical(); root.setBackgroundColor(this.palette.surface); root.setPadding(dp(20), dp(8), dp(20), 0);
        LinearLayout tabs = new LinearLayout(activity); tabs.setPadding(dp(4), dp(4), dp(4), dp(4)); tabs.setBackground(shape(this.palette.container, 16));
        cameraTab = action("相机 NEF", false, () -> selectTab(false)); savedTab = action("已保存 NEF", false, () -> selectTab(true));
        tabs.addView(cameraTab, new LinearLayout.LayoutParams(0, -2, 1)); tabs.addView(savedTab, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(tabs, new LinearLayout.LayoutParams(-1, -2));
        TextView note = text("NEF 保留相机原始数据，不包含手机预设效果。", 13, this.palette.secondary, false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2); noteParams.topMargin = dp(12); noteParams.bottomMargin = dp(16); root.addView(note, noteParams);
        taskRegion = vertical(); pageProgress = progressViews(true); taskRegion.addView(progressView(pageProgress, true));
        LinearLayout.LayoutParams taskParams = new LinearLayout.LayoutParams(-1, -2); taskParams.bottomMargin = dp(16); root.addView(taskRegion, taskParams);
        LinearLayout listHeading = new LinearLayout(activity); listHeading.setGravity(Gravity.CENTER_VERTICAL);
        pageCount = text("", 15, this.palette.text, true); listHeading.addView(pageCount, new LinearLayout.LayoutParams(0, -2, 1));
        Button refresh = action("刷新", false, () -> { if (!isBusy() && !loading) refresh(); else feedback("文件任务进行中", "请等待当前读取或保存完成后刷新。"); });
        refresh.setCompoundDrawablesWithIntrinsicBounds(new AppIcon(AppIcon.Kind.REFRESH, this.palette.primary, density()), null, null, null);
        refresh.setCompoundDrawablePadding(dp(6)); listHeading.addView(refresh, new LinearLayout.LayoutParams(-2, -2));
        root.addView(listHeading, new LinearLayout.LayoutParams(-1, -2));
        listRegion = new FrameLayout(activity); root.addView(listRegion, new LinearLayout.LayoutParams(-1, 0, 1));
        list = new ListView(activity); list.setBackgroundColor(this.palette.surface); list.setDivider(null); list.setClipToPadding(false);
        list.setPadding(0, dp(4), 0, dp(24)); list.setFooterDividersEnabled(false);
        LinearLayout footer = vertical(); footer.setGravity(Gravity.CENTER); footer.setPadding(dp(12), dp(12), dp(12), dp(20));
        footerSpinner = new ProgressBar(activity); footerSpinner.setIndeterminateTintList(ColorStateList.valueOf(this.palette.primary));
        footer.addView(footerSpinner, new LinearLayout.LayoutParams(dp(24), dp(24)));
        footerText = text("", 13, this.palette.secondary, false); footerText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerCopy = new LinearLayout.LayoutParams(-1, -2); footerCopy.topMargin = dp(8); footer.addView(footerText, footerCopy);
        footerAction = action("重试读取", false, () -> loadPage(false)); footer.addView(footerAction, new LinearLayout.LayoutParams(-2, -2));
        list.addFooterView(footer, null, false); adapter = new FileAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, index, id) -> { if (index < adapter.getCount()) chooseFile(index); });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int state) { }
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (visible > 0 && first + visible >= total - 2) maybeLoadMore();
            }
        });
        listRegion.addView(list, new FrameLayout.LayoutParams(-1, -1));
        emptyRegion = vertical(); emptyRegion.setGravity(Gravity.CENTER); emptyRegion.setPadding(dp(20), dp(24), dp(20), dp(28));
        ImageView file = new ImageView(activity); file.setImageDrawable(new AppIcon(AppIcon.Kind.FILE, this.palette.secondary, density()));
        emptyRegion.addView(file, new LinearLayout.LayoutParams(dp(40), dp(40)));
        emptyTitle = text("", 19, this.palette.text, true); emptyTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams emptyTitleParams = new LinearLayout.LayoutParams(-1, -2); emptyTitleParams.topMargin = dp(16); emptyRegion.addView(emptyTitle, emptyTitleParams);
        emptyBody = text("", 14, this.palette.secondary, false); emptyBody.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams emptyBodyParams = new LinearLayout.LayoutParams(-1, -2); emptyBodyParams.topMargin = dp(8); emptyRegion.addView(emptyBody, emptyBodyParams);
        emptyAction = action("重试读取", false, this::emptyAction); LinearLayout.LayoutParams emptyActionParams = new LinearLayout.LayoutParams(-2, -2);
        emptyActionParams.topMargin = dp(12); emptyRegion.addView(emptyAction, emptyActionParams);
        listRegion.addView(emptyRegion, new FrameLayout.LayoutParams(-1, -1));
        updatePage(); updateProgress();
        if (position != null) list.onRestoreInstanceState(position);
        if (!loaded && !loading && pageError.isEmpty()) root.post(() -> loadPage(true));
        return root;
    }

    private void selectTab(boolean nextLocal) {
        if (closed || !open || local == nextLocal) return;
        local = nextLocal; pageEpoch++; loading = false; loaded = false; pageError = ""; nextOffset = 0; hasMore = !local;
        cameraFiles.clear(); savedFiles.clear(); if (list != null) list.setSelection(0); updatePage(); loadPage(true);
    }
    private void refresh() {
        pageEpoch++; loading = false; loaded = false; pageError = ""; nextOffset = 0; hasMore = !local;
        cameraFiles.clear(); savedFiles.clear(); updatePage(); loadPage(true);
    }
    private void maybeLoadMore() {
        if (!closed && open && !local && loaded && hasMore && !loading && pageError.isEmpty() && !isBusy() && !controller.isRawBusy()) loadPage(false);
    }
    private void loadPage(boolean first) {
        if (closed || !open || loading || isBusy() && !local) return;
        final int epoch = pageEpoch; final boolean requestedLocal = local;
        if (!local && !controller.hasLiveSession()) { pageError = "连接相机后，即可查看存储卡中的 NEF 原文件。"; loaded = true; updatePage(); return; }
        if (!local && !rawReadAllowed(false)) { pageError = liveBusy() ? "请先停止照片直播，再查看 RAW 文件；相机连接会继续保留。" : "照片任务正在处理中，请完成后重试。"; loaded = true; updatePage(); return; }
        if (!first && !local && !hasMore) return;
        loading = true; pageError = ""; updatePage();
        if (local) {
            controller.listRawDownloads(new AlbumController.Callback<List<RawPhotoDownload>>() {
                public void onSuccess(List<RawPhotoDownload> files) {
                    if (!currentPage(epoch, requestedLocal)) return;
                    loading = false; loaded = true; savedFiles.clear(); savedFiles.addAll(files); updatePage();
                }
                public void onError(String error) { pageFailure(epoch, requestedLocal, error); }
            });
        } else {
            final int offset = first ? 0 : nextOffset;
            controller.loadRawPage(offset, PAGE_SIZE, new AlbumController.Callback<PhotoPage>() {
                public void onSuccess(PhotoPage page) {
                    if (!currentPage(epoch, requestedLocal)) return;
                    loading = false; loaded = true;
                    if (page.nextOffset() < offset || page.hasMore() && page.nextOffset() == offset) { pageError = "相机 RAW 列表没有继续推进，请刷新后重试。"; updatePage(); return; }
                    Set<String> known = new LinkedHashSet<>(); for (PhotoItem file : cameraFiles) known.add(file.storageId() + ":" + file.handle());
                    for (PhotoItem file : page.items()) if (file.isRaw() && known.add(file.storageId() + ":" + file.handle())) cameraFiles.add(file);
                    nextOffset = page.nextOffset(); hasMore = page.hasMore(); updatePage();
                    if (hasMore && list != null) list.post(() -> { if (currentPage(epoch, requestedLocal)
                            && (cameraFiles.isEmpty() || list.getLastVisiblePosition() >= list.getCount() - 3)) maybeLoadMore(); });
                }
                public void onError(String error) { pageFailure(epoch, requestedLocal, error); }
            });
        }
    }
    private void pageFailure(int epoch, boolean requestedLocal, String error) {
        if (!currentPage(epoch, requestedLocal)) return;
        loading = false; loaded = true; pageError = clean(error); updatePage();
    }
    private boolean currentPage(int epoch, boolean requestedLocal) { return !closed && open && epoch == pageEpoch && local == requestedLocal; }
    private void emptyAction() {
        if (!local && !controller.hasLiveSession()) host.onConnectionRequired("查看 RAW 文件");
        else if (!local && liveBusy()) feedback("请先停止照片直播", "停止直播后可查看或下载 RAW 原文件，相机连接会继续保留。");
        else loadPage(!loaded || local);
    }
    private void updatePage() {
        if (closed || root == null) return;
        styleTab(cameraTab, !local); styleTab(savedTab, local);
        int count = local ? savedFiles.size() : cameraFiles.size();
        pageCount.setText((local ? "已保存" : "已找到") + " " + count + " 份 NEF");
        if (adapter != null) adapter.notifyDataSetChanged();
        boolean empty = count == 0;
        emptyRegion.setVisibility(empty && !loading ? View.VISIBLE : View.GONE);
        if (empty) {
            emptyTitle.setText(!pageError.isEmpty() ? local ? "暂时无法读取本机文件" : "暂时无法读取 RAW 文件" : local ? "还没有保存的 NEF" : "未找到 NEF 原文件");
            emptyBody.setText(!pageError.isEmpty() ? pageError : local ? "从相机导出的 NEF 会保留在这里，断开相机后也能保存到系统相册。"
                    : "存储卡需要有 NEF 原文件；只有 JPG 的照片无法转换为相机 RAW。");
            emptyAction.setText(!local && !controller.hasLiveSession() ? "去连接" : !local && liveBusy() ? "查看提示" : "重试读取");
            emptyAction.setVisibility(!pageError.isEmpty() ? View.VISIBLE : View.GONE);
        }
        footerSpinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        footerAction.setVisibility(!pageError.isEmpty() && !empty ? View.VISIBLE : View.GONE);
        footerText.setText(loading ? local ? "正在读取本机 NEF…" : nextOffset > 0 ? "正在查找 NEF · 已扫描 " + nextOffset + " 个文件" : "正在读取相机 RAW 文件…"
                : !pageError.isEmpty() ? pageError : !local && hasMore ? "继续下滑，读取更多 NEF" : count > 0 ? local ? "原始文件已保留在手机" : "已读取全部可用 RAW 文件" : "");
        footerText.setTextColor(!pageError.isEmpty() ? palette.error : palette.secondary);
        updateProgress();
    }

    private void chooseFile(int index) {
        if (closed || !canShow()) return;
        if (isBusy()) { showProgress(); return; }
        ensurePalette(); dismissFile();
        RawPhotoDownload saved = local ? savedFiles.get(index) : null;
        PhotoItem remote = local ? null : cameraFiles.get(index);
        String filename = saved != null ? saved.filename() : remote.filename();
        String metadata = bytes(saved != null ? saved.byteSize() : remote.byteSize());
        if (saved != null) metadata += " · 保存于 " + date(saved.savedAt());
        else if (!captureDate(remote.captureDate()).isEmpty()) metadata += " · " + captureDate(remote.captureDate());
        AlertDialog next = dialog().setTitle(clean(filename)).setMessage(metadata + "\n\n保留相机原始 NEF 数据，不包含手机预设效果。")
                .setPositiveButton(saved != null ? "保存到系统相册" : "下载原文件", (dialog, which) -> {
                    if (closed) return;
                    host.beforeSavePhotos(() -> { if (saved != null) beginPublish(saved); else beginDownload(remote, null); });
                }).setNegativeButton("取消", null).create();
        fileDialog = next; next.setOnDismissListener(ignored -> { if (fileDialog == next) fileDialog = null; }); show(next);
    }

    private void beginDownload(PhotoItem remote, DownloadedPhoto original) {
        if (closed || !rawReadAllowed(true)) return;
        // Local lookup may find a previously saved NEF without a network. The core still refuses an
        // uncached remote read without a current matching camera and uniquely verified source evidence.
        if (remote != null && !controller.hasLiveSession()) { host.onConnectionRequired("下载 RAW 原文件"); return; }
        if (!retainPhotoWork()) return;
        batch = null; Operation next = new Operation(remote, original, null); runDownload(next);
    }
    private void runDownload(Operation next) {
        try { start(next); }
        catch (RuntimeException failure) { failed(next, failure.getMessage()); return; }
        AlbumController.RawDownloadCallback callback = new AlbumController.RawDownloadCallback() {
            public void onStage(String stage) {
                if (!current(next)) return;
                next.stage = "discovery".equals(stage) ? "查找对应的 NEF 原文件" : "receiving".equals(stage) ? "接收 RAW 原文件" : "verifying".equals(stage) ? "校验原始文件" : "准备 RAW 原文件";
                if ("verifying".equals(stage)) next.progress = Math.max(next.progress, .94);
                updateProgress();
            }
            public void onDiscoveryProgress(int checked, int total) {
                if (!current(next)) return;
                next.stage = "核对 RAW 文件 · " + Math.max(0, checked) + (total > 0 ? "/" + total : ""); updateProgress();
            }
            public void onProgress(long received, long total) {
                if (!current(next)) return;
                if (total > 0) { next.total = total; next.received = Math.max(next.received, Math.max(0, Math.min(received, total))); next.progress = Math.max(next.progress, .90 * next.received / total); }
                updateProgress();
            }
            public void onSuccess(RawPhotoDownload saved) {
                if (!current(next)) return;
                if (saved == null) { failed(next, "未取得可核对的 NEF 原文件，请稍后重试。"); return; }
                next.saved = saved; next.filename = saved.filename(); next.received = next.total = saved.byteSize(); next.progress = Math.max(next.progress, .95); publish(next);
            }
            public void onError(String error) { failed(next, error); }
        };
        try {
            if (next.remote != null && next.remote.isRaw()) controller.downloadRaw(next.remote, callback);
            else if (next.remote != null) controller.downloadRawForJpeg(next.remote, callback);
            else if (next.original != null) controller.downloadRawForLocal(next.original, callback);
            else failed(next, "未找到可核对的相机照片，请在 RAW 文件列表明确选择 NEF 原文件。");
        } catch (RuntimeException failure) { failed(next, failure.getMessage()); }
    }
    private void beginPublish(RawPhotoDownload saved) {
        if (closed || !canStart()) return;
        if (!retainPhotoWork()) return;
        batch = null; Operation next = new Operation(null, null, saved); next.received = next.total = saved.byteSize(); next.progress = .95;
        try { start(next); publish(next); }
        catch (RuntimeException failure) { failed(next, failure.getMessage()); }
    }
    private void start(Operation next) {
        operation = next; next.busy = true; updateProgress(); if (adapter != null) adapter.notifyDataSetChanged();
        boolean first = next.batch == null || next.batch.index == 0;
        if (!closed && first) host.onChanged(); if (first) showProgress();
    }
    private void publish(Operation next) {
        if (!current(next)) return;
        next.stage = "保存到系统相册"; next.progress = Math.max(next.progress, .97); updateProgress();
        try { controller.publishRaw(next.saved, new AlbumController.Callback<String>() {
            public void onSuccess(String uri) {
                if (!current(next)) return;
                next.busy = false; next.complete = true; next.progress = 1; next.stage = "已保存到系统相册"; published.add(next.saved.sourceIdentity());
                if (next.batch != null) { batchFinishedInput(next, true); return; }
                releasePhotoWork();
                updateProgress();
                if (closed) return;
                if (adapter != null) adapter.notifyDataSetChanged();
                if (open && local && !loading) loadPage(true);
                host.onChanged(); feedback("RAW 已保存", "NEF 原文件已保存到系统相册，未套用手机预设。" );
            }
            public void onError(String error) { failed(next, "NEF 原文件已保留在手机，保存到系统相册未完成。" + clean(error)); }
        }); } catch (RuntimeException failure) { failed(next, failure.getMessage()); }
    }
    private boolean current(Operation expected) { return operation == expected && expected.busy; }
    private void failed(Operation next, String error) {
        if (!current(next)) return;
        next.busy = false; next.error = clean(error); next.stage = next.saved != null ? "原文件已保留，保存相册待重试" : "RAW 未完成"; updateProgress();
        if (next.batch != null) { batchFinishedInput(next, false); return; }
        releasePhotoWork();
        if (closed) return;
        if (adapter != null) adapter.notifyDataSetChanged(); host.onChanged(); feedback("RAW 未完成", next.error);
    }
    private void retry() {
        Operation previous = operation; if (closed || previous == null || previous.busy || previous.complete) return;
        host.beforeSavePhotos(() -> {
            if (closed || operation != previous || previous.busy) return;
            dismissProgress();
            if (previous.batch != null) {
                Batch old = previous.batch; if (old.active || !canStart()) return;
                List<Operation> inputs = new ArrayList<>();
                for (Operation failed : old.failedInputs) inputs.add(new Operation(failed.remote, failed.original, failed.saved));
                for (int i = old.index; i < old.inputs.size(); i++) { Operation waiting = old.inputs.get(i); inputs.add(new Operation(waiting.remote, waiting.original, waiting.saved)); }
                if (!inputs.isEmpty()) beginBatch(inputs); return;
            }
            if (previous.saved != null) beginPublish(previous.saved); else beginDownload(previous.remote, previous.original);
        });
    }

    private void beginBatch(List<Operation> inputs) {
        if (inputs.isEmpty()) return;
        if (!retainPhotoWork()) return;
        batch = new Batch(inputs); operation = batch.inputs.get(0); operation.busy = false; runBatchNext();
    }
    private void runBatchNext() {
        Batch current = batch;
        if (current == null || !current.active || current.index >= current.inputs.size()) return;
        Operation next = current.inputs.get(current.index);
        // A previous input's failure must not cause a loop against a lost camera or a newly-started live stream.
        if (next.saved == null && (liveBusy() || !controller.hasLiveSession())) {
            current.stop = true; current.reason = liveBusy() ? "照片直播已开启，尚未开始的 RAW 已保留待重试。" : "相机连接不可用，尚未开始的 RAW 已保留待重试。";
            finishBatch(current); return;
        }
        try {
            if (next.saved != null) { next.received = next.total = next.saved.byteSize(); next.progress = .95; start(next); publish(next); }
            else runDownload(next);
        } catch (RuntimeException failure) { failed(next, failure.getMessage()); }
    }
    private void batchFinishedInput(Operation input, boolean success) {
        Batch current = input.batch;
        if (batch != current || !current.active || current.index >= current.inputs.size() || current.inputs.get(current.index) != input) return;
        if (success) current.completed++; else { current.failed++; current.failedInputs.add(input); }
        current.index++;
        if (current.stop || current.index == current.inputs.size()) { finishBatch(current); return; }
        runBatchNext();
    }
    private void finishBatch(Batch current) {
        if (batch != current || !current.active) return;
        current.active = false;
        releasePhotoWork();
        Operation last = operation;
        int remaining = current.inputs.size() - current.index;
        String summary = "已保存 " + current.completed + " 份" + (current.failed > 0 ? " · " + current.failed + " 份待重试" : "") + (remaining > 0 ? " · " + remaining + " 份未开始" : "");
        if (last != null) {
            last.busy = false; last.complete = current.failed == 0 && remaining == 0;
            last.stage = summary;
            if (!current.reason.isEmpty()) last.error = current.reason;
            if (!last.complete && last.error.isEmpty()) last.error = current.failed > 0 ? "部分 RAW 未完成，可重试未完成的文件。" : "已停止后续文件；当前原文件已完成处理。";
            if (last.complete) last.progress = 1;
        }
        updateProgress(); if (closed) return;
        if (adapter != null) adapter.notifyDataSetChanged(); if (open && local && !loading) loadPage(true);
        host.onChanged(); feedback(current.stop ? "已停止后续 RAW" : "RAW 批量导出完成", summary + (current.reason.isEmpty() ? "。原始 NEF 不包含手机预设效果。" : "。" + current.reason));
    }
    private void stopRemaining() {
        if (batch == null || !batch.active || batch.stop) return;
        batch.stop = true; updateProgress();
        feedback("将停止后续文件", "当前 NEF 会继续接收并保存，完成后停止剩余文件；相机连接会保留。");
    }
    private boolean retainPhotoWork() {
        if (photoWorkLease != null) { feedback("RAW 任务尚未结束", "请等待当前原文件保存完成后重试。"); return false; }
        try {
            AutoCloseable lease = host.beginPhotoWork();
            if (lease == null) throw new IllegalStateException("后台照片服务未能启动，请返回后重试。");
            photoWorkLease = lease; return true;
        } catch (Exception failure) {
            feedback("RAW 未开始", "暂时无法启动后台照片任务。" + clean(failure.getMessage())); return false;
        }
    }
    private void releasePhotoWork() {
        AutoCloseable held = photoWorkLease; photoWorkLease = null;
        if (held != null) try { held.close(); } catch (Exception ignored) { }
    }
    private boolean canStart() {
        if (isBusy() || controller.isRawBusy() || host.isOtherPhotoWorkBusy()) { feedback("照片任务进行中", "请等待当前接收、套用预设或保存完成后，再导出 RAW。"); return false; }
        return true;
    }
    private boolean rawReadAllowed(boolean notify) {
        if (liveBusy()) { if (notify) feedback("请先停止照片直播", "停止直播后可查看或下载 RAW 原文件，相机连接会继续保留。"); return false; }
        if (notify) return canStart();
        return !isBusy() && !controller.isRawBusy() && !host.isOtherPhotoWorkBusy();
    }
    private boolean liveBusy() {
        PhotoLiveState value = controller.photoLiveState();
        return value != null && (value.enabled || value.starting || value.receiving || value.queued > 0);
    }

    private void showProgress() {
        if (closed || operation == null || !canShow()) return;
        ensurePalette(); dismissProgress();
        dialogProgress = progressViews(false); LinearLayout content = progressView(dialogProgress, false);
        content.setPadding(dp(22), dp(4), dp(22), dp(20));
        AlertDialog next = dialog().setTitle("导出 RAW").setView(content).setNegativeButton("收起", null)
                .setPositiveButton("重试", null).setNeutralButton("查看文件", null).create();
        progressDialog = next;
        next.setOnDismissListener(ignored -> { if (progressDialog == next) { progressDialog = null; dialogProgress = null; } });
        next.setOnShowListener(ignored -> {
            next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> { if (batch != null && batch.active) stopRemaining(); else retry(); });
            next.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> { dismissProgress(); open(true); }); updateProgress();
        });
        show(next); updateProgress();
    }
    private ProgressViews progressViews(boolean action) {
        ProgressViews views = new ProgressViews(); views.filename = text("", 15, palette.text, true); views.filename.setMaxLines(2); views.filename.setEllipsize(TextUtils.TruncateAt.END);
        views.stage = text("", 13, palette.secondary, false); views.percent = text("", 14, palette.primary, true); views.percent.setGravity(Gravity.END);
        views.stage.setMaxLines(action ? 4 : 7); views.stage.setEllipsize(TextUtils.TruncateAt.END);
        views.bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); views.bar.setMax(1000);
        views.bar.setProgressTintList(ColorStateList.valueOf(palette.primary)); views.bar.setProgressBackgroundTintList(ColorStateList.valueOf(palette.outline));
        views.bar.setIndeterminateTintList(ColorStateList.valueOf(palette.primary));
        if (action) views.action = action("查看", false, () -> { if (operation != null && !operation.busy && !operation.complete) retry(); else showProgress(); });
        return views;
    }
    private LinearLayout progressView(ProgressViews views, boolean card) {
        LinearLayout content = vertical(); if (card) { content.setPadding(dp(16), dp(12), dp(8), dp(12)); content.setBackground(shape(palette.card, 16)); }
        LinearLayout heading = new LinearLayout(activity); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(views.filename, new LinearLayout.LayoutParams(0, -2, 1));
        if (views.action != null) heading.addView(views.action, new LinearLayout.LayoutParams(-2, -2)); content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout stage = new LinearLayout(activity); stage.setGravity(Gravity.CENTER_VERTICAL); stage.addView(views.stage, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams percentage = new LinearLayout.LayoutParams(-2, -2); percentage.setMarginStart(dp(10)); stage.addView(views.percent, percentage);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(-1, -2); stageParams.topMargin = dp(4); content.addView(stage, stageParams);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(-1, dp(4)); bar.topMargin = dp(10); bar.setMarginEnd(card ? dp(8) : 0); content.addView(views.bar, bar);
        return content;
    }
    /** Updates existing views only; byte callbacks never request an Activity or page rebuild. */
    private void updateProgress() {
        if (closed) return;
        if (taskRegion != null) taskRegion.setVisibility(operation == null ? View.GONE : View.VISIBLE);
        bindProgress(pageProgress); bindProgress(dialogProgress);
        if (progressDialog != null && progressDialog.isShowing()) {
            Button retry = progressDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            boolean canStop = batch != null && batch.active && !batch.stop && batch.index < batch.inputs.size() - 1;
            if (retry != null) { retry.setText(canStop ? "停止后续" : "重试"); retry.setVisibility(canStop || operation != null && !operation.busy && !operation.complete ? View.VISIBLE : View.GONE); }
            Button negative = progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (negative != null) negative.setText(operation != null && operation.complete ? "完成" : "收起");
        }
    }
    private void bindProgress(ProgressViews views) {
        Operation current = operation; if (current == null || views == null) return;
        views.filename.setText(clean(current.filename));
        Batch group = current.batch;
        String stage = group != null && group.active ? "第 " + (group.index + 1) + "/" + group.inputs.size() + " 份 · " + current.stage + (group.stop ? " · 完成本张后停止" : "") : current.stage;
        views.stage.setText(!current.error.isEmpty() ? stage + "\n" + current.error : stage
                + (current.total > 0 && current.busy ? " · " + bytes(current.received) + "/" + bytes(current.total) : ""));
        views.stage.setTextColor(!current.error.isEmpty() ? palette.error : palette.secondary);
        boolean indeterminate = current.busy && current.progress == 0 && current.total <= 0;
        double fraction = group == null ? current.progress : (group.index + (group.active ? current.progress : 0)) / group.inputs.size();
        views.bar.setIndeterminate(indeterminate); views.bar.setProgress((int)Math.floor(fraction * 1000));
        views.percent.setText(indeterminate ? "" : (current.complete && (group == null || !group.active) ? 100 : Math.min(99, (int)Math.floor(fraction * 100))) + "%");
        views.percent.setContentDescription(indeterminate ? "正在核对 RAW 原文件" : "RAW 导出进度 " + views.percent.getText());
        if (views.action != null) views.action.setText(current.busy ? "查看" : current.complete ? "详情" : "重试");
    }

    private final class FileAdapter extends BaseAdapter {
        public int getCount() { return local ? savedFiles.size() : cameraFiles.size(); }
        public Object getItem(int position) { return local ? savedFiles.get(position) : cameraFiles.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row = recycled instanceof LinearLayout ? (LinearLayout)recycled : new LinearLayout(activity);
            row.removeAllViews(); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(88)); row.setPadding(dp(4), dp(12), dp(4), dp(12));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), null, shape(palette.card, 14)));
            ImageView file = new ImageView(activity); file.setImageDrawable(new AppIcon(AppIcon.Kind.FILE, palette.secondary, density()));
            LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(dp(30), dp(34)); icon.setMarginEnd(dp(14)); row.addView(file, icon);
            LinearLayout copy = vertical(); String filename, metadata;
            if (local) { RawPhotoDownload item = savedFiles.get(position); filename = item.filename(); metadata = bytes(item.byteSize()) + " · 保存于 " + date(item.savedAt()); }
            else { PhotoItem item = cameraFiles.get(position); filename = item.filename(); String captured = captureDate(item.captureDate()); metadata = bytes(item.byteSize()) + (captured.isEmpty() ? "" : " · " + captured); }
            TextView name = text(clean(filename), 15, palette.text, true); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); copy.addView(name);
            TextView detail = text(metadata, 12, palette.secondary, false); LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2); detailParams.topMargin = dp(4); copy.addView(detail, detailParams);
            row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            TextView label = text(local && published.contains(savedFiles.get(position).sourceIdentity()) ? "已存相册" : "NEF", 12, palette.secondary, true);
            label.setGravity(Gravity.CENTER); label.setPadding(dp(8), dp(5), dp(8), dp(5)); label.setBackground(shape(palette.container, 8));
            LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(-2, -2); badge.setMarginStart(dp(10)); row.addView(label, badge);
            row.setContentDescription(clean(filename) + "，NEF 原文件，" + metadata + "，" + (local ? "点击保存到系统相册" : "点击下载原文件")); return row;
        }
    }

    private View formatChoice(String title, String body, Runnable click) {
        LinearLayout row = vertical(); row.setPadding(dp(12), dp(14), dp(12), dp(14)); row.setMinimumHeight(dp(76));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), shape(palette.card, 14), null));
        TextView heading = text(title, 16, palette.text, true); row.addView(heading);
        TextView description = text(body, 13, palette.secondary, false); LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(-1, -2); bodyParams.topMargin = dp(5); row.addView(description, bodyParams);
        row.setFocusable(true); row.setClickable(true); row.setContentDescription(title + "，" + body); row.setOnClickListener(view -> click.run()); return row;
    }
    private void styleTab(Button button, boolean selected) {
        if (button == null) return;
        button.setTextColor(selected ? palette.text : palette.secondary); button.setTypeface(Typeface.create("sans-serif", selected ? Typeface.BOLD : Typeface.NORMAL));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), shape(selected ? palette.card : palette.container, 12), null)); button.setSelected(selected);
    }
    private Button action(String text, boolean primary, Runnable click) {
        Button button = new Button(activity); button.setText(text); button.setAllCaps(false); button.setTextSize(14); button.setMinimumHeight(dp(48)); button.setMinHeight(dp(48));
        button.setMinWidth(0); button.setMinimumWidth(0); button.setPadding(dp(12), dp(8), dp(12), dp(8)); button.setTextColor(primary ? palette.onPrimary : palette.primary);
        button.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), shape(primary ? palette.primary : palette.surface, 14), null));
        button.setOnClickListener(view -> click.run()); return button;
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView text = new TextView(activity); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setIncludeFontPadding(false);
        text.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL)); text.setLineSpacing(dp(3), 1); return text;
    }
    private LinearLayout vertical() { LinearLayout result = new LinearLayout(activity); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private AlertDialog.Builder dialog() { ensurePalette(); return new AlertDialog.Builder(new ContextThemeWrapper(activity,
            palette.dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)); }
    private void show(AlertDialog next) {
        try { next.show(); } catch (android.view.WindowManager.BadTokenException | IllegalStateException unavailable) {
            if (formatDialog == next) formatDialog = null; if (fileDialog == next) fileDialog = null; if (progressDialog == next) { progressDialog = null; dialogProgress = null; }
        }
    }
    private void feedback(String title, String text) { if (!closed) host.onFeedback(title, text); }
    private void ensurePalette() { if (palette == null) palette = new UiPalette(activity); }
    private boolean canShow() { return !activity.isFinishing() && !activity.isDestroyed(); }
    private void dismissFormat() { AlertDialog previous = formatDialog; formatDialog = null; if (previous != null) previous.dismiss(); }
    private void dismissFile() { AlertDialog previous = fileDialog; fileDialog = null; if (previous != null) previous.dismiss(); }
    private void dismissProgress() { AlertDialog previous = progressDialog; progressDialog = null; dialogProgress = null; if (previous != null) previous.dismiss(); }
    private void clearPage() { root = null; list = null; adapter = null; listRegion = null; emptyRegion = null; taskRegion = null; pageProgress = null; }
    private float density() { return activity.getResources().getDisplayMetrics().density; }
    private int dp(float value) { return Math.round(value * density()); }
    private static String bytes(long value) { return value >= 1024 * 1024 ? String.format(Locale.CHINA, "%.1f MB", value / (1024.0 * 1024)) : value >= 1024 ? String.format(Locale.CHINA, "%.1f KB", value / 1024.0) : Math.max(0, value) + " B"; }
    private static String date(long time) { return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.CHINA).format(new Date(time)); }
    private static String captureDate(String raw) {
        if (raw == null || raw.length() < 15) return "";
        try { SimpleDateFormat parser = new SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US); parser.setLenient(false); Date value = parser.parse(raw.substring(0, 15)); return value == null ? "" : new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(value); }
        catch (ParseException | RuntimeException invalid) { return ""; }
    }
    private static String clean(String value) { String text = value == null ? "请检查相机连接后重试。" : value.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").trim(); return text.length() > 1500 ? text.substring(0, 1500) + "…" : text; }

    @Override public void close() {
        if (closed) return;
        closed = true; open = false; pageEpoch++; loading = false; dismissFormat(); dismissFile(); dismissProgress(); clearPage();
        // An accepted download continues to its verified private copy and gallery publication.
        // Its callbacks keep their operation identity, but no longer touch this Activity or Host.
    }
}

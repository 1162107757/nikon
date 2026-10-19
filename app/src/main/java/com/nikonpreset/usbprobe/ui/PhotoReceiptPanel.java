package com.nikonpreset.usbprobe.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.media.ExifInterface;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.nikonpreset.usbprobe.album.AlbumController;
import com.nikonpreset.usbprobe.album.PhotoLiveReceipt;
import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Predicate;

/** Project-local receive history; opening it never connects or requests a camera image. */
public final class PhotoReceiptPanel implements AutoCloseable {
    public interface Host {
        void onResumeProject(String projectId);
        default void onRetryProject(String projectId) { onResumeProject(projectId); }
        void onFeedback(String title, String text);
    }

    private enum Filter { ALL, PENDING, SAVED }
    private final Activity activity;
    private final AlbumController controller;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private final List<PhotoLiveReceipt> receipts = new ArrayList<>();
    private final List<PhotoLiveReceipt> visible = new ArrayList<>();
    private final Set<String> pendingImages = new HashSet<>(), failedImages = new HashSet<>();
    private final LruCache<String, Bitmap> thumbnails = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private UiPalette palette;
    private Dialog dialog;
    private AlertDialog detailsDialog;
    private ReceiptAdapter adapter;
    private FrameLayout listRegion;
    private ListView list;
    private TextView savedCount, transferringCount, retryCount;
    private Button allFilter, pendingFilter, savedFilter, retryAction, resumeAction;
    private String projectId = "", projectName = "", loadError = "";
    private Filter filter = Filter.ALL;
    private int generation, imageGeneration;
    private boolean closed, loading, refreshQueued;
    private Function<String, String> presetStatusProvider;
    private Predicate<String> presetPendingProvider, presetRetryProvider;

    public PhotoReceiptPanel(Activity activity, AlbumController controller, Host host) {
        if (activity == null || controller == null || host == null) throw new IllegalArgumentException("Missing receipt host");
        this.activity = activity; this.controller = controller; this.host = host;
    }

    /** Called on the UI thread; the provider should read in-memory state without I/O. */
    public void setPresetStatusProvider(Function<String, String> provider) {
        if (closed) return;
        presetStatusProvider = provider;
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    public void setPresetPendingProvider(Predicate<String> pending, Predicate<String> retry) {
        presetPendingProvider = pending; presetRetryProvider = retry;
        if (dialog != null) updateContents();
    }

    public void show(String id, String name) {
        if (!canShow()) return;
        if (id == null || id.trim().isEmpty()) {
            host.onFeedback("请先选择项目", "选择拍摄项目后，即可离线查看接收记录。"); return;
        }
        dismiss();
        projectId = id; projectName = name == null || name.trim().isEmpty() ? "拍摄项目" : name;
        palette = new UiPalette(activity); filter = Filter.ALL;
        receipts.clear(); visible.clear(); loadError = "";
        LinearLayout content = vertical(); content.setPadding(dp(18), dp(10), dp(18), dp(14));
        content.setBackground(shape(palette.surface, 28));
        content.addView(buildHeading(), new LinearLayout.LayoutParams(-1, -2));
        content.addView(buildCounts(), new LinearLayout.LayoutParams(-1, -2));
        content.addView(buildFilters(), new LinearLayout.LayoutParams(-1, -2));
        listRegion = new FrameLayout(activity);
        content.addView(listRegion, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = new LinearLayout(activity); actions.setGravity(Gravity.CENTER_VERTICAL);
        retryAction = action("重试未完成", false, this::retryProject);
        resumeAction = action("继续接收此项目", true, this::resumeProject);
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(0, -2, 1);
        retryParams.setMarginEnd(dp(8)); actions.addView(retryAction, retryParams);
        actions.addView(resumeAction, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(10); content.addView(actions, actionsParams);
        Dialog next = new Dialog(activity); next.requestWindowFeature(Window.FEATURE_NO_TITLE);
        next.setContentView(content); next.setCanceledOnTouchOutside(true); dialog = next;
        next.setOnDismissListener(ignored -> { if (dialog == next) releaseDialog(); });
        Window window = next.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.28f); window.setGravity(Gravity.BOTTOM);
        }
        try {
            next.show();
            if (window != null) {
                int width = Math.min(dp(560), Math.max(dp(200), activity.getResources().getDisplayMetrics().widthPixels - dp(24)));
                int height = Math.min(dp(760), activity.getResources().getDisplayMetrics().heightPixels - dp(32));
                window.setLayout(width, Math.max(dp(220), height));
                WindowManager.LayoutParams attributes = window.getAttributes(); attributes.y = dp(10);
                window.setAttributes(attributes);
            }
        } catch (WindowManager.BadTokenException | IllegalStateException unavailable) {
            dismiss(); return;
        }
        refresh();
    }

    /** Refreshes local records only, coalescing updates while a read is already pending. */
    public void refresh() {
        if (!canShow() || dialog == null) return;
        if (loading) { refreshQueued = true; return; }
        final int request = ++generation;
        final String requestedProject = projectId;
        loading = true; loadError = ""; updateActions();
        if (receipts.isEmpty()) renderList();
        controller.listPhotoLiveReceipts(requestedProject, new AlbumController.Callback<List<PhotoLiveReceipt>>() {
            @Override public void onSuccess(List<PhotoLiveReceipt> result) {
                main.post(() -> {
                    if (!current(request, requestedProject)) return;
                    loading = false; receipts.clear(); failedImages.clear();
                    if (result != null) for (PhotoLiveReceipt receipt : result) {
                        if (receipt != null && requestedProject.equals(receipt.projectId)) receipts.add(receipt);
                    }
                    updateContents(); finishRefresh();
                });
            }
            @Override public void onError(String error) {
                main.post(() -> {
                    if (!current(request, requestedProject)) return;
                    loading = false; loadError = error == null || error.trim().isEmpty() ? "本机接收记录暂时无法读取。" : error;
                    updateContents(); finishRefresh();
                });
            }
        });
    }

    private void finishRefresh() {
        if (refreshQueued) { refreshQueued = false; refresh(); }
    }

    private View buildHeading() {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(52));
        LinearLayout names = vertical(); names.addView(text("接收记录", 19, palette.text, true));
        TextView name = text(projectName, 13, palette.secondary, false); name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name); row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = action("关闭", false, this::dismiss); close.setBackground(interactive(Color.TRANSPARENT, 14));
        row.addView(close, new LinearLayout.LayoutParams(dp(60), dp(44))); return row;
    }

    private View buildCounts() {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(64));
        savedCount = count(row, "已保存", palette.success);
        transferringCount = count(row, "传输中", palette.primary);
        retryCount = count(row, "待重试", palette.error); return row;
    }

    private TextView count(LinearLayout parent, String label, int color) {
        LinearLayout column = vertical(); column.setGravity(Gravity.CENTER);
        TextView number = text("0", 20, color, true); column.addView(number);
        TextView caption = text(label, 12, palette.secondary, false); caption.setMaxLines(1); column.addView(caption);
        parent.addView(column, new LinearLayout.LayoutParams(0, -2, 1)); return number;
    }

    private View buildFilters() {
        LinearLayout row = new LinearLayout(activity); row.setPadding(dp(3), dp(2), dp(3), dp(2));
        row.setMinimumHeight(dp(48));
        row.setBackground(shape(palette.container, 17));
        allFilter = filterButton("全部", Filter.ALL); pendingFilter = filterButton("待处理", Filter.PENDING); savedFilter = filterButton("已保存", Filter.SAVED);
        row.addView(allFilter, new LinearLayout.LayoutParams(0, -1, 1));
        row.addView(pendingFilter, new LinearLayout.LayoutParams(0, -1, 1));
        row.addView(savedFilter, new LinearLayout.LayoutParams(0, -1, 1));
        return row;
    }

    private Button filterButton(String label, Filter target) {
        return action(label, false, () -> { if (filter == target) return; filter = target; updateContents(); });
    }

    private void updateContents() {
        int saved = 0, transferring = 0, retry = 0;
        visible.clear();
        for (PhotoLiveReceipt receipt : receipts) {
            if (isSaved(receipt)) saved++;
            boolean needsRetry = !receipt.error.isEmpty() || "error".equals(receipt.status) || "stopped".equals(receipt.status)
                    || presetRetryProvider != null && presetRetryProvider.test(receipt.identity);
            if (needsRetry) retry++; else if (!isSaved(receipt)) transferring++;
            if (filter == Filter.ALL || filter == Filter.SAVED && isSaved(receipt)
                    || filter == Filter.PENDING && (!isSaved(receipt) || needsRetry || presetPendingProvider != null && presetPendingProvider.test(receipt.identity))) visible.add(receipt);
        }
        savedCount.setText(Integer.toString(saved)); transferringCount.setText(Integer.toString(transferring)); retryCount.setText(Integer.toString(retry));
        updateFilter(allFilter, filter == Filter.ALL); updateFilter(pendingFilter, filter == Filter.PENDING); updateFilter(savedFilter, filter == Filter.SAVED);
        updateActions(); renderList();
    }

    private void updateFilter(Button button, boolean selected) {
        button.setSelected(selected); button.setTextColor(selected ? palette.text : palette.secondary);
        button.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        button.setBackground(interactive(selected ? palette.card : Color.TRANSPARENT, 14));
    }

    private void updateActions() {
        if (retryAction == null) return;
        boolean unfinished = false;
        for (PhotoLiveReceipt receipt : receipts) if (!isSaved(receipt) || !receipt.error.isEmpty()
                || presetRetryProvider != null && presetRetryProvider.test(receipt.identity)) { unfinished = true; break; }
        retryAction.setVisibility(unfinished ? View.VISIBLE : View.GONE);
        // The host checks camera/permissions/tasks even when the sheet is offline.
        resumeAction.setEnabled(!loading); retryAction.setEnabled(!loading);
    }

    private void renderList() {
        if (listRegion == null) return;
        if (!loading && loadError.isEmpty() && !visible.isEmpty()) {
            if (list == null) {
                listRegion.removeAllViews(); list = new ListView(activity);
                list.setDivider(new ColorDrawable(palette.outline)); list.setDividerHeight(dp(0.5f));
                list.setClipToPadding(false); list.setPadding(0, dp(8), 0, dp(4));
                adapter = new ReceiptAdapter(); list.setAdapter(adapter);
                listRegion.addView(list, new FrameLayout.LayoutParams(-1, -1));
            } else adapter.notifyDataSetChanged();
            return;
        }
        list = null; adapter = null; listRegion.removeAllViews();
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(true);
        LinearLayout state = vertical(); state.setGravity(Gravity.CENTER); state.setPadding(dp(12), dp(16), dp(12), dp(16));
        if (loading) {
            ProgressBar progress = new ProgressBar(activity); state.addView(progress, new LinearLayout.LayoutParams(dp(28), dp(28)));
        }
        TextView message = text(loading ? "正在读取本机记录…" : !loadError.isEmpty() ? "记录读取未完成"
                : receipts.isEmpty() ? "还没有接收记录" : filter == Filter.PENDING ? "没有待处理记录" : "还没有已保存照片", 15, palette.secondary, false);
        message.setGravity(Gravity.CENTER); message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); state.addView(message);
        if (!loadError.isEmpty()) {
            TextView error = text(loadError, 13, palette.error, false); error.setMaxLines(2); error.setEllipsize(TextUtils.TruncateAt.END);
            error.setGravity(Gravity.CENTER); state.addView(error);
            state.addView(action("重试读取", false, this::refresh), new LinearLayout.LayoutParams(-1, -2));
            state.addView(action("错误详情", false, () -> showDetails("记录读取未完成", loadError)), new LinearLayout.LayoutParams(-1, -2));
        } else if (!loading && receipts.isEmpty()) {
            TextView hint = text("项目记录保存在本机，断开相机也可查看。", 13, palette.secondary, false);
            hint.setGravity(Gravity.CENTER); state.addView(hint);
        }
        scroll.addView(state, new ScrollView.LayoutParams(-1, -1)); listRegion.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    }

    private void resumeProject() {
        if (!canShow() || dialog == null || loading) return;
        String selectedProject = projectId;
        dismiss(); host.onResumeProject(selectedProject);
    }

    private void retryProject() {
        if (!canShow() || dialog == null || loading) return;
        String selectedProject = projectId; dismiss(); host.onRetryProject(selectedProject);
    }

    private final class ReceiptAdapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Row row = recycled instanceof Row ? (Row) recycled : new Row();
            PhotoLiveReceipt receipt = visible.get(position); row.receipt = receipt;
            row.name.setText(receipt.filename.isEmpty() ? "未命名照片" : receipt.filename);
            String status = status(receipt); row.status.setText(status + " · " + bytes(receipt.byteSize));
            row.status.setTextColor(isSaved(receipt) ? palette.success : "error".equals(receipt.status) || "stopped".equals(receipt.status) ? palette.error : palette.secondary);
            row.time.setText(receipt.updatedAt > 0 ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(receipt.updatedAt)) : "时间未记录");
            String effect = presetStatus(receipt.identity); row.effect.setText(effect); row.effect.setVisibility(effect.isEmpty() ? View.GONE : View.VISIBLE);
            row.error.setText(receipt.error); row.error.setVisibility(receipt.error.isEmpty() ? View.GONE : View.VISIBLE);
            row.image.setImageDrawable(new AppIcon(AppIcon.Kind.CAMERA, palette.secondary, activity.getResources().getDisplayMetrics().density));
            String key = imageKey(receipt); row.imageKey = key;
            Bitmap cached = receipt.saved == null ? null : thumbnails.get(key);
            if (cached != null) row.image.setImageBitmap(cached);
            else if (receipt.saved != null) requestThumbnail(receipt, key);
            row.info.setContentDescription((receipt.filename.isEmpty() ? "照片" : receipt.filename) + "，接收详情");
            return row;
        }
    }

    private final class Row extends LinearLayout {
        final ImageView image; final TextView name, status, time, effect, error; final ImageButton info;
        PhotoLiveReceipt receipt; String imageKey;
        Row() {
            super(activity); setGravity(Gravity.CENTER_VERTICAL); setPadding(0, dp(12), 0, dp(12));
            image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackground(shape(palette.container, 12)); image.setClipToOutline(true); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(image, new LinearLayout.LayoutParams(dp(56), dp(56)));
            LinearLayout labels = vertical(); name = text("", 14, palette.text, true); name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            status = text("", 12, palette.secondary, false); status.setMaxLines(1); status.setEllipsize(TextUtils.TruncateAt.END);
            time = text("", 12, palette.secondary, false); time.setMaxLines(1);
            effect = text("", 12, palette.primary, false); effect.setMaxLines(2); effect.setEllipsize(TextUtils.TruncateAt.END);
            error = text("", 12, palette.error, false); error.setMaxLines(2); error.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name); labels.addView(status); labels.addView(time); labels.addView(effect); labels.addView(error);
            LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(0, -2, 1); labelsParams.setMarginStart(dp(12)); addView(labels, labelsParams);
            info = new ImageButton(activity); info.setImageDrawable(new AppIcon(AppIcon.Kind.INFO, palette.secondary, activity.getResources().getDisplayMetrics().density));
            info.setPadding(dp(11), dp(11), dp(11), dp(11)); info.setBackground(interactive(Color.TRANSPARENT, 16));
            info.setOnClickListener(view -> { if (receipt != null) showReceiptDetails(receipt); }); addView(info, new LinearLayout.LayoutParams(dp(44), dp(44)));
        }
    }

    private void showReceiptDetails(PhotoLiveReceipt receipt) {
        String effect = presetStatus(receipt.identity);
        String detail = "文件：" + receipt.filename + "\n状态：" + status(receipt) + "\n大小：" + bytes(receipt.byteSize)
                + (receipt.updatedAt > 0 ? "\n更新：" + DateFormat.getDateTimeInstance().format(new Date(receipt.updatedAt)) : "")
                + (!receipt.captureDate.isEmpty() ? "\n相机拍摄时间：" + receipt.captureDate : "")
                + (!effect.isEmpty() ? "\n效果：" + effect : "") + (!receipt.error.isEmpty() ? "\n\n" + receipt.error : "");
        showDetails("接收详情", detail);
    }

    private void showDetails(String title, String message) {
        if (!canShow() || dialog == null) return;
        if (detailsDialog != null) detailsDialog.dismiss();
        ScrollView scroll = new ScrollView(activity); TextView detail = text(message, 14, palette.text, false);
        detail.setPadding(dp(24), dp(10), dp(24), dp(16)); detail.setTextIsSelectable(true); scroll.addView(detail);
        AlertDialog next = new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton("关闭", null).create();
        detailsDialog = next; next.setOnDismissListener(ignored -> { if (detailsDialog == next) detailsDialog = null; }); next.show();
        next.getButton(AlertDialog.BUTTON_POSITIVE).setMinHeight(dp(44)); next.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(palette.primary);
        if (next.getWindow() != null) next.getWindow().setBackgroundDrawable(shape(palette.card, 24));
    }

    private void requestThumbnail(PhotoLiveReceipt receipt, String key) {
        if (closed || dialog == null || pendingImages.size() >= 6 || pendingImages.contains(key) || failedImages.contains(key)) return;
        final int request = imageGeneration; final File source = receipt.saved.file(); pendingImages.add(key);
        images.execute(() -> {
            Bitmap result = null;
            try {
                if (source.isFile()) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1; int target = dp(72);
                        while (Math.max(bounds.outWidth, bounds.outHeight) / (options.inSampleSize * 2) >= target) options.inSampleSize *= 2;
                        result = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
                        if (result != null) result = orientThumbnail(result, source);
                    }
                }
            } catch (RuntimeException | OutOfMemoryError unavailable) { }
            final Bitmap ready = result;
            main.post(() -> {
                if (closed || dialog == null || request != imageGeneration) { if (ready != null) ready.recycle(); return; }
                pendingImages.remove(key);
                if (ready != null) thumbnails.put(key, ready); else failedImages.add(key);
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        });
    }

    private String presetStatus(String identity) {
        if (presetStatusProvider == null) return "";
        try { String value = presetStatusProvider.apply(identity); return value == null ? "" : value.trim(); }
        catch (RuntimeException unavailable) { return ""; }
    }

    private static Bitmap orientThumbnail(Bitmap bitmap, File source) {
        try {
            Matrix matrix = new Matrix();
            switch (new ExifInterface(source.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
                case ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
                case ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(270); matrix.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(270); break;
                default: return bitmap;
            }
            Bitmap oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (oriented != bitmap) bitmap.recycle();
            return oriented;
        } catch (java.io.IOException invalidExif) { return bitmap; }
    }

    private static boolean isSaved(PhotoLiveReceipt receipt) { return receipt.saved != null || "saved".equals(receipt.status); }
    private static String imageKey(PhotoLiveReceipt receipt) {
        return receipt.projectId + ":" + receipt.identity + (receipt.saved == null ? "" : ":" + receipt.saved.file().getAbsolutePath());
    }
    private static String status(PhotoLiveReceipt receipt) {
        if (receipt.saved != null) return receipt.error.isEmpty() ? "原图已保存" : "原图已保存，需重试";
        switch (receipt.status) {
            case "saved": return "原图已移除";
            case "queued": return "等待接收";
            case "preview": return "预览已就绪";
            case "receiving": return "正在接收原图";
            case "error": return "接收待重试";
            default: return "接收已暂停";
        }
    }

    private static String bytes(long size) {
        if (size <= 0) return "大小未记录";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.ROOT, "%.0f KB", size / 1024d);
        return String.format(Locale.ROOT, "%.1f MB", size / (1024d * 1024d));
    }

    private boolean current(int request, String id) { return canShow() && dialog != null && request == generation && id.equals(projectId); }
    private boolean canShow() { return !closed && !activity.isFinishing() && !activity.isDestroyed(); }
    private void dismiss() {
        Dialog previous = dialog;
        if (previous != null) previous.dismiss();
        if (previous == null || dialog == previous) releaseDialog();
    }
    private void releaseDialog() {
        generation++; imageGeneration++; loading = refreshQueued = false;
        if (detailsDialog != null) detailsDialog.dismiss(); detailsDialog = null;
        dialog = null; list = null; adapter = null; listRegion = null;
        savedCount = transferringCount = retryCount = null;
        allFilter = pendingFilter = savedFilter = retryAction = resumeAction = null;
        pendingImages.clear(); failedImages.clear();
    }

    @Override public void close() {
        if (closed) return; closed = true; dismiss(); main.removeCallbacksAndMessages(null);
        images.shutdownNow(); thumbnails.evictAll(); receipts.clear(); visible.clear(); presetStatusProvider = null; presetPendingProvider = presetRetryProvider = null;
    }

    private LinearLayout vertical() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL)); return view;
    }
    private Button action(String label, boolean primary, Runnable callback) {
        Button view = new Button(activity); view.setText(label); view.setAllCaps(false); view.setTextSize(13);
        view.setTextColor(primary ? palette.onPrimary : palette.primary); view.setMinHeight(dp(44)); view.setMinimumHeight(dp(44));
        view.setPadding(dp(8), dp(8), dp(8), dp(8)); view.setBackground(interactive(primary ? palette.primary : palette.primaryContainer, 16));
        view.setOnClickListener(ignored -> { if (canShow() && dialog != null) callback.run(); }); return view;
    }
    private GradientDrawable shape(int color, float radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private RippleDrawable interactive(int color, float radius) { return new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), shape(color, radius), shape(Color.WHITE, radius)); }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}

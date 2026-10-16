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
import com.nikonpreset.usbprobe.library.OnlinePresetRepository;
import com.nikonpreset.usbprobe.library.PresetLibrary;
import com.nikonpreset.usbprobe.library.PresetRecord;
import com.nikonpreset.usbprobe.ncp.NcpFile;
import com.nikonpreset.usbprobe.ncp.NcpPictureControlConverter;
import com.nikonpreset.usbprobe.picturecontrol.PictureControlData;
import com.nikonpreset.usbprobe.ui.AlbumPanel;
import com.nikonpreset.usbprobe.ui.AppIcon;
import com.nikonpreset.usbprobe.ui.UiPalette;
import com.nikonpreset.usbprobe.usb.UsbPictureControlImport;
import com.nikonpreset.usbprobe.usb.UsbPictureControlProbe;
import com.nikonpreset.usbprobe.usb.UsbProbe;

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

/** Consumer shell. All camera writes remain in the existing, independently guarded USB backend. */
public final class MainActivity extends Activity {
    private static final int PICK_NCP = 100, PICK_SAMPLE = 101, EXPORT_REPORT = 102;
    private static final String USB_PERMISSION = "com.nikonpreset.usbprobe.CONSUMER_USB_PERMISSION";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final Set<String> pendingImages = new HashSet<>();
    private final Set<String> failedImages = new HashSet<>();
    private final Map<String, List<PreviewTarget>> imageTargets = new HashMap<>();
    private UiPalette colors;
    private UsbManager usb;
    private PresetLibrary library;
    private OnlinePresetRepository online;
    private AlbumPanel album;
    private LinearLayout shell, body;
    private TextView title, busyLabel;
    private boolean localTab, albumTab, ready, busy, receiverRegistered, importAfterPick;
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
    private JSONObject pendingJournal;
    private int deferredRequest;
    private Intent deferredResult;
    private PendingConfirmation confirmation;
    private ImportOutcome importOutcome;

    private static final class PendingConfirmation {
        final UsbDevice device;
        final String model, occupied;
        final PresetRecord preset;
        private final byte[] source;
        final List<Integer> emptySlots;
        int targetIndex, middle = 4, clarity;
        NcpPictureControlConverter.Conversion conversion;
        PendingConfirmation(UsbDevice device, String model, PresetRecord preset, byte[] source,
                            List<Integer> emptySlots, String occupied) {
            this.device = device; this.model = model; this.preset = preset; this.source = source.clone();
            this.emptySlots = new ArrayList<>(emptySlots); this.occupied = occupied;
        }
        int target() { return emptySlots.get(targetIndex); }
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
                    scanSlots(device, permissionRecovery);
                } else finishBusy("未获得相机访问权限，请重新导入并在系统弹窗中允许。 ");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbPictureControlProbe probe = activeProbe;
                if (probe != null && probe.isProbingDevice(device)) probe.close();
                UsbPictureControlImport importer = activeImport;
                if (importer != null && importer.isProbingDevice(device)) importer.close();
                if (permissionDevice != null && device != null && permissionDevice.getDeviceId() == device.getDeviceId()) {
                    permissionDevice = null; finishBusy("相机已断开。请连接数据线后重新开始。");
                } else if (!busy) render();
            } else if (!busy) render();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        colors = new UiPalette(this);
        usb = (UsbManager) getSystemService(USB_SERVICE);
        if (state != null) {
            localTab = state.getBoolean("local"); albumTab = state.getBoolean("album");
            detailId = state.getString("detailId", "");
            previewForId = state.getString("previewForId", ""); importAfterPick = state.getBoolean("importAfterPick");
        }
        album = new AlbumPanel(this, new AlbumPanel.Host() {
            @Override public void onChanged() {
                if (!destroyed && albumTab) render();
            }
            @Override public void onMessage(String title, String text) { notice(title, text); }
        });
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
                File previous = new File(getFilesDir(), "last-report.json");
                if (previous.isFile()) try { report = readText(new AtomicFile(previous), 4 * 1024 * 1024); }
                catch (IOException e) { message = "最近报告暂时无法读取，本地预设仍可使用。"; }
                AtomicFile journal = journalFile();
                if (journal.getBaseFile().isFile() || new File(journal.getBaseFile().getPath() + ".bak").isFile()) {
                    recoveryBlocked = true;
                    try { pendingJournal = new JSONObject(readText(journal, 32 * 1024)); }
                    catch (Exception e) { message = "上次导入的记录无法读取。请先打开帮助导出报告，勿重复导入。"; }
                }
                onUi(() -> {
                    if (state == null && online.getEndpoint().isEmpty() && !library.list().isEmpty()) localTab = true;
                    ready = true; render();
                    if (deferredResult != null) { Intent savedResult = deferredResult; int request = deferredRequest; deferredResult = null; onActivityResult(request, RESULT_OK, savedResult); }
                    else if (!localTab && !albumTab) loadCatalog(false);
                });
            } catch (Exception e) { onUi(() -> { initializationError = readable(e); ready = true; render(); }); }
        });
    }

    private void render() {
        if (destroyed) return;
        colors = new UiPalette(this); imageEpoch++;
        getWindow().setStatusBarColor(colors.surface); getWindow().setNavigationBarColor(colors.surface);
        int system = colors.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(system);
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
        LinearLayout bar = horizontal(); bar.setPadding(dp(16), dp(8), dp(12), dp(8));
        boolean back = confirmation != null || importOutcome != null || !detailId.isEmpty() || detailOnline != null || (albumTab && album.canGoBack());
        if (back) bar.addView(iconButton(AppIcon.Kind.BACK, "返回", this::goBack));
        title = text(albumTab ? album.title() : importOutcome != null ? "导入结果" : confirmation != null ? "确认导入" : (!detailId.isEmpty() || detailOnline != null ? "预设详情" : "预设库"), back ? 20 : 25, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (!back) bar.addView(iconButton(AppIcon.Kind.HELP, "帮助与设置", this::showHelp)); pane.addView(bar);
        if (busy) {
            LinearLayout loading = horizontal(); loading.setPadding(dp(20), dp(10), dp(20), dp(10));
            ProgressBar spinner = new ProgressBar(this); loading.addView(spinner, new LinearLayout.LayoutParams(dp(24), dp(24)));
            busyLabel = text(progress, 14, false); busyLabel.setPadding(dp(12), 0, 0, 0); loading.addView(busyLabel, new LinearLayout.LayoutParams(0, -2, 1)); pane.addView(loading);
        } else busyLabel = null;
        body = vertical(); pane.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if (!ready) empty(body, "正在准备预设库", "正在读取保存在手机中的预设。", null, null);
        else if (!initializationError.isEmpty()) empty(body, "预设库暂不可用", initializationError, "查看帮助", this::showHelp);
        else if (albumTab) body.addView(album.createView(), new LinearLayout.LayoutParams(-1, -1));
        else if (importOutcome != null) renderImportOutcome();
        else if (confirmation != null) renderConfirmation();
        else if (!detailId.isEmpty() || detailOnline != null) renderDetail();
        else renderHome();
        if (!back) {
            divider(pane);
            LinearLayout navigation = horizontal(); navigation.setPadding(dp(12), dp(3), dp(12), dp(4));
            nav(navigation, "预设", AppIcon.Kind.PRESETS, !albumTab, () -> switchTab(false));
            nav(navigation, "相册", AppIcon.Kind.ALBUM, albumTab, () -> switchTab(true));
            pane.addView(navigation);
        }
        setContentView(shell); shell.requestApplyInsets();
    }

    private void renderHome() {
        LinearLayout top = vertical(); top.setPadding(0, 0, 0, dp(8));
        LinearLayout tabs = horizontal();
        sourceTab(tabs, "在线预设", !localTab, () -> { localTab = false; render(); if (!catalogLoaded) loadCatalog(false); });
        sourceTab(tabs, "我的预设", localTab, () -> { localTab = true; render(); });
        top.addView(tabs); divider(top);
        LinearLayout connection = getResources().getConfiguration().screenWidthDp < 360 || getResources().getConfiguration().fontScale > 1.25f ? vertical() : horizontal();
        connection.setPadding(dp(12), dp(9), dp(8), dp(9)); connection.setBackground(shape(colors.container, 10));
        LinearLayout status = horizontal();
        ImageView cameraIcon = new ImageView(this); cameraIcon.setImageDrawable(new AppIcon(AppIcon.Kind.CAMERA, colors.secondary, getResources().getDisplayMetrics().density));
        status.addView(cameraIcon, new LinearLayout.LayoutParams(dp(25), dp(25)));
        LinearLayout statusCopy = vertical(); statusCopy.setPadding(dp(10), 0, dp(6), 0);
        TextView connectionTitle = text(cameraConnectionText(), 14, true); statusCopy.addView(connectionTitle);
        TextView connectionNote = text("浏览与下载不受影响", 11, false); connectionNote.setTextColor(colors.secondary); statusCopy.addView(connectionNote); status.addView(statusCopy);
        if (connection.getOrientation() == LinearLayout.HORIZONTAL) connection.addView(status, new LinearLayout.LayoutParams(0, -2, 1)); else connection.addView(status);
        Button file = compactButton("从手机添加 NCP", AppIcon.Kind.FILE, true, () -> pickNcp(true));
        LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(-2, -2); fileParams.topMargin = connection.getOrientation() == LinearLayout.VERTICAL ? dp(8) : 0; connection.addView(file, fileParams);
        LinearLayout.LayoutParams connectionParams = new LinearLayout.LayoutParams(-1, -2); connectionParams.topMargin = dp(12); connectionParams.bottomMargin = dp(2); top.addView(connection, connectionParams);
        if (recoveryBlocked) {
            TextView warning = text("上次导入的结果需要核对，请先读取相机，避免重复导入。", 14, false);
            warning.setTextColor(colors.error); top.addView(warning);
            top.addView(button("核对上次导入 · 只读", AppIcon.Kind.REFRESH, false, this::recoverImport));
        }
        if (!message.isEmpty()) { TextView note = text(message, 13, false); note.setPadding(0, dp(8), 0, dp(4)); top.addView(note); }
        list = new ListView(this); list.setDivider(null); list.setClipToPadding(false); list.setPadding(dp(16), 0, dp(16), dp(24));
        list.addHeaderView(top, null, false);
        if (!localTab && catalogLoaded && !loadingCatalog && !catalog.isEmpty()) {
            LinearLayout footer = vertical(); footer.addView(button("刷新在线预设", AppIcon.Kind.REFRESH, false, () -> loadCatalog(false))); list.addFooterView(footer, null, false);
        }
        adapter = new PresetAdapter(); list.setAdapter(adapter);
        body.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); refreshPresetRows();
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
                if (localTab) empty(holder, "把喜欢的色彩带在身边", "从手机添加 NCP，或在线下载预设。没有样片的预设仍可导入相机。", "添加本地预设", () -> pickNcp(false));
                else if (loadingCatalog) empty(holder, "正在加载在线预设", "下载后的预设会保存在“我的预设”。", null, null);
                else if (online.getEndpoint().isEmpty()) empty(holder, "在线预设库尚未配置", "配置预设库地址后，即可浏览真实样片并下载。也可以先使用本地 NCP。", "配置在线库地址", MainActivity.this::configureCatalog);
                else empty(holder, "暂时没有在线预设", catalogMessage, "重新加载", () -> loadCatalog(false));
                if (!localTab && !loadingCatalog && !online.getEndpoint().isEmpty()) holder.addView(button("查看缓存目录", AppIcon.Kind.PRESETS, false, () -> loadCatalog(true)));
                return holder;
            }
            Object value = visiblePresets.get(position);
            PresetRecord record = value instanceof PresetRecord ? (PresetRecord) value : null;
            OnlinePreset item = value instanceof OnlinePreset ? (OnlinePreset) value : null;
            LinearLayout row = vertical(); row.setPadding(0, dp(2), 0, dp(16));
            String name = record == null ? item.name : record.displayName;
            FrameLayout photo = preview(record, item, photoHeight(1.68f)); row.addView(photo);
            LinearLayout caption = horizontal(); caption.setPadding(dp(8), dp(10), dp(8), dp(10));
            LinearLayout copy = vertical(); TextView nameView = text(name, 18, true); nameView.setPadding(0, 0, dp(8), dp(4)); copy.addView(nameView);
            boolean downloaded = record != null || library.findBySha256(item.sha256) != null;
            String description = record == null ? item.description : record.description;
            TextView sub = text(description.isEmpty() ? "本地 NCP · 查看详情并导入相机" : description, 12, false); sub.setMaxLines(2); sub.setTextColor(colors.secondary); copy.addView(sub);
            caption.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            Button action = compactButton(downloaded ? "查看" : "下载", downloaded ? null : AppIcon.Kind.DOWNLOAD, false, () -> {
                if (busy) return;
                detailId = record == null ? "" : record.id; detailOnline = item; render();
                if (!downloaded) downloadPreset(item);
            });
            caption.addView(action, new LinearLayout.LayoutParams(-2, -2)); row.addView(caption);
            row.setContentDescription(name + "，" + (downloaded ? "已在本地" : "可下载") + "，查看预设");
            row.setBackground(ripple(colors.surface)); row.setFocusable(true); row.setClickable(true);
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
        content.addView(preview(local, remote, photoHeight(1.25f)));
        heading(content, local != null ? local.displayName : remote.name, 24);
        String description = local != null ? local.description : remote.description;
        if (!description.isEmpty()) label(content, description, 15, colors.text);
        informationRow(content, AppIcon.Kind.CHECK, local != null ? "已保存在手机" : "在线预设 · 尚未下载", local != null ? "NCP 预设文件" : "下载后即可导入相机");
        divider(content);
        informationRow(content, AppIcon.Kind.CAMERA, "USB 兼容导入", "连接相机后检查兼容性与可用空槽");
        String previewKey = local != null && !local.previewUri.isEmpty() ? local.previewUri : remote == null ? "" : remote.previewUrl;
        if (!previewKey.isEmpty() && failedImages.contains(previewKey)) content.addView(button("重新加载样片", AppIcon.Kind.REFRESH, false, () -> { failedImages.remove(previewKey); render(); }));
        if (remote != null && !remote.compatibleModels.isEmpty()) label(content, "发布者标注机型：" + String.join("、", remote.compatibleModels) + "。导入前仍会检查文件兼容性。", 12, colors.secondary);
        if (local != null) {
            final String id = local.id;
            content.addView(textLink(local.previewUri.isEmpty() ? "添加配套真实样片" : "更换配套真实样片", () -> pickSample(id)));
            label(content, local.originalFileName + " · " + local.byteLength + " 字节", 12, colors.secondary);
        }
        TextView sampleNote = label(content, "样片仅作拍摄参考，效果以实际拍摄为准。", 12, colors.secondary); sampleNote.setBackground(shape(colors.container, 8)); sampleNote.setPadding(dp(12), dp(10), dp(12), dp(10));
        if (!message.isEmpty()) label(content, message, 13, colors.secondary);
        LinearLayout footer = vertical(); footer.setPadding(dp(16), dp(4), dp(16), dp(12));
        if (local != null) {
            PresetRecord choice = local;
            footer.addView(button("导入相机", AppIcon.Kind.CAMERA, true, () -> beginImport(choice)));
            TextView note = label(footer, "使用 USB 数据线 · 保存到机内空槽", 12, colors.secondary); note.setGravity(Gravity.CENTER);
        } else footer.addView(button("下载到手机", AppIcon.Kind.DOWNLOAD, true, () -> downloadPreset(remote)));
        body.addView(footer);
    }

    private FrameLayout preview(PresetRecord record, OnlinePreset remote, int height) {
        FrameLayout frame = new FrameLayout(this); frame.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(height)));
        frame.setBackground(shape(colors.container, 9)); frame.setClipToOutline(true);
        TextView placeholder = text("暂无样片", 14, false); placeholder.setGravity(Gravity.CENTER); placeholder.setTextColor(colors.secondary);
        frame.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
        ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setContentDescription("预设配套样片"); frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        String uri = record == null ? "" : record.previewUri;
        String key = !uri.isEmpty() ? uri : remote == null || remote.previewUrl.isEmpty() ? "" : remote.previewUrl;
        if (key.isEmpty()) { placeholder.setText("暂无配套样片"); return frame; }
        image.setTag(key); Bitmap cached = imageCache.get(key);
        if (cached != null) { image.setImageBitmap(cached); placeholder.setVisibility(View.GONE); return frame; }
        if (failedImages.contains(key)) { placeholder.setText("样片暂不可用"); return frame; }
        placeholder.setText("正在载入样片…");
        List<PreviewTarget> targets = imageTargets.get(key);
        if (targets == null) { targets = new ArrayList<>(); imageTargets.put(key, targets); }
        targets.add(new PreviewTarget(image, placeholder, imageEpoch));
        if (pendingImages.contains(key)) return frame;
        // Bound old requests across rapid navigation. Visible rows share one network/decode task per key.
        if (pendingImages.size() >= 32) { placeholder.setText("样片待加载，稍后重试"); return frame; }
        pendingImages.add(key);
        images.execute(() -> {
            Bitmap bitmap = null; String error = "";
            try {
                Uri source = !uri.isEmpty() ? Uri.parse(uri) : Uri.fromFile(online.downloadPreview(remote));
                bitmap = decodePreview(readBounded(source, 8 * 1024 * 1024));
            } catch (Exception | OutOfMemoryError e) { error = "样片暂不可用"; }
            final Bitmap result = bitmap; final String failure = error;
            runOnUiThread(() -> {
                pendingImages.remove(key); List<PreviewTarget> waiting = imageTargets.remove(key);
                if (destroyed) { if (result != null) result.recycle(); return; }
                if (result != null) imageCache.put(key, result); else failedImages.add(key);
                if (waiting != null) for (PreviewTarget target : waiting) if (target.epoch == imageEpoch && key.equals(target.image.getTag())) {
                    if (result != null) { target.image.setImageBitmap(result); target.placeholder.setVisibility(View.GONE); }
                    else target.placeholder.setText(failure);
                }
            });
        });
        return frame;
    }

    private static final class PreviewTarget {
        final ImageView image; final TextView placeholder; final int epoch;
        PreviewTarget(ImageView image, TextView placeholder, int epoch) { this.image = image; this.placeholder = placeholder; this.epoch = epoch; }
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
        if (request == PICK_NCP) {
            final boolean shouldImport = importAfterPick; importAfterPick = false; startBusy("正在添加预设…");
            worker.execute(() -> { try {
                PresetLibrary.AddResult added = library.importFile(documentName(uri), readBounded(uri, NcpFile.MAX_BYTES));
                onUi(() -> { busy = false; localTab = true; albumTab = false; detailId = added.record.id; detailOnline = null;
                    message = added.added ? "已加入我的预设。可添加真实样片，或直接导入相机。" : "相同预设已在本地，可直接使用。"; render(); if (shouldImport) beginImport(added.record); });
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
        if (busy) return;
        if (recoveryBlocked) { notice("请先核对上次导入", "上次操作的结果尚未确认，请在预设首页点击“核对上次导入”，读取实际槽位后再继续。 "); return; }
        selected = record; startBusy("正在检查预设兼容性…");
        worker.execute(() -> { try {
            byte[] bytes = library.readNcp(record.id); NcpPictureControlConverter.convert(bytes, 4, 0);
            onUi(() -> { selectedBytes = bytes; busy = false; findCamera(false); });
        } catch (Exception e) { onUi(() -> { finishBusy("这份预设暂不能通过当前兼容通道导入。"); notice("暂不支持这份预设", readable(e) + "\n原文件已保留在手机。"); }); } });
    }

    private void recoverImport() {
        if (busy) return;
        if (pendingJournal == null) { notice("导入记录无法读取", "请先通过帮助导出已有报告，并在相机上检查已保存的预设。当前不会发起新写入。 "); return; }
        new AlertDialog.Builder(this).setTitle("核对上次使用的相机")
                .setMessage("请确认当前连接的是上次导入的同一台相机。应用无法区分同型号的不同机身。\n\n接下来只读取相机中的预设，不会写入或覆盖。")
                .setNegativeButton("取消", null).setPositiveButton("已连接同一台相机，开始核对", (d, w) -> findCamera(true)).show();
    }

    private void findCamera(boolean recovery) {
        if (busy) return;
        // Also cancel a wireless connection still being established before opening a USB session.
        album.disconnect();
        List<UsbDevice> devices = UsbProbe.findNikonDevices(usb);
        if (devices.isEmpty()) {
            render();
            new AlertDialog.Builder(this).setTitle("用数据线连接相机")
                    .setMessage("打开相机，用支持数据传输的 USB 线连接手机。\n\n如果手机提供 OTG 开关，请先打开。连接后允许此 App 访问相机，并关闭占用相机的其他应用。\n\n预设导入使用 USB；相册使用无线连接。")
                    .setNegativeButton("稍后", null).setPositiveButton("已连接，重新检测", (d, w) -> findCamera(recovery)).show(); return;
        }
        if (devices.size() == 1) requestCamera(devices.get(0), recovery);
        else {
            String[] names = new String[devices.size()]; for (int i = 0; i < names.length; i++) names[i] = "Nikon USB · " + String.format(Locale.ROOT, "%04X", devices.get(i).getProductId());
            new AlertDialog.Builder(this).setTitle("选择相机").setItems(names, (d, which) -> requestCamera(devices.get(which), recovery)).setNegativeButton("取消", null).show();
        }
    }

    private void requestCamera(UsbDevice device, boolean recovery) {
        if (busy) return; startBusy(recovery ? "正在核对上次导入…" : "正在读取相机空槽…");
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
        UsbPictureControlProbe probe = new UsbPictureControlProbe(); activeProbe = probe;
        worker.execute(() -> {
            try {
                UsbPictureControlProbe.Result result = probe.probe(device, usb);
                List<UsbPictureControlProbe.ItemRecord> slots = validateSnapshot(result);
                if (recovery) { completeRecovery(result, slots); return; }
                String saveWarning = saveReport(result.toJsonString());
                onUi(() -> { busy = false; message = saveWarning; render(); chooseImportOptions(device, result.model, slots); });
            } catch (Exception e) {
                if (e instanceof UsbPictureControlProbe.ProbeException) {
                    UsbPictureControlProbe.Result partial = ((UsbPictureControlProbe.ProbeException) e).partialResult;
                    if (partial != null) saveReport(partial.toJsonString());
                }
                onUi(() -> {
                    if (recovery) {
                        busy = false; confirmation = null;
                        importOutcome = new ImportOutcome("结果仍待核对", readable(e) + "\n\n请关闭其他相机应用，连接上次的相机后重新只读核对。", pendingJournal == null ? "" : pendingJournal.optString("presetName"), pendingJournal == null ? "" : pendingJournal.optString("model"), pendingJournal == null ? -1 : pendingJournal.optInt("targetItem", -1), false, true); render();
                    } else { finishBusy("无法完成相机读取，未开始新的写入。"); notice("请检查相机连接", readable(e) + "\n关闭其他相机应用，重新连接 USB 后再读取。"); }
                });
            } finally { probe.close(); if (activeProbe == probe) activeProbe = null; }
        });
    }

    private List<UsbPictureControlProbe.ItemRecord> validateSnapshot(UsbPictureControlProbe.Result result) throws Exception {
        if (!result.completed || !result.sessionClosed || result.protocolPhaseUnconfirmed) throw new IOException("相机读取或会话关闭未完成，请重新连接后读取。");
        List<UsbPictureControlProbe.ItemRecord> slots = new ArrayList<>(); boolean[] seen = new boolean[9];
        for (UsbPictureControlProbe.ItemRecord item : result.items) {
            if (item.itemCode < 201 || item.itemCode > 209) continue;
            if (seen[item.itemCode - 201] || !item.parsed || !sha256(item.rawBytes()).equals(item.sha256)) throw new IOException("槽位记录不完整或校验失败，请重新读取。");
            PictureControlData.parse(item.rawBytes()); seen[item.itemCode - 201] = true; slots.add(item);
        }
        for (boolean present : seen) if (!present) throw new IOException("没有读全相机的九个槽位，请重新读取。");
        return slots;
    }

    private void chooseImportOptions(UsbDevice device, String model, List<UsbPictureControlProbe.ItemRecord> slots) {
        List<Integer> empty = new ArrayList<>(); StringBuilder occupied = new StringBuilder();
        for (UsbPictureControlProbe.ItemRecord item : slots) {
            if (item.empty) empty.add(item.itemCode);
            else occupied.append(slotLabel(item.itemCode)).append(" · ").append(item.registrationName).append('\n');
        }
        if (empty.isEmpty()) {
            importOutcome = new ImportOutcome("相机没有空槽", "C1–C9 均已使用。请先在相机上整理一个空槽，再回来导入。现有预设不会被覆盖。", selected.displayName, model, -1, false, false);
            confirmation = null; render(); return;
        }
        PendingConfirmation next = new PendingConfirmation(device, model, selected, selectedBytes, empty, occupied.toString());
        startBusy("正在准备导入信息…");
        worker.execute(() -> {
            try {
                NcpPictureControlConverter.Conversion conversion = NcpPictureControlConverter.convert(next.sourceBytes(), next.middle, next.clarity);
                if (!conversion.sourceSha256().equals(next.preset.sha256)) throw new IOException("文件与已选择的预设不一致，请重新选择。");
                onUi(() -> { next.conversion = conversion; confirmation = next; importOutcome = null; busy = false; render(); });
            } catch (Exception e) { onUi(() -> { finishBusy("兼容适配未完成，未写入相机。"); notice("无法准备导入", readable(e)); }); }
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
        informationRow(content, AppIcon.Kind.CAMERA, plan.model, "已连接，可导入预设");
        heading(content, "选择存储位置", 16);
        String[] choices = new String[plan.emptySlots.size()];
        for (int i = 0; i < choices.length; i++) choices[i] = slotLabel(plan.emptySlots.get(i)) + " · 空槽";
        Spinner target = spinner(content, choices, plan.targetIndex); target.setEnabled(!busy);
        label(content, "仅显示当前空槽，不覆盖已有预设。", 12, colors.secondary);
        LinearLayout compatibility = vertical(); compatibility.setPadding(dp(14), dp(6), dp(14), dp(10)); compatibility.setBackground(shape(colors.container, 10));
        LinearLayout compatibilityTitle = horizontal(); TextView compatibilityName = text("兼容适配", 16, true); compatibilityTitle.addView(compatibilityName, new LinearLayout.LayoutParams(0, -2, 1));
        compatibilityTitle.addView(textLink("查看并调整 ›", () -> editCompatibility(plan))); compatibility.addView(compatibilityTitle);
        TextView compatibilityValues = text("中等范围锐化 " + quarter(plan.middle) + " / 清晰度 " + quarter(plan.clarity), 13, false); compatibilityValues.setTextColor(colors.secondary); compatibility.addView(compatibilityValues);
        LinearLayout.LayoutParams compatibilityParams = new LinearLayout.LayoutParams(-1, -2); compatibilityParams.topMargin = dp(16); content.addView(compatibility, compatibilityParams);
        label(content, "兼容适配，效果可能与相机原生载入不同。", 12, colors.secondary);
        if (!plan.occupied.isEmpty()) {
            TextView more = label(content, "查看已使用槽位", 14, colors.primary); more.setMinHeight(dp(48)); more.setGravity(Gravity.CENTER_VERTICAL); more.setFocusable(true);
            more.setOnClickListener(v -> notice("已使用槽位", plan.occupied));
        }
        LinearLayout footer = vertical(); footer.setPadding(dp(16), dp(4), dp(16), dp(12));
        Button confirm = button("导入到 " + slotLabel(plan.target()), AppIcon.Kind.CAMERA, true, () -> {
            if (confirmation != plan || plan.conversion == null) return;
            writePreset(plan.device, plan.model, plan.preset, plan.sourceBytes(), plan.target(), plan.middle, plan.clarity, plan.conversion.dataSha256());
        });
        footer.addView(confirm); TextView footerNote = label(footer, "导入时保持连接，请勿在相机上更改槽位", 12, colors.secondary); footerNote.setGravity(Gravity.CENTER); body.addView(footer);
        target.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { if (!busy && confirmation == plan) { plan.targetIndex = position; confirm.setText("导入到 " + slotLabel(plan.target())); } }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
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

    private void finishImportScreen() { if (busy) return; importOutcome = null; confirmation = null; message = ""; render(); }
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

    private void writePreset(UsbDevice device, String model, PresetRecord preset, byte[] source, int target, int middle, int clarity, String expectedHash) {
        if (busy || recoveryBlocked) return;
        startBusy("正在导入并读回核对，请保持连接…");
        UsbPictureControlImport importer = new UsbPictureControlImport(); activeImport = importer;
        worker.execute(() -> {
            UsbPictureControlImport.Result result = null; Exception failure = null; boolean started = false; String storageWarning = "";
            try {
                if (!sha256(source).equals(preset.sha256)) throw new IOException("源预设校验失败。");
                JSONObject journal = new JSONObject().put("schemaVersion", 1).put("createdAt", System.currentTimeMillis())
                        .put("presetName", preset.displayName).put("sourceSha256", preset.sha256).put("expectedSha256", expectedHash)
                        .put("targetItem", target).put("model", model).put("vendorId", device.getVendorId()).put("productId", device.getProductId());
                // A durable intent record precedes the backend call. An interrupted process must not silently repeat a write.
                byte[] journalBytes = journal.toString(2).getBytes(StandardCharsets.UTF_8);
                writeAtomic(journalFile(), journalBytes);
                try (InputStream savedJournal = journalFile().openRead()) {
                    if (!Arrays.equals(journalBytes, readBounded(savedJournal, 32 * 1024))) throw new IOException("导入记录无法安全保存，已停止，未开始相机写入。");
                }
                pendingJournal = journal; recoveryBlocked = true; started = true;
                result = importer.importNcpPreset(device, usb, source.clone(), middle, clarity, target);
            } catch (Exception e) {
                failure = e;
                if (e instanceof UsbPictureControlImport.ImportException) result = ((UsbPictureControlImport.ImportException) e).partialResult;
            } finally {
                importer.close(); if (activeImport == importer) activeImport = null;
            }
            if (result != null) {
                try { storageWarning = saveReport(result.toJsonString()); }
                catch (RuntimeException e) { storageWarning = "\n报告生成失败。当前相机写入结果仍有效，请先核对槽位，勿重复导入。"; }
                // A reported write without verified readback remains blocked until an explicit read-only recovery.
                boolean settled = result.completed || !result.writeAttempted;
                if (settled && storageWarning.isEmpty()) { journalFile().delete(); pendingJournal = null; recoveryBlocked = false; }
            } else if (!started) recoveryBlocked = false;
            final UsbPictureControlImport.Result outcome = result; final Exception error = failure; final String warning = storageWarning; final boolean invoked = started;
            onUi(() -> {
                busy = false;
                boolean success = outcome != null && outcome.ncpRegistrationVerified && outcome.completed && outcome.readbackVerified && outcome.sessionClosed;
                confirmation = null;
                if (success) {
                    importOutcome = new ImportOutcome("已保存到 " + slotLabel(target) + " 并核对", "预设已保存，相机读回的数据与本次适配数据一致。" + warning, preset.displayName, model, target, true, recoveryBlocked, preset.sha256);
                } else {
                    boolean uncertain = invoked && (outcome == null || outcome.writeAttempted);
                    importOutcome = new ImportOutcome(uncertain ? "结果待核对" : "暂未导入", (error == null ? "相机尚未确认完整结果。" : readable(error))
                            + "\n\n" + (uncertain ? "相机可能已经保存预设，请先只读核对，不要重复导入。" : "未发起写入，请检查连接后重新开始。") + warning,
                            preset.displayName, model, target, false, uncertain);
                }
                message = ""; render();
            });
        });
    }

    private void completeRecovery(UsbPictureControlProbe.Result snapshot, List<UsbPictureControlProbe.ItemRecord> slots) throws Exception {
        JSONObject journal = pendingJournal;
        if (journal == null) throw new IOException("没有可读取的上次导入记录。");
        if (!journal.getString("model").equals(snapshot.model)) throw new IOException("连接的相机型号与上次导入记录不同，请连接原相机。");
        int target = journal.getInt("targetItem"); String expected = journal.getString("expectedSha256");
        UsbPictureControlProbe.ItemRecord observed = null;
        for (UsbPictureControlProbe.ItemRecord slot : slots) if (slot.itemCode == target) observed = slot;
        if (observed == null) throw new IOException("未读到上次操作的目标槽位。");
        boolean match = observed.sha256.equals(expected); boolean empty = observed.empty;
        JSONObject json = snapshot.toJson().put("consumerRecovery", true).put("pendingImport", journal)
                .put("expectedDataObserved", match).put("targetObservedEmpty", empty);
        String warning = saveReport(json.toString(2));
        // Even an occupied but different slot has been observed. Do not infer success or ever overwrite it.
        if (warning.isEmpty()) { journalFile().delete(); pendingJournal = null; recoveryBlocked = false; }
        final String text = match ? "已在 " + slotLabel(target) + " 读到与上次导入一致的数据。请在相机菜单选择它使用。"
                : empty ? slotLabel(target) + " 当前为空，没有读到上次导入的数据。可以重新选择预设并确认导入。"
                : slotLabel(target) + " 当前已有其他数据，与上次预期不一致。未修改相机；继续导入时会选择其他空槽。";
        onUi(() -> {
            busy = false; confirmation = null;
            if (detailId.isEmpty() && detailOnline == null && library.findBySha256(journal.optString("sourceSha256")) != null) detailId = journal.optString("sourceSha256");
            importOutcome = new ImportOutcome(match ? "已保存到 " + slotLabel(target) + " 并核对" : empty ? "目标槽位为空" : "目标槽位内容不同", text + warning,
                    journal.optString("presetName"), snapshot.model, target, match, recoveryBlocked, journal.optString("sourceSha256")); message = ""; render();
        });
    }

    private String saveReport(String json) {
        report = json;
        try {
            AtomicFile target = new AtomicFile(new File(getFilesDir(), "last-report.json")); byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            writeAtomic(target, bytes);
            try (InputStream input = target.openRead()) { if (!Arrays.equals(bytes, readBounded(input, 4 * 1024 * 1024))) throw new IOException("报告保存后校验失败。"); }
            return "";
        }
        catch (Exception e) { return "\n报告自动保存失败，当前相机结果仍有效。请从帮助导出报告，避免重复写入。"; }
    }

    private void exportReport() {
        if (busy || report.isEmpty()) return;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "nikon-usb-report.json"), EXPORT_REPORT);
    }

    private void showHelp() {
        List<String> labels = new ArrayList<>();
        labels.add("数据线导入说明"); labels.add("在线预设库地址"); labels.add("导出最近报告"); labels.add("高级诊断");
        if (recoveryBlocked) labels.add("核对上次导入 · 只读");
        labels.add("关于相机预设");
        new AlertDialog.Builder(this).setTitle("帮助与设置").setItems(labels.toArray(new String[0]), (d, w) -> {
            switch (w) {
                case 0: notice("数据线导入", "先选择预设，再用 USB 数据线连接已开机的尼康相机，并允许 App 访问。\n\nApp 会自动读取空槽，核对兼容适配摘要，确认后导入并读回验证。已占用槽位不会主动覆盖。\n\nUSB 导入与无线相册是不同连接方式；导入前会断开本 App 的无线相册连接。"); break;
                case 1: if (busy) notice("操作进行中", "请等待当前操作结束后再更改地址。"); else configureCatalog(); break;
                case 2: if (busy) notice("操作进行中", "结束后可导出报告。"); else if (report.isEmpty()) notice("暂无报告", "连接相机并执行读取或导入后可导出报告。"); else exportReport(); break;
                case 3: if (busy) notice("操作进行中", "请等待当前任务结束后打开高级诊断。"); else if (recoveryBlocked) notice("先核对上次导入", "请先使用只读核对确认上次操作的结果，再打开包含写入工具的高级诊断。"); else { album.disconnect(); startActivity(new Intent(this, DiagnosticsActivity.class)); } break;
                default: if (recoveryBlocked && w == 4) recoverImport(); else notice("相机预设", "预设浏览、本地管理与尼康相机导入。\n当前 USB 兼容通道以 Z6II 实测为基础，并不支持所有 NCP、NP2 或 NP3。\n\n在线库需自行配置，首批精选与真实样片尚未内置。无线相册支持范围以实际连接与读取结果为准。");
            }
        }).setNegativeButton("关闭", null).show();
    }

    private void switchTab(boolean showAlbum) {
        if (busy) return;
        confirmation = null; importOutcome = null;
        albumTab = showAlbum; detailId = ""; detailOnline = null; message = ""; render();
        if (!showAlbum && !localTab && !catalogLoaded) loadCatalog(false);
    }

    private void goBack() {
        if (busy) { notice("操作进行中", "请等待当前任务结束；导入期间保持数据线连接。"); return; }
        if (importOutcome != null || confirmation != null) finishImportScreen();
        else if (albumTab && album.canGoBack()) { album.goBack(); render(); }
        else if (!detailId.isEmpty() || detailOnline != null) { detailId = ""; detailOnline = null; message = ""; render(); }
        else if (albumTab) switchTab(false);
        else super.onBackPressed();
    }
    @Override public void onBackPressed() { goBack(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("local", localTab); out.putBoolean("album", albumTab);
        out.putString("detailId", detailId);
        out.putString("previewForId", previewForId); out.putBoolean("importAfterPick", importAfterPick);
        super.onSaveInstanceState(out);
    }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); render(); }

    private void startBusy(String value) { busy = true; progress = value; render(); }
    private void finishBusy(String value) { busy = false; progress = ""; message = value; render(); }
    private void onUi(Runnable task) { if (!destroyed) runOnUiThread(() -> { if (!destroyed) task.run(); }); }
    private String cameraConnectionText() {
        int count = usb == null ? 0 : UsbProbe.findNikonDevices(usb).size();
        return count == 0 ? "USB 未连接" : "USB 已连接";
    }
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
        GradientDrawable background = shape(primary ? colors.primary : colors.surface, 8); if (!primary) background.setStroke(dp(1), colors.outline);
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
        LinearLayout cell = vertical(); cell.setGravity(Gravity.CENTER); cell.setPadding(dp(12), dp(6), dp(12), dp(6)); cell.setMinimumHeight(dp(60));
        cell.setBackground(ripple(colors.surface)); cell.setEnabled(!busy); cell.setAlpha(busy ? .5f : 1); cell.setFocusable(true); cell.setContentDescription(value); cell.setSelected(active);
        ImageView icon = new ImageView(this); icon.setImageDrawable(new AppIcon(kind, active ? colors.primary : colors.secondary, getResources().getDisplayMetrics().density));
        cell.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24))); TextView label = text(value, 12, active); label.setGravity(Gravity.CENTER); label.setTextColor(active ? colors.primary : colors.secondary); label.setPadding(0, dp(4), 0, 0); cell.addView(label, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); params.leftMargin = dp(4); params.rightMargin = dp(4); parent.addView(cell, params);
        cell.setOnClickListener(v -> { if (!busy) click.run(); });
    }
    private void sourceTab(LinearLayout parent, String value, boolean active, Runnable click) {
        LinearLayout cell = vertical(); TextView tab = text(value, 15, active); tab.setGravity(Gravity.CENTER); tab.setTextColor(active ? colors.primary : colors.secondary);
        cell.addView(tab, new LinearLayout.LayoutParams(-1, dp(47))); View line = new View(this); line.setBackgroundColor(active ? colors.primary : colors.surface); cell.addView(line, new LinearLayout.LayoutParams(-1, dp(2)));
        cell.setBackground(ripple(colors.surface)); cell.setFocusable(true); cell.setSelected(active); cell.setContentDescription(value); cell.setOnClickListener(v -> { if (!busy) click.run(); });
        parent.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
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
        FileOutputStream output = null;
        try { output = file.startWrite(); output.write(bytes); output.getFD().sync(); file.finishWrite(output); }
        catch (IOException | RuntimeException e) { if (output != null) file.failWrite(output); throw e; }
    }
    private static String readText(AtomicFile file, int limit) throws IOException { try (InputStream input = file.openRead()) { return new String(readBounded(input, limit), StandardCharsets.UTF_8); } }

    @Override protected void onDestroy() {
        destroyed = true; if (activeProbe != null) activeProbe.close(); if (activeImport != null) activeImport.close();
        if (album != null) album.close(); worker.shutdownNow(); images.shutdownNow(); imageCache.evictAll();
        if (receiverRegistered) unregisterReceiver(receiver); super.onDestroy();
    }
}

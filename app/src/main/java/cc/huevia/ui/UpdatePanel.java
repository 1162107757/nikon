package cc.huevia.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ClipData;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import cc.huevia.updates.AppUpdateManager;
import java.util.Locale;

/** The progress views update in place; camera pages never rebuild for update-download ticks. */
public final class UpdatePanel implements AutoCloseable {
    public interface Host {
        void onChanged();
        boolean idleForAutomaticCheck();
        void onFeedback(String title, String message);
        void onAvailable(String version, Runnable show);
        void beforeInstall(Runnable proceed);
    }
    private final Activity activity;
    private final Host host;
    private final AppUpdateManager updates;
    private UiPalette colors;
    private boolean open, waitingPermission, pendingResume, hostVisible;
    private long installEpoch;
    private LinearLayout content;
    private TextView status, detail, releaseName, notes, channel;
    private ProgressBar progress;
    private Button primary, secondary, check;
    public UpdatePanel(Activity activity, Host host) {
        this.activity = activity; this.host = host;
        updates = new AppUpdateManager(activity, new AppUpdateManager.Listener() {
            @Override public void changed() { refresh(); }
            @Override public void available() { if (host.idleForAutomaticCheck() && updates.release != null) host.onAvailable(updates.release.name, UpdatePanel.this::open); }
        });
    }
    public boolean isOpen() { return open; }
    public void open() { open = true; host.onChanged(); if (updates.release == null && !updates.downloading) updates.check(false); }
    public void goBack() { installEpoch++; open = false; content = null; host.onChanged(); }
    public void automaticCheck() { updates.autoCheck(host.idleForAutomaticCheck()); }
    public void onResume() {
        hostVisible = true;
        updates.resume();
        if (waitingPermission) { waitingPermission = false;
            if (activity.getPackageManager().canRequestPackageInstalls()) pendingResume = true;
            else host.onFeedback("尚未允许安装", "可在系统设置中允许 Huevia 安装应用，再点击安装更新。");
        }
        if (pendingResume) { pendingResume = false; install(); }
    }
    public void onPause() { hostVisible = false; installEpoch++; updates.pause(); }
    public View createView(UiPalette palette) {
        colors = palette; ScrollView scroll = new ScrollView(activity); scroll.setClipToPadding(false);
        content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(22), dp(12), dp(22), dp(36)); scroll.addView(content);
        TextView heading = label("让 Huevia 保持更新", 25, true); content.addView(heading);
        TextView current = label("当前版本 " + version(), 14, false); current.setTextColor(colors.secondary); current.setPadding(0, dp(10), 0, dp(28)); content.addView(current);
        channel = label("", 15, true); channel.setMinHeight(dp(48)); channel.setGravity(Gravity.CENTER_VERTICAL); channel.setBackground(shape(colors.card, 18)); channel.setPadding(dp(16), 0, dp(16), 0); channel.setFocusable(true);
        channel.setOnClickListener(v -> chooseChannel()); content.addView(channel);
        LinearLayout panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(18), dp(20), dp(18), dp(20)); panel.setBackground(shape(colors.card, 24));
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(-1, -2); space.topMargin = dp(18); content.addView(panel, space);
        status = label("", 18, true); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); panel.addView(status);
        releaseName = label("", 15, true); releaseName.setPadding(0, dp(12), 0, dp(4)); panel.addView(releaseName);
        notes = label("", 15, false); notes.setLineSpacing(dp(4), 1); notes.setPadding(0, dp(8), 0, dp(20)); panel.addView(notes);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgressTintList(ColorStateList.valueOf(colors.primary)); panel.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));
        detail = label("", 13, false); detail.setPadding(0, dp(14), 0, dp(10)); detail.setTextColor(colors.secondary); panel.addView(detail);
        primary = action("", this::primaryAction, true); panel.addView(primary, new LinearLayout.LayoutParams(-1, dp(50)));
        secondary = action("取消下载", updates::cancel, false); LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(-1, dp(48)); margin.topMargin = dp(8); panel.addView(secondary, margin);
        check = action("重新检查", () -> updates.check(false), false); content.addView(check, margin);
        TextView explanation = label("下载完成后由你确认安装。正在直播、导入或传输照片时，请先完成任务；安装会关闭当前相机连接。", 13, false);
        explanation.setTextColor(colors.secondary); explanation.setLineSpacing(dp(4), 1); explanation.setPadding(dp(4), dp(20), dp(4), 0); content.addView(explanation);
        refresh(); return scroll;
    }
    private void chooseChannel() {
        if (updates.downloading || updates.checking || updates.verifying) { host.onFeedback("请稍后切换", "请先完成检查、取消下载或等待校验完成。"); return; }
        new AlertDialog.Builder(activity).setTitle("更新通道").setSingleChoiceItems(new String[]{"正式版", "测试版"}, "beta".equals(updates.channel()) ? 1 : 0,
                (dialog, which) -> { dialog.dismiss(); updates.channel(which == 1 ? "beta" : "stable"); }).setNegativeButton("取消", null).show();
    }
    private void primaryAction() {
        if (updates.verified) { install(); return; }
        if (updates.metered()) new AlertDialog.Builder(activity).setTitle("使用流量下载？")
                .setMessage("安装包约 " + size() + "，可能产生移动数据费用。")
                .setNegativeButton("稍后", null).setPositiveButton("继续下载", (d,w) -> updates.download(true)).show();
        else updates.download(false);
    }
    private void install() {
        final long token = ++installEpoch;
        updates.verifyForInstall(() -> {
            if (token == installEpoch && hostVisible && open && !activity.isFinishing())
                host.beforeInstall(() -> { if (token == installEpoch && hostVisible && open && !activity.isFinishing()) openInstaller(); });
        });
    }
    private void openInstaller() {
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity).setTitle("允许安装 Huevia 更新")
                    .setMessage("请在系统设置中允许此来源，返回后继续安装。")
                    .setNegativeButton("稍后", null).setPositiveButton("前往设置", (d,w) -> {
                        waitingPermission = true;
                        try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
                        catch (RuntimeException failure) { waitingPermission = false; host.onFeedback("无法打开安装设置", "请在系统设置中允许 Huevia 安装应用。"); }
                    }).show(); return;
        }
        Uri uri = updates.installerUri(); if (uri == null) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Huevia update", uri)); activity.startActivity(intent);
        } catch (RuntimeException failure) { host.onFeedback("无法打开安装程序", "安装包已保留，请稍后重试。"); }
    }
    private void refresh() {
        if (!open || content == null || status == null) return;
        AppUpdateManager.Release release = updates.release; boolean locked = updates.checking || updates.downloading || updates.verifying;
        channel.setText("更新通道 · " + ("beta".equals(updates.channel()) ? "测试版" : "正式版") + "  ›"); channel.setEnabled(!locked);
        status.setText(updates.status); releaseName.setText(release == null ? "" : release.name + " · " + size()); releaseName.setVisibility(release == null ? View.GONE : View.VISIBLE);
        notes.setText(release == null ? "" : release.notes); notes.setVisibility(release == null || release.notes.isEmpty() ? View.GONE : View.VISIBLE);
        progress.setVisibility(updates.downloading || updates.verifying || updates.checking ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(updates.verifying || updates.checking); progress.setProgress(updates.progress);
        detail.setText(!updates.error.isEmpty() ? updates.error : updates.downloading ? updates.progress + "% · " + size() : "");
        detail.setTextColor(updates.error.isEmpty() ? colors.secondary : colors.error); detail.setVisibility(detail.length() == 0 ? View.GONE : View.VISIBLE);
        primary.setText(updates.verified ? "安装更新" : updates.downloading ? "正在下载 · " + updates.progress + "%" : updates.verifying ? "正在校验…" : "下载更新");
        primary.setVisibility(release == null ? View.GONE : View.VISIBLE); primary.setEnabled(!locked); primary.setAlpha(locked ? .55f : 1);
        secondary.setVisibility(updates.downloading ? View.VISIBLE : View.GONE); check.setEnabled(!locked); check.setAlpha(locked ? .55f : 1);
    }
    private String size() { return updates.release == null ? "" : String.format(Locale.CHINA, "%.1f MiB", updates.release.bytes / 1048576d); }
    private String version() { try { return activity.getPackageManager().getPackageInfo(activity.getPackageName(),0).versionName; } catch (Exception ignored) { return ""; } }
    private TextView label(String value, int size, boolean bold) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(colors.text); if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return view; }
    private Button action(String value, Runnable click, boolean filled) { Button view = new Button(activity); view.setText(value); view.setTextSize(15); view.setAllCaps(false); view.setTextColor(filled ? colors.onAction : colors.text); view.setBackgroundTintList(null); view.setBackground(shape(filled ? colors.action : colors.container, 26)); view.setOnClickListener(v -> click.run()); return view; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable value = new GradientDrawable(); value.setColor(color); value.setCornerRadius(dp(radius)); return value; }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    @Override public void close() { content = null; updates.close(); }
}

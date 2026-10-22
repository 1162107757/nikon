package cc.huevia;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;

import cc.huevia.usb.UsbProbe;
import cc.huevia.usb.UsbNcpTransfer;
import cc.huevia.usb.UsbPictureControlProbe;
import cc.huevia.usb.UsbPictureControlImport;
import cc.huevia.picturecontrol.PictureControlData;
import cc.huevia.ncp.NcpFile;
import cc.huevia.ncp.NcpPictureControlConverter;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A capability probe and an explicitly selected experimental standard-PTP file transfer. */
public class DiagnosticsActivity extends Activity {
    private static final int OPEN_NCP = 10;
    private static final int SAVE_REPORT = 11;
    private static final int MAX_NCP_BYTES = 1024 * 1024;
    private static final String USB_PERMISSION = "cc.huevia.USB_PERMISSION";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final int background = Color.rgb(16, 18, 20);
    private final int foreground = Color.rgb(242, 242, 236);
    private final int muted = Color.rgb(161, 166, 170);
    private final int accent = Color.rgb(255, 212, 59);
    private UsbManager usbManager;
    private volatile UsbProbe activeProbe;
    private volatile UsbNcpTransfer activeTransfer;
    private volatile UsbPictureControlProbe activePictureControlProbe;
    private volatile UsbPictureControlImport activePictureControlImport;
    private enum UsbAction {
        CAPABILITIES, DIRECTORY_INSPECTION, FILE_TRANSFER,
        EXISTING_DIRECTORY_TRANSFER, PICTURE_CONTROL_READ, PICTURE_CONTROL_COPY, NCP_COMPATIBILITY_IMPORT
    }
    private UsbDevice pendingDevice;
    private UsbAction pendingAction;
    private PictureControlCopy pendingCopy;
    private NcpImportPlan pendingNcpImport;
    private volatile boolean destroyed;
    private boolean busy;
    private boolean receiverRegistered;
    private TextView statusText, presetText, resultText;
    private Button probeButton, directoryProbeButton, pictureControlButton, fileButton,
            saveButton, transferButton, directoryExperimentButton, pictureControlCopyButton, ncpImportButton;
    private String report;
    private JSONObject preset;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (USB_PERMISSION.equals(intent.getAction())) {
                if (pendingDevice == null || device == null
                        || device.getDeviceId() != pendingDevice.getDeviceId()) return;
                pendingDevice = null;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        && usbManager.hasPermission(device)) {
                    runUsbAction(device, pendingAction);
                } else {
                    setBusy(false);
                    showUsbPrerequisite("未获得 USB 访问权限", "请保持相机开机及数据线连接，重新检测后在系统弹窗中允许访问相机。检测只读取能力，原导入或试传需重新确认。");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbProbe probe = activeProbe;
                if (probe != null && device != null && probe.isProbingDevice(device)) {
                    probe.close();
                }
                UsbNcpTransfer transfer = activeTransfer;
                if (transfer != null && device != null && transfer.isProbingDevice(device)) {
                    transfer.close();
                }
                UsbPictureControlProbe pictureControlProbe = activePictureControlProbe;
                if (pictureControlProbe != null && device != null && pictureControlProbe.isProbingDevice(device)) {
                    pictureControlProbe.close();
                }
                UsbPictureControlImport pictureControlImport = activePictureControlImport;
                if (pictureControlImport != null && device != null && pictureControlImport.isProbingDevice(device)) {
                    pictureControlImport.close();
                }
                if (pendingDevice != null && device != null
                        && pendingDevice.getDeviceId() == device.getDeviceId()) {
                    pendingDevice = null;
                    setBusy(false);
                    showUsbPrerequisite("相机已断开", "请重新连接支持数据传输的 USB 线，确认相机开机，再检测并允许 USB 访问权限。");
                }
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        makeUi();
        IntentFilter filter = new IntentFilter(USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        receiverRegistered = true;
        restorePreset();
        restoreReport();
    }

    private void makeUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(background);
        scroll.setFillViewport(true);
        scroll.setFitsSystemWindows(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(32));
        scroll.addView(root);
        addText(root, getString(R.string.app_name) + " / USB LAB · " + appVersion(), 12, accent, true);
        TextView title = addText(root, "一根线，连接你的预设", 28, foreground, true);
        title.setPadding(0, dp(12), 0, dp(10));
        addText(root, "目标设备：Nikon Z6II · 安卓手机", 14, muted, false);
        addText(root, "实验版本：机内复制已获成功反馈，新增 NCP 兼容试导入。请先读取空槽，再预览适配参数。", 14, accent, false)
                .setPadding(0, dp(16), 0, dp(16));

        LinearLayout connection = card(root);
        addText(connection, "01  连接相机", 18, foreground, true);
        addText(connection, "打开相机，用支持数据传输的 USB 线连接手机。手机需支持 USB 主机 / OTG。先关闭占用相机连接的其他应用。", 14, muted, false);
        probeButton = button(connection, "检测相机 · 读取能力", true);
        probeButton.setOnClickListener(v -> detectCamera(UsbAction.CAPABILITIES));
        pictureControlButton = button(connection, "读取机内优化校准 · 只读", true);
        pictureControlButton.setOnClickListener(v -> detectCamera(UsbAction.PICTURE_CONTROL_READ));
        addText(connection, "读取标准优化校准与 C-1 至 C-9 的现有数据，识别空槽。无需选择 NCP 文件；不会修改机内预设。", 13, muted, false)
                .setPadding(0, dp(12), 0, 0);
        directoryProbeButton = button(connection, "目录诊断 · 只读", false);
        directoryProbeButton.setOnClickListener(v -> detectCamera(UsbAction.DIRECTORY_INSPECTION));
        addText(connection, "已在相机导出预设，但 App 找不到 NIKON 目录时，使用“目录诊断”。结果记录 USB 实际列出的目录，便于判断访问范围。", 13, muted, false)
                .setPadding(0, dp(12), 0, 0);
        statusText = addText(connection, "等待检测", 15, foreground, false);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        LinearLayout files = card(root);
        addText(files, "02  准备 NCP 文件", 18, foreground, true);
        presetText = addText(files, "尚未选择文件。检测 USB 能力不需要先选择预设。", 14, muted, false);
        fileButton = button(files, "选择 NCP 文件", false);
        fileButton.setOnClickListener(v -> chooseNcpFile());
        ncpImportButton = button(files, "NCP 兼容试导入 · 机内空槽", true);
        ncpImportButton.setOnClickListener(v -> chooseNcpImportOptions());
        addText(files, "保留已支持的 NCP 参数和曲线，预览并指定旧文件没有的中等范围锐化、清晰度，再直接登记到空槽。尚未验证与相机原生载入效果完全一致。", 13, muted, false)
                .setPadding(0, dp(12), 0, 0);
        transferButton = button(files, "原通道：试传到存储卡", false);
        transferButton.setOnClickListener(v -> {
            if (!requirePreset()) return;
            new AlertDialog.Builder(this)
                .setTitle("试传 NCP 文件")
                .setMessage("将通过 USB 尝试写入相机卡的 NIKON/CUSTOMPC，使用目录列表中未占用的编号。可能创建文件夹；不请求覆盖或删除。\n\n普通模式遇到只读标志会停止。这条路径尚未完成 Z6II 真机验证，传输后仍需在相机菜单中载入预设。")
                .setNegativeButton("取消", null)
                .setPositiveButton("试传", (dialog, which) -> detectCamera(UsbAction.FILE_TRANSFER)).show();
        });
        addText(files, "目标目录：SD 卡根目录下的 NIKON/CUSTOMPC。若普通试传因只读标志停止，可使用下方入口实测现存目录。先断开手机，在相机的“管理优化校准 → 载入/保存 → 复制到存储卡”中导出一个自定义预设到 SD，再连接手机。", 13, muted, false)
                .setPadding(0, dp(14), 0, 0);
        directoryExperimentButton = button(files, "指定目录实测", false);
        directoryExperimentButton.setOnClickListener(v -> {
            if (!requirePreset()) return;
            new AlertDialog.Builder(this)
                .setTitle("实测现存的 NIKON/CUSTOMPC")
                .setMessage("此入口会在 Z6II 报告“只读，可删除”时，仍向已存在且可被 USB 读取到的 NIKON/CUSTOMPC 尝试新增 NCP，记录相机真实返回码。目录不可见时停止，不会创建目录。\n\n使用目录列表中未占用的编号，不请求覆盖、删除或修改机内预设槽。相机若只显示部分文件，列表未占用不等于卡上一定没有同名文件。连接中断可能留下空文件或未完成文件，失败后请先查看报告。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始实测", (dialog, which) -> detectCamera(UsbAction.EXISTING_DIRECTORY_TRANSFER)).show();
        });

        LinearLayout pictureControls = card(root);
        addText(pictureControls, "03  机内复制 · 空槽实测", 18, foreground, true);
        addText(pictureControls, "先使用上方“读取机内优化校准”，再选择一个已有自定义预设和一个空槽。此入口会实际登记预设并读回核对，用于验证专用写入；尚未转换或写入所选 NCP 文件。", 14, muted, false);
        pictureControlCopyButton = button(pictureControls, "选择机内预设与空槽", true);
        pictureControlCopyButton.setOnClickListener(v -> choosePictureControlCopy());

        LinearLayout results = card(root);
        addText(results, "04  验证结果", 18, foreground, true);
        resultText = addText(results, "先读取机内优化校准，再按需实测空槽复制。每次完成或失败后，请保存诊断报告。", 14, muted, false);
        resultText.setTextIsSelectable(true);
        saveButton = button(results, "保存诊断报告", false);
        saveButton.setOnClickListener(v -> {
            if (busy) return;
            if (report == null) {
                new AlertDialog.Builder(this).setTitle("先生成诊断报告")
                        .setMessage("请先连接 USB 相机并完成一次检测，生成报告后再保存。检测仅用于读取能力，不会自动继续导入或试传。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("检测相机", (dialog, which) -> detectCamera(UsbAction.CAPABILITIES)).show();
                return;
            }
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "nikon-usb-report.json");
            try { startActivityForResult(intent, SAVE_REPORT); }
            catch (RuntimeException unavailable) {
                new AlertDialog.Builder(this).setTitle("无法选择报告保存位置")
                        .setMessage("手机未提供可用的文件保存界面，请检查系统文件应用后重试。当前报告仍保留在本机。")
                        .setPositiveButton("好", null).show();
            }
        });
        addText(root, "文件与报告保存在本机。报告不包含相机序列号。", 12, muted, false);
        setContentView(scroll);
    }

    private void chooseNcpFile() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try { startActivityForResult(intent, OPEN_NCP); }
        catch (RuntimeException unavailable) {
            new AlertDialog.Builder(this).setTitle("无法选择文件")
                    .setMessage("手机未提供可用的文件选择器，请检查系统文件应用后重试。")
                    .setPositiveButton("好", null).show();
        }
    }

    private boolean requirePreset() {
        if (busy) return false;
        if (preset != null) return true;
        new AlertDialog.Builder(this).setTitle("先选择 NCP 文件")
                .setMessage("选择并检查 NCP 文件后，再点击这项操作。选择文件不会自动导入或试传。")
                .setNegativeButton("取消", null)
                .setPositiveButton("选择 NCP 文件", (dialog, which) -> chooseNcpFile()).show();
        return false;
    }

    private void showUsbPrerequisite(String title, String message) {
        statusText.setText(message);
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("检测相机", (dialog, which) -> detectCamera(UsbAction.CAPABILITIES)).show();
    }

    private void showPictureControlPrerequisite(String title, Exception error) {
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage(error.getMessage() + "\n\n先只读获取完整的机内预设与空槽报告，再重新点击原操作。读取不会自动复制或导入预设。")
                .setNegativeButton("取消", null)
                .setPositiveButton("读取机内优化校准", (dialog, which) -> detectCamera(UsbAction.PICTURE_CONTROL_READ)).show();
    }

    private void detectCamera(UsbAction action) {
        if (busy) return;
        if ((action == UsbAction.FILE_TRANSFER || action == UsbAction.EXISTING_DIRECTORY_TRANSFER)
                && !requirePreset()) return;
        List<UsbDevice> devices = UsbProbe.findNikonDevices(usbManager);
        if (devices.isEmpty()) {
            showUsbPrerequisite("先连接 USB 相机", "未发现尼康相机。请开机并使用支持数据传输的 USB 线连接手机，确认已启用 OTG（如有此设置）。连接后检测相机，并在系统弹窗中允许访问 USB。");
            return;
        }
        if (devices.size() == 1) requestProbe(devices.get(0), action);
        else {
            String[] choices = new String[devices.size()];
            for (int i = 0; i < devices.size(); i++) {
                UsbDevice device = devices.get(i);
                choices[i] = "Nikon USB " + String.format(Locale.ROOT, "%04X", device.getProductId());
            }
            new AlertDialog.Builder(this).setTitle("选择相机")
                    .setItems(choices, (dialog, which) -> requestProbe(devices.get(which), action)).show();
        }
    }

    private void requestProbe(UsbDevice device, UsbAction action) {
        setBusy(true);
        pendingAction = action;
        statusText.setText("正在连接相机…");
        if (usbManager.hasPermission(device)) {
            runUsbAction(device, action);
        }
        else {
            pendingDevice = device;
            statusText.setText("等待 USB 访问权限，请在系统弹窗中允许访问相机…");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
            PendingIntent permission = PendingIntent.getBroadcast(this, device.getDeviceId(),
                    new Intent(USB_PERMISSION).setPackage(getPackageName()), flags);
            try { usbManager.requestPermission(device, permission); }
            catch (Exception error) {
                pendingDevice = null;
                setBusy(false);
                showUsbPrerequisite("无法申请 USB 访问权限", "请检查数据线连接与系统 USB 权限后重新检测。\n" + error.getMessage());
            }
        }
    }

    private void runUsbAction(UsbDevice device, UsbAction action) {
        switch (action) {
            case FILE_TRANSFER: runTransfer(device, false); break;
            case EXISTING_DIRECTORY_TRANSFER: runTransfer(device, true); break;
            case DIRECTORY_INSPECTION: runProbe(device, true); break;
            case PICTURE_CONTROL_READ: runPictureControlProbe(device); break;
            case PICTURE_CONTROL_COPY: runPictureControlCopy(device); break;
            case NCP_COMPATIBILITY_IMPORT: runNcpImport(device); break;
            default: runProbe(device, false);
        }
    }

    private void choosePictureControlCopy() {
        if (busy) return;
        try {
            PictureControlSnapshot snapshot = readPictureControlSnapshot();
            List<PictureControlChoice> sources = snapshot.sources;
            List<Integer> targets = snapshot.targets;
            if (sources.isEmpty()) throw new IllegalStateException("没有本次实验支持的机内自定义预设，请先读取并检查结果。");
            String[] sourceNames = new String[sources.size()];
            for (int i = 0; i < sources.size(); i++) sourceNames[i] = sources.get(i).displayName();
            new AlertDialog.Builder(this).setTitle("选择要复制的机内预设")
                    .setItems(sourceNames, (dialog, which) -> {
                        PictureControlChoice source = sources.get(which);
                        String[] targetNames = new String[targets.size()];
                        for (int i = 0; i < targets.size(); i++) targetNames[i] = slotLabel(targets.get(i)) + " · 读取时为空";
                        new AlertDialog.Builder(this).setTitle("选择目标空槽")
                                .setItems(targetNames, (targetDialog, targetWhich) -> confirmPictureControlCopy(source, targets.get(targetWhich)))
                                .setNegativeButton("取消", null).show();
                    }).setNegativeButton("取消", null).show();
        } catch (Exception error) {
            showPictureControlPrerequisite("先读取机内预设与空槽", error);
        }
    }

    private PictureControlSnapshot readPictureControlSnapshot() throws Exception {
            if (report == null) throw new IllegalStateException("请先点击“读取机内优化校准 · 只读”。");
            JSONObject json = new JSONObject(report);
            if (!"nikon_picture_control_read_only".equals(json.optString("probeMode"))
                    || !json.optBoolean("completed") || !json.optBoolean("sessionCloseConfirmed")
                    || json.optBoolean("protocolPhaseUnconfirmed")) {
                throw new IllegalStateException("请先重新执行“读取机内优化校准 · 只读”，取得完整结果后再选择目标。");
            }
            List<PictureControlChoice> sources = new ArrayList<>();
            List<Integer> targets = new ArrayList<>();
            boolean[] seen = new boolean[9];
            JSONArray items = json.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                int code = item.getInt("itemCode");
                if (code < 201 || code > 209) continue;
                if (seen[code - 201] || !item.optBoolean("parsed")) {
                    throw new IllegalStateException("槽位记录不完整或重复，请重新执行只读检查。");
                }
                seen[code - 201] = true;
                byte[] bytes = Base64.getDecoder().decode(item.getString("rawBase64"));
                if (!hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(item.getString("sha256"))) {
                    throw new IllegalStateException("本机槽位快照校验失败，请重新读取。");
                }
                PictureControlData data = PictureControlData.parse(bytes);
                if (data.hasNoBasePictureControl()) targets.add(code);
                else if (data.basePictureControl() >= 1 && data.basePictureControl() <= 6) {
                    sources.add(new PictureControlChoice(code, data.registrationName(), bytes));
                }
            }
            for (boolean present : seen) if (!present) {
                throw new IllegalStateException("缺少部分槽位记录，请重新执行只读检查。");
            }
            if (targets.isEmpty()) throw new IllegalStateException("没有空的自定义槽位，已停止；不会请求覆盖现有预设。");
            return new PictureControlSnapshot(sources, targets);
    }

    private static final class PictureControlSnapshot {
        final List<PictureControlChoice> sources;
        final List<Integer> targets;
        PictureControlSnapshot(List<PictureControlChoice> sources, List<Integer> targets) {
            this.sources = sources;
            this.targets = targets;
        }
    }

    private void chooseNcpImportOptions() {
        if (!requirePreset()) return;
        try {
            List<Integer> targets = readPictureControlSnapshot().targets;
            LinearLayout options = new LinearLayout(this);
            options.setOrientation(LinearLayout.VERTICAL);
            options.setPadding(dp(20), dp(8), dp(20), dp(4));
            addText(options, "旧 NCP 未包含下面两项。请明确选择适配值；这不是相机原生载入的补全规则。", 14, muted, false);
            addText(options, "目标空槽", 14, foreground, false);
            String[] targetLabels = new String[targets.size()];
            for (int i = 0; i < targets.size(); i++) targetLabels[i] = slotLabel(targets.get(i)) + " · 读取时为空";
            Spinner target = choiceSpinner(options, targetLabels, 0);
            addText(options, "中等范围锐化（默认适配值 1）", 14, foreground, false);
            Spinner middle = choiceSpinner(options, quarterStepLabels(0, 20), 4);
            addText(options, "清晰度（默认适配值 0）", 14, foreground, false);
            Spinner clarity = choiceSpinner(options, quarterStepLabels(-20, 20), 20);
            new AlertDialog.Builder(this).setTitle("NCP 兼容适配参数").setView(options)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("生成预览", (dialog, which) -> prepareNcpImport(
                            targets.get(target.getSelectedItemPosition()), middle.getSelectedItemPosition(),
                            clarity.getSelectedItemPosition() - 20)).show();
        } catch (Exception error) { showPictureControlPrerequisite("先读取机内预设与空槽", error); }
    }

    private Spinner choiceSpinner(LinearLayout parent, String[] choices, int selection) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selection);
        parent.addView(spinner, new LinearLayout.LayoutParams(-1, dp(48)));
        return spinner;
    }

    private static String[] quarterStepLabels(int minimum, int maximum) {
        String[] labels = new String[maximum - minimum + 1];
        for (int i = 0; i < labels.length; i++) labels[i] = String.format(Locale.ROOT, "%.2f", (minimum + i) / 4.0);
        return labels;
    }

    private void prepareNcpImport(int targetItem, int middleQuarter, int clarityQuarter) {
        if (!requirePreset()) return;
        JSONObject selected = preset;
        setBusy(true);
        statusText.setText("正在生成 NCP 兼容预览，请稍候…");
        worker.execute(() -> {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(new File(getFilesDir(), "selected.ncp").toPath());
                String hash = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (!hash.equals(selected.optString("sha256"))) {
                    throw new IllegalStateException("本机 NCP 与已选择文件的校验值不一致，请重新选择。");
                }
                NcpPictureControlConverter.Conversion conversion = NcpPictureControlConverter.convert(bytes, middleQuarter, clarityQuarter);
                NcpImportPlan plan = new NcpImportPlan(bytes, selected.optString("fileName"), hash,
                        targetItem, middleQuarter, clarityQuarter);
                onUi(() -> {
                    setBusy(false);
                    statusText.setText("兼容预览已生成，请检查目标与适配参数后确认。");
                    new AlertDialog.Builder(this).setTitle("确认向 " + slotLabel(targetItem) + " 导入")
                            .setMessage("文件：" + plan.fileName + "\n目标：" + slotLabel(targetItem)
                                    + "\n\n" + conversion.toDisplayText()
                                    + "\n\n这是兼容适配，尚未验证与相机原生载入效果一致。确认后会复查目标连续两次为空，再登记一次并读回核对。"
                                    + "\n操作期间请勿更改相机槽位或断开连接。若写入或读回未完成，先保存报告并重新只读检查。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("导入到 " + slotLabel(targetItem), (dialog, which) -> {
                                pendingNcpImport = plan;
                                detectCamera(UsbAction.NCP_COMPATIBILITY_IMPORT);
                            }).show();
                });
            } catch (Exception error) { onUi(() -> {
                setBusy(false);
                statusText.setText("兼容预览未生成，请检查 NCP 文件与适配参数。");
                showImportError("无法生成兼容预览", error);
            }); }
        });
    }

    private void runNcpImport(UsbDevice device) {
        NcpImportPlan plan = pendingNcpImport;
        pendingNcpImport = null;
        if (plan == null) {
            setBusy(false);
            statusText.setText("没有已确认的 NCP 导入预览。");
            return;
        }
        UsbPictureControlImport importer = new UsbPictureControlImport();
        activePictureControlImport = importer;
        statusText.setText("正在转换 NCP、复查空槽并登记，请保持连接…");
        worker.execute(() -> {
            try {
                UsbPictureControlImport.Result result = importer.importNcpPreset(device, usbManager,
                        plan.ncpBytes, plan.middleQuarter, plan.clarityQuarter, plan.targetItem);
                JSONObject json = result.toJson();
                addNcpImportContext(json, plan);
                String saved = json.toString(2);
                String persistenceWarning = persistReport(saved);
                onUi(() -> {
                    report = saved;
                    resultText.setText(result.toDisplayText() + persistenceWarning);
                    statusText.setText(result.completed && result.readbackVerified
                            ? "NCP 兼容数据已登记到 " + slotLabel(plan.targetItem) + " 并读回核对。请保存报告，在相机中检查拍摄效果。"
                            : "登记后的核对或收尾未完成，请保存报告并重新只读检查。");
                    saveButton.setEnabled(!busy);
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                try {
                    UsbPictureControlImport.ImportException importError = error instanceof UsbPictureControlImport.ImportException
                            ? (UsbPictureControlImport.ImportException) error : null;
                    UsbPictureControlImport.Result partial = importError == null ? null : importError.partialResult;
                    JSONObject json = partial == null ? new JSONObject() : partial.toJson();
                    addNcpImportContext(json, plan);
                    json.put("error", message);
                    if (importError != null && importError.responseCode != null) {
                        json.put("ptpResponseCode", String.format(Locale.ROOT, "0x%04X", importError.responseCode));
                    }
                    String saved = json.toString(2);
                    String persistenceWarning = persistReport(saved);
                    String partialText = partial == null ? "请保存报告并重新只读检查目标槽；不要盲目重复写入。" : partial.toDisplayText();
                    onUi(() -> {
                        report = saved;
                        resultText.setText(partialText + persistenceWarning);
                        saveButton.setEnabled(!busy);
                    });
                } catch (Exception ignored) { /* Keep the primary failure visible. */ }
                onUi(() -> statusText.setText("NCP 兼容导入未完成：" + message));
            } finally {
                try { importer.close(); }
                finally {
                    if (activePictureControlImport == importer) activePictureControlImport = null;
                    onUi(() -> setBusy(false));
                }
            }
        });
    }

    private void addNcpImportContext(JSONObject json, NcpImportPlan plan) throws org.json.JSONException {
        json.put("appVersion", appVersion());
        json.put("operationPurpose", "ncp_compatibility_import");
        json.put("selectedNcpFileName", plan.fileName);
        json.put("selectedNcpSha256", plan.sha256);
        json.put("requestedTargetItem", plan.targetItem);
        json.put("adaptationMiddleQuarter", plan.middleQuarter);
        json.put("adaptationClarityQuarter", plan.clarityQuarter);
        json.put("conversionEquivalentToNative", false);
        json.put("photoEffectVerified", false);
    }

    private void showImportError(String title, Exception error) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(error.getMessage()).setPositiveButton("好", null).show();
    }

    private static final class NcpImportPlan {
        final byte[] ncpBytes;
        final String fileName;
        final String sha256;
        final int targetItem;
        final int middleQuarter;
        final int clarityQuarter;
        NcpImportPlan(byte[] bytes, String fileName, String sha256, int target, int middle, int clarity) {
            ncpBytes = bytes.clone();
            this.fileName = fileName;
            this.sha256 = sha256;
            targetItem = target;
            middleQuarter = middle;
            clarityQuarter = clarity;
        }
    }

    private void confirmPictureControlCopy(PictureControlChoice source, int targetItem) {
        new AlertDialog.Builder(this).setTitle("确认向 " + slotLabel(targetItem) + " 登记预设")
                .setMessage("来源：" + source.displayName() + "\n目标：" + slotLabel(targetItem)
                        + "\n\nApp 会重新读取槽位，确认来源未变，且目标连续两次报告为空，再发送一次登记请求并读回核对。"
                        + "\n\n此步骤会实际修改目标槽。操作期间请勿在相机或其他应用中更改这些槽位，也不要断开数据线。若出现写入或读回错误，先保存报告并重新只读检查，不要重复写入。"
                        + "\n\n这次复制机内预设，不会把手机中选定的 NCP 导入相机。")
                .setNegativeButton("取消", null)
                .setPositiveButton("复制到 " + slotLabel(targetItem), (dialog, which) -> {
                    pendingCopy = new PictureControlCopy(source, targetItem);
                    detectCamera(UsbAction.PICTURE_CONTROL_COPY);
                }).show();
    }

    private void runPictureControlCopy(UsbDevice device) {
        PictureControlCopy copy = pendingCopy;
        pendingCopy = null;
        if (copy == null) {
            setBusy(false);
            statusText.setText("未选择要复制的机内预设与空槽。");
            return;
        }
        UsbPictureControlImport importer = new UsbPictureControlImport();
        activePictureControlImport = importer;
        statusText.setText("正在复查来源和空槽，随后登记并读回，请保持连接…");
        worker.execute(() -> {
            try {
                UsbPictureControlImport.Result result = importer.importPreset(device, usbManager,
                        copy.source.bytes, copy.source.itemCode, copy.targetItem);
                JSONObject json = result.toJson();
                json.put("appVersion", appVersion());
                json.put("operationPurpose", "copy_existing_camera_picture_control");
                json.put("ncpImportVerified", false);
                String saved = json.toString(2);
                String persistenceWarning = persistReport(saved);
                onUi(() -> {
                    report = saved;
                    resultText.setText(result.toDisplayText() + persistenceWarning);
                    statusText.setText(result.completed && result.readbackVerified
                            ? "已复制到 " + slotLabel(copy.targetItem) + " 并读回核对。请保存报告，再在相机中检查。"
                            : "写入后的精确核对未完成。请保存报告并重新只读检查，勿重复写入。");
                    saveButton.setEnabled(!busy);
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                try {
                    UsbPictureControlImport.ImportException importError = error instanceof UsbPictureControlImport.ImportException
                            ? (UsbPictureControlImport.ImportException) error : null;
                    UsbPictureControlImport.Result partial = importError == null ? null : importError.partialResult;
                    JSONObject json = partial == null ? new JSONObject() : partial.toJson();
                    json.put("appVersion", appVersion());
                    json.put("operationPurpose", "copy_existing_camera_picture_control");
                    json.put("ncpImportVerified", false);
                    json.put("error", message);
                    json.put("requestedSourceItem", copy.source.itemCode);
                    json.put("requestedTargetItem", copy.targetItem);
                    if (importError != null && importError.responseCode != null) {
                        json.put("ptpResponseCode", String.format(Locale.ROOT, "0x%04X", importError.responseCode));
                    }
                    String saved = json.toString(2);
                    String persistenceWarning = persistReport(saved);
                    String partialText = partial == null ? "请保存报告并重新读取槽位，确认目标状态后再继续。" : partial.toDisplayText();
                    onUi(() -> {
                        report = saved;
                        resultText.setText(partialText + persistenceWarning);
                        saveButton.setEnabled(!busy);
                    });
                } catch (Exception ignored) { /* Preserve the primary operation failure. */ }
                onUi(() -> statusText.setText("机内复制未完成：" + message));
            } finally {
                try { importer.close(); }
                finally {
                    if (activePictureControlImport == importer) activePictureControlImport = null;
                    onUi(() -> setBusy(false));
                }
            }
        });
    }

    private static String slotLabel(int itemCode) { return "C-" + (itemCode - 200); }

    private static final class PictureControlChoice {
        final int itemCode;
        final String name;
        final byte[] bytes;
        PictureControlChoice(int itemCode, String name, byte[] bytes) {
            this.itemCode = itemCode;
            this.name = name;
            this.bytes = bytes.clone();
        }
        String displayName() { return slotLabel(itemCode) + " · " + name; }
    }

    private static final class PictureControlCopy {
        final PictureControlChoice source;
        final int targetItem;
        PictureControlCopy(PictureControlChoice source, int targetItem) {
            this.source = source;
            this.targetItem = targetItem;
        }
    }

    private void runPictureControlProbe(UsbDevice device) {
        UsbPictureControlProbe probe = new UsbPictureControlProbe();
        activePictureControlProbe = probe;
        statusText.setText("正在读取机内优化校准，请保持相机开机和数据线连接…");
        worker.execute(() -> {
            try {
                UsbPictureControlProbe.Result result = probe.probe(device, usbManager);
                JSONObject json = result.toJson();
                json.put("appVersion", appVersion());
                json.put("presetWriteAttempted", false);
                json.put("ncpImportVerified", false);
                if (preset != null) json.put("selectedPreset", preset);
                String saved = json.toString(2);
                String persistenceWarning = persistReport(saved);
                onUi(() -> {
                    report = saved;
                    resultText.setText(result.toDisplayText() + persistenceWarning);
                    statusText.setText("机内优化校准读取完成。请保存诊断报告，以便继续验证预设转换与导入。");
                    saveButton.setEnabled(!busy);
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                try {
                    UsbPictureControlProbe.ProbeException probeError = error instanceof UsbPictureControlProbe.ProbeException
                            ? (UsbPictureControlProbe.ProbeException) error : null;
                    UsbPictureControlProbe.Result partial = probeError == null ? null : probeError.partialResult;
                    JSONObject json = partial == null ? new JSONObject() : partial.toJson();
                    json.put("error", message);
                    json.put("appVersion", appVersion());
                    json.put("pictureControlInspectionRequested", true);
                    json.put("presetWriteAttempted", false);
                    json.put("ncpImportVerified", false);
                    if (probeError != null && probeError.responseCode != null) {
                        json.put("ptpResponseCode", String.format(Locale.ROOT, "0x%04X", probeError.responseCode));
                    }
                    if (preset != null) json.put("selectedPreset", preset);
                    String saved = json.toString(2);
                    String persistenceWarning = persistReport(saved);
                    String partialText = partial == null ? "" : partial.toDisplayText() + "\n\n";
                    onUi(() -> {
                        report = saved;
                        resultText.setText(partialText + "本次只读取数据，没有修改机内预设。" + persistenceWarning);
                        saveButton.setEnabled(!busy);
                    });
                } catch (Exception ignored) { /* Keep the original camera error visible. */ }
                onUi(() -> statusText.setText("机内优化校准读取未完成：" + message));
            } finally {
                try { probe.close(); }
                finally {
                    if (activePictureControlProbe == probe) activePictureControlProbe = null;
                    onUi(() -> setBusy(false));
                }
            }
        });
    }

    private void runProbe(UsbDevice device, boolean directoryInspection) {
        UsbProbe probe = new UsbProbe();
        activeProbe = probe;
        statusText.setText(directoryInspection ? "正在进行只读目录诊断，请保持连接…" : "正在读取相机能力…");
        worker.execute(() -> {
            try {
                UsbProbe.Result result = probe.probe(device, usbManager, directoryInspection);
                JSONObject json = result.toJson();
                json.put("presetWriteAttempted", false);
                json.put("ncpImportVerified", false);
                json.put("appVersion", appVersion());
                if (preset != null) json.put("selectedPreset", preset);
                String saved = json.toString(2);
                String persistenceWarning = persistReport(saved);
                onUi(() -> {
                    report = saved;
                    resultText.setText(result.toDisplayText() + persistenceWarning);
                    statusText.setText(directoryInspection
                            ? "只读目录诊断完成。请查看下方结果并保存诊断报告；未执行上传。"
                            : "USB 读取完成。尚未验证或执行 NCP 导入。");
                    saveButton.setEnabled(!busy);
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                try {
                    UsbProbe.ProbeException probeError = error instanceof UsbProbe.ProbeException
                            ? (UsbProbe.ProbeException) error : null;
                    UsbProbe.Result partial = probeError == null ? null : probeError.partialResult;
                    JSONObject json = partial == null ? new JSONObject() : partial.toJson();
                    json.put("error", message);
                    json.put("appVersion", appVersion());
                    if (probeError != null && probeError.responseCode != null) {
                        json.put("ptpResponseCode", String.format(Locale.ROOT, "0x%04X", probeError.responseCode));
                    }
                    json.put("presetWriteAttempted", false);
                    json.put("ncpImportVerified", false);
                    json.put("directoryInspectionRequested", directoryInspection);
                    if (preset != null) json.put("selectedPreset", preset);
                    String saved = json.toString(2);
                    String persistenceWarning = persistReport(saved);
                    String partialText = partial == null ? "" : partial.toDisplayText() + "\n\n";
                    onUi(() -> {
                        report = saved;
                        saveButton.setEnabled(!busy);
                        resultText.setText(partialText + "当前没有执行预设写入。" + persistenceWarning);
                    });
                } catch (Exception ignored) { /* Keep original probe error visible. */ }
                onUi(() -> {
                    statusText.setText((directoryInspection ? "目录诊断未完成：" : "检测未完成：") + message);
                });
            } finally {
                probe.close();
                if (activeProbe == probe) activeProbe = null;
                onUi(() -> setBusy(false));
            }
        });
    }

    private void runTransfer(UsbDevice device, boolean directoryExperiment) {
        if (preset == null) {
            setBusy(false);
            requirePreset();
            return;
        }
        UsbNcpTransfer transfer = new UsbNcpTransfer();
        activeTransfer = transfer;
        JSONObject selected = preset;
        statusText.setText(directoryExperiment
                ? "正在检查现存的 NIKON/CUSTOMPC 并实测上传，请保持数据线连接…"
                : "正在试传并读回核对，请保持数据线连接…");
        worker.execute(() -> {
            try {
                byte[] bytes = java.nio.file.Files.readAllBytes(new File(getFilesDir(), "selected.ncp").toPath());
                NcpFile.validate(bytes);
                if (!hex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(selected.optString("sha256"))) {
                    throw new IllegalStateException("本机文件与选定预设的校验值不一致，请重新选择文件。");
                }
                UsbNcpTransfer.Result result = transfer.transfer(device, usbManager, bytes, directoryExperiment);
                JSONObject json = result.toJson();
                json.put("appVersion", appVersion());
                json.put("selectedPreset", selected);
                String saved = json.toString(2);
                String persistenceWarning = persistReport(saved);
                onUi(() -> {
                    report = saved;
                    resultText.setText(result.toDisplayText() + persistenceWarning);
                    statusText.setText("文件已传到卡里并读回核对。请断开连接，在相机菜单中载入预设。");
                    saveButton.setEnabled(!busy);
                });
            } catch (Exception error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                try {
                    JSONObject json = error instanceof UsbNcpTransfer.TransferException
                            && ((UsbNcpTransfer.TransferException) error).partialResult != null
                            ? ((UsbNcpTransfer.TransferException) error).partialResult.toJson() : new JSONObject();
                    json.put("error", message);
                    if (error instanceof UsbNcpTransfer.TransferException
                            && ((UsbNcpTransfer.TransferException) error).responseCode != null) {
                        json.put("ptpResponseCode", String.format(Locale.ROOT, "0x%04X",
                                ((UsbNcpTransfer.TransferException) error).responseCode));
                    }
                    json.put("existingDirectoryExperimentRequested", directoryExperiment);
                    json.put("menuImportVerified", false);
                    json.put("appVersion", appVersion());
                    json.put("selectedPreset", selected);
                    String saved = json.toString(2);
                    String persistenceWarning = persistReport(saved);
                    String changes = error instanceof UsbNcpTransfer.TransferException
                            && ((UsbNcpTransfer.TransferException) error).partialResult != null
                            ? ((UsbNcpTransfer.TransferException) error).partialResult.toDisplayText() : "尚未开始相机文件传输。";
                    onUi(() -> { report = saved; resultText.setText(changes + persistenceWarning); saveButton.setEnabled(!busy); });
                } catch (Exception ignored) { /* Preserve the original transfer failure. */ }
                onUi(() -> statusText.setText("试传未完成：" + message));
            } finally {
                transfer.close();
                if (activeTransfer == transfer) activeTransfer = null;
                onUi(() -> setBusy(false));
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == OPEN_NCP) {
            setBusy(true);
            statusText.setText("正在读取并检查 NCP 文件，请稍候…");
            worker.execute(() -> {
                try {
                    String name = documentName(uri);
                    if (!name.toLowerCase(Locale.ROOT).endsWith(".ncp")) {
                        throw new IllegalArgumentException("请选择扩展名为 .NCP 的文件。");
                    }
                    byte[] bytes = readBounded(uri, MAX_NCP_BYTES);
                    if (bytes.length == 0) throw new IllegalArgumentException("文件为空。");
                    String version = NcpFile.validate(bytes);
                    String hash = hex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    JSONObject metadata = new JSONObject();
                    metadata.put("fileName", name);
                    metadata.put("byteLength", bytes.length);
                    metadata.put("sha256", hash);
                    metadata.put("containerStructureValidated", true);
                    metadata.put("version", version);
                    metadata.put("cameraCompatibilityVerified", false);
                    try (FileOutputStream file = openFileOutput("selected.ncp", MODE_PRIVATE)) { file.write(bytes); }
                    try (FileOutputStream file = openFileOutput("preset.json", MODE_PRIVATE)) {
                        file.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
                    }
                    onUi(() -> {
                        preset = metadata; showPreset();
                        statusText.setText("NCP 文件已检查，可以生成兼容预览或选择试传。");
                    });
                } catch (Exception error) {
                    onUi(() -> {
                        statusText.setText("NCP 文件检查未完成，请重新选择。");
                        new AlertDialog.Builder(this).setTitle("文件未导入")
                                .setMessage(error.getMessage()).setPositiveButton("好", null).show();
                    });
                } finally { onUi(() -> setBusy(false)); }
            });
        } else if (request == SAVE_REPORT && report != null) {
            String snapshot = report;
            setBusy(true);
            statusText.setText("正在保存诊断报告，请稍候…");
            worker.execute(() -> {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new IllegalStateException("无法写入所选位置。");
                    output.write(snapshot.getBytes(StandardCharsets.UTF_8));
                    onUi(() -> statusText.setText("诊断报告已保存。"));
                } catch (Exception error) {
                    onUi(() -> statusText.setText("保存失败：" + error.getMessage()));
                } finally { onUi(() -> setBusy(false)); }
            });
        }
    }

    private String documentName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        }
        return "unknown";
    }

    private byte[] readBounded(Uri uri, int maxBytes) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalStateException("无法读取文件。");
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > maxBytes) throw new IllegalArgumentException("文件超过 1 MB，请确认它是 NCP 预设。");
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
    }

    private void restorePreset() {
        try {
            File file = new File(getFilesDir(), "preset.json");
            if (file.isFile()) {
                preset = new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                NcpFile.validate(java.nio.file.Files.readAllBytes(new File(getFilesDir(), "selected.ncp").toPath()));
                showPreset();
            }
        } catch (Exception ignored) { preset = null; }
    }

    private void restoreReport() {
        try {
            File file = new File(getFilesDir(), "last-report.json");
            if (file.isFile()) {
                report = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                new JSONObject(report);
                resultText.setText("已保留上一次诊断报告。连接相机并重新检测可生成最新报告。");
                saveButton.setEnabled(!busy);
            }
        } catch (Exception ignored) { report = null; }
    }

    private String persistReport(String value) {
        try {
            try (FileOutputStream file = openFileOutput("last-report.tmp", MODE_PRIVATE)) {
                file.write(value.getBytes(StandardCharsets.UTF_8));
            }
            java.nio.file.Files.move(new File(getFilesDir(), "last-report.tmp").toPath(),
                    new File(getFilesDir(), "last-report.json").toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return "";
        } catch (Exception error) {
            return "\n\n本机报告自动保存失败，当前 USB 结果仍有效。请用“保存诊断报告”导出，避免重复上传。";
        }
    }

    private void showPreset() {
        if (preset == null) return;
        presetText.setText(preset.optString("fileName") + "\n" + preset.optInt("byteLength")
                + " 字节 · 已保留原始内容\nSHA-256: " + preset.optString("sha256")
                + "\n文件结构已检查；相机兼容性待验证。");
    }

    private void setBusy(boolean value) {
        busy = value;
        probeButton.setEnabled(!value);
        directoryProbeButton.setEnabled(!value);
        pictureControlButton.setEnabled(!value);
        pictureControlCopyButton.setEnabled(!value);
        fileButton.setEnabled(!value);
        transferButton.setEnabled(!value);
        directoryExperimentButton.setEnabled(!value);
        ncpImportButton.setEnabled(!value);
        saveButton.setEnabled(!value);
    }

    private void onUi(Runnable action) {
        if (!destroyed) runOnUiThread(() -> { if (!destroyed) action.run(); });
    }

    @Override protected void onDestroy() {
        destroyed = true;
        UsbProbe probe = activeProbe;
        if (probe != null) probe.close();
        UsbNcpTransfer transfer = activeTransfer;
        if (transfer != null) transfer.close();
        UsbPictureControlProbe pictureControlProbe = activePictureControlProbe;
        if (pictureControlProbe != null) pictureControlProbe.close();
        UsbPictureControlImport pictureControlImport = activePictureControlImport;
        if (pictureControlImport != null) pictureControlImport.close();
        worker.shutdownNow();
        if (receiverRegistered) unregisterReceiver(usbReceiver);
        super.onDestroy();
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.rgb(28, 31, 34));
        shape.setCornerRadius(dp(18));
        card.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(16);
        parent.addView(card, params);
        return card;
    }

    private TextView addText(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setLineSpacing(dp(4), 1f);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(10);
        parent.addView(text, params);
        return text;
    }

    private Button button(LinearLayout parent, String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(primary ? background : foreground);
        button.setTextSize(15);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(primary ? accent : Color.rgb(47, 51, 55));
        shape.setCornerRadius(dp(12));
        button.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(50));
        params.topMargin = dp(4);
        parent.addView(button, params);
        return button;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String appVersion() {
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return version == null ? "unknown" : version;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return "unknown";
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
}

package com.nikonpreset.usbprobe.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.nikonpreset.usbprobe.library.PresetLibrary;
import com.nikonpreset.usbprobe.library.PresetRecord;
import com.nikonpreset.usbprobe.ncp.PresetPreviewProfiles;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Local visual picker. No camera session, network, or original-photo operation is performed. */
public final class PresetSelectionPage implements AutoCloseable {
    public interface Callback {
        void onSelected(PresetRecord preset); // null only after explicitly choosing None.
        void onDismissed(); // Also called before onSelected on a successful selection.
    }
    private enum State { IDLE, LOADING, READY, UNSUPPORTED, ERROR }
    private static final class Entry {
        final PresetRecord preset; State state = State.IDLE; String error = "";
        Entry(PresetRecord preset) { this.preset = preset; }
    }

    private final Activity activity;
    private final boolean compactGrid;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), task -> { Thread thread = new Thread(task, "preset-selection"); thread.setDaemon(true); return thread; });
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getAllocationByteCount(); }
    };
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> pending = new HashSet<>();
    private volatile Set<String> visibleIds = Collections.emptySet();
    private volatile int generation;
    private volatile boolean closed;
    private Dialog dialog;
    private BooleanSupplier sourceValid;
    private Callback callback;
    private PresetLibrary library; // Constructed/read only on the single worker.
    private PresetPreviewRenderer renderer;
    private UiPalette palette;
    private FrameLayout shell, region;
    private LinearLayout pane;
    private TextView heading, subtitle;
    private ImageButton back;
    private ListView list;
    private Cards adapter;
    private String title = "选择预设", selected = "", version = "";
    private boolean allowNone, loading, selecting, scrolling;
    private float fontScale;
    private int columns = 1;

    public PresetSelectionPage(Activity activity) {
        this(activity, false);
    }

    /** D live workspace uses compact visual cards; other callers retain the existing single-column page. */
    public PresetSelectionPage(Activity activity, boolean compactGrid) {
        if (activity == null) throw new IllegalArgumentException("activity");
        this.activity = activity; this.compactGrid = compactGrid;
        worker.setKeepAliveTime(15, TimeUnit.SECONDS); worker.allowCoreThreadTimeOut(true);
    }

    /** UI-thread API. Cancellation leaves the parent's current preset and form intact. */
    public void show(String title, String currentPresetId, boolean allowNone,
                     BooleanSupplier sourceValid, Callback callback) {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        if (sourceValid == null || callback == null) throw new IllegalArgumentException("Missing selection host");
        dismiss();
        if (!safe(sourceValid)) return;
        this.title = title == null || title.trim().isEmpty() ? "选择预设" : title;
        this.selected = currentPresetId == null ? "" : currentPresetId;
        final String owner=accountOwner();
        this.allowNone = allowNone; this.sourceValid = ()->safe(sourceValid)&&owner.equals(accountOwner()); this.callback = callback;
        palette = new UiPalette(activity); fontScale = activity.getResources().getConfiguration().fontScale;
        shell = new FrameLayout(activity) {
            @Override protected void onConfigurationChanged(android.content.res.Configuration configuration) {
                super.onConfigurationChanged(configuration); post(() -> { if (dialog != null) { adapt(); requestLayout(); } });
            }
        }; shell.setBackgroundColor(palette.surface);
        shell.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        pane = new LinearLayout(activity); pane.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams bounded = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER_HORIZONTAL);
        shell.addView(pane, bounded);
        pane.addView(header(), new LinearLayout.LayoutParams(-1, -2));
        region = new FrameLayout(activity); pane.addView(region, new LinearLayout.LayoutParams(-1, 0, 1));
        shell.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> adapt());
        Dialog next = new Dialog(activity, palette.dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar);
        next.requestWindowFeature(Window.FEATURE_NO_TITLE); next.setContentView(shell); next.setCanceledOnTouchOutside(false);
        dialog = next;
        next.setOnDismissListener(ignored -> { if (dialog == next) finish(false, null); });
        try {
            next.show(); Window window = next.getWindow();
            if (window != null) {
                window.setLayout(-1, -1); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
                else window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                styleWindow(window);
            }
            shell.requestApplyInsets(); adapt(); PageTransitions.enter(pane, 1, false); load();
        } catch (WindowManager.BadTokenException | IllegalStateException unavailable) { finish(false, null); }
    }

    public void dismiss() { if (dialog != null) finish(false, null); }

    /** Disposes this page and suppresses every queued selection result. */
    @Override public void close() {
        if (closed) return;
        closed = true; finish(false, null); worker.shutdownNow(); cache.evictAll();
    }

    private View header() {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(10), dp(20), dp(12)); row.setMinimumHeight(dp(72));
        back = new ImageButton(activity); back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setContentDescription("返回"); back.setOnClickListener(view -> dismiss());
        row.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout names = new LinearLayout(activity); names.setOrientation(LinearLayout.VERTICAL); names.setPadding(dp(10), 0, 0, 0);
        heading = text(this.title, 23, true); heading.setMaxLines(2); heading.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = text("模板效果预览 · 选中后返回", 13, false); subtitle.setPadding(0, dp(5), 0, 0); subtitle.setMaxLines(2);
        names.addView(heading); names.addView(subtitle); row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        styleHeader(); return row;
    }

    private void load() {
        if (!valid()) return;
        final int token = ++generation; worker.getQueue().clear(); pending.clear(); entries.clear(); visibleIds = Collections.emptySet();
        loading = true; selecting = false; adapter = null; list = null; region.removeAllViews();
        LinearLayout state = state("正在读取本地预设…", ""); ProgressBar progress = new ProgressBar(activity);
        state.addView(progress, 0, new LinearLayout.LayoutParams(dp(32), dp(32))); center(state);
        execute(() -> {
            try {
                PresetLibrary local = new PresetLibrary(activity.getApplicationContext().getFilesDir());
                com.nikonpreset.usbprobe.library.PersonalPresetSync scope=new com.nikonpreset.usbprobe.library.PersonalPresetSync(activity.getFilesDir(),local);
                String owner=accountOwner();List<PresetRecord> presets = new ArrayList<>();for(PresetRecord r:local.list())if(scope.visible(owner,r.id))presets.add(r);
                PresetPreviewRenderer previews = new PresetPreviewRenderer(activity.getApplicationContext());
                main.post(() -> {
                    if (!current(token)) return;
                    library = local; renderer = previews; version = previews.versionKey(); loading = false;
                    for (PresetRecord preset : presets) entries.add(new Entry(preset)); renderList();
                });
            } catch (Exception failure) { main.post(() -> { if (current(token)) { loading = false; showLoadError(readable(failure)); } }); }
        }, () -> { loading = false; showLoadError("本地预设暂时忙碌，请重试。"); });
    }

    private void renderList() {
        region.removeAllViews();
        if (entries.isEmpty() && !allowNone) {
            center(state("还没有本地预设", "先在预设库添加或下载预设，再回来选择。")); return;
        }
        list = new ListView(activity); list.setDivider(null); list.setDividerHeight(0); list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setClipToPadding(false); list.setPadding(0, dp(2), 0, dp(24)); list.setCacheColorHint(Color.TRANSPARENT);
        adapter = new Cards(); list.setAdapter(adapter); list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                scrolling = state == AbsListView.OnScrollListener.SCROLL_STATE_FLING;
                if (!scrolling) { updateVisible(); if (adapter != null) adapter.notifyDataSetChanged(); }
            }
            @Override public void onScroll(AbsListView view, int first, int count, int total) { updateVisible(); }
        });
        region.addView(list, new FrameLayout.LayoutParams(-1, -1)); list.post(this::updateVisible);
    }

    private void showLoadError(String detail) {
        if (!valid()) return;
        region.removeAllViews(); LinearLayout state = state("预设暂时无法读取", shortReason(detail));
        Button retry = button("重试", this::load); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2); p.topMargin = dp(16); state.addView(retry, p); center(state);
    }

    private void updateVisible() {
        if (list == null || adapter == null) return;
        Set<String> ids = new HashSet<>(); int offset = allowNone ? 1 : 0;
        int first = Math.max(0, list.getFirstVisiblePosition() - offset) * columns;
        int last = Math.min(entries.size() - 1, (list.getLastVisiblePosition() - offset + 2) * columns - 1);
        for (int index = first; index <= last; index++) ids.add(entries.get(index).preset.id);
        visibleIds = Collections.unmodifiableSet(ids);
        if (!scrolling) for (int index = first; index <= last; index++) request(entries.get(index));
    }

    private void request(Entry entry) {
        if (!valid() || selecting || scrolling || entry.state != State.IDLE || pending.size() >= 6 || !visibleIds.contains(entry.preset.id)) return;
        final int token = generation; final String key = key(entry);
        entry.state = State.LOADING; pending.add(entry.preset.id);
        execute(() -> {
            if (closed || token != generation) return;
            Bitmap bitmap = null; String reason = ""; boolean unsupported = false, offscreen = !visibleIds.contains(entry.preset.id);
            if (!offscreen) try {
                byte[] ncp = library.readPreset(entry.preset.id);
                try { PresetPreviewProfiles.fromPreset(ncp); }
                catch (IOException invalid) { unsupported = true; throw invalid; }
                bitmap = renderer.render(ncp);
                int edge = Math.max(bitmap.getWidth(), bitmap.getHeight());
                if (edge > 720) {
                    Bitmap reduced = Bitmap.createScaledBitmap(bitmap, Math.max(1, bitmap.getWidth() * 720 / edge), Math.max(1, bitmap.getHeight() * 720 / edge), true);
                    if (reduced != bitmap) { bitmap.recycle(); bitmap = reduced; }
                }
            } catch (Exception | OutOfMemoryError failure) { reason = readable(failure); }
            final Bitmap image = bitmap; final String detail = reason; final boolean invalid = unsupported, skipped = offscreen;
            main.post(() -> {
                if (!current(token)) { if (image != null) image.recycle(); return; }
                pending.remove(entry.preset.id);
                if (skipped) entry.state = State.IDLE;
                else if (image != null) { cache.put(key, image); entry.state = State.READY; entry.error = ""; }
                else { entry.state = invalid ? State.UNSUPPORTED : State.ERROR; entry.error = detail; }
                if (adapter != null) adapter.notifyDataSetChanged();
            });
        }, () -> { pending.remove(entry.preset.id); entry.state = State.IDLE; });
    }

    private void choose(Entry entry) {
        if (!valid() || selecting || entry != null && entry.state != State.READY) return;
        selecting = true; subtitle.setText("正在确认预设…");
        final int token = generation;
        // Discard offscreen previews so confirmation is next after any one in-flight render.
        worker.getQueue().clear(); pending.clear();
        for (Entry pendingEntry : entries) if (pendingEntry.state == State.LOADING) pendingEntry.state = State.IDLE;
        if (entry == null) { finish(true, null); return; }
        if (adapter != null) adapter.notifyDataSetChanged();
        execute(() -> {
            boolean unsupported = false;
            try {
                byte[] bytes = library.readPreset(entry.preset.id);
                try { PresetPreviewProfiles.fromPreset(bytes); } catch (IOException invalid) { unsupported = true; throw invalid; }
                main.post(() -> { if (current(token)) finish(true, entry.preset); });
            } catch (Exception failure) { final boolean invalid = unsupported; main.post(() -> {
                if (!current(token)) return;
                selecting = false; subtitle.setText("模板效果预览 · 选中后返回"); cache.remove(key(entry));
                entry.state = invalid ? State.UNSUPPORTED : State.ERROR; entry.error = readable(failure);
                if (adapter != null) adapter.notifyDataSetChanged();
            }); }
        }, () -> { selecting = false; subtitle.setText("暂时无法确认，请再试一次"); if (adapter != null) adapter.notifyDataSetChanged(); });
    }

    private void finish(boolean chosen, PresetRecord preset) {
        Dialog previous = dialog; Callback target = callback; BooleanSupplier guard = sourceValid;
        dialog = null; callback = null; sourceValid = null; generation++; worker.getQueue().clear(); pending.clear(); visibleIds = Collections.emptySet();
        selecting = false; loading = false;
        if (pane != null) PageTransitions.cancel(pane);
        if (previous != null) { previous.setOnDismissListener(null); previous.dismiss(); }
        if (region != null) region.removeAllViews();
        list = null; adapter = null; entries.clear(); library = null; renderer = null; cache.evictAll();
        if (target != null && !closed && !activity.isFinishing() && !activity.isDestroyed()) {
            target.onDismissed();
            if (chosen && !closed && safe(guard) && !activity.isFinishing() && !activity.isDestroyed()) target.onSelected(preset);
        }
    }

    private final class Cards extends BaseAdapter {
        @Override public int getCount() { return (entries.size() + columns - 1) / columns + (allowNone ? 1 : 0); }
        @Override public Object getItem(int position) { return allowNone && position == 0 ? null : entries.get((position - (allowNone ? 1 : 0)) * columns); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) {
            Entry entry = (Entry) getItem(position);
            return !selecting && (entry == null || columns > 1 || entry.state == State.READY);
        }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            if (columns > 1 && !(allowNone && position == 0)) {
                CardRow row = convert instanceof CardRow ? (CardRow) convert : new CardRow();
                row.bind((position - (allowNone ? 1 : 0)) * columns); return row;
            }
            Card card = convert instanceof Card ? (Card) convert : new Card(); card.bind((Entry) getItem(position)); return card;
        }
    }

    /** A ListView row owns two independent card click/retry targets and their separate preview entries. */
    private final class CardRow extends LinearLayout {
        final Card first = new Card(true), second = new Card(true);
        CardRow() {
            super(activity); setOrientation(HORIZONTAL); setGravity(Gravity.TOP);
            setPadding(dp(20), 0, dp(20), dp(12));
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1); left.setMarginEnd(dp(6));
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1); right.setMarginStart(dp(6));
            addView(first, left); addView(second, right);
        }
        void bind(int index) {
            first.bind(entries.get(index));
            if (index + 1 < entries.size()) { second.setVisibility(VISIBLE); second.bind(entries.get(index + 1)); }
            else { second.setVisibility(INVISIBLE); second.image.setImageDrawable(null); second.entry = null; }
        }
    }

    private final class Card extends LinearLayout {
        final FrameLayout content, photo;
        final ImageView image, check;
        final TextView placeholder, name, note;
        final Button retry;
        final boolean gridCard;
        Entry entry;
        Card() { this(false); }
        Card(boolean gridCard) {
            super(activity); this.gridCard = gridCard; setOrientation(VERTICAL);
            setPadding(gridCard ? 0 : dp(20), 0, gridCard ? 0 : dp(20), gridCard ? 0 : dp(18));
            content = new FrameLayout(activity); content.setClipToOutline(true); addView(content, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout body = new LinearLayout(activity); body.setOrientation(VERTICAL); content.addView(body, new FrameLayout.LayoutParams(-1, -2));
            photo = new FrameLayout(activity); body.addView(photo, new LinearLayout.LayoutParams(-1, dp(200)));
            placeholder = text("正在生成效果预览…", 14, false); placeholder.setGravity(Gravity.CENTER); placeholder.setPadding(dp(20), dp(16), dp(20), dp(16)); photo.addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            image = new ImageView(activity); image.setScaleType(ImageView.ScaleType.CENTER_CROP); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); photo.addView(image, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout info = new LinearLayout(activity); info.setGravity(Gravity.CENTER_VERTICAL);
            info.setPadding(dp(gridCard ? 11 : 18), dp(gridCard ? 11 : 16), dp(gridCard ? 8 : 16), dp(gridCard ? 12 : 17));
            body.addView(info, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout labels = new LinearLayout(activity); labels.setOrientation(VERTICAL); info.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            name = text("", gridCard ? 14 : 18, true); name.setMaxLines(2); if (gridCard) name.setMinLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END); labels.addView(name);
            note = text("", gridCard ? 11 : 13, false); note.setPadding(0, dp(6), 0, 0); if (gridCard) note.setMaxLines(3); labels.addView(note);
            check = new ImageView(activity); int inset = dp(gridCard ? 5 : 10); check.setPadding(inset, inset, inset, inset);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams selected = new LinearLayout.LayoutParams(dp(gridCard ? 26 : 44), dp(gridCard ? 26 : 44)); selected.setMarginStart(dp(gridCard ? 4 : 10)); info.addView(check, selected);
            retry = button("重试预览", () -> { if (entry != null && !selecting) { entry.state = State.IDLE; request(entry); if (adapter != null) adapter.notifyDataSetChanged(); } });
            LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-2, -2); retryParams.setMargins(dp(16), 0, dp(16), dp(14)); body.addView(retry, retryParams);
            if (gridCard) { retry.setTextSize(12); retry.setPadding(dp(8), dp(8), dp(8), dp(8)); }
            content.setMinimumHeight(dp(88)); content.setFocusable(true); content.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName());
                }
            });
        }
        void bind(Entry next) {
            entry = next; boolean none = entry == null, current = none ? selected.isEmpty() : selected.equals(entry.preset.id);
            content.setBackground(ripple(palette.card, gridCard ? 16 : 24)); photo.setBackgroundColor(palette.container);
            retry.setTextColor(palette.primary); retry.setBackground(ripple(palette.primaryContainer, 18));
            name.setTextSize(gridCard ? 14 : 18); name.setTextColor(palette.text); note.setTextSize(gridCard ? 11 : 13); note.setTextColor(palette.secondary); placeholder.setTextSize(gridCard ? 12 : 14); placeholder.setTextColor(palette.secondary);
            name.setText(none ? "不使用预设" : entry.preset.displayName); photo.setVisibility(none ? GONE : VISIBLE);
            check.setVisibility(current ? VISIBLE : GONE);
            check.setImageDrawable(icon(AppIcon.Kind.CHECK, gridCard ? palette.primary : palette.onPrimary));
            check.setBackground(gridCard ? null : shape(palette.primary, 22));
            image.setImageDrawable(null); retry.setVisibility(GONE);
            if (none) note.setText("仅保存原图");
            else {
                Bitmap bitmap = cache.get(key(entry));
                if (bitmap != null) { image.setImageBitmap(bitmap); entry.state = State.READY; }
                else if (entry.state == State.READY) entry.state = State.IDLE;
                switch (entry.state) {
                    case READY: note.setText(current ? "当前选择" : "轻点使用这个预设"); break;
                    case UNSUPPORTED: placeholder.setText("此预设暂不支持照片效果"); note.setText("暂不可选 · " + shortReason(entry.error)); break;
                    case ERROR: placeholder.setText("效果预览暂不可用"); note.setText(shortReason(entry.error)); retry.setVisibility(VISIBLE); break;
                    default: placeholder.setText("正在生成效果预览…"); note.setText("模板模拟"); request(entry); break;
                }
                placeholder.setVisibility(bitmap == null ? VISIBLE : GONE);
            }
            note.setVisibility(gridCard && !none && entry.state == State.READY ? GONE : VISIBLE);
            boolean enabled = !selecting && (none || entry.state == State.READY);
            retry.setEnabled(!selecting);
            content.setEnabled(enabled); content.setClickable(enabled); content.setOnClickListener(enabled ? view -> choose(entry) : null);
            content.setSelected(current); content.setAlpha(!none && entry.state == State.UNSUPPORTED ? .72f : 1f);
            content.setContentDescription(name.getText() + (current ? "，当前选择" : "") + (enabled ? "，轻点选择" : "，" + note.getText()));
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = Math.max(0, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
            if (photo.getVisibility() != GONE) {
                int height = gridCard ? Math.min(dp(220), Math.max(dp(96), Math.round(width * .8f)))
                        : Math.min(dp(300), Math.max(dp(140), Math.round(width * .64f)));
                ViewGroup.LayoutParams params = photo.getLayoutParams(); if (params.height != height) { params.height = height; photo.setLayoutParams(params); }
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private void adapt() {
        if (dialog == null || shell == null) return;
        int available = shell.getWidth() - shell.getPaddingLeft() - shell.getPaddingRight();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) pane.getLayoutParams(); int width = available <= 0 ? -1 : Math.min(available, dp(680));
        if (params.width != width) { params.width = width; pane.setLayoutParams(params); }
        UiPalette next = new UiPalette(activity); float scale = activity.getResources().getConfiguration().fontScale;
        int contentWidth = available <= 0 ? activity.getResources().getDisplayMetrics().widthPixels : Math.min(available, dp(680));
        int nextColumns = compactGrid && scale <= 1.3f && contentWidth >= dp(320) ? 2 : 1;
        if (nextColumns != columns) {
            int offset = allowNone ? 1 : 0;
            boolean showingNone = allowNone && list != null && list.getFirstVisiblePosition() == 0;
            int anchor = list == null ? 0 : Math.max(0, list.getFirstVisiblePosition() - offset) * columns;
            columns = nextColumns;
            if (adapter != null) adapter.notifyDataSetChanged();
            if (list != null) { list.setSelection(showingNone ? 0 : offset + anchor / columns); list.post(this::updateVisible); }
        }
        if (next.dark != palette.dark || fontScale != scale) {
            palette = next; fontScale = scale; shell.setBackgroundColor(palette.surface); styleHeader();
            Window window = dialog.getWindow(); if (window != null) styleWindow(window);
            if (adapter != null) adapter.notifyDataSetChanged();
            else if (!loading) load();
        }
    }
    private void styleHeader() {
        if (heading == null) return;
        heading.setTextSize(23); heading.setTextColor(palette.text); subtitle.setTextSize(13); subtitle.setTextColor(palette.secondary);
        back.setImageDrawable(icon(AppIcon.Kind.BACK, palette.text)); back.setBackground(ripple(Color.TRANSPARENT, 24));
    }
    private void styleWindow(Window window) {
        window.setBackgroundDrawable(new ColorDrawable(palette.surface)); window.setStatusBarColor(palette.surface); window.setNavigationBarColor(palette.surface);
        if (Build.VERSION.SDK_INT >= 30 && window.getInsetsController() != null) {
            int appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            window.getInsetsController().setSystemBarsAppearance(palette.dark ? 0 : appearance, appearance);
        }
        View decor = window.getDecorView(); int flags = decor.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(palette.dark ? flags & ~light : flags | light);
    }
    private LinearLayout state(String title, String detail) {
        LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL); layout.setGravity(Gravity.CENTER); layout.setPadding(dp(28), dp(24), dp(28), dp(24));
        TextView label = text(title, 18, true); label.setGravity(Gravity.CENTER); label.setPadding(0, dp(16), 0, 0); layout.addView(label);
        if (!detail.isEmpty()) { TextView body = text(detail, 14, false); body.setTextColor(palette.secondary); body.setGravity(Gravity.CENTER); body.setPadding(0, dp(10), 0, 0); layout.addView(body); }
        return layout;
    }
    private void center(View content) { region.addView(content, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)); }
    private TextView text(String value, int size, boolean bold) { TextView result = new TextView(activity); result.setText(value); result.setTextSize(size); result.setTextColor(palette.text); if (bold) result.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return result; }
    private Button button(String title, Runnable action) {
        Button button = new Button(activity); button.setText(title); button.setTextSize(14); button.setAllCaps(false); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setPadding(dp(20), dp(10), dp(20), dp(10));
        button.setTextColor(palette.primary); button.setBackground(ripple(palette.primaryContainer, 18)); button.setStateListAnimator(null); button.setOnClickListener(view -> action.run()); return button;
    }
    private GradientDrawable shape(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private RippleDrawable ripple(int color, int radius) { return new RippleDrawable(ColorStateList.valueOf(palette.primaryContainer), shape(color, radius), shape(Color.WHITE, radius)); }
    private AppIcon icon(AppIcon.Kind kind, int color) { return new AppIcon(kind, color, activity.getResources().getDisplayMetrics().density); }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    private String key(Entry entry) { return entry.preset.id + ':' + version; }
    private boolean valid() { return !closed && dialog != null && !activity.isFinishing() && !activity.isDestroyed() && safe(sourceValid); }
    private boolean current(int token) { return token == generation && valid(); }
    private String accountOwner(){com.nikonpreset.usbprobe.account.AccountRepository r=com.nikonpreset.usbprobe.account.AndroidAccounts.get(activity);com.nikonpreset.usbprobe.account.AccountRepository.Session s=r.cachedSession();return s==null||r.isLogoutPending()?"":s.user.id;}
    private static boolean safe(BooleanSupplier guard) { try { return guard != null && guard.getAsBoolean(); } catch (RuntimeException gone) { return false; } }
    private void execute(Runnable task, Runnable rejected) {
        try { worker.execute(task); } catch (RejectedExecutionException full) { if (!closed) rejected.run(); }
    }
    private static String readable(Throwable failure) {
        if (failure instanceof OutOfMemoryError) return "暂时没有足够内存显示效果，请重试。";
        String text = failure.getMessage(); return text == null || text.trim().isEmpty() ? "本地预设暂时不可用，请重试。" : text.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
    private static String shortReason(String text) { return text.length() > 180 ? text.substring(0, 180) + "…" : text; }
}

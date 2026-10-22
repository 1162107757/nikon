package cc.huevia.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import cc.huevia.account.AccountException;
import cc.huevia.account.AccountInstallation;
import cc.huevia.account.AccountRepository;
import cc.huevia.account.AndroidAccounts;
import cc.huevia.account.QqLoginController;
import cc.huevia.R;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Optional consumer account screens. Their network queue is independent of the camera runtime. */
public final class AccountPanel implements AutoCloseable {
    public static final int REQUEST_AVATAR = 7342;
    public interface Host {
        void onChanged();
        boolean isVisible();
        void onFeedback(String title, String message);
    }
    private enum Screen { CLOSED, LOGIN, REGISTER, PROFILE }
    private final Activity activity;
    private final Host host;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final AccountRepository repository;
    private final QqLoginController qqLogin;
    private AccountRepository.Session session;
    private Screen screen = Screen.CLOSED;
    private boolean restoring = true, working, destroyed, refreshAfterRestore;
    private boolean qqAuthorizing, qqExchanging;
    private boolean passwordLogin, passwordVisible;
    private long qqRequestEpoch;
    private long epoch;
    private String usernameDraft = "", pageMessage = "";
    private boolean messageError;
    private ScrollView root;
    private String viewConfiguration = "";
    private UiPalette colors;
    private EditText username, password, confirmation;
    private ImageButton showPassword, showConfirmation;
    private TextView status;
    private Button submit, changeMode, refreshButton, logoutButton;
    private Button qqButton;
    private Button loginMethod;
    private LinearLayout qqLoadingContent;
    private TextView qqLoadingLabel;
    private ProgressBar spinner;
    private LinearLayout loadingContent;
    private TextView loadingLabel;
    private AlertDialog logoutDialog, qqPrivacyDialog, loginHelpDialog;
    private AlertDialog challengeDialog;
    private String challengeId = "", installationId = "", pendingQqToken;
    private android.graphics.Bitmap challengeBitmap;
    private EditText challengeAnswer;
    private ImageView challengeImage;
    private boolean challengeLoading, challengeRequested;
    private boolean hostResumed = true;
    private long challengeEpoch;
    private UserAvatarView profileAvatar;
    private Button editAvatar;
    private AlertDialog avatarDialog;
    private AccountRepository.Session avatarOwner;
    private cc.huevia.account.AvatarImages.Prepared avatarDraft;
    private boolean avatarPickerPending;
    private long avatarRequestEpoch;

    public AccountPanel(Activity activity, Host host) {
        this.activity = activity; this.host = host;
        repository = AndroidAccounts.get(activity);
        qqLogin = new QqLoginController(activity, new QqLoginController.Callback() {
            @Override public void onAuthorized(String accessToken) { exchangeQqAuthorization(accessToken); }
            @Override public void onCancelled() { finishQqAuthorization("已取消 QQ 登录。", false); }
            @Override public void onError(String message) { finishQqAuthorization(message, true); }
        });
        network.execute(() -> {
            String failure = "";
            try { repository.restore(); }
            catch (Exception problem) { failure = readable(problem); }
            String result = failure;
            onUi(() -> {
                session = repository.cachedSession(); restoring = false;
                if (!result.isEmpty()) { pageMessage = result; messageError = true; }
                if (isOpen()) { screen = session == null ? Screen.LOGIN : Screen.PROFILE; discardView(); }
                changed();
                if (refreshAfterRestore && session != null && isOpen()) { refreshAfterRestore = false; refresh(); }
            });
        });
    }

    public boolean isOpen() { return screen != Screen.CLOSED; }
    public String title() { return screen == Screen.REGISTER ? "注册账号" : screen == Screen.PROFILE ? "我的账号" : "账号登录"; }
    public String navigationKey() { return "settings:account:" + screen.name() + (screen == Screen.LOGIN && passwordLogin ? ":password" : ""); }
    public View createHeaderAction(UiPalette palette) {
        if (screen != Screen.LOGIN && screen != Screen.REGISTER) return null;
        colors = palette;
        Button guest = textButton("游客使用", this::continueAsGuest);
        guest.setTextSize(13); guest.setTextColor(palette.secondary);
        return guest;
    }
    public String summaryTitle() {
        AccountRepository.Session snapshot = restoring ? session : repository.cachedSession();
        if (repository.isLogoutPending()) return "退出未完成";
        if (snapshot == null) return "未登录";
        return snapshot.user.nickname.isEmpty() ? snapshot.user.username : snapshot.user.nickname;
    }
    public String summarySubtitle() {
        AccountRepository.Session snapshot = restoring ? session : repository.cachedSession();
        if (repository.isLogoutPending()) return "本机登录信息尚未清除，请重试退出登录";
        if (snapshot == null) return "登录或注册，也可继续以游客使用";
        return accountName(snapshot.user) + (snapshot.verified ? " · 登录状态已验证" : " · 已保存登录信息，待联网核验");
    }
    public String summaryValue() {
        AccountRepository.Session snapshot = restoring ? session : repository.cachedSession();
        return restoring ? "读取中" : repository.isLogoutPending() ? "需重试" : snapshot == null ? "" : snapshot.verified ? "已登录" : "离线缓存";
    }

    public void open() {
        if (destroyed || isOpen()) return;
        if (!restoring) session = repository.cachedSession();
        epoch++; pageMessage = ""; messageError = false; passwordLogin = false;
        if (repository.isLogoutPending()) { pageMessage = "本机登录信息尚未清除，请重试退出登录。"; messageError = true; }
        screen = session == null ? Screen.LOGIN : Screen.PROFILE;
        refreshAfterRestore = restoring;
        discardView(); changed();
        if (!restoring && session != null && !repository.isLogoutPending()) refresh();
    }

    /** System Back is always available, including during a request. It never changes a camera session. */
    public void goBack() {
        if (!isOpen()) return;
        epoch++; refreshAfterRestore = false; clearAvatarDraft(); avatarOwner = null; avatarPickerPending = false;
        cancelQqAuthorization();
        pendingQqToken = null; clearChallenge();
        rememberUsername(); dismissDialog(); clearSecrets(); hideKeyboard();
        if (screen == Screen.REGISTER && !working) screen = Screen.LOGIN;
        else if (screen == Screen.LOGIN && passwordLogin && !working) passwordLogin = false;
        else { screen = Screen.CLOSED; usernameDraft = ""; }
        pageMessage = ""; messageError = false; discardView(); changed();
    }

    private void continueAsGuest() {
        if (!isOpen() || destroyed) return;
        epoch++; refreshAfterRestore = false;
        cancelQqAuthorization(); dismissDialog(); clearSecrets(); hideKeyboard();
        pendingQqToken = null; clearChallenge();
        screen = Screen.CLOSED; passwordLogin = false; usernameDraft = "";
        pageMessage = ""; messageError = false; discardView(); changed();
    }

    public View createView(UiPalette palette) {
        String configuration = palette.dark + ":" + activity.getResources().getConfiguration().fontScale
                + ":" + activity.getResources().getConfiguration().screenWidthDp;
        if (root != null && configuration.equals(viewConfiguration)) {
            if (root.getParent() instanceof ViewGroup) ((ViewGroup) root.getParent()).removeView(root);
            return root;
        }
        // A configuration change stays in the same form. Passwords remain only in transient views.
        char[] previousPassword = secret(password), previousConfirmation = secret(confirmation);
        rememberUsername(); discardView(); colors = palette; viewConfiguration = configuration;
        root = new ScrollView(activity); root.setFillViewport(true); root.setClipToPadding(false);
        LinearLayout content = vertical(); content.setPadding(dp(24), 0, dp(24), dp(20));
        root.addView(content, new ScrollView.LayoutParams(-1, -2));
        if (screen == Screen.PROFILE && session != null) { content.setPadding(dp(22), dp(6), dp(22), dp(32)); buildProfile(content); }
        else buildForm(content);
        if (password != null && previousPassword.length > 0) password.setText(new String(previousPassword));
        if (confirmation != null && previousConfirmation.length > 0) confirmation.setText(new String(previousConfirmation));
        Arrays.fill(previousPassword, '\0'); Arrays.fill(previousConfirmation, '\0');
        updateWorkingState();
        return root;
    }

    private void buildForm(LinearLayout content) {
        boolean registering = screen == Screen.REGISTER;
        boolean welcome = !registering && !passwordLogin;
        addBrand(content, welcome);
        if (!welcome) {
            TextView heading = text(registering ? "创建账号" : "账号密码登录", 28, true); content.addView(heading);
            TextView intro = text(registering ? "注册后自动登录 Huevia。" : "使用你的 Huevia 账号继续。", 14, false);
            intro.setTextColor(colors.secondary); intro.setPadding(0, dp(10), 0, dp(24)); content.addView(intro);
        }
        status = text(pageMessage, 13, false); status.setTextColor(messageError ? colors.error : colors.secondary);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); status.setPadding(dp(2), 0, dp(2), dp(16));
        status.setVisibility(pageMessage.isEmpty() ? View.GONE : View.VISIBLE); content.addView(status);
        if (welcome) {
            addQqAction(content);
            TextView note = text("使用 QQ 账号授权，首次登录自动创建账号", 12, false);
            note.setTextColor(colors.secondary); note.setGravity(Gravity.CENTER); note.setPadding(0, dp(12), 0, dp(6)); content.addView(note);
            loginMethod = textButton("使用账号密码登录", () -> switchLoginMethod(true));
            LinearLayout.LayoutParams methodParams = new LinearLayout.LayoutParams(-1, -2); methodParams.topMargin = dp(6);
            content.addView(loginMethod, methodParams);
        } else {
            buildPasswordForm(content, registering);
            if (!registering) {
                loginMethod = textButton("使用 QQ 快捷登录", () -> switchLoginMethod(false));
                LinearLayout.LayoutParams methodParams = new LinearLayout.LayoutParams(-1, -2); methodParams.topMargin = dp(10);
                content.addView(loginMethod, methodParams);
            }
        }
        addRegistrationAction(content, registering);
        if (repository.isLogoutPending()) {
            logoutButton = textButton("重试退出登录", this::logout); logoutButton.setTextColor(colors.error);
            content.addView(logoutButton, new LinearLayout.LayoutParams(-1, -2));
        }
        content.addView(new View(activity), new LinearLayout.LayoutParams(1, 0, 1f));
        addLoginFooter(content);
        // Avoid opening the keyboard when this optional page is first shown.
        content.setFocusableInTouchMode(true); content.requestFocus();
    }

    private void addBrand(LinearLayout content, boolean welcome) {
        // Short windows keep the login action above the fold; the rest remains scrollable.
        welcome = welcome && activity.getResources().getConfiguration().screenHeightDp >= 480;
        LinearLayout brand = new LinearLayout(activity);
        brand.setOrientation(welcome ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        brand.setGravity(welcome ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        brand.setPadding(0, dp(welcome ? 36 : 14), 0, dp(welcome ? 44 : 30));
        ImageView mark = new ImageView(activity); mark.setImageResource(R.drawable.huevia_launcher);
        mark.setBackground(shape(Color.TRANSPARENT, welcome ? 20 : 11)); mark.setClipToOutline(true);
        mark.setScaleType(ImageView.ScaleType.FIT_CENTER); mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(mark, new LinearLayout.LayoutParams(dp(welcome ? 76 : 38), dp(welcome ? 76 : 38)));
        TextView name = text(activity.getString(R.string.app_name), welcome ? 30 : 20, true);
        name.setLetterSpacing(-.025f);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, -2);
        if (welcome) nameParams.topMargin = dp(17); else nameParams.setMarginStart(dp(10));
        brand.addView(name, nameParams);
        if (welcome) {
            TextView tagline = text("把喜欢的色彩，带进相机", 14, false); tagline.setTextColor(colors.secondary);
            LinearLayout.LayoutParams taglineParams = new LinearLayout.LayoutParams(-2, -2); taglineParams.topMargin = dp(12);
            brand.addView(tagline, taglineParams);
        }
        content.addView(brand, new LinearLayout.LayoutParams(-1, -2));
    }

    private void buildPasswordForm(LinearLayout content, boolean registering) {
        LinearLayout form = vertical(); content.addView(form);
        username = input(form, "用户名", registering ? "设置你的用户名" : "请输入用户名", false, registering ? "newUsername" : View.AUTOFILL_HINT_USERNAME);
        username.setText(usernameDraft); username.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        username.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        if (registering) {
            TextView usernameHelp = text("3–32 位，以英文字母开头。可用字母、数字和下划线，字母不区分大小写。", 12, false);
            usernameHelp.setTextColor(colors.secondary); usernameHelp.setPadding(0, dp(8), 0, 0); form.addView(usernameHelp);
        }
        password = input(form, "密码", registering ? "设置 8–128 位密码" : "请输入密码", true,
                registering ? "newPassword" : View.AUTOFILL_HINT_PASSWORD);
        password.setImeOptions((registering ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE) | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        if (registering) {
            confirmation = input(form, "确认密码", "再次输入密码", true, "newPassword");
            confirmation.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            addChallenge(form);
        }
        submit = button(registering ? "注册并登录" : "登录", true, this::submit);
        submit.setBackground(actionBackground(colors.primary, 18));
        addLoadingAction(content, submit, true);
        password.setOnEditorActionListener((view, id, event) -> { if (!registering && id == EditorInfo.IME_ACTION_DONE) { submit(); return true; } return false; });
        if (confirmation != null) confirmation.setOnEditorActionListener((view, id, event) -> { if (id == EditorInfo.IME_ACTION_DONE) { submit(); return true; } return false; });
    }

    private void addRegistrationAction(LinearLayout content, boolean registering) {
        LinearLayout row = new LinearLayout(activity); row.setGravity(Gravity.CENTER); row.setOrientation(LinearLayout.HORIZONTAL);
        if (!registering) { TextView prompt = text("还没有账号？", 13, false); prompt.setTextColor(colors.secondary); row.addView(prompt); }
        changeMode = textButton(registering ? "返回登录" : "注册账号", () -> {
            if (working || restoring) return;
            rememberUsername(); clearSecrets(); hideKeyboard(); epoch++;
            screen = registering ? Screen.LOGIN : Screen.REGISTER; pageMessage = ""; messageError = false; discardView(); changed();
        });
        changeMode.setTextSize(13); changeMode.setPadding(dp(6), dp(10), dp(6), dp(10)); row.addView(changeMode);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2); rowParams.topMargin = dp(registering ? 10 : 20); content.addView(row, rowParams);
    }

    private void switchLoginMethod(boolean passwordForm) {
        if (destroyed || working || restoring || screen != Screen.LOGIN || repository.isLogoutPending()) return;
        rememberUsername(); clearSecrets(); hideKeyboard(); dismissDialog(); epoch++;
        passwordLogin = passwordForm; pageMessage = ""; messageError = false; discardView(); changed();
    }

    private void addLoginFooter(LinearLayout content) {
        LinearLayout footer = vertical(); footer.setGravity(Gravity.CENTER_HORIZONTAL); footer.setPadding(0, dp(32), 0, 0);
        TextView note = text("不登录，也能管理本机预设与照片", 12, false); note.setTextColor(colors.secondary); note.setGravity(Gravity.CENTER); footer.addView(note);
        // Vertical controls also fit narrow screens and large accessibility fonts without truncation.
        LinearLayout links = new LinearLayout(activity); links.setGravity(Gravity.CENTER); links.setOrientation(LinearLayout.HORIZONTAL);
        if (activity.getResources().getConfiguration().screenWidthDp < 350 || activity.getResources().getConfiguration().fontScale >= 1.3f) links.setOrientation(LinearLayout.VERTICAL);
        Button privacy = privacyButton(); privacy.setTextSize(12); privacy.setTextColor(colors.secondary);
        privacy.setPadding(dp(6), dp(10), dp(6), dp(10)); links.addView(privacy);
        Button help = textButton("登录帮助", this::showLoginHelp); help.setTextSize(12); help.setTextColor(colors.secondary);
        help.setPadding(dp(6), dp(10), dp(6), dp(10)); links.addView(help);
        LinearLayout.LayoutParams linksParams = new LinearLayout.LayoutParams(-1, -2); linksParams.topMargin = dp(8); footer.addView(links, linksParams);
        content.addView(footer, new LinearLayout.LayoutParams(-1, -2));
    }

    private void showLoginHelp() {
        if (destroyed || !isOpen() || !host.isVisible() || loginHelpDialog != null) return;
        clearSecrets(); hideKeyboard();
        loginHelpDialog = new AlertDialog.Builder(activity).setTitle("登录帮助")
                .setMessage("登录需要可上网的网络。\n\n连接相机 Wi-Fi 时可能无法上网，可先切换至手机网络完成登录，再连接相机。\n\n暂时无法登录时，仍可选择游客使用。")
                .setPositiveButton("知道了", null).create();
        loginHelpDialog.setOnDismissListener(dialog -> loginHelpDialog = null); loginHelpDialog.show();
    }

    private void buildProfile(LinearLayout content) {
        AccountRepository.User user = session.user;
        LinearLayout identity = vertical(); identity.setPadding(dp(18), dp(20), dp(18), dp(20)); identity.setBackground(shape(colors.card, 22));
        LinearLayout identityRow = new LinearLayout(activity); identityRow.setGravity(Gravity.CENTER_VERTICAL);
        profileAvatar = new UserAvatarView(activity); profileAvatar.setAvatar(user);
        profileAvatar.setContentDescription("修改头像"); profileAvatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        profileAvatar.setBackground(actionBackground(Color.TRANSPARENT, 40)); profileAvatar.setOnClickListener(view -> showAvatarActions());
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(72), dp(72)); avatarParams.setMarginEnd(dp(16)); identityRow.addView(profileAvatar, avatarParams);
        LinearLayout identityCopy = vertical();
        TextView name = text(summaryTitle(), 24, true); name.setMaxLines(2); name.setEllipsize(android.text.TextUtils.TruncateAt.END); identityCopy.addView(name);
        TextView account = text(accountName(user), 14, false); account.setTextColor(colors.secondary); account.setPadding(0, dp(6), 0, 0); identityCopy.addView(account);
        editAvatar = textButton("修改头像", this::showAvatarActions); editAvatar.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        editAvatar.setPadding(0, 0, dp(12), 0); identityCopy.addView(editAvatar);
        identityRow.addView(identityCopy, new LinearLayout.LayoutParams(0, -2, 1f)); identity.addView(identityRow);
        TextView verified = text(repository.isLogoutPending() ? "退出未完成 · 请重试" : session.verified ? "已登录 · 状态已验证" : "已保存登录信息 · 待联网核验", 12, false);
        verified.setTextColor(repository.isLogoutPending() ? colors.error : session.verified ? colors.success : colors.secondary); verified.setPadding(0, dp(12), 0, 0); identity.addView(verified); content.addView(identity);
        LinearLayout details = vertical(); details.setPadding(dp(18), dp(6), dp(18), dp(6)); details.setBackground(shape(colors.card, 22));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2); detailParams.topMargin = dp(18); content.addView(details, detailParams);
        if (!user.username.isEmpty()) detail(details, "用户名", user.username);
        detail(details, "登录方式", "password".equals(user.provider) ? "账号密码" : "qq".equals(user.provider) ? "QQ" : "账号");
        detail(details, "注册时间", date(user.createdAt));
        detail(details, "最近登录", date(user.lastLoginAt));
        status = text(pageMessage, 13, false); status.setTextColor(messageError ? colors.error : colors.secondary);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); status.setPadding(dp(4), dp(16), dp(4), dp(6));
        status.setVisibility(pageMessage.isEmpty() ? View.GONE : View.VISIBLE); content.addView(status);
        refreshButton = button("刷新账号资料", false, this::refresh);
        addLoadingAction(content, refreshButton, false);
        logoutButton = button(repository.isLogoutPending() ? "重试退出登录" : "退出登录", false, () -> {
            if (repository.isLogoutPending()) logout(); else confirmLogout();
        }); logoutButton.setTextColor(colors.error);
        LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(-1, -2); logoutParams.topMargin = dp(10); content.addView(logoutButton, logoutParams);
        TextView note = text("账号退出后，手机中的预设、照片和拍摄项目仍会保留。", 12, false);
        note.setTextColor(colors.secondary); note.setPadding(dp(4), dp(16), dp(4), 0); content.addView(note);
        LinearLayout.LayoutParams privacyParams = new LinearLayout.LayoutParams(-1, -2); privacyParams.topMargin = dp(8);
        content.addView(privacyButton(), privacyParams);
    }

    private void addQqAction(LinearLayout parent) {
        qqButton = button("QQ 快捷登录", true, () -> showQqPrivacy(true));
        qqButton.setBackground(actionBackground(colors.primary, 18));
        FrameLayout action = new FrameLayout(activity); action.addView(qqButton, new FrameLayout.LayoutParams(-1, -1));
        qqLoadingContent = new LinearLayout(activity); qqLoadingContent.setGravity(Gravity.CENTER);
        qqLoadingContent.setPadding(dp(16), dp(12), dp(16), dp(12));
        qqLoadingContent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ProgressBar progress = new ProgressBar(activity);
        progress.setIndeterminateTintList(ColorStateList.valueOf(colors.onPrimary));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(20), dp(20)); progressParams.setMarginEnd(dp(8));
        qqLoadingContent.addView(progress, progressParams);
        qqLoadingLabel = text("", 15, false); qqLoadingLabel.setTextColor(colors.onPrimary);
        qqLoadingContent.addView(qqLoadingLabel, new LinearLayout.LayoutParams(-2, -2));
        action.addView(qqLoadingContent, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        qqLoadingContent.setVisibility(View.GONE); parent.addView(action, new LinearLayout.LayoutParams(-1, -2));
    }

    private Button privacyButton() {
        Button link = button("QQ 登录与隐私说明", false, () -> showQqPrivacy(false)); link.setTextSize(13);
        link.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(colors.primaryContainer), shape(Color.TRANSPARENT, 14), null));
        return link;
    }

    /** No QQ SDK API is called until the user explicitly accepts this disclosure. */
    private void showQqPrivacy(boolean continueLogin) {
        if (destroyed || !isOpen() || !host.isVisible() || qqPrivacyDialog != null) return;
        if (continueLogin && (working || restoring || repository.isLogoutPending() || screen != Screen.LOGIN)) return;
        clearSecrets(); hideKeyboard();
        final long consentEpoch = epoch;
        LinearLayout body = vertical(); body.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView disclosure = text("QQ 登录为可选功能，取消后仍可使用账号密码登录或游客模式。\n\n"
                + "我们使用深圳市腾讯计算机系统有限公司提供的 QQ 互联 SDK，仅在您点击“同意并继续”后启用，用于发起 QQ 授权。腾讯 SDK 会处理设备型号、操作系统和 QQ/TIM 安装情况等信息，用于授权及兼容性问题排查；具体范围可查看下方腾讯声明。\n\n"
                + "您授权后，我们通过加密连接验证授权，并保存应用内身份标识、昵称、头像及注册和登录记录，用于建立账号和展示资料。QQ 授权凭据仅用于本次验证，不保存在本应用账号存储或服务端数据库中；手机只加密保存本应用的登录会话和必要资料。\n\n"
                + "首次使用 QQ 登录会建立独立账号，不会自动合并已有账号密码账户。\n\n"
                + "您可随时退出本应用账号，并在 QQ 授权管理中撤回授权。退出登录不会删除服务器账号资料，也不会删除手机中的照片、预设和拍摄项目。", 14, false);
        disclosure.setLineSpacing(dp(3), 1f); body.addView(disclosure);
        Button official = button("查看腾讯 QQ 互联 SDK 隐私声明", false, this::openQqPrivacy);
        LinearLayout.LayoutParams officialParams = new LinearLayout.LayoutParams(-1, -2); officialParams.topMargin = dp(12); body.addView(official, officialParams);
        ScrollView scroll = new ScrollView(activity); scroll.addView(body);
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("QQ 登录与隐私说明").setView(scroll)
                .setNegativeButton(continueLogin ? "暂不使用" : "关闭", null);
        if (continueLogin) dialog.setPositiveButton("同意并继续", (view, which) -> {
            if (!destroyed && epoch == consentEpoch && screen == Screen.LOGIN && host.isVisible() && !working && !restoring) startQqLogin();
        });
        qqPrivacyDialog = dialog.create(); qqPrivacyDialog.setOnDismissListener(view -> qqPrivacyDialog = null); qqPrivacyDialog.show();
    }

    private void openQqPrivacy() {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://wiki.connect.qq.com/qq互联sdk隐私保护声明"))); }
        catch (android.content.ActivityNotFoundException missing) { setMessage("未找到浏览器，请稍后在可上网的设备查看 QQ 互联 SDK 隐私声明。", true); }
    }

    private void startQqLogin() {
        if (destroyed || working || restoring || screen != Screen.LOGIN || repository.isLogoutPending()) return;
        qqRequestEpoch = epoch; qqAuthorizing = true; working = true;
        setMessage("", false); updateWorkingState(); qqLogin.start();
    }

    private void finishQqAuthorization(String message, boolean error) {
        if (destroyed || !qqAuthorizing) return;
        qqAuthorizing = false; working = false;
        if (epoch == qqRequestEpoch && isOpen()) { setMessage(message, error); updateWorkingState(); }
        else completeOutsideEpoch();
    }

    private void exchangeQqAuthorization(String accessToken) {
        if (destroyed || !qqAuthorizing || epoch != qqRequestEpoch || screen != Screen.LOGIN) return;
        qqAuthorizing = false; qqExchanging = true; updateWorkingState();
        final long requestEpoch = qqRequestEpoch;
        network.execute(() -> {
            Exception failure = null;
            try { repository.loginWithQq(accessToken); } catch (Exception problem) { failure = problem; }
            Exception result = failure;
            onUi(() -> {
                session = repository.cachedSession(); working = false; qqExchanging = false;
                if (requestEpoch == epoch && isOpen()) {
                    if (result == null) {
                        clearSecrets(); screen = Screen.PROFILE; pageMessage = ""; messageError = false; discardView(); changed();
                        if (host.isVisible()) host.onFeedback("QQ 登录成功", "您已登录账号。");
                    } else if (hostResumed && result instanceof AccountException && "CHALLENGE_REQUIRED".equals(((AccountException) result).code)) {
                        pendingQqToken = accessToken; showQqChallenge();
                    } else { setMessage(readable(result), true); updateWorkingState(); }
                } else completeOutsideEpoch();
            });
        });
    }

    private void cancelQqAuthorization() {
        if (!qqAuthorizing) return;
        qqAuthorizing = false; working = false; qqLogin.cancel();
    }

    public boolean onActivityResult(int request, int result, Intent data) {
        if (request != REQUEST_AVATAR) return qqLogin.onActivityResult(request, result, data);
        if (!avatarPickerPending) return true;
        avatarPickerPending = false;
        final AccountRepository.Session owner = avatarOwner; final long attempt = avatarRequestEpoch;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) { avatarOwner = null; return true; }
        if (!avatarOwnerMatches(owner, attempt)) { avatarOwner = null; session = repository.cachedSession(); completeOutsideEpoch(); return true; }
        final Uri uri = data.getData(); working = true; setMessage("正在准备头像…", false); updateWorkingState();
        network.execute(() -> {
            cc.huevia.account.AvatarImages.Prepared prepared = null; Exception failure = null;
            try { prepared = cc.huevia.account.AvatarImages.prepare(activity.getApplicationContext(), uri); }
            catch (Exception problem) { failure = problem; }
            final cc.huevia.account.AvatarImages.Prepared ready = prepared; final Exception error = failure;
            activity.runOnUiThread(() -> {
                if (destroyed || activity.isFinishing()) { if (ready != null) ready.close(); return; }
                working = false;
                if (!avatarOwnerMatches(owner, attempt)) { if (ready != null) ready.close(); avatarOwner = null; session = repository.cachedSession(); completeOutsideEpoch(); return; }
                if (error != null) { avatarOwner = null; setMessage(readable(error), true); updateWorkingState(); return; }
                avatarDraft = ready; setMessage("", false); updateWorkingState(); if (hostResumed) showAvatarPreview();
            });
        });
        return true;
    }
    public void onHostResumed() {
        hostResumed = true; qqLogin.onHostResumed();
        if (avatarDraft != null && avatarDialog == null) showAvatarPreview();
        if (screen == Screen.REGISTER && challengeId.isEmpty() && !challengeLoading) fetchChallenge(null);
    }

    private boolean avatarOwnerMatches(AccountRepository.Session owner, long attempt) {
        AccountRepository.Session current = repository.cachedSession();
        return !destroyed && attempt == epoch && screen == Screen.PROFILE && owner != null && current != null
                && owner.user.id.equals(current.user.id) && owner.user.updatedAt.equals(current.user.updatedAt) && !repository.isLogoutPending();
    }

    private void showAvatarActions() {
        if (destroyed || working || restoring || screen != Screen.PROFILE || session == null || !host.isVisible() || repository.isLogoutPending()) return;
        final AccountRepository.Session owner = repository.cachedSession(); final long attempt = epoch;
        String restoreLabel = owner != null && "qq".equals(owner.user.provider) ? "恢复 QQ 头像" : "恢复默认头像";
        avatarDialog = new AlertDialog.Builder(activity).setTitle("修改头像")
                .setItems(new String[]{"从相册选择", restoreLabel}, (dialog, which) -> {
                    avatarDialog = null;
                    if (!avatarOwnerMatches(owner, attempt)) return;
                    if (which == 0) chooseAvatar(owner, attempt); else confirmDefaultAvatar(owner, attempt);
                }).setNegativeButton("取消", null).create();
        AlertDialog shown = avatarDialog; shown.setOnDismissListener(dialog -> { if (avatarDialog == shown) avatarDialog = null; }); shown.show();
    }

    private void chooseAvatar(AccountRepository.Session owner, long attempt) {
        Intent picker = new Intent(Build.VERSION.SDK_INT >= 33 ? android.provider.MediaStore.ACTION_PICK_IMAGES : Intent.ACTION_OPEN_DOCUMENT);
        picker.setType("image/*"); picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (Build.VERSION.SDK_INT < 33) picker.addCategory(Intent.CATEGORY_OPENABLE);
        clearAvatarDraft(); avatarOwner = owner; avatarRequestEpoch = attempt; avatarPickerPending = true;
        try { activity.startActivityForResult(picker, REQUEST_AVATAR); }
        catch (android.content.ActivityNotFoundException | SecurityException error) {
            avatarPickerPending = false; avatarOwner = null; setMessage("无法打开照片选择器，请稍后重试。", true);
        }
    }

    private void showAvatarPreview() {
        final cc.huevia.account.AvatarImages.Prepared draft = avatarDraft;
        final AccountRepository.Session owner = avatarOwner; final long attempt = avatarRequestEpoch;
        if (draft == null || !hostResumed) return;
        if (!avatarOwnerMatches(owner, attempt) || !host.isVisible()) { clearAvatarDraft(); avatarOwner = null; return; }
        LinearLayout body = vertical(); body.setGravity(Gravity.CENTER_HORIZONTAL); body.setPadding(dp(24), dp(8), dp(24), dp(8));
        UserAvatarView preview = new UserAvatarView(activity); preview.showPreview(draft.preview); preview.setContentDescription("新头像预览");
        body.addView(preview, new LinearLayout.LayoutParams(dp(160), dp(160)));
        TextView note = text("照片已居中裁为正方形，确认后用于账号与社区展示。", 14, false);
        note.setTextColor(colors.secondary); note.setPadding(0, dp(16), 0, 0); note.setGravity(Gravity.CENTER); body.addView(note);
        avatarDialog = new AlertDialog.Builder(activity).setTitle("确认新头像").setView(body).setNegativeButton("取消", null)
                .setPositiveButton("保存头像", (dialog, which) -> {
                    if (!avatarOwnerMatches(owner, attempt) || working) return;
                    avatarDraft = null; avatarOwner = null; saveAvatar(owner, attempt, draft);
                }).create();
        AlertDialog shown = avatarDialog;
        shown.setOnDismissListener(dialog -> {
            if (avatarDialog == shown) avatarDialog = null;
            if (avatarDraft == draft && hostResumed) { clearAvatarDraft(); avatarOwner = null; }
        }); shown.show();
    }

    private void confirmDefaultAvatar(AccountRepository.Session owner, long attempt) {
        boolean qq = "qq".equals(owner.user.provider);
        avatarDialog = new AlertDialog.Builder(activity).setTitle(qq ? "恢复 QQ 头像？" : "恢复默认头像？")
                .setMessage(qq ? "将使用最近一次 QQ 登录时验证的头像。" : "将使用统一的默认用户头像。")
                .setNegativeButton("取消", null).setPositiveButton("恢复", (dialog, which) -> {
                    if (avatarOwnerMatches(owner, attempt) && !working) saveAvatar(owner, attempt, null);
                }).create();
        AlertDialog shown = avatarDialog; shown.setOnDismissListener(dialog -> { if (avatarDialog == shown) avatarDialog = null; }); shown.show();
    }

    private void saveAvatar(AccountRepository.Session owner, long attempt, cc.huevia.account.AvatarImages.Prepared draft) {
        working = true; setMessage(draft == null ? "正在恢复头像…" : "正在保存头像…", false); updateWorkingState();
        network.execute(() -> {
            Exception failure = null;
            try {
                String installation = AccountInstallation.id(activity.getApplicationContext());
                if (draft == null) repository.restoreDefaultAvatar(owner, installation); else repository.updateAvatar(owner, draft.jpeg, installation);
            } catch (Exception problem) { failure = problem; }
            finally { if (draft != null) draft.close(); }
            final Exception error = failure;
            onUi(() -> {
                working = false; session = repository.cachedSession();
                if (attempt != epoch || screen != Screen.PROFILE || session == null || !owner.user.id.equals(session.user.id)) { completeOutsideEpoch(); return; }
                pageMessage = error == null ? "头像已更新。" : readable(error); messageError = error != null;
                discardView(); changed();
                if (error instanceof AccountException && "USER_CONFLICT".equals(((AccountException) error).code)) refresh();
            });
        });
    }

    private void clearAvatarDraft() { if (avatarDraft != null) { avatarDraft.close(); avatarDraft = null; } }

    private void submit() {
        if (destroyed || working || restoring || username == null || password == null) return;
        String name = username.getText().toString().trim().toLowerCase(Locale.ROOT);
        String secret = password.getText().toString();
        boolean registering = screen == Screen.REGISTER;
        username.setError(null); password.setError(null); if (confirmation != null) confirmation.setError(null);
        if (!name.matches("[a-z][a-z0-9_]{2,31}")) {
            username.setError("用户名为 3–32 位，首位为英文字母，其余可用字母、数字和下划线。"); username.requestFocus(); return;
        }
        if (secret.length() < 8 || secret.length() > 128) { password.setError("密码需要 8–128 位，首尾空格也计入。"); password.requestFocus(); return; }
        if (registering && (confirmation == null || !secret.equals(confirmation.getText().toString()))) {
            if (confirmation != null) { confirmation.setError("两次输入的密码不一致。"); confirmation.requestFocus(); } return;
        }
        final String proof = challengeAnswer == null ? "" : challengeAnswer.getText().toString().trim();
        final String proofId = challengeId, installation = installationId;
        if (registering && (challengeLoading || proofId.isEmpty() || !proof.matches("[A-Za-z0-9]{4,8}"))) {
            if (challengeAnswer != null) { challengeAnswer.setError("请填写图片验证码。"); challengeAnswer.requestFocus(); }
            setMessage("请先完成图片验证；看不清时可以换一张。", true); return;
        }
        usernameDraft = name; username.setText(name); hideKeyboard();
        final long requestEpoch = epoch;
        working = true; setMessage("", false); updateWorkingState();
        network.execute(() -> {
            Exception failure = null;
            try { if (registering) repository.register(name, secret, proofId, proof, installation); else repository.login(name, secret); }
            catch (Exception problem) { failure = problem; }
            Exception result = failure;
            onUi(() -> {
                session = repository.cachedSession(); working = false;
                if (requestEpoch == epoch && isOpen()) {
                    if (result == null) {
                        clearSecrets(); screen = Screen.PROFILE; pageMessage = ""; messageError = false; discardView(); changed();
                        if (host.isVisible()) host.onFeedback(registering ? "注册成功" : "登录成功", "您已登录账号。" );
                    } else { clearSecrets(); setMessage(readable(result), true); updateWorkingState(); if (registering) fetchChallenge(null); }
                } else completeOutsideEpoch();
            });
        });
    }

    private void refresh() {
        if (destroyed || working || restoring || session == null || !isOpen()) return;
        final long requestEpoch = epoch;
        working = true; setMessage("正在核验登录状态…", false); updateWorkingState();
        network.execute(() -> {
            Exception failure = null;
            try { repository.refresh(); } catch (Exception problem) { failure = problem; }
            Exception result = failure;
            onUi(() -> {
                session = repository.cachedSession(); working = false;
                if (requestEpoch == epoch && isOpen()) {
                    if (repository.isLogoutPending()) {
                        screen = session == null ? Screen.LOGIN : Screen.PROFILE;
                        pageMessage = readable(result); messageError = true;
                    } else if (session == null) {
                        clearSecrets(); screen = Screen.LOGIN; pageMessage = result == null ? "请重新登录。" : readable(result); messageError = true;
                    } else { pageMessage = result == null ? "账号资料已更新。" : readable(result); messageError = result != null; }
                    discardView(); changed();
                } else completeOutsideEpoch();
            });
        });
    }

    private void confirmLogout() {
        if (working || session == null || !host.isVisible()) return;
        final long requestEpoch = epoch;
        logoutDialog = new AlertDialog.Builder(activity).setTitle("退出当前账号？")
                .setMessage("手机中的预设、照片和拍摄项目会保留，您可以继续以游客使用。")
                .setNegativeButton("取消", null).setPositiveButton("退出登录", (dialog, which) -> {
                    if (!destroyed && epoch == requestEpoch && isOpen() && !working) logout();
                }).create();
        logoutDialog.setOnDismissListener(dialog -> logoutDialog = null); logoutDialog.show();
    }

    private void logout() {
        final long requestEpoch = epoch;
        working = true; setMessage("正在退出登录…", false); updateWorkingState();
        network.execute(() -> {
            Exception failure = null;
            try { repository.logout(); } catch (Exception problem) { failure = problem; }
            Exception result = failure;
            onUi(() -> {
                session = repository.cachedSession(); working = false;
                if (requestEpoch == epoch && isOpen()) {
                    if (repository.isLogoutPending()) {
                        screen = session == null ? Screen.LOGIN : Screen.PROFILE;
                        pageMessage = readable(result); messageError = true; discardView(); changed();
                    } else if (session == null) {
                        clearSecrets(); epoch++; screen = Screen.CLOSED; usernameDraft = ""; discardView(); changed();
                        if (host.isVisible()) host.onFeedback("已退出登录", result == null ? "可以继续以游客使用，手机中的内容已保留。" : readable(result));
                    } else { setMessage(readable(result), true); updateWorkingState(); }
                } else completeOutsideEpoch();
            });
        });
    }

    private EditText input(LinearLayout parent, String label, String hint, boolean secret, String autofill) {
        TextView caption = text(label, 13, true); caption.setPadding(0, parent.getChildCount() == 0 ? 0 : dp(20), 0, dp(8)); parent.addView(caption);
        LinearLayout fieldContainer = new LinearLayout(activity); fieldContainer.setGravity(Gravity.CENTER_VERTICAL); fieldContainer.setAddStatesFromChildren(true);
        StateListDrawable fieldBackground = new StateListDrawable();
        GradientDrawable focused = shape(colors.card, 14); focused.setStroke(dp(1), colors.primary);
        GradientDrawable normal = shape(colors.card, 14); normal.setStroke(dp(1), colors.outline);
        fieldBackground.addState(new int[] { android.R.attr.state_focused }, focused);
        fieldBackground.addState(new int[] {}, normal); fieldContainer.setBackground(fieldBackground);
        EditText field = new EditText(activity); field.setId(View.generateViewId()); caption.setLabelFor(field.getId());
        field.setContentDescription(label);
        field.setTextColor(colors.text); field.setHintTextColor(colors.secondary); field.setHint(hint); field.setTextSize(16);
        field.setSingleLine(true); field.setMinHeight(dp(54)); field.setPadding(dp(16), dp(12), dp(16), dp(12)); field.setBackground(null);
        field.setSelectAllOnFocus(false); field.setSaveEnabled(false); field.setFreezesText(false);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_NORMAL));
        if (secret) { field.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); field.setTransformationMethod(PasswordTransformationMethod.getInstance()); }
        if (Build.VERSION.SDK_INT >= 26) field.setAutofillHints(autofill);
        fieldContainer.addView(field, new LinearLayout.LayoutParams(0, -2, 1f));
        if (secret) {
            ImageButton toggle = new ImageButton(activity); toggle.setImageTintList(ColorStateList.valueOf(colors.secondary));
            toggle.setStateListAnimator(null); toggle.setElevation(0f); toggle.setTranslationZ(0f);
            toggle.setBackground(actionBackground(Color.TRANSPARENT, 24)); toggle.setPadding(dp(14), dp(14), dp(14), dp(14));
            toggle.setOnClickListener(view -> setPasswordVisible(!passwordVisible));
            if ("确认密码".equals(label)) showConfirmation = toggle; else showPassword = toggle;
            updateVisibilityButton(toggle); fieldContainer.addView(toggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        parent.addView(fieldContainer, new LinearLayout.LayoutParams(-1, -2)); return field;
    }

    private void setPasswordVisible(boolean visible) {
        passwordVisible = visible; reveal(password, visible); reveal(confirmation, visible);
        updateVisibilityButton(showPassword); updateVisibilityButton(showConfirmation);
    }
    private void updateVisibilityButton(ImageButton button) {
        if (button == null) return;
        button.setImageResource(passwordVisible ? R.drawable.ic_account_password_hidden : R.drawable.ic_account_password_visible);
        button.setContentDescription(passwordVisible ? "隐藏密码" : "显示密码"); button.setSelected(passwordVisible);
    }

    private void detail(LinearLayout parent, String label, String value) {
        LinearLayout row = vertical(); row.setPadding(0, dp(12), 0, dp(12));
        TextView caption = text(label, 12, false); caption.setTextColor(colors.secondary); row.addView(caption);
        TextView detail = text(value, 15, false); detail.setTextIsSelectable(true); detail.setPadding(0, dp(4), 0, 0); row.addView(detail); parent.addView(row);
    }

    private void updateWorkingState() {
        boolean enabled = !working && !restoring;
        boolean loading = !enabled;
        boolean canAuthenticate = enabled && !repository.isLogoutPending();
        if (username != null) username.setEnabled(canAuthenticate);
        if (password != null) password.setEnabled(canAuthenticate);
        if (confirmation != null) confirmation.setEnabled(canAuthenticate);
        if (showPassword != null) showPassword.setEnabled(canAuthenticate);
        if (showConfirmation != null) showConfirmation.setEnabled(canAuthenticate);
        if (submit != null) {
            submit.setEnabled(canAuthenticate); submit.setAlpha(canAuthenticate || loading ? 1f : .65f);
            boolean passwordLoading = loading && !qqAuthorizing && !qqExchanging;
            updateLoadingAction(submit, true, passwordLoading, restoring ? "正在读取账号…" : working && passwordLoading ? screen == Screen.REGISTER ? "正在注册…" : "正在登录…" : screen == Screen.REGISTER ? "注册并登录" : "登录");
        }
        if (qqButton != null) {
            boolean qqLoading = restoring || working;
            String label = restoring ? "正在读取账号…" : qqAuthorizing ? "等待 QQ 授权…" : qqExchanging ? "正在登录…" : working ? "正在处理账号…" : "QQ 快捷登录";
            qqButton.setEnabled(canAuthenticate); qqButton.setAlpha(canAuthenticate || qqLoading ? 1f : .65f);
            qqButton.setText(label); qqButton.setContentDescription(label); qqButton.setTextColor(qqLoading ? Color.TRANSPARENT : colors.onPrimary);
            if (qqLoadingLabel != null) qqLoadingLabel.setText(label);
            if (qqLoadingContent != null) qqLoadingContent.setVisibility(qqLoading ? View.VISIBLE : View.GONE);
        }
        if (changeMode != null) { changeMode.setEnabled(canAuthenticate); changeMode.setAlpha(canAuthenticate ? 1f : .45f); }
        if (loginMethod != null) { loginMethod.setEnabled(canAuthenticate); loginMethod.setAlpha(canAuthenticate ? 1f : .45f); }
        if (refreshButton != null) {
            refreshButton.setEnabled(canAuthenticate);
            updateLoadingAction(refreshButton, false, loading, loading ? "正在处理…" : "刷新账号资料");
        }
        if (logoutButton != null) logoutButton.setEnabled(enabled);
        boolean avatarEnabled = canAuthenticate && !avatarPickerPending;
        if (editAvatar != null) editAvatar.setEnabled(avatarEnabled);
        if (profileAvatar != null) { profileAvatar.setEnabled(avatarEnabled); profileAvatar.setAlpha(avatarEnabled ? 1f : .6f); }
    }

    private void setMessage(String message, boolean error) {
        pageMessage = message; messageError = error;
        if (status != null) { status.setText(message); status.setTextColor(error ? colors.error : colors.secondary); status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE); }
    }

    public void onHostPaused() { hostResumed = false; dismissDialog(); clearSecrets(); pendingQqToken = null; clearChallenge(); qqLogin.onHostPaused(); }
    private void completeOutsideEpoch() {
        // An old request may update the saved identity, but never reopens a page or announces an error there.
        if (isOpen()) {
            if (session != null) screen = Screen.PROFILE;
            else if (screen == Screen.PROFILE) screen = Screen.LOGIN;
            discardView();
        }
        changed();
    }
    private void rememberUsername() { if (username != null) usernameDraft = username.getText().toString(); }
    private void clearSecrets() {
        if (password != null) password.getText().clear();
        if (confirmation != null) confirmation.getText().clear();
        if (challengeAnswer != null) challengeAnswer.getText().clear();
        setPasswordVisible(false);
    }
    private void discardView() {
        clearSecrets(); root = null; viewConfiguration = ""; username = password = confirmation = null; showPassword = showConfirmation = null;
        status = null; submit = changeMode = refreshButton = logoutButton = null;
        qqButton = loginMethod = null; qqLoadingContent = null; qqLoadingLabel = null;
        spinner = null; loadingContent = null; loadingLabel = null;
        challengeAnswer = null; challengeImage = null;
        profileAvatar = null; editAvatar = null;
    }
    private void dismissDialog() {
        if (avatarDialog != null) { AlertDialog shown = avatarDialog; avatarDialog = null; shown.dismiss(); }
        if (challengeDialog != null) { challengeDialog.dismiss(); challengeDialog = null; }
        if (logoutDialog != null) { logoutDialog.dismiss(); logoutDialog = null; }
        if (qqPrivacyDialog != null) { qqPrivacyDialog.dismiss(); qqPrivacyDialog = null; }
        if (loginHelpDialog != null) { loginHelpDialog.dismiss(); loginHelpDialog = null; }
    }

    private void clearChallenge() { challengeEpoch++; challengeLoading = false; challengeId = ""; challengeRequested = false; challengeBitmap = null; }

    private void addChallenge(LinearLayout form) {
        TextView label = text("安全验证", 13, true); label.setPadding(0, dp(20), 0, dp(8)); form.addView(label);
        challengeImage = new ImageView(activity); challengeImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        challengeImage.setContentDescription("图片验证码，点击更换"); challengeImage.setBackground(shape(colors.container, 12));
        challengeImage.setImageBitmap(challengeBitmap); challengeImage.setOnClickListener(view -> fetchChallenge(null));
        form.addView(challengeImage, new LinearLayout.LayoutParams(-1, dp(84)));
        challengeAnswer = input(form, "验证码", "输入图片中的字符", false, "");
        challengeAnswer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        Button replace = textButton("看不清，换一张", () -> fetchChallenge(null)); form.addView(replace);
        if (!challengeRequested) { challengeRequested = true; form.post(() -> fetchChallenge(null)); }
    }

    private void fetchChallenge(Runnable complete) {
        if (destroyed || challengeLoading || !isOpen()) return;
        challengeLoading = true; challengeId = ""; final long attempt = epoch, proofAttempt = ++challengeEpoch;
        if (challengeAnswer != null) challengeAnswer.getText().clear();
        setMessage("正在获取验证码…", false);
        network.execute(() -> {
            String id = "", installation = ""; android.graphics.Bitmap bitmap = null; Exception failure = null;
            try {
                installation = AccountInstallation.id(activity.getApplicationContext());
                java.util.Map<String, Object> result = repository.challenge(installation);
                id = cc.huevia.account.AccountJson.string(result, "id", true, 36);
                String image = cc.huevia.account.AccountJson.string(result, "image", true, 60000);
                if (!image.startsWith("data:image/png;base64,")) throw new java.io.IOException("验证码图片无效。");
                byte[] bytes = android.util.Base64.decode(image.substring(22), android.util.Base64.DEFAULT);
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); options.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth < 1 || options.outHeight < 1 || options.outWidth > 1024 || options.outHeight > 512) throw new java.io.IOException("验证码图片无效。");
                bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) throw new java.io.IOException("验证码图片无效。");
            } catch (Exception problem) { failure = problem; }
            final String proofId = id, installationValue = installation; final android.graphics.Bitmap picture = bitmap; final Exception result = failure;
            onUi(() -> {
                if (proofAttempt != challengeEpoch) return;
                challengeLoading = false;
                if (attempt != epoch || !isOpen() || !hostResumed) return;
                if (result != null) { setMessage(readable(result), true); return; }
                challengeId = proofId; installationId = installationValue; challengeBitmap = picture;
                if (challengeImage != null) challengeImage.setImageBitmap(picture);
                setMessage("", false); if (complete != null) complete.run();
            });
        });
    }

    private void showQqChallenge() {
        updateWorkingState();
        if (pendingQqToken == null) return;
        fetchChallenge(() -> {
            if (pendingQqToken == null || !isOpen()) return;
            LinearLayout form = vertical(); form.setPadding(dp(24), dp(8), dp(24), 0);
            ImageView picture = new ImageView(activity); picture.setImageBitmap(challengeBitmap);
            picture.setScaleType(ImageView.ScaleType.FIT_CENTER); form.addView(picture, new LinearLayout.LayoutParams(-1, dp(90)));
            EditText answer = input(form, "验证码", "输入图片中的字符", false, "");
            answer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            challengeDialog = new AlertDialog.Builder(activity).setTitle("完成验证后创建账号").setView(form)
                    .setNegativeButton("取消", (dialog, which) -> pendingQqToken = null).setNeutralButton("换一张", (dialog, which) -> showQqChallenge())
                    .setPositiveButton("继续", null).create();
            challengeDialog.setOnCancelListener(dialog -> pendingQqToken = null);
            challengeDialog.setOnShowListener(dialog -> challengeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                final String proof = answer.getText().toString().trim(), id = challengeId, installation = installationId;
                if (!proof.matches("[A-Za-z0-9]{4,8}")) { answer.setError("请填写图片中的字符。"); return; }
                final String token = pendingQqToken; pendingQqToken = null;
                if (token == null) { challengeDialog.dismiss(); return; }
                challengeDialog.dismiss(); working = qqExchanging = true; updateWorkingState(); final long attempt = epoch;
                network.execute(() -> {
                    Exception failure = null;
                    try { repository.loginWithQq(token, id, proof, installation); } catch (Exception problem) { failure = problem; }
                    final Exception result = failure;
                    onUi(() -> {
                        working = qqExchanging = false; session = repository.cachedSession();
                        if (attempt != epoch || !isOpen()) { completeOutsideEpoch(); return; }
                        if (result == null) { clearSecrets(); clearChallenge(); screen = Screen.PROFILE; discardView(); changed(); host.onFeedback("QQ 登录成功", "您已登录账号。"); }
                        else { setMessage(readable(result), true); updateWorkingState(); }
                    });
                });
            })); challengeDialog.show();
        });
    }
    private void changed() { if (!destroyed && host.isVisible()) host.onChanged(); }
    private void onUi(Runnable task) { activity.runOnUiThread(() -> { if (!destroyed && !activity.isFinishing()) task.run(); }); }
    private void hideKeyboard() {
        View focus = activity.getCurrentFocus(); InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (focus != null && keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }
    private static char[] secret(EditText field) { return field == null ? new char[0] : field.getText().toString().toCharArray(); }
    private static void reveal(EditText field, boolean visible) {
        if (field == null) return; int selection = field.getSelectionStart();
        field.setTransformationMethod(visible ? null : PasswordTransformationMethod.getInstance());
        field.setSelection(Math.max(0, Math.min(selection, field.length())));
    }
    private static String accountName(AccountRepository.User user) { return "qq".equals(user.provider) ? "QQ 账号" : user.username.isEmpty() ? "账号已登录" : "@" + user.username; }
    private static String date(String value) {
        try { return DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA).withZone(ZoneId.systemDefault()).format(Instant.parse(value)); }
        catch (RuntimeException invalid) { return "暂无记录"; }
    }
    private static String readable(Exception failure) {
        if (failure instanceof AccountException) return failure.getMessage() == null ? "账号暂时不可用，请稍后重试。" : failure.getMessage();
        return "暂时无法连接账号服务。请使用可上网的网络后重试，相机功能仍可继续使用。";
    }
    private LinearLayout vertical() { LinearLayout view = new LinearLayout(activity); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String value, int size, boolean bold) { TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(colors.text); view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL)); return view; }
    private void addLoadingAction(LinearLayout parent, Button button, boolean primary) {
        FrameLayout action = new FrameLayout(activity);
        // The button keeps its full width. Loading content overlays its center instead of taking space beside it.
        action.addView(button, new FrameLayout.LayoutParams(-1, -1));
        loadingContent = new LinearLayout(activity); loadingContent.setGravity(Gravity.CENTER);
        loadingContent.setPadding(dp(16), dp(12), dp(16), dp(12));
        loadingContent.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        loadingContent.setClickable(false); loadingContent.setFocusable(false);
        int foreground = primary ? colors.onPrimary : colors.primary;
        spinner = new ProgressBar(activity); spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(foreground));
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(dp(20), dp(20)); spinnerParams.setMarginEnd(dp(8));
        loadingContent.addView(spinner, spinnerParams);
        loadingLabel = text("", 15, false); loadingLabel.setTextColor(foreground);
        loadingContent.addView(loadingLabel, new LinearLayout.LayoutParams(-2, -2));
        action.addView(loadingContent, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        loadingContent.setVisibility(View.GONE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(18);
        parent.addView(action, params);
    }
    private void updateLoadingAction(Button button, boolean primary, boolean loading, String label) {
        // Keep the actual label for measurement and accessibility, while the overlay draws the busy label.
        button.setText(label); button.setTextColor(loading ? Color.TRANSPARENT : primary ? colors.onPrimary : colors.primary);
        button.setContentDescription(label);
        if (loadingLabel != null) loadingLabel.setText(label);
        if (loadingContent != null) loadingContent.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
    private Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(activity); button.setText(label); button.setAllCaps(false); button.setTextSize(15); button.setMinHeight(dp(52)); button.setMinimumHeight(dp(52));
        // Native Button elevation survives a custom background and is clipped by loading wrappers.
        // Keep these actions flat; their RippleDrawable supplies the pressed feedback.
        button.setStateListAnimator(null); button.setElevation(0f); button.setTranslationZ(0f);
        button.setTextColor(primary ? colors.onPrimary : colors.primary); button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(colors.primaryContainer), shape(primary ? colors.primary : colors.card, 26), null));
        button.setPadding(dp(16), dp(12), dp(16), dp(12)); button.setOnClickListener(view -> action.run()); return button;
    }
    private Button textButton(String label, Runnable action) {
        Button link = button(label, false, action); link.setMinHeight(dp(48)); link.setMinimumHeight(dp(48));
        link.setMinWidth(0); link.setMinimumWidth(0); link.setPadding(dp(12), dp(10), dp(12), dp(10));
        link.setBackground(actionBackground(Color.TRANSPARENT, 14)); return link;
    }
    private android.graphics.drawable.RippleDrawable actionBackground(int fill, int radius) {
        return new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(colors.primaryContainer), shape(fill, radius), shape(Color.WHITE, radius));
    }
    private GradientDrawable shape(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    @Override public void close() {
        if (destroyed) return; destroyed = true; epoch++; qqLogin.close(); dismissDialog(); clearAvatarDraft(); avatarOwner = null; avatarPickerPending = false; pendingQqToken = null; clearChallenge(); clearSecrets(); discardView(); network.shutdownNow();
    }
}

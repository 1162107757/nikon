package cc.huevia.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import cc.huevia.R;
import cc.huevia.account.AccountException;
import cc.huevia.account.AccountInstallation;
import cc.huevia.account.AccountRepository;
import cc.huevia.account.AndroidAccounts;
import cc.huevia.community.CommunityException;
import cc.huevia.community.CommunityRepository;
import cc.huevia.community.SubmissionDraftStore;
import cc.huevia.community.SubmissionFlow;
import cc.huevia.community.CommentReplyPages;
import cc.huevia.community.CommentReplyTarget;
import cc.huevia.library.OnlinePreset;
import cc.huevia.ncp.NcpFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import static cc.huevia.community.CommunityRepository.*;

/** Optional online contribution, discussion and inbox. Camera connection ownership is never touched. */
public final class CommunityPanel implements AutoCloseable {
    public static final int PICK_NCP = 7401, PICK_PREVIEW = 7402;
    public interface Host {
        void onChanged(); boolean isVisible();
        default void onUnreadChanged(int count) { onChanged(); }
        void onFeedback(String title, String message);
        void onLoginRequired(); void onDownloadPreset(OnlinePreset preset);
        void onOpenPreset(OnlinePreset preset, String focusCommentId);
    }
    private enum Screen { CLOSED, FEED, DETAIL, SUBMISSIONS, EDITOR, MESSAGES }
    private interface Work<T> { T run() throws Exception; }
    private interface Result<T> { void done(T value); }
    private final Activity activity; private final Host host;
    private final AccountRepository accounts; private final CommunityRepository api;
    private final ExecutorService network = Executors.newSingleThreadExecutor(), images = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Deque<Screen> history = new ArrayDeque<>();
    private Screen screen = Screen.CLOSED; private UiPalette colors; private ScrollView root;
    private LinearLayout inlineComments;
    private String inlineConfiguration = "", inlinePresetId = "";
    private boolean inlineVisible, pendingCommentResume, showingFocusedThread;
    private String viewConfiguration = "";
    private boolean destroyed, loading, writing, hasMore; private long epoch; private int page = 1, unread;
    private String unreadOwnerId=""; private long unreadRevision; private boolean unreadPending,hostResumed;
    private TextView messageSummary; private final Map<String,View> messageRows=new LinkedHashMap<>();
    private final Set<String> pendingReads=new HashSet<>();
    private final Runnable unreadPoll=new Runnable(){@Override public void run(){if(destroyed||!hostResumed)return;refreshUnread();main.postDelayed(this,60000);}};
    private String message = "", category = "", presetId = "", focusCommentId = "", ownerId = "";
    private OnlinePreset selectedPreset;
    private final List<Map<String,Object>> feed = new ArrayList<>(), comments = new ArrayList<>(), submissions = new ArrayList<>(), messages = new ArrayList<>();
    private final CommentReplyPages replyPages = new CommentReplyPages();
    private Map<String,Object> draft = SubmissionDraftStore.fresh();
    private SubmissionDraftStore draftStore;
    private EditText nameInput, descriptionInput, categoryInput, modelsInput;
    private CheckBox rightsInput; private TextView editorNote;
    private boolean restoringDraft; private AlertDialog dialog;
    private Runnable persistEditor; private String commentKey = UUID.randomUUID().toString(), commentText = "", replyParent = "", replyTargetId = "", replyTargetName = "", commentOwnerId = "", renderedCommentOwnerId = "";
    private final android.util.LruCache<String,Bitmap> covers = new android.util.LruCache<String,Bitmap>(8*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap value) { return value.getAllocationByteCount(); }
    };
    private String pendingPickerOwner = ""; private int pendingPickerCode;

    public CommunityPanel(Activity activity, Host host) {
        this.activity = activity; this.host = host; accounts = AndroidAccounts.get(activity); api = new CommunityRepository(accounts);
    }
    public boolean isOpen() { return screen != Screen.CLOSED; }
    public String title() {
        switch (screen) { case FEED:return "在线预设"; case DETAIL:return "预设详情"; case SUBMISSIONS:return "我的投稿";
            case EDITOR:return "发布预设"; case MESSAGES:return "消息"; default:return "Huevia"; }
    }
    public String navigationKey() { return "community:" + screen + ":" + presetId; }
    public int unreadCount() { AccountRepository.Session current=accounts.cachedSession();return current!=null&&!accounts.isLogoutPending()&&current.user.id.equals(unreadOwnerId)?unread:0; }
    /** The detail page owns the ScrollView; only this section is updated by comment callbacks. */
    public View createInlineComments(OnlinePreset preset, UiPalette palette) {
        selectedPreset = preset; return createInlineComments(preset.id, palette);
    }
    public View createInlineComments(String threadId, UiPalette palette) {
        boolean fresh = !threadId.equals(inlinePresetId);
        if (fresh) {
            dismissDialog(); epoch++; loading = false; message = "";
            presetId = inlinePresetId = threadId;
            comments.clear(); replyPages.clear(); page = 1; hasMore = false; showingFocusedThread = false;
            clearCommentDraft(); renderedCommentOwnerId = "";
            inlineComments = null;
        }
        colors = palette; inlineVisible = true;
        String configuration = palette.dark + ":" + activity.getResources().getConfiguration().fontScale
                + ":" + activity.getResources().getConfiguration().screenWidthDp;
        if (inlineComments == null || !configuration.equals(inlineConfiguration)) {
            inlineConfiguration = configuration; inlineComments = vertical(); renderInlineComments();
        }
        if (inlineComments.getParent() instanceof ViewGroup) ((ViewGroup) inlineComments.getParent()).removeView(inlineComments);
        if (fresh) loadComments(false);
        onAccountChanged();
        return inlineComments;
    }
    public void setInlineVisible(boolean visible) { inlineVisible = visible; }
    /** Leaving a preset invalidates queued reads; returning from account keeps the same thread and draft. */
    public void detachInlineComments() {
        if (inlinePresetId.isEmpty()) return;
        dismissDialog(); epoch++; loading = false; inlineVisible = false; inlinePresetId = "";
        inlineComments = null; comments.clear(); replyPages.clear(); focusCommentId = ""; showingFocusedThread = false;
        clearCommentDraft(); renderedCommentOwnerId = "";
    }
    public void focusInlineComment(String commentId) { focusCommentId = commentId == null ? "" : commentId; }
    public void onAccountChanged() {
        AccountRepository.Session current = accounts.cachedSession();
        String currentOwner = current == null || accounts.isLogoutPending() ? "" : current.user.id;
        boolean ownershipChanged = !currentOwner.equals(renderedCommentOwnerId);
        syncUnreadOwner();
        renderedCommentOwnerId = currentOwner;
        if (!currentOwner.isEmpty() && !commentOwnerId.isEmpty() && !currentOwner.equals(commentOwnerId)) {
            dismissDialog(); clearCommentDraft();
        }
        if (!currentOwner.isEmpty() && commentOwnerId.isEmpty() && pendingCommentResume) commentOwnerId = currentOwner;
        boolean updated = false;
        if (current != null && !inlinePresetId.isEmpty()) {
            updated = refreshOwnAvatars(comments, current.user);
        }
        if (!inlinePresetId.isEmpty() && (ownershipChanged || updated)) renderInlineComments();
        if(ownershipChanged&&screen==Screen.MESSAGES&&!loading&&!currentOwner.isEmpty())loadMessages(false);
        if (pendingCommentResume && inlineVisible && current != null && !accounts.isLogoutPending() && !writing) {
            pendingCommentResume = false; final String target = inlinePresetId;
            main.post(() -> { if (inlineVisible && target.equals(inlinePresetId) && dialog == null) composeComment(replyParent,replyTargetId,replyTargetName); });
        }
    }
    private void clearCommentDraft() {
        commentText = ""; replyParent = ""; replyTargetId = ""; replyTargetName = ""; commentOwnerId = ""; pendingCommentResume = false;
        commentKey = UUID.randomUUID().toString();
    }
    private boolean refreshOwnAvatars(List<?> rows, AccountRepository.User user) {
        boolean updated = false;
        for (Object value : rows) if (value instanceof Map) {
            Map<String,Object> comment = (Map<String,Object>) value;
            Object author = comment.get("author");
            if (author instanceof Map) {
                Map<String,Object> person = (Map<String,Object>) author;
                if (user.id.equals(text(person,"id")) && (!user.avatarUrl.equals(text(person,"avatarUrl")) || !user.nickname.equals(text(person,"nickname")))) {
                    person.put("avatarUrl",user.avatarUrl); person.put("nickname",user.nickname); updated = true;
                }
            }
            if (comment.get("replies") instanceof List) updated |= refreshOwnAvatars((List<?>) comment.get("replies"),user);
        }
        return updated;
    }
    private void renderInlineComments() {
        if (inlineComments == null) return;
        AccountRepository.Session active = accounts.cachedSession();
        renderedCommentOwnerId = active == null || accounts.isLogoutPending() ? "" : active.user.id;
        ScrollView parent = null;
        for (android.view.ViewParent ancestor = inlineComments.getParent(); ancestor != null; ancestor = ancestor.getParent())
            if (ancestor instanceof ScrollView) { parent = (ScrollView) ancestor; break; }
        final ScrollView scroll = parent; final int position = scroll == null ? 0 : scroll.getScrollY();
        boolean focusing = !focusCommentId.isEmpty();
        inlineComments.removeAllViews(); gap(inlineComments, 28); divider(inlineComments); gap(inlineComments, 8);
        buildComments(inlineComments);
        if (!message.isEmpty()) {
            TextView note = label(message, 14, false); note.setTextColor(colors.error); note.setPadding(0, dp(12), 0, dp(4));
            note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); inlineComments.addView(note);
            if (!loading && !writing) addAction(inlineComments, "重新加载评论", false, () -> loadComments(false));
        }
        if (loading || writing) {
            ProgressBar progress = new ProgressBar(activity); progress.setContentDescription(writing ? "正在发送评论" : "正在加载评论");
            progress.setIndeterminateTintList(ColorStateList.valueOf(colors.primary));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(28), dp(28)); params.gravity = Gravity.CENTER_HORIZONTAL; params.topMargin = dp(16);
            inlineComments.addView(progress, params);
        }
        // Replacing comment rows must not reset or move the enclosing preset detail.
        if (scroll != null && !focusing) scroll.post(() -> { if (inlineVisible && scroll.isAttachedToWindow()) scroll.scrollTo(0, position); });
    }
    public void openFeed() { enter(Screen.FEED); feed.clear(); page = 1; loadFeed(false); }
    public void openPreset(OnlinePreset preset) { selectedPreset = preset; openPreset(preset.id); }
    public void openPreset(String id) { try { presetId = id(id); } catch (IOException e) { feedback(e.getMessage()); return; }
        enter(Screen.DETAIL); comments.clear(); page = 1; loadDetail(); }
    public void openSubmissions() { if (!requireLogin()) return; enter(Screen.SUBMISSIONS); submissions.clear(); page = 1; loadSubmissions(); }
    public void openSubmissionComposer() { openEditor(null); }
    public void openMessages() { if (!requireLogin()) return; enter(Screen.MESSAGES); messages.clear(); page = 1; loadMessages(false); }
    private void enter(Screen next) {
        detachInlineComments();
        snapshotEditor(true); dismissDialog(); if (screen != Screen.CLOSED && screen != next) history.push(screen);
        screen = next; epoch++; loading = false; message = ""; changed();
    }
    public void goBack() {
        if (!isOpen()) return; snapshotEditor(true); dismissDialog(); epoch++; loading = false;
        screen = history.isEmpty() ? Screen.CLOSED : history.pop(); message = ""; changed();
        if (screen == Screen.SUBMISSIONS) loadSubmissions();
    }
    public void onHostPaused() { hostResumed=false;main.removeCallbacks(unreadPoll);snapshotEditor(true); dismissDialog(); }
    public void onHostResumed() { hostResumed=true;onAccountChanged();refreshUnread();main.removeCallbacks(unreadPoll);main.postDelayed(unreadPoll,60000);if(screen==Screen.MESSAGES&&!loading)markDisplayedMessagesRead(messages); }
    private void syncUnreadOwner(){
        AccountRepository.Session current=accounts.cachedSession();String user=current==null||accounts.isLogoutPending()?"":current.user.id;
        if(user.equals(unreadOwnerId))return;
        unreadOwnerId=user;unreadRevision++;unread=0;pendingReads.clear();messages.clear();messageRows.clear();messageSummary=null;
        if(screen==Screen.MESSAGES){epoch++;loading=false;root=null;}badgeChanged();
    }
    public void refreshUnread() {
        if (destroyed) return;syncUnreadOwner();AccountRepository.Session before = accounts.cachedSession();
        if(before==null||accounts.isLogoutPending()||unreadPending)return;
        final String user = before.user.id;final long revision=unreadRevision;unreadPending=true;
        network.execute(() -> {
            int found=-1;
            try { ensureInstallation(); Map<String,Object> result = api.getForOwner("/community/messages/unread", user); int count = (int)Math.max(0,Math.min(9999, number(result,"unread",number(result,"count",0))));
                found=count;
            } catch (Exception ignored) { /* Inbox failure does not interrupt a camera or clear known unread messages. */ }
            final int count=found;onUi(()->{unreadPending=false;AccountRepository.Session current=accounts.cachedSession();
                if(current!=null&&!accounts.isLogoutPending()&&current.user.id.equals(user)&&revision==unreadRevision&&count>=0){unread=count;badgeChanged();}
                else if(revision!=unreadRevision&&hostResumed)refreshUnread();});
        });
    }
    private void badgeChanged() { if(!destroyed)host.onUnreadChanged(unreadCount()); }
    private boolean requireLogin() {
        AccountRepository.Session current = accounts.cachedSession();
        if (current != null && !accounts.isLogoutPending()) return true;
        host.onFeedback("登录后继续", "发布预设、发表评论和查看消息需要登录，浏览预设仍可使用游客模式。"); host.onLoginRequired(); return false;
    }
    public View createView(UiPalette palette) {
        String configuration = palette.dark + ":" + activity.getResources().getConfiguration().fontScale + ":" + activity.getResources().getConfiguration().screenWidthDp;
        if (root != null && !configuration.equals(viewConfiguration)) { snapshotEditor(true); root = null; }
        viewConfiguration = configuration;
        colors = palette;
        if (root != null) { if (root.getParent() instanceof ViewGroup) ((ViewGroup)root.getParent()).removeView(root); return root; }
        root = new ScrollView(activity); root.setFillViewport(true); root.setClipToPadding(false);
        LinearLayout content = vertical(); content.setPadding(dp(20), dp(8), dp(20), dp(32));
        root.addView(content, new ScrollView.LayoutParams(-1,-2));
        if (!message.isEmpty()) { TextView note = label(message, 14, false); note.setTextColor(colors.error); note.setPadding(0,0,0,dp(16)); note.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); content.addView(note); }
        switch (screen) {
            case FEED:buildFeed(content);break; case DETAIL:buildDetail(content);break; case SUBMISSIONS:buildSubmissions(content);break;
            case EDITOR:buildEditor(content);break; case MESSAGES:buildMessages(content);break; default:break;
        }
        if (loading || writing) { ProgressBar progress = new ProgressBar(activity); progress.setContentDescription(writing?"正在提交，请稍候":"正在加载"); progress.setIndeterminateTintList(ColorStateList.valueOf(colors.primary));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(28),dp(28)); p.gravity=Gravity.CENTER_HORIZONTAL;p.topMargin=dp(20);content.addView(progress,p); }
        if (!message.isEmpty() && !loading && screen != Screen.EDITOR) addAction(content,"重新加载",false,this::reload);
        if (screen == Screen.FEED || screen == Screen.MESSAGES) root.setOnScrollChangeListener((v,x,y,oldx,oldy)->{
            if (!loading && hasMore && root != null && root.getChildAt(0).getBottom() - (root.getHeight()+y) < dp(240)) {
                if (screen == Screen.FEED) loadFeed(true); else if (screen == Screen.MESSAGES) loadMessages(true);
            }
        });
        return root;
    }
    private void reload() {
        switch(screen) { case FEED:page=1;feed.clear();loadFeed(false);break;case DETAIL:loadDetail();break;
            case SUBMISSIONS:loadSubmissions();break;case MESSAGES:page=1;messages.clear();loadMessages(false);break;default:feedback("草稿已保留，可重新选择文件或再次保存。"); }
    }
    private void loadFeed(boolean append) {
        if (loading) return; int next = append ? page+1 : 1;
        load(() -> api.get("/community/feed?page="+next+"&pageSize=20&category="+encode(category),false), result->{
            try { if (!append)feed.clear();feed.addAll(items(result));page=next;hasMore=Boolean.TRUE.equals(result.get("hasMore")); }
            catch(IOException problem){message=problem.getMessage();} changed();
        });
    }
    private void buildFeed(LinearLayout content) {
        LinearLayout actions = horizontal();
        Button publish = action("发布预设",true,()->openEditor(null)); actions.addView(publish,new LinearLayout.LayoutParams(0,-2,1));
        actions.addView(action("我的投稿",false,this::openSubmissions),new LinearLayout.LayoutParams(0,-2,1)); content.addView(actions);
        Button filter = action(category.isEmpty()?"全部分类":"分类 · "+category,false,()->{
            String[] choices={"全部","人像","风景","胶片","黑白","日常","夜景"};
            dialog=new AlertDialog.Builder(activity).setTitle("选择分类").setItems(choices,(d,which)->{
                category=which==0?"":choices[which];feed.clear();page=1;loadFeed(false);
            }).create();dialog.show();
        });content.addView(filter);
        if (feed.isEmpty()&&!loading) empty(content,"还没有已发布的预设","你可以分享自己的色彩，审核通过后会出现在这里。");
        for(int i=0;i<feed.size();i+=2){ LinearLayout row=horizontal();row.setGravity(Gravity.TOP);
            for(int j=i;j<Math.min(i+2,feed.size());j++) {
                Map<String,Object> value=feed.get(j); LinearLayout card=vertical();card.setPadding(0,0,0,dp(12));card.setBackground(shape(colors.card,16));card.setClipToOutline(true);
                View cover=cover(text(value,"previewUrl"),dp(190));card.addView(cover,new LinearLayout.LayoutParams(-1,dp(190)));
                TextView name=label(text(value,"name"),16,true);name.setPadding(dp(12),dp(12),dp(12),dp(4));card.addView(name);
                TextView author=label(author(value)+" · "+("community".equals(text(value,"source"))?"用户分享":"精选预设"),12,false);
                author.setTextColor(colors.secondary);author.setPadding(dp(12),0,dp(12),0);card.addView(author);
                card.setContentDescription(text(value,"name")+"，"+author(value));card.setFocusable(true);card.setOnClickListener(v->{try{openPreset(preset(value));}catch(IOException e){feedback(e.getMessage());}});
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(j>i)p.setMarginStart(dp(12));row.addView(card,p);
            }
            if(i+1>=feed.size())row.addView(new View(activity),new LinearLayout.LayoutParams(0,1,1));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);content.addView(row,p);
        }
    }
    private void loadDetail() {
        final String selected=presetId;
        load(()->api.get("/community/presets/"+id(selected),false),result->{
            try {selectedPreset=preset(object(result.get("preset")));message="";}catch(IOException e){message=e.getMessage();} changed();loadComments(false);
        });
    }
    private void loadComments(boolean append) {
        if (loading) return;
        int next=append?page+1:1;
        final String selected = presetId;
        final String target = !append ? focusCommentId : "";
        load(()->api.get("/community/presets/"+id(selected)+"/comments?page="+next+"&pageSize=20"+(target.isEmpty()?"":"&focusCommentId="+id(target)),false),result->{
            try{if(!append){comments.clear();replyPages.clear();showingFocusedThread=!target.isEmpty()&&!Boolean.FALSE.equals(result.get("focusAvailable"));}comments.addAll(items(result));page=next;hasMore=Boolean.TRUE.equals(result.get("hasMore")) || comments.size()<number(result,"total",comments.size());if(Boolean.FALSE.equals(result.get("focusAvailable"))){message="这条评论已删除或隐藏，可以重新加载查看其他交流。";focusCommentId="";}}catch(IOException e){message=e.getMessage();}changed();
        });
    }
    private void buildDetail(LinearLayout content) {
        if(selectedPreset!=null&&selectedPreset.id.equals(presetId)){
            content.addView(cover(selectedPreset.previewUrl,dp(240)),new LinearLayout.LayoutParams(-1,dp(240)));
            title(content,selectedPreset.name);TextView attribution=label((selectedPreset.authorName.isEmpty()?"Huevia":selectedPreset.authorName)+" · "+("community".equals(selectedPreset.source)?"用户分享":"精选预设"),13,false);attribution.setTextColor(colors.secondary);content.addView(attribution);
            if(!selectedPreset.description.isEmpty()){TextView description=label(selectedPreset.description,15,false);description.setPadding(0,dp(16),0,dp(12));content.addView(description);}
            if(!selectedPreset.category.isEmpty())detail(content,"分类",selectedPreset.category);
            addAction(content,"下载预设",true,()->host.onDownloadPreset(selectedPreset));
        }
        gap(content,24); buildComments(content);
    }
    private void buildComments(LinearLayout content) {
        LinearLayout heading=horizontal();TextView section=label("评论",20,true);if(android.os.Build.VERSION.SDK_INT>=28)section.setAccessibilityHeading(true);heading.addView(section,new LinearLayout.LayoutParams(0,-2,1));heading.addView(action("发表评论",false,()->composeComment("")));content.addView(heading);
        if(comments.isEmpty()&&!loading && message.isEmpty())empty(content,"还没有评论","说说你的使用感受，交流拍摄场景、相机适配和预设效果。");
        for(Map<String,Object> comment:comments) {
            content.addView(commentRow(comment,null));Object replies=comment.get("replies");
            if(replies instanceof List)for(Object reply:(List<?>)replies)try{content.addView(commentRow(object(reply),comment));}catch(IOException ignored){}
            int loaded = replies instanceof List ? ((List<?>)replies).size() : 0;
            if(number(comment,"replyCount",loaded)>loaded)content.addView(action("查看更多回复",false,()->loadReplies(comment)));
        }
        if(hasMore&&!loading)addAction(content,"更多评论",false,()->loadComments(true));
        if(showingFocusedThread&&!loading)addAction(content,"查看全部评论",false,()->{focusCommentId="";loadComments(false);});
    }
    private void loadReplies(Map<String,Object> rootComment) {
        String rootId=text(rootComment,"id");int next=replyPages.nextPage(rootId);
        load(()->api.get("/community/comments/"+id(text(rootComment,"id"))+"/replies?page="+next+"&pageSize=20",false),result->{
            try{List<Map<String,Object>> previous=new ArrayList<>();if(rootComment.get("replies") instanceof List)for(Object row:(List<?>)rootComment.get("replies"))previous.add(object(row));long total=number(result,"total",-1);List<Map<String,Object>> merged=replyPages.accept(rootId,next,number(result,"page",-1),total,previous,items(result));rootComment.put("replies",merged);rootComment.put("replyCount",total);}catch(IOException error){message=error.getMessage();}changed();
        });
    }
    private View commentRow(Map<String,Object> comment,Map<String,Object> rootComment) {
        boolean reply=rootComment!=null;
        LinearLayout row=vertical();row.setPadding(dp(reply?20:0),dp(16),0,dp(12));
        LinearLayout identity=horizontal();UserAvatarView avatar=new UserAvatarView(activity);
        Object authorValue=comment.get("author");Map<String,Object> person=authorValue instanceof Map?(Map<String,Object>)authorValue:body();
        String displayName=author(comment);avatar.setAvatar(text(person,"id"),text(person,"avatarUrl"));avatar.setContentDescription(displayName+"的头像");avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams avatarLayout=new LinearLayout.LayoutParams(dp(reply?28:36),dp(reply?28:36));avatarLayout.setMarginEnd(dp(10));identity.addView(avatar,avatarLayout);
        LinearLayout authorMeta=vertical();TextView author=label(displayName,14,true);
        if(reply){
            String recipient=CommentReplyTarget.recipientName(comment,rootComment), relation=" 回复 ";
            android.text.SpannableString identityText=new android.text.SpannableString(displayName+relation+recipient);
            identityText.setSpan(new android.text.style.ForegroundColorSpan(colors.secondary),displayName.length(),displayName.length()+relation.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            identityText.setSpan(new android.text.style.StyleSpan(Typeface.NORMAL),displayName.length(),displayName.length()+relation.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            author.setText(identityText);
        }
        authorMeta.addView(author);
        TextView date=label(date(text(comment,"createdAt")),12,false);date.setTextColor(colors.secondary);authorMeta.addView(date);identity.addView(authorMeta,new LinearLayout.LayoutParams(0,-2,1));row.addView(identity);
        TextView body=label("deleted".equals(text(comment,"status"))?"评论已删除":text(comment,"text"),15,false);body.setPadding(0,dp(10),0,dp(4));body.setTextIsSelectable(true);row.addView(body);
        String commentId=text(comment,"id");if(commentId.equals(focusCommentId)){row.setBackground(shape(colors.primaryContainer,12));focusCommentId="";row.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){@Override public void onLayoutChange(View target,int left,int top,int right,int bottom,int oldLeft,int oldTop,int oldRight,int oldBottom){if(bottom<=top)return;target.removeOnLayoutChangeListener(this);target.post(()->target.requestRectangleOnScreen(new android.graphics.Rect(0,0,target.getWidth(),target.getHeight()),true));}});}
        if(!"deleted".equals(text(comment,"status"))){LinearLayout actions=horizontal();
            String parent=reply?text(rootComment,"id"):commentId;
            Button replyAction=action("回复",false,()->composeComment(parent,commentId,displayName));
            replyAction.setContentDescription("回复"+displayName);actions.addView(replyAction);
            if(isOwn(comment))actions.addView(action("删除",false,()->confirmDeleteComment(commentId)));
            else actions.addView(action("举报",false,()->reportComment(commentId)));
            row.addView(actions);
        }
        View divider=new View(activity);divider.setBackgroundColor(colors.outline);row.addView(divider,new LinearLayout.LayoutParams(-1,dp(.5f)));return row;
    }
    private void composeComment(String parent) {
        composeComment(parent,parent,"");
    }
    private void composeComment(String parent,String targetId,String targetName) {
        if(writing)return;
        AccountRepository.Session current=accounts.cachedSession();
        if(current!=null&&!accounts.isLogoutPending()){
            if(!commentOwnerId.isEmpty()&&!commentOwnerId.equals(current.user.id)){dismissDialog();clearCommentDraft();}
            commentOwnerId=current.user.id;
        }
        if(!parent.equals(replyParent)||!targetId.equals(replyTargetId)){commentText="";commentKey=UUID.randomUUID().toString();}
        replyParent=parent;replyTargetId=targetId;replyTargetName=targetName;
        if(!requireLogin()){pendingCommentResume=true;return;}
        final String composingOwner=commentOwnerId;
        String recipient=targetName.isEmpty()?"这条评论":targetName;
        LinearLayout form=vertical();form.setPadding(dp(24),dp(8),dp(24),0);EditText input=field(form,"内容",parent.isEmpty()?"分享使用感受":"回复 "+recipient,commentText,500,true);
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){String value=s.toString();if(!value.equals(commentText)){commentText=value;commentKey=UUID.randomUUID().toString();}}public void afterTextChanged(Editable text){}});
        dialog=new AlertDialog.Builder(activity).setTitle(parent.isEmpty()?"发表评论":"回复 "+recipient).setView(form).setNegativeButton("取消",null).setPositiveButton("发送",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            AccountRepository.Session sending=accounts.cachedSession();
            if(sending==null||accounts.isLogoutPending()){pendingCommentResume=true;dismissDialog();requireLogin();return;}
            if(!composingOwner.equals(sending.user.id)){onAccountChanged();return;}
            String value=input.getText().toString().trim();if(value.isEmpty()||value.codePointCount(0,value.length())>500){input.setError("请输入 1–500 字的内容。");return;}
            if(!value.equals(commentText)){commentText=value;commentKey=UUID.randomUUID().toString();}
            Map<String,Object> body=body("text",value,"idempotencyKey",commentKey);if(!parent.isEmpty()){body.put("parentId",parent);body.put("replyToId",targetId);}
            final String selected=presetId; pendingCommentResume=true;
            dismissDialog();write(()->api.writeForOwner("POST","/community/presets/"+id(selected)+"/comments",body,composingOwner),result->{pendingCommentResume=false;commentText="";commentKey=UUID.randomUUID().toString();Map<String,Object> posted;try{posted=object(result.get("comment"));}catch(IOException ignored){posted=body();}feedback("pending".equals(text(posted,"status"))?"评论已提交，审核后展示":"评论已发送");page=1;loadComments(false);});
        }));dialog.show();
    }
    private void confirmDeleteComment(String id) {
        if(writing)return;dialog=new AlertDialog.Builder(activity).setTitle("删除这条评论？").setMessage("删除后其他用户将无法查看这条内容。").setNegativeButton("取消",null)
                .setPositiveButton("删除",(d,w)->write(()->api.write("DELETE","/community/comments/"+id(id),body()),r->loadComments(false))).create();dialog.show();
    }
    private void reportComment(String id) {
        if(!requireLogin()||writing)return;String[] reasons={"广告或垃圾信息","辱骂或骚扰","涉嫌侵权","其他不当内容"};
        dialog=new AlertDialog.Builder(activity).setTitle("举报原因").setItems(reasons,(d,w)->write(()->api.write("POST","/community/comments/"+id(id)+"/report",body("reason",reasons[w],"idempotencyKey",UUID.randomUUID().toString())),r->feedback("举报已收到，管理员会进行核查。"))).setNegativeButton("取消",null).create();dialog.show();
    }
    private void loadSubmissions() {
        load(()->api.get("/community/submissions?page="+page+"&pageSize=20",true),result->{try{submissions.clear();submissions.addAll(items(result));hasMore=submissions.size()<number(result,"total",submissions.size());}catch(IOException e){message=e.getMessage();}changed();});
    }
    private void buildSubmissions(LinearLayout content) {
        addAction(content,"发布新预设",true,()->openEditor(null));
        if(submissions.isEmpty()&&!loading)empty(content,"把喜欢的色彩分享出来","投稿会先交给管理员审核，审核结果会发送到消息列表。");
        for(Map<String,Object> value:submissions){LinearLayout row=vertical();row.setPadding(0,dp(20),0,dp(20));
            TextView name=label(text(value,"name"),18,true);row.addView(name);TextView state=label(statusName(text(value,"status"))+" · "+date(text(value,"updatedAt")),13,false);state.setTextColor(colors.secondary);state.setPadding(0,dp(8),0,0);row.addView(state);
            if(!text(value,"reason").isEmpty()){TextView reason=label("审核说明："+text(value,"reason"),14,false);reason.setTextColor(colors.error);reason.setPadding(0,dp(8),0,0);row.addView(reason);}
            row.addView(action("查看与编辑",false,()->openEditor(value)));content.addView(row);divider(content);
        }
        if(hasMore&&!loading)addAction(content,"下一页",false,()->{page++;loadSubmissions();});
        if(page>1)addAction(content,"上一页",false,()->{page--;loadSubmissions();});
    }
    private void openEditor(Map<String,Object> server) {
        if(!requireLogin())return;AccountRepository.Session current=accounts.cachedSession();if(current==null||accounts.isLogoutPending()){requireLogin();return;}ownerId=current.user.id;
        enter(Screen.EDITOR);restoringDraft=true;final String owner=ownerId;
        load(()->{if(draftStore==null)draftStore=new SubmissionDraftStore(activity.getFilesDir());return server==null?draftStore.load(owner):new LinkedHashMap<>(server);},result->{
            draft=new LinkedHashMap<>(result);if(server==null&&Arrays.asList("published","approved","pending","pending_review").contains(text(draft,"status")))draft=SubmissionDraftStore.fresh();
            if(server!=null){draft.put("saveKey",UUID.randomUUID().toString());draft.put("submitKey",UUID.randomUUID().toString());}
            restoringDraft=false;changed();
        });
    }
    private void buildEditor(LinearLayout content) {
        if(restoringDraft){empty(content,"正在读取草稿","填写的内容和已选文件会保留到下次继续。");return;}
        String state=text(draft,"status");boolean locked="pending".equals(state)||"pending_review".equals(state);
        if(!state.isEmpty())detail(content,"状态",statusName(state));
        if(!text(draft,"reason").isEmpty()){TextView reason=label("审核说明："+text(draft,"reason"),14,false);reason.setTextColor(colors.error);content.addView(reason);}
        nameInput=field(content,"预设名称","让大家认识这份色彩",text(draft,"name"),160,false);
        descriptionInput=field(content,"简介","适合什么场景、有什么特点",text(draft,"description"),2000,true);
        categoryInput=field(content,"分类","如人像、风景、胶片",text(draft,"category"),24,false);
        modelsInput=field(content,"适用相机（选填）","多个机型用逗号分隔",models(draft),256,false);
        gap(content,16);addAction(content,text(draft,"fileAssetId").isEmpty()&&text(draft,"filePath").isEmpty()?"选择 NCP 文件":"重新选择 NCP 文件",false,()->pick(PICK_NCP));
        TextView filename=label(fileLabel(draft,"file"),13,false);filename.setTextColor(colors.secondary);content.addView(filename);
        addAction(content,text(draft,"previewAssetId").isEmpty()&&text(draft,"previewPath").isEmpty()?"添加效果图（可选）":"更换效果图",false,()->pick(PICK_PREVIEW));
        TextView preview=label(fileLabel(draft,"preview"),13,false);preview.setTextColor(colors.secondary);content.addView(preview);
        if(!text(draft,"previewAssetId").isEmpty()||!text(draft,"previewPath").isEmpty())addAction(content,"移除效果图",false,()->{snapshotEditor(false);draft.remove("previewAssetId");draft.remove("previewPath");draft.remove("preview");draft.remove("previewName");persistDraft();changed();});
        rightsInput=new CheckBox(activity);rightsInput.setText("我拥有预设及效果图的公开分享权利");rightsInput.setTextColor(colors.text);rightsInput.setTextSize(14);rightsInput.setMinHeight(dp(48));rightsInput.setButtonTintList(ColorStateList.valueOf(colors.primary));rightsInput.setChecked(Boolean.TRUE.equals(draft.get("rightsConfirmed")));gap(content,16);content.addView(rightsInput);
        editorNote=label("仅支持 NCP 0100（不超过 1 MB）。效果图不超过 4 MB；没有效果图时展示模板示意。",12,false);editorNote.setTextColor(colors.secondary);editorNote.setPadding(0,dp(10),0,dp(8));content.addView(editorNote);
        for(EditText edit:new EditText[]{nameInput,descriptionInput,categoryInput,modelsInput}){edit.setEnabled(!locked&&!writing);edit.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){scheduleDraft();}public void afterTextChanged(Editable e){}});}
        rightsInput.setEnabled(!locked&&!writing);rightsInput.setOnCheckedChangeListener((b,c)->scheduleDraft());
        if(locked)detail(content,"审核中","审核完成前暂不能修改。结果会保存在消息列表中。");
        else{addAction(content,writing?"正在保存…":"保存草稿",false,()->saveSubmission(false));addAction(content,writing?"正在提交…":"提交审核",true,()->saveSubmission(true));}
    }
    private void scheduleDraft(){if(persistEditor!=null)main.removeCallbacks(persistEditor);persistEditor=()->snapshotEditor(true);main.postDelayed(persistEditor,350);}
    private void snapshotEditor(boolean save) {
        if(destroyed||screen!=Screen.EDITOR||restoringDraft||nameInput==null||descriptionInput==null||categoryInput==null||modelsInput==null)return;
        Map<String,Object> next=new LinkedHashMap<>(draft);next.put("name",nameInput.getText().toString());next.put("description",descriptionInput.getText().toString());next.put("category",categoryInput.getText().toString());
        List<String> models=new ArrayList<>();for(String model:modelsInput.getText().toString().split("[,，]"))if(!model.trim().isEmpty())models.add(model.trim());next.put("compatibleModels",models);next.put("rightsConfirmed",rightsInput!=null&&rightsInput.isChecked());
        if(!next.equals(draft)){next.put("saveKey",UUID.randomUUID().toString());next.put("submitKey",UUID.randomUUID().toString());next.put("submitPending",false);}draft=next;if(save)persistDraft();
    }
    private void persistDraft() {
        if(ownerId.isEmpty()||draftStore==null)return;final String owner=ownerId;final Map<String,Object> snapshot=new LinkedHashMap<>(draft);
        network.execute(()->{try{draftStore.save(owner,snapshot);}catch(IOException error){onUi(()->{if(screen==Screen.EDITOR) {message="草稿暂未保存，请保留当前页面后重试。";if(editorNote!=null)editorNote.setText(message);}});}});
    }
    private void pick(int code) {
        if(writing)return;snapshotEditor(true);pendingPickerOwner=ownerId;pendingPickerCode=code;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(code==PICK_NCP?"*/*":"image/*");
        try{activity.startActivityForResult(intent,code);}catch(RuntimeException problem){feedback("无法打开文件选择器，请检查手机是否有可用的文件应用。");}
    }
    public boolean onActivityResult(int request,int result,Intent data) {
        if(request!=PICK_NCP&&request!=PICK_PREVIEW)return false;
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null)return true;
        AccountRepository.Session current=accounts.cachedSession();if(screen!=Screen.EDITOR||current==null||!current.user.id.equals(pendingPickerOwner)||request!=pendingPickerCode){feedback("账号或投稿已变化，请重新选择文件。");return true;}
        final Uri uri=data.getData();final String kind=request==PICK_NCP?"ncp":"preview",owner=ownerId;
        load(()->{
            byte[] bytes;try(InputStream input=activity.getContentResolver().openInputStream(uri)){if(input==null)throw new IOException("文件无法读取。");bytes=bounded(input,"ncp".equals(kind)?1024*1024:4*1024*1024);}
            if("ncp".equals(kind))NcpFile.validate(bytes);else validateImage(bytes);
            String filename="ncp".equals(kind)?"preset.NCP":"preview";
            try(android.database.Cursor cursor=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst()){String found=cursor.getString(0);if(found!=null)filename=found.replaceAll("[\\\\/\\x00-\\x1f]","_");}}
            return body("path",draftStore.storeFile(owner,kind,bytes).getAbsolutePath(),"name",filename);
        },value->{snapshotEditor(false);draft.put("ncp".equals(kind)?"filePath":"previewPath",text(value,"path"));draft.put("ncp".equals(kind)?"fileName":"previewName",text(value,"name"));draft.remove("ncp".equals(kind)?"fileAssetId":"previewAssetId");draft.put("saveKey",UUID.randomUUID().toString());persistDraft();changed();});return true;
    }
    private void saveSubmission(boolean submit) {
        if(writing||!requireLogin())return;snapshotEditor(true);
        AccountRepository.Session current=accounts.cachedSession();if(!current.user.id.equals(ownerId)){feedback("账号已切换，请重新打开投稿页面。");return;}
        if(text(draft,"name").trim().isEmpty()){nameInput.setError("请填写预设名称。");return;}
        if(!Boolean.TRUE.equals(draft.get("rightsConfirmed"))){feedback("请先确认拥有预设和效果图的公开分享权利。");return;}
        if(text(draft,"filePath").isEmpty()&&text(draft,"fileAssetId").isEmpty()){feedback("请先选择有效的 NCP 文件。");return;}
        final Map<String,Object> snapshot=new LinkedHashMap<>(draft);final String owner=ownerId;
        write(()->SubmissionFlow.save(api,draftStore,owner,snapshot,submit),saved->{draft=new LinkedHashMap<>(saved);message="";feedback(submit?"投稿已提交，审核结果会发送到消息列表。":"草稿已保存，可稍后继续编辑。");changed();});
    }
    private void loadMessages(boolean append) {
        AccountRepository.Session current=accounts.cachedSession();if(current==null||accounts.isLogoutPending())return;final String user=current.user.id;
        int next=append?page+1:1;load(()->api.getForOwner("/community/messages?page="+next+"&pageSize=20",user),result->{
            AccountRepository.Session active=accounts.cachedSession();if(active==null||!active.user.id.equals(user))return;
            List<Map<String,Object>> incoming=List.of();
            try{incoming=items(result);if(!append)messages.clear();messages.addAll(incoming);page=next;unreadRevision++;unread=(int)Math.max(0,Math.min(9999,number(result,"unread",unread)));hasMore=messages.size()<number(result,"total",messages.size());badgeChanged();}catch(IOException e){message=e.getMessage();}changed();markDisplayedMessagesRead(incoming);
        });
    }
    private void markDisplayedMessagesRead(List<Map<String,Object>> displayed){
        if(destroyed||!hostResumed||screen!=Screen.MESSAGES||!host.isVisible())return;
        AccountRepository.Session current=accounts.cachedSession();if(current==null||accounts.isLogoutPending())return;final String user=current.user.id;
        List<String> ids=new ArrayList<>();for(Map<String,Object> item:displayed){String key=text(item,"id");if(!Boolean.TRUE.equals(item.get("read"))&&!pendingReads.contains(key)&&ids.size()<50)ids.add(key);}
        if(ids.isEmpty())return;pendingReads.addAll(ids);unreadRevision++;
        network.execute(()->{Map<String,Object> answer=null;Exception failure=null;
            try{ensureInstallation();answer=api.writeForOwner("POST","/community/messages/read-batch",body("ids",ids),user);}catch(Exception e){failure=e;}
            final Map<String,Object> result=answer;final Exception problem=failure;
            onUi(()->{AccountRepository.Session active=accounts.cachedSession();if(active==null||accounts.isLogoutPending()||!active.user.id.equals(user))return;pendingReads.removeAll(ids);
                if(problem!=null){if(screen==Screen.MESSAGES){message="已读状态暂未同步，请稍后重新打开消息或点击全部已读。";changed();}return;}
                try{Object values=result.get("readIds");if(!(values instanceof List)||((List<?>)values).size()>50||number(result,"unread",-1)<0)throw new IOException();
                    Set<String> accepted=new HashSet<>();for(Object value:(List<?>)values){if(!(value instanceof String)||!ids.contains(value))throw new IOException();accepted.add((String)value);}
                    for(Map<String,Object> item:messages)if(accepted.contains(text(item,"id")))item.put("read",Boolean.TRUE);
                    for(String key:accepted){View row=messageRows.get(key);if(row!=null)row.setBackgroundColor(android.graphics.Color.TRANSPARENT);}
                    unreadRevision++;unread=(int)Math.min(9999,number(result,"unread",unread));badgeChanged();if(messageSummary!=null)messageSummary.setText(unread>0?unread+" 条未读":"没有未读消息");
                }catch(IOException malformed){if(screen==Screen.MESSAGES){message="已读状态暂未同步，请重新打开消息后重试。";changed();}}
            });
        });
    }
    private void buildMessages(LinearLayout content) {
        messageRows.clear();LinearLayout header=horizontal();TextView state=label(unread>0?unread+" 条未读":"没有未读消息",14,false);messageSummary=state;state.setTextColor(colors.secondary);header.addView(state,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(action("全部已读",false,()->write(()->api.write("POST","/community/messages/read-all",body()),r->{unreadRevision++;unread=0;badgeChanged();loadMessages(false);})));content.addView(header);
        if(messages.isEmpty()&&!loading)empty(content,"消息会保存在这里","有人回复你的评论、或投稿审核完成时，你会在这里收到消息。");
        for(Map<String,Object> value:messages){LinearLayout row=vertical();row.setPadding(dp(14),dp(16),dp(14),dp(16));
            messageRows.put(text(value,"id"),row);
            if(!Boolean.TRUE.equals(value.get("read")))row.setBackground(shape(colors.primaryContainer,14));
            String type=text(value,"type");TextView title=label("reply".equals(type)?author(value)+" 回复了你":type.contains("reject")?"投稿审核未通过":"投稿审核通知",15,true);row.addView(title);
            TextView body=label(text(value,"text"),14,false);body.setPadding(0,dp(8),0,dp(6));row.addView(body);
            TextView date=label(date(text(value,"createdAt")),12,false);date.setTextColor(colors.secondary);row.addView(date);
            boolean available=!Boolean.FALSE.equals(value.get("available"));row.addView(action(available?"查看详情":"内容已不可用",false,()->openMessage(value)));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);content.addView(row,p);
        }
    }
    private void openMessage(Map<String,Object> item) {
        if(writing)return;String messageId=text(item,"id");
        write(()->api.write("POST","/community/messages/"+id(messageId)+"/read",body()),r->{
            if(!Boolean.TRUE.equals(item.get("read")))unread=Math.max(0,unread-1);item.put("read",Boolean.TRUE);unreadRevision++;badgeChanged();refreshUnread();
            if(Boolean.FALSE.equals(item.get("available"))){feedback("这条消息关联的内容已删除、隐藏或下架。");changed();return;}
            if(!"reply".equals(text(item,"type"))&&!text(item,"submissionId").isEmpty()){
                load(()->api.get("/community/submissions/"+id(text(item,"submissionId")),true),value->{try{openEditor(object(value.get("submission")));}catch(IOException e){feedback(e.getMessage());}});
            }else{
                final String selected=text(item,"presetId"),focus=text(item,"commentId");
                load(()->api.get("/community/presets/"+id(selected),false),value->{
                    try {
                        OnlinePreset preset=preset(object(value.get("preset")));
                        dismissDialog();screen=Screen.CLOSED;history.clear();epoch++;loading=false;message="";root=null;
                        host.onOpenPreset(preset,focus);
                    }catch(IOException error){message=error.getMessage();changed();}
                });
            }
        });
    }
    private <T> void load(Work<T> work,Result<T> result){ if(destroyed||loading)return;loading=true;message="";final long attempt=epoch;changed();
        network.execute(()->{T value=null;Exception failure=null;try{ensureInstallation();value=work.run();}catch(Exception problem){failure=problem;}final T output=value;final Exception error=failure;
            onUi(()->{if(attempt!=epoch)return;loading=false;if(error!=null){message=readable(error);if(restoringDraft)restoringDraft=false;changed();if(loginError(error))host.onLoginRequired();}else result.done(output);});});
    }
    private <T> void write(Work<T> work,Result<T> result){if(destroyed||writing||!requireLogin())return;writing=true;message="";final long attempt=epoch;final String owner=ownerId;final boolean editorWrite=screen==Screen.EDITOR;AccountRepository.Session current=accounts.cachedSession();final String writer=current==null?"":current.user.id;changed();
        network.execute(()->{T value=null;Exception failure=null;Map<String,Object> recovery=null;try{AccountRepository.Session active=accounts.cachedSession();if(active==null||accounts.isLogoutPending()||!writer.equals(active.user.id))throw new AccountException("SESSION_CHANGED",409,"账号已变化，请确认当前账号后重试。");ensureInstallation();value=work.run();}catch(Exception problem){failure=problem;if(editorWrite&&draftStore!=null)try{recovery=draftStore.load(owner);}catch(IOException ignored){}}final T output=value;final Exception error=failure;final Map<String,Object> recovered=recovery;
            onUi(()->{writing=false;if(attempt!=epoch){if(error==null)refreshUnread();changed();return;}if(error!=null){if(recovered!=null&&owner.equals(ownerId))draft=new LinkedHashMap<>(recovered);message=readable(error);changed();if(loginError(error))host.onLoginRequired();else pendingCommentResume=false;}else result.done(output);});});
    }
    private boolean isOwn(Map<String,Object> value){AccountRepository.Session current=accounts.cachedSession();Object author=value.get("author");return current!=null&&!accounts.isLogoutPending()&&author instanceof Map&&current.user.id.equals(text((Map<String,Object>)author,"id"));}
    private static String author(Map<String,Object> value){Object author=value.get("author");if(author instanceof Map){String name=text((Map<String,Object>)author,"nickname");if(!name.isEmpty())return name;}return "Huevia";}
    private static String models(Map<String,Object> value){Object models=value.get("compatibleModels");if(!(models instanceof List))return "";List<String> result=new ArrayList<>();for(Object model:(List<?>)models)if(model instanceof String)result.add((String)model);return String.join("，",result);}
    private static String key(Map<String,Object> value,String key){String result=text(value,key);if(result.isEmpty()){result=UUID.randomUUID().toString();value.put(key,result);}return result;}
    private static String fileLabel(Map<String,Object> value,String prefix){String name=text(value,prefix+"Name");if(!name.isEmpty())return name;if(value.get(prefix) instanceof Map){String original=text((Map<String,Object>)value.get(prefix),"originalName");if(!original.isEmpty())return original;}return "preview".equals(prefix)?"未添加时使用默认模板示意":"请选择 NCP 0100 文件";}
    private static String statusName(String state){switch(state){case"draft":return"草稿";case"pending":case"pending_review":return"待审核";case"approved":case"published":return"已发布";case"rejected":return"审核未通过";case"unpublished":case"withdrawn":return"已下架";default:return"草稿";}}
    private static boolean loginError(Exception error){return error instanceof AccountException&&((AccountException)error).status==401||error instanceof CommunityException&&((CommunityException)error).status==401;}
    private static String readable(Exception error){if(error instanceof AccountException||error instanceof CommunityException)return error.getMessage();return"暂时无法连接在线服务。请使用可上网的网络后手动重试；相机连接不会被切换，投稿草稿会保留。";}
    private View cover(String address,int height){FrameLayout frame=new FrameLayout(activity);frame.setBackground(shape(colors.container,16));frame.setClipToOutline(true);ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setImageResource(R.drawable.preset_template);image.setContentDescription("模板示意");frame.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView fallback=label("模板示意",11,false);fallback.setTextColor(Color.WHITE);fallback.setPadding(dp(8),dp(4),dp(8),dp(4));fallback.setBackground(shape(0xb020242b,10));FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);badge.setMargins(dp(8),dp(8),dp(8),dp(8));frame.addView(fallback,badge);if(address.isEmpty())return frame;
        Bitmap cached=covers.get(address);if(cached!=null){image.setImageBitmap(cached);image.setContentDescription("预设效果图");fallback.setVisibility(View.GONE);return frame;}
        images.execute(()->{Bitmap bitmap=null;try{java.net.URI uri=new java.net.URI(address);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null)throw new IOException();HttpsURLConnection connection=(HttpsURLConnection)new URL(address).openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(8000);connection.setReadTimeout(10000);try{if(connection.getResponseCode()!=200)throw new IOException();byte[] bytes;try(InputStream input=connection.getInputStream()){bytes=bounded(input,4*1024*1024);}validateImage(bytes);BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1024)options.inSampleSize*=2;options.inJustDecodeBounds=false;bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);}finally{connection.disconnect();}}catch(Exception ignored){}final Bitmap ready=bitmap;if(ready!=null){covers.put(address,ready);onUi(()->{image.setImageBitmap(ready);image.setContentDescription("预设效果图");fallback.setVisibility(View.GONE);});}});return frame;}
    private void ensureInstallation() throws IOException { api.setInstallationId(AccountInstallation.id(activity.getApplicationContext())); }
    private static byte[] bounded(InputStream input,int max)throws IOException{ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(count==0||output.size()+count>max)throw new IOException("文件过大。");output.write(buffer,0,count);}return output.toByteArray();}
    private static void validateImage(byte[] bytes)throws IOException{BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(options.outWidth<1||options.outHeight<1||options.outWidth>16384||options.outHeight>16384||(long)options.outWidth*options.outHeight>64000000)throw new IOException("请选择有效的 PNG、JPEG 或 WebP 图片。");}
    private static String mime(byte[] bytes){if(bytes.length>4&&bytes[0]==(byte)0x89&&bytes[1]=='P')return"image/png";if(bytes.length>3&&bytes[0]==(byte)0xff&&bytes[1]==(byte)0xd8)return"image/jpeg";if(bytes.length>12&&bytes[8]=='W'&&bytes[9]=='E')return"image/webp";return"application/octet-stream";}
    private void changed(){
        if(!inlinePresetId.isEmpty()&&screen==Screen.CLOSED){if(!destroyed)renderInlineComments();return;}
        root=null;nameInput=descriptionInput=categoryInput=modelsInput=null;rightsInput=null;editorNote=null;if(!destroyed&&host.isVisible())host.onChanged();
    }
    private void onUi(Runnable action){activity.runOnUiThread(()->{if(!destroyed&&!activity.isFinishing())action.run();});}
    private void feedback(String value){host.onFeedback("Huevia",value);}
    private void dismissDialog(){if(dialog!=null){dialog.dismiss();dialog=null;}}
    private static String date(String value){try{return DateTimeFormatter.ofPattern("M月d日 HH:mm",Locale.CHINA).withZone(ZoneId.systemDefault()).format(Instant.parse(value));}catch(RuntimeException e){return"";}}
    private LinearLayout vertical(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout horizontal(){LinearLayout view=new LinearLayout(activity);view.setOrientation(LinearLayout.HORIZONTAL);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private TextView label(String text,int size,boolean bold){TextView view=new TextView(activity);view.setText(text);view.setTextSize(size);view.setTextColor(colors.text);view.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));view.setLineSpacing(dp(3),1);return view;}
    private Button action(String text,boolean primary,Runnable callback){Button button=new Button(activity);button.setText(text);button.setAllCaps(false);button.setTextSize(14);button.setMinHeight(dp(46));button.setMinimumHeight(dp(46));button.setMinWidth(0);button.setMinimumWidth(0);button.setPadding(dp(14),dp(10),dp(14),dp(10));button.setTextColor(primary?colors.onPrimary:colors.primary);button.setStateListAnimator(null);button.setElevation(0);button.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(colors.primaryContainer),shape(primary?colors.primary:Color.TRANSPARENT,14),null));button.setOnClickListener(v->callback.run());button.setEnabled(!writing);return button;}
    private void addAction(LinearLayout parent,String text,boolean primary,Runnable callback){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(12);parent.addView(action(text,primary,callback),params);}
    private EditText field(LinearLayout parent,String caption,String hint,String text,int maximum,boolean multiline){TextView label=label(caption,13,true);label.setPadding(0,dp(18),0,dp(8));parent.addView(label);EditText input=new EditText(activity);input.setTextColor(colors.text);input.setHintTextColor(colors.secondary);input.setTextSize(16);input.setHint(hint);input.setText(text);input.setMinHeight(dp(52));input.setPadding(dp(14),dp(12),dp(14),dp(12));input.setBackground(shape(colors.container,14));input.setSingleLine(!multiline);input.setInputType(InputType.TYPE_CLASS_TEXT|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maximum)});input.setSaveEnabled(false);input.setId(View.generateViewId());label.setLabelFor(input.getId());parent.addView(input,new LinearLayout.LayoutParams(-1,-2));return input;}
    private void title(LinearLayout parent,String title){TextView label=label(title,24,true);label.setPadding(0,dp(20),0,dp(10));parent.addView(label);}
    private void detail(LinearLayout parent,String label,String value){TextView item=label(label+" · "+value,14,false);item.setTextColor(colors.secondary);item.setPadding(0,dp(10),0,dp(10));parent.addView(item);}
    private void empty(LinearLayout parent,String title,String message){TextView heading=label(title,18,true);heading.setPadding(0,dp(32),0,dp(10));parent.addView(heading);TextView copy=label(message,14,false);copy.setTextColor(colors.secondary);copy.setPadding(0,0,0,dp(24));parent.addView(copy);}
    private void divider(LinearLayout parent){View line=new View(activity);line.setBackgroundColor(colors.outline);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(.5f)));}
    private void gap(LinearLayout parent,int size){parent.addView(new View(activity),new LinearLayout.LayoutParams(1,dp(size)));}
    private GradientDrawable shape(int fill,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(fill);drawable.setCornerRadius(dp(radius));return drawable;}
    private int dp(float value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    @Override public void close(){if(destroyed)return;main.removeCallbacks(unreadPoll);snapshotEditor(true);destroyed=true;epoch++;if(persistEditor!=null)main.removeCallbacks(persistEditor);dismissDialog();network.shutdown();images.shutdownNow();covers.evictAll();}
}

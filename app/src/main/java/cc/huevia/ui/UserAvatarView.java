package cc.huevia.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.LruCache;
import android.view.View;

import cc.huevia.account.AccountRepository;
import cc.huevia.account.AvatarUrlPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** A shared circular avatar with a quiet native default, bounded public loading and identity-safe reuse. */
public final class UserAvatarView extends View {
    private static final int MAX_DOWNLOAD = 2 * 1024 * 1024;
    private static final ExecutorService LOADERS = Executors.newFixedThreadPool(3, task -> {
        Thread thread = new Thread(task, "public-avatar"); thread.setDaemon(true); return thread;
    });
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "avatar-deadline"); thread.setDaemon(true); return thread;
    });
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path circle = new Path();
    private final RectF target = new RectF();
    private final AppIcon defaultIcon;
    private final UiPalette colors;
    private Bitmap picture;
    private String binding = "";
    private long revision;

    public UserAvatarView(Context context) {
        super(context); colors = new UiPalette(context);
        defaultIcon = new AppIcon(AppIcon.Kind.PERSON, colors.secondary, getResources().getDisplayMetrics().density);
        setContentDescription("用户头像"); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setAvatar(AccountRepository.User user) { setAvatar(user == null ? "" : user.id, user == null ? "" : user.avatarUrl); }

    public void setAvatar(String identity, String url) {
        String value = AvatarUrlPolicy.allows(url) ? url : "";
        String key = (identity == null ? "" : identity) + "\n" + value;
        if (key.equals(binding)) return;
        binding = key; final long attempt = ++revision;
        picture = value.isEmpty() ? null : CACHE.get(key); invalidate();
        if (value.isEmpty() || picture != null) return;
        WeakReference<UserAvatarView> owner = new WeakReference<>(this);
        LOADERS.execute(() -> {
            Bitmap loaded = null;
            try { loaded = load(value); } catch (IOException | RuntimeException | OutOfMemoryError ignored) { }
            if (loaded == null) return;
            CACHE.put(key, loaded); final Bitmap ready = loaded;
            UserAvatarView view = owner.get();
            if (view != null) view.post(() -> {
                UserAvatarView current = owner.get();
                if (current != null && current.revision == attempt && key.equals(current.binding)) { current.picture = ready; current.invalidate(); }
            });
        });
    }

    /** Preview images stay transient and never enter the public URL cache. */
    public void showPreview(Bitmap bitmap) { revision++; binding = "preview:" + revision; picture = bitmap; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float diameter = Math.min(getWidth(), getHeight()), left = (getWidth() - diameter) / 2f, top = (getHeight() - diameter) / 2f;
        target.set(left, top, left + diameter, top + diameter);
        paint.setColor(colors.container); canvas.drawOval(target, paint);
        if (picture != null && !picture.isRecycled()) {
            circle.reset(); circle.addOval(target, Path.Direction.CW); int save = canvas.save(); canvas.clipPath(circle);
            float scale = Math.max(diameter / picture.getWidth(), diameter / picture.getHeight());
            float width = picture.getWidth() * scale, height = picture.getHeight() * scale;
            RectF image = new RectF(left + (diameter - width) / 2, top + (diameter - height) / 2,
                    left + (diameter + width) / 2, top + (diameter + height) / 2);
            canvas.drawBitmap(picture, null, image, paint); canvas.restoreToCount(save);
        } else {
            int inset = Math.round(diameter * .25f);
            defaultIcon.setBounds(Math.round(left) + inset, Math.round(top) + inset,
                    Math.round(left + diameter) - inset, Math.round(top + diameter) - inset); defaultIcon.draw(canvas);
        }
    }

    private static Bitmap load(String url) throws IOException {
        if (!AvatarUrlPolicy.allows(url)) throw new IOException("Invalid avatar address");
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(6000); connection.setReadTimeout(8000);
        connection.setUseCaches(false); connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Cookie", ""); connection.setRequestProperty("Cookie2", "");
        ScheduledFuture<?> deadline = DEADLINES.schedule(connection::disconnect, 18, TimeUnit.SECONDS);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Avatar unavailable");
            String type = connection.getContentType(), encoding = connection.getContentEncoding();
            if (type == null || !type.toLowerCase(Locale.ROOT).matches("image/(?:jpeg|png|webp)(?:\\s*;.*)?")
                    || (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding))
                    || connection.getContentLengthLong() > MAX_DOWNLOAD) throw new IOException("Invalid avatar image");
            byte[] bytes;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0 || count > MAX_DOWNLOAD - output.size() || Thread.currentThread().isInterrupted()) throw new IOException("Avatar too large");
                    output.write(buffer, 0, count);
                }
                bytes = output.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth < 1 || bounds.outHeight < 1 || bounds.outWidth > 8192 || bounds.outHeight > 8192
                    || (long) bounds.outWidth * bounds.outHeight > 32_000_000) throw new IOException("Invalid avatar dimensions");
            BitmapFactory.Options decode = new BitmapFactory.Options(); decode.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / decode.inSampleSize > 512) decode.inSampleSize *= 2;
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, decode);
            if (bitmap == null) throw new IOException("Cannot decode avatar");
            return bitmap;
        } finally { deadline.cancel(false); connection.disconnect(); }
    }
}

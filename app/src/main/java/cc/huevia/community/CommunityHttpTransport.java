package cc.huevia.community;

import cc.huevia.account.AccountHttpTransport;
import cc.huevia.account.AccountRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Product-owned HTTPS only. No redirects, cookies, camera network rebinding or automatic retries. */
public final class CommunityHttpTransport implements AccountRepository.Transport {
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "community-deadline"); thread.setDaemon(true); return thread;
    });
    private final String contentType;
    private final boolean personal;
    private volatile String installationId;
    public CommunityHttpTransport() { this("application/json; charset=utf-8"); }
    public CommunityHttpTransport(String contentType) { this(contentType,false); }
    public CommunityHttpTransport(String contentType,boolean personal) { this.contentType = contentType;this.personal=personal; }
    public void setInstallationId(String value) { installationId = value; }
    @Override public AccountRepository.Response request(String method, String path, String token, byte[] body) throws IOException {
        if (path == null || !path.matches((personal?"/personal/":"/community/")+"[A-Za-z0-9_/?=&%.+\\-]*") || path.contains("..")
                || !java.util.Arrays.asList("GET", "POST", "PUT", "DELETE").contains(method)
                || (token != null && !token.matches("[A-Za-z0-9_-]{43}")) || (body != null && body.length > 4 * 1024 * 1024))
            throw new IOException("请求内容无效。");
        HttpsURLConnection connection = (HttpsURLConnection) new URL(AccountHttpTransport.BASE_URL + path).openConnection();
        connection.setRequestMethod(method); connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(8000);
        connection.setReadTimeout(10000); connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json"); connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("Cookie", ""); connection.setRequestProperty("Cookie2", "");
        if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (installationId != null) connection.setRequestProperty("X-Installation-Id", installationId);
        ScheduledFuture<?> deadline = DEADLINES.schedule(connection::disconnect, 35, TimeUnit.SECONDS);
        try {
            if (body != null) {
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length); connection.setRequestProperty("Content-Type", contentType);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new IOException("服务地址异常，请稍后重试。");
            String encoding = connection.getContentEncoding();
            if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding)) throw new IOException("服务响应无效。");
            int maximum = personal?2*1024*1024:512 * 1024; long length = connection.getContentLengthLong();
            if (length > maximum) throw new IOException("服务响应过大。");
            String type = connection.getContentType();
            if (status != 204 && (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json"))) throw new IOException("服务响应无效。");
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (stream != null) try (InputStream input = stream) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("操作已取消。");
                    if (count == 0 || output.size() + count > maximum) throw new IOException("服务响应过大。");
                    output.write(buffer, 0, count);
                }
            }
            if (length >= 0 && length != output.size()) throw new IOException("服务响应不完整。");
            return new AccountRepository.Response(status, output.toByteArray());
        } finally { deadline.cancel(false); connection.disconnect(); }
    }
}

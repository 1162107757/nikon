package com.nikonpreset.usbprobe.library;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

/** No credentials, cookies, permissive TLS, HTTP downgrade or automatic redirect handling. */
final class SecureHttpClient {
    interface Connections { HttpsURLConnection open(URL url) throws IOException; }
    private final Connections connections;
    SecureHttpClient() { this(url -> (HttpsURLConnection) url.openConnection()); }
    SecureHttpClient(Connections connections) { this.connections = connections; }

    static URI httpsUri(String text) throws IOException {
        try {
            if (text == null || text.length() > 4096) throw new URISyntaxException("", "length");
            URI uri = new URI(text);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535)
                throw new URISyntaxException("", "HTTPS URL required");
            return uri;
        } catch (URISyntaxException e) { throw new IOException("请输入不含账号或片段的完整 HTTPS 地址。", e); }
    }

    byte[] get(String address, int maxBytes, long expectedBytes, String accept,
               OnlinePresetRepository.ProgressListener listener) throws IOException {
        URI uri = httpsUri(address);
        long deadline = System.nanoTime() + 45_000_000_000L;
        for (int redirects = 0; redirects <= 3; redirects++) {
            checkDeadline(deadline);
            HttpsURLConnection connection = connections.open(uri.toURL());
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(8000); connection.setReadTimeout(10000);
                connection.setUseCaches(false); connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", accept);
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status = connection.getResponseCode();
                checkDeadline(deadline);
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirects == 3) throw new IOException("下载重定向次数过多。");
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.length() > 4096) throw new IOException("下载重定向地址无效。");
                    try { uri = httpsUri(uri.resolve(location).toString()); }
                    catch (IllegalArgumentException e) { throw new IOException("下载重定向地址无效。", e); }
                    continue;
                }
                if (status != 200) throw new IOException("服务器返回 HTTP " + status + "，未完成下载。");
                String encoding = connection.getHeaderField("Content-Encoding");
                if (encoding != null && !encoding.isEmpty() && !"identity".equalsIgnoreCase(encoding))
                    throw new IOException("服务器返回了不支持的内容编码。");
                long declared = connection.getContentLengthLong();
                if (declared > maxBytes || (expectedBytes >= 0 && declared >= 0 && declared != expectedBytes))
                    throw new IOException("服务器声明的文件大小与限制或目录不符。");
                long total = expectedBytes >= 0 ? expectedBytes : declared;
                if (listener != null) listener.onProgress(0, total);
                try (InputStream input = connection.getInputStream()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
                    byte[] buffer = new byte[8192]; int count;
                    while (true) {
                        checkDeadline(deadline);
                        count = input.read(buffer);
                        checkDeadline(deadline);
                        if (count == -1) break;
                        if (count == 0) throw new IOException("下载未取得进展。");
                        if (count > maxBytes - bytes.size() || (expectedBytes >= 0 && count > expectedBytes - bytes.size()))
                            throw new IOException("下载超过允许的大小，文件未加入本地库。");
                        bytes.write(buffer, 0, count);
                        if (listener != null) listener.onProgress(bytes.size(), total);
                    }
                    if (expectedBytes >= 0 && bytes.size() != expectedBytes)
                        throw new IOException("下载文件不完整，未加入本地库。");
                    if (declared >= 0 && bytes.size() != declared) throw new IOException("下载长度与服务器声明不符。");
                    return bytes.toByteArray();
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("下载重定向次数过多。");
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已取消。");
        if (System.nanoTime() - deadline >= 0) throw new IOException("下载超时，请检查网络后重试。");
    }
}

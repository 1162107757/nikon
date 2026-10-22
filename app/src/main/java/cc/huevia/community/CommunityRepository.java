package cc.huevia.community;

import cc.huevia.account.AccountJson;
import cc.huevia.account.AccountRepository;
import cc.huevia.library.OnlinePreset;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Worker-only community API. Every write is issued once and retains the caller's idempotency key. */
public final class CommunityRepository {
    private final AccountRepository accounts;
    private final AccountRepository.Transport transport;
    private String installationId;
    public CommunityRepository(AccountRepository accounts) { this(accounts, new CommunityHttpTransport()); }
    public CommunityRepository(AccountRepository accounts, AccountRepository.Transport transport) { this.accounts = accounts; this.transport = transport; }
    public void setInstallationId(String value) throws IOException {
        id(value); installationId = value;
        if (transport instanceof CommunityHttpTransport) ((CommunityHttpTransport)transport).setInstallationId(value);
    }
    public Map<String,Object> get(String path, boolean login) throws IOException { return request("GET", path, null, login); }
    public Map<String,Object> getForOwner(String path,String ownerId) throws IOException {
        id(ownerId);return decode(accounts.requestWithSession(transport,"GET",path,null,true,ownerId));
    }
    public Map<String,Object> write(String method, String path, Map<String,Object> body) throws IOException { return request(method, path, body, true); }
    public Map<String,Object> writeForOwner(String method, String path, Map<String,Object> body, String ownerId) throws IOException {
        id(ownerId);
        byte[] bytes = body == null ? null : AccountJson.stringify(body).getBytes(StandardCharsets.UTF_8);
        return decode(accounts.requestWithSession(transport, method, path, bytes, true, ownerId));
    }
    private Map<String,Object> request(String method, String path, Map<String,Object> body, boolean login) throws IOException {
        byte[] bytes = body == null ? null : AccountJson.stringify(body).getBytes(StandardCharsets.UTF_8);
        return decode(accounts.requestWithSession(transport, method, path, bytes, login));
    }
    public Map<String,Object> upload(String kind, String name, byte[] bytes, String mime) throws IOException {
        if ((!"ncp".equals(kind) && !"preview".equals(kind)) || bytes == null || bytes.length == 0
                || bytes.length > ("ncp".equals(kind) ? 1024 * 1024 : 4 * 1024 * 1024)) throw new IOException("文件大小或格式不符合要求。");
        String path = "/community/uploads?kind=" + kind + "&filename=" + encode(name);
        CommunityHttpTransport upload = new CommunityHttpTransport(mime); upload.setInstallationId(installationId);
        return decode(accounts.requestWithSession(upload, "POST", path, bytes, true));
    }
    public static String encode(String value) { return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8); }
    public static Map<String,Object> body(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i+1]); return result;
    }
    public static String id(String value) throws IOException {
        if (value == null || !value.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw new IOException("内容标识无效，请刷新后重试。");
        return value;
    }
    public static Map<String,Object> object(Object value) throws IOException { return AccountJson.object(value); }
    public static String text(Map<String,Object> value, String key) {
        Object item = value.get(key); if (!(item instanceof String)) return "";
        String result = (String) item; return result.length() > 4096 ? result.substring(0,4096) : result;
    }
    public static long number(Map<String,Object> value, String key, long fallback) {
        try { return AccountJson.integer(value,key); } catch (IOException problem) { return fallback; }
    }
    public static List<Map<String,Object>> items(Map<String,Object> value) throws IOException {
        Object rows = value.get("items"); if (!(rows instanceof List) || ((List<?>) rows).size() > 200) throw new IOException("列表响应无效。");
        List<Map<String,Object>> result = new ArrayList<>(); for (Object row : (List<?>) rows) result.add(object(row)); return Collections.unmodifiableList(result);
    }
    public static OnlinePreset preset(Map<String,Object> value) throws IOException { return OnlinePreset.fromMap(value); }
    private static Map<String,Object> decode(AccountRepository.Response response) throws IOException {
        Map<String,Object> value;
        try {
            if (response.body.length == 0 && response.status >= 200 && response.status < 300) return new LinkedHashMap<>();
            if (response.body.length == 0 || response.body.length > 512*1024) throw new IOException();
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(response.body)).toString();
            value = object(AccountJson.parse(json));
        } catch (Exception problem) { throw new CommunityException("INVALID_RESPONSE", response.status, "服务响应不完整，请稍后重试。"); }
        if (response.status >= 200 && response.status < 300) return value;
        Map<String,Object> error = value.get("error") instanceof Map ? object(value.get("error")) : Collections.emptyMap();
        String code = text(error,"code"), message = text(error,"message");
        if (response.status == 401) message = "登录已失效，请重新登录后继续。";
        else if (response.status == 429) message = "操作较频繁，请稍后再试。";
        else if (response.status >= 500) message = "服务暂不可用，请稍后手动重试，草稿已保留。";
        else if (message.isEmpty()) message = "操作未完成，请刷新后重试。";
        throw new CommunityException(code, response.status, message);
    }
}

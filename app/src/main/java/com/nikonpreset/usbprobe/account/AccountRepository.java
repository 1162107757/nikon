package com.nikonpreset.usbprobe.account;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Synchronous worker-thread API. The constructor and cachedSession() do no disk or network work. */
public final class AccountRepository {
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;
    public static final int MAX_AVATAR_BYTES = 1024 * 1024;

    public interface Store {
        byte[] read() throws IOException;
        void write(byte[] value) throws IOException;
        void clear() throws IOException;
    }
    public interface Transport {
        Response request(String method, String path, String bearerToken, byte[] body) throws IOException;
        default Response profileRequest(String method, String path, String bearerToken, byte[] body,
                                        String installationId, String expectedUpdatedAt) throws IOException {
            return request(method, path, bearerToken, body);
        }
    }
    public interface Clock { long nowMillis(); }
    public static final class Response {
        public final int status;
        public final byte[] body;
        public Response(int status, byte[] body) {
            this.status = status;
            this.body = body == null ? new byte[0] : body;
        }
    }
    public static final class User {
        public final String id, username, nickname, avatarUrl, avatarSource, provider, status;
        public final String createdAt, lastLoginAt, updatedAt;
        public final long loginCount;
        private final Map<String, Object> json;
        private User(Map<String, Object> value) throws IOException {
            id = text(value, "id", 36, false);
            if (!id.matches("(?i)[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) throw invalid();
            username = text(value, "username", 32, true);
            if (!username.isEmpty() && !username.matches("[a-z][a-z0-9_]{2,31}")) throw invalid();
            nickname = text(value, "nickname", 160, true);
            avatarUrl = text(value, "avatarUrl", 2048, true);
            if (!avatarUrl.isEmpty()) {
                try {
                    java.net.URI uri = new java.net.URI(avatarUrl);
                    if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                            || uri.getRawFragment() != null) throw invalid();
                } catch (java.net.URISyntaxException e) { throw invalid(); }
            }
            provider = text(value, "provider", 16, false);
            if (!"password".equals(provider) && !"qq".equals(provider)) throw invalid();
            String source = text(value, "avatarSource", 16, true);
            avatarSource = source.isEmpty() ? ("qq".equals(provider) && !avatarUrl.isEmpty() ? "qq" : "default") : source;
            if (!"qq".equals(avatarSource) && !"custom".equals(avatarSource) && !"default".equals(avatarSource)) throw invalid();
            status = text(value, "status", 16, false);
            if (!"active".equals(status)) throw new AccountException("USER_DISABLED", 403, "账号已停用，请联系管理员。");
            createdAt = timestamp(value, "createdAt");
            lastLoginAt = timestamp(value, "lastLoginAt");
            updatedAt = timestamp(value, "updatedAt");
            loginCount = AccountJson.integer(value, "loginCount");
            if (loginCount < 0 || loginCount > 9007199254740991L) throw invalid();
            json = new LinkedHashMap<>();
            json.put("id", id); json.put("username", username); json.put("nickname", nickname);
            json.put("avatarUrl", avatarUrl); json.put("avatarSource", avatarSource); json.put("provider", provider); json.put("status", status);
            json.put("createdAt", createdAt); json.put("lastLoginAt", lastLoginAt);
            json.put("updatedAt", updatedAt); json.put("loginCount", loginCount);
        }
    }
    public static final class Session {
        public final User user;
        public final String expiresAt;
        public final long expiresAtMillis;
        /** False means a restored/offline identity; online-only actions must still be authorized by the server. */
        public final boolean verified;
        private final String token;
        private Session(User user, String token, String expiresAt, boolean verified) throws IOException {
            this.user = user; this.token = token; this.expiresAt = expiresAt;
            expiresAtMillis = parseTimestamp(expiresAt);
            this.verified = verified;
        }
        private Session unverified() throws IOException { return new Session(user, token, expiresAt, false); }
    }

    private final Store store;
    private final Transport transport;
    private final Clock clock;
    private Session session;
    private long generation;
    private boolean restored;
    private boolean logoutPending;

    public AccountRepository(Store store, Transport transport) {
        this(store, transport, System::currentTimeMillis);
    }
    public AccountRepository(Store store, Transport transport, Clock clock) {
        if (store == null || transport == null || clock == null) throw new IllegalArgumentException("Account dependencies are required");
        this.store = store; this.transport = transport; this.clock = clock;
    }

    /** Must run on the account executor before the first account operation. No password is ever restored. */
    public synchronized Session restore() throws IOException {
        if (restored) return current();
        restored = true;
        byte[] bytes = null;
        try {
            bytes = store.read();
            if (bytes == null) return null;
            Map<String, Object> json = decode(bytes);
            if (AccountJson.integer(json, "version") != 1) throw invalid();
            session = parseSession(json, false);
            if (session.expiresAtMillis <= clock.nowMillis()) { clearLocal(); return null; }
            return session;
        } catch (IOException | RuntimeException error) {
            if (logoutPending) throw clearFailed("本机登录信息尚未清除，请重试退出登录。");
            session = null;
            clearLocal();
            throw new AccountException("SESSION_STORAGE_RESET", 0, "本机登录信息已失效，请重新登录。");
        } finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }

    /** Returns a snapshot only; callers never obtain the bearer token. */
    public synchronized Session cachedSession() {
        return session != null && (logoutPending || session.expiresAtMillis > clock.nowMillis()) ? session : null;
    }

    /** A failed local clear is a retryable cleanup state, never a usable authenticated session. */
    public synchronized boolean isLogoutPending() { return logoutPending; }

    public Session register(String username, String password) throws IOException { return authenticate("/auth/register", username, password); }
    public Session register(String username, String password, String challengeId, String challengeAnswer, String installationId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>(); body.put("username", validateUsername(username));
        if (password == null || password.length() < 8 || password.length() > 128)
            throw new AccountException("INVALID_PASSWORD", 400, "密码需要 8–128 个字符。");
        body.put("password", password); addChallenge(body, challengeId, challengeAnswer, installationId);
        return authenticate("/auth/register", body, true);
    }
    public Session login(String username, String password) throws IOException { return authenticate("/auth/login", username, password); }

    /** Performs one request using the existing account identity. Tokens are never persisted by consumers. */
    public Response requestWithSession(Transport target, String method, String path, byte[] body, boolean required) throws IOException {
        return requestWithSession(target, method, path, body, required, null);
    }

    /** Bind a queued user action to its author while taking the bearer snapshot under the same lock. */
    public Response requestWithSession(Transport target, String method, String path, byte[] body, boolean required, String expectedOwnerId) throws IOException {
        final Session before; final long attempt;
        synchronized (this) {
            ensureRestored(); before = current(); attempt = generation;
            if (required && before == null) throw new AccountException("USER_LOGIN_REQUIRED", 401, "登录后即可继续此操作。");
            if (expectedOwnerId != null && (before == null || !expectedOwnerId.equals(before.user.id))) throw changed();
        }
        Response response = target.request(method, path, before == null ? null : before.token, body);
        synchronized (this) {
            if (attempt != generation || (before != null && session != before)) throw changed();
            if (before != null && response.status == 401) clearLocal();
            else if (before != null && response.status == 403) {
                try {
                    Map<String,Object> error = AccountJson.object(decode(response.body).get("error"));
                    if ("USER_DISABLED".equals(error.get("code"))) clearLocal();
                } catch (IOException ignored) { /* A resource-level denial does not sign out an active user. */ }
            }
        }
        return response;
    }

    public Map<String, Object> challenge(String installationId) throws IOException {
        if (installationId == null || !installationId.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw invalid();
        return success(transport.request("GET", "/auth/challenge?installationId=" + installationId, null, null));
    }

    private static void addChallenge(Map<String, Object> body, String id, String answer, String installation) throws IOException {
        if (id == null || !id.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                || installation == null || !installation.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                || answer == null || !answer.matches("[A-Za-z0-9]{4,8}"))
            throw new AccountException("CHALLENGE_REQUIRED", 428, "请填写图片验证码后继续。");
        body.put("challengeId", id); body.put("challengeAnswer", answer); body.put("installationId", installation);
    }

    /** Exchanges a short-lived QQ credential for this app's own session; QQ credentials are never stored. */
    public Session loginWithQq(String accessToken) throws IOException {
        return loginWithQq(accessToken, null, null, null);
    }

    public Session loginWithQq(String accessToken, String challengeId, String challengeAnswer, String installationId) throws IOException {
        if (accessToken == null || accessToken.length() < 16 || accessToken.length() > 2048
                || !accessToken.matches("[\\x21-\\x7e]+"))
            throw new AccountException("qq_invalid_token", 400, "QQ 登录信息不完整，请重新授权。");
        Map<String, Object> body = new LinkedHashMap<>(); body.put("accessToken", accessToken);
        if (challengeId != null) addChallenge(body, challengeId, challengeAnswer, installationId);
        return authenticate("/auth/qq", body, false);
    }

    private Session authenticate(String path, String username, String password) throws IOException {
        String normalized = validateUsername(username);
        if (password == null || password.length() < 8 || password.length() > 128)
            throw new AccountException("INVALID_PASSWORD", 400, "密码需要 8–128 个字符。");
        Map<String, Object> body = new LinkedHashMap<>(); body.put("username", normalized); body.put("password", password);
        return authenticate(path, body, true);
    }

    private Session authenticate(String path, Map<String, Object> body, boolean passwordLogin) throws IOException {
        long attempt;
        synchronized (this) {
            ensureRestored();
            if (logoutPending) throw clearFailed("本机登录信息尚未清除，请先重试退出登录。");
            attempt = ++generation;
        }
        byte[] request = AccountJson.stringify(body).getBytes(StandardCharsets.UTF_8);
        Session fresh;
        try { fresh = parseSession(success(transport.request("POST", path, null, request)), true); }
        catch (AccountException e) {
            if (passwordLogin && e.status == 401) throw new AccountException("USER_LOGIN_FAILED", 401, "用户名或密码不正确。");
            throw e;
        }
        catch (IOException e) { throw network(); }
        finally { Arrays.fill(request, (byte) 0); body.clear(); }
        boolean superseded;
        boolean saveFailed = false;
        synchronized (this) {
            superseded = attempt != generation;
            if (!superseded) {
                if (fresh.expiresAtMillis <= clock.nowMillis()) throw new AccountException("SESSION_EXPIRED", 401, "登录已过期，请重新登录。");
                try { persist(fresh); }
                catch (IOException e) { saveFailed = true; }
                if (!saveFailed) { session = fresh; return session; }
            }
        }
        revokeQuietly(fresh.token);
        if (saveFailed) throw new AccountException("SESSION_SAVE_FAILED", 0, "无法安全保存登录信息，请稍后重试。");
        throw new AccountException("SESSION_CHANGED", 0, "登录操作已取消，请重新登录。");
    }

    /** Offline errors preserve the cached identity and mark it unverified. */
    public Session refresh() throws IOException {
        final Session before;
        final long attempt;
        synchronized (this) { ensureRestored(); before = current(); if (before == null) return null; attempt = generation; }
        try {
            Map<String, Object> json = success(transport.request("GET", "/auth/me", before.token, null));
            User user = new User(AccountJson.object(json.get("user")));
            if (!before.user.id.equals(user.id)) throw invalid();
            Session fresh = new Session(user, before.token, timestamp(json, "expiresAt"), true);
            synchronized (this) {
                if (generation != attempt || session != before) throw changed();
                if (fresh.expiresAtMillis <= clock.nowMillis()) { clearLocal(); throw expired(); }
                persist(fresh); session = fresh; return fresh;
            }
        } catch (IOException error) {
            synchronized (this) {
                if (generation == attempt && session == before) {
                    if (error instanceof AccountException && (((AccountException) error).status == 401 || ((AccountException) error).status == 403)) clearLocal();
                    else session = before.unverified();
                }
            }
            if (error instanceof AccountException) throw error;
            throw network();
        }
    }

    /** The picker owner is required: a result from an older account must never edit the next account. */
    public Session updateAvatar(Session owner, byte[] jpeg, String installationId) throws IOException {
        if (jpeg == null || jpeg.length < 4 || jpeg.length > MAX_AVATAR_BYTES
                || (jpeg[0] & 255) != 255 || (jpeg[1] & 255) != 216
                || (jpeg[jpeg.length - 2] & 255) != 255 || (jpeg[jpeg.length - 1] & 255) != 217)
            throw new AccountException("INVALID_AVATAR", 400, "请选择有效图片，头像需小于 1 MB。");
        return changeAvatar(owner, jpeg.clone(), false, installationId);
    }

    public Session restoreDefaultAvatar(Session owner, String installationId) throws IOException {
        return changeAvatar(owner, null, true, installationId);
    }

    private Session changeAvatar(Session owner, byte[] image, boolean reset, String installationId) throws IOException {
        Session before = null; long attempt = -1;
        byte[] request = image;
        try {
            if (installationId == null || !installationId.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw invalid();
            synchronized (this) {
                ensureRestored(); before = current(); attempt = generation;
                if (before == null) throw new AccountException("USER_LOGIN_REQUIRED", 401, "登录后可修改头像。");
                if (owner == null || !before.user.id.equals(owner.user.id) || !before.user.updatedAt.equals(owner.user.updatedAt)) throw changed();
            }
            if (reset) request = AccountJson.stringify(Map.of("expectedUpdatedAt", owner.user.updatedAt)).getBytes(StandardCharsets.UTF_8);
            Response response = transport.profileRequest(reset ? "POST" : "PUT", reset ? "/profile/avatar/reset" : "/profile/avatar",
                    before.token, request, installationId, owner.user.updatedAt);
            Map<String, Object> json = success(response);
            User user = new User(AccountJson.object(json.get("user")));
            if (!before.user.id.equals(user.id)) throw invalid();
            Session fresh = new Session(user, before.token, before.expiresAt, true);
            synchronized (this) {
                if (attempt != generation || session != before) throw changed();
                if (fresh.expiresAtMillis <= clock.nowMillis()) { clearLocal(); throw expired(); }
                try { persist(fresh); }
                catch (IOException error) { throw new AccountException("PROFILE_SAVE_FAILED", 0, "头像已更新，但本机资料未能保存。请刷新账号资料后查看。"); }
                session = fresh; return fresh;
            }
        } catch (IOException error) {
            synchronized (this) {
                if (before != null && attempt == generation && session == before && error instanceof AccountException
                        && (((AccountException) error).status == 401 || "USER_DISABLED".equals(((AccountException) error).code))) clearLocal();
            }
            if (error instanceof AccountException) throw error;
            throw network();
        } finally { if (request != null) Arrays.fill(request, (byte) 0); }
    }

    /** Attempts both local cleanup and server revocation. Local failure stays visible and retryable. */
    public void logout() throws IOException {
        final Session before;
        boolean localFailed = false;
        synchronized (this) {
            try { ensureRestored(); }
            catch (IOException error) { if (!logoutPending) throw error; }
            before = session;
            try { clearLocal(); }
            catch (IOException error) { localFailed = true; }
        }
        boolean remoteFailed = false;
        if (before != null) {
            try {
                Response response = transport.request("POST", "/auth/logout", before.token, "{}".getBytes(StandardCharsets.UTF_8));
                if (response.status != 401 && response.status != 403) success(response);
            } catch (IOException error) { remoteFailed = true; }
        }
        synchronized (this) {
            if (localFailed && logoutPending) {
                throw clearFailed(remoteFailed ? "本机登录信息未清除，服务器注销也未完成，请重试退出登录。"
                        : before == null ? "本机登录信息尚未清除，请重试退出登录。"
                        : "服务器已注销，但本机登录信息尚未清除，请重试退出登录。");
            }
        }
        if (remoteFailed) {
            throw new AccountException("LOGOUT_REMOTE_FAILED", 0, "本机已退出；服务器注销暂未完成，原会话将到期失效。");
        }
    }

    private synchronized Session current() throws IOException {
        if (logoutPending) throw clearFailed("本机登录信息尚未清除，请重试退出登录。");
        if (session != null && session.expiresAtMillis <= clock.nowMillis()) { clearLocal(); throw expired(); }
        return session;
    }
    private void ensureRestored() throws IOException { if (!restored) restore(); }
    private void clearLocal() throws IOException {
        // Invalidate every in-flight operation even if the backing store cannot yet be removed.
        generation++;
        try { store.clear(); }
        catch (IOException e) {
            logoutPending = true;
            if (session != null) session = session.unverified();
            throw clearFailed("本机登录信息尚未清除，请重试退出登录。");
        }
        session = null;
        logoutPending = false;
    }
    private void persist(Session fresh) throws IOException {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", 1); value.put("tokenType", "Bearer"); value.put("accessToken", fresh.token);
        value.put("expiresAt", fresh.expiresAt); value.put("user", fresh.user.json);
        byte[] bytes = AccountJson.stringify(value).getBytes(StandardCharsets.UTF_8);
        try { store.write(bytes); } finally { Arrays.fill(bytes, (byte) 0); }
    }
    private void revokeQuietly(String token) {
        try { transport.request("POST", "/auth/logout", token, "{}".getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ignored) { /* No credential logging, including failed cleanup. */ }
    }
    private static Session parseSession(Map<String, Object> json, boolean verified) throws IOException {
        if (!"Bearer".equals(text(json, "tokenType", 16, false))) throw invalid();
        String token = text(json, "accessToken", 43, false);
        if (!token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        return new Session(new User(AccountJson.object(json.get("user"))), token, timestamp(json, "expiresAt"), verified);
    }
    private static Map<String, Object> success(Response response) throws IOException {
        Map<String, Object> json;
        try { json = decode(response.body); }
        catch (IOException e) {
            if (response.status == 401) throw new AccountException("USER_LOGIN_REQUIRED", 401, "登录已失效，请重新登录。");
            if (response.status == 403) throw new AccountException("USER_DISABLED", 403, "账号暂不可用，请联系管理员。");
            if (response.status == 429) throw new AccountException("USER_AUTH_THROTTLED", 429, "尝试次数较多，请稍后再试。");
            if (response.status >= 500) throw new AccountException("SERVICE_UNAVAILABLE", response.status, "账号服务暂不可用，请稍后重试。");
            throw invalid();
        }
        if (response.status >= 200 && response.status <= 299) return json;
        String code = "";
        Object error = json.get("error");
        if (error instanceof Map) {
            Object value = ((Map<?, ?>) error).get("code");
            if (value instanceof String && ((String) value).matches("[A-Za-z_]{1,64}")) code = (String) value;
        }
        switch (code) {
            case "USER_CONFLICT": throw new AccountException(code, response.status, "账号资料已有更新，请刷新后重新选择头像。");
            case "INVALID_AVATAR":
            case "AVATAR_TOO_LARGE": throw new AccountException(code, response.status, "头像图片无效或过大，请重新选择图片。");
            case "CHALLENGE_REQUIRED": throw new AccountException(code, response.status, "为保护账号安全，请先完成图片验证。");
            case "CHALLENGE_INVALID":
            case "CHALLENGE_EXPIRED": throw new AccountException(code, response.status, "验证码不正确或已过期，请换一张后重试。");
            case "INVALID_USERNAME": throw new AccountException(code, response.status, "用户名需要 3–32 位，以英文字母开头，仅含字母、数字和下划线。");
            case "INVALID_PASSWORD": throw new AccountException(code, response.status, "密码需要 8–128 个字符。");
            case "USERNAME_TAKEN": throw new AccountException(code, response.status, "这个用户名已被使用，请换一个。");
            case "USER_DISABLED": throw new AccountException(code, response.status, "账号已停用，请联系管理员。");
            case "USER_LOGIN_FAILED": throw new AccountException(code, response.status, "用户名或密码不正确。");
            case "USER_LOGIN_REQUIRED": throw new AccountException(code, response.status, "登录已失效，请重新登录。");
            case "qq_not_configured": throw new AccountException(code, response.status, "QQ 登录暂未开放，请稍后再试。");
            case "qq_invalid_token": throw new AccountException(code, response.status, "QQ 登录信息不完整，请重新授权。");
            case "qq_verification_failed": throw new AccountException(code, response.status, "QQ 授权已失效或未通过验证，请重新授权。");
            case "qq_provider_unavailable": throw new AccountException(code, response.status, "暂时无法验证 QQ 用户信息，请稍后重试。");
            case "qq_timeout": throw new AccountException(code, response.status, "QQ 授权验证超时，请稍后重试。");
            case "qq_login_busy":
            case "qq_login_throttled": throw new AccountException(code, response.status, "QQ 登录请求较多，请稍后再试。");
            default:
                if (response.status == 429) throw new AccountException("USER_AUTH_THROTTLED", 429, "尝试次数较多，请稍后再试。");
                if (response.status == 401) throw new AccountException("USER_LOGIN_REQUIRED", 401, "登录已失效，请重新登录。");
                if (response.status == 403) throw new AccountException("USER_DISABLED", 403, "账号暂不可用，请联系管理员。");
                if (response.status >= 500) throw new AccountException("SERVICE_UNAVAILABLE", response.status, "账号服务暂不可用，请稍后重试。");
                throw new AccountException("ACCOUNT_REQUEST_FAILED", response.status, "操作未完成，请稍后重试。");
        }
    }
    private static String validateUsername(String username) throws AccountException {
        if (username == null || !username.matches("[A-Za-z][A-Za-z0-9_]{2,31}"))
            throw new AccountException("INVALID_USERNAME", 400, "用户名需要 3–32 位，以英文字母开头，仅含字母、数字和下划线。");
        return username.toLowerCase(Locale.ROOT);
    }
    private static String text(Map<String, Object> map, String key, int max, boolean empty) throws IOException {
        Object value = map.get(key);
        if (empty && value == null) return "";
        if (!(value instanceof String)) throw invalid();
        String string = (String) value;
        if (string.length() > max || (!empty && string.isEmpty())) throw invalid();
        for (int i = 0; i < string.length(); i++) if (string.charAt(i) < 32 || string.charAt(i) == 127) throw invalid();
        return string;
    }
    private static String timestamp(Map<String, Object> map, String key) throws IOException {
        String value = text(map, key, 40, false); parseTimestamp(value); return value;
    }
    private static long parseTimestamp(String value) throws AccountException {
        try { return Instant.parse(value).toEpochMilli(); }
        catch (DateTimeParseException | ArithmeticException e) { throw invalid(); }
    }
    static Map<String, Object> decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) throw invalid();
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return AccountJson.object(AccountJson.parse(value));
        } catch (CharacterCodingException | RuntimeException e) { throw invalid(); }
    }
    private static AccountException invalid() { return new AccountException("INVALID_RESPONSE", 0, "账号服务返回异常，请稍后重试。"); }
    private static AccountException network() { return new AccountException("NETWORK_UNAVAILABLE", 0, "暂时无法连接账号服务，请检查网络后重试。"); }
    private static AccountException changed() { return new AccountException("SESSION_CHANGED", 0, "登录状态已变化，请重试。"); }
    private static AccountException expired() { return new AccountException("SESSION_EXPIRED", 401, "登录已过期，请重新登录。"); }
    private static AccountException clearFailed(String message) { return new AccountException("SESSION_CLEAR_FAILED", 0, message); }
}

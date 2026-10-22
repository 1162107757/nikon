package cc.huevia.account;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static cc.huevia.account.AccountRepositoryTest.*;

/** Profile mutation guards and public URL bounds run without an Android runtime. */
public final class AccountAvatarTest {
    static final String INSTALLATION = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
    static final byte[] JPEG = new byte[]{(byte)255,(byte)216,1,2,(byte)255,(byte)217};
    public static void main(String[] args) throws Exception {
        updates(); failures(); ownerRace(); bounds();
        System.out.println("Avatar account checks passed (atomic session update, picker owner, stale responses, public origins and request bounds).");
    }
    static void updates() throws Exception {
        MemoryStore store = new MemoryStore(); Profile transport = new Profile(); AccountRepository repo = new AccountRepository(store, transport, () -> NOW);
        AccountRepository.Session owner = repo.login("abc", "password");
        transport.user = new LinkedHashMap<>(user(ID_A)); transport.user.put("avatarSource", "custom");
        String url = AccountHttpTransport.BASE_URL + "/avatars/" + ID_A + ".jpg";
        transport.user.put("avatarUrl", url); transport.user.put("updatedAt", "2026-10-18T00:01:00.123456+00:00");
        AccountRepository.Session updated = repo.updateAvatar(owner, JPEG, INSTALLATION);
        check(updated.user.avatarUrl.equals(url) && updated.user.avatarSource.equals("custom") && updated.verified, "new user fields accepted");
        check(transport.expected.equals(owner.user.updatedAt) && transport.installation.equals(INSTALLATION) && transport.token.equals(TOKEN_A), "profile request reuses bearer and exact raw version");
        check(transport.method.equals("PUT") && transport.path.equals("/profile/avatar") && Arrays.equals(transport.snapshot, JPEG), "upload body remains raw JPEG");
        check(Arrays.equals(transport.borrowed, new byte[transport.borrowed.length]) && JPEG[0] == (byte)255, "temporary request buffer cleared without altering caller preview");
        Map<String,Object> saved = AccountRepository.decode(store.bytes);
        check(saved.get("accessToken").equals(TOKEN_A) && saved.get("expiresAt").equals(EXPIRY), "single existing session retains its token and expiry");
        check(AccountJson.object(saved.get("user")).get("avatarUrl").equals(url), "avatar user persisted atomically to the existing store");
        transport.user = new LinkedHashMap<>(user(ID_A)); transport.user.put("avatarSource", "default");
        repo.restoreDefaultAvatar(updated, INSTALLATION);
        check(transport.method.equals("POST") && transport.path.equals("/profile/avatar/reset")
                && AccountRepository.decode(transport.snapshot).equals(Map.of("expectedUpdatedAt", updated.user.updatedAt)), "reset sends only exact expected version");
    }
    static void failures() throws Exception {
        MemoryStore store = new MemoryStore(); Profile transport = new Profile(); AccountRepository repo = new AccountRepository(store, transport, () -> NOW);
        AccountRepository.Session owner = repo.login("abc", "password"); byte[] before = store.bytes.clone();
        transport.offline = true; failure("NETWORK_UNAVAILABLE", () -> repo.updateAvatar(owner, JPEG, INSTALLATION));
        check(repo.cachedSession() == owner && Arrays.equals(store.bytes, before), "offline failure retains old identity and avatar on disk");
        transport.offline = false; transport.user = new LinkedHashMap<>(user(ID_A)); transport.user.put("avatarUrl", AccountHttpTransport.BASE_URL + "/avatars/" + ID_A + ".jpg");
        store.writeFails = true; failure("PROFILE_SAVE_FAILED", () -> repo.updateAvatar(owner, JPEG, INSTALLATION));
        check(repo.cachedSession() == owner && Arrays.equals(store.bytes, before), "storage failure cannot partially swap in-memory identity");
        store.writeFails = false; transport.status = 409; failure("USER_CONFLICT", () -> repo.updateAvatar(owner, JPEG, INSTALLATION));
        check(repo.cachedSession() == owner, "version conflict preserves old avatar");
        int calls = transport.profileCalls;
        for (byte[] bad : new byte[][]{null, new byte[0], new byte[4], new byte[AccountRepository.MAX_AVATAR_BYTES + 1]}) failure("INVALID_AVATAR", () -> repo.updateAvatar(owner, bad, INSTALLATION));
        check(transport.profileCalls == calls, "invalid uploads never contact service");
    }
    static void ownerRace() throws Exception {
        MemoryStore store = new MemoryStore(); Profile transport = new Profile(); AccountRepository repo = new AccountRepository(store, transport, () -> NOW);
        AccountRepository.Session owner = repo.login("abc", "password");
        transport.loginId = ID_B; transport.loginToken = TOKEN_B; repo.login("second", "password");
        failure("SESSION_CHANGED", () -> repo.updateAvatar(owner, JPEG, INSTALLATION));
        check(transport.profileCalls == 0 && repo.cachedSession().user.id.equals(ID_B), "old picker result never edits a newly signed-in account");
        transport.loginId = ID_A; transport.loginToken = TOKEN_A; AccountRepository.Session fresh = repo.login("first", "password");
        transport.entered = new CountDownLatch(1); transport.release = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread pending = thread(() -> repo.updateAvatar(fresh, JPEG, INSTALLATION), error);
        check(transport.entered.await(3, TimeUnit.SECONDS), "avatar request is pending");
        repo.logout(); transport.loginId = ID_B; transport.loginToken = TOKEN_B; repo.login("second", "password");
        transport.release.countDown(); join(pending);
        check(error.get() instanceof AccountException && ((AccountException)error.get()).code.equals("SESSION_CHANGED"), "late avatar response rejected after identity changed");
        check(repo.cachedSession().user.id.equals(ID_B) && AccountRepository.decode(store.bytes).get("accessToken").equals(TOKEN_B), "old avatar response cannot overwrite newer Keystore session");
    }
    static void bounds() throws Exception {
        String version = "2026-10-18T00:00:00.123456+00:00";
        AccountHttpTransport.validateProfileRequest("PUT", "/profile/avatar", TOKEN_A, JPEG, INSTALLATION, version);
        AccountHttpTransport.validateProfileRequest("POST", "/profile/avatar/reset", TOKEN_A, json(Map.of("expectedUpdatedAt", version)), INSTALLATION, version);
        byte[] max = new byte[AccountRepository.MAX_AVATAR_BYTES]; max[0]=(byte)255; max[1]=(byte)216; max[max.length-2]=(byte)255; max[max.length-1]=(byte)217;
        AccountHttpTransport.validateProfileRequest("PUT", "/profile/avatar", TOKEN_A, max, INSTALLATION, version);
        failure("INVALID_RESPONSE", () -> AccountHttpTransport.validateProfileRequest("PUT", "/profile/avatar", TOKEN_A, new byte[max.length+1], INSTALLATION, version));
        failure("INVALID_RESPONSE", () -> AccountHttpTransport.validateProfileRequest("PUT", "https://evil.test/profile/avatar", TOKEN_A, JPEG, INSTALLATION, version));
        failure("INVALID_RESPONSE", () -> AccountHttpTransport.validateProfileRequest("PUT", "/profile/avatar", TOKEN_A, JPEG, INSTALLATION, version+"\r\nBad: x"));
        check(AvatarUrlPolicy.allows(AccountHttpTransport.BASE_URL + "/avatars/" + ID_A + ".jpg"), "own public avatar route accepted");
        check(AvatarUrlPolicy.allows("https://q.qlogo.cn/headimg_dl?dst_uin=1&spec=100") && AvatarUrlPolicy.allows("https://thirdqq.qlogo.cn/qqapp/123/abc/100"), "verified QQ public origins accepted");
        for (String bad : new String[]{"http://q.qlogo.cn/a", "https://q.qlogo.cn.evil.test/a", "https://evil.test/a", "https://q.qlogo.cn@evil.test/a", "https://q.qlogo.cn:444/a", "https://q.qlogo.cn/a#fragment", AccountHttpTransport.BASE_URL+"/auth/me", AccountHttpTransport.BASE_URL+"/avatars/"+ID_A+".jpg?token=secret"})
            check(!AvatarUrlPolicy.allows(bad), "unsafe public image address rejected");
    }
    static final class Profile implements AccountRepository.Transport {
        String loginId=ID_A, loginToken=TOKEN_A, method,path,token,installation,expected; byte[] snapshot,borrowed;
        Map<String,Object> user=user(ID_A); int profileCalls,status=200; boolean offline; CountDownLatch entered,release;
        public AccountRepository.Response request(String method, String path, String token, byte[] body) {
            return response(200, path.equals("/auth/logout") ? Map.of("ok",true) : authentication(loginToken,loginId));
        }
        public AccountRepository.Response profileRequest(String method, String path, String token, byte[] body, String installation, String expected) throws IOException {
            this.method=method;this.path=path;this.token=token;this.borrowed=body;this.snapshot=body.clone();this.installation=installation;this.expected=expected;profileCalls++;
            if (entered != null) { entered.countDown(); await(release); }
            if (offline) throw new IOException("offline");
            return response(status,status==409?Map.of("error",Map.of("code","USER_CONFLICT")):Map.of("user",user));
        }
    }
}

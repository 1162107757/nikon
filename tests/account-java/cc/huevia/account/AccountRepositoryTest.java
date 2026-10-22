package cc.huevia.account;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AccountRepositoryTest {
    private static int checks;
    static final String TOKEN_A = "A".repeat(43), TOKEN_B = "B".repeat(43);
    static final String QQ_TOKEN = "opaque-qq-token&openid=forged?+/%value";
    static final String ID_A = "19bd1434-e597-4f27-808d-9b86eb8d983d", ID_B = "42ee04c3-b4de-4992-8e22-6ec528b01d92";
    static final long NOW = Instant.parse("2026-10-18T00:00:00Z").toEpochMilli();
    static final String EXPIRY = "2026-11-18T00:00:00Z";

    public static void main(String[] args) throws Exception {
        basic(); validation(); errors(); qqLogin(); qqErrors(); qqConcurrency(); restore(); refresh(); logout(); failedLocalLogout(); concurrency(); sharedLifecycle(); transport();
        System.out.println("Account regression passed: " + checks + " checks (host logic/transport bounds; AndroidKeyStore compile only).");
    }
    static void basic() throws Exception {
        MemoryStore store = new MemoryStore(); Fake transport = new Fake();
        transport.answer = response(201, authentication(TOKEN_A, ID_A));
        AccountRepository repository = repository(store, transport);
        check(store.reads == 0 && repository.cachedSession() == null, "construction performs no I/O");
        AccountRepository.Session session = repository.register("Photo_User", " 123456 ");
        check("/auth/register".equals(transport.path) && "POST".equals(transport.method) && transport.token == null, "registration route without bearer");
        Map<String,Object> sent = AccountRepository.decode(transport.body);
        check("photo_user".equals(sent.get("username")), "username normalized");
        check(" 123456 ".equals(sent.get("password")), "password spaces preserved");
        check(session.verified && session.user.id.equals(ID_A) && session.user.username.equals("photo_user"), "registration immediately authenticates");
        check(store.bytes != null && !AccountRepository.decode(store.bytes).containsKey("password")
                && !new String(store.bytes, StandardCharsets.UTF_8).contains(" 123456 "), "password is never persisted");
        check(store.writes == 1 && repository.cachedSession() == session, "atomic store contract before state exposure");
        repository.login("photo_user", "pass\u0000word");
        check("pass\u0000word".equals(AccountRepository.decode(transport.body).get("password")), "password controls remain exact");
        check("/auth/login".equals(transport.path), "login route");
        check(session.expiresAtMillis == Instant.parse(EXPIRY).toEpochMilli() && session.user.loginCount == 1, "typed account fields");
    }
    static void validation() throws Exception {
        MemoryStore store = new MemoryStore(); Fake fake = new Fake(); AccountRepository repo = repository(store, fake);
        for (String username : new String[]{"ab", "1test", "with space", " photo", "photo ", "中文用户", "x".repeat(33), "a\nxx"}) {
            failure("INVALID_USERNAME", () -> repo.login(username, "password"));
        }
        for (String password : new String[]{"", "1234567", "x".repeat(129)}) failure("INVALID_PASSWORD", () -> repo.register("valid", password));
        check(fake.calls == 0 && store.reads == 0, "validation failures have no I/O");
        repo.register("Abc", "x".repeat(128)); check(fake.calls == 1, "password upper length boundary");
    }
    static void qqLogin() throws Exception {
        MemoryStore store = new MemoryStore(); Fake fake = new Fake(); AccountRepository repo = repository(store, fake);
        for (String token : new String[]{null, "", "a".repeat(15), "a".repeat(2049), "token with spaces", "qq-token\nwith-newline", "中文".repeat(12)})
            failure("qq_invalid_token", () -> repo.loginWithQq(token));
        check(fake.calls == 0 && store.reads == 0, "invalid QQ credentials perform no disk or network work");
        AtomicReference<byte[]> request = new AtomicReference<>();
        fake.custom = (method, path, token, body) -> { request.set(body); return response(200, qqAuthentication(TOKEN_A, ID_A)); };
        AccountRepository.Session session = repo.loginWithQq(QQ_TOKEN);
        check("POST".equals(fake.method) && "/auth/qq".equals(fake.path) && fake.token == null, "QQ exchanges credential only at the login route without app bearer");
        check(AccountRepository.decode(fake.body).equals(Map.of("accessToken", QQ_TOKEN)), "QQ request sends only opaque credential, never client identity");
        check(Arrays.equals(request.get(), new byte[request.get().length]), "QQ request buffer cleared after exchange");
        check(session.verified && "qq".equals(session.user.provider) && session.user.username.isEmpty(), "QQ accepts server-provided identity without password username");
        Map<String,Object> saved = AccountRepository.decode(store.bytes);
        check(TOKEN_A.equals(saved.get("accessToken")) && !new String(store.bytes, StandardCharsets.UTF_8).contains(QQ_TOKEN), "only independent app session is persisted, never QQ credential");
        AccountRepository.Session restored = repository(store, fake).restore();
        check(restored != null && !restored.verified && restored.user.provider.equals("qq"), "QQ identity restores using existing protected store contract");
        fake.custom = null; fake.answer = response(200, Map.of("user", qqAuthentication(TOKEN_A, ID_A).get("user"), "expiresAt", EXPIRY));
        check(repo.refresh().verified && "/auth/me".equals(fake.path) && TOKEN_A.equals(fake.token), "QQ refresh uses independent app bearer");
        fake.answer = response(200, Map.of("ok", true)); repo.logout();
        check(TOKEN_A.equals(fake.token) && "/auth/logout".equals(fake.path) && store.bytes == null, "QQ logout revokes app session and clears local storage");
        MemoryStore failing = new MemoryStore(); failing.writeFails = true; Fake cleanup = new Fake(); cleanup.answer = response(200, qqAuthentication(TOKEN_B, ID_B));
        failure("SESSION_SAVE_FAILED", () -> repository(failing, cleanup).loginWithQq(QQ_TOKEN));
        check(TOKEN_B.equals(cleanup.token) && "/auth/logout".equals(cleanup.path) && failing.bytes == null, "QQ failed storage revokes only newly issued app session");
    }
    static void qqErrors() throws Exception {
        MemoryStore store = new MemoryStore(); Fake fake = new Fake(); AccountRepository repo = repository(store, fake);
        for (Object[] row : new Object[][]{{503,"qq_not_configured"},{400,"qq_invalid_token"},{401,"qq_verification_failed"},
                {502,"qq_provider_unavailable"},{504,"qq_timeout"},{429,"qq_login_busy"},{429,"qq_login_throttled"},{403,"USER_DISABLED"}}) {
            fake.answer = response((int)row[0], Map.of("error", Map.of("code", row[1], "message", QQ_TOKEN + " raw provider data")));
            try { repo.loginWithQq(QQ_TOKEN); throw new AssertionError("expected QQ failure"); }
            catch (AccountException error) {
                check(row[1].equals(error.code) && error.status == (int)row[0] && error.getMessage().matches(".*[\\u4e00-\\u9fff].*"), "QQ error remains specific and Chinese: " + row[1]);
                check(!error.getMessage().contains(QQ_TOKEN) && !error.getMessage().contains("raw provider data") && error.getCause() == null, "QQ error never exposes provider message or credential");
            }
        }
        AtomicReference<byte[]> request = new AtomicReference<>();
        fake.custom = (method, path, token, body) -> { request.set(body); throw new IOException("https://provider.test/?access_token=" + QQ_TOKEN); };
        try { repo.loginWithQq(QQ_TOKEN); throw new AssertionError("expected QQ network failure"); }
        catch (AccountException error) { check(error.code.equals("NETWORK_UNAVAILABLE") && !error.getMessage().contains(QQ_TOKEN) && error.getCause() == null, "QQ transport failure is sanitized"); }
        check(Arrays.equals(request.get(), new byte[request.get().length]) && store.bytes == null && store.writes == 0 && repo.cachedSession() == null, "failed QQ exchange clears request buffer and cannot create a session");
    }
    static void qqConcurrency() throws Exception {
        MemoryStore store = new MemoryStore(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicBoolean revoked = new AtomicBoolean();
        AccountRepository.Transport transport = (method, path, token, body) -> {
            if (path.equals("/auth/qq")) { entered.countDown(); await(release); return response(200, qqAuthentication(TOKEN_A, ID_A)); }
            if (path.equals("/auth/logout")) { revoked.set(TOKEN_A.equals(token)); return response(200, Map.of("ok", true)); }
            return response(200, authentication(TOKEN_B, ID_B));
        };
        AccountRepository repo = new AccountRepository(store, transport, () -> NOW); AtomicReference<Throwable> error = new AtomicReference<>();
        Thread pending = thread(() -> repo.loginWithQq(QQ_TOKEN), error); check(entered.await(3, TimeUnit.SECONDS), "QQ exchange pending");
        repo.login("abc", "password"); release.countDown(); join(pending);
        check(error.get() instanceof AccountException && ((AccountException)error.get()).code.equals("SESSION_CHANGED"), "newer password login supersedes pending QQ login");
        check(repo.cachedSession().user.id.equals(ID_B) && revoked.get() && !new String(store.bytes, StandardCharsets.UTF_8).contains(QQ_TOKEN), "late QQ result cannot replace newer app session and is revoked");
    }
    static void errors() throws Exception {
        Fake fake = new Fake(); AccountRepository repo = repository(new MemoryStore(), fake);
        for (Object[] row : new Object[][]{{401,"USER_LOGIN_FAILED"},{403,"USER_DISABLED"},{409,"USERNAME_TAKEN"},{429,"USER_AUTH_THROTTLED"},{503,"SERVICE_UNAVAILABLE"}}) {
            fake.answer = response((int)row[0], Map.of("error", Map.of("code", row[1], "message", TOKEN_A + " secret")));
            try { repo.login("abc", "password"); throw new AssertionError("expected failure"); }
            catch (AccountException error) { check(row[1].equals(error.code) && !error.getMessage().contains("secret") && !error.getMessage().contains(TOKEN_A), "safe mapped error " + row[0]); }
        }
        fake.answer = new AccountRepository.Response(200, new byte[65537]); failure("INVALID_RESPONSE", () -> repo.login("abc", "password"));
        fake.answer = response(200, authentication("bad", ID_A)); failure("INVALID_RESPONSE", () -> repo.login("abc", "password"));
        Map<String,Object> malformed = authentication(TOKEN_A, ID_A); malformed.put("expiresAt", "not-time"); fake.answer = response(200, malformed);
        failure("INVALID_RESPONSE", () -> repo.login("abc", "password"));
        malformed = authentication(TOKEN_A, ID_A); malformed.put("tokenType", "Basic"); fake.answer = response(200, malformed);
        failure("INVALID_RESPONSE", () -> repo.login("abc", "password"));
        malformed = authentication(TOKEN_A, ID_A); @SuppressWarnings("unchecked") Map<String,Object> badUser=(Map<String,Object>)malformed.get("user");
        badUser.put("avatarUrl", "http://unsafe.test/a"); fake.answer = response(200, malformed); failure("INVALID_RESPONSE", () -> repo.login("abc", "password"));
        failure("INVALID_RESPONSE", () -> AccountRepository.decode(new byte[]{(byte)0xc0,(byte)0xaf}));
        checkIOException(() -> AccountRepository.decode("{\"x\":1,\"x\":2}".getBytes(StandardCharsets.UTF_8)), "duplicate JSON keys");
        checkIOException(() -> AccountRepository.decode(("{\"x\":".repeat(14)+"0"+"}".repeat(14)).getBytes(StandardCharsets.UTF_8)), "nested JSON bounded");
        checkIOException(() -> AccountRepository.decode("{\"x\":\"\\ud800\"}".getBytes(StandardCharsets.UTF_8)), "invalid surrogate rejected");
    }
    static void restore() throws Exception {
        MemoryStore store = new MemoryStore(); Fake fake = new Fake(); AccountRepository repo = repository(store, fake); repo.login("abc", "password");
        AccountRepository restored = repository(store, fake); AccountRepository.Session session = restored.restore();
        check(session != null && !session.verified && session.user.id.equals(ID_A), "restored identity explicitly unverified");
        int reads = store.reads; restored.restore(); check(reads == store.reads, "restore idempotent");
        store.bytes = "corrupt".getBytes(StandardCharsets.UTF_8); failure("SESSION_STORAGE_RESET", () -> repository(store,fake).restore());
        check(store.bytes == null, "bad persisted data cleared");
        store.readFails = true; failure("SESSION_STORAGE_RESET", () -> repository(store,fake).restore());
        store.readFails = false; check(store.clears >= 2, "missing key/store error requests cleanup");
        Map<String,Object> expired = authentication(TOKEN_A, ID_A); expired.put("version",1); expired.put("expiresAt","2026-10-17T00:00:00Z"); store.bytes = json(expired);
        check(repository(store, fake).restore() == null && store.bytes == null, "expired restored token purged");
        MemoryStore fail = new MemoryStore(); fail.writeFails = true; fake.answer=response(200,authentication(TOKEN_A,ID_A));
        failure("SESSION_SAVE_FAILED", () -> repository(fail,fake).login("abc","password"));
        check("/auth/logout".equals(fake.path) && TOKEN_A.equals(fake.token), "failed persistence revokes newly issued token");
    }
    static void refresh() throws Exception {
        MemoryStore store = new MemoryStore(); Fake fake = new Fake(); AccountRepository repo = repository(store,fake); repo.login("abc","password");
        fake.failure = new IOException("sensitive raw URL/password"); failure("NETWORK_UNAVAILABLE",repo::refresh);
        check(repo.cachedSession() != null && !repo.cachedSession().verified && store.bytes != null, "offline refresh preserves unverified cache");
        fake.failure = null; fake.answer = response(200, Map.of("user",user(ID_A),"expiresAt",EXPIRY));
        check(repo.refresh().verified && TOKEN_A.equals(fake.token) && "/auth/me".equals(fake.path), "online revalidation retains private bearer");
        fake.answer = response(503,Map.of("error",Map.of("code","CLOUD_UNAVAILABLE"))); failure("SERVICE_UNAVAILABLE",repo::refresh);
        check(repo.cachedSession() != null && !repo.cachedSession().verified, "server outage preserves cache");
        fake.answer = new AccountRepository.Response(401,"not-json".getBytes(StandardCharsets.UTF_8)); failure("USER_LOGIN_REQUIRED",repo::refresh);
        check(repo.cachedSession() == null && store.bytes == null, "malformed 401 still purges credentials");
        fake.answer=response(200,authentication(TOKEN_A,ID_A));repo.login("abc","password");
        fake.answer=response(403,Map.of("error",Map.of("code","USER_DISABLED")));failure("USER_DISABLED",repo::refresh);
        check(repo.cachedSession()==null && store.bytes==null,"disabled account purges credentials");
        long[] now={NOW}; fake.answer=response(200,authentication(TOKEN_A,ID_A));AccountRepository timed=new AccountRepository(store,fake,()->now[0]);timed.login("abc","password");
        now[0]=Instant.parse(EXPIRY).toEpochMilli();check(timed.cachedSession()==null,"expired identity is not exposed by cache");failure("SESSION_EXPIRED",timed::refresh);
        check(store.bytes==null,"expired identity purged before next online action");
    }
    static void logout() throws Exception {
        MemoryStore store=new MemoryStore();Fake fake=new Fake();AccountRepository repo=repository(store,fake);repo.login("abc","password");
        fake.failure=new IOException("offline"); failure("LOGOUT_REMOTE_FAILED",repo::logout);
        check(repo.cachedSession()==null && store.bytes==null,"offline logout clears local credentials first");
        fake.failure=null;check(repo.refresh()==null,"failed remote logout never reuses local token");
        check(repository(store,fake).restore()==null,"logout survives repository recreation");
        fake.answer=response(200,authentication(TOKEN_A,ID_A));repo.login("abc","password");fake.answer=new AccountRepository.Response(401,null);repo.logout();
        check(repo.cachedSession()==null,"already expired remote logout succeeds locally");
        int count=fake.calls;repo.logout();check(fake.calls==count,"guest logout makes no remote call");
    }
    static void concurrency() throws Exception {
        MemoryStore store=new MemoryStore();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean revoked=new AtomicBoolean();
        AccountRepository.Transport transport=(method,path,token,body)->{
            if (path.equals("/auth/login")) {entered.countDown();await(release);return response(200,authentication(TOKEN_A,ID_A));}
            revoked.set(TOKEN_A.equals(token));return response(200,Map.of("ok",true));
        };
        AccountRepository repo=new AccountRepository(store,transport,()->NOW);AtomicReference<Throwable> error=new AtomicReference<>();
        Thread pending=thread(()->repo.login("abc","password"),error);check(entered.await(3,TimeUnit.SECONDS),"pending login entered transport");
        repo.logout();release.countDown();join(pending);
        check(error.get() instanceof AccountException && ((AccountException)error.get()).code.equals("SESSION_CHANGED"),"logout supersedes pending login");
        check(repo.cachedSession()==null && store.bytes==null && revoked.get(),"late login cannot restore logged-out state; new token revoked");
        MemoryStore store2=new MemoryStore();CountDownLatch first=new CountDownLatch(1),go=new CountDownLatch(1);
        AccountRepository.Transport racing=(method,path,token,body)->{
            if(path.equals("/auth/logout"))return response(200,Map.of("ok",true));
            String name=(String)AccountRepository.decode(body).get("username");
            if(name.equals("first")){first.countDown();await(go);return response(200,authentication(TOKEN_A,ID_A));}
            return response(200,authentication(TOKEN_B,ID_B));
        };
        AccountRepository repo2=new AccountRepository(store2,racing,()->NOW);AtomicReference<Throwable> oldError=new AtomicReference<>();
        Thread old=thread(()->repo2.login("first","password"),oldError);check(first.await(3,TimeUnit.SECONDS),"first login pending");
        repo2.login("second","password");go.countDown();join(old);
        check(repo2.cachedSession().user.id.equals(ID_B) && oldError.get() instanceof AccountException,"newest initiated login wins");
        Fake fake=new Fake();MemoryStore store3=new MemoryStore();AccountRepository repo3=repository(store3,fake);repo3.login("abc","password");
        CountDownLatch refreshing=new CountDownLatch(1),refreshRelease=new CountDownLatch(1);
        fake.custom=(method,path,token,body)->{if(path.equals("/auth/me")){refreshing.countDown();await(refreshRelease);return response(200,Map.of("user",user(ID_A),"expiresAt",EXPIRY));}return response(200,Map.of("ok",true));};
        AtomicReference<Throwable> refreshError=new AtomicReference<>();Thread refresher=thread(repo3::refresh,refreshError);check(refreshing.await(3,TimeUnit.SECONDS),"refresh pending");
        repo3.logout();refreshRelease.countDown();join(refresher);
        check(repo3.cachedSession()==null && store3.bytes==null && refreshError.get() instanceof AccountException,"late refresh cannot undo logout");
    }
    static void failedLocalLogout() throws Exception {
        MemoryStore store=new MemoryStore();Fake fake=new Fake();AccountRepository repo=repository(store,fake);repo.login("abc","password");
        store.clearFails=true;fake.failure=new IOException("Remote unavailable");int calls=fake.calls;
        try{repo.logout();throw new AssertionError("Expected incomplete logout");}
        catch(AccountException failure){check(failure.code.equals("SESSION_CLEAR_FAILED") && failure.getMessage().contains("服务器注销也未完成") && !failure.getMessage().contains("本机已退出"),"double cleanup failure is not reported as logged out");}
        check(fake.calls==calls+1 && "/auth/logout".equals(fake.path) && TOKEN_A.equals(fake.token),"failed local clear still attempts remote revocation");
        check(repo.isLogoutPending() && repo.cachedSession()!=null && !repo.cachedSession().verified && store.bytes!=null,"failed local clear retains retry state and unverified identity");
        int afterLogout=fake.calls;failure("SESSION_CLEAR_FAILED",repo::refresh);failure("SESSION_CLEAR_FAILED",()->repo.login("abc","password"));
        check(fake.calls==afterLogout,"pending logout cannot revalidate or replace old credentials");
        store.clearFails=false;fake.failure=null;fake.answer=new AccountRepository.Response(401,null);repo.logout();
        check(repo.cachedSession()==null && !repo.isLogoutPending() && store.bytes==null,"retry completes local cleanup even when server already revoked token");
        MemoryStore local=new MemoryStore();Fake remote=new Fake();long[]now={NOW};AccountRepository pending=new AccountRepository(local,remote,()->now[0]);pending.login("abc","password");
        local.clearFails=true;remote.answer=response(200,Map.of("ok",true));
        try{pending.logout();throw new AssertionError("Expected local cleanup failure");}
        catch(AccountException failure){check(failure.code.equals("SESSION_CLEAR_FAILED") && failure.getMessage().contains("服务器已注销"),"successful server revocation does not hide local cleanup failure");}
        now[0]=Instant.parse(EXPIRY).toEpochMilli();check(pending.cachedSession()!=null && pending.isLogoutPending(),"expired pending cleanup still exposes retry affordance");
        local.clearFails=false;pending.logout();check(pending.cachedSession()==null && !pending.isLogoutPending(),"expired cleanup can be retried");
        MemoryStore damaged=new MemoryStore();damaged.bytes="damaged encrypted store".getBytes(StandardCharsets.UTF_8);damaged.clearFails=true;
        AccountRepository recovery=repository(damaged,new Fake());failure("SESSION_CLEAR_FAILED",recovery::restore);
        check(recovery.isLogoutPending() && recovery.cachedSession()==null,"unreadable store plus failed clear remains explicit pending cleanup");
        damaged.clearFails=false;recovery.logout();check(!recovery.isLogoutPending() && damaged.bytes==null,"anonymous pending cleanup remains retryable");
    }
    static void sharedLifecycle() throws Exception {
        AccountRepositoryProvider provider=new AccountRepositoryProvider();MemoryStore store=new MemoryStore();Fake fake=new Fake();AtomicInteger constructions=new AtomicInteger();
        AccountRepositoryProvider.Factory factory=()->{constructions.incrementAndGet();return repository(store,fake);};
        AccountRepository pageA=provider.get(factory),pageB=provider.get(factory);
        check(pageA==pageB && constructions.get()==1,"all Activity account screens share one repository lifecycle");
        pageA.login("abc","password");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        fake.custom=(method,path,token,body)->{
            if(path.equals("/auth/me")){entered.countDown();await(release);return response(200,Map.of("user",user(ID_A),"expiresAt",EXPIRY));}
            throw new IOException("Offline second Activity logout");
        };
        AtomicReference<Throwable> refreshFailure=new AtomicReference<>();Thread first=thread(pageA::refresh,refreshFailure);
        check(entered.await(3,TimeUnit.SECONDS),"first Activity has an in-flight refresh");failure("LOGOUT_REMOTE_FAILED",pageB::logout);
        release.countDown();join(first);
        check(refreshFailure.get() instanceof AccountException && ((AccountException)refreshFailure.get()).code.equals("SESSION_CHANGED"),"second Activity offline logout invalidates first Activity generation");
        check(pageA.cachedSession()==null && pageB.cachedSession()==null && store.bytes==null,"late refresh across Activity instances cannot restore disk or memory token");
        CountDownLatch ready=new CountDownLatch(12),start=new CountDownLatch(1);AtomicBoolean mismatch=new AtomicBoolean();Thread[]threads=new Thread[12];
        for(int i=0;i<threads.length;i++){threads[i]=new Thread(()->{ready.countDown();try{await(start);if(provider.get(factory)!=pageA)mismatch.set(true);}catch(IOException error){mismatch.set(true);}});threads[i].start();}
        check(ready.await(3,TimeUnit.SECONDS),"concurrent provider callers are ready");start.countDown();for(Thread thread:threads)thread.join(3000);
        check(!mismatch.get() && constructions.get()==1,"shared provider constructs exactly once under concurrent Activity creation");
    }
    static void transport() throws Exception {
        AccountHttpTransport.validateRequest("POST","/auth/login",null,"{}".getBytes(StandardCharsets.UTF_8));checks++;
        AccountHttpTransport.validateRequest("POST","/auth/qq",null,json(Map.of("accessToken",QQ_TOKEN)));checks++;
        for(String path:new String[]{"https://evil.test/auth/me","/auth/me?redirect=evil","/../auth/me","/auth/me#evil","/api/users"})
            failure("INVALID_RESPONSE",()->AccountHttpTransport.validateRequest("GET",path,TOKEN_A,null));
        failure("INVALID_RESPONSE",()->AccountHttpTransport.validateRequest("GET","/auth/me",TOKEN_A+"\r\nInjected: yes",null));
        failure("INVALID_RESPONSE",()->AccountHttpTransport.validateRequest("POST","/auth/login",TOKEN_A,new byte[2]));
        failure("INVALID_RESPONSE",()->AccountHttpTransport.validateRequest("POST","/auth/qq",TOKEN_A,new byte[2]));
        failure("INVALID_RESPONSE",()->AccountHttpTransport.validateRequest("GET","/auth/qq",null,null));
        failure("INVALID_RESPONSE",()->AccountHttpTransport.validateRequest("POST","/auth/login",null,new byte[4097]));
        byte[] max=new byte[65536];check(AccountHttpTransport.readBounded(new ByteArrayInputStream(max),new AtomicBoolean()).length==65536,"HTTP response max accepted");
        failure("INVALID_RESPONSE",()->AccountHttpTransport.readBounded(new ByteArrayInputStream(new byte[65537]),new AtomicBoolean()));
        checkIOException(()->AccountHttpTransport.readBounded(new ByteArrayInputStream(new byte[1]),new AtomicBoolean(true)),"HTTP total deadline cancels read");
    }

    static AccountRepository repository(MemoryStore store,Fake fake){return new AccountRepository(store,fake,()->NOW);}
    static Map<String,Object> user(String id){Map<String,Object> map=new LinkedHashMap<>();map.put("id",id);map.put("username","photo_user");map.put("nickname","摄影用户");map.put("avatarUrl","");map.put("provider","password");map.put("status","active");map.put("createdAt","2026-10-18T00:00:00.123456+00:00");map.put("lastLoginAt","2026-10-18T00:00:00Z");map.put("updatedAt","2026-10-18T00:00:00Z");map.put("loginCount",1);return map;}
    static Map<String,Object> authentication(String token,String id){Map<String,Object> map=new LinkedHashMap<>();map.put("tokenType","Bearer");map.put("accessToken",token);map.put("expiresAt",EXPIRY);map.put("user",user(id));return map;}
    static Map<String,Object> qqAuthentication(String token,String id){Map<String,Object> map=authentication(token,id);@SuppressWarnings("unchecked") Map<String,Object> user=(Map<String,Object>)map.get("user");user.put("provider","qq");user.put("username","");return map;}
    static byte[] json(Map<String,Object> value){return AccountJson.stringify(value).getBytes(StandardCharsets.UTF_8);}
    static AccountRepository.Response response(int status,Map<String,Object>value){return new AccountRepository.Response(status,json(value));}
    interface Work{void run()throws Exception;}
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    static void failure(String code,Work work)throws Exception{try{work.run();throw new AssertionError("Expected "+code);}catch(AccountException e){check(code.equals(e.code),"error code expected "+code+", actual "+e.code);}}
    static void checkIOException(Work work,String label)throws Exception{try{work.run();throw new AssertionError(label);}catch(IOException e){checks++;}}
    static Thread thread(Work work,AtomicReference<Throwable>error){Thread t=new Thread(()->{try{work.run();}catch(Throwable e){error.set(e);}});t.start();return t;}
    static void await(CountDownLatch latch)throws IOException{try{if(!latch.await(5,TimeUnit.SECONDS))throw new IOException("Test latch timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Interrupted");}}
    static void join(Thread thread)throws Exception{thread.join(6000);check(!thread.isAlive(),"concurrent worker completed");}
    static final class MemoryStore implements AccountRepository.Store{
        byte[]bytes;int reads,writes,clears;boolean readFails,writeFails,clearFails;
        public synchronized byte[]read()throws IOException{reads++;if(readFails)throw new IOException("Key unavailable");return bytes==null?null:bytes.clone();}
        public synchronized void write(byte[]value)throws IOException{writes++;if(writeFails)throw new IOException("Disk full");bytes=value.clone();}
        public synchronized void clear()throws IOException{clears++;if(clearFails)throw new IOException("Unable to remove both encrypted file and key");if(bytes!=null)Arrays.fill(bytes,(byte)0);bytes=null;}
    }
    static final class Fake implements AccountRepository.Transport{
        AccountRepository.Response answer=response(200,authentication(TOKEN_A,ID_A));IOException failure;String method,path,token;byte[]body;int calls;AccountRepository.Transport custom;
        public AccountRepository.Response request(String method,String path,String token,byte[]body)throws IOException{this.method=method;this.path=path;this.token=token;this.body=body==null?null:body.clone();calls++;if(custom!=null)return custom.request(method,path,token,body);if(failure!=null)throw failure;return answer;}
    }
}

package cc.huevia.community;

import cc.huevia.account.AccountJson;
import cc.huevia.account.AccountRepository;
import cc.huevia.account.AccountException;
import cc.huevia.library.OnlinePreset;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static cc.huevia.community.CommunityRepository.*;

/** Host contract tests: no network, no Android UI and no production files. */
public final class CommunityRepositoryTest {
    private static int checks;
    private static final String USER="19bd1434-e597-4f27-808d-9b86eb8d983d", OTHER="42ee04c3-b4de-4992-8e22-6ec528b01d92", TOKEN="A".repeat(43);
    private static final class Memory implements AccountRepository.Store {
        byte[] value; public byte[] read(){return value;} public void write(byte[] bytes){value=bytes.clone();} public void clear(){value=null;}
    }
    private static final class Fake implements AccountRepository.Transport {
        int calls,status=200;String path,method,token;byte[] sent;Map<String,Object> answer=body("items",List.of());boolean fail;
        public AccountRepository.Response request(String method,String path,String token,byte[] bytes)throws IOException{
            calls++;this.method=method;this.path=path;this.token=token;this.sent=bytes==null?null:bytes.clone();if(fail)throw new IOException("offline");
            return new AccountRepository.Response(status,AccountJson.stringify(answer).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static AccountRepository accounts(Memory memory)throws Exception{
        return accounts(memory,USER);
    }
    private static AccountRepository accounts(Memory memory,String userId)throws Exception{
        Fake auth=new Fake();auth.answer=body("tokenType","Bearer","accessToken",TOKEN,"expiresAt","2026-10-25T00:00:00Z","user",body("id",USER,"username","huevia","nickname","Huevia","avatarUrl","","provider","password","status","active","createdAt","2026-10-18T00:00:00Z","lastLoginAt","2026-10-18T00:00:00Z","updatedAt","2026-10-18T00:00:00Z","loginCount",1));
        @SuppressWarnings("unchecked") Map<String,Object> author=(Map<String,Object>)auth.answer.get("user");author.put("id",userId);
        AccountRepository repo=new AccountRepository(memory,auth,()->Instant.parse("2026-10-18T00:00:00Z").toEpochMilli());repo.login("huevia","password123");return repo;
    }
    public static void main(String[] args)throws Exception{
        Memory memory=new Memory();Fake transport=new Fake();AccountRepository guest=new AccountRepository(memory,transport);
        CommunityRepository api=new CommunityRepository(guest,transport);api.get("/community/feed?page=1&pageSize=20",false);
        check(transport.token==null&&transport.calls==1,"guest public read carries no admin credentials");
        try{api.write("POST","/community/submissions",body());throw new AssertionError();}catch(AccountException expected){check(expected.status==401&&transport.calls==1,"guest write rejected before transport");}
        AccountRepository repo=accounts(memory);transport=new Fake();api=new CommunityRepository(repo,transport);
        Map<String,Object> write=body("text","回复","idempotencyKey","a-stable-key");transport.answer=body("comment",body("id",OTHER));api.write("POST","/community/presets/"+OTHER+"/comments",write);
        check(TOKEN.equals(transport.token)&&transport.method.equals("POST"),"uses existing account bearer for write");
        Map<String,Object> sent=object(AccountJson.parse(new String(transport.sent,StandardCharsets.UTF_8)));
        check("a-stable-key".equals(sent.get("idempotencyKey"))&&"回复".equals(sent.get("text")),"write retains explicit idempotency key and Unicode");
        api.writeForOwner("POST","/community/presets/"+OTHER+"/comments",write,USER);
        check(TOKEN.equals(transport.token),"author-bound write uses the selected account bearer");
        int ownerCalls=transport.calls;
        try{api.writeForOwner("POST","/community/presets/"+OTHER+"/comments",write,OTHER);throw new AssertionError();}
        catch(AccountException expected){check(expected.code.equals("SESSION_CHANGED")&&transport.calls==ownerCalls,"wrong author rejected atomically before transport");}
        Fake secondTransport=new Fake();CommunityRepository switched=new CommunityRepository(accounts(new Memory(),OTHER),secondTransport);
        try{switched.writeForOwner("POST","/community/presets/"+OTHER+"/comments",write,USER);throw new AssertionError();}
        catch(AccountException expected){check(expected.code.equals("SESSION_CHANGED")&&secondTransport.calls==0,"queued old-author write never sends as new account");}
        transport.fail=true;int before=transport.calls;try{api.write("POST","/community/submissions",write);throw new AssertionError();}catch(IOException expected){}
        check(transport.calls==before+1,"failed write never auto retries");transport.fail=false;
        transport.status=403;transport.answer=body("error",body("code","RESOURCE_FORBIDDEN","message","此投稿不属于你。"));
        try{api.write("PUT","/community/submissions/"+OTHER,write);throw new AssertionError();}catch(CommunityException expected){check(expected.status==403,"resource denial exposed");}
        check(repo.cachedSession()!=null&&memory.value!=null,"resource forbidden does not clear user identity");
        transport.status=429;transport.answer=body("error",body("code","ABUSE_THROTTLED","message","provider-secret"));try{api.write("POST","/community/submissions",write);throw new AssertionError();}catch(CommunityException expected){check(!expected.getMessage().contains("secret"),"rate denial uses bounded product copy");}
        transport.status=403;transport.answer=body("error",body("code","USER_DISABLED","message","账号已停用"));try{api.write("POST","/community/submissions",write);throw new AssertionError();}catch(CommunityException expected){}
        check(repo.cachedSession()==null&&memory.value==null,"explicit disabled clears existing identity");
        repo=accounts(memory);transport=new Fake();transport.status=401;transport.answer=body("error",body("code","USER_LOGIN_REQUIRED","message","expired"));api=new CommunityRepository(repo,transport);
        try{api.write("POST","/community/comments/"+OTHER+"/report",write);throw new AssertionError();}catch(CommunityException expected){}
        check(repo.cachedSession()==null&&memory.value==null,"authoritative unauthorized clears session");
        repo=accounts(memory);AccountRepository.Transport broken=(m,p,t,b)->new AccountRepository.Response(200,new byte[]{(byte)0xc3,(byte)0x28});api=new CommunityRepository(repo,broken);
        try{api.get("/community/feed",false);throw new AssertionError();}catch(CommunityException expected){check(expected.code.equals("INVALID_RESPONSE"),"invalid UTF8 rejected");}
        Map<String,Object> published=body("id",OTHER,"name","胶片","description","test","category","胶片","fileUrl","https://example.test/preset.ncp","sha256","a".repeat(64),"byteLength",8,"previewUrl","","compatibleModels",List.of("Z 6II"),"author",body("id",USER,"nickname","摄影师"),"source","community");
        OnlinePreset item=preset(object(AccountJson.parse(AccountJson.stringify(published))));check(item.authorName.equals("摄影师")&&item.source.equals("community"),"nested feed author parsed without losing file verification fields");
        published.remove("author");published.remove("source");item=preset(object(AccountJson.parse(AccountJson.stringify(published))));check(item.authorName.isEmpty()&&item.source.isEmpty(),"legacy manifest remains compatible");
        File scratch=Files.createTempDirectory("huevia-community-").toFile();SubmissionDraftStore store=new SubmissionDraftStore(scratch);Map<String,Object> draft=SubmissionDraftStore.fresh();draft.put("name","草稿");
        File preset=store.storeFile(USER,"ncp",new byte[]{1,2,3});draft.put("filePath",preset.getAbsolutePath());store.save(USER,draft);Map<String,Object> restored=store.load(USER);
        check(restored.get("name").equals("草稿")&&restored.get("createKey").equals(draft.get("createKey")),"draft and idempotency key persist on restart");
        check(store.load(OTHER).get("name")==null,"draft private to owning account");check(Arrays.equals(store.readFile(preset.getAbsolutePath(),3),new byte[]{1,2,3}),"draft selected file remains readable");
        try{store.readFile(new File(scratch,"escape.ncp").getAbsolutePath(),16);throw new AssertionError();}catch(IOException expected){check(true,"draft path escape blocked");}
        try{store.storeFile(USER,"ncp",new byte[1048577]);throw new AssertionError();}catch(IOException expected){check(true,"NCP upload cap enforced before write");}
        try{store.storeFile(USER,"preview",new byte[4194305]);throw new AssertionError();}catch(IOException expected){check(true,"preview upload cap enforced before write");}
        check(!Files.readString(new File(new File(scratch,"community-drafts"),USER+".json").toPath()).contains(TOKEN),"draft contains no session token");
        try{id("../../admin");throw new AssertionError();}catch(IOException expected){check(true,"resource identifiers cannot change routes");}
        submissionPhases(store,memory);
        System.out.println("Community repository/draft contract: "+checks+" checks passed (host-only).");
    }
    private static final class Workflow implements AccountRepository.Transport {
        int saves,submits,revision;boolean loseSubmit;String lostKey;Map<String,Object> saved;final Map<String,String> requests=new LinkedHashMap<>();
        public AccountRepository.Response request(String method,String path,String token,byte[] bytes)throws IOException {
            Map<String,Object> request=object(AccountJson.parse(new String(bytes,StandardCharsets.UTF_8)));String key=text(request,"idempotencyKey");String previous=requests.putIfAbsent(key,AccountJson.stringify(request));
            if(previous!=null&&!previous.equals(AccountJson.stringify(request)))throw new IOException("Idempotency conflict");
            if(path.endsWith("/submit")){submits++;if(lostKey!=null&&lostKey.equals(key))return answer();if(number(request,"expectedRevision",-1)!=revision)throw new IOException("Submit revision conflict");saved.put("status","pending");if(loseSubmit){loseSubmit=false;lostKey=key;throw new IOException("Server committed then connection closed");}return answer();}
            if("PUT".equals(method)&&number(request,"expectedRevision",-1)!=revision)throw new IOException("Save revision conflict");
            saves++;revision++;saved=new LinkedHashMap<>(request);saved.put("id",OTHER);saved.put("revision",revision);saved.put("status","draft");return answer();
        }
        private AccountRepository.Response answer(){return new AccountRepository.Response(200,AccountJson.stringify(body("submission",saved)).getBytes(StandardCharsets.UTF_8));}
    }
    private static void submissionPhases(SubmissionDraftStore store,Memory memory)throws Exception {
        Workflow backend=new Workflow();CommunityRepository api=new CommunityRepository(accounts(memory),backend);Map<String,Object> draft=SubmissionDraftStore.fresh();draft.putAll(body("name","测试","description","","category","","compatibleModels",List.of(),"fileAssetId","ncp-"+"a".repeat(64),"rightsConfirmed",true));
        String originalKey=text(draft,"saveKey");Map<String,Object> first=SubmissionFlow.save(api,store,USER,draft,false);
        check(backend.saves==1&&backend.submits==0&&text(first,"id").equals(OTHER),"save-only creates server draft once");
        check(!originalKey.equals(text(first,"saveKey")),"acknowledged save rotates next operation key");
        Map<String,Object> completed=SubmissionFlow.save(api,store,USER,first,true);
        check(backend.saves==2&&backend.submits==1&&text(completed,"status").equals("pending"),"save then submit uses current revision and distinct save key");
        check(Boolean.FALSE.equals(completed.get("submitPending")),"acknowledged submit clears durable pending marker");
        Workflow lost=new Workflow();lost.loseSubmit=true;api=new CommunityRepository(accounts(memory),lost);draft=SubmissionDraftStore.fresh();draft.putAll(body("name","测试响应丢失","description","","category","","compatibleModels",List.of(),"fileAssetId","ncp-"+"b".repeat(64),"rightsConfirmed",true));
        try{SubmissionFlow.save(api,store,USER,draft,true);throw new AssertionError();}catch(IOException expected){check(lost.saves==1&&lost.submits==1,"lost submit response does not auto retry");}
        Map<String,Object> checkpoint=store.load(USER);String pendingKey=text(checkpoint,"submitKey");check(Boolean.TRUE.equals(checkpoint.get("submitPending"))&&number(checkpoint,"revision",0)==1,"saved checkpoint retains exact submit revision");
        completed=SubmissionFlow.save(api,store,USER,checkpoint,true);
        check(lost.saves==1&&lost.submits==2&&lost.lostKey.equals(pendingKey),"explicit retry reuses submit key without another save");
        check(text(completed,"status").equals("pending")&&!Boolean.TRUE.equals(completed.get("submitPending")),"lost acknowledgement recovers to pending state");
        Map<String,Object> denied=SubmissionDraftStore.fresh();denied.put("rightsConfirmed",false);int calls=lost.saves+lost.submits;try{SubmissionFlow.save(api,store,USER,denied,true);throw new AssertionError();}catch(IOException expected){check(lost.saves+lost.submits==calls,"rights confirmation checked before transmission");}
    }
    private static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;}
}

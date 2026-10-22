package cc.huevia.account;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import static cc.huevia.account.AccountRepositoryTest.*;

/** Challenge contract tests use the existing account identity and no external network. */
public final class AccountCommunityAuthTest {
    private static int passed;
    public static void main(String[] args)throws Exception {
        MemoryStore store=new MemoryStore();Fake fake=new Fake();AccountRepository repo=repository(store,fake);
        fake.answer=response(200,Map.of("id",ID_A,"image","data:image/png;base64,AAAA","expiresAt","2026-10-18T00:02:00Z"));
        Map<String,Object> proof=repo.challenge(ID_B);require(fake.path.equals("/auth/challenge?installationId="+ID_B)&&fake.method.equals("GET")&&fake.token==null&&fake.body==null,"challenge only fixed public route");
        require(ID_A.equals(proof.get("id")),"challenge returned to UI without creating session");
        int before=fake.calls;try{repo.challenge("../bad");throw new AssertionError();}catch(java.io.IOException expected){require(fake.calls==before,"invalid installation rejected before transport");}
        fake.answer=response(201,authentication(TOKEN_A,ID_A));AtomicReference<byte[]> actual=new AtomicReference<>();fake.custom=(m,p,t,b)->{actual.set(b);return response(201,authentication(TOKEN_A,ID_A));};
        repo.register("Photo_User","password123",ID_A,"A2B3C",ID_B);Map<String,Object> payload=AccountRepository.decode(fake.body);
        require(payload.get("username").equals("photo_user")&&payload.get("challengeId").equals(ID_A)&&payload.get("challengeAnswer").equals("A2B3C")&&payload.get("installationId").equals(ID_B),"register binds challenge and random installation with normalized username");
        require(java.util.Arrays.equals(actual.get(),new byte[actual.get().length]),"challenge and password request buffer wiped");
        String persisted=new String(store.bytes,StandardCharsets.UTF_8);require(!persisted.contains("A2B3C")&&!persisted.contains("challengeId")&&!persisted.contains("password123"),"challenge proof and password never persisted");
        for(String id:new String[]{null,"not-id","../../admin"})try{repo.register("valid","password123",id,"A2B3C",ID_B);throw new AssertionError();}catch(AccountException expected){require(expected.code.equals("CHALLENGE_REQUIRED"),"invalid challenge rejected");}
        fake.custom=null;fake.answer=response(428,Map.of("error",Map.of("code","CHALLENGE_REQUIRED","message",TOKEN_A+"provider secret")));
        try{repo.loginWithQq(QQ_TOKEN);throw new AssertionError();}catch(AccountException expected){require(expected.status==428&&expected.code.equals("CHALLENGE_REQUIRED")&&!expected.getMessage().contains("secret"),"QQ new-account requirement retained as safe distinct error");}
        fake.answer=response(200,qqAuthentication(TOKEN_B,ID_B));repo.loginWithQq(QQ_TOKEN,ID_A,"C3D4E",ID_B);payload=AccountRepository.decode(fake.body);
        require(QQ_TOKEN.equals(payload.get("accessToken"))&&"C3D4E".equals(payload.get("challengeAnswer")),"QQ retries credential with proof, no fabricated openid");
        persisted=new String(store.bytes,StandardCharsets.UTF_8);require(!persisted.contains(QQ_TOKEN)&&!persisted.contains("C3D4E"),"only independent App session persists for QQ");
        for(String code:new String[]{"CHALLENGE_INVALID","CHALLENGE_EXPIRED"}){fake.answer=response(400,Map.of("error",Map.of("code",code,"message","unsafe provider detail")));try{repo.register("valid","password123",ID_A,"A2B3C",ID_B);throw new AssertionError();}catch(AccountException expected){require(expected.code.equals(code)&&!expected.getMessage().contains("provider"),"challenge denial mapped safely");}}
        AccountHttpTransport.validateRequest("GET","/auth/challenge?installationId="+ID_B,null,null);require(true,"challenge GET transport allowed");
        for(String path:new String[]{"/auth/challenge?installationId=not-id","/auth/challenge?installationId="+ID_B+"&next=https://evil.test","/auth/challenge?installationId="+ID_B+"#x"})try{AccountHttpTransport.validateRequest("GET",path,null,null);throw new AssertionError();}catch(AccountException expected){require(expected.code.equals("INVALID_RESPONSE"),"challenge routing injection blocked");}
        System.out.println("Account community challenge contract: "+passed+" checks passed (host-only).");
    }
    private static void require(boolean value,String label){if(!value)throw new AssertionError(label);passed++;}
}

package cc.huevia.account;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Explicit opt-in real HTTPS probe. Credentials arrive only via bounded stdin, never command arguments. */
public final class AccountLiveProbe {
    public static void main(String[] args) {
        MemoryStore store = new MemoryStore(); AccountRepository repository = null;
        byte[] input = null;
        boolean failed = false;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int n;
            while ((n = System.in.read(buffer)) != -1) {
                if (n == 0 || n > 8192 - bytes.size()) throw new IOException("Invalid probe input size");
                bytes.write(buffer, 0, n);
            }
            input = bytes.toByteArray(); Map<String,Object> request = AccountRepository.decode(input);
            String username = AccountJson.string(request, "username", true, 32);
            // Password controls/spaces are valid; do not use the metadata string validator.
            Object passwordValue = request.get("password");
            if (!(passwordValue instanceof String)) throw new IOException("Missing probe password");
            String password = (String) passwordValue;
            boolean register = "register".equals(request.get("action"));
            RecordingTransport transport = new RecordingTransport(); repository = new AccountRepository(store, transport);
            AccountRepository.Session first = register ? repository.register(username, password) : repository.login(username, password);
            require(first != null && first.verified); int checks = 1;
            String userId = first.user.id;
            require(repository.refresh().user.id.equals(userId)); checks++;
            String replayToken = transport.lastBearer;
            AccountRepository recovered = new AccountRepository(store, transport);
            require(recovered.restore() != null && !recovered.cachedSession().verified); checks++;
            require(recovered.refresh().verified); checks++;
            repository.logout(); require(repository.cachedSession() == null && store.bytes == null); checks++;
            require(transport.delegate.request("GET", "/auth/me", replayToken, null).status == 401); checks++;
            require(new AccountRepository(store,transport).restore() == null); checks++;
            AccountRepository.Session second = repository.login(username,password);
            require(second.user.id.equals(userId) && second.verified); checks++;
            repository.logout(); require(repository.cachedSession() == null); checks++;
            Map<String,Object> report = new LinkedHashMap<>(); report.put("passed",true);report.put("checks",checks);
            report.put("userId",userId);report.put("username",second.user.username);report.put("transport","production Android account HTTPS client on host JDK");
            System.out.println(AccountJson.stringify(report));
            request.clear();
        } catch (Throwable error) {
            String code = error instanceof AccountException ? ((AccountException)error).code : "PROBE_FAILED";
            System.err.println("Account live probe failed: " + code + ". Credentials and server bodies are omitted.");
            failed = true;
        } finally {
            if (input != null) Arrays.fill(input,(byte)0);
            if (repository != null) try { repository.logout(); } catch (IOException ignored) { }
            store.clear();
        }
        if (failed) System.exit(1);
    }
    private static void require(boolean condition) throws IOException { if(!condition) throw new IOException("Probe assertion failed"); }
    private static final class MemoryStore implements AccountRepository.Store {
        byte[] bytes;
        public synchronized byte[] read(){return bytes==null?null:bytes.clone();}
        public synchronized void write(byte[]value){bytes=value.clone();}
        public synchronized void clear(){if(bytes!=null)Arrays.fill(bytes,(byte)0);bytes=null;}
    }
    private static final class RecordingTransport implements AccountRepository.Transport {
        final AccountHttpTransport delegate = new AccountHttpTransport(); String lastBearer;
        public AccountRepository.Response request(String method,String path,String token,byte[]body)throws IOException {
            if(token!=null) lastBearer=token;
            return delegate.request(method,path,token,body);
        }
    }
}

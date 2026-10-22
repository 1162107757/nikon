package cc.huevia.library;
import java.io.*;
import java.net.URL;
import java.security.cert.Certificate;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import cc.huevia.account.AccountJson;

/** Public feed paging contract; no internet, image decoding or camera access. */
public final class CommunityPagingTest {
    static int checks;static Map<String,Object> answer;static String address;static int calls;
    public static void main(String[] args)throws Exception{
        File directory=Files.createTempDirectory("huevia-paging-").toFile();
        SecureHttpClient http=new SecureHttpClient(url->{address=url.toString();calls++;return new Mock(url,AccountJson.stringify(answer).getBytes(StandardCharsets.UTF_8));});
        OnlinePresetRepository repository=new OnlinePresetRepository(directory,new PresetLibrary(directory),http);
        try{repository.fetchPage(1,20,"");throw new AssertionError();}catch(IOException expected){check(calls==0,"unmanaged endpoint cannot be expanded into community routes");}
        repository.useManagedEndpoint("https://managed.example.test/preset-library/catalog.json");
        Map<String,Object> preset=new LinkedHashMap<>(Map.of("id","19bd1434-e597-4f27-808d-9b86eb8d983d","name","胶片","description","","category","胶片","sha256","a".repeat(64),"byteLength",8,"fileUrl","https://managed.example.test/file.ncp","previewUrl","","compatibleModels",List.of()));
        preset.put("author",Map.of("id","42ee04c3-b4de-4992-8e22-6ec528b01d92","nickname","摄影师","avatarUrl",""));preset.put("source","community");
        answer=new LinkedHashMap<>(Map.of("items",List.of(preset),"page",1,"pageSize",20,"total",41,"hasMore",true,"categories",List.of("","胶片","人像")));
        OnlinePresetRepository.PageResult result=repository.fetchPage(1,20,"胶片");check(result.presets.size()==1&&result.total==41&&result.hasMore,"first page does not impose legacy256 total cap");
        check(result.categories.equals(List.of("","胶片","人像")),"global categories retained including uncategorized");check(result.presets.get(0).authorName.equals("摄影师"),"feed attribution retained");
        check(address.equals("https://managed.example.test/preset-library/community/feed?page=1&pageSize=20&category=%E8%83%B6%E7%89%87"),"fixed product page URL and escaped category");
        repository.fetchPage(1,20,"__uncategorized__");check(address.endsWith("category=__uncategorized__"),"uncategorized sentinel preserved");repository.fetchPage(1,20,"");check(address.endsWith("category="),"empty category means all");
        int before=calls;for(int[] bad:new int[][]{{0,20},{1,0},{1,101},{100001,20}})try{repository.fetchPage(bad[0],bad[1],"");throw new AssertionError();}catch(IOException expected){check(calls==before,"invalid page rejected before HTTP");}
        for(String key:new String[]{"total","page","pageSize"}){Object previous=answer.put(key,-1);try{repository.fetchPage(1,20,"");throw new AssertionError();}catch(IOException expected){check(true,"page bounds validated");}answer.put(key,previous);}
        Object previous=answer.put("items",List.of(preset,preset));try{repository.fetchPage(1,20,"");throw new AssertionError();}catch(IOException expected){check(true,"duplicate row IDs rejected");}answer.put("items",previous);
        previous=answer.put("hasMore","true");try{repository.fetchPage(1,20,"");throw new AssertionError();}catch(IOException expected){check(true,"hasMore strict boolean");}answer.put("hasMore",previous);
        previous=answer.put("categories",List.of("全部"));try{repository.fetchPage(1,20,"");throw new AssertionError();}catch(IOException expected){check(true,"reserved category cannot become real category");}answer.put("categories",previous);
        System.out.println("Community paging: "+checks+" checks passed (host-only).");
    }
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    static final class Mock extends HttpsURLConnection{
        final byte[] bytes;Mock(URL url,byte[] bytes){super(url);this.bytes=bytes;}
        public int getResponseCode(){return 200;}public long getContentLengthLong(){return bytes.length;}public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}public String getHeaderField(String key){return null;}
        public void disconnect(){}public void connect(){}public boolean usingProxy(){return false;}public String getCipherSuite(){return"TEST";}public Certificate[] getLocalCertificates(){return new Certificate[0];}public Certificate[] getServerCertificates(){return new Certificate[0];}
    }
}

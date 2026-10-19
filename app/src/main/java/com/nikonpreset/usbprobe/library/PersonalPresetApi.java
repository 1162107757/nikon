package com.nikonpreset.usbprobe.library;
import com.nikonpreset.usbprobe.account.*;
import com.nikonpreset.usbprobe.community.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Owner-pinned requests. No file URL is public and no automatic network rebinding occurs. */
public final class PersonalPresetApi implements PersonalPresetSync.Backend {
    private final AccountRepository accounts;private final AccountRepository.Transport transport;
    public PersonalPresetApi(AccountRepository accounts,String installation){this.accounts=accounts;CommunityHttpTransport http=new CommunityHttpTransport("application/json; charset=utf-8",true);http.setInstallationId(installation);transport=http;}
    public PersonalPresetApi(AccountRepository accounts,AccountRepository.Transport transport){this.accounts=accounts;this.transport=transport;}
    private Map<String,Object> request(String owner,String method,String path,Map<String,Object> body)throws IOException {
        AccountRepository.Response r=accounts.requestWithSession(transport,method,path,body==null?null:AccountJson.stringify(body).getBytes(StandardCharsets.UTF_8),true,owner);
        if(r.status<200||r.status>=300){if(r.status==409)throw new IOException("云端版本已更新，请重新同步后处理差异。");if(r.status==401)throw new IOException("请重新登录后同步，离线副本仍可使用。");if(r.status==429)throw new IOException("同步次数较多，请稍后再试。");throw new IOException("云端同步暂未完成，已保留本机预设，请稍后重试。");}
        if(r.body.length>2*1024*1024)throw new IOException("云端响应过大。");return AccountJson.object(AccountJson.parse(LibraryFiles.utf8(r.body)));
    }
    public List<Map<String,Object>> list(String owner,String after)throws IOException {Map<String,Object> result=request(owner,"GET","/personal/presets?after="+after,null);Object rows=result.get("items");if(!(rows instanceof List)||((List<?>)rows).size()>100)throw new IOException("云端列表无效。");List<Map<String,Object>> output=new ArrayList<>();for(Object row:(List<?>)rows)output.add(AccountJson.object(row));return output;}
    public byte[] download(String owner,String hash)throws IOException {Map<String,Object> r=request(owner,"GET","/personal/presets/"+LibraryFiles.requireHash(hash),null);Object encoded=r.get("fileBase64");if(!(encoded instanceof String)||((String)encoded).length()>1398104)throw new IOException("云端文件不完整。");try {byte[] bytes=Base64.getDecoder().decode((String)encoded);if(bytes.length>1048576||!LibraryFiles.sha256(bytes).equals(hash))throw new IOException("云端文件校验失败。");return bytes;}catch(IllegalArgumentException e){throw new IOException("云端文件无效。");}}
    public long put(String owner,String hash,Map<String,Object> metadata,byte[] bytes,long revision)throws IOException {Map<String,Object> body=new LinkedHashMap<>(metadata);body.put("expectedRevision",revision);body.put("fileBase64",Base64.getEncoder().encodeToString(bytes));return AccountJson.integer(request(owner,"PUT","/personal/presets/"+LibraryFiles.requireHash(hash),body),"revision");}
    public void delete(String owner,String hash,long revision)throws IOException {Map<String,Object> body=new LinkedHashMap<>();body.put("expectedRevision",revision);request(owner,"DELETE","/personal/presets/"+LibraryFiles.requireHash(hash),body);}
}

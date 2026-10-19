package com.nikonpreset.usbprobe.library;

import com.nikonpreset.usbprobe.account.AccountJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** One serial worker owns mutations. Immutable visibility snapshots are safe for the UI. */
public final class PersonalPresetSync {
    public interface Backend {
        List<Map<String,Object>> list(String owner,String after)throws IOException;
        byte[] download(String owner,String hash)throws IOException;
        long put(String owner,String hash,Map<String,Object> metadata,byte[] bytes,long revision)throws IOException;
        void delete(String owner,String hash,long revision)throws IOException;
    }
    public interface Progress { void update(int completed,int total); }
    private final File file;private final PresetLibrary library;
    private final Map<String,Object> accounts;private volatile Map<String,Set<String>> claimed=Map.of(),hidden=Map.of();
    public PersonalPresetSync(File files,PresetLibrary library)throws IOException {
        this.library=library;file=new File(files,"personal-presets.json");
        accounts=file.exists()?AccountJson.object(AccountJson.parse(LibraryFiles.utf8(LibraryFiles.read(file,8*1024*1024)))):new LinkedHashMap<>();publish();
    }
    private static String owner(String value)throws IOException {if(value==null||!value.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IOException("请先登录账号。");return value;}
    @SuppressWarnings("unchecked") private Map<String,Object> account(String id)throws IOException {owner(id);Object found=accounts.get(id);if(found==null){found=new LinkedHashMap<String,Object>();accounts.put(id,found);}return AccountJson.object(found);}
    private Map<String,Object> entries(String id)throws IOException {Map<String,Object> a=account(id);if(!a.containsKey("entries"))a.put("entries",new LinkedHashMap<>());return AccountJson.object(a.get("entries"));}
    private Map<String,Object> entry(String id,String hash)throws IOException {LibraryFiles.requireHash(hash);Map<String,Object> e=entries(id);if(!e.containsKey(hash))e.put(hash,new LinkedHashMap<>());return AccountJson.object(e.get(hash));}
    private static long number(Map<String,Object> m,String k,long fallback){Object v=m.get(k);if(v instanceof Long||v instanceof Integer)return ((Number)v).longValue();try{return AccountJson.integer(m,k);}catch(IOException bad){return fallback;}}
    public boolean visible(String id,String hash){Set<String> owners=claimed.get(hash);return owners==null||owners.isEmpty()||(!id.isEmpty()&&owners.contains(id)&&!hidden.getOrDefault(id,Set.of()).contains(hash));}
    public List<PresetRecord> unclaimed(){List<PresetRecord> result=new ArrayList<>();for(PresetRecord r:library.list())if(!claimed.containsKey(r.id))result.add(r);return result;}
    public boolean consentNeeded(String id)throws IOException{return !Boolean.TRUE.equals(account(id).get("consent"))&&!unclaimed().isEmpty();}
    public void consent(String id,boolean include)throws IOException {account(id).put("consent",true);if(include)for(PresetRecord r:unclaimed())markAdded(id,r);save();}
    public void markAdded(String id,PresetRecord record)throws IOException {Map<String,Object> e=entry(id,record.id);e.put("metadata",metadata(record));e.put("dirty",true);e.put("deleted",false);e.remove("conflictRevision");save();}
    public void markDeleted(String id,String hash)throws IOException {Map<String,Object> e=entry(id,hash);e.put("dirty",true);e.put("deleted",true);save();}
    public int conflicts(String id)throws IOException {int count=0;for(Object v:entries(id).values())if(AccountJson.object(v).containsKey("conflictRevision"))count++;return count;}
    public void resolveConflicts(String id,boolean keepLocal)throws IOException {for(Object v:entries(id).values()){Map<String,Object> e=AccountJson.object(v);if(e.containsKey("conflictRevision")){e.put("revision",number(e,"conflictRevision",0));e.remove("conflictRevision");if(!keepLocal){e.put("dirty",false);e.put("revision",-1L);e.put("deleted",false);e.put("restoreRemote",true);}}}save();}
    public void restoreCachedMetadata(String id)throws IOException {for(var item:entries(id).entrySet()){Map<String,Object> e=AccountJson.object(item.getValue());if(!Boolean.TRUE.equals(e.get("deleted"))&&e.get("metadata") instanceof Map&&library.findBySha256(item.getKey())!=null)library.updatePersonalMetadata(item.getKey(),AccountJson.object(e.get("metadata")));}}
    public void sync(String id,Backend api,Progress progress)throws IOException {
        owner(id);
        // A committed local deletion survives a process death before its UI callback queued sync.
        for(var item:entries(id).entrySet())if(library.wasRemoved(item.getKey())){Map<String,Object> e=AccountJson.object(item.getValue());if(!Boolean.TRUE.equals(e.get("deleted"))&&!Boolean.TRUE.equals(e.get("restoreRemote"))){e.put("deleted",true);e.put("dirty",true);}}
        save();Map<String,Map<String,Object>> remote=new LinkedHashMap<>();String after="";
        for(int page=0;page<51;page++){
            List<Map<String,Object>> rows=api.list(id,after);if(rows.size()>100)throw new IOException("云端列表不完整。");
            for(Map<String,Object> row:rows){String hash=LibraryFiles.requireHash(string(row,"sha256"));if(hash.compareTo(after)<=0||remote.put(hash,row)!=null)throw new IOException("云端分页无效。");after=hash;}
            if(rows.size()<100)break;if(page==50)throw new IOException("云端预设数量超出本版上限。");
        }
        Set<String> union=new HashSet<>(remote.keySet());union.addAll(entries(id).keySet());
        int done=0,total=remote.size()+union.size();progress.update(done,total);
        for(var item:remote.entrySet()){
            String hash=item.getKey();Map<String,Object> row=item.getValue(),e=entry(id,hash);long revision=number(row,"revision",-1);if(revision<1)throw new IOException("云端版本无效。");
            if(Boolean.TRUE.equals(e.get("dirty"))){
                boolean sameDeletion=Boolean.TRUE.equals(e.get("deleted"))&&Boolean.TRUE.equals(row.get("deleted"));
                boolean sameContent=!Boolean.TRUE.equals(e.get("deleted"))&&!Boolean.TRUE.equals(row.get("deleted"))&&sameMetadata(row,e.get("metadata"));
                if(sameDeletion||sameContent){e.put("revision",revision);e.put("dirty",false);e.remove("conflictRevision");}
                else if(number(e,"revision",0)!=revision)e.put("conflictRevision",revision);
            }
            else if(Boolean.TRUE.equals(row.get("deleted"))){e.put("deleted",true);e.put("revision",revision);}
            else {
                PresetRecord existing=library.findBySha256(hash);
                if(existing==null){byte[] bytes=api.download(id,hash);if(!LibraryFiles.sha256(bytes).equals(hash))throw new IOException("云端预设校验失败。");library.importFile(string(row,"originalFileName"),bytes);}
                library.updatePersonalMetadata(hash,row);e.put("metadata",metadata(library.findBySha256(hash)));e.put("deleted",false);e.put("revision",revision);
            }
            e.remove("restoreRemote");save();progress.update(++done,total);
        }
        for(var item:new ArrayList<>(entries(id).entrySet())){
            String hash=item.getKey();Map<String,Object> e=AccountJson.object(item.getValue());
            if(Boolean.TRUE.equals(e.get("dirty"))&&!e.containsKey("conflictRevision")){
                long revision=number(e,"revision",0);
                if(Boolean.TRUE.equals(e.get("deleted"))||library.findBySha256(hash)==null){api.delete(id,hash,revision);e.put("deleted",true);if(revision>0)e.put("revision",revision+1);}
                else {Map<String,Object> m=AccountJson.object(e.get("metadata"));long saved=api.put(id,hash,m,library.readPreset(hash),revision);if(saved<1)throw new IOException("云端保存结果无效。");e.put("revision",saved);}
                e.put("dirty",false);save();
            }
            progress.update(++done,total);
        }
    }
    public static Map<String,Object> metadata(PresetRecord r){Map<String,Object> m=new LinkedHashMap<>();m.put("displayName",r.displayName);m.put("originalFileName",r.originalFileName);m.put("category",r.category);m.put("description",r.description);return m;}
    private static String string(Map<String,Object> m,String k)throws IOException{Object v=m.get(k);if(!(v instanceof String))throw new IOException("预设信息不完整。");return (String)v;}
    private static boolean sameMetadata(Map<String,Object> remote,Object local)throws IOException{if(!(local instanceof Map))return false;Map<String,Object> m=AccountJson.object(local);for(String k:new String[]{"displayName","originalFileName","category","description"})if(!Objects.equals(remote.get(k),m.get(k)))return false;return true;}
    private void save()throws IOException{LibraryFiles.atomicWrite(file,AccountJson.stringify(accounts).getBytes(StandardCharsets.UTF_8));publish();}
    private void publish()throws IOException {
        Map<String,Set<String>> next=new HashMap<>(),deleted=new HashMap<>();
        for(var a:accounts.entrySet()){owner(a.getKey());Object rows=AccountJson.object(a.getValue()).get("entries");if(!(rows instanceof Map))continue;
            for(var item:AccountJson.object(rows).entrySet()){LibraryFiles.requireHash(item.getKey());next.computeIfAbsent(item.getKey(),k->new HashSet<>()).add(a.getKey());if(Boolean.TRUE.equals(AccountJson.object(item.getValue()).get("deleted")))deleted.computeIfAbsent(a.getKey(),k->new HashSet<>()).add(item.getKey());}}
        claimed=next;hidden=deleted;
    }
}

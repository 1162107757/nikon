package cc.huevia.library;
import java.io.*;import java.nio.file.*;import java.util.*;

/** Local files + injected owner-aware cloud. No accounts or uploads on the real service. */
public final class PersonalPresetSyncTest {
 static int checks;static final String A="11111111-1111-4111-8111-111111111111",B="22222222-2222-4222-8222-222222222222";
 static void check(boolean v,String label){if(!v)throw new AssertionError(label);checks++;}
 static byte[] ncp(){byte[] b=new byte[52];b[0]='N';b[1]='C';b[2]='P';b[7]=1;b[11]=36;b[12]='0';b[13]='1';b[14]='0';b[15]='0';b[16]='Q';return b;}
 static class Cloud implements PersonalPresetSync.Backend {
  Map<String,Map<String,Map<String,Object>>> owners=new HashMap<>();byte[] bytes=ncp();int writes;boolean lose,offline;
  Map<String,Map<String,Object>> records(String o){return owners.computeIfAbsent(o,k->new LinkedHashMap<>());}
  public List<Map<String,Object>> list(String o,String after)throws IOException{if(offline)throw new IOException("offline");List<Map<String,Object>> rows=new ArrayList<>();for(var item:records(o).entrySet())if(item.getKey().compareTo(after)>0)rows.add(new LinkedHashMap<>(item.getValue()));return rows;}
  public byte[] download(String o,String h)throws IOException{if(!records(o).containsKey(h))throw new IOException("wrong owner");return bytes.clone();}
  public long put(String o,String h,Map<String,Object> m,byte[] b,long rev)throws IOException{writes++;Map<String,Object> old=records(o).get(h);long current=old==null?0:((Number)old.get("revision")).longValue();if(rev!=current)throw new IOException("conflict");Map<String,Object> r=new LinkedHashMap<>(m);r.put("sha256",h);r.put("revision",current+1);r.put("deleted",false);records(o).put(h,r);if(lose){lose=false;throw new IOException("lost response");}return current+1;}
  public void delete(String o,String h,long rev){Map<String,Object> r=records(o).get(h);if(r!=null){r.put("deleted",true);r.put("revision",rev+1);}}
 }
 public static void main(String[]args)throws Exception {
  File root=Files.createTempDirectory(Path.of(args[0]),"personal-").toFile();PresetLibrary lib=new PresetLibrary(root);PresetRecord r=lib.importFile("Q.NCP",ncp()).record;PersonalPresetSync s=new PersonalPresetSync(root,lib);Cloud cloud=new Cloud();
  check(s.consentNeeded(A)&&s.visible("",r.id),"guest remains local; explicit consent needed");s.consent(A,false);s.sync(A,cloud,(d,t)->check(d<=t,"bounded progress"));check(cloud.writes==0,"decline never uploads existing files");
  s.consent(A,true);s.sync(A,cloud,(d,t)->{});check(cloud.writes==1&&cloud.records(A).size()==1,"consented file saved");check(!s.visible(B,r.id)&&!s.visible("",r.id),"account cache isolated from guest and other account");check(s.unclaimed().isEmpty(),"other account cache not offered for upload");
  PersonalPresetSync restarted=new PersonalPresetSync(root,lib);restarted.sync(A,cloud,(d,t)->{});check(cloud.writes==1,"restart does not duplicate unchanged upload");
  File phone2=Files.createTempDirectory(Path.of(args[0]),"phone2-").toFile();PresetLibrary secondLib=new PresetLibrary(phone2);PersonalPresetSync second=new PersonalPresetSync(phone2,secondLib);second.sync(A,cloud,(d,t)->check(d<=t,"restore progress"));check(Arrays.equals(secondLib.readPreset(r.id),ncp()),"new phone gets exact original file");
  PresetRecord edited=lib.setCategory(r.id,"风景");restarted.markAdded(A,edited);cloud.lose=true;try{restarted.sync(A,cloud,(d,t)->{});throw new AssertionError();}catch(IOException expected){}int writes=cloud.writes;restarted.sync(A,cloud,(d,t)->{});check(cloud.writes==writes&&restarted.conflicts(A)==0,"lost upload response reconciles without duplicate or false conflict");
  secondLib.setCategory(r.id,"人像");second.markAdded(A,secondLib.findBySha256(r.id));second.sync(A,cloud,(d,t)->{});check(second.conflicts(A)==1,"concurrent metadata asks instead of overwriting");second.resolveConflicts(A,false);second.sync(A,cloud,(d,t)->{});check(secondLib.findBySha256(r.id).category.equals("风景"),"explicit cloud choice resolves conflict");
  lib.remove(r.id);restarted.sync(A,cloud,(d,t)->{});check(Boolean.TRUE.equals(cloud.records(A).get(r.id).get("deleted")),"committed local deletion survives missing UI callback");
  second.sync(A,cloud,(d,t)->{});check(!second.visible(A,r.id),"remote deletion not resurrected by offline cache");check(secondLib.findBySha256(r.id)!=null,"remote removal does not destroy files used by projects");
  second.markAdded(A,secondLib.findBySha256(r.id));second.sync(A,cloud,(d,t)->{});check(!Boolean.TRUE.equals(cloud.records(A).get(r.id).get("deleted")),"explicit re-add restores");
  cloud.offline=true;PresetRecord b=secondLib.setCategory(r.id,"胶片");second.markAdded(A,b);try{second.sync(A,cloud,(d,t)->{});throw new AssertionError();}catch(IOException expected){}cloud.offline=false;new PersonalPresetSync(phone2,secondLib).sync(A,cloud,(d,t)->{});check(cloud.records(A).get(r.id).get("category").equals("胶片"),"offline edits persist and retry after process restart");
  System.out.println("PASS: personal preset sync "+checks+" checks");
 }
}

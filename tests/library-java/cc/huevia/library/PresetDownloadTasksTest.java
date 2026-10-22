package cc.huevia.library;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.Executor;
import javax.net.ssl.HttpsURLConnection;

/** Real repository commits with injected HTTPS; no Android UI, network or camera access. */
public final class PresetDownloadTasksTest {
    private static int checks; private static File root;
    public static void main(String[] args)throws Exception {
        root=new File(args[0]);root.mkdirs(); stagesAndDeduplication(); failuresAndRetry(); boundedAndNavigation();
        System.out.println("Preset download tasks: "+checks+" checks passed (no network/camera I/O).");
    }
    private static void stagesAndDeduplication()throws Exception {
        File directory=fresh();PresetLibrary library=new PresetLibrary(directory);FakeHttp http=new FakeHttp();byte[] raw=ncp(1);
        OnlinePreset item=preset(raw,true);http.responses.add(raw);http.responses.add(new byte[]{1,2,3});
        OnlinePresetRepository repository=new OnlinePresetRepository(directory,library,http.client());QueueExecutor executor=new QueueExecutor();PresetDownloadTasks tasks=new PresetDownloadTasks(executor);
        List<String> stages=new ArrayList<>();boolean[] committedBeforeSample={false},saw99={false},unknown={false};
        tasks.addObserver(()->{PresetDownloadTasks.State state=tasks.state(item.sha256);if(state==null)return;stages.add(state.stage);
            if(state.stage.equals("保存中")){check(state.active&&state.percent()==99,"complete source bytes remain 99 until commit");saw99[0]=true;}
            if(state.stage.equals("载入样片")){check(state.active&&state.percent()==-1&&state.record==null,"unknown sample stage has no fake percentage or success");check(tasks.libraryRevision()==0,"byte/sample progress does not trigger library rereads");unknown[0]=true;committedBeforeSample[0]=library.findBySha256(item.sha256)!=null;}
        });
        check(tasks.start(item,repository),"first task accepted");long identity=tasks.state(item.sha256).identity;
        check(!tasks.start(item,repository),"same SHA active task deduplicated");check(tasks.state(item.sha256).identity==identity,"duplicate keeps task identity");
        tasks.forget(item.sha256);check(tasks.isActive(item.sha256),"active task cannot be forgotten during delete");
        check(executor.jobs.size()==1,"duplicate does not enqueue a second transfer");executor.jobs.remove().run();
        PresetDownloadTasks.State complete=tasks.state(item.sha256);check(!complete.active&&complete.percent()==100&&complete.record!=null,"verified committed result reaches 100");
        check(tasks.libraryRevision()==1,"successful commit emits exactly one library revision");
        check(saw99[0]&&unknown[0]&&committedBeforeSample[0],"commit/sample phases exercised with actual disk marker");
        check(!complete.note.isEmpty(),"optional sample failure is explicit without failing saved recipe");check(complete.error.isEmpty(),"sample failure does not misclassify preset save");
        check(Arrays.equals(raw,library.readPreset(complete.record.id)),"final result contains exact source bytes");check(http.requests==2,"one recipe and one optional sample request");
        check(stages.indexOf("等待下载")<stages.indexOf("保存中")&&stages.indexOf("载入样片")<stages.lastIndexOf("已下载"),"real stage order");
        check(PresetDownloadTasks.shared()==PresetDownloadTasks.shared(),"application owners share process task identity");
    }
    private static void failuresAndRetry()throws Exception {
        File directory=fresh();PresetLibrary library=new PresetLibrary(directory);FakeHttp http=new FakeHttp();byte[] raw=ncp(2);OnlinePreset item=preset(raw,false);
        http.responses.add(new byte[]{1,2});OnlinePresetRepository repository=new OnlinePresetRepository(directory,library,http.client());QueueExecutor executor=new QueueExecutor();PresetDownloadTasks tasks=new PresetDownloadTasks(executor);
        tasks.start(item,repository);long old=tasks.state(item.sha256).identity;executor.jobs.remove().run();PresetDownloadTasks.State failed=tasks.state(item.sha256);
        check(!failed.active&&!failed.error.isEmpty()&&failed.record==null,"failed transfer has explicit retry state");check(failed.percent()<100,"failure never claims 100");check(library.list().isEmpty(),"partial source creates no library record");
        http.responses.add(raw);check(tasks.start(item,repository),"explicit retry accepted");check(tasks.state(item.sha256).identity>old,"retry gets a fresh task identity");executor.jobs.remove().run();
        check(tasks.state(item.sha256).percent()==100&&library.list().size()==1,"retry commits exactly one preset");long revision=tasks.libraryRevision();tasks.forget(item.sha256);check(tasks.state(item.sha256)==null,"terminal task can be removed after local deletion");
        check(tasks.libraryRevision()==revision+1,"deletion event refreshes owners holding an older library snapshot");
        tasks.forget(item.sha256);check(tasks.libraryRevision()==revision+2,"local import deletion emits revision even without prior download state");
        check(library.list().size()==1,"forgetting UI task metadata never deletes source files");
        PresetDownloadTasks rejected=new PresetDownloadTasks(work->{throw new java.util.concurrent.RejectedExecutionException("fixture");});rejected.start(item,repository);
        check(!rejected.state(item.sha256).active&&!rejected.state(item.sha256).error.isEmpty(),"executor rejection cannot leave an eternal spinner");
    }
    private static void boundedAndNavigation()throws Exception {
        File directory=fresh();PresetLibrary library=new PresetLibrary(directory);FakeHttp http=new FakeHttp();QueueExecutor executor=new QueueExecutor();PresetDownloadTasks tasks=new PresetDownloadTasks(executor);
        OnlinePresetRepository repository=new OnlinePresetRepository(directory,library,http.client());List<OnlinePreset> items=new ArrayList<>();
        int[] oldEvents={0},newEvents={0};Runnable first=()->oldEvents[0]++,second=()->newEvents[0]++;tasks.addObserver(first);
        for(int index=10;index<14;index++){byte[] bytes=ncp(index);OnlinePreset item=preset(bytes,false);items.add(item);http.responses.add(bytes);tasks.start(item,repository);}
        check(tasks.snapshot().size()==4&&executor.jobs.size()==4,"at most four simultaneous accepted downloads");
        try{tasks.start(preset(ncp(15),false),repository);throw new AssertionError("expected bounded queue rejection");}catch(IOException expected){checks++;}
        check(!tasks.start(items.get(0),repository),"active duplicate remains deduplicated at queue limit");
        tasks.removeObserver(first);int eventsAtLeave=oldEvents[0];tasks.addObserver(second);tasks.addObserver(()->{throw new IllegalStateException("unrelated observer");});
        while(!executor.jobs.isEmpty())executor.jobs.remove().run();
        check(oldEvents[0]==eventsAtLeave&&newEvents[0]>0,"leaving and returning observers do not cancel or duplicate task");
        check(!tasks.hasActive()&&library.list().size()==4,"accepted background tasks all finish once");
        for(OnlinePreset item:items)check(tasks.state(item.sha256).percent()==100,"returning page reads actual terminal state");
        check(http.requests==4,"navigation caused no extra network requests");
    }
    private static OnlinePreset preset(byte[] bytes,boolean preview)throws Exception {
        Map<String,Object> data=new LinkedHashMap<>();data.put("id",UUID.randomUUID().toString());data.put("name","Task "+(bytes[40]&255));data.put("description","");
        data.put("fileUrl","https://fixture.invalid/preset.ncp");data.put("sha256",LibraryFiles.sha256(bytes));data.put("byteLength",bytes.length);data.put("previewUrl",preview?"https://fixture.invalid/sample.png":"");data.put("compatibleModels",List.of());
        return OnlinePreset.fromMap(LibraryJson.object(LibraryJson.parse(LibraryJson.stringify(data))));
    }
    private static byte[] ncp(int marker){byte[] bytes=new byte[52];bytes[0]='N';bytes[1]='C';bytes[2]='P';bytes[7]=1;bytes[11]=36;System.arraycopy("0100Task".getBytes(StandardCharsets.US_ASCII),0,bytes,12,8);bytes[40]=(byte)marker;return bytes;}
    private static File fresh()throws IOException{return Files.createTempDirectory(root.toPath(),"tasks-").toFile();}
    private static void check(boolean okay,String label){checks++;if(!okay)throw new AssertionError(label);}
    private static final class QueueExecutor implements Executor{final Queue<Runnable> jobs=new ArrayDeque<>();public void execute(Runnable task){jobs.add(task);}}
    private static final class FakeHttp{final Queue<byte[]> responses=new ArrayDeque<>();int requests;SecureHttpClient client(){return new SecureHttpClient(url->{requests++;byte[] bytes=responses.poll();if(bytes==null)throw new IOException("Unexpected HTTP request");return new Fixture(url,bytes);});}}
    private static final class Fixture extends HttpsURLConnection{final byte[] bytes;Fixture(URL url,byte[] bytes){super(url);this.bytes=bytes;}public int getResponseCode(){return 200;}public long getContentLengthLong(){return bytes.length;}public String getHeaderField(String name){return null;}public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}public String getCipherSuite(){return "fixture";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}}
}

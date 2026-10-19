package com.nikonpreset.usbprobe.album;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Production RAW controller/client using the existing framed synthetic peer, without camera hardware. */
public final class RawControllerHostTest {
    private static int checks;
    private static final Class<?> PEER;
    static { try { PEER = Class.forName(AlbumControllerHostTest.class.getName() + "$Peer"); } catch(Exception e){throw new AssertionError(e);} }
    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("raw-controller-");
        explicitRawAndRestart(base.resolve("success"));
        rejectedSources(base.resolve("rejected"));
        automaticMatchingFailsClosed(base.resolve("pairing"));
        localJpegReusesRawOffline(base.resolve("local"));
        unreadableInventoryPreventsPairing(base.resolve("incomplete"));
        rawReservationProtectsLiveAndCancellation(base.resolve("reservation"));
        System.out.println("PASS: " + checks + " production RAW controller/PTP checks");
    }
    private static void explicitRawAndRestart(Path root)throws Exception {
        byte[] source=RawFormatPairingTest.fixture(true);Object peer=peer(source,true);RawPhotoDownload saved;PhotoItem stale;
        try(AlbumController controller=connect(root,peer)){
            PhotoPage jpg=call(cb->controller.loadPage(0,20,cb));check(jpg.items().isEmpty(),"RAW-only camera does not leak into JPEG selection/rendering");
            PhotoPage page=call(cb->controller.loadRawPage(0,20,cb));check(page.items().size()==1 && !page.hasMore(),"RAW-only NEF is explicitly browsable");
            stale=page.items().get(0);RawResult result=new RawResult();controller.downloadRaw(stale,result);saved=result.value();
            check(Arrays.equals(source,Files.readAllBytes(saved.file().toPath())),"GetObject writes all original sensor/EXIF bytes unchanged");
            check(result.last==source.length && result.total==source.length,"RAW transfer uses real received bytes");
            check(result.stages.contains("receiving") && result.stages.contains("verifying"),"Byte transfer and verification are separate stages");
            check(count(peer,0x1009)==1,"Exactly one streamed object request");
            RawResult same=new RawResult();controller.downloadRaw(stale,same);check(same.value().file().equals(saved.file())&&count(peer,0x1009)==1,"Repeated explicit download reuses verified exact source");
            check(call(controller::listRawDownloads).size()==1 && call(controller::listDownloads).isEmpty(),"RAW completion is isolated from JPEG catalog");
        }
        Object next=peer(source,true);
        try(AlbumController controller=connect(root,next)){
            PhotoPage page=call(cb->controller.loadRawPage(0,20,cb));RawResult result=new RawResult();controller.downloadRaw(page.items().get(0),result);
            check(result.value().file().equals(saved.file())&&count(next,0x1009)==0,"Restart/new connection reuses source only with same trusted camera GUID and descriptor");
            RawResult old=new RawResult();controller.downloadRaw(stale,old);check(old.error().contains("旧的连接"),"Old session PhotoItem cannot authorize a new camera read");
            controller.disconnect();check(call(controller::listRawDownloads).size()==1,"Completed RAW remains available offline");
            check(RawControllerHostTest.<RawPhotoDownload>call(cb->controller.findRawDownload(saved.sourceIdentity(),cb)).file().equals(saved.file()),"Persistent source identity finds original offline");
        }
        Object untrusted=peer(source,false);
        try(AlbumController controller=connect(root,untrusted)){
            PhotoPage page=call(cb->controller.loadRawPage(0,20,cb));RawResult result=new RawResult();controller.downloadRaw(page.items().get(0),result);
            check(result.value().file().equals(saved.file())&&count(untrusted,0x1009)==1,"Unidentified camera must re-read bytes; only identical content deduplicates");
        }
    }
    private static void rejectedSources(Path base)throws Exception{
        Object renamed=peer(new byte[]{(byte)255,(byte)216,0,0,(byte)255,(byte)217},true);
        // A non-NEF payload larger than the minimum passes transport bounds but fails structure.
        ((Map<Long,byte[]>)field(renamed,"originals")).put(42L,new byte[512]);
        try(AlbumController controller=connect(base.resolve("fake"),renamed)){
            PhotoPage page=call(cb->controller.loadRawPage(0,20,cb));RawResult result=new RawResult();controller.downloadRaw(page.items().get(0),result);
            check(result.error().contains("NEF")||result.error().contains("RAW"),"Renamed non-NEF is rejected");check(call(controller::listRawDownloads).isEmpty(),"Bad NEF never enters completed list");
        }
        Object changed=peer(RawFormatPairingTest.fixture(false),true);set(changed,"changeIdentityAfterOriginal",true);
        try(AlbumController controller=connect(base.resolve("changed"),changed)){
            PhotoPage page=call(cb->controller.loadRawPage(0,20,cb));RawResult result=new RawResult();controller.downloadRaw(page.items().get(0),result);
            check(result.error().contains("已变化"),"Descriptor is re-read after streamed RAW, not just before");check(call(controller::listRawDownloads).isEmpty(),"Changed camera object cannot commit");
        }
        Object huge=peer(RawFormatPairingTest.fixture(true),true);((Map<Long,byte[]>)field(huge,"originals")).clear();set(huge,"originalSize",PhotoFormat.MAX_RAW_BYTES+1);
        try(AlbumController controller=connect(base.resolve("huge"),huge)){
            PhotoPage page=call(cb->controller.loadRawPage(0,20,cb));RawResult result=new RawResult();controller.downloadRaw(page.items().get(0),result);
            check(result.error().contains("512 MB")&&count(huge,0x1009)==0,"Oversize metadata fails before reading an object");
        }
    }
    private static void automaticMatchingFailsClosed(Path root)throws Exception {
        Object peer=peer(RawFormatPairingTest.fixture(true),true);
        ((Map<Long,String>)field(peer,"filenames")).put(43L,"RAWONLY.JPG");
        ((Map<Long,byte[]>)field(peer,"originals")).put(43L,new byte[32]);
        set(peer,"photoHandles",new long[]{42,43});
        try(AlbumController controller=connect(root,peer)){
            PhotoPage page=call(cb->controller.loadPage(0,20,cb));check(page.items().size()==1,"JPG view still excludes NEF");
            RawResult result=new RawResult();controller.downloadRawForJpeg(page.items().get(0),result);
            check(result.error().contains("明确选择")&&count(peer,0x1009)==0,"Missing capture time cannot silently match same-name NEF");
            check(result.stages.contains("discovery"),"Matching reports inventory stage before download");
        }
    }
    private static void localJpegReusesRawOffline(Path root)throws Exception {
        Object peer=peer(RawJpegReferenceTest.jpeg(),true);((Map<Long,Integer>)field(peer,"formats")).put(42L,0x3801);((Map<Long,String>)field(peer,"filenames")).put(42L,"LOCAL.JPG");
        RawPhotoDownload cached;DownloadedPhoto jpeg;
        try(AlbumController controller=connect(root,peer)){
            PhotoPage page=call(cb->controller.loadPage(0,20,cb));Result<DownloadedPhoto> got=new Result<>();controller.download(page.items().get(0),new AlbumController.DownloadCallback(){public void onProgress(long r,long t){}public void onSuccess(DownloadedPhoto p){got.onSuccess(p);}public void onError(String e){got.onError(e);}});jpeg=got.value();
            call(controller::listRawDownloads); // Local FIFO drains the optional source bind before inspecting it.
            RawJpegReferenceStore.Reference reference=new RawJpegReferenceStore(root.resolve("files").toFile(),root.resolve("external/Pictures").toFile()).find(jpeg);
            check(reference!=null,"Normal JPEG completion records exact RAW pairing provenance asynchronously");
            RawPhotoStore store=new RawPhotoStore(root.resolve("external/Pictures/camera-raw").toFile());java.io.File temporary=store.createTemporary();Files.write(temporary.toPath(),RawFormatPairingTest.fixture(true));
            RawPhotoDownload raw=store.commit("f".repeat(64),temporary,"LOCAL.NEF");cached=store.alias(reference.sourceIdentity,raw);
            controller.disconnect();RawResult result=new RawResult();controller.downloadRawForLocal(jpeg,result);check(result.value().file().equals(cached.file()),"Local JPEG reuses proven NEF with no camera connection");
        }
        try(AlbumController controller=new AlbumController(new android.content.Context(root.toFile()))){RawResult result=new RawResult();controller.downloadRawForLocal(jpeg,result);check(result.value().file().equals(cached.file()),"Offline local NEF export survives controller/app restart");}
    }
    private static void unreadableInventoryPreventsPairing(Path root)throws Exception {
        Object peer=peer(RawFormatPairingTest.fixture(true),true);((Map<Long,String>)field(peer,"filenames")).put(43L,"RAWONLY.JPG");((Map<Long,byte[]>)field(peer,"originals")).put(43L,RawJpegReferenceTest.jpeg());set(peer,"photoHandles",new long[]{42,43,44});
        try(AlbumController controller=connect(root,peer)){
            PhotoPage page=call(cb->controller.loadPage(0,20,cb));((Map<Long,Integer>)field(peer,"metadataBusy")).put(44L,1);
            RawResult result=new RawResult();controller.downloadRawForJpeg(page.items().get(0),result);
            check(result.error().contains("未完成全部文件核对")&&count(peer,0x1009)==0,"One unreadable descriptor invalidates proof of all-card RAW uniqueness");
        }
    }
    private static void rawReservationProtectsLiveAndCancellation(Path root)throws Exception{
        Object peer=peer(RawFormatPairingTest.fixture(true),true);set(peer,"pauseOriginal",true);
        try(AlbumController controller=connect(root,peer)){
            PhotoPage page=call(cb->controller.loadRawPage(0,20,cb));RawResult result=new RawResult();controller.downloadRaw(page.items().get(0),result);
            check(((CountDownLatch)field(peer,"originalPaused")).await(10,TimeUnit.SECONDS),"RAW stream is in flight");
            Result<Void> live=new Result<>();controller.startPhotoLive(live);check(live.error().contains("RAW"),"A live project cannot become enabled while manual RAW owns the operation");
            Result<PtpIpClient> transfer=new Result<>();controller.transferForPictureControl(transfer);check(transfer.error().contains("RAW"),"Preset import cannot steal the in-flight RAW connection");
            controller.disconnect();((CountDownLatch)field(peer,"releaseOriginal")).countDown();check(!result.error().isEmpty(),"Disconnect terminates RAW progress through a cancellation result");
            check(call(controller::listRawDownloads).isEmpty(),"Interrupted original never becomes complete");
            check(!controller.isRawBusy(),"RAW reservation is released after cancellation");
        }finally{((CountDownLatch)field(peer,"releaseOriginal")).countDown();}
    }
    private static Object peer(byte[] source,boolean trusted)throws Exception{
        Constructor<?> ctor=PEER.getDeclaredConstructor();ctor.setAccessible(true);Object peer=ctor.newInstance();
        ((Map<Long,Integer>)field(peer,"formats")).put(42L,0x3802);((Map<Long,String>)field(peer,"filenames")).put(42L,"RAWONLY.NEF");
        ((Map<Long,byte[]>)field(peer,"originals")).put(42L,source);
        if(trusted)Arrays.fill((byte[])field(peer,"cameraGuid"),(byte)7);return peer;
    }
    private static AlbumController connect(Path root,Object peer)throws Exception {Method method=AlbumControllerHostTest.class.getDeclaredMethod("connected",Path.class,PEER);method.setAccessible(true);return (AlbumController)method.invoke(null,root,peer);}
    private static Object field(Object object,String name)throws Exception{Field value=PEER.getDeclaredField(name);value.setAccessible(true);return value.get(object);}
    private static void set(Object object,String name,Object value)throws Exception{Field f=PEER.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    private static int count(Object peer,int operation)throws Exception{Method m=PEER.getDeclaredMethod("count",int.class);m.setAccessible(true);return (Integer)m.invoke(peer,operation);}
    private static <T>T call(Consumer<AlbumController.Callback<T>> action)throws Exception{Result<T> r=new Result<>();action.accept(r);return r.value();}
    private static class Result<T>implements AlbumController.Callback<T>{final CountDownLatch done=new CountDownLatch(1);T result;String error;public void onSuccess(T value){result=value;done.countDown();}public void onError(String message){error=message;done.countDown();}void await()throws Exception{if(!done.await(15,TimeUnit.SECONDS))throw new AssertionError("No RAW callback");}T value()throws Exception{await();if(error!=null)throw new AssertionError(error);return result;}String error()throws Exception{await();if(error==null)throw new AssertionError("Expected rejection");return error;}}
    private static final class RawResult extends Result<RawPhotoDownload>implements AlbumController.RawDownloadCallback{long last,total;final java.util.List<String>stages=new java.util.ArrayList<>();public void onProgress(long done,long total){last=done;this.total=total;}public void onStage(String stage){stages.add(stage);}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}

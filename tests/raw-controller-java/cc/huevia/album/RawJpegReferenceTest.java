package cc.huevia.album;

import cc.huevia.ptp.PtpObjectInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Exact-byte local JPEG provenance persists separately from the established album schema. */
public final class RawJpegReferenceTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("raw-jpeg-evidence-");Path files=root.resolve("files"),pictures=root.resolve("external/Pictures"),album=pictures.resolve("camera-album");Files.createDirectories(album);
        byte[] bytes=jpeg();Path first=album.resolve("original.JPG");Files.write(first,bytes);DownloadedPhoto photo=new DownloadedPhoto(first.toFile(),"DSC_0001.JPG");
        RawJpegReferenceStore store=new RawJpegReferenceStore(files.toFile(),pictures.toFile());
        PtpObjectInfo info=new PtpObjectInfo.Builder().objectFormat(0x3801).storageId(1).parentObject(7).compressedSize(bytes.length).filename("DSC_0001.JPG").captureDate("20261018T201100").build();
        PhotoItem item=new PhotoItem("s",42,info);String camera="a".repeat(32)+"\nNikon\nZ6II";
        store.bind(camera,false,item,photo);check(store.find(photo)==null,"Untrusted device is never saved as reconstructable camera evidence");
        store.bind(camera,true,item,photo);RawJpegReferenceStore.Reference ref=new RawJpegReferenceStore(files.toFile(),pictures.toFile()).find(photo);
        check(ref.handle==42 && Arrays.equals(ref.info.toBytes(),info.toBytes())&&ref.camera.equals(camera),"Restart preserves full exact camera descriptor");
        check(ref.sourceIdentity.equals(PhotoLiveLedger.identity(camera,42,info)),"Reference uses established camera/card/descriptor identity");
        Path alias=album.resolve("renamed.JPG");Files.write(alias,bytes);check(store.find(new DownloadedPhoto(alias.toFile(),"rename.JPG")).sourceIdentity.equals(ref.sourceIdentity),"Verified identical original may be found after local rename");
        byte[] altered=bytes.clone();altered[14]^=55;Files.write(alias,altered);check(store.find(new DownloadedPhoto(alias.toFile(),"DSC_0001.JPG"))==null,"A matching displayed filename cannot substitute different original bytes");
        store.bind("b".repeat(32)+"\nNikon\nZ6II",true,item,photo);fails(()->store.find(photo));
        store.bind(camera,true,item,photo);fails(()->store.find(photo));
        Path effect=album.resolve("effect-"+"f".repeat(64)+"__Effect.JPG");Files.write(effect,bytes);fails(()->store.find(new DownloadedPhoto(effect.toFile(),"Effect.JPG")));
        System.out.println("PASS: "+checks+" local JPG-to-RAW provenance checks");
    }
    static byte[] jpeg(){byte[] b=new byte[32];b[0]=(byte)255;b[1]=(byte)216;b[30]=(byte)255;b[31]=(byte)217;return b;}
    private interface Operation{void run()throws Exception;}
    private static void fails(Operation action)throws Exception{try{action.run();throw new AssertionError("Expected ambiguous/invalid source rejection");}catch(java.io.IOException expected){checks++;}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}

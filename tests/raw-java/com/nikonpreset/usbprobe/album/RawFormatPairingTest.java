package com.nikonpreset.usbprobe.album;

import com.nikonpreset.usbprobe.ptp.PtpObjectInfo;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Structure/identity cases independent of Android codecs and actual camera hardware. */
public final class RawFormatPairingTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("raw-structure-");
        for (boolean little : new boolean[] {false, true}) {
            byte[] valid = fixture(little); Path file = root.resolve("valid-" + little + ".NEF"); Files.write(file, valid);
            PhotoFormat.validateNef(file.toFile(), valid.length); check(Arrays.equals(valid, Files.readAllBytes(file)), "Validation preserves every byte");
            for (int cut : new int[] {0, 2, 7, 30, 85, 105, 270}) {
                Files.write(file, Arrays.copyOf(valid, cut)); fails(() -> PhotoFormat.validateNef(file.toFile(), cut));
            }
            Files.write(file, valid); fails(() -> PhotoFormat.validateNef(file.toFile(), valid.length + 1));
            for (int offset : new int[] {2, 4, 10, 12, 54, 66, 82, 100}) {
                byte[] bad = valid.clone(); bad[offset] = (byte) 0xff; Files.write(file, bad);
                fails(() -> PhotoFormat.validateNef(file.toFile(), bad.length));
            }
        }
        PtpObjectInfo jpg = info(1, 7, "DSC_0001.JPG", "20261018T190001", 23);
        PhotoItem same = raw(1,7,"DSC_0001.NEF","20261018T190001",23);
        check(RawPhotoPairing.unique(jpg, List.of(same), true) == same, "Same-card unique pair");
        PhotoItem cross = raw(2,99,"dsc_0001.nef","20261018T190001",23);
        check(RawPhotoPairing.unique(jpg, List.of(cross), true) == cross, "Cross-card pair from same verified camera inventory");
        fails(() -> RawPhotoPairing.unique(jpg,List.of(same,cross),true));
        fails(() -> RawPhotoPairing.unique(jpg,List.of(same),false));
        fails(() -> RawPhotoPairing.unique(jpg,List.of(raw(1,8,"DSC_0001.NEF","20261018T190001",23)),true));
        fails(() -> RawPhotoPairing.unique(jpg,List.of(raw(1,7,"DSC_0001.NEF","20261018T190002",23)),true));
        fails(() -> RawPhotoPairing.unique(jpg,List.of(raw(1,7,"DSC_0001.NEF","20261018T190001",24)),true));
        fails(() -> RawPhotoPairing.unique(info(1,7,"DSC_0001.JPG","",23),List.of(same),true));
        fails(() -> RawPhotoPairing.unique(info(1,7,"DSC_0001.JPG","20269999T996001",23),List.of(raw(1,7,"DSC_0001.NEF","20269999T996001",23)),true));
        fails(() -> RawPhotoPairing.unique(jpg,List.of(raw(1,7,"OTHER.NEF","20261018T190001",23)),true));
        for (int format : new int[] {0x3802, 0x3000, 0xb101}) {
            PtpObjectInfo raw = new PtpObjectInfo.Builder().objectFormat(format).filename("a.NEF").build();
            check(PhotoFormat.of(raw) == PhotoFormat.NEF, "NEF candidate does not assume undocumented vendor code");
        }
        check(PhotoFormat.of(new PtpObjectInfo.Builder().objectFormat(0x3001).filename("folder.NEF").build()) == PhotoFormat.OTHER,"Folder suffix is not RAW");
        for(String invalid : new String[]{"../a.NEF","a/b.nef","a\\b.nef","a.NEF.JPG","a\n.NEF"}) check(!PhotoFormat.isNefName(invalid),"Unsafe/non-NEF suffix rejected");
        byte[] previewOutOfBounds = fixture(true); ByteBuffer preview = ByteBuffer.wrap(previewOutOfBounds).order(ByteOrder.LITTLE_ENDIAN);
        preview.putInt(82,128).position(128);preview.putShort((short)3);
        preview.putShort((short)0x0106).putShort((short)3).putInt(1).putShort((short)2).putShort((short)0);
        entry(preview,0x0111,4,1,-16);entry(preview,0x0117,4,1,28);preview.putInt(0);
        Path brokenPreview=root.resolve("invalid-preview.NEF");Files.write(brokenPreview,previewOutOfBounds);
        fails(()->PhotoFormat.validateNef(brokenPreview.toFile(),previewOutOfBounds.length));
        System.out.println("PASS: " + checks + " RAW structure and unique pairing checks");
    }
    public static byte[] fixture(boolean little) {
        ByteBuffer out = ByteBuffer.allocate(512).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        out.put((byte)(little?'I':'M')).put((byte)(little?'I':'M')).putShort((short)42).putInt(8).putShort((short)6);
        entry(out,0x010f,2,6,100); entry(out,0x0100,4,1,2); entry(out,0x0101,4,1,2);
        out.putShort((short)0x0106).putShort((short)3).putInt(1).putShort((short)32803).putShort((short)0);
        entry(out,0x0111,4,1,256); entry(out,0x0117,4,1,32); out.putInt(0);
        out.position(100);out.put(new byte[]{'N','I','K','O','N',0});
        for (int i=256;i<288;i++) out.put(i,(byte)i);
        return out.array();
    }
    private static void entry(ByteBuffer out,int tag,int type,int count,int value){out.putShort((short)tag).putShort((short)type).putInt(count).putInt(value);}
    private static PtpObjectInfo info(long storage,long parent,String filename,String time,long sequence){return new PtpObjectInfo.Builder().storageId(storage).parentObject(parent).objectFormat(0x3801).filename(filename).captureDate(time).sequenceNumber(sequence).build();}
    private static PhotoItem raw(long storage,long parent,String filename,String time,long sequence){return new PhotoItem("session",42,new PtpObjectInfo.Builder().storageId(storage).parentObject(parent).objectFormat(0x3802).filename(filename).captureDate(time).sequenceNumber(sequence).build());}
    private interface Operation {void run()throws Exception;}
    private static void fails(Operation operation)throws Exception{try{operation.run();throw new AssertionError("Expected rejection");}catch(IOException expected){checks++;}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}

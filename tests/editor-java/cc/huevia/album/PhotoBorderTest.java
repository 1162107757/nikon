package cc.huevia.album;
import cc.huevia.ncp.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class PhotoBorderTest {
    private static int checks;
    private static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception{
        String sha="b".repeat(64);File root=Files.createTempDirectory("photo-border-test-").toFile();PhotoEditStore store=new PhotoEditStore(root,"account-a");
        PhotoEditStore.Draft neutral=new PhotoEditStore.Draft(sha,new byte[0],"原图",0,false);
        check(!neutral.hasEffect(),"old neutral remains neutral");check(neutral.border.equals(PhotoBorder.NONE),"old constructor default");
        PhotoBorder border=PhotoBorder.NONE.withTemplate(2).withCamera(false,"NIKON","NIKON Z 6_2").withField(0,"28mm").withField(4,"2026-10-23 13:23:56").withVisible(5,false).withLogo(2).withAppearance(0,130).withAppearance(1,0);
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();border.write(new DataOutputStream(encoded));check(border.equals(PhotoBorder.read(new DataInputStream(new ByteArrayInputStream(encoded.toByteArray())))),"all border settings round trip");
        PhotoGeometry geometry=new PhotoGeometry(1,0,0,0,10000,10000);PhotoAdjustments manual=PhotoAdjustments.NONE.with(PhotoAdjustments.Parameter.EXPOSURE,20);
        PhotoEditStore.Draft draft=new PhotoEditStore.Draft(sha,new byte[0],"原图",0,manual,geometry,border,false);store.save(draft);PhotoEditStore.Draft loaded=store.read(sha);
        check(loaded.border.equals(border),"draft border round trip");check(loaded.geometry.equals(geometry)&&loaded.adjustments.equals(manual),"border preserves composition and colour");check(loaded.hasEffect(),"border only activates export");check(GeometryPreviewProfile.geometry(loaded.profile()).equals(geometry),"geometry survives border wrapper");
        check(!draft.identity().equals(neutral.identity()),"draft identity includes border");check(!draft.profile().modelVersion().equals(neutral.profile().modelVersion()),"effect identity includes border");
        for(int i=1;i<=3;i++){PhotoBorder b=PhotoBorder.NONE.withTemplate(i);check(!new PhotoEditStore.Draft(sha,new byte[0],"原图",0,PhotoAdjustments.NONE,PhotoGeometry.NONE,b,false).profile().modelVersion().equals(neutral.profile().modelVersion()),"template cache separated "+i);int[] dims=b.dimensions(3000,2000);check(dims[0]>=3000&&dims[1]>2000,"retains all photo pixels "+i);}
        int[] landscape=PhotoBorder.NONE.withTemplate(3).dimensions(3000,2000);check(landscape[0]==3000&&landscape[1]==2336,"footer proportional dimensions");int[] portrait=PhotoBorder.NONE.withTemplate(2).dimensions(2000,3000);check(portrait[0]==2900&&portrait[1]==3280,"portrait retains layout and pixels");
        PhotoEditStore.Draft latest=new PhotoEditStore.Draft(sha,new byte[0],"原图",0,manual,geometry,border.withTemplate(3),false);store.save(latest);store.markExported(draft);check(!store.read(sha).exported,"old frozen export cannot mark newer border saved");store.markExported(latest);check(store.read(sha).exported&&store.read(sha).border.equals(latest.border),"mark exported retains border");check(new PhotoEditStore(root,"account-b").read(sha)==null,"account isolation");
        for(int magic:new int[]{0x50454431,0x50454432,0x50454433}){
            ByteArrayOutputStream raw=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(raw);out.writeInt(magic);out.writeUTF(sha);out.writeUTF("无滤镜");out.writeInt(0);out.writeBoolean(false);out.writeInt(0);if(magic>=0x50454432){out.writeUTF(PhotoAdjustments.MODEL);int[] values=PhotoAdjustments.NONE.values();out.writeInt(values.length);for(int v:values)out.writeInt(v);}if(magic>=0x50454433){out.writeUTF(PhotoGeometry.MODEL);for(int v:new int[]{0,0,0,0,10000,10000})out.writeInt(v);}out.close();byte[] body=raw.toByteArray();ByteArrayOutputStream file=new ByteArrayOutputStream();file.write(body);file.write(MessageDigest.getInstance("SHA-256").digest(body));Path target=root.toPath().resolve("photo-edit-drafts").resolve(PhotoEditStore.hash("account-a".getBytes(java.nio.charset.StandardCharsets.UTF_8))).resolve(sha+".draft");Files.write(target,file.toByteArray());check(store.read(sha).border.equals(PhotoBorder.NONE),"migrate PED"+(magic-0x50454430));}
        check(!border.withField(0,"35mm").identity().equals(border.identity()),"field affects cache");check(!border.withVisible(4,false).identity().equals(border.identity()),"visibility affects cache");check(!border.withAppearance(3,150).identity().equals(border.identity()),"blur affects cache");check(border.withCamera(true,"","").automatic,"restore auto");
        try{PhotoBorder.NONE.withAppearance(0,0);throw new AssertionError("invalid margin accepted");}catch(IllegalArgumentException expected){checks++;}try{PhotoBorder.NONE.withCamera(false,"NIKON","x".repeat(97));throw new AssertionError("oversize text accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS: "+checks+" photo border migration/identity/frozen export/geometry checks");
    }
}

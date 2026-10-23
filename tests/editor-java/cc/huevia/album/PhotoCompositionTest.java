package cc.huevia.album;

import cc.huevia.ncp.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Geometry coverage, backward identities and real account-private recipe/draft I/O. */
public final class PhotoCompositionTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        for(int exif=1;exif<=8;exif++)for(int turns=0;turns<4;turns++)for(int angle:new int[]{-450,-137,0,90,450}){
            PhotoGeometry g=new PhotoGeometry(turns,angle,0,0,PhotoGeometry.UNIT,PhotoGeometry.UNIT);
            PhotoGeometry.Plan plan=g.plan(601,403,exif);
            check(plan.width==((exif>=5)^(turns%2==1)?403:601),"oriented output width");
            check(plan.height==((exif>=5)^(turns%2==1)?601:403),"oriented output height");
            for(double[] point:new double[][]{{0,0},{plan.width,0},{0,plan.height},{plan.width,plan.height},{plan.width/2.0,plan.height/2.0}}){double[] input=plan.inverse(point[0],point[1]);check(input[0]>=-1e-7&&input[0]<=601+1e-7&&input[1]>=-1e-7&&input[1]<=403+1e-7,"straighten covers corner without outside-source pixels");}
        }
        PhotoGeometry cycle=PhotoGeometry.NONE;for(int i=0;i<4;i++)cycle=cycle.rotate();check(cycle.isNeutral(),"four quarter rotations restore original");
        PhotoGeometry crop=PhotoGeometry.NONE.crop(.1,.2,.9,.8);PhotoGeometry.Plan p=crop.plan(6000,4000,1);check(p.width==4800&&p.height==2400,"full-size crop pixels");
        PhotoGeometry quarter=crop.rotate();check(quarter.left==0&&quarter.right==PhotoGeometry.UNIT&&quarter.turns==1,"rotate starts a full tentative frame");
        check(PhotoGeometry.NONE.plan(6000,4000,6).width==4000,"EXIF portrait frame");
        for(int orientation=1;orientation<=8;orientation++){PhotoGeometry.Plan plan=crop.straighten(-125).plan(6000,4000,orientation);check(plan.width>0&&plan.height>0,"all mirrored EXIF transforms produce valid crop");}
        expect(()->new PhotoGeometry(4,0,0,0,1,1));expect(()->new PhotoGeometry(0,451,0,0,1,1));expect(()->PhotoGeometry.NONE.crop(.8,0,.1,1));expect(()->PhotoGeometry.NONE.crop(Double.NaN,0,1,1));
        PresetPreviewProfile colour=AdjustedPreviewProfile.of(null,PhotoAdjustments.NONE);check(GeometryPreviewProfile.of(colour,PhotoGeometry.NONE)==colour,"neutral geometry preserves exact legacy model object");check(!GeometryPreviewProfile.of(colour,crop).modelVersion().equals(colour.modelVersion()),"crop participates in output identity");
        byte[] preset=NcpCompatibilityFixtures.ncp();String sha="a".repeat(64);PhotoAdjustments manual=PhotoAdjustments.NONE.with(PhotoAdjustments.Parameter.EXPOSURE,12).with(PhotoAdjustments.Parameter.SATURATION,-15);
        File root=Files.createTempDirectory("composition-recipes-").toFile();PhotoEditStore store=new PhotoEditStore(root,"owner-a");PhotoEditStore.Draft draft=new PhotoEditStore.Draft(sha,preset,"Fixture",73,manual,crop,false);store.save(draft);check(store.read(sha).identity().equals(draft.identity()),"PED3 roundtrip freezes crop+filter+manual");check(store.read(sha).geometry.equals(crop),"geometry survives draft reload");
        PhotoEditStore.Draft changed=new PhotoEditStore.Draft(sha,preset,"Fixture",73,manual,crop.straighten(5),false);store.save(changed);store.markExported(draft);check(!store.read(sha).exported,"late export cannot mark newer geometry exported");store.markExported(changed);check(store.read(sha).exported&&store.read(sha).geometry.equals(changed.geometry),"matching export retains geometry");
        String oldSha="b".repeat(64);File draftFile=new File(root,"photo-edit-drafts/"+PhotoEditStore.hash("owner-a".getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/"+oldSha+".draft");ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){out.writeInt(0x50454432);out.writeUTF(oldSha);out.writeUTF("Fixture");out.writeInt(73);out.writeBoolean(false);out.writeInt(preset.length);out.write(preset);out.writeUTF(PhotoAdjustments.MODEL);int[] values=manual.values();out.writeInt(values.length);for(int v:values)out.writeInt(v);}byte[] body=bytes.toByteArray();try(FileOutputStream out=new FileOutputStream(draftFile)){out.write(body);out.write(MessageDigest.getInstance("SHA-256").digest(body));}PhotoEditStore.Draft migrated=store.read(oldSha);check(migrated.geometry.isNeutral()&&migrated.adjustments.equals(manual)&&migrated.strength==73,"PED2 migration preserves manual and adds neutral crop");
        PhotoRecipeStore recipes=new PhotoRecipeStore(root,"owner-a");PhotoRecipeStore.Recipe recipe=recipes.create("晚霞",draft);check(recipes.list().size()==1,"recipe persisted");PhotoEditStore.Draft applied=recipes.read(recipe.id).apply("c".repeat(64),PhotoGeometry.NONE.rotate());check(applied.adjustments.equals(manual)&&applied.strength==73&&Arrays.equals(applied.presetBytes(),preset),"recipe copies exact colour settings and bytes");check(applied.geometry.equals(PhotoGeometry.NONE.rotate()),"recipe preserves target geometry, excludes source crop");check(applied.sourceSha.equals("c".repeat(64))&&!applied.exported,"recipe binds target source and pending state");check(new PhotoRecipeStore(root,"owner-b").list().isEmpty(),"recipe account isolation");expectIo(()->recipes.create("晚霞",draft));expectIo(()->recipes.create("\n",draft));expectIo(()->recipes.create("x".repeat(41),draft));expectIo(()->recipes.read("../bad"));
        recipes.rename(recipe.id,"日落");check(recipes.read(recipe.id).name.equals("日落"),"rename persists");check(recipes.read(recipe.id).summary().contains("73%"),"recipe strength summary");byte[] immutable=recipe.presetBytes();immutable[0]^=1;check(Arrays.equals(recipes.read(recipe.id).presetBytes(),preset),"recipe input is immutable");recipes.delete(recipe.id);check(recipes.list().isEmpty(),"explicit delete removes only recipe");check(store.read(sha).geometry.equals(changed.geometry),"recipe deletion leaves photo drafts unchanged");
        PhotoRecipeStore.Recipe damaged=recipes.create("损坏测试",draft);File recipeFile=new File(root,"photo-recipes/"+PhotoEditStore.hash("owner-a".getBytes(java.nio.charset.StandardCharsets.UTF_8))+"/"+damaged.id+".recipe");byte[] tampered=Files.readAllBytes(recipeFile.toPath());tampered[12]^=1;Files.write(recipeFile.toPath(),tampered);expectIo(()->recipes.read(damaged.id));
        System.out.println("PASS: "+checks+" composition/recipe geometry, migration and storage checks");
    }
    private interface Task{void run()throws Exception;}
    private static void expect(Task t)throws Exception{try{t.run();throw new AssertionError("invalid geometry accepted");}catch(IllegalArgumentException expected){checks++;}}
    private static void expectIo(Task t)throws Exception{try{t.run();throw new AssertionError("invalid recipe accepted");}catch(IOException expected){checks++;}}
    private static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
}

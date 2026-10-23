package cc.huevia.album;

import cc.huevia.ncp.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static cc.huevia.ncp.PhotoAdjustments.Parameter.*;

/** Contract tests for observable tone behaviour, immutable drafts, legacy migration and output identities. */
public final class PhotoAdjustmentsTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        PresetPreviewProfile preset=PresetPreviewProfiles.fromPreset(NcpCompatibilityFixtures.ncp());
        check(AdjustedPreviewProfile.of(preset,PhotoAdjustments.NONE)==preset,"Neutral manual settings keep exact preset object and legacy model");
        int width=257,height=193;int[] source=new int[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){int v=110+(int)(40*Math.sin(x*.39)+30*Math.sin(y*.21));source[y*width+x]=0xa5000000|v<<16|Math.min(255,v+17)<<8|Math.max(0,v-23);}
        int[] snapshot=source.clone();Set<String> models=new HashSet<>();
        for(PhotoAdjustments.Parameter p:PhotoAdjustments.Parameter.values()){
            PhotoAdjustments settings=PhotoAdjustments.NONE.with(p,p==EXPOSURE?7:40);
            PresetPreviewProfile profile=AdjustedPreviewProfile.of(null,settings);
            int[] output=profile.render(source,width,height);
            check(!Arrays.equals(source,output),p.name()+" changes actual photo pixels");
            check(models.add(profile.modelVersion()),p.label+" has a distinct export model identity");
            for(int value:output)check((value>>>24)==0xa5,p.label+" preserves alpha");
            check(Arrays.equals(source,snapshot),p.label+" never overwrites the source array");
            try{settings.with(p,p.max+1);throw new AssertionError("out of range accepted");}catch(IllegalArgumentException expected){checks++;}
        }
        int dark=0xff303030,mid=0xff808080,light=0xffe0e0e0;
        check(red(adjust(EXPOSURE,10,mid))>red(mid),"Positive exposure brightens");
        check(red(adjust(EXPOSURE,-10,mid))<red(mid),"Negative exposure darkens");
        check(adjust(EXPOSURE,10,mid)!=adjust(BRIGHTNESS,100,mid),"Exposure and brightness are distinct controls");
        check(red(adjust(CONTRAST,60,dark))<red(dark)&&red(adjust(CONTRAST,60,light))>red(light),"Contrast expands around middle gray");
        check(red(adjust(SHADOWS,50,dark))-red(dark)>red(adjust(SHADOWS,50,light))-red(light),"Shadow control primarily affects dark regions");
        check(red(light)-red(adjust(HIGHLIGHTS,-50,light))>red(dark)-red(adjust(HIGHLIGHTS,-50,dark)),"Highlight recovery primarily affects bright regions");
        int warm=adjust(TEMPERATURE,60,mid),cool=adjust(TEMPERATURE,-60,mid);
        check(red(warm)>(warm&255)&&red(cool)<(cool&255),"Temperature is relative warm/cool");
        int tint=adjust(TINT,60,mid);check(red(tint)>((tint>>>8)&255),"Positive tint moves gray toward magenta");
        int gray=adjust(SATURATION,-100,0xffb85020);check(red(gray)==((gray>>>8)&255)&&red(gray)==(gray&255),"Minus 100 saturation produces grayscale");
        PhotoAdjustments manual=PhotoAdjustments.NONE.with(EXPOSURE,7).with(TEMPERATURE,12);
        int[] full=preset.render(source,width,height);
        int[] expected=AdjustedPreviewProfile.of(null,manual).render(full,width,height);
        int[] actual=AdjustedPreviewProfile.of(preset,manual).render(source,width,height);
        check(Arrays.equals(expected,actual),"Manual settings apply AFTER the complete preset result");
        check(Arrays.equals(AdjustedPreviewProfile.of(null,manual).render(source,width,height),AdjustedPreviewProfile.of(StrengthPreviewProfile.of(preset,0),manual).render(source,width,height)),"Filter strength zero retains manual edits");
        PhotoAdjustments detail=manual.with(CLARITY,35).with(SHARPENING,25);
        PresetPreviewProfile combined=AdjustedPreviewProfile.of(StrengthPreviewProfile.of(preset,70),detail);
        int[] rows=source.clone();combined.renderPixels(new SpatialPreviewFilter.Pixels(){
            public int width(){return width;}public int height(){return height;}
            public void read(int[] data,int row,int count){System.arraycopy(rows,row*width,data,0,count*width);}
            public void write(int[] data,int row,int count){System.arraycopy(data,0,rows,row*width,count*width);}
        },()->false,null);
        check(Arrays.equals(rows,combined.render(source,width,height)),"Bounded in-place export and preview use identical transforms");
        try{combined.renderPixels(new SpatialPreviewFilter.Pixels(){public int width(){return 1;}public int height(){return 1;}public void read(int[] v,int y,int n){throw new AssertionError("cancelled read");}public void write(int[] v,int y,int n){throw new AssertionError("cancelled write");}},()->true,null);throw new AssertionError("cancel accepted");}catch(IOException expectedCancellation){checks++;}
        drafts(manual,detail);
        System.out.println("PASS: "+checks+" basic adjustment behaviour/migration/identity checks");
    }
    private static void drafts(PhotoAdjustments manual,PhotoAdjustments detail)throws Exception{
        Path root=Files.createTempDirectory("adjustment-core-");
        try{
            String sha="c".repeat(64);byte[] recipe=NcpCompatibilityFixtures.ncp();PhotoEditStore store=new PhotoEditStore(root.toFile(),"owner");
            PhotoEditStore.Draft first=new PhotoEditStore.Draft(sha,recipe,"Preset",70,manual,false);store.save(first);
            check(store.read(sha).adjustments.equals(manual),"Every manual parameter survives disk persistence");
            PhotoEditStore.Draft changed=new PhotoEditStore.Draft(sha,recipe,"Preset",70,detail,false);store.save(changed);store.markExported(first);
            check(!store.read(sha).exported&&store.read(sha).adjustments.equals(detail),"Late export cannot mark newer manual edits exported");
            Set<String> ids=new HashSet<>();for(PhotoAdjustments.Parameter p:PhotoAdjustments.Parameter.values())ids.add(new PhotoEditStore.Draft(sha,recipe,"Preset",70,PhotoAdjustments.NONE.with(p,1),false).identity());check(ids.size()==13,"All manual values participate in draft identity");
            PhotoEditStore.Draft noFilter=new PhotoEditStore.Draft(sha,null,"",0,manual,false);store.save(noFilter);
            check(store.read(sha).hasEffect()&&store.read(sha).presetBytes().length==0,"Manual-only draft is exportable without a preset");
            check(noFilter.effectPresetSha().matches("[a-f0-9]{64}"),"Manual-only effects have a valid separate source-link identity");
            store.markExported(noFilter);check(store.readPending(sha)==null,"Matching manual export completes its draft");
            Path file;try(var paths=Files.walk(root)){file=paths.filter(p->p.getFileName().toString().equals(sha+".draft")).findFirst().orElseThrow();}
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(buffer)){out.writeInt(0x50454431);out.writeUTF(sha);out.writeUTF("Legacy");out.writeInt(63);out.writeBoolean(false);out.writeInt(recipe.length);out.write(recipe);}
            byte[] body=buffer.toByteArray();buffer.write(MessageDigest.getInstance("SHA-256").digest(body));Files.write(file,buffer.toByteArray());
            PhotoEditStore.Draft legacy=store.read(sha);check(legacy.strength==63&&legacy.adjustments.isNeutral(),"Existing v1 draft migrates with every manual parameter zero");
            check(legacy.profile().modelVersion().equals(StrengthPreviewProfile.of(PresetPreviewProfiles.fromPreset(recipe),63).modelVersion()),"Migrating an old draft does not change its rendering model");
            int[] copy=manual.values();copy[0]=100;check(!manual.equals(new PhotoAdjustments(copy)),"Settings own immutable values");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static int adjust(PhotoAdjustments.Parameter p,int value,int input){return AdjustedPreviewProfile.of(null,PhotoAdjustments.NONE.with(p,value)).applyArgb(input);}
    private static int red(int value){return (value>>>16)&255;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
}

package cc.huevia.album;
import cc.huevia.ncp.*;
import java.nio.file.*;
import java.util.*;

public final class PhotoEditorCoreTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        PresetPreviewProfile base=PresetPreviewProfiles.fromPreset(NcpCompatibilityFixtures.ncp());
        check(StrengthPreviewProfile.of(base,100)==base,"100% preserves the exact legacy profile and identity");
        Set<String> identities=new HashSet<>();for(int strength=0;strength<=100;strength++){
            PresetPreviewProfile p=StrengthPreviewProfile.of(base,strength);check(identities.add(p.modelVersion()),"Different strengths have different model identities");
            for(int color:new int[]{0,0x7f123456,0xff808080,0xffffffff,0xff0099ff}){
                int actual=p.applyArgb(color);check((actual>>>24)==(color>>>24),"Strength keeps original alpha");
                if(strength==0)check(actual==color,"0% preserves the original pixel exactly");
                if(strength==100)check(actual==base.applyArgb(color),"100% is exactly the former full effect");
                for(int shift=0;shift<=16;shift+=8){int a=(color>>>shift)&255,b=(base.applyArgb(color)>>>shift)&255,v=(actual>>>shift)&255;check(v>=Math.min(a,b)&&v<=Math.max(a,b),"Mix remains between source and effect channels");}
            }
        }
        try{StrengthPreviewProfile.of(base,101);throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        spatialStrengthMixesTheCompleteEffect();
        Path root=Files.createTempDirectory("editor-core-");
        try{
            byte[] bytes=NcpCompatibilityFixtures.ncp(),snapshot=bytes.clone();String a="a".repeat(64),b="b".repeat(64);
            PhotoEditStore store=new PhotoEditStore(root.toFile(),"owner-A");PhotoEditStore.Draft first=new PhotoEditStore.Draft(a,bytes,"Recipe A",70,false);store.save(first);
            bytes[0]=0;check(Arrays.equals(first.presetBytes(),snapshot),"Draft owns immutable preset bytes");
            check(store.read(a).identity().equals(first.identity())&&store.read(a).strength==70,"Per-photo draft survives a new disk read");
            check(new PhotoEditStore(root.toFile(),"owner-B").read(a)==null&&new PhotoEditStore(root.toFile(),"").read(a)==null,"Other account and guest cannot see this draft");
            PhotoEditStore.Draft second=new PhotoEditStore.Draft(b,snapshot,"Recipe B",40,false);store.save(second);
            check(store.read(a).strength==70&&store.read(b).strength==40,"Independent photos keep independent strengths");
            store.markExported(first);check(store.readPending(a)==null,"Exported history cannot override a selected saved version");check(store.read(a).exported&&!store.read(b).exported,"Export marks only its exact photo draft");
            PhotoEditStore.Draft changed=new PhotoEditStore.Draft(a,snapshot,"Recipe A",20,false);store.save(changed);check(store.readPending(a).strength==20,"Only unexported edits are selected for batch export");store.markExported(first);check(!store.read(a).exported&&store.read(a).strength==20,"A late export cannot mark a changed draft exported");
            store.save(new PhotoEditStore.Draft(b,null,"原图",0,false));check(!store.read(b).hasEffect()&&store.read(b).presetBytes().length==0,"Explicit original is a durable independent choice");
            Path record=Files.walk(root).filter(p->p.getFileName().toString().equals(a+".draft")).findFirst().orElseThrow();byte[] corrupt=Files.readAllBytes(record);corrupt[corrupt.length-1]^=1;Files.write(record,corrupt);
            try{store.read(a);throw new AssertionError("corrupt accepted");}catch(java.io.IOException expected){checks++;}
            try{store.read("../escape");throw new AssertionError("path accepted");}catch(java.io.IOException expected){checks++;}
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
        System.out.println("PASS: "+checks+" photo editor strength/draft checks");
    }
    private static void spatialStrengthMixesTheCompleteEffect() throws Exception {
        PresetPreviewProfile profile = new PresetPreviewProfile() {
            public String registrationName() { return "Synthetic spatial integration"; }
            public String modelVersion() { return "host-spatial"; }
            public double mapTone(double v) { return v; }
            public int applyArgb(int v) { return (v & 0xff00ffff) | Math.min(255, ((v >>> 16) & 255) + 9) << 16; }
            public SpatialPreviewFilter.Settings spatialSettings() { return new SpatialPreviewFilter.Settings(.7, .5, -.3); }
        };
        int width = 79, height = 59; int[] source = new int[width * height];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int value = 128 + (int) Math.round(38 * Math.sin(x * .2) + 25 * Math.sin(y * .35));
            source[y * width + x] = ((x * 3 + y * 7) & 255) << 24 | value * 0x010101;
        }
        int[] snapshot = source.clone(), full = profile.render(source, width, height);
        check(!Arrays.equals(source, full), "Integration fixture has a real colour and neighbourhood effect");
        for (int strength : new int[] {0, 1, 25, 50, 99, 100}) {
            PresetPreviewProfile partial = StrengthPreviewProfile.of(profile, strength);
            int[] actual = partial.render(source, width, height);
            for (int i = 0; i < source.length; i++)
                check(actual[i] == mix(source[i], full[i], strength), "Strength mixes the complete spatial result after filtering");
            check(Arrays.equals(source, snapshot), "Strength rendering leaves its input untouched");
        }
        int[] inner = StrengthPreviewProfile.of(profile, 63).render(source, width, height);
        int[] nullableCancel = source.clone();
        StrengthPreviewProfile.of(profile, 63).renderPixels(new SpatialPreviewFilter.Pixels() {
            public int width() { return width; } public int height() { return height; }
            public void read(int[] row, int start, int rows) { System.arraycopy(nullableCancel, start * width, row, 0, width * rows); }
            public void write(int[] row, int start, int rows) { System.arraycopy(row, 0, nullableCancel, start * width, width * rows); }
        }, null, null);
        check(Arrays.equals(inner, nullableCancel), "Optional cancellation has the same contract in strength and base profiles");
        int[] nested = StrengthPreviewProfile.of(StrengthPreviewProfile.of(profile, 63), 41).render(source, width, height);
        for (int i = 0; i < source.length; i++) check(nested[i] == mix(source[i], inner[i], 41), "Nested strengths keep exact integer composition");
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(true);
        try {
            StrengthPreviewProfile.of(profile, 0).renderPixels(new SpatialPreviewFilter.Pixels() {
                public int width() { return width; } public int height() { return height; }
                public void read(int[] row, int start, int rows) { throw new AssertionError("Cancelled zero strength read pixels"); }
                public void write(int[] row, int start, int rows) { throw new AssertionError("Cancelled zero strength wrote pixels"); }
            }, cancelled::get, null);
            throw new AssertionError("Cancelled zero-strength processing accepted");
        } catch (java.io.IOException expected) { checks++; }
    }
    private static int mix(int original, int effect, int strength) {
        int result = original & 0xff000000;
        for (int shift = 0; shift <= 16; shift += 8)
            result |= ((((original >>> shift) & 255) * (100 - strength) + ((effect >>> shift) & 255) * strength + 50) / 100) << shift;
        return result;
    }
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
}

package cc.huevia.ncp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Independent 064d compatibility/ownership regressions; not Nikon colour certification. */
public final class Np3StandardMonochromePreviewProfileTest {
    private static int checks;
    private static final String STANDARD_VERSION = "np3-0310-monochrome-spatial-v1";
    private static final String DEEP_VERSION = "np3-0310-deep-tone-monochrome-spatial-v1";
    private static final String ZIP_SHA = "2f0ee976bf8026be25120c89863dbfd727f667a429a5a213c497be3d537b8367";
    private static final String SCOTT_SHA = "ff57c07d5c7df1b6f6ffe031ada0b987f6242043c28a2837dc40e3ebdd14561f";
    private static final String SCOTT_ENTRY = "Nikon-Recipes-main/Third Party Creators/Scott Tucker/Scott's Ilford 400 LO.NP3";
    // Independently audited base/control records; names and curve samples are synthetic.
    private static final String[] FIELDS = {
        "", "30333130", "5354414e44415244204d4f4e4f00000000000000", "064d", "0000", "ff01",
        "8c04", "7d04", "0104", "0104", "ff04", "ff04", "8000", "8000", "ff04",
        "ff01", "ff01", "ff01", "ff01", "ff01", "ff01", "ff0a", "8804", "ff04", "ff04",
        "0101", "0101", "0101", "0101", "0101", "8001",
        "80808080808080808080808080808080808080808080808001010100",
        "80008080800080808000808001010100b2018001"
    };
    // Populated from the unmodified v78 Deep Tone model before 064d implementation.
    private static final String[] DREAMY_POINT_SHA = {
        "9a37ec0ba3179165245e1b85fdf2156323ffa620f80206397a890094788ed89c",
        "722862a9960b32056d55148ed38be1faf15f63654e31d35e4bf96191c6fe9fbe"
    };
    private static final String[] DREAMY_IMAGE_SHA = {
        "1bb3c378d11c70f9cdb486b48576d4bbbdab76100bf53ad9a25615ba8930ca8e",
        "be7f758f2775d874c6ed58b2cb75510baa0df74d46e110084c13890e61cc525d"
    };

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--freeze-dreamy-baseline".equals(args[0])) {
            originalDreamyRegressions(true);
            return;
        }
        boolean parserOnly = args.length > 0 && "--parser-only".equals(args[0]);
        strictBinaryDispatchAndIndependentModelIdentity();
        rawCurveAndEditorMetadataRemainIndependent();
        namesAndCommentsAreDataRatherThanAllowlistKeys();
        unknownOrAmbiguousControlsAreRejected();
        originalScottIsReadOnlyAndMatchesTheAuditedFile(parserOnly);
        originalDreamyRegressions(false);
        if (!parserOnly) {
            renderingPreservesOwnershipAlphaAndRepeatability();
            spatialAndStrengthUseTheCompletedImage();
            frozenPointFormulaAcrossRgbCornersAndPermittedCurveExtremes();
        }
        System.out.println("PASS: " + checks + " standard-monochrome checks" + (parserOnly ? " (parser-only)" : ""));
    }

    /**
     * Independent numerical checks of the frozen application approximation only.
     * The reference interpolates raw curve bytes itself, never mapTone/applyArgb;
     * StrictMath and one-level tolerance allow platform pow rounding differences.
     */
    private static void frozenPointFormulaAcrossRgbCornersAndPermittedCurveExtremes() throws Exception {
        for(int kind=0;kind<4;kind++){
            byte[] curve=identityCurve();
            if(kind==1)for(int i=0;i<=256;i++)sample(curve,i,(i&1)==0?0:32767);
            if(kind==2)for(int i=0;i<=256;i++)sample(curve,i,i<12?0:32767);
            if(kind==3)for(int i=0;i<=256;i++)sample(curve,i,(int)Math.round((.15+.7*(1-StrictMath.cos(i*StrictMath.PI/32))/2)*32767));
            Np3MonochromePreviewProfile p=parse(fixture(curve,true));
            for(int gray=0;gray<256;gray++)point(p,curve,gray<<24|gray*0x010101,kind);
            for(int r=0;r<17;r++)for(int g=0;g<17;g++)for(int b=0;b<17;b++){
                int red=r==16?255:r*16,green=g==16?255:g*16,blue=b==16?255:b*16;
                point(p,curve,0x9d000000|red<<16|green<<8|blue,kind);
            }
            for(int r=0;r<9;r++)for(int g=0;g<9;g++)for(int b=0;b<9;b++)point(p,curve,0x41000000|r<<16|g<<8|b,kind);
            int[] edges={0,1,2,7,127,128,254,255};
            for(int r:edges)for(int g:edges)for(int b:edges)point(p,curve,0xff000000|r<<16|g<<8|b,kind);
            Random random=new Random(0x064d0310L);
            for(int i=0;i<4096;i++)point(p,curve,random.nextInt(),kind);
        }
        Np3MonochromePreviewProfile identity=parse(fixture(identityCurve(),true));
        equal(0xff000000,identity.applyArgb(0xff000000));equal(0xffffffff,identity.applyArgb(0xffffffff));
        int previous=-1;
        for(int gray=0;gray<256;gray++){
            int current=blue(identity.applyArgb(0xff000000|gray*0x010101));
            check(current>=previous,"Frozen standard base response remains monotone on grayscale");previous=current;
        }
    }

    private static void point(Np3MonochromePreviewProfile p,byte[] curve,int pixel,int kind){
        double g=.2126*(red(pixel)/255.0)+.7152*(green(pixel)/255.0)+.0722*(blue(pixel)/255.0);
        g=StrictMath.max(0,StrictMath.min(1,g));double q=StrictMath.pow(g,1.92),position=256*q/(q+.48*(1-g));
        int low=(int)StrictMath.floor(position);double sample=low==256?value(curve,256):value(curve,low)+(value(curve,low+1)-value(curve,low))*(position-low);
        int expected=(int)StrictMath.floor(StrictMath.max(0,StrictMath.min(1,sample/32767.0))*255+.5);
        int actual=p.applyArgb(pixel);gray(actual);equal(pixel>>>24,actual>>>24);
        check(Math.abs(expected-blue(actual))<=1,"Frozen standard formula differs at curve="+kind+" pixel="+Integer.toHexString(pixel)+" expected="+expected+" actual="+blue(actual));
    }

    private static void strictBinaryDispatchAndIndependentModelIdentity() throws Exception {
        byte[] bytes=fixture(identityCurve(),false), copy=bytes.clone();
        Np3MonochromePreviewProfile p=parse(bytes);
        equal("STANDARD MONO",p.registrationName()); equal(STANDARD_VERSION,p.modelVersion());
        check(!DEEP_VERSION.equals(p.modelVersion()),"064d and Deep Tone require separate render identities");
        check(!Np3PreviewProfile.MODEL_VERSION.equals(p.modelVersion()),"064d cannot reuse the Flexible identity");
        check(PresetPreviewProfiles.MODEL_VERSION.contains(STANDARD_VERSION),"Template identity includes 064d model");
        check(PresetPreviewProfiles.fromPreset(bytes) instanceof Np3MonochromePreviewProfile,"Binary factory routes 064d to monochrome");
        near(3,p.sharpening()); near(2,p.midRangeSharpening()); near(-.75,p.clarity());
        near(1.0/3,p.spatialSettings().sharpening); near(.4,p.spatialSettings().midRangeSharpening); near(-.15,p.spatialSettings().clarity);
        equal(copy,bytes); rejected(() -> Np3PreviewProfile.fromNp3(bytes));
        double before=p.mapTone(.413); Arrays.fill(bytes,(byte)0); near(before,p.mapTone(.413));
        for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-.01,1.01}) invalid(() -> p.mapTone(value));
        byte[] lower=fixture(identityCurve(),true);field(lower,6,0,116);field(lower,7,0,108);field(lower,22,0,108);
        PresetPreviewProfile a=parse(lower);near(-1,a.spatialSettings().sharpening);near(-1,a.spatialSettings().clarity);near(-1,a.spatialSettings().midRangeSharpening);
        byte[] upper=fixture(identityCurve(),true);field(upper,6,0,164);field(upper,7,0,148);field(upper,22,0,148);
        PresetPreviewProfile b=parse(upper);near(1,b.spatialSettings().sharpening);near(1,b.spatialSettings().clarity);near(1,b.spatialSettings().midRangeSharpening);
    }

    private static void rawCurveAndEditorMetadataRemainIndependent() throws Exception {
        byte[] curve=identityCurve();
        for(int i=0;i<=256;i++)sample(curve,i,(int)Math.round((.1+.8*i/256.0)*32767));
        sample(curve,128,8192);sample(curve,129,24576);
        Np3MonochromePreviewProfile p=parse(fixture(curve,true));
        for(int i=0;i<=256;i++)near(value(curve,i)/32767.0,p.mapTone(i/256.0));
        near((8192+(24576-8192)*.375)/32767.0,p.mapTone(128.375/256));
        check(p.mapTone(.5)<p.mapTone(127/256.0),"Permitted non-monotone curve sample is preserved");
        byte[] metadata=curve.clone();metadata[2]=12;metadata[3]=(byte)240;metadata[4]=8;metadata[5]=(byte)220;metadata[6]=2;metadata[7]=50;
        Np3MonochromePreviewProfile q=parse(fixture(metadata,true));
        for(int i=0;i<=256;i++)near(p.mapTone(i/256.0),q.mapTone(i/256.0));
    }

    private static void namesAndCommentsAreDataRatherThanAllowlistKeys() throws Exception {
        byte[] a=fixture(identityCurve(),true), b=a.clone();
        int name=offset(b,2);Arrays.fill(b,name,name+20,(byte)0);
        byte[] other="OTHER VALID MONO".getBytes(StandardCharsets.US_ASCII);System.arraycopy(other,0,b,name,other.length);
        equal("OTHER VALID MONO",parse(b).registrationName());
        byte[] changed=fixture(identityCurve(),true);field(changed,6,0,132);
        near(1,parse(changed).sharpening());near(0,parse(a).sharpening());
        byte[] note=new byte[16], utf8="合成 test".getBytes(StandardCharsets.UTF_8);System.arraycopy(utf8,0,note,0,utf8.length);
        byte[] header=fixture(null,true), commented=append(append(header,0x10100,note),2,identityCurve());
        Np3MonochromePreviewProfile p=parse(commented);
        for(int i=0;i<=256;i++)near(i/256.0,p.mapTone(i/256.0),1.0/32767);
        reject(append(append(append(header,0x10100,note),0x10100,note),2,identityCurve()));
        reject(append(a,0x10100,note));
        byte[] bad=note.clone();bad[0]=(byte)0xc0;reject(append(append(header,0x10100,bad),2,identityCurve()));
        bad=note.clone();bad[15]=1;reject(append(append(header,0x10100,bad),2,identityCurve()));
        reject(append(append(header,0x10100,new byte[15]),2,identityCurve()));
        reject(append(append(header,0x10100,new byte[17]),2,identityCurve()));
    }

    private static void unknownOrAmbiguousControlsAreRejected() throws Exception {
        reject(null);reject(new byte[1024*1024+1]);byte[] valid=fixture(identityCurve(),true);
        for(int size=0;size<valid.length;size++)reject(Arrays.copyOf(valid,size));
        reject(fixture(null,true));
        for(int[] m:new int[][]{
            {1,0,'9'},{3,0,0},{3,1,0x55},{3,1,0x4e},{3,0,8},{4,0,1},{5,0,128},{5,1,4},
            {6,0,115},{6,0,165},{6,0,1},{6,1,1},{7,0,107},{7,0,149},{7,1,1},
            {22,0,107},{22,0,149},{22,0,255},{22,1,1},{8,0,128},{8,0,255},{9,0,128},{9,1,1},
            {10,0,128},{11,0,128},{12,0,0x81},{12,0,0x82},{12,0,0x83},{12,0,0x84},{12,0,0x85},{12,0,255},{12,1,1},
            {13,0,0x7f},{13,0,0x81},{13,0,255},{13,1,1},{14,0,128},{15,0,128},{16,0,128},{17,0,128},{18,0,128},{19,0,128},
            {20,0,128},{20,0,127},{20,0,129},{20,1,4},{21,0,128},{23,0,128},{24,0,128},
            {25,0,128},{26,0,128},{27,0,128},{28,0,128},{29,0,128},{30,0,129},{30,1,4},
            {31,0,129},{31,23,127},{31,24,4},{31,27,1},{32,0,129},{32,1,1},{32,2,129},{32,11,129},{32,12,4},{32,16,179},{32,18,129},{32,19,0}
        }){byte[] bad=valid.clone();field(bad,m[0],m[1],m[2]);reject(bad);}
        for(int[] m:new int[][]{{0,'X'},{7,2},{11,3},{24,1},{43,1}}){byte[] bad=valid.clone();bad[m[0]]=(byte)m[1];reject(bad);}
        byte[] empty=valid.clone();Arrays.fill(empty,offset(empty,2),offset(empty,2)+20,(byte)0);reject(empty);
        reject(Arrays.copyOf(valid,valid.length+1));reject(append(valid,99,new byte[16]));reject(append(valid,2,identityCurve()));
        for(int[] m:new int[][]{{0,0},{1,0},{2,255},{6,21},{7,101},{8,21},{11,0},{63,1},{64,128}}){byte[] c=identityCurve();c[m[0]]=(byte)m[1];reject(fixture(c,true));}
        byte[] levels=identityCurve();levels[4]=(byte)255;levels[5]=0;reject(fixture(levels,true));
        // Same registration name cannot turn an unknown base into a supported file.
        byte[] foreign=valid.clone();field(foreign,3,0,8);reject(foreign);
    }

    private static void renderingPreservesOwnershipAlphaAndRepeatability() throws Exception {
        byte[] bytes=fixture(identityCurve(),false),copy=bytes.clone();Np3MonochromePreviewProfile p=parse(bytes);
        int[] input=image(43,37),before=input.clone(),output=p.render(input,43,37);
        check(output!=input,"Render output ownership is independent");equal(before,input);equal(copy,bytes);
        int differences=0;for(int i=0;i<input.length;i++){gray(output[i]);equal(input[i]>>>24,output[i]>>>24);if(input[i]!=output[i])differences++;}
        check(differences>0,"Supported mono produces a visible effect");equal(output,p.render(input,43,37));
        Arrays.fill(bytes,(byte)0);equal(output,p.render(input,43,37));int first=output[0];output[0]^=0xffffff;equal(first,p.render(input,43,37)[0]);
        for(int alpha=0;alpha<=255;alpha++){int pixel=alpha<<24|0xa04f38,result=p.applyArgb(pixel);gray(result);equal(alpha,result>>>24);equal(p.applyArgb(0xffa04f38)&0xffffff,result&0xffffff);}
        invalid(() -> p.render(null,1,1));invalid(() -> p.render(new int[1],0,1));invalid(() -> p.render(new int[1],2,2));
        ArrayPixels pixels=new ArrayPixels(input.clone(),43,37);rejected(() -> p.renderPixels(pixels,() -> true,null));equal(0,pixels.writes);
        byte[] flat=identityCurve();for(int i=0;i<=256;i++)sample(flat,i,16384);Np3MonochromePreviewProfile fixed=parse(fixture(flat,true));
        int expected=fixed.applyArgb(0xff000000);for(int c:new int[]{0,0xffffff,0xff0000,0x00ff00,0x0000ff,0x735cb2})equal(expected,fixed.applyArgb(0xff000000|c));
    }

    private static void spatialAndStrengthUseTheCompletedImage() throws Exception {
        Np3MonochromePreviewProfile zero=parse(fixture(identityCurve(),true)),spatial=parse(fixture(identityCurve(),false));
        int[] input=image(61,47),original=input.clone(),point=zero.render(input,61,47),full=spatial.render(input,61,47);
        check(!Arrays.equals(point,full),"Nonzero spatial settings change completed monochrome output");
        for(int i=0;i<input.length;i++){equal(zero.applyArgb(input[i]),point[i]);equal(zero.applyArgb(input[i]),spatial.applyArgb(input[i]));gray(full[i]);}
        for(int strength:new int[]{0,37,100}){
            PresetPreviewProfile mixed=StrengthPreviewProfile.of(spatial,strength);int[] result=mixed.render(input,61,47);int coloured=0;
            check(strength==100?mixed.modelVersion().equals(STANDARD_VERSION):!mixed.modelVersion().equals(STANDARD_VERSION),"Each strength has the correct output identity");
            for(int i=0;i<input.length;i++){equal(mix(input[i],full[i],strength),result[i]);equal(input[i]>>>24,result[i]>>>24);if(red(result[i])!=green(result[i])||green(result[i])!=blue(result[i]))coloured++;}
            if(strength==37)check(coloured>0,"Partial strength retains source colour");
        }
        ArrayPixels untouched=new ArrayPixels(input.clone(),61,47);StrengthPreviewProfile.of(spatial,0).renderPixels(untouched,() -> false,null);equal(0,untouched.reads);equal(0,untouched.writes);
        int[] nested=StrengthPreviewProfile.of(StrengthPreviewProfile.of(spatial,61),37).render(input,61,47);
        for(int i=0;i<input.length;i++)equal(mix(input[i],mix(input[i],full[i],61),37),nested[i]);equal(original,input);
    }

    private static void originalScottIsReadOnlyAndMatchesTheAuditedFile(boolean parserOnly) throws Exception {
        File archive=archive();if(!archive.isFile()){System.out.println("SKIP: full local NP3 corpus absent; original Scott not downloaded or embedded");return;}
        equal(ZIP_SHA,sha(Files.readAllBytes(archive.toPath())));
        try(ZipFile zip=new ZipFile(archive,StandardCharsets.UTF_8)){
            byte[] bytes=read(zip,SCOTT_ENTRY),before=bytes.clone();equal(SCOTT_SHA,sha(bytes));equal(1036,bytes.length);
            Np3MonochromePreviewProfile p=parse(bytes);equal("Scott's Ilford LO",p.registrationName());equal(STANDARD_VERSION,p.modelVersion());
            near(3,p.sharpening());near(2,p.midRangeSharpening());near(-.75,p.clarity());
            check(PresetPreviewProfiles.fromPreset(bytes) instanceof Np3MonochromePreviewProfile,"Original Scott routes to monochrome");
            if(!parserOnly){int[] input=image(37,29),copy=input.clone(),output=p.render(input,37,29);for(int i=0;i<output.length;i++){gray(output[i]);equal(input[i]>>>24,output[i]>>>24);}equal(copy,input);equal(output,p.render(input,37,29));}
            equal(before,bytes);equal(SCOTT_SHA,sha(read(zip,SCOTT_ENTRY)));
        }
    }

    private static void originalDreamyRegressions(boolean freeze) throws Exception {
        File archive=archive();if(!archive.isFile()){System.out.println("SKIP: full local NP3 corpus absent; original Dreamy regression not downloaded or embedded");return;}
        String prefix="Nikon-Recipes-main/Third Party Creators/Mark G Adams/Nikon Premium Recipes/";
        try(ZipFile zip=new ZipFile(archive,StandardCharsets.UTF_8)){
            String[] names={"Premium Dreamy Mono.NP3","Premium Dreamy Peep.NP3"};
            for(int k=0;k<names.length;k++){
                byte[] bytes=read(zip,prefix+names[k]),before=bytes.clone();Np3MonochromePreviewProfile p=parse(bytes);equal(DEEP_VERSION,p.modelVersion());
                MessageDigest point=MessageDigest.getInstance("SHA-256");Random random=new Random(0x064d0310L);
                for(int i=0;i<4096;i++)update(point,p.applyArgb(random.nextInt()));
                String pointSha=HexFormat.of().formatHex(point.digest()),imageSha=sha(p.render(image(61,47),61,47));
                if(freeze)System.out.println(names[k]+" point="+pointSha+" image="+imageSha);
                else{equal(DREAMY_POINT_SHA[k],pointSha);equal(DREAMY_IMAGE_SHA[k],imageSha);}
                equal(before,bytes);
            }
        }
    }

    private static File archive(){return new File("dist/nikon-cloud-recipes-20261018/nikon-recipes-main.zip");}
    private static Np3MonochromePreviewProfile parse(byte[] b)throws IOException{return Np3MonochromePreviewProfile.fromNp3(b);}
    private static byte[] fixture(byte[] curve,boolean zero)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{'N','C','P',0});
        for(int i=1;i<=32;i++){byte[] data=HexFormat.of().parseHex(FIELDS[i]);if(zero&&(i==6||i==7||i==22))data[0]=(byte)128;write(out,i*256,data);}
        if(curve!=null)write(out,2,curve);u32(out,0);return out.toByteArray();
    }
    private static byte[] identityCurve(){byte[] b=new byte[578];b[0]=0x49;b[1]=0x30;b[3]=(byte)255;b[5]=(byte)255;b[6]=1;b[8]=2;b[9]=0;b[10]=0;b[11]=(byte)255;b[12]=(byte)255;for(int i=0;i<=256;i++)sample(b,i,(int)Math.round(i*32767.0/256));return b;}
    private static void sample(byte[] b,int i,int v){b[64+i*2]=(byte)(v>>>8);b[65+i*2]=(byte)v;}
    private static int value(byte[] b,int i){return (b[64+i*2]&255)*256+(b[65+i*2]&255);}
    private static byte[] append(byte[] b,int id,byte[] payload)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(b,0,b.length-4);write(out,id,payload);u32(out,0);return out.toByteArray();}
    private static void write(ByteArrayOutputStream out,int id,byte[] b)throws IOException{u32(out,id);u32(out,b.length);out.write(b);}
    private static void u32(ByteArrayOutputStream out,int n){out.write(n>>>24);out.write(n>>>16);out.write(n>>>8);out.write(n);}
    private static int offset(byte[] b,int wanted){int o=4;for(int i=1;i<=32;i++){int id=read32(b,o),length=read32(b,o+4);o+=8;if(id==wanted*256)return o;o+=length;}throw new AssertionError("Missing field");}
    private static int read32(byte[] b,int o){return (b[o]&255)<<24|(b[o+1]&255)<<16|(b[o+2]&255)<<8|(b[o+3]&255);}
    private static void field(byte[] b,int id,int byteIndex,int value){b[offset(b,id)+byteIndex]=(byte)value;}
    private static byte[] read(ZipFile zip,String name)throws IOException{ZipEntry e=zip.getEntry(name);if(e==null)throw new AssertionError("Missing real-recipe ZIP entry");try(var in=zip.getInputStream(e)){return in.readAllBytes();}}
    private static int[] image(int w,int h){int[] result=new int[w*h];Random random=new Random(0x064dL);for(int i=0;i<result.length;i++)result[i]=random.nextInt();return result;}
    private static int mix(int a,int b,int strength){int result=a&0xff000000;for(int shift=0;shift<=16;shift+=8)result|=((((a>>>shift)&255)*(100-strength)+((b>>>shift)&255)*strength+50)/100)<<shift;return result;}
    private static int red(int p){return p>>>16&255;}private static int green(int p){return p>>>8&255;}private static int blue(int p){return p&255;}
    private static void gray(int p){check(red(p)==green(p)&&green(p)==blue(p),"Completed full-strength effect is monochrome");}
    private static String sha(byte[] b)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}
    private static String sha(int[] pixels)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");for(int p:pixels)update(md,p);return HexFormat.of().formatHex(md.digest());}
    private static void update(MessageDigest md,int p){md.update(new byte[]{(byte)(p>>>24),(byte)(p>>>16),(byte)(p>>>8),(byte)p});}
    private static void reject(byte[] b)throws Exception{rejected(() -> parse(b));rejected(() -> PresetPreviewProfiles.fromPreset(b));}
    private interface Throwing{void run()throws Exception;}
    private static void rejected(Throwing task)throws Exception{try{task.run();throw new AssertionError("Expected explicit IOException");}catch(IOException expected){check(expected.getMessage()!=null&&!expected.getMessage().isBlank(),"Rejection has a reason");}}
    private static void invalid(Throwing task)throws Exception{try{task.run();throw new AssertionError("Expected invalid argument");}catch(IllegalArgumentException expected){checks++;}}
    private static void near(double expected,double actual){near(expected,actual,1e-12);}
    private static void near(double expected,double actual,double tolerance){check(Math.abs(expected-actual)<=tolerance,"Expected "+expected+" got "+actual);}
    private static void equal(Object expected,Object actual){check(expected.equals(actual),"Expected "+expected+" got "+actual);}
    private static void equal(byte[] expected,byte[] actual){check(Arrays.equals(expected,actual),"Byte input was changed");}
    private static void equal(int[] expected,int[] actual){check(Arrays.equals(expected,actual),"Pixel arrays differ");}
    private static void check(boolean condition,String reason){checks++;if(!condition)throw new AssertionError(reason);}
    private static final class ArrayPixels implements SpatialPreviewFilter.Pixels{
        private final int[] data;private final int w,h;int reads,writes;ArrayPixels(int[] data,int w,int h){this.data=data;this.w=w;this.h=h;}
        public int width(){return w;}public int height(){return h;}
        public void read(int[] target,int first,int rows){reads++;System.arraycopy(data,first*w,target,0,rows*w);}
        public void write(int[] source,int first,int rows){writes++;System.arraycopy(source,0,data,first*w,rows*w);}
    }
}

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

/** Strict Deep Tone subset and pipeline regressions; no claim of Nikon pixel fidelity. */
public final class Np3MonochromePreviewProfileTest {
    private static int checks;
    private static final String FC_VERSION = "np3-0310-encoded-hsl-zones-spatial-v3";
    // Frozen before introducing monochrome, from the previously verified v3 classes.
    private static final String[] FC_OUTPUT_SHA = {
        "ca3809b29422a90ad4148deaf1164beaf5fab622f918fe010c8b20f85713f310",
        "e58e017c1216026333824006a94b675e2f16b131fba595593c81c06c530d2b2f",
        "457843d79256a1413323cc82cd9dd9f5e0bb66b55510abd609ab120146de6eed"
    };
    // Exact non-name records from the independent Dreamy byte audit. Curve samples
    // below are synthetic; third-party recipe bytes are read only from the local ZIP.
    private static final String[] MONO_FIELDS = {
        "", "30333130", "484f5354204d4f4e4f0000000000000000000000", "0655", "0000", "ff01",
        "8404", "8004", "0104", "0104", "ff04", "ff04", "8400", "8000", "ff04",
        "ff01", "ff01", "ff01", "ff01", "ff01", "ff01", "ff0a", "7c04", "ff04", "ff04",
        "0101", "0101", "0101", "0101", "0101", "8001",
        "80808080808080808080808080808080808080808080808001010100",
        "80008080800080808000808001010100b2018001"
    };

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--parser-only".equals(args[0])) {
            parsingAndSpatialMetadataAreIndependentOfThePendingPixelTransform();
            curveSamplesAndMetadataAreNotAppliedTwice(false);
            commentsHaveBoundedUtf8AndUnambiguousOrder();
            strictUnsupportedAndMalformedRecordsFail();
            oldFlexibleColourOutputsAndDispatchRemainUnchanged();
            originalDreamyPairIsCheckedReadOnly(Arrays.copyOfRange(args,1,args.length),false);
            System.out.println("PASS: " + checks + " monochrome parser/curve and old-FC checks; monochrome pixels not evaluated");
            return;
        }
        supportedFiltersHaveSeparateIdentityAndVisibleDifference();
        curveSamplesAndMetadataAreNotAppliedTwice(true);
        pointTransformKeepsTheFixedFormulaAcrossPermittedCurveExtremes();
        commentsHaveBoundedUtf8AndUnambiguousOrder();
        ownershipAlphaGrayAndRenderRepeatability();
        spatialAndStrengthUseTheCompleteImage();
        strictUnsupportedAndMalformedRecordsFail();
        oldFlexibleColourOutputsAndDispatchRemainUnchanged();
        originalDreamyPairIsCheckedReadOnly(args,true);
        System.out.println("PASS: " + checks + " strict monochrome and existing-FC regression checks");
    }

    /** Numerical shortcut fidelity to the frozen app formula, not to Nikon rendering. */
    private static void pointTransformKeepsTheFixedFormulaAcrossPermittedCurveExtremes() throws Exception {
        for (int curveKind=0;curveKind<3;curveKind++) for (int filter : new int[] {0x83,0x84}) {
            byte[] curve=identityCurve();
            if(curveKind==1) for(int i=0;i<=256;i++) sample(curve,i,(i&1)==0?0:32767);
            if(curveKind==2) for(int i=0;i<=256;i++) sample(curve,i,i<12?0:32767);
            Np3MonochromePreviewProfile profile=parse(fixture(filter,curve,true));
            Random random=new Random(655L);
            for(int i=0;i<8450;i++) {
                int pixel=i<256 ? 0xff000000 | i*0x010101 : i==256 ? 0x9d002427 : i==257 ? 0x411f0813 : random.nextInt();
                double linear=filter==0x83
                        ? .80*linear(red(pixel))+.18*linear(green(pixel))+.02*linear(blue(pixel))
                        : .15*linear(red(pixel))+.80*linear(green(pixel))+.05*linear(blue(pixel));
                linear=Math.max(0,Math.min(1,linear));
                double encoded=linear<=.0031308 ? 12.92*linear : 1.055*Math.pow(linear,1/2.4)-.055;
                double mapped=profile.mapTone(Math.pow(Math.max(0,Math.min(1,encoded)),1.35));
                int reference=(int)Math.round(Math.max(0,Math.min(1,mapped))*255);
                int actual=blue(profile.applyArgb(pixel));
                check(Math.abs(reference-actual)<=1,"Monochrome shortcut distorts a permitted curve: kind="+curveKind
                        +" filter="+filter+" pixel="+Integer.toHexString(pixel)+" actual="+actual+" exact="+reference);
            }
        }
    }

    private static double linear(int channel) {
        double value=channel/255.0;return value<=.04045 ? value/12.92 : Math.pow((value+.055)/1.055,2.4);
    }

    private static void parsingAndSpatialMetadataAreIndependentOfThePendingPixelTransform() throws Exception {
        for (int filter : new int[] {0x83,0x84}) {
            byte[] bytes=fixture(filter,identityCurve(),false), before=bytes.clone();
            Np3MonochromePreviewProfile profile=parse(bytes);
            equal("HOST MONO",profile.registrationName());equal(Np3MonochromePreviewProfile.MODEL_VERSION,profile.modelVersion());
            near(1.0/9,profile.spatialSettings().sharpening);near(-.2,profile.spatialSettings().midRangeSharpening);near(0,profile.spatialSettings().clarity);
            check(Arrays.equals(before,bytes),"Parser does not modify input bytes");
            double mapped=profile.mapTone(.37);Arrays.fill(bytes,(byte)0);near(mapped,profile.mapTone(.37));
            for(double invalid : new double[] {Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-.1,1.1}) invalid(() -> profile.mapTone(invalid));
        }
        byte[] minimum=fixture(0x83,identityCurve(),true);field(minimum,6,0,116);field(minimum,7,0,108);field(minimum,22,0,108);
        PresetPreviewProfile lower=parse(minimum);near(-1,lower.spatialSettings().sharpening);near(-1,lower.spatialSettings().clarity);near(-1,lower.spatialSettings().midRangeSharpening);
        byte[] maximum=fixture(0x84,identityCurve(),true);field(maximum,6,0,164);field(maximum,7,0,148);field(maximum,22,0,148);
        PresetPreviewProfile upper=parse(maximum);near(1,upper.spatialSettings().sharpening);near(1,upper.spatialSettings().clarity);near(1,upper.spatialSettings().midRangeSharpening);
    }

    private static void supportedFiltersHaveSeparateIdentityAndVisibleDifference() throws Exception {
        Np3MonochromePreviewProfile red = mono(0x83), green = mono(0x84);
        equal("HOST MONO", red.registrationName());
        equal(Np3MonochromePreviewProfile.MODEL_VERSION, red.modelVersion());
        check(!FC_VERSION.equals(red.modelVersion()), "Monochrome cannot share the Flexible model identity");
        check(!NcpPreviewProfile.MODEL_VERSION.equals(red.modelVersion()), "Monochrome cannot share the legacy NCP identity");
        check(PresetPreviewProfiles.MODEL_VERSION.contains(red.modelVersion()), "Template identity includes the monochrome model");
        check(PresetPreviewProfiles.MODEL_VERSION.contains(FC_VERSION), "Template identity retains the Flexible model");
        check(PresetPreviewProfiles.MODEL_VERSION.contains(NcpPreviewProfile.MODEL_VERSION), "Template identity retains the legacy NCP model");
        byte[] bytes = fixture(0x83, identityCurve(), true);
        PresetPreviewProfile dispatched = PresetPreviewProfiles.fromPreset(bytes);
        check(dispatched instanceof Np3MonochromePreviewProfile, "Binary factory dispatches the strict monochrome subset");
        int differences = 0;
        for (int rgb : new int[] {0xff0000,0x00ff00,0x0000ff,0xff8000,0x50b070,0x805050,0x1234ef}) {
            int a = red.applyArgb(0xff000000 | rgb), b = green.applyArgb(0xff000000 | rgb);
            gray(a); gray(b); if (a != b) differences++;
            equal(a, dispatched.applyArgb(0xff000000 | rgb));
        }
        check(differences > 0, "Verified red and green settings must not collapse to the same filter");
    }

    private static void curveSamplesAndMetadataAreNotAppliedTwice(boolean withPixels) throws Exception {
        byte[] curve = identityCurve();
        for (int i = 0; i <= 256; i++) sample(curve,i,(int)Math.round((.1 + .8 * i / 256.0) * 32767));
        sample(curve,128,8192); sample(curve,129,24576);
        Np3MonochromePreviewProfile profile = parse(fixture(0x84,curve,true));
        for (int i = 0; i <= 256; i++) near(sampleValue(curve,i) / 32767.0,profile.mapTone(i / 256.0));
        near((8192 + (24576 - 8192) * .25) / 32767.0,profile.mapTone(128.25 / 256.0));
        check(profile.mapTone(.5) < profile.mapTone(127 / 256.0), "Legal non-monotone samples are retained");
        byte[] metadata = curve.clone(); metadata[2]=12; metadata[3]=(byte)240; metadata[4]=8; metadata[5]=(byte)220;
        metadata[6]=2; metadata[7]=50;
        Np3MonochromePreviewProfile same = parse(fixture(0x84,metadata,true));
        for (int i=0;i<=256;i++) near(profile.mapTone(i / 256.0),same.mapTone(i / 256.0));
        if (!withPixels) return;
        Random random = new Random(655L);
        for (int i=0;i<2048;i++) {int pixel=random.nextInt(); equal(profile.applyArgb(pixel),same.applyArgb(pixel));}
        byte[] flat = identityCurve(); for (int i=0;i<=256;i++) sample(flat,i,16384);
        Np3MonochromePreviewProfile flatProfile = parse(fixture(0x83,flat,true));
        int flatResult = flatProfile.applyArgb(0xff000000); gray(flatResult);
        for (int rgb : new int[] {0,0xffffff,0xff0000,0x00ff00,0x0000ff,0x735cb2})
            equal(flatResult,flatProfile.applyArgb(0xff000000 | rgb));
    }

    private static void commentsHaveBoundedUtf8AndUnambiguousOrder() throws Exception {
        byte[] note=new byte[16], text="合成 test".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(text,0,note,0,text.length);
        byte[] base=fixture(0x84,null,true), commented=append(append(base,0x10100,note),2,identityCurve());
        Np3MonochromePreviewProfile profile=parse(commented), plain=mono(0x84);
        for(int i=0;i<=256;i++)near(plain.mapTone(i/256.0),profile.mapTone(i/256.0));
        reject(append(append(append(base,0x10100,note),0x10100,note),2,identityCurve()));
        reject(append(fixture(0x84,identityCurve(),true),0x10100,note));
        byte[] bad=note.clone();bad[0]=(byte)0xc0;reject(append(append(base,0x10100,bad),2,identityCurve()));
        bad=note.clone();bad[15]=1;reject(append(append(base,0x10100,bad),2,identityCurve()));
        reject(append(append(base,0x10100,new byte[15]),2,identityCurve()));
        reject(append(append(base,0x10100,new byte[17]),2,identityCurve()));
    }

    private static void ownershipAlphaGrayAndRenderRepeatability() throws Exception {
        byte[] bytes=fixture(0x84,identityCurve(),false), originalBytes=bytes.clone();
        Np3MonochromePreviewProfile profile=parse(bytes);
        check(Arrays.equals(bytes,originalBytes),"Parsing does not alter the original NP3");
        int[] input=image(43,37), original=input.clone(), output=profile.render(input,43,37);
        check(output != input,"Render returns independently owned output"); equal(original,input);
        for (int i=0;i<input.length;i++) {equal(input[i] >>> 24,output[i] >>> 24); gray(output[i]);}
        equal(output,profile.render(input,43,37));
        Arrays.fill(bytes,(byte)0); equal(output,profile.render(input,43,37));
        int first=output[0]; output[0]^=0x00ffffff;
        equal(first,profile.render(input,43,37)[0]); equal(original,input);
        for (int alpha=0;alpha<=255;alpha++) {
            int pixel=alpha << 24 | 0x00a04f38, result=profile.applyArgb(pixel);
            equal(alpha,result >>> 24); gray(result);
            equal(profile.applyArgb(0xffa04f38) & 0xffffff,result & 0xffffff);
        }
        for (double value : new double[] {Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,-.1,1.1})
            invalid(() -> profile.mapTone(value));
        invalid(() -> profile.render(null,1,1)); invalid(() -> profile.render(new int[1],0,1));
        invalid(() -> profile.render(new int[1],2,2)); invalid(() -> profile.render(new int[1],Integer.MAX_VALUE,Integer.MAX_VALUE));
        ArrayPixels cancelled = new ArrayPixels(input.clone(),43,37);
        rejected(() -> profile.renderPixels(cancelled,() -> true,null));
        equal(0,cancelled.writes);
    }

    private static void spatialAndStrengthUseTheCompleteImage() throws Exception {
        byte[] zeroBytes=fixture(0x83,identityCurve(),true), strongBytes=zeroBytes.clone();
        field(strongBytes,6,0,164); field(strongBytes,7,0,148); field(strongBytes,22,0,148);
        Np3MonochromePreviewProfile zero=parse(zeroBytes), strong=parse(strongBytes);
        int[] input=image(61,47), colour=zero.render(input,61,47), full=strong.render(input,61,47);
        for (int i=0;i<input.length;i++) {
            equal(zero.applyArgb(input[i]),colour[i]); equal(zero.applyArgb(input[i]),strong.applyArgb(input[i]));
            gray(full[i]); equal(input[i] >>> 24,full[i] >>> 24);
        }
        check(!Arrays.equals(colour,full),"Spatial settings affect the completed monochrome image");
        PresetPreviewProfile off=StrengthPreviewProfile.of(strong,0);
        equal(input,off.render(input,61,47));
        ArrayPixels untouched=new ArrayPixels(input.clone(),61,47); off.renderPixels(untouched,() -> false,null);
        equal(0,untouched.reads); equal(0,untouched.writes);
        for (int strength : new int[] {0,37,100}) {
            PresetPreviewProfile mixed=StrengthPreviewProfile.of(strong,strength);
            check(strength==100 ? mixed.modelVersion().equals(strong.modelVersion()) : !mixed.modelVersion().equals(strong.modelVersion()),
                    "Strength changes must preserve independent output identities");
            int[] result=mixed.render(input,61,47); int coloured=0;
            for (int i=0;i<input.length;i++) {
                equal(mix(input[i],full[i],strength),result[i]);
                equal(mix(input[i],strong.applyArgb(input[i]),strength),mixed.applyArgb(input[i]));
                equal(input[i] >>> 24,result[i] >>> 24);
                if (red(result[i]) != green(result[i]) || green(result[i]) != blue(result[i])) coloured++;
            }
            if (strength==37) check(coloured>0,"Partial strength correctly retains original colour");
        }
        PresetPreviewProfile nested=StrengthPreviewProfile.of(StrengthPreviewProfile.of(strong,61),37);
        int[] nestedResult=nested.render(input,61,47);
        for (int i=0;i<input.length;i++) equal(mix(input[i],mix(input[i],full[i],61),37),nestedResult[i]);
    }

    private static void strictUnsupportedAndMalformedRecordsFail() throws Exception {
        reject(null); reject(new byte[1024*1024+1]);
        byte[] valid=fixture(0x83,identityCurve(),true);
        for (int length=0;length<valid.length;length++) reject(Arrays.copyOf(valid,length));
        // No-curve semantics are deliberately outside the initial evidenced subset.
        reject(fixture(0x83,null,true));
        for (int[] mutation : new int[][] {
                {1,0,'9'},{3,0,0},{3,1,0x4d},{3,0,8},{4,0,1},{5,0,128},{5,1,4},
                {6,0,115},{6,0,165},{6,0,1},{6,1,1},{7,0,107},{7,0,149},{7,1,1},
                {22,0,107},{22,0,149},{22,0,255},{22,1,1},
                {8,0,128},{8,0,255},{9,0,128},{9,1,1},{10,0,128},{11,0,128},
                {12,0,0x80},{12,0,0x81},{12,0,0x82},{12,0,0x85},{12,0,255},{12,1,1},
                {13,0,0x7f},{13,0,0x81},{13,0,255},{13,1,1},{14,0,128},
                {15,0,128},{16,0,128},{17,0,128},{18,0,128},{19,0,128},
                {20,0,128},{20,0,127},{20,0,129},{20,1,4},{21,0,128},{23,0,128},{24,0,128},
                {25,0,128},{26,0,128},{27,0,128},{28,0,128},{29,0,128},{30,0,129},{30,1,4},
                {31,0,129},{31,23,127},{31,24,4},{31,27,1},{32,0,129},{32,1,1},{32,2,129},
                {32,11,129},{32,12,4},{32,16,179},{32,18,129},{32,19,0}
        }) {byte[] bad=valid.clone();field(bad,mutation[0],mutation[1],mutation[2]);reject(bad);}
        for (int[] mutation : new int[][] {{0,'X'},{7,2},{11,3},{24,1},{43,1}}) {
            byte[] bad=valid.clone();bad[mutation[0]]=(byte)mutation[1];reject(bad);
        }
        byte[] emptyName=valid.clone();Arrays.fill(emptyName,fieldOffset(emptyName,2),fieldOffset(emptyName,2)+20,(byte)0);reject(emptyName);
        reject(Arrays.copyOf(valid,valid.length+1));
        reject(append(valid,99,new byte[16])); reject(append(valid,2,identityCurve()));
        for (int[] mutation : new int[][] {{0,0},{1,0},{2,255},{6,21},{7,101},{8,21},{11,0},{63,1},{64,128}}) {
            byte[] curve=identityCurve();curve[mutation[0]]=(byte)mutation[1];reject(fixture(0x83,curve,true));
        }
        byte[] levels=identityCurve();levels[4]=(byte)255;levels[5]=0;reject(fixture(0x84,levels,true));
        // Unrelated legacy/FC values cannot silently bypass the strict subset guards.
        for (int index : new int[] {8,9,10,11,14,23,24,25,26,27,28,29,30}) {
            byte[] bad=valid.clone();field(bad,index,0,0);reject(bad);
        }
    }

    private static void oldFlexibleColourOutputsAndDispatchRemainUnchanged() throws Exception {
        for (int mode=0;mode<3;mode++) {
            byte[] bytes=flexibleFixture(mode);
            Np3PreviewProfile direct=Np3PreviewProfile.fromNp3(bytes);
            PresetPreviewProfile factory=PresetPreviewProfiles.fromPreset(bytes);
            check(factory instanceof Np3PreviewProfile,"Existing Flexible binary dispatch stays in its original class");
            equal(FC_VERSION,direct.modelVersion()); equal(FC_VERSION,factory.modelVersion());
            MessageDigest output=MessageDigest.getInstance("SHA-256"); Random random=new Random(0x06550310L);
            for (int i=0;i<4096;i++) {
                int pixel=random.nextInt(), value=direct.applyArgb(pixel); equal(value,factory.applyArgb(pixel));
                output.update(new byte[] {(byte)(value>>>24),(byte)(value>>>16),(byte)(value>>>8),(byte)value});
            }
            equal(FC_OUTPUT_SHA[mode],HexFormat.of().formatHex(output.digest()));
            rejected(() -> Np3MonochromePreviewProfile.fromNp3(bytes));
        }
        byte[] mono=fixture(0x83,identityCurve(),true);
        rejected(() -> Np3PreviewProfile.fromNp3(mono));
    }

    private static void originalDreamyPairIsCheckedReadOnly(String[] args,boolean withPixels) throws Exception {
        File archive=new File(args.length==0 ? "dist/nikon-cloud-recipes-20261018/nikon-recipes-main.zip" : args[0]);
        if (!archive.isFile()) {
            if (args.length>0) throw new AssertionError("Requested real-recipe ZIP is absent");
            System.out.println("SKIP: local full NP3 ZIP absent; no recipe was downloaded or embedded"); return;
        }
        equal("2f0ee976bf8026be25120c89863dbfd727f667a429a5a213c497be3d537b8367",sha(Files.readAllBytes(archive.toPath())));
        String folder="Nikon-Recipes-main/Third Party Creators/Mark G Adams/Nikon Premium Recipes/";
        try (ZipFile zip=new ZipFile(archive,StandardCharsets.UTF_8)) {
            byte[] mono=read(zip,folder+"Premium Dreamy Mono.NP3"), peep=read(zip,folder+"Premium Dreamy Peep.NP3");
            equal("886cc2b108050b2d7c27052cd17f005d6f98800bb577493971a0f221c247313d",sha(mono));
            equal("d3774968a0c3919ba7e4ddabd876bb84d9a77c3d733d7cfad53fcdda937e26af",sha(peep));
            equal(978,mono.length); equal(978,peep.length);
            byte[] a=mono.clone(), b=peep.clone(); Np3MonochromePreviewProfile green=parse(mono), red=parse(peep);
            equal("Premium Dreamy Mono",green.registrationName()); equal("Premium Dreamy Peep",red.registrationName());
            if (withPixels) {
                check(PresetPreviewProfiles.fromPreset(mono) instanceof Np3MonochromePreviewProfile,"Original Mono dispatches");
                check(PresetPreviewProfiles.fromPreset(peep) instanceof Np3MonochromePreviewProfile,"Original Peep dispatches");
                int different=0; Random random=new Random(0x655L);
                for (int i=0;i<4096;i++) {
                    int pixel=random.nextInt(), m=green.applyArgb(pixel), p=red.applyArgb(pixel);
                    gray(m);gray(p);equal(pixel>>>24,m>>>24);equal(pixel>>>24,p>>>24);if(m!=p)different++;
                }
                check(different>0,"Original Dreamy pair retains its distinct verified filters");
            }
            for (int i=0;i<=256;i++) near(green.mapTone(i/256.0),red.mapTone(i/256.0));
            check(Arrays.equals(a,mono) && Arrays.equals(b,peep),"Real recipe bytes remain unchanged");
            if(withPixels) completeAuditedCatalogKeepsTheExpectedStrictSubset(zip);
        }
    }

    private static void completeAuditedCatalogKeepsTheExpectedStrictSubset(ZipFile zip) throws Exception {
        int total=0,flexible=0,monochrome=0,rejected=0;
        var entries=zip.entries();
        while(entries.hasMoreElements()) {
            ZipEntry entry=entries.nextElement();
            if(entry.isDirectory() || !entry.getName().toUpperCase(java.util.Locale.ROOT).endsWith(".NP3"))continue;
            total++;byte[] bytes=read(zip,entry.getName()),before=bytes.clone();
            PresetPreviewProfile profile;
            try {profile=PresetPreviewProfiles.fromPreset(bytes);}
            catch(IOException unsupported) {check(unsupported.getMessage()!=null && !unsupported.getMessage().isBlank(),"Rejected catalog entry gives a reason");rejected++;continue;}
            if(profile instanceof Np3PreviewProfile) flexible++;
            else if(profile instanceof Np3MonochromePreviewProfile) {
                monochrome++;int[] input=image(17,19),copy=input.clone(),output=profile.render(input,17,19);
                for(int i=0;i<input.length;i++){gray(output[i]);equal(input[i]>>>24,output[i]>>>24);}
                equal(copy,input);equal(output,profile.render(input,17,19));
            } else throw new AssertionError("NP3 catalog entry dispatched as an unrelated preset family");
            check(Arrays.equals(before,bytes),"Catalog parsing/rendering preserves original recipe bytes");
        }
        equal(236,total);equal(228,flexible);equal(4,monochrome);equal(4,rejected);
    }

    private static byte[] fixture(int filter,byte[] curve,boolean zeroSpatial) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.writeBytes(new byte[]{'N','C','P',0});
        for (int index=1;index<=32;index++) {
            byte[] payload=HexFormat.of().parseHex(MONO_FIELDS[index]);
            if (index==12) payload[0]=(byte)filter;
            if (zeroSpatial && (index==6 || index==7 || index==22)) payload[0]=(byte)128;
            u32(out,index*256);u32(out,payload.length);out.writeBytes(payload);
        }
        u32(out,0);byte[] base=out.toByteArray();return curve==null ? base : append(base,2,curve);
    }
    private static Np3MonochromePreviewProfile mono(int filter) throws IOException {return parse(fixture(filter,identityCurve(),true));}
    private static Np3MonochromePreviewProfile parse(byte[] bytes) throws IOException {return Np3MonochromePreviewProfile.fromNp3(bytes);}
    private static byte[] flexibleFixture(int mode) {
        byte[] bytes=fixture(0x83,null,true);field(bytes,3,0,0);field(bytes,3,1,32);
        for (int i : new int[] {8,9}) field(bytes,i,0,255);
        for (int i : new int[] {12,13}) field(bytes,i,0,255);
        for (int i=25;i<=29;i++) field(bytes,i,0,128);
        if (mode==1) {
            field(bytes,25,0,178);field(bytes,27,0,173);field(bytes,28,0,133);field(bytes,29,0,108);field(bytes,30,0,103);
            field(bytes,31,5,148);field(bytes,31,17,28);
        }
        if (mode==2) {
            for (int i=25;i<=29;i++) field(bytes,i,0,1);
            byte[] curve=identityCurve();for(int i=0;i<=256;i++)sample(curve,i,(int)Math.round((.1+.8*(i/256.0)*(i/256.0))*32767));
            bytes=append(bytes,2,curve);field(bytes,31,5,148);field(bytes,30,0,103);
        }
        return bytes;
    }
    private static byte[] identityCurve() {
        byte[] c=new byte[578];c[0]=0x49;c[1]=0x30;c[3]=c[5]=(byte)255;c[6]=1;c[8]=2;c[11]=c[12]=(byte)255;
        for(int i=0;i<=256;i++)sample(c,i,(int)Math.round(i*32767/256.0));return c;
    }
    private static byte[] append(byte[] base,int tag,byte[] payload) {
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(base,0,base.length-4);u32(out,tag);u32(out,payload.length);out.writeBytes(payload);u32(out,0);return out.toByteArray();
    }
    private static void sample(byte[] c,int i,int value){c[64+i*2]=(byte)(value>>>8);c[65+i*2]=(byte)value;}
    private static int sampleValue(byte[] c,int i){return (c[64+i*2]&255)<<8 | (c[65+i*2]&255);}
    private static void field(byte[] bytes,int index,int at,int value){bytes[fieldOffset(bytes,index)+at]=(byte)value;}
    private static int fieldOffset(byte[] bytes,int index){int at=4;for(int i=1;i<index;i++)at+=8+read32(bytes,at+4);return at+8;}
    private static int read32(byte[] b,int p){return (b[p]&255)<<24|(b[p+1]&255)<<16|(b[p+2]&255)<<8|b[p+3]&255;}
    private static void u32(ByteArrayOutputStream out,int value){out.write(value>>>24);out.write(value>>>16);out.write(value>>>8);out.write(value);}
    private static byte[] read(ZipFile zip,String path)throws IOException{ZipEntry entry=zip.getEntry(path);if(entry==null)throw new IOException("Missing fixed recipe");try(var in=zip.getInputStream(entry)){return in.readAllBytes();}}
    private static String sha(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static int[] image(int width,int height){int[] result=new int[width*height];Random random=new Random(0x03100655L);for(int i=0;i<result.length;i++)result[i]=random.nextInt();return result;}
    private static int mix(int original,int effect,int strength){int result=original&0xff000000;for(int s=0;s<=16;s+=8)result|=((((original>>>s)&255)*(100-strength)+((effect>>>s)&255)*strength+50)/100)<<s;return result;}
    private static int red(int v){return v>>>16&255;} private static int green(int v){return v>>>8&255;} private static int blue(int v){return v&255;}
    private static void gray(int value){check(red(value)==green(value)&&green(value)==blue(value),"100% monochrome has equal RGB channels");}
    private interface Action{void run()throws Exception;}
    private static void reject(byte[] bytes)throws Exception{rejected(() -> Np3MonochromePreviewProfile.fromNp3(bytes));rejected(() -> PresetPreviewProfiles.fromPreset(bytes));}
    private static void rejected(Action action)throws Exception{try{action.run();}catch(IOException expected){checks++;return;}throw new AssertionError("Expected strict malformed/unsupported rejection");}
    private static void invalid(Action action)throws Exception{try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError("Expected invalid input rejection");}
    private static void near(double expected,double actual){check(Double.isFinite(actual)&&Math.abs(expected-actual)<1e-12,"Unexpected curve sample/interpolation");}
    private static void equal(Object a,Object b){check(a instanceof int[] && b instanceof int[] ? Arrays.equals((int[])a,(int[])b) : java.util.Objects.equals(a,b),"Unexpected result: "+a+" vs "+b);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;}
    private static final class ArrayPixels implements SpatialPreviewFilter.Pixels {
        final int[] data;final int width,height;int reads,writes;
        ArrayPixels(int[] data,int width,int height){this.data=data;this.width=width;this.height=height;}
        public int width(){return width;}public int height(){return height;}
        public void read(int[] target,int row,int rows){reads++;System.arraycopy(data,row*width,target,0,rows*width);}
        public void write(int[] source,int row,int rows){writes++;System.arraycopy(source,0,data,row*width,rows*width);}
    }
}

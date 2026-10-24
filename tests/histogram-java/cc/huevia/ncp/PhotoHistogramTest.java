package cc.huevia.ncp;

import java.util.Arrays;

public final class PhotoHistogramTest {
    private static int assertions;
    public static void main(String[] args) {
        PhotoHistogram empty=PhotoHistogram.fromArgb(new int[0]);
        ok(!empty.hasData(),"empty differs from all black");eq(empty.pixelCount(),0,"empty count");eq(empty.shadowPercent(),"—","no fake zero");
        for(PhotoHistogram.Channel channel:PhotoHistogram.Channel.values()) {
            eq(empty.bins(channel).length,256,"256 bins");eq(empty.max(channel),0,"empty max");
        }
        PhotoHistogram black=PhotoHistogram.fromArgb(new int[]{0xff000000,0xff000000});
        ok(black.hasData(),"black is valid data");eq(black.count(PhotoHistogram.Channel.LUMINANCE,0),2,"black peak at zero");
        eq(black.shadowCount(),2,"black shadow");eq(black.highlightCount(),0,"black no highlight");eq(black.shadowPercent(),"100.0%","black percent");
        PhotoHistogram white=PhotoHistogram.fromArgb(new int[]{0xffffffff});
        eq(white.count(PhotoHistogram.Channel.LUMINANCE,255),1,"white endpoint retained");eq(white.highlightPercent(),"100.0%","white highlight");eq(white.shadowPercent(),"0%","true zero");
        int[] primaries={0xffff0000,0xff00ff00,0xff0000ff};
        PhotoHistogram rgb=PhotoHistogram.fromArgb(primaries);
        eq(rgb.count(PhotoHistogram.Channel.LUMINANCE,54),1,"red luminance");
        eq(rgb.count(PhotoHistogram.Channel.LUMINANCE,182),1,"green luminance");
        eq(rgb.count(PhotoHistogram.Channel.LUMINANCE,18),1,"blue luminance");
        eq(rgb.highlightCount(),3,"any channel 255 triggers");eq(rgb.shadowCount(),0,"single-channel zero is not shadow");
        int[] greys=new int[256];for(int i=0;i<256;i++)greys[i]=0xff000000|(i<<16)|(i<<8)|i;
        PhotoHistogram ramp=PhotoHistogram.fromArgb(greys);
        for(PhotoHistogram.Channel channel:PhotoHistogram.Channel.values()) {
            for(int bin=0;bin<256;bin++)eq(ramp.count(channel,bin),1,"uniform grey ramp");
            eq(ramp.max(channel),1,"ramp max");
        }
        PhotoHistogram unequal=PhotoHistogram.fromArgb(new int[]{0xff010204,0xff010305,0xff010306,0xff020307});
        eq(unequal.max(PhotoHistogram.Channel.RED),3,"red peak");eq(unequal.max(PhotoHistogram.Channel.GREEN),3,"green peak");eq(unequal.max(PhotoHistogram.Channel.BLUE),1,"blue peak");eq(unequal.sharedRgbMaximum(),3,"RGB has shared denominator");
        PhotoHistogram twoPeak=PhotoHistogram.fromArgb(new int[]{0xff000000,0xff000000,0xffffffff});
        eq(twoPeak.count(PhotoHistogram.Channel.LUMINANCE,0),2,"left endpoint");eq(twoPeak.count(PhotoHistogram.Channel.LUMINANCE,255),1,"right endpoint");
        eq(PhotoHistogram.percent(1,1001),"<0.1%","tiny nonzero");eq(PhotoHistogram.percent(1,1000),"0.1%","boundary .1");
        eq(PhotoHistogram.percent(1,2000),"<0.1%","not rounded to false .1");eq(PhotoHistogram.percent(0,1000),"0%","measured zero");
        eq(PhotoHistogram.percent(1234,10000),"12.3%","one decimal");eq(PhotoHistogram.percent(Long.MAX_VALUE-1,Long.MAX_VALUE),"100.0%","no long product overflow");
        eq(PhotoHistogram.percent(0,0),"—","no-data percent");
        PhotoHistogram.Builder builder=new PhotoHistogram.Builder();builder.addArgb(0xff123456);
        PhotoHistogram frozen=builder.build();builder.addArgb(0xff123456);eq(frozen.pixelCount(),1,"builder cannot mutate model");
        long[] binCopy=frozen.bins(PhotoHistogram.Channel.RED);Arrays.fill(binCopy,999);eq(frozen.count(PhotoHistogram.Channel.RED,0x12),1,"array returned is a clone");
        PhotoHistogram subset=new PhotoHistogram.Builder().addArgb(new int[]{1,0xff204060,3},1,1).build();eq(subset.pixelCount(),1,"row range");eq(subset.count(PhotoHistogram.Channel.RED,32),1,"range uses correct pixel");
        for(int color:new int[]{0,0xffffff,0xff0000,0xff00,0xff,0x112233,0x010101})
            ok(!(PhotoHistogram.isHighlight(color)&&PhotoHistogram.isShadow(color)),"warning predicates mutually exclusive");
        throwsIllegal(()->PhotoHistogram.fromArgb(null),"null");
        throwsIllegal(()->new PhotoHistogram.Builder().addArgb(new int[2],1,2),"invalid range");
        throwsIllegal(()->new PhotoHistogram.Builder().addArgb(new int[2],-1,1),"negative range");
        throwsIllegal(()->new PhotoHistogram.Builder().addArgb(new int[2],0,Integer.MAX_VALUE),"overflow range");
        throwsIllegal(()->ramp.count(PhotoHistogram.Channel.RED,256),"invalid bin");
        throwsIllegal(()->PhotoHistogram.percent(2,1),"invalid hits");throwsIllegal(()->PhotoHistogram.percent(-1,1),"negative hits");
        System.out.println("PhotoHistogramTest PASS: "+assertions+" assertions");
    }
    private static void ok(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    private static void eq(long actual,long expected,String message){ok(actual==expected,message+": "+actual+" != "+expected);}
    private static void eq(String actual,String expected,String message){ok(actual.equals(expected),message+": "+actual+" != "+expected);}
    private static void throwsIllegal(Runnable action,String message){boolean thrown=false;try{action.run();}catch(IllegalArgumentException expected){thrown=true;}ok(thrown,message);}
}

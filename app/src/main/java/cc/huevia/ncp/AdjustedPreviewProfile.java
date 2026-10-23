package cc.huevia.ncp;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import static cc.huevia.ncp.PhotoAdjustments.Parameter.*;

/** Original -> complete preset/strength -> manual colour -> resolution-aware manual detail. */
public final class AdjustedPreviewProfile implements PresetPreviewProfile {
    private static final PresetPreviewProfile ORIGINAL = new PresetPreviewProfile(){
        public String registrationName(){return "无滤镜";} public String modelVersion(){return "original-srgb-v1";}
        public double mapTone(double v){return v;}public int applyArgb(int v){return v;}
    };
    private final PresetPreviewProfile base;
    private final PhotoAdjustments settings;
    private final double[][] channels=new double[3][256];
    private final boolean colourNeutral;
    private AdjustedPreviewProfile(PresetPreviewProfile base,PhotoAdjustments settings){
        this.base=base;this.settings=settings;
        boolean neutral=true;for(PhotoAdjustments.Parameter p:PhotoAdjustments.Parameter.values())if(p!=CLARITY&&p!=SHARPENING&&settings.get(p)!=0)neutral=false;
        colourNeutral=neutral;
        double exposure=Math.pow(2,settings.get(EXPOSURE)/10.0),t=settings.get(TEMPERATURE)/100.0,tint=settings.get(TINT)/100.0;
        double[] gain={Math.exp(.35*t+.13*tint),Math.exp(-.15*tint),Math.exp(-.35*t+.13*tint)};
        double normalize=.2126*gain[0]+.7152*gain[1]+.0722*gain[2];
        for(int c=0;c<3;c++)for(int v=0;v<256;v++)channels[c][v]=encode(linear(v/255.0)*exposure*gain[c]/normalize);
    }
    public static PresetPreviewProfile of(PresetPreviewProfile base,PhotoAdjustments settings){
        if(settings==null)throw new IllegalArgumentException("settings");
        if(settings.isNeutral())return base==null?ORIGINAL:base;
        return new AdjustedPreviewProfile(base,settings);
    }
    public String registrationName(){return base==null?"基础调节":base.registrationName();}
    public String modelVersion(){return (base==null?ORIGINAL.modelVersion():base.modelVersion())+":"+settings.identity();}
    public double mapTone(double value){return value;}
    public int applyArgb(int input){return manual(base==null?input:base.applyArgb(input));}
    private int manual(int input){
        if(colourNeutral)return input;
        double r=channels[0][(input>>>16)&255],g=channels[1][(input>>>8)&255],b=channels[2][input&255];
        double y=.2126*r+.7152*g+.0722*b;
        double next=.5+(y-.5)*Math.exp(settings.get(CONTRAST)*.009);
        next+=settings.get(BRIGHTNESS)/100.0*.32*4*y*(1-y);
        next+=settings.get(HIGHLIGHTS)/100.0*.38*smooth(.45,1,y)*4*y*(1-y);
        next+=settings.get(SHADOWS)/100.0*.38*(1-smooth(0,.55,y))*4*y*(1-y);
        next+=settings.get(WHITES)/100.0*.25*Math.pow(y,4);
        next+=settings.get(BLACKS)/100.0*.25*Math.pow(1-y,4);
        double delta=clamp(next)-y;r+=delta;g+=delta;b+=delta;
        double l=.2126*r+.7152*g+.0722*b,max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
        double saturation=max>0?(max-min)/max:0;
        double amount=(1+settings.get(SATURATION)/100.0)*(1+settings.get(VIBRANCE)/100.0*.8*Math.pow(1-saturation,2));
        return (input&0xff000000)|(channel(l+(r-l)*amount)<<16)|(channel(l+(g-l)*amount)<<8)|channel(l+(b-l)*amount);
    }
    @Override public void renderPixels(SpatialPreviewFilter.Pixels pixels,BooleanSupplier cancel,DoubleConsumer progress)throws IOException{
        if(base!=null)base.renderPixels(pixels,cancel,progress==null?null:v->progress.accept(v*.5));
        SpatialPreviewFilter.Settings detail=new SpatialPreviewFilter.Settings(settings.get(SHARPENING)/100.0,0,settings.get(CLARITY)/100.0);
        SpatialPreviewFilter.applyScaled(pixels,detail,this::manual,cancel,progress==null?null:v->progress.accept((base==null?0:.5)+(base==null?1:.5)*v));
    }
    private static double smooth(double a,double b,double v){double x=clamp((v-a)/(b-a));return x*x*(3-2*x);}
    private static double linear(double v){return v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);}
    private static double encode(double v){return v<=.0031308?clamp(v*12.92):clamp(1.055*Math.pow(v,1/2.4)-.055);}
    private static double clamp(double v){return Math.max(0,Math.min(1,v));}
    private static int channel(double v){return (int)Math.round(clamp(v)*255);}
}

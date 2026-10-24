package cc.huevia.ncp;

import java.io.IOException;
import java.util.function.*;

/** Colour is delegated; border compositing occurs after oriented geometry in the Android renderer. */
public final class BorderPreviewProfile implements PresetPreviewProfile {
    public final PresetPreviewProfile colour;public final PhotoBorder border;
    private BorderPreviewProfile(PresetPreviewProfile p,PhotoBorder b){colour=p;border=b;}
    public static PresetPreviewProfile of(PresetPreviewProfile p,PhotoBorder b){if(p==null||b==null)throw new IllegalArgumentException("settings");return b.isNone()?p:new BorderPreviewProfile(p,b);}
    public static PhotoBorder border(PresetPreviewProfile p){return p instanceof BorderPreviewProfile?((BorderPreviewProfile)p).border:PhotoBorder.NONE;}
    public String registrationName(){return colour.registrationName();}public String modelVersion(){return colour.modelVersion()+":"+border.identity();}public double mapTone(double v){return colour.mapTone(v);}public int applyArgb(int v){return colour.applyArgb(v);}public SpatialPreviewFilter.Settings spatialSettings(){return colour.spatialSettings();}
    public void renderPixels(SpatialPreviewFilter.Pixels p,BooleanSupplier c,DoubleConsumer progress)throws IOException{colour.renderPixels(p,c,progress);}
}

package cc.huevia.ncp;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** Carries geometry through frozen exports and cache identities; colour processing remains delegated. */
public final class GeometryPreviewProfile implements PresetPreviewProfile {
    public final PresetPreviewProfile colour;
    public final PhotoGeometry geometry;
    private GeometryPreviewProfile(PresetPreviewProfile colour,PhotoGeometry geometry){this.colour=colour;this.geometry=geometry;}
    public static PresetPreviewProfile of(PresetPreviewProfile colour,PhotoGeometry geometry){if(colour==null||geometry==null)throw new IllegalArgumentException("settings");return geometry.isNeutral()?colour:new GeometryPreviewProfile(colour,geometry);}
    public String registrationName(){return colour.registrationName();}
    public String modelVersion(){return colour.modelVersion()+":"+geometry.identity();}
    public double mapTone(double v){return colour.mapTone(v);}
    public int applyArgb(int v){return colour.applyArgb(v);}
    public SpatialPreviewFilter.Settings spatialSettings(){return colour.spatialSettings();}
    public void renderPixels(SpatialPreviewFilter.Pixels pixels,BooleanSupplier cancel,DoubleConsumer progress)throws IOException{colour.renderPixels(pixels,cancel,progress);}
    public static PhotoGeometry geometry(PresetPreviewProfile profile){if(profile instanceof BorderPreviewProfile)profile=((BorderPreviewProfile)profile).colour;return profile instanceof GeometryPreviewProfile?((GeometryPreviewProfile)profile).geometry:PhotoGeometry.NONE;}
}

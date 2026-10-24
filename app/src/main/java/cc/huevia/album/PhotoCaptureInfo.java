package cc.huevia.album;

import android.media.ExifInterface;
import cc.huevia.ncp.PhotoBorder;
import java.io.*;
import java.util.*;

/** Read only, original EXIF. Missing data stays missing; never infer a camera from pixels. */
public final class PhotoCaptureInfo {
    public static final PhotoCaptureInfo EMPTY=new PhotoCaptureInfo("","",new String[]{"","","","",""});
    public final String brand,model;private final String[] fields;
    public PhotoCaptureInfo(String brand,String model,String[] fields){this.brand=brand==null?"":brand.trim();this.model=model==null?"":model.trim();this.fields=fields.clone();}
    public static PhotoCaptureInfo read(File file){try{ExifInterface e=new ExifInterface(file.getPath());String make=value(e,"Make"),model=value(e,"Model");String focal=number(value(e,"FocalLength")),aperture=number(value(e,"FNumber"));double seconds=e.getAttributeDouble("ExposureTime",0);String shutter=seconds>0?(seconds<1?"1/"+Math.round(1/seconds):number(Double.toString(seconds)))+"s":"";String iso=value(e,"PhotographicSensitivity");if(iso.isEmpty())iso=value(e,"ISOSpeedRatings");if(!iso.matches("[1-9][0-9]{0,6}"))iso="";String date=value(e,"DateTimeOriginal");if(date.isEmpty())date=value(e,"DateTimeDigitized");if(!date.matches("[0-9]{4}:[0-9]{2}:[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}"))date="";else date=date.substring(0,10).replace(':','-')+date.substring(10);return new PhotoCaptureInfo(brandKey(make.isEmpty()?model:make),model,new String[]{focal.isEmpty()?"":focal+"mm",aperture.isEmpty()?"":"F"+aperture,shutter,iso.isEmpty()?"":"ISO"+iso,date});}catch(IOException|RuntimeException missing){return EMPTY;}}
    private static String value(ExifInterface e,String k){String s=e.getAttribute(k);return s==null?"":s.trim().replaceAll("[\\p{Cntrl}]"," ");}
    private static double decimal(String s){try{String[] p=s.split("/");double v=p.length==2?Double.parseDouble(p[0])/Double.parseDouble(p[1]):Double.parseDouble(s);return Double.isFinite(v)&&v>0&&v<1e9?v:0;}catch(RuntimeException invalid){return 0;}}
    private static String number(String s){double v=decimal(s);if(v==0)return "";return String.format(Locale.US,"%.2f",v).replaceAll("0+$","").replaceAll("\\.$","");}
    public static String brandKey(String name){String n=name.toUpperCase(Locale.ROOT);if(n.contains("NIKON"))return "NIKON";if(n.contains("FUJI"))return "FUJIFILM";if(n.contains("SONY"))return "SONY";if(n.contains("CANON"))return "CANON";return name.trim();}
    public String brand(PhotoBorder b){return b.automatic?brand:b.brand;}
    public String model(PhotoBorder b){return b.automatic?model:b.model;}
    public String field(PhotoBorder b,int index){return b.field(index).isEmpty()?fields[index]:b.field(index);}
    public String parameters(PhotoBorder b){StringJoiner s=new StringJoiner("  ");for(int i=0;i<4;i++)if(b.shows(i+1)&&!field(b,i).isEmpty())s.add(field(b,i));return s.toString();}
    public static String friendly(String v){return v.replaceFirst("(?i)^NIKON\\s+","Nikon ").replace("Z 6_2","Z6 II").replace("Z 7_2","Z7 II").replace("Z 6_3","Z6 III").replace("Z f","Zf").replaceAll("Z ([5789])", "Z$1");}
    public String company(PhotoBorder b){String s=brand(b);return s.equals("NIKON")?"NIKON CORPORATION":s;}
}

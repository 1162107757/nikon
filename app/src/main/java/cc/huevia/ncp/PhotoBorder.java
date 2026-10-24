package cc.huevia.ncp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Immutable display settings. Capture metadata is always resolved from the verified original. */
public final class PhotoBorder {
    public static final String MODEL="photo-border-v1";
    public static final String[] TEMPLATES={"无边框","模糊居中","模糊侧栏","白底信息"};
    public static final PhotoBorder NONE=new PhotoBorder(0,true,"","",new String[5],63,0,100,100,100,100);
    public final int template,visible,logo,margin,corner,shadow,blur;
    public final boolean automatic;
    public final String brand,model;
    private final String[] fields;
    public PhotoBorder(int template,boolean automatic,String brand,String model,String[] fields,int visible,int logo,int margin,int corner,int shadow,int blur){
        if(template<0||template>3||fields==null||fields.length!=5||visible<0||visible>63||logo<0||logo>3||margin<50||margin>160||corner<0||corner>200||shadow<0||shadow>150||blur<0||blur>200)throw new IllegalArgumentException("边框设置无效。");
        this.template=template;this.automatic=automatic;this.brand=clean(brand);this.model=clean(model);this.fields=fields.clone();for(int i=0;i<5;i++)this.fields[i]=clean(this.fields[i]);this.visible=visible;this.logo=logo;this.margin=margin;this.corner=corner;this.shadow=shadow;this.blur=blur;
    }
    private static String clean(String v){if(v==null)return "";v=v.trim().replaceAll("[\\p{Cntrl}]"," ");if(v.length()>96)throw new IllegalArgumentException("边框文字最多 96 个字符。");return v;}
    private PhotoBorder copy(int t,boolean a,String b,String m,String[] f,int v,int l,int x,int c,int s,int u){return new PhotoBorder(t,a,b,m,f,v,l,x,c,s,u);}
    public boolean isNone(){return template==0;}
    public String field(int i){return fields[i];}
    public boolean shows(int i){return (visible&(1<<i))!=0;}
    public PhotoBorder withTemplate(int t){return copy(t,automatic,brand,model,fields,visible,logo,margin,corner,shadow,blur);}
    public PhotoBorder withCamera(boolean auto,String b,String m){return copy(template,auto,b,m,fields,visible,logo,margin,corner,shadow,blur);}
    public PhotoBorder withField(int i,String value){String[] f=fields.clone();f[i]=value;return copy(template,automatic,brand,model,f,visible,logo,margin,corner,shadow,blur);}
    public PhotoBorder withVisible(int i,boolean show){return copy(template,automatic,brand,model,fields,show?visible|(1<<i):visible&~(1<<i),logo,margin,corner,shadow,blur);}
    public PhotoBorder withLogo(int l){return copy(template,automatic,brand,model,fields,visible,l,margin,corner,shadow,blur);}
    public PhotoBorder withAppearance(int index,int value){return copy(template,automatic,brand,model,fields,visible,logo,index==0?value:margin,index==1?value:corner,index==2?value:shadow,index==3?value:blur);}
    public PhotoBorder reset(){return NONE.withTemplate(template);}
    public void write(DataOutput out)throws IOException{out.writeUTF(MODEL);out.writeInt(template);out.writeBoolean(automatic);out.writeUTF(brand);out.writeUTF(model);for(String f:fields)out.writeUTF(f);for(int i:new int[]{visible,logo,margin,corner,shadow,blur})out.writeInt(i);}
    public static PhotoBorder read(DataInput in)throws IOException{if(!MODEL.equals(in.readUTF()))throw new IOException("无法读取此版本的边框。");try{int t=in.readInt();boolean a=in.readBoolean();String b=in.readUTF(),m=in.readUTF();String[] f=new String[5];for(int i=0;i<5;i++)f[i]=in.readUTF();return new PhotoBorder(t,a,b,m,f,in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt(),in.readInt());}catch(IllegalArgumentException e){throw new IOException("边框草稿损坏。",e);}}
    public String identity(){try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();write(new DataOutputStream(bytes));byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(Locale.ROOT,"%02x",b&255));return MODEL+":"+hex;}catch(IOException|NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    @Override public boolean equals(Object v){if(!(v instanceof PhotoBorder))return false;PhotoBorder o=(PhotoBorder)v;return template==o.template&&automatic==o.automatic&&brand.equals(o.brand)&&model.equals(o.model)&&Arrays.equals(fields,o.fields)&&visible==o.visible&&logo==o.logo&&margin==o.margin&&corner==o.corner&&shadow==o.shadow&&blur==o.blur;}
    @Override public int hashCode(){return identity().hashCode();}
    /** Photo pixels retain their dimensions. Border dimensions are derived proportionally at every resolution. */
    public int[] dimensions(int w,int h){if(w<1||h<1)throw new IllegalArgumentException("照片尺寸无效。");double pad=margin/100.0;if(template==1)return new int[]{w+Math.round(w*.22f*(float)pad),h+Math.round(w*.145f*(float)pad)};if(template==2)return new int[]{w+Math.round(w*.45f*(float)pad),h+Math.round(w*.14f*(float)pad)};if(template==3)return new int[]{w,h+Math.round(w*.112f*(float)pad)};return new int[]{w,h};}
}

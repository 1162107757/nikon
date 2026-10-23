package cc.huevia.album;

import cc.huevia.ncp.PhotoAdjustments;
import cc.huevia.ncp.PhotoGeometry;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Private account-scoped reusable colour recipes. Crop is deliberately per-photo, never copied. */
public final class PhotoRecipeStore {
    private static final Object LOCK=new Object();
    private static final int MAGIC=0x50524331,LIMIT=1024*1024,MAX_RECIPES=100;
    private final File root;
    public static final class Recipe {
        public final String id,name,presetName;
        public final long createdAt;
        public final int strength;
        public final PhotoAdjustments adjustments;
        private final byte[] preset;
        private Recipe(String id,String name,long createdAt,byte[] preset,String presetName,int strength,PhotoAdjustments manual)throws IOException{
            if(id==null||!id.matches("[0-9a-f]{32}")||createdAt<=0)throw new IOException("配方身份无效。");
            this.id=id;this.name=clean(name);this.createdAt=createdAt;
            PhotoEditStore.Draft checked=new PhotoEditStore.Draft("0".repeat(64),preset,presetName,strength,manual,false);
            this.preset=checked.presetBytes();this.presetName=checked.name;this.strength=checked.strength;this.adjustments=manual;
        }
        public PhotoEditStore.Draft apply(String sha,PhotoGeometry geometry)throws IOException{return new PhotoEditStore.Draft(sha,preset,presetName,strength,adjustments,geometry,false);}
        public byte[] presetBytes(){return preset.clone();}
        public String summary(){return presetName+(preset.length==0?"":" · "+strength+"%")+" · 调节 "+adjustments.count()+" 项";}
    }
    public PhotoRecipeStore(File files,String owner)throws IOException{
        File base=files.getCanonicalFile();root=new File(base,"photo-recipes/"+PhotoEditStore.hash((owner==null?"":owner).getBytes(StandardCharsets.UTF_8))).getCanonicalFile();
        if(!root.toPath().startsWith(base.toPath())||!root.isDirectory()&&!root.mkdirs())throw new IOException("无法读取我的配方。");
    }
    public List<Recipe> list()throws IOException{synchronized(LOCK){File[] files=root.listFiles((d,n)->n.endsWith(".recipe"));if(files==null)throw new IOException("无法读取配方列表。");List<Recipe> rows=new ArrayList<>();for(File f:files)rows.add(read(f.getName().substring(0,f.getName().length()-7)));rows.sort(Comparator.comparingLong((Recipe r)->r.createdAt).reversed().thenComparing(r->r.id));return rows;}}
    public Recipe create(String name,PhotoEditStore.Draft settings)throws IOException{synchronized(LOCK){List<Recipe> existing=list();if(existing.size()>=MAX_RECIPES)throw new IOException("最多保存 100 个配方，请先删除不再使用的配方。");String clean=clean(name);unique(existing,clean,null);Recipe recipe=new Recipe(UUID.randomUUID().toString().replace("-",""),clean,System.currentTimeMillis(),settings.presetBytes(),settings.name,settings.strength,settings.adjustments);write(recipe);return recipe;}}
    public Recipe rename(String id,String name)throws IOException{synchronized(LOCK){Recipe old=read(id);String clean=clean(name);unique(list(),clean,id);Recipe next=new Recipe(id,clean,old.createdAt,old.preset,old.presetName,old.strength,old.adjustments);write(next);return next;}}
    public void delete(String id)throws IOException{synchronized(LOCK){Files.delete(file(id).toPath());}}
    public Recipe read(String id)throws IOException{synchronized(LOCK){File file=file(id);if(file.length()<40||file.length()>LIMIT+4096)throw new IOException("配方文件损坏。");byte[] all=Files.readAllBytes(file.toPath());byte[] body=Arrays.copyOf(all,all.length-32);if(!MessageDigest.isEqual(digest(body),Arrays.copyOfRange(all,body.length,all.length)))throw new IOException("配方校验失败。");try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(body))){if(in.readInt()!=MAGIC||!id.equals(in.readUTF()))throw new IOException("配方身份不符。");String name=in.readUTF();long created=in.readLong();String presetName=in.readUTF();int strength=in.readInt(),length=in.readInt();if(length<0||length>LIMIT||length>in.available())throw new IOException("配方预设数据损坏。");byte[] preset=new byte[length];in.readFully(preset);if(!PhotoAdjustments.MODEL.equals(in.readUTF())||in.readInt()!=PhotoAdjustments.Parameter.values().length)throw new IOException("无法读取此版本的配方。");int[] values=new int[PhotoAdjustments.Parameter.values().length];for(int i=0;i<values.length;i++)values[i]=in.readInt();if(in.available()!=0)throw new IOException("配方包含未知数据。");try{return new Recipe(id,name,created,preset,presetName,strength,new PhotoAdjustments(values));}catch(IllegalArgumentException invalid){throw new IOException("配方调节数据损坏。",invalid);}}}}
    private void write(Recipe r)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){out.writeInt(MAGIC);out.writeUTF(r.id);out.writeUTF(r.name);out.writeLong(r.createdAt);out.writeUTF(r.presetName);out.writeInt(r.strength);out.writeInt(r.preset.length);out.write(r.preset);out.writeUTF(PhotoAdjustments.MODEL);int[] values=r.adjustments.values();out.writeInt(values.length);for(int v:values)out.writeInt(v);}
        byte[] body=bytes.toByteArray();File temp=File.createTempFile("recipe-",".part",root);try{try(FileOutputStream output=new FileOutputStream(temp)){output.write(body);output.write(digest(body));output.getFD().sync();}try{Files.move(temp.toPath(),file(r.id).toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp.toPath(),file(r.id).toPath(),StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(temp.toPath());}
    }
    private File file(String id)throws IOException{if(id==null||!id.matches("[0-9a-f]{32}"))throw new IOException("配方身份无效。");File f=new File(root,id+".recipe");if(!f.getCanonicalFile().equals(f)||Files.isSymbolicLink(f.toPath()))throw new IOException("配方路径无效。");return f;}
    private static String clean(String value)throws IOException{String s=value==null?"":value.trim();if(s.isEmpty()||s.codePointCount(0,s.length())>40||s.codePoints().anyMatch(c->Character.isISOControl(c)||c==0x2028||c==0x2029))throw new IOException("配方名称请填写 1–40 个字符，不能包含换行。");return s;}
    private static void unique(List<Recipe> rows,String name,String except)throws IOException{for(Recipe r:rows)if(!r.id.equals(except)&&r.name.equalsIgnoreCase(name))throw new IOException("已有同名配方，请换一个名称。");}
    private static byte[] digest(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}

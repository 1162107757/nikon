package cc.huevia.album;

import android.content.*;
import android.database.Cursor;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.AtomicFile;
import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Verified publication receipts deduplicate a saved version; extra copies receive fresh metadata only. */
final class PhotoEffectGallery {
    private static final Object LOCK=new Object();private static final int MAGIC=0x50454731,MAX=10000;
    private final Context context;private final PhotoLiveGallery exact;
    PhotoEffectGallery(Context context){this.context=context;exact=new PhotoLiveGallery(context);}
    private static final class Receipt {String stage,uri,sha;long bytes,time;Receipt(String stage,String uri,String sha,long bytes,long time){this.stage=stage;this.uri=uri;this.sha=sha;this.bytes=bytes;this.time=time;}}
    private File directory(String name)throws IOException{File base=context.getFilesDir().getCanonicalFile(),dir=new File(base,name).getCanonicalFile();if(!dir.getParentFile().equals(base)||!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法保存效果图导出记录。");return dir;}
    /** A version keeps the same receipt when the private store changes between internal/external storage. */
    private List<AtomicFile> journals(File source)throws IOException{
        String sha=PhotoEditStore.hashFile(source),version=PhotoPresetSources.isEffect(source)?"effect:"+source.getName():"original";
        File directory=directory("photo-effect-gallery");LinkedHashSet<String> identities=new LinkedHashSet<>();
        identities.add(version+"\n"+sha);
        // v82 keyed the same version by its private path. Import every known old location.
        identities.add(source.getPath()+"\n"+sha);
        if(PhotoPresetSources.isEffect(source)){
            File internal=new File(new File(context.getFilesDir(),"pictures"),"camera-album").getCanonicalFile();
            File external=context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES);
            identities.add(new File(internal,source.getName()).getPath()+"\n"+sha);
            if(external!=null)identities.add(new File(new File(external,"camera-album").getCanonicalFile(),source.getName()).getPath()+"\n"+sha);
        }
        List<AtomicFile> result=new ArrayList<>();for(String identity:identities)result.add(new AtomicFile(new File(directory,PhotoEditStore.hash(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8))+".record")));return result;
    }
    private List<Receipt> readSource(List<AtomicFile> files)throws IOException{
        LinkedHashMap<String,Receipt> merged=new LinkedHashMap<>();boolean found=false;
        for(AtomicFile journal:files){List<Receipt> rows=read(journal);if(rows==null)continue;found=true;
            for(Receipt row:rows){String key=row.uri.isEmpty()?"stage:"+row.stage:"uri:"+row.uri;Receipt previous=merged.get(key);
                if(previous!=null&&(previous.bytes!=row.bytes||!previous.sha.equals(row.sha)))throw new IOException("同一相册副本的历史记录不符。");
                if(previous==null)merged.put(key,row);
            }
        }
        if(merged.size()>MAX)throw new IOException("效果图导出记录数量无效。");return found?new ArrayList<>(merged.values()):null;
    }
    private void retireLegacyJournals(List<AtomicFile> files)throws IOException{
        for(int i=1;i<files.size();i++){AtomicFile file=files.get(i);file.delete();if(file.getBaseFile().exists()||new File(file.getBaseFile()+".bak").exists())throw new IOException("旧导出记录尚未完成迁移，可重试。");}
    }
    private File stage(String name)throws IOException{if(!name.matches("export-[0-9a-f]{32}\\.JPG"))throw new IOException("导出临时文件身份无效。");File f=new File(directory("photo-export-staging"),name);if(!f.getCanonicalFile().equals(f)||Files.isSymbolicLink(f.toPath()))throw new IOException("导出临时文件路径无效。");return f;}
    private List<Receipt> read(AtomicFile file)throws IOException{
        if(!file.getBaseFile().exists()&&!new File(file.getBaseFile()+".bak").exists())return null;
        if(file.getBaseFile().length()>8*1024*1024)throw new IOException("效果图导出记录过大。");
        try(DataInputStream in=new DataInputStream(file.openRead())){if(in.readInt()!=MAGIC)throw new IOException("效果图导出记录损坏。");int count=in.readInt();if(count<0||count>MAX)throw new IOException("效果图导出记录数量无效。");List<Receipt> rows=new ArrayList<>();for(int i=0;i<count;i++){Receipt r=new Receipt(in.readUTF(),in.readUTF(),in.readUTF(),in.readLong(),in.readLong());if(!r.sha.matches("[0-9a-f]{64}")||r.bytes<4||r.bytes>512L*1024*1024||r.time<0||!r.stage.isEmpty()&&!r.stage.matches("export-[0-9a-f]{32}\\.JPG")||r.uri.isEmpty()&&r.stage.isEmpty())throw new IOException("效果图导出记录无效。");if(!r.uri.isEmpty())uri(r.uri);rows.add(r);}if(in.read()!=-1)throw new IOException("效果图导出记录含未知数据。");return rows;}
    }
    private void write(AtomicFile file,List<Receipt> rows)throws IOException{FileOutputStream stream=null;try{stream=file.startWrite();DataOutputStream out=new DataOutputStream(stream);out.writeInt(MAGIC);out.writeInt(rows.size());for(Receipt r:rows){out.writeUTF(r.stage);out.writeUTF(r.uri);out.writeUTF(r.sha);out.writeLong(r.bytes);out.writeLong(r.time);}out.flush();stream.getFD().sync();file.finishWrite(stream);stream=null;}finally{if(stream!=null)file.failWrite(stream);}}
    private static Uri uri(String value)throws IOException{Uri uri=Uri.parse(value);if(!"content".equals(uri.getScheme())||!"media".equals(uri.getAuthority())||!uri.getPath().matches("/(external|external_primary)/images/media/[0-9]+"))throw new IOException("效果图相册地址无效。");return uri;}
    private boolean exists(Receipt receipt)throws IOException{
        Uri target=uri(receipt.uri);String[] columns=Build.VERSION.SDK_INT>=29?new String[]{MediaStore.Images.Media.OWNER_PACKAGE_NAME,MediaStore.Images.Media.IS_PENDING}:new String[]{MediaStore.Images.Media._ID};
        try(Cursor cursor=context.getContentResolver().query(target,columns,null,null,null)){if(cursor==null)throw new IOException("无法读取效果图相册状态。");if(!cursor.moveToFirst())return false;if(Build.VERSION.SDK_INT>=29&&(!context.getPackageName().equals(cursor.getString(0))||cursor.getInt(1)!=0))throw new IOException("效果图相册副本归属或保存状态已变化。");}
        try(InputStream input=context.getContentResolver().openInputStream(target)){if(input==null)throw new IOException("无法核对效果图相册内容。");java.security.MessageDigest digest;try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(Exception impossible){throw new AssertionError(impossible);}byte[] buffer=new byte[65536];long bytes=0;int n;while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("相册核对已取消。");bytes+=n;if(bytes>receipt.bytes)throw new IOException("相册效果图已被修改，未覆盖或删除。");digest.update(buffer,0,n);}StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));if(bytes!=receipt.bytes||!receipt.sha.contentEquals(hash))throw new IOException("相册效果图已被修改，未覆盖或删除。");}return true;
    }
    String publish(DownloadedPhoto photo)throws IOException{return publish(photo,false);}
    private PhotoLiveGallery.PublicationState fallbackStatus(DownloadedPhoto photo,boolean includeOrigin){
        PhotoLiveGallery.PublicationState prior=exact.exactPublicationStatus(photo);
        if(prior.status!=PhotoLiveGallery.PublicationStatus.MISSING||!includeOrigin)return prior;
        LocalPhotoOrigin.Result origin=LocalPhotoOrigin.check(context,photo);
        if(origin.state==LocalPhotoOrigin.State.EXISTING_SOURCE)return new PhotoLiveGallery.PublicationState(PhotoLiveGallery.PublicationStatus.EXISTING_SOURCE,origin.uri,origin.message);
        if(origin.state==LocalPhotoOrigin.State.UNAVAILABLE)return new PhotoLiveGallery.PublicationState(PhotoLiveGallery.PublicationStatus.UNAVAILABLE,"",origin.message);
        return prior;
    }
    String publish(DownloadedPhoto photo,boolean forceNewCopy)throws IOException{
        synchronized(LOCK){try(PhotoLocalTasks.Lease lease=PhotoLocalTasks.use()){
            File source=exact.checkedEffectSource(photo);List<AtomicFile> files=journals(source);AtomicFile file=files.get(0);List<Receipt> rows=readSource(files);if(rows==null){rows=new ArrayList<>();PhotoLiveGallery.PublicationState prior=fallbackStatus(photo,!forceNewCopy);if(prior.status==PhotoLiveGallery.PublicationStatus.UNAVAILABLE)throw new IOException(prior.message.isEmpty()?"系统相册状态暂不可用，请重试。":prior.message);if(prior.status==PhotoLiveGallery.PublicationStatus.EXISTING_SOURCE)return prior.uri;if(prior.status==PhotoLiveGallery.PublicationStatus.PENDING){String recovered=exact.publishExact(photo);rows.add(new Receipt("",recovered,PhotoEditStore.hashFile(source),source.length(),0));write(file,rows);return recovered;}if(prior.status==PhotoLiveGallery.PublicationStatus.PUBLISHED)rows.add(new Receipt("",prior.uri,PhotoEditStore.hashFile(source),source.length(),0));}
            else{write(file,rows);retireLegacyJournals(files);}
            // Recover a previous interrupted publication using its frozen bytes and the existing publisher journal.
            String recovered="";
            for(Receipt pending:rows)if(pending.uri.isEmpty()){File frozen=stage(pending.stage);if(!frozen.isFile()||frozen.length()!=pending.bytes||!PhotoEditStore.hashFile(frozen).equals(pending.sha))throw new IOException("上次导出副本不完整，请保留原图并检查存储空间。");pending.uri=exact.publishExact(new DownloadedPhoto(frozen,photo.filename(),photo.projectId(),photo.projectName()));write(file,rows);if(!exists(pending))throw new IOException("相册尚未确认导出副本。");recovered=pending.uri;Files.deleteIfExists(frozen.toPath());pending.stage="";write(file,rows);}
            for(Receipt completed:rows)if(!completed.uri.isEmpty()&&!completed.stage.isEmpty()){if(!exists(completed))throw new IOException("上次导出相册副本已移除，请重新保存。");File frozen=stage(completed.stage);if(frozen.exists()&&(frozen.length()!=completed.bytes||!PhotoEditStore.hashFile(frozen).equals(completed.sha)))throw new IOException("已导出暂存副本发生变化，未清理。");Files.deleteIfExists(frozen.toPath());completed.stage="";write(file,rows);recovered=completed.uri;}
            // Finishing a previously interrupted request is its retry, even for an extra-copy request.
            if(!recovered.isEmpty())return recovered;
            if(!forceNewCopy){for(int i=rows.size()-1;i>=0;i--){Receipt receipt=rows.get(i);if(!receipt.uri.isEmpty()&&exists(receipt))return receipt.uri;}PhotoLiveGallery.PublicationState fallback=fallbackStatus(photo,true);if(fallback.status==PhotoLiveGallery.PublicationStatus.PUBLISHED||fallback.status==PhotoLiveGallery.PublicationStatus.EXISTING_SOURCE)return fallback.uri;if(fallback.status==PhotoLiveGallery.PublicationStatus.UNAVAILABLE)throw new IOException(fallback.message);if(fallback.status==PhotoLiveGallery.PublicationStatus.PENDING||!PhotoPresetSources.isEffect(source))return exact.publishExact(photo);}
            if(rows.size()>=MAX)throw new IOException("此效果的相册导出记录已达上限，请先清理不再需要的版本。");
            long now=System.currentTimeMillis();for(Receipt previous:rows){if(previous.time==Long.MAX_VALUE)throw new IOException("导出记录时间无效。");now=Math.max(now,previous.time+1);}String name="export-"+UUID.randomUUID().toString().replace("-","")+".JPG";File frozen=stage(name);String sourceSha=PhotoEditStore.hashFile(source);
            try{Files.copy(source.toPath(),frozen.toPath());ExifInterface exif=new ExifInterface(frozen.getPath());Date date=new Date(now);SimpleDateFormat format=new SimpleDateFormat("yyyy:MM:dd HH:mm:ss",Locale.US);String clock=format.format(date),zone=new SimpleDateFormat("XXX",Locale.US).format(date);if(zone.equals("Z"))zone="+00:00";String sub=String.format(Locale.US,"%03d",now%1000);for(String tag:new String[]{"DateTime","DateTimeOriginal","DateTimeDigitized"})exif.setAttribute(tag,clock);for(String tag:new String[]{"OffsetTime","OffsetTimeOriginal","OffsetTimeDigitized"})exif.setAttribute(tag,zone);for(String tag:new String[]{"SubSecTime","SubSecTimeOriginal","SubSecTimeDigitized"})exif.setAttribute(tag,sub);exif.saveAttributes();if(!frozen.setLastModified(now))throw new IOException("无法设置导出副本时间。");PhotoPreviewCache.validateJpeg(frozen,frozen.length());if(!sourceSha.equals(PhotoEditStore.hashFile(source)))throw new IOException("效果图已变化，未继续导出。");
                Receipt receipt=new Receipt(name,"",PhotoEditStore.hashFile(frozen),frozen.length(),now);rows.add(receipt);write(file,rows);receipt.uri=exact.publishExact(new DownloadedPhoto(frozen,photo.filename(),photo.projectId(),photo.projectName()));write(file,rows);if(!exists(receipt))throw new IOException("相册尚未确认导出副本。");if(PhotoPresetSources.isEffect(source)&&!source.setLastModified(now))throw new IOException("效果已导出，本地修改时间未更新。");Files.deleteIfExists(frozen.toPath());receipt.stage="";write(file,rows);return receipt.uri;
            }catch(IOException|RuntimeException failure){boolean recorded=false;for(Receipt r:rows)if(r.stage.equals(name))recorded=true;if(!recorded)Files.deleteIfExists(frozen.toPath());throw failure;}
        }catch(RuntimeException failure){throw new IOException("效果图相册保存未完成，原图和渲染结果已保留。",failure);}}
    }
    PhotoLiveGallery.PublicationState status(DownloadedPhoto photo){try(PhotoLocalTasks.Lease lease=PhotoLocalTasks.use()){synchronized(LOCK){File source=exact.checkedEffectSource(photo);List<Receipt> rows=readSource(journals(source));if(rows!=null)for(int i=rows.size()-1;i>=0;i--){Receipt r=rows.get(i);if(r.uri.isEmpty())return new PhotoLiveGallery.PublicationState(PhotoLiveGallery.PublicationStatus.PENDING,"","");if(exists(r))return new PhotoLiveGallery.PublicationState(PhotoLiveGallery.PublicationStatus.PUBLISHED,r.uri,"");}return fallbackStatus(photo,true);}}catch(OutOfMemoryError memory){return new PhotoLiveGallery.PublicationState(PhotoLiveGallery.PublicationStatus.UNAVAILABLE,"","系统相册核对内存不足，应用内照片已保留，请重试。");}catch(Exception failure){return new PhotoLiveGallery.PublicationState(PhotoLiveGallery.PublicationStatus.UNAVAILABLE,"",failure.getMessage());}}
    boolean delete(DownloadedPhoto photo)throws IOException{synchronized(LOCK){File source=exact.checkedEffectSource(photo);List<AtomicFile> files=journals(source);AtomicFile file=files.get(0);List<Receipt> rows=readSource(files);if(rows==null)return false;
        PhotoLiveGallery.PublicationState legacy=exact.exactPublicationStatus(photo,false);if(legacy.status==PhotoLiveGallery.PublicationStatus.UNAVAILABLE||legacy.status==PhotoLiveGallery.PublicationStatus.PENDING)throw new IOException("旧相册副本尚未通过核对，未删除任何导出照片。");if(legacy.status==PhotoLiveGallery.PublicationStatus.PUBLISHED){boolean known=false;for(Receipt r:rows)if(r.uri.equals(legacy.uri))known=true;if(!known){rows.add(new Receipt("",legacy.uri,PhotoEditStore.hashFile(source),source.length(),0));write(file,rows);}}
        // Check every recorded public copy before deleting any. Foreign or modified copies block the operation.
        for(Receipt r:rows)if(!r.uri.isEmpty())exists(r);else{File frozen=stage(r.stage);if(!frozen.isFile()||frozen.length()!=r.bytes||!PhotoEditStore.hashFile(frozen).equals(r.sha))throw new IOException("未完成导出副本无法核对，未删除任何相册照片。");}
        for(Receipt r:rows){if(!r.uri.isEmpty()&&exists(r)){context.getContentResolver().delete(uri(r.uri),null,null);try(Cursor cursor=context.getContentResolver().query(uri(r.uri),new String[]{MediaStore.Images.Media._ID},null,null,null)){if(cursor==null||cursor.moveToFirst())throw new IOException("系统相册尚未确认删除。");}}else if(r.uri.isEmpty()){File frozen=stage(r.stage);exact.deletePublished(new DownloadedPhoto(frozen,photo.filename(),photo.projectId(),photo.projectName()));}if(!r.stage.isEmpty())Files.deleteIfExists(stage(r.stage).toPath());}
        for(AtomicFile journal:files)journal.delete();return true;}}
}

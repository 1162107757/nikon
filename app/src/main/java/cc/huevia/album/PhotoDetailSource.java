package cc.huevia.album;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.FileObserver;
import cc.huevia.ncp.PresetPreviewProfile;
import cc.huevia.ui.PhotoDetailViewport;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** One frozen page: two lossless full-resolution disk surfaces, bounded region/overview RAM. */
public final class PhotoDetailSource implements AutoCloseable {
    public static final int CURRENT=0, BEFORE=1;
    private static final int TILE=512, CACHE_BYTES=12*1024*1024;
    private static final Set<String> LIVE_DIRECTORIES=Collections.synchronizedSet(new HashSet<>());
    public static final class Request {
        public final Context context; public final File file; public final String sha,identity;
        public final PresetPreviewProfile profile; public final boolean canCompare;
        public final BooleanSupplier stillValid;
        public Request(Context context,File verifiedFile,String sha,String identity,PresetPreviewProfile profile,boolean canCompare,BooleanSupplier stillValid){
            if(context==null||verifiedFile==null||sha==null||!sha.matches("[a-fA-F0-9]{64}")||identity==null||identity.isEmpty()||stillValid==null||canCompare&&profile==null)throw new IllegalArgumentException("细节快照无效。");
            this.context=context.getApplicationContext();this.file=verifiedFile.getAbsoluteFile();this.sha=sha;this.identity=identity;
            this.profile=profile;this.canCompare=canCompare;this.stillValid=stillValid;
        }
    }
    public static final class Tile {
        public final Rect bounds;public final Bitmap bitmap;
        Tile(Rect bounds,Bitmap bitmap){this.bounds=bounds;this.bitmap=bitmap;}
    }
    public static final class Frame {
        public final int mode,width,height,sample;public final Bitmap overview;public final List<Tile> tiles;
        Frame(int mode,int width,int height,int sample,Bitmap overview,List<Tile> tiles){this.mode=mode;this.width=width;this.height=height;this.sample=sample;this.overview=overview;this.tiles=Collections.unmodifiableList(tiles);}
    }
    public interface Callback {
        void loaded(Frame frame);
        void failed(String message);
        void invalidated();
    }
    private static final PresetPreviewProfile UNCHANGED=new PresetPreviewProfile(){
        public String registrationName(){return "已保存版本";}public String modelVersion(){return "saved-file-pixels-v1";}
        public double mapTone(double v){return v;}public int applyArgb(int v){return v;}
    };
    private static final class Surface {final File file;final BitmapRegionDecoder decoder;final Bitmap overview;final int width,height;
        Surface(File file,BitmapRegionDecoder decoder,Bitmap overview){this.file=file;this.decoder=decoder;this.overview=overview;this.width=decoder.getWidth();this.height=decoder.getHeight();}}
    private final Request request;private final File directory;private final Handler main=new Handler(Looper.getMainLooper());
    private final long sourceLength,sourceModified;private final FileObserver observer;
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"photo-real-detail");t.setDaemon(true);return t;},new ThreadPoolExecutor.DiscardOldestPolicy());
    private final Surface[] surfaces=new Surface[2];
    private final LinkedHashMap<String,Bitmap> cache=new LinkedHashMap<>(32,.75f,true);
    private int cacheBytes;private boolean pruned;private volatile long serial;private volatile boolean closed,sourceChanged,releasePending;
    @SuppressWarnings("deprecation") public PhotoDetailSource(Request request){
        this.request=request;directory=new File(request.context.getCacheDir(),"photo-detail-"+UUID.randomUUID());
        LIVE_DIRECTORIES.add(directory.getName());
        sourceLength=request.file.length();sourceModified=request.file.lastModified();
        observer=new FileObserver(request.file.getParent(),FileObserver.CLOSE_WRITE|FileObserver.DELETE|FileObserver.MOVED_FROM|FileObserver.MODIFY|FileObserver.ATTRIB|FileObserver.DELETE_SELF|FileObserver.MOVE_SELF){public void onEvent(int event,String path){if(path==null||request.file.getName().equals(path))sourceChanged=true;}};
        observer.startWatching();
    }
    public boolean canCompare(){return request.canCompare;}
    public boolean valid(){return !closed&&!sourceChanged&&request.file.length()==sourceLength&&request.file.lastModified()==sourceModified&&request.stillValid.getAsBoolean();}
    public void cancelFrames(){serial++;worker.getQueue().clear();}
    /** Called after the view drops its frame when the Activity enters the background. */
    public void releaseMemory(){if(closed)return;cancelFrames();releasePending=true;worker.execute(this::drainRelease);}
    private void drainRelease(){if(releasePending){releasePending=false;release();}}
    public void frame(int mode,int canvasWidth,int canvasHeight,double scale,double nx,double ny,Callback callback){
        if(closed)return;
        final long token=++serial;
        worker.execute(()->{
            try{
                drainRelease();
                check();if(mode==BEFORE&&!request.canCompare)throw new IOException("此版本暂无法恢复调色前内容。");
                Surface surface=prepare(mode);if(token!=serial)return;
                PhotoDetailViewport viewport=new PhotoDetailViewport();viewport.canvas(canvasWidth,canvasHeight);viewport.image(surface.width,surface.height);
                if(scale>0){viewport.fitting=false;viewport.scale=scale;viewport.center(nx*surface.width,ny*surface.height);}
                else viewport.fitImage();
                int sample=viewport.sample(),step=TILE*sample;
                int left=Math.max(0,(int)Math.floor(viewport.centerX-canvasWidth/(2*viewport.scale))),top=Math.max(0,(int)Math.floor(viewport.centerY-canvasHeight/(2*viewport.scale)));
                int right=Math.min(surface.width,(int)Math.ceil(viewport.centerX+canvasWidth/(2*viewport.scale))),bottom=Math.min(surface.height,(int)Math.ceil(viewport.centerY+canvasHeight/(2*viewport.scale)));
                List<Tile> tiles=new ArrayList<>();long framePixels=0;
                for(int y=top/step*step;y<bottom;y+=step)for(int x=left/step*step;x<right;x+=step){
                    check();if(token!=serial)return;
                    Rect region=new Rect(x,y,Math.min(x+step,surface.width),Math.min(y+step,surface.height));
                    String key=request.identity+":"+mode+":"+sample+":"+x+":"+y;
                    Bitmap bitmap=cache.get(key);
                    if(bitmap==null){bitmap=surface.decoder.decodeRegion(region,options(sample));if(bitmap==null)throw new IOException("细节区域解码失败。");put(key,bitmap);}
                    framePixels+=(long)bitmap.getWidth()*bitmap.getHeight();
                    if(framePixels>8_388_608L)throw new IOException("此视口所需内存过多，请返回后重试。");
                    tiles.add(new Tile(region,bitmap));
                }
                Frame frame=new Frame(mode,surface.width,surface.height,sample,surface.overview,tiles);
                main.post(()->{if(closed||token!=serial)return;if(!valid()){callback.invalidated();return;}callback.loaded(frame);});
            }catch(Exception|OutOfMemoryError failure){
                String message=failure instanceof OutOfMemoryError?"细节暂时无法载入，返回后可重试。":failure.getMessage();
                main.post(()->{if(closed||token!=serial)return;if(!valid()){callback.invalidated();return;}callback.failed(message==null?"无法载入细节，请重试。":message);});
            }
        });
    }
    private Surface prepare(int mode)throws IOException{
        Surface existing=surfaces[mode];if(existing!=null)return existing;
        check();if(!request.file.isFile()||!request.sha.equalsIgnoreCase(PhotoEditStore.hashFile(request.file)))throw new IOException("照片文件已变化，请返回后重新打开。");
        if(!pruned){pruned=true;pruneOrphans();}
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("细节暂存目录不可用，请检查存储空间。");
        File target=new File(directory,mode==CURRENT?"current.png":"before.png");
        BitmapRegionDecoder decoder=null;Bitmap overview=null;
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(request.file.getPath(),bounds);
            if(bounds.outWidth<1||bounds.outHeight<1||!"image/jpeg".equals(bounds.outMimeType))throw new IOException("仅支持已校验的完整 JPEG 文件。");
            long workingDisk=(long)bounds.outWidth*bounds.outHeight*4+8L*1024*1024;
            if(directory.getUsableSpace()<workingDisk)throw new IOException("存储空间不足，无法准备完整细节。原图已保留。");
            new PhotoPresetRenderer(request.context.getAssets()).renderDetail(request.file,target,request.canCompare?request.profile:UNCHANGED,mode==BEFORE||!request.canCompare,()->!valid()||Thread.currentThread().isInterrupted(),null);
            check();if(!request.sha.equalsIgnoreCase(PhotoEditStore.hashFile(request.file)))throw new IOException("照片文件已变化，请返回后重新打开。");
            decoder=BitmapRegionDecoder.newInstance(target.getPath(),false);if(decoder==null)throw new IOException("无法读取完整细节。");
            Surface other=surfaces[1-mode];if(other!=null&&(decoder.getWidth()!=other.width||decoder.getHeight()!=other.height))throw new IOException("前后构图尺寸不一致，未显示错误对比。");
            int sample=1;while(Math.max(decoder.getWidth(),decoder.getHeight())/(double)sample>1024)sample*=2;
            overview=BitmapFactory.decodeFile(target.getPath(),options(sample));if(overview==null)throw new IOException("细节定位图读取失败。");
            Surface created=new Surface(target,decoder,overview);surfaces[mode]=created;return created;
        }catch(IOException|RuntimeException|OutOfMemoryError failure){if(decoder!=null)decoder.recycle();if(overview!=null)overview.recycle();target.delete();throw failure;}
    }
    private static BitmapFactory.Options options(int sample){BitmapFactory.Options o=new BitmapFactory.Options();o.inScaled=false;o.inSampleSize=sample;o.inPreferredConfig=Bitmap.Config.ARGB_8888;o.inPreferredColorSpace=ColorSpace.get(ColorSpace.Named.SRGB);return o;}
    private void check()throws IOException{if(!valid()||Thread.currentThread().isInterrupted())throw new IOException("细节页面已失效。");}
    private void put(String key,Bitmap bitmap){
        cache.put(key,bitmap);cacheBytes+=bitmap.getAllocationByteCount();
        while(cacheBytes>CACHE_BYTES&&!cache.isEmpty()){Map.Entry<String,Bitmap> first=cache.entrySet().iterator().next();cacheBytes-=first.getValue().getAllocationByteCount();cache.remove(first.getKey());}
        // Removed bitmaps can still be drawn by the current immutable frame; GC owns their final release.
    }
    private void release(){cache.clear();cacheBytes=0;for(int i=0;i<surfaces.length;i++){Surface s=surfaces[i];surfaces[i]=null;if(s!=null){s.decoder.recycle();s.file.delete();}}File[] rest=directory.listFiles();if(rest!=null)for(File file:rest)file.delete();directory.delete();}
    /** Only our flat UUID directories, never arbitrary cache children or another live page. */
    private void pruneOrphans(){
        File[] children=request.context.getCacheDir().listFiles();if(children==null)return;
        synchronized(LIVE_DIRECTORIES){for(File child:children){
            if(!child.isDirectory()||!child.getName().matches("photo-detail-[a-f0-9-]{36}")||LIVE_DIRECTORIES.contains(child.getName()))continue;
            File[] files=child.listFiles();if(files!=null)for(File file:files)if(file.isFile()&&(file.getName().equals("current.png")||file.getName().equals("before.png")))file.delete();child.delete();
        }}
    }
    @Override public void close(){if(closed)return;closed=true;observer.stopWatching();serial++;worker.getQueue().clear();worker.execute(()->{release();LIVE_DIRECTORIES.remove(directory.getName());});worker.shutdown();}
}

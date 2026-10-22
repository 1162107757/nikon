package cc.huevia.album;

import android.content.Context;
import cc.huevia.ptp.PtpObjectInfo;
import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Successful/ambiguous automatic pairing across actual PTP/IP frames and both card layouts. */
public final class RawPairTransportHostTest {
    private static int checks;
    public static void main(String[] args)throws Exception {
        Path base=Files.createTempDirectory("raw-pair-transport-");
        for(boolean dual:new boolean[]{false,true}){
            Peer peer=new Peer(dual,false);CameraWifiNetwork.testConnector=peer::socket;
            try(AlbumController controller=new AlbumController(new Context(base.resolve("pair-"+dual).toFile()))){
                RawPairTransportHostTest.<ConnectionInfo>call(cb->controller.connect("camera.test",15740,cb));PhotoPage page=call(cb->controller.loadPage(0,20,cb));
                check(page.items().size()==1,"JPEG inventory remains format filtered");
                RawResult result=new RawResult();controller.downloadRawForJpeg(page.items().get(0),result);RawPhotoDownload saved=result.value();
                check(Arrays.equals(peer.raw,Files.readAllBytes(saved.file().toPath())),"Unique RAW+JPG pair preserves exact NEF bytes across "+(dual?"two cards":"one card"));
                check(peer.downloads.equals(List.of(43L)),"Only uniquely matched RAW object is downloaded");
                check(saved.sourceIdentity().equals(controller.stablePhotoIdentity(page.items().get(0))),"JPEG-to-NEF alias uses original JPEG identity");
            }
        }
        Peer peer=new Peer(true,true);CameraWifiNetwork.testConnector=peer::socket;
        try(AlbumController controller=new AlbumController(new Context(base.resolve("ambiguous").toFile()))){
            RawPairTransportHostTest.<ConnectionInfo>call(cb->controller.connect("camera.test",15740,cb));PhotoPage page=call(cb->controller.loadPage(0,20,cb));RawResult result=new RawResult();controller.downloadRawForJpeg(page.items().get(0),result);
            check(result.error().contains("多个同名")&&peer.downloads.isEmpty(),"Second same-name NEF on another card fails before any GetObject");
            check(result.selectionRequired,"Ambiguous automatic pair requires explicit selection without guessing");
        }
        for(String reason:List.of("name","no-nef","zero-guid")){
            Peer refused=new Peer(false,false);CameraWifiNetwork.testConnector=refused::socket;
            if(reason.equals("name"))refused.info.put(43L,Peer.descriptor(1,7,0x3802,"OTHER.NEF",refused.raw.length));
            if(reason.equals("no-nef"))refused.info.remove(43L);
            if(reason.equals("zero-guid"))refused.trusted=false;
            try(AlbumController controller=new AlbumController(new Context(base.resolve(reason).toFile()))){
                RawPairTransportHostTest.<ConnectionInfo>call(cb->controller.connect("camera.test",15740,cb));PhotoPage page=call(cb->controller.loadPage(0,20,cb));
                RawResult result=new RawResult();controller.downloadRawForJpeg(page.items().get(0),result);String error=result.error();
                check(result.selectionRequired&&refused.downloads.isEmpty(),reason+" cannot automatically download a substitute RAW");
                check(error.contains("核对 "+refused.info.size()+" 个对象")&&error.contains(reason.equals("no-nef")?"列出 0 个 NEF":"列出 1 个 NEF"),"Selection failure includes only actual inventory counts");
                if(reason.equals("no-nef"))check(error.contains("当前连接未列出 NEF")&&!error.contains("未开启 RAW"),"Empty remote inventory never claims the card has no RAW");
                if(reason.equals("name")){
                    check(error.contains("已找到 NEF"),"NEF present but unmatched is distinct from an empty NEF inventory");
                    PhotoPage raw=call(cb->controller.loadRawPage(0,20,cb));RawResult explicit=new RawResult();controller.downloadRaw(raw.items().get(0),explicit);
                    check(!explicit.selectionRequired&&Arrays.equals(refused.raw,Files.readAllBytes(explicit.value().file().toPath()))
                            &&refused.downloads.equals(List.of(43L)),"Explicitly selected NEF succeeds with unchanged bytes after automatic name match was refused");
                }
            }
        }
        System.out.println("PASS: "+checks+" successful RAW pairing/PTP transport checks");
    }
    private static final class Peer {
        final byte[]raw=RawFormatPairingTest.fixture(true);final Map<Long,PtpObjectInfo>info=new LinkedHashMap<>();final List<Long>downloads=new ArrayList<>();int sockets;boolean trusted=true;
        Peer(boolean dual,boolean duplicate){
            info.put(42L,descriptor(1,7,0x3801,"DSC_0042.JPG",32));info.put(43L,descriptor(dual?2:1,dual?9:7,0x3802,"DSC_0042.NEF",raw.length));
            if(duplicate)info.put(44L,descriptor(1,7,0x3802,"DSC_0042.NEF",raw.length));
        }
        private static PtpObjectInfo descriptor(long storage,long parent,int format,String name,int bytes){return new PtpObjectInfo.Builder().storageId(storage).parentObject(parent).objectFormat(format).filename(name).compressedSize(bytes).captureDate("20261018T204501").sequenceNumber(42).build();}
        Socket socket(String host,int port,int timeout){return new FakeSocket(this,sockets++%2!=0);}
        void receive(FakeSocket socket,byte[]frame)throws IOException{
            int type=(int)u32(frame,4);if(type==3){socket.input.add(packet(4,new byte[0]));return;}
            if(type==1){byte[]guid=new byte[16];if(trusted)Arrays.fill(guid,(byte)8);socket.input.add(packet(2,join(i32(1),guid,"RAW test\0".getBytes(StandardCharsets.UTF_16LE),i32(0x10000))));return;}
            if(type!=6)throw new IOException("Unexpected frame");int op=u16(frame,12);long txn=u32(frame,14),handle=frame.length>=22?u32(frame,18):0;byte[]data;
            switch(op){
                case 0x1002:case 0x1003:socket.input.add(response(txn));return;
                case 0x1001:try{java.lang.reflect.Method method=AlbumControllerHostTest.class.getDeclaredMethod("info",boolean.class,boolean.class,String.class,int.class,String.class);method.setAccessible(true);data=(byte[])method.invoke(null,true,false,"RAW camera",-1,"Nikon");}catch(Exception e){throw new IOException(e);}break;
                case 0x1004:data=join(i32(2),i32(1),i32(2));break;
                case 0x1007:{long format=u32(frame,22);List<Long>selected=new ArrayList<>();for(Map.Entry<Long,PtpObjectInfo>entry:info.entrySet())if(entry.getValue().storageId()==handle&&(format==0||format==entry.getValue().objectFormat()))selected.add(entry.getKey());ByteArrayOutputStream out=new ByteArrayOutputStream();out.writeBytes(i32(selected.size()));for(long id:selected)out.writeBytes(i32(id));data=out.toByteArray();break;}
                case 0x1008:data=info.get(handle).toBytes();break;
                case 0x1009:downloads.add(handle);data=raw;break;
                default:throw new IOException("Unexpected operation "+Integer.toHexString(op));
            }
            socket.input.add(packet(9,join(i32(txn),ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(data.length).array())));
            socket.input.add(packet(12,join(i32(txn),data)));socket.input.add(response(txn));
        }
    }
    private static final class FakeSocket extends Socket {
        final Peer peer;final boolean event;final QueueInput input=new QueueInput();boolean closed;
        FakeSocket(Peer peer,boolean event){this.peer=peer;this.event=event;}
        @Override public InputStream getInputStream(){return input;}
        @Override public OutputStream getOutputStream(){return new OutputStream(){final ByteArrayOutputStream pending=new ByteArrayOutputStream();public void write(int b)throws IOException{write(new byte[]{(byte)b},0,1);}public void write(byte[]b,int off,int len)throws IOException{pending.write(b,off,len);while(pending.size()>=8){byte[]all=pending.toByteArray();int count=(int)u32(all,0);if(all.length<count)return;pending.reset();pending.write(all,count,all.length-count);peer.receive(FakeSocket.this,Arrays.copyOf(all,count));}}};}
        @Override public void setSoTimeout(int value){}@Override public void setTcpNoDelay(boolean value){}@Override public boolean isConnected(){return true;}@Override public boolean isClosed(){return closed;}@Override public void close(){closed=true;input.close();}
    }
    private static final class QueueInput extends InputStream{final Deque<byte[]>chunks=new ArrayDeque<>();int offset;boolean closed;synchronized void add(byte[]b){chunks.add(b);notifyAll();}public int read()throws IOException{byte[]b=new byte[1];return read(b,0,1)==-1?-1:b[0]&255;}public synchronized int read(byte[]b,int off,int len)throws IOException{while(chunks.isEmpty()&&!closed)try{wait();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}if(closed)return -1;int count=Math.min(len,chunks.peek().length-offset);System.arraycopy(chunks.peek(),offset,b,off,count);offset+=count;if(offset==chunks.peek().length){chunks.remove();offset=0;}return count;}public synchronized void close(){closed=true;notifyAll();}}
    private static byte[]packet(int type,byte[]body){return join(i32(body.length+8),i32(type),body);}private static byte[]response(long transaction){return packet(7,join(new byte[]{1,0x20},i32(transaction)));}private static byte[]i32(long n){return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int)n).array();}private static int u16(byte[]b,int o){return (b[o]&255)|((b[o+1]&255)<<8);}private static long u32(byte[]b,int o){return Integer.toUnsignedLong(ByteBuffer.wrap(b,o,4).order(ByteOrder.LITTLE_ENDIAN).getInt());}private static byte[]join(byte[]...all){ByteArrayOutputStream out=new ByteArrayOutputStream();for(byte[]b:all)out.writeBytes(b);return out.toByteArray();}
    private static <T>T call(Consumer<AlbumController.Callback<T>>action)throws Exception{Result<T>r=new Result<>();action.accept(r);return r.value();}
    private static class Result<T>implements AlbumController.Callback<T>{final CountDownLatch done=new CountDownLatch(1);T result;String error;public void onSuccess(T value){result=value;done.countDown();}public void onError(String value){error=value;done.countDown();}void await()throws Exception{if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("Pair callback timed out");}T value()throws Exception{await();if(error!=null)throw new AssertionError(error);return result;}String error()throws Exception{await();if(error==null)throw new AssertionError("Expected pairing rejection");return error;}}
    private static final class RawResult extends Result<RawPhotoDownload>implements AlbumController.RawDownloadCallback{boolean selectionRequired;public void onProgress(long r,long t){}public void onSelectionRequired(String message){selectionRequired=true;onError(message);}}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}

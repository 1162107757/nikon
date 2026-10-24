package cc.huevia.album;

import java.io.*;
import java.nio.*;

/** The framework EXIF writer quantizes compatibility rationals to /10000 (e.g. 1/640).
 * Preserve the original unsigned rationals after it has finished writing its TIFF directory. */
public final class PhotoExifRationals {
    private PhotoExifRationals(){}
    private static final class Field {final long offset,numerator,denominator;final ByteOrder order;Field(long o,long n,long d,ByteOrder b){offset=o;numerator=n;denominator=d;order=b;}}
    public static void preserve(File source,File target)throws IOException{
        for(int tag:new int[]{0x829a,0x829d,0x920a}){Field from=find(source,tag);if(from==null)continue;Field to=find(target,tag);if(to==null)throw new IOException("效果图拍摄参数未完整保留，未保存。");try(RandomAccessFile file=new RandomAccessFile(target,"rw")){ByteBuffer bytes=ByteBuffer.allocate(8).order(to.order);bytes.putInt((int)from.numerator).putInt((int)from.denominator);file.seek(to.offset);file.write(bytes.array());}}
    }
    private static Field find(File path,int tag)throws IOException{
        try(RandomAccessFile file=new RandomAccessFile(path,"r")){if(file.length()<4||file.readUnsignedShort()!=0xffd8)return null;while(file.getFilePointer()+4<=file.length()){
            if(file.readUnsignedByte()!=255)return null;int marker=file.readUnsignedByte();while(marker==255)marker=file.readUnsignedByte();if(marker==0xda||marker==0xd9)return null;int length=file.readUnsignedShort()-2;if(length<0||file.getFilePointer()+length>file.length())return null;long start=file.getFilePointer();
            if(marker==0xe1&&length>=14){byte[] bytes=new byte[length];file.readFully(bytes);if(bytes[0]=='E'&&bytes[1]=='x'&&bytes[2]=='i'&&bytes[3]=='f'&&bytes[4]==0&&bytes[5]==0){ByteOrder order=bytes[6]=='I'&&bytes[7]=='I'?ByteOrder.LITTLE_ENDIAN:bytes[6]=='M'&&bytes[7]=='M'?ByteOrder.BIG_ENDIAN:null;if(order==null)return null;ByteBuffer data=ByteBuffer.wrap(bytes).order(order);if(u16(data,8)!=42)return null;int root=offset(data,10);if(root<0)return null;int exif=entry(data,root,0x8769);if(exif<0)return null;int pointer=offset(data,exif+8);if(pointer<0)return null;int field=entry(data,pointer,tag);if(field<0||u16(data,field+2)!=5||u32(data,field+4)!=1)return null;int value=offset(data,field+8);if(value<0||value+8>bytes.length)return null;long n=u32(data,value),d=u32(data,value+4);return d==0?null:new Field(start+value,n,d,order);}}
            file.seek(start+length);
        }return null;}catch(IndexOutOfBoundsException|BufferUnderflowException malformed){return null;}
    }
    private static int entry(ByteBuffer b,int directory,int wanted){if(directory<6||directory+2>b.limit())return -1;int count=u16(b,directory);if((long)directory+2+12L*count>b.limit())return -1;for(int i=0;i<count;i++){int at=directory+2+i*12;if(u16(b,at)==wanted)return at;}return -1;}
    private static int offset(ByteBuffer b,int at){long value=u32(b,at)+6;return value>=6&&value<b.limit()?(int)value:-1;}
    private static int u16(ByteBuffer b,int p){return b.getShort(p)&65535;}private static long u32(ByteBuffer b,int p){return b.getInt(p)&0xffffffffL;}
}

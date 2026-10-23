package cc.huevia.album;

import android.graphics.*;
import cc.huevia.ncp.PhotoGeometry;

/** Pixel composition shared by the native editor and the full-size JPEG writer. */
public final class PhotoComposition {
    private PhotoComposition(){}
    public static Bitmap apply(Bitmap source,PhotoGeometry geometry,int exif){
        if(geometry.isNeutral()&&exif==1)return source;
        PhotoGeometry.Plan plan=geometry.plan(source.getWidth(),source.getHeight(),exif);
        Bitmap output=Bitmap.createBitmap(plan.width,plan.height,Bitmap.Config.ARGB_8888);
        try{Canvas canvas=new Canvas(output);canvas.drawColor(Color.BLACK);Matrix matrix=new Matrix();matrix.setValues(plan.matrix());canvas.drawBitmap(source,matrix,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));return output;}
        catch(RuntimeException|Error failure){output.recycle();throw failure;}
    }
}

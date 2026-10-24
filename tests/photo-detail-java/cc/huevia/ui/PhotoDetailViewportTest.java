package cc.huevia.ui;

/** Meaningful 1:1 coordinate/anchor checks without relying on Android density. */
public final class PhotoDetailViewportTest {
    private static int checks;
    public static void main(String[] args){
        PhotoDetailViewport v=new PhotoDetailViewport();v.canvas(1080,1800);v.image(6000,4000);
        near(.18,v.fit(),"landscape photo fit on portrait physical canvas");near(.18,v.scale,"initial fit");
        near(3000,v.centerX,"initial center x");near(2000,v.centerY,"initial center y");
        v.zoom(1,540,900);near(1,v.scale,"100% independent of display density");near(-2460,v.left(),"100% physical x transform");
        near(1,(2461+v.left())-(2460+v.left()),"adjacent pixels exactly one canvas px");
        near(1,v.sample(),"100% decodes full-resolution regions");
        v.zoom(2,540,900);near(2,v.scale,"200%");near(2,(2461*2+v.left())-(2460*2+v.left()),"200% adjacent pixels two px");
        v.zoom(12,540,900);near(2,v.scale,"maximum two times");
        v.fitImage();double fx=710,fy=940;double sourceX=(fx-v.left())/v.scale,sourceY=(fy-v.top())/v.scale;
        v.zoom(1,fx,fy);near(sourceX,(fx-v.left())/v.scale,"double-tap x anchor preserved");near(sourceY,(fy-v.top())/v.scale,"double-tap y anchor preserved");
        v.move(1e6,1e6);near(0,v.left(),"left edge cannot expose blank canvas");near(0,v.top(),"top edge cannot expose blank canvas");
        v.move(-2e6,-2e6);near(1080,v.left()+6000*v.scale,"right edge constrained");near(1800,v.top()+4000*v.scale,"bottom edge constrained");
        v.center(3000,2000);v.zoom(1,540,900);double cx=v.centerX,cy=v.centerY,scale=v.scale;
        v.image(6000,4000);near(cx,v.centerX,"switch current/before preserves center x");near(cy,v.centerY,"switch mode preserves center y");near(scale,v.scale,"switch mode preserves scale");
        v.canvas(1800,1080);near(1,v.scale,"orientation keeps physical 100%");near(cx,v.centerX,"rotation preserves feasible center");
        v.fitImage();near(.27,v.scale,"fit responds to landscape canvas");
        PhotoDetailViewport small=new PhotoDetailViewport();small.canvas(1080,1800);small.image(640,360);
        near(1,small.scale,"small image not auto-upscaled");near(220,small.left(),"small image centered x");near(720,small.top(),"small image centered y");
        small.move(500,700);near(320,small.centerX,"small axis cannot pan x");near(180,small.centerY,"small axis cannot pan y");
        small.zoom(.01,540,900);near(1,small.scale,"pinch minimum fit");
        PhotoDetailViewport panorama=new PhotoDetailViewport();panorama.canvas(1080,1800);panorama.image(20000,1000);
        near(.054,panorama.fit(),"panorama fit");near(16,panorama.sample(),"power-of-two sample never worse than canvas scale");
        panorama.zoom(1,540,900);panorama.center(10000,0);near(500,panorama.centerY,"short axis centered while long axis pans");
        near(400,panorama.top(),"no off-axis drift");
        for(int w:new int[]{1,639,6013,20000})for(int h:new int[]{1,359,4001}){
            PhotoDetailViewport odd=new PhotoDetailViewport();odd.canvas(1079,1837);odd.image(w,h);odd.zoom(1,539.5,918.5);
            near(1,odd.scale,"odd dimensions 1:1 "+w+"x"+h);odd.center(-100000,100000);
            check(odd.centerX>=0&&odd.centerX<=w&&odd.centerY>=0&&odd.centerY<=h,"odd boundary "+w+"x"+h);
        }
        System.out.println("PASS PhotoDetailViewportTest "+checks+" checks");
    }
    private static void near(double expected,double actual,String message){checks++;if(Math.abs(expected-actual)>1e-6)throw new AssertionError(message+": expected "+expected+" actual "+actual);}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}

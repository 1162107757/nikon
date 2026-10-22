package cc.huevia.ui;

/** Host-only gesture decisions. Native dispatch, child CANCEL and drawing require device checks. */
public final class PullRefreshGestureTest {
    private static int checks;
    public static void main(String[] args) {
        for(float density:new float[]{1f,2f,3f}) {
            float slop=8*density,threshold=64*density;
            for(int distance=0;distance<=100;distance++) {
                PullRefreshLayout.Gesture gesture=new PullRefreshLayout.Gesture(slop,threshold);
                gesture.begin(20,30,true);
                boolean captured=gesture.move(20,30+distance*density,true);
                check(captured==(distance>8),"intercept only after slop");
                check(gesture.finish(20,30+distance*density,true)==(distance>=64),"release threshold across densities");
                check(!gesture.finish(20,2000,true),"one release cannot repeat");
            }
        }
        PullRefreshLayout.Gesture gesture=new PullRefreshLayout.Gesture(8,64);
        gesture.begin(0,0,true);check(!gesture.finish(0,90,true),"an UP jump without a captured MOVE cannot refresh");
        gesture.begin(0,0,true);check(!gesture.move(100,20,true),"horizontal scroll belongs to child");check(!gesture.move(100,200,true),"horizontal gesture cannot later be stolen");check(!gesture.finish(100,200,true),"horizontal gesture cannot refresh");
        gesture.begin(0,0,true);check(!gesture.move(0,-30,true),"upward scroll belongs to child");check(!gesture.move(0,100,true),"upward gesture cannot be reclaimed");
        gesture.begin(0,0,true);check(!gesture.move(10,11,true),"diagonal movement needs a clear vertical direction");check(gesture.move(10,30,true),"clear downward direction may capture");
        gesture.begin(0,0,true);gesture.move(0,100,true);check(!gesture.finish(0,40,true),"retracting before release cancels threshold");
        gesture.begin(0,0,true);gesture.move(0,100,true);gesture.cancel();check(!gesture.finish(0,100,true),"cancel, multiple pointers or lost focus never refresh");check(!gesture.eligible&&!gesture.dragging&&gesture.pull==0,"cancellation clears gesture state");
        gesture.begin(0,0,false);check(!gesture.move(0,100,true),"blocked or non-top starting gesture stays with child");
        gesture.begin(0,0,true);gesture.move(0,100,true);check(!gesture.finish(0,100,false),"eligibility revoked before release prevents refresh");
        gesture.begin(0,0,true);gesture.move(0,100,false);check(!gesture.finish(0,100,true),"revoked eligibility cannot resume old pull");
        gesture.begin(0,0,true);gesture.move(0,100,true);check(!gesture.move(120,100,true),"horizontal direction change cancels a captured pull");
        gesture.begin(Float.NaN,0,true);check(!gesture.move(0,100,true),"invalid pointer start is rejected");
        gesture.begin(0,0,true);check(!gesture.move(0,Float.POSITIVE_INFINITY,true),"invalid pointer move is rejected");
        gesture.begin(0,0,true);check(gesture.move(0,100,true)&&gesture.finish(0,100,true),"new gesture can refresh after cancellation");
        System.out.println("Pull refresh gesture PASS "+checks);
    }
    private static void check(boolean value,String reason) { checks++;if(!value)throw new AssertionError(reason); }
}

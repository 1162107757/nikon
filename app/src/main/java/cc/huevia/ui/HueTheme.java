package cc.huevia.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;
import android.widget.Button;

/**
 * THESIS: Orange light enters the photographic workflow; photographs remain the content.
 * OWN-WORLD: warm white layers, orange/peach light, charcoal capsules, system sans.
 * STORY: discover a real preset, connect a camera, keep and edit the original photograph.
 * FIRST VIEWPORT: compact luminous header, source control, connection state, categories,
 * two-column photographic presets; a four-destination bar and one add action.
 * FORM: approved detailed/index.html, 2026-10-23; native Android Views, real controllers.
 * FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review,
 * the verdict, DESIGN.md, and every shipping raster carrying its provenance
 */
public final class HueTheme {
    public static final int PHOTO_CONTROL_SCRIM = 0xB8262320;
    private HueTheme() {}
    public static GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color);
        drawable.setCornerRadius(radius); return drawable;
    }
    public static Drawable action(Context context, UiPalette p, float radiusDp) {
        float radius = radiusDp * context.getResources().getDisplayMetrics().density;
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{p.action, p.actionEnd}); face.setCornerRadius(radius);
        StateListDrawable state = new StateListDrawable();
        state.addState(new int[]{-android.R.attr.state_enabled}, rounded(p.container, radius));
        state.addState(new int[]{}, new RippleDrawable(ColorStateList.valueOf(p.dark ? 0x22000000 : 0x33FFFFFF),
                face, rounded(Color.WHITE, radius)));
        return state;
    }
    public static void styleAction(Button button, UiPalette p, boolean filled) {
        float density = button.getResources().getDisplayMetrics().density;
        button.setBackgroundTintList(null); button.setStateListAnimator(null); button.setElevation(0);
        button.setMinHeight(Math.round(48*density)); button.setMinimumHeight(Math.round(48*density));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},
                new int[]{p.secondary,filled?p.onAction:p.text}));
        if (filled) button.setBackground(action(button.getContext(),p,26));
        else button.setBackground(new RippleDrawable(ColorStateList.valueOf(p.primaryContainer),
                rounded(p.container,26*density),rounded(Color.WHITE,26*density)));
    }
    public static void card(View view, UiPalette p, float radiusDp) {
        float d=view.getResources().getDisplayMetrics().density;
        view.setBackground(rounded(p.card,radiusDp*d)); view.setClipToOutline(true);
        view.setElevation(p.dark?0:2*d);
        if(android.os.Build.VERSION.SDK_INT>=28){view.setOutlineAmbientShadowColor(0x12302B23);view.setOutlineSpotShadowColor(0x18302B23);}
    }
    /** Bounded, cached shaders; no bitmap, blur capture or continuing animation. */
    public static Drawable orangeLight(Context context, UiPalette p, float heightDp) {
        return new OrangeLight(p,heightDp*context.getResources().getDisplayMetrics().density);
    }
    private static final class OrangeLight extends Drawable {
        final UiPalette p; final float height; final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Shader base, warm, light, shade;
        OrangeLight(UiPalette p,float height){this.p=p;this.height=height;}
        @Override protected void onBoundsChange(Rect b){
            float w=b.width(),h=Math.min(height,b.height());
            base=new LinearGradient(0,0,w,h,p.dark?new int[]{0xff423636,0xff7C3B29,0xff6D4B30, p.surface}:new int[]{0xffBEB5BC,0xffE6653C,0xffF2AB67,p.surface},null,Shader.TileMode.CLAMP);
            warm=new RadialGradient(w*.38f,h*.45f,Math.max(w*.72f,1),new int[]{p.dark?0x887D3024:0xFFE96C40,0x00E96C40},null,Shader.TileMode.CLAMP);
            light=new RadialGradient(w*.96f,h*.02f,Math.max(w*.82f,1),new int[]{p.dark?0x777C6747:0xFFFFE5AC,0x00FFE5AC},null,Shader.TileMode.CLAMP);
            shade=new LinearGradient(0,h*.65f,0,h,new int[]{0x00FAFAF8,p.surface},null,Shader.TileMode.CLAMP);
        }
        @Override public void draw(Canvas canvas){Rect b=getBounds();canvas.drawColor(p.surface);float h=Math.min(height,b.height());
            for(Shader shader:new Shader[]{base,warm,light,shade}){paint.setShader(shader);canvas.drawRect(b.left,b.top,b.right,b.top+h,paint);}paint.setShader(null);
        }
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(ColorFilter filter){}
        @Override public int getOpacity(){return PixelFormat.OPAQUE;}
    }
}

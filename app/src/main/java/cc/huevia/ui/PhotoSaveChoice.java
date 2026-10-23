package cc.huevia.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import cc.huevia.album.PhotoSaveTarget;

/** Explicit per-scene destination; preferences are committed only when the user starts the task. */
public final class PhotoSaveChoice {
    private static final String PREFS="photo-save-destinations";
    public interface Selected { void chosen(PhotoSaveTarget target,boolean newCopy); }
    private PhotoSaveChoice(){}
    public static PhotoSaveTarget preferred(Context context,String scene,PhotoSaveTarget fallback){
        String value=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(scene,fallback.name());
        try{return PhotoSaveTarget.valueOf(value);}catch(Exception invalid){return fallback;}
    }
    public static void remember(Context context,String scene,PhotoSaveTarget target){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(scene,target.name()).apply();}
    public static void show(Activity activity,String title,String scene,PhotoSaveTarget fallback,boolean allowNewCopy,Selected selected){
        if(activity.isFinishing()||activity.isDestroyed())return;
        UiPalette palette=new UiPalette(activity);float density=activity.getResources().getDisplayMetrics().density;
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);int inset=Math.round(20*density);content.setPadding(inset,0,inset,0);
        TextView explanation=new TextView(activity);explanation.setText("选择保存位置。原图和编辑记录保留，可稍后再保存到手机相册。");explanation.setTextSize(14);explanation.setTextColor(palette.secondary);content.addView(explanation);
        RadioGroup group=new RadioGroup(activity);group.setOrientation(RadioGroup.VERTICAL);PhotoSaveTarget initial=preferred(activity,scene,fallback);
        RadioButton app=new RadioButton(activity);app.setId(android.view.View.generateViewId());app.setText("仅保存在 App 内");app.setTextSize(16);app.setTextColor(palette.text);app.setMinHeight(Math.round(56*density));app.setTag("save-target-app");group.addView(app);
        RadioButton phone=new RadioButton(activity);phone.setId(android.view.View.generateViewId());phone.setText("保存到手机相册");phone.setTextSize(16);phone.setTextColor(palette.text);phone.setMinHeight(Math.round(56*density));phone.setTag("save-target-phone");group.addView(phone);
        group.check(initial==PhotoSaveTarget.PHONE_ALBUM?phone.getId():app.getId());content.addView(group);
        TextView detail=new TextView(activity);detail.setTextSize(13);detail.setTextColor(palette.secondary);content.addView(detail);
        CheckBox extra=new CheckBox(activity);extra.setText("另存新副本");extra.setTextSize(14);extra.setTextColor(palette.text);extra.setMinHeight(Math.round(48*density));extra.setTag("save-extra-copy");if(allowNewCopy)content.addView(extra);
        Runnable update=()->{boolean system=group.getCheckedRadioButtonId()==phone.getId();detail.setText(system?"同一版本已保存且仍有效时直接复用；新副本使用本次保存时间。":"手机相册不新增照片。App 内照片不会随预览缓存清理；卸载或清除应用数据前请另存需要保留的照片。");extra.setEnabled(system);if(!system)extra.setChecked(false);};group.setOnCheckedChangeListener((g,id)->update.run());update.run();
        ScrollView scrolling=new ScrollView(activity);scrolling.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(title).setView(scrolling).setNegativeButton("取消",null).setPositiveButton(scene.startsWith("live:")?"开始直播":"保存",(d,w)->{PhotoSaveTarget target=group.getCheckedRadioButtonId()==phone.getId()?PhotoSaveTarget.PHONE_ALBUM:PhotoSaveTarget.APP_ONLY;remember(activity,scene,target);selected.chosen(target,allowNewCopy&&extra.isChecked()&&target==PhotoSaveTarget.PHONE_ALBUM);}).create();
        dialog.show();
    }
}

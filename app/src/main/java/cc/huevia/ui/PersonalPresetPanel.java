package cc.huevia.ui;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import cc.huevia.account.*;
import cc.huevia.library.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Foreground account library sync. It never changes the camera's Wi-Fi or binding. */
public final class PersonalPresetPanel {
    public interface Host {void changed();void login();boolean canPrompt();}
    private final Activity activity;private final Host host;private final PresetLibrary library;
    private final AccountRepository accounts;private final PersonalPresetSync sync;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();private final Handler main=new Handler(Looper.getMainLooper());
    private String observed="",status="";private long attempt;private boolean running,destroyed,checkingConsent;private TextView statusView;
    private final Set<String> offered=new HashSet<>();
    private PersonalPresetSync.Backend backend;
    public PersonalPresetPanel(Activity activity,PresetLibrary library,Host host)throws IOException {this.activity=activity;this.library=library;this.host=host;accounts=AndroidAccounts.get(activity);sync=new PersonalPresetSync(activity.getFilesDir(),library);}
    public String owner(){AccountRepository.Session current=accounts.cachedSession();return current==null||accounts.isLogoutPending()?"":current.user.id;}
    public boolean visible(PresetRecord r){return sync.visible(owner(),r.id);}
    public void accountChanged(){String current=owner();if(!current.equals(observed)){observed=current;status="";updateStatus();host.changed();if(!current.isEmpty())requestSync();}offerConsent();}
    public void resumed(){accountChanged();if(!owner().isEmpty()&&System.currentTimeMillis()-attempt>60000)requestSync();}
    public View status(UiPalette colors){statusView=new TextView(activity);statusView.setTextSize(12);statusView.setTextColor(colors.secondary);statusView.setPadding(0,dp(4),0,dp(6));statusView.setMinHeight(dp(44));statusView.setGravity(Gravity.CENTER_VERTICAL);statusView.setOnClickListener(v->openActions());statusView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);updateStatus();return statusView;}
    private void updateStatus(){if(statusView!=null){String extra="";if(!owner().isEmpty()&&status.startsWith("云端已同步")){int local=sync.unclaimed().size();if(local>0)extra=" · "+local+" 份仅本机";}statusView.setText(owner().isEmpty()?"仅保存在本机 · 登录后可同步云端 ›":status.isEmpty()?"个人云端 · 仅自己可见 · 可离线使用 ›":status+extra+" ›");}}
    public void added(PresetRecord r,String owner){if(owner.isEmpty())return;enqueue(owner,()->sync.markAdded(owner,r),true);}
    public void removed(String hash,String owner){if(owner.isEmpty())return;enqueue(owner,()->sync.markDeleted(owner,hash),true);}
    private interface Work {void run()throws Exception;}
    private void enqueue(String owner,Work work,boolean thenSync){if(destroyed)return;worker.execute(()->{try{work.run();post(()->{host.changed();if(thenSync&&owner.equals(owner()))requestSync();});}catch(Exception e){post(()->{if(owner.equals(owner())){status="本机已保留，云端同步记录未保存 · 点击重试";updateStatus();}});}});}
    public void requestSync(){final String owner=owner();if(destroyed||running||owner.isEmpty())return;running=true;attempt=System.currentTimeMillis();status="正在同步个人云端…";updateStatus();
        worker.execute(()->{String failure="";int conflicts=0;try{
            sync.restoreCachedMetadata(owner);
            PersonalPresetSync.Backend api=backend==null?new PersonalPresetApi(accounts,AccountInstallation.id(activity.getApplicationContext())):backend;
            sync.sync(owner,api,(done,total)->post(()->{if(owner.equals(owner())){status=total==0?"正在检查云端预设…":"正在同步 "+Math.min(done,total)+" / "+total;updateStatus();}}));conflicts=sync.conflicts(owner);
        }catch(Exception e){failure=e instanceof IOException?e.getMessage():"稍后重试";}
        final String error=failure;final int differences=conflicts;post(()->{running=false;if(owner.equals(owner())){status=!error.isEmpty()?"待同步 · 本机预设仍可使用 · 点击重试":differences>0?"有 "+differences+" 份云端信息已更新 · 点击处理":"云端已同步 · 仅自己可见";updateStatus();host.changed();offerConsent();}else if(!owner().isEmpty())requestSync();});});
    }
    public void offerConsent(){final String owner=owner();if(destroyed||checkingConsent||owner.isEmpty()||offered.contains(owner)||!host.canPrompt())return;checkingConsent=true;
        worker.execute(()->{boolean needed=false;int count=0;try{needed=sync.consentNeeded(owner);count=sync.unclaimed().size();}catch(IOException ignored){}final boolean ask=needed;final int amount=count;
            post(()->{checkingConsent=false;if(!ask||!owner.equals(owner())||!host.canPrompt())return;offered.add(owner);
                new AlertDialog.Builder(activity).setTitle("同步本机预设到账号？").setMessage("有 "+amount+" 份本机预设可保存到个人云端，仅你可见。换手机登录后可恢复，当前手机仍能离线使用。\n\n同步预设文件、名称和分类；自选样片与模板图片保留在本机。")
                    .setNegativeButton("暂不同步",(d,w)->enqueue(owner,()->sync.consent(owner,false),false))
                    .setPositiveButton("同步到我的账号",(d,w)->enqueue(owner,()->sync.consent(owner,true),true)).show();});});
    }
    public void openActions(){final String owner=owner();if(owner.isEmpty()){host.login();return;}if(running)return;
        worker.execute(()->{int count=0;try{count=sync.conflicts(owner);}catch(IOException ignored){}final int conflicts=count;post(()->{if(!owner.equals(owner()))return;
            if(conflicts>0)new AlertDialog.Builder(activity).setTitle("处理云端信息差异").setMessage("其他设备更新了同一预设的名称、分类或删除状态。预设原文件内容相同，请选择保留哪一份信息。")
                .setNegativeButton("使用云端信息",(d,w)->enqueue(owner,()->sync.resolveConflicts(owner,false),true))
                .setPositiveButton("保留本机更改",(d,w)->enqueue(owner,()->sync.resolveConflicts(owner,true),true)).show();
            else new AlertDialog.Builder(activity).setTitle("我的预设 · 个人云端").setItems(new String[]{"立即同步","同步已有本机预设","存储说明"},(d,w)->{
                if(w==0)requestSync();else if(w==1){offered.remove(owner);new AlertDialog.Builder(activity).setTitle("同步本机预设？").setMessage("将尚未归属账号的本机预设保存到当前账号，仅你可见。不会上传其他账号的离线副本。")
                    .setNegativeButton("取消",null).setPositiveButton("同步",(dialog,which)->enqueue(owner,()->sync.consent(owner,true),true)).show();}
                else new AlertDialog.Builder(activity).setTitle("云端与离线使用").setMessage("预设文件、名称和分类保存在个人账号，登录后自动同步；手机保留离线副本。未联网时更改会排队，恢复网络后可重试。\n\n模板和自选样片保留在本机。删除已同步预设会同时删除个人云端记录；已生成照片与相机中的预设保留。").setPositiveButton("知道了",null).show();}).show();
        });});
    }
    private int dp(int value){return Math.round(activity.getResources().getDisplayMetrics().density*value);}
    private void post(Runnable action){main.post(()->{if(!destroyed&&!activity.isFinishing())action.run();});}
    public void close(){destroyed=true;statusView=null;worker.shutdownNow();}
}

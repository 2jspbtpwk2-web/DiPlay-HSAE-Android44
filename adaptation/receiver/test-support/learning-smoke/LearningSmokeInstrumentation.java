package local.airuize.diplaylegacy.learningsmoke;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Actual installed final APK only. No fake phone/session, learned key injection, ROOT source,
 * permission changes, physical microphone capture, or claim that the vehicle hardware passed.
 * Same certificate as target. Interrupted run: -e restoreOnly true before any further run.
 * The durable files-dir backup preserves presence, type, and value of every entry in all four
 * optional learning/UI preference files, including unknown future keys and StringSet entries.
 */
public final class LearningSmokeInstrumentation extends Instrumentation {
    private static final String PKG="local.airuize.diplaylegacy";
    private static final String ACT="local.airuize.receiver.ReceiverActivity";
    private static final String WIRED="local.airuize.receiver.LegacyWiredService";
    private static final String STEERING="local.airuize.receiver.SteeringVoiceService";
    private static final String[] FILES={"receiver-ui","performance-learning-v1",
        "receiver-feature-learning-v1","receiver-steering-learning"};
    private final StringBuilder report=new StringBuilder();
    private final AtomicReference<Throwable> uncaught=new AtomicReference<Throwable>();
    private final Map<String,Map<String,?>> originals=new HashMap<String,Map<String,?>>();
    private Activity activity;
    private boolean restoreOnly, backedUp, originalSteeringRunning, backupAbsent, restorationPerformed;
    private int checks, recreates, pid;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args); restoreOnly=args!=null && "true".equals(args.getString("restoreOnly"));
        pid=Process.myPid(); start();
    }
    @Override public boolean onException(Object object,Throwable error) {
        uncaught.compareAndSet(null,error); return false;
    }
    @Override public void onStart() {
        boolean passed=false;
        try {
            if(restoreOnly) loadBackup(); else exercise();
            passed=true;
        } catch(Throwable error) { line("FAIL "+Log.getStackTraceString(error)); }
        finally {
            try { closeActivity(); awaitNoLearning(5000); }
            catch(Throwable error) { passed=false;line("FAIL cleanup "+Log.getStackTraceString(error)); }
            try { restore(); }
            catch(Throwable error) { passed=false;line("FAIL restore "+Log.getStackTraceString(error)); }
        }
        if(uncaught.get()!=null) {passed=false;line("FAIL uncaught "+Log.getStackTraceString(uncaught.get()));}
        if(passed) line(restoreOnly ? "PASS LearningSmokeRestoreOnly checks="+checks+
            " backupPresent="+!backupAbsent+" restorePerformed="+restorationPerformed :
            "PASS LearningSmokeInstrumentation checks="+checks+" recreates="+recreates+" pid="+pid+
            " actualInstalledApk=true hardwareLearningNotClaimed=true preferencesRestored=true");
        Bundle result=new Bundle();result.putString("stream","\n"+report);result.putInt("checks",checks);
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }
    private void exercise() throws Exception {
        require(!serviceRunning(WIRED),"no existing wired session may be disturbed");
        require(!backupFile().exists(),"no unfinished learning test; use restoreOnly first");
        originalSteeringRunning=serviceRunning(STEERING);
        backup();
        require(getTargetContext().getPackageManager().getPackageInfo(PKG,0).versionCode==21040,
            "actual installed final 0.60 package version");
        require(prefs("receiver-ui").edit().putBoolean("vehicleGps",false).putBoolean("microphonePcm",false)
            .putBoolean("steeringVoice",false).commit(),
            "temporary opt-out fixtures committed after durable backup");
        if(originalSteeringRunning)getTargetContext().stopService(new Intent().setComponent(new ComponentName(PKG,STEERING)));
        final LocationManager gps=(LocationManager)getTargetContext().getSystemService(Context.LOCATION_SERVICE);
        final boolean gpsBefore=gps!=null && gps.isProviderEnabled(LocationManager.GPS_PROVIDER);
        open(); awaitNoLearning(5000);
        toolsAndSettings();performance(); feature(); steering(); exportReport();
        openWheel();
        recreate("steering-window-open");
        require(recreates>=2 && !learning() && !calibrating(),"two actual system recreations leave neither learning resource gate active");
        main(new Action(){public void run() throws Exception {
            require(dialogs(activity).isEmpty(),"replacement activity has no retained learning window");
            click("功能适配",false);
        }});
        awaitText("功能适配",false);
        dismissAll();
        require(gps==null || gps.isProviderEnabled(LocationManager.GPS_PROVIDER)==gpsBefore,
            "learning UI never changes the system GPS provider");
        require(!prefs("receiver-ui").getBoolean("vehicleGps",true) &&
            !prefs("receiver-ui").getBoolean("microphonePcm",true),"learning UI never silently enables privacy preferences");
        require(!serviceRunning(WIRED),"learning menus and probes never start a wired service");
        line("LEARNING_LIFECYCLE PASS actualSystemRecreate=true oldDialogsReleased=true noResourceGate=true");
    }
    private void performance() throws Exception {
        openPerformance();
        main(new Action(){public void run() throws Exception {
            require(find("照这 3 步做",false)!=null,"automatic picture page has explicit numbered instructions");
            require(find("停在工具页",true)!=null,"tool/diagnostic samples are explained without EXCLUDED jargon");
            setCheck("允许自动调整画面",false);
            setCheck("固定现在的画质",true);click("保存我的选择",false);
        }});
        performanceState("after-disable-and-lock");
        require(!prefs("performance-learning-v1").getBoolean("automatic",true) &&
            prefs("performance-learning-v1").getBoolean("locked",false),"performance flags persist as booleans");
        require(!((Boolean)call("PerformanceLearning","isAutomatic")).booleanValue() &&
            ((Boolean)call("PerformanceLearning","isLocked")).booleanValue(),"saved flags affect actual in-memory policy");
        openPerformance();
        main(new Action(){public void run() throws Exception {
            setCheck("允许自动调整画面",true);
            setCheck("固定现在的画质",false);click("保存我的选择",false);
        }});
        performanceState("after-enable-and-unlock");
        openPerformance();clickAndWait("效果反馈与恢复","画面效果反馈");
        clickAndWait("当前效果不好，回退并固定",null);
        performanceState("after-rollback");
        require(((Boolean)call("PerformanceLearning","isLocked")).booleanValue(),"explicit rollback locks the future profile");
        openPerformance();clickAndWait("效果反馈与恢复","画面效果反馈");
        clickAndWait("清除画面适配记录","清除画面适配记录？");clickAndWait("清除记录",null);
        performanceState("after-reset");
        require(!((Boolean)call("PerformanceLearning","isLocked")).booleanValue(),"explicit reset clears the optional profile lock");
        openPerformance();clickAndWait("启用自动优化画面",null);
        require("auto".equals(prefs("receiver-ui").getString("displayMode","")),"automatic display mode remains the expected String preference");
        main(new Action(){public void run() throws Exception {
            require(((CheckBox)find("允许自动调整画面",false)).isChecked() &&
                !((CheckBox)find("固定现在的画质",false)).isChecked(),"one-step enable refreshes visible choices");
            click("保存我的选择",false);
        }});
        require(((Boolean)call("PerformanceLearning","isAutomatic")).booleanValue() &&
            !((Boolean)call("PerformanceLearning","isLocked")).booleanValue(),"save does not undo one-step enable with stale checkbox state");
        require(!serviceRunning(WIRED),"flag save/reset/rollback never reconnects automatically");
        dismissAll();
        line("LEARNING_PERFORMANCE_UI PASS savedFlags=true rollbackAndReset=true typedDisplayMode=true noAutoReconnect=true");
    }
    private void toolsAndSettings() throws Exception {
        dismissAll();clickAndWait("设置","设置");
        main(new Action(){public void run() throws Exception {
            for(String value:new String[]{"画面","声音","麦克风与 GPS","方向盘与连接","外观"}) {
                View view=find(value,false);require(view!=null,"grouped settings entry: "+value);
                while(view!=null && !view.isClickable())view=view.getParent() instanceof View?(View)view.getParent():null;
                require(view!=null && view.getMinimumHeight()>=56*getTargetContext().getResources().getDisplayMetrics().density,
                    "settings card has a 56dp or larger touch target: "+value);
            }
        }});
        clickAndWait("画面","画面设置");clickAndWait("选择画面模式","画面模式");
        clickAndWait("流畅优先 · 分辨率较低",null);
        clickAndWait("选择帧率","帧率");clickAndWait("24 帧/秒 · 折中",null);
        clickAndWait("保存画面设置",null);
        require("smooth".equals(prefs("receiver-ui").getString("displayMode","")) &&
            prefs("receiver-ui").getInt("fps",0)==24,"display mode and fps save with their actual preference types");
        openSettingsGroup("画面","画面设置");clickAndWait("编辑自定义分辨率","自定义分辨率");
        final int oldWidth=prefs("receiver-ui").getInt("width",1024);
        main(new Action(){public void run() throws Exception {number("宽度（640～1920）").setText("10");click("保存手动值",false);}});
        main(new Action(){public void run() throws Exception {
            require(number("宽度（640～1920）").getError()!=null,"invalid custom width keeps dialog open with inline error");
            require(prefs("receiver-ui").getInt("width",1024)==oldWidth,"invalid custom width never partially saves");
            number("宽度（640～1920）").setText("640");number("高度（360～1920）").setText("960");
            number("画面比例（10 表示 100%，范围 3～10）").setText("8");click("保存手动值",false);
        }});
        require(prefs("receiver-ui").getInt("width",0)==640 && prefs("receiver-ui").getInt("height",0)==960 &&
            prefs("receiver-ui").getInt("scale",0)==8,"valid manual width/height/scale persist exactly");
        require("smooth".equals(prefs("receiver-ui").getString("displayMode","")),"editing custom numbers does not silently change the display mode");
        openSettingsGroup("画面","画面设置");clickAndWait("选择帧率","帧率");clickAndWait("15 帧/秒 · 低负载",null);
        clickAndWait("取消",null);require(prefs("receiver-ui").getInt("fps",0)==24,"cancel discards an unsaved frame-rate choice");

        final String key=String.valueOf(call("LearningDevice","id"))+".";
        require(prefs("receiver-feature-learning-v1").edit().putInt(key+"audio-original",300)
            .putInt(key+"audio-buffer",1000).putBoolean(key+"audio-enabled",true).commit(),"old buffer trial fixture committed");
        openSettingsGroup("声音","声音设置");clickAndWait("DiPlay 播放音量","DiPlay 播放音量");clickAndWait("75%",null);
        clickAndWait("音乐启动缓冲","音乐启动缓冲");clickAndWait("500 毫秒 · 折中",null);
        main(new Action(){public void run() throws Exception {setCheck("管理播放音频焦点",false);setCheck("导航时压低音乐",true);click("保存声音设置",false);}});
        require(prefs("receiver-ui").getInt("volume",0)==75 && prefs("receiver-ui").getInt("bufferMillis",0)==500 &&
            !prefs("receiver-ui").getBoolean("audioFocus",true) && prefs("receiver-ui").getBoolean("navigationDuck",false),
            "sound controls save volume/buffer/focus/duck exact values");
        require(!prefs("receiver-feature-learning-v1").getBoolean(key+"audio-enabled",true),"saving main sound settings withdraws a hidden old buffer trial");

        openSettingsGroup("麦克风与 GPS","麦克风与 GPS");
        main(new Action(){public void run() throws Exception {setCheck("使用车机麦克风",true);setCheck("使用车机 GPS",true);click("保存功能开关",false);}});
        require(prefs("receiver-ui").getBoolean("microphonePcm",false) && prefs("receiver-ui").getBoolean("vehicleGps",false),
            "explicit privacy opt-in is saved only by the user save control");
        openSettingsGroup("麦克风与 GPS","麦克风与 GPS");
        main(new Action(){public void run() throws Exception {setCheck("使用车机麦克风",false);setCheck("使用车机 GPS",false);click("保存功能开关",false);}});
        openSettingsGroup("方向盘与连接","方向盘与连接");
        main(new Action(){public void run() throws Exception {
            require(find("断开后允许重新连接",false)==null && find("允许后台运行",false)==null,"unimplemented legacy switches are not advertised");
            setCheck("方向盘语音键",false);setCheck("转发方向盘切歌",false);setCheck("开机后准备连接",false);click("保存连接设置",false);
        }});
        require(!serviceRunning(WIRED),"all grouped settings save without connecting a phone");
        dismissAll();clickAndWait("问题排查","问题排查");
        main(new Action(){public void run() throws Exception {
            for(String value:new String[]{"导出问题简报","检查声音与麦克风","查看车机 GPS 状态","完整诊断与按键采集","只采集完整报告","查看运行日志"})
                require(find(value,false)!=null,"problem-based diagnostic control: "+value);
        }});
        clickAndWait("查看运行日志","运行日志");dismissAll();clickAndWait("使用说明","使用说明");
        main(new Action(){public void run() throws Exception {
            require(find("日常使用",false)!=null && find("功能适配怎么用",false)!=null && find("反馈问题",false)!=null,
                "short in-app help explains first use, adaptation and feedback");
        }});
        dismissAll();require(prefs("receiver-ui").edit().putInt("fps",30).putBoolean("hevc",false).commit(),
            "compatible fps fixture restored for subsequent automatic picture flow");
        line("TOOLS_SETTINGS_UI PASS groupedCards=true largeTouchTargets=true manualValidation=true cancelDoesNotSave=true mainBufferWins=true explicitPrivacyOnly=true noAutoConnect=true");
    }
    private void openSettingsGroup(String label,String title) throws Exception {
        dismissAll();clickAndWait("设置","设置");clickAndWait(label,title);
    }
    private EditText number(String description) throws Exception {
        View view=findDescription(lastDialog().getWindow().getDecorView(),description);
        require(view instanceof EditText,"actual numeric editor exists: "+description);return (EditText)view;
    }
    private View findDescription(View root,String description) {
        if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;
        if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++){
            View result=findDescription(group.getChildAt(i),description);if(result!=null)return result;
        }}return null;
    }
    /** Read only known booleans and bounded product diagnostic metadata after dispatch is idle;
     * never dump unrelated preference values when an assertion needs investigation. */
    private void performanceState(String phase) throws Exception {
        Map<String,?> values=prefs("performance-learning-v1").getAll();
        Object automatic=values.get("automatic"),locked=values.get("locked");
        line("PERFORMANCE_STATE "+phase+" automatic="+String.valueOf(automatic)+
            " automaticType="+(automatic==null?"absent":automatic.getClass().getSimpleName())+
            " locked="+String.valueOf(locked)+" lockedType="+(locked==null?"absent":locked.getClass().getSimpleName())+
            " inMemoryAutomatic="+call("PerformanceLearning","isAutomatic")+
            " inMemoryLocked="+call("PerformanceLearning","isLocked"));
        String snapshot=String.valueOf(call("PerformanceLearning","snapshot"));
        line("PERFORMANCE_SNAPSHOT "+phase+" "+snapshot.substring(0,Math.min(4096,snapshot.length())));
    }
    private void openPerformance() throws Exception {
        dismissAll();clickAndWait("功能适配","功能适配");
        clickAndWait("自动优化画面","自动优化画面");
    }
    private void openFeature() throws Exception {
        dismissAll();clickAndWait("功能适配","功能适配");
        clickAndWait("麦克风、声音与定位","麦克风、声音与定位");
    }
    private void feature() throws Exception {
        final String key=String.valueOf(call("LearningDevice","id"))+".";
        SharedPreferences.Editor clean=prefs("receiver-feature-learning-v1").edit();
        for(String stored:prefs("receiver-feature-learning-v1").getAll().keySet())
            if(stored.startsWith(key+"mic7-") || stored.equals(key+"candidate-speechrecognition") || stored.equals(key+"confirmed-speechrecognition"))clean.remove(stored);
        require(clean.commit(),"missing source-B sample fixture is explicit and covered by durable full restore");
        openFeature();clickAndWait("观察 GPS 接收","请先开启定位");
        require(!prefs("receiver-ui").getBoolean("vehicleGps",true),"GPS observation respects explicit app opt-out");
        dismissAll();openFeature();clickAndWait("检查车机麦克风","麦克风适配");
        clickAndWait("方案 A · 语音输入","麦克风方案 A");
        clickAndWait("第 1 步 · 保持安静 3 秒","准备保持安静");clickAndWait("开始","校准未开始");
        require(!calibrating() && !prefs("receiver-ui").getBoolean("microphonePcm",true),
            "disabled microphone neither opens calibration nor silently opts in");
        openFeature();clickAndWait("检查车机麦克风","麦克风适配");
        clickAndWait("方案 B · 通话输入","麦克风方案 B");
        clickAndWait("第 3 步 · 下次连接试用此方案","下次连接试用哪种用途？");
        clickAndWait("用于 Siri","这次没有保存");
        require(!prefs("receiver-feature-learning-v1").getBoolean(key+"candidate-speechrecognition",false),
            "missing real quiet/speech samples cannot save a trial candidate");
        // The explicit local tone has no phone transport and never changes route or volume.
        startTone();
        main(new Action(){public void run() throws Exception {click("停止",false);}});
        awaitNoLearning(5000);
        require(!calibrating(),"explicit tone cancellation releases the real audio job");
        startTone();dismissAll();awaitNoLearning(5000);
        require(!calibrating(),"external progress dismissal releases audio without replacing managed OnDismiss");
        startTone();recreate("feature-tone-active");awaitNoLearning(5000);
        require(!calibrating(),"actual onPause/onDestroy cancels an active optional audio job");
        featureBackgroundResume();
        line("LEARNING_FEATURE_UI PASS optOutPreserved=true noFakeMicTrial=true cancelAndExternalDismiss=true pauseDestroyCleanup=true physicalMicNotOpened=true");
    }
    private void featureBackgroundResume() throws Exception {
        openFeature();clickAndWait("检查车机麦克风","麦克风适配");
        final Activity same=activity;
        main(new Action(){public void run() throws Exception {require(same.moveTaskToBack(true),"feature page task really moves to background");}});
        awaitResumeFlag(false);
        awaitNoDialogs(5000);awaitNoLearning(5000);
        main(new Action(){public void run() throws Exception {
            require(field(same,"featureLearningUi")==null,"paused Activity releases its Feature UI owner");
        }});
        getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(PKG,ACT))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        awaitResumeFlag(true);waitForIdleSync();
        main(new Action(){public void run() throws Exception {
            require(activity==same && !same.isDestroyed() && !Boolean.TRUE.equals(field(same,"destroyed")),
                "real background/foreground preserves the same Activity without recreation");
            require(dialogs(activity).isEmpty() && find("功能适配",false)!=null,"foreground returns to a usable dashboard without closed-owner modal pages");
        }});
        openFeature();clickAndWait("检查车机麦克风","麦克风适配");
        clickAndWait("方案 A · 语音输入","麦克风方案 A");
        clickAndWait("第 1 步 · 保持安静 3 秒","准备保持安静");
        require(!calibrating(),"reopened real microphone introduction works without silently recording");
        dismissAll();
        line("FEATURE_BACKGROUND_RESUME PASS realTaskBackground=true sameActivity=true staleDialogsReleased=true reopenedControlsWork=true noRecreate=true");
    }
    private void awaitResumeFlag(final boolean expected) throws Exception {
        long until=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<until) {
            final boolean[] matches=new boolean[1];main(new Action(){public void run() throws Exception {
                matches[0]=Boolean.valueOf(expected).equals(field(activity,"resumed"));
            }});
            if(matches[0])return;SystemClock.sleep(30);
        }throw new AssertionError("real Activity resumed flag did not become "+expected);
    }
    private void startTone() throws Exception {
        openFeature();clickAndWait("检查车机声音","声音检查");clickAndWait("播放车机短提示音","车机本地试听");
        clickAndWait("播放短提示音","正在播放短提示音");
    }
    private void steering() throws Exception {
        openWheel();
        main(new Action(){public void run() throws Exception {
            AlertDialog dialog=lastDialog();
            require(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(),"actual steering wizard start control is usable");
            require(!dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString().contains("启用"),"no mapping is invented before visible events");
            click("开始学习",false);
        }});
        awaitText("本机不是已支持的HSAE按键来源",true);awaitNoLearning(5000);
        require(!learning(),"unsupported emulator explains source and releases the observer without claiming a key");
        main(new Action(){public void run() throws Exception {click("取消",false);}});
        dismissAll();awaitNoDialogs(5000);require(!learning(),"cancel clears steering gate");
        openWheel();
        dismissAll();awaitNoDialogs(5000);require(!learning(),"external steering dialog dismiss clears its gate");
        openWheel();
        main(new Action(){public void run() throws Exception {click("关闭并清除",false);}});
        require(!prefs("receiver-ui").getBoolean("steeringMedia",true),"explicit restore disables steering as a Boolean");
        require(!prefs("receiver-steering-learning").contains("boundDevice"),"explicit restore removes the current learned binding");
        String snapshot=String.valueOf(call("SteeringLearning","snapshot",new Class<?>[]{Context.class},new Object[]{getTargetContext()}));
        require(snapshot.contains("learning=false") && snapshot.contains("savedMapping=") && snapshot.contains("deviceKey="),
            "actual steering diagnostic has bounded firmware/mapping/gate metadata");
        dismissAll();
        line("LEARNING_STEERING_UI PASS realEntry=true unsupportedSourceExplained=true cancelAndDismiss=true explicitDefaultOff=true noRootOrHardwareLearningClaim=true");
    }
    private void openWheel() throws Exception {
        dismissAll();clickAndWait("功能适配","功能适配");clickAndWait("方向盘切歌","方向盘切歌 · 按键识别");
    }
    private void exportReport() throws Exception {
        dismissAll();clickAndWait("功能适配","功能适配");
        clickAndWait("导出问题简报","问题简报已导出");
        final String[] path=new String[1];
        main(new Action(){public void run() throws Exception {
            TextView message=(TextView)lastDialog().findViewById(android.R.id.message);
            require(message!=null,"actual export dialog exposes its path");path[0]=message.getText().toString().split("\n")[0];
        }});
        File file=new File(path[0]);require(file.isFile() && file.length()>0 && file.length()<=196608,"actual metadata report is bounded and saved");
        byte[] bytes=new byte[(int)file.length()];FileInputStream in=new FileInputStream(file);
        try {int offset=0;while(offset<bytes.length){int n=in.read(bytes,offset,bytes.length-offset);if(n<0)break;offset+=n;}require(offset==bytes.length,"report fully readable");}finally{in.close();}
        String text=new String(bytes,"UTF-8");
        require(text.contains("DiPlay 自学习简报 0.60") && text.contains("性能学习：") &&
            text.contains("SteeringLearning deviceKey=") && text.contains("功能学习："),"report contains all three real learning snapshots");
        require(!learning() && !calibrating() && !serviceRunning(WIRED),"metadata export starts no active capture or phone connection");
        line("LEARNING_REPORT_UI PASS actualExport=true boundedMetadata=true noCaptureStarted=true path="+file.getAbsolutePath());
        dismissAll();
    }
    private void recreate(final String reason) throws Exception {
        final Activity old=activity;ActivityMonitor monitor=addMonitor(ACT,null,false);
        try {
            main(new Action(){public void run(){old.recreate();}});
            Activity next=waitForMonitorWithTimeout(monitor,15000);
            require(next!=null && next!=old,"real system recreation creates a new Activity: "+reason);
            activity=next;awaitReady();
            long until=SystemClock.elapsedRealtime()+15000;
            while(!old.isDestroyed() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(20);
            require(old.isDestroyed() && Boolean.TRUE.equals(field(old,"destroyed")),"old Activity framework and product destruction completed");
            main(new Action(){public void run() throws Exception {require(dialogs(old).isEmpty(),"destroyed Activity managed registry cleared");}});
            awaitNoLearning(5000);recreates++;
        } finally {removeMonitor(monitor);}
    }
    private void open() throws Exception {
        activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(PKG,ACT)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        require(activity!=null && ACT.equals(activity.getClass().getName()),"actual production Activity launched");awaitReady();
    }
    private void awaitReady() throws Exception {
        long until=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<until){
            final boolean[] ready=new boolean[1];main(new Action(){public void run() throws Exception {ready[0]=Boolean.TRUE.equals(field(activity,"resumed")) && find("功能适配",false)!=null;}});
            if(ready[0]){waitForIdleSync();return;}SystemClock.sleep(40);
        }
        throw new AssertionError("actual Activity did not resume with the learning control");
    }
    private void closeActivity() throws Exception {
        if(activity==null)return;dismissAll();
        final Activity old=activity;main(new Action(){public void run(){old.finish();}});
        long until=SystemClock.elapsedRealtime()+1500;
        while(!old.isDestroyed() && SystemClock.elapsedRealtime()<until)SystemClock.sleep(25);
        // Ordinary finish can be deferred by the framework while instrumentation is active;
        // only the monitored real recreations above count as destruction evidence.
        line("CLEANUP finishRequested=true frameworkDestroyed="+old.isDestroyed()+" notCountedAsRecreate=true");activity=null;
    }
    private void clickAndWait(final String value,String expected) throws Exception {
        main(new Action(){public void run() throws Exception {click(value,false);}});
        if(expected!=null)awaitText(expected,false);else{waitForIdleSync();SystemClock.sleep(60);}
    }
    private void awaitText(final String value,final boolean prefix) throws Exception {
        long until=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<until){final boolean[] found=new boolean[1];
            main(new Action(){public void run() throws Exception {
                if(prefix) found[0]=find(value,true)!=null;
                else for(AlertDialog dialog:dialogs(activity)) if(dialog.isShowing() &&
                    find(dialog.getWindow().getDecorView(),value,false)!=null) {found[0]=true;break;}
            }});
            if(found[0]){require(true,"visible text observed on later UI turn: "+value);return;}SystemClock.sleep(40);
        }throw new AssertionError("visible text timeout: "+value);
    }
    private void awaitNoDialogs(long timeout) throws Exception {
        long until=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<until){final boolean[] empty=new boolean[1];main(new Action(){public void run() throws Exception{empty[0]=dialogs(activity).isEmpty();}});if(empty[0])return;SystemClock.sleep(30);}
        throw new AssertionError("managed dialogs did not clear after dismissal");
    }
    private void dismissAll() throws Exception {
        if(activity==null)return;
        main(new Action(){public void run() throws Exception {for(AlertDialog dialog:dialogs(activity))dialog.dismiss();}});
        awaitNoDialogs(5000);waitForIdleSync();
    }
    private void awaitNoLearning(long timeout) throws Exception {
        long until=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<until){if(!learning() && !calibrating())return;SystemClock.sleep(25);}
        throw new AssertionError("optional learning/audio resources remain active after cancellation");
    }
    private boolean learning() throws Exception{return ((Boolean)call("SteeringLearning","isLearning")).booleanValue();}
    private boolean calibrating() throws Exception{return ((Boolean)call("FeatureLearning","isCalibrating")).booleanValue();}
    private Object call(String type,String name) throws Exception{return call(type,name,new Class<?>[0],new Object[0]);}
    private Object call(String type,String name,Class<?>[] signature,Object[] args) throws Exception {
        Class<?> cls=Class.forName("local.airuize.receiver."+type,true,getTargetContext().getClassLoader());
        Field instance=cls.getDeclaredField("INSTANCE");instance.setAccessible(true);
        Method method=cls.getDeclaredMethod(name,signature);method.setAccessible(true);return method.invoke(instance.get(null),args);
    }
    private static Object field(Object owner,String name) throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private List<AlertDialog> dialogs(Activity owner) throws Exception {
        List<AlertDialog> result=new ArrayList<AlertDialog>();
        for(Object item:(Collection<?>)field(owner,"managedDialogs"))if(item instanceof AlertDialog)result.add((AlertDialog)item);
        return result;
    }
    private AlertDialog lastDialog() throws Exception {
        List<AlertDialog> values=dialogs(activity);
        for(AlertDialog value:values)if(value.isShowing() && value.getWindow().getDecorView().hasWindowFocus())return value;
        for(int i=values.size()-1;i>=0;i--)if(values.get(i).isShowing())return values.get(i);
        throw new AssertionError("no actual visible managed dialog");
    }
    private View find(String text,boolean prefix) throws Exception {
        List<AlertDialog> values=dialogs(activity);
        for(AlertDialog value:values)if(value.isShowing() && value.getWindow().getDecorView().hasWindowFocus()) {
            View found=find(value.getWindow().getDecorView(),text,prefix);if(found!=null)return found;
        }
        for(int i=values.size()-1;i>=0;i--){AlertDialog d=values.get(i);if(d.isShowing()){View found=find(d.getWindow().getDecorView(),text,prefix);if(found!=null)return found;}}
        return find(activity.getWindow().getDecorView(),text,prefix);
    }
    private View find(View view,String text,boolean prefix) {
        if(!view.isShown() || view.getWindowVisibility()!=View.VISIBLE)return null;
        if(view instanceof TextView){String v=((TextView)view).getText().toString();if(prefix?v.startsWith(text):v.equals(text))return view;}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View v=find(group.getChildAt(i),text,prefix);if(v!=null)return v;}}
        return null;
    }
    private void click(String text,boolean prefix) throws Exception {
        View found=find(text,prefix);require(found!=null,"text/tree control exists: "+text);
        found.requestRectangleOnScreen(new android.graphics.Rect(0,0,found.getWidth(),found.getHeight()),true);
        View target=found;
        for(ViewParent parent=target.getParent();parent instanceof View;parent=parent.getParent()){
            if(parent instanceof AdapterView<?>){AdapterView<?> adapter=(AdapterView<?>)parent;int n=adapter.getPositionForView(found);
                require(n!=AdapterView.INVALID_POSITION && adapter.performItemClick(target,n,adapter.getItemIdAtPosition(n)),"actual list item clicked: "+text);return;}
            if(target.isClickable())break;target=(View)parent;
        }
        require(target.isEnabled() && target.performClick(),"actual control clicked: "+text);
    }
    private void setCheck(String text,boolean value) throws Exception {
        View found=find(text,false);require(found instanceof CheckBox,"actual checkbox exists: "+text);
        CheckBox check=(CheckBox)found;require(check.isEnabled(),"checkbox enabled: "+text);
        // CompoundButton toggles before delegating to View.performClick(). With no explicit
        // OnClickListener the return value can be false even though the real toggle succeeded.
        if(check.isChecked()!=value)check.performClick();
        require(check.isChecked()==value,"checkbox value updated: "+text);
    }
    private boolean serviceRunning(String cls) {
        ActivityManager manager=(ActivityManager)getTargetContext().getSystemService(Context.ACTIVITY_SERVICE);
        for(ActivityManager.RunningServiceInfo info:manager.getRunningServices(100))if(cls.equals(info.service.getClassName()))return true;
        return false;
    }
    private SharedPreferences prefs(String name){return getTargetContext().getSharedPreferences(name,Context.MODE_PRIVATE);}
    private File backupFile(){return new File(getTargetContext().getFilesDir(),"learning-smoke-preferences-v1.properties");}
    private void backup() throws Exception {
        Properties data=new Properties();data.setProperty("format","1");
        data.setProperty("steeringServiceRunning",String.valueOf(originalSteeringRunning));
        for(int i=0;i<FILES.length;i++){
            Map<String,?> values=new HashMap<String,Object>(prefs(FILES[i]).getAll());originals.put(FILES[i],values);
            data.setProperty("file."+i,FILES[i]);data.setProperty("count."+i,String.valueOf(values.size()));int j=0;
            for(Map.Entry<String,?> entry:values.entrySet()){
                String b=i+"."+(j++);data.setProperty(b+".key",entry.getKey());writeValue(data,b,entry.getValue());
            }
        }
        FileOutputStream out=new FileOutputStream(backupFile());try{data.store(out,"DiPlay exact learning smoke preferences; restoreOnly after interruption");out.getFD().sync();}finally{out.close();}
        backedUp=true;line("BACKUP durable="+backupFile().getAbsolutePath()+" completePreferenceFiles=4");
    }
    private void loadBackup() throws Exception {
        if(!backupFile().exists()) {
            backupAbsent=true;
            line("RESTORE_LEARNING_PREFS SKIP backupAbsent=true restorePerformed=false noPreferencesChanged=true");
            return;
        }
        require(backupFile().isFile(),"present restoreOnly backup is a regular file");Properties data=new Properties();
        FileInputStream in=new FileInputStream(backupFile());try{data.load(in);}finally{in.close();}
        require("1".equals(data.getProperty("format")),"backup format supported");
        require(data.containsKey("steeringServiceRunning"),"backup includes original steering service state");
        originalSteeringRunning=Boolean.parseBoolean(data.getProperty("steeringServiceRunning"));
        for(int i=0;i<FILES.length;i++){
            require(FILES[i].equals(data.getProperty("file."+i)),"backup preference-file whitelist matches");
            int count=Integer.parseInt(data.getProperty("count."+i));require(count>=0 && count<=10000,"bounded backup entry count");
            Map<String,Object> map=new HashMap<String,Object>();for(int j=0;j<count;j++){String b=i+"."+j;map.put(data.getProperty(b+".key"),readValue(data,b));}
            originals.put(FILES[i],map);
        }backedUp=true;
    }
    private void writeValue(Properties data,String key,Object value) {
        String type=value instanceof Boolean?"bool":value instanceof Integer?"int":value instanceof Long?"long":
            value instanceof Float?"float":value instanceof String?"string":value instanceof Set<?>?"set":"unsupported";
        require(!"unsupported".equals(type),"supported original preference type");data.setProperty(key+".type",type);
        if(value instanceof Set<?>){int i=0;for(Object item:(Set<?>)value){require(item instanceof String,"StringSet only contains String");data.setProperty(key+".item."+(i++),(String)item);}data.setProperty(key+".size",String.valueOf(i));}
        else data.setProperty(key+".value",String.valueOf(value));
    }
    private Object readValue(Properties data,String key) {
        String type=data.getProperty(key+".type"),value=data.getProperty(key+".value");
        if("bool".equals(type))return Boolean.valueOf(value);if("int".equals(type))return Integer.valueOf(value);
        if("long".equals(type))return Long.valueOf(value);if("float".equals(type))return Float.valueOf(value);
        if("string".equals(type))return value;
        if("set".equals(type)){int n=Integer.parseInt(data.getProperty(key+".size"));require(n>=0 && n<=10000,"bounded StringSet backup");Set<String> values=new HashSet<String>();for(int i=0;i<n;i++)values.add(data.getProperty(key+".item."+i));return values;}
        throw new AssertionError("unsupported backup type");
    }
    @SuppressWarnings("unchecked") private void restore() throws Exception {
        if(!backedUp)return;
        for(String name:FILES){Map<String,?> values=originals.get(name);require(values!=null,"original preference file available: "+name);
            SharedPreferences.Editor edit=prefs(name).edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){
                String key=entry.getKey();Object v=entry.getValue();if(v instanceof Boolean)edit.putBoolean(key,((Boolean)v).booleanValue());
                else if(v instanceof Integer)edit.putInt(key,((Integer)v).intValue());else if(v instanceof Long)edit.putLong(key,((Long)v).longValue());
                else if(v instanceof Float)edit.putFloat(key,((Float)v).floatValue());else if(v instanceof String)edit.putString(key,(String)v);
                else if(v instanceof Set<?>)edit.putStringSet(key,new HashSet<String>((Set<String>)v));else throw new AssertionError("unhandled restore type");
            }
            require(edit.commit(),"exact preference restore committed: "+name);require(values.equals(prefs(name).getAll()),"complete original preference values/types/presence restored: "+name);
        }
        // A surviving target process must read restored flags, not retain the test's singleton state.
        Class<?> cls=Class.forName("local.airuize.receiver.PerformanceLearning",true,getTargetContext().getClassLoader());
        Field instance=cls.getDeclaredField("INSTANCE");instance.setAccessible(true);Field storage=cls.getDeclaredField("preferences");storage.setAccessible(true);storage.set(null,null);
        Method init=cls.getDeclaredMethod("initialize",Context.class,String.class,String.class);init.setAccessible(true);
        init.invoke(instance.get(null),getTargetContext(),String.valueOf(call("LearningDevice","id")),String.valueOf(call("LearningDevice","label")));
        // initialize may write schema for an originally absent/corrupt file. Restore disk once more
        // while keeping its safely decoded in-memory state, preserving exact pre-test data.
        Map<String,?> values=originals.get("performance-learning-v1");SharedPreferences.Editor edit=prefs("performance-learning-v1").edit().clear();
        for(Map.Entry<String,?> entry:values.entrySet()){Object v=entry.getValue();String k=entry.getKey();
            if(v instanceof Boolean)edit.putBoolean(k,((Boolean)v).booleanValue());else if(v instanceof Integer)edit.putInt(k,((Integer)v).intValue());
            else if(v instanceof Long)edit.putLong(k,((Long)v).longValue());else if(v instanceof Float)edit.putFloat(k,((Float)v).floatValue());
            else if(v instanceof String)edit.putString(k,(String)v);else if(v instanceof Set<?>)edit.putStringSet(k,new HashSet<String>((Set<String>)v));}
        require(edit.commit() && values.equals(prefs("performance-learning-v1").getAll()),"post-reload performance disk state remains exact");
        Intent steering=new Intent().setComponent(new ComponentName(PKG,STEERING));
        if(originalSteeringRunning)getTargetContext().startService(steering);else getTargetContext().stopService(steering);
        line("RESTORE_STEERING_SERVICE originalRunning="+originalSteeringRunning);
        require(backupFile().delete(),"successful exact restore deletes durable backup");backedUp=false;
        restorationPerformed=true;
        line("RESTORE_LEARNING_PREFS PASS completeFiles=4 typesAndPresence=true noClearData=true");
    }
    private interface Action{void run() throws Exception;}
    private void main(final Action action) throws Exception {
        final AtomicReference<Throwable> error=new AtomicReference<Throwable>();
        runOnMainSync(new Runnable(){public void run(){try{action.run();}catch(Throwable problem){error.set(problem);}}});
        // AlertDialog's default buttons post their application listener and dismissal via
        // Handler messages. SyncRunnable returning proves only that performClick returned.
        // Drain those queued main-loop callbacks before reading preferences or opening the
        // next dialog. Never wait for idle from inside the UI runnable itself.
        waitForIdleSync();
        if(error.get()!=null){AssertionError failure=new AssertionError("main operation failed");failure.initCause(error.get());throw failure;}
        require(Process.myPid()==pid,"target process has not restarted");
    }
    private void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private void line(String message){report.append(message).append('\n');Log.i("DiPlayLearningSmoke",message);}
}

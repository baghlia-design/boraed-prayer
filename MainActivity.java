package com.boraed.prayer;
import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    public static MainActivity instance;
    private static int previousRingerMode = AudioManager.RINGER_MODE_NORMAL;
    private static Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable restoreRunnable;
    private static Runnable tickerRunnable;
    private static long silentEndTime = 0;
    public static final String CHANNEL_ID = "silent_mode_channel";
    public static final String ALARM_CHANNEL_ID = "prayer_alarm_channel";
    public static final int NOTIF_ID = 1001;
    private static final int REQ_NOTIF = 101;
    private static final int REQ_DND = 102;

    @Override public void onCreate(Bundle s){
        super.onCreate(s);
        instance = this;
        setFullScreen();
        createChannels();
        getBridge().getWebView().addJavascriptInterface(new SilentBridge(), "AndroidSilent");
        handler.postDelayed(() -> askAllPermissions(), 1500);
        if(getIntent()!=null && "CANCEL_SILENT".equals(getIntent().getAction())) doCancelReal(this);
    }
    void setFullScreen(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P){
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R){
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            if(c!=null){ c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); c.hide(WindowInsetsCompat.Type.navigationBars()); }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }
    @Override public void onWindowFocusChanged(boolean hasFocus){ super.onWindowFocusChanged(hasFocus); if(hasFocus) setFullScreen(); }
    
    void askAllPermissions(){
        if(Build.VERSION.SDK_INT >= 33){
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED){
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF); return;
            }
        }
        askDndPermission();
    }
    void askDndPermission(){
        NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        if(!nm.isNotificationPolicyAccessGranted()){
            try{ Toast.makeText(this,"للوضع الصامت: فعل وصول عدم الإزعاج",Toast.LENGTH_LONG).show(); startActivityForResult(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), REQ_DND); return; }catch(Exception e){}
        }
        askBatteryPermission();
    }
    void askBatteryPermission(){
        try{ PowerManager pm=(PowerManager)getSystemService(Context.POWER_SERVICE); if(!pm.isIgnoringBatteryOptimizations(getPackageName())){ Intent i=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS); i.setData(Uri.parse("package:"+getPackageName())); startActivity(i); } }catch(Exception e){}
    }
    @Override public void onRequestPermissionsResult(int rc,String[] p,int[] g){ super.onRequestPermissionsResult(rc,p,g); if(rc==REQ_NOTIF) askDndPermission(); }
    @Override protected void onActivityResult(int rc,int res,Intent d){ super.onActivityResult(rc,res,d); if(rc==REQ_DND) askBatteryPermission(); }
    void createChannels(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            NotificationManager nm=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,"الصامت",NotificationManager.IMPORTANCE_LOW));
            NotificationChannel ac=new NotificationChannel(ALARM_CHANNEL_ID,"تنبيهات الصلاة",NotificationManager.IMPORTANCE_HIGH);
            ac.enableVibration(true); ac.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC); ac.setBypassDnd(true);
            nm.createNotificationChannel(ac);
        }
    }
    public class SilentBridge {
        @JavascriptInterface public void vibrate(int ms){ runOnUiThread(() -> { Vibrator v=(Vibrator)getSystemService(Context.VIBRATOR_SERVICE); if(v==null) return; if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)); else v.vibrate(ms); }); }
        @JavascriptInterface public void scheduleAlarm(long ts,int id,String title,String body,boolean isAdhan,int silentMinutes){
            try{ AlarmManager am=(AlarmManager)getSystemService(Context.ALARM_SERVICE); Intent intent=new Intent(MainActivity.this,AlarmReceiver.class); intent.putExtra("id",id); intent.putExtra("title",title); intent.putExtra("body",body); intent.putExtra("isAdhan",isAdhan); intent.putExtra("silentMinutes",silentMinutes); PendingIntent pi=PendingIntent.getBroadcast(MainActivity.this,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE); am.setAlarmClock(new AlarmManager.AlarmClockInfo(ts,pi),pi); }catch(Exception e){}
        }
        @JavascriptInterface public void cancelAllAlarms(){ try{ AlarmManager am=(AlarmManager)getSystemService(Context.ALARM_SERVICE); for(int i=0;i<50;i++){ Intent intent=new Intent(MainActivity.this,AlarmReceiver.class); PendingIntent pi=PendingIntent.getBroadcast(MainActivity.this,i,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE); am.cancel(pi);} }catch(Exception e){} }
        @JavascriptInterface public void setSilent(int m){ runOnUiThread(() -> doSilentReal(MainActivity.this,m)); }
        @JavascriptInterface public void cancelSilent(){ runOnUiThread(() -> doCancelReal(MainActivity.this)); }
        @JavascriptInterface public long getRemainingSeconds(){ if(silentEndTime==0) return 0; long r=(silentEndTime-System.currentTimeMillis())/1000; return r>0?r:0; }
        @JavascriptInterface public boolean hasPermission(){ NotificationManager nm=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE); return nm.isNotificationPolicyAccessGranted(); }
        @JavascriptInterface public void requestPermission(){ startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); }
    }
    static void doSilentReal(Context ctx,int minutes){ try{ PowerManager pm=(PowerManager)ctx.getSystemService(Context.POWER_SERVICE); PowerManager.WakeLock wl=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Sama:Silent"); wl.acquire(5000); AudioManager am=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE); previousRingerMode=am.getRingerMode(); am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE); silentEndTime=System.currentTimeMillis()+(minutes*60*1000L); if(restoreRunnable!=null) handler.removeCallbacks(restoreRunnable); if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable); startTicker(ctx); restoreRunnable=() -> { try{ AudioManager a=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE); a.setRingerMode(previousRingerMode);}catch(Exception e){} silentEndTime=0; cancelStatic(ctx); if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable); }; handler.postDelayed(restoreRunnable,minutes*60*1000L); wl.release(); }catch(Exception e){} }
    static void doCancelReal(Context ctx){ if(restoreRunnable!=null) handler.removeCallbacks(restoreRunnable); if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable); silentEndTime=0; try{ AudioManager a=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE); a.setRingerMode(previousRingerMode);}catch(Exception e){} cancelStatic(ctx); }
    static void startTicker(Context ctx){ tickerRunnable=new Runnable(){ @Override public void run(){ long rem=(silentEndTime-System.currentTimeMillis())/1000; if(rem<=0) return; showStatic(ctx,rem); handler.postDelayed(this,1000);} }; handler.post(tickerRunnable); showStatic(ctx,(silentEndTime-System.currentTimeMillis())/1000); }
    static void showStatic(Context ctx,long sec){ try{ long m=sec/60; long s=sec%60; String t=String.format("%02d:%02d",m,s); Intent ci=new Intent(ctx,MainActivity.class); ci.setAction("CANCEL_SILENT"); PendingIntent pi=PendingIntent.getActivity(ctx,0,ci,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE); NotificationCompat.Builder b=new NotificationCompat.Builder(ctx,CHANNEL_ID).setSmallIcon(ctx.getApplicationInfo().icon).setContentTitle("🔕 وضع الصامت مفعل").setContentText("المتبقي: "+t+" - اضغط للإنهاء").setOngoing(true).setOnlyAlertOnce(true).addAction(0,"إيقاف الآن",pi); ((NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID,b.build()); }catch(Exception e){} }
    static void cancelStatic(Context ctx){ try{ ((NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID); }catch(Exception e){} }
    @Override protected void onNewIntent(Intent i){ super.onNewIntent(i); if(i!=null && "CANCEL_SILENT".equals(i.getAction())) doCancelReal(this); }

    public static class AlarmReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent){
            PowerManager.WakeLock wl=null, wlScreen=null;
            try{
                PowerManager pm=(PowerManager)ctx.getSystemService(Context.POWER_SERVICE);
                wl=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "Sama:Alarm"); wl.acquire(90000);
                wlScreen=pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "Sama:Screen"); wlScreen.acquire(90000);
                int id=intent.getIntExtra("id",0); String title=intent.getStringExtra("title"); String body=intent.getStringExtra("body"); boolean isAdhan=intent.getBooleanExtra("isAdhan",false); int silentMinutes=intent.getIntExtra("silentMinutes",0);
                try{
                    int soundRes=0; if(isAdhan){ soundRes=ctx.getResources().getIdentifier("azan","raw",ctx.getPackageName()); if(soundRes==0) soundRes=ctx.getResources().getIdentifier("adhan","raw",ctx.getPackageName()); } else { soundRes=ctx.getResources().getIdentifier("kawthar","raw",ctx.getPackageName()); }
                    if(soundRes!=0){ MediaPlayer mp=MediaPlayer.create(ctx,soundRes); if(mp!=null){ mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()); mp.setWakeMode(ctx,PowerManager.PARTIAL_WAKE_LOCK); mp.setVolume(1.0f,1.0f); mp.setOnCompletionListener(MediaPlayer::release); mp.start(); } }
                    Vibrator v=(Vibrator)ctx.getSystemService(Context.VIBRATOR_SERVICE); if(v!=null){ v.vibrate(VibrationEffect.createWaveform(new long[]{0,500,500,500},0)); }
                }catch(Exception e){}
                Intent open=new Intent(ctx, MainActivity.class); open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent fullPI=PendingIntent.getActivity(ctx, id+1000, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                PendingIntent contentPI=PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                NotificationManager nm=(NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationCompat.Builder b=new NotificationCompat.Builder(ctx, ALARM_CHANNEL_ID).setSmallIcon(ctx.getApplicationInfo().icon).setContentTitle(title!=null?title:"حان وقت الصلاة").setContentText(body!=null?body:"حان الآن موعد الصلاة").setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setFullScreenIntent(fullPI,true).setAutoCancel(true).setOngoing(true).setContentIntent(contentPI);
                nm.notify(2000+id, b.build());
                try{ new Handler(Looper.getMainLooper()).postDelayed(() -> { try{ ctx.startActivity(open); }catch(Exception e){} },500); }catch(Exception e){ ctx.startActivity(open); }
                if(isAdhan && silentMinutes>0){ AudioManager am=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE); previousRingerMode=am.getRingerMode(); am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE); silentEndTime=System.currentTimeMillis()+(silentMinutes*60*1000L); if(restoreRunnable!=null) handler.removeCallbacks(restoreRunnable); if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable); startTicker(ctx); showStatic(ctx,silentMinutes*60L); restoreRunnable=() -> { try{ AudioManager a=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE); a.setRingerMode(previousRingerMode);}catch(Exception e){} silentEndTime=0; cancelStatic(ctx); if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable); }; handler.postDelayed(restoreRunnable,silentMinutes*60*1000L); }
            }catch(Exception e){} finally { if(wl!=null && wl.isHeld()) wl.release(); if(wlScreen!=null && wlScreen.isHeld()) wlScreen.release(); }
        }
    }
    public static class BootReceiver extends BroadcastReceiver { @Override public void onReceive(Context ctx, Intent intent){} }
}

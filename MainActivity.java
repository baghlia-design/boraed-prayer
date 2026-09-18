package com.boraed.prayer;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import androidx.core.app.NotificationCompat;
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

    @Override public void onCreate(Bundle s){
        super.onCreate(s);
        instance = this;
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().getDecorView().setSystemUiVisibility(1542);
        createChannels();
        getBridge().getWebView().addJavascriptInterface(new SilentBridge(), "AndroidSilent");
        try {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            if(!pm.isIgnoringBatteryOptimizations(getPackageName())){
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:"+getPackageName())); startActivity(i);
            }
        } catch(Exception e){}
    }

    void createChannels(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "الصامت", NotificationManager.IMPORTANCE_LOW));
            NotificationChannel alarmCh = new NotificationChannel(ALARM_CHANNEL_ID, "تنبيهات الصلاة", NotificationManager.IMPORTANCE_HIGH);
            alarmCh.enableVibration(true);
            alarmCh.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            alarmCh.setBypassDnd(true);
            nm.createNotificationChannel(alarmCh);
        }
    }

    public class SilentBridge {
        @JavascriptInterface public void vibrate(int ms){
            runOnUiThread(() -> {
                Vibrator v = (Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
                if(v==null) return;
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)); else v.vibrate(ms);
            });
        }
        @JavascriptInterface public void scheduleAlarm(long timestampMillis, int id, String title, String body, boolean isAdhan, int silentMinutes){
            try {
                AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
                Intent intent = new Intent(MainActivity.this, AlarmReceiver.class);
                intent.putExtra("id", id); intent.putExtra("title", title); intent.putExtra("body", body);
                intent.putExtra("isAdhan", isAdhan); intent.putExtra("silentMinutes", silentMinutes);
                PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(timestampMillis, pi), pi);
            } catch(Exception e){}
        }
        @JavascriptInterface public void cancelAllAlarms(){
            try {
                AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
                for(int i=0;i<50;i++){
                    Intent intent = new Intent(MainActivity.this, AlarmReceiver.class);
                    PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, i, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    am.cancel(pi);
                }
            } catch(Exception e){}
        }
        @JavascriptInterface public void setSilent(int minutes){ runOnUiThread(() -> { doSilentReal(MainActivity.this, minutes); }); }
        @JavascriptInterface public void cancelSilent(){ runOnUiThread(() -> { doCancelReal(MainActivity.this); }); }
        @JavascriptInterface public long getRemainingSeconds(){
            if(silentEndTime==0) return 0;
            long rem = (silentEndTime - System.currentTimeMillis())/1000;
            return rem>0 ? rem : 0;
        }
        @JavascriptInterface public boolean hasPermission(){ NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE); return nm.isNotificationPolicyAccessGranted(); }
        @JavascriptInterface public void requestPermission(){ startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); }
    }

    static void doSilentReal(Context ctx, int minutes){
        try {
            PowerManager pm = (PowerManager)ctx.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sama:Silent");
            wl.acquire(5000);
            AudioManager am = (AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
            previousRingerMode = am.getRingerMode();
            am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
            silentEndTime = System.currentTimeMillis() + (minutes*60*1000L);
            if(restoreRunnable!=null) handler.removeCallbacks(restoreRunnable);
            if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable);
            startTicker(ctx);
            restoreRunnable = () -> { try{ AudioManager a=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE); a.setRingerMode(previousRingerMode);}catch(Exception e){} silentEndTime=0; cancelStatic(ctx); if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable); };
            handler.postDelayed(restoreRunnable, minutes*60*1000L);
            wl.release();
        } catch(Exception e){}
    }
    static void doCancelReal(Context ctx){
        if(restoreRunnable!=null) handler.removeCallbacks(restoreRunnable);
        if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable);
        silentEndTime=0;
        try{ AudioManager a=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE); a.setRingerMode(previousRingerMode); }catch(Exception e){}
        cancelStatic(ctx);
    }
    static void startTicker(Context ctx){
        tickerRunnable = new Runnable(){ @Override public void run(){ long rem = (silentEndTime - System.currentTimeMillis())/1000; if(rem<=0) return; showStatic(ctx, rem); handler.postDelayed(this, 1000); } };
        handler.post(tickerRunnable);
        showStatic(ctx, (silentEndTime - System.currentTimeMillis())/1000);
    }
    static void showStatic(Context ctx, long sec){ try{ long m=sec/60; long s=sec%60; String t=String.format("%02d:%02d", m,s); Intent ci=new Intent(ctx, MainActivity.class); ci.setAction("CANCEL_SILENT"); PendingIntent pi=PendingIntent.getActivity(ctx,0,ci,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE); NotificationCompat.Builder b=new NotificationCompat.Builder(ctx,CHANNEL_ID).setSmallIcon(ctx.getApplicationInfo().icon).setContentTitle("🔕 وضع الصامت مفعل").setContentText("المتبقي: "+t+" - اضغط للإنهاء").setOngoing(true).setOnlyAlertOnce(true).addAction(0,"إيقاف الآن",pi); ((NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID,b.build()); }catch(Exception e){} }
    static void cancelStatic(Context ctx){ try{ ((NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID); }catch(Exception e){} }
    @Override protected void onNewIntent(Intent i){ super.onNewIntent(i); if(i!=null && "CANCEL_SILENT".equals(i.getAction())) doCancelReal(this); }

    public static class AlarmReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent){
            PowerManager.WakeLock wakeLock = null;
            try {
                PowerManager pm = (PowerManager)ctx.getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "Sama:PrayerAlarm");
                wakeLock.acquire(70000); // يبقى صاحي 70 ثانية حتى والشاشة مطفأة

                int id = intent.getIntExtra("id",0);
                String title = intent.getStringExtra("title");
                String body = intent.getStringExtra("body");
                boolean isAdhan = intent.getBooleanExtra("isAdhan",false);
                int silentMinutes = intent.getIntExtra("silentMinutes",0);

                try {
                    int soundRes = 0;
                    if(isAdhan){
                        soundRes = ctx.getResources().getIdentifier("azan","raw",ctx.getPackageName());
                        if(soundRes==0) soundRes = ctx.getResources().getIdentifier("adhan","raw",ctx.getPackageName());
                    } else {
                        soundRes = ctx.getResources().getIdentifier("kawthar","raw",ctx.getPackageName());
                        if(soundRes==0) soundRes = ctx.getResources().getIdentifier("alkawthar","raw",ctx.getPackageName());
                    }
                    if(soundRes!=0){
                        MediaPlayer mp = MediaPlayer.create(ctx, soundRes);
                        if(mp!=null){ 
                            mp.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
                            mp.setOnCompletionListener(MediaPlayer::release); 
                            mp.start(); 
                        }
                    }
                } catch(Exception e){}

                NotificationManager nm = (NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                Intent open = new Intent(ctx, MainActivity.class);
                PendingIntent pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, ALARM_CHANNEL_ID).setSmallIcon(ctx.getApplicationInfo().icon).setContentTitle(title).setContentText(body).setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setAutoCancel(true).setContentIntent(pi);
                nm.notify(2000+id, b.build());

                if(isAdhan && silentMinutes>0){
                    AudioManager am = (AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
                    previousRingerMode = am.getRingerMode();
                    am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                    silentEndTime = System.currentTimeMillis() + (silentMinutes*60*1000L);
                    if(restoreRunnable!=null) handler.removeCallbacks(restoreRunnable);
                    if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable);
                    startTicker(ctx); showStatic(ctx, silentMinutes*60L);
                    restoreRunnable = () -> { try{ AudioManager a=(AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE); a.setRingerMode(previousRingerMode);}catch(Exception e){} silentEndTime=0; cancelStatic(ctx); if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable); };
                    handler.postDelayed(restoreRunnable, silentMinutes*60*1000L);
                }
            } catch(Exception e){} finally {
                if(wakeLock!=null && wakeLock.isHeld()) wakeLock.release();
            }
        }
    }

    public static class BootReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent){
            if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())){
                // سيتم إعادة الجدولة عند فتح التطبيق من الـ JS
            }
        }
    }
}

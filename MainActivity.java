package com.boraed.prayer;
import android.app.*;
import android.content.*;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.os.VibrationEffect;
import android.os.Vibrator;
import androidx.core.app.NotificationCompat;
import com.getcapacitor.BridgeActivity;
public class MainActivity extends BridgeActivity {
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
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        hide(); createChannels();
        getBridge().getWebView().addJavascriptInterface(new SilentBridge(), "AndroidSilent");
        try {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            if(!pm.isIgnoringBatteryOptimizations(getPackageName())){
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                i.setData(Uri.parse("package:"+getPackageName())); startActivity(i);
            }
        } catch(Exception e){}
    }
    void hide(){ getWindow().getDecorView().setSystemUiVisibility(1542); }
    @Override public void onWindowFocusChanged(boolean f){ super.onWindowFocusChanged(f); if(f) hide(); }
    void createChannels(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "الصامت", NotificationManager.IMPORTANCE_LOW));
            NotificationChannel alarmCh = new NotificationChannel(ALARM_CHANNEL_ID, "تنبيهات الصلاة", NotificationManager.IMPORTANCE_HIGH);
            alarmCh.enableVibration(true);
            alarmCh.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
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
        // هذه هي الدالة الجديدة اللي تصحّي الجوال والشاشة مطفية
        @JavascriptInterface public void scheduleAlarm(long timestampMillis, int id, String title, String body, boolean isAdhan, int silentMinutes){
            try {
                AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
                Intent intent = new Intent(MainActivity.this, AlarmReceiver.class);
                intent.putExtra("id", id);
                intent.putExtra("title", title);
                intent.putExtra("body", body);
                intent.putExtra("isAdhan", isAdhan);
                intent.putExtra("silentMinutes", silentMinutes);
                PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(timestampMillis, pi);
                am.setAlarmClock(info, pi);
            } catch(Exception e){}
        }
        @JavascriptInterface public void cancelAllAlarms(){
            try {
                AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
                for(int i=0;i<20;i++){
                    Intent intent = new Intent(MainActivity.this, AlarmReceiver.class);
                    PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, i, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                    am.cancel(pi);
                }
            } catch(Exception e){}
        }
        @JavascriptInterface public void setSilent(int minutes){
            runOnUiThread(() -> doSilent(minutes));
        }
        @JavascriptInterface public void cancelSilent(){
            runOnUiThread(() -> doCancelSilent());
        }
        @JavascriptInterface public long getRemainingSeconds(){
            if(silentEndTime==0) return 0;
            long rem = (silentEndTime - System.currentTimeMillis())/1000;
            return rem>0 ? rem : 0;
        }
        @JavascriptInterface public boolean hasPermission(){ NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE); return nm.isNotificationPolicyAccessGranted(); }
        @JavascriptInterface public void requestPermission(){ startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); }
    }
    static void doSilent(int minutes){
        try {
            Context ctx = null; // سيتم ضبطه من الـ receiver
            // هذه الدالة تُستدعى من الـ UI Thread فقط
        } catch(Exception e){}
        // المنطق الحقيقي في الأسفل
        AudioManager am = null;
        try { am = (AudioManager) com.getcapacitor.Bridge.getInstance().getContext().getSystemService(Context.AUDIO_SERVICE); } catch(Exception e){}
        if(am==null) return;
        previousRingerMode = am.getRingerMode();
        am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        silentEndTime = System.currentTimeMillis() + (minutes*60*1000L);
        if(restoreRunnable!=null) handler.removeCallbacks(restoreRunnable);
        if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable);
        startStaticTicker();
        restoreRunnable = () -> { try{ AudioManager a = (AudioManager) com.getcapacitor.Bridge.getInstance().getContext().getSystemService(Context.AUDIO_SERVICE); a.setRingerMode(previousRingerMode); }catch(Exception e){} silentEndTime=0; cancelStaticNotification(); if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable); };
        handler.postDelayed(restoreRunnable, minutes*60*1000L);
    }
    static void doCancelSilent(){
        if(restoreRunnable!=null) handler.removeCallbacks(restoreRunnable);
        if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable);
        silentEndTime=0;
        try{ AudioManager a = (AudioManager) com.getcapacitor.Bridge.getInstance().getContext().getSystemService(Context.AUDIO_SERVICE); a.setRingerMode(previousRingerMode); }catch(Exception e){}
        cancelStaticNotification();
    }
    static void startStaticTicker(){
        tickerRunnable = new Runnable() {
            @Override public void run(){
                long rem = (silentEndTime - System.currentTimeMillis())/1000;
                if(rem<=0) return;
                showStaticNotification(rem);
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(tickerRunnable);
    }
    static void showStaticNotification(long totalSeconds){
        try {
            Context ctx = com.getcapacitor.Bridge.getInstance().getContext();
            long m = totalSeconds/60; long s = totalSeconds%60;
            String timeText = String.format("%02d:%02d", m, s);
            Intent cancelIntent = new Intent(ctx, MainActivity.class);
            cancelIntent.setAction("CANCEL_SILENT");
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, CHANNEL_ID).setSmallIcon(ctx.getApplicationInfo().icon).setContentTitle("🔕 وضع الصامت مفعل").setContentText("المتبقي: "+timeText+" - اضغط للإنهاء").setOngoing(true).setOnlyAlertOnce(true).addAction(0, "إيقاف الآن", pi);
            ((NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, b.build());
        } catch(Exception e){}
    }
    static void cancelStaticNotification(){
        try {
            Context ctx = com.getcapacitor.Bridge.getInstance().getContext();
            ((NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID);
        } catch(Exception e){}
    }
    void showOngoingNotification(long totalSeconds){ showStaticNotification(totalSeconds); }
    void cancelNotification(){ cancelStaticNotification(); }
    @Override protected void onNewIntent(Intent i){ super.onNewIntent(i); if(i!=null && "CANCEL_SILENT".equals(i.getAction())){ doCancelSilent(); } }

    public static class AlarmReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context ctx, Intent intent){
            try {
                int id = intent.getIntExtra("id", 0);
                String title = intent.getStringExtra("title");
                String body = intent.getStringExtra("body");
                boolean isAdhan = intent.getBooleanExtra("isAdhan", false);
                int silentMinutes = intent.getIntExtra("silentMinutes", 0);

                // 1. أظهر تنبيه الصلاة حتى والشاشة مطفية
                NotificationManager nm = (NotificationManager)ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                Intent openIntent = new Intent(ctx, MainActivity.class);
                PendingIntent pi = PendingIntent.getActivity(ctx, id, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                NotificationCompat.Builder b = new NotificationCompat.Builder(ctx, ALARM_CHANNEL_ID)
                    .setSmallIcon(ctx.getApplicationInfo().icon)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
                nm.notify(2000+id, b.build());

                // 2. إذا كان أذان، حوّل للصامت (مع هزاز) حتى والشاشة مطفية
                if(isAdhan && silentMinutes>0){
                    AudioManager am = (AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE);
                    previousRingerMode = am.getRingerMode();
                    am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                    silentEndTime = System.currentTimeMillis() + (silentMinutes*60*1000L);
                    if(restoreRunnable!=null) handler.removeCallbacks(restoreRunnable);
                    if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable);
                    startStaticTicker();
                    showStaticNotification(silentMinutes*60L);
                    restoreRunnable = () -> { try{ AudioManager a = (AudioManager)ctx.getSystemService(Context.AUDIO_SERVICE); a.setRingerMode(previousRingerMode); }catch(Exception e){} silentEndTime=0; cancelStaticNotification(); if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable); };
                    handler.postDelayed(restoreRunnable, silentMinutes*60*1000L);
                }
            } catch(Exception e){}
        }
    }
}

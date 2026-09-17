package com.boraed.prayer;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import androidx.core.app.NotificationCompat;
import com.getcapacitor.BridgeActivity;
public class MainActivity extends BridgeActivity {
    private int previousRingerMode = AudioManager.RINGER_MODE_NORMAL;
    private Handler handler = new Handler();
    private Runnable restoreRunnable;
    private Runnable tickerRunnable;
    private long silentEndTime = 0;
    public static final String CHANNEL_ID = "silent_mode_channel";
    public static final int NOTIF_ID = 1001;
    @Override public void onCreate(Bundle s){
        super.onCreate(s);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        hide(); createChannel();
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
    void createChannel(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "الصامت", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
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
        @JavascriptInterface public void setSilent(int minutes){
            runOnUiThread(() -> {
                try {
                    AudioManager am = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
                    previousRingerMode = am.getRingerMode();
                    am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                    silentEndTime = System.currentTimeMillis() + (minutes * 60 * 1000L);
                    if(restoreRunnable!=null) handler.removeCallbacks(restoreRunnable);
                    if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable);
                    startTicker();
                    restoreRunnable = () -> { am.setRingerMode(previousRingerMode); silentEndTime=0; cancelNotification(); if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable); };
                    handler.postDelayed(restoreRunnable, minutes*60*1000L);
                } catch(Exception e){}
            });
        }
        @JavascriptInterface public void cancelSilent(){
            runOnUiThread(() -> {
                if(restoreRunnable!=null) handler.removeCallbacks(restoreRunnable);
                if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable);
                silentEndTime=0;
                AudioManager am = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
                am.setRingerMode(previousRingerMode);
                cancelNotification();
            });
        }
        @JavascriptInterface public long getRemainingSeconds(){
            if(silentEndTime==0) return 0;
            long rem = (silentEndTime - System.currentTimeMillis())/1000;
            return rem>0 ? rem : 0;
        }
        @JavascriptInterface public boolean hasPermission(){ NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE); return nm.isNotificationPolicyAccessGranted(); }
        @JavascriptInterface public void requestPermission(){ startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); }
    }
    void startTicker(){
        tickerRunnable = new Runnable() {
            @Override public void run(){
                long rem = 0;
                if(silentEndTime>0) rem = (silentEndTime - System.currentTimeMillis())/1000;
                if(rem<=0){ return; }
                showOngoingNotification(rem);
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(tickerRunnable);
        showOngoingNotification((silentEndTime - System.currentTimeMillis())/1000);
    }
    void showOngoingNotification(long totalSeconds){
        long m = totalSeconds/60;
        long s = totalSeconds%60;
        String timeText = String.format("%02d:%02d", m, s);
        Intent cancelIntent = new Intent(this, MainActivity.class);
        cancelIntent.setAction("CANCEL_SILENT");
        PendingIntent pi = PendingIntent.getActivity(this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(getApplicationInfo().icon)
            .setContentTitle("🔕 وضع الصامت مفعل")
            .setContentText("المتبقي: "+timeText+" - اضغط للإنهاء الآن")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "إيقاف الصامت الآن", pi);
        ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, b.build());
    }
    void cancelNotification(){ ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID); }
    @Override protected void onNewIntent(Intent i){ super.onNewIntent(i); if(i!=null && "CANCEL_SILENT".equals(i.getAction())){ AudioManager am = (AudioManager)getSystemService(Context.AUDIO_SERVICE); am.setRingerMode(previousRingerMode); if(restoreRunnable!=null) handler.removeCallbacks(restoreRunnable); if(tickerRunnable!=null) handler.removeCallbacks(tickerRunnable); silentEndTime=0; cancelNotification(); } }
}

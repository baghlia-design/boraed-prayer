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
    public static final String CHANNEL_ID = "silent_mode_channel";
    public static final int NOTIF_ID = 1001;
    @Override public void onCreate(Bundle s){
        super.onCreate(s);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        hide();
        createChannel();
        getBridge().getWebView().addJavascriptInterface(new SilentBridge(), "AndroidSilent");
        try {
            PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
            if(!pm.isIgnoringBatteryOptimizations(getPackageName())){
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:"+getPackageName()));
                startActivity(intent);
            }
        } catch(Exception e){}
    }
    void hide(){ getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN); }
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
                if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
                    v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                } else { v.vibrate(ms); }
            });
        }
        @JavascriptInterface public void setSilent(int minutes){
            runOnUiThread(() -> {
                try {
                    AudioManager am = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
                    NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
                    previousRingerMode = am.getRingerMode();
                    int prevFilter = nm.getCurrentInterruptionFilter();
                    am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                    if(nm.isNotificationPolicyAccessGranted()){
                        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE);
                    }
                    if(restoreRunnable!= null) handler.removeCallbacks(restoreRunnable);
                    showOngoingNotification(minutes);
                    restoreRunnable = () -> {
                        am.setRingerMode(previousRingerMode);
                        try{ if(nm.isNotificationPolicyAccessGranted()) nm.setInterruptionFilter(prevFilter); }catch(Exception e){}
                        cancelNotification();
                    };
                    handler.postDelayed(restoreRunnable, minutes * 60 * 1000L);
                } catch(Exception e){}
            });
        }
        @JavascriptInterface public void cancelSilent(){
            runOnUiThread(() -> {
                if(restoreRunnable!= null) handler.removeCallbacks(restoreRunnable);
                AudioManager am = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
                NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
                am.setRingerMode(previousRingerMode);
                try{ if(nm.isNotificationPolicyAccessGranted()) nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL); }catch(Exception e){}
                cancelNotification();
            });
        }
        @JavascriptInterface public boolean hasPermission(){ NotificationManager nm = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE); return nm.isNotificationPolicyAccessGranted(); }
        @JavascriptInterface public void requestPermission(){ startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)); }
    }
    void showOngoingNotification(int minutes){
        Intent cancelIntent = new Intent(this, MainActivity.class);
        cancelIntent.setAction("CANCEL_SILENT");
        PendingIntent pi = PendingIntent.getActivity(this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(getApplicationInfo().icon).setContentTitle("وضع الصامت مفعل").setContentText("ينتهي بعد "+minutes+" دقيقة - اضغط للانهاء").setOngoing(true).addAction(0, "انهاء الصامت الان", pi);
        ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, b.build());
    }
    void cancelNotification(){ ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NOTIF_ID); }
    @Override protected void onNewIntent(Intent intent){ super.onNewIntent(intent); if(intent!= null && "CANCEL_SILENT".equals(intent.getAction())){ AudioManager am = (AudioManager)getSystemService(Context.AUDIO_SERVICE); am.setRingerMode(previousRingerMode); if(restoreRunnable!= null) handler.removeCallbacks(restoreRunnable); cancelNotification(); } }
}

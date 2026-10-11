package com.crit.landing;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

/** Always-on watcher. A foreground service keeps Landing's process awake (not frozen)
 *  while other apps are open, so it hears the screen turning off from anywhere. */
public class UnlockService extends Service {
    static final String CHANNEL = "unlock";
    private BroadcastReceiver receiver;

    static void start(Context c) {
        if (!c.getSharedPreferences(HomeActivity.PREFS, MODE_PRIVATE).getBoolean(HomeActivity.KEY_UNLOCK, false)) return;
        try {
            Intent i = new Intent(c, UnlockService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { }
    }

    static void stop(Context c) { c.stopService(new Intent(c, UnlockService.class)); }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Open on unlock", NotificationManager.IMPORTANCE_MIN));
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!getSharedPreferences(HomeActivity.PREFS, MODE_PRIVATE).getBoolean(HomeActivity.KEY_UNLOCK, false)) return;
                if (!Settings.canDrawOverlays(c)) return;
                AudioManager am = getSystemService(AudioManager.class);
                int mode = am == null ? AudioManager.MODE_NORMAL : am.getMode();
                if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                        || mode == AudioManager.MODE_RINGTONE) return;
                HomeActivity.launchTarget(c);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, HomeActivity.class).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Unlock opens " + HomeActivity.appLabel(this, HomeActivity.findTargetPackage(this)))
                .setContentText("Tap for Landing settings")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (receiver != null) try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}

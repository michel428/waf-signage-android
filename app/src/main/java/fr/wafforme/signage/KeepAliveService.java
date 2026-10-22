package fr.wafforme.signage;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

/**
 * Garde l'affichage au premier plan (v2.0 — Mi TV / Android TV).
 *
 * Sur les TV (Mi TV, Philips, TCL…), le bouton « marche » de la télécommande
 * ne fait qu'une mise en veille : au rallumage il n'y a pas de démarrage,
 * donc pas de BOOT_COMPLETED, et la TV revient sur son lanceur (PatchWall).
 * Ce service (premier plan, discret) :
 *  - relance l'affichage quand l'écran se rallume (SCREEN_ON / fin d'écran de veille) ;
 *  - ramène l'affichage s'il est resté caché plus de N minutes (quelqu'un a
 *    ouvert une autre appli puis est parti) — réglable dans le menu de la box.
 */
public class KeepAliveService extends Service {
    static volatile boolean visible = false;
    static volatile long hiddenSince = 0;

    private static final String CH = "waf_keepalive";
    private final Handler h = new Handler(Looper.getMainLooper());
    private BroadcastReceiver rx;

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable ignored) {}
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("waf", MODE_PRIVATE); }
    static boolean keepFront(Context c) { return prefs(c).getBoolean("keep_front", true); }
    static int returnMinutes(Context c) { return Math.max(1, prefs(c).getInt("front_min", 5)); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        goForeground();
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent in) {
                String a = in.getAction();
                if (Intent.ACTION_SCREEN_OFF.equals(a)) return;
                // TV rallumée / fin d'écran de veille : on revient sur l'affichage
                h.postDelayed(() -> bringFront("wake"), 3500);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_DREAMING_STOPPED);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(rx, f);
        } catch (Throwable ignored) {}
        h.postDelayed(tick, 60_000);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try { if (rx != null) unregisterReceiver(rx); } catch (Throwable ignored) {}
        super.onDestroy();
    }

    /** Vérification toutes les minutes : affichage caché depuis trop longtemps → on le ramène. */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            try {
                if (!visible && hiddenSince > 0 && keepFront(KeepAliveService.this)
                        && System.currentTimeMillis() - hiddenSince > returnMinutes(KeepAliveService.this) * 60_000L) {
                    bringFront("idle");
                }
            } catch (Throwable ignored) {}
            h.postDelayed(this, 60_000);
        }
    };

    private boolean screenOn() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm == null || (Build.VERSION.SDK_INT >= 20 ? pm.isInteractive() : pm.isScreenOn());
        } catch (Throwable t) { return true; }
    }

    private void bringFront(String why) {
        if (visible || !keepFront(this) || !screenOn()) return;
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra("fromWake", why);
            startActivity(i);
            hiddenSince = System.currentTimeMillis();   // pas de nouvel essai avant N min si le système refuse
        } catch (Throwable ignored) {}
    }

    private void goForeground() {
        try {
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm != null && nm.getNotificationChannel(CH) == null) {
                    NotificationChannel ch = new NotificationChannel(CH, "Affichage WAF", NotificationManager.IMPORTANCE_MIN);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
                b = new Notification.Builder(this, CH);
            } else {
                b = new Notification.Builder(this);
                b.setPriority(Notification.PRIORITY_MIN);
            }
            b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("WAF Signage").setContentText("Affichage actif").setOngoing(true);
            Notification n = b.build();
            if (Build.VERSION.SDK_INT >= 34) startForeground(42, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(42, n);
        } catch (Throwable ignored) {}
    }
}

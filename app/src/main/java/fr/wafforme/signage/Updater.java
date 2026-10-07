package fr.wafforme.signage;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mise à jour automatique de l'appli depuis GitHub (via le serveur signage :
 * /api/app-version.php?platform=android, qui donne la dernière version publiée).
 *
 * - Android 12+ : installation silencieuse (après la 1re mise à jour faite par l'appli).
 * - Avant Android 12 : Android exige une confirmation → l'appli ne l'affiche pas
 *   pendant l'affichage, elle la garde pour le menu (« Installer la mise à jour »).
 */
public class Updater {

    public interface Listener { void onState(String state, String detail); }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("waf", Context.MODE_PRIVATE); }

    /** Vérifie puis installe si besoin. interactive = lancé par quelqu'un devant l'écran (menu). */
    public static void run(final Context ctx, final boolean interactive, final Listener l) {
        new Thread(() -> {
            try {
                JSONObject j = new JSONObject(get(BuildConfig.BASE_URL + "/api/app-version.php?platform=android&current=" + BuildConfig.VERSION_CODE));
                if (!j.optBoolean("success")) { say(l, "none", j.optString("error", "")); return; }
                JSONObject d = j.getJSONObject("data");
                int code = d.optInt("code", 0);
                String ver = d.optString("version", "");
                prefs(ctx).edit().putInt("latest_code", code).putString("latest_version", ver).putLong("last_check", System.currentTimeMillis()).apply();
                if (code <= BuildConfig.VERSION_CODE) { say(l, "uptodate", BuildConfig.VERSION_NAME); return; }

                say(l, "downloading", ver);
                File apk = new File(ctx.getCacheDir(), "waf-signage-update.apk");
                download(d.getString("url"), apk);
                say(l, "installing", ver);
                install(ctx, apk, interactive);
            } catch (Throwable t) {
                prefs(ctx).edit().putString("update_error", t.getClass().getSimpleName() + ": " + t.getMessage()).apply();
                say(l, "error", t.getMessage());
            }
        }).start();
    }

    private static void say(Listener l, String s, String d) { if (l != null) l.onState(s, d == null ? "" : d); }

    static void install(Context ctx, File apk, boolean interactive) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        try (OutputStream out = s.openWrite("waf-signage", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            s.fsync(out);
        }
        Intent i = new Intent(ctx, UpdateReceiver.class).putExtra("interactive", interactive);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pend = PendingIntent.getBroadcast(ctx, id, i, flags);
        s.commit(pend.getIntentSender());
        s.close();
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(15000); c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "WAFSignageApp/" + BuildConfig.VERSION_NAME);
        try (InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream()) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while (in != null && (n = in.read(b)) > 0) o.write(b, 0, n);
            return o.toString("UTF-8");
        } finally { c.disconnect(); }
    }

    /** Téléchargement en suivant les redirections GitHub (→ objects.githubusercontent.com). */
    static void download(String url, File dest) throws Exception {
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000); c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "WAFSignageApp/" + BuildConfig.VERSION_NAME);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { url = c.getHeaderField("Location"); c.disconnect(); continue; }
            if (code != 200) { c.disconnect(); throw new Exception("HTTP " + code); }
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(dest)) {
                byte[] b = new byte[65536]; int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
            } finally { c.disconnect(); }
            return;
        }
        throw new Exception("trop de redirections");
    }
}

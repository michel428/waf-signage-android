package fr.wafforme.signage;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Copie locale des vidéos de la playlist sur la box (dossier cache de l'appli).
 * - Une vidéo déjà copiée démarre instantanément et passe même sans réseau.
 * - Téléchargement en arrière-plan, une vidéo à la fois (ne gêne pas la lecture).
 * - Limites : 120 Mo par vidéo (les vidéos de cours ne sont pas copiées),
 *   600 Mo au total — les vidéos les moins récemment jouées sont effacées d'abord.
 * - Une copie de plus de 3 jours est re-téléchargée (fichier remplacé sur le serveur ?).
 */
final class VideoCache {
    private static final long MAX_FILE  = 120L * 1024 * 1024;
    private static final long MAX_TOTAL = 600L * 1024 * 1024;
    private static final long MAX_AGE   = 3L * 24 * 3600 * 1000;
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Set<String> pending = new HashSet<>();

    private VideoCache() {}

    private static File dir(Context c) {
        File d = new File(c.getCacheDir(), "videos");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String key(String url) {
        try {
            String u = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
            byte[] h = MessageDigest.getInstance("SHA-1").digest(u.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString() + ".mp4";
        } catch (Throwable t) { return String.valueOf(url.hashCode()) + ".mp4"; }
    }

    /** Fichier local complet, ou null (dans ce cas, lire depuis le réseau). */
    static File get(Context c, String url) {
        File f = new File(dir(c), key(url));
        if (!f.exists() || f.length() == 0) return null;
        if (System.currentTimeMillis() - f.lastModified() > MAX_AGE) { prefetch(c, url, true); }
        else f.setLastModified(System.currentTimeMillis());  // « récemment utilisée » (LRU)
        return f;
    }

    static void drop(Context c, String url) {
        try { new File(dir(c), key(url)).delete(); } catch (Throwable ignored) {}
    }

    static void prefetch(Context c, String url) { prefetch(c, url, false); }

    static void prefetch(final Context ctx, final String url, final boolean refresh) {
        if (url == null || !url.startsWith("http")) return;
        final Context c = ctx.getApplicationContext();
        final File f = new File(dir(c), key(url));
        if (!refresh && f.exists() && f.length() > 0) return;
        synchronized (pending) { if (!pending.add(url)) return; }
        POOL.execute(() -> {
            File part = new File(f.getPath() + ".part");
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(10000);
                con.setReadTimeout(30000);
                con.setRequestProperty("User-Agent", "WAFSignageApp/" + BuildConfig.VERSION_NAME);
                if (con.getResponseCode() != 200) return;
                long len = -1;
                try { String cl = con.getHeaderField("Content-Length"); if (cl != null) len = Long.parseLong(cl.trim()); } catch (Throwable ignored) {}
                if (len > MAX_FILE) return;
                trim(c, len > 0 ? len : 0);
                try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[64 * 1024];
                    long total = 0; int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_FILE) throw new java.io.IOException("trop gros");
                        out.write(buf, 0, n);
                    }
                }
                if (part.length() > 0 && (len <= 0 || part.length() == len)) {
                    if (f.exists()) f.delete();
                    part.renameTo(f);
                }
            } catch (Throwable ignored) {
            } finally {
                if (con != null) con.disconnect();
                if (part.exists()) part.delete();
                synchronized (pending) { pending.remove(url); }
            }
        });
    }

    /** Fait de la place : supprime les copies les moins récemment jouées. */
    private static void trim(Context c, long incoming) {
        File[] files = dir(c).listFiles((d, n) -> n.endsWith(".mp4"));
        if (files == null) return;
        long total = incoming;
        for (File f : files) total += f.length();
        if (total <= MAX_TOTAL) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= MAX_TOTAL) break;
            total -= f.length();
            f.delete();
        }
    }
}

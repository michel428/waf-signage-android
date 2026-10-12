package fr.wafforme.signage;

import android.app.Activity;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Lecture vidéo native (MediaPlayer + décodeur matériel de la box), placée
 * SOUS la page web transparente à l'endroit exact où la page affiche la vidéo.
 *
 * Pourquoi : dans une WebView, les box Android d'entrée de gamme décodent mal
 * (saccades, écran noir, démarrage lent). Le lecteur natif utilise directement
 * le décodeur matériel, et lit depuis une copie locale (VideoCache) quand elle
 * existe → démarrage immédiat, même sans réseau.
 *
 * La page (signage.js) appelle WafApp.videoPlay(...) / videoStop() et reçoit
 * en retour window.WafNV.started(id) / ended(id) / error(id, msg).
 */
class NativeVideo implements TextureView.SurfaceTextureListener {

    interface Callback { void js(String code); }

    private final Activity act;
    private final TextureView tv;
    private final Callback cb;
    private Surface surface;
    private MediaPlayer mp;

    private String id = null, url = null, fit = "cover";
    private boolean loop = false, muted = true, started = false;

    NativeVideo(Activity act, FrameLayout parent, Callback cb) {
        this.act = act;
        this.cb = cb;
        tv = new TextureView(act);
        tv.setSurfaceTextureListener(this);
        tv.setVisibility(View.INVISIBLE);
        parent.addView(tv, 0, new FrameLayout.LayoutParams(1, 1));
    }

    /** Coordonnées en pixels physiques (déjà multipliées par devicePixelRatio côté page). */
    void play(String id, String url, boolean loop, boolean muted, int x, int y, int w, int h, String fit) {
        stop(false);
        this.id = id; this.url = url; this.loop = loop; this.muted = muted;
        this.fit = "contain".equals(fit) ? "contain" : "cover";
        this.started = false;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.max(2, w), Math.max(2, h));
        lp.leftMargin = x; lp.topMargin = y;
        tv.setLayoutParams(lp);
        tv.setVisibility(View.VISIBLE);
        if (surface != null) start();
    }

    private void start() {
        if (url == null || surface == null) return;
        final String myId = id;
        try {
            mp = new MediaPlayer();
            mp.setSurface(surface);
            File local = VideoCache.get(act, url);
            if (local != null) mp.setDataSource(local.getAbsolutePath());
            else {
                Map<String, String> hd = new HashMap<>();
                hd.put("User-Agent", "WAFSignageApp/" + BuildConfig.VERSION_NAME);
                mp.setDataSource(act, Uri.parse(url), hd);
                VideoCache.prefetch(act, url);   // copie locale pour les passages suivants
            }
            mp.setLooping(loop);
            if (muted) mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(p -> {
                if (!same(myId)) return;
                applyTransform(p.getVideoWidth(), p.getVideoHeight());
                p.start();
            });
            mp.setOnVideoSizeChangedListener((p, vw, vh) -> { if (same(myId)) applyTransform(vw, vh); });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && same(myId) && !started) {
                    started = true;
                    cb.js("window.WafNV&&WafNV.started(" + q(myId) + ")");
                }
                return false;
            });
            mp.setOnCompletionListener(p -> { if (same(myId) && !loop) cb.js("window.WafNV&&WafNV.ended(" + q(myId) + ")"); });
            mp.setOnErrorListener((p, what, extra) -> {
                if (same(myId)) {
                    // Copie locale corrompue ? on l'efface pour la prochaine fois
                    VideoCache.drop(act, url);
                    cb.js("window.WafNV&&WafNV.error(" + q(myId) + "," + q("Lecture native impossible (" + what + "/" + extra + ")") + ")");
                    stop(false);
                }
                return true;
            });
            mp.prepareAsync();
        } catch (Throwable t) {
            cb.js("window.WafNV&&WafNV.error(" + q(myId) + "," + q("Lecture native impossible : " + t.getMessage()) + ")");
            stop(false);
        }
    }

    private boolean same(String myId) { return myId != null && myId.equals(id); }

    /** Recadrage façon object-fit (cover / contain) dans la zone de la page. */
    private void applyTransform(int vw, int vh) {
        int w = tv.getLayoutParams().width, h = tv.getLayoutParams().height;
        if (vw <= 0 || vh <= 0 || w <= 0 || h <= 0) return;
        float s = "contain".equals(fit) ? Math.min((float) w / vw, (float) h / vh) : Math.max((float) w / vw, (float) h / vh);
        Matrix m = new Matrix();
        m.setScale(vw * s / w, vh * s / h, w / 2f, h / 2f);
        tv.setTransform(m);
    }

    /** Arrête la lecture. notify=false : pas de rappel à la page. */
    void stop(boolean notify) {
        id = null; url = null; started = false;
        if (mp != null) {
            try { mp.setOnCompletionListener(null); mp.setOnErrorListener(null); mp.setOnInfoListener(null); mp.stop(); } catch (Throwable ignored) {}
            try { mp.release(); } catch (Throwable ignored) {}
            mp = null;
        }
        tv.setVisibility(View.INVISIBLE);
    }

    @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        surface = new Surface(st);
        if (url != null && mp == null) start();
    }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        stop(false);
        if (surface != null) { surface.release(); surface = null; }
        return true;
    }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }

    private static String q(String s) { return org.json.JSONObject.quote(s == null ? "" : s); }
}

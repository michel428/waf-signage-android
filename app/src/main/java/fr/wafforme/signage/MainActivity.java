package fr.wafforme.signage;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ConsoleMessage;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * WAF Signage — appli plein écran pour les boîtiers Android des écrans du club.
 *
 * 1. Page locale assets/boot.html : animation de lancement, puis
 *    - écran déjà configuré → lecture après un court compte à rebours ;
 *    - sinon → association par code (Écrans → Associer un écran) ou choix
 *      d'un écran existant.
 * 2. Lecture : https://signage.waf-forme.fr/player/?screen=<slug>
 * 3. Menu caché : touche MENU, appui long sur OK, ou 5 tapotements dans le
 *    coin en haut à gauche (écran tactile).
 * 4. Coupure réseau / page en erreur → écran « Hors connexion » qui
 *    réessaie tout seul et relance la lecture dès que le réseau revient.
 */
public class MainActivity extends Activity {

    static final String BASE = BuildConfig.BASE_URL;
    static final String BOOT = "file:///android_asset/boot.html";

    private WebView web;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean inPlayer = false;
    private boolean pairing  = false;
    private boolean checkedThisRun = false;  // vérif. de mise à jour faite depuis le lancement   // page d'appairage du serveur (player/pair.php)

    // Menu caché
    private long okDownAt = 0;
    private int cornerTaps = 0;
    private long firstTapAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("waf", MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        buildWebView();
        hideSystemUi();
        ui.postDelayed(updateTick, 3 * 60 * 1000L);   // 1re vérification 3 min après le lancement
        loadBoot(getIntent() != null && getIntent().getBooleanExtra("fromBoot", false) ? "#boot" : "");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Touche HOME quand l'appli est le lanceur : on reste sur l'écran en cours
        hideSystemUi();
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void buildWebView() {
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0d0b0b"));
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setKeepScreenOn(true);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setUserAgentString(s.getUserAgentString() + " WAFSignageApp/" + BuildConfig.VERSION_NAME);

        web.addJavascriptInterface(new Bridge(), "WafApp");
        web.setWebChromeClient(new WebChromeClient() {
            @Override public Bitmap getDefaultVideoPoster() {
                // Évite l'icône « play » grise avant le démarrage des vidéos
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
            @Override public boolean onConsoleMessage(ConsoleMessage m) { return true; }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return catchPaired(r.getUrl().toString()); }
            @Override public boolean shouldOverrideUrlLoading(WebView v, String url) { return catchPaired(url); }
            @Override public void onPageStarted(WebView v, String url, Bitmap fav) { catchPaired(url); }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
                if (Build.VERSION.SDK_INT >= 21 && req.isForMainFrame()) onPlayerFailed();
            }
            @Override @SuppressWarnings("deprecation")
            public void onReceivedError(WebView v, int code, String desc, String url) {
                if (url != null && v.getUrl() != null && url.equals(v.getUrl())) onPlayerFailed();
            }
            @Override
            public void onReceivedHttpError(WebView v, WebResourceRequest req, WebResourceResponse res) {
                if (req.isForMainFrame() && res.getStatusCode() >= 500) onPlayerFailed();
            }
            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler h, SslError err) {
                // Vieux boîtiers : autorité Let's Encrypt inconnue du système.
                // On accepte uniquement pour notre propre serveur (contenu d'affichage).
                String host = err != null && err.getUrl() != null ? Uri.parse(err.getUrl()).getHost() : null;
                String ours = Uri.parse(BASE).getHost();
                if (host != null && host.equals(ours) && err.getPrimaryError() == SslError.SSL_UNTRUSTED) h.proceed();
                else { h.cancel(); onPlayerFailed(); }
            }
            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                // Le moteur web a planté (mémoire) : on repart d'une WebView neuve
                ui.post(MainActivity.this::rebuild);
                return true;
            }
        });
        setContentView(web);
        web.requestFocus();
    }

    // ── Mises à jour : au lancement puis chaque nuit (3 h – 5 h) ──
    private final Runnable updateTick = new Runnable() {
        @Override public void run() {
            java.util.Calendar c = java.util.Calendar.getInstance();
            String today = c.get(java.util.Calendar.YEAR) + "-" + c.get(java.util.Calendar.DAY_OF_YEAR);
            int h = c.get(java.util.Calendar.HOUR_OF_DAY);
            boolean first = !checkedThisRun;
            if (first || (h >= 3 && h < 5 && !today.equals(prefs.getString("auto_check_day", "")))) {
                checkedThisRun = true;
                prefs.edit().putString("auto_check_day", today).apply();
                Updater.run(MainActivity.this, false, null);
            }
            ui.postDelayed(this, 30 * 60 * 1000L);
        }
    };

    private void rebuild() {
        try { if (web != null) { ((android.view.ViewGroup) web.getParent()).removeView(web); web.destroy(); } } catch (Throwable ignored) {}
        buildWebView();
        hideSystemUi();
        String slug = prefs.getString("slug", "");
        if (!slug.isEmpty()) openPlayer(); else loadBoot("");
    }

    /**
     * Appairage : player/pair.php (système de code existant du serveur) se
     * redirige tout seul vers /player/?screen=<slug> une fois le code saisi
     * dans l'admin — on mémorise ce slug pour les prochains démarrages.
     */
    private boolean catchPaired(String url) {
        if (!pairing || url == null || !url.startsWith(BASE + "/player/")) return false;
        String slug = Uri.parse(url).getQueryParameter("screen");
        if (slug == null || slug.isEmpty()) return false;
        pairing = false;
        prefs.edit().putString("slug", slug).putString("label", "").apply();
        ui.post(() -> loadBoot("#paired"));
        return true;
    }

    void openPairing() {
        inPlayer = false;
        pairing = true;
        web.loadUrl(BASE + "/player/pair.php");
    }

    private void onPlayerFailed() {
        if (pairing) { pairing = false; ui.post(() -> loadBoot("#offline")); return; }
        if (!inPlayer) return;
        ui.post(() -> loadBoot("#offline"));
    }

    void loadBoot(String hash) {
        inPlayer = false;
        pairing = false;
        web.loadUrl(BOOT + hash);
    }

    void openPlayer() {
        String slug = prefs.getString("slug", "");
        if (slug.isEmpty()) { loadBoot(""); return; }
        inPlayer = true;
        web.loadUrl(BASE + "/player/?screen=" + Uri.encode(slug) + "&app=" + Uri.encode(BuildConfig.VERSION_NAME));
    }

    private void hideSystemUi() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); hideSystemUi(); }
    @Override protected void onPause()  { if (web != null) web.onPause(); super.onPause(); }

    // ── Touches télécommande ──────────────────────────────────────────
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (pairing) {
            // RETOUR / MENU sur la page du code → retour à la configuration
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_UP) loadBoot("#setup");
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (inPlayer) {
            if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_SETTINGS || k == KeyEvent.KEYCODE_INFO) {
                if (e.getAction() == KeyEvent.ACTION_UP) loadBoot("#menu");
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) okDownAt = System.currentTimeMillis();
                if (e.getAction() == KeyEvent.ACTION_UP && okDownAt > 0 && System.currentTimeMillis() - okDownAt > 1200) {
                    okDownAt = 0; loadBoot("#menu"); return true;
                }
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK) return true; // pas de sortie accidentelle
            return super.dispatchKeyEvent(e);
        }
        // Pages locales : RETOUR est géré par la page
        if (k == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) web.evaluateJavascript("window.appBack&&appBack()", null);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // Écran tactile : 5 tapotements en 3 s dans le coin haut-gauche → menu
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (inPlayer && ev.getAction() == MotionEvent.ACTION_DOWN) {
            float lim = 140 * getResources().getDisplayMetrics().density;
            if (ev.getX() < lim && ev.getY() < lim) {
                long now = System.currentTimeMillis();
                if (now - firstTapAt > 3000) { firstTapAt = now; cornerTaps = 0; }
                if (++cornerTaps >= 5) { cornerTaps = 0; loadBoot("#menu"); return true; }
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    // ── Pont JavaScript (boot.html → appli) ───────────────────────────
    class Bridge {
        @JavascriptInterface public String baseUrl()   { return BASE; }
        @JavascriptInterface public String version()   { return BuildConfig.VERSION_NAME; }
        @JavascriptInterface public String device()    { return Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE; }
        @JavascriptInterface public String slug()      { return prefs.getString("slug", ""); }
        @JavascriptInterface public String label()     { return prefs.getString("label", ""); }

        @JavascriptInterface public void setScreen(String slug, String label) {
            prefs.edit().putString("slug", slug == null ? "" : slug).putString("label", label == null ? "" : label).apply();
        }
        @JavascriptInterface public void clearScreen() { prefs.edit().remove("slug").remove("label").apply(); }
        @JavascriptInterface public void start()       { ui.post(MainActivity.this::openPlayer); }
        @JavascriptInterface public void pair()        { ui.post(MainActivity.this::openPairing); }
        @JavascriptInterface public void restart()     { ui.post(MainActivity.this::rebuild); }

        /**
         * Requête HTTP faite par l'appli (pas par la page locale) : pas de
         * souci de CORS ni d'origine file://. Réponse renvoyée à la page par
         * window.__http(id, codeHttp, contenu) — code 0 = réseau injoignable.
         */
        @JavascriptInterface public void http(final String id, final String url) {
            new Thread(() -> {
                int code = 0; String body;
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(12000);
                    c.setUseCaches(false);
                    c.setRequestProperty("Cache-Control", "no-cache");
                    c.setRequestProperty("User-Agent", "WAFSignageApp/" + BuildConfig.VERSION_NAME);
                    code = c.getResponseCode();
                    InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                    body = in != null ? read(in) : "";
                } catch (Throwable t) {
                    code = 0;
                    body = t.getClass().getSimpleName() + ": " + t.getMessage();
                } finally {
                    if (c != null) c.disconnect();
                }
                final String js = "window.__http&&__http(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(body) + ")";
                ui.post(() -> { if (web != null) web.evaluateJavascript(js, null); });
            }).start();
        }

        @JavascriptInterface public boolean online() {
            try {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo n = cm.getActiveNetworkInfo();
                return n != null && n.isConnected();
            } catch (Throwable t) { return true; }
        }

        /** Démarrage automatique possible ? (Android 10+ : permission « par-dessus les autres applis ») */
        @JavascriptInterface public boolean autostartOk() {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(MainActivity.this);
        }
        @JavascriptInterface public void openAutostart() {
            ui.post(() -> {
                Intent i = Build.VERSION.SDK_INT >= 23
                        ? new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))
                        : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                if (!tryStart(i)) tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            });
        }

        @JavascriptInterface public boolean isLauncher() {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            ResolveInfo r = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
            return r != null && r.activityInfo != null && getPackageName().equals(r.activityInfo.packageName);
        }
        @JavascriptInterface public void openLauncherSettings() {
            ui.post(() -> {
                if (!tryStart(new Intent(Settings.ACTION_HOME_SETTINGS)))
                    tryStart(new Intent(Settings.ACTION_SETTINGS));
            });
        }
        @JavascriptInterface public void openSettings() { ui.post(() -> tryStart(new Intent(Settings.ACTION_SETTINGS))); }

        // ── Commandes à distance (admin → Monitoring) ──
        /** Redémarre complètement l'appli (nouveau processus). */
        @JavascriptInterface public void appRestart() {
            ui.post(() -> {
                try {
                    Intent i = new Intent(MainActivity.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    int fl = android.app.PendingIntent.FLAG_CANCEL_CURRENT | (Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);
                    android.app.PendingIntent pi = android.app.PendingIntent.getActivity(MainActivity.this, 4242, i, fl);
                    android.app.AlarmManager am = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
                    am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 1500, pi);
                } catch (Throwable t) { rebuild(); return; }
                finishAffinity();
                ui.postDelayed(() -> System.exit(0), 300);
            });
        }
        /** Redémarrage du boîtier : seulement si l'appli est « propriétaire de l'appareil » (sinon Android l'interdit) → à défaut, redémarre l'appli. */
        @JavascriptInterface public void reboot() {
            try {
                android.app.admin.DevicePolicyManager dpm = (android.app.admin.DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
                if (Build.VERSION.SDK_INT >= 24 && dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                    dpm.reboot(new android.content.ComponentName(MainActivity.this, BootReceiver.class));
                    return;
                }
            } catch (Throwable ignored) {}
            appRestart();
        }
        /** Mise à jour lancée à distance : silencieuse si possible, sinon proposée dans le menu. */
        @JavascriptInterface public void updateNow() { Updater.run(MainActivity.this, false, null); }

        // ── Mises à jour (menu) ──
        @JavascriptInterface public String updateInfo() {
            try {
                return new org.json.JSONObject()
                    .put("current", BuildConfig.VERSION_NAME)
                    .put("latest", prefs.getString("latest_version", ""))
                    .put("newer", prefs.getInt("latest_code", 0) > BuildConfig.VERSION_CODE)
                    .put("pending", prefs.getBoolean("update_pending", false))
                    .put("error", prefs.getString("update_error", ""))
                    .toString();
            } catch (Throwable t) { return "{}"; }
        }
        @JavascriptInterface public void checkUpdate() {
            Updater.run(MainActivity.this, true, (state, detail) -> ui.post(() -> {
                if (web != null) web.evaluateJavascript("window.onUpdateState&&onUpdateState(" + JSONObject.quote(state) + "," + JSONObject.quote(detail) + ")", null);
            }));
        }
        /** Autorisation « Installer des applis inconnues » (Android 8+), nécessaire aux mises à jour. */
        @JavascriptInterface public boolean updatesOk() {
            return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
        }
        @JavascriptInterface public void openUpdatesPermission() {
            ui.post(() -> {
                if (Build.VERSION.SDK_INT >= 26 && !tryStart(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))))
                    tryStart(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            });
        }
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    private boolean tryStart(Intent i) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return true; }
        catch (ActivityNotFoundException | SecurityException e) { return false; }
    }

    @Override protected void onDestroy() {
        ui.removeCallbacks(updateTick);
        if (web != null) web.destroy();
        super.onDestroy();
    }
}

package fr.wafforme.signage;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.RelativeLayout;

public class MainActivity extends Activity {

    private static final String TAG     = "WAFSignage";
    private static final String URL     = BuildConfig.SIGNAGE_URL;
    private static final long   RELOAD_OFFLINE_MS = 30_000;  // retry si offline
    private static final long   RELOAD_ERROR_MS   = 15_000;  // retry si erreur HTTP

    private WebView  webView;
    private Handler  handler = new Handler();
    private Runnable retryRunnable;
    private boolean  loaded = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen — pas de barre de statut, pas de barre nav
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );

        // Layout
        RelativeLayout root = new RelativeLayout(this);
        root.setBackgroundColor(0xFF0D0B0B); // --black WAF
        setContentView(root);

        // WebView
        webView = new WebView(this);
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        );
        root.addView(webView, lp);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
                cancelRetry();
                Log.d(TAG, "Page chargée : " + url);
            }
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.w(TAG, "Erreur " + errorCode + " — retry dans " + (RELOAD_ERROR_MS/1000) + "s");
                scheduleRetry(RELOAD_ERROR_MS);
            }
        });

        webView.setWebChromeClient(new WebChromeClient());

        loadSignage();
    }

    private void loadSignage() {
        if (isOnline()) {
            webView.loadUrl(URL);
        } else {
            Log.w(TAG, "Pas de réseau — retry dans " + (RELOAD_OFFLINE_MS/1000) + "s");
            scheduleRetry(RELOAD_OFFLINE_MS);
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
        return ni != null && ni.isConnected();
    }

    private void scheduleRetry(long delayMs) {
        cancelRetry();
        retryRunnable = () -> loadSignage();
        handler.postDelayed(retryRunnable, delayMs);
    }

    private void cancelRetry() {
        if (retryRunnable != null) {
            handler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
    }

    @Override
    public void onBackPressed() {
        // Bloquer le bouton retour en mode kiosk
        if (webView.canGoBack()) webView.goBack();
        // sinon : rien (pas de sortie)
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        // Rétablir immersive si focus perdu
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        webView.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelRetry();
        webView.destroy();
        super.onDestroy();
    }
}

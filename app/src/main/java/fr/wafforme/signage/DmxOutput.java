package fr.wafforme.signage;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;


/**
 * Lumières du club, calculées par la box :
 *  - lyres DMX512 par adaptateur USB « Open DMX » (puce FTDI FT232R) ;
 *  - ruban LED Bluetooth (BleStrip), qui suit les mêmes ambiances.
 *
 * L'adaptateur n'a pas de processeur : c'est l'appli qui fabrique la trame
 * DMX en continu (≈ 30 fois / s) : BREAK, puis 1 octet de départ (0) et les
 * 512 canaux, à 250 000 bauds, 8 bits, 2 bits de stop, sans parité.
 * Pilotage direct de la puce FTDI (requêtes de contrôle USB), sans bibliothèque.
 *
 * La page du player envoie l'ambiance voulue (WafApp.dmxSet) : une scène, ou
 * une séquence {"seq":[{program, strip, dur, fade}, …]} jouée en boucle.
 * On passe en fondu d'une ambiance / d'une étape à l'autre.
 */
class DmxOutput {
    private static final int FTDI_VID = 0x0403;
    private static final String ACTION_PERM = "fr.wafforme.signage.USB_PERMISSION";

    // Requêtes FTDI (protocole SIO)
    private static final int REQ_RESET = 0, REQ_FLOW = 2, REQ_BAUD = 3, REQ_DATA = 4;
    private static final int DATA_8N2 = 8 | (2 << 11);       // 8 bits, sans parité, 2 stop
    private static final int BREAK_ON = 1 << 14;

    private final Context ctx;
    private final UsbManager usb;
    private final BleStrip strip;
    private UsbDeviceConnection conn;
    private UsbEndpoint out;
    private int iface = 0;
    private Thread loop;
    private volatile boolean running = false;
    private volatile String status = "aucun adaptateur";

    /** Une étape d'ambiance : lyres + ruban, durée et fondu d'entrée. */
    private static final class Step {
        DmxProgram p; DmxProgram s; boolean sOff; long dur, fade;
    }

    private final byte[] frame = new byte[513];              // [0] = code de départ
    private final float[] from = new float[513];             // valeurs au moment du changement
    private final float[] cur  = new float[513];             // dernières valeurs envoyées
    private Step[] steps = {step(null, null, 60000, 0)};
    private long total = 60000, seqStart = 0;
    private int curStep = 0;
    private long fadeStart = 0, fadeMs = 0;
    private final float[] sCur = new float[3];               // couleur actuelle du ruban (lissée)
    private final Object lock = new Object();

    DmxOutput(Context c, BleStrip.PermissionAsker asker) {
        ctx = c.getApplicationContext();
        usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        strip = new BleStrip(ctx, asker);
        IntentFilter f = new IntentFilter(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
        else ctx.registerReceiver(rx, f);
    }

    /** Statut affiché dans l'admin : « ok » ou le problème de l'adaptateur DMX, + le ruban s'il est utilisé. */
    String status() { return strip.used() ? status + " · ruban : " + strip.status() : status; }

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) { closeUsb(); status = "adaptateur débranché"; }
            else connect();   // branché, ou autorisation accordée
        }
    };

    /** Cherche l'adaptateur FTDI et l'ouvre (demande l'autorisation si besoin). */
    synchronized void connect() {
        if (conn != null) return;
        try {
            UsbDevice dev = null;
            for (UsbDevice d : usb.getDeviceList().values()) if (d.getVendorId() == FTDI_VID) { dev = d; break; }
            if (dev == null) { status = "aucun adaptateur"; return; }
            if (!usb.hasPermission(dev)) {
                status = "autorisation USB demandée (valider sur l'écran)";
                int fl = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                Intent pi = new Intent(ACTION_PERM).setPackage(ctx.getPackageName());
                usb.requestPermission(dev, PendingIntent.getBroadcast(ctx, 0, pi, fl));
                return;
            }
            UsbInterface itf = dev.getInterface(0);
            UsbEndpoint ep = null;
            for (int k = 0; k < itf.getEndpointCount(); k++) {
                UsbEndpoint e = itf.getEndpoint(k);
                if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && e.getDirection() == UsbConstants.USB_DIR_OUT) ep = e;
            }
            UsbDeviceConnection c = usb.openDevice(dev);
            if (c == null || ep == null) { status = "adaptateur illisible"; return; }
            if (!c.claimInterface(itf, true)) { c.close(); status = "adaptateur occupé"; return; }
            iface = itf.getId() + 1;  // index FTDI (port A = 1)
            ctl(c, REQ_RESET, 0);                 // remise à zéro
            ctl(c, REQ_FLOW, 0);                  // pas de contrôle de flux
            ctl(c, REQ_BAUD, 12);                 // 3 MHz / 12 = 250 000 bauds
            ctl(c, REQ_DATA, DATA_8N2);
            conn = c; out = ep;
            status = "ok";
            start();
        } catch (Throwable t) { status = "erreur : " + t.getMessage(); closeUsb(); }
    }

    private int ctl(UsbDeviceConnection c, int req, int val) {
        return c.controlTransfer(0x40, req, val, iface, null, 0, 1000);
    }

    /** Boucle de calcul (≈ 30 fois / s) : tourne dès qu'il y a une ambiance, avec ou sans adaptateur DMX. */
    private synchronized void start() {
        if (running) return;
        running = true;
        loop = new Thread(() -> {
            while (running) {
                long t0 = System.currentTimeMillis();
                try { tick(); } catch (Throwable e) { status = "erreur d'envoi"; closeUsb(); }
                long dt = System.currentTimeMillis() - t0;
                try { Thread.sleep(Math.max(5, 33 - dt)); } catch (InterruptedException ie) { break; }
            }
        }, "waf-dmx");
        loop.setPriority(Thread.MAX_PRIORITY);
        loop.start();
    }

    private void tick() {
        Step st;
        synchronized (lock) { st = computeFrame(); }
        // Ruban LED (canaux virtuels 1-3 = rouge, vert, bleu ; 4 = intensité)
        if (strip.used()) {
            if (st.sOff || st.s == null) strip.push(0, 0, 0, false);
            else {
                float[] v = st.s.compute(System.currentTimeMillis());
                float k = v[4] / 255f;
                for (int i = 0; i < 3; i++) sCur[i] += (v[i + 1] * k - sCur[i]) * 0.25f;   // lissage : pas d'à-coups
                strip.push(Math.round(sCur[0]), Math.round(sCur[1]), Math.round(sCur[2]), true);
            }
        }
        // Lyres
        UsbDeviceConnection c = conn; UsbEndpoint e = out;
        if (c == null || e == null) return;
        // BREAK (≥ 88 µs) puis MARK AFTER BREAK, puis la trame
        ctl(c, REQ_DATA, DATA_8N2 | BREAK_ON);
        try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        ctl(c, REQ_DATA, DATA_8N2);
        c.bulkTransfer(e, frame, frame.length, 1000);
    }

    /** Valeurs du moment : étape en cours (séquence) + fondu depuis l'ambiance précédente. */
    private Step computeFrame() {
        long now = System.currentTimeMillis();
        int idx = 0;
        if (steps.length > 1 && total > 0) {
            long t = (now - seqStart) % total, acc = 0;
            for (int i = 0; i < steps.length; i++) { acc += steps[i].dur; if (t < acc) { idx = i; break; } }
            if (idx != curStep) {                                   // étape suivante : fondu depuis l'affiché
                curStep = idx;
                System.arraycopy(cur, 0, from, 0, cur.length);
                fadeStart = now; fadeMs = steps[idx].fade;
            }
        }
        Step st = steps[idx];
        float[] target = st.p.compute(now);
        float p = fadeMs <= 0 ? 1f : Math.min(1f, (now - fadeStart) / (float) fadeMs);
        frame[0] = 0;
        for (int i = 1; i <= 512; i++) {
            cur[i] = from[i] + (target[i] - from[i]) * p;
            frame[i] = (byte) Math.round(Math.max(0, Math.min(255, cur[i])));
        }
        return st;
    }

    private static Step step(JSONObject program, JSONObject strip, long dur, long fade) {
        Step s = new Step();
        s.p = new DmxProgram(program);
        s.sOff = strip != null && strip.optBoolean("off", false);
        s.s = strip != null && !s.sOff ? new DmxProgram(strip) : null;
        s.dur = Math.max(1000, dur);
        s.fade = Math.max(0, Math.min(30000, fade));
        return s;
    }

    /**
     * Nouvelle ambiance : JSON simple {"1":255,…}, programme animé (voir DmxProgram),
     * ou séquence {"seq":[{"program":{…},"strip":{…},"dur":60000,"fade":2000},…]}.
     * "strip" : réglages du ruban LED ({"off":true} = éteint ; absent = ruban non utilisé).
     */
    void set(String json, int fade) {
        JSONObject o;
        try { o = new JSONObject(json == null ? "{}" : json); } catch (Throwable ignored) { return; }
        Step[] ns;
        JSONArray seq = o.optJSONArray("seq");
        JSONObject stripCfg = o.optJSONObject("strip");
        if (seq != null && seq.length() > 0) {
            java.util.ArrayList<Step> l = new java.util.ArrayList<>();
            for (int i = 0; i < seq.length(); i++) {
                JSONObject it = seq.optJSONObject(i);
                if (it == null) continue;
                JSONObject pr = it.optJSONObject("program");
                JSONObject so = it.optJSONObject("strip");
                if (stripCfg == null && so != null) stripCfg = so;
                l.add(step(pr != null ? pr : new JSONObject(), so, it.optLong("dur", 60000), it.optLong("fade", 2000)));
            }
            ns = l.isEmpty() ? new Step[]{step(null, null, 60000, 0)} : l.toArray(new Step[0]);
        } else {
            ns = new Step[]{step(o, stripCfg, 60000, 0)};
        }
        long tot = 0; for (Step s : ns) tot += s.dur;
        synchronized (lock) {
            System.arraycopy(cur, 0, from, 0, cur.length);   // on part de ce qui est affiché
            steps = ns; total = tot; curStep = 0;
            seqStart = System.currentTimeMillis();
            fadeMs = Math.max(0, Math.min(30000, fade));
            fadeStart = seqStart;
        }
        strip.configure(stripCfg != null, stripCfg != null ? stripCfg.optString("name", "") : "", stripCfg != null ? stripCfg.optInt("bright", 100) : 100);
        if (conn == null) connect();
        start();
    }

    private synchronized void closeUsb() {
        try { if (conn != null) conn.close(); } catch (Throwable ignored) {}
        conn = null; out = null;
    }

    synchronized void close() {
        running = false;
        if (loop != null) loop.interrupt();
        loop = null;
        closeUsb();
    }

    void destroy() {
        close();
        strip.disconnect();
        try { ctx.unregisterReceiver(rx); } catch (Throwable ignored) {}
    }
}

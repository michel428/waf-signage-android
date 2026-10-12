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

import org.json.JSONObject;


/**
 * Sortie DMX512 par adaptateur USB « Open DMX » (puce FTDI FT232R, ex.
 * Enttec Open DMX USB) branché sur la box.
 *
 * L'adaptateur n'a pas de processeur : c'est l'appli qui fabrique la trame
 * DMX en continu (≈ 30 fois / s) : BREAK, puis 1 octet de départ (0) et les
 * 512 canaux, à 250 000 bauds, 8 bits, 2 bits de stop, sans parité.
 * Pilotage direct de la puce FTDI (requêtes de contrôle USB), sans bibliothèque.
 *
 * La page du player envoie la « scène » voulue (WafApp.dmxSet) ; on passe
 * en fondu des valeurs actuelles aux nouvelles.
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
    private UsbDeviceConnection conn;
    private UsbEndpoint out;
    private int iface = 0;
    private Thread loop;
    private volatile boolean running = false;
    private volatile String status = "aucun adaptateur";

    private final byte[] frame = new byte[513];              // [0] = code de départ
    private final float[] from = new float[513];             // valeurs au moment du changement
    private final float[] cur  = new float[513];             // dernières valeurs envoyées
    private DmxProgram prog = new DmxProgram(null);          // ambiance en cours (statique ou animée)
    private volatile long fadeStart = 0, fadeMs = 0;
    private final Object lock = new Object();

    DmxOutput(Context c) {
        ctx = c.getApplicationContext();
        usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        IntentFilter f = new IntentFilter(ACTION_PERM);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
        else ctx.registerReceiver(rx, f);
    }

    String status() { return status; }

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) { close(); status = "adaptateur débranché"; }
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
        } catch (Throwable t) { status = "erreur : " + t.getMessage(); close(); }
    }

    private int ctl(UsbDeviceConnection c, int req, int val) {
        return c.controlTransfer(0x40, req, val, iface, null, 0, 1000);
    }

    private void start() {
        if (running) return;
        running = true;
        loop = new Thread(() -> {
            while (running) {
                long t0 = System.currentTimeMillis();
                try { sendFrame(); } catch (Throwable e) { status = "erreur d'envoi"; close(); break; }
                long dt = System.currentTimeMillis() - t0;
                try { Thread.sleep(Math.max(5, 33 - dt)); } catch (InterruptedException ie) { break; }
            }
        }, "waf-dmx");
        loop.setPriority(Thread.MAX_PRIORITY);
        loop.start();
    }

    private void sendFrame() {
        UsbDeviceConnection c = conn; UsbEndpoint e = out;
        if (c == null || e == null) return;
        // Valeurs du moment (fondu linéaire)
        synchronized (lock) { computeFrame(); }
        // BREAK (≥ 88 µs) puis MARK AFTER BREAK, puis la trame
        ctl(c, REQ_DATA, DATA_8N2 | BREAK_ON);
        try { Thread.sleep(1); } catch (InterruptedException ignored) {}
        ctl(c, REQ_DATA, DATA_8N2);
        c.bulkTransfer(e, frame, frame.length, 1000);
    }

    /** Valeurs du moment : programme (animé) + fondu depuis l'ambiance précédente. */
    private void computeFrame() {
        long now = System.currentTimeMillis();
        float[] target = prog.compute(now);
        float p = fadeMs <= 0 ? 1f : Math.min(1f, (now - fadeStart) / (float) fadeMs);
        frame[0] = 0;
        for (int i = 1; i <= 512; i++) {
            cur[i] = from[i] + (target[i] - from[i]) * p;
            frame[i] = (byte) Math.round(Math.max(0, Math.min(255, cur[i])));
        }
    }

    /**
     * Nouvelle ambiance. JSON simple {"1":255,…} (canal → valeur) ou programme
     * animé {"base":{…},"fixtures":[…],"motion":{…},"color":{…}} (voir DmxProgram).
     */
    void set(String json, int fade) {
        DmxProgram np;
        try { np = new DmxProgram(new JSONObject(json == null ? "{}" : json)); }
        catch (Throwable ignored) { return; }
        synchronized (lock) {
            System.arraycopy(cur, 0, from, 0, cur.length);   // on part de ce qui est affiché
            prog = np;
            fadeMs = Math.max(0, Math.min(30000, fade));
            fadeStart = System.currentTimeMillis();
        }
        if (conn == null) connect();
    }

    synchronized void close() {
        running = false;
        if (loop != null) loop.interrupt();
        loop = null;
        try { if (conn != null) conn.close(); } catch (Throwable ignored) {}
        conn = null; out = null;
    }

    void destroy() {
        close();
        try { ctx.unregisterReceiver(rx); } catch (Throwable ignored) {}
    }
}

package fr.wafforme.signage;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.UUID;

/**
 * Ruban LED Bluetooth (contrôleurs de l'appli « Lotus Lantern X » / duoCo :
 * ELK-BLEDOM, ELK-BLEDOB, MELK…). Trames de 9 octets écrites sur la
 * caractéristique FFF3 du service FFF0 :
 *   allumer   7E 00 04 F0 00 01 FF 00 EF
 *   éteindre  7E 00 04 00 00 00 FF 00 EF
 *   couleur   7E 00 05 03 RR GG BB 00 EF
 *   lumière   7E 00 01 LL 00 00 00 00 EF   (0-100)
 *
 * La box trouve le ruban toute seule (nom commençant par ELK-, MELK, BLEDOM…
 * ou le nom réglé dans l'admin), s'y connecte et lui envoie ≈ 10 couleurs / s,
 * seulement quand la couleur change. Reconnexion automatique.
 */
@SuppressLint("MissingPermission")
class BleStrip {
    private static final UUID SVC  = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");
    private static final String[] PREFIXES = {"ELK-", "MELK", "BLEDOM", "LEDBLE", "ELK_", "LEDDMX", "Lotus", "LED-"};

    interface PermissionAsker { void ask(String[] perms); }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final PermissionAsker asker;
    private BluetoothAdapter adapter;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic ch;
    private volatile String status = "désactivé";
    private volatile boolean wanted = false, scanning = false, connecting = false, ready = false, busy = false;
    private volatile String filter = "";
    private long lastScan = 0, lastWrite = 0, lastSent = 0, lastPermAsk = 0, busySince = 0;
    private int sr = -1, sg = -1, sb = -1, bright = 100;
    private boolean sentOn = false, wantOn = true, powerSent = false;
    private String devName = "";

    BleStrip(Context c, PermissionAsker a) {
        ctx = c.getApplicationContext();
        asker = a;
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = bm != null ? bm.getAdapter() : null;
        } catch (Throwable t) { adapter = null; }
    }

    String status() { return status; }
    boolean used() { return wanted; }

    /** Réglages venant du serveur (null = ruban non utilisé). */
    void configure(boolean use, String name, int brightPct) {
        filter = name == null ? "" : name.trim();
        bright = Math.max(5, Math.min(100, brightPct));
        if (use == wanted) return;
        wanted = use;
        if (!use) { h.post(this::disconnect); status = "désactivé"; }
    }

    // ───────────────────────── Permissions ─────────────────────────

    private String[] missingPerms() {
        String[] need = Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : (Build.VERSION.SDK_INT >= 23 ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION} : new String[0]);
        java.util.ArrayList<String> miss = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 23) for (String p : need) if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p);
        return miss.toArray(new String[0]);
    }

    // ───────────────────────── Boucle (appelée ≈ 30 fois / s) ─────────────────────────

    /** Couleur voulue (0-255) ; on = false pour éteindre le ruban. */
    void push(int r, int g, int b, boolean on) {
        if (!wanted) return;
        long now = System.currentTimeMillis();
        if (!ready) { if (!scanning && !connecting && now - lastScan > 15000) h.post(this::startScan); return; }
        if (busy && now - busySince < 300) return;
        if (now - lastWrite < 90) return;                       // ≈ 10 envois / s maximum
        if (!powerSent || on != sentOn) {
            write(on ? new byte[]{0x7e, 0x00, 0x04, (byte) 0xf0, 0x00, 0x01, (byte) 0xff, 0x00, (byte) 0xef}
                     : new byte[]{0x7e, 0x00, 0x04, 0x00, 0x00, 0x00, (byte) 0xff, 0x00, (byte) 0xef});
            sentOn = on; powerSent = true; sr = sg = sb = -1;
            return;
        }
        if (!on) return;
        int k = bright;
        r = r * k / 100; g = g * k / 100; b = b * k / 100;
        boolean changed = Math.abs(r - sr) + Math.abs(g - sg) + Math.abs(b - sb) > 2;
        if (!changed && now - lastSent < 10000) return;         // rappel toutes les 10 s (au cas où)
        write(new byte[]{0x7e, 0x00, 0x05, 0x03, (byte) r, (byte) g, (byte) b, 0x00, (byte) 0xef});
        sr = r; sg = g; sb = b; lastSent = now;
    }

    private void write(byte[] frame) {
        BluetoothGatt gt = gatt; BluetoothGattCharacteristic c = ch;
        if (gt == null || c == null) return;
        try {
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            c.setValue(frame);
            busy = true; busySince = System.currentTimeMillis(); lastWrite = busySince;
            if (!gt.writeCharacteristic(c)) busy = false;
        } catch (Throwable t) { busy = false; }
    }

    // ───────────────────────── Recherche / connexion ─────────────────────────

    private void startScan() {
        if (!wanted || scanning || connecting || ready) return;
        lastScan = System.currentTimeMillis();
        if (adapter == null) { status = "pas de Bluetooth sur la box"; return; }
        String[] miss = missingPerms();
        if (miss.length > 0) {
            status = "autorisation Bluetooth à valider sur l'écran";
            if (asker != null && System.currentTimeMillis() - lastPermAsk > 120000) { lastPermAsk = System.currentTimeMillis(); asker.ask(miss); }
            return;
        }
        if (!adapter.isEnabled()) { status = "Bluetooth éteint sur la box"; try { adapter.enable(); } catch (Throwable ignored) {} return; }
        // Déjà appairé / vu avec le bon nom ?
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) if (matches(d.getName())) { connect(d); return; }
        } catch (Throwable ignored) {}
        BluetoothLeScanner sc = adapter.getBluetoothLeScanner();
        if (sc == null) { status = "Bluetooth indisponible"; return; }
        scanning = true;
        status = "recherche du ruban…";
        try {
            sc.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCb);
        } catch (Throwable t) { scanning = false; status = "recherche impossible"; return; }
        h.postDelayed(() -> { if (scanning) { stopScan(); if (!connecting && !ready) status = "ruban introuvable (allumé ? appli Lotus fermée ?)"; } }, 10000);
    }

    private void stopScan() {
        scanning = false;
        try { BluetoothLeScanner sc = adapter.getBluetoothLeScanner(); if (sc != null) sc.stopScan(scanCb); } catch (Throwable ignored) {}
    }

    private boolean matches(String name) {
        if (name == null || name.isEmpty()) return false;
        if (!filter.isEmpty()) return name.toLowerCase().startsWith(filter.toLowerCase()) || name.equalsIgnoreCase(filter);
        for (String p : PREFIXES) if (name.toUpperCase().startsWith(p.toUpperCase())) return true;
        return false;
    }

    private final ScanCallback scanCb = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) {
            BluetoothDevice d = r.getDevice();
            String n = null;
            try { n = r.getScanRecord() != null ? r.getScanRecord().getDeviceName() : null; } catch (Throwable ignored) {}
            if (n == null) try { n = d.getName(); } catch (Throwable ignored) {}
            if (scanning && matches(n)) { stopScan(); devName = n; h.post(() -> connect(d)); }
        }
        @Override public void onScanFailed(int code) { scanning = false; status = "recherche impossible (" + code + ")"; }
    };

    private void connect(BluetoothDevice d) {
        if (!wanted || connecting || ready) return;
        connecting = true;
        try { if (devName.isEmpty()) devName = d.getName() == null ? "ruban" : d.getName(); } catch (Throwable ignored) {}
        status = "connexion à " + devName + "…";
        try {
            gatt = Build.VERSION.SDK_INT >= 23 ? d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE) : d.connectGatt(ctx, false, gattCb);
        } catch (Throwable t) { connecting = false; status = "connexion impossible"; return; }
        h.postDelayed(() -> { if (connecting && !ready) { status = "le ruban ne répond pas"; disconnect(); } }, 15000);
    }

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int st, int ns) {
            if (ns == BluetoothProfile.STATE_CONNECTED) { try { g.discoverServices(); } catch (Throwable ignored) {} }
            else if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                boolean was = ready;
                h.post(() -> { closeGatt(); if (wanted) status = was ? "ruban déconnecté, reconnexion…" : status; lastScan = System.currentTimeMillis() - 12000; });
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
            BluetoothGattCharacteristic c = null;
            BluetoothGattService s = g.getService(SVC);
            if (s != null) c = s.getCharacteristic(CHAR);
            if (c == null) for (BluetoothGattService sv : g.getServices()) {   // variantes : 1re caractéristique inscriptible du service FFxx
                String u = sv.getUuid().toString();
                if (!u.startsWith("0000ff")) continue;
                for (BluetoothGattCharacteristic x : sv.getCharacteristics())
                    if ((x.getProperties() & (BluetoothGattCharacteristic.PROPERTY_WRITE | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) { c = x; break; }
                if (c != null) break;
            }
            if (c == null) { status = "appareil Bluetooth non compatible (" + devName + ")"; h.post(BleStrip.this::disconnect); return; }
            ch = c; connecting = false; ready = true; powerSent = false; busy = false;
            status = "connecté (" + devName + ")";
            // luminosité du ruban à fond : on règle l'intensité nous-mêmes sur la couleur
            write(new byte[]{0x7e, 0x00, 0x01, 0x64, 0x00, 0x00, 0x00, 0x00, (byte) 0xef});
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int st) { busy = false; }
    };

    private void closeGatt() {
        ready = false; connecting = false; ch = null; busy = false;
        try { if (gatt != null) { gatt.disconnect(); gatt.close(); } } catch (Throwable ignored) {}
        gatt = null;
    }

    void disconnect() {
        stopScan();
        if (gatt != null && ready) {   // on éteint le ruban en partant
            write(new byte[]{0x7e, 0x00, 0x04, 0x00, 0x00, 0x00, (byte) 0xff, 0x00, (byte) 0xef});
        }
        closeGatt();
    }
}

package fr.wafforme.signage;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Son de la musique reçu du PC musique (appli « WAF Lumières » sous Linux), sur le réseau local.
 * Paquets UDP (port 47712), ≈ 30 / s :  WAFA|1|niveau|basses|médiums|aigus|n° de temps|bpm
 * Valeurs déjà lissées par le PC (0 à 1). La box répond toutes les secondes à l'expéditeur
 * (WAFB|…) pour que le PC affiche « box reliée ».
 */
final class AudioLink {
    static final int PORT = 47712;

    volatile float lvl, bass, mid, high, bpm;
    volatile int beatSeq;
    volatile long lastAt;
    volatile String from = "";
    private volatile String reply = "";
    private volatile boolean running;
    private DatagramSocket sock;

    /** Texte renvoyé au PC (état des lumières). */
    void setReply(String r) { reply = r == null ? "" : r; }

    boolean fresh(long now) { return now - lastAt < 1500; }

    synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(() -> {
            byte[] buf = new byte[256];
            long lastReply = 0;
            while (running) {
                try {
                    if (sock == null) {
                        sock = new DatagramSocket(null);
                        sock.setReuseAddress(true);
                        sock.setBroadcast(true);
                        sock.bind(new InetSocketAddress(PORT));
                        sock.setSoTimeout(2000);
                    }
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try { sock.receive(p); } catch (java.net.SocketTimeoutException te) { continue; }
                    String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (!s.startsWith("WAFA|")) continue;
                    String[] a = s.split("\\|");
                    if (a.length < 8) continue;
                    lvl = f(a[2]); bass = f(a[3]); mid = f(a[4]); high = f(a[5]);
                    try { beatSeq = Integer.parseInt(a[6].trim()); } catch (NumberFormatException ignored) {}
                    bpm = f(a[7]);
                    long now = System.currentTimeMillis();
                    lastAt = now;
                    InetAddress src = p.getAddress();
                    from = src != null ? src.getHostAddress() : "";
                    if (now - lastReply > 1000 && src != null) {
                        lastReply = now;
                        byte[] r = ("WAFB|1|" + reply).getBytes(StandardCharsets.UTF_8);
                        try { sock.send(new DatagramPacket(r, r.length, src, p.getPort())); } catch (Throwable ignored) {}
                    }
                } catch (Throwable e) {
                    try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
                    sock = null;
                    try { Thread.sleep(3000); } catch (InterruptedException ie) { break; }
                }
            }
        }, "waf-audio");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        running = false;
        try { if (sock != null) sock.close(); } catch (Throwable ignored) {}
        sock = null;
    }

    private static float f(String s) {
        try { float v = Float.parseFloat(s.trim()); return Float.isNaN(v) ? 0 : v; } catch (NumberFormatException e) { return 0; }
    }
}

package fr.wafforme.signage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Ambiance lumineuse calculée en continu (≈ 30 fois / s) : valeurs fixes +
 * mouvements des lyres + animation des couleurs / de l'intensité. Reprend
 * l'esprit des fonctions Q Light Controller du club (EFX, chasers, matrices),
 * avec en plus des effets doux (feu, océan, comète, respiration…).
 * Aucun effet stroboscopique : toutes les variations d'intensité sont fondues.
 *
 * JSON reçu du serveur :
 * {
 *   "base":     {"1":0,"6":255,…},                        valeurs fixes (canal → 0-255)
 *   "fixtures": [{"pan":1,"tilt":3,"dim":6,"r":7,"g":8,"b":9,"w":10},…]   canaux absolus (0 = absent)
 *   "motion":   {"shapes":["circle"],"period":20000,"every":30000,"size":127,"cx":127,"cy":127,
 *                "offsets":[0,180,180,0],"flip":[1,-1,-1,1],"fan":0},
 *   "color":    {"mode":"chase","palette":[[255,0,0,0],…],"step":10000,"spread":true}
 * }
 * Un JSON « plat » {"1":255,…} est accepté tel quel (scène statique).
 */
final class DmxProgram {
    private final float[] base = new float[513];
    private int[][] fx = new int[0][];          // par lyre : pan, tilt, dim, r, g, b, w
    private String[] shapes = null;
    private long period = 20000, every = 30000;
    private float size = 127, cx = 127, cy = 127, fan = 0;
    private float[] offs = new float[0];
    private float[] flip = new float[0];
    private String cmode = null;
    private int[][] palette = new int[0][];
    private long step = 10000;
    private boolean spread = true;
    private final float[] out = new float[513];
    final byte[] curve = new byte[513];         // protections à la sortie (voir DmxOutput.safe)

    private static final int[][] FIRE = {{255, 30, 0, 0}, {255, 80, 0, 0}, {255, 130, 0, 0}, {255, 60, 0, 0}};

    DmxProgram(JSONObject o) {
        if (o == null) return;
        JSONObject b = o.has("base") ? o.optJSONObject("base") : o;   // format plat = base seule
        if (b != null) {
            Iterator<String> it = b.keys();
            while (it.hasNext()) {
                String k = it.next();
                try { int ch = Integer.parseInt(k); if (ch >= 1 && ch <= 512) base[ch] = clamp(b.optInt(k, 0)); } catch (NumberFormatException ignored) {}
            }
        }
        JSONObject cv = o.optJSONObject("curves");
        if (cv != null) {
            Iterator<String> it = cv.keys();
            while (it.hasNext()) {
                String k = it.next();
                try { int ch = Integer.parseInt(k); if (ch >= 1 && ch <= 512) curve[ch] = (byte) cv.optInt(k, 0); } catch (NumberFormatException ignored) {}
            }
        }
        JSONArray f = o.optJSONArray("fixtures");
        if (f != null) {
            fx = new int[f.length()][];
            for (int i = 0; i < f.length(); i++) {
                JSONObject x = f.optJSONObject(i);
                if (x == null) x = new JSONObject();
                fx[i] = new int[]{x.optInt("pan"), x.optInt("tilt"), x.optInt("dim"), x.optInt("r"), x.optInt("g"), x.optInt("b"), x.optInt("w")};
            }
        }
        JSONObject m = o.optJSONObject("motion");
        if (m != null) {
            JSONArray s = m.optJSONArray("shapes");
            if (s != null && s.length() > 0) { shapes = new String[s.length()]; for (int i = 0; i < s.length(); i++) shapes[i] = s.optString(i, "circle"); }
            period = Math.max(1000, m.optLong("period", 20000));
            every = Math.max(2000, m.optLong("every", 30000));
            size = (float) m.optDouble("size", 127);
            cx = (float) m.optDouble("cx", 127);
            cy = (float) m.optDouble("cy", 127);
            fan = (float) m.optDouble("fan", 0);
            JSONArray of = m.optJSONArray("offsets");
            if (of != null) { offs = new float[of.length()]; for (int i = 0; i < of.length(); i++) offs[i] = (float) Math.toRadians(of.optDouble(i, 0)); }
            JSONArray fl = m.optJSONArray("flip");
            if (fl != null) { flip = new float[fl.length()]; for (int i = 0; i < fl.length(); i++) flip[i] = fl.optDouble(i, 1) < 0 ? -1f : 1f; }
        }
        JSONObject c = o.optJSONObject("color");
        if (c != null) {
            cmode = c.optString("mode", "static");
            step = Math.max(300, c.optLong("step", 10000));
            spread = c.optBoolean("spread", true);
            JSONArray p = c.optJSONArray("palette");
            if (p != null) {
                palette = new int[p.length()][];
                for (int i = 0; i < p.length(); i++) {
                    JSONArray q = p.optJSONArray(i);
                    palette[i] = new int[]{q != null ? q.optInt(0) : 0, q != null ? q.optInt(1) : 0, q != null ? q.optInt(2) : 0, q != null ? q.optInt(3) : 0};
                }
            }
        }
    }

    private static float clamp(double v) { return (float) Math.max(0, Math.min(255, v)); }

    float[] compute(long now) {
        System.arraycopy(base, 0, out, 0, out.length);
        int n = fx.length;
        // ── Mouvements ──
        if (shapes != null && n > 0) {
            String shape = shapes[(int) ((now / every) % shapes.length)];
            double t0 = 2 * Math.PI * (now % period) / (double) period;
            double a0 = 2 * Math.PI * ((now % (period * 1000L)) / (double) period);   // temps continu (formes libres)
            for (int i = 0; i < n; i++) {
                double o = i < offs.length ? offs[i] : 0;
                double[] xy = shape(shape, t0 + o, a0 + o * 3.7);
                float sx = i < flip.length ? flip[i] : 1f;
                double fo = fan * (i - (n - 1) / 2.0);                  // éventail : chaque lyre vise un endroit différent
                set(fx[i][0], cx + fo + size * xy[0] * sx);
                set(fx[i][1], cy + size * xy[1]);
            }
        }
        // ── Couleurs / intensité ──
        if (cmode != null && n > 0) {
            for (int i = 0; i < n; i++) {
                float[] c = color(now, i, n);
                if (c == null) continue;
                float k = 1f;
                if (c.length > 4) {
                    if (fx[i][2] > 0) set(fx[i][2], c[4]);
                    else k = c[4] / 255f;                                // pas de canal intensité : on assombrit la couleur
                }
                set(fx[i][3], c[0] * k); set(fx[i][4], c[1] * k); set(fx[i][5], c[2] * k); set(fx[i][6], c[3] * k);
            }
        }
        return out;
    }

    private void set(int ch, double v) { if (ch >= 1 && ch <= 512) out[ch] = clamp(v); }

    // ────────────────────────── Mouvements ──────────────────────────

    /** Formes des mouvements, x et y dans [-1 ; 1]. t = phase du tour (0-2π), a = temps continu. */
    static double[] shape(String s, double t, double a) {
        double sn = Math.sin(t), cs = Math.cos(t);
        double u = ((t / (2 * Math.PI)) % 1.0 + 1.0) % 1.0;
        switch (s) {
            case "eight":     return new double[]{sn, Math.sin(2 * t)};
            case "line":      return new double[]{cs, cs};
            case "line2":     return new double[]{cs, 0};
            case "tilt":      return new double[]{0, sn};
            case "diamond":   return new double[]{Math.pow(sn, 3), Math.pow(cs, 3)};
            case "square": {
                double v = u * 4; int k = (int) v; double f = v - k;
                switch (k) { case 0: return new double[]{-1 + 2 * f, -1}; case 1: return new double[]{1, -1 + 2 * f}; case 2: return new double[]{1 - 2 * f, 1}; default: return new double[]{-1, 1 - 2 * f}; }
            }
            case "squarechoppy": {
                int k = (int) (u * 4);
                return new double[]{k == 1 || k == 2 ? 1 : -1, k >= 2 ? 1 : -1};
            }
            case "leaf":      return new double[]{Math.pow(sn, 5), cs * Math.abs(cs)};
            case "lissajous": return new double[]{Math.sin(2 * t + Math.PI / 2), Math.sin(3 * t)};
            case "pendulum":  return new double[]{sn, 0.7 * sn * sn - 0.35};
            case "triangle":  return poly(TRI, u);
            case "star":      return poly(STAR, u);
            case "spiral": {
                double r = 0.5 - 0.5 * Math.cos(t);
                return new double[]{r * Math.cos(4 * t), r * Math.sin(4 * t)};
            }
            case "flower": {
                double r = Math.cos(3 * t);
                return new double[]{r * cs, r * sn};
            }
            case "clover": {
                double r = Math.cos(2 * t);
                return new double[]{r * cs, r * sn};
            }
            case "orbit":     return new double[]{0.7 * cs + 0.3 * Math.cos(5 * t), 0.7 * sn + 0.3 * Math.sin(5 * t)};
            case "zigzag":    return new double[]{tri(u * 5), tri(u)};
            case "bounce":    return new double[]{cs, 1 - 2 * Math.abs(Math.sin(3 * t))};
            case "cross": {
                double v = Math.sin(4 * Math.PI * u);
                return u < 0.5 ? new double[]{v, 0} : new double[]{0, v};
            }
            case "scan":      return new double[]{tri(u), 0.15 * Math.sin(6 * t)};
            case "ballyhoo":  return new double[]{0.6 * Math.sin(a) + 0.4 * Math.sin(2.31 * a + 1.3), 0.6 * Math.cos(1.37 * a) + 0.4 * Math.sin(3.1 * a + 0.4)};
            case "random":    return new double[]{2 * noise(a * 0.6) - 1, 2 * noise(a * 0.6 + 71.3) - 1};
            case "circle":
            default:          return new double[]{cs, sn};
        }
    }

    private static final double[] TRI = {0, -1, 0.87, 0.5, -0.87, 0.5};
    private static final double[] STAR = star();

    private static double[] star() {
        double[] p = new double[20];
        for (int k = 0; k < 10; k++) {
            double r = k % 2 == 0 ? 1 : 0.4, ang = -Math.PI / 2 + k * Math.PI / 5;
            p[2 * k] = r * Math.cos(ang); p[2 * k + 1] = r * Math.sin(ang);
        }
        return p;
    }

    /** Parcours d'un polygone fermé (points x,y à la suite), u dans [0 ; 1). */
    private static double[] poly(double[] p, double u) {
        int n = p.length / 2;
        double v = u * n; int k = ((int) Math.floor(v)) % n; double f = v - Math.floor(v);
        int j = (k + 1) % n;
        return new double[]{p[2 * k] + (p[2 * j] - p[2 * k]) * f, p[2 * k + 1] + (p[2 * j + 1] - p[2 * k + 1]) * f};
    }

    /** Onde triangle [-1 ; 1] de période 1. */
    private static double tri(double x) { double f = x - Math.floor(x); return f < 0.5 ? -1 + 4 * f : 3 - 4 * f; }

    // ────────────────────────── Couleurs ──────────────────────────

    /** Couleur d'une lyre : {r, g, b, w} (+ intensité optionnelle). */
    float[] color(long now, int i, int n) {
        if (!"mix".equals(cmode)) return color(cmode, now, i, n);
        // Mix : enchaîne plusieurs animations (comme le chaser du club), avec un fondu d'1,5 s entre deux
        String[] seq = {"rainbow", "fade", "comet", "wave", "gradient", "breathe", "chase", "bounce", "drift"};
        long len = step * 2, k = now / len;
        float[] cur = color(seq[(int) (k % seq.length)], now, i, n);
        long into = now % len;
        if (cur != null && cur.length == 4) cur = new float[]{cur[0], cur[1], cur[2], cur[3], 255f};
        if (into >= 1500 || k == 0 || cur == null) return cur;
        float[] prev = color(seq[(int) ((k - 1) % seq.length)], now, i, n);
        if (prev == null) return cur;
        double f = smooth(into / 1500.0);
        float[] r = new float[5];
        for (int j = 0; j < 5; j++) {
            float a = j < prev.length ? prev[j] : 255f, b = j < cur.length ? cur[j] : 255f;
            r[j] = (float) (a + (b - a) * f);
        }
        return r;
    }

    private float[] color(String mode, long now, int i, int n) {
        int pl = palette.length;
        double ts = (now % (step * 100000L)) / (double) step;   // nombre de « pas » écoulés
        switch (mode) {
            case "rainbow": {
                double h = ((now % (step * 2)) / (double) (step * 2)) + (spread ? i / (double) n : 0);
                return hsv(h % 1.0);
            }
            case "wave": {                            // la couleur « passe » d'une lyre à l'autre
                if (pl == 0) return null;
                int[] c = palette[(int) ((now / step) % pl)];
                double ph = 2 * Math.PI * ((now % 2000) / 2000.0) - (spread ? i * Math.PI / 2 : 0);
                float d = (float) (127.5 + 127.5 * Math.sin(ph));
                return new float[]{c[0], c[1], c[2], c[3], d};
            }
            case "random": {                          // couleurs au hasard, en fondu (jamais de saut brutal)
                long per = Math.max(1500, step / 2);
                long k = now / per + (spread ? i * 7919L : 0);
                double f = smooth(Math.min(1, (now % per) / 800.0));
                float[] a = hsv(((k * 2654435761L) & 0xffff) / 65536.0), b = hsv((((k + 1) * 2654435761L) & 0xffff) / 65536.0);
                return new float[]{a[0] + (b[0] - a[0]) * (float) f, a[1] + (b[1] - a[1]) * (float) f, a[2] + (b[2] - a[2]) * (float) f, 0};
            }
            case "pulse": {
                if (pl == 0) return null;
                int[] c = palette[0];
                float d = (float) (127.5 + 127.5 * Math.sin(2 * Math.PI * (now % step) / step - (spread ? i * 0.6 : 0)));
                return new float[]{c[0], c[1], c[2], c[3], d};
            }
            case "chase": {                           // les couleurs tournent, avec un court fondu (pas de flash)
                if (pl == 0) return null;
                long k = now / step + (spread ? i : 0);
                double f = smooth(Math.min(1, (now % step) / 400.0));
                return lerp(palette[(int) ((k - 1 + pl) % pl)], palette[(int) (k % pl)], f);
            }
            case "fade": {                            // fondu enchaîné d'une couleur à l'autre
                if (pl == 0) return null;
                return palAt(palette, ts + (spread ? i : 0), true);
            }
            case "gradient": {                        // dégradé de la palette réparti sur les lyres, qui défile
                if (pl == 0) return null;
                return palAt(palette, ts + i * pl / (double) n, false);
            }
            case "split": {                           // deux groupes de lyres qui échangent leurs couleurs
                if (pl == 0) return null;
                long k = (long) Math.floor(ts);
                double f = ts - k;
                double sw = smooth(Math.min(1, f * step / 1200.0));      // échange en 1,2 s
                int g = i % 2;
                return lerp(palette[(int) ((k + g) % pl)], palette[(int) ((k + 1 + g) % pl)], sw);
            }
            case "alternate": {                       // une lyre sur deux, en fondu (pas de flash)
                if (pl == 0) return null;
                double on = 0.5 + 0.5 * Math.cos(Math.PI * ts + (i % 2) * Math.PI);
                int[] c = palette[(int) (((long) (ts / 2)) % pl)];
                return new float[]{c[0], c[1], c[2], c[3], (float) (25 + 230 * on)};
            }
            case "comet": {                           // une lyre s'allume, les autres gardent une traînée
                if (pl == 0) return null;
                double pos = (ts - Math.floor(ts)) * n;
                double dd = ((pos - i) % n + n) % n;                     // distance derrière la tête
                double v = dd > n - 0.6 ? 1 - (n - dd) / 0.6             // montée douce juste avant
                         : Math.max(0, 1 - dd / 1.6);                    // traînée
                if (n < 3) v = 0.5 + 0.5 * Math.cos(2 * Math.PI * dd / n);
                int[] c = palette[(int) (((long) ts) % pl)];
                return new float[]{c[0], c[1], c[2], c[3], (float) (10 + 245 * v * v)};
            }
            case "bounce": {                          // la lumière fait des allers-retours
                if (pl == 0) return null;
                double pos = n > 1 ? (tri(ts / 2.0) + 1) / 2 * (n - 1) : 0;
                double v = Math.max(0, 1 - Math.abs(pos - i) / 1.2);
                int[] c = palette[(int) (((long) (ts / 2)) % pl)];
                return new float[]{c[0], c[1], c[2], c[3], (float) (10 + 245 * v)};
            }
            case "breathe": {                         // respiration lente, la couleur change à chaque souffle
                if (pl == 0) return null;
                double ph = ts + (spread ? i * 0.25 : 0);
                double v = 0.5 - 0.5 * Math.cos(2 * Math.PI * ph);
                int[] c = palette[(int) ((((long) Math.floor(ph + 0.5)) % pl + pl) % pl)];
                return new float[]{c[0], c[1], c[2], c[3], (float) (30 + 225 * v)};
            }
            case "heartbeat": {                       // battement de cœur doux (boum-boum)
                if (pl == 0) return null;
                long st = Math.max(800, step);
                double ph = ((now + (spread ? i * 90L : 0)) % st) / (double) st;
                double e = Math.exp(-Math.pow((ph - 0.12) / 0.06, 2)) + 0.7 * Math.exp(-Math.pow((ph - 0.34) / 0.06, 2));
                int[] c = palette[0];
                return new float[]{c[0], c[1], c[2], c[3], (float) (60 + 195 * Math.min(1, e))};
            }
            case "fire": {                            // flammes : couleurs chaudes qui ondulent
                int[][] p = pl >= 2 ? palette : FIRE;
                double sp = Math.max(260, step / 25.0);
                double h = noise(now / sp + i * 17.3);
                double d = noise(now / (sp * 0.6) + i * 31.7 + 5);
                float[] c = palAt(p, h * (p.length - 1), false);
                return new float[]{c[0], c[1], c[2], c[3], (float) (140 + 115 * d)};
            }
            case "drift": {                           // dérive lente (océan, aurore, coucher de soleil…)
                if (pl == 0) return null;
                double h = noise(ts * 0.8 + (spread ? i * 3.1 : 0));
                double d = noise(ts * 1.3 + i * 7.7 + 11);
                float[] c = palAt(palette, h * pl * 1.5, true);
                return new float[]{c[0], c[1], c[2], c[3], (float) (120 + 135 * d)};
            }
            case "twinkle": {                         // scintillement doux : les lyres s'illuminent tour à tour
                if (pl == 0) return null;
                double v = Math.pow(noise(ts * 3 + i * 13.7), 2.2);
                int[] c = palette[(int) ((((long) ts) + i) % pl)];
                return new float[]{c[0], c[1], c[2], c[3], (float) (20 + 235 * v)};
            }
            case "static":
            default: {
                if (pl == 0) return null;
                int[] c = palette[spread ? i % pl : 0];
                return new float[]{c[0], c[1], c[2], c[3]};
            }
        }
    }

    private static float[] lerp(int[] a, int[] b, double f) {
        return new float[]{(float) (a[0] + (b[0] - a[0]) * f), (float) (a[1] + (b[1] - a[1]) * f), (float) (a[2] + (b[2] - a[2]) * f), (float) (a[3] + (b[3] - a[3]) * f)};
    }

    /** Couleur d'une palette à une position décimale (fondu entre deux couleurs voisines). */
    private static float[] palAt(int[][] p, double x, boolean ease) {
        int pl = p.length;
        double fl = Math.floor(x);
        int k = (int) ((((long) fl) % pl + pl) % pl);
        double f = x - fl;
        return lerp(p[k], p[(k + 1) % pl], ease ? smooth(f) : f);
    }

    private static double smooth(double f) { return f * f * (3 - 2 * f); }

    /** Bruit doux [0 ; 1] (valeurs aléatoires reliées en douceur). */
    private static double noise(double x) {
        double fl = Math.floor(x);
        long i = (long) fl;
        double f = smooth(x - fl);
        return hash(i) + (hash(i + 1) - hash(i)) * f;
    }

    private static double hash(long i) {
        long v = i * 374761393L + 668265263L;
        v = (v ^ (v >>> 13)) * 1274126177L;
        return ((v ^ (v >>> 16)) & 0xffffff) / 16777216.0;
    }

    private static float[] hsv(double h) {
        double r, g, b; int k = (int) (h * 6); double f = h * 6 - k;
        switch (k % 6) {
            case 0: r = 1; g = f; b = 0; break;
            case 1: r = 1 - f; g = 1; b = 0; break;
            case 2: r = 0; g = 1; b = f; break;
            case 3: r = 0; g = 1 - f; b = 1; break;
            case 4: r = f; g = 0; b = 1; break;
            default: r = 1; g = 0; b = 1 - f;
        }
        return new float[]{(float) (r * 255), (float) (g * 255), (float) (b * 255), 0};
    }
}

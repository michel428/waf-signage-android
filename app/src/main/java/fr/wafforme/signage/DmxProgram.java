package fr.wafforme.signage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;

/**
 * Ambiance lumineuse calculée en continu (≈ 30 fois / s) : valeurs fixes +
 * mouvements des lyres + animation des couleurs. Reprend l'esprit des
 * fonctions Q Light Controller du club (EFX cercles, huits, carrés…, chaser
 * de couleurs, matrices RVB).
 *
 * JSON reçu du serveur :
 * {
 *   "base":     {"1":0,"6":255,…},                        valeurs fixes (canal → 0-255)
 *   "fixtures": [{"pan":1,"tilt":3,"dim":6,"r":7,"g":8,"b":9,"w":10},…]   canaux absolus (0 = absent)
 *   "motion":   {"shapes":["circle","eight"],"period":20000,"every":30000,"size":127,"cx":127,"cy":127,"offsets":[0,180,180,0]},
 *   "color":    {"mode":"chase","palette":[[255,0,0,0],…],"step":10000,"spread":true}
 * }
 * Un JSON « plat » {"1":255,…} est accepté tel quel (scène statique).
 */
final class DmxProgram {
    private final float[] base = new float[513];
    private int[][] fx = new int[0][];          // par lyre : pan, tilt, dim, r, g, b, w
    private String[] shapes = null;
    private long period = 20000, every = 30000;
    private float size = 127, cx = 127, cy = 127;
    private float[] offs = new float[0];
    private String cmode = null;
    private int[][] palette = new int[0][];
    private long step = 10000;
    private boolean spread = true;
    private final float[] out = new float[513];

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
            JSONArray of = m.optJSONArray("offsets");
            if (of != null) { offs = new float[of.length()]; for (int i = 0; i < of.length(); i++) offs[i] = (float) Math.toRadians(of.optDouble(i, 0)); }
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
            for (int i = 0; i < n; i++) {
                double t = t0 + (i < offs.length ? offs[i] : 0);
                double[] xy = shape(shape, t);
                set(fx[i][0], cx + size * xy[0]);
                set(fx[i][1], cy + size * xy[1]);
            }
        }
        // ── Couleurs ──
        if (cmode != null && n > 0) {
            for (int i = 0; i < n; i++) {
                float[] c = color(now, i, n);
                if (c == null) continue;
                set(fx[i][3], c[0]); set(fx[i][4], c[1]); set(fx[i][5], c[2]); set(fx[i][6], c[3]);
                if (c.length > 4) set(fx[i][2], c[4]);
            }
        }
        return out;
    }

    private void set(int ch, double v) { if (ch >= 1 && ch <= 512) out[ch] = clamp(v); }

    /** Formes des mouvements (équivalents des EFX de Q Light Controller), x et y dans [-1 ; 1]. */
    private static double[] shape(String s, double t) {
        double sn = Math.sin(t), cs = Math.cos(t);
        switch (s) {
            case "eight":     return new double[]{sn, Math.sin(2 * t)};
            case "line":      return new double[]{cs, cs};
            case "line2":     return new double[]{cs, 0};
            case "diamond":   return new double[]{Math.pow(sn, 3), Math.pow(cs, 3)};
            case "square": {
                double u = (t / (2 * Math.PI)) % 1.0 * 4; int k = (int) u; double f = u - k;
                switch (k) { case 0: return new double[]{-1 + 2 * f, -1}; case 1: return new double[]{1, -1 + 2 * f}; case 2: return new double[]{1 - 2 * f, 1}; default: return new double[]{-1, 1 - 2 * f}; }
            }
            case "squarechoppy": {
                int k = (int) ((t / (2 * Math.PI)) % 1.0 * 4);
                return new double[]{k == 1 || k == 2 ? 1 : -1, k >= 2 ? 1 : -1};
            }
            case "leaf":      return new double[]{Math.pow(sn, 5), cs * Math.abs(cs)};
            case "lissajous": return new double[]{Math.sin(2 * t + Math.PI / 2), Math.sin(3 * t)};
            case "circle":
            default:          return new double[]{cs, sn};
        }
    }

    /** Couleur d'une lyre : {r, g, b, w} (+ intensité optionnelle). */
    private float[] color(long now, int i, int n) {
        String mode = cmode;
        if ("mix".equals(mode)) {                     // enchaîne plusieurs animations (comme le chaser du club)
            String[] seq = {"rainbow", "chase", "wave", "random", "chase"};
            mode = seq[(int) ((now / (step * 2)) % seq.length)];
        }
        int pl = palette.length;
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
            case "random": {
                long k = now / Math.max(300, step / 4) + (spread ? i * 7919L : 0);
                double h = ((k * 2654435761L) & 0xffff) / 65536.0;
                return hsv(h);
            }
            case "pulse": {
                if (pl == 0) return null;
                int[] c = palette[0];
                float d = (float) (127.5 + 127.5 * Math.sin(2 * Math.PI * (now % step) / step - (spread ? i * 0.6 : 0)));
                return new float[]{c[0], c[1], c[2], c[3], d};
            }
            case "chase": {
                if (pl == 0) return null;
                int k = (int) ((now / step + (spread ? i : 0)) % pl);
                int[] c = palette[k];
                return new float[]{c[0], c[1], c[2], c[3]};
            }
            case "static":
            default: {
                if (pl == 0) return null;
                int[] c = palette[spread ? i % pl : 0];
                return new float[]{c[0], c[1], c[2], c[3]};
            }
        }
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

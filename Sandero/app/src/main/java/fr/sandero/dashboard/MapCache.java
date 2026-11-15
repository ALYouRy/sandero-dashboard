package fr.sandero.dashboard;

import android.content.Context;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Carte hors ligne : tout ce que la carte demande à OpenFreeMap (styles, tuiles, polices, icônes)
 * est gardé dans getFilesDir()/carte. Sans internet, la carte est servie depuis ce dossier.
 * On peut aussi télécharger d'avance toute une zone (download).
 */
public class MapCache {
    private static final String HOST = "tiles.openfreemap.org";
    private static final String OSM = "tile.openstreetmap.org";
    private static File dir;

    /* état du téléchargement d'une zone */
    public static volatile boolean run = false, stop = false;
    public static volatile int done = 0, total = 0, fail = 0;
    public static volatile long bytes = 0;
    public static volatile String msg = "";

    static File root(Context c) {
        if (dir == null) { dir = new File(c.getFilesDir(), "carte"); dir.mkdirs(); }
        return dir;
    }

    /* clé de cache : on enlève la version de la planète (/planet/20251112_001001_pt/14/…) pour que les tuiles restent valables après une mise à jour d'OpenFreeMap */
    static String key(String host, String path) {
        String p = path.replaceAll("^/planet/[^/]+_pt/", "/planet/v/").replaceAll("^/planet/\\d[^/]*/", "/planet/v/");
        p = p.replaceAll("[^A-Za-z0-9/._%-]", "_");
        return (host.equals(OSM) ? "osm" : "ofm") + p + (p.endsWith("/") ? "index" : "");
    }

    static boolean live(String path) {
        /* styles et description de la planète : on préfère la version en ligne (pour suivre les mises à jour) */
        return path.startsWith("/styles/") || path.equals("/planet") || path.equals("/planet/");
    }

    static String mime(String path) {
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".pbf") || path.endsWith(".mvt")) return "application/x-protobuf";
        return "application/json";
    }

    public static WebResourceResponse intercept(Context c, String url) {
        try {
            URL u = new URL(url);
            String h = u.getHost();
            if (!HOST.equals(h) && !OSM.equals(h)) return null;
            String path = u.getPath();
            File f = new File(root(c), key(h, path));
            byte[] b = null;
            if (live(path)) {
                b = fetch(url);
                if (b != null) save(f, b);
                else if (f.exists()) b = read(f);
            } else {
                if (f.exists()) b = read(f);
                else { b = fetch(url); if (b != null) save(f, b); }
            }
            if (b == null) {
                WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", 404, "Hors ligne", cors(), new ByteArrayInputStream(new byte[0]));
                return r;
            }
            return new WebResourceResponse(mime(path), mime(path).startsWith("application/json") ? "utf-8" : null, 200, "OK", cors(), new ByteArrayInputStream(b));
        } catch (Exception e) {
            return null;
        }
    }

    static Map<String, String> cors() {
        Map<String, String> m = new HashMap<>();
        m.put("Access-Control-Allow-Origin", "*");
        m.put("Cache-Control", "max-age=86400");
        return m;
    }

    static byte[] fetch(String url) {
        HttpURLConnection k = null;
        try {
            k = (HttpURLConnection) new URL(url).openConnection();
            k.setConnectTimeout(7000);
            k.setReadTimeout(15000);
            k.setRequestProperty("User-Agent", "LaMathias/1.0 (autoradio)");
            if (k.getResponseCode() != 200) return null;
            InputStream in = k.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            if (k != null) k.disconnect();
        }
    }

    static byte[] read(File f) {
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    static void save(File f, byte[] b) {
        try {
            f.getParentFile().mkdirs();
            File t = new File(f.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(t);
            o.write(b);
            o.close();
            t.renameTo(f);
        } catch (Exception ignored) { }
    }

    /** récupère une adresse en passant par le cache (et la garde) */
    static byte[] get(Context c, String url, boolean count) {
        try {
            URL u = new URL(url);
            File f = new File(root(c), key(u.getHost(), u.getPath()));
            if (f.exists() && !live(u.getPath())) return read(f);
            byte[] b = fetch(url);
            if (b != null) { save(f, b); if (count) bytes += b.length; }
            else if (f.exists()) b = read(f);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    static int lon2x(double lon, int z) { return (int) Math.floor((lon + 180) / 360 * (1 << z)); }
    static int lat2y(double lat, int z) {
        double r = Math.toRadians(lat);
        return (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * (1 << z));
    }

    static int count(double la0, double lo0, double la1, double lo1, int z0, int z1) {
        int n = 0;
        for (int z = z0; z <= z1; z++) {
            int x0 = lon2x(lo0, z), x1 = lon2x(lo1, z), y0 = lat2y(la1, z), y1 = lat2y(la0, z);
            n += (x1 - x0 + 1) * (y1 - y0 + 1);
        }
        return n;
    }

    /** Télécharge d'avance une zone : styles, polices, icônes, puis toutes les tuiles jusqu'au zoom zmax (et la France en zoom large). */
    public static synchronized boolean download(final Context c, final double la0, final double lo0, final double la1, final double lo1, final int zmax) {
        if (run) return false;
        run = true; stop = false; done = 0; fail = 0; bytes = 0; msg = "Préparation…";
        total = count(la0, lo0, la1, lo1, 9, zmax) + count(41.3, -5.2, 51.1, 9.6, 0, 8);
        new Thread(() -> {
            try {
                /* 1. styles + polices + icônes */
                Set<String> fonts = new HashSet<>();
                String glyphs = null;
                for (String s : new String[]{"liberty", "positron", "dark"}) {
                    byte[] b = get(c, "https://" + HOST + "/styles/" + s, true);
                    if (b == null) continue;
                    JSONObject st = new JSONObject(new String(b, "UTF-8"));
                    if (glyphs == null) glyphs = st.optString("glyphs", null);
                    String sp = st.optString("sprite", "");
                    if (sp.startsWith("http")) for (String e : new String[]{".json", ".png", "@2x.json", "@2x.png"}) get(c, sp + e, true);
                    JSONArray L = st.optJSONArray("layers");
                    if (L != null) for (int i = 0; i < L.length(); i++) {
                        JSONObject li = L.optJSONObject(i);
                        JSONObject lay = li == null ? null : li.optJSONObject("layout");
                        if (lay == null) continue;
                        JSONArray tf = lay.optJSONArray("text-font");
                        if (tf != null && tf.length() > 0 && tf.opt(0) instanceof String) {
                            StringBuilder sb = new StringBuilder();
                            for (int j = 0; j < tf.length(); j++) { if (j > 0) sb.append(','); sb.append(tf.optString(j)); }
                            fonts.add(sb.toString());
                        }
                    }
                }
                if (glyphs != null) for (String fs : fonts) for (String rg : new String[]{"0-255", "256-511", "8192-8447"}) {
                    if (stop) break;
                    get(c, glyphs.replace("{fontstack}", URLEncoder.encode(fs, "UTF-8").replace("+", "%20")).replace("{range}", rg), true);
                }
                /* 2. adresse des tuiles (description de la planète) */
                byte[] tj = get(c, "https://" + HOST + "/planet", true);
                if (tj == null) { msg = "Pas d’internet : impossible de télécharger la carte."; return; }
                String tpl = new JSONObject(new String(tj, "UTF-8")).getJSONArray("tiles").getString(0);
                /* 3. tuiles : France en zoom large, puis la zone en détail */
                msg = "Téléchargement des tuiles…";
                tiles(c, tpl, 41.3, -5.2, 51.1, 9.6, 0, 8);
                tiles(c, tpl, la0, lo0, la1, lo1, 9, zmax);
                msg = stop ? "Téléchargement arrêté." : (fail > 0 ? "Terminé (" + fail + " tuiles manquantes, relance pour compléter)." : "Carte hors ligne prête.");
            } catch (Exception e) {
                msg = "Erreur : " + e.getMessage();
            } finally {
                run = false;
            }
        }).start();
        return true;
    }

    static void tiles(Context c, String tpl, double la0, double lo0, double la1, double lo1, int z0, int z1) {
        for (int z = z0; z <= z1 && !stop; z++) {
            int x0 = lon2x(lo0, z), x1 = lon2x(lo1, z), y0 = lat2y(la1, z), y1 = lat2y(la0, z);
            for (int x = x0; x <= x1 && !stop; x++)
                for (int y = y0; y <= y1 && !stop; y++) {
                    String url = tpl.replace("{z}", "" + z).replace("{x}", "" + x).replace("{y}", "" + y);
                    byte[] b = get(c, url, true);
                    if (b == null) fail++;
                    done++;
                }
        }
    }

    private static long szV = -1, szT = 0;
    public static long size(Context c) {
        long now = System.currentTimeMillis();
        if (szV < 0 || (!run && now - szT > 5000) || now - szT > 60000) { szV = du(root(c)); szT = now; }
        return szV;
    }

    static long du(File f) {
        if (f.isFile()) return f.length();
        long n = 0;
        File[] l = f.listFiles();
        if (l != null) for (File x : l) n += du(x);
        return n;
    }

    public static void clear(Context c) { rm(root(c)); root(c).mkdirs(); }

    static void rm(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File x : l) rm(x);
        f.delete();
    }

    public static String state(Context c) {
        try {
            JSONObject o = new JSONObject();
            o.put("run", run); o.put("done", done); o.put("total", total); o.put("fail", fail);
            o.put("bytes", bytes); o.put("msg", msg); o.put("size", size(c));
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }
}

package fr.sandero.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mise à jour par internet : l'APK est publié par GitHub (Releases du dépôt),
 * l'appli le télécharge puis ouvre l'installeur Android (un seul appui sur « Installer »).
 * La signature reste la même (sandero.jks) : les trajets et réglages sont conservés.
 */
final class Updater {
    private Updater() {}

    static final String AUTH = "fr.sandero.dashboard.apk";

    private static void js(Activity a, WebView web, String code) {
        a.runOnUiThread(() -> { try { web.evaluateJavascript(code, null); } catch (Exception ignored) {} });
    }

    /** Ouvre une connexion en suivant les redirections (GitHub renvoie vers un autre serveur). */
    private static HttpURLConnection open(String url) throws Exception {
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "LaMathias-Autoradio");
            c.setRequestProperty("Cache-Control", "no-cache");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("redirection sans adresse");
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) { c.disconnect(); throw new Exception("HTTP " + code); }
            return c;
        }
        throw new Exception("trop de redirections");
    }

    static void check(Activity a, WebView web, String url) {
        new Thread(() -> {
            String res;
            try {
                HttpURLConnection c = open(url + (url.contains("?") ? "&" : "?") + "t=" + System.currentTimeMillis());
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0 && bo.size() < 65536) bo.write(b, 0, n);
                in.close();
                c.disconnect();
                res = "window.onUpdateInfo&&window.onUpdateInfo(" + JSONObject.quote(bo.toString("UTF-8")) + ",null)";
            } catch (Exception e) {
                res = "window.onUpdateInfo&&window.onUpdateInfo(null," + JSONObject.quote(String.valueOf(e.getMessage())) + ")";
            }
            js(a, web, res);
        }).start();
    }

    static File apkFile(Activity a) {
        File d = new File(a.getCacheDir(), "maj");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return new File(d, "la-mathias.apk");
    }

    static void download(Activity a, WebView web, String url) {
        new Thread(() -> {
            File f = apkFile(a);
            File tmp = new File(f.getParentFile(), "en-cours.apk");
            try {
                HttpURLConnection c = open(url);
                long total = c.getContentLengthLong();
                InputStream in = c.getInputStream();
                OutputStream out = new FileOutputStream(tmp);
                byte[] b = new byte[16384];
                long got = 0;
                int n, last = -1;
                while ((n = in.read(b)) > 0) {
                    out.write(b, 0, n);
                    got += n;
                    int pct = total > 0 ? (int) (got * 100 / total) : -1;
                    if (pct != last) { last = pct; js(a, web, "window.onUpdateProgress&&window.onUpdateProgress(" + pct + "," + got + ")"); }
                }
                out.close();
                in.close();
                c.disconnect();
                if (got < 100000) throw new Exception("fichier incomplet");
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                if (!tmp.renameTo(f)) throw new Exception("enregistrement impossible");
                js(a, web, "window.onUpdateReady&&window.onUpdateReady()");
                a.runOnUiThread(() -> install(a, web));
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                js(a, web, "window.onUpdateError&&window.onUpdateError(" + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
            }
        }).start();
    }

    /** Ouvre l'installeur Android. Sur Android 8+, il faut d'abord autoriser l'appli à installer des applis. */
    static void install(Activity a, WebView web) {
        File f = apkFile(a);
        if (!f.exists()) { js(a, web, "window.onUpdateError&&window.onUpdateError('aucun fichier téléchargé')"); return; }
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            js(a, web, "window.onUpdatePerm&&window.onUpdatePerm()");
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                try { a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)); } catch (Exception ignored) {}
            }
            return;
        }
        try {
            Uri u = Uri.parse("content://" + AUTH + "/la-mathias.apk");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            js(a, web, "window.onUpdateError&&window.onUpdateError(" + JSONObject.quote("installeur introuvable : " + e.getMessage()) + ")");
        }
    }

    static boolean ready(Activity a) { return apkFile(a).exists(); }
}

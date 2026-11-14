package fr.sandero.dashboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 7;
    private WebView web;
    private BleBridge ble;
    private GeoLink geo;
    private PhoneLink phone;
    private ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        ble = new BleBridge(this, web);
        web.addJavascriptInterface(ble, "AndroidBLE");
        web.addJavascriptInterface(new AppApi(), "AndroidApp");
        phone = new PhoneLink(this, web);
        phone.start();
        geo = new GeoLink(this);
        geo.start();
        if (!phone.hasPerm()) phone.askPerm();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url != null && url.startsWith("intent:")) {
                    launchFromIntentUrl(url);
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsConfirm(WebView v, String url, String message, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> r.confirm())
                        .setNegativeButton("Annuler", (d, w) -> r.cancel())
                        .setOnCancelListener(d -> r.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Choisir un fichier"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    private void launchFromIntentUrl(String url) {
        if (url.contains("action=")) {
            try {
                Intent a = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(a);
                return;
            } catch (Exception e) {
                Toast.makeText(this, "Réglage introuvable sur cet appareil", Toast.LENGTH_SHORT).show();
                return;
            }
        }
        Matcher m = Pattern.compile("package=([A-Za-z0-9_.]+)").matcher(url);
        if (m.find()) {
            Intent i = getPackageManager().getLaunchIntentForPackage(m.group(1));
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    startActivity(i);
                    return;
                } catch (Exception ignored) { }
            }
        }
        Toast.makeText(this, "Application introuvable", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE && fileCb != null) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null && data.getData() != null) out = new Uri[]{data.getData()};
            fileCb.onReceiveValue(out);
            fileCb = null;
            return;
        }
        if (req == Backup.REQ_PICK) { onBackupResult(res, data); return; }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == GeoLink.REQ_PERM) {
            geo.start();
            web.evaluateJavascript("window.onGeoPerm&&window.onGeoPerm()", null);
            return;
        }
        if (req == PhoneLink.REQ_PERM) {
            phone.start();
            phone.push();
            return;
        }
        ble.onPermissionResult(req, results);
    }

    /* CarPlay ou une autre appli passe devant : l'appli se met en mode économie
       (plus de 3D ni de dessin, OBD ralenti) sans arrêter l'enregistrement des trajets */
    @Override
    protected void onStop() {
        super.onStop();
        if (web != null) {
            web.evaluateJavascript("window.onAppVisible&&window.onAppVisible(false)", null);
            web.onPause();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (web != null) {
            web.onResume();
            web.evaluateJavascript("window.onAppVisible&&window.onAppVisible(true)", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (phone != null) phone.push();
        notifySplit();
    }

    /* Écran partagé : prévient la page pour qu'elle passe en panneau compact */
    @Override
    public void onMultiWindowModeChanged(boolean inMulti) {
        super.onMultiWindowModeChanged(inMulti);
        notifySplit();
    }

    private void notifySplit() {
        if (web == null) return;
        final boolean on = android.os.Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode();
        web.post(() -> web.evaluateJavascript("window.onSplit&&window.onSplit(" + on + ")", null));
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /* Touche Retour : revient à l'écran précédent de l'appli, puis à l'Accueil.
       Sur l'Accueil, elle ne fait rien (l'appli peut être l'écran d'accueil d'Android). */
    @Override
    public void onBackPressed() {
        if (web == null) return;
        web.evaluateJavascript("window.appBack?window.appBack():false", null);
    }

    @Override
    protected void onDestroy() {
        if (ble != null) ble.shutdown();
        if (phone != null) phone.stop();
        if (geo != null) geo.stop();
        super.onDestroy();
    }

    /* Fonctions natives proposées à la page */
    private void onBackupResult(int res, Intent data) {
        if (res != RESULT_OK || data == null || data.getData() == null) {
            web.evaluateJavascript("window.onBackupData&&window.onBackupData(null,'Aucun fichier choisi')", null);
            return;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            String js;
            try {
                String txt = Backup.read(MainActivity.this, uri);
                js = "window.onBackupData&&window.onBackupData(" + JSONObject.quote(txt) + ")";
            } catch (Exception e) {
                js = "window.onBackupData&&window.onBackupData(null," + JSONObject.quote(String.valueOf(e.getMessage())) + ")";
            }
            final String fjs = js;
            runOnUiThread(() -> web.evaluateJavascript(fjs, null));
        }).start();
    }

    private class AppApi {
        /* ---- caractéristiques de l'autoradio (pour régler automatiquement les performances) ---- */
        @JavascriptInterface
        public String deviceInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("sdk", android.os.Build.VERSION.SDK_INT);
                o.put("release", android.os.Build.VERSION.RELEASE);
                o.put("model", android.os.Build.MODEL);
                o.put("maker", android.os.Build.MANUFACTURER);
                o.put("hw", android.os.Build.HARDWARE);
                o.put("board", android.os.Build.BOARD);
                o.put("cores", Runtime.getRuntime().availableProcessors());
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am != null) {
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    o.put("ramMb", mi.totalMem / (1024 * 1024));
                    o.put("lowRam", am.isLowRamDevice());
                }
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    android.content.pm.PackageInfo wv = WebView.getCurrentWebViewPackage();
                    if (wv != null) o.put("webview", wv.versionName);
                }
            } catch (Exception ignored) { }
            return o.toString();
        }

        /* ---- sauvegarde des données (Téléchargements/Sandero) ---- */
        @JavascriptInterface
        public String backupWrite(String json, String monthTag) {
            return Backup.write(MainActivity.this, json, monthTag);
        }

        @JavascriptInterface
        public void backupPick() {
            runOnUiThread(() -> {
                try { Backup.pick(MainActivity.this); }
                catch (Exception e) { web.evaluateJavascript("window.onBackupData&&window.onBackupData(null,'Sélecteur de fichiers indisponible')", null); }
            });
        }

        /* version réelle d'Android (certains autoradios affichent une fausse version dans les réglages) */
        @JavascriptInterface
        public int sdk() {
            return android.os.Build.VERSION.SDK_INT;
        }

        /* la sauvegarde automatique peut-elle écrire sans demander d'autorisation ? */
        @JavascriptInterface
        public boolean backupCanWrite() {
            return android.os.Build.VERSION.SDK_INT >= 29
                    || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }

        /* ---- ouverture automatique au démarrage de l'autoradio ---- */
        @JavascriptInterface
        public void setAutoBoot(boolean on) {
            getSharedPreferences(BootReceiver.PREFS, MODE_PRIVATE).edit().putBoolean(BootReceiver.KEY_BOOT, on).apply();
        }

        @JavascriptInterface
        public boolean canOverlay() {
            return android.os.Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(MainActivity.this);
        }

        @JavascriptInterface
        public void askOverlay() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Réglage introuvable sur cet autoradio", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public boolean bootStart() {
            return getIntent() != null && getIntent().getBooleanExtra("boot", false);
        }

        @JavascriptInterface
        public String geo() {
            return geo == null ? "{\"perm\":false,\"ok\":false}" : geo.status();
        }

        @JavascriptInterface
        public void geoPerm() {
            runOnUiThread(() -> { if (geo != null) geo.askPerm(); });
        }

        @JavascriptInterface
        public String apps() {
            try {
                PackageManager pm = getPackageManager();
                Intent q = new Intent(Intent.ACTION_MAIN);
                q.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
                final List<String[]> rows = new ArrayList<>();
                for (ResolveInfo r : list) {
                    String pkg = r.activityInfo.packageName;
                    if (pkg.equals(getPackageName())) continue;
                    rows.add(new String[]{String.valueOf(r.loadLabel(pm)), pkg});
                }
                Collections.sort(rows, new Comparator<String[]>() {
                    @Override
                    public int compare(String[] a, String[] b) {
                        return a[0].compareToIgnoreCase(b[0]);
                    }
                });
                JSONArray arr = new JSONArray();
                for (String[] r : rows) {
                    JSONObject o = new JSONObject();
                    o.put("n", r[0]);
                    o.put("p", r[1]);
                    arr.put(o);
                }
                return arr.toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        /* ouvre une application (Waze…) à côté du tableau de bord, en écran partagé */
        @JavascriptInterface
        public void launchSplit(final String pkg) {
            runOnUiThread(() -> {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) {
                    Toast.makeText(MainActivity.this, "Application introuvable : " + pkg, Toast.LENGTH_SHORT).show();
                    return;
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Impossible d’ouvrir l’application", Toast.LENGTH_SHORT).show();
                    return;
                }
                // l'écran partagé a-t-il été accepté par l'autoradio ? réponse à la page après un court délai
                web.postDelayed(() -> {
                    boolean on = android.os.Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode();
                    web.evaluateJavascript("window.onSplitResult&&window.onSplitResult(" + on + ")", null);
                }, 1800);
            });
        }

        @JavascriptInterface
        public boolean inSplit() {
            return android.os.Build.VERSION.SDK_INT >= 24 && isInMultiWindowMode();
        }

        /* état de la connexion Bluetooth du téléphone (JSON) */
        @JavascriptInterface
        public String phoneLink() {
            return phone != null ? phone.status() : "{}";
        }

        /* demande l'autorisation Bluetooth (Android 12 et plus) */
        @JavascriptInterface
        public void phonePerm() {
            runOnUiThread(() -> { if (phone != null) phone.askPerm(); });
        }

        @JavascriptInterface
        public void mediaKey(final int code) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                        long now = SystemClock.uptimeMillis();
                        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
                    } catch (Exception ignored) { }
                }
            });
        }

        @JavascriptInterface
        public String nowPlaying() {
            try {
                MediaSessionManager msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
                List<MediaController> cs = msm.getActiveSessions(new ComponentName(MainActivity.this, MediaListener.class));
                JSONObject o = new JSONObject();
                if (cs == null || cs.isEmpty()) return o.toString();
                MediaController c = cs.get(0);
                MediaMetadata md = c.getMetadata();
                PlaybackState ps = c.getPlaybackState();
                if (md != null) {
                    String t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                    String a = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    if (t != null) o.put("t", t);
                    if (a != null) o.put("a", a);
                }
                o.put("p", ps != null && ps.getState() == PlaybackState.STATE_PLAYING);
                return o.toString();
            } catch (SecurityException e) {
                return "{\"perm\":true}";
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public String icon(String pkg) {
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, 96, 96);
                d.draw(c);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bo);
                return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void shareText(final String name, final String text) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_SUBJECT, name);
                    i.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "Enregistrer / envoyer"));
                }
            });
        }
    }
}

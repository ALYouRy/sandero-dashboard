package fr.sandero.dashboard;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Wi-Fi de l'autoradio : état, réseaux autour, connexion (Android 8.1 : on peut encore ajouter un réseau depuis une appli). */
public class WifiLink {
    private final Context c;
    private final WifiManager wm;
    private final ConnectivityManager cm;

    private final SharedPreferences sp;
    private final Handler h = new Handler(Looper.getMainLooper());
    private long lastTry = 0;
    private int miss = 0;
    volatile String autoMsg = "";

    public WifiLink(Context ctx) {
        c = ctx.getApplicationContext();
        wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
        cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        sp = c.getSharedPreferences("wifi", Context.MODE_PRIVATE);
        h.postDelayed(tick, 8000);
    }

    /* réseau à rejoindre tout seul (partage de connexion du téléphone) */
    public void setAuto(String ssid, String pwd, boolean on) {
        sp.edit().putString("ssid", ssid == null ? "" : ssid).putString("pwd", pwd == null ? "" : pwd).putBoolean("auto", on).apply();
        miss = 0; lastTry = 0;
        if (on && ssid != null && !ssid.isEmpty()) { ensure(ssid, pwd); h.removeCallbacks(tick); h.postDelayed(tick, 1500); }
    }

    private boolean connectedWifi() {
        try { WifiInfo wi = wm.getConnectionInfo(); return wi != null && wi.getNetworkId() != -1 && wi.getSupplicantState() == android.net.wifi.SupplicantState.COMPLETED; }
        catch (Exception e) { return false; }
    }

    /** toutes les 15 s (60 s une fois connecté) : si l'autoradio n'est sur aucun Wi-Fi, il cherche le partage de connexion et s'y connecte */
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long next = 15000;
            try {
                String ssid = sp.getString("ssid", "");
                if (sp.getBoolean("auto", true) && !ssid.isEmpty()) {
                    if (connectedWifi()) { next = 60000; miss = 0; autoMsg = ""; }
                    else {
                        if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
                        wm.startScan();
                        boolean seen = false;
                        try { for (ScanResult r : wm.getScanResults()) if (ssid.equals(r.SSID)) { seen = true; break; } } catch (Exception ignored) { }
                        long now = SystemClock.elapsedRealtime();
                        /* visible : on se connecte ; invisible : on essaie quand même de temps en temps (réseau masqué, iPhone en veille) */
                        if ((seen || miss % 4 == 3) && now - lastTry > 20000) {
                            lastTry = now;
                            int id = ensure(ssid, sp.getString("pwd", ""));
                            if (id != -1) { wm.enableNetwork(id, true); wm.reconnect(); }
                            autoMsg = seen ? "Connexion à " + ssid + "…" : "Recherche de " + ssid + "…";
                        } else autoMsg = "Recherche de " + ssid + "…";
                        miss++;
                        if (miss > 40) next = 45000;
                    }
                } else autoMsg = "";
            } catch (Exception ignored) { }
            h.postDelayed(this, next);
        }
    };

    /* enregistre le réseau dans Android s'il n'y est pas (ou si le mot de passe a changé) et rend son numéro */
    @SuppressWarnings("deprecation")
    private int ensure(String ssid, String pwd) {
        try {
            List<WifiConfiguration> cf = wm.getConfiguredNetworks();
            if (cf != null) for (WifiConfiguration w : cf) if (ssid.equals(clean(w.SSID))) {
                String k = sp.getString("k_" + ssid, null);
                if (pwd == null || pwd.isEmpty() || pwd.equals(k)) return w.networkId;
                wm.removeNetwork(w.networkId);
                break;
            }
            WifiConfiguration w = new WifiConfiguration();
            w.SSID = "\"" + ssid + "\"";
            w.hiddenSSID = true;
            if (pwd != null && !pwd.isEmpty()) w.preSharedKey = "\"" + pwd + "\"";
            else w.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            w.priority = 100000;
            int id = wm.addNetwork(w);
            if (id != -1) { wm.saveConfiguration(); sp.edit().putString("k_" + ssid, pwd == null ? "" : pwd).apply(); }
            return id;
        } catch (Exception e) {
            return -1;
        }
    }

    static String clean(String s) {
        if (s == null) return "";
        if (s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        return "<unknown ssid>".equals(s) ? "" : s;
    }

    public String state() {
        JSONObject o = new JSONObject();
        try {
            boolean on = wm != null && wm.isWifiEnabled();
            o.put("on", on);
            WifiInfo wi = wm != null ? wm.getConnectionInfo() : null;
            boolean wifi = false, net = false, other = false;
            if (cm != null) {
                Network[] all = cm.getAllNetworks();
                for (Network n : all) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc == null) continue;
                    boolean in = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                    if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) { wifi = true; if (in) net = true; }
                    else if (in) other = true;
                }
            }
            o.put("wifi", wifi && wi != null && wi.getNetworkId() != -1);
            o.put("net", net);
            o.put("other", other);
            o.put("auto", autoMsg);
            if (wi != null && wi.getNetworkId() != -1) {
                o.put("ssid", clean(wi.getSSID()));
                o.put("rssi", wi.getRssi());
                o.put("lvl", WifiManager.calculateSignalLevel(wi.getRssi(), 4));
            }
        } catch (Exception ignored) { }
        return o.toString();
    }

    public boolean enable(boolean on) {
        try { return wm.setWifiEnabled(on); } catch (Exception e) { return false; }
    }

    public void scan() {
        try { wm.startScan(); } catch (Exception ignored) { }
    }

    public String list() {
        JSONArray a = new JSONArray();
        try {
            List<ScanResult> L = wm.getScanResults();
            Map<String, ScanResult> best = new HashMap<>();
            for (ScanResult r : L) {
                if (r.SSID == null || r.SSID.isEmpty()) continue;
                ScanResult b = best.get(r.SSID);
                if (b == null || r.level > b.level) best.put(r.SSID, r);
            }
            Map<String, Boolean> saved = new HashMap<>();
            try {
                List<WifiConfiguration> cf = wm.getConfiguredNetworks();
                if (cf != null) for (WifiConfiguration w : cf) saved.put(clean(w.SSID), true);
            } catch (Exception ignored) { }
            for (ScanResult r : best.values()) {
                JSONObject o = new JSONObject();
                o.put("ssid", r.SSID);
                o.put("lvl", WifiManager.calculateSignalLevel(r.level, 4));
                o.put("rssi", r.level);
                String cap = r.capabilities == null ? "" : r.capabilities;
                o.put("sec", cap.contains("WPA") || cap.contains("WEP") || cap.contains("PSK") || cap.contains("SAE"));
                o.put("saved", saved.containsKey(r.SSID));
                a.put(o);
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    /** se connecte : réseau déjà connu (pwd vide) ou nouveau (avec mot de passe) */
    @SuppressWarnings("deprecation")
    public boolean connect(String ssid, String pwd) {
        try {
            if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);
            int id = -1;
            List<WifiConfiguration> cf = wm.getConfiguredNetworks();
            if (cf != null) for (WifiConfiguration w : cf) if (ssid.equals(clean(w.SSID))) { id = w.networkId; break; }
            if (pwd != null && !pwd.isEmpty()) {
                if (id != -1) wm.removeNetwork(id);
                WifiConfiguration w = new WifiConfiguration();
                w.SSID = "\"" + ssid + "\"";
                w.preSharedKey = "\"" + pwd + "\"";
                id = wm.addNetwork(w);
            } else if (id == -1) {
                WifiConfiguration w = new WifiConfiguration();
                w.SSID = "\"" + ssid + "\"";
                w.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                id = wm.addNetwork(w);
            }
            if (id == -1) return false;
            wm.disconnect();
            boolean ok = wm.enableNetwork(id, true);
            wm.reconnect();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    public void openSettings() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception ignored) { }
    }
}

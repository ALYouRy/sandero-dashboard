package fr.sandero.dashboard;

import android.content.Context;
import android.content.Intent;
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

    public WifiLink(Context ctx) {
        c = ctx.getApplicationContext();
        wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
        cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
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

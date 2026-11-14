package fr.sandero.dashboard;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.Set;

/**
 * Surveille la connexion Bluetooth du téléphone avec l'autoradio
 * (appels = profil mains libres, musique = profil audio) et prévient la page web.
 *
 * L'autoradio est côté « voiture » : selon les modèles, Android expose la liaison
 * via les profils classiques (A2DP / HEADSET) ou via les profils voiture
 * (A2DP_SINK = 11, HEADSET_CLIENT = 16). On interroge les quatre.
 * Certains autoradios gèrent le Bluetooth avec un module séparé, invisible pour Android :
 * dans ce cas rien n'est détecté, et la page l'explique.
 */
public class PhoneLink {

    static final int REQ_PERM = 41;
    private static final int P_HEADSET = 1, P_A2DP = 2, P_A2DP_SINK = 11, P_HFP_CLIENT = 16;

    private final Activity act;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BroadcastReceiver rx;

    PhoneLink(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    boolean hasPerm() {
        return Build.VERSION.SDK_INT < 31
                || act.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    void askPerm() {
        if (Build.VERSION.SDK_INT >= 31 && !hasPerm()) {
            act.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_PERM);
        } else {
            push();
        }
    }

    void start() {
        if (rx != null) return;
        rx = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                // laisse le temps à la liaison de se stabiliser avant de relire l'état
                ui.removeCallbacksAndMessages(null);
                ui.postDelayed(PhoneLink.this::push, 700);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED");
        f.addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED");
        try {
            if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(rx, f, Context.RECEIVER_EXPORTED);
            else act.registerReceiver(rx, f);
        } catch (Exception ignored) {
            rx = null;
        }
    }

    void stop() {
        ui.removeCallbacksAndMessages(null);
        if (rx != null) {
            try { act.unregisterReceiver(rx); } catch (Exception ignored) { }
            rx = null;
        }
    }

    private BluetoothAdapter adapter() {
        try {
            BluetoothManager m = (BluetoothManager) act.getSystemService(Context.BLUETOOTH_SERVICE);
            return m != null ? m.getAdapter() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean prof(BluetoothAdapter a, int p) {
        try {
            return a.getProfileConnectionState(p) == BluetoothProfile.STATE_CONNECTED;
        } catch (Exception e) {
            return false;
        }
    }

    String status() {
        JSONObject o = new JSONObject();
        try {
            if (!hasPerm()) { o.put("perm", false); return o.toString(); }
            BluetoothAdapter a = adapter();
            if (a == null) { o.put("none", true); return o.toString(); }
            boolean on = a.isEnabled();
            o.put("on", on);
            if (!on) return o.toString();
            boolean calls = prof(a, P_HEADSET) || prof(a, P_HFP_CLIENT);
            boolean music = prof(a, P_A2DP) || prof(a, P_A2DP_SINK);
            // nom de l'appareil : appareil jumelé actuellement relié (téléphone en priorité)
            String name = "", other = "";
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded != null) {
                Method isConn = null;
                try { isConn = BluetoothDevice.class.getMethod("isConnected"); } catch (Exception ignored) { }
                for (BluetoothDevice d : bonded) {
                    boolean c = false;
                    try { if (isConn != null) c = (Boolean) isConn.invoke(d); } catch (Exception ignored) { }
                    if (!c) continue;
                    String n = d.getName() != null ? d.getName() : d.getAddress();
                    BluetoothClass bc = d.getBluetoothClass();
                    if (bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE) { name = n; break; }
                    if (other.isEmpty()) other = n;
                }
            }
            if (name.isEmpty()) name = other;
            o.put("calls", calls);
            o.put("music", music);
            o.put("connected", calls || music || !name.isEmpty());
            o.put("name", name);
        } catch (Exception e) {
            try { o.put("err", true); } catch (Exception ignored) { }
        }
        return o.toString();
    }

    void push() {
        final String js = "window.onPhoneLink&&window.onPhoneLink(" + JSONObject.quote(status()) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }
}

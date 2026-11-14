package fr.sandero.dashboard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.ArrayAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pont Bluetooth entre la page web et l'adaptateur OBD-II.
 * Gère les ELM327 Bluetooth LE (GATT) et Bluetooth classique (SPP / RFCOMM, appairés).
 */
public class BleBridge {

    private static final int REQ_PERM = 11;
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Activity act;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

    /* connexion en cours */
    private BluetoothGatt gatt;
    private final ArrayList<BluetoothGattCharacteristic> chars = new ArrayList<BluetoothGattCharacteristic>();
    private BluetoothSocket sock;
    private OutputStream sockOut;
    private int connectId = -1;
    private Runnable connectTimeout;

    /* file d'opérations GATT (une seule à la fois) */
    private final ArrayDeque<Op> queue = new ArrayDeque<Op>();
    private Op cur;
    private Runnable watchdog;

    /* choix de l'appareil */
    private int pendingPick = -1;
    private BluetoothLeScanner scanner;
    private ScanCallback scanCb;
    private AlertDialog dialog;

    private static abstract class Op {
        int id;
        abstract void start();
    }

    public BleBridge(Activity a, WebView w) {
        act = a;
        web = w;
    }

    /* ------------------------------------------------------------ appels depuis la page */

    @JavascriptInterface
    public void pick(final int id) {
        ui.post(new Runnable() { public void run() { doPick(id); } });
    }

    @JavascriptInterface
    public void connect(final int id, final String addr) {
        ui.post(new Runnable() { public void run() { doConnect(id, addr); } });
    }

    @JavascriptInterface
    public void subscribe(final int id, final int idx) {
        ui.post(new Runnable() { public void run() { doNotify(id, idx); } });
    }

    @JavascriptInterface
    public void write(final int id, final int idx, final String b64, final boolean noResp) {
        ui.post(new Runnable() { public void run() { doWrite(id, idx, b64, noResp); } });
    }

    @JavascriptInterface
    public void disconnect() {
        ui.post(new Runnable() { public void run() { closeAll(false); } });
    }

    public void shutdown() {
        closeAll(false);
    }

    /* ------------------------------------------------------------ retours vers la page */

    private void js(final String code) {
        web.post(new Runnable() { public void run() { web.evaluateJavascript(code, null); } });
    }

    private void resolve(int id, String json) {
        js("window.__ble&&window.__ble.res(" + id + "," + json + ")");
    }

    private void reject(int id, String msg) {
        js("window.__ble&&window.__ble.rej(" + id + "," + JSONObject.quote(msg) + ")");
    }

    private void emitValue(int idx, byte[] v) {
        js("window.__ble&&window.__ble.val(" + idx + ",'" + Base64.encodeToString(v, Base64.NO_WRAP) + "')");
    }

    private void emitDisc() {
        js("window.__ble&&window.__ble.disc()");
    }

    /* ------------------------------------------------------------ permissions */

    private String[] neededPerms() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPerms() {
        if (Build.VERSION.SDK_INT < 23) return true;
        for (String p : neededPerms()) {
            if (act.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    public void onPermissionResult(int req, int[] results) {
        if (req != REQ_PERM || pendingPick < 0) return;
        int id = pendingPick;
        pendingPick = -1;
        boolean ok = results.length > 0;
        for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) ok = false;
        if (ok) doPick(id);
        else reject(id, "Autorisation Bluetooth refusée");
    }

    /* ------------------------------------------------------------ choix de l'adaptateur */

    private static class Item {
        String name, addr;
        boolean paired;
    }

    private void doPick(final int id) {
        if (adapter == null || !adapter.isEnabled()) {
            reject(id, "Bluetooth désactivé : active-le puis réessaie");
            return;
        }
        if (!hasPerms()) {
            pendingPick = id;
            act.requestPermissions(neededPerms(), REQ_PERM);
            return;
        }
        stopScan();
        final ArrayList<Item> items = new ArrayList<Item>();
        final ArrayAdapter<String> ad = new ArrayAdapter<String>(act, android.R.layout.simple_list_item_1, new ArrayList<String>());
        final boolean[] chosen = {false};

        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                Item it = new Item();
                it.name = d.getName() == null ? "(sans nom)" : d.getName();
                it.addr = d.getAddress();
                it.paired = true;
                items.add(it);
                ad.add(it.name + "  ·  appairé");
            }
        } catch (SecurityException ignored) { }

        scanner = adapter.getBluetoothLeScanner();
        scanCb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                BluetoothDevice d = r.getDevice();
                String addr = d.getAddress();
                for (Item i : items) if (i.addr.equals(addr)) return;
                String n = null;
                try { n = d.getName(); } catch (SecurityException ignored) { }
                if (n == null && r.getScanRecord() != null) n = r.getScanRecord().getDeviceName();
                Item it = new Item();
                it.name = n == null ? "(sans nom)" : n;
                it.addr = addr;
                items.add(it);
                ad.add(it.name + "  ·  " + addr);
            }
        };
        try {
            if (scanner != null) scanner.startScan(scanCb);
        } catch (Exception ignored) { }

        dialog = new AlertDialog.Builder(act)
                .setTitle("Choisis ton adaptateur OBD")
                .setAdapter(ad, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dlg, int which) {
                        chosen[0] = true;
                        Item it = items.get(which);
                        try {
                            JSONObject o = new JSONObject();
                            o.put("addr", it.addr);
                            o.put("name", it.name);
                            resolve(id, o.toString());
                        } catch (Exception e) {
                            reject(id, "erreur");
                        }
                    }
                })
                .setNegativeButton("Annuler", null)
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                stopScan();
                if (!chosen[0]) reject(id, "Aucun appareil choisi");
            }
        });
        dialog.show();
    }

    private void stopScan() {
        try {
            if (scanner != null && scanCb != null) scanner.stopScan(scanCb);
        } catch (Exception ignored) { }
        scanner = null;
        scanCb = null;
    }

    /* ------------------------------------------------------------ connexion */

    private void doConnect(final int id, String addr) {
        closeAll(false);
        if (adapter == null) {
            reject(id, "Bluetooth indisponible");
            return;
        }
        final BluetoothDevice dev;
        try {
            dev = adapter.getRemoteDevice(addr);
        } catch (Exception e) {
            reject(id, "Adresse invalide");
            return;
        }
        connectId = id;
        connectTimeout = new Runnable() {
            public void run() {
                if (connectId == id) {
                    int c = connectId;
                    connectId = -1;
                    closeAll(false);
                    reject(c, "Délai dépassé : l'adaptateur ne répond pas (contact mis ? adaptateur libre ?)");
                }
            }
        };
        ui.postDelayed(connectTimeout, 30000);

        int type = BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        boolean bonded = false;
        try {
            type = dev.getType();
            bonded = dev.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException ignored) { }
        curDev = dev;
        triedClassic = false;
        triedLe = false;

        /* Après une réinstallation, Android peut ne plus connaître le type de l'adaptateur
           (« inconnu ») : un ELM327 classique appairé part alors à tort en Bluetooth LE.
           Classique d'abord s'il est appairé et pas purement LE ; sinon LE, puis bascule. */
        if (type == BluetoothDevice.DEVICE_TYPE_CLASSIC
                || (bonded && type != BluetoothDevice.DEVICE_TYPE_LE)) {
            startClassic(dev, id);
        } else {
            startLe(dev, id);
        }
    }

    private BluetoothDevice curDev;
    private boolean triedClassic, triedLe;
    private Runnable leFallback;

    private void startClassic(BluetoothDevice dev, int id) {
        triedClassic = true;
        connectClassic(dev, id);
    }

    private void startLe(final BluetoothDevice dev, final int id) {
        triedLe = true;
        gatt = dev.connectGatt(act, false, gattCb, BluetoothDevice.TRANSPORT_LE);
        /* pas de réponse en LE au bout de 10 s : on tente le Bluetooth classique */
        if (leFallback != null) ui.removeCallbacks(leFallback);
        leFallback = new Runnable() {
            public void run() { switchMode(id, true); }
        };
        ui.postDelayed(leFallback, 10000);
    }

    /** Bascule LE -> classique (ou classique -> LE) si l'autre mode n'a pas encore été essayé. */
    private boolean switchMode(int id, boolean fromLe) {
        if (connectId != id || curDev == null) return false;
        if (fromLe && !triedClassic) {
            BluetoothGatt g = gatt;
            gatt = null;
            if (g != null) {
                try { g.disconnect(); } catch (Exception ignored) { }
                try { g.close(); } catch (Exception ignored) { }
            }
            startClassic(curDev, id);
            return true;
        }
        if (!fromLe && !triedLe) {
            startLe(curDev, id);
            return true;
        }
        return false;
    }

    private void finishConnect(int id, String json) {
        if (connectId != id) return;
        if (leFallback != null) ui.removeCallbacks(leFallback);
        connectId = -1;
        ui.removeCallbacks(connectTimeout);
        resolve(id, json);
    }

    private void failConnect(String msg) {
        if (leFallback != null) ui.removeCallbacks(leFallback);
        if (connectId < 0) return;
        int id = connectId;
        connectId = -1;
        ui.removeCallbacks(connectTimeout);
        reject(id, msg);
    }

    /* --- Bluetooth classique (SPP) --- */

    private void connectClassic(final BluetoothDevice dev, final int id) {
        new Thread(new Runnable() {
            public void run() {
                BluetoothSocket s = null;
                try {
                    try { adapter.cancelDiscovery(); } catch (Exception ignored) { }
                    try {
                        s = dev.createRfcommSocketToServiceRecord(SPP);
                        s.connect();
                    } catch (IOException first) {
                        try { if (s != null) s.close(); } catch (IOException ignored) { }
                        s = (BluetoothSocket) dev.getClass().getMethod("createRfcommSocket", int.class).invoke(dev, 1);
                        s.connect();
                    }
                } catch (final Exception e) {
                    try { if (s != null) s.close(); } catch (IOException ignored) { }
                    ui.post(new Runnable() { public void run() {
                        if (!switchMode(id, false))
                            failConnect("Connexion impossible : vérifie que le contact est mis, que l'adaptateur n'est pas connecté à un autre appareil, puis appaire-le dans les réglages Bluetooth");
                    } });
                    return;
                }
                final BluetoothSocket fs = s;
                ui.post(new Runnable() {
                    public void run() {
                        if (connectId != id) {
                            try { fs.close(); } catch (IOException ignored) { }
                            return;
                        }
                        sock = fs;
                        try { sockOut = fs.getOutputStream(); } catch (IOException e) { failConnect("Flux Bluetooth indisponible"); return; }
                        startReader(fs);
                        finishConnect(id, "{\"services\":[{\"uuid\":\"spp\",\"chars\":[{\"i\":0,\"uuid\":\"spp\",\"p\":{\"notify\":true,\"indicate\":false,\"write\":true,\"writeNoResp\":true}}]}]}");
                    }
                });
            }
        }).start();
    }

    private void startReader(final BluetoothSocket s) {
        new Thread(new Runnable() {
            public void run() {
                byte[] buf = new byte[512];
                try {
                    InputStream in = s.getInputStream();
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        final byte[] chunk = new byte[n];
                        System.arraycopy(buf, 0, chunk, 0, n);
                        ui.post(new Runnable() { public void run() { emitValue(0, chunk); } });
                    }
                } catch (IOException ignored) { }
                ui.post(new Runnable() {
                    public void run() {
                        if (sock == s) {
                            sock = null;
                            sockOut = null;
                            emitDisc();
                        }
                    }
                });
            }
        }).start();
    }

    /* --- Bluetooth LE (GATT) --- */

    private final BluetoothGattCallback gattCb = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(final BluetoothGatt g, int status, final int newState) {
            ui.post(new Runnable() {
                public void run() {
                    if (g != gatt) { try { g.close(); } catch (Exception ignored) { } return; }
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        if (leFallback != null) ui.removeCallbacks(leFallback);
                        g.discoverServices();
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        boolean wasConnecting = connectId >= 0;
                        if (wasConnecting && !triedClassic) {
                            if (leFallback != null) ui.removeCallbacks(leFallback);
                            switchMode(connectId, true);
                            return;
                        }
                        closeAll(false);
                        if (wasConnecting) failConnect("Connexion refusée par l'adaptateur");
                        else emitDisc();
                    }
                }
            });
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt g, final int status) {
            ui.post(new Runnable() {
                public void run() {
                    if (g != gatt || connectId < 0) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) { failConnect("Services Bluetooth introuvables"); return; }
                    try {
                        chars.clear();
                        JSONArray services = new JSONArray();
                        for (android.bluetooth.BluetoothGattService sv : g.getServices()) {
                            JSONArray cs = new JSONArray();
                            for (BluetoothGattCharacteristic c : sv.getCharacteristics()) {
                                int pr = c.getProperties();
                                JSONObject p = new JSONObject();
                                p.put("notify", (pr & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0);
                                p.put("indicate", (pr & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0);
                                p.put("write", (pr & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0);
                                p.put("writeNoResp", (pr & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0);
                                JSONObject o = new JSONObject();
                                o.put("i", chars.size());
                                o.put("uuid", c.getUuid().toString());
                                o.put("p", p);
                                cs.put(o);
                                chars.add(c);
                            }
                            JSONObject so = new JSONObject();
                            so.put("uuid", sv.getUuid().toString());
                            so.put("chars", cs);
                            services.put(so);
                        }
                        JSONObject res = new JSONObject();
                        res.put("services", services);
                        finishConnect(connectId, res.toString());
                    } catch (Exception e) {
                        failConnect("Lecture des services impossible");
                    }
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            final byte[] v = c.getValue();
            if (v == null) return;
            final byte[] copy = v.clone();
            final BluetoothGattCharacteristic cc = c;
            ui.post(new Runnable() {
                public void run() {
                    int idx = chars.indexOf(cc);
                    if (idx >= 0) emitValue(idx, copy);
                }
            });
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            ui.post(new Runnable() { public void run() { opDone(true); } });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            ui.post(new Runnable() { public void run() { opDone(true); } });
        }
    };

    /* ------------------------------------------------------------ opérations */

    private void enqueue(Op op) {
        queue.add(op);
        if (cur == null) nextOp();
    }

    private void nextOp() {
        cur = queue.poll();
        if (cur == null) return;
        watchdog = new Runnable() { public void run() { opDone(true); } };
        ui.postDelayed(watchdog, 2000);
        cur.start();
    }

    private void opDone(boolean ok) {
        if (cur == null) return;
        ui.removeCallbacks(watchdog);
        Op o = cur;
        cur = null;
        if (ok) resolve(o.id, "null");
        else reject(o.id, "Échec de l'opération Bluetooth");
        nextOp();
    }

    private void doNotify(final int id, final int idx) {
        if (sock != null) {
            resolve(id, "null");
            return;
        }
        if (gatt == null || idx < 0 || idx >= chars.size()) {
            reject(id, "Non connecté");
            return;
        }
        final BluetoothGattCharacteristic c = chars.get(idx);
        Op op = new Op() {
            void start() {
                try {
                    gatt.setCharacteristicNotification(c, true);
                    BluetoothGattDescriptor d = c.getDescriptor(CCC);
                    if (d == null) { opDone(true); return; }
                    boolean indicate = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0;
                    d.setValue(indicate ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    if (!gatt.writeDescriptor(d)) opDone(false);
                } catch (Exception e) {
                    opDone(false);
                }
            }
        };
        op.id = id;
        enqueue(op);
    }

    private void doWrite(final int id, final int idx, String b64, final boolean noResp) {
        final byte[] data;
        try {
            data = Base64.decode(b64, Base64.DEFAULT);
        } catch (Exception e) {
            reject(id, "Données invalides");
            return;
        }
        if (sock != null && sockOut != null) {
            final OutputStream out = sockOut;
            new Thread(new Runnable() {
                public void run() {
                    boolean ok = true;
                    try { out.write(data); out.flush(); } catch (IOException e) { ok = false; }
                    final boolean fok = ok;
                    ui.post(new Runnable() {
                        public void run() {
                            if (fok) resolve(id, "null"); else reject(id, "Écriture impossible");
                        }
                    });
                }
            }).start();
            return;
        }
        if (gatt == null || idx < 0 || idx >= chars.size()) {
            reject(id, "Non connecté");
            return;
        }
        final BluetoothGattCharacteristic c = chars.get(idx);
        Op op = new Op() {
            void start() {
                try {
                    c.setWriteType(noResp ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    c.setValue(data);
                    if (!gatt.writeCharacteristic(c)) opDone(false);
                } catch (Exception e) {
                    opDone(false);
                }
            }
        };
        op.id = id;
        enqueue(op);
    }

    private void closeAll(boolean unused) {
        stopScan();
        if (connectTimeout != null) ui.removeCallbacks(connectTimeout);
        if (leFallback != null) ui.removeCallbacks(leFallback);
        if (watchdog != null) ui.removeCallbacks(watchdog);
        queue.clear();
        cur = null;
        BluetoothGatt g = gatt;
        gatt = null;
        if (g != null) {
            try { g.disconnect(); } catch (Exception ignored) { }
            try { g.close(); } catch (Exception ignored) { }
        }
        BluetoothSocket s = sock;
        sock = null;
        sockOut = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
        chars.clear();
    }
}

package fr.sandero.dashboard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Position GPS de l'autoradio : trajets favoris (départ et arrivée) et tracé des trajets sur la carte. Rien n'est envoyé ailleurs : la position reste dans l'appli.
 */
class GeoLink implements LocationListener {
    static final int REQ_PERM = 52;
    private final Activity act;
    private LocationManager lm;
    private Location best;
    private boolean on;
    /** numéro de la dernière position reçue : l'appli sait ainsi si une position est nouvelle, même si l'horloge de l'autoradio est fausse */
    private long seq;
    /* toutes les positions GPS reçues (1 par seconde), gardées 3 minutes : l'appli les relit toutes, aucune n'est perdue */
    private final java.util.ArrayDeque<double[]> fixes = new java.util.ArrayDeque<>();
    private long fixSeq;
    /* satellites (Android 7+) : combien sont vus, combien servent à la position, force moyenne du signal */
    private volatile int satSeen = -1, satUsed = -1;
    private volatile float cn0 = 0;
    private Object gnssCb;

    GeoLink(Activity act) {
        this.act = act;
    }

    boolean hasPerm() {
        return Build.VERSION.SDK_INT < 23
                || act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || act.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    void askPerm() {
        if (hasPerm()) {
            start();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            act.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERM);
        }
    }

    @SuppressWarnings("MissingPermission")
    void start() {
        if (on || !hasPerm()) return;
        try {
            lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            /* 0 ms : chaque position que la puce GPS calcule (1 par seconde en général, parfois plus) */
            try { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, Looper.getMainLooper()); } catch (Exception ignored) {}
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    GnssStatus.Callback cb = new GnssStatus.Callback() {
                        @Override public void onSatelliteStatusChanged(GnssStatus st) {
                            int n = st.getSatelliteCount(), u = 0; float[] c = new float[n]; int k = 0;
                            for (int i = 0; i < n; i++) if (st.usedInFix(i)) { u++; c[k++] = st.getCn0DbHz(i); }
                            java.util.Arrays.sort(c, 0, k);
                            float sum = 0; int m = Math.min(4, k);
                            for (int i = 0; i < m; i++) sum += c[k - 1 - i];
                            satSeen = n; satUsed = u; cn0 = m > 0 ? sum / m : 0;
                        }
                        @Override public void onStopped() { satUsed = 0; }
                    };
                    lm.registerGnssStatusCallback(cb);
                    gnssCb = cb;
                } catch (Exception ignored) {}
            }
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                keep(l);
                if (LocationManager.NETWORK_PROVIDER.equals(p)) {
                    lm.requestLocationUpdates(p, LocationManager.GPS_PROVIDER.equals(p) ? 1000L : 3000L, 0f, this, Looper.getMainLooper());
                }
            }
            on = true;
        } catch (Exception ignored) {
        }
    }

    void stop() {
        try {
            if (lm != null) lm.removeUpdates(this);
            if (lm != null && gnssCb != null && Build.VERSION.SDK_INT >= 24) lm.unregisterGnssStatusCallback((GnssStatus.Callback) gnssCb);
        } catch (Exception ignored) {
        }
        on = false;
    }

    /** âge réel d'une position, en ms, mesuré sur l'horloge interne (fiable même si la date de l'autoradio est fausse) */
    private static long ageMs(Location l) {
        if (Build.VERSION.SDK_INT >= 17 && l.getElapsedRealtimeNanos() > 0) {
            return Math.max(0, (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1000000L);
        }
        return Math.max(0, System.currentTimeMillis() - l.getTime());
    }

    private void keep(Location l) {
        if (l == null) return;
        boolean gps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
        boolean bestGps = best != null && LocationManager.GPS_PROVIDER.equals(best.getProvider());
        if (best == null) { best = l; seq++; return; }
        long a = ageMs(l), b = ageMs(best);
        /* une position GPS récente (moins de 6 s) n'est jamais remplacée par une position réseau, moins précise */
        if (!gps && bestGps && b < 6000L) return;
        if (a + 1000L < b || (a <= b + 15000L && l.getAccuracy() < best.getAccuracy()) || gps && !bestGps) {
            best = l;
            seq++;
        }
    }

    /** {"perm":true,"ok":true,"lat":..,"lon":..,"acc":..,"age":secondes} */
    String status() {
        StringBuilder s = new StringBuilder("{\"perm\":").append(hasPerm());
        if (!on && hasPerm()) start();
        if (best != null) {
            long age = ageMs(best) / 1000;
            s.append(",\"ok\":true,\"lat\":").append(best.getLatitude())
                    .append(",\"lon\":").append(best.getLongitude())
                    .append(",\"acc\":").append(Math.round(best.getAccuracy()))
                    .append(",\"age\":").append(age)
                    .append(",\"seq\":").append(seq)
                    .append(",\"gps\":").append(LocationManager.GPS_PROVIDER.equals(best.getProvider()));
        } else {
            s.append(",\"ok\":false");
        }
        s.append(",\"sat\":").append(satSeen).append(",\"used\":").append(satUsed).append(",\"cn0\":").append(Math.round(cn0));
        return s.append('}').toString();
    }

    @Override
    public void onLocationChanged(Location l) {
        keep(l);
        if (l != null && LocationManager.GPS_PROVIDER.equals(l.getProvider())) {
            synchronized (fixes) {
                fixSeq++;
                long el = Build.VERSION.SDK_INT >= 17 && l.getElapsedRealtimeNanos() > 0 ? l.getElapsedRealtimeNanos() / 1000000L : SystemClock.elapsedRealtime();
                fixes.addLast(new double[]{fixSeq, l.getLatitude(), l.getLongitude(), l.hasAccuracy() ? l.getAccuracy() : 99,
                        l.hasSpeed() ? l.getSpeed() : -1, el, l.hasBearing() ? l.getBearing() : -1});
                while (fixes.size() > 600) fixes.removeFirst();
            }
        }
    }

    /** positions GPS reçues après le numéro « after » : [[n,lat,lon,précision m,vitesse m/s,horloge ms,cap °],…] */
    String batch(long after) {
        StringBuilder b = new StringBuilder("[");
        synchronized (fixes) {
            for (double[] f : fixes) {
                if ((long) f[0] <= after) continue;
                if (b.length() > 1) b.append(',');
                b.append('[').append((long) f[0]).append(',').append(f[1]).append(',').append(f[2]).append(',')
                        .append(Math.round(f[3] * 10) / 10.0).append(',').append(Math.round(f[4] * 10) / 10.0).append(',').append((long) f[5]).append(',').append(Math.round(f[6] * 10) / 10.0).append(']');
            }
        }
        return b.append(']').toString();
    }

    @Override
    public void onStatusChanged(String p, int st, Bundle b) {
    }

    /* GPS activé après le lancement de l'appli : on s'y abonne aussi */
    @Override
    @SuppressWarnings("MissingPermission")
    public void onProviderEnabled(String p) {
        try {
            if (lm != null && hasPerm() && (LocationManager.GPS_PROVIDER.equals(p) || LocationManager.NETWORK_PROVIDER.equals(p))) {
                lm.requestLocationUpdates(p, LocationManager.GPS_PROVIDER.equals(p) ? 0L : 3000L, 0f, this, Looper.getMainLooper());
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onProviderDisabled(String p) {
    }
}

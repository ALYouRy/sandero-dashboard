package fr.sandero.dashboard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

/**
 * Position GPS de l'autoradio : trajets favoris (départ et arrivée) et tracé des trajets sur la carte. Rien n'est envoyé ailleurs : la position reste dans l'appli.
 */
class GeoLink implements LocationListener {
    static final int REQ_PERM = 52;
    private final Activity act;
    private LocationManager lm;
    private Location best;
    private boolean on;

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
            for (String p : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(p);
                keep(l);
                if (LocationManager.GPS_PROVIDER.equals(p) || LocationManager.NETWORK_PROVIDER.equals(p)) {
                    lm.requestLocationUpdates(p, 3000L, 10f, this, Looper.getMainLooper());
                }
            }
            on = true;
        } catch (Exception ignored) {
        }
    }

    void stop() {
        try {
            if (lm != null) lm.removeUpdates(this);
        } catch (Exception ignored) {
        }
        on = false;
    }

    private void keep(Location l) {
        if (l == null) return;
        if (best == null || l.getTime() > best.getTime() + 15000L
                || (l.getTime() >= best.getTime() - 15000L && l.getAccuracy() < best.getAccuracy())) {
            best = l;
        }
    }

    /** {"perm":true,"ok":true,"lat":..,"lon":..,"acc":..,"age":secondes} */
    String status() {
        StringBuilder s = new StringBuilder("{\"perm\":").append(hasPerm());
        if (!on && hasPerm()) start();
        if (best != null) {
            long age = Math.max(0, (System.currentTimeMillis() - best.getTime()) / 1000);
            s.append(",\"ok\":true,\"lat\":").append(best.getLatitude())
                    .append(",\"lon\":").append(best.getLongitude())
                    .append(",\"acc\":").append(Math.round(best.getAccuracy()))
                    .append(",\"age\":").append(age);
        } else {
            s.append(",\"ok\":false");
        }
        return s.append('}').toString();
    }

    @Override
    public void onLocationChanged(Location l) {
        keep(l);
    }

    @Override
    public void onStatusChanged(String p, int st, Bundle b) {
    }

    @Override
    public void onProviderEnabled(String p) {
    }

    @Override
    public void onProviderDisabled(String p) {
    }
}

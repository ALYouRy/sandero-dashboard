package fr.sandero.dashboard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Ouvre le tableau de bord quand l'autoradio démarre (réglage « Ouvrir au démarrage »).
 * Sur Android 10 et plus, il faut aussi l'autorisation « Afficher par-dessus les autres
 * applications », sinon Android bloque l'ouverture. Certains autoradios se mettent en veille
 * au lieu de s'éteindre : dans ce cas l'appli est déjà ouverte au réveil.
 */
public class BootReceiver extends BroadcastReceiver {
    static final String PREFS = "sandero";
    static final String KEY_BOOT = "autoboot";

    @Override
    public void onReceive(Context c, Intent in) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!p.getBoolean(KEY_BOOT, true)) return;
        try {
            Intent i = new Intent(c, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            i.putExtra("boot", true);
            c.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}

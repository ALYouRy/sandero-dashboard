package fr.sandero.dashboard;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 5.5 : petite bulle native qui affiche la conso par-dessus CarPlay.
 * Pas de page web derrière : trois textes changés une fois par seconde, rien d'autre.
 * Touche = retour dans l'appli ; glisser = déplacer (la place est gardée).
 */
class ConsoBubble {
    private final Activity act;
    private final WindowManager wm;
    private final SharedPreferences sp;
    private LinearLayout box;
    private TextView big, unit, sub;
    private WindowManager.LayoutParams lp;
    private boolean shown;
    private String last = "";

    ConsoBubble(Activity a) {
        act = a;
        wm = (WindowManager) a.getSystemService(Activity.WINDOW_SERVICE);
        sp = a.getSharedPreferences("bubble", Activity.MODE_PRIVATE);
    }

    boolean can() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(act);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, act.getResources().getDisplayMetrics()));
    }

    private void build() {
        if (box != null) return;
        box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(14), dp(8), dp(14), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(235, 12, 14, 24));
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(1), Color.argb(60, 255, 255, 255));
        box.setBackground(bg);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        big = new TextView(act);
        big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextColor(Color.WHITE);
        big.setIncludeFontPadding(false);
        unit = new TextView(act);
        unit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        unit.setTypeface(Typeface.DEFAULT_BOLD);
        unit.setTextColor(Color.argb(190, 255, 255, 255));
        unit.setPadding(dp(6), 0, 0, dp(4));
        row.addView(big);
        row.addView(unit);
        sub = new TextView(act);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        sub.setTextColor(Color.argb(170, 255, 255, 255));
        sub.setPadding(0, dp(2), 0, 0);
        box.addView(row);
        box.addView(sub);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = sp.getInt("x", dp(16));
        lp.y = sp.getInt("y", dp(90));

        box.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; moved = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) moved = true;
                        if (moved) {
                            lp.x = Math.max(0, ox + (int) dx); lp.y = Math.max(0, oy + (int) dy);
                            try { wm.updateViewLayout(box, lp); } catch (Exception ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) sp.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                        else open();
                        return true;
                }
                return false;
            }
        });
    }

    private void open() {
        try {
            Intent i = new Intent(act, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            act.startActivity(i);
        } catch (Exception ignored) { }
    }

    /** valeur, unité, ligne du dessous (vide = cachée), couleur #rrggbb */
    void set(String v, String u, String s, String color) {
        if (!can()) return;
        build();
        String key = v + "|" + u + "|" + s + "|" + color;
        if (!key.equals(last)) {
            last = key;
            big.setText(v);
            unit.setText(u);
            sub.setText(s);
            sub.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
            try { big.setTextColor(Color.parseColor(color)); } catch (Exception e) { big.setTextColor(Color.WHITE); }
        }
        if (!shown) {
            try { wm.addView(box, lp); shown = true; } catch (Exception ignored) { }
        }
    }

    void hide() {
        if (!shown) return;
        try { wm.removeView(box); } catch (Exception ignored) { }
        shown = false;
    }
}

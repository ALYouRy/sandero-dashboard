package fr.sandero.dashboard;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Sauvegarde des trajets, pleins, réglages… dans Téléchargements/Sandero.
 * Ce dossier n'est pas effacé quand on désinstalle l'appli : on peut restaurer après une réinstallation.
 * Deux fichiers : « sandero-sauvegarde.json » (toujours le plus récent) et une copie par mois.
 */
class Backup {
    static final int REQ_PICK = 63;
    static final int REQ_PERM = 64;
    private static final String DIR = "Sandero";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Écrit les deux fichiers. Renvoie "ok" ou "err:raison". */
    static String write(Activity act, String json, String monthTag) {
        try {
            byte[] data = json.getBytes(UTF8);
            write1(act, "sandero-sauvegarde.json", data);
            if (monthTag != null && monthTag.length() > 0) write1(act, "sandero-sauvegarde-" + monthTag + ".json", data);
            return "ok";
        } catch (SecurityException e) {
            return "err:perm";
        } catch (Exception e) {
            return "err:" + e.getMessage();
        }
    }

    private static void write1(Activity act, String name, byte[] data) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver cr = act.getContentResolver();
            Uri col = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String rel = Environment.DIRECTORY_DOWNLOADS + "/" + DIR + "/";
            Uri uri = null;
            Cursor c = null;
            try {
                c = cr.query(col, new String[]{MediaStore.MediaColumns._ID},
                        MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                        new String[]{name, rel}, null);
                if (c != null && c.moveToFirst()) uri = Uri.withAppendedPath(col, String.valueOf(c.getLong(0)));
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
            if (uri == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
                uri = cr.insert(col, v);
            }
            if (uri == null) throw new Exception("création du fichier impossible");
            OutputStream o = cr.openOutputStream(uri, "wt");
            if (o == null) throw new Exception("écriture impossible");
            try { o.write(data); } finally { o.close(); }
        } else {
            if (act.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                act.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
                throw new SecurityException("perm");
            }
            File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR);
            if (!d.exists() && !d.mkdirs()) throw new Exception("dossier impossible à créer");
            FileOutputStream o = new FileOutputStream(new File(d, name));
            try { o.write(data); } finally { o.close(); }
        }
    }

    /** Ouvre le sélecteur de fichiers d'Android pour choisir une sauvegarde. */
    static void pick(Activity act) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        act.startActivityForResult(i, REQ_PICK);
    }

    static String read(Activity act, Uri uri) throws Exception {
        InputStream in = act.getContentResolver().openInputStream(uri);
        if (in == null) throw new Exception("lecture impossible");
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
                if (b.size() > 30 * 1024 * 1024) throw new Exception("fichier trop gros");
            }
            return new String(b.toByteArray(), UTF8);
        } finally {
            in.close();
        }
    }
}

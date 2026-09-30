package dev.voidwager.wallhunt;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Every wallpaper Wallhunt sets, newest last, so Revert can step back one set at a time.
 * Before Wallhunt first touches a screen it also tries to save the wallpaper already there
 * (an "original"). Android 13+ may refuse that read, in which case history starts at Wallhunt's first set.
 */
final class History {
    static final int HOME = WallpaperManager.FLAG_SYSTEM, LOCK = WallpaperManager.FLAG_LOCK;
    private static final int[] SCREENS = {HOME, LOCK};
    private static final int MAX = 15;

    private static final class Entry {
        final int which;
        final String file;
        final boolean original;

        Entry(int which, String file, boolean original) {
            this.which = which;
            this.file = file;
            this.original = original;
        }
    }

    private final WallpaperManager wm;
    private final SharedPreferences prefs;
    private final File dir;
    private final List<Entry> log = new ArrayList<>();

    History(Context ctx, SharedPreferences prefs) {
        this.wm = WallpaperManager.getInstance(ctx);
        this.prefs = prefs;
        this.dir = new File(ctx.getFilesDir(), "history");
        dir.mkdirs();
        try {
            JSONArray a = new JSONArray(prefs.getString("history", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Entry e = new Entry(o.getInt("which"), o.getString("file"), o.optBoolean("original"));
                if (new File(dir, e.file).exists()) log.add(e);
            }
        } catch (JSONException e) {
            log.clear();
        }
    }

    /** Sets {@code jpeg} on {@code which}, saving what was there first so it can be reverted. */
    synchronized void set(byte[] jpeg, int which) throws IOException {
        for (int s : SCREENS) {
            if ((which & s) != 0 && latest(s, log.size()) == null) {
                byte[] now = readCurrent(s);
                if (now != null) add(s, now, true);
            }
        }
        try (InputStream in = new java.io.ByteArrayInputStream(jpeg)) {
            wm.setStream(in, null, true, which);
        }
        add(which, jpeg, false);
    }

    /** True when a screen in {@code which} has never been set by Wallhunt, so its original isn't saved yet. */
    synchronized boolean firstTouch(int which) {
        for (int s : SCREENS) if ((which & s) != 0 && latest(s, log.size()) == null) return true;
        return false;
    }

    /** True when the newest set has an earlier wallpaper to go back to on at least one of its screens. */
    synchronized boolean canRevert() {
        if (log.isEmpty()) return false;
        Entry last = log.get(log.size() - 1);
        for (int s : SCREENS) {
            if ((last.which & s) != 0 && latest(s, log.size() - 1) != null) return true;
        }
        return false;
    }

    /** Undoes the newest set. Returns a sentence saying what was restored. */
    synchronized String revert() throws IOException {
        if (!canRevert()) return "Nothing earlier to go back to.";
        Entry last = log.remove(log.size() - 1);
        List<String> done = new ArrayList<>(), stuck = new ArrayList<>();
        for (int s : SCREENS) {
            if ((last.which & s) == 0) continue;
            Entry prev = latest(s, log.size());
            if (prev == null) { stuck.add(name(s)); continue; }
            try (InputStream in = new FileInputStream(new File(dir, prev.file))) {
                wm.setStream(in, null, true, s);
            }
            done.add(name(s) + (prev.original ? " (your original)" : ""));
        }
        new File(dir, last.file).delete();
        save();
        String msg = "Reverted " + String.join(" and ", done) + ".";
        if (!stuck.isEmpty()) msg += " No earlier " + String.join("/", stuck) + " wallpaper was saved.";
        return msg;
    }

    private Entry latest(int screen, int before) {
        for (int i = before - 1; i >= 0; i--) if ((log.get(i).which & screen) != 0) return log.get(i);
        return null;
    }

    /** The wallpaper on {@code screen} right now, or null if Android won't let us read it. */
    private byte[] readCurrent(int screen) {
        try {
            ParcelFileDescriptor pfd = wm.getWallpaperFile(screen);
            // A lock screen without its own wallpaper shows the home one.
            if (pfd == null && screen == LOCK) pfd = wm.getWallpaperFile(HOME);
            if (pfd == null) return null;
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] b = new byte[64 * 1024];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                return out.size() > 0 ? out.toByteArray() : null;
            }
        } catch (SecurityException | IOException e) {
            return null;
        }
    }

    private void add(int which, byte[] data, boolean original) throws IOException {
        String file = System.currentTimeMillis() + "-" + which + (original ? "-orig" : "") + ".img";
        try (FileOutputStream out = new FileOutputStream(new File(dir, file))) {
            out.write(data);
        }
        log.add(new Entry(which, file, original));
        // Trim the oldest Wallhunt sets; originals stay so a full revert is always possible.
        for (int i = 0; log.size() > MAX && i < log.size(); ) {
            if (log.get(i).original) { i++; continue; }
            new File(dir, log.remove(i).file).delete();
        }
        save();
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : log) {
                a.put(new JSONObject().put("which", e.which).put("file", e.file).put("original", e.original));
            }
        } catch (JSONException ignored) {
            // put() only throws on non-finite numbers
        }
        prefs.edit().putString("history", a.toString()).apply();
    }

    private static String name(int screen) {
        return screen == HOME ? "home screen" : "lock screen";
    }
}

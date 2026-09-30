package dev.voidwager.wallhunt;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every wallpaper Wallhunt sets, newest last, so Revert can step back one set at a time and the
 * History screen can offer them again. Before Wallhunt first touches a screen it also tries to save the
 * wallpaper already there (an "original"). Android 13+ may refuse that read, in which case history starts at
 * Wallhunt's first set.
 */
final class History {
    static final int HOME = WallpaperManager.FLAG_SYSTEM, LOCK = WallpaperManager.FLAG_LOCK;
    private static final int[] SCREENS = {HOME, LOCK};
    private static final int MAX = 30;

    private static final class Entry {
        final int which;
        final String file, hash, prompt, page;
        final long time;
        final boolean original;

        Entry(int which, String file, String hash, String prompt, String page, long time, boolean original) {
            this.which = which;
            this.file = file;
            this.hash = hash;
            this.prompt = prompt;
            this.page = page;
            this.time = time;
            this.original = original;
        }
    }

    /** One distinct wallpaper for the History screen: every set of the same image, merged. */
    static final class Item {
        File file;
        String hash, prompt = "", page = "";
        long time;
        int screens;
        boolean original;
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
        boolean upgraded = false;
        try {
            JSONArray a = new JSONArray(prefs.getString("history", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                File f = new File(dir, o.getString("file"));
                if (!f.exists()) continue;
                String hash = o.optString("hash");
                if (hash.isEmpty()) {           // entries saved by 1.1/1.2 carry no hash
                    hash = hash(Files.readAllBytes(f.toPath()));
                    upgraded = true;
                }
                log.add(new Entry(o.getInt("which"), f.getName(), hash, o.optString("prompt"), o.optString("page"),
                        o.optLong("time", f.lastModified()), o.optBoolean("original")));
            }
        } catch (JSONException | IOException e) {
            log.clear();
        }
        if (upgraded) save();
    }

    /** Sets {@code jpeg} on {@code which}, saving what was there first so it can be reverted. */
    synchronized void set(byte[] jpeg, int which, String prompt, String page) throws IOException {
        for (int s : SCREENS) {
            if ((which & s) != 0 && latest(s, log.size()) == null) {
                byte[] now = readCurrent(s);
                if (now != null) add(s, now, "", "", true);
            }
        }
        try (InputStream in = new ByteArrayInputStream(jpeg)) {
            wm.setStream(in, null, true, which);
        }
        add(which, jpeg, prompt, page, false);
    }

    /** Sets a History item again. It becomes the newest entry, so Revert undoes it like any other set. */
    synchronized void reuse(Item item, int which) throws IOException {
        set(Files.readAllBytes(item.file.toPath()), which, item.prompt, item.page);
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
        Entry last = log.get(log.size() - 1);
        removeAt(log.size() - 1);
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
        save();
        String msg = "Reverted " + String.join(" and ", done) + ".";
        if (!stuck.isEmpty()) msg += " No earlier " + String.join("/", stuck) + " wallpaper was saved.";
        return msg;
    }

    /** Distinct wallpapers, newest first. */
    synchronized List<Item> items() {
        Map<String, Item> byHash = new LinkedHashMap<>();
        for (int i = log.size() - 1; i >= 0; i--) {
            Entry e = log.get(i);
            Item it = byHash.get(e.hash);
            if (it == null) {
                it = new Item();
                it.file = new File(dir, e.file);
                it.hash = e.hash;
                it.time = e.time;
                byHash.put(e.hash, it);
            }
            it.screens |= e.which;
            it.original |= e.original;
            if (it.prompt.isEmpty()) it.prompt = e.prompt;
            if (it.page.isEmpty()) it.page = e.page;
        }
        return new ArrayList<>(byHash.values());
    }

    /** Forgets every set of this image. Revert then skips it. */
    synchronized void delete(Item item) {
        for (int i = log.size() - 1; i >= 0; i--) {
            if (log.get(i).hash.equals(item.hash)) new File(dir, log.remove(i).file).delete();
        }
        save();
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
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[64 * 1024];
                for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
                return out.size() > 0 ? out.toByteArray() : null;
            }
        } catch (SecurityException | IOException e) {
            return null;
        }
    }

    private void add(int which, byte[] data, String prompt, String page, boolean original) throws IOException {
        long now = System.currentTimeMillis();
        String hash = hash(data);
        // The same image already on disk (set before, or on another screen) shares its file.
        String file = null;
        for (Entry e : log) if (e.hash.equals(hash)) { file = e.file; break; }
        if (file == null) {
            file = now + "-" + which + (original ? "-orig" : "") + ".img";
            try (FileOutputStream out = new FileOutputStream(new File(dir, file))) {
                out.write(data);
            }
        }
        log.add(new Entry(which, file, hash, prompt == null ? "" : prompt, page == null ? "" : page, now, original));
        // Trim the oldest Wallhunt sets; originals stay so a full revert is always possible.
        for (int i = 0; log.size() > MAX && i < log.size(); ) {
            if (log.get(i).original) { i++; continue; }
            removeAt(i);
        }
        save();
    }

    /** Drops one entry, deleting its file only when no other entry still uses it. */
    private void removeAt(int i) {
        Entry gone = log.remove(i);
        for (Entry e : log) if (e.file.equals(gone.file)) return;
        new File(dir, gone.file).delete();
    }

    private void save() {
        JSONArray a = new JSONArray();
        try {
            for (Entry e : log) {
                a.put(new JSONObject().put("which", e.which).put("file", e.file).put("hash", e.hash)
                        .put("prompt", e.prompt).put("page", e.page).put("time", e.time).put("original", e.original));
            }
        } catch (JSONException ignored) {
            // put() only throws on non-finite numbers
        }
        prefs.edit().putString("history", a.toString()).apply();
    }

    private static String hash(byte[] data) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String name(int screen) {
        return screen == HOME ? "home screen" : "lock screen";
    }
}

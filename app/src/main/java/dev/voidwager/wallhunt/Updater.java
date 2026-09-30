package dev.voidwager.wallhunt;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * In-place updates from this repo's GitHub releases. Besides Wallhaven and Claude, the only network calls:
 * one GET to api.github.com at most every 6 h while the app is open, and the APK download the user asks
 * for. The downloaded APK must carry the same package name, a higher version and the same signing
 * certificate as the installed app, or it is refused — so an update always replaces the app, keeping its
 * data, and never needs an uninstall.
 */
final class Updater {
    static final String REPO = "voidwager/wallhunt";
    static final long CHECK_EVERY_MS = 6 * 3600_000L;
    private static final long RETRY_AFTER_FAIL_MS = 3600_000L;

    static final class Release {
        String version = "", url = "", notes = "";
        long size;
    }

    private Updater() {}

    static boolean enabled(SharedPreferences p) { return p.getBoolean("upd_on", true); }

    static boolean due(SharedPreferences p) {
        return enabled(p) && System.currentTimeMillis() - p.getLong("upd_at", 0) > CHECK_EVERY_MS;
    }

    static String installedVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Numeric, dot-wise compare of "1.2", "v1.10.0" etc. */
    static int compare(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\."), y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int yi = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    /** The saved latest release, if it is newer than what is installed. */
    static Release available(Context c, SharedPreferences p) {
        String v = p.getString("upd_ver", null);
        if (v == null || compare(v, installedVersion(c)) <= 0) return null;
        Release r = new Release();
        r.version = v;
        r.url = p.getString("upd_url", "");
        r.size = p.getLong("upd_size", 0);
        r.notes = p.getString("upd_notes", "");
        return r.url.isEmpty() ? null : r;
    }

    /** Blocking: asks GitHub for the latest release and saves it. Returns null on success, else the reason. */
    static String check(SharedPreferences p) {
        long now = System.currentTimeMillis();
        try {
            HttpURLConnection h = open("https://api.github.com/repos/" + REPO + "/releases/latest");
            h.setRequestProperty("Accept", "application/vnd.github+json");
            if (h.getResponseCode() != 200) throw new IOException("GitHub answered " + h.getResponseCode());
            JSONObject j = new JSONObject(readAll(h.getInputStream()));
            Release r = new Release();
            r.version = j.optString("tag_name", "").replaceFirst("^v", "");
            r.notes = plain(j.optString("body", ""));
            JSONArray assets = j.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) {
                    r.url = a.optString("browser_download_url");
                    r.size = a.optLong("size");
                    break;
                }
            }
            p.edit().putString("upd_ver", r.version).putString("upd_url", r.url).putLong("upd_size", r.size)
                    .putString("upd_notes", r.notes).putLong("upd_at", now).remove("upd_err").apply();
            return null;
        } catch (Exception e) {
            // offline or rate-limited: try again in an hour rather than in six
            p.edit().putLong("upd_at", now - CHECK_EVERY_MS + RETRY_AFTER_FAIL_MS)
                    .putString("upd_err", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).apply();
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /** Blocking: downloads the release APK into the cache, reporting 0–100. */
    static File download(Context c, Release r, IntConsumer progress) throws IOException {
        File out = new File(c.getCacheDir(), "update.apk");
        HttpURLConnection h = open(r.url);
        if (h.getResponseCode() != 200) throw new IOException("download answered " + h.getResponseCode());
        long total = h.getContentLengthLong() > 0 ? h.getContentLengthLong() : r.size;
        try (InputStream in = h.getInputStream(); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int n, last = -1;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                got += n;
                int pct = total > 0 ? (int) (got * 100 / total) : 0;
                if (pct != last) progress.accept(last = pct);
            }
        }
        return out;
    }

    /** Null when the APK is a genuine newer build of this app, else why it isn't. */
    static String verify(Context c, File apk) {
        PackageManager pm = c.getPackageManager();
        PackageInfo a = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (a == null) return "the download isn't a valid app";
        if (!c.getPackageName().equals(a.packageName)) return "the download is a different app";
        try {
            PackageInfo me = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (a.getLongVersionCode() <= me.getLongVersionCode()) return "the download isn't newer than this version";
            if (a.signingInfo == null || me.signingInfo == null
                    || !sameSigners(a.signingInfo.getApkContentsSigners(), me.signingInfo.getApkContentsSigners()))
                return "the download is signed with a different key";
        } catch (PackageManager.NameNotFoundException e) {
            return "can't read this app's own signature";
        }
        return null;
    }

    private static boolean sameSigners(Signature[] x, Signature[] y) {
        if (x == null || y == null || x.length != y.length) return false;
        for (int i = 0; i < x.length; i++) if (!Arrays.equals(x[i].toByteArray(), y[i].toByteArray())) return false;
        return true;
    }

    /** Hands the APK to Android's installer; it asks the user to confirm, then replaces the app in place. */
    static void install(Context c, File apk) throws IOException {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        sp.setAppPackageName(c.getPackageName());
        int id = pi.createSession(sp);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream o = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                s.fsync(o);
            }
            Intent status = new Intent(c, InstallReceiver.class).setAction(InstallReceiver.ACTION);
            PendingIntent pend = PendingIntent.getBroadcast(c, id, status,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            s.commit(pend.getIntentSender());
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(20_000);
        h.setInstanceFollowRedirects(true);
        h.setRequestProperty("User-Agent", "Wallhunt-updater");
        return h;
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream i = in; ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    /** Release notes as plain text: markdown emphasis, headings, links and list markers stripped, first ~500 chars. */
    private static String plain(String md) {
        String s = md.replaceAll("\\[([^\\]]+)\\]\\([^)]*\\)", "$1").replaceAll("(?m)^#+\\s*", "")
                .replaceAll("(?m)^\\s*[-*]\\s+", "• ").replaceAll("\\*\\*|__|`|\\*", "").replaceAll("\\r", "").trim();
        return s.length() > 500 ? s.substring(0, s.lastIndexOf(' ', 500)) + "…" : s;
    }
}

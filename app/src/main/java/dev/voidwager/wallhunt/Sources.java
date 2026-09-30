package dev.voidwager.wallhunt;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The image sources Wallhunt searches. Wallhaven and Openverse need no key; Unsplash and Pexels need a free one
 * and are skipped without it. Every source is asked for portrait images only.
 */
final class Sources {
    static final String WALLHAVEN = "wallhaven", UNSPLASH = "unsplash", PEXELS = "pexels", OPENVERSE = "openverse";
    static final String[] ALL = {WALLHAVEN, UNSPLASH, PEXELS, OPENVERSE};

    /** One candidate image, whatever site it came from. */
    static final class Wall {
        String id, source, page, full, thumb, resolution, credit = "", creditUrl = "", ping = "";
        int favorites;
    }

    private Sources() {}

    static String name(String source) {
        switch (source) {
            case UNSPLASH: return "Unsplash";
            case PEXELS: return "Pexels";
            case OPENVERSE: return "Openverse";
            default: return "Wallhaven";
        }
    }

    /** What each source is good at, for Claude's choice. */
    static String strength(String source) {
        switch (source) {
            case UNSPLASH: return "high-end photography: nature, cities, minimal, moody";
            case PEXELS: return "broad stock photography: nature, cities, textures, everyday scenes";
            case OPENVERSE: return "Creative Commons images: space, museum art, public-domain photos; quality varies";
            default: return "wallpaper community: digital art, anime, games, fantasy, abstract, some photos";
        }
    }

    static boolean needsKey(String source) { return source.equals(UNSPLASH) || source.equals(PEXELS); }

    /**
     * Runs every query on one source and keeps up to {@code max} distinct portrait images at least
     * {@code minW}x{@code minH}. {@code key} is ignored by sources that don't need one.
     */
    static List<Wall> search(String source, String key, List<String> queries, int minW, int minH, int max)
            throws IOException {
        Map<String, Wall> seen = new LinkedHashMap<>();
        int perQuery = Math.max(2, (max + queries.size() - 1) / queries.size() + 1);
        for (String q : queries) {
            try {
                for (Wall w : query(source, key, q, minW, minH, perQuery)) {
                    if (seen.size() >= max) break;
                    seen.putIfAbsent(w.id, w);
                }
            } catch (JSONException e) {
                throw new IOException(name(source) + " sent an unreadable reply", e);
            }
            if (seen.size() >= max) break;
        }
        return new ArrayList<>(seen.values());
    }

    private static List<Wall> query(String source, String key, String q, int minW, int minH, int n)
            throws IOException, JSONException {
        String enc = URLEncoder.encode(q, "UTF-8");
        List<Wall> out = new ArrayList<>();
        switch (source) {
            case WALLHAVEN: {
                JSONArray data = json(get("https://wallhaven.cc/api/v1/search?q=" + enc
                        + "&categories=110&purity=100&ratios=portrait&sorting=relevance"
                        + "&atleast=" + minW + "x" + minH, null)).getJSONArray("data");
                for (int i = 0; i < data.length() && out.size() < n; i++) {
                    JSONObject o = data.getJSONObject(i);
                    Wall w = wall(WALLHAVEN, o.optString("id"), o.optString("url"), o.optString("path"),
                            o.getJSONObject("thumbs").optString("original"), o.optString("resolution"));
                    w.favorites = o.optInt("favorites");
                    out.add(w);
                }
                break;
            }
            case UNSPLASH: {
                JSONArray res = json(get("https://api.unsplash.com/search/photos?query=" + enc
                        + "&orientation=portrait&content_filter=high&per_page=" + n,
                        "Client-ID " + key)).getJSONArray("results");
                for (int i = 0; i < res.length(); i++) {
                    JSONObject o = res.getJSONObject(i);
                    if (o.optInt("width") < minW || o.optInt("height") < minH) continue;
                    JSONObject urls = o.getJSONObject("urls"), links = o.getJSONObject("links"),
                            user = o.getJSONObject("user");
                    String utm = "?utm_source=wallhunt&utm_medium=referral";
                    Wall w = wall(UNSPLASH, o.optString("id"), links.optString("html") + utm,
                            urls.optString("raw") + "&h=" + Math.max(minH, 2400) + "&fm=jpg&q=85",
                            urls.optString("small"), o.optInt("width") + "x" + o.optInt("height"));
                    w.credit = "Photo by " + user.optString("name") + " on Unsplash";
                    w.creditUrl = user.getJSONObject("links").optString("html") + utm;
                    w.ping = links.optString("download_location",
                            "https://api.unsplash.com/photos/" + o.optString("id") + "/download");
                    out.add(w);
                }
                break;
            }
            case PEXELS: {
                JSONArray res = json(get("https://api.pexels.com/v1/search?query=" + enc
                        + "&orientation=portrait&per_page=" + n, key)).getJSONArray("photos");
                for (int i = 0; i < res.length(); i++) {
                    JSONObject o = res.getJSONObject(i);
                    if (o.optInt("width") < minW || o.optInt("height") < minH) continue;
                    JSONObject src = o.getJSONObject("src");
                    Wall w = wall(PEXELS, String.valueOf(o.optLong("id")), o.optString("url"),
                            src.optString("original") + "?auto=compress&cs=tinysrgb&h=" + Math.max(minH, 2400),
                            src.optString("medium"), o.optInt("width") + "x" + o.optInt("height"));
                    w.credit = "Photo by " + o.optString("photographer") + " on Pexels";
                    w.creditUrl = o.optString("photographer_url");
                    out.add(w);
                }
                break;
            }
            case OPENVERSE: {
                JSONArray res = json(get("https://api.openverse.org/v1/images/?q=" + enc
                        + "&aspect_ratio=tall&size=large&mature=false&page_size=" + Math.max(n, 5), null))
                        .getJSONArray("results");
                for (int i = 0; i < res.length() && out.size() < n; i++) {
                    JSONObject o = res.getJSONObject(i);
                    if (o.optInt("width") < minW || o.optInt("height") < minH) continue;
                    Wall w = wall(OPENVERSE, o.optString("id"), o.optString("foreign_landing_url"),
                            o.optString("url"), o.optString("thumbnail"), o.optInt("width") + "x" + o.optInt("height"));
                    String lic = o.optString("license").toUpperCase(java.util.Locale.ROOT) + " "
                            + o.optString("license_version");
                    String by = o.optString("creator");
                    w.credit = (by.isEmpty() || by.equals("null") ? "Unknown creator" : by) + " · " + lic.trim()
                            + " · via Openverse";
                    w.creditUrl = o.optString("foreign_landing_url");
                    out.add(w);
                }
                break;
            }
            default:
                break;
        }
        return out;
    }

    /** Unsplash's guidelines ask apps to report each photo actually used. Failure doesn't matter. */
    static void reportUse(Wall w, String unsplashKey) {
        if (!UNSPLASH.equals(w.source) || w.ping.isEmpty() || unsplashKey.isEmpty()) return;
        try {
            get(w.ping, "Client-ID " + unsplashKey);
        } catch (IOException ignored) {
            // an uncounted download is harmless
        }
    }

    private static Wall wall(String source, String id, String page, String full, String thumb, String res) {
        Wall w = new Wall();
        w.source = source;
        w.id = source + ":" + id;
        w.page = page;
        // Android blocks plain http; the hosts behind these links all serve https too.
        w.full = full.replaceFirst("^http://", "https://");
        w.thumb = thumb.replaceFirst("^http://", "https://");
        w.resolution = res;
        return w;
    }

    private static JSONObject json(byte[] body) throws JSONException {
        return new JSONObject(new String(body, StandardCharsets.UTF_8));
    }

    static byte[] get(String url) throws IOException { return get(url, null); }

    private static byte[] get(String url, String auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "Wallhunt/1.4 (Android)");
        if (auth != null) c.setRequestProperty("Authorization", auth);
        if (url.startsWith("https://api.unsplash.com")) c.setRequestProperty("Accept-Version", "v1");
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("key rejected (HTTP " + code + ")");
            if (code == 429) throw new IOException("rate limit reached, try later");
            if (code != 200) throw new IOException("HTTP " + code + " from " + new URL(url).getHost());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[64 * 1024];
                for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
                return buf.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}

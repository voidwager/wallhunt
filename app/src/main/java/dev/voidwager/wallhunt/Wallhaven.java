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

/** Wallhaven's public search API. SFW only, so no API key is needed. */
final class Wallhaven {
    static final class Wall {
        final String id, page, full, thumb, resolution;
        final int favorites;

        Wall(JSONObject o) throws JSONException {
            id = o.optString("id");
            page = o.optString("url");
            full = o.optString("path");
            thumb = o.getJSONObject("thumbs").optString("original");
            resolution = o.optString("resolution");
            favorites = o.optInt("favorites");
        }
    }

    private Wallhaven() {}

    /** Runs each query, keeps the first {@code perQuery} hits of each, drops duplicates, stops at {@code max}. */
    static List<Wall> search(List<String> queries, int minW, int minH, int perQuery, int max) throws IOException {
        Map<String, Wall> seen = new LinkedHashMap<>();
        for (String q : queries) {
            String url = "https://wallhaven.cc/api/v1/search?q=" + URLEncoder.encode(q, "UTF-8")
                    + "&categories=110&purity=100&ratios=portrait&sorting=relevance"
                    + "&atleast=" + minW + "x" + minH;
            try {
                JSONArray data = new JSONObject(new String(get(url), StandardCharsets.UTF_8)).getJSONArray("data");
                for (int i = 0; i < data.length() && i < perQuery; i++) {
                    Wall w = new Wall(data.getJSONObject(i));
                    seen.putIfAbsent(w.id, w);
                }
            } catch (JSONException e) {
                throw new IOException("Wallhaven sent an unreadable reply", e);
            }
            if (seen.size() >= max) break;
        }
        List<Wall> out = new ArrayList<>(seen.values());
        return out.size() > max ? out.subList(0, max) : out;
    }

    static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "Wallhunt/1.0 (Android)");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " from " + url);
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

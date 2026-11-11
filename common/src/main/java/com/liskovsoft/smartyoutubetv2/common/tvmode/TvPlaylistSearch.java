package com.liskovsoft.smartyoutubetv2.common.tvmode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube's search for playlists, asked the way the YouTube TV app asks it. The app's own search
 * returns videos only: its reader has no entry for the playlist, show and course tiles this
 * search answers with. Needs no account and no key.<br/>
 * Blocks: call from a worker thread.
 */
final class TvPlaylistSearch {
    private static final String URL = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false";
    /** YouTube's search filter "Type: Playlist". */
    private static final String PLAYLISTS = "EgIQAw==";
    private static final int TIMEOUT_MS = 10_000;
    private static final Pattern COUNT = Pattern.compile("\"(?:text|content)\":\"([\\d,]+)\\s*(?:episodes|videos|lessons|episode|video)\"");

    private TvPlaylistSearch() {
    }

    /**
     * Playlists for a search. Shows and courses come first: those are the ones YouTube itself
     * keeps complete and in order, where a playlist is whatever somebody put together.
     */
    static List<TvChannel.Series> find(String query) throws Exception {
        JSONObject client = new JSONObject();
        client.put("clientName", "TVHTML5");
        client.put("clientVersion", "7.20250714.16.00");
        client.put("hl", "en");
        JSONObject body = new JSONObject();
        body.put("context", new JSONObject().put("client", client));
        body.put("query", query);
        body.put("params", PLAYLISTS);

        HttpURLConnection connection = (HttpURLConnection) new URL(URL).openConnection();
        String answer;

        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version");

            OutputStream out = connection.getOutputStream();
            out.write(body.toString().getBytes("UTF-8"));
            out.close();

            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("HTTP " + connection.getResponseCode());
            }

            answer = read(connection.getInputStream());
        } finally {
            connection.disconnect();
        }

        List<JSONObject> tiles = new ArrayList<>();
        collect(new JSONObject(answer), tiles);

        List<TvChannel.Series> official = new ArrayList<>();
        List<TvChannel.Series> others = new ArrayList<>();

        for (JSONObject tile : tiles) {
            String type = tile.optString("contentType", "");
            String id = tile.optString("contentId", "");
            boolean kept = type.endsWith("_SHOW") || type.endsWith("_COURSE");

            if (id.isEmpty() || (!kept && !type.endsWith("_PLAYLIST"))) {
                continue;
            }

            TvChannel.Series one = new TvChannel.Series();
            one.playlistId = id;
            JSONObject metadata = tile.optJSONObject("metadata");
            JSONObject lockup = metadata != null ? metadata.optJSONObject("lockupMetadataViewModel") : null;

            if (lockup != null) {
                JSONObject title = lockup.optJSONObject("title");
                one.title = title != null ? title.optString("content", "") : "";
                one.author = firstPart(lockup);
            }

            Matcher count = COUNT.matcher(tile.toString());
            if (count.find()) {
                try {
                    one.count = Integer.parseInt(count.group(1).replace(",", ""));
                } catch (NumberFormatException e) {
                    one.count = 0;
                }
            }

            if (!one.title.isEmpty()) {
                (kept ? official : others).add(one);
            }
        }

        official.addAll(others);
        return official;
    }

    /** The first text under a tile's title: who made the playlist. */
    private static String firstPart(JSONObject lockup) {
        JSONObject metadata = lockup.optJSONObject("metadata");
        JSONObject content = metadata != null ? metadata.optJSONObject("contentMetadataViewModel") : null;
        JSONArray rows = content != null ? content.optJSONArray("metadataRows") : null;

        for (int i = 0; rows != null && i < rows.length(); i++) {
            JSONArray parts = rows.optJSONObject(i) != null ? rows.optJSONObject(i).optJSONArray("metadataParts") : null;

            for (int j = 0; parts != null && j < parts.length(); j++) {
                JSONObject text = parts.optJSONObject(j) != null ? parts.optJSONObject(j).optJSONObject("text") : null;
                String value = text != null ? text.optString("content", "") : "";
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }

        return "";
    }

    /** Every "lockupViewModel" in the answer, wherever it sits: the layout around them changes. */
    private static void collect(Object node, List<JSONObject> out) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject tile = object.optJSONObject("lockupViewModel");

            if (tile != null) {
                out.add(tile);
                return;
            }

            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                collect(object.opt(keys.next()), out);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collect(array.opt(i), out);
            }
        }
    }

    private static String read(InputStream stream) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16_384];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        } finally {
            stream.close();
        }
    }
}

package com.liskovsoft.smartyoutubetv2.common.tvmode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube's official Data API, with the viewer's own key. TV mode works without it. With a key
 * it learns what a search result doesn't say (the language the uploader set, the description,
 * tags, exact views and length, age restriction), can search as another country, and reads
 * YouTube's most popular chart.<br/>
 * A key gets 10,000 units a day. Details of 50 videos cost 1, a chart page costs 1, a search
 * costs 100. The count is kept here and nothing is sent once the day's units are gone.<br/>
 * Every request blocks: call from a worker thread.
 */
final class TvDataApi {
    static final int DAILY_UNITS = 10_000;
    private static final int COST_SEARCH = 100;
    private static final String BASE = "https://www.googleapis.com/youtube/v3/";
    private static final int TIMEOUT_MS = 10_000;
    private static final Pattern DURATION = Pattern.compile("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?");

    /** What the API says about one video. */
    static final class Facts {
        String id;
        String title;
        String author;
        String channelId;
        String description = "";
        String imageUrl;
        /** Tags and description in lower case, for looking up words. */
        String text = "";
        /** The spoken language the uploader set, as a two or three letter code. Empty when not set or not speech. */
        String language = "";
        long durationMs;
        /** When it was uploaded. 0 when unknown. */
        long publishedMs;
        /** YouTube's own category number: 10 music, 17 sports, 20 gaming, 25 news... 0 when unknown. */
        int category;
        long views = -1;

        /** Views for each day since the upload: how fast a video is spreading. -1 when it can't be told. */
        long viewsPerDay() {
            if (views < 0 || publishedMs <= 0) {
                return -1;
            }

            double days = Math.max(0.25, (System.currentTimeMillis() - publishedMs) / 86_400_000.0);
            return (long) (views / days);
        }
        boolean ageRestricted;
        boolean live;
        boolean upcoming;
    }

    static final class Page {
        final List<String> ids = new ArrayList<>();
        final List<Facts> facts = new ArrayList<>();
        String next;
    }

    private String mKey = "";
    /** The day the count is for, in Pacific time: that is when Google resets it. */
    private String mDay = "";
    private int mUsed;
    private int mSearches;
    /** Google said the day's units are gone. */
    private boolean mSpent;
    /** Google refused the key. Stays until the key changes. */
    private boolean mRefused;
    private String mProblem;

    synchronized void restore(String key, String day, int used, int searches) {
        mKey = key != null ? key.trim() : "";
        mDay = day != null ? day : "";
        mUsed = used;
        mSearches = searches;
        rollDay();
    }

    synchronized void setKey(String key) {
        mKey = key != null ? key.trim() : "";
        mRefused = false;
        mProblem = null;
    }

    synchronized String key() {
        return mKey;
    }

    synchronized String day() {
        return mDay;
    }

    synchronized int used() {
        rollDay();
        return mUsed;
    }

    synchronized int searches() {
        rollDay();
        return mSearches;
    }

    /** Why the key isn't in use, in words for the settings screen, or null. */
    synchronized String problem() {
        return mProblem;
    }

    /** A key is set, Google accepts it and the day has units left. */
    synchronized boolean ready() {
        rollDay();
        return !mKey.isEmpty() && !mRefused && !mSpent && mUsed < DAILY_UNITS;
    }

    /** Searches are the expensive call, so they have their own, smaller allowance. */
    synchronized boolean canSearch(int searchesPerDay) {
        return ready() && mSearches < searchesPerDay && mUsed + COST_SEARCH + 1 <= DAILY_UNITS;
    }

    private void rollDay() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("America/Los_Angeles"));
        String today = format.format(new Date());

        if (!today.equals(mDay)) {
            mDay = today;
            mUsed = 0;
            mSearches = 0;
            mSpent = false;
            if (!mRefused) {
                mProblem = null;
            }
        }
    }

    /** Details of videos by id, 50 to a request. Ids YouTube no longer has are simply missing from the answer. */
    Map<String, Facts> videos(List<String> ids) throws Exception {
        Map<String, Facts> result = new LinkedHashMap<>();

        for (int from = 0; from < ids.size(); from += 50) {
            StringBuilder joined = new StringBuilder();
            for (String id : ids.subList(from, Math.min(ids.size(), from + 50))) {
                joined.append(joined.length() > 0 ? "," : "").append(id);
            }

            JSONObject answer = get("videos?part=snippet,contentDetails,statistics&maxResults=50&id=" + encode(joined.toString()), 1, false);
            JSONArray items = answer.optJSONArray("items");

            for (int i = 0; items != null && i < items.length(); i++) {
                Facts facts = factsOf(items.optJSONObject(i));
                if (facts != null) {
                    result.put(facts.id, facts);
                }
            }
        }

        return result;
    }

    /**
     * A search for videos, as seen from a country. Returns ids only: the titles a search returns
     * are cut short and escaped, so the caller asks {@link #videos} for the rest.
     * @param duration "short", "medium", "long" or null
     * @param order "viewCount", "date" or null for relevance
     */
    Page search(String query, String region, String language, String duration, String order, String pageToken) throws Exception {
        StringBuilder path = new StringBuilder("search?part=id&type=video&maxResults=50&safeSearch=none&q=").append(encode(query));
        append(path, "regionCode", region);
        append(path, "relevanceLanguage", language);
        append(path, "videoDuration", duration);
        append(path, "order", order);
        append(path, "pageToken", pageToken);

        JSONObject answer = get(path.toString(), COST_SEARCH, true);
        Page page = new Page();
        page.next = answer.isNull("nextPageToken") ? null : answer.optString("nextPageToken", null);
        JSONArray items = answer.optJSONArray("items");

        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject id = items.optJSONObject(i) != null ? items.optJSONObject(i).optJSONObject("id") : null;
            String videoId = id != null ? id.optString("videoId", null) : null;
            if (videoId != null && !videoId.isEmpty()) {
                page.ids.add(videoId);
            }
        }

        return page;
    }

    /** The video ids of a playlist, in its order. 1 unit for every 50. */
    List<String> playlist(String playlistId) throws Exception {
        List<String> ids = new ArrayList<>();
        String pageToken = null;

        for (int page = 0; page < 60; page++) {
            StringBuilder path = new StringBuilder("playlistItems?part=contentDetails&maxResults=50&playlistId=").append(encode(playlistId));
            append(path, "pageToken", pageToken);
            JSONObject answer = get(path.toString(), 1, false);
            JSONArray items = answer.optJSONArray("items");

            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject details = items.optJSONObject(i) != null ? items.optJSONObject(i).optJSONObject("contentDetails") : null;
                String videoId = details != null ? details.optString("videoId", "") : "";
                if (!videoId.isEmpty()) {
                    ids.add(videoId);
                }
            }

            pageToken = answer.isNull("nextPageToken") ? null : answer.optString("nextPageToken", null);
            if (pageToken == null) {
                break;
            }
        }

        return ids;
    }

    /** One page of a country's most popular videos right now: what YouTube's Trending page showed. */
    Page popular(String region, String pageToken) throws Exception {
        StringBuilder path = new StringBuilder("videos?part=snippet,contentDetails,statistics&chart=mostPopular&maxResults=50");
        append(path, "regionCode", region);
        append(path, "pageToken", pageToken);

        JSONObject answer = get(path.toString(), 1, false);
        Page page = new Page();
        page.next = answer.isNull("nextPageToken") ? null : answer.optString("nextPageToken", null);
        JSONArray items = answer.optJSONArray("items");

        for (int i = 0; items != null && i < items.length(); i++) {
            Facts facts = factsOf(items.optJSONObject(i));
            if (facts != null) {
                page.facts.add(facts);
            }
        }

        return page;
    }

    private static Facts factsOf(JSONObject item) {
        if (item == null || item.optString("id", "").isEmpty()) {
            return null;
        }

        Facts facts = new Facts();
        facts.id = item.optString("id");
        JSONObject snippet = item.optJSONObject("snippet");

        if (snippet != null) {
            facts.title = snippet.optString("title", null);
            facts.author = snippet.optString("channelTitle", null);
            facts.channelId = snippet.optString("channelId", null);
            facts.description = snippet.optString("description", "");
            String language = snippet.optString("defaultAudioLanguage", "").toLowerCase(Locale.ROOT);
            int dash = language.indexOf('-');
            language = dash > 0 ? language.substring(0, dash) : language;
            facts.language = "zxx".equals(language) || "und".equals(language) ? "" : language; // zxx: no speech
            facts.category = snippet.optInt("categoryId", 0);
            String published = snippet.optString("publishedAt", "");
            if (published.length() >= 19) {
                try {
                    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                    format.setTimeZone(TimeZone.getTimeZone("UTC"));
                    Date date = format.parse(published.substring(0, 19));
                    facts.publishedMs = date != null ? date.getTime() : 0;
                } catch (Throwable e) {
                    facts.publishedMs = 0;
                }
            }
            String broadcast = snippet.optString("liveBroadcastContent", "none");
            facts.live = "live".equals(broadcast);
            facts.upcoming = "upcoming".equals(broadcast);

            JSONObject thumbnails = snippet.optJSONObject("thumbnails");
            JSONObject thumbnail = thumbnails == null ? null : thumbnails.optJSONObject("high") != null ? thumbnails.optJSONObject("high")
                    : thumbnails.optJSONObject("medium") != null ? thumbnails.optJSONObject("medium") : thumbnails.optJSONObject("default");
            facts.imageUrl = thumbnail != null ? thumbnail.optString("url", null) : null;

            StringBuilder text = new StringBuilder(facts.description);
            JSONArray tags = snippet.optJSONArray("tags");
            for (int i = 0; tags != null && i < tags.length(); i++) {
                text.append('\n').append(tags.optString(i, ""));
            }
            facts.text = text.toString().toLowerCase(Locale.ROOT);
        }

        if (facts.imageUrl == null) {
            facts.imageUrl = "https://i.ytimg.com/vi/" + facts.id + "/hqdefault.jpg";
        }

        JSONObject details = item.optJSONObject("contentDetails");

        if (details != null) {
            facts.durationMs = parseDuration(details.optString("duration", ""));
            JSONObject rating = details.optJSONObject("contentRating");
            facts.ageRestricted = rating != null && "ytAgeRestricted".equals(rating.optString("ytRating", ""));
        }

        JSONObject statistics = item.optJSONObject("statistics");

        if (statistics != null && statistics.has("viewCount")) {
            try {
                facts.views = Long.parseLong(statistics.optString("viewCount", "-1"));
            } catch (NumberFormatException e) {
                facts.views = -1;
            }
        }

        return facts;
    }

    /** "PT1H2M3S" in milliseconds. 0 for a stream, which has no length. */
    static long parseDuration(String iso) {
        Matcher matcher = DURATION.matcher(iso != null ? iso : "");

        if (!matcher.matches()) {
            return 0;
        }

        long seconds = 0;
        int[] weights = {86_400, 3_600, 60, 1};

        for (int i = 0; i < weights.length; i++) {
            String part = matcher.group(i + 1);
            if (part != null) {
                seconds += Long.parseLong(part) * weights[i];
            }
        }

        return seconds * 1_000;
    }

    private static void append(StringBuilder path, String name, String value) throws Exception {
        if (value != null && !value.isEmpty()) {
            path.append('&').append(name).append('=').append(encode(value));
        }
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private JSONObject get(String path, int cost, boolean isSearch) throws Exception {
        String key;

        synchronized (this) {
            if (!ready() || mUsed + cost > DAILY_UNITS) {
                throw new IllegalStateException("no key, or the day's units are used up");
            }
            key = mKey;
            // Counted before the answer: Google counts a request that fails too.
            mUsed += cost;
            if (isSearch) {
                mSearches++;
            }
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(BASE + path + "&key=" + encode(key)).openConnection();

        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            String body = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());

            if (status >= 400) {
                String reason = reasonOf(body);

                synchronized (this) {
                    if (reason.contains("quotaExceeded") || reason.contains("dailyLimitExceeded")) {
                        mSpent = true;
                        mProblem = "Today's allowance is used up. It resets at midnight Pacific time.";
                    } else if (status == 400 || status == 401 || (status == 403 && !reason.contains("rateLimit"))) {
                        // A wrong key, a key locked to another app, or a project without the YouTube Data API switched on.
                        mRefused = true;
                        mProblem = "Google refused the key (" + reason + ").";
                    }
                }

                throw new IllegalStateException("HTTP " + status + " " + reason);
            }

            return new JSONObject(body);
        } finally {
            connection.disconnect();
        }
    }

    private static String reasonOf(String body) {
        try {
            JSONObject error = new JSONObject(body).optJSONObject("error");
            JSONArray errors = error != null ? error.optJSONArray("errors") : null;
            JSONObject first = errors != null ? errors.optJSONObject(0) : null;
            String reason = first != null ? first.optString("reason", "") : "";
            return !reason.isEmpty() ? reason : error != null ? error.optString("status", "error") : "error";
        } catch (Throwable e) {
            return "error";
        }
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8_192];
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

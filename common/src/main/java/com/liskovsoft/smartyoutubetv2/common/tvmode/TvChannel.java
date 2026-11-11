package com.liskovsoft.smartyoutubetv2.common.tvmode;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One TV mode channel: what it searches for, and its lineup.<br/>
 * The lineup only grows at the end. {@link #pos} is the video that is on now.
 */
public class TvChannel {
    public static final String ANY = "any";
    public static final String LENGTH_SHORT = "short";   // under 4 min
    public static final String LENGTH_MEDIUM = "medium"; // 4 to 20 min
    public static final String LENGTH_LONG = "long";     // over 20 min
    public static final String UPLOADED_YEAR = "year";
    public static final String UPLOADED_MONTH = "month";
    public static final String UPLOADED_WEEK = "week";
    public static final String UPLOADED_TODAY = "today";
    public static final String ORDER_MIXED = "mixed";
    public static final String ORDER_NEW = "new";
    public static final String ORDER_VIEWS = "views";
    /** Most viewed among this week's uploads. */
    public static final String ORDER_TRENDING = "trending";
    public static final String TYPE_VIDEO = "video";
    /** YouTube's "Movies" search filter. Paid titles come without a video id and are left out. */
    public static final String TYPE_MOVIE = "movie";
    public static final String FEATURE_HD = "hd";
    public static final String FEATURE_4K = "4k";
    public static final String FEATURE_HDR = "hdr";
    public static final String FEATURE_SUBTITLES = "subtitles";
    public static final String FEATURE_CC = "cc";
    public static final String FEATURE_360 = "360";
    public static final String FEATURE_VR180 = "vr180";
    public static final String FEATURE_3D = "3d";
    /** Streams that are live now. They have no length, so each gets a slot of {@link #STREAM_SLOT_MS}. */
    public static final String FEATURE_LIVE = "live";
    public static final String FEATURE_LOCATION = "location";
    public static final String FEATURE_PURCHASED = "purchased";
    public static final long STREAM_SLOT_MS = 30 * 60_000;
    public static final String MODE_DEFAULT = "default";
    public static final String MODE_LIVE = "live";
    public static final String MODE_FREE = "pace";

    /** Program type: playlists played whole, in episode order, like a rerun channel. */
    public static final String KIND_SERIES = "Series";

    /** One series of a rerun channel: a YouTube playlist and how far the channel is into it. */
    public static final class Series {
        public String playlistId;
        /** What the app opens to read the playlist. Kept from the search result that found it. */
        public String pageKey;
        public String title = "";
        public String author = "";
        /** Episodes, as far as known. 0 before the playlist was read. */
        public int count;
        /** The next episode to put in the lineup, counted from 0. */
        public int next;
    }

    public String id;
    public String name;
    /**
     * The kind of program: Movies, Series, Songs... The guide's Type filter goes by it, and
     * picking one in the editor sets the filters that suit it. Empty = not said.
     */
    public String kind = "";
    /** A Series channel's playlists. They take turns, each in its own order. */
    public final List<Series> series = new ArrayList<>();
    /** How many episodes of one series play before the next series has its turn. 0 = the whole series. */
    public int seriesRun = 1;
    /** A series that ends starts again from its first episode. Otherwise it leaves the rotation. */
    public boolean seriesLoop = true;
    /** Whose turn it is in {@link #series}. */
    public int seriesTurn;
    /** For a video in the lineup that is an episode: its playlist id and number, as "id#number". */
    public final Map<String, String> episodes = new HashMap<>();
    public final List<String> terms = new ArrayList<>();
    public final List<String> creators = new ArrayList<>();
    public String length = ANY;
    public String uploaded = ANY;
    public String order = ORDER_MIXED;
    public String type = TYPE_VIDEO;
    /** YouTube's "Features" search filters, as FEATURE_* values. All of them go to YouTube together. */
    public final List<String> features = new ArrayList<>();
    /** Leave out videos shorter than this many minutes. 0 = any. For movie channels, where a search also returns clips. */
    public int minMinutes;
    /** Also search for playlists and take videos out of them. Nothing remembers the playlist afterwards. */
    public boolean playlists;
    /** The group this channel is filed under in the guide's Genre filter. Empty = none. */
    public String genre = "";
    public boolean noShorts = true;
    /** Leave out videos with fewer views than this. 0 = any. */
    public long minViews;
    /**
     * With a YouTube Data API key: also search as this country ("US"), which the app's own search
     * can't do because YouTube goes by where the TV is. Empty = don't.
     */
    public String region = "";
    /** With a YouTube Data API key: also take from this country's most popular chart ("IN"). Empty = don't. */
    public String chart = "";
    /**
     * The channel's era, as two years. A title that names a year and none inside the era is
     * left out. 0 = no era.
     */
    public int yearFrom;
    public int yearTo;
    /** YouTube channel ids of the creators, by name. Looked up once, then kept. */
    public final Map<String, String> creatorIds = new HashMap<>();
    /** How many times in a row each creator was skipped within the first seconds. */
    public final Map<String, Integer> skips = new HashMap<>();
    public final List<String> skipWords = new ArrayList<>();
    public final List<String> blocked = new ArrayList<>();
    /**
     * The channel's language, as one code from TvLanguage. Empty or TvLanguage.ANY = any language:
     * music and ambience channels have no language to go by. A list because the saved file had one.
     */
    public final List<String> languages = new ArrayList<>();
    public String mode = MODE_DEFAULT;
    public boolean fav;
    /** Came from the preset list. */
    public boolean preset;
    /** Changed on this TV. The preset list never overwrites an edited channel. */
    public boolean edited;
    public final List<Video> lineup = new ArrayList<>();
    /**
     * Videos a Live TV channel played while nobody watched. They aren't "watched", so they come
     * back at the end of the lineup later, mixed with new ones.
     */
    public final List<Video> missed = new ArrayList<>();
    public int pos;
    public int termCursor;
    /** Live TV: clock time when the video on now started. 0 = not running yet. */
    public long liveStartMs;
    /** Videos the Data API was asked about and that passed. Saved, so a restart doesn't ask again. */
    public final java.util.Set<String> checked = new java.util.HashSet<>();
    /** Video.toString() doesn't keep the length, so it's kept here by video id. */
    public final Map<String, Long> durations = new HashMap<>();

    // Not saved
    /** The row shown in the guide and used by the player as the playlist. Held here so it can't be collected. */
    public VideoGroup group;
    public boolean loading;
    /** Searches stopped returning new videos, so watched ones are allowed back. */
    public boolean exhausted;
    public int emptyFills;
    /** How many searches this channel ran since the app started. Every few, one asks for recent uploads. */
    public int fills;
    public long lastFillMs;
    /** The video that is playing and when it started, to tell a skip from watching. */
    public String startedId;
    public long startedMs;
    /** When this channel last searched through the Data API and last read the chart. Both are rationed. */
    public long lastApiSearchMs;
    public long lastChartMs;
    /** Callers waiting for the search that is running now. */
    public final List<Runnable> waiters = new ArrayList<>();

    /** Length of a video in the lineup. A guess of 10 minutes when YouTube didn't say. */
    public long durationOf(Video video) {
        Long duration = durations.get(video.videoId);
        return duration != null && duration > 0 ? duration : 10 * 60_000;
    }

    public boolean isSeries() {
        return KIND_SERIES.equalsIgnoreCase(kind);
    }

    public Series findSeries(String playlistId) {
        for (Series one : series) {
            if (one.playlistId != null && one.playlistId.equals(playlistId)) {
                return one;
            }
        }

        return null;
    }

    /** The series an episode in the lineup belongs to, or null for a video that isn't one. */
    public Series seriesOf(String videoId) {
        String mark = videoId != null ? episodes.get(videoId) : null;
        int hash = mark != null ? mark.lastIndexOf('#') : -1;
        return hash > 0 ? findSeries(mark.substring(0, hash)) : null;
    }

    /** An episode's number in its series, counted from 0, or -1 for a video that isn't one. */
    public int episodeOf(String videoId) {
        String mark = videoId != null ? episodes.get(videoId) : null;
        int hash = mark != null ? mark.lastIndexOf('#') : -1;

        try {
            return hash > 0 ? Integer.parseInt(mark.substring(hash + 1)) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Search terms and YouTube channels both feed the lineup. With playlists on, each term is also searched for playlists. */
    public int sources() {
        return Math.max(1, terms.size() + creators.size() + (playlists ? terms.size() : 0));
    }

    /** The channel's language code, or "" when it plays any language. */
    public String language() {
        return languages.isEmpty() || languages.contains(TvLanguage.ANY) ? "" : languages.get(0);
    }

    public boolean hasFeature(String feature) {
        return features.contains(feature);
    }

    public Video onNow() {
        return pos >= 0 && pos < lineup.size() ? lineup.get(pos) : null;
    }

    public int ahead() {
        return lineup.size() - pos - 1;
    }

    public int indexOf(String videoId) {
        if (videoId == null) {
            return -1;
        }

        for (int i = 0; i < lineup.size(); i++) {
            if (videoId.equals(lineup.get(i).videoId)) {
                return i;
            }
        }

        return -1;
    }

    /** The part of a channel that decides what it plays. A change here means the coming-up lineup is stale. */
    public String searchKey() {
        return terms + "|" + creators + "|" + length + "|" + uploaded + "|" + order + "|" + features + "|" + noShorts + "|" + skipWords + "|" + blocked + "|" + minViews + "|" + languages
                + "|" + type + "|" + minMinutes + "|" + playlists + (isSeries() ? "|series" + seriesIds() + seriesRun : "")
                + (yearTo > 0 ? "|years" + yearFrom + "-" + yearTo : "");
    }

    private String seriesIds() {
        StringBuilder ids = new StringBuilder();
        for (Series one : series) {
            ids.append(one.playlistId).append(',');
        }
        return ids.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("kind", kind);
        o.put("seriesRun", seriesRun);
        o.put("seriesLoop", seriesLoop);
        o.put("seriesTurn", seriesTurn);
        JSONArray seriesArray = new JSONArray();
        for (Series one : series) {
            JSONObject item = new JSONObject();
            item.put("id", one.playlistId);
            item.put("key", one.pageKey);
            item.put("title", one.title);
            item.put("author", one.author);
            item.put("count", one.count);
            item.put("next", one.next);
            seriesArray.put(item);
        }
        o.put("series", seriesArray);
        o.put("terms", new JSONArray(terms));
        o.put("creators", new JSONArray(creators));
        o.put("length", length);
        o.put("uploaded", uploaded);
        o.put("order", order);
        o.put("type", type);
        o.put("features", new JSONArray(features));
        o.put("minMinutes", minMinutes);
        o.put("playlists", playlists);
        o.put("genre", genre);
        o.put("noShorts", noShorts);
        o.put("minViews", minViews);
        o.put("region", region);
        o.put("chart", chart);
        o.put("years", new JSONArray().put(yearFrom).put(yearTo));
        o.put("creatorIds", new JSONObject(creatorIds));
        o.put("skips", new JSONObject(skips));
        o.put("skipWords", new JSONArray(skipWords));
        o.put("blocked", new JSONArray(blocked));
        o.put("mode", mode);
        o.put("languages", new JSONArray(languages));
        o.put("fav", fav);
        o.put("preset", preset);
        o.put("edited", edited);
        o.put("pos", pos);
        o.put("termCursor", termCursor);
        o.put("liveStartMs", liveStartMs);

        o.put("lineup", videosToJson(lineup));
        o.put("missed", videosToJson(missed));

        return o;
    }

    private JSONArray videosToJson(List<Video> videos) throws JSONException {
        JSONArray items = new JSONArray();

        for (Video video : videos) {
            JSONObject item = new JSONObject();
            item.put("v", video.toString());
            item.put("a", video.author);
            Long duration = durations.get(video.videoId);
            item.put("d", duration != null ? duration : -1);
            if (checked.contains(video.videoId)) {
                item.put("k", 1);
            }
            if (episodes.containsKey(video.videoId)) {
                item.put("e", episodes.get(video.videoId));
            }
            items.put(item);
        }

        return items;
    }

    private void videosFromJson(JSONArray items, List<Video> out) {
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            Video video = item != null ? parseVideo(item.optString("v", null)) : null;
            if (video == null || video.videoId == null) {
                continue;
            }
            video.author = item.isNull("a") ? null : item.optString("a", null);
            video.tvChannelId = id;
            long duration = item.optLong("d", -1);
            if (duration > 0) {
                durations.put(video.videoId, duration);
            }
            if (item.optInt("k", 0) == 1) {
                checked.add(video.videoId);
            }
            if (!item.isNull("e")) {
                episodes.put(video.videoId, item.optString("e", ""));
            }
            out.add(video);
        }
    }

    private static final String FIELD_END = "&vi;";

    /**
     * Reads a video saved by Video.toString(). Video.fromString() does the same but builds a
     * search pattern for every number it reads. With some 200 channels that took over five
     * seconds on the TV, on the main thread, and Android closed the app for not answering.
     * This reads only the fields a lineup uses, by position, with no patterns.
     */
    private static Video parseVideo(String text) {
        if (text == null) {
            return null;
        }

        List<String> fields = new ArrayList<>(24);
        int from = 0;
        int at;

        while ((at = text.indexOf(FIELD_END, from)) != -1) {
            fields.add(text.substring(from, at));
            from = at + FIELD_END.length();
        }

        fields.add(text.substring(from));

        if (fields.size() < 20) {
            return Video.fromString(text); // an unexpected shape: the slow way knows more of them
        }

        Video video = new Video();
        video.title = field(fields, 2);
        video.videoId = field(fields, 3);
        video.channelId = field(fields, 6);
        video.bgImageUrl = field(fields, 7);
        video.cardImageUrl = field(fields, 8);
        video.secondTitle = field(fields, 14);
        video.previewUrl = field(fields, 15);
        video.badge = field(fields, 19);
        video.isLive = "true".equals(field(fields, 20));
        return video;
    }

    private static String field(List<String> fields, int index) {
        String value = index < fields.size() ? fields.get(index) : null;
        return value == null || value.isEmpty() || "null".equals(value) ? null : value;
    }

    /** How many coming-up videos are saved for a channel. The rest is found again by the next search. */
    private static final int SAVED_AHEAD = 30;
    private static final int SAVED_MISSED = 20;

    /**
     * A copy with its own lists, for saving on another thread while this one keeps changing.
     * The videos themselves are shared: they aren't changed once they are in a lineup.
     */
    public TvChannel copyForSave() {
        TvChannel c = new TvChannel();
        c.id = id;
        c.name = name;
        c.kind = kind;
        c.seriesRun = seriesRun;
        c.seriesLoop = seriesLoop;
        c.seriesTurn = seriesTurn;
        for (Series one : series) {
            Series copy = new Series();
            copy.playlistId = one.playlistId;
            copy.pageKey = one.pageKey;
            copy.title = one.title;
            copy.author = one.author;
            copy.count = one.count;
            copy.next = one.next;
            c.series.add(copy);
        }
        c.episodes.putAll(episodes);
        c.terms.addAll(terms);
        c.creators.addAll(creators);
        c.length = length;
        c.uploaded = uploaded;
        c.order = order;
        c.type = type;
        c.features.addAll(features);
        c.minMinutes = minMinutes;
        c.playlists = playlists;
        c.genre = genre;
        c.noShorts = noShorts;
        c.minViews = minViews;
        c.region = region;
        c.chart = chart;
        c.yearFrom = yearFrom;
        c.yearTo = yearTo;
        c.creatorIds.putAll(creatorIds);
        c.skips.putAll(skips);
        c.skipWords.addAll(skipWords);
        c.blocked.addAll(blocked);
        c.mode = mode;
        c.languages.addAll(languages);
        c.fav = fav;
        c.preset = preset;
        c.edited = edited;
        // Not everything: a long saved lineup on every channel made the file slow to read back.
        c.lineup.addAll(lineup.subList(0, Math.min(lineup.size(), Math.max(0, pos) + 1 + SAVED_AHEAD)));
        c.missed.addAll(missed.subList(Math.max(0, missed.size() - SAVED_MISSED), missed.size()));
        c.pos = pos;
        c.termCursor = termCursor;
        c.liveStartMs = liveStartMs;
        c.durations.putAll(durations);
        c.checked.addAll(checked);
        return c;
    }

    public static TvChannel fromJson(JSONObject o) {
        TvChannel c = new TvChannel();
        c.id = o.optString("id", null);
        c.name = o.optString("name", "");
        c.readSettings(o);
        c.fav = o.optBoolean("fav", false);
        c.preset = o.optBoolean("preset", false);
        c.edited = o.optBoolean("edited", false);
        c.termCursor = o.optInt("termCursor", 0);
        c.liveStartMs = o.optLong("liveStartMs", 0);

        JSONObject ids = o.optJSONObject("creatorIds");
        if (ids != null) {
            for (java.util.Iterator<String> it = ids.keys(); it.hasNext();) {
                String key = it.next();
                c.creatorIds.put(key, ids.optString(key, null));
            }
        }

        JSONObject skips = o.optJSONObject("skips");
        if (skips != null) {
            for (java.util.Iterator<String> it = skips.keys(); it.hasNext();) {
                String key = it.next();
                c.skips.put(key, skips.optInt(key, 0));
            }
        }

        c.videosFromJson(o.optJSONArray("lineup"), c.lineup);
        c.videosFromJson(o.optJSONArray("missed"), c.missed);

        c.pos = Math.max(0, Math.min(o.optInt("pos", 0), Math.max(0, c.lineup.size() - 1)));

        return c;
    }

    /** Reads the search settings. Used for saved channels and for the preset list. */
    public void readSettings(JSONObject o) {
        kind = o.optString("kind", "");
        seriesRun = o.optInt("seriesRun", 1);
        seriesLoop = o.optBoolean("seriesLoop", true);
        seriesTurn = o.optInt("seriesTurn", seriesTurn);
        readSeries(o.optJSONArray("series"));
        readList(o.optJSONArray("terms"), terms);
        readList(o.optJSONArray("creators"), creators);
        length = o.optString("length", ANY);
        uploaded = o.optString("uploaded", ANY);
        order = o.optString("order", ORDER_MIXED);
        type = o.optString("type", TYPE_VIDEO);
        readList(o.optJSONArray("features"), features);
        if (FEATURE_4K.equals(o.optString("quality", ANY)) && !features.contains(FEATURE_4K)) {
            features.add(FEATURE_4K); // the setting this list replaced
        }
        minMinutes = o.optInt("minMinutes", 0);
        playlists = o.optBoolean("playlists", false);
        genre = o.optString("genre", "");
        noShorts = o.optBoolean("noShorts", true);
        minViews = o.optLong("minViews", 0);
        region = o.optString("region", "");
        chart = o.optString("chart", "");
        JSONArray years = o.optJSONArray("years");
        yearFrom = years != null ? years.optInt(0, 0) : 0;
        yearTo = years != null ? years.optInt(1, 0) : 0;
        readList(o.optJSONArray("skipWords"), skipWords);
        readList(o.optJSONArray("blocked"), blocked);
        mode = o.optString("mode", MODE_DEFAULT);
        readList(o.optJSONArray("languages"), languages);
    }

    /**
     * The channel's playlists. A series that is already known keeps its place: the preset list
     * names the playlists, and how far this TV is into each stays with the TV.
     */
    private void readSeries(JSONArray array) {
        List<Series> before = new ArrayList<>(series);
        series.clear();

        for (int i = 0; array != null && i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            String playlistId = item != null ? item.optString("id", "") : "";

            if (playlistId.isEmpty()) {
                continue;
            }

            Series one = new Series();
            one.playlistId = playlistId;
            one.pageKey = item.isNull("key") ? null : item.optString("key", null);
            one.title = item.optString("title", "");
            one.author = item.optString("author", "");
            one.count = item.optInt("count", 0);
            one.next = item.optInt("next", 0);

            for (Series known : before) {
                if (playlistId.equals(known.playlistId) && !item.has("next")) {
                    one.next = known.next;
                    one.count = Math.max(one.count, known.count);
                }
            }

            series.add(one);
        }
    }

    private static void readList(JSONArray array, List<String> out) {
        out.clear();

        if (array == null) {
            return;
        }

        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, null);
            if (value != null && !value.trim().isEmpty()) {
                out.add(value.trim());
            }
        }
    }
}

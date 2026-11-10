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
    public static final String ORDER_MIXED = "mixed";
    public static final String ORDER_NEW = "new";
    public static final String ORDER_VIEWS = "views";
    public static final String QUALITY_4K = "4k";
    public static final String MODE_DEFAULT = "default";

    public String id;
    public String name;
    public final List<String> terms = new ArrayList<>();
    public final List<String> creators = new ArrayList<>();
    public String length = ANY;
    public String uploaded = ANY;
    public String order = ORDER_MIXED;
    public String quality = ANY;
    public boolean noShorts = true;
    public final List<String> skipWords = new ArrayList<>();
    public final List<String> blocked = new ArrayList<>();
    public String mode = MODE_DEFAULT;
    public boolean fav;
    /** Came from the preset list. */
    public boolean preset;
    /** Changed on this TV. The preset list never overwrites an edited channel. */
    public boolean edited;
    public final List<Video> lineup = new ArrayList<>();
    public int pos;
    public int termCursor;
    /** Video.toString() doesn't keep the length, so it's kept here by video id. */
    public final Map<String, Long> durations = new HashMap<>();

    // Not saved
    /** The row shown in the guide and used by the player as the playlist. Held here so it can't be collected. */
    public VideoGroup group;
    public boolean loading;
    /** Searches stopped returning new videos, so watched ones are allowed back. */
    public boolean exhausted;
    public int emptyFills;
    /** Callers waiting for the search that is running now. */
    public final List<Runnable> waiters = new ArrayList<>();

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
        return terms + "|" + creators + "|" + length + "|" + uploaded + "|" + order + "|" + quality + "|" + noShorts + "|" + skipWords + "|" + blocked;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("terms", new JSONArray(terms));
        o.put("creators", new JSONArray(creators));
        o.put("length", length);
        o.put("uploaded", uploaded);
        o.put("order", order);
        o.put("quality", quality);
        o.put("noShorts", noShorts);
        o.put("skipWords", new JSONArray(skipWords));
        o.put("blocked", new JSONArray(blocked));
        o.put("mode", mode);
        o.put("fav", fav);
        o.put("preset", preset);
        o.put("edited", edited);
        o.put("pos", pos);
        o.put("termCursor", termCursor);

        JSONArray items = new JSONArray();
        for (Video video : lineup) {
            JSONObject item = new JSONObject();
            item.put("v", video.toString());
            item.put("a", video.author);
            Long duration = durations.get(video.videoId);
            item.put("d", duration != null ? duration : -1);
            items.put(item);
        }
        o.put("lineup", items);

        return o;
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

        JSONArray items = o.optJSONArray("lineup");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                Video video = item != null ? Video.fromString(item.optString("v", null)) : null;
                if (video == null || video.videoId == null) {
                    continue;
                }
                video.author = item.isNull("a") ? null : item.optString("a", null);
                video.tvChannelId = c.id;
                long duration = item.optLong("d", -1);
                if (duration > 0) {
                    c.durations.put(video.videoId, duration);
                }
                c.lineup.add(video);
            }
        }

        c.pos = Math.max(0, Math.min(o.optInt("pos", 0), Math.max(0, c.lineup.size() - 1)));

        return c;
    }

    /** Reads the search settings. Used for saved channels and for the preset list. */
    public void readSettings(JSONObject o) {
        readList(o.optJSONArray("terms"), terms);
        readList(o.optJSONArray("creators"), creators);
        length = o.optString("length", ANY);
        uploaded = o.optString("uploaded", ANY);
        order = o.optString("order", ORDER_MIXED);
        quality = o.optString("quality", ANY);
        noShorts = o.optBoolean("noShorts", true);
        readList(o.optJSONArray("skipWords"), skipWords);
        readList(o.optJSONArray("blocked"), blocked);
        mode = o.optString("mode", MODE_DEFAULT);
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

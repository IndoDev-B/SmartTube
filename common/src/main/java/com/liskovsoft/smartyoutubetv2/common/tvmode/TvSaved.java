package com.liskovsoft.smartyoutubetv2.common.tvmode;

import org.json.JSONObject;

/**
 * A program kept outside any channel's lineup: one left part-way ("Continue watching"), or one
 * put aside from the hold OK menu ("My list"). It carries what a card needs, since the channel
 * it came from may have moved on, or be gone, by the time it is picked.
 */
public class TvSaved {
    public String videoId;
    public String title = "";
    public String author = "";
    public String image;
    /** The TV channel it was on, to go back to when it ends. */
    public String channelId;
    public String channelName = "";
    public long durationMs;
    /** Where it was left. 0 = not started. */
    public long positionMs;
    public long atMs;
    /**
     * Set when it was played from a playlist: the card stands for the playlist, and videoId is the
     * video of it played last. Playlists stay until taken off by hand: on shuffle there is no last video.
     */
    public String playlistId;
    public String playlistTitle = "";
    /** The last video's place in the playlist, from 0. -1 = not known. */
    public int playlistIndex = -1;
    public int playlistSize = -1;
    /** The last video played was seen to its end. */
    public boolean finished;

    public boolean isPlaylist() {
        return playlistId != null;
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", videoId);
        o.put("title", title);
        o.put("author", author);
        o.put("image", image);
        o.put("channel", channelId);
        o.put("channelName", channelName);
        o.put("duration", durationMs);
        o.put("position", positionMs);
        o.put("at", atMs);
        if (playlistId != null) {
            o.put("playlist", playlistId);
            o.put("playlistTitle", playlistTitle);
            o.put("playlistIndex", playlistIndex);
            o.put("playlistSize", playlistSize);
            o.put("finished", finished);
        }
        return o;
    }

    public static TvSaved fromJson(JSONObject o) {
        TvSaved saved = new TvSaved();
        saved.videoId = o.isNull("id") ? null : o.optString("id", null);
        saved.title = o.optString("title", "");
        saved.author = o.optString("author", "");
        saved.image = o.isNull("image") ? null : o.optString("image", null);
        saved.channelId = o.isNull("channel") ? null : o.optString("channel", null);
        saved.channelName = o.optString("channelName", "");
        saved.durationMs = o.optLong("duration", 0);
        saved.positionMs = o.optLong("position", 0);
        saved.atMs = o.optLong("at", 0);
        saved.playlistId = o.isNull("playlist") ? null : o.optString("playlist", null);
        saved.playlistTitle = o.optString("playlistTitle", "");
        saved.playlistIndex = o.optInt("playlistIndex", -1);
        saved.playlistSize = o.optInt("playlistSize", -1);
        saved.finished = o.optBoolean("finished", false);
        return saved;
    }
}

package com.liskovsoft.smartyoutubetv2.common.tvmode;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV mode: channels that each play a lineup of YouTube search results.<br/>
 * Channels, lineups and your place in them are kept on the TV. Nothing is sent anywhere.<br/>
 * <br/>
 * Threads: channels and lineups are only touched on the main thread. The worker thread does the
 * network and file work and posts its results back.
 */
public class TvModeService {
    private static final String TAG = TvModeService.class.getSimpleName();
    /** Sidebar section id. Below SidebarService.RESERVED_ID and not used by MediaGroup.TYPE_*. */
    public static final int SECTION_ID = 50;
    public static final String ACTION_SETTINGS = "@settings";
    public static final String ACTION_SHUFFLE_ALL = "@shuffle_all";
    public static final String ACTION_NEW_CHANNEL = "@new_channel";
    public static final String ACTION_GENRE = "@genre";
    public static final String ACTION_LANGUAGE = "@language";
    public static final String ACTION_FAVOURITES = "@favourites";
    /** Marks a video played by the Random button. It belongs to no channel. */
    public static final String RANDOM = "@random";
    /** Marks a Live TV program played once, outside its channel's schedule. */
    public static final String ONCE = "@once";
    private String mOnceChannelId;
    public static final String FX_STATIC = "static";
    public static final String FX_BLACK = "black";
    public static final String FX_NONE = "none";
    public static final String FX_VHS = "vhs";
    public static final String OPEN_GUIDE = "guide";
    public static final String OPEN_LAST = "play";
    private static final String STATE_FILE = "tv_mode_state.json";
    private static final String CONFIG_CACHE_FILE = "tv_mode_config.json";
    private static final long CONFIG_CHECK_PERIOD_MS = 5 * 60 * 1_000;
    private static final int MAX_PAST = 20;
    private static final int PAST_SHOWN = 8;
    private static final int MAX_MISSED = 80;
    /** Channels at the top of the guide get their first videos before the rest. */
    private static final int GUIDE_FIRST = 8;
    /** Under the player's controls: this many channels, each with this many videos. More made scrolling there drag. */
    private static final int PLAYER_ROWS = 6;
    private static final int PLAYER_ROW_VIDEOS = 8;
    private static final long TOP_UP_PERIOD_MS = 60_000;
    private static final long SAVE_DELAY_MS = 2_000;
    private static final long SKIP_WINDOW_MS = 30_000;
    private static final int MAX_WATCHED = 3_000;
    @SuppressLint("StaticFieldLeak")
    private static TvModeService sInstance;
    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    /** Searches nobody is waiting for: channels far down the guide, top-ups. They never hold up the urgent ones. */
    private final ExecutorService mBackground = Executors.newSingleThreadExecutor();
    private final List<TvChannel> mChannels = new ArrayList<>();
    private final Set<String> mWatched = new LinkedHashSet<>();
    private final Set<String> mDeletedPresets = new HashSet<>();
    /** Last results page per search, so the next fill continues instead of starting over. Worker thread only. */
    private final Map<String, MediaGroup> mPages = new ConcurrentHashMap<>();
    private final List<String> mGlobalSkipWords = new ArrayList<>();
    /** Ready-made topics for New channel: a name followed by its search terms. */
    private final List<List<String>> mIdeas = new ArrayList<>();
    private String mDefaultMode = TvChannel.MODE_FREE;
    private boolean mDpadSurf = true;
    private boolean mFavOnly;
    private boolean mYtHistory;
    private String mSwitchEffect = FX_STATIC;
    private boolean mScanlines;
    private boolean mSleepTurnsOff;
    private String mOpenMode = OPEN_GUIDE;
    private String mLastChannelId;
    /** The channel the guide's focus was on last, so the guide comes back to it. */
    private String mGuideChannelId;
    /** The guide's filters: only channels of this genre / language are shown. Empty = any. */
    private String mFilterGenre = "";
    private String mFilterLanguage = "";
    /** Genres in the order the preset list names them. */
    private final List<String> mGenreOrder = new ArrayList<>();
    /** The channels the filters leave. Built when asked for, thrown away when anything changes. */
    private List<TvChannel> mShown;
    /** Playlists a search found, by channel and search term. Worker threads only. */
    private final Map<String, List<MediaItem>> mPlaylists = new ConcurrentHashMap<>();
    // Search filters the MediaServiceCore table has no entry for. Our patch (patches/MediaServiceCore/
    // search-filters.patch) builds YouTube's filter value for these, and for any combination when
    // OPT_BUILD is set. Without the patch the bits are ignored and the checks in accept() still apply.
    private static final int OPT_HD = 1 << 19;
    private static final int OPT_SUBTITLES = 1 << 20;
    private static final int OPT_CREATIVE_COMMONS = 1 << 21;
    private static final int OPT_3D = 1 << 22;
    private static final int OPT_360 = 1 << 23;
    private static final int OPT_VR180 = 1 << 24;
    private static final int OPT_LOCATION = 1 << 25;
    private static final int OPT_PURCHASED = 1 << 26;
    private static final int OPT_BUILD = 1 << 27;
    /** At most this many videos are taken from one playlist at a time, so a channel doesn't play one album in a row. */
    private static final int PLAYLIST_TAKE = 8;
    /** A search for playlists looks at this many of the playlists it found, one for each of its turns. */
    private static final int PLAYLISTS_USED = 6;
    private int mSleepMinutes;
    private long mSleepAtMs;
    private final Runnable mSleep = () -> {
        mSleepMinutes = 0;
        mSleepAtMs = 0;
        TvModeController.onSleepTimer();
    };
    private int mMinAhead = 12;
    private int mMaxPerCreatorInRow = 2;
    private int mSkipsToBlock = 3;
    /** One search in this many asks YouTube for this week's uploads. 0 = never. */
    private int mRecentEvery = 3;
    private long mMinViews;
    private boolean mLoaded;
    private String mConfigText;
    private long mConfigCheckedMs;
    private BrowseSection mSection;
    private int mGuideToken;

    public interface OnGroup {
        void onGroup(VideoGroup group);
    }

    private TvModeService(Context context) {
        mContext = context.getApplicationContext();
    }

    public static TvModeService instance(Context context) {
        if (sInstance == null) {
            sInstance = new TvModeService(context);
        }

        return sInstance;
    }

    /** TV mode is built into every flavor but only switched on where the flavor says so (dev for now). */
    public static boolean isEnabled(Context context) {
        return context != null && context.getResources().getBoolean(R.bool.tv_mode_enabled);
    }

    // ---------------- guide ----------------

    /**
     * Builds one row per channel. Channels that already have a lineup come back at once,
     * the rest as their first search finishes. Callbacks are on the main thread.
     */
    public void loadGuide(BrowseSection section, OnGroup onGroup, Runnable onReady) {
        ensureLoaded();
        refreshConfig();

        mSection = section;
        final int token = ++mGuideToken;
        final VideoGroup changed = buildActionGroup();
        boolean waiting = false;

        // The guide draws straight from the channels, so one small row is sent just to say "draw".
        onGroup.onGroup(changed);

        // The channels on screen first: with a filter on, those are not the first ones of the whole list.
        List<TvChannel> order = new ArrayList<>(getShownChannels());
        for (TvChannel channel : mChannels) {
            if (!order.contains(channel)) {
                order.add(channel);
            }
        }

        for (int i = 0; i < order.size(); i++) {
            final TvChannel channel = order.get(i);

            if (channel.onNow() != null) {
                topUp(channel);
                continue;
            }

            // A channel without videos yet. The ones at the top are fetched first.
            final boolean first = i < GUIDE_FIRST;
            waiting |= first;
            fill(channel, () -> {
                if (token == mGuideToken) {
                    onGroup.onGroup(changed);
                    if (first && onReady != null) {
                        onReady.run();
                    }
                }
            }, first);
        }

        if (!waiting && onReady != null) {
            onReady.run();
        }
    }

    /** Fetches more videos for a channel that is running low. At most once a minute for each channel. */
    private void topUp(TvChannel channel) {
        if (channel.ahead() < mMinAhead && !channel.loading && System.currentTimeMillis() - channel.lastFillMs > TOP_UP_PERIOD_MS) {
            fill(channel, null, false);
        }
    }

    /** Ids of the guide's lines, top to bottom: the buttons, then every channel. */
    public List<Integer> getGuideRowIds() {
        ensureLoaded();
        List<Integer> ids = new ArrayList<>();
        ids.add("tv_mode:actions".hashCode());

        for (TvChannel channel : mChannels) {
            ids.add(("tv_mode:" + channel.id).hashCode());
        }

        return ids;
    }

    /** The top row of the guide: TV settings, Shuffle all, New channel. One press up from channel 1. */
    private VideoGroup buildActionGroup() {
        List<Video> actions = new ArrayList<>();
        actions.add(actionCard(ACTION_SETTINGS, mContext.getString(R.string.tv_mode_settings), R.drawable.icon_settings));
        actions.add(actionCard(ACTION_SHUFFLE_ALL, mContext.getString(R.string.tv_mode_shuffle_all), R.drawable.icon_trending));
        actions.add(actionCard(ACTION_NEW_CHANNEL, mContext.getString(R.string.tv_mode_new_channel), R.drawable.icon_playlist));
        VideoGroup group = VideoGroup.from(actions, mSection, 0);
        group.setId("tv_mode:actions".hashCode());
        group.setTitle(mContext.getString(R.string.header_tv_mode));
        for (Video action : actions) {
            action.setGroup(group);
        }
        return group;
    }

    private Video actionCard(String action, String title, int iconResId) {
        Video card = new Video();
        card.tvChannelId = action;
        card.title = title;
        card.cardImageUrl = "android.resource://" + mContext.getPackageName() + "/" + iconResId;
        return card;
    }

    public static boolean isAction(Video video) {
        return video != null && video.tvChannelId != null && video.tvChannelId.startsWith("@");
    }

    /** row = the row's place on screen. Row 0 is the action row, so channel 1 is row 1. */
    private VideoGroup buildGroup(TvChannel channel, int row) {
        syncLive(channel);
        // A few already played videos stay at the start of the line, to the left of what's on now.
        List<Video> videos = new ArrayList<>(channel.lineup.subList(Math.max(0, channel.pos - PAST_SHOWN), channel.lineup.size()));
        VideoGroup group = VideoGroup.from(videos, mSection, row);
        group.setId(("tv_mode:" + channel.id).hashCode());
        group.setTitle(title(channel));
        for (Video video : videos) { // the helper keeps an old group when there's no section
            video.setGroup(group);
        }
        channel.group = group;
        return group;
    }

    // ---------------- player ----------------

    public TvChannel findChannel(Video video) {
        if (video == null || video.tvChannelId == null) {
            return null;
        }

        ensureLoaded();

        for (TvChannel channel : mChannels) {
            if (video.tvChannelId.equals(channel.id)) {
                return channel;
            }
        }

        return null;
    }

    public int indexOf(TvChannel channel) {
        return mChannels.indexOf(channel);
    }

    /** "3   Nature   LIVE" */
    public String title(TvChannel channel) {
        return (mChannels.indexOf(channel) + 1) + "   " + channel.name + (isLive(channel) ? "   \u2022 LIVE" : "") + (channel.fav ? "   \u2605" : "");
    }

    /**
     * Makes a channel's video on now ready to play and returns it. A live channel starts where the clock is.
     */
    public Video tune(TvChannel channel) {
        syncLive(channel);
        Video onNow = channel.onNow();

        if (onNow == null) {
            return null;
        }

        if (channel.group == null || !channel.group.contains(onNow)) {
            buildGroup(channel, mChannels.indexOf(channel) + 1);
        }

        if (isLive(channel) && !onNow.isLive) { // a stream has no "part-way in": it plays what is live
            onNow.pendingPosMs = liveOffsetMs(channel);
        }

        return onNow;
    }

    /**
     * A Live TV channel is showing a stream, which never ends by itself. When its slot is over
     * this returns what the channel has on next, ready to play. Null while the slot still runs.
     */
    public Video afterStream(Video stream) {
        TvChannel channel = findChannel(stream);

        if (channel == null || !isLive(channel)) {
            return null;
        }

        syncLive(channel);
        Video onNow = channel.onNow();

        return onNow != null && onNow.videoId != null && !onNow.videoId.equals(stream.videoId) ? tune(channel) : null;
    }

    /**
     * Live TV: play a program that isn't on now. The channel's schedule moves so that this program
     * starts now and everything after it follows.<br/>
     * A later program: the ones jumped over weren't watched, so they come back at the end of the lineup.<br/>
     * An earlier program: it plays again, and the ones after it run again in their order.
     */
    public Video jumpLive(TvChannel channel, Video video) {
        int index = channel.indexOf(video.videoId);

        if (index == -1) {
            return null;
        }

        for (int i = index - 1; i > channel.pos; i--) {
            Video passed = channel.lineup.remove(i);
            channel.durations.remove(passed.videoId);
            if (!mWatched.contains(passed.videoId) && !channel.missed.contains(passed)) {
                channel.missed.add(passed);
            }
            index--;
        }

        channel.pos = index;
        channel.liveStartMs = System.currentTimeMillis();
        channel.group = null;
        Video picked = channel.lineup.get(index);
        buildGroup(channel, mChannels.indexOf(channel) + 1);
        picked.pendingPosMs = 1; // from the start, even when it was partly watched before
        save();
        return picked;
    }

    /**
     * Free mode: the guide's preview played the channel's video to its end, so the channel moves
     * on to its next one, the way it would in the player.
     */
    public void previewEnded(TvChannel channel, String videoId) {
        Video onNow = channel.onNow();

        if (isLive(channel) || onNow == null || videoId == null || !videoId.equals(onNow.videoId) || channel.ahead() < 1) {
            return;
        }

        remember(videoId);
        channel.pos++;
        channel.group = null;
        topUp(channel);
        save();
    }

    /** A TV mode video started: it's now what the channel has on, and everything before it is in the past. */
    public String onVideoStarted(Video video) {
        TvChannel channel = findChannel(video);

        if (channel == null) {
            return null;
        }

        int index = channel.indexOf(video.videoId);

        if (index == -1) {
            return null;
        }

        remember(video.videoId);

        // The player takes "next" from the video's row, which must be the channel's whole lineup.
        if (channel.group == null || !channel.group.contains(video) || video.getGroup() != channel.group) {
            buildGroup(channel, mChannels.indexOf(channel) + 1);
            video.setGroup(channel.group);
        }

        // Skipping a creator's videos within the first seconds, several times, blocks that creator here.
        // Watching one longer forgives the earlier skips.
        String note = null;
        String toBlock = null;
        long now = System.currentTimeMillis();
        Video previous = channel.onNow();

        if (previous != null && previous.videoId != null && previous.videoId.equals(channel.startedId) && !isLive(channel)) {
            String creator = creatorOf(previous.author);

            if (creator != null && index == channel.pos + 1 && now - channel.startedMs < SKIP_WINDOW_MS) {
                Integer before = channel.skips.get(creator);
                int count = (before != null ? before : 0) + 1;
                channel.skips.put(creator, count);

                if (count >= mSkipsToBlock && !channel.blocked.contains(creator)) {
                    channel.skips.remove(creator);
                    toBlock = creator;
                    note = creator + ": skipped " + count + " times, so it's blocked on " + channel.name + ". Unblock it in Edit channel.";
                }
            } else if (creator != null && now - channel.startedMs >= SKIP_WINDOW_MS) {
                channel.skips.remove(creator);
            }
        }

        channel.startedId = video.videoId;
        channel.startedMs = now;
        mLastChannelId = channel.id;
        mGuideChannelId = channel.id; // back in the guide, the focus is on the channel that was playing

        if (isLive(channel)) {
            syncLive(channel);
            if (index != channel.pos) { // the player moved on by itself: the clock restarts at this video
                channel.liveStartMs = System.currentTimeMillis();
            }
        }

        channel.pos = index;

        // Keep a short tail of what already played.
        int extra = channel.pos - MAX_PAST;
        for (int i = 0; i < extra; i++) {
            Video removed = channel.lineup.remove(0);
            channel.durations.remove(removed.videoId);
            channel.pos--;
        }

        if (channel.ahead() < mMinAhead) {
            fill(channel, null);
        }

        checkNext(channel);
        save();

        if (toBlock != null) {
            block(channel, toBlock);
        }

        return note;
    }

    /**
     * The other channels as rows for the player, starting with the one after the playing channel.
     */
    public List<VideoGroup> getOtherChannelRows(Video current) {
        List<VideoGroup> rows = new ArrayList<>();
        TvChannel from = findChannel(current);

        if (from == null) {
            return rows;
        }

        int start = mChannels.indexOf(from);
        Set<TvChannel> shown = new HashSet<>(getShownChannels());

        for (int n = 1; n < mChannels.size() && rows.size() < PLAYER_ROWS; n++) {
            int index = (start + n) % mChannels.size();
            TvChannel channel = mChannels.get(index);

            if (!shown.contains(channel)) {
                continue;
            }

            syncLive(channel);

            if (channel.onNow() == null) {
                continue;
            }

            // A short row of its own. The videos keep pointing at their channel's lineup.
            int end = Math.min(channel.lineup.size(), channel.pos + PLAYER_ROW_VIDEOS);
            VideoGroup row = VideoGroup.from(new ArrayList<>(channel.lineup.subList(channel.pos, end)), null, -1);
            row.setId(("tv_mode:player:" + channel.id).hashCode());
            row.setTitle(title(channel));
            row.setPosition(-1); // the player adds rows in the order they arrive
            rows.add(row);
        }

        return rows;
    }

    /** The video on now on the next (dir 1) or previous (dir -1) channel that has one. */
    public Video surf(Video current, int dir) {
        TvChannel from = findChannel(current);

        if (from == null || mChannels.size() < 2) {
            return null;
        }

        int start = mChannels.indexOf(from);
        // CH+ and CH- stay within what the guide's filters show.
        Set<TvChannel> shown = new HashSet<>(getShownChannels());

        for (int n = 1; n < mChannels.size(); n++) {
            int index = ((start + dir * n) % mChannels.size() + mChannels.size()) % mChannels.size();
            TvChannel channel = mChannels.get(index);

            if (!shown.contains(channel)) {
                continue;
            }

            Video onNow = tune(channel);

            if (onNow != null) {
                return onNow;
            }
        }

        return null;
    }

    /**
     * What {@link #surf} would tune to, without tuning: no group building, no start position set.
     * Used to load the next channels' video info ahead, so a channel change starts sooner.
     */
    public Video peek(Video current, int dir) {
        List<Video> ahead = peek(current, dir, 1);
        return ahead.isEmpty() ? null : ahead.get(0);
    }

    /**
     * The videos on now on the next {@code count} channels in that direction, nearest first, without tuning.
     * Several, because CH+ pressed a few times quickly skips past the nearest channel.
     */
    public List<Video> peek(Video current, int dir, int count) {
        List<Video> result = new ArrayList<>();
        TvChannel from = findChannel(current);

        if (from == null || mChannels.size() < 2) {
            return result;
        }

        int start = mChannels.indexOf(from);
        Set<TvChannel> shown = new HashSet<>(getShownChannels());

        for (int n = 1; n < mChannels.size(); n++) {
            int index = ((start + dir * n) % mChannels.size() + mChannels.size()) % mChannels.size();
            TvChannel channel = mChannels.get(index);

            if (!shown.contains(channel)) {
                continue;
            }

            syncLive(channel); // a live channel's "on now" follows the clock
            Video onNow = channel.onNow();

            if (onNow != null && !result.contains(onNow)) {
                result.add(onNow);

                if (result.size() >= count) {
                    break;
                }
            }
        }

        return result;
    }

    private void remember(String videoId) {
        if (videoId == null) {
            return;
        }

        mWatched.remove(videoId);
        mWatched.add(videoId);

        if (mWatched.size() > MAX_WATCHED) {
            String oldest = mWatched.iterator().next();
            mWatched.remove(oldest);
        }
    }

    // ---------------- Live TV ----------------

    /** Live TV channels run on the clock and only play what is on now. Free mode channels wait for you. */
    public boolean isLive(TvChannel channel) {
        return TvChannel.MODE_LIVE.equals(TvChannel.MODE_DEFAULT.equals(channel.mode) ? mDefaultMode : channel.mode);
    }

    private long durationOf(TvChannel channel, Video video) {
        return channel.durationOf(video);
    }

    /** The creator's name. Search results carry it as "name, views, age" in one string. */
    public static String creatorOf(String author) {
        if (author == null) {
            return null;
        }

        int end = author.indexOf('\u2022');
        String name = (end >= 0 ? author.substring(0, end) : author).trim();
        return name.isEmpty() ? null : name;
    }

    /** Moves a live channel to the video the clock says is on now. */
    private void syncLive(TvChannel channel) {
        Video onNow = channel.onNow();

        if (onNow == null || !isLive(channel)) {
            return;
        }

        long now = System.currentTimeMillis();

        if (channel.liveStartMs <= 0 || channel.liveStartMs > now) {
            // Just went live: join part-way in, the way you would find any channel that was already running.
            channel.liveStartMs = now - (long) (Math.random() * 0.6 * durationOf(channel, onNow));
        }

        while (channel.ahead() > 0 && now - channel.liveStartMs >= durationOf(channel, channel.onNow())) {
            Video passed = channel.onNow();
            channel.liveStartMs += durationOf(channel, passed);
            channel.pos++;

            // It ran, but nobody saw it: it may come back later. Only what was watched is gone for good.
            if (!mWatched.contains(passed.videoId) && !channel.missed.contains(passed)) {
                channel.missed.add(passed);
                if (channel.missed.size() > MAX_MISSED) {
                    channel.missed.remove(0);
                }
            }
        }

        // A channel left running for days must not keep everything it ever played.
        while (channel.pos > MAX_PAST) {
            channel.lineup.remove(0);
            channel.pos--;
        }

        if (channel.ahead() == 0 && now - channel.liveStartMs >= durationOf(channel, channel.onNow())) {
            channel.liveStartMs = now; // ran out of lineup: hold on the last video until more arrives
        }

        topUp(channel);
    }

    /** How far into the video on now a live channel is. */
    public long liveOffsetMs(TvChannel channel) {
        syncLive(channel);
        return Math.max(0, System.currentTimeMillis() - channel.liveStartMs);
    }

    /** Clock time when a video of a live channel starts. index = its place in the lineup. */
    public long liveStartOf(TvChannel channel, int index) {
        syncLive(channel);
        long time = channel.liveStartMs;

        for (int i = channel.pos; i < index && i < channel.lineup.size(); i++) {
            time += durationOf(channel, channel.lineup.get(i));
        }

        return time;
    }

    // ---------------- settings ----------------

    public String getDefaultMode() {
        ensureLoaded();
        return mDefaultMode;
    }

    public void setDefaultMode(String mode) {
        mDefaultMode = mode;
        save();
    }

    public boolean isDpadSurfEnabled() {
        ensureLoaded();
        return mDpadSurf;
    }

    public void setDpadSurfEnabled(boolean enabled) {
        mDpadSurf = enabled;
        save();
    }

    public boolean isFavOnly() {
        ensureLoaded();
        return mFavOnly;
    }

    public void setFavOnly(boolean enabled) {
        mFavOnly = enabled;
        save();
    }

    public boolean isYtHistoryEnabled() {
        ensureLoaded();
        return mYtHistory;
    }

    public void setYtHistoryEnabled(boolean enabled) {
        mYtHistory = enabled;
        save();
    }

    public String getSwitchEffect() {
        ensureLoaded();
        return mSwitchEffect;
    }

    public void setSwitchEffect(String effect) {
        mSwitchEffect = effect;
        save();
    }

    public boolean isScanlinesEnabled() {
        return false; // the setting was dropped: the VHS preview does this job
    }

    public void setScanlinesEnabled(boolean enabled) {
        mScanlines = enabled;
        save();
    }

    public String getOpenMode() {
        ensureLoaded();
        return mOpenMode;
    }

    public void setOpenMode(String mode) {
        mOpenMode = mode;
        save();
    }

    /** The channel that was watched last, ready to play. Null when nothing was watched yet. */
    public Video tuneLastChannel() {
        ensureLoaded();

        for (TvChannel channel : mChannels) {
            if (channel.id.equals(mLastChannelId)) {
                return tune(channel);
            }
        }

        return null;
    }

    /**
     * Where in a video the "VHS preview" switching effect takes its frame: where the video will
     * start (a live channel's clock, or where it was left). A video starting from the top uses a
     * frame a little way in, since the very first frames are usually a logo or black.
     */
    public long previewPositionMs(Video video) {
        TvChannel channel = findChannel(video);

        if (channel == null || video.videoId == null) {
            return 0;
        }

        long position = 0;
        Video onNow = channel.onNow();

        if (isLive(channel) && onNow != null && video.videoId.equals(onNow.videoId)) {
            position = liveOffsetMs(channel);
        } else {
            com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State state =
                    com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.instance(mContext).getByVideoId(video.videoId);
            if (state != null) {
                position = state.positionMs;
            }
        }

        return position > 0 ? position : Math.min(channel.durationOf(video) / 10, 60_000);
    }

    /**
     * What a channel has on, as a copy for the small preview player in the guide. The copy belongs
     * to no channel, so watching the preview doesn't count as watching the channel.
     */
    public Video previewCopy(TvChannel channel) {
        syncLive(channel);
        Video onNow = channel.onNow();

        if (onNow == null || onNow.videoId == null) {
            return null;
        }

        long position = 0;

        if (isLive(channel)) {
            position = liveOffsetMs(channel);
        } else {
            com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State state =
                    com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.instance(mContext).getByVideoId(onNow.videoId);
            if (state != null) {
                position = state.positionMs;
            }
        }

        checkNext(channel);

        Video copy = Video.from(onNow);
        copy.author = onNow.author;
        copy.setGroup(null);
        copy.playlistId = null;
        copy.playlistParams = null;
        copy.playlistIndex = -1;
        copy.pendingPosMs = Math.max(1, position);
        copy.incognito = true;
        return copy;
    }

    /**
     * Clock time at which the channel's video on now started, for drawing the guide's time line.
     * A Live TV channel runs on the clock. A Free mode channel is paused where it was left, so
     * its start keeps the same distance behind now.
     */
    public long onNowStartMs(TvChannel channel, long now) {
        if (isLive(channel)) {
            syncLive(channel);
            return channel.liveStartMs;
        }

        Video onNow = channel.onNow();
        long position = 0;

        if (onNow != null && onNow.videoId != null) {
            com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State state =
                    com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.instance(mContext).getByVideoId(onNow.videoId);
            if (state != null && state.positionMs > 0 && state.positionMs < channel.durationOf(onNow)) {
                position = state.positionMs;
            }
        }

        return now - position;
    }

    /** The first video of the fresh lineup after a shuffle, ready to play. */
    public Video firstAfterShuffle(TvChannel channel) {
        if (channel.ahead() < 1) {
            return null;
        }

        buildGroup(channel, mChannels.indexOf(channel) + 1);
        return channel.lineup.get(channel.pos + 1);
    }

    // ---------------- the guide's filters ----------------

    /**
     * The channels the guide shows and CH+ / CH- move through: the ones that pass the Genre,
     * Language and Favourites filters. A filter only hides channels. It never changes a lineup.
     */
    public List<TvChannel> getShownChannels() {
        ensureLoaded();

        if (mShown == null) {
            List<TvChannel> shown = new ArrayList<>();

            for (TvChannel channel : mChannels) {
                if ((!mFavOnly || channel.fav)
                        && (mFilterGenre.isEmpty() || mFilterGenre.equalsIgnoreCase(channel.genre))
                        && (mFilterLanguage.isEmpty() || mFilterLanguage.equals(channel.language()))) {
                    shown.add(channel);
                }
            }

            mShown = shown;
        }

        return mShown;
    }

    public String getFilterGenre() {
        ensureLoaded();
        return mFilterGenre;
    }

    public void setFilterGenre(String genre) {
        mFilterGenre = genre != null ? genre : "";
        save();
    }

    public String getFilterLanguage() {
        ensureLoaded();
        return mFilterLanguage;
    }

    public void setFilterLanguage(String code) {
        mFilterLanguage = code != null ? code : "";
        save();
    }

    /** The genres that have channels: first the ones the preset list names, in its order, then any others. */
    public List<String> getGenres() {
        ensureLoaded();
        List<String> genres = new ArrayList<>();

        for (String genre : mGenreOrder) {
            for (TvChannel channel : mChannels) {
                if (genre.equalsIgnoreCase(channel.genre)) {
                    genres.add(genre);
                    break;
                }
            }
        }

        for (TvChannel channel : mChannels) {
            if (!channel.genre.isEmpty() && !containsIgnoreCase(genres, channel.genre)) {
                genres.add(channel.genre);
            }
        }

        return genres;
    }

    /** The language codes that have channels, in the order of TvLanguage's list. */
    public List<String> getChannelLanguages() {
        ensureLoaded();
        List<String> codes = new ArrayList<>();

        for (String[] language : TvLanguage.LANGUAGES) {
            for (TvChannel channel : mChannels) {
                if (language[0].equals(channel.language())) {
                    codes.add(language[0]);
                    break;
                }
            }
        }

        return codes;
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String item : list) {
            if (item.equalsIgnoreCase(value)) {
                return true;
            }
        }

        return false;
    }

    /** The channel the guide should put its focus on: where the focus was, or what was playing. */
    public String getGuideChannelId() {
        ensureLoaded();
        return mGuideChannelId;
    }

    public void setGuideChannel(TvChannel channel) {
        if (channel != null) {
            mGuideChannelId = channel.id; // written with the next save: the focus moves too often to save each time
        }
    }

    public int getSleepMinutes() {
        return mSleepMinutes;
    }

    /** Minutes until the sleep timer stops playback, rounded up. 0 = it isn't running. */
    public int getSleepMinutesLeft() {
        long left = mSleepAtMs - System.currentTimeMillis();
        return mSleepMinutes > 0 && left > 0 ? (int) ((left + 59_999) / 60_000) : 0;
    }

    /**
     * Stops TV mode playing and dims the screen after this many minutes. 0 = off.
     * Not kept across restarts. An app can't switch a TV off, so the TV's own power saving does that later.
     */
    public void setSleepTimer(int minutes) {
        mSleepMinutes = minutes;
        mSleepAtMs = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        mMain.removeCallbacks(mSleep);

        if (minutes > 0) {
            mMain.postDelayed(mSleep, minutes * 60_000L);
        }
    }

    /**
     * Live TV follows what is actually on screen. After a seek, a pause or a slow start the
     * channel's clock is moved so that "on now" is at the player's position, and everything
     * after it on that channel shifts with it.
     */
    public void alignLive(Video video, long positionMs) {
        TvChannel channel = findChannel(video);

        if (channel == null || !isLive(channel) || positionMs < 0) {
            return;
        }

        Video onNow = channel.onNow();

        if (onNow != null && onNow.videoId != null && onNow.videoId.equals(video.videoId)) {
            channel.liveStartMs = System.currentTimeMillis() - positionMs;
        }
    }

    /**
     * A video picked at random from what is coming up on any channel, to play once from the start.
     * It is a copy that belongs to no channel, so no channel moves and the guide stays as it is.
     */
    public Video randomVideo() {
        ensureLoaded();
        List<Video> coming = new ArrayList<>();

        for (TvChannel channel : mChannels) {
            for (int i = channel.pos + 1; i < channel.lineup.size(); i++) {
                coming.add(channel.lineup.get(i));
            }
        }

        if (coming.isEmpty()) {
            return null;
        }

        Video pick = coming.get((int) (Math.random() * coming.size()));
        Video copy = Video.from(pick);
        copy.author = pick.author;
        copy.badge = pick.badge;
        copy.setGroup(null);
        copy.playlistId = null;
        copy.playlistParams = null;
        copy.playlistIndex = -1;
        copy.pendingPosMs = 1; // from the start, even when it was partly watched before
        copy.incognito = !mYtHistory;
        copy.tvChannelId = RANDOM;
        return copy;
    }

    /**
     * A program of a Live TV channel, to play once from the start without touching the channel:
     * its schedule runs on as if nothing happened. It is a copy that belongs to no channel.
     * When it ends, {@link #afterOnce} gives what the channel has on by then.
     */
    public Video playOnce(TvChannel channel, Video video) {
        Video copy = Video.from(video);
        copy.author = video.author;
        copy.badge = video.badge;
        copy.setGroup(null);
        copy.playlistId = null;
        copy.playlistParams = null;
        copy.playlistIndex = -1;
        copy.pendingPosMs = 1;
        copy.incognito = !mYtHistory;
        copy.tvChannelId = ONCE;
        mOnceChannelId = channel.id;
        remember(video.videoId);
        return copy;
    }

    /** The channel a play-once video was picked from, tuned to what it has on now. */
    public Video afterOnce() {
        for (TvChannel channel : mChannels) {
            if (channel.id.equals(mOnceChannelId)) {
                return tune(channel);
            }
        }

        return null;
    }

    public int getWatchedCount() {
        ensureLoaded();
        return mWatched.size();
    }

    // ---------------- channels ----------------

    public List<TvChannel> getChannels() {
        ensureLoaded();
        return mChannels;
    }

    /** Throws away what is coming up and builds a fresh lineup. What was dropped does not come back. */
    public void shuffle(TvChannel channel, Runnable onDone) {
        while (channel.lineup.size() > channel.pos + 1) {
            Video removed = channel.lineup.remove(channel.lineup.size() - 1);
            channel.durations.remove(removed.videoId);
            remember(removed.videoId);
        }

        channel.group = null;
        channel.emptyFills = 0;
        channel.termCursor++;
        save();
        fill(channel, onDone);
    }

    public void shuffleAll(Runnable onDone) {
        for (TvChannel channel : mChannels) {
            shuffle(channel, onDone);
        }
    }

    public void setFav(TvChannel channel, boolean fav) {
        channel.fav = fav;
        save();
    }

    public void setGenre(TvChannel channel, String genre) {
        channel.genre = genre != null ? genre : "";
        channel.edited = true;
        save();
    }

    public void setMode(TvChannel channel, String mode) {
        channel.mode = mode;
        channel.liveStartMs = 0;
        save();
    }

    public void rename(TvChannel channel, String name) {
        channel.name = name;
        channel.edited = true;
        save();
    }

    /** Never play this creator on this channel again. */
    public void block(TvChannel channel, String author) {
        if (author == null || channel.blocked.contains(author)) {
            return;
        }

        channel.blocked.add(author);
        channel.edited = true;

        for (int i = channel.lineup.size() - 1; i > channel.pos; i--) {
            if (author.equalsIgnoreCase(creatorOf(channel.lineup.get(i).author))) {
                channel.durations.remove(channel.lineup.remove(i).videoId);
            }
        }

        channel.group = null;
        save();

        if (channel.ahead() < mMinAhead) {
            fill(channel, null);
        }
    }

    /** Adds a YouTube channel as a source. id may be null: it's looked up by name on first use. */
    public boolean addCreator(TvChannel channel, String name, String id, Runnable onDone) {
        if (name == null || name.trim().isEmpty() || channel.creators.contains(name.trim())) {
            return false;
        }

        channel.creators.add(name.trim());

        if (id != null) {
            channel.creatorIds.put(name.trim(), id);
        }

        onSearchChanged(channel, onDone);
        return true;
    }

    /** A search setting of the channel changed: keep what is on now, rebuild what is coming up. */
    public void onSearchChanged(TvChannel channel, Runnable onDone) {
        channel.edited = true;
        dropComingUp(channel);

        while (channel.lineup.size() > channel.pos + 1) { // nothing from the old search may stay queued
            channel.durations.remove(channel.lineup.remove(channel.lineup.size() - 1).videoId);
        }

        String prefix = channel.id + "|";
        for (String key : new ArrayList<>(mPages.keySet())) {
            if (key.startsWith(prefix)) {
                mPages.remove(key);
            }
        }
        for (String key : new ArrayList<>(mPlaylists.keySet())) {
            if (key.startsWith(prefix)) {
                mPlaylists.remove(key);
            }
        }

        save();
        fill(channel, onDone);
    }

    /** Forgets what was watched, so those videos can come back. null = on every channel. */
    public void clearHistory(TvChannel only) {
        for (TvChannel channel : mChannels) {
            if (only != null && only != channel) {
                continue;
            }

            for (int i = channel.pos; i > 0; i--) {
                Video removed = channel.lineup.remove(0);
                channel.durations.remove(removed.videoId);
                mWatched.remove(removed.videoId);
            }

            channel.pos = 0;
            channel.group = null;
            channel.exhausted = false;
        }

        if (only == null) {
            mWatched.clear();
        }

        save();
    }

    public boolean move(TvChannel channel, int dir) {
        int from = mChannels.indexOf(channel);
        int to = from + dir;

        if (from == -1 || to < 0 || to >= mChannels.size()) {
            return false;
        }

        Collections.swap(mChannels, from, to);
        save();
        return true;
    }

    public void delete(TvChannel channel) {
        mChannels.remove(channel);

        if (channel.preset) {
            mDeletedPresets.add(channel.id); // or the preset list would bring it straight back
        }

        save();
    }

    public TvChannel create(String name, List<String> terms, Runnable onDone) {
        TvChannel channel = new TvChannel();
        channel.id = "my-" + System.currentTimeMillis();
        channel.name = name;
        channel.terms.addAll(terms);
        channel.edited = true;
        mChannels.add(channel);
        save();
        fill(channel, onDone);
        return channel;
    }

    /** Back to the preset list. Channels made on this TV are removed. */
    public void restorePresets() {
        mChannels.clear();
        mDeletedPresets.clear();

        if (mConfigText != null) {
            applyConfig(mConfigText);
        }

        save();
    }

    /** Ready-made topics for New channel, from the preset list file. Each is a name followed by its search terms. */
    public List<List<String>> getIdeas() {
        ensureLoaded();
        return mIdeas;
    }

    // ---------------- lineups ----------------

    /** Adds one page of search results to the end of the channel's lineup. */
    private void fill(TvChannel channel, Runnable onDone) {
        fill(channel, onDone, true);
    }

    /** urgent: somebody is waiting for this channel. Otherwise it queues behind the other background searches. */
    private void fill(final TvChannel channel, final Runnable onDone, final boolean urgent) {
        if (channel.terms.isEmpty() && channel.creators.isEmpty()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }

        if (channel.loading) { // a search is already running: hear about it when it lands
            if (onDone != null) {
                channel.waiters.add(onDone);
            }
            return;
        }

        channel.loading = true;
        channel.lastFillMs = System.currentTimeMillis();
        // Sources take turns: each search term, each YouTube channel, then (when the channel also
        // looks in playlists) each search term again as a search for playlists.
        final int playlistTurns = channel.playlists ? channel.terms.size() : 0;
        final int turn = Math.abs(channel.termCursor++) % (channel.terms.size() + channel.creators.size() + playlistTurns);
        final boolean fromPlaylist = turn >= channel.terms.size() + channel.creators.size();
        final boolean fromCreator = !fromPlaylist && turn >= channel.terms.size();
        final String term = fromPlaylist ? channel.terms.get(turn - channel.terms.size() - channel.creators.size())
                : fromCreator ? channel.creators.get(turn - channel.terms.size()) : channel.terms.get(turn);
        final String knownCreatorId = fromCreator ? channel.creatorIds.get(term) : null;
        // Every few searches one asks for this week's uploads, so a channel isn't only YouTube's
        // evergreen results. Not when the channel has its own date or order setting.
        final boolean recent = !fromCreator && !fromPlaylist && mRecentEvery > 0 && channel.fills % mRecentEvery == mRecentEvery - 1
                && TvChannel.ANY.equals(channel.uploaded) && TvChannel.ORDER_MIXED.equals(channel.order);
        final int playlistIndex = channel.fills;
        // A movie channel takes turns between YouTube's Movies filter and a plain search for long
        // videos. The Movies filter also returns titles to rent or buy, which can't be played, and
        // in some regions little else. The plain search keeps the channel from running dry then.
        final boolean asMovie = TvChannel.TYPE_MOVIE.equals(channel.type) && channel.fills % 2 == 0;
        channel.fills++;
        final int options = fromCreator ? 0 : fromPlaylist ? (SearchOptions.TYPE_PLAYLIST | OPT_BUILD) : searchOptions(channel, recent, asMovie);
        // What is sent when YouTube returns nothing for the full set of filters: the type alone.
        // The checks in accept() still apply the rest.
        final int plainOptions = (asMovie ? SearchOptions.TYPE_MOVIE : SearchOptions.TYPE_VIDEO) | OPT_BUILD;
        // A plain search returns mostly English. A channel in another language adds the language's
        // name to its searches, or the language check would throw nearly everything away.
        final String languageWord = fromCreator ? "" : TvLanguage.searchWord(channel.language());
        final String query = languageWord.isEmpty() || term.toLowerCase(Locale.ROOT).contains(languageWord)
                || TvLanguage.detect(term) == null || !"en".equals(TvLanguage.detect(term)) ? term : term + " " + languageWord;
        final String pageKey = channel.id + "|" + (fromCreator ? "@" : fromPlaylist ? "#" : "") + query + "|" + options;
        final String searchKey = channel.searchKey();

        (urgent ? mWorker : mBackground).execute(() -> {
            final List<MediaItem> found = new ArrayList<>();
            final String[] creatorId = {knownCreatorId};

            try {
                ContentService service = YouTubeServiceManager.instance().getContentService();
                MediaGroup previous = fromPlaylist ? null : mPages.get(pageKey);
                List<MediaGroup> groups;

                if (fromPlaylist) {
                    groups = playlistVideos(service, pageKey, query, options, playlistIndex);
                } else if (previous != null) {
                    MediaGroup next = service.continueGroup(previous);
                    groups = next != null ? Collections.singletonList(next) : new ArrayList<MediaGroup>();
                } else if (fromCreator) {
                    if (creatorId[0] == null) {
                        creatorId[0] = findCreatorId(service, term);
                    }
                    groups = creatorId[0] != null ? service.getChannelObserve(creatorId[0]).blockingFirst() : new ArrayList<MediaGroup>();
                } else {
                    groups = service.getSearch(query, options);

                    if (!recent && options != plainOptions && countItems(groups) == 0) {
                        groups = service.getSearch(query, plainOptions);
                    }
                }

                MediaGroup lastPage = null;

                if (groups != null) {
                    for (MediaGroup group : groups) {
                        if (group == null || group.getMediaItems() == null) {
                            continue;
                        }
                        found.addAll(group.getMediaItems());
                        if (group.getNextPageKey() != null) {
                            lastPage = group;
                        }
                    }
                }

                if (fromPlaylist) {
                    // A few from each playlist, picked at random: a whole playlist in a row would be one album or one series.
                    Collections.shuffle(found);
                    while (found.size() > PLAYLIST_TAKE) {
                        found.remove(found.size() - 1);
                    }
                } else if (lastPage != null) {
                    mPages.put(pageKey, lastPage);
                } else {
                    mPages.remove(pageKey);
                }
            } catch (Throwable e) {
                Log.e(TAG, "Search failed for %s: %s", term, e.getMessage());
            }

            mMain.post(() -> {
                channel.loading = false;

                if (fromCreator && creatorId[0] != null) {
                    channel.creatorIds.put(term, creatorId[0]);
                }

                // The channel was edited while the search ran. These results no longer apply.
                if (mChannels.contains(channel) && searchKey.equals(channel.searchKey())) {
                    int added = append(channel, found, fromCreator ? term : null);
                    Log.d(TAG, "%s: +%s videos from \"%s\"%s", channel.name, added, term, recent ? " (this week)" : "");

                    if (added > 0) {
                        channel.emptyFills = 0;
                        save();
                    } else if (!recent) { // a quiet week says nothing about the channel running dry
                        channel.emptyFills++;
                        // Every term came back with nothing new: let watched videos return.
                        if (channel.emptyFills >= channel.sources() * 2) {
                            channel.exhausted = true;
                        }
                    }

                    // One page is often not enough after filtering. Bounded so a dead search can't loop.
                    if (channel.ahead() < mMinAhead && channel.emptyFills < channel.sources() * 3) {
                        fill(channel, null, urgent);
                    }
                }

                List<Runnable> waiters = new ArrayList<>(channel.waiters);
                channel.waiters.clear();

                if (onDone != null) {
                    onDone.run();
                }

                for (Runnable waiter : waiters) {
                    waiter.run();
                }
            });
        });
    }

    /**
     * The channel's filters as YouTube search options: type, length, upload date, order and
     * features, all together. recent = this search asks for this week's uploads. asMovie = this
     * search uses YouTube's Movies filter.
     */
    private static int searchOptions(TvChannel channel, boolean recent, boolean asMovie) {
        int options = OPT_BUILD | (asMovie ? SearchOptions.TYPE_MOVIE : SearchOptions.TYPE_VIDEO);

        if (TvChannel.LENGTH_SHORT.equals(channel.length)) {
            options |= SearchOptions.DURATION_UNDER_4;
        } else if (TvChannel.LENGTH_MEDIUM.equals(channel.length)) {
            options |= SearchOptions.DURATION_BETWEEN_4_20;
        } else if (TvChannel.LENGTH_LONG.equals(channel.length) || channel.minMinutes >= 20) {
            options |= SearchOptions.DURATION_OVER_20;
        }

        boolean trending = TvChannel.ORDER_TRENDING.equals(channel.order);

        if (TvChannel.UPLOADED_TODAY.equals(channel.uploaded)) {
            options |= SearchOptions.UPLOAD_DATE_TODAY;
        } else if (recent || trending || TvChannel.UPLOADED_WEEK.equals(channel.uploaded)) {
            options |= SearchOptions.UPLOAD_DATE_THIS_WEEK;
        } else if (TvChannel.UPLOADED_MONTH.equals(channel.uploaded)) {
            options |= SearchOptions.UPLOAD_DATE_THIS_MONTH;
        } else if (TvChannel.UPLOADED_YEAR.equals(channel.uploaded)) {
            options |= SearchOptions.UPLOAD_DATE_THIS_YEAR;
        }

        if (TvChannel.ORDER_NEW.equals(channel.order)) {
            options |= SearchOptions.SORT_BY_UPLOAD_DATE;
        } else if (trending || TvChannel.ORDER_VIEWS.equals(channel.order)) {
            options |= SearchOptions.SORT_BY_VIEW_COUNT;
        }

        options |= channel.hasFeature(TvChannel.FEATURE_HD) ? OPT_HD : 0;
        options |= channel.hasFeature(TvChannel.FEATURE_4K) ? SearchOptions.FEATURE_4K : 0;
        options |= channel.hasFeature(TvChannel.FEATURE_HDR) ? SearchOptions.FEATURE_HDR : 0;
        options |= channel.hasFeature(TvChannel.FEATURE_SUBTITLES) ? OPT_SUBTITLES : 0;
        options |= channel.hasFeature(TvChannel.FEATURE_CC) ? OPT_CREATIVE_COMMONS : 0;
        options |= channel.hasFeature(TvChannel.FEATURE_360) ? OPT_360 : 0;
        options |= channel.hasFeature(TvChannel.FEATURE_VR180) ? OPT_VR180 : 0;
        options |= channel.hasFeature(TvChannel.FEATURE_3D) ? OPT_3D : 0;
        options |= channel.hasFeature(TvChannel.FEATURE_LIVE) ? SearchOptions.FEATURE_LIVE : 0;
        options |= channel.hasFeature(TvChannel.FEATURE_LOCATION) ? OPT_LOCATION : 0;
        options |= channel.hasFeature(TvChannel.FEATURE_PURCHASED) ? OPT_PURCHASED : 0;

        return options;
    }

    private static int countItems(List<MediaGroup> groups) {
        int count = 0;

        if (groups != null) {
            for (MediaGroup group : groups) {
                count += group != null && group.getMediaItems() != null ? group.getMediaItems().size() : 0;
            }
        }

        return count;
    }

    /**
     * The videos of one playlist that a search for playlists found. The search runs once for a
     * term and its playlists then take turns. Nothing is kept about a playlist beyond this list:
     * its videos join the lineup as plain videos.
     */
    private List<MediaGroup> playlistVideos(ContentService service, String key, String query, int options, int index) {
        List<MediaItem> playlists = mPlaylists.get(key);

        if (playlists == null) {
            playlists = new ArrayList<>();
            List<MediaGroup> groups = service.getSearch(query, options);

            if (groups != null) {
                for (MediaGroup group : groups) {
                    if (group == null || group.getMediaItems() == null) {
                        continue;
                    }
                    for (MediaItem item : group.getMediaItems()) {
                        if (item != null && item.getVideoId() == null && (item.getReloadPageKey() != null || item.getPlaylistId() != null)
                                && playlists.size() < PLAYLISTS_USED) {
                            playlists.add(item);
                        }
                    }
                }
            }

            mPlaylists.put(key, playlists);
        }

        List<MediaGroup> result = new ArrayList<>();

        if (!playlists.isEmpty()) {
            MediaItem playlist = playlists.get(Math.abs(index) % playlists.size());
            MediaGroup videos = playlist.getReloadPageKey() != null ? service.getGroup(playlist) : service.getGroup("VL" + playlist.getPlaylistId());
            if (videos != null) {
                result.add(videos);
            }
        }

        return result;
    }

    /** The YouTube channel id for a creator's name: the first channel a search for the name returns. */
    private static String findCreatorId(ContentService service, String name) {
        List<MediaGroup> groups = service.getSearch(name, SearchOptions.TYPE_CHANNEL);

        if (groups != null) {
            for (MediaGroup group : groups) {
                if (group == null || group.getMediaItems() == null) {
                    continue;
                }
                for (MediaItem item : group.getMediaItems()) {
                    if (item != null && item.getChannelId() != null && item.getVideoId() == null) {
                        return item.getChannelId();
                    }
                }
            }
        }

        return null;
    }

    /** "1.2M views" as a number, or -1 when the text has no view count this can read. */
    static long parseViews(CharSequence text) {
        if (text == null) {
            return -1;
        }

        java.util.regex.Matcher matcher = VIEWS.matcher(text);

        if (!matcher.find()) {
            return -1;
        }

        try {
            double number = Double.parseDouble(matcher.group(1).replace(",", ""));
            String unit = matcher.group(2) != null ? matcher.group(2).toUpperCase(Locale.ROOT) : "";
            return (long) (number * ("K".equals(unit) ? 1_000 : "M".equals(unit) ? 1_000_000 : "B".equals(unit) ? 1_000_000_000L : 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static final java.util.regex.Pattern VIEWS = java.util.regex.Pattern.compile("([\\d.,]+)\\s*([KMBkmb])?\\s+views");

    /** creator: set when the videos came from a creator's own page, where YouTube leaves the name off. */
    private int append(TvChannel channel, List<MediaItem> found, String creator) {
        List<Video> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Video video : channel.lineup) {
            seen.add(video.videoId);
        }

        for (MediaItem item : found) {
            if (item == null || !accept(channel, item) || !seen.add(item.getVideoId())) {
                continue;
            }

            if (!channel.exhausted && mWatched.contains(item.getVideoId())) {
                continue;
            }

            Video video = Video.from(item);
            // A search result must play as a single video, not as part of a playlist or mix.
            video.playlistId = null;
            video.playlistParams = null;
            video.playlistIndex = -1;
            video.tvChannelId = channel.id;
            if (video.author == null && creator != null) {
                video.author = creator;
            }
            fresh.add(video);
        }

        // Videos that ran while nobody watched rejoin here, at the end, mixed with the new ones:
        // about one for every three new, or a batch of them when the search found nothing new.
        int rejoin = fresh.isEmpty() ? 10 : Math.max(1, fresh.size() / 3);

        for (java.util.Iterator<Video> it = channel.missed.iterator(); it.hasNext() && rejoin > 0;) {
            Video video = it.next();

            if (mWatched.contains(video.videoId)) {
                it.remove(); // it was watched after all
            } else if (seen.add(video.videoId)) { // not while it is still in the lineup
                it.remove();
                fresh.add(video);
                rejoin--;
            }
        }

        if (!TvChannel.ORDER_NEW.equals(channel.order) && !TvChannel.ORDER_VIEWS.equals(channel.order)) {
            Collections.shuffle(fresh);
        }

        int added = 0;

        for (Video video : fresh) {
            if (creatorRunsTooLong(channel, video)) {
                continue;
            }

            channel.lineup.add(video);
            if (video.isLive) {
                channel.durations.put(video.videoId, TvChannel.STREAM_SLOT_MS); // a stream has no length: it gets a slot
            } else if (video.getDurationMs() > 0) {
                channel.durations.put(video.videoId, video.getDurationMs());
            }
            if (channel.group != null) {
                channel.group.add(video);
            }
            added++;
        }

        return added;
    }

    private boolean accept(TvChannel channel, MediaItem item) {
        // isMovie: a title from YouTube's store, to rent or buy. It has no video id to play.
        if (item.getVideoId() == null || item.getTitle() == null || item.isUpcoming() || item.isMovie()) {
            return false;
        }

        // Streams only on a channel made for them, and such a channel plays nothing else.
        if (item.isLive() != channel.hasFeature(TvChannel.FEATURE_LIVE)) {
            return false;
        }

        long durationMs = item.isLive() ? 0 : item.getDurationMs();

        if (channel.minMinutes > 0 && !item.isLive() && durationMs > 0 && durationMs < channel.minMinutes * 60_000L) {
            return false;
        }

        if (channel.noShorts && (item.isShorts() || (durationMs > 0 && durationMs <= 61_000))) {
            return false;
        }

        if (durationMs > 0) {
            long minutes = durationMs / 60_000;
            if ((TvChannel.LENGTH_SHORT.equals(channel.length) && minutes >= 4)
                    || (TvChannel.LENGTH_MEDIUM.equals(channel.length) && (minutes < 4 || minutes > 20))
                    || (TvChannel.LENGTH_LONG.equals(channel.length) && minutes < 20)) {
                return false;
            }
        }

        long minViews = Math.max(mMinViews, channel.minViews);

        if (minViews > 0) {
            long views = parseViews(item.getSecondTitle());
            if (views >= 0 && views < minViews) {
                return false;
            }
        }

        String channelLanguage = channel.language();

        if (!channelLanguage.isEmpty()) {
            String language = TvLanguage.detect(item.getTitle());
            // A Hindi channel keeps titles in plain Latin letters: a great many Hindi videos are titled that way.
            boolean latinOnHindi = "en".equals(language) && !"en".equals(channelLanguage);
            if (language != null && !channelLanguage.equals(language) && !latinOnHindi) {
                return false;
            }
        }

        String title = item.getTitle().toLowerCase(Locale.ROOT);

        if (containsAny(title, channel.skipWords) || containsAny(title, mGlobalSkipWords)) {
            return false;
        }

        String author = creatorOf(item.getAuthor());

        if (author != null) {
            for (String blocked : channel.blocked) {
                if (author.equalsIgnoreCase(blocked)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean containsAny(String lowerCaseText, List<String> words) {
        for (String word : words) {
            if (lowerCaseText.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    /** True when the lineup already ends with the most videos one creator may have in a row. */
    private boolean creatorRunsTooLong(TvChannel channel, Video video) {
        String creator = creatorOf(video.author);

        if (mMaxPerCreatorInRow <= 0 || creator == null || channel.lineup.size() < mMaxPerCreatorInRow) {
            return false;
        }

        for (int i = channel.lineup.size() - mMaxPerCreatorInRow; i < channel.lineup.size(); i++) {
            if (!creator.equals(creatorOf(channel.lineup.get(i).author))) {
                return false;
            }
        }

        return true;
    }

    // ---------------- preset list and tuning (bundled file, updated from the repo) ----------------

    private void ensureLoaded() {
        if (mLoaded) {
            return;
        }

        mLoaded = true;
        loadState();

        String config = readFile(new File(mContext.getFilesDir(), CONFIG_CACHE_FILE));

        if (config == null || !applyConfig(config)) {
            try {
                applyConfig(readStream(mContext.getAssets().open(mContext.getString(R.string.tv_mode_config_asset))));
            } catch (Throwable e) {
                Log.e(TAG, "Can't read the bundled TV mode config: %s", e.getMessage());
            }
        }
    }

    /**
     * "Refresh channels" in TV settings: fetch the channel list now instead of at the next start,
     * take out queued videos that no longer fit their channel, and top every channel up.
     * onChanged runs each time something arrived, so the guide can redraw.
     */
    public void refreshChannels(final Runnable onChanged) {
        ensureLoaded();
        mConfigCheckedMs = 0;
        refreshConfig(() -> {
            fillLow(null); // the guide redraws by itself every few seconds
            if (onChanged != null) {
                onChanged.run();
            }
        });

        for (TvChannel channel : mChannels) {
            purge(channel);
        }

        fillLow(null); // the guide redraws by itself every few seconds
        save();
    }

    private void fillLow(Runnable onDone) {
        for (TvChannel channel : mChannels) {
            if (channel.ahead() < mMinAhead && !channel.loading) {
                channel.emptyFills = 0;
                fill(channel, onDone, false);
            }
        }
    }

    // ---------------- program info ----------------

    public interface OnInfo {
        void onInfo(String videoId, String description);
    }

    private static final int MAX_DESCRIPTIONS = 300;
    /** Descriptions already fetched, by video id. Main thread only. */
    private final Map<String, String> mDescriptions = new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_DESCRIPTIONS;
        }
    };
    private final Set<String> mDescriptionsAsked = new HashSet<>();
    private final ExecutorService mInfoWorker = Executors.newSingleThreadExecutor();

    /** A program's description if it was fetched before, or null. */
    public String getInfo(String videoId) {
        return videoId != null ? mDescriptions.get(videoId) : null;
    }

    /**
     * Fetches a program's description: one small request, off the main thread, once for a video.
     * Search results don't carry descriptions, so this is asked only for a program the viewer
     * rests on in the guide and for the program a channel has on next.<br/>
     * The description also says more about the language than the title does. A video whose
     * description shows another language than its channel's leaves the lineup.
     */
    public void loadInfo(final Video video, final OnInfo onInfo) {
        final String id = video != null ? video.videoId : null;

        if (id == null) {
            return;
        }

        String known = mDescriptions.get(id);

        if (known != null) {
            if (onInfo != null) {
                onInfo.onInfo(id, known);
            }
            return;
        }

        if (!mDescriptionsAsked.add(id)) {
            return; // on its way
        }

        mInfoWorker.execute(() -> {
            String text = "";

            try {
                com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata metadata =
                        YouTubeServiceManager.instance().getMediaItemService().getMetadata(id);
                if (metadata != null && metadata.getDescription() != null) {
                    text = metadata.getDescription();
                }
            } catch (Throwable e) {
                Log.e(TAG, "No description for %s: %s", id, e.getMessage());
            }

            final String description = text;

            mMain.post(() -> {
                mDescriptionsAsked.remove(id);
                mDescriptions.put(id, description);
                checkDescription(video, description);

                if (onInfo != null) {
                    onInfo.onInfo(id, description);
                }
            });
        });
    }

    /** Takes a video out of its channel when its description shows another language than the channel's. */
    private void checkDescription(Video video, String description) {
        TvChannel channel = findChannel(video);

        if (channel == null || description == null || description.isEmpty() || channel.language().isEmpty()) {
            return;
        }

        // The start of the description is about the video. Further down come links and boilerplate.
        String start = description.length() > 400 ? description.substring(0, 400) : description;
        String language = TvLanguage.detect(start);
        String channelLanguage = channel.language();
        boolean latinOnHindi = "en".equals(language) && !"en".equals(channelLanguage);

        if (language == null || channelLanguage.equals(language) || latinOnHindi) {
            return;
        }

        int index = channel.indexOf(video.videoId);

        if (index > channel.pos) {
            channel.durations.remove(channel.lineup.remove(index).videoId);
        } else if (index == channel.pos && channel.ahead() > 0 && !video.videoId.equals(channel.startedId)) {
            channel.durations.remove(channel.lineup.remove(index).videoId);
            channel.liveStartMs = 0;
        } else {
            return;
        }

        Log.d(TAG, "%s: \"%s\" left, its description reads as %s", channel.name, video.getTitle(), language);
        channel.group = null;
        topUp(channel);
        save();
    }

    /** Checks the description of what a channel has on next, so a video in the wrong language is gone before its turn. */
    private void checkNext(TvChannel channel) {
        if (!channel.language().isEmpty() && channel.ahead() > 0) {
            loadInfo(channel.lineup.get(channel.pos + 1), null);
        }
    }

    /**
     * True when a video that is already in a lineup no longer fits its channel: the title has
     * a skip word, reads as another language, or the creator is blocked. The same checks as
     * {@link #accept}, for the ones a saved video still has the data for.
     */
    private boolean stale(TvChannel channel, Video video) {
        String title = video.getTitle();

        if (title == null) {
            return false;
        }

        String lower = title.toLowerCase(Locale.ROOT);

        if (containsAny(lower, channel.skipWords) || containsAny(lower, mGlobalSkipWords)) {
            return true;
        }

        String channelLanguage = channel.language();

        if (!channelLanguage.isEmpty()) {
            String language = TvLanguage.detect(title);
            boolean latinOnHindi = "en".equals(language) && !"en".equals(channelLanguage);
            if (language != null && !channelLanguage.equals(language) && !latinOnHindi) {
                return true;
            }
        }

        String author = creatorOf(video.author);

        if (author != null) {
            for (String blocked : channel.blocked) {
                if (author.equalsIgnoreCase(blocked)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Takes videos that no longer fit out of a channel's lineup, after its skip words or language
     * rules changed. What's on now goes too, unless it is the video that was started in the player.
     */
    private void purge(TvChannel channel) {
        boolean removed = false;

        for (int i = channel.lineup.size() - 1; i > channel.pos; i--) {
            if (stale(channel, channel.lineup.get(i))) {
                channel.durations.remove(channel.lineup.remove(i).videoId);
                removed = true;
            }
        }

        Video onNow = channel.onNow();

        if (onNow != null && channel.ahead() > 0 && stale(channel, onNow) && !onNow.videoId.equals(channel.startedId)) {
            channel.durations.remove(channel.lineup.remove(channel.pos).videoId);
            channel.liveStartMs = 0; // a live channel joins the video that takes its place part-way in
            removed = true;
        }

        if (removed) {
            channel.group = null;
        }
    }

    /** Downloads the preset list in the background. A change applies now and is kept for the next start. */
    private void refreshConfig() {
        refreshConfig(null);
    }

    private void refreshConfig(final Runnable onChanged) {
        long now = System.currentTimeMillis();

        if (now - mConfigCheckedMs < CONFIG_CHECK_PERIOD_MS) {
            return;
        }

        mConfigCheckedMs = now;
        final String url = mContext.getString(R.string.tv_mode_config_url);

        mWorker.execute(() -> {
            HttpURLConnection connection = null;

            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(8_000);
                connection.setReadTimeout(8_000);

                if (connection.getResponseCode() != 200) {
                    return;
                }

                final String text = readStream(connection.getInputStream());
                new JSONObject(text); // a broken file must never replace a good one

                mMain.post(() -> {
                    if (!text.equals(mConfigText) && applyConfig(text)) {
                        mWorker.execute(() -> writeFile(new File(mContext.getFilesDir(), CONFIG_CACHE_FILE), text));
                        save();
                        if (onChanged != null) {
                            onChanged.run();
                        }
                    }
                });
            } catch (Throwable e) {
                Log.e(TAG, "TV mode config download failed: %s", e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });
    }

    /**
     * Adds preset channels that are missing and updates the ones that weren't edited on this TV.
     * Channels made or edited here are left alone.
     */
    private boolean applyConfig(String text) {
        try {
            JSONObject config = new JSONObject(text);
            JSONObject tuning = config.optJSONObject("tuning");

            if (tuning != null) {
                mMinAhead = Math.max(3, tuning.optInt("minAhead", mMinAhead));
                mMaxPerCreatorInRow = tuning.optInt("maxPerCreatorInRow", mMaxPerCreatorInRow);
                mSkipsToBlock = Math.max(2, tuning.optInt("skipsToBlock", mSkipsToBlock));
                mRecentEvery = tuning.optInt("recentEvery", mRecentEvery);
                mMinViews = tuning.optLong("minViews", mMinViews);
                mGlobalSkipWords.clear();
                JSONArray words = tuning.optJSONArray("skipWords");
                for (int i = 0; words != null && i < words.length(); i++) {
                    mGlobalSkipWords.add(words.optString(i, ""));
                }
                mGlobalSkipWords.removeAll(Collections.singletonList(""));
            }

            JSONArray genres = config.optJSONArray("genres");
            mGenreOrder.clear();
            for (int i = 0; genres != null && i < genres.length(); i++) {
                String genre = genres.optString(i, "");
                if (!genre.isEmpty()) {
                    mGenreOrder.add(genre);
                }
            }

            JSONArray ideas = config.optJSONArray("ideas");
            mIdeas.clear();
            for (int i = 0; ideas != null && i < ideas.length(); i++) {
                JSONObject idea = ideas.optJSONObject(i);
                JSONArray terms = idea != null ? idea.optJSONArray("terms") : null;
                if (terms == null || terms.length() == 0 || idea.optString("name", "").isEmpty()) {
                    continue;
                }
                List<String> entry = new ArrayList<>();
                entry.add(idea.optString("name"));
                for (int j = 0; j < terms.length(); j++) {
                    entry.add(terms.optString(j, ""));
                }
                mIdeas.add(entry);
            }

            JSONArray presets = config.optJSONArray("channels");
            int presetsAdded = 0;
            boolean hadChannels = !mChannels.isEmpty();

            for (int i = 0; presets != null && i < presets.length(); i++) {
                JSONObject preset = presets.optJSONObject(i);
                String id = preset != null ? preset.optString("id", null) : null;

                if (id == null || mDeletedPresets.contains(id)) {
                    continue;
                }

                TvChannel channel = null;

                for (TvChannel existing : mChannels) {
                    if (id.equals(existing.id)) {
                        channel = existing;
                        break;
                    }
                }

                if (channel == null) {
                    channel = new TvChannel();
                    channel.id = id;
                    channel.preset = true;
                    mChannels.add(Math.min(i, mChannels.size()), channel);
                    presetsAdded++;
                } else if (channel.edited) {
                    // Edited here, so its search stays as it is. It still gets a genre and a
                    // language when it has none, or the guide's filters would never show it.
                    if (channel.genre.isEmpty()) {
                        channel.genre = preset.optString("genre", "");
                    }
                    JSONArray presetLanguages = preset.optJSONArray("languages");
                    if (channel.languages.isEmpty() && presetLanguages != null && presetLanguages.length() > 0) {
                        channel.languages.add(presetLanguages.optString(0, TvLanguage.ANY));
                    }
                    continue;
                }

                String before = channel.searchKey();
                channel.name = preset.optString("name", id);
                channel.readSettings(preset);

                if (!before.equals(channel.searchKey())) {
                    purge(channel); // what the new rules would not have let in goes first
                    dropComingUp(channel);
                }
            }

            // A preset that left the list goes from the TV too, unless it was edited here.
            if (presets != null && presets.length() > 0) {
                Set<String> listed = new HashSet<>();
                for (int i = 0; i < presets.length(); i++) {
                    JSONObject preset = presets.optJSONObject(i);
                    if (preset != null) {
                        listed.add(preset.optString("id", ""));
                    }
                }
                for (java.util.Iterator<TvChannel> it = mChannels.iterator(); it.hasNext();) {
                    TvChannel channel = it.next();
                    if (channel.preset && !channel.edited && !listed.contains(channel.id)) {
                        it.remove();
                    }
                }
            }

            // The list grew on a TV that already had channels: put the presets in the list's order,
            // which keeps each genre together. Channels made on this TV stay after them. Only then,
            // so channels the viewer moved stay where they were put until the list itself changes.
            if (presetsAdded > 0 && hadChannels) {
                final Map<String, Integer> order = new java.util.HashMap<>();
                for (int i = 0; i < presets.length(); i++) {
                    JSONObject preset = presets.optJSONObject(i);
                    if (preset != null) {
                        order.put(preset.optString("id", ""), i);
                    }
                }
                List<TvChannel> listed = new ArrayList<>();
                List<TvChannel> others = new ArrayList<>();
                for (TvChannel channel : mChannels) {
                    (order.containsKey(channel.id) ? listed : others).add(channel);
                }
                Collections.sort(listed, (a, b) -> order.get(a.id) - order.get(b.id));
                mChannels.clear();
                mChannels.addAll(listed);
                mChannels.addAll(others);
            }

            mConfigText = text;
            mShown = null;
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Bad TV mode config: %s", e.getMessage());
            return false;
        }
    }

    /** The channel's search changed: keep what's on now and the next two, refill the rest. */
    private void dropComingUp(TvChannel channel) {
        while (channel.lineup.size() > channel.pos + 3) {
            Video removed = channel.lineup.remove(channel.lineup.size() - 1);
            channel.durations.remove(removed.videoId);
        }

        channel.group = null;
        channel.exhausted = false;
        channel.emptyFills = 0;
        channel.termCursor = 0;
    }

    // ---------------- saving ----------------

    private void loadState() {
        String text = readFile(new File(mContext.getFilesDir(), STATE_FILE));

        if (text == null) {
            return;
        }

        try {
            JSONObject state = new JSONObject(text);
            JSONArray channels = state.optJSONArray("channels");

            for (int i = 0; channels != null && i < channels.length(); i++) {
                TvChannel channel = TvChannel.fromJson(channels.getJSONObject(i));
                if (channel.id != null) {
                    mChannels.add(channel);
                }
            }

            JSONObject settings = state.optJSONObject("settings");
            if (settings != null) {
                mDefaultMode = settings.optString("defaultMode", mDefaultMode);
                mDpadSurf = settings.optBoolean("dpadSurf", mDpadSurf);
                mFavOnly = settings.optBoolean("favOnly", mFavOnly);
                mYtHistory = settings.optBoolean("ytHistory", mYtHistory);
                mSwitchEffect = settings.optString("switchEffect", mSwitchEffect);
                mScanlines = settings.optBoolean("scanlines", mScanlines);
                mSleepTurnsOff = settings.optBoolean("sleepTurnsOff", mSleepTurnsOff);
                mOpenMode = settings.optString("openMode", mOpenMode);
                mLastChannelId = settings.isNull("lastChannel") ? null : settings.optString("lastChannel", null);
                mGuideChannelId = settings.isNull("guideChannel") ? mLastChannelId : settings.optString("guideChannel", mLastChannelId);
                if (!settings.has("filterGenre")) {
                    // Saved before the guide had filters. "Favourites only" then only steered CH+ and CH-.
                    // It now hides channels in the guide, which nobody asked for by ticking the old setting.
                    mFavOnly = false;
                }
                mFilterGenre = settings.optString("filterGenre", "");
                mFilterLanguage = settings.optString("filterLanguage", "");
            }

            addAll(state.optJSONArray("watched"), mWatched);
            addAll(state.optJSONArray("deletedPresets"), mDeletedPresets);
        } catch (Throwable e) {
            Log.e(TAG, "Can't read the saved TV mode state: %s", e.getMessage());
            mChannels.clear();
        }
    }

    private static void addAll(JSONArray array, Set<String> out) {
        for (int i = 0; array != null && i < array.length(); i++) {
            String value = array.optString(i, null);
            if (value != null) {
                out.add(value);
            }
        }
    }

    private boolean mSavePending;
    private final Runnable mSaveNow = this::saveNow;

    /**
     * Saves soon. With 100 channels a change comes every moment while they fill, and writing the
     * whole state each time made the screen stutter.
     */
    private void save() {
        mShown = null; // every change comes through here, and a change may move a channel in or out of the filters

        if (!mSavePending) {
            mSavePending = true;
            mMain.postDelayed(mSaveNow, SAVE_DELAY_MS);
        }
    }

    private void saveNow() {
        mSavePending = false;

        try {
            final JSONObject settings = new JSONObject();
            settings.put("defaultMode", mDefaultMode);
            settings.put("dpadSurf", mDpadSurf);
            settings.put("favOnly", mFavOnly);
            settings.put("ytHistory", mYtHistory);
            settings.put("switchEffect", mSwitchEffect);
            settings.put("scanlines", mScanlines);
            settings.put("sleepTurnsOff", mSleepTurnsOff);
            settings.put("openMode", mOpenMode);
            settings.put("lastChannel", mLastChannelId);
            settings.put("guideChannel", mGuideChannelId);
            settings.put("filterGenre", mFilterGenre);
            settings.put("filterLanguage", mFilterLanguage);

            // Copies of the lists, so the worker can turn them into text while the channels go on changing.
            final List<TvChannel> channels = new ArrayList<>();
            for (TvChannel channel : mChannels) {
                channels.add(channel.copyForSave());
            }
            final List<String> watched = new ArrayList<>(mWatched);
            final List<String> deletedPresets = new ArrayList<>(mDeletedPresets);

            mWorker.execute(() -> {
                try {
                    JSONObject state = new JSONObject();
                    JSONArray array = new JSONArray();
                    for (TvChannel channel : channels) {
                        array.put(channel.toJson());
                    }
                    state.put("settings", settings);
                    state.put("channels", array);
                    state.put("watched", new JSONArray(watched));
                    state.put("deletedPresets", new JSONArray(deletedPresets));
                    writeFile(new File(mContext.getFilesDir(), STATE_FILE), state.toString());
                } catch (Throwable e) {
                    Log.e(TAG, "Can't save the TV mode state: %s", e.getMessage());
                }
            });
        } catch (Throwable e) {
            Log.e(TAG, "Can't save the TV mode state: %s", e.getMessage());
        }
    }

    private static String readFile(File file) {
        if (!file.exists()) {
            return null;
        }

        try {
            return readStream(new FileInputStream(file));
        } catch (Throwable e) {
            Log.e(TAG, "Can't read %s: %s", file.getName(), e.getMessage());
            return null;
        }
    }

    private static String readStream(InputStream stream) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;

            while ((count = stream.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }

            return out.toString("UTF-8");
        } finally {
            stream.close();
        }
    }

    /** Writes to a temp file first, so a crash mid-write can't leave half a file. */
    private static void writeFile(File file, String text) {
        File temp = new File(file.getPath() + ".tmp");

        try {
            FileOutputStream out = new FileOutputStream(temp);
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }

            if (!temp.renameTo(file)) {
                file.delete();
                temp.renameTo(file);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Can't write %s: %s", file.getName(), e.getMessage());
        }
    }
}

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
    /** Marks a video played by the Random button. It belongs to no channel. */
    public static final String RANDOM = "@random";
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
    /** Languages every channel plays unless it has its own setting. Empty = any. */
    private final List<String> mLanguages = new ArrayList<>();
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

        for (int i = 0; i < mChannels.size(); i++) {
            final TvChannel channel = mChannels.get(i);

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

        if (isLive(channel)) {
            onNow.pendingPosMs = liveOffsetMs(channel);
        }

        return onNow;
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

        for (int n = 1; n < mChannels.size() && rows.size() < PLAYER_ROWS; n++) {
            int index = (start + n) % mChannels.size();
            TvChannel channel = mChannels.get(index);
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
        boolean favOnly = false;

        if (mFavOnly) {
            for (TvChannel channel : mChannels) {
                favOnly |= channel.fav;
            }
        }

        for (int n = 1; n < mChannels.size(); n++) {
            int index = ((start + dir * n) % mChannels.size() + mChannels.size()) % mChannels.size();
            TvChannel channel = mChannels.get(index);

            if (favOnly && !channel.fav) {
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
        boolean favOnly = false;

        if (mFavOnly) {
            for (TvChannel channel : mChannels) {
                favOnly |= channel.fav;
            }
        }

        for (int n = 1; n < mChannels.size(); n++) {
            int index = ((start + dir * n) % mChannels.size() + mChannels.size()) % mChannels.size();
            TvChannel channel = mChannels.get(index);

            if (favOnly && !channel.fav) {
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

    // ---------------- languages ----------------

    public List<String> getLanguages() {
        ensureLoaded();
        return mLanguages;
    }

    /** The languages a channel plays: its own setting, or TV settings. Empty = any. */
    public List<String> languagesOf(TvChannel channel) {
        if (channel.languages.isEmpty()) {
            return mLanguages;
        }

        return channel.languages.contains(TvLanguage.ANY) ? Collections.<String>emptyList() : channel.languages;
    }

    /** Switches a language on or off in TV settings. The channels follow a moment later, all at once. */
    public void setLanguage(String code, boolean on) {
        mLanguages.remove(code);

        if (on) {
            mLanguages.add(code);
        }

        save();
        mMain.removeCallbacks(mApplyLanguages);
        mMain.postDelayed(mApplyLanguages, 3_000); // several boxes are usually ticked in a row
    }

    private final Runnable mApplyLanguages = () -> {
        for (TvChannel channel : mChannels) {
            if (channel.languages.isEmpty()) { // channels with their own setting aren't touched
                rebuildComingUp(channel);
                fill(channel, null, false);
            }
        }
        save();
    };

    /** Keeps what a channel has on and drops the rest, so the next search builds it again. */
    private void rebuildComingUp(TvChannel channel) {
        dropComingUp(channel);

        while (channel.lineup.size() > channel.pos + 1) {
            channel.durations.remove(channel.lineup.remove(channel.lineup.size() - 1).videoId);
        }

        channel.missed.clear();
        String prefix = channel.id + "|";
        for (String key : new ArrayList<>(mPages.keySet())) {
            if (key.startsWith(prefix)) {
                mPages.remove(key);
            }
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
        // Sources take turns: each search term, then each YouTube channel.
        final int turn = Math.abs(channel.termCursor++) % (channel.terms.size() + channel.creators.size());
        final boolean fromCreator = turn >= channel.terms.size();
        final String term = fromCreator ? channel.creators.get(turn - channel.terms.size()) : channel.terms.get(turn);
        final String knownCreatorId = fromCreator ? channel.creatorIds.get(term) : null;
        // Every few searches one asks for this week's uploads, so a channel isn't only YouTube's
        // evergreen results. Not when the channel has its own date or order setting.
        final boolean recent = !fromCreator && mRecentEvery > 0 && channel.fills % mRecentEvery == mRecentEvery - 1
                && TvChannel.ANY.equals(channel.uploaded) && TvChannel.ORDER_MIXED.equals(channel.order);
        channel.fills++;
        final int options = fromCreator ? 0 : recent ? SearchOptions.UPLOAD_DATE_THIS_WEEK : searchOptions(channel);
        // A plain search returns mostly English. With a language filter the searches take turns
        // between the languages, each with the language's name added, or the filter would throw
        // nearly everything away and the channel would run dry.
        final List<String> languages = languagesOf(channel);
        final String languageWord = fromCreator || languages.isEmpty() ? "" : TvLanguage.searchWord(languages.get(channel.fills % languages.size()));
        final String query = languageWord.isEmpty() ? term : term + " " + languageWord;
        final String pageKey = channel.id + "|" + (fromCreator ? "@" : "") + query + "|" + options;
        final String searchKey = channel.searchKey();

        (urgent ? mWorker : mBackground).execute(() -> {
            final List<MediaItem> found = new ArrayList<>();
            final String[] creatorId = {knownCreatorId};

            try {
                ContentService service = YouTubeServiceManager.instance().getContentService();
                MediaGroup previous = mPages.get(pageKey);
                List<MediaGroup> groups;

                if (previous != null) {
                    MediaGroup next = service.continueGroup(previous);
                    groups = next != null ? Collections.singletonList(next) : new ArrayList<MediaGroup>();
                } else if (fromCreator) {
                    if (creatorId[0] == null) {
                        creatorId[0] = findCreatorId(service, term);
                    }
                    groups = creatorId[0] != null ? service.getChannelObserve(creatorId[0]).blockingFirst() : new ArrayList<MediaGroup>();
                } else {
                    groups = service.getSearch(query, options);
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

                if (lastPage != null) {
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
     * YouTube takes only some filter combinations (see SearchFilterHelper), so one filter is sent
     * with the search and the rest are applied to the results in {@link #accept}.
     */
    private static int searchOptions(TvChannel channel) {
        if (TvChannel.LENGTH_SHORT.equals(channel.length)) {
            return SearchOptions.DURATION_UNDER_4;
        } else if (TvChannel.LENGTH_MEDIUM.equals(channel.length)) {
            return SearchOptions.DURATION_BETWEEN_4_20;
        } else if (TvChannel.LENGTH_LONG.equals(channel.length)) {
            return SearchOptions.DURATION_OVER_20;
        } else if (TvChannel.UPLOADED_MONTH.equals(channel.uploaded)) {
            return SearchOptions.UPLOAD_DATE_THIS_MONTH;
        } else if (TvChannel.UPLOADED_YEAR.equals(channel.uploaded)) {
            return SearchOptions.UPLOAD_DATE_THIS_YEAR;
        } else if (TvChannel.ORDER_NEW.equals(channel.order)) {
            return SearchOptions.SORT_BY_UPLOAD_DATE;
        } else if (TvChannel.ORDER_VIEWS.equals(channel.order)) {
            return SearchOptions.SORT_BY_VIEW_COUNT;
        } else if (TvChannel.QUALITY_4K.equals(channel.quality)) {
            return SearchOptions.FEATURE_4K;
        }

        return SearchOptions.TYPE_VIDEO;
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
            if (video.getDurationMs() > 0) {
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
        if (item.getVideoId() == null || item.getTitle() == null || item.isLive() || item.isUpcoming() || item.isMovie()) {
            return false;
        }

        long durationMs = item.getDurationMs();

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

        List<String> languages = languagesOf(channel);

        if (!languages.isEmpty()) {
            String language = TvLanguage.detect(item.getTitle());
            if (language != null && !languages.contains(language)) {
                return false;
            }
        }

        String title = item.getTitle().toLowerCase(Locale.ROOT);

        if (containsAny(title, channel.skipWords) || containsAny(title, mGlobalSkipWords)) {
            return false;
        }

        if (TvChannel.QUALITY_4K.equals(channel.quality) && searchOptions(channel) != SearchOptions.FEATURE_4K
                && !title.contains("4k") && !title.contains("uhd")) {
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

    /** Downloads the preset list in the background. A change applies now and is kept for the next start. */
    private void refreshConfig() {
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
                } else if (channel.edited) {
                    continue;
                }

                String before = channel.searchKey();
                channel.name = preset.optString("name", id);
                channel.readSettings(preset);

                if (!before.equals(channel.searchKey())) {
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

            mConfigText = text;
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
                JSONArray languages = settings.optJSONArray("languages");
                for (int i = 0; languages != null && i < languages.length(); i++) {
                    mLanguages.add(languages.optString(i));
                }
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
            settings.put("languages", new JSONArray(mLanguages));

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

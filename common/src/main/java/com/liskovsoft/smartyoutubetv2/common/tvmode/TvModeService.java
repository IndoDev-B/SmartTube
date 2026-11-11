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
    private final Map<String, List<String>> mPlaylists = new ConcurrentHashMap<>();
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
    /** YouTube's Data API with the viewer's own key. Not in use until a key is entered in TV settings. */
    private final TvDataApi mApi = new TvDataApi();
    private static final int MAX_FACTS = 4_000;
    /** What the Data API said about a video, by video id. Read on the main thread, filled by the workers. */
    private final Map<String, TvDataApi.Facts> mFacts = Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, TvDataApi.Facts>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, TvDataApi.Facts> eldest) {
                    return size() > MAX_FACTS;
                }
            });
    private boolean mLineupsChecked;
    /** Videos the Data API no longer knows: deleted or private. */
    private final Set<String> mGone = Collections.synchronizedSet(new HashSet<String>());
    /** Where the next Data API search or chart page of a source continues. Worker threads only. */
    private final Map<String, String> mApiPages = new ConcurrentHashMap<>();
    /** A Data API search costs 100 of the day's 10,000 units, so they are rationed: this many a day... */
    private int mApiSearchesPerDay = 60;
    /** ...shared out among the channels that search that way, and one channel never more often than this. */
    private long mApiSearchGapMs = 2 * 60 * 60_000L;
    /** A chart page costs 1 unit. The chart itself changes a few times a day. */
    private long mChartGapMs = 2 * 60 * 60_000L;

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

        switchAccount(); // whoever is signed in now sees their own channels, favourites and history

        if (!mLineupsChecked) { // once after a start: saved lineups come back without what the Data API said
            mLineupsChecked = true;
            checkLineups(null);
        }

        refreshFresh();

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
        if (channel.ahead() < minAhead(channel) && !channel.loading && System.currentTimeMillis() - channel.lastFillMs > TOP_UP_PERIOD_MS) {
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
            if (!isFresh(channel) && !channel.isSeries() && !mWatched.contains(passed.videoId) && !channel.missed.contains(passed)) {
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

        // Starting a video doesn't make it watched: see watching(), below, once the channel is on it.

        // The player takes "next" from the video's row, which must be the channel's whole lineup.
        if (channel.group == null || !channel.group.contains(video) || video.getGroup() != channel.group) {
            buildGroup(channel, mChannels.indexOf(channel) + 1);
            video.setGroup(channel.group);
        }

        // Leaving a video early says nothing: it may be idle flicking through the channels. A
        // creator is blocked only from the channel's menu, never by counting skips.
        String note = null;
        String toBlock = null;
        long now = System.currentTimeMillis();

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
        // From where it starts: part-way in on Live TV, where it was left on a channel that waits.
        watching(channel, video.videoId, isLive(channel) ? liveOffsetMs(channel) : savedPositionMs(video.videoId));

        // Keep a short tail of what already played.
        int extra = channel.pos - MAX_PAST;
        for (int i = 0; i < extra; i++) {
            Video removed = channel.lineup.remove(0);
            channel.durations.remove(removed.videoId);
            channel.pos--;
        }

        if (channel.ahead() < minAhead(channel)) {
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
        mWatchedAt.put(videoId, System.currentTimeMillis());

        if (mWatched.size() > MAX_WATCHED) {
            String oldest = mWatched.iterator().next();
            mWatched.remove(oldest);
            mWatchedAt.remove(oldest);
        }
    }

    // ---------------- channels whose videos go stale ----------------

    /**
     * For how many days a video stays showable, by program type: news and trending are old
     * within days, where a film is as good next year. From the preset list's "freshDays".
     * A type that isn't named keeps its videos for good.
     */
    private final Map<String, Integer> mFreshDays = new java.util.HashMap<>();
    /** A fresh channel looks at its sources again this often while the app is open, even with a full lineup. */
    private static final long FRESH_REFILL_MS = 3 * 60 * 60_000L;
    private static final java.util.regex.Pattern AGO = java.util.regex.Pattern.compile(
            "(\\d+)\\s+(second|minute|hour|day|week|month|year)s?\\s+ago", java.util.regex.Pattern.CASE_INSENSITIVE);

    private int freshDays(TvChannel channel) {
        Integer days = mFreshDays.get(channel.kind.toLowerCase(Locale.ROOT));
        return days != null ? days : 0;
    }

    private boolean isFresh(TvChannel channel) {
        return freshDays(channel) > 0;
    }

    /** Uploaded longer ago than the channel shows. A video whose upload time isn't known is not too old. */
    private boolean tooOld(TvChannel channel, long publishedMs) {
        int days = freshDays(channel);
        return days > 0 && publishedMs > 0 && System.currentTimeMillis() - publishedMs > days * 24L * 60 * 60_000;
    }

    /** "3 days ago" in a search result's second line, as a time. 0 when the line doesn't say. */
    private static long publishedFrom(CharSequence text) {
        if (text == null) {
            return 0;
        }

        java.util.regex.Matcher matcher = AGO.matcher(text);

        if (!matcher.find()) {
            return 0;
        }

        long count = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2).toLowerCase(Locale.ROOT);
        long unitMs = "second".equals(unit) ? 1_000L : "minute".equals(unit) ? 60_000L : "hour".equals(unit) ? 3_600_000L
                : "day".equals(unit) ? 86_400_000L : "week".equals(unit) ? 7 * 86_400_000L : "month".equals(unit) ? 30 * 86_400_000L : 365 * 86_400_000L;

        return System.currentTimeMillis() - count * unitMs;
    }

    /** How many videos a channel keeps ready. A fresh channel keeps fewer: what waits longest goes stale. */
    private int minAhead(TvChannel channel) {
        return isFresh(channel) ? Math.max(4, mMinAhead / 2) : mMinAhead;
    }

    /**
     * Fresh channels, when the guide opens: what went past its shelf life while nobody looked
     * goes, and a channel that hasn't looked at its sources for a while looks again, so today's
     * videos are next instead of at the end of an old queue.
     */
    private void refreshFresh() {
        long now = System.currentTimeMillis();

        for (TvChannel channel : mChannels) {
            if (!isFresh(channel) || channel.loading) {
                continue;
            }

            int before = channel.lineup.size();
            purge(channel);
            boolean due = now - channel.lastFillMs > FRESH_REFILL_MS;

            if (due) {
                dropComingUp(channel);
            }

            if (due || channel.lineup.size() != before) {
                channel.emptyFills = 0;
                fill(channel, null, false);
            }
        }
    }

    // ---------------- what counts as watched ----------------

    /** When each watched video was watched, by video id. A video without a time was watched before times were kept. */
    private final Map<String, Long> mWatchedAt = new java.util.HashMap<>();
    /**
     * After how many days a watched video may be shown again, by program type. A song is wanted
     * again within days, a film after months, and free films are not endless: sooner or later
     * they have to repeat. -1 = never. From the preset list's "rewatchDays".
     */
    private final Map<String, Integer> mRewatchDays = new java.util.HashMap<>();
    private int mRewatchDaysDefault = 30;
    /** A video counts as watched once this much of it was seen. Flicking past a channel leaves no mark. */
    private static final double WATCHED_SHARE = 0.7;
    /** On Live TV: this share of the program, or this long, whichever is less. */
    private static final double LIVE_WATCHED_SHARE = 0.3;
    private static final long LIVE_WATCHED_MS = 15 * 60_000;

    /** Watched, and too recently to come back on this channel. */
    private boolean isWatched(TvChannel channel, String videoId) {
        if (videoId == null || !mWatched.contains(videoId)) {
            return false;
        }

        Integer days = mRewatchDays.get(channel.kind.toLowerCase(Locale.ROOT));
        int rest = days != null ? days : mRewatchDaysDefault;
        Long at = mWatchedAt.get(videoId);

        return rest < 0 || at == null || System.currentTimeMillis() - at < rest * 24L * 60 * 60_000;
    }

    /** Where a video was left, from the app's own record of positions. 0 when it was never started. */
    private long savedPositionMs(String videoId) {
        try {
            com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State state =
                    com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.instance(mContext).getByVideoId(videoId);
            return state != null ? Math.max(0, state.positionMs) : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    // What is being watched now, in the player or in the guide's preview: both are watching.
    private TvChannel mWatchingChannel;
    private String mWatchingId;
    private long mWatchingSinceMs;
    /** How far into the video it was when the watching began. */
    private long mWatchingFromMs;

    /**
     * A video is on screen from now, starting this far in. Whatever was on before is settled:
     * watched if enough of it was seen, otherwise it stays unwatched and can come round again.
     */
    public void watching(TvChannel channel, String videoId, long fromMs) {
        if (channel != null && videoId != null && videoId.equals(mWatchingId) && channel == mWatchingChannel) {
            return; // still the same video
        }

        stopWatching();

        if (channel != null && videoId != null) {
            mWatchingChannel = channel;
            mWatchingId = videoId;
            mWatchingSinceMs = System.currentTimeMillis();
            mWatchingFromMs = Math.max(0, fromMs);
        }
    }

    /** Nothing is on screen any more. */
    public void stopWatching() {
        TvChannel channel = mWatchingChannel;
        String videoId = mWatchingId;
        mWatchingChannel = null;
        mWatchingId = null;

        if (channel == null || videoId == null || !mChannels.contains(channel)) {
            return;
        }

        Long known = channel.durations.get(videoId);
        long durationMs = known != null && known > 0 ? known : 0;
        long seenMs = System.currentTimeMillis() - mWatchingSinceMs;

        if (durationMs <= 0 || seenMs <= 0) {
            return; // a stream, or a length nobody knows: there is no share to speak of
        }

        seenMs = Math.min(seenMs, Math.max(0, durationMs - mWatchingFromMs));
        // Live TV runs on the clock, and a program is usually joined part-way: sitting through
        // 30% of it, or a quarter of an hour of a long one, is having watched it.
        // A channel that waits for its viewer counts from the start: carrying on where it was left
        // finishes the film, though no single sitting saw most of it.
        boolean enough = isLive(channel) ? seenMs >= Math.min(LIVE_WATCHED_SHARE * durationMs, LIVE_WATCHED_MS)
                : mWatchingFromMs + seenMs >= WATCHED_SHARE * durationMs && seenMs >= Math.min(5 * 60_000, 0.2 * durationMs);

        if (enough) {
            remember(videoId);
            save();
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

            // It ran, but nobody saw it: it may come back later. Not on a channel whose videos go
            // stale, where yesterday's is no better for having been missed, and not an episode,
            // which its series keeps count of.
            if (!isFresh(channel) && !channel.isSeries() && !mWatched.contains(passed.videoId) && !channel.missed.contains(passed)) {
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
                        && (mFilterKind.isEmpty() || mFilterKind.equalsIgnoreCase(channel.kind))
                        && (mFilterLanguage.isEmpty() || mFilterLanguage.equals(channel.language()))) {
                    shown.add(channel);
                }
            }

            mShown = shown;
        }

        return mShown;
    }

    // ---------------- program types ----------------

    /** The guide's Type filter: only channels with this kind of program are shown. Empty = any. */
    private String mFilterKind = "";
    /** Program types in the order the preset list names them. */
    private final List<String> mKindOrder = new ArrayList<>();
    /**
     * What a channel built from a program type searches for and how it filters. One row for a
     * type: its name, then the search patterns with {genre} and {language} to fill in, then
     * settings as name=value. Used when a type is picked in the editor or in New channel.
     */
    private static final String[][] KINDS = {
            {"Movies", "{language} {genre} full movie", "{genre} movie {language} full length", "type=movie", "minMinutes=60", "playlists=true"},
            {TvChannel.KIND_SERIES, "{language} {genre} serial all episodes", "{genre} series full episodes {language}"},
            {"Songs", "{language} {genre} songs", "{genre} music video {language}", "length=medium", "playlists=true"},
            {"Shows", "{language} {genre} show full episode", "{genre} show {language}", "length=long"},
            {"Documentary", "{genre} documentary {language}", "{language} documentary {genre} full", "length=long"},
            {"Podcasts", "{genre} podcast full episode {language}", "{language} {genre} podcast", "length=long"},
            {"News", "{language} {genre} news", "{genre} news analysis {language}", "length=medium", "uploaded=week"},
            {"Sports", "{genre} highlights {language}", "{language} {genre} full match", "length=medium"},
            {"Kids", "{genre} for kids {language}", "{language} kids {genre}"},
            {"Learning", "{genre} explained {language}", "{language} {genre} lecture", "length=medium"},
            {"Lifestyle", "{language} {genre}", "{genre} vlog {language}", "length=medium"},
            {"Gaming", "{genre} gameplay {language}", "{language} {genre} gaming", "length=medium"},
            {"Ambience", "{genre} ambience", "{genre} 4k relaxing", "length=long"},
            {"Live streams", "{genre} live {language}", "{language} {genre} live stream", "feature=live"},
            {"Trending", "{language} {genre}", "{genre} viral {language}", "length=medium", "order=trending"},
    };

    public String getFilterKind() {
        ensureLoaded();
        return mFilterKind;
    }

    public void setFilterKind(String kind) {
        mFilterKind = kind != null ? kind : "";
        save();
    }

    /** The program types that have channels: first the ones the preset list names, in its order, then any others. */
    public List<String> getKinds() {
        ensureLoaded();
        List<String> kinds = new ArrayList<>();

        for (String kind : mKindOrder) {
            for (TvChannel channel : mChannels) {
                if (kind.equalsIgnoreCase(channel.kind)) {
                    kinds.add(kind);
                    break;
                }
            }
        }

        for (TvChannel channel : mChannels) {
            if (!channel.kind.isEmpty() && !containsIgnoreCase(kinds, channel.kind)) {
                kinds.add(channel.kind);
            }
        }

        return kinds;
    }

    /** Every program type a channel can be given, whether or not a channel has it yet. */
    public List<String> getAllKinds() {
        ensureLoaded();
        List<String> kinds = new ArrayList<>();

        for (String[] kind : KINDS) {
            kinds.add(kind[0]);
        }

        for (String kind : getKinds()) {
            if (!containsIgnoreCase(kinds, kind)) {
                kinds.add(kind);
            }
        }

        return kinds;
    }

    /** Every genre a channel can be given: the preset list's, then any others channels have. */
    public List<String> getAllGenres() {
        ensureLoaded();
        List<String> genres = new ArrayList<>(mGenreOrder);

        for (TvChannel channel : mChannels) {
            if (!channel.genre.isEmpty() && !containsIgnoreCase(genres, channel.genre)) {
                genres.add(channel.genre);
            }
        }

        return genres;
    }

    private static String[] kindRow(String kind) {
        for (String[] row : KINDS) {
            if (row[0].equalsIgnoreCase(kind)) {
                return row;
            }
        }

        return null;
    }

    /** The search terms a channel of this type, genre and language starts with. */
    private static List<String> termsFor(String kind, String genre, String language) {
        List<String> terms = new ArrayList<>();
        String[] row = kindRow(kind);
        String genreWord = genre != null ? genre.toLowerCase(Locale.ROOT) : "";
        String languageWord = language == null || language.isEmpty() || TvLanguage.ANY.equals(language) ? "" : TvLanguage.nameOf(language).toLowerCase(Locale.ROOT);

        for (int i = 1; row != null && i < row.length; i++) {
            if (row[i].contains("=")) {
                continue;
            }

            String term = row[i].replace("{genre}", genreWord).replace("{language}", languageWord).replaceAll("\\s+", " ").trim();
            if (!term.isEmpty() && !terms.contains(term)) {
                terms.add(term);
            }
        }

        if (terms.isEmpty() && !genreWord.isEmpty()) {
            terms.add((languageWord + " " + genreWord).trim());
        }

        return terms;
    }

    /** Sets the filters that suit a program type. The search terms and everything else stay. */
    private static void applyKind(TvChannel channel, String kind) {
        channel.kind = kind != null ? kind : "";
        channel.type = TvChannel.TYPE_VIDEO;
        channel.length = TvChannel.ANY;
        channel.uploaded = TvChannel.ANY;
        channel.order = TvChannel.ORDER_MIXED;
        channel.minMinutes = 0;
        channel.playlists = false;
        channel.features.remove(TvChannel.FEATURE_LIVE);
        String[] row = kindRow(kind);

        for (int i = 1; row != null && i < row.length; i++) {
            int equals = row[i].indexOf('=');
            if (equals < 0) {
                continue;
            }

            String name = row[i].substring(0, equals);
            String value = row[i].substring(equals + 1);

            if ("type".equals(name)) {
                channel.type = value;
            } else if ("length".equals(name)) {
                channel.length = value;
            } else if ("uploaded".equals(name)) {
                channel.uploaded = value;
            } else if ("order".equals(name)) {
                channel.order = value;
            } else if ("minMinutes".equals(name)) {
                channel.minMinutes = Integer.parseInt(value);
            } else if ("playlists".equals(name)) {
                channel.playlists = "true".equals(value);
            } else if ("feature".equals(name) && !channel.features.contains(value)) {
                channel.features.add(value);
            }
        }
    }

    /** The editor's Program type: the channel takes the type's filters, and what was queued under the old ones goes. */
    public void setKind(TvChannel channel, String kind, Runnable onDone) {
        applyKind(channel, kind);
        mShown = null;
        onSearchChanged(channel, onDone);
    }

    /**
     * New channel from a program type, a genre and a language. A Series channel starts without
     * videos: its playlists are picked next, with {@link #findSeries}.
     */
    public TvChannel build(String kind, String genre, String language, String origin, Runnable onDone) {
        TvChannel channel = new TvChannel();
        channel.id = "my-" + System.currentTimeMillis();
        channel.genre = genre != null ? genre : "";
        channel.languages.add(language == null || language.isEmpty() ? TvLanguage.ANY : language);
        String languageName = channel.language().isEmpty() ? "" : TvLanguage.nameOf(channel.language());
        String[] from = originRow(origin);

        if (from == null) {
            channel.name = (languageName + " " + channel.genre + " " + kind).replaceAll("\\s+", " ").trim();
            channel.terms.addAll(termsFor(kind, genre, channel.language()));
        } else {
            // Where it is from is one thing and what is spoken another: Korean films in English,
            // Nollywood in English, Hollywood in Hindi. The origin's word goes into the searches,
            // its countries are the ones the Data API searches as, and a video has to name the
            // origin somewhere. In a language that isn't the origin's own it is a dub, so it has
            // to say that too.
            channel.origin = from[0];
            channel.name = (from[0] + " " + channel.genre + " " + kind + (languageName.isEmpty() ? "" : " in " + languageName)).replaceAll("\\s+", " ").trim();
            channel.terms.addAll(termsFor(kind, (from[2] + " " + channel.genre).trim(), channel.language()));
            channel.region = from[1];
            if (!from[3].isEmpty()) {
                channel.needWords.add(from[3]);
            }
            if (!languageName.isEmpty() && !java.util.Arrays.asList(from[4].split(",")).contains(channel.language())) {
                channel.needWords.add(languageName.toLowerCase(Locale.ROOT) + "|dubbed|dub");
            }
        }

        applyKind(channel, kind);
        channel.edited = true;
        mChannels.add(channel);
        mShown = null;
        save();
        fill(channel, onDone);
        return channel;
    }

    /**
     * Where a channel's programs can be from, for New channel: a name, the countries the Data API
     * searches as ("NG,GH,KE,ZA", taking turns), the word that goes into the searches, and the
     * words one of which a video has to carry ("nollywood|nigerian|ghana"), and the languages
     * its programs are made in ("en"): in any other language they are dubs. From the preset
     * list's "origins", so a region can be added without a new build.
     */
    private final List<String[]> mOrigins = new ArrayList<>();

    public List<String> getOrigins() {
        ensureLoaded();
        List<String> names = new ArrayList<>();

        for (String[] origin : mOrigins) {
            names.add(origin[0]);
        }

        return names;
    }

    private String[] originRow(String name) {
        for (String[] origin : mOrigins) {
            if (origin[0].equalsIgnoreCase(name)) {
                return origin;
            }
        }

        return null;
    }

    // ---------------- series: playlists played in order ----------------

    /** The episodes of a playlist, in its order. Read once for a run of the app. */
    private final Map<String, List<Video>> mEpisodes = new ConcurrentHashMap<>();
    private final Map<String, Long> mEpisodesFailed = new ConcurrentHashMap<>();
    /** A playlist is read to its end, in pages, up to this many. */
    private static final int MAX_EPISODE_PAGES = 60;
    private static final int SERIES_FOUND = 14;

    public interface OnSeriesFound {
        void onFound(List<TvChannel.Series> found);
    }

    /** Searches for playlists to play as a series. Shows and courses, which YouTube keeps in order itself, come first. */
    public void findSeries(final String query, final OnSeriesFound onFound) {
        mWorker.execute(() -> {
            final List<TvChannel.Series> found = new ArrayList<>();

            try {
                // The app's own search returns videos only, so the playlists are asked for directly.
                for (TvChannel.Series one : TvPlaylistSearch.find(query)) {
                    if (found.size() < SERIES_FOUND) {
                        Log.d(TAG, "Series search \"%s\": %s | %s | %s | %s", query, one.playlistId, one.title, one.author, one.count);
                        found.add(one);
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "Series search failed for %s: %s", query, e.getMessage());
            }

            Log.d(TAG, "Series search \"%s\": %s found", query, found.size());
            mMain.post(() -> onFound.onFound(found));
        });
    }

    /** The first number in a text like "139 episodes", or 0. */
    private static int countIn(CharSequence text) {
        if (text == null) {
            return 0;
        }

        java.util.regex.Matcher matcher = COUNT.matcher(text);

        try {
            return matcher.find() ? Integer.parseInt(matcher.group(1).replace(",", "")) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final java.util.regex.Pattern COUNT = java.util.regex.Pattern.compile("([\\d,]+)\\s*(?:episodes|videos|lessons|episode|video)");

    /** A playlist's name without what uploaders pile on after it: the cast, "Full Episodes", the network. */
    public static String seriesName(String title) {
        if (title == null) {
            return "";
        }

        int cut = title.length();

        for (String separator : new String[] {" - ", " | ", " || ", " (", " : "}) {
            int at = title.indexOf(separator);
            if (at > 3 && at < cut) {
                cut = at;
            }
        }

        return title.substring(0, cut).trim();
    }

    public void addSeries(TvChannel channel, TvChannel.Series one, Runnable onDone) {
        if (one == null || one.playlistId == null || channel.findSeries(one.playlistId) != null) {
            return;
        }

        one.title = seriesName(one.title);
        one.next = 0;
        channel.series.add(one);
        channel.edited = true;
        save();
        fill(channel, onDone);
    }

    public void removeSeries(TvChannel channel, TvChannel.Series one, Runnable onDone) {
        channel.series.remove(one);
        channel.seriesTurn = 0;
        channel.edited = true;

        // Its episodes that are queued go. What is on now plays out.
        for (int i = channel.lineup.size() - 1; i > channel.pos; i--) {
            if (channel.seriesOf(channel.lineup.get(i).videoId) == null) {
                unqueue(channel, channel.lineup.remove(i));
            }
        }

        channel.group = null;
        save();
        fill(channel, onDone);
    }

    /** Moves a series to an episode, counted from 1. What is queued is rebuilt from there. */
    public void setEpisode(TvChannel channel, TvChannel.Series one, int episode, Runnable onDone) {
        requeue(channel);
        one.next = Math.max(0, episode - 1);
        channel.edited = true;
        save();
        fill(channel, onDone);
    }

    public void setSeriesRun(TvChannel channel, int run, Runnable onDone) {
        requeue(channel);
        channel.seriesRun = Math.max(0, run);
        channel.edited = true;
        save();
        fill(channel, onDone);
    }

    public void setSeriesLoop(TvChannel channel, boolean loop) {
        channel.seriesLoop = loop;
        channel.edited = true;
        save();
    }

    /** Takes back everything that is queued after what's on now, so the next fill deals the episodes again. */
    private void requeue(TvChannel channel) {
        while (channel.lineup.size() > channel.pos + 1) {
            unqueue(channel, channel.lineup.remove(channel.lineup.size() - 1));
        }

        channel.group = null;
    }

    /**
     * Forgets a video that left the lineup before its turn. An episode goes back to its series:
     * the series continues from it, so nothing is skipped by a shuffle or an edit.
     */
    private void unqueue(TvChannel channel, Video removed) {
        channel.durations.remove(removed.videoId);
        TvChannel.Series one = channel.seriesOf(removed.videoId);
        int number = channel.episodeOf(removed.videoId);

        if (one != null && number >= 0) {
            one.next = Math.min(one.next, number);
        }

        channel.episodes.remove(removed.videoId);
        channel.published.remove(removed.videoId);
    }

    /** A Series channel's fill: reads the playlists that haven't been read, then deals out the next episodes. */
    private void fillSeries(final TvChannel channel, final Runnable onDone, final boolean urgent) {
        if (channel.series.isEmpty()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }

        if (channel.loading) {
            if (onDone != null) {
                channel.waiters.add(onDone);
            }
            return;
        }

        channel.loading = true;
        channel.lastFillMs = System.currentTimeMillis();
        final List<String[]> toRead = new ArrayList<>(); // playlist id, page key

        for (TvChannel.Series one : channel.series) {
            Long failedAt = mEpisodesFailed.get(one.playlistId);
            // A playlist that couldn't be read is left alone for a while: the guide asks again every few seconds.
            if (!mEpisodes.containsKey(one.playlistId) && (failedAt == null || channel.lastFillMs - failedAt > 5 * 60_000)) {
                toRead.add(new String[] {one.playlistId, one.pageKey});
            }
        }

        (urgent ? mWorker : mBackground).execute(() -> {
            for (String[] playlist : toRead) {
                List<Video> episodes = readEpisodes(playlist[0], playlist[1]);
                Log.d(TAG, "%s: playlist %s has %s episodes", channel.name, playlist[0], episodes.size());
                if (!episodes.isEmpty()) {
                    mEpisodes.put(playlist[0], episodes);
                    mEpisodesFailed.remove(playlist[0]);
                } else {
                    mEpisodesFailed.put(playlist[0], System.currentTimeMillis());
                }
            }

            mMain.post(() -> {
                channel.loading = false;

                if (mChannels.contains(channel) && channel.isSeries()) {
                    int added = dealEpisodes(channel);
                    Log.d(TAG, "%s: +%s episodes", channel.name, added);
                    if (added > 0) {
                        save();
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

    /** Every video of a playlist, in the playlist's order. Worker threads. */
    private List<Video> readEpisodes(String playlistId, String pageKey) {
        List<Video> episodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        try {
            ContentService service = YouTubeServiceManager.instance().getContentService();
            // YouTube serves a playlist as a channel whose id is "VL" and the playlist's id. That
            // works for an id from anywhere. The page key a search result carries is a second way in.
            List<MediaGroup> groups = service.getChannelObserve("VL" + playlistId).blockingFirst();
            MediaGroup group = null;

            for (int g = 0; groups != null && g < groups.size(); g++) {
                MediaGroup one = groups.get(g);
                if (one != null && one.getMediaItems() != null && !one.getMediaItems().isEmpty() && (group == null || one.getNextPageKey() != null)) {
                    group = one;
                }
            }

            if (group == null && pageKey != null) {
                group = service.getGroup(pageKey);
            }

            for (int page = 0; group != null && page < MAX_EPISODE_PAGES; page++) {
                List<MediaItem> items = group.getMediaItems();

                for (int i = 0; items != null && i < items.size(); i++) {
                    MediaItem item = items.get(i);

                    if (item == null || item.getVideoId() == null || item.getTitle() == null || !seen.add(item.getVideoId())) {
                        continue;
                    }

                    Video video = Video.from(item);
                    video.playlistId = null; // plays as a single video: the channel decides what is next
                    video.playlistParams = null;
                    video.playlistIndex = -1;
                    episodes.add(video);
                }

                if (group.getNextPageKey() == null) {
                    break;
                }

                group = service.continueGroup(group);
            }
        } catch (Throwable e) {
            Log.e(TAG, "Can't read playlist %s: %s", playlistId, e.getMessage());
        }

        if (runsBackwards(episodes)) {
            Collections.reverse(episodes);
            Log.d(TAG, "Playlist %s lists its newest episode first: turned round", playlistId);
        }

        // The app's own reader came back empty: with a Data API key the same list is one request per 50 videos.
        if (episodes.isEmpty() && mApi.ready()) {
            try {
                List<String> ids = mApi.playlist(playlistId);
                Map<String, TvDataApi.Facts> facts = mApi.videos(ids);

                for (String id : ids) {
                    TvDataApi.Facts one = facts.get(id);
                    if (one != null && one.title != null && seen.add(id)) {
                        mFacts.put(id, one);
                        episodes.add(videoOf(one));
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "Data API: can't read playlist %s: %s", playlistId, e.getMessage());
            }
        }

        return episodes;
    }

    private static final java.util.regex.Pattern EPISODE = java.util.regex.Pattern.compile(
            "(?:\\bep(?:isode)?s?\\b\\.?|\\bpart\\b|\\bchapter\\b|#|एपिसोड|भाग)\\s*[-:#]?\\s*(\\d{1,4})", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Many uploaders add each new episode at the top, so their playlist runs from the last
     * episode to the first. The episode numbers in the titles give it away: when they mostly
     * count down, the list is the wrong way round.
     */
    private static boolean runsBackwards(List<Video> episodes) {
        int up = 0;
        int down = 0;
        int previous = -1;

        for (Video episode : episodes) {
            java.util.regex.Matcher matcher = EPISODE.matcher(episode.getTitle() != null ? episode.getTitle() : "");

            if (!matcher.find()) {
                continue;
            }

            int number = Integer.parseInt(matcher.group(1));

            if (previous >= 0 && number != previous) {
                if (number > previous) {
                    up++;
                } else {
                    down++;
                }
            }

            previous = number;
        }

        if (up + down >= 4) {
            return down > up * 2;
        }

        // Too few numbers to go by. A list that opens with its finale is backwards all the same.
        String first = !episodes.isEmpty() && episodes.get(0).getTitle() != null ? episodes.get(0).getTitle().toLowerCase(Locale.ROOT) : "";
        return first.contains("last episode") || first.contains("final episode") || first.contains("finale");
    }

    /** Turns a series round, for a playlist whose order the titles don't give away. It starts again from its other end. */
    public void flipSeries(TvChannel channel, TvChannel.Series one, Runnable onDone) {
        requeue(channel);
        one.flip = !one.flip;
        one.next = 0;
        channel.edited = true;
        save();
        fill(channel, onDone);
    }

    /** A video built from what the Data API said, for a source that has no search result to build it from. */
    private static Video videoOf(TvDataApi.Facts facts) {
        Video video = new Video();
        video.videoId = facts.id;
        video.title = facts.title;
        video.author = facts.author;
        video.channelId = facts.channelId;
        video.cardImageUrl = facts.imageUrl;
        video.bgImageUrl = facts.imageUrl;
        video.isLive = facts.live;
        video.secondTitle = facts.author;
        return video;
    }

    /** Puts the next episodes at the end of the lineup: the series take turns, each from where it is. Main thread. */
    private int dealEpisodes(TvChannel channel) {
        int added = 0;
        int count = channel.series.size();
        int turn = count > 0 ? Math.abs(channel.seriesTurn) % count : 0;
        int run = channel.seriesRun <= 0 ? Integer.MAX_VALUE : channel.seriesRun;
        Set<String> queued = new HashSet<>();

        for (int i = Math.max(0, channel.pos); i < channel.lineup.size(); i++) {
            queued.add(channel.lineup.get(i).videoId);
        }

        for (int guard = 0; guard < 400 && channel.ahead() < minAhead(channel); guard++) {
            TvChannel.Series one = null;
            List<Video> episodes = null;

            for (int i = 0; i < count; i++) {
                TvChannel.Series candidate = channel.series.get((turn + i) % count);
                List<Video> its = mEpisodes.get(candidate.playlistId);

                if (its == null || its.isEmpty()) {
                    continue; // couldn't be read: the others go on without it
                }

                candidate.count = its.size();

                if (candidate.next >= its.size()) {
                    if (!channel.seriesLoop) {
                        continue;
                    }
                    candidate.next = 0;
                }

                one = candidate;
                episodes = its;
                turn = (turn + i) % count;
                break;
            }

            if (one == null) {
                break;
            }

            int dealt = 0;

            while (dealt < run && one.next < episodes.size() && channel.ahead() < minAhead(channel)) {
                Video source = episodes.get(one.flip ? episodes.size() - 1 - one.next : one.next);

                if (!queued.add(source.videoId)) {
                    dealt = -1; // a short series came round to an episode that is still queued: enough for now
                    break;
                }

                Video video = Video.from(source);
                video.playlistId = null;
                video.playlistParams = null;
                video.playlistIndex = -1;
                video.author = source.author != null ? source.author : one.author;
                video.tvChannelId = channel.id;
                channel.lineup.add(video);
                channel.episodes.put(video.videoId, one.playlistId + "#" + one.next);

                TvDataApi.Facts facts = mFacts.get(video.videoId);
                long durationMs = source.getDurationMs() > 0 ? source.getDurationMs() : facts != null ? facts.durationMs : 0;
                if (durationMs > 0) {
                    channel.durations.put(video.videoId, durationMs);
                }

                if (channel.group != null) {
                    channel.group.add(video);
                }

                one.next++;
                dealt++;
                added++;
            }

            if (dealt < 0) {
                break;
            }

            // The whole-series setting stays with a series until it ends.
            if (run != Integer.MAX_VALUE || one.next >= episodes.size()) {
                turn = (turn + 1) % count;
            }
        }

        channel.seriesTurn = turn;
        return added;
    }

    /** The episode of a series that is on or comes next on the channel, counted from 1. */
    public int episodeOn(TvChannel channel, TvChannel.Series one) {
        int lowest = -1;

        for (int i = Math.max(0, channel.pos); i < channel.lineup.size(); i++) {
            String id = channel.lineup.get(i).videoId;
            if (channel.seriesOf(id) == one) {
                int number = channel.episodeOf(id);
                lowest = lowest < 0 ? number : Math.min(lowest, number);
            }
        }

        return (lowest >= 0 ? lowest : one.next) + 1;
    }

    /** "Mahabharat · Ep 41" for an episode in a lineup, or null for a video that isn't one. */
    public String episodeLabel(TvChannel channel, Video video) {
        if (channel == null || video == null) {
            return null;
        }

        TvChannel.Series one = channel.seriesOf(video.videoId);
        int number = channel.episodeOf(video.videoId);

        return one != null && number >= 0 ? "Ep " + (number + 1) + (one.count > 0 ? " of " + one.count : "") : null;
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
            boolean episode = channel.seriesOf(removed.videoId) != null;
            unqueue(channel, removed);
            // Nobody saw these, so a shuffle doesn't use them up: they go to the back and come
            // round again later, mixed with new ones. An episode goes back to its series instead.
            if (!episode && !isFresh(channel) && !mWatched.contains(removed.videoId) && !channel.missed.contains(removed)) {
                channel.missed.add(removed);
                if (channel.missed.size() > MAX_MISSED) {
                    channel.missed.remove(0);
                }
            }
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

        if (channel.ahead() < minAhead(channel)) {
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
            unqueue(channel, channel.lineup.remove(channel.lineup.size() - 1));
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

    /** True for a preset channel that was changed on this TV, which "Reset this channel" can undo. */
    public boolean canReset(TvChannel channel) {
        return channel.preset && channel.edited && presetOf(channel.id) != null;
    }

    /** One channel back to what the preset list says: every change made to it on this TV goes. */
    public boolean resetToPreset(TvChannel channel, Runnable onDone) {
        JSONObject preset = presetOf(channel.id);

        if (preset == null) {
            return false;
        }

        channel.name = preset.optString("name", channel.id);
        channel.readSettings(preset);
        channel.presetBase = preset.toString();
        channel.skips.clear();
        mShown = null;
        onSearchChanged(channel, onDone); // rebuilds what is queued under the preset's rules
        channel.edited = false;
        save();
        return true;
    }

    /** A channel as the preset list has it now, or null when the list doesn't have it. */
    private JSONObject presetOf(String id) {
        try {
            JSONArray presets = mConfigText != null ? new JSONObject(mConfigText).optJSONArray("channels") : null;

            for (int i = 0; presets != null && i < presets.length(); i++) {
                JSONObject preset = presets.optJSONObject(i);
                if (preset != null && id != null && id.equals(preset.optString("id", null))) {
                    return preset;
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Can't read the preset list: %s", e.getMessage());
        }

        return null;
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
        if (channel.isSeries()) { // playlists in order: nothing is searched, shuffled or filtered
            fillSeries(channel, onDone, urgent);
            return;
        }

        final long now = System.currentTimeMillis();
        // Sources take turns: each search term, each YouTube channel, then (when the channel also
        // looks in playlists) each search term again as a search for playlists. With a Data API
        // key, a channel that names a country also searches each term as that country, and a
        // channel that names a chart reads it. Those two are rationed, so they come and go.
        final int playlistTurns = channel.playlists ? channel.terms.size() : 0;
        final int ownTurns = channel.terms.size() + channel.creators.size() + playlistTurns;
        // A Data API search is a term asked as one of the channel's countries ("US,GB,AU": they
        // take turns). One that reached its last page rests: asking again would start over and
        // return what the channel already has, for the same 100 units.
        final List<String> apiSearches = new ArrayList<>(); // "term|country"
        if (!channel.region.isEmpty() && now - channel.lastApiSearchMs > apiSearchGapMs() && mApi.canSearch(mApiSearchesPerDay)) {
            for (String country : channel.region.split(",")) {
                for (String oneTerm : channel.terms) {
                    String key = oneTerm + "|" + country.trim();
                    Long restUntil = channel.apiRest.get(key);
                    if (!country.trim().isEmpty() && (restUntil == null || now > restUntil)) {
                        apiSearches.add(key);
                    }
                }
            }
        }
        final int apiTurns = apiSearches.size();
        final int chartTurns = !channel.chart.isEmpty() && now - channel.lastChartMs > mChartGapMs && mApi.ready() ? 1 : 0;

        if (ownTurns + apiTurns + chartTurns == 0) {
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
        channel.lastFillMs = now;
        // The rationed sources go first whenever they are allowed: they are the better ones, and a
        // channel that is rarely low on videos would otherwise almost never get to them.
        final boolean fromChart = chartTurns > 0;
        final boolean fromApi = !fromChart && apiTurns > 0;
        final int turn = fromChart || fromApi ? 0 : Math.abs(channel.termCursor++) % ownTurns;
        final boolean fromPlaylist = !fromChart && !fromApi && turn >= channel.terms.size() + channel.creators.size();
        final boolean fromCreator = !fromChart && !fromApi && !fromPlaylist && turn >= channel.terms.size();
        // The search that has gone least deep goes first, so terms and countries stay level.
        final String apiSearch = fromApi ? apiSearches.get(Math.abs(channel.fills) % apiSearches.size()) : "";
        final String apiToken = channel.apiTokens.get(apiSearch);
        final String term = fromChart ? channel.chart : fromApi ? apiSearch.substring(0, apiSearch.lastIndexOf('|'))
                : fromPlaylist ? channel.terms.get(turn - channel.terms.size() - channel.creators.size())
                : fromCreator ? channel.creators.get(turn - channel.terms.size()) : channel.terms.get(turn);
        final String knownCreatorId = fromCreator ? channel.creatorIds.get(term) : null;
        // Set now, not when the answer lands: a request that fails must not be repeated at once.
        if (fromApi) {
            channel.lastApiSearchMs = now;
        } else if (fromChart) {
            channel.lastChartMs = now;
        }
        final String apiRegion = fromApi ? apiSearch.substring(apiSearch.lastIndexOf('|') + 1) : "";
        /** What the search answered: {0} is its next page, or null at the last one. {1} is set when it answered at all. */
        final String[] apiAnswer = {null, null};
        final String apiLanguage = channel.language();
        final String apiDuration = TvChannel.LENGTH_SHORT.equals(channel.length) ? "short" : TvChannel.LENGTH_MEDIUM.equals(channel.length) ? "medium"
                : TvChannel.LENGTH_LONG.equals(channel.length) || channel.minMinutes >= 20 ? "long" : null;
        final String apiOrder = TvChannel.ORDER_NEW.equals(channel.order) ? "date" : TvChannel.ORDER_VIEWS.equals(channel.order) ? "viewCount" : null;
        // Every few searches one asks for this week's uploads, so a channel isn't only YouTube's
        // evergreen results. Not when the channel has its own date or order setting.
        final boolean recent = !fromCreator && !fromPlaylist && !fromApi && !fromChart && mRecentEvery > 0 && channel.fills % mRecentEvery == mRecentEvery - 1
                && TvChannel.ANY.equals(channel.uploaded) && TvChannel.ORDER_MIXED.equals(channel.order);
        final int playlistIndex = channel.fills;
        // A movie channel takes turns between YouTube's Movies filter and a plain search for long
        // videos. The Movies filter also returns titles to rent or buy, which can't be played, and
        // in some regions little else. The plain search keeps the channel from running dry then.
        final boolean asMovie = TvChannel.TYPE_MOVIE.equals(channel.type) && channel.fills % 2 == 0;
        channel.fills++;
        final int options = fromCreator || fromApi || fromChart ? 0 : fromPlaylist ? (SearchOptions.TYPE_PLAYLIST | OPT_BUILD) : searchOptions(channel, recent, asMovie);
        // What is sent when YouTube returns nothing for the full set of filters: the type alone.
        // The checks in accept() still apply the rest.
        final int plainOptions = (asMovie ? SearchOptions.TYPE_MOVIE : SearchOptions.TYPE_VIDEO) | OPT_BUILD;
        // A plain search returns mostly English. A channel in another language adds the language's
        // name to its searches, or the language check would throw nearly everything away.
        final String languageWord = fromCreator ? "" : TvLanguage.searchWord(channel.language());
        final String query = languageWord.isEmpty() || term.toLowerCase(Locale.ROOT).contains(languageWord)
                || TvLanguage.detect(term) == null || !"en".equals(TvLanguage.detect(term)) ? term : term + " " + languageWord;
        final String pageKey = channel.id + "|" + (fromCreator ? "@" : fromPlaylist ? "#" : fromApi ? "~" : fromChart ? "^" : "") + query + "|" + options;
        final String searchKey = channel.searchKey();

        (urgent ? mWorker : mBackground).execute(() -> {
            final List<MediaItem> found = new ArrayList<>();
            /** From the Data API, which answers with facts and no search result to build a video from. */
            final List<TvDataApi.Facts> foundFacts = new ArrayList<>();
            final String[] creatorId = {knownCreatorId};

            try {
                ContentService service = YouTubeServiceManager.instance().getContentService();
                MediaGroup previous = fromPlaylist || fromApi || fromChart ? null : mPages.get(pageKey);
                List<MediaGroup> groups;

                if (fromChart) {
                    // The whole chart, 200 videos in 4 pages at 1 unit each: after the channel's
                    // own rules only a few of each page are left.
                    String next = null;
                    for (int i = 0; i < 4 && (i == 0 || next != null); i++) {
                        TvDataApi.Page page = mApi.popular(term, next);
                        foundFacts.addAll(page.facts);
                        next = page.next;
                    }
                    groups = null;
                } else if (fromApi) {
                    TvDataApi.Page page = mApi.search(term, apiRegion, apiLanguage, apiDuration, apiOrder, apiToken);
                    apiAnswer[0] = page.next;
                    apiAnswer[1] = "yes";
                    // In the order the search returned them.
                    Map<String, TvDataApi.Facts> facts = mApi.videos(page.ids);
                    for (String id : page.ids) {
                        if (facts.get(id) != null) {
                            foundFacts.add(facts.get(id));
                        }
                    }
                    groups = null;
                } else if (fromPlaylist) {
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
                } else if (fromApi || fromChart) {
                    for (TvDataApi.Facts facts : foundFacts) {
                        mFacts.put(facts.id, facts);
                    }
                } else if (lastPage != null) {
                    mPages.put(pageKey, lastPage);
                } else {
                    mPages.remove(pageKey);
                }
            } catch (Throwable e) {
                Log.e(TAG, "Search failed for %s: %s", term, e.getMessage());
            }

            learnFacts(found);

            mMain.post(() -> {
                channel.loading = false;

                if (fromCreator && creatorId[0] != null) {
                    channel.creatorIds.put(term, creatorId[0]);
                }

                // The channel was edited while the search ran. These results no longer apply.
                if (mChannels.contains(channel) && searchKey.equals(channel.searchKey())) {
                    if (fromApi && apiAnswer[1] != null) {
                        if (apiAnswer[0] != null) {
                            channel.apiTokens.put(apiSearch, apiAnswer[0]);
                        } else { // the last page: three weeks from now it starts again, for what was uploaded since
                            channel.apiTokens.remove(apiSearch);
                            channel.apiRest.put(apiSearch, System.currentTimeMillis() + 21 * 24 * 60 * 60_000L);
                        }
                    }

                    int added = fromApi || fromChart ? appendFacts(channel, foundFacts, fromChart) : append(channel, found, fromCreator ? term : null);
                    Log.d(TAG, "%s: +%s videos from \"%s\"%s", channel.name, added, term,
                            recent ? " (this week)" : fromApi ? " (Data API, as " + apiRegion + ", of " + foundFacts.size() + ")"
                                    : fromChart ? " (most popular chart, of " + foundFacts.size() + ")" : "");

                    if (added > 0) {
                        channel.emptyFills = 0;
                        save();
                    } else if (!recent && !fromApi && !fromChart) { // a quiet week or a spent allowance says nothing about the channel running dry
                        channel.emptyFills++;
                        // Every term came back with nothing new: let watched videos return.
                        if (channel.emptyFills >= channel.sources() * 2) {
                            channel.exhausted = true;
                        }
                    }

                    // One page is often not enough after filtering. Bounded so a dead search can't loop.
                    if (channel.ahead() < minAhead(channel) && channel.emptyFills < channel.sources() * 3) {
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
        List<String> playlists = mPlaylists.get(key);

        if (playlists == null) {
            playlists = new ArrayList<>();

            // The app's own search returns no playlists, so this one is asked directly.
            try {
                for (TvChannel.Series found : TvPlaylistSearch.find(query)) {
                    if (playlists.size() < PLAYLISTS_USED) {
                        playlists.add(found.playlistId);
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "Playlist search failed for %s: %s", query, e.getMessage());
            }

            mPlaylists.put(key, playlists);
        }

        List<MediaGroup> result = new ArrayList<>();

        if (!playlists.isEmpty()) {
            // A playlist opens as the channel "VL" + its id.
            List<MediaGroup> groups = service.getChannelObserve("VL" + playlists.get(Math.abs(index) % playlists.size())).blockingFirst();
            if (groups != null) {
                result.addAll(groups);
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
            return (long) (number * ("K".equals(unit) ? 1_000 : "M".equals(unit) ? 1_000_000 : "B".equals(unit) ? 1_000_000_000L
                    : "LAKH".equals(unit) ? 100_000 : "CRORE".equals(unit) ? 10_000_000 : 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // India's YouTube counts in lakh (100 thousand) and crore (10 million).
    private static final java.util.regex.Pattern VIEWS = java.util.regex.Pattern.compile("([\\d.,]+)\\s*([KMBkmb]|lakh|crore)?\\s+views", java.util.regex.Pattern.CASE_INSENSITIVE);
    /** A year in a title. Not part of a longer number, so "1080p" and "2160p" aren't years. */
    private static final java.util.regex.Pattern YEAR = java.util.regex.Pattern.compile("(?<![\\d])(19[2-9]\\d|20[0-3]\\d)(?![\\dpP])");

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

            if (!channel.exhausted && isWatched(channel, item.getVideoId())) {
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
            // When it was uploaded: exactly, from the Data API, or roughly, from "3 days ago" in the result.
            TvDataApi.Facts known = mFacts.get(video.videoId);
            long publishedMs = known != null && known.publishedMs > 0 ? known.publishedMs : publishedFrom(item.getSecondTitle());

            if (tooOld(channel, publishedMs)) {
                continue;
            }

            if (publishedMs > 0 && isFresh(channel)) {
                channel.published.put(video.videoId, publishedMs);
            }

            keepFacts(channel, video.videoId, video.getDurationMs());
            fresh.add(video);
        }

        return addFresh(channel, fresh, seen);
    }

    /**
     * The same for videos the Data API found, which come as facts instead of search results.
     * fromChart: the chart is every language of a country at once, so only videos the uploader
     * marked with the channel's language are taken.
     */
    private static final int CHART_PER_CATEGORY = 5;

    private int appendFacts(TvChannel channel, List<TvDataApi.Facts> found, boolean fromChart) {
        List<Video> fresh = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<Integer, Integer> perCategory = new java.util.HashMap<>();

        for (Video video : channel.lineup) {
            seen.add(video.videoId);
        }

        for (TvDataApi.Facts facts : found) {
            if (facts.id == null || facts.title == null || facts.upcoming
                    || !fits(channel, facts.title, facts.author, facts.live ? 0 : facts.durationMs, facts.live, false, facts.views)
                    || !factsFit(channel, facts, fromChart) || lacksNeeded(channel, facts.title, facts) || !seen.add(facts.id)) {
                continue;
            }

            if (!channel.exhausted && isWatched(channel, facts.id)) {
                continue;
            }

            Video video = new Video();
            video.videoId = facts.id;
            video.title = facts.title;
            video.author = facts.author;
            video.channelId = facts.channelId;
            video.cardImageUrl = facts.imageUrl;
            video.bgImageUrl = facts.imageUrl;
            video.isLive = facts.live;
            video.secondTitle = facts.author;
            video.tvChannelId = channel.id;
            // The chart is mostly one kind of video at a time. A few of each kind keeps the channel mixed.
            if (fromChart) {
                Integer taken = perCategory.get(facts.category);
                if (taken != null && taken >= CHART_PER_CATEGORY) {
                    seen.remove(facts.id);
                    continue;
                }
                perCategory.put(facts.category, taken != null ? taken + 1 : 1);
            }

            if (facts.publishedMs > 0 && isFresh(channel)) {
                channel.published.put(facts.id, facts.publishedMs);
            }

            keepFacts(channel, facts.id, 0);
            fresh.add(video);
        }

        return addFresh(channel, fresh, seen);
    }

    /** What the Data API said about a video that made it into a lineup: its length, and its description for the guide. */
    private void keepFacts(TvChannel channel, String videoId, long knownDurationMs) {
        TvDataApi.Facts facts = mFacts.get(videoId);

        if (facts == null) {
            return;
        }

        channel.checked.add(videoId);

        if (knownDurationMs <= 0 && facts.durationMs > 0 && !facts.live) {
            channel.durations.put(videoId, facts.durationMs);
        }

        if (!mDescriptions.containsKey(videoId)) {
            mDescriptions.put(videoId, facts.description.length() > MAX_DESCRIPTION_CHARS ? facts.description.substring(0, MAX_DESCRIPTION_CHARS) : facts.description);
        }
    }

    /** Adds the videos a source brought to the end of the lineup. seen: the ids in the lineup and in fresh. */
    private int addFresh(TvChannel channel, List<Video> fresh, Set<String> seen) {
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
            if (creatorRunsTooLong(channel, video) || sameAsQueued(channel, video)) {
                continue;
            }

            channel.lineup.add(video);
            if (video.isLive) {
                channel.durations.put(video.videoId, TvChannel.STREAM_SLOT_MS); // a stream has no length: it gets a slot
            } else if (video.getDurationMs() > 0 && !channel.durations.containsKey(video.videoId)) {
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

        // With a Data API key the exact length and view count are known, and more besides.
        TvDataApi.Facts facts = mFacts.get(item.getVideoId());
        long durationMs = item.isLive() ? 0 : item.getDurationMs();
        if (durationMs <= 0 && facts != null && !item.isLive()) {
            durationMs = facts.durationMs;
        }
        long views = facts != null && facts.views >= 0 ? facts.views : parseViews(item.getSecondTitle());

        return fits(channel, item.getTitle(), item.getAuthor(), durationMs, item.isLive(), item.isShorts(), views)
                && (facts == null || factsFit(channel, facts, false)) && !lacksNeeded(channel, item.getTitle(), facts);
    }

    /** The channel's own rules, on what both a search result and the Data API say about a video. views: -1 = unknown. */
    private boolean fits(TvChannel channel, String itemTitle, String itemAuthor, long durationMs, boolean live, boolean shorts, long views) {
        // Streams only on a channel made for them, and such a channel plays nothing else.
        if (live != channel.hasFeature(TvChannel.FEATURE_LIVE)) {
            return false;
        }

        if (channel.minMinutes > 0 && !live && durationMs > 0 && durationMs < channel.minMinutes * 60_000L) {
            return false;
        }

        if (channel.noShorts && (shorts || (durationMs > 0 && durationMs <= 61_000))) {
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

        if (minViews > 0 && views >= 0 && views < minViews) {
            return false;
        }

        String channelLanguage = channel.language();

        if (!channelLanguage.isEmpty()) {
            String language = TvLanguage.detect(itemTitle);
            // A Hindi channel keeps titles in plain Latin letters: a great many Hindi videos are titled that way.
            boolean latinOnHindi = "en".equals(language) && !"en".equals(channelLanguage);
            if (language != null && !channelLanguage.equals(language) && !latinOnHindi) {
                return false;
            }
        }

        String title = itemTitle.toLowerCase(Locale.ROOT);

        if (containsAny(title, channel.skipWords) || containsAny(title, mGlobalSkipWords)) {
            return false;
        }

        // A channel for an era: a title that names a year, and only years outside the era, is
        // from another time. YouTube has no filter for when a film or a song was made, so the
        // year uploaders put in the title is all there is. A title without a year passes.
        if (outOfEra(channel, itemTitle)) {
            return false;
        }

        String author = creatorOf(itemAuthor);

        if (author != null) {
            for (String blocked : channel.blocked) {
                if (author.equalsIgnoreCase(blocked)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * What only the Data API knows about a video, against the channel's language.
     * strict: take only videos marked with the channel's language. Otherwise a video without a
     * mark gets the benefit of the doubt.
     */
    private boolean factsFit(TvChannel channel, TvDataApi.Facts facts, boolean strict) {
        // Age-restricted videos need a signed-in adult account and stop a channel dead without one.
        // "YouTube Movies" is the store: titles to rent or buy.
        if (facts.ageRestricted || facts.upcoming || (facts.author != null && facts.author.startsWith("YouTube Movies"))) {
            return false;
        }

        if (facts.category > 0 && channel.skipCategories.contains(String.valueOf(facts.category))) {
            return false;
        }

        // "Viral" as a number: views for each day since the upload. A title that says viral proves nothing.
        long speed = facts.viewsPerDay();
        if (channel.minSpeed > 0 && speed >= 0 && speed < channel.minSpeed) {
            return false;
        }

        if (tooOld(channel, facts.publishedMs)) {
            return false;
        }

        String channelLanguage = channel.language();

        if (channelLanguage.isEmpty()) {
            return true;
        }

        String marked = facts.language;

        if (strict && !channelLanguage.equals(marked)) {
            return false;
        }

        if (!marked.isEmpty() && !marked.equals(channelLanguage)) {
            // Uploaders in India often leave the mark on English for a Hindi video, so on a channel
            // in another language "English" says nothing. Any other mark is deliberate and holds.
            boolean saysNothing = "en".equals(marked) && !"en".equals(channelLanguage);
            // Hindi and Urdu are one spoken language: ghazals and qawwali are marked either way.
            boolean sameSpeech = ("hi".equals(marked) && "ur".equals(channelLanguage)) || ("ur".equals(marked) && "hi".equals(channelLanguage));

            if (!saysNothing && !sameSpeech) {
                return false;
            }
        }

        // Tags and description name the language or the film industry when the title doesn't.
        List<String> hints = mForeignHints.get(channelLanguage);

        if (hints != null) {
            for (String word : hints) {
                if (facts.text.contains(word)) {
                    return false;
                }
            }
        }

        return true;
    }

    /** Asks the Data API about search results it hasn't been asked about yet. Worker threads. Does nothing without a key. */
    private void learnFacts(List<MediaItem> found) {
        if (found.isEmpty() || !mApi.ready()) {
            return;
        }

        List<String> ids = new ArrayList<>();

        for (MediaItem item : found) {
            String id = item != null ? item.getVideoId() : null;
            if (id != null && !mFacts.containsKey(id) && !ids.contains(id) && ids.size() < 150) {
                ids.add(id);
            }
        }

        if (ids.isEmpty()) {
            return;
        }

        try {
            mFacts.putAll(mApi.videos(ids));
        } catch (Throwable e) {
            Log.e(TAG, "Data API: no details for %s videos: %s", ids.size(), e.getMessage());
        }
    }

    private void turnPage(String pageKey, String next) {
        if (next != null) {
            mApiPages.put(pageKey, next);
        } else {
            mApiPages.remove(pageKey);
        }
    }

    // ---------------- Data API key (TV settings) ----------------

    public String getApiKey() {
        ensureLoaded();
        return mApi.key();
    }

    public void setApiKey(String key) {
        ensureLoaded();
        mApi.setKey(key);
        saveNow();
    }

    /** One line for TV settings: whether a key is set and how much of the day's allowance is used. */
    public String getApiStatus() {
        ensureLoaded();

        if (mApi.key().isEmpty()) {
            return "not set";
        }

        String problem = mApi.problem();

        return problem != null ? problem : mApi.used() + " of " + TvDataApi.DAILY_UNITS + " units used today, " + mApi.searches() + " searches";
    }

    /** A table of days by program type, from the preset list. Types are matched whatever their case. */
    private static void readDays(JSONObject table, Map<String, Integer> out) {
        if (table == null) {
            return;
        }

        out.clear();

        for (java.util.Iterator<String> it = table.keys(); it.hasNext();) {
            String kind = it.next();
            out.put(kind.toLowerCase(Locale.ROOT), table.optInt(kind, 0));
        }
    }

    /**
     * How long a channel waits between its Data API searches. The day's searches are shared out
     * among the channels that use them: few channels search often, many take longer turns. Never
     * less than the gap the preset list names.
     */
    private long apiSearchGapMs() {
        int channels = 0;

        for (TvChannel channel : mChannels) {
            channels += channel.region.isEmpty() ? 0 : 1;
        }

        long shared = mApiSearchesPerDay > 0 ? 24 * 60 * 60_000L * Math.max(1, channels) / mApiSearchesPerDay : Long.MAX_VALUE;
        return Math.max(mApiSearchGapMs, shared);
    }

    public interface OnApiTest {
        void onResult(boolean works, String message);
    }

    /** Sends one request (1 unit) to see whether Google accepts the key. */
    public void testApiKey(final OnApiTest onResult) {
        mWorker.execute(() -> {
            boolean works = false;
            String message;

            try {
                works = !mApi.videos(Collections.singletonList("dQw4w9WgXcQ")).isEmpty();
                message = works ? "The key works" : "Google answered, but with nothing. Try again later.";
            } catch (Throwable e) {
                String problem = mApi.problem();
                message = problem != null ? problem : "Couldn't reach Google: " + e.getMessage();
            }

            final boolean result = works;
            final String text = message;
            mMain.post(() -> {
                onResult.onResult(result, text);
                if (result) {
                    checkLineups(null); // what is queued gets the new checks straight away
                }
            });
        });
    }

    private static boolean containsAny(String lowerCaseText, List<String> words) {
        for (String word : words) {
            if (lowerCaseText.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }

        return false;
    }

    private static boolean outOfEra(TvChannel channel, String title) {
        if (channel.yearTo <= 0 || title == null) {
            return false;
        }

        java.util.regex.Matcher years = YEAR.matcher(title);
        boolean named = false;

        while (years.find()) {
            int year = Integer.parseInt(years.group(1));
            if (year >= channel.yearFrom && year <= channel.yearTo) {
                return false;
            }
            named = true;
        }

        return named || channel.yearsStrict;
    }

    /** True when the channel asks for certain words and neither the title nor what the Data API said has one. */
    private boolean lacksNeeded(TvChannel channel, String title, TvDataApi.Facts facts) {
        if (channel.needWords.isEmpty() || title == null) {
            return false;
        }

        String text = title.toLowerCase(Locale.ROOT) + (facts != null ? "\n" + facts.text : "");

        // Every entry must be there. An entry can offer choices with "|": "hindi|dubbed".
        for (String need : channel.needWords) {
            if (!containsAny(text, java.util.Arrays.asList(need.split("\\|")))) {
                return true;
            }
        }

        return false;
    }

    /**
     * The same film or concert uploaded by somebody else: a long video whose length matches one
     * that is on or queued to within a second and a half. Two different films of over twenty
     * minutes almost never run the same to the second.
     */
    private boolean sameAsQueued(TvChannel channel, Video video) {
        TvDataApi.Facts facts = mFacts.get(video.videoId);
        long durationMs = video.getDurationMs() > 0 ? video.getDurationMs() : facts != null ? facts.durationMs : 0;

        if (durationMs < 20 * 60_000 || video.isLive) {
            return false;
        }

        for (int i = Math.max(0, channel.pos); i < channel.lineup.size(); i++) {
            Long queued = channel.durations.get(channel.lineup.get(i).videoId);
            if (queued != null && Math.abs(queued - durationMs) <= 1_500) {
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

    // ---------------- accounts ----------------
    //
    // Everything a person does is theirs: the channels they made or changed, favourites, what
    // they watched, where each channel and each series is, the guide's filters. All of it lives
    // in one saved file for each account, and one for nobody signed in. The preset list is the
    // same for everybody, and so is what belongs to the TV: the Data API key and its day's count.

    private static final String DEVICE_FILE = "tv_mode_device.json";
    /** Whose state is loaded: a key for the signed-in account, "" for nobody. */
    private String mAccount = "";
    private boolean mDeviceLoaded;
    /** The state saved before accounts were told apart went to an account already. */
    private boolean mLegacyTaken;

    private String accountKey() {
        try {
            com.liskovsoft.mediaserviceinterfaces.oauth.Account account =
                    com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.instance().getSelectedAccount();
            if (account != null && !account.isEmpty()) {
                // Not the address itself: the key is part of a file name.
                return "a" + Integer.toHexString((account.getEmail() + "|" + account.getName()).hashCode());
            }
        } catch (Throwable e) {
            Log.e(TAG, "Can't tell who is signed in: %s", e.getMessage());
            return mAccount;
        }

        return "";
    }

    private File stateFile(String account) {
        return new File(mContext.getFilesDir(), account.isEmpty() ? STATE_FILE : "tv_mode_state_" + account + ".json");
    }

    /** Another account was signed in since the state was loaded: this one is saved and put away, the other's is loaded. */
    private void switchAccount() {
        if (!mLoaded) {
            return;
        }

        String now = accountKey();

        if (now.equals(mAccount)) {
            return;
        }

        stopWatching();
        mMain.removeCallbacks(mSaveNow);
        saveNow(); // to the file of the account that is leaving

        for (TvChannel channel : mChannels) {
            channel.group = null;
        }
        mChannels.clear();
        mWatched.clear();
        mWatchedAt.clear();
        mDeletedPresets.clear();
        mPages.clear();
        mPlaylists.clear();
        mShown = null;
        mLastChannelId = null;
        mGuideChannelId = null;
        mFilterKind = "";
        mFilterLanguage = "";
        mFavOnly = false;
        mConfigText = null;
        mLineupsChecked = false;
        mLoaded = false;
        Log.d(TAG, "Account changed: TV mode now holds %s", now.isEmpty() ? "nobody's state" : "account " + now);
        ensureLoaded();
    }

    private void loadDevice() {
        if (mDeviceLoaded) {
            return;
        }

        mDeviceLoaded = true;
        String text = readFile(new File(mContext.getFilesDir(), DEVICE_FILE));

        try {
            if (text != null) {
                JSONObject device = new JSONObject(text);
                mApi.restore(device.optString("apiKey", ""), device.optString("apiDay", ""), device.optInt("apiUsed", 0), device.optInt("apiSearches", 0));
                mLegacyTaken = device.optBoolean("legacyTaken", false);
                mHasDevice = true;
            }
        } catch (Throwable e) {
            Log.e(TAG, "Can't read the TV's own settings: %s", e.getMessage());
        }
    }

    /** False until the TV's own file exists: before that the key was kept in the state, and is taken from there once. */
    private boolean mHasDevice;

    private void ensureLoaded() {
        if (mLoaded) {
            return;
        }

        mLoaded = true;
        mAccount = accountKey();
        loadDevice();
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
        checkLineups(onChanged);
    }

    /**
     * With a Data API key: asks about every video that is on or coming up and hasn't been asked
     * about, then takes out the ones that don't fit. Videos that were queued before the key was
     * entered, or that a restart brought back from the saved lineups, get checked this way.
     * About 50 units for 200 channels.
     */
    private void checkLineups(final Runnable onChanged) {
        if (!mApi.ready()) {
            return;
        }

        final List<String> ids = new ArrayList<>();

        for (TvChannel channel : mChannels) {
            for (int i = Math.max(0, channel.pos); i < channel.lineup.size(); i++) {
                String id = channel.lineup.get(i).videoId;
                if (id != null && !mFacts.containsKey(id) && !channel.checked.contains(id)) {
                    ids.add(id);
                }
            }
        }

        if (ids.isEmpty()) {
            return;
        }

        mBackground.execute(() -> {
            try {
                Map<String, TvDataApi.Facts> answer = mApi.videos(ids);
                mFacts.putAll(answer);
                // Asked about and not in the answer: deleted or made private since it was queued.
                for (String id : ids) {
                    if (!answer.containsKey(id)) {
                        mGone.add(id);
                    }
                }
            } catch (Throwable e) {
                Log.e(TAG, "Data API: no details for %s videos: %s", ids.size(), e.getMessage());
                return;
            }

            mMain.post(() -> {
                int before = 0;
                int after = 0;

                for (TvChannel channel : mChannels) {
                    before += channel.lineup.size();
                    purge(channel);
                    after += channel.lineup.size();

                    for (int i = Math.max(0, channel.pos); i < channel.lineup.size(); i++) {
                        keepFacts(channel, channel.lineup.get(i).videoId, 0);
                    }
                }

                Log.d(TAG, "Data API: checked %s queued videos, %s left the lineups", ids.size(), before - after);

                if (before != after) {
                    fillLow(null);
                    save();
                    if (onChanged != null) {
                        onChanged.run();
                    }
                }
            });
        });
    }

    private void fillLow(Runnable onDone) {
        for (TvChannel channel : mChannels) {
            if (channel.ahead() < minAhead(channel) && !channel.loading) {
                channel.emptyFills = 0;
                fill(channel, onDone, false);
            }
        }
    }

    // ---------------- program info ----------------

    public interface OnInfo {
        void onInfo(String videoId, String description);
    }

    /** With a Data API key every video in a lineup comes with its description, so there are many more to keep. */
    private static final int MAX_DESCRIPTIONS = 1_500;
    private static final int MAX_DESCRIPTION_CHARS = 5_000;
    /** Descriptions already fetched, by video id. Main thread only. */
    private final Map<String, String> mDescriptions = new java.util.LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_DESCRIPTIONS;
        }
    };
    private final Set<String> mDescriptionsAsked = new HashSet<>();
    private final ExecutorService mInfoWorker = Executors.newSingleThreadExecutor();
    /** The same two for a program the viewer is looking at, which doesn't queue behind the background checks. */
    private final Set<String> mDescriptionsAskedFirst = new HashSet<>();
    private final ExecutorService mInfoFirstWorker = Executors.newSingleThreadExecutor();
    /** Who is waiting for a description that is on its way, by video id. Main thread only. */
    private final Map<String, List<OnInfo>> mDescriptionWaiters = new java.util.HashMap<>();
    /** When a fetch last failed, by video id, so a video that can't be read isn't asked for over and over. */
    private final Map<String, Long> mDescriptionsFailed = new java.util.HashMap<>();
    private static final long INFO_RETRY_MS = 5_000;
    private static final long INFO_BACKGROUND_RETRY_MS = 10 * 60_000;

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

        // Asked for by the viewer: goes ahead of the background checks.
        final boolean first = onInfo != null;
        Long failedAt = mDescriptionsFailed.get(id);

        if (failedAt != null && System.currentTimeMillis() - failedAt < (first ? INFO_RETRY_MS : INFO_BACKGROUND_RETRY_MS)) {
            if (first) {
                onInfo.onInfo(id, null);
            }
            return;
        }

        if (first) {
            // Kept even when a fetch is on its way, or the answer would reach nobody.
            List<OnInfo> waiters = mDescriptionWaiters.get(id);
            if (waiters == null) {
                waiters = new ArrayList<>();
                mDescriptionWaiters.put(id, waiters);
            }
            waiters.add(onInfo);

            if (!mDescriptionsAskedFirst.add(id)) {
                return; // on its way
            }
        } else if (mDescriptionsAskedFirst.contains(id) || !mDescriptionsAsked.add(id)) {
            return; // on its way
        }

        (first ? mInfoFirstWorker : mInfoWorker).execute(() -> {
            String text = null; // null: the fetch failed, which is not the same as an empty description

            try {
                com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata metadata =
                        YouTubeServiceManager.instance().getMediaItemService().getMetadata(id);
                if (metadata != null) {
                    text = metadata.getDescription() != null ? metadata.getDescription() : "";
                }
            } catch (Throwable e) {
                Log.e(TAG, "No description for %s: %s", id, e.getMessage());
            }

            final String description = text;

            mMain.post(() -> {
                (first ? mDescriptionsAskedFirst : mDescriptionsAsked).remove(id);
                boolean arrived = mDescriptions.containsKey(id); // the other fetch of this video got here first

                if (description != null && !arrived) {
                    mDescriptionsFailed.remove(id);
                    mDescriptions.put(id, description);
                    checkDescription(video, description);
                } else if (description == null && !arrived) {
                    if (mDescriptionsAskedFirst.contains(id) || mDescriptionsAsked.contains(id)) {
                        return; // the other fetch of this video may still bring it
                    }

                    // Not kept as an empty description: the next look at the program asks again.
                    if (mDescriptionsFailed.size() > 500) {
                        mDescriptionsFailed.clear();
                    }
                    mDescriptionsFailed.put(id, System.currentTimeMillis());
                }

                List<OnInfo> waiters = mDescriptionWaiters.remove(id);

                if (waiters != null) {
                    String answer = mDescriptions.get(id);
                    for (OnInfo waiter : waiters) {
                        waiter.onInfo(id, answer);
                    }
                }
            });
        });
    }

    /** Takes a video out of its channel when its description shows another language than the channel's. */
    private void checkDescription(Video video, String description) {
        TvChannel channel = findChannel(video);

        if (channel == null || description == null || description.isEmpty() || channel.language().isEmpty() || channel.isSeries()) {
            return;
        }

        // The start of the description is about the video. Further down come links and boilerplate.
        String start = description.length() > 400 ? description.substring(0, 400) : description;
        String channelLanguage = channel.language();
        // The uploader's own mark beats a guess from the text: match reports and the like read as
        // another language when they are full of names.
        TvDataApi.Facts facts = mFacts.get(video.videoId);
        String language = facts != null && channelLanguage.equals(facts.language) ? null : TvLanguage.detect(start);
        boolean latinOnHindi = "en".equals(language) && !"en".equals(channelLanguage);

        // A film from India is often titled and described in English. Its description still names
        // its language or its industry somewhere, in the text or in a hashtag.
        List<String> hints = mForeignHints.get(channelLanguage);
        String hint = null;
        if (hints != null) {
            String lower = description.toLowerCase(Locale.ROOT);
            for (String word : hints) {
                if (lower.contains(word)) {
                    hint = word;
                    break;
                }
            }
        }

        if (hint != null) {
            language = "\"" + hint + "\"";
        } else if (language == null || channelLanguage.equals(language) || latinOnHindi) {
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
        if (channel.language().isEmpty()) {
            return;
        }

        // What's on and the two after it: a wrong one is gone before the viewer gets to it.
        for (int i = channel.pos; i <= channel.pos + 2 && i >= 0 && i < channel.lineup.size(); i++) {
            loadInfo(channel.lineup.get(i), null);
        }
    }

    /**
     * Words that give a video away as being in another language, by the language of the channel
     * it is on. Looked for in the description. From the preset list's "foreignHints".
     */
    private final Map<String, List<String>> mForeignHints = new java.util.HashMap<>();

    /**
     * True when a video that is already in a lineup no longer fits its channel: the title has
     * a skip word, reads as another language, or the creator is blocked. The same checks as
     * {@link #accept}, for the ones a saved video still has the data for.
     */
    private boolean stale(TvChannel channel, Video video) {
        String title = video.getTitle();

        if (channel.isSeries()) { // its playlists were picked by hand: only an episode that no longer exists goes
            return mGone.contains(video.videoId);
        }

        if (title == null) {
            return false;
        }

        String lower = title.toLowerCase(Locale.ROOT);

        if (containsAny(lower, channel.skipWords) || containsAny(lower, mGlobalSkipWords)) {
            return true;
        }

        TvDataApi.Facts facts = mFacts.get(video.videoId);

        Long publishedMs = channel.published.get(video.videoId);

        if (mGone.contains(video.videoId) || (facts != null && !factsFit(channel, facts, false)) || outOfEra(channel, title)
                || lacksNeeded(channel, title, facts) || (publishedMs != null && tooOld(channel, publishedMs))) {
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
                mApiSearchesPerDay = Math.max(0, Math.min(99, tuning.optInt("apiSearchesPerDay", mApiSearchesPerDay)));
                readDays(tuning.optJSONObject("freshDays"), mFreshDays);
                readDays(tuning.optJSONObject("rewatchDays"), mRewatchDays);
                if (mRewatchDays.containsKey("default")) {
                    mRewatchDaysDefault = mRewatchDays.get("default");
                }
                mApiSearchGapMs = Math.max(1, tuning.optInt("apiSearchGapHours", (int) (mApiSearchGapMs / 3_600_000L))) * 3_600_000L;
                mChartGapMs = Math.max(10, tuning.optInt("chartGapMinutes", (int) (mChartGapMs / 60_000L))) * 60_000L;
                mGlobalSkipWords.clear();
                JSONArray words = tuning.optJSONArray("skipWords");
                for (int i = 0; words != null && i < words.length(); i++) {
                    mGlobalSkipWords.add(words.optString(i, ""));
                }
                mGlobalSkipWords.removeAll(Collections.singletonList(""));
            }

            JSONObject hints = config.optJSONObject("foreignHints");
            mForeignHints.clear();
            if (hints != null) {
                for (java.util.Iterator<String> it = hints.keys(); it.hasNext();) {
                    String code = it.next();
                    JSONArray words = hints.optJSONArray(code);
                    List<String> list = new ArrayList<>();
                    for (int i = 0; words != null && i < words.length(); i++) {
                        String word = words.optString(i, "").toLowerCase(Locale.ROOT);
                        if (!word.isEmpty()) {
                            list.add(word);
                        }
                    }
                    mForeignHints.put(code, list);
                }
            }

            JSONArray origins = config.optJSONArray("origins");
            mOrigins.clear();
            for (int i = 0; origins != null && i < origins.length(); i++) {
                JSONObject origin = origins.optJSONObject(i);
                if (origin != null && !origin.optString("name", "").isEmpty()) {
                    mOrigins.add(new String[] {origin.optString("name"), origin.optString("countries", ""),
                            origin.optString("word", ""), origin.optString("need", ""), origin.optString("languages", "")});
                }
            }

            JSONArray kinds = config.optJSONArray("kinds");
            mKindOrder.clear();
            for (int i = 0; kinds != null && i < kinds.length(); i++) {
                String kind = kinds.optString(i, "");
                if (!kind.isEmpty()) {
                    mKindOrder.add(kind);
                }
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
                    // Edited here. What was changed on this TV stays, and everything else follows
                    // the preset list: see TvChannel.followPreset.
                    String beforeMerge = channel.searchKey();
                    channel.followPreset(preset);
                    if (!beforeMerge.equals(channel.searchKey())) {
                        purge(channel);
                        dropComingUp(channel);
                    }

                    // It still gets a genre and a
                    // language when it has none, or the guide's filters would never show it.
                    // The same for its program type, which channels saved before types existed don't have.
                    // Its genre then comes along: what "genre" meant changed with it.
                    if (channel.kind.isEmpty()) {
                        channel.kind = preset.optString("kind", "");
                        channel.genre = preset.optString("genre", "");
                    }
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
                channel.presetBase = preset.toString(); // what an edit made from here on is told apart from

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

            int withApi = 0;
            for (TvChannel channel : mChannels) {
                withApi += channel.region.isEmpty() && channel.chart.isEmpty() ? 0 : 1;
            }
            Log.d(TAG, "Channel list applied: %s channels, %s with a Data API source, key %s", mChannels.size(), withApi,
                    mApi.key().isEmpty() ? "not set" : mApi.ready() ? "in use" : "set, not usable now");
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Bad TV mode config: %s", e.getMessage());
            return false;
        }
    }

    /** The channel's search changed: keep what's on now and the next two, refill the rest. */
    private void dropComingUp(TvChannel channel) {
        while (channel.lineup.size() > channel.pos + 3) {
            unqueue(channel, channel.lineup.remove(channel.lineup.size() - 1));
        }

        channel.group = null;
        channel.exhausted = false;
        channel.emptyFills = 0;
        channel.termCursor = 0;
    }

    // ---------------- saving ----------------

    private void loadState() {
        String text = readFile(stateFile(mAccount));

        // The first account to open TV mode after accounts were told apart takes over what was
        // saved until then, so nothing that was set up or watched is lost. Later accounts start clean.
        if (text == null && !mAccount.isEmpty() && !mLegacyTaken) {
            text = readFile(stateFile(""));
            mLegacyTaken = true;
        }

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
                mFilterKind = settings.optString("filterKind", "");
                mFilterLanguage = settings.optString("filterLanguage", "");
                if (!mHasDevice && mApi.key().isEmpty()) { // saved by a build that kept the key with the state
                    mApi.restore(settings.optString("apiKey", ""), settings.optString("apiDay", ""), settings.optInt("apiUsed", 0), settings.optInt("apiSearches", 0));
                }
            }

            addAll(state.optJSONArray("watched"), mWatched);
            JSONObject watchedAt = state.optJSONObject("watchedAt");
            for (java.util.Iterator<String> it = watchedAt != null ? watchedAt.keys() : null; it != null && it.hasNext();) {
                String id = it.next();
                mWatchedAt.put(id, watchedAt.optLong(id, 0));
            }
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

        if (!mLoaded) { // nothing is loaded: writing now would save an empty state over a real one
            return;
        }

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
            settings.put("filterGenre", ""); // kept so an older build finds the key; the guide filters by type now
            settings.put("filterKind", mFilterKind);
            settings.put("filterLanguage", mFilterLanguage);

            // What belongs to the TV and not to an account. The key stays on this TV: it is never
            // part of the channel list, which is public.
            final JSONObject device = new JSONObject();
            device.put("apiKey", mApi.key());
            device.put("apiUsed", mApi.used());
            device.put("apiSearches", mApi.searches());
            device.put("apiDay", mApi.day());
            device.put("legacyTaken", mLegacyTaken);
            mHasDevice = true;

            // Copies of the lists, so the worker can turn them into text while the channels go on changing.
            final List<TvChannel> channels = new ArrayList<>();
            for (TvChannel channel : mChannels) {
                channels.add(channel.copyForSave());
            }
            final List<String> watched = new ArrayList<>(mWatched);
            final JSONObject watchedAt = new JSONObject(mWatchedAt);
            final List<String> deletedPresets = new ArrayList<>(mDeletedPresets);
            final File file = stateFile(mAccount); // named now: the account may be another by the time this is written

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
                    state.put("watchedAt", watchedAt);
                    state.put("deletedPresets", new JSONArray(deletedPresets));
                    writeFile(file, state.toString());
                    writeFile(new File(mContext.getFilesDir(), DEVICE_FILE), device.toString());
                    writeLineups(channels);
                } catch (Throwable e) {
                    Log.e(TAG, "Can't save the TV mode state: %s", e.getMessage());
                }
            });
        } catch (Throwable e) {
            Log.e(TAG, "Can't save the TV mode state: %s", e.getMessage());
        }
    }

    private long mLineupsWrittenMs;
    private volatile boolean mLineupsLate;

    /**
     * What every channel has on and coming up, as plain text in the app's own folder on shared
     * storage (Android/data/.../files/tv_lineups.txt). It is there to look at when a channel
     * plays the wrong thing: the saved state itself can't be read from outside the app.
     */
    private void writeLineups(List<TvChannel> channels) {
        long now = System.currentTimeMillis();

        if (now - mLineupsWrittenMs < 20_000) {
            // Not now: channels save in bursts while they fill. But the last change of a burst
            // must reach the file too, so one more save is asked for once the pause is over.
            if (!mLineupsLate) {
                mLineupsLate = true;
                mMain.postDelayed(this::save, 21_000);
            }
            return;
        }

        mLineupsLate = false;
        mLineupsWrittenMs = now;

        try {
            File folder = mContext.getExternalFilesDir(null);

            if (folder == null) {
                return;
            }

            StringBuilder text = new StringBuilder();

            for (TvChannel channel : channels) {
                text.append("# ").append(channel.name).append(" | ").append(channel.kind).append(" | ").append(channel.genre)
                        .append(" | ").append(channel.language()).append('\n');

                for (int i = Math.max(0, channel.pos); i < channel.lineup.size(); i++) {
                    Video video = channel.lineup.get(i);
                    TvDataApi.Facts facts = mFacts.get(video.videoId);
                    Long durationMs = channel.durations.get(video.videoId);
                    String title = video.getTitle() != null ? video.getTitle().replace('\n', ' ').replace('|', '/') : "";
                    text.append(title).append(" | ").append(video.author != null ? video.author : "")
                            .append(" | ").append(durationMs != null ? durationMs / 60_000 : -1)
                            .append(" | ").append(facts != null ? facts.language : "?")
                            .append(" | ").append(facts != null ? facts.views : -1)
                            .append(" | ").append(channel.episodes.containsKey(video.videoId) ? channel.episodes.get(video.videoId) : "")
                            .append(" | ").append(video.videoId).append('\n');
                }
            }

            writeFile(new File(folder, "tv_lineups.txt"), text.toString());
        } catch (Throwable e) {
            Log.e(TAG, "Can't write the lineups: %s", e.getMessage());
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

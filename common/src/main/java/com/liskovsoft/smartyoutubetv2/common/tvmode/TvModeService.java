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
    private static final String STATE_FILE = "tv_mode_state.json";
    private static final String CONFIG_CACHE_FILE = "tv_mode_config.json";
    private static final long CONFIG_CHECK_PERIOD_MS = 5 * 60 * 1_000;
    private static final int MAX_PAST = 20;
    private static final int MAX_WATCHED = 3_000;
    @SuppressLint("StaticFieldLeak")
    private static TvModeService sInstance;
    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
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
    private int mSleepMinutes;
    private final Runnable mSleep = () -> {
        mSleepMinutes = 0;
        TvModeController.onSleepTimer();
    };
    private int mMinAhead = 12;
    private int mMaxPerCreatorInRow = 2;
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
        boolean waiting = false;

        onGroup.onGroup(buildActionGroup());

        for (int i = 0; i < mChannels.size(); i++) {
            final TvChannel channel = mChannels.get(i);
            final int index = i;

            if (channel.onNow() != null) {
                onGroup.onGroup(buildGroup(channel, index + 1));
                if (channel.ahead() < mMinAhead) {
                    fill(channel, null);
                }
            } else {
                waiting = true;
                fill(channel, () -> {
                    if (token == mGuideToken && channel.onNow() != null) {
                        onGroup.onGroup(buildGroup(channel, mChannels.indexOf(channel) + 1));
                    }
                    if (token == mGuideToken && onReady != null) {
                        onReady.run();
                    }
                });
            }
        }

        if (!waiting && onReady != null) {
            onReady.run();
        }
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
        List<Video> videos = new ArrayList<>(channel.lineup.subList(channel.pos, channel.lineup.size()));
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
    public void onVideoStarted(Video video) {
        TvChannel channel = findChannel(video);

        if (channel == null) {
            return;
        }

        int index = channel.indexOf(video.videoId);

        if (index == -1) {
            return;
        }

        remember(video.videoId);

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
            channel.liveStartMs += durationOf(channel, channel.onNow());
            remember(channel.onNow().videoId);
            channel.pos++;
        }

        if (channel.ahead() == 0 && now - channel.liveStartMs >= durationOf(channel, channel.onNow())) {
            channel.liveStartMs = now; // ran out of lineup: hold on the last video until more arrives
        }

        if (channel.ahead() < mMinAhead) {
            fill(channel, null);
        }
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

    public int getSleepMinutes() {
        return mSleepMinutes;
    }

    /** Stops TV mode playing after this many minutes. 0 = off. Not kept across restarts. */
    public void setSleepTimer(int minutes) {
        mSleepMinutes = minutes;
        mMain.removeCallbacks(mSleep);

        if (minutes > 0) {
            mMain.postDelayed(mSleep, minutes * 60_000L);
        }
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
    private void fill(final TvChannel channel, final Runnable onDone) {
        if (channel.terms.isEmpty()) {
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
        final String term = channel.terms.get(Math.abs(channel.termCursor++) % channel.terms.size());
        final int options = searchOptions(channel);
        final String pageKey = channel.id + "|" + term + "|" + options;
        final String searchKey = channel.searchKey();

        mWorker.execute(() -> {
            final List<MediaItem> found = new ArrayList<>();

            try {
                ContentService service = YouTubeServiceManager.instance().getContentService();
                MediaGroup previous = mPages.get(pageKey);
                List<MediaGroup> groups;

                if (previous != null) {
                    MediaGroup next = service.continueGroup(previous);
                    groups = next != null ? Collections.singletonList(next) : new ArrayList<MediaGroup>();
                } else {
                    groups = service.getSearch(term, options);
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

                // The channel was edited while the search ran. These results no longer apply.
                if (mChannels.contains(channel) && searchKey.equals(channel.searchKey())) {
                    int added = append(channel, found);
                    Log.d(TAG, "%s: +%s videos from \"%s\"", channel.name, added, term);

                    if (added == 0) {
                        channel.emptyFills++;
                        // Every term came back with nothing new: let watched videos return.
                        if (channel.emptyFills >= channel.terms.size() * 2) {
                            channel.exhausted = true;
                        }
                    } else {
                        channel.emptyFills = 0;
                        save();
                    }

                    // One page is often not enough after filtering. Bounded so a dead search can't loop.
                    if (channel.ahead() < mMinAhead && channel.emptyFills < channel.terms.size() * 3) {
                        fill(channel, null);
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

    private int append(TvChannel channel, List<MediaItem> found) {
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
            fresh.add(video);
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

    private void save() {
        try {
            JSONObject state = new JSONObject();
            JSONArray channels = new JSONArray();

            for (TvChannel channel : mChannels) {
                channels.put(channel.toJson());
            }

            JSONObject settings = new JSONObject();
            settings.put("defaultMode", mDefaultMode);
            settings.put("dpadSurf", mDpadSurf);
            settings.put("favOnly", mFavOnly);
            settings.put("ytHistory", mYtHistory);
            state.put("settings", settings);
            state.put("channels", channels);
            state.put("watched", new JSONArray(mWatched));
            state.put("deletedPresets", new JSONArray(mDeletedPresets));

            final String text = state.toString();
            mWorker.execute(() -> writeFile(new File(mContext.getFilesDir(), STATE_FILE), text));
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

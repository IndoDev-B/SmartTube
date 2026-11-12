package com.liskovsoft.smartyoutubetv2.tv.ui.browse.video;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvChannel;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvModeService;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The lower part of the TV guide: a programme grid on one shared time line.<br/>
 * Every channel is a line, a program is as wide as it is long, and a red line down the whole
 * grid marks now. A Live TV channel moves under that line with the clock. A Free mode channel
 * stands still with the line at the spot where it was left.<br/>
 * Drawn on a canvas and steered by the D-pad: Up/Down change channel, Left/Right move along a
 * channel's programs, OK watches, holding OK opens the channel's options.<br/>
 * Sizes are dp at 1080p, where 1dp is 2 TV pixels.
 */
public class TvGuideGridView extends View {
    public interface Listener {
        /** The focus moved. The video is a program, or one of the buttons in the top line. */
        void onFocused(Video video);

        void onClicked(Video video);

        void onLongClicked(Video video);

        /** The grid took the remote (true) or gave it to the side menu (false). */
        void onGridFocus(boolean focused);
    }

    private static final int RED = 0xFFE62117;
    private static final int LINE = 0xDB1C1D1F;
    private static final int LINE_NOW = 0xFF2A2C30;
    private static final int FOCUSED = 0xFFF1F1F1;
    private static final long MINUTE = 60_000;
    /** How much of the past stays visible left of the now line. */
    private static final long PAST_SHOWN_MS = 12 * MINUTE;
    private static final int PAST_PROGRAMS = 8;
    private static final long TICK_MS = 5_000;
    private static final int MAX_THUMBS = 160;
    /** Small pictures for the programs, by video id. Loaded when a program is first drawn. */
    private final java.util.Map<String, android.graphics.Bitmap> mThumbs = new java.util.HashMap<>();
    private final java.util.Set<String> mThumbsAsked = new java.util.HashSet<>();
    private final TvModeService mService;
    /** How many of the top line's buttons sit on the left. The rest are right-aligned. */
    private static final int LEFT_BUTTONS = 4;
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path mPath = new android.graphics.Path();
    private final TextPaint mTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mMeta = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mNumber = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mName = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mRuler = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mPillText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final List<Video> mButtons = new ArrayList<>();
    /**
     * The lines between the buttons and the time grid, top to bottom. One with nothing on it isn't
     * shown. The results of a search sit lowest, closest to the channels: the first Up from
     * channel 1 lands there, which is the way back after picking a result.
     */
    private static final String[] SHELVES = {TvModeService.SHELF_RESUME, TvModeService.SHELF_LIST, TvModeService.SHELF_RESULTS};
    /** The line above the time grid the focus is on. Null = the focus is on the buttons, or on a channel. */
    private String mShelf;
    private int mShelfItem;
    /** The first card on screen of each of those lines. */
    private final java.util.Map<String, Integer> mShelfFirst = new java.util.HashMap<>();
    /** The card the focus was on in each of those lines, to come back to it. */
    private final java.util.Map<String, Integer> mShelfItems = new java.util.HashMap<>();
    /** Free mode lines and the lines above the grid: every program the same width, since no clock runs on them. */
    private static final float CARD_DP = 232;
    /**
     * Where each Free line is scrolled, by channel id, as the first card on screen counted from
     * what's on now. Free lines scroll on their own: moving along one leaves the others alone.
     */
    private final java.util.Map<String, Integer> mFreeOffset = new java.util.HashMap<>();
    /** The program the focus was on in each Free line, by channel id, to come back to it. */
    private final java.util.Map<String, String> mFreeFocus = new java.util.HashMap<>();
    private Listener mListener;
    /** -1 = the buttons in the top line, otherwise the channel's place in the list. */
    private int mRow;
    private int mButton;
    /** The focused program's place in its channel's lineup. */
    private int mProgram = -1;
    /** The time the focus is at. Up/Down land on whatever the next channel has on at this time. */
    private long mFocusTimeMs;
    /** How far the grid is scrolled to the right of its resting place, where now is near the left. */
    private long mScrollMs;
    private int mTopRow;
    private boolean mLongPressed;
    /** The focus is on what a channel has on now. When the channel moves to its next video, the focus goes with it. */
    private boolean mOnNowFocused = true;
    /** The channel the focus is on, by id: the list can change under it when a filter is picked. */
    private String mFocusedId;
    /** Back was pressed on this channel line. -1 = none. */
    private int mReturnRow = -1;
    private boolean mBackTaken;
    private int mShownCount = -1;
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            // Only while the guide is what's on screen. Under the full screen player this tick
            // wrote the guide's own row back as the channel to return to, every 5 seconds, over
            // the channel the viewer had switched to with CH+ / CH-.
            if (hasWindowFocus()) {
                followNow();
                notifyFocus(); // the part above the grid shows progress too
            }
            invalidate(); // the programs slide under the now line
            postDelayed(this, TICK_MS);
        }
    };

    public TvGuideGridView(Context context) {
        super(context);

        mService = TvModeService.instance(context);
        setFocusable(true);
        setFocusableInTouchMode(true);

        mTitle.setTextSize(dp(13.5f));
        mMeta.setTextSize(dp(10.5f));
        mNumber.setTextSize(dp(17));
        mNumber.setTypeface(Typeface.DEFAULT_BOLD);
        mName.setTextSize(dp(10));
        mRuler.setTextSize(dp(11.5f));
        mRuler.setColor(0xFFCFCFCF);
        mPillText.setTextSize(dp(13.5f));

        mStroke.setStyle(Paint.Style.STROKE);
        mStroke.setStrokeWidth(dp(1.5f));

        // Filters on the left, actions on the right. LEFT_BUTTONS says where the first group ends.
        mButtons.add(button(TvModeService.ACTION_GENRE, R.drawable.icon_tv_mode));
        mButtons.add(button(TvModeService.ACTION_TOPIC, R.drawable.icon_playlist));
        mButtons.add(button(TvModeService.ACTION_LANGUAGE, R.drawable.settings_language));
        mButtons.add(button(TvModeService.ACTION_FAVOURITES, R.drawable.icon_subscriptions));
        mButtons.add(button(TvModeService.ACTION_SEARCH, R.drawable.action_search));
        mButtons.add(button(TvModeService.ACTION_NEW_CHANNEL, R.drawable.icon_playlist));
        mButtons.add(button(TvModeService.ACTION_SETTINGS, R.drawable.icon_settings));
        labelButtons();
        focusChannel(mService.getGuideChannelId());
    }

    private Video button(String action, int iconResId) {
        Video button = new Video();
        button.tvChannelId = action;
        button.cardImageUrl = "android.resource://" + getContext().getPackageName() + "/" + iconResId;
        return button;
    }

    /** The filter buttons say what they are set to, so a filter that is on shows without opening it. */
    private void labelButtons() {
        String kind = mService.getFilterKind();
        String genre = mService.getFilterGenre();
        String language = mService.getFilterLanguage();
        String languageName = language.isEmpty() ? "" : com.liskovsoft.smartyoutubetv2.common.tvmode.TvLanguage.nameOf(language);
        mButtons.get(0).title = "Type: " + (kind.isEmpty() ? "Any" : kind);
        mButtons.get(1).title = "Genre: " + (genre.isEmpty() ? "Any" : genre);
        mButtons.get(2).title = "Language: " + (language.isEmpty() ? "Any" : languageName);
        mButtons.get(3).title = "Favourites";
        mButtons.get(4).title = "Search";
        mButtons.get(5).title = getContext().getString(R.string.tv_mode_new_channel);
        mButtons.get(6).title = getContext().getString(R.string.tv_mode_settings);

        // When the full labels don't fit on the line: the filters say only what they are set to,
        // and Favourites keeps just its star. The picture above names the focused button anyway.
        mShortTitles[0] = kind.isEmpty() ? "Type" : kind;
        mShortTitles[1] = genre.isEmpty() ? "Genre" : genre;
        mShortTitles[2] = language.isEmpty() ? "Language" : languageName;
        mShortTitles[3] = "";
        mShortTitles[4] = mButtons.get(4).title;
        mShortTitles[5] = "New";
        mShortTitles[6] = mButtons.get(6).title;
    }

    private final String[] mShortTitles = new String[7];

    /** A top line button: its icon, and its label when it has one. */
    private float pillWidth(String label, float iconSize) {
        return label == null || label.isEmpty()
                ? dp(12) + iconSize + dp(12)
                : dp(12) + iconSize + dp(7) + mPillText.measureText(label) + dp(14);
    }

    /** The lines above the time grid that have something on them. */
    private List<String> shelves() {
        List<String> shown = new ArrayList<>();

        for (String shelf : SHELVES) {
            // The results line shows while the playlist finder is still looking, before its first card.
            if (!mService.getShelf(shelf).isEmpty() || (TvModeService.SHELF_RESULTS.equals(shelf) && mService.isResultsLoading())) {
                shown.add(shelf);
            }
        }

        return shown;
    }

    /** The card to land on when the focus comes into a line: the one it was on last time. */
    private int rememberedItem(String shelf) {
        Integer item = shelf != null ? mShelfItems.get(shelf) : null;
        return item != null ? item : 0;
    }

    // ---------------- Free mode lines ----------------

    private boolean isFree(TvChannel channel) {
        return !mService.isLive(channel);
    }

    /** A Free Series line goes on past its queue with the rest of the series, read from the playlist already in memory. */
    private List<Video> upcoming(TvChannel channel) {
        return isFree(channel) ? mService.upcomingEpisodes(channel) : java.util.Collections.<Video>emptyList();
    }

    /** How many programs a line has to move through: its lineup, and on a Free Series line the rest of the series. */
    private int programCount(TvChannel channel) {
        return channel.lineup.size() + upcoming(channel).size();
    }

    private Video programOf(TvChannel channel, int index) {
        if (index < 0) {
            return null;
        }
        if (index < channel.lineup.size()) {
            return channel.lineup.get(index);
        }
        List<Video> more = upcoming(channel);
        int at = index - channel.lineup.size();
        return at < more.size() ? more.get(at) : null;
    }

    private int cardsThatFit() {
        return Math.max(1, (int) ((getWidth() - timelineLeft()) / dp(CARD_DP)));
    }

    /** The first card on screen of a Free line. */
    private int freeFirst(TvChannel channel) {
        Integer offset = mFreeOffset.get(channel.id);
        int first = Math.max(0, channel.pos) + (offset != null ? offset : 0);
        return Math.max(firstProgram(channel), Math.min(first, Math.max(0, programCount(channel) - 1)));
    }

    /** Scrolls a Free line just enough for the focused card to be on screen. */
    private void scrollFreeToFocus(TvChannel channel) {
        if (mProgram < 0) {
            return;
        }

        int first = freeFirst(channel);
        int fit = cardsThatFit();

        if (mProgram < first) {
            first = mProgram;
        } else if (mProgram >= first + fit) {
            first = mProgram - fit + 1;
        }

        mFreeOffset.put(channel.id, first - Math.max(0, channel.pos));
    }

    /** Holding Left or Right speeds up: one card at a time at first, then three, then ten. */
    private static int stepFor(KeyEvent event) {
        int repeats = event.getRepeatCount();
        return repeats > 24 ? 10 : repeats > 8 ? 3 : 1;
    }

    /** The lines are drawn in full only while the focus is on one of them: the guide has room for three channels. */
    private boolean shelvesOpen() {
        return mRow < 0 && mShelf != null;
    }

    /** Where the time grid starts: under the buttons, and under the lines above the grid when there are any. */
    private float gridTop() {
        int count = shelves().size();
        return dp(36) + (count == 0 ? 0 : shelvesOpen() ? count * dp(44) + dp(2) : dp(24));
    }

    private String shelfName(String shelf) {
        if (TvModeService.SHELF_RESULTS.equals(shelf)) {
            String name = mService.getResultsName();
            return name.isEmpty() ? "RESULTS" : name.toUpperCase();
        }
        return TvModeService.SHELF_RESUME.equals(shelf) ? "CONTINUE WATCHING" : "MY LIST";
    }

    /** A search picked a channel, or a program coming up on one: the focus goes there. */
    private void focusTarget(String channelId, String videoId) {
        List<TvChannel> channels = mService.getShownChannels();

        for (int i = 0; channelId != null && i < channels.size(); i++) {
            TvChannel channel = channels.get(i);

            if (!channelId.equals(channel.id)) {
                continue;
            }

            mRow = i;
            mShelf = null;
            mFocusedId = channelId;
            mProgram = -1;
            mFocusTimeMs = 0;
            mOnNowFocused = true;
            mReturnRow = -1;

            int index = videoId != null ? channel.indexOf(videoId) : -1;

            if (index > channel.pos) {
                mProgram = index;
                mOnNowFocused = false;
                // A Free line has no clock: the focus goes to the card, and the line scrolls to it.
                mFocusTimeMs = isFree(channel) ? 0 : startOf(channel, index, System.currentTimeMillis());
            }

            return;
        }
    }

    /** The focus is on a channel, not on the buttons in the top line. */
    public boolean isChannelFocused() {
        return mRow >= 0;
    }

    /** Puts the focus on a channel, when the filters show it. Used to come back to where the viewer was. */
    public void focusChannel(String channelId) {
        List<TvChannel> channels = mService.getShownChannels();

        for (int i = 0; channelId != null && i < channels.size(); i++) {
            if (channelId.equals(channels.get(i).id)) {
                if (mRow != i) {
                    mRow = i;
                    mShelf = null;
                    mProgram = -1;
                    mFocusTimeMs = 0;
                    mOnNowFocused = true;
                }
                mFocusedId = channelId;
                return;
            }
        }
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /** Channels or lineups changed. Keeps the focus on the same channel and program where it can. */
    public void reload() {
        String[] target = mService.takeGuideTarget();
        List<TvChannel> channels = mService.getShownChannels();
        labelButtons();

        if (target != null) {
            focusTarget(target[0], target[1]);
        }

        // A search just filled the results line: it opens with the focus on its first card.
        String shelfTarget = mService.takeShelfTarget();
        if (shelfTarget != null && shelves().contains(shelfTarget)) {
            if (mRow >= 0) {
                mReturnRow = mRow; // Down goes back to the channel the viewer was on
            }
            mRow = -1;
            mShelf = shelfTarget;
            mShelfItem = 0;
            mShelfItems.put(shelfTarget, 0);
            mShelfFirst.put(shelfTarget, 0);
        }

        // The list under the focus changed (a filter, a move, a delete): stay on the same channel
        // when it is still shown, on the one that took its place otherwise.
        if (mRow >= 0 && mFocusedId != null) {
            int at = -1;

            for (int i = 0; i < channels.size(); i++) {
                if (mFocusedId.equals(channels.get(i).id)) {
                    at = i;
                    break;
                }
            }

            if (at == -1) {
                mRow = Math.min(mRow, channels.size() - 1);
                mProgram = -1;
            } else {
                mRow = at;
            }
        }

        // A filter changed what is listed: start from the top of the new list.
        if (mRow < 0 && mShownCount != channels.size()) {
            mTopRow = 0;
            mReturnRow = -1;
        }
        mShownCount = channels.size();

        if (mRow >= channels.size()) {
            mRow = channels.size() - 1;
            mProgram = -1;
        }

        if (mRow >= 0) {
            TvChannel channel = channels.get(mRow);
            if (mProgram < firstProgram(channel) || mProgram >= programCount(channel)) {
                mProgram = -1; // it's gone: back to what the channel has on
            }
        }

        followNow();
        fixFocus();
        invalidate();
    }

    /** A channel moved on to its next video while the focus was on what it had on: stay on what is on. */
    private void followNow() {
        List<TvChannel> channels = mService.getShownChannels();

        if (mRow >= 0 && mRow < channels.size() && mOnNowFocused) {
            TvChannel channel = channels.get(mRow);
            mService.onNowStartMs(channel, System.currentTimeMillis()); // moves a live channel to where the clock is
            if (channel.onNow() != null && mProgram != channel.pos) {
                mProgram = channel.pos;
                mFocusTimeMs = 0;
                fixFocus();
            }
        }
    }

    /** The video the focus is on, for the part of the guide above the grid. */
    public Video getFocused() {
        if (mRow < 0) {
            if (mShelf != null) {
                List<Video> cards = mService.getShelf(mShelf);
                if (mShelfItem >= 0 && mShelfItem < cards.size()) {
                    return cards.get(mShelfItem);
                }
                if (cards.isEmpty()) {
                    return null; // the results line while the finder is still looking
                }
            }
            return mButtons.get(mButton);
        }

        List<TvChannel> channels = mService.getShownChannels();

        if (mRow >= channels.size()) {
            return null;
        }

        TvChannel channel = channels.get(mRow);
        Video program = programOf(channel, mProgram);
        return program != null ? program : channel.onNow();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(mTick);
        postDelayed(mTick, TICK_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mTick);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        invalidate();

        if (mListener != null) {
            mListener.onGridFocus(gainFocus); // losing it means the side menu opened
        }
    }

    // ---------------- time line ----------------

    /** Clock time at which the channel's video on now started. A Free mode channel is paused, so it keeps moving with now. */
    private long onNowStart(TvChannel channel, long now) {
        return mService.onNowStartMs(channel, now);
    }

    private long startOf(TvChannel channel, int index, long now) {
        long time = onNowStart(channel, now);

        for (int i = channel.pos; i < index && i < channel.lineup.size(); i++) {
            time += channel.durationOf(channel.lineup.get(i));
        }

        for (int i = channel.pos - 1; i >= index && i >= 0; i--) {
            time -= channel.durationOf(channel.lineup.get(i));
        }

        return time;
    }

    private static int firstProgram(TvChannel channel) {
        return Math.max(0, channel.pos - PAST_PROGRAMS);
    }

    /** The program of a channel that covers a moment in time. */
    private int programAt(TvChannel channel, long timeMs, long now) {
        int first = firstProgram(channel);
        long start = startOf(channel, first, now);

        for (int i = first; i < channel.lineup.size(); i++) {
            long end = start + channel.durationOf(channel.lineup.get(i));
            if (timeMs < end || i == channel.lineup.size() - 1) {
                return i;
            }
            start = end;
        }

        return channel.pos;
    }

    private float pxPerMs() {
        return dp(10) / (float) MINUTE; // 10dp a minute: half an hour is 300dp
    }

    private float timelineLeft() {
        return dp(55 + 150 + 6);
    }

    private long viewStart(long now) {
        return now - PAST_SHOWN_MS + mScrollMs;
    }

    // ---------------- focus ----------------

    private void fixFocus() {
        List<TvChannel> channels = mService.getShownChannels();
        long now = System.currentTimeMillis();

        if (channels.isEmpty()) {
            mRow = -1;
        }

        if (mRow < 0) {
            mRow = -1;
            mButton = Math.max(0, Math.min(mButton, mButtons.size() - 1));
            mScrollMs = 0;

            if (mShelf != null) {
                List<Video> cards = mService.getShelf(mShelf);

                if (cards.isEmpty() && shelves().contains(mShelf)) {
                    mShelfItem = 0; // the results line, still looking: the focus waits on it
                } else if (cards.isEmpty()) {
                    // Its last program was removed: the other line, or the buttons when that one is empty too.
                    List<String> left = shelves();
                    mShelf = left.isEmpty() ? null : left.get(0);
                    mShelfItem = 0;
                } else {
                    mShelfItem = Math.max(0, Math.min(mShelfItem, cards.size() - 1));
                }
            }
        } else {
            TvChannel channel = channels.get(mRow);

            if (channel.lineup.isEmpty()) {
                mProgram = -1;
            } else if (mProgram < 0 && isFree(channel)) {
                // A Free line has no clock to line up with: back to the card it was on, or to what's on.
                String last = mFreeFocus.get(channel.id);
                int index = last != null ? channel.indexOf(last) : -1;
                if (index < 0 && last != null) { // one of the episodes past the queue
                    List<Video> more = upcoming(channel);
                    for (int i = 0; i < more.size(); i++) {
                        if (last.equals(more.get(i).videoId)) {
                            index = channel.lineup.size() + i;
                            break;
                        }
                    }
                }
                mProgram = index > channel.pos ? index : Math.max(0, channel.pos);
            } else if (mProgram < 0) {
                // Just arrived on this channel: whatever it has on at the time the focus was at.
                // Only a time still ahead counts. One that has passed since it was set, by the
                // clock running on or by coming down from the top line a while ago, sent the
                // focus to a program that had already played instead of the one on now.
                if (mFocusTimeMs <= now) {
                    mFocusTimeMs = 0;
                }
                mProgram = programAt(channel, mFocusTimeMs > 0 ? mFocusTimeMs : now, now);
            }

            if (isFree(channel)) {
                scrollFreeToFocus(channel); // the time line, and the other lines, stay where they are
            } else {
                scrollToFocus(channel, now);
            }
        }

        int visible = visibleRows();
        if (mRow < mTopRow) {
            mTopRow = Math.max(0, mRow);
        } else if (mRow >= mTopRow + visible) {
            mTopRow = mRow - visible + 1;
        }
    }

    /** Moves the time line just enough for the focused program's start to be on screen. */
    private void scrollToFocus(TvChannel channel, long now) {
        if (mProgram < 0 || getWidth() == 0) {
            return;
        }

        long start = startOf(channel, mProgram, now);
        long end = start + channel.durationOf(channel.lineup.get(mProgram));
        long shownMs = (long) ((getWidth() - timelineLeft()) / pxPerMs());

        if (start <= now && end > now) {
            mScrollMs = 0; // what's on now: the grid's resting place
        } else if (start < viewStart(now)) {
            mScrollMs = start - (now - PAST_SHOWN_MS);
        } else if (start > viewStart(now) + shownMs * 6 / 10) {
            mScrollMs = start - (now - PAST_SHOWN_MS) - shownMs * 3 / 10;
        }
    }

    private void notifyFocus() {
        List<TvChannel> channels = mService.getShownChannels();

        if (mRow >= 0 && mRow < channels.size()) {
            mFocusedId = channels.get(mRow).id;
            mService.setGuideChannel(channels.get(mRow)); // the guide comes back to this channel
        }

        if (mListener != null) {
            mListener.onFocused(getFocused());
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        List<TvChannel> channels = mService.getShownChannels();
        long now = System.currentTimeMillis();

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mRow >= 0) {
                    mRow--;
                    mProgram = -1;
                    if (mRow < 0) { // off the first channel: the lines above the grid, the lowest first
                        List<String> lines = shelves();
                        mShelf = lines.isEmpty() ? null : lines.get(lines.size() - 1);
                        mShelfItem = rememberedItem(mShelf);
                    }
                    moved();
                } else if (mShelf != null) {
                    List<String> lines = shelves();
                    int at = lines.indexOf(mShelf);
                    mShelf = at > 0 ? lines.get(at - 1) : null;
                    mShelfItem = rememberedItem(mShelf);
                    moved();
                }
                return true; // the top line is where Up stops
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mRow < 0) {
                    // From the buttons through the lines above the grid, then on to the channels.
                    List<String> lines = shelves();
                    int at = mShelf != null ? lines.indexOf(mShelf) : -1;
                    if (at + 1 < lines.size()) {
                        mShelf = lines.get(at + 1);
                        mShelfItem = rememberedItem(mShelf);
                        moved();
                        return true;
                    }
                }
                if (mRow < channels.size() - 1) {
                    if (mRow < 0) {
                        mShelf = null;
                        mFocusTimeMs = 0; // what is on now, and it stays "now" as the clock runs
                        // After Back, Down goes back to the channel Back was pressed on.
                        mRow = mReturnRow > 0 && mReturnRow < channels.size() ? mReturnRow - 1 : -1;
                        mReturnRow = -1;
                    }
                    mRow++;
                    mProgram = -1;
                    moved();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                // From a channel, Back goes up to the filters. From there it opens the side menu as before.
                if (mRow >= 0) {
                    mBackTaken = true;
                    mReturnRow = mRow;
                    mRow = -1;
                    mShelf = null;
                    mProgram = -1;
                    moved();
                    return true;
                }
                if (mShelf != null) { // from a line above the grid, Back goes to the buttons too
                    mBackTaken = true;
                    mShelf = null;
                    moved();
                    return true;
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (mRow < 0 && mShelf != null) {
                    if (mShelfItem == 0) {
                        return false; // off the left edge: the side menu
                    }
                    mShelfItem = Math.max(0, mShelfItem - stepFor(event));
                    moved();
                    return true;
                }
                if (mRow < 0) {
                    if (mButton == 0) {
                        return false; // off the left edge: the side menu
                    }
                    mButton--;
                    moved();
                    return true;
                }
                if (mProgram > firstProgram(channels.get(mRow))) {
                    TvChannel channel = channels.get(mRow);
                    mProgram = Math.max(firstProgram(channel), mProgram - stepFor(event));
                    // Back on what's on now: Up/Down follow now again, not this program's start.
                    // A Free line has no clock, so moving along it never sets a time for the others.
                    mFocusTimeMs = mProgram == channel.pos || isFree(channel) ? 0 : startOf(channel, mProgram, now);
                    moved();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mRow < 0 && mShelf != null) {
                    mShelfItem = Math.max(0, Math.min(mService.getShelf(mShelf).size() - 1, mShelfItem + stepFor(event)));
                } else if (mRow < 0) {
                    mButton = Math.min(mButtons.size() - 1, mButton + 1);
                } else if (mProgram >= 0 && mProgram < programCount(channels.get(mRow)) - 1) {
                    TvChannel channel = channels.get(mRow);
                    mProgram = Math.min(programCount(channel) - 1, mProgram + stepFor(event));
                    mFocusTimeMs = mProgram == channel.pos || isFree(channel) ? 0 : startOf(channel, mProgram, now);
                }
                moved();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (event.getRepeatCount() == 0) {
                    mLongPressed = false;
                    event.startTracking();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
                longClick();
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            mLongPressed = true;
            longClick();
            return true;
        }

        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!mLongPressed && event.isTracking() && !event.isCanceled() && mListener != null && getFocused() != null) {
                mListener.onClicked(getFocused());
            }
            mLongPressed = false;
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BACK && mBackTaken) {
            mBackTaken = false;
            return true; // the press that moved the focus up must not also open the side menu
        }

        return super.onKeyUp(keyCode, event);
    }

    private void longClick() {
        if (mListener != null && (mRow >= 0 || mShelf != null) && getFocused() != null) {
            mListener.onLongClicked(getFocused());
        }
    }

    private void moved() {
        fixFocus();
        List<TvChannel> channels = mService.getShownChannels();
        mOnNowFocused = mRow < 0 || (mRow < channels.size() && mProgram == channels.get(mRow).pos);

        if (mRow < 0 && mShelf != null) {
            mShelfItems.put(mShelf, mShelfItem);
        } else if (mRow >= 0 && mRow < channels.size() && isFree(channels.get(mRow))) {
            TvChannel channel = channels.get(mRow);
            Video program = programOf(channel, mProgram);
            if (program != null && program.videoId != null && mProgram > channel.pos) {
                mFreeFocus.put(channel.id, program.videoId);
            } else {
                mFreeFocus.remove(channel.id); // on what's on: follow it as the channel moves on
            }
        }
        invalidate();
        notifyFocus();
        // Leaving a channel can put its program on "Continue watching", a moment after the focus
        // has moved: drawn again then, the line shows it at once instead of at the next tick.
        removeCallbacks(mRedraw);
        postDelayed(mRedraw, 500);
    }

    private final Runnable mRedraw = () -> invalidate();

    // ---------------- drawing ----------------

    private int visibleRows() {
        int rowsHeight = getHeight() - (int) gridTop() - dp(22);
        return Math.max(1, rowsHeight / dp(44));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fixFocus();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        List<TvChannel> channels = mService.getShownChannels();
        long now = System.currentTimeMillis();
        boolean focused = isFocused();
        int width = getWidth();
        float left = dp(55);
        float timelineLeft = timelineLeft();
        float pxPerMs = pxPerMs();
        long viewStart = viewStart(now);

        // Top line: the filters from the left, the actions from the right. Each is an icon and a label.
        float iconSize = dp(14);
        float[] pillWidths = new float[mButtons.size()];
        String[] labels = new String[mButtons.size()];
        float rightWidth = 0;
        float allWidth = 0;
        for (int i = 0; i < mButtons.size(); i++) {
            labels[i] = mButtons.get(i).title;
            pillWidths[i] = pillWidth(labels[i], iconSize);
            allWidth += pillWidths[i] + dp(9);
        }
        if (left + allWidth + dp(36) > width - dp(45)) { // too long for one line: the short labels
            for (int i = 0; i < mButtons.size(); i++) {
                labels[i] = mShortTitles[i] != null ? mShortTitles[i] : labels[i];
                pillWidths[i] = pillWidth(labels[i], iconSize);
            }
        }
        for (int i = LEFT_BUTTONS; i < mButtons.size(); i++) {
            rightWidth += pillWidths[i] + (i > LEFT_BUTTONS ? dp(9) : 0);
        }
        float x = left;
        for (int i = 0; i < mButtons.size(); i++) {
            if (i == LEFT_BUTTONS) {
                x = Math.max(x, width - dp(45) - rightWidth);
            }
            String action = mButtons.get(i).tvChannelId;
            boolean on = focused && mRow < 0 && mShelf == null && mButton == i;
            // A filter that is on keeps a red tint, so it shows from anywhere in the guide.
            boolean active = (TvModeService.ACTION_FAVOURITES.equals(action) && mService.isFavOnly())
                    || (TvModeService.ACTION_GENRE.equals(action) && !mService.getFilterKind().isEmpty())
                    || (TvModeService.ACTION_TOPIC.equals(action) && !mService.getFilterGenre().isEmpty())
                    || (TvModeService.ACTION_LANGUAGE.equals(action) && !mService.getFilterLanguage().isEmpty());
            mRect.set(x, dp(2), x + pillWidths[i], dp(30));
            mFill.setColor(on ? FOCUSED : active ? 0x99E62117 : 0x1FFFFFFF);
            canvas.drawRoundRect(mRect, dp(14), dp(14), mFill);
            int ink = on ? 0xFF111111 : 0xFFFFFFFF;
            drawIcon(canvas, action, x + dp(12) + iconSize / 2, dp(16), iconSize, ink, active);
            mPillText.setColor(ink);
            canvas.drawText(labels[i], x + dp(12) + iconSize + dp(7), dp(21), mPillText);
            x += pillWidths[i] + dp(9);
        }

        // Continue watching and My list sit between the buttons and the time grid: no clock runs on them.
        float gridTop = gridTop();
        drawShelves(canvas, focused, width, left, timelineLeft);

        if (channels.isEmpty()) {
            mRuler.setTypeface(Typeface.DEFAULT);
            canvas.drawText(mService.getChannels().isEmpty() ? "Channels are loading" : "No channel matches the filters above. Set them to Any to see every channel.",
                    left, gridTop + dp(60), mRuler);
            return;
        }

        // Ruler: the time now on the left, then a mark every half hour
        float rulerBase = gridTop + dp(15);
        mRuler.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(time(now), left, rulerBase, mRuler);
        mRuler.setTypeface(Typeface.DEFAULT);
        long halfHour = 30 * MINUTE;
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(viewStart);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        calendar.set(java.util.Calendar.MINUTE, calendar.get(java.util.Calendar.MINUTE) < 30 ? 0 : 30);
        float rowTop = gridTop + dp(22);
        float rowHeight = dp(44);
        float cellHeight = dp(40);
        int visible = visibleRows() + 1; // the last one may be cut off, which shows there is more

        // The clock only means something for Live lines. With none on screen, no times, no now line:
        // Free lines wait for the viewer, and a clock over them read as if they ran on it.
        boolean anyLive = false;
        for (int row = mTopRow; row < channels.size() && row < mTopRow + visible; row++) {
            anyLive |= mService.isLive(channels.get(row));
        }

        List<Float> marks = new ArrayList<>();
        canvas.save();
        canvas.clipRect(timelineLeft, gridTop, width, getHeight());
        for (long mark = calendar.getTimeInMillis(); (mark - viewStart) * pxPerMs < width; mark += halfHour) {
            float markX = timelineLeft + (mark - viewStart) * pxPerMs;
            marks.add(markX);
            if (anyLive) {
                mFill.setColor(0x33FFFFFF);
                canvas.drawRect(markX, gridTop, markX + dp(1), rowTop, mFill);
                canvas.drawText(time(mark), markX + dp(5), rulerBase, mRuler);
            }
        }
        canvas.restore();

        // Channels

        for (int row = mTopRow; row < channels.size() && row < mTopRow + visible; row++) {
            TvChannel channel = channels.get(row);
            float top = rowTop + (row - mTopRow) * rowHeight;
            boolean rowFocused = focused && row == mRow;
            boolean live = mService.isLive(channel);

            // The channel: number and name
            mRect.set(left, top, left + dp(150), top + cellHeight);
            mFill.setColor(rowFocused ? RED : LINE);
            canvas.drawRoundRect(mRect, dp(7), dp(7), mFill);
            mNumber.setColor(0xFFFFFFFF);
            // The channel's own number, which a filter doesn't change: it is what the number keys tune to.
            canvas.drawText(String.valueOf(mService.indexOf(channel) + 1), left + dp(10), top + dp(26), mNumber);
            mName.setColor(rowFocused ? 0xFFFFFFFF : 0xFFD6D6D6);
            String name = channel.name.toUpperCase() + (channel.fav ? "  ★" : "");
            float nameLeft = left + dp(48);
            float nameRoom = dp(150) - dp(54);
            String about = about(channel, nameRoom);
            canvas.drawText(fit(name, mName, nameRoom), nameLeft, top + (about.isEmpty() ? dp(24) : dp(18)), mName);
            if (!about.isEmpty()) {
                mName.setColor(rowFocused ? 0xFFFFFFFF : 0xFF9E9E9E);
                canvas.drawText(about, nameLeft, top + dp(32), mName);
            }

            if (!live) { // Free: cards of one width, scrolled on their own
                drawFreeRow(canvas, channel, top, cellHeight, rowFocused, width, timelineLeft);
                continue;
            }

            // The half hour marks and the now line run down the Live lines only: a Free line has no clock.
            mFill.setColor(0x33FFFFFF);
            for (float markX : marks) {
                if (markX >= timelineLeft && markX <= width) {
                    canvas.drawRect(markX, top - (rowHeight - cellHeight), markX + dp(1), top + cellHeight, mFill);
                }
            }

            float nowLineX = timelineLeft + (now - viewStart) * pxPerMs;
            if (nowLineX >= timelineLeft && nowLineX <= width) {
                mFill.setColor(RED);
                canvas.drawRect(nowLineX - dp(1), top - (rowHeight - cellHeight), nowLineX + dp(1), top + cellHeight, mFill);
            }

            // Its programs, each as wide as it is long
            canvas.save();
            canvas.clipRect(timelineLeft, top, width, top + cellHeight);
            int first = firstProgram(channel);
            long start = channel.lineup.isEmpty() ? 0 : startOf(channel, first, now);

            for (int i = first; i < channel.lineup.size(); i++) {
                Video video = channel.lineup.get(i);
                long end = start + channel.durationOf(video);
                float cellLeft = timelineLeft + (start - viewStart) * pxPerMs;
                float cellRight = timelineLeft + (end - viewStart) * pxPerMs - dp(3);
                start = end;

                if (cellRight < timelineLeft) {
                    continue;
                }
                if (cellLeft > width) {
                    break;
                }

                boolean on = rowFocused && i == mProgram;
                boolean onNow = i == channel.pos;
                boolean dimmed = i < channel.pos || (live && !onNow);
                mRect.set(cellLeft, top, cellRight, top + cellHeight);
                mFill.setColor(on ? FOCUSED : onNow ? LINE_NOW : LINE);
                canvas.drawRoundRect(mRect, dp(7), dp(7), mFill);

                if (onNow) { // a red edge marks what's on
                    mFill.setColor(RED);
                    canvas.drawRect(Math.max(cellLeft, timelineLeft), top + cellHeight - dp(2.5f), cellRight, top + cellHeight, mFill);
                }

                float textLeft = Math.max(cellLeft, timelineLeft) + dp(9);

                // A small picture at the start of the program, when the program is wide enough to keep its title.
                float thumbWidth = dp(64);
                float thumbLeft = Math.max(cellLeft, timelineLeft);
                if (cellRight - thumbLeft > thumbWidth + dp(70)) {
                    android.graphics.Bitmap thumb = thumb(video);
                    if (thumb != null && !thumb.isRecycled()) {
                        mRect.set(thumbLeft + dp(2), top + dp(2), thumbLeft + dp(2) + thumbWidth, top + cellHeight - dp(2));
                        mThumbPaint.setAlpha(dimmed && !on ? 120 : 255);
                        canvas.drawBitmap(thumb, null, mRect, mThumbPaint);
                        textLeft = thumbLeft + thumbWidth + dp(10);
                    }
                }

                float room = cellRight - textLeft - dp(8);

                if (room > dp(24)) {
                    mTitle.setColor(on ? 0xFF111111 : dimmed ? 0xFF9A9A9A : 0xFFFFFFFF);
                    mTitle.setTypeface(onNow ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                    canvas.drawText(fit(video.getTitle() != null ? video.getTitle() : "", mTitle, room),
                            textLeft, top + dp(17), mTitle);

                    String author = video.getAuthor();
                    String meta = i < channel.pos ? "Watched" : live && !onNow ? time(end - channel.durationOf(video)) : author != null ? author.trim() : "";
                    if (video.badge != null && !video.badge.isEmpty()) {
                        meta += (meta.isEmpty() ? "" : "  •  ") + video.badge;
                    }
                    mMeta.setColor(on ? 0xFF333333 : dimmed ? 0xFF808080 : 0xFFBDBDBD);
                    canvas.drawText(fit(meta, mMeta, room), textLeft, top + dp(32), mMeta);
                }
            }

            canvas.restore();

            // The now line again, over the programs, where it shows through.
            if (nowLineX >= timelineLeft && nowLineX <= width) {
                mFill.setColor(RED);
                canvas.drawRect(nowLineX - dp(1), top, nowLineX + dp(1), top + cellHeight, mFill);
            }
        }

        // Now, on the ruler above the channels
        float nowX = timelineLeft + (now - viewStart) * pxPerMs;
        if (anyLive && nowX >= timelineLeft && nowX <= width) {
            mFill.setColor(RED);
            canvas.drawRect(nowX - dp(1), gridTop, nowX + dp(1), rowTop, mFill);
        }
    }

    /**
     * A Free mode line: no clock runs on it, so every program is a card of one width, like the
     * lines above the grid, and the line scrolls on its own. Already played on the left, what's
     * on with a red edge, then what's next. A Series line goes on with the rest of the series.
     */
    private void drawFreeRow(Canvas canvas, TvChannel channel, float top, float cellHeight, boolean rowFocused, int width, float timelineLeft) {
        float cardWidth = dp(CARD_DP);
        float thumbWidth = dp(64);
        int count = programCount(channel);
        int first = freeFirst(channel);

        canvas.save();
        canvas.clipRect(timelineLeft, top, width, top + cellHeight);

        for (int i = first; i < count; i++) {
            float cellLeft = timelineLeft + (i - first) * cardWidth;
            if (cellLeft > width) {
                break;
            }

            Video video = programOf(channel, i);
            if (video == null) {
                break;
            }

            float cellRight = cellLeft + cardWidth - dp(3);
            boolean on = rowFocused && i == mProgram;
            boolean onNow = i == channel.pos;
            boolean past = i < channel.pos;
            boolean beyond = i >= channel.lineup.size(); // past the queue: read from the playlist

            mRect.set(cellLeft, top, cellRight, top + cellHeight);
            mFill.setColor(on ? FOCUSED : onNow ? LINE_NOW : LINE);
            canvas.drawRoundRect(mRect, dp(7), dp(7), mFill);

            if (onNow) {
                mFill.setColor(RED);
                float edge = video.percentWatched > 0 && video.percentWatched < 100
                        ? cellLeft + (cellRight - cellLeft) * video.percentWatched / 100f : cellRight;
                canvas.drawRect(cellLeft, top + cellHeight - dp(2.5f), edge, top + cellHeight, mFill);
            }

            float textLeft = cellLeft + dp(9);
            android.graphics.Bitmap thumb = thumb(video);

            if (thumb != null && !thumb.isRecycled()) {
                mRect.set(cellLeft + dp(2), top + dp(2), cellLeft + dp(2) + thumbWidth, top + cellHeight - dp(2));
                mThumbPaint.setAlpha(past && !on ? 120 : 255);
                canvas.drawBitmap(thumb, null, mRect, mThumbPaint);
                textLeft = cellLeft + thumbWidth + dp(10);
            }

            float room = cellRight - textLeft - dp(8);
            mTitle.setColor(on ? 0xFF111111 : past ? 0xFF9A9A9A : 0xFFFFFFFF);
            mTitle.setTypeface(onNow ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            canvas.drawText(fit(video.getTitle() != null ? video.getTitle() : "", mTitle, room), textLeft, top + dp(17), mTitle);

            String author = video.getAuthor() != null ? video.getAuthor().trim() : "";
            String meta = past ? "Watched" : onNow ? (video.percentWatched > 0 && video.percentWatched < 100 ? "Paused" : "Up first") : author;
            if (video.badge != null && !video.badge.isEmpty()) {
                meta = beyond || meta.isEmpty() ? video.badge + (beyond && !author.isEmpty() ? "  •  " + author : "") : meta + "  •  " + video.badge;
            }
            mMeta.setColor(on ? 0xFF333333 : past ? 0xFF808080 : 0xFFBDBDBD);
            canvas.drawText(fit(meta, mMeta, room), textLeft, top + dp(32), mMeta);
        }

        canvas.restore();
    }

    /**
     * The lines above the time grid. Closed, they are one thin line of names and counts. With the
     * focus on one of them they open: a name cell like a channel's, then the programs as cards
     * of one width, since no clock runs here.
     */
    private void drawShelves(Canvas canvas, boolean focused, int width, float left, float timelineLeft) {
        List<String> lines = shelves();

        if (lines.isEmpty()) {
            return;
        }

        if (!shelvesOpen()) {
            // Closed: a tab for each line, with a red mark and its count, and how to open them.
            // Plain grey text here went unseen.
            float x = left;
            float top = dp(35);
            float height = dp(20);

            for (String shelf : lines) {
                int size = mService.getShelf(shelf).size();
                String name = shelfName(shelf);
                // A long search would push the other tabs off the line.
                name = fit(name, mName, dp(220));
                String text = name + "   " + (size == 0 ? "…" : String.valueOf(size));
                float textLeft = x + dp(25);
                float tabWidth = dp(25) + mName.measureText(text) + dp(11);
                mRect.set(x, top, x + tabWidth, top + height);
                mFill.setColor(0x33FFFFFF);
                canvas.drawRoundRect(mRect, dp(10), dp(10), mFill);

                float cx = x + dp(14);
                float cy = top + height / 2;
                float r = dp(4.5f);
                mFill.setColor(RED);

                if (TvModeService.SHELF_RESUME.equals(shelf)) { // a play mark
                    mPath.reset();
                    mPath.moveTo(cx - r * 0.7f, cy - r);
                    mPath.lineTo(cx + r, cy);
                    mPath.lineTo(cx - r * 0.7f, cy + r);
                    mPath.close();
                    canvas.drawPath(mPath, mFill);
                } else if (TvModeService.SHELF_RESULTS.equals(shelf)) { // a magnifying glass
                    mStroke.setColor(RED);
                    canvas.drawCircle(cx - r * 0.2f, cy - r * 0.2f, r * 0.65f, mStroke);
                    canvas.drawLine(cx + r * 0.3f, cy + r * 0.3f, cx + r, cy + r, mStroke);
                } else { // a plus
                    canvas.drawRect(cx - r, cy - dp(1), cx + r, cy + dp(1), mFill);
                    canvas.drawRect(cx - dp(1), cy - r, cx + dp(1), cy + r, mFill);
                }

                mName.setColor(0xFFFFFFFF);
                canvas.drawText(text, textLeft, top + dp(13.5f), mName);
                x += tabWidth + dp(8);
            }

            // With three tabs the hint no longer fits on the line.
            if (lines.size() < 3) {
                mName.setColor(0xFF9E9E9E);
                canvas.drawText("Press Up on the first channel to open", x + dp(6), top + dp(13.5f), mName);
            }
            return;
        }

        float cardWidth = dp(232);
        float cellHeight = dp(40);
        float thumbWidth = dp(64);
        int fit = Math.max(1, (int) ((width - timelineLeft) / cardWidth));

        for (int line = 0; line < lines.size(); line++) {
            String shelf = lines.get(line);
            List<Video> cards = mService.getShelf(shelf);
            float top = dp(36) + line * dp(44);
            boolean rowFocused = focused && shelf.equals(mShelf);

            mRect.set(left, top, left + dp(150), top + cellHeight);
            mFill.setColor(rowFocused ? RED : LINE);
            canvas.drawRoundRect(mRect, dp(7), dp(7), mFill);
            boolean results = TvModeService.SHELF_RESULTS.equals(shelf);
            mName.setColor(rowFocused ? 0xFFFFFFFF : 0xFFD6D6D6);
            canvas.drawText(fit(shelfName(shelf), mName, dp(136)), left + dp(10), top + dp(18), mName);
            mName.setColor(rowFocused ? 0xFFFFFFFF : 0xFF9E9E9E);
            String count = results
                    ? (mService.isResultsLoading() ? (cards.isEmpty() ? "Looking…" : cards.size() + " so far, looking…")
                            : cards.size() + (cards.size() == 1 ? " result" : " results"))
                    : cards.size() + (cards.size() == 1 ? " program" : " programs");
            canvas.drawText(count, left + dp(10), top + dp(32), mName);

            if (cards.isEmpty()) {
                mMeta.setColor(0xFFBDBDBD);
                canvas.drawText("Opening each playlist to check it holds this program", timelineLeft + dp(9), top + dp(25), mMeta);
                continue;
            }

            // The line scrolls just enough to keep the focused card on screen.
            Integer kept = mShelfFirst.get(shelf);
            int first = kept != null ? kept : 0;

            if (shelf.equals(mShelf)) {
                if (mShelfItem < first) {
                    first = mShelfItem;
                } else if (mShelfItem >= first + fit) {
                    first = mShelfItem - fit + 1;
                }
            }

            first = Math.max(0, Math.min(first, cards.size() - 1));
            mShelfFirst.put(shelf, first);

            canvas.save();
            canvas.clipRect(timelineLeft, top, width, top + cellHeight);

            for (int i = first; i < cards.size(); i++) {
                Video card = cards.get(i);
                float cellLeft = timelineLeft + (i - first) * cardWidth;
                float cellRight = cellLeft + cardWidth - dp(3);

                if (cellLeft > width) {
                    break;
                }

                boolean on = rowFocused && i == mShelfItem;
                mRect.set(cellLeft, top, cellRight, top + cellHeight);
                mFill.setColor(on ? FOCUSED : LINE_NOW);
                canvas.drawRoundRect(mRect, dp(7), dp(7), mFill);

                float textLeft = cellLeft + dp(9);
                android.graphics.Bitmap thumb = thumb(card);

                if (thumb != null && !thumb.isRecycled()) {
                    mRect.set(cellLeft + dp(2), top + dp(2), cellLeft + dp(2) + thumbWidth, top + cellHeight - dp(2));
                    mThumbPaint.setAlpha(255);
                    canvas.drawBitmap(thumb, null, mRect, mThumbPaint);
                    textLeft = cellLeft + thumbWidth + dp(10);
                }

                boolean channelCard = results && mService.isChannelResult(card);
                if (channelCard) {
                    // A whole channel, not a program: its number in a red badge over the picture of what it has on.
                    TvModeService.Found found = mService.resultFound(card);
                    String number = found != null ? String.valueOf(mService.indexOf(found.channel) + 1) : "";
                    float badgeWidth = mNumber.measureText(number) + dp(10);
                    mRect.set(cellLeft + dp(2), top + dp(2), cellLeft + dp(2) + Math.max(thumbWidth * 0.6f, badgeWidth), top + cellHeight - dp(2));
                    mFill.setColor(RED);
                    canvas.drawRoundRect(mRect, dp(5), dp(5), mFill);
                    mNumber.setColor(0xFFFFFFFF);
                    canvas.drawText(number, mRect.left + (mRect.width() - mNumber.measureText(number)) / 2, top + dp(26), mNumber);
                    textLeft = Math.max(textLeft, mRect.right + dp(8));
                }

                float room = cellRight - textLeft - dp(8);
                mTitle.setColor(on ? 0xFF111111 : 0xFFFFFFFF);
                mTitle.setTypeface(channelCard ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                String cardTitle = card.getTitle() != null ? card.getTitle() : "";
                canvas.drawText(fit(channelCard ? cardTitle.toUpperCase() : cardTitle, mTitle, room), textLeft, top + dp(17), mTitle);
                mMeta.setColor(on ? 0xFF333333 : 0xFFBDBDBD);
                canvas.drawText(fit(results ? mService.resultNote(card) : mService.savedNote(card), mMeta, room), textLeft, top + dp(32), mMeta);

                if (card.percentWatched > 0) { // how far in, as a red edge along the bottom
                    mFill.setColor(RED);
                    canvas.drawRect(cellLeft, top + cellHeight - dp(2.5f),
                            cellLeft + (cellRight - cellLeft) * Math.min(100f, card.percentWatched) / 100f, top + cellHeight, mFill);
                }
            }

            canvas.restore();
        }
    }

    /**
     * The small line under a channel's name: its language and genre. A part that a filter already
     * fixes for every channel on screen is left out, and the genre goes first when both don't fit.
     */
    private String about(TvChannel channel, float room) {
        String language = mService.getFilterLanguage().isEmpty() && !channel.language().isEmpty()
                ? com.liskovsoft.smartyoutubetv2.common.tvmode.TvLanguage.nameOf(channel.language()) : "";
        // The program type and the genre: "Movies · Action". The type is left out when the Type
        // filter is on, and when the genre says the same ("Songs · Songs").
        String kind = mService.getFilterKind().isEmpty() && !channel.kind.equalsIgnoreCase(channel.genre) ? channel.kind : "";
        String genre = kind.isEmpty() || channel.genre.isEmpty() ? kind + channel.genre : kind + " · " + channel.genre;

        if (language.isEmpty() || genre.isEmpty()) {
            return fit(language + genre, mName, room);
        }

        String all = language + " · " + genre;
        if (mName.measureText(all) <= room) {
            return all;
        }

        // Too long: the genre is what tells two channels of a type apart, so it stays the longest.
        String shorter = language + " · " + (channel.genre.isEmpty() ? kind : channel.genre);
        return mName.measureText(shorter) <= room ? shorter : fit(channel.genre.isEmpty() ? kind : channel.genre, mName, room);
    }

    /** The top line's icons, drawn here so they are sharp at any size and take the button's colour. */
    private void drawIcon(Canvas canvas, String action, float cx, float cy, float size, int color, boolean active) {
        float r = size / 2;
        mFill.setColor(color);
        mStroke.setColor(color);

        if (TvModeService.ACTION_GENRE.equals(action)) { // four tiles
            float gap = dp(1.5f);
            canvas.drawRect(cx - r, cy - r, cx - gap, cy - gap, mFill);
            canvas.drawRect(cx + gap, cy - r, cx + r, cy - gap, mFill);
            canvas.drawRect(cx - r, cy + gap, cx - gap, cy + r, mFill);
            canvas.drawRect(cx + gap, cy + gap, cx + r, cy + r, mFill);
        } else if (TvModeService.ACTION_TOPIC.equals(action)) { // a tag: filled when the filter is on
            mPath.reset();
            mPath.moveTo(cx - r, cy - r * 0.55f);
            mPath.lineTo(cx + r * 0.35f, cy - r * 0.55f);
            mPath.lineTo(cx + r, cy);
            mPath.lineTo(cx + r * 0.35f, cy + r * 0.55f);
            mPath.lineTo(cx - r, cy + r * 0.55f);
            mPath.close();
            canvas.drawPath(mPath, active ? mFill : mStroke);
            if (!active) {
                canvas.drawCircle(cx - r * 0.45f, cy, dp(1.5f), mFill);
            }
        } else if (TvModeService.ACTION_LANGUAGE.equals(action)) { // a globe
            canvas.drawCircle(cx, cy, r - dp(0.75f), mStroke);
            mRect.set(cx - r * 0.45f, cy - r + dp(0.75f), cx + r * 0.45f, cy + r - dp(0.75f));
            canvas.drawOval(mRect, mStroke);
            canvas.drawLine(cx - r + dp(0.75f), cy, cx + r - dp(0.75f), cy, mStroke);
        } else if (TvModeService.ACTION_FAVOURITES.equals(action)) { // a star: filled when the filter is on
            mPath.reset();
            for (int i = 0; i < 10; i++) {
                double angle = -Math.PI / 2 + i * Math.PI / 5;
                float radius = i % 2 == 0 ? r : r * 0.42f;
                float px = cx + (float) Math.cos(angle) * radius;
                float py = cy + (float) Math.sin(angle) * radius;
                if (i == 0) {
                    mPath.moveTo(px, py);
                } else {
                    mPath.lineTo(px, py);
                }
            }
            mPath.close();
            canvas.drawPath(mPath, active ? mFill : mStroke);
        } else if (TvModeService.ACTION_SEARCH.equals(action)) { // a magnifying glass
            canvas.drawCircle(cx - r * 0.2f, cy - r * 0.2f, r * 0.62f, mStroke);
            canvas.drawLine(cx + r * 0.28f, cy + r * 0.28f, cx + r, cy + r, mStroke);
        } else if (TvModeService.ACTION_NEW_CHANNEL.equals(action)) { // a plus
            float t = dp(1.25f);
            canvas.drawRect(cx - r, cy - t, cx + r, cy + t, mFill);
            canvas.drawRect(cx - t, cy - r, cx + t, cy + r, mFill);
        } else { // settings: three sliders
            for (int i = -1; i <= 1; i++) {
                float y = cy + i * r * 0.72f;
                canvas.drawLine(cx - r, y, cx + r, y, mStroke);
                canvas.drawCircle(cx + (i == 0 ? r * 0.4f : -r * 0.35f), y, dp(2), mFill);
            }
        }
    }

    private final Paint mThumbPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** The program's picture if it has arrived. Asks for it once otherwise, and the grid redraws when it comes. */
    private android.graphics.Bitmap thumb(final Video video) {
        final String id = video.videoId;

        if (id == null || video.cardImageUrl == null) {
            return null;
        }

        android.graphics.Bitmap thumb = mThumbs.get(id);

        if (thumb == null && mThumbsAsked.add(id)) {
            if (mThumbs.size() > MAX_THUMBS) { // a long session: start over with what is on screen
                mThumbs.clear();
                mThumbsAsked.clear();
                mThumbsAsked.add(id);
            }

            com.bumptech.glide.Glide.with(getContext().getApplicationContext())
                    .asBitmap()
                    .load(video.cardImageUrl)
                    .override(dp(64), dp(36))
                    .centerCrop()
                    .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                        @Override
                        public void onResourceReady(android.graphics.Bitmap picture,
                                                    com.bumptech.glide.request.transition.Transition<? super android.graphics.Bitmap> transition) {
                            mThumbs.put(id, picture);
                            invalidate();
                        }

                        @Override
                        public void onLoadCleared(android.graphics.drawable.Drawable placeholder) {
                            mThumbs.remove(id);
                        }
                    });
        }

        return thumb;
    }

    /** Titles cut to fit, kept by text and width. Measuring text for every program on every frame made scrolling stutter. */
    private final java.util.Map<String, String> mFitted = new java.util.HashMap<>();

    private String fit(String text, TextPaint paint, float room) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String key = (paint.getTypeface() == Typeface.DEFAULT_BOLD ? "b" : "n") + (int) paint.getTextSize() + "|" + (int) room + "|" + text;
        String fitted = mFitted.get(key);

        if (fitted == null) {
            if (mFitted.size() > 1_500) {
                mFitted.clear();
            }
            fitted = TextUtils.ellipsize(text, paint, room, TextUtils.TruncateAt.END).toString();
            mFitted.put(key, fitted);
        }

        return fitted;
    }

    private String time(long timeMs) {
        return DateFormat.getTimeFormat(getContext()).format(new Date(timeMs));
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}

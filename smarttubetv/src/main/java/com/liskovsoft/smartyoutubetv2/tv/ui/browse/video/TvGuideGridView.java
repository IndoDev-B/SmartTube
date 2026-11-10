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
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mMeta = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mNumber = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mName = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mRuler = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mPillText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final List<Video> mButtons = new ArrayList<>();
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
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            followNow();
            notifyFocus(); // the part above the grid shows progress too
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

        mButtons.add(button(TvModeService.ACTION_SETTINGS, R.string.tv_mode_settings, R.drawable.icon_settings));
        mButtons.add(button(TvModeService.ACTION_SHUFFLE_ALL, R.string.tv_mode_shuffle_all, R.drawable.action_mode_shuffle));
        mButtons.add(button(TvModeService.ACTION_NEW_CHANNEL, R.string.tv_mode_new_channel, R.drawable.icon_playlist));
    }

    private Video button(String action, int titleResId, int iconResId) {
        Video button = new Video();
        button.tvChannelId = action;
        button.title = getContext().getString(titleResId);
        button.cardImageUrl = "android.resource://" + getContext().getPackageName() + "/" + iconResId;
        return button;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /** Channels or lineups changed. Keeps the focus on the same channel and program where it can. */
    public void reload() {
        List<TvChannel> channels = mService.getChannels();

        if (mRow >= channels.size()) {
            mRow = channels.size() - 1;
            mProgram = -1;
        }

        if (mRow >= 0) {
            TvChannel channel = channels.get(mRow);
            if (mProgram < firstProgram(channel) || mProgram >= channel.lineup.size()) {
                mProgram = -1; // it's gone: back to what the channel has on
            }
        }

        followNow();
        fixFocus();
        invalidate();
    }

    /** A channel moved on to its next video while the focus was on what it had on: stay on what is on. */
    private void followNow() {
        List<TvChannel> channels = mService.getChannels();

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
            return mButtons.get(mButton);
        }

        List<TvChannel> channels = mService.getChannels();

        if (mRow >= channels.size()) {
            return null;
        }

        TvChannel channel = channels.get(mRow);
        return mProgram >= 0 && mProgram < channel.lineup.size() ? channel.lineup.get(mProgram) : channel.onNow();
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
        List<TvChannel> channels = mService.getChannels();
        long now = System.currentTimeMillis();

        if (channels.isEmpty()) {
            mRow = -1;
        }

        if (mRow < 0) {
            mRow = -1;
            mButton = Math.max(0, Math.min(mButton, mButtons.size() - 1));
            mScrollMs = 0;
        } else {
            TvChannel channel = channels.get(mRow);

            if (channel.lineup.isEmpty()) {
                mProgram = -1;
            } else if (mProgram < 0) {
                // Just arrived on this channel: whatever it has on at the time the focus was at.
                mProgram = programAt(channel, mFocusTimeMs > 0 ? mFocusTimeMs : now, now);
            }

            scrollToFocus(channel, now);
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
        if (mListener != null) {
            mListener.onFocused(getFocused());
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        List<TvChannel> channels = mService.getChannels();
        long now = System.currentTimeMillis();

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mRow >= 0) {
                    mRow--;
                    mProgram = -1;
                    moved();
                }
                return true; // the top line is where Up stops
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (mRow < channels.size() - 1) {
                    if (mRow < 0) {
                        mFocusTimeMs = now;
                    }
                    mRow++;
                    mProgram = -1;
                    moved();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (mRow < 0) {
                    if (mButton == 0) {
                        return false; // off the left edge: the side menu
                    }
                    mButton--;
                    moved();
                    return true;
                }
                if (mProgram > firstProgram(channels.get(mRow))) {
                    mProgram--;
                    // Back on what's on now: Up/Down follow now again, not this program's start.
                    mFocusTimeMs = mProgram == channels.get(mRow).pos ? 0 : startOf(channels.get(mRow), mProgram, now);
                    moved();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (mRow < 0) {
                    mButton = Math.min(mButtons.size() - 1, mButton + 1);
                } else if (mProgram >= 0 && mProgram < channels.get(mRow).lineup.size() - 1) {
                    mProgram++;
                    mFocusTimeMs = mProgram == channels.get(mRow).pos ? 0 : startOf(channels.get(mRow), mProgram, now);
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

        return super.onKeyUp(keyCode, event);
    }

    private void longClick() {
        if (mListener != null && mRow >= 0 && getFocused() != null) {
            mListener.onLongClicked(getFocused());
        }
    }

    private void moved() {
        fixFocus();
        List<TvChannel> channels = mService.getChannels();
        mOnNowFocused = mRow < 0 || (mRow < channels.size() && mProgram == channels.get(mRow).pos);
        invalidate();
        notifyFocus();
    }

    // ---------------- drawing ----------------

    private int visibleRows() {
        int rowsHeight = getHeight() - dp(36 + 22);
        return Math.max(1, rowsHeight / dp(44));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fixFocus();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        List<TvChannel> channels = mService.getChannels();
        long now = System.currentTimeMillis();
        boolean focused = isFocused();
        int width = getWidth();
        float left = dp(55);
        float timelineLeft = timelineLeft();
        float pxPerMs = pxPerMs();
        long viewStart = viewStart(now);

        // Top line: the buttons
        float x = left;
        for (int i = 0; i < mButtons.size(); i++) {
            String label = mButtons.get(i).title;
            float pillWidth = mPillText.measureText(label) + dp(28);
            boolean on = focused && mRow < 0 && mButton == i;
            mRect.set(x, dp(2), x + pillWidth, dp(30));
            mFill.setColor(on ? FOCUSED : 0x1FFFFFFF);
            canvas.drawRoundRect(mRect, dp(14), dp(14), mFill);
            mPillText.setColor(on ? 0xFF111111 : 0xFFFFFFFF);
            canvas.drawText(label, x + dp(14), dp(21), mPillText);
            x += pillWidth + dp(9);
        }

        // Ruler: the time now on the left, then a mark every half hour
        float rulerBase = dp(36 + 15);
        mRuler.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(time(now), left, rulerBase, mRuler);
        mRuler.setTypeface(Typeface.DEFAULT);
        long halfHour = 30 * MINUTE;
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        calendar.setTimeInMillis(viewStart);
        calendar.set(java.util.Calendar.SECOND, 0);
        calendar.set(java.util.Calendar.MILLISECOND, 0);
        calendar.set(java.util.Calendar.MINUTE, calendar.get(java.util.Calendar.MINUTE) < 30 ? 0 : 30);
        canvas.save();
        canvas.clipRect(timelineLeft, 0, width, getHeight());
        for (long mark = calendar.getTimeInMillis(); (mark - viewStart) * pxPerMs < width; mark += halfHour) {
            float markX = timelineLeft + (mark - viewStart) * pxPerMs;
            mFill.setColor(0x33FFFFFF);
            canvas.drawRect(markX, dp(36), markX + dp(1), getHeight(), mFill);
            canvas.drawText(time(mark), markX + dp(5), rulerBase, mRuler);
        }
        canvas.restore();

        // Channels
        float rowTop = dp(36 + 22);
        float rowHeight = dp(44);
        float cellHeight = dp(40);
        int visible = visibleRows() + 1; // the last one may be cut off, which shows there is more

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
            canvas.drawText(String.valueOf(row + 1), left + dp(10), top + dp(26), mNumber);
            mName.setColor(rowFocused ? 0xFFFFFFFF : 0xFFD6D6D6);
            String name = channel.name.toUpperCase() + (channel.fav ? "  ★" : "");
            float nameLeft = left + dp(42);
            canvas.drawText(TextUtils.ellipsize(name, mName, dp(150) - dp(48), TextUtils.TruncateAt.END).toString(),
                    nameLeft, top + (live ? dp(18) : dp(24)), mName);
            if (live) {
                mName.setColor(rowFocused ? 0xFFFFFFFF : 0xFFFF8A80);
                canvas.drawText("LIVE", nameLeft, top + dp(32), mName);
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
                    canvas.drawText(TextUtils.ellipsize(video.getTitle() != null ? video.getTitle() : "", mTitle, room, TextUtils.TruncateAt.END).toString(),
                            textLeft, top + dp(17), mTitle);

                    String author = video.getAuthor();
                    String meta = i < channel.pos ? "Watched" : live && !onNow ? time(end - channel.durationOf(video)) : author != null ? author.trim() : "";
                    if (video.badge != null && !video.badge.isEmpty()) {
                        meta += (meta.isEmpty() ? "" : "  •  ") + video.badge;
                    }
                    mMeta.setColor(on ? 0xFF333333 : dimmed ? 0xFF808080 : 0xFFBDBDBD);
                    canvas.drawText(TextUtils.ellipsize(meta, mMeta, room, TextUtils.TruncateAt.END).toString(), textLeft, top + dp(32), mMeta);
                }
            }

            canvas.restore();
        }

        // Now: one red line down every channel
        float nowX = timelineLeft + (now - viewStart) * pxPerMs;
        if (nowX >= timelineLeft && nowX <= width) {
            mFill.setColor(RED);
            canvas.drawRect(nowX - dp(1), dp(36), nowX + dp(1), getHeight(), mFill);
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

    private String time(long timeMs) {
        return DateFormat.getTimeFormat(getContext()).format(new Date(timeMs));
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}

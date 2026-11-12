package com.liskovsoft.smartyoutubetv2.tv.ui.browse.video;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build.VERSION;
import android.text.TextUtils.TruncateAt;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvChannel;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvModeService;
import com.liskovsoft.smartyoutubetv2.tv.presenter.TvActionCardPresenter;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.embedplayer.EmbedPlayerView;

import java.util.Date;

/**
 * Top part of the TV guide: what the focused channel has on, with a preview picture.<br/>
 * Sizes are dp at 1080p, where 1dp is 2 TV pixels.
 */
public class TvGuideHeroView extends FrameLayout {
    public static final int HEIGHT_DP = 312;
    private static final int RED = 0xFFE62117;
    private static final int BACKGROUND = 0xFF0F0F10;
    private final TextView mChannelChip;
    private final TextView mModeChip;
    private final TextView mTitle;
    private final TextView mBy;
    private final View mProgressTrack;
    private final View mProgress;
    private final TextView mOkKey;
    private final TextView mOkLabel;
    private final TextView mHoldKey;
    private final TextView mHoldLabel;
    private final ImageView mPreview;
    private final ImageView mPreviewIcon;
    private final TextView mPreviewBug;
    private final TextView mClock;
    private String mShownId;
    private final FrameLayout mPreviewBox;
    // Live preview: the focused channel plays in the picture box, with sound, for as long as it's focused.
    private static final long PREVIEW_DELAY_MS = 1_200;
    private EmbedPlayerView mPreviewPlayer;
    private String mPreviewChannelId;
    private TvChannel mPreviewChannel;
    private boolean mPreviewAllowed = true;
    /** The side menu is open: no preview until it closes. */
    private boolean mPreviewPaused;
    /** The video the preview was started with, to notice when the channel has moved on to another. */
    private String mPreviewVideoId;
    private long mPreviewStartedMs;
    /** Starts in a row of the same video that didn't end up playing. */
    private int mPreviewTries;
    private Runnable mOnChannelMoved;
    /** How long a start gets before the check looks at whether it is playing. */
    private static final long PREVIEW_CHECK_MS = 6_000;
    private static final int PREVIEW_MAX_TRIES = 4;
    private final Runnable mStartPreview = this::startPreview;
    private TextView mAbout;
    private TextView mStatsLine;
    /** The program the description line is for. */
    private String mAboutId;
    private Video mAboutVideo;
    /** The focus must rest this long before a description is fetched, so scrolling fetches nothing. */
    private static final long ABOUT_DELAY_MS = 1_200;
    private static final int ABOUT_CHARS = 700;
    private final Runnable mLoadAbout = () -> {
        final Video video = mAboutVideo;

        if (video != null && isAttachedToWindow()) {
            TvModeService.instance(getContext()).loadInfo(video, (videoId, description) -> {
                if (!videoId.equals(mAboutId)) {
                    return;
                }

                if (description == null) {
                    mAboutId = null; // the fetch failed: the next look at this program asks again
                } else {
                    mAbout.setText(shorten(description));
                    showStats(videoId);
                }
            });
        }
    };

    /** A description as one short paragraph: line breaks and runs of spaces become single spaces. */
    private static String shorten(String description) {
        if (description == null) {
            return "";
        }

        String text = description.replace('\n', ' ').replace('\r', ' ').trim();

        while (text.contains("  ")) {
            text = text.replace("  ", " ");
        }

        return text.length() > ABOUT_CHARS ? text.substring(0, ABOUT_CHARS) : text;
    }

    /** The program's numbers along the bottom, when they are in. Empty until then, and for a button. */
    private void showStats(String videoId) {
        String stats = TvModeService.instance(getContext()).getStats(videoId);
        String text = stats != null ? stats : "";

        if (!text.contentEquals(mStatsLine.getText())) {
            mStatsLine.setText(text);
        }
    }

    /** Shows the description of a program if it is known, and asks for it after a moment if not. */
    private void about(Video video) {
        String id = video != null ? video.videoId : null;

        if (id == null) {
            removeCallbacks(mLoadAbout);
            mAboutId = null;
            mAboutVideo = null;
            mAbout.setText("");
            showStats(null);
            return;
        }

        // Every time, not only when the program changes: the dislikes arrive a moment after the rest.
        showStats(id);

        if (id.equals(mAboutId)) {
            return; // the same program as a moment ago: shown, or on its way
        }

        removeCallbacks(mLoadAbout);
        mAboutId = id;
        mAboutVideo = video;
        TvModeService service = TvModeService.instance(getContext());
        String known = service.getInfo(id);
        mAbout.setText(shorten(known));

        if (known == null || service.getStats(id) == null) {
            postDelayed(mLoadAbout, ABOUT_DELAY_MS);
        }
    }

    public TvGuideHeroView(Context context) {
        super(context);

        setBackgroundColor(BACKGROUND);

        // Left: what's on
        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        LayoutParams infoParams = new LayoutParams(dp(350), LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        infoParams.setMargins(dp(55), dp(25), 0, 0);
        addView(info, infoParams);

        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        info.addView(chips);

        mChannelChip = text(14f, 0xFFFFFFFF, 1);
        mChannelChip.setTypeface(null, Typeface.BOLD);
        mChannelChip.setPadding(dp(8), dp(2), dp(8), dp(2));
        mChannelChip.setBackground(rounded(RED, 0, dp(4)));
        chips.addView(mChannelChip);

        mModeChip = text(11f, 0xFFFFFFFF, 1);
        mModeChip.setTypeface(null, Typeface.BOLD);
        mModeChip.setPadding(dp(6), dp(1), dp(6), dp(1));
        mModeChip.setBackground(rounded(0, 0xC0FFFFFF, dp(4)));
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        modeParams.leftMargin = dp(6);
        chips.addView(mModeChip, modeParams);

        mTitle = text(20f, 0xFFFFFFFF, 2); // two lines at most: the room goes to the description
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(9);
        info.addView(mTitle, titleParams);

        mBy = text(14f, 0xFFE0E0E0, 2);
        mBy.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams byParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        byParams.topMargin = dp(7);
        info.addView(mBy, byParams);

        FrameLayout bar = new FrameLayout(context);
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(dp(260), dp(4));
        barParams.topMargin = dp(9);
        info.addView(bar, barParams);
        mProgressTrack = bar;
        bar.setBackground(rounded(0x4DFFFFFF, 0, dp(2)));
        mProgress = new View(context);
        mProgress.setBackground(rounded(RED, 0, dp(2)));
        bar.addView(mProgress, new LayoutParams(0, LayoutParams.MATCH_PARENT));

        // What the program is about: the start of its description, once the focus has rested on it.
        mAbout = text(13f, 0xFFB8B8B8, 6);
        mAbout.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams aboutParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        aboutParams.topMargin = dp(9);
        info.addView(mAbout, aboutParams);

        // Bottom left: the two things OK does here
        LinearLayout hint = new LinearLayout(context);
        hint.setOrientation(LinearLayout.HORIZONTAL);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams hintParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        hintParams.setMargins(dp(55), 0, 0, dp(14));
        addView(hint, hintParams);

        mOkKey = key("OK");
        hint.addView(mOkKey);
        mOkLabel = text(14f, 0xFFEDEDED, 1);
        hint.addView(mOkLabel, gap(7, 19));
        mHoldKey = key("HOLD OK");
        hint.addView(mHoldKey);
        mHoldLabel = text(14f, 0xFFEDEDED, 1);
        mHoldLabel.setText("Channel options");
        hint.addView(mHoldLabel, gap(7, 0));
        // The viewer knows what OK does by now. The line goes to the program's numbers instead:
        // views, likes, dislikes, when it was uploaded, subscribers.
        hint.setVisibility(GONE);

        mStatsLine = text(13.5f, 0xFFD0D0D0, 1);
        // As wide as the text above it: the preview picture starts right of that.
        LayoutParams statsParams = new LayoutParams(dp(350), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        statsParams.setMargins(dp(55), 0, 0, dp(12));
        addView(mStatsLine, statsParams);

        // Right: preview picture
        FrameLayout preview = new FrameLayout(context);
        mPreviewBox = preview;
        preview.setBackground(rounded(0xFF000000, 0, dp(8)));
        if (VERSION.SDK_INT >= 21) {
            preview.setClipToOutline(true);
        }
        LayoutParams previewParams = new LayoutParams(dp(500), dp(281), Gravity.TOP | Gravity.END);
        previewParams.setMargins(0, dp(20), dp(45), 0);
        addView(preview, previewParams);

        mPreview = new ImageView(context);
        mPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.addView(mPreview, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        mPreviewIcon = new ImageView(context);
        mPreviewIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        preview.addView(mPreviewIcon, new LayoutParams(dp(110), dp(110), Gravity.CENTER));

        mPreviewBug = text(13f, 0xFFFFFFFF, 1);
        mPreviewBug.setTypeface(null, Typeface.BOLD);
        mPreviewBug.setPadding(dp(7), dp(2), dp(7), dp(2));
        mPreviewBug.setBackground(rounded(0xB3000000, 0, dp(4)));
        LayoutParams bugParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        bugParams.setMargins(dp(9), dp(8), 0, 0);
        preview.addView(mPreviewBug, bugParams);

        // The clock sits over the top right corner of the picture, like a channel's on-screen clock.
        mClock = text(13f, 0xFFFFFFFF, 1);
        mClock.setTypeface(null, Typeface.BOLD);
        mClock.setPadding(dp(7), dp(2), dp(7), dp(2));
        mClock.setBackground(rounded(0xB3000000, 0, dp(4)));
        LayoutParams clockParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        clockParams.setMargins(0, dp(8), dp(9), 0);
        preview.addView(mClock, clockParams);
    }

    /** Shows the focused card: a program of a channel, or one of the buttons in the top row. */
    public void show(Video video) {
        if (video == null) {
            return;
        }

        Context context = getContext();
        TvModeService service = TvModeService.instance(context);
        mClock.setText(DateFormat.getTimeFormat(context).format(new Date()));

        // A card of the results line. A channel or program shows as itself, with its channel in the picture box.
        if (TvModeService.isResult(video)) {
            Video target = service.resultTarget(video);
            if (target != null) {
                show(target);
                return;
            }

            // A playlist the finder found: nothing to play here, OK opens it.
            TvModeService.PlaylistHit hit = service.resultPlaylist(video);
            mChannelChip.setText("PLAYLIST");
            mModeChip.setVisibility(GONE);
            mTitle.setText(video.getTitle());
            mBy.setMaxLines(1);
            String by = video.author != null ? video.author : "";
            String note = service.resultNote(video);
            String tag = hit != null && hit.uploader ? "The uploader's own list" : "";
            String line = by.isEmpty() ? note : note.isEmpty() ? by : by + "  •  " + note;
            mBy.setText(tag.isEmpty() ? line : tag + "  •  " + line);
            mProgressTrack.setVisibility(INVISIBLE);
            mOkKey.setVisibility(VISIBLE);
            mOkLabel.setVisibility(VISIBLE);
            mOkLabel.setText("Open the playlist");
            mHoldKey.setVisibility(VISIBLE);
            mHoldLabel.setVisibility(VISIBLE);
            mHoldLabel.setText("Make a channel, remove");
            mPreviewIcon.setVisibility(GONE);
            mPreviewBug.setVisibility(GONE);
            previewChannel(null);
            about(null);

            if (video.videoId != null && !video.videoId.equals(mShownId)) {
                mShownId = video.videoId;
                if (!(context instanceof Activity && ((Activity) context).isDestroyed()) && video.cardImageUrl != null) {
                    Glide.with(context).load(video.cardImageUrl).into(mPreview);
                }
            }
            return;
        }

        // A program of "Continue watching" or "My list": it belongs to no channel, so nothing plays in the picture box.
        if (TvModeService.isShelf(video)) {
            boolean started = video.percentWatched > 0;
            String note = service.savedNote(video);
            String by = video.author != null ? video.author : "";

            mChannelChip.setText(TvModeService.SHELF_RESUME.equals(video.tvChannelId) ? "CONTINUE WATCHING" : "MY LIST");
            mModeChip.setVisibility(GONE);
            mTitle.setText(video.getTitle());
            mBy.setMaxLines(1);
            mBy.setText(by.isEmpty() ? note : note.isEmpty() ? by : note + "  •  " + by);
            mProgressTrack.setVisibility(started ? VISIBLE : INVISIBLE);
            if (started) {
                android.view.ViewGroup.LayoutParams params = mProgress.getLayoutParams();
                params.width = Math.round(dp(260) * Math.min(100, video.percentWatched) / 100f);
                mProgress.setLayoutParams(params);
            }
            mOkKey.setVisibility(VISIBLE);
            mOkLabel.setVisibility(VISIBLE);
            mOkLabel.setText(started ? "Carry on watching" : "Watch");
            mHoldKey.setVisibility(VISIBLE);
            mHoldLabel.setVisibility(VISIBLE);
            mHoldLabel.setText("Remove, clear, info");
            mPreviewIcon.setVisibility(GONE);
            mPreviewBug.setVisibility(GONE);
            previewChannel(null);
            about(video);

            if (video.videoId != null && !video.videoId.equals(mShownId)) {
                mShownId = video.videoId;

                if (!(context instanceof Activity && ((Activity) context).isDestroyed())) {
                    Glide.with(context)
                            .load("https://i.ytimg.com/vi/" + video.videoId + "/maxresdefault.jpg")
                            .error(Glide.with(context).load(video.cardImageUrl))
                            .into(mPreview);
                }
            }
            return;
        }

        if (TvModeService.isAction(video)) {
            mShownId = video.tvChannelId;
            mChannelChip.setText("TV");
            mModeChip.setVisibility(GONE);
            mTitle.setText(video.title);
            mBy.setMaxLines(2);
            mBy.setText(describe(video.tvChannelId));
            mProgressTrack.setVisibility(INVISIBLE);
            mOkKey.setVisibility(VISIBLE);
            mOkLabel.setVisibility(VISIBLE);
            mOkLabel.setText(!TvModeService.ACTION_FAVOURITES.equals(video.tvChannelId) ? "Open" : service.isFavOnly() ? "Show all channels" : "Show favourites only");
            mHoldKey.setVisibility(GONE);
            mHoldLabel.setVisibility(GONE);
            mPreviewBug.setVisibility(GONE);
            Glide.with(context.getApplicationContext()).clear(mPreview);
            mPreview.setImageDrawable(null);
            mPreviewIcon.setImageResource(TvActionCardPresenter.iconOf(video));
            mPreviewIcon.setVisibility(VISIBLE);
            previewChannel(null);
            about(null);
            return;
        }

        TvChannel channel = service.findChannel(video);

        if (channel == null) {
            return;
        }

        previewChannel(channel);
        about(video);

        boolean live = service.isLive(channel);
        int index = channel.indexOf(video.videoId);
        boolean onNow = index == channel.pos;
        String number = String.valueOf(service.indexOf(channel) + 1);

        mChannelChip.setText("CH " + number + "   " + channel.name.toUpperCase());
        mModeChip.setText(live ? "LIVE" : "FREE");
        mModeChip.setVisibility(VISIBLE);
        mTitle.setText(video.getTitle());
        mHoldKey.setVisibility(VISIBLE);
        mHoldLabel.setVisibility(VISIBLE);
        mHoldLabel.setText("Channel options");
        mPreviewIcon.setVisibility(GONE);
        mPreviewBug.setText("CH " + number + "   " + channel.name);
        mPreviewBug.setVisibility(VISIBLE);

        String author = video.getAuthor();
        String by = author != null ? author : "";
        // An episode of a series says which one it is, so the order shows.
        String episode = service.episodeLabel(channel, video);
        if (episode != null) {
            com.liskovsoft.smartyoutubetv2.common.tvmode.TvChannel.Series series = channel.seriesOf(video.videoId);
            by = (series != null && !series.title.isEmpty() ? shortTitle(series.title) + "  •  " : "") + episode;
        } else if (video.badge != null && !video.badge.isEmpty()) {
            by += (by.isEmpty() ? "" : "  •  ") + video.badge;
        }

        String when;
        String ok;
        float percent = -1;

        if (onNow && live) {
            long duration = channel.durationOf(video);
            long offset = service.liveOffsetMs(channel);
            when = "On now until " + time(System.currentTimeMillis() + Math.max(0, duration - offset));
            ok = "Watch";
            percent = 100f * offset / duration;
        } else if (onNow) {
            boolean started = video.percentWatched > 0 && video.percentWatched < 100;
            when = started ? "Paused" : "Up first";
            ok = started ? "Resume" : "Watch";
            percent = Math.max(0, video.percentWatched);
        } else if (live) {
            when = index > channel.pos ? "Starts " + time(service.liveStartOf(channel, index)) : "Already played";
            ok = null;
        } else {
            when = "Coming up";
            ok = "Play this now";
        }

        // One line: the creator, the length, and when. The lines saved go to the description.
        mBy.setMaxLines(1);
        // The time first: a long creator name is what gets cut off at the end of the line.
        mBy.setText(by.isEmpty() ? when : when + "  •  " + by);
        mOkKey.setVisibility(ok != null ? VISIBLE : GONE);
        mOkLabel.setVisibility(ok != null ? VISIBLE : GONE);
        mOkLabel.setText(ok);

        mProgressTrack.setVisibility(percent >= 0 ? VISIBLE : INVISIBLE);
        if (percent >= 0) {
            android.view.ViewGroup.LayoutParams params = mProgress.getLayoutParams();
            params.width = Math.round(dp(260) * Math.min(100, percent) / 100f);
            mProgress.setLayoutParams(params);
        }

        if (video.videoId != null && !video.videoId.equals(mShownId)) {
            mShownId = video.videoId;

            if (context instanceof Activity && ((Activity) context).isDestroyed()) {
                return;
            }

            // The big picture when YouTube has one, the card's picture otherwise.
            Glide.with(context)
                    .load("https://i.ytimg.com/vi/" + video.videoId + "/maxresdefault.jpg")
                    .error(Glide.with(context).load(video.cardImageUrl))
                    .into(mPreview);
        }
    }

    /**
     * Plays the focused channel in the picture box. It starts after the focus has rested on a
     * channel for a moment, so moving down the list doesn't start a player on every line.
     */
    private void previewChannel(TvChannel channel) {
        String id = channel != null ? channel.id : null;

        if (id != null && id.equals(mPreviewChannelId)) {
            checkPreview(); // the same channel: make sure it is still playing what the channel has on
            return;
        }

        stopPreview();
        mPreviewChannelId = id;
        mPreviewChannel = channel;
        mPreviewTries = 0;
        mPreviewStartedMs = System.currentTimeMillis(); // the check waits for the start that is on its way

        if (channel != null && mPreviewAllowed && !mPreviewPaused) {
            postDelayed(mStartPreview, PREVIEW_DELAY_MS);
        }
    }

    /**
     * Called every few seconds while a channel stays focused. Starts the preview again when it
     * isn't running, and moves it on when the channel has moved to its next program.<br/>
     * Without this a preview that failed to start once stayed dark: nothing asked again. That
     * was the still picture after coming back from a menu or from the full screen player, where
     * the small player is refused while the big one is still closing.
     */
    private void checkPreview() {
        if (mPreviewChannel == null || !mPreviewAllowed || mPreviewPaused || !isAttachedToWindow()
                || System.currentTimeMillis() - mPreviewStartedMs < PREVIEW_CHECK_MS) {
            return;
        }

        boolean running = mPreviewPlayer != null && mPreviewPlayer.isEngineInitialized();
        TvModeService service = TvModeService.instance(getContext());
        Video onNow = service.isLive(mPreviewChannel) ? service.previewCopy(mPreviewChannel) : mPreviewChannel.onNow();
        boolean movedOn = onNow != null && onNow.videoId != null && !onNow.videoId.equals(mPreviewVideoId);

        if (running && !movedOn) {
            mPreviewTries = 0;
            return;
        }

        // A video that can't be played must not be asked for again and again.
        if (!movedOn && mPreviewTries >= PREVIEW_MAX_TRIES) {
            return;
        }

        stopPreview();
        startPreview();
    }

    private void startPreview() {
        if (mPreviewChannel == null || !mPreviewAllowed || mPreviewPaused || !isAttachedToWindow()) {
            return;
        }

        final TvChannel channel = mPreviewChannel;
        Video copy = TvModeService.instance(getContext()).previewCopy(channel);

        if (copy == null) {
            return;
        }

        mPreviewTries = copy.videoId != null && copy.videoId.equals(mPreviewVideoId) ? mPreviewTries + 1 : 1;
        mPreviewVideoId = copy.videoId;
        mPreviewStartedMs = System.currentTimeMillis();

        mPreviewPlayer = new EmbedPlayerView(getContext()) {
            /** The player closes the small player itself when a video ends. That is the cue for the channel's next program. */
            @Override
            public void finishReally() {
                boolean ended = false;

                try {
                    if (isEngineInitialized()) {
                        long duration = getDurationMs();
                        ended = duration > 0 && duration - getPositionMs() < 3_000;
                    }
                } catch (Throwable e) {
                    // the player was already taken apart: not an end
                }

                super.finishReally();

                if (ended) {
                    TvGuideHeroView.this.post(() -> onPreviewEnded(channel, this));
                }
            }
        };
        mPreviewPlayer.setQuality(EmbedPlayerView.QUALITY_NORMAL);
        mPreviewPlayer.setUseController(false);
        mPreviewPlayer.setMute(false); // sound says a channel is playing, so it has no time limit either
        mPreviewPlayer.setFocusable(false);
        // Over the still picture, under the channel name and the clock.
        mPreviewBox.addView(mPreviewPlayer, 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mPreviewPlayer.openVideo(copy);
        // Watching here is watching: a program seen through in the preview is as watched as one seen full screen.
        TvModeService.instance(getContext()).watching(channel, copy.videoId, copy.pendingPosMs);
        // Once this one has had a few seconds to start: the channels above and below, for the next Up or Down.
        removeCallbacks(mPreloadAround);
        postDelayed(mPreloadAround, PRELOAD_DELAY_MS);
    }

    private static final long PRELOAD_DELAY_MS = 4_000;
    private final Runnable mPreloadAround = () -> {
        if (mPreviewChannel != null && mPreviewPlayer != null && isAttachedToWindow()) {
            TvModeService.instance(getContext()).preloadAround(mPreviewChannel);
        }
    };

    /** The preview played a program to its end: a Free mode channel moves to its next one, and the preview follows. */
    private void onPreviewEnded(TvChannel channel, EmbedPlayerView player) {
        if (player != mPreviewPlayer || channel != mPreviewChannel) {
            return; // the focus has moved on since
        }

        TvModeService.instance(getContext()).previewEnded(channel, mPreviewVideoId);
        stopPreview();
        startPreview();

        if (mOnChannelMoved != null) {
            mOnChannelMoved.run();
        }
    }

    /** Told when the preview moved a channel to its next program, so the grid below can follow. */
    public void setOnChannelMoved(Runnable onChannelMoved) {
        mOnChannelMoved = onChannelMoved;
    }

    private void stopPreview() {
        removeCallbacks(mStartPreview);
        removeCallbacks(mPreloadAround);
        TvModeService.instance(getContext()).cancelPreload();

        if (mPreviewPlayer != null) {
            mPreviewPlayer.finish();
            mPreviewBox.removeView(mPreviewPlayer);
            mPreviewPlayer = null;
            TvModeService.instance(getContext()).stopWatching();
        }
    }

    /** The guide left the screen (false) or is back (true). The preview must not play behind the real player. */
    public void setPreviewAllowed(boolean allowed) {
        mPreviewAllowed = allowed;

        if (!allowed) {
            stopPreview();
            mPreviewChannelId = null;
        }
    }

    /** The side menu opened (true) or closed (false). The preview is silent while the menu is open. */
    public void setPreviewPaused(boolean paused) {
        if (mPreviewPaused == paused) {
            return;
        }

        mPreviewPaused = paused;

        if (paused) {
            stopPreview();
        } else if (mPreviewChannel != null && mPreviewAllowed) {
            mPreviewTries = 0;
            removeCallbacks(mStartPreview);
            postDelayed(mStartPreview, PREVIEW_DELAY_MS);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPreview();
    }

    /** A playlist's name cut at its first separator: uploaders pile keywords on after it. */
    private static String shortTitle(String title) {
        int cut = title.length();
        for (String separator : new String[] {" | ", " - ", " || ", " ("}) {
            int at = title.indexOf(separator);
            if (at > 3 && at < cut) {
                cut = at;
            }
        }
        String text = title.substring(0, cut).trim();
        return text.length() > 32 ? text.substring(0, 32) : text;
    }

    private static String describe(String action) {
        if (TvModeService.ACTION_SETTINGS.equals(action)) {
            return "Channel mode, remote, sleep timer, history, shuffle all.";
        } else if (TvModeService.ACTION_GENRE.equals(action)) {
            return "Show only one kind of program: movies, series, songs...\nCH+ and CH- stay within them.";
        } else if (TvModeService.ACTION_LANGUAGE.equals(action)) {
            return "Show only the channels in one language.\nCH+ and CH- stay within them.";
        } else if (TvModeService.ACTION_FAVOURITES.equals(action)) {
            return "Show only your favourite channels.\nHold OK on a channel to make it one.";
        } else if (TvModeService.ACTION_SEARCH.equals(action)) {
            return "Find a channel by name, or a program by its title,\namong what every channel has on and coming up.";
        }

        return "Pick a ready-made topic, or type your own.";
    }

    private String time(long timeMs) {
        return DateFormat.getTimeFormat(getContext()).format(new Date(timeMs));
    }

    private TextView key(String label) {
        TextView view = text(11.5f, 0xFFFFFFFF, 1);
        view.setText(label);
        view.setTypeface(null, Typeface.BOLD);
        view.setPadding(dp(7), dp(2), dp(7), dp(2));
        view.setBackground(rounded(0x38FFFFFF, 0, dp(4)));
        return view;
    }

    private LinearLayout.LayoutParams gap(int leftDp, int rightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(leftDp);
        params.rightMargin = dp(rightDp);
        return params;
    }

    private TextView text(float sizeDp, int color, int lines) {
        TextView view = new TextView(getContext());
        view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        view.setTextColor(color);
        view.setMaxLines(lines);
        view.setEllipsize(TruncateAt.END);
        view.setIncludeFontPadding(false);
        return view;
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(radius);
        drawable.setColor(fill);
        if (stroke != 0) {
            drawable.setStroke(dp(1.5f), stroke);
        }
        return drawable;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }
}

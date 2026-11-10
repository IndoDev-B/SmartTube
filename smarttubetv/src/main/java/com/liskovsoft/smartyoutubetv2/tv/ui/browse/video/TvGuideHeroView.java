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
    private final Runnable mStartPreview = this::startPreview;

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

        mTitle = text(25f, 0xFFFFFFFF, 3);
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

        if (TvModeService.isAction(video)) {
            mShownId = video.tvChannelId;
            mChannelChip.setText("TV");
            mModeChip.setVisibility(GONE);
            mTitle.setText(video.title);
            mBy.setText(describe(video.tvChannelId));
            mProgressTrack.setVisibility(INVISIBLE);
            mOkKey.setVisibility(VISIBLE);
            mOkLabel.setVisibility(VISIBLE);
            mOkLabel.setText("Open");
            mHoldKey.setVisibility(GONE);
            mHoldLabel.setVisibility(GONE);
            mPreviewBug.setVisibility(GONE);
            Glide.with(context.getApplicationContext()).clear(mPreview);
            mPreview.setImageDrawable(null);
            mPreviewIcon.setImageResource(TvActionCardPresenter.iconOf(video));
            mPreviewIcon.setVisibility(VISIBLE);
            previewChannel(null);
            return;
        }

        TvChannel channel = service.findChannel(video);

        if (channel == null) {
            return;
        }

        previewChannel(channel);

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
        mPreviewIcon.setVisibility(GONE);
        mPreviewBug.setText("CH " + number + "   " + channel.name);
        mPreviewBug.setVisibility(VISIBLE);

        String author = video.getAuthor();
        String by = author != null ? author : "";
        if (video.badge != null && !video.badge.isEmpty()) {
            by += (by.isEmpty() ? "" : "  •  ") + video.badge;
        }

        String when;
        String ok;
        float percent = -1;

        if (onNow && live) {
            long duration = channel.durationOf(video);
            long offset = service.liveOffsetMs(channel);
            when = "On now  •  until " + time(System.currentTimeMillis() + Math.max(0, duration - offset));
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

        mBy.setText(by + "\n" + when);
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
            return; // another program of the same channel: the preview keeps playing
        }

        stopPreview();
        mPreviewChannelId = id;
        mPreviewChannel = channel;

        if (channel != null && mPreviewAllowed) {
            postDelayed(mStartPreview, PREVIEW_DELAY_MS);
        }
    }

    private void startPreview() {
        if (mPreviewChannel == null || !mPreviewAllowed || !isAttachedToWindow()) {
            return;
        }

        Video copy = TvModeService.instance(getContext()).previewCopy(mPreviewChannel);

        if (copy == null) {
            return;
        }

        mPreviewPlayer = new EmbedPlayerView(getContext());
        mPreviewPlayer.setQuality(EmbedPlayerView.QUALITY_NORMAL);
        mPreviewPlayer.setUseController(false);
        mPreviewPlayer.setMute(false); // sound says a channel is playing, so it has no time limit either
        mPreviewPlayer.setFocusable(false);
        // Over the still picture, under the channel name and the clock.
        mPreviewBox.addView(mPreviewPlayer, 1, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        mPreviewPlayer.openVideo(copy);
    }

    private void stopPreview() {
        removeCallbacks(mStartPreview);

        if (mPreviewPlayer != null) {
            mPreviewPlayer.finish();
            mPreviewBox.removeView(mPreviewPlayer);
            mPreviewPlayer = null;
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

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPreview();
    }

    private static String describe(String action) {
        if (TvModeService.ACTION_SETTINGS.equals(action)) {
            return "Channel mode, remote, sleep timer, history.";
        } else if (TvModeService.ACTION_SHUFFLE_ALL.equals(action)) {
            return "New lineups for every channel.\nYour channels and settings stay.";
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

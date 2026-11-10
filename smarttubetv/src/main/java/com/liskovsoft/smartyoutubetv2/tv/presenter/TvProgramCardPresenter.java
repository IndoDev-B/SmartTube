package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build.VERSION;
import android.text.TextUtils.TruncateAt;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.leanback.widget.Presenter;

import com.bumptech.glide.Glide;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvChannel;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvModeService;
import com.liskovsoft.smartyoutubetv2.tv.presenter.base.LongClickPresenter;

import java.util.Date;

/**
 * A program in the TV guide: picture on the left, title and creator on the right.<br/>
 * Wide and short, so several channels fit on screen and each reads at a glance from the couch.
 */
public class TvProgramCardPresenter extends LongClickPresenter {
    private static final int RED = 0xFFE62117;
    private static final int CARD = 0xDB1C1D1F;
    private static final int CARD_FOCUSED = 0xFFF1F1F1;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFBDBDBD;
    private static final int TEXT_FOCUSED = 0xFF111111;
    private static final int TEXT_DIM_FOCUSED = 0xFF333333;

    /** Built in code so every size is in one place. Sizes are dp at 1080p, where 1dp is 2 TV pixels. */
    static class ProgramView extends FrameLayout {
        final ImageView image;
        final TextView badge;
        final TextView title;
        final TextView meta;
        final View nowEdge;
        final View progressTrack;
        final View progress;
        final GradientDrawable background = new GradientDrawable();
        boolean past;

        ProgramView(Context context) {
            super(context);

            int height = dp(58);
            setLayoutParams(new ViewGroup.LayoutParams(dp(264), height));
            setFocusable(true);
            setFocusableInTouchMode(true);
            background.setCornerRadius(dp(8));
            background.setColor(CARD);
            setBackground(background);
            if (VERSION.SDK_INT >= 21) {
                setClipToOutline(true);
            }

            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            addView(line, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            FrameLayout thumb = new FrameLayout(context);
            line.addView(thumb, new LinearLayout.LayoutParams(dp(103), height));

            image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0xFF222222);
            thumb.addView(image, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

            badge = text(context, 10.5f, TEXT, 1);
            badge.setPadding(dp(4), 0, dp(4), 0);
            LayoutParams badgeParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
            badgeParams.setMargins(0, 0, dp(4), dp(5));
            thumb.addView(badge, badgeParams);

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(10), dp(5), dp(10), 0);
            line.addView(texts, new LinearLayout.LayoutParams(0, height, 1));

            title = text(context, 13f, TEXT, 2);
            texts.addView(title);

            meta = text(context, 11f, TEXT_DIM, 1);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            metaParams.topMargin = dp(1);
            texts.addView(meta, metaParams);

            nowEdge = new View(context);
            nowEdge.setBackgroundColor(RED);
            addView(nowEdge, new LayoutParams(dp(4), LayoutParams.MATCH_PARENT, Gravity.START));

            progressTrack = new View(context);
            progressTrack.setBackgroundColor(0x40FFFFFF);
            addView(progressTrack, new LayoutParams(LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM));

            progress = new View(context);
            progress.setBackgroundColor(RED);
            addView(progress, new LayoutParams(0, dp(3), Gravity.BOTTOM | Gravity.START));
        }

        int dp(float value) {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
        }

        private static TextView text(Context context, float sizeDp, int color, int lines) {
            TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp);
            view.setTextColor(color);
            view.setMaxLines(lines);
            view.setEllipsize(TruncateAt.END);
            view.setIncludeFontPadding(false);
            return view;
        }

        @Override
        protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
            applyFocus(gainFocus);
        }

        void applyFocus(boolean focused) {
            background.setColor(focused ? CARD_FOCUSED : CARD);
            title.setTextColor(focused ? TEXT_FOCUSED : TEXT);
            meta.setTextColor(focused ? TEXT_DIM_FOCUSED : TEXT_DIM);
            setAlpha(past && !focused ? 0.5f : 1f);
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        return new ViewHolder(new ProgramView(parent.getContext()));
    }

    @Override
    public void onBindViewHolder(Presenter.ViewHolder viewHolder, Object item) {
        super.onBindViewHolder(viewHolder, item);

        Video video = (Video) item;
        ProgramView view = (ProgramView) viewHolder.view;
        Context context = view.getContext();
        TvModeService service = TvModeService.instance(context);
        TvChannel channel = service.findChannel(video);
        boolean live = channel != null && service.isLive(channel);
        boolean onNow = channel != null && channel.onNow() != null && video.videoId != null && video.videoId.equals(channel.onNow().videoId);
        String author = video.getAuthor();

        view.title.setText(video.getTitle());
        view.nowEdge.setVisibility(onNow ? View.VISIBLE : View.GONE);
        view.progressTrack.setVisibility(onNow ? View.VISIBLE : View.GONE);
        view.progress.setVisibility(onNow ? View.VISIBLE : View.GONE);
        int place = channel != null ? channel.indexOf(video.videoId) : -1;
        boolean played = channel != null && place != -1 && place < channel.pos;
        view.past = played || (live && !onNow); // dimmed: already played, or on Live TV not on yet

        String badge = video.badge;
        int badgeColor = 0xD9000000;
        String meta = author != null ? author : "";

        if (onNow) {
            float percent = live && channel.durationOf(video) > 0 ? 100f * service.liveOffsetMs(channel) / channel.durationOf(video) : video.percentWatched;
            ViewGroup.LayoutParams params = view.progress.getLayoutParams();
            params.width = Math.round(view.dp(264) * Math.max(0, Math.min(100, percent)) / 100f);
            view.progress.setLayoutParams(params);

            if (live) {
                badge = "LIVE";
                badgeColor = RED;
            } else if (video.percentWatched > 0) {
                meta = join(meta, "paused");
            }
        } else if (played) {
            meta = join("Watched", meta);
        } else if (live && channel != null) {
            int index = channel.indexOf(video.videoId);
            if (index > channel.pos) {
                meta = join(meta, badge);
                badge = DateFormat.getTimeFormat(context).format(new Date(service.liveStartOf(channel, index)));
            }
        }

        view.meta.setText(meta);
        view.badge.setText(badge);
        view.badge.setVisibility(badge != null && !badge.isEmpty() ? View.VISIBLE : View.GONE);
        view.badge.setTypeface(null, onNow && live ? Typeface.BOLD : Typeface.NORMAL);
        GradientDrawable badgeBackground = new GradientDrawable();
        badgeBackground.setCornerRadius(view.dp(3));
        badgeBackground.setColor(badgeColor);
        view.badge.setBackground(badgeBackground);
        view.applyFocus(view.isFocused());

        if (context instanceof Activity && ((Activity) context).isDestroyed()) {
            return;
        }

        Glide.with(context).load(video.cardImageUrl).into(view.image);
    }

    private static String join(String first, String second) {
        if (second == null || second.isEmpty()) {
            return first;
        }

        return first == null || first.isEmpty() ? second : first + " • " + second;
    }

    @Override
    public void onUnbindViewHolder(Presenter.ViewHolder viewHolder) {
        super.onUnbindViewHolder(viewHolder);

        ProgramView view = (ProgramView) viewHolder.view;
        Glide.with(view.getContext().getApplicationContext()).clear(view.image);
        view.image.setImageDrawable(null);
    }
}

package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.leanback.widget.Presenter;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

/**
 * A button in the top row of the TV guide: TV settings, Shuffle all, New channel.
 */
public class TvActionCardPresenter extends Presenter {
    private static final int PILL = 0x1FFFFFFF;
    private static final int PILL_FOCUSED = 0xFFF1F1F1;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_FOCUSED = 0xFF111111;

    static class ActionView extends LinearLayout {
        final ImageView icon;
        final TextView label;
        final GradientDrawable background = new GradientDrawable();

        ActionView(Context context) {
            super(context);

            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)));
            setPadding(dp(14), 0, dp(16), 0);
            setFocusable(true);
            setFocusableInTouchMode(true);
            background.setCornerRadius(dp(16));
            background.setColor(PILL);
            setBackground(background);

            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LayoutParams iconParams = new LayoutParams(dp(17), dp(17));
            iconParams.rightMargin = dp(8);
            addView(icon, iconParams);

            label = new TextView(context);
            label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13.5f);
            label.setTextColor(TEXT);
            label.setMaxLines(1);
            label.setIncludeFontPadding(false);
            addView(label, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }

        int dp(float value) {
            return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
        }

        @Override
        protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
            applyFocus(gainFocus);
        }

        void applyFocus(boolean focused) {
            background.setColor(focused ? PILL_FOCUSED : PILL);
            label.setTextColor(focused ? TEXT_FOCUSED : TEXT);
            icon.setColorFilter(focused ? TEXT_FOCUSED : TEXT);
        }
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent) {
        return new ViewHolder(new ActionView(parent.getContext()));
    }

    @Override
    public void onBindViewHolder(ViewHolder viewHolder, Object item) {
        Video action = (Video) item;
        ActionView view = (ActionView) viewHolder.view;

        view.label.setText(action.title);
        view.icon.setImageResource(iconOf(action));
        view.applyFocus(view.isFocused());
    }

    /** The icon is carried in the card's image address: android.resource://package/id */
    public static int iconOf(Video action) {
        try {
            String url = action.cardImageUrl;
            return Integer.parseInt(url.substring(url.lastIndexOf('/') + 1));
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public void onUnbindViewHolder(ViewHolder viewHolder) {
        // NOP
    }
}

package com.liskovsoft.smartyoutubetv2.tv.ui.playback.other;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils.TruncateAt;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.tvmode.TvModeController;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvModeOverlay;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvModeService;

import java.util.Random;

/**
 * What TV mode draws over the playing video: the channel banner, TV static while the next video
 * loads, retro scanlines, and the dimmed screen after the sleep timer.<br/>
 * Never takes focus or touches, so the player works exactly as before underneath it.
 */
public class TvModeOverlayView extends FrameLayout implements TvModeOverlay {
    private static final int RED = 0xFFE62117;
    private static final long BANNER_MS = 5_000;
    private final StaticView mStatic;
    private final View mBlack;
    private final View mScanlines;
    private final FrameLayout mBanner;
    private final TextView mNumber;
    private final TextView mName;
    private final TextView mMode;
    private final TextView mTitle;
    private final TextView mSubtitle;
    private final TextView mNextLabel;
    private final TextView mNextTitle;
    private final TextView mSleep;
    private final Runnable mHideBanner = this::hideBanner;

    /** Adds the overlay on top of the player screen and hands it to TV mode. */
    public static void attach(ViewGroup playerRoot) {
        TvModeOverlayView overlay = new TvModeOverlayView(playerRoot.getContext());
        playerRoot.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TvModeController.setOverlay(overlay);
    }

    public TvModeOverlayView(Context context) {
        super(context);

        setFocusable(false);
        setClickable(false);

        mBlack = new View(context);
        mBlack.setBackgroundColor(0xFF000000);
        mBlack.setVisibility(GONE);
        addView(mBlack, matchParent());

        mStatic = new StaticView(context);
        mStatic.setVisibility(GONE);
        addView(mStatic, matchParent());

        mScanlines = new ScanlinesView(context);
        mScanlines.setVisibility(GONE);
        addView(mScanlines, matchParent());

        // Banner: channel number, name, what's on, what's next
        mBanner = new FrameLayout(context);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0x00000000, 0xCC000000, 0xF2000000});
        mBanner.setBackground(shade);
        mBanner.setVisibility(GONE);
        addView(mBanner, new LayoutParams(LayoutParams.MATCH_PARENT, dp(190), Gravity.BOTTOM));

        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(rounded(RED, dp(9)));
        LayoutParams tileParams = new LayoutParams(dp(90), dp(90), Gravity.BOTTOM | Gravity.START);
        tileParams.setMargins(dp(55), 0, 0, dp(45));
        mBanner.addView(tile, tileParams);

        TextView ch = text(13f, 0xFFFFFFFF, 1);
        ch.setText("CH");
        tile.addView(ch, wrap());
        mNumber = text(46f, 0xFFFFFFFF, 1);
        mNumber.setTypeface(null, Typeface.BOLD);
        tile.addView(mNumber, wrap());

        LinearLayout middle = new LinearLayout(context);
        middle.setOrientation(LinearLayout.VERTICAL);
        LayoutParams middleParams = new LayoutParams(dp(480), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        middleParams.setMargins(dp(170), 0, 0, dp(45));
        mBanner.addView(middle, middleParams);

        LinearLayout nameLine = new LinearLayout(context);
        nameLine.setOrientation(LinearLayout.HORIZONTAL);
        nameLine.setGravity(Gravity.CENTER_VERTICAL);
        middle.addView(nameLine);
        mName = text(16f, 0xFFFF8A80, 1);
        mName.setTypeface(null, Typeface.BOLD);
        mName.setAllCaps(true);
        nameLine.addView(mName, wrap());
        mMode = text(11f, 0xFFFFFFFF, 1);
        mMode.setTypeface(null, Typeface.BOLD);
        mMode.setPadding(dp(5), dp(1), dp(5), dp(1));
        GradientDrawable modeBox = rounded(0, dp(3));
        modeBox.setStroke(dp(1.5f), 0xCCFFFFFF);
        mMode.setBackground(modeBox);
        LinearLayout.LayoutParams modeParams = wrap();
        modeParams.leftMargin = dp(9);
        nameLine.addView(mMode, modeParams);

        mTitle = text(27f, 0xFFFFFFFF, 1);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(4);
        middle.addView(mTitle, titleParams);

        mSubtitle = text(15.5f, 0xFFDDDDDD, 1);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(5);
        middle.addView(mSubtitle, subtitleParams);

        LinearLayout right = new LinearLayout(context);
        right.setOrientation(LinearLayout.VERTICAL);
        LayoutParams rightParams = new LayoutParams(dp(235), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        rightParams.setMargins(0, 0, dp(55), dp(45));
        mBanner.addView(right, rightParams);

        mNextLabel = text(14f, 0xFFCFCFCF, 1);
        right.addView(mNextLabel);
        mNextTitle = text(16f, 0xFFFFFFFF, 2);
        LinearLayout.LayoutParams nextParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        nextParams.topMargin = dp(3);
        right.addView(mNextTitle, nextParams);

        mSleep = text(26f, 0xFFBBBBBB, 2);
        mSleep.setGravity(Gravity.CENTER);
        mSleep.setBackgroundColor(0xE0000000);
        mSleep.setVisibility(GONE);
        addView(mSleep, matchParent());
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(mHideBanner);
    }

    @Override
    public void showBanner(String number, String name, String mode, String title, String subtitle, String nextLabel, String nextTitle) {
        mNumber.setText(number);
        mName.setText(name);
        mMode.setText(mode);
        mTitle.setText(title);
        mSubtitle.setText(subtitle);
        mNextLabel.setText(nextLabel);
        mNextTitle.setText(nextTitle);
        mBanner.setVisibility(VISIBLE);
        removeCallbacks(mHideBanner);
        postDelayed(mHideBanner, BANNER_MS);
    }

    @Override
    public void hideBanner() {
        removeCallbacks(mHideBanner);
        mBanner.setVisibility(GONE);
    }

    @Override
    public void showSwitching(String effect) {
        boolean noise = TvModeService.FX_STATIC.equals(effect);
        mStatic.setVisibility(noise ? VISIBLE : GONE);
        mBlack.setVisibility(noise ? GONE : VISIBLE);
    }

    @Override
    public void hideSwitching() {
        mStatic.setVisibility(GONE);
        mBlack.setVisibility(GONE);
    }

    @Override
    public void setScanlines(boolean enabled) {
        mScanlines.setVisibility(enabled ? VISIBLE : GONE);
    }

    @Override
    public void showSleep(String message) {
        mSleep.setText(message);
        mSleep.setVisibility(VISIBLE);
    }

    @Override
    public void hideSleep() {
        mSleep.setVisibility(GONE);
    }

    private LayoutParams matchParent() {
        return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
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

    private GradientDrawable rounded(int fill, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(radius);
        drawable.setColor(fill);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    /** Old TV snow: a small grey noise picture, redrawn every frame and stretched over the screen. */
    private static class StaticView extends View {
        private static final int W = 160;
        private static final int H = 90;
        private final Bitmap mNoise = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        private final int[] mPixels = new int[W * H];
        private final Random mRandom = new Random();
        private final Paint mPaint = new Paint();
        private final Rect mTarget = new Rect();

        StaticView(Context context) {
            super(context);
            mPaint.setFilterBitmap(false); // keep the grain sharp
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int band = mRandom.nextInt(H); // one brighter band rolling through, like a weak signal

            for (int i = 0; i < mPixels.length; i++) {
                int row = i / W;
                int grey = mRandom.nextInt(Math.abs(row - band) < 4 ? 256 : 200);
                mPixels[i] = 0xFF000000 | (grey << 16) | (grey << 8) | grey;
            }

            mNoise.setPixels(mPixels, 0, W, 0, 0, W, H);
            mTarget.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(mNoise, null, mTarget, mPaint);

            if (getVisibility() == VISIBLE) {
                postInvalidateOnAnimation();
            }
        }
    }

    /** Thin dark lines across the picture, like a tube TV. */
    private static class ScanlinesView extends View {
        private final Paint mPaint = new Paint();

        ScanlinesView(Context context) {
            super(context);

            Bitmap tile = Bitmap.createBitmap(1, 4, Bitmap.Config.ARGB_8888);
            tile.setPixel(0, 0, 0x55000000);
            tile.setPixel(0, 1, 0x22000000);
            mPaint.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
        }
    }
}

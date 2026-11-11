package com.liskovsoft.smartyoutubetv2.tv.ui.playback.other;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvModeController;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.previewtimebar.GlideThumbnailTransformation;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import io.reactivex.disposables.Disposable;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvModeOverlay;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvModeService;

import java.util.Random;

/**
 * What TV mode draws over the playing video: the channel banner, TV static while the next video
 * loads, retro scanlines, and the dimmed screen after the sleep timer.<br/>
 * Never takes focus or touches, so the player works exactly as before underneath it.
 */
public class TvModeOverlayView extends FrameLayout implements TvModeOverlay {
    private static final String TAG = TvModeOverlayView.class.getSimpleName();
    private static final int RED = 0xFFE62117;
    private static final long BANNER_MS = 5_000;
    private final StaticView mStatic;
    private final VhsView mVhs;
    /** The video the VHS preview is for. A frame that arrives for another one is dropped. */
    private String mPreviewId;
    private CustomTarget<Bitmap> mFrameTarget;
    private CustomTarget<Bitmap> mFallbackTarget;
    private Disposable mStoryboardAction;
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
    private final IconButton mRandomButton;
    private final IconButton mListButton;
    private final IconButton mSettingsButton;
    /** The three buttons from left to right, the way Left and Right move through them. */
    private final IconButton[] mTopButtons;
    /** Says what the focused button does: the buttons themselves are only icons. */
    private final TextView mTip;
    private static final int TOP_BUTTON_DP = 36;
    /** The control the focus came from, to hand it back on Down. */
    private java.lang.ref.WeakReference<View> mReturnFocus = new java.lang.ref.WeakReference<>(null);
    /**
     * The cursor is resting on the top buttons. They then stay on screen by themselves, also after
     * the player's controls fade out, so Random can be pressed again and again.
     */
    private boolean mTopHeld;
    /** The top buttons go away by themselves when no button was pressed on them for this long. */
    private static final long TOP_BUTTONS_MS = 8_000;
    private final Runnable mTopTimeout = this::leaveTopButtons;

    private void restartTopTimeout() {
        removeCallbacks(mTopTimeout);
        postDelayed(mTopTimeout, TOP_BUTTONS_MS);
    }

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

        mVhs = new VhsView(context);
        mVhs.setVisibility(GONE);
        addView(mVhs, matchParent());

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

        // Top corners: Random on the left, TV settings on the right. Up from the controls reaches them.
        // Next to TV settings: put what is playing on My list, or take it off.
        mRandomButton = topButton(TvModeController.TOP_BUTTON_RANDOM, "Play a random program", Gravity.START, 55);
        mListButton = topButton(TvModeController.TOP_BUTTON_MYLIST, "Save for later", Gravity.END, 55 + TOP_BUTTON_DP + 10);
        mSettingsButton = topButton(TvModeController.TOP_BUTTON_SETTINGS, "TV settings", Gravity.END, 55);
        mTopButtons = new IconButton[] {mRandomButton, mListButton, mSettingsButton};

        mTip = text(13f, 0xFFFFFFFF, 1);
        mTip.setPadding(dp(8), dp(3), dp(8), dp(3));
        mTip.setBackground(rounded(0xE6000000, dp(4)));
        mTip.setVisibility(GONE);
        addView(mTip, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));

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

    /** A round icon button in a top corner. marginDp = how far it sits from that side's edge. */
    private IconButton topButton(final int which, String tip, int gravity, int marginDp) {
        final IconButton button = new IconButton(getContext(), which, tip, gravity, marginDp);
        button.setFocusable(true);
        button.setFocusableInTouchMode(true);
        button.setVisibility(GONE);
        LayoutParams params = new LayoutParams(dp(TOP_BUTTON_DP), dp(TOP_BUTTON_DP), Gravity.TOP | gravity);
        params.setMargins(gravity == Gravity.START ? dp(marginDp) : 0, dp(30), gravity == Gravity.END ? dp(marginDp) : 0, 0);
        addView(button, params);
        button.setOnFocusChangeListener((view, focused) -> {
            button.invalidate();
            if (focused) {
                showTip(button);
            } else if (mTipFor == button) {
                hideTip();
            }
        });
        button.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) {
                // The matching key-up must not reach anything else either.
                return keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER || keyCode == android.view.KeyEvent.KEYCODE_ENTER
                        || keyCode == android.view.KeyEvent.KEYCODE_BACK;
            }

            restartTopTimeout();

            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER:
                    if (event.getRepeatCount() == 0) {
                        TvModeController.onTopButton(which);
                    }
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                    int to = java.util.Arrays.asList(mTopButtons).indexOf(button)
                            + (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    if (to >= 0 && to < mTopButtons.length) {
                        mTopButtons[to].requestFocus();
                    }
                    return true;
                case android.view.KeyEvent.KEYCODE_DPAD_UP:
                    return true; // nothing above
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                case android.view.KeyEvent.KEYCODE_BACK:
                    leaveTopButtons();
                    return true;
            }

            return false;
        });
        return button;
    }

    /** Down or Back on a top button: the cursor goes back to the player. */
    private void leaveTopButtons() {
        removeCallbacks(mTopTimeout);
        mTopHeld = false;
        View back = mReturnFocus.get();
        boolean controlsUp = TvModeController.isControlsUp();

        if (controlsUp) {
            if (back != null && back.isShown()) {
                back.requestFocus(); // back to the control the cursor came from
            }
        } else {
            // The controls have faded out: only the video is left. The player keeps the cursor on
            // its seek bar while they are hidden, and the first key press then brings the controls
            // up with the cursor on Pause. Leaving it on any other control breaks that.
            View seekBar = getRootView().findViewById(com.liskovsoft.smartyoutubetv2.tv.R.id.playback_progress);
            if (seekBar != null) {
                seekBar.requestFocus();
            }
            setTopButtonsVisible(false);
            com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.playerglue.tooltips.TooltipCompatHandler.hideAll();
        }
    }

    private void setTopButtonsVisible(boolean visible) {
        for (IconButton button : mTopButtons) {
            button.setVisibility(visible ? VISIBLE : GONE);
        }

        if (!visible) {
            hideTip();
        }
    }

    private IconButton mTipFor;

    /** The focused button's name, right under it. */
    private void showTip(IconButton button) {
        mTipFor = button;
        mTip.setText(button.tip);
        LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP | button.gravity);
        params.setMargins(button.gravity == Gravity.START ? dp(button.marginDp) : 0, dp(30 + TOP_BUTTON_DP + 6),
                button.gravity == Gravity.END ? dp(button.marginDp) : 0, 0);
        mTip.setLayoutParams(params);
        mTip.setVisibility(VISIBLE);
    }

    private void hideTip() {
        mTipFor = null;
        mTip.setVisibility(GONE);
    }

    private boolean isTopButton(View view) {
        return view == mRandomButton || view == mListButton || view == mSettingsButton;
    }

    @Override
    public void setMyListState(boolean listed) {
        mListButton.on = listed;
        mListButton.tip = listed ? "Take off My list" : "Save for later";
        mListButton.invalidate();

        if (mTipFor == mListButton) {
            mTip.setText(mListButton.tip);
        }
    }

    /**
     * One of the top buttons: a dark disc with a drawn icon, white with a dark icon when focused.
     * Drawn here so the icons are sharp at any size and need no picture files.
     */
    private static class IconButton extends View {
        final int which;
        final int gravity;
        final int marginDp;
        String tip;
        /** My list only: what is playing is on the list. */
        boolean on;
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mBox = new RectF();

        IconButton(Context context, int which, String tip, int gravity, int marginDp) {
            super(context);
            this.which = which;
            this.tip = tip;
            this.gravity = gravity;
            this.marginDp = marginDp;
            mStroke.setStyle(Paint.Style.STROKE);
            mStroke.setStrokeCap(Paint.Cap.ROUND);
            mStroke.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = getWidth() * 0.24f; // half the icon's size
            boolean focused = isFocused();
            int ink = focused ? 0xFF111111 : 0xFFFFFFFF;

            mFill.setColor(focused ? 0xFFF1F1F1 : 0x99000000);
            canvas.drawCircle(cx, cy, getWidth() / 2f, mFill);
            mFill.setColor(ink);
            mStroke.setColor(ink);
            mStroke.setStrokeWidth(getWidth() * 0.06f);

            if (which == TvModeController.TOP_BUTTON_RANDOM) { // a die showing three
                mBox.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawRoundRect(mBox, r * 0.35f, r * 0.35f, mStroke);
                float dot = r * 0.2f;
                canvas.drawCircle(cx - r * 0.48f, cy - r * 0.48f, dot, mFill);
                canvas.drawCircle(cx, cy, dot, mFill);
                canvas.drawCircle(cx + r * 0.48f, cy + r * 0.48f, dot, mFill);
            } else if (which == TvModeController.TOP_BUTTON_MYLIST) {
                mPath.rewind();
                if (on) { // a tick: it is on the list
                    mPath.moveTo(cx - r, cy);
                    mPath.lineTo(cx - r * 0.3f, cy + r * 0.7f);
                    mPath.lineTo(cx + r, cy - r * 0.7f);
                } else { // a plus: add it
                    mPath.moveTo(cx - r, cy);
                    mPath.lineTo(cx + r, cy);
                    mPath.moveTo(cx, cy - r);
                    mPath.lineTo(cx, cy + r);
                }
                canvas.drawPath(mPath, mStroke);
            } else { // settings: three sliders
                for (int i = -1; i <= 1; i++) {
                    float y = cy + i * r * 0.72f;
                    canvas.drawLine(cx - r, y, cx + r, y, mStroke);
                    canvas.drawCircle(cx + (i == 0 ? r * 0.4f : -r * 0.35f), y, r * 0.24f, mFill);
                }
            }
        }
    }

    @Override
    public void showTopButtons(boolean show) {
        if (!show && mTopHeld) {
            return; // the cursor is on them: they stay when the controls fade out
        }

        setTopButtonsVisible(show);
    }

    @Override
    public void dropTopButtons() {
        removeCallbacks(mTopTimeout);
        mTopHeld = false;
        setTopButtonsVisible(false);
    }

    @Override
    public boolean focusTopButtons() {
        if (mRandomButton.getVisibility() != VISIBLE) {
            return false;
        }

        View focused = getRootView().findFocus();

        if (focused == null || isTopButton(focused)) {
            return false;
        }

        // Only from the top row of the controls. The row under the seek bar sits in the lowest part
        // of the screen, and Up from there belongs to the player.
        int[] place = new int[2];
        focused.getLocationOnScreen(place);

        if (place[1] + focused.getHeight() / 2 > getHeight() * 0.8f) {
            return false;
        }

        mReturnFocus = new java.lang.ref.WeakReference<>(focused);
        mTopHeld = mRandomButton.requestFocus();
        if (mTopHeld) {
            restartTopTimeout();
        }
        return mTopHeld;
    }

    @Override
    public void focusRandomButton() {
        if (mTopHeld && !mRandomButton.isFocused() && !mListButton.isFocused() && !mSettingsButton.isFocused()) {
            setTopButtonsVisible(true);
            mRandomButton.requestFocus();
        }
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
        mVhs.setVisibility(GONE);
        mVhs.setFrame(null, false);
        mPreviewId = null;
        RxHelper.disposeActions(mStoryboardAction);
        clearTargets();
    }

    private void clearTargets() {
        // The view lets go of a picture before Glide takes it back.
        RequestManager glide = Glide.with(getContext().getApplicationContext());
        if (mFrameTarget != null) {
            glide.clear(mFrameTarget);
            mFrameTarget = null;
        }
        if (mFallbackTarget != null) {
            glide.clear(mFallbackTarget);
            mFallbackTarget = null;
        }
    }

    @Override
    public void showPreview(final String videoId, final long positionMs, String fallbackImageUrl) {
        hideSwitching();

        if (videoId == null) {
            showSwitching(TvModeService.FX_STATIC);
            return;
        }

        mPreviewId = videoId;
        // Static shows through until the first picture is in, so the screen is never blank.
        mStatic.setVisibility(VISIBLE);
        mVhs.setVisibility(VISIBLE);

        if (fallbackImageUrl != null) {
            mFallbackTarget = new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap picture, @Nullable Transition<? super Bitmap> transition) {
                    if (videoId.equals(mPreviewId)) {
                        mVhs.setFrame(picture, false); // kept only until the real frame arrives
                    }
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {
                    // NOP
                }
            };
            Glide.with(getContext().getApplicationContext()).asBitmap().load(fallbackImageUrl).into(mFallbackTarget);
        }

        mStoryboardAction = loadStoryboard(videoId, storyboard -> {
            if (!videoId.equals(mPreviewId)) {
                return;
            }

            mFrameTarget = new CustomTarget<Bitmap>() {
                @Override
                public void onResourceReady(@NonNull Bitmap frame, @Nullable Transition<? super Bitmap> transition) {
                    if (videoId.equals(mPreviewId)) {
                        mVhs.setFrame(frame, true);
                    }
                }

                @Override
                public void onLoadCleared(@Nullable Drawable placeholder) {
                    // NOP
                }
            };
            frameRequest(storyboard, positionMs).into(mFrameTarget);
        });
    }

    @Override
    public void preloadPreview(String videoId, final long positionMs) {
        if (videoId == null) {
            return;
        }

        // Same request as showPreview, so the picture sheet is in Glide's cache when it's needed.
        loadStoryboard(videoId, storyboard -> frameRequest(storyboard, positionMs).preload());
    }

    private interface OnStoryboard {
        void onStoryboard(MediaItemStoryboard storyboard);
    }

    /** YouTube's seek previews for a video. Comes from the video info, which TV mode loads ahead for nearby channels. */
    private Disposable loadStoryboard(String videoId, final OnStoryboard callback) {
        return YouTubeServiceManager.instance().getMediaItemService().getStoryboardObserve(videoId)
                .subscribe(storyboard -> post(() -> {
                    // Every number here is divided by later on, so a video without usable seek previews stops here.
                    if (storyboard != null && storyboard.getGroupDurationMS() > 0 && storyboard.getGroupSize() != null
                            && storyboard.getGroupSize().getDurationEachMS() > 0
                            && storyboard.getGroupSize().getRowCount() > 0 && storyboard.getGroupSize().getColCount() > 0) {
                        callback.onStoryboard(storyboard);
                    }
                }), error -> Log.e(TAG, "No seek previews for the VHS preview: %s", error.getMessage()));
    }

    /** One frame cut out of the sheet of seek previews that covers this position. */
    private RequestBuilder<Bitmap> frameRequest(MediaItemStoryboard storyboard, long positionMs) {
        int group = (int) (positionMs / storyboard.getGroupDurationMS());
        long inGroupMs = positionMs % storyboard.getGroupDurationMS();
        MediaItemStoryboard.Size size = storyboard.getGroupSize();

        return Glide.with(getContext().getApplicationContext())
                .asBitmap()
                .load(storyboard.getGroupUrl(group))
                .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                .transform(new GlideThumbnailTransformation(inGroupMs, size.getWidth(), size.getHeight(),
                        size.getRowCount(), size.getColCount(), size.getDurationEachMS()));
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

    /**
     * A still frame made to look like a paused VHS tape: red and cyan colour fringes, grain,
     * two thin broken lines drifting down, tearing at the bottom edge, scanlines and
     * the recorder's own PLAY text. The frames YouTube gives are small, and the look hides that.
     */
    private static class VhsView extends View {
        private final Paint mPicture = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint mRed = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint mBlue = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint mLines = new Paint();
        private final Paint mSpeck = new Paint();
        private final Paint mGrain = new Paint();
        private final android.graphics.Matrix mGrainMatrix = new android.graphics.Matrix();
        private BitmapShader mGrainShader;
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPlayMark = new Path();
        private final RectF mTarget = new RectF();
        private final Random mRandom = new Random();
        private Bitmap mFrame;
        private boolean mIsRealFrame;
        /** Where down the screen the tracking band starts, 0 to 1. */
        private float mBandStart;

        VhsView(Context context) {
            super(context);

            ColorMatrix washedOut = new ColorMatrix();
            washedOut.setSaturation(0.7f);
            mPicture.setColorFilter(new ColorMatrixColorFilter(washedOut));

            // Colour split: the red part of the picture sits a little to the right, the green and
            // blue part (cyan) a little to the left. Added together they give the picture back,
            // with red and cyan fringes on every edge.
            mRed.setColorFilter(new ColorMatrixColorFilter(new float[] {1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0}));
            mRed.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            mBlue.setColorFilter(new ColorMatrixColorFilter(new float[] {0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0}));
            mBlue.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));

            // Grain: a small tile of random dots, laid over the picture at a new offset every frame.
            Bitmap grain = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < 96; x++) {
                for (int y = 0; y < 96; y++) {
                    int value = mRandom.nextInt(256);
                    grain.setPixel(x, y, (mRandom.nextInt(46) << 24) | (value << 16) | (value << 8) | value);
                }
            }
            mGrainShader = new BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            mGrain.setShader(mGrainShader);

            Bitmap tile = Bitmap.createBitmap(1, 4, Bitmap.Config.ARGB_8888);
            tile.setPixel(0, 0, 0x66000000);
            tile.setPixel(0, 1, 0x26000000);
            mLines.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));

            mSpeck.setColor(0xD9FFFFFF);
            mText.setColor(0xF2FFFFFF);
            mText.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            mText.setShadowLayer(6, 3, 3, 0xFF000000);
        }

        /** real: a frame of the video itself. A stand-in picture never replaces one. */
        void setFrame(Bitmap frame, boolean real) {
            if (frame != null && !real && mIsRealFrame) {
                return;
            }

            if (mFrame == null && frame != null) {
                mBandStart = mRandom.nextFloat(); // a new channel change: the band starts at a new height
            }

            mFrame = frame;
            mIsRealFrame = frame != null && real;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            Bitmap frame = mFrame;

            if (frame == null || frame.isRecycled()) {
                return; // the static underneath shows
            }

            int width = getWidth();
            int height = getHeight();
            long time = SystemClock.uptimeMillis();

            canvas.drawColor(0xFF000000);

            // The whole picture trembles a little from side to side.
            float jitter = (mRandom.nextFloat() - 0.5f) * width * 0.004f;
            float split = width * 0.0055f;
            mTarget.set(jitter + split, 0, width + jitter + split, height);
            canvas.drawBitmap(frame, null, mTarget, mRed);
            mTarget.offset(-2 * split, 0);
            canvas.drawBitmap(frame, null, mTarget, mBlue);

            // Tracking band: a strip pulled sideways and full of noise, rolling down the screen.
            // Two hair-thin broken white lines, each drifting down slowly. They start somewhere else
            // on every channel change.
            float lineHeight = Math.max(2, height * 0.003f);
            for (int i = 0; i < 2; i++) {
                float top = ((time % 9000) / 9000f + mBandStart + i * 0.37f) % 1f * height;
                for (float x = mRandom.nextFloat() * width * 0.03f; x < width; ) {
                    float dash = width * (0.004f + 0.02f * mRandom.nextFloat());
                    canvas.drawRect(x, top, x + dash, top + lineHeight, mSpeck);
                    x += dash + width * (0.002f + 0.012f * mRandom.nextFloat());
                }
            }

            // Head-switching noise: the bottom edge of a tape picture is always torn.
            float edge = height * 0.035f;
            tear(canvas, frame, width, height, height - edge, edge, width * 0.08f * (mRandom.nextFloat() - 0.3f), 26);

            for (int i = 0; i < 2; i++) {
                speck(canvas, width, mRandom.nextFloat() * height);
            }

            mGrainMatrix.setTranslate(mRandom.nextInt(96), mRandom.nextInt(96));
            mGrainShader.setLocalMatrix(mGrainMatrix);
            canvas.drawRect(0, 0, width, height, mGrain);

            canvas.drawRect(0, 0, width, height, mLines);

            // The recorder's own text, top left.
            float size = height * 0.058f;
            float left = width * 0.06f;
            float base = height * 0.13f;
            mText.setTextSize(size);
            canvas.drawText("PLAY", left, base, mText);
            float markLeft = left + mText.measureText("PLAY ");
            mPlayMark.rewind();
            mPlayMark.moveTo(markLeft, base - size * 0.72f);
            mPlayMark.lineTo(markLeft + size * 0.62f, base - size * 0.36f);
            mPlayMark.lineTo(markLeft, base);
            mPlayMark.close();
            canvas.drawPath(mPlayMark, mText);

            if (getVisibility() == VISIBLE) {
                postInvalidateOnAnimation();
            }
        }

        /** Redraws one horizontal strip of the picture shifted sideways, with noise over it. */
        private void tear(Canvas canvas, Bitmap frame, int width, int height, float top, float stripHeight, float shift, int specks) {
            canvas.save();
            canvas.clipRect(0, top, width, top + stripHeight);
            canvas.drawColor(0xFF000000);
            mTarget.set(shift, 0, width + shift, height);
            canvas.drawBitmap(frame, null, mTarget, mPicture);
            canvas.drawColor(0x2EFFFFFF);
            for (int i = 0; i < specks; i++) {
                speck(canvas, width, top + mRandom.nextFloat() * stripHeight);
            }
            canvas.restore();
        }

        private void speck(Canvas canvas, int width, float y) {
            float x = mRandom.nextFloat() * width;
            canvas.drawRect(x, y, x + width * (0.01f + 0.05f * mRandom.nextFloat()), y + 3, mSpeck);
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

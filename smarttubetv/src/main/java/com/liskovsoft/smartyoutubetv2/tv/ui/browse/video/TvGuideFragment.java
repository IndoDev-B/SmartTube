package com.liskovsoft.smartyoutubetv2.tv.ui.browse.video;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.app.BrowseSupportFragment;
import androidx.leanback.widget.Presenter;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvModeService;
import com.liskovsoft.smartyoutubetv2.tv.presenter.TvActionCardPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.TvProgramCardPresenter;

/**
 * The TV guide: what the focused channel has on at the top, the channels as lines below it.<br/>
 * The lines are the normal SmartTube rows with a wide program card, so Up/Down/Left/Right, OK and
 * long press all work the way they do everywhere else.
 */
public class TvGuideFragment extends VideoRowsFragment {
    private TvProgramCardPresenter mProgramPresenter;
    private TvActionCardPresenter mActionPresenter;
    private TvGuideHeroView mHero;
    private Video mSelected;
    private boolean mWasPaused;

    @Override
    protected Presenter getCustomCardPresenter(VideoGroup group) {
        if (group == null || group.isEmpty()) {
            return null;
        }

        if (TvModeService.isAction(group.get(0))) {
            if (mActionPresenter == null) {
                mActionPresenter = new TvActionCardPresenter();
            }
            return mActionPresenter;
        }

        if (mProgramPresenter == null) {
            mProgramPresenter = new TvProgramCardPresenter();
            mProgramPresenter.setOnItemViewLongPressedListener(createLongPressedListener());
        }

        return mProgramPresenter;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rows = super.onCreateView(inflater, container, savedInstanceState);

        FrameLayout root = new FrameLayout(inflater.getContext());
        root.addView(rows, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mHero = new TvGuideHeroView(inflater.getContext());
        root.addView(mHero, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TvGuideHeroView.HEIGHT_DP)));
        mHero.show(mSelected);

        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        hideAppTitle();
    }

    @Override
    public void onResume() {
        super.onResume();
        hideAppTitle();

        if (mWasPaused) {
            // Back from the player: channels moved on, so rebuild the lines and return to the same one.
            mWasPaused = false;
            BrowsePresenter presenter = BrowsePresenter.instance(getContext());
            if (presenter.getCurrentSection() != null && presenter.getCurrentSection().getId() == TvModeService.SECTION_ID) {
                int line = getPosition();
                presenter.refresh(false);
                setPosition(line);
            }
        }

        if (mHero != null) {
            mHero.show(mSelected); // times and progress moved on while away
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mWasPaused = true;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mHero = null;
        showAppTitle(1f);
    }

    /**
     * The search button, clock and logo would sit on top of the preview. The guide has its own clock.<br/>
     * Leanback shows the title again whenever the first line is focused, so it is also made see-through:
     * hidden means it can't take focus, see-through means it can't flash over the preview.
     */
    private void hideAppTitle() {
        if (getMainFragmentAdapter() != null && getMainFragmentAdapter().getFragmentHost() != null) {
            getMainFragmentAdapter().getFragmentHost().showTitleView(false);
        }

        showAppTitle(0f);
    }

    private void showAppTitle(float alpha) {
        if (getParentFragment() instanceof BrowseSupportFragment) {
            View title = ((BrowseSupportFragment) getParentFragment()).getTitleView();
            if (title != null) {
                title.setAlpha(alpha);
            }
        }
    }

    /**
     * The focused line sits just under the top part instead of at the top of the screen.
     */
    @Override
    public void setAlignment(int windowAlignOffsetFromTop) {
        super.setAlignment(dp(TvGuideHeroView.HEIGHT_DP + 34));
    }

    @Override
    protected void onVideoItemSelected(Video video) {
        mSelected = video;
        hideAppTitle();

        if (mHero != null) {
            mHero.show(video);
        }
    }

    private int dp(float value) {
        // The system metrics: this is called before the fragment is attached and has resources of its own.
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, android.content.res.Resources.getSystem().getDisplayMetrics()));
    }
}

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
import androidx.leanback.widget.ListRowPresenter;
import androidx.leanback.widget.Presenter;
import androidx.leanback.widget.RowPresenter;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvModeService;
import com.liskovsoft.smartyoutubetv2.tv.adapter.VideoGroupObjectAdapter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.TvActionCardPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.TvListRowPresenter;
import com.liskovsoft.smartyoutubetv2.tv.presenter.TvProgramCardPresenter;

import java.util.ArrayList;
import java.util.List;

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
    private boolean mOpened;
    private android.view.ViewTreeObserver.OnGlobalFocusChangeListener mFocusListener;

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

        // The app's search button sits hidden above the guide. Up on the top line would move the
        // focus onto it, where nothing shows it, so the top line is where Up stops.
        if (getVerticalGridView() != null) {
            getVerticalGridView().setOnKeyInterceptListener(event ->
                    event.getKeyCode() == android.view.KeyEvent.KEYCODE_DPAD_UP && getSelectedPosition() <= 0);
        }

        // "When I open TV: play the last channel". Opening means moving into the guide, not just
        // passing over TV in the side menu, so it waits for the focus to land on a card.
        mOpened = false;
        mFocusListener = (oldFocus, newFocus) -> {
            if (mOpened || newFocus == null || getView() == null || getContext() == null) {
                return;
            }

            for (android.view.ViewParent parent = newFocus.getParent(); parent != null; parent = parent.getParent()) {
                if (parent == getView()) {
                    mOpened = true;
                    TvModeService service = TvModeService.instance(getContext());
                    Video last = TvModeService.OPEN_LAST.equals(service.getOpenMode()) ? service.tuneLastChannel() : null;
                    if (last != null) {
                        PlaybackPresenter.instance(getContext()).openVideo(last);
                    }
                    return;
                }
            }
        };
        view.getViewTreeObserver().addOnGlobalFocusChangeListener(mFocusListener);
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
                presenter.refresh(false);
            }
        }

        if (mHero != null) {
            mHero.setPreviewAllowed(true);
            mHero.show(mSelected); // times and progress moved on while away
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mWasPaused = true;

        if (mHero != null) {
            mHero.setPreviewAllowed(false); // the player, or a menu, is taking over
        }
    }

    @Override
    public void onDestroyView() {
        if (getView() != null && mFocusListener != null) {
            getView().getViewTreeObserver().removeOnGlobalFocusChangeListener(mFocusListener);
        }
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
    protected ListRowPresenter createRowPresenter() {
        TvListRowPresenter presenter = new TvListRowPresenter();
        presenter.enableChildRoundedCorners(true);
        return presenter;
    }

    /**
     * The guide is rebuilt after every change (a setting, a shuffle, a new channel, coming back
     * from the player). Clearing the lines would throw the focus back to the top, so a line that
     * is already on screen gets its new programs in place and keeps the focus.
     */
    @Override
    public void update(VideoGroup group) {
        if (group == null) {
            return;
        }

        boolean startOver = group.getAction() == VideoGroup.ACTION_REPLACE && group.getPosition() == -1;

        if (startOver) {
            if (!keepLines()) {
                super.update(group);
            }
            return;
        }

        VideoGroupObjectAdapter line = getGroupAdapter(group.getId());

        if (line == null) {
            super.update(group); // a new line
            return;
        }

        line.clear();
        line.add(group);
        setRowTitle(group.getId(), group.getTitle());

        // Back to the video on now. Programs that already played are to its left.
        int position = findRowPosition(group.getId());
        RowPresenter.ViewHolder holder = position != -1 ? getRowViewHolder(position) : null;
        if (holder instanceof ListRowPresenter.ViewHolder) {
            ((ListRowPresenter.ViewHolder) holder).getGridView().setSelectedPosition(TvListRowPresenter.onNowIndex(line, getContext()));
        }
    }

    /**
     * Drops the lines of channels that are gone. True when the lines left are in the right order,
     * so they can be refreshed where they are. False means the guide has to be built again.
     */
    private boolean keepLines() {
        List<Integer> shown = getRowIds();

        if (shown.isEmpty() || getContext() == null) {
            return false;
        }

        List<Integer> wanted = TvModeService.instance(getContext()).getGuideRowIds();

        for (int id : shown) {
            if (!wanted.contains(id)) {
                removeRow(id);
            }
        }

        shown = getRowIds();
        List<Integer> wantedShown = new ArrayList<>();

        for (int id : wanted) {
            if (shown.contains(id)) {
                wantedShown.add(id);
            }
        }

        // A new channel is added at the end, so the lines shown must be the start of the wanted list.
        return !shown.isEmpty() && shown.equals(wantedShown) && shown.equals(wanted.subList(0, shown.size()));
    }

    @Override
    protected void onVideoItemSelected(Video video) {
        mSelected = video;
        hideAppTitle();

        if (mHero != null) {
            // Focus changes arrive in the middle of a layout pass, where a text change can't resize its view.
            // Posting it runs the update right after.
            mHero.post(() -> {
                if (mHero != null) {
                    mHero.show(mSelected);
                }
            });
        }
    }

    private int dp(float value) {
        // The system metrics: this is called before the fragment is attached and has resources of its own.
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, android.content.res.Resources.getSystem().getDisplayMetrics()));
    }
}

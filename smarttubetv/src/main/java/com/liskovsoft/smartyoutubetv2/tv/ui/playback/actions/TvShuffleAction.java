package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * TV mode player button.
 */
public class TvShuffleAction extends TwoStateAction {
    public TvShuffleAction(Context context) {
        super(context, R.id.action_tv_shuffle, R.drawable.action_mode_shuffle, false);

        String[] labels = new String[2];
        labels[INDEX_OFF] = context.getString(R.string.tv_mode_shuffle);
        labels[INDEX_ON] = context.getString(R.string.tv_mode_shuffle);
        setLabels(labels);
    }
}

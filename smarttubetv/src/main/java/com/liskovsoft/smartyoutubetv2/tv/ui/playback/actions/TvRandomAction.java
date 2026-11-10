package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * TV mode player button.
 */
public class TvRandomAction extends TwoStateAction {
    public TvRandomAction(Context context) {
        super(context, R.id.action_tv_random, R.drawable.action_tv_random, false);

        String[] labels = new String[2];
        labels[INDEX_OFF] = context.getString(R.string.tv_mode_random);
        labels[INDEX_ON] = context.getString(R.string.tv_mode_random);
        setLabels(labels);
    }
}

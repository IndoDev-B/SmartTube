package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * TV mode player button.
 */
public class TvGuideAction extends TwoStateAction {
    public TvGuideAction(Context context) {
        super(context, R.id.action_tv_guide, R.drawable.action_queue, false);

        String[] labels = new String[2];
        labels[INDEX_OFF] = context.getString(R.string.tv_mode_guide);
        labels[INDEX_ON] = context.getString(R.string.tv_mode_guide);
        setLabels(labels);
    }
}

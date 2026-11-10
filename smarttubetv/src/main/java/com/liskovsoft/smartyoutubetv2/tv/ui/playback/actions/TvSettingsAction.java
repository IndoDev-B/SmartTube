package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import com.liskovsoft.smartyoutubetv2.tv.R;

/**
 * TV mode player button.
 */
public class TvSettingsAction extends TwoStateAction {
    public TvSettingsAction(Context context) {
        super(context, R.id.action_tv_settings, R.drawable.icon_settings, false);

        String[] labels = new String[2];
        labels[INDEX_OFF] = context.getString(R.string.tv_mode_settings);
        labels[INDEX_ON] = context.getString(R.string.tv_mode_settings);
        setLabels(labels);
    }
}

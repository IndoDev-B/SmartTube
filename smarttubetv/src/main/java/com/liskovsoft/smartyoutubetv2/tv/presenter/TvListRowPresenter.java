package com.liskovsoft.smartyoutubetv2.tv.presenter;

import android.content.Context;

import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.ObjectAdapter;
import androidx.leanback.widget.RowPresenter;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvChannel;
import com.liskovsoft.smartyoutubetv2.common.tvmode.TvModeService;

/**
 * A channel line in the TV guide. Videos that already played sit at its start, so the line opens
 * on the video that is on now and Left goes back through what was on.
 */
public class TvListRowPresenter extends CustomListRowPresenter {
    @Override
    protected void onBindRowViewHolder(RowPresenter.ViewHolder holder, Object item) {
        super.onBindRowViewHolder(holder, item);

        if (holder instanceof ViewHolder && item instanceof ListRow) {
            ViewHolder row = (ViewHolder) holder;
            int onNow = onNowIndex(((ListRow) item).getAdapter(), row.view.getContext());

            if (onNow > 0) {
                row.getGridView().setSelectedPosition(onNow);
            }
        }
    }

    /** Where in a line the video on now is. 0 when the line isn't a channel. */
    public static int onNowIndex(ObjectAdapter adapter, Context context) {
        if (adapter == null || adapter.size() == 0 || !(adapter.get(0) instanceof Video)) {
            return 0;
        }

        TvChannel channel = TvModeService.instance(context).findChannel((Video) adapter.get(0));
        Video onNow = channel != null ? channel.onNow() : null;

        if (onNow == null || onNow.videoId == null) {
            return 0;
        }

        for (int i = 0; i < adapter.size(); i++) {
            Object video = adapter.get(i);
            if (video instanceof Video && onNow.videoId.equals(((Video) video).videoId)) {
                return i;
            }
        }

        return 0;
    }
}

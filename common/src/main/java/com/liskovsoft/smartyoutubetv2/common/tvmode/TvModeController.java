package com.liskovsoft.smartyoutubetv2.common.tvmode;

import android.view.KeyEvent;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;

/**
 * Player side of TV mode. Does nothing unless the playing video came from a TV mode channel.<br/>
 * Must be registered before VideoStateController: it reads the incognito flag set here.
 */
public class TvModeController extends BasePlayerController {
    @Override
    public void onNewVideo(Video item) {
        if (item == null || item.tvChannelId == null) {
            return;
        }

        // TV mode videos stay out of the YouTube history.
        item.incognito = true;

        TvModeService.instance(getContext()).onVideoStarted(item);
    }

    @Override
    public boolean onKeyDown(int keyCode) {
        Video current = getVideo();

        if (current == null || current.tvChannelId == null || getPlayer() == null) {
            return false;
        }

        int dir = 0;

        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            dir = 1;
        } else if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            dir = -1;
        } else if (!getPlayer().isOverlayShown()) {
            // With only the video on screen, Up/Down surf channels (most streaming remotes have no CH buttons).
            // Down goes to the next channel number, the same way the guide list runs down the screen.
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                dir = -1;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                dir = 1;
            }
        }

        if (dir == 0) {
            return false;
        }

        TvModeService service = TvModeService.instance(getContext());
        Video next = service.surf(current, dir);

        if (next == null) {
            return false;
        }

        TvChannel channel = service.findChannel(next);

        if (channel != null) {
            MessageHelpers.showMessage(getContext(), String.format("CH %s   %s", service.indexOf(channel) + 1, channel.name));
        }

        getMainController().onNewVideo(next);

        return true;
    }
}

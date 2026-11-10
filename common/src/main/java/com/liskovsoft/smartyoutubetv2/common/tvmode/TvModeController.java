package com.liskovsoft.smartyoutubetv2.common.tvmode;

import android.view.KeyEvent;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.lang.ref.WeakReference;

/**
 * Player side of TV mode. Does nothing unless the playing video came from a TV mode channel.<br/>
 * Must be registered before VideoStateController: it reads the incognito flag set here.
 */
public class TvModeController extends BasePlayerController {
    private static final long DIGITS_TIMEOUT_MS = 1_500;
    private static WeakReference<TvModeController> sInstance = new WeakReference<>(null);
    private String mDigits = "";
    private final Runnable mTuneDigits = this::tuneDigits;

    public TvModeController() {
        sInstance = new WeakReference<>(this);
    }

    /** The sleep timer ran out: stop playing. */
    public static void onSleepTimer() {
        TvModeController controller = sInstance.get();

        if (controller != null && controller.getPlayer() != null && controller.isTvVideo(controller.getVideo())) {
            controller.getPlayer().setPlayWhenReady(false);
            MessageHelpers.showLongMessage(controller.getContext(), "Sleep timer: stopped");
        }
    }

    private boolean isTvVideo(Video video) {
        return video != null && video.tvChannelId != null && !TvModeService.isAction(video);
    }

    private TvModeService getService() {
        return TvModeService.instance(getContext());
    }

    @Override
    public void onNewVideo(Video item) {
        if (!isTvVideo(item)) {
            return;
        }

        // TV mode videos stay out of the YouTube history unless the setting says otherwise.
        item.incognito = !getService().isYtHistoryEnabled();

        getService().onVideoStarted(item);
    }

    @Override
    public boolean onNextClicked() {
        return blockIfLive();
    }

    @Override
    public boolean onPreviousClicked() {
        return blockIfLive();
    }

    /** Live TV plays only what's on now: no skipping along the lineup. */
    private boolean blockIfLive() {
        Video current = getVideo();

        if (!isTvVideo(current)) {
            return false;
        }

        TvChannel channel = getService().findChannel(current);

        if (channel == null || !getService().isLive(channel)) {
            return false;
        }

        MessageHelpers.showMessage(getContext(), "Live channel: change channel or Shuffle");
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode) {
        Video current = getVideo();

        if (!isTvVideo(current) || getPlayer() == null) {
            return false;
        }

        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return onDigit(keyCode - KeyEvent.KEYCODE_0);
        }

        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return onDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0);
        }

        int dir = 0;

        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) {
            dir = 1;
        } else if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            dir = -1;
        } else if (!getPlayer().isOverlayShown() && getService().isDpadSurfEnabled()) {
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

        return tune(getService().surf(current, dir));
    }

    /** Number keys: type a channel number. Two digits within a moment make a two-digit number. */
    private boolean onDigit(int digit) {
        mDigits = (mDigits + digit);

        if (mDigits.length() > 2) {
            mDigits = mDigits.substring(mDigits.length() - 2);
        }

        MessageHelpers.showMessage(getContext(), "CH " + mDigits);
        Utils.removeCallbacks(mTuneDigits);
        Utils.postDelayed(mTuneDigits, DIGITS_TIMEOUT_MS);

        return true;
    }

    private void tuneDigits() {
        int number = Integer.parseInt(mDigits);
        mDigits = "";

        if (getPlayer() == null) {
            return;
        }

        if (number < 1 || number > getService().getChannels().size()) {
            MessageHelpers.showMessage(getContext(), "No channel " + number);
            return;
        }

        TvChannel channel = getService().getChannels().get(number - 1);
        Video current = getVideo();

        if (current != null && channel.id.equals(current.tvChannelId)) {
            return;
        }

        if (!tune(getService().tune(channel))) {
            MessageHelpers.showMessage(getContext(), "Channel " + number + " is still loading");
        }
    }

    private boolean tune(Video next) {
        if (next == null) {
            return false;
        }

        TvChannel channel = getService().findChannel(next);

        if (channel != null) {
            MessageHelpers.showMessage(getContext(), "CH " + getService().title(channel));
        }

        getMainController().onNewVideo(next);

        return true;
    }
}

package com.liskovsoft.smartyoutubetv2.common.tvmode;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.KeyEvent;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.lang.ref.WeakReference;
import java.util.Date;

/**
 * Player side of TV mode. Does nothing unless the playing video came from a TV mode channel.<br/>
 * Must be registered before VideoStateController: it reads the incognito flag set here.
 */
public class TvModeController extends BasePlayerController {
    private static final long DIGITS_TIMEOUT_MS = 1_500;
    private static final long SWITCHING_MAX_MS = 8_000;
    private static WeakReference<TvModeController> sInstance = new WeakReference<>(null);
    private static WeakReference<TvModeOverlay> sOverlay = new WeakReference<>(null);
    private String mDigits = "";
    private String mLastChannelId;
    private boolean mSleeping;
    private final Runnable mTuneDigits = this::tuneDigits;
    private final Runnable mHideSwitching = () -> {
        TvModeOverlay overlay = sOverlay.get();
        if (overlay != null) {
            overlay.hideSwitching();
        }
    };

    public TvModeController() {
        sInstance = new WeakReference<>(this);
    }

    /** The player screen hands over the view TV mode draws on. */
    public static void setOverlay(TvModeOverlay overlay) {
        sOverlay = new WeakReference<>(overlay);
    }

    /** The sleep timer ran out: stop playing, then turn the TV off or dim the screen. */
    public static void onSleepTimer() {
        TvModeController controller = sInstance.get();

        if (controller == null || controller.getPlayer() == null || !controller.isTvVideo(controller.getVideo())) {
            return;
        }

        controller.getPlayer().setPlayWhenReady(false);

        TvModeService service = controller.getService();

        if (service.isSleepTurnsOff() && service.turnOffTv()) {
            return;
        }

        TvModeOverlay overlay = sOverlay.get();

        if (overlay != null) {
            controller.mSleeping = true;
            overlay.showSleep("Sleep timer.  Press any button to keep watching.");
        } else {
            MessageHelpers.showLongMessage(controller.getContext(), "Sleep timer: stopped");
        }
    }

    /**
     * A video in the rows under the player was pressed. Live TV plays only what's on now,
     * so anything else on a live channel is turned down. True = don't play it.
     */
    public static boolean blocksSuggestionClick(Context context, Video item) {
        if (item == null || item.tvChannelId == null || TvModeService.isAction(item)) {
            return false;
        }

        TvModeService service = TvModeService.instance(context);
        TvChannel channel = service.findChannel(item);

        if (channel == null || !service.isLive(channel)) {
            return false;
        }

        Video onNow = service.tune(channel); // also sets where a live video starts

        if (onNow == null || onNow.videoId == null || onNow.videoId.equals(item.videoId)) {
            return false;
        }

        int index = channel.indexOf(item.videoId);
        MessageHelpers.showMessage(context, index > channel.pos
                ? "Starts at " + DateFormat.getTimeFormat(context).format(new Date(service.liveStartOf(channel, index)))
                : "Already played");
        return true;
    }

    private boolean isTvVideo(Video video) {
        return video != null && video.tvChannelId != null && !TvModeService.isAction(video);
    }

    private TvModeService getService() {
        return TvModeService.instance(getContext());
    }

    @Override
    public void onNewVideo(Video item) {
        TvModeOverlay overlay = sOverlay.get();

        if (!isTvVideo(item)) {
            // A normal video: nothing of TV mode may stay on screen.
            mLastChannelId = null;
            if (overlay != null) {
                overlay.hideBanner();
                overlay.hideSwitching();
                overlay.setScanlines(false);
            }
            return;
        }

        // TV mode videos stay out of the YouTube history unless the setting says otherwise.
        item.incognito = !getService().isYtHistoryEnabled();

        String note = getService().onVideoStarted(item);

        if (note != null) {
            MessageHelpers.showLongMessage(getContext(), note);
        }

        TvChannel channel = getService().findChannel(item);

        if (overlay != null && channel != null) {
            String effect = getService().getSwitchEffect();
            if (!TvModeService.FX_NONE.equals(effect)) {
                overlay.showSwitching(effect);
                Utils.removeCallbacks(mHideSwitching);
                Utils.postDelayed(mHideSwitching, SWITCHING_MAX_MS); // never leave the picture covered
            }
            overlay.setScanlines(getService().isScanlinesEnabled());
            showBanner(overlay, channel, item);
        } else if (channel != null && !channel.id.equals(mLastChannelId)) {
            MessageHelpers.showMessage(getContext(), "CH " + getService().title(channel));
        }

        mLastChannelId = channel != null ? channel.id : null;
    }

    private void showBanner(TvModeOverlay overlay, TvChannel channel, Video video) {
        TvModeService service = getService();
        boolean live = service.isLive(channel);
        int index = channel.indexOf(video.videoId);
        Video next = index != -1 && index + 1 < channel.lineup.size() ? channel.lineup.get(index + 1) : null;
        String author = video.getAuthor();
        String subtitle = author != null ? author : "";

        if (video.badge != null && !video.badge.isEmpty()) {
            subtitle += (subtitle.isEmpty() ? "" : "  •  ") + video.badge;
        }

        String nextLabel = next == null ? null
                : live ? "Next at " + DateFormat.getTimeFormat(getContext()).format(new Date(service.liveStartOf(channel, index + 1)))
                : "Up next";

        overlay.showBanner(String.valueOf(service.indexOf(channel) + 1), channel.name, live ? "LIVE" : "FREE",
                video.getTitle(), subtitle, nextLabel, next != null ? next.getTitle() : null);
    }

    @Override
    public void onVideoLoaded(Video item) {
        // The picture is ready: lift the static.
        Utils.removeCallbacks(mHideSwitching);
        Utils.postDelayed(mHideSwitching, 350);
    }

    @Override
    public void onControlsShown(boolean shown) {
        TvModeOverlay overlay = sOverlay.get();

        if (shown && overlay != null) {
            overlay.hideBanner(); // the controls show the same things
        }
    }

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId == R.id.action_tv_guide) {
            if (getPlayer() != null) {
                getPlayer().finish();
            }
            BrowsePresenter.instance(getContext()).selectSection(TvModeService.SECTION_ID);
        } else if (buttonId == R.id.action_tv_shuffle) {
            Video current = getVideo();
            final TvChannel channel = isTvVideo(current) ? getService().findChannel(current) : null;

            if (channel == null) {
                MessageHelpers.showMessage(getContext(), "Shuffle works on TV channels");
                return;
            }

            MessageHelpers.showMessage(getContext(), "Shuffling " + channel.name);
            getService().shuffle(channel, () -> {
                Video next = getService().firstAfterShuffle(channel);
                if (next != null && getPlayer() != null) {
                    getMainController().onNewVideo(next);
                }
            });
        }
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

        if (mSleeping && keyCode != -1) { // any button wakes the screen after the sleep timer
            mSleeping = false;
            TvModeOverlay overlay = sOverlay.get();
            if (overlay != null) {
                overlay.hideSleep();
            }
            getPlayer().setPlayWhenReady(true);
            return true;
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

        getMainController().onNewVideo(next);

        return true;
    }
}

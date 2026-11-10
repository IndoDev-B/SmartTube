package com.liskovsoft.smartyoutubetv2.common.tvmode;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.KeyEvent;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import io.reactivex.disposables.Disposable;

/**
 * Player side of TV mode. Does nothing unless the playing video came from a TV mode channel.<br/>
 * Must be registered before VideoStateController: it reads the incognito flag set here.
 */
public class TvModeController extends BasePlayerController {
    private static final long DIGITS_TIMEOUT_MS = 1_500;
    private static final long SWITCHING_MAX_MS = 20_000;
    private static final long FIRST_FRAME_MS = 150;
    private static WeakReference<TvModeController> sInstance = new WeakReference<>(null);
    private static WeakReference<TvModeOverlay> sOverlay = new WeakReference<>(null);
    private String mDigits = "";
    private String mDecoratedId;
    private String mBannerWaitingId;
    private boolean mSleeping;
    private final Runnable mTuneDigits = this::tuneDigits;
    private static final long PRELOAD_DELAY_MS = 4_000;
    private final Runnable mPreloadNeighbours = this::preloadNeighbours;
    private Disposable mPreloadAction;
    /** Channels pre-loaded each way. Must stay within FormatInfoWrapper's cache (8 videos) with room for the playing one. */
    private static final int PRELOAD_DEPTH = 3;
    private volatile int mPreloadGeneration;
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

        // The first video starts before the player screen exists, so its banner is shown now.
        TvModeController controller = sInstance.get();

        if (controller != null && controller.isTvVideo(controller.getVideo())) {
            controller.decorate(controller.getVideo());
        }
    }

    /** The sleep timer ran out: stop playing, then turn the TV off or dim the screen. */
    public static void onSleepTimer() {
        TvModeController controller = sInstance.get();

        if (controller == null || controller.getPlayer() == null || !controller.isTvVideo(controller.getVideo())) {
            return;
        }

        controller.getPlayer().setPlayWhenReady(false);

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
            mDecoratedId = null;
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

        decorate(item);
    }

    /**
     * Static while the video loads, then the channel banner. Once per video: the player restarts
     * a video by itself when a stream fails, and that must not replay them.
     */
    private void decorate(Video item) {
        TvModeOverlay overlay = sOverlay.get();
        TvChannel channel = getService().findChannel(item);

        if (overlay == null || channel == null) {
            return; // the player screen isn't up yet: setOverlay() calls back when it is
        }

        overlay.setScanlines(getService().isScanlinesEnabled());

        if (item.videoId == null || item.videoId.equals(mDecoratedId)) {
            return;
        }

        mDecoratedId = item.videoId;
        String effect = getService().getSwitchEffect();

        if (!TvModeService.FX_NONE.equals(effect)) {
            if (TvModeService.FX_VHS.equals(effect)) {
                overlay.showPreview(item.videoId, getService().previewPositionMs(item), item.cardImageUrl);
            } else {
                overlay.showSwitching(effect);
            }
            Utils.removeCallbacks(mHideSwitching);
            Utils.postDelayed(mHideSwitching, SWITCHING_MAX_MS); // never leave the picture covered
        }

        if (getPlayer() == null || !getPlayer().isOverlayShown()) {
            showBanner(overlay, channel, item);
        } else {
            mBannerWaitingId = item.videoId; // the controls are up and show the same things: banner once they go
        }
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

    // The static stays up until the video really plays. "Loaded" comes seconds earlier, while the
    // player is still buffering, and lifting it then showed the loading circle in between.

    @Override
    public void onSeekEnd() {
        alignLive(); // Live TV: a seek moves the channel's schedule with it
    }

    private void alignLive() {
        Video current = getVideo();

        if (isTvVideo(current) && getPlayer() != null) {
            getService().alignLive(current, getPlayer().getPositionMs());
        }
    }

    @Override
    public void onPlay() {
        alignLive(); // a pause or a slow start: the schedule follows what is on screen
        liftSwitching(FIRST_FRAME_MS); // playing: give the first frame a moment to reach the screen
        Utils.removeCallbacks(mPreloadNeighbours);
        Utils.postDelayed(mPreloadNeighbours, PRELOAD_DELAY_MS);
    }

    /**
     * Loads the video info of the channels above and below while this one plays, so Up/Down starts
     * about 1 to 2 s sooner. Waits a few seconds first: the playing video gets the bandwidth while it starts.
     */
    private void preloadNeighbours() {
        Video current = getVideo();

        // Only while the video stays out of YouTube history: loading video info renews the playback
        // id that history updates read, so the playing video's history would be sent under a new id.
        // The setting, not video.incognito: VideoStateController clears that flag once it has read it.
        if (!isTvVideo(current) || getService().isYtHistoryEnabled()) {
            return;
        }

        // Nearest first, both directions in turn: CH+ pressed a few times quickly lands 2 or 3 channels away.
        List<Video> down = getService().peek(current, 1, PRELOAD_DEPTH);
        List<Video> up = getService().peek(current, -1, PRELOAD_DEPTH);
        final List<Video> videos = new ArrayList<>();
        final List<String> ids = new ArrayList<>();

        for (int i = 0; i < PRELOAD_DEPTH; i++) {
            for (List<Video> side : Arrays.asList(down, up)) {
                Video video = i < side.size() ? side.get(i) : null;
                if (video != null && video.videoId != null && !video.videoId.equals(current.videoId) && !ids.contains(video.videoId)) {
                    ids.add(video.videoId);
                    videos.add(video);
                }
            }
        }

        // A new channel replaces the previous channel's list: what's loaded so far stays in the cache.
        RxHelper.disposeActions(mPreloadAction);
        final int generation = ++mPreloadGeneration;
        final boolean warmPreview = TvModeService.FX_VHS.equals(getService().getSwitchEffect());

        mPreloadAction = RxHelper.execute(RxHelper.fromRunnable(() -> {
            MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
            for (Video video : videos) {
                if (generation != mPreloadGeneration) {
                    return;
                }
                service.getFormatInfo(video.videoId);
                if (warmPreview) {
                    // Right after its video info: the storyboard comes from that info, now cached.
                    Utils.post(() -> warmPreview(video));
                }
            }
        }));
    }

    /** VHS preview: the storyboard sheet at the channel's "on now" spot lands in the image cache, so the frame shows at once */
    private void warmPreview(Video video) {
        TvModeOverlay overlay = sOverlay.get();

        if (overlay != null) {
            overlay.preloadPreview(video.videoId, getService().previewPositionMs(video));
        }
    }

    @Override
    public void onPause() {
        liftSwitching(0); // started paused: nothing more to wait for
    }

    @Override
    public void onEngineError(int type, int rendererIndex, Throwable error) {
        liftSwitching(0); // the error message must be readable
    }

    private void liftSwitching(long delayMs) {
        Utils.removeCallbacks(mHideSwitching);
        Utils.postDelayed(mHideSwitching, delayMs);
    }

    @Override
    public void onControlsShown(boolean shown) {
        TvModeOverlay overlay = sOverlay.get();

        if (overlay == null) {
            return;
        }

        if (shown) {
            overlay.hideBanner(); // the controls show the same things
            return;
        }

        Video current = getVideo();

        if (mBannerWaitingId != null && isTvVideo(current) && mBannerWaitingId.equals(current.videoId)) {
            TvChannel channel = getService().findChannel(current);
            if (channel != null) {
                showBanner(overlay, channel, current);
            }
        }

        mBannerWaitingId = null;
    }

    @Override
    public void onButtonClicked(int buttonId, int buttonState) {
        if (buttonId == R.id.action_tv_settings) {
            TvModeMenu.showSettings(getContext());
        } else if (buttonId == R.id.action_tv_random) {
            Video random = getService().randomVideo();

            if (random == null) {
                MessageHelpers.showMessage(getContext(), "Open the TV guide once, so the channels have videos");
                return;
            }

            MessageHelpers.showMessage(getContext(), "Random: " + random.getTitle());
            getMainController().onNewVideo(random);
        } else if (buttonId == R.id.action_tv_guide) {
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

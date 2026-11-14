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

    public static final int TOP_BUTTON_RANDOM = 0;
    public static final int TOP_BUTTON_SETTINGS = 1;
    public static final int TOP_BUTTON_MYLIST = 2;
    /** Opens the on-screen keypad. The overlay handles it itself: nothing to do here. */
    public static final int TOP_BUTTON_REMOTE = 3;

    /** On-screen keypad: a digit key. Unlike the remote's number keys nothing tunes by itself: Go does. */
    public static void typeDigit(int digit) {
        TvModeController controller = sInstance.get();

        if (controller == null || controller.getPlayer() == null) {
            return;
        }

        Utils.removeCallbacks(controller.mTuneDigits); // digits typed on the remote before are taken over, not tuned
        controller.mDigits = controller.mDigits + digit;

        if (controller.mDigits.length() > 3) { // up to channel 999
            controller.mDigits = controller.mDigits.substring(controller.mDigits.length() - 3);
        }

        controller.showTyped();
    }

    /** On-screen keypad, Prev: back to the channel watched before this one. Pressed again, back here. */
    public static void tunePrevious() {
        TvModeController controller = sInstance.get();

        if (controller == null || controller.getPlayer() == null) {
            return;
        }

        Utils.removeCallbacks(controller.mTuneDigits);
        controller.mDigits = "";
        controller.showTyped();

        TvModeService service = controller.getService();
        TvChannel channel = service.previousChannel();

        if (channel == null) {
            MessageHelpers.showMessage(controller.getContext(), "No previous channel yet");
            return;
        }

        if (!controller.tune(service.tune(channel))) {
            MessageHelpers.showMessage(controller.getContext(), "Channel " + (service.indexOf(channel) + 1) + " is still loading");
        }
    }

    /** On-screen keypad: takes back the last typed digit. Nothing left = nothing gets tuned. */
    public static void deleteDigit() {
        TvModeController controller = sInstance.get();

        if (controller == null || controller.mDigits.isEmpty()) {
            return;
        }

        controller.mDigits = controller.mDigits.substring(0, controller.mDigits.length() - 1);
        Utils.removeCallbacks(controller.mTuneDigits); // on the keypad only Go tunes
        controller.showTyped();
    }

    /** On-screen keypad, Go: tunes what was typed now instead of after the pause. */
    public static void tuneTypedNow() {
        TvModeController controller = sInstance.get();

        if (controller != null && !controller.mDigits.isEmpty()) {
            Utils.removeCallbacks(controller.mTuneDigits);
            controller.tuneDigits();
        }
    }

    /** On-screen keypad, First / Last: the first or last channel of the list the guide shows. */
    public static void tuneEdge(boolean last) {
        TvModeController controller = sInstance.get();

        if (controller == null || controller.getPlayer() == null) {
            return;
        }

        // Half-typed digits would otherwise tune somewhere else a moment later.
        Utils.removeCallbacks(controller.mTuneDigits);
        controller.mDigits = "";
        controller.showTyped();

        TvModeService service = controller.getService();
        List<TvChannel> shown = service.getShownChannels();

        if (shown.isEmpty()) {
            MessageHelpers.showMessage(controller.getContext(), "No channels");
            return;
        }

        TvChannel channel = shown.get(last ? shown.size() - 1 : 0);
        int number = service.indexOf(channel) + 1;
        Video current = controller.getVideo();

        if (current != null && channel.id.equals(current.tvChannelId)) {
            MessageHelpers.showMessage(controller.getContext(), "Already on CH " + number);
            return;
        }

        if (!controller.tune(service.tune(channel))) {
            MessageHelpers.showMessage(controller.getContext(), "Channel " + number + " is still loading");
        }
    }

    /** The keypad shows the digits typed so far, also the ones typed on the remote. */
    private void showTyped() {
        TvModeOverlay overlay = sOverlay.get();

        if (overlay != null) {
            overlay.showTypedDigits(mDigits);
        }
    }

    /** The channel of the last video, so the keypad closes on a channel change but not on the next program. */
    private String mRemoteChannelId;

    /** The channel playing, for the line next to the top buttons: "CH 12   Name". Null when it isn't a channel's program. */
    public static String currentChannelLabel() {
        TvModeController controller = sInstance.get();
        Video current = controller != null ? controller.getVideo() : null;

        if (current == null || !controller.isTvVideo(current)) {
            return null;
        }

        TvChannel channel = controller.getService().findChannel(current);
        return channel != null ? "CH " + (controller.getService().indexOf(channel) + 1) + "   " + channel.name : null;
    }

    /** The player's own controls are on screen. */
    public static boolean isControlsUp() {
        TvModeController controller = sInstance.get();
        return controller != null && controller.getPlayer() != null && controller.getPlayer().isOverlayShown();
    }

    /** One of the buttons at the top of the player was pressed. */
    public static void onTopButton(int button) {
        TvModeController controller = sInstance.get();

        if (controller != null && controller.getPlayer() != null) {
            if (button == TOP_BUTTON_REMOTE) {
                return; // the keypad is the overlay's own
            }
            if (button == TOP_BUTTON_MYLIST) {
                controller.toggleMyList();
                return;
            }
            controller.onButtonClicked(button == TOP_BUTTON_RANDOM ? R.id.action_tv_random : R.id.action_tv_settings, 0);
        }
    }

    /** The My list button at the top of the player: what is playing goes on the list, or comes off it. */
    private void toggleMyList() {
        Video current = getVideo();

        if (current == null || current.videoId == null) {
            return;
        }

        if (current.isLive) {
            MessageHelpers.showMessage(getContext(), "A live stream can't be saved for later");
            return;
        }

        boolean added = getService().toggleMyList(current, getPlayer().getDurationMs());
        MessageHelpers.showMessage(getContext(), added ? "Saved to My list" : "Taken off My list");

        TvModeOverlay overlay = sOverlay.get();
        if (overlay != null) {
            overlay.setMyListState(added);
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

        // Asks first: playing it moves the channel's schedule.
        TvModeMenu.confirmJump(context, channel, item, picked -> {
            TvModeController controller = sInstance.get();
            if (controller != null && controller.getPlayer() != null) {
                controller.getMainController().onNewVideo(picked);
            }
        });
        return true;
    }

    /** A Live TV channel showing a stream moves on when the stream's slot is over: a stream never ends by itself. */
    @Override
    public void onTickle() {
        Video current = getVideo();
        noteSaved(false);

        // A channel's program, every few minutes: where it is goes to "Continue watching" while it
        // plays, so it is there even when the app is closed straight from the player. Not every
        // tick: each one writes the whole saved state.
        if (isTvVideo(current) && getPlayer() != null && getPlayer().isPlaying() && ++mCheckpointTicks >= 3) {
            mCheckpointTicks = 0;
            getService().checkpoint();
        }

        if (!isTvVideo(current) || !current.isLive || getPlayer() == null) {
            return;
        }

        Video next = getService().afterStream(current);

        if (next != null) {
            getMainController().onNewVideo(next);
        }
    }

    private int mCheckpointTicks;

    private static boolean isRandom(Video video) {
        return video != null && TvModeService.RANDOM.equals(video.tvChannelId);
    }

    /** The top buttons belong to TV mode: a channel's video, or one the Random button picked. */
    private boolean hasTopButtons(Video video) {
        return isTvVideo(video) || isRandom(video) || TvModeService.isShelf(video)
                || (video != null && TvModeService.ONCE.equals(video.tvChannelId));
    }

    private final Runnable mKeepRandom = () -> {
        TvModeOverlay overlay = sOverlay.get();

        if (getPlayer() != null && overlay != null && isRandom(getVideo())) {
            overlay.focusRandomButton();
        }
    };

    private static String sEndedStreamId;
    private static int sEndedCount;
    private static long sEndedFirstMs;

    /**
     * A TV mode live stream reported its end. Streams do that on a network hiccup or when the
     * player falls behind the live edge, and the app then played the channel's next program.
     * True = load the same stream again. After 3 ends within 5 minutes the stream has most
     * likely really finished, and the channel moves on as before.
     */
    public static boolean reloadsEndedStream(Video video) {
        if (video == null || !video.isLive || video.videoId == null || video.tvChannelId == null) {
            return false;
        }

        long now = System.currentTimeMillis();

        if (!video.videoId.equals(sEndedStreamId) || now - sEndedFirstMs > 5 * 60_000) {
            sEndedStreamId = video.videoId;
            sEndedCount = 0;
            sEndedFirstMs = now;
        }

        boolean reload = ++sEndedCount <= 3;
        com.liskovsoft.sharedutils.mylogger.Log.d("TvModeController", "Live stream %s reported its end (%s in a row): %s", video.videoId, sEndedCount, reload ? "loading it again" : "moving on");
        return reload;
    }

    /**
     * The player knows a video's real length the moment it has loaded it, in the guide's preview
     * too. A channel's lineup often doesn't: YouTube leaves the length off some search results and
     * the guide then counts 10 minutes. Kept now, the guide's boxes and Live TV's clock are right.
     */
    @Override
    public void onVideoLoaded(Video item) {
        if (item != null && !item.isLive && getPlayer() != null && TvModeService.isEnabled(getContext())) {
            long duration = getPlayer().getDurationMs();
            if (duration > 0 && duration < Video.MAX_LIVE_DURATION_MS) {
                getService().learnDuration(item.videoId, duration);
            }
        }
    }

    @Override
    public void onPlayEnd() {
        // An ordinary video ended: a single one comes off "Continue watching", a playlist's notes it was seen through.
        Video ended = getVideo();
        if (ended != null && ended.tvChannelId == null && getPlayer() != null && !isEmbedPlayer() && TvModeService.isEnabled(getContext())) {
            long duration = getPlayer().getDurationMs();
            getService().notePlain(ended, duration, duration, true);
        }

        // A random video ended: play another, the way the button would.
        if (isRandom(getVideo()) && getPlayer() != null) {
            Video random = getService().randomVideo();
            if (random != null) {
                getMainController().onNewVideo(random);
            }
        }

        // A program played once, outside the schedule, ended: back to its channel, where the clock is now.
        Video current = getVideo();
        if (current != null && TvModeService.ONCE.equals(current.tvChannelId) && getPlayer() != null) {
            Video back = getService().afterOnce();
            if (back != null) {
                getMainController().onNewVideo(back);
            }
        }

        // A program from "Continue watching" or "My list" ended: it is finished, and its channel carries on.
        if (TvModeService.isShelf(current) && getPlayer() != null) {
            long duration = getPlayer().getDurationMs();
            getService().savedProgress(current, duration, duration, true);
            Video back = getService().afterSaved();
            if (back != null) {
                getMainController().onNewVideo(back);
            }
        }
    }

    /** A saved program is in the player: where it is goes to "Continue watching". now = write it down at once. */
    private void noteSaved(boolean now) {
        Video current = getVideo();

        // An ordinary video, from Home, search or a playlist: it goes on the same "Continue watching".
        if (current != null && current.tvChannelId == null && getPlayer() != null && !isEmbedPlayer() && getPlayer().isEngineInitialized()
                && TvModeService.isEnabled(getContext())) {
            long position = getPlayer().getPositionMs();
            if (position > 0) {
                getService().notePlain(current, position, getPlayer().getDurationMs(), now);
            }
            return;
        }

        if (TvModeService.isShelf(current) && getPlayer() != null && getPlayer().isEngineInitialized()) {
            long position = getPlayer().getPositionMs();
            if (position > 0) { // 0 is a player that hasn't started, or was already reset
                getService().savedProgress(current, position, getPlayer().getDurationMs(), now);
            }
        }
    }

    @Override
    public void onEngineReleased() {
        noteSaved(true);
        getService().checkpoint(); // a channel's program: where it was left, before the app can be closed
    }

    private boolean isTvVideo(Video video) {
        return video != null && video.tvChannelId != null && !TvModeService.isAction(video);
    }

    private TvModeService getService() {
        return TvModeService.instance(getContext());
    }

    @Override
    public void onNewVideo(Video item) {
        // Not in a preview player: the guide's preview box plays an untagged copy on purpose, and
        // tagging it made the preview act as the TV player, stuck at the end of a program.
        if (!isEmbedPlayer()) {
            getService().adoptTvVideo(item);
        }
        TvModeOverlay overlay = sOverlay.get();

        // The keypad did its job once the channel changed. The next program on the same channel keeps it.
        String channelId = item != null ? item.tvChannelId : null;
        if (overlay != null && !android.text.TextUtils.equals(channelId, mRemoteChannelId)) {
            overlay.hideRemote();
        }
        mRemoteChannelId = channelId;

        if (!isTvVideo(item)) {
            // A normal video: nothing of TV mode may stay on screen.
            mDecoratedId = null;
            if (overlay != null) {
                overlay.hideBanner();
                overlay.hideSwitching();
                overlay.setScanlines(false);
            }

            if (isRandom(item)) {
                // Keep the controls up with the cursor on Random, so OK again plays another one.
                Utils.removeCallbacks(mKeepRandom);
                Utils.postDelayed(mKeepRandom, 400);
            } else if (overlay != null && !hasTopButtons(item)) {
                overlay.dropTopButtons();
            } else if (overlay != null && getPlayer() != null && getPlayer().isOverlayShown()) {
                // A program from Continue watching or My list, started with the controls already up:
                // dropping the buttons here left them missing until the controls came up again.
                overlay.showTopButtons(true);
                overlay.setMyListState(getService().isInMyList(item.videoId));
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
        if (isRandom(getVideo())) {
            Utils.removeCallbacks(mKeepRandom);
            Utils.post(mKeepRandom); // the player took the focus while it loaded
        }

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
        noteSaved(true);
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

        overlay.showTopButtons(shown && hasTopButtons(getVideo()));

        if (shown) {
            Video playing = getVideo();
            overlay.setMyListState(playing != null && getService().isInMyList(playing.videoId));
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
        getService().adoptTvVideo(current);

        // isControlsShown: the buttons and seek bar are focused, not the rows of videos under them.
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && hasTopButtons(current) && getPlayer() != null && getPlayer().isControlsShown()) {
            TvModeOverlay overlay = sOverlay.get();
            if (overlay != null && overlay.focusTopButtons()) {
                return true;
            }
        }

        // A program from Continue watching, My list or played once isn't a channel's, but the
        // channel keys still go on from the channel that was on.
        boolean fromSaved = current != null && (TvModeService.isShelf(current) || TvModeService.ONCE.equals(current.tvChannelId));

        if (fromSaved && getPlayer() != null && (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN)) {
            return tune(getService().surf(current, keyCode == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1));
        }

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

        if (mDigits.length() > 3) { // up to channel 999
            mDigits = mDigits.substring(mDigits.length() - 3);
        }

        MessageHelpers.showMessage(getContext(), "CH " + mDigits);
        showTyped();
        Utils.removeCallbacks(mTuneDigits);
        Utils.postDelayed(mTuneDigits, DIGITS_TIMEOUT_MS);

        return true;
    }

    private void tuneDigits() {
        if (mDigits.isEmpty()) {
            return; // the keypad's Del took them all back
        }

        int number = Integer.parseInt(mDigits);
        mDigits = "";
        showTyped();

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

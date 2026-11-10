package com.liskovsoft.smartyoutubetv2.common.tvmode;

/**
 * What TV mode draws over the playing video. Implemented by the player screen.
 */
public interface TvModeOverlay {
    /** The channel banner along the bottom. It hides itself after a few seconds. */
    void showBanner(String number, String name, String mode, String title, String subtitle, String nextLabel, String nextTitle);

    void hideBanner();

    /** Covers the picture while the next video loads. effect: TvModeService.FX_STATIC or FX_BLACK. */
    void showSwitching(String effect);

    /**
     * Covers the picture with a frame of the video that is loading, taken from YouTube's seek
     * previews at this position and given a VHS look. fallbackImageUrl is shown until that frame
     * arrives, and instead of it when the video has no seek previews.
     */
    void showPreview(String videoId, long positionMs, String fallbackImageUrl);

    /** Fetches the frame {@link #showPreview} would show, so a later channel change has it at once. */
    void preloadPreview(String videoId, long positionMs);

    void hideSwitching();

    void setScanlines(boolean enabled);

    /** Random (top left) and TV settings (top right). Shown together with the player's controls. */
    void showTopButtons(boolean show);

    /**
     * Up was pressed in the player's controls. Moves the focus to the Random button when nothing
     * of the player is above the focused control. True = the focus moved.
     */
    boolean focusTopButtons();

    /** Puts the focus on the Random button, so OK again plays another one. */
    void focusRandomButton();

    /** Dims the screen after the sleep timer, with a line of text. */
    void showSleep(String message);

    void hideSleep();
}

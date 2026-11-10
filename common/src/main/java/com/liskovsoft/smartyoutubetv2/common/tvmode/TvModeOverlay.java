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

    void hideSwitching();

    void setScanlines(boolean enabled);

    /** Dims the screen after the sleep timer, with a line of text. */
    void showSleep(String message);

    void hideSleep();
}

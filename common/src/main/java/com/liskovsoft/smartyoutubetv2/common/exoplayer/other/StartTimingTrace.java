package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.os.SystemClock;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.source.MediaSourceEventListener.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaSourceEventListener.MediaLoadData;
import com.liskovsoft.sharedutils.mylogger.Log;

/**
 * Dev app only: logs each step of a video start, with the time since the player was handed the video.
 * Downloads (what, size, how long), decoder start, first frame drawn, ready. Tag: StartTrace.
 */
public class StartTimingTrace implements AnalyticsListener {
    private static final String TAG = "StartTrace";
    /** Only the start matters: stop logging this long after it */
    private static final long WINDOW_MS = 10_000;
    private long mStartMs = -1;

    /** The player was just handed a new video */
    public void start() {
        mStartMs = SystemClock.elapsedRealtime();
        log("prepare");
    }

    private void log(String event) {
        if (mStartMs == -1) {
            return;
        }

        long elapsedMs = SystemClock.elapsedRealtime() - mStartMs;

        if (elapsedMs > WINDOW_MS) {
            mStartMs = -1;
            return;
        }

        Log.d(TAG, "+%s ms %s", elapsedMs, event);
    }

    @Override
    public void onLoadStarted(EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
        log("load start " + describe(mediaLoadData));
    }

    @Override
    public void onLoadCompleted(EventTime eventTime, LoadEventInfo loadEventInfo, MediaLoadData mediaLoadData) {
        log("load done " + describe(mediaLoadData) + " " + loadEventInfo.bytesLoaded / 1024 + " KB in " + loadEventInfo.loadDurationMs + " ms");
    }

    @Override
    public void onDecoderInitialized(EventTime eventTime, int trackType, String decoderName, long initializationDurationMs) {
        log("decoder ready " + track(trackType) + " " + decoderName + " in " + initializationDurationMs + " ms");
    }

    @Override
    public void onDecoderInputFormatChanged(EventTime eventTime, int trackType, Format format) {
        log("decoder input " + track(trackType) + " " + (format.height > 0 ? format.height + "p" : format.sampleMimeType));
    }

    @Override
    public void onRenderedFirstFrame(EventTime eventTime, @Nullable Surface surface) {
        log("first frame drawn");
    }

    @Override
    public void onPlayerStateChanged(EventTime eventTime, boolean playWhenReady, int playbackState) {
        String state = playbackState == Player.STATE_READY ? "ready" : playbackState == Player.STATE_BUFFERING ? "buffering"
                : playbackState == Player.STATE_ENDED ? "ended" : "idle";
        log("state " + state + (playWhenReady ? ", playing" : ", paused"));
    }

    private static String describe(MediaLoadData data) {
        String type = data.dataType == C.DATA_TYPE_MEDIA ? "media" : data.dataType == C.DATA_TYPE_MEDIA_INITIALIZATION ? "init+index"
                : data.dataType == C.DATA_TYPE_MANIFEST ? "manifest" : "type" + data.dataType;
        String quality = data.trackFormat != null && data.trackFormat.height > 0 ? " " + data.trackFormat.height + "p" : "";
        return type + " " + track(data.trackType) + quality;
    }

    private static String track(int trackType) {
        return trackType == C.TRACK_TYPE_VIDEO ? "video" : trackType == C.TRACK_TYPE_AUDIO ? "audio"
                : trackType == C.TRACK_TYPE_TEXT ? "text" : "other";
    }
}

package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.chunk.MediaChunk;
import com.google.android.exoplayer2.source.chunk.MediaChunkIterator;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.BaseTrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.BandwidthMeter;

import java.util.List;

/**
 * Plays the first seconds of a video in a lower quality, then the chosen one for good.<br/>
 * The first download is then a fraction of the size of a 4K one, so the picture appears sooner.
 * Both qualities sit in this one selection, so the step up happens inside the running stream:
 * no reload, no stall. It never steps back down; that stays the user's choice.
 */
public class StartLowTrackSelection extends BaseTrackSelection {
    /** {@link TrackSelection.Definition#data} of a definition that should start low */
    public static final Object START_LOW = new Object();
    /**
     * Step up once this much is buffered. Measured on a BRAVIA: one 5 s piece of 4K60 is ~22 MB and took 6.6 s
     * to arrive, so with only 4 s buffered the picture stalled. 1080p pieces arrive in under a second,
     * so waiting for 10 s costs nothing at the start.
     */
    private static final long STEP_UP_BUFFERED_US = 10_000_000;
    private final int mHighIndex;
    private int mSelectedIndex;

    /**
     * @param tracks the chosen quality and the start quality, in any order
     */
    public StartLowTrackSelection(TrackGroup group, int... tracks) {
        super(group, tracks); // sorted by bitrate, highest first
        mHighIndex = 0;
        mSelectedIndex = length() - 1;
    }

    @Override
    public void updateSelectedTrack(long playbackPositionUs, long bufferedDurationUs, long availableDurationUs,
                                    List<? extends MediaChunk> queue, MediaChunkIterator[] mediaChunkIterators) {
        if (mSelectedIndex != mHighIndex && bufferedDurationUs >= STEP_UP_BUFFERED_US
                && !isBlacklisted(mHighIndex, android.os.SystemClock.elapsedRealtime())) {
            mSelectedIndex = mHighIndex;
        }
    }

    @Override
    public int getSelectedIndex() {
        return mSelectedIndex;
    }

    @Override
    public int getSelectionReason() {
        return C.SELECTION_REASON_ADAPTIVE;
    }

    @Nullable
    @Override
    public Object getSelectionData() {
        return null;
    }

    /** Makes a {@link StartLowTrackSelection} for definitions marked {@link #START_LOW}, the usual selections for the rest. */
    public static class Factory implements TrackSelection.Factory {
        private final AdaptiveTrackSelection.Factory mDefaultFactory = new AdaptiveTrackSelection.Factory();

        @Override
        public TrackSelection[] createTrackSelections(TrackSelection.Definition[] definitions, BandwidthMeter bandwidthMeter) {
            TrackSelection.Definition[] others = new TrackSelection.Definition[definitions.length];

            for (int i = 0; i < definitions.length; i++) {
                TrackSelection.Definition definition = definitions[i];
                others[i] = definition != null && definition.data == START_LOW ? null : definition;
            }

            TrackSelection[] selections = mDefaultFactory.createTrackSelections(others, bandwidthMeter);

            for (int i = 0; i < definitions.length; i++) {
                TrackSelection.Definition definition = definitions[i];
                if (definition != null && definition.data == START_LOW) {
                    selections[i] = new StartLowTrackSelection(definition.group, definition.tracks);
                }
            }

            return selections;
        }
    }
}

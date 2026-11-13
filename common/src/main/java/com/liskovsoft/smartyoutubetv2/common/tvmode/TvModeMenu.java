package com.liskovsoft.smartyoutubetv2.common.tvmode;

import android.content.Context;
import android.text.format.DateFormat;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.PlaybackPresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * TV mode menus, built from SmartTube's own side dialog: TV settings, a channel's options,
 * the channel editor and New channel.
 */
public class TvModeMenu {
    private static final String[] COMMON_SKIP_WORDS = {"reaction", "trailer", "compilation", "sleep", "live", "podcast"};
    private static final int[] SLEEP_MINUTES = {0, 15, 30, 45, 60, 90};
    /** YouTube's "Features" search filters: the value kept in the channel, and its name on screen. */
    private static final String[][] FEATURES = {
            {TvChannel.FEATURE_HD, "HD"},
            {TvChannel.FEATURE_4K, "4K"},
            {TvChannel.FEATURE_HDR, "HDR"},
            {TvChannel.FEATURE_SUBTITLES, "Subtitles"},
            {TvChannel.FEATURE_LIVE, "Live streams"},
            {TvChannel.FEATURE_360, "360°"},
            {TvChannel.FEATURE_VR180, "VR180"},
            {TvChannel.FEATURE_3D, "3D"},
            {TvChannel.FEATURE_CC, "Creative Commons"},
            {TvChannel.FEATURE_LOCATION, "Has a location"},
            {TvChannel.FEATURE_PURCHASED, "Bought on my account"},
    };
    private final Context mContext;
    private final TvModeService mService;
    private final AppDialogPresenter mDialog;

    private interface Setter {
        void set(String value);
    }

    private TvModeMenu(Context context) {
        mContext = context;
        mService = TvModeService.instance(context);
        mDialog = AppDialogPresenter.instance(context);
    }

    /** A card in the guide was pressed. True = handled here, false = play it as usual. */
    public static boolean onClicked(Context context, Video item) {
        TvModeMenu menu = new TvModeMenu(context);

        // The results line: a channel or program goes to its place in the guide, a playlist opens.
        if (TvModeService.isResult(item)) {
            menu.openResult(item);
            return true;
        }

        // "Continue watching" and "My list": the program plays by itself, from where it was left.
        if (TvModeService.isShelf(item)) {
            menu.playShelf(item);
            return true;
        }

        if (TvModeService.isAction(item)) {
            if (TvModeService.ACTION_SETTINGS.equals(item.tvChannelId)) {
                menu.showSettings();
            } else if (TvModeService.ACTION_SEARCH.equals(item.tvChannelId)) {
                menu.askSearch("");
            } else if (TvModeService.ACTION_SHUFFLE_ALL.equals(item.tvChannelId)) {
                menu.mService.shuffleAll(menu::refreshGuide);
                menu.toast("New lineups on every channel");
            } else if (TvModeService.ACTION_NEW_CHANNEL.equals(item.tvChannelId)) {
                menu.showNewChannel();
            } else if (TvModeService.ACTION_GENRE.equals(item.tvChannelId)) {
                menu.showGenreFilter();
            } else if (TvModeService.ACTION_TOPIC.equals(item.tvChannelId)) {
                menu.showTopicFilter();
            } else if (TvModeService.ACTION_LANGUAGE.equals(item.tvChannelId)) {
                menu.showLanguageFilter();
            } else if (TvModeService.ACTION_FAVOURITES.equals(item.tvChannelId)) {
                menu.mService.setFavOnly(!menu.mService.isFavOnly());
                menu.refreshGuide();
            }
            return true;
        }

        TvChannel channel = menu.mService.findChannel(item);

        // An episode the guide shows past a Series channel's queue: the series moves to it first.
        if (channel != null && menu.mService.isUpcoming(channel, item)) {
            menu.mService.goToUpcoming(channel, item, picked -> {
                if (picked != null) {
                    PlaybackPresenter.instance(context).openVideo(picked);
                } else {
                    menu.toast("That episode can't be found in the playlist any more");
                }
                menu.refreshGuide();
            });
            return true;
        }

        if (channel == null || !menu.mService.isLive(channel)) {
            return false;
        }

        // Live TV: what's on now starts where the clock is. Anything else asks first.
        Video onNow = menu.mService.tune(channel);

        if (onNow == null || onNow.videoId == null || onNow.videoId.equals(item.videoId)) {
            return false;
        }

        menu.confirmJump(channel, item, picked -> PlaybackPresenter.instance(context).openVideo(picked));

        return true;
    }

    /** Called with the program to play once the viewer has said yes. */
    public interface OnJump {
        void onJump(Video video);
    }

    /**
     * A program of a Live TV channel that isn't on now was picked, in the guide or under the
     * player. Asks first, because playing it moves the channel's whole schedule.
     */
    public static void confirmJump(Context context, TvChannel channel, Video item, OnJump onJump) {
        new TvModeMenu(context).confirmJump(channel, item, onJump);
    }

    private void confirmJump(TvChannel channel, Video item, OnJump onJump) {
        int index = channel.indexOf(item.videoId);

        if (index == -1) {
            return;
        }

        String when = index > channel.pos
                ? "Starts at " + DateFormat.getTimeFormat(mContext).format(new Date(mService.liveStartOf(channel, index)))
                : "Already played";

        // Cancel comes first, so a stray OK changes nothing.
        mDialog.appendSingleButton(UiOptionItem.from("Cancel", option -> mDialog.closeDialog()));
        mDialog.appendSingleButton(UiOptionItem.from("Play just this one. The schedule stays as it is", option -> {
            mDialog.closeDialog();
            onJump.onJump(mService.playOnce(channel, item));
        }));
        mDialog.appendSingleButton(UiOptionItem.from("Play now. This channel's schedule moves to start here", option -> {
            mDialog.closeDialog();
            Video picked = mService.jumpLive(channel, item);
            if (picked != null) {
                onJump.onJump(picked);
            }
        }));
        mDialog.showDialog(when + ": " + (item.getTitle() != null ? item.getTitle() : ""));
    }

    // ---------------- the guide's filters ----------------

    private void showGenreFilter() {
        // The button filters by program type: Movies, Series, Songs... A channel's genre shows in its line.
        String current = mService.getFilterKind();
        appendChoice("Any", current.isEmpty(), () -> mService.setFilterKind(""));

        for (String kind : mService.getKinds()) {
            appendChoice(kind, kind.equalsIgnoreCase(current), () -> mService.setFilterKind(kind));
        }

        mDialog.showDialog("Type");
    }

    /** The Genre button: the genres of the type picked, or of every type, each with how many channels it shows. */
    private void showTopicFilter() {
        String current = mService.getFilterGenre();
        List<String> genres = mService.getGenres();

        if (genres.isEmpty()) {
            toast("The channels shown have no genre to pick from");
            return;
        }

        appendChoice("Any", current.isEmpty(), () -> mService.setFilterGenre(""));

        for (String genre : genres) {
            appendChoice(genre + "  ·  " + mService.countWithGenre(genre), genre.equalsIgnoreCase(current), () -> mService.setFilterGenre(genre));
        }

        String kind = mService.getFilterKind();
        mDialog.showDialog(kind.isEmpty() ? "Genre" : "Genre of " + kind);
    }

    // ---------------- series ----------------

    /** Asks what to look for, then lists the playlists found, to tick the ones the channel should play. */
    private void askSeries(TvChannel channel, String suggestion) {
        SimpleEditDialog.show(mContext, "Series to look for", suggestion, value -> {
            String query = value != null ? value.trim() : "";

            if (!query.isEmpty()) {
                toast("Looking for series");
                mService.findSeries(query, found -> showSeriesFound(channel, query, found));
            }

            return true;
        });
    }

    private void showSeriesFound(TvChannel channel, String query, List<TvChannel.Series> found) {
        if (found.isEmpty()) {
            toast("No series found for \"" + query + "\"");
            return;
        }

        for (TvChannel.Series one : found) {
            String label = TvModeService.seriesName(one.title) + (one.author.isEmpty() ? "" : "  ·  " + one.author) + (one.count > 0 ? "  ·  " + one.count + " episodes" : "");
            mDialog.appendSingleSwitch(UiOptionItem.from(label, option -> {
                TvChannel.Series has = channel.findSeries(one.playlistId);

                if (option.isSelected() && has == null) {
                    mService.addSeries(channel, one, this::refreshGuide);
                } else if (!option.isSelected() && has != null) {
                    mService.removeSeries(channel, has, this::refreshGuide);
                }
            }, channel.findSeries(one.playlistId) != null));
        }

        mDialog.appendSingleButton(UiOptionItem.from("Look for something else", option -> {
            mDialog.closeDialog();
            askSeries(channel, query);
        }));

        mDialog.showDialog("Tick the series for " + channel.name);
    }

    /** What to type into the series search when nothing was typed yet: the channel's own first search term. */
    private static String seriesQuery(TvChannel channel) {
        return !channel.terms.isEmpty() ? channel.terms.get(0) : channel.name.toLowerCase(Locale.ROOT) + " all episodes";
    }

    /** The editor's lines for a Series channel: its playlists and how they take turns. */
    private void appendSeriesEditor(TvChannel channel) {
        for (TvChannel.Series one : channel.series) {
            List<OptionItem> actions = new ArrayList<>();
            int at = Math.min(mService.episodeOn(channel, one), Math.max(1, one.count));

            actions.add(UiOptionItem.from("Start again from episode 1", option -> {
                mDialog.closeDialog();
                mService.setEpisode(channel, one, 1, this::refreshGuide);
                toast(one.title + " starts again");
            }));
            actions.add(UiOptionItem.from("Go to an episode", option -> {
                mDialog.closeDialog();
                SimpleEditDialog.show(mContext, "Episode number" + (one.count > 0 ? " (1 to " + one.count + ")" : ""), String.valueOf(at), value -> {
                    try {
                        int episode = Integer.parseInt(value.trim());
                        mService.setEpisode(channel, one, episode, this::refreshGuide);
                        toast(one.title + " continues from episode " + episode);
                    } catch (NumberFormatException e) {
                        toast("That isn't a number");
                    }
                    return true;
                });
            }));
            actions.add(UiOptionItem.from("It plays backwards: turn it round", option -> {
                mDialog.closeDialog();
                mService.flipSeries(channel, one, this::refreshGuide);
                toast(one.title + " now plays from its other end");
            }));
            actions.add(UiOptionItem.from("Remove from this channel", option -> {
                mDialog.closeDialog();
                mService.removeSeries(channel, one, this::refreshGuide);
                toast("Removed " + one.title);
            }));

            mDialog.appendStringsCategory(one.title + (one.count > 0 ? "  ·  on episode " + at + " of " + one.count : ""), actions);
        }

        mDialog.appendSingleButton(UiOptionItem.from("+  Add a series", option -> {
            mDialog.closeDialog();
            askSeries(channel, seriesQuery(channel));
        }));

        mDialog.appendRadioCategory("Episodes in a row", options(String.valueOf(channel.seriesRun), value -> {
            mService.setSeriesRun(channel, Integer.parseInt(value), this::refreshGuide);
        }, "1", "1, then the next series", "2", "2, then the next series", "3", "3, then the next series", "0", "The whole series"));

        mDialog.appendRadioCategory("When a series ends", options(channel.seriesLoop ? "loop" : "stop",
                value -> mService.setSeriesLoop(channel, "loop".equals(value)),
                "loop", "Start it again", "stop", "Leave it out"));
    }

    private void showLanguageFilter() {
        String current = mService.getFilterLanguage();
        appendChoice("Any", current.isEmpty(), () -> mService.setFilterLanguage(""));

        for (String code : mService.getChannelLanguages()) {
            appendChoice(TvLanguage.nameOf(code), code.equals(current), () -> mService.setFilterLanguage(code));
        }

        mDialog.showDialog("Language");
    }

    /** One line of a pick-one list: OK picks it and closes the list. */
    private void appendChoice(String label, boolean current, Runnable pick) {
        mDialog.appendSingleButton(UiOptionItem.from((current ? "●  " : "○  ") + label, option -> {
            mDialog.closeDialog();
            pick.run();
            refreshGuide();
        }));
    }

    /** TV settings, opened from the player. */
    public static void showSettings(Context context) {
        new TvModeMenu(context).showSettings();
    }

    /** A card in the guide was long pressed. True = handled here. */
    public static boolean onLongClicked(Context context, Video item) {
        if (TvModeService.isResult(item)) {
            new TvModeMenu(context).showResultMenu(item);
            return true;
        }

        if (TvModeService.isShelf(item)) {
            new TvModeMenu(context).showSavedMenu(item);
            return true;
        }

        if (TvModeService.isAction(item)) {
            return true;
        }

        TvModeMenu menu = new TvModeMenu(context);
        TvChannel channel = menu.mService.findChannel(item);

        if (channel == null) {
            return false;
        }

        menu.showChannelMenu(channel, item);
        return true;
    }

    // ---------------- TV settings ----------------

    private void showSettings() {
        mDialog.appendRadioCategory("New channels play", modeOptions(mService.getDefaultMode(), false, mode -> {
            mService.setDefaultMode(mode);
            refreshGuide();
        }));

        mDialog.appendSingleSwitch(UiOptionItem.from("Up/Down changes channel",
                option -> mService.setDpadSurfEnabled(option.isSelected()), mService.isDpadSurfEnabled()));

        mDialog.appendRadioCategory("When I open TV", options(mService.getOpenMode(), mService::setOpenMode,
                TvModeService.OPEN_GUIDE, "Show the guide", TvModeService.OPEN_LAST, "Play the last channel"));

        mDialog.appendRadioCategory("Switching effect", options(mService.getSwitchEffect(), mService::setSwitchEffect,
                TvModeService.FX_STATIC, "TV static", TvModeService.FX_VHS, "VHS preview", TvModeService.FX_BLACK, "Black screen", TvModeService.FX_NONE, "Loading circle"));

        List<OptionItem> sleep = new ArrayList<>();
        for (int minutes : SLEEP_MINUTES) {
            sleep.add(UiOptionItem.from(minutes == 0 ? "Off" : "Stop playing in " + minutes + " minutes", option -> {
                mService.setSleepTimer(minutes);
                toast(minutes == 0 ? "Sleep timer off" : "In " + minutes + " minutes the video stops and the screen dims");
            }, mService.getSleepMinutes() == minutes));
        }
        // The line itself says what the timer is doing, so there's no need to open it to check.
        int left = mService.getSleepMinutesLeft();
        mDialog.appendRadioCategory(left > 0 ? "Sleep timer: stops in " + left + " min" : "Sleep timer: off", sleep);

        mDialog.appendSingleSwitch(UiOptionItem.from("Save to YouTube history",
                option -> mService.setYtHistoryEnabled(option.isSelected()), mService.isYtHistoryEnabled()));

        // Optional. With it channels know each video's language, description and view count before
        // it airs, English channels can search as the US, and Trending reads YouTube's own chart.
        mDialog.appendSingleButton(UiOptionItem.from("YouTube API key: " + mService.getApiStatus(), option -> {
            mDialog.closeDialog();
            SimpleEditDialog.show(mContext, "YouTube Data API key (empty = don't use one)", mService.getApiKey(), value -> {
                String key = value != null ? value.trim() : "";
                mService.setApiKey(key);

                if (key.isEmpty()) {
                    toast("API key removed");
                } else {
                    toast("Checking the key");
                    mService.testApiKey((works, message) -> toast(message));
                }

                return true;
            });
        }));

        mDialog.appendSingleButton(UiOptionItem.from("Refresh channels", option -> {
            mDialog.closeDialog();
            mService.refreshChannels(this::refreshGuide);
            toast("Getting the latest channel list and new videos");
        }));

        appendConfirm("Shuffle all channels", "Yes, new lineups on every channel", () -> {
            mService.shuffleAll(this::refreshGuide);
            toast("New lineups on every channel");
        });

        appendConfirm("Clear TV history (" + mService.getWatchedCount() + " videos)", "Yes, watched videos may come back", () -> {
            mService.clearHistory(null);
            toast("TV history cleared");
            refreshGuide();
        });

        appendConfirm("Restore preset channels", "Yes, remove the channels I made", () -> {
            mService.restorePresets();
            toast("Preset channels restored");
            refreshGuide();
        });

        mDialog.showDialog("TV settings");
    }

    // ---------------- channel options (hold OK) ----------------

    private void showChannelMenu(TvChannel channel, Video video) {
        mDialog.appendSingleButton(UiOptionItem.from("Watch", option -> {
            mDialog.closeDialog();
            Video onNow = mService.tune(channel);
            if (onNow != null) {
                PlaybackPresenter.instance(mContext).openVideo(onNow);
            }
        }));

        if (video != null && video.videoId != null) {
            // The whole description, to scroll through. The guide itself shows only its first lines.
            mDialog.appendSingleButton(UiOptionItem.from("Program info", option -> mService.loadInfo(video, (videoId, description) -> {
                String title = video.getTitle() != null ? video.getTitle() : "";
                mDialog.appendLongTextCategory(title, UiOptionItem.from(
                        description == null ? "Couldn't load the description. Try again in a moment."
                                : description.isEmpty() ? "This program has no description." : description));
                mDialog.showDialog(title);
            })));

            if (!video.isLive) { // a stream has nothing to come back to
                appendMyList(video);

                mDialog.appendSingleButton(UiOptionItem.from("Find playlists for this", option -> {
                    mDialog.closeDialog();
                    findPlaylists(video, channel);
                }));
            }
        }

        mDialog.appendSingleButton(UiOptionItem.from("Edit channel", option -> showEditor(channel)));

        // Only for a preset that was changed here: one press back to what the preset list says.
        if (mService.canReset(channel)) {
            appendConfirm("Reset this channel", "Yes, undo my changes to " + channel.name, () -> {
                mService.resetToPreset(channel, this::refreshGuide);
                toast(channel.name + " is back to its preset");
            });
        }

        mDialog.appendSingleButton(UiOptionItem.from("Shuffle", option -> {
            mDialog.closeDialog();
            mService.shuffle(channel, this::refreshGuide);
            toast("Shuffled " + channel.name);
        }));

        mDialog.appendSingleSwitch(UiOptionItem.from("Favourite", option -> {
            mService.setFav(channel, option.isSelected());
            refreshGuide();
        }, channel.fav));

        final String author = video != null ? TvModeService.creatorOf(video.author) : null;

        if (author != null) {
            mDialog.appendSingleButton(UiOptionItem.from("Block " + author, option -> {
                mDialog.closeDialog();
                mService.block(channel, author);
                toast(author + " blocked on " + channel.name);
                refreshGuide();
            }));
        }

        if (author != null && !channel.creators.contains(author)) {
            final String channelId = video.channelId;
            mDialog.appendSingleButton(UiOptionItem.from("More from " + author, option -> {
                mDialog.closeDialog();
                mService.addCreator(channel, author, channelId, this::refreshGuide);
                toast(author + " added to " + channel.name);
            }));
        }

        appendConfirm("Clear history", "Yes, its watched videos may come back", () -> {
            mService.clearHistory(channel);
            toast("History cleared for " + channel.name);
            refreshGuide();
        });

        mDialog.appendSingleButton(UiOptionItem.from("Move up", option -> {
            if (mService.move(channel, -1)) {
                mDialog.closeDialog();
                toast("Now channel " + (mService.indexOf(channel) + 1));
                refreshGuide();
            }
        }));

        mDialog.appendSingleButton(UiOptionItem.from("Move down", option -> {
            if (mService.move(channel, 1)) {
                mDialog.closeDialog();
                toast("Now channel " + (mService.indexOf(channel) + 1));
                refreshGuide();
            }
        }));

        appendConfirm("Delete channel", "Yes, delete " + channel.name, () -> {
            mService.delete(channel);
            toast("Channel deleted");
            refreshGuide();
        });

        mDialog.showDialog(mService.title(channel));
    }

    // ---------------- Continue watching, My list ----------------

    private void appendMyList(Video video) {
        boolean listed = mService.isInMyList(video.videoId);

        mDialog.appendSingleButton(UiOptionItem.from(listed ? "Take off My list" : "Save for later", option -> {
            mDialog.closeDialog();
            toast(mService.toggleMyList(video) ? "Saved to My list, at the top of the guide" : "Taken off My list");
            refreshGuide();
        }));
    }

    /** OK on a card of "Continue watching" or "My list". A playlist seen to its last video opens its own screen. */
    private void playShelf(Video card) {
        TvSaved saved = mService.getSaved(card);

        if (TvModeService.isFinishedPlaylist(saved)) {
            openPlaylist(saved);
            return;
        }

        Video video = mService.playSaved(card);
        if (video != null) {
            PlaybackPresenter.instance(mContext).openVideo(video);
        }
    }

    /** SmartTube's own playlist screen. What plays from there is ordinary SmartTube. */
    private void openPlaylist(TvSaved saved) {
        Video playlist = new Video();
        playlist.videoId = saved.videoId;
        playlist.playlistId = saved.playlistId;
        playlist.title = saved.playlistTitle.isEmpty() ? saved.title : saved.playlistTitle;
        playlist.author = saved.author;
        com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter.instance(mContext).openChannel(playlist);
    }

    /** Hold OK on a program of one of the two lines above the time grid, or of "Continue watching" on Home. */
    private void showSavedMenu(Video card) {
        final String shelf = card.tvChannelId;
        final boolean resume = !TvModeService.SHELF_LIST.equals(shelf);
        final String name = resume ? "Continue watching" : "My list";
        final String title = card.getTitle() != null ? card.getTitle() : "";
        final TvSaved saved = mService.getSaved(card);
        final boolean playlist = saved != null && saved.isPlaylist();

        mDialog.appendSingleButton(UiOptionItem.from(resume ? (TvModeService.isFinishedPlaylist(saved) ? "Open the playlist" : "Carry on watching") : "Watch", option -> {
            mDialog.closeDialog();
            playShelf(card);
        }));

        if (playlist) {
            if (!TvModeService.isFinishedPlaylist(saved)) {
                mDialog.appendSingleButton(UiOptionItem.from("Open the playlist", option -> {
                    mDialog.closeDialog();
                    openPlaylist(saved);
                }));
            }

            mDialog.appendSingleButton(UiOptionItem.from("Add to a TV channel", option -> {
                mDialog.closeDialog();
                showAddToChannel(saved.playlistId, saved.playlistTitle.isEmpty() ? saved.title : saved.playlistTitle,
                        saved.author, Math.max(0, saved.playlistIndex));
            }));
        } else {
            mDialog.appendSingleButton(UiOptionItem.from("Program info", option -> mService.loadInfo(card, (videoId, description) -> {
                mDialog.appendLongTextCategory(title, UiOptionItem.from(
                        description == null ? "Couldn't load the description. Try again in a moment."
                                : description.isEmpty() ? "This program has no description." : description));
                mDialog.showDialog(title);
            })));
        }

        if (resume && !playlist) {
            appendMyList(card);
        }

        mDialog.appendSingleButton(UiOptionItem.from("Remove from " + name, option -> {
            mDialog.closeDialog();
            mService.removeSaved(shelf, card.videoId);
            refreshGuide();
        }));

        appendConfirm("Clear " + name, "Yes, remove every program from " + name, () -> {
            mService.clearSaved(shelf);
            toast(name + " cleared");
            refreshGuide();
        });

        mDialog.showDialog(title);
    }

    // ---------------- search ----------------

    /** The matches go on the results line above the channels, which opens with the focus on the first one. */
    private void askSearch(String last) {
        SimpleEditDialog.showSubmitOnEnter(mContext, "Search channels and programs", last, value -> {
            String query = value != null ? value.trim() : "";

            if (!query.isEmpty()) {
                int count = mService.searchToResults(query);

                if (count == 0) {
                    toast("Nothing on the channels matches \"" + query + "\"");
                } else {
                    refreshGuide();
                }
            }

            return true;
        });
    }

    /** OK on a card of the results line. */
    private void openResult(Video card) {
        TvModeService.Found found = mService.resultFound(card);

        if (found != null) {
            mService.showInGuide(found.channel, found.video);
            refreshGuide();
            return;
        }

        TvModeService.PlaylistHit hit = mService.resultPlaylist(card);

        if (hit != null) {
            // SmartTube's own playlist screen. What plays from there is ordinary SmartTube, not TV.
            Video playlist = new Video();
            playlist.videoId = hit.videoId;
            playlist.playlistId = hit.series.playlistId;
            playlist.title = hit.series.title;
            playlist.author = hit.series.author;
            com.liskovsoft.smartyoutubetv2.common.app.presenters.ChannelUploadsPresenter.instance(mContext).openChannel(playlist);
        }
    }

    /** Hold OK on a card of the results line. */
    private void showResultMenu(Video card) {
        String title = card.getTitle() != null ? card.getTitle() : "";
        TvModeService.PlaylistHit hit = mService.resultPlaylist(card);

        if (hit != null) {
            mDialog.appendSingleButton(UiOptionItem.from("Open the playlist", option -> {
                mDialog.closeDialog();
                openResult(card);
            }));
            mDialog.appendSingleButton(UiOptionItem.from("Make a Series channel from episode " + hit.episode, option -> {
                mDialog.closeDialog();
                TvChannel made = mService.createSeriesChannel(hit, this::refreshGuide);
                toast("New channel " + (mService.indexOf(made) + 1) + ": " + made.name);
                refreshGuide();
            }));
        } else {
            mDialog.appendSingleButton(UiOptionItem.from("Go to it in the guide", option -> {
                mDialog.closeDialog();
                openResult(card);
            }));
        }

        mDialog.appendSingleButton(UiOptionItem.from("Remove from results", option -> {
            mDialog.closeDialog();
            mService.removeResult(card);
            refreshGuide();
        }));

        mDialog.appendSingleButton(UiOptionItem.from("Clear results", option -> {
            mDialog.closeDialog();
            mService.clearResults();
            refreshGuide();
        }));

        mDialog.showDialog(title);
    }

    /** "Find playlists for this": the results line opens at once and fills as each playlist is checked. */
    private void findPlaylists(Video video, TvChannel channel) {
        mService.findPlaylists(video, channel, this::refreshGuide, count -> {
            if (count == 0) {
                toast("No playlist found with this program. Lists longer than about 100 episodes can't be checked to the end.");
            }
        });
        toast("Looking for playlists with this program");
        refreshGuide();
    }

    // ---------------- channel editor ----------------

    private void showEditor(TvChannel channel) {
        appendKind(channel);

        if (channel.isSeries()) { // playlists in order: no search terms or filters to set
            appendSeriesEditor(channel);
            appendEditorEnd(channel);
            return;
        }

        // Search terms: press one to remove it, or add one.
        List<OptionItem> terms = new ArrayList<>();
        for (String term : channel.terms) {
            terms.add(UiOptionItem.from("Remove: " + term, option -> {
                if (channel.creators.isEmpty() && channel.terms.size() < 2) {
                    toast("A channel needs at least one source");
                    return;
                }
                channel.terms.remove(term);
                searchChanged(channel, "Removed \"" + term + "\"");
            }));
        }
        terms.add(UiOptionItem.from("+  Type a search term", option -> {
            mDialog.closeDialog();
            SimpleEditDialog.show(mContext, "Search term for " + channel.name, "", value -> {
                addTerm(channel, value);
                return true;
            });
        }));
        String base = channel.name.toLowerCase(Locale.ROOT);
        for (String idea : Arrays.asList(base + " documentary", base + " 4k", "best of " + base, base + " for beginners", base + " full episode")) {
            if (!channel.terms.contains(idea)) {
                terms.add(UiOptionItem.from("Add: " + idea, option -> addTerm(channel, idea)));
            }
        }
        mDialog.appendStringsCategory("Search terms (" + channel.terms.size() + ")", terms);

        // YouTube channels: their uploads join the lineup next to the search results.
        List<OptionItem> creators = new ArrayList<>();
        for (String creator : channel.creators) {
            creators.add(UiOptionItem.from("Remove: " + creator, option -> {
                if (channel.terms.isEmpty() && channel.creators.size() < 2) {
                    toast("A channel needs at least one source");
                    return;
                }
                channel.creators.remove(creator);
                channel.creatorIds.remove(creator);
                searchChanged(channel, "Removed " + creator);
            }));
        }
        creators.add(UiOptionItem.from("+  Type a YouTube channel's name", option -> {
            mDialog.closeDialog();
            SimpleEditDialog.show(mContext, "YouTube channel for " + channel.name, "", value -> {
                if (value != null && mService.addCreator(channel, value, null, this::refreshGuide)) {
                    toast("Added " + value.trim());
                }
                return true;
            });
        }));
        mDialog.appendStringsCategory("YouTube channels (" + channel.creators.size() + ")", creators);

        mDialog.appendRadioCategory("Minimum views", options(String.valueOf(channel.minViews), value -> {
            channel.minViews = Long.parseLong(value);
            searchChanged(channel, null);
        }, "0", "Any", "10000", "10K", "100000", "100K", "1000000", "1M"));

        mDialog.appendRadioCategory("Length", options(channel.length, value -> {
            channel.length = value;
            searchChanged(channel, null);
        }, TvChannel.ANY, "Any", TvChannel.LENGTH_SHORT, "Under 4 min", TvChannel.LENGTH_MEDIUM, "4 to 20 min", TvChannel.LENGTH_LONG, "Over 20 min"));

        mDialog.appendRadioCategory("Uploaded", options(channel.uploaded, value -> {
            channel.uploaded = value;
            searchChanged(channel, null);
        }, TvChannel.ANY, "Any time", TvChannel.UPLOADED_YEAR, "This year", TvChannel.UPLOADED_MONTH, "This month",
                TvChannel.UPLOADED_WEEK, "This week", TvChannel.UPLOADED_TODAY, "Today"));

        mDialog.appendRadioCategory("Order", options(channel.order, value -> {
            channel.order = value;
            searchChanged(channel, null);
        }, TvChannel.ORDER_MIXED, "Mixed", TvChannel.ORDER_TRENDING, "Trending this week", TvChannel.ORDER_VIEWS, "Most viewed",
                TvChannel.ORDER_NEW, "Newest first"));

        mDialog.appendRadioCategory("Search YouTube for", options(channel.type, value -> {
            channel.type = value;
            searchChanged(channel, null);
        }, TvChannel.TYPE_VIDEO, "Videos", TvChannel.TYPE_MOVIE, "Movies"));

        mDialog.appendRadioCategory("Shortest video", options(String.valueOf(channel.minMinutes), value -> {
            channel.minMinutes = Integer.parseInt(value);
            searchChanged(channel, null);
        }, "0", "Any", "10", "10 min", "30", "30 min", "60", "1 hour", "90", "1 hour 30 min"));

        // YouTube's own "Features" filters. Every ticked one must be true of a video.
        List<OptionItem> features = new ArrayList<>();
        for (String[] feature : FEATURES) {
            final String code = feature[0];
            features.add(UiOptionItem.from(feature[1], option -> {
                channel.features.remove(code);
                if (option.isSelected()) {
                    channel.features.add(code);
                }
                searchChanged(channel, null);
            }, channel.features.contains(code)));
        }
        mDialog.appendCheckedCategory("Features (" + channel.features.size() + ")", features);

        mDialog.appendSingleSwitch(UiOptionItem.from("Also look in playlists", option -> {
            channel.playlists = option.isSelected();
            searchChanged(channel, null);
        }, channel.playlists));

        mDialog.appendSingleSwitch(UiOptionItem.from("Skip Shorts", option -> {
            channel.noShorts = option.isSelected();
            searchChanged(channel, null);
        }, channel.noShorts));

        // Skip words: videos with one of these in the title are left out.
        List<OptionItem> words = new ArrayList<>();
        for (String word : channel.skipWords) {
            words.add(UiOptionItem.from("Remove: " + word, option -> {
                channel.skipWords.remove(word);
                searchChanged(channel, "No longer skipping \"" + word + "\"");
            }));
        }
        words.add(UiOptionItem.from("+  Type a word", option -> {
            mDialog.closeDialog();
            SimpleEditDialog.show(mContext, "Word to skip on " + channel.name, "", value -> {
                addSkipWord(channel, value);
                return true;
            });
        }));
        for (String word : COMMON_SKIP_WORDS) {
            if (!channel.skipWords.contains(word)) {
                words.add(UiOptionItem.from("Add: " + word, option -> addSkipWord(channel, word)));
            }
        }
        mDialog.appendStringsCategory("Skip words (" + channel.skipWords.size() + ")", words);

        if (!channel.blocked.isEmpty()) {
            List<OptionItem> blocked = new ArrayList<>();
            for (String author : channel.blocked) {
                blocked.add(UiOptionItem.from("Unblock: " + author, option -> {
                    channel.blocked.remove(author);
                    searchChanged(channel, author + " unblocked");
                }));
            }
            mDialog.appendStringsCategory("Blocked creators (" + channel.blocked.size() + ")", blocked);
        }

        appendEditorEnd(channel);
    }

    /** The program type: picking one sets the filters that suit it. Series turns the channel into playlists in order. */
    private void appendKind(TvChannel channel) {
        List<String> choices = new ArrayList<>(Arrays.asList("", "None"));
        for (String kind : mService.getAllKinds()) {
            choices.add(kind);
            choices.add(kind);
        }
        mDialog.appendRadioCategory("Program type" + (channel.kind.isEmpty() ? "" : ": " + channel.kind), options(channel.kind, value -> {
            mDialog.closeDialog(); // the lines on screen belong to the old type
            mService.setKind(channel, value, this::refreshGuide);
            if (TvChannel.KIND_SERIES.equalsIgnoreCase(value) && channel.series.isEmpty()) {
                askSeries(channel, seriesQuery(channel));
            } else {
                toast(value.isEmpty() ? "No program type" : channel.name + " is now " + value + ", with the filters for it");
            }
        }, choices.toArray(new String[0])));
    }

    /** The lines every channel's editor ends with. */
    private void appendEditorEnd(TvChannel channel) {
        mDialog.appendSingleButton(UiOptionItem.from("Name: " + channel.name, option -> {
            mDialog.closeDialog();
            SimpleEditDialog.show(mContext, "Channel name", channel.name, value -> {
                if (value != null && !value.trim().isEmpty()) {
                    mService.rename(channel, value.trim());
                    refreshGuide();
                }
                return true;
            });
        }));

        // One language, or any. It decides which Language filter in the guide shows the channel.
        List<String> languageChoices = new ArrayList<>(Arrays.asList("", "Any"));
        for (String[] language : TvLanguage.LANGUAGES) {
            languageChoices.add(language[0]);
            languageChoices.add(language[1]);
        }
        mDialog.appendRadioCategory("Language", options(channel.language(), value -> {
            channel.languages.clear();
            channel.languages.add(value.isEmpty() ? TvLanguage.ANY : value);
            searchChanged(channel, null);
        }, languageChoices.toArray(new String[0])));

        // What the channel is about: Action, Mythology, Science... It shows in the channel's line in the guide.
        List<String> genreChoices = new ArrayList<>(Arrays.asList("", "None"));
        for (String genre : mService.getAllGenres()) {
            genreChoices.add(genre);
            genreChoices.add(genre);
        }
        mDialog.appendRadioCategory("Genre", options(channel.genre, value -> {
            mService.setGenre(channel, value);
            refreshGuide();
        }, genreChoices.toArray(new String[0])));

        mDialog.appendRadioCategory("Mode", modeOptions(channel.mode, true, mode -> {
            mService.setMode(channel, mode);
            refreshGuide();
        }));

        mDialog.showDialog("Edit " + (mService.indexOf(channel) + 1) + "  " + channel.name);
    }

    private void addTerm(TvChannel channel, String value) {
        String term = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";

        if (term.isEmpty() || channel.terms.contains(term)) {
            return;
        }

        channel.terms.add(term);
        searchChanged(channel, "Added \"" + term + "\"");
    }

    private void addSkipWord(TvChannel channel, String value) {
        String word = value != null ? value.trim().toLowerCase(Locale.ROOT) : "";

        if (word.isEmpty() || channel.skipWords.contains(word)) {
            return;
        }

        channel.skipWords.add(word);
        searchChanged(channel, "Skipping \"" + word + "\"");
    }

    /** What the channel searches for changed: rebuild what's coming up and show it in the guide. */
    private void searchChanged(TvChannel channel, String message) {
        if (message != null) {
            mDialog.closeDialog(); // the list on screen no longer matches the channel
            toast(message);
        }

        mService.onSearchChanged(channel, this::refreshGuide);
    }

    // ---------------- new channel ----------------

    private void showNewChannel() {
        // Three picks make a channel: what kind of program, what it is about, and the language.
        mDialog.appendSingleButton(UiOptionItem.from("+  Pick a type and a genre", option -> {
            mDialog.closeDialog();
            pickKind();
        }));

        mDialog.appendSingleButton(UiOptionItem.from("+  Type a topic", option -> {
            mDialog.closeDialog();
            SimpleEditDialog.show(mContext, "What should the channel play?", "", value -> {
                String topic = value != null ? value.trim() : "";
                if (!topic.isEmpty()) {
                    create(topic.substring(0, 1).toUpperCase(Locale.ROOT) + topic.substring(1), Collections.singletonList(topic.toLowerCase(Locale.ROOT)));
                }
                return true;
            });
        }));

        // Ready-made topics: one press makes a channel, so nothing has to be typed.
        for (List<String> idea : mService.getIdeas()) {
            final String name = idea.get(0);
            boolean exists = false;

            for (TvChannel channel : mService.getChannels()) {
                exists |= name.equalsIgnoreCase(channel.name);
            }

            if (!exists) {
                mDialog.appendSingleButton(UiOptionItem.from(name, option -> {
                    mDialog.closeDialog();
                    create(name, idea.subList(1, idea.size()));
                }));
            }
        }

        mDialog.showDialog("New channel");
    }

    private void pickKind() {
        for (String kind : mService.getAllKinds()) {
            mDialog.appendSingleButton(UiOptionItem.from(kind, option -> {
                mDialog.closeDialog();
                pickGenre(kind);
            }));
        }

        mDialog.showDialog("New channel: what kind of program?");
    }

    private void pickGenre(String kind) {
        mDialog.appendSingleButton(UiOptionItem.from("+  Type a genre", option -> {
            mDialog.closeDialog();
            SimpleEditDialog.show(mContext, "Genre for the " + kind + " channel", "", value -> {
                String genre = value != null ? value.trim() : "";
                if (!genre.isEmpty()) {
                    pickLanguage(kind, genre.substring(0, 1).toUpperCase(Locale.ROOT) + genre.substring(1));
                }
                return true;
            });
        }));

        for (String genre : mService.getAllGenres()) {
            mDialog.appendSingleButton(UiOptionItem.from(genre, option -> {
                mDialog.closeDialog();
                pickLanguage(kind, genre);
            }));
        }

        mDialog.showDialog("New " + kind + " channel: what is it about?");
    }

    private void pickLanguage(String kind, String genre) {
        mDialog.appendSingleButton(UiOptionItem.from("Any language", option -> {
            mDialog.closeDialog();
            pickOrigin(kind, genre, "");
        }));

        for (String[] language : TvLanguage.LANGUAGES) {
            mDialog.appendSingleButton(UiOptionItem.from(language[1], option -> {
                mDialog.closeDialog();
                pickOrigin(kind, genre, language[0]);
            }));
        }

        mDialog.showDialog("New " + genre + " " + kind + " channel: which language?");
    }

    /** Where the programs are from. Apart from the language: Korean films in English, Hollywood in Hindi. */
    private void pickOrigin(String kind, String genre, String language) {
        List<String> origins = mService.getOrigins();

        if (origins.isEmpty()) {
            build(kind, genre, language, "");
            return;
        }

        mDialog.appendSingleButton(UiOptionItem.from("From anywhere", option -> {
            mDialog.closeDialog();
            build(kind, genre, language, "");
        }));

        for (String origin : origins) {
            mDialog.appendSingleButton(UiOptionItem.from(origin, option -> {
                mDialog.closeDialog();
                build(kind, genre, language, origin);
            }));
        }

        mDialog.showDialog("New " + genre + " " + kind + " channel: from where?");
    }

    private void build(String kind, String genre, String language, String origin) {
        TvChannel channel = mService.build(kind, genre, language, origin, this::refreshGuide);
        toast("Channel " + mService.getChannels().size() + " added: " + channel.name);

        if (channel.isSeries()) { // a series channel plays the playlists that get ticked next
            askSeries(channel, seriesQuery(channel));
        }
    }

    private void create(String name, List<String> terms) {
        mService.create(name, terms, this::refreshGuide);
        toast("Channel " + mService.getChannels().size() + " added: " + name);
    }

    // ---------------- helpers ----------------

    /** values: pairs of value, label. */
    private static List<OptionItem> options(String current, Setter setter, String... values) {
        List<OptionItem> result = new ArrayList<>();

        for (int i = 0; i + 1 < values.length; i += 2) {
            final String value = values[i];
            result.add(UiOptionItem.from(values[i + 1], option -> setter.set(value), value.equals(current)));
        }

        return result;
    }

    private List<OptionItem> modeOptions(String current, boolean withDefault, Setter setter) {
        List<String> values = new ArrayList<>();

        if (withDefault) {
            values.add(TvChannel.MODE_DEFAULT);
            values.add("Default (" + (TvChannel.MODE_LIVE.equals(mService.getDefaultMode()) ? "Live TV" : "Free mode") + ")");
        }

        values.addAll(Arrays.asList(TvChannel.MODE_LIVE, "Live TV", TvChannel.MODE_FREE, "Free mode"));

        return options(current, setter, values.toArray(new String[0]));
    }

    /** A line that opens a one-item list, so a stray OK can't delete anything. */
    private void appendConfirm(String title, String yesLabel, Runnable action) {
        List<OptionItem> confirm = new ArrayList<>();
        confirm.add(UiOptionItem.from(yesLabel, option -> {
            mDialog.closeDialog();
            action.run();
        }));
        mDialog.appendStringsCategory(title, confirm);
    }

    private void toast(String message) {
        MessageHelpers.showMessage(mContext, message);
    }

    /** Rebuilds the guide rows, if the guide is what's on screen. */
    private void refreshGuide() {
        BrowsePresenter browse = BrowsePresenter.instance(mContext);
        BrowseSection section = browse.getCurrentSection();

        if (section != null && section.getId() == TvModeService.SECTION_ID && browse.getView() != null) {
            browse.refresh(false);
        }

        browse.updateHomeResumeRow(); // Home's "Continue watching" is the same list
    }

    // ---------------- Add a playlist to a TV channel ----------------

    /** From SmartTube's own menus: a playlist card in search, or a video played from a playlist. */
    public static void showAddToChannel(Context context, String playlistId, String title, String author, int fromIndex) {
        new TvModeMenu(context).showAddToChannel(playlistId, title, author, fromIndex);
    }

    /** The Free Series channels, and a new one. fromIndex: the video to start at, from 0. */
    private void showAddToChannel(String playlistId, String title, String author, int fromIndex) {
        final String name = title != null && !title.isEmpty() ? title : "this playlist";
        final String from = fromIndex > 0 ? ", from video " + (fromIndex + 1) : "";

        mDialog.appendSingleButton(UiOptionItem.from("New Series channel" + from, option -> {
            mDialog.closeDialog();
            TvChannel made = mService.addPlaylistToChannel(null, playlistId, title, author, fromIndex, this::refreshGuide);
            toast("New channel " + (mService.indexOf(made) + 1) + ": " + made.name);
            refreshGuide();
        }));

        for (TvChannel channel : mService.freeSeriesChannels()) {
            final boolean has = channel.findSeries(playlistId) != null;
            String label = "CH " + (mService.indexOf(channel) + 1) + "  " + channel.name + (has ? "  (has it already)" : "");

            mDialog.appendSingleButton(UiOptionItem.from(label, option -> {
                mDialog.closeDialog();
                if (has) {
                    toast(channel.name + " already plays this playlist");
                    return;
                }
                mService.addPlaylistToChannel(channel, playlistId, title, author, fromIndex, this::refreshGuide);
                toast("Added to " + channel.name + from);
                refreshGuide();
            }));
        }

        mDialog.showDialog("Add \"" + name + "\" to a TV channel");
    }
}

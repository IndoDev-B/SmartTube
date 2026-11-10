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

        if (TvModeService.isAction(item)) {
            if (TvModeService.ACTION_SETTINGS.equals(item.tvChannelId)) {
                menu.showSettings();
            } else if (TvModeService.ACTION_SHUFFLE_ALL.equals(item.tvChannelId)) {
                menu.mService.shuffleAll(menu::refreshGuide);
                menu.toast("New lineups on every channel");
            } else if (TvModeService.ACTION_NEW_CHANNEL.equals(item.tvChannelId)) {
                menu.showNewChannel();
            }
            return true;
        }

        TvChannel channel = menu.mService.findChannel(item);

        if (channel == null || !menu.mService.isLive(channel)) {
            return false;
        }

        // Live TV: only what's on now can be watched, and it starts where the clock is.
        Video onNow = menu.mService.tune(channel);

        if (onNow == null || onNow.videoId == null || onNow.videoId.equals(item.videoId)) {
            return false;
        }

        int index = channel.indexOf(item.videoId);

        if (index > channel.pos) {
            menu.toast("Starts at " + DateFormat.getTimeFormat(context).format(new Date(menu.mService.liveStartOf(channel, index))));
        } else {
            menu.toast("Already played");
        }

        return true;
    }

    /** A card in the guide was long pressed. True = handled here. */
    public static boolean onLongClicked(Context context, Video item) {
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

        mDialog.appendSingleSwitch(UiOptionItem.from("Favourites only",
                option -> mService.setFavOnly(option.isSelected()), mService.isFavOnly()));

        mDialog.appendRadioCategory("When I open TV", options(mService.getOpenMode(), mService::setOpenMode,
                TvModeService.OPEN_GUIDE, "Show the guide", TvModeService.OPEN_LAST, "Play the last channel"));

        mDialog.appendRadioCategory("Switching effect", options(mService.getSwitchEffect(), mService::setSwitchEffect,
                TvModeService.FX_STATIC, "TV static", TvModeService.FX_VHS, "VHS preview", TvModeService.FX_BLACK, "Black screen", TvModeService.FX_NONE, "Loading circle"));

        mDialog.appendSingleSwitch(UiOptionItem.from("Retro TV scanlines",
                option -> mService.setScanlinesEnabled(option.isSelected()), mService.isScanlinesEnabled()));

        List<OptionItem> sleep = new ArrayList<>();
        for (int minutes : SLEEP_MINUTES) {
            sleep.add(UiOptionItem.from(minutes == 0 ? "Off" : minutes + " minutes",
                    option -> mService.setSleepTimer(minutes), mService.getSleepMinutes() == minutes));
        }
        mDialog.appendRadioCategory("Sleep timer", sleep);

        mDialog.appendSingleSwitch(UiOptionItem.from("Sleep timer turns the TV off", option -> {
            if (!mService.setSleepTurnsOff(mContext, option.isSelected())) {
                toast("This TV doesn't allow it. The screen will dim instead.");
            }
        }, mService.isSleepTurnsOff()));

        mDialog.appendSingleSwitch(UiOptionItem.from("Save to YouTube history",
                option -> mService.setYtHistoryEnabled(option.isSelected()), mService.isYtHistoryEnabled()));

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

        mDialog.appendSingleButton(UiOptionItem.from("Edit channel", option -> showEditor(channel)));

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

    // ---------------- channel editor ----------------

    private void showEditor(TvChannel channel) {
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
        }, TvChannel.ANY, "Any time", TvChannel.UPLOADED_YEAR, "This year", TvChannel.UPLOADED_MONTH, "This month"));

        mDialog.appendRadioCategory("Order", options(channel.order, value -> {
            channel.order = value;
            searchChanged(channel, null);
        }, TvChannel.ORDER_MIXED, "Mixed", TvChannel.ORDER_NEW, "Newest first", TvChannel.ORDER_VIEWS, "Most viewed"));

        mDialog.appendRadioCategory("Quality", options(channel.quality, value -> {
            channel.quality = value;
            searchChanged(channel, null);
        }, TvChannel.ANY, "Any", TvChannel.QUALITY_4K, "4K only"));

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
    }
}

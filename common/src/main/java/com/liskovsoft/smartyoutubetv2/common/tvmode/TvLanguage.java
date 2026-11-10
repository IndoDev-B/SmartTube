package com.liskovsoft.smartyoutubetv2.common.tvmode;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Guesses the language of a video from its title, for TV mode's language filter.<br/>
 * YouTube doesn't say what language a search result is in, so this goes by what the title shows:
 * the script it is written in, a language named in it ("in Hindi", "Tamil dubbed"), and the small
 * common words of languages that share the Latin alphabet. A title in plain Latin letters with
 * no other sign counts as English.<br/>
 * It can't tell languages apart that share a script and no hint in the title (Hindi and Marathi,
 * Urdu and Arabic), and it can't hear the audio.
 */
public final class TvLanguage {
    /** Channel setting: any language, whatever TV settings say. */
    public static final String ANY = "*";
    /** code, name on screen, word added to a search to find videos in that language */
    public static final String[][] LANGUAGES = {
            {"en", "English", ""},
            {"hi", "Hindi", "hindi"},
            {"ta", "Tamil", "tamil"},
            {"te", "Telugu", "telugu"},
            {"kn", "Kannada", "kannada"},
            {"ml", "Malayalam", "malayalam"},
            {"bn", "Bengali", "bangla"},
            {"mr", "Marathi", "marathi"},
            {"gu", "Gujarati", "gujarati"},
            {"pa", "Punjabi", "punjabi"},
            {"ar", "Urdu / Arabic", "urdu"},
            {"es", "Spanish", "español"},
            {"pt", "Portuguese", "português"},
            {"fr", "French", "français"},
            {"de", "German", "deutsch"},
            {"it", "Italian", "italiano"},
            {"ru", "Russian", "на русском"},
            {"ja", "Japanese", "日本語"},
            {"ko", "Korean", "한국어"},
            {"zh", "Chinese", "中文"},
            {"id", "Indonesian", "indonesia"},
            {"tr", "Turkish", "türkçe"},
            {"th", "Thai", "ภาษาไทย"},
            {"vi", "Vietnamese", "tiếng việt"},
    };

    /** A language named in the title. Longer names first, so "portuguese" isn't read as something shorter. */
    private static final String[][] NAMED = {
            {"hi", "hindi"}, {"ta", "tamil"}, {"te", "telugu"}, {"kn", "kannada"}, {"ml", "malayalam"},
            {"bn", "bengali"}, {"bn", "bangla"}, {"mr", "marathi"}, {"gu", "gujarati"}, {"pa", "punjabi"},
            {"ar", "urdu"}, {"ar", "arabic"}, {"es", "español"}, {"es", "spanish"}, {"es", "castellano"},
            {"pt", "português"}, {"pt", "portuguese"}, {"fr", "français"}, {"de", "deutsch"},
            {"it", "italiano"}, {"id", "bahasa indonesia"}, {"tr", "türkçe"},
    };

    private static final Set<String> EN = words("the of and to in is for with on how what why this that you your from at by are was it its an be can will who when we my best most new full top vs");
    private static final Set<String> ES = words("el los las del que una por para con es cómo más como sus lo se al su esta este muy todo sobre mejores mejor");
    private static final Set<String> PT = words("os das dos em um uma para com não é você como mais ao seu sua isso muito sobre melhores melhor");
    private static final Set<String> FR = words("le les des du et est une pour dans avec sur au qui ce cette comment plus pas vous nous tout sont meilleur");
    private static final Set<String> DE = words("der und ist ein eine für mit von im auf nicht wie das den dem sich auch zum zur wir ich beste");
    private static final Set<String> IT = words("il gli di che è per una della nel come più sono questo questa alla dei delle migliori");
    private static final Set<String> ID = words("yang dan ini itu dengan untuk dari cara tidak ada akan bisa saya kami juga pada adalah");
    private static final Set<String> TR = words("ve bir bu için ile nasıl çok daha en ne gibi kadar var");
    /** Hindi written in Latin letters, the way a great many Hindi titles are. */
    private static final Set<String> HI = words("hai kya ka ki ke mein nahi kaise aur ko se bhi yeh kyun hain tha thi kar raha rahi hoga apna apne sab kuch liye wala wali");

    private TvLanguage() {
    }

    private static Set<String> words(String list) {
        return new HashSet<>(Arrays.asList(list.split(" ")));
    }

    public static String nameOf(String code) {
        for (String[] language : LANGUAGES) {
            if (language[0].equals(code)) {
                return language[1];
            }
        }

        return code;
    }

    /** The word to add to a search to find videos in this language. Empty for English. */
    public static String searchWord(String code) {
        for (String[] language : LANGUAGES) {
            if (language[0].equals(code)) {
                return language[2];
            }
        }

        return "";
    }

    /** The language code a title looks like, or null when the title gives nothing to go by. */
    public static String detect(String title) {
        if (title == null) {
            return null;
        }

        // 1. The script it is written in.
        int latin = 0;
        int[] other = new int[SCRIPTS.length];
        int kana = 0;

        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);

            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= 0x00C0 && c <= 0x024F)) {
                latin++;
            } else if (c >= 0x3040 && c <= 0x30FF) {
                kana++;
            } else if (c > 0x036F) {
                for (int s = 0; s < SCRIPTS.length; s++) {
                    if (c >= SCRIPT_FROM[s] && c <= SCRIPT_TO[s]) {
                        other[s]++;
                        break;
                    }
                }
            }
        }

        if (kana >= 2) {
            return "ja";
        }

        int best = -1;

        for (int s = 0; s < SCRIPTS.length; s++) {
            if (other[s] >= 2 && (best == -1 || other[s] > other[best])) {
                best = s;
            }
        }

        String lower = title.toLowerCase(Locale.ROOT);

        if (best != -1) {
            // Marathi shares its script with Hindi, so only the title naming it tells them apart.
            return "hi".equals(SCRIPTS[best]) && lower.contains("marathi") ? "mr" : SCRIPTS[best];
        }

        if (latin < 3) {
            return null; // numbers, symbols or emoji only
        }

        // 2. A language named in the title: "in Hindi", "Tamil dubbed", "en español".
        for (String[] named : NAMED) {
            if (lower.contains(named[1])) {
                return named[0];
            }
        }

        // 3. Latin letters: the small common words give the language away.
        if (lower.indexOf('ñ') >= 0 || lower.indexOf('¿') >= 0 || lower.indexOf('¡') >= 0) {
            return "es";
        }
        if (lower.indexOf('ã') >= 0 || lower.indexOf('õ') >= 0) {
            return "pt";
        }
        if (lower.indexOf('ß') >= 0) {
            return "de";
        }
        if (lower.indexOf('ğ') >= 0 || lower.indexOf('ş') >= 0 || lower.indexOf('ı') >= 0) {
            return "tr";
        }
        if (lower.indexOf('ư') >= 0 || lower.indexOf('ơ') >= 0 || lower.indexOf('đ') >= 0
                || hasVietnameseTone(lower)) {
            return "vi";
        }

        String[] tokens = lower.split("[^\\p{L}]+");
        int en = count(tokens, EN);
        String[] codes = {"es", "pt", "fr", "de", "it", "id", "tr", "hi"};
        Set<?>[] sets = {ES, PT, FR, DE, IT, ID, TR, HI};
        String code = "en";
        int most = 1; // one stray word isn't enough: "la", "de" and "die" turn up in English titles too

        for (int i = 0; i < codes.length; i++) {
            int hits = count(tokens, sets[i]);
            if (hits > most && hits > en) {
                most = hits;
                code = codes[i];
            }
        }

        return code;
    }

    private static boolean hasVietnameseTone(String lower) {
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c >= 0x1EA0 && c <= 0x1EF9) { // letters with Vietnamese tone marks
                return true;
            }
        }

        return false;
    }

    private static int count(String[] tokens, Set<?> words) {
        int hits = 0;

        for (String token : tokens) {
            if (words.contains(token)) {
                hits++;
            }
        }

        return hits;
    }

    private static final String[] SCRIPTS = {"hi", "bn", "pa", "gu", "ta", "te", "kn", "ml", "th", "ar", "ar", "he", "el", "ru", "zh", "ko"};
    private static final int[] SCRIPT_FROM = {0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B80, 0x0C00, 0x0C80, 0x0D00, 0x0E00, 0x0600, 0x0750, 0x0590, 0x0370, 0x0400, 0x4E00, 0xAC00};
    private static final int[] SCRIPT_TO = {0x097F, 0x09FF, 0x0A7F, 0x0AFF, 0x0BFF, 0x0C7F, 0x0CFF, 0x0D7F, 0x0E7F, 0x06FF, 0x077F, 0x05FF, 0x03FF, 0x04FF, 0x9FFF, 0xD7AF};
}

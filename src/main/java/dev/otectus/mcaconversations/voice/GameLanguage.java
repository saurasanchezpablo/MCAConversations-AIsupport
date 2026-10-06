package dev.otectus.mcaconversations.voice;

import java.util.Locale;
import java.util.Map;

/**
 * A Minecraft language code ({@code es_es}, {@code de_de}, {@code zh_tw}, {@code pt_br}) as the name of a
 * language a model or a speech engine understands ("Spanish (Spain)", "German", "Traditional Chinese").
 * Covers every code the game ships: real languages through {@link Locale}, and the game's joke and
 * constructed languages by hand. Pure.
 */
public final class GameLanguage {

    /** Codes {@link Locale} does not name, or names in a way that would mislead a model. */
    private static final Map<String, String> SPECIAL = Map.ofEntries(
            Map.entry("zh_cn", "Simplified Chinese"),
            Map.entry("zh_tw", "Traditional Chinese (Taiwan)"),
            Map.entry("zh_hk", "Traditional Chinese (Hong Kong, Cantonese wording)"),
            Map.entry("lzh", "Classical Chinese"),
            // The game's playful variants of English are still English to a model.
            Map.entry("en_pt", "English (pirate speak)"),
            Map.entry("en_ud", "English"),
            Map.entry("lol_us", "English (LOLCAT)"),
            Map.entry("enp", "English (Anglish)"),
            Map.entry("enws", "English (Shakespearean)"),
            Map.entry("esan", "Spanish (Andalusian)"),
            Map.entry("val_es", "Valencian"),
            Map.entry("sr_cs", "Serbian (Latin script)"),
            Map.entry("sr_sp", "Serbian (Cyrillic script)"),
            Map.entry("ry_ua", "Rusyn"),
            Map.entry("isv", "Interslavic"),
            Map.entry("tok", "Toki Pona"),
            Map.entry("qya_aa", "Quenya"),
            Map.entry("tlh_aa", "Klingon"),
            Map.entry("jbo_en", "Lojban"),
            Map.entry("io_en", "Ido"),
            Map.entry("la_la", "Latin"),
            Map.entry("bar", "Bavarian German"),
            Map.entry("ksh", "Kölsch"),
            Map.entry("nds_de", "Low German"),
            Map.entry("fra_de", "East Franconian German"),
            Map.entry("sxu", "Upper Saxon German"),
            Map.entry("szl", "Silesian"),
            Map.entry("lmo", "Lombard"),
            Map.entry("vec_it", "Venetian"),
            Map.entry("fur_it", "Friulian"),
            Map.entry("nah", "Nahuatl"),
            Map.entry("ovd", "Elfdalian"),
            Map.entry("rpr", "Russian (pre-revolutionary spelling)"),
            Map.entry("sah_sah", "Yakut"),
            Map.entry("zlm_arab", "Malay (Jawi script)"),
            Map.entry("brb", "Brabantian"),
            Map.entry("hn_no", "Norwegian (Høgnorsk)"));

    /** Languages the game ships in several regional forms: for these the region is named too. */
    private static final java.util.Set<String> REGIONAL = java.util.Set.of("es", "pt", "en", "fr", "de", "nl");

    private GameLanguage() {
    }

    /** The code in canonical form: lower case, {@code -} as {@code _}; {@code en_us} when blank. */
    public static String normalise(String code) {
        String c = code == null ? "" : code.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return c.isEmpty() ? "en_us" : c;
    }

    /** The two- or three-letter language part ({@code es} for {@code es_mx}). */
    public static String base(String code) {
        String c = normalise(code);
        int cut = c.indexOf('_');
        return cut < 0 ? c : c.substring(0, cut);
    }

    /**
     * The language's English name, with the region where the game has several ("Spanish (Mexico)"),
     * or null when the code is not a language anyone could name.
     */
    public static String name(String code) {
        String c = normalise(code);
        String special = SPECIAL.get(c);
        if (special != null) {
            return special;
        }
        String base = base(c);
        Locale locale = Locale.forLanguageTag(c.replace('_', '-'));
        String language = locale.getDisplayLanguage(Locale.ENGLISH);
        if (language.isBlank() || language.equalsIgnoreCase(base)) {
            return null; // Locale does not know it: better no name than a made-up one
        }
        String country = locale.getDisplayCountry(Locale.ENGLISH);
        if (REGIONAL.contains(base) && !country.isBlank() && !country.equalsIgnoreCase(locale.getCountry())) {
            return language + " (" + country + ")";
        }
        return language;
    }

    /** The language's name without the region ("Spanish" for {@code es_mx}), or null. */
    public static String plainName(String code) {
        String full = name(code);
        if (full == null) {
            return null;
        }
        int paren = full.indexOf(" (");
        return paren < 0 ? full : full.substring(0, paren);
    }
}

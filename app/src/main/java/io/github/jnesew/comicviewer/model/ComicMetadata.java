package io.github.jnesew.comicviewer.model;

import io.github.jnesew.comicviewer.util.InputLimits;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional publication hints. Decoded pages and explicit reader/library choices remain authoritative. */
public final class ComicMetadata {
    public static final ComicMetadata EMPTY = new ComicMetadata(Collections.emptyMap(), Collections.emptyList());
    public enum Manga { UNKNOWN, NO, YES, RIGHT_TO_LEFT }
    public record Page(int image, String type, boolean doublePage) { }

    public final String title;
    public final SeriesMetadata series;
    public final int volume;
    public final int year;
    public final int month;
    public final int day;
    public final String summary;
    public final Map<String, String> creators;
    public final String publisher;
    public final String language;
    public final Manga manga;
    public final List<Page> pages;

    public ComicMetadata(Map<String, String> fields, List<Page> pages) {
        title = text(fields, "Title", InputLimits.MAX_TITLE_CODE_POINTS);
        series = new SeriesMetadata(fields.get("Series"), fields.get("Number"));
        volume = integer(fields.get("Volume"), 0, Integer.MAX_VALUE);
        year = integer(fields.get("Year"), 1, 9999);
        month = integer(fields.get("Month"), 1, 12);
        int parsedDay = integer(fields.get("Day"), 1, 31);
        if (parsedDay > 0 && month > 0) {
            try { LocalDate.of(year > 0 ? year : 2000, month, parsedDay); }
            catch (DateTimeException invalid) { parsedDay = -1; }
        }
        day = parsedDay;
        summary = text(fields, "Summary", 4096);
        LinkedHashMap<String, String> roles = new LinkedHashMap<>();
        for (String role : new String[]{"Writer", "Penciller", "Inker", "Colorist", "Letterer",
                "CoverArtist", "Editor", "Translator"}) {
            String value = text(fields, role, InputLimits.MAX_TITLE_CODE_POINTS);
            if (!value.isEmpty()) roles.put(role, value);
        }
        creators = Collections.unmodifiableMap(roles);
        publisher = text(fields, "Publisher", InputLimits.MAX_TITLE_CODE_POINTS);
        language = text(fields, "LanguageISO", 128);
        String value = text(fields, "Manga", 32);
        manga = "YesAndRightToLeft".equalsIgnoreCase(value) ? Manga.RIGHT_TO_LEFT
                : "Yes".equalsIgnoreCase(value) ? Manga.YES
                : "No".equalsIgnoreCase(value) ? Manga.NO : Manga.UNKNOWN;
        this.pages = Collections.unmodifiableList(new java.util.ArrayList<>(pages));
    }

    /** Bounded publication fields for the library cache; page hints stay with the opened document. */
    public Map<String, String> publicationFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Title", title); fields.put("Series", series.name); fields.put("Number", series.number);
        fields.put("Volume", Integer.toString(volume)); fields.put("Year", Integer.toString(year));
        fields.put("Month", Integer.toString(month)); fields.put("Day", Integer.toString(day));
        fields.put("Summary", summary); fields.putAll(creators);
        fields.put("Publisher", publisher); fields.put("LanguageISO", language);
        fields.put("Manga", manga == Manga.RIGHT_TO_LEFT ? "YesAndRightToLeft" :
                manga == Manga.YES ? "Yes" : manga == Manga.NO ? "No" : "Unknown");
        return Collections.unmodifiableMap(fields);
    }

    public int coverPage(int pageCount) {
        for (Page page : pages) {
            if (page.image >= 0 && page.image < pageCount && "FrontCover".equalsIgnoreCase(page.type)) {
                return page.image;
            }
        }
        return 0;
    }

    public static int integer(String value, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.trim());
            return parsed >= minimum && parsed <= maximum ? parsed : -1;
        } catch (NumberFormatException invalid) { return -1; }
    }

    private static String text(Map<String, String> fields, String key, int maximum) {
        return InputLimits.normalizeText(fields.get(key), maximum);
    }
}

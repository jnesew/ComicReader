package io.github.jnesew.comicviewer.ui;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.model.ComicMetadata;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.Ui;
import java.util.Map;

/** Localized publication details shared by the series overview and reader. */
public final class PublicationDetails {
    private PublicationDetails() { }

    public static boolean hasFields(ComicMetadata metadata) {
        return !metadata.creators.isEmpty() || !metadata.summary.isEmpty() ||
                !metadata.publisher.isEmpty() || !metadata.language.isEmpty() ||
                metadata.volume >= 0 || metadata.year > 0;
    }

    public static String issueLabel(Context context, ReadingProgress item, ComicMetadata metadata) {
        String name = item.titleOverride ? item.title : metadata.title;
        if (item.seriesNumber.trim().isEmpty()) return name.isEmpty() ? item.title : name;
        String number = context.getString(R.string.comic_issue_number, item.seriesNumber);
        return name.isEmpty() ? number : context.getString(R.string.series_issue_title, number, name);
    }

    public static String creditText(Context context, Map.Entry<String, String> creator) {
        int label = switch (creator.getKey()) {
            case "Writer" -> R.string.series_writer; case "Penciller" -> R.string.series_penciller;
            case "Inker" -> R.string.series_inker; case "Colorist" -> R.string.series_colorist;
            case "Letterer" -> R.string.series_letterer; case "CoverArtist" -> R.string.series_cover_artist;
            case "Editor" -> R.string.series_editor; default -> R.string.series_translator;
        };
        return context.getString(label, creator.getValue());
    }

    public static void addFields(LinearLayout container, ComicMetadata metadata, boolean creditsFirst) {
        Context context = container.getContext();
        if (!creditsFirst) addSummary(container, metadata);
        for (Map.Entry<String, String> creator : metadata.creators.entrySet()) add(container, creditText(context, creator));
        if (!metadata.publisher.isEmpty()) add(container, context.getString(R.string.series_publisher, metadata.publisher));
        if (!metadata.language.isEmpty()) add(container, context.getString(R.string.series_language, metadata.language));
        if (metadata.volume >= 0) add(container, context.getString(R.string.series_volume, metadata.volume));
        if (metadata.year > 0) {
            String date = Integer.toString(metadata.year);
            if (metadata.month > 0) date += String.format(java.util.Locale.ROOT, "-%02d", metadata.month);
            if (metadata.month > 0 && metadata.day > 0) date += String.format(java.util.Locale.ROOT, "-%02d", metadata.day);
            add(container, context.getString(R.string.series_publication_date, date));
        }
        if (creditsFirst) addSummary(container, metadata);
    }

    private static void addSummary(LinearLayout container, ComicMetadata metadata) {
        if (metadata.summary.isEmpty()) return;
        TextView value = add(container, metadata.summary);
        value.setTextSize(14);
        value.setPadding(0, Ui.dp(container.getContext(), 12), 0, Ui.dp(container.getContext(), 8));
    }

    private static TextView add(LinearLayout container, String text) {
        TextView value = Ui.text(container.getContext(), text, 13, Ui.textMuted(container.getContext()));
        value.setPadding(0, Ui.dp(container.getContext(), 6), 0, 0);
        container.addView(value);
        return value;
    }
}

package io.github.jnesew.comicviewer.ui.dialog;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.model.ComicMetadata;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.ui.PublicationDetails;
import io.github.jnesew.comicviewer.util.Ui;

/** A snapshot of the active issue; does not open documents or change reading state. */
public final class ComicDetailsDialog {
    public AlertDialog create(Context context, ReadingProgress issue, ComicMetadata metadata) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(context, 24), Ui.dp(context, 8), Ui.dp(context, 24), Ui.dp(context, 20));
        PublicationDetails.addFields(content, metadata, true);
        if (!PublicationDetails.hasFields(metadata)) {
            content.addView(Ui.text(context, context.getString(R.string.comic_no_publication_details), 14, Ui.textMuted(context)));
        }
        ScrollView scroll = new ScrollView(context); scroll.addView(content);
        return new AlertDialog.Builder(context)
                .setTitle(PublicationDetails.issueLabel(context, issue, metadata))
                .setView(scroll).setPositiveButton(R.string.close, null).create();
    }
}

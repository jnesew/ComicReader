package io.github.jnesew.comicviewer.ui.dialog;

import android.app.AlertDialog;
import android.content.Context;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.data.ReaderPreferences;
import java.util.Arrays;

/** Library appearance choices; the activity owns applying the selected theme. */
public final class AppearanceDialog {
    private AppearanceDialog() { }

    public static void show(Context context, ReaderPreferences preferences, Runnable onChanged) {
        String[] modes = {"system", "light", "dark"};
        String[] labels = {context.getString(R.string.appearance_system),
                context.getString(R.string.appearance_light), context.getString(R.string.appearance_dark)};
        int checked = Arrays.asList(modes).indexOf(preferences.appearance());
        new AlertDialog.Builder(context).setTitle(R.string.appearance)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    preferences.setAppearance(modes[which]);
                    dialog.dismiss();
                    onChanged.run();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }
}

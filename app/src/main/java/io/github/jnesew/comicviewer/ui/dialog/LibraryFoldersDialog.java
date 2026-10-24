package io.github.jnesew.comicviewer.ui.dialog;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.library.FolderScanCoordinator;
import io.github.jnesew.comicviewer.model.LibraryFolder;
import io.github.jnesew.comicviewer.util.Ui;
import java.util.List;
import java.util.function.Function;

/** Presentation of explicitly selected folders; the Activity owns grants and mutations. */
public final class LibraryFoldersDialog {
    public interface Listener {
        void onAdd();
        void onRescan();
        void onRemove(LibraryFolder folder);
    }
    private final Context context;
    private final Listener listener;
    private final AlertDialog dialog;
    private final LinearLayout rows;
    private final ScrollView scroll;

    public LibraryFoldersDialog(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), Ui.dp(context, 8));
        scroll = new ScrollView(context);
        scroll.addView(rows);
        dialog = new AlertDialog.Builder(context).setTitle(R.string.library_folders)
                .setView(scroll).setNegativeButton(R.string.close, null)
                .setPositiveButton(R.string.library_add_folder, null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.dismiss(); listener.onAdd();
            });
        });
    }

    public void show(List<LibraryFolder> folders,
            Function<String, FolderScanCoordinator.FolderState> states) {
        refresh(folders, states);
        dialog.show();
        refresh(folders, states);
    }

    public boolean isShowing() { return dialog.isShowing(); }
    public void dismiss() { dialog.dismiss(); }

    public void refresh(List<LibraryFolder> folders,
            Function<String, FolderScanCoordinator.FolderState> states) {
        int position = scroll.getScrollY();
        rows.removeAllViews();
        if (!folders.isEmpty()) {
            TextView rescan = Ui.actionButton(context, R.string.library_rescan_all_folders, R.drawable.ic_folder);
            rescan.setOnClickListener(view -> listener.onRescan());
            LinearLayout.LayoutParams rescanParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rescanParams.bottomMargin = Ui.dp(context, 12);
            rows.addView(rescan, rescanParams);
        }
        if (folders.isEmpty()) {
            TextView empty = Ui.text(context, context.getString(R.string.library_no_folders), 15, Ui.TEXT_MUTED);
            empty.setPadding(0, Ui.dp(context, 16), 0, Ui.dp(context, 16));
            rows.addView(empty);
        }
        for (LibraryFolder folder : folders) {
            FolderScanCoordinator.FolderState state = states.apply(folder.uri);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 8));
            row.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(context, 12), Ui.SURFACE_HIGH, Ui.dp(context, 1)));
            LinearLayout titleRow = new LinearLayout(context);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            String label = folder.label.isEmpty() ? context.getString(R.string.library_unnamed_folder) : folder.label;
            TextView title = Ui.text(context, label, 16, Ui.TEXT);
            Ui.bold(title);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton remove = Ui.iconButton(context, R.drawable.ic_close,
                    context.getString(R.string.library_remove_folder_named, label));
            remove.setOnClickListener(view -> listener.onRemove(folder));
            titleRow.addView(remove, new LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)));
            row.addView(titleRow);

            String location = folder.location;
            try {
                Uri uri = Uri.parse(folder.uri);
                String documentId = DocumentsContract.getTreeDocumentId(uri);
                // Older selections stored a provider label before the opaque directory ID.
                if (location.isEmpty() || location.endsWith(" · " + documentId)) location = documentId;
            } catch (RuntimeException invalid) {
                if (location.isEmpty()) location = folder.uri;
            }
            TextView secondary = Ui.text(context, location, 12, Ui.TEXT_MUTED);
            secondary.setMaxLines(3);
            row.addView(secondary);
            TextView status = Ui.text(context, state.detail().isEmpty()
                    ? context.getString(R.string.library_folder_ready) : state.detail(), 13,
                    state.unavailable() ? Ui.ACCENT : Ui.TEXT_MUTED);
            status.setPadding(0, Ui.dp(context, 8), 0, 0);
            row.addView(status);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = Ui.dp(context, 12);
            rows.addView(row, params);
        }
        scroll.post(() -> scroll.scrollTo(0, position));
    }
}

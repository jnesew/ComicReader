package io.github.jnesew.comicviewer.ui.dialog;

import android.app.AlertDialog;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.library.FolderScanCoordinator;
import io.github.jnesew.comicviewer.model.LibraryFolder;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.Ui;
import java.util.List;
import java.util.function.Function;

/** Presentation of explicitly selected folders; the Activity owns grants and mutations. */
public final class LibraryFoldersDialog {
    public interface Listener {
        void onAdd();
        void onImportImageFolder();
        void onRescan();
        void onRemove(LibraryFolder folder);
        void onRemoveImageComic(ReadingProgress comic);
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
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout actions = new LinearLayout(context);
        actions.setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), 0);
        Button add = new Button(context);
        add.setText(R.string.library_add_folder);
        add.setOnClickListener(view -> { dismiss(); listener.onAdd(); });
        Button images = new Button(context);
        images.setText(R.string.import_image_folder);
        images.setOnClickListener(view -> { dismiss(); listener.onImportImageFolder(); });
        for (Button button : new Button[]{add, images}) {
            button.setAllCaps(false);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setMinimumHeight(Ui.dp(context, 48));
            button.setTextSize(14);
            button.setPadding(Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10));
        }
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        addParams.setMarginEnd(Ui.dp(context, 8));
        actions.addView(add, addParams);
        actions.addView(images, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(actions);
        content.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(Ui.dp(context, 24), Ui.dp(context, 12), Ui.dp(context, 12), 0);
        TextView title = Ui.text(context, context.getString(R.string.library_folders), 20, Ui.text(context));
        heading.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton help = Ui.iconButton(context, R.drawable.ic_help, context.getString(R.string.library_folders_help));
        help.setOnClickListener(view -> new AlertDialog.Builder(context)
                .setTitle(R.string.library_folders_help).setMessage(R.string.library_folders_help_message)
                .setPositiveButton(R.string.close, null).show());
        heading.addView(help, new LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)));
        dialog = new AlertDialog.Builder(context).setCustomTitle(heading)
                .setView(content).setNegativeButton(R.string.close, null).create();
    }

    public void show(List<LibraryFolder> folders, List<ReadingProgress> imageComics,
            Function<String, FolderScanCoordinator.FolderState> states,
            Function<String, FolderScanCoordinator.FolderState> imageStates) {
        refresh(folders, imageComics, states, imageStates);
        dialog.show();
        refresh(folders, imageComics, states, imageStates);
    }

    public boolean isShowing() { return dialog.isShowing(); }
    public void dismiss() { dialog.dismiss(); }

    public void refresh(List<LibraryFolder> folders, List<ReadingProgress> imageComics,
            Function<String, FolderScanCoordinator.FolderState> states,
            Function<String, FolderScanCoordinator.FolderState> imageStates) {
        int position = scroll.getScrollY();
        rows.removeAllViews();
        if (!folders.isEmpty() || !imageComics.isEmpty()) {
            TextView rescan = Ui.actionButton(context, R.string.library_rescan_all_folders, R.drawable.ic_folder);
            rescan.setOnClickListener(view -> listener.onRescan());
            LinearLayout.LayoutParams rescanParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rescanParams.bottomMargin = Ui.dp(context, 12);
            rows.addView(rescan, rescanParams);
        }
        if (folders.isEmpty() && imageComics.isEmpty()) {
            TextView empty = Ui.text(context, context.getString(R.string.library_no_folders), 15, Ui.textMuted(context));
            empty.setPadding(0, Ui.dp(context, 16), 0, Ui.dp(context, 16));
            rows.addView(empty);
        }
        for (LibraryFolder folder : folders) {
            FolderScanCoordinator.FolderState state = states.apply(folder.uri);
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 8));
            row.setBackground(Ui.rounded(Ui.surface(context), Ui.dp(context, 12), Ui.surfaceHigh(context), Ui.dp(context, 1)));
            LinearLayout titleRow = new LinearLayout(context);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            String label = folder.label.isEmpty() ? context.getString(R.string.library_unnamed_folder) : folder.label;
            TextView title = Ui.text(context, label, 16, Ui.text(context));
            Ui.bold(title);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton remove = Ui.iconButton(context, R.drawable.ic_close,
                    context.getString(R.string.library_remove_folder_named, label));
            remove.setOnClickListener(view -> listener.onRemove(folder));
            titleRow.addView(remove, new LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)));
            row.addView(titleRow);
            row.addView(Ui.text(context, context.getString(R.string.folder_type_library), 13, Ui.accent(context)));

            String location = folder.location;
            try {
                Uri uri = Uri.parse(folder.uri);
                String documentId = DocumentsContract.getTreeDocumentId(uri);
                // Older selections stored a provider label before the opaque directory ID.
                if (location.isEmpty() || location.endsWith(" · " + documentId)) location = documentId;
            } catch (RuntimeException invalid) {
                if (location.isEmpty()) location = folder.uri;
            }
            TextView secondary = Ui.text(context, location, 12, Ui.textMuted(context));
            secondary.setMaxLines(3);
            row.addView(secondary);
            addStatus(row, state);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = Ui.dp(context, 12);
            rows.addView(row, params);
        }
        for (ReadingProgress comic : imageComics) {
            FolderScanCoordinator.FolderState state = imageStates.apply(comic.uri);
            LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 8));
            row.setBackground(Ui.rounded(Ui.surface(context), Ui.dp(context, 12), Ui.surfaceHigh(context), Ui.dp(context, 1)));
            LinearLayout titleRow = new LinearLayout(context); titleRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = Ui.text(context, comic.title, 16, Ui.text(context)); Ui.bold(title);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton remove = Ui.iconButton(context, R.drawable.ic_close, context.getString(R.string.forget_title, comic.title));
            remove.setOnClickListener(view -> listener.onRemoveImageComic(comic));
            titleRow.addView(remove, new LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)));
            row.addView(titleRow);
            row.addView(Ui.text(context, context.getString(R.string.folder_type_image_comic), 13, Ui.accent(context)));
            String location;
            try { location = DocumentsContract.getDocumentId(Uri.parse(comic.uri)); }
            catch (RuntimeException rootTree) {
                try { location = DocumentsContract.getTreeDocumentId(Uri.parse(comic.uri)); }
                catch (RuntimeException invalid) { location = comic.uri; }
            }
            TextView path = Ui.text(context, location, 12, Ui.textMuted(context)); path.setMaxLines(3); row.addView(path);
            addStatus(row, state);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = Ui.dp(context, 12); rows.addView(row, params);
        }
        scroll.post(() -> scroll.scrollTo(0, position));
    }

    private void addStatus(LinearLayout row, FolderScanCoordinator.FolderState state) {
        boolean warning = state.unavailable() || !state.incompleteDetail().isEmpty();
        if (!state.scanning() && !warning) return;
        String detail = state.detail().isEmpty() ? state.incompleteDetail() : state.detail();
        if (detail.isEmpty()) return;
        TextView status = Ui.text(context, detail, 13, warning ? Ui.accent(context) : Ui.textMuted(context));
        status.setPadding(0, Ui.dp(context, 8), 0, 0);
        row.addView(status);
    }
}

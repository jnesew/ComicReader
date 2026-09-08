package io.github.jnesew.comicviewer.ui.dialog;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import io.github.jnesew.comicviewer.util.Ui;
import java.util.List;
import android.content.Context;
import io.github.jnesew.comicviewer.R;
import java.util.function.IntConsumer;

/** Page-selection UI with no session or database ownership. */
public final class ReaderNavigationDialogs {
    private final Context context;
    public ReaderNavigationDialogs(Context context) { this.context = context; }
    public void showJump(int page, int pageCount, IntConsumer onSelected) {
        EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(Integer.toString(page + 1));
        input.selectAll();
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        FrameLayout wrapper = new FrameLayout(context);
        int side = Ui.dp(context, 24);
        wrapper.setPadding(side, Ui.dp(context, 4), side, 0);
        wrapper.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.reader_jump_to_page)
                .setMessage(context.getString(R.string.reader_jump_message, pageCount))
                .setView(wrapper)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.go, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString().trim());
                        if (value < 1 || value > pageCount) {
                            input.setError(context.getString(R.string.reader_page_range_error, pageCount));
                            return;
                        }
                        onSelected.accept(value - 1);
                        dialog.dismiss();
                    } catch (NumberFormatException error) {
                        input.setError(context.getString(R.string.reader_page_number_error));
                    }
                }));
        dialog.show();
        input.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    public void showBookmarks(List<Integer> bookmarks, IntConsumer onSelected) {
        if (bookmarks.isEmpty()) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.reader_bookmarks)
                    .setMessage(R.string.reader_no_bookmarks)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        String[] labels = new String[bookmarks.size()];
        for (int i = 0; i < bookmarks.size(); i++) {
            labels[i] = context.getString(R.string.reader_bookmark_page, bookmarks.get(i) + 1);
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.reader_bookmarks)
                .setItems(labels, (dialog, which) -> onSelected.accept(bookmarks.get(which)))
                .setNegativeButton(R.string.close, null)
                .show();
    }
}

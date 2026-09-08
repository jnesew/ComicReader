package io.github.jnesew.comicviewer.ui.dialog;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.Ui;
import java.util.List;
import android.content.Context;
import io.github.jnesew.comicviewer.R;
import java.util.function.Consumer;

/** Metadata form. The caller owns persistence and reader refresh. */
public final class ComicEditorDialog {
    public record Edit(String uri, String title, int seriesMode, String series, String number) { }
    private final Context context;
    public ComicEditorDialog(Context context) { this.context = context; }
    public void show(ReadingProgress current, List<String> seriesNames, Consumer<Edit> onSave) {
        if (current.uri.isEmpty()) return;
        ScrollView scroll = new ScrollView(context);
        LinearLayout fields = new LinearLayout(context);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(Ui.dp(context, 22), Ui.dp(context, 8), Ui.dp(context, 22), Ui.dp(context, 8));
        scroll.addView(fields);

        TextView titleLabel = editorLabel(R.string.comic_title_label);
        fields.addView(titleLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText title = new EditText(context);
        title.setSingleLine(true);
        title.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        title.setText(current.title);
        fields.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 54)));
        TextView useOriginal = Ui.text(
                context, context.getString(R.string.comic_use_original_title), 14, Ui.ACCENT);
        useOriginal.setGravity(Gravity.CENTER_VERTICAL);
        useOriginal.setPadding(0, 0, 0, Ui.dp(context, 8));
        useOriginal.setClickable(true);
        useOriginal.setOnClickListener(view -> {
            String original = current.originalTitle.isEmpty()
                    ? current.title : current.originalTitle;
            title.setText(original);
            title.setSelection(title.length());
        });
        fields.addView(useOriginal, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 42)));

        TextView groupingLabel = editorLabel(R.string.comic_grouping_label);
        fields.addView(groupingLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        RadioGroup grouping = new RadioGroup(context);
        int automaticId = View.generateViewId();
        int manualId = View.generateViewId();
        int standaloneId = View.generateViewId();
        RadioButton automatic = editorRadio(
                automaticId, R.string.comic_grouping_automatic);
        RadioButton manual = editorRadio(manualId, R.string.comic_grouping_series);
        RadioButton standalone = editorRadio(
                standaloneId, R.string.comic_grouping_standalone);
        grouping.addView(automatic);
        grouping.addView(manual);
        grouping.addView(standalone);
        fields.addView(grouping);

        TextView seriesLabel = editorLabel(R.string.comic_series_label);
        fields.addView(seriesLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AutoCompleteTextView series = new AutoCompleteTextView(context);
        series.setSingleLine(true);
        series.setThreshold(0);
        series.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        series.setAdapter(new ArrayAdapter<>(context,
                android.R.layout.simple_dropdown_item_1line, seriesNames));
        String initialSeries = current.seriesTitle.isEmpty()
                ? current.detectedSeriesName : current.seriesTitle;
        series.setText(initialSeries, false);
        series.setOnClickListener(view -> series.showDropDown());
        fields.addView(series, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 54)));

        TextView numberLabel = editorLabel(R.string.comic_issue_number_label);
        fields.addView(numberLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        EditText number = new EditText(context);
        number.setSingleLine(true);
        number.setInputType(InputType.TYPE_CLASS_TEXT);
        number.setText(current.seriesNumber.isEmpty()
                ? current.detectedSeriesNumber : current.seriesNumber);
        fields.addView(number, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 54)));

        if (current.seriesOverride == LibraryDatabase.SERIES_AUTOMATIC) {
            automatic.setChecked(true);
        } else if (current.seriesOverride == LibraryDatabase.SERIES_STANDALONE) {
            standalone.setChecked(true);
        } else {
            manual.setChecked(true);
        }
        Runnable updateSeriesFields = () -> {
            boolean enabled = grouping.getCheckedRadioButtonId() == manualId;
            series.setEnabled(enabled);
            number.setEnabled(enabled);
            seriesLabel.setEnabled(enabled);
            numberLabel.setEnabled(enabled);
        };
        grouping.setOnCheckedChangeListener((group, checkedId) -> updateSeriesFields.run());
        updateSeriesFields.run();

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.comic_edit_title)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String editedTitle = title.getText().toString().trim();
                    if (editedTitle.isEmpty()) {
                        title.setError(context.getString(R.string.comic_title_required));
                        return;
                    }
                    int seriesMode;
                    if (grouping.getCheckedRadioButtonId() == automaticId) {
                        seriesMode = LibraryDatabase.SERIES_AUTOMATIC;
                    } else if (grouping.getCheckedRadioButtonId() == standaloneId) {
                        seriesMode = LibraryDatabase.SERIES_STANDALONE;
                    } else {
                        seriesMode = LibraryDatabase.SERIES_MANUAL;
                        if (series.getText().toString().trim().isEmpty()) {
                            series.setError(context.getString(R.string.comic_series_required));
                            return;
                        }
                    }
                    onSave.accept(new Edit(current.uri, editedTitle, seriesMode,
                            series.getText().toString(), number.getText().toString()));
                    dialog.dismiss();
                }));
        dialog.show();
    }

    private TextView editorLabel(int text) {
        TextView label = Ui.text(context, context.getString(text), 13, Ui.TEXT_MUTED);
        label.setPadding(0, Ui.dp(context, 8), 0, 0);
        return label;
    }

    private RadioButton editorRadio(int id, int text) {
        RadioButton choice = new RadioButton(context);
        choice.setId(id);
        choice.setText(text);
        choice.setTextColor(Ui.TEXT);
        choice.setTextSize(15);
        choice.setMinHeight(Ui.dp(context, 44));
        choice.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Ui.ACCENT, Ui.TEXT_MUTED}));
        return choice;
    }
}

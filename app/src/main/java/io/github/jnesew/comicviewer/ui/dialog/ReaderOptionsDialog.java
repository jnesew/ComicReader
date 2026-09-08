package io.github.jnesew.comicviewer.ui.dialog;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import io.github.jnesew.comicviewer.data.ReaderPreferences;
import io.github.jnesew.comicviewer.model.OpeningZoomPolicy;
import io.github.jnesew.comicviewer.model.ReadingDirection;
import io.github.jnesew.comicviewer.util.Ui;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import android.content.Context;
import io.github.jnesew.comicviewer.R;
import java.util.function.Consumer;

/** Settings forms emit commands; their caller applies preferences and live reader changes. */
public final class ReaderOptionsDialog {
    public record Options(boolean tapZones, boolean volumeNavigation, boolean rememberZoom,
            String defaultZoom, boolean keepScreenOn, boolean autoHideControls) { }
    public interface Listener {
        void onOptionsSaved(Options options);
        void onThemeSelected(String key, int color);
        void onShortcutSelected(String action, ReaderPreferences.Shortcut shortcut);
        void onShortcutsReset();
    }
    private static final String[] SHORTCUT_ACTIONS = {
            "next", "previous", "next_alt", "previous_alt"
    };
    private final Context context;
    private final ReaderPreferences preferences;
    private final Listener listener;
    public ReaderOptionsDialog(Context context, ReaderPreferences preferences, Listener listener) {
        this.context = context;
        this.preferences = preferences;
        this.listener = listener;
    }
    public void showReadingDirection(String direction, Consumer<String> onSelected) {
        String[] values = {
                ReadingDirection.AUTO,
                ReadingDirection.LEFT_TO_RIGHT,
                ReadingDirection.RIGHT_TO_LEFT
        };
        int[] labels = {
                R.string.reading_direction_auto,
                R.string.reading_direction_left_to_right,
                R.string.reading_direction_right_to_left
        };
        String current = ReadingDirection.normalize(direction);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(context, 22), Ui.dp(context, 4), Ui.dp(context, 22), Ui.dp(context, 4));
        TextView explanation = Ui.text(
                context, context.getString(R.string.reading_direction_message), 14, Ui.TEXT_MUTED);
        explanation.setPadding(0, 0, 0, Ui.dp(context, 8));
        content.addView(explanation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RadioGroup choices = new RadioGroup(context);
        int[] ids = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            RadioButton choice = new RadioButton(context);
            ids[index] = View.generateViewId();
            choice.setId(ids[index]);
            choice.setText(labels[index]);
            choice.setTextColor(Ui.TEXT);
            choice.setTextSize(16);
            choice.setMinHeight(Ui.dp(context, 52));
            choice.setButtonTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{Ui.ACCENT, Ui.TEXT_MUTED}));
            choices.addView(choice, new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 52)));
            if (values[index].equals(current)) choice.setChecked(true);
        }
        content.addView(choices);

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.reader_reading_direction)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .create();
        choices.setOnCheckedChangeListener((group, checkedId) -> {
            for (int index = 0; index < ids.length; index++) {
                if (ids[index] != checkedId) continue;
                onSelected.accept(values[index]);
                dialog.dismiss();
                return;
            }
        });
        dialog.show();
    }

    private void showCanvasThemes() {
        LinkedHashMap<String, Integer> themes = ReaderPreferences.canvasThemes();
        ArrayList<String> keys = new ArrayList<>(themes.keySet());
        String[] labels = new String[keys.size()];
        int selected = 0;
        for (int i = 0; i < keys.size(); i++) {
            labels[i] = ReaderPreferences.themeLabel(context, keys.get(i));
            if (keys.get(i).equals(preferences.canvasTheme())) selected = i;
        }
        new AlertDialog.Builder(context)
                .setTitle(R.string.reader_background_color)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    listener.onThemeSelected(keys.get(which), themes.get(keys.get(which)));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public void show() {
        ScrollView scroll = new ScrollView(context);
        LinearLayout options = new LinearLayout(context);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(Ui.dp(context, 12), Ui.dp(context, 4), Ui.dp(context, 12), Ui.dp(context, 4));
        scroll.addView(options);

        CheckBox tapZones = checkBox(R.string.option_tap_zones, preferences.tapZones());
        CheckBox volume = checkBox(R.string.option_volume_navigation, preferences.volumeNavigation());
        CheckBox remember = checkBox(R.string.option_remember_zoom, preferences.rememberZoom());
        CheckBox screen = checkBox(R.string.option_keep_screen_awake, preferences.keepScreenOn());
        CheckBox autoHide = checkBox(R.string.option_auto_hide_controls, preferences.autoHideControls());
        options.addView(tapZones);
        options.addView(volume);
        options.addView(remember);

        TextView defaultZoomTitle = Ui.text(
                context, context.getString(R.string.option_default_zoom), 15, Ui.TEXT);
        defaultZoomTitle.setPadding(0, Ui.dp(context, 12), 0, Ui.dp(context, 2));
        options.addView(defaultZoomTitle);
        TextView defaultZoomExplanation = Ui.text(
                context, context.getString(R.string.option_default_zoom_description), 13, Ui.TEXT_MUTED);
        defaultZoomExplanation.setPadding(0, 0, 0, Ui.dp(context, 4));
        options.addView(defaultZoomExplanation);

        String[] defaultZoomValues = {
                OpeningZoomPolicy.APP_DEFAULT,
                OpeningZoomPolicy.FIT_WIDTH,
                OpeningZoomPolicy.FIT_PAGE
        };
        int[] defaultZoomLabels = {
                R.string.option_default_zoom_app,
                R.string.reader_fit_width,
                R.string.reader_fit_page
        };
        int[] defaultZoomIds = new int[defaultZoomValues.length];
        RadioGroup defaultZoomChoices = new RadioGroup(context);
        String selectedDefaultZoom = preferences.defaultZoomMode();
        for (int index = 0; index < defaultZoomValues.length; index++) {
            RadioButton choice = new RadioButton(context);
            defaultZoomIds[index] = View.generateViewId();
            choice.setId(defaultZoomIds[index]);
            choice.setText(defaultZoomLabels[index]);
            choice.setTextColor(Ui.TEXT);
            choice.setTextSize(15);
            choice.setMinHeight(Ui.dp(context, 44));
            choice.setButtonTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{Ui.ACCENT, Ui.TEXT_MUTED}));
            defaultZoomChoices.addView(choice, new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 44)));
            if (defaultZoomValues[index].equals(selectedDefaultZoom)) choice.setChecked(true);
        }
        options.addView(defaultZoomChoices);

        TextView backgroundColor = optionsButton(R.string.reader_background_color);
        backgroundColor.setOnClickListener(view -> showCanvasThemes());
        options.addView(backgroundColor);
        TextView hardwareShortcuts = optionsButton(R.string.reader_hardware_shortcuts);
        hardwareShortcuts.setOnClickListener(view -> showKeyboardSettings());
        options.addView(hardwareShortcuts);
        options.addView(screen);
        options.addView(autoHide);

        new AlertDialog.Builder(context)
                .setTitle(R.string.reader_options)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String zoom = preferences.defaultZoomMode();
                    for (int index = 0; index < defaultZoomIds.length; index++) {
                        if (defaultZoomChoices.getCheckedRadioButtonId() == defaultZoomIds[index]) {
                            zoom = defaultZoomValues[index];
                            break;
                        }
                    }
                    listener.onOptionsSaved(new Options(tapZones.isChecked(), volume.isChecked(),
                            remember.isChecked(), zoom, screen.isChecked(), autoHide.isChecked()));
                })
                .show();
    }

    private TextView optionsButton(int text) {
        TextView button = Ui.text(context, context.getString(text), 15, Ui.TEXT);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(Ui.dp(context, 14), Ui.dp(context, 6), Ui.dp(context, 14), Ui.dp(context, 6));
        button.setBackground(Ui.rounded(Ui.SURFACE_HIGH, Ui.dp(context, 12), 0, 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 48));
        params.topMargin = Ui.dp(context, 8);
        button.setLayoutParams(params);
        return button;
    }

    private void showKeyboardSettings() {
        LinearLayout rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(Ui.dp(context, 18), Ui.dp(context, 4), Ui.dp(context, 18), Ui.dp(context, 4));
        AlertDialog[] holder = new AlertDialog[1];
        for (String action : SHORTCUT_ACTIONS) {
            TextView button = Ui.text(context,
                    shortcutLabel(action) + "\n" + preferences.shortcut(action).label(), 15, Ui.TEXT);
            button.setGravity(Gravity.CENTER_VERTICAL);
            button.setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8));
            button.setBackground(Ui.rounded(Ui.SURFACE_HIGH, Ui.dp(context, 12), 0, 0));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 64));
            params.bottomMargin = Ui.dp(context, 8);
            rows.addView(button, params);
            button.setOnClickListener(view -> captureShortcut(action, () -> {
                if (holder[0] != null) holder[0].dismiss();
                showKeyboardSettings();
            }));
        }
        holder[0] = new AlertDialog.Builder(context)
                .setTitle(R.string.reader_hardware_shortcuts)
                .setMessage(R.string.shortcuts_instruction)
                .setView(rows)
                .setNeutralButton(R.string.shortcuts_reset_defaults, (dialog, which) -> {
                    listener.onShortcutsReset();
                    Toast.makeText(context, R.string.shortcuts_reset, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(R.string.done, null)
                .create();
        holder[0].show();
    }

    @SuppressLint("GestureBackNavigation")
    private void captureShortcut(String action, Runnable onSaved) {
        TextView prompt = Ui.text(context, context.getString(R.string.shortcuts_press_combination), 17, Ui.TEXT);
        prompt.setGravity(Gravity.CENTER);
        prompt.setPadding(Ui.dp(context, 24), Ui.dp(context, 28), Ui.dp(context, 24), Ui.dp(context, 28));
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(shortcutLabel(action))
                .setView(prompt)
                .setNegativeButton(R.string.cancel, null)
                .create();
        dialog.setOnKeyListener((ignored, keyCode, event) -> {
            // Predictive back is handled by the activity callback. This branch only lets a
            // physical Back key retain the dialog's standard dismiss behavior.
            if (keyCode == KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() != KeyEvent.ACTION_UP || keyCode == KeyEvent.KEYCODE_UNKNOWN) return true;
            ReaderPreferences.Shortcut candidate = new ReaderPreferences.Shortcut(
                    keyCode, ReaderPreferences.normalizeModifiers(event.getMetaState()));
            for (String other : SHORTCUT_ACTIONS) {
                if (other.equals(action)) continue;
                ReaderPreferences.Shortcut existing = preferences.shortcut(other);
                if (existing.keyCode() == candidate.keyCode() &&
                        existing.modifiers() == candidate.modifiers()) {
                    prompt.setText(context.getString(
                            R.string.shortcuts_already_used, shortcutLabel(other)));
                    return true;
                }
            }
            listener.onShortcutSelected(action, candidate);
            dialog.dismiss();
            onSaved.run();
            return true;
        });
        dialog.show();
    }

    private CheckBox checkBox(int label, boolean checked) {
        CheckBox box = new CheckBox(context);
        box.setText(label);
        box.setTextColor(Ui.TEXT);
        box.setTextSize(15);
        box.setChecked(checked);
        box.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Ui.ACCENT, Ui.TEXT_MUTED}));
        box.setMinHeight(Ui.dp(context, 52));
        return box;
    }

    private String shortcutLabel(String action) {
        int label = switch (action) {
            case "previous" -> R.string.shortcut_previous;
            case "next_alt" -> R.string.shortcut_next_alternate;
            case "previous_alt" -> R.string.shortcut_previous_alternate;
            default -> R.string.shortcut_next;
        };
        return context.getString(label);
    }
}

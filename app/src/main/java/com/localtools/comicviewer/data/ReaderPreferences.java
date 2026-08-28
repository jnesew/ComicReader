package com.localtools.comicviewer.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.KeyEvent;

import com.localtools.comicviewer.R;

import java.util.LinkedHashMap;

public final class ReaderPreferences {
    private static final String FILE = "reader_preferences";
    private final SharedPreferences values;

    public ReaderPreferences(Context context) {
        values = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean tapZones() {
        return values.getBoolean("tap_zones", true);
    }

    public void setTapZones(boolean value) {
        values.edit().putBoolean("tap_zones", value).apply();
    }

    public boolean volumeNavigation() {
        return values.getBoolean("volume_navigation", false);
    }

    public void setVolumeNavigation(boolean value) {
        values.edit().putBoolean("volume_navigation", value).apply();
    }

    public boolean rememberZoom() {
        return values.getBoolean("remember_zoom", true);
    }

    public void setRememberZoom(boolean value) {
        values.edit().putBoolean("remember_zoom", value).apply();
    }

    public boolean keepScreenOn() {
        return values.getBoolean("keep_screen_on", true);
    }

    public void setKeepScreenOn(boolean value) {
        values.edit().putBoolean("keep_screen_on", value).apply();
    }

    public boolean legacyRightToLeft() {
        return values.getBoolean("right_to_left", false);
    }

    public boolean needsPerTitleDirectionMigration() {
        return !values.getBoolean("per_title_direction_migrated", false);
    }

    public void finishPerTitleDirectionMigration() {
        values.edit()
                .putBoolean("per_title_direction_migrated", true)
                .remove("right_to_left")
                .apply();
    }

    public boolean autoHideControls() {
        return values.getBoolean("auto_hide_controls", true);
    }

    public void setAutoHideControls(boolean value) {
        values.edit().putBoolean("auto_hide_controls", value).apply();
    }

    public String canvasTheme() {
        return values.getString("canvas_theme", "oled");
    }

    public void setCanvasTheme(String key) {
        values.edit().putString("canvas_theme", key).apply();
    }

    public String libraryFolderUri() {
        return values.getString("library_folder_uri", "");
    }

    public String libraryFolderLabel() {
        return values.getString("library_folder_label", "");
    }

    public void setLibraryFolder(String uri, String label) {
        values.edit()
                .putString("library_folder_uri", uri == null ? "" : uri)
                .putString("library_folder_label", label == null ? "" : label)
                .apply();
    }

    public void clearLibraryFolder() {
        values.edit().remove("library_folder_uri").remove("library_folder_label").apply();
    }

    public int canvasColor() {
        return canvasThemes().getOrDefault(canvasTheme(), Color.BLACK);
    }

    public static LinkedHashMap<String, Integer> canvasThemes() {
        LinkedHashMap<String, Integer> themes = new LinkedHashMap<>();
        themes.put("oled", Color.rgb(0, 0, 0));
        themes.put("charcoal", Color.rgb(24, 27, 32));
        themes.put("warm", Color.rgb(43, 37, 32));
        themes.put("slate", Color.rgb(29, 36, 48));
        themes.put("paper", Color.rgb(216, 210, 196));
        return themes;
    }

    public static String themeLabel(Context context, String key) {
        int label = switch (key) {
            case "charcoal" -> R.string.theme_charcoal;
            case "warm" -> R.string.theme_warm;
            case "slate" -> R.string.theme_slate;
            case "paper" -> R.string.theme_paper;
            default -> R.string.theme_oled;
        };
        return context.getString(label);
    }

    public Shortcut shortcut(String action) {
        Shortcut fallback = defaultShortcut(action);
        int keyCode = values.getInt("shortcut_" + action + "_key", fallback.keyCode);
        int modifiers = values.getInt("shortcut_" + action + "_meta", fallback.modifiers);
        return new Shortcut(keyCode, modifiers);
    }

    public void setShortcut(String action, Shortcut shortcut) {
        values.edit()
                .putInt("shortcut_" + action + "_key", shortcut.keyCode)
                .putInt("shortcut_" + action + "_meta", shortcut.modifiers)
                .apply();
    }

    public void resetShortcuts() {
        SharedPreferences.Editor editor = values.edit();
        for (String action : new String[]{"next", "previous", "next_alt", "previous_alt"}) {
            editor.remove("shortcut_" + action + "_key");
            editor.remove("shortcut_" + action + "_meta");
        }
        editor.apply();
    }

    public boolean matches(String action, KeyEvent event) {
        Shortcut shortcut = shortcut(action);
        int normalized = normalizeModifiers(event.getMetaState());
        return shortcut.keyCode == event.getKeyCode() && shortcut.modifiers == normalized;
    }

    public static int normalizeModifiers(int state) {
        return KeyEvent.normalizeMetaState(state) & (
                KeyEvent.META_SHIFT_ON |
                KeyEvent.META_CTRL_ON |
                KeyEvent.META_ALT_ON |
                KeyEvent.META_META_ON);
    }

    public static Shortcut defaultShortcut(String action) {
        return switch (action) {
            case "previous" -> new Shortcut(KeyEvent.KEYCODE_DPAD_LEFT, 0);
            case "next_alt" -> new Shortcut(KeyEvent.KEYCODE_SPACE, 0);
            case "previous_alt" -> new Shortcut(KeyEvent.KEYCODE_SPACE, KeyEvent.META_SHIFT_ON);
            default -> new Shortcut(KeyEvent.KEYCODE_DPAD_RIGHT, 0);
        };
    }

    public static final class Shortcut {
        private final int keyCode;
        private final int modifiers;

        public Shortcut(int keyCode, int modifiers) {
            this.keyCode = keyCode;
            this.modifiers = modifiers;
        }

        public int keyCode() {
            return keyCode;
        }

        public int modifiers() {
            return modifiers;
        }

        public String label() {
            StringBuilder result = new StringBuilder();
            if ((modifiers & KeyEvent.META_CTRL_ON) != 0) result.append("Ctrl+");
            if ((modifiers & KeyEvent.META_ALT_ON) != 0) result.append("Alt+");
            if ((modifiers & KeyEvent.META_SHIFT_ON) != 0) result.append("Shift+");
            if ((modifiers & KeyEvent.META_META_ON) != 0) result.append("Meta+");
            String key = KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "");
            result.append(key.replace('_', ' '));
            return result.toString();
        }
    }
}

package io.github.jnesew.comicviewer.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.KeyEvent;

import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.model.OpeningZoomPolicy;
import io.github.jnesew.comicviewer.model.ReaderDefaults;
import io.github.jnesew.comicviewer.model.ReadingDirection;
import io.github.jnesew.comicviewer.model.LibraryFolder;
import io.github.jnesew.comicviewer.util.InputLimits;
import io.github.jnesew.comicviewer.render.BufferingPolicy;
import io.github.jnesew.comicviewer.render.AndroidBufferingMemory;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public final class ReaderPreferences {
    private static final String FILE = "reader_preferences";
    private final SharedPreferences values;
    private final String deviceBufferingDefault;

    public ReaderPreferences(Context context) {
        this(context, FILE, AndroidBufferingMemory.deviceDefault(context));
    }

    ReaderPreferences(Context context, String preferenceFile, String deviceBufferingDefault) {
        values = context.getSharedPreferences(preferenceFile, Context.MODE_PRIVATE);
        this.deviceBufferingDefault = BufferingPolicy.normalize(deviceBufferingDefault);
    }

    public String appearance() {
        String mode = values.getString("appearance", "system");
        return "light".equals(mode) || "dark".equals(mode) ? mode : "system";
    }

    public void setAppearance(String mode) {
        values.edit().putString("appearance",
                "light".equals(mode) || "dark".equals(mode) ? mode : "system").apply();
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

    public String defaultZoomMode() {
        return OpeningZoomPolicy.normalizeGlobalDefault(
                values.getString("default_zoom_mode", OpeningZoomPolicy.APP_DEFAULT));
    }

    public void setDefaultZoomMode(String value) {
        String normalized = OpeningZoomPolicy.normalizeGlobalDefault(value);
        SharedPreferences.Editor editor = values.edit();
        if (OpeningZoomPolicy.APP_DEFAULT.equals(normalized)) {
            editor.remove("default_zoom_mode");
        } else {
            editor.putString("default_zoom_mode", normalized);
        }
        editor.apply();
    }

    public String defaultReadingLayout() {
        return ReaderDefaults.normalizeLayout(values.getString(
                "default_reading_layout", ReaderDefaults.SINGLE));
    }

    public void setDefaultReadingLayout(String value) {
        values.edit().putString("default_reading_layout", ReaderDefaults.normalizeLayout(value)).apply();
    }

    public String defaultReadingDirection() {
        return ReadingDirection.normalize(values.getString(
                "default_reading_direction", ReadingDirection.AUTO));
    }

    public void setDefaultReadingDirection(String value) {
        values.edit().putString("default_reading_direction", ReadingDirection.normalize(value)).apply();
    }

    public String bufferingLevel() {
        return BufferingPolicy.resolvePreference(bufferingChoice(), deviceBufferingDefault);
    }

    public String bufferingChoice() {
        return BufferingPolicy.preferenceChoice(values.getString("buffering_level", null));
    }

    public void setBufferingLevel(String level) {
        String choice = BufferingPolicy.preferenceChoice(level);
        SharedPreferences.Editor editor = values.edit();
        if (BufferingPolicy.DEVICE_DEFAULT.equals(choice)) editor.remove("buffering_level");
        else editor.putString("buffering_level", choice);
        editor.apply();
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

    /** The collection and removal of the legacy keys are committed together, exactly once. */
    public synchronized List<LibraryFolder> libraryFolders() {
        if (!values.contains("library_folders")) {
            ArrayList<LibraryFolder> migrated = new ArrayList<>();
            String uri = values.getString("library_folder_uri", "");
            if (!uri.isEmpty()) migrated.add(new LibraryFolder(uri,
                    values.getString("library_folder_label", ""), ""));
            saveLibraryFolders(migrated, pendingFolderReleases());
        }
        ArrayList<LibraryFolder> folders = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(values.getString("library_folders", "[]"));
            for (int index = 0; index < array.length(); index++) {
                JSONObject entry = array.getJSONObject(index);
                String uri = entry.getString("uri");
                if (uri.isEmpty() || !InputLimits.isIdentifierAcceptable(uri)) continue;
                boolean duplicate = false;
                for (LibraryFolder folder : folders) if (folder.sameTree(uri)) duplicate = true;
                if (!duplicate) folders.add(new LibraryFolder(uri,
                        entry.optString("label", ""), entry.optString("location", "")));
            }
        } catch (JSONException invalidCollection) {
            // Never replace a corrupt persisted collection with an empty one silently.
            throw new IllegalStateException("Invalid saved library folder collection", invalidCollection);
        }
        return java.util.Collections.unmodifiableList(folders);
    }

    public synchronized void addLibraryFolder(LibraryFolder selected) {
        replaceLibraryFolder("", selected);
    }

    public synchronized void replaceLibraryFolder(String previousUri, LibraryFolder selected) {
        ArrayList<LibraryFolder> folders = new ArrayList<>(libraryFolders());
        Set<String> pending = pendingFolderReleases();
        int position = folders.size();
        for (int index = folders.size() - 1; index >= 0; index--) {
            LibraryFolder folder = folders.get(index);
            if (folder.sameTree(selected.uri)) {
                // Retain the original tree URI so existing source identities remain stable.
                selected = new LibraryFolder(folder.uri, selected.label, selected.location);
                position = index;
                folders.remove(index);
            } else if (!previousUri.isEmpty() && folder.uri.equals(previousUri)) {
                position = index;
                pending.add(folder.uri);
                folders.remove(index);
            }
        }
        folders.add(Math.min(position, folders.size()), selected);
        pending.remove(selected.uri);
        saveLibraryFolders(folders, pending);
    }

    public synchronized void removeLibraryFolder(String uri) {
        ArrayList<LibraryFolder> folders = new ArrayList<>(libraryFolders());
        Set<String> pending = pendingFolderReleases();
        if (folders.removeIf(folder -> folder.uri.equals(uri))) pending.add(uri);
        saveLibraryFolders(folders, pending);
    }

    public synchronized List<String> libraryFolderUris() {
        ArrayList<String> result = new ArrayList<>();
        for (LibraryFolder folder : libraryFolders()) result.add(folder.uri);
        return result;
    }

    public synchronized Set<String> pendingFolderReleases() {
        return new HashSet<>(values.getStringSet("pending_folder_releases", java.util.Collections.emptySet()));
    }

    public synchronized void finishFolderRelease(String uri) {
        Set<String> pending = pendingFolderReleases();
        pending.remove(uri);
        if (!values.edit().putStringSet("pending_folder_releases", pending).commit()) {
            throw new IllegalStateException("Could not save library folder grant state");
        }
    }

    private void saveLibraryFolders(List<LibraryFolder> folders, Set<String> pending) {
        JSONArray array = new JSONArray();
        try {
            for (LibraryFolder folder : folders) {
                JSONObject entry = new JSONObject();
                entry.put("uri", folder.uri);
                entry.put("label", folder.label);
                entry.put("location", folder.location);
                array.put(entry);
            }
        } catch (JSONException invalidValue) {
            throw new IllegalArgumentException("Invalid library folder", invalidValue);
        }
        if (!values.edit().putString("library_folders", array.toString())
                .putStringSet("pending_folder_releases", pending)
                .remove("library_folder_uri").remove("library_folder_label").commit()) {
            throw new IllegalStateException("Could not save library folders");
        }
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

package io.github.jnesew.comicviewer.data;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.library.LibraryQueryController;
import io.github.jnesew.comicviewer.ui.HomeView;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.Ui;

/** Observable navigation behavior across recreation, without relying on child indexes or helper names. */
final class LibraryNavigationTests {
    static void seriesCoversStayAligned(Instrumentation instrumentation, LibraryDatabase database) {
        String file = "library-grid-alignment-instrumentation";
        Context target = instrumentation.getTargetContext();
        target.deleteSharedPreferences(file);
        for (int series = 0; series < 18; series++) {
            int issues = series % 4 == 0 ? 12 : 2;
            for (int issue = 0; issue < issues; issue++) {
                String uri = "alignment-" + series + "-" + issue;
                database.ensureImported(uri, "Issue " + issue, 1, 1);
                database.applyDetectedSeries(uri, String.format(java.util.Locale.ROOT,
                        "Series %02d", series), Integer.toString(issue), "", "");
                if (series % 3 != 0) {
                    ReadingProgress progress = database.get(uri);
                    progress.page = 2; progress.pageCount = 5; progress.lastOpened = 123;
                    database.saveReadingProgress(progress);
                    if (series % 3 == 2) database.setReadStatus(uri, true);
                }
            }
            if (series % 2 == 0) database.toggleSeriesFavorite(database.get("alignment-" + series + "-0").seriesId);
        }
        try {
            for (float fontScale : new float[]{1f, 1.5f}) {
                android.content.res.Configuration config = new android.content.res.Configuration(
                        target.getResources().getConfiguration());
                config.fontScale = fontScale;
                config.setLocale(fontScale == 1f ? java.util.Locale.ENGLISH : new java.util.Locale("fi"));
                Context scaled = target.createConfigurationContext(config);
                Context context = new ContextThemeWrapper(scaled, android.R.style.Theme_Material) {
                    @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                        return target.getSharedPreferences(file, mode);
                    }
                };
                String[] densities = {"standard", "compact", "dense"};
                for (int index = 0; index < densities.length; index++) {
                    String density = densities[index];
                    int columns = index + 2;
                    context.getSharedPreferences(file, 0).edit().putString("mode", "series")
                            .putString("sort", LibraryDatabase.SORT_TITLE_ASC).putString("grid_density", density).commit();
                    java.util.concurrent.atomic.AtomicReference<AssertionError> failure = new java.util.concurrent.atomic.AtomicReference<>();
                    instrumentation.runOnMainSync(() -> {
                        HomeView home = new HomeView(context, new LibraryQueryController(database), null);
                        int width = Ui.dp(context, Math.round(392 * fontScale)), height = Ui.dp(context, 650);
                        try {
                            home.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                            home.layout(0, 0, width, height);
                            GridView grid = find(home, GridView.class);
                            check(grid.getNumColumns() == columns);
                            alignedRows(grid);
                            grid.scrollListBy(Ui.dp(context, 900));
                            grid.scrollListBy(-Ui.dp(context, 380));
                            alignedRows(grid);
                            if (columns == 3 && fontScale == 1f) savePreview(target, home, width, height,
                                    "series-grid-alignment.png");
                        } catch (AssertionError error) {
                            savePreview(target, home, width, height, "series-grid-alignment-failure.png");
                            failure.set(error);
                        } finally { home.close(); }
                    });
                    if (failure.get() != null) throw new AssertionError(density + " at font scale " + fontScale,
                            failure.get());
                }
            }
        } finally { target.deleteSharedPreferences(file); }
    }

    private static void savePreview(Context context, View view, int width, int height, String filename) {
        android.graphics.Bitmap screenshot = android.graphics.Bitmap.createBitmap(width, height,
                android.graphics.Bitmap.Config.ARGB_8888);
        try (var output = new java.io.FileOutputStream(new java.io.File(context.getCacheDir(), filename))) {
            view.draw(new android.graphics.Canvas(screenshot));
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } catch (java.io.IOException ignored) { }
        finally { screenshot.recycle(); }
    }

    private static void alignedRows(GridView grid) {
        int first = grid.getFirstVisiblePosition();
        java.util.HashMap<Integer, Integer> tops = new java.util.HashMap<>();
        for (int index = 0; index < grid.getChildCount(); index++) {
            View card = grid.getChildAt(index);
            ImageView cover = find(card, ImageView.class);
            int row = (first + index) / grid.getNumColumns();
            int top = card.getTop() + cover.getTop();
            Integer previous = tops.putIfAbsent(row, top);
            if (previous != null && previous != top) throw new AssertionError(
                    "Cover tops differ in row " + row + ": " + previous + " vs " + top);
        }
    }

    static void recreation(Instrumentation instrumentation, LibraryDatabase database) {
        String file = "library-navigation-instrumentation";
        Context target = instrumentation.getTargetContext();
        target.deleteSharedPreferences(file);
        Context context = new ContextThemeWrapper(target, android.R.style.Theme_Material) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(file, mode);
            }
        };
        database.ensureImported("first", "First issue", 1, 1);
        database.ensureImported("second", "Second issue", 1, 1);
        database.applyDetectedSeries("first", "Navigation series", "1", "", "");
        database.applyDetectedSeries("second", "Navigation series", "2", "", "");
        database.toggleSeriesFavorite(database.get("first").seriesId);
        context.getSharedPreferences(file, 0).edit().putString("mode", "series")
                .putString("filter", LibraryDatabase.FILTER_FAVORITES).commit();
        instrumentation.runOnMainSync(() -> {
            HomeView home = new HomeView(context, new LibraryQueryController(database), null);
            HomeView restored = null;
            try {
                EditText search = find(home, EditText.class);
                search.setVisibility(View.VISIBLE); search.setText("Navigation");
                layout(home);
                View series = clickableDescription(home, "Navigation series");
                check(series != null); series.performClick(); layout(home);
                check(find(home, GridView.class).getAdapter().getCount() == 2);
                check(search.getText().toString().isEmpty());
                search.setVisibility(View.GONE);
                Bundle saved = new Bundle(); home.saveState(saved); home.close();
                Context light = new ContextThemeWrapper(context, R.style.Theme_ComicViewer_Light);
                restored = new HomeView(light, new LibraryQueryController(database), null);
                check(Ui.background(light) != Ui.background(context));
                restored.restoreState(saved); layout(restored);
                check(find(restored, GridView.class).getAdapter().getCount() == 2);
                check(restored.handleBack()); layout(restored);
                EditText restoredSearch = find(restored, EditText.class);
                check(restoredSearch.getVisibility() == View.VISIBLE);
                check("Navigation".equals(restoredSearch.getText().toString()));
                check(find(restored, GridView.class).getAdapter().getCount() == 1);
                check(selectedText(restored, context.getString(R.string.library_view_series)));
            } finally { home.close(); if (restored != null) restored.close(); }
        });
        target.deleteSharedPreferences(file);
    }

    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1080, 1920);
    }
    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            T found = find(group.getChildAt(index), type); if (found != null) return found;
        }
        return null;
    }
    private static View clickableDescription(View view, String title) {
        CharSequence description = view.getContentDescription();
        if (view.isClickable() && description != null && description.toString().startsWith(title)) return view;
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            View found = clickableDescription(group.getChildAt(index), title); if (found != null) return found;
        }
        return null;
    }
    private static boolean selectedText(View view, String text) {
        if (view instanceof TextView label && text.contentEquals(label.getText()) && view.isSelected()) return true;
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            if (selectedText(group.getChildAt(index), text)) return true;
        }
        return false;
    }
    private static void check(boolean value) { if (!value) throw new AssertionError("Navigation state lost"); }
}

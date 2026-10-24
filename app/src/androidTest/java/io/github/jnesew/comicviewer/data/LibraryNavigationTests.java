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
import android.widget.TextView;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.library.LibraryQueryController;
import io.github.jnesew.comicviewer.ui.HomeView;

/** Observable navigation behavior across recreation, without relying on child indexes or helper names. */
final class LibraryNavigationTests {
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
                restored = new HomeView(context, new LibraryQueryController(database), null);
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

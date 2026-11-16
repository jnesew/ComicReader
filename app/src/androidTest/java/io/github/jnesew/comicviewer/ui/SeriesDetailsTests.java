package io.github.jnesew.comicviewer.ui;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.library.LibraryQueryController;
import io.github.jnesew.comicviewer.model.ComicMetadata;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.model.SeriesGroup;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Observable series overview content, Continue behavior, native scrolling, and state restoration. */
public final class SeriesDetailsTests {
    private static final String PREFS = "series-details-instrumentation";
    private static final String FIFTH = "Only the fifth issue synopsis.";
    private static final String SIXTH = "Only the sixth issue synopsis.";

    private static void seed(LibraryDatabase db, boolean metadata) {
        for (int number = 1; number <= 36; number++) {
            String uri = "details:" + number;
            db.ensureImported(uri, "File " + number, 100, 200);
            db.applyDetectedSeries(uri, "Moonlit Atlas", Integer.toString(number), "", "");
            if (metadata && (number == 1 || number == 5 || number == 6)) {
                db.storePublicationMetadata(uri, new ComicMetadata(Map.of(
                        "Title", number == 5 ? "The Glass Mountain" : "Chapter " + number,
                        "Summary", number == 5 ? FIFTH : number == 6 ? SIXTH : "First issue synopsis.",
                        "Writer", "Mira Vale", "Publisher", "Example Press", "Year", "2024"), List.of()));
            }
        }
        if (metadata) {
            ReadingProgress progress = db.get("details:5");
            progress.page = 2; progress.pageCount = 10; progress.lastOpened = 100;
            db.saveReadingProgress(progress);
        }
    }
    private static Context context(Instrumentation instrumentation, boolean light, float fontScale) {
        Context target = instrumentation.getTargetContext();
        android.content.res.Configuration config = new android.content.res.Configuration(target.getResources().getConfiguration());
        config.fontScale = fontScale;
        return new ContextThemeWrapper(target.createConfigurationContext(config), light
                ? R.style.Theme_ComicViewer_Light : R.style.Theme_ComicViewer) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences(PREFS, mode);
            }
        };
    }
    private static void prepare(Instrumentation instrumentation) {
        Context target = instrumentation.getTargetContext(); target.deleteSharedPreferences(PREFS);
        target.getSharedPreferences(PREFS, 0).edit().putString("mode", "series")
                .putString("grid_density", "compact").commit();
    }
    private static HomeView open(Context context, LibraryDatabase db, Commands commands) {
        HomeView home = new HomeView(context, new LibraryQueryController(db), commands);
        layout(home);
        View series = description(home, "Moonlit Atlas", true);
        check(series != null, "Series card missing"); series.performClick(); layout(home);
        return home;
    }

    public static void overviewAndContinue(Instrumentation instrumentation, LibraryDatabase db) {
        seed(db, true); prepare(instrumentation);
        main(instrumentation, () -> {
            Context context = context(instrumentation, false, 1f); Commands commands = new Commands();
            HomeView home = open(context, db, commands);
            try {
                String label = context.getString(R.string.series_issue_title,
                        context.getString(R.string.comic_issue_number, "5"), "The Glass Mountain");
                TextView disclosure = text(home, label);
                check(disclosure != null && disclosure.isClickable(), "Target issue title was not expandable");
                check(text(home, FIFTH) == null, "Synopsis started expanded");
                disclosure.performClick(); layout(home);
                check(text(home, FIFTH) != null && text(home, "First issue synopsis.") == null, "Description did not match Continue");
                text(home, context.getString(R.string.series_continue)).performClick();
                check("details:5".equals(commands.opened), "Continue opened a different issue");
                check(db.get("details:5").lastOpened == 100 && !db.get("details:5").read,
                        "Showing details marked the issue opened or read");
                find(home, EditText.class).setText("File 1"); layout(home);
                check(text(home, FIFTH) != null, "Filtering replaced the Continue description");
                text(home, context.getString(R.string.series_continue)).performClick();
                check("details:5".equals(commands.opened), "Filtering changed Continue's target");
                db.setReadStatus("details:5", true);
                ReadingProgress next = db.get("details:6"); next.lastOpened = 200; db.saveReadingProgress(next);
                home.refresh(); layout(home);
                String nextLabel = context.getString(R.string.series_issue_title,
                        context.getString(R.string.comic_issue_number, "6"), "Chapter 6");
                check(text(home, FIFTH) == null && text(home, SIXTH) == null, "Target change retained stale expanded details");
                text(home, nextLabel).performClick(); layout(home);
                check(text(home, SIXTH) != null, "New Continue target did not supply its own summary");
                text(home, context.getString(R.string.series_continue)).performClick();
                check("details:6".equals(commands.opened), "Continue action lagged behind its details");
            } finally { home.close(); }
        });
        instrumentation.getTargetContext().deleteSharedPreferences(PREFS);
    }

    public static void scrollingAndRestoration(Instrumentation instrumentation, LibraryDatabase db) {
        seed(db, true); prepare(instrumentation);
        main(instrumentation, () -> {
            Context context = context(instrumentation, false, 1f);
            HomeView home = open(context, db, new Commands()); HomeView restored = null;
            try {
                String label = context.getString(R.string.series_issue_title,
                        context.getString(R.string.comic_issue_number, "5"), "The Glass Mountain");
                text(home, label).performClick(); layout(home);
                ListView list = find(home, ListView.class);
                list.scrollListBy(1800); layout(home);
                check(list.getFirstVisiblePosition() > 0, "Overview did not scroll away");
                check(text(home, FIFTH) == null && text(home, label) == null, "Header remained visible over issue rows");
                check(text(home, "Moonlit Atlas") != null && text(home, context.getString(R.string.series_issues)) != null,
                        "Pinned series title or issue controls disappeared");
                check(list.getChildCount() < 12, "The whole series was inflated instead of recycled");
                int firstIssue = firstCardNumber(list);
                Bundle state = new Bundle(); home.saveState(state);
                restored = new HomeView(context(instrumentation, true, 1.5f), new LibraryQueryController(db), new Commands());
                restored.restoreState(state); layout(restored);
                ListView restoredList = find(restored, ListView.class);
                check(restoredList.getFirstVisiblePosition() > 0, "Recreation reopened the overview instead of restoring scroll");
                check(Math.abs(firstCardNumber(restoredList) - firstIssue) <= 2, "Recreation lost the issue anchor after columns changed");
                View info = description(restored, context.getString(R.string.series_issue_details), true);
                check(info != null, "Pinned information action missing"); info.performClick(); layout(restored);
                check(text(restored, FIFTH) != null, "Information action did not restore expanded issue details");
                info.performClick(); layout(restored);
                check(text(restored, FIFTH) == null, "Information action did not collapse visible details");
                info.performClick(); layout(restored);
                check(text(restored, FIFTH) != null, "Information action did not reopen collapsed details");
                check(restored.handleBack(), "Series back action failed"); layout(restored);
                check(((View) find(restored, ListView.class).getParent()).getVisibility() == View.GONE, "Series issue rows remained after Back");
            } finally { home.close(); if (restored != null) restored.close(); }
        });
        instrumentation.getTargetContext().deleteSharedPreferences(PREFS);
    }

    public static void missingMetadataAndEmptyFilter(Instrumentation instrumentation, LibraryDatabase db) {
        seed(db, false); prepare(instrumentation);
        main(instrumentation, () -> {
            Context context = context(instrumentation, false, 1f); Commands commands = new Commands();
            HomeView home = open(context, db, commands); HomeView filtered = null;
            try {
                TextView issue = text(home, context.getString(R.string.comic_issue_number, "1"));
                check(issue != null && !issue.isClickable(), "Empty details offered a nonfunctional disclosure");
                check(description(home, context.getString(R.string.series_issue_details), true) == null, "Empty metadata offered a toolbar action");
                text(home, context.getString(R.string.series_continue)).performClick();
                check("details:1".equals(commands.opened), "Missing metadata prevented reading");
                Bundle state = new Bundle(); home.saveState(state); state.putString("home_filter", LibraryDatabase.FILTER_COMPLETED);
                filtered = new HomeView(context, new LibraryQueryController(db), commands); filtered.restoreState(state); layout(filtered);
                check(text(filtered, context.getString(R.string.library_no_results)) != null, "Empty filter did not show a result notice");
                text(filtered, context.getString(R.string.series_continue)).performClick();
                check("details:1".equals(commands.opened), "An empty visible filter hid the full-series Continue target");
            } finally { home.close(); if (filtered != null) filtered.close(); }
        });
        instrumentation.getTargetContext().deleteSharedPreferences(PREFS);
    }

    private static void layout(View view) {
        for (int pass = 0; pass < 2; pass++) {
            view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, 1080, 1920);
        }
    }
    private static TextView text(View view, String value) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof TextView text && value.contentEquals(text.getText())) return text;
        if (view instanceof ViewGroup group) for (int child = 0; child < group.getChildCount(); child++) {
            TextView found = text(group.getChildAt(child), value); if (found != null) return found;
        }
        return null;
    }
    private static View description(View view, String prefix, boolean clickable) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if ((!clickable || view.isClickable()) && view.getContentDescription() != null &&
                view.getContentDescription().toString().startsWith(prefix)) return view;
        if (view instanceof ViewGroup group) for (int child = 0; child < group.getChildCount(); child++) {
            View found = description(group.getChildAt(child), prefix, clickable); if (found != null) return found;
        }
        return null;
    }
    private static int firstCardNumber(View list) {
        View card = description(list, "File ", true);
        check(card != null, "No visible comic card");
        String title = card.getContentDescription().toString();
        return Integer.parseInt(title.substring(5, title.indexOf(',')));
    }
    private static <T> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup group) for (int child = 0; child < group.getChildCount(); child++) {
            T found = find(group.getChildAt(child), type); if (found != null) return found;
        }
        return null;
    }
    private static void main(Instrumentation instrumentation, Runnable action) {
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class Commands implements HomeView.Listener {
        String opened;
        @Override public void onRecentRequested(ReadingProgress item) { opened = item.uri; }
        @Override public void onOpenRequested() { }
        @Override public void onFavoriteRequested(ReadingProgress item) { }
        @Override public void onForgetRequested(ReadingProgress item) { }
        @Override public void onSeriesForgetRequested(SeriesGroup group) { }
        @Override public void onComicEditRequested(ReadingProgress item) { }
        @Override public void onComicReadStatusRequested(ReadingProgress item, boolean read) { }
        @Override public void onSeriesReadStatusRequested(SeriesGroup group, boolean read) { }
        @Override public void onSeriesFavoriteRequested(SeriesGroup group) { }
        @Override public void onFoldersRequested() { }
        @Override public void onAddFolderRequested() { }
        @Override public void onLibraryMenuRequested(View anchor) { }
    }
}

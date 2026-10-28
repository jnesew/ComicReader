package io.github.jnesew.comicviewer.ui;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.model.SeriesGroup;
import io.github.jnesew.comicviewer.util.LibraryGridDensity;
import io.github.jnesew.comicviewer.util.Ui;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Native-view checks of the visible status, issue overlay and card footprint after recycling. */
public final class LibraryCardTests {
    public static void hybridStatusAndBadges(Instrumentation instrumentation) {
        Context target = instrumentation.getTargetContext();
        for (int theme : new int[]{R.style.Theme_ComicViewer, R.style.Theme_ComicViewer_Light}) {
            for (float scale : new float[]{1f, 1.5f}) {
                Configuration configuration = new Configuration(target.getResources().getConfiguration());
                configuration.fontScale = scale;
                configuration.setLocale(scale == 1f ? java.util.Locale.ENGLISH : new java.util.Locale("fi"));
                Context context = new ContextThemeWrapper(target.createConfigurationContext(configuration), theme);
                for (LibraryGridDensity density : LibraryGridDensity.values()) {
                    AtomicReference<Throwable> failure = new AtomicReference<>();
                    instrumentation.runOnMainSync(() -> {
                        try { recycledCards(context, density); }
                        catch (Throwable error) { failure.set(error); }
                    });
                    if (failure.get() != null) throw new AssertionError(
                            density + ", font scale " + scale + ", theme " + theme, failure.get());
                }
            }
        }
    }

    private static void recycledCards(Context context, LibraryGridDensity density) {
        ReadingProgress unread = comic("Unread comic", "12");
        ReadingProgress strip = comic("Long strip", "");
        strip.lastOpened = 123; strip.pageCount = 1; strip.scrollRatio = .42f;
        ReadingProgress completed = comic("Marked read", "1905-10-15");
        completed.read = true;
        ReadingProgress indexing = comic("Indexing comic", "");
        indexing.lastOpened = 123;
        ReadingProgress unavailable = comic("Unavailable comic", "7");
        unavailable.available = false; unavailable.read = true;
        ReadingProgress atEnd = comic("Last position", "1234567890".repeat(12));
        atEnd.lastOpened = 123; atEnd.pageCount = 1; atEnd.scrollRatio = 1f;
        CoverThumbnailLoader covers = new CoverThumbnailLoader();
        try {
            LibraryGridAdapter adapter = new LibraryGridAdapter(context, null, group -> { }, covers, density);
            FrameLayout parent = new FrameLayout(context);
            View recycled = null;
            int cardHeight = -1;
            ReadingProgress[] cases = {unread, strip, completed, indexing, unavailable, atEnd, unread, atEnd};
            int[] percentages = {-1, 42, -1, -1, -1, 99, -1, 99};
            int[] labels = {R.string.comic_status_new, 0, R.string.comic_status_completed,
                    R.string.comic_status_indexing, R.string.comic_status_unavailable, 0, R.string.comic_status_new, 0};
            for (int index = 0; index < cases.length; index++) {
                ReadingProgress item = cases[index];
                adapter.submit(List.of(item), List.of(), false);
                recycled = adapter.getView(0, recycled, parent);
                layout(recycled, context, density);
                if (cardHeight < 0) cardHeight = recycled.getMeasuredHeight();
                check(cardHeight == recycled.getMeasuredHeight(), "Reading status changed card height");
                ProgressBar bar = find(recycled, ProgressBar.class);
                if (percentages[index] >= 0) {
                    check(bar.getVisibility() == View.VISIBLE, "Started comic has no progress bar");
                    check(bar.getProgress() == percentages[index], "Saved within-page position was lost");
                    check(recycled.getContentDescription().toString().contains(context.getString(
                            R.string.comic_in_progress_percent, percentages[index])), "Progress is not accessible");
                    check(!visibleText(recycled, context.getString(R.string.comic_status_completed)),
                            "A saved final position was treated as completed");
                } else {
                    check(bar.getVisibility() == View.GONE, "Progress bar leaked into a text status");
                    check(visibleText(recycled, context.getString(labels[index])), "Status label was lost");
                }
                ImageView cover = find(recycled, ImageView.class);
                TextView badge = describedText(recycled,
                        context.getString(R.string.comic_issue_number, item.seriesNumber));
                if (item.seriesNumber.isEmpty()) {
                    check(badge == null, "Missing issue number left a stale badge");
                } else {
                    check(badge != null && badge.getVisibility() == View.VISIBLE, "Issue badge was lost");
                    check(bounds((ViewGroup) recycled, cover).contains(bounds((ViewGroup) recycled, badge)),
                            "Issue badge extends outside the cover");
                }
            }

            SeriesGroup group = new SeriesGroup(1, "series:1", "Grouped series", List.of(unread, completed),
                    unread, 0, 0, 0);
            adapter.submit(List.of(), List.of(group), true);
            recycled = adapter.getView(0, recycled, parent);
            layout(recycled, context, density);
            check(cardHeight == recycled.getMeasuredHeight(), "Grouped series changed the card footprint");
            check(find(recycled, ProgressBar.class).getVisibility() == View.GONE, "Series totals were replaced by a bar");
            String total = context.getResources().getQuantityString(R.plurals.series_issue_count, 2, 2);
            String status = context.getResources().getQuantityString(R.plurals.series_unread_issue_count, 1, 1);
            check(visibleText(recycled, total + " · " + status), "Series totals or unread count were lost");
            TextView oldBadge = describedText(recycled, context.getString(R.string.comic_issue_number, atEnd.seriesNumber));
            check(oldBadge == null || oldBadge.getVisibility() == View.GONE, "An issue badge leaked into a grouped series");
            check(!atEnd.read && !strip.read, "Showing progress changed explicit read status");
        } finally { covers.close(); }
    }

    private static ReadingProgress comic(String title, String number) {
        ReadingProgress progress = new ReadingProgress();
        progress.uri = "test:" + title; progress.title = title; progress.seriesNumber = number;
        return progress;
    }

    private static void layout(View view, Context context, LibraryGridDensity density) {
        float scale = Math.max(1f, context.getResources().getConfiguration().fontScale);
        int width = Ui.dp(context, Math.round(density.minimumCardWidthDp * scale));
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, width, view.getMeasuredHeight());
    }

    private static Rect bounds(ViewGroup root, View child) {
        Rect bounds = new Rect(0, 0, child.getWidth(), child.getHeight());
        root.offsetDescendantRectToMyCoords(child, bounds);
        return bounds;
    }

    private static boolean visibleText(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof TextView label && text.contentEquals(label.getText())) return true;
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            if (visibleText(group.getChildAt(index), text)) return true;
        }
        return false;
    }

    private static TextView describedText(View view, String description) {
        if (view instanceof TextView label && label.getContentDescription() != null
                && description.contentEquals(label.getContentDescription())) return label;
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            TextView result = describedText(group.getChildAt(index), description);
            if (result != null) return result;
        }
        return null;
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            T result = find(group.getChildAt(index), type);
            if (result != null) return result;
        }
        return null;
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

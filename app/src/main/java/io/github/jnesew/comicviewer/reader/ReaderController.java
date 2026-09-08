package io.github.jnesew.comicviewer.reader;
import android.app.Activity;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;
import io.github.jnesew.comicviewer.data.ReaderPreferences;
import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.model.ReadingDirection;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.render.ComicCanvasView;
import io.github.jnesew.comicviewer.ui.dialog.ReaderOptionsDialog;
import io.github.jnesew.comicviewer.ui.dialog.ReaderNavigationDialogs;
import io.github.jnesew.comicviewer.ui.ReaderScreen;
import io.github.jnesew.comicviewer.util.SeriesNavigator;
import java.util.List;
import android.content.Context;
import io.github.jnesew.comicviewer.R;

/** Reader UI commands, separate from document ownership and Android Activity lifecycle. */
public final class ReaderController implements ReaderScreen.Listener, ComicCanvasView.Listener {
    public interface Host {
        void onHome();
        void onComicEditRequested(ReadingProgress progress);
        void onChromeVisibilityChanged(boolean visible);
        void onKeepScreenOnChanged();
    }
    private final ReaderSession session;
    private final Context context;
    private final Host host;
    public ReaderController(ReaderSession session, Host host) {
        this.session = session;
        this.context = session.context;
        this.host = host;
    }
    @Override public void onHome() { host.onHome(); }
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (session.readerActive && session.document() != null && event.getAction() == KeyEvent.ACTION_DOWN &&
                event.getRepeatCount() == 0) {
            if (session.preferences.volumeNavigation()) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    navigate(1);
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                    navigate(-1);
                    return true;
                }
            }
            if (session.preferences.matches("next", event) || session.preferences.matches("next_alt", event)) {
                navigate(1);
                return true;
            }
            if (session.preferences.matches("previous", event) || session.preferences.matches("previous_alt", event)) {
                navigate(-1);
                return true;
            }
            if (event.getMetaState() == 0) {
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_MOVE_HOME -> {
                        session.reader.canvas.showPage(0, 0f);
                        return true;
                    }
                    case KeyEvent.KEYCODE_MOVE_END -> {
                        session.reader.canvas.showPage(session.document().count() - 1, 0f);
                        return true;
                    }
                    case KeyEvent.KEYCODE_B -> {
                        onBookmark();
                        return true;
                    }
                    case KeyEvent.KEYCODE_W -> {
                        session.reader.canvas.fitWidth();
                        return true;
                    }
                    case KeyEvent.KEYCODE_F -> {
                        session.reader.canvas.fitPage();
                        return true;
                    }
                    case KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> {
                        session.reader.canvas.multiplyZoom(1.15f);
                        return true;
                    }
                    case KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> {
                        session.reader.canvas.multiplyZoom(1f / 1.15f);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public void onPrevious() {
        navigate(-1);
    }

    @Override
    public void onNext() {
        navigate(1);
    }

    @Override
    public void onSeek(int targetPage) {
        if (session.document() == null || session.document().isUnavailable()) return;
        session.reader.canvas.showPage(clamp(targetPage, 0, session.document().count() - 1), 0f);
        session.reader.keepChromeAwake();
    }

    @Override
    public void onPagePreviewRequested(int page) {
        if (session.pagePreviewLoader != null) session.pagePreviewLoader.request(page);
    }

    @Override
    public void onPagePreviewCancelled() {
        if (session.pagePreviewLoader != null) session.pagePreviewLoader.cancel();
    }

    @Override
    public void onBookmark() {
        if (session.document() == null || session.document().isUnavailable()) return;
        boolean added = session.database.toggleBookmark(session.document().key(), session.reader.canvas.page());
        session.reader.updateBookmark(added);
        Toast.makeText(context,
                added ? context.getString(R.string.reader_bookmarked_page, session.reader.canvas.page() + 1)
                        : context.getString(R.string.reader_removed_bookmark),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onLayoutMenu(View anchor) {
        PopupMenu menu = new PopupMenu(context, anchor);
        addLayoutChoice(menu, 1, R.string.reader_layout_single, ComicCanvasView.SINGLE);
        addLayoutChoice(menu, 2, R.string.reader_layout_spread, ComicCanvasView.SPREAD);
        addLayoutChoice(menu, 3, R.string.reader_layout_continuous, ComicCanvasView.CONTINUOUS);
        menu.getMenu().setGroupCheckable(0, true, true);
        menu.show();
    }

    private void addLayoutChoice(
            PopupMenu menu, int itemId, int label, String readingMode) {
        menu.getMenu().add(0, itemId, itemId, label)
                .setCheckable(true)
                .setChecked(readingMode.equals(session.reader.canvas.readingMode()))
                .setOnMenuItemClickListener(item -> {
                    if (!ComicCanvasView.CONTINUOUS.equals(readingMode) &&
                            session.reader.canvas.isContinuous()) {
                        session.continuous.leaveContinuousSession(readingMode);
                    } else {
                        session.reader.canvas.setReadingMode(readingMode);
                        if (ComicCanvasView.CONTINUOUS.equals(readingMode)) {
                            session.continuous.beginContinuousSession();
                        }
                    }
                    session.reader.updateMode(session.reader.canvas.readingMode());
                    session.saves.scheduleSave();
                    session.reader.keepChromeAwake();
                    return true;
                });
    }

    @Override
    public void onFitMenu(View anchor) {
        PopupMenu menu = new PopupMenu(context, anchor);
        menu.getMenu().add(R.string.reader_fit_width).setOnMenuItemClickListener(item -> {
            session.reader.canvas.fitWidth();
            return true;
        });
        menu.getMenu().add(R.string.reader_fit_page).setEnabled(!session.reader.canvas.isContinuous())
                .setOnMenuItemClickListener(item -> {
                    session.reader.canvas.fitPage();
                    return true;
                });
        menu.getMenu().add(R.string.reader_actual_size).setOnMenuItemClickListener(item -> {
            session.reader.canvas.actualSize();
            return true;
        });
        menu.getMenu().add(R.string.reader_zoom_in).setOnMenuItemClickListener(item -> {
            session.reader.canvas.multiplyZoom(1.2f);
            return true;
        });
        menu.getMenu().add(R.string.reader_zoom_out).setOnMenuItemClickListener(item -> {
            session.reader.canvas.multiplyZoom(1f / 1.2f);
            return true;
        });
        boolean gesturesLocked = session.reader.canvas.zoomGesturesLocked();
        menu.getMenu().add(gesturesLocked
                ? R.string.reader_unlock_zoom_gestures
                : R.string.reader_lock_zoom_gestures).setOnMenuItemClickListener(item -> {
                    boolean locked = !session.reader.canvas.zoomGesturesLocked();
                    session.reader.canvas.setZoomGesturesLocked(locked);
                    if (session.progress() != null) session.progress().zoomGesturesLocked = locked;
                    session.reader.updateZoom(
                            session.reader.canvas.zoomMode(), session.reader.canvas.zoom(), locked);
                    session.saves.scheduleSave();
                    return true;
                });
        menu.show();
    }

    @Override
    public void onMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(context, anchor);
        menu.getMenu().add(R.string.reader_jump_to_page)
                .setEnabled(session.document() != null && !session.document().isUnavailable()).setOnMenuItemClickListener(item -> {
            showJumpDialog();
            return true;
        });
        menu.getMenu().add(R.string.reader_bookmarks)
                .setEnabled(session.document() != null && !session.document().isUnavailable()).setOnMenuItemClickListener(item -> {
            showBookmarks();
            return true;
        });
        if (session.progress() != null) {
            menu.getMenu().add(session.progress().favorite
                    ? R.string.title_remove_favorite : R.string.title_add_favorite)
                    .setOnMenuItemClickListener(item -> {
                        toggleTitleFavorite();
                        return true;
                    });
            menu.getMenu().add(R.string.comic_edit)
                    .setOnMenuItemClickListener(item -> {
                        host.onComicEditRequested(session.progress());
                        return true;
                    });
            menu.getMenu().add(R.string.reader_reading_direction).setOnMenuItemClickListener(item -> {
                showReadingDirection();
                return true;
            });
        }
        menu.getMenu().add(R.string.reader_options).setOnMenuItemClickListener(item -> {
            showReaderOptions();
            return true;
        });
        menu.show();
    }

    @Override
    public void onChromeVisibilityChanged(boolean visible) {
        if (session.readerActive) host.onChromeVisibilityChanged(visible);
    }

    @Override
    public void onReaderPositionChanged(String documentKey, int page, float pageRatio) {
        if (session.document() == null || session.progress() == null) return;
        if (session.reader.canvas.isContinuous() && !documentKey.isEmpty() &&
                !documentKey.equals(session.document().key())) {
            if (!session.continuous.switchContinuousIssue(documentKey)) return;
        }
        session.progress().page = session.reader.canvas.pageEnd();
        session.progress().scrollRatio = pageRatio;
        session.reader.updatePosition(page, session.reader.canvas.pageEnd(), session.document().count());
        session.reader.updateBookmark(session.database.isBookmarked(session.document().key(), page));
        session.saves.scheduleSave();
    }

    @Override
    public void onReaderZoomChanged(String mode, float zoom) {
        if (session.progress() == null) return;
        session.progress().zoomMode = mode;
        session.progress().zoom = zoom;
        session.reader.updateZoom(mode, zoom, session.reader.canvas.zoomGesturesLocked());
        session.saves.scheduleSave();
    }

    @Override
    public void onNavigateRequested(int delta) {
        navigate(delta);
    }

    @Override
    public void onChromeToggleRequested() {
        session.reader.toggleChrome();
    }

    @Override
    public void onContinuousBoundaryApproached(int direction) {
        session.continuous.requestContinuousAdjacent(direction, false);
    }

    @Override
    public void onContinuousBoundaryRetry(int direction) {
        session.continuous.requestContinuousAdjacent(direction, true);
    }

    @Override
    public void onRetryUnavailable() {
        if (session.document() != null && session.document().isUnavailable()) onUnavailableRetry(session.document().key());
    }

    @Override
    public void onUnavailableRetry(String key) {
        if (session.comicOpening) return;
        session.openComic(Uri.parse(key), false, ReaderSession.OpenPosition.BEGINNING, session.reader.canvas.readingMode());
    }

    private void navigate(int delta) {
        if (session.document() == null || session.comicOpening) return;
        if (session.reader.canvas.isContinuous()) {
            session.saves.saveNow();
            if (session.reader.canvas.moveContinuousPage(delta)) return;
        }
        int target = session.reader.canvas.navigationTarget(delta);
        if (target == session.reader.canvas.page()) {
            if (delta > 0 && session.reader.canvas.isAtDocumentEnd() && openAdjacentSeriesIssue(1)) return;
            if (delta < 0 && session.reader.canvas.isAtDocumentStart() && openAdjacentSeriesIssue(-1)) return;
            Toast.makeText(context,
                    target == 0 ? R.string.reader_first_page : R.string.reader_last_page,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        session.saves.saveNow();
        session.reader.canvas.showPage(target, 0f);
    }

    private boolean openAdjacentSeriesIssue(int direction) {
        if (session.progress() == null || session.progress().seriesId <= 0L) return false;
        List<ReadingProgress> issues = session.database.seriesIssues(session.progress().seriesId);
        if (issues.size() <= 1) return false;
        ReadingProgress adjacent = direction > 0
                ? SeriesNavigator.nextIssue(issues, session.progress().uri)
                : SeriesNavigator.previousIssue(issues, session.progress().uri);
        if (adjacent == null) {
            Toast.makeText(context, direction > 0
                            ? R.string.reader_end_of_series : R.string.reader_start_of_series,
                    Toast.LENGTH_SHORT).show();
            return true;
        }
        if (!adjacent.available) {
            if (session.reader.canvas.isContinuous()) {
                session.continuous.requestContinuousAdjacent(direction, false);
            } else {
                session.continuous.cancelContinuousPrefetch();
                ++session.openGeneration;
                session.activateUnavailable(adjacent, session.reader.canvas.readingMode());
            }
            return true;
        }
        if (session.reader.canvas.isContinuous()) {
            session.continuous.requestContinuousAdjacent(direction, false);
            return true;
        }
        session.saves.saveNow();
        String readingMode = session.reader.canvas.readingMode();
        ReaderSession.OpenPosition position = direction > 0 ? ReaderSession.OpenPosition.BEGINNING : ReaderSession.OpenPosition.END;
        session.openComic(Uri.parse(adjacent.uri), false, position, readingMode);
        return true;
    }

    private void showJumpDialog() {
        if (session.document() == null || session.document().isUnavailable()) return;
        ComicDocument selected = session.document();
        new ReaderNavigationDialogs(context).showJump(session.reader.canvas.page(), session.document().count(), page -> {
            if (session.document() == selected) session.reader.canvas.showPage(page, 0f);
        });
    }

    private void showBookmarks() {
        if (session.document() == null || session.document().isUnavailable()) return;
        ComicDocument selected = session.document();
        new ReaderNavigationDialogs(context).showBookmarks(session.database.bookmarks(session.document().key()), page -> {
            if (session.document() == selected) session.reader.canvas.showPage(page, 0f);
        });
    }

    private void toggleTitleFavorite() {
        if (session.document() == null || session.progress() == null) return;
        session.progress().favorite = session.database.toggleFavorite(session.document().key());
        Toast.makeText(context, context.getString(
                session.progress().favorite ? R.string.title_added_favorite : R.string.title_removed_favorite,
                session.progress().title), Toast.LENGTH_SHORT).show();
    }

    private ReaderOptionsDialog optionsDialog() {
        return new ReaderOptionsDialog(context, session.preferences, new ReaderOptionsDialog.Listener() {
            @Override public void onOptionsSaved(ReaderOptionsDialog.Options options) {
                session.preferences.setTapZones(options.tapZones());
                session.preferences.setVolumeNavigation(options.volumeNavigation());
                session.preferences.setRememberZoom(options.rememberZoom());
                session.preferences.setDefaultZoomMode(options.defaultZoom());
                session.preferences.setKeepScreenOn(options.keepScreenOn());
                session.preferences.setAutoHideControls(options.autoHideControls());
                session.reader.canvas.setTapZones(options.tapZones());
                host.onKeepScreenOnChanged();
                if (!options.autoHideControls() && session.readerActive) session.reader.showChrome();
            }
            @Override public void onThemeSelected(String key, int color) {
                session.preferences.setCanvasTheme(key);
                session.reader.canvas.setCanvasColor(color);
            }
            @Override public void onShortcutSelected(String action, ReaderPreferences.Shortcut shortcut) {
                session.preferences.setShortcut(action, shortcut);
            }
            @Override public void onShortcutsReset() { session.preferences.resetShortcuts(); }
        });
    }

    public void showReaderOptions() { optionsDialog().show(); }

    private void showReadingDirection() {
        if (session.document() == null || session.progress() == null) return;
        ComicDocument selected = session.document();
        optionsDialog().showReadingDirection(session.progress().readingDirection, direction -> {
            if (session.document() == selected) setReadingDirection(direction);
        });
    }

    private void setReadingDirection(String direction) {
        if (session.document() == null || session.progress() == null) return;
        String normalized = ReadingDirection.normalize(direction);
        session.progress().readingDirection = normalized;
        session.database.setReadingDirection(session.document().key(), normalized);
        session.reader.setRightToLeft(ReadingDirection.isRightToLeft(
                normalized, session.document().suggestedRightToLeft()));
        session.reader.keepChromeAwake();
    }
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

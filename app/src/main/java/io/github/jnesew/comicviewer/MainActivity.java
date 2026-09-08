package io.github.jnesew.comicviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentCallbacks2;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import io.github.jnesew.comicviewer.data.CoverStore;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.data.ReaderPreferences;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.model.SeriesGroup;
import io.github.jnesew.comicviewer.ui.HomeView;
import io.github.jnesew.comicviewer.library.LibraryQueryController;
import io.github.jnesew.comicviewer.library.LibraryImportCoordinator;
import io.github.jnesew.comicviewer.library.FolderScanCoordinator;
import io.github.jnesew.comicviewer.ui.dialog.ComicEditorDialog;
import io.github.jnesew.comicviewer.ui.ReaderScreen;
import io.github.jnesew.comicviewer.reader.ReaderSession;
import io.github.jnesew.comicviewer.reader.ReaderController;
import io.github.jnesew.comicviewer.util.LibraryFolderLabel;
import io.github.jnesew.comicviewer.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

public final class MainActivity extends Activity implements
        HomeView.Listener {

    private static final int REQUEST_IMPORT_COMICS = 4101;
    private static final int REQUEST_LIBRARY_FOLDER = 4102;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LibraryDatabase database;
    private LibraryImportCoordinator imports;
    private FolderScanCoordinator scans;
    private ReaderPreferences preferences;
    private HomeView home;
    private ReaderScreen reader;
    private ReaderSession session;
    private ReaderController readerController;
    private View loadingOverlay;
    private TextView loadingLabel;

    private boolean destroyed;
    private Object platformBackCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureEdgeToEdge();
        database = new LibraryDatabase(this);
        preferences = new ReaderPreferences(this);
        migrateLegacyReadingDirection();

        imports = new LibraryImportCoordinator(this, database, new LibraryImportCoordinator.Listener() {
            @Override public void onLibraryChanged() { home.refresh(); }
            @Override public void onImportFinished(int count) {
                Toast.makeText(MainActivity.this, count > 0 ? getResources().getQuantityString(
                        R.plurals.comics_added, count, count) : getString(R.string.comics_add_failed),
                        Toast.LENGTH_SHORT).show();
            }
        });
        scans = new FolderScanCoordinator(this, database, preferences, imports,
                () -> session != null && session.isBusy(), new FolderScanCoordinator.Listener() {
                    @Override public void onLibraryChanged() { home.refresh(); }
                    @Override public void onScanStatus(String detail, boolean scanning, boolean persistent) {
                        updateLibraryFolderUi(detail, scanning, persistent);
                    }
                });
        root = new FrameLayout(this);
        home = new HomeView(this, new LibraryQueryController(database), this);
        session = new ReaderSession(this, database, preferences, imports, new ReaderSession.Host() {
            @Override public boolean isFinishing() { return MainActivity.this.isFinishing(); }
            @Override public void onReaderActivated() { home.setVisibility(View.GONE); }
            @Override public void onLibraryChanged() { if (!destroyed) home.refresh(); }
            @Override public void onKeepScreenOnChanged() { applyKeepScreenOn(); }
            @Override public void onOpening(String message) { showLoading(message); }
            @Override public void onOpeningMessage(String message) { if (!destroyed) loadingLabel.setText(message); }
            @Override public void onLoadingFinished() { if (!destroyed) hideLoading(); }
            @Override public void onError(String title, String message) { if (!destroyed) showError(title, message); }
        });
        readerController = new ReaderController(session, new ReaderController.Host() {
            @Override public void onHome() { MainActivity.this.onHome(); }
            @Override public void onComicEditRequested(ReadingProgress item) { showComicEditor(item); }
            @Override public void onChromeVisibilityChanged(boolean visible) {
                if (session.isActive()) showSystemBars(visible);
            }
            @Override public void onKeepScreenOnChanged() { applyKeepScreenOn(); }
        });
        reader = new ReaderScreen(this, preferences, readerController);
        session.attach(reader);
        reader.canvas.setListener(readerController);
        reader.setVisibility(View.GONE);
        loadingOverlay = createLoadingOverlay();
        loadingOverlay.setVisibility(View.GONE);

        root.addView(home, matchParent());
        root.addView(reader, matchParent());
        root.addView(loadingOverlay, matchParent());
        setContentView(root);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            platformBackCallback = Api33Back.register(this);
        }
        showSystemBars(true);
        updateLibraryFolderUi("", false, false);

        Intent launchIntent = getIntent();
        if (launchIntent != null && launchIntent.getData() != null &&
                Intent.ACTION_VIEW.equals(launchIntent.getAction())) {
            Uri uri = launchIntent.getData();
            persistReadAccess(uri, launchIntent.getFlags());
            mainHandler.post(() -> session.openComic(uri, true));
        }
        imports.startCoverBackfill();
        imports.startMetadataBackfill();
        mainHandler.postDelayed(() -> scans.maybeScanLibraryFolder(false), 300L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getData() != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            persistReadAccess(intent.getData(), intent.getFlags());
            session.openComic(intent.getData(), true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_LIBRARY_FOLDER) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri selected = data.getData();
            persistReadAccess(selected, data.getFlags());
            scans.selectFolder(selected);
            return;
        }
        if (requestCode != REQUEST_IMPORT_COMICS || resultCode != RESULT_OK || data == null) return;
        LinkedHashSet<Uri> selected = new LinkedHashSet<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index++) {
                Uri uri = clip.getItemAt(index).getUri();
                if (uri != null) selected.add(uri);
            }
        }
        if (data.getData() != null) selected.add(data.getData());
        if (selected.isEmpty()) return;
        ArrayList<Uri> uris = new ArrayList<>(selected);
        for (Uri uri : uris) persistReadAccess(uri, data.getFlags());
        if (uris.size() == 1) session.openComic(uris.get(0), true);
        else imports.importComics(uris);
    }

    @Override
    protected void onPause() {
        session.saveNow();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (database != null && !session.isActive()) {
            mainHandler.postDelayed(() -> scans.maybeScanLibraryFolder(false), 350L);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                platformBackCallback != null) {
            Api33Back.unregister(this, platformBackCallback);
            platformBackCallback = null;
        }
        session.close();
        mainHandler.removeCallbacksAndMessages(null);
        imports.close();
        scans.close();
        home.close();
        Thread cleanup = new Thread(() -> {
            boolean interrupted = false;
            for (;;) {
                try {
                    session.awaitStopped();
                    imports.awaitStopped();
                    scans.awaitStopped();
                    break;
                } catch (InterruptedException ignored) { interrupted = true; }
            }
            database.close();
            if (interrupted) Thread.currentThread().interrupt();
        }, "comic-database-close");
        cleanup.start();
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            home.trimCoverCache();
            session.trimMemory();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        home.trimCoverCache();
        session.trimMemory();
    }

    @Override
    @SuppressLint("GestureBackNavigation")
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (handleInternalBack()) return;
        super.onBackPressed();
    }

    private boolean handleInternalBack() {
        if (loadingOverlay.getVisibility() == View.VISIBLE) {
            session.cancelOpening();
            return true;
        }
        if (session.isActive()) {
            onHome();
            return true;
        }
        return home.handleBack();
    }

    private void handlePlatformBack() {
        if (!handleInternalBack()) finishAfterTransition();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return readerController != null && readerController.dispatchKeyEvent(event)
                || super.dispatchKeyEvent(event);
    }

    @Override
    public void onOpenRequested() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.comicbook+zip", "application/x-cbz", "application/zip",
                "application/epub+zip", "application/pdf"
        });
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, REQUEST_IMPORT_COMICS);
    }

    @Override
    public void onFavoriteRequested(ReadingProgress item) {
        database.toggleFavorite(item.uri);
        home.refresh();
    }

    @Override
    public void onRecentRequested(ReadingProgress recent) {
        if (!recent.available) {
            confirmForgetTitle(recent, R.string.forget_unavailable_message);
            return;
        }
        try {
            session.openComic(Uri.parse(recent.uri), false);
        } catch (RuntimeException error) {
            showError(getString(R.string.error_open_recent_title),
                    getString(R.string.error_saved_reference_invalid));
        }
    }

    @Override
    public void onForgetRequested(ReadingProgress recent) {
        confirmForgetTitle(recent, R.string.forget_message);
    }

    private void confirmForgetTitle(ReadingProgress recent, int message) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.forget_title, recent.title))
                .setMessage(message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.forget, (dialog, which) -> {
                    forgetEntries(Collections.singletonList(recent));
                })
                .show();
    }

    @Override
    public void onSeriesForgetRequested(SeriesGroup series) {
        int count = series.issues.size();
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.forget_series_title, series.title))
                .setMessage(getResources().getQuantityString(
                        R.plurals.forget_series_message, count, count))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.forget_series, (dialog, which) ->
                        forgetEntries(series.issues))
                .show();
    }

    @Override
    public void onComicEditRequested(ReadingProgress item) {
        showComicEditor(item);
    }

    @Override
    public void onLibraryMenuRequested(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        boolean configured = !preferences.libraryFolderUri().isEmpty();
        menu.getMenu().add(configured
                ? R.string.library_change_folder : R.string.library_choose_folder)
                .setOnMenuItemClickListener(item -> {
                    chooseLibraryFolder();
                    return true;
                });
        if (configured) {
            menu.getMenu().add(R.string.library_rescan_folder)
                    .setEnabled(!scans.isRunning())
                    .setOnMenuItemClickListener(item -> {
                        scans.maybeScanLibraryFolder(true);
                        return true;
                    });
            menu.getMenu().add(R.string.library_stop_folder)
                    .setOnMenuItemClickListener(item -> {
                        confirmStopLibraryFolder();
                        return true;
                    });
        }
        List<ReadingProgress> confirmedMissing = configured
                ? database.confirmedMissingItems(preferences.libraryFolderUri())
                : Collections.emptyList();
        if (!confirmedMissing.isEmpty()) {
            int count = confirmedMissing.size();
            menu.getMenu().add(getResources().getQuantityString(
                            R.plurals.library_review_unavailable, count, count))
                    .setEnabled(!scans.isRunning())
                    .setOnMenuItemClickListener(item -> {
                        showConfirmedMissingReview();
                        return true;
                    });
        }
        menu.getMenu().add(home.coverSizeMenuLabel()).setOnMenuItemClickListener(item -> {
            home.showCoverSizeDialog();
            return true;
        });
        menu.getMenu().add(R.string.reader_options).setOnMenuItemClickListener(item -> {
            readerController.showReaderOptions();
            return true;
        });
        menu.show();
    }

    private void showConfirmedMissingReview() {
        if (scans.isRunning()) return;
        List<ReadingProgress> missing = database.confirmedMissingItems(
                preferences.libraryFolderUri());
        if (missing.isEmpty()) {
            Toast.makeText(this, R.string.library_no_confirmed_missing,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence[] labels = new CharSequence[missing.size()];
        boolean[] selected = new boolean[missing.size()];
        for (int index = 0; index < missing.size(); index++) {
            ReadingProgress item = missing.get(index);
            labels[index] = missingItemLabel(item);
            selected[index] = true;
        }
        int count = missing.size();
        new AlertDialog.Builder(this)
                .setTitle(getResources().getQuantityString(
                        R.plurals.library_unavailable_count, count, count))
                .setMultiChoiceItems(labels, selected,
                        (dialog, which, checked) -> selected[which] = checked)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.continue_action, (dialog, which) -> {
                    ArrayList<ReadingProgress> chosen = new ArrayList<>();
                    for (int index = 0; index < missing.size(); index++) {
                        if (selected[index]) chosen.add(missing.get(index));
                    }
                    if (!chosen.isEmpty()) confirmForgetMissing(chosen);
                })
                .show();
    }

    private void confirmForgetMissing(List<ReadingProgress> missing) {
        int count = missing.size();
        new AlertDialog.Builder(this)
                .setTitle(getResources().getQuantityString(
                        R.plurals.forget_missing_title, count, count))
                .setMessage(getResources().getQuantityString(
                        R.plurals.forget_missing_message, count, count))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.forget, (dialog, which) -> forgetEntries(missing))
                .show();
    }

    private String missingItemLabel(ReadingProgress item) {
        if (item.seriesTitle.trim().isEmpty()) return item.title;
        if (item.seriesNumber.trim().isEmpty()) {
            return getString(R.string.library_missing_series_item,
                    item.seriesTitle, item.title);
        }
        return getString(R.string.library_missing_numbered_series_item,
                item.seriesTitle, item.seriesNumber, item.title);
    }

    private void forgetEntries(List<ReadingProgress> entries) {
        ArrayList<String> uris = new ArrayList<>();
        for (ReadingProgress entry : entries) {
            if (entry != null && !entry.uri.trim().isEmpty()) uris.add(entry.uri);
        }
        for (String cover : database.forgetAll(uris)) CoverStore.delete(this, cover);
        for (String uri : uris) {
            try {
                releaseReadAccess(Uri.parse(uri));
            } catch (RuntimeException ignored) {
            }
        }
        home.refresh();
    }

    public void onHome() {
        session.leaveReader();
        reader.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        home.refresh();
        applyKeepScreenOn();
        showSystemBars(true);
        scans.maybeScanLibraryFolder(false);
    }

    private void chooseLibraryFolder() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(picker, REQUEST_LIBRARY_FOLDER);
    }

    private void confirmStopLibraryFolder() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.library_stop_folder_title)
                .setMessage(R.string.library_stop_folder_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.library_stop_folder, (dialog, which) ->
                        scans.stopLibraryFolder())
                .show();
    }

    private void updateLibraryFolderUi(
            String detail, boolean scanning, boolean persistent) {
        if (home == null || preferences == null) return;
        home.setLibraryFolderState(
                LibraryFolderLabel.compact(preferences.libraryFolderLabel()),
                detail, scanning, persistent);
    }

    private void showComicEditor(ReadingProgress item) {
        new ComicEditorDialog(this).show(database.get(item.uri), database.seriesNames(), edit -> {
            if (session.progress() != null && edit.uri().equals(session.progress().uri)) session.saveNow();
            database.setComicMetadata(edit.uri(), edit.title(), edit.seriesMode(),
                    edit.series(), edit.number());
            session.applyComicMetadataEdit(edit.uri());
        });
    }

    private void migrateLegacyReadingDirection() {
        if (!preferences.needsPerTitleDirectionMigration()) return;
        if (preferences.legacyRightToLeft()) database.migrateLegacyRightToLeftTitles();
        preferences.finishPerTitleDirectionMigration();
    }

    @SuppressLint({"NewApi", "UseRequiresApi"})
    private static final class Api33Back {
        private Api33Back() {
        }

        static Object register(MainActivity activity) {
            OnBackInvokedCallback callback = activity::handlePlatformBack;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(MainActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (OnBackInvokedCallback) callback);
        }
    }

    private View createLoadingOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(230, 8, 10, 13));
        overlay.setClickable(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        ProgressBar spinner = new ProgressBar(this);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(Ui.ACCENT));
        content.addView(spinner, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        loadingLabel = Ui.text(this, getString(R.string.reader_opening), 16, Ui.TEXT);
        loadingLabel.setGravity(Gravity.CENTER);
        loadingLabel.setPadding(Ui.dp(this, 24), Ui.dp(this, 18), Ui.dp(this, 24), 0);
        content.addView(loadingLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        overlay.addView(content, params);
        return overlay;
    }

    private void showLoading(String message) {
        loadingLabel.setText(message);
        loadingOverlay.setVisibility(View.VISIBLE);
        loadingOverlay.bringToFront();
    }

    private void hideLoading() {
        loadingOverlay.setVisibility(View.GONE);
    }

    private void persistReadAccess(Uri uri, int flags) {
        if (uri == null || !"content".equals(uri.getScheme())) return;
        int takeFlags = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        takeFlags &= Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (takeFlags == 0) takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // Some providers grant session access only; opening still works for this session.
        }
    }

    private void releaseReadAccess(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return;
        try {
            getContentResolver().releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // The provider may already have revoked or only temporarily granted access.
        }
    }

    private void configureEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.BLACK);
        }
    }

    private void showSystemBars(boolean visible) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller == null) return;
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (visible) controller.show(WindowInsets.Type.systemBars());
            else controller.hide(WindowInsets.Type.systemBars());
        } else {
            int layout = View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            int hidden = View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            getWindow().getDecorView().setSystemUiVisibility(visible ? layout : layout | hidden);
        }
    }

    private void applyKeepScreenOn() {
        if (session.isActive() && preferences.keepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static FrameLayout.LayoutParams matchWidthWrapHeight() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}

package io.github.jnesew.comicviewer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentCallbacks2;
import android.content.DialogInterface;
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
import android.view.inputmethod.EditorInfo;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.text.InputType;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import io.github.jnesew.comicviewer.data.CoverStore;
import io.github.jnesew.comicviewer.data.ContentFingerprint;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.data.LibraryFolderScanner;
import io.github.jnesew.comicviewer.data.ReaderPreferences;
import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.document.DocumentInfo;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.ReadingDirection;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.model.SeriesMetadata;
import io.github.jnesew.comicviewer.render.ComicCanvasView;
import io.github.jnesew.comicviewer.render.PagePreviewLoader;
import io.github.jnesew.comicviewer.render.TileRenderer;
import io.github.jnesew.comicviewer.ui.HomeView;
import io.github.jnesew.comicviewer.ui.ReaderScreen;
import io.github.jnesew.comicviewer.util.LibraryFolderLabel;
import io.github.jnesew.comicviewer.util.LibraryScanResult;
import io.github.jnesew.comicviewer.util.Ui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity implements
        HomeView.Listener,
        ReaderScreen.Listener,
        ComicCanvasView.Listener {

    private static final int REQUEST_IMPORT_COMICS = 4101;
    private static final int REQUEST_LIBRARY_FOLDER = 4102;
    private static final long AUTO_SCAN_COOLDOWN_MS = 5_000L;
    private static final String[] SHORTCUT_ACTIONS = {
            "next", "previous", "next_alt", "previous_alt"
    };

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService archiveLoader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "comic-archive-loader");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final ExecutorService indexWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "comic-index-worker");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final ExecutorService libraryWorker = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "comic-library-worker");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final ExecutorService scanWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "comic-folder-scanner");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final Set<String> libraryJobs = Collections.synchronizedSet(new java.util.HashSet<>());
    private final AtomicBoolean folderScanRunning = new AtomicBoolean();

    private FrameLayout root;
    private LibraryDatabase database;
    private ReaderPreferences preferences;
    private HomeView home;
    private ReaderScreen reader;
    private View loadingOverlay;
    private TextView loadingLabel;

    private ComicDocument archive;
    private TileRenderer tileRenderer;
    private PagePreviewLoader pagePreviewLoader;
    private ReadingProgress progress;
    private volatile boolean readerActive;
    private volatile boolean comicOpening;
    private volatile int openGeneration;
    private volatile boolean destroyed;
    private volatile long lastFolderScanStarted;
    private volatile String pendingReleaseFolderUri = "";
    private volatile Future<?> folderScanTask;
    private volatile int folderScanGeneration;
    private Object platformBackCallback;

    private final Runnable deferredSave = this::saveNow;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureEdgeToEdge();
        database = new LibraryDatabase(this);
        preferences = new ReaderPreferences(this);
        migrateLegacyReadingDirection();

        root = new FrameLayout(this);
        home = new HomeView(this, database, this);
        reader = new ReaderScreen(this, preferences, this);
        reader.canvas.setListener(this);
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
            mainHandler.post(() -> openComic(uri, true));
        }
        startCoverBackfill();
        startMetadataBackfill();
        mainHandler.postDelayed(() -> maybeScanLibraryFolder(false), 300L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getData() != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            persistReadAccess(intent.getData(), intent.getFlags());
            openComic(intent.getData(), true);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_LIBRARY_FOLDER) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri selected = data.getData();
            String previous = preferences.libraryFolderUri();
            folderScanGeneration++;
            Future<?> activeScan = folderScanTask;
            if (activeScan != null) activeScan.cancel(true);
            folderScanRunning.set(false);
            persistReadAccess(selected, data.getFlags());
            DocumentInfo folder = ComicDocumentFactory.describe(this, selected);
            preferences.setLibraryFolder(
                    selected.toString(), LibraryFolderLabel.compact(folder.displayName));
            if (!previous.isEmpty() && !previous.equals(selected.toString())) {
                pendingReleaseFolderUri = previous;
            }
            updateLibraryFolderUi("", false, false);
            lastFolderScanStarted = 0L;
            maybeScanLibraryFolder(true);
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
        if (uris.size() == 1) openComic(uris.get(0), true);
        else importComics(uris);
    }

    @Override
    protected void onPause() {
        saveNow();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (database != null && !readerActive) {
            mainHandler.postDelayed(() -> maybeScanLibraryFolder(false), 350L);
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
        openGeneration++;
        mainHandler.removeCallbacks(deferredSave);
        saveNow();
        closeCurrentArchive();
        archiveLoader.shutdownNow();
        indexWorker.shutdownNow();
        libraryWorker.shutdownNow();
        scanWorker.shutdownNow();
        home.close();
        database.close();
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            home.trimCoverCache();
            if (pagePreviewLoader != null) pagePreviewLoader.trimMemory();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        home.trimCoverCache();
        if (pagePreviewLoader != null) pagePreviewLoader.trimMemory();
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
            openGeneration++;
            comicOpening = false;
            hideLoading();
            return true;
        }
        if (readerActive) {
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
        if (readerActive && archive != null && event.getAction() == KeyEvent.ACTION_DOWN &&
                event.getRepeatCount() == 0) {
            if (preferences.volumeNavigation()) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    navigate(1);
                    return true;
                }
                if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
                    navigate(-1);
                    return true;
                }
            }
            if (preferences.matches("next", event) || preferences.matches("next_alt", event)) {
                navigate(1);
                return true;
            }
            if (preferences.matches("previous", event) || preferences.matches("previous_alt", event)) {
                navigate(-1);
                return true;
            }
            if (event.getMetaState() == 0) {
                switch (event.getKeyCode()) {
                    case KeyEvent.KEYCODE_MOVE_HOME -> {
                        reader.canvas.showPage(0, 0f);
                        return true;
                    }
                    case KeyEvent.KEYCODE_MOVE_END -> {
                        reader.canvas.showPage(archive.count() - 1, 0f);
                        return true;
                    }
                    case KeyEvent.KEYCODE_B -> {
                        onBookmark();
                        return true;
                    }
                    case KeyEvent.KEYCODE_W -> {
                        reader.canvas.fitWidth();
                        return true;
                    }
                    case KeyEvent.KEYCODE_F -> {
                        reader.canvas.fitPage();
                        return true;
                    }
                    case KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> {
                        reader.canvas.multiplyZoom(1.15f);
                        return true;
                    }
                    case KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> {
                        reader.canvas.multiplyZoom(1f / 1.15f);
                        return true;
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event);
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
    public void onRecentRequested(ReadingProgress recent) {
        if (!recent.available) {
            showError(getString(R.string.error_open_recent_title),
                    getString(R.string.error_library_source_unavailable));
            return;
        }
        try {
            openComic(Uri.parse(recent.uri), false);
        } catch (RuntimeException error) {
            showError(getString(R.string.error_open_recent_title),
                    getString(R.string.error_saved_reference_invalid));
        }
    }

    @Override
    public void onForgetRequested(ReadingProgress recent) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.forget_title, recent.title))
                .setMessage(R.string.forget_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.forget, (dialog, which) -> {
                    String cover = database.forget(recent.uri);
                    CoverStore.delete(this, cover);
                    try {
                        getContentResolver().releasePersistableUriPermission(
                                Uri.parse(recent.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (RuntimeException ignored) {
                    }
                    home.refresh();
                })
                .show();
    }

    @Override
    public void onSeriesEditRequested(ReadingProgress item) {
        showSeriesAssignment(item);
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
                    .setEnabled(!folderScanRunning.get())
                    .setOnMenuItemClickListener(item -> {
                        lastFolderScanStarted = 0L;
                        maybeScanLibraryFolder(true);
                        return true;
                    });
            menu.getMenu().add(R.string.library_stop_folder)
                    .setOnMenuItemClickListener(item -> {
                        confirmStopLibraryFolder();
                        return true;
                    });
        }
        menu.getMenu().add(home.coverSizeMenuLabel()).setOnMenuItemClickListener(item -> {
            home.showCoverSizeDialog();
            return true;
        });
        menu.getMenu().add(R.string.reader_options).setOnMenuItemClickListener(item -> {
            showReaderOptions();
            return true;
        });
        menu.show();
    }

    @Override
    public void onHome() {
        openGeneration++;
        comicOpening = false;
        saveNow();
        closeCurrentArchive();
        readerActive = false;
        reader.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        home.refresh();
        applyKeepScreenOn();
        showSystemBars(true);
        maybeScanLibraryFolder(false);
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
        if (archive == null) return;
        reader.canvas.showPage(clamp(targetPage, 0, archive.count() - 1), 0f);
        reader.keepChromeAwake();
    }

    @Override
    public void onPagePreviewRequested(int page) {
        if (pagePreviewLoader != null) pagePreviewLoader.request(page);
    }

    @Override
    public void onPagePreviewCancelled() {
        if (pagePreviewLoader != null) pagePreviewLoader.cancel();
    }

    @Override
    public void onBookmark() {
        if (archive == null) return;
        boolean added = database.toggleBookmark(archive.key(), reader.canvas.page());
        reader.updateBookmark(added);
        Toast.makeText(this,
                added ? getString(R.string.reader_bookmarked_page, reader.canvas.page() + 1)
                        : getString(R.string.reader_removed_bookmark),
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onToggleMode() {
        reader.canvas.setReadingMode(!reader.canvas.isContinuous());
        reader.updateMode(reader.canvas.isContinuous());
        scheduleSave();
    }

    @Override
    public void onFitMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.reader_fit_width).setOnMenuItemClickListener(item -> {
            reader.canvas.fitWidth();
            return true;
        });
        menu.getMenu().add(R.string.reader_fit_page).setEnabled(!reader.canvas.isContinuous())
                .setOnMenuItemClickListener(item -> {
                    reader.canvas.fitPage();
                    return true;
                });
        menu.getMenu().add(R.string.reader_actual_size).setOnMenuItemClickListener(item -> {
            reader.canvas.actualSize();
            return true;
        });
        menu.getMenu().add(R.string.reader_zoom_in).setOnMenuItemClickListener(item -> {
            reader.canvas.multiplyZoom(1.2f);
            return true;
        });
        menu.getMenu().add(R.string.reader_zoom_out).setOnMenuItemClickListener(item -> {
            reader.canvas.multiplyZoom(1f / 1.2f);
            return true;
        });
        boolean gesturesLocked = reader.canvas.zoomGesturesLocked();
        menu.getMenu().add(gesturesLocked
                ? R.string.reader_unlock_zoom_gestures
                : R.string.reader_lock_zoom_gestures).setOnMenuItemClickListener(item -> {
                    boolean locked = !reader.canvas.zoomGesturesLocked();
                    reader.canvas.setZoomGesturesLocked(locked);
                    if (progress != null) progress.zoomGesturesLocked = locked;
                    reader.updateZoom(
                            reader.canvas.zoomMode(), reader.canvas.zoom(), locked);
                    scheduleSave();
                    return true;
                });
        menu.show();
    }

    @Override
    public void onMoreMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.reader_open_another).setOnMenuItemClickListener(item -> {
            onOpenRequested();
            return true;
        });
        menu.getMenu().add(R.string.reader_jump_to_page).setOnMenuItemClickListener(item -> {
            showJumpDialog();
            return true;
        });
        menu.getMenu().add(R.string.reader_bookmarks).setOnMenuItemClickListener(item -> {
            showBookmarks();
            return true;
        });
        if (progress != null) {
            menu.getMenu().add(progress.favorite
                    ? R.string.title_remove_favorite : R.string.title_add_favorite)
                    .setOnMenuItemClickListener(item -> {
                        toggleTitleFavorite();
                        return true;
                    });
            menu.getMenu().add(R.string.series_edit_assignment)
                    .setOnMenuItemClickListener(item -> {
                        showSeriesAssignment(progress);
                        return true;
                    });
            menu.getMenu().add(R.string.reader_reading_direction).setOnMenuItemClickListener(item -> {
                showReadingDirection();
                return true;
            });
        }
        menu.getMenu().add(R.string.reader_canvas_color).setOnMenuItemClickListener(item -> {
            showCanvasThemes();
            return true;
        });
        menu.getMenu().add(R.string.reader_options).setOnMenuItemClickListener(item -> {
            showReaderOptions();
            return true;
        });
        menu.getMenu().add(R.string.reader_hardware_shortcuts).setOnMenuItemClickListener(item -> {
            showKeyboardSettings();
            return true;
        });
        menu.getMenu().add(R.string.reader_hide_controls).setOnMenuItemClickListener(item -> {
            reader.toggleChrome();
            return true;
        });
        menu.show();
    }

    @Override
    public void onChromeVisibilityChanged(boolean visible) {
        if (readerActive) showSystemBars(visible);
    }

    @Override
    public void onReaderPositionChanged(int page, float pageRatio) {
        if (archive == null || progress == null) return;
        progress.page = page;
        progress.scrollRatio = pageRatio;
        reader.updatePosition(page, archive.count());
        reader.updateBookmark(database.isBookmarked(archive.key(), page));
        scheduleSave();
    }

    @Override
    public void onReaderZoomChanged(String mode, float zoom) {
        if (progress == null) return;
        progress.zoomMode = mode;
        progress.zoom = zoom;
        reader.updateZoom(mode, zoom, reader.canvas.zoomGesturesLocked());
        scheduleSave();
    }

    @Override
    public void onNavigateRequested(int delta) {
        navigate(delta);
    }

    @Override
    public void onChromeToggleRequested() {
        reader.toggleChrome();
    }

    private void openComic(Uri uri, boolean manualImport) {
        if (uri == null) return;
        comicOpening = true;
        int generation = ++openGeneration;
        showLoading(getString(R.string.reader_opening));
        archiveLoader.execute(() -> {
            boolean created = false;
            try {
                DocumentInfo document = ComicDocumentFactory.describe(this, uri);
                String sample = manualImport
                        ? sampleContent(uri, document.size) : "";
                created = database.get(uri.toString()).uri.isEmpty();
                ReadingProgress saved = database.ensureImported(
                        uri.toString(), document.title, document.size, document.modified);
                if (manualImport) {
                    database.markManualSource(saved.uri);
                    saved.manualSource = true;
                    if (!sample.isEmpty()) {
                        database.setLibraryFingerprint(
                                saved.uri, document.size, sample, saved.contentFingerprint);
                        saved.sampleSignature = sample;
                    }
                }
                List<PageInfo> cachedPages = saved.indexComplete
                        ? database.pageIndex(saved.uri) : Collections.emptyList();
                ComicDocument opened = ComicDocumentFactory.open(
                        this, uri, document, saved.page, cachedPages,
                        saved.documentSize, saved.documentModified, message ->
                        mainHandler.post(() -> {
                            if (generation == openGeneration) loadingLabel.setText(message);
                        }));
                database.updateTitle(opened.key(), opened.title());
                applySeriesMetadata(opened, null);
                saved.title = opened.title();
                mainHandler.post(() -> {
                    if (generation != openGeneration || isFinishing()) {
                        opened.close();
                        return;
                    }
                    activateArchive(opened, saved);
                    hideLoading();
                    startBackgroundIndex(opened, generation);
                });
            } catch (IOException | RuntimeException error) {
                if (created) {
                    String cover = database.forget(uri.toString());
                    CoverStore.delete(this, cover);
                }
                mainHandler.post(() -> {
                    if (generation != openGeneration || isFinishing()) return;
                    comicOpening = false;
                    hideLoading();
                    showError(getString(R.string.error_open_comic), safeMessage(error));
                });
            }
        });
    }

    private void activateArchive(ComicDocument opened, ReadingProgress saved) {
        saveNow();
        closeCurrentArchive();
        archive = opened;
        progress = saved;
        if (progress.uri.isEmpty()) {
            progress.uri = opened.key();
            progress.title = opened.title();
        }
        progress.title = opened.title();
        progress.pageCount = opened.count();
        progress.indexedPages = opened.indexedPages();
        progress.indexComplete = opened.isIndexComplete();
        progress.documentSize = opened.documentSize();
        progress.documentModified = opened.documentModified();
        progress.page = clamp(progress.page, 0, opened.count() - 1);
        if (!preferences.rememberZoom()) {
            progress.zoomMode = ComicCanvasView.FIT_WIDTH;
            progress.zoom = 1f;
        }

        tileRenderer = new TileRenderer(
                this,
                opened,
                reader.canvas::postInvalidateOnAnimation,
                message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
        pagePreviewLoader = new PagePreviewLoader(
                opened,
                Ui.dp(this, 112),
                Ui.dp(this, 144),
                new PagePreviewLoader.Callback() {
                    @Override
                    public void onPreviewReady(int page, android.graphics.Bitmap bitmap) {
                        reader.showPagePreview(page, bitmap);
                    }

                    @Override
                    public void onPreviewUnavailable(int page) {
                        reader.showPagePreviewUnavailable(page);
                    }
                });
        reader.canvas.setTapZones(preferences.tapZones());
        reader.canvas.setRightToLeft(ReadingDirection.isRightToLeft(
                progress.readingDirection, opened.suggestedRightToLeft()));
        reader.canvas.setCanvasColor(preferences.canvasColor());
        reader.canvas.setDocument(tileRenderer, opened.pages(), progress);
        reader.setTitle(opened.title());
        reader.updatePosition(progress.page, opened.count());
        reader.updateMode(reader.canvas.isContinuous());
        reader.updateZoom(reader.canvas.zoomMode(), reader.canvas.zoom(),
                reader.canvas.zoomGesturesLocked());
        reader.updateBookmark(database.isBookmarked(opened.key(), progress.page));

        readerActive = true;
        comicOpening = false;
        home.setVisibility(View.GONE);
        reader.setVisibility(View.VISIBLE);
        reader.showChrome();
        applyKeepScreenOn();
        saveNow();
    }

    private void startBackgroundIndex(ComicDocument opened, int generation) {
        indexWorker.execute(() -> {
            try {
                ReadingProgress libraryItem = database.get(opened.key());
                boolean ownsCoverJob = libraryJobs.add(opened.key());
                if (ownsCoverJob && !CoverStore.exists(libraryItem.coverPath)) {
                    try {
                        String coverPath = CoverStore.ensureCover(this, opened);
                        database.setCover(opened.key(), coverPath, LibraryDatabase.COVER_READY);
                        mainHandler.post(() -> {
                            if (generation == openGeneration && progress != null &&
                                    opened.key().equals(progress.uri)) {
                                progress.coverPath = coverPath;
                                progress.coverState = LibraryDatabase.COVER_READY;
                            }
                            home.refresh();
                        });
                    } catch (IOException | RuntimeException coverError) {
                        database.setCover(opened.key(), "", LibraryDatabase.COVER_FAILED);
                    } finally {
                        libraryJobs.remove(opened.key());
                    }
                } else if (ownsCoverJob) {
                    libraryJobs.remove(opened.key());
                }
                if (opened.isIndexComplete()) return;
                opened.buildPageIndex(this, null, new ComicDocument.IndexCallback() {
                    @Override
                    public boolean isCancelled() {
                        return destroyed || generation != openGeneration || archive != opened;
                    }

                    @Override
                    public void onProgress(int indexedPages, int pageCount) {
                        if (generation != openGeneration) return;
                        database.updateArchiveState(opened.key(), pageCount, indexedPages, false,
                                opened.documentSize(), opened.documentModified());
                    }

                    @Override
                    public void onPagesUpdated() {
                        mainHandler.post(() -> {
                            if (generation == openGeneration && archive == opened && readerActive) {
                                reader.canvas.onPageInfoChanged();
                            }
                        });
                    }

                    @Override
                    public void onComplete(List<PageInfo> pages) {
                        if (generation != openGeneration) return;
                        database.replacePageIndex(opened.key(), pages);
                        mainHandler.post(() -> {
                            if (generation == openGeneration && archive == opened && progress != null) {
                                progress.indexedPages = pages.size();
                                progress.indexComplete = true;
                                reader.canvas.onPageInfoChanged();
                                home.refresh();
                            }
                        });
                    }
                });
            } catch (IOException | RuntimeException ignored) {
                // Closing or replacing an active archive intentionally cancels its background index.
            }
        });
    }

    private void importComics(List<Uri> uris) {
        libraryWorker.execute(() -> {
            int imported = 0;
            for (Uri uri : uris) {
                if (destroyed || Thread.currentThread().isInterrupted()) break;
                if (processLibraryItem(uri, true, null, true)) imported++;
            }
            int count = imported;
            mainHandler.post(() -> {
                if (destroyed) return;
                home.refresh();
                Toast.makeText(this, count > 0 ? getResources().getQuantityString(
                        R.plurals.comics_added, count, count) :
                        getString(R.string.comics_add_failed), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void startCoverBackfill() {
        libraryWorker.execute(() -> {
            for (ReadingProgress item : database.coversNeedingBackfill(200)) {
                if (destroyed || Thread.currentThread().isInterrupted()) return;
                try {
                    processLibraryItem(Uri.parse(item.uri), false);
                } catch (RuntimeException ignored) {
                    database.setCover(item.uri, "", LibraryDatabase.COVER_FAILED);
                }
            }
        });
    }

    private void startMetadataBackfill() {
        libraryWorker.execute(() -> {
            for (ReadingProgress item : database.metadataNeedingBackfill(500)) {
                if (destroyed || Thread.currentThread().isInterrupted()) return;
                String key = item.uri;
                if (!libraryJobs.add(key)) continue;
                try {
                    Uri uri = Uri.parse(key);
                    DocumentInfo document = ComicDocumentFactory.describe(this, uri);
                    List<PageInfo> cachedPages = item.indexComplete
                            ? database.pageIndex(key) : Collections.emptyList();
                    try (ComicDocument opened = ComicDocumentFactory.open(
                            this, uri, document, item.page, cachedPages,
                            item.documentSize, item.documentModified, null)) {
                        database.updateTitle(key, opened.title());
                        applySeriesMetadata(opened, null);
                    }
                } catch (IOException | RuntimeException error) {
                    database.markMetadataFailed(key);
                } finally {
                    libraryJobs.remove(key);
                }
            }
            mainHandler.post(() -> {
                if (!destroyed) home.refresh();
            });
        });
    }

    private boolean processLibraryItem(Uri uri, boolean buildFullIndex) {
        return processLibraryItem(uri, buildFullIndex, null, false);
    }

    private boolean processLibraryItem(
            Uri uri,
            boolean buildFullIndex,
            LibraryFolderScanner.Entry sourceEntry,
            boolean manualImport) {
        String key = uri.toString();
        if (!libraryJobs.add(key)) return false;
        boolean created = false;
        try {
            DocumentInfo document = ComicDocumentFactory.describe(this, uri);
            String sample = manualImport
                    ? sampleContent(uri, document.size) : "";
            created = database.get(key).uri.isEmpty();
            ReadingProgress item = database.ensureImported(
                    key, document.title, document.size, document.modified);
            if (manualImport) {
                database.markManualSource(key);
                if (!sample.isEmpty()) {
                    database.setLibraryFingerprint(
                            key, document.size, sample, item.contentFingerprint);
                }
            }
            List<PageInfo> cachedPages = item.indexComplete
                    ? database.pageIndex(key) : Collections.emptyList();
            try (ComicDocument opened = ComicDocumentFactory.open(
                    this, uri, document, item.page, cachedPages,
                    item.documentSize, item.documentModified, null)) {
                database.updateTitle(key, opened.title());
                applySeriesMetadata(opened, sourceEntry);
                if (!CoverStore.exists(item.coverPath)) {
                    String cover = CoverStore.ensureCover(this, opened);
                    database.setCover(key, cover, LibraryDatabase.COVER_READY);
                }
                database.updateArchiveState(key, opened.count(), opened.indexedPages(),
                        opened.isIndexComplete(), opened.documentSize(), opened.documentModified());
                mainHandler.post(() -> {
                    if (!destroyed) home.refresh();
                });
                if (buildFullIndex && !opened.isIndexComplete()) {
                    opened.buildPageIndex(this, null, new ComicDocument.IndexCallback() {
                        @Override
                        public void onProgress(int indexedPages, int pageCount) {
                            database.updateArchiveState(key, pageCount, indexedPages, false,
                                    opened.documentSize(), opened.documentModified());
                        }

                        @Override
                        public void onPagesUpdated() {
                        }

                        @Override
                        public void onComplete(List<PageInfo> pages) {
                            database.replacePageIndex(key, pages);
                        }
                    });
                }
            }
            mainHandler.post(() -> {
                if (!destroyed) home.refresh();
            });
            return true;
        } catch (IOException | RuntimeException error) {
            if (created) {
                String cover = database.forget(key);
                CoverStore.delete(this, cover);
            } else {
                database.setCover(key, "", LibraryDatabase.COVER_FAILED);
            }
            return false;
        } finally {
            libraryJobs.remove(key);
        }
    }

    private void applySeriesMetadata(
            ComicDocument opened, LibraryFolderScanner.Entry sourceEntry) {
        SeriesMetadata metadata = opened.seriesMetadata();
        String folderKey = sourceEntry == null ? "" : sourceEntry.seriesFolderKey;
        String folderName = sourceEntry == null ? "" : sourceEntry.seriesFolderName;
        if (sourceEntry == null && !metadata.hasSeries()) {
            ReadingProgress current = database.get(opened.key());
            if (current.detectedSeriesKey.startsWith("folder:")) {
                folderKey = current.detectedSeriesKey.substring("folder:".length());
                folderName = current.detectedSeriesName;
            }
        }
        database.applyDetectedSeries(
                opened.key(), metadata.name, metadata.number, folderKey, folderName);
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
                        stopLibraryFolder())
                .show();
    }

    private void stopLibraryFolder() {
        String configured = preferences.libraryFolderUri();
        preferences.clearLibraryFolder();
        folderScanGeneration++;
        Future<?> activeScan = folderScanTask;
        if (activeScan != null) activeScan.cancel(true);
        folderScanRunning.set(false);
        if (!configured.isEmpty()) {
            database.markFolderUnavailable(configured);
            releaseReadAccess(Uri.parse(configured));
        }
        String pendingRelease = pendingReleaseFolderUri;
        if (!pendingRelease.isEmpty() && !pendingRelease.equals(configured)) {
            database.markFolderUnavailable(pendingRelease);
            releaseReadAccess(Uri.parse(pendingRelease));
        }
        pendingReleaseFolderUri = "";
        updateLibraryFolderUi("", false, false);
        home.refresh();
    }

    private void maybeScanLibraryFolder(boolean userRequested) {
        if (destroyed || preferences == null || database == null) return;
        String configured = preferences.libraryFolderUri();
        if (configured.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!userRequested && now - lastFolderScanStarted < AUTO_SCAN_COOLDOWN_MS) return;
        if (!folderScanRunning.compareAndSet(false, true)) return;
        int scanGeneration = ++folderScanGeneration;
        lastFolderScanStarted = now;
        updateLibraryFolderUi("", true, true);
        Uri treeUri;
        try {
            treeUri = Uri.parse(configured);
        } catch (RuntimeException exception) {
            if (scanGeneration == folderScanGeneration) folderScanRunning.set(false);
            updateLibraryFolderUi(
                    getString(R.string.library_scan_incomplete_short), false, true);
            return;
        }

        folderScanTask = scanWorker.submit(() -> {
            ScanCounts counts = new ScanCounts();
            long scanStarted = System.currentTimeMillis();
            LibraryFolderScanner.Summary traversal = null;
            try {
                traversal = LibraryFolderScanner.scan(
                        this, treeUri,
                        () -> destroyed || Thread.currentThread().isInterrupted() ||
                                scanGeneration != folderScanGeneration ||
                                !preferences.libraryFolderUri().equals(treeUri.toString()),
                        entry -> processScannedEntry(
                                entry, scanStarted, counts, scanGeneration));
                if (!destroyed && traversal.complete() && !readerActive && !comicOpening &&
                        preferences.libraryFolderUri().equals(treeUri.toString())) {
                    database.finishFolderScan(treeUri.toString(), scanStarted);
                    String pendingRelease = pendingReleaseFolderUri;
                    if (!pendingRelease.isEmpty() &&
                            !pendingRelease.equals(treeUri.toString())) {
                        database.markFolderUnavailable(pendingRelease);
                        releaseReadAccess(Uri.parse(pendingRelease));
                        pendingReleaseFolderUri = "";
                    }
                }
            } catch (IOException | RuntimeException error) {
                counts.errors++;
            } finally {
                if (scanGeneration == folderScanGeneration) folderScanRunning.set(false);
            }
            LibraryFolderScanner.Summary finished = traversal;
            mainHandler.post(() -> {
                if (destroyed || scanGeneration != folderScanGeneration) return;
                home.refresh();
                LibraryScanResult result = folderScanResult(counts, finished);
                String summary = folderScanSummary(result);
                updateLibraryFolderUi(summary, false, result.shouldPersist());
                if (!preferences.libraryFolderUri().equals(treeUri.toString())) {
                    lastFolderScanStarted = 0L;
                    maybeScanLibraryFolder(false);
                }
            });
        });
    }

    private void processScannedEntry(
            LibraryFolderScanner.Entry entry,
            long scanStarted,
            ScanCounts counts,
            int scanGeneration) {
        if (destroyed || Thread.currentThread().isInterrupted()) return;
        if (!scanStillConfigured(entry, scanGeneration)) return;
        String identity = entry.sourceIdentity();
        LibraryDatabase.ScannedFile existing = database.scannedFile(identity);
        String documentUri = entry.uri.toString();
        ReadingProgress existingProgress = existing == null
                ? new ReadingProgress() : database.get(existing.canonicalUri);
        boolean providerUnchanged = existing != null &&
                providerFingerprintMatches(existing, entry) && !existingProgress.uri.isEmpty();

        FingerprintProbe probe = new FingerprintProbe();
        if (existing != null) probe.sample = existing.sampleSignature;
        boolean newSource = existing == null;
        boolean scannerOwnsCanonical = existing != null &&
                existing.canonicalUri.equals(existing.documentUri);
        boolean safeToMerge = !readerActive && !comicOpening;
        if (newSource || (scannerOwnsCanonical && safeToMerge &&
                !existingProgress.uri.isEmpty())) {
            ReadingProgress manualMatch = findExactManualDuplicate(
                    entry, documentUri, probe);
            if (manualMatch != null && scanStillConfigured(entry, scanGeneration)) {
                LibraryDatabase.DuplicateMergeResult merge = null;
                if (!newSource) {
                    merge = database.mergeExactDuplicate(
                            manualMatch.uri, existing.canonicalUri,
                            probe.sample, probe.fullFingerprint);
                    if (!merge.merged) manualMatch = null;
                }
                if (manualMatch != null) {
                    database.upsertScannedFile(scannedFile(
                            entry, manualMatch.uri, probe.sample,
                            probe.fullFingerprint, scanStarted));
                    database.setLibraryFingerprint(
                            manualMatch.uri, entry.size,
                            probe.sample, probe.fullFingerprint);
                    reapplyFolderSeries(manualMatch.uri, entry);
                    if (merge != null) {
                        for (String cover : merge.obsoleteCoverPaths) {
                            CoverStore.delete(this, cover);
                        }
                    }
                    counts.duplicates++;
                    return;
                }
            }
        }

        if (providerUnchanged) {
            database.touchScannedFile(
                    identity, entry.uri.toString(), entry.relativePath,
                    entry.size, entry.modified, scanStarted);
            updateRenamedTitle(existing, entry);
            reapplyFolderSeries(existing.canonicalUri, entry);
            counts.unchanged++;
            return;
        }

        if ((readerActive || comicOpening) && existing != null &&
                existing.canonicalUri.equals(existing.documentUri) &&
                !existing.documentUri.equals(documentUri)) {
            counts.skipped++;
            return;
        }
        if (existing != null && existing.canonicalUri.equals(existing.documentUri) &&
                !existing.documentUri.equals(documentUri) && !readerActive && !comicOpening) {
            database.relinkCanonicalUri(existing.canonicalUri, documentUri);
        }

        if (probe.sample.isEmpty()) probe.sample = sampleContent(entry.uri, entry.size);

        if (existing == null && !probe.sample.isEmpty()) {
            for (LibraryDatabase.ScannedFile candidate : database.duplicateCandidates(
                    identity, entry.size, probe.sample)) {
                if (database.get(candidate.canonicalUri).uri.isEmpty()) continue;
                try {
                    if (probe.fullFingerprint.isEmpty()) {
                        probe.fullFingerprint = ContentFingerprint.full(this, entry.uri);
                    }
                    String candidateFingerprint = candidate.contentFingerprint;
                    if (candidateFingerprint.isEmpty()) {
                        candidateFingerprint = ContentFingerprint.full(
                                this, Uri.parse(candidate.documentUri));
                        database.setScannedFingerprint(
                                candidate.sourceIdentity, candidateFingerprint);
                    }
                    if (!probe.fullFingerprint.equals(candidateFingerprint)) continue;
                    if (!scanStillConfigured(entry, scanGeneration)) return;
                    database.upsertScannedFile(scannedFile(
                            entry, candidate.canonicalUri, probe.sample,
                            probe.fullFingerprint, scanStarted));
                    counts.duplicates++;
                    return;
                } catch (IOException | RuntimeException ignored) {
                    // An inaccessible candidate is not sufficient evidence to suppress this item.
                }
            }
        }

        boolean previouslyImported = existing != null || !database.get(documentUri).uri.isEmpty();
        if (!processLibraryItem(entry.uri, false, entry, false)) {
            counts.skipped++;
            return;
        }
        if (!scanStillConfigured(entry, scanGeneration)) {
            if (!previouslyImported) {
                String cover = database.forget(documentUri);
                CoverStore.delete(this, cover);
            }
            return;
        }
        database.upsertScannedFile(scannedFile(
                entry, documentUri, probe.sample, probe.fullFingerprint, scanStarted));
        if (previouslyImported) counts.updated++;
        else counts.imported++;
    }

    private void reapplyFolderSeries(
            String canonicalUri, LibraryFolderScanner.Entry entry) {
        ReadingProgress current = database.get(canonicalUri);
        if (current.uri.isEmpty()) return;
        boolean embedded = current.detectedSeriesKey.startsWith("metadata:");
        database.applyDetectedSeries(
                canonicalUri,
                embedded ? current.detectedSeriesName : "",
                embedded ? current.detectedSeriesNumber : "",
                entry.seriesFolderKey,
                entry.seriesFolderName);
    }

    private void updateRenamedTitle(
            LibraryDatabase.ScannedFile existing, LibraryFolderScanner.Entry entry) {
        if (existing.relativePath.equals(entry.relativePath)) return;
        ReadingProgress current = database.get(existing.canonicalUri);
        if (current.uri.isEmpty()) return;
        String oldFilename = existing.relativePath;
        int slash = oldFilename.lastIndexOf('/');
        if (slash >= 0) oldFilename = oldFilename.substring(slash + 1);
        String oldTitle = DocumentInfo.stripSupportedExtension(oldFilename);
        if (!current.title.equals(oldTitle)) return;
        database.updateTitle(
                existing.canonicalUri,
                DocumentInfo.stripSupportedExtension(entry.displayName));
    }

    private ReadingProgress findExactManualDuplicate(
            LibraryFolderScanner.Entry entry,
            String excludedCanonicalUri,
            FingerprintProbe probe) {
        if (libraryJobs.contains(excludedCanonicalUri)) return null;
        List<ReadingProgress> candidates = database.manualDuplicateCandidates(
                entry.size, excludedCanonicalUri);
        if (candidates.isEmpty()) return null;
        // Re-read both sides for the confirmation attempt. Persisted hashes are useful hints, but
        // a document provider may replace bytes without the title having been reopened first.
        probe.sample = sampleContent(entry.uri, entry.size);
        probe.fullFingerprint = "";
        if (probe.sample.isEmpty()) return null;

        for (ReadingProgress candidate : candidates) {
            if (candidate.uri.equals(entry.uri.toString()) ||
                    libraryJobs.contains(candidate.uri)) continue;
            try {
                Uri candidateUri = Uri.parse(candidate.uri);
                String candidateSample = ContentFingerprint.sample(
                        this, candidateUri, candidate.documentSize);
                database.setLibraryFingerprint(
                        candidate.uri, candidate.documentSize, candidateSample, "");
                if (!probe.sample.equals(candidateSample)) continue;
                if (probe.fullFingerprint.isEmpty()) {
                    probe.fullFingerprint = ContentFingerprint.full(this, entry.uri);
                }
                String candidateFingerprint = ContentFingerprint.full(this, candidateUri);
                database.setLibraryFingerprint(
                        candidate.uri, candidate.documentSize,
                        candidateSample, candidateFingerprint);
                if (probe.fullFingerprint.equals(candidateFingerprint)) return candidate;
            } catch (IOException | RuntimeException ignored) {
                // A revoked manual grant is not evidence that two entries are identical.
            }
        }
        return null;
    }

    private String sampleContent(Uri uri, long documentSize) {
        try {
            return ContentFingerprint.sample(this, uri, documentSize);
        } catch (IOException | RuntimeException ignored) {
            // Opening remains the authoritative readability and format validation step.
            return "";
        }
    }

    private static LibraryDatabase.ScannedFile scannedFile(
            LibraryFolderScanner.Entry entry,
            String canonicalUri,
            String sample,
            String fingerprint,
            long seenAt) {
        LibraryDatabase.ScannedFile result = new LibraryDatabase.ScannedFile();
        result.sourceIdentity = entry.sourceIdentity();
        result.treeUri = entry.treeUri;
        result.documentId = entry.documentId;
        result.documentUri = entry.uri.toString();
        result.relativePath = entry.relativePath;
        result.canonicalUri = canonicalUri;
        result.documentSize = entry.size;
        result.documentModified = entry.modified;
        result.sampleSignature = sample;
        result.contentFingerprint = fingerprint;
        result.lastSeen = seenAt;
        result.available = true;
        return result;
    }

    private static boolean providerFingerprintMatches(
            LibraryDatabase.ScannedFile existing, LibraryFolderScanner.Entry entry) {
        boolean sizeChanged = existing.documentSize >= 0L && entry.size >= 0L &&
                existing.documentSize != entry.size;
        boolean modifiedChanged = existing.documentModified >= 0L && entry.modified >= 0L &&
                existing.documentModified != entry.modified;
        return !sizeChanged && !modifiedChanged;
    }

    private boolean scanStillConfigured(
            LibraryFolderScanner.Entry entry, int scanGeneration) {
        return !destroyed && !Thread.currentThread().isInterrupted() &&
                scanGeneration == folderScanGeneration &&
                preferences.libraryFolderUri().equals(entry.treeUri);
    }

    private LibraryScanResult folderScanResult(
            ScanCounts counts, LibraryFolderScanner.Summary traversal) {
        int providerErrors = traversal == null ? 1 : traversal.providerErrors;
        boolean incomplete = counts.errors + providerErrors > 0 ||
                (traversal != null && traversal.bounded);
        return new LibraryScanResult(
                counts.imported, counts.updated, counts.unchanged,
                counts.duplicates, counts.skipped, incomplete);
    }

    private String folderScanSummary(LibraryScanResult result) {
        if (!result.shouldShow()) return "";
        ArrayList<String> parts = new ArrayList<>();
        if (result.incomplete) {
            parts.add(getString(R.string.library_scan_incomplete_short));
        }
        if (result.added > 0) {
            parts.add(getString(R.string.library_scan_added, result.added));
        }
        if (result.updated > 0) {
            parts.add(getString(R.string.library_scan_updated, result.updated));
        }
        if (result.duplicates > 0) {
            parts.add(getString(R.string.library_scan_duplicates, result.duplicates));
        }
        if (result.skipped > 0) {
            parts.add(getString(R.string.library_scan_skipped, result.skipped));
        }
        return String.join(" · ", parts);
    }

    private void updateLibraryFolderUi(
            String detail, boolean scanning, boolean persistent) {
        if (home == null || preferences == null) return;
        home.setLibraryFolderState(
                LibraryFolderLabel.compact(preferences.libraryFolderLabel()),
                detail, scanning, persistent);
    }

    private static final class ScanCounts {
        private int imported;
        private int updated;
        private int unchanged;
        private int duplicates;
        private int skipped;
        private int errors;
    }

    private static final class FingerprintProbe {
        private String sample = "";
        private String fullFingerprint = "";
    }

    private void navigate(int delta) {
        if (archive == null) return;
        int target = clamp(reader.canvas.page() + delta, 0, archive.count() - 1);
        if (target == reader.canvas.page()) {
            Toast.makeText(this,
                    target == 0 ? R.string.reader_first_page : R.string.reader_last_page,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        saveNow();
        reader.canvas.showPage(target, 0f);
    }

    private void scheduleSave() {
        mainHandler.removeCallbacks(deferredSave);
        mainHandler.postDelayed(deferredSave, 400L);
    }

    private void saveNow() {
        mainHandler.removeCallbacks(deferredSave);
        if (archive == null || progress == null) return;
        progress.uri = archive.key();
        progress.title = archive.title();
        progress.page = reader.canvas.page();
        progress.pageCount = archive.count();
        progress.scrollRatio = reader.canvas.pageRatio();
        progress.zoomMode = reader.canvas.zoomMode();
        progress.zoom = reader.canvas.zoom();
        progress.zoomGesturesLocked = reader.canvas.zoomGesturesLocked();
        progress.readingMode = reader.canvas.isContinuous() ? "continuous" : "single";
        progress.lastOpened = System.currentTimeMillis();
        database.saveReadingProgress(progress);
    }

    private void closeCurrentArchive() {
        reader.dismissPagePreview();
        reader.canvas.clearDocument();
        if (pagePreviewLoader != null) {
            pagePreviewLoader.close();
            pagePreviewLoader = null;
        }
        if (tileRenderer != null) {
            tileRenderer.close();
            tileRenderer = null;
        }
        if (archive != null) {
            archive.close();
            archive = null;
        }
        progress = null;
    }

    private void showJumpDialog() {
        if (archive == null) return;
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(Integer.toString(reader.canvas.page() + 1));
        input.selectAll();
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        FrameLayout wrapper = new FrameLayout(this);
        int side = Ui.dp(this, 24);
        wrapper.setPadding(side, Ui.dp(this, 4), side, 0);
        wrapper.addView(input, matchWidthWrapHeight());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reader_jump_to_page)
                .setMessage(getString(R.string.reader_jump_message, archive.count()))
                .setView(wrapper)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.go, null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        int value = Integer.parseInt(input.getText().toString().trim());
                        if (value < 1 || value > archive.count()) {
                            input.setError(getString(R.string.reader_page_range_error, archive.count()));
                            return;
                        }
                        reader.canvas.showPage(value - 1, 0f);
                        dialog.dismiss();
                    } catch (NumberFormatException error) {
                        input.setError(getString(R.string.reader_page_number_error));
                    }
                }));
        dialog.show();
        input.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void showBookmarks() {
        if (archive == null) return;
        List<Integer> bookmarks = database.bookmarks(archive.key());
        if (bookmarks.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.reader_bookmarks)
                    .setMessage(R.string.reader_no_bookmarks)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }
        String[] labels = new String[bookmarks.size()];
        for (int i = 0; i < bookmarks.size(); i++) {
            labels[i] = getString(R.string.reader_bookmark_page, bookmarks.get(i) + 1);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.reader_bookmarks)
                .setItems(labels, (dialog, which) -> reader.canvas.showPage(bookmarks.get(which), 0f))
                .setNegativeButton(R.string.close, null)
                .show();
    }

    private void toggleTitleFavorite() {
        if (archive == null || progress == null) return;
        progress.favorite = database.toggleFavorite(archive.key());
        Toast.makeText(this, getString(
                progress.favorite ? R.string.title_added_favorite : R.string.title_removed_favorite,
                archive.title()), Toast.LENGTH_SHORT).show();
    }

    private void showSeriesAssignment(ReadingProgress item) {
        ReadingProgress current = database.get(item.uri);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(Ui.dp(this, 22), Ui.dp(this, 4), Ui.dp(this, 22), 0);

        TextView explanation = Ui.text(
                this, getString(R.string.series_assignment_message), 14, Ui.TEXT_MUTED);
        explanation.setPadding(0, 0, 0, Ui.dp(this, 10));
        fields.addView(explanation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText name = new EditText(this);
        name.setHint(R.string.series_name_hint);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(current.seriesTitle);
        fields.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));

        EditText number = new EditText(this);
        number.setHint(R.string.series_issue_number_hint);
        number.setSingleLine(true);
        number.setInputType(InputType.TYPE_CLASS_TEXT);
        number.setText(current.seriesNumber);
        fields.addView(number, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)));

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.series_assignment_title, current.title))
                .setView(fields)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    database.setManualSeries(
                            current.uri, name.getText().toString(), number.getText().toString());
                    home.refresh();
                });
        if (current.seriesOverride != LibraryDatabase.SERIES_AUTOMATIC ||
                !current.detectedSeriesKey.isEmpty()) {
            builder.setNeutralButton(R.string.series_use_automatic, (dialog, which) -> {
                database.useAutomaticSeries(current.uri);
                home.refresh();
            });
        }
        builder.show();
    }

    private void showReadingDirection() {
        if (archive == null || progress == null) return;
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
        String current = ReadingDirection.normalize(progress.readingDirection);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 22), Ui.dp(this, 4), Ui.dp(this, 22), Ui.dp(this, 4));
        TextView explanation = Ui.text(
                this, getString(R.string.reading_direction_message), 14, Ui.TEXT_MUTED);
        explanation.setPadding(0, 0, 0, Ui.dp(this, 8));
        content.addView(explanation, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RadioGroup choices = new RadioGroup(this);
        int[] ids = new int[values.length];
        for (int index = 0; index < values.length; index++) {
            RadioButton choice = new RadioButton(this);
            ids[index] = View.generateViewId();
            choice.setId(ids[index]);
            choice.setText(labels[index]);
            choice.setTextColor(Ui.TEXT);
            choice.setTextSize(16);
            choice.setMinHeight(Ui.dp(this, 52));
            choice.setButtonTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{Ui.ACCENT, Ui.TEXT_MUTED}));
            choices.addView(choice, new RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)));
            if (values[index].equals(current)) choice.setChecked(true);
        }
        content.addView(choices);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reader_reading_direction)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .create();
        choices.setOnCheckedChangeListener((group, checkedId) -> {
            for (int index = 0; index < ids.length; index++) {
                if (ids[index] != checkedId) continue;
                setReadingDirection(values[index]);
                dialog.dismiss();
                return;
            }
        });
        dialog.show();
    }

    private void setReadingDirection(String direction) {
        if (archive == null || progress == null) return;
        String normalized = ReadingDirection.normalize(direction);
        progress.readingDirection = normalized;
        database.setReadingDirection(archive.key(), normalized);
        reader.canvas.setRightToLeft(ReadingDirection.isRightToLeft(
                normalized, archive.suggestedRightToLeft()));
        reader.keepChromeAwake();
    }

    private void showCanvasThemes() {
        LinkedHashMap<String, Integer> themes = ReaderPreferences.canvasThemes();
        ArrayList<String> keys = new ArrayList<>(themes.keySet());
        String[] labels = new String[keys.size()];
        int selected = 0;
        for (int i = 0; i < keys.size(); i++) {
            labels[i] = ReaderPreferences.themeLabel(this, keys.get(i));
            if (keys.get(i).equals(preferences.canvasTheme())) selected = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.reader_canvas_color)
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    preferences.setCanvasTheme(keys.get(which));
                    reader.canvas.setCanvasColor(themes.get(keys.get(which)));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showReaderOptions() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 4));
        scroll.addView(options);

        CheckBox tapZones = checkBox(R.string.option_tap_zones, preferences.tapZones());
        CheckBox volume = checkBox(R.string.option_volume_navigation, preferences.volumeNavigation());
        CheckBox remember = checkBox(R.string.option_remember_zoom, preferences.rememberZoom());
        CheckBox screen = checkBox(R.string.option_keep_screen_awake, preferences.keepScreenOn());
        CheckBox autoHide = checkBox(R.string.option_auto_hide_controls, preferences.autoHideControls());
        options.addView(tapZones);
        options.addView(volume);
        options.addView(remember);
        options.addView(screen);
        options.addView(autoHide);

        new AlertDialog.Builder(this)
                .setTitle(R.string.reader_options)
                .setView(scroll)
                .setNeutralButton(R.string.reader_hardware_shortcuts,
                        (dialog, which) -> showKeyboardSettings())
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    preferences.setTapZones(tapZones.isChecked());
                    preferences.setVolumeNavigation(volume.isChecked());
                    preferences.setRememberZoom(remember.isChecked());
                    preferences.setKeepScreenOn(screen.isChecked());
                    preferences.setAutoHideControls(autoHide.isChecked());
                    reader.canvas.setTapZones(tapZones.isChecked());
                    applyKeepScreenOn();
                    if (!autoHide.isChecked() && readerActive) reader.showChrome();
                })
                .show();
    }

    private void migrateLegacyReadingDirection() {
        if (!preferences.needsPerTitleDirectionMigration()) return;
        if (preferences.legacyRightToLeft()) database.migrateLegacyRightToLeftTitles();
        preferences.finishPerTitleDirectionMigration();
    }

    private void showKeyboardSettings() {
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 4));
        AlertDialog[] holder = new AlertDialog[1];
        for (String action : SHORTCUT_ACTIONS) {
            TextView button = Ui.text(this,
                    shortcutLabel(action) + "\n" + preferences.shortcut(action).label(), 15, Ui.TEXT);
            button.setGravity(Gravity.CENTER_VERTICAL);
            button.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            button.setBackground(Ui.rounded(Ui.SURFACE_HIGH, Ui.dp(this, 12), 0, 0));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64));
            params.bottomMargin = Ui.dp(this, 8);
            rows.addView(button, params);
            button.setOnClickListener(view -> captureShortcut(action, () -> {
                if (holder[0] != null) holder[0].dismiss();
                showKeyboardSettings();
            }));
        }
        holder[0] = new AlertDialog.Builder(this)
                .setTitle(R.string.reader_hardware_shortcuts)
                .setMessage(R.string.shortcuts_instruction)
                .setView(rows)
                .setNeutralButton(R.string.shortcuts_reset_defaults, (dialog, which) -> {
                    preferences.resetShortcuts();
                    Toast.makeText(this, R.string.shortcuts_reset, Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(R.string.done, null)
                .create();
        holder[0].show();
    }

    @SuppressLint("GestureBackNavigation")
    private void captureShortcut(String action, Runnable onSaved) {
        TextView prompt = Ui.text(this, getString(R.string.shortcuts_press_combination), 17, Ui.TEXT);
        prompt.setGravity(Gravity.CENTER);
        prompt.setPadding(Ui.dp(this, 24), Ui.dp(this, 28), Ui.dp(this, 24), Ui.dp(this, 28));
        AlertDialog dialog = new AlertDialog.Builder(this)
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
                    prompt.setText(getString(
                            R.string.shortcuts_already_used, shortcutLabel(other)));
                    return true;
                }
            }
            preferences.setShortcut(action, candidate);
            dialog.dismiss();
            onSaved.run();
            return true;
        });
        dialog.show();
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
        if (readerActive && preferences.keepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private CheckBox checkBox(int label, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setTextColor(Ui.TEXT);
        box.setTextSize(15);
        box.setChecked(checked);
        box.setButtonTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Ui.ACCENT, Ui.TEXT_MUTED}));
        box.setMinHeight(Ui.dp(this, 52));
        return box;
    }

    private void showError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private String shortcutLabel(String action) {
        int label = switch (action) {
            case "previous" -> R.string.shortcut_previous;
            case "next_alt" -> R.string.shortcut_next_alternate;
            case "previous_alt" -> R.string.shortcut_previous_alternate;
            default -> R.string.shortcut_next;
        };
        return getString(label);
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? getString(R.string.error_file_unreadable) : message;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
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

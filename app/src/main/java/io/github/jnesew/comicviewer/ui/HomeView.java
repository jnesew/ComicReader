package io.github.jnesew.comicviewer.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.library.LibraryQueryController;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.model.SeriesGroup;
import io.github.jnesew.comicviewer.util.LibraryGridDensity;
import io.github.jnesew.comicviewer.util.LibraryScanResult;
import io.github.jnesew.comicviewer.util.Ui;

import java.util.Collections;
import java.util.List;

/** Recycled, memory-bounded cover library optimized for narrow Android screens. */
public final class HomeView extends FrameLayout {
    public interface Listener {
        void onOpenRequested();
        void onFavoriteRequested(ReadingProgress item);
        void onRecentRequested(ReadingProgress progress);
        void onForgetRequested(ReadingProgress progress);
        void onSeriesForgetRequested(SeriesGroup series);
        void onComicEditRequested(ReadingProgress progress);
        void onLibraryMenuRequested(View anchor);
    }

    private static final String VIEW_PREFERENCES = "library_view";
    private static final String GRID_DENSITY_PREFERENCE = "grid_density";
    private static final String VIEW_TITLES = "titles";
    private static final String VIEW_SERIES = "series";

    private final LibraryQueryController queries;
    private final Listener listener;
    private final SharedPreferences viewPreferences;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CoverThumbnailLoader covers;
    private final LibraryGridAdapter adapter;

    private final LinearLayout chrome;
    private final GridView grid;
    private final EditText searchInput;
    private final TextView headerBack;
    private final TextView headerTitle;
    private final TextView sortButton;
    private final TextView filterButton;
    private final TextView viewButton;
    private final TextView folderStatus;
    private final Runnable hideFolderStatus;
    private final LinearLayout emptyState;
    private final TextView emptyTitle;
    private final TextView emptyBody;
    private final TextView addButton;

    private List<ReadingProgress> rows = Collections.emptyList();
    private List<SeriesGroup> seriesRows = Collections.emptyList();
    private String sort;
    private String filter;
    private String viewMode;
    private LibraryGridDensity gridDensity;
    private long selectedSeriesId;
    private String selectedSeriesTitle = "";
    private boolean closed;

    public HomeView(Context context, LibraryQueryController queries, Listener listener) {
        super(context);
        this.queries = queries;
        this.listener = listener;
        this.viewPreferences = context.getSharedPreferences(VIEW_PREFERENCES, Context.MODE_PRIVATE);
        this.sort = viewPreferences.getString("sort", LibraryDatabase.SORT_RECENT);
        this.filter = viewPreferences.getString("filter", LibraryDatabase.FILTER_ALL);
        this.viewMode = viewPreferences.getString("mode", VIEW_TITLES);
        this.gridDensity = LibraryGridDensity.fromKey(
                viewPreferences.getString(GRID_DENSITY_PREFERENCE, null));
        setBackgroundColor(Ui.HOME_BACKGROUND);

        covers = new CoverThumbnailLoader();
        adapter = new LibraryGridAdapter(context, listener, this::openSeries, covers, gridDensity);

        chrome = new LinearLayout(context);
        chrome.setOrientation(LinearLayout.VERTICAL);
        addView(chrome, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 6));
        headerBack = Ui.iconButton(context, "‹", context.getString(R.string.library_back_to_series));
        headerBack.setTextSize(34);
        headerBack.setVisibility(GONE);
        headerBack.setOnClickListener(view -> leaveSeries());
        header.addView(headerBack, new LinearLayout.LayoutParams(
                Ui.dp(context, 48), Ui.dp(context, 58)));

        headerTitle = Ui.text(context, context.getString(R.string.library_title), 24, Ui.TEXT);
        headerTitle.setSingleLine(true);
        headerTitle.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(headerTitle, new LinearLayout.LayoutParams(0, Ui.dp(context, 58), 1f));

        TextView search = Ui.iconButton(context, "⌕", context.getString(R.string.library_search));
        search.setTextSize(28);
        search.setOnClickListener(view -> toggleSearch());
        header.addView(search);

        TextView settings = Ui.iconButton(context, "⋮", context.getString(R.string.library_menu));
        settings.setOnClickListener(view -> listener.onLibraryMenuRequested(settings));
        header.addView(settings);
        chrome.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        searchInput = new EditText(context);
        searchInput.setSingleLine(true);
        searchInput.setHint(R.string.library_search_hint);
        searchInput.setTextColor(Ui.TEXT);
        searchInput.setHintTextColor(Ui.TEXT_MUTED);
        searchInput.setTextSize(16);
        searchInput.setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), 0);
        searchInput.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(context, 14), Ui.SURFACE_HIGH, Ui.dp(context, 1)));
        searchInput.setVisibility(GONE);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                refresh();
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 50));
        searchParams.leftMargin = Ui.dp(context, 16);
        searchParams.rightMargin = Ui.dp(context, 16);
        searchParams.bottomMargin = Ui.dp(context, 8);
        chrome.addView(searchInput, searchParams);

        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(Ui.dp(context, 16), Ui.dp(context, 6), Ui.dp(context, 16), Ui.dp(context, 12));
        sortButton = selectorButton(context);
        sortButton.setContentDescription(context.getString(R.string.library_sort_menu));
        sortButton.setOnClickListener(this::showSortMenu);
        controls.addView(sortButton, new LinearLayout.LayoutParams(
                0, Ui.dp(context, 46), 1f));

        filterButton = selectorButton(context);
        filterButton.setContentDescription(context.getString(R.string.library_filter_menu));
        filterButton.setOnClickListener(this::showFilterMenu);
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(
                0, Ui.dp(context, 46), 1f);
        filterParams.leftMargin = Ui.dp(context, 10);
        controls.addView(filterButton, filterParams);

        viewButton = selectorButton(context);
        viewButton.setTextColor(Ui.ACCENT);
        viewButton.setContentDescription(context.getString(R.string.library_view_menu));
        viewButton.setOnClickListener(this::showViewMenu);
        LinearLayout.LayoutParams viewParams = new LinearLayout.LayoutParams(
                Ui.dp(context, 82), Ui.dp(context, 46));
        viewParams.leftMargin = Ui.dp(context, 10);
        controls.addView(viewButton, viewParams);
        chrome.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        folderStatus = Ui.text(context, "", 12, Ui.TEXT_MUTED);
        folderStatus.setSingleLine(true);
        folderStatus.setEllipsize(TextUtils.TruncateAt.END);
        folderStatus.setGravity(Gravity.CENTER_VERTICAL);
        folderStatus.setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), 0);
        folderStatus.setBackground(Ui.rounded(
                Ui.SURFACE, Ui.dp(context, 11), Ui.SURFACE_HIGH, Ui.dp(context, 1)));
        folderStatus.setClickable(true);
        folderStatus.setFocusable(true);
        folderStatus.setVisibility(GONE);
        folderStatus.setOnClickListener(view -> listener.onLibraryMenuRequested(folderStatus));
        hideFolderStatus = () -> {
            if (!closed) folderStatus.setVisibility(GONE);
        };
        LinearLayout.LayoutParams folderParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 36));
        folderParams.leftMargin = Ui.dp(context, 16);
        folderParams.rightMargin = Ui.dp(context, 16);
        folderParams.bottomMargin = Ui.dp(context, 8);
        chrome.addView(folderStatus, folderParams);

        FrameLayout body = new FrameLayout(context);
        chrome.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        grid = new GridView(context);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(Ui.dp(context, gridDensity.minimumCardWidthDp));
        grid.setHorizontalSpacing(Ui.dp(context, 12));
        grid.setVerticalSpacing(Ui.dp(context, gridDensity.verticalSpacingDp));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setClipToPadding(false);
        grid.setPadding(Ui.dp(context, 16), Ui.dp(context, 4), Ui.dp(context, 16), Ui.dp(context, 100));
        grid.setSelector(android.R.color.transparent);
        grid.setAdapter(adapter);
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyState = new LinearLayout(context);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(Ui.dp(context, 28), Ui.dp(context, 36),
                Ui.dp(context, 28), Ui.dp(context, 100));
        emptyTitle = Ui.text(context, context.getString(R.string.library_empty_title), 20, Ui.TEXT);
        Ui.bold(emptyTitle);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyState.addView(emptyTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        emptyBody = Ui.text(context, context.getString(R.string.library_empty_body), 15, Ui.TEXT_MUTED);
        emptyBody.setGravity(Gravity.CENTER);
        emptyBody.setPadding(0, Ui.dp(context, 10), 0, 0);
        emptyState.addView(emptyBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        body.addView(emptyState, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        addButton = Ui.text(context, "＋", 32, Color.rgb(25, 29, 36));
        addButton.setGravity(Gravity.CENTER);
        addButton.setContentDescription(context.getString(R.string.library_add_comics_description));
        addButton.setClickable(true);
        addButton.setFocusable(true);
        addButton.setElevation(Ui.dp(context, 10));
        GradientDrawable addBackground = Ui.rounded(Ui.ACCENT, Ui.dp(context, 34), 0, 0);
        addButton.setBackground(addBackground);
        addButton.setOnClickListener(view -> listener.onOpenRequested());
        FrameLayout.LayoutParams addParams = new FrameLayout.LayoutParams(
                Ui.dp(context, 68), Ui.dp(context, 68), Gravity.END | Gravity.BOTTOM);
        addParams.rightMargin = Ui.dp(context, 20);
        addParams.bottomMargin = Ui.dp(context, 22);
        addView(addButton, addParams);

        setOnApplyWindowInsetsListener((view, insets) -> {
            chrome.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) addButton.getLayoutParams();
            params.bottomMargin = Ui.dp(context, 22) + insets.getSystemWindowInsetBottom();
            addButton.setLayoutParams(params);
            grid.setPadding(Ui.dp(context, 16), Ui.dp(context, 4), Ui.dp(context, 16),
                    Ui.dp(context, 100) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        updateSelectorLabels();
        refresh();
    }

    public void refresh() {
        if (closed) return;
        String query = searchInput == null ? "" : searchInput.getText().toString();
        LibraryQueryController.Snapshot snapshot = queries.query(
                query, sort, filter, selectedSeriesId, VIEW_SERIES.equals(viewMode));
        rows = snapshot.rows();
        seriesRows = snapshot.seriesRows();
        adapter.submit(rows, seriesRows, showingSeriesGrid());
        boolean empty = showingSeriesGrid() ? seriesRows.isEmpty() : rows.isEmpty();
        emptyState.setVisibility(empty ? VISIBLE : GONE);
        grid.setVisibility(empty ? GONE : VISIBLE);
        if (empty && (!query.trim().isEmpty() || !LibraryDatabase.FILTER_ALL.equals(filter))) {
            emptyTitle.setText(R.string.library_no_results);
            emptyBody.setVisibility(GONE);
        } else {
            emptyTitle.setText(R.string.library_empty_title);
            emptyBody.setText(R.string.library_empty_body);
            emptyBody.setVisibility(VISIBLE);
        }
        headerBack.setVisibility(selectedSeriesId > 0L ? VISIBLE : GONE);
        headerTitle.setText(selectedSeriesId > 0L ? selectedSeriesTitle :
                getResources().getString(R.string.library_title));
        updateSelectorLabels();
    }

    public boolean handleBack() {
        if (selectedSeriesId <= 0L) return false;
        leaveSeries();
        return true;
    }

    public void setLibraryFolderState(
            String label, String detail, boolean scanning, boolean persistent) {
        mainHandler.removeCallbacks(hideFolderStatus);
        String folder = label == null ? "" : label.trim();
        if (folder.isEmpty()) {
            folderStatus.setVisibility(GONE);
            return;
        }
        String state = scanning ? getResources().getString(R.string.library_folder_scanning)
                : (detail == null ? "" : detail.trim());
        if (state.isEmpty()) {
            folderStatus.setVisibility(GONE);
            return;
        }
        folderStatus.setText(getResources().getString(
                R.string.library_folder_named_status, folder, state));
        folderStatus.setContentDescription(folderStatus.getText());
        folderStatus.setVisibility(VISIBLE);
        if (!scanning && !persistent) {
            mainHandler.postDelayed(hideFolderStatus, LibraryScanResult.DISPLAY_DURATION_MS);
        }
    }

    public String coverSizeMenuLabel() {
        return getResources().getString(
                R.string.library_cover_size_current, densityLabel(gridDensity));
    }

    public void showCoverSizeDialog() {
        LibraryGridDensity[] values = LibraryGridDensity.values();
        String[] labels = new String[values.length];
        int checked = 0;
        for (int index = 0; index < values.length; index++) {
            labels[index] = getResources().getString(densityLabelResource(values[index]));
            if (values[index] == gridDensity) checked = index;
        }
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.library_cover_size)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    applyGridDensity(values[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public void trimCoverCache() {
        covers.trimMemory();
    }

    public void close() {
        if (closed) return;
        closed = true;
        mainHandler.removeCallbacks(hideFolderStatus);
        covers.close();
    }

    private void applyGridDensity(LibraryGridDensity density) {
        if (density == null || density == gridDensity || closed) return;
        int firstVisible = grid.getFirstVisiblePosition();
        gridDensity = density;
        adapter.setDensity(density);
        viewPreferences.edit().putString(GRID_DENSITY_PREFERENCE, density.key).apply();
        grid.setColumnWidth(Ui.dp(getContext(), density.minimumCardWidthDp));
        grid.setVerticalSpacing(Ui.dp(getContext(), density.verticalSpacingDp));
        grid.setAdapter(null);
        grid.setAdapter(adapter);
        grid.requestLayout();
        if (firstVisible >= 0) grid.post(() -> grid.setSelection(firstVisible));
    }

    private String densityLabel(LibraryGridDensity density) {
        return getResources().getString(densityLabelResource(density));
    }

    private static int densityLabelResource(LibraryGridDensity density) {
        return switch (density) {
            case LARGE -> R.string.library_cover_size_large;
            case COMPACT -> R.string.library_cover_size_compact;
            case DENSE -> R.string.library_cover_size_dense;
            default -> R.string.library_cover_size_standard;
        };
    }

    private void toggleSearch() {
        boolean show = searchInput.getVisibility() != VISIBLE;
        searchInput.setVisibility(show ? VISIBLE : GONE);
        if (show) {
            searchInput.requestFocus();
            InputMethodManager input = (InputMethodManager) getContext().getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            if (input != null) input.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        } else {
            searchInput.setText("");
            searchInput.clearFocus();
            InputMethodManager input = (InputMethodManager) getContext().getSystemService(
                    Context.INPUT_METHOD_SERVICE);
            if (input != null) input.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    private void showSortMenu(View anchor) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        addSortItem(menu, R.string.sort_recently_read, LibraryDatabase.SORT_RECENT);
        addSortItem(menu, R.string.sort_recently_added, LibraryDatabase.SORT_ADDED);
        addSortItem(menu, R.string.sort_title_ascending, LibraryDatabase.SORT_TITLE_ASC);
        addSortItem(menu, R.string.sort_title_descending, LibraryDatabase.SORT_TITLE_DESC);
        addSortItem(menu, R.string.sort_progress, LibraryDatabase.SORT_PROGRESS);
        menu.show();
    }

    private void addSortItem(PopupMenu menu, int label, String value) {
        menu.getMenu().add(label).setCheckable(true).setChecked(value.equals(sort))
                .setOnMenuItemClickListener(item -> {
            sort = value;
            viewPreferences.edit().putString("sort", sort).apply();
            updateSelectorLabels();
            refresh();
            return true;
        });
    }

    private void showFilterMenu(View anchor) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        addFilterItem(menu, R.string.filter_all, LibraryDatabase.FILTER_ALL);
        addFilterItem(menu, R.string.filter_new, LibraryDatabase.FILTER_NEW);
        addFilterItem(menu, R.string.filter_in_progress, LibraryDatabase.FILTER_READING);
        addFilterItem(menu, R.string.filter_completed, LibraryDatabase.FILTER_COMPLETED);
        addFilterItem(menu, R.string.filter_favorites, LibraryDatabase.FILTER_FAVORITES);
        menu.show();
    }

    private void showViewMenu(View anchor) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        addViewItem(menu, R.string.library_view_titles, VIEW_TITLES);
        addViewItem(menu, R.string.library_view_series, VIEW_SERIES);
        menu.show();
    }

    private void addViewItem(PopupMenu menu, int label, String value) {
        menu.getMenu().add(label).setCheckable(true).setChecked(value.equals(viewMode))
                .setOnMenuItemClickListener(item -> {
                    viewMode = value;
                    selectedSeriesId = 0L;
                    selectedSeriesTitle = "";
                    viewPreferences.edit().putString("mode", viewMode).apply();
                    updateSelectorLabels();
                    refresh();
                    return true;
                });
    }

    private void addFilterItem(PopupMenu menu, int label, String value) {
        menu.getMenu().add(label).setCheckable(true).setChecked(value.equals(filter))
                .setOnMenuItemClickListener(item -> {
            filter = value;
            viewPreferences.edit().putString("filter", filter).apply();
            updateSelectorLabels();
            refresh();
            return true;
        });
    }

    private void updateSelectorLabels() {
        int sortLabel = switch (sort) {
            case LibraryDatabase.SORT_ADDED -> R.string.sort_recently_added;
            case LibraryDatabase.SORT_TITLE_ASC -> R.string.sort_title_ascending;
            case LibraryDatabase.SORT_TITLE_DESC -> R.string.sort_title_descending;
            case LibraryDatabase.SORT_PROGRESS -> R.string.sort_progress;
            default -> R.string.sort_recently_read;
        };
        int filterLabel = switch (filter) {
            case LibraryDatabase.FILTER_NEW -> R.string.filter_new;
            case LibraryDatabase.FILTER_READING -> R.string.filter_in_progress;
            case LibraryDatabase.FILTER_COMPLETED -> R.string.filter_completed;
            case LibraryDatabase.FILTER_FAVORITES -> R.string.filter_favorites;
            default -> R.string.filter_all;
        };
        sortButton.setText(getResources().getString(sortLabel) + " ⌄");
        filterButton.setText(filterLabel);
        viewButton.setText(VIEW_SERIES.equals(viewMode)
                ? R.string.library_view_series : R.string.library_view_titles);
    }

    private boolean showingSeriesGrid() {
        return selectedSeriesId <= 0L && VIEW_SERIES.equals(viewMode);
    }

    private void openSeries(SeriesGroup group) {
        if (group.isStandalone()) {
            listener.onRecentRequested(group.issues.get(0));
            return;
        }
        selectedSeriesId = group.id;
        selectedSeriesTitle = group.title;
        searchInput.setText("");
        refresh();
        grid.setSelection(0);
    }

    private void leaveSeries() {
        selectedSeriesId = 0L;
        selectedSeriesTitle = "";
        searchInput.setText("");
        refresh();
        grid.setSelection(0);
    }

    private TextView selectorButton(Context context) {
        TextView button = Ui.text(context, "", 14, Ui.TEXT);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackground(Ui.rounded(
                Color.TRANSPARENT, Ui.dp(context, 12), Ui.TEXT_MUTED, Ui.dp(context, 1)));
        return button;
    }

}

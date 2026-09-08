package io.github.jnesew.comicviewer.ui;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.model.SeriesGroup;
import io.github.jnesew.comicviewer.util.LibraryGridDensity;
import io.github.jnesew.comicviewer.util.Ui;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Recycled comic/series cards. All mutations are commands to the screen owner. */
final class LibraryGridAdapter extends BaseAdapter {
    private final Context context;
    private final HomeView.Listener listener;
    private final Consumer<SeriesGroup> onSeries;
    private final CoverThumbnailLoader covers;
    private LibraryGridDensity gridDensity;
    private List<ReadingProgress> rows = Collections.emptyList();
    private List<SeriesGroup> seriesRows = Collections.emptyList();
    private boolean seriesGrid;

    LibraryGridAdapter(Context context, HomeView.Listener listener, Consumer<SeriesGroup> onSeries,
            CoverThumbnailLoader covers, LibraryGridDensity density) {
        this.context = context;
        this.listener = listener;
        this.onSeries = onSeries;
        this.covers = covers;
        this.gridDensity = density;
    }
    void submit(List<ReadingProgress> rows, List<SeriesGroup> seriesRows, boolean seriesGrid) {
        this.rows = rows;
        this.seriesRows = seriesRows;
        this.seriesGrid = seriesGrid;
        notifyDataSetChanged();
    }
    void setDensity(LibraryGridDensity density) { gridDensity = density; }

    @Override
    public int getCount() {
        return seriesGrid ? seriesRows.size() : rows.size();
    }

    @Override
    public Object getItem(int position) {
        return seriesGrid ? seriesRows.get(position) : rows.get(position);
    }

    @Override
    public long getItemId(int position) {
        Object item = getItem(position);
        return item instanceof SeriesGroup group
                ? group.key.hashCode() : ((ReadingProgress) item).uri.hashCode();
    }

    @Override
    public boolean hasStableIds() {
        return true;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        CardHolder holder;
        if (convertView == null) {
            holder = createCard();
            convertView = holder.root;
            convertView.setTag(holder);
        } else {
            holder = (CardHolder) convertView.getTag();
        }
        if (seriesGrid) {
            bindSeriesCard(holder, seriesRows.get(position));
            return convertView;
        }
        ReadingProgress item = rows.get(position);
        holder.root.setAlpha(item.available ? 1f : 0.62f);
        holder.root.setContentDescription(context.getResources().getString(
                R.string.comic_accessibility_progress, item.title, item.percent()));
        holder.root.setOnClickListener(view -> listener.onRecentRequested(item));
        holder.root.setOnLongClickListener(view -> {
            listener.onForgetRequested(item);
            return true;
        });
        holder.options.setContentDescription(context.getResources().getString(R.string.comic_options));
        holder.options.setVisibility(View.VISIBLE);
        holder.options.setOnClickListener(view -> showComicMenu(view, item));
        holder.favorite.setVisibility(View.VISIBLE);
        updateFavoriteButton(holder.favorite, item);
        holder.favorite.setOnClickListener(view -> toggleFavorite(item));
        holder.cover.setContentDescription(context.getResources().getString(
                R.string.cover_description, item.title));
        covers.bindCover(holder.cover, item);
        holder.title.setText(item.title);
        holder.progress.setProgress(item.percent());
        bindTitleDetails(holder.details, item);
        return convertView;
    }

    private void bindSeriesCard(CardHolder holder, SeriesGroup group) {
        ReadingProgress standalone = group.isStandalone() ? group.issues.get(0) : null;
        holder.root.setAlpha(group.isUnavailable() ? 0.62f : 1f);
        if (standalone != null) {
            holder.root.setContentDescription(context.getResources().getString(
                    R.string.comic_accessibility_progress,
                    standalone.title, standalone.percent()));
        } else {
            holder.root.setContentDescription(context.getResources().getQuantityString(
                    R.plurals.series_accessibility_issues, group.issues.size(),
                    group.title, group.issues.size()));
        }
        holder.root.setOnClickListener(view -> onSeries.accept(group));
        holder.root.setOnLongClickListener(view -> {
            if (standalone != null) listener.onForgetRequested(standalone);
            else listener.onSeriesForgetRequested(group);
            return true;
        });
        holder.options.setContentDescription(context.getResources().getString(
                standalone == null ? R.string.series_options : R.string.comic_options));
        holder.options.setVisibility(View.VISIBLE);
        holder.options.setOnClickListener(view -> {
            if (standalone != null) showComicMenu(view, standalone);
            else showSeriesMenu(view, group);
        });
        holder.favorite.setVisibility(View.GONE);
        holder.favorite.setOnClickListener(null);
        holder.cover.setContentDescription(context.getResources().getString(
                R.string.series_cover_description, group.title));
        covers.bindCover(holder.cover, group.cover);
        holder.title.setText(group.title);
        holder.progress.setProgress(group.percent);
        if (standalone != null) {
            bindTitleDetails(holder.details, standalone);
        } else if (group.isUnavailable()) {
            holder.details.setText(R.string.comic_status_unavailable);
        } else if (group.unavailableIssueCount() > 0) {
            String total = context.getResources().getQuantityString(
                    R.plurals.series_issue_count, group.issues.size(), group.issues.size());
            String unavailable = context.getResources().getQuantityString(
                    R.plurals.series_unavailable_issue_count, group.unavailableIssueCount(),
                    group.unavailableIssueCount());
            holder.details.setText(total + " · " + unavailable);
        } else {
            holder.details.setText(context.getResources().getQuantityString(
                    R.plurals.series_issue_count, group.issues.size(), group.issues.size()));
        }
    }

    private void bindTitleDetails(TextView details, ReadingProgress item) {
        if (!item.available) {
            details.setText(R.string.comic_status_unavailable);
        } else if (item.isNew()) {
            details.setText(R.string.comic_status_new);
        } else if (item.isCompleted()) {
            details.setText(R.string.comic_status_completed);
        } else if (item.pageCount > 0) {
            details.setText(context.getResources().getString(
                    R.string.comic_page_progress,
                    Math.min(item.page + 1, item.pageCount), item.pageCount));
        } else {
            details.setText(R.string.comic_status_indexing);
        }
    }

    private void showComicMenu(View anchor, ReadingProgress item) {
        PopupMenu menu = new PopupMenu(context, anchor);
        menu.getMenu().add(item.favorite ? R.string.title_remove_favorite : R.string.title_add_favorite)
                .setOnMenuItemClickListener(selected -> {
                    toggleFavorite(item);
                    return true;
                });
        menu.getMenu().add(R.string.comic_edit).setOnMenuItemClickListener(selected -> {
            listener.onComicEditRequested(item);
            return true;
        });
        menu.getMenu().add(R.string.forget).setOnMenuItemClickListener(selected -> {
            listener.onForgetRequested(item);
            return true;
        });
        menu.show();
    }

    private void showSeriesMenu(View anchor, SeriesGroup group) {
        PopupMenu menu = new PopupMenu(context, anchor);
        menu.getMenu().add(R.string.forget_series).setOnMenuItemClickListener(selected -> {
            listener.onSeriesForgetRequested(group);
            return true;
        });
        menu.show();
    }

    private void toggleFavorite(ReadingProgress item) {
        listener.onFavoriteRequested(item);
    }

    private void updateFavoriteButton(TextView button, ReadingProgress item) {
        button.setText(item.favorite ? "★" : "☆");
        button.setTextColor(item.favorite ? Ui.ACCENT : Ui.TEXT);
        button.setContentDescription(context.getResources().getString(
                item.favorite ? R.string.title_remove_favorite_named : R.string.title_add_favorite_named,
                item.title));
    }

    private CardHolder createCard() {
        LibraryGridDensity density = gridDensity;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClickable(true);
        root.setFocusable(true);
        root.setPadding(0, 0, 0, Ui.dp(context, 2));

        FrameLayout coverFrame = new FrameLayout(context);
        ImageView cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setClipToOutline(true);
        cover.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(context, 12), Ui.SURFACE_HIGH,
                Ui.dp(context, 1)));
        coverFrame.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView favorite = Ui.text(context, "☆", density.favoriteTextSp, Ui.TEXT);
        favorite.setGravity(Gravity.CENTER);
        favorite.setClickable(true);
        favorite.setFocusable(true);
        favorite.setElevation(Ui.dp(context, 5));
        favorite.setBackground(Ui.rounded(
                Color.argb(218, 13, 16, 21), Ui.dp(context, 22), Ui.TEXT_MUTED, Ui.dp(context, 1)));
        FrameLayout.LayoutParams favoriteParams = new FrameLayout.LayoutParams(
                Ui.dp(context, 44), Ui.dp(context, 44), Gravity.TOP | Gravity.END);
        favoriteParams.topMargin = Ui.dp(context, 7);
        favoriteParams.rightMargin = Ui.dp(context, 7);
        coverFrame.addView(favorite, favoriteParams);
        root.addView(coverFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, density.coverHeightDp)));

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setGravity(Gravity.TOP);
        titleRow.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 4));
        titleRow.setMinimumHeight(Ui.dp(context, density.titleRowHeightDp));
        TextView title = Ui.text(context, "", density.titleTextSp, Ui.TEXT);
        Ui.bold(title);
        title.setIncludeFontPadding(true);
        title.setLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.TOP);
        Paint.FontMetricsInt titleMetrics = title.getPaint().getFontMetricsInt();
        int titleLineBox = Math.max(
                title.getLineHeight(), titleMetrics.bottom - titleMetrics.top);
        int titleHeight = titleLineBox * 2 + Ui.dp(context, 3);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, titleHeight, 1f));
        TextView options = Ui.text(
                context, "⋮", density == LibraryGridDensity.DENSE ? 18 : 20, Ui.TEXT_MUTED);
        options.setGravity(Gravity.TOP | Gravity.END);
        options.setClickable(true);
        options.setFocusable(true);
        titleRow.addView(options, new LinearLayout.LayoutParams(
                Ui.dp(context, 30), ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar progress = new ProgressBar(
                context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.SURFACE_HIGH));
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 4)));

        TextView details = Ui.text(context, "", density.detailTextSp, Ui.TEXT_MUTED);
        details.setSingleLine(true);
        details.setEllipsize(TextUtils.TruncateAt.END);
        details.setPadding(0, Ui.dp(context, 7), 0, 0);
        root.addView(details, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, density.detailHeightDp)));
        return new CardHolder(root, cover, favorite, title, options, progress, details);
    }

    private static final class CardHolder {
        private final LinearLayout root;
        private final ImageView cover;
        private final TextView favorite;
        private final TextView title;
        private final TextView options;
        private final ProgressBar progress;
        private final TextView details;

        private CardHolder(
                LinearLayout root,
                ImageView cover,
                TextView favorite,
                TextView title,
                TextView options,
                ProgressBar progress,
                TextView details) {
            this.root = root;
            this.cover = cover;
            this.favorite = favorite;
            this.title = title;
            this.options = options;
            this.progress = progress;
            this.details = details;
        }
    }
}

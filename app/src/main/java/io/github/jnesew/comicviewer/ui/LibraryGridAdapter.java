package io.github.jnesew.comicviewer.ui;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ImageButton;
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
        holder.cover.setContentDescription(context.getResources().getString(
                R.string.cover_description, item.title));
        covers.bindCover(holder.cover, item);
        bindTitle(holder.title, item.title, item.favorite);
        bindIssue(holder.issue, item);
        bindProgress(holder.progress, item.percent(), item.isCompleted(), !item.isNew() && item.page > 0);
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
            String seriesDescription = context.getResources().getQuantityString(
                    R.plurals.series_accessibility_issues, group.issues.size(),
                    group.title, group.issues.size());
            String readingStatus = group.isCaughtUp()
                    ? context.getString(R.string.series_caught_up)
                    : context.getResources().getQuantityString(R.plurals.series_unread_issue_count,
                            group.unreadIssueCount(), group.unreadIssueCount());
            holder.root.setContentDescription(seriesDescription + ", " + readingStatus);
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
        holder.cover.setContentDescription(context.getResources().getString(
                R.string.series_cover_description, group.title));
        covers.bindCover(holder.cover, group.cover);
        bindTitle(holder.title, group.title, group.favorite);
        if (standalone != null) bindIssue(holder.issue, standalone);
        else holder.issue.setVisibility(View.GONE);
        bindProgress(holder.progress, group.percent, group.isCaughtUp(), group.percent > 0);
        if (standalone != null) {
            bindTitleDetails(holder.details, standalone);
        } else {
            String total = context.getResources().getQuantityString(
                    R.plurals.series_issue_count, group.issues.size(), group.issues.size());
            String status = group.isCaughtUp()
                    ? context.getString(R.string.series_caught_up)
                    : context.getResources().getQuantityString(R.plurals.series_unread_issue_count,
                            group.unreadIssueCount(), group.unreadIssueCount());
            if (group.unavailableIssueCount() > 0) {
                String unavailable = context.getResources().getQuantityString(
                        R.plurals.series_unavailable_issue_count, group.unavailableIssueCount(),
                        group.unavailableIssueCount());
                status += " · " + unavailable;
            }
            holder.details.setText(total + " · " + status);
        }
    }

    private void bindTitleDetails(TextView details, ReadingProgress item) {
        if (!item.available) {
            details.setText(R.string.comic_status_unavailable);
        } else if (item.isCompleted()) {
            details.setText(R.string.comic_status_completed);
        } else if (item.isNew()) {
            details.setText(R.string.comic_status_new);
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
        menu.getMenu().add(item.isCompleted() ? R.string.comic_mark_unread : R.string.comic_mark_read)
                .setOnMenuItemClickListener(selected -> {
                    listener.onComicReadStatusRequested(item, !item.isCompleted());
                    return true;
                });
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
        menu.getMenu().add(group.favorite ? R.string.series_remove_favorite : R.string.series_add_favorite)
                .setOnMenuItemClickListener(selected -> {
                    listener.onSeriesFavoriteRequested(group);
                    return true;
                });
        menu.getMenu().add(R.string.series_mark_read).setOnMenuItemClickListener(selected -> {
            listener.onSeriesReadStatusRequested(group, true);
            return true;
        });
        menu.getMenu().add(R.string.series_mark_unread).setOnMenuItemClickListener(selected -> {
            listener.onSeriesReadStatusRequested(group, false);
            return true;
        });
        menu.getMenu().add(R.string.forget_series).setOnMenuItemClickListener(selected -> {
            listener.onSeriesForgetRequested(group);
            return true;
        });
        menu.show();
    }

    private void toggleFavorite(ReadingProgress item) {
        listener.onFavoriteRequested(item);
    }

    private void bindTitle(TextView title, String value, boolean favorite) {
        if (favorite) {
            android.graphics.drawable.Drawable marker = Ui.icon(context, R.drawable.ic_star_filled, 14, Ui.ACCENT);
            // A marker taller than the text ascent makes favorite cards taller in Dense view.
            int size = Math.min(Ui.dp(context, 14), -title.getPaint().getFontMetricsInt().ascent);
            marker.setBounds(0, 0, size, size);
            android.text.SpannableString marked = new android.text.SpannableString("\uFFFC " + value);
            marked.setSpan(new android.text.style.ImageSpan(
                    marker,
                    android.text.style.ImageSpan.ALIGN_BASELINE), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            title.setText(marked);
        } else title.setText(value);
        title.setContentDescription(favorite ? context.getString(R.string.favorite_title, value) : value);
    }

    private void bindIssue(TextView subtitle, ReadingProgress item) {
        boolean structured = !item.seriesNumber.trim().isEmpty();
        subtitle.setVisibility(structured ? View.VISIBLE : View.GONE);
        if (structured) subtitle.setText(context.getString(R.string.comic_issue_number, item.seriesNumber));
    }

    private void bindProgress(ProgressBar progress, int percent, boolean complete, boolean started) {
        // GridView fills rows from either edge while scrolling. Keep the card height stable
        // when the track is hidden so covers cannot shift relative to neighboring cards.
        progress.setVisibility(complete || (started && percent > 0) ? View.VISIBLE : View.INVISIBLE);
        progress.setProgress(complete ? 100 : percent);
    }

    private CardHolder createCard() {
        LibraryGridDensity density = gridDensity;
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClickable(true);
        root.setFocusable(true);
        root.setPadding(0, 0, 0, Ui.dp(context, 2));

        ImageView cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setClipToOutline(true);
        cover.setBackground(Ui.rounded(Ui.SURFACE, Ui.dp(context, 12), Ui.SURFACE_HIGH, Ui.dp(context, 1)));
        root.addView(cover, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, density.coverHeightDp)));

        TextView title = Ui.text(context, "", density.titleTextSp, Ui.TEXT);
        Ui.bold(title);
        title.setIncludeFontPadding(true);
        title.setLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.TOP);
        title.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 3));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView issue = Ui.text(context, "", density.detailTextSp, Ui.TEXT_MUTED);
        issue.setSingleLine(true);
        issue.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(issue, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(Ui.ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.SURFACE_HIGH));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 4));
        progressParams.topMargin = Ui.dp(context, 5);
        root.addView(progress, progressParams);

        LinearLayout statusRow = new LinearLayout(context);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView details = Ui.text(context, "", density.detailTextSp, Ui.TEXT_MUTED);
        details.setLines(3);
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setEllipsize(TextUtils.TruncateAt.END);
        statusRow.addView(details, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton options = Ui.iconButton(context, R.drawable.ic_more, context.getString(R.string.comic_options));
        options.setImageTintList(ColorStateList.valueOf(Ui.TEXT_MUTED));
        options.setPadding(Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12));
        statusRow.addView(options, new LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)));
        root.addView(statusRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new CardHolder(root, cover, title, issue, options, progress, details);
    }

    private static final class CardHolder {
        final LinearLayout root;
        final ImageView cover;
        final TextView title;
        final TextView issue;
        final ImageButton options;
        final ProgressBar progress;
        final TextView details;
        CardHolder(LinearLayout root, ImageView cover, TextView title, TextView issue,
                ImageButton options, ProgressBar progress, TextView details) {
            this.root = root;
            this.cover = cover;
            this.title = title;
            this.issue = issue;
            this.options = options;
            this.progress = progress;
            this.details = details;
        }
    }
}

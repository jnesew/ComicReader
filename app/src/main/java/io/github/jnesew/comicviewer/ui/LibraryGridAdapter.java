package io.github.jnesew.comicviewer.ui;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
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
        holder.root.setOnClickListener(view -> listener.onRecentRequested(item));
        holder.root.setOnLongClickListener(view -> {
            showComicMenu(holder.options, item);
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
        bindTitleDetails(holder, item);
        return convertView;
    }

    private void bindSeriesCard(CardHolder holder, SeriesGroup group) {
        ReadingProgress standalone = group.isStandalone() ? group.issues.get(0) : null;
        holder.root.setAlpha(group.isUnavailable() ? 0.62f : 1f);
        if (standalone == null) {
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
            if (standalone != null) showComicMenu(holder.options, standalone);
            else showSeriesMenu(holder.options, group);
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
        if (standalone != null) {
            bindTitleDetails(holder, standalone);
        } else {
            holder.progress.setVisibility(View.GONE);
            holder.details.setVisibility(View.VISIBLE);
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

    private void bindTitleDetails(CardHolder holder, ReadingProgress item) {
        TextView details = holder.details;
        boolean inProgress = item.available && !item.isCompleted() && !item.isNew() && item.pageCount > 0;
        // The label continues to reserve the same two-line area while a bar replaces its first line.
        // Neither reading state nor a missing cover badge can change the height of a recycled card.
        details.setVisibility(inProgress ? View.INVISIBLE : View.VISIBLE);
        holder.progress.setVisibility(inProgress ? View.VISIBLE : View.GONE);
        String status;
        if (inProgress) {
            int percent = Math.min(99, item.positionPercent());
            holder.progress.setProgress(percent);
            details.setText("");
            status = context.getString(R.string.comic_in_progress_percent, percent) + ", "
                    + context.getString(R.string.comic_page_progress,
                            Math.max(0, Math.min(item.page, item.pageCount - 1)) + 1, item.pageCount);
            holder.progress.setContentDescription(status);
        } else {
            if (!item.available) {
                details.setText(R.string.comic_status_unavailable);
            } else if (item.isCompleted()) {
                details.setText(R.string.comic_status_completed);
            } else if (item.isNew()) {
                details.setText(R.string.comic_status_new);
            } else {
                details.setText(R.string.comic_status_indexing);
            }
            status = details.getText().toString();
        }
        String title = item.title;
        if (hasIssueNumber(item)) title += ", " + context.getString(R.string.comic_issue_number, item.seriesNumber);
        holder.root.setContentDescription(title + ", " + status);
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
            android.graphics.drawable.Drawable marker = Ui.icon(context, R.drawable.ic_star_filled, 14, Ui.accent(context));
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
        boolean structured = hasIssueNumber(item);
        subtitle.setVisibility(structured ? View.VISIBLE : View.GONE);
        subtitle.setText(structured ? item.seriesNumber.trim() : "");
        subtitle.setContentDescription(structured ? context.getString(R.string.comic_issue_number, item.seriesNumber) : null);
    }

    private static boolean hasIssueNumber(ReadingProgress item) {
        return !item.seriesNumber.trim().isEmpty();
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
        cover.setBackground(Ui.rounded(Ui.surface(context), Ui.dp(context, 12), Ui.surfaceHigh(context), Ui.dp(context, 1)));
        FrameLayout coverFrame = new FrameLayout(context);
        coverFrame.addView(cover, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ImageButton options = Ui.iconButton(context, R.drawable.ic_more, context.getString(R.string.comic_options));
        options.setImageTintList(ColorStateList.valueOf(android.graphics.Color.WHITE));
        options.setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14));
        options.setBackground(new android.graphics.drawable.InsetDrawable(
                Ui.rounded(0xB30B0D10, Ui.dp(context, 16), 0, 0), Ui.dp(context, 8)));
        FrameLayout.LayoutParams optionsParams = new FrameLayout.LayoutParams(
                Ui.dp(context, 48), Ui.dp(context, 48), Gravity.TOP | Gravity.END);
        coverFrame.addView(options, optionsParams);
        TextView issue = Ui.text(context, "", Math.max(11, density.detailTextSp - 1), android.graphics.Color.WHITE);
        issue.setSingleLine(true);
        issue.setEllipsize(TextUtils.TruncateAt.END);
        issue.setIncludeFontPadding(false);
        issue.setPadding(Ui.dp(context, 6), Ui.dp(context, 3), Ui.dp(context, 6), Ui.dp(context, 3));
        issue.setBackground(Ui.rounded(0xB30B0D10, Ui.dp(context, 5), 0, 0));
        issue.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams issueParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        issueParams.setMargins(Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6), Ui.dp(context, 6));
        coverFrame.addView(issue, issueParams);
        root.addView(coverFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, density.coverHeightDp)));

        TextView title = Ui.text(context, "", density.titleTextSp, Ui.text(context));
        Ui.bold(title);
        title.setIncludeFontPadding(true);
        title.setLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.TOP);
        title.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 3));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView details = Ui.text(context, "", density.detailTextSp, Ui.textMuted(context));
        // Full card width normally fits one line; retain room for Finnish, large fonts and dense grids.
        // Equal line counts keep neighboring cover rows aligned when GridView fills in either direction.
        details.setLines(2);
        details.setGravity(Gravity.TOP);
        details.setPadding(0, Ui.dp(context, 6), 0, 0);
        details.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout status = new FrameLayout(context);
        status.addView(details, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(Ui.accent(context)));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.surfaceHigh(context)));
        progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int barHeight = Ui.dp(context, 4);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight);
        android.graphics.Paint.FontMetricsInt metrics = details.getPaint().getFontMetricsInt();
        progressParams.topMargin = details.getPaddingTop() + Math.max(0, (metrics.bottom - metrics.top - barHeight) / 2);
        status.addView(progress, progressParams);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new CardHolder(root, cover, title, issue, options, details, progress);
    }

    private static final class CardHolder {
        final LinearLayout root;
        final ImageView cover;
        final TextView title;
        final TextView issue;
        final ImageButton options;
        final TextView details;
        final ProgressBar progress;
        CardHolder(LinearLayout root, ImageView cover, TextView title, TextView issue,
                ImageButton options, TextView details, ProgressBar progress) {
            this.root = root;
            this.cover = cover;
            this.title = title;
            this.issue = issue;
            this.options = options;
            this.details = details;
            this.progress = progress;
        }
    }
}

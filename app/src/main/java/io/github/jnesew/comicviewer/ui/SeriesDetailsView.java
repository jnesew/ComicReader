package io.github.jnesew.comicviewer.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.library.LibraryQueryController;
import io.github.jnesew.comicviewer.model.ComicMetadata;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.LibraryGridDensity;
import io.github.jnesew.comicviewer.util.LibraryTitleDisplay;
import io.github.jnesew.comicviewer.util.Ui;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native recycled issue rows with one scrolling overview and pinned issue controls. */
final class SeriesDetailsView extends FrameLayout {
    private final CoverThumbnailLoader covers;
    private final LibraryGridAdapter cards;
    private final IssueRows rows;
    private final ListView list;
    private final LinearLayout overview;
    private final LinearLayout header;
    private final LinearLayout controls;
    private final View controlsSpace;
    private final ImageView cover;
    private final TextView title;
    private final TextView credit;
    private final TextView count;
    private final TextView issue;
    private final LinearLayout details;
    private final TextView continueButton;
    private final TextView filter;
    private LibraryGridDensity density;
    private ReadingProgress target;
    private String targetUri = "";
    private boolean expanded;
    private boolean hasDetails;
    private ScrollPosition pendingScroll;
    private record ScrollPosition(boolean header, int issue, int offset) { }

    SeriesDetailsView(Context context, HomeView.Listener listener, CoverThumbnailLoader covers,
            LibraryGridDensity density, LibraryTitleDisplay titleDisplay, OnClickListener onFilter) {
        super(context);
        this.covers = covers;
        this.density = density;
        setBackgroundColor(Ui.background(context));
        cards = new LibraryGridAdapter(context, listener, group -> { }, covers, density, titleDisplay);
        rows = new IssueRows();
        list = new ListView(context);
        list.setId(View.generateViewId());
        list.setDivider(null); list.setDividerHeight(0);
        list.setItemsCanFocus(true);
        list.setSelector(android.R.color.transparent);
        list.setClipToPadding(false);
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        overview = new LinearLayout(context);
        overview.setOrientation(LinearLayout.VERTICAL);
        overview.setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 16));
        LinearLayout identity = new LinearLayout(context);
        cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setClipToOutline(true);
        identity.addView(cover, new LinearLayout.LayoutParams(Ui.dp(context, 76), Ui.dp(context, 112)));
        LinearLayout names = new LinearLayout(context); names.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        nameParams.setMarginStart(Ui.dp(context, 14)); identity.addView(names, nameParams);
        title = Ui.text(context, "", 20, Ui.text(context)); Ui.bold(title);
        title.setMaxLines(3); title.setEllipsize(TextUtils.TruncateAt.END); names.addView(title);
        credit = Ui.text(context, "", 12, Ui.textMuted(context));
        credit.setMaxLines(2); credit.setEllipsize(TextUtils.TruncateAt.END); names.addView(credit);
        count = Ui.text(context, "", 13, Ui.textMuted(context));
        count.setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8)); names.addView(count);
        overview.addView(identity);

        issue = Ui.text(context, "", 15, Ui.text(context)); Ui.bold(issue);
        issue.setMaxLines(2); issue.setEllipsize(TextUtils.TruncateAt.END);
        issue.setGravity(Gravity.CENTER_VERTICAL);
        issue.setMinimumHeight(Ui.dp(context, 48));
        issue.setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10));
        LinearLayout.LayoutParams issueParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        issueParams.topMargin = Ui.dp(context, 12); overview.addView(issue, issueParams);
        issue.setOnClickListener(view -> { expanded = !expanded; updateExpansion(); });
        details = new LinearLayout(context); details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8));
        overview.addView(details);
        continueButton = Ui.text(context, context.getString(R.string.series_continue), 16, Ui.background(context));
        Ui.bold(continueButton); continueButton.setGravity(Gravity.CENTER);
        continueButton.setMinimumHeight(Ui.dp(context, 48));
        continueButton.setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 12));
        continueButton.setBackground(Ui.rounded(Ui.accent(context), Ui.dp(context, 12), 0, 0));
        continueButton.setFocusable(true); continueButton.setClickable(true);
        continueButton.setOnClickListener(view -> { if (target != null && listener != null) listener.onRecentRequested(target); });
        LinearLayout.LayoutParams continueParams = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        continueParams.topMargin = Ui.dp(context, 12); overview.addView(continueButton, continueParams);

        controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(Ui.dp(context, 16), 0, Ui.dp(context, 16), 0);
        controls.setBackgroundColor(Ui.background(context));
        TextView issues = Ui.text(context, context.getString(R.string.series_issues), 15, Ui.text(context)); Ui.bold(issues);
        controls.addView(issues, new LinearLayout.LayoutParams(0, Ui.dp(context, 48), 1f));
        issues.setGravity(Gravity.CENTER_VERTICAL);
        filter = Ui.text(context, "", 14, Ui.text(context));
        filter.setGravity(Gravity.CENTER); filter.setMinimumHeight(Ui.dp(context, 48));
        filter.setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10));
        filter.setCompoundDrawablesRelative(null, null, Ui.icon(context, R.drawable.ic_chevron_down, 16, Ui.textMuted(context)), null);
        filter.setCompoundDrawablePadding(Ui.dp(context, 8));
        filter.setFocusable(true); filter.setClickable(true); filter.setOnClickListener(onFilter);
        filter.setContentDescription(context.getString(R.string.library_filter_menu));
        controls.addView(filter);
        header = new LinearLayout(context); header.setOrientation(LinearLayout.VERTICAL);
        header.addView(overview);
        controlsSpace = new View(context);
        header.addView(controlsSpace, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context, 48)));
        list.addHeaderView(header, null, true);
        list.setAdapter(rows);
        addView(controls, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        controls.setAccessibilityTraversalBefore(list.getId());
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) { }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) { positionControls(); }
        });
    }

    void bind(LibraryQueryController.SeriesOverview data, List<ReadingProgress> visible, int filterLabel) {
        target = data.target();
        String next = target == null ? "" : target.uri;
        if (!next.equals(targetUri)) expanded = false;
        targetUri = next;
        title.setText(data.group().title);
        cover.setContentDescription(getContext().getString(R.string.cover_description, data.group().title));
        covers.bindCover(cover, data.group().cover);
        String total = getResources().getQuantityString(R.plurals.series_issue_count,
                data.group().issues.size(), data.group().issues.size());
        String unread = data.group().isCaughtUp() ? getContext().getString(R.string.series_caught_up) :
                getResources().getQuantityString(R.plurals.series_unread_issue_count,
                        data.group().unreadIssueCount(), data.group().unreadIssueCount());
        count.setText(getContext().getString(R.string.series_counts, total, unread));
        if (data.group().unavailableIssueCount() > 0) {
            unread = getContext().getString(R.string.series_counts, unread,
                    getResources().getQuantityString(R.plurals.series_unavailable_issue_count,
                            data.group().unavailableIssueCount(), data.group().unavailableIssueCount()));
            count.setText(getContext().getString(R.string.series_counts, total, unread));
        }
        ComicMetadata metadata = data.metadata();
        String issueLabel = target == null ? "" : PublicationDetails.issueLabel(getContext(), target, metadata);
        String credits = metadata.creators.isEmpty() ? "" : PublicationDetails.creditText(getContext(), metadata.creators.entrySet().iterator().next());
        credit.setText(target == null || credits.isEmpty() ? "" : getContext().getString(R.string.series_counts,
                target.seriesNumber.isEmpty() ? target.title : getContext().getString(R.string.comic_issue_number, target.seriesNumber), credits));
        credit.setVisibility(credits.isEmpty() ? GONE : VISIBLE);
        issue.setText(issueLabel);
        issue.setVisibility(target == null ? GONE : VISIBLE);
        continueButton.setVisibility(target == null ? GONE : VISIBLE);
        continueButton.setContentDescription(getContext().getString(R.string.series_continue_issue, issueLabel));
        details.removeAllViews();
        PublicationDetails.addFields(details, metadata, false);
        hasDetails = target != null && details.getChildCount() > 0;
        updateExpansion();
        filter.setText(filterLabel);
        cards.submit(visible, Collections.emptyList(), false);
        rows.notifyDataSetChanged();
    }

    private void updateExpansion() {
        details.setVisibility(expanded && hasDetails ? VISIBLE : GONE);
        issue.setClickable(hasDetails); issue.setFocusable(hasDetails);
        issue.setBackground(hasDetails ? Ui.rounded(Ui.surface(getContext()), Ui.dp(getContext(), 10),
                Ui.surfaceHigh(getContext()), Ui.dp(getContext(), 1)) : null);
        issue.setCompoundDrawablesRelative(null, null, hasDetails ? Ui.icon(getContext(),
                expanded ? R.drawable.ic_chevron_up : R.drawable.ic_chevron_down, 20, Ui.textMuted(getContext())) : null, null);
        issue.setCompoundDrawablePadding(Ui.dp(getContext(), 8));
        issue.setContentDescription(hasDetails ? getContext().getString(R.string.series_details_accessibility,
                issue.getText(), getContext().getString(expanded
                        ? R.string.series_details_expanded : R.string.series_details_collapsed)) : issue.getText());
    }

    void resetScroll() {
        pendingScroll = new ScrollPosition(true, 0, 0); list.setSelection(0); requestLayout();
    }
    boolean hasDetails() { return hasDetails; }
    void toggleDetails() {
        if (!hasDetails) return;
        boolean visible = list.getFirstVisiblePosition() == 0 && list.getChildCount() > 0 &&
                header.getTop() + details.getBottom() > 0 && header.getTop() + details.getTop() < list.getHeight();
        expanded = !(expanded && visible);
        updateExpansion();
        list.setSelection(0);
    }
    void hide() { target = null; targetUri = ""; expanded = false; hasDetails = false; pendingScroll = null; covers.unbindCover(cover); }
    void setBottomInset(int bottom) { list.setPadding(0, 0, 0, Ui.dp(getContext(), 12) + bottom); }
    void updatePresentation(LibraryGridDensity density, LibraryTitleDisplay display) {
        pendingScroll = captureScroll(); this.density = density;
        cards.setDensity(density); cards.setTitleDisplay(display); rows.notifyDataSetChanged(); requestLayout();
    }

    private ScrollPosition captureScroll() {
        int first = list.getFirstVisiblePosition();
        return new ScrollPosition(first == 0, Math.max(0, first - 1) * rows.columns,
                list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop());
    }
    void saveState(Bundle state) {
        ScrollPosition position = captureScroll();
        state.putBoolean("series_header", position.header); state.putInt("series_first_issue", position.issue);
        state.putInt("series_offset", position.offset); state.putBoolean("series_expanded", expanded);
        state.putString("series_target", targetUri);
    }
    void restoreState(Bundle state) {
        expanded = targetUri.equals(state.getString("series_target", "")) && state.getBoolean("series_expanded");
        updateExpansion();
        pendingScroll = new ScrollPosition(state.getBoolean("series_header", true),
                state.getInt("series_first_issue"), state.getInt("series_offset")); requestLayout();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = Math.max(1, MeasureSpec.getSize(widthSpec) - Ui.dp(getContext(), 32));
        float fontScale = Math.max(1f, getResources().getConfiguration().fontScale);
        int width = Ui.dp(getContext(), Math.round(density.minimumCardWidthDp * fontScale));
        int columns = Math.max(1, (available + Ui.dp(getContext(), 12)) / (width + Ui.dp(getContext(), 12)));
        if (columns != rows.columns) {
            if (pendingScroll == null) pendingScroll = captureScroll();
            rows.columns = columns; rows.notifyDataSetChanged();
        }
        controls.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        controlsSpace.getLayoutParams().height = controls.getMeasuredHeight();
        if (pendingScroll != null) {
            ScrollPosition position = pendingScroll; pendingScroll = null;
            list.setSelectionFromTop(position.header ? 0 : 1 + position.issue / rows.columns, position.offset);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom); positionControls();
    }
    private void positionControls() {
        int position = list.getFirstVisiblePosition() == 0 && list.getChildCount() > 0
                ? list.getChildAt(0).getTop() + overview.getHeight() : 0;
        controls.setTranslationY(Math.max(0, position));
    }

    private final class IssueRows extends BaseAdapter {
        int columns = 1;
        @Override public int getCount() { return Math.max(1, (cards.getCount() + columns - 1) / columns); }
        @Override public Object getItem(int position) { return position; }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return cards.getCount() == 0 ? 1 : 0; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return cards.getCount() > 0; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            if (cards.getCount() == 0) {
                TextView empty = Ui.text(getContext(), getContext().getString(R.string.library_no_results), 16, Ui.textMuted(getContext()));
                empty.setPadding(Ui.dp(getContext(), 16), Ui.dp(getContext(), 24), Ui.dp(getContext(), 16), Ui.dp(getContext(), 24));
                return empty;
            }
            LinearLayout row = convertView instanceof LinearLayout layout ? layout : new LinearLayout(getContext());
            List<View> recycled = new ArrayList<>();
            for (int child = 0; child < row.getChildCount(); child++) recycled.add(row.getChildAt(child));
            row.removeAllViews(); row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(Ui.dp(getContext(), 16), Ui.dp(getContext(), 4), Ui.dp(getContext(), 16), Ui.dp(getContext(), density.verticalSpacingDp));
            for (int column = 0; column < columns; column++) {
                int item = position * columns + column;
                View previous = column < recycled.size() ? recycled.get(column) : null;
                View cell = item < cards.getCount() ? cards.getView(item,
                        previous != null && previous.getTag() != null ? previous : null, row) : new View(getContext());
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
                if (column > 0) params.setMarginStart(Ui.dp(getContext(), 12));
                row.addView(cell, params);
            }
            return row;
        }
    }
}

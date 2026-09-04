package io.github.jnesew.comicviewer.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.data.ReaderPreferences;
import io.github.jnesew.comicviewer.render.ComicCanvasView;
import io.github.jnesew.comicviewer.util.Ui;

public final class ReaderScreen extends FrameLayout {
    public interface Listener {
        void onHome();
        void onPrevious();
        void onNext();
        void onSeek(int page);
        void onPagePreviewRequested(int page);
        void onPagePreviewCancelled();
        void onBookmark();
        void onLayoutMenu(View anchor);
        void onFitMenu(View anchor);
        void onMoreMenu(View anchor);
        void onChromeVisibilityChanged(boolean visible);
    }

    private final ReaderPreferences preferences;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout topBar;
    private final LinearLayout bottomBar;
    private final TextView title;
    private final TextView bookmark;
    private final TextView pageLabel;
    private final TextView zoomLabel;
    private final TextView modeLabel;
    private final SeekBar seekBar;
    private final LinearLayout previewCard;
    private final ImageView previewImage;
    private final ProgressBar previewLoading;
    private final TextView previewUnavailable;
    private final TextView previewLabel;
    private final Runnable hideChrome;
    private boolean chromeVisible = true;
    private boolean updatingSeek;
    private boolean seekTracking;
    private int pageCount;
    private int selectedSeekPage;
    private int previewPage = -1;

    public final ComicCanvasView canvas;

    public ReaderScreen(
            Context context,
            ReaderPreferences preferences,
            Listener listener) {
        super(context);
        this.preferences = preferences;
        this.listener = listener;
        setBackgroundColor(Color.BLACK);

        canvas = new ComicCanvasView(context);
        addView(canvas, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(context);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4));
        topBar.setBackgroundColor(Color.argb(224, 13, 16, 21));
        TextView home = Ui.iconButton(context, "‹", context.getString(R.string.reader_back_to_library));
        home.setOnClickListener(view -> {
            keepChromeAwake();
            listener.onHome();
        });
        topBar.addView(home);
        title = Ui.text(context, context.getString(R.string.reader_default_title), 16, Ui.TEXT);
        Ui.bold(title);
        title.setSingleLine(true);
        topBar.addView(title, new LinearLayout.LayoutParams(0, Ui.dp(context, 52), 1f));
        bookmark = Ui.iconButton(context, "☆", context.getString(R.string.reader_add_bookmark));
        bookmark.setOnClickListener(view -> {
            keepChromeAwake();
            listener.onBookmark();
        });
        topBar.addView(bookmark);
        TextView more = Ui.iconButton(context, "⋮", context.getString(R.string.reader_menu));
        more.setOnClickListener(view -> {
            keepChromeAwake();
            listener.onMoreMenu(view);
        });
        topBar.addView(more);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        addView(topBar, topParams);

        bottomBar = new LinearLayout(context);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8));
        bottomBar.setBackgroundColor(Color.argb(232, 13, 16, 21));

        LinearLayout status = new LinearLayout(context);
        status.setGravity(Gravity.CENTER_VERTICAL);
        pageLabel = Ui.text(context, context.getString(R.string.reader_page_label, 1, 1), 13, Ui.TEXT);
        status.addView(pageLabel, new LinearLayout.LayoutParams(0, Ui.dp(context, 30), 1f));
        zoomLabel = Ui.text(context, context.getString(R.string.reader_fit_width), 13, Ui.ACCENT);
        zoomLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        zoomLabel.setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 4), 0);
        zoomLabel.setClickable(true);
        zoomLabel.setFocusable(true);
        zoomLabel.setContentDescription(context.getString(R.string.reader_page_fit_zoom_menu));
        zoomLabel.setOnClickListener(view -> {
            keepChromeAwake();
            listener.onFitMenu(view);
        });
        status.addView(zoomLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(context, 36)));
        bottomBar.addView(status);

        seekBar = new SeekBar(context);
        seekBar.setMax(0);
        seekBar.setContentDescription(context.getString(R.string.reader_page_slider));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    selectedSeekPage = progress;
                    pageLabel.setText(getResources().getString(
                            R.string.reader_page_label, progress + 1, pageCount));
                    requestPagePreview(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                handler.removeCallbacks(hideChrome);
                seekTracking = true;
                requestPagePreview(seekBar.getProgress());
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                seekTracking = false;
                listener.onPagePreviewCancelled();
                hidePagePreview();
                if (!updatingSeek) listener.onSeek(selectedSeekPage);
                keepChromeAwake();
            }
        });
        bottomBar.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 36)));

        LinearLayout controls = new LinearLayout(context);
        controls.setGravity(Gravity.CENTER);
        TextView previous = Ui.iconButton(context, "‹", context.getString(R.string.reader_previous_page));
        previous.setOnClickListener(view -> {
            keepChromeAwake();
            listener.onPrevious();
        });
        controls.addView(previous, new LinearLayout.LayoutParams(0, Ui.dp(context, 52), 1f));
        modeLabel = Ui.text(context, context.getString(R.string.reader_layout_single), 12, Ui.TEXT);
        modeLabel.setGravity(Gravity.CENTER);
        modeLabel.setClickable(true);
        modeLabel.setFocusable(true);
        modeLabel.setBackground(Ui.rounded(Ui.SURFACE_HIGH, Ui.dp(context, 18), 0, 0));
        modeLabel.setContentDescription(context.getString(R.string.reader_choose_layout));
        modeLabel.setMaxLines(2);
        modeLabel.setOnClickListener(view -> {
            keepChromeAwake();
            listener.onLayoutMenu(view);
        });
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(
                Ui.dp(context, 148), Ui.dp(context, 44));
        modeParams.leftMargin = Ui.dp(context, 8);
        modeParams.rightMargin = Ui.dp(context, 8);
        controls.addView(modeLabel, modeParams);
        TextView next = Ui.iconButton(context, "›", context.getString(R.string.reader_next_page));
        next.setOnClickListener(view -> {
            keepChromeAwake();
            listener.onNext();
        });
        controls.addView(next, new LinearLayout.LayoutParams(0, Ui.dp(context, 52), 1f));
        bottomBar.addView(controls);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        addView(bottomBar, bottomParams);

        previewCard = new LinearLayout(context);
        previewCard.setOrientation(LinearLayout.VERTICAL);
        previewCard.setPadding(
                Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 6));
        previewCard.setBackground(Ui.rounded(
                Ui.SURFACE_HIGH, Ui.dp(context, 14), Ui.ACCENT, Ui.dp(context, 1)));
        previewCard.setElevation(Ui.dp(context, 14));
        previewCard.setVisibility(INVISIBLE);
        previewCard.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        FrameLayout imageFrame = new FrameLayout(context);
        imageFrame.setBackground(Ui.rounded(
                Ui.HOME_BACKGROUND, Ui.dp(context, 9), 0, 0));
        previewImage = new ImageView(context);
        previewImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        imageFrame.addView(previewImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        previewLoading = new ProgressBar(context);
        FrameLayout.LayoutParams loadingParams = new FrameLayout.LayoutParams(
                Ui.dp(context, 32), Ui.dp(context, 32), Gravity.CENTER);
        imageFrame.addView(previewLoading, loadingParams);
        previewUnavailable = Ui.text(
                context, context.getString(R.string.reader_preview_unavailable), 11, Ui.TEXT_MUTED);
        previewUnavailable.setGravity(Gravity.CENTER);
        previewUnavailable.setPadding(Ui.dp(context, 6), 0, Ui.dp(context, 6), 0);
        previewUnavailable.setVisibility(GONE);
        imageFrame.addView(previewUnavailable, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        previewCard.addView(imageFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 144)));
        previewLabel = Ui.text(
                context, context.getString(R.string.reader_page_label, 1, 1), 12, Ui.TEXT);
        previewLabel.setGravity(Gravity.CENTER);
        Ui.bold(previewLabel);
        previewCard.addView(previewLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 30)));

        FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                Ui.dp(context, 128), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        previewParams.leftMargin = Ui.dp(context, 8);
        previewParams.bottomMargin = Ui.dp(context, 148);
        addView(previewCard, previewParams);

        setOnApplyWindowInsetsListener((view, insets) -> {
            topBar.setPadding(Ui.dp(context, 4),
                    Ui.dp(context, 4) + insets.getSystemWindowInsetTop(),
                    Ui.dp(context, 4), Ui.dp(context, 4));
            bottomBar.setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10),
                    Ui.dp(context, 8) + insets.getSystemWindowInsetBottom());
            if (seekTracking) post(this::positionPagePreview);
            return insets;
        });
        hideChrome = this::hideChromeNow;
    }

    public void setTitle(String value) {
        title.setText(value);
    }

    public void updatePosition(int page, int pageEnd, int count) {
        pageCount = Math.max(1, count);
        pageLabel.setText(pageEnd > page
                ? getResources().getString(
                        R.string.reader_page_range_label, page + 1, pageEnd + 1, pageCount)
                : getResources().getString(R.string.reader_page_label, page + 1, pageCount));
        updatingSeek = true;
        seekBar.setMax(Math.max(0, pageCount - 1));
        seekBar.setProgress(Math.max(0, Math.min(page, pageCount - 1)));
        selectedSeekPage = page;
        updatingSeek = false;
    }

    public void updateZoom(String mode, float zoom, boolean gesturesLocked) {
        String zoomText = switch (mode) {
            case ComicCanvasView.FIT_PAGE -> getResources().getString(R.string.reader_fit_page);
            case ComicCanvasView.MANUAL -> getResources().getString(
                    R.string.reader_width_percent, Math.round(zoom * 100f));
            case ComicCanvasView.CONTINUOUS ->
                    zoom == 1f ? getResources().getString(R.string.reader_fit_width) :
                            getResources().getString(
                                    R.string.reader_width_percent, Math.round(zoom * 100f));
            default -> getResources().getString(R.string.reader_fit_width);
        };
        zoomLabel.setText(gesturesLocked
                ? getResources().getString(R.string.reader_zoom_locked_label, zoomText)
                : zoomText);
        zoomLabel.setContentDescription(getResources().getString(gesturesLocked
                ? R.string.reader_zoom_locked_description
                : R.string.reader_page_fit_zoom_menu));
    }

    public void updateMode(String readingMode) {
        int label = switch (readingMode) {
            case ComicCanvasView.CONTINUOUS -> R.string.reader_layout_continuous;
            case ComicCanvasView.SPREAD -> R.string.reader_layout_spread;
            default -> R.string.reader_layout_single;
        };
        modeLabel.setText(label);
    }

    public void updateBookmark(boolean bookmarked) {
        bookmark.setText(bookmarked ? "★" : "☆");
        bookmark.setContentDescription(getResources().getString(bookmarked ?
                R.string.reader_remove_bookmark : R.string.reader_add_bookmark));
    }

    public void showPagePreview(int page, Bitmap bitmap) {
        if (!seekTracking || page != previewPage || bitmap == null || bitmap.isRecycled()) return;
        previewImage.setImageBitmap(bitmap);
        previewLoading.setVisibility(GONE);
        previewUnavailable.setVisibility(GONE);
    }

    public void showPagePreviewUnavailable(int page) {
        if (!seekTracking || page != previewPage) return;
        previewImage.setImageDrawable(null);
        previewLoading.setVisibility(GONE);
        previewUnavailable.setVisibility(VISIBLE);
    }

    public void dismissPagePreview() {
        seekTracking = false;
        listener.onPagePreviewCancelled();
        hidePagePreview();
    }

    public void toggleChrome() {
        if (chromeVisible) hideChrome.run();
        else showChrome();
    }

    public void showChrome() {
        handler.removeCallbacks(hideChrome);
        chromeVisible = true;
        topBar.setVisibility(VISIBLE);
        bottomBar.setVisibility(VISIBLE);
        topBar.animate().cancel();
        bottomBar.animate().cancel();
        topBar.setAlpha(0f);
        bottomBar.setAlpha(0f);
        topBar.setTranslationY(-Ui.dp(getContext(), 16));
        bottomBar.setTranslationY(Ui.dp(getContext(), 16));
        topBar.animate().alpha(1f).translationY(0f).setDuration(160).start();
        bottomBar.animate().alpha(1f).translationY(0f).setDuration(160).start();
        listener.onChromeVisibilityChanged(true);
        keepChromeAwake();
    }

    public void keepChromeAwake() {
        handler.removeCallbacks(hideChrome);
        if (chromeVisible && preferences.autoHideControls()) {
            handler.postDelayed(hideChrome, 3500L);
        }
    }

    private void hideChromeNow() {
        if (!chromeVisible) return;
        chromeVisible = false;
        topBar.animate().cancel();
        bottomBar.animate().cancel();
        topBar.animate().alpha(0f).translationY(-topBar.getHeight() * 0.25f)
                .setDuration(170).withEndAction(() -> topBar.setVisibility(INVISIBLE)).start();
        bottomBar.animate().alpha(0f).translationY(bottomBar.getHeight() * 0.25f)
                .setDuration(170).withEndAction(() -> bottomBar.setVisibility(INVISIBLE)).start();
        listener.onChromeVisibilityChanged(false);
    }

    private void requestPagePreview(int page) {
        if (!seekTracking || page == previewPage) return;
        previewPage = page;
        previewImage.setImageDrawable(null);
        previewLoading.setVisibility(VISIBLE);
        previewUnavailable.setVisibility(GONE);
        previewLabel.setText(getResources().getString(
                R.string.reader_page_label, page + 1, pageCount));
        positionPagePreview();

        previewCard.animate().cancel();
        if (previewCard.getVisibility() != VISIBLE) {
            previewCard.setAlpha(0f);
            previewCard.setScaleX(0.94f);
            previewCard.setScaleY(0.94f);
            previewCard.setVisibility(VISIBLE);
            previewCard.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(120).start();
        } else {
            previewCard.setAlpha(1f);
            previewCard.setScaleX(1f);
            previewCard.setScaleY(1f);
        }
        listener.onPagePreviewRequested(page);
    }

    private void hidePagePreview() {
        previewPage = -1;
        previewCard.animate().cancel();
        previewCard.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f)
                .setDuration(110)
                .withEndAction(() -> {
                    if (seekTracking) return;
                    previewCard.setVisibility(INVISIBLE);
                    previewImage.setImageDrawable(null);
                    previewLoading.setVisibility(GONE);
                    previewUnavailable.setVisibility(GONE);
                })
                .start();
    }

    private void positionPagePreview() {
        if (!seekTracking) return;
        if (getWidth() <= 0 || seekBar.getWidth() <= 0 || bottomBar.getHeight() <= 0) {
            post(this::positionPagePreview);
            return;
        }
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) previewCard.getLayoutParams();
        int trackWidth = Math.max(1,
                seekBar.getWidth() - seekBar.getPaddingLeft() - seekBar.getPaddingRight());
        float ratio = seekBar.getMax() <= 0
                ? 0.5f : (float) selectedSeekPage / seekBar.getMax();
        int[] rootLocation = new int[2];
        int[] seekLocation = new int[2];
        getLocationInWindow(rootLocation);
        seekBar.getLocationInWindow(seekLocation);
        float thumbCenter = seekLocation[0] - rootLocation[0] + seekBar.getPaddingLeft() +
                trackWidth * ratio;
        int side = Ui.dp(getContext(), 8);
        int left = Math.round(thumbCenter - params.width / 2f);
        left = Math.max(side, Math.min(
                Math.max(side, getWidth() - params.width - side), left));
        int bottomMargin = bottomBar.getHeight() + Ui.dp(getContext(), 8);
        if (params.bottomMargin != bottomMargin) {
            params.bottomMargin = bottomMargin;
            previewCard.setLayoutParams(params);
        }
        previewCard.setX(left);
    }
}

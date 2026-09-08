package io.github.jnesew.comicviewer.render;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import io.github.jnesew.comicviewer.R;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.PageLayoutEngine;
import io.github.jnesew.comicviewer.util.SpreadPageLayout;
import io.github.jnesew.comicviewer.util.Ui;
import io.github.jnesew.comicviewer.util.ZoomGestureGate;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

/** Touch-first page and continuous canvas with tile-backed rendering. */
public final class ComicCanvasView extends View {
    public interface Listener {
        void onReaderPositionChanged(String documentKey, int page, float pageRatio);
        void onReaderZoomChanged(String mode, float zoom);
        void onNavigateRequested(int delta);
        void onChromeToggleRequested();
        void onContinuousBoundaryApproached(int direction);
        void onContinuousBoundaryRetry(int direction);
        void onUnavailableRetry(String documentKey);
    }

    public static final class ContinuousDocument {
        public final String key;
        public final String title;
        public final TileRenderer renderer;
        public final List<PageInfo> pages;

        public ContinuousDocument(
                String key, String title, TileRenderer renderer, List<PageInfo> pages) {
            this.key = key == null ? "" : key;
            this.title = title == null ? "" : title;
            this.renderer = renderer;
            this.pages = pages;
        }
    }

    public static final String FIT_WIDTH = "fit_width";
    public static final String FIT_PAGE = "fit_page";
    public static final String MANUAL = "manual";
    public static final String SINGLE = "single";
    public static final String SPREAD = "spread";
    public static final String CONTINUOUS = "continuous";

    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;
    private final OverScroller scroller;
    private final ZoomGestureGate zoomGestureGate = new ZoomGestureGate();
    private final PageLayoutEngine continuousLayout = new PageLayoutEngine();
    private final SpreadPageLayout spreadLayout = new SpreadPageLayout();
    private final RectF destination = new RectF();
    private final RectF clip = new RectF();
    private final Paint separatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint separatorLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private TileRenderer renderer;
    private List<PageInfo> pages = Collections.emptyList();
    private List<ContinuousDocument> continuousDocuments = Collections.emptyList();
    private ContinuousPageMap pageMap = new ContinuousPageMap(Collections.emptyList());
    private float[] continuousExtraBefore = new float[0];
    private String topBoundaryText = "";
    private String bottomBoundaryText = "";
    private boolean topBoundaryRetry;
    private boolean bottomBoundaryRetry;
    private boolean topApproachSent;
    private boolean bottomApproachSent;
    private Listener listener;
    private int page;
    private float pageRatio;
    private boolean continuous;
    private boolean spread;
    private boolean tapZones = true;
    private boolean rightToLeft;
    private int canvasColor = Color.BLACK;

    // Paged transform. zoom is a multiplier relative to fit-width.
    private String zoomMode = FIT_WIDTH;
    private float zoom = 1f;
    private float singleScale = 1f;
    private float singleX;
    private float singleY;

    // Continuous transform.
    private float continuousZoom = 1f;
    private float documentScroll;
    private float continuousPanX;
    private float pinchStartZoom = 1f;
    private final float pageGap;
    private final float issueSeparatorHeight;
    private final float boundaryHeight;

    private int pendingRestorePage;
    private float pendingRestoreRatio;
    private boolean pendingRestore;

    public ComicCanvasView(Context context) {
        this(context, null);
    }

    public ComicCanvasView(Context context, AttributeSet attributes) {
        super(context, attributes);
        setFocusable(true);
        setClickable(true);
        setContentDescription(context.getString(R.string.reader_default_title));
        pageGap = Ui.dp(context, 8);
        issueSeparatorHeight = Ui.dp(context, 72);
        boundaryHeight = Ui.dp(context, 58);
        separatorPaint.setColor(Color.rgb(215, 221, 233));
        separatorPaint.setTextSize(Ui.dp(context, 14));
        separatorPaint.setTextAlign(Paint.Align.CENTER);
        separatorLinePaint.setColor(Color.rgb(85, 91, 104));
        separatorLinePaint.setStrokeWidth(Math.max(1f, Ui.dp(context, 1)));
        scroller = new OverScroller(context);
        gestureDetector = new GestureDetector(context, new Gestures());
        scaleDetector = new ScaleGestureDetector(context, new Scaling());
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setDocument(TileRenderer renderer, List<PageInfo> pages, ReadingProgress progress) {
        this.renderer = renderer;
        this.pages = pages;
        this.page = clamp(progress.page, 0, Math.max(0, pages.size() - 1));
        this.pageRatio = clamp(progress.scrollRatio, 0f, 1f);
        this.continuous = CONTINUOUS.equals(progress.readingMode);
        this.spread = SPREAD.equals(progress.readingMode);
        if (continuous) {
            installContinuousDocuments(Collections.singletonList(new ContinuousDocument(
                    progress.uri, progress.title, renderer, pages)));
        } else {
            clearContinuousDocuments();
        }
        spreadLayout.calculate(pages);
        if (spread) this.page = spreadLayout.anchorFor(this.page);
        zoomGestureGate.setLocked(progress.zoomGesturesLocked);
        if (continuous) {
            continuousZoom = clamp(progress.zoom, 0.6f, 5f);
            zoomMode = CONTINUOUS;
            zoom = continuousZoom;
        } else {
            zoomMode = switch (progress.zoomMode) {
                case FIT_PAGE, MANUAL -> progress.zoomMode;
                default -> FIT_WIDTH;
            };
            zoom = clamp(progress.zoom, 0.1f, 12f);
        }
        pendingRestorePage = page;
        pendingRestoreRatio = pageRatio;
        pendingRestore = true;
        relayoutDocument();
        invalidate();
    }

    public void clearDocument() {
        renderer = null;
        pages = Collections.emptyList();
        clearContinuousDocuments();
        spreadLayout.calculate(pages);
        scroller.forceFinished(true);
        invalidate();
    }

    /** Relayout around the current reading anchor after background page dimensions arrive. */
    public void onPageInfoChanged() {
        if (pages.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;
        int anchorPage = page;
        float anchorRatio = pageRatio;
        spreadLayout.calculate(pages);
        if (spread) {
            anchorPage = spreadLayout.anchorFor(anchorPage);
            page = anchorPage;
        }
        if (continuous) relayoutContinuousAround(anchorPage, anchorRatio);
        else configurePaged(anchorRatio);
        invalidate();
    }

    public int page() {
        return continuous ? localPageFor(page) : page;
    }

    public float pageRatio() {
        return pageRatio;
    }

    public boolean isContinuous() {
        return continuous;
    }

    public boolean isSpread() {
        return spread;
    }

    public String readingMode() {
        if (continuous) return CONTINUOUS;
        return spread ? SPREAD : SINGLE;
    }

    public int pageEnd() {
        if (continuous) return localPageFor(page);
        return spread ? spreadLayout.endFor(page) : page;
    }

    public int navigationTarget(int delta) {
        if (pages.isEmpty()) return 0;
        if (continuous) {
            int local = localPageFor(page);
            int issue = issueFor(page);
            int count = issue >= 0 && issue < continuousDocuments.size()
                    ? continuousDocuments.get(issue).pages.size() : pages.size();
            return clamp(local + delta, 0, Math.max(0, count - 1));
        }
        if (!spread) {
            return clamp(page + delta, 0, pages.size() - 1);
        }
        return spreadLayout.adjacentAnchor(page, delta);
    }

    public boolean isAtDocumentEnd() {
        if (pages.isEmpty()) return false;
        if (!continuous) return pageEnd() >= pages.size() - 1;
        int issue = issueFor(page);
        if (issue < 0) return false;
        int end = pageMap.end(issue);
        float bottom = continuousLayout.top(end) + continuousLayout.height(end) + pageGap;
        float maximum = Math.max(continuousLayout.top(pageMap.start(issue)),
                bottom - Math.max(1, getHeight()));
        return documentScroll >= maximum - 1f;
    }

    public boolean isAtDocumentStart() {
        if (pages.isEmpty()) return false;
        if (!continuous) return page == 0;
        int issue = issueFor(page);
        return issue >= 0 && documentScroll <= continuousLayout.top(
                pageMap.start(issue)) + 1f;
    }

    public String continuousDocumentKey() {
        int issue = issueFor(page);
        return issue >= 0 && issue < continuousDocuments.size()
                ? continuousDocuments.get(issue).key : "";
    }

    public boolean moveContinuousPage(int delta) {
        if (!continuous || pages.isEmpty() || delta == 0) return false;
        int issue = issueFor(page);
        int local = localPageFor(page);
        if (issue < 0) return false;
        if (delta < 0 && local == 0 && !isAtDocumentStart()) return false;
        if (delta > 0 && local == continuousDocuments.get(issue).pages.size() - 1 &&
                !isAtDocumentEnd()) return false;
        int target = page + (delta > 0 ? 1 : -1);
        if (target < 0 || target >= pages.size()) return false;
        int targetIssue = issueFor(target);
        if (delta < 0 && targetIssue != issue) {
            showContinuousIssueEnd(targetIssue);
        } else {
            showGlobalPage(target, 0f);
        }
        return true;
    }

    public void setContinuousDocuments(
            List<ContinuousDocument> documents,
            String anchorKey,
            int anchorPage,
            float anchorRatio) {
        if (!continuous || documents == null || documents.isEmpty()) return;
        scroller.forceFinished(true);
        installContinuousDocuments(documents);
        ContinuousPageMap.Position restored = pageMap.restore(
                new ContinuousPageMap.Anchor(anchorKey, anchorPage, anchorRatio), page);
        page = restored.page();
        pageRatio = restored.ratio();
        pendingRestorePage = page;
        pendingRestoreRatio = pageRatio;
        pendingRestore = true;
        relayoutDocument();
        invalidate();
    }

    public void setContinuousBoundary(
            int direction, String text, boolean retry) {
        if (direction < 0) {
            topBoundaryText = text == null ? "" : text;
            topBoundaryRetry = retry;
        } else {
            bottomBoundaryText = text == null ? "" : text;
            bottomBoundaryRetry = retry;
        }
        invalidate();
    }

    public String zoomMode() {
        return continuous ? CONTINUOUS : zoomMode;
    }

    public float zoom() {
        return continuous ? continuousZoom : zoom;
    }

    public boolean zoomGesturesLocked() {
        return zoomGestureGate.isLocked();
    }

    public void setZoomGesturesLocked(boolean locked) {
        zoomGestureGate.setLocked(locked);
    }

    public void setTapZones(boolean enabled) {
        tapZones = enabled;
    }

    public void setRightToLeft(boolean enabled) {
        rightToLeft = enabled;
        invalidate();
    }

    public void setCanvasColor(int color) {
        canvasColor = color;
        int brightness = (Color.red(color) * 299 + Color.green(color) * 587 +
                Color.blue(color) * 114) / 1000;
        separatorPaint.setColor(brightness >= 150
                ? Color.rgb(42, 45, 52) : Color.rgb(215, 221, 233));
        separatorLinePaint.setColor(brightness >= 150
                ? Color.rgb(130, 126, 118) : Color.rgb(85, 91, 104));
        invalidate();
    }

    public void setReadingMode(String requestedMode) {
        String normalized = switch (requestedMode) {
            case CONTINUOUS -> CONTINUOUS;
            case SPREAD -> SPREAD;
            default -> SINGLE;
        };
        if (readingMode().equals(normalized) || pages.isEmpty()) return;
        int anchorPage = page;
        float anchorRatio = pageRatio;
        continuous = CONTINUOUS.equals(normalized);
        spread = SPREAD.equals(normalized);
        if (spread) anchorPage = spreadLayout.anchorFor(anchorPage);
        scroller.forceFinished(true);
        if (continuous) {
            continuousZoom = 1f;
            zoomMode = CONTINUOUS;
            zoom = 1f;
        } else {
            zoomMode = FIT_WIDTH;
            zoom = 1f;
        }
        pendingRestorePage = anchorPage;
        pendingRestoreRatio = anchorRatio;
        pendingRestore = true;
        relayoutDocument();
        notifyZoom();
        notifyPosition();
        invalidate();
    }

    public void showPage(int targetPage, float restoreRatio) {
        if (pages.isEmpty()) return;
        page = continuous
                ? globalPageFor(continuousDocumentKey(), targetPage)
                : clamp(targetPage, 0, pages.size() - 1);
        if (spread) page = spreadLayout.anchorFor(page);
        pageRatio = clamp(restoreRatio, 0f, 1f);
        scroller.forceFinished(true);
        if (continuous) {
            documentScroll = clampScroll(continuousLayout.positionFor(page, pageRatio));
        } else {
            configurePaged(pageRatio);
        }
        notifyPosition();
        invalidate();
    }

    public void fitWidth() {
        if (pages.isEmpty()) return;
        if (continuous) {
            continuousZoom = 1f;
            continuousPanX = 0f;
            relayoutContinuousAround(page, pageRatio);
        } else {
            pageRatio = currentSingleRatio();
            zoomMode = FIT_WIDTH;
            zoom = 1f;
            configurePaged(pageRatio);
        }
        notifyZoom();
        notifyPosition();
        invalidate();
    }

    public void fitPage() {
        if (pages.isEmpty()) return;
        if (continuous) {
            fitWidth();
            return;
        }
        zoomMode = FIT_PAGE;
        zoom = 1f;
        configurePaged(0f);
        notifyZoom();
        notifyPosition();
        invalidate();
    }

    public void actualSize() {
        if (pages.isEmpty()) return;
        if (continuous) {
            PageInfo info = pages.get(page);
            float base = contentWidth() / info.width;
            applyContinuousZoom(clamp(1f / Math.max(0.0001f, base), 0.6f, 5f),
                    getWidth() / 2f, getHeight() / 2f);
            return;
        }
        float fit = fitWidthScale();
        zoomMode = MANUAL;
        zoom = clamp(1f / Math.max(0.0001f, fit), 0.1f, 12f);
        singleScale = 1f;
        centerPagedOnRatio(pageRatio);
        notifyZoom();
        notifyPosition();
        invalidate();
    }

    public void multiplyZoom(float factor) {
        if (pages.isEmpty()) return;
        if (continuous) {
            applyContinuousZoom(continuousZoom * factor, getWidth() / 2f, getHeight() / 2f);
        } else {
            applyPagedZoom(zoom * factor, getWidth() / 2f, getHeight() / 2f);
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (pages.isEmpty()) return;
        int anchorPage = page;
        float anchorRatio = pageRatio;
        relayoutDocument();
        if (continuous) relayoutContinuousAround(anchorPage, anchorRatio);
        else configurePaged(anchorRatio);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(canvasColor);
        if (renderer == null || pages.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;

        if (continuous) drawContinuous(canvas);
        else drawPaged(canvas);
    }

    private void drawPaged(Canvas canvas) {
        clip.set(0f, 0f, getWidth(), getHeight());
        if (!spread || spreadLayout.endFor(page) == page) {
            PageInfo info = pages.get(page);
            destination.set(singleX, singleY,
                    singleX + info.width * singleScale,
                    singleY + info.height * singleScale);
            renderer.drawPages(canvas, Collections.singletonList(
                    new TileRenderer.PageRequest(page, destination, clip)));
            return;
        }

        int leftPage = spreadLayout.leftPage(page, rightToLeft);
        int rightPage = spreadLayout.rightPage(page, rightToLeft);
        float sourceHeight = pagedSourceHeight();
        float leftWidth = normalizedPageWidth(leftPage, sourceHeight) * singleScale;
        float rightWidth = normalizedPageWidth(rightPage, sourceHeight) * singleScale;
        float height = sourceHeight * singleScale;
        destination.set(singleX, singleY, singleX + leftWidth, singleY + height);
        TileRenderer.PageRequest left = new TileRenderer.PageRequest(leftPage, destination, clip);
        float rightX = singleX + leftWidth + pageGap;
        destination.set(rightX, singleY, rightX + rightWidth, singleY + height);
        renderer.drawPages(canvas, java.util.Arrays.asList(left,
                new TileRenderer.PageRequest(rightPage, destination, clip)));
    }

    private void drawContinuous(Canvas canvas) {
        if (continuousLayout.size() == 0) return;
        // Intersecting tiles already extend beyond the viewport to their grid edges.
        // Avoid spending the visible-frame budget on extra offscreen raster/PDF tiles.
        float prefetch = 0f;
        int first = continuousLayout.pageAt(Math.max(0f, documentScroll - prefetch));
        int last = continuousLayout.pageAt(Math.min(
                continuousLayout.documentHeight(), documentScroll + getHeight() + prefetch));
        float pageWidth = contentWidth() * continuousZoom;
        float x = (getWidth() - pageWidth) / 2f + continuousPanX;
        clip.set(0f, -prefetch, getWidth(), getHeight() + prefetch);
        drawContinuousLabels(canvas, first, last);
        java.util.Map<TileRenderer, List<TileRenderer.PageRequest>> requests =
                new java.util.LinkedHashMap<>();
        for (int index = first; index <= last; index++) {
            float top = continuousLayout.top(index) - documentScroll;
            destination.set(x, top, x + pageWidth, top + continuousLayout.height(index));
            TileRenderer pageRenderer = rendererForPage(index);
            if (pageRenderer != null) {
                // PDF pages use viewport-only requests even beside a raster issue.
                RectF pageClip = pageRenderer.usesRenderedTiles()
                        ? new RectF(0f, 0f, getWidth(), getHeight()) : clip;
                requests.computeIfAbsent(pageRenderer, key -> new ArrayList<>()).add(
                        new TileRenderer.PageRequest(
                        index < pageMap.size() ? pageMap.localPageFor(index) : index,
                        destination,
                        pageClip));
            }
        }
        for (java.util.Map.Entry<TileRenderer, List<TileRenderer.PageRequest>> entry :
                requests.entrySet()) {
            entry.getKey().drawPages(canvas, entry.getValue());
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            zoomGestureGate.resetTouch();
        }
        boolean scaled = scaleDetector.onTouchEvent(event);
        boolean gestured = gestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP ||
                event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            notifyPosition();
            zoomGestureGate.resetTouch();
        }
        return scaled || gestured || super.onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_CLASS_POINTER) != 0 &&
                event.getAction() == MotionEvent.ACTION_SCROLL) {
            float wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL);
            if ((event.getMetaState() & KeyEvent.META_CTRL_ON) != 0) {
                multiplyZoom(wheel > 0 ? 1.12f : 1f / 1.12f);
            } else if (continuous) {
                documentScroll = clampScroll(documentScroll - wheel * Ui.dp(getContext(), 72));
                updateContinuousPosition();
                invalidate();
            } else {
                singleY += wheel * Ui.dp(getContext(), 72);
                clampSingleOffsets();
                pageRatio = currentSingleRatio();
                notifyPosition();
                invalidate();
            }
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void computeScroll() {
        if (!scroller.computeScrollOffset()) return;
        if (continuous) {
            documentScroll = scroller.getCurrY();
            updateContinuousPosition();
        } else {
            singleX = -scroller.getCurrX();
            singleY = -scroller.getCurrY();
            clampSingleOffsets();
            pageRatio = currentSingleRatio();
            notifyPosition();
        }
        postInvalidateOnAnimation();
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private void relayoutDocument() {
        if (getWidth() <= 0 || pages.isEmpty()) return;
        if (continuous) {
            calculateContinuousLayout();
            if (pendingRestore) {
                documentScroll = clampScroll(
                        continuousLayout.positionFor(pendingRestorePage, pendingRestoreRatio));
                pendingRestore = false;
            } else {
                documentScroll = clampScroll(documentScroll);
            }
            clampContinuousPan();
            updateContinuousPosition();
        } else {
            configurePaged(pendingRestore ? pendingRestoreRatio : pageRatio);
            pendingRestore = false;
        }
    }

    private void configurePaged(float restoreRatio) {
        if (pages.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;
        if (FIT_PAGE.equals(zoomMode)) {
            singleScale = Math.min(fitWidthScale(),
                    (float) getHeight() / pagedSourceHeight());
        } else if (MANUAL.equals(zoomMode)) {
            singleScale = fitWidthScale() * clamp(zoom, 0.1f, 12f);
        } else {
            singleScale = fitWidthScale();
        }
        centerPagedOnRatio(restoreRatio);
    }

    private void centerPagedOnRatio(float restoreRatio) {
        float width = pagedDisplayWidth(singleScale);
        float height = pagedDisplayHeight(singleScale);
        singleX = (getWidth() - width) / 2f;
        if (height > getHeight()) {
            singleY = -clamp(restoreRatio, 0f, 1f) * (height - getHeight());
        } else if (FIT_WIDTH.equals(zoomMode)) {
            singleY = 0f;
        } else {
            singleY = (getHeight() - height) / 2f;
        }
        clampSingleOffsets();
        pageRatio = currentSingleRatio();
    }

    private void applyPagedZoom(float requestedZoom, float focusX, float focusY) {
        if (pages.isEmpty()) return;
        float horizontalAnchor = (focusX - singleX) /
                Math.max(1f, pagedDisplayWidth(singleScale));
        float verticalAnchor = (focusY - singleY) /
                Math.max(1f, pagedDisplayHeight(singleScale));
        zoomMode = MANUAL;
        zoom = clamp(requestedZoom, 0.1f, 12f);
        singleScale = fitWidthScale() * zoom;
        singleX = focusX - horizontalAnchor * pagedDisplayWidth(singleScale);
        singleY = focusY - verticalAnchor * pagedDisplayHeight(singleScale);
        clampSingleOffsets();
        pageRatio = currentSingleRatio();
        notifyZoom();
        notifyPosition();
        invalidate();
    }

    private void applyContinuousZoom(float requestedZoom, float focusX, float focusY) {
        if (pages.isEmpty() || getWidth() <= 0) return;
        float oldDocumentHeight = Math.max(1f, continuousLayout.documentHeight());
        float verticalAnchor = (documentScroll + focusY) / oldDocumentHeight;
        float oldWidth = contentWidth() * continuousZoom;
        float oldLeft = (getWidth() - oldWidth) / 2f + continuousPanX;
        float horizontalAnchor = (focusX - oldLeft) / Math.max(1f, oldWidth);

        continuousZoom = clamp(requestedZoom, 0.6f, 5f);
        zoom = continuousZoom;
        zoomMode = CONTINUOUS;
        calculateContinuousLayout();
        documentScroll = clampScroll(
                verticalAnchor * continuousLayout.documentHeight() - focusY);
        float newWidth = contentWidth() * continuousZoom;
        continuousPanX = focusX - horizontalAnchor * newWidth - (getWidth() - newWidth) / 2f;
        clampContinuousPan();
        updateContinuousPosition();
        notifyZoom();
        invalidate();
    }

    private void relayoutContinuousAround(int anchorPage, float anchorRatio) {
        calculateContinuousLayout();
        documentScroll = clampScroll(continuousLayout.positionFor(anchorPage, anchorRatio));
        clampContinuousPan();
        updateContinuousPosition();
    }

    private void clampSingleOffsets() {
        if (pages.isEmpty()) return;
        float width = pagedDisplayWidth(singleScale);
        float height = pagedDisplayHeight(singleScale);
        if (width <= getWidth()) singleX = (getWidth() - width) / 2f;
        else singleX = clamp(singleX, getWidth() - width, 0f);

        if (height <= getHeight()) {
            singleY = FIT_WIDTH.equals(zoomMode) ? 0f : (getHeight() - height) / 2f;
        } else {
            singleY = clamp(singleY, getHeight() - height, 0f);
        }
    }

    private void clampContinuousPan() {
        float overflow = Math.max(0f, (contentWidth() * continuousZoom - getWidth()) / 2f);
        continuousPanX = clamp(continuousPanX, -overflow, overflow);
    }

    private float clampScroll(float value) {
        return clamp(value, 0f, continuousLayout.maximumScroll(getHeight()));
    }

    private float currentSingleRatio() {
        if (pages.isEmpty()) return 0f;
        float height = pagedDisplayHeight(singleScale);
        float range = height - getHeight();
        return range <= 0f ? 0f : clamp(-singleY / range, 0f, 1f);
    }

    private void updateContinuousPosition() {
        if (continuousLayout.size() == 0) return;
        int newPage = continuousLayout.pageAt(documentScroll + 1f);
        float newRatio = continuousLayout.pageRatio(newPage, documentScroll);
        page = newPage;
        pageRatio = newRatio;
        notifyPosition();
        notifyContinuousBoundaries();
    }

    private float fitWidthScale() {
        if (pages.isEmpty()) return 1f;
        float available = Math.max(1f, getWidth() - pagedScreenGap());
        return available / pagedSourceWidth();
    }

    private float pagedSourceWidth() {
        if (!spread || spreadLayout.endFor(page) == page) return pages.get(page).width;
        float height = pagedSourceHeight();
        return normalizedPageWidth(spreadLayout.leftPage(page, rightToLeft), height) +
                normalizedPageWidth(spreadLayout.rightPage(page, rightToLeft), height);
    }

    private float pagedSourceHeight() {
        if (!spread || spreadLayout.endFor(page) == page) return pages.get(page).height;
        return Math.max(pages.get(spreadLayout.leftPage(page, rightToLeft)).height,
                pages.get(spreadLayout.rightPage(page, rightToLeft)).height);
    }

    private float pagedDisplayWidth(float scale) {
        return pagedSourceWidth() * scale + pagedScreenGap();
    }

    private float pagedDisplayHeight(float scale) {
        return pagedSourceHeight() * scale;
    }

    private float pagedScreenGap() {
        return spread && spreadLayout.endFor(page) > page ? pageGap : 0f;
    }

    private float normalizedPageWidth(int pageIndex, float commonHeight) {
        PageInfo info = pages.get(pageIndex);
        return commonHeight * info.width / info.height;
    }

    private float contentWidth() {
        return Math.max(1f, getWidth() - pageGap * 2f);
    }

    private void installContinuousDocuments(List<ContinuousDocument> documents) {
        ArrayList<ContinuousDocument> accepted = new ArrayList<>();
        int pageCount = 0;
        for (ContinuousDocument document : documents) {
            if (document == null || document.renderer == null || document.pages == null ||
                    document.pages.isEmpty()) continue;
            accepted.add(document);
            pageCount += document.pages.size();
        }
        if (accepted.isEmpty()) return;

        continuousDocuments = Collections.unmodifiableList(accepted);
        ArrayList<ContinuousPageMap.Issue> issues = new ArrayList<>();
        ArrayList<PageInfo> flattened = new ArrayList<>(pageCount);
        for (ContinuousDocument document : accepted) {
            issues.add(new ContinuousPageMap.Issue(document.key, document.pages.size()));
            flattened.addAll(document.pages);
        }
        pageMap = new ContinuousPageMap(issues);
        continuousExtraBefore = new float[pageMap.size()];
        for (int issue = 1; issue < pageMap.issueCount(); issue++) {
            continuousExtraBefore[pageMap.start(issue)] = issueSeparatorHeight;
        }
        pages = Collections.unmodifiableList(flattened);
        topApproachSent = false;
        bottomApproachSent = false;
    }

    private void clearContinuousDocuments() {
        continuousDocuments = Collections.emptyList();
        pageMap = new ContinuousPageMap(Collections.emptyList());
        continuousExtraBefore = new float[0];
        topBoundaryText = "";
        bottomBoundaryText = "";
        topBoundaryRetry = false;
        bottomBoundaryRetry = false;
        topApproachSent = false;
        bottomApproachSent = false;
    }

    private int issueFor(int globalPage) {
        return continuous ? pageMap.issueFor(globalPage) : -1;
    }

    private int localPageFor(int globalPage) {
        return continuous ? pageMap.localPageFor(globalPage)
                : clamp(globalPage, 0, Math.max(0, pages.size() - 1));
    }

    private int globalPageFor(String key, int localPage) {
        return pageMap.globalPageFor(key, localPage, page);
    }

    private TileRenderer rendererForPage(int globalPage) {
        int issue = issueFor(globalPage);
        return issue >= 0 && issue < continuousDocuments.size()
                ? continuousDocuments.get(issue).renderer : renderer;
    }

    private void calculateContinuousLayout() {
        if (continuousExtraBefore.length > 0) {
            continuousExtraBefore[0] = boundaryHeight;
        }
        continuousLayout.calculate(
                pages, contentWidth(), continuousZoom, pageGap,
                continuousExtraBefore,
                boundaryHeight);
    }

    private void drawContinuousLabels(Canvas canvas, int first, int last) {
        for (int issue = 1; issue < pageMap.issueCount(); issue++) {
            int start = pageMap.start(issue);
            if (start < first - 1 || start > last + 1) continue;
            float center = continuousLayout.top(start) - issueSeparatorHeight / 2f - documentScroll;
            drawSeparatorLabel(canvas, continuousDocuments.get(issue).title, center);
        }
        if (!topBoundaryText.isEmpty() && pageMap.issueCount() > 0) {
            float center = continuousLayout.top(0) - boundaryHeight / 2f - documentScroll;
            if (center > -boundaryHeight && center < getHeight() + boundaryHeight) {
                drawSeparatorLabel(canvas, topBoundaryText, center);
            }
        }
        if (!bottomBoundaryText.isEmpty() && continuousLayout.size() > 0) {
            float center = continuousLayout.documentHeight() - boundaryHeight / 2f - documentScroll;
            if (center > -boundaryHeight && center < getHeight() + boundaryHeight) {
                drawSeparatorLabel(canvas, bottomBoundaryText, center);
            }
        }
    }

    private void drawSeparatorLabel(Canvas canvas, String text, float centerY) {
        float inset = Ui.dp(getContext(), 22);
        canvas.drawLine(inset, centerY - Ui.dp(getContext(), 17),
                getWidth() - inset, centerY - Ui.dp(getContext(), 17), separatorLinePaint);
        String fitted = text == null ? "" : text;
        float maximum = Math.max(1f, getWidth() - Ui.dp(getContext(), 32));
        while (fitted.length() > 1 && separatorPaint.measureText(fitted) > maximum) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        if (!fitted.equals(text)) fitted = fitted.trim() + "…";
        canvas.drawText(fitted, getWidth() / 2f,
                centerY + Ui.dp(getContext(), 7), separatorPaint);
    }

    private void notifyContinuousBoundaries() {
        if (listener == null || !continuous || continuousLayout.size() == 0) return;
        float threshold = Math.max(getHeight() * 1.5f, Ui.dp(getContext(), 480));
        float maximum = continuousLayout.maximumScroll(getHeight());
        if (documentScroll <= threshold && !topApproachSent) {
            topApproachSent = true;
            listener.onContinuousBoundaryApproached(-1);
        } else if (documentScroll > threshold * 1.5f) {
            topApproachSent = false;
        }
        if (maximum - documentScroll <= threshold && !bottomApproachSent) {
            bottomApproachSent = true;
            listener.onContinuousBoundaryApproached(1);
        } else if (maximum - documentScroll > threshold * 1.5f) {
            bottomApproachSent = false;
        }
    }

    private void showGlobalPage(int targetPage, float restoreRatio) {
        page = clamp(targetPage, 0, pages.size() - 1);
        pageRatio = clamp(restoreRatio, 0f, 1f);
        scroller.forceFinished(true);
        documentScroll = clampScroll(continuousLayout.positionFor(page, pageRatio));
        updateContinuousPosition();
        invalidate();
    }

    private void showContinuousIssueEnd(int issue) {
        if (issue < 0 || issue >= pageMap.issueCount()) return;
        int end = pageMap.end(issue);
        float bottom = continuousLayout.top(end) + continuousLayout.height(end) + pageGap;
        scroller.forceFinished(true);
        documentScroll = clampScroll(Math.max(
                continuousLayout.top(pageMap.start(issue)),
                bottom - Math.max(1, getHeight())));
        updateContinuousPosition();
        invalidate();
    }

    private void notifyPosition() {
        if (listener == null || pages.isEmpty()) return;
        listener.onReaderPositionChanged(
                continuous ? continuousDocumentKey() : "", page(), pageRatio);
    }

    private void notifyZoom() {
        if (listener != null) listener.onReaderZoomChanged(zoomMode(), zoom());
    }

    private final class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            scroller.forceFinished(true);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent first, MotionEvent current, float distanceX, float distanceY) {
            if (zoomGestureGate.isScaling()) return true;
            if (continuous) {
                documentScroll = clampScroll(documentScroll + distanceY);
                continuousPanX -= distanceX;
                clampContinuousPan();
                updateContinuousPosition();
            } else {
                singleX -= distanceX;
                singleY -= distanceY;
                clampSingleOffsets();
                pageRatio = currentSingleRatio();
                notifyPosition();
            }
            invalidate();
            return true;
        }

        @Override
        public boolean onFling(MotionEvent first, MotionEvent last, float velocityX, float velocityY) {
            if (zoomGestureGate.hasConsumedScale()) return true;
            if (!continuous && Math.abs(velocityX) > Ui.dp(getContext(), 700) &&
                    Math.abs(velocityX) > Math.abs(velocityY) * 1.35f &&
                    singleScale <= fitWidthScale() * 1.08f) {
                int physical = velocityX < 0 ? 1 : -1;
                int logical = rightToLeft ? -physical : physical;
                if (listener != null) listener.onNavigateRequested(logical);
                return true;
            }

            if (continuous) {
                int maximum = Math.round(continuousLayout.maximumScroll(getHeight()));
                scroller.fling(0, Math.round(documentScroll), 0, Math.round(-velocityY),
                        0, 0, 0, maximum);
            } else {
                int maximumX = Math.round(Math.max(
                        0f, pagedDisplayWidth(singleScale) - getWidth()));
                int maximumY = Math.round(Math.max(
                        0f, pagedDisplayHeight(singleScale) - getHeight()));
                scroller.fling(Math.round(-singleX), Math.round(-singleY),
                        Math.round(-velocityX), Math.round(-velocityY),
                        0, maximumX, 0, maximumY);
            }
            postInvalidateOnAnimation();
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent event) {
            performClick();
            if (!pages.isEmpty()) {
                int hitPage = continuous
                        ? continuousLayout.pageAt(documentScroll + event.getY()) : page;
                TileRenderer hitRenderer = continuous ? rendererForPage(hitPage) : renderer;
                if (hitRenderer != null && hitRenderer.isUnavailable()) {
                    float scale = continuous ? contentWidth() * continuousZoom / 1000f : singleScale;
                    float left = continuous
                            ? (getWidth() - contentWidth() * continuousZoom) / 2f + continuousPanX
                            : singleX;
                    float top = continuous ? continuousLayout.top(hitPage) - documentScroll : singleY;
                    if (TileRenderer.hitsNoticeRetry(
                            (event.getX() - left) / scale, (event.getY() - top) / scale)) {
                        if (listener != null) listener.onUnavailableRetry(hitRenderer.documentKey());
                        return true;
                    }
                }
            }
            if (continuous && continuousLayout.size() > 0) {
                float documentY = documentScroll + event.getY();
                if (topBoundaryRetry && documentY < continuousLayout.top(0)) {
                    if (listener != null) listener.onContinuousBoundaryRetry(-1);
                    return true;
                }
                if (bottomBoundaryRetry &&
                        documentY > continuousLayout.documentHeight() - boundaryHeight) {
                    if (listener != null) listener.onContinuousBoundaryRetry(1);
                    return true;
                }
            }
            if (!continuous && tapZones && getWidth() > 0) {
                float fraction = event.getX() / getWidth();
                if (fraction < 0.24f || fraction > 0.76f) {
                    int physical = fraction > 0.5f ? 1 : -1;
                    int logical = rightToLeft ? -physical : physical;
                    if (listener != null) listener.onNavigateRequested(logical);
                    return true;
                }
            }
            if (listener != null) listener.onChromeToggleRequested();
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent event) {
            if (zoomGestureGate.isLocked()) return true;
            if (continuous) {
                float target = continuousZoom > 1.08f ? 1f : 2f;
                applyContinuousZoom(target, event.getX(), event.getY());
            } else if (MANUAL.equals(zoomMode) && zoom > 1.08f) {
                fitWidth();
            } else {
                applyPagedZoom(2.25f, event.getX(), event.getY());
            }
            return true;
        }
    }

    private final class Scaling extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            zoomGestureGate.begin(detector.getCurrentSpan());
            pinchStartZoom = continuous
                    ? continuousZoom
                    : (MANUAL.equals(zoomMode)
                            ? zoom
                            : singleScale / Math.max(0.0001f, fitWidthScale()));
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            float gestureScale = zoomGestureGate.scaleFor(detector.getCurrentSpan());
            if (!zoomGestureGate.isScaling()) return true;
            if (continuous) {
                applyContinuousZoom(
                        pinchStartZoom * gestureScale, detector.getFocusX(), detector.getFocusY());
            } else {
                applyPagedZoom(
                        pinchStartZoom * gestureScale, detector.getFocusX(), detector.getFocusY());
            }
            return true;
        }

        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            zoomGestureGate.end();
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}

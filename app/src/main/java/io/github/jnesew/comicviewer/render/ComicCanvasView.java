package io.github.jnesew.comicviewer.render;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
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
import io.github.jnesew.comicviewer.util.Ui;
import io.github.jnesew.comicviewer.util.ZoomGestureGate;

import java.util.List;
import java.util.Collections;

/** Touch-first page and continuous canvas with tile-backed rendering. */
public final class ComicCanvasView extends View {
    public interface Listener {
        void onReaderPositionChanged(int page, float pageRatio);
        void onReaderZoomChanged(String mode, float zoom);
        void onNavigateRequested(int delta);
        void onChromeToggleRequested();
    }

    public static final String FIT_WIDTH = "fit_width";
    public static final String FIT_PAGE = "fit_page";
    public static final String MANUAL = "manual";
    public static final String CONTINUOUS = "continuous";

    private final GestureDetector gestureDetector;
    private final ScaleGestureDetector scaleDetector;
    private final OverScroller scroller;
    private final ZoomGestureGate zoomGestureGate = new ZoomGestureGate();
    private final PageLayoutEngine continuousLayout = new PageLayoutEngine();
    private final RectF destination = new RectF();
    private final RectF clip = new RectF();

    private TileRenderer renderer;
    private List<PageInfo> pages = Collections.emptyList();
    private Listener listener;
    private int page;
    private float pageRatio;
    private boolean continuous;
    private boolean tapZones = true;
    private boolean rightToLeft;
    private int canvasColor = Color.BLACK;

    // Single-page transform. zoom is a multiplier relative to fit-width.
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
        this.continuous = "continuous".equals(progress.readingMode);
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
        scroller.forceFinished(true);
        invalidate();
    }

    /** Relayout around the current reading anchor after background page dimensions arrive. */
    public void onPageInfoChanged() {
        if (pages.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;
        int anchorPage = page;
        float anchorRatio = pageRatio;
        if (continuous) relayoutContinuousAround(anchorPage, anchorRatio);
        else configureSingle(anchorRatio);
        invalidate();
    }

    public int page() {
        return page;
    }

    public float pageRatio() {
        return pageRatio;
    }

    public boolean isContinuous() {
        return continuous;
    }

    public boolean isAtDocumentEnd() {
        if (pages.isEmpty()) return false;
        if (!continuous) return page >= pages.size() - 1;
        float maximum = Math.max(
                0f, continuousLayout.documentHeight() - Math.max(1, getHeight()));
        return documentScroll >= maximum - 1f;
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
    }

    public void setCanvasColor(int color) {
        canvasColor = color;
        invalidate();
    }

    public void setReadingMode(boolean useContinuous) {
        if (continuous == useContinuous || pages.isEmpty()) return;
        int anchorPage = page;
        float anchorRatio = pageRatio;
        continuous = useContinuous;
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
        page = clamp(targetPage, 0, pages.size() - 1);
        pageRatio = clamp(restoreRatio, 0f, 1f);
        scroller.forceFinished(true);
        if (continuous) {
            documentScroll = clampScroll(continuousLayout.positionFor(page, pageRatio));
        } else {
            configureSingle(pageRatio);
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
            configureSingle(pageRatio);
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
        configureSingle(0f);
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
        centerSingleOnRatio(pageRatio);
        notifyZoom();
        notifyPosition();
        invalidate();
    }

    public void multiplyZoom(float factor) {
        if (pages.isEmpty()) return;
        if (continuous) {
            applyContinuousZoom(continuousZoom * factor, getWidth() / 2f, getHeight() / 2f);
        } else {
            applySingleZoom(zoom * factor, getWidth() / 2f, getHeight() / 2f);
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
        else configureSingle(anchorRatio);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(canvasColor);
        if (renderer == null || pages.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;

        if (continuous) drawContinuous(canvas);
        else drawSingle(canvas);
    }

    private void drawSingle(Canvas canvas) {
        PageInfo info = pages.get(page);
        destination.set(
                singleX,
                singleY,
                singleX + info.width * singleScale,
                singleY + info.height * singleScale);
        clip.set(0f, 0f, getWidth(), getHeight());
        renderer.drawPage(canvas, page, destination, clip);
    }

    private void drawContinuous(Canvas canvas) {
        if (continuousLayout.size() == 0) return;
        // A rendered PDF tile intersecting the viewport already extends to its grid edge.
        // Extra high-resolution PDF prefetch can evict visible tiles while zooming.
        float prefetch = renderer.usesRenderedTiles() ? 0f : getHeight() * 0.55f;
        int first = continuousLayout.pageAt(Math.max(0f, documentScroll - prefetch));
        int last = continuousLayout.pageAt(Math.min(
                continuousLayout.documentHeight(), documentScroll + getHeight() + prefetch));
        float pageWidth = contentWidth() * continuousZoom;
        float x = (getWidth() - pageWidth) / 2f + continuousPanX;
        clip.set(0f, -prefetch, getWidth(), getHeight() + prefetch);
        for (int index = first; index <= last; index++) {
            float top = continuousLayout.top(index) - documentScroll;
            destination.set(x, top, x + pageWidth, top + continuousLayout.height(index));
            renderer.drawPage(canvas, index, destination, clip);
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
            continuousLayout.calculate(pages, contentWidth(), continuousZoom, pageGap);
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
            configureSingle(pendingRestore ? pendingRestoreRatio : pageRatio);
            pendingRestore = false;
        }
    }

    private void configureSingle(float restoreRatio) {
        if (pages.isEmpty() || getWidth() <= 0 || getHeight() <= 0) return;
        PageInfo info = pages.get(page);
        if (FIT_PAGE.equals(zoomMode)) {
            singleScale = Math.min((float) getWidth() / info.width, (float) getHeight() / info.height);
        } else if (MANUAL.equals(zoomMode)) {
            singleScale = fitWidthScale() * clamp(zoom, 0.1f, 12f);
        } else {
            singleScale = fitWidthScale();
        }
        centerSingleOnRatio(restoreRatio);
    }

    private void centerSingleOnRatio(float restoreRatio) {
        PageInfo info = pages.get(page);
        float width = info.width * singleScale;
        float height = info.height * singleScale;
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

    private void applySingleZoom(float requestedZoom, float focusX, float focusY) {
        if (pages.isEmpty()) return;
        float oldScale = singleScale;
        float sourceX = (focusX - singleX) / Math.max(0.0001f, oldScale);
        float sourceY = (focusY - singleY) / Math.max(0.0001f, oldScale);
        zoomMode = MANUAL;
        zoom = clamp(requestedZoom, 0.1f, 12f);
        singleScale = fitWidthScale() * zoom;
        singleX = focusX - sourceX * singleScale;
        singleY = focusY - sourceY * singleScale;
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
        continuousLayout.calculate(pages, contentWidth(), continuousZoom, pageGap);
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
        continuousLayout.calculate(pages, contentWidth(), continuousZoom, pageGap);
        documentScroll = clampScroll(continuousLayout.positionFor(anchorPage, anchorRatio));
        clampContinuousPan();
        updateContinuousPosition();
    }

    private void clampSingleOffsets() {
        if (pages.isEmpty()) return;
        PageInfo info = pages.get(page);
        float width = info.width * singleScale;
        float height = info.height * singleScale;
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
        return clamp(value, 0f,
                Math.max(0f, continuousLayout.documentHeight() - Math.max(1, getHeight())));
    }

    private float currentSingleRatio() {
        if (pages.isEmpty()) return 0f;
        float height = pages.get(page).height * singleScale;
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
    }

    private float fitWidthScale() {
        if (pages.isEmpty()) return 1f;
        return (float) Math.max(1, getWidth()) / pages.get(page).width;
    }

    private float contentWidth() {
        return Math.max(1f, getWidth() - pageGap * 2f);
    }

    private void notifyPosition() {
        if (listener != null && !pages.isEmpty()) listener.onReaderPositionChanged(page, pageRatio);
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
                int maximum = Math.round(Math.max(
                        0f, continuousLayout.documentHeight() - Math.max(1, getHeight())));
                scroller.fling(0, Math.round(documentScroll), 0, Math.round(-velocityY),
                        0, 0, 0, maximum);
            } else {
                PageInfo info = pages.get(page);
                int maximumX = Math.round(Math.max(0f, info.width * singleScale - getWidth()));
                int maximumY = Math.round(Math.max(0f, info.height * singleScale - getHeight()));
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
                applySingleZoom(2.25f, event.getX(), event.getY());
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
                applySingleZoom(
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

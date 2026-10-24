package io.github.jnesew.comicviewer.util;

import io.github.jnesew.comicviewer.model.PageInfo;

import java.util.List;

/** Pure layout math shared by the continuous renderer and JVM tests. */
public final class PageLayoutEngine {
    private double[] tops = new double[0];
    private double[] heights = new double[0];
    private double documentHeight;

    public void calculate(List<PageInfo> pages, float contentWidth, float zoom, float gap) {
        calculate(pages, contentWidth, zoom, gap, null, 0f);
    }

    public void calculate(
            List<PageInfo> pages,
            float contentWidth,
            float zoom,
            float gap,
            float[] extraBefore,
            float footer) {
        int count = pages.size();
        tops = new double[count];
        heights = new double[count];
        double cursor = gap;
        double targetWidth = (double) Math.max(1f, contentWidth) * Math.max(0.1f, zoom);
        for (int i = 0; i < count; i++) {
            if (extraBefore != null && i < extraBefore.length) {
                cursor += Math.max(0f, extraBefore[i]);
            }
            PageInfo page = pages.get(i);
            tops[i] = cursor;
            heights[i] = targetWidth * page.height / page.width;
            cursor += heights[i] + gap;
        }
        documentHeight = count == 0 ? 0f : cursor + Math.max(0f, footer);
    }

    public int size() {
        return tops.length;
    }

    public double top(int page) {
        return tops[page];
    }

    public float height(int page) {
        return (float) heights[page];
    }

    public double documentHeight() {
        return documentHeight;
    }

    /** Keep the final page reachable at the top, even when it is shorter than the viewport.
     * Continuous issue activation and subsequent buffering use that reading anchor. */
    public double maximumScroll(float viewportHeight) {
        if (tops.length == 0) return 0f;
        return Math.max(tops[tops.length - 1],
                Math.max(0f, documentHeight - Math.max(1f, viewportHeight)));
    }

    public int pageAt(double documentY) {
        if (tops.length == 0) return 0;
        int low = 0;
        int high = tops.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (tops[middle] <= documentY) low = middle + 1;
            else high = middle - 1;
        }
        return Math.max(0, Math.min(tops.length - 1, high));
    }

    public float pageRatio(int page, double documentY) {
        if (heights.length == 0) return 0f;
        int safePage = Math.max(0, Math.min(page, heights.length - 1));
        return (float) clamp((documentY - tops[safePage]) / Math.max(1d, heights[safePage]));
    }

    public double positionFor(int page, float ratio) {
        if (tops.length == 0) return 0f;
        int safePage = Math.max(0, Math.min(page, tops.length - 1));
        return tops[safePage] + clamp(ratio) * heights[safePage];
    }

    private static double clamp(double value) {
        return Math.max(0f, Math.min(1f, value));
    }
}

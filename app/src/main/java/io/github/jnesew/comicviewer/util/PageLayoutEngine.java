package io.github.jnesew.comicviewer.util;

import io.github.jnesew.comicviewer.model.PageInfo;

import java.util.List;

/** Pure layout math shared by the continuous renderer and JVM tests. */
public final class PageLayoutEngine {
    private float[] tops = new float[0];
    private float[] heights = new float[0];
    private float documentHeight;

    public void calculate(List<PageInfo> pages, float contentWidth, float zoom, float gap) {
        int count = pages.size();
        tops = new float[count];
        heights = new float[count];
        float cursor = gap;
        float targetWidth = Math.max(1f, contentWidth) * Math.max(0.1f, zoom);
        for (int i = 0; i < count; i++) {
            PageInfo page = pages.get(i);
            tops[i] = cursor;
            heights[i] = targetWidth * page.aspectHeight();
            cursor += heights[i] + gap;
        }
        documentHeight = count == 0 ? 0f : cursor;
    }

    public int size() {
        return tops.length;
    }

    public float top(int page) {
        return tops[page];
    }

    public float height(int page) {
        return heights[page];
    }

    public float documentHeight() {
        return documentHeight;
    }

    public int pageAt(float documentY) {
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

    public float pageRatio(int page, float documentY) {
        if (heights.length == 0) return 0f;
        int safePage = Math.max(0, Math.min(page, heights.length - 1));
        return clamp((documentY - tops[safePage]) / Math.max(1f, heights[safePage]));
    }

    public float positionFor(int page, float ratio) {
        if (tops.length == 0) return 0f;
        int safePage = Math.max(0, Math.min(page, tops.length - 1));
        return tops[safePage] + clamp(ratio) * heights[safePage];
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}

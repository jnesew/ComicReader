package io.github.jnesew.comicviewer.util;

import io.github.jnesew.comicviewer.model.PageInfo;

import java.util.List;

/** Pure page grouping for cover-aware two-page reading. */
public final class SpreadPageLayout {
    private int[] anchors = new int[0];
    private int[] ends = new int[0];

    public void calculate(List<PageInfo> pages) {
        int count = pages.size();
        anchors = new int[count];
        ends = new int[count];
        int page = 0;
        while (page < count) {
            int end = page;
            boolean cover = page == 0;
            if (!cover && !isLandscape(pages.get(page)) && page + 1 < count &&
                    !isLandscape(pages.get(page + 1))) {
                end = page + 1;
            }
            for (int member = page; member <= end; member++) {
                anchors[member] = page;
                ends[member] = end;
            }
            page = end + 1;
        }
    }

    public int size() {
        return anchors.length;
    }

    public int anchorFor(int page) {
        if (anchors.length == 0) return 0;
        return anchors[clampPage(page)];
    }

    public int endFor(int page) {
        if (ends.length == 0) return 0;
        return ends[clampPage(page)];
    }

    public int adjacentAnchor(int page, int delta) {
        if (anchors.length == 0 || delta == 0) return anchorFor(page);
        int anchor = anchorFor(page);
        int steps = Math.abs(delta);
        for (int step = 0; step < steps; step++) {
            if (delta > 0) {
                int end = endFor(anchor);
                if (end >= anchors.length - 1) break;
                anchor = anchors[end + 1];
            } else {
                if (anchor <= 0) break;
                anchor = anchors[anchor - 1];
            }
        }
        return anchor;
    }

    public int leftPage(int page, boolean rightToLeft) {
        int anchor = anchorFor(page);
        int end = endFor(page);
        return rightToLeft && end > anchor ? end : anchor;
    }

    public int rightPage(int page, boolean rightToLeft) {
        int anchor = anchorFor(page);
        int end = endFor(page);
        return rightToLeft && end > anchor ? anchor : end;
    }

    private int clampPage(int page) {
        return Math.max(0, Math.min(anchors.length - 1, page));
    }

    private static boolean isLandscape(PageInfo page) {
        return page.width > page.height;
    }
}

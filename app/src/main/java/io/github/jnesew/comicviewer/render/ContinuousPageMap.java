package io.github.jnesew.comicviewer.render;

import java.util.ArrayList;
import java.util.List;

/** Immutable issue/local/global mapping. Contains no rendering or Android state. */
public final class ContinuousPageMap {
    public record Issue(String key, int pageCount) {
        public Issue {
            if (key == null || pageCount <= 0) throw new IllegalArgumentException("Empty issue");
        }
    }
    public record Anchor(String key, int page, float ratio) { }
    public record Position(int page, float ratio) { }
    private final List<Issue> issues;
    private final int[] starts;
    private final int[] ends;
    private final int[] issueForPage;
    private final int[] localPage;

    public ContinuousPageMap(List<Issue> issues) {
        this.issues = java.util.Collections.unmodifiableList(new ArrayList<>(issues));
        starts = new int[issues.size()];
        ends = new int[issues.size()];
        int count = 0;
        for (int i = 0; i < issues.size(); i++) {
            starts[i] = count;
            count = Math.addExact(count, issues.get(i).pageCount());
            ends[i] = count - 1;
        }
        issueForPage = new int[count];
        localPage = new int[count];
        for (int i = 0; i < issues.size(); i++) {
            for (int page = starts[i]; page <= ends[i]; page++) {
                issueForPage[page] = i;
                localPage[page] = page - starts[i];
            }
        }
    }
    public int size() { return localPage.length; }
    public int issueCount() { return issues.size(); }
    public int start(int issue) { return starts[issue]; }
    public int end(int issue) { return ends[issue]; }
    public int issueFor(int page) {
        return page < 0 || page >= size() ? -1 : issueForPage[page];
    }
    public int localPageFor(int page) {
        return issueFor(page) < 0 ? Math.max(0, Math.min(Math.max(0, size() - 1), page))
                : localPage[page];
    }
    public int globalPageFor(String key, int page, int fallbackGlobalPage) {
        if (issues.isEmpty()) return 0;
        int issue = -1;
        for (int i = 0; i < issues.size(); i++) {
            if (issues.get(i).key().equals(key)) { issue = i; break; }
        }
        if (issue < 0) issue = Math.max(0, issueFor(fallbackGlobalPage));
        return starts[issue] + Math.max(0, Math.min(ends[issue] - starts[issue], page));
    }
    public Position restore(Anchor anchor, int fallbackGlobalPage) {
        return new Position(globalPageFor(anchor.key(), anchor.page(), fallbackGlobalPage),
                Math.max(0f, Math.min(1f, anchor.ratio())));
    }
}

package com.localtools.comicviewer.util;

import com.localtools.comicviewer.data.LibraryDatabase;
import com.localtools.comicviewer.model.ReadingProgress;
import com.localtools.comicviewer.model.SeriesGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure grouping/order logic shared by the Android library UI and unit tests. */
public final class SeriesOrganizer {
    private SeriesOrganizer() {
    }

    public static List<SeriesGroup> group(
            List<ReadingProgress> allTitles,
            List<ReadingProgress> matchingTitles,
            String sort) {
        Set<String> matchingUris = new HashSet<>();
        for (ReadingProgress item : matchingTitles) matchingUris.add(item.uri);

        LinkedHashMap<String, ArrayList<ReadingProgress>> grouped = new LinkedHashMap<>();
        for (ReadingProgress item : allTitles) {
            String key = item.seriesId > 0L ? "series:" + item.seriesId : "title:" + item.uri;
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }

        ArrayList<SeriesGroup> result = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            ArrayList<ReadingProgress> issues = entry.getValue();
            boolean matches = false;
            for (ReadingProgress issue : issues) {
                if (matchingUris.contains(issue.uri)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) continue;
            issues.sort(ISSUE_ORDER);
            ReadingProgress first = issues.get(0);
            long id = first.seriesId;
            String title = id > 0L && !first.seriesTitle.trim().isEmpty()
                    ? first.seriesTitle : first.title;
            int totalPercent = 0;
            long lastOpened = 0L;
            long addedAt = 0L;
            for (ReadingProgress issue : issues) {
                totalPercent += issue.percent();
                lastOpened = Math.max(lastOpened, issue.lastOpened);
                addedAt = Math.max(addedAt, issue.addedAt);
            }
            result.add(new SeriesGroup(
                    id, entry.getKey(), title, new ArrayList<>(issues), first,
                    Math.round((float) totalPercent / issues.size()), lastOpened, addedAt));
        }
        result.sort(groupOrder(sort));
        return result;
    }

    public static List<ReadingProgress> sortIssues(List<ReadingProgress> issues) {
        ArrayList<ReadingProgress> result = new ArrayList<>(issues);
        result.sort(ISSUE_ORDER);
        return result;
    }

    private static final Comparator<ReadingProgress> ISSUE_ORDER = (left, right) -> {
        boolean leftNumbered = !left.seriesNumber.trim().isEmpty();
        boolean rightNumbered = !right.seriesNumber.trim().isEmpty();
        if (leftNumbered != rightNumbered) return leftNumbered ? -1 : 1;
        if (leftNumbered) {
            int byNumber = NaturalOrder.INSTANCE.compare(left.seriesNumber, right.seriesNumber);
            if (byNumber != 0) return byNumber;
        }
        int byTitle = NaturalOrder.INSTANCE.compare(left.title, right.title);
        if (byTitle != 0) return byTitle;
        return left.uri.compareTo(right.uri);
    };

    private static Comparator<SeriesGroup> groupOrder(String sort) {
        Comparator<SeriesGroup> title = Comparator.comparing(
                group -> group.title.toLowerCase(Locale.ROOT), NaturalOrder.INSTANCE);
        return switch (sort == null ? LibraryDatabase.SORT_RECENT : sort) {
            case LibraryDatabase.SORT_ADDED -> Comparator
                    .comparingLong((SeriesGroup group) -> group.addedAt).reversed()
                    .thenComparing(title);
            case LibraryDatabase.SORT_TITLE_ASC -> title;
            case LibraryDatabase.SORT_TITLE_DESC -> title.reversed();
            case LibraryDatabase.SORT_PROGRESS -> Comparator
                    .comparingInt((SeriesGroup group) -> group.percent).reversed()
                    .thenComparing(
                            Comparator.comparingLong((SeriesGroup group) -> group.lastOpened)
                                    .reversed())
                    .thenComparing(title);
            default -> Comparator
                    .comparingInt((SeriesGroup group) -> group.lastOpened <= 0L ? 1 : 0)
                    .thenComparing(
                            Comparator.comparingLong((SeriesGroup group) -> group.lastOpened)
                                    .reversed())
                    .thenComparing(
                            Comparator.comparingLong((SeriesGroup group) -> group.addedAt)
                                    .reversed())
                    .thenComparing(title);
        };
    }
}

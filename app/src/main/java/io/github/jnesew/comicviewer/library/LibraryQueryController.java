package io.github.jnesew.comicviewer.library;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.model.SeriesGroup;
import io.github.jnesew.comicviewer.util.SeriesOrganizer;

/** Library filtering and series grouping, independent of view construction. */
public final class LibraryQueryController {
    public record Snapshot(List<ReadingProgress> rows, List<SeriesGroup> seriesRows) { }
    private final LibraryDatabase database;
    public LibraryQueryController(LibraryDatabase database) { this.database = database; }
    public Snapshot query(String query, String sort, String filter, long selectedSeriesId,
            boolean seriesMode) {
        List<ReadingProgress> rows;
        List<SeriesGroup> seriesRows;
        List<ReadingProgress> matching = database.library(query, sort, filter);
        if (selectedSeriesId > 0L) {
            ArrayList<ReadingProgress> selected = new ArrayList<>();
            for (ReadingProgress item : matching) {
                if (item.seriesId == selectedSeriesId) selected.add(item);
            }
            rows = SeriesOrganizer.sortIssues(selected);
            seriesRows = Collections.emptyList();
        } else if (seriesMode) {
            List<ReadingProgress> all = database.library(
                    "", sort, LibraryDatabase.FILTER_ALL);
            rows = Collections.emptyList();
            seriesRows = SeriesOrganizer.group(all, matching, sort);
        } else {
            rows = matching;
            seriesRows = Collections.emptyList();
        }
        return new Snapshot(rows, seriesRows);
    }
}

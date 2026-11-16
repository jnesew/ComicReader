package io.github.jnesew.comicviewer.data;

import android.app.Instrumentation;
import android.content.Context;
import io.github.jnesew.comicviewer.model.ComicMetadata;
import io.github.jnesew.comicviewer.model.PageInfo;
import java.util.List;
import java.util.Map;

/** Production SQLite metadata caching, migration, and source-reconciliation boundaries. */
final class PublicationMetadataTests {
    static void roundTrip(LibraryDatabase db) {
        db.ensureImported("comic", "Original", 100, 200);
        var saved = db.get("comic"); saved.page = 4; saved.pageCount = 12; saved.lastOpened = 100;
        db.saveReadingProgress(saved); db.toggleBookmark("comic", 4); db.toggleFavorite("comic");
        db.setReadStatus("comic", true);
        db.setComicMetadata("comic", "My title", LibraryDatabase.SERIES_MANUAL, "My series", "5");
        ComicMetadata metadata = new ComicMetadata(Map.of("Title", "The Glass Mountain", "Summary", "A summary 🦊",
                "Writer", "Mira Vale", "Penciller", "Other Artist", "Year", "2024", "Month", "2", "Day", "29",
                "Volume", "3", "Publisher", "Example Press", "LanguageISO", "fi"), List.of());
        db.storePublicationMetadata("comic", metadata);
        db.close(); db.getWritableDatabase();
        ComicMetadata restored = db.publicationMetadata("comic");
        check(restored.publicationFields().equals(metadata.publicationFields()), "Publication fields changed across restart");
        saved = db.get("comic");
        check(saved.page == 4 && saved.read && saved.favorite && db.isBookmarked("comic", 4), "Metadata overwrote reading state");
        check(saved.title.equals("My title") && saved.seriesTitle.equals("My series"), "Metadata overwrote explicit edits");
        db.setSourceAvailability("comic", false);
        db.markMetadataFailed("comic");
        check(db.publicationMetadata("comic").summary.equals("A summary 🦊"), "Failure erased saved metadata");
        db.forget("comic"); db.storePublicationMetadata("comic", metadata);
        check(db.publicationMetadata("comic").summary.isEmpty(), "Stale metadata resurrected a forgotten title");
    }

    static void reconciliation(LibraryDatabase db) {
        db.ensureImported("old", "Old", 100, 200);
        db.storePublicationMetadata("old", new ComicMetadata(Map.of("Summary", "Keep across a move"), List.of()));
        check(db.relinkCanonicalUri("old", "new"), "Relink failed");
        check(db.publicationMetadata("new").summary.equals("Keep across a move"), "Relink lost metadata");
        check(db.publicationMetadata("old").summary.isEmpty(), "Old key retained metadata");
        db.ensureImported("copy", "Copy", 100, 200); db.markManualSource("copy");
        db.mergeExactDuplicate("copy", "new", "sample", "full");
        check(db.publicationMetadata("copy").summary.equals("Keep across a move"), "Duplicate merge lost metadata");
        check(db.publicationMetadata("new").summary.isEmpty(), "Duplicate metadata was orphaned");
    }

    static void upgradeTwelve(Instrumentation instrumentation) {
        Context context = instrumentation.getTargetContext(); String name = "publication-upgrade.sqlite3";
        context.deleteDatabase(name);
        try {
            try (LibraryDatabase old = new LibraryDatabase(context, name)) {
                old.ensureImported("comic", "Existing", 100, 200);
                old.applyDetectedSeries("comic", "Series", "2", "", "");
                old.toggleFavorite("comic"); old.toggleBookmark("comic", 1); old.setReadStatus("comic", true);
                old.replacePageIndex("comic", List.of(new PageInfo("page", 100, 200)));
                old.getWritableDatabase().execSQL("DROP TABLE publication_metadata");
                old.getWritableDatabase().setVersion(12);
            }
            try (LibraryDatabase upgraded = new LibraryDatabase(context, name)) {
                var saved = upgraded.get("comic");
                check(upgraded.getReadableDatabase().getVersion() == 13, "Schema 12 did not upgrade");
                check(saved.favorite && saved.read && upgraded.isBookmarked("comic", 1) &&
                        upgraded.pageIndex("comic").size() == 1 && saved.seriesTitle.equals("Series"), "Migration lost library state");
                check(upgraded.metadataNeedingBackfill(10).size() == 1, "Existing metadata was not queued for refresh");
                check(upgraded.publicationMetadata("comic").summary.isEmpty(), "Migration invented publication metadata");
            }
        } finally { context.deleteDatabase(name); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

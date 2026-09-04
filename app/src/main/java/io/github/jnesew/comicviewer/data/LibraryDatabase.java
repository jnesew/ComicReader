package io.github.jnesew.comicviewer.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.model.ReadingDirection;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.InputLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.text.Normalizer;

/** Durable library, reading progress, cover metadata, and archive page index. */
public final class LibraryDatabase extends SQLiteOpenHelper {
    public static final String SORT_RECENT = "recent";
    public static final String SORT_ADDED = "added";
    public static final String SORT_TITLE_ASC = "title_asc";
    public static final String SORT_TITLE_DESC = "title_desc";
    public static final String SORT_PROGRESS = "progress";

    public static final String FILTER_ALL = "all";
    public static final String FILTER_NEW = "new";
    public static final String FILTER_READING = "reading";
    public static final String FILTER_COMPLETED = "completed";
    public static final String FILTER_FAVORITES = "favorites";

    public static final int COVER_PENDING = 0;
    public static final int COVER_READY = 1;
    public static final int COVER_FAILED = -1;

    public static final int SERIES_AUTOMATIC = 0;
    public static final int SERIES_MANUAL = 1;
    public static final int SERIES_STANDALONE = 2;

    public static final int METADATA_PENDING = 0;
    public static final int METADATA_READY = 1;
    public static final int METADATA_FAILED = -1;

    private static final String NAME = "comicviewer.sqlite3";
    private static final int VERSION = 6;
    private static final String PROGRESS_WITH_SERIES =
            "SELECT p.*, COALESCE(s.name, '') AS series_title FROM progress p " +
                    "LEFT JOIN series s ON s.id = p.series_id";

    public LibraryDatabase(Context context) {
        super(context, NAME, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE progress (" +
                "uri TEXT PRIMARY KEY," +
                "title TEXT NOT NULL," +
                "page INTEGER NOT NULL DEFAULT 0," +
                "page_count INTEGER NOT NULL DEFAULT 0," +
                "scroll_ratio REAL NOT NULL DEFAULT 0," +
                "zoom_mode TEXT NOT NULL DEFAULT 'fit_width'," +
                "zoom REAL NOT NULL DEFAULT 1," +
                "zoom_gestures_locked INTEGER NOT NULL DEFAULT 0," +
                "reading_mode TEXT NOT NULL DEFAULT 'single'," +
                "last_opened INTEGER NOT NULL DEFAULT 0," +
                "added_at INTEGER NOT NULL DEFAULT 0," +
                "cover_path TEXT NOT NULL DEFAULT ''," +
                "cover_state INTEGER NOT NULL DEFAULT 0," +
                "indexed_pages INTEGER NOT NULL DEFAULT 0," +
                "index_complete INTEGER NOT NULL DEFAULT 0," +
                "document_size INTEGER NOT NULL DEFAULT -1," +
                "document_modified INTEGER NOT NULL DEFAULT -1," +
                "manual_source INTEGER NOT NULL DEFAULT 0," +
                "sample_signature TEXT NOT NULL DEFAULT ''," +
                "content_fingerprint TEXT NOT NULL DEFAULT ''," +
                "favorite INTEGER NOT NULL DEFAULT 0," +
                "reading_direction TEXT NOT NULL DEFAULT 'auto'," +
                "available INTEGER NOT NULL DEFAULT 1," +
                "series_id INTEGER NOT NULL DEFAULT 0," +
                "series_number TEXT NOT NULL DEFAULT ''," +
                "series_override INTEGER NOT NULL DEFAULT 0," +
                "detected_series_key TEXT NOT NULL DEFAULT ''," +
                "detected_series_name TEXT NOT NULL DEFAULT ''," +
                "detected_series_number TEXT NOT NULL DEFAULT ''," +
                "metadata_state INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE bookmarks (" +
                "uri TEXT NOT NULL," +
                "page INTEGER NOT NULL," +
                "created INTEGER NOT NULL," +
                "PRIMARY KEY(uri, page))");
        createPageTable(db);
        createSeriesTables(db);
        createIndexes(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE progress ADD COLUMN added_at INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE progress ADD COLUMN cover_path TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE progress ADD COLUMN cover_state INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE progress ADD COLUMN indexed_pages INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE progress ADD COLUMN index_complete INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE progress ADD COLUMN document_size INTEGER NOT NULL DEFAULT -1");
            db.execSQL("ALTER TABLE progress ADD COLUMN document_modified INTEGER NOT NULL DEFAULT -1");
            db.execSQL("UPDATE progress SET added_at = CASE " +
                    "WHEN last_opened > 0 THEN last_opened ELSE ? END WHERE added_at = 0",
                    new Object[]{System.currentTimeMillis()});
            createPageTable(db);
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE progress ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE progress ADD COLUMN reading_direction TEXT NOT NULL DEFAULT 'auto'");
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE progress ADD COLUMN available INTEGER NOT NULL DEFAULT 1");
            db.execSQL("ALTER TABLE progress ADD COLUMN series_id INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE progress ADD COLUMN series_number TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE progress ADD COLUMN series_override INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE progress ADD COLUMN detected_series_key TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE progress ADD COLUMN detected_series_name TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE progress ADD COLUMN detected_series_number TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE progress ADD COLUMN metadata_state INTEGER NOT NULL DEFAULT 0");
            createSeriesTables(db);
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE progress ADD COLUMN manual_source " +
                    "INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE progress ADD COLUMN sample_signature " +
                    "TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE progress ADD COLUMN content_fingerprint " +
                    "TEXT NOT NULL DEFAULT ''");
            db.execSQL("UPDATE progress SET manual_source=1 WHERE uri NOT IN (" +
                    "SELECT DISTINCT canonical_uri FROM scanned_files)");
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE progress ADD COLUMN zoom_gestures_locked " +
                    "INTEGER NOT NULL DEFAULT 0");
        }
        createIndexes(db);
    }

    public ReadingProgress get(String uri) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                PROGRESS_WITH_SERIES + " WHERE p.uri=? LIMIT 1", new String[]{uri})) {
            if (!cursor.moveToFirst()) return new ReadingProgress();
            return fromCursor(cursor);
        }
    }

    public ReadingProgress ensureImported(
            String uri, String title, long documentSize, long documentModified) {
        String safeTitle = InputLimits.normalizeText(
                title, InputLimits.MAX_TITLE_CODE_POINTS);
        if (safeTitle.isEmpty()) safeTitle = "Comic";
        SQLiteDatabase db = getWritableDatabase();
        ReadingProgress existing = get(uri);
        long now = System.currentTimeMillis();
        if (existing.uri.isEmpty()) {
            ReadingProgress created = new ReadingProgress();
            created.uri = uri;
            created.title = safeTitle;
            created.addedAt = now;
            created.documentSize = documentSize;
            created.documentModified = documentModified;
            save(created);
            return created;
        }

        boolean changed = fingerprintChanged(existing, documentSize, documentModified);
        ContentValues update = new ContentValues();
        update.put("title", safeTitle);
        update.put("document_size", documentSize);
        update.put("document_modified", documentModified);
        update.put("available", 1);
        if (changed) {
            db.delete("archive_pages", "uri=?", new String[]{uri});
            update.put("indexed_pages", 0);
            update.put("index_complete", 0);
            update.put("cover_path", "");
            update.put("cover_state", COVER_PENDING);
            update.put("page_count", 0);
            update.put("metadata_state", METADATA_PENDING);
            update.put("detected_series_key", "");
            update.put("detected_series_name", "");
            update.put("detected_series_number", "");
            update.put("sample_signature", "");
            update.put("content_fingerprint", "");
            if (existing.seriesOverride == SERIES_AUTOMATIC) {
                update.put("series_id", 0);
                update.put("series_number", "");
                existing.seriesId = 0L;
                existing.seriesTitle = "";
                existing.seriesNumber = "";
            }
            existing.indexedPages = 0;
            existing.indexComplete = false;
            existing.coverPath = "";
            existing.coverState = COVER_PENDING;
            existing.pageCount = 0;
            existing.metadataState = METADATA_PENDING;
            existing.detectedSeriesKey = "";
            existing.detectedSeriesName = "";
            existing.detectedSeriesNumber = "";
            existing.sampleSignature = "";
            existing.contentFingerprint = "";
        }
        db.update("progress", update, "uri=?", new String[]{uri});
        existing.title = safeTitle;
        existing.documentSize = documentSize;
        existing.documentModified = documentModified;
        existing.available = true;
        return existing;
    }

    public List<ReadingProgress> library(String query, String sort, String filter) {
        ArrayList<String> clauses = new ArrayList<>();
        ArrayList<String> arguments = new ArrayList<>();
        String trimmed = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.isEmpty()) {
            clauses.add("(LOWER(p.title) LIKE ? OR LOWER(COALESCE(s.name, '')) LIKE ?)");
            arguments.add("%" + trimmed + "%");
            arguments.add("%" + trimmed + "%");
        }
        switch (filter == null ? FILTER_ALL : filter) {
            case FILTER_NEW -> clauses.add("p.last_opened <= 0");
            case FILTER_READING -> clauses.add(
                    "p.last_opened > 0 AND (p.page_count <= 0 OR p.page < p.page_count - 1)");
            case FILTER_COMPLETED -> clauses.add(
                    "p.last_opened > 0 AND p.page_count > 0 AND p.page >= p.page_count - 1");
            case FILTER_FAVORITES -> clauses.add("p.favorite = 1");
            default -> {
            }
        }

        String order = switch (sort == null ? SORT_RECENT : sort) {
            case SORT_ADDED -> "p.added_at DESC, p.title COLLATE NOCASE ASC";
            case SORT_TITLE_ASC -> "p.title COLLATE NOCASE ASC, p.added_at DESC";
            case SORT_TITLE_DESC -> "p.title COLLATE NOCASE DESC, p.added_at DESC";
            case SORT_PROGRESS -> "CASE WHEN p.page_count > 1 THEN " +
                    "CAST(p.page AS REAL) / (p.page_count - 1) ELSE 0 END DESC, p.last_opened DESC";
            default -> "CASE WHEN p.last_opened <= 0 THEN 1 ELSE 0 END, " +
                    "p.last_opened DESC, p.added_at DESC, p.title COLLATE NOCASE ASC";
        };
        String selection = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        String[] args = arguments.toArray(new String[0]);
        ArrayList<ReadingProgress> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                PROGRESS_WITH_SERIES + selection + " ORDER BY " + order, args)) {
            while (cursor.moveToNext()) result.add(fromCursor(cursor));
        }
        return result;
    }

    public List<ReadingProgress> seriesIssues(long seriesId) {
        ArrayList<ReadingProgress> result = new ArrayList<>();
        if (seriesId <= 0L) return result;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                PROGRESS_WITH_SERIES + " WHERE p.series_id=?",
                new String[]{Long.toString(seriesId)})) {
            while (cursor.moveToNext()) result.add(fromCursor(cursor));
        }
        return result;
    }

    public List<ReadingProgress> coversNeedingBackfill(int limit) {
        ArrayList<ReadingProgress> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                PROGRESS_WITH_SERIES + " WHERE p.cover_state=? ORDER BY p.added_at ASC LIMIT ?",
                new String[]{Integer.toString(COVER_PENDING), Integer.toString(Math.max(1, limit))})) {
            while (cursor.moveToNext()) result.add(fromCursor(cursor));
        }
        return result;
    }

    public List<ReadingProgress> metadataNeedingBackfill(int limit) {
        ArrayList<ReadingProgress> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                PROGRESS_WITH_SERIES + " WHERE p.metadata_state=? ORDER BY p.added_at ASC LIMIT ?",
                new String[]{Integer.toString(METADATA_PENDING),
                        Integer.toString(Math.max(1, limit))})) {
            while (cursor.moveToNext()) result.add(fromCursor(cursor));
        }
        return result;
    }

    public void save(ReadingProgress progress) {
        if (progress.addedAt <= 0L) progress.addedAt = System.currentTimeMillis();
        ContentValues values = new ContentValues();
        values.put("uri", progress.uri);
        values.put("title", InputLimits.normalizeText(
                progress.title, InputLimits.MAX_TITLE_CODE_POINTS));
        values.put("page", progress.page);
        values.put("page_count", progress.pageCount);
        values.put("scroll_ratio", progress.scrollRatio);
        values.put("zoom_mode", progress.zoomMode);
        values.put("zoom", progress.zoom);
        values.put("zoom_gestures_locked", progress.zoomGesturesLocked ? 1 : 0);
        values.put("reading_mode", progress.readingMode);
        values.put("last_opened", progress.lastOpened);
        values.put("added_at", progress.addedAt);
        values.put("cover_path", progress.coverPath);
        values.put("cover_state", progress.coverState);
        values.put("indexed_pages", progress.indexedPages);
        values.put("index_complete", progress.indexComplete ? 1 : 0);
        values.put("document_size", progress.documentSize);
        values.put("document_modified", progress.documentModified);
        values.put("manual_source", progress.manualSource ? 1 : 0);
        values.put("sample_signature", progress.sampleSignature);
        values.put("content_fingerprint", progress.contentFingerprint);
        values.put("favorite", progress.favorite ? 1 : 0);
        values.put("reading_direction", ReadingDirection.normalize(progress.readingDirection));
        values.put("available", progress.available ? 1 : 0);
        values.put("series_id", progress.seriesId);
        values.put("series_number", InputLimits.normalizeText(
                progress.seriesNumber, InputLimits.MAX_ISSUE_CODE_POINTS));
        values.put("series_override", progress.seriesOverride);
        values.put("detected_series_key", progress.detectedSeriesKey);
        values.put("detected_series_name", InputLimits.normalizeText(
                progress.detectedSeriesName, InputLimits.MAX_SERIES_CODE_POINTS));
        values.put("detected_series_number", InputLimits.normalizeText(
                progress.detectedSeriesNumber, InputLimits.MAX_ISSUE_CODE_POINTS));
        values.put("metadata_state", progress.metadataState);
        getWritableDatabase().insertWithOnConflict(
                "progress", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Update reader state without overwriting cover/index metadata written by background work. */
    public void saveReadingProgress(ReadingProgress progress) {
        ContentValues values = new ContentValues();
        values.put("title", InputLimits.normalizeText(
                progress.title, InputLimits.MAX_TITLE_CODE_POINTS));
        values.put("page", progress.page);
        values.put("page_count", progress.pageCount);
        values.put("scroll_ratio", progress.scrollRatio);
        values.put("zoom_mode", progress.zoomMode);
        values.put("zoom", progress.zoom);
        values.put("zoom_gestures_locked", progress.zoomGesturesLocked ? 1 : 0);
        values.put("reading_mode", progress.readingMode);
        values.put("last_opened", progress.lastOpened);
        int updated = getWritableDatabase().update(
                "progress", values, "uri=?", new String[]{progress.uri});
        if (updated == 0) save(progress);
    }

    public void updateArchiveState(
            String uri, int pageCount, int indexedPages, boolean complete,
            long documentSize, long documentModified) {
        ContentValues values = new ContentValues();
        values.put("page_count", Math.max(0, pageCount));
        values.put("indexed_pages", Math.max(0, indexedPages));
        values.put("index_complete", complete ? 1 : 0);
        values.put("document_size", documentSize);
        values.put("document_modified", documentModified);
        getWritableDatabase().update("progress", values, "uri=?", new String[]{uri});
    }

    public void replacePageIndex(String uri, List<PageInfo> pages) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("archive_pages", "uri=?", new String[]{uri});
            for (int index = 0; index < pages.size(); index++) {
                PageInfo page = pages.get(index);
                ContentValues values = new ContentValues();
                values.put("uri", uri);
                values.put("position", index);
                values.put("entry_name", page.name);
                values.put("width", page.width);
                values.put("height", page.height);
                db.insertOrThrow("archive_pages", null, values);
            }
            ContentValues progress = new ContentValues();
            progress.put("page_count", pages.size());
            progress.put("indexed_pages", pages.size());
            progress.put("index_complete", 1);
            db.update("progress", progress, "uri=?", new String[]{uri});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<PageInfo> pageIndex(String uri) {
        ArrayList<PageInfo> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "archive_pages", new String[]{"entry_name", "width", "height"},
                "uri=?", new String[]{uri}, null, null, "position ASC")) {
            while (cursor.moveToNext()) {
                result.add(new PageInfo(cursor.getString(0), cursor.getInt(1), cursor.getInt(2)));
            }
        }
        return result;
    }

    public void setCover(String uri, String path, int state) {
        ContentValues values = new ContentValues();
        values.put("cover_path", path == null ? "" : path);
        values.put("cover_state", state);
        getWritableDatabase().update("progress", values, "uri=?", new String[]{uri});
    }

    public void updateTitle(String uri, String title) {
        String safeTitle = InputLimits.normalizeText(
                title, InputLimits.MAX_TITLE_CODE_POINTS);
        if (safeTitle.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("title", safeTitle);
        getWritableDatabase().update("progress", values, "uri=?", new String[]{uri});
    }

    /** Apply embedded metadata first, then the containing source folder when metadata is absent. */
    public void applyDetectedSeries(
            String uri,
            String embeddedName,
            String embeddedNumber,
            String folderKey,
            String folderName) {
        String metadataName = InputLimits.normalizeText(
                embeddedName, InputLimits.MAX_SERIES_CODE_POINTS);
        String metadataNumber = InputLimits.normalizeText(
                embeddedNumber, InputLimits.MAX_ISSUE_CODE_POINTS);
        String detectedKey;
        String detectedName;
        String detectedNumber;
        String origin;
        if (!metadataName.isEmpty()) {
            detectedKey = "metadata:" + normalizeSeriesName(metadataName);
            detectedName = metadataName;
            detectedNumber = metadataNumber;
            origin = "metadata";
        } else if (!safeStableKey(folderKey).isEmpty() &&
                !InputLimits.normalizeText(
                        folderName, InputLimits.MAX_SERIES_CODE_POINTS).isEmpty()) {
            detectedKey = "folder:" + safeStableKey(folderKey);
            detectedName = InputLimits.normalizeText(
                    folderName, InputLimits.MAX_SERIES_CODE_POINTS);
            detectedNumber = "";
            origin = "folder";
        } else {
            detectedKey = "";
            detectedName = "";
            detectedNumber = "";
            origin = "";
        }

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ReadingProgress current = get(uri);
            if (current.uri.isEmpty()) return;
            ContentValues values = new ContentValues();
            values.put("detected_series_key", detectedKey);
            values.put("detected_series_name", detectedName);
            values.put("detected_series_number", detectedNumber);
            values.put("metadata_state", METADATA_READY);
            if (current.seriesOverride == SERIES_AUTOMATIC) {
                long seriesId = detectedKey.isEmpty()
                        ? 0L : ensureSeries(db, detectedKey, detectedName, origin);
                values.put("series_id", seriesId);
                values.put("series_number", detectedNumber);
            }
            db.update("progress", values, "uri=?", new String[]{uri});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void setManualSeries(String uri, String name, String number) {
        String seriesName = InputLimits.normalizeText(
                name, InputLimits.MAX_SERIES_CODE_POINTS);
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        if (seriesName.isEmpty()) {
            values.put("series_id", 0);
            values.put("series_number", "");
            values.put("series_override", SERIES_STANDALONE);
        } else {
            long seriesId = findSeriesByName(db, seriesName);
            if (seriesId <= 0L) {
                seriesId = ensureSeries(
                        db, "manual:" + normalizeSeriesName(seriesName), seriesName, "manual");
            }
            values.put("series_id", seriesId);
            values.put("series_number", InputLimits.normalizeText(
                    number, InputLimits.MAX_ISSUE_CODE_POINTS));
            values.put("series_override", SERIES_MANUAL);
        }
        db.update("progress", values, "uri=?", new String[]{uri});
    }

    public void useAutomaticSeries(String uri) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ReadingProgress current = get(uri);
            if (current.uri.isEmpty()) return;
            long seriesId = current.detectedSeriesKey.isEmpty() ? 0L : ensureSeries(
                    db, current.detectedSeriesKey, current.detectedSeriesName,
                    current.detectedSeriesKey.startsWith("folder:") ? "folder" : "metadata");
            ContentValues values = new ContentValues();
            values.put("series_id", seriesId);
            values.put("series_number", current.detectedSeriesNumber);
            values.put("series_override", SERIES_AUTOMATIC);
            db.update("progress", values, "uri=?", new String[]{uri});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void markMetadataFailed(String uri) {
        ContentValues values = new ContentValues();
        values.put("metadata_state", METADATA_FAILED);
        getWritableDatabase().update("progress", values, "uri=?", new String[]{uri});
    }

    public boolean toggleFavorite(String uri) {
        SQLiteDatabase db = getWritableDatabase();
        boolean favorite;
        try (Cursor cursor = db.query(
                "progress", new String[]{"favorite"}, "uri=?", new String[]{uri},
                null, null, null, "1")) {
            if (!cursor.moveToFirst()) return false;
            favorite = cursor.getInt(0) == 0;
        }
        ContentValues values = new ContentValues();
        values.put("favorite", favorite ? 1 : 0);
        db.update("progress", values, "uri=?", new String[]{uri});
        return favorite;
    }

    public void setReadingDirection(String uri, String direction) {
        ContentValues values = new ContentValues();
        values.put("reading_direction", ReadingDirection.normalize(direction));
        getWritableDatabase().update("progress", values, "uri=?", new String[]{uri});
    }

    public void migrateLegacyRightToLeftTitles() {
        ContentValues values = new ContentValues();
        values.put("reading_direction", ReadingDirection.RIGHT_TO_LEFT);
        getWritableDatabase().update(
                "progress", values, "reading_direction=?",
                new String[]{ReadingDirection.AUTO});
    }

    public boolean toggleBookmark(String uri, int page) {
        SQLiteDatabase db = getWritableDatabase();
        boolean existing;
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM bookmarks WHERE uri=? AND page=?",
                new String[]{uri, Integer.toString(page)})) {
            existing = cursor.moveToFirst();
        }
        if (existing) {
            db.delete("bookmarks", "uri=? AND page=?", new String[]{uri, Integer.toString(page)});
            return false;
        }
        ContentValues values = new ContentValues();
        values.put("uri", uri);
        values.put("page", page);
        values.put("created", System.currentTimeMillis());
        db.insertOrThrow("bookmarks", null, values);
        return true;
    }

    public boolean isBookmarked(String uri, int page) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT 1 FROM bookmarks WHERE uri=? AND page=?",
                new String[]{uri, Integer.toString(page)})) {
            return cursor.moveToFirst();
        }
    }

    public List<Integer> bookmarks(String uri) {
        ArrayList<Integer> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                "bookmarks", new String[]{"page"}, "uri=?", new String[]{uri},
                null, null, "page ASC")) {
            while (cursor.moveToNext()) result.add(cursor.getInt(0));
        }
        return result;
    }

    /** Record that this canonical URI was explicitly granted through the document picker. */
    public void markManualSource(String uri) {
        ContentValues values = new ContentValues();
        values.put("manual_source", 1);
        values.put("available", 1);
        getWritableDatabase().update("progress", values, "uri=?", new String[]{uri});
    }

    public void setLibraryFingerprint(
            String uri, long documentSize, String sampleSignature, String contentFingerprint) {
        ContentValues values = new ContentValues();
        values.put("document_size", documentSize);
        values.put("sample_signature", clean(sampleSignature));
        values.put("content_fingerprint", clean(contentFingerprint));
        getWritableDatabase().update("progress", values, "uri=?", new String[]{uri});
    }

    /**
     * Return independently imported titles with the same reported size. Prefix and full hashes are
     * intentionally checked by the caller because reading provider content does not belong in SQL.
     */
    public List<ReadingProgress> manualDuplicateCandidates(
            long documentSize, String excludedCanonicalUri) {
        ArrayList<ReadingProgress> result = new ArrayList<>();
        if (documentSize < 0L) return result;
        String excluded = clean(excludedCanonicalUri);
        String selection = "p.manual_source=1 AND p.document_size=?";
        ArrayList<String> arguments = new ArrayList<>();
        arguments.add(Long.toString(documentSize));
        if (!excluded.isEmpty()) {
            selection += " AND p.uri<>?";
            arguments.add(excluded);
        }
        try (Cursor cursor = getReadableDatabase().rawQuery(
                PROGRESS_WITH_SERIES + " WHERE " + selection +
                        " ORDER BY p.favorite DESC, p.last_opened DESC, p.added_at ASC",
                arguments.toArray(new String[0]))) {
            while (cursor.moveToNext()) result.add(fromCursor(cursor));
        }
        return result;
    }

    public ScannedFile scannedFile(String sourceIdentity) {
        try (Cursor cursor = getReadableDatabase().query(
                "scanned_files", null, "source_identity=?", new String[]{sourceIdentity},
                null, null, null, "1")) {
            return cursor.moveToFirst() ? scannedFileFromCursor(cursor) : null;
        }
    }

    public List<ScannedFile> duplicateCandidates(
            String sourceIdentity, long documentSize, String sampleSignature) {
        ArrayList<ScannedFile> result = new ArrayList<>();
        if (documentSize < 0L || clean(sampleSignature).isEmpty()) return result;
        try (Cursor cursor = getReadableDatabase().query(
                "scanned_files", null,
                "source_identity<>? AND document_size=? AND sample_signature=?",
                new String[]{sourceIdentity, Long.toString(documentSize), sampleSignature},
                null, null, "last_seen DESC")) {
            while (cursor.moveToNext()) result.add(scannedFileFromCursor(cursor));
        }
        return result;
    }

    public void upsertScannedFile(ScannedFile source) {
        ContentValues values = new ContentValues();
        values.put("source_identity", source.sourceIdentity);
        values.put("tree_uri", source.treeUri);
        values.put("document_id", source.documentId);
        values.put("document_uri", source.documentUri);
        values.put("relative_path", source.relativePath);
        values.put("canonical_uri", source.canonicalUri);
        values.put("document_size", source.documentSize);
        values.put("document_modified", source.documentModified);
        values.put("sample_signature", source.sampleSignature);
        values.put("content_fingerprint", source.contentFingerprint);
        values.put("last_seen", source.lastSeen);
        values.put("available", source.available ? 1 : 0);
        getWritableDatabase().insertWithOnConflict(
                "scanned_files", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void setScannedFingerprint(String sourceIdentity, String fingerprint) {
        ContentValues values = new ContentValues();
        values.put("content_fingerprint", clean(fingerprint));
        getWritableDatabase().update(
                "scanned_files", values, "source_identity=?", new String[]{sourceIdentity});
    }

    public void touchScannedFile(
            String sourceIdentity,
            String documentUri,
            String relativePath,
            long documentSize,
            long documentModified,
            long seenAt) {
        ContentValues values = new ContentValues();
        values.put("document_uri", documentUri);
        values.put("relative_path", relativePath);
        values.put("document_size", documentSize);
        values.put("document_modified", documentModified);
        values.put("last_seen", seenAt);
        values.put("available", 1);
        getWritableDatabase().update(
                "scanned_files", values, "source_identity=?", new String[]{sourceIdentity});
    }

    /**
     * Fold a scanner-created title into an independently imported canonical title after the caller
     * has confirmed byte identity. The canonical title's explicit settings win; bookmarks and
     * favorites are combined, the newest reading position wins, and the best cached cover/index is
     * retained.
     */
    public DuplicateMergeResult mergeExactDuplicate(
            String canonicalUri,
            String duplicateUri,
            String sampleSignature,
            String contentFingerprint) {
        DuplicateMergeResult result = new DuplicateMergeResult();
        String canonicalKey = clean(canonicalUri);
        String duplicateKey = clean(duplicateUri);
        if (canonicalKey.isEmpty() || duplicateKey.isEmpty() ||
                canonicalKey.equals(duplicateKey)) return result;

        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ReadingProgress canonical = get(canonicalKey);
            ReadingProgress duplicate = get(duplicateKey);
            if (canonical.uri.isEmpty() || duplicate.uri.isEmpty() || !canonical.manualSource) {
                return result;
            }

            ContentValues merged = new ContentValues();
            boolean useDuplicateReading = duplicate.lastOpened > canonical.lastOpened;
            if (useDuplicateReading) {
                merged.put("page", duplicate.page);
                merged.put("scroll_ratio", duplicate.scrollRatio);
                merged.put("zoom_mode", duplicate.zoomMode);
                merged.put("zoom", duplicate.zoom);
                merged.put("reading_mode", duplicate.readingMode);
                merged.put("last_opened", duplicate.lastOpened);
            }
            merged.put("page_count", Math.max(canonical.pageCount, duplicate.pageCount));
            merged.put("favorite", canonical.favorite || duplicate.favorite ? 1 : 0);
            merged.put("added_at", earliestPositive(canonical.addedAt, duplicate.addedAt));
            merged.put("available", 1);
            merged.put("manual_source", 1);
            merged.put("sample_signature", clean(sampleSignature));
            merged.put("content_fingerprint", clean(contentFingerprint));
            if (canonical.documentSize < 0L && duplicate.documentSize >= 0L) {
                merged.put("document_size", duplicate.documentSize);
            }
            if (ReadingDirection.AUTO.equals(canonical.readingDirection) &&
                    !ReadingDirection.AUTO.equals(duplicate.readingDirection)) {
                merged.put("reading_direction",
                        ReadingDirection.normalize(duplicate.readingDirection));
            }

            boolean canonicalCover = canonical.coverState == COVER_READY &&
                    !clean(canonical.coverPath).isEmpty();
            boolean duplicateCover = duplicate.coverState == COVER_READY &&
                    !clean(duplicate.coverPath).isEmpty();
            if (!canonicalCover && duplicateCover) {
                merged.put("cover_path", duplicate.coverPath);
                merged.put("cover_state", COVER_READY);
                result.addObsoleteCover(canonical.coverPath, duplicate.coverPath);
            } else {
                result.addObsoleteCover(duplicate.coverPath, canonical.coverPath);
            }

            boolean useDuplicateIndex =
                    (!canonical.indexComplete && duplicate.indexComplete) ||
                            duplicate.indexedPages > canonical.indexedPages;
            if (useDuplicateIndex) {
                db.delete("archive_pages", "uri=?", new String[]{canonicalKey});
                ContentValues pages = new ContentValues();
                pages.put("uri", canonicalKey);
                db.update("archive_pages", pages, "uri=?", new String[]{duplicateKey});
                merged.put("indexed_pages", duplicate.indexedPages);
                merged.put("index_complete", duplicate.indexComplete ? 1 : 0);
                merged.put("page_count", duplicate.pageCount);
            } else {
                db.delete("archive_pages", "uri=?", new String[]{duplicateKey});
            }

            db.execSQL("INSERT OR IGNORE INTO bookmarks(uri, page, created) " +
                            "SELECT ?, page, created FROM bookmarks WHERE uri=?",
                    new Object[]{canonicalKey, duplicateKey});
            db.delete("bookmarks", "uri=?", new String[]{duplicateKey});

            db.update("progress", merged, "uri=?", new String[]{canonicalKey});
            ContentValues sources = new ContentValues();
            sources.put("canonical_uri", canonicalKey);
            db.update("scanned_files", sources, "canonical_uri=?", new String[]{duplicateKey});
            db.delete("progress", "uri=?", new String[]{duplicateKey});
            db.setTransactionSuccessful();
            result.merged = true;
        } finally {
            db.endTransaction();
        }
        return result;
    }

    public boolean relinkCanonicalUri(String oldUri, String newUri) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            boolean changed = migrateUri(db, oldUri, newUri);
            db.setTransactionSuccessful();
            return changed;
        } finally {
            db.endTransaction();
        }
    }

    /** Mark disappeared sources unavailable and move a canonical title to a surviving exact copy. */
    public void finishFolderScan(String treeUri, long scanStarted) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues missing = new ContentValues();
            missing.put("available", 0);
            db.update("scanned_files", missing, "tree_uri=? AND last_seen<?",
                    new String[]{treeUri, Long.toString(scanStarted)});
            ContentValues present = new ContentValues();
            present.put("available", 1);
            db.update("scanned_files", present, "tree_uri=? AND last_seen>=?",
                    new String[]{treeUri, Long.toString(scanStarted)});

            ArrayList<String[]> replacements = new ArrayList<>();
            String replacementSql = "SELECT sf.canonical_uri, MIN(sf.document_uri) " +
                    "FROM scanned_files sf WHERE sf.tree_uri=? AND sf.last_seen>=? " +
                    "AND sf.document_uri<>sf.canonical_uri AND NOT EXISTS (" +
                    "SELECT 1 FROM scanned_files direct WHERE " +
                    "direct.canonical_uri=sf.canonical_uri AND " +
                    "direct.document_uri=sf.canonical_uri AND direct.last_seen>=?) " +
                    "AND EXISTS (SELECT 1 FROM progress p WHERE " +
                    "p.uri=sf.canonical_uri AND p.manual_source=0) " +
                    "GROUP BY sf.canonical_uri";
            try (Cursor cursor = db.rawQuery(replacementSql, new String[]{
                    treeUri, Long.toString(scanStarted), Long.toString(scanStarted)})) {
                while (cursor.moveToNext()) {
                    replacements.add(new String[]{cursor.getString(0), cursor.getString(1)});
                }
            }
            for (String[] replacement : replacements) {
                migrateUri(db, replacement[0], replacement[1]);
            }

            db.execSQL("UPDATE progress SET available=0 WHERE manual_source=0 AND uri IN (" +
                    "SELECT DISTINCT canonical_uri FROM scanned_files WHERE tree_uri=?)",
                    new Object[]{treeUri});
            db.execSQL("UPDATE progress SET available=1 WHERE uri IN (" +
                    "SELECT DISTINCT canonical_uri FROM scanned_files " +
                    "WHERE tree_uri=? AND available=1)", new Object[]{treeUri});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void markFolderUnavailable(String treeUri) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues missing = new ContentValues();
            missing.put("available", 0);
            db.update("scanned_files", missing, "tree_uri=?", new String[]{treeUri});
            db.execSQL("UPDATE progress SET available=0 WHERE manual_source=0 AND uri IN (" +
                    "SELECT DISTINCT canonical_uri FROM scanned_files WHERE tree_uri=?) " +
                    "AND uri NOT IN (SELECT DISTINCT canonical_uri FROM scanned_files " +
                    "WHERE available=1)", new Object[]{treeUri});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public String forget(String uri) {
        SQLiteDatabase db = getWritableDatabase();
        String cover = get(uri).coverPath;
        db.beginTransaction();
        try {
            db.delete("bookmarks", "uri=?", new String[]{uri});
            db.delete("archive_pages", "uri=?", new String[]{uri});
            db.delete("scanned_files", "canonical_uri=? OR document_uri=?",
                    new String[]{uri, uri});
            db.delete("progress", "uri=?", new String[]{uri});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return cover;
    }

    public static final class ScannedFile {
        public String sourceIdentity = "";
        public String treeUri = "";
        public String documentId = "";
        public String documentUri = "";
        public String relativePath = "";
        public String canonicalUri = "";
        public long documentSize = -1L;
        public long documentModified = -1L;
        public String sampleSignature = "";
        public String contentFingerprint = "";
        public long lastSeen = 0L;
        public boolean available = true;
    }

    public static final class DuplicateMergeResult {
        public boolean merged;
        public final List<String> obsoleteCoverPaths = new ArrayList<>();

        private void addObsoleteCover(String candidate, String retained) {
            String path = clean(candidate);
            if (path.isEmpty() || path.equals(clean(retained)) ||
                    obsoleteCoverPaths.contains(path)) return;
            obsoleteCoverPaths.add(path);
        }
    }

    private static void createPageTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS archive_pages (" +
                "uri TEXT NOT NULL," +
                "position INTEGER NOT NULL," +
                "entry_name TEXT NOT NULL," +
                "width INTEGER NOT NULL," +
                "height INTEGER NOT NULL," +
                "PRIMARY KEY(uri, position))");
    }

    private static void createSeriesTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS series (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "stable_key TEXT NOT NULL UNIQUE," +
                "name TEXT NOT NULL," +
                "origin TEXT NOT NULL," +
                "updated_at INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS scanned_files (" +
                "source_identity TEXT PRIMARY KEY," +
                "tree_uri TEXT NOT NULL," +
                "document_id TEXT NOT NULL," +
                "document_uri TEXT NOT NULL," +
                "relative_path TEXT NOT NULL DEFAULT ''," +
                "canonical_uri TEXT NOT NULL," +
                "document_size INTEGER NOT NULL DEFAULT -1," +
                "document_modified INTEGER NOT NULL DEFAULT -1," +
                "sample_signature TEXT NOT NULL DEFAULT ''," +
                "content_fingerprint TEXT NOT NULL DEFAULT ''," +
                "last_seen INTEGER NOT NULL DEFAULT 0," +
                "available INTEGER NOT NULL DEFAULT 1," +
                "UNIQUE(tree_uri, document_id))");
    }

    private static void createIndexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX IF NOT EXISTS progress_last_opened " +
                "ON progress(last_opened DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS progress_added_at " +
                "ON progress(added_at DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS progress_title " +
                "ON progress(title COLLATE NOCASE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS progress_favorite " +
                "ON progress(favorite DESC, last_opened DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS progress_series " +
                "ON progress(series_id, series_number COLLATE NOCASE, title COLLATE NOCASE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS progress_metadata " +
                "ON progress(metadata_state, added_at)");
        db.execSQL("CREATE INDEX IF NOT EXISTS progress_manual_signature " +
                "ON progress(manual_source, document_size, sample_signature)");
        db.execSQL("CREATE INDEX IF NOT EXISTS archive_pages_uri " +
                "ON archive_pages(uri, position)");
        db.execSQL("CREATE INDEX IF NOT EXISTS scanned_files_tree_seen " +
                "ON scanned_files(tree_uri, last_seen, available)");
        db.execSQL("CREATE INDEX IF NOT EXISTS scanned_files_signature " +
                "ON scanned_files(document_size, sample_signature)");
        db.execSQL("CREATE INDEX IF NOT EXISTS scanned_files_canonical " +
                "ON scanned_files(canonical_uri, available)");
    }

    private static long ensureSeries(
            SQLiteDatabase db, String stableKey, String name, String origin) {
        String key = safeStableKey(stableKey);
        String title = InputLimits.normalizeText(name, InputLimits.MAX_SERIES_CODE_POINTS);
        if (key.isEmpty() || title.isEmpty()) return 0L;
        try (Cursor cursor = db.query(
                "series", new String[]{"id", "name"}, "stable_key=?", new String[]{key},
                null, null, null, "1")) {
            if (cursor.moveToFirst()) {
                long id = cursor.getLong(0);
                if (!title.equals(cursor.getString(1))) {
                    ContentValues update = new ContentValues();
                    update.put("name", title);
                    update.put("origin", clean(origin));
                    update.put("updated_at", System.currentTimeMillis());
                    db.update("series", update, "id=?", new String[]{Long.toString(id)});
                }
                return id;
            }
        }
        ContentValues values = new ContentValues();
        values.put("stable_key", key);
        values.put("name", title);
        values.put("origin", clean(origin));
        values.put("updated_at", System.currentTimeMillis());
        return db.insertOrThrow("series", null, values);
    }

    private static long findSeriesByName(SQLiteDatabase db, String name) {
        String wanted = normalizeSeriesName(name);
        try (Cursor cursor = db.query(
                "series", new String[]{"id", "name"}, null, null, null, null,
                "updated_at DESC")) {
            while (cursor.moveToNext()) {
                if (wanted.equals(normalizeSeriesName(cursor.getString(1)))) {
                    return cursor.getLong(0);
                }
            }
        }
        return 0L;
    }

    private static boolean migrateUri(SQLiteDatabase db, String oldUri, String newUri) {
        if (clean(oldUri).isEmpty() || clean(newUri).isEmpty() || oldUri.equals(newUri)) return false;
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM progress WHERE uri=?", new String[]{newUri})) {
            if (cursor.moveToFirst()) return false;
        }
        ContentValues progress = new ContentValues();
        progress.put("uri", newUri);
        db.update("progress", progress, "uri=?", new String[]{oldUri});
        ContentValues bookmarks = new ContentValues();
        bookmarks.put("uri", newUri);
        db.update("bookmarks", bookmarks, "uri=?", new String[]{oldUri});
        ContentValues pages = new ContentValues();
        pages.put("uri", newUri);
        db.update("archive_pages", pages, "uri=?", new String[]{oldUri});
        ContentValues sources = new ContentValues();
        sources.put("canonical_uri", newUri);
        db.update("scanned_files", sources, "canonical_uri=?", new String[]{oldUri});
        return true;
    }

    private static ScannedFile scannedFileFromCursor(Cursor cursor) {
        ScannedFile result = new ScannedFile();
        result.sourceIdentity = string(cursor, "source_identity");
        result.treeUri = string(cursor, "tree_uri");
        result.documentId = string(cursor, "document_id");
        result.documentUri = string(cursor, "document_uri");
        result.relativePath = string(cursor, "relative_path");
        result.canonicalUri = string(cursor, "canonical_uri");
        result.documentSize = longValue(cursor, "document_size");
        result.documentModified = longValue(cursor, "document_modified");
        result.sampleSignature = string(cursor, "sample_signature");
        result.contentFingerprint = string(cursor, "content_fingerprint");
        result.lastSeen = longValue(cursor, "last_seen");
        result.available = integer(cursor, "available") != 0;
        return result;
    }

    private static String normalizeSeriesName(String value) {
        String normalized = Normalizer.normalize(InputLimits.normalizeText(
                        value, InputLimits.MAX_SERIES_CODE_POINTS), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return normalized.replaceAll("\\s+", " ");
    }

    private static String safeStableKey(String value) {
        String key = clean(value);
        if (key.codePointCount(0, key.length()) > InputLimits.MAX_STABLE_KEY_CODE_POINTS ||
                key.indexOf('\0') >= 0) return "";
        return key;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean fingerprintChanged(
            ReadingProgress current, long documentSize, long documentModified) {
        boolean sizeChanged = current.documentSize >= 0L && documentSize >= 0L &&
                current.documentSize != documentSize;
        boolean modifiedChanged = current.documentModified >= 0L && documentModified >= 0L &&
                current.documentModified != documentModified;
        return sizeChanged || modifiedChanged;
    }

    private static long earliestPositive(long first, long second) {
        if (first <= 0L) return Math.max(0L, second);
        if (second <= 0L) return first;
        return Math.min(first, second);
    }

    private static ReadingProgress fromCursor(Cursor cursor) {
        ReadingProgress result = new ReadingProgress();
        result.uri = string(cursor, "uri");
        result.title = string(cursor, "title");
        result.page = integer(cursor, "page");
        result.pageCount = integer(cursor, "page_count");
        result.scrollRatio = real(cursor, "scroll_ratio");
        result.zoomMode = string(cursor, "zoom_mode");
        result.zoom = real(cursor, "zoom");
        result.zoomGesturesLocked = integer(cursor, "zoom_gestures_locked") != 0;
        result.readingMode = string(cursor, "reading_mode");
        result.lastOpened = longValue(cursor, "last_opened");
        result.addedAt = longValue(cursor, "added_at");
        result.coverPath = string(cursor, "cover_path");
        result.coverState = integer(cursor, "cover_state");
        result.indexedPages = integer(cursor, "indexed_pages");
        result.indexComplete = integer(cursor, "index_complete") != 0;
        result.documentSize = longValue(cursor, "document_size");
        result.documentModified = longValue(cursor, "document_modified");
        result.manualSource = integer(cursor, "manual_source") != 0;
        result.sampleSignature = string(cursor, "sample_signature");
        result.contentFingerprint = string(cursor, "content_fingerprint");
        result.favorite = integer(cursor, "favorite") != 0;
        result.readingDirection = ReadingDirection.normalize(string(cursor, "reading_direction"));
        result.available = integer(cursor, "available") != 0;
        result.seriesId = longValue(cursor, "series_id");
        result.seriesTitle = string(cursor, "series_title");
        result.seriesNumber = string(cursor, "series_number");
        result.seriesOverride = integer(cursor, "series_override");
        result.detectedSeriesKey = string(cursor, "detected_series_key");
        result.detectedSeriesName = string(cursor, "detected_series_name");
        result.detectedSeriesNumber = string(cursor, "detected_series_number");
        result.metadataState = integer(cursor, "metadata_state");
        return result;
    }

    private static String string(Cursor cursor, String column) {
        return cursor.getString(cursor.getColumnIndexOrThrow(column));
    }

    private static int integer(Cursor cursor, String column) {
        return cursor.getInt(cursor.getColumnIndexOrThrow(column));
    }

    private static long longValue(Cursor cursor, String column) {
        return cursor.getLong(cursor.getColumnIndexOrThrow(column));
    }

    private static float real(Cursor cursor, String column) {
        return cursor.getFloat(cursor.getColumnIndexOrThrow(column));
    }
}

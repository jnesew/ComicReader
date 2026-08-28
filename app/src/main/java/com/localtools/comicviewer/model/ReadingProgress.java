package com.localtools.comicviewer.model;

public final class ReadingProgress {
    public String uri = "";
    public String title = "";
    public int page = 0;
    public int pageCount = 0;
    public float scrollRatio = 0f;
    public String zoomMode = "fit_width";
    public float zoom = 1f;
    public boolean zoomGesturesLocked = false;
    public String readingMode = "single";
    public long lastOpened = 0L;
    public long addedAt = 0L;
    public String coverPath = "";
    public int coverState = 0;
    public int indexedPages = 0;
    public boolean indexComplete = false;
    public long documentSize = -1L;
    public long documentModified = -1L;
    public boolean manualSource = false;
    public String sampleSignature = "";
    public String contentFingerprint = "";
    public boolean favorite = false;
    public String readingDirection = ReadingDirection.AUTO;
    public boolean available = true;
    public long seriesId = 0L;
    public String seriesTitle = "";
    public String seriesNumber = "";
    public int seriesOverride = 0;
    public String detectedSeriesKey = "";
    public String detectedSeriesName = "";
    public String detectedSeriesNumber = "";
    public int metadataState = 0;

    public int percent() {
        if (pageCount <= 1) return 0;
        return Math.round(100f * Math.max(0, Math.min(page, pageCount - 1)) / (pageCount - 1));
    }

    public boolean isNew() {
        return lastOpened <= 0L;
    }

    public boolean isCompleted() {
        return pageCount > 0 && page >= pageCount - 1;
    }
}

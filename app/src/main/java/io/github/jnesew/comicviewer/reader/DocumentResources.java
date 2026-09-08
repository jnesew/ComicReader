package io.github.jnesew.comicviewer.reader;

import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.render.TileRenderer;

/** One owner per opened document. Active selection and the buffer share this holder. */
final class DocumentResources implements AutoCloseable {
    final ComicDocument document;
    TileRenderer renderer;
    ReadingProgress progress;
    private volatile boolean closed;

    DocumentResources(ComicDocument document, ReadingProgress progress) {
        this(document, null, progress);
    }

    DocumentResources(ComicDocument document, TileRenderer renderer, ReadingProgress progress) {
        this.document = document;
        this.renderer = renderer;
        this.progress = progress;
    }

    boolean isClosed() { return closed; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            if (renderer != null) renderer.close();
        } finally {
            document.close();
        }
    }
}

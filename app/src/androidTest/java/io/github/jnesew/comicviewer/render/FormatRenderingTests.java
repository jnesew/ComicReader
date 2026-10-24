package io.github.jnesew.comicviewer.render;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.SystemClock;

import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.document.DocumentInfo;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.model.PageInfo;
import io.github.jnesew.comicviewer.util.Ui;
import io.github.jnesew.comicviewer.util.SecureXml;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real container parsers, platform raster/PDF decoders, and reader View pixel assertions. */
public final class FormatRenderingTests {
    private static final String[] IMAGES = {"page.png", "page.jpg", "page.webp", "page.bmp", "page.gif", "limit.png"};
    private FormatRenderingTests() { }

    public static void platformXmlSecurity() {
        for (Charset encoding : new Charset[]{StandardCharsets.UTF_8, StandardCharsets.UTF_16,
                StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE}) {
            try {
                String valid = "<?xml version=\"1.0\"?><ComicInfo><Series>Ääni 漫画 🦊</Series></ComicInfo>";
                ReaderGeometryTests.check(SecureXml.parse(valid.getBytes(encoding), false)
                        .getDocumentElement().getTextContent().equals("Ääni 漫画 🦊"), "Unicode XML changed");
                for (String hostile : new String[]{
                        "<!DOCTYPE ComicInfo SYSTEM 'file:///does-not-exist'><ComicInfo/>",
                        "<!DOCTYPE ComicInfo [<!ENTITY x 'expanded'>]><ComicInfo>&x;</ComicInfo>",
                        "<!DOCTYPE ComicInfo [<!ENTITY % x SYSTEM 'https://example.invalid/dtd'>%x;]><ComicInfo/>"}) {
                    try {
                        SecureXml.parse(hostile.getBytes(encoding), true);
                        throw new AssertionError("Hostile XML accepted on Android");
                    } catch (org.xml.sax.SAXException expected) { }
                }
            } catch (Exception error) { throw new AssertionError(error); }
        }
    }

    public static void mixedRasterPages(Instrumentation instrumentation) {
        for (boolean epub : new boolean[]{false, true}) {
            File file = new File(instrumentation.getTargetContext().getCacheDir(),
                    epub ? "rendering-audit.epub" : "rendering-audit.cbz");
            try {
                archive(instrumentation, file, epub);
                try (ComicDocument document = open(instrumentation, file);
                     Fixture f = new Fixture(instrumentation, document)) {
                    if (!epub) ReaderGeometryTests.check(document.seriesMetadata().name.equals("Ääni 漫画 🦊"),
                            "ComicInfo metadata was lost on Android");
                    for (String mode : new String[]{ComicCanvasView.SINGLE, ComicCanvasView.CONTINUOUS}) {
                        for (int page = 0; page < IMAGES.length; page++) {
                            int index = page;
                            f.main(() -> {
                                ReadingProgress progress = new ReadingProgress();
                                progress.uri = document.key(); progress.readingMode = mode;
                                f.view.setDocument(f.renderer, document.pages(), progress);
                                f.view.fitWidth(); f.view.showPage(index, 0f);
                            });
                            int inset = mode.equals(ComicCanvasView.CONTINUOUS) ? Ui.dp(f.context, 8) : 0;
                            f.await(() -> f.color(inset + 4, 100, Color.RED) &&
                                    f.color(795 - inset, 100, Color.BLUE),
                                    mode + " cropped side edges of " + IMAGES[page]);
                            float pageHeight = (800f - 2 * inset) * document.page(page).aspectHeight();
                            float ratio = mode.equals(ComicCanvasView.CONTINUOUS)
                                    ? Math.max(0f, 1f - 600f / pageHeight) : 1f;
                            f.main(() -> f.view.showPage(index, ratio));
                            int bottomY = Math.min(590, (int) pageHeight - 10);
                            f.await(() -> f.color(400, bottomY, Color.rgb(0, 192, 0)),
                                    mode + " cannot reach bottom of " + IMAGES[page]);
                            if (page == 0 || page == IMAGES.length - 1) {
                                f.main(() -> {
                                    f.view.showPage(index, 0f); f.view.multiplyZoom(100f);
                                    ReaderGeometryTests.drag(f.view, -100_000, 0);
                                });
                                f.await(() -> f.color(795, 100, Color.BLUE), mode + " cannot pan to right edge");
                                f.main(() -> ReaderGeometryTests.drag(f.view, 100_000, 0));
                                f.await(() -> f.color(4, 100, Color.RED), mode + " cannot pan to left edge");
                                f.main(() -> ReaderGeometryTests.wheel(f.view, -1_000_000));
                                int endY = mode.equals(ComicCanvasView.CONTINUOUS) ? 300 : 590;
                                f.await(() -> f.color(400, endY, Color.rgb(0, 192, 0)), mode + " lost bottom at maximum zoom: " + IMAGES[page]);
                            }
                        }
                    }
                    for (boolean rtl : new boolean[]{false, true}) {
                        f.main(() -> {
                            f.view.setReadingMode(ComicCanvasView.SPREAD);
                            f.view.setRightToLeft(rtl); f.view.fitPage(); f.view.showPage(1, 0f);
                        });
                        f.await(() -> f.edgeRuns(300) == 4, "Spread lost an inner or outer edge, RTL=" + rtl);
                    }
                    // GIF uses the full-decoder fallback. Request 1:1 output to exercise its
                    // bounded backing bitmap, rather than only its small fit-width sample.
                    f.main(() -> {
                        ReadingProgress progress = new ReadingProgress(); progress.page = 4;
                        f.view.setDocument(f.renderer, document.pages(), progress);
                        f.view.actualSize(); ReaderGeometryTests.wheel(f.view, -1000);
                    });
                    f.await(() -> f.color(400, 590, Color.rgb(0, 192, 0)), "GIF fallback lost its bottom at actual size");
                }
            } catch (IOException error) { throw new AssertionError(error); }
            finally { file.delete(); }
        }
    }

    public static void tallPdfPreview(Instrumentation instrumentation) {
        File file = new File(instrumentation.getTargetContext().getCacheDir(), "tall-audit.pdf");
        try {
            PdfDocument pdf = new PdfDocument();
            try (FileOutputStream output = new FileOutputStream(file)) {
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(1024, 262144, 0).create());
                Paint paint = new Paint(); paint.setColor(Color.RED);
                page.getCanvas().drawRect(0, 0, 128, 262144, paint);
                paint.setColor(Color.BLUE); page.getCanvas().drawRect(896, 0, 1024, 262144, paint);
                paint.setColor(Color.GREEN); page.getCanvas().drawRect(0, 260000, 1024, 262144, paint);
                pdf.finishPage(page); pdf.writeTo(output);
            } finally { pdf.close(); }
            try (ComicDocument document = open(instrumentation, file)) {
                Bitmap preview = document.renderPreview(0, 112, 144);
                try {
                    ReaderGeometryTests.check(preview.getWidth() <= 112 && preview.getHeight() <= 144,
                            "Tall PDF preview exceeded requested bounds");
                } finally { preview.recycle(); }
                try (Fixture f = new Fixture(instrumentation, document)) {
                    f.main(() -> { f.view.fitWidth(); f.view.showPage(0, 1f); });
                    f.await(() -> f.color(400, 590, Color.GREEN), "Tall PDF bottom was clipped");
                }
            }
        } catch (IOException error) { throw new AssertionError(error); }
        finally { file.delete(); }
    }

    public static void dimensionsArrivingAfterFirstDraw(Instrumentation instrumentation) {
        File file = new File(instrumentation.getTargetContext().getCacheDir(), "indexing-audit.cbz");
        try {
            archive(instrumentation, file, false);
            try (ComicDocument actual = open(instrumentation, file)) {
                PendingDimensions pending = new PendingDimensions(actual);
                try (Fixture f = new Fixture(instrumentation, pending)) {
                    f.main(() -> f.view.showPage(1, 0f));
                    f.await(() -> f.color(4, 100, Color.RED) && f.color(400, 100, Color.WHITE),
                            "Initial provisional layout did not draw");
                    f.main(() -> {
                        pending.pages.set(1, actual.page(1));
                        f.view.onPageInfoChanged();
                    });
                    f.await(() -> f.color(80, 100, Color.RED) && f.color(120, 100, Color.WHITE) &&
                                    f.color(795, 100, Color.BLUE),
                            "Cached provisional tile was stretched after dimensions arrived");
                }
            }
        } catch (IOException error) { throw new AssertionError(error); }
        finally { file.delete(); }
    }

    private static final class PendingDimensions implements ComicDocument {
        final ComicDocument actual;
        final List<PageInfo> pages;
        PendingDimensions(ComicDocument actual) {
            this.actual = actual; pages = new ArrayList<>(actual.snapshotPages());
            pages.set(1, new PageInfo(actual.page(1).name, 400, 4000));
        }
        @Override public Uri uri() { return actual.uri(); }
        @Override public String key() { return actual.key(); }
        @Override public String title() { return actual.title(); }
        @Override public long documentSize() { return actual.documentSize(); }
        @Override public long documentModified() { return actual.documentModified(); }
        @Override public int count() { return pages.size(); }
        @Override public int indexedPages() { return 1; }
        @Override public boolean isIndexComplete() { return false; }
        @Override public boolean suggestedRightToLeft() { return false; }
        @Override public List<PageInfo> pages() { return pages; }
        @Override public List<PageInfo> snapshotPages() { return new ArrayList<>(pages); }
        @Override public PageInfo page(int index) { return pages.get(index); }
        @Override public InputStream openPage(int index) throws IOException { return actual.openPage(index); }
        @Override public void extractPage(int index, File file) throws IOException { actual.extractPage(index, file); }
        @Override public void buildPageIndex(Context c, ProgressCallback p, IndexCallback callback) { }
        @Override public void close() { }
    }

    private static ComicDocument open(Instrumentation instrumentation, File file) throws IOException {
        Context context = instrumentation.getTargetContext();
        ComicDocument document = ComicDocumentFactory.open(context, Uri.fromFile(file),
                new DocumentInfo(file.getName(), file.length(), file.lastModified(), ""),
                0, List.of(), -1, -1, null);
        try {
            document.buildPageIndex(context, null, new ComicDocument.IndexCallback() {
                @Override public void onProgress(int done, int total) { }
                @Override public void onPagesUpdated() { }
                @Override public void onComplete(List<io.github.jnesew.comicviewer.model.PageInfo> pages) { }
            });
            return document;
        } catch (IOException | RuntimeException error) { document.close(); throw error; }
    }

    private static void archive(Instrumentation instrumentation, File file, boolean epub) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            if (!epub) entry(zip, "ComicInfo.xml", "<ComicInfo><Series>Ääni 漫画 🦊</Series><Number>2.5</Number></ComicInfo>");
            if (epub) {
                entry(zip, "mimetype", "application/epub+zip");
                entry(zip, "META-INF/container.xml", "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"book.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
                StringBuilder manifest = new StringBuilder(), spine = new StringBuilder();
                for (int i = 0; i < IMAGES.length; i++) {
                    manifest.append("<item id=\"p").append(i).append("\" href=\"").append(i).append('-').append(IMAGES[i])
                            .append("\" media-type=\"image/").append(new String[]{"png", "jpeg", "webp", "bmp", "gif", "png"}[i]).append("\"/>");
                    spine.append("<itemref idref=\"p").append(i).append("\"/>");
                }
                entry(zip, "book.opf", "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><metadata/><manifest>" + manifest + "</manifest><spine>" + spine + "</spine></package>");
            }
            for (int i = 0; i < IMAGES.length; i++) {
                boolean bmp = IMAGES[i].endsWith("bmp");
                try (InputStream raw = instrumentation.getContext().getAssets().open("rendering/" + IMAGES[i] + (bmp ? ".bin" : ""));
                     InputStream input = bmp ? new GZIPInputStream(raw) : raw) {
                    zip.putNextEntry(new ZipEntry(i + "-" + IMAGES[i]));
                    input.transferTo(zip); zip.closeEntry();
                }
            }
        }
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }

    private static final class Fixture implements AutoCloseable {
        final Instrumentation instrumentation;
        final Context context;
        final ComicCanvasView view;
        final TileRenderer renderer;
        final AtomicReference<String> error = new AtomicReference<>();
        final Bitmap output = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888);
        Fixture(Instrumentation instrumentation, ComicDocument document) {
            this.instrumentation = instrumentation; context = instrumentation.getTargetContext();
            AtomicReference<ComicCanvasView> created = new AtomicReference<>();
            main(() -> created.set(new ComicCanvasView(context)));
            view = created.get();
            renderer = new TileRenderer(context, document, () -> { }, error::set, null, 24 * 1024);
            main(() -> { view.layout(0, 0, 800, 600); view.setDocument(renderer, document.pages(), new ReadingProgress()); });
        }
        void main(Runnable runnable) { ReaderGeometryTests.onMain(instrumentation, runnable); }
        void await(java.util.function.BooleanSupplier ready, String message) {
            long until = SystemClock.elapsedRealtime() + 15000;
            do {
                main(() -> view.draw(new Canvas(output)));
                ReaderGeometryTests.check(error.get() == null, "Render failure: " + error.get());
                if (ready.getAsBoolean()) return;
                SystemClock.sleep(20);
            } while (SystemClock.elapsedRealtime() < until);
            throw new AssertionError(message);
        }
        boolean color(int x, int y, int color) {
            int actual = output.getPixel(x, y);
            return Math.abs(Color.red(actual) - Color.red(color)) < 20 &&
                    Math.abs(Color.green(actual) - Color.green(color)) < 20 &&
                    Math.abs(Color.blue(actual) - Color.blue(color)) < 20;
        }
        int edgeRuns(int y) {
            int runs = 0, previous = 0;
            for (int x = 0; x < 800; x++) {
                int value = color(x, y, Color.RED) ? 1 : color(x, y, Color.BLUE) ? 2 : 0;
                if (value != 0 && value != previous) runs++;
                previous = value;
            }
            return runs;
        }
        @Override public void close() { main(() -> { view.clearDocument(); renderer.close(); }); output.recycle(); }
    }
}

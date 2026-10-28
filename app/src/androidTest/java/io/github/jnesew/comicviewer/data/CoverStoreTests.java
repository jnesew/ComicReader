package io.github.jnesew.comicviewer.data;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;

import io.github.jnesew.comicviewer.document.ComicDocument;
import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.document.DocumentInfo;
import io.github.jnesew.comicviewer.library.LibraryImportCoordinator;
import io.github.jnesew.comicviewer.model.ReadingProgress;
import io.github.jnesew.comicviewer.util.CacheKey;
import io.github.jnesew.comicviewer.util.InputLimits;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Production cover generation and importing with real Android decoders. */
public final class CoverStoreTests {
    private CoverStoreTests() { }

    public static void rasterCovers(Instrumentation instrumentation) {
        Context context = instrumentation.getTargetContext();
        for (boolean epub : new boolean[]{false, true}) {
            for (String image : new String[]{"page.gif", "page.bmp", "page.png", "page.jpg", "page.webp", "limit.png"}) {
                File source = new File(context.getCacheDir(), "cover-test" + (epub ? ".epub" : ".cbz"));
                String cover = null;
                try {
                    archive(instrumentation, source, image, epub);
                    try (ComicDocument document = open(context, source)) {
                        cover = CoverStore.ensureCover(context, document);
                    }
                    Bitmap bitmap = BitmapFactory.decodeFile(cover);
                    check(bitmap != null, "Missing cover for " + image + ", EPUB=" + epub);
                    try {
                        check(bitmap.getWidth() <= 720 && bitmap.getHeight() <= 1080, "Cover exceeds its size limit");
                        color(bitmap, .04f, .25f, Color.RED);
                        color(bitmap, .96f, .25f, Color.BLUE);
                        color(bitmap, .5f, .25f, image.equals("page.gif") ? Color.rgb(60, 60, 60) : Color.WHITE);
                        if (image.equals("page.gif")) {
                            check(Math.abs((float) bitmap.getWidth() / bitmap.getHeight() - 2f) < .02f,
                                    "Wide GIF cover aspect changed");
                            color(bitmap, .5f, .96f, Color.rgb(0, 192, 0));
                        } else if (image.equals("page.jpg")) {
                            check(Math.abs((float) bitmap.getHeight() / bitmap.getWidth() - 2f) < .02f,
                                    "Full-page cover aspect changed");
                            color(bitmap, .5f, .96f, Color.rgb(0, 192, 0));
                        } else {
                            check(Math.abs((float) bitmap.getHeight() / bitmap.getWidth() - 1.5f) < .02f,
                                    "Long-page cover did not retain its top crop");
                            color(bitmap, .5f, .96f, Color.WHITE);
                        }
                    } finally { bitmap.recycle(); }
                    check(source.isFile(), "Cover generation changed the comic source");
                    cleanTemporaryFiles(context, source);
                } catch (IOException error) { throw new AssertionError(image + ", EPUB=" + epub, error); }
                finally { CoverStore.delete(context, cover); source.delete(); }
            }
        }
    }

    public static void gifImport(Instrumentation instrumentation, LibraryDatabase database) {
        Context context = instrumentation.getTargetContext();
        File source = new File(context.getCacheDir(), "gif-import.cbz");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger imported = new AtomicInteger(-1);
        LibraryImportCoordinator imports = new LibraryImportCoordinator(context, database,
                new LibraryImportCoordinator.Listener() {
                    @Override public void onLibraryChanged() { }
                    @Override public void onImportFinished(int count) { imported.set(count); finished.countDown(); }
                });
        try {
            archive(instrumentation, source, "page.gif", false);
            imports.importComics(List.of(Uri.fromFile(source)));
            check(finished.await(30, TimeUnit.SECONDS), "GIF import did not finish");
            ReadingProgress item = database.get(Uri.fromFile(source).toString());
            check(imported.get() == 1 && CoverStore.exists(item.coverPath), "GIF import failed to retain its cover");
            check(item.pageCount == 1 && item.indexComplete && item.manualSource, "GIF import lost library metadata");
            check(source.isFile(), "Import changed the comic source");
        } catch (IOException | InterruptedException error) { throw new AssertionError(error); }
        finally {
            imports.close();
            try { imports.awaitStopped(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            CoverStore.delete(context, database.get(Uri.fromFile(source).toString()).coverPath);
            source.delete();
        }
    }

    public static void oversizedCover(Instrumentation instrumentation) {
        Context context = instrumentation.getTargetContext();
        File source = new File(context.getCacheDir(), "oversized-cover.epub");
        try {
            // The valid reading page has different dimensions from the separately declared cover.
            archive(instrumentation, source, "oversized.png", true);
            try (ComicDocument document = open(context, source)) {
                try {
                    CoverStore.ensureCover(context, document);
                    throw new AssertionError("Oversized cover was accepted");
                } catch (InputLimits.LimitExceededException expected) { }
            }
            cleanTemporaryFiles(context, source);
            check(!new File(context.getFilesDir(), "covers/" + CacheKey.digest(Uri.fromFile(source).toString()) + ".jpg").exists(),
                    "Failed cover left a completed thumbnail");
        } catch (IOException error) { throw new AssertionError(error); }
        finally { source.delete(); }
    }

    private static void cleanTemporaryFiles(Context context, File source) {
        String key = CacheKey.digest(Uri.fromFile(source).toString());
        check(!new File(context.getCacheDir(), "cover_sources/" + key + ".image").exists(), "Extracted cover was retained");
        check(!new File(context.getFilesDir(), "covers/" + key + ".jpg.partial").exists(), "Partial cover was retained");
    }

    private static ComicDocument open(Context context, File file) throws IOException {
        return ComicDocumentFactory.open(context, Uri.fromFile(file),
                new DocumentInfo(file.getName(), file.length(), file.lastModified(), ""),
                0, List.of(), -1, -1, null);
    }

    private static void archive(Instrumentation instrumentation, File file, String image, boolean epub) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            if (epub) {
                entry(zip, "mimetype", "application/epub+zip");
                entry(zip, "META-INF/container.xml", "<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>");
                String mime = image.endsWith("jpg") ? "jpeg" : image.substring(image.lastIndexOf('.') + 1);
                entry(zip, "book.opf", "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><metadata/><manifest>"
                        + "<item id=\"cover\" href=\"" + image + "\" media-type=\"image/" + mime + "\" properties=\"cover-image\"/>"
                        + "<item id=\"page\" href=\"reading.png\" media-type=\"image/png\"/>"
                        + "</manifest><spine><itemref idref=\"page\"/></spine></package>");
                image(instrumentation, zip, "page.png", "reading.png");
            }
            image(instrumentation, zip, image, image);
        }
    }

    private static void image(Instrumentation instrumentation, ZipOutputStream zip, String image, String entry) throws IOException {
        boolean bmp = image.endsWith("bmp");
        try (InputStream raw = instrumentation.getContext().getAssets().open("rendering/" + image + (bmp ? ".bin" : ""));
             InputStream input = bmp ? new GZIPInputStream(raw) : raw) {
            zip.putNextEntry(new ZipEntry(entry)); InputLimits.copy(input, zip, InputLimits.MAX_PAGE_BYTES, InputLimits.Reason.PAGE_BYTES); zip.closeEntry();
        }
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }

    private static void color(Bitmap bitmap, float x, float y, int expected) {
        int actual = bitmap.getPixel(Math.round((bitmap.getWidth() - 1) * x), Math.round((bitmap.getHeight() - 1) * y));
        check(Math.abs(Color.red(actual) - Color.red(expected)) < 30 &&
                Math.abs(Color.green(actual) - Color.green(expected)) < 30 &&
                Math.abs(Color.blue(actual) - Color.blue(expected)) < 30, "Cover lost its edges, crop, or colors");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

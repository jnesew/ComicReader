package io.github.jnesew.comicviewer.library;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.provider.DocumentsContract;

import io.github.jnesew.comicviewer.data.ContentFingerprint;
import io.github.jnesew.comicviewer.data.LibraryDatabase;
import io.github.jnesew.comicviewer.document.ComicDocumentFactory;
import io.github.jnesew.comicviewer.document.DocumentInfo;
import io.github.jnesew.comicviewer.model.ReadingProgress;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Reads/proves replacement sources on a worker; publishes key changes only when the reader is idle. */
final class LibrarySourceReconciler {
    private final Context context;
    private final LibraryDatabase database;

    LibrarySourceReconciler(Context context, LibraryDatabase database) {
        this.context = context;
        this.database = database;
    }

    List<Change> prepare(List<String> retainedTrees, String affectedTree,
            BooleanSupplier cancelled) {
        Set<String> retained = new HashSet<>(retainedTrees);
        ArrayList<Change> changes = new ArrayList<>();
        for (ReadingProgress item : database.sourceItems()) {
            if (cancelled.getAsBoolean()) break;
            List<LibraryDatabase.ScannedFile> copies = database.sourceCopies(item.uri);
            if (affectedTree != null && copies.stream().noneMatch(source ->
                    source.treeUri.equals(affectedTree))) continue;
            Change change = new Change(item);
            for (String manual : database.manualSources(item.uri)) {
                boolean readable = independentManualGrant(manual) && readable(manual, item.documentSize);
                change.manualUris.add(manual);
                change.manualReadable.add(readable);
                if (readable && manual.equals(item.uri)) change.currentReadable = true;
            }
            for (LibraryDatabase.ScannedFile source : copies) {
                if (!source.available || !retained.contains(source.treeUri)) continue;
                if (source.documentUri.equals(item.uri) && readable(item.uri, source.documentSize)) {
                    change.currentReadable = true;
                }
            }
            if (!change.currentReadable) {
                for (int index = 0; index < change.manualUris.size(); index++) {
                    if (!change.manualReadable.get(index)) continue;
                    String uri = change.manualUris.get(index);
                    String full = fullHash(uri);
                    if (!full.isEmpty() && matchesSavedIdentity(item, copies, full)) {
                        change.manualReplacement = uri;
                        change.full = full;
                        break;
                    }
                }
                if (change.manualReplacement.isEmpty()) {
                    for (LibraryDatabase.ScannedFile source : copies) {
                        if (cancelled.getAsBoolean()) break;
                        if (!source.available || !retained.contains(source.treeUri) ||
                                source.documentUri.equals(item.uri)) continue;
                        try {
                            Uri uri = Uri.parse(source.documentUri);
                            DocumentInfo info = ComicDocumentFactory.describe(context, uri);
                            String sample = ContentFingerprint.sample(context, uri, info.size);
                            String full = ContentFingerprint.full(context, uri);
                            if (!matchesSavedIdentity(item, copies, full) ||
                                    !sample.equals(ContentFingerprint.sample(context, uri, info.size))) continue;
                            DocumentInfo after = ComicDocumentFactory.describe(context, uri);
                            if (after.size != info.size || after.modified != info.modified) continue;
                            change.replacement = new LibraryDatabase.ScannedFile();
                            LibraryDatabase.ScannedFile replacement = change.replacement;
                            replacement.sourceIdentity = source.sourceIdentity;
                            replacement.treeUri = source.treeUri;
                            replacement.documentId = source.documentId;
                            replacement.documentUri = source.documentUri;
                            replacement.relativePath = source.relativePath;
                            replacement.canonicalUri = source.documentUri;
                            replacement.documentSize = info.size;
                            replacement.documentModified = info.modified;
                            replacement.sampleSignature = sample;
                            replacement.contentFingerprint = full;
                            replacement.lastSeen = source.lastSeen;
                            replacement.available = true;
                            for (LibraryDatabase.ScannedFile proof : copies) {
                                if (full.equals(proof.contentFingerprint)) {
                                    change.proof = proof;
                                    break;
                                }
                            }
                            break;
                        } catch (IOException | RuntimeException unavailableCopy) {
                            // A stored association alone is not fresh proof that the copy is usable.
                        }
                    }
                }
            }
            changes.add(change);
        }
        return changes;
    }

    private static boolean matchesSavedIdentity(ReadingProgress item,
            List<LibraryDatabase.ScannedFile> sources, String full) {
        if (full.isEmpty()) return false;
        if (!item.contentFingerprint.isEmpty()) return full.equals(item.contentFingerprint);
        for (LibraryDatabase.ScannedFile source : sources) {
            if (full.equals(source.contentFingerprint)) return true;
        }
        return false;
    }

    private String fullHash(String uri) {
        try { return ContentFingerprint.full(context, Uri.parse(uri)); }
        catch (IOException | RuntimeException unavailable) { return ""; }
    }

    private boolean readable(String uri, long size) {
        try { return !ContentFingerprint.sample(context, Uri.parse(uri), size).isEmpty(); }
        catch (IOException | RuntimeException unavailable) { return false; }
    }

    private boolean independentManualGrant(String uriString) {
        Uri uri = Uri.parse(uriString);
        // A tree-derived document URI can otherwise appear readable solely through the grant
        // that is about to be released. Standard document/file URIs are probed independently.
        if (!DocumentsContract.isTreeUri(uri)) return true;
        for (UriPermission grant : context.getContentResolver().getPersistedUriPermissions()) {
            if (grant.isReadPermission() && grant.getUri().equals(uri)) return true;
        }
        return false;
    }

    final class Change {
        final ReadingProgress before;
        final ArrayList<String> manualUris = new ArrayList<>();
        final ArrayList<Boolean> manualReadable = new ArrayList<>();
        boolean currentReadable;
        String manualReplacement = "";
        String full = "";
        LibraryDatabase.ScannedFile replacement;
        LibraryDatabase.ScannedFile proof;

        Change(ReadingProgress before) { this.before = before; }

        void apply() {
            ReadingProgress current = database.get(before.uri);
            if (current.uri.isEmpty() || current.documentSize != before.documentSize ||
                    current.documentModified != before.documentModified ||
                    !current.contentFingerprint.equals(before.contentFingerprint) ||
                    !database.manualSources(before.uri).equals(manualUris)) return;
            for (int index = 0; index < manualUris.size(); index++) {
                database.setManualSourceAvailable(before.uri, manualUris.get(index), manualReadable.get(index));
            }
            if (currentReadable) {
                database.setSourceAvailability(before.uri, true);
            } else if (!manualReplacement.isEmpty()) {
                if (!database.reconnectManualSource(before.uri, manualReplacement, full)) {
                    database.setSourceAvailability(before.uri, false);
                }
            } else if (replacement != null) {
                if (!database.reconnectByFingerprint(before.uri, proof, replacement)) {
                    database.setSourceAvailability(before.uri, false);
                }
            } else {
                database.setSourceAvailability(before.uri, false);
            }
        }
    }
}

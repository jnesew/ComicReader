package com.localtools.comicviewer.util;

/** Makes Storage Access Framework fallback identifiers suitable for compact UI notices. */
public final class LibraryFolderLabel {
    private LibraryFolderLabel() {
    }

    public static String compact(String value) {
        String original = value == null ? "" : value.trim();
        if (original.isEmpty()) return "";
        String candidate = original.replace('\\', '/');
        int colon = candidate.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < candidate.length()) {
            candidate = candidate.substring(colon + 1);
        }
        while (candidate.endsWith("/")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        int slash = candidate.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < candidate.length()) {
            candidate = candidate.substring(slash + 1);
        }
        candidate = candidate.trim();
        if (candidate.isEmpty()) return original;
        if (Character.isLowerCase(candidate.charAt(0))) {
            candidate = Character.toUpperCase(candidate.charAt(0)) + candidate.substring(1);
        }
        return candidate;
    }
}

package io.github.jnesew.comicviewer.render;

/** Session-wide limits for speculative decoding. Visible tile limits stay unchanged. */
public final class BufferingPolicy {
    public static final String STANDARD = "standard";
    public static final String INCREASED = "increased";
    public static final String HIGH = "high";
    private static final long MIB = 1024L * 1024L;

    private BufferingPolicy() { }

    public static String normalize(String value) {
        return INCREASED.equals(value) || HIGH.equals(value) ? value : STANDARD;
    }

    public static int lookaheadViewports(String value) {
        return switch (normalize(value)) {
            case INCREASED -> 1;
            case HIGH -> 3;
            default -> 0;
        };
    }

    public static float behindViewports(String value) {
        return HIGH.equals(normalize(value)) ? .5f : 0f;
    }

    public static int queuedTiles(String value) {
        return switch (normalize(value)) {
            case INCREASED -> 8;
            case HIGH -> 16;
            default -> 0;
        };
    }

    public static long visibleBytesPerRenderer(long heapBytes) {
        return Math.min(64L * MIB, Math.max(24L * MIB, heapBytes / 8L));
    }

    /** Three open renderers plus speculative tiles must leave substantial Java heap free. */
    public static long speculativeBytes(String value, long heapBytes) {
        if (STANDARD.equals(normalize(value))) return 0L;
        long visible = 3L * visibleBytesPerRenderer(heapBytes);
        long room = Math.max(0L, heapBytes * 3L / 5L - visible -
                Math.max(16L * MIB, heapBytes / 10L));
        long target = HIGH.equals(normalize(value)) ? 48L * MIB : 24L * MIB;
        return Math.max(0L, Math.min(target, room));
    }

    /** Work becoming visible always sorts ahead of queued speculative work. */
    public static int compareTasks(boolean firstSpeculative, long firstOrder,
            boolean secondSpeculative, long secondOrder) {
        if (firstSpeculative != secondSpeculative) return firstSpeculative ? 1 : -1;
        return Long.compare(firstOrder, secondOrder);
    }
}

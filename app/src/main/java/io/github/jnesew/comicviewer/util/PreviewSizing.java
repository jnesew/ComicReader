package io.github.jnesew.comicviewer.util;

/** Pure thumbnail sizing helpers shared by the Android preview loader and unit tests. */
public final class PreviewSizing {
    public static final class Size {
        public final int width;
        public final int height;

        private Size(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private PreviewSizing() {}

    /**
     * Chooses a power-of-two decode sample whose decoded raster is at most roughly twice the
     * requested edge size. The final bitmap is scaled precisely by {@link #fitInside}.
     */
    public static int decodeSample(
            int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
        validate(sourceWidth, sourceHeight, maxWidth, maxHeight);
        long decodeWidth = (long) maxWidth * 2L;
        long decodeHeight = (long) maxHeight * 2L;
        int sample = 1;
        while ((ceilDiv(sourceWidth, sample) > decodeWidth ||
                ceilDiv(sourceHeight, sample) > decodeHeight) &&
                sample <= (1 << 29)) {
            sample *= 2;
        }
        return sample;
    }

    /** Returns aspect-preserving dimensions that fit inside the requested box. */
    public static Size fitInside(
            int sourceWidth,
            int sourceHeight,
            int maxWidth,
            int maxHeight,
            boolean allowUpscale) {
        validate(sourceWidth, sourceHeight, maxWidth, maxHeight);
        double scale = Math.min(
                (double) maxWidth / sourceWidth,
                (double) maxHeight / sourceHeight);
        if (!allowUpscale) scale = Math.min(1d, scale);
        int width = Math.max(1, Math.min(maxWidth, (int) Math.round(sourceWidth * scale)));
        int height = Math.max(1, Math.min(maxHeight, (int) Math.round(sourceHeight * scale)));
        return new Size(width, height);
    }

    private static long ceilDiv(int value, int divisor) {
        return ((long) value + divisor - 1L) / divisor;
    }

    private static void validate(
            int sourceWidth, int sourceHeight, int maxWidth, int maxHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) {
            throw new IllegalArgumentException("Preview dimensions must be positive.");
        }
    }
}

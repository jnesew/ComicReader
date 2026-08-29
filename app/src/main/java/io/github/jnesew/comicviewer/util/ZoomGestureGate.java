package io.github.jnesew.comicviewer.util;

/** Pure gesture disambiguation for separating two-finger translation from intentional pinch. */
public final class ZoomGestureGate {
    static final float ACTIVATION_FACTOR = 1.04f;

    private static final double ACTIVATION_LOG = Math.log(ACTIVATION_FACTOR);

    private boolean locked;
    private boolean scaling;
    private boolean scaleConsumed;
    private float initialSpan = 1f;

    public void setLocked(boolean locked) {
        this.locked = locked;
        scaling = false;
        scaleConsumed = false;
    }

    public boolean isLocked() {
        return locked;
    }

    public void resetTouch() {
        scaling = false;
        scaleConsumed = false;
    }

    public void begin(float span) {
        initialSpan = validSpan(span);
        scaling = false;
    }

    /**
     * Returns the scale relative to the gesture's starting zoom. The activation dead zone is
     * removed from the result so crossing the threshold does not produce a visible zoom jump.
     */
    public float scaleFor(float span) {
        if (locked) return 1f;
        double logarithmicScale = Math.log(validSpan(span) / initialSpan);
        double magnitude = Math.abs(logarithmicScale);
        if (!scaling && magnitude <= ACTIVATION_LOG) return 1f;

        scaling = true;
        scaleConsumed = true;
        double adjustedMagnitude = Math.max(0d, magnitude - ACTIVATION_LOG);
        double adjusted = Math.copySign(adjustedMagnitude, logarithmicScale);
        return (float) Math.exp(adjusted);
    }

    public void end() {
        scaling = false;
    }

    public boolean isScaling() {
        return scaling;
    }

    public boolean hasConsumedScale() {
        return scaleConsumed;
    }

    private static float validSpan(float span) {
        return Float.isFinite(span) && span > 0f ? span : 1f;
    }
}

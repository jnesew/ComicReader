package com.localtools.comicviewer.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ZoomGestureGateTest {
    @Test
    public void lockedGestureNeverBecomesScale() {
        ZoomGestureGate gate = new ZoomGestureGate();
        gate.setLocked(true);
        gate.resetTouch();
        gate.begin(100f);

        assertEquals(1f, gate.scaleFor(180f), 0.0001f);
        assertFalse(gate.isScaling());
        assertFalse(gate.hasConsumedScale());
    }

    @Test
    public void minorSpanJitterRemainsTranslation() {
        ZoomGestureGate gate = new ZoomGestureGate();
        gate.resetTouch();
        gate.begin(100f);

        assertEquals(1f, gate.scaleFor(103f), 0.0001f);
        assertEquals(1f, gate.scaleFor(97f), 0.0001f);
        assertFalse(gate.isScaling());
        assertFalse(gate.hasConsumedScale());
    }

    @Test
    public void deliberatePinchActivatesWithoutThresholdJump() {
        ZoomGestureGate gate = new ZoomGestureGate();
        gate.resetTouch();
        gate.begin(100f);

        assertEquals(1.05f / ZoomGestureGate.ACTIVATION_FACTOR,
                gate.scaleFor(105f), 0.0001f);
        assertTrue(gate.isScaling());
        assertTrue(gate.hasConsumedScale());

        assertEquals(1f, gate.scaleFor(102f), 0.0001f);
        assertTrue(gate.isScaling());
    }

    @Test
    public void inwardPinchUsesSymmetricDeadZone() {
        ZoomGestureGate gate = new ZoomGestureGate();
        gate.resetTouch();
        gate.begin(100f);

        assertEquals(0.95f * ZoomGestureGate.ACTIVATION_FACTOR,
                gate.scaleFor(95f), 0.0001f);
        assertTrue(gate.isScaling());
    }

    @Test
    public void newTouchClearsConsumedScaleState() {
        ZoomGestureGate gate = new ZoomGestureGate();
        gate.resetTouch();
        gate.begin(100f);
        gate.scaleFor(120f);
        assertTrue(gate.hasConsumedScale());

        gate.resetTouch();
        assertFalse(gate.isScaling());
        assertFalse(gate.hasConsumedScale());
    }
}

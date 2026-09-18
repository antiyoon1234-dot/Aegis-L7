package com.l7defense.listener;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementHeuristicsTest {
    @Test void yawWrapIsSmallAndHalfTurnsRemainDetectable() {
        assertEquals(2.0f, MovementHeuristicsListener.yawDifference(179, -179));
        assertEquals(180.0f, MovementHeuristicsListener.yawDifference(0, 180));
        assertEquals(0.0f, MovementHeuristicsListener.yawDifference(0, 720));
    }
}

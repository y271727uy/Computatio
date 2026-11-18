package com.y271727uy.computatio.algorithm.fastpath.model;

import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import com.y271727uy.computatio.algorithm.fastpath.debug.*;

import com.y271727uy.computatio.algorithm.fastpath.execution.*;

import com.y271727uy.computatio.algorithm.fastpath.search.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TraversalBoundsTest {
    @Test
    void appliesSearchPaddingRadiusAndWorldHeightInCore() {
        TraversalBounds bounds = TraversalBounds.forPath(
                new GridPos(0, 64, 0),
                new GridPos(40, 80, 40),
                24,
                0,
                90);

        assertEquals(-12, bounds.minX());
        assertEquals(24, bounds.maxX());
        assertEquals(56, bounds.minY());
        assertEquals(88, bounds.maxY());
        assertTrue(bounds.contains(new TraversalBounds(0, 12, 0, 12, 60, 70)));
    }
}

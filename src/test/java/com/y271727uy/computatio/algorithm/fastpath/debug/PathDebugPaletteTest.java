package com.y271727uy.computatio.algorithm.fastpath.debug;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PathDebugPaletteTest {
    @Test
    void assignsTheSwimmingColorInCore() {
        assertEquals(0xFF3E8CFF, PathDebugPalette.colorFor(MovementMode.SWIM));
    }
}

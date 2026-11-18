package com.y271727uy.computatio.algorithm.fastpath.model;

import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

/** Immutable block-grid coordinate used by all platform-independent path data. */
public record GridPos(int x, int y, int z) {
    /** Packs a block coordinate using Minecraft-compatible bit ranges. */
    public long key() {
        return key(x, y, z);
    }

    /** Packs a block coordinate without allocating a position object. */
    public static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }
}

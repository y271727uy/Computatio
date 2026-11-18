package com.y271727uy.computatio.algorithm.fastpath.model;

import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

/** Immutable movement capabilities used by both search and the platform route follower. */
public record PathCapabilities(boolean canOpenDoors, boolean canFly,
                               boolean canClimbWalls, boolean canClimbLadders) {
    /** Backward-compatible ground NPC capabilities. */
    public static final PathCapabilities DEFAULT = new PathCapabilities(true, false, false, true);

    public PathCapabilities {
    }
}

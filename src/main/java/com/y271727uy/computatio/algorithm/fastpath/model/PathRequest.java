package com.y271727uy.computatio.algorithm.fastpath.model;

import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import java.util.Objects;

/** Immutable search input. Request ownership and scheduling identifiers remain platform concerns. */
public record PathRequest(String worldId, GridPos start, PathVector target, PathIntent intent,
                          PathCapabilities capabilities) {
    /** Creates a request with the default ground-NPC capabilities. */
    public PathRequest(String worldId, GridPos start, PathVector target, PathIntent intent) {
        this(worldId, start, target, intent, PathCapabilities.DEFAULT);
    }

    public PathRequest {
        worldId = Objects.requireNonNull(worldId, "worldId");
        start = Objects.requireNonNull(start, "start");
        target = Objects.requireNonNull(target, "target");
        intent = Objects.requireNonNull(intent, "intent");
        capabilities = Objects.requireNonNull(capabilities, "capabilities");
    }

    /** Returns the block containing the requested destination point. */
    public GridPos targetBlock() {
        return target.blockPosition();
    }
}

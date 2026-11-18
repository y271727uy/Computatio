package com.y271727uy.computatio.algorithm.fastpath.model;

import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import java.util.Objects;

public record PathWaypoint(GridPos blockPosition, PathVector position, MovementMode mode) {
    public PathWaypoint {
        blockPosition = Objects.requireNonNull(blockPosition, "blockPosition");
        position = Objects.requireNonNull(position, "position");
        mode = Objects.requireNonNull(mode, "mode");
    }

    public static PathWaypoint of(TraversalCell cell, MovementMode mode) {
        return new PathWaypoint(cell.position(), new PathVector(cell.x() + 0.5D, cell.standY(), cell.z() + 0.5D), mode);
    }
}

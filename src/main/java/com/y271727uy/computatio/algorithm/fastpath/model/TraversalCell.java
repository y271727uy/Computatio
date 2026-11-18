package com.y271727uy.computatio.algorithm.fastpath.model;

import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

/** A sampled occupiable grid cell with its stand height and traversal traits. */
public record TraversalCell(int x, int y, int z, double standY, boolean water, boolean climbable,
                            boolean woodenDoor, double cost) {
    /** Returns the compact coordinate key used by snapshots and searches. */
    public long key() {
        return GridPos.key(x, y, z);
    }

    /** Returns the grid coordinate for platform result conversion. */
    public GridPos position() {
        return new GridPos(x, y, z);
    }

    /** Returns the normal movement mode when entering this cell. */
    public MovementMode defaultMode(PathIntent intent) {
        if (water) {
            return MovementMode.SWIM;
        }
        if (climbable) {
            return MovementMode.CLIMB;
        }
        return intent == PathIntent.RUN ? MovementMode.RUN : MovementMode.WALK;
    }
}

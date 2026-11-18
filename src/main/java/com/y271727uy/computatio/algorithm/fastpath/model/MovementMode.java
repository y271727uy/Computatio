package com.y271727uy.computatio.algorithm.fastpath.model;

import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

/** Motion that the platform follower must use to reach a waypoint. */
public enum MovementMode {
    WALK,
    RUN,
    JUMP,
    SWIM,
    CLIMB,
    WALL_CLIMB,
    FLY,
    FALL;

    /** Returns whether a follower must preserve this waypoint's free-space Y coordinate. */
    public boolean usesDirectTargetHeight() {
        return this == SWIM || this == CLIMB || this == WALL_CLIMB || this == FLY;
    }
}

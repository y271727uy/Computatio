package com.y271727uy.computatio.algorithm.fastpath.execution;

import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import com.y271727uy.computatio.algorithm.fastpath.debug.*;

import com.y271727uy.computatio.algorithm.fastpath.search.*;

import com.y271727uy.computatio.algorithm.fastpath.model.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PathRouteFollowerTest {
    @Test
    void usesPathHeightForSwimmingWaypoints() {
        FakePort port = new FakePort(new PathVector(0.0D, 64.0D, 0.0D));
        PathRouteFollower follower = new PathRouteFollower(port, PathCapabilities.DEFAULT);
        PathWaypoint waypoint = waypoint(0, 65, 0, 65.25D, MovementMode.SWIM);

        assertTrue(follower.setRoute(PathResult.success(List.of(waypoint))));
        follower.tick(1.0D);

        assertEquals(waypoint, port.lastWaypoint);
        assertEquals(65.25D, port.lastTargetY);
        assertEquals(0, port.groundHeightResolutions);
        assertTrue(port.doorOpenAttempts > 0);
    }

    @Test
    void requestsRepathOnlyOnceAfterFortyStalledTicks() {
        FakePort port = new FakePort(new PathVector(0.0D, 64.0D, 0.0D));
        PathRouteFollower follower = new PathRouteFollower(port, PathCapabilities.DEFAULT);
        PathWaypoint waypoint = waypoint(3, 64, 0, 64.0D, MovementMode.WALK);

        follower.setRoute(PathResult.success(List.of(waypoint)));
        for (int tick = 0; tick < 40; tick++) {
            follower.tick(1.0D);
        }

        assertTrue(follower.isStuck());
        assertTrue(follower.consumeRepathRequest());
        assertFalse(follower.consumeRepathRequest());
    }

    private static PathWaypoint waypoint(int x, int y, int z, double standY, MovementMode mode) {
        return new PathWaypoint(new GridPos(x, y, z), new PathVector(x + 0.5D, standY, z + 0.5D), mode);
    }

    private static final class FakePort implements PathRouteFollowerPort {
        private final PathVector position;
        private PathWaypoint lastWaypoint;
        private double lastTargetY;
        private int groundHeightResolutions;
        private int doorOpenAttempts;

        private FakePort(PathVector position) {
            this.position = position;
        }

        @Override
        public PathVector currentPosition() {
            return position;
        }

        @Override
        public double bodyWidth() {
            return 0.6D;
        }

        @Override
        public double resolveGroundTargetY(PathWaypoint waypoint) {
            groundHeightResolutions++;
            return waypoint.position().y() + 0.5D;
        }

        @Override
        public void openDoor(GridPos position) {
            doorOpenAttempts++;
        }

        @Override
        public void moveTo(PathVector target, double targetY, double speedModifier) {
            lastTargetY = targetY;
        }

        @Override
        public void moveToWaypoint(PathWaypoint waypoint, double targetY, double speedModifier) {
            lastWaypoint = waypoint;
            PathRouteFollowerPort.super.moveToWaypoint(waypoint, targetY, speedModifier);
        }
    }
}

package com.y271727uy.computatio.algorithm.fastpath.debug;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PathDebugRouteTest {
    @Test
    void convertsCoreResultWithoutPlatformTypes() {
        PathWaypoint waypoint = new PathWaypoint(
                new GridPos(2, 64, 3), new PathVector(2.5D, 64.25D, 3.5D), MovementMode.SWIM);
        PathDebugRoute route = PathDebugRoute.from(
                new UUID(1L, 2L), PathResult.success(List.of(waypoint)));

        assertTrue(route.success());
        assertEquals(1, route.points().size());
        assertEquals(MovementMode.SWIM, route.points().get(0).mode());
        assertEquals(64.25D, route.points().get(0).y());
    }
}

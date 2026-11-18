package com.y271727uy.computatio.algorithm.fastpath.search;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathfinderTest {
    @Test
    void findsStraightGroundRoute() {
        TraversalSnapshot.Builder snapshot = snapshotBuilder();
        snapshot.addCell(groundCell(0, 64, 0));
        snapshot.addCell(groundCell(0, 64, 1));
        snapshot.addCell(groundCell(0, 64, 2));

        PathResult result = Pathfinder.find(request(0, 64, 0, 0, 64, 2), snapshot.build());

        assertTrue(result.success());
        assertTrue(result.waypoints().size() >= 2);
    }

    @Test
    void rejectsRouteThroughOpenTrapdoorThinWall() {
        TraversalSnapshot.Builder snapshot = snapshotBuilder();
        snapshot.addCell(groundCell(0, 64, 0));
        snapshot.addCell(groundCell(0, 64, 1));
        snapshot.addHorizontalBarrier(0, 64, 0, TraversalSnapshot.barrierMask(0, 1));

        PathResult result = Pathfinder.find(request(0, 64, 0, 0, 64, 1), snapshot.build());

        assertEquals(PathResult.Status.NO_PATH, result.status());
    }

    @Test
    void respectsDoorOpeningCapability() {
        TraversalSnapshot.Builder snapshot = snapshotBuilder();
        snapshot.addCell(groundCell(0, 64, 0));
        snapshot.addCell(new TraversalCell(0, 64, 1, 64, false, false, true, 1.0D));
        snapshot.addCell(groundCell(0, 64, 2));

        PathCapabilities cannotOpenDoors = new PathCapabilities(false, false, false, true);
        PathResult result = Pathfinder.find(request(0, 64, 0, 0, 64, 2, cannotOpenDoors), snapshot.build());

        assertFalse(result.success());
    }

    @Test
    void respectsLadderClimbingCapability() {
        TraversalSnapshot.Builder snapshot = snapshotBuilder();
        snapshot.addCell(new TraversalCell(0, 64, 0, 64, false, true, false, 1.0D));
        snapshot.addCell(new TraversalCell(0, 65, 0, 65, false, true, false, 1.0D));

        PathCapabilities cannotClimbLadders = new PathCapabilities(true, false, false, false);
        PathResult result = Pathfinder.find(request(0, 64, 0, 0, 65, 0, cannotClimbLadders), snapshot.build());

        assertFalse(result.success());
    }

    @Test
    void fliesThroughFreeBodyPassages() {
        TraversalSnapshot.Builder snapshot = snapshotBuilder();
        snapshot.addBodyPassage(0, 64, 0);
        snapshot.addBodyPassage(0, 64, 1);

        PathCapabilities flying = new PathCapabilities(true, true, false, false);
        PathResult result = Pathfinder.find(request(0, 64, 0, 0, 64, 1, flying), snapshot.build());

        assertTrue(result.success());
        assertTrue(result.waypoints().stream().anyMatch(waypoint -> waypoint.mode() == MovementMode.FLY));
    }

    @Test
    void flightCannotCrossThinWallsAlongTheZAxisOrDiagonally() {
        TraversalSnapshot.Builder builder = snapshotBuilder();
        for (int x = 0; x <= 1; x++) {
            builder.addBodyPassage(x, 64, 0).addBodyPassage(x, 64, 1)
                    .addHorizontalBarrier(x, 64, 0, TraversalSnapshot.barrierMask(0, 1));
        }
        TraversalSnapshot snapshot = builder.build();
        PathCapabilities flying = new PathCapabilities(true, true, false, false);

        assertEquals(PathResult.Status.NO_PATH,
                Pathfinder.find(request(0, 64, 0, 0, 64, 1, flying), snapshot).status());
        assertEquals(PathResult.Status.NO_PATH,
                Pathfinder.find(request(0, 64, 0, 1, 64, 1, flying), snapshot).status());
    }

    @Test
    void reportsSearchLimitWhenTheFrontierStillContainsCells() {
        int targetZ = 20_000;
        TraversalSnapshot.Builder snapshot = snapshotBuilder();
        for (int z = 0; z <= targetZ; z++) {
            snapshot.addCell(groundCell(0, 64, z));
        }

        PathResult result = Pathfinder.find(request(0, 64, 0, 0, 64, targetZ), snapshot.build());

        assertEquals(PathResult.Status.SEARCH_LIMIT_REACHED, result.status());
    }

    @Test
    void climbsAFullHeightWall() {
        TraversalSnapshot.Builder snapshot = snapshotBuilder();
        snapshot.addBodyPassage(0, 64, 0);
        snapshot.addBodyPassage(0, 65, 0);

        PathCapabilities wallClimber = new PathCapabilities(true, false, true, false);
        PathResult result = Pathfinder.find(request(0, 64, 0, 0, 65, 0, wallClimber), snapshot.build());

        assertTrue(result.success());
        assertTrue(result.waypoints().stream().anyMatch(waypoint -> waypoint.mode() == MovementMode.WALL_CLIMB));
    }

    @Test
    void wallClimberCannotCrossOpenAir() {
        TraversalSnapshot.Builder snapshot = snapshotBuilder();
        snapshot.addBodyPassage(0, 64, 0);
        snapshot.addBodyPassage(0, 64, 1);

        PathCapabilities wallClimber = new PathCapabilities(true, false, true, false);
        PathResult result = Pathfinder.find(request(0, 64, 0, 0, 64, 1, wallClimber), snapshot.build());

        assertFalse(result.success());
    }

    @Test
    void findsSwimmingRouteWithinWater() {
        TraversalSnapshot.Builder snapshot = snapshotBuilder();
        snapshot.addCell(waterCell(0, 64, 0));
        snapshot.addCell(waterCell(0, 64, 1));

        PathResult result = Pathfinder.find(request(0, 64, 0, 0, 64, 1), snapshot.build());

        assertTrue(result.success());
        assertTrue(result.waypoints().stream().anyMatch(waypoint -> waypoint.mode() == MovementMode.SWIM));
    }

    @Test
    void rejectsSnapshotFromAnotherWorld() {
        TraversalSnapshot.Builder snapshot = TraversalSnapshot.builder(
                "test:other_world", new GridPos(0, 64, 0), new GridPos(0, 64, 1), 63, 65, 0L, true);
        snapshot.addCell(groundCell(0, 64, 0));
        snapshot.addCell(groundCell(0, 64, 1));

        PathResult result = Pathfinder.find(request(0, 64, 0, 0, 64, 1), snapshot.build());

        assertFalse(result.success());
        assertTrue(result.reason().equals("world_mismatch"));
    }

    private static TraversalSnapshot.Builder snapshotBuilder() {
        return TraversalSnapshot.builder("test:world", new GridPos(0, 64, 0), new GridPos(0, 64, 2),
                63, 65, 0L, true);
    }

    private static TraversalCell groundCell(int x, int y, int z) {
        return new TraversalCell(x, y, z, y, false, false, false, 1.0D);
    }

    private static TraversalCell waterCell(int x, int y, int z) {
        return new TraversalCell(x, y, z, y, true, false, false, 5.0D);
    }

    private static PathRequest request(int startX, int startY, int startZ, int targetX, int targetY, int targetZ) {
        return request(startX, startY, startZ, targetX, targetY, targetZ, PathCapabilities.DEFAULT);
    }

    private static PathRequest request(int startX, int startY, int startZ, int targetX, int targetY, int targetZ,
                                       PathCapabilities capabilities) {
        return new PathRequest("test:world", new GridPos(startX, startY, startZ),
                new PathVector(targetX + 0.5D, targetY, targetZ + 0.5D), PathIntent.WALK, capabilities);
    }
}

package com.y271727uy.computatio.algorithm.fastpath.search;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AsyncPathfinderTest {
    @Test
    void calculatesUsingTheSuppliedFrozenSnapshot() {
        AtomicInteger snapshotBuilds = new AtomicInteger();

        PathResult result = AsyncPathfinder.findAsync(
                request(),
                () -> {
                    snapshotBuilds.incrementAndGet();
                    return snapshot();
                },
                Runnable::run).join();

        assertEquals(1, snapshotBuilds.get());
        assertTrue(result.success());
    }

    private static PathRequest request() {
        return new PathRequest(
                "test:world",
                new GridPos(0, 64, 0),
                new PathVector(0.5D, 64.0D, 1.5D),
                PathIntent.WALK);
    }

    private static TraversalSnapshot snapshot() {
        TraversalSnapshot.Builder builder = TraversalSnapshot.builder(
                "test:world", new GridPos(0, 64, 0), new GridPos(0, 64, 1), 63, 65, 0L, true);
        builder.addCell(new TraversalCell(0, 64, 0, 64.0D, false, false, false, 1.0D));
        builder.addCell(new TraversalCell(0, 64, 1, 64.0D, false, false, false, 1.0D));
        return builder.build();
    }
}

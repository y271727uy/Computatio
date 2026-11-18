package com.y271727uy.computatio.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.y271727uy.computatio.algorithm.fastpath.model.GridPos;
import com.y271727uy.computatio.algorithm.fastpath.model.PathIntent;
import com.y271727uy.computatio.algorithm.fastpath.model.PathRequest;
import com.y271727uy.computatio.algorithm.fastpath.model.PathResult;
import com.y271727uy.computatio.algorithm.fastpath.model.PathResult.Status;
import com.y271727uy.computatio.algorithm.fastpath.model.PathVector;
import com.y271727uy.computatio.algorithm.fastpath.model.TraversalCell;
import com.y271727uy.computatio.algorithm.fastpath.model.TraversalSnapshot;
import com.y271727uy.computatio.api.fastpath.FastPath;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;

class FastPathTest {
    private static final GridPos START = new GridPos(0, 64, 0);
    private static final GridPos TARGET = new GridPos(0, 64, 1);
    private static final PathRequest REQUEST = new PathRequest(
            "test:world", START, new PathVector(0.5D, 64.0D, 1.5D), PathIntent.WALK);

    @Test
    void facadeFindsPathWithoutExposingSearchImplementation() {
        TraversalSnapshot snapshot = snapshotBuilder(true)
                .addCell(new TraversalCell(0, 64, 1, 64.0D, false, false, false, 1.0D)).build();

        assertEquals(Status.EXACT_TARGET, FastPath.find(REQUEST, snapshot).status());
    }

    @Test
    void reportsTheActualDestinationWhenTheTargetIsSubstituted() {
        PathResult result = FastPath.find(REQUEST, snapshotBuilder(true).build());

        assertEquals(Status.NEAR_TARGET, result.status());
        assertTrue(result.success());
        assertEquals(START, result.waypoints().get(0).blockPosition());
    }

    @Test
    void reportsIncompleteSampleWhenTargetWasNotCaptured() {
        PathResult result = FastPath.find(REQUEST, snapshotBuilder(false).build());

        assertEquals(Status.INCOMPLETE_SNAPSHOT, result.status());
        assertFalse(result.success());
    }

    @Test
    void searchesTheFrozenSnapshotOnTheSuppliedExecutor() {
        TraversalSnapshot snapshot = snapshotBuilder(false)
                .addCell(new TraversalCell(0, 64, 1, 64.0D, false, false, false, 1.0D)).build();
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        var pending = FastPath.findAsync(REQUEST, snapshot, tasks::add);
        assertFalse(pending.isDone());
        tasks.remove().run();

        assertEquals(Status.EXACT_TARGET, pending.join().status());
    }

    private static TraversalSnapshot.Builder snapshotBuilder(boolean complete) {
        return TraversalSnapshot.builder(REQUEST.worldId(), START, TARGET, 63, 65, 0L, complete)
                .addCell(new TraversalCell(0, 64, 0, 64.0D, false, false, false, 1.0D));
    }
}

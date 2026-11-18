package com.y271727uy.computatio.algorithm.fastpath.model;

import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Objects;

/**
 * Immutable, platform-neutral traversal data captured before a worker search begins.
 *
 * <p>Builders are single-use. Once {@link Builder#build()} returns, no mutable collection used by
 * the snapshot remains reachable from the builder, so workers can read it without world access or
 * synchronization.
 */
public final class TraversalSnapshot {
    private static final byte NORTH = 1;
    private static final byte SOUTH = 2;
    private static final byte WEST = 4;
    private static final byte EAST = 8;

    private final String worldId;
    private final GridPos start;
    private final GridPos target;
    private final Long2ObjectOpenHashMap<TraversalCell> cells;
    private final LongOpenHashSet bodyPassages;
    private final Long2ByteOpenHashMap horizontalBarriers;
    private final int minY;
    private final int maxY;
    private final long capturedAt;
    private final boolean complete;

    private TraversalSnapshot(Builder builder) {
        worldId = builder.worldId;
        start = builder.start;
        target = builder.target;
        cells = builder.cells;
        bodyPassages = builder.bodyPassages;
        horizontalBarriers = builder.horizontalBarriers;
        minY = builder.minY;
        maxY = builder.maxY;
        capturedAt = builder.capturedAt;
        complete = builder.complete;
    }

    /** Returns the platform-provided stable world identifier. */
    public String worldId() {
        return worldId;
    }

    /** Returns the requested source grid coordinate. */
    public GridPos start() {
        return start;
    }

    /** Returns the requested target grid coordinate. */
    public GridPos target() {
        return target;
    }

    /** Returns the walkable cell at a coordinate, or {@code null} when absent. */
    public TraversalCell cell(int x, int y, int z) {
        return cells.get(GridPos.key(x, y, z));
    }

    /** Returns the walkable cell at a coordinate, or {@code null} when absent. */
    public TraversalCell cell(GridPos position) {
        return cells.get(position.key());
    }

    /** Returns whether the body can pass vertically through the given grid position. */
    public boolean bodyPassage(int x, int y, int z) {
        return bodyPassages.contains(GridPos.key(x, y, z));
    }

    /** Returns whether a cardinal move crosses a captured thin collision panel. */
    public boolean blocksHorizontalBoundary(int fromX, int y, int fromZ, int toX, int toZ) {
        int dx = Integer.compare(toX - fromX, 0);
        int dz = Integer.compare(toZ - fromZ, 0);
        if (Math.abs(toX - fromX) + Math.abs(toZ - fromZ) != 1) {
            return false;
        }
        return (horizontalBarriers.get(GridPos.key(fromX, y, fromZ)) & barrierMask(dx, dz)) != 0
                || (horizontalBarriers.get(GridPos.key(toX, y, toZ)) & barrierMask(-dx, -dz)) != 0;
    }

    /** Returns the inclusive lower sampled Y bound. */
    public int minY() {
        return minY;
    }

    /** Returns the inclusive upper sampled Y bound. */
    public int maxY() {
        return maxY;
    }

    /** Returns the platform tick or monotonic capture time. */
    public long capturedAt() {
        return capturedAt;
    }

    /** Returns false when terrain data was unavailable in any part of the sampled region. */
    public boolean complete() {
        return complete;
    }

    /** Returns the directional thin-wall mask used by platform samplers. */
    public static byte barrierMask(int dx, int dz) {
        if (dx == 0 && dz < 0) return NORTH;
        if (dx == 0 && dz > 0) return SOUTH;
        if (dx < 0 && dz == 0) return WEST;
        if (dx > 0 && dz == 0) return EAST;
        return 0;
    }

    /** Creates the single-use mutable assembler for a frozen snapshot. */
    public static Builder builder(String worldId, GridPos start, GridPos target, int minY, int maxY,
                                  long capturedAt, boolean complete) {
        return new Builder(worldId, start, target, minY, maxY, capturedAt, complete);
    }

    /** Single-threaded snapshot assembler used only before scheduling a worker search. */
    public static final class Builder {
        private final String worldId;
        private final GridPos start;
        private final GridPos target;
        private final Long2ObjectOpenHashMap<TraversalCell> cells = new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet bodyPassages = new LongOpenHashSet();
        private final Long2ByteOpenHashMap horizontalBarriers = new Long2ByteOpenHashMap();
        private final int minY;
        private final int maxY;
        private final long capturedAt;
        private final boolean complete;
        private boolean built;

        private Builder(String worldId, GridPos start, GridPos target, int minY, int maxY,
                        long capturedAt, boolean complete) {
            this.worldId = Objects.requireNonNull(worldId, "worldId");
            this.start = Objects.requireNonNull(start, "start");
            this.target = Objects.requireNonNull(target, "target");
            this.minY = minY;
            this.maxY = maxY;
            this.capturedAt = capturedAt;
            this.complete = complete;
        }

        /** Adds one walkable cell discovered by a platform sampler. */
        public Builder addCell(TraversalCell cell) {
            ensureOpen();
            cells.put(Objects.requireNonNull(cell, "cell").key(), cell);
            return this;
        }

        /** Marks a grid position as vertically occupiable by the entity body. */
        public Builder addBodyPassage(int x, int y, int z) {
            ensureOpen();
            bodyPassages.add(GridPos.key(x, y, z));
            return this;
        }

        /** Adds a cardinal thin-wall collision barrier to a source cell. */
        public Builder addHorizontalBarrier(int x, int y, int z, byte mask) {
            ensureOpen();
            if (mask != 0) {
                long key = GridPos.key(x, y, z);
                horizontalBarriers.put(key, (byte) (horizontalBarriers.get(key) | mask));
            }
            return this;
        }

        /** Seals the owned primitive collections and creates the worker-safe snapshot. */
        public TraversalSnapshot build() {
            ensureOpen();
            built = true;
            return new TraversalSnapshot(this);
        }

        private void ensureOpen() {
            if (built) {
                throw new IllegalStateException("TraversalSnapshot.Builder is already built");
            }
        }
    }
}

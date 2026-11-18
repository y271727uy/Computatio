package com.y271727uy.computatio.algorithm.fastpath.model;

import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import java.util.Objects;

/** 不依赖平台坐标类型的寻路快照采样范围。 */
public record TraversalBounds(int minX, int maxX, int minZ, int maxZ, int minY, int maxY) {
    private static final int MINIMUM_RADIUS = 16;
    private static final int HORIZONTAL_PADDING = 12;
    private static final int VERTICAL_PADDING = 8;

    /**
     * forPath：计算覆盖起点、目标与安全边距的有界采样盒。
     *
     * @param start 路径起点
     * @param target 路径目标方块
     * @param radius 起点周围允许的最大采样半径
     * @param worldMinY 平台允许采样的最低 Y
     * @param worldMaxY 平台允许采样的最高 Y
     * @return 可直接用于地形采样的闭区间范围
     */
    public static TraversalBounds forPath(
            GridPos start,
            GridPos target,
            int radius,
            int worldMinY,
            int worldMaxY) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(target, "target");
        if (worldMinY > worldMaxY) {
            throw new IllegalArgumentException("worldMinY must not exceed worldMaxY");
        }
        int safeRadius = Math.max(MINIMUM_RADIUS, radius);
        int minX = Math.max(Math.min(start.x(), target.x()) - HORIZONTAL_PADDING, start.x() - safeRadius);
        int maxX = Math.min(Math.max(start.x(), target.x()) + HORIZONTAL_PADDING, start.x() + safeRadius);
        int minZ = Math.max(Math.min(start.z(), target.z()) - HORIZONTAL_PADDING, start.z() - safeRadius);
        int maxZ = Math.min(Math.max(start.z(), target.z()) + HORIZONTAL_PADDING, start.z() + safeRadius);
        int minY = Math.max(worldMinY, Math.min(start.y(), target.y()) - VERTICAL_PADDING);
        int maxY = Math.min(worldMaxY, Math.max(start.y(), target.y()) + VERTICAL_PADDING);
        return new TraversalBounds(minX, maxX, minZ, maxZ, minY, maxY);
    }

    /** contains：返回本范围是否完整包含另一个采样范围。 */
    public boolean contains(TraversalBounds other) {
        Objects.requireNonNull(other, "other");
        return minX <= other.minX && maxX >= other.maxX
                && minZ <= other.minZ && maxZ >= other.maxZ
                && minY <= other.minY && maxY >= other.maxY;
    }
}

package com.y271727uy.computatio.algorithm.fastpath.debug;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import java.util.List;
import java.util.UUID;

/** 可安全传输到客户端的有界调试路径数据。 */
public record PathDebugRoute(UUID routeId, boolean success, String reason, List<PathDebugPoint> points) {
    /** 单条调试路径可传输的最大点数，用于限制网络与客户端内存占用。 */
    public static final int MAX_POINTS = 2048;

    public PathDebugRoute {
        routeId = routeId != null ? routeId : new UUID(0L, 0L);
        reason = reason != null ? reason : "";
        points = points == null
                ? List.of()
                : List.copyOf(points.size() > MAX_POINTS ? points.subList(0, MAX_POINTS) : points);
    }

    /** from：将 Core 路径结果转换为有界调试数据。 */
    public static PathDebugRoute from(UUID routeId, PathResult result) {
        if (result == null) {
            return new PathDebugRoute(routeId, false, "path_result_missing", List.of());
        }
        List<PathDebugPoint> points = result.waypoints().stream()
                .limit(MAX_POINTS)
                .map(PathDebugRoute::toPoint)
                .toList();
        return new PathDebugRoute(routeId, result.success(), result.reason(), points);
    }

    private static PathDebugPoint toPoint(PathWaypoint waypoint) {
        PathVector position = waypoint.position();
        return new PathDebugPoint(position.x(), position.y(), position.z(), waypoint.mode());
    }
}

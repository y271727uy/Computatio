package com.y271727uy.computatio.algorithm.fastpath.debug;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import java.util.Objects;

/** 供任意平台调试渲染器消费的不可变路径点。 */
public record PathDebugPoint(double x, double y, double z, MovementMode mode) {
    public PathDebugPoint {
        mode = Objects.requireNonNull(mode, "mode");
    }
}

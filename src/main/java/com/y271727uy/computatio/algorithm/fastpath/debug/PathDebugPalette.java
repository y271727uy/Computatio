package com.y271727uy.computatio.algorithm.fastpath.debug;

import com.y271727uy.computatio.algorithm.fastpath.model.*;
import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

import java.util.Objects;

/** 供各平台调试渲染器共享的路径模式颜色。 */
public final class PathDebugPalette {
    private PathDebugPalette() {
    }

    /** colorFor：返回指定移动模式的 ARGB 调试颜色。 */
    public static int colorFor(MovementMode mode) {
        Objects.requireNonNull(mode, "mode");
        return switch (mode) {
            case RUN -> 0xFF35E06B;
            case JUMP -> 0xFFFFDD44;
            case SWIM -> 0xFF3E8CFF;
            case CLIMB -> 0xFFD064FF;
            case FALL -> 0xFFFF8A2A;
            default -> 0xFF16D9D9;
        };
    }
}

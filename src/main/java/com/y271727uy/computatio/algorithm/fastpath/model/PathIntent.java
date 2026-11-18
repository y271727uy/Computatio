package com.y271727uy.computatio.algorithm.fastpath.model;

import com.y271727uy.computatio.algorithm.fastpath.search.*;
import com.y271727uy.computatio.algorithm.fastpath.execution.*;
import com.y271727uy.computatio.algorithm.fastpath.debug.*;
import com.y271727uy.computatio.algorithm.fastpath.cache.*;

/** Requested ground movement speed. Game-specific reasons for moving stay outside the core. */
public enum PathIntent {
    WALK,
    RUN
}

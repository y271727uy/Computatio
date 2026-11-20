package com.y271727uy.computatio.api.cycle;

import com.y271727uy.computatio.algorithm.cycle.CycleSolveLimits;

/** Structural limits for one cycle solve; {@code 0} means unlimited. */
public record CycleLimits(int maxKeys, int maxPatterns, int maxStates, int maxFirings, int maxSeedLadderSteps) {
    public static final CycleLimits DEFAULT = new CycleLimits(0, 0, 0, 0, 0);

    public CycleLimits {
        if (maxKeys < 0 || maxPatterns < 0 || maxStates < 0 || maxFirings < 0 || maxSeedLadderSteps < 0) {
            throw new IllegalArgumentException("Cycle solver limits must not be negative");
        }
    }

    static CycleSolveLimits internal(CycleLimits limits) {
        if (limits == null) return CycleSolveLimits.DEFAULT;
        return new CycleSolveLimits(limits.maxKeys, limits.maxPatterns, limits.maxStates, limits.maxFirings,
            limits.maxSeedLadderSteps);
    }
}

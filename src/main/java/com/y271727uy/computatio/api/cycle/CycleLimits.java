package com.y271727uy.computatio.api.cycle;

import com.y271727uy.computatio.algorithm.cycle.CycleMemoryBudget;
import com.y271727uy.computatio.algorithm.cycle.CycleSolveLimits;

/**
 * Limits for one cycle solve. The structural limits use {@code 0} for unlimited; {@code maxMemoryBytes} is the
 * solver's retained-memory allowance (NeoECO's {@code ecoPlanningMaxMemoryMiB}) and must be positive. Running out of
 * memory answers {@link CycleStatus#UNKNOWN_BUDGET}.
 */
public record CycleLimits(int maxKeys, int maxPatterns, int maxStates, int maxFirings, int maxSeedLadderSteps,
        long maxMemoryBytes) {
    /** 64 MiB, NeoECO's configuration default. */
    public static final long DEFAULT_MAX_MEMORY_BYTES = CycleMemoryBudget.DEFAULT_MAX_BYTES;
    public static final CycleLimits DEFAULT = new CycleLimits(0, 0, 0, 0, 0);

    public CycleLimits {
        if (maxKeys < 0 || maxPatterns < 0 || maxStates < 0 || maxFirings < 0 || maxSeedLadderSteps < 0) {
            throw new IllegalArgumentException("Cycle solver limits must not be negative");
        }
        if (maxMemoryBytes < 1) throw new IllegalArgumentException("Memory allowance must be positive");
    }

    /** Structural limits with the default memory allowance. */
    public CycleLimits(int maxKeys, int maxPatterns, int maxStates, int maxFirings, int maxSeedLadderSteps) {
        this(maxKeys, maxPatterns, maxStates, maxFirings, maxSeedLadderSteps, DEFAULT_MAX_MEMORY_BYTES);
    }

    /** The same limits with another memory allowance. */
    public CycleLimits withMaxMemoryBytes(long bytes) {
        return new CycleLimits(maxKeys, maxPatterns, maxStates, maxFirings, maxSeedLadderSteps, bytes);
    }

    static CycleSolveLimits internal(CycleLimits limits) {
        if (limits == null) return CycleSolveLimits.DEFAULT;
        return new CycleSolveLimits(limits.maxKeys, limits.maxPatterns, limits.maxStates, limits.maxFirings,
            limits.maxSeedLadderSteps);
    }

    static long memory(CycleLimits limits) {
        return limits == null ? DEFAULT_MAX_MEMORY_BYTES : limits.maxMemoryBytes;
    }
}

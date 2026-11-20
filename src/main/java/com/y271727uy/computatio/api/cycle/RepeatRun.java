package com.y271727uy.computatio.api.cycle;

import java.math.BigInteger;

/**
 * One entry of a compact plan: when {@code repetitions > 1}, the last {@code repeatWidth} entries ending here form a
 * circuit that runs {@code repetitions} laps; otherwise {@code repeatWidth} is 1.
 */
public interface RepeatRun {
    int repeatWidth();

    BigInteger repetitions();
}

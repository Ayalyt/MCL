package org.example.words;

/**
 * 时间字类型标记
 */
public enum WordType {
    DELAY_TIMED,          // (σ, t)
    CLOCK_TIMED,          // (σ, v)
    REGION_TIMED,         // (σ, [v])
    RESET_DELAY_TIMED,    // (σ, t, b)
    RESET_CLOCK_TIMED,    // (σ, v, b)
    RESET_REGION_TIMED    // (σ, [v], b)
}

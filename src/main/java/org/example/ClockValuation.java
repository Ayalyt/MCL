package org.example;

import lombok.Getter;
import org.example.region.Region;
import org.example.utils.Rational;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 表示多时钟系统的时钟赋值向量（优化版，含区域比较缓存）
 * @author Ayalyt
 */
public final class ClockValuation {
    @Getter
    private final SortedMap<Clock, Rational> clockValuation; // 时钟 → 值

    // ---------------------------
    // 构造方法
    // ---------------------------
    public ClockValuation(SortedMap<Clock, Rational> valuation) {
        this.clockValuation = new TreeMap<>(valuation);
    }

    public ClockValuation copy() {
        return new ClockValuation(new TreeMap<>(this.clockValuation));
    }

    /**
     * 静态工厂方法：创建全零时钟赋值
     * @param clocks 所有时钟集合
     */
    public static ClockValuation zero(Collection<Clock> clocks) {
        SortedMap<Clock, Rational> vals = new TreeMap<>();
        clocks.forEach(clock -> vals.put(clock, Rational.ZERO));
        return new ClockValuation(vals);
    }

    // ---------------------------
    // 核心功能方法
    // ---------------------------
    public Set<Clock> getClocks() {
        return Collections.unmodifiableSet(clockValuation.keySet());
    }

    public Rational getValue(Clock clock) {
        if ("x_0".equals(clock.getName())){
            return Rational.ZERO;
        }
        return Optional.ofNullable(clockValuation.get(clock))
                .orElseThrow(() -> new NoSuchElementException("Clock " + clock + "未找到"));
    }

    public ClockValuation delay(Rational delay) {
        SortedMap<Clock, Rational> newVals = new TreeMap<>();
        clockValuation.forEach((clock, val) -> {
             if (!"x0".equals(clock.getName())) {
                 newVals.put(clock, val.add(delay));
        }});
        return new ClockValuation(newVals);
    }

    /**
     * @param resets
     */
    public ClockValuation reset(Set<Clock> resets) {
        SortedMap<Clock, Rational> newVals = new TreeMap<>(clockValuation);
        resets.forEach(clock -> {
            if (newVals.containsKey(clock)) {
                newVals.put(clock, Rational.ZERO);
            } else{
                throw new IllegalArgumentException("clock" + clock.getName()+ "未定义," + newVals.keySet());
            }
        });
        return new ClockValuation(newVals);
    }

    // ---------------------------
    // 区域相关方法
    // ---------------------------
    public boolean isInRegion(Region region) {
        return region.contains(this);
    }

    public Region toRegion(ClockConfiguration config) {
        return Region.fromValuation(this, config);
    }

    // ---------------------------
    // 内部工具方法
    // ---------------------------
    public boolean isFractionZero(Clock clock) {
        Rational val = getValue(clock);
        return val.isZero();
    }

    public Rational getFraction(Clock clock) {
        Rational val = getValue(clock);
        return val.subtract(Rational.valueOf(val.intValue()));
    }

    // ---------------------------
    // 重写Object方法
    // ---------------------------
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClockValuation that)) {
            return false;
        }
        return clockValuation.equals(that.clockValuation);
    }

    @Override
    public int hashCode() {
        return clockValuation.hashCode();
    }

    @Override
    public String toString() {
        return clockValuation.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().toString())
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
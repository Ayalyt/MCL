package org.example.region;

import lombok.Getter;
import org.example.Clock;
import org.example.ClockConfiguration;
import org.example.ClockValuation;
import org.example.utils.Rational;

import java.util.*;
import java.util.stream.Collectors;

@Getter
public final class Region {
    //==================== 不可变成员 ====================
    private final Map<Clock, Integer> integerParts;    // 时钟 → ⌊v(c)⌋
    private final Set<Clock> zeroFractionClocks;      // frac(v(c))=0的时钟
    private final List<ClockFraction> fractionOrder;  // 非零小数时钟的排序
    private final ClockConfiguration config;         // 各时钟的κ值配置
    private final int hashCode;                       // 预计算的哈希值

    //==================== 构造方法 ====================
    private Region(Map<Clock, Integer> integerParts,
                   Set<Clock> zeroFractionClocks,
                   List<ClockFraction> fractionOrder,
                   ClockConfiguration config) {
        this.integerParts = Map.copyOf(integerParts);
        this.zeroFractionClocks = Set.copyOf(zeroFractionClocks);
        this.fractionOrder = List.copyOf(fractionOrder);
        this.config = config;
        this.hashCode = computeHashCode();
    }
    public Region(Region other) {
        this(Map.copyOf(other.integerParts), Set.copyOf(other.zeroFractionClocks),
                List.copyOf(other.fractionOrder), other.config
        );
    }

    //==================== 工厂方法 ====================
    public static Region fromValuation(ClockValuation v, ClockConfiguration config) {
        // 1. 计算整数部分
        Map<Clock, Integer> intParts = computeIntegerParts(v, config);

        // 2. 确定零小数时钟
        Set<Clock> zeroFracClocks = computeZeroFractionClocks(v, config, intParts);

        // 3. 计算非零小数时钟顺序
        List<ClockFraction> fractions = computeFractionOrder(v, config, intParts, zeroFracClocks);

        return new Region(intParts, zeroFracClocks, fractions, config);
    }

    //==================== 核心方法 ====================
    public ClockValuation buildValuation() {
        SortedMap<Clock, Rational> values = new TreeMap<>();

        // 1. 设置整数部分
        integerParts.forEach((clock, intPart) ->
                values.put(clock, Rational.valueOf(intPart)));

        // 2. 处理零小数时钟
        zeroFractionClocks.forEach(clock ->
                values.put(clock, values.get(clock).add(Rational.ZERO)));

        // 3. 设置非零小数（保持顺序）
        if (!fractionOrder.isEmpty()) {
            Rational fractionStep = Rational.ONE.divide(Rational.valueOf(fractionOrder.size() + 1));
            for (int i = 0; i < fractionOrder.size(); i++) {
                Clock clock = fractionOrder.get(i).clock();
                Rational increment = fractionStep.multiply(Rational.valueOf(i + 1));
                values.put(clock, values.get(clock).add(increment));
            }
        }

        return new ClockValuation(values);
    }

    public boolean contains(ClockValuation v) {
        // 1. 检查时钟集合一致性
        if (!v.getClocks().equals(integerParts.keySet())) {
            return false;
        }

        // 2. 验证整数部分
        if (!checkIntegerParts(v)) {
            return false;
        }

        // 3. 验证小数部分
        return checkFractionalParts(v);
    }

    //==================== 辅助方法 ====================
    private boolean checkIntegerParts(ClockValuation v) {
        for (Map.Entry<Clock, Integer> entry : integerParts.entrySet()) {
            Clock clock = entry.getKey();
            int intPart = v.getValue(clock).intValue();
            int kappa = config.getClockKappa(clock);

            if (intPart != entry.getValue()) {
                // 处理超过κ的情况（论文条件1）
                if (intPart <= kappa || entry.getValue() <= kappa) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean checkFractionalParts(ClockValuation v) {
        // 检查零小数时钟
        for (Clock clock : zeroFractionClocks) {
            if (!v.isFractionZero(clock)) {
                return false;
            }
        }

        // 检查非零小数顺序
        List<ClockFraction> actualFractions = getActualFractions(v);
        if (actualFractions.size() != fractionOrder.size()) {
            return false;
        }

        for (int i = 0; i < fractionOrder.size(); i++) {
            if (!fractionOrder.get(i).clock().equals(actualFractions.get(i).clock())) {
                return false;
            }
        }
        return true;
    }

    private List<ClockFraction> getActualFractions(ClockValuation v) {
        return integerParts.keySet().stream()
                .filter(clock -> !zeroFractionClocks.contains(clock))
                .filter(clock -> integerParts.get(clock) <= config.getClockKappa(clock))
                .map(clock -> new ClockFraction(clock, v.getFraction(clock)))
                .sorted(Comparator.comparing(ClockFraction::fraction))
                .toList();
    }

    //==================== 静态辅助方法 ====================
//    private static Map<Clock, Integer> computeIntegerParts(ClockValuation v, ClockConfiguration config) {
//        return v.getClocks().stream()
//                .collect(Collectors.toMap(
//                        clock -> clock,
//                        clock -> {
//                            Rational val = v.getValue(clock);
//                            int floorValue = val.intValue();
//                            int maxConstant = config.getClockKappa(clock);
//                            return floorValue > maxConstant ? maxConstant + 1 : floorValue;
//                        }
//                ));
//    }
    private static Map<Clock, Integer> computeIntegerParts(ClockValuation v, ClockConfiguration config) {
        Map<Clock, Integer> result = new HashMap<>();

        for (Clock clock : v.getClocks()) {
            Rational val = v.getValue(clock);
            int floorValue = val.intValue();
            int maxConstant = config.getClockKappa(clock);
            int finalValue = floorValue > maxConstant ? maxConstant + 1 : floorValue;

            result.put(clock, finalValue);
        }

        return result;
    }

    private static Set<Clock> computeZeroFractionClocks(ClockValuation v,
                                                        ClockConfiguration config,
                                                        Map<Clock, Integer> intParts) {
        return v.getClocks().stream()
                .filter(clock -> {
                    int kappa = config.getClockKappa(clock);
                    return intParts.get(clock) <= kappa && v.isFractionZero(clock);
                })
                .collect(Collectors.toSet());
    }

    private static List<ClockFraction> computeFractionOrder(ClockValuation v,
                                                            ClockConfiguration config,
                                                            Map<Clock, Integer> intParts,
                                                            Set<Clock> zeroFracClocks) {
        return v.getClocks().stream()
                .filter(clock -> {
                    int kappa = config.getClockKappa(clock);
                    return intParts.get(clock) <= kappa && !zeroFracClocks.contains(clock);
                })
                .map(clock -> new ClockFraction(clock, v.getFraction(clock)))
                .sorted(Comparator.comparing(ClockFraction::fraction))
                .toList();
    }

    public Clock getClockFromClockFraction(int i){
        return this.fractionOrder.get(i).clock();
    }

    //==================== 内部类 ====================
    static final class ClockFraction {
        private final Clock clock;
        private final Rational fraction;

        ClockFraction(Clock clock, Rational fraction) {
            this.clock = clock;
            this.fraction = fraction;
        }

        Clock clock() { return clock; }
        Rational fraction() { return fraction; }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ClockFraction that)) {
                return false;
            }
            return that.fraction.compareTo(fraction) == 0 &&
                    clock.equals(that.clock);
        }

        @Override
        public int hashCode() {
            return Objects.hash(clock, fraction);
        }
    }

    //==================== Object方法 ====================
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Region region)) {
            return false;
        }
        return integerParts.equals(region.integerParts) &&
                zeroFractionClocks.equals(region.zeroFractionClocks) &&
                fractionOrder.equals(region.fractionOrder);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private int computeHashCode() {
        return Objects.hash(integerParts, zeroFractionClocks, fractionOrder);
    }

    @Override
    public String toString() {
        return String.format("Region[int=%s, zero=%s, frac=%s]",
                integerParts, zeroFractionClocks,
                fractionOrder.stream()
                        .map(f -> f.clock() +"=" + f.fraction().toString())
                        .collect(Collectors.joining("<")));
    }
}
package org.example.region;

import lombok.Getter;
import org.example.Clock;
import org.example.ClockConfiguration;
import org.example.ClockValuation;
import org.example.constraint.AtomConstraint;
import org.example.constraint.Constraint;
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

    /**
     * 将当前 ClockRegion 转换为一个 Constraint 对象 (合取范式)。
     * 这个 Constraint 代表了所有满足此区域定义的时钟赋值。
     *
     * @return 对应的 Constraint 对象。
     */
    public Constraint toConstraint(boolean needFraction) {
        Set<AtomConstraint> atoms = new HashSet<>();

        // 1. 处理整数部分约束
        for (Map.Entry<Clock, Integer> entry : integerParts.entrySet()) {
            Clock c = entry.getKey();
            if (c.isZeroClock()) {
                continue;
            }

            int intPart = entry.getValue();
            int kappa = config.getClockKappa(c);

            if (intPart > kappa) { // 对应 v(c) > kappa
                // 添加 c > kappa (即 0 - c < -kappa)
                atoms.add(AtomConstraint.greaterThan(c, Rational.valueOf(kappa)));
            } else { // 对应 floor(v(c)) == intPart
                // 添加 c >= intPart
                atoms.add(AtomConstraint.greaterEqual(c, Rational.valueOf(intPart)));
            }
        }

        // 2. 处理零小数部分约束 (frac(c) = 0)
        for (Clock c : zeroFractionClocks) {
            if (c.isZeroClock()) {
                continue;
            }
            // 添加 c = intPart(c) 的约束。这不能直接用单个差分约束表达。
            // 我们用两个差分约束来界定: c >= intPart(c) 和 c <= intPart(c)
            // c >= intPart(c) 在步骤 1 中已添加 (对于 intPart <= kappa 的情况)
            // 需要添加 c <= intPart(c) (即 c - 0 <= intPart(c))
            Integer intPart = integerParts.get(c);
            // 只有当 c <= kappa 时 frac(c)=0 才有意义
            if (intPart != null && intPart <= config.getClockKappa(c)) {
                atoms.add(AtomConstraint.lessEqual(c, Rational.valueOf(intPart)));
            }
        }


        // 3. 处理非零小数部分排序约束 0 < frac(c_i1) < frac(c_i2) < ... < 1
        Clock lastClock = null; // 用于处理 c_i - c_j > floor(c_i) - floor(c_j)
        for (int i = 0; i < fractionOrder.size(); i++) {
            Region.ClockFraction currentFraction = fractionOrder.get(i);
            Clock currentClock = currentFraction.clock();
            if (currentClock.isZeroClock()) {
                continue; // 不应该发生，但防御性检查
            }

            int intPartCurrent = integerParts.get(currentClock);

            atoms.add(AtomConstraint.lessThan(Clock.getZeroClock(), currentClock, Rational.valueOf(intPartCurrent).negate()));

            // 处理与上一个非零小数时钟的关系 frac(c_current) > frac(c_last)
            if (i > 0 && lastClock != null && needFraction) {
                // c_current - c_last > floor(c_current) - floor(c_last)
                int intPartLast = integerParts.get(lastClock);
                Rational diffIntParts = Rational.valueOf(intPartCurrent - intPartLast);
                // 添加 c_last - c_current < -diffIntParts (即 c_current - c_last > diffIntParts)
                atoms.add(AtomConstraint.lessThan(lastClock, currentClock, diffIntParts.negate()));
            }

            // 处理与零小数时钟的关系 frac(c_current) > frac(c_zero) = 0
            for (Clock zeroClock : zeroFractionClocks) {
                if (zeroClock.isZeroClock()) {
                    continue;
                }
                // c_current - c_zero > floor(c_current) - floor(c_zero)
                int intPartZero = integerParts.get(zeroClock);
                Rational diffIntParts = Rational.valueOf(intPartCurrent - intPartZero);
                // 添加 c_zero - c_current < -diffIntParts (即 c_current - c_zero > diffIntParts)
                atoms.add(AtomConstraint.lessThan(zeroClock, currentClock, diffIntParts.negate()));
            }

            lastClock = currentClock;
        }

        if (needFraction) {
            // 4. 处理零小数时钟之间的关系 frac(c_i) = frac(c_j) = 0
            List<Clock> zeroClocksList = new ArrayList<>(zeroFractionClocks);
            for (int i = 0; i < zeroClocksList.size(); i++) {
                Clock ci = zeroClocksList.get(i);
                if (ci.isZeroClock()) {
                    continue;
                }
                for (int j = i + 1; j < zeroClocksList.size(); j++) {
                    Clock cj = zeroClocksList.get(j);
                    if (cj.isZeroClock()) {
                        continue;
                    }

                    // c_i - c_j = floor(c_i) - floor(c_j) (整数)
                    int intPartI = integerParts.get(ci);
                    int intPartJ = integerParts.get(cj);
                    Rational diffIntParts = Rational.valueOf(intPartI - intPartJ);
                    // 添加 c_i - c_j <= diffIntParts
                    atoms.add(AtomConstraint.lessEqual(ci, cj, diffIntParts));
                    // 添加 c_j - c_i <= -diffIntParts
                    atoms.add(AtomConstraint.lessEqual(cj, ci, diffIntParts.negate()));
                }
            }
        }

        return Constraint.of(new HashSet<>(this.config.getClocks()), atoms);
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

    public record ClockFraction(Clock clock, Rational fraction) {

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
package org.example.region;

import com.microsoft.z3.*;
import org.example.Clock;
import org.example.ClockValuation;
import org.example.constraint.AtomConstraint;
import org.example.constraint.Constraint;
import org.example.utils.Rational;
import org.example.utils.Z3Converter;
import org.example.words.ResetClockTimedWord;

import java.util.*;

/**
 * @author Ayalyt
 */
public class RegionSolver {

    public RegionSolver() {
    }


    /**
     * 查找最小的非负延迟 d >= 0，使得 v_curr 延迟 d 后进入目标区域 R_target。
     * 这对应论文算法1中所需的 Solve 函数功能。
     *
     * @param v_curr   当前的时钟赋值。
     * @param R_target 目标区域。
     * @return 最小的非负延迟 Rational 值；如果不存在这样的延迟，则返回 null。
     */
    public static Optional<Rational> solveDelay(ClockValuation v_curr, Region R_target) {
        // 初始化全局最小延迟为 0
        Rational d_min = Rational.ZERO;

        try {
            // 遍历目标区域配置中定义的所有时钟
            for (Clock c : R_target.getConfig().getClocks()) {
                // 跳过特殊的 x0 时钟（如果存在且不需要处理）
                if (c.isZeroClock()) {
                    continue;
                }

                // 获取当前时钟 c 的值
                Rational val_curr = v_curr.getValue(c);
                // 获取目标区域对时钟 c 的整数部分要求
                Integer k_target_int = R_target.getIntegerParts().get(c);
                if (k_target_int == null) {
                    // 防御性编程：如果区域定义不完整
                    throw new NoSuchElementException("时钟 " + c + " 在目标区域整数部分定义中未找到。");
                }
                Rational k_target = Rational.valueOf(k_target_int); // 转换为 Rational
                // 获取时钟 c 的上界 kappa
                int kappa_int = R_target.getConfig().getClockKappa(c);
                Rational kappa = Rational.valueOf(kappa_int); // 转换为 Rational

                // 初始化当前时钟 c 所需的最小延迟为 0
                Rational d_lower_c = Rational.ZERO;

                // --- 约束 1: 整数部分下界 ---
                if (k_target_int > kappa_int) { // 对应区域定义 v(c) > kappa 的情况
                    // 需要 val_curr + d > kappa
                    // 计算到达边界 kappa 所需的最小延迟 (可能为负，取 max(0, ...))
                    d_lower_c = Rational.max(Rational.ZERO, kappa.subtract(val_curr));
                    // 注意：这里计算的是到达边界的延迟。严格大于 '>' 由后续 contains 检查。
                } else { // 对应区域定义 floor(v(c)) == k_target 的情况
                    // 这意味着需要 val_curr + d >= k_target
                    // 计算到达下界 k_target 所需的最小延迟
                    d_lower_c = Rational.max(Rational.ZERO, k_target.subtract(val_curr));
                }

                // --- 约束 2: 零小数部分 (仅当 k_target <= kappa 时适用) ---
                if (R_target.getZeroFractionClocks().contains(c)) {
                    // 需要 val_curr + d 等于某个整数 I，且这个整数必须是 k_target
                    Rational target_integer_val = k_target; // 目标整数值就是 k_target

                    // 计算精确达到 target_integer_val 所需的最小非负延迟
                    Rational d_to_target_int = Rational.max(Rational.ZERO, target_integer_val.subtract(val_curr));

                    // 检查应用此延迟后是否真的得到目标整数
                    Rational val_at_d = val_curr.add(d_to_target_int);

                    if (val_at_d.compareTo(target_integer_val) == 0 && val_at_d.isInteger()) {
                        // 如果精确到达了目标整数 k_target
                        d_lower_c = Rational.max(d_lower_c, d_to_target_int);
                    } else {
                        // 如果无法精确到达 k_target (可能因为小数部分或已超过)
                        // 则需要找到第一个可达的、且 >= k_target 的整数
                        // ceil(val_curr) 是 >= val_curr 的最小整数
                        Rational ceil_val_curr = val_curr.isInteger() ? val_curr : Rational.valueOf(val_curr.intValue() + 1);
                        // 第一个可达的目标整数是 target_integer_val 和 ceil_val_curr 中的较大者
                        Rational first_reachable_int = Rational.max(target_integer_val, ceil_val_curr);

                        // 计算到达这个 first_reachable_int 所需的最小非负延迟
                        Rational d_to_first_int = Rational.max(Rational.ZERO, first_reachable_int.subtract(val_curr));
                        // 更新当前时钟的最小延迟要求
                        d_lower_c = Rational.max(d_lower_c, d_to_first_int);
                    }
                }

                // 更新全局最小延迟 (取所有时钟要求的最大值)
                d_min = Rational.max(d_min, d_lower_c);
            }

        } catch (NoSuchElementException e) {
            // 处理获取时钟值或区域信息时可能发生的错误
            System.err.println("计算 d_min 时出错: " + e.getMessage());
            return Optional.empty(); // 或者根据需要进行其他错误处理
        }

        // --- 验证步骤 ---
        // 计算应用全局最小延迟 d_min 后的时钟赋值
        ClockValuation v_check = v_curr.delay(d_min);

        // 使用 Region 的 contains 方法检查 v_check 是否完全满足目标区域的所有条件
        // (包括整数部分、零小数、小数排序、严格不等式等)
        if (R_target.contains(v_check)) {
            // 如果 contains 返回 true，说明 d_min 是一个有效的延迟解
            return Optional.ofNullable(d_min);
        } else {
            // 如果 contains 返回 false，说明即使满足了所有计算出的下界/零小数要求，
            // 最终结果 v_check 仍然不满足区域的某些约束（如隐式上界、严格小数排序、或正好在严格边界上）。
            // 这意味着不存在满足条件的非负延迟 d。
            return Optional.empty();
        }
    }

    public static Optional<Rational> solveDelay(ClockValuation currentValues, Constraint guard){
        Rational maxLowerBound = Rational.ZERO;
        boolean strictLowerBoundExists = false;
        Rational maxStrictLowerBound = Rational.ZERO;

        for (AtomConstraint ac : guard.getConstraints()) {
            Clock c1 = ac.getClock1();
            Clock c2 = ac.getClock2();
            Rational V = ac.getUpperbound();
            boolean isStrict = !ac.isClosed();

            if (V.equals(Rational.INFINITY)) {
                continue;
            }

            Rational val1 = c1.isZeroClock() ? Rational.ZERO : currentValues.getValue(c1);
            Rational val2 = c2.isZeroClock() ? Rational.ZERO : currentValues.getValue(c2);

            Rational lowerBound = null;
            boolean currentIsStrict = false;

            if (!c1.isZeroClock() && !c2.isZeroClock()) {
                if (isStrict) { // val1 - val2 < V
                    if (val1.subtract(val2).compareTo(V) >= 0) {
                        return Optional.of(Rational.INFINITY);
                    }
                } else { // val1 - val2 <= V
                    if (val1.subtract(val2).compareTo(V) > 0) {
                        return Optional.of(Rational.INFINITY);
                    }
                }
                continue;
            } else if (!c1.isZeroClock() && c2.isZeroClock()) {
                continue;
            } else if (c1.isZeroClock() && !c2.isZeroClock()) {
                // val1 - (val2 + d) <= V  =>  -d <= V - val1 + val2  =>  d >= val1 - val2 - V
                lowerBound = val1.subtract(val2).subtract(V);
                currentIsStrict = isStrict; // 继承原子约束的严格性
            } else { // c1.isZeroClock() && c2.isZeroClock()
                if (isStrict) { // 0 < V
                    if (Rational.ZERO.compareTo(V) >= 0) {
                        return Optional.of(Rational.INFINITY);
                    }
                } else { // 0 <= V
                    if (Rational.ZERO.compareTo(V) > 0) {
                        return Optional.of(Rational.INFINITY);
                    }
                }
                continue;
            }

            if (lowerBound != null) {
                if (currentIsStrict) {
                    strictLowerBoundExists = true;
                    if (lowerBound.compareTo(maxStrictLowerBound) > 0) {
                        maxStrictLowerBound = lowerBound;
                    }
                } else {
                    if (lowerBound.compareTo(maxLowerBound) > 0) {
                        maxLowerBound = lowerBound;
                    }
                }
            }
        }

        // 确定最终延迟
        if (strictLowerBoundExists && maxStrictLowerBound.compareTo(maxLowerBound) >= 0) {
            return Optional.of(maxStrictLowerBound.add(Rational.EPSILON));// 简化处理，直接返回最大严格下界
        } else {
            // 最小延迟是 maxLowerBound
            return maxLowerBound.compareTo(Rational.ZERO) >= 0 ? Optional.of(maxLowerBound.add(Rational.EPSILON)) : Optional.of(Rational.ZERO);
        }
    }

    public ResetClockTimedWord toResetClockTimedWord(Set<Clock> clocks) {
        try (Context ctx = new Context()){
            // 创建延迟变量d
            RealExpr d = ctx.mkRealConst("d");
            List<BoolExpr> constraints = new ArrayList<>();
            Map<Clock, ArithExpr<RealSort>> fracExpressions = new HashMap<>();
            // 处理每个时钟的约束
            for (Clock clock : clocks) {
                Rational currentVal = Rational.ZERO;
                int kappa = 0;
                Integer targetInt = 0;
                // 创建当前时钟值 + d的表达式
                ArithExpr<RealSort> currentExpr = ctx.mkReal(String.format("%.6f", currentVal));
                ArithExpr<RealSort> vPlusD = ctx.mkAdd(currentExpr, d);
                // 处理整数部分约束
                if (targetInt <= kappa) {
                    // 严格匹配整数部分
                    IntExpr floor = ctx.mkReal2Int(vPlusD);
                    constraints.add(ctx.mkEq(floor, ctx.mkInt(targetInt)));
                    // 处理小数部分
                    if (targetInt == 0) {
                        // 小数部分必须为0
                        constraints.add(ctx.mkEq(
                                ctx.mkSub(vPlusD, floor),
                                ctx.mkReal(0)
                        ));
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
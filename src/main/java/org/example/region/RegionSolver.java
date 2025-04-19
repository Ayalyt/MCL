package org.example.region;

import com.microsoft.z3.*;
import org.example.Clock;
import org.example.ClockValuation;
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
     * 查找一个非负延迟 'd'，使得 currentValuation + d 位于 targetRegion 内。
     * 使用内部管理的 Z3 Context 和 Solver。
     *
     * @param currentValuation 起始时钟赋值 (Map<Clock, Rational>)。
     * @param targetRegion     目标区域定义。
     * @return 如果找到，返回包含 Rational 延迟 'd' 的 Optional，否则返回 Optional.empty()。
     */
    public Optional<Rational> solveDelay(ClockValuation currentValuation, Region targetRegion) {
        Objects.requireNonNull(currentValuation, "当前时钟赋值不能为空");
        Objects.requireNonNull(targetRegion, "目标区域不能为空");

        try (Context ctx = new Context()) {
            Solver solver = ctx.mkSolver();
            // 1. 保存当前求解器状态

            // 2. 延迟变量d
            RealExpr d = ctx.mkRealConst("d");

            // 所有约束的列表
            List<BoolExpr> constraints = new ArrayList<>();

            // 3. 延迟 d 必须大于等于 0
            constraints.add(ctx.mkGe(d, Z3Converter.rational2Ratnum(Rational.ZERO, ctx)));

            // 存储小数部分表达式以便后续排序
            Map<Clock, ArithExpr<RealSort>> fracExpressions = new HashMap<>();

            // 4. 为每个时钟处理约束
            for (Clock clock : currentValuation.getClocks()) {
                Rational currentVal = currentValuation.getValue(clock);

                // 获取该时钟在目标区域的约束信息
                int kappa = targetRegion.getConfig().getClockKappa(clock); // 时钟上限
                Integer targetInt = targetRegion.getIntegerParts().get(clock); // 目标整数部分 (如果 > kappa 则为 null)

                // Z3 表达式: currentVal + d
                RatNum currentExprZ3 = Z3Converter.rational2Ratnum(currentVal, ctx); // 使用 Rational 类精确转换
                ArithExpr<RealSort> vPlusD = ctx.mkAdd(currentExprZ3, d);

                // --- 整数部分约束 ---
                IntExpr floorVPlusD = ctx.mkReal2Int(vPlusD); // Z3 的 floor 函数: floor(v + d)

                if (targetInt != null) { // 情况 1: 目标整数部分 targetInt <= kappa
                    // 约束：floor(v + d) == targetInt
                    constraints.add(ctx.mkEq(floorVPlusD, ctx.mkInt(targetInt)));

                    // --- 小数部分约束 (仅当 targetInt <= kappa 时需要) ---
                    // 计算小数部分: (v + d) - floor(v + d)
                    ArithExpr<RealSort> fracPart = ctx.mkSub(vPlusD, ctx.mkInt2Real(floorVPlusD));

                    if (targetRegion.getZeroFractionClocks().contains(clock)) {
                        // 情况 1a: 小数部分必须为 0
                        constraints.add(ctx.mkEq(fracPart, Z3Converter.rational2Ratnum(Rational.ZERO, ctx)));
                    } else {
                        // 情况 1b: 小数部分非零，存储表达式用于排序
                        // 添加约束 fracPart > 0 (因为非零)
                        constraints.add(ctx.mkGt(fracPart, Z3Converter.rational2Ratnum(Rational.ZERO, ctx)));
                        fracExpressions.put(clock, fracPart); // 存储小数部分表达式
                    }
                } else {
                    // 情况 2: 目标整数部分 > kappa (targetInt 为 null)
                    // 约束：floor(v + d) > kappa
                    constraints.add(ctx.mkGt(floorVPlusD, ctx.mkInt(kappa)));
                    // 当值超过 kappa 时，不需要小数部分的约束
                }
            }

            // 5. 处理小数部分的顺序约束
            List<Region.ClockFraction> fractionOrder = targetRegion.getFractionOrder();
            for (int i = 0; i < fractionOrder.size() - 1; i++) {
                Clock c1 = fractionOrder.get(i).clock();
                Clock c2 = fractionOrder.get(i + 1).clock();

                ArithExpr<RealSort> frac1 = fracExpressions.get(c1);
                ArithExpr<RealSort> frac2 = fracExpressions.get(c2);

                // 添加严格小于约束：frac(c1) < frac(c2)
                // 仅当两个时钟都有非零小数部分才添加约束
                if (frac1 != null && frac2 != null) {
                    constraints.add(ctx.mkLt(frac1, frac2));
                } else {
                    System.err.println("警告: 排序列表中的时钟 (" + c1 + " 或 " + c2 + ") 没有非零小数部分表达式。");
                }
            }

            // 6. 将所有约束添加到求解器
            solver.add(constraints.toArray(new BoolExpr[0]));

            // 7. 检查约束是否可满足
            Status status = solver.check();

            // 8. 处理结果
            if (status == Status.SATISFIABLE) {
                Model model = solver.getModel();
                Expr<?> resultExpr = model.evaluate(d, false);

                // 9. 将 Z3 结果转换回 Rational 类型
                Rational delayResult;
                if (resultExpr instanceof RatNum) {
                    delayResult = Z3Converter.ratnum2Rational((RatNum) resultExpr);
                } else if (resultExpr instanceof IntNum) {
                    delayResult = Rational.valueOf(((IntNum) resultExpr).getInt());
                } else {
                    // 处理其他可能的返回类型（不太常见，但为了健壮性）
                    System.err.println("警告: Z3 为延迟 'd' 返回了非预期的类型: " + resultExpr.getClass() + "，尝试字符串转换。");
                    try {
                        delayResult = Rational.valueOf(resultExpr.toString());
                    } catch (NumberFormatException | ArithmeticException parseEx) {
                        System.err.println("错误: 无法将 Z3 结果 '" + resultExpr + "' 解析为 Rational。");
                        return Optional.empty();
                    }
                }

                return Optional.of(delayResult);
            } else {
                // 如果状态是 UNSATISFIABLE 或 UNKNOWN
                System.out.println("求解器状态: " + status);
                return Optional.empty();
            }
        } catch (Z3Exception e) {
            System.err.println("Z3 错误 (solveDelay): " + e.getMessage());
            e.printStackTrace();
            return Optional.empty();
        } catch (Exception e) {
            System.err.println("意外错误 (solveDelay): " + e.getMessage());
            e.printStackTrace();
            return Optional.empty();
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